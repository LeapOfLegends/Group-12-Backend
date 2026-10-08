DROP TABLE IF EXISTS holdings;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS instruments;
DROP TABLE IF EXISTS clients;
DROP TABLE IF EXISTS admins;
--DROP TABLE IF EXISTS transactions;

CREATE TABLE instruments (
	instrument_id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    instrument_name VARCHAR(100) NOT NULL,
    asset_class VARCHAR(100) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    is_tradable BOOLEAN NOT NULL,
    bid_price NUMERIC(20,8),
    ask_price NUMERIC(20,8),
    last_price NUMERIC(20,8),
    quote_as_of TIMESTAMPTZ,
    last_trade_as_of TIMESTAMPTZ,
    CONSTRAINT chk_instruments_bid_price_positive CHECK (bid_price IS NULL OR bid_price > 0),
    CONSTRAINT chk_instruments_ask_price_positive CHECK (ask_price IS NULL OR ask_price > 0),
    CONSTRAINT chk_instruments_last_price_positive CHECK (last_price IS NULL OR last_price > 0),
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
	--login_role TEXT NOT NULL CHECK (login_role IN ('USER', 'ADMIN')),
    --username VARCHAR(50) NOT NULL UNIQUE,
	--CONSTRAINT chk_username_no_spaces CHECK (username != '\s'),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
	ssn VARCHAR(11) NOT NULL,
	phone_number VARCHAR(12) NOT NULL,
    account_balance NUMERIC(40,16) NOT NULL DEFAULT 0 CHECK (account_balance >= 0),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    date_of_birth DATE NOT NULL
);

CREATE TABLE admins(
	admin_id BIGSERIAL PRIMARY KEY,
	first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
	email VARCHAR(255) NOT NULL UNIQUE,
	password_hash VARCHAR(255) NOT NULL,
	created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
	roles TEXT NOT NULL CHECK (roles IN ('super_admin', 'finance', 'operations')),
    is_active BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE orders (
    order_id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES clients(client_id),
    instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id),
    order_type TEXT NOT NULL CHECK(order_type IN ('SELL','BUY')),
    quantity NUMERIC(20,8) NOT NULL CHECK (quantity > 0),
	status TEXT NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED', 'FAILED', 'FILLED')),
    reserved_cash NUMERIC(40,16) CHECK (reserved_cash > 0),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    filled_at TIMESTAMPTZ,
	execution_price NUMERIC(20,8) CHECK (execution_price > 0),
    execution_quote_as_of TIMESTAMPTZ,
    trade_value NUMERIC(40,16) GENERATED ALWAYS AS (execution_price * quantity) STORED,
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
    CONSTRAINT uq_holdings_client_instrument UNIQUE (client_id, instrument_id)
);

CREATE TABLE transactions (
	transaction_id BIGSERIAL PRIMARY KEY,
	client_id BIGINT NOT NULL REFERENCES clients(client_id),
	order_id BIGINT REFERENCES orders(order_id),
	transaction_history TIMESTAMPTZ,
	account_balance NUMERIC(40,16) NOT NULL DEFAULT 0 CHECK (account_balance >= 0), --(account_balance joined from clients)
	order_amount NUMERIC(40,16), --(trade_value joined from orders)
	order_type TEXT CHECK(order_type IN ('SELL', 'BUY')), --(order_type joined from orders)
	order_history TIMESTAMPTZ, --(filled_at joined from orders)
	transaction_amount NUMERIC(20, 8),
	transaction_type TEXT CHECK(transaction_type IN ('WITHDRAWAL', 'DEPOSIT'))
);

CREATE TABLE holding_history (
    holding_id BIGINT NOT NULL REFERENCES holdings(holding_id),
	order_id BIGINT NOT NULL REFERENCES orders(order_id),
    client_id BIGINT NOT NULL REFERENCES clients(client_id),
    instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id),
    quantity NUMERIC(20,8) NOT NULL CHECK (quantity > 0),
    average_cost NUMERIC(30,16) NOT NULL CHECK (average_cost >= 0),
	updated_at TIMESTAMP -- will be pulled from holdings tables so will not use current_timestamp
);