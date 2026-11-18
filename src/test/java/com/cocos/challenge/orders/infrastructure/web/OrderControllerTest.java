package com.cocos.challenge.orders.infrastructure.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.cocos.challenge.orders.application.port.in.CancelOrderUseCase;
import com.cocos.challenge.orders.application.port.in.PlaceOrderCommand;
import com.cocos.challenge.orders.application.port.in.PlaceOrderUseCase;
import com.cocos.challenge.orders.application.port.in.PlacedOrder;
import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderSizing;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.orders.domain.RejectionReason;
import com.cocos.challenge.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebMvcTest(OrderController.class)
@ActiveProfiles("test")
class OrderControllerTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 12, 10, 15);
    private static final UUID KEY = UUID.fromString("4f9c2a1e-8d7b-4c3a-9e6f-1b2d3c4e5f60");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private PlaceOrderUseCase placeOrder;

    @MockitoBean
    private CancelOrderUseCase cancelOrder;

    @Test
    void placesAnOrderAndReturnsTheContract() {
        when(placeOrder.place(any())).thenReturn(new PlacedOrder(new Order(12, 1, 47, OrderSide.BUY, OrderType.LIMIT,
                OrderStatus.NEW, 10, new BigDecimal("900.50"), AT), null, false));

        MvcTestResult result = post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "LIMIT", "quantity": 10, "price": 900.50}""");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).doesNotContainHeader("Idempotent-Replayed");
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"id": 12, "userId": 1, "instrumentId": 47, "side": "BUY", "type": "LIMIT", "status": "NEW",
                 "size": 10, "price": 900.50, "datetime": "2026-11-12T10:15:00", "rejectionReason": null}""");
        ArgumentCaptor<PlaceOrderCommand> command = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(placeOrder).place(command.capture());
        assertThat(command.getValue()).isEqualTo(new PlaceOrderCommand(1, 47, OrderSide.BUY, OrderType.LIMIT,
                new OrderSizing.Quantity(10), new BigDecimal("900.50"), KEY));
    }

    @Test
    void replayIsACreatedResponseMarkedAsReplayed() {
        when(placeOrder.place(any())).thenReturn(new PlacedOrder(new Order(12, 1, 47, OrderSide.BUY, OrderType.LIMIT,
                OrderStatus.NEW, 10, new BigDecimal("900.50"), AT), null, true));

        MvcTestResult result = post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "LIMIT", "quantity": 10, "price": 900.50}""");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).hasHeader("Idempotent-Replayed", "true");
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(12);
    }

    @Test
    void missingIdempotencyKeyIsAValidationError() {
        MvcTestResult result = mvc.post().uri("/api/v1/users/1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 1}""").exchange();

        assertValidationError(result, "Idempotency-Key");
    }

    @Test
    void idempotencyKeyMustBeAUuid() {
        MvcTestResult result = mvc.post().uri("/api/v1/users/1/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "not-a-uuid")
                .content("""
                        {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 1}""").exchange();

        assertValidationError(result, "Idempotency-Key");
    }

    @Test
    void reusedKeyIsAConflict() {
        when(placeOrder.place(any())).thenThrow(new ConflictException("IDEMPOTENCY_KEY_REUSED", "different request"));

        MvcTestResult result = post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 1}""");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void rejectedOrderIsACreatedResourceWithItsReason() {
        when(placeOrder.place(any())).thenReturn(new PlacedOrder(new Order(13, 1, 47, OrderSide.SELL,
                OrderType.MARKET, OrderStatus.REJECTED, 100, new BigDecimal("925.85"), AT),
                RejectionReason.INSUFFICIENT_HOLDINGS, false));

        MvcTestResult result = post(1, """
                {"instrumentId": 47, "side": "SELL", "type": "MARKET", "quantity": 100}""");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(result).bodyJson().extractingPath("$.rejectionReason").isEqualTo("INSUFFICIENT_HOLDINGS");
    }

    @Test
    void amountIsMappedToAnAmountSizing() {
        when(placeOrder.place(any())).thenReturn(new PlacedOrder(new Order(14, 1, 47, OrderSide.BUY,
                OrderType.MARKET, OrderStatus.FILLED, 10, new BigDecimal("925.85"), AT), null, false));

        assertThat(post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "amount": 10000.50}"""))
                .hasStatus(HttpStatus.CREATED);
        ArgumentCaptor<PlaceOrderCommand> command = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(placeOrder).place(command.capture());
        assertThat(command.getValue().sizing()).isEqualTo(new OrderSizing.Amount(new BigDecimal("10000.50")));
    }

    @Test
    void marketOrderWithPriceIsRejected() {
        assertValidationError(post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 1, "price": 10}"""),
                "priceMatchingType");
    }

    @Test
    void limitOrderWithoutPriceIsRejected() {
        assertValidationError(post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "LIMIT", "quantity": 1}"""), "priceMatchingType");
    }

    @Test
    void quantityAndAmountTogetherOrNeitherAreRejected() {
        assertValidationError(post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 1, "amount": 100}"""),
                "quantityOrAmount");
        assertValidationError(post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET"}"""), "quantityOrAmount");
    }

    @Test
    void placesATransferWithOnlyAnAmount() {
        when(placeOrder.place(any())).thenReturn(new PlacedOrder(new Order(12, 1, 66, OrderSide.CASH_IN,
                OrderType.MARKET, OrderStatus.FILLED, 5000, new BigDecimal("1.00"), AT), null, false));

        MvcTestResult result = post(1, """
                {"side": "CASH_IN", "amount": 5000}""");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"id": 12, "userId": 1, "instrumentId": 66, "side": "CASH_IN", "type": "MARKET", "status": "FILLED",
                 "size": 5000, "price": 1.00, "datetime": "2026-11-12T10:15:00", "rejectionReason": null}""");
        ArgumentCaptor<PlaceOrderCommand> command = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(placeOrder).place(command.capture());
        assertThat(command.getValue()).isEqualTo(new PlaceOrderCommand(1, null, OrderSide.CASH_IN, null,
                new OrderSizing.Amount(new BigDecimal("5000")), null, KEY));
    }

    @Test
    void rejectedCashOutReturnsItsReason() {
        when(placeOrder.place(any())).thenReturn(new PlacedOrder(new Order(13, 1, 66, OrderSide.CASH_OUT,
                OrderType.MARKET, OrderStatus.REJECTED, 900000, new BigDecimal("1.00"), AT),
                RejectionReason.INSUFFICIENT_FUNDS, false));

        MvcTestResult result = post(1, """
                {"side": "CASH_OUT", "amount": 900000}""");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(result).bodyJson().extractingPath("$.rejectionReason").isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void tradeFieldsAreNotAllowedOnTransfers() {
        assertValidationError(post(1, """
                {"instrumentId": 66, "side": "CASH_IN", "amount": 100}"""), "instrumentId");
        assertValidationError(post(1, """
                {"side": "CASH_OUT", "type": "MARKET", "amount": 100}"""), "type");
        assertValidationError(post(1, """
                {"side": "CASH_IN", "type": "LIMIT", "amount": 100, "price": 1}"""), "price");
        assertValidationError(post(1, """
                {"side": "CASH_IN", "quantity": 100}"""), "quantity");
        assertValidationError(post(1, """
                {"instrumentId": 66, "side": "CASH_IN", "type": "MARKET", "quantity": 1}"""), "amount");
    }

    @Test
    void invalidTransferAmountsAreRejected() {
        assertValidationError(post(1, """
                {"side": "CASH_IN"}"""), "quantityOrAmount");
        assertValidationError(post(1, """
                {"side": "CASH_IN", "amount": 0}"""), "amount");
        assertValidationError(post(1, """
                {"side": "CASH_OUT", "amount": -100}"""), "amount");
        assertValidationError(post(1, """
                {"side": "CASH_IN", "amount": 100.50}"""), "amount");
        assertValidationError(post(1, """
                {"side": "CASH_OUT", "amount": 2147483648}"""), "amount");
    }

    @Test
    void transferAmountMustBeWrittenWithoutDecimals() {
        assertValidationError(post(1, """
                {"side": "CASH_OUT", "amount": 100.00}"""), "amount");
    }

    @Test
    void invalidNumbersAreRejected() {
        assertValidationError(post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 0}"""), "quantity");
        assertValidationError(post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": -5}"""), "quantity");
        assertValidationError(post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "amount": 0}"""), "amount");
        assertValidationError(post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "amount": 10.001}"""), "amount");
        assertValidationError(post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "LIMIT", "quantity": 1, "price": 900.505}"""), "price");
        assertValidationError(post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "LIMIT", "quantity": 1, "price": 100000000.00}"""),
                "price");
        assertValidationError(post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "LIMIT", "quantity": 1, "price": -1}"""), "price");
    }

    @Test
    void missingFieldsAreRejected() {
        assertValidationError(post(1, """
                {"side": "BUY", "type": "MARKET", "quantity": 1}"""), "instrumentId");
        assertValidationError(post(1, """
                {"instrumentId": 47, "type": "MARKET", "quantity": 1}"""), "side");
        assertValidationError(post(1, """
                {"instrumentId": 47, "side": "BUY", "quantity": 1}"""), "type");
    }

    @Test
    void invalidUserIdIsRejected() {
        assertValidationError(post(0, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 1}"""), "userId");
    }

    @Test
    void unknownEnumValueAndFractionalQuantityAreMalformed() {
        assertMalformed(post(1, """
                {"instrumentId": 47, "side": "HOLD", "type": "MARKET", "quantity": 1}"""));
        assertMalformed(post(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 1.5}"""));
        assertMalformed(post(1, "{not json"));
    }

    @Test
    void cancelReturnsTheCancelledOrder() {
        when(cancelOrder.cancel(1, 5)).thenReturn(new Order(5, 1, 45, OrderSide.BUY, OrderType.LIMIT,
                OrderStatus.CANCELLED, 50, new BigDecimal("710.00"), LocalDateTime.of(2023, 7, 12, 15, 14, 20)));

        MvcTestResult result = mvc.post().uri("/api/v1/users/1/orders/5/cancel").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"id": 5, "userId": 1, "instrumentId": 45, "side": "BUY", "type": "LIMIT", "status": "CANCELLED",
                 "size": 50, "price": 710.00, "datetime": "2023-07-12T15:14:20", "rejectionReason": null}""");
    }

    @Test
    void cancellingANonNewOrderIsAConflict() {
        when(cancelOrder.cancel(1, 1)).thenThrow(new ConflictException("ORDER_NOT_CANCELLABLE", "Order 1 is FILLED"));

        MvcTestResult result = mvc.post().uri("/api/v1/users/1/orders/1/cancel").exchange();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ORDER_NOT_CANCELLABLE");
    }

    @Test
    void cancelWithInvalidIdsIsRejected() {
        assertValidationError(mvc.post().uri("/api/v1/users/1/orders/0/cancel").exchange(), "orderId");
        verifyNoInteractions(cancelOrder);
    }

    private MvcTestResult post(int userId, String body) {
        return mvc.post().uri("/api/v1/users/{userId}/orders", userId).header("Idempotency-Key", KEY.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private void assertValidationError(MvcTestResult result, String field) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().contains(field);
        verifyNoInteractions(placeOrder);
    }

    private void assertMalformed(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
        verifyNoInteractions(placeOrder);
    }
}
