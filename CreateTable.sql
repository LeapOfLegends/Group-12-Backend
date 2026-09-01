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
