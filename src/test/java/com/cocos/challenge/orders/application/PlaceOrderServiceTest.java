package com.cocos.challenge.orders.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.cocos.challenge.instruments.application.port.in.InstrumentQuery;
import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.instruments.domain.InstrumentType;
import com.cocos.challenge.marketdata.application.port.in.MarketDataQuery;
import com.cocos.challenge.marketdata.domain.MarketQuote;
import com.cocos.challenge.orders.application.port.in.PlaceOrderCommand;
import com.cocos.challenge.orders.application.port.in.PlacedOrder;
import com.cocos.challenge.orders.application.port.out.IdempotencyRecord;
import com.cocos.challenge.orders.application.port.out.LedgerQueryPort;
import com.cocos.challenge.orders.application.port.out.OrderIdempotencyPort;
import com.cocos.challenge.orders.application.port.out.OrderRepositoryPort;
import com.cocos.challenge.orders.domain.CashBalance;
import com.cocos.challenge.orders.domain.NetPosition;
import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderSizing;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.orders.domain.RejectionReason;
import com.cocos.challenge.orders.domain.Reservations;
import com.cocos.challenge.shared.domain.BusinessRuleViolationException;
import com.cocos.challenge.shared.domain.ConflictException;
import com.cocos.challenge.shared.domain.NotFoundException;
import com.cocos.challenge.users.application.port.in.AccountLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceOrderServiceTest {

    private static final Instrument PAMP = new Instrument(47, "PAMP", "Pampa Holding S.A.", InstrumentType.ACCIONES);
    private static final Instrument ARS = new Instrument(66, "ARS", "PESOS", InstrumentType.MONEDA);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T13:15:00.123Z"),
            ZoneId.of("America/Argentina/Buenos_Aires"));

    @Mock
    private AccountLock accountLock;

    @Mock
    private InstrumentQuery instruments;

    @Mock
    private MarketDataQuery marketData;

    @Mock
    private LedgerQueryPort ledger;

    @Mock
    private OrderRepositoryPort orders;

    @Mock
    private OrderIdempotencyPort idempotency;

    private PlaceOrderService service;

    @BeforeEach
    void setUp() {
        service = new PlaceOrderService(accountLock, instruments, marketData, ledger, orders, idempotency, CLOCK);
    }

    @Test
    void buyMarketLocksTheAccountBeforeReadingAndPersistsFilledAtTheLastClose() {
        givenLockedUserAndPamp();
        givenClose("925.85");
        when(ledger.reservations(1)).thenReturn(new Reservations(new BigDecimal("125500.00"), Map.of()));
        when(ledger.cashBalance(1)).thenReturn(new CashBalance(new BigDecimal("753000.00")));
        returnSavedOrderWithId(12);

        PlacedOrder placed = service.place(command(OrderSide.BUY, OrderType.MARKET, new OrderSizing.Quantity(10), null));

        InOrder order = inOrder(accountLock, instruments, ledger, orders);
        order.verify(accountLock).lock(1);
        order.verify(instruments).findById(47);
        order.verify(ledger).reservations(1);
        order.verify(orders).save(any());
        assertThat(placed.order()).isEqualTo(new Order(12, 1, 47, OrderSide.BUY, OrderType.MARKET,
                OrderStatus.FILLED, 10, new BigDecimal("925.85"), LocalDateTime.of(2026, 11, 12, 10, 15)));
        assertThat(placed.rejectionReason()).isNull();
    }

    @Test
    void insufficientFundsIsPersistedAsRejectedWithoutException() {
        givenLockedUserAndPamp();
        givenClose("925.85");
        when(ledger.reservations(1)).thenReturn(new Reservations(new BigDecimal("125500.00"), Map.of()));
        when(ledger.cashBalance(1)).thenReturn(new CashBalance(new BigDecimal("753000.00")));
        returnSavedOrderWithId(13);

        PlacedOrder placed = service.place(
                command(OrderSide.BUY, OrderType.MARKET, new OrderSizing.Quantity(1000), null));

        assertThat(placed.order().status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(placed.order().size()).isEqualTo(1000);
        assertThat(placed.rejectionReason()).isEqualTo(RejectionReason.INSUFFICIENT_FUNDS);
    }

    @Test
    void sellLimitUsesNetQuantityMinusReservedSharesAndTheLimitPrice() {
        givenLockedUserAndPamp();
        when(ledger.reservations(1)).thenReturn(new Reservations(BigDecimal.ZERO, Map.of(47, 30L)));
        when(ledger.netPositions(1)).thenReturn(List.of(new NetPosition(31, -10), new NetPosition(47, 40)));
        returnSavedOrderWithId(14);

        PlacedOrder ok = service.place(
                command(OrderSide.SELL, OrderType.LIMIT, new OrderSizing.Quantity(10), new BigDecimal("1000.5")));
        PlacedOrder rejected = service.place(
                command(OrderSide.SELL, OrderType.LIMIT, new OrderSizing.Quantity(11), new BigDecimal("1000.5")));

        assertThat(ok.order().status()).isEqualTo(OrderStatus.NEW);
        assertThat(ok.order().price()).isEqualTo(new BigDecimal("1000.50"));
        assertThat(rejected.rejectionReason()).isEqualTo(RejectionReason.INSUFFICIENT_HOLDINGS);
        verifyNoInteractions(marketData);
    }

    @Test
    void amountIsConvertedToWholeSharesAtTheExecutionPrice() {
        givenLockedUserAndPamp();
        givenClose("925.85");
        when(ledger.reservations(1)).thenReturn(new Reservations(BigDecimal.ZERO, Map.of()));
        when(ledger.cashBalance(1)).thenReturn(new CashBalance(new BigDecimal("753000.00")));
        returnSavedOrderWithId(15);

        PlacedOrder placed = service.place(
                command(OrderSide.BUY, OrderType.MARKET, new OrderSizing.Amount(new BigDecimal("10000")), null));

        assertThat(placed.order().size()).isEqualTo(10);
    }

    @Test
    void amountBelowOneShareIsNotPersisted() {
        givenLockedUserAndPamp();
        givenClose("925.85");

        assertThatThrownBy(() -> service.place(
                command(OrderSide.BUY, OrderType.MARKET, new OrderSizing.Amount(new BigDecimal("100")), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("AMOUNT_TOO_LOW");
        verify(orders, never()).save(any());
    }

    @Test
    void quantityAboveTheIntRangeIsNotPersisted() {
        givenLockedUserAndPamp();

        assertThatThrownBy(() -> service.place(command(OrderSide.BUY, OrderType.LIMIT,
                new OrderSizing.Amount(new BigDecimal("9999999999.99")), new BigDecimal("0.01"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("QUANTITY_OUT_OF_RANGE");
        verify(orders, never()).save(any());
    }

    @Test
    void unknownUserIsNotFoundAndNothingIsRead() {
        when(accountLock.lock(999)).thenReturn(false);

        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(999, 47, OrderSide.BUY, OrderType.MARKET,
                new OrderSizing.Quantity(1), null, UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo("USER_NOT_FOUND");
        verifyNoInteractions(instruments, marketData, ledger, orders, idempotency);
    }

    @Test
    void unknownInstrumentIsNotFound() {
        when(accountLock.lock(1)).thenReturn(true);
        when(instruments.findById(47)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.place(command(OrderSide.BUY, OrderType.MARKET,
                new OrderSizing.Quantity(1), null)))
                .isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo("INSTRUMENT_NOT_FOUND");
    }

    @Test
    void currencyIsNotTradable() {
        when(accountLock.lock(1)).thenReturn(true);
        when(instruments.findById(66)).thenReturn(Optional.of(ARS));

        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(1, 66, OrderSide.BUY, OrderType.MARKET,
                new OrderSizing.Quantity(1), null, UUID.randomUUID())))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("INSTRUMENT_NOT_TRADABLE");
        verify(orders, never()).save(any());
    }

    @Test
    void marketOrderWithoutQuoteIsNotPersisted() {
        givenLockedUserAndPamp();
        when(marketData.findLatestByInstrumentId(47)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.place(command(OrderSide.BUY, OrderType.MARKET,
                new OrderSizing.Quantity(1), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("MARKET_PRICE_UNAVAILABLE");
        verify(orders, never()).save(any());
        verify(idempotency, never()).save(any());
    }

    @Test
    void newOrderStoresItsKeyWithTheCreationSnapshotAfterTheLock() {
        givenLockedUserAndPamp();
        givenClose("925.85");
        when(ledger.reservations(1)).thenReturn(new Reservations(new BigDecimal("125500.00"), Map.of()));
        when(ledger.cashBalance(1)).thenReturn(new CashBalance(new BigDecimal("753000.00")));
        returnSavedOrderWithId(13);
        PlaceOrderCommand command = command(OrderSide.BUY, OrderType.MARKET, new OrderSizing.Quantity(1000), null);

        PlacedOrder placed = service.place(command);

        InOrder order = inOrder(accountLock, idempotency, orders);
        order.verify(accountLock).lock(1);
        order.verify(idempotency).find(1, command.idempotencyKey());
        order.verify(orders).save(any());
        order.verify(idempotency).save(new IdempotencyRecord(1, command.idempotencyKey(),
                OrderRequestFingerprint.of(command), 13, OrderStatus.REJECTED, RejectionReason.INSUFFICIENT_FUNDS,
                LocalDateTime.of(2026, 11, 12, 10, 15)));
        assertThat(placed.replayed()).isFalse();
    }

    @Test
    void knownKeyWithTheSameRequestReplaysTheCreationResultWithoutPlacing() {
        PlaceOrderCommand command = command(OrderSide.BUY, OrderType.LIMIT, new OrderSizing.Quantity(10),
                new BigDecimal("900.50"));
        when(accountLock.lock(1)).thenReturn(true);
        when(idempotency.find(1, command.idempotencyKey())).thenReturn(Optional.of(new IdempotencyRecord(1,
                command.idempotencyKey(), OrderRequestFingerprint.of(command), 12, OrderStatus.NEW, null,
                LocalDateTime.of(2026, 11, 12, 10, 15))));
        // The order was cancelled after its creation.
        when(orders.findByIdAndUserId(12, 1)).thenReturn(Optional.of(new Order(12, 1, 47, OrderSide.BUY,
                OrderType.LIMIT, OrderStatus.CANCELLED, 10, new BigDecimal("900.50"),
                LocalDateTime.of(2026, 11, 12, 10, 15))));

        PlacedOrder placed = service.place(command);

        assertThat(placed.replayed()).isTrue();
        assertThat(placed.order().status()).as("creation result, not the current state").isEqualTo(OrderStatus.NEW);
        assertThat(placed.order().id()).isEqualTo(12);
        assertThat(placed.rejectionReason()).isNull();
        verifyNoInteractions(instruments, marketData, ledger);
        verify(orders, never()).save(any());
        verify(idempotency, never()).save(any());
    }

    @Test
    void replayedRejectionKeepsItsReason() {
        PlaceOrderCommand command = command(OrderSide.SELL, OrderType.MARKET, new OrderSizing.Quantity(100), null);
        when(accountLock.lock(1)).thenReturn(true);
        when(idempotency.find(1, command.idempotencyKey())).thenReturn(Optional.of(new IdempotencyRecord(1,
                command.idempotencyKey(), OrderRequestFingerprint.of(command), 14, OrderStatus.REJECTED,
                RejectionReason.INSUFFICIENT_HOLDINGS, LocalDateTime.of(2026, 11, 12, 10, 15))));
        when(orders.findByIdAndUserId(14, 1)).thenReturn(Optional.of(new Order(14, 1, 47, OrderSide.SELL,
                OrderType.MARKET, OrderStatus.REJECTED, 100, new BigDecimal("925.85"),
                LocalDateTime.of(2026, 11, 12, 10, 15))));

        PlacedOrder placed = service.place(command);

        assertThat(placed.rejectionReason()).isEqualTo(RejectionReason.INSUFFICIENT_HOLDINGS);
        assertThat(placed.order().status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void knownKeyWithADifferentRequestIsAConflict() {
        PlaceOrderCommand original = command(OrderSide.BUY, OrderType.MARKET, new OrderSizing.Quantity(10), null);
        PlaceOrderCommand different = new PlaceOrderCommand(1, 47, OrderSide.BUY, OrderType.MARKET,
                new OrderSizing.Quantity(11), null, original.idempotencyKey());
        when(accountLock.lock(1)).thenReturn(true);
        when(idempotency.find(1, original.idempotencyKey())).thenReturn(Optional.of(new IdempotencyRecord(1,
                original.idempotencyKey(), OrderRequestFingerprint.of(original), 12, OrderStatus.FILLED, null,
                LocalDateTime.of(2026, 11, 12, 10, 15))));

        assertThatThrownBy(() -> service.place(different))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        verifyNoInteractions(instruments, marketData, ledger);
        verify(orders, never()).save(any());
    }

    @Test
    void commandRejectsInconsistentInput() {
        assertThatThrownBy(() -> command(OrderSide.BUY, OrderType.MARKET, new OrderSizing.Quantity(1), BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(OrderSide.BUY, OrderType.LIMIT, new OrderSizing.Quantity(1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(OrderSide.BUY, OrderType.LIMIT, new OrderSizing.Quantity(1),
                new BigDecimal("1.001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(OrderSide.CASH_OUT, OrderType.MARKET, new OrderSizing.Quantity(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cashInLocksTheAccountAndPersistsAFilledTransferOnTheCurrency() {
        when(accountLock.lock(1)).thenReturn(true);
        when(instruments.findLocalCurrency()).thenReturn(Optional.of(ARS));
        when(ledger.reservations(1)).thenReturn(new Reservations(new BigDecimal("125500.00"), Map.of()));
        when(ledger.cashBalance(1)).thenReturn(new CashBalance(new BigDecimal("753000.00")));
        returnSavedOrderWithId(12);
        PlaceOrderCommand command = transfer(OrderSide.CASH_IN, "5000");

        PlacedOrder placed = service.place(command);

        InOrder order = inOrder(accountLock, idempotency, orders);
        order.verify(accountLock).lock(1);
        order.verify(idempotency).find(1, command.idempotencyKey());
        order.verify(orders).save(any());
        order.verify(idempotency).save(new IdempotencyRecord(1, command.idempotencyKey(),
                OrderRequestFingerprint.of(command), 12, OrderStatus.FILLED, null,
                LocalDateTime.of(2026, 11, 12, 10, 15)));
        assertThat(placed.order()).isEqualTo(new Order(12, 1, 66, OrderSide.CASH_IN, OrderType.MARKET,
                OrderStatus.FILLED, 5000, new BigDecimal("1.00"), LocalDateTime.of(2026, 11, 12, 10, 15)));
        assertThat(placed.rejectionReason()).isNull();
        verify(instruments, never()).findById(any(Integer.class));
        verifyNoInteractions(marketData);
    }

    @Test
    void cashOutWithinTheAvailableCashIsFilled() {
        givenLockedUserAndCurrency();
        // available = 753,000 - 125,500 reserved by NEW BUY orders = 627,500
        when(ledger.reservations(1)).thenReturn(new Reservations(new BigDecimal("125500.00"), Map.of()));
        when(ledger.cashBalance(1)).thenReturn(new CashBalance(new BigDecimal("753000.00")));
        returnSavedOrderWithId(12);

        PlacedOrder placed = service.place(transfer(OrderSide.CASH_OUT, "627500"));

        assertThat(placed.order().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(placed.order().side()).isEqualTo(OrderSide.CASH_OUT);
        assertThat(placed.order().size()).isEqualTo(627500);
        assertThat(placed.rejectionReason()).isNull();
    }

    @Test
    void cashOutAboveTheCashLeftAfterReservationsIsPersistedAsRejected() {
        givenLockedUserAndCurrency();
        when(ledger.reservations(1)).thenReturn(new Reservations(new BigDecimal("125500.00"), Map.of()));
        when(ledger.cashBalance(1)).thenReturn(new CashBalance(new BigDecimal("753000.00")));
        returnSavedOrderWithId(13);

        PlacedOrder placed = service.place(transfer(OrderSide.CASH_OUT, "627501"));

        assertThat(placed.order().status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(placed.order().size()).isEqualTo(627501);
        assertThat(placed.rejectionReason()).isEqualTo(RejectionReason.INSUFFICIENT_FUNDS);
        verify(idempotency).save(any());
    }

    @Test
    void transferOfAnUnknownUserIsNotFoundAndPersistsNothing() {
        when(accountLock.lock(1)).thenReturn(false);

        assertThatThrownBy(() -> service.place(transfer(OrderSide.CASH_IN, "100")))
                .isInstanceOf(NotFoundException.class)
                .extracting("code").isEqualTo("USER_NOT_FOUND");
        verifyNoInteractions(instruments, ledger, orders, idempotency);
    }

    @Test
    void transferCommandRequiresOnlyAWholeAmount() {
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> new PlaceOrderCommand(1, 66, OrderSide.CASH_IN, null,
                new OrderSizing.Amount(BigDecimal.TEN), null, key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlaceOrderCommand(1, null, OrderSide.CASH_IN, OrderType.MARKET,
                new OrderSizing.Amount(BigDecimal.TEN), null, key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlaceOrderCommand(1, null, OrderSide.CASH_OUT, null,
                new OrderSizing.Quantity(10), null, key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfer(OrderSide.CASH_IN, "10.50")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfer(OrderSide.CASH_IN, "2147483648"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transfer(OrderSide.CASH_IN, "2147483647").transferAmount()).isEqualTo(Integer.MAX_VALUE);
        assertThat(transfer(OrderSide.CASH_OUT, "100.00").transferAmount()).isEqualTo(100);
    }

    private void givenLockedUserAndCurrency() {
        when(accountLock.lock(1)).thenReturn(true);
        when(instruments.findLocalCurrency()).thenReturn(Optional.of(ARS));
    }

    private static PlaceOrderCommand transfer(OrderSide side, String amount) {
        return new PlaceOrderCommand(1, null, side, null, new OrderSizing.Amount(new BigDecimal(amount)), null,
                UUID.randomUUID());
    }

    private void givenLockedUserAndPamp() {
        when(accountLock.lock(1)).thenReturn(true);
        when(instruments.findById(47)).thenReturn(Optional.of(PAMP));
    }

    private void givenClose(String close) {
        when(marketData.findLatestByInstrumentId(47)).thenReturn(Optional.of(
                new MarketQuote(47, LocalDate.of(2023, 7, 14), new BigDecimal(close), new BigDecimal("921.80"))));
    }

    private void returnSavedOrderWithId(int id) {
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        when(orders.save(captor.capture())).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            return new Order(id, o.userId(), o.instrumentId(), o.side(), o.type(), o.status(), o.size(), o.price(),
                    o.datetime());
        });
    }

    private static PlaceOrderCommand command(OrderSide side, OrderType type, OrderSizing sizing, BigDecimal price) {
        return new PlaceOrderCommand(1, 47, side, type, sizing, price, UUID.randomUUID());
    }
}
