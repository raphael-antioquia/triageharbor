package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.triageharbor.support.PostgresTestConfiguration;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "triageharbor.ingest.rate-limit.global.capacity=2000",
            "triageharbor.ingest.rate-limit.global.refill-tokens-per-minute=1000",
            "triageharbor.ingest.rate-limit.project.capacity=1",
            "triageharbor.ingest.rate-limit.project.refill-tokens-per-minute=20"
        })
@Import({
    PostgresTestConfiguration.class,
    PerProjectIngestRateLimitHttpTests.AdjustableTimeConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PerProjectIngestRateLimitHttpTests {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdjustableTimeMeter timeMeter;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void refillBuckets() {
        timeMeter.advance(Duration.ofMinutes(5));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE TABLE events, incidents, projects");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void projectQuotaRejectsWithoutPersistingOrChargingAnotherProject(
            CapturedOutput output) throws Exception {

        ProjectCredential a = insertProject("Project A");
        ProjectCredential b = insertProject("Project B");

        double initialGlobalRejections = rejectionCount("global", "none");

        String canary = "CANARY_PROJECT_LIMIT_6f8";
        String body = validBody("password=" + canary);

        assertThat(post(a.ingestKey().toString(), body).statusCode())
                .isEqualTo(202);

        assertThat(rejectionCount("project", a.projectId().toString()))
                .isZero();

        HttpResponse<String> rejected =
                post(a.ingestKey().toString(), body);

        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejectionCount("project", a.projectId().toString()))
                .isEqualTo(1.0);
        assertThat(rejected.body()).isEmpty();
        assertThat(rejected.headers().firstValue("Retry-After"))
                .hasValue("3");
        assertThat(rejected.headers().firstValue("Content-Type"))
                .isEmpty();

        // A's exhausted quota must not consume B's first token.
        assertThat(post(b.ingestKey().toString(), validBody("B event"))
                        .statusCode())
                .isEqualTo(202);
        assertThat(rejectionCount("project", b.projectId().toString()))
                .isZero();

        assertThat(rejectionCount("global", "none"))
                .isEqualTo(initialGlobalRejections);
        assertThat(count("incidents", a.projectId())).isEqualTo(1L);
        assertThat(count("events", a.projectId())).isEqualTo(1L);
        assertThat(count("incidents", b.projectId())).isEqualTo(1L);
        assertThat(count("events", b.projectId())).isEqualTo(1L);

        meterRegistry.find(IngestRejectionMetrics.NAME)
                .meters()
                .forEach(
                        meter -> {
                            Meter.Id id = meter.getId();

                            assertThat(id.getTag("reason"))
                                    .isIn("global", "project");

                            assertThat(id.getTag("project_id"))
                                    .isNotNull();

                            assertThat(id.toString())
                                    .doesNotContain(canary)
                                    .doesNotContain(a.ingestKey().toString())
                                    .doesNotContain(b.ingestKey().toString());
                        });

        assertThat(output.getAll())
                .doesNotContain(canary)
                .doesNotContain(a.ingestKey().toString())
                .doesNotContain(b.ingestKey().toString());
    }

    @Test
    void invalidRequestsDoNotSpendProjectTokens() throws Exception {
        ProjectCredential project = insertProject("Project A");

        assertThat(post(
                        project.ingestKey().toString(),
                        "{ not json").statusCode())
                .isEqualTo(400);

        assertThat(post(
                        project.ingestKey().toString(),
                        """
                        {"message": "Missing occurredAt"}
                        """).statusCode())
                .isEqualTo(400);

        assertThat(post(
                        UUID.randomUUID().toString(),
                        validBody("Unknown key")).statusCode())
                .isEqualTo(401);

        assertThat(post(
                        project.ingestKey().toString(),
                        validBody("First valid event")).statusCode())
                .isEqualTo(202);

        // Invalid requests and successful admission must not count as rejections.
        assertThat(rejectionCount("project", project.projectId().toString()))
                .isZero();

        assertThat(post(
                        project.ingestKey().toString(),
                        validBody("Second valid event")).statusCode())
                .isEqualTo(429);

        assertThat(rejectionCount("project", project.projectId().toString()))
                .isEqualTo(1.0);
        assertThat(count("events", project.projectId())).isEqualTo(1L);
        assertThat(count("incidents", project.projectId())).isEqualTo(1L);
    }

    @Test
    void retryAfterRoundsUpAndQuotaRefills() throws Exception {
        ProjectCredential project = insertProject("Project A");
        String key = project.ingestKey().toString();
        String body = validBody("Repeatable failure");

        assertThat(post(key, body).statusCode()).isEqualTo(202);

        HttpResponse<String> firstRejection = post(key, body);
        assertThat(firstRejection.statusCode()).isEqualTo(429);
        assertThat(firstRejection.headers().firstValue("Retry-After"))
                .hasValue("3");

        timeMeter.advance(Duration.ofMillis(1500));

        HttpResponse<String> partialRefill = post(key, body);
        assertThat(partialRefill.statusCode()).isEqualTo(429);
        assertThat(partialRefill.headers().firstValue("Retry-After"))
                .hasValue("2");

        assertThat(count("events", project.projectId())).isEqualTo(1L);

        timeMeter.advance(Duration.ofMillis(1500));

        assertThat(post(key, body).statusCode()).isEqualTo(202);

        assertThat(count("events", project.projectId())).isEqualTo(2L);
        assertThat(count("incidents", project.projectId())).isEqualTo(1L);
    }

    private double rejectionCount(String reason, String projectId) {
        Counter counter =
                meterRegistry.find(IngestRejectionMetrics.NAME)
                        .tags(
                                "reason", reason,
                                "project_id", projectId)
                        .counter();

        return counter == null ? 0.0 : counter.count();
    }

    private HttpResponse<String> post(String ingestKey, String body)
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

    private long count(String table, UUID projectId) {
        Long value =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE project_id = ?",
                        Long.class,
                        projectId);

        return value;
    }

    private static String validBody(String message) {
        return """
                {
                  "occurredAt": "2026-09-05T20:00:00Z",
                  "message": "%s"
                }
                """
                .formatted(message);
    }

    private record ProjectCredential(UUID projectId, UUID ingestKey) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class AdjustableTimeConfiguration {

        @Bean
        @Primary
        AdjustableTimeMeter adjustableTimeMeter() {
            return new AdjustableTimeMeter();
        }
    }

    static final class AdjustableTimeMeter implements TimeMeter {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
