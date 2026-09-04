package com.triageharbor.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.triageharbor.support.PostgresTestConfiguration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class DatabaseConstraintTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE events, incidents, projects");
    }

    @Test
    void validProjectIncidentAndEventCanBePersisted() {
        UUID projectId = insertProject("Test Project");
        UUID incidentId = insertIncident(projectId, "fingerprint-1");
        UUID eventId = UUID.randomUUID();

        OffsetDateTime now = now();

        jdbcTemplate.update(
                """
                INSERT INTO events (
                    id,
                    project_id,
                    incident_id,
                    occurred_at,
                    received_at,
                    exception_type,
                    message,
                    stack_trace
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                projectId,
                incidentId,
                now,
                now,
                "java.lang.IllegalStateException",
                "Something failed",
                "stack trace");

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM events WHERE id = ?",
                        Integer.class,
                        eventId);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void incidentCannotReferenceNonexistentProject() {
        assertConstraintViolation(
                "23503",
                "incidents_project_fk",
                () -> insertIncident(UUID.randomUUID(), "fingerprint-1"));
    }

    @Test
    void duplicateIncidentFingerprintIdentityIsRejected() {
        UUID projectId = insertProject("Test Project");

        insertIncident(projectId, "fingerprint-1");

        assertConstraintViolation(
                "23505",
                "incidents_fingerprint_unique",
                () -> insertIncident(projectId, "fingerprint-1"));
    }

    @Test
    void sameFingerprintInDifferentProjectsIsAllowed() {
        UUID projectA = insertProject("Project A");
        UUID projectB = insertProject("Project B");

        insertIncident(projectA, "fingerprint-shared");
        insertIncident(projectB, "fingerprint-shared");

        Integer count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM incidents
                        WHERE fingerprint = ?
                        """,
                        Integer.class,
                        "fingerprint-shared");

        assertThat(count).isEqualTo(2);
    }

    @Test
    void sameFingerprintUnderDifferentVersionsCoexistsInOneProject() {
        UUID projectId = insertProject("Test Project");

        insertIncident(projectId, "fingerprint-1", 1, 1, "open");
        insertIncident(projectId, "fingerprint-1", 2, 1, "open");

        Integer count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM incidents
                        WHERE project_id = ?
                          AND fingerprint = ?
                        """,
                        Integer.class,
                        projectId,
                        "fingerprint-1");

        assertThat(count).isEqualTo(2);
    }

    @Test
    void fingerprintVersionMustBePositive() {
        UUID projectId = insertProject("Test Project");

        assertConstraintViolation(
                "23514",
                "incidents_fingerprint_version_positive",
                () -> insertIncident(projectId, "fingerprint-1", 0, 1, "open"));
    }

    @Test
    void occurrenceCountMustBePositive() {
        UUID projectId = insertProject("Test Project");

        assertConstraintViolation(
                "23514",
                "incidents_occurrence_count_positive",
                () -> insertIncident(projectId, "fingerprint-1", 1, 0, "open"));
    }

    @Test
    void lastSeenCannotPrecedeFirstSeen() {
        UUID projectId = insertProject("Test Project");

        OffsetDateTime firstSeen =
                OffsetDateTime.parse("2026-09-04T12:00:00Z");
        OffsetDateTime lastSeen =
                OffsetDateTime.parse("2026-09-04T11:59:59Z");

        assertConstraintViolation(
                "23514",
                "incidents_seen_order_valid",
                () ->
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
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                                UUID.randomUUID(),
                                projectId,
                                "fingerprint-1",
                                1,
                                "Failure",
                                "open",
                                1L,
                                firstSeen,
                                lastSeen));
    }

    @Test
    void unsupportedIncidentStatusIsRejected() {
        UUID projectId = insertProject("Test Project");

        assertConstraintViolation(
                "23514",
                "incidents_status_valid",
                () -> insertIncident(projectId, "fingerprint-1", 1, 1, "ignored"));
    }

    @Test
    void eventCannotReferenceIncidentOwnedByDifferentProject() {
        UUID projectA = insertProject("Project A");
        UUID projectB = insertProject("Project B");

        UUID incidentA = insertIncident(projectA, "fingerprint-a");

        OffsetDateTime now = now();

        assertConstraintViolation(
                "23503",
                "events_incident_project_fk",
                () ->
                        jdbcTemplate.update(
                                """
                                INSERT INTO events (
                                    id,
                                    project_id,
                                    incident_id,
                                    occurred_at,
                                    received_at,
                                    message
                                )
                                VALUES (?, ?, ?, ?, ?, ?)
                                """,
                                UUID.randomUUID(),
                                projectB,
                                incidentA,
                                now,
                                now,
                                "Cross-project event"));
    }

    @Test
    void projectDeletionIsRestrictedWhenIncidentExists() {
        UUID projectId = insertProject("Test Project");

        insertIncident(projectId, "fingerprint-1");

        assertConstraintViolation(
                "23503",
                "incidents_project_fk",
                () ->
                        jdbcTemplate.update(
                                "DELETE FROM projects WHERE id = ?",
                                projectId));
    }

    @Test
    void incidentDeletionIsRestrictedWhenEventExists() {
        UUID projectId = insertProject("Test Project");
        UUID incidentId = insertIncident(projectId, "fingerprint-1");

        OffsetDateTime now = now();

        jdbcTemplate.update(
                """
                INSERT INTO events (
                    id,
                    project_id,
                    incident_id,
                    occurred_at,
                    received_at,
                    message
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                projectId,
                incidentId,
                now,
                now,
                "Failure");

        assertConstraintViolation(
                "23503",
                "events_incident_project_fk",
                () ->
                        jdbcTemplate.update(
                                "DELETE FROM incidents WHERE id = ?",
                                incidentId));
    }

    @Test
    void eventMessageCannotBeNull() {
        UUID projectId = insertProject("Test Project");
        UUID incidentId = insertIncident(projectId, "fingerprint-1");

        OffsetDateTime now = now();

        assertColumnViolation(
                "23502",
                "message",
                () ->
                        jdbcTemplate.update(
                                """
                                INSERT INTO events (
                                    id,
                                    project_id,
                                    incident_id,
                                    occurred_at,
                                    received_at,
                                    message
                                )
                                VALUES (?, ?, ?, ?, ?, NULL)
                                """,
                                UUID.randomUUID(),
                                projectId,
                                incidentId,
                                now,
                                now));
    }

    private UUID insertProject(String name) {
        UUID id = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO projects (id, name) VALUES (?, ?)",
                id,
                name);

        return id;
    }

    private UUID insertIncident(UUID projectId, String fingerprint) {
        return insertIncident(projectId, fingerprint, 1, 1, "open");
    }

    private UUID insertIncident(
            UUID projectId,
            String fingerprint,
            int fingerprintVersion,
            long occurrenceCount,
            String status) {

        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();

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
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                projectId,
                fingerprint,
                fingerprintVersion,
                "Failure",
                status,
                occurrenceCount,
                now,
                now);

        return id;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private void assertConstraintViolation(
            String expectedSqlState,
            String expectedConstraint,
            Runnable operation) {

        PSQLException exception = postgresException(operation);

        assertThat(exception.getSQLState())
                .isEqualTo(expectedSqlState);

        assertThat(exception.getServerErrorMessage())
                .isNotNull();

        assertThat(exception.getServerErrorMessage().getConstraint())
                .isEqualTo(expectedConstraint);
    }

    private void assertColumnViolation(
            String expectedSqlState,
            String expectedColumn,
            Runnable operation) {

        PSQLException exception = postgresException(operation);

        assertThat(exception.getSQLState())
                .isEqualTo(expectedSqlState);

        assertThat(exception.getServerErrorMessage())
                .isNotNull();

        assertThat(exception.getServerErrorMessage().getColumn())
                .isEqualTo(expectedColumn);
    }

    private PSQLException postgresException(Runnable operation) {
        Throwable thrown = catchThrowable(operation::run);

        assertThat(thrown).isNotNull();

        Throwable cause = NestedExceptionUtils.getMostSpecificCause(thrown);

        assertThat(cause).isInstanceOf(PSQLException.class);

        return (PSQLException) cause;
    }
}
