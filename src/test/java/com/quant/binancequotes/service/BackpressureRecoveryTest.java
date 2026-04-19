package com.quant.binancequotes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.quant.binancequotes.config.PersistenceProperties;
import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.repository.QuoteRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class BackpressureRecoveryTest {

  private static final Logger log = LoggerFactory.getLogger(BackpressureRecoveryTest.class);

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
        System.currentTimeMillis());
  }

  private PersistenceProperties testProps() {
    PersistenceProperties p = new PersistenceProperties();
    p.setQueueCapacity(20);
    p.setBatchSize(5);
    p.setFlushMs(100);
    p.setShutdownTimeoutMs(5_000);
    p.setDropOldestThreshold(0.8);
    return p;
  }

  @AfterEach
  void tearDown() {
    if (service != null) {
      service.shutdown();
    }
  }

  @Test
  void backpressureRecovery_selfHealsAfterOutage() throws Exception {
    AtomicBoolean outage = new AtomicBoolean(true);
    AtomicInteger totalInserted = new AtomicInteger(0);

    QuoteRepository repo = mock(QuoteRepository.class);
    when(repo.batchInsert(anyList()))
        .thenAnswer(
            inv -> {
              if (outage.get()) {
                throw new RuntimeException("simulated DB outage");
              }
              int count = ((List<?>) inv.getArgument(0)).size();
              totalInserted.addAndGet(count);
              return count;
            });

    PersistenceProperties props = testProps();
    service = new BatchPersistenceService(props, repo, new SimpleMeterRegistry());
    service.startDrainer();

    Thread.sleep(200);

    int totalEnqueued = 100;
    for (int i = 0; i < totalEnqueued; i++) {
      service.enqueue(makeQuote("BTCUSDT", i + 1));
    }

    Thread.sleep(2_000);

    long droppedDuringOutage = service.droppedCount();
    log.info(
        "During outage: enqueued={}, dropped={}, queueDepth={}",
        totalEnqueued,
        droppedDuringOutage,
        service.queueDepth());

    assertThat(droppedDuringOutage)
        .as("Some quotes should have been dropped during outage")
        .isGreaterThan(0);

    long tRecoveryStart = System.nanoTime();
    outage.set(false);

    long deadline = System.currentTimeMillis() + 10_000;
    while (service.queueDepth() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }
    long drainTimeMs = (System.nanoTime() - tRecoveryStart) / 1_000_000;

    log.info(
        "After recovery: totalInserted={}, dropped={}, drainTimeMs={}ms, queueDepth={}",
        totalInserted.get(),
        service.droppedCount(),
        drainTimeMs,
        service.queueDepth());

    assertThat(service.queueDepth()).as("Queue should be empty after recovery").isEqualTo(0);

    assertThat(totalInserted.get())
        .as("Some quotes should be persisted after recovery")
        .isGreaterThan(0);

    int postRecoveryQuotes = 10;
    int insertedBeforePostRecovery = totalInserted.get();
    for (int i = 0; i < postRecoveryQuotes; i++) {
      service.enqueue(makeQuote("ETHUSDT", 10_000 + i));
    }

    Thread.sleep(500);

    int postRecoveryInserted = totalInserted.get() - insertedBeforePostRecovery;
    assertThat(postRecoveryInserted)
        .as(
            "Post-recovery quotes should be persisted (expected %d, got %d)",
            postRecoveryQuotes, postRecoveryInserted)
        .isGreaterThan(0);

    verify(repo, atLeastOnce()).batchInsert(anyList());

    log.info(
        "Recovery verified: drainTime={}ms, totalDropped={}, totalInserted={}, selfHealed=true",
        drainTimeMs,
        service.droppedCount(),
        totalInserted.get());
  }
}
