package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.triageharbor.support.PostgresTestConfiguration;
import io.github.bucket4j.TimeMeter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "triageharbor.ingest.rate-limit.global.capacity=2",
            "triageharbor.ingest.rate-limit.global.refill-tokens-per-minute=1"
        })
@Import({
    PostgresTestConfiguration.class,
    GlobalIngestRateLimitPathHttpTests.AdjustableTimeConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GlobalIngestRateLimitPathHttpTests {

    // Capacity 2 keeps every row non-vacuous: an unrefilled bucket fails.
    private static final int CAPACITY = 2;

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    // Valid body, no ingest key: a routed request stops at 401 before DB access.
    private static final String VALID_BODY =
            """
            {
              "occurredAt": "2026-09-05T20:00:00Z",
              "message": "Path admission probe"
            }
            """;

    @LocalServerPort
    private int port;

    @Autowired
    private AdjustableTimeMeter timeMeter;

    @ParameterizedTest(name = "{0} -> {1}, charged={2}")
    @CsvSource({
        "/events?probe=1, 401, true",
        "/even%74s,       401, true",
        "/events;probe=1, 401, true",
        "/events/,        404, false",
        "/events-extra,   404, false",
        "//events,        404, true"
    })
    void pathVariantRoutingAndCharging(
            String path, int expectedStatus, boolean charged) throws Exception {

        // Refill to capacity; the fake monotonic clock only moves forward.
        timeMeter.advance(Duration.ofMinutes(5));

        int status = send(path).statusCode();
        int remaining = canonicalAdmissionsUntilExhausted();

        assertSoftly(
                softly -> {
                    softly.assertThat(status)
                            .as("routing result for %s", path)
                            .isEqualTo(expectedStatus);

                    softly.assertThat(remaining)
                            .as("canonical admissions left after %s", path)
                            .isEqualTo(charged ? CAPACITY - 1 : CAPACITY);

                    // Security invariant: reaching the controller always costs a token.
                    if (status == 401) {
                        softly.assertThat(remaining)
                                .as("%s reached ingestion without spending a token", path)
                                .isEqualTo(CAPACITY - 1);
                    }
                });
    }

    @ParameterizedTest(name = "{0} /events -> {1}, charged once")
    @CsvSource({
        "GET,     405",
        "HEAD,    405",
        "OPTIONS, 200",
        "PUT,     405",
        "PATCH,   405",
        "DELETE,  405"
    })
    void everyMethodOnEventsSpendsExactlyOneToken(String method, int expectedStatus)
            throws Exception {

        timeMeter.advance(Duration.ofMinutes(5));

        int status = sendWithoutBody(method, "/events").statusCode();
        int remaining = canonicalAdmissionsUntilExhausted();

        assertSoftly(
                softly -> {
                    softly.assertThat(status)
                            .as("status for %s /events", method)
                            .isEqualTo(expectedStatus);

                    // 2 left = unmetered; 0 left = charged twice.
                    softly.assertThat(remaining)
                            .as("%s /events must spend exactly one token", method)
                            .isEqualTo(CAPACITY - 1);
                });
    }

    private int canonicalAdmissionsUntilExhausted() throws Exception {
        for (int admitted = 0; admitted <= CAPACITY; admitted++) {
            int status = send("/events").statusCode();

            if (status == 429) {
                return admitted;
            }

            assertThat(status).as("canonical /events status").isEqualTo(401);
        }

        throw new AssertionError("Canonical /events was never rate limited");
    }

    private HttpResponse<Void> send(String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(VALID_BODY))
                        .build();

        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private HttpResponse<Void> sendWithoutBody(String method, String path)
            throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .method(method, BodyPublishers.noBody())
                        .build();

        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AdjustableTimeConfiguration {

        @Bean
        @Primary
        AdjustableTimeMeter adjustableIngestTimeMeter() {
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
