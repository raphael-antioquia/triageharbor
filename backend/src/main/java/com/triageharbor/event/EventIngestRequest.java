package com.triageharbor.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventIngestRequest(
        @NotNull Instant occurredAt,
        String exceptionType,
        @NotNull String message,
        String stackTrace) {}
