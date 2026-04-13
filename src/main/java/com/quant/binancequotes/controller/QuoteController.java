package com.quant.binancequotes.controller;

import com.quant.binancequotes.config.AppProperties;
import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.service.QuoteService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints exposing the latest quotes.
 *
 * <p>Reads never hit the database — {@link QuoteService} serves from the in-memory {@code
 * ConcurrentHashMap} (see {@code docs/design_decisions.md} DD-3). Quotes are serialized directly as
 * records; no DTO layer (DD-12). Jackson is configured for {@code WRITE_BIGDECIMAL_AS_PLAIN} so
 * monetary fields render as plain decimals (e.g. {@code 67432.15}, not {@code 6.743215E4}).
 */
@RestController
@RequestMapping(path = "/api/quotes", produces = MediaType.APPLICATION_JSON_VALUE)
public class QuoteController {

  private final QuoteService quoteService;
  private final AppProperties appProperties;

  public QuoteController(QuoteService quoteService, AppProperties appProperties) {
    this.quoteService = quoteService;
    this.appProperties = appProperties;
  }

  /**
   * Returns the latest quote for every configured symbol.
   *
   * <p>Symbols that have not yet received a frame from the WebSocket are omitted from the response.
   */
  @GetMapping
  public Map<String, Quote> allQuotes() {
    return quoteService.all();
  }

  /**
   * Returns the latest quote for a single symbol.
   *
   * @param symbol the instrument symbol (e.g. {@code BTCUSDT})
   * @return the latest quote
   * @throws SymbolNotFoundException if the symbol is not in the configured list or no quote exists
   */
  @GetMapping("/{symbol}")
  public Quote quoteBySymbol(@PathVariable String symbol) {
    String normalized = symbol.toUpperCase();
    if (!appProperties.getSymbols().contains(normalized)) {
      throw new SymbolNotFoundException(normalized);
    }
    return quoteService.get(normalized).orElseThrow(() -> new SymbolNotFoundException(normalized));
  }

  /**
   * Checked exception signalling that the requested symbol is unknown or has no quote yet.
   *
   * <p>Caught by {@link ApiExceptionHandler} and rendered as a 404 JSON response.
   */
  public static class SymbolNotFoundException extends RuntimeException {

    private final String symbol;

    public SymbolNotFoundException(String symbol) {
      super("Unknown symbol: " + symbol);
      this.symbol = symbol;
    }

    public String getSymbol() {
      return symbol;
    }
  }
}
