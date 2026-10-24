package com.triageharbor.event;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

final class GlobalIngestRateLimitFilter extends OncePerRequestFilter {

    private final Bucket bucket;

    GlobalIngestRateLimitFilter(
            GlobalIngestRateLimitProperties properties,
            TimeMeter timeMeter) {

        this.bucket =
                Bucket.builder()
                        .withCustomTimePrecision(timeMeter)
                        .addLimit(
                                limit ->
                                        limit.capacity(properties.capacity())
                                                .refillGreedy(
                                                        properties.refillTokensPerMinute(),
                                                        Duration.ofMinutes(1)))
                        .build();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(
                HttpHeaders.RETRY_AFTER,
                Long.toString(IngestRetryAfter.seconds(probe)));
    }
}
