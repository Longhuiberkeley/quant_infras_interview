package com.quant.binancequotes.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.quant.binancequotes.config.PersistenceProperties;
import com.quant.binancequotes.service.BatchPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/**
 * Unit tests for {@link PersistenceQueueHealthIndicator}.
 *
 * <p>Verifies the indicator reports correct status for each queue-depth scenario.
 */
class PersistenceQueueHealthIndicatorTest {

  private BatchPersistenceService mockService;
  private PersistenceProperties props;
  private PersistenceQueueHealthIndicator indicator;

  @BeforeEach
  void setUp() {
    mockService = mock(BatchPersistenceService.class);
    props = new PersistenceProperties();
    props.setQueueCapacity(10_000);
    indicator = new PersistenceQueueHealthIndicator(mockService, props);
  }

  @Test
  void up_whenQueueDepthBelow80Percent() {
    when(mockService.queueDepth()).thenReturn(5_000); // 50%
    when(mockService.droppedCount()).thenReturn(0L);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void outOfService_whenQueueDepthBetween80And95Percent() {
    when(mockService.queueDepth()).thenReturn(8_500); // 85%
    when(mockService.droppedCount()).thenReturn(0L);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
  }

  @Test
  void down_whenQueueDepthAbove95Percent() {
    when(mockService.queueDepth()).thenReturn(9_600); // 96%
    when(mockService.droppedCount()).thenReturn(10L);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void healthIncludesQueueDetails() {
    when(mockService.queueDepth()).thenReturn(5_000);
    when(mockService.droppedCount()).thenReturn(3L);

    var health = indicator.health();

    assertThat(health.getDetails()).containsKey("queueDepth");
    assertThat(health.getDetails()).containsKey("queueCapacity");
    assertThat(health.getDetails()).containsKey("utilization");
    assertThat(health.getDetails()).containsKey("droppedCount");
  }
}
