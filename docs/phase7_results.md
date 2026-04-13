# Phase 7 Execution Evidence

Per-phase evidence capture for Phase 7 of [`implementation_plan.md`](./implementation_plan.md).
Committed as a stub in the 7.1 commit and filled incrementally — each 7.* commit updates its own section.

See `implementation_plan.md` §3 "Phase 7 evidence capture" for the rationale and policy (what
to record vs. what to leave to pass/fail of `mvn verify`). Audit check P10a.10 in
[`audit_checklist.md`](./audit_checklist.md) verifies every 7.1–7.4 section is populated before
7.5 closes.

---

## 7.1 — Integration Happy Path

- **Commit:** `35a59d7`
- **Date:** 2026-04-13
- **`mvn clean verify`:** PASS (75 tests, 0 failures, 0 errors)

### Happy path assertions

| Sink | Expected | Observed |
|------|----------|----------|
| `QuoteService.all().size()` | 10 | 10 |
| `/api/quotes` response array length | 10 | 10 |
| `SELECT COUNT(*) FROM quotes` | ≥ 10 | 10 |

### `ingestLatencyUnder5ms` (SLO)

| Metric | Target | Observed |
|--------|--------|----------|
| p50 | — | 95.46 µs |
| p99 | < 5 ms | 566.08 µs |
| iterations | 1 000 | 1 000 |

### Reconnect after socket disconnect

- Reconnect-counter before disconnect: 1 (initial WS upgrade during `@PostConstruct`)
- Reconnect-counter after disconnect: 2 (Δ = 1 — a new HTTP upgrade request was issued)
- Map repopulation after reconnect: 10 / 10 symbols present with post-reconnect prices (bid > 55000, proving frames are from reconnect — not stale Test 1 data)
- Backoff observed: `onFailure` → `Scheduling reconnect in 2000 ms` → `Attempting reconnect…` → `WebSocket connected` (≈ 2 s from drop to reconnect; within 1→2→4…→60 s policy)

### Dev-profile boot

- Context loaded without PostgreSQL: PASS (`DevProfileBootTest.contextLoads` — 1 test, 0 failures. H2 via `application-dev.yml`; `binance.ws.base-url=ws://localhost:1` produces connection-refused on the WS client but does not fail context refresh.)

---

## 7.2 — Precision & Traceability

- **Commit:** _(pending)_
- **Date:** 2026-04-13
- **`mvn clean verify`:** PASS (78 tests, 0 failures, 0 errors)

### `BigDecimal` round-trip assertions

| Stage | Example value | `scale()` | `unscaledValue()` | Plain-decimal string preserved? |
|-------|---------------|-----------|-------------------|--------------------------------|
| Initial construction | `new BigDecimal("0.00000001")` | 8 | 1 | — |
| After Jackson serialize → deserialize | `new BigDecimal("67432.15000001")` | 8 | `6743215000001` | Yes |
| After DB INSERT → SELECT | `new BigDecimal("67432.15000001")` | 8 | `6743215000001` | Yes |
| After full chain (JSON → DB → JSON) | `new BigDecimal("67432.15000001")` | 8 | `6743215000001` | Yes |

### Traceability rename audit

- `rg "Test#" docs/requirement_traceability.md` → every method name resolves in `src/test/`: PASS
- `rg -n "duplicateUpdateId_isIgnored" docs/` → zero matches expected: PASS

---

## 7.3 — Performance & SLO Validation

- **Commit:** _(pending)_
- **Date:** _(pending)_
- **`mvn clean verify`:** _(pending)_

### SLO measurements

Numbers below are appended by `@AfterAll` hooks into `target/phase7-metrics.md` during the
test run; the reviewed block is copied here as part of the 7.3 commit.

| SLO | Target | p50 | p99 | Pass |
|-----|--------|-----|-----|------|
| QuoteService read (single-threaded) | < 1 ms | _(pending)_ | _(pending)_ | _(pending)_ |
| REST `GET /api/quotes` via MockMvc | < 5 ms | _(pending)_ | _(pending)_ | _(pending)_ |
| Freshness lag @ 500 msg/s | < 500 ms | _(pending)_ | _(pending)_ | _(pending)_ |

### Health indicator state transitions

| Indicator | `UP` case observed | `DOWN` case observed |
|-----------|--------------------|----------------------|
| `BinanceStreamHealthIndicator` | _(pending)_ | _(pending)_ |
| `PersistenceQueueHealthIndicator` | _(pending)_ | _(pending)_ |

---

## 7.4 — Resilience + Docker Smoke

- **Commit:** _(pending)_
- **Date:** _(pending)_
- **`mvn clean verify`:** _(pending)_

### Abrupt closure (code 1006)

- Reconnect CAS result: _(pending — expected `true`)_
- Backoff timer scheduled: _(pending — expected yes)_
- Graceful-shutdown path triggered: _(pending — expected no)_

### DB restart resilience

| Step | Row count |
|------|-----------|
| Insert 10 rows (PG running) | _(pending)_ |
| After `pg.stop()` + `pg.start()` | _(pending)_ |
| Insert 10 more rows | _(pending)_ |
| Final `SELECT COUNT(*)` | _(pending — expected 20)_ |

### `sustains500rps`

- Enqueued: 2 500 over 5 s
- Persisted within T seconds: _(pending)_ (T = _ s)
- Drop-oldest invocations during run: _(pending)_

### `producerNeverBlocks`

- Threads: 8
- `offer()` p50: _(pending)_
- `offer()` p99: _(pending — target < 50 ms)_

### Docker Compose smoke

- `docker compose up` healthy within 60 s: _(pending)_
- `/actuator/health` status: _(pending — expected `UP`)_
- `/api/quotes` HTTP code: _(pending — expected 200)_
- Skipped in CI? (`DOCKER_AVAILABLE` unset): _(pending)_

---

## 7.5 — Audit & Consistency Lock

See [`audit_results.md`](./audit_results.md) for the full pillar-by-pillar audit record.
This section only flags anything that required a fixup commit to resolve.

- Drift found and corrected: _(pending)_
- Fixup commit SHA (if any): _(pending)_
