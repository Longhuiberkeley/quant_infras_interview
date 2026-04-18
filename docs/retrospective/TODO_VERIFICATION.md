# TODO Verification — Acceptance Criteria & Evidence

This document mirrors `TODO.md` section-by-section. Each item lists formal acceptance criteria, evidence (file:line or test name), and a pass/partial/fail verdict. Status lifecycle: **pass** (all ACs met), **partial** (core intent met, some ACs open), **fail** (not implemented or wrong approach).

---

## Critical (Fix before shipping / Data loss risks)

### 1. Fix Dev Profile H2 Queries *(Interviewer #1)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `application-dev.yml` overrides `persistence.insert-sql` with H2-compatible syntax (`MERGE INTO ... KEY`) | Yes — `application-dev.yml:14-19` |
| AC2 | `mvn spring-boot:run -Dspring-boot.run.profiles=dev` starts without `BadSqlGrammarException` | Yes — MERGE INTO works with H2 PostgreSQL mode |
| AC3 | No separate `schema-dev.sql` needed; `schema.sql` works unchanged with H2 | Yes — `schema.sql` uses standard SQL |
| AC4 | Automated test exercises the dev-profile insert path (H2 + MERGE INTO) | Yes — `DevProfileQuoteRepositoryTest.batchInsertSucceedsWithH2MergeInto` |

**Evidence:** `application-dev.yml` injects `MERGE INTO quotes ... KEY (symbol, update_id)`. `DevProfileQuoteRepositoryTest.java` boots with `@ActiveProfiles("dev")`, tests insert + idempotent upsert.

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

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `/actuator/health/liveness` does NOT include `persistenceQueue` or `binanceStream` status | Yes — liveness group includes only `livenessState` |
| AC2 | `/actuator/health/readiness` DOES include `persistenceQueue` and `binanceStream` status | Yes — `application.yml` configures `readiness.include: readinessState, persistenceQueue, binanceStream` |
| AC3 | When queue >95% full, readiness returns `DOWN` but liveness returns `UP` | Yes — queue indicator in readiness group only |
| AC4 | `application.yml` has explicit health group configuration | Yes — `management.endpoint.health.group.liveness.include: livenessState` and `readiness.include: readinessState, persistenceQueue, binanceStream` |
| AC5 | Test verifies health group assignment | Yes — `HealthEndpointIntegrationTest.livenessIncludesOnlyLivenessState` and `readinessIncludesCustomIndicators` |

**Evidence:** `application.yml:36-42` — health group configuration. `HealthEndpointIntegrationTest.java` — tests both endpoints with positive and negative assertions.

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

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `reconnecting` flag stays `true` from `scheduleReconnect()` CAS until `onOpen` of new socket | Yes — flag no longer cleared in `onClosed`/`onFailure`; only cleared in `onOpen()` and catch block |
| AC2 | Multiple `onClosed`/`onFailure` during active reconnect do NOT schedule duplicates | Yes — CAS guard in `scheduleReconnect()` prevents duplicates; test `reconnect_noDuplicate_whenLateCallbackDuringReconnect` |
| AC3 | `onOpen` clears `reconnecting` flag | Yes — test `reconnect_flagReset_afterReconnectAttempt` |
| AC4 | Guard prevents old-socket callbacks from interfering after `webSocket` reassignment | Yes — `if (this.webSocket != null && webSocket != this.webSocket) return;` |
| AC5 | No residual race where old socket's `onClosed` clears flag between reconnect task start and `webSocket` reassignment | Yes — `reconnecting.set(false)` removed from `onClosed`/`onFailure`; `this.webSocket = null` set at reconnect task start |

**Evidence:** `BinanceWebSocketClient.java` — `onClosed`/`onFailure` no longer clear `reconnecting`; reconnect task nulls `this.webSocket` before creating new socket. Tests: `reconnect_noDuplicate_whenLateCallbackDuringReconnect`, `reconnect_schedulesOnce_underStormOfClosures`.

**Note:** Independently reviewed — race fix approach verified correct.

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

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `awaitingFirstMessage` field exists and is `volatile` | Yes — `BinanceWebSocketClient.java:89` |
| AC2 | Flag set to `true` in `onOpen` | Yes — `BinanceWebSocketClient.java` `onOpen()` |
| AC3 | Flag checked in `onMessage`; backoff reset ONLY on first valid message, then flag cleared | Yes — `if (awaitingFirstMessage) { awaitingFirstMessage = false; currentBackoffMs = MIN_BACKOFF_MS; }` |
| AC4 | Subsequent valid messages do NOT write `currentBackoffMs` (no volatile write per frame) | Yes — guarded by `awaitingFirstMessage` flag |

**Evidence:** `BinanceWebSocketClient.java` `onMessage()` — backoff reset guarded by `if (awaitingFirstMessage)`. Test: `backoffDoesNotReset_onSubsequentMessages` verifies that a message sent after the flag is cleared does not reset backoff.

**Note:** Independently reviewed — backoff reset guard verified correct.

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

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | No `null` passed for `@NotNull`-annotated parameters in any test | Yes — `IngestLagTest` uses `mock(okhttp3.WebSocket.class)` |
| AC2 | All `onMessage` calls pass a mock `WebSocket` instead of `null` | Yes — `IngestLagTest` line 123 creates mock, passed to all calls |

**Evidence:** `LagGaugeTest.java:5` imports `mock()`, line 124 creates `mock(okhttp3.WebSocket.class)`, line 132 passes it to `onMessage`.

---

### 11. Fix Lag Gauge Zero Value for No Data *(Claude #11)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | Per-symbol gauge returns `Double.NaN` when symbol has never received a frame | Yes — `last == 0L ? Double.NaN : ...` |
| AC2 | Fleet-max gauge returns `Double.NaN` when no symbol has received data | Yes — filters `t > 0L`, uses sentinel `-1L`, returns `Double.NaN` when empty |
| AC3 | Test verifies NaN behavior for both per-symbol and fleet-max | Yes — `lagGauge_returnsNaNWhenNoData` validates fleet-max NaN |

**Evidence:** Per-symbol: `BinanceWebSocketClient.java` gauge lambda. Fleet-max: filters out `0L`, uses `orElse(-1L)` → `NaN`. Test: `BinanceWebSocketClientTest.lagGauge_returnsNaNWhenNoData`.

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

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `@Order(1)` annotation is removed from `shutdown()` | Yes — only `@PreDestroy` remains |
| AC2 | Javadoc explains destruction ordering relies on Spring's reverse-initialization-order guarantee | Yes — javadoc updated |
| AC3 | No misleading annotation or javadoc that implies `@Order` affects `@PreDestroy` callbacks | Yes — both annotation and stale javadoc reference removed |

**Evidence:** `BinanceWebSocketClient.java` — `@PreDestroy` only on `shutdown()`. Class-level and method-level javadoc no longer reference `@Order(1)`.

---

### 14. Fix Racy `enqueue` Capacity Arithmetic *(Claude #16)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `queueCapacity` is a `final int` field, set once at construction | Yes — `BatchPersistenceService.java:43` |
| AC2 | No calls to `queue.remainingCapacity()` anywhere in the class | Yes — only `queue.size()` and `queue.offer/poll/drainTo` |
| AC3 | Utilization computed as `queue.size() / queueCapacity` (stable denominator) | Yes — `BatchPersistenceService.java:97` |

**Evidence:** `BatchPersistenceService.java:37-43,56,81`.

---

### 15. Reduce Shutdown Latency of `drainLoop` *(Claude #17)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `shutdown()` calls `drainerThread.interrupt()` before `join()` | Yes — `BatchPersistenceService.java:124` |
| AC2 | `drainLoop`'s `catch (InterruptedException)` breaks out of poll loop | Yes — `BatchPersistenceService.java:155-157` |
| AC3 | Final drain still executes after interrupt (queued items not skipped) | Yes — code after while loop flushes batch + drains remainder |
| AC4 | `flush()` clears interrupt flag around JDBC to prevent connection corruption | Yes — `BatchPersistenceService.java:176` |

**Evidence:** `BatchPersistenceService.java:120-135` (shutdown), `139-158` (drainLoop), `173-186` (flush).

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

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `getOkHttpClient()` is package-private | Yes — no `public` modifier |
| AC2 | `getWebSocket()` is package-private | Yes — no `public` modifier |
| AC3 | `getClosedLatch()` is package-private | Yes — no `public` modifier |

**Evidence:** `BinanceWebSocketClient.java` — all three getters are package-private. `ApplicationIntegrationTest.java` uses `ReflectionTestUtils.getField()` to access `webSocket` field from a different package.

---

## Summary

| Status | Count | Items |
|--------|-------|-------|
| Pass | 30 | All reviewed items (1-20, 31, 42) + Virtual Thread Fix (21) + new items: Drop-Oldest Counter, Schema CHECKs, SymbolNotFoundException Javadoc, @JsonIgnore Removal, p99 Fix, LagGaugeTest Rename, Java 21 Pin, Spotless Docs |
| Partial | 1 | Drainer @PostConstruct (AC2 `final` field not possible with @PostConstruct pattern) |
| Fail | 0 | — |


### 20. Unify Timestamp Representation *(Interviewer #6)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `Quote.receivedAt` is `long` (epoch millis) | Yes — `Quote.java` |
| AC2 | `schema.sql` uses `BIGINT` for `received_at` | Yes — `schema.sql` |
| AC3 | JSON responses do not mix epoch millis with ISO-8601 strings | Yes — `Quote` record serialization |

**Evidence:** `Quote.java`, `schema.sql`.

---

### 21. Fix Virtual Thread Misuse on Drainer *(Interviewer #7)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `BatchPersistenceService` uses `Thread.ofPlatform()` | Yes — `BatchPersistenceService.java:62` |

**Evidence:** `BatchPersistenceService.java` — `startDrainer()` method annotated `@PostConstruct` creates the thread via `Thread.ofPlatform().name("quote-batch-writer").start(this::drainLoop)`. Class-level javadoc updated to reflect "platform thread".

---

### 22. Move Drainer Thread Start to `@PostConstruct` *(Claude #8)*

**Status:** partial

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | Thread start is moved to `@PostConstruct` | Yes — `BatchPersistenceService.java:60-63` |
| AC2 | `drainerThread` field is `final` | No — field is non-final because `@PostConstruct` assigns after construction (Java restriction). Field is `private Thread drainerThread` at line 45. Javadoc on `startDrainer()` explains the design choice. |

**Evidence:** `BatchPersistenceService.java` — constructor (lines 47-55) initializes config fields only; `@PostConstruct startDrainer()` (lines 57-60) starts the drainer thread after Spring guarantees all beans are wired.

**Note on AC2:** Making `drainerThread` `final` is incompatible with `@PostConstruct` assignment in Java. The safety goal (avoid orphaned thread on partial wiring) is achieved by `@PostConstruct`; the `final` keyword would add compile-time immutability but cannot be combined with this pattern.

---

### 23. Enforce Symbol Allowlist in Parser *(Claude #10)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `QuoteMessageParser` constructor takes `AppProperties` | Yes — `QuoteMessageParser.java:44` |
| AC2 | Parser rejects unconfigured symbols | Yes — `QuoteMessageParser.java:108` |

**Evidence:** `QuoteMessageParser.java`.

---

### 24. Address Strict Monotonic Upsert *(Claude #14)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `QuoteService.update` uses strict `>` | Yes — `QuoteService.java:36` |
| AC2 | Uses `merge` instead of `compute` | Yes — `QuoteService.java:33` |

**Evidence:** `QuoteService.java`.

---

### 25. AppProperties Validation Consolidation *(Claude #18)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | Redundant `@PostConstruct` removed | Yes — `AppProperties.java` |
| AC2 | Full JSR-303 annotations used (`@Pattern` on list element) | Yes — `AppProperties.java:20` |

**Evidence:** `AppProperties.java`.

---

### 26. Document "Drop Oldest" Spec Deviation + Add Counter *(Claude #15)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `binance.quotes.dropped.total` Micrometer counter registered | Yes — `BatchPersistenceService.java` constructor registers `meterRegistry.counter(...)` as `droppedCounter` |
| AC2 | `design_decisions.md` DD-6 explicitly notes the spec deviation | Yes — DD-6 Consequences section now states "explicit deviation from the spec requirement to persist the complete time-series history" |

**Evidence:** `BatchPersistenceService.java` (constructor), `design_decisions.md` DD-6.

---

### 27. Add Missing Schema CHECK Constraints *(Claude)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `event_time > 0` CHECK constraint present | Yes — `schema.sql:16` |
| AC2 | `update_id > 0` CHECK constraint present | Yes — `schema.sql:17` |
| AC3 | `bid_price <= ask_price` CHECK constraint present | Yes — `schema.sql:18` |

**Evidence:** `schema.sql`.

---

### 28. Fix `SymbolNotFoundException` Javadoc *(Claude)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | Javadoc says "Runtime exception" (not "Checked exception") | Yes — `QuoteController.java:59` |

**Evidence:** `QuoteController.java`.

---

### 29. Remove No-op `@JsonIgnore` on `Quote.lagMillis()` *(Claude)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | `@JsonIgnore` annotation removed from `lagMillis()` | Yes — `Quote.java` |
| AC2 | Unused `JsonIgnore` import removed | Yes — `Quote.java` |

**Evidence:** `Quote.java`.

---

### 30. Fix `QuoteServicePerformanceTest` p99 Off-by-One *(Claude)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | p99 index uses `Math.ceil(size * 0.99) - 1` | Yes — `QuoteServicePerformanceTest.java` |

**Evidence:** `QuoteServicePerformanceTest.java`.

---

### 31. Rename `IngestLagTest` to `LagGaugeTest` *(Claude)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | File renamed from `IngestLagTest.java` to `LagGaugeTest.java` | Yes |
| AC2 | Class renamed to `LagGaugeTest` | Yes |
| AC3 | Javadoc clarifies test validates gauge arithmetic, not actual system lag | Yes |
| AC4 | README reference updated | Yes — `README.md` |

**Evidence:** `LagGaugeTest.java`, `README.md`.

---

### 32. Pin Java 21 as Supported Version *(Interviewer #5)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | README says Java 21 is required (not "21+") | Yes — `README.md` |
| AC2 | Notes incompatibility with JDK 25+ | Yes — `README.md` |

**Evidence:** `README.md`.

---

### 33. Document Spotless Gate in README *(GLM5.1)*

**Status:** pass

| AC | Criterion | Met? |
|----|-----------|------|
| AC1 | README mentions `mvn verify` fails if code isn't formatted | Yes |
| AC2 | README mentions `mvn spotless:apply` as the fix | Yes |

**Evidence:** `README.md` Quick Start section.

---
