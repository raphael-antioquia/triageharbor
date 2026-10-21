package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triageharbor.support.PostgresTestConfiguration;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;

@SpringBootTest(properties = {
    "spring.datasource.hikari.maximum-pool-size=1",
    "spring.datasource.hikari.connection-timeout=1000",
    "spring.datasource.hikari.validation-timeout=500"
})
@Import(PostgresTestConfiguration.class)
class EventIngestTransactionStartFailureTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IngestCredentialResolver ingestCredentialResolver;

    @Autowired
    private EventIngestService eventIngestService;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE events, incidents, projects");
    }

    @Test
    void exhaustedPoolPreventsTransactionStart() throws Exception {
        UUID projectId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO projects (id, name) VALUES (?, ?)",
                projectId,
                "Pool exhaustion test");

        UUID ingestKey =
                jdbcTemplate.queryForObject(
                        "SELECT ingest_key FROM projects WHERE id = ?",
                        UUID.class,
                        projectId);

        // Resolve before occupying the pool's only connection.
        AuthenticatedProject project =
                ingestCredentialResolver.resolve(ingestKey);

        assertThat(project.id()).isEqualTo(projectId);

        EventIngestRequest request =
                new EventIngestRequest(
                        Instant.parse("2026-09-05T20:00:00Z"),
                        "java.lang.IllegalStateException",
                        "Synthetic pool failure",
                        "Synthetic stack trace");

        // This test intentionally has no @Transactional annotation.
        try (Connection heldConnection = dataSource.getConnection()) {
            assertThat(heldConnection.isClosed()).isFalse();

            assertThatThrownBy(
                            () -> eventIngestService.ingest(project, request))
                    .isInstanceOf(CannotCreateTransactionException.class);
        }

        // Check durable state only after releasing the held connection.
        assertThat(rowCount("incidents")).isZero();
        assertThat(rowCount("events")).isZero();
    }

    private long rowCount(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Long.class);
    }
}
