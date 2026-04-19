package com.quant.binancequotes.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.websocket.BinanceWebSocketClient;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class QuoteHistoryRepositoryTest {

  @MockBean private BinanceWebSocketClient binanceWebSocketClient;

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("test_quotes")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
  }

  @Autowired private QuoteHistoryRepository quoteHistoryRepository;
  @Autowired private QuoteRepository quoteRepository;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void clearTable() throws Exception {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM quotes");
    }
  }

  private Quote makeQuote(String symbol, long updateId, long eventTime) {
    return new Quote(
        symbol,
        new BigDecimal("50000.00"),
        new BigDecimal("1.0"),
        new BigDecimal("50001.00"),
        new BigDecimal("1.0"),
        updateId,
        eventTime,
        eventTime - 3,
        eventTime);
  }

  @Test
  void findHistory_returnsQuotesInRange() {
    long baseTime = 100_000L;
    quoteRepository.batchInsert(
        List.of(
            makeQuote("BTCUSDT", 1, baseTime),
            makeQuote("BTCUSDT", 2, baseTime + 100),
            makeQuote("BTCUSDT", 3, baseTime + 200),
            makeQuote("BTCUSDT", 4, baseTime + 300)));

    List<Quote> results =
        quoteHistoryRepository.findHistory("BTCUSDT", baseTime + 100, baseTime + 200);

    assertThat(results).hasSize(2);
    assertThat(results.get(0).eventTime()).isEqualTo(baseTime + 200);
    assertThat(results.get(1).eventTime()).isEqualTo(baseTime + 100);
  }

  @Test
  void findHistory_returnsEmptyForNoMatch() {
    quoteRepository.batchInsert(List.of(makeQuote("BTCUSDT", 1, 100_000L)));

    List<Quote> results = quoteHistoryRepository.findHistory("BTCUSDT", 200_000L, 300_000L);

    assertThat(results).isEmpty();
  }

  @Test
  void findHistory_limitApplied() {
    long baseTime = 1_000_000L;
    int totalQuotes = 1101;
    for (int i = 0; i < totalQuotes; i++) {
      quoteRepository.batchInsert(List.of(makeQuote("ETHUSDT", i + 1, baseTime + i)));
    }

    List<Quote> results =
        quoteHistoryRepository.findHistory("ETHUSDT", baseTime, baseTime + totalQuotes);

    assertThat(results)
        .as("Results should be capped at 1000 even when %d rows match", totalQuotes)
        .hasSize(1000);
  }

  @Test
  void findHistory_preservesBigDecimalPrecision() {
    long eventTime = 200_000L;
    Quote original =
        new Quote(
            "SOLUSDT",
            new BigDecimal("67432.15000000"),
            new BigDecimal("1.23400000"),
            new BigDecimal("67432.20000000"),
            new BigDecimal("0.56700000"),
            1L,
            eventTime,
            eventTime,
            eventTime);
    quoteRepository.batchInsert(List.of(original));

    List<Quote> results = quoteHistoryRepository.findHistory("SOLUSDT", eventTime, eventTime);

    assertThat(results).hasSize(1);
    Quote retrieved = results.get(0);
    assertThat(retrieved.bid()).isEqualByComparingTo(new BigDecimal("67432.15"));
    assertThat(retrieved.ask()).isEqualByComparingTo(new BigDecimal("67432.20"));
    assertThat(retrieved.bidSize()).isEqualByComparingTo(new BigDecimal("1.234"));
    assertThat(retrieved.askSize()).isEqualByComparingTo(new BigDecimal("0.567"));
  }
}
