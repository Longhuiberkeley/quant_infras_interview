# Project: Binance Quote Streaming Service

## 1. Objective
Create a Java service that receives, processes, and stores real-time quote data for the top 10 instruments from Binance.

## 2. Functional Requirements
*   **Instruments:** Select the top 10 instruments by market capitalization (e.g., BTC/USDT, ETH/USDT, etc.). You may choose to use either **SPOT** or **Perpetuals**.
*   **Data Source:** Connect via the **Binance Streaming API (WebSockets)**. You may use either the global Binance API or Binance US.
*   **Quote Data:** Each quote must include `bid`, `bid_size`, `ask`, and `ask_size`.
*   **Data Storage:**
    *   **Full History:** Persist the complete time-series history of every quote received into a database of your choice.
    *   **Latest Quotes:** Provide a mechanism (e.g., an API endpoint or a separate retrieval layer) to get the most recent quotes for each instrument from the system.

## 3. Quality & Performance
*   **Latency:** Performance is a top priority. The service must be designed for low-latency processing and efficient data ingestion.
*   **Correctness:** Adequate test coverage is required to ensure the system handles data accurately and reliably.

## 4. Deliverables
*   **GitHub Repository:** Provide a link to the repository with the full commit history preserved.
*   **README:** Include a comprehensive README file explaining how to:
    1.  Build the service.
    2.  Run the service.
    3.  Execute the tests.
*   **Timeline:** The expected time for completion is **1 day**.
*   **Tools:** You are encouraged to use any AI tools to assist in the development process.
