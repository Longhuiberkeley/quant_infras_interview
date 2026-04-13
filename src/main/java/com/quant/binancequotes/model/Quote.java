package com.quant.binancequotes.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable quote snapshot for a single instrument.
 *
 * <p>Monetary fields ({@code bid}, {@code bidSize}, {@code ask}, {@code askSize}) are {@link
 * BigDecimal} to preserve Binance's decimal-string precision. See {@code docs/design_decisions.md}
 * DD-2.
 *
 * <p>{@code eventTime} (Binance {@code E}) is the exchange-side timestamp used for freshness-lag
 * observability. {@code transactionTime} (Binance {@code T}) is the matching-engine commit time.
 * Both are milliseconds since epoch and come from the USDT-M Perpetuals {@code @bookTicker} stream
 * (see DD-10).
 *
 * @param symbol          e.g. "BTCUSDT"
 * @param bid             best bid price
 * @param bidSize         best bid quantity
 * @param ask             best ask price
 * @param askSize         best ask quantity
 * @param updateId        Binance order-book update id ({@code u}), monotonic per symbol
 * @param eventTime       Binance event time ({@code E}), ms since epoch
 * @param transactionTime Binance transaction time ({@code T}), ms since epoch
 * @param receivedAt      local clock time when the frame was parsed
 */
public record Quote(
    String symbol,
    BigDecimal bid,
    BigDecimal bidSize,
    BigDecimal ask,
    BigDecimal askSize,
    long updateId,
    long eventTime,
    long transactionTime,
    Instant receivedAt) {

  /** Returns the lag between now and the Binance event time, in milliseconds. */
  @JsonIgnore
  public long lagMillis() {
    return System.currentTimeMillis() - eventTime;
  }
}
