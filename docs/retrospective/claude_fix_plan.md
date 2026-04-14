# Plan: Apply Code Review Fixes to `binance-quote-service`

## Context

A strict code review of `this repository` identified ~30 issues in a Spring Boot 3.3 / Java 21 take-home: WebSocket → in-memory map + bounded-queue batched JDBC writes. Three issues (#1 missing `wget`, #2 PID-1 signal handling, #3 health-down-on-backpressure) **each cause production data loss or container restart loops** — they directly contradict the project's central pitch of graceful, lossless ingestion. Two more (#4 per-message volatile write, #5 silent insert-count drop) are correctness bugs hiding behind plausible logging.

Goal: fix all identified issues, grouped into commit-sized batches, each leaving `mvn verify` green. Batches are ordered by severity so the user can stop after Batch 4 and still capture all the load-bearing fixes.

## Strategy

- **One commit per batch**, conventional prefix matching the existing history (`fix:`, `refactor:`, `docs:`, `test:`).
- **Tests-first for behavior changes**: add a failing test, then the fix.
- **Update `docs/design_decisions.md` in-place** with new ADR entries (DD-14+) using the existing format: `## DD-n — Title` with `Context / Decision / Alternatives / Consequences` sections.
- Verified during exploration: no existing Micrometer `Counter` usage and no existing JDBC exception helpers — both will be new patterns introduced by this plan.
- Verified during exploration: of the 8 public test-only getters on `BinanceWebSocketClient`, only `getOkHttpClient`, `getWebSocket`, `getClosedLatch` are unused by tests and can safely become package-private.

---

## Batch 1 — Container & deployment (Critical #1, #2; Low: Maven Wrapper, JAR name)

**Files:** `Dockerfile`, `docker-compose.yml`

1. **Switch build stage to official Maven image** — drop `apt-get install maven`:
   ```dockerfile
   FROM maven:3.9-eclipse-temurin-21 AS build
   WORKDIR /build
   COPY pom.xml .
   RUN mvn -B dependency:go-offline
   COPY src ./src
   RUN mvn -B clean package -DskipTests
   ```
   Adds the dependency-cache layer so source-only changes don't re-download deps.

2. **Replace hardcoded JAR name** with a glob (`Dockerfile:13`):
   ```dockerfile
   COPY --from=build /build/target/binance-quote-service-*.jar app.jar
   ```

3. **Fix PID-1 signal propagation** (`Dockerfile:17`) — switch to exec form so SIGTERM reaches the JVM and `server.shutdown: graceful` actually runs:
   ```dockerfile
   ENTRYPOINT ["java", "-jar", "app.jar"]
   ```
   `JAVA_OPTS` was only used to inject `-XX:+UseContainerSupport` and `-XX:MaxRAMPercentage=75.0` — bake those into a `JDK_JAVA_OPTIONS` env var instead, which the JVM honors directly without a shell:
   ```dockerfile
   ENV JDK_JAVA_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
   ```

4. **Fix the broken healthcheck** (`docker-compose.yml:35`). `eclipse-temurin:21-jre` has neither `wget` nor `curl`. Two options — pick **(a)** because it's smaller:
   - **(a)** Install `curl` in the runtime stage (one apt line, ~3MB) and `curl -fsS http://localhost:18080/actuator/health/liveness || exit 1`.
   - (b) Java-native: `CMD-SHELL` running a tiny inline `java -e` is not possible; would need a separate utility class.

   This also feeds Batch 2 — endpoint changes from `/actuator/health` to `/actuator/health/liveness`.

---

## Batch 2 — Liveness vs. readiness split (Critical #3)

**Files:** `application.yml`, `BinanceStreamHealthIndicator.java`, `PersistenceQueueHealthIndicator.java`, `docker-compose.yml`, `docs/design_decisions.md`

Backpressure and stale data are **readiness** signals, not **liveness**. As written, both flip `/actuator/health` to DOWN, which causes compose to restart the container and wipe in-memory state — the worst possible response to a transient overload.

1. **Add health groups** to `application.yml` under `management.endpoint.health` (probes are already enabled at line 36):
   ```yaml
   group:
     liveness:
       include: livenessState
     readiness:
       include: readinessState,binanceStream,persistenceQueue
   ```

2. **`BinanceStreamHealthIndicator.java:51-56`** — keep current logic; it's correct *as a readiness signal*. No code change needed once the group is in place.

3. **`PersistenceQueueHealthIndicator.java:40-45`** — soften:
   - Remove the `>0.95 → DOWN` branch entirely. Backpressure is never "down".
   - Use `OUT_OF_SERVICE` at >0.95 (orchestrators interpret as "stop sending traffic") and `UP with details` below that.

4. **`docker-compose.yml:35`** — point the healthcheck at the new liveness endpoint:
   ```
   curl -fsS http://localhost:18080/actuator/health/liveness || exit 1
   ```

5. **New ADR**: `docs/design_decisions.md` → `## DD-14 — Liveness vs. readiness split`. Document why the previous coupling was wrong and what each indicator means now.

6. **Update `DockerComposeSmokeTest.java:44-56`** to also assert that `/actuator/health/liveness` returns 200 and that `/actuator/health/readiness` exists (both should be UP in the smoke scenario).

---

## Batch 3 — WebSocket correctness & races (Critical #4; High #6, #7, #11, #12; Nit: test getter visibility)

**Files:** `BinanceWebSocketClient.java`, `BinanceWebSocketClientTest.java`

1. **First-message backoff reset** (#4, `BinanceWebSocketClient.java:251`). Add a field:
   ```java
   private final AtomicBoolean awaitingFirstMessage = new AtomicBoolean(false);
   ```
   Set `true` in `onOpen`. In `onMessage`, replace the unconditional reset with:
   ```java
   if (awaitingFirstMessage.compareAndSet(true, false)) {
     currentBackoffMs = MIN_BACKOFF_MS;
   }
   ```
   Eliminates the per-frame volatile write at ~500 msg/s and makes the docstring true.

2. **Reconnect race** (#6, line 312). Don't clear `reconnecting` from inside the scheduled task before the new socket is established. Move `reconnecting.set(false)` into `onOpen` (paired with `awaitingFirstMessage.set(true)` from #1). On reconnect failure (catch block), keep `reconnecting=true` and chain via a fresh `scheduler.schedule` rather than recursing — see #7.

3. **Reconnect catch-block recursion** (#7, lines 320-325). Replace direct `scheduleReconnect()` recursion with:
   ```java
   } catch (Exception e) {
     log.error("Reconnect attempt failed", e);
     increaseBackoff();
     // Re-arm via the scheduler instead of recursing on the current task
     reconnecting.set(false);
     scheduleReconnect();
   }
   ```
   Same effect, but the current scheduled task completes cleanly before the next is queued.

4. **Lag gauge "no data → 0" lie** (#11, lines 130-133). Return `Double.NaN` when `last == 0L`:
   ```java
   m -> {
     long last = m.get(symbol).get();
     return last == 0L ? Double.NaN : (double) (System.currentTimeMillis() - last);
   }
   ```
   Update the fleet-max gauge identically. Add a unit test asserting `NaN` before any frame is received and a real value after.

5. **`closedLatch` reassignment race** (#12, line 91). Convert to `AtomicReference<CountDownLatch>`:
   ```java
   private final AtomicReference<CountDownLatch> closedLatchRef = new AtomicReference<>();
   ```
   Set immediately before each `newWebSocket(...)` call (start + reconnect). In `shutdown()`, capture `CountDownLatch latch = closedLatchRef.get();` once into a local before awaiting.

6. **Tighten test-only API surface** (Low #20). Confirmed via exploration that `getOkHttpClient`, `getWebSocket`, `getClosedLatch` are not referenced by `BinanceWebSocketClientTest.java` — make them package-private (or delete them). The other 5 (`getScheduler`, `isReconnecting`, `isShuttingDown`, `getCurrentBackoffMs`, `getLastEventTimeBySymbol`, `isConnected`) are used by tests and stay.

7. **Remove no-op `@Order(1)`** (Medium #13, line 193). Delete annotation; keep the constructor's javadoc explanation of destruction-order reliance.

---

## Batch 4 — Repository correctness (Critical #5)

**Files:** `QuoteRepository.java`, `QuoteRepositoryIntegrationTest.java`

1. **Fix the inserted-count loop** (#5, lines 69-74). The PostgreSQL JDBC driver returns `Statement.SUCCESS_NO_INFO` (`-2`) for batch updates; the current `r > 0` filter discards these and underreports. Replace with:
   ```java
   import java.sql.Statement;
   ...
   int attempted = 0;
   for (int r : results) {
     if (r == Statement.EXECUTE_FAILED) continue;
     // r >= 0 (actual count) or SUCCESS_NO_INFO (-2): treat as "presumed inserted"
     attempted += (r >= 0) ? r : 1;
   }
   return attempted;
   ```
   And rename the local from `inserted` to `attempted` in the log line in `BatchPersistenceService.flush` to reflect the new semantics. Update Javadoc.

2. **New integration test** in `QuoteRepositoryIntegrationTest.java`: insert N rows including a duplicate, assert `attempted` reports correctly under PostgreSQL (Testcontainers).

---

## Batch 5 — Persistence robustness (High #8, #9; Medium #16, #17; Spec compliance #15)

**Files:** `BatchPersistenceService.java`, `BatchPersistenceServiceTest.java`, `docs/design_decisions.md`

1. **Move drainer thread start out of constructor** (#8, line 53). Mark `drainerThread` `final` and assign it in a new `@PostConstruct void start()`. Add a corresponding test that verifies enqueue-before-PostConstruct doesn't lose data (the queue is created in the constructor, so enqueue still works; the drainer just doesn't run until start).

2. **Smarter retry classification** (#9, lines 176-200). Add a helper:
   ```java
   private static boolean isTransientConnectionFailure(Throwable t) {
     for (Throwable cur = t; cur != null; cur = cur.getCause()) {
       if (cur instanceof org.springframework.jdbc.CannotGetJdbcConnectionException) return true;
       if (cur instanceof java.sql.SQLTransientConnectionException) return true;
       if (cur instanceof java.sql.SQLNonTransientConnectionException) return true;
     }
     return false;
   }
   ```
   In `flush(...)`, on transient failure: do **not** explode the batch; sleep with capped backoff and retry the whole batch up to N times. Only call `retryOneByOne` for non-connection errors (e.g. constraint violations on a poison row).

3. **Store capacity field** (#16, lines 75-90). Add `private final int queueCapacity;` initialized in the constructor. Replace all `queue.remainingCapacity() + queue.size()` arithmetic with `queueCapacity`. Eliminates racy math + 3 redundant computations per enqueue.

4. **Wake drainer at shutdown** (#17). In `shutdown()`, after setting `shuttingDown = true`, call `drainerThread.interrupt()` to break out of `queue.poll(flushMs, ...)` immediately. The existing `catch (InterruptedException) { break; }` handles it cleanly (combined with the final-drain logic that follows the loop).

5. **Drop counter as Micrometer Counter** (#15). Inject `MeterRegistry` and register:
   ```java
   this.droppedCounter = Counter.builder("binance.quotes.dropped.total")
       .description("Quotes dropped due to persistence backpressure")
       .register(meterRegistry);
   ```
   Increment in the drop branch. Keep the existing `AtomicLong droppedCount` (used by health indicator) but increment the counter alongside. This is the project's first Micrometer counter; pattern follows the existing `Gauge.builder` style in `BinanceWebSocketClient`.

6. **New ADR**: `## DD-15 — "Drop oldest" is a deliberate spec deviation`. Spec §2 says "complete history"; we drop on overflow. Document why (no blocking on WS thread = DD-6) and how it's surfaced (the new counter) so an operator can alert on `rate(binance_quotes_dropped_total) > 0`.

---

## Batch 6 — Parser & service guards (High #10; Medium #14)

**Files:** `QuoteMessageParser.java`, `QuoteService.java`, `QuoteMessageParserTest.java`, `QuoteServiceTest.java`

1. **Symbol allowlist in parser** (#10). Inject `AppProperties`:
   ```java
   private final Set<String> allowedSymbols;
   public QuoteMessageParser(AppProperties props) {
     this.allowedSymbols = Set.copyOf(props.getSymbols());
     ...
   }
   ```
   After extracting `symbol`, reject and log WARN if `!allowedSymbols.contains(symbol)`. Add a parser test for unknown symbols. This also closes the secondary leak in `BinanceWebSocketClient.java:254` where `computeIfAbsent` would silently create an unregistered map entry.

2. **Strict monotonic upsert** (#14, `QuoteService.java:36`). Change `>=` to `>`:
   ```java
   quotes.merge(quote.symbol(), quote,
       (existing, incoming) -> incoming.updateId() > existing.updateId() ? incoming : existing);
   ```
   `merge` over `compute` is slightly clearer; identical atomicity. Update `QuoteServiceTest.java` to assert that an idempotent re-send (same updateId) does not replace the stored quote (use a sentinel field like a different `receivedAt` to detect overwrite).

---

## Batch 7 — Config cleanup (Medium #18, #19)

**Files:** `AppProperties.java`, `application.yml`, `application-dev.yml`

1. **Consolidate `AppProperties` validation** (#18). Delete the `@PostConstruct void validate()` and use JSR-303 only:
   ```java
   @NotNull
   @Size(min = 10, max = 10)
   private List<@Pattern(regexp = "^[A-Z]+USDT$") String> symbols;
   ```
   Single source of truth. Existing `AppPropertiesTest.java` cases must still pass; the failure messages will change format, so update assertions.

2. **DEBUG logging out of base profile** (#19). Move `logging.level.com.quant.binancequotes: DEBUG` from `application.yml:42` to `application-dev.yml`. Base `application.yml` keeps `root: INFO` only.

---

## Batch 8 — Schema, docs, nits (Low: #21, #22, others)

**Files:** `src/main/resources/schema.sql`, `QuoteController.java`, `Quote.java`, `docs/design_decisions.md`

1. **Add CHECK constraints** to `schema.sql` so the DB enforces all parser invariants (the docs claim it does):
   ```sql
   CONSTRAINT chk_event_time CHECK (event_time > 0),
   CONSTRAINT chk_update_id  CHECK (update_id > 0),
   CONSTRAINT chk_spread     CHECK (bid_price <= ask_price)
   ```
   Verify H2 PostgreSQL mode accepts these (it should). Add a `QuoteRepositoryIntegrationTest` case asserting that an inserted row with `bid > ask` raises a constraint violation.

2. **Fix `SymbolNotFoundException` Javadoc** (`QuoteController.java:60-63`): remove "checked exception" wording — it `extends RuntimeException`.

3. **Remove no-op `@JsonIgnore` on `Quote.lagMillis()`** (`Quote.java:41-44`). Records serialize by component, not by accessor; the annotation is a no-op. Either delete the annotation or — if you want defense-in-depth in case Jackson behavior changes — leave it with a one-line comment explaining why it's there.

---

## Batch 9 — Test improvements (nits)

**Files:** `QuoteServicePerformanceTest.java`, `IngestLagTest.java`

1. **p99 off-by-one** (`QuoteServicePerformanceTest.java:74`):
   ```java
   long p99 = latencies.get((int) Math.ceil(size * 0.99) - 1);
   ```

2. **`IngestLagTest` overstates what it proves** (`IngestLagTest.java:88-156`). It hard-codes `eventTime = now - 50` so it can only fail if gauge math is broken. Either:
   - Rename to `lagGaugeReportsMeasuredAge` to match what it actually tests, or
   - Strengthen by sending a burst, sleeping a known interval, and asserting the gauge tracks elapsed wall-clock.
   Pick the rename — it's honest and zero-risk.

---

## Critical files modified (summary)

| File | Batches |
|---|---|
| `Dockerfile` | 1 |
| `docker-compose.yml` | 1, 2 |
| `src/main/resources/application.yml` | 2, 7 |
| `src/main/resources/application-dev.yml` | 7 |
| `src/main/resources/schema.sql` | 8 |
| `BinanceWebSocketClient.java` | 3 |
| `BatchPersistenceService.java` | 5 |
| `QuoteRepository.java` | 4 |
| `QuoteMessageParser.java` | 6 |
| `QuoteService.java` | 6 |
| `BinanceStreamHealthIndicator.java` | 2 |
| `PersistenceQueueHealthIndicator.java` | 2 |
| `AppProperties.java` | 7 |
| `Quote.java` | 8 |
| `QuoteController.java` | 8 (Javadoc only) |
| `docs/design_decisions.md` | 2 (DD-14), 5 (DD-15) |
| Test files | every batch where production code changed |

---

## Verification

After **every batch**:
- `mvn verify` must stay green (Spotless + all 97 tests).
- `mvn dependency:tree | grep -i jpa` returns nothing (existing rule from `CLAUDE.md`).

After **Batch 1 + 2** (deployment + health):
- `docker compose build && docker compose up -d` → `docker compose ps` shows app `(healthy)` within 60s.
- `docker compose stop app` (sends SIGTERM) — `docker compose logs app` should show "Initiating WebSocket shutdown…" then "WebSocket shutdown complete" then "Persistence drainer stopped". If you only see SIGKILL after 10s, Batch 1.3 (PID-1 fix) didn't take effect.

After **Batch 5** (persistence robustness):
- Manual blast test: `docker compose pause postgres` while the app is ingesting; watch `binance.quotes.dropped.total` counter. `docker compose unpause postgres`; verify the queue drains and no exceptions are thrown after recovery.
- Confirm `binance_quote_lag_max_millis` gauge stays bounded.

After **Batch 6** (parser allowlist):
- New unit test in `QuoteMessageParserTest` for an unknown symbol returns `Optional.empty()`.

After **all batches**:
- `curl -s localhost:18080/actuator/health/liveness` → `{"status":"UP"}`
- `curl -s localhost:18080/actuator/health/readiness` → `{"status":"UP","components":{"binanceStream":{...},"persistenceQueue":{...}}}`
- `curl -s localhost:18080/actuator/metrics/binance.quotes.dropped.total` → returns the new counter.
- `curl -s localhost:18080/actuator/metrics/binance.quote.lag.millis?tag=symbol:BTCUSDT` → returns a real value, not 0 from the never-seeded fallback.

## Out of scope (intentionally not addressed)

- Doc consolidation. The review noted the `docs/` folder is over-engineered for a 1-day take-home. Touching that is a judgment call about how the user wants this submission read; not a defect.
- Replacing `LinkedBlockingQueue` with a Disruptor or `ArrayBlockingQueue`. Current performance is within all SLOs; not justified.
- Auth on `/actuator`. Out of scope for an interview project.
- Switching from `NamedParameterJdbcTemplate` to `JdbcClient`. The CLAUDE.md mention of `JdbcClient.batchUpdate` is aspirational — `JdbcClient` has no batch API. Current choice is correct.
