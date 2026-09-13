package com.triageharbor;

enum TriageHarborMode {
    SERVE,
    MIGRATE;

    static TriageHarborMode parse(String value) {
        if (value == null) {
            return SERVE;
        }

        return switch (value) {
            case "serve" -> SERVE;
            case "migrate" -> MIGRATE;
            default ->
                    throw new IllegalArgumentException(
                            "Invalid TRIAGEHARBOR_MODE value '"
                                    + value
                                    + "'. Expected 'serve' or 'migrate'.");
        };
    }
}
