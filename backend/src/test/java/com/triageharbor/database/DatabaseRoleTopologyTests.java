package com.triageharbor.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.triageharbor.support.RoleSeparatedPostgresTestConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(RoleSeparatedPostgresTestConfiguration.class)
class DatabaseRoleTopologyTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Test
    void runtimeDataSourceUsesAppRole() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class))
                .isEqualTo("triageharbor_app");
    }

    @Test
    void flywayMigratesAsMigrator() throws SQLException {
        // Admin identity: JdbcTemplate is the subject under test, not the verifier.
        try (Connection admin =
                        DriverManager.getConnection(
                                postgresContainer.getJdbcUrl(),
                                postgresContainer.getUsername(),
                                postgresContainer.getPassword());
                Statement statement = admin.createStatement()) {

            assertThat(
                            queryStrings(
                                    statement,
                                    """
                                    SELECT tablename || ':' || tableowner
                                    FROM pg_tables
                                    WHERE schemaname = 'public'
                                      AND tablename IN (
                                          'projects',
                                          'incidents',
                                          'events',
                                          'flyway_schema_history'
                                      )
                                    """))
                    .containsExactlyInAnyOrder(
                            "events:triageharbor_migrator",
                            "flyway_schema_history:triageharbor_migrator",
                            "incidents:triageharbor_migrator",
                            "projects:triageharbor_migrator");

            assertThat(
                            queryStrings(
                                    statement,
                                    "SELECT DISTINCT installed_by FROM flyway_schema_history"))
                    .containsExactly("triageharbor_migrator");
        }
    }

    private List<String> queryStrings(Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();

        try (ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }

        return values;
    }
}
