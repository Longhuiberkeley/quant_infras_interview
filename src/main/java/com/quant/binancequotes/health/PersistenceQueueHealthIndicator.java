package com.quant.binancequotes.health;

import com.quant.binancequotes.config.PersistenceProperties;
import com.quant.binancequotes.service.BatchPersistenceService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for the persistence queue.
 *
 * <p>Reports {@code UP} when the queue is accepting writes and the depth is below capacity.
 * Includes {@code queueDepth} and {@code droppedCount} as health details.
 */
@Component("persistenceQueue")
public class PersistenceQueueHealthIndicator implements HealthIndicator {

  private final BatchPersistenceService batchPersistenceService;
  private final int queueCapacity;

  public PersistenceQueueHealthIndicator(
      BatchPersistenceService batchPersistenceService, PersistenceProperties props) {
    this.batchPersistenceService = batchPersistenceService;
    this.queueCapacity = props.getQueueCapacity();
  }

  @Override
  public Health health() {
    int depth = batchPersistenceService.queueDepth();
    long dropped = batchPersistenceService.droppedCount();
    double utilization = depth / (double) queueCapacity;

    Health.Builder builder =
        new Health.Builder()
            .withDetail("queueDepth", depth)
            .withDetail("queueCapacity", queueCapacity)
            .withDetail("utilization", String.format("%.1f%%", utilization * 100))
            .withDetail("droppedCount", dropped);

    if (utilization > 0.95) {
      builder.down();
    } else if (utilization > 0.8) {
      builder.outOfService();
    } else {
      builder.up();
    }

    return builder.build();
  }
}
