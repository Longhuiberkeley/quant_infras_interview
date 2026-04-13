package com.quant.binancequotes.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.quant.binancequotes.config.BinanceProperties;
import com.quant.binancequotes.websocket.BinanceWebSocketClient;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/**
 * Unit tests for {@link BinanceStreamHealthIndicator}.
 *
 * <p>Verifies the indicator reports correct status for each connection / staleness scenario.
 */
class BinanceStreamHealthIndicatorTest {

  private BinanceWebSocketClient mockClient;
  private BinanceProperties binanceProperties;
  private BinanceStreamHealthIndicator indicator;

  @BeforeEach
  void setUp() {
    mockClient = mock(BinanceWebSocketClient.class);
    binanceProperties = new BinanceProperties();
    binanceProperties.setStalenessThresholdMs(30_000L); // 30 seconds
    indicator = new BinanceStreamHealthIndicator(mockClient, binanceProperties);
  }

  @Test
  void up_whenConnectedAndRecentMessage() {
    when(mockClient.isConnected()).thenReturn(true);
    when(mockClient.isShuttingDown()).thenReturn(false);
    when(mockClient.getLastEventTimeBySymbol())
        .thenReturn(Map.of("BTCUSDT", new AtomicLong(System.currentTimeMillis())));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void down_whenDisconnected() {
    when(mockClient.isConnected()).thenReturn(false);
    when(mockClient.isShuttingDown()).thenReturn(false);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void down_whenConnectedButStale() {
    when(mockClient.isConnected()).thenReturn(true);
    when(mockClient.isShuttingDown()).thenReturn(false);
    // Set event time far enough in the past to exceed the 30 s threshold
    when(mockClient.getLastEventTimeBySymbol())
        .thenReturn(Map.of("BTCUSDT", new AtomicLong(System.currentTimeMillis() - 60_000L)));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("maxLagMillis");
    assertThat(health.getDetails()).containsKey("thresholdMillis");
  }

  @Test
  void outOfService_whenShuttingDown() {
    when(mockClient.isShuttingDown()).thenReturn(true);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
  }
}
