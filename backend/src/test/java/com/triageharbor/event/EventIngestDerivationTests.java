package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pinned fingerprint vectors were derived independently from the encoding specification.
 * Do not regenerate them from the implementation: changing an established vector requires
 * a new fingerprint version.
 */
class EventIngestDerivationTests {

    @Test
    void fingerprintMatchesKnownVector() {
        String fingerprint =
                EventFingerprintV1.compute(
                        "java.lang.IllegalStateException",
                        "Something failed");

        assertThat(fingerprint)
                .isEqualTo(
                        "cfe87783a52a39077cb4f611b282fdb2bfe88a34f00b2ab9d4749e2d64315df8");
    }

    @Test
    void fingerprintNullFieldEncodingIsPinned() {
        assertThat(EventFingerprintV1.compute(null, ""))
                .isEqualTo(
                        "fba92a78af0187782e6a8158d1f37d76104443e5af612986596d5b66653af02d");
    }

    @Test
    void fingerprintDistinguishesNullFromEmptyString() {
        String nullExceptionType = EventFingerprintV1.compute(null, "");
        String emptyExceptionType = EventFingerprintV1.compute("", "");

        assertThat(nullExceptionType).isNotEqualTo(emptyExceptionType);
    }

    @Test
    void fingerprintLengthPrefixUsesUtf8ByteLength() {
        String fingerprint = EventFingerprintV1.compute("é", "🙂");

        assertThat(fingerprint)
                .isEqualTo(
                        "14bcc6620578703af702a450f21a795922415a54a4bbf78f607ac36c692ae64e");
    }

    @Test
    void fingerprintEncodingAvoidsFieldBoundaryAmbiguity() {
        String first = EventFingerprintV1.compute("a", "b:c");
        String second = EventFingerprintV1.compute("a:b", "c");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void fingerprintIsLowercaseHexOfFixedLength() {
        assertThat(EventFingerprintV1.compute("a", "b"))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void titlePrefersNonBlankExceptionType() {
        assertThat(
                        IncidentTitle.derive(
                                "java.lang.IllegalStateException",
                                "Something failed"))
                .isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void titleFallsBackToMessageWhenExceptionTypeIsAbsent() {
        assertThat(IncidentTitle.derive(null, "Something failed"))
                .isEqualTo("Something failed");
    }

    @Test
    void titleFallsBackToMessageWhenExceptionTypeIsBlank() {
        assertThat(IncidentTitle.derive("   ", "Something failed"))
                .isEqualTo("Something failed");
    }

    @Test
    void titleTruncatesAtUnicodeCodePointBoundary() {
        String source = "a".repeat(199) + "🙂" + "trailing";

        String title = IncidentTitle.derive(source, "fallback");

        assertThat(title.codePointCount(0, title.length())).isEqualTo(200);
        assertThat(title).isEqualTo("a".repeat(199) + "🙂");
    }
}
