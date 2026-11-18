-- V2: persistent idempotency for order creation (POST /api/v1/users/{userId}/orders).
--
-- Applied explicitly, never by the application (ddl-auto: validate makes the application refuse to start while
-- this table is missing). Requires V1 = database.sql. Additive: no existing table, column or row is modified.
--
-- Run with ON_ERROR_STOP so that any error aborts the whole script:
--   psql -v ON_ERROR_STOP=1 -U <user> -d <db> -f db/migration/V2__create_order_idempotency.sql
-- It is intentionally NOT written with IF NOT EXISTS: if a table with this name already exists (applied twice, or a
-- conflicting object), the script fails and the transaction rolls back instead of silently accepting a schema that
-- may differ from this definition.
--
-- One row per order created through the API, inserted in the same transaction as the order:
--   * (user_id, idempotency_key) is unique: the same key can be reused by different users, never by the same one;
--   * request_hash identifies the original intention (SHA-256 of a canonical form of the request);
--   * order_id + order_status + rejection_reason are the minimal immutable snapshot of the creation result. The rest
--     of the original response (instrument, side, type, size, price, datetime) is never modified after creation and
--     is read from orders; only orders.status can change later (NEW -> CANCELLED), which is why the status at
--     creation is kept here. rejection_reason has no column in orders.

BEGIN;

CREATE TABLE order_idempotency (
    id               BIGSERIAL    PRIMARY KEY,
    user_id          INT          NOT NULL REFERENCES users (id),
    idempotency_key  UUID         NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    order_id         INT          NOT NULL REFERENCES orders (id),
    order_status     VARCHAR(20)  NOT NULL,
    rejection_reason VARCHAR(30),
    created_at       TIMESTAMP    NOT NULL,
    CONSTRAINT uq_order_idempotency_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT uq_order_idempotency_order UNIQUE (order_id),
    CONSTRAINT ck_order_idempotency_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_order_idempotency_order_status CHECK (order_status IN ('NEW', 'FILLED', 'REJECTED')),
    CONSTRAINT ck_order_idempotency_rejection CHECK (
        (order_status = 'REJECTED' AND rejection_reason IN ('INSUFFICIENT_FUNDS', 'INSUFFICIENT_HOLDINGS'))
        OR (order_status <> 'REJECTED' AND rejection_reason IS NULL))
);

COMMIT;
