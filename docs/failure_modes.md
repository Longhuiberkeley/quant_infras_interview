# Failure Modes & Mitigations

This document catalogs every failure scenario we identified, our mitigation strategy, and whether the mitigation is implemented or documented as a known trade-off.

## Philosophy

For a 1-day interview project, we cannot implement every possible resilience pattern. Instead we:
1. **Implement** the critical ones that directly affect correctness and uptime.
2. **Document** the rest with clear rationale, showing we've thought about them.

This signals engineering maturity without over-engineering.

---

## Failure Modes

### FM-1: WebSocket Disconnect

| Field | Detail |
|-------|--------|
| **Trigger** | Network interruption, Binance server restart, idle timeout |
| **Impact** | No new quotes received; stale data served from in-memory map |
| **Mitigation** | Exponential backoff reconnect (1s → 2s → 4s → … → 60s cap) |
| **Implemented?** | **Yes** — in `BinanceWebSocketClient.onFailure()` and `onClosed()` |
| **Verification** | Unit test simulates disconnect, asserts reconnect is scheduled |

### FM-2: Database Temporarily Unavailable

| Field | Detail |
|-------|--------|
| **Trigger** | PostgreSQL restart, network partition, connection pool exhaustion |
| **Impact** | Quotes cannot be persisted; in-memory reads still work |
| **Mitigation** | `LinkedBlockingQueue` (capacity 10,000) absorbs burst; batch writer retries with exponential backoff |
| **Implemented?** | **Yes** — bounded queue + retry logic in `BatchPersistenceService` |
| **Verification** | Integration test stops PostgreSQL container, sends quotes, restarts DB, asserts data is eventually persisted |
| **Capacity** | At ~50 msgs/sec, the queue buffers ~3.3 minutes of data |

### FM-3: Malformed JSON Message

| Field | Detail |
|-------|--------|
| **Trigger** | Binance sends an unexpected message format (e.g., connection upgrade frame, error payload) |
| **Impact** | `JsonProcessingException` could crash the handler |
| **Mitigation** | Try-catch around deserialization; log the raw message and skip |
| **Implemented?** | **Yes** — in `QuoteMessageParser.parse()` |
| **Verification** | Unit test passes malformed JSON, asserts no exception thrown |

### FM-4: High Message Burst (Backpressure)

| Field | Detail |
|-------|--------|
| **Trigger** | Volatile market, Binance sends rapid-fire updates (e.g., 500+ msgs/sec during a flash crash) |
| **Impact** | Queue fills up; new messages would block the WebSocket thread |
| **Mitigation** | `LinkedBlockingQueue.offer()` (non-blocking) with a drop policy: log a warning and discard the oldest quotes when queue is >90% full |
| **Implemented?** | **Yes** — `offer()` with size check in `QuoteService` |
| **Verification** | Unit test floods the queue, asserts WebSocket thread is never blocked |

### FM-5: Duplicate Messages

| Field | Detail |
|-------|--------|
| **Trigger** | WebSocket reconnect may re-deliver the last few messages |
| **Impact** | Would cause duplicate rows in PostgreSQL |
| **Mitigation** | Schema declares `UNIQUE(symbol, update_id)`; write path uses `INSERT ... ON CONFLICT (symbol, update_id) DO NOTHING`. Binance's per-symbol `u` (orderbook update id) is the natural dedup key. See `design_decisions.md` DD-7. The in-memory map always holds the latest (key = symbol), so reads are unaffected. |
| **Implemented?** | **Yes** — at the schema level; zero application-side dedup logic required. |
| **Verification** | `QuoteRepositoryIntegrationTest#duplicateUpdateId_isIgnored` |

### FM-6: JVM Crash / OOM

| Field | Detail |
|-------|--------|
| **Trigger** | Memory leak, unbounded data structure growth |
| **Impact** | Service dies; data in queue (max ~10K quotes) is lost |
| **Mitigation** | Bounded queue limits max memory. JVM `-Xmx256m` sets a hard ceiling. DB data persists across restarts. |
| **Implemented?** | **Documented** — `-Xmx` flag in README run instructions |
| **Trade-off** | Loss of in-flight queue data on crash is acceptable. Max data loss = ~3 minutes of quotes. |

### FM-7: Clock Skew

| Field | Detail |
|-------|--------|
| **Trigger** | System clock drift on the host machine |
| **Impact** | Inaccurate `receivedTimestamp` for queries |
| **Mitigation** | Use Binance's `eventTime` (exchange-provided timestamp) as the source of truth. Store `receivedAt` (local clock) separately for debugging only. |
| **Implemented?** | **Yes** — `Quote` POJO has both `eventTime` (Binance) and `receivedAt` (local) |
| **Verification** | Unit test verifies `eventTime` is used for all business logic |

### FM-8: Slow Consumer (REST API)

| Field | Detail |
|-------|--------|
| **Trigger** | A client makes a heavy concurrent request to `/api/quotes` |
| **Impact** | Tomcat thread pool exhaustion |
| **Mitigation** | In-memory map reads are O(1) and non-blocking. Tomcat default thread pool (200 threads) is more than sufficient. |
| **Implemented?** | **Documented** — acceptable at this scale |
| **Future enhancement** | Rate limiting via Spring interceptors |

### FM-9: WebSocket Connection Refused (VPN / Firewall)

| Field | Detail |
|-------|--------|
| **Trigger** | Developer's network blocks outbound WebSocket connections |
| **Impact** | Cannot connect to Binance at all |
| **Mitigation** | OkHttp supports HTTP/SOCKS proxy via `OkHttpClient.Builder.proxy()`. Document proxy configuration in README. |
| **Implemented?** | **Documented** — README includes proxy configuration instructions |
| **Note** | This is a dev-environment issue, not a production concern |

### FM-10: Spring Boot Application Fails to Start

| Field | Detail |
|-------|--------|
| **Trigger** | Missing DB, wrong Java version, port conflict |
| **Impact** | Service doesn't start |
| **Mitigation** | Spring's `dev` profile uses H2 in-memory DB as a zero-config fallback. Health check endpoint (`/actuator/health`) for Docker. |
| **Implemented?** | **Yes** — `application-dev.yml` configures H2 |
| **Verification** | Manual: `mvn spring-boot:run -Dspring.profiles.active=dev` |

### FM-12: Corrupt Market Data (Crossed Spread / Zero Price / Invalid Timestamp)

| Field | Detail |
|-------|--------|
| **Trigger** | Binance sends a malformed quote (zero price, crossed spread, future timestamp), or a parser bug misinterprets fields |
| **Impact** | Invalid data persisted to DB; downstream consumers see impossible market state |
| **Mitigation** | `QuoteMessageParser` validates business invariants before constructing `Quote` (DD-13). Schema CHECK constraints provide a DB-level backstop. |
| **Implemented?** | **Yes** — parser validation + schema CHECK constraints |
| **Verification** | `QuoteMessageParserTest#crossedSpread_returnsEmpty`, `QuoteMessageParserTest#zeroPrice_returnsEmpty` |

---

## Summary Matrix

| ID | Failure | Implemented | Category |
|----|---------|-------------|----------|
| FM-1 | WebSocket disconnect | **Yes** | Network |
| FM-2 | DB unavailable | **Yes** | Infrastructure |
| FM-3 | Malformed JSON | **Yes** | Data |
| FM-4 | Message burst / backpressure | **Yes** | Load |
| FM-5 | Duplicate messages | **Yes** | Data |
| FM-6 | JVM crash / OOM | Documented | Infrastructure |
| FM-7 | Clock skew | **Yes** | Data |
| FM-8 | Slow consumer | Documented | Load |
| FM-9 | VPN / firewall | Documented | Network |
| FM-10 | Startup failure | **Yes** | Infrastructure |
| FM-12 | Corrupt market data | **Yes** | Data |
