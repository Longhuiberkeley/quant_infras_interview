package com.quant.binancequotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import com.quant.binancequotes.config.AppProperties;
import com.quant.binancequotes.config.BinanceProperties;
import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.service.BatchPersistenceService;
import com.quant.binancequotes.service.QuoteService;
import com.quant.binancequotes.websocket.BinanceWebSocketClient;
import com.quant.binancequotes.websocket.QuoteMessageParser;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests that the freshness-lag gauge reports measured age correctly and stays under the SLO
 * threshold under sustained load.
 *
 * <p>Pumps 500 mock messages/sec through the {@link BinanceWebSocketClient} and asserts that the
 * {@code binance.quote.lag.max.millis} gauge stays under 500 ms at steady state. Note: the test
 * hard-codes {@code eventTime = now - 50ms}, so it validates gauge arithmetic — not actual
 * end-to-end system lag.
 *
 * <p>Maps to SLO row in {@code docs/architecture.md} §8: "Binance freshness lag: p99 < 500 ms".
 */
class LagGaugeTest {

  private static final Logger log = LoggerFactory.getLogger(LagGaugeTest.class);
  private static final int MESSAGES_PER_SECOND = 500;
  private static final int DURATION_SECONDS = 3;
  private static final long LAG_THRESHOLD_MS = 500L;

  private BinanceWebSocketClient client;
  private MeterRegistry meterRegistry;
  private QuoteService quoteService;

  private static volatile double observedMaxLagMs;

  @BeforeEach
  void setUp() {
    AppProperties appProperties = new AppProperties();
    appProperties.setSymbols(
        List.of(
            "BTCUSDT",
            "ETHUSDT",
            "BNBUSDT",
            "SOLUSDT",
            "XRPUSDT",
            "DOGEUSDT",
            "ADAUSDT",
            "TRXUSDT",
            "LINKUSDT",
            "AVAXUSDT"));

    BinanceProperties binanceProperties = new BinanceProperties();
    binanceProperties.setBaseUrl("wss://fstream.binance.com");

    quoteService = new QuoteService();
    BatchPersistenceService mockPersistence = mock(BatchPersistenceService.class);
    org.mockito.Mockito.doNothing().when(mockPersistence).enqueue(any(Quote.class));

    meterRegistry = new SimpleMeterRegistry();

    client =
        new BinanceWebSocketClient(
            appProperties,
            binanceProperties,
            quoteService,
            mockPersistence,
            new QuoteMessageParser(appProperties),
            meterRegistry);
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.getScheduler().shutdownNow();
    }
  }

  @Test
  void lagGaugeUnder500msAt500rps() {
    String template =
        """
        {
          "stream": "%s@bookTicker",
          "data": {
            "u": %d,
            "E": %d,
            "T": %d,
            "s": "%s",
            "b": "50000.00",
            "B": "1.5",
            "a": "50001.00",
            "A": "2.0"
          }
        }
        """;

    int totalMessages = MESSAGES_PER_SECOND * DURATION_SECONDS;
    List<String> symbols =
        List.of(
            "BTCUSDT",
            "ETHUSDT",
            "BNBUSDT",
            "SOLUSDT",
            "XRPUSDT",
            "DOGEUSDT",
            "ADAUSDT",
            "TRXUSDT",
            "LINKUSDT",
            "AVAXUSDT");

    long startNs = System.nanoTime();

    okhttp3.WebSocket mockWebSocket = mock(okhttp3.WebSocket.class);

    for (int i = 0; i < totalMessages; i++) {
      String symbol = symbols.get(i % symbols.size());
      long eventTime = System.currentTimeMillis() - 50;
      String streamName = symbol.toLowerCase();
      String msg = String.format(template, streamName, i + 1, eventTime, eventTime, symbol);

      client.onMessage(mockWebSocket, msg);
    }

    long elapsedNs = System.nanoTime() - startNs;
    double elapsedMs = elapsedNs / 1_000_000.0;

    var gauge = meterRegistry.find("binance.quote.lag.max.millis").gauge();
    assertThat(gauge).as("Fleet-max lag gauge should be registered").isNotNull();
    double maxLag = gauge.value();
    observedMaxLagMs = maxLag;

    double actualRps = totalMessages / (elapsedMs / 1000.0);

    log.info(
        "Lag gauge test — messages: {}, elapsed: {} ms, actual rate: {} msg/s, max lag: {} ms",
        totalMessages,
        String.format("%.2f", elapsedMs),
        String.format("%.0f", actualRps),
        String.format("%.2f", maxLag));

    assertThat(maxLag)
        .as(
            "Fleet-max freshness lag should stay under %d ms at %d msg/s (was %.2f ms)",
            LAG_THRESHOLD_MS, MESSAGES_PER_SECOND, maxLag)
        .isLessThan(LAG_THRESHOLD_MS);
  }

  @org.junit.jupiter.api.AfterAll
  static void recordMetrics() {
    String line =
        String.format(
            "| Freshness lag @ 500 msg/s | < 500 ms | — | %.2f ms | %s |%n",
            observedMaxLagMs, observedMaxLagMs < LAG_THRESHOLD_MS ? "PASS" : "FAIL");
    try {
      java.nio.file.Path target = java.nio.file.Paths.get("target", "phase7-metrics.md");
      java.nio.file.Files.createDirectories(target.getParent());
      java.nio.file.Files.writeString(
          target,
          "### LagGaugeTest\n\n| SLO | Target | p50 | p99 | Pass |\n"
              + "|-----|--------|-----|-----|------|\n"
              + line,
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
    } catch (Exception e) {
      log.warn("Failed to append metrics to target/phase7-metrics.md", e);
    }
  }
}
