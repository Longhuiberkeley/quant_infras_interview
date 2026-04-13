package com.quant.binancequotes.health;

import com.quant.binancequotes.websocket.BinanceWebSocketClient;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for the Binance WebSocket stream connection.
 *
 * <p>Reports {@code UP} when the WebSocket is connected and the maximum freshness lag is within the
 * staleness threshold, {@code DOWN} when disconnected, and {@code OUT_OF_SERVICE} when the
 * application is shutting down.
 */
@Component("binanceStream")
public class BinanceStreamHealthIndicator implements HealthIndicator {

  private final BinanceWebSocketClient webSocketClient;
  private final long stalenessThresholdMs;

  public BinanceStreamHealthIndicator(
      BinanceWebSocketClient webSocketClient,
      com.quant.binancequotes.config.BinanceProperties binanceProperties) {
    this.webSocketClient = webSocketClient;
    this.stalenessThresholdMs = binanceProperties.getStalenessThresholdMs();
  }

  @Override
  public Health health() {
    if (webSocketClient.isShuttingDown()) {
      return Health.outOfService().withDetail("reason", "application shutting down").build();
    }

    if (!webSocketClient.isConnected()) {
      return Health.down().withDetail("status", "disconnected").build();
    }

    // Check fleet-max lag against staleness threshold
    Map<String, AtomicLong> lastEventTimeBySymbol = webSocketClient.getLastEventTimeBySymbol();
    long now = System.currentTimeMillis();
    long maxLag =
        lastEventTimeBySymbol.values().stream()
            .mapToLong(AtomicLong::get)
            .filter(t -> t > 0L)
            .map(t -> now - t)
            .max()
            .orElse(0L);

    if (maxLag > stalenessThresholdMs) {
      return Health.down()
          .withDetail("status", "stale")
          .withDetail("maxLagMillis", maxLag)
          .withDetail("thresholdMillis", stalenessThresholdMs)
          .build();
    }

    return Health.up().withDetail("status", "connected").withDetail("maxLagMillis", maxLag).build();
  }
}
