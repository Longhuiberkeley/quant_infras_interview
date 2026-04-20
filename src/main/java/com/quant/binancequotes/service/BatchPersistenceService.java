package com.quant.binancequotes.service;

import com.quant.binancequotes.config.PersistenceProperties;
import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.repository.QuoteRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Service;

/**
 * Async batched persistence service that drains a bounded {@link LinkedBlockingQueue} of {@link
 * Quote}s on a single named platform thread and flushes them to PostgreSQL in batches.
 *
 * <p><b>Backpressure:</b> when the queue exceeds {@code dropOldestThreshold * queueCapacity}, the
 * oldest entry is silently discarded and a {@code WARN} is logged. This is non-blocking {@code
 * offer}-based — the caller (WebSocket thread) never waits.
 *
 * <p><b>Graceful shutdown:</b> {@link #shutdown()} drains remaining queue items with a bounded
 * timeout before the application context closes.
 *
 * <p>See {@code docs/design_decisions.md} DD-6 (no blocking on WS thread) and DD-3 (write-only
 * persistence).
 */
@Service
public class BatchPersistenceService {

  private static final Logger log = LoggerFactory.getLogger(BatchPersistenceService.class);

  private final LinkedBlockingQueue<Quote> queue;
  private final QuoteRepository quoteRepository;
  private final int batchSize;
  private final long flushMs;
  private final int dropOldestAt;
  private final long shutdownTimeoutMs;
  private final int queueCapacity;
  private final Counter droppedCounter;
  private volatile boolean shuttingDown = false;
  private Thread drainerThread;

  public BatchPersistenceService(
      PersistenceProperties props, QuoteRepository quoteRepository, MeterRegistry meterRegistry) {
    this.quoteRepository = quoteRepository;
    this.batchSize = props.getBatchSize();
    this.flushMs = props.getFlushMs();
    this.shutdownTimeoutMs = props.getShutdownTimeoutMs();
    this.queueCapacity = props.getQueueCapacity();
    this.queue = new LinkedBlockingQueue<>(this.queueCapacity);
    this.dropOldestAt = (int) (this.queueCapacity * props.getDropOldestThreshold());
    this.droppedCounter = meterRegistry.counter("binance.quotes.dropped.total");
  }

  @PostConstruct
  void startDrainer() {
    this.drainerThread = Thread.ofPlatform().name("quote-batch-writer").start(this::drainLoop);
  }

  /**
   * Enqueues a quote for batch persistence. Non-blocking: if the queue is full, the oldest entry is
   * dropped and a {@code WARN} is logged.
   *
   * @param quote the quote to persist
   */
  public void enqueue(Quote quote) {
    if (shuttingDown) {
      log.debug("Dropping quote — shutting down: {}", quote.symbol());
      return;
    }
    boolean offered = queue.offer(quote);
    if (!offered) {
      // Queue full — drop oldest (backpressure)
      Quote dropped = queue.poll();
      if (dropped != null) {
        droppedCounter.increment();
        log.warn(
            "Persistence queue full (capacity={}). Dropped oldest: {}. Total dropped: {}",
            queueCapacity,
            dropped.symbol(),
            droppedCounter.count());
      }
      // Retry the offer now that space was freed
      boolean retryOk = queue.offer(quote);
      if (!retryOk) {
        log.error("Failed to enqueue quote after dropping oldest: {}", quote.symbol());
      }
    } else if (queue.size() >= dropOldestAt) {
      log.warn(
          "Persistence queue at {}% capacity ({} / {}). Drop-oldest backpressure will fire on next"
              + " overflow.",
          (queue.size() * 100) / queueCapacity, queue.size(), queueCapacity);
    }
  }

  /** Returns the current queue depth (used by the health indicator). */
  public int queueDepth() {
    return queue.size();
  }

  /** Returns total number of quotes dropped due to backpressure. */
  public long droppedCount() {
    return (long) droppedCounter.count();
  }

  /** Returns the drainer thread (for observability / testing). */
  Thread drainerThread() {
    return drainerThread;
  }

  /**
   * Signals the drainer to stop after draining remaining items. Called by {@code @PreDestroy} on
   * the application context.
   */
  @PreDestroy
  public void shutdown() {
    log.info("Initiating persistence shutdown — draining {} queued quotes…", queue.size());
    shuttingDown = true;
    drainerThread.interrupt();
    try {
      drainerThread.join(shutdownTimeoutMs);
      log.info(
          "Persistence drainer stopped. Remaining in queue: {}. Total dropped: {}",
          queue.size(),
          droppedCounter.count());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while waiting for persistence drainer to stop");
    }
  }

  // ── Internal drain loop ──────────────────────────────────────────────────

  private void drainLoop() {
    List<Quote> batch = new ArrayList<>(batchSize);
    long lastFlush = System.currentTimeMillis();

    while (!shuttingDown || !queue.isEmpty()) {
      try {
        Quote q = queue.poll(flushMs, TimeUnit.MILLISECONDS);
        if (q != null) {
          batch.add(q);
        }
        long now = System.currentTimeMillis();
        if (!batch.isEmpty() && (batch.size() >= batchSize || (now - lastFlush) >= flushMs)) {
          flush(batch);
          batch = new ArrayList<>(batchSize);
          lastFlush = now;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    // Final drain: flush whatever is left
    if (!batch.isEmpty()) {
      flush(batch);
    }
    // Drain any stragglers that arrived after the loop exit check
    List<Quote> remainder = new ArrayList<>(batchSize);
    queue.drainTo(remainder);
    if (!remainder.isEmpty()) {
      flush(remainder);
    }
  }

  private static final long RETRY_BATCH_INITIAL_BACKOFF_MS = 1_000L;
  private static final long RETRY_BATCH_MAX_BACKOFF_MS = 30_000L;
  private static final int RETRY_BATCH_MAX_ATTEMPTS = 5;

  private void flush(List<Quote> batch) {
    boolean interrupted = Thread.interrupted();
    try {
      int inserted = quoteRepository.batchInsert(batch);
      log.debug("Persisted {}/{} quotes", inserted, batch.size());
    } catch (Exception e) {
      if (isConnectionException(e)) {
        log.warn(
            "Connection-class exception persisting batch of {}. Retrying whole batch with backoff.",
            batch.size(),
            e);
        retryWholeBatch(batch);
      } else {
        log.error("Failed to persist batch of {}. Will retry individually.", batch.size(), e);
        retryOneByOne(batch);
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private boolean isConnectionException(Exception e) {
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof SQLTransientConnectionException
          || cause instanceof SQLTransientException
          || cause instanceof CannotGetJdbcConnectionException) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private void retryWholeBatch(List<Quote> batch) {
    long backoffMs = RETRY_BATCH_INITIAL_BACKOFF_MS;
    for (int attempt = 1; attempt <= RETRY_BATCH_MAX_ATTEMPTS; attempt++) {
      try {
        Thread.sleep(backoffMs);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        log.warn("Interrupted during whole-batch retry — abandoning {} quotes", batch.size());
        return;
      }
      try {
        int inserted = quoteRepository.batchInsert(batch);
        log.info("Whole-batch retry attempt {} succeeded — persisted {} quotes", attempt, inserted);
        return;
      } catch (Exception e) {
        if (attempt < RETRY_BATCH_MAX_ATTEMPTS) {
          log.warn(
              "Whole-batch retry attempt {}/{} failed. Next backoff: {} ms",
              attempt,
              RETRY_BATCH_MAX_ATTEMPTS,
              backoffMs * 2,
              e);
          backoffMs = Math.min(backoffMs * 2, RETRY_BATCH_MAX_BACKOFF_MS);
        } else {
          log.error(
              "Whole-batch retry exhausted after {} attempts. Giving up on {} quotes.",
              RETRY_BATCH_MAX_ATTEMPTS,
              batch.size(),
              e);
        }
      }
    }
  }

  private void retryOneByOne(List<Quote> batch) {
    int maxRetries = 3;
    for (Quote q : batch) {
      int attempts = 0;
      boolean persisted = false;
      while (attempts < maxRetries && !persisted) {
        try {
          quoteRepository.batchInsert(List.of(q));
          persisted = true;
        } catch (Exception e) {
          attempts++;
          if (attempts >= maxRetries) {
            log.error("Giving up on quote after {} retries: {}", maxRetries, q.symbol(), e);
          } else {
            try {
              Thread.sleep(100L * attempts); // 100ms, 200ms, 300ms
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              return;
            }
          }
        }
      }
    }
  }
}
