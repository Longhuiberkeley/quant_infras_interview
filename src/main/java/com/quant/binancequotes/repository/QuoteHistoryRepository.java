package com.quant.binancequotes.repository;

import com.quant.binancequotes.model.Quote;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QuoteHistoryRepository {

  private static final String HISTORY_SQL =
      "SELECT symbol, bid_price, bid_size, ask_price, ask_size, "
          + "update_id, event_time, transaction_time, received_at "
          + "FROM quotes "
          + "WHERE symbol = :symbol AND event_time >= :from AND event_time <= :to "
          + "ORDER BY event_time DESC LIMIT 1000";

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public QuoteHistoryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<Quote> findHistory(String symbol, long from, long to) {
    return jdbcTemplate.query(
        HISTORY_SQL,
        Map.of("symbol", symbol, "from", from, "to", to),
        (rs, rowNum) ->
            new Quote(
                rs.getString("symbol"),
                rs.getBigDecimal("bid_price"),
                rs.getBigDecimal("bid_size"),
                rs.getBigDecimal("ask_price"),
                rs.getBigDecimal("ask_size"),
                rs.getLong("update_id"),
                rs.getLong("event_time"),
                rs.getLong("transaction_time"),
                rs.getLong("received_at")));
  }
}
