
package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fingerprint-v2 redaction golden vectors. A change in scrubbed fingerprint inputs
 * requires fingerprint-version review; do not simply update the expected strings.
 */
class SensitiveDataRedactorTests {

    private static final String GOLDEN_INGEST_TOKEN =
            "th_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @Test
    void goldenVectorsPinOrderAndSecretRemoval() {
        List<Golden> vectors =
                List.of(
                        new Golden(
                                "URL and assignment overlap",
                                "postgres://user:token=CANARY_URL_5d21e0@db/x",
                                "postgres://user:[REDACTED]@db/x",
                                "CANARY_URL_5d21e0"),
                        new Golden(
                                "message assignment",
                                "user=bob password=CANARY_MSG_7f3a91,tail&more",
                                "user=bob password=[REDACTED]",
                                "CANARY_MSG_7f3a91"),
                        new Golden(
                                "stack trace caused by",
                                "Caused by: Authorization: Bearer CANARY_TRACE_c2e8d4",
                                "Caused by: Authorization: [REDACTED]",
                                "CANARY_TRACE_c2e8d4"),
                        new Golden(
                                "exception type",
                                "clientSecret=CANARY_TYPE_91b0aa",
                                "clientSecret=[REDACTED]",
                                "CANARY_TYPE_91b0aa"),
                        new Golden(
                                "JWT inside a benign assignment",
                                "reference=eyJCANARY_MSG_7f3a91.eyJpayload123.signature",
                                "reference=[REDACTED]",
                                "CANARY_MSG_7f3a91"),
                        new Golden(
                                "URL inside sensitive assignment",
                                "token=postgres://user:CANARY_URL_5d21e0@db/x",
                                "token=[REDACTED]",
                                "CANARY_URL_5d21e0"),
                        new Golden(
                                "standalone ingest credential",
                                "failure reported " + GOLDEN_INGEST_TOKEN,
                                "failure reported [REDACTED]",
                                GOLDEN_INGEST_TOKEN),
                        new Golden(
                                "ingest credential assignment",
                                "ingestKey=" + GOLDEN_INGEST_TOKEN,
                                "ingestKey=[REDACTED]",
                                GOLDEN_INGEST_TOKEN),
                        new Golden(
                                "ingest credential header",
                                "X-TriageHarbor-Ingest-Key: " + GOLDEN_INGEST_TOKEN,
                                "X-TriageHarbor-Ingest-Key: [REDACTED]",
                                GOLDEN_INGEST_TOKEN),
                        new Golden(
                                "32-character credential minimum",
                                "th_" + "A".repeat(32),
                                "[REDACTED]",
                                "A".repeat(32)),
                        new Golden(
                                "benign assignment containing credential",
                                "reference=" + GOLDEN_INGEST_TOKEN,
                                "reference=[REDACTED]",
                                GOLDEN_INGEST_TOKEN),
                        new Golden(
                                "right-glued suffix is consumed",
                                "context=" + GOLDEN_INGEST_TOKEN + "suffix",
                                "context=[REDACTED]",
                                GOLDEN_INGEST_TOKEN),
                        new Golden(
                                "uppercase at last window position",
                                "th_" + "a".repeat(42) + "G",
                                "[REDACTED]",
                                "a".repeat(42) + "G"),
                        new Golden(
                                "left-glued credential",
                                "Bearer" + GOLDEN_INGEST_TOKEN,
                                "Bearer[REDACTED]",
                                GOLDEN_INGEST_TOKEN),
                        new Golden(
                                "JSON string field",
                                "{\"note\":\"" + GOLDEN_INGEST_TOKEN + "\"}",
                                "{\"note\":\"[REDACTED]\"}",
                                GOLDEN_INGEST_TOKEN),
                        new Golden(
                                "URL query parameter",
                                "https://hooks.example/x?ref=" + GOLDEN_INGEST_TOKEN,
                                "https://hooks.example/x?ref=[REDACTED]",
                                GOLDEN_INGEST_TOKEN),
                        new Golden(
                                "left-glued detection accepts identifier false positive",
                                "test_health_CheckFailsWhenPrimaryReplicaIsDown",
                                "test_heal[REDACTED]",
                                "CheckFailsWhenPrimaryReplicaIsDown"));

        for (Golden vector : vectors) {
            String sanitized = SensitiveDataRedactor.redact(vector.raw());
            assertThat(sanitized).as(vector.name()).isEqualTo(vector.expected());
            assertThat(sanitized).as(vector.name()).doesNotContain(vector.canary());
            assertThat(SensitiveDataRedactor.redact(sanitized))
                    .as(vector.name() + " idempotence")
                    .isEqualTo(sanitized);
        }
    }

    @Test
    void urlUserinfoUsesLastAtAndDoesNotStopAtQuestionOrHash() {
        assertThat(
                        SensitiveDataRedactor.redact(
                                "postgres://app:sec?ret#part@db:5432/x"))
                .isEqualTo("postgres://app:[REDACTED]@db:5432/x");

        assertThat(
                        SensitiveDataRedactor.redact(
                                "postgres://user:abc@def@db/x"))
                .isEqualTo("postgres://user:[REDACTED]@db/x");
    }

    @Test
    void urlUserinfoWithEmptyUsernameOrHostIsRedacted() {
        assertThat(SensitiveDataRedactor.redact("redis://:CANARY_URL_5d21e0@cache:6379"))
                .isEqualTo("redis://:[REDACTED]@cache:6379");
        assertThat(SensitiveDataRedactor.redact("postgresql://app:CANARY_URL_5d21e0@/db"))
                .isEqualTo("postgresql://app:[REDACTED]@/db");
    }

    @Test
    void urlsWithoutPasswordsAreUnchanged() {
        for (String url :
                List.of(
                        "https://user@host/x",
                        "https://host:8443/a@b",
                        "jdbc:postgresql://db:5432/app")) {
            assertThat(SensitiveDataRedactor.redact(url)).as(url).isEqualTo(url);
        }
    }

    @Test
    void rawSlashInMalformedUrlPasswordIsNotRecognized() {
        String malformed = "postgres://user:sec/ret@db/x";
        // Explicitly pinned limitation, not a safety guarantee for malformed URLs.
        assertThat(SensitiveDataRedactor.redact(malformed)).isEqualTo(malformed);
    }

    @Test
    void jwtSignatureMayBeEmptyAndOrdinaryDottedTextIsPreserved() {
        assertThat(
                        SensitiveDataRedactor.redact(
                                "eyJabcdefgh.eyJpayload123."))
                .isEqualTo("[REDACTED]");
        assertThat(SensitiveDataRedactor.redact("version=1.2.3 Some.Class.Name"))
                .isEqualTo("version=1.2.3 Some.Class.Name");
    }

    @Test
    void sensitiveIdentifiersUseSegmentsAndAdjacentPairs() {
        for (String key :
                List.of(
                        "DB_PASSWORD",
                        "accessToken",
                        "clientSecret",
                        "X-Api-Key",
                        "Authorization",
                        "Set-Cookie",
                        "apiKey",
                        "private.key",
                        "sessionId",
                        "sessionid",
                        "credentials",
                        "ingestKey",
                        "ingest_key",
                        "X-TriageHarbor-Ingest-Key")) {
            assertThat(SensitiveDataRedactor.redact(key + "=synthetic"))
                    .as(key)
                    .isEqualTo(key + "=[REDACTED]");
        }
    }

    @Test
    void benignIdentifiersDoNotRedact() {
        for (String key :
                List.of(
                        "key",
                        "cache_key",
                        "tokenization",
                        "oauth",
                        "apiName",
                        "ingest",
                        "ingestId",
                        "keyIngest")) {
            assertThat(SensitiveDataRedactor.redact(key + "=synthetic"))
                    .as(key)
                    .isEqualTo(key + "=synthetic");
        }
    }

    @Test
    void headersAndQuotedValuesAreScrubbed() {
        assertThat(SensitiveDataRedactor.redact("Authorization: Bearer abc123"))
                .isEqualTo("Authorization: [REDACTED]");
        assertThat(SensitiveDataRedactor.redact("Cookie: SID=x; theme=dark"))
                .isEqualTo("Cookie: [REDACTED]");
        assertThat(SensitiveDataRedactor.redact("Set-Cookie: SID=x; HttpOnly"))
                .isEqualTo("Set-Cookie: [REDACTED]");
        assertThat(SensitiveDataRedactor.redact("\"authorization\":\"Bearer abc123\""))
                .isEqualTo("\"authorization\":\"[REDACTED]\"");
        assertThat(SensitiveDataRedactor.redact("\"token\":\"a\\\"b\""))
                .isEqualTo("\"token\":\"[REDACTED]\"");
        assertThat(SensitiveDataRedactor.redact("token='a\\'b'"))
                .isEqualTo("token='[REDACTED]'");
    }

    @Test
    void unterminatedQuoteConsumesRestOfLine() {
        assertThat(SensitiveDataRedactor.redact("password=\"secret\nstatus=ok"))
                .isEqualTo("password=[REDACTED]\nstatus=ok");
    }

    @Test
    void nonSensitiveAssignmentDoesNotHideEmbeddedSecret() {
        assertThat(SensitiveDataRedactor.redact("user=bob password=hunter2"))
                .isEqualTo("user=bob password=[REDACTED]");
        assertThat(SensitiveDataRedactor.redact("\"msg\":\"password=CANARY_MSG_7f3a91\""))
                .isEqualTo("\"msg\":\"password=[REDACTED]");
    }

    @Test
    void separatorsUseSpacesOrTabsAndValuesStopAtCrOrLf() {
        assertThat(SensitiveDataRedactor.redact("token\t=\tx,y\r\nstatus=ok"))
                .isEqualTo("token\t=\t[REDACTED]\r\nstatus=ok");
        assertThat(SensitiveDataRedactor.redact("'token': 'x'"))
                .isEqualTo("'token': '[REDACTED]'");
        // Assignments never span lines.
        assertThat(SensitiveDataRedactor.redact("token\n=value"))
                .isEqualTo("token\n=value");
    }

    @Test
    void dottedClassNameKeysFollowSegmentRules() {
        // Accepted v1 consequence: class names used as keys are split on dots.
        assertThat(
                        SensitiveDataRedactor.redact(
                                "com.example.auth.TokenExpiredException: expired for job 42"))
                .isEqualTo("com.example.auth.TokenExpiredException: [REDACTED]");
        assertThat(
                        SensitiveDataRedactor.redact(
                                "\tat com.example.TokenFilter.check(TokenFilter.java:42)"))
                .isEqualTo("\tat com.example.TokenFilter.check(TokenFilter.java:[REDACTED]");
    }

    @Test
    void sensitiveQuotedValuesAcrossLinesRedactIndependently() {
        assertThat(
                        SensitiveDataRedactor.redact(
                                "\"token\":\"a\",\"secret\":\"b\"\n\"password\":\"c\" ok\r\ntoken=d"))
                .isEqualTo(
                        "\"token\":\"[REDACTED]\",\"secret\":\"[REDACTED]\"\n"
                                + "\"password\":\"[REDACTED]\" ok\r\ntoken=[REDACTED]");
    }

    @Test
    void manySensitiveQuotedValuesOnOneLineStayBounded() {
        // Single quotes need no JSON escaping, so this size is reachable via HTTP.
        String pair = "'token':'x',";
        int pairs = (EventRequestSizeFilter.MAX_REQUEST_BYTES - 1_024) / pair.length();
        String raw = pair.repeat(pairs);
        String expected = "'token':'[REDACTED]',".repeat(pairs);

        assertTimeout(
                Duration.ofSeconds(1),
                () -> assertThat(SensitiveDataRedactor.redact(raw)).isEqualTo(expected));
    }

    @Test
    void proseFalsePositivesIntentionallyMerge() {
        assertThat(SensitiveDataRedactor.redact("Invalid token: expired"))
                .isEqualTo("Invalid token: [REDACTED]");
        assertThat(SensitiveDataRedactor.redact("Invalid token: malformed"))
                .isEqualTo("Invalid token: [REDACTED]");
    }

    @Test
    void alreadyRedactedAndEmptyInputsRemainUnchanged() {
        assertThat(SensitiveDataRedactor.redact(null)).isNull();
        assertThat(SensitiveDataRedactor.redact("")).isEmpty();
        assertThat(SensitiveDataRedactor.redact("[REDACTED]"))
                .isEqualTo("[REDACTED]");
        assertThat(SensitiveDataRedactor.redact("token=[REDACTED]"))
                .isEqualTo("token=[REDACTED]");
    }

    @Test
    void longAndNearMissInputsAreBoundedInOrdinaryExecution() {
        assertTimeout(
                Duration.ofSeconds(5),
                () -> {
                    String longKey = "a".repeat(EventRequestSizeFilter.MAX_REQUEST_BYTES);
                    String nearMiss = longKey + "=hello";
                    String backslashes = "\\".repeat(EventRequestSizeFilter.MAX_REQUEST_BYTES);
                    String secret = "token=\"" + backslashes + "synthetic\"";
                    String jwt = "eyJ" + "a".repeat(EventRequestSizeFilter.MAX_REQUEST_BYTES) + ".eyJpayload000.signature";
                    String longQuoted = "password=\"" + "x".repeat(EventRequestSizeFilter.MAX_REQUEST_BYTES) + "\"";

                    assertThat(SensitiveDataRedactor.redact(longKey)).isEqualTo(longKey);
                    assertThat(SensitiveDataRedactor.redact(nearMiss)).isEqualTo(nearMiss);
                    assertThat(SensitiveDataRedactor.redact(secret))
                            .isEqualTo("token=\"[REDACTED]\"");
                    assertThat(SensitiveDataRedactor.redact(jwt)).isEqualTo("[REDACTED]");
                    assertThat(SensitiveDataRedactor.redact(longQuoted))
                            .isEqualTo("password=\"[REDACTED]\"");
                });
    }

    @Test
    void credentialHeuristicKnownNearMissesArePinned() {
        String tooShort = "th_" + "A".repeat(31);
        String lowercaseOnly = "th_" + "a".repeat(42) + "g";
        String uppercaseOutsideWindow = "th_" + "a".repeat(43) + "G";

        for (String input :
                List.of(tooShort, lowercaseOnly, uppercaseOutsideWindow)) {
            assertThat(SensitiveDataRedactor.redact(input))
                    .as(input)
                    .isEqualTo(input);
        }
    }

    @Test
    void repeatedCredentialNearMissesRemainBounded() {
        for (String fragment : List.of("th_th_", "th_ath_a")) {
            String input =
                    fragment.repeat(
                            EventRequestSizeFilter.MAX_REQUEST_BYTES
                                    / fragment.length());

            assertTimeout(
                    Duration.ofSeconds(2),
                    () -> assertThat(SensitiveDataRedactor.redact(input))
                            .as(fragment)
                            .isEqualTo(input));
        }
    }

    @Test
    void fullEventRedactionWithAdversarialFieldsRemainsBounded() {
        String token =
                "th_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

        String exceptionType = "SyntheticError " + token;
        String message =
                "th_th_".repeat(10_000) + " password=CANARY_MSG_7f3a91";
        String stackTrace =
                "th_ath_a".repeat(10_000)
                        + " postgres://user:CANARY_URL_5d21e0@db/x";

        // Conservative allowance for JSON property names and punctuation.
        assertThat(
                        exceptionType.length()
                                + message.length()
                                + stackTrace.length()
                                + 512)
                .isLessThan(EventRequestSizeFilter.MAX_REQUEST_BYTES);

        assertTimeout(
                Duration.ofSeconds(2),
                () -> {
                    RedactedEvent event =
                            RedactedEvent.from(
                                    new EventIngestRequest(
                                            Instant.parse("2026-01-01T10:00:00Z"),
                                            exceptionType,
                                            message,
                                            stackTrace));

                    assertThat(event.exceptionType())
                            .isEqualTo("SyntheticError [REDACTED]");

                    assertThat(event.message())
                            .isEqualTo(
                                    "th_th_".repeat(10_000)
                                            + " password=[REDACTED]");

                    assertThat(event.stackTrace())
                            .isEqualTo(
                                    "th_ath_a".repeat(10_000)
                                            + " postgres://user:[REDACTED]@db/x");
                });
    }

    private record Golden(String name, String raw, String expected, String canary) {}
}
