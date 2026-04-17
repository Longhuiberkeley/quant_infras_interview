# TODO Verification — Acceptance Criteria & Evidence

This document mirrors `TODO.md` section-by-section. Each item lists formal acceptance criteria, evidence (file:line or test name), and a pass/partial/fail verdict. Status lifecycle: **pass** (all ACs met), **partial** (core intent met, some ACs open), **fail** (not implemented or wrong approach).

---

## Critical (Fix before shipping / Data loss risks)

### 1. Fix Dev Profile H2 Queries *(Interviewer #1)*

**Status:** partial

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `application-dev.yml` overrides `persistence.insert-sql` with H2-compatible syntax (`MERGE INTO ... KEY`) | Yes — `application-dev.yml:14-19` |
| AC2 | `mvn spring-boot:run -Dspring-boot.run.profiles=dev` starts without `BadSqlGrammarException` | Yes — MERGE INTO works with H2 PostgreSQL mode |
| AC3 | No separate `schema-dev.sql` needed; `schema.sql` works unchanged with H2 | Yes — `schema.sql` uses standard SQL |
| AC4 | Automated test exercises the dev-profile insert path (H2 + MERGE INTO) | **No** — `QuoteRepositoryIntegrationTest` uses Testcontainers PostgreSQL only |

**Evidence:** `application-dev.yml` injects `MERGE INTO quotes ... KEY (symbol, update_id)`. `QuoteRepository` reads `${persistence.insert-sql}` via `@Value`.

**Open gap:** Zero test coverage for the dev profile. Add a `@SpringBootTest(activeProfiles = "dev")` test or at minimum a manual CI step.

---

### 2. Fix Container Healthcheck (Docker) *(Claude #1)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | Dockerfile runtime stage installs `curl` | Yes — `Dockerfile:11` |
| AC2 | `docker-compose.yml` app healthcheck uses `curl -fsS` | Yes — `docker-compose.yml:23` |
| AC3 | Healthcheck targets `/actuator/health/liveness` (not `/actuator/health`) | Yes — `docker-compose.yml:23` |

**Evidence:** `Dockerfile:11` — `apt-get install -y --no-install-recommends curl`. `docker-compose.yml:23` — `curl -fsS http://localhost:18080/actuator/health/liveness`.

---

### 3. Fix Graceful Shutdown (PID-1 Issue) *(Claude #2)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | Dockerfile uses exec-form `ENTRYPOINT` (JSON array, no `sh -c`) | Yes — `Dockerfile:14` |
| AC2 | JVM options passed via `JDK_JAVA_OPTIONS` env var (no shell expansion needed) | Yes — `Dockerfile:13` |
| AC3 | `java` runs as PID 1 inside container (SIGTERM received directly) | Yes — exec-form ENTRYPOINT guarantees this |

**Evidence:** `Dockerfile:14` — `ENTRYPOINT ["java", "-jar", "app.jar"]`. `Dockerfile:13` — `ENV JDK_JAVA_OPTIONS="..."`.

---

### 4. Liveness vs. Readiness Health Check Split *(Claude #3)*

**Status:** partial

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `/actuator/health/liveness` does NOT include `persistenceQueue` or `binanceStream` status | Yes — default liveness group only includes `livenessState` |
| AC2 | `/actuator/health/readiness` DOES include `persistenceQueue` and `binanceStream` status | **No** — no `management.endpoint.health.group.readiness` config |
| AC3 | When queue >95% full, readiness returns `DOWN` but liveness returns `UP` | **No** — queue indicator not in readiness group |
| AC4 | `application.yml` has explicit health group configuration | **No** — missing |
| AC5 | Test verifies health group assignment | **No** — no test |

**Evidence:** `application.yml` has `management.endpoint.health.probes.enabled: true`. `docker-compose.yml:23` uses `/actuator/health/liveness`. But no `management.endpoint.health.group.readiness.include` configuration.

**Open gap:** The immediate "container restarts on queue full" problem is solved (liveness doesn't see queue health), but the readiness path is unconfigured. Need:
```yaml
management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState, persistenceQueue, binanceStream
        liveness:
          include: livenessState
```

---

### 5. Fix `QuoteRepository.batchInsert` Logged Count Bug *(Claude #5)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `batchInsert` handles `Statement.SUCCESS_NO_INFO` (`-2`) and counts it as 1 inserted row | Yes — `QuoteRepository.java:66-71` |
| AC2 | Against PostgreSQL, `batchInsert` returns a positive count (not 0) for successful inserts | Yes — `QuoteRepositoryIntegrationTest.batchInsertSucceeds` asserts count == 10 |
| AC3 | Debug log in `BatchPersistenceService.flush()` shows reasonable count | Yes — uses the corrected return value |

**Evidence:** `QuoteRepository.java:66-71` — explicit `else if (r == Statement.SUCCESS_NO_INFO) { inserted += 1; }`. Test: `QuoteRepositoryIntegrationTest.batchInsertSucceeds`.

**Note:** The count is an upper bound — `ON CONFLICT DO NOTHING` rows also produce `SUCCESS_NO_INFO`, so actual inserts may be less than reported. Acceptable per TODO intent.

---

### 6. Fix `BinanceWebSocketClient` Reconnect Race *(Claude #6)*

**Status:** partial

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `reconnecting` flag stays `true` from `scheduleReconnect()` CAS until `onOpen` of new socket | Yes — flag only cleared in `onOpen()` and `catch` block |
| AC2 | Multiple `onClosed`/`onFailure` during active reconnect do NOT schedule duplicates | Yes — test `reconnect_schedulesOnce_underStormOfClosures` |
| AC3 | `onOpen` clears `reconnecting` flag | Yes — test `reconnect_flagReset_afterReconnectAttempt` |
| AC4 | Guard prevents old-socket callbacks from interfering after `webSocket` reassignment | Yes — `if (this.webSocket != null && webSocket != this.webSocket) return;` |
| AC5 | No residual race where old socket's `onClosed` clears flag between reconnect task start and `webSocket` reassignment | **Partial** — narrow window exists before `webSocket = okHttpClient.newWebSocket(...)` executes |

**Evidence:** `BinanceWebSocketClient.java:238-245` (`onOpen`), `BinanceWebSocketClient.java:309-328` (`scheduleReconnect` task). Tests: `BinanceWebSocketClientTest`.

**Open gap:** Between reconnect task start and `webSocket` reassignment (~line 323), the old socket's `onClosed` can still pass the identity guard and clear `reconnecting`. Fix: null out `webSocket` at the start of the reconnect task body, or use a separate `reconnectGeneration` counter.

---

### 7. Fix `scheduleReconnect` Exception Handling *(Claude #7)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | When `newWebSocket` throws, exactly one new reconnect is scheduled (not stacked) | Yes — `scheduleReconnect()` uses `scheduler.schedule(...)` (async) |
| AC2 | Backoff increases exponentially on successive failures | Yes — `increaseBackoff()` called in catch |
| AC3 | Flag is NOT cleared before `newWebSocket` call in the success path | Yes — flag cleared only in `onOpen` (success) or `catch` (failure) |

**Evidence:** `BinanceWebSocketClient.java:312-328`. The scheduler is single-threaded; `scheduler.schedule(...)` enqueues without blocking.

**Open gap:** No dedicated test for "reconnect exception → retry with backoff." Should add one that mocks `OkHttpClient.newWebSocket` to throw.

---

### 8. Fix WebSocket Backoff Reset Bug *(Claude #4)*

**Status:** partial

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `awaitingFirstMessage` field exists and is `volatile` | Yes — `BinanceWebSocketClient.java:82` |
| AC2 | Flag set to `true` in `onOpen` | Yes — `BinanceWebSocketClient.java:244` |
| AC3 | Flag checked in `onMessage`; backoff reset ONLY on first valid message, then flag cleared | **No** — flag is set but never read; `currentBackoffMs = MIN_BACKOFF_MS` executes on every valid message |
| AC4 | Subsequent valid messages do NOT write `currentBackoffMs` (no volatile write per frame) | **No** — volatile write happens every valid message |

**Evidence:** `BinanceWebSocketClient.java:249-265` (`onMessage`) — no `if (awaitingFirstMessage)` guard around `currentBackoffMs = MIN_BACKOFF_MS`. The field `awaitingFirstMessage` is dead code.

**Open gap:** Either:
- (a) Guard the backoff reset: `if (awaitingFirstMessage) { awaitingFirstMessage = false; currentBackoffMs = MIN_BACKOFF_MS; }`, or
- (b) Remove the `awaitingFirstMessage` field and accept that resetting an already-minimum value is a no-op (the functional behavior is correct; the cost is ~500 volatile writes/sec which is negligible).

---

## High (Architecture, Performance, & Testing)

*No [dev-done] or [reviewed] items in this section — all remain `[ ]` open.*

---

## Medium (Code Cleanliness, Correctness, & Observability)

### 9. Suppress Expected Exception Stack Traces in Tests *(Interviewer #4)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `logback-test.xml` suppresses WARN from `QuoteMessageParser` | Yes — level set to `ERROR` |
| AC2 | `logback-test.xml` suppresses logs from `BinanceWebSocketClient` | Yes — level set to `OFF` |
| AC3 | Build console output is clean during test runs | Yes — verified in `logback-test.xml` |

**Evidence:** `src/test/resources/logback-test.xml`.

---

### 10. Fix IDEA Test Quirks *(Interviewer #3)*

**Status:** partial

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | No `null` passed for `@NotNull`-annotated parameters in any test | **No** — `IngestLagTest` still passes `null` for `webSocket` parameter |
| AC2 | All `onMessage` calls pass a mock `WebSocket` instead of `null` | **No** — `IngestLagTest` uses `client.onMessage(null, msg)` |

**Evidence:** `IngestLagTest.java` — `client.onMessage(null, msg)` at multiple call sites. OkHttp's `WebSocketListener.onMessage(WebSocket, String)` has `@NotNull` on the first parameter, which IDEA enforces.

**Open gap:** Replace `null` with a mock `WebSocket` in `IngestLagTest`, or extract a helper that doesn't require the parameter.

---

### 11. Fix Lag Gauge Zero Value for No Data *(Claude #11)*

**Status:** partial

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | Per-symbol gauge returns `Double.NaN` when symbol has never received a frame | Yes — `BinanceWebSocketClient.java:135`: `return last == 0L ? Double.NaN : ...` |
| AC2 | Fleet-max gauge returns `Double.NaN` when no symbol has received data | **No** — `BinanceWebSocketClient.java:151`: `.orElse(0L)` returns `0L` |
| AC3 | Test verifies NaN behavior for both per-symbol and fleet-max | **No** — no NaN-specific test |

**Evidence:** Per-symbol: `BinanceWebSocketClient.java:134-136`. Fleet-max: `BinanceWebSocketClient.java:145-151`.

**Open gap:** Change fleet-max to:
```java
.mapToLong(AtomicLong::get)
.filter(t -> t > 0L)
.mapToObj(t -> (OptionalLong) OptionalLong.of(System.currentTimeMillis() - t))
.max()
.orElse(OptionalLong.empty())
// Return NaN when empty, or use a different approach
```
Simpler: check if any value `> 0L` exists; if not, return `Double.NaN` directly.

---

### 12. Thread-safe Latch Reassignment *(Claude #12)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `closedLatch` is `AtomicReference<CountDownLatch>` | Yes — `BinanceWebSocketClient.java:87` |
| AC2 | `start()` and `scheduleReconnect()` atomically set new latch | Yes — `closedLatch.set(new CountDownLatch(1))` in both |
| AC3 | `shutdown()` captures latch into local variable before `await()` | Yes — `BinanceWebSocketClient.java:214` |

**Evidence:** `BinanceWebSocketClient.java:87,170,322,214`.

---

### 13. Remove No-op `@Order(1)` on `@PreDestroy` *(Claude #13)*

**Status:** partial

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `@Order(1)` annotation is removed from `shutdown()` | **No** — still present at `BinanceWebSocketClient.java:192` |
| AC2 | Javadoc explains destruction ordering relies on Spring's reverse-initialization-order guarantee | Yes — javadoc explains it |
| AC3 | No misleading annotation that implies `@Order` affects `@PreDestroy` callbacks | **No** — `@Order(1)` still present |

**Evidence:** `BinanceWebSocketClient.java:192-193` — `@PreDestroy @Order(1)`. Javadoc at `BinanceWebSocketClient.java:184-188` explains the ordering mechanism.

**Open gap:** Delete the `@Order(1)` annotation. The javadoc is good and should remain.

---

### 14. Fix Racy `enqueue` Capacity Arithmetic *(Claude #16)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `queueCapacity` is a `final int` field, set once at construction | Yes — `BatchPersistenceService.java:40` |
| AC2 | No calls to `queue.remainingCapacity()` anywhere in the class | Yes — only `queue.size()` and `queue.offer/poll/drainTo` |
| AC3 | Utilization computed as `queue.size() / queueCapacity` (stable denominator) | Yes — `BatchPersistenceService.java:58` |

**Evidence:** `BatchPersistenceService.java:38-40,58,78`.

---

### 15. Reduce Shutdown Latency of `drainLoop` *(Claude #17)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `shutdown()` calls `drainerThread.interrupt()` before `join()` | Yes — `BatchPersistenceService.java:97` |
| AC2 | `drainLoop`'s `catch (InterruptedException)` breaks out of poll loop | Yes — `BatchPersistenceService.java:140-143` |
| AC3 | Final drain still executes after interrupt (queued items not skipped) | Yes — code after while loop flushes batch + drains remainder |
| AC4 | `flush()` clears interrupt flag around JDBC to prevent connection corruption | Yes — `BatchPersistenceService.java:151` |

**Evidence:** `BatchPersistenceService.java:93-108` (shutdown), `130-150` (drainLoop), `151-162` (flush).

---

### 16. Move DEBUG Logging Out of Base Profile *(Claude #19)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `application.yml` does NOT set `com.quant.binancequotes` logging level | Yes — only `root: INFO` |
| AC2 | `application-dev.yml` sets `com.quant.binancequotes: DEBUG` | Yes — `application-dev.yml:13-14` |
| AC3 | Running without dev profile produces INFO-level logs for application code | Yes — inherits `root: INFO` |

**Evidence:** `application.yml:33-34`, `application-dev.yml:12-14`.

---

## Low (Nits, Tooling, Docs, & Compatibility)

### 17. Fix Hardcoded JAR Name in Dockerfile *(Claude)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | Dockerfile COPY uses glob pattern for JAR filename | Yes — `binance-quote-service-*.jar` |
| AC2 | No hardcoded version string in COPY path | Yes — glob matches any version |

**Evidence:** `Dockerfile:12`.

---

### 18. Use Official Maven Docker Image *(Claude)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | Build stage uses official Maven Docker image | Yes — `FROM maven:3.9-eclipse-temurin-21 AS build` |
| AC2 | Image includes both Maven 3.9+ and JDK 21 | Yes — `eclipse-temurin-21` variant |

**Evidence:** `Dockerfile:2`.

---

### 19. Tighten Test-Only Getter Visibility *(Claude #20)*

**Status:** partial

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `getOkHttpClient()` is package-private | Yes — no `public` modifier |
| AC2 | `getWebSocket()` is package-private | **No** — still `public` at `BinanceWebSocketClient.java:408` |
| AC3 | `getClosedLatch()` is package-private | Yes — no `public` modifier |

**Evidence:** `BinanceWebSocketClient.java:403,408,413`.

**Open gap:** Remove `public` from `getWebSocket()`.

---

## Summary

| Status | Count | Items |
|--------|-------|-------|
| Pass | 11 | Container Healthcheck, PID-1 Shutdown, Batch Insert Count, scheduleReconnect Exception, Suppress Stack Traces, Thread-safe Latch, Racy Capacity, Shutdown Drain Latency, DEBUG in Base Profile, Hardcoded JAR, Maven Image |
| Partial | 8 | Dev Profile H2, Liveness/Readiness Split, Reconnect Race, Backoff Reset, IDEA Test Quirks, Lag Gauge NaN, @Order(1) Removal, Getter Visibility |
| Fail | 0 | — |

### Priority fixes for partial items (ordered by impact):

1. **Liveness/Readiness Split** — add health group config to `application.yml` (3-line YAML change)
2. **Lag Gauge NaN** — fix fleet-max `orElse(0L)` → `NaN` (1-line code change)
3. **Backoff Reset** — either guard with `awaitingFirstMessage` or remove dead code
4. **@Order(1) Removal** — delete annotation (1-line change)
5. **IDEA Test Quirks** — replace `null` with mock in `IngestLagTest`
6. **Getter Visibility** — remove `public` from `getWebSocket()`
7. **Reconnect Race** — null out `webSocket` at reconnect task start
8. **Dev Profile H2** — add automated test
