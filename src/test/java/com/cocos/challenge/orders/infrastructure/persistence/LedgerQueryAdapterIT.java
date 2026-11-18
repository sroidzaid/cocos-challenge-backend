package com.cocos.challenge.orders.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.Map;

import com.cocos.challenge.orders.domain.NetPosition;
import com.cocos.challenge.orders.domain.Reservations;
import com.cocos.challenge.shared.infrastructure.persistence.PersistedDataIntegrityException;
import com.cocos.challenge.support.PersistenceIT;
import com.cocos.challenge.support.PreV3Schema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seed of database.sql is the oracle. User 1, FILLED orders only:
 * cash = 1,000,000 - 46,500 + 9,400 - 100,000 - 30,800 - 125,000 + 45,900 = 753,000.00;
 * PAMP (47) = 50 - 10 = 40; METR (54) = 500; BMA (31) = 20 - 30 = -10 (inconsistency present in the provided data).
 */
@PersistenceIT
@Import(LedgerQueryAdapter.class)
class LedgerQueryAdapterIT {

    @Autowired
    private LedgerQueryAdapter ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void cashIsComputedExactlyFromFilledOrdersOnly() {
        BigDecimal cash = ledger.cashBalance(1).amount();

        assertThat(cash).isEqualByComparingTo("753000.00");
        assertThat(cash.scale()).as("exact NUMERIC arithmetic, no rounding").isEqualTo(2);
    }

    @Test
    void netPositionsReflectPersistedMovementsIncludingNegativeOnes() {
        assertThat(ledger.netPositions(1)).containsExactly(
                new NetPosition(31, -10),
                new NetPosition(47, 40),
                new NetPosition(54, 500));
    }

    @Test
    void userWithoutOrdersHasZeroCashAndNoPositions() {
        assertThat(ledger.cashBalance(2).amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledger.netPositions(2)).isEmpty();
    }

    @Test
    void pendingCancelledAndRejectedOrdersDoNotAffectTheLedger() {
        jdbc.update("""
                INSERT INTO orders (instrumentid, userid, size, price, type, side, status, datetime) VALUES
                  (66, 2, 5000, 1, 'MARKET', 'CASH_IN', 'FILLED', now()),
                  (47, 2, 1, 900, 'LIMIT', 'BUY', 'NEW', now()),
                  (47, 2, 1, 900, 'LIMIT', 'BUY', 'CANCELLED', now()),
                  (47, 2, 99, 900, 'MARKET', 'BUY', 'REJECTED', now())""");

        assertThat(ledger.cashBalance(2).amount()).isEqualByComparingTo("5000.00");
        assertThat(ledger.netPositions(2)).isEmpty();
    }

    @Test
    void newBuyOrdersReserveCash() {
        // User 1 has two NEW LIMIT BUY orders in the seed: 50 x 710 + 60 x 1500 = 125,500.
        Reservations reservations = ledger.reservations(1);

        assertThat(reservations.cash()).isEqualByComparingTo("125500.00");
        assertThat(reservations.cash().scale()).isEqualTo(2);
        assertThat(reservations.sharesByInstrument()).isEmpty();
    }

    @Test
    void newSellOrdersReserveSharesPerInstrument() {
        jdbc.update("""
                INSERT INTO orders (instrumentid, userid, size, price, type, side, status, datetime) VALUES
                  (47, 1, 15, 960, 'LIMIT', 'SELL', 'NEW', now()),
                  (47, 1, 5, 970, 'LIMIT', 'SELL', 'NEW', now()),
                  (54, 1, 100, 300, 'LIMIT', 'SELL', 'NEW', now()),
                  (54, 1, 100, 300, 'LIMIT', 'SELL', 'CANCELLED', now())""");

        Reservations reservations = ledger.reservations(1);

        assertThat(reservations.sharesByInstrument()).containsExactlyInAnyOrderEntriesOf(Map.of(47, 20L, 54, 100L));
        assertThat(reservations.sharesOf(31)).isZero();
        assertThat(reservations.cash()).as("sells do not reserve cash").isEqualByComparingTo("125500.00");
    }

    @Test
    void userWithoutPendingOrdersReservesNothing() {
        assertThat(ledger.reservations(2).cash()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledger.reservations(2).sharesByInstrument()).isEmpty();
    }

    @Test
    void uninterpretablePendingOrderMakesTheReservationsQueryFail() {
        jdbc.update("""
                INSERT INTO orders (instrumentid, userid, size, price, type, side, status, datetime)
                VALUES (66, 2, 100, 1, 'MARKET', 'CASH_OUT', 'NEW', now())""");

        assertThatThrownBy(() -> ledger.reservations(2)).isInstanceOf(PersistedDataIntegrityException.class);
    }

    @Test
    void uninterpretableFilledOrderMakesTheCashQueryFail() {
        // V3 rejects this row; the application defense still matters for databases without V3.
        PreV3Schema.dropCheck(jdbc, "chk_orders_side");
        jdbc.update("""
                INSERT INTO orders (instrumentid, userid, size, price, type, side, status, datetime)
                VALUES (66, 2, 100, 1, 'MARKET', 'DEPOSIT', 'FILLED', now())""");

        assertThatThrownBy(() -> ledger.cashBalance(2)).isInstanceOf(PersistedDataIntegrityException.class);
    }

    @Test
    void filledTradeWithoutSizeMakesThePositionsQueryFail() {
        // V3 rejects this row; the application defense still matters for databases without V3.
        PreV3Schema.dropNotNull(jdbc, "size");
        jdbc.update("""
                INSERT INTO orders (instrumentid, userid, size, price, type, side, status, datetime)
                VALUES (47, 2, NULL, 900, 'MARKET', 'BUY', 'FILLED', now())""");

        assertThatThrownBy(() -> ledger.netPositions(2)).isInstanceOf(PersistedDataIntegrityException.class);
    }
}
