package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

class PerProjectIngestRateLimitConfigurationTests {

    private static final String PREFIX =
            "triageharbor.ingest.rate-limit.project.";

    // Global configuration supplies the shared TimeMeter bean.
    private final ApplicationContextRunner unconfiguredRunner =
            new ApplicationContextRunner()
                    .withBean(
                            IngestRejectionMetrics.class,
                            () -> new IngestRejectionMetrics(
                                    new SimpleMeterRegistry()))
                    .withUserConfiguration(
                            GlobalIngestRateLimitConfiguration.class,
                            PerProjectIngestRateLimitConfiguration.class,
                            ValidationAutoConfiguration.class)
                    .withPropertyValues(
                            "triageharbor.ingest.rate-limit.global.capacity=2000",
                            "triageharbor.ingest.rate-limit.global.refill-tokens-per-minute=1000");

    private final ApplicationContextRunner contextRunner =
            unconfiguredRunner.withPropertyValues(
                    PREFIX + "capacity=500",
                    PREFIX + "refill-tokens-per-minute=100");

    @Test
    void validConfigurationBindsAndRegistersLimiter() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context)
                            .hasSingleBean(PerProjectIngestRateLimiter.class);

                    assertThat(context).hasSingleBean(TimeMeter.class);
                    assertThat(context.getBean(TimeMeter.class))
                            .isSameAs(TimeMeter.SYSTEM_NANOTIME);

                    PerProjectIngestRateLimitProperties properties =
                            context.getBean(
                                    PerProjectIngestRateLimitProperties.class);

                    assertThat(properties.capacity()).isEqualTo(500);
                    assertThat(properties.refillTokensPerMinute())
                            .isEqualTo(100);
                });
    }

    @ParameterizedTest
    @CsvSource({
        "capacity, 0, capacity",
        "capacity, -1, capacity",
        "refill-tokens-per-minute, 0, refillTokensPerMinute",
        "refill-tokens-per-minute, -1, refillTokensPerMinute"
    })
    void nonPositiveQuotaPreventsStartup(
            String property, String value, String field) {

        contextRunner
                .withPropertyValues(PREFIX + property + "=" + value)
                .run(
                        context ->
                                GlobalIngestRateLimitConfigurationTests
                                        .assertPositiveViolations(
                                                context.getStartupFailure(),
                                                field));
    }

    @Test
    void missingCapacityPreventsStartup() {
        unconfiguredRunner
                .withPropertyValues(
                        PREFIX + "refill-tokens-per-minute=100")
                .run(
                        context ->
                                GlobalIngestRateLimitConfigurationTests
                                        .assertPositiveViolations(
                                                context.getStartupFailure(),
                                                "capacity"));
    }

    @Test
    void missingRefillPreventsStartup() {
        unconfiguredRunner
                .withPropertyValues(PREFIX + "capacity=500")
                .run(
                        context ->
                                GlobalIngestRateLimitConfigurationTests
                                        .assertPositiveViolations(
                                                context.getStartupFailure(),
                                                "refillTokensPerMinute"));
    }

    @Test
    void missingBothQuotasPreventsStartup() {
        unconfiguredRunner.run(
                context ->
                        GlobalIngestRateLimitConfigurationTests
                                .assertPositiveViolations(
                                        context.getStartupFailure(),
                                        "capacity",
                                        "refillTokensPerMinute"));
    }
}
