package com.triageharbor;

import static org.assertj.core.api.Assertions.assertThat;

import com.triageharbor.support.IngestCredentialFixtures;
import com.triageharbor.support.RoleSeparatedPostgresTestConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(RoleSeparatedPostgresTestConfiguration.class)
class CredentialAdminRevokeTests {

    private static final String PASSWORD = "test-migrator-password";

    private static final DateTimeFormatter UTC_MICROS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                    .withZone(ZoneOffset.UTC);

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Test
    void revokeOneOfTwoCredentialsLeavesSiblingActive()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID targetId;
        UUID siblingId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            targetId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            siblingId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        assertThat(revokedAt(targetId)).isNull();
        assertThat(revokedAt(siblingId)).isNull();

        Result result = invoke(projectId, targetId, false);

        // Outcome first: committed state seen from a separate session.
        assertThat(revokedAt(targetId)).isNotNull();
        assertThat(revokedAt(siblingId)).isNull();

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEmpty();

        // Exactly one receipt line: no warning, no cleanup message.
        assertThat(result.stderr())
                .isEqualTo(
                        "Credential " + targetId
                                + " revoked for project " + projectId
                                + " (name=revoke-project)."
                                + System.lineSeparator());
    }

    @Test
    void repeatedRevokePreservesOriginalTimestamp()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID targetId;
        UUID siblingA;
        UUID siblingB;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            targetId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            siblingA = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            siblingB = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        Result first = invoke(projectId, targetId, false);

        OffsetDateTime original = revokedAt(targetId);

        assertThat(original).isNotNull();
        assertThat(revokedAt(siblingA)).isNull();
        assertThat(revokedAt(siblingB)).isNull();
        assertThat(first.exitCode()).isZero();

        Result repeat = invoke(projectId, targetId, false);

        // Check committed state before interpreting the CLI result.
        assertThat(revokedAt(targetId)).isEqualTo(original);
        assertThat(revokedAt(siblingA)).isNull();
        assertThat(revokedAt(siblingB)).isNull();

        assertThat(repeat.exitCode())
                .as("stderr: %s", repeat.stderr())
                .isZero();
        assertThat(repeat.stdout()).isEmpty();
        assertThat(repeat.stderr())
                .isEqualTo(
                        "Credential " + targetId
                                + " already revoked at "
                                + UTC_MICROS.format(original.toInstant())
                                + " for project " + projectId
                                + " (name=revoke-project). No change made."
                                + System.lineSeparator());
    }

    @Test
    void alreadyRevokedSucceedsWithoutForceWhenSiblingIsLastActive()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID revokedId;
        UUID lastActiveId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            revokedId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            lastActiveId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        Result first = invoke(projectId, revokedId, false);

        OffsetDateTime original = revokedAt(revokedId);

        assertThat(original).isNotNull();
        assertThat(revokedAt(lastActiveId)).isNull();
        assertThat(first.exitCode()).isZero();

        // One active credential remains; retry without --force-last.
        Result repeat = invoke(projectId, revokedId, false);

        assertThat(revokedAt(revokedId)).isEqualTo(original);
        assertThat(revokedAt(lastActiveId)).isNull();

        assertThat(repeat.exitCode())
                .as("stderr: %s", repeat.stderr())
                .isZero();
        assertThat(repeat.stdout()).isEmpty();
        assertThat(repeat.stderr())
                .isEqualTo(
                        "Credential " + revokedId
                                + " already revoked at "
                                + UTC_MICROS.format(original.toInstant())
                                + " for project " + projectId
                                + " (name=revoke-project). No change made."
                                + System.lineSeparator());
    }

    private Result invoke(
            UUID projectId, UUID credentialId, boolean forceLast) {

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        String[] args = forceLast
                ? new String[] {
                    "revoke", "--project", projectId.toString(),
                    "--credential", credentialId.toString(),
                    "--force-last"
                }
                : new String[] {
                    "revoke", "--project", projectId.toString(),
                    "--credential", credentialId.toString()
                };

        int exitCode;

        try (PrintStream out = new PrintStream(
                        stdout, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(
                        stderr, true, StandardCharsets.UTF_8)) {

            exitCode = CredentialAdmin.run(
                    args, environment(), out, err);
        }

        return new Result(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private Map<String, String> environment() {
        return Map.of(
                "DB_HOST", postgresContainer.getHost(),
                "DB_PORT",
                String.valueOf(postgresContainer.getMappedPort(5432)),
                "DB_NAME", postgresContainer.getDatabaseName(),
                "DB_MIGRATOR_USER", "triageharbor_migrator",
                "DB_MIGRATOR_PASSWORD", PASSWORD);
    }

    private Connection migratorConnection() throws SQLException {
        return DriverManager.getConnection(
                postgresContainer.getJdbcUrl(),
                "triageharbor_migrator",
                PASSWORD);
    }

    private void insertProject(Connection connection, UUID projectId)
            throws SQLException {

        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO projects (id, name) VALUES (?, ?)")) {

            statement.setObject(1, projectId);
            statement.setString(2, "revoke-project");

            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private UUID credentialId(
            Connection connection, UUID projectId, String token)
            throws SQLException {

        try (PreparedStatement statement =
                connection.prepareStatement(
                        """
                        SELECT id
                        FROM project_ingest_credentials
                        WHERE project_id = ?
                          AND key_hash = sha256(convert_to(?, 'UTF8'))
                        """)) {

            statement.setObject(1, projectId);
            statement.setString(2, token);

            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                UUID id = rows.getObject(1, UUID.class);
                assertThat(rows.next()).isFalse();
                return id;
            }
        }
    }

    private OffsetDateTime revokedAt(UUID credentialId)
            throws SQLException {

        try (Connection connection = migratorConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT revoked_at
                                FROM project_ingest_credentials
                                WHERE id = ?
                                """)) {

            statement.setObject(1, credentialId);

            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                OffsetDateTime value =
                        rows.getObject(1, OffsetDateTime.class);
                assertThat(rows.next()).isFalse();
                return value;
            }
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}
