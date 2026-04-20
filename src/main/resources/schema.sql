CREATE TABLE IF NOT EXISTS quotes (
    id               BIGSERIAL PRIMARY KEY,
    symbol           VARCHAR(20) NOT NULL,
    bid_price        NUMERIC(24,8) NOT NULL,
    bid_size         NUMERIC(24,8) NOT NULL,
    ask_price        NUMERIC(24,8) NOT NULL,
    ask_size         NUMERIC(24,8) NOT NULL,
    update_id        BIGINT NOT NULL,
    event_time       BIGINT NOT NULL,
    transaction_time BIGINT NOT NULL,
    received_at      BIGINT NOT NULL,
    CONSTRAINT quotes_symbol_updateid_uk UNIQUE (symbol, update_id),
    CONSTRAINT chk_positive_bid  CHECK (bid_price > 0),
    CONSTRAINT chk_positive_ask  CHECK (ask_price > 0),
    CONSTRAINT chk_nonneg_sizes CHECK (bid_size >= 0 AND ask_size >= 0),
    CONSTRAINT chk_positive_event_time CHECK (event_time > 0),
    CONSTRAINT chk_positive_update_id CHECK (update_id > 0),
    CONSTRAINT chk_non_crossed_spread CHECK (bid_price <= ask_price)
);

CREATE INDEX IF NOT EXISTS idx_quotes_symbol_time
    ON quotes (symbol, event_time DESC);
