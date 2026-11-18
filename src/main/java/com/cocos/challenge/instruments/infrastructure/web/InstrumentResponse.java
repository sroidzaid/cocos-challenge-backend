package com.cocos.challenge.instruments.infrastructure.web;

import java.math.BigDecimal;

import com.cocos.challenge.instruments.application.port.in.InstrumentSearchResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Instrument", description = "Market asset matching the search, with its latest price")
record InstrumentResponse(
        @Schema(description = "Instrument id; use it as instrumentId when sending orders", example = "31")
        int id,
        @Schema(example = "BMA")
        String ticker,
        @Schema(example = "Banco Macro S.A.")
        String name,
        @Schema(description = "Last price in ARS (close of the latest quote); null without market data",
                example = "1502.80", nullable = true)
        BigDecimal lastPrice,
        @Schema(description = "Daily return in percent: (close - previousClose) / previousClose * 100, 2 decimals; "
                + "null when it cannot be computed", example = "-1.15", nullable = true)
        BigDecimal dailyReturnPercent) {

    static InstrumentResponse from(InstrumentSearchResult result) {
        return new InstrumentResponse(result.instrument().id(), result.instrument().ticker(),
                result.instrument().name(), result.lastPrice(), result.dailyReturnPercent());
    }
}
