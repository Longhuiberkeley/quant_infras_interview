package com.quant.binancequotes.websocket;

import static org.junit.jupiter.api.Assertions.*;

import com.quant.binancequotes.model.Quote;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteMessageParserTest {

  private QuoteMessageParser parser;

  @BeforeEach
  void setUp() {
    parser = new QuoteMessageParser();
  }

  // ── Helper ─────────────────────────────────────────────────────────────────

  /** Builds a minimal valid combined-stream bookTicker message. */
  private String validBookTickerMessage() {
    return """
           {
             "stream": "btcusdt@bookTicker",
             "data": {
               "e": "bookTicker",
               "u": 4123567890,
               "E": 1713456789000,
               "T": 1713456788997,
               "s": "BTCUSDT",
               "b": "67432.15",
               "B": "1.234",
               "a": "67432.20",
               "A": "0.567"
             }
           }
           """;
  }

  // ── Happy path ─────────────────────────────────────────────────────────────

  @Test
  void validBookTicker_parsesQuote() {
    Optional<Quote> result = parser.parse(validBookTickerMessage());

    assertTrue(result.isPresent());
    Quote q = result.get();
    assertEquals("BTCUSDT", q.symbol());
    assertEquals(new BigDecimal("67432.15"), q.bid());
    assertEquals(new BigDecimal("1.234"), q.bidSize());
    assertEquals(new BigDecimal("67432.20"), q.ask());
    assertEquals(new BigDecimal("0.567"), q.askSize());
    assertEquals(4123567890L, q.updateId());
    assertEquals(1713456789000L, q.eventTime());
    assertEquals(1713456788997L, q.transactionTime());
    assertNotNull(q.receivedAt());
  }

  @Test
  void eventTimeAndTransactionTime_mappedFromEAndT() {
    String msg =
        """
        {
          "stream": "ethusdt@bookTicker",
          "data": {
            "u": 1,
            "E": 1700000000123,
            "T": 1700000000099,
            "s": "ETHUSDT",
            "b": "3000.00",
            "B": "10.0",
            "a": "3000.01",
            "A": "5.0"
          }
        }
        """;

    Optional<Quote> result = parser.parse(msg);
    assertTrue(result.isPresent());
    assertEquals(1700000000123L, result.get().eventTime());
    assertEquals(1700000000099L, result.get().transactionTime());
  }

  @Test
  void bidEqualsAsk_isAccepted() {
    String msg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": 1713456789000,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "50000.00",
            "B": "1.0",
            "a": "50000.00",
            "A": "1.0"
          }
        }
        """;

    Optional<Quote> result = parser.parse(msg);
    assertTrue(result.isPresent(), "bid == ask should be accepted (tight spread)");
  }

  @Test
  void zeroBidSize_isAccepted() {
    String msg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": 1713456789000,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "50000.00",
            "B": "0",
            "a": "50001.00",
            "A": "1.0"
          }
        }
        """;

    Optional<Quote> result = parser.parse(msg);
    assertTrue(result.isPresent(), "zero bidSize should be accepted");
    assertEquals(BigDecimal.ZERO, result.get().bidSize());
  }

  // ── Rejection cases ────────────────────────────────────────────────────────

  @Test
  void zeroPrice_bid_returnsEmpty() {
    String msg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": 1713456789000,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "0",
            "B": "1.0",
            "a": "50001.00",
            "A": "1.0"
          }
        }
        """;

    assertTrue(parser.parse(msg).isEmpty());
  }

  @Test
  void zeroPrice_ask_returnsEmpty() {
    String msg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": 1713456789000,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "50000.00",
            "B": "1.0",
            "a": "0",
            "A": "1.0"
          }
        }
        """;

    assertTrue(parser.parse(msg).isEmpty());
  }

  @Test
  void negativePrice_returnsEmpty() {
    String msg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": 1713456789000,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "-1.00",
            "B": "1.0",
            "a": "50001.00",
            "A": "1.0"
          }
        }
        """;

    assertTrue(parser.parse(msg).isEmpty());
  }

  @Test
  void crossedSpread_returnsEmpty() {
    String msg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": 1713456789000,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "50001.00",
            "B": "1.0",
            "a": "50000.00",
            "A": "1.0"
          }
        }
        """;

    assertTrue(parser.parse(msg).isEmpty());
  }

  @Test
  void zeroEventTime_returnsEmpty() {
    String msg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": 0,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "50000.00",
            "B": "1.0",
            "a": "50001.00",
            "A": "1.0"
          }
        }
        """;

    assertTrue(parser.parse(msg).isEmpty());
  }

  @Test
  void futureEventTime_returnsEmpty() {
    long farFuture = System.currentTimeMillis() + 7_200_000L; // 2 hours in the future
    String msg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": %d,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "50000.00",
            "B": "1.0",
            "a": "50001.00",
            "A": "1.0"
          }
        }
        """
            .formatted(farFuture);

    assertTrue(parser.parse(msg).isEmpty());
  }

  @Test
  void negativeBidSize_returnsEmpty() {
    String msg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": 1713456789000,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "50000.00",
            "B": "-1.0",
            "a": "50001.00",
            "A": "1.0"
          }
        }
        """;

    assertTrue(parser.parse(msg).isEmpty());
  }

  @Test
  void negativeAskSize_returnsEmpty() {
    String msg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": 1713456789000,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "50000.00",
            "B": "1.0",
            "a": "50001.00",
            "A": "-0.5"
          }
        }
        """;

    assertTrue(parser.parse(msg).isEmpty());
  }

  // ── Edge cases ─────────────────────────────────────────────────────────────

  @Test
  void malformedJson_returnsEmpty() {
    assertTrue(parser.parse("{ bad json").isEmpty());
    assertTrue(parser.parse("not json at all").isEmpty());
    assertTrue(parser.parse("").isEmpty());
  }

  @Test
  void subscriptionAckFrame_returnsEmpty() {
    String ack = "{\"result\":null,\"id\":1}";
    assertTrue(parser.parse(ack).isEmpty());
  }

  @Test
  void missingDataStream_returnsEmpty() {
    String msg = "{\"stream\":\"btcusdt@bookTicker\"}";
    assertTrue(parser.parse(msg).isEmpty());
  }

  @Test
  void missingSymbol_returnsEmpty() {
    String msg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": 1713456789000,
            "T": 1713456788997,
            "b": "50000.00",
            "B": "1.0",
            "a": "50001.00",
            "A": "1.0"
          }
        }
        """;

    assertTrue(parser.parse(msg).isEmpty());
  }
}
