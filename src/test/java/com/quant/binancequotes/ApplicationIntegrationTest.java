package com.quant.binancequotes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.binancequotes.config.AppProperties;
import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.service.QuoteService;
import com.quant.binancequotes.websocket.BinanceWebSocketClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;
import org.awaitility.Awaitility;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Full-stack integration test proving the end-to-end data flow:
 *
 * <p>WebSocket {@literal @}bookTicker frame &rarr; QuoteMessageParser &rarr; QuoteService
 * (ConcurrentHashMap) &rarr; REST API (MockMvc) &rarr; PostgreSQL (Testcontainers).
 *
 * <p>Uses OkHttp MockWebServer to simulate Binance. Both the PostgreSQL container and the
 * MockWebServer are started in a {@code static {}} initialiser block so their state is guaranteed
 * to be ready before Spring's {@code @DynamicPropertySource} suppliers are resolved — this avoids
 * extension-ordering races between {@code SpringExtension} and {@code TestcontainersExtension}.
 * Containers are cleaned up by Testcontainers' Ryuk sidecar on JVM exit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApplicationIntegrationTest {

  // ── Shared infra (started BEFORE Spring context) ─────────────────────────

  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("test_quotes")
          .withUsername("test")
          .withPassword("test");

  static final MockWebServer mockWebServer = new MockWebServer();

  /** Server-side WebSocket handle, captured inside the listener's {@code onOpen}. */
  static final AtomicReference<WebSocket> serverWebSocket = new AtomicReference<>();

  static {
    postgres.start();
    try {
      mockWebServer.start();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to start MockWebServer", e);
    }
    // Queue the initial WebSocket upgrade so BinanceWebSocketClient.@PostConstruct start()
    // (which runs during Spring context refresh) can complete the handshake synchronously.
    mockWebServer.enqueue(webSocketUpgradeResponse());
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    registry.add("binance.ws.base-url", () -> "ws://localhost:" + mockWebServer.getPort());
    // Faster flush cycle keeps the DB-assert loop of the happy path tight.
    registry.add("persistence.flush-ms", () -> "100");
  }

  /**
   * Builds a {@link MockResponse} that upgrades the next incoming request to a WebSocket and
   * publishes the server-side socket handle into {@link #serverWebSocket}.
   */
  private static MockResponse webSocketUpgradeResponse() {
    return new MockResponse()
        .withWebSocketUpgrade(
            new WebSocketListener() {
              @Override
              public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                serverWebSocket.set(webSocket);
              }

              @Override
              public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
                // Client doesn't send app-level messages.
              }

              @Override
              public void onMessage(@NotNull WebSocket ws, @NotNull ByteString bytes) {
                // ignore
              }

              @Override
              public void onClosed(@NotNull WebSocket ws, int code, @NotNull String reason) {
                // ignore
              }

              @Override
              public void onFailure(
                  @NotNull WebSocket ws, @NotNull Throwable t, Response response) {
                // ignore
              }
            });
  }

  // ── Spring-injected beans ────────────────────────────────────────────────

  @Autowired private QuoteService quoteService;
  @Autowired private MockMvc mockMvc;
  @Autowired private BinanceWebSocketClient wsClient;
  @Autowired private DataSource dataSource;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AppProperties appProperties;

  // ── Helpers ──────────────────────────────────────────────────────────────

  private String bookTickerFrame(
      String symbol, long updateId, long eventTime, String bid, String ask) {
    return String.format(
        "{\"stream\":\"%s@bookTicker\","
            + "\"data\":{\"u\":%d,\"E\":%d,\"T\":%d,\"s\":\"%s\","
            + "\"b\":\"%s\",\"B\":\"1.0\",\"a\":\"%s\",\"A\":\"1.0\"}}",
        symbol.toLowerCase(), updateId, eventTime, eventTime - 3, symbol, bid, ask);
  }

  private long countQuoteRows() throws Exception {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM quotes")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private void clearQuoteTable() throws Exception {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM quotes");
    }
  }

  // ── Test 1: Happy path — all 10 symbols flow through all sinks ───────────

  @Test
  @Order(1)
  void happyPath_allTenSymbols_flowThroughAllSinks() throws Exception {
    // Context startup already triggered the WS upgrade against MockWebServer.
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> wsClient.isConnected() && serverWebSocket.get() != null);

    clearQuoteTable();

    List<String> symbols = appProperties.getSymbols();
    long baseUpdateId = 10_000_000L;
    long eventTime = System.currentTimeMillis();

    WebSocket ws = serverWebSocket.get();
    for (int i = 0; i < symbols.size(); i++) {
      String symbol = symbols.get(i);
      ws.send(bookTickerFrame(symbol, baseUpdateId + i, eventTime + i, "50000.00", "50001.00"));
    }

    // ── Sink 1: QuoteService map ──
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> quoteService.all().size() == 10);

    Map<String, Quote> allQuotes = quoteService.all();
    assertThat(allQuotes).hasSize(10);
    for (String symbol : symbols) {
      Quote q = allQuotes.get(symbol);
      assertThat(q).as("Quote for %s", symbol).isNotNull();
      assertThat(q.bid().compareTo(BigDecimal.ZERO)).isPositive();
      assertThat(q.ask().compareTo(BigDecimal.ZERO)).isPositive();
      assertThat(q.bid().compareTo(q.ask())).isLessThanOrEqualTo(0);
    }

    // ── Sink 2: REST API via MockMvc ──
    String json =
        mockMvc
            .perform(MockMvcRequestBuilders.get("/api/quotes"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode root = objectMapper.readTree(json);
    assertThat(root.size()).isEqualTo(10);
    for (String symbol : symbols) {
      JsonNode node = root.get(symbol);
      assertThat(node).as("REST response missing symbol %s", symbol).isNotNull();
      assertThat(node.get("bid")).isNotNull();
      assertThat(node.get("ask")).isNotNull();
    }

    // ── Sink 3: PostgreSQL row count ──
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(countQuoteRows()).isGreaterThanOrEqualTo(10L));

    long finalRowCount = countQuoteRows();
    System.out.printf(
        "happyPath: map.size=%d, REST.size=%d, DB.count=%d%n",
        allQuotes.size(), root.size(), finalRowCount);
  }

  // ── Test 2: ingestLatencyUnder5ms ────────────────────────────────────────

  @Test
  @Order(2)
  void ingestLatencyUnder5ms() {
    int iterations = 1_000;
    long[] latencies = new long[iterations];
    String symbol = "BTCUSDT";
    long baseUpdateId = 20_000_000L;

    WebSocket ws = serverWebSocket.get();
    assertThat(ws).as("server-side WebSocket must be live from Test 1").isNotNull();

    for (int i = 0; i < iterations; i++) {
      long expectedUpdateId = baseUpdateId + i;
      long eventTime = System.currentTimeMillis();
      String frame = bookTickerFrame(symbol, expectedUpdateId, eventTime, "50000.00", "50001.00");

      long t0 = System.nanoTime();
      ws.send(frame);

      long deadline = t0 + 5_000_000_000L; // 5-s wall-clock safety
      boolean observed = false;
      while (System.nanoTime() < deadline) {
        var maybe = quoteService.get(symbol);
        if (maybe.isPresent() && maybe.get().updateId() >= expectedUpdateId) {
          observed = true;
          break;
        }
        Thread.onSpinWait();
      }
      long t1 = System.nanoTime();
      latencies[i] = t1 - t0;

      if (!observed) {
        throw new AssertionError(
            "iteration "
                + i
                + ": QuoteService did not reflect updateId "
                + expectedUpdateId
                + " within 5 s");
      }
    }

    Arrays.sort(latencies);
    long p50 = latencies[iterations * 50 / 100];
    long p99 = latencies[iterations * 99 / 100];
    double p50Us = p50 / 1_000.0;
    double p99Us = p99 / 1_000.0;

    System.out.printf(
        "ingestLatency: p50=%.2f us, p99=%.2f us (target p99 < 5000 us)%n", p50Us, p99Us);

    assertThat(p99)
        .as("p99 ingest latency should be under 5 ms (observed %.2f us)", p99Us)
        .isLessThan(5_000_000L);
  }

  // ── Test 3: reconnectAfterNetworkDrop ────────────────────────────────────

  @Test
  @Order(3)
  void reconnectAfterNetworkDrop() throws Exception {
    List<String> symbols = appProperties.getSymbols();
    int requestsBefore = mockWebServer.getRequestCount();

    // Enqueue the upgrade response that will serve the client's reconnect attempt.
    mockWebServer.enqueue(webSocketUpgradeResponse());

    // Sever the connection. We call cancel() on the client-side WebSocket to force an
    // abrupt TCP tear-down (IOException on the read thread → onFailure → scheduleReconnect).
    // This simulates a network drop — the MockWebServer stays alive (no port-reuse
    // flakiness). MockWebServer's server-side WebSocket doesn't support cancel() and its
    // graceful close() is not deterministic under load; cancelling from the client side
    // is the cleanest way to trigger the production reconnect path.
    serverWebSocket.set(null);
    WebSocket clientWs = wsClient.getWebSocket();
    assertThat(clientWs).as("client-side WebSocket must be live").isNotNull();
    clientWs.cancel();

    // Client detects disconnect.
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> !wsClient.isConnected());

    // Observable reconnect counter: MockWebServer request count increments when the
    // client opens a new HTTP request for the WS upgrade. Backoff ≈ 2 s after first
    // disconnect, so 15 s bounds the reconnect attempt comfortably.
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(100))
        .until(() -> mockWebServer.getRequestCount() > requestsBefore);
    int requestsAfter = mockWebServer.getRequestCount();

    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> wsClient.isConnected() && serverWebSocket.get() != null);

    WebSocket newWs = serverWebSocket.get();
    long eventTime = System.currentTimeMillis();
    for (int i = 0; i < symbols.size(); i++) {
      String symbol = symbols.get(i);
      newWs.send(bookTickerFrame(symbol, 30_000_000L + i, eventTime + i, "60000.00", "60001.00"));
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> postReconnectPricesLanded(symbols));

    Map<String, Quote> allQuotes = quoteService.all();
    assertThat(allQuotes).hasSize(10);
    for (String symbol : symbols) {
      Quote q = allQuotes.get(symbol);
      assertThat(q).as("Quote for %s", symbol).isNotNull();
      // Post-reconnect frames use 60000; Test 1 used 50000. Anything > 55000 proves we
      // read new data, not stale Test 1 data.
      assertThat(q.bid().compareTo(new BigDecimal("55000"))).isPositive();
    }

    System.out.printf(
        "reconnect: requestsBefore=%d, requestsAfter=%d, delta=%d%n",
        requestsBefore, requestsAfter, requestsAfter - requestsBefore);
  }

  private boolean postReconnectPricesLanded(List<String> symbols) {
    Map<String, Quote> snapshot = quoteService.all();
    if (snapshot.size() < symbols.size()) {
      return false;
    }
    BigDecimal threshold = new BigDecimal("55000");
    for (String s : symbols) {
      Quote q = snapshot.get(s);
      if (q == null || q.bid().compareTo(threshold) <= 0) {
        return false;
      }
    }
    return true;
  }

  // ── Test 4: Dev-profile boot without PostgreSQL ──────────────────────────

  /**
   * Nested test class verifying the application context loads under the {@code dev} profile with H2
   * (no PostgreSQL) and a deliberately unreachable WebSocket URL.
   */
  @SpringBootTest
  @ActiveProfiles("dev")
  @TestPropertySource(properties = "binance.ws.base-url=ws://localhost:1")
  static class DevProfileBootTest {

    @Autowired private ApplicationContext context;

    @Test
    void contextLoads() {
      // Successful context load is the assertion. The WS client will fail to connect to
      // ws://localhost:1 and schedule backoff retries, but that doesn't fail the context.
      // H2 is used automatically via application-dev.yml.
      assertThat(context).isNotNull();
    }
  }
}
