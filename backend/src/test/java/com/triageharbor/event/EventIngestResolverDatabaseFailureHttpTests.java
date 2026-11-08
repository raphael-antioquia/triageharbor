package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triageharbor.support.IngestCredentialFixtures;
import com.triageharbor.support.RoleSeparatedPostgresTestConfiguration;
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
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RoleSeparatedPostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventIngestResolverDatabaseFailureHttpTests {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final String RESTORE_SELECT =
            "GRANT SELECT ON project_ingest_credentials TO triageharbor_app";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IngestCredentialResolver ingestCredentialResolver;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @AfterEach
    void cleanUpAsAdmin() throws SQLException {
        try (Connection admin = adminConnection();
                Statement statement = admin.createStatement()) {

            // Backstop if the test failed before normal restoration.
            statement.execute(RESTORE_SELECT);

            statement.execute(
                    "TRUNCATE TABLE events, incidents, project_ingest_credentials, projects");
        }
    }

    @Test
    void realCredentialLookupPermissionFailureReturnsSanitized500(
            CapturedOutput output) throws Exception {

        UUID projectId = UUID.randomUUID();
        String ingestKey;

        // Fixture setup runs as admin, not as the restricted app role.
        try (Connection admin = adminConnection();
                PreparedStatement statement =
                        admin.prepareStatement(
                                """
                                INSERT INTO projects (id, name)
                                VALUES (?, ?)
                                """)) {

            statement.setObject(1, projectId);
            statement.setString(2, "Permission failure project");

            assertThat(statement.executeUpdate()).isEqualTo(1);

            ingestKey =
                    IngestCredentialFixtures.insertForProject(
                            admin, projectId);
        }

        // Positive control: the actual app-role resolver works first.
        assertThat(ingestCredentialResolver.resolve(ingestKey).id())
                .isEqualTo(projectId);

        try {
            executeAsAdmin(
                    """
                    REVOKE SELECT
                    ON project_ingest_credentials
                    FROM triageharbor_app
                    """);

            // Confirm PostgreSQL is genuinely denying app-role SELECT.
            assertThatThrownBy(
                            () -> jdbcTemplate.queryForObject(
                                    """
                                    SELECT COUNT(*)
                                    FROM project_ingest_credentials
                                    """,
                                    Long.class))
                    .isInstanceOfSatisfying(
                            DataAccessException.class,
                            exception -> {
                                Throwable cause =
                                        exception.getMostSpecificCause();

                                assertThat(cause)
                                        .isInstanceOf(SQLException.class);

                                assertThat(((SQLException) cause).getSQLState())
                                        .isEqualTo("42501");
                            });

            // Exercise the real resolver through HTTP, without mocking it.
            HttpResponse<String> response = postEvents(ingestKey);

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("Content-Type"))
                    .isEmpty();

            assertNoPersistedEventsOrIncidents();

            // The controller should log only the exception class,
            // not the secret or PostgreSQL's permission-error details.
            assertThat(output.getAll())
                    .contains("Database failure during POST /events:")
                    .doesNotContain(ingestKey)
                    .doesNotContain("permission denied")
                    .doesNotContain("SELECT project_id");

        } finally {
            // Restore the original narrow, table-level SELECT grant.
            executeAsAdmin(RESTORE_SELECT);
        }
    }

    private HttpResponse<String> postEvents(String ingestKey)
            throws Exception {

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/events"))
                        .header("Content-Type", "application/json")
                        .header(
                                EventIngestController.INGEST_KEY_HEADER,
                                ingestKey)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        """
                                        {
                                          "occurredAt": "2026-09-05T20:00:00Z",
                                          "message": "Synthetic resolver failure"
                                        }
                                        """,
                                        StandardCharsets.UTF_8))
                        .build();

        return HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void assertNoPersistedEventsOrIncidents()
            throws SQLException {

        try (Connection admin = adminConnection();
                Statement statement = admin.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                """
                                SELECT
                                    (SELECT COUNT(*) FROM events)
                                        AS event_count,
                                    (SELECT COUNT(*) FROM incidents)
                                        AS incident_count
                                """)) {

            assertThat(result.next()).isTrue();
            assertThat(result.getLong("event_count")).isZero();
            assertThat(result.getLong("incident_count")).isZero();
        }
    }

    private void executeAsAdmin(String sql) throws SQLException {
        try (Connection admin = adminConnection();
                Statement statement = admin.createStatement()) {

            statement.execute(sql);
        }
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword());
    }
}
