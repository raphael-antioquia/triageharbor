package com.triageharbor.event;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "triageharbor.ingest.rate-limit.project")
record PerProjectIngestRateLimitProperties(
        @Positive long capacity,
        @Positive long refillTokensPerMinute) {}
