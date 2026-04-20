package com.quant.binancequotes.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.quant.binancequotes.model.Quote;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class QuoteServiceConcurrentReadTest {

  private static final Logger log = LoggerFactory.getLogger(QuoteServiceConcurrentReadTest.class);
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
  private static final int READER_THREADS = 8;
  private static final long TEST_DURATION_MS = 2_000L;
  private static final long P99_THRESHOLD_NS = 2_000_000L;

  private static QuoteService quoteService;

  @BeforeAll
  static void setUp() {
    quoteService = new QuoteService();
    for (int i = 0; i < SYMBOLS.size(); i++) {
      quoteService.update(makeQuote(SYMBOLS.get(i), i + 1));
    }
  }

  @Test
  void concurrentRead_p99Under2ms() throws Exception {
    Queue<Long> latencies = new ConcurrentLinkedQueue<>();
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch doneGate = new CountDownLatch(READER_THREADS);
    AtomicLong writerId = new AtomicLong(100);

    Thread writerThread =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                for (String symbol : SYMBOLS) {
                  quoteService.update(makeQuote(symbol, writerId.incrementAndGet()));
                }
              }
            },
            "test-writer");

    for (int t = 0; t < READER_THREADS; t++) {
      new Thread(
              () -> {
                try {
                  startGate.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                long deadline = System.currentTimeMillis() + TEST_DURATION_MS;
                while (System.currentTimeMillis() < deadline) {
                  long start = System.nanoTime();
                  quoteService.get("BTCUSDT");
                  long elapsed = System.nanoTime() - start;
                  latencies.add(elapsed);
                }
                doneGate.countDown();
              },
              "test-reader-" + t)
          .start();
    }

    startGate.countDown();
    writerThread.start();

    doneGate.await();
    writerThread.interrupt();
    writerThread.join(2000);

    List<Long> sorted = new ArrayList<>(latencies);
    sorted.sort(Long::compare);
    int size = sorted.size();
    long p50 = sorted.get(size / 2);
    long p99 = sorted.get((int) Math.ceil(size * 0.99) - 1);
    long max = sorted.get(size - 1);

    log.info(
        "Concurrent read ({} readers + 1 writer, {} samples) — p50: {} µs, p99: {} µs, max: {}"
            + " µs",
        READER_THREADS,
        size,
        String.format("%.2f", p50 / 1_000.0),
        String.format("%.2f", p99 / 1_000.0),
        String.format("%.2f", max / 1_000.0));

    assertThat(p99)
        .as("p99 read latency under contention should be under 2 ms (was %.2f µs)", p99 / 1_000.0)
        .isLessThan(P99_THRESHOLD_NS);
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
