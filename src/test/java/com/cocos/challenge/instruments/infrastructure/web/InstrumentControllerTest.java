package com.cocos.challenge.instruments.infrastructure.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.cocos.challenge.instruments.application.port.in.InstrumentSearchResult;
import com.cocos.challenge.instruments.application.port.in.SearchInstrumentsUseCase;
import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.instruments.domain.InstrumentType;
import com.cocos.challenge.marketdata.domain.MarketQuote;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebMvcTest(InstrumentController.class)
@ActiveProfiles("test")
class InstrumentControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private SearchInstrumentsUseCase searchInstruments;

    @Test
    void returnsTheJsonContract() {
        when(searchInstruments.search("banco", 20)).thenReturn(List.of(
                new InstrumentSearchResult(new Instrument(31, "BMA", "Banco Macro S.A.", InstrumentType.ACCIONES),
                        new MarketQuote(31, LocalDate.of(2023, 7, 14), new BigDecimal("1502.80"),
                                new BigDecimal("1520.25"))),
                new InstrumentSearchResult(
                        new Instrument(3, "PGR", "Phoenix Global Resources", InstrumentType.ACCIONES), null)));

        MvcTestResult result = mvc.get().uri("/api/v1/instruments").param("q", "banco").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                [
                  {"id": 31, "ticker": "BMA", "name": "Banco Macro S.A.", "lastPrice": 1502.80, "dailyReturnPercent": -1.15},
                  {"id": 3, "ticker": "PGR", "name": "Phoenix Global Resources", "lastPrice": null, "dailyReturnPercent": null}
                ]""");
        assertThat(result).body().asString().contains("\"lastPrice\":1502.80");
    }

    @Test
    void usesTheDefaultLimitAndAcceptsAnExplicitOne() {
        when(searchInstruments.search(anyString(), anyInt())).thenReturn(List.of());

        assertThat(mvc.get().uri("/api/v1/instruments").param("q", "x").exchange()).hasStatusOk();
        verify(searchInstruments).search("x", 20);
        assertThat(mvc.get().uri("/api/v1/instruments").param("q", "x").param("limit", "100").exchange())
                .hasStatusOk();
        verify(searchInstruments).search("x", 100);
    }

    @Test
    void emptyResultIsAnEmptyArray() {
        when(searchInstruments.search("zzz", 20)).thenReturn(List.of());

        MvcTestResult result = mvc.get().uri("/api/v1/instruments").param("q", "zzz").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("[]");
    }

    @Test
    void missingQueryIsAValidationError() {
        assertValidationError(mvc.get().uri("/api/v1/instruments").exchange(), "q");
    }

    @Test
    void blankQueryIsAValidationError() {
        assertValidationError(mvc.get().uri("/api/v1/instruments").param("q", "   ").exchange(), "q");
    }

    @Test
    void tooLongQueryIsAValidationError() {
        assertValidationError(mvc.get().uri("/api/v1/instruments").param("q", "x".repeat(101)).exchange(), "q");
    }

    @Test
    void limitOutOfRangeIsAValidationError() {
        assertValidationError(mvc.get().uri("/api/v1/instruments").param("q", "x").param("limit", "0").exchange(),
                "limit");
        assertValidationError(mvc.get().uri("/api/v1/instruments").param("q", "x").param("limit", "101").exchange(),
                "limit");
    }

    @Test
    void nonNumericLimitIsABadRequest() {
        MvcTestResult result = mvc.get().uri("/api/v1/instruments").param("q", "x").param("limit", "ten").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(searchInstruments);
    }

    private void assertValidationError(MvcTestResult result, String field) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo(field);
        verifyNoInteractions(searchInstruments);
    }
}
