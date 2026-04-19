package com.quant.binancequotes.controller;

import com.quant.binancequotes.controller.QuoteController.SymbolNotFoundException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralized exception handling for the REST API.
 *
 * <p>Catches {@link SymbolNotFoundException} and renders a clean 404 JSON body without leaking
 * stack traces. Any unexpected error falls through to a generic 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(SymbolNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Map<String, String> handleSymbolNotFound(SymbolNotFoundException ex) {
    log.debug("Symbol not found: {}", ex.getSymbol());
    return Map.of("error", ex.getMessage());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, String> handleMissingParam(MissingServletRequestParameterException ex) {
    return Map.of("error", "Missing required parameter: " + ex.getParameterName());
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public Map<String, String> handleUnexpected(Exception ex) {
    log.error("Unexpected error", ex);
    return Map.of("error", "Internal server error");
  }
}
