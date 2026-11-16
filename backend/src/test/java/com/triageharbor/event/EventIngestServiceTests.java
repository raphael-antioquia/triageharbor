package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triageharbor.support.IngestCredentialFixtures;
import com.triageharbor.support.PostgresTestConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import({
    PostgresTestConfiguration.class,
    EventIngestServiceTests.TestClockConfiguration.class
})
class EventIngestServiceTests {

    private static final Instant RECEIVED_AT =
            Instant.parse("2026-09-05T20:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EventIngestService eventIngestService;

    @Autowired
    private IngestCredentialResolver ingestCredentialResolver;

    @Autowired
    private RecordingClock recordingClock;

    @BeforeEach
    void resetClock() {
        recordingClock.reset();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE events, incidents, project_ingest_credentials, projects");
    }

    @Test
    void validEventIsPersistedUnderProjectResolvedFromIngestKey() {
        ProjectCredential project = insertProject("Project A");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        eventIngestService.ingest(
                authenticatedProject,
                request(
                        Instant.parse("2026-01-01T10:00:00Z"),
                        "java.lang.IllegalStateException",
                        "Something failed"));

        UUID incidentProjectId =
                jdbcTemplate.queryForObject(
                        "SELECT project_id FROM incidents",
                        UUID.class);

        UUID eventProjectId =
                jdbcTemplate.queryForObject(
                        "SELECT project_id FROM events",
                        UUID.class);

        assertThat(incidentProjectId).isEqualTo(project.projectId());
        assertThat(eventProjectId).isEqualTo(project.projectId());
    }

    @Test
    void repeatedIdenticalEventsIncrementOneIncidentAndAppendBothEvents() {
        ProjectCredential project = insertProject("Project A");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        EventIngestRequest request =
                request(
                        Instant.parse("2026-01-01T10:00:00Z"),
                        "java.lang.IllegalStateException",
                        "Something failed");

        eventIngestService.ingest(authenticatedProject, request);
        eventIngestService.ingest(authenticatedProject, request);

        Long incidentRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM incidents",
                        Long.class);

        Long occurrenceCount =
                jdbcTemplate.queryForObject(
                        "SELECT occurrence_count FROM incidents",
                        Long.class);

        Long eventRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM events",
                        Long.class);

        assertThat(incidentRows).isEqualTo(1L);
        assertThat(occurrenceCount).isEqualTo(2L);
        assertThat(eventRows).isEqualTo(2L);
    }

    @Test
    void differentErrorsInOneProjectCreateSeparateIncidents() {
        ProjectCredential project = insertProject("Project A");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        Instant occurredAt = Instant.parse("2026-01-01T10:00:00Z");

        eventIngestService.ingest(
                authenticatedProject,
                request(
                        occurredAt,
                        "java.lang.IllegalStateException",
                        "Something failed"));

        eventIngestService.ingest(
                authenticatedProject,
                request(
                        occurredAt,
                        "java.io.IOException",
                        "Disk unavailable"));

        assertThat(rowCount("incidents")).isEqualTo(2L);
        assertThat(rowCount("events")).isEqualTo(2L);
    }

    @Test
    void identicalEventsUnderDifferentCredentialsCreateSeparateIncidents() {
        ProjectCredential projectA = insertProject("Project A");
        ProjectCredential projectB = insertProject("Project B");

        AuthenticatedProject authenticatedA =
                ingestCredentialResolver.resolve(projectA.ingestKey());
        AuthenticatedProject authenticatedB =
                ingestCredentialResolver.resolve(projectB.ingestKey());

        EventIngestRequest request =
                request(
                        Instant.parse("2026-01-01T10:00:00Z"),
                        "java.lang.IllegalStateException",
                        "Something failed");

        eventIngestService.ingest(authenticatedA, request);
        eventIngestService.ingest(authenticatedB, request);

        Long incidentRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM incidents",
                        Long.class);

        Long eventRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM events",
                        Long.class);

        assertThat(incidentRows).isEqualTo(2L);
        assertThat(eventRows).isEqualTo(2L);
    }

    @Test
    void receivedAtIsCapturedOnceAndUsedForIncidentAndEvent() {
        ProjectCredential project = insertProject("Project A");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        Instant occurredAt = Instant.parse("2020-01-01T00:00:00Z");

        eventIngestService.ingest(
                authenticatedProject,
                request(
                        occurredAt,
                        "java.lang.IllegalStateException",
                        "Something failed"));

        OffsetDateTime firstSeen =
                jdbcTemplate.queryForObject(
                        "SELECT first_seen_at FROM incidents",
                        OffsetDateTime.class);

        OffsetDateTime lastSeen =
                jdbcTemplate.queryForObject(
                        "SELECT last_seen_at FROM incidents",
                        OffsetDateTime.class);

        OffsetDateTime eventReceivedAt =
                jdbcTemplate.queryForObject(
                        "SELECT received_at FROM events",
                        OffsetDateTime.class);

        OffsetDateTime eventOccurredAt =
                jdbcTemplate.queryForObject(
                        "SELECT occurred_at FROM events",
                        OffsetDateTime.class);

        OffsetDateTime expectedReceivedAt =
                RECEIVED_AT.atOffset(ZoneOffset.UTC);

        assertThat(recordingClock.calls()).isEqualTo(1);
        assertThat(firstSeen).isEqualTo(expectedReceivedAt);
        assertThat(lastSeen).isEqualTo(expectedReceivedAt);
        assertThat(eventReceivedAt).isEqualTo(expectedReceivedAt);
        assertThat(eventOccurredAt)
                .isEqualTo(occurredAt.atOffset(ZoneOffset.UTC));
    }

    @Test
    void aggregateTimestampsAreIndependentOfArrivalOrder() {
        ProjectCredential project = insertProject("Project A");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        EventIngestRequest request =
                request(
                        Instant.parse("2026-01-01T10:00:00Z"),
                        "java.lang.IllegalStateException",
                        "Something failed");

        Instant later = Instant.parse("2026-09-05T21:00:00Z");
        Instant earlier = Instant.parse("2026-09-05T19:00:00Z");

        recordingClock.setInstant(later);
        eventIngestService.ingest(authenticatedProject, request);

        recordingClock.setInstant(earlier);
        eventIngestService.ingest(authenticatedProject, request);

        OffsetDateTime firstSeen =
                jdbcTemplate.queryForObject(
                        "SELECT first_seen_at FROM incidents",
                        OffsetDateTime.class);

        OffsetDateTime lastSeen =
                jdbcTemplate.queryForObject(
                        "SELECT last_seen_at FROM incidents",
                        OffsetDateTime.class);

        assertThat(firstSeen)
                .isEqualTo(earlier.atOffset(ZoneOffset.UTC));

        assertThat(lastSeen)
                .isEqualTo(later.atOffset(ZoneOffset.UTC));
    }

    @Test
    void matchingOccurrenceDoesNotRewriteIncidentTitle() {
        ProjectCredential project = insertProject("Project A");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        String fingerprint =
                EventFingerprintV2.compute(
                        "java.lang.IllegalStateException",
                        "Something failed");

        jdbcTemplate.update(
                """
                INSERT INTO incidents (
                    project_id,
                    fingerprint,
                    fingerprint_version,
                    title,
                    occurrence_count,
                    first_seen_at,
                    last_seen_at
                )
                VALUES (?, ?, ?, ?, 1, ?, ?)
                """,
                project.projectId(),
                fingerprint,
                EventFingerprintV2.VERSION,
                "Original title snapshot",
                RECEIVED_AT.atOffset(ZoneOffset.UTC),
                RECEIVED_AT.atOffset(ZoneOffset.UTC));

        eventIngestService.ingest(
                authenticatedProject,
                request(
                        Instant.parse("2026-01-01T10:00:00Z"),
                        "java.lang.IllegalStateException",
                        "Something failed"));

        String title =
                jdbcTemplate.queryForObject(
                        "SELECT title FROM incidents",
                        String.class);

        Long occurrenceCount =
                jdbcTemplate.queryForObject(
                        "SELECT occurrence_count FROM incidents",
                        Long.class);

        assertThat(title).isEqualTo("Original title snapshot");
        assertThat(occurrenceCount).isEqualTo(2L);
    }

    @Test
    void eventInsertFailureRollsBackIncidentUpsert() {
        ProjectCredential project = insertProject("Project A");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        EventIngestRequest invalidRequest =
                request(
                        null,
                        "java.lang.IllegalStateException",
                        "Something failed");

        assertThatThrownBy(
                        () ->
                                eventIngestService.ingest(
                                        authenticatedProject,
                                        invalidRequest))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(rowCount("incidents")).isZero();
        assertThat(rowCount("events")).isZero();
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

    private EventIngestRequest request(
            Instant occurredAt,
            String exceptionType,
            String message) {

        return new EventIngestRequest(
                occurredAt,
                exceptionType,
                message,
                "stack trace");
    }

    private long rowCount(String table) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table,
                        Long.class);

        return count;
    }

    private record ProjectCredential(
            UUID projectId,
            String ingestKey) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {

        @Bean
        @Primary
        RecordingClock recordingClock() {
            return new RecordingClock(RECEIVED_AT);
        }
    }

    static final class RecordingClock extends Clock {

        private volatile Instant instant;
        private final AtomicInteger calls = new AtomicInteger();

        RecordingClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            calls.incrementAndGet();
            return instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            instant = RECEIVED_AT;
            calls.set(0);
        }
    }
}
