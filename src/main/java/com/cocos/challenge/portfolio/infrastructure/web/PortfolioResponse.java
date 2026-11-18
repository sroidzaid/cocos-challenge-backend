package com.cocos.challenge.portfolio.infrastructure.web;

import java.math.BigDecimal;
import java.util.List;

import com.cocos.challenge.portfolio.domain.Portfolio;
import com.cocos.challenge.portfolio.domain.PortfolioWarning;
import com.cocos.challenge.portfolio.domain.PositionValuation;
import com.cocos.challenge.portfolio.domain.ValuationStatus;
import com.cocos.challenge.shared.domain.MonetaryPolicy;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Portfolio", description = "Account value, cash and positions of a user")
record PortfolioResponse(
        @Schema(example = "1")
        int userId,
        @Schema(description = "cashBalance + market value of every priced position (negative ones included). "
                + "When valuationComplete is false this is a partial known value: unpriced positions are excluded",
                example = "889756.00")
        BigDecimal totalAccountValue,
        @Schema(description = "ARS from FILLED orders (ledger balance)", example = "753000.00")
        BigDecimal cashBalance,
        @Schema(description = "ARS available to trade: cashBalance minus the cash reserved by NEW BUY orders",
                example = "627500.00")
        BigDecimal availableCash,
        @Schema(description = "false when at least one position has no market price")
        boolean valuationComplete,
        @Schema(description = "Non-zero positions ordered by ticker")
        List<PositionResponse> positions,
        @Schema(description = "One entry per position whose valuation is not OK")
        List<WarningResponse> warnings) {

    static PortfolioResponse from(Portfolio portfolio) {
        return new PortfolioResponse(
                portfolio.userId(),
                MonetaryPolicy.toMoneyScale(portfolio.totalAccountValue()),
                MonetaryPolicy.toMoneyScale(portfolio.cashBalance()),
                MonetaryPolicy.toMoneyScale(portfolio.availableCash()),
                portfolio.valuationComplete(),
                portfolio.positions().stream().map(PositionResponse::from).toList(),
                portfolio.warnings().stream().map(WarningResponse::from).toList());
    }

    @Schema(name = "Position")
    record PositionResponse(
            @Schema(example = "47")
            int instrumentId,
            @Schema(example = "PAMP")
            String ticker,
            @Schema(example = "Pampa Holding S.A.")
            String name,
            @Schema(description = "Net shares; negative when the history has more sells than buys", example = "40")
            long quantity,
            @Schema(description = "Weighted average purchase price, 2 decimals; null when quantity <= 0",
                    example = "930.00", nullable = true)
            BigDecimal averageCost,
            @Schema(description = "Close of the latest quote; null without market data", example = "925.85",
                    nullable = true)
            BigDecimal lastPrice,
            @Schema(description = "quantity * lastPrice; null without market data", example = "37034.00",
                    nullable = true)
            BigDecimal marketValue,
            @Schema(description = "Unrealized return of the open position over its weighted average cost: "
                    + "(lastPrice - averageCost) / averageCost * 100, 2 decimals; null when it cannot be computed "
                    + "reliably. Despite the name, it excludes realized gains and is not a historical total return",
                    example = "-0.45",
                    nullable = true)
            BigDecimal totalReturnPercent,
            ValuationStatus valuationStatus) {

        static PositionResponse from(PositionValuation position) {
            return new PositionResponse(position.instrumentId(), position.ticker(), position.name(),
                    position.quantity(), MonetaryPolicy.toMoneyScale(position.averageCost()), position.lastPrice(),
                    MonetaryPolicy.toMoneyScale(position.marketValue()), position.totalReturnPercent(),
                    position.status());
        }
    }

    @Schema(name = "PortfolioWarning")
    record WarningResponse(ValuationStatus code, int instrumentId, String message) {

        static WarningResponse from(PortfolioWarning warning) {
            return new WarningResponse(warning.code(), warning.instrumentId(), warning.message());
        }
    }
}
