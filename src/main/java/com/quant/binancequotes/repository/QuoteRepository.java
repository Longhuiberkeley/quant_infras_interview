package com.quant.binancequotes.repository;

import com.quant.binancequotes.model.Quote;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Thin repository wrapping {@link NamedParameterJdbcTemplate} for batch inserts into the {@code
 * quotes} table.
 *
 * <p>Uses {@code ON CONFLICT (symbol, update_id) DO NOTHING} so that replay-after-reconnect
 * duplicates are silently discarded without raising an error. {@code JdbcClient} has no batch API,
 * so we use {@code NamedParameterJdbcTemplate} directly. See {@code docs/design_decisions.md} DD-1
 * (no JPA) and DD-3 (write-only persistence).
 */
@Repository
public class QuoteRepository {

  private static final String SQL =
      """
      INSERT INTO quotes (symbol, bid_price, bid_size, ask_price, ask_size,
                          update_id, event_time, transaction_time, received_at)
      VALUES (:symbol, :bid, :bidSize, :ask, :askSize,
              :updateId, :eventTime, :transactionTime, :receivedAt)
      ON CONFLICT (symbol, update_id) DO NOTHING
      """;

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public QuoteRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Batch-inserts a list of quotes. Duplicates (same symbol + updateId) are silently ignored via
   * {@code ON CONFLICT DO NOTHING}. This method is the sole write-path into the database.
   *
   * @param quotes non-null list of quotes to persist
   * @return number of rows actually inserted (may be less than {@code quotes.size()} due to
   *     conflicts)
   */
  public int batchInsert(List<Quote> quotes) {
    if (quotes.isEmpty()) {
      return 0;
    }
    SqlParameterSource[] batch =
        quotes.stream()
            .map(
                q ->
                    new MapSqlParameterSource(
                        Map.of(
                            "symbol", q.symbol(),
                            "bid", q.bid(),
                            "bidSize", q.bidSize(),
                            "ask", q.ask(),
                            "askSize", q.askSize(),
                            "updateId", q.updateId(),
                            "eventTime", q.eventTime(),
                            "transactionTime", q.transactionTime(),
                            "receivedAt", Timestamp.from(q.receivedAt()))))
            .toArray(SqlParameterSource[]::new);

    int[] results = jdbcTemplate.batchUpdate(SQL, batch);
    int inserted = 0;
    for (int r : results) {
      if (r > 0) {
        inserted += r;
      }
    }
    return inserted;
  }
}
