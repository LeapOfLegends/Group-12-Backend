DROP TABLE IF EXISTS holdings;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS accounts;
DROP TABLE IF EXISTS instruments;
DROP TABLE IF EXISTS users;

CREATE TABLE instruments (
    symbol VARCHAR(20) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    asset_class VARCHAR(100),
    currency CHAR(3),
    tradable BOOLEAN NOT NULL,
    instrument_price NUMERIC(14,2) NOT NULL
);

CREATE TABLE users (
    user_id BIGSERIAL PRIMARY KEY,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE accounts (
    account_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id),
    account_balance NUMERIC(18,2) NOT NULL,
    account_type VARCHAR(100) NOT NULL,
    opened_date DATE NOT NULL
);

CREATE TABLE orders (
    order_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id),
    order_date DATE NOT NULL,
    order_type VARCHAR(20) NOT NULL,
    order_amount NUMERIC(14,4) NOT NULL,
    symbol VARCHAR(20) NOT NULL REFERENCES instruments(symbol),
    instrument_quantity NUMERIC(14,4) NOT NULL
);

CREATE TABLE holdings (
    holding_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(account_id),
    symbol VARCHAR(20) NOT NULL REFERENCES instruments(symbol),
    quantity NUMERIC(14,4) NOT NULL,
    average_cost NUMERIC(18,4) NOT NULL
);
DROP TABLE IF EXISTS holdings;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS accounts;
DROP TABLE IF EXISTS instruments;
DROP TABLE IF EXISTS clients;
DROP TABLE IF EXISTS users;
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

CREATE TABLE accounts (
    account_id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES clients(client_id),
    account_balance NUMERIC(14,4) NOT NULL,
    account_type VARCHAR(100) NOT NULL,
    opened_date DATE NOT NULL
);

CREATE TABLE orders (
    order_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(account_id),
    order_date DATE NOT NULL,
    order_type VARCHAR(20) NOT NULL,
    total_cost NUMERIC(14,4) NOT NULL,
    instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
	status TEXT NOT NULL CHECK (status IN ('ACCEPTED', 'REJECTED', 'FAILED', 'PENDING')),
	price NUMERIC(14,4) NOT NULL CHECK (price > 0)
);

CREATE TABLE holdings (
    holding_id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(account_id),
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