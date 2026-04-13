# Requirement Traceability Matrix

Maps each interviewer requirement → design decision → implementation module → test coverage.

## Requirements → Design → Code → Tests

### REQ-1: Receive real-time quote data for top 10 instruments via Binance WebSocket

| Aspect | Detail |
|--------|--------|
| **Source** | Interviewer requirements §2: "Connect via the Binance Streaming API (WebSockets)" |
| **Data source** | USDT-M Perpetuals (spec allows "SPOT or Perpetuals"; perps `@bookTicker` carries `E` and `T`, SPOT does not — see `design_decisions.md` DD-10) |
| **Design** | Targeted combined stream of per-symbol `@bookTicker` streams: `wss://fstream.binance.com/stream?streams=btcusdt@bookTicker/ethusdt@bookTicker/...` — built from the configured symbol list at startup. Explicitly **not** the `!bookTicker` firehose (see DD-9). |
| **Module** | `websocket/BinanceWebSocketClient.java`, `websocket/QuoteMessageParser.java` |
| **Config** | `config/BinanceProperties.java`, `config/AppProperties.java` — symbol list, base URL, staleness threshold |
| **Test** | `BinanceWebSocketClientTest.java` — mock WebSocket, verify message handling, reconnect behavior |

### REQ-2: Select top 10 instruments by market cap

| Aspect | Detail |
|--------|--------|
| **Source** | Interviewer requirements §2: "Select the top 10 instruments by market capitalization" |
| **Design** | Hardcoded list of 10 USDT-M Perpetual pairs; documented in `architecture.md` |
| **Module** | `config/AppProperties.java` — `@ConfigurationProperties` list of symbols |
| **Test** | `AppPropertiesTest.java` — verify exactly 10 symbols loaded |

### REQ-3: Quote data includes bid, bid_size, ask, ask_size

| Aspect | Detail |
|--------|--------|
| **Source** | Interviewer requirements §2: "Each quote must include bid, bid_size, ask, and ask_size" |
| **Design** | Immutable `Quote` record with `symbol`, `bid`/`bidSize`/`ask`/`askSize` as `BigDecimal`, plus `updateId` (Binance `u`), `eventTime` (Binance `E`), `transactionTime` (Binance `T`), `receivedAt` (local ingest). |
| **Module** | `model/Quote.java`, `websocket/QuoteMessageParser.java` |
| **Test** | `QuoteMessageParserTest.java` — verify JSON → Quote mapping for all fields, including `E` and `T` |

### REQ-4: Persist complete time-series history to database

| Aspect | Detail |
|--------|--------|
| **Source** | Interviewer requirements §2: "Persist the complete time-series history of every quote received" |
| **Design** | PostgreSQL with batch INSERT via async queue |
| **Module** | `service/BatchPersistenceService.java`, `repository/QuoteRepository.java` |
| **Schema** | `resources/schema.sql` — `quotes` table with timestamp index |
| **Test** | `BatchPersistenceServiceTest.java` — unit test batching logic |
| **Test** | `QuoteRepositoryIntegrationTest.java` — Testcontainers, verify INSERT |

### REQ-5: Provide mechanism to get latest quotes

| Aspect | Detail |
|--------|--------|
| **Source** | Interviewer requirements §2: "Provide a mechanism to get the most recent quotes" |
| **Design** | REST API backed by in-memory `ConcurrentHashMap` |
| **Module** | `controller/QuoteController.java`, `service/QuoteService.java` |
| **Endpoint** | `GET /api/quotes`, `GET /api/quotes/{symbol}` |
| **Test** | `QuoteControllerTest.java` — `@WebMvcTest`, verify response format |
| **Test** | `QuoteServiceTest.java` — verify update and retrieval |

### REQ-6: Low-latency processing

| Aspect | Detail |
|--------|--------|
| **Source** | Interviewer requirements §3: "Performance is a top priority" |
| **Design** | In-memory map for reads (O(1)); async queue decouples ingestion from DB writes |
| **Module** | `service/QuoteService.java`, `service/BatchPersistenceService.java` |
| **Test** | `QuoteServicePerformanceTest.java` — assert retrieval < 1ms |

### REQ-7: Adequate test coverage

| Aspect | Detail |
|--------|--------|
| **Source** | Interviewer requirements §3: "Adequate test coverage is required" |
| **Design** | Unit tests for all services/controllers; integration tests with Testcontainers for DB |
| **Framework** | JUnit 5, Mockito, Spring Test, Testcontainers |
| **Test modules** | All `*Test.java` files under `src/test/` |
| **CI** | `mvn test` runs all tests |

### REQ-8: GitHub repository with commit history

| Aspect | Detail |
|--------|--------|
| **Source** | Interviewer requirements §4: "Full commit history preserved" |
| **Design** | Git repo with meaningful, incremental commits per implementation phase |
| **Process** | Commit after each phase; see `implementation_plan.md` for phase breakdown |

### REQ-9: README with build, run, test instructions

| Aspect | Detail |
|--------|--------|
| **Source** | Interviewer requirements §4: "Comprehensive README" |
| **Deliverable** | `README.md` at project root |

---

## Test Coverage Summary

| Test Class | Type | Covers |
|-----------|------|--------|
| `QuoteMessageParserTest` | Unit | REQ-3 |
| `QuoteServiceTest` | Unit | REQ-5, REQ-6 |
| `BatchPersistenceServiceTest` | Unit | REQ-4 |
| `BinanceWebSocketClientTest` | Unit | REQ-1 |
| `QuoteControllerTest` | Unit (@WebMvcTest) | REQ-5 |
| `AppConfigTest` | Unit | REQ-2 |
| `QuoteRepositoryIntegrationTest` | Integration (Testcontainers) | REQ-4 |
| `QuoteServicePerformanceTest` | Performance | REQ-6 |
| `ApplicationIntegrationTest` | Integration (@SpringBootTest) | REQ-1 through REQ-5 end-to-end |

## Coverage by Requirement

| Requirement | Unit Tests | Integration Tests |
|-------------|-----------|-------------------|
| REQ-1 WebSocket ingestion | `BinanceWebSocketClientTest` | `ApplicationIntegrationTest` |
| REQ-2 Top 10 instruments | `AppPropertiesTest` | — |
| REQ-3 Quote fields | `QuoteMessageParserTest` | `ApplicationIntegrationTest` |
| REQ-4 DB persistence | `BatchPersistenceServiceTest` | `QuoteRepositoryIntegrationTest` |
| REQ-5 Latest quotes API | `QuoteServiceTest`, `QuoteControllerTest` | `ApplicationIntegrationTest` |
| REQ-6 Latency | `QuoteServicePerformanceTest`, `IngestLagTest` | — |
| REQ-7 Test coverage | (meta-requirement) | — |

---

## Non-Functional Requirements (Resilience)

Maps each failure mode from [`failure_modes.md`](./failure_modes.md) to the mitigation module and the test(s) that prove the mitigation works. Every `FM-*` has at least one test or an explicit "documented" marker with justification.

| FM ID | Failure Mode | Mitigation Module | Test(s) Proving Mitigation |
|-------|--------------|-------------------|----------------------------|
| FM-1 | WebSocket disconnect | `BinanceWebSocketClient` — exponential backoff reconnect with atomic in-flight guard | `BinanceWebSocketClientTest#reconnect_schedulesOnce_underStormOfClosures`, `BinanceWebSocketClientTest#backoffProgression`, `ApplicationIntegrationTest#reconnectAfterNetworkDrop` |
| FM-2 | DB temporarily unavailable | `BatchPersistenceService` — bounded queue absorbs burst + capped exponential retry | `BatchPersistenceServiceTest#retriesOnTransientSqlFailure`, `QuoteRepositoryIntegrationTest#batchInsert_survivesRestart` |
| FM-3 | Malformed JSON message | `QuoteMessageParser` — try/catch, log raw, skip | `QuoteMessageParserTest#malformedJson_returnsEmpty`, `QuoteMessageParserTest#subscriptionAckFrame_returnsEmpty` |
| FM-4 | High message burst (backpressure) | `BatchPersistenceService` — non-blocking `offer` with drop-oldest above 90% depth | `BatchPersistenceServiceTest#dropOldest_whenQueueAboveThreshold`, `BatchPersistenceServiceTest#producerNeverBlocks` |
| FM-5 | Duplicate messages on reconnect | Schema — `UNIQUE(symbol, update_id)` + `INSERT ... ON CONFLICT DO NOTHING` | `QuoteRepositoryIntegrationTest#duplicateUpdateId_isIgnored` |
| FM-6 | JVM crash / OOM | Bounded queue cap + `-Xmx` in README | *Documented*: DD-6 bounds max in-flight loss to queue capacity (~3 min at 50 msg/s) |
| FM-7 | Clock skew | `Quote` uses Binance `eventTime` for business logic; `receivedAt` is debug-only | `QuoteMessageParserTest#eventTimePreserved`, `QuoteServiceTest#monotonicByUpdateId` |
| FM-8 | Slow REST consumer | O(1) in-memory read path, non-blocking | `QuoteServicePerformanceTest#p99ReadUnder1ms`, Tomcat default pool sizing |
| FM-9 | VPN / firewall | OkHttp proxy support via `BinanceProperties.proxy` | *Documented* in README (dev-environment concern) |
| FM-10 | Application startup failure | `dev` profile with H2; actuator health for orchestration | `ApplicationIntegrationTest#bootsUnderDevProfile_withoutPostgres` |

**Shutdown / durability NFR (beyond the original FM list):**

| ID | Concern | Module | Test |
|----|---------|--------|------|
| FM-11 | Data loss on SIGTERM | `BatchPersistenceService` — `@PreDestroy` drains queue with bounded timeout | `BatchPersistenceServiceTest#shutdownFlushesQueue`, `BatchPersistenceServiceTest#shutdownTimeoutRespected` |

**SLOs (from `architecture.md` §8) → Tests:**

| SLO | Test |
|-----|------|
| Read p99 < 1 ms | `QuoteServicePerformanceTest#p99ReadUnder1ms` |
| REST p99 < 5 ms | `QuoteControllerTest#p99LatencyUnder5ms` |
| Ingest-to-available p99 < 5 ms | `ApplicationIntegrationTest#ingestLatencyUnder5ms` |
| Freshness lag p99 < 500 ms | `IngestLagTest#lagGaugeUnder500msAt500rps` |
| Persistence headroom ≥ 10× | `BatchPersistenceServiceTest#sustains500rps` |
