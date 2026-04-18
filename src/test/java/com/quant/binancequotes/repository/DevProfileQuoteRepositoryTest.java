package com.quant.binancequotes.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.websocket.BinanceWebSocketClient;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("dev")
class DevProfileQuoteRepositoryTest {

  @MockBean private BinanceWebSocketClient binanceWebSocketClient;

  @Autowired private QuoteRepository quoteRepository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void batchInsertSucceedsWithH2MergeInto() {
    Quote quote1 =
        new Quote(
            "BTCUSDT",
            new BigDecimal("60000.00"),
            new BigDecimal("1.0"),
            new BigDecimal("60000.10"),
            new BigDecimal("2.0"),
            1L,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            System.currentTimeMillis());

    // Initially insert 1 row
    int inserted = quoteRepository.batchInsert(List.of(quote1));
    assertThat(inserted).isEqualTo(1);

    // Using MERGE INTO, identical upsert works seamlessly without blowing up H2
    int insertedAgain = quoteRepository.batchInsert(List.of(quote1));
    assertThat(insertedAgain).isEqualTo(1);

    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM quotes WHERE symbol = 'BTCUSDT'", Long.class);
    assertThat(count).isEqualTo(1L);
  }
}
