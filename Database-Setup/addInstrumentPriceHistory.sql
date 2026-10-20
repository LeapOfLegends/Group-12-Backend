-- Additive deployment for existing databases. Do not run createTables.sql against
-- a populated environment because that script intentionally drops existing tables.
CREATE TABLE IF NOT EXISTS instrument_price_history (
    instrument_id BIGINT NOT NULL REFERENCES instruments(instrument_id),
    timeframe VARCHAR(10) NOT NULL
        CHECK (timeframe IN ('5Min', '30Min', '1Day', '1Week', '1Month')),
    bar_start TIMESTAMPTZ NOT NULL,
    close_price NUMERIC(30,16) NOT NULL CHECK (close_price > 0),
    PRIMARY KEY (instrument_id, timeframe, bar_start)
);
