package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PerProjectIngestRateLimiterTests {

    @Test
    void projectsHaveIndependentBudgets() {
        ManualTimeMeter time = new ManualTimeMeter();

        PerProjectIngestRateLimiter limiter =
                limiter(1, 60, time);

        AuthenticatedProject a =
                new AuthenticatedProject(UUID.randomUUID());
        AuthenticatedProject b =
                new AuthenticatedProject(UUID.randomUUID());

        assertThat(limiter.tryConsume(a).isConsumed()).isTrue();
        assertThat(limiter.tryConsume(a).isConsumed()).isFalse();

        assertThat(limiter.tryConsume(b).isConsumed()).isTrue();
        assertThat(limiter.tryConsume(b).isConsumed()).isFalse();
    }

    @Test
    void projectBucketRefillsUsingMonotonicTime() {
        ManualTimeMeter time = new ManualTimeMeter();

        PerProjectIngestRateLimiter limiter =
                limiter(2, 20, time);

        AuthenticatedProject project =
                new AuthenticatedProject(UUID.randomUUID());

        assertThat(limiter.tryConsume(project).isConsumed()).isTrue();
        assertThat(limiter.tryConsume(project).isConsumed()).isTrue();

        ConsumptionProbe rejected = limiter.tryConsume(project);

        assertThat(rejected.isConsumed()).isFalse();
        assertThat(rejected.getNanosToWaitForRefill())
                .isEqualTo(Duration.ofSeconds(3).toNanos());

        time.advance(Duration.ofMillis(1500));

        ConsumptionProbe partial = limiter.tryConsume(project);

        assertThat(partial.isConsumed()).isFalse();
        assertThat(partial.getNanosToWaitForRefill())
                .isEqualTo(Duration.ofMillis(1500).toNanos());

        time.advance(Duration.ofMillis(1500));

        assertThat(limiter.tryConsume(project).isConsumed()).isTrue();
        assertThat(limiter.tryConsume(project).isConsumed()).isFalse();
    }

    @Test
    void concurrentFirstUseCannotExceedProjectCapacity() throws Exception {
        int capacity = 8;
        int callers = 50;

        ManualTimeMeter time = new ManualTimeMeter();
        PerProjectIngestRateLimiter limiter =
                limiter(capacity, 60, time);

        AuthenticatedProject project =
                new AuthenticatedProject(UUID.randomUUID());

        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(callers);

        try {
            List<Future<Boolean>> results = new ArrayList<>();

            for (int i = 0; i < callers; i++) {
                results.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();

                                    if (!start.await(15, TimeUnit.SECONDS)) {
                                        throw new AssertionError(
                                                "Start gate timed out");
                                    }

                                    return limiter
                                            .tryConsume(project)
                                            .isConsumed();
                                }));
            }

            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int admitted = 0;

            for (Future<Boolean> result : results) {
                if (result.get(15, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }

            assertThat(admitted).isEqualTo(capacity);

        } finally {
            start.countDown();
            executor.shutdownNow();

            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS))
                    .as("all admission workers terminated")
                    .isTrue();
        }
    }

    private static PerProjectIngestRateLimiter limiter(
            long capacity,
            long refill,
            TimeMeter time) {
        return new PerProjectIngestRateLimiter(
                new PerProjectIngestRateLimitProperties(
                        capacity, refill),
                time);
    }

    private static final class ManualTimeMeter implements TimeMeter {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
