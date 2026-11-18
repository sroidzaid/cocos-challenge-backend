package com.cocos.challenge;

import java.util.List;

import com.cocos.challenge.support.ApplicationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executable guarantee that running the application does not modify the schema: with the full context up (Hibernate
 * validation done), the database contains exactly database.sql (V1, the tables provided by Cocos) plus the approved,
 * explicitly applied migrations, and nothing else:
 * <ul>
 *     <li>V2__create_order_idempotency.sql: the new order_idempotency table;</li>
 *     <li>V3__orders_integrity_constraints.sql: NOT NULL and CHECK constraints on orders (no column or data change);</li>
 *     <li>V4__lookup_indexes.sql: two secondary indexes, on orders and marketdata (no constraint or data change).</li>
 * </ul>
 * The expected values are transcribed from those scripts. Names are compared with the "C" collation so the order does
 * not depend on the server locale.
 */
@ApplicationIT
class SchemaIntegrityIT {

    private static final String ORIGINAL_TABLES = "('instruments', 'marketdata', 'orders', 'users')";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void tablesAreTheProvidedOnesPlusTheApprovedMigration() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' ORDER BY table_name COLLATE "C\"""", String.class))
                .containsExactly("instruments", "marketdata", "order_idempotency", "orders", "users");
    }

    /** Same names and types as database.sql; only the nullability of orders changes, by V3 (price stays nullable). */
    @Test
    void originalColumnsKeepTheirNamesAndTypes() {
        assertThat(columnsOf("table_name IN " + ORIGINAL_TABLES)).containsExactly(
                "instruments.id:integer:NO",
                "instruments.ticker:character varying(10):YES",
                "instruments.name:character varying(255):YES",
                "instruments.type:character varying(10):YES",
                "marketdata.id:integer:NO",
                "marketdata.instrumentid:integer:YES",
                "marketdata.high:numeric(10,2):YES",
                "marketdata.low:numeric(10,2):YES",
                "marketdata.open:numeric(10,2):YES",
                "marketdata.close:numeric(10,2):YES",
                "marketdata.previousclose:numeric(10,2):YES",
                "marketdata.date:date:YES",
                "orders.id:integer:NO",
                "orders.instrumentid:integer:NO",
                "orders.userid:integer:NO",
                "orders.size:integer:NO",
                "orders.price:numeric(10,2):YES",
                "orders.type:character varying(10):NO",
                "orders.side:character varying(10):NO",
                "orders.status:character varying(20):NO",
                "orders.datetime:timestamp without time zone:NO",
                "users.id:integer:NO",
                "users.email:character varying(255):YES",
                "users.accountnumber:character varying(20):YES");
    }

    @Test
    void idempotencyTableMatchesTheMigration() {
        assertThat(columnsOf("table_name = 'order_idempotency'")).containsExactly(
                "order_idempotency.id:bigint:NO",
                "order_idempotency.user_id:integer:NO",
                "order_idempotency.idempotency_key:uuid:NO",
                "order_idempotency.request_hash:character varying(64):NO",
                "order_idempotency.order_id:integer:NO",
                "order_idempotency.order_status:character varying(20):NO",
                "order_idempotency.rejection_reason:character varying(30):YES",
                "order_idempotency.created_at:timestamp without time zone:NO");

        assertThat(jdbc.queryForList("""
                SELECT c.conname || ':' || c.contype::text
                FROM pg_constraint c
                WHERE c.conrelid = 'order_idempotency'::regclass
                ORDER BY c.conname COLLATE "C\"""", String.class))
                .containsExactly(
                        "ck_order_idempotency_order_status:c",
                        "ck_order_idempotency_rejection:c",
                        "ck_order_idempotency_request_hash:c",
                        "order_idempotency_order_id_fkey:f",
                        "order_idempotency_pkey:p",
                        "order_idempotency_user_id_fkey:f",
                        "uq_order_idempotency_order:u",
                        "uq_order_idempotency_user_key:u");
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'uq_order_idempotency_user_key'""",
                String.class)).isEqualTo("UNIQUE (user_id, idempotency_key)");
    }

    @Test
    void ordersIntegrityConstraintsMatchV3() {
        assertThat(jdbc.queryForList("""
                SELECT conname FROM pg_constraint
                WHERE conrelid = 'orders'::regclass AND contype = 'c'
                ORDER BY conname COLLATE "C\"""", String.class))
                .containsExactly("chk_orders_price", "chk_orders_side", "chk_orders_size", "chk_orders_status",
                        "chk_orders_type");
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_orders_size'", String.class))
                .isEqualTo("CHECK ((size > 0))");
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_orders_price'", String.class))
                .isEqualTo("CHECK (((price IS NULL) OR (price > (0)::numeric)))");
    }

    /** V4: plain (non-unique) indexes with the exact columns and directions of the queries they serve. */
    @Test
    void lookupIndexesMatchV4() {
        assertThat(jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_orders_user_status'", String.class))
                .isEqualTo("CREATE INDEX idx_orders_user_status ON public.orders USING btree (userid, status)");
        assertThat(jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_marketdata_instrument_date'", String.class))
                .isEqualTo("CREATE INDEX idx_marketdata_instrument_date ON public.marketdata "
                        + "USING btree (instrumentid, date DESC, id DESC)");
    }

    @Test
    void originalConstraintsAreUnchangedAndIndexesAndSequencesAreExactlyTheExpected() {
        List<String> originalConstraints = jdbc.queryForList("""
                SELECT c.conrelid::regclass::text || ':' || c.contype::text
                FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
                WHERE n.nspname = 'public' AND c.conrelid::regclass::text IN %s
                ORDER BY 1""".formatted(ORIGINAL_TABLES), String.class);
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' ORDER BY indexname COLLATE \"C\"",
                String.class);
        List<String> sequences = jdbc.queryForList("""
                SELECT sequence_name FROM information_schema.sequences
                WHERE sequence_schema = 'public' ORDER BY sequence_name COLLATE "C\"""", String.class);

        // p = primary key, f = foreign key (orders -> instruments/users, marketdata -> instruments): from database.sql.
        // c = the 5 CHECK constraints of V3 on orders. No UNIQUE on the original tables.
        assertThat(originalConstraints).containsExactlyInAnyOrder(
                "instruments:p", "users:p", "orders:p", "orders:f", "orders:f", "marketdata:p", "marketdata:f",
                "orders:c", "orders:c", "orders:c", "orders:c", "orders:c");
        assertThat(indexes).containsExactly("idx_marketdata_instrument_date", "idx_orders_user_status",
                "instruments_pkey", "marketdata_pkey", "order_idempotency_pkey", "orders_pkey",
                "uq_order_idempotency_order", "uq_order_idempotency_user_key", "users_pkey");
        assertThat(sequences).containsExactly("instruments_id_seq", "marketdata_id_seq", "order_idempotency_id_seq",
                "orders_id_seq", "users_id_seq");
    }

    private List<String> columnsOf(String tableFilter) {
        return jdbc.queryForList("""
                SELECT table_name || '.' || column_name || ':' || data_type
                       || COALESCE('(' || character_maximum_length || ')', '')
                       || CASE WHEN data_type = 'numeric' THEN '(' || numeric_precision || ',' || numeric_scale || ')'
                               ELSE '' END
                       || ':' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND %s
                ORDER BY table_name, ordinal_position""".formatted(tableFilter), String.class);
    }
}
