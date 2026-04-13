package com.quant.binancequotes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.repository.QuoteRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
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

@SpringBootTest
@Testcontainers
class QuoteRoundTripTest {

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

  @Autowired private ObjectMapper objectMapper;
  @Autowired private QuoteRepository quoteRepository;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void clearTable() throws Exception {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM quotes");
    }
  }

  private Quote makeQuote() {
    return new Quote(
        "BTCUSDT",
        new BigDecimal("67432.15000001"),
        new BigDecimal("1.23400001"),
        new BigDecimal("67432.20000001"),
        new BigDecimal("0.56700001"),
        1000000L,
        System.currentTimeMillis(),
        System.currentTimeMillis(),
        Instant.now());
  }

  @Test
  void testJacksonSerializationPrecision() throws Exception {
    Quote quote = makeQuote();
    String json = objectMapper.writeValueAsString(quote);

    // Assert no scientific notation in numeric fields (robust: works with any symbol)
    JsonNode node = objectMapper.readTree(json);
    assertThat(node.get("bid").asText()).doesNotMatch("(?i).*[eE].*");
    assertThat(json).contains("67432.15000001");

    Quote deserialized = objectMapper.readValue(json, Quote.class);

    assertThat(deserialized.bid().scale()).isEqualTo(8);
    assertThat(deserialized.bid().unscaledValue().toString()).isEqualTo("6743215000001");
    assertThat(deserialized.bid()).isEqualTo(quote.bid());
  }

  @Test
  void testDatabaseRoundTripPrecision() throws Exception {
    Quote quote = makeQuote();
    quoteRepository.batchInsert(List.of(quote));

    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery("SELECT bid_price, bid_size FROM quotes WHERE symbol = 'BTCUSDT'")) {
      assertThat(rs.next()).isTrue();

      BigDecimal dbBidPrice = rs.getBigDecimal("bid_price");
      BigDecimal dbBidSize = rs.getBigDecimal("bid_size");

      assertThat(dbBidPrice).isEqualByComparingTo(quote.bid());
      assertThat(dbBidSize).isEqualByComparingTo(quote.bidSize());

      // Verify exact equality without scale adjustment issues
      assertThat(dbBidPrice.compareTo(quote.bid())).isZero();
    }
  }

  @Test
  void testCombinedRoundTrip() throws Exception {
    Quote original = makeQuote();

    // 1. Serialize and deserialize
    String json = objectMapper.writeValueAsString(original);
    Quote deserialized = objectMapper.readValue(json, Quote.class);

    // 2. Insert to DB
    quoteRepository.batchInsert(List.of(deserialized));

    // 3. Select back
    Quote fromDb = null;
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM quotes WHERE symbol = 'BTCUSDT'")) {
      assertThat(rs.next()).isTrue();
      fromDb =
          new Quote(
              rs.getString("symbol"),
              rs.getBigDecimal("bid_price"),
              rs.getBigDecimal("bid_size"),
              rs.getBigDecimal("ask_price"),
              rs.getBigDecimal("ask_size"),
              rs.getLong("update_id"),
              rs.getLong("event_time"),
              rs.getLong("transaction_time"),
              rs.getTimestamp("received_at").toInstant());
    }

    // 4. Serialize again
    String finalJson = objectMapper.writeValueAsString(fromDb);

    // Assert no scientific notation in numeric fields (robust: works with any symbol)
    JsonNode finalNode = objectMapper.readTree(finalJson);
    assertThat(finalNode.get("bid").asText()).doesNotMatch("(?i).*[eE].*");
    assertThat(finalJson).contains("67432.15000001");
    assertThat(fromDb.bid().compareTo(original.bid())).isZero();
  }
}
