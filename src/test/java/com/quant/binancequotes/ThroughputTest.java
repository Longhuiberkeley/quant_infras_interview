package com.quant.binancequotes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.binancequotes.config.AppProperties;
import com.quant.binancequotes.service.QuoteService;
import com.quant.binancequotes.websocket.BinanceWebSocketClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Measures sustained throughput and end-to-end latency under realistic conditions.
 *
 * <p>Uses a real embedded Tomcat ({@code RANDOM_PORT}) with {@link TestRestTemplate} for HTTP
 * round-trips, Testcontainers PostgreSQL, and MockWebServer simulating Binance.
 *
 * <p>Test 1 blasts 10 000 WS frames and measures ingest throughput (quotes/sec). Test 2 measures
 * full-path latency (WS &rarr; parser &rarr; map &rarr; real HTTP GET &rarr; JSON parse) with
 * actual TCP overhead — not MockMvc.
 *
 * <p>SLO: sustained throughput &ge; 500 qps; p99 full-path latency &lt; 100 ms under load.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ThroughputTest {

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
  @Autowired private TestRestTemplate restTemplate;
  @Autowired private ObjectMapper objectMapper;
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

  @Test
  @Order(1)
  void sustainedThroughput_meetsSLO() throws Exception {
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> wsClient.isConnected() && serverWebSocket.get() != null);

    List<String> symbols = appProperties.getSymbols();
    int framesPerSymbol = 1_000;
    int totalFrames = symbols.size() * framesPerSymbol;
    long baseUpdateId = 60_000_000L;
    long eventTime = System.currentTimeMillis();

    WebSocket ws = serverWebSocket.get();
    assertThat(ws).as("server-side WebSocket must be live").isNotNull();

    long t0 = System.nanoTime();
    for (int i = 0; i < framesPerSymbol; i++) {
      for (int s = 0; s < symbols.size(); s++) {
        long updateId = baseUpdateId + i * symbols.size() + s;
        ws.send(bookTickerFrame(symbols.get(s), updateId, eventTime + i, "50000.00", "50001.00"));
      }
    }
    long tSendComplete = System.nanoTime();

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> {
              for (int s = 0; s < symbols.size(); s++) {
                long expectedId = baseUpdateId + (framesPerSymbol - 1) * symbols.size() + s;
                var maybe = quoteService.get(symbols.get(s));
                if (maybe.isEmpty() || maybe.get().updateId() < expectedId) {
                  return false;
                }
              }
              return true;
            });
    long tConfirmed = System.nanoTime();

    double totalDurationSec = (tConfirmed - t0) / 1_000_000_000.0;
    double sendDurationSec = (tSendComplete - t0) / 1_000_000_000.0;
    double throughput = totalFrames / totalDurationSec;
    double sendRate = totalFrames / sendDurationSec;

    System.out.printf(
        "throughput: sent=%d, sendRate=%.0f qps, confirmedRate=%.0f qps,"
            + " sendTime=%.3fs, confirmTime=%.3fs%n",
        totalFrames, sendRate, throughput, sendDurationSec, totalDurationSec);

    assertThat(throughput)
        .as("Sustained throughput should be >= 500 qps (observed %.0f qps)", throughput)
        .isGreaterThanOrEqualTo(500.0);

    String json = restTemplate.getForObject("/api/quotes", String.class);
    JsonNode root = objectMapper.readTree(json);
    assertThat(root.size()).isEqualTo(10);
    for (String symbol : symbols) {
      assertThat(root.get(symbol)).as("REST response missing symbol %s", symbol).isNotNull();
    }
  }

  @Test
  @Order(2)
  void latencyUnderLoad_withRealHttp() throws Exception {
    int iterations = 200;
    long[] latencies = new long[iterations];
    String symbol = "BTCUSDT";
    long baseUpdateId = 70_000_000L;

    WebSocket ws = serverWebSocket.get();
    assertThat(ws).as("server-side WebSocket must be live from Test 1").isNotNull();

    for (int i = 0; i < iterations; i++) {
      long expectedUpdateId = baseUpdateId + i;
      long eventTime = System.currentTimeMillis();
      String frame = bookTickerFrame(symbol, expectedUpdateId, eventTime, "50000.00", "50001.00");

      long t0 = System.nanoTime();
      ws.send(frame);

      long deadline = t0 + 5_000_000_000L;
      boolean observed = false;
      while (System.nanoTime() < deadline) {
        var maybe = quoteService.get(symbol);
        if (maybe.isPresent() && maybe.get().updateId() >= expectedUpdateId) {
          observed = true;
          break;
        }
        Thread.onSpinWait();
      }

      if (!observed) {
        throw new AssertionError(
            "latencyUnderLoad: QuoteService did not reflect updateId " + expectedUpdateId);
      }

      String body = restTemplate.getForObject("/api/quotes/" + symbol, String.class);
      JsonNode node = objectMapper.readTree(body);
      assertThat(node.get("updateId").asLong()).isGreaterThanOrEqualTo(expectedUpdateId);

      latencies[i] = System.nanoTime() - t0;
    }

    Arrays.sort(latencies);
    long p50 = latencies[iterations * 50 / 100];
    long p99 = latencies[iterations * 99 / 100];
    double p50Ms = p50 / 1_000_000.0;
    double p99Ms = p99 / 1_000_000.0;

    System.out.printf(
        "latencyUnderLoad (real HTTP): p50=%.2f ms, p99=%.2f ms (target p99 < 100 ms)%n",
        p50Ms, p99Ms);

    assertThat(p99)
        .as("p99 full-path latency (real HTTP) should be under 100 ms (observed %.2f ms)", p99Ms)
        .isLessThan(100_000_000L);
  }
}
