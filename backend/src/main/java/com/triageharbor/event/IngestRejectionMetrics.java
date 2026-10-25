package com.triageharbor.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
final class IngestRejectionMetrics {

    static final String NAME = "triageharbor.ingest.rejected";
    private static final String DESCRIPTION = "Rejected ingestion requests";

    private final MeterRegistry registry;
    private final Counter globalCounter;

    IngestRejectionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.globalCounter = counter("global", "none");
    }

    void recordGlobal() {
        globalCounter.increment();
    }

    // Tag value comes only from a resolved project, never request input.
    void recordProject(AuthenticatedProject project) {
        counter("project", project.id().toString()).increment();
    }

    private Counter counter(String reason, String projectId) {
        return Counter.builder(NAME)
                .description(DESCRIPTION)
                .tag("reason", reason)
                .tag("project_id", projectId)
                .register(registry);
    }
}
