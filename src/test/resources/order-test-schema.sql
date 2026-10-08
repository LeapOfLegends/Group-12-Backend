CREATE TABLE markets (
    market_id BIGSERIAL PRIMARY KEY,
    market_code TEXT NOT NULL CONSTRAINT uq_markets_market_code UNIQUE CONSTRAINT chk_markets_market_code CHECK (market_code IN ('IEX', 'LSE', 'NSE', 'BSE', 'FX', 'CRYPTO')),
    market_status TEXT NOT NULL DEFAULT 'CLOSED' CONSTRAINT chk_markets_market_status CHECK (market_status IN ('OPEN', 'CLOSED')),
    status_as_of TIMESTAMPTZ,
    CONSTRAINT chk_markets_crypto_open CHECK (market_code <> 'CRYPTO' OR market_status = 'OPEN')
);

CREATE TABLE instruments (
    instrument_id BIGSERIAL PRIMARY KEY,
    market_id BIGINT CONSTRAINT fk_instruments_market REFERENCES markets(market_id),
    symbol VARCHAR(20) NOT NULL,
    instrument_name VARCHAR(100) NOT NULL,
    asset_class VARCHAR(100) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    is_tradable BOOLEAN NOT NULL,
    bid_price NUMERIC(20,8) CONSTRAINT chk_instruments_bid_price_positive CHECK (bid_price IS NULL OR bid_price > 0),
    ask_price NUMERIC(20,8) CONSTRAINT chk_instruments_ask_price_positive CHECK (ask_price IS NULL OR ask_price > 0),
    last_price NUMERIC(20,8) CONSTRAINT chk_instruments_last_price_positive CHECK (last_price IS NULL OR last_price > 0),
    quote_as_of TIMESTAMPTZ,
    last_trade_as_of TIMESTAMPTZ,
    CONSTRAINT chk_instruments_quote_snapshot CHECK (
        (bid_price IS NULL AND ask_price IS NULL AND quote_as_of IS NULL)
        OR (bid_price IS NOT NULL AND ask_price IS NOT NULL AND quote_as_of IS NOT NULL)
    ),
    CONSTRAINT chk_instruments_last_trade_snapshot CHECK (
        (last_price IS NULL AND last_trade_as_of IS NULL)
        OR (last_price IS NOT NULL AND last_trade_as_of IS NOT NULL)
    )
);

CREATE TABLE clients (
    client_id BIGSERIAL PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    ssn VARCHAR(11) NOT NULL,
    phone_number VARCHAR(12) NOT NULL,
    account_balance NUMERIC(40,16) NOT NULL CHECK (account_balance >= 0),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE orders (
    order_id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES clients(client_id),
    instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id),
    order_type TEXT NOT NULL CHECK (order_type IN ('SELL', 'BUY')),
    quantity NUMERIC(20,8) NOT NULL CHECK (quantity > 0),
    status TEXT NOT NULL DEFAULT 'SUBMITTED'
        CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED', 'FAILED', 'FILLED')),
    reserved_cash NUMERIC(40,16) CHECK (reserved_cash > 0),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    filled_at TIMESTAMPTZ,
    execution_price NUMERIC(20,8) CHECK (execution_price > 0),
    execution_quote_as_of TIMESTAMPTZ,
    trade_value NUMERIC(40,16)
        GENERATED ALWAYS AS (execution_price * quantity) STORED,
    rejection_reason TEXT,
    failure_reason TEXT
);

CREATE TABLE holdings (
    holding_id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES clients(client_id),
    instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id),
    quantity NUMERIC(20,8) NOT NULL CHECK (quantity > 0),
    average_cost NUMERIC(30,16) NOT NULL CHECK (average_cost >= 0),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (client_id, instrument_id)
);
