package com.cocos.challenge.portfolio.domain;

/** Something the client should know about the valuation of a position. */
public record PortfolioWarning(ValuationStatus code, int instrumentId, String message) {
}
