package com.triageharbor.event;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class PerProjectIngestRateLimiter {

    private final ConcurrentMap<UUID, Bucket> buckets =
            new ConcurrentHashMap<>();

    private final PerProjectIngestRateLimitProperties properties;
    private final TimeMeter timeMeter;

    PerProjectIngestRateLimiter(
            PerProjectIngestRateLimitProperties properties,
            TimeMeter timeMeter) {
        this.properties = properties;
        this.timeMeter = timeMeter;
    }

    ConsumptionProbe tryConsume(AuthenticatedProject project) {
        return buckets
                .computeIfAbsent(project.id(), ignored -> createBucket())
                .tryConsumeAndReturnRemaining(1);
    }

    private Bucket createBucket() {
        return Bucket.builder()
                .withCustomTimePrecision(timeMeter)
                .addLimit(
                        limit ->
                                limit.capacity(properties.capacity())
                                        .refillGreedy(
                                                properties.refillTokensPerMinute(),
                                                Duration.ofMinutes(1)))
                .build();
    }
}
