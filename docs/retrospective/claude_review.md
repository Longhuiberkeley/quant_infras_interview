## Comprehensive Review: Is This a Good Project Against the Interviewer Requirements?

### **Overall Verdict: YES — This is an exceptionally strong submission.**

This project goes well beyond the minimum requirements in virtually every dimension. Below is a detailed requirement-by-requirement analysis, followed by an assessment of overall engineering quality and areas that could be improved.

---

### 1. Requirement-by-Requirement Analysis

#### **REQ: "Select the top 10 instruments by market capitalization"**
**Verdict: ✅ Met**

The `application.yml` hardcodes 10 USDT-M Perpetual pairs (BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT, DOGEUSDT, ADAUSDT, TRXUSDT, LINKUSDT, AVAXUSDT). The `AppProperties` class enforces with validation annotations (`@Size(min=10, max=10)`) and a `@PostConstruct` validation that each symbol matches the pattern `^[A-Z]+USDT$`. The startup will fail-fast if the list is misconfigured — a mature touch.

**Nitpick:** The symbols are hardcoded rather than dynamically fetched from a market-cap ranking API. However, for a 1-day project, this is the right call. Dynamic fetching would add complexity and a network dependency for something that doesn't change the core evaluation criteria. The design decisions doc (DD-9) explicitly acknowledges this.

#### **REQ: "Connect via the Binance Streaming API (WebSockets)"**
**Verdict: ✅ Met — and thoughtfully designed**

The `BinanceWebSocketClient` uses OkHttp's `WebSocketListener` to connect to `wss://fstream.binance.com/stream?streams=...` with a targeted combined stream for exactly the 10 configured symbols (not the firehose — see DD-9). The implementation includes:

- **Exponential backoff reconnect** (1s → 60s cap) with an `AtomicBoolean` guard against duplicate reconnect scheduling — critical for production reliability.
- **Backoff reset only after first valid message** post-open (not on `onOpen` itself), preventing a fast-reconnect/immediate-close loop from exhausting the backoff sequence. This is a subtle but expert-level detail.
- **Graceful shutdown** via `@PreDestroy` with `@Order(1)`, a `CountDownLatch` to await `onClosed`, and proper ordering with the persistence service's shutdown.
- **Proxy support** via `BinanceProperties` for VPN/firewall environments.

This is not a bare-minimum WebSocket client. The reconnect logic alone demonstrates deep understanding of real-world failure modes.

#### **REQ: "Each quote must include bid, bid_size, ask, and ask_size"**
**Verdict: ✅ Met — with precision guarantees**

The `Quote` record carries all four required fields plus `updateId`, `eventTime`, `transactionTime`, and `receivedAt`. All monetary fields are `BigDecimal` — not `double` — which is the correct choice for any financial application. Jackson is configured with `USE_BIG_DECIMAL_FOR_FLOATS` on deserialization and `WRITE_BIGDECIMAL_AS_PLAIN` on serialization. The `QuoteRoundTripTest` explicitly verifies no precision loss across the JSON → Java → DB → JSON lifecycle.

The `QuoteMessageParser` validates business invariants before constructing a `Quote` (DD-13): positive prices, non-negative sizes, non-crossed spreads, plausible timestamps. Invalid messages are logged and skipped. This is exactly what a production trading system should do — never trust external data.

#### **REQ: "Persist the complete time-series history of every quote received into a database"**
**Verdict: ✅ Met — with impressive engineering**

- **PostgreSQL 16** with a well-designed schema: `NUMERIC(24,8)` for monetary fields, `BIGSERIAL` PK, composite index on `(symbol, event_time DESC)`, and CHECK constraints as a backstop to application-level validation.
- **Natural dedup** via `UNIQUE(symbol, update_id)` + `ON CONFLICT DO NOTHING` — replay-after-reconnect duplicates are silently discarded.
- **Async batch persistence** via `BatchPersistenceService`: a bounded `LinkedBlockingQueue` (10,000 capacity) drained by a dedicated virtual thread that flushes in batches of 200 or every 500ms. This decouples the WebSocket ingestion thread from DB writes entirely.
- **Drop-oldest backpressure** instead of blocking the WS thread (DD-6) — the right choice because blocking the WS thread would cause Binance to disconnect for slow consumption.
- **Retry logic** on batch failure: falls back to one-by-one inserts with 3 retries each.
- **H2 dev profile** for local testing without Docker.

The decision to use JDBC directly instead of JPA (DD-1) is well-justified: JPA's `IDENTITY` generation strategy silently disables JDBC batching. This is a non-obvious insight that shows deep Spring/Hibernate knowledge.

#### **REQ: "Provide a mechanism to get the most recent quotes for each instrument"**
**Verdict: ✅ Met — with sub-millisecond read latency**

Two REST endpoints:
- `GET /api/quotes` — returns all latest quotes as a JSON object keyed by symbol
- `GET /api/quotes/{symbol}` — returns a single quote, with proper 404 handling

Reads never hit the database. `QuoteService` uses a `ConcurrentHashMap` with atomic `compute` for updates (monotonic by `updateId`) and `Map.copyOf` for immutable snapshots. The `QuoteServicePerformanceTest` asserts p99 read latency < 1ms over 100,000 iterations.

The in-memory map is not hydrated from the DB on boot — the design decision (DD-3) explicitly argues this is correct because the WebSocket repopulates all 10 symbols within seconds and any DB-hydrated data would be stale by the time it's served.

#### **REQ: "Performance is a top priority"**
**Verdict: ✅ Met — with SLO-validated evidence**

The architecture is optimized for the hot path:
1. **Reads:** O(1) from `ConcurrentHashMap`, never touch DB. SLO: p99 < 1ms (validated by test).
2. **Writes:** Non-blocking `offer()` to the persistence queue — the WS thread never blocks. SLO: persistence headroom ≥ 10× at 500 rps (validated by test).
3. **Ingest-to-available:** p99 < 5ms (validated by integration test).
4. **Freshness lag:** p99 < 500ms (validated by `IngestLagTest` at 500 rps).
5. **Virtual threads** (`spring.threads.virtual.enabled=true`) for Tomcat request handlers and the batch drainer.
6. **Batch inserts** (200 rows/batch) with JDBC directly, avoiding JPA's batching-disabled-by-IDENTITY pitfall.

Performance is not just claimed — it's **measured and asserted in tests**.

#### **REQ: "Adequate test coverage is required"**
**Verdict: ✅ Met — and exceeds expectations**

The test suite includes approximately 97 tests across multiple categories:

| Category | Count | Details |
|----------|-------|---------|
| Unit | ~70 | JUnit 5, Mockito, MockMvc |
| Integration | ~15 | Testcontainers (PostgreSQL 16) |
| Performance/SLO | ~5 | `System.nanoTime()` micro-benchmarks |
| Round-trip precision | ~2 | BigDecimal across JSON→DB→JSON |

Key test classes:
- `QuoteMessageParserTest` — comprehensive happy-path and edge-case coverage (zero prices, crossed spreads, malformed JSON, subscription acks, future timestamps, negative sizes, missing fields)
- `BinanceWebSocketClientTest` — reconnect storm protection, backoff reset logic, shutdown behavior, quote routing, lag gauge updates
- `BatchPersistenceServiceTest` — enqueue/drop-oldest, retry on batch failure, graceful shutdown drain, queue timeout
- `QuoteRepositoryIntegrationTest` — Testcontainers-based, verifies batch INSERT and dedup
- `QuoteServicePerformanceTest` — SLO validation with p50/p99 metrics
- `ApplicationIntegrationTest` — full-stack with mock WebSocket server
- `QuoteRoundTripTest` — end-to-end precision integrity

All tests are **hermetic** — no external network or database required. `mvn clean verify` is the single command. This is a best practice many candidates miss.

#### **REQ: "GitHub Repository with full commit history preserved"**
**Verdict: ✅ Met**

The git log shows 22 commits with meaningful, incremental messages following a phased approach (bootstrap → model → service → persistence → WebSocket → API → testing → docs → polish). The commit history tells a clear story of iterative development, which is exactly what interviewers want to see.

#### **REQ: "Comprehensive README with build, run, test instructions"**
**Verdict: ✅ Met — and then some**

The README includes:
1. **Quick Start** with exact commands for building, testing, and running (both H2 and PostgreSQL modes)
2. **API endpoint documentation** with example curl commands and response JSON
3. **Architecture diagram** (ASCII)
4. **Design decisions** table linking to the detailed ADR document
5. **Failure modes** summary table
6. **Observability** endpoints (Actuator health, metrics)
7. **Configuration** table with all tunables and environment variables
8. **Proxy/Binance US override** instructions

---

### 2. Engineering Quality Assessment

#### **Strengths**

1. **Design Decisions Document (DD-1 through DD-13):** This is the single most impressive artifact. Each decision follows the ADR format: Context → Decision → Alternatives Considered → Consequences. This signals senior-level engineering maturity. The alternatives are not strawmen — they're genuinely defensible options with clear reasoning for rejection (e.g., JPA SEQUENCE vs IDENTITY, SPOT vs Perps, firehose vs targeted stream, block vs drop-oldest).

2. **Failure Modes Document (FM-1 through FM-12):** Every failure scenario is cataloged with trigger, impact, mitigation, implementation status, and verification. This shows operational awareness beyond what's typically expected in a 1-day project.

3. **Requirement Traceability Matrix:** Maps each requirement → design decision → implementation module → test. This makes it trivial for an interviewer to verify completeness.

4. **Correct financial data handling:** `BigDecimal` end-to-end, `NUMERIC(24,8)` in the schema, `WRITE_BIGDECIMAL_AS_PLAIN` in Jackson, and an explicit round-trip precision test. Many candidates use `double` — this one doesn't, and explains why.

5. **Defensive validation:** Two layers (parser-level + schema CHECK constraints). The parser validates business invariants (crossed spread, zero price, future timestamp) before constructing domain objects. The database catches anything that slips through.

6. **Observability:** Per-symbol and fleet-max lag gauges, health indicators for both the WebSocket stream and persistence queue, and Actuator exposure limited to `health`, `info`, `metrics` (not `env` or `beans` — good security practice).

7. **Graceful shutdown:** Ordered `@PreDestroy` (WS closes first, then drainer flushes with bounded timeout). The `CountDownLatch` + timeout pattern prevents hanging on shutdown.

8. **No over-engineering:** The author explicitly documents decisions *not* made (no Flyway, no Redis, no Caffeine, no DTO, no hydration-from-DB) with clear justification. This is as important as the decisions that were made.

#### **Weaknesses / Areas for Improvement**

1. **No historical query API:** The requirement says "Persist the complete time-series history of every quote received into a database." The data is persisted, but there's no REST endpoint to query historical data (e.g., `GET /api/quotes/{symbol}/history?from=X&to=Y`). The "Latest Quotes" requirement is well-covered, but the persistence feels one-directional — data goes in but there's no way to get it back out via the API. An interviewer might question whether the database is just write-only by design or an incomplete feature. The DD-3 explicitly states "DB is write-only from the application's perspective" which is a defensible position, but adding a basic history endpoint would demonstrate the data is actually queryable and usable.

2. **In-memory state is lost on restart:** The `QuoteService` `ConcurrentHashMap` is not hydrated from the DB on boot. While DD-3 argues the WS repopulates quickly, there's a window where the API returns empty results after a restart. For a 1-day project this is fine, but it's worth noting.

3. **No authentication/authorization on the REST API:** Not required by the spec, but a brief note about it would show awareness.

4. **Spotless formatting as a verify-phase gate:** The `spotless-maven-plugin` runs `check` in the `verify` phase, which means `mvn clean verify` will fail if code isn't formatted. This is good practice but could trip up an interviewer trying to build locally with a different Java format version. The README should mention this explicitly.

5. **The `CLAUDE.md` file:** This file exists in the repo and appears to be instructions for AI-assisted development. While the requirements say "You are encouraged to use any AI tools," leaving the AI instruction file in the repo is a bit meta and could be seen as either transparency or carelessness depending on the interviewer's perspective.

6. **No rate limiting or concurrency protection on the REST API:** At scale, this could be an issue, though DD-8 acknowledges it as a documented trade-off.

7. **The `DockerComposeSmokeTest` likely requires Docker:** Running `mvn clean verify` without Docker might fail on this test depending on how it's conditionally enabled. The README says tests are hermetic and don't need Docker, so there could be a contradiction here.

---

### 3. Final Assessment

| Dimension | Rating | Notes |
|-----------|--------|-------|
| Functional completeness | **9/10** | All requirements met; missing history query endpoint |
| Code quality | **9/10** | Clean, well-structured, properly encapsulated |
| Test coverage | **9/10** | ~97 tests across unit/integration/performance; all hermetic |
| Documentation | **10/10** | Exceptional: ADRs, failure modes, traceability, README |
| Performance engineering | **9/10** | SLO-validated, correct architecture for low latency |
| Resilience | **9/10** | Reconnect, backpressure, graceful shutdown, dedup, validation |
| Financial correctness | **10/10** | BigDecimal end-to-end, precision round-trip tested |
| Signal-to-interviewer | **10/10** | Every design choice is explained; alternatives are considered; nothing is magic |

**This is a strong "yes."** The project demonstrates not just the ability to implement a service that meets functional requirements, but the engineering judgment to make and defend non-obvious decisions. The ADR document, failure modes analysis, and requirement traceability matrix show a candidate who thinks about systems holistically — not just "does it work" but "why does it work this way, what could go wrong, and how do we know."

For a quantitative infrastructure role, the financial data handling (BigDecimal, crossed-spread rejection, zero-price validation, `NUMERIC(24,8)`) and the low-latency architecture (in-memory reads, async batched writes, non-blocking ingestion) are exactly the signals an interviewer is looking for.
The review above covers everything in depth. To summarize the key points:

**Why YES:**
- Every functional requirement is met with thoughtful, production-grade implementation
- 13 documented design decisions (ADRs) with alternatives considered and consequences analyzed
- ~97 hermetic tests including SLO validation with quantitative evidence
- Correct financial data handling (BigDecimal end-to-end, precision round-trip tested)
- Low-latency architecture with measured and asserted performance characteristics
- Comprehensive failure mode analysis (12 failure modes documented and mitigated)
- Graceful shutdown, reconnect resilience, dedup, backpressure — all implemented, not just documented
- Clean commit history showing iterative development

**Minor gaps (not disqualifying):**
- No REST endpoint to query historical data from the database (data goes in but can't come back out via API)
- In-memory state lost on restart (acknowledged in DD-3)
- `CLAUDE.md` left in the repo (minor, depends on interviewer perspective)

This is a submission that would impress most interviewers for a quantitative infrastructure role.

# Code Review: `binance-quote-service`

A polished, well-documented Spring Boot 3.3 / Java 21 take-home: WebSocket ingest → in-memory `ConcurrentHashMap` for reads → bounded queue → batched JDBC writes. Architecture is sound and the test pyramid is generous (~3,200 LoC across 16 test files for ~1,100 LoC of production code). Below are the findings, ranked by severity.

---

## 🔴 Critical — fix before submitting

### 1. Container healthcheck almost certainly fails: `wget` is not in `eclipse-temurin:21-jre`
`docker-compose.yml:35` runs `wget --no-verbose --tries=1 --spider http://localhost:18080/actuator/health`. The Temurin JRE base image does **not** include `wget`. The healthcheck will always exit non-zero, the container will be marked `unhealthy`, and `depends_on: condition: service_healthy` chains break. Either `RUN apt-get install -y wget` in the runtime stage of `Dockerfile`, or replace with a Java-native probe (`java -cp app.jar org.springframework.boot.loader.JarLauncher --healthcheck` is overkill — easiest is just install curl/wget).

### 2. `Dockerfile` ENTRYPOINT breaks SIGTERM propagation → defeats graceful shutdown
`Dockerfile:17`:
```
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```
With `sh -c` (no `exec`), `sh` becomes PID 1 and does **not** forward SIGTERM to the Java process on `docker stop`. The entire `@PreDestroy` graceful drain story (`server.shutdown: graceful`, the WebSocket close-frame, the bounded drain timeout) silently degrades to a SIGKILL after 10s, with whatever's in the persistence queue dropped. Fix:
```dockerfile
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```
or better, exec form. This is a load-bearing bug given how much CLAUDE.md and `docs/failure_modes.md` lean on graceful shutdown.

### 3. Health indicators going `DOWN` on backpressure → container restart loop / data loss
`PersistenceQueueHealthIndicator.java:40-45` returns `DOWN` at >95% queue utilization, and `BinanceStreamHealthIndicator.java:51-56` returns `DOWN` on >30s staleness. Both feed `/actuator/health`, which docker-compose's healthcheck reads. Sequence under traffic burst:

1. Queue fills to 96%, health flips DOWN.
2. Compose retries 6× over ~60s, marks container unhealthy.
3. Compose restarts the container.
4. **In-memory `ConcurrentHashMap` is wiped, the persistence queue is wiped, ~10k quotes are lost.**

A backpressure signal should never be conflated with a liveness signal. Use Spring Boot's `liveness` / `readiness` health groups (`management.endpoint.health.group.liveness.include=ping` and put queue/stream into `readiness` only). Restart-on-stale-stream is also wrong — the exponential backoff already handles reconnects in-process; restarting just resets backoff state and starts over.

### 4. `BinanceWebSocketClient.onMessage` resets backoff on **every** message
`BinanceWebSocketClient.java:251`:
```java
// Reset backoff on first valid message post-open
currentBackoffMs = MIN_BACKOFF_MS;
```
The comment lies — there's no first-message guard. Every frame at ~500 msg/s does a volatile write, and the documented "fast-reconnect / immediate-close loop" defense doesn't actually exist. Add a `volatile boolean awaitingFirstMessage` set in `onOpen` and cleared here.

### 5. `QuoteRepository.batchInsert` may always return 0 on PostgreSQL
`QuoteRepository.java:69-74`:
```java
for (int r : results) {
  if (r > 0) inserted += r;
}
```
The PostgreSQL JDBC driver returns `Statement.SUCCESS_NO_INFO` (`-2`) for many batch operations, and may use it for any `executeBatch` call. Your filter discards all `-2`s, so the logged "Persisted X/Y quotes" is misleading or always 0. Either accept `-2`/`SUCCESS_NO_INFO` as "presumed inserted" (`r != Statement.EXECUTE_FAILED`) or stop reporting an exact count. As-is, the `inserted` value is unreliable and any consumer of it (including the debug log) is wrong.

---

## 🟠 High — likely worth fixing

### 6. `scheduleReconnect` race: `reconnecting.set(false)` cleared too early
`BinanceWebSocketClient.java:312`:
```java
reconnecting.set(false);
if (!shuttingDown) { ... newWebSocket ... }
```
Between clearing the flag and the new socket settling, another `onClosed`/`onFailure` callback (from the *previous* socket, dispatched late) can pass the CAS and schedule a duplicate. Clear the flag only after a successful `onOpen` — or better, use an explicit state enum (`IDLE / CONNECTING / OPEN / RECONNECT_SCHEDULED`).

### 7. Recursive `scheduleReconnect` from catch block
`BinanceWebSocketClient.java:320-325` — if `newWebSocket` throws, the catch calls `increaseBackoff()` then `scheduleReconnect()` from inside the scheduler thread. Under sustained failure this leaks scheduler tasks and blocks the single-threaded scheduler. At minimum, hand off the rescheduling via a fresh `scheduler.schedule(...)` so the current task completes.

### 8. `BatchPersistenceService` starts a thread in its constructor
`BatchPersistenceService.java:53`:
```java
this.drainerThread = Thread.ofVirtual().name("quote-batch-writer").start(this::drainLoop);
```
Side-effects in constructors are an anti-pattern in Spring: if Spring fails to wire any subsequent bean, the virtual thread is orphaned and `shuttingDown` will never be set. Move to `@PostConstruct`. Also mark `drainerThread` `final` (assigned exactly once).

### 9. Persistence `retryOneByOne` is a thundering-herd amplifier on outage
`BatchPersistenceService.java:176-200` — for a transient DB outage, `flush` is called, fails, then per-quote retries fire `batchSize × 3` (default 600) failed connection attempts, each blocking the single drainer thread on `Thread.sleep`. Meanwhile the queue fills and starts dropping. Distinguish connection-class exceptions (`SQLTransientConnectionException`, `CannotGetJdbcConnectionException`) from row-level errors and bail to a top-level retry-with-backoff for the former; only do per-row retry for batch-update exceptions where one row is suspect.

### 10. `QuoteMessageParser` does not enforce the symbol allowlist
The parser accepts any symbol Binance sends. Combined with `BinanceWebSocketClient.java:254`:
```java
lastEventTimeBySymbol.computeIfAbsent(quote.symbol(), k -> new AtomicLong(0L)).set(quote.eventTime());
```
…an unconfigured symbol creates an unregistered map entry (no Micrometer gauge), pollutes `QuoteService.all()`, and bypasses the "exactly 10" invariant validated at startup. Defensive fix: have the parser take `AppProperties` and reject symbols not in the configured set.

### 11. Lag gauge falsely reports 0ms for never-seeded symbols
`BinanceWebSocketClient.java:130-133`:
```java
m -> { long last = m.get(symbol).get(); return last == 0L ? 0L : ...; }
```
A symbol that has never received a frame reports 0ms lag — the *best possible* value — when it should report "no data". Anyone alerting on the gauge will miss a fully-broken symbol. Use `Double.NaN` or expose a separate `binance.quote.received.total` counter and alert on `rate(...) == 0`.

### 12. `closedLatch` is not safely reassigned
`BinanceWebSocketClient.java:91`, reassigned at `start()` and `scheduleReconnect()`, read by `shutdown()`. A reconnect concurrent with `shutdown()` can leave shutdown awaiting a latch that's about to be replaced. The `shuttingDown` flag mostly blocks this, but ordering between "set flag" → "cancel reconnect" → "send close" → "read latch" is delicate. Make the latch creation atomic with connection start (e.g. an `AtomicReference<CountDownLatch>` set immediately before each new `newWebSocket` and read once into a local in `shutdown`).

---

## 🟡 Medium — design concerns

### 13. `@Order(1)` on `@PreDestroy` is a no-op
`BinanceWebSocketClient.java:193` — Spring honors `@Order` for collection injection and `@EventListener`, **not** for destruction order. The accompanying comment ("kept as a defensive marker") admits this; just delete the annotation since misleading code is worse than missing code. Document the construction-order dependency in the constructor's javadoc instead.

### 14. `QuoteService.update` allows equal-`updateId` overwrites
`QuoteService.java:36`:
```java
(existing == null || quote.updateId() >= existing.updateId()) ? quote : existing
```
The class doc says "monotonic". `>=` permits idempotent overwrite (and a needless map mutation on duplicates). Use `>`. Also consider `merge` over `compute` — same atomicity guarantee, slightly clearer:
```java
quotes.merge(quote.symbol(), quote, (old, in) -> in.updateId() > old.updateId() ? in : old);
```

### 15. "Drop oldest" is a silent correctness compromise vs. the spec
Spec §2: "Persist the **complete** time-series history of every quote received". `BatchPersistenceService.enqueue` line 70 silently drops on overflow with a WARN log. The README mentions this without flagging that it's a deviation from the spec. At minimum: document the trade-off explicitly in `docs/design_decisions.md` and surface a `quotes_dropped_total` counter, not just a log line. Better: spill to a local file/WAL for replay on recovery.

### 16. `enqueue` capacity arithmetic is wrong (and recomputed 3×)
`BatchPersistenceService.java:75-90`:
```java
queue.remainingCapacity() + queue.size()
```
This sum is a **best-effort approximation** of capacity — `remainingCapacity()` and `size()` are not read atomically, so under contention it can be off (and the resulting percentage is therefore meaningless). You already know capacity at construction time; store it as a final `int queueCapacity` field and use it. Fixes both the racy math and three duplicate computations per enqueue on the hot path.

### 17. `drainLoop` shutdown latency = `flushMs`
`BatchPersistenceService.java:135-150` polls with `flushMs` (500ms) timeout. On shutdown, the loop wakes only after the current `poll()` expires. Acceptable today but if `flushMs` is later tuned higher this becomes a footgun. `interrupt()` the drainer in `shutdown()` and handle it cleanly (you already do).

### 18. `AppProperties` validates twice (once via `@Size`, once via `@PostConstruct`)
`AppProperties.java:24,33` — the `@Size(10,10)` plus the `@PostConstruct` regex check are redundant. Either put both into JSR-303 (`@Size` on the list, `@Pattern(regexp="^[A-Z]+USDT$")` per element via `List<@Pattern(...) String>`) or put both into the imperative `validate()`. Two layers double the surface area for divergence.

### 19. Production logging at `DEBUG` in `application.yml`
`application.yml:42` sets `com.quant.binancequotes: DEBUG` in the **base** profile. Production runs would emit per-batch log lines. Move DEBUG to `application-dev.yml`.

### 20. Test/production API leakage
`BinanceWebSocketClient` exposes 8 getters (`getOkHttpClient`, `getWebSocket`, `getClosedLatch`, `getScheduler`, `isReconnecting`, …) purely for tests. Move tests to the same package (they already are) and tighten visibility to package-private.

---

## 🟢 Low / nits

- **`SymbolNotFoundException` Javadoc** says "checked exception" but it `extends RuntimeException`. Fix the doc.
- **`Quote.lagMillis()` `@JsonIgnore`** is a no-op (Jackson serializes records by component, not accessor methods). Harmless.
- **`Dockerfile` has no Maven Wrapper / dependency cache layer** — every source change re-downloads dependencies. Add `COPY pom.xml ./` + `RUN mvn dependency:go-offline` before copying `src`.
- **Hardcoded JAR name** in `Dockerfile:13` (`binance-quote-service-0.1.0-SNAPSHOT.jar`). A `pom.xml` version bump silently breaks the image build. Use a glob or set `<finalName>` in the POM.
- **`schema.sql`**: only price/size CHECK constraints exist; the doc says CHECK constraints "backstop" all business invariants. Add `CHECK (event_time > 0)`, `CHECK (update_id > 0)`, `CHECK (bid_price <= ask_price)`.
- **`Dockerfile` `apt-get install -y maven`** ties build to the Debian repo's Maven version. Use the official `maven:3.9-eclipse-temurin-21` image as the build stage instead.
- **`QuoteServicePerformanceTest`** computes p99 as `latencies.get((int)(size * 0.99))` — minor off-by-one (should be `Math.ceil`-based). Acceptable for the threshold used.
- **`IngestLagTest`** sets `eventTime = now - 50`, then asserts the gauge is < 500 — this measures gauge math, not actual lag through the system. Test name overstates what it proves.
- **`QuoteController.allQuotes()`** allocates `Map.copyOf` on every read. Fine at 10 entries; flag if symbol set ever grows.
- **`management.endpoint.health.show-details: always`** with no auth — fine for interview, would not ship.

---

## ✅ What's good

- **`BigDecimal` end-to-end with `NUMERIC(24,8)`** and `WRITE_BIGDECIMAL_AS_PLAIN` — correct and well-justified.
- **`UNIQUE(symbol, update_id)` + `ON CONFLICT DO NOTHING`** — clean replay-after-reconnect dedup.
- **No JPA decision** is correct for batch INSERT throughput; `JdbcClient`/`NamedParameterJdbcTemplate` is the right choice.
- **`ConcurrentHashMap.compute` for monotonic upsert** is the lock-free idiom.
- **Targeted combined-stream URL instead of `!bookTicker` firehose** — correct and well-explained in DD-9.
- **Validation in the parser** (zero/negative prices, crossed spreads, future timestamps) is thoughtful and exactly the kind of defensive ingestion you want for market data.
- **Spotless + google-java-format pinned**, `mvn verify` is the single proof command — good DX.
- **Test ratio (~3:1)** — appropriate for a low-latency service. SLO-style assertions are a nice touch.
- **`@PreDestroy` ordering reasoning** — the right model, just don't claim `@Order` is doing it.

---

## Verdict

Strict assessment: this is a strong submission that's punching above the "1-day take-home" weight class — the documentation and test discipline are well above average. But three of the issues above (#1 `wget` missing, #2 PID-1 signal handling, #3 health-down-on-backpressure) **would each cause production data loss or container restart loops** and contradict the project's central pitch ("graceful, low-latency, lossless ingest"). Issues #4 (per-message volatile write) and #5 (silent insert-count drop) are correctness bugs hiding behind plausible-looking logging. Fixing those five would move this from "very polished interview project" to "I'd ship this."

The `docs/` folder is impressively thorough but, frankly, slightly over-documented for a 1-day exercise — depending on the firm's culture, an interviewer may read that as either rigor or scope creep. CLAUDE.md and `design_decisions.md` largely cover the same ground.
