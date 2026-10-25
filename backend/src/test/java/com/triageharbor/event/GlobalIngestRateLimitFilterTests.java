package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletInputStream;
import java.io.BufferedReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GlobalIngestRateLimitFilterTests {

    @Test
    void exactlyCapacityRequestsAreAdmittedWithoutRefill() throws Exception {
        ManualTimeMeter timeMeter = new ManualTimeMeter();
        GlobalIngestRateLimitFilter filter =
                new GlobalIngestRateLimitFilter(
                        new GlobalIngestRateLimitProperties(2, 60),
                        timeMeter,
                        new IngestRejectionMetrics(new SimpleMeterRegistry()));

        AtomicInteger downstreamCalls = new AtomicInteger();

        MockHttpServletResponse first =
                invoke(filter, eventRequest(), downstreamCalls);
        MockHttpServletResponse second =
                invoke(filter, eventRequest(), downstreamCalls);
        MockHttpServletResponse third =
                invoke(filter, eventRequest(), downstreamCalls);

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(200);

        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getHeader("Retry-After")).isEqualTo("1");
        assertThat(third.getContentAsByteArray()).isEmpty();
        assertThat(third.getContentType()).isNull();

        assertThat(downstreamCalls.get()).isEqualTo(2);
    }

    @Test
    void greedyRefillAndRetryAfterUseControlledMonotonicTime()
            throws Exception {

        ManualTimeMeter timeMeter = new ManualTimeMeter();
        GlobalIngestRateLimitFilter filter =
                new GlobalIngestRateLimitFilter(
                        new GlobalIngestRateLimitProperties(1, 20),
                        timeMeter,
                        new IngestRejectionMetrics(new SimpleMeterRegistry()));

        AtomicInteger downstreamCalls = new AtomicInteger();

        // One token refills every three seconds.
        assertThat(invoke(filter, eventRequest(), downstreamCalls).getStatus())
                .isEqualTo(200);

        MockHttpServletResponse initialRejection =
                invoke(filter, eventRequest(), downstreamCalls);
        assertThat(initialRejection.getStatus()).isEqualTo(429);
        assertThat(initialRejection.getHeader("Retry-After")).isEqualTo("3");

        timeMeter.advance(Duration.ofMillis(1500));

        MockHttpServletResponse partialRefill =
                invoke(filter, eventRequest(), downstreamCalls);
        assertThat(partialRefill.getStatus()).isEqualTo(429);
        assertThat(partialRefill.getHeader("Retry-After")).isEqualTo("2");

        timeMeter.advance(Duration.ofMillis(1499));

        MockHttpServletResponse almostRefilled =
                invoke(filter, eventRequest(), downstreamCalls);
        assertThat(almostRefilled.getStatus()).isEqualTo(429);
        assertThat(almostRefilled.getHeader("Retry-After")).isEqualTo("1");

        // The earlier rejected attempts must not consume tokens.
        // Exactly one token should become available at three seconds.
        timeMeter.advance(Duration.ofMillis(1));

        assertThat(invoke(filter, eventRequest(), downstreamCalls).getStatus())
                .isEqualTo(200);

        // The refilled token has now been spent.
        assertThat(invoke(filter, eventRequest(), downstreamCalls).getStatus())
                .isEqualTo(429);

        assertThat(downstreamCalls.get()).isEqualTo(2);
    }

    @Test
    void rejectedRequestDoesNotReadBodyOrContentLength() throws Exception {
        ManualTimeMeter timeMeter = new ManualTimeMeter();
        GlobalIngestRateLimitFilter filter =
                new GlobalIngestRateLimitFilter(
                        new GlobalIngestRateLimitProperties(1, 60),
                        timeMeter,
                        new IngestRejectionMetrics(new SimpleMeterRegistry()));

        AtomicInteger downstreamCalls = new AtomicInteger();

        // Exhaust the single available token.
        invoke(filter, eventRequest(), downstreamCalls);

        MockHttpServletRequest rejectedRequest =
                new MockHttpServletRequest("POST", "/events") {

                    @Override
                    public ServletInputStream getInputStream() {
                        throw new AssertionError("Body stream was accessed");
                    }

                    @Override
                    public BufferedReader getReader() {
                        throw new AssertionError("Body reader was accessed");
                    }

                    @Override
                    public int getContentLength() {
                        throw new AssertionError("Content length was accessed");
                    }

                    @Override
                    public long getContentLengthLong() {
                        throw new AssertionError("Long content length was accessed");
                    }
                };

        MockHttpServletResponse response =
                invoke(filter, rejectedRequest, downstreamCalls);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(downstreamCalls.get()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsCannotConsumeBeyondCapacity() throws Exception {
        int capacity = 8;
        int requestCount = 32;

        ManualTimeMeter timeMeter = new ManualTimeMeter();
        GlobalIngestRateLimitFilter filter =
                new GlobalIngestRateLimitFilter(
                        new GlobalIngestRateLimitProperties(capacity, 60),
                        timeMeter,
                        new IngestRejectionMetrics(new SimpleMeterRegistry()));

        AtomicInteger downstreamCalls = new AtomicInteger();

        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);

        try {
            List<Future<Integer>> results = new ArrayList<>();

            for (int i = 0; i < requestCount; i++) {
                results.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();

                                    if (!start.await(15, TimeUnit.SECONDS)) {
                                        throw new AssertionError(
                                                "Start gate timed out");
                                    }

                                    return invoke(
                                                    filter,
                                                    eventRequest(),
                                                    downstreamCalls)
                                            .getStatus();
                                }));
            }

            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();

            for (Future<Integer> result : results) {
                statuses.add(result.get(15, TimeUnit.SECONDS));
            }

            assertThat(statuses)
                    .hasSize(requestCount)
                    .isSubsetOf(200, 429);

            assertThat(statuses.stream().filter(status -> status == 200).count())
                    .isEqualTo(capacity);

            assertThat(statuses.stream().filter(status -> status == 429).count())
                    .isEqualTo(requestCount - capacity);

            assertThat(downstreamCalls.get()).isEqualTo(capacity);

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static MockHttpServletRequest eventRequest() {
        return new MockHttpServletRequest("POST", "/events");
    }

    private static MockHttpServletResponse invoke(
            GlobalIngestRateLimitFilter filter,
            MockHttpServletRequest request,
            AtomicInteger downstreamCalls)
            throws Exception {

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (nextRequest, nextResponse) ->
                        downstreamCalls.incrementAndGet());

        return response;
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
