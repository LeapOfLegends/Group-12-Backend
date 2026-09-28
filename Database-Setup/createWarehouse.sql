CREATE SCHEMA IF NOT EXISTS dw;

CREATE TABLE IF NOT EXISTS dw.table_records (
	instrument_id BIGINT NOT NULL,
	client_id BIGINT NOT NULL,
	order_id BIGINT NOT NULL,
	holding_id BIGINT NOT NULL,
	symbol VARCHAR(20) NOT NULL,
	instrument_name VARCHAR(100) NOT NULL,
	asset_class VARCHAR(100) NOT NULL,
	currency VARCHAR(3) NOT NULL,
	is_tradable BOOLEAN NOT NULL,
	instrument_price NUMERIC(14, 4) NOT NULL,
	account_balance NUMERIC(14, 4) NOT NULL,
	created_at TIMESTAMP NOT NULL,
	order_type TEXT NOT NULL,
	order_quantity INTEGER NOT NULL,
	order_status TEXT NOT NULL,
	submitted_at TIMESTAMPTZ NOT NULL,
	accepted_at TIMESTAMPTZ,
	rejected_at TIMESTAMPTZ,
	failed_at TIMESTAMPTZ,
	filled_at TIMESTAMPTZ,
	execution_price NUMERIC(14, 4),
	trade_value NUMERIC(18, 4),
	rejection_reason TEXT,
	failure_reason TEXT,
	holding_quantity INTEGER NOT NULL,
	holdings_average_cost NUMERIC(14, 4) NOT NULL,
	holdings_updated TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dw_trade_filled_at ON dw.table_records(filled_at DESC);
CREATE INDEX IF NOT EXISTS idx_dw_trade_symbol ON dw.table_records(symbol DESC);
CREATE INDEX IF NOT EXISTS idx_dw_trade_segment ON dw.table_records(asset_class, filled_at DESC);
CREATE INDEX IF NOT EXISTS idx_dw_trade_date ON dw.table_records(submitted_at);


CREATE TABLE IF NOT EXISTS dw.etl_watermark (
	pipeline_name VARCHAR(100) PRIMARY KEY,
	last_filled_at TIMESTAMPTZ NOT NULL, 
	last_fill_id BIGINT,
	updated_at TIMESTAMPTTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


INSERT INTO dw.etl_watermark(pipeline_name, last_filled_at)
VALUES ('trade_activity', TIMESTAMPTZ '1970-01-01 00:00:00+00')
ON CONFLICT (pipeline_name) DO NOTHING;