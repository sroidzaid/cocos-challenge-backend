package com.cocos.challenge.instruments.infrastructure.persistence;

import java.util.List;

import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.instruments.domain.InstrumentType;
import com.cocos.challenge.shared.infrastructure.persistence.PersistedDataIntegrityException;
import com.cocos.challenge.support.PersistenceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PersistenceIT
@Import(InstrumentPersistenceAdapter.class)
class InstrumentPersistenceAdapterIT {

    @Autowired
    private InstrumentPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void findsById() {
        assertThat(adapter.findById(47))
                .contains(new Instrument(47, "PAMP", "Pampa Holding S.A.", InstrumentType.ACCIONES));
        assertThat(adapter.findById(9999)).isEmpty();
    }

    @Test
    void findsSeveralByIdInOneCall() {
        assertThat(adapter.findAllByIds(List.of(31, 47, 54, 9999)))
                .extracting(Instrument::ticker)
                .containsExactlyInAnyOrder("BMA", "PAMP", "METR");
        assertThat(adapter.findAllByIds(List.of())).isEmpty();
    }

    @Test
    void findsByExactTickerIgnoringCase() {
        assertThat(adapter.findByTicker("ggal")).map(Instrument::id).contains(34);
        assertThat(adapter.findByTicker("GGA")).isEmpty();
    }

    @Test
    void searchesByPartialNameIgnoringCase() {
        assertThat(adapter.search("BANCO", 20))
                .extracting(Instrument::ticker)
                .containsExactly("BBAR", "BHIP", "BMA", "BPAT");
    }

    @Test
    void matchesTickerOrNameOrderedByRelevance() {
        // Ticker prefix "PA" first (PAMP, PATA), then any other match in ticker (BPAT, CGPA2) or name
        // ("Compañía Introductora..." -> INTR), each group ordered by ticker.
        assertThat(adapter.search("pa", 20))
                .extracting(Instrument::ticker)
                .containsExactly("PAMP", "PATA", "BPAT", "CGPA2", "INTR");
    }

    @Test
    void exactTickerComesFirst() {
        jdbc.update("INSERT INTO instruments (ticker, name, type) VALUES ('AGGAL', 'Contains GGAL', 'ACCIONES')");

        assertThat(adapter.search("ggal", 20)).extracting(Instrument::ticker).containsExactly("GGAL", "AGGAL");
    }

    @Test
    void currencyInstrumentsAreNotMarketAssets() {
        assertThat(adapter.search("ARS", 20)).isEmpty();
        assertThat(adapter.search("pesos", 20)).isEmpty();
    }

    @Test
    void appliesTheLimit() {
        assertThat(adapter.search("banco", 2)).extracting(Instrument::ticker).containsExactly("BBAR", "BHIP");
    }

    @Test
    void treatsLikeWildcardsTypedByTheUserLiterally() {
        assertThat(adapter.search("%", 20)).isEmpty();
        assertThat(adapter.search("_", 20)).isEmpty();
        assertThat(adapter.search("\\", 20)).isEmpty();
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertThat(adapter.search("  bma  ", 20)).extracting(Instrument::ticker).containsExactly("BMA");
    }

    @Test
    void returnsEmptyWhenNothingMatches() {
        assertThat(adapter.search("zzz", 20)).isEmpty();
    }

    @Test
    void resolvesTheLocalCurrencyWithoutAssumingItsId() {
        Integer arsId = jdbc.queryForObject("SELECT id FROM instruments WHERE ticker = 'ARS'", Integer.class);

        assertThat(adapter.findLocalCurrency()).hasValueSatisfying(ars -> {
            assertThat(ars.id()).isEqualTo(arsId);
            assertThat(ars.ticker()).isEqualTo("ARS");
            assertThat(ars.type()).isEqualTo(InstrumentType.MONEDA);
            assertThat(ars.isCurrency()).isTrue();
        });
    }

    @Test
    void duplicatedTickerIsReportedNotHidden() {
        jdbc.update("INSERT INTO instruments (ticker, name, type) VALUES ('GGAL', 'Duplicate', 'ACCIONES')");

        assertThatThrownBy(() -> adapter.findByTicker("GGAL")).isInstanceOf(PersistedDataIntegrityException.class);
    }

    @Test
    void unsupportedStoredTypeFailsExplicitly() {
        jdbc.update("UPDATE instruments SET type = 'BONOS' WHERE id = 1");

        assertThatThrownBy(() -> adapter.findById(1))
                .isInstanceOf(PersistedDataIntegrityException.class)
                .hasMessageContaining("instruments.id=1");
    }
}
