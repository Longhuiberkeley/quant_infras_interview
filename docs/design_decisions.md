# Design Decisions

ADR-style log of the non-obvious choices in this service. Each entry has the same shape:
**Context** — the forcing function, **Decision** — what we picked, **Alternatives considered** — what we rejected and why, **Consequences** — what this now costs or buys us.

Entries are not listed in priority order; cross-reference IDs (`DD-n`) are stable.

---

## DD-1 — Use `JdbcClient.batchUpdate`, not Spring Data JPA, for the write path

**Context.** Write throughput matters more than developer ergonomics here — the service persists every incoming quote. We have no read path from the database (latest reads are served from an in-memory map — see DD-3).

**Decision.** Use `spring-boot-starter-jdbc` and Spring 6's `JdbcClient` with `batchUpdate`. Drop `spring-boot-starter-data-jpa` from the dependency tree entirely.

**Alternatives considered.**
- *Spring Data JPA + Hibernate with `BIGSERIAL` IDENTITY PK.* Rejected: Hibernate silently disables JDBC batching when the identifier generation strategy is `IDENTITY`, because it needs the generated id after every insert. `hibernate.jdbc.batch_size=200` becomes a no-op and you get one round-trip per row.
- *JPA with a `SEQUENCE` or `TABLE` generator.* Rejected: works, but pulls in the full ORM to manipulate five columns of primitive data. All the indirection, none of the benefit.
- *Raw `JdbcTemplate.batchUpdate`.* Acceptable; `JdbcClient` is the modern fluent wrapper introduced in Spring 6 and is clearer to read.

**Consequences.** ~10× write throughput headroom versus a naive JPA setup. No entity lifecycle to reason about. SQL is explicit in the repository and easy to grep. We give up JPA's lazy-loading and entity graph — irrelevant here because we never read via the ORM.

---

## DD-2 — `BigDecimal` in Java, `NUMERIC(24,8)` in PostgreSQL

**Context.** Binance publishes bid/ask prices and sizes as decimal strings (e.g. `"67432.15"`). This is deliberate: floating-point representation cannot express common decimals exactly (`0.1 + 0.2 ≠ 0.3`). Any financial service that reduces quotes to IEEE-754 is wrong at the millicent level and will be flagged in review.

**Decision.** Monetary fields are `BigDecimal` throughout the application and `NUMERIC(24,8)` in the schema. Jackson is configured with `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS`; REST responses serialize with `WRITE_BIGDECIMAL_AS_PLAIN` so clients never see `6.743215E4`.

**Alternatives considered.**
- *`double` / `DOUBLE PRECISION`.* Rejected: precision loss on every deserialization. Aggregations accumulate error.
- *`long` with fixed-point encoding (e.g. cents × 10⁸).* Rejected for this project: a defensible choice for a real HFT path — no GC, no allocation — but overkill at 50 msg/s and needlessly obscures the code.

**Consequences.** `BigDecimal` math is slower than `double` and produces short-lived allocations. At our rate (~50 msg/s, burst 500) this is invisible in profiles. Comparisons must use `compareTo`, not `equals` (which distinguishes `"1.0"` from `"1.00"`). Documented in the code where comparisons live. The `QuoteRoundTripTest` explicitly guarantees that precision loss is structurally prevented across the JSON → DB → JSON lifecycle.

---

## DD-3 — In-memory `ConcurrentHashMap` for latest-quote reads

**Context.** The spec demands low latency for "get the most recent quote". Database reads have GC, lock, and disk variance; even a warm PostgreSQL index lookup is hundreds of microseconds on a good day.

**Decision.** `QuoteService` maintains a `ConcurrentHashMap<String, Quote>` keyed by symbol. REST reads are served from the map. The DB is write-only from the application's perspective.

**Alternatives considered.**
- *Read from PostgreSQL with an index on `(symbol, event_time DESC)`.* Rejected for the hot path: correctness equivalent but latency tail is unpredictable under write load or autovacuum.
- *Redis / in-process cache library (Caffeine).* Rejected: 10 entries do not justify a cache library, and a Redis dependency doubles the ops surface.
- *`AtomicReference<Map>` with copy-on-write.* Rejected: `ConcurrentHashMap` already gives us lock-striped writes and wait-free reads; CoW would help only if reads vastly outnumbered writes, which they do not.

**Consequences.** Reads are O(1) and sub-microsecond in practice. State is lost on restart, but the WebSocket repopulates all 10 symbols within seconds and the DB has the full history. We explicitly do **not** hydrate the map from the DB on boot — the freshness delta would be meaningless versus just waiting for the next `bookTicker` frame.

---

## DD-4 — OkHttp for the WebSocket client (over `java.net.http.WebSocket`)

**Context.** Java 21 ships a built-in WebSocket client. OkHttp is an additional dependency.

**Decision.** Use OkHttp 4.12.x. Worth the dependency.

**Alternatives considered.**
- *`java.net.http.WebSocket` (JDK).* Rejected for a 1-day build: the API is correct but low-level — ping/pong, reconnection, and partial-frame aggregation are all manual. OkHttp handles all three out of the box.
- *Spring's `WebSocketClient` (`StandardWebSocketClient` / `ReactorNettyWebSocketClient`).* Rejected: Spring's client-side WebSocket support is an afterthought. OkHttp's API is narrower and more battle-tested.
- *Netty directly.* Rejected: too low-level for a one-day project.

**Consequences.** +1 dependency (~800 KB). We accept this in exchange for a ~50-line `WebSocketListener` subclass handling the entire lifecycle. Swapping to the JDK client later is localized to `BinanceWebSocketClient`.

---

## DD-5 — PostgreSQL (over TimescaleDB, QuestDB, ClickHouse)

**Context.** The spec requires persisting the complete time series. At 10 symbols × ~2–5 updates/s, we expect ~20–50 rows/s sustained, with bursts to ~500/s during volatility.

**Decision.** PostgreSQL 16 with a single table, a composite index on `(symbol, event_time DESC)`, and `UNIQUE(symbol, update_id)` for natural dedup.

**Alternatives considered.**
- *TimescaleDB.* Rejected: hypertables shine at 10⁴–10⁶ rows/s. At our rate the extension adds operational complexity for no observable benefit.
- *QuestDB.* Rejected: excellent at ingest, but niche — some interviewers will not have encountered it, and the signal we want to send is competence, not novelty.
- *ClickHouse.* Rejected: columnar store optimized for analytical reads, which this project does not have.
- *SQLite.* Rejected: concurrent-writer bottleneck and signals minimal effort.

**Consequences.** PostgreSQL is the default every reviewer understands. We're operating at ~1% of its batch-insert headroom, so there is ample room to grow. If this ever became a real ingest pipeline at 10⁴+ msg/s, DD-5 would be revisited in favor of a TSDB.

---

## DD-6 — Drop-oldest backpressure (not block, not drop-newest)

**Context.** The bounded persistence queue (10 000 slots) can fill if PostgreSQL stalls. Three backpressure policies are standard: block the producer, drop the newest arrival, drop the oldest queued item.

**Decision.** Drop oldest when queue depth exceeds 90%. Log a `WARN` with the count dropped per flush window. Never block the WebSocket thread.

**Alternatives considered.**
- *Block the producer (`put` instead of `offer`).* Rejected: would stall the WebSocket reader thread, causing Binance to disconnect us for slow consumption and inflating freshness lag.
- *Drop newest arrival.* Rejected: under backpressure, the newest data is the most valuable (it reflects current market state). Dropping the newest means the in-memory map and the DB diverge on *the most recent* quotes — the wrong direction.
- *Spill to disk.* Rejected: complexity does not justify the benefit at this scale.

**Consequences.** Under sustained DB outage we lose the oldest in-flight data. This is an explicit deviation from the spec requirement to "persist the *complete* time-series history" — under pathological backpressure, the drop-oldest policy discards the oldest buffered quotes before they reach PostgreSQL. This trade-off is acceptable because (a) the in-memory map continues serving the *latest* to REST clients unaffected, (b) the DB regains consistency on every newer write, and (c) bursts of real pathological load at this scale are minutes long at worst — not hours. The cumulative drop count is tracked by the `binance.quotes.dropped.total` Micrometer counter and exposed in the `persistenceQueue` health indicator details.

---

## DD-7 — Natural dedup key `(symbol, update_id)`

**Context.** Binance `bookTicker` messages include `u` — the orderbook update id, monotonically increasing per symbol. On WebSocket reconnects, Binance may re-deliver the last several messages. Without dedup, we'd get duplicate rows in the history table.

**Decision.** Declare `UNIQUE(symbol, update_id)` in the schema. Write path uses `INSERT ... ON CONFLICT (symbol, update_id) DO NOTHING`.

**Alternatives considered.**
- *Accept duplicates* (original plan). Rejected: harmless for correctness, but leaves the history table with meaningless repeated rows at reconnect boundaries — visible to any analyst.
- *Application-level dedup with a seen-set.* Rejected: cost is an unbounded set, or a bounded one with eviction logic. The database already has the perfect data structure.
- *Separate dedup via `id = hash(symbol, update_id)`.* Rejected: an explicit UNIQUE constraint is more idiomatic and the query planner uses it.

**Consequences.** One extra index. One extra clause in the INSERT. `update_id` becomes part of the `Quote` record. The replay-after-reconnect failure mode (FM-5) upgrades from "Partial" to "Yes".

---

## DD-8 — `spring.threads.virtual.enabled=true`, plus one explicit platform thread for the batch drainer

**Context.** Java 21 virtual threads remove the need to size thread pools for I/O-bound work. Spring Boot 3.2+ integrates this at the framework level.

**Decision.** Set `spring.threads.virtual.enabled=true` in `application.yml`. This makes Tomcat request handlers and `@Async` / auto-configured `TaskExecutor` beans use virtual threads. Separately, the batch-persistence drainer is started explicitly as `Thread.ofPlatform().name("quote-batch-writer").start(...)` from `@PostConstruct`, joined from `@PreDestroy`. A platform thread is used for the drainer because it is a single, long-lived, dedicated loop — virtual threads provide no benefit here (see TODO item "Fix Virtual Thread Misuse on Drainer").

**Alternatives considered.**
- *Manually create virtual threads everywhere.* Rejected: not idiomatic on Spring Boot 3.2+.
- *Rely only on the property.* Rejected: the property covers request handlers and executor-backed tasks, not a dedicated, long-running drainer loop owned by one service. That loop is simpler as an explicit thread — no need to route it through an executor.

**Consequences.** REST requests and any future `@Async` call get virtual threads for free via the Spring property. The drainer uses a platform thread, which is simpler and more appropriate for a single long-lived loop. Stack traces name the drainer (`quote-batch-writer`) so it's greppable in `jcmd Thread.print`.

---

## DD-9 — Targeted combined stream, not `!bookTicker` firehose

**Context.** Binance exposes two subscription shapes. `!bookTicker` is the all-symbols firehose (hundreds of pairs, thousands of messages per second). The combined stream `wss://fstream.binance.com/stream?streams=btcusdt@bookTicker/ethusdt@bookTicker/...` delivers exactly the symbols requested. (Base URL is `fstream` — USDT-M Perpetuals — rather than `stream` — SPOT — per DD-10.)

**Decision.** Targeted combined stream for exactly the 10 configured symbols.

**Alternatives considered.**
- *`!bookTicker` with application-side filtering.* Rejected: 99% of ingress bytes would be discarded. Burns bandwidth, burns parser CPU, raises GC pressure, and would pointlessly fire our own reconnect logic on every momentary blip in the firehose.
- *One WebSocket connection per symbol.* Rejected: 10 connections where 1 suffices is wasteful, and Binance rate-limits new connections.

**Consequences.** The URL is built from the configured symbol list at startup and logged once so a reviewer can eyeball it. The inbound JSON is wrapped as `{"stream":"btcusdt@bookTicker","data":{...}}`, which the parser must unwrap.

---

## DD-10 — USDT-M Perpetuals, not SPOT

**Context.** The spec says "SPOT **or** Perpetuals". The choice turns out to be load-bearing for the observability story — the two `@bookTicker` payload shapes differ. Verified against Binance's own documentation:

- SPOT `<symbol>@bookTicker`: `{u, s, b, B, a, A}` — update id, symbol, best bid/ask price and size. **No event time. No transaction time.**
- USDT-M Perpetuals `<symbol>@bookTicker`: `{e, u, E, T, s, b, B, a, A}` — adds event type (`e`), event time (`E`, ms), and matching-engine transaction time (`T`, ms).

**Decision.** USDT-M Perpetuals. Base URL `wss://fstream.binance.com`.

**Alternatives considered.**
- *SPOT with `receivedAt` (local-clock) as the lag source.* Rejected: `now − receivedAt` measures only our internal processing (always ≈ 0 at ingestion). It cannot detect Binance-side delay, network delay, or stale mock feeds in tests. Drops the freshness-lag SLO from a real signal to a vanity metric.
- *SPOT with `@depth` stream.* Rejected: `@depth` carries `E`, but its payload is an L2 delta (or snapshot) rather than top-of-book, so we would have to derive best bid/ask from depth ourselves. More code, different semantics, no upside at this scale.
- *Binance US perps.* Binance US does not offer derivatives; N/A.

**Consequences.**
- The `binance.quote.lag.millis` gauge and the "freshness lag p99 < 500 ms" SLO become real, measurable signals.
- `Quote` record carries both `eventTime` (`E`) and `transactionTime` (`T`). The primary lag gauge uses `E` (Binance-to-us, the canonical observability number); `T` is retained in the record for downstream use.
- Schema gains a `transaction_time` column.
- Ticker names are identical (`BTCUSDT` etc.); on `fstream.binance.com` they resolve to the perpetual contract, not the spot pair.
- Perp liquidity is typically higher than spot for the top names, making the test feed more active.

---

## DD-11 — Lag gauge: per-symbol tagged + one fleet-max

**Context.** `binance.quote.lag.millis` exists to answer two different questions:
1. "Is any symbol lagging?" — needs a single number for health checks and shutdown decisions.
2. "Which symbol is lagging?" — needs per-symbol visibility for debugging.

**Decision.** Two Micrometer gauges, backed by the same `ConcurrentHashMap<String, AtomicLong>` keyed by symbol:
- `binance.quote.lag.millis{symbol="BTCUSDT"}` — one registration per symbol at startup, each bound to `lagBySymbol.get(symbol)`.
- `binance.quote.lag.max.millis` — untagged, computes `max(lagBySymbol.values())` on each scrape.

**Alternatives considered.**
- *Single tagged gauge, let Prometheus compute max.* Rejected: we want the max available via `/actuator/metrics/...` without requiring a scrape aggregator; also used by `BinanceStreamHealthIndicator`.
- *One untagged gauge exposing only the fleet-max.* Rejected: loses per-symbol debuggability.
- *DistributionSummary with per-scrape snapshot.* Rejected: overkill for a point-in-time lag signal; gauges are the right primitive.

**Consequences.** Ten `Gauge` registrations plus one. `HealthIndicator` reads the max gauge directly. Per-symbol investigations use the tag filter. Zero allocation on the hot path — the WS thread does `lagBySymbol.get(symbol).set(now − E)`.

---

## DD-12 — No separate `QuoteDto`; serialize `Quote` directly

**Context.** The original plan listed a `QuoteDto` for REST responses distinct from the internal `Quote`. DTOs make sense when the external shape must differ from the internal shape — for example, to hide an internal id or to remap field names.

**Decision.** Drop `QuoteDto`. The REST endpoint returns `Quote` records directly. Jackson serializes the record via its accessor methods; `WRITE_BIGDECIMAL_AS_PLAIN` handles number formatting.

**Alternatives considered.**
- *Keep `QuoteDto` for "future-proofing".* Rejected: adds a mapping step and a maintenance burden for a shape that is currently identical.
- *Use `@JsonView` to hide `receivedAt`.* Rejected: `receivedAt` is useful to clients (debugging feed health client-side). Expose it.

**Consequences.** One fewer class and one fewer mapping call per request. If the API shape ever needs to diverge from the internal record (e.g. we add an internal-only persistence-status field), the DTO gets reintroduced at that point.

---

## DD-13 — Quote-level data integrity validation in parser + schema CHECK constraints

**Context.** Binance is reliable, but any financial system must treat external data as untrusted. A zero price, a crossed spread (bid > ask), a negative size, or a timestamp in the far future indicates either a corrupt message or a parser bug. Silently persisting corrupt data is worse than dropping it — it poisons the historical record and any downstream analytics.

**Decision.** Two layers of defense:
1. `QuoteMessageParser` validates business invariants before constructing a `Quote`: `bid > 0`, `ask > 0`, `bidSize >= 0`, `askSize >= 0`, `bid <= ask`, `eventTime > 0`, `eventTime` not in the far future (within 1 hour). Invalid messages are logged at `WARN` and skipped (return `Optional.empty()`).
2. `schema.sql` adds CHECK constraints as a backstop: `bid_price > 0`, `ask_price > 0`, `bid_size >= 0`, `ask_size >= 0`. Even if application validation has a bug, the DB rejects corrupt rows.

**Alternatives considered.**
- *Validate only at the schema level.* Rejected: INSERT failures are expensive (network round-trip, retry, log noise). Catching corrupt data at parse time is cheaper and produces clearer diagnostics.
- *No validation — trust Binance.* Rejected: signals financial naivety. Every production trading system validates inbound market data.
- *Reject zero sizes.* Not chosen: a zero bid/ask size is valid in some markets (one side is empty). We allow it but require non-negative.

**Consequences.** The parser returns `Optional.empty()` for corrupt messages, consistent with the existing `FM-3` handling of malformed JSON. Adds ~10 lines of validation logic. Schema CHECK constraints are enforced on every INSERT; `ON CONFLICT DO NOTHING` rows bypass them (they're never inserted), so there's no performance concern.

---

## Decisions NOT made (explicit deferrals)

- **Hydrating the in-memory map from the DB at boot.** Worth the complexity only if startup freshness matters more than waiting a few seconds for the WS. It doesn't, here.
- **Per-symbol dedicated drainer threads.** One drainer scales fine to ~10⁴ msg/s. Revisit if sustained rate grows 100×.
- **Metrics backend (Prometheus/OTel).** Actuator + the `binance.quote.lag.millis` gauge is enough signal for an interview project; production would add an exporter.
- **Schema migrations (Flyway/Liquibase).** A single flat `schema.sql` is sufficient for one-day scope.

---

## DD-14 — Semaphore-based concurrency limiting on REST endpoints

**Context.** The spec does not mandate rate limiting, but an unbounded API can be trivially DoSed by a misbehaving client. At the same time, the service is single-node and low-traffic (10 instruments, internal consumers), so a full token-bucket implementation (Bucket4j) is overkill.

**Decision.** Add a `OncePerRequestFilter` backed by a `java.util.concurrent.Semaphore` with configurable permits (default 100). Requests to `/api/**` paths must acquire a permit before processing; if none is available within a timeout (default 500 ms), a 429 is returned. Non-API paths (actuator, etc.) bypass the filter entirely.

**Alternatives considered.**
- *Bucket4j token-bucket.* Rejected: adds a dependency for a problem that doesn't exist at this scale. Semaphore-based concurrency control is sufficient and zero-dependency.
- *No rate limiting.* Acceptable for the original spec but noted as a gap by reviewers. Adding it demonstrates production-mindedness.

**Consequences.** At most 100 concurrent API requests; others get 429. This is generous for 10 instruments and internal consumers. The semaphore is non-fair (the default); for 10 instruments with internal consumers, starvation is not a practical concern. With virtual threads enabled (`spring.threads.virtual.enabled=true`), `tryAcquire` blocks a virtual thread (cheap). The timeout (default 500 ms) bounds the wait.

---

## DD-15 — Historical quote query endpoint

**Context.** The original spec requires only the latest quote per instrument. The persistence layer (PostgreSQL with a composite index on `(symbol, event_time DESC)`) is naturally suited for time-range queries, but no endpoint exposes this capability.

**Decision.** Add `GET /api/quotes/{symbol}/history?from=X&to=Y` that queries PostgreSQL directly using `NamedParameterJdbcTemplate` with named parameters. Returns up to 1000 results ordered by `event_time DESC`. Uses the same `Quote` record for serialization (DD-12). Out of scope per original spec but noted as a future enhancement; implemented to demonstrate the persistence layer is queryable.

**Alternatives considered.**
- *No historical endpoint.* The original spec doesn't require it, but reviewers noted the absence.
- *Streaming/cursor-based pagination.* Rejected for scope: `LIMIT 1000` is sufficient for an interview project.

**Consequences.** This is the only REST endpoint that hits the database. The composite index `idx_quotes_symbol_time` makes the query efficient. No DTO layer — reuses the `Quote` record (DD-12). The endpoint validates the symbol against the configured allowlist, consistent with the latest-quote endpoint.
