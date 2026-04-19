package com.quant.binancequotes.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.websocket.BinanceWebSocketClient;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
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
class BatchInsertThroughputTest {

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

  @Autowired private QuoteRepository quoteRepository;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void clearTable() throws Exception {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM quotes");
    }
  }

  private Quote makeQuote(String symbol, long updateId) {
    return new Quote(
        symbol,
        new BigDecimal("50000.00"),
        new BigDecimal("1.0"),
        new BigDecimal("50001.00"),
        new BigDecimal("1.0"),
        updateId,
        System.currentTimeMillis(),
        System.currentTimeMillis(),
        System.currentTimeMillis());
  }

  @Test
  void batchInsertThroughput_meetsHeadroomSLO() {
    int batchSize = 200;
    int totalRows = 5_000;
    int numBatches = totalRows / batchSize;
    long baseUpdateId = 80_000_000L;
    String[] symbols = {
      "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
      "DOGEUSDT", "ADAUSDT", "TRXUSDT", "LINKUSDT", "AVAXUSDT"
    };

    long t0 = System.nanoTime();
    for (int b = 0; b < numBatches; b++) {
      List<Quote> batch = new ArrayList<>(batchSize);
      for (int i = 0; i < batchSize; i++) {
        int symbolIdx = (b * batchSize + i) % symbols.length;
        batch.add(makeQuote(symbols[symbolIdx], baseUpdateId + b * batchSize + i));
      }
      quoteRepository.batchInsert(batch);
    }
    long elapsed = System.nanoTime() - t0;

    double elapsedSec = elapsed / 1_000_000_000.0;
    double throughput = totalRows / elapsedSec;

    System.out.printf(
        "batchInsertThroughput: %d rows in %.2f s = %.0f rows/sec (batch size %d)%n",
        totalRows, elapsedSec, throughput, batchSize);

    assertThat(throughput)
        .as(
            "Batch insert throughput should be >= 1000 rows/sec (2x headroom over 500 qps),"
                + " observed %.0f rows/sec",
            throughput)
        .isGreaterThanOrEqualTo(1_000.0);
  }

  @Test
  void batchInsertThroughput_smallBatch() {
    int batchSize = 50;
    int totalRows = 2_000;
    int numBatches = totalRows / batchSize;
    long baseUpdateId = 90_000_000L;
    String[] symbols = {"BTCUSDT", "ETHUSDT", "BNBUSDT"};

    long t0 = System.nanoTime();
    for (int b = 0; b < numBatches; b++) {
      List<Quote> batch = new ArrayList<>(batchSize);
      for (int i = 0; i < batchSize; i++) {
        int symbolIdx = (b * batchSize + i) % symbols.length;
        batch.add(makeQuote(symbols[symbolIdx], baseUpdateId + b * batchSize + i));
      }
      quoteRepository.batchInsert(batch);
    }
    long elapsed = System.nanoTime() - t0;

    double elapsedSec = elapsed / 1_000_000_000.0;
    double throughput = totalRows / elapsedSec;

    System.out.printf(
        "batchInsertThroughput (batch=50): %d rows in %.2f s = %.0f rows/sec%n",
        totalRows, elapsedSec, throughput);

    assertThat(throughput)
        .as(
            "Even with smaller batches, throughput should be >= 1000 rows/sec, observed %.0f",
            throughput)
        .isGreaterThanOrEqualTo(1_000.0);
  }
}
