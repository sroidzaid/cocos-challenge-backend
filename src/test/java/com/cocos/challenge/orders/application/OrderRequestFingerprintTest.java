package com.cocos.challenge.orders.application;

import java.math.BigDecimal;
import java.util.UUID;

import com.cocos.challenge.orders.application.port.in.PlaceOrderCommand;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderSizing;
import com.cocos.challenge.orders.domain.OrderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRequestFingerprintTest {

    @Test
    void canonicalFormIsVersionedAndReadable() {
        assertThat(OrderRequestFingerprint.canonical(limit("900.50", new OrderSizing.Quantity(10))))
                .isEqualTo("v1|user=1|instrument=47|side=BUY|type=LIMIT|quantity=10|price=900.5");
        assertThat(OrderRequestFingerprint.canonical(new PlaceOrderCommand(1, 47, OrderSide.SELL, OrderType.MARKET,
                new OrderSizing.Amount(new BigDecimal("10000.00")), null, UUID.randomUUID())))
                .isEqualTo("v1|user=1|instrument=47|side=SELL|type=MARKET|amount=10000|price=-");
    }

    @Test
    void transfersHaveNoInstrumentNorTypeAndTheSideAndAmountChangeTheFingerprint() {
        assertThat(OrderRequestFingerprint.canonical(transfer(OrderSide.CASH_IN, "5000.00")))
                .isEqualTo("v1|user=1|instrument=-|side=CASH_IN|type=-|amount=5000|price=-");
        String reference = OrderRequestFingerprint.of(transfer(OrderSide.CASH_IN, "5000"));

        assertThat(OrderRequestFingerprint.of(transfer(OrderSide.CASH_IN, "5000.0"))).isEqualTo(reference);
        assertThat(OrderRequestFingerprint.of(transfer(OrderSide.CASH_OUT, "5000"))).isNotEqualTo(reference);
        assertThat(OrderRequestFingerprint.of(transfer(OrderSide.CASH_IN, "5001"))).isNotEqualTo(reference);
    }

    private static PlaceOrderCommand transfer(OrderSide side, String amount) {
        return new PlaceOrderCommand(1, null, side, null, new OrderSizing.Amount(new BigDecimal(amount)), null,
                UUID.randomUUID());
    }

    @Test
    void isASha256HexDigest() {
        assertThat(OrderRequestFingerprint.of(limit("900.50", new OrderSizing.Quantity(10))))
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    void equivalentNumberRepresentationsAreTheSameIntention() {
        String reference = OrderRequestFingerprint.of(limit("900.50", new OrderSizing.Amount(new BigDecimal("10"))));

        assertThat(OrderRequestFingerprint.of(limit("900.5", new OrderSizing.Amount(new BigDecimal("10.0")))))
                .isEqualTo(reference);
        assertThat(OrderRequestFingerprint.of(limit("900.50", new OrderSizing.Amount(new BigDecimal("10.00")))))
                .isEqualTo(reference);
    }

    @Test
    void theIdempotencyKeyIsNotPartOfTheFingerprint() {
        assertThat(OrderRequestFingerprint.of(limit("900.50", new OrderSizing.Quantity(10))))
                .isEqualTo(OrderRequestFingerprint.of(limit("900.50", new OrderSizing.Quantity(10))));
    }

    @Test
    void everyBusinessFieldChangesTheFingerprint() {
        String reference = OrderRequestFingerprint.of(limit("900.50", new OrderSizing.Quantity(10)));
        UUID key = UUID.randomUUID();

        assertThat(OrderRequestFingerprint.of(limit("900.51", new OrderSizing.Quantity(10)))).isNotEqualTo(reference);
        assertThat(OrderRequestFingerprint.of(limit("900.50", new OrderSizing.Quantity(11)))).isNotEqualTo(reference);
        assertThat(OrderRequestFingerprint.of(limit("900.50", new OrderSizing.Amount(BigDecimal.TEN))))
                .as("quantity=10 is not amount=10").isNotEqualTo(reference);
        assertThat(OrderRequestFingerprint.of(new PlaceOrderCommand(2, 47, OrderSide.BUY, OrderType.LIMIT,
                new OrderSizing.Quantity(10), new BigDecimal("900.50"), key))).isNotEqualTo(reference);
        assertThat(OrderRequestFingerprint.of(new PlaceOrderCommand(1, 54, OrderSide.BUY, OrderType.LIMIT,
                new OrderSizing.Quantity(10), new BigDecimal("900.50"), key))).isNotEqualTo(reference);
        assertThat(OrderRequestFingerprint.of(new PlaceOrderCommand(1, 47, OrderSide.SELL, OrderType.LIMIT,
                new OrderSizing.Quantity(10), new BigDecimal("900.50"), key))).isNotEqualTo(reference);
        assertThat(OrderRequestFingerprint.of(new PlaceOrderCommand(1, 47, OrderSide.BUY, OrderType.MARKET,
                new OrderSizing.Quantity(10), null, key))).isNotEqualTo(reference);
    }

    private static PlaceOrderCommand limit(String price, OrderSizing sizing) {
        return new PlaceOrderCommand(1, 47, OrderSide.BUY, OrderType.LIMIT, sizing, new BigDecimal(price),
                UUID.randomUUID());
    }
}
