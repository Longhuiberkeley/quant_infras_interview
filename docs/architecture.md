# Architecture

## 1. System Overview

```
                        ┌──────────────────────────────────────────────────┐
                        │           Binance Quote Streaming Service        │
                        │                                                  │
 Binance WebSocket      │  ┌──────────────────┐    ┌─────────────────┐    │
 (USDT-M Perps          ──────►│ WebSocketClient   │    │  REST API       │    │
  @bookTicker)          │  │  (OkHttp)         │    │  (Spring MVC)   │    │
 wss://fstream.…        │  │                   │    │                 │    │
 targeted combined      │  │  - reconnect      │    │                 │    │
 stream for 10 pairs    │  │  - heartbeat      │    │ GET /api/quotes │◄───── Consumers
                        │  │  - lag gauge      │    │ GET /api/quotes │    │
                        │  └────────┬─────────┘    │   /{symbol}     │    │
                        │           │              │ GET /api/quotes │    │
                        │           ▼              │   /{symbol}     │    │
                        │  ┌──────────────────┐    │   /history      │    │
                        │  │ QuoteMessageParser│    │       │    │    │
                        │  │  (JSON → Quote)   │    └───────┼────┼────┘    │
                        │  │  BigDecimal fields │            │    │         │
                        │  └────────┬─────────┘             │    │         │
                        │           │                        │    │         │
                        │     ┌─────┴──────┐                 │    │         │
                        │     ▼            ▼                 │    │         │
                        │  ┌──────────┐ ┌─────────────────┐  │    │         │
                        │  │QuoteSvc  │ │BatchPersistSvc  │  │    │         │
                        │  │(Concurrent│ │(LinkedBlocking- │  │    │         │
                        │  │ HashMap)  │ │ Queue → platform│  │    │         │
                        │  │ O(1) reads│ │ thread drainer) │  │    │         │
                        │  └─────┬────┘ └───────┬─────────┘  │    │         │
                        │        │              │             │    │         │
                        │        │              ▼             │    │         │
                        │        │     ┌───────────────────┐ │    │         │
                        │        │     │  PostgreSQL 16     │◄┼────┘         │
                        │        │     │  quotes(NUMERIC,   │ │ (history     │
                        │        │     │  UNIQUE(symbol,    │ │  endpoint    │
                        │        │     │         update_id))│ │  only)       │
                        │        │     └───────────────────┘ │              │
                        │        └───────────────────────────┘              │
                        └──────────────────────────────────────────────────┘
```

## 2. Data Flow

1. **Ingest.** `BinanceWebSocketClient` opens a single OkHttp connection to the targeted combined stream — `wss://fstream.binance.com/stream?streams=btcusdt@bookTicker/ethusdt@bookTicker/...` — built from the configured symbol list at startup. USDT-M Perpetuals rather than SPOT because the SPOT `@bookTicker` payload omits the event-time and transaction-time fields we rely on for lag observability (see `design_decisions.md` DD-10).
2. **Parse.** Raw JSON (wrapper shape `{"stream":"...","data":{...}}`) is unwrapped and deserialized into an immutable `Quote` record (`symbol`, `bid`, `bidSize`, `ask`, `askSize`, `updateId`, `eventTime` [Binance `E`], `transactionTime` [Binance `T`], `receivedAt`). All numeric fields are `BigDecimal`. Business invariants (positive prices, non-crossed spread, valid timestamps) are validated before construction; corrupt messages are dropped (DD-13).
3. **Route — two parallel paths.**
   - **Hot path (reads):** `QuoteService` updates a `ConcurrentHashMap<String, Quote>`. O(1) reads, sub-microsecond in practice.
   - **Warm path (persistence):** `BatchPersistenceService` `offer`s onto a bounded `LinkedBlockingQueue<Quote>` (capacity 10 000). A single named virtual thread drains up to `persistence.batch-size` items (default 200) or flushes on `persistence.flush-ms` timeout (default 500), whichever comes first, and issues one `INSERT ... ON CONFLICT (symbol, update_id) DO NOTHING` via `JdbcClient.batchUpdate`.
4. **Serve.** `QuoteController` exposes REST endpoints; latest-quote endpoints are backed by the in-memory map (DD-3) while the history endpoint queries PostgreSQL directly (DD-15).
5. **Observe.** `/actuator/health` aggregates the default DB check with two custom indicators (`binanceStream`, `persistenceQueue`). The Micrometer gauge `binance.quote.lag.millis` tracks `now − eventTime` so freshness is one number away.

## 3. Key Design Decisions

See [`design_decisions.md`](./design_decisions.md) for ADR-style entries covering each non-obvious choice: `JdbcClient` over JPA, `BigDecimal` over `double`, in-memory map for reads, OkHttp over the JDK WebSocket client, PostgreSQL over TSDB alternatives, drop-oldest backpressure, natural dedup via `(symbol, update_id)`, the virtual-threads property, targeted over firehose subscription, semaphore-based concurrency limiting (DD-14), and the historical quote query endpoint (DD-15).

## 4. Technology Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 21 |
| Framework | Spring Boot | 3.3.x |
| Build | Maven | 3.9+ |
| WebSocket Client | OkHttp | 4.12.x |
| Database | PostgreSQL | 16 (alpine) |
| DB access | Spring JDBC (`JdbcClient`) | Spring 6.x |
| JSON | Jackson (BigDecimal mode) | managed by Spring Boot |
| Metrics / Health | Spring Boot Actuator + Micrometer | managed by Spring Boot |
| Testing | JUnit 5, Mockito, Testcontainers, MockMvc | managed by Spring Boot |
| Style | Spotless + google-java-format | 1.22+ |
| Containerization | Docker + Docker Compose | — |

## 5. Top 10 Instruments (USDT-M Perpetuals)

Ten non-stablecoin USDT-M perpetual contracts, configurable via `application.yml` (`app.symbols`). The service fails to start if the list is not exactly 10 entries or if any symbol is not uppercase. The ticker names (e.g. `BTCUSDT`) are identical to their SPOT counterparts — on `fstream.binance.com` they resolve to the perpetual contract rather than the spot pair.

| # | Symbol | Name |
|---|--------|------|
| 1 | BTCUSDT | Bitcoin |
| 2 | ETHUSDT | Ethereum |
| 3 | BNBUSDT | BNB |
| 4 | SOLUSDT | Solana |
| 5 | XRPUSDT | XRP |
| 6 | DOGEUSDT | Dogecoin |
| 7 | ADAUSDT | Cardano |
| 8 | TRXUSDT | TRON |
| 9 | LINKUSDT | Chainlink |
| 10 | AVAXUSDT | Avalanche |

## 6. Schema

```sql
CREATE TABLE IF NOT EXISTS quotes (
    id               BIGSERIAL PRIMARY KEY,
    symbol           VARCHAR(20) NOT NULL,
    bid_price        NUMERIC(24,8) NOT NULL,
    bid_size         NUMERIC(24,8) NOT NULL,
    ask_price        NUMERIC(24,8) NOT NULL,
    ask_size         NUMERIC(24,8) NOT NULL,
    update_id        BIGINT NOT NULL,
    event_time       BIGINT NOT NULL,   -- Binance `E` (ms since epoch)
    transaction_time BIGINT NOT NULL,   -- Binance `T` (ms since epoch)
    received_at      BIGINT NOT NULL,         -- local clock (ms since epoch)
    CONSTRAINT quotes_symbol_updateid_uk UNIQUE (symbol, update_id),
    CONSTRAINT chk_positive_bid  CHECK (bid_price > 0),
    CONSTRAINT chk_positive_ask  CHECK (ask_price > 0),
    CONSTRAINT chk_nonneg_sizes CHECK (bid_size >= 0 AND ask_size >= 0)
);

CREATE INDEX IF NOT EXISTS idx_quotes_symbol_time
    ON quotes (symbol, event_time DESC);
```

- `UNIQUE(symbol, update_id)` enables `INSERT ... ON CONFLICT DO NOTHING` for natural dedup on WebSocket reconnect replays (see DD-7, FM-5).
- `BIGSERIAL` PK is retained because we never fetch the generated id — batching is safe under `JdbcClient`.
- `NUMERIC(24,8)` matches Binance's decimal string precision exactly (see DD-2).
- CHECK constraints reject corrupt prices and sizes at the DB level (see DD-13).

## 7. API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/quotes` | Latest quote for each of the 10 configured instruments (map keyed by symbol) |
| GET | `/api/quotes/{symbol}` | Latest quote for a specific instrument (`BTCUSDT`, …) — 404 if unknown |
| GET | `/api/quotes/{symbol}/history?from=X&to=Y` | Historical quotes from PostgreSQL for a time range (epoch millis, max 1000 results) |
| GET | `/actuator/health` | Aggregated health (DB + WS + queue) |
| GET | `/actuator/metrics/binance.quote.lag.millis` | Freshness lag gauge (now − eventTime) |

Response shape (the `Quote` record is serialized directly — no separate DTO, see DD-12):
```json
{
  "symbol": "BTCUSDT",
  "bid": 67432.15,
  "bidSize": 1.234,
  "ask": 67432.20,
  "askSize": 0.567,
  "updateId": 4123567890,
  "eventTime": 1713456789000,
  "transactionTime": 1713456788997,
  "receivedAt": "2026-04-13T15:33:09.012Z"
}
```

`bid`, `bidSize`, `ask`, `askSize` are serialized as JSON numbers in plain decimal form (Jackson's `WRITE_BIGDECIMAL_AS_PLAIN`) — never scientific notation.

The default server port is **18080** (override with `--server.port=` or `SERVER_PORT` env var).

## 8. Service Level Objectives

| Metric | Target |
|--------|--------|
| Service-layer read latency (in-memory map) | p50 < 100 µs, p99 < 1 ms |
| REST endpoint latency (localhost) | p99 < 5 ms |
| Ingest-to-available lag (WS frame → map) | p99 < 5 ms |
| Binance freshness lag (now − eventTime) | p99 < 500 ms |
| Sustained persistence throughput headroom | ≥ 10× observed rate |

Each SLO has a corresponding test; see `requirement_traceability.md` for the mapping.
