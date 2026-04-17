package com.quant.binancequotes.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class HealthEndpointIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void livenessIncludesOnlyLivenessState() {
    @SuppressWarnings("unchecked")
    ResponseEntity<Map> response =
        restTemplate.getForEntity("/actuator/health/liveness", Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<String, Object> body = response.getBody();
    assertThat(body).containsKey("components");

    @SuppressWarnings("unchecked")
    Map<String, Object> components = (Map<String, Object>) body.get("components");

    assertThat(components).containsKey("livenessState");
    assertThat(components).doesNotContainKey("persistenceQueue");
    assertThat(components).doesNotContainKey("binanceStream");
  }

  @Test
  void readinessIncludesCustomIndicators() {
    @SuppressWarnings("unchecked")
    ResponseEntity<Map> response =
        restTemplate.getForEntity("/actuator/health/readiness", Map.class);
    assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE);

    Map<String, Object> body = response.getBody();
    assertThat(body).containsKey("components");

    @SuppressWarnings("unchecked")
    Map<String, Object> components = (Map<String, Object>) body.get("components");

    assertThat(components).containsKey("readinessState");
    assertThat(components).containsKey("persistenceQueue");
    assertThat(components).containsKey("binanceStream");
  }
}
