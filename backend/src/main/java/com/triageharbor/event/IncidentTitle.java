package com.triageharbor.event;

import java.util.Objects;

final class IncidentTitle {

    static final int MAX_CODE_POINTS = 200;

    private IncidentTitle() {}

    static String derive(String exceptionType, String message) {
        Objects.requireNonNull(message, "message");

        String source =
                exceptionType != null && !exceptionType.isBlank()
                        ? exceptionType
                        : message;

        int codePointCount = source.codePointCount(0, source.length());

        if (codePointCount <= MAX_CODE_POINTS) {
            return source;
        }

        int endIndex = source.offsetByCodePoints(0, MAX_CODE_POINTS);
        return source.substring(0, endIndex);
    }
}
