package com.quant.binancequotes.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimitFilterTest {

  private RateLimitFilter createFilter(int maxConcurrent, long timeoutMs) {
    return new RateLimitFilter(maxConcurrent, timeoutMs);
  }

  @Test
  void underLimit_passesThrough() throws Exception {
    RateLimitFilter filter = createFilter(10, 100);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getRequestURI()).thenReturn("/api/quotes/BTCUSDT");

    filter.doFilterInternal(req, resp, chain);

    verify(chain).doFilter(req, resp);
    assertThat(filter.availablePermits()).isEqualTo(10);
  }

  @Test
  void nonApiPaths_bypassFilter() throws Exception {
    RateLimitFilter filter = createFilter(1, 100);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getRequestURI()).thenReturn("/actuator/health");

    filter.doFilterInternal(req, resp, chain);

    verify(chain).doFilter(req, resp);
  }

  @Test
  void overLimit_returns429() throws Exception {
    RateLimitFilter filter = createFilter(1, 50);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRequestURI()).thenReturn("/api/quotes/BTCUSDT");

    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    StringWriter holderWriter = new StringWriter();
    HttpServletResponse holderResp = mock(HttpServletResponse.class);
    when(holderResp.getWriter()).thenReturn(new PrintWriter(holderWriter));

    Thread holder =
        new Thread(
            () -> {
              try {
                filter.doFilterInternal(
                    req,
                    holderResp,
                    (r, s) -> {
                      holding.countDown();
                      try {
                        release.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    });
              } catch (Exception e) {
                Thread.currentThread().interrupt();
              }
            });
    holder.start();
    holding.await();

    StringWriter rejectWriter = new StringWriter();
    HttpServletResponse resp2 = mock(HttpServletResponse.class);
    when(resp2.getWriter()).thenReturn(new PrintWriter(rejectWriter));
    FilterChain chain2 = mock(FilterChain.class);
    filter.doFilterInternal(req, resp2, chain2);

    verify(resp2).setStatus(429);
    verify(chain2, never()).doFilter(any(), any());

    release.countDown();
    holder.join(2000);
  }

  @Test
  void concurrentRequestsExhaustingPermits() throws Exception {
    int maxPermits = 2;
    int totalRequests = 10;
    RateLimitFilter filter = createFilter(maxPermits, 100);

    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch doneGate = new CountDownLatch(totalRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger rejectCount = new AtomicInteger(0);

    for (int i = 0; i < totalRequests; i++) {
      new Thread(
              () -> {
                try {
                  startGate.await();
                  HttpServletRequest req = mock(HttpServletRequest.class);
                  when(req.getRequestURI()).thenReturn("/api/quotes/BTCUSDT");

                  AtomicInteger statusHolder = new AtomicInteger(200);
                  HttpServletResponse resp = mock(HttpServletResponse.class);
                  doAnswer(
                          inv -> {
                            statusHolder.set(inv.getArgument(0));
                            return null;
                          })
                      .when(resp)
                      .setStatus(anyInt());
                  when(resp.getStatus()).thenAnswer(inv -> statusHolder.get());
                  when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

                  filter.doFilterInternal(
                      req,
                      resp,
                      (r, s) -> {
                        try {
                          Thread.sleep(200);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        successCount.incrementAndGet();
                      });

                  if (statusHolder.get() == 429) {
                    rejectCount.incrementAndGet();
                  }
                } catch (Exception e) {
                  Thread.currentThread().interrupt();
                } finally {
                  doneGate.countDown();
                }
              })
          .start();
    }

    startGate.countDown();
    doneGate.await(10, TimeUnit.SECONDS);

    assertThat(rejectCount.get()).isGreaterThan(0);
    assertThat(successCount.get()).isGreaterThan(0);
    assertThat(successCount.get() + rejectCount.get())
        .as("All requests should be accounted for as either success or rejection")
        .isEqualTo(totalRequests);
  }
}
