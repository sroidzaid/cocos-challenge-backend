package com.cocos.challenge.shared.infrastructure.web;

import com.cocos.challenge.shared.domain.BusinessRuleViolationException;
import com.cocos.challenge.shared.domain.ConflictException;
import com.cocos.challenge.shared.domain.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the error contract (RFC 9457 + code + correlationId) using a fixture controller that exists only in
 * tests. Nested in a test class, the fixture is excluded from component scanning in other test contexts.
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.FixtureController.class)
@Import(GlobalExceptionHandlerTest.FixtureController.class)
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void beanValidationErrorsReturn400WithFieldDetails() {
        MvcTestResult result = mvc.post().uri("/fixture/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\": 0}")
                .exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("quantity");
        assertThat(result).bodyJson().extractingPath("$.errors[0].message").isNotNull();
    }

    @Test
    void malformedJsonReturns400() {
        MvcTestResult result = mvc.post().uri("/fixture/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-json")
                .exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    @Test
    void invalidRequestParameterReturns400() {
        MvcTestResult result = mvc.get().uri("/fixture/search").param("limit", "0").exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("limit");
    }

    @Test
    void pathVariableTypeMismatchReturns400() {
        MvcTestResult result = mvc.get().uri("/fixture/orders/not-a-number").exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "BAD_REQUEST");
    }

    @Test
    void notFoundReturns404WithDomainCode() {
        MvcTestResult result = mvc.get().uri("/fixture/orders/404").exchange();

        assertProblem(result, HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Order 404 not found");
    }

    @Test
    void conflictReturns409WithDomainCode() {
        MvcTestResult result = mvc.get().uri("/fixture/orders/409").exchange();

        assertProblem(result, HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE");
    }

    @Test
    void businessRuleViolationReturns422WithDomainCode() {
        MvcTestResult result = mvc.get().uri("/fixture/orders/422").exchange();

        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "MARKET_PRICE_UNAVAILABLE");
    }

    @Test
    void transientDatabaseErrorReturns503() {
        MvcTestResult result = mvc.get().uri("/fixture/orders/503").exchange();

        assertProblem(result, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
    }

    @Test
    void unexpectedErrorReturns500WithoutLeakingInternals() {
        MvcTestResult result = mvc.get().uri("/fixture/orders/500").exchange();

        assertProblem(result, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertThat(result).body().asString().doesNotContain("secret-internal-detail");
    }

    @Test
    void unsupportedMethodGetsTheSameErrorShape() {
        MvcTestResult result = mvc.delete().uri("/fixture/orders/1").exchange();

        assertProblem(result, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
    }

    @Test
    void errorBodyCarriesTheRequestCorrelationId() {
        MvcTestResult result = mvc.get().uri("/fixture/orders/404")
                .header(CorrelationIdFilter.HEADER, "test-correlation-1")
                .exchange();

        assertThat(result).hasHeader(CorrelationIdFilter.HEADER, "test-correlation-1");
        assertThat(result).bodyJson().extractingPath("$.correlationId").isEqualTo("test-correlation-1");
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.correlationId").isNotNull();
    }

    // ---- Fixture -------------------------------------------------------------------------------------------------

    record FixtureRequest(@NotNull @Positive Integer quantity) {
    }

    @RestController
    @RequestMapping("/fixture")
    static class FixtureController {

        @PostMapping("/orders")
        String create(@Valid @RequestBody FixtureRequest request) {
            return "ok";
        }

        @GetMapping("/search")
        String search(@RequestParam @Min(1) int limit) {
            return "ok";
        }

        @GetMapping("/orders/{id}")
        String get(@PathVariable long id) {
            switch ((int) id) {
                case 404 -> throw new NotFoundException("ORDER_NOT_FOUND", "Order 404 not found");
                case 409 -> throw new ConflictException("ORDER_NOT_CANCELLABLE", "Only NEW orders can be cancelled");
                case 422 -> throw new BusinessRuleViolationException("MARKET_PRICE_UNAVAILABLE", "No market price");
                case 503 -> throw new QueryTimeoutException("lock timeout");
                case 500 -> throw new IllegalStateException("secret-internal-detail");
                default -> {
                    return "ok";
                }
            }
        }
    }
}
