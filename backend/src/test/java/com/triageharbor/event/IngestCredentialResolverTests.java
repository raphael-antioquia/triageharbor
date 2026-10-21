package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triageharbor.support.PostgresTestConfiguration;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class IngestCredentialResolverTests {

    @Autowired
    private IngestCredentialResolver ingestCredentialResolver;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE events, incidents, projects");
    }

    @Test
    void ingestKeyResolvesToItsOwnProject() {
        UUID projectA = insertProject("Project A");
        UUID projectB = insertProject("Project B");

        AuthenticatedProject authenticatedA =
                ingestCredentialResolver.resolve(ingestKeyOf(projectA));

        AuthenticatedProject authenticatedB =
                ingestCredentialResolver.resolve(ingestKeyOf(projectB));

        assertThat(authenticatedA.id()).isEqualTo(projectA);
        assertThat(authenticatedB.id()).isEqualTo(projectB);
        assertThat(authenticatedA.id()).isNotEqualTo(authenticatedB.id());
    }

    @Test
    void unknownIngestKeyIsRejected() {
        assertThatThrownBy(
                        () ->
                                ingestCredentialResolver.resolve(
                                        UUID.randomUUID()))
                .isInstanceOf(UnknownIngestKeyException.class);
    }

    private UUID insertProject(String name) {
        UUID id = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO projects (id, name) VALUES (?, ?)",
                id,
                name);

        return id;
    }

    private UUID ingestKeyOf(UUID projectId) {
        UUID ingestKey =
                jdbcTemplate.queryForObject(
                        "SELECT ingest_key FROM projects WHERE id = ?",
                        UUID.class,
                        projectId);

        return Objects.requireNonNull(ingestKey);
    }
}
