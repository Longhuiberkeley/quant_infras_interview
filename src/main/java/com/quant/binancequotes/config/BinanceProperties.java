package com.quant.binancequotes.config;

import java.net.InetSocketAddress;
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

  /** Optional HTTP proxy host. When set together with {@link #proxyPort}, enables proxying. */
  private String proxyHost;

  /** Optional HTTP proxy port. */
  private int proxyPort;

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

  public String getProxyHost() {
    return proxyHost;
  }

  public void setProxyHost(String proxyHost) {
    this.proxyHost = proxyHost;
  }

  public int getProxyPort() {
    return proxyPort;
  }

  public void setProxyPort(int proxyPort) {
    this.proxyPort = proxyPort;
  }

  public long getStalenessThresholdMs() {
    return stalenessThresholdMs;
  }

  public void setStalenessThresholdMs(long stalenessThresholdMs) {
    this.stalenessThresholdMs = stalenessThresholdMs;
  }

  /**
   * Constructs a {@link Proxy} from the configured host and port, or returns {@code null} if no
   * proxy is configured.
   */
  public Proxy toProxy() {
    if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
      return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
    }
    return null;
  }
}
