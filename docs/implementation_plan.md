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
│   └── implementation_plan.md       (this file)
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
│           ├── config/AppPropertiesTest.java
│           ├── websocket/{QuoteMessageParserTest, BinanceWebSocketClientTest}.java
│           ├── service/{QuoteServiceTest, BatchPersistenceServiceTest, QuoteServicePerformanceTest}.java
│           ├── repository/QuoteRepositoryIntegrationTest.java  (Testcontainers)
│           ├── controller/QuoteControllerTest.java
│           ├── IngestLagTest.java
│           └── ApplicationIntegrationTest.java                 (@SpringBootTest + MockWebServer)
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
- `schema.sql` — per `architecture.md` §6 (includes `transaction_time` column).
- Unit: `QuoteTest`, `AppPropertiesTest`.

**Review before moving on.**
- All monetary fields are `BigDecimal`, `Quote` is immutable.
- Schema has `UNIQUE(symbol, update_id)`, `transaction_time` column, and the composite index.
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
- `QuoteMessageParser.java` — Jackson configured for `BigDecimal`; unwraps `{"stream":"...","data":{...}}`; parses `E` → `eventTime`, `T` → `transactionTime`; returns `Optional<Quote>`; silently skips subscription acks.
- `BinanceWebSocketClient.java` — OkHttp `WebSocketListener`; combined-stream URL (`/stream?streams=...`) built from `AppProperties.symbols`; exponential backoff 1 → 2 → 4 → … → 60 s; atomic `AtomicBoolean reconnecting` guard; `volatile boolean shuttingDown` guard checked before every reconnect schedule; resets backoff **after first message**, not socket-open; on message → `QuoteService.update` + `BatchPersistenceService.enqueue`.
- **WebSocket graceful shutdown** — `@PreDestroy` sets `shuttingDown = true`, cancels pending reconnect, calls `webSocket.close(1000, "application shutdown")`, awaits `onClosed` with a bounded timeout (default 3 s). This must run **before** `BatchPersistenceService.@PreDestroy` so the drainer sees a closed input before flushing.
- `BinanceStreamHealthIndicator.java`.
- **Lag metric (per DD-11)** — two Micrometer gauges backed by one `ConcurrentHashMap<String, AtomicLong> lagBySymbol`:
  - Per-symbol: `Gauge.builder("binance.quote.lag.millis", lagBySymbol, m -> m.get(symbol).get()).tag("symbol", symbol).register(registry)` — one per configured symbol, registered at client construction.
  - Fleet-max: `Gauge.builder("binance.quote.lag.max.millis", lagBySymbol, m -> m.values().stream().mapToLong(AtomicLong::get).max().orElse(0L)).register(registry)` — single untagged gauge.
  - Hot path: `lagBySymbol.get(symbol).set(System.currentTimeMillis() - quote.eventTime())`. Zero allocation.
- Unit: `QuoteMessageParserTest` (includes `E`/`T` parsing), `BinanceWebSocketClientTest`.

**Review before moving on.**
- Built URL uses `fstream.binance.com` and is logged once on startup.
- Storm of `onClosed` + `onFailure` spawns exactly one reconnect (unit test with atomic counter).
- `@PreDestroy` path: `shuttingDown` flag stops reconnect scheduling; close frame is sent; `onClosed` callback completes; drainer only starts its own `@PreDestroy` afterwards.
- Backoff resets only on first inbound message post-open.
- Parser handles the `/stream` wrapper, populates both `eventTime` and `transactionTime`.
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
- Fresh-clone `docker compose up` lands `UP` within 60 s for all services.
- `/actuator/health` shows `UP` including `binanceStream` and `persistenceQueue`.

**Commit.** `chore(ops): docker compose with postgres healthcheck`.

---

### Phase 7 — Comprehensive Tests (~1.5 h)

**Goal.** Prove every SLO and every FM is covered.

**Deliverables.**
- `ApplicationIntegrationTest` — `@SpringBootTest` with Testcontainers PG + OkHttp `MockWebServer` simulating Binance; verifies frame → map → REST → DB end-to-end and the reconnect scenario.
- `QuoteServicePerformanceTest` — 10 k iterations, logs p50/p99; fails only if p99 > 1 ms.
- `IngestLagTest` — mock WS at 500 msg/s; asserts `binance.quote.lag.millis` p99 < 5 ms.
- FM-specific tests per `requirement_traceability.md` NFR section.

**Review before moving on.**
- Every SLO row has a test asserting or logging it.
- Every `FM-*` row maps to a named test method that exists.
- No `@Disabled` / `@Ignore`d tests.
- Test run is hermetic (no real Binance, no external DB).

**Commit.** `test: end-to-end integration + resilience + perf sanity`.

---

### Phase 8 — README, CLAUDE.md, Polish (~1 h)

**Goal.** Anyone who clones the repo can build, run, and understand it.

**Deliverables.**
- `README.md` — overview; prerequisites; `mvn verify`, `docker compose up`, `mvn spring-boot:run -Dspring-boot.run.profiles=dev`; `curl` API examples; failure-mode summary linking to `docs/failure_modes.md`; proxy and Binance US override notes; troubleshooting (Apple Silicon / Ryuk).
- `CLAUDE.md` — project-local guidance for future Claude Code sessions (stack, conventions, forbidden shortcuts).
- Final `mvn clean verify` green.

**Review before moving on.**
- A reader new to the repo can go from clone to working `curl /api/quotes` in 5 minutes.
- README links into `docs/` instead of duplicating.
- Commit log reads as a narrative; no `wip` / `fix fix` noise.

**Commit.** `docs: README and CLAUDE.md`.

---

## 4. Timeline

| Phase | Duration | Cumulative |
|-------|----------|-----------|
| 0. Bootstrap | 30 min | 0:30 |
| 1. Model & Schema | 30 min | 1:00 |
| 2. QuoteService | 30 min | 1:30 |
| 3. Persistence | 1:30 | 3:00 |
| 4. WebSocket Ingestion | 2:00 | 5:00 |
| 5. REST API | 30 min | 5:30 |
| 6. Docker & Ops | 1:00 | 6:30 |
| 7. Tests | 1:30 | 8:00 |
| 8. README & CLAUDE.md | 1:00 | 9:00 |
| **Total** | **~9 h** | (inside a 1-day window) |

Buffer: ~3 h for unexpected issues.

## 5. Risk Register

| Risk | Mitigation |
|------|------------|
| Binance blocked from dev machine | Global endpoint default; US override documented; OkHttp proxy support |
| Testcontainers on Apple Silicon | `postgres:16-alpine` is multi-arch; troubleshooting note in README |
| Batch INSERT tuning | Batch size and flush timeout are config properties, not compile-time constants |
| Latency test flakiness | SLO thresholds are logged first and enforced at an order of magnitude above target |
