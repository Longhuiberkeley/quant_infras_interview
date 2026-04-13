package com.quant.binancequotes.config;

import java.net.Proxy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Binance WebSocket connection.
 *
 * <p>Bound to the {@code binance.ws} prefix in {@code application.yml}. The base URL defaults to
 * the USDT-M Perpetuals endpoint ({@code wss://fstream.binance.com}); see {@code
 * docs/design_decisions.md} DD-10.
 */
@ConfigurationProperties("binance.ws")
public class BinanceProperties {

  /** Base WebSocket URL, e.g. {@code wss://fstream.binance.com}. */
  private String baseUrl = "wss://fstream.binance.com";

  /** Optional HTTP proxy (e.g. {@code http://proxy.corp:3128}). */
  private Proxy proxy;

  /**
   * Staleness threshold in milliseconds. If no message arrives for a symbol within this window, the
   * stream is considered stale.
   */
  private long stalenessThresholdMs = 30_000;

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public Proxy getProxy() {
    return proxy;
  }

  public void setProxy(Proxy proxy) {
    this.proxy = proxy;
  }

  public long getStalenessThresholdMs() {
    return stalenessThresholdMs;
  }

  public void setStalenessThresholdMs(long stalenessThresholdMs) {
    this.stalenessThresholdMs = stalenessThresholdMs;
  }
}
