package com.quant.binancequotes.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quant.binancequotes.config.AppProperties;
import com.quant.binancequotes.model.Quote;
import com.quant.binancequotes.repository.QuoteHistoryRepository;
import com.quant.binancequotes.service.QuoteService;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {QuoteController.class, ApiExceptionHandler.class})
class QuoteHistoryControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockBean private QuoteService quoteService;
  @MockBean private AppProperties appProperties;
  @MockBean private QuoteHistoryRepository quoteHistoryRepository;

  @BeforeEach
  void setUp() {
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

  @Test
  void history_returnsQuotes() throws Exception {
    Quote q =
        new Quote(
            "BTCUSDT",
            new BigDecimal("50000.00"),
            new BigDecimal("1.0"),
            new BigDecimal("50001.00"),
            new BigDecimal("1.0"),
            1,
            1000,
            997,
            1000);
    when(quoteHistoryRepository.findHistory("BTCUSDT", 0, 2000)).thenReturn(List.of(q));

    mockMvc
        .perform(get("/api/quotes/BTCUSDT/history").param("from", "0").param("to", "2000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].symbol").value("BTCUSDT"))
        .andExpect(jsonPath("$[0].eventTime").value(1000));
  }

  @Test
  void history_returnsEmptyList() throws Exception {
    when(quoteHistoryRepository.findHistory("BTCUSDT", 0, 0)).thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/api/quotes/BTCUSDT/history").param("from", "0").param("to", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void history_unknownSymbol_returns404() throws Exception {
    mockMvc
        .perform(get("/api/quotes/FOOBAR/history").param("from", "0").param("to", "1000"))
        .andExpect(status().isNotFound());
  }

  @Test
  void history_caseInsensitive() throws Exception {
    when(quoteHistoryRepository.findHistory("BTCUSDT", 0, 1000))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/api/quotes/btcusdt/history").param("from", "0").param("to", "1000"))
        .andExpect(status().isOk());
  }

  @Test
  void history_missingFromParam_returns400() throws Exception {
    mockMvc
        .perform(get("/api/quotes/BTCUSDT/history").param("to", "1000"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void history_invertedRange_returnsEmpty() throws Exception {
    when(quoteHistoryRepository.findHistory("BTCUSDT", 2000, 1000))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/api/quotes/BTCUSDT/history").param("from", "2000").param("to", "1000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }
}
