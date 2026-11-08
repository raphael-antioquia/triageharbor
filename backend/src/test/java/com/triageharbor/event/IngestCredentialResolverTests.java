package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triageharbor.support.IngestCredentialFixtures;
import com.triageharbor.support.PostgresTestConfiguration;
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

    private static final String GOLDEN_INGEST_TOKEN =
            "th_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @Autowired
    private IngestCredentialResolver ingestCredentialResolver;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE events, incidents, project_ingest_credentials, projects");
    }

    @Test
    void ingestKeyResolvesToItsOwnProject() {
        UUID projectA = insertProject("Project A");
        UUID projectB = insertProject("Project B");

        String keyA =
                IngestCredentialFixtures.insertForProject(
                        jdbcTemplate, projectA);

        String keyB =
                IngestCredentialFixtures.insertForProject(
                        jdbcTemplate, projectB);

        AuthenticatedProject authenticatedA =
                ingestCredentialResolver.resolve(keyA);

        AuthenticatedProject authenticatedB =
                ingestCredentialResolver.resolve(keyB);

        assertThat(authenticatedA.id()).isEqualTo(projectA);
        assertThat(authenticatedB.id()).isEqualTo(projectB);
        assertThat(authenticatedA.id()).isNotEqualTo(authenticatedB.id());
    }

    @Test
    void knownGoldenHashResolvesToItsProject() {
        UUID projectId = insertProject("Golden Vector Project");

        // Independent SHA-256 vector for the entire UTF-8 token.
        // Do not calculate this value using production code or fixtures.
        jdbcTemplate.update(
                """
                INSERT INTO project_ingest_credentials (
                    project_id, key_hash, key_prefix
                )
                VALUES (
                    ?,
                    decode(
                        '4408c404a92fa9553a8bca1b57b5a0757bfb1324cd9b980b8d457034ca22aff4',
                        'hex'
                    ),
                    'th_AAECAwQF'
                )
                """,
                projectId);

        AuthenticatedProject authenticated =
                ingestCredentialResolver.resolve(GOLDEN_INGEST_TOKEN);

        assertThat(authenticated.id()).isEqualTo(projectId);
    }

    @Test
    void unknownIngestKeyIsRejected() {
        assertThatThrownBy(
                        () -> ingestCredentialResolver.resolve(
                                "th_" + "A".repeat(43)))
                .isInstanceOf(UnknownIngestKeyException.class);
    }

    @Test
    void malformedIngestKeysAreRejected() {
        String validShape = "th_" + "A".repeat(43);

        String[] malformedKeys = {
            null,
            "",
            "th_" + "A".repeat(42),
            "th_" + "A".repeat(44),
            validShape + "=",
            " " + validShape,
            validShape + " ",
            validShape + "," + validShape,
            "TH_" + "A".repeat(43)
        };

        for (String malformedKey : malformedKeys) {
            assertThatThrownBy(
                            () -> ingestCredentialResolver.resolve(malformedKey))
                    .isInstanceOf(UnknownIngestKeyException.class);
        }
    }

    private UUID insertProject(String name) {
        UUID projectId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO projects (id, name) VALUES (?, ?)",
                projectId,
                name);

        return projectId;
    }
}
