package com.triageharbor.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.triageharbor.support.RoleSeparatedPostgresTestConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(RoleSeparatedPostgresTestConfiguration.class)
class PostgresJdbcDiagnosticTests {

    private static final String CANARY = "CANARY_PGJDBC_DETAIL_91a7";

    private static final UUID PROJECT_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final UUID INCIDENT_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final String NOT_NULL_VIOLATION =
            """
            INSERT INTO events (
                project_id,
                incident_id,
                occurred_at,
                message
            )
            VALUES (?, ?, NULL, ?)
            """;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcConnectionDetails jdbcConnectionDetails;

    @Test
    void applicationDataSourceDisablesServerErrorDetail()
            throws SQLException {
        HikariDataSource hikari =
                dataSource.unwrap(HikariDataSource.class);

        assertThat(
                        hikari.getDataSourceProperties()
                                .getProperty("logServerErrorDetail"))
                .isEqualTo("false");
    }

    @Test
    void applicationDataSourceSuppressesFailingRow() throws SQLException {
        // Exercises the real pool and driver, not just the bound property.
        try (Connection connection = dataSource.getConnection()) {
            assertFailingRowSuppressed(notNullFailure(connection));
        }
    }

    @Test
    void enabledServerErrorDetailExposesFailingRow() throws SQLException {
        try (Connection connection = directConnection(true)) {
            SQLException exception = notNullFailure(connection);

            assertThat(exception.getSQLState()).isEqualTo("23502");

            // If RLS is later enabled, PostgreSQL may omit failing-row
            // details. This positive control should then fail.
            assertThat(exception.getMessage())
                    .contains("violates not-null constraint")
                    .contains("occurred_at")
                    .contains("Failing row contains")
                    .contains(CANARY);

            assertThat(exception.toString())
                    .contains("Failing row contains")
                    .contains(CANARY);
        }
    }

    @Test
    void disabledServerErrorDetailSuppressesFailingRow()
            throws SQLException {
        try (Connection connection = directConnection(false)) {
            assertFailingRowSuppressed(notNullFailure(connection));
        }
    }

    private Connection directConnection(boolean logServerErrorDetail)
            throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", jdbcConnectionDetails.getUsername());
        properties.setProperty(
                "password", jdbcConnectionDetails.getPassword());
        properties.setProperty(
                "logServerErrorDetail",
                Boolean.toString(logServerErrorDetail));

        return DriverManager.getConnection(
                jdbcConnectionDetails.getJdbcUrl(), properties);
    }

    private static SQLException notNullFailure(Connection connection)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(NOT_NULL_VIOLATION)) {
            statement.setObject(1, PROJECT_ID);
            statement.setObject(2, INCIDENT_ID);
            statement.setString(3, CANARY);

            return assertThrows(
                    SQLException.class, statement::executeUpdate);
        }
    }

    private static void assertFailingRowSuppressed(SQLException exception) {
        assertThat(exception.getSQLState()).isEqualTo("23502");

        assertThat(exception.getMessage())
                .contains("violates not-null constraint")
                .contains("occurred_at")
                .doesNotContain("Failing row contains")
                .doesNotContain(CANARY);

        assertThat(exception.toString())
                .contains("violates not-null constraint")
                .contains("occurred_at")
                .doesNotContain("Failing row contains")
                .doesNotContain(CANARY);
    }
}
