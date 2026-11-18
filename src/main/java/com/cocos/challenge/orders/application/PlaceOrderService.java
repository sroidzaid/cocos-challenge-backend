package com.cocos.challenge.orders.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.cocos.challenge.instruments.application.port.in.InstrumentQuery;
import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.marketdata.application.port.in.MarketDataQuery;
import com.cocos.challenge.marketdata.domain.MarketQuote;
import com.cocos.challenge.orders.application.port.in.PlaceOrderCommand;
import com.cocos.challenge.orders.application.port.in.PlaceOrderUseCase;
import com.cocos.challenge.orders.application.port.in.PlacedOrder;
import com.cocos.challenge.orders.application.port.out.IdempotencyRecord;
import com.cocos.challenge.orders.application.port.out.LedgerQueryPort;
import com.cocos.challenge.orders.application.port.out.OrderIdempotencyPort;
import com.cocos.challenge.orders.application.port.out.OrderRepositoryPort;
import com.cocos.challenge.orders.domain.Availability;
import com.cocos.challenge.orders.domain.NetPosition;
import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderPlacementPolicy;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.orders.domain.PlacementDecision;
import com.cocos.challenge.orders.domain.Reservations;
import com.cocos.challenge.shared.domain.BusinessRuleViolationException;
import com.cocos.challenge.shared.domain.ConflictException;
import com.cocos.challenge.shared.domain.NotFoundException;
import com.cocos.challenge.users.application.port.in.AccountLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Places BUY/SELL orders and CASH_IN/CASH_OUT transfers. Everything happens in one READ COMMITTED transaction whose
 * first statement is the account lock: the availability read afterwards sees every order committed by the previous
 * lock holder, so two concurrent orders or withdrawals of the same user can never spend the same cash or sell the
 * same shares. Business rejections are a result, not an exception: the REJECTED order is persisted in the same
 * transaction and committed.
 */
@Service
class PlaceOrderService implements PlaceOrderUseCase {

    static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    static final String INSTRUMENT_NOT_FOUND = "INSTRUMENT_NOT_FOUND";
    static final String INSTRUMENT_NOT_TRADABLE = "INSTRUMENT_NOT_TRADABLE";
    static final String MARKET_PRICE_UNAVAILABLE = "MARKET_PRICE_UNAVAILABLE";
    static final String AMOUNT_TOO_LOW = "AMOUNT_TOO_LOW";
    static final String QUANTITY_OUT_OF_RANGE = "QUANTITY_OUT_OF_RANGE";
    static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    private final AccountLock accountLock;
    private final InstrumentQuery instruments;
    private final MarketDataQuery marketData;
    private final LedgerQueryPort ledger;
    private final OrderRepositoryPort orders;
    private final OrderIdempotencyPort idempotency;
    private final Clock clock;

    PlaceOrderService(AccountLock accountLock, InstrumentQuery instruments, MarketDataQuery marketData,
            LedgerQueryPort ledger, OrderRepositoryPort orders, OrderIdempotencyPort idempotency, Clock clock) {
        this.accountLock = accountLock;
        this.instruments = instruments;
        this.marketData = marketData;
        this.ledger = ledger;
        this.orders = orders;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * Order of operations, all in one transaction:
     * <ol>
     *     <li>account lock (first statement): serializes every order of the user, so two requests with the same key
     *     (keys are per user) never run at the same time;</li>
     *     <li>idempotency lookup, after the lock: under READ COMMITTED it sees the key committed by the previous lock
     *     holder. Known key: same fingerprint returns the stored result, different fingerprint is a 409;</li>
     *     <li>validation, availability and decision (unchanged);</li>
     *     <li>insert the order and its idempotency record; the commit makes both visible or neither.</li>
     * </ol>
     * The unique (user, key) constraint is the database-level safety net for writers that do not take the lock.
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PlacedOrder place(PlaceOrderCommand command) {
        int userId = command.userId();
        if (!accountLock.lock(userId)) {
            throw new NotFoundException(USER_NOT_FOUND, "User " + userId + " does not exist");
        }
        String requestHash = OrderRequestFingerprint.of(command);
        Optional<IdempotencyRecord> previous = idempotency.find(userId, command.idempotencyKey());
        if (previous.isPresent()) {
            return replay(previous.get(), requestHash);
        }
        if (command.side().isTransfer()) {
            return placeTransfer(command, requestHash);
        }

        Instrument instrument = instruments.findById(command.instrumentId())
                .orElseThrow(() -> new NotFoundException(INSTRUMENT_NOT_FOUND,
                        "Instrument " + command.instrumentId() + " does not exist"));
        if (instrument.isCurrency()) {
            throw new BusinessRuleViolationException(INSTRUMENT_NOT_TRADABLE,
                    instrument.ticker() + " is a currency, not a tradable asset");
        }
        BigDecimal price = executionPrice(command, instrument);
        int size = size(command, price);

        Availability availability = availability(userId, command.side(), instrument.id());
        PlacementDecision decision = OrderPlacementPolicy.decide(command.side(), command.type(), size, price,
                availability);
        LocalDateTime now = now();
        Order saved = orders.save(Order.newTrade(userId, instrument.id(), command.side(), command.type(),
                decision.status(), size, price, now));
        return store(command, requestHash, saved, decision, now);
    }

    /**
     * CASH_IN/CASH_OUT on the local currency instrument (size = amount in whole pesos, price = 1). Runs under the same
     * lock and transaction as trades; a CASH_OUT is checked against the same available cash as a BUY.
     */
    private PlacedOrder placeTransfer(PlaceOrderCommand command, String requestHash) {
        int userId = command.userId();
        Instrument currency = instruments.findLocalCurrency()
                .orElseThrow(() -> new IllegalStateException("The local currency instrument ("
                        + Instrument.LOCAL_CURRENCY_TICKER + ") does not exist"));
        int amount = command.transferAmount();
        PlacementDecision decision = OrderPlacementPolicy.decideTransfer(command.side(), amount,
                cashAvailability(userId));
        LocalDateTime now = now();
        Order saved = orders.save(Order.newTransfer(userId, currency.id(), command.side(), decision.status(), amount,
                now));
        return store(command, requestHash, saved, decision, now);
    }

    private PlacedOrder store(PlaceOrderCommand command, String requestHash, Order saved, PlacementDecision decision,
            LocalDateTime now) {
        idempotency.save(new IdempotencyRecord(command.userId(), command.idempotencyKey(), requestHash, saved.id(),
                saved.status(), decision.reason(), now));
        return new PlacedOrder(saved, decision.reason(), false);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Rebuilds the original creation result: the order's immutable columns plus the status and rejection reason
     * stored at creation. A later cancellation does not change what the original request returned.
     */
    private PlacedOrder replay(IdempotencyRecord previous, String requestHash) {
        if (!previous.requestHash().equals(requestHash)) {
            throw new ConflictException(IDEMPOTENCY_KEY_REUSED, "Idempotency key " + previous.idempotencyKey()
                    + " was already used for a different order request");
        }
        Order order = orders.findByIdAndUserId(previous.orderId(), previous.userId())
                .orElseThrow(() -> new IllegalStateException("Order " + previous.orderId()
                        + " referenced by an idempotency key does not exist"));
        return new PlacedOrder(order.withStatus(previous.orderStatus()), previous.rejectionReason(), true);
    }

    /** MARKET: close of the latest quote. LIMIT: the limit price. Both with scale 2 (NUMERIC(10,2)). */
    private BigDecimal executionPrice(PlaceOrderCommand command, Instrument instrument) {
        BigDecimal price;
        if (command.type() == OrderType.LIMIT) {
            price = command.limitPrice();
        } else {
            price = marketData.findLatestByInstrumentId(instrument.id())
                    .map(MarketQuote::lastPrice)
                    .filter(close -> close.signum() > 0)
                    .orElseThrow(() -> new BusinessRuleViolationException(MARKET_PRICE_UNAVAILABLE,
                            instrument.ticker() + " has no market price; MARKET orders cannot be executed"));
        }
        return price.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static int size(PlaceOrderCommand command, BigDecimal price) {
        long shares = command.sizing().sharesAt(price);
        if (shares == 0) {
            throw new BusinessRuleViolationException(AMOUNT_TOO_LOW,
                    "The amount does not buy a single share at " + price.toPlainString());
        }
        if (shares > Integer.MAX_VALUE) {
            throw new BusinessRuleViolationException(QUANTITY_OUT_OF_RANGE,
                    "The resulting quantity exceeds the maximum of " + Integer.MAX_VALUE + " shares");
        }
        return (int) shares;
    }

    /** Read after the lock. Same formulas as the portfolio (cash and reservations come from the same queries). */
    private Availability availability(int userId, OrderSide side, int instrumentId) {
        Reservations reservations = ledger.reservations(userId);
        if (side == OrderSide.BUY) {
            return Availability.of(ledger.cashBalance(userId), reservations, 0, instrumentId);
        }
        long netQuantity = ledger.netPositions(userId).stream()
                .filter(position -> position.instrumentId() == instrumentId)
                .mapToLong(NetPosition::quantity)
                .sum();
        return new Availability(BigDecimal.ZERO, netQuantity - reservations.sharesOf(instrumentId));
    }

    /** Same available cash a BUY is checked against: cash balance minus the cash reserved by NEW BUY orders. */
    private Availability cashAvailability(int userId) {
        BigDecimal reserved = ledger.reservations(userId).cash();
        return new Availability(ledger.cashBalance(userId).amount().subtract(reserved), 0);
    }
}
