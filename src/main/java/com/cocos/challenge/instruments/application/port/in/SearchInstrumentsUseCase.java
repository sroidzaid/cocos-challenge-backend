package com.cocos.challenge.instruments.application.port.in;

import java.util.List;

/**
 * Searches market assets by ticker or name and attaches their latest quote ("Buscar activos" in the challenge).
 */
public interface SearchInstrumentsUseCase {

    /**
     * @param text  non-blank fragment matched (case-insensitive, partial) against ticker or name
     * @param limit maximum number of results, positive
     * @return matches ordered by relevance; empty when nothing matches
     */
    List<InstrumentSearchResult> search(String text, int limit);
}
