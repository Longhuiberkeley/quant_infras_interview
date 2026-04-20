package com.quant.binancequotes.service;

import static org.assertj.core.api.Assertions.*;

import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.websocket.BinanceWebSocketClient;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
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

/**
 * Measures quote-to-DB persistence latency using Testcontainers PostgreSQL.
 *
 * <p>Enqueues quotes via {@link BatchPersistenceService} and polls the database until each row
 * appears, measuring the time from {@code enqueue()} to confirmed persistence. This exercises real
 * JDBC I/O — the batch drainer, connection pool, and {@code INSERT ... ON CONFLICT DO NOTHING} are
 * all production code paths.
 *
 * <p>SLO: p99 &lt; 500 ms (accounts for batch flush interval + JDBC round-trip).
 */
@SpringBootTest
@Testcontainers
class PersistenceLatencyTest {

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
    registry.add("persistence.flush-ms", () -> "50");
  }

  @Autowired private BatchPersistenceService persistenceService;
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

  private boolean rowExists(String symbol, long updateId) throws Exception {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT 1 FROM quotes WHERE symbol = '"
                    + symbol
                    + "' AND update_id = "
                    + updateId)) {
      return rs.next();
    }
  }

  @Test
  void persistenceLatencyUnder500ms() throws Exception {
    int iterations = 200;
    long[] latencies = new long[iterations];
    String symbol = "BTCUSDT";
    long baseUpdateId = 50_000_000L;

    for (int i = 0; i < iterations; i++) {
      long expectedId = baseUpdateId + i;
      Quote quote = makeQuote(symbol, expectedId);

      long t0 = System.nanoTime();
      persistenceService.enqueue(quote);

      long deadline = t0 + 30_000_000_000L;
      boolean found = false;
      while (System.nanoTime() < deadline) {
        if (rowExists(symbol, expectedId)) {
          found = true;
          break;
        }
        Thread.sleep(5);
      }
      long t1 = System.nanoTime();

      if (!found) {
        throw new AssertionError(
            "Quote not persisted within 30 s: symbol=" + symbol + " updateId=" + expectedId);
      }

      latencies[i] = t1 - t0;
    }

    Arrays.sort(latencies);
    long p50 = latencies[iterations * 50 / 100];
    long p99 = latencies[iterations * 99 / 100];
    double p50Ms = p50 / 1_000_000.0;
    double p99Ms = p99 / 1_000_000.0;

    System.out.printf(
        "persistenceLatency: p50=%.2f ms, p99=%.2f ms (target p99 < 500 ms)%n", p50Ms, p99Ms);

    assertThat(p99)
        .as("p99 persistence latency should be under 500 ms (observed %.2f ms)", p99Ms)
        .isLessThan(500_000_000L);
  }
}
