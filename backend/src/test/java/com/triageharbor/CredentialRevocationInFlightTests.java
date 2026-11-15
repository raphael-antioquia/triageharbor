package com.triageharbor;

import static org.assertj.core.api.Assertions.assertThat;

import com.triageharbor.event.AuthenticatedProject;
import com.triageharbor.event.EventIngestRequest;
import com.triageharbor.event.EventIngestService;
import com.triageharbor.event.IngestCredentialResolver;
import com.triageharbor.support.IngestCredentialFixtures;
import com.triageharbor.support.RoleSeparatedPostgresTestConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Characterizes the accepted revocation contract: a project resolved
// before revocation commits may still persist; a fresh lookup after
// the commit is rejected. Deterministic interleaving only.
//
// Must not be @Transactional: the writer would join a test transaction
// that rolls back, and the migrator session would see no rows.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RoleSeparatedPostgresTestConfiguration.class)
class CredentialRevocationInFlightTests {

    private static final String MIGRATOR_PASSWORD = "test-migrator-password";
    private static final String PROJECT_NAME = "b6-project";
    private static final String NL = System.lineSeparator();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final String EVENT_JSON =
            """
            {
              "occurredAt": "2026-11-14T00:00:00Z",
              "exceptionType": "B6Exception",
              "message": "B6 revoked-window event",
              "stackTrace": "B6 stack"
            }
            """;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Autowired
    private IngestCredentialResolver resolver;

    @Autowired
    private EventIngestService writer;

    @LocalServerPort
    private int port;

    private UUID projectId;

    @Test
    @Timeout(45)
    void resolvedBeforeRevocationStillPersistsWithSiblingActive()
            throws Exception {

        projectId = UUID.randomUUID();

        String targetToken;
        String siblingToken;
        UUID targetId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection);
            targetToken =
                    IngestCredentialFixtures.insertForProject(connection, projectId);
            targetId = credentialId(connection, targetToken);
            siblingToken =
                    IngestCredentialFixtures.insertForProject(connection, projectId);
        }

        assertThat(activeCredentialCount()).isEqualTo(2);

        // Request A has resolved, but has not entered the writer.
        AuthenticatedProject previouslyResolved = resolver.resolve(targetToken);
        assertThat(previouslyResolved.id()).isEqualTo(projectId);

        // The real operator CLI commits revocation before Request A writes.
        CliResult revoked = revoke(targetId, false);

        // Committed prerequisites first, with CLI context attached.
        assertThat(revokedAt(targetId))
                .as("CLI exit=%s stderr=%s", revoked.exitCode(), revoked.stderr())
                .isNotNull();
        assertThat(activeCredentialCount())
                .as("CLI exit=%s stderr=%s", revoked.exitCode(), revoked.stderr())
                .isEqualTo(1);
        assertThat(ingestState()).isEqualTo(new IngestState(0, 0, 0));

        assertThat(revoked.exitCode()).isZero();
        assertThat(revoked.stdout()).isEmpty();
        assertThat(revoked.stderr()).isEqualTo(receipt(targetId) + NL);

        // Request A persists with its pre-revocation project identity.
        writer.ingest(previouslyResolved, b6Event());

        assertThat(ingestState()).isEqualTo(new IngestState(1, 1, 1));

        // Request B: fresh lookup after the commit must reject.
        HttpResponse<String> rejected = postEvents(targetToken);

        assertThat(ingestState())
                .as("revoked-key HTTP status=%s", rejected.statusCode())
                .isEqualTo(new IngestState(1, 1, 1));
        assertThat(rejected.statusCode()).isEqualTo(401);
        assertThat(rejected.body()).isEmpty();

        // Positive control: same helper and project, active sibling.
        HttpResponse<String> accepted = postEvents(siblingToken);

        assertThat(ingestState())
                .as("sibling HTTP status=%s", accepted.statusCode())
                .isEqualTo(new IngestState(1, 2, 2));
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(accepted.body()).isEmpty();
        assertThat(activeCredentialCount()).isEqualTo(1);
    }

    @Test
    @Timeout(45)
    void resolvedBeforeLastKeyRevocationStillPersists() throws Exception {
        projectId = UUID.randomUUID();

        String onlyToken;
        UUID onlyId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection);
            onlyToken =
                    IngestCredentialFixtures.insertForProject(connection, projectId);
            onlyId = credentialId(connection, onlyToken);
        }

        assertThat(activeCredentialCount()).isEqualTo(1);

        AuthenticatedProject previouslyResolved = resolver.resolve(onlyToken);
        assertThat(previouslyResolved.id()).isEqualTo(projectId);

        CliResult revoked = revoke(onlyId, true);

        // Prerequisite: zero active credentials after a committed revoke.
        assertThat(revokedAt(onlyId))
                .as("CLI exit=%s stderr=%s", revoked.exitCode(), revoked.stderr())
                .isNotNull();
        assertThat(activeCredentialCount())
                .as("CLI exit=%s stderr=%s", revoked.exitCode(), revoked.stderr())
                .isZero();
        assertThat(ingestState()).isEqualTo(new IngestState(0, 0, 0));

        assertThat(revoked.exitCode()).isZero();
        assertThat(revoked.stdout()).isEmpty();
        assertThat(revoked.stderr())
                .isEqualTo(
                        receipt(onlyId) + NL
                                + "WARNING: project " + projectId
                                + " now has no active ingest credential; "
                                + "ingestion fails until a new credential is issued."
                                + NL);

        // Zero active keys. If the writer starts rejecting here, the
        // revocation contract changed: update CONTEXT deliberately.
        writer.ingest(previouslyResolved, b6Event());

        assertThat(ingestState()).isEqualTo(new IngestState(1, 1, 1));

        HttpResponse<String> rejected = postEvents(onlyToken);

        assertThat(ingestState())
                .as("revoked-key HTTP status=%s", rejected.statusCode())
                .isEqualTo(new IngestState(1, 1, 1));
        assertThat(rejected.statusCode()).isEqualTo(401);
        assertThat(rejected.body()).isEmpty();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        if (projectId == null) {
            return;
        }

        // Project-scoped cleanup as the migrator. No TRUNCATE or CASCADE.
        try (Connection connection = migratorConnection()) {
            deleteForProject(connection, "DELETE FROM events WHERE project_id = ?");
            deleteForProject(connection, "DELETE FROM incidents WHERE project_id = ?");
            deleteForProject(
                    connection,
                    "DELETE FROM project_ingest_credentials WHERE project_id = ?");
            deleteForProject(connection, "DELETE FROM projects WHERE id = ?");
        }
    }

    private static EventIngestRequest b6Event() {
        return new EventIngestRequest(
                Instant.parse("2026-11-14T00:00:00Z"),
                "B6Exception",
                "B6 revoked-window event",
                "B6 stack");
    }

    private String receipt(UUID credentialId) {
        return "Credential " + credentialId
                + " revoked for project " + projectId
                + " (name=" + PROJECT_NAME + ").";
    }

    private CliResult revoke(UUID credentialId, boolean forceLast) {
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "revoke",
                                "--project", projectId.toString(),
                                "--credential", credentialId.toString()));

        if (forceLast) {
            args.add("--force-last");
        }

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode;

        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {

            exitCode =
                    CredentialAdmin.run(
                            args.toArray(String[]::new),
                            Map.of(
                                    "DB_HOST", postgresContainer.getHost(),
                                    "DB_PORT",
                                    String.valueOf(postgresContainer.getMappedPort(5432)),
                                    "DB_NAME", postgresContainer.getDatabaseName(),
                                    "DB_MIGRATOR_USER", "triageharbor_migrator",
                                    "DB_MIGRATOR_PASSWORD", MIGRATOR_PASSWORD),
                            out,
                            err);
        }

        return new CliResult(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postEvents(String token) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/events"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-TriageHarbor-Ingest-Key", token)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        EVENT_JSON, StandardCharsets.UTF_8))
                        .build();

        return HTTP_CLIENT.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private Connection migratorConnection() throws SQLException {
        return DriverManager.getConnection(
                postgresContainer.getJdbcUrl(), "triageharbor_migrator", MIGRATOR_PASSWORD);
    }

    private void insertProject(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO projects (id, name) VALUES (?, ?)")) {

            statement.setObject(1, projectId);
            statement.setString(2, PROJECT_NAME);

            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private UUID credentialId(Connection connection, String token)
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

    private OffsetDateTime revokedAt(UUID credentialId) throws SQLException {
        try (Connection connection = migratorConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT revoked_at FROM project_ingest_credentials WHERE id = ?")) {

            statement.setObject(1, credentialId);

            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                OffsetDateTime value = rows.getObject(1, OffsetDateTime.class);
                assertThat(rows.next()).isFalse();
                return value;
            }
        }
    }

    private long activeCredentialCount() throws SQLException {
        try (Connection connection = migratorConnection()) {
            return count(
                    connection,
                    """
                    SELECT count(*)
                    FROM project_ingest_credentials
                    WHERE project_id = ?
                      AND revoked_at IS NULL
                    """);
        }
    }

    private IngestState ingestState() throws SQLException {
        try (Connection connection = migratorConnection()) {
            return new IngestState(
                    count(connection, "SELECT count(*) FROM incidents WHERE project_id = ?"),
                    count(
                            connection,
                            "SELECT coalesce(sum(occurrence_count), 0) FROM incidents WHERE project_id = ?"),
                    count(connection, "SELECT count(*) FROM events WHERE project_id = ?"));
        }
    }

    private long count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, projectId);

            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                long value = rows.getLong(1);
                assertThat(rows.next()).isFalse();
                return value;
            }
        }
    }

    private void deleteForProject(Connection connection, String sql)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, projectId);
            statement.executeUpdate();
        }
    }

    private record CliResult(int exitCode, String stdout, String stderr) {}

    private record IngestState(long incidents, long occurrences, long events) {}
}
