package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

@JsonTest
class EventIngestRequestJsonTests {

    @Autowired
    private JacksonTester<EventIngestRequest> json;

    @Test
    void unknownFieldsAreIgnored() throws Exception {
        String content =
                """
                {
                  "occurredAt": "2026-09-04T22:30:00Z",
                  "message": "Something failed",
                  "projectId": "00000000-0000-0000-0000-000000000001"
                }
                """;

        EventIngestRequest request = json.parseObject(content);

        assertThat(request.occurredAt())
                .isEqualTo(Instant.parse("2026-09-04T22:30:00Z"));
        assertThat(request.message()).isEqualTo("Something failed");
    }

    @Test
    void missingOccurredAtDeserializesAsNull() throws Exception {
        String content =
                """
                {
                  "message": "Something failed"
                }
                """;

        EventIngestRequest request = json.parseObject(content);

        assertThat(request.occurredAt()).isNull();
    }
}
