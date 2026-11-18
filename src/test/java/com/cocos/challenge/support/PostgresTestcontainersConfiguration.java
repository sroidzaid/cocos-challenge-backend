package com.cocos.challenge.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Real PostgreSQL for integration tests, initialised by the container itself with the provided {@code database.sql}
 * exactly as delivered by Cocos (same DDL and seed data). The application connects to it without modifying it.
 * <p>
 * A single container is shared by every Spring test context of the JVM (started once, removed by Testcontainers
 * when the JVM exits), so contexts are cheap. Consequently tests must leave the data as they found it: repository
 * tests roll back their transaction, tests that commit restore the seed with {@link SeedDataRestorer}.
 * {@link ServiceConnection} wires the datasource automatically: tests never use the local or Cocos settings.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

    public static final String POSTGRES_IMAGE = "postgres:16-alpine";

    /**
     * Same procedure documented for a new local database: the original database.sql (V1) and then every versioned
     * migration, in order. The image's entrypoint runs them with psql ON_ERROR_STOP, so a failing script fails the
     * container start.
     */
    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withCopyFileToContainer(
                    MountableFile.forHostPath("database.sql"), "/docker-entrypoint-initdb.d/01-database.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("db/migration/V2__create_order_idempotency.sql"),
                    "/docker-entrypoint-initdb.d/02-V2__create_order_idempotency.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("db/migration/V3__orders_integrity_constraints.sql"),
                    "/docker-entrypoint-initdb.d/03-V3__orders_integrity_constraints.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("db/migration/V4__lookup_indexes.sql"),
                    "/docker-entrypoint-initdb.d/04-V4__lookup_indexes.sql");

    /** destroyMethod="" keeps the shared container alive when an individual context is closed. */
    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return CONTAINER;
    }

    public static String jdbcUrl() {
        return CONTAINER.getJdbcUrl();
    }

    public static String username() {
        return CONTAINER.getUsername();
    }

    public static String password() {
        return CONTAINER.getPassword();
    }
}
