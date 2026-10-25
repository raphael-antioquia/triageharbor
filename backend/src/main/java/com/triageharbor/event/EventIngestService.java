package com.triageharbor.event;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventIngestService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public EventIngestService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional
    public void ingest(AuthenticatedProject project, EventIngestRequest request) {
        RedactedEvent redactedEvent = RedactedEvent.from(request);

        UUID projectId = project.id();

        Instant receivedAt = clock.instant();

        String fingerprint =
                EventFingerprintV1.compute(
                        redactedEvent.exceptionType(),
                        redactedEvent.message());

        String title =
                IncidentTitle.derive(
                        redactedEvent.exceptionType(),
                        redactedEvent.message());

        OffsetDateTime receivedAtUtc = toUtcOffset(receivedAt);

        UUID incidentId =
                jdbcTemplate.queryForObject(
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
                        ON CONFLICT (project_id, fingerprint_version, fingerprint)
                        DO UPDATE SET
                            occurrence_count = incidents.occurrence_count + 1,
                            first_seen_at = LEAST(
                                incidents.first_seen_at,
                                EXCLUDED.first_seen_at
                            ),
                            last_seen_at = GREATEST(
                                incidents.last_seen_at,
                                EXCLUDED.last_seen_at
                            )
                        -- When incident regression is introduced, keep the status
                        -- transition in this atomic conflict update rather than
                        -- performing a separate application-side transition.
                        RETURNING id
                        """,
                        UUID.class,
                        projectId,
                        fingerprint,
                        EventFingerprintV1.VERSION,
                        title,
                        receivedAtUtc,
                        receivedAtUtc);

        jdbcTemplate.update(
                """
                INSERT INTO events (
                    project_id,
                    incident_id,
                    occurred_at,
                    received_at,
                    exception_type,
                    message,
                    stack_trace
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                projectId,
                incidentId,
                toUtcOffset(redactedEvent.occurredAt()),
                receivedAtUtc,
                redactedEvent.exceptionType(),
                redactedEvent.message(),
                redactedEvent.stackTrace());
    }

    private static OffsetDateTime toUtcOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
