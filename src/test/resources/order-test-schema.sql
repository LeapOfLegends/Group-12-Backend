CREATE TABLE instruments (
    instrument_id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    instrument_name VARCHAR(100) NOT NULL,
    asset_class VARCHAR(100) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    is_tradable BOOLEAN NOT NULL,
    price NUMERIC(14,4) NOT NULL CHECK (price > 0)
);

CREATE TABLE clients (
    client_id BIGSERIAL PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    ssn VARCHAR(11) NOT NULL,
    phone_number VARCHAR(12) NOT NULL,
    account_balance NUMERIC(14,4) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE orders (
    order_id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES clients(client_id),
    instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id),
    order_type TEXT NOT NULL CHECK (order_type IN ('SELL', 'BUY')),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    status TEXT NOT NULL DEFAULT 'SUBMITTED'
        CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED', 'FAILED', 'FILLED')),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    filled_at TIMESTAMPTZ,
    execution_price NUMERIC(14,4) CHECK (execution_price > 0),
    trade_value NUMERIC(18,4)
        GENERATED ALWAYS AS (execution_price * quantity) STORED,
    rejection_reason TEXT,
    failure_reason TEXT
);
