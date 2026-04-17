package com.quant.binancequotes.websocket;

import com.quant.binancequotes.config.AppProperties;
import com.quant.binancequotes.config.BinanceProperties;
import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.service.BatchPersistenceService;
import com.quant.binancequotes.service.QuoteService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OkHttp-based WebSocket client that ingests Binance USDT-M Perpetuals {@code @bookTicker} streams.
 *
 * <p>Builds a targeted combined-stream URL from the configured symbol list (DD-9), implements an
 * exponential backoff reconnect loop (1 s → 60 s cap) guarded by atomic flags, and exposes
 * per-symbol and fleet-max freshness-lag Micrometer gauges (DD-11).
 *
 * <p><b>Backoff reset policy:</b> the backoff delay resets to 1 s only after the <em>first inbound
 * message</em> post-open — not on {@code onOpen} itself. This prevents a fast-reconnect /
 * immediate-close loop from exhausting the backoff sequence.
 *
 * <p><b>Graceful shutdown:</b> {@link #shutdown()} ({@code @PreDestroy}) sets {@code shuttingDown =
 * true}, cancels any pending reconnect, sends a standard 1000 close frame, and awaits {@code
 * onClosed} with a bounded timeout (3 s). This runs before {@link
 * BatchPersistenceService#shutdown()} so the drainer sees a closed input before flushing.
 * Destruction order is guaranteed by Spring's reverse-initialization-order semantics.
 *
 * @see <a href="docs/design_decisions.md">DD-6, DD-9, DD-10, DD-11</a>
 */
@Component
public class BinanceWebSocketClient extends WebSocketListener {

  private static final Logger log = LoggerFactory.getLogger(BinanceWebSocketClient.class);

  /** Minimum reconnect backoff delay (1 second). */
  private static final long MIN_BACKOFF_MS = 1_000L;

  /** Maximum reconnect backoff delay (60 seconds). */
  private static final long MAX_BACKOFF_MS = 60_000L;

  /** Backoff multiplier (exponential: 1 → 2 → 4 → …). */
  private static final double BACKOFF_MULTIPLIER = 2.0;

  /** Timeout waiting for onClosed callback during shutdown (3 seconds). */
  private static final long SHUTDOWN_ON_CLOSED_TIMEOUT_MS = 3_000L;

  // ── Dependencies ──────────────────────────────────────────────────────────

  private final AppProperties appProperties;
  private final BinanceProperties binanceProperties;
  private final QuoteService quoteService;
  private final BatchPersistenceService batchPersistenceService;
  private final QuoteMessageParser parser;

  // ── Networking ────────────────────────────────────────────────────────────

  private volatile OkHttpClient okHttpClient;
  private volatile WebSocket webSocket;

  // ── State flags ───────────────────────────────────────────────────────────

  /** Guards against duplicate reconnect scheduling. CAS-based. */
  private final AtomicBoolean reconnecting = new AtomicBoolean(false);

  /** Set to true during @PreDestroy; prevents any further reconnect scheduling. */
  private volatile boolean shuttingDown = false;

  /** True between onOpen and onClosed — used by the health indicator. */
  private volatile boolean connected = false;

  /** True between onOpen and the first valid message. */
  private volatile boolean awaitingFirstMessage = false;

  /** Latch awaited by shutdown() after sending close frame. */
  private final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch>
      closedLatch = new java.util.concurrent.atomic.AtomicReference<>();

  // ── Backoff state ─────────────────────────────────────────────────────────

  private volatile long currentBackoffMs = MIN_BACKOFF_MS;
  private volatile ScheduledFuture<?> reconnectTask;
  private final ScheduledExecutorService scheduler;

  // ── Lag metrics (DD-11) ──────────────────────────────────────────────────

  /** Per-symbol lag values, updated on the hot path with zero allocation. */
  private final ConcurrentHashMap<String, AtomicLong> lastEventTimeBySymbol;

  /**
   * Constructs the client and registers Micrometer gauges.
   *
   * <p>Does <em>not</em> connect yet — that happens in {@link #start()}.
   */
  public BinanceWebSocketClient(
      AppProperties appProperties,
      BinanceProperties binanceProperties,
      QuoteService quoteService,
      BatchPersistenceService batchPersistenceService,
      QuoteMessageParser parser,
      MeterRegistry meterRegistry) {
    this.appProperties = appProperties;
    this.binanceProperties = binanceProperties;
    this.quoteService = quoteService;
    this.batchPersistenceService = batchPersistenceService;
    this.parser = parser;
    this.lastEventTimeBySymbol = new ConcurrentHashMap<>();

    // Per-symbol lag gauge registrations
    List<String> symbols = appProperties.getSymbols();
    for (String symbol : symbols) {
      lastEventTimeBySymbol.putIfAbsent(symbol, new AtomicLong(0L));
      Gauge.builder(
              "binance.quote.lag.millis",
              lastEventTimeBySymbol,
              m -> {
                long last = m.get(symbol).get();
                return last == 0L ? Double.NaN : System.currentTimeMillis() - last;
              })
          .tag("symbol", symbol)
          .register(meterRegistry);
    }

    // Fleet-max gauge
    Gauge.builder(
            "binance.quote.lag.max.millis",
            lastEventTimeBySymbol,
            m -> {
              long maxLag =
                  m.values().stream()
                      .mapToLong(AtomicLong::get)
                      .filter(t -> t > 0L)
                      .map(t -> System.currentTimeMillis() - t)
                      .max()
                      .orElse(-1L);
              return maxLag == -1L ? Double.NaN : (double) maxLag;
            })
        .register(meterRegistry);

    // Scheduler for reconnect backoff (single-threaded, only used for scheduling retries)
    this.scheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "ws-reconnect-scheduler");
              t.setDaemon(true);
              return t;
            });
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  /**
   * Builds the combined-stream URL and initiates the WebSocket connection. Called automatically at
   * application startup via {@code @PostConstruct}.
   */
  @jakarta.annotation.PostConstruct
  public void start() {
    String combinedUrl = buildCombinedStreamUrl();
    log.info("Connecting to Binance WebSocket: {}", combinedUrl);

    OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
    if (binanceProperties.toProxy() != null) {
      clientBuilder.proxy(binanceProperties.toProxy());
    }
    this.okHttpClient = clientBuilder.build();

    Request request = new Request.Builder().url(combinedUrl).build();
    closedLatch.set(new java.util.concurrent.CountDownLatch(1));
    this.webSocket = okHttpClient.newWebSocket(request, this);
  }

  /**
   * Graceful shutdown: sets the shuttingDown flag, cancels any pending reconnect, sends a standard
   * 1000 close frame, and awaits {@code onClosed} with a bounded timeout.
   *
   * <p>This bean depends on {@link BatchPersistenceService} via constructor injection. Spring
   * destroys beans in reverse-initialization order, so this {@code @PreDestroy} runs before the
   * drainer's.
   */
  @PreDestroy
  public void shutdown() {
    log.info("Initiating WebSocket shutdown…");
    shuttingDown = true;

    // Cancel any pending reconnect
    if (reconnectTask != null && !reconnectTask.isDone()) {
      reconnectTask.cancel(true);
      log.debug("Cancelled pending reconnect task");
    }

    // Send standard close frame if still connected
    WebSocket ws = webSocket;
    if (ws != null) {
      ws.close(1000, "application shutdown");
    }

    // Await onClosed callback
    java.util.concurrent.CountDownLatch latch = closedLatch.get();
    if (latch != null) {
      try {
        boolean awaited = latch.await(SHUTDOWN_ON_CLOSED_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!awaited) {
          log.warn(
              "onClosed callback did not complete within {} ms — proceeding with shutdown",
              SHUTDOWN_ON_CLOSED_TIMEOUT_MS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Interrupted while awaiting onClosed callback");
      }
    }

    // Shut down the reconnect scheduler
    scheduler.shutdownNow();

    log.info("WebSocket shutdown complete");
  }

  // ── WebSocketListener callbacks ───────────────────────────────────────────

  @Override
  public void onOpen(WebSocket webSocket, Response response) {
    if (this.webSocket != null && webSocket != this.webSocket) {
      return;
    }
    log.info("WebSocket connected — {}", response.request().url());
    connected = true;
    awaitingFirstMessage = true;
    reconnecting.set(false);
  }

  @Override
  public void onMessage(WebSocket webSocket, String text) {
    // Parse the message
    var maybeQuote = parser.parse(text);
    if (maybeQuote.isEmpty()) {
      return;
    }
    Quote quote = maybeQuote.get();

    if (awaitingFirstMessage) {
      awaitingFirstMessage = false;
      currentBackoffMs = MIN_BACKOFF_MS;
    }

    // Update last event time for dynamic lag gauge
    lastEventTimeBySymbol
        .computeIfAbsent(quote.symbol(), k -> new AtomicLong(0L))
        .set(quote.eventTime());

    // Route to in-memory store and persistence queue
    quoteService.update(quote);
    batchPersistenceService.enqueue(quote);
  }

  @Override
  public void onMessage(WebSocket webSocket, ByteString bytes) {
    log.warn("Received binary message — ignoring (expected text only)");
  }

  @Override
  public void onClosed(WebSocket webSocket, int code, String reason) {
    if (this.webSocket != null && webSocket != this.webSocket) {
      return;
    }
    log.info("WebSocket closed — code={}, reason={}", code, reason);
    connected = false;
    java.util.concurrent.CountDownLatch latch = closedLatch.get();
    if (latch != null) {
      latch.countDown();
    }
    if (!shuttingDown) {
      increaseBackoff();
      scheduleReconnect();
    }
  }

  @Override
  public void onFailure(WebSocket webSocket, Throwable t, Response response) {
    if (this.webSocket != null && webSocket != this.webSocket) {
      return;
    }
    log.warn("WebSocket failure — {}", response != null ? response.request().url() : "unknown", t);
    connected = false;
    java.util.concurrent.CountDownLatch latch = closedLatch.get();
    if (latch != null) {
      latch.countDown();
    }
    if (!shuttingDown) {
      increaseBackoff();
      scheduleReconnect();
    }
  }

  // ── Reconnect logic ───────────────────────────────────────────────────────

  /**
   * Schedules a reconnect attempt with exponential backoff. Uses CAS on {@link #reconnecting} to
   * ensure exactly one reconnect is scheduled even under a storm of {@code onClosed}/{@code
   * onFailure} callbacks.
   */
  private void scheduleReconnect() {
    if (!reconnecting.compareAndSet(false, true)) {
      log.debug("Reconnect already scheduled — skipping");
      return;
    }
    long delay = currentBackoffMs;
    log.info("Scheduling reconnect in {} ms", delay);
    reconnectTask =
        scheduler.schedule(
            () -> {
              try {
                if (!shuttingDown) {
                  log.info("Attempting reconnect…");
                  this.webSocket = null;
                  String combinedUrl = buildCombinedStreamUrl();
                  Request request = new Request.Builder().url(combinedUrl).build();
                  closedLatch.set(new java.util.concurrent.CountDownLatch(1));
                  webSocket = okHttpClient.newWebSocket(request, this);
                } else {
                  reconnecting.set(false);
                }
              } catch (Exception e) {
                log.error("Reconnect attempt failed", e);
                reconnecting.set(false);
                increaseBackoff();
                scheduleReconnect();
              }
            },
            delay,
            TimeUnit.MILLISECONDS);
  }

  /**
   * Increases the backoff delay exponentially, capped at {@link #MAX_BACKOFF_MS}. Called from
   * {@link #onFailure} and when reconnect itself fails.
   */
  private void increaseBackoff() {
    currentBackoffMs = (long) Math.min(currentBackoffMs * BACKOFF_MULTIPLIER, MAX_BACKOFF_MS);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /**
   * Builds the targeted combined-stream URL from the configured symbol list.
   *
   * <p>Example: {@code wss://fstream.binance.com/stream?streams=btcusdt @bookTicker/ethusdt
   * @bookTicker/…}
   *
   * <p>Symbols are lowercased because Binance requires lowercase stream names.
   */
  private String buildCombinedStreamUrl() {
    String streams =
        appProperties.getSymbols().stream()
            .map(String::toLowerCase)
            .map(s -> s + "@bookTicker")
            .reduce((a, b) -> a + "/" + b)
            .orElseThrow(() -> new IllegalStateException("app.symbols must not be empty"));
    return binanceProperties.getBaseUrl() + "/stream?streams=" + streams;
  }

  // ── Visibility for tests / health indicator ───────────────────────────────

  /** Returns whether the WebSocket is currently considered connected. */
  public boolean isConnected() {
    return connected;
  }

  /** Returns the per-symbol last event time map (exposed for health indicator and testing). */
  public Map<String, AtomicLong> getLastEventTimeBySymbol() {
    return lastEventTimeBySymbol;
  }

  /** Returns the current reconnect backoff delay in ms. */
  public long getCurrentBackoffMs() {
    return currentBackoffMs;
  }

  /** Returns the reconnecting flag state (for testing). */
  public boolean isReconnecting() {
    return reconnecting.get();
  }

  /** Returns whether the client is in shutdown mode. */
  public boolean isShuttingDown() {
    return shuttingDown;
  }

  /** Exposes the scheduler for test shutdown. */
  public ScheduledExecutorService getScheduler() {
    return scheduler;
  }

  /** Exposes the OkHttpClient for test inspection. */
  OkHttpClient getOkHttpClient() {
    return okHttpClient;
  }

  /** Exposes the WebSocket for test inspection. */
  WebSocket getWebSocket() {
    return webSocket;
  }

  /** Exposes the closed latch for test inspection. */
  java.util.concurrent.CountDownLatch getClosedLatch() {
    return closedLatch.get();
  }
}
