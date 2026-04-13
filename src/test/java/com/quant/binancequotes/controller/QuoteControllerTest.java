package com.quant.binancequotes.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.binancequotes.config.AppProperties;
import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.service.QuoteService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(controllers = {QuoteController.class, ApiExceptionHandler.class})
class QuoteControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockBean private QuoteService quoteService;

  @MockBean private AppProperties appProperties;

  @BeforeEach
  void setUp() {
    // Default: all 10 symbols configured
    when(appProperties.getSymbols())
        .thenReturn(
            List.of(
                "BTCUSDT",
                "ETHUSDT",
                "BNBUSDT",
                "SOLUSDT",
                "XRPUSDT",
                "DOGEUSDT",
                "ADAUSDT",
                "TRXUSDT",
                "LINKUSDT",
                "AVAXUSDT"));
  }

  // --- GET /api/quotes ---

  @Test
  void getAllQuotes_returnsAllSymbols() throws Exception {
    Quote btc = testQuote("BTCUSDT", 1);
    Quote eth = testQuote("ETHUSDT", 2);
    when(quoteService.all()).thenReturn(Map.of("BTCUSDT", btc, "ETHUSDT", eth));

    mockMvc
        .perform(get("/api/quotes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.BTCUSDT.symbol").value("BTCUSDT"))
        .andExpect(jsonPath("$.ETHUSDT.symbol").value("ETHUSDT"));
  }

  @Test
  void getAllQuotes_returnsEmptyMapWhenNoQuotes() throws Exception {
    when(quoteService.all()).thenReturn(Map.of());

    mockMvc
        .perform(get("/api/quotes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  // --- GET /api/quotes/{symbol} ---

  @Test
  void getQuoteBySymbol_returnsQuote() throws Exception {
    Quote btc = testQuote("BTCUSDT", 1);
    when(quoteService.get("BTCUSDT")).thenReturn(Optional.of(btc));

    mockMvc
        .perform(get("/api/quotes/BTCUSDT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value("BTCUSDT"))
        .andExpect(jsonPath("$.bid").exists())
        .andExpect(jsonPath("$.ask").exists());
  }

  @Test
  void getQuoteBySymbol_normalizesToLowercase() throws Exception {
    // The controller normalizes to uppercase internally; the path variable is case-insensitive
    Quote btc = testQuote("BTCUSDT", 1);
    when(quoteService.get("BTCUSDT")).thenReturn(Optional.of(btc));

    mockMvc
        .perform(get("/api/quotes/btcusdt"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value("BTCUSDT"));
  }

  @Test
  void getQuoteByUnknownConfiguredSymbol_returns404() throws Exception {
    // Symbol is configured but QuoteService has no data yet
    when(quoteService.get("BTCUSDT")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/quotes/BTCUSDT"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("Unknown symbol: BTCUSDT"));
  }

  @Test
  void getQuoteByUnconfiguredSymbol_returns404() throws Exception {
    // Symbol not in the configured list at all
    mockMvc
        .perform(get("/api/quotes/FOOBAR"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("Unknown symbol: FOOBAR"));
  }

  // --- BigDecimal serialization (DD-2: no scientific notation) ---

  @Test
  void bigDecimalSerializesAsPlainNotScientific() throws Exception {
    // FIX: Using "ETHUSDT" instead of "BTCUSDT" exposes the previous flawed test
    // that simply asserted `body.doesNotContain("E")`. A capital 'E' in the symbol
    // would break the old assertion. We now parse the JSON and verify the numeric
    // fields directly to ensure they are plain decimals without scientific notation.
    Quote q =
        new Quote(
            "ETHUSDT",
            new BigDecimal("67432.15000000"),
            new BigDecimal("1.23400000"),
            new BigDecimal("67432.20000000"),
            new BigDecimal("0.56700000"),
            1L,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            Instant.now());
    when(quoteService.get("ETHUSDT")).thenReturn(Optional.of(q));

    MvcResult result =
        mockMvc.perform(get("/api/quotes/ETHUSDT")).andExpect(status().isOk()).andReturn();

    String body = result.getResponse().getContentAsString();

    // We expect the body to have the symbol with 'E'
    assertThat(body).contains("\"symbol\":\"ETHUSDT\"");

    // Parse the JSON to inspect the actual numeric serialization
    com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(body);

    // The node should be a numeric type in JSON, not a string
    assertThat(root.get("bid").isNumber()).isTrue();

    // The string representation generated by Jackson should not use scientific notation
    String bidStr = root.get("bid").asText();
    assertThat(bidStr).doesNotContain("E").doesNotContain("e");
    assertThat(new BigDecimal(bidStr)).isEqualByComparingTo(new BigDecimal("67432.15"));
  }

  // --- 404 does not leak stack traces ---

  @Test
  void notFoundResponseDoesNotLeakStackTrace() throws Exception {
    mockMvc
        .perform(get("/api/quotes/UNKNOWN"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").exists())
        .andExpect(jsonPath("$.stackTrace").doesNotExist())
        .andExpect(jsonPath("$.message").doesNotExist())
        .andExpect(jsonPath("$.exception").doesNotExist());
  }

  // --- Helpers ---

  private Quote testQuote(String symbol, long updateId) {
    return new Quote(
        symbol,
        new BigDecimal("67432.15"),
        new BigDecimal("1.234"),
        new BigDecimal("67432.20"),
        new BigDecimal("0.567"),
        updateId,
        System.currentTimeMillis(),
        System.currentTimeMillis(),
        Instant.now());
  }
}
