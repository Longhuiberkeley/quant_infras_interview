package com.quant.binancequotes.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.quant.binancequotes.model.Quote;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performance test for {@link QuoteService} read latency.
 *
 * <p>Populates the in-memory map with 10 quotes (one per configured symbol), runs 10 000 reads per
 * symbol, and asserts that p99 retrieval latency stays under 1 ms. Uses {@code System.nanoTime()}
 * for sub-millisecond precision.
 *
 * <p>Maps to SLO row in {@code docs/architecture.md} §8: "Service-layer read latency: p99 < 1 ms".
 */
class QuoteServicePerformanceTest {

  private static final Logger log = LoggerFactory.getLogger(QuoteServicePerformanceTest.class);
  private static final List<String> SYMBOLS =
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
  private static final int READS_PER_SYMBOL = 10_000;
  private static final long P99_THRESHOLD_NS = 1_000_000L; // 1 ms in nanoseconds

  private static QuoteService quoteService;

  // Metrics captured for phase7_results.md
  private static volatile double p50Ns;
  private static volatile double p99Ns;

  @BeforeAll
  static void setUp() {
    quoteService = new QuoteService();
    // Pre-populate with 10 quotes
    for (int i = 0; i < SYMBOLS.size(); i++) {
      quoteService.update(makeQuote(SYMBOLS.get(i), i + 1));
    }
  }

  @Test
  void p99ReadUnder1ms() {
    List<Long> latencies = new ArrayList<>(SYMBOLS.size() * READS_PER_SYMBOL);

    for (String symbol : SYMBOLS) {
      for (int j = 0; j < READS_PER_SYMBOL; j++) {
        long start = System.nanoTime();
        quoteService.get(symbol);
        long elapsed = System.nanoTime() - start;
        latencies.add(elapsed);
      }
    }

    latencies.sort(Long::compare);
    int size = latencies.size();
    long p50 = latencies.get(size / 2);
    long p99 = latencies.get((int) Math.ceil(size * 0.99) - 1);
    long max = latencies.get(size - 1);

    p50Ns = p50;
    p99Ns = p99;

    log.info(
        "QuoteService read latency — iterations: {}, p50: {} µs, p99: {} µs, max: {} µs",
        size,
        String.format("%.2f", p50 / 1_000.0),
        String.format("%.2f", p99 / 1_000.0),
        String.format("%.2f", max / 1_000.0));

    assertThat(p99)
        .as("p99 read latency should be under 1 ms (was %.2f µs)", p99 / 1_000.0)
        .isLessThan(P99_THRESHOLD_NS);
  }

  @AfterAll
  static void recordMetrics() {
    // Append to target/phase7-metrics.md for phase7_results.md evidence capture
    String line =
        String.format(
            "| QuoteService read (single-threaded) | < 1 ms | %.2f µs | %.2f µs | %s |%n",
            p50Ns / 1_000.0, p99Ns / 1_000.0, p99Ns < P99_THRESHOLD_NS ? "PASS" : "FAIL");
    try {
      java.nio.file.Path target = java.nio.file.Paths.get("target", "phase7-metrics.md");
      java.nio.file.Files.createDirectories(target.getParent());
      java.nio.file.Files.writeString(
          target,
          "### QuoteServicePerformanceTest\n\n| SLO | Target | p50 | p99 | Pass |\n"
              + "|-----|--------|-----|-----|------|\n"
              + line,
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
    } catch (Exception e) {
      log.warn("Failed to append metrics to target/phase7-metrics.md", e);
    }
  }

  private static Quote makeQuote(String symbol, long updateId) {
    return new Quote(
        symbol,
        new BigDecimal("50000.00"),
        new BigDecimal("1.5"),
        new BigDecimal("50001.00"),
        new BigDecimal("2.0"),
        updateId,
        System.currentTimeMillis(),
        System.currentTimeMillis(),
        System.currentTimeMillis());
  }
}
