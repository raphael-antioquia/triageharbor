package com.triageharbor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.triageharbor.support.RoleSeparatedPostgresTestConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Import(RoleSeparatedPostgresTestConfiguration.class)
class CredentialAdminDatabaseTests {

    private static final String PASSWORD = "test-migrator-password";

    private static final String PROJECT =
            "11111111-1111-1111-1111-111111111111";

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migratedDatabasePassesPreflightWithoutExecutingCommand() {
        Map<String, String> env = environment(postgresContainer);

        int credentialsBefore =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM project_ingest_credentials",
                        Integer.class);

        assertThatCode(() -> CredentialAdmin.verifySchema(env))
                .doesNotThrowAnyException();

        Result result = invoke(env);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr())
                .contains("not implemented")
                .doesNotContain("preflight failed");

        int credentialsAfter =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM project_ingest_credentials",
                        Integer.class);

        assertThat(credentialsAfter).isEqualTo(credentialsBefore);
    }

    @Test
    void preflightRejectsPendingFutureAndChecksumStates()
            throws SQLException {

        // Separate container: never corrupt shared Flyway history.
        try (PostgreSQLContainer db =
                new PostgreSQLContainer(DockerImageName.parse("postgres:17.11"))
                        .withDatabaseName("triageharbor")
                        .withInitScript("db/test-role-bootstrap.sql")) {

            db.start();
            Map<String, String> env = environment(db);

            // Test fixture only: migrate through V3.
            migrator(db)
                    .target(MigrationVersion.fromVersion("3"))
                    .load()
                    .migrate();

            assertThat(migrationCount(db)).isEqualTo(3);

            // Pending V4: Flyway validation must reject it.
            String beforePending = historySnapshot(db);
            Result pending = invoke(env);

            assertThat(pending.exitCode()).isEqualTo(1);
            assertThat(pending.stdout()).isEmpty();
            assertThat(pending.stderr())
                    .contains("do not validate")
                    .contains(
                            "4:RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED");

            assertThat(historySnapshot(db)).isEqualTo(beforePending);

            // Positive control: a fully migrated V4 database passes.
            migrator(db).load().migrate();

            assertThat(migrationCount(db)).isEqualTo(4);

            assertThatCode(() -> CredentialAdmin.verifySchema(env))
                    .doesNotThrowAnyException();

            // Simulate a newer deployment that applied V5.
            // Flyway validation ignores future migrations by default,
            // so MigrationVerifier must reject this state.
            admin(
                    db,
                    """
                    INSERT INTO flyway_schema_history (
                        installed_rank,
                        version,
                        description,
                        type,
                        script,
                        checksum,
                        installed_by,
                        execution_time,
                        success
                    )
                    SELECT
                        max(installed_rank) + 1,
                        '5',
                        'future migration',
                        'SQL',
                        'V5__future_migration.sql',
                        0,
                        'test',
                        0,
                        true
                    FROM flyway_schema_history
                    """);

            assertThat(migrationCount(db)).isEqualTo(5);

            String beforeFuture = historySnapshot(db);
            Result future = invoke(env);

            assertThat(future.exitCode()).isEqualTo(1);
            assertThat(future.stdout()).isEmpty();
            assertThat(future.stderr())
                    .contains("not packaged in this image");

            assertThat(historySnapshot(db)).isEqualTo(beforeFuture);

            // Remove the simulated V5 before testing checksum drift.
            admin(
                    db,
                    "DELETE FROM flyway_schema_history WHERE version = '5'");

            assertThat(migrationCount(db)).isEqualTo(4);

            // Change V4's stored checksum to simulate migration drift.
            admin(
                    db,
                    """
                    UPDATE flyway_schema_history
                    SET checksum = checksum + 1
                    WHERE version = '4'
                    """);

            String beforeChecksum = historySnapshot(db);
            Result checksum = invoke(env);

            assertThat(checksum.exitCode()).isEqualTo(1);
            assertThat(checksum.stdout()).isEmpty();
            assertThat(checksum.stderr())
                    .contains("do not validate")
                    .contains("4:CHECKSUM_MISMATCH");

            assertThat(historySnapshot(db)).isEqualTo(beforeChecksum);
        }
    }

    private static Map<String, String> environment(
            PostgreSQLContainer db) {

        return Map.of(
                "DB_HOST", db.getHost(),
                "DB_PORT", String.valueOf(db.getMappedPort(5432)),
                "DB_NAME", db.getDatabaseName(),
                "DB_MIGRATOR_USER", "triageharbor_migrator",
                "DB_MIGRATOR_PASSWORD", PASSWORD);
    }

    private static FluentConfiguration migrator(
            PostgreSQLContainer db) {

        return Flyway.configure()
                .dataSource(
                        db.getJdbcUrl(),
                        "triageharbor_migrator",
                        PASSWORD)
                .locations("classpath:db/migration");
    }

    private static void admin(PostgreSQLContainer db, String sql)
            throws SQLException {

        try (Connection connection =
                        DriverManager.getConnection(
                                db.getJdbcUrl(),
                                db.getUsername(),
                                db.getPassword());
                Statement statement = connection.createStatement()) {

            assertThat(statement.executeUpdate(sql)).isEqualTo(1);
        }
    }

    private static String historySnapshot(PostgreSQLContainer db)
            throws SQLException {

        try (Connection connection =
                        DriverManager.getConnection(
                                db.getJdbcUrl(),
                                db.getUsername(),
                                db.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                """
                                SELECT coalesce(string_agg(
                                    installed_rank::text
                                        || ':' || coalesce(version, 'R')
                                        || ':' || coalesce(checksum::text, 'null')
                                        || ':' || success::text,
                                    ',' ORDER BY installed_rank), '')
                                FROM flyway_schema_history
                                """)) {

            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static int migrationCount(PostgreSQLContainer db)
            throws SQLException {

        try (Connection connection =
                        DriverManager.getConnection(
                                db.getJdbcUrl(),
                                db.getUsername(),
                                db.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT count(*) FROM flyway_schema_history")) {

            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    private static Result invoke(Map<String, String> env) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode;

        try (PrintStream out =
                        new PrintStream(
                                stdout, true, StandardCharsets.UTF_8);
                PrintStream err =
                        new PrintStream(
                                stderr, true, StandardCharsets.UTF_8)) {

            exitCode =
                    CredentialAdmin.run(
                            new String[] {
                                "revoke",
                                "--project",
                                PROJECT,
                                "--credential",
                                "22222222-2222-2222-2222-222222222222"
                            },
                            env,
                            out,
                            err);
        }

        return new Result(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record Result(
            int exitCode, String stdout, String stderr) {}
}
