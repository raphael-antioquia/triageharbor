package com.triageharbor.event;

import io.github.bucket4j.ConsumptionProbe;

final class IngestRetryAfter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private IngestRetryAfter() {}

    static long seconds(ConsumptionProbe probe) {
        return Math.max(
                1L,
                Math.ceilDiv(
                        probe.getNanosToWaitForRefill(),
                        NANOS_PER_SECOND));
    }
}
