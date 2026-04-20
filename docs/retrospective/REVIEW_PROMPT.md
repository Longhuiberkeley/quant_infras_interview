# Big Review Prompt

You are a senior code reviewer. Two development agents have completed work on a Java/Spring Boot project — a Binance quote streaming service. Your job is to perform a thorough, independent review of all 14 `[dev-done]` items listed in `docs/retrospective/TODO.md`, verifying each one against the actual source code.

**Start here:** Read these documents in order:
1. `docs/retrospective/TODO.md` — the full task list (focus on `[dev-done]` items)
2. `docs/retrospective/TODO_VERIFICATION.md` — acceptance criteria and claimed evidence for each item
3. `CLAUDE.md` — project conventions and architecture invariants (important: check for violations)

## What you are reviewing

There are 14 `[dev-done]` items across two "batches" of work. The first batch was done by a previous agent (items marked `[dev-done]` with verification sections numbered 24-25 in TODO_VERIFICATION.md). The second batch was done by the current agent (items numbered 21-22, 26-33 in TODO_VERIFICATION.md).

### Batch A — Previous agent's work (2 items, verification sections 24-25):
- **Item 24:** Address Strict Monotonic Upsert — `QuoteService.update` uses strict `>` and `merge` instead of `>=` and `compute`
- **Item 25:** AppProperties Validation Consolidation — redundant `@PostConstruct` removed, full JSR-303 used

### Batch B — Current agent's work (12 items, verification sections 21-22, 26-33):
- **Item 21:** Fix Virtual Thread Misuse on Drainer — `Thread.ofVirtual()` → `Thread.ofPlatform()`
- **Item 22:** Move Drainer Thread Start to `@PostConstruct` — thread start moved from constructor to `@PostConstruct` method (NOTE: marked **partial** — AC2 says field should be `final` but that's incompatible with `@PostConstruct`; evaluate whether this is acceptable)
- **Item 26:** Document "Drop Oldest" Spec Deviation + Add Counter — `binance.quotes.dropped.total` Micrometer gauge + DD-6 spec deviation docs
- **Item 27:** Add Missing Schema CHECK Constraints — `event_time > 0`, `update_id > 0`, `bid_price <= ask_price` in `schema.sql`
- **Item 28:** Fix `SymbolNotFoundException` Javadoc — "Checked exception" → "Runtime exception"
- **Item 29:** Remove No-op `@JsonIgnore` on `Quote.lagMillis()` — annotation + import removed
- **Item 30:** Fix `QuoteServicePerformanceTest` p99 Off-by-One — `Math.ceil` based indexing
- **Item 31:** Rename `IngestLagTest` to `LagGaugeTest` — file/class rename, javadoc clarified
- **Item 32:** Pin Java 21 in README — "Java 21+" → "Java 21 (required; not yet compatible with JDK 25+)"
- **Item 33:** Document Spotless Gate in README — formatting note added to Quick Start

## Review methodology

For each item, you MUST:

1. **Read the actual source code** referenced in TODO_VERIFICATION.md. Do not trust the claims — verify them yourself.
2. **Check every acceptance criterion** individually. For each AC, report whether the code actually satisfies it.
3. **Check for side-effects**: Did the change break anything? Were tests updated? Are imports clean?
4. **Check line number accuracy**: TODO_VERIFICATION.md has a history of stale line numbers. Note any discrepancies.
5. **Check for convention violations**: Cross-reference with `CLAUDE.md` conventions. In particular:
   - No JPA
   - No `double`/`float` for money
   - No firehose WebSocket subscription
   - No blocking on WebSocket thread
   - No parameter interpolation in SQL
   - Reads never hit DB
   - No `@Disabled` tests

## Specific things to watch for

1. **BatchPersistenceService changes** (items 21, 22, 26): Three changes touched this file. Verify they're coherent together:
   - Constructor now takes `MeterRegistry` as 3rd parameter — are ALL callers updated?
   - `@PostConstruct startDrainer()` is package-private — can tests call it? (They're in the same package, so yes — verify.)
   - The `binance.quotes.dropped.total` is registered as a `gauge` (not a `counter`) backed by `AtomicLong::get`. Is this correct? A gauge reads the current value; a counter increments. Since `droppedCount` is an `AtomicLong` that only grows, a gauge reading its `.get()` is functionally correct but semantically different from a `Counter`. Is this acceptable?

2. **schema.sql** (item 27): New CHECK constraints were added. Does `schema.sql` still work with H2 in PostgreSQL mode? The dev profile uses `MERGE INTO` for inserts but still runs the same `schema.sql`. Verify the CHECK constraint syntax is H2-compatible.

3. **QuoteService.java** (item 24): The code uses `>` but check if there are any remaining javadoc references to `>=` or `compute` (the previous review found these were stale).

4. **LagGaugeTest rename** (item 31): Verify the old `IngestLagTest.java` file is actually deleted and no references to it remain anywhere.

## Build verification

After your code review, run:
```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
mvn verify -DskipITs
```
Report the test count and build status. Note: there are 2 skipped Docker smoke tests — that's expected.

## Output format

For each item, produce a table:

```
### Item NN: <title>
**Verdict:** PASS / PARTIAL / FAIL
| AC | Criterion | Verified? | Notes |
```

Then produce a summary:

```
## Summary
| Batch | Items | Pass | Partial | Fail |
|-------|-------|------|---------|------|
| A (previous agent) | 2 | ? | ? | ? |
| B (current agent) | 12 | ? | ? | ? |

### Issues found
- [list any issues]

### Recommendations
- [list any follow-up actions]
```

**IMPORTANT:** Be ruthless. A previous review found that items 21 and 22 were marked "pass" when the code had never been changed. Do not let that happen again. If something is wrong, say so.
