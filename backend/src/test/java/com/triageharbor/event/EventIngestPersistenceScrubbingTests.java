package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.triageharbor.support.RoleSeparatedPostgresTestConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(RoleSeparatedPostgresTestConfiguration.class)
class EventIngestPersistenceScrubbingTests {

    @Autowired
    private EventIngestService eventIngestService;

    @Autowired
    private IngestCredentialResolver ingestCredentialResolver;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @AfterEach
    void cleanUpAsAdmin() throws SQLException {
        try (Connection admin = adminConnection();
                Statement statement = admin.createStatement()) {
            statement.execute("TRUNCATE TABLE events, incidents, projects");
        }
    }

    @Test
    void persistedEventAndIncidentContainSanitizedValues() throws SQLException {
        ProjectCredential project = insertProjectAsAdmin("Project A");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        eventIngestService.ingest(
                authenticatedProject,
                new EventIngestRequest(
                        Instant.parse("2026-01-01T10:00:00Z"),
                        "clientSecret=CANARY_TYPE_91b0aa",
                        "password=CANARY_MSG_7f3a91,tail&more",
                        "Caused by: Authorization: Bearer CANARY_TRACE_c2e8d4"));

        try (Connection admin = adminConnection();
                PreparedStatement counts =
                        admin.prepareStatement(
                                """
                                SELECT
                                    (SELECT COUNT(*) FROM incidents
                                     WHERE project_id = ?) AS incident_count,
                                    (SELECT COUNT(*) FROM events
                                     WHERE project_id = ?) AS event_count
                                """);
                PreparedStatement persisted =
                        admin.prepareStatement(
                                """
                                SELECT
                                    e.exception_type,
                                    e.message,
                                    e.stack_trace,
                                    row_to_json(e)::text AS event_row_json,
                                    i.title,
                                    i.fingerprint,
                                    i.fingerprint_version,
                                    i.occurrence_count
                                FROM events e
                                JOIN incidents i
                                  ON i.project_id = e.project_id
                                 AND i.id = e.incident_id
                                WHERE e.project_id = ?
                                """)) {

            counts.setObject(1, project.projectId());
            counts.setObject(2, project.projectId());

            try (ResultSet result = counts.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("incident_count")).isEqualTo(1L);
                assertThat(result.getLong("event_count")).isEqualTo(1L);
            }

            persisted.setObject(1, project.projectId());

            try (ResultSet result = persisted.executeQuery()) {
                assertThat(result.next()).isTrue();

                assertThat(result.getString("event_row_json"))
                        .doesNotContain(
                                "CANARY_TYPE_91b0aa",
                                "CANARY_MSG_7f3a91",
                                "CANARY_TRACE_c2e8d4");

                assertThat(result.getString("exception_type"))
                        .isEqualTo("clientSecret=[REDACTED]");

                assertThat(result.getString("message"))
                        .isEqualTo("password=[REDACTED]");

                assertThat(result.getString("stack_trace"))
                        .isEqualTo("Caused by: Authorization: [REDACTED]");

                assertThat(result.getString("title"))
                        .isEqualTo("clientSecret=[REDACTED]");

                assertThat(result.getString("fingerprint"))
                        .isEqualTo(
                                "922f0af55a5c416555c2e8dc1cff07e4b3bb75c97f977f18b56fb938e3d87bf6");

                assertThat(result.getInt("fingerprint_version"))
                        .isEqualTo(EventFingerprintV2.VERSION);

                assertThat(result.getLong("occurrence_count"))
                        .isEqualTo(1L);

                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void differentRawSecretsGroupBySanitizedFingerprint() throws SQLException {
        ProjectCredential project = insertProjectAsAdmin("Project A");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        for (String suffix : new String[] {"FIRST_91ab", "SECOND_73cd"}) {
            eventIngestService.ingest(
                    authenticatedProject,
                    new EventIngestRequest(
                            Instant.parse("2026-01-01T10:00:00Z"),
                            "clientSecret=CANARY_TYPE_" + suffix,
                            "password=CANARY_MSG_" + suffix,
                            "Caused by: Authorization: Bearer CANARY_TRACE_" + suffix));
        }

        try (Connection admin = adminConnection();
                PreparedStatement incidents =
                        admin.prepareStatement(
                                """
                                SELECT title, fingerprint, fingerprint_version,
                                       occurrence_count
                                FROM incidents
                                WHERE project_id = ?
                                """);
                PreparedStatement events =
                        admin.prepareStatement(
                                """
                                SELECT exception_type, message, stack_trace,
                                       row_to_json(e)::text AS event_row_json
                                FROM events e
                                WHERE project_id = ?
                                """)) {

            incidents.setObject(1, project.projectId());

            try (ResultSet result = incidents.executeQuery()) {
                assertThat(result.next()).isTrue();

                assertThat(result.getString("title"))
                        .isEqualTo("clientSecret=[REDACTED]");

                assertThat(result.getString("fingerprint"))
                        .isEqualTo(
                                "922f0af55a5c416555c2e8dc1cff07e4b3bb75c97f977f18b56fb938e3d87bf6");

                assertThat(result.getInt("fingerprint_version")).isEqualTo(EventFingerprintV2.VERSION);
                assertThat(result.getLong("occurrence_count")).isEqualTo(2L);
                assertThat(result.next()).isFalse();
            }

            events.setObject(1, project.projectId());

            try (ResultSet result = events.executeQuery()) {
                int eventCount = 0;

                while (result.next()) {
                    eventCount++;

                    assertThat(result.getString("exception_type"))
                            .isEqualTo("clientSecret=[REDACTED]");

                    assertThat(result.getString("message"))
                            .isEqualTo("password=[REDACTED]");

                    assertThat(result.getString("stack_trace"))
                            .isEqualTo("Caused by: Authorization: [REDACTED]");

                    assertThat(result.getString("event_row_json"))
                            .doesNotContain("CANARY_");
                }

                assertThat(eventCount).isEqualTo(2);
            }
        }
    }

    @Test
    void nullExceptionTypeUsesSanitizedMessageForIncident()
            throws SQLException {
        ProjectCredential project = insertProjectAsAdmin("Project A");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        eventIngestService.ingest(
                authenticatedProject,
                new EventIngestRequest(
                        Instant.parse("2026-01-01T10:00:00Z"),
                        null,
                        "password=CANARY_NULL_MSG_d02e",
                        "at example.Service.run(Service.java:42)"));

        try (Connection admin = adminConnection();
                PreparedStatement statement =
                        admin.prepareStatement(
                                """
                                SELECT
                                    e.exception_type,
                                    e.message,
                                    e.stack_trace,
                                    row_to_json(e)::text AS event_row_json,
                                    i.title,
                                    i.fingerprint,
                                    i.fingerprint_version,
                                    i.occurrence_count
                                FROM events e
                                JOIN incidents i
                                  ON i.project_id = e.project_id
                                 AND i.id = e.incident_id
                                WHERE e.project_id = ?
                                """)) {

            statement.setObject(1, project.projectId());

            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();

                assertThat(result.getString("exception_type")).isNull();

                assertThat(result.getString("message"))
                        .isEqualTo("password=[REDACTED]");

                assertThat(result.getString("stack_trace"))
                        .isEqualTo("at example.Service.run(Service.java:42)");

                assertThat(result.getString("title"))
                        .isEqualTo("password=[REDACTED]");

                assertThat(result.getString("fingerprint"))
                        .isEqualTo(
                                "493f566fc15dd6f7f667337837d50b8a74633cc70bcb730e0b80bb5a22f207f3");

                assertThat(result.getInt("fingerprint_version"))
                        .isEqualTo(EventFingerprintV2.VERSION);

                assertThat(result.getLong("occurrence_count"))
                        .isEqualTo(1L);

                assertThat(result.getString("event_row_json"))
                        .doesNotContain("CANARY_NULL_MSG_d02e");

                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void credentialShapedSecretsAreScrubbedBeforePersistence()
            throws SQLException {
        String tokenA =
                "th_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
        String tokenB =
                "th_BAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

        ProjectCredential project = insertProjectAsAdmin("Project A");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        for (String token : List.of(tokenA, tokenB)) {
            eventIngestService.ingest(
                    authenticatedProject,
                    new EventIngestRequest(
                            Instant.parse("2026-01-01T10:00:00Z"),
                            "SyntheticError " + token,
                            "failure reported " + token,
                            "at synthetic.Handler " + token));
        }

        try (Connection admin = adminConnection();
                PreparedStatement statement =
                        admin.prepareStatement(
                                """
                                SELECT
                                    e.exception_type,
                                    e.message,
                                    e.stack_trace,
                                    row_to_json(e)::text AS event_row_json,
                                    i.title,
                                    i.fingerprint_version,
                                    i.occurrence_count,
                                    row_to_json(i)::text AS incident_row_json
                                FROM events e
                                JOIN incidents i
                                  ON i.project_id = e.project_id
                                 AND i.id = e.incident_id
                                WHERE e.project_id = ?
                                """)) {

            statement.setObject(1, project.projectId());

            int eventCount = 0;

            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    eventCount++;

                    assertThat(result.getString("exception_type"))
                            .isEqualTo("SyntheticError [REDACTED]");

                    assertThat(result.getString("message"))
                            .isEqualTo("failure reported [REDACTED]");

                    assertThat(result.getString("stack_trace"))
                            .isEqualTo("at synthetic.Handler [REDACTED]");

                    assertThat(result.getString("title"))
                            .isEqualTo("SyntheticError [REDACTED]");

                    assertThat(result.getInt("fingerprint_version"))
                            .isEqualTo(EventFingerprintV2.VERSION);

                    assertThat(result.getLong("occurrence_count"))
                            .isEqualTo(2L);

                    assertThat(result.getString("event_row_json"))
                            .doesNotContain(tokenA, tokenB);

                    assertThat(result.getString("incident_row_json"))
                            .doesNotContain(tokenA, tokenB);
                }
            }

            assertThat(eventCount).isEqualTo(2);
        }
    }

    private ProjectCredential insertProjectAsAdmin(String name)
            throws SQLException {
        UUID projectId = UUID.randomUUID();

        try (Connection admin = adminConnection();
                PreparedStatement statement =
                        admin.prepareStatement(
                                """
                                INSERT INTO projects (id, name)
                                VALUES (?, ?)
                                RETURNING ingest_key
                                """)) {

            statement.setObject(1, projectId);
            statement.setString(2, name);

            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();

                return new ProjectCredential(
                        projectId,
                        result.getObject("ingest_key", UUID.class));
            }
        }
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword());
    }

    private record ProjectCredential(UUID projectId, UUID ingestKey) {}
}
