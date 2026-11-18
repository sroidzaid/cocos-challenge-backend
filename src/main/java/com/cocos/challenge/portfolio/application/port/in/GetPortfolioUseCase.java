package com.cocos.challenge.portfolio.application.port.in;

import com.cocos.challenge.portfolio.domain.Portfolio;

public interface GetPortfolioUseCase {

    /**
     * @throws com.cocos.challenge.shared.domain.NotFoundException with code {@code USER_NOT_FOUND}
     */
    Portfolio getPortfolio(int userId);
}
