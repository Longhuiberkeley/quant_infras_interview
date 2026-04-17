# Final Consolidated TODO List

This document synthesizes feedback from the interviewer, automated AI reviews (Claude, GLM5.1, Minimax), and personal retrospective notes. Every item is a trackable checkbox. Sources are tagged in parentheses. Checkbox lifecycle: `[ ]` (open) → `[dev-done]` (implemented, awaiting review) → `[reviewed]` (verified against code) → `[incomplete]` (partially done or review found gaps).

> **Verification:** Each `[dev-done]`, `[reviewed]`, and `[incomplete]` item has formal acceptance criteria and evidence in [`TODO_VERIFICATION.md`](./TODO_VERIFICATION.md). Items marked `[incomplete]` have open ACs listed there — fix those gaps before flipping back to `[dev-done]`.

---

## Critical (Fix before shipping / Data loss risks)

- [dev-done] **(MUST) Fix Dev Profile H2 Queries:** `mvn spring-boot:run -Dspring-boot.run.profiles=dev` fails at runtime — H2 in PostgreSQL mode does not support `ON CONFLICT (symbol, update_id) DO NOTHING` in the batch INSERT. The schema creates fine, but `QuoteRepository.batchInsert` throws `BadSqlGrammarException`. Provide an H2-compatible SQL variant (e.g. `MERGE INTO`) or use a separate `schema-dev.sql`. *(Interviewer #1)*
- [reviewed] **Fix Container Healthcheck (Docker):** `wget` is missing in `eclipse-temurin:21-jre`, so the `docker-compose.yml` healthcheck always fails and the container is marked unhealthy. Install `curl` in the Dockerfile runtime stage and switch the probe to `curl -fsS`. *(Claude #1)*
- [reviewed] **Fix Graceful Shutdown (PID-1 Issue):** `ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]` runs `sh` as PID 1, which does not forward SIGTERM. The entire `@PreDestroy` drain story silently degrades to SIGKILL after 10s. Fix with `exec` or switch to exec-form `ENTRYPOINT`. *(Claude #2)*
- [incomplete] **Liveness vs. Readiness Health Check Split:** `PersistenceQueueHealthIndicator` returns `DOWN` at >95% queue utilization, feeding `/actuator/health`, which triggers a container restart that wipes the in-memory map and persistence queue (~10k quotes lost). Backpressure and stale-stream signals belong in readiness, not liveness. Use Spring Boot health groups. *(Claude #3)*
- [dev-done] **Fix `QuoteRepository.batchInsert` Logged Count Bug:** PostgreSQL JDBC returns `SUCCESS_NO_INFO` (`-2`) for batch operations. The `r > 0` filter discards these, so the logged "Persisted X/Y quotes" always shows 0 on PostgreSQL. The rows ARE inserted — the data path is correct — but the count is unreliable. Accept `-2` as "presumed inserted" or stop reporting an exact count. *(Claude #5)*
- [dev-done] **Fix `BinanceWebSocketClient` Reconnect Race:** `reconnecting.set(false)` is cleared at line 312 BEFORE the new socket is established at line 318. Between those lines, a late `onClosed`/`onFailure` callback from the previous socket can pass the CAS and schedule a duplicate reconnect. Clear the flag only after successful `onOpen`. *(Claude #6)*
- [dev-done] **Fix `scheduleReconnect` Exception Handling:** If `newWebSocket` throws inside the reconnect task, the catch block calls `scheduleReconnect()` while still running on the scheduler thread. Although `scheduler.schedule(...)` is async (not truly recursive), the reconnecting flag is already cleared, so a new reconnect is immediately scheduled — potentially stacking attempts under sustained failure. Hand off via a fresh `scheduler.schedule(...)` in the catch block and avoid clearing the flag before socket establishment. *(Claude #7)*
- [incomplete] **Fix WebSocket Backoff Reset Bug:** `BinanceWebSocketClient` resets `currentBackoffMs` on **every** `onMessage` (line 251), not just the first message post-open. At ~500 msg/s this is a per-frame volatile write, and the documented "fast-reconnect / immediate-close loop" defense doesn't actually exist. Add a `volatile boolean awaitingFirstMessage` flag set in `onOpen`, cleared on first message. *(Claude #4)*

---

## High (Architecture, Performance, & Testing)

- [ ] **(MUST) Add Tick-to-Trade Latency Test:** Measure the latency between the exchange timestamp (`eventTime`) on a quote and the timestamp when it is served to the client via REST. The existing `ApplicationIntegrationTest.ingestLatencyUnder5ms` stops at the in-memory map; it doesn't include REST serialization or HTTP round-trip. Need a full-path test: WS frame → parser → QuoteService → REST GET → response. *(Interviewer #2)*
- [ ] **(MUST) Add Quote-to-DB Persistence Latency Test (Testcontainers):** Measure the latency between quote receipt (`receivedAt`) and the moment the row is confirmed in PostgreSQL. Nothing currently measures this — `sustains500rps` uses a mocked `QuoteRepository`, so actual I/O is never exercised. Use Testcontainers for a real PostgreSQL round-trip. *(Interviewer #2)*
- [ ] **(MUST) Add Live Throughput Test:** Measure how many quotes are received per second and what the end-to-end latency distribution looks like under realistic conditions. All existing perf tests use mocks (MockWebServer, mock QuoteRepository). A test with Testcontainers PostgreSQL + MockWebServer would be the closest to real. *(Interviewer #2)*
- [ ] **Add Concurrent Read Latency Under Write Contention:** `QuoteServicePerformanceTest` is single-threaded. Add a multi-threaded variant: N reader threads + continuous writer thread, assert p99 read latency stays under SLO. Tests `ConcurrentHashMap` behavior under realistic contention. *(Audit gap)*
- [ ] **Add Batch Insert Throughput Against Real PostgreSQL:** `sustains500rps` proves the enqueue path is fast with a mock. Add a Testcontainers-based variant that measures actual `batchInsert` throughput (rows/sec) against PostgreSQL 16 to validate the "10x headroom" claim against real I/O. *(Audit gap)*
- [ ] **Add Backpressure Recovery Test:** Simulate a DB outage (pause Testcontainers), let the queue fill and start dropping, unpause, and measure: (a) how many quotes were dropped, (b) time to drain the backlog, (c) whether the system self-heals without intervention. *(Audit gap)*
- [ ] **Add Reconnect Recovery Latency Test:** Measure time from WebSocket disconnect to first new quote available in the in-memory map. The existing `reconnectAfterNetworkDrop` test verifies reconnect *works* but doesn't measure *how long* it takes. *(Audit gap)*
- [ ] **(MUST) Unify Timestamp Representation:** Resolve inconsistency between `BIGINT` (eventTime/transactionTime) and `TIMESTAMP WITH TIME ZONE` (receivedAt) in `schema.sql` and API responses. The JSON response mixes epoch millis with ISO-8601 strings for the same conceptual type. *(Interviewer #6)*
- [ ] **(MUST) Fix Virtual Thread Misuse on Drainer:** `BatchPersistenceService#drainerThread` is a single long-lived virtual thread. Virtual threads shine when you have thousands of short-lived I/O-blocking tasks; for one dedicated loop running for the app's lifetime, virtual vs. platform makes no difference. Convert to a platform thread. *(Interviewer #7)*
- [ ] **Move Drainer Thread Start to `@PostConstruct`:** The drainer thread is started in the `BatchPersistenceService` constructor (line 53). If Spring fails to wire a subsequent bean, the thread is orphaned and `shuttingDown` is never set. Move to `@PostConstruct` and mark the field `final`. *(Claude #8)*
- [ ] **Enhance Persistence Retry Logic:** On a transient DB outage, `flush` fails and then `retryOneByOne` fires `batchSize x 3` (default 600) failed connection attempts, each blocking the single drainer thread on `Thread.sleep`. Meanwhile the queue fills and drops. Distinguish connection-class exceptions (`SQLTransientConnectionException`, `CannotGetJdbcConnectionException`) from row-level errors; retry the whole batch with capped backoff for the former, per-row retry only for the latter. *(Claude #9)*

---

## Medium (Code Cleanliness, Correctness, & Observability)

- [dev-done] **(MUST) Suppress Expected Exception Stack Traces in Tests:** Clean up the build console output by silencing expected exception stack traces during testing. The exceptions are confusing — are they real errors the build ignores, or expected? *(Interviewer #4)*
- [incomplete] **(MUST) Fix IDEA Test Quirks:** Remove `@NotNull` violations (e.g., passing `null` for unused `webSocket` parameter in `IngestLagTest.java:133`). Works in Maven but fails in IntelliJ IDEA. *(Interviewer #3)*
- [ ] **Enforce Symbol Allowlist in Parser:** `QuoteMessageParser` accepts any symbol Binance sends. An unconfigured symbol creates an unregistered map entry in `BinanceWebSocketClient` (no Micrometer gauge), pollutes `QuoteService.all()`, and bypasses the "exactly 10" startup invariant. Have the parser reject symbols not in `AppProperties`. *(Claude #10)*
- [incomplete] **Fix Lag Gauge Zero Value for No Data:** A symbol that has never received a frame reports 0ms lag — the *best possible* value — when it should report "no data." Anyone alerting on the gauge will miss a fully-broken symbol. Return `Double.NaN` instead of `0L`. *(Claude #11)*
- [dev-done] **Thread-safe Latch Reassignment:** `closedLatch` is reassigned in `start()` and `scheduleReconnect()`, read by `shutdown()`. A reconnect concurrent with `shutdown()` can leave shutdown awaiting a stale latch. Use `AtomicReference<CountDownLatch>` and capture into a local in `shutdown()`. *(Claude #12)*
- [incomplete] **Remove No-op `@Order(1)` on `@PreDestroy`:** Spring honors `@Order` for collection injection and `@EventListener`, not destruction callbacks. The annotation is misleading. Delete it; document the construction-order dependency in javadoc instead. *(Claude #13)*
- [ ] **Document "Drop Oldest" Spec Deviation + Add Counter:** Spec says "persist the *complete* time-series history." The system silently drops on overflow with only a WARN log. Add a `binance.quotes.dropped.total` Micrometer counter and document this as an explicit spec deviation in `design_decisions.md`. *(Claude #15)*
- [reviewed] **Fix Racy `enqueue` Capacity Arithmetic:** `queue.remainingCapacity() + queue.size()` is not read atomically, so the utilization percentage is meaningless under contention. Store capacity as a `final int queueCapacity` field at construction time. *(Claude #16)*
- [reviewed] **Reduce Shutdown Latency of `drainLoop`:** `poll()` blocks for up to `flushMs` (500ms). On shutdown, `interrupt()` the drainer thread to break out immediately. The existing `catch (InterruptedException) { break; }` already handles it. *(Claude #17)*
- [reviewed] **Move DEBUG Logging Out of Base Profile:** `application.yml` line 42 sets `com.quant.binancequotes: DEBUG` in the base profile, which applies to production. Move to `application-dev.yml`. *(Claude #19)*

---

## Low (Nits, Tooling, Docs, & Compatibility)

- [ ] **(MUST) Pin Java 21 as Supported Version:** Tests only pass on Java 21; they fail on 25/26. Document Java 21 as the required version in the README. Expanding to newer JDKs is a separate effort. *(Interviewer #5)*
- [ ] **(MUST) Improve Build Time:** Build takes over a minute, which is too long for this amount of code. Consider adding a Maven dependency cache layer to the Docker build and profiling the test suite. *(Interviewer #8, Claude)*
- [ ] **Docker Dependency in Maven Verify:** Clarify in the README whether `mvn verify` requires Docker (for `DockerComposeSmokeTest`) or is strictly hermetic. *(Claude)*
- [ ] **Address Strict Monotonic Upsert:** `QuoteService.update` uses `>=` which permits idempotent overwrite on duplicate `updateId`. Change to strict `>` and consider `merge` over `compute`. *(Claude #14)*
- [ ] **AppProperties Validation Consolidation:** `@Size(10,10)` plus `@PostConstruct` regex check are redundant. Use JSR-303 only (`@Pattern` per element) or imperative only — not both. *(Claude #18)*
- [reviewed] **Fix Hardcoded JAR Name in Dockerfile:** `binance-quote-service-0.1.0-SNAPSHOT.jar` breaks silently on version bump. Use a glob or set `<finalName>` in the POM. *(Claude)*
- [reviewed] **Use Official Maven Docker Image:** `apt-get install -y maven` in Dockerfile ties the build to the Debian repo's Maven version. Use `maven:3.9-eclipse-temurin-21` as the build stage instead. *(Claude)*
- [ ] **Add Missing Schema CHECK Constraints:** Docs claim CHECK constraints "backstop all business invariants," but `event_time > 0`, `update_id > 0`, and `bid_price <= ask_price` are absent. Add them. *(Claude)*
- [ ] **Fix `SymbolNotFoundException` Javadoc:** Says "checked exception" but the class `extends RuntimeException`. *(Claude)*
- [ ] **Remove No-op `@JsonIgnore` on `Quote.lagMillis()`:** Records serialize by component, not accessor method. The annotation does nothing. *(Claude)*
- [ ] **Fix `QuoteServicePerformanceTest` p99 Off-by-One:** `latencies.get((int)(size * 0.99))` should use `Math.ceil`-based indexing. *(Claude)*
- [ ] **Rename `IngestLagTest` to Match What It Proves:** The test hard-codes `eventTime = now - 50` so it only validates gauge math, not actual system lag. Rename to `lagGaugeReportsMeasuredAge`. *(Claude)*
- [incomplete] **Tighten Test-Only Getter Visibility:** `BinanceWebSocketClient` exposes 8 public getters purely for tests. Make unused ones (`getOkHttpClient`, `getWebSocket`, `getClosedLatch`) package-private. *(Claude #20)*
- [ ] **Document Spotless Gate in README:** `mvn verify` fails if code isn't formatted. This could trip an interviewer building locally. Mention explicitly. *(GLM5.1)*

---

## Optional

- [ ] **Address API Rate Limiting / Concurrency Protection:** While DD-8 acknowledges this as a trade-off, adding basic rate limiting (e.g., using Bucket4j or a simple Semaphore) would show extreme thoroughness for a production-grade service. *(Claude Review)*
- [ ] **Implement Historical Data REST API:** Add an endpoint to query historical quotes from the database (e.g., `GET /api/quotes/{symbol}/history?from=X&to=Y`), demonstrating the persistence layer is actually queryable. Out of scope per current spec (latest-only), but noted as future enhancement. *(Personal Retro, Claude, GLM5.1)*
