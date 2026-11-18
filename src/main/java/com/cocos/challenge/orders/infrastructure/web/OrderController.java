package com.cocos.challenge.orders.infrastructure.web;

import java.util.UUID;

import com.cocos.challenge.orders.application.port.in.CancelOrderUseCase;
import com.cocos.challenge.orders.application.port.in.PlaceOrderUseCase;
import com.cocos.challenge.orders.application.port.in.PlacedOrder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/{userId}/orders")
@Tag(name = "Orders", description = "Place BUY/SELL orders and CASH_IN/CASH_OUT transfers; cancel NEW orders")
class OrderController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PlaceOrderUseCase placeOrder;
    private final CancelOrderUseCase cancelOrder;

    OrderController(PlaceOrderUseCase placeOrder, CancelOrderUseCase cancelOrder) {
        this.placeOrder = placeOrder;
        this.cancelOrder = cancelOrder;
    }

    @PostMapping
    @Operation(summary = "Place a BUY/SELL order or a CASH_IN/CASH_OUT transfer",
            description = "MARKET orders execute at the last close and are FILLED; LIMIT orders stay NEW and reserve "
                    + "cash (BUY) or shares (SELL). Without enough available cash/shares the order is persisted as "
                    + "REJECTED (201, status REJECTED). Size by quantity or by amount (floor(amount / price)). "
                    + "CASH_IN/CASH_OUT take only an amount in whole pesos and are recorded on the ARS instrument "
                    + "(size = amount, price = 1, type MARKET): CASH_IN is always FILLED; CASH_OUT is FILLED when the "
                    + "amount does not exceed the available cash (cash minus cash reserved by NEW BUY orders), "
                    + "otherwise REJECTED with INSUFFICIENT_FUNDS. Idempotent: retrying with the same Idempotency-Key and the same request returns the original "
                    + "creation result (201, header Idempotent-Replayed: true) without placing another order.")
    @ApiResponse(responseCode = "201", description = "Order persisted: FILLED, NEW or REJECTED; or the original "
            + "result of this Idempotency-Key (Idempotent-Replayed: true)",
            headers = @Header(name = REPLAYED_HEADER, description = "true when the response is a replay",
                    schema = @Schema(type = "boolean")))
    @ApiResponse(responseCode = "400", description = "Invalid request or missing/invalid Idempotency-Key "
            + "(VALIDATION_FAILED, MALFORMED_REQUEST)")
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND, INSTRUMENT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_KEY_REUSED (same key, different request), "
            + "IDEMPOTENCY_KEY_CONFLICT (concurrent use of the key; retry)")
    @ApiResponse(responseCode = "422", description = "INSTRUMENT_NOT_TRADABLE, MARKET_PRICE_UNAVAILABLE, "
            + "AMOUNT_TOO_LOW, QUANTITY_OUT_OF_RANGE (nothing persisted)")
    @ApiResponse(responseCode = "503", description = "Account busy (lock timeout); retry")
    ResponseEntity<OrderResponse> place(
            @Parameter(description = "User id", example = "1") @PathVariable @Min(1) int userId,
            @Parameter(description = "Client-generated UUID identifying this order intention; reuse it when "
                    + "retrying", required = true, example = "4f9c2a1e-8d7b-4c3a-9e6f-1b2d3c4e5f60")
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) UUID idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        PlacedOrder placed = placeOrder.place(request.toCommand(userId, idempotencyKey));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (placed.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(OrderResponse.from(placed));
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel a NEW order", description = "Only NEW orders can be cancelled; the reservation is "
            + "released.")
    @ApiResponse(responseCode = "200", description = "Order CANCELLED")
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND, ORDER_NOT_FOUND (also for another user's order)")
    @ApiResponse(responseCode = "409", description = "ORDER_NOT_CANCELLABLE (not NEW)")
    @ApiResponse(responseCode = "503", description = "Account busy (lock timeout); retry")
    OrderResponse cancel(
            @Parameter(description = "User id", example = "1") @PathVariable @Min(1) int userId,
            @Parameter(description = "Order id", example = "5") @PathVariable @Min(1) int orderId) {
        return OrderResponse.from(cancelOrder.cancel(userId, orderId));
    }
}
