-- V4: secondary indexes for the queries the API runs on every request.
--
-- Applied explicitly, never by the application. Requires V1 = database.sql (independent of V2 and V3).
-- Indexes only: no table, column, constraint or data changes. Not required for the application to start
-- (ddl-auto: validate does not check indexes); without V4 the same queries run, with sequential scans.
--
-- Why: cash, reservations and positions are derived from orders on every order placement and portfolio read, and the
-- latest quote is looked up per instrument. Without these indexes each of those queries scans the whole table, so its
-- cost grows with the total ledger instead of with the orders of one user.
--
-- The UNIQUE constraints of docs/schema-proposals/02-unique-constraints.sql (marketdata(instrumentid, date),
-- instruments(ticker)) are deliberately left out: they change what data is accepted, not only how it is read.
--
-- On a database with traffic, run each statement as CREATE INDEX CONCURRENTLY instead, outside a transaction
-- (CONCURRENTLY cannot run inside one) to avoid blocking writes on orders while the index is built.

BEGIN;

-- Ledger aggregates (cash, reserved cash/shares, net positions, FILLED orders of the portfolio):
-- all filter by WHERE userid = :userId AND status = '...'.
CREATE INDEX idx_orders_user_status ON orders (userid, status);

-- Latest quote per instrument: DISTINCT ON (instrumentid) ... ORDER BY instrumentid, date DESC, id DESC,
-- and findByInstrumentIdOrderByDateDescIdDesc. The column order and directions match, so no sort is needed.
CREATE INDEX idx_marketdata_instrument_date ON marketdata (instrumentid, date DESC, id DESC);

COMMIT;
