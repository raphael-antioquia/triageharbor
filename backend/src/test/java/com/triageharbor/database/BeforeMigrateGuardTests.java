package com.triageharbor.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class BeforeMigrateGuardTests {

    private static final String MIGRATOR_PASSWORD = "test-migrator-password";

    @Test
    void migrationStopsBeforeApplicationSchemaChangesWhenAppRoleIsMissing()
            throws SQLException {

        try (PostgreSQLContainer postgres =
                new PostgreSQLContainer(DockerImageName.parse("postgres:17.11"))
                        .withDatabaseName("triageharbor")) {

            postgres.start();

            provisionMigratorOnly(postgres);

            Flyway flyway =
                    Flyway.configure()
                            .dataSource(
                                    postgres.getJdbcUrl(),
                                    "triageharbor_migrator",
                                    MIGRATOR_PASSWORD)
                            .locations("classpath:db/migration")
                            .load();

            assertThatThrownBy(flyway::migrate)
                    .isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining(
                            "Required database role triageharbor_app does not exist")
                    .hasStackTraceContaining("Run database bootstrap");

            try (Connection admin =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    Statement statement = admin.createStatement();
                    ResultSet resultSet =
                            statement.executeQuery(
                                    "SELECT to_regclass('public.projects') IS NULL")) {

                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getBoolean(1)).isTrue();
            }
        }
    }

    private void provisionMigratorOnly(PostgreSQLContainer postgres)
            throws SQLException {

        try (Connection admin =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                Statement statement = admin.createStatement()) {

            statement.execute(
                    """
                    CREATE ROLE triageharbor_migrator
                        WITH LOGIN
                        NOSUPERUSER
                        NOCREATEDB
                        NOCREATEROLE
                        NOREPLICATION
                        NOBYPASSRLS
                        PASSWORD 'test-migrator-password'
                    """);

            statement.execute(
                    """
                    GRANT CONNECT
                        ON DATABASE triageharbor
                        TO triageharbor_migrator
                    """);

            statement.execute(
                    """
                    GRANT USAGE, CREATE
                        ON SCHEMA public
                        TO triageharbor_migrator
                    """);
        }
    }
}
