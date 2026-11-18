package com.cocos.challenge.instruments.infrastructure.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InstrumentJpaRepository extends JpaRepository<InstrumentEntity, Integer> {

    /** Returns a list on purpose: ticker has no UNIQUE constraint, duplicates must be detected, not hidden. */
    @Query(value = "SELECT * FROM instruments WHERE upper(ticker) = upper(:ticker) ORDER BY id", nativeQuery = true)
    List<InstrumentEntity> findAllByTickerIgnoreCase(@Param("ticker") String ticker);

    @Query(value = """
            SELECT * FROM instruments
            WHERE type = :type AND upper(ticker) = upper(:ticker)
            ORDER BY id""", nativeQuery = true)
    List<InstrumentEntity> findAllByTypeAndTickerIgnoreCase(@Param("type") String type, @Param("ticker") String ticker);

    /**
     * Partial, case-insensitive match on ticker OR name, excluding one instrument type (the currency).
     * Patterns arrive already escaped (see the adapter); {@code ESCAPE '\'} makes user-typed {@code %} and {@code _}
     * literal. Relevance: exact ticker first, then tickers starting with the text, then the rest; ties by ticker, id.
     */
    @Query(value = """
            SELECT * FROM instruments
            WHERE (ticker ILIKE CAST(:containsPattern AS text) ESCAPE '\\'
                   OR name ILIKE CAST(:containsPattern AS text) ESCAPE '\\')
              AND type IS DISTINCT FROM CAST(:excludedType AS text)
            ORDER BY CASE
                         WHEN upper(ticker) = upper(CAST(:text AS text)) THEN 0
                         WHEN ticker ILIKE CAST(:prefixPattern AS text) ESCAPE '\\' THEN 1
                         ELSE 2
                     END,
                     ticker, id
            LIMIT :limit""", nativeQuery = true)
    List<InstrumentEntity> search(@Param("text") String text,
            @Param("containsPattern") String containsPattern,
            @Param("prefixPattern") String prefixPattern,
            @Param("excludedType") String excludedType,
            @Param("limit") int limit);
}
