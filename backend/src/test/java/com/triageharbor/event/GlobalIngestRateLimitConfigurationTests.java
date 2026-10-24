package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.TimeMeter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

class GlobalIngestRateLimitConfigurationTests {

    private static final String PREFIX =
            "triageharbor.ingest.rate-limit.global.";

    private final ApplicationContextRunner unconfiguredRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            GlobalIngestRateLimitConfiguration.class,
                            ValidationAutoConfiguration.class);

    private final ApplicationContextRunner contextRunner =
            unconfiguredRunner.withPropertyValues(
                    PREFIX + "capacity=2000",
                    PREFIX + "refill-tokens-per-minute=1000");

    @Test
    void validConfigurationBindsAndProvidesMonotonicTimeMeter() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();

                    GlobalIngestRateLimitProperties properties =
                            context.getBean(GlobalIngestRateLimitProperties.class);

                    assertThat(properties.capacity()).isEqualTo(2000);
                    assertThat(properties.refillTokensPerMinute())
                            .isEqualTo(1000);

                    TimeMeter timeMeter = context.getBean(TimeMeter.class);

                    assertThat(timeMeter)
                            .isSameAs(TimeMeter.SYSTEM_NANOTIME);
                    assertThat(timeMeter.isWallClockBased()).isFalse();
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
                                assertPositiveViolations(
                                        context.getStartupFailure(), field));
    }

    @Test
    void missingCapacityPreventsStartup() {
        unconfiguredRunner
                .withPropertyValues(
                        PREFIX + "refill-tokens-per-minute=1000")
                .run(
                        context ->
                                assertPositiveViolations(
                                        context.getStartupFailure(),
                                        "capacity"));
    }

    @Test
    void missingRefillPreventsStartup() {
        unconfiguredRunner
                .withPropertyValues(PREFIX + "capacity=2000")
                .run(
                        context ->
                                assertPositiveViolations(
                                        context.getStartupFailure(),
                                        "refillTokensPerMinute"));
    }

    @Test
    void missingBothQuotasPreventsStartup() {
        unconfiguredRunner.run(
                context ->
                        assertPositiveViolations(
                                context.getStartupFailure(),
                                "capacity",
                                "refillTokensPerMinute"));
    }

    static void assertPositiveViolations(
            Throwable failure, String... expectedFields) {

        assertThat(failure).isNotNull();

        Throwable root = failure;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        assertThat(root).isInstanceOf(BindValidationException.class);

        List<ObjectError> errors =
                ((BindValidationException) root)
                        .getValidationErrors()
                        .getAllErrors();

        assertThat(errors)
                .allSatisfy(
                        error -> {
                            assertThat(error).isInstanceOf(FieldError.class);
                            assertThat(error.getCode()).isEqualTo("Positive");
                        });

        assertThat(
                        errors.stream()
                                .map(error -> ((FieldError) error).getField())
                                .toList())
                .containsExactlyInAnyOrder(expectedFields);
    }
}
