package com.cocos.challenge;

import javax.sql.DataSource;

import com.cocos.challenge.support.ApplicationIT;
import com.cocos.challenge.support.PostgresTestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests must never reach the local or the external Cocos database: only the "test" profile is active and the
 * datasource points to the Testcontainers PostgreSQL.
 */
@ApplicationIT
class ProfileIsolationIT {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Test
    void onlyTheTestProfileIsActive() {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
    }

    @Test
    void datasourcePointsToTheTestContainer() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getJdbcUrl()).isEqualTo(PostgresTestcontainersConfiguration.jdbcUrl());
    }
}
