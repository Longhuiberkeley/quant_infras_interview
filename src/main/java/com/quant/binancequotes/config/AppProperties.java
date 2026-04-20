package com.quant.binancequotes.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

  @NotNull
  @Size(min = 10, max = 10)
  private List<@Pattern(regexp = "^[A-Z]+USDT$") String> symbols;

  public List<String> getSymbols() {
    return symbols;
  }

  public void setSymbols(List<String> symbols) {
    this.symbols = symbols;
  }
}
