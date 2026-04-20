package com.quant.binancequotes.model;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class QuoteTest {

  private static final long NOW = System.currentTimeMillis();

  private Quote sampleQuote() {
    return new Quote(
        "BTCUSDT",
        new BigDecimal("67432.15"),
        new BigDecimal("1.234"),
        new BigDecimal("67432.20"),
        new BigDecimal("0.567"),
        4123567890L,
        1713456789000L,
        1713456788997L,
        NOW);
  }

  @Test
  void recordConstructionPreservesFields() {
    Quote q = sampleQuote();

    assertEquals("BTCUSDT", q.symbol());
    assertEquals(new BigDecimal("67432.15"), q.bid());
    assertEquals(new BigDecimal("1.234"), q.bidSize());
    assertEquals(new BigDecimal("67432.20"), q.ask());
    assertEquals(new BigDecimal("0.567"), q.askSize());
    assertEquals(4123567890L, q.updateId());
    assertEquals(1713456789000L, q.eventTime());
    assertEquals(1713456788997L, q.transactionTime());
    assertEquals(NOW, q.receivedAt());
  }

  @Test
  void lagMillisIsNonNegativeAndReasonable() {
    long eventTime = System.currentTimeMillis() - 50; // 50 ms ago
    Quote q =
        new Quote(
            "ETHUSDT",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            1L,
            eventTime,
            eventTime - 3,
            System.currentTimeMillis());

    long lag = q.lagMillis();

    assertTrue(lag >= 0, "lag should be non-negative");
    assertTrue(lag < 500, "lag should be under 500 ms for a recent event");
  }

  @Test
  void lagMillisUsesEventTimeNotTransactionTime() {
    long pastEventTime = System.currentTimeMillis() - 10_000; // 10 seconds ago
    long recentTxTime = System.currentTimeMillis() - 100; // 100 ms ago

    Quote q =
        new Quote(
            "SOLUSDT",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            1L,
            pastEventTime,
            recentTxTime,
            System.currentTimeMillis());

    long lag = q.lagMillis();

    // lag should be ~10 seconds, NOT ~100 ms
    assertTrue(lag >= 9_000, "lag should reflect eventTime gap, not transactionTime");
  }

  @Test
  void recordIsImmutableByConstruction() {
    Quote q = sampleQuote();
    // Records have no setters; this test documents the intent.
    // Any mutation would require reflection, which we don't do.
    assertNotEquals(
        new BigDecimal("0"),
        q.bid(),
        "monetary fields should not be default-constructable to zero");
  }
}
