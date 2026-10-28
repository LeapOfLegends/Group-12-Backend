-- Append-only demonstration account for John Page.
--
-- This script intentionally does not alter shared instruments, market data, or any
-- pre-existing client data. Planning happens in transaction-local temporary tables;
-- only new client, order, holding, and transaction rows are persisted.

BEGIN;

-- clients.created_at is a timestamp without time zone. The backend interprets it as
-- UTC when reconstructing portfolio history, so use UTC consistently throughout.
SET LOCAL TIME ZONE 'UTC';

DO $seed_john_page$
DECLARE
    v_email                 CONSTANT TEXT := 'johnpage@demo.com';
    v_ssn                   CONSTANT TEXT := '900-41-2638';
    v_password_hash         CONSTANT TEXT := '$2b$12$/1.TiGLPfikMlfZT6/69v.AFSVkZL.gyxmd/ozYNLoIM4SZHv3gWy';
    v_symbols               CONSTANT TEXT[] := ARRAY[
        'AAPL', 'MSFT', 'NVDA', 'AMZN', 'META', 'TSLA',
        'AMD', 'JPM', 'GOOGL', 'COST', 'BTC/USD', 'ETH/USD'
    ];
    v_terminal_indices      CONSTANT INTEGER[] := ARRAY[14, 41, 69, 96, 123, 147, 165, 174];
    v_rejected_indices      CONSTANT INTEGER[] := ARRAY[14, 69, 123, 165];

    v_account_created       TIMESTAMP;
    v_account_created_tz    TIMESTAMPTZ;
    v_history_start         TIMESTAMPTZ;
    v_history_end           TIMESTAMPTZ;
    v_client_id             BIGINT;
    v_order_id              BIGINT;
    v_generated_trade_value NUMERIC(40,16);
    v_balance               NUMERIC(40,16) := 0;
    v_execution_price       NUMERIC(20,8);
    v_quantity              NUMERIC(20,8);
    v_trade_value           NUMERIC(40,16);
    v_reserved_cash         NUMERIC(40,16);
    v_new_quantity          NUMERIC(20,8);
    v_new_average_cost      NUMERIC(30,16);
    v_submitted_at          TIMESTAMPTZ;
    v_accepted_at           TIMESTAMPTZ;
    v_terminal_at           TIMESTAMPTZ;
    v_filled_at             TIMESTAMPTZ;
    v_quote_as_of           TIMESTAMPTZ;
    v_symbol                TEXT;
    v_asset_class           TEXT;
    v_order_type            TEXT;
    v_status                TEXT;
    v_rejection_reason      TEXT;
    v_failure_reason        TEXT;
    v_base_price            NUMERIC(20,8);
    v_progress              DOUBLE PRECISION;
    v_i                     INTEGER;
    v_symbol_index          INTEGER;
    v_rows                  INTEGER;
    v_bad_count             INTEGER;
    v_missing_symbols       TEXT;
    v_position              RECORD;
    v_plan                  RECORD;
    v_ledger                RECORD;
BEGIN
    -- Serialize attempts for this demo identity, then make repeat execution a no-op.
    PERFORM pg_advisory_xact_lock(hashtext(v_email)::BIGINT);

    IF EXISTS (SELECT 1 FROM clients WHERE LOWER(email) = LOWER(v_email)) THEN
        RAISE NOTICE 'Demo client % already exists; no rows were inserted.', v_email;
        RETURN;
    END IF;

    IF EXISTS (SELECT 1 FROM clients WHERE ssn = v_ssn) THEN
        RAISE EXCEPTION 'Cannot seed %: fictitious SSN % is already in use.', v_email, v_ssn;
    END IF;

    v_account_created := (CURRENT_DATE - INTERVAL '5 years')::DATE + TIME '14:00:00';
    v_account_created_tz := v_account_created AT TIME ZONE 'UTC';
    v_history_start := v_account_created_tz + INTERVAL '7 days';
    v_history_end := CURRENT_TIMESTAMP - INTERVAL '10 days';

    IF v_history_end <= v_history_start THEN
        RAISE EXCEPTION 'The calculated five-year history window is invalid.';
    END IF;

    CREATE TEMP TABLE tmp_john_expected_instruments (
        symbol          TEXT PRIMARY KEY,
        market_code     TEXT NOT NULL,
        asset_class     TEXT NOT NULL,
        currency        TEXT NOT NULL,
        base_price      NUMERIC(20,8) NOT NULL CHECK (base_price > 0)
    ) ON COMMIT DROP;

    INSERT INTO tmp_john_expected_instruments
        (symbol, market_code, asset_class, currency, base_price)
    VALUES
        ('AAPL',    'IEX',    'EQUITY', 'USD',   245.00000000),
        ('MSFT',    'IEX',    'EQUITY', 'USD',   520.00000000),
        ('NVDA',    'IEX',    'EQUITY', 'USD',   190.00000000),
        ('AMZN',    'IEX',    'EQUITY', 'USD',   230.00000000),
        ('META',    'IEX',    'EQUITY', 'USD',   720.00000000),
        ('TSLA',    'IEX',    'EQUITY', 'USD',   460.00000000),
        ('AMD',     'IEX',    'EQUITY', 'USD',   250.00000000),
        ('JPM',     'IEX',    'EQUITY', 'USD',   310.00000000),
        ('GOOGL',   'IEX',    'EQUITY', 'USD',   210.00000000),
        ('COST',    'IEX',    'EQUITY', 'USD',  1000.00000000),
        ('BTC/USD', 'CRYPTO', 'CRYPTO', 'USD', 98000.00000000),
        ('ETH/USD', 'CRYPTO', 'CRYPTO', 'USD',  4200.00000000);

    -- Require one unambiguous, tradable USD instrument for every requested symbol.
    SELECT STRING_AGG(checks.symbol, ', ' ORDER BY checks.symbol)
    INTO v_missing_symbols
    FROM (
        SELECT expected.symbol
        FROM tmp_john_expected_instruments expected
        LEFT JOIN markets market
            ON market.market_code = expected.market_code
        LEFT JOIN instruments instrument
            ON instrument.market_id = market.market_id
           AND instrument.symbol = expected.symbol
           AND instrument.asset_class = expected.asset_class
           AND instrument.currency = expected.currency
           AND instrument.is_tradable = TRUE
        GROUP BY expected.symbol
        HAVING COUNT(instrument.instrument_id) <> 1
    ) checks;

    IF v_missing_symbols IS NOT NULL THEN
        RAISE EXCEPTION
            'Expected exactly one tradable USD instrument for each symbol; missing or ambiguous: %',
            v_missing_symbols;
    END IF;

    CREATE TEMP TABLE tmp_john_instruments (
        symbol          TEXT PRIMARY KEY,
        instrument_id   BIGINT NOT NULL UNIQUE,
        asset_class     TEXT NOT NULL,
        base_price      NUMERIC(20,8) NOT NULL
    ) ON COMMIT DROP;

    INSERT INTO tmp_john_instruments (symbol, instrument_id, asset_class, base_price)
    SELECT expected.symbol, instrument.instrument_id, expected.asset_class, expected.base_price
    FROM tmp_john_expected_instruments expected
    JOIN markets market
        ON market.market_code = expected.market_code
    JOIN instruments instrument
        ON instrument.market_id = market.market_id
       AND instrument.symbol = expected.symbol
       AND instrument.asset_class = expected.asset_class
       AND instrument.currency = expected.currency
       AND instrument.is_tradable = TRUE;

    IF (SELECT COUNT(*) FROM tmp_john_instruments) <> 12 THEN
        RAISE EXCEPTION 'Instrument resolution did not produce exactly 12 instruments.';
    END IF;

    CREATE TEMP TABLE tmp_john_order_plan (
        plan_id                 INTEGER PRIMARY KEY,
        instrument_id           BIGINT NOT NULL,
        symbol                  TEXT NOT NULL,
        order_type              TEXT NOT NULL CHECK (order_type IN ('BUY', 'SELL')),
        quantity                NUMERIC(20,8) NOT NULL CHECK (quantity > 0),
        status                  TEXT NOT NULL CHECK (status IN ('REJECTED', 'FAILED', 'FILLED')),
        reserved_cash           NUMERIC(40,16),
        submitted_at            TIMESTAMPTZ NOT NULL,
        accepted_at             TIMESTAMPTZ,
        rejected_at             TIMESTAMPTZ,
        failed_at               TIMESTAMPTZ,
        filled_at               TIMESTAMPTZ,
        execution_price         NUMERIC(20,8),
        execution_quote_as_of   TIMESTAMPTZ,
        rejection_reason        TEXT,
        failure_reason          TEXT,
        expected_trade_value    NUMERIC(40,16)
    ) ON COMMIT DROP;

    CREATE TEMP TABLE tmp_john_positions (
        instrument_id   BIGINT PRIMARY KEY,
        symbol          TEXT NOT NULL UNIQUE,
        quantity        NUMERIC(20,8) NOT NULL CHECK (quantity >= 0),
        average_cost    NUMERIC(30,16) NOT NULL CHECK (average_cost >= 0),
        updated_at      TIMESTAMP NOT NULL
    ) ON COMMIT DROP;

    CREATE TEMP TABLE tmp_john_ledger (
        event_id         BIGSERIAL PRIMARY KEY,
        event_at         TIMESTAMPTZ NOT NULL,
        sort_order       INTEGER NOT NULL,
        transaction_type TEXT NOT NULL CHECK (
            transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'BUY', 'SELL')
        ),
        plan_id          INTEGER,
        amount           NUMERIC(40,16) NOT NULL,
        balance_after    NUMERIC(40,16)
    ) ON COMMIT DROP;

    CREATE TEMP TABLE tmp_john_order_map (
        plan_id          INTEGER PRIMARY KEY,
        order_id         BIGINT NOT NULL UNIQUE
    ) ON COMMIT DROP;

    -- Legitimate funding events. Their ledger balances are calculated only after all
    -- order cash movements have been planned and chronologically merged.
    INSERT INTO tmp_john_ledger (event_at, sort_order, transaction_type, amount)
    VALUES
        (v_account_created_tz + INTERVAL '5 minutes', 10, 'DEPOSIT',    150000.0000000000000000),
        (v_account_created_tz + INTERVAL '210 days',  10, 'DEPOSIT',     30000.0000000000000000),
        (v_account_created_tz + INTERVAL '420 days',  10, 'DEPOSIT',     45000.0000000000000000),
        (v_account_created_tz + INTERVAL '690 days',  10, 'DEPOSIT',     25000.0000000000000000),
        (v_account_created_tz + INTERVAL '950 days',  10, 'DEPOSIT',     50000.0000000000000000),
        (v_account_created_tz + INTERVAL '1230 days', 10, 'DEPOSIT',     35000.0000000000000000),
        (v_account_created_tz + INTERVAL '1450 days', 10, 'DEPOSIT',     40000.0000000000000000),
        (v_account_created_tz + INTERVAL '1600 days', 10, 'DEPOSIT',     30000.0000000000000000),
        (v_account_created_tz + INTERVAL '1725 days', 10, 'DEPOSIT',     55000.0000000000000000),
        (v_account_created_tz + INTERVAL '330 days',  10, 'WITHDRAWAL',  -5000.0000000000000000),
        (v_account_created_tz + INTERVAL '760 days',  10, 'WITHDRAWAL',  -7500.0000000000000000),
        (v_account_created_tz + INTERVAL '1100 days', 10, 'WITHDRAWAL',  -4000.0000000000000000),
        (v_account_created_tz + INTERVAL '1430 days', 10, 'WITHDRAWAL',  -6000.0000000000000000),
        (v_account_created_tz + INTERVAL '1650 days', 10, 'WITHDRAWAL',  -3500.0000000000000000),
        (CURRENT_TIMESTAMP - INTERVAL '4 days',       10, 'DEPOSIT',     12500.0000000000000000),
        (CURRENT_TIMESTAMP - INTERVAL '3 days',       10, 'WITHDRAWAL',  -2500.0000000000000000);

    -- Build 180 deterministic orders across the five-year window. The first purchase
    -- establishes every position; TSLA and AMD are later exited completely, leaving
    -- ten open holdings. Crypto supplies valid continuous-market activity in 1D/1W.
    FOR v_i IN 1..180 LOOP
        v_reserved_cash := NULL;
        v_accepted_at := NULL;
        v_terminal_at := NULL;
        v_filled_at := NULL;
        v_quote_as_of := NULL;
        v_rejection_reason := NULL;
        v_failure_reason := NULL;
        v_trade_value := NULL;

        IF v_i <= 176 THEN
            v_submitted_at := v_history_start
                + ((v_history_end - v_history_start)
                   * ((v_i - 1)::DOUBLE PRECISION / 175.0))
                + (CASE
                    WHEN MOD(v_i, 18) BETWEEN 0 AND 5
                        THEN (MOD(v_i, 6) - 3) * INTERVAL '1 day'
                    WHEN MOD(v_i, 18) IN (16, 17)
                        THEN INTERVAL '2 days'
                    ELSE INTERVAL '0 days'
                   END);
        ELSE
            v_submitted_at := CASE v_i
                WHEN 177 THEN CURRENT_TIMESTAMP - INTERVAL '6 days'
                WHEN 178 THEN CURRENT_TIMESTAMP - INTERVAL '2 days'
                WHEN 179 THEN CURRENT_TIMESTAMP - INTERVAL '12 hours'
                ELSE CURRENT_TIMESTAMP - INTERVAL '1 hour'
            END;
        END IF;

        IF v_i = ANY (v_terminal_indices) THEN
            v_symbol := v_symbols[1 + MOD(v_i * 7, ARRAY_LENGTH(v_symbols, 1))];
            v_order_type := CASE WHEN MOD(v_i, 2) = 0 THEN 'BUY' ELSE 'SELL' END;
        ELSIF v_i <= ARRAY_LENGTH(v_symbols, 1) THEN
            v_symbol := v_symbols[v_i];
            v_order_type := 'BUY';
        ELSIF v_i = 104 THEN
            v_symbol := 'TSLA';
            v_order_type := 'SELL';
        ELSIF v_i = 138 THEN
            v_symbol := 'AMD';
            v_order_type := 'SELL';
        ELSIF v_i = 177 THEN
            v_symbol := 'BTC/USD';
            v_order_type := 'BUY';
        ELSIF v_i = 178 THEN
            v_symbol := 'ETH/USD';
            v_order_type := 'SELL';
        ELSIF v_i = 179 THEN
            v_symbol := 'ETH/USD';
            v_order_type := 'BUY';
        ELSIF v_i = 180 THEN
            v_symbol := 'BTC/USD';
            v_order_type := 'BUY';
        ELSIF MOD(v_i, 4) = 0 THEN
            SELECT position.*
            INTO v_position
            FROM tmp_john_positions position
            WHERE position.quantity > CASE
                    WHEN position.symbol IN ('BTC/USD', 'ETH/USD') THEN 0.00000001
                    ELSE 1.00000000
                END
              AND position.symbol NOT IN ('TSLA', 'AMD')
            ORDER BY MD5(position.symbol || ':' || v_i::TEXT)
            LIMIT 1;

            IF FOUND THEN
                v_symbol := v_position.symbol;
                v_order_type := 'SELL';
            ELSE
                v_symbol := 'AAPL';
                v_order_type := 'BUY';
            END IF;
        ELSE
            v_symbol_index := 1 + MOD(v_i * 7, ARRAY_LENGTH(v_symbols, 1));
            LOOP
                v_symbol := v_symbols[v_symbol_index];
                EXIT WHEN NOT (v_symbol = 'TSLA' AND v_i > 104)
                          AND NOT (v_symbol = 'AMD' AND v_i > 138);
                v_symbol_index := 1 + MOD(v_symbol_index, ARRAY_LENGTH(v_symbols, 1));
            END LOOP;
            v_order_type := 'BUY';
        END IF;

        SELECT instrument.asset_class, instrument.base_price
        INTO STRICT v_asset_class, v_base_price
        FROM tmp_john_instruments instrument
        WHERE instrument.symbol = v_symbol;

        -- Historical equities are placed on weekdays during the regular US session.
        IF v_asset_class = 'EQUITY' THEN
            IF EXTRACT(ISODOW FROM v_submitted_at) = 6 THEN
                v_submitted_at := v_submitted_at + INTERVAL '2 days';
            ELSIF EXTRACT(ISODOW FROM v_submitted_at) = 7 THEN
                v_submitted_at := v_submitted_at + INTERVAL '1 day';
            END IF;
            v_submitted_at := DATE_TRUNC('day', v_submitted_at)
                + INTERVAL '14 hours 45 minutes'
                + MOD(v_i * 17, 55) * INTERVAL '1 minute'
                + MOD(v_i * 13, 50) * INTERVAL '1 second';
        END IF;

        v_progress := GREATEST(
            0.0,
            LEAST(
                1.0,
                EXTRACT(EPOCH FROM (v_submitted_at - v_history_start))
                    / EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - v_history_start))
            )
        );

        v_execution_price := ROUND((
            v_base_price
            * (0.52 + 0.48 * v_progress)::NUMERIC
            * (1.0 + 0.07 * SIN(v_i * 0.63 + CHAR_LENGTH(v_symbol)))::NUMERIC
            * (0.97 + MOD(v_i * 37 + CHAR_LENGTH(v_symbol), 7) / 100.0)::NUMERIC
        )::NUMERIC, 8);

        IF v_execution_price <= 0 THEN
            RAISE EXCEPTION 'Generated a nonpositive execution price for plan %.', v_i;
        END IF;

        IF v_i IN (104, 138) THEN
            SELECT position.*
            INTO STRICT v_position
            FROM tmp_john_positions position
            WHERE position.symbol = v_symbol
              AND position.quantity > 0;
            v_quantity := v_position.quantity;
        ELSIF v_i = 178 THEN
            SELECT position.*
            INTO STRICT v_position
            FROM tmp_john_positions position
            WHERE position.symbol = 'ETH/USD'
              AND position.quantity > 0;
            v_quantity := ROUND(v_position.quantity * 0.35, 8);
        ELSIF v_order_type = 'SELL' AND NOT (v_i = ANY (v_terminal_indices)) THEN
            SELECT position.*
            INTO STRICT v_position
            FROM tmp_john_positions position
            WHERE position.symbol = v_symbol
              AND position.quantity > 0;

            IF v_asset_class = 'EQUITY' THEN
                v_quantity := GREATEST(1, FLOOR(v_position.quantity * 0.30))::NUMERIC(20,8);
                IF v_quantity >= v_position.quantity THEN
                    v_quantity := v_position.quantity - 1;
                END IF;
            ELSE
                v_quantity := ROUND(v_position.quantity * 0.30, 8);
            END IF;
        ELSIF v_asset_class = 'EQUITY' THEN
            v_quantity := (2 + MOD(v_i * 3 + CHAR_LENGTH(v_symbol), 9))::NUMERIC(20,8);
        ELSIF v_symbol = 'BTC/USD' THEN
            v_quantity := (0.015 + MOD(v_i * 7, 6) * 0.010)::NUMERIC(20,8);
        ELSE
            v_quantity := (0.25 + MOD(v_i * 5, 6) * 0.15)::NUMERIC(20,8);
        END IF;

        IF v_quantity IS NULL OR v_quantity <= 0 THEN
            RAISE EXCEPTION 'Generated a nonpositive quantity for plan %.', v_i;
        END IF;

        IF v_i = ANY (v_terminal_indices) THEN
            IF v_i = ANY (v_rejected_indices) THEN
                v_status := 'REJECTED';
                v_terminal_at := v_submitted_at + INTERVAL '20 seconds';
                v_rejection_reason := CASE v_order_type
                    WHEN 'BUY' THEN 'INSUFFICIENT_FUNDS'
                    ELSE 'INSUFFICIENT_HOLDINGS'
                END;
            ELSE
                v_status := 'FAILED';
                v_accepted_at := v_submitted_at + INTERVAL '12 seconds';
                v_terminal_at := v_submitted_at + INTERVAL '5 minutes';
                v_failure_reason := 'MARKET_QUOTE_TIMEOUT';
                IF v_order_type = 'BUY' THEN
                    v_reserved_cash := ROUND(v_quantity * v_execution_price * 1.02, 16);
                END IF;
            END IF;
        ELSE
            v_status := 'FILLED';
            v_accepted_at := v_submitted_at + INTERVAL '12 seconds';
            v_filled_at := v_submitted_at + INTERVAL '45 seconds';
            v_quote_as_of := v_filled_at - INTERVAL '3 seconds';
            v_trade_value := ROUND(v_quantity * v_execution_price, 16);
            IF v_order_type = 'BUY' THEN
                v_reserved_cash := ROUND(v_trade_value * 1.02, 16);
            END IF;
        END IF;

        INSERT INTO tmp_john_order_plan (
            plan_id, instrument_id, symbol, order_type, quantity, status,
            reserved_cash, submitted_at, accepted_at, rejected_at, failed_at,
            filled_at, execution_price, execution_quote_as_of,
            rejection_reason, failure_reason, expected_trade_value
        )
        SELECT
            v_i,
            instrument.instrument_id,
            v_symbol,
            v_order_type,
            v_quantity,
            v_status,
            v_reserved_cash,
            v_submitted_at,
            v_accepted_at,
            CASE WHEN v_status = 'REJECTED' THEN v_terminal_at END,
            CASE WHEN v_status = 'FAILED' THEN v_terminal_at END,
            v_filled_at,
            CASE WHEN v_status = 'FILLED' THEN v_execution_price END,
            v_quote_as_of,
            v_rejection_reason,
            v_failure_reason,
            v_trade_value
        FROM tmp_john_instruments instrument
        WHERE instrument.symbol = v_symbol;

        IF v_status = 'FILLED' THEN
            INSERT INTO tmp_john_ledger (
                event_at, sort_order, transaction_type, plan_id, amount
            ) VALUES (
                v_filled_at,
                20,
                v_order_type,
                v_i,
                CASE WHEN v_order_type = 'BUY' THEN -v_trade_value ELSE v_trade_value END
            );

            SELECT position.*
            INTO v_position
            FROM tmp_john_positions position
            WHERE position.symbol = v_symbol;

            IF v_order_type = 'BUY' THEN
                IF NOT FOUND THEN
                    INSERT INTO tmp_john_positions (
                        instrument_id, symbol, quantity, average_cost, updated_at
                    )
                    SELECT
                        instrument.instrument_id,
                        v_symbol,
                        v_quantity,
                        v_execution_price::NUMERIC(30,16),
                        v_filled_at AT TIME ZONE 'UTC'
                    FROM tmp_john_instruments instrument
                    WHERE instrument.symbol = v_symbol;
                ELSIF v_position.quantity = 0 THEN
                    UPDATE tmp_john_positions
                    SET quantity = v_quantity,
                        average_cost = v_execution_price::NUMERIC(30,16),
                        updated_at = v_filled_at AT TIME ZONE 'UTC'
                    WHERE symbol = v_symbol;
                ELSE
                    v_new_quantity := v_position.quantity + v_quantity;
                    v_new_average_cost := ROUND(
                        (
                            v_position.quantity * v_position.average_cost
                            + v_quantity * v_execution_price
                        ) / v_new_quantity,
                        16
                    );
                    UPDATE tmp_john_positions
                    SET quantity = v_new_quantity,
                        average_cost = v_new_average_cost,
                        updated_at = v_filled_at AT TIME ZONE 'UTC'
                    WHERE symbol = v_symbol;
                END IF;
            ELSE
                IF NOT FOUND OR v_position.quantity < v_quantity THEN
                    RAISE EXCEPTION 'Plan % attempts to oversell %.', v_i, v_symbol;
                END IF;

                UPDATE tmp_john_positions
                SET quantity = quantity - v_quantity,
                    updated_at = v_filled_at AT TIME ZONE 'UTC'
                WHERE symbol = v_symbol;
            END IF;
        END IF;
    END LOOP;

    -- Calculate the complete chronological cash ledger from zero. This is done before
    -- inserting the client so account_balance can be inserted at its final value.
    FOR v_ledger IN
        SELECT ledger.*
        FROM tmp_john_ledger ledger
        ORDER BY ledger.event_at, ledger.sort_order, ledger.event_id
    LOOP
        v_balance := v_balance + v_ledger.amount;
        IF v_balance < 0 THEN
            RAISE EXCEPTION
                'Cash would become negative at planned ledger event % (balance %).',
                v_ledger.event_id,
                v_balance;
        END IF;

        UPDATE tmp_john_ledger
        SET balance_after = v_balance
        WHERE event_id = v_ledger.event_id;
    END LOOP;

    IF v_balance < 25000 THEN
        RAISE EXCEPTION 'Final demo cash balance % is not meaningful enough for new trades.', v_balance;
    END IF;

    -- Permanent writes begin here. Every statement below inserts rows owned by the
    -- newly generated client; no pre-existing permanent row is updated or deleted.
    INSERT INTO clients (
        first_name,
        last_name,
        email,
        password_hash,
        ssn,
        phone_number,
        account_balance,
        created_at,
        date_of_birth
    ) VALUES (
        'John',
        'Page',
        v_email,
        v_password_hash,
        v_ssn,
        '555-010-2486',
        v_balance,
        v_account_created,
        DATE '1982-04-17'
    )
    RETURNING client_id INTO v_client_id;

    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows <> 1 OR v_client_id IS NULL THEN
        RAISE EXCEPTION 'Expected exactly one newly inserted demo client.';
    END IF;

    FOR v_plan IN
        SELECT plan.*
        FROM tmp_john_order_plan plan
        ORDER BY plan.plan_id
    LOOP
        INSERT INTO orders (
            client_id,
            instrument_id,
            order_type,
            quantity,
            status,
            reserved_cash,
            submitted_at,
            accepted_at,
            rejected_at,
            failed_at,
            filled_at,
            execution_price,
            execution_quote_as_of,
            rejection_reason,
            failure_reason
        ) VALUES (
            v_client_id,
            v_plan.instrument_id,
            v_plan.order_type,
            v_plan.quantity,
            v_plan.status,
            v_plan.reserved_cash,
            v_plan.submitted_at,
            v_plan.accepted_at,
            v_plan.rejected_at,
            v_plan.failed_at,
            v_plan.filled_at,
            v_plan.execution_price,
            v_plan.execution_quote_as_of,
            v_plan.rejection_reason,
            v_plan.failure_reason
        )
        RETURNING order_id, trade_value
        INTO v_order_id, v_generated_trade_value;

        IF v_plan.status = 'FILLED'
           AND v_generated_trade_value <> v_plan.expected_trade_value THEN
            RAISE EXCEPTION
                'Generated trade value mismatch for plan %: expected %, received %.',
                v_plan.plan_id,
                v_plan.expected_trade_value,
                v_generated_trade_value;
        END IF;

        IF v_plan.status <> 'FILLED' AND v_generated_trade_value IS NOT NULL THEN
            RAISE EXCEPTION 'Non-filled plan % unexpectedly has a trade value.', v_plan.plan_id;
        END IF;

        INSERT INTO tmp_john_order_map (plan_id, order_id)
        VALUES (v_plan.plan_id, v_order_id);
    END LOOP;

    INSERT INTO transactions (
        client_id,
        order_id,
        transaction_type,
        amount,
        balance_after,
        created_at
    )
    SELECT
        v_client_id,
        order_map.order_id,
        ledger.transaction_type,
        ledger.amount,
        ledger.balance_after,
        ledger.event_at
    FROM tmp_john_ledger ledger
    LEFT JOIN tmp_john_order_map order_map
        ON order_map.plan_id = ledger.plan_id
    ORDER BY ledger.event_at, ledger.sort_order, ledger.event_id;

    INSERT INTO holdings (
        client_id,
        instrument_id,
        quantity,
        average_cost,
        updated_at
    )
    SELECT
        v_client_id,
        position.instrument_id,
        position.quantity,
        position.average_cost,
        position.updated_at
    FROM tmp_john_positions position
    WHERE position.quantity > 0
    ORDER BY position.instrument_id;

    -- Validate the persisted demo dataset before allowing the transaction to commit.
    IF (SELECT COUNT(*) FROM clients WHERE client_id = v_client_id AND email = v_email) <> 1 THEN
        RAISE EXCEPTION 'Demo client identity validation failed.';
    END IF;

    IF (SELECT COUNT(*) FROM orders WHERE client_id = v_client_id) <> 180 THEN
        RAISE EXCEPTION 'Expected exactly 180 demo orders.';
    END IF;

    IF (SELECT COUNT(*) FROM orders WHERE client_id = v_client_id AND status = 'FILLED') <> 172
       OR (SELECT COUNT(*) FROM orders WHERE client_id = v_client_id AND status = 'REJECTED') <> 4
       OR (SELECT COUNT(*) FROM orders WHERE client_id = v_client_id AND status = 'FAILED') <> 4
       OR EXISTS (
            SELECT 1 FROM orders
            WHERE client_id = v_client_id AND status IN ('SUBMITTED', 'ACCEPTED')
       ) THEN
        RAISE EXCEPTION 'Demo order status distribution is invalid.';
    END IF;

    SELECT COUNT(*)
    INTO v_bad_count
    FROM orders demo_order
    LEFT JOIN transactions trade_transaction
        ON trade_transaction.order_id = demo_order.order_id
    WHERE demo_order.client_id = v_client_id
      AND demo_order.status = 'FILLED'
      AND (
          trade_transaction.transaction_id IS NULL
          OR trade_transaction.client_id <> v_client_id
          OR trade_transaction.transaction_type <> demo_order.order_type
          OR trade_transaction.amount <> CASE demo_order.order_type
                WHEN 'BUY' THEN -demo_order.trade_value
                ELSE demo_order.trade_value
             END
          OR trade_transaction.created_at <> demo_order.filled_at
      );

    IF v_bad_count <> 0 THEN
        RAISE EXCEPTION '% filled orders have invalid or missing trade transactions.', v_bad_count;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM orders demo_order
        JOIN transactions trade_transaction
            ON trade_transaction.order_id = demo_order.order_id
        WHERE demo_order.client_id = v_client_id
          AND demo_order.status <> 'FILLED'
    ) THEN
        RAISE EXCEPTION 'A non-filled order has an associated trade transaction.';
    END IF;

    IF (SELECT COUNT(*) FROM transactions WHERE client_id = v_client_id) <> 188 THEN
        RAISE EXCEPTION 'Expected exactly 188 demo transactions.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM (
            SELECT
                transaction_id,
                balance_after,
                SUM(amount) OVER (
                    ORDER BY created_at, transaction_id
                    ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                ) AS replayed_balance
            FROM transactions
            WHERE client_id = v_client_id
        ) replay
        WHERE replay.balance_after <> replay.replayed_balance
           OR replay.balance_after < 0
    ) THEN
        RAISE EXCEPTION 'The persisted transaction ledger does not reconcile chronologically.';
    END IF;

    IF (
        SELECT latest_transaction.balance_after
        FROM transactions latest_transaction
        WHERE latest_transaction.client_id = v_client_id
        ORDER BY latest_transaction.created_at DESC, latest_transaction.transaction_id DESC
        LIMIT 1
    ) <> (
        SELECT client.account_balance
        FROM clients client
        WHERE client.client_id = v_client_id
    ) THEN
        RAISE EXCEPTION 'Final ledger balance does not match the client account balance.';
    END IF;

    IF (SELECT COUNT(*) FROM holdings WHERE client_id = v_client_id) <> 10
       OR EXISTS (
            SELECT 1 FROM holdings
            WHERE client_id = v_client_id AND quantity <= 0
       ) THEN
        RAISE EXCEPTION 'Expected exactly ten positive current holdings.';
    END IF;

    WITH executed_quantities AS (
        SELECT
            demo_order.instrument_id,
            SUM(CASE demo_order.order_type
                    WHEN 'BUY' THEN demo_order.quantity
                    ELSE -demo_order.quantity
                END) AS quantity
        FROM orders demo_order
        WHERE demo_order.client_id = v_client_id
          AND demo_order.status = 'FILLED'
        GROUP BY demo_order.instrument_id
    ), current_quantities AS (
        SELECT holding.instrument_id, holding.quantity
        FROM holdings holding
        WHERE holding.client_id = v_client_id
    )
    SELECT COUNT(*)
    INTO v_bad_count
    FROM executed_quantities executed
    FULL JOIN current_quantities current_holding
        ON current_holding.instrument_id = executed.instrument_id
    WHERE COALESCE(executed.quantity, 0) <> COALESCE(current_holding.quantity, 0);

    IF v_bad_count <> 0 THEN
        RAISE EXCEPTION 'Current holding quantities do not reconcile to filled orders.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM holdings holding
        JOIN tmp_john_positions planned
            ON planned.instrument_id = holding.instrument_id
        WHERE holding.client_id = v_client_id
          AND (
              holding.quantity <> planned.quantity
              OR holding.average_cost <> planned.average_cost
          )
    ) THEN
        RAISE EXCEPTION 'Current holdings do not match weighted-average-cost planning.';
    END IF;

    IF (
        SELECT MAX(submitted_at) - MIN(submitted_at)
        FROM orders
        WHERE client_id = v_client_id
    ) < INTERVAL '4 years 9 months' THEN
        RAISE EXCEPTION 'Demo orders do not cover approximately five years.';
    END IF;

    IF (
        SELECT COUNT(DISTINCT EXTRACT(YEAR FROM submitted_at))
        FROM orders
        WHERE client_id = v_client_id
    ) < 5 THEN
        RAISE EXCEPTION 'Demo orders do not include activity in at least five calendar years.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM orders
        WHERE client_id = v_client_id
          AND (
              submitted_at > CURRENT_TIMESTAMP
              OR accepted_at > CURRENT_TIMESTAMP
              OR rejected_at > CURRENT_TIMESTAMP
              OR failed_at > CURRENT_TIMESTAMP
              OR filled_at > CURRENT_TIMESTAMP
              OR execution_quote_as_of > CURRENT_TIMESTAMP
          )
    ) OR EXISTS (
        SELECT 1 FROM transactions
        WHERE client_id = v_client_id AND created_at > CURRENT_TIMESTAMP
    ) THEN
        RAISE EXCEPTION 'The demo dataset contains a future timestamp.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM orders demo_order
        JOIN instruments instrument
            ON instrument.instrument_id = demo_order.instrument_id
        WHERE demo_order.client_id = v_client_id
          AND instrument.asset_class = 'EQUITY'
          AND (
              EXTRACT(ISODOW FROM demo_order.submitted_at) NOT BETWEEN 1 AND 5
              OR demo_order.submitted_at::TIME < TIME '14:30:00'
              OR demo_order.submitted_at::TIME > TIME '20:59:59'
          )
    ) THEN
        RAISE EXCEPTION 'An equity order falls outside the synthetic weekday session.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM orders
        WHERE client_id = v_client_id
          AND status = 'FILLED'
          AND filled_at >= CURRENT_TIMESTAMP - INTERVAL '1 year'
    ) OR NOT EXISTS (
        SELECT 1 FROM orders
        WHERE client_id = v_client_id
          AND status = 'FILLED'
          AND filled_at >= CURRENT_TIMESTAMP - INTERVAL '1 month'
    ) OR NOT EXISTS (
        SELECT 1 FROM orders
        WHERE client_id = v_client_id
          AND status = 'FILLED'
          AND filled_at >= CURRENT_TIMESTAMP - INTERVAL '1 week'
    ) OR NOT EXISTS (
        SELECT 1 FROM orders
        WHERE client_id = v_client_id
          AND status = 'FILLED'
          AND filled_at >= CURRENT_TIMESTAMP - INTERVAL '1 day'
    ) THEN
        RAISE EXCEPTION 'Required recent filled activity is missing.';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM transactions
        WHERE client_id = v_client_id
          AND transaction_type IN ('DEPOSIT', 'WITHDRAWAL')
          AND created_at >= CURRENT_TIMESTAMP - INTERVAL '1 week'
    ) THEN
        RAISE EXCEPTION 'Required recent funding activity is missing.';
    END IF;

    RAISE NOTICE
        'Seeded %: 180 orders, 10 holdings, 188 transactions, final cash balance %.',
        v_email,
        v_balance;
END;
$seed_john_page$;

COMMIT;
