package com.quant.binancequotes.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the async batch persistence layer.
 *
 * <p>These values control the bounded queue that holds incoming quotes, how many are flushed in a
 * single batch, how often the drainer wakes, and how long we wait for in-flight writes during
 * graceful shutdown.
 */
@ConfigurationProperties("persistence")
@Validated
public class PersistenceProperties {

  /** Maximum number of quotes the queue can hold before backpressure kicks in. */
  @Min(100)
  private int queueCapacity = 10_000;

  /** Number of quotes accumulated before a single batch INSERT is issued. */
  @Min(1)
  @Max(2000)
  private int batchSize = 200;

  /** How often (ms) the drainer flushes regardless of batch-size (low-traffic guard). */
  @Positive private long flushMs = 500;

  /** Maximum time (ms) to wait for the queue to drain during {@code @PreDestroy}. */
  @Positive private long shutdownTimeoutMs = 5_000;

  /** Fraction of queue capacity at which drop-oldest backpressure fires (0.0–1.0). */
  @Min(0)
  @Max(1)
  private double dropOldestThreshold = 0.9;

  public int getQueueCapacity() {
    return queueCapacity;
  }

  public void setQueueCapacity(int queueCapacity) {
    this.queueCapacity = queueCapacity;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public long getFlushMs() {
    return flushMs;
  }

  public void setFlushMs(long flushMs) {
    this.flushMs = flushMs;
  }

  public long getShutdownTimeoutMs() {
    return shutdownTimeoutMs;
  }

  public void setShutdownTimeoutMs(long shutdownTimeoutMs) {
    this.shutdownTimeoutMs = shutdownTimeoutMs;
  }

  public double getDropOldestThreshold() {
    return dropOldestThreshold;
  }

  public void setDropOldestThreshold(double dropOldestThreshold) {
    this.dropOldestThreshold = dropOldestThreshold;
  }
}
