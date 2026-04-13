package com.quant.binancequotes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BinanceQuoteServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(BinanceQuoteServiceApplication.class, args);
  }
}
