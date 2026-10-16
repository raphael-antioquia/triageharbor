package com.triageharbor.event;

import java.time.Instant;

final class RedactedEvent {

    private final Instant occurredAt;
    private final String exceptionType;
    private final String message;
    private final String stackTrace;

    private RedactedEvent(
            Instant occurredAt,
            String exceptionType,
            String message,
            String stackTrace) {
        this.occurredAt = occurredAt;
        this.exceptionType = exceptionType;
        this.message = message;
        this.stackTrace = stackTrace;
    }

    static RedactedEvent from(EventIngestRequest request) {
        return new RedactedEvent(
                request.occurredAt(),
                SensitiveDataRedactor.redact(request.exceptionType()),
                SensitiveDataRedactor.redact(request.message()),
                SensitiveDataRedactor.redact(request.stackTrace()));
    }

    Instant occurredAt() {
        return occurredAt;
    }

    String exceptionType() {
        return exceptionType;
    }

    String message() {
        return message;
    }

    String stackTrace() {
        return stackTrace;
    }
}
