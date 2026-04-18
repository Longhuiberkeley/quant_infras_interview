# CLAUDE.md

Guidance for Claude Code sessions working in this repository.

## What this project is

A 1-day Java take-home: a Spring Boot service that ingests real-time quotes for 10 Binance **USDT-M Perpetuals** over WebSocket, persists full history to PostgreSQL, and serves the latest quote per instrument over REST with sub-millisecond read latency. Perps (not SPOT) because SPOT `@bookTicker` omits `E` and `T` — see `docs/design_decisions.md` DD-10.

Full spec: `docs/interviewer_requirements.md`.

## Read these first

- `docs/architecture.md` — the system in one diagram.
- `docs/design_decisions.md` — why each non-obvious choice was made (ADR-style).
- `docs/failure_modes.md` — what breaks at runtime and how we mitigate.
- `docs/requirement_traceability.md` — requirement → code → test map, including the NFR (FM-*) matrix.
- `docs/implementation_plan.md` — the phased build plan the commit history follows.

If a decision in the code looks surprising, check `design_decisions.md` before changing it.

## Prerequisites

Java 21 and Maven 3.9+ must be on `$PATH`. This project uses **SDKMAN!** for toolchain management. If `mvn` or `java` is not found, initialise SDKMAN first:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

Installed versions: `21.0.6-tem` (Java), `3.9.14` (Maven).

## Stack

- Java 21 (records, virtual threads, pattern matching).
- Spring Boot 3.3.x (`spring-boot-starter-web`, `spring-boot-starter-jdbc`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`).
- PostgreSQL 16 via Docker Compose; H2 for `dev` profile.
- OkHttp 4.12.x for the WebSocket client.
- Jackson for JSON (configured for `BigDecimal`).
- JUnit 5, Mockito, Testcontainers, MockMvc, OkHttp MockWebServer for tests.
- Spotless + google-java-format 1.22+ for style.

## Commands

| Intent | Command |
|--------|---------|
| Build + format + test | `mvn verify` |
| Auto-format | `mvn spotless:apply` |
| Run against PostgreSQL (via compose) | `docker compose up` |
| Run against H2 only (no Docker) | `mvn spring-boot:run -Dspring-boot.run.profiles=dev` |
| Override port (default 18080) | `--server.port=18080` or `SERVER_PORT=...` |
| Unit tests only | `mvn test` |
| One test | `mvn test -Dtest=QuoteServiceTest` |
| Dep tree (check no JPA leaked in) | `mvn dependency:tree \| grep -i jpa` → should be empty |
| Live lag gauge | `curl -s localhost:8080/actuator/metrics/binance.quote.lag.millis` |

## Conventions (load-bearing — don't violate without updating `design_decisions.md`)

- **No JPA.** Write path uses `JdbcClient.batchUpdate`. `@GeneratedValue(IDENTITY)` + Hibernate silently breaks JDBC batching (DD-1). If you're tempted to add `spring-boot-starter-data-jpa`, read DD-1 first.
- **No `double` / `float` / `DOUBLE PRECISION` for money.** All monetary fields are `BigDecimal` / `NUMERIC(24,8)` (DD-2). Binance sends decimal strings precisely for this reason.
- **No firehose WebSocket subscription.** Never subscribe to `!bookTicker`. Build the targeted combined stream URL from the configured symbol list (DD-9).
- **No blocking on the WebSocket thread.** The persistence queue uses non-blocking `offer` with drop-oldest backpressure; blocking would cause Binance to disconnect us (DD-6).
- **No parameter interpolation in SQL.** Always `JdbcClient` named parameters.
- **Reads never hit the DB.** `QuoteService` serves the `ConcurrentHashMap` (DD-3). Persistence is write-only from the application's perspective.
- **No hydrating the map from DB at startup.** The WebSocket repopulates in seconds; see `design_decisions.md` §deferrals.
- **No `@Disabled` / `@Ignore`d tests.** Fix or delete.
- **Exactly 10 symbols, uppercase, USDT-quoted.** Validated at startup (`AppProperties`); the list is configurable but the shape is not.
- **No unvalidated market data.** The parser rejects quotes with zero/negative prices, crossed spreads (`bid > ask`), or implausible timestamps (DD-13). Schema CHECK constraints backstop this at the DB level.

## Architecture invariants

- `Quote` is an immutable `record` with `BigDecimal` monetary fields and both `eventTime` (Binance) and `receivedAt` (local).
- The batch drainer runs on a single platform thread named `quote-batch-writer`, started via `@PostConstruct`. `@PreDestroy` drains the queue with a bounded timeout.
- `UNIQUE(symbol, update_id)` + `ON CONFLICT DO NOTHING` handles replay-after-reconnect dedup.
- `BinanceStreamHealthIndicator` and `PersistenceQueueHealthIndicator` feed `/actuator/health`; orchestration relies on them.
- `binance.quote.lag.millis` (Micrometer gauge) = `now − eventTime`. It's the single number that tells you real-time correctness.

## Commit style

One commit per phase of `docs/implementation_plan.md`, conventional prefix (`feat:`, `chore:`, `test:`, `docs:`). Each commit should leave the app in a runnable, `mvn verify`-green state. No `wip` / `fix fix` noise.

## Things not to add without a reason

- ORMs, caching libraries, message brokers, Reactive Netty — none are justified at this scale.
- Schema migration tools (Flyway/Liquibase) — one flat `schema.sql` is enough here.
- Historical REST endpoints — the spec asks for latest only.
- Extra metrics backends — actuator is sufficient for an interview project.
- Reconnect triggered from `onClosing`. Reconnect lives in `onClosed` and `onFailure`, guarded by an atomic flag.
