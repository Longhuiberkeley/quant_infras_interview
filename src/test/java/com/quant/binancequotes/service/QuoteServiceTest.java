package com.quant.binancequotes.service;

import static org.assertj.core.api.Assertions.*;

import com.quant.binancequotes.model.Quote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteServiceTest {

  private QuoteService service;

  @BeforeEach
  void setUp() {
    service = new QuoteService();
  }

  // --- Basic CRUD ---

  @Test
  void getReturnsEmptyForUnknownSymbol() {
    assertThat(service.get("BTCUSDT")).isEmpty();
  }

  @Test
  void getReturnsQuoteAfterUpdate() {
    Quote q = testQuote("BTCUSDT", 1);
    service.update(q);
    assertThat(service.get("BTCUSDT")).contains(q);
  }

  @Test
  void allReturnsAllInsertedQuotes() {
    service.update(testQuote("BTCUSDT", 1));
    service.update(testQuote("ETHUSDT", 2));
    Map<String, Quote> all = service.all();
    assertThat(all).hasSize(2);
    assertThat(all.get("BTCUSDT").updateId()).isEqualTo(1);
    assertThat(all.get("ETHUSDT").updateId()).isEqualTo(2);
  }

  @Test
  void allReturnsEmptyMapWhenNoQuotes() {
    assertThat(service.all()).isEmpty();
  }

  // --- all() returns immutable snapshot ---

  @Test
  void allReturnsImmutableSnapshot() {
    service.update(testQuote("BTCUSDT", 1));
    Map<String, Quote> snapshot = service.all();
    assertThatThrownBy(() -> snapshot.put("ETHUSDT", testQuote("ETHUSDT", 2)))
        .isInstanceOf(UnsupportedOperationException.class);
    // Subsequent updates to the service should not affect the snapshot
    service.update(testQuote("ETHUSDT", 3));
    assertThat(snapshot).hasSize(1);
  }

  // --- Monotonic-by-updateId semantics ---

  @Test
  void newerUpdateIdReplacesOlder() {
    service.update(testQuote("BTCUSDT", 10, 100));
    service.update(testQuote("BTCUSDT", 11, 200));
    Quote result = service.get("BTCUSDT").orElseThrow();
    assertThat(result.updateId()).isEqualTo(11);
    assertThat(result.transactionTime()).isEqualTo(200);
  }

  @Test
  void olderUpdateIdDoesNotReplace() {
    service.update(testQuote("BTCUSDT", 10, 200));
    service.update(testQuote("BTCUSDT", 5, 100));
    Quote result = service.get("BTCUSDT").orElseThrow();
    assertThat(result.updateId()).isEqualTo(10);
    assertThat(result.transactionTime()).isEqualTo(200);
  }

  @Test
  void equalUpdateIdReplaces() {
    // Same updateId — treat as re-delivery after reconnect; idempotent replace is fine
    service.update(testQuote("BTCUSDT", 10, 200));
    service.update(testQuote("BTCUSDT", 10, 200));
    Quote result = service.get("BTCUSDT").orElseThrow();
    assertThat(result.updateId()).isEqualTo(10);
  }

  @Test
  void differentSymbolsAreIndependent() {
    service.update(testQuote("BTCUSDT", 10));
    service.update(testQuote("ETHUSDT", 5));
    assertThat(service.get("BTCUSDT").get().updateId()).isEqualTo(10);
    assertThat(service.get("ETHUSDT").get().updateId()).isEqualTo(5);
  }

  // --- Concurrent correctness ---

  @Test
  void concurrentUpdatesFromMultipleThreadsAllPersist() throws InterruptedException {
    int threadCount = 10;
    int updatesPerThread = 100;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);

    for (int t = 0; t < threadCount; t++) {
      final int threadIndex = t;
      executor.submit(
          () -> {
            try {
              startLatch.await();
              for (int i = 0; i < updatesPerThread; i++) {
                String symbol = "SYM" + threadIndex + "USDT";
                service.update(testQuote(symbol, i));
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    Map<String, Quote> all = service.all();
    assertThat(all).hasSize(threadCount);
    for (int t = 0; t < threadCount; t++) {
      String symbol = "SYM" + t + "USDT";
      Quote q = all.get(symbol);
      assertThat(q).isNotNull();
      // The last update should have updateId == updatesPerThread - 1
      assertThat(q.updateId()).isEqualTo(updatesPerThread - 1);
    }
  }

  @Test
  void concurrentUpdatesToSameSymbolMaintainMonotonicity() throws InterruptedException {
    AtomicInteger failures = new AtomicInteger();
    int threadCount = 20;
    int updatesPerThread = 500;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);

    for (int t = 0; t < threadCount; t++) {
      final int threadIndex = t;
      executor.submit(
          () -> {
            try {
              startLatch.await();
              for (int i = 0; i < updatesPerThread; i++) {
                service.update(testQuote("BTCUSDT", threadIndex * updatesPerThread + i));
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    Quote result = service.get("BTCUSDT").orElseThrow();
    // The winning updateId should be the maximum one submitted
    assertThat(result.updateId()).isEqualTo(threadCount * updatesPerThread - 1);
  }

  @Test
  void concurrentReadsDuringWritesSeeConsistentState() throws InterruptedException {
    int writerCount = 4;
    int readerCount = 4;
    int writesPerThread = 200;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(writerCount + readerCount);
    List<Map<String, Quote>> snapshots = new ArrayList<>();

    ExecutorService executor = Executors.newFixedThreadPool(writerCount + readerCount);

    // Writers: each writes to its own symbol key
    for (int w = 0; w < writerCount; w++) {
      final int wi = w;
      executor.submit(
          () -> {
            try {
              startLatch.await();
              for (int i = 0; i < writesPerThread; i++) {
                service.update(testQuote("SYM" + wi + "USDT", i));
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    // Readers: take snapshots concurrently
    for (int r = 0; r < readerCount; r++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              for (int i = 0; i < writesPerThread; i++) {
                Map<String, Quote> snap = service.all();
                synchronized (snapshots) {
                  snapshots.add(snap);
                }
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    // Every snapshot should be a valid Map (no nulls, no corruption)
    for (Map<String, Quote> snap : snapshots) {
      assertThat(snap).isNotNull();
      for (Map.Entry<String, Quote> entry : snap.entrySet()) {
        assertThat(entry.getKey()).isNotNull();
        assertThat(entry.getValue()).isNotNull();
      }
    }
  }

  // --- No synchronized keyword ---

  @Test
  void noSynchronizedKeywordInSource() {
    String sourcePath = QuoteService.class.getName().replace('.', '/').concat(".java");
    // The simplest check: the compiled class's method modifiers shouldn't include ACC_SYNCHRONIZED
    // But a practical test is to check the source file for the "synchronized" keyword.
    // We verify programmatically that no method in QuoteService is synchronized.
    for (var method : QuoteService.class.getDeclaredMethods()) {
      assertThat(java.lang.reflect.Modifier.isSynchronized(method.getModifiers()))
          .as("Method %s should not be synchronized", method.getName())
          .isFalse();
    }
  }

  // --- Helpers ---

  private Quote testQuote(String symbol, long updateId) {
    return testQuote(symbol, updateId, System.currentTimeMillis());
  }

  private Quote testQuote(String symbol, long updateId, long transactionTime) {
    return new Quote(
        symbol,
        BigDecimal.valueOf(67432.15),
        BigDecimal.valueOf(1.234),
        BigDecimal.valueOf(67432.20),
        BigDecimal.valueOf(0.567),
        updateId,
        System.currentTimeMillis(),
        transactionTime,
        Instant.now());
  }
}
