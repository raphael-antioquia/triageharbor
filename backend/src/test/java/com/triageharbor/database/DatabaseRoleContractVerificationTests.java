package com.triageharbor.database;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.triageharbor.support.RoleSeparatedPostgresTestConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(RoleSeparatedPostgresTestConfiguration.class)
class DatabaseRoleContractVerificationTests {

    private static final String VERIFIER_RESOURCE =
            "/db/verification/database-role-contract.sql";

    private static final int EXPECTED_CHECK_COUNT = 47;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Test
    void roleSeparatedDatabaseMatchesPortableContract() throws Exception {
        try (Connection admin = adminConnection()) {
            List<VerificationResult> results = runVerifier(admin);

            assertThat(results).hasSize(EXPECTED_CHECK_COUNT);

            assertThat(results)
                    .extracting(VerificationResult::checkName)
                    .doesNotHaveDuplicates();

            assertThat(failed(results))
                    .as("failed database role contract checks")
                    .isEmpty();
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("contractViolations")
    void verifierDetectsViolation(String expectedFailure, String mutation)
            throws Exception {

        try (Connection admin = adminConnection()) {
            admin.setAutoCommit(false);

            try (Statement statement = admin.createStatement()) {
                statement.execute(mutation);

                assertThat(failed(runVerifier(admin)))
                        .as("expected verifier to detect %s", expectedFailure)
                        .contains(expectedFailure);
            } finally {
                admin.rollback();
            }
        }
    }

    @Test
    void verifierAcceptsDefaultPrivilegeHardening() throws Exception {
        try (Connection admin = adminConnection()) {
            admin.setAutoCommit(false);

            try (Statement statement = admin.createStatement()) {
                statement.execute(
                        """
                        ALTER DEFAULT PRIVILEGES
                        FOR ROLE triageharbor_migrator
                        REVOKE EXECUTE ON ROUTINES FROM PUBLIC
                        """);

                assertThat(failed(runVerifier(admin)))
                        .as("hardening default privileges must remain valid")
                        .isEmpty();
            } finally {
                admin.rollback();
            }
        }
    }

    static Stream<Arguments> contractViolations() {
        return Stream.of(
                // Table/column privileges and grant options.
                arguments(
                        "app_table_privileges_exact:events",
                        "GRANT DELETE ON events TO triageharbor_app"),
                arguments(
                        "app_table_privileges_exact:events",
                        "GRANT UPDATE (project_id) ON events TO triageharbor_app"),
                arguments(
                        "app_table_privileges_exact:projects",
                        "GRANT SELECT ON projects TO triageharbor_app WITH GRANT OPTION"),
                arguments(
                        "app_table_privileges_exact:projects",
                        "GRANT SELECT (name) ON projects TO triageharbor_app WITH GRANT OPTION"),
                arguments(
                        "app_table_privileges_exact:project_ingest_credentials",
                        "GRANT UPDATE (revoked_at) ON project_ingest_credentials TO triageharbor_app"),
                arguments(
                        "app_table_privileges_exact:project_ingest_credentials",
                        "REVOKE SELECT ON project_ingest_credentials FROM triageharbor_app"),

                // Database/schema grant options.
                arguments(
                        "app_schema_usage",
                        "GRANT USAGE ON SCHEMA public TO triageharbor_app WITH GRANT OPTION"),
                arguments(
                        "app_database_connect",
                        """
                        DO $$
                        BEGIN
                            EXECUTE format(
                                'GRANT CONNECT ON DATABASE %I TO triageharbor_app WITH GRANT OPTION',
                                current_database());
                        END
                        $$
                        """),

                // Memberships, both directions.
                arguments(
                        "restricted_roles_are_members_of_nothing",
                        "GRANT triageharbor_migrator TO triageharbor_app"),
                arguments(
                        "restricted_roles_granted_to_nobody",
                        "GRANT triageharbor_migrator TO CURRENT_USER"),

                // Role attributes and PUBLIC.
                arguments(
                        "role_app_nocreaterole",
                        "ALTER ROLE triageharbor_app CREATEROLE"),
                arguments(
                        "public_role_schema_no_usage",
                        "GRANT USAGE ON SCHEMA public TO PUBLIC"),

                // Ownership.
                arguments(
                        "owner_events_migrator",
                        "ALTER TABLE events OWNER TO CURRENT_USER"),

                // Object map.
                arguments(
                        "schema_public_table_set_exact",
                        "CREATE TABLE public.unexpected_table (id int)"),
                arguments(
                        "schema_public_sequences_none",
                        "CREATE SEQUENCE public.unexpected_sequence"),
                arguments(
                        "schema_public_views_none",
                        "CREATE VIEW public.unexpected_view AS SELECT 1 AS id"),
                arguments(
                        "schema_public_materialized_views_none",
                        """
                        CREATE MATERIALIZED VIEW public.unexpected_materialized_view
                        AS SELECT 1 AS id
                        """),
                arguments(
                        "schema_public_routines_none",
                        """
                        CREATE FUNCTION public.unexpected_fn()
                        RETURNS int
                        LANGUAGE sql
                        AS 'SELECT 1'
                        """),

                // Default privileges.
                arguments(
                        "default_privileges_do_not_grant_app_or_public",
                        """
                        ALTER DEFAULT PRIVILEGES
                        FOR ROLE triageharbor_migrator
                        GRANT SELECT ON TABLES TO triageharbor_app
                        """),
                arguments(
                        "default_privileges_do_not_grant_app_or_public",
                        """
                        ALTER DEFAULT PRIVILEGES
                        FOR ROLE triageharbor_migrator
                        GRANT SELECT ON TABLES TO PUBLIC
                        """));
    }

    private List<VerificationResult> runVerifier(Connection connection)
            throws Exception {

        List<VerificationResult> results = new ArrayList<>();

        try (Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery(loadVerifierSql())) {

            while (resultSet.next()) {
                results.add(
                        new VerificationResult(
                                resultSet.getString("check_name"),
                                resultSet.getBoolean("passed")));
            }
        }

        return results;
    }

    private static List<String> failed(
            List<VerificationResult> results) {

        return results.stream()
                .filter(result -> !result.passed())
                .map(VerificationResult::checkName)
                .toList();
    }

    private String loadVerifierSql() throws IOException {
        InputStream resource =
                getClass().getResourceAsStream(VERIFIER_RESOURCE);

        if (resource == null) {
            throw new IllegalStateException(
                    "Missing verifier resource: " + VERIFIER_RESOURCE);
        }

        try (resource) {
            return new String(resource.readAllBytes(), UTF_8);
        }
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword());
    }

    private record VerificationResult(
            String checkName,
            boolean passed) {}
}
