package com.quant.binancequotes.repository;

import static org.assertj.core.api.Assertions.*;

import com.quant.binancequotes.model.Quote;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link QuoteRepository} using Testcontainers PostgreSQL.
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>Batch insert succeeds
 *   <li>{@code ON CONFLICT (symbol, update_id) DO NOTHING} deduplicates silently
 *   <li>Monetary fields survive DB round-trip without precision loss
 * </ul>
 */
@SpringBootTest
@Testcontainers
class QuoteRepositoryIntegrationTest {

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
        new BigDecimal("67432.15000000"),
        new BigDecimal("1.23400000"),
        new BigDecimal("67432.20000000"),
        new BigDecimal("0.56700000"),
        updateId,
        System.currentTimeMillis(),
        System.currentTimeMillis(),
        Instant.now());
  }

  @Test
  void batchInsertSucceeds() throws Exception {
    List<Quote> batch = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      batch.add(makeQuote("BTCUSDT", 1_000_000L + i));
    }

    int inserted = quoteRepository.batchInsert(batch);
    assertThat(inserted).isEqualTo(10);

    // Verify in DB
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM quotes")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(10);
    }
  }

  @Test
  void duplicateSymbolUpdateIdIsSilentlyIgnored() throws Exception {
    List<Quote> batch = List.of(makeQuote("ETHUSDT", 5_000_000L));

    int first = quoteRepository.batchInsert(batch);
    assertThat(first).isEqualTo(1);

    // Insert the same (symbol, updateId) again — should return 0 new rows
    int second = quoteRepository.batchInsert(batch);
    assertThat(second).isEqualTo(0);

    // Count should still be 1
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM quotes")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  void monetaryFieldsSurviveDbRoundTrip() throws Exception {
    Quote original = makeQuote("SOLUSDT", 7_000_000L);
    quoteRepository.batchInsert(List.of(original));

    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT bid_price, bid_size, ask_price, ask_size FROM quotes WHERE symbol ="
                    + " 'SOLUSDT' AND update_id = 7000000")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBigDecimal("bid_price"))
          .isEqualByComparingTo(new BigDecimal("67432.15000000"));
      assertThat(rs.getBigDecimal("bid_size")).isEqualByComparingTo(new BigDecimal("1.23400000"));
      assertThat(rs.getBigDecimal("ask_price"))
          .isEqualByComparingTo(new BigDecimal("67432.20000000"));
      assertThat(rs.getBigDecimal("ask_size")).isEqualByComparingTo(new BigDecimal("0.56700000"));
    }
  }

  @Test
  void mixedInsertAndConflictInSameBatch() throws Exception {
    quoteRepository.batchInsert(List.of(makeQuote("BNBUSDT", 9_000_000L)));

    List<Quote> mixed = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      mixed.add(makeQuote("BNBUSDT", 9_000_001L + i));
    }
    mixed.add(makeQuote("BNBUSDT", 9_000_000L));

    int inserted = quoteRepository.batchInsert(mixed);
    assertThat(inserted).isEqualTo(5);

    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM quotes WHERE symbol = 'BNBUSDT'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(6);
    }
  }
}
