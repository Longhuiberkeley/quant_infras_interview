# Pre-Submission Audit Checklist

This checklist is executed during **Phase 7.5** (Audit & Consistency Lock). Every item is
re-verified from scratch regardless of prior findings. Results are recorded in
[`audit_results.md`](./audit_results.md).

## Legend

| Prefix | Source Document |
|--------|----------------|
| REQ-n | [`interviewer_requirements.md`](./interviewer_requirements.md) |
| DD-n | [`design_decisions.md`](./design_decisions.md) |
| FM-n | [`failure_modes.md`](./failure_modes.md) |
| SLO-n | [`architecture.md`](./architecture.md) §8 |
| IP-n | [`implementation_plan.md`](./implementation_plan.md) §3 phases |

## Traceability Model (V-Model)

Each check traces through the full chain:

```
Interviewer REQ → Design Decision (DD) → Code Module → Test → Audit Check
                         ↓
                  Failure Mode (FM) → Mitigation → Test → Audit Check
```

---

## P1. Requirement Completeness

> Verifies that every interviewer requirement has a traceable implementation and test.

### P1.1 Every interviewer requirement mapped to implementation

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-1 through REQ-9 |

- **Check:** For each row in `interviewer_requirements.md` §2–4, identify the implementing module(s) listed in `requirement_traceability.md`.
- **Command:** `rg "^### REQ-" docs/requirement_traceability.md` → verify each REQ entry has a **Module** row.
- **Pass:** Every REQ has at least one module and one test listed.

### P1.2 Every interviewer requirement has at least one test

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-7 ("Adequate test coverage is required") |

- **Check:** For each REQ in `requirement_traceability.md`, the **Test** row names at least one test class.
- **Command:** Extract test class names from `requirement_traceability.md`, then `ls src/test/java/**/ClassName.java` for each.
- **Pass:** Every REQ maps to a test file that exists.

### P1.3 Every SLO has a corresponding test

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-6 ("Performance is a top priority") |
| **SLO** | `architecture.md` §8 — five SLO rows |

- **Check:** Each SLO row in `architecture.md` §8 should map to a named test in `requirement_traceability.md`.
- **Command:** `rg "SLO" docs/requirement_traceability.md` → extract test names → verify existence.
- **Pass:** All five SLOs map to existing test methods.

---

## P2. Financial Domain Correctness

> Catches incorrect numeric types, precision loss, and missing validations in the financial data path.

### P2.1 No `double` or `float` in monetary model fields

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-3 (bid, bid_size, ask, ask_size) |
| **DD** | DD-2 (`BigDecimal` in Java, `NUMERIC(24,8)` in PostgreSQL) |

- **Check:** No `double` or `float` types used for monetary fields in the `Quote` record or any service that handles them.
- **Command:** `rg "double\b|float\b" src/main/java/com/quant/binancequotes/model/ src/main/java/com/quant/binancequotes/service/`
- **Pass:** Zero matches.

### P2.2 `BigDecimal` constructed from `String`, never from `double` literal

| Trace | Reference |
|-------|-----------|
| **DD** | DD-2 |

- **Check:** No `new BigDecimal(0.xxx)` with a floating-point literal; always `new BigDecimal("0.xxx")` or `BigDecimal.valueOf()`.
- **Command (ast-grep):** `ast-grep --pattern 'new BigDecimal($$$FLOAT)' --language java src/main/` where FLOAT is a floating-point literal (not a string).
- **Manual fallback:** `rg "new BigDecimal\([0-9]+\." src/main/` — flag any match.
- **Pass:** Zero matches in production code. Test fixtures using `BigDecimal.valueOf()` are acceptable.

### P2.3 `BigDecimal` comparisons use `compareTo`, not `equals`

| Trace | Reference |
|-------|-----------|
| **DD** | DD-2 — "Comparisons must use `compareTo`, not `equals` (which distinguishes `1.0` from `1.00`)" |

- **Check:** Anywhere `BigDecimal` values are compared for ordering or equality, `compareTo` is used.
- **Command:** `rg "\.equals\(" src/main/java/ | grep -i "bigdecimal\|bid\|ask\|price\|size"` — flag可疑 matches.
- **Command (ast-grep):** `ast-grep --pattern '$BD.equals($$$)' --language java src/main/` where BD is a BigDecimal-typed variable.
- **Pass:** No monetary `BigDecimal` field compared via `.equals()`.

### P2.4 Jackson configured for `BigDecimal` serialization/deserialization

| Trace | Reference |
|-------|-----------|
| **DD** | DD-2 |

- **Check:** Jackson `ObjectMapper` has `USE_BIG_DECIMAL_FOR_FLOATS` enabled (deserialization) and `WRITE_BIG_DECIMAL_AS_PLAIN` enabled (serialization).
- **Command:** `rg "USE_BIG_DECIMAL_FOR_FLOATS|WRITE_BIG_DECIMAL_AS_PLAIN" src/main/`
- **Pass:** Both features are configured.

### P2.5 SQL monetary columns are `NUMERIC`, not `DOUBLE PRECISION` / `REAL` / `FLOAT`

| Trace | Reference |
|-------|-----------|
| **DD** | DD-2 |

- **Check:** `schema.sql` uses `NUMERIC` for all price and size columns.
- **Command:** `rg "DOUBLE|REAL|FLOAT" src/main/resources/schema.sql`
- **Pass:** Zero matches.

### P2.6 `BigDecimal` arithmetic has explicit `MathContext` / `RoundingMode`

| Trace | Reference |
|-------|-----------|
| **DD** | DD-2 |

- **Check:** Any `BigDecimal.divide()` or `BigDecimal.multiply()` call that could produce non-terminating decimals has an explicit rounding mode.
- **Command:** `rg "\.divide\(" src/main/` → verify each call has 2+ arguments (divisor + rounding mode).
- **Pass:** All `divide` calls specify rounding mode, OR no `divide` calls exist in production code.

### P2.7 Crossed spread protection exists (`bid <= ask`)

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-3 |
| **DD** | DD-13 |
| **FM** | FM-12 |
| **Code** | `QuoteMessageParser.java` |
| **Test** | `QuoteMessageParserTest#crossedSpread_returnsEmpty` |

- **Check:** Parser rejects quotes where bid > ask.
- **Command:** `rg "bid.*ask|ask.*bid" src/main/java/**/parser/` → find validation logic.
- **Pass:** Explicit check exists that rejects `bid > ask`.

### P2.8 Positive price validation exists

| Trace | Reference |
|-------|-----------|
| **DD** | DD-13 |
| **FM** | FM-12 |

- **Check:** Parser rejects quotes with zero or negative bid/ask prices.
- **Command:** `rg "> 0|signum|compareTo.*ZERO" src/main/java/**/parser/`
- **Pass:** Explicit check exists for `bid > 0` and `ask > 0`.

### P2.9 Schema CHECK constraints backstop application validation

| Trace | Reference |
|-------|-----------|
| **DD** | DD-13 |
| **FM** | FM-12 |

- **Check:** `schema.sql` has CHECK constraints for positive prices and non-negative sizes.
- **Command:** `rg "CHECK" src/main/resources/schema.sql`
- **Pass:** At least three CHECK constraints: `bid_price > 0`, `ask_price > 0`, `bid_size >= 0 AND ask_size >= 0`.

---

## P3. Data Integrity End-to-End

> Verifies the complete data pipeline from Binance frame to REST response and DB storage.

### P3.1 Binance field mapping correct

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-3 |
| **DD** | DD-10 |

- **Check:** Parser maps Binance fields correctly: `b` → bid, `B` → bidSize, `a` → ask, `A` → askSize, `E` → eventTime, `T` → transactionTime, `u` → updateId, `s` → symbol.
- **Command:** Read `QuoteMessageParser.java` and verify each field mapping against Binance USDT-M Perpetuals `@bookTicker` spec.
- **Binance spec reference:** `{e, u, E, T, s, b, B, a, A}` as documented in DD-10.
- **Pass:** Every Binance field maps to the correct `Quote` record component.

### P3.2 Quote survives JSON → parser → REST response round-trip

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-3, REQ-5 |
| **DD** | DD-2, DD-12 |

- **Check:** A parsed Quote, when serialized to JSON via Jackson (with `WRITE_BIG_DECIMAL_AS_PLAIN`), produces the expected field names and plain-decimal values.
- **Test:** `QuoteRoundTripTest` (if exists) or `QuoteControllerTest`.
- **Pass:** JSON output contains `bid`, `bidSize`, `ask`, `askSize` as plain decimals (no scientific notation).

### P3.3 Quote survives JSON → parser → DB INSERT → DB SELECT round-trip

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-4 |
| **DD** | DD-2 |
| **FM** | FM-5 |
| **Test** | `QuoteRepositoryIntegrationTest#monetaryFieldsSurviveDbRoundTrip` |

- **Check:** `BigDecimal` values survive a full DB round-trip without precision loss.
- **Command:** `rg "SurviveDbRoundTrip|survive.*round" src/test/`
- **Pass:** Test exists and asserts exact `BigDecimal` values after DB round-trip.

### P3.4 No precision loss across any boundary

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-3 |
| **DD** | DD-2 |

- **Check:** No stage in the pipeline (JSON → Java → SQL → Java → JSON) converts `BigDecimal` to `double` or back.
- **Command:** `rg "\.doubleValue\(\)|\.floatValue\(\)|Double\.parseDouble|Float\.parseFloat" src/main/`
- **Pass:** Zero matches in production code.

### P3.5 `receivedAt` is `Instant` (time-zone-aware), not `LocalDateTime`

| Trace | Reference |
|-------|-----------|
| **DD** | DD-2 |

- **Check:** `Quote.receivedAt` is `java.time.Instant`. Schema column is `TIMESTAMP WITH TIME ZONE`.
- **Command:** `rg "receivedAt" src/main/java/com/quant/binancequotes/model/Quote.java`
- **Command:** `rg "received_at" src/main/resources/schema.sql`
- **Pass:** Java type is `Instant`, SQL type is `TIMESTAMP WITH TIME ZONE`.

---

## P4. Java & Concurrency Correctness

> Catches common Java pitfalls, thread safety issues, and resource management errors.

### P4.1 `InterruptedException` never swallowed

| Trace | Reference |
|-------|-----------|
| **FM** | FM-1, FM-11 |

- **Check:** Every `catch (InterruptedException ...)` block either re-interrupts the thread or re-throws.
- **Command (ast-grep):** `ast-grep --pattern 'catch (InterruptedException $E) { $$$ }' --language java src/main/` → verify each body contains `Thread.currentThread().interrupt()` or `throw`.
- **Manual fallback:** `rg "catch.*InterruptedException" src/main/ -A 5` → eyeball each.
- **Pass:** Every catch block re-interrupts or rethrows.

### P4.2 No raw `Optional.get()` without guard

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-5 |

- **Check:** No bare `.get()` on `Optional` without preceding `.isPresent()`, `.filter()`, or usage inside `.orElseThrow()`.
- **Command (ast-grep):** `ast-grep --pattern '$OPT.get()' --language java src/main/` where OPT is Optional-typed.
- **Manual fallback:** `rg "\.get\(\)" src/main/` → exclude `ConcurrentHashMap.get()`, `Map.get()`.
- **Pass:** Every `Optional.get()` is guarded.

### P4.3 No TOCTOU races on `ConcurrentHashMap` (no get+put pattern)

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-6 |
| **DD** | DD-3 |

- **Check:** `QuoteService.update()` uses `compute()` or `merge()`, not `get()` followed by `put()`.
- **Command:** `rg "\.get\(" src/main/java/**/service/QuoteService.java` → should return nothing (no `get` in update logic).
- **Pass:** `update` method uses `compute` for atomic read-modify-write.

### P4.4 Resource leaks: connections/streams use try-with-resources

| Trace | Reference |
|-------|-----------|
| **FM** | FM-2 |

- **Check:** Any `getConnection()`, `new FileInputStream()`, or similar resource acquisition uses try-with-resources.
- **Command:** `rg "getConnection\(\)" src/main/` → verify surrounding try-with-resources.
- **Pass:** All resource acquisitions are in try-with-resources blocks.

### P4.5 `volatile` / `AtomicBoolean` used correctly for cross-thread flags

| Trace | Reference |
|-------|-----------|
| **DD** | DD-6 |
| **FM** | FM-1 |

- **Check:** Flags shared between threads (`shuttingDown`, `reconnecting`) are `volatile` or `AtomicBoolean`.
- **Command:** `rg "boolean\s+shuttingDown|boolean\s+reconnecting" src/main/` — must be `volatile`.
- **Command:** `rg "private.*boolean\s+(shutting|reconnect|stop|running)" src/main/` → verify `volatile` modifier.
- **Pass:** All cross-thread boolean flags are `volatile` or atomic.

### P4.6 No `System.out.println` in production code

| Trace | Reference |
|-------|-----------|

- **Check:** No `System.out` or `System.err` print statements in `src/main/`.
- **Command:** `rg "System\.(out|err)\.print" src/main/`
- **Pass:** Zero matches.

### P4.7 Broad `Exception` catches are intentional and documented

| Trace | Reference |
|-------|-----------|
| **FM** | FM-2, FM-3 |

- **Check:** Any `catch (Exception ...)` or `catch (Throwable ...)` in production code is either in a deliberate catch-all boundary (e.g., drainer loop, message parser) and has a comment/log explaining why.
- **Command:** `rg "catch\s*\(Exception\s|catch\s*\(Throwable\s" src/main/ -A 3`
- **Pass:** Each broad catch is in a boundary layer and logs the exception (not silently swallowed).

### P4.8 No resource leak on constructor failure after thread start

| Trace | Reference |
|-------|-----------|
| **FM** | FM-11 |

- **Check:** If `BatchPersistenceService` constructor starts the drainer thread and then throws, the thread must be stoppable. Verify constructor logic: thread start happens last, or there's a cleanup path.
- **Command:** Read `BatchPersistenceService` constructor — verify `Thread.start()` is the last statement.
- **Pass:** Thread start is the last operation in the constructor, OR there's a try-catch that shuts down the thread on failure.

### P4.9 Constructor-injected fields are `private final` (non-config classes)

| Trace | Reference |
|-------|-----------|

- **Check:** Service classes (not `@ConfigurationProperties`) that receive dependencies via constructor should declare those fields as `private final`. This prevents accidental reassignment and signals immutability.
- **Command:** `rg "public.*\(\s*\w+\s+\w+\)" src/main/java/**/service/ src/main/java/**/repository/ src/main/java/**/controller/` → extract constructor params, then verify corresponding fields are `private final`.
- **Command (ast-grep):** `ast-grep --pattern 'class $CLASS { private $TYPE $FIELD; $CTOR($TYPE $FIELD) }' --language java src/main/` where CTOR is the constructor and FIELD is not declared `final`.
- **Pass:** All constructor-injected fields in services, repositories, and controllers are `private final`. (Note: `@ConfigurationProperties` classes are excluded — Spring requires mutable fields for YAML binding.)

### P4.10 No public mutable instance fields

| Trace | Reference |
|-------|-----------|

- **Check:** No public instance fields unless they are `static final` constants. Public mutable fields break encapsulation and are a security/correctness risk.
- **Command:** `rg "public\s+(?!static\s+final)" src/main/ | grep -v "class\|interface\|record\|void\|@|enum"`
- **Pass:** Zero matches. (If any exist, verify they're intentional constants in interfaces or records.)

### P4.11 No static mutable state (static non-final fields)

| Trace | Reference |
|-------|-----------|

- **FM** | FM-1, FM-4 |

- **Check:** No `private static` fields that are not `final`. Static mutable state is a hidden global variable and is almost always a concurrency bug.
- **Command:** `rg "private static\s+(?!final)" src/main/`
- **Pass:** Zero matches. (The only exception is static constants like `SYMBOL_PATTERN` in `AppProperties`, which are `static final`.)

### P4.12 `record` used for pure data carriers, not classes with getters

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-3 |
| **DD** | DD-12 |

- **Check:** Immutable data models use Java 21 `record` instead of `class` with getter methods. Records are the idiomatic choice for value objects in modern Java.
- **Command:** `rg "class\s+\w+\s*{" src/main/java/**/model/`
- **Pass:** Model package contains only `record` definitions, no `class` definitions.

---

## P5. Architecture Invariant Compliance

> Enforces the documented conventions in CLAUDE.md and design_decisions.md.

### P5.1 No JPA dependency

| Trace | Reference |
|-------|-----------|
| **DD** | DD-1 |
| **Conventional** | CLAUDE.md "No JPA" |

- **Check:** No JPA dependency in the Maven dependency tree.
- **Command:** `mvn dependency:tree 2>/dev/null | grep -i jpa`
- **Pass:** Zero matches (empty output).

### P5.2 No `SELECT` in write-path repository

| Trace | Reference |
|-------|-----------|
| **DD** | DD-3 (write-only persistence) |
| **IP** | Phase 3 review gate |

- **Check:** `QuoteRepository.java` contains no `SELECT` statements.
- **Command:** `rg "SELECT" src/main/java/com/quant/binancequotes/repository/`
- **Pass:** Zero matches.

### P5.3 `QuoteService` has no DB dependency

| Trace | Reference |
|-------|-----------|
| **DD** | DD-3 |
| **FM** | FM-8 |

- **Check:** `QuoteService.java` imports no DB-related classes.
- **Command:** `rg "Repository|JdbcTemplate|DataSource|jdbc" src/main/java/com/quant/binancequotes/service/QuoteService.java`
- **Pass:** Zero matches.

### P5.4 No `!bookTicker` firehose subscription

| Trace | Reference |
|-------|-----------|
| **DD** | DD-9 |

- **Check:** WebSocket URL is a targeted combined stream, not `!bookTicker`.
- **Command:** `rg "!bookTicker" src/`
- **Pass:** Zero matches.

### P5.5 All SQL uses named parameters, no string concatenation

| Trace | Reference |
|-------|-----------|
| **Conventional** | CLAUDE.md "No parameter interpolation in SQL" |

- **Check:** No SQL constructed via string concatenation (`+` operator with variables).
- **Command:** `rg '".*".*\+.*SQL\|SQL.*\+.*"' src/main/java/**/repository/`
- **Command:** `rg "\+" src/main/java/com/quant/binancequotes/repository/QuoteRepository.java` → no concatenation with SQL string.
- **Pass:** All parameters are `:namedParam` style.

### P5.6 `spring.threads.virtual.enabled=true`

| Trace | Reference |
|-------|-----------|
| **DD** | DD-8 |

- **Check:** Property is set in `application.yml`.
- **Command:** `rg "virtual" src/main/resources/application.yml`
- **Pass:** `enabled: true` found under `spring.threads.virtual`.

### P5.7 Drainer thread named `quote-batch-writer`

| Trace | Reference |
|-------|-----------|
| **DD** | DD-8 |
| **IP** | Phase 3 review gate |

- **Check:** Virtual thread is created with explicit name.
- **Command:** `rg "quote-batch-writer" src/main/`
- **Pass:** Found in `BatchPersistenceService.java`.

### P5.8 WebSocket thread never blocks (non-blocking `offer`)

| Trace | Reference |
|-------|-----------|
| **DD** | DD-6 |
| **FM** | FM-4 |

- **Check:** `enqueue()` uses `offer()`, not `put()`.
- **Command:** `rg "\.put\(" src/main/java/com/quant/binancequotes/service/BatchPersistenceService.java`
- **Pass:** Zero matches (no `put()` call, only `offer()`).

### P5.9 No map hydration from DB at startup

| Trace | Reference |
|-------|-----------|
| **DD** | DD-3 (explicit deferral) |

- **Check:** No `@PostConstruct` or `ApplicationRunner` in `QuoteService` that loads from the DB.
- **Command:** `rg "PostConstruct|ApplicationRunner|CommandLineRunner" src/main/java/com/quant/binancequotes/service/QuoteService.java`
- **Pass:** Zero matches.

---

## P6. Spring Boot & Lifecycle

> Verifies framework configuration, bean lifecycle, and health monitoring.

### P6.1 `@PreDestroy` ordering: WS client closes before persistence drainer

| Trace | Reference |
|-------|-----------|
| **DD** | DD-6 |
| **FM** | FM-1, FM-11 |

- **Check:** Spring `@PreDestroy` runs in reverse initialization order. The WS client bean must be initialized AFTER `BatchPersistenceService` so its `@PreDestroy` runs FIRST.
- **Command:** Check bean dependency graph — does `BinanceWebSocketClient` depend on `BatchPersistenceService`? If yes, Spring destroys WS first.
- **Pass:** WS client has a constructor dependency on `BatchPersistenceService`, OR there's an explicit `@DependsOn` annotation, OR manual shutdown ordering via `SmartLifecycle`.

### P6.2 `@ConfigurationProperties` validated with JSR-380

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-2 |
| **IP** | Phase 1 |

- **Check:** All `@ConfigurationProperties` classes have `@Validated` and appropriate constraint annotations.
- **Command:** `rg "@ConfigurationProperties" src/main/ -A 2` → verify `@Validated` follows.
- **Pass:** `AppProperties` and `PersistenceProperties` both have `@Validated`.

### P6.3 No `@Value` annotations (use `@ConfigurationProperties`)

| Trace | Reference |
|-------|-----------|

- **Check:** No `@Value` annotations in production code.
- **Command:** `rg "@Value" src/main/`
- **Pass:** Zero matches.

### P6.4 `dev` profile boots without external infrastructure

| Trace | Reference |
|-------|-----------|
| **FM** | FM-10 |
| **IP** | Phase 0 |

- **Check:** `application-dev.yml` configures H2 in-memory DB.
- **Command:** `rg "h2|H2" src/main/resources/application-dev.yml`
- **Pass:** H2 datasource configured with `MODE=PostgreSQL`.

### P6.5 Health indicators registered and aggregated

| Trace | Reference |
|-------|-----------|
| **FM** | FM-1, FM-4 |
| **IP** | Phase 3, Phase 4 |

- **Check:** `PersistenceQueueHealthIndicator` and `BinanceStreamHealthIndicator` are `@Component` classes implementing `HealthIndicator`.
- **Command:** `rg "implements HealthIndicator" src/main/`
- **Pass:** At least two health indicators found.

### P6.6 Graceful shutdown enabled

| Trace | Reference |
|-------|-----------|
| **FM** | FM-11 |

- **Check:** `server.shutdown=graceful` is set in `application.yml`.
- **Command:** `rg "graceful" src/main/resources/application.yml`
- **Pass:** Found.

---

## P7. Security

> Cross-cutting security checks — no secrets leaked, no injection vectors.

### P7.1 No secrets in code or committed config

| Trace | Reference |
|-------|-----------|

- **Check:** No real API keys, passwords, or credentials in committed files (only placeholder defaults).
- **Command:** `rg "password.*=.*[^\"']\w{8,}" src/main/resources/` → flag non-trivial passwords.
- **Command:** `rg "api.key|apikey|api_key|secret_key" src/main/` → flag credential patterns.
- **Pass:** Only placeholder defaults (e.g., `postgres:postgres` for local dev).

### P7.2 No SQL injection (all parameters are named/bound)

| Trace | Reference |
|-------|-----------|
| **Conventional** | CLAUDE.md "No parameter interpolation in SQL" |

- **Check:** No SQL strings built by concatenating user input.
- **Command:** `rg "String.format.*SELECT\|String.format.*INSERT\|StringBuilder.*SQL" src/main/`
- **Pass:** Zero matches.

### P7.3 No stack traces in REST error responses

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-5 |

- **Check:** `@ControllerAdvice` / `@ExceptionHandler` does not include stack traces in response bodies.
- **Command:** `rg "getStackTrace|printStackTrace|exception.getMessage" src/main/java/**/controller/ src/main/java/**/exception/`
- **Pass:** Error responses contain only sanitized messages, no stack traces.

### P7.4 Actuator exposure is restricted

| Trace | Reference |
|-------|-----------|

- **Check:** `management.endpoints.web.exposure.include` does not expose everything.
- **Command:** `rg "exposure" src/main/resources/application.yml -A 2`
- **Pass:** Only `health`, `info`, `metrics` exposed (not `*` or `env` or `beans`).

---

## P8. Test Quality & Anti-Cheating

> Detects phantom tests, useless assertions, and test anti-patterns.

### P8.1 Every `@Test` method has at least 1 assertion

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-7 |

- **Check:** Count assertions per test method. Flag any with 0.
- **Command:**
  ```bash
  for f in $(find src/test -name "*Test.java"); do
    echo "=== $f ==="
    # Extract method names with @Test, then check for assertions in the method body
    rg -n "@Test" "$f" -A 20 | rg "assert|verify|assertThat|assertEquals|assertTrue|assertFalse|assertNotNull|expect"
  done
  ```
- **Pass:** Every `@Test` method contains at least one assertion or `verify` call.

### P8.2 No tests that only verify mock interactions with zero real assertions

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-7 |

- **Check:** Tests that call only `verify(mock, ...)` without any `assert*` on real state.
- **Command:** Manual review — flag tests where the only checks are `verify(mockRepo, ...)` with no `assertEquals` / `assertThat` on the system under test's state.
- **Pass:** Every test verifies real observable state, not just mock interactions.

### P8.3 No `@Disabled` / `@Ignore`d tests

| Trace | Reference |
|-------|-----------|
| **Conventional** | CLAUDE.md "No `@Disabled` / `@Ignore`d tests" |

- **Check:** No skipped tests.
- **Command:** `rg "@Disabled|@Ignore" src/test/`
- **Pass:** Zero matches.

### P8.4 No tautological assertions (always-true conditions)

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-7 |

- **Check:** Flag assertions that can never fail: `assertTrue(x >= 0)` where x is unsigned, `assertEquals(x, x)`, `assertNotNull(new Object())`, etc.
- **Command:** `rg "assertTrue\(.*>=\s*0\)|assertTrue\(true\)|assertEquals\([^,]+,\s*\1\)" src/test/`
- **Pass:** Zero tautological assertions.

### P8.5 Test helper duplication within reason

| Trace | Reference |
|-------|-----------|

- **Check:** `makeQuote()` / `testQuote()` helpers appear in multiple test files. Flag if > 4 copies.
- **Command:** `rg "private.*Quote.*(makeQuote|testQuote|createQuote|sampleQuote|buildQuote)" src/test/`
- **Pass:** ≤ 4 copies; > 4 suggests extracting a shared `QuoteFixture` utility class.

### P8.6 Every test file has at least 1 test method

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-7 |

- **Check:** Every `*Test.java` file contains at least one `@Test` annotation.
- **Command:** `for f in $(find src/test -name "*Test.java"); do count=$(rg -c "@Test" "$f" 2>/dev/null || echo 0); echo "$count $f"; done | sort -n`
- **Pass:** Every file has count ≥ 1.

### P8.7 Integration tests use Testcontainers (no external DB dependency)

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-7 |
| **IP** | Phase 3, Phase 7 |

- **Check:** Integration tests that need PostgreSQL use `@Testcontainers` + `PostgreSQLContainer`, not a real external DB.
- **Command:** `rg "@Testcontainers|PostgreSQLContainer" src/test/`
- **Pass:** All DB-dependent tests use Testcontainers.

### P8.8 `Thread.sleep` in unit tests flagged for flakiness risk

| Trace | Reference |
|-------|-----------|

- **Check:** Unit tests (non-integration) using `Thread.sleep` for timing assertions.
- **Command:** `rg "Thread.sleep" src/test/ -l` → for each file, determine if it's unit or integration.
- **Pass:** `Thread.sleep` only in tests where timing is explicitly under test (e.g., batch flush interval). No arbitrary `sleep(1000)` to "wait for things to settle."

---

## P9. Operational Readiness

> Verifies the project builds, runs, and meets CI quality gates.

### P9.1 `mvn clean verify` passes

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-9 |
| **IP** | Every phase review gate |

- **Check:** Full build passes: compile, test, spotless check.
- **Command:** `mvn clean verify`
- **Pass:** BUILD SUCCESS.

### P9.2 `mvn spotless:check` passes

| Trace | Reference |
|-------|-----------|
| **IP** | Phase 0 |

- **Check:** Code formatting is consistent.
- **Command:** `mvn spotless:check`
- **Pass:** All files are properly formatted.

### P9.3 No JPA in dependency tree

| Trace | Reference |
|-------|-----------|
| **DD** | DD-1 |

- **Command:** `mvn dependency:tree 2>/dev/null | grep -i jpa`
- **Pass:** Empty output.

### P9.4 Docker Compose starts healthy (when implemented)

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-9 |
| **IP** | Phase 6 |

- **Check:** `docker compose up` starts PostgreSQL + app; health check passes.
- **Command:** `docker compose up -d && sleep 30 && curl -sf http://localhost:18080/actuator/health | jq .status`
- **Pass:** Response status is `"UP"`.

### P9.5 README build/run/test instructions are accurate

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-9 |

- **Check:** README commands actually work on a fresh clone.
- **Manual:** Follow the README instructions end-to-end.
- **Pass:** `mvn verify`, `docker compose up`, and `curl` examples all work as documented.

### P9.6 No `TODO` / `FIXME` in production code without tracking note

| Trace | Reference |
|-------|-----------|

- **Check:** Any TODO/FIXME in `src/main/` has a justification comment or links to an issue.
- **Command:** `rg "TODO|FIXME" src/main/`
- **Pass:** Zero un-annotated TODOs, or each has a comment explaining deferral rationale.

---

## P10. Documentation Consistency

> Split into P10a (internal docs, Phase 7.5) and P10b (external docs, after Phase 8).

### P10a — Internal Doc Consistency (Phase 7.5)

### P10a.1 Every `DD-*` cross-reference resolves

| Trace | Reference |
|-------|-----------|

- **Check:** Every `DD-n` mentioned in any doc file resolves to an entry in `design_decisions.md`.
- **Command:** `rg "DD-[0-9]+" docs/ -o | sort -u` → for each, verify it has a `## DD-n` heading in `design_decisions.md`.
- **Pass:** Every DD reference resolves.

### P10a.2 Every `FM-*` cross-reference resolves

| Trace | Reference |
|-------|-----------|

- **Check:** Every `FM-n` mentioned in any doc file resolves to an entry in `failure_modes.md`.
- **Command:** `rg "FM-[0-9]+" docs/ -o | sort -u` → for each, verify it has a `### FM-n` heading in `failure_modes.md`.
- **Pass:** Every FM reference resolves.

### P10a.3 Every test named in `requirement_traceability.md` exists in `src/test/`

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-7 |

- **Check:** Extract test class/method names from traceability matrix and verify they exist.
- **Command:** `rg "Test\.java" docs/requirement_traceability.md -o | sort -u` → verify each file exists.
- **Pass:** All referenced test classes exist.

### P10a.4 Every class in `implementation_plan.md` tree exists or is explicitly future

| Trace | Reference |
|-------|-----------|
| **IP** | §1 project structure tree |

- **Check:** Every `.java` file listed in the implementation plan tree either exists or belongs to a phase not yet implemented.
- **Manual:** Compare tree listing against `find src/main -name "*.java"`.
- **Pass:** All implemented-phase files exist; unimplemented-phase files are clearly marked.

### P10a.5 Architecture diagram matches actual data flow

| Trace | Reference |
|-------|-----------|

- **Check:** The ASCII diagram in `architecture.md` §1 matches the actual bean dependency graph.
- **Manual:** Trace the data flow: WS → Parser → (QuoteService + BatchPersistenceService) → (REST + DB). Verify each arrow in the diagram.
- **Pass:** Diagram accurately represents the implemented flow.

### P10a.6 Schema in `architecture.md` matches `schema.sql`

| Trace | Reference |
|-------|-----------|
| **DD** | DD-2, DD-7 |

- **Check:** The DDL block in `architecture.md` §6 is identical to `src/main/resources/schema.sql`.
- **Manual:** Compare both side by side.
- **Pass:** Column names, types, constraints, and indexes are identical.

### P10a.7 FM numbering is sequential with no gaps

| Trace | Reference |
|-------|-----------|

- **Check:** FM IDs in `failure_modes.md` are sequential (FM-1, FM-2, …, FM-n) with no gaps.
- **Command:** `rg "^### FM-" docs/failure_modes.md` → verify sequential numbering.
- **Pass:** No gaps in FM numbering (e.g., no jump from FM-10 to FM-12 without FM-11).

### P10a.8 `CLAUDE.md` conventions match actual code patterns

| Trace | Reference |
|-------|-----------|

- **Check:** Every convention stated in CLAUDE.md is actually enforced in the codebase.
- **Manual:** Review each bullet in CLAUDE.md's "Conventions" section against the code.
- **Pass:** No convention is contradicted by the code.

### P10a.9 Doc text matches actual implementation (no stale descriptions)

| Trace | Reference |
|-------|-----------|

- **Check:** Key phrases in docs match reality. For example: DD-1 title says "`JdbcClient`" but actual code uses `NamedParameterJdbcTemplate` — the doc body should explain this.
- **Command:** `rg "JdbcClient" docs/` → verify references are accurate or explained.
- **Pass:** All doc descriptions match implementation, or discrepancies are explicitly noted.

---

### P10b — External Doc Consistency (After Phase 8)

### P10b.1 README links are valid and point to correct sections

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-9 |

- **Check:** All `[text](link)` references in README resolve.
- **Manual:** Click / verify each link.
- **Pass:** No broken links.

### P10b.2 README API examples match actual endpoint behavior

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-5, REQ-9 |

- **Check:** `curl` examples in README produce the documented output.
- **Manual:** Run each `curl` command against a running instance.
- **Pass:** Response matches README documentation.

### P10b.3 README prerequisites match actual project requirements

| Trace | Reference |
|-------|-----------|
| **REQ** | REQ-9 |

- **Check:** Java version, Maven version, Docker version stated in README match `pom.xml` source/target and actual requirements.
- **Command:** `rg "java.version|maven.compiler" pom.xml` → cross-reference with README.
- **Pass:** Prerequisites match.

---

## Appendix: ast-grep Commands Reference

For checks that benefit from AST-level analysis (more precise than text grep), install `ast-grep`:

```bash
# Install
npm install -g @ast-grep/cli
# Or: cargo install ast-grep

# Usage pattern
ast-grep --pattern 'PATTERN' --language java src/main/
```

Useful patterns for this audit:

| Pattern | What it catches |
|---------|----------------|
| `new BigDecimal($$$LIT)` where LIT is not a string | Double-construction of BigDecimal |
| `catch (InterruptedException $E) { $$$ }` without interrupt | Swallowed interrupt |
| `$OPT.get()` on Optional | Unguarded Optional.get |
| `catch (Exception $E) { $$$ }` without rethrow/log | Swallowed exception |
| `$MAP.get($K); ... $MAP.put($K, $V)` | TOCTOU race on map |
