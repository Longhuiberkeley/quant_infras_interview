package com.quant.binancequotes.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration test: verifies the real {@code application.yml} loads 10 valid symbols.
 *
 * <p>Extracted from {@link AppPropertiesTest} because Surefire does not discover nested static test
 * classes, so this test would never have run.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:testdb",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password="
    })
class AppPropertiesIntegrationTest {

  @Autowired private AppProperties appProperties;

  @Test
  void tenSymbolsLoadFromDefaultConfig() {
    assertThat(appProperties.getSymbols()).hasSize(10);
    for (String symbol : appProperties.getSymbols()) {
      assertThat(symbol).matches("^[A-Z]+USDT$");
    }
  }
}
