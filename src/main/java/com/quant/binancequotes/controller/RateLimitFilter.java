package com.quant.binancequotes.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  private final Semaphore semaphore;
  private final long acquireTimeoutMs;

  public RateLimitFilter(
      @Value("${app.rate-limit.max-concurrent:100}") int maxConcurrent,
      @Value("${app.rate-limit.acquire-timeout-ms:500}") long acquireTimeoutMs) {
    this.semaphore = new Semaphore(maxConcurrent);
    this.acquireTimeoutMs = acquireTimeoutMs;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (!request.getRequestURI().startsWith("/api/")) {
      filterChain.doFilter(request, response);
      return;
    }

    boolean acquired = false;
    try {
      acquired = semaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      sendTooManyRequests(response);
      return;
    }

    if (!acquired) {
      sendTooManyRequests(response);
      return;
    }

    try {
      filterChain.doFilter(request, response);
    } finally {
      semaphore.release();
    }
  }

  private void sendTooManyRequests(HttpServletResponse response) throws IOException {
    log.debug("Rate limit exceeded — returning 429");
    response.setStatus(429);
    response.setContentType("application/json");
    response
        .getWriter()
        .write(objectMapper.writeValueAsString(Map.of("error", "Too many requests")));
  }

  int availablePermits() {
    return semaphore.availablePermits();
  }
}
