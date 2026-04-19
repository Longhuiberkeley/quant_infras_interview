package com.quant.binancequotes.controller;

import com.quant.binancequotes.config.AppProperties;
import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.repository.QuoteHistoryRepository;
import com.quant.binancequotes.service.QuoteService;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints exposing the latest quotes.
 *
 * <p>Latest-quote reads never hit the database — {@link QuoteService} serves from the in-memory
 * {@code ConcurrentHashMap} (see {@code docs/design_decisions.md} DD-3). Quotes are serialized
 * directly as records; no DTO layer (DD-12). Jackson is configured for {@code
 * WRITE_BIGDECIMAL_AS_PLAIN} so monetary fields render as plain decimals (e.g. {@code 67432.15},
 * not {@code 6.743215E4}).
 */
@RestController
@RequestMapping(path = "/api/quotes", produces = MediaType.APPLICATION_JSON_VALUE)
public class QuoteController {

  private final QuoteService quoteService;
  private final AppProperties appProperties;
  private final QuoteHistoryRepository quoteHistoryRepository;

  public QuoteController(
      QuoteService quoteService,
      AppProperties appProperties,
      QuoteHistoryRepository quoteHistoryRepository) {
    this.quoteService = quoteService;
    this.appProperties = appProperties;
    this.quoteHistoryRepository = quoteHistoryRepository;
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
   * Returns historical quotes for a symbol within a time range.
   *
   * <p>Unlike the latest-quote endpoints, this queries PostgreSQL directly (DD-15).
   *
   * @param symbol the instrument symbol (e.g. {@code BTCUSDT})
   * @param from start of time range, epoch milliseconds (inclusive)
   * @param to end of time range, epoch milliseconds (inclusive)
   * @return up to 1000 quotes ordered by event time descending
   * @throws SymbolNotFoundException if the symbol is not in the configured list
   */
  @GetMapping("/{symbol}/history")
  public List<Quote> quoteHistory(
      @PathVariable String symbol, @RequestParam long from, @RequestParam long to) {
    String normalized = symbol.toUpperCase();
    if (!appProperties.getSymbols().contains(normalized)) {
      throw new SymbolNotFoundException(normalized);
    }
    return quoteHistoryRepository.findHistory(normalized, from, to);
  }

  /**
   * Runtime exception signalling that the requested symbol is unknown or has no quote yet.
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
