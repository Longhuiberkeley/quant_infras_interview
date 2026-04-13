# Binance Quote Streaming Service

A 1-day Java take-home: a Spring Boot service that ingests real-time order-book quotes for **10 Binance USDT-M Perpetual** instruments over WebSocket, persists the full time-series history to PostgreSQL, and serves the latest quote per instrument over REST with sub-millisecond read latency.

Built per the specification in [`docs/interviewer_requirements.md`](docs/interviewer_requirements.md).

## Architecture at a Glance

```
Binance WebSocket (fstream) ──▶ QuoteMessageParser ──▶ QuoteService (ConcurrentHashMap)
                                      │                        │
                                      ▼                        ▼
                               (validation)            REST /api/quotes
                                      │
                                      ▼
                          BatchPersistenceService ──▶ PostgreSQL (JdbcClient, batched)
```

- **Reads** never hit the database — `QuoteService` serves from an in-memory `ConcurrentHashMap`.
- **Writes** are batched asynchronously on a single virtual thread (`quote-batch-writer`) with a bounded queue (10 000) and backpressure (drop-oldest at 90%).
- **Deduplication** is handled by `UNIQUE(symbol, update_id)` + `ON CONFLICT DO NOTHING`.

Full system diagram: [`docs/architecture.md`](docs/architecture.md).
Design rationale (ADR-style): [`docs/design_decisions.md`](docs/design_decisions.md).

## Quick Start

### Prerequisites

| Tool | Version |
|------|---------|
| Java | 21+ (Temurin recommended) |
| Maven | 3.9+ |
| Docker | optional, for `docker compose` path |

```bash
# SDKMAN users:
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

### Option A — Docker Compose (PostgreSQL + App)

```bash
cp .env.example .env          # customize if needed; defaults work out of the box
docker compose up --build
```

Both containers come up healthy within ~60 s. The app connects to Binance live and starts ingesting immediately.

### Option B — H2 Dev Profile (No Docker)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Runs against an in-memory H2 database. WebSocket ingestion still connects to Binance live.

### Option C — Build & Test Only

```bash
mvn clean verify
```

Runs Spotless (Google Java Format), compiles, and executes all 95+ tests. All tests are hermetic — no external network or database required.

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/quotes` | Latest quote for every configured symbol |
| `GET` | `/api/quotes/{symbol}` | Latest quote for a single symbol (404 if unknown or no quote yet) |

### Example

```bash
curl -s localhost:18080/api/quotes/BTCUSDT | python3 -m json.tool
```

```json
{
  "symbol": "BTCUSDT",
  "bid": "67432.15000000",
  "bidSize": "12.50000000",
  "ask": "67432.20000000",
  "askSize": "8.30000000",
  "updateId": 4892741,
  "eventTime": 1713000000000,
  "transactionTime": 1713000000001,
  "receivedAt": "2026-04-13T12:00:00.123Z"
}
```

Monetary fields are serialized as **plain decimals** (never scientific notation) via `BigDecimal` end-to-end.

### All Quotes

```bash
curl -s localhost:18080/api/quotes | python3 -m json.tool
```

Returns a JSON object keyed by symbol (`{"BTCUSDT": {...}, "ETHUSDT": {...}, ...}`), omitting symbols that have not yet received a WebSocket frame.

## Observability

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | Overall status + `binanceStream` + `persistenceQueue` sub-components |
| `GET /actuator/metrics/binance.quote.lag.millis?tag=symbol:BTCUSDT` | Per-symbol freshness lag (ms) |
| `GET /actuator/metrics/binance.quote.lag.max.millis` | Fleet-max freshness lag across all 10 symbols |
| `GET /actuator/info` | Application metadata |

Actuator exposure is limited to `health`, `info`, `metrics` — no `env`, `beans`, or `*`.

## Configuration

All configuration lives in `src/main/resources/application.yml`. Environment variables override defaults:

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `binance_quotes` | Database name |
| `DB_USER` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password |
| `SERVER_PORT` | `18080` | HTTP port |

### Key Tunables

| Property | Default | Description |
|----------|---------|-------------|
| `app.symbols` | 10 USDT pairs | Exactly 10 symbols, uppercase, validated at startup |
| `persistence.queue-capacity` | 10 000 | Max buffered quotes before backpressure |
| `persistence.batch-size` | 200 | Rows per batch INSERT |
| `persistence.flush-ms` | 500 | Flush interval for the batch writer |
| `binance.ws.staleness-threshold-ms` | 30 000 | Health indicator staleness cutoff |

### Proxy / Binance US Override

To route through a proxy or point at Binance US, override the relevant properties:

```yaml
binance:
  ws:
    base-url: wss://stream.binance.us:9443   # Binance US override
    proxy-host: proxy.corp                    # optional HTTP proxy
    proxy-port: 3128
```

The stream path (`/stream?streams=...`) is appended automatically.

## Design Decisions

Every non-obvious choice is documented as an ADR in [`docs/design_decisions.md`](docs/design_decisions.md). Highlights:

| ID | Decision | Why |
|----|----------|-----|
| DD-1 | No JPA | `@GeneratedValue(IDENTITY)` silently breaks JDBC batching |
| DD-2 | `BigDecimal` everywhere, `NUMERIC(24,8)` in DB | Money must not lose precision |
| DD-3 | Reads never hit the DB | Sub-ms read latency via `ConcurrentHashMap` |
| DD-6 | Non-blocking `offer` on persistence queue | Blocking on the WS thread causes Binance disconnects |
| DD-9 | No `!bookTicker` firehose | Targeted combined-stream from the configured symbol list |
| DD-10 | USDT-M Perpetuals (not SPOT) | SPOT `@bookTicker` omits `E` and `T` fields |
| DD-11 | Micrometer lag gauges | Single number for real-time correctness |
| DD-13 | Parser validates business invariants | Zero/negative prices, crossed spreads, future timestamps rejected |

## Failure Modes

Documented in [`docs/failure_modes.md`](docs/failure_modes.md). Summary:

| Failure Mode | Mitigation |
|--------------|------------|
| WebSocket disconnect | Exponential backoff reconnect (1s → 60s cap) |
| PostgreSQL unavailable | Bounded queue (10 000) absorbs ~3.3 min at 50 msg/s; batch writer retries with backoff |
| Malformed JSON | Try-catch around parser; log + skip |
| Message burst (backpressure) | Drop-oldest above 90% queue capacity with `WARN` log |
| Reconnect storm | Atomic `reconnecting` guard; backoff resets only after first message post-open |
| Graceful shutdown | `@PreDestroy` ordering: WS closes first, drainer flushes remaining items within bounded timeout |

## Test Quality

| Category | Count | Framework |
|----------|-------|-----------|
| Unit | ~70 | JUnit 5, Mockito, MockMvc, OkHttp MockWebServer |
| Integration | ~15 | Testcontainers (PostgreSQL 16) |
| Performance / SLO | ~5 | `System.nanoTime()` micro-benchmarks |

All 5 SLO rows are validated by tests that measure and assert:

| SLO | Test |
|-----|------|
| Read p99 < 1 ms | `QuoteServicePerformanceTest#p99ReadUnder1ms` |
| REST p99 < 5 ms | `QuoteControllerTest#p99LatencyUnder5ms` |
| Ingest-to-available p99 < 5 ms | `ApplicationIntegrationTest#ingestLatencyUnder5ms` |
| Freshness lag p99 < 500 ms | `IngestLagTest#lagGaugeUnder500msAt500rps` |
| Persistence headroom ≥ 10× | `BatchPersistenceServiceTest#sustains500rps` |

Pre-submission audit: [`docs/audit_results.md`](docs/audit_results.md) (71/71 checks passed, P1–P9 + P10a + P10b).

## Project Structure

```
src/main/java/com/quant/binancequotes/
├── config/       # @ConfigurationProperties (AppProperties, BinanceProperties, PersistenceProperties)
├── model/        # Quote record (immutable, BigDecimal monetary fields)
├── websocket/    # BinanceWebSocketClient, QuoteMessageParser
├── service/      # QuoteService (in-memory), BatchPersistenceService (async batch writer)
├── repository/   # QuoteRepository (JdbcClient batchInsert)
├── controller/   # QuoteController, ApiExceptionHandler
└── health/       # BinanceStreamHealthIndicator, PersistenceQueueHealthIndicator
```

Full traceability: [`docs/requirement_traceability.md`](docs/requirement_traceability.md).
Implementation plan: [`docs/implementation_plan.md`](docs/implementation_plan.md).

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `mvn` or `java` not found | Run `source "$HOME/.sdkman/bin/sdkman-init.sh"` |
| Docker Compose Ryuk container fails (Apple Silicon) | Ensure Docker Desktop ≥ 4.18, or set `TESTCONTAINERS_RYUK_DISABLED=true` for tests |
| Application starts but no quotes appear | Check `/actuator/health` — `binanceStream` must be `UP`. If `DOWN`, verify network access to `fstream.binance.com` |
| Connection refused to PostgreSQL | Run `docker compose up` first, or use `-Dspring-boot.run.profiles=dev` for H2 |
