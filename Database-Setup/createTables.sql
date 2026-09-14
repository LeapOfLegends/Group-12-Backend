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
    price NUMERIC(14,4) NOT NULL CHECK (price > 0)
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
    account_balance NUMERIC(14,4) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE admins(
	admin_id BIGSERIAL PRIMARY KEY,
	first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
	email VARCHAR(255) NOT NULL UNIQUE,
	password_hash VARCHAR(255) NOT NULL,
	created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE orders (
    order_id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES clients(client_id),
    order_timestamp TIMESTAMP NOT NULL,
    order_type TEXT NOT NULL CHECK(order_type IN ('SELL','BUY')),
    total_cost NUMERIC(14,4) NOT NULL,
    instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
	status TEXT NOT NULL CHECK (status IN ('ACCEPTED', 'REJECTED', 'FAILED', 'PENDING')),
	price NUMERIC(14,4) NOT NULL CHECK (price > 0)
);

CREATE TABLE holdings (
    holding_id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES clients(client_id),
    instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id),
    quantity INTEGER NOT NULL,
    average_cost NUMERIC(14,4) NOT NULL,
	updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

/*
CREATE TABLE transactions(
transaction_id BIGSERIAL PRIMARY KEY,
total_cost NUMERIC(14,4) NOT NULL,
txn_type TEXT NOT NULL CHECK (txn_type IN ('BUY', 'SELL', 'DIVIDEND', 'DEPOSIT', 'WITHDRAWAL'))
);
*/