package com.triageharbor.event;

import java.util.Objects;
import java.util.UUID;

/** Project identity derived from a verified ingest credential. */
public final class AuthenticatedProject {

    private final UUID id;

    // Package-private: production code obtains instances via the resolver.
    AuthenticatedProject(UUID id) {
        this.id = Objects.requireNonNull(id);
    }

    public UUID id() {
        return id;
    }
}
