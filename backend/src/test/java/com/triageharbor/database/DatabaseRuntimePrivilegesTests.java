package com.triageharbor.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triageharbor.event.AuthenticatedProject;
import com.triageharbor.event.EventIngestRequest;
import com.triageharbor.event.EventIngestService;
import com.triageharbor.event.IngestCredentialResolver;
import com.triageharbor.incident.IncidentReadService;
import com.triageharbor.incident.IncidentSummary;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(RoleSeparatedPostgresTestConfiguration.class)
class DatabaseRuntimePrivilegesTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EventIngestService eventIngestService;

    @Autowired
    private IngestCredentialResolver ingestCredentialResolver;

    @Autowired
    private IncidentReadService incidentReadService;

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
    void currentRuntimeOperationsSucceedAsAppRole() throws SQLException {
        ProjectCredential project = insertProjectAsAdmin("Project A");

        EventIngestRequest request =
                new EventIngestRequest(
                        Instant.parse("2026-09-15T04:00:00Z"),
                        "java.lang.IllegalStateException",
                        "Something failed",
                        "stack trace");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        eventIngestService.ingest(authenticatedProject, request);
        eventIngestService.ingest(authenticatedProject, request);

        List<IncidentSummary> incidents =
                incidentReadService.listForProject(project.projectId());

        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).occurrenceCount()).isEqualTo(2L);

        assertThat(eventCountAsAdmin()).isEqualTo(2L);
    }

    @Test
    void appRoleCannotExceedRuntimeAllowlist() {
        assertPermissionDenied(
                () ->
                        jdbcTemplate.update(
                                "INSERT INTO projects (name) VALUES ('not allowed')"));

        assertPermissionDenied(
                () ->
                        jdbcTemplate.update(
                                "UPDATE projects SET ingest_key = gen_random_uuid()"));

        assertPermissionDenied(
                () ->
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM events",
                                Long.class));

        assertPermissionDenied(
                () ->
                        jdbcTemplate.update(
                                "UPDATE events SET message = 'x'"));

        assertPermissionDenied(
                () ->
                        jdbcTemplate.update(
                                "DELETE FROM incidents"));

        assertPermissionDenied(
                () ->
                        jdbcTemplate.execute(
                                "TRUNCATE TABLE events"));

        assertPermissionDenied(
                () ->
                        jdbcTemplate.execute(
                                "CREATE TABLE app_probe (id int)"));
    }

    @Test
    void appRoleCannotReadFlywayHistory() {
        assertPermissionDenied(
                () ->
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM flyway_schema_history",
                                Long.class));
    }

    private ProjectCredential insertProjectAsAdmin(String name) throws SQLException {
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

            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();

                return new ProjectCredential(
                        projectId,
                        resultSet.getObject("ingest_key", UUID.class));
            }
        }
    }

    private long eventCountAsAdmin() throws SQLException {
        try (Connection admin = adminConnection();
                Statement statement = admin.createStatement();
                ResultSet resultSet =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM events")) {

            assertThat(resultSet.next()).isTrue();

            return resultSet.getLong(1);
        }
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword());
    }

    private void assertPermissionDenied(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        DataAccessException.class,
                        exception -> {
                            Throwable cause = exception.getMostSpecificCause();

                            assertThat(cause).isInstanceOf(SQLException.class);
                            assertThat(((SQLException) cause).getSQLState())
                                    .isEqualTo("42501");
                        });
    }

    private record ProjectCredential(UUID projectId, UUID ingestKey) {}
}
