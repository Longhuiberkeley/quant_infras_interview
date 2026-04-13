package com.quant.binancequotes.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

class AppPropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Configuration
  @EnableConfigurationProperties(AppProperties.class)
  static class TestConfig {}

  @Test
  void rejectsShortSymbolList() {
    contextRunner
        .withPropertyValues(
            "app.symbols[0]=BTCUSDT", "app.symbols[1]=ETHUSDT", "app.symbols[2]=BNBUSDT")
        .run(
            context -> {
              assertThat(context).hasFailed();
              Throwable failure = context.getStartupFailure();
              assertThat(failure).isNotNull();
              Throwable root = findRootCause(failure);
              // Spring @Size validation fires BindValidationException;
              // either way, the context must fail to start.
              assertThat(root.getMessage())
                  .satisfiesAnyOf(
                      m -> assertThat(m).contains("size must be between 10 and 10"),
                      m -> assertThat(m).contains("app.symbols"));
            });
  }

  @Test
  void rejectsLowercaseSymbol() {
    contextRunner
        .withPropertyValues(
            "app.symbols[0]=btcusdt",
            "app.symbols[1]=ETHUSDT",
            "app.symbols[2]=BNBUSDT",
            "app.symbols[3]=SOLUSDT",
            "app.symbols[4]=XRPUSDT",
            "app.symbols[5]=DOGEUSDT",
            "app.symbols[6]=ADAUSDT",
            "app.symbols[7]=TRXUSDT",
            "app.symbols[8]=LINKUSDT",
            "app.symbols[9]=AVAXUSDT")
        .run(
            context -> {
              assertThat(context).hasFailed();
              Throwable failure = context.getStartupFailure();
              assertThat(failure).isNotNull();
              Throwable root = findRootCause(failure);
              assertThat(root).isInstanceOf(IllegalStateException.class);
              assertThat(root.getMessage()).contains("btcusdt");
              assertThat(root.getMessage()).contains("does not match pattern");
            });
  }

  @Test
  void rejectsNonUsdtSuffix() {
    contextRunner
        .withPropertyValues(
            "app.symbols[0]=BTCUSD",
            "app.symbols[1]=ETHUSDT",
            "app.symbols[2]=BNBUSDT",
            "app.symbols[3]=SOLUSDT",
            "app.symbols[4]=XRPUSDT",
            "app.symbols[5]=DOGEUSDT",
            "app.symbols[6]=ADAUSDT",
            "app.symbols[7]=TRXUSDT",
            "app.symbols[8]=LINKUSDT",
            "app.symbols[9]=AVAXUSDT")
        .run(
            context -> {
              assertThat(context).hasFailed();
              Throwable failure = context.getStartupFailure();
              assertThat(failure).isNotNull();
              Throwable root = findRootCause(failure);
              assertThat(root).isInstanceOf(IllegalStateException.class);
              assertThat(root.getMessage()).contains("BTCUSD");
              assertThat(root.getMessage()).contains("does not match pattern");
            });
  }

  @Test
  void rejectsNullSymbols() {
    AppProperties props = new AppProperties();
    props.setSymbols(null);

    assertThatThrownBy(props::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not be null");
  }

  @Test
  void acceptsValidTenSymbolList() {
    contextRunner
        .withPropertyValues(
            "app.symbols[0]=BTCUSDT",
            "app.symbols[1]=ETHUSDT",
            "app.symbols[2]=BNBUSDT",
            "app.symbols[3]=SOLUSDT",
            "app.symbols[4]=XRPUSDT",
            "app.symbols[5]=DOGEUSDT",
            "app.symbols[6]=ADAUSDT",
            "app.symbols[7]=TRXUSDT",
            "app.symbols[8]=LINKUSDT",
            "app.symbols[9]=AVAXUSDT")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              AppProperties props = context.getBean(AppProperties.class);
              assertThat(props.getSymbols()).hasSize(10);
              assertThat(props.getSymbols())
                  .containsExactly(
                      "BTCUSDT",
                      "ETHUSDT",
                      "BNBUSDT",
                      "SOLUSDT",
                      "XRPUSDT",
                      "DOGEUSDT",
                      "ADAUSDT",
                      "TRXUSDT",
                      "LINKUSDT",
                      "AVAXUSDT");
            });
  }

  /** Integration test: verifies the real application.yml loads 10 valid symbols. */
  @SpringBootTest(classes = TestConfig.class)
  @TestPropertySource(
      properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
      })
  static class SpringBootIntegrationTest {

    @Autowired private AppProperties appProperties;

    @Test
    void tenSymbolsLoadFromDefaultConfig() {
      assertThat(appProperties.getSymbols()).hasSize(10);
      for (String symbol : appProperties.getSymbols()) {
        assertThat(symbol).matches("^[A-Z]+USDT$");
      }
    }
  }

  private static Throwable findRootCause(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }
}
