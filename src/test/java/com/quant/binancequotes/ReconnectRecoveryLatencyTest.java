package com.quant.binancequotes;

import static org.assertj.core.api.Assertions.assertThat;

import com.quant.binancequotes.config.AppProperties;
import com.quant.binancequotes.service.QuoteService;
import com.quant.binancequotes.websocket.BinanceWebSocketClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconnectRecoveryLatencyTest {

  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("test_quotes")
          .withUsername("test")
          .withPassword("test");

  static final MockWebServer mockWebServer = new MockWebServer();

  static final AtomicReference<WebSocket> serverWebSocket = new AtomicReference<>();

  static {
    postgres.start();
    try {
      mockWebServer.start();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to start MockWebServer", e);
    }
    mockWebServer.enqueue(webSocketUpgradeResponse());
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    registry.add("binance.ws.base-url", () -> "ws://localhost:" + mockWebServer.getPort());
    registry.add("persistence.flush-ms", () -> "50");
  }

  private static MockResponse webSocketUpgradeResponse() {
    return new MockResponse()
        .withWebSocketUpgrade(
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket webSocket, Response response) {
                serverWebSocket.set(webSocket);
              }

              @Override
              public void onMessage(WebSocket ws, String text) {}

              @Override
              public void onMessage(WebSocket ws, ByteString bytes) {}

              @Override
              public void onClosed(WebSocket ws, int code, String reason) {}

              @Override
              public void onFailure(WebSocket ws, Throwable t, Response response) {}
            });
  }

  @Autowired private QuoteService quoteService;
  @Autowired private AppProperties appProperties;
  @Autowired private BinanceWebSocketClient wsClient;

  private String bookTickerFrame(
      String symbol, long updateId, long eventTime, String bid, String ask) {
    return String.format(
        "{\"stream\":\"%s@bookTicker\","
            + "\"data\":{\"u\":%d,\"E\":%d,\"T\":%d,\"s\":\"%s\","
            + "\"b\":\"%s\",\"B\":\"1.0\",\"a\":\"%s\",\"A\":\"1.0\"}}",
        symbol.toLowerCase(), updateId, eventTime, eventTime - 3, symbol, bid, ask);
  }

  @BeforeAll
  void waitForConnection() {
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> wsClient.isConnected() && serverWebSocket.get() != null);

    String symbol = appProperties.getSymbols().get(0);
    long eventTime = System.currentTimeMillis();
    serverWebSocket
        .get()
        .send(bookTickerFrame(symbol, 1_000_000L, eventTime, "50000.00", "50001.00"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> quoteService.get(symbol).isPresent());
  }

  @Test
  void reconnectRecovery_measuresLatency() throws Exception {
    String symbol = appProperties.getSymbols().get(0);
    int requestsBefore = mockWebServer.getRequestCount();

    mockWebServer.enqueue(webSocketUpgradeResponse());

    long tDisconnect = System.nanoTime();

    serverWebSocket.set(null);
    WebSocket clientWs = wsClient.getWebSocket();
    assertThat(clientWs).isNotNull();
    clientWs.cancel();

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> !wsClient.isConnected());

    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> wsClient.isConnected() && serverWebSocket.get() != null);

    long reconnectUpdateId = 50_000_000L;
    long eventTime = System.currentTimeMillis();
    serverWebSocket
        .get()
        .send(bookTickerFrame(symbol, reconnectUpdateId, eventTime, "60000.00", "60001.00"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(10))
        .until(
            () ->
                quoteService.get(symbol).map(q -> q.updateId() >= reconnectUpdateId).orElse(false));

    long tRecovered = System.nanoTime();
    long recoveryMs = (tRecovered - tDisconnect) / 1_000_000;

    System.out.printf(
        "reconnectRecoveryLatency: %d ms (disconnect → first new quote in map)%n", recoveryMs);

    assertThat(recoveryMs)
        .as("Reconnect recovery should complete within 5 seconds (was %d ms)", recoveryMs)
        .isLessThan(5_000L);

    System.out.printf(
        "reconnect: requestsBefore=%d, requestsAfter=%d%n",
        requestsBefore, mockWebServer.getRequestCount());
  }
}
