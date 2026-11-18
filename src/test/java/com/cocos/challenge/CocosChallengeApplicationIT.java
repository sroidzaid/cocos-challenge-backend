package com.cocos.challenge;

import com.cocos.challenge.shared.infrastructure.web.CorrelationIdFilter;
import com.cocos.challenge.support.ApplicationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full application against a real PostgreSQL initialised with the provided database.sql.
 * Guards the wiring: datasource, JPA schema validation, actuator, OpenAPI and request correlation.
 * Schema immutability is verified in {@link SchemaIntegrityIT}.
 */
@ApplicationIT
class CocosChallengeApplicationIT {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seedDataFromDatabaseSqlIsAvailable() {
        Integer instruments = jdbcTemplate.queryForObject("SELECT count(*) FROM instruments", Integer.class);
        String cashType = jdbcTemplate.queryForObject(
                "SELECT type FROM instruments WHERE ticker = 'ARS'", String.class);

        assertThat(instruments).isEqualTo(66);
        assertThat(cashType).isEqualTo("MONEDA");
    }

    @Test
    void healthIsUpIncludingDatabase() {
        MvcTestResult result = mvc.get().uri("/actuator/health").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("UP");
        assertThat(result).containsHeader(CorrelationIdFilter.HEADER);
    }

    @Test
    void openApiSpecIsPublished() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.info.title").isEqualTo("Cocos Challenge API");
    }

    @Test
    void unknownRouteReturnsProblemDetail() {
        MvcTestResult result = mvc.get().uri("/api/v1/does-not-exist").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
    }
}
