package com.triageharbor.incident;

import java.time.Instant;
import java.util.UUID;

public record IncidentSummary(
        UUID id,
        String title,
        String status,
        long occurrenceCount,
        Instant firstSeenAt,
        Instant lastSeenAt) {}
