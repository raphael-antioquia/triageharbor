package com.triageharbor.event;

final class UnknownIngestKeyException extends RuntimeException {

    UnknownIngestKeyException() {
        super("Unknown ingest credential");
    }
}
