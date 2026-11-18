package com.cocos.challenge.instruments.infrastructure.web;

import java.util.List;

import com.cocos.challenge.instruments.application.port.in.SearchInstrumentsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Constraint annotations on parameters trigger Spring MVC's built-in method validation (400 VALIDATION_FAILED). */
@RestController
@RequestMapping("/api/v1/instruments")
@Tag(name = "Instruments", description = "Search of market assets")
class InstrumentController {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final SearchInstrumentsUseCase searchInstruments;

    InstrumentController(SearchInstrumentsUseCase searchInstruments) {
        this.searchInstruments = searchInstruments;
    }

    @GetMapping
    @Operation(summary = "Search assets by ticker or name",
            description = "Partial, case-insensitive match on ticker or name. Currency instruments (ARS) are not "
                    + "market assets and are excluded. Ordered by relevance: exact ticker, ticker prefix, rest.")
    @ApiResponse(responseCode = "200", description = "Matching assets (empty array when nothing matches)")
    @ApiResponse(responseCode = "400", description = "Invalid parameters")
    List<InstrumentResponse> search(
            @Parameter(description = "Text to search in ticker or name", example = "banco")
            @RequestParam @NotBlank @Size(max = 100) String q,
            @Parameter(description = "Maximum number of results (1-100)")
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) @Min(1) @Max(MAX_LIMIT) int limit) {
        return searchInstruments.search(q, limit).stream().map(InstrumentResponse::from).toList();
    }
}
