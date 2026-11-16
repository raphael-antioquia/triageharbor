package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.triageharbor.support.PostgresTestConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.CannotCreateTransactionException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class EventIngestFailureHandlerTests {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @MockitoBean
    private IngestCredentialResolver ingestCredentialResolver;

    @MockitoBean
    private EventIngestService eventIngestService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void resolverDatabaseFailureReturns500(CapturedOutput output)
            throws Exception {
        String ingestKey = "th_" + "A".repeat(43);
        String canary = "CANARY_RESOLVER_DB_7a91";

        when(ingestCredentialResolver.resolve(ingestKey))
                .thenThrow(new DataAccessResourceFailureException(
                        "Database lookup failed: " + canary));

        HttpResponse<String> response = postEvents(ingestKey);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEmpty();

        verifyNoInteractions(eventIngestService);

        assertThat(output.getAll())
                .contains("Database failure during POST /events: DataAccessResourceFailureException")
                .doesNotContain(canary)
                .doesNotContain(ingestKey);
    }

    @Test
    void writerDatabaseFailureReturns500(CapturedOutput output)
            throws Exception {
        String ingestKey = "th_" + "A".repeat(43);
        String canary = "CANARY_WRITER_DB_84c2";

        UUID projectId = UUID.randomUUID();

        when(ingestCredentialResolver.resolve(ingestKey))
                .thenReturn(new AuthenticatedProject(projectId));

        doThrow(new DataIntegrityViolationException(
                        "Database write failed: " + canary))
                .when(eventIngestService)
                .ingest(any(AuthenticatedProject.class),
                        any(EventIngestRequest.class));

        double before = projectRejectionCount(projectId);

        HttpResponse<String> response = postEvents(ingestKey);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(projectRejectionCount(projectId))
                .isEqualTo(before);
        assertThat(response.body()).isEmpty();

        assertThat(output.getAll())
                .contains("Database failure during POST /events: DataIntegrityViolationException")
                .doesNotContain(canary)
                .doesNotContain(ingestKey);
    }

    @Test
    void transactionFailureDoesNotLeakMessageOrCause(CapturedOutput output)
            throws Exception {
        String ingestKey = "th_" + "A".repeat(43);

        String messageCanary = "CANARY_TX_MESSAGE_38fa";
        String causeCanary = "CANARY_TX_CAUSE_92bd";

        when(ingestCredentialResolver.resolve(ingestKey))
                .thenReturn(new AuthenticatedProject(UUID.randomUUID()));

        CannotCreateTransactionException failure =
                new CannotCreateTransactionException(
                        "Transaction start failed: " + messageCanary,
                        new IllegalStateException(
                                "Nested driver detail: " + causeCanary));

        doThrow(failure)
                .when(eventIngestService)
                .ingest(any(AuthenticatedProject.class),
                        any(EventIngestRequest.class));

        HttpResponse<String> response = postEvents(ingestKey);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body())
                .doesNotContain(messageCanary)
                .doesNotContain(causeCanary)
                .doesNotContain(ingestKey)
                .isEmpty();

        assertThat(output.getAll())
                .contains("Database failure during POST /events: CannotCreateTransactionException")
                .doesNotContain(messageCanary)
                .doesNotContain(causeCanary)
                .doesNotContain(ingestKey);
    }

    private double projectRejectionCount(UUID projectId) {
        Counter counter =
                meterRegistry.find(IngestRejectionMetrics.NAME)
                        .tags(
                                "reason", "project",
                                "project_id", projectId.toString())
                        .counter();

        return counter == null ? 0.0 : counter.count();
    }

    private HttpResponse<String> postEvents(String ingestKey)
            throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(
                                "http://localhost:" + port + "/events"))
                        .header("Content-Type", "application/json")
                        .header(
                                EventIngestController.INGEST_KEY_HEADER,
                                ingestKey)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                """
                                {
                                  "occurredAt": "2026-09-05T20:00:00Z",
                                  "message": "Something failed"
                                }
                                """,
                                StandardCharsets.UTF_8))
                        .build();

        return HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
