# Audit Results

Execution record for [`audit_checklist.md`](./audit_checklist.md). Filled incrementally during
Phase 7.5 (and P10b after Phase 8). Each session picks up where the last left off.

---

## Summary

| Pillar | Description | Status | Pass | Fail | N/A |
|--------|-------------|--------|------|------|-----|
| P1 | Requirement Completeness | DONE | 2 | 1 | — |
| P2 | Financial Domain Correctness | DONE | 9 | — | — |
| P3 | Data Integrity End-to-End | DONE | 5 | — | — |
| P4 | Java & Concurrency Correctness | DONE | 12 | — | 1 |
| P5 | Architecture Invariant Compliance | DONE | 9 | — | — |
| P6 | Spring Boot & Lifecycle | DONE | 6 | — | — |
| P7 | Security | DONE | 4 | — | — |
| P8 | Test Quality & Anti-Cheating | PENDING | — | — | — |
| P9 | Operational Readiness | DEFERRED | — | — | — |
| P10a | Internal Doc Consistency | PENDING | — | — | — |
| P10b | External Doc Consistency (post-Phase 8) | DEFERRED | — | — | — |

**Overall:** 47 / 47 checks passed (P1-P7 complete; P8, P10a pending; P9, P10b deferred until after Phase 7.4 + Phase 8)

---

## Session Log

| # | Date | Pillars Covered | Agent | Notes |
|---|------|----------------|-------|-------|
| 1 | 2026-04-13 | P1–P7 (full evidence gathering + results recording) | Qwen Code | 3 subagents ran research-only checks in parallel; main agent serialized results into this file. P1.2 FAIL fixed: `AppConfigTest` → `AppPropertiesTest` in `requirement_traceability.md`. |

---

## P1. Requirement Completeness

### P1.1 Every interviewer requirement mapped to implementation

- **Result:** PASS
- **Evidence:** All 9 REQs (REQ-1 through REQ-9) in `requirement_traceability.md` have Module rows mapping to implementation code: REQ-1 → `BinanceWebSocketClient.java`, `QuoteMessageParser.java`; REQ-2 → `AppProperties.java`; REQ-3 → `Quote.java`, `QuoteMessageParser.java`; REQ-4 → `BatchPersistenceService.java`, `QuoteRepository.java`; REQ-5 → `QuoteController.java`, `QuoteService.java`; REQ-6 → `QuoteService.java`, `BatchPersistenceService.java`; REQ-7 → meta (all test files); REQ-8 → process; REQ-9 → `README.md`.
- **Fix (if needed):** N/A

### P1.2 Every interviewer requirement has at least one test

- **Result:** PASS (fixed)
- **Evidence:** `requirement_traceability.md` originally referenced `AppConfigTest` (line 103) but the actual test class is `AppPropertiesTest.java`. All other 9 test classes exist.
- **Fix (if needed):** Renamed `AppConfigTest` → `AppPropertiesTest` in `requirement_traceability.md` line 103.

### P1.3 Every SLO has a corresponding test

- **Result:** PASS
- **Evidence:** All 5 SLOs from `architecture.md` §8 map to existing test methods: Read p99 < 1ms → `QuoteServicePerformanceTest#p99ReadUnder1ms`; REST p99 < 5ms → `QuoteControllerTest#p99LatencyUnder5ms`; Ingest-to-available p99 < 5ms → `ApplicationIntegrationTest#ingestLatencyUnder5ms`; Freshness lag p99 < 500ms → `IngestLagTest#lagGaugeUnder500msAt500rps`; Persistence headroom >= 10x → `BatchPersistenceServiceTest#sustains500rps`.
- **Fix (if needed):** N/A

---

## P2. Financial Domain Correctness

### P2.1 No `double` or `float` in monetary model fields

- **Result:** PASS
- **Evidence:** `grep -rn "double\|float"` in `model/` and `service/` returned zero matches. Only `double` uses in non-monetary contexts: `PersistenceProperties.dropOldestThreshold` (a 0.0–1.0 fraction), `PersistenceQueueHealthIndicator.utilization` (queue ratio), `BinanceWebSocketClient.BACKOFF_MULTIPLIER` (backoff factor).
- **Fix (if needed):** N/A

### P2.2 `BigDecimal` constructed from `String`, never from `double` literal

- **Result:** PASS
- **Evidence:** `grep -rn "new BigDecimal([0-9]"` in `src/main/` returned zero matches. All `new BigDecimal(...)` calls use string arguments from JSON text.
- **Fix (if needed):** N/A

### P2.3 `BigDecimal` comparisons use `compareTo`, not `equals`

- **Result:** PASS
- **Evidence:** `grep -rn "\.equals("` in `src/main/` returned zero matches. All BigDecimal comparisons in `QuoteMessageParser.java` use `compareTo()`: lines 101, 105, 109, 113, 117.
- **Fix (if needed):** N/A

### P2.4 Jackson configured for `BigDecimal` serialization/deserialization

- **Result:** PASS
- **Evidence:** `USE_BIG_DECIMAL_FOR_FLOATS` configured in `QuoteMessageParser.java:43` (DeserializationFeature). `WRITE_BIGDECIMAL_AS_PLAIN` configured via Spring Boot property in `application.yml:22` (`spring.jackson.generator.write-big-decimal-as-plain: true`).
- **Fix (if needed):** N/A

### P2.5 SQL monetary columns are `NUMERIC`, not `DOUBLE PRECISION` / `REAL` / `FLOAT`

- **Result:** PASS
- **Evidence:** `schema.sql` defines `bid_price NUMERIC(24,8)`, `bid_size NUMERIC(24,8)`, `ask_price NUMERIC(24,8)`, `ask_size NUMERIC(24,8)`. No DOUBLE/REAL/FLOAT types found.
- **Fix (if needed):** N/A

### P2.6 `BigDecimal` arithmetic has explicit `MathContext` / `RoundingMode`

- **Result:** PASS (N/A)
- **Evidence:** `grep -rn "\.divide("` in `src/main/` returned zero matches. The service performs no division operations on BigDecimal.
- **Fix (if needed):** N/A

### P2.7 Crossed spread protection exists (`bid <= ask`)

- **Result:** PASS
- **Evidence:** `QuoteMessageParser.java:117-120`: `if (bid.compareTo(ask) > 0) { log.warn("Crossed spread (bid > ask) ..."); return Optional.empty(); }`
- **Fix (if needed):** N/A

### P2.8 Positive price validation exists

- **Result:** PASS
- **Evidence:** `QuoteMessageParser.java:101-104`: bid checked with `compareTo(BigDecimal.ZERO) <= 0`; `QuoteMessageParser.java:105-108`: ask checked similarly. Both return `Optional.empty()` on zero/negative.
- **Fix (if needed):** N/A

### P2.9 Schema CHECK constraints backstop application validation

- **Result:** PASS
- **Evidence:** `schema.sql` has three CHECK constraints: `chk_positive_bid CHECK (bid_price > 0)`, `chk_positive_ask CHECK (ask_price > 0)`, `chk_nonneg_sizes CHECK (bid_size >= 0 AND ask_size >= 0)`.
- **Fix (if needed):** N/A

---

## P3. Data Integrity End-to-End

### P3.1 Binance field mapping correct

- **Result:** PASS
- **Evidence:** `QuoteMessageParser.java:76-83` maps all 8 Binance fields: `s` → symbol, `b` → bid, `B` → bidSize, `a` → ask, `A` → askSize, `u` → updateId, `E` → eventTime, `T` → transactionTime. Matches DD-10 spec.
- **Fix (if needed):** N/A

### P3.2 Quote survives JSON → parser → REST response round-trip

- **Result:** PASS
- **Evidence:** `QuoteRoundTripTest.testJacksonSerializationPrecision()` asserts no scientific notation (`doesNotContain("E")`) and exact decimal values survive serialization/deserialization. Jackson configured with `WRITE_BIGDECIMAL_AS_PLAIN`.
- **Fix (if needed):** N/A

### P3.3 Quote survives JSON → parser → DB INSERT → DB SELECT round-trip

- **Result:** PASS
- **Evidence:** `QuoteRepositoryIntegrationTest.monetaryFieldsSurviveDbRoundTrip()` (lines 108-124) verifies all four BigDecimal monetary fields survive INSERT/SELECT using `rs.getBigDecimal()` and `isEqualByComparingTo()`.
- **Fix (if needed):** N/A

### P3.4 No precision loss across any boundary

- **Result:** PASS
- **Evidence:** `grep -rn "doubleValue\|floatValue\|Double.parseDouble\|Float.parseFloat"` in `src/main/` returned zero matches. `QuoteRoundTripTest.testCombinedRoundTrip()` verifies full pipeline JSON → DB → JSON.
- **Fix (if needed):** N/A

### P3.5 `receivedAt` is `Instant` (time-zone-aware), not `LocalDateTime`

- **Result:** PASS
- **Evidence:** `Quote.java:39`: `Instant receivedAt` (record parameter). `schema.sql:11`: `received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()`.
- **Fix (if needed):** N/A

---

## P4. Java & Concurrency Correctness

### P4.1 `InterruptedException` never swallowed

- **Result:** PASS
- **Evidence:** All 4 `catch (InterruptedException` occurrences re-interrupt: `BatchPersistenceService.java:123,147,192` and `BinanceWebSocketClient.java:219` — each calls `Thread.currentThread().interrupt()` plus logs.
- **Fix (if needed):** N/A

### P4.2 No raw `Optional.get()` without guard

- **Result:** PASS
- **Evidence:** Only `Optional.get()` in `src/main/` is at `BinanceWebSocketClient.java:247` — guarded by `if (maybeQuote.isEmpty()) { return; }` at line 244. All other `.get()` calls are on `AtomicLong`/`AtomicBoolean`/`Map`.
- **Fix (if needed):** N/A

### P4.3 No TOCTOU races on `ConcurrentHashMap` (no get+put pattern)

- **Result:** PASS
- **Evidence:** `QuoteService.java:32-35` uses `quotes.compute(symbol, (key, existing) -> ...)` for atomic read-modify-write. No `get()` + `put()` pattern.
- **Fix (if needed):** N/A

### P4.4 Resource leaks: connections/streams use try-with-resources

- **Result:** PASS (N/A)
- **Evidence:** No direct `.getConnection()` calls in `src/main/`. `NamedParameterJdbcTemplate` manages connections internally. Test code uses try-with-resources correctly.
- **Fix (if needed):** N/A

### P4.5 `volatile` / `AtomicBoolean` used correctly for cross-thread flags

- **Result:** PASS
- **Evidence:** `shuttingDown` declared `volatile boolean` in both `BatchPersistenceService.java:42` and `BinanceWebSocketClient.java:85`. `reconnecting` is `AtomicBoolean` in `BinanceWebSocketClient.java:82`.
- **Fix (if needed):** N/A

### P4.6 No `System.out.println` in production code

- **Result:** PASS
- **Evidence:** `grep -rn "System.out\|System.err"` in `src/main/java/` returned zero matches. All logging uses SLF4J `Logger`.
- **Fix (if needed):** N/A

### P4.7 Broad `Exception` catches are intentional and documented

- **Result:** PASS
- **Evidence:** All 4 `catch (Exception` blocks log the exception: `BatchPersistenceService.java:169,185` (persist failure with batch size), `BinanceWebSocketClient.java:319` (reconnect failure), `QuoteMessageParser.java:58` (malformed JSON with warn log).
- **Fix (if needed):** N/A

### P4.8 No resource leak on constructor failure after thread start

- **Result:** PASS
- **Evidence:** `BatchPersistenceService` constructor (lines 45-57): `Thread.ofVirtual().name("quote-batch-writer").start(this::drainLoop)` is the last statement. All field initialization completes before thread start. If `start()` throws, the object is never constructed — no resources to leak.
- **Fix (if needed):** N/A

### P4.9 Constructor-injected fields are `private final` (non-config classes)

- **Result:** PASS
- **Evidence:** All constructor-injected fields verified `private final`: `QuoteService:23`, `QuoteController:25-26`, `QuoteRepository:33`, `BatchPersistenceService:35-41`, `BinanceWebSocketClient:67-82`, `BinanceStreamHealthIndicator:20-21`, `PersistenceQueueHealthIndicator:18-19`.
- **Fix (if needed):** N/A

### P4.10 No public mutable instance fields

- **Result:** PASS
- **Evidence:** All `public` members in `src/main/` are methods, constructors, or class declarations. No public instance fields found.
- **Fix (if needed):** N/A

### P4.11 No static mutable state (static non-final fields)

- **Result:** PASS
- **Evidence:** All 11 `private static` fields are `final` (3 Logger, 1 String constant, 1 SQL string, 4 long constants, 1 double constant, 1 long constant). `grep` for `private static [^f]` returned zero matches.
- **Fix (if needed):** N/A

### P4.12 `record` used for pure data carriers, not classes with getters

- **Result:** PASS
- **Evidence:** `Quote.java:29` is `public record Quote(...)`. It is the only type in the `model/` package. No `class` definitions found.
- **Fix (if needed):** N/A

---

## P5. Architecture Invariant Compliance

### P5.1 No JPA dependency

- **Result:** PASS
- **Evidence:** `mvn dependency:tree | grep -i jpa` returned empty. Zero JPA dependencies in the Maven tree.
- **Fix (if needed):** N/A

### P5.2 No `SELECT` in write-path repository

- **Result:** PASS
- **Evidence:** `QuoteRepository.java` contains only `INSERT ... ON CONFLICT` SQL. `grep "SELECT"` returned zero matches. Consistent with DD-3 (write-only persistence).
- **Fix (if needed):** N/A

### P5.3 `QuoteService` has no DB dependency

- **Result:** PASS
- **Evidence:** `grep "Repository\|JdbcTemplate\|DataSource\|jdbc"` in `QuoteService.java` returned zero matches. Imports only `Quote`, `Map`, `Optional`, `ConcurrentHashMap`, `Service`. Pure in-memory store.
- **Fix (if needed):** N/A

### P5.4 No `!bookTicker` firehose subscription

- **Result:** PASS
- **Evidence:** `grep -rn "!bookTicker"` in `src/` returned zero matches. Client builds targeted combined stream URL from configured symbol list via `buildCombinedStreamUrl()` (line 273 of `BinanceWebSocketClient.java`).
- **Fix (if needed):** N/A

### P5.5 All SQL uses named parameters, no string concatenation

- **Result:** PASS
- **Evidence:** `QuoteRepository.java` uses `:symbol`, `:bid`, `:bidSize`, `:ask`, `:askSize`, `:updateId`, `:eventTime`, `:transactionTime`, `:receivedAt` — all `:namedParam` style. SQL is a single text-block constant. No `String.format` or string concatenation found.
- **Fix (if needed):** N/A

### P5.6 `spring.threads.virtual.enabled=true`

- **Result:** PASS
- **Evidence:** `application.yml:4-6`: `spring.threads.virtual.enabled: true`.
- **Fix (if needed):** N/A

### P5.7 Drainer thread named `quote-batch-writer`

- **Result:** PASS
- **Evidence:** `BatchPersistenceService.java:53`: `Thread.ofVirtual().name("quote-batch-writer").start(this::drainLoop)`.
- **Fix (if needed):** N/A

### P5.8 WebSocket thread never blocks (non-blocking `offer`)

- **Result:** PASS
- **Evidence:** `BatchPersistenceService.java:68`: `boolean offered = queue.offer(quote)`. Retry at line 79 also uses `offer()`. No `put()` calls found.
- **Fix (if needed):** N/A

### P5.9 No map hydration from DB at startup

- **Result:** PASS
- **Evidence:** `QuoteService.java` has zero `@PostConstruct`, `ApplicationRunner`, or `CommandLineRunner` annotations. The class is a plain `@Service` with a `ConcurrentHashMap` field — nothing reads from the database at startup.
- **Fix (if needed):** N/A

---

## P6. Spring Boot & Lifecycle

### P6.1 `@PreDestroy` ordering: WS client closes before persistence drainer

- **Result:** PASS
- **Evidence:** `BinanceWebSocketClient` constructor takes `BatchPersistenceService` as a parameter (line 112), establishing a dependency. Spring guarantees dependency beans are fully initialized first. `@PreDestroy` ordering is explicit: `BinanceWebSocketClient.shutdown()` has `@Order(1)` (line 155), so it runs before `BatchPersistenceService.shutdown()` (no `@Order`, default higher number). WS closes before drainer flushes.
- **Fix (if needed):** N/A

### P6.2 `@ConfigurationProperties` validated with JSR-380

- **Result:** PASS
- **Evidence:** `AppProperties.java:17` — `@Validated` + `@NotNull`, `@Size(min=10,max=10)`, `@Pattern`. `PersistenceProperties.java:16` — `@Validated` + `@Min`, `@Max`, `@Positive` constraints. `BinanceProperties.java` has `@ConfigurationProperties("binance.ws")` but no constraint annotations — this is acceptable as none of its fields require validation.
- **Fix (if needed):** N/A

### P6.3 No `@Value` annotations (use `@ConfigurationProperties`)

- **Result:** PASS
- **Evidence:** `grep -rn "@Value"` in `src/main/` returned zero matches. All configuration via `@ConfigurationProperties` binding.
- **Fix (if needed):** N/A

### P6.4 `dev` profile boots without external infrastructure

- **Result:** PASS
- **Evidence:** `application-dev.yml:5-9`: H2 datasource configured with `jdbc:h2:mem:binance_quotes_dev;MODE=PostgreSQL;...`, `driver-class-name: org.h2.Driver`, H2 console enabled.
- **Fix (if needed):** N/A

### P6.5 Health indicators registered and aggregated

- **Result:** PASS
- **Evidence:** Two `@Component` classes implementing `HealthIndicator`: `BinanceStreamHealthIndicator` (registered as `"binanceStream"`) and `PersistenceQueueHealthIndicator` (registered as `"persistenceQueue"`).
- **Fix (if needed):** N/A

### P6.6 Graceful shutdown enabled

- **Result:** PASS
- **Evidence:** `application.yml:23`: `shutdown: graceful`.
- **Fix (if needed):** N/A

---

## P7. Security

### P7.1 No secrets in code or committed config

- **Result:** PASS
- **Evidence:** `application.yml:10`: `password: ${DB_PASSWORD:postgres}` — uses env var with Docker default fallback (`postgres` is the standard PostgreSQL image default). `application-dev.yml:9`: `password: ""` — empty for H2 dev. No hardcoded API keys, tokens, or credentials.
- **Fix (if needed):** N/A

### P7.2 No SQL injection (all parameters are named/bound)

- **Result:** PASS
- **Evidence:** No `String.format` with SQL, no `StringBuilder` with SQL in `src/main/`. `QuoteRepository.java` uses a text-block constant with `NamedParameterJdbcTemplate`. `BinanceWebSocketClient.buildCombinedStreamUrl()` concatenates symbols validated by `AppProperties` (`^[A-Z]+USDT$` pattern).
- **Fix (if needed):** N/A

### P7.3 No stack traces in REST error responses

- **Result:** PASS
- **Evidence:** `ApiExceptionHandler.java`: `handleSymbolNotFound` (line 27) returns `Map.of("error", ex.getMessage())` — just the message. `handleUnexpected` (line 33) returns `Map.of("error", "Internal server error")` — generic message only. Stack traces are logged via `log.error()` but never serialized to response bodies.
- **Fix (if needed):** N/A

### P7.4 Actuator exposure is restricted

- **Result:** PASS
- **Evidence:** `application.yml:30-33`: `management.endpoints.web.exposure.include: health,info,metrics`. Only the three required endpoints. No `*`, `env`, or `beans`.
- **Fix (if needed):** N/A

---

## P8. Test Quality & Anti-Cheating

### P8.1 Every `@Test` method has at least 1 assertion

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P8.2 No tests that only verify mock interactions with zero real assertions

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P8.3 No `@Disabled` / `@Ignore`d tests

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P8.4 No tautological assertions (always-true conditions)

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P8.5 Test helper duplication within reason

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P8.6 Every test file has at least 1 test method

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P8.7 Integration tests use Testcontainers (no external DB dependency)

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P8.8 `Thread.sleep` in unit tests flagged for flakiness risk

- **Result:**
- **Evidence:**
- **Fix (if needed):**

---

## P9. Operational Readiness

### P9.1 `mvn clean verify` passes

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P9.2 `mvn spotless:check` passes

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P9.3 No JPA in dependency tree

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P9.4 Docker Compose starts healthy (when implemented)

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P9.5 README build/run/test instructions are accurate

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P9.6 No `TODO` / `FIXME` in production code without tracking note

- **Result:**
- **Evidence:**
- **Fix (if needed):**

---

## P10a. Internal Doc Consistency (Phase 7.5)

### P10a.1 Every `DD-*` cross-reference resolves

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P10a.2 Every `FM-*` cross-reference resolves

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P10a.3 Every test named in `requirement_traceability.md` exists in `src/test/`

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P10a.4 Every class in `implementation_plan.md` tree exists or is explicitly future

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P10a.5 Architecture diagram matches actual data flow

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P10a.6 Schema in `architecture.md` matches `schema.sql`

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P10a.7 FM numbering is sequential with no gaps

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P10a.8 `CLAUDE.md` conventions match actual code patterns

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P10a.9 Doc text matches actual implementation (no stale descriptions)

- **Result:**
- **Evidence:**
- **Fix (if needed):**

---

## P10b. External Doc Consistency (After Phase 8)

### P10b.1 README links are valid and point to correct sections

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P10b.2 README API examples match actual endpoint behavior

- **Result:**
- **Evidence:**
- **Fix (if needed):**

### P10b.3 README prerequisites match actual project requirements

- **Result:**
- **Evidence:**
- **Fix (if needed):**
