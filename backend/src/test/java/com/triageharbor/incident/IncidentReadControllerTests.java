package com.triageharbor.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.triageharbor.support.IngestCredentialFixtures;
import com.triageharbor.support.PostgresTestConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
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
class IncidentReadControllerTests {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final String INGEST_KEY_HEADER = "X-TriageHarbor-Ingest-Key";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE events, incidents, project_ingest_credentials, projects");
    }

    @Test
    void existingProjectReturnsItsIncidents() throws Exception {
        ProjectCredential project = insertProject("Project A");

        UUID incidentId =
                UUID.fromString("11111111-1111-1111-1111-111111111111");

        insertIncident(
                incidentId,
                project.projectId(),
                "Something failed",
                "open",
                3L,
                Instant.parse("2026-09-07T00:00:00Z"),
                Instant.parse("2026-09-07T01:00:00Z"));

        HttpResponse<String> response = getIncidents(project.projectId());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains(incidentId.toString())
                .contains("Something failed");
        assertThat(countOccurrences(response.body(), "\"id\":")).isEqualTo(1);
    }

    @Test
    void projectReadDoesNotIncludeIncidentsFromAnotherProject() throws Exception {
        ProjectCredential projectA = insertProject("Project A");
        ProjectCredential projectB = insertProject("Project B");

        UUID incidentA =
                UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID incidentB =
                UUID.fromString("22222222-2222-2222-2222-222222222222");

        insertIncident(
                incidentA,
                projectA.projectId(),
                "Project A failure",
                "open",
                1L,
                Instant.parse("2026-09-07T00:00:00Z"),
                Instant.parse("2026-09-07T01:00:00Z"));

        insertIncident(
                incidentB,
                projectB.projectId(),
                "Project B failure",
                "open",
                1L,
                Instant.parse("2026-09-07T00:00:00Z"),
                Instant.parse("2026-09-07T01:00:00Z"));

        HttpResponse<String> response = getIncidents(projectA.projectId());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains(incidentA.toString())
                .contains("Project A failure")
                .doesNotContain(incidentB.toString())
                .doesNotContain("Project B failure");
        assertThat(countOccurrences(response.body(), "\"id\":")).isEqualTo(1);
    }

    @Test
    void incidentsAreOrderedByLastSeenDescending() throws Exception {
        ProjectCredential project = insertProject("Project A");

        UUID olderIncident =
                UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID newerIncident =
                UUID.fromString("22222222-2222-2222-2222-222222222222");

        insertIncident(
                olderIncident,
                project.projectId(),
                "Older failure",
                "open",
                1L,
                Instant.parse("2026-09-07T00:00:00Z"),
                Instant.parse("2026-09-07T01:00:00Z"));

        insertIncident(
                newerIncident,
                project.projectId(),
                "Newer failure",
                "open",
                1L,
                Instant.parse("2026-09-07T00:00:00Z"),
                Instant.parse("2026-09-07T02:00:00Z"));

        HttpResponse<String> response = getIncidents(project.projectId());

        assertThat(response.statusCode()).isEqualTo(200);

        int newerPosition = response.body().indexOf(newerIncident.toString());
        int olderPosition = response.body().indexOf(olderIncident.toString());

        assertThat(newerPosition).isGreaterThanOrEqualTo(0);
        assertThat(olderPosition).isGreaterThanOrEqualTo(0);
        assertThat(newerPosition).isLessThan(olderPosition);
    }

    @Test
    void identicalLastSeenUsesIdDescendingAsTieBreak() throws Exception {
        ProjectCredential project = insertProject("Project A");

        UUID lowerPostgresUuid =
                UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID higherPostgresUuid =
                UUID.fromString("80000000-0000-0000-0000-000000000000");

        Instant identicalLastSeen =
                Instant.parse("2026-09-07T01:00:00Z");

        insertIncident(
                lowerPostgresUuid,
                project.projectId(),
                "Lower UUID",
                "open",
                1L,
                Instant.parse("2026-09-07T00:00:00Z"),
                identicalLastSeen);

        insertIncident(
                higherPostgresUuid,
                project.projectId(),
                "Higher UUID",
                "open",
                1L,
                Instant.parse("2026-09-07T00:00:00Z"),
                identicalLastSeen);

        HttpResponse<String> response = getIncidents(project.projectId());

        assertThat(response.statusCode()).isEqualTo(200);

        int higherPosition =
                response.body().indexOf(higherPostgresUuid.toString());
        int lowerPosition =
                response.body().indexOf(lowerPostgresUuid.toString());

        assertThat(higherPosition).isGreaterThanOrEqualTo(0);
        assertThat(lowerPosition).isGreaterThanOrEqualTo(0);
        assertThat(higherPosition).isLessThan(lowerPosition);
    }

    @Test
    void existingProjectWithoutIncidentsReturnsEmptyList() throws Exception {
        ProjectCredential project = insertProject("Project A");

        HttpResponse<String> response = getIncidents(project.projectId());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("[]");
    }

    @Test
    void unknownProjectReturnsNotFound() throws Exception {
        HttpResponse<String> response =
                getIncidents(UUID.randomUUID());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void malformedProjectIdReturnsBadRequest() throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        "http://localhost:"
                                                + port
                                                + "/projects/not-a-uuid/incidents"))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

        HttpResponse<String> response =
                HTTP_CLIENT.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void responseMapsAndSerializesIncidentFieldsCorrectly() throws Exception {
        ProjectCredential project = insertProject("Project A");

        UUID incidentId =
                UUID.fromString("11111111-1111-1111-1111-111111111111");

        insertIncident(
                incidentId,
                project.projectId(),
                "Database unavailable",
                "resolved",
                42L,
                Instant.parse("2026-09-07T00:59:00Z"),
                Instant.parse("2026-09-07T01:00:00Z"));

        HttpResponse<String> response = getIncidents(project.projectId());

        assertThat(response.statusCode()).isEqualTo(200);

        assertThat(response.body())
                .contains("\"id\":\"" + incidentId + "\"")
                .contains("\"title\":\"Database unavailable\"")
                .contains("\"status\":\"resolved\"")
                .contains("\"occurrenceCount\":42")
                .contains("\"firstSeenAt\":\"2026-09-07T00:59:00Z\"")
                .contains("\"lastSeenAt\":\"2026-09-07T01:00:00Z\"")
                .doesNotContain("\"projectId\"")
                .doesNotContain("\"fingerprint\"")
                .doesNotContain("\"fingerprintVersion\"")
                .doesNotContain("\"createdAt\"");
        assertThat(countOccurrences(response.body(), "\"id\":")).isEqualTo(1);
    }

    @Test
    void readIgnoresIngestKeyHeader() throws Exception {
        ProjectCredential projectA = insertProject("Project A");
        ProjectCredential projectB = insertProject("Project B");

        UUID incidentA =
                UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID incidentB =
                UUID.fromString("22222222-2222-2222-2222-222222222222");

        insertIncident(
                incidentA,
                projectA.projectId(),
                "Project A failure",
                "open",
                1L,
                Instant.parse("2026-09-07T00:00:00Z"),
                Instant.parse("2026-09-07T01:00:00Z"));

        insertIncident(
                incidentB,
                projectB.projectId(),
                "Project B failure",
                "open",
                1L,
                Instant.parse("2026-09-07T00:00:00Z"),
                Instant.parse("2026-09-07T01:00:00Z"));

        HttpResponse<String> withoutHeader =
                getIncidents(projectA.projectId());

        HttpResponse<String> withProjectBIngestKey =
                getIncidents(
                        projectA.projectId(),
                        projectB.ingestKey());

        assertThat(withoutHeader.statusCode()).isEqualTo(200);
        assertThat(withProjectBIngestKey.statusCode()).isEqualTo(200);

        assertThat(withProjectBIngestKey.body())
                .isEqualTo(withoutHeader.body())
                .contains(incidentA.toString())
                .doesNotContain(incidentB.toString());
        assertThat(
                        countOccurrences(
                                withProjectBIngestKey.body(),
                                "\"id\":"))
                .isEqualTo(1);
    }

    private HttpResponse<String> getIncidents(UUID projectId)
            throws Exception {
        return getIncidents(projectId, null);
    }

    private HttpResponse<String> getIncidents(
            UUID projectId,
            String ingestKey)
            throws Exception {

        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        "http://localhost:"
                                                + port
                                                + "/projects/"
                                                + projectId
                                                + "/incidents"))
                        .header("Accept", "application/json")
                        .GET();

        if (ingestKey != null) {
            request.header(INGEST_KEY_HEADER, ingestKey);
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

        String ingestKey =
                IngestCredentialFixtures.insertForProject(
                        jdbcTemplate, projectId);

        return new ProjectCredential(projectId, ingestKey);
    }

    private void insertIncident(
            UUID incidentId,
            UUID projectId,
            String title,
            String status,
            long occurrenceCount,
            Instant firstSeenAt,
            Instant lastSeenAt) {

        jdbcTemplate.update(
                """
                INSERT INTO incidents (
                    id,
                    project_id,
                    fingerprint,
                    fingerprint_version,
                    title,
                    status,
                    occurrence_count,
                    first_seen_at,
                    last_seen_at
                )
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?)
                """,
                incidentId,
                projectId,
                "fingerprint-" + incidentId,
                title,
                status,
                occurrenceCount,
                firstSeenAt.atOffset(ZoneOffset.UTC),
                lastSeenAt.atOffset(ZoneOffset.UTC));
    }

    private static int countOccurrences(String value, String needle) {
        if (needle.isEmpty()) {
            throw new IllegalArgumentException("needle must not be empty");
        }

        int count = 0;
        int fromIndex = 0;

        while (true) {
            int index = value.indexOf(needle, fromIndex);

            if (index < 0) {
                return count;
            }

            count++;
            fromIndex = index + needle.length();
        }
    }

    private record ProjectCredential(
            UUID projectId,
            String ingestKey) {}
}
