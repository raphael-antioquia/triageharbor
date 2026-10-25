package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.triageharbor.support.PostgresTestConfiguration;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
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
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "triageharbor.ingest.rate-limit.global.capacity=1",
            "triageharbor.ingest.rate-limit.global.refill-tokens-per-minute=1"
        })
@Import({
    PostgresTestConfiguration.class,
    GlobalIngestRateLimitHttpTests.FrozenTimeConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class GlobalIngestRateLimitHttpTests {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final String BODY_CANARY = "rate-limit-body-canary-7f3a";

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meterRegistry;

    // Keep this class to one test: its single token is spent and time never advances.
    @Test
    void globalAdmissionChargesEventsOnlyAndPrecedesSizeCheck(CapturedOutput output)
            throws Exception {
        double initialGlobalRejections = globalRejectionCount();

        // Dashboard reads must not spend the only token.
        assertThat(send(dashboardRequest()).statusCode()).isEqualTo(404);

        HttpResponse<byte[]> options =
                send(
                        HttpRequest.newBuilder(uri("/events"))
                                .method("OPTIONS", BodyPublishers.noBody())
                                .build());

        assertThat(options.statusCode()).isEqualTo(200);

        // Admitted requests spend tokens but are not rejections.
        assertThat(globalRejectionCount()).isEqualTo(initialGlobalRejections);

        // Global 429 must win over the size filter's 413.
        String ingestKey = UUID.randomUUID().toString();
        HttpResponse<byte[]> rejected = send(oversizedPost(ingestKey));

        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.body()).isEmpty();
        assertThat(rejected.headers().firstValue("Retry-After")).hasValue("60");
        assertThat(rejected.headers().firstValue("Content-Type")).isEmpty();
        assertThat(globalRejectionCount())
                .isEqualTo(initialGlobalRejections + 1.0);

        // Exhaustion must not spill over to dashboard routes.
        assertThat(send(dashboardRequest()).statusCode()).isEqualTo(404);

        assertThat(output).doesNotContain(ingestKey).doesNotContain(BODY_CANARY);
    }

    private double globalRejectionCount() {
        Counter counter =
                meterRegistry.find(IngestRejectionMetrics.NAME)
                        .tags("reason", "global", "project_id", "none")
                        .counter();

        return counter == null ? 0.0 : counter.count();
    }

    private HttpRequest oversizedPost(String ingestKey) {
        byte[] body = new byte[EventRequestSizeFilter.MAX_REQUEST_BYTES + 1];
        Arrays.fill(body, (byte) 'x');

        byte[] canary = BODY_CANARY.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(canary, 0, body, 0, canary.length);

        return HttpRequest.newBuilder(uri("/events"))
                .header("Content-Type", "application/json")
                .header(EventIngestController.INGEST_KEY_HEADER, ingestKey)
                .POST(BodyPublishers.ofByteArray(body))
                .build();
    }

    private HttpRequest dashboardRequest() {
        return HttpRequest.newBuilder(
                        uri("/projects/" + UUID.randomUUID() + "/incidents"))
                .GET()
                .build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<byte[]> send(HttpRequest request) throws Exception {
        return HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FrozenTimeConfiguration {

        @Bean
        @Primary
        TimeMeter frozenIngestTimeMeter() {
            return new TimeMeter() {

                @Override
                public long currentTimeNanos() {
                    return 0L;
                }

                @Override
                public boolean isWallClockBased() {
                    return false;
                }
            };
        }
    }
}
