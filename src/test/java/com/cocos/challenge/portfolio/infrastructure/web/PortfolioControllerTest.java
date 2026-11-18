package com.cocos.challenge.portfolio.infrastructure.web;

import java.math.BigDecimal;
import java.util.List;

import com.cocos.challenge.portfolio.application.port.in.GetPortfolioUseCase;
import com.cocos.challenge.portfolio.domain.Holding;
import com.cocos.challenge.portfolio.domain.Portfolio;
import com.cocos.challenge.portfolio.domain.PositionValuation;
import com.cocos.challenge.shared.domain.MonetaryPolicy;
import com.cocos.challenge.shared.domain.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebMvcTest(PortfolioController.class)
@ActiveProfiles("test")
class PortfolioControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private GetPortfolioUseCase getPortfolio;

    @Test
    void returnsTheJsonContract() {
        // Average cost 10/3 is kept unrounded in the domain and presented with 2 decimals.
        PositionValuation priced = PositionValuation.of(
                new Holding(47, 3, BigDecimal.TEN.divide(BigDecimal.valueOf(3), MonetaryPolicy.MATH)),
                "PAMP", "Pampa Holding S.A.", new BigDecimal("4.00"));
        PositionValuation negative = PositionValuation.of(new Holding(31, -10, null), "BMA", "Banco Macro S.A.",
                new BigDecimal("1502.80"));
        when(getPortfolio.getPortfolio(1)).thenReturn(Portfolio.of(1, new BigDecimal("100000.00"),
                new BigDecimal("5000.00"), List.of(priced, negative)));

        MvcTestResult result = mvc.get().uri("/api/v1/users/1/portfolio").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {
                  "userId": 1,
                  "totalAccountValue": 84984.00,
                  "cashBalance": 100000.00,
                  "availableCash": 95000.00,
                  "valuationComplete": true,
                  "positions": [
                    {"instrumentId": 31, "ticker": "BMA", "name": "Banco Macro S.A.", "quantity": -10,
                     "averageCost": null, "lastPrice": 1502.80, "marketValue": -15028.00,
                     "totalReturnPercent": null, "valuationStatus": "NEGATIVE_QUANTITY"},
                    {"instrumentId": 47, "ticker": "PAMP", "name": "Pampa Holding S.A.", "quantity": 3,
                     "averageCost": 3.33, "lastPrice": 4.00, "marketValue": 12.00,
                     "totalReturnPercent": 20.00, "valuationStatus": "OK"}
                  ],
                  "warnings": [
                    {"code": "NEGATIVE_QUANTITY", "instrumentId": 31,
                     "message": "BMA has a negative quantity (more shares sold than bought in the order history): average cost and return are not computed"}
                  ]
                }""");
        assertThat(result).body().asString().contains("\"averageCost\":3.33", "\"totalAccountValue\":84984.00");
    }

    @Test
    void unknownUserIsNotFound() {
        when(getPortfolio.getPortfolio(999)).thenThrow(new NotFoundException("USER_NOT_FOUND", "User 999 does not exist"));

        MvcTestResult result = mvc.get().uri("/api/v1/users/999/portfolio").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void nonPositiveUserIdIsAValidationError() {
        MvcTestResult result = mvc.get().uri("/api/v1/users/0/portfolio").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("userId");
        verifyNoInteractions(getPortfolio);
    }

    @Test
    void nonNumericUserIdIsABadRequest() {
        assertThat(mvc.get().uri("/api/v1/users/abc/portfolio").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(getPortfolio);
    }
}
