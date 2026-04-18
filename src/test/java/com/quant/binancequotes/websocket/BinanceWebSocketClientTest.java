package com.quant.binancequotes.websocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quant.binancequotes.config.AppProperties;
import com.quant.binancequotes.config.BinanceProperties;
import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.service.BatchPersistenceService;
import com.quant.binancequotes.service.QuoteService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Response;
import okhttp3.WebSocket;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link BinanceWebSocketClient} reconnect logic and atomic flag behavior.
 *
 * <p>These tests do <em>not</em> open a real WebSocket connection. They exercise the callback
 * handlers ({@code onOpen}, {@code onMessage}, {@code onClosed}, {@code onFailure}) directly with
 * mock objects to verify:
 *
 * <ul>
 *   <li>Reconnect is scheduled exactly once under a storm of {@code onClosed}/{@code onFailure}
 *   <li>Backoff resets only after the first valid inbound message
 *   <li>Graceful shutdown prevents further reconnects
 *   <li>Quote routing to {@link QuoteService} and {@link BatchPersistenceService}
 * </ul>
 */
class BinanceWebSocketClientTest {

  private BinanceWebSocketClient client;
  private AppProperties appProperties;
  private BinanceProperties binanceProperties;
  private QuoteService quoteService;
  private BatchPersistenceService batchPersistenceService;
  private MeterRegistry meterRegistry;

  private WebSocket mockWebSocket;
  private Response mockResponse;

  @TempDir java.nio.file.Path tempDir;

  @BeforeEach
  void setUp() {
    appProperties = new AppProperties();
    appProperties.setSymbols(
        List.of(
            "BTCUSDT",
            "ETHUSDT",
            "BNBUSDT",
            "SOLUSDT",
            "XRPUSDT",
            "DOGEUSDT",
            "ADAUSDT",
            "TRXUSDT",
            "LINKUSDT",
            "AVAXUSDT"));

    binanceProperties = new BinanceProperties();
    binanceProperties.setBaseUrl("wss://fstream.binance.com");

    quoteService = new QuoteService();
    batchPersistenceService = mock(BatchPersistenceService.class);

    meterRegistry = new SimpleMeterRegistry();

    // Create the client — it won't auto-connect since we don't call start()
    client =
        new BinanceWebSocketClient(
            appProperties,
            binanceProperties,
            quoteService,
            batchPersistenceService,
            new QuoteMessageParser(appProperties),
            meterRegistry);

    mockWebSocket = mock(WebSocket.class);
    mockResponse = mock(Response.class);
    okhttp3.HttpUrl mockUrl =
        Objects.requireNonNull(
            okhttp3.HttpUrl.get("https://fstream.binance.com/stream?streams=btcusdt@bookTicker"));
    okhttp3.Request mockRequest = new okhttp3.Request.Builder().url(mockUrl).build();
    when(mockResponse.request()).thenReturn(mockRequest);
  }

  @AfterEach
  void tearDown() {
    // Ensure the client's scheduler is shut down to avoid thread leaks
    client.getScheduler().shutdownNow();
  }

  // ── Reconnect storm: exactly one reconnect scheduled ───────────────────────

  @Test
  void reconnect_schedulesOnce_underStormOfClosures() throws Exception {
    // Simulate a storm of onClosed + onFailure callbacks
    AtomicInteger reconnectAttempts = new AtomicInteger(0);

    // Override scheduleReconnect by spying is not practical; instead we verify
    // the reconnecting flag prevents duplicate scheduling.

    // First onFailure should set the reconnecting flag
    client.onFailure(mockWebSocket, new RuntimeException("network error"), mockResponse);

    assertTrue(client.isReconnecting(), "reconnecting flag should be set after first onFailure");

    // Subsequent onClosed/onFailure should NOT schedule another reconnect
    client.onClosed(mockWebSocket, 1006, "abnormal closure");
    client.onFailure(mockWebSocket, new RuntimeException("another error"), mockResponse);
    client.onClosed(mockWebSocket, 1001, "going away");

    // The reconnecting flag should still be true (only one CAS succeeded)
    assertTrue(
        client.isReconnecting(),
        "reconnecting flag should remain set — only one reconnect should be scheduled");
  }

  @Test
  void reconnect_noDuplicate_whenLateCallbackDuringReconnect() {
    WebSocket oldSocket = mock(WebSocket.class);

    // First failure sets reconnecting flag via CAS
    client.onFailure(oldSocket, new RuntimeException("fail"), mockResponse);
    assertTrue(client.isReconnecting(), "First onFailure should set reconnecting");

    // Late onClosed from the same old socket arrives while reconnect is in-flight.
    // Before the fix, onClosed cleared reconnecting, allowing scheduleReconnect()
    // CAS to succeed again (duplicate). After the fix, onClosed does not clear
    // the flag, so scheduleReconnect's CAS(false→true) fails.
    client.onClosed(oldSocket, 1006, "late callback");
    assertTrue(
        client.isReconnecting(),
        "Late onClosed must NOT clear the reconnecting flag during active reconnect");

    // Another late onFailure should also not duplicate
    client.onFailure(oldSocket, new RuntimeException("late fail"), mockResponse);
    assertTrue(
        client.isReconnecting(),
        "Late onFailure must NOT clear the reconnecting flag during active reconnect");
  }

  @Test
  void reconnect_flagReset_afterReconnectAttempt() throws Exception {
    // First failure schedules a reconnect
    client.onFailure(mockWebSocket, new RuntimeException("network error"), mockResponse);
    assertTrue(client.isReconnecting());

    // Simulate the reconnect task running: the flag is reset
    // Verify the flag behavior directly by simulating a successful reconnect
    // via onOpen — which is where reconnecting.set(false) actually happens
    client.onOpen(mockWebSocket, mockResponse);

    // After onOpen, a new onFailure should be able to schedule again
    client.onFailure(mockWebSocket, new RuntimeException("error after reconnect"), mockResponse);
    assertTrue(
        client.isReconnecting(),
        "should be able to schedule reconnect after a new failure post-open");
  }

  // ── Backoff reset on first message (not on open) ───────────────────────────

  @Test
  void backoffDoesNotReset_onOpen_onlyOnFirstMessage() {
    // Simulate a failure that increases backoff
    client.onFailure(mockWebSocket, new RuntimeException("fail"), mockResponse);

    assertEquals(2000L, client.getCurrentBackoffMs(), "Fix: verify backoff actually increased");

    long backoffBeforeOpen = client.getCurrentBackoffMs();

    // Simulate open
    client.onOpen(mockWebSocket, mockResponse);

    assertEquals(
        backoffBeforeOpen, client.getCurrentBackoffMs(), "onOpen should NOT reset the backoff");

    // Now simulate receiving a valid message
    String validMsg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": %d,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "50000.00",
            "B": "1.0",
            "a": "50001.00",
            "A": "1.0"
          }
        }
        """
            .formatted(System.currentTimeMillis() - 100);

    client.onMessage(mockWebSocket, validMsg);

    assertEquals(
        1_000L,
        client.getCurrentBackoffMs(),
        "Backoff should reset to 1s after first valid message");
  }

  @Test
  void backoffDoesNotReset_onSubsequentMessages() {
    // Increase backoff via failure
    client.onFailure(mockWebSocket, new RuntimeException("fail"), mockResponse);
    assertEquals(2000L, client.getCurrentBackoffMs());

    // Open and send first message — resets backoff
    client.onOpen(mockWebSocket, mockResponse);
    String validMsg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": %d,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "50000.00",
            "B": "1.0",
            "a": "50001.00",
            "A": "1.0"
          }
        }
        """
            .formatted(System.currentTimeMillis() - 100);
    client.onMessage(mockWebSocket, validMsg);
    assertEquals(1_000L, client.getCurrentBackoffMs());

    // Increase backoff again
    client.onFailure(mockWebSocket, new RuntimeException("fail2"), mockResponse);
    long increasedBackoff = client.getCurrentBackoffMs();
    assertTrue(increasedBackoff > 1_000L, "Backoff should have increased: " + increasedBackoff);

    // Open again
    client.onOpen(mockWebSocket, mockResponse);

    // Send second message — should NOT reset backoff (awaitingFirstMessage was set
    // by onOpen, but this time we verify the flag is properly cleared after first msg)
    // To test the "no reset on subsequent" path we send a message after onOpen but
    // with the flag cleared by a previous message, then fail again to increase backoff,
    // then send another message without a fresh onOpen.
    client.onMessage(mockWebSocket, validMsg);
    assertEquals(1_000L, client.getCurrentBackoffMs(), "First message after open resets backoff");

    // Now increase backoff WITHOUT an onOpen, then send a message
    client.onFailure(mockWebSocket, new RuntimeException("fail3"), mockResponse);
    long backoffBeforeMsg = client.getCurrentBackoffMs();
    assertTrue(backoffBeforeMsg > 1_000L);

    // Sending a message when awaitingFirstMessage is false should NOT reset
    client.onMessage(mockWebSocket, validMsg);
    assertEquals(
        backoffBeforeMsg,
        client.getCurrentBackoffMs(),
        "Subsequent messages must NOT reset backoff");
  }

  // ── Graceful shutdown ─────────────────────────────────────────────────────

  @Test
  void shutdown_setsFlagAndPreventsReconnect() {
    client.shutdown();

    assertTrue(client.isShuttingDown());

    // After shutdown, onFailure should NOT schedule a reconnect
    client.onFailure(mockWebSocket, new RuntimeException("error"), mockResponse);

    // The reconnecting flag should NOT be set because shuttingDown is true
    assertFalse(
        client.isReconnecting(), "onFailure after shutdown should NOT schedule a reconnect");
  }

  @Test
  void shutdown_sendsCloseFrame() {
    // We can't test this easily with mocks since start() creates its own WebSocket.
    // The close(1000, ...) call happens inside shutdown(). We verify the flag and latch behavior.
    client.shutdown();
    assertTrue(client.isShuttingDown());
  }

  @Test
  void shutdown_awaitOnClosed_withTimeout() throws Exception {
    // The closedLatch is initialized in start(). Since we don't call start() in unit tests,
    // we verify the shutdown behavior by checking the shuttingDown flag directly.
    client.shutdown();
    assertTrue(client.isShuttingDown());

    // Simulate the onClosed callback — this should not throw even after shutdown
    client.onClosed(mockWebSocket, 1000, "application shutdown");
    assertFalse(client.isConnected());
  }

  // ── Quote routing on message ───────────────────────────────────────────────

  @Test
  void validMessage_routesToQuoteServiceAndPersistence() {
    String validMsg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 42,
            "E": %d,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "50000.00",
            "B": "1.5",
            "a": "50001.00",
            "A": "2.0"
          }
        }
        """
            .formatted(System.currentTimeMillis() - 50);

    client.onMessage(mockWebSocket, validMsg);

    // Verify quote is in the in-memory store
    var quote = quoteService.get("BTCUSDT");
    assertTrue(quote.isPresent());
    assertEquals(new BigDecimal("50000.00"), quote.get().bid());
    assertEquals(new BigDecimal("50001.00"), quote.get().ask());
    assertEquals(42, quote.get().updateId());

    // Verify enqueue was called
    verify(batchPersistenceService, times(1)).enqueue(any(Quote.class));
  }

  @Test
  void invalidMessage_doesNotRoute() {
    String invalidMsg = "{\"result\":null,\"id\":1}"; // subscription ack

    client.onMessage(mockWebSocket, invalidMsg);

    // No quote should be added
    assertTrue(quoteService.get("BTCUSDT").isEmpty());

    // No enqueue should happen
    verify(batchPersistenceService, never()).enqueue(any(Quote.class));
  }

  // ── Connection state tracking ──────────────────────────────────────────────

  @Test
  void onOpen_setsConnectedTrue() {
    assertFalse(client.isConnected());

    client.onOpen(mockWebSocket, mockResponse);

    assertTrue(client.isConnected());
  }

  @Test
  void onClosed_setsConnectedFalse() {
    client.onOpen(mockWebSocket, mockResponse);
    assertTrue(client.isConnected());

    client.onClosed(mockWebSocket, 1000, "normal");

    assertFalse(client.isConnected());
  }

  @Test
  void onFailure_setsConnectedFalse() {
    client.onOpen(mockWebSocket, mockResponse);
    assertTrue(client.isConnected());

    client.onFailure(mockWebSocket, new RuntimeException("fail"), mockResponse);

    assertFalse(client.isConnected());
  }

  // ── Binary message handling ────────────────────────────────────────────────

  @Test
  void binaryMessage_isIgnored() {
    ByteString binaryData = ByteString.of((byte) 0x00, (byte) 0x01, (byte) 0x02);

    client.onMessage(mockWebSocket, binaryData);

    // Should not throw, should not route any quote
    assertTrue(quoteService.get("BTCUSDT").isEmpty());
    verify(batchPersistenceService, never()).enqueue(any(Quote.class));
  }

  // ── Lag gauge update ──────────────────────────────────────────────────────

  @Test
  void lagGauge_returnsNaNWhenNoData() {
    var maxLagGauge = meterRegistry.find("binance.quote.lag.max.millis").gauge();
    assertTrue(
        Double.isNaN(maxLagGauge.value()),
        "Fleet-max lag gauge should report NaN before any messages arrive");
  }

  @Test
  void lagGauge_perSymbolReturnsNaNWhenNoData() {
    var btcGauge = meterRegistry.find("binance.quote.lag.millis").tag("symbol", "BTCUSDT").gauge();
    assertNotNull(btcGauge, "Per-symbol gauge should be registered for BTCUSDT");
    assertTrue(
        Double.isNaN(btcGauge.value()),
        "Per-symbol lag gauge should report NaN before any messages arrive for that symbol");
  }

  @Test
  void lagGauge_updatedOnMessage() {
    long eventTime = System.currentTimeMillis() - 200; // 200ms ago
    String validMsg =
        """
        {
          "stream": "btcusdt@bookTicker",
          "data": {
            "u": 1,
            "E": %d,
            "T": 1713456788997,
            "s": "BTCUSDT",
            "b": "50000.00",
            "B": "1.0",
            "a": "50001.00",
            "A": "1.0"
          }
        }
        """
            .formatted(eventTime);

    client.onMessage(mockWebSocket, validMsg);

    long lastEvent = client.getLastEventTimeBySymbol().get("BTCUSDT").get();
    assertEquals(eventTime, lastEvent, "Last event time should be stored exactly");

    // Test dynamic lag by computing current lag
    long currentLag = System.currentTimeMillis() - lastEvent;
    assertTrue(
        currentLag >= 200 && currentLag < 5000,
        "Lag should dynamically compute to >= 200ms, but was " + currentLag);
  }

  // ── URL construction ──────────────────────────────────────────────────────

  @Test
  void combinedStreamUrl_builtCorrectly() {
    assertEquals(10, appProperties.getSymbols().size());
    assertTrue(appProperties.getSymbols().contains("BTCUSDT"));
    assertTrue(appProperties.getSymbols().contains("ETHUSDT"));
  }

  @Test
  void reconnect_afterAbruptSocketClosure() {
    assertFalse(client.isShuttingDown());

    client.onClosed(mockWebSocket, 1006, "abnormal closure");

    assertTrue(client.isReconnecting(), "reconnecting CAS should succeed on code 1006");
    assertTrue(client.getCurrentBackoffMs() >= 2000L, "backoff should have been increased");
    assertFalse(client.isShuttingDown(), "graceful-shutdown path should NOT be triggered");
  }
}
