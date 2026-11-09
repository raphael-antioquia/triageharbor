package com.triageharbor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.triageharbor.support.IngestCredentialFixtures;
import com.triageharbor.support.RoleSeparatedPostgresTestConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Import(RoleSeparatedPostgresTestConfiguration.class)
class CredentialAdminListTests {

    private static final String PASSWORD = "test-migrator-password";

    private static final String HEADER =
            "project_id\tproject_name\tcredential_id"
                    + "\tkey_prefix\tcreated_at_utc\trevoked_at_utc";

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Test
    void existingProjectWithoutCredentialsProducesEmptyInventory()
            throws SQLException {

        UUID projectId = UUID.randomUUID();

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId, "empty-project");
        }

        Result result = invoke(projectId);

        assertThat(result.exitCode()).isZero();

        List<String> lines = result.stdout().lines().toList();

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo(HEADER);

        assertThat(lines.get(1).split("\t", -1))
                .containsExactly(
                        projectId.toString(),
                        "empty-project",
                        "",
                        "",
                        "",
                        "");
    }

    @Test
    void nonexistentProjectProducesOperationalFailure() {
        Result result = invoke(UUID.randomUUID());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("Project not found.");
    }

    @Test
    void listingOneProjectNeverIncludesAnotherProjectsCredentials()
            throws SQLException {

        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();

        String tokenA;
        String tokenB;
        UUID credentialA;
        UUID credentialB;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectA, "Project A");
            insertProject(connection, projectB, "Project B");

            tokenA =
                    IngestCredentialFixtures.insertForProject(
                            connection, projectA);

            tokenB =
                    IngestCredentialFixtures.insertForProject(
                            connection, projectB);

            credentialA = onlyCredentialId(connection, projectA);
            credentialB = onlyCredentialId(connection, projectB);
        }

        Result result = invoke(projectA);

        assertThat(result.exitCode()).isZero();

        List<String> lines = result.stdout().lines().toList();

        // Header plus exactly one credential belonging to A.
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo(HEADER);

        String[] fields = lines.get(1).split("\t", -1);

        assertThat(fields).hasSize(6);
        assertThat(fields[0]).isEqualTo(projectA.toString());
        assertThat(fields[1]).isEqualTo("Project A");
        assertThat(fields[2]).isEqualTo(credentialA.toString());
        assertThat(fields[3]).isEqualTo(tokenA.substring(0, 11));

        assertThat(result.stdout())
                .doesNotContain(
                        projectB.toString(),
                        credentialB.toString(),
                        tokenB.substring(0, 11),
                        tokenA,
                        tokenB);

        assertThat(result.stderr())
                .doesNotContain(tokenA, tokenB);
    }

    @Test
    void listConnectionIsReadOnlyInPostgres()
            throws SQLException {

        try (Connection connection =
                        CredentialAdmin.openReadOnlyConnection(environment());
                Statement statement = connection.createStatement()) {

            assertThat(connection.isReadOnly()).isTrue();

            try (ResultSet rows =
                    statement.executeQuery(
                            "SHOW transaction_read_only")) {

                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("on");
            }

            SQLException failure =
                    assertThrows(
                            SQLException.class,
                            () ->
                                    statement.executeUpdate(
                                            """
                                            INSERT INTO projects (name)
                                            VALUES ('should-not-exist')
                                            """));

            // 25006 = read_only_sql_transaction
            assertThat(failure.getSQLState()).isEqualTo("25006");
        }
    }

    @Test
    void failedStdoutReturnsNonzero()
            throws SQLException {

        UUID projectId = UUID.randomUUID();

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId, "output-failure");
        }

        ByteArrayOutputStream diagnostics =
                new ByteArrayOutputStream();

        try (PrintStream failingOut =
                        new PrintStream(
                                new OutputStream() {
                                    @Override
                                    public void write(int value)
                                            throws IOException {
                                        throw new IOException("stdout closed");
                                    }
                                },
                                true,
                                StandardCharsets.UTF_8);
                PrintStream err =
                        new PrintStream(
                                diagnostics,
                                true,
                                StandardCharsets.UTF_8)) {

            int exitCode =
                    CredentialAdmin.run(
                            new String[] {
                                "list", "--project", projectId.toString()
                            },
                            environment(),
                            failingOut,
                            err);

            assertThat(exitCode).isEqualTo(1);
            assertThat(diagnostics.toString(StandardCharsets.UTF_8))
                    .contains("Credential list output failed.")
                    .doesNotContain("stdout closed");
        }
    }


    @Test
    void activeAndRevokedCredentialsShowUtcMicrosecondTimestamps()
            throws SQLException {

        UUID projectId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        UUID revokedId = UUID.randomUUID();

        OffsetDateTime activeCreated =
                OffsetDateTime.parse("2026-11-08T12:00:00+05:30");

        OffsetDateTime revokedCreated =
                OffsetDateTime.parse("2026-11-08T13:14:15.123456+05:30");

        OffsetDateTime revokedAt =
                OffsetDateTime.parse("2026-11-09T09:10:11.654321+05:30");

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId, "timestamp-project");

            // Insert the later credential first to exercise
            // chronological ordering rather than insertion order.
            insertCredential(
                    connection,
                    projectId,
                    revokedId,
                    "th_ZYXWVUTS",
                    revokedCreated,
                    revokedAt);

            insertCredential(
                    connection,
                    projectId,
                    activeId,
                    "th_ABCDEFGH",
                    activeCreated,
                    null);
        }

        Result result = invoke(projectId);

        assertThat(result.exitCode()).isZero();

        List<String> lines = result.stdout().lines().toList();

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo(HEADER);

        assertThat(lines.get(1).split("\t", -1))
                .containsExactly(
                        projectId.toString(),
                        "timestamp-project",
                        activeId.toString(),
                        "th_ABCDEFGH",
                        "2026-11-08T06:30:00.000000Z",
                        "");

        assertThat(lines.get(2).split("\t", -1))
                .containsExactly(
                        projectId.toString(),
                        "timestamp-project",
                        revokedId.toString(),
                        "th_ZYXWVUTS",
                        "2026-11-08T07:44:15.123456Z",
                        "2026-11-09T03:40:11.654321Z");
    }

    @Test
    void equalTimestampsAreOrderedByCredentialId()
            throws SQLException {

        UUID projectId = UUID.randomUUID();

        // Both UUIDs have identical high bits. Their low bits
        // establish an unambiguous PostgreSQL UUID ordering.
        UUID firstId =
                new UUID(projectId.getMostSignificantBits(), 1L);

        UUID secondId =
                new UUID(projectId.getMostSignificantBits(), 2L);

        OffsetDateTime created =
                OffsetDateTime.parse("2026-01-01T00:00:00Z");

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId, "ordering-project");

            // Deliberately insert in the opposite order.
            insertCredential(
                    connection,
                    projectId,
                    secondId,
                    "th_ZYXWVUTS",
                    created,
                    null);

            insertCredential(
                    connection,
                    projectId,
                    firstId,
                    "th_ABCDEFGH",
                    created,
                    null);
        }

        Result result = invoke(projectId);

        assertThat(result.exitCode()).isZero();

        List<String> lines = result.stdout().lines().toList();

        assertThat(lines).hasSize(3);

        assertThat(lines.get(1).split("\t", -1)[2])
                .isEqualTo(firstId.toString());

        assertThat(lines.get(2).split("\t", -1)[2])
                .isEqualTo(secondId.toString());
    }

    @Test
    void hostileProjectNameIsEscapedForTerminalOutput()
            throws SQLException {

        UUID projectId = UUID.randomUUID();

        String hostileName =
                "alpha\tbeta\nnext\rline\\slash"
                        + (char) 0x1b + "[31m"
                        + (char) 0x202e + "end"
                        + (char) 0x85 + "nel"
                        + (char) 0x2028 + "ls";

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId, hostileName);
        }

        Result result = invoke(projectId);

        assertThat(result.exitCode()).isZero();

        List<String> lines = result.stdout().lines().toList();

        assertThat(lines).hasSize(2);

        String[] fields = lines.get(1).split("\t", -1);

        assertThat(fields).hasSize(6);
        assertThat(fields[0]).isEqualTo(projectId.toString());

        assertThat(fields[1])
                .isEqualTo(
                        "alpha\\tbeta\\nnext\\rline\\\\slash"
                                + "\\u{1b}[31m\\u{202e}end"
                                + "\\u{85}nel\\u{2028}ls");

        assertThat(result.stdout())
                .doesNotContain(
                        String.valueOf((char) 0x1b),
                        String.valueOf((char) 0x202e),
                        String.valueOf((char) 0x85),
                        String.valueOf((char) 0x2028));
    }

    @Test
    void inventoryNeverPrintsRawTokenOrSha256Hash()
            throws SQLException, NoSuchAlgorithmException {

        UUID projectId = UUID.randomUUID();
        String token;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId, "secret-test");

            token =
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId);
        }

        String hashHex =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                token.getBytes(
                                                        StandardCharsets.UTF_8)));

        Result result = invoke(projectId);

        assertThat(result.exitCode()).isZero();

        assertThat(result.stdout())
                .contains(token.substring(0, 11))
                .doesNotContain(token, hashHex);

        assertThat(result.stderr())
                .doesNotContain(token, hashHex);
    }

    @Test
    void missingCredentialTableAfterPreflightFailsSafely()
            throws SQLException {

        // Never modify migration state or schema in the shared container.
        try (PostgreSQLContainer db =
                new PostgreSQLContainer(
                                DockerImageName.parse("postgres:17.11"))
                        .withDatabaseName("triageharbor")
                        .withInitScript("db/test-role-bootstrap.sql")) {

            db.start();

            // Apply the actual packaged migrations.
            Flyway.configure()
                    .dataSource(
                            db.getJdbcUrl(),
                            "triageharbor_migrator",
                            PASSWORD)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            UUID projectId = UUID.randomUUID();

            try (Connection connection =
                    DriverManager.getConnection(
                            db.getJdbcUrl(),
                            "triageharbor_migrator",
                            PASSWORD)) {

                insertProject(
                        connection, projectId, "schema-drift-test");
            }

            // Deliberately change the schema outside Flyway.
            try (Connection connection =
                            DriverManager.getConnection(
                                    db.getJdbcUrl(),
                                    db.getUsername(),
                                    db.getPassword());
                    Statement statement = connection.createStatement()) {

                statement.executeUpdate(
                        """
                        ALTER TABLE project_ingest_credentials
                        RENAME TO project_ingest_credentials_hidden
                        """);
            }

            Map<String, String> env = environment(db);

            // Migration history is still valid, despite schema drift.
            CredentialAdmin.verifySchema(env);

            // The real inventory command should fail at its SELECT.
            Result result = invoke(projectId, env);

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.stdout()).isEmpty();

            assertThat(result.stderr())
                    .contains(
                            "Credential inventory failed (SQLState 42P01).")
                    .doesNotContain(
                            "project_ingest_credentials",
                            "schema-drift-test",
                            PASSWORD);
        }
    }

    private Result invoke(UUID projectId) {
        return invoke(projectId, environment());
    }

    private Result invoke(
            UUID projectId, Map<String, String> env) {

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
                                "list", "--project", projectId.toString()
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

    private Map<String, String> environment() {
        return environment(postgresContainer);
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

    private Connection migratorConnection()
            throws SQLException {

        return DriverManager.getConnection(
                postgresContainer.getJdbcUrl(),
                "triageharbor_migrator",
                PASSWORD);
    }

    private static void insertProject(
            Connection connection,
            UUID projectId,
            String name)
            throws SQLException {

        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO projects (id, name) VALUES (?, ?)")) {

            statement.setObject(1, projectId);
            statement.setString(2, name);

            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static UUID onlyCredentialId(
            Connection connection,
            UUID projectId)
            throws SQLException {

        try (PreparedStatement statement =
                connection.prepareStatement(
                        """
                        SELECT id
                        FROM project_ingest_credentials
                        WHERE project_id = ?
                        """)) {

            statement.setObject(1, projectId);

            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();

                UUID id = rows.getObject(1, UUID.class);

                assertThat(rows.next()).isFalse();
                return id;
            }
        }
    }

    private static void insertCredential(
            Connection connection,
            UUID projectId,
            UUID credentialId,
            String prefix,
            OffsetDateTime createdAt,
            OffsetDateTime revokedAt)
            throws SQLException {

        // Deterministic, distinct 32-byte test digest derived
        // from the credential ID. Not a production token hash.
        byte[] testHash =
                ByteBuffer.allocate(32)
                        .putLong(credentialId.getMostSignificantBits())
                        .putLong(credentialId.getLeastSignificantBits())
                        .array();

        try (PreparedStatement statement =
                connection.prepareStatement(
                        """
                        INSERT INTO project_ingest_credentials (
                            id,
                            project_id,
                            key_hash,
                            key_prefix,
                            created_at,
                            revoked_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {

            statement.setObject(1, credentialId);
            statement.setObject(2, projectId);
            statement.setBytes(3, testHash);
            statement.setString(4, prefix);
            statement.setObject(5, createdAt);

            if (revokedAt == null) {
                statement.setNull(
                        6, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                statement.setObject(6, revokedAt);
            }

            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private record Result(
            int exitCode,
            String stdout,
            String stderr) {}
}
