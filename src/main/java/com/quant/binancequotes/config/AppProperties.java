package com.quant.binancequotes.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-level configuration properties bound to the {@code app} prefix.
 *
 * <p>Validates at startup that the symbol list contains exactly 10 entries, each uppercase and
 * ending in {@code USDT}. Invalid configuration fails the application context initialization.
 */
@ConfigurationProperties("app")
@Validated
public class AppProperties {

  private static final String SYMBOL_PATTERN = "^[A-Z]+USDT$";

  @NotNull private List<String> symbols;

  @Size(min = 10, max = 10)
  public List<String> getSymbols() {
    return symbols;
  }

  public void setSymbols(List<String> symbols) {
    this.symbols = symbols;
  }

  @PostConstruct
  void validate() {
    if (symbols == null) {
      throw new IllegalStateException("app.symbols must not be null");
    }
    for (int i = 0; i < symbols.size(); i++) {
      String s = symbols.get(i);
      if (s == null || !s.matches(SYMBOL_PATTERN)) {
        throw new IllegalStateException(
            "app.symbols["
                + i
                + "] = \""
                + s
                + "\" does not match pattern "
                + SYMBOL_PATTERN
                + " (must be uppercase, e.g. BTCUSDT)");
      }
    }
  }
}
