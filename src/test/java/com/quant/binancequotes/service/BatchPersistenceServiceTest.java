package com.quant.binancequotes.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quant.binancequotes.config.PersistenceProperties;
import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.repository.QuoteRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BatchPersistenceServiceTest {

  private static final int SMALL_QUEUE = 20;
  private static final int SMALL_BATCH = 5;
  private static final long FAST_FLUSH = 200L;

  private QuoteRepository mockRepo;
  private BatchPersistenceService service;

  private Quote makeQuote(String symbol, long updateId) {
    return new Quote(
        symbol,
        new BigDecimal("50000"),
        new BigDecimal("1.0"),
        new BigDecimal("50001"),
        new BigDecimal("1.0"),
        updateId,
        System.currentTimeMillis(),
        System.currentTimeMillis(),
        Instant.now());
  }

  private PersistenceProperties smallProps() {
    PersistenceProperties p = new PersistenceProperties();
    p.setQueueCapacity(SMALL_QUEUE);
    p.setBatchSize(SMALL_BATCH);
    p.setFlushMs(FAST_FLUSH);
    p.setShutdownTimeoutMs(2_000);
    p.setDropOldestThreshold(0.9);
    return p;
  }

  /** Start service with a default mock that returns 0 inserted. */
  private void startService(PersistenceProperties props) {
    mockRepo = mock(QuoteRepository.class);
    when(mockRepo.batchInsert(anyList())).thenReturn(0);
    service = new BatchPersistenceService(props, mockRepo);
  }

  /** Start service with a pre-configured mock (for tests that need custom stubs). */
  private void startServiceWithMock(QuoteRepository repo, PersistenceProperties props) {
    mockRepo = repo;
    service = new BatchPersistenceService(props, mockRepo);
  }

  @AfterEach
  void tearDown() {
    if (service != null) {
      service.shutdown();
    }
  }

  // ── Tests ────────────────────────────────────────────────────────────────

  @Test
  void enqueueDrainsAndPersists() throws Exception {
    startService(smallProps());

    // Enqueue enough to fill one batch
    for (int i = 0; i < SMALL_BATCH; i++) {
      service.enqueue(makeQuote("BTCUSDT", i + 1));
    }

    // Wait for the drainer to flush (flushMs + margin)
    Thread.sleep(FAST_FLUSH + 300);

    verify(mockRepo, atLeastOnce()).batchInsert(anyList());
  }

  @Test
  void enqueueDuringShutdownIsSilentlyDropped() throws Exception {
    startService(smallProps());
    service.shutdown();
    // Should not throw
    service.enqueue(makeQuote("ETHUSDT", 999));
    assertEquals(0, service.queueDepth());
  }

  @Test
  void dropOldestFiresWhenQueueOverflows() throws Exception {
    // Use a very small queue and a slow consumer to force overflow
    PersistenceProperties p = smallProps();
    p.setQueueCapacity(5);
    p.setDropOldestThreshold(0.8);

    // Make the repo slow so queue builds up
    QuoteRepository slowRepo = mock(QuoteRepository.class);
    when(slowRepo.batchInsert(anyList()))
        .thenAnswer(
            inv -> {
              Thread.sleep(500);
              return ((List<?>) inv.getArgument(0)).size();
            });

    startServiceWithMock(slowRepo, p);

    // Flood the queue faster than the drainer can consume
    long droppedBefore = service.droppedCount();
    for (int i = 0; i < 50; i++) {
      service.enqueue(makeQuote("SOLUSDT", i + 1));
    }

    // Wait for everything to drain
    Thread.sleep(3_000);
    service.shutdown();

    // At least some drops should have occurred
    assertTrue(
        service.droppedCount() > droppedBefore,
        "Expected some drops due to overflow, but droppedCount=" + service.droppedCount());
  }

  @Test
  void shutdownDrainsRemainingQueue() throws Exception {
    // Block the repo so items pile up in queue
    CountDownLatch repoBlocked = new CountDownLatch(1);
    QuoteRepository blockingRepo = mock(QuoteRepository.class);
    when(blockingRepo.batchInsert(anyList()))
        .thenAnswer(
            inv -> {
              repoBlocked.await(5, TimeUnit.SECONDS);
              return ((List<?>) inv.getArgument(0)).size();
            });

    startServiceWithMock(blockingRepo, smallProps());

    // Enqueue more than one batch
    for (int i = 0; i < SMALL_BATCH * 2; i++) {
      service.enqueue(makeQuote("BNBUSDT", i + 1));
    }

    // Give the drainer time to pick up the first batch and block
    Thread.sleep(100);

    // Now unblock the repo and shut down — shutdown should drain what's left
    repoBlocked.countDown();
    service.shutdown();

    // After shutdown the queue should be empty (or nearly so within timeout)
    assertTrue(
        service.queueDepth() <= SMALL_BATCH,
        "Queue should be drained or near-empty after shutdown, but depth=" + service.queueDepth());
  }

  @Test
  void drainerThreadIsNamedQuoteBatchWriter() {
    startService(smallProps());

    Thread t = service.drainerThread();
    assertNotNull(t, "Drainer thread should exist");
    assertEquals("quote-batch-writer", t.getName());
    assertTrue(t.isVirtual(), "Drainer should be a virtual thread");
  }

  @Test
  void retryOneByOneOnBatchFailure() throws Exception {
    // First call fails, second succeeds
    QuoteRepository failingRepo = mock(QuoteRepository.class);
    when(failingRepo.batchInsert(anyList()))
        .thenThrow(new RuntimeException("DB glitch"))
        .thenReturn(1);

    startServiceWithMock(failingRepo, smallProps());

    service.enqueue(makeQuote("XRPUSDT", 1));
    Thread.sleep(FAST_FLUSH + 500);

    // Should have been called at least twice (initial + retry)
    verify(failingRepo, atLeast(2)).batchInsert(anyList());
  }

  @Test
  void queueDepthReflectsEnqueuedMinusDrained() throws Exception {
    CountDownLatch repoBlocked = new CountDownLatch(1);
    QuoteRepository blockingRepo = mock(QuoteRepository.class);
    when(blockingRepo.batchInsert(anyList()))
        .thenAnswer(
            inv -> {
              repoBlocked.await(5, TimeUnit.SECONDS);
              return ((List<?>) inv.getArgument(0)).size();
            });

    startServiceWithMock(blockingRepo, smallProps());
    assertEquals(0, service.queueDepth());

    for (int i = 0; i < 10; i++) {
      service.enqueue(makeQuote("DOGEUSDT", i + 1));
    }

    Thread.sleep(100);

    assertEquals(
        5, service.queueDepth(), "Queue should contain exactly 5 items while drainer is blocked");

    repoBlocked.countDown();

    Thread.sleep(FAST_FLUSH + 500);
    assertEquals(0, service.queueDepth(), "Queue should be completely empty after draining");
  }

  @Test
  void producerNeverBlocks() throws Exception {
    CountDownLatch repoBlocked = new CountDownLatch(1);
    QuoteRepository blockingRepo = mock(QuoteRepository.class);
    when(blockingRepo.batchInsert(anyList()))
        .thenAnswer(
            inv -> {
              repoBlocked.await(10, TimeUnit.SECONDS);
              return ((List<?>) inv.getArgument(0)).size();
            });

    PersistenceProperties p = smallProps();
    p.setQueueCapacity(10_000);
    p.setDropOldestThreshold(0.9);
    startServiceWithMock(blockingRepo, p);

    int threads = 8;
    int callsPerThread = 500;
    AtomicLong maxLatencyNs = new AtomicLong(0);
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch doneGate = new CountDownLatch(threads);

    for (int t = 0; t < threads; t++) {
      int threadIdx = t;
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  startGate.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                for (int i = 0; i < callsPerThread; i++) {
                  long start = System.nanoTime();
                  service.enqueue(makeQuote("COIN" + threadIdx, i + 1));
                  long elapsed = System.nanoTime() - start;
                  maxLatencyNs.updateAndGet(cur -> Math.max(cur, elapsed));
                }
                doneGate.countDown();
              });
    }

    startGate.countDown();
    assertTrue(doneGate.await(30, TimeUnit.SECONDS), "All producer threads should finish");

    long p99TargetNs = 50_000_000L;
    assertTrue(
        maxLatencyNs.get() < p99TargetNs,
        "Max offer() latency was "
            + (maxLatencyNs.get() / 1_000_000)
            + " ms, expected < 50 ms (proving non-blocking behaviour)");

    repoBlocked.countDown();
    service.shutdown();
  }

  @Test
  void shutdownTimeoutRespected() throws Exception {
    CountDownLatch repoBlocked = new CountDownLatch(1);
    QuoteRepository blockingRepo = mock(QuoteRepository.class);
    when(blockingRepo.batchInsert(anyList()))
        .thenAnswer(
            inv -> {
              repoBlocked.await(30, TimeUnit.SECONDS);
              return 0;
            });

    PersistenceProperties p = smallProps();
    p.setShutdownTimeoutMs(200);
    startServiceWithMock(blockingRepo, p);

    for (int i = 0; i < SMALL_BATCH * 3; i++) {
      service.enqueue(makeQuote("ADAUSDT", i + 1));
    }

    Thread.sleep(100);

    long start = System.nanoTime();
    service.shutdown();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertTrue(
        elapsedMs < 2 * 200,
        "shutdown() should return within 2x the timeout (400 ms), but took " + elapsedMs + " ms");

    assertTrue(service.queueDepth() > 0, "Partial drain expected — some items should remain");

    repoBlocked.countDown();
  }

  @Test
  void sustains500rps() throws Exception {
    AtomicInteger totalInserted = new AtomicInteger(0);
    QuoteRepository countingRepo = mock(QuoteRepository.class);
    when(countingRepo.batchInsert(anyList()))
        .thenAnswer(
            inv -> {
              int count = ((List<?>) inv.getArgument(0)).size();
              totalInserted.addAndGet(count);
              return count;
            });

    PersistenceProperties p = smallProps();
    p.setQueueCapacity(10_000);
    p.setBatchSize(50);
    p.setFlushMs(100);
    startServiceWithMock(countingRepo, p);

    int totalQuotes = 2_500;
    long start = System.currentTimeMillis();
    for (int i = 0; i < totalQuotes; i++) {
      service.enqueue(makeQuote("LINKUSDT", i + 1));
      if ((i + 1) % 500 == 0 && i < totalQuotes - 1) {
        long elapsed = System.currentTimeMillis() - start;
        long sleepMs = 1000 - elapsed;
        if (sleepMs > 0) {
          Thread.sleep(sleepMs);
        }
        start = System.currentTimeMillis();
      }
    }

    for (int wait = 0; wait < 50 && totalInserted.get() < totalQuotes; wait++) {
      Thread.sleep(200);
    }

    assertTrue(
        totalInserted.get() >= totalQuotes,
        "Expected at least "
            + totalQuotes
            + " persisted, got "
            + totalInserted.get()
            + " (drops="
            + service.droppedCount()
            + ")");
    assertEquals(0, service.droppedCount(), "No drops expected at 500 rps with 10k queue");
  }
}
