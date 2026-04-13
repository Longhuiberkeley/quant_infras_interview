# Implementation Plan

This is the authoritative phased plan the build follows. Each phase ends with a committable, runnable state. Target ~9h active work inside the 1-day window; ~3h buffer.

Companion docs:
- [`interviewer_requirements.md`](./interviewer_requirements.md) — what was asked.
- [`architecture.md`](./architecture.md) — what we're building.
- [`design_decisions.md`](./design_decisions.md) — why these choices (ADR-style).
- [`failure_modes.md`](./failure_modes.md) — runtime risks and mitigations.
- [`requirement_traceability.md`](./requirement_traceability.md) — req → code → test, including NFR (FM-*).

## 1. Project Structure

```
binance-quote-service/
├── docs/
│   ├── interviewer_requirements.md
│   ├── architecture.md
│   ├── design_decisions.md
│   ├── failure_modes.md
│   ├── requirement_traceability.md
│   ├── implementation_plan.md       (this file)
│   ├── audit_checklist.md           (Phase 7.5: structured pre-submission audit)
│   ├── audit_results.md             (Phase 7.5: execution record)
│   ├── phase7_results.md            (Phase 7.1–7.4: evidence capture, per-phase)
│   └── journal.md                   (development log)
│
├── src/
│   ├── main/
│   │   ├── java/com/quant/binancequotes/
│   │   │   ├── BinanceQuoteServiceApplication.java
│   │   │   │
│   │   │   ├── config/
│   │   │   │   ├── AppProperties.java         (@ConfigurationProperties: symbol list, validation)
│   │   │   │   ├── BinanceProperties.java     (WS URL, proxy, staleness threshold)
│   │   │   │   └── PersistenceProperties.java (queue size, batch size, flush ms)
│   │   │   │
│   │   │   ├── model/
│   │   │   │   └── Quote.java                 (record, BigDecimal fields — serialized directly, no DTO; DD-12)
│   │   │   │
│   │   │   ├── websocket/
│   │   │   │   ├── BinanceWebSocketClient.java
│   │   │   │   └── QuoteMessageParser.java
│   │   │   │
│   │   │   ├── service/
│   │   │   │   ├── QuoteService.java          (ConcurrentHashMap)
│   │   │   │   └── BatchPersistenceService.java
│   │   │   │
│   │   │   ├── repository/
│   │   │   │   └── QuoteRepository.java       (JdbcClient batchUpdate)
│   │   │   │
│   │   │   ├── controller/
│   │   │   │   ├── QuoteController.java
│   │   │   │   └── ApiExceptionHandler.java   (@ControllerAdvice)
│   │   │   │
│   │   │   └── health/
│   │   │       ├── BinanceStreamHealthIndicator.java
│   │   │       └── PersistenceQueueHealthIndicator.java
│   │   │
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml            (H2 fallback)
│   │       └── schema.sql
│   │
│   └── test/
│       └── java/com/quant/binancequotes/
│           ├── model/QuoteTest.java
│           ├── config/{AppPropertiesTest, AppPropertiesIntegrationTest}.java
│           ├── websocket/{QuoteMessageParserTest, BinanceWebSocketClientTest}.java
│           ├── service/{QuoteServiceTest, BatchPersistenceServiceTest,
│           │              QuoteServicePerformanceTest}.java
│           ├── repository/QuoteRepositoryIntegrationTest.java  (Testcontainers)
│           ├── controller/QuoteControllerTest.java
│           ├── IngestLagTest.java
│           ├── QuoteRoundTripTest.java                         (JSON → DB precision)
│           ├── ApplicationIntegrationTest.java                 (@SpringBootTest + MockWebServer)
│           ├── DockerComposeSmokeTest.java                     (Docker Compose sanity)
│           └── health/{BinanceStreamHealthIndicatorTest,
│                         PersistenceQueueHealthIndicatorTest}.java
│
├── docker-compose.yml
├── Dockerfile
├── pom.xml
├── README.md
├── CLAUDE.md
├── .gitignore
└── .env.example
```

## 2. Maven Dependencies (summary)

```
spring-boot-starter-web
spring-boot-starter-jdbc          (NOT starter-data-jpa — see DD-1)
spring-boot-starter-actuator
spring-boot-starter-validation    (for @ConfigurationProperties validation)
okhttp                            (4.12.x)
postgresql                        (runtime)
h2                                (test + dev runtime)
spring-boot-starter-test          (JUnit 5, Mockito, MockMvc)
testcontainers:postgresql
okhttp-mockwebserver              (test)

Plugins:
  spring-boot-maven-plugin
  spotless-maven-plugin           (google-java-format 1.22+, bound to verify)
```

## 3. Phases

Each phase lists its **goal**, **deliverables**, **review gate** (what to verify before moving on), and **commit message**.

---

### Phase 0 — Bootstrap & Standards (~30 min)

**Goal.** Committable skeleton that starts under `dev` profile with H2, builds cleanly, enforces style.

**Deliverables.**
- `pom.xml` with the dependency set from §2 plus Spotless plugin bound to `verify`.
- `.gitignore` (Java/Maven/IntelliJ standard).
- `BinanceQuoteServiceApplication.java`.
- `application.yml` — `spring.threads.virtual.enabled=true`, `spring.sql.init.mode=always`, actuator exposure.
- `application-dev.yml` — H2 file DB fallback.

**Review before moving on.**
- **Gate command:** `mvn clean verify` passes.
- Spotless actually rejects unformatted code (introduce a stray space, confirm `mvn verify` fails).
- `mvn dependency:tree | grep -i jpa` returns nothing.
- Boot log includes the virtual-threads line.
- `.gitignore` excludes `target/`, `.idea/`, `*.iml`, `.env`, `*.log`.

**Commit.** `chore: bootstrap Spring Boot skeleton with style enforcement`.

---

### Phase 1 — Domain Model & Schema (~30 min)

**Goal.** Precise financial types and a schema that supports natural dedup.

**Deliverables.**
- `Quote.java` — `record Quote(String symbol, BigDecimal bid, BigDecimal bidSize, BigDecimal ask, BigDecimal askSize, long updateId, long eventTime, long transactionTime, Instant receivedAt)`. `eventTime` maps to Binance `E`, `transactionTime` to Binance `T` (both ms since epoch; available on USDT-M Perps `@bookTicker` — see DD-10).
- No `QuoteDto` (DD-12): the record is serialized directly by the controller.
- `AppProperties.java` — `@ConfigurationProperties("app")` with `@NotNull`, `@Size(min=10, max=10)` validation on the symbol list + `@Pattern("[A-Z]+USDT")`.
- `schema.sql` — per `architecture.md` §6 (includes `transaction_time` column and CHECK constraints per DD-13).
- Unit: `QuoteTest`, `AppPropertiesTest`.

**Review before moving on.**
- **Gate command:** `mvn clean verify` passes.
- All monetary fields are `BigDecimal`, `Quote` is immutable.
- Schema has `UNIQUE(symbol, update_id)`, `transaction_time` column, the composite index, and CHECK constraints on `bid_price > 0`, `ask_price > 0`, `bid_size >= 0`, `ask_size >= 0`.
- Deleting a symbol from the list prevents boot.
- No JPA annotations on any model class.

**Commit.** `feat(model): Quote domain, schema with natural dedup key`.

---

### Phase 2 — In-memory Quote Service (~30 min)

**Goal.** O(1) read/write path for latest quotes.

**Deliverables.**
- `QuoteService.java` — `ConcurrentHashMap<String, Quote>`; `update(Quote)`, `get(String): Optional<Quote>`, `all(): Map<String, Quote>`.
- Unit: `QuoteServiceTest` (concurrent correctness, monotonic-by-updateId).

**Review before moving on.**
- **Gate command:** `mvn clean verify` passes.
- `update` uses `compute`/`merge` — no TOCTOU races between `get` and `put`.
- `all()` returns an immutable snapshot.
- No `synchronized` anywhere.

**Commit.** `feat(service): in-memory latest-quote store`.

---

### Phase 3 — Persistence (JdbcClient, async batching) (~1.5 h)

**Goal.** Async batched writes with graceful shutdown and backpressure.

**Deliverables.**
- `QuoteRepository.java` — wraps `JdbcClient`; `batchInsert(List<Quote>)` with `INSERT ... ON CONFLICT (symbol, update_id) DO NOTHING`.
- `PersistenceProperties.java` — queue capacity, batch size, flush ms, shutdown timeout.
- `BatchPersistenceService.java` — bounded `LinkedBlockingQueue<Quote>` (10 k default), single named virtual thread drainer, drop-oldest above 90% with `WARN` log, capped exponential retry, `@PreDestroy` flushes with bounded timeout.
- `PersistenceQueueHealthIndicator.java`.
- Unit: `BatchPersistenceServiceTest`.
- Integration: `QuoteRepositoryIntegrationTest` (Testcontainers `postgres:16-alpine`) covering dedup conflict path.

**Review before moving on.**
- **Gate command:** `mvn clean verify` passes.
- Drainer thread appears as `quote-batch-writer` in `jcmd Thread.print`.
- Batch size / flush ms are config-driven, not magic numbers.
- Drop-oldest `WARN` fires in the overflow unit test.
- Shutdown test confirms queue drains within timeout.
- Zero `SELECT` statements in the write-path module.

**Commit.** `feat(persistence): async batched JdbcClient writer with dedup + graceful shutdown`.

---

### Phase 4 — WebSocket Ingestion (~2 h)

**Goal.** Targeted combined stream with a resilient reconnect loop and a freshness-lag gauge.

**Deliverables.**
- `BinanceProperties.java` — base URL (default `wss://fstream.binance.com`), optional proxy, staleness threshold.
- `QuoteMessageParser.java` — Jackson configured for `BigDecimal`; unwraps `{"stream":"...","data":{...}}`; parses `E` → `eventTime`, `T` → `transactionTime`; validates business invariants (`bid > 0`, `ask > 0`, `bid ≤ ask`, positive `eventTime` — see DD-13); returns `Optional<Quote>`; silently skips invalid and subscription acks.
- `BinanceWebSocketClient.java` — OkHttp `WebSocketListener`; combined-stream URL (`/stream?streams=...`) built from `AppProperties.symbols`; exponential backoff 1 → 2 → 4 → … → 60 s; atomic `AtomicBoolean reconnecting` guard; `volatile boolean shuttingDown` guard checked before every reconnect schedule; resets backoff **after first message**, not socket-open; on message → `QuoteService.update` + `BatchPersistenceService.enqueue`.
- **WebSocket graceful shutdown** — `@PreDestroy` sets `shuttingDown = true`, cancels pending reconnect, calls `webSocket.close(1000, "application shutdown")`, awaits `onClosed` with a bounded timeout (default 3 s). This must run **before** `BatchPersistenceService.@PreDestroy` so the drainer sees a closed input before flushing.
- `BinanceStreamHealthIndicator.java`.
- **Lag metric (per DD-11)** — two Micrometer gauges backed by one `ConcurrentHashMap<String, AtomicLong> lagBySymbol`:
  - Per-symbol: `Gauge.builder("binance.quote.lag.millis", lagBySymbol, m -> m.get(symbol).get()).tag("symbol", symbol).register(registry)` — one per configured symbol, registered at client construction.
  - Fleet-max: `Gauge.builder("binance.quote.lag.max.millis", lagBySymbol, m -> m.values().stream().mapToLong(AtomicLong::get).max().orElse(0L)).register(registry)` — single untagged gauge.
  - Hot path: `lagBySymbol.get(symbol).set(System.currentTimeMillis() - quote.eventTime())`. Zero allocation.
- Unit: `QuoteMessageParserTest` (includes `E`/`T` parsing), `BinanceWebSocketClientTest`.

**Review before moving on.**
- **Gate command:** `mvn clean verify` passes.
- Built URL uses `fstream.binance.com` and is logged once on startup.
- Storm of `onClosed` + `onFailure` spawns exactly one reconnect (unit test with atomic counter).
- `@PreDestroy` path: `shuttingDown` flag stops reconnect scheduling; close frame is sent; `onClosed` callback completes; drainer only starts its own `@PreDestroy` afterwards.
- Backoff resets only on first inbound message post-open.
- Parser handles the `/stream` wrapper, populates both `eventTime` and `transactionTime`.
- Parser rejects `bid > ask` (crossed spread), zero prices, and future-dated `eventTime` with a `WARN` log (see DD-13).
- Per-symbol lag gauges visible at `/actuator/metrics/binance.quote.lag.millis?tag=symbol:BTCUSDT`; fleet-max at `/actuator/metrics/binance.quote.lag.max.millis`.

**Commit.** `feat(ws): targeted combined-stream ingestion with resilient reconnect`.

---

### Phase 5 — REST API (~30 min)

**Goal.** Expose latest quotes over HTTP.

**Deliverables.**
- `QuoteController.java` — `GET /api/quotes`, `GET /api/quotes/{symbol}`. Returns `Quote` records directly (no DTO; DD-12).
- `ApiExceptionHandler.java` — `@ControllerAdvice` handling unknown-symbol 404.
- Jackson configured with `WRITE_BIGDECIMAL_AS_PLAIN` so responses never use scientific notation.
- Unit: `QuoteControllerTest` (`@WebMvcTest`).

**Review before moving on.**
- **Gate command:** `mvn clean verify` passes.
- 404 path is centralized in `@ControllerAdvice`.
- `BigDecimal` fields serialize as plain decimals (`67432.15`, not `6.743215E4`).
- 404 responses do not leak stack traces.

**Commit.** `feat(api): REST endpoints for latest quotes`.

---

### Phase 6 — Docker, Ops, Traceability Sync (~1 h)

**Goal.** One-command startup; docs reflect final system.

**Deliverables.**
- `Dockerfile` — multi-stage (`eclipse-temurin:21-jdk` → `:21-jre`), non-root user.
- `docker-compose.yml` — `postgres:16-alpine` + app; both with healthchecks; app depends on `service_healthy`.
- `.env.example` with DB credential placeholders.

**Review before moving on.**
- **Gate command:** `mvn clean verify` passes.
- Fresh-clone `docker compose up` lands `UP` within 60 s for all services.
- `/actuator/health` shows `UP` including `binanceStream` and `persistenceQueue`.

**Commit.** `chore(ops): docker compose with postgres healthcheck`.

---

### Phase 7.1 — Integration Happy Path (~45 min)

**Goal.** Prove the system works end-to-end: WebSocket frame → in-memory map → REST → DB. This is the single most important test of the project — if the plumbing is broken, nothing else matters.

**Deliverables.**
- `ApplicationIntegrationTest` — `@SpringBootTest` with Testcontainers PG + OkHttp `MockWebServer` simulating Binance.
  - Happy path: send mock `@bookTicker` frames for all 10 symbols → verify data appears in `QuoteService` map → verify `/api/quotes` returns all 10 via MockMvc → verify rows landed in PostgreSQL via raw JDBC `SELECT COUNT(*)`.
  - `ingestLatencyUnder5ms` — measure `System.nanoTime()` between MockWebServer enqueue and `QuoteService.get(symbol).map(Quote::updateId)` returning the expected value; run 1 000 iterations; log p50/p99; fail if p99 > 5 ms. Fulfils the SLO row in `requirement_traceability.md:153`.
  - Reconnect after network drop: enqueue `new MockResponse().withSocketPolicy(DISCONNECT_AFTER_REQUEST)` on a long-lived `MockWebServer` → verify reconnect counter increments → enqueue valid frames → verify map repopulates. (No port reuse — the server stays up; only the socket is severed.)
  - Dev profile boot: `@SpringBootTest` with `dev` profile, no PostgreSQL container → verify application context loads successfully.

**Review before moving on.**
- **Gate command:** `mvn clean verify` passes.
- Happy path test asserts data in all 3 sinks (map, REST response body, DB row count).
- Reconnect test verifies data flow resumes after reconnection (not just that reconnect was scheduled).
- Dev profile test asserts successful context load.
- No real Binance connection; no external DB. Fully hermetic.

**Commit.** `test(7.1): full-stack integration test with mock WebSocket`.

**Parallelisation.** This phase creates `ApplicationIntegrationTest.java` (new file). No existing files are modified. Safe to run in parallel with Phase 7.2.

---

### Phase 7.2 — Precision & Traceability Fix (~35 min)

**Goal.** Guarantee `BigDecimal` survives the full pipeline; fix naming drift between traceability docs and actual test method names.

**Deliverables.**
- `QuoteRoundTripTest` — Testcontainers PG integration test. Construct a `Quote` with 8-decimal-place `BigDecimal` values (e.g., `new BigDecimal("0.00000001")`). Stages asserted:
  1. Jackson serialize → deserialize: assert `scale()` and `unscaledValue()` preserved, no scientific notation in JSON string.
  2. INSERT to PostgreSQL via `QuoteRepository.batchInsert` → SELECT back: assert `BigDecimal` exact equality after DB round-trip.
  3. Combined: serialize → deserialize → INSERT → SELECT → serialize: assert end-to-end precision.
- Update `requirement_traceability.md` — rename the following method references to match actual code:

  | Old (doc) | New (actual) |
  |-----------|-------------|
  | `QuoteMessageParserTest#eventTimePreserved` | `QuoteMessageParserTest#eventTimeAndTransactionTime_mappedFromEAndT` |
  | `QuoteServiceTest#monotonicByUpdateId` | `QuoteServiceTest#newerUpdateIdReplacesOlder` (covers same contract) |
  | `QuoteRepositoryIntegrationTest#duplicateUpdateId_isIgnored` | `QuoteRepositoryIntegrationTest#duplicateSymbolUpdateIdIsSilentlyIgnored` |
  | `BatchPersistenceServiceTest#dropOldest_whenQueueAboveThreshold` | `BatchPersistenceServiceTest#dropOldestFiresWhenQueueOverflows` |
  | `BatchPersistenceServiceTest#shutdownFlushesQueue` | `BatchPersistenceServiceTest#shutdownDrainsRemainingQueue` |
  | `BatchPersistenceServiceTest#retriesOnTransientSqlFailure` | `BatchPersistenceServiceTest#retryOneByOneOnBatchFailure` |
  | `QuoteMessageParserTest#zeroPrice_returnsEmpty` | `QuoteMessageParserTest#zeroPrice_bid_returnsEmpty` + `#zeroPrice_ask_returnsEmpty` |

- Update `failure_modes.md` — rename 1 method reference in FM-5 (`duplicateUpdateId_isIgnored` → `duplicateSymbolUpdateIdIsSilentlyIgnored`).

**Review before moving on.**
- **Gate command:** `mvn clean verify` passes.
- `QuoteRoundTripTest` asserts `BigDecimal.scale()`, `BigDecimal.unscaledValue()`, and plain-decimal string form at each boundary (JSON, DB).
- Every `Test#methodName` in `requirement_traceability.md` resolves to an actual method in `src/test/` (run `rg "Test#" docs/requirement_traceability.md` and verify each).
- No `1E-8` or scientific notation in any serialized JSON output.

**Commit.** `test(7.2): BigDecimal precision round-trip + fix traceability naming drift`.

**Parallelisation.** This phase creates `QuoteRoundTripTest.java` (new file) and edits doc files. No overlap with Phase 7.1 or 7.3 test files. Safe to run in parallel with Phase 7.1.

---

### Phase 7.3 — Performance & SLO Validation (~30 min)

**Goal.** Empirically prove every SLO with a test that measures and asserts. "Performance is a top priority" is REQ-6 — these tests must exist.

**Deliverables.**
- `QuoteServicePerformanceTest` — populate `ConcurrentHashMap` with 10 quotes; run 10 000 reads per symbol; log p50/p99; fail if p99 > 1 ms.
- `IngestLagTest` — pump 500 mock messages/sec through `BinanceWebSocketClient` with frozen clock; assert `binance.quote.lag.max.millis` gauge stays under 500 ms at steady state.
- `QuoteControllerTest#p99LatencyUnder5ms` — add method to existing class; 1 000 MockMvc calls to `GET /api/quotes`; log p99; fail if > 5 ms.
- Unit tests for both health indicators:
  - `BinanceStreamHealthIndicatorTest` — verify `UP` when connected + recent message; `DOWN` when disconnected or stale.
  - `PersistenceQueueHealthIndicatorTest` — verify `UP` when queue depth < 90%; `DOWN` when queue depth ≥ 90%.

**Review before moving on.**
- **Gate command:** `mvn clean verify` passes.
- All 5 SLO rows in `architecture.md` §8 map to a test method that runs and passes.
- Performance thresholds are logged (not just asserted) so p50/p99 numbers are visible in CI output.
- Health indicator tests verify both `UP` and `DOWN` states.
- `QuoteServicePerformanceTest` uses `System.nanoTime()` (not `currentTimeMillis()`) for sub-millisecond precision.

**Commit.** `test(7.3): SLO validation — performance, lag, health indicators`.

**Parallelisation.** This phase creates 2 new files and adds 1 method to `QuoteControllerTest`. No overlap with Phase 7.1 or 7.2 files. Safe to run in parallel with Phases 7.1 and 7.2 (but should run before 7.4 since 7.4's Docker test depends on the full suite being green).

---

### Phase 7.4 — Resilience Edge Cases + Docker Smoke (~30 min)

**Goal.** Prove failure-mode mitigations work under hostile conditions; verify the Docker Compose stack starts healthy.

**Deliverables.**
- `BinanceWebSocketClientTest#reconnect_afterAbruptSocketClosure` — simulate code 1006 abnormal closure (no close frame sent); assert `reconnecting` CAS succeeds, backoff timer is scheduled, and no graceful-shutdown path is triggered.
- `BatchPersistenceServiceTest#producerNeverBlocks` — flood `enqueue()` from 8 threads simultaneously while drainer is paused; measure per-call `offer()` latency via `System.nanoTime()`; log p50/p99 and assert p99 < 50 ms (tighter thresholds like 10 ms are flaky under virtual-thread scheduler jitter; the point is to prove non-blocking behaviour, not fixed-latency behaviour).
- `BatchPersistenceServiceTest#shutdownTimeoutRespected` — block the drainer thread; enqueue items; call `@PreDestroy` with 200 ms timeout; assert partial drain occurred and method returned within 2× the timeout.
- `BatchPersistenceServiceTest#sustains500rps` — pump 500 quotes/sec for 5 seconds (2 500 total) into a queue draining at batch-size 50; assert all items eventually persisted.
- `QuoteRepositoryIntegrationTest#batchInsert_survivesRestart` — **DEFERRED**: insert 10 rows; stop PostgreSQL container; restart container; insert 10 more rows; assert 20 rows total via `SELECT COUNT(*)`. This test cannot be made reliable on macOS Docker Desktop due to port-forwarding recovery delays after in-place container restart. See [`docs/restart-test-analysis.md`](./restart-test-analysis.md) for root-cause analysis and approaches tried. Revisit when CI runs on Linux or when Docker Desktop fixes the port-forwarding race. FM-2 coverage is maintained by `BatchPersistenceServiceTest#retryOneByOneOnBatchFailure`.
- `DockerComposeSmokeTest` — use Testcontainers' `ComposeContainer` (v2 Compose API; `DockerComposeContainer` is deprecated in Testcontainers ≥1.19) to start `docker-compose.yml`; wait for app health check; assert `/actuator/health` returns `{"status":"UP"}`; assert `/api/quotes` returns 200. Guard with `@EnabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "true")` or `@DisabledInNativeImage` so CI without Docker can skip.

**Review before moving on.**
- **Gate command:** `mvn clean verify` passes.
- Every `FM-*` row in `requirement_traceability.md` NFR table maps to a named test method that exists and passes.
- Abrupt closure test distinguishes from graceful close (code 1006 vs 1000).
- DB restart test uses real Testcontainers stop/start — not a mock.
- Docker smoke test passes locally (manual: `docker compose up` → `curl /actuator/health`).
- No `@Disabled` / `@Ignore`d tests without an environment guard.

**Commit.** `test(7.4): resilience edge cases — abrupt closure, DB restart, Docker smoke`.

**Parallelisation.** This phase adds methods to 3 existing test files and creates 1 new file. It should run **after** Phases 7.1–7.3 so that the base test suite is green and the Docker smoke can validate the complete set.

---

### Phase 7.5 — Audit & Consistency Lock (~30 min)

**Goal.** Verify the codebase, test suite, and documentation are internally consistent before writing final prose. No new production code or tests are produced in this phase.

**Audit framework.** This phase executes the structured checklist in [`docs/audit_checklist.md`](./audit_checklist.md) (10 pillars, ~55 checks). Results are recorded in [`docs/audit_results.md`](./audit_results.md). Each check traces back to interviewer requirements, design decisions (DD-\*), and failure modes (FM-\*) per the V-model.

**Deliverables.**
- Completed `docs/audit_results.md` for pillars P1–P9 and P10a (internal docs).
- Green `mvn clean verify`.
- All checks in `audit_checklist.md` pillars P1–P9 pass.
- All checks in P10a (internal doc consistency) pass.
- Commit log reviewed: reads as a clean narrative, no `wip` / `fix fix` / orphan commits.
- Any drift found is corrected (test rename, stale doc reference, etc.) and committed as a single fixup.

**Pillar summary (P10b runs after Phase 8):**

| Pillar | Description | Phase |
|--------|-------------|-------|
| P1 | Requirement Completeness | 7.5 |
| P2 | Financial Domain Correctness | 7.5 |
| P3 | Data Integrity End-to-End | 7.5 |
| P4 | Java & Concurrency Correctness | 7.5 |
| P5 | Architecture Invariant Compliance | 7.5 |
| P6 | Spring Boot & Lifecycle | 7.5 |
| P7 | Security | 7.5 |
| P8 | Test Quality & Anti-Cheating | 7.5 |
| P9 | Operational Readiness | 7.5 |
| P10a | Internal Doc Consistency | 7.5 |
| P10b | External Doc Consistency (README/CLAUDE.md) | After Phase 8 |

**Review before moving on.**
- **Gate command:** `mvn clean verify` passes.
- All checks in pillars P1–P9 and P10a pass. If any fail, fix and re-run before proceeding to Phase 8.
- The system is now considered locked: Phase 8 (README, CLAUDE.md) describes this exact state.

**Commit.** `chore: audit lock — verify doc/test/code consistency` (only if drift was corrected; skip commit if everything was already consistent).

**Parallelisation.** Must be sequential — it audits the output of Phases 7.1–7.4.

---

### Phase 8 — README, CLAUDE.md, Polish (~1 h)

**Goal.** Anyone who clones the repo can build, run, and understand it.

**Deliverables.**
- `README.md` — overview; prerequisites; `mvn verify`, `docker compose up`, `mvn spring-boot:run -Dspring-boot.run.profiles=dev`; `curl` API examples; failure-mode summary linking to `docs/failure_modes.md`; proxy and Binance US override notes; troubleshooting (Apple Silicon / Ryuk).
- `CLAUDE.md` — project-local guidance for future Claude Code sessions (stack, conventions, forbidden shortcuts).
- Final `mvn clean verify` green.

**Review before moving on.**
- **Gate command:** `mvn clean verify` passes.
- A reader new to the repo can go from clone to working `curl /api/quotes` in 5 minutes.
- README links into `docs/` instead of duplicating.
- Commit log reads as a narrative; no `wip` / `fix fix` noise.

**Commit.** `docs: README and CLAUDE.md`.

---

## 4. Timeline

| Phase | Duration | Cumulative | Parallelisable? |
|-------|----------|-----------|----------------|
| 0. Bootstrap | 30 min | 0:30 | — |
| 1. Model & Schema | 30 min | 1:00 | — |
| 2. QuoteService | 30 min | 1:30 | — |
| 3. Persistence | 1:30 | 3:00 | — |
| 4. WebSocket Ingestion | 2:00 | 5:00 | — |
| 5. REST API | 30 min | 5:30 | — |
| 6. Docker & Ops | 1:00 | 6:30 | — |
| 7.1. Integration | 45 min | 7:15 | Yes — with 7.2, 7.3 |
| 7.2. Precision | 35 min | 7:50 | Yes — with 7.1, 7.3 |
| 7.3. Performance | 30 min | 8:20 | Yes — with 7.1, 7.2 |
| 7.4. Resilience + Docker | 30 min | 8:50 | After 7.1–7.3 |
| 7.5. Audit & Lock | 20–30 min | 9:20 | Sequential only |
| 8. README & CLAUDE.md | 1:00 | 10:20 | — |
| **Total (sequential)** | **~10.5 h** | (inside a 1-day window) |
| **Total (parallelised)** | **~9 h** | (7.1+7.2+7.3 concurrent) | |

Buffer: ~3 h for unexpected issues.

**Parallelisation strategy for Phase 7:**

```
7.1 ──┐
7.2 ──┤──→ merge + verify ──→ 7.4 ──→ 7.5 ──→ 8
7.3 ──┘
```

Phases 7.1, 7.2, and 7.3 touch completely disjoint files (no overlap in test classes or docs). They can be launched as 3 concurrent agent sessions. Merge order does not matter. Phase 7.4 modifies existing test files and should run after the merge to avoid conflicts. Phase 7.5 audits everything and must be last.

**Phase 7 evidence capture.** `docs/phase7_results.md` is created as a stub in the 7.1 commit and filled incrementally — each 7.* commit updates its own section. Capture evidence only where pass/fail hides signal: perf p50/p99 numbers (7.3), integration counts + reconnect counters (7.1), resilience before/after state (7.4). Pure unit-test pass/fail is covered by green `mvn verify` and does not need a results entry. 7.3 perf tests use a JUnit 5 `@AfterAll` hook that appends one markdown row to `target/phase7-metrics.md`; the reviewed block is copied into `docs/phase7_results.md` as part of the 7.3 commit. Audit check P10a.* in `audit_checklist.md` verifies all four phase sections are filled before 7.5 closes.

## 5. Risk Register

| Risk | Mitigation |
|------|------------|
| Binance blocked from dev machine | Global endpoint default; US override documented; OkHttp proxy support |
| Testcontainers on Apple Silicon | `postgres:16-alpine` is multi-arch; troubleshooting note in README |
| Batch INSERT tuning | Batch size and flush timeout are config properties, not compile-time constants |
| Latency test flakiness | SLO thresholds are logged first and enforced at an order of magnitude above target |
