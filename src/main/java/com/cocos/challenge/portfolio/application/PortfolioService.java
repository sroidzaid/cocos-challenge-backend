package com.cocos.challenge.portfolio.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.cocos.challenge.instruments.application.port.in.InstrumentQuery;
import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.marketdata.application.port.in.MarketDataQuery;
import com.cocos.challenge.marketdata.domain.MarketQuote;
import com.cocos.challenge.orders.application.port.in.LedgerQuery;
import com.cocos.challenge.portfolio.application.port.in.GetPortfolioUseCase;
import com.cocos.challenge.portfolio.domain.Holding;
import com.cocos.challenge.portfolio.domain.Portfolio;
import com.cocos.challenge.portfolio.domain.PositionCalculator;
import com.cocos.challenge.portfolio.domain.PositionValuation;
import com.cocos.challenge.shared.domain.NotFoundException;
import com.cocos.challenge.users.application.port.in.UserLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the portfolio from several reads (cash, reservations, FILLED orders, instruments, prices).
 * <p>
 * They run in one read-only REPEATABLE READ transaction so that all of them see the same PostgreSQL snapshot: an
 * order committed in the middle cannot make the cash and the positions disagree. This is read consistency, not
 * locking: no row locks nor the account lock are taken, so the portfolio never blocks (nor waits for) order placement.
 * The ports of the other modules use REQUIRED propagation and join this transaction.
 */
@Service
class PortfolioService implements GetPortfolioUseCase {

    static final String USER_NOT_FOUND = "USER_NOT_FOUND";

    private final UserLookup users;
    private final LedgerQuery ledger;
    private final InstrumentQuery instruments;
    private final MarketDataQuery marketData;

    PortfolioService(UserLookup users, LedgerQuery ledger, InstrumentQuery instruments, MarketDataQuery marketData) {
        this.users = users;
        this.ledger = ledger;
        this.instruments = instruments;
        this.marketData = marketData;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Portfolio getPortfolio(int userId) {
        if (users.findById(userId).isEmpty()) {
            throw new NotFoundException(USER_NOT_FOUND, "User " + userId + " does not exist");
        }
        BigDecimal cash = ledger.cashBalance(userId).amount();
        BigDecimal reservedCash = ledger.reservations(userId).cash();
        List<Holding> holdings = PositionCalculator.holdings(ledger.filledOrders(userId));
        if (holdings.isEmpty()) {
            return Portfolio.of(userId, cash, reservedCash, List.of());
        }

        List<Integer> ids = holdings.stream().map(Holding::instrumentId).toList();
        Map<Integer, Instrument> instrumentsById = instruments.findAllByIds(ids).stream()
                .collect(Collectors.toMap(Instrument::id, Function.identity()));
        Map<Integer, MarketQuote> quotes = marketData.findLatestByInstrumentIds(ids);

        List<PositionValuation> valuations = holdings.stream()
                .map(holding -> {
                    Instrument instrument = instrumentsById.get(holding.instrumentId());
                    if (instrument == null) {
                        throw new IllegalStateException("Orders of user " + userId + " reference instrument "
                                + holding.instrumentId() + ", which does not exist");
                    }
                    MarketQuote quote = quotes.get(holding.instrumentId());
                    return PositionValuation.of(holding, instrument.ticker(), instrument.name(),
                            quote == null ? null : quote.lastPrice());
                })
                .toList();
        return Portfolio.of(userId, cash, reservedCash, valuations);
    }
}
