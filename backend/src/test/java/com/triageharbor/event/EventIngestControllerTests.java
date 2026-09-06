package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.triageharbor.support.PostgresTestConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class EventIngestControllerTests {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE events, incidents, projects");
    }

    @Test
    void validRequestReturnsAcceptedAndPersistsEvent() throws Exception {
        ProjectCredential project = insertProject("Project A");

        HttpResponse<String> response =
                postEvents(
                        project.ingestKey().toString(),
                        """
                        {
                          "occurredAt": "2026-09-05T20:00:00Z",
                          "exceptionType": "java.lang.IllegalStateException",
                          "message": "Something failed",
                          "stackTrace": "stack trace"
                        }
                        """);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEmpty();
        assertThat(rowCount("incidents")).isEqualTo(1L);
        assertThat(rowCount("events")).isEqualTo(1L);
    }

    @Test
    void missingIngestKeyReturnsUnauthorized() throws Exception {
        HttpResponse<String> response =
                postEvents(
                        null,
                        validBody());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
        assertThat(rowCount("incidents")).isZero();
        assertThat(rowCount("events")).isZero();
    }

    @Test
    void malformedIngestKeyReturnsUnauthorized() throws Exception {
        HttpResponse<String> response =
                postEvents(
                        "not-a-uuid",
                        validBody());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
        assertThat(rowCount("incidents")).isZero();
        assertThat(rowCount("events")).isZero();
    }

    @Test
    void unknownIngestKeyReturnsUnauthorized() throws Exception {
        HttpResponse<String> response =
                postEvents(
                        UUID.randomUUID().toString(),
                        validBody());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
        assertThat(rowCount("incidents")).isZero();
        assertThat(rowCount("events")).isZero();
    }

    @Test
    void missingOccurredAtReturnsBadRequestWithoutEchoingEventContent() throws Exception {
        ProjectCredential project = insertProject("Project A");

        HttpResponse<String> response =
                postEvents(
                        project.ingestKey().toString(),
                        """
                        {
                          "message": "Something failed"
                        }
                        """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .doesNotContain(project.ingestKey().toString())
                .doesNotContain("Something failed");

        assertThat(rowCount("incidents")).isZero();
        assertThat(rowCount("events")).isZero();
    }

    @Test
    void missingMessageReturnsBadRequestWithoutEchoingCredential() throws Exception {
        ProjectCredential project = insertProject("Project A");

        HttpResponse<String> response =
                postEvents(
                        project.ingestKey().toString(),
                        """
                        {
                          "occurredAt": "2026-09-05T20:00:00Z"
                        }
                        """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .doesNotContain(project.ingestKey().toString());

        assertThat(rowCount("incidents")).isZero();
        assertThat(rowCount("events")).isZero();
    }

    @Test
    void malformedJsonReturnsBadRequestWithoutEchoingEventContent() throws Exception {
        ProjectCredential project = insertProject("Project A");

        HttpResponse<String> response =
                postEvents(
                        project.ingestKey().toString(),
                        """
                        {
                          "occurredAt": "2026-09-05T20:00:00Z",
                          "message": "Sensitive synthetic marker"
                        """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .doesNotContain(project.ingestKey().toString())
                .doesNotContain("Sensitive synthetic marker");

        assertThat(rowCount("incidents")).isZero();
        assertThat(rowCount("events")).isZero();
    }

    @Test
    void malformedRequestIsRejectedBeforeCredentialCheck() throws Exception {
        HttpResponse<String> response =
                postEvents(
                        null,
                        "{ not json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(rowCount("incidents")).isZero();
        assertThat(rowCount("events")).isZero();
    }

    @Test
    void callerSuppliedProjectIdCannotChooseTenant() throws Exception {
        ProjectCredential projectA = insertProject("Project A");
        ProjectCredential projectB = insertProject("Project B");

        HttpResponse<String> response =
                postEvents(
                        projectA.ingestKey().toString(),
                        """
                        {
                          "projectId": "%s",
                          "occurredAt": "2026-09-05T20:00:00Z",
                          "message": "Something failed"
                        }
                        """
                                .formatted(projectB.projectId()));

        assertThat(response.statusCode()).isEqualTo(202);

        UUID incidentProjectId =
                jdbcTemplate.queryForObject(
                        "SELECT project_id FROM incidents",
                        UUID.class);

        UUID eventProjectId =
                jdbcTemplate.queryForObject(
                        "SELECT project_id FROM events",
                        UUID.class);

        assertThat(incidentProjectId).isEqualTo(projectA.projectId());
        assertThat(eventProjectId).isEqualTo(projectA.projectId());
    }

    @Test
    void oversizedEventReturnsPayloadTooLarge() throws Exception {
        ProjectCredential project = insertProject("Project A");

        String oversizedMessage =
                "a".repeat(EventRequestSizeFilter.MAX_REQUEST_BYTES);

        String body =
                """
                {
                  "occurredAt": "2026-09-05T20:00:00Z",
                  "message": "%s"
                }
                """
                        .formatted(oversizedMessage);

        assertThat(body.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(EventRequestSizeFilter.MAX_REQUEST_BYTES);

        HttpResponse<String> response =
                postEvents(
                        project.ingestKey().toString(),
                        body);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).isEmpty();
        assertThat(rowCount("incidents")).isZero();
        assertThat(rowCount("events")).isZero();
    }

    private HttpResponse<String> postEvents(
            String ingestKey,
            String body)
            throws Exception {

        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/events"))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        body,
                                        StandardCharsets.UTF_8));

        if (ingestKey != null) {
            request.header(EventIngestController.INGEST_KEY_HEADER, ingestKey);
        }

        return HTTP_CLIENT.send(
                request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private ProjectCredential insertProject(String name) {
        UUID projectId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO projects (id, name) VALUES (?, ?)",
                projectId,
                name);

        UUID ingestKey =
                jdbcTemplate.queryForObject(
                        "SELECT ingest_key FROM projects WHERE id = ?",
                        UUID.class,
                        projectId);

        return new ProjectCredential(projectId, ingestKey);
    }

    private long rowCount(String table) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table,
                        Long.class);

        return count;
    }

    private String validBody() {
        return """
                {
                  "occurredAt": "2026-09-05T20:00:00Z",
                  "message": "Something failed"
                }
                """;
    }

    private record ProjectCredential(UUID projectId, UUID ingestKey) {}
}
