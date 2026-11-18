package com.cocos.challenge.portfolio.infrastructure.web;

import com.cocos.challenge.portfolio.application.port.in.GetPortfolioUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/{userId}/portfolio")
@Tag(name = "Portfolio", description = "Account value, available cash and positions")
class PortfolioController {

    private final GetPortfolioUseCase getPortfolio;

    PortfolioController(GetPortfolioUseCase getPortfolio) {
        this.getPortfolio = getPortfolio;
    }

    @GetMapping
    @Operation(summary = "Get the portfolio of a user",
            description = "Total account value, cash balance, cash available to trade (net of NEW BUY reservations) "
                    + "and positions valued at the latest close, with weighted average cost and total return.")
    @ApiResponse(responseCode = "200", description = "Portfolio")
    @ApiResponse(responseCode = "400", description = "Invalid user id")
    @ApiResponse(responseCode = "404", description = "User not found (USER_NOT_FOUND)")
    PortfolioResponse portfolio(
            @Parameter(description = "User id", example = "1") @PathVariable @Min(1) int userId) {
        return PortfolioResponse.from(getPortfolio.getPortfolio(userId));
    }
}
