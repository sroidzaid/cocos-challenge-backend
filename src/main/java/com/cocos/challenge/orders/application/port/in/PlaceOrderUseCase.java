package com.cocos.challenge.orders.application.port.in;

public interface PlaceOrderUseCase {

    /**
     * Places a BUY/SELL order. A valid order without enough cash or shares is not an error: it is persisted as
     * REJECTED and returned like any other.
     *
     * @throws com.cocos.challenge.shared.domain.NotFoundException              USER_NOT_FOUND, INSTRUMENT_NOT_FOUND
     * @throws com.cocos.challenge.shared.domain.BusinessRuleViolationException INSTRUMENT_NOT_TRADABLE,
     *                                                                          MARKET_PRICE_UNAVAILABLE,
     *                                                                          AMOUNT_TOO_LOW, QUANTITY_OUT_OF_RANGE
     */
    PlacedOrder place(PlaceOrderCommand command);
}
