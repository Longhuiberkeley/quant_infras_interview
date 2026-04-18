package com.quant.binancequotes.service;

import com.quant.binancequotes.model.Quote;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory latest-quote store backed by a {@link ConcurrentHashMap}.
 *
 * <p>Reads never hit the database — this map is the source of truth for the REST API (see {@code
 * docs/design_decisions.md} DD-3). The WebSocket ingestion path calls {@link #update(Quote)} for
 * every valid frame; the REST controller calls {@link #get(String)} or {@link #all()}.
 *
 * <p><b>Monotonic-by-updateId:</b> {@link #update(Quote)} replaces an existing entry only when the
 * incoming {@code updateId} is strictly greater than the current one. This prevents stale frames
 * (e.g. delivered out of order after a reconnect) from overwriting newer data.
 */
@Service
public class QuoteService {

  private final ConcurrentHashMap<String, Quote> quotes = new ConcurrentHashMap<>();

  /**
   * Upsert a quote atomically using {@link ConcurrentHashMap#merge}.
   *
   * <p>Replaces the existing entry only if the new {@code updateId} is {@code >} the current one,
   * ensuring strict monotonic ordering per symbol. No {@code synchronized} blocks — {@code merge}
   * provides lock-striped atomicity.
   */
  public void update(Quote quote) {
    quotes.merge(
        quote.symbol(),
        quote,
        (existing, incoming) -> incoming.updateId() > existing.updateId() ? incoming : existing);
  }

  /** Returns the latest quote for the given symbol, or empty if none exists. */
  public Optional<Quote> get(String symbol) {
    return Optional.ofNullable(quotes.get(symbol));
  }

  /**
   * Returns an immutable snapshot of all quotes.
   *
   * <p>The returned map is a point-in-time copy; subsequent updates do not affect it.
   */
  public Map<String, Quote> all() {
    return Map.copyOf(quotes);
  }
}
