package com.triageharbor.incident;

final class UnknownProjectException extends RuntimeException {

    UnknownProjectException() {
        super("Unknown project");
    }
}
