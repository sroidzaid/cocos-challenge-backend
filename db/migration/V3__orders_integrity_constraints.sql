-- V3: integrity rules for the orders ledger, enforced by PostgreSQL.
--
-- Applied explicitly, never by the application. Requires V1 = database.sql (V2 is independent of it).
-- Not required for the application to start: ddl-auto: validate checks tables, columns and types, not NOT NULL or
-- CHECK constraints. The application keeps validating everything it writes and failing explicitly on stored values it
-- cannot interpret; V3 adds the same protection at database level, also against writes made outside the API.
--
-- Why: cash, positions and reservations are derived from orders. A missing reference, an unknown side/type/status or
-- a non-positive size would silently corrupt every calculation.
--
-- Compatible with every row the application writes:
--   * BUY/SELL: instrument, positive whole size, positive price (MARKET = last close, LIMIT = limit price);
--   * CASH_IN/CASH_OUT (seed convention): ARS instrument, size = amount in whole pesos (> 0), price = 1;
--   * REJECTED orders are persisted with their real size and price.
-- price stays nullable on purpose (the provided model does not require it); when present it must be positive.
--
-- No data is modified to make it pass. If an existing row violates a rule, the ALTER fails and the transaction rolls
-- back: nothing is changed. Check before applying (expected result: 0 for every column):
--
--   SELECT count(*) FILTER (WHERE instrumentid IS NULL)                                     AS null_instrumentid,
--          count(*) FILTER (WHERE userid IS NULL)                                           AS null_userid,
--          count(*) FILTER (WHERE size IS NULL)                                             AS null_size,
--          count(*) FILTER (WHERE side IS NULL)                                             AS null_side,
--          count(*) FILTER (WHERE type IS NULL)                                             AS null_type,
--          count(*) FILTER (WHERE status IS NULL)                                           AS null_status,
--          count(*) FILTER (WHERE datetime IS NULL)                                         AS null_datetime,
--          count(*) FILTER (WHERE side NOT IN ('BUY', 'SELL', 'CASH_IN', 'CASH_OUT'))       AS invalid_side,
--          count(*) FILTER (WHERE type NOT IN ('MARKET', 'LIMIT'))                          AS invalid_type,
--          count(*) FILTER (WHERE status NOT IN ('NEW', 'FILLED', 'REJECTED', 'CANCELLED')) AS invalid_status,
--          count(*) FILTER (WHERE size <= 0)                                                AS invalid_size,
--          count(*) FILTER (WHERE price <= 0)                                               AS invalid_price
--   FROM orders;
--
-- Run with ON_ERROR_STOP so that any error aborts the whole script:
--   psql -v ON_ERROR_STOP=1 -U <user> -d <db> -f db/migration/V3__orders_integrity_constraints.sql
-- Applying it twice fails (the constraints already exist) and changes nothing.

BEGIN;

ALTER TABLE orders
    ALTER COLUMN instrumentid SET NOT NULL,
    ALTER COLUMN userid       SET NOT NULL,
    ALTER COLUMN size         SET NOT NULL,
    ALTER COLUMN side         SET NOT NULL,
    ALTER COLUMN type         SET NOT NULL,
    ALTER COLUMN status       SET NOT NULL,
    ALTER COLUMN datetime     SET NOT NULL;

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_side   CHECK (side IN ('BUY', 'SELL', 'CASH_IN', 'CASH_OUT')),
    ADD CONSTRAINT chk_orders_type   CHECK (type IN ('MARKET', 'LIMIT')),
    ADD CONSTRAINT chk_orders_status CHECK (status IN ('NEW', 'FILLED', 'REJECTED', 'CANCELLED')),
    ADD CONSTRAINT chk_orders_size   CHECK (size > 0),
    ADD CONSTRAINT chk_orders_price  CHECK (price IS NULL OR price > 0);

COMMIT;
