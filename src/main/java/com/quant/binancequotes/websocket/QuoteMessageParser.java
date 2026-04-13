package com.quant.binancequotes.websocket;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.binancequotes.model.Quote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parses Binance {@code @bookTicker} WebSocket messages into {@link Quote} records.
 *
 * <p>Handles the combined-stream wrapper shape {@code {"stream":"...","data":{...}}} and maps
 * Binance's short field names ({@code u}, {@code E}, {@code T}, {@code s}, {@code b}, {@code B},
 * {@code a}, {@code A}) to the {@code Quote} record fields.
 *
 * <p>Validates business invariants before construction (DD-13): positive prices, non-negative
 * sizes, non-crossed spread ({@code bid <= ask}), positive {@code updateId}, plausible timestamps
 * ({@code eventTime}, {@code transactionTime}), and non-negative {@code transactionTime}. Invalid
 * messages are logged at {@code WARN} and return {@link Optional#empty()}.
 *
 * <p>Subscription acknowledgment frames (e.g. {@code {"result":null,"id":1}}) are silently skipped.
 *
 * @see <a
 *     href="https://binance-docs.github.io/apidocs/futures/en/#individual-symbol-book-ticker-streams">Binance
 *     Futures WebSocket Docs</a>
 */
@Component
public class QuoteMessageParser {

  private static final Logger log = LoggerFactory.getLogger(QuoteMessageParser.class);

  /** Maximum allowed future drift for eventTime (1 hour). */
  private static final long MAX_FUTURE_DRIFT_MS = 3_600_000L;

  private final ObjectMapper mapper;

  public QuoteMessageParser() {
    this.mapper = new ObjectMapper();
    this.mapper.configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
  }

  /**
   * Parses a raw WebSocket text message into a {@link Quote}, or returns empty if the message is
   * invalid, a subscription ack, or fails business-invariant checks.
   *
   * @param rawMessage the raw JSON string from the WebSocket
   * @return a valid {@link Quote} wrapped in {@link Optional}, or empty if the message should be
   *     skipped
   */
  public Optional<Quote> parse(String rawMessage) {
    JsonNode root;
    try {
      root = mapper.readTree(rawMessage);
    } catch (Exception e) {
      log.warn("Malformed JSON received — skipping: {}", rawMessage, e);
      return Optional.empty();
    }

    // Subscription acknowledgment: {"result":null,"id":1}
    if (root.has("result") && !root.has("data")) {
      log.debug("Subscription ack received — skipping");
      return Optional.empty();
    }

    // Combined-stream wrapper: {"stream":"btcusdt@bookTicker","data":{...}}
    JsonNode data = root.path("data");
    if (data.isMissingNode() || data.isNull()) {
      log.warn("No 'data' field in message — skipping: {}", rawMessage);
      return Optional.empty();
    }

    // Extract short-name fields from the data payload
    String symbol = data.path("s").asText(null);
    String bidStr = data.path("b").asText(null);
    String bidSizeStr = data.path("B").asText(null);
    String askStr = data.path("a").asText(null);
    String askSizeStr = data.path("A").asText(null);
    long updateId = data.path("u").asLong(-1);
    long eventTime = data.path("E").asLong(-1);
    long transactionTime = data.path("T").asLong(-1);

    // Validate presence of required fields
    if (symbol == null || bidStr == null || askStr == null) {
      log.warn("Missing required fields in data payload — skipping: {}", data);
      return Optional.empty();
    }

    BigDecimal bid;
    BigDecimal bidSize;
    BigDecimal ask;
    BigDecimal askSize;
    try {
      bid = new BigDecimal(bidStr);
      bidSize = bidSizeStr != null ? new BigDecimal(bidSizeStr) : BigDecimal.ZERO;
      ask = new BigDecimal(askStr);
      askSize = askSizeStr != null ? new BigDecimal(askSizeStr) : BigDecimal.ZERO;
    } catch (NumberFormatException e) {
      log.warn("Non-numeric price/size in message — skipping: {}", data, e);
      return Optional.empty();
    }

    // Business invariant validation (DD-13)
    if (bid.compareTo(BigDecimal.ZERO) <= 0) {
      log.warn("Zero or negative bid — skipping: symbol={}, bid={}", symbol, bid);
      return Optional.empty();
    }
    if (ask.compareTo(BigDecimal.ZERO) <= 0) {
      log.warn("Zero or negative ask — skipping: symbol={}, ask={}", symbol, ask);
      return Optional.empty();
    }
    if (bidSize.compareTo(BigDecimal.ZERO) < 0) {
      log.warn("Negative bidSize — skipping: symbol={}, bidSize={}", symbol, bidSize);
      return Optional.empty();
    }
    if (askSize.compareTo(BigDecimal.ZERO) < 0) {
      log.warn("Negative askSize — skipping: symbol={}, askSize={}", symbol, askSize);
      return Optional.empty();
    }
    if (bid.compareTo(ask) > 0) {
      log.warn(
          "Crossed spread (bid > ask) — skipping: symbol={}, bid={}, ask={}", symbol, bid, ask);
      return Optional.empty();
    }
    if (eventTime <= 0) {
      log.warn("Zero or negative eventTime — skipping: symbol={}, eventTime={}", symbol, eventTime);
      return Optional.empty();
    }
    if (updateId <= 0) {
      log.warn("Zero or negative updateId — skipping: symbol={}, updateId={}", symbol, updateId);
      return Optional.empty();
    }
    if (transactionTime < 0) {
      log.warn(
          "Negative transactionTime — skipping: symbol={}, transactionTime={}",
          symbol,
          transactionTime);
      return Optional.empty();
    }
    long now = System.currentTimeMillis();
    if (eventTime > now + MAX_FUTURE_DRIFT_MS) {
      log.warn(
          "Far-future eventTime ({} ms ahead) — skipping: symbol={}, eventTime={}, now={}",
          eventTime - now,
          symbol,
          eventTime,
          now);
      return Optional.empty();
    }

    Instant receivedAt = Instant.now();
    return Optional.of(
        new Quote(
            symbol, bid, bidSize, ask, askSize, updateId, eventTime, transactionTime, receivedAt));
  }
}
