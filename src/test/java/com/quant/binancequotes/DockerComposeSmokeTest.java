package com.quant.binancequotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "true")
class DockerComposeSmokeTest {

  @Container
  static final ComposeContainer compose =
      new ComposeContainer(java.nio.file.Path.of("docker-compose.yml").toFile())
          .withServices("postgres", "app")
          .withStartupTimeout(Duration.ofSeconds(120));

  private String get(String urlStr) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(5000);
    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = br.readLine()) != null) {
        sb.append(line);
      }
      return sb.toString();
    } finally {
      conn.disconnect();
    }
  }

  @Test
  void actuatorHealth_returnsUp() {
    int appPort = compose.getServicePort("app", 18080);
    String healthUrl = "http://127.0.0.1:" + appPort + "/actuator/health";

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              String body = get(healthUrl);
              assertThat(body).contains("\"status\":\"UP\"");
            });
  }

  @Test
  void quotesEndpoint_returns200() {
    int appPort = compose.getServicePort("app", 18080);
    String quotesUrl = "http://127.0.0.1:" + appPort + "/api/quotes";

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              HttpURLConnection conn =
                  (HttpURLConnection) URI.create(quotesUrl).toURL().openConnection();
              conn.setConnectTimeout(5000);
              conn.setReadTimeout(5000);
              try {
                assertThat(conn.getResponseCode()).isEqualTo(200);
              } finally {
                conn.disconnect();
              }
            });
  }
}
