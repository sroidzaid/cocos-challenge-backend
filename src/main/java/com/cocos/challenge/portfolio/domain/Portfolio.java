package com.cocos.challenge.portfolio.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Snapshot of a user's account.
 *
 * @param cashBalance       ARS from FILLED orders (ledger balance), exact
 * @param availableCash     cash that can be used for new orders: {@code cashBalance} minus the cash reserved by NEW
 *                          BUY orders (decision D2), exact
 * @param totalAccountValue {@code cashBalance} plus the market value of every priced position (negative ones
 *                          included). When {@code valuationComplete} is {@code false} it is a <b>partial known
 *                          value</b>: positions without market data are not part of it
 * @param valuationComplete {@code false} when at least one position has no market price
 * @param positions         non-zero positions ordered by ticker
 */
public record Portfolio(
        int userId,
        BigDecimal cashBalance,
        BigDecimal availableCash,
        BigDecimal totalAccountValue,
        boolean valuationComplete,
        List<PositionValuation> positions,
        List<PortfolioWarning> warnings) {

    public Portfolio {
        Objects.requireNonNull(cashBalance, "cashBalance");
        Objects.requireNonNull(availableCash, "availableCash");
        Objects.requireNonNull(totalAccountValue, "totalAccountValue");
        positions = List.copyOf(positions);
        warnings = List.copyOf(warnings);
    }

    public static Portfolio of(int userId, BigDecimal cashBalance, BigDecimal reservedCash,
            List<PositionValuation> valuations) {
        List<PositionValuation> positions = valuations.stream()
                .sorted(Comparator.comparing(PositionValuation::ticker).thenComparing(PositionValuation::instrumentId))
                .toList();
        BigDecimal total = cashBalance;
        boolean complete = true;
        List<PortfolioWarning> warnings = new ArrayList<>();
        for (PositionValuation position : positions) {
            if (position.isPriced()) {
                total = total.add(position.marketValue());
            } else {
                complete = false;
                warnings.add(new PortfolioWarning(ValuationStatus.NO_MARKET_PRICE, position.instrumentId(),
                        position.ticker() + " has no market data: it is not valued and not included in the total"
                                + " account value"));
            }
            if (position.quantity() < 0) {
                warnings.add(new PortfolioWarning(ValuationStatus.NEGATIVE_QUANTITY, position.instrumentId(),
                        position.ticker() + " has a negative quantity (more shares sold than bought in the order"
                                + " history): average cost and return are not computed"));
            }
        }
        return new Portfolio(userId, cashBalance, cashBalance.subtract(reservedCash), total, complete, positions,
                warnings);
    }
}
