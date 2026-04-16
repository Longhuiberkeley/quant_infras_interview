# Interviewer Feedback

1. **Dev Profile Configuration Issue**
   Dev configuration with H2 fails due to the problem with sql queries so this command from the README does not work:
   `mvn spring-boot:run -Dspring-boot.run.profiles=dev`

2. **Missing Performance Tests for Critical Paths**
   I don’t really see enough performance tests. Since latency is important, writing performance tests is essential. There’s `QuoteServicePerformanceTest.java`, that only tests data retrieval from memory, which is expected to be fast and not really important. More interesting is tick-to-trade latency, in this case it is the latency between exchange timestamp for quote and the timestamp when the quote is served to the client. Similarly latency between quote timestamp/quote receive ts and the moment the quote is saved to the database. Also there’s no live test that shows how many quotes are received per second and what’s the latency.

3. **Test Quirks (IDEA vs. Maven Build)**
   There are quirks in tests like `com/quant/binancequotes/IngestLagTest.java:133` where null is passed as `@NotNull` parameter. This works in the build, does not work in IDEA. In fact the parameter `webSocket` is not used in `com.quant.binancequotes.websocket.BinanceWebSocketClient#onMessage(okhttp3.WebSocket, java.lang.String)` and similar methods.

4. **Confusing Build Output Exceptions**
   There are exceptions in the console during build which is confusing: are these real errors and the build just ignores them, or are these expected exceptions? I’d just properly ignore these stack traces during build/test.

5. **Java Compatibility (Java 25/26)**
   Minor: tests don’t work on Java 25, 26, only pass on Java 21.

6. **Timestamp Representation Inconsistencies**
   There’s inconsistency in timestamp representation in server replies, compare `transactionTime` and `receivedAt`: `"ADAUSDT":{"symbol":"ADAUSDT","bid":0.24180,"bidSize":187979,"ask":0.24190,"askSize":360611,"updateId":10330855928306,"eventTime":1776262974922,"transactionTime":1776262974922,"receivedAt":"2026-04-15T14:23:11.393231244Z"}`. Not sure it is a good idea to have 2 different ways to keep timestamp in the database (`schema.sql`):
   ```sql
   update_id        BIGINT NOT NULL,
   event_time       BIGINT NOT NULL,
   transaction_time BIGINT NOT NULL,
   received_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
   ```

7. **Pointless Virtual Thread Usage**
   `com.quant.binancequotes.service.BatchPersistenceService#drainerThread` being virtual thread is pointless here.

8. **Build Time**
   Nit: the build takes over a minute which is too long for this amount of code.