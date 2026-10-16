
package com.triageharbor.event;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SensitiveDataRedactor {

    private static final String MARKER = "[REDACTED]";

    private static final Pattern URL_SCHEME =
            Pattern.compile("(?<![A-Za-z0-9+.-])[A-Za-z][A-Za-z0-9+.-]*+://");

    private static final Pattern JWT =
            Pattern.compile(
                    "\\beyJ[A-Za-z0-9_-]{8,}+\\.eyJ[A-Za-z0-9_-]++\\.[A-Za-z0-9_-]*+");

    private static final Pattern ASSIGNMENT =
            Pattern.compile(
                    "(?<![\\w.-])([\"']?)([A-Za-z][\\w.-]*+)\\1[ \\t]*+[:=][ \\t]*+");

    private static final Pattern DOUBLE_QUOTED_VALUE =
            Pattern.compile("\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\"");

    private static final Pattern SINGLE_QUOTED_VALUE =
            Pattern.compile("'[^'\\\\]*+(?:\\\\.[^'\\\\]*+)*+'");

    private static final Pattern KEY_BOUNDARIES =
            Pattern.compile("[_.-]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    private static final Set<String> SENSITIVE_SEGMENTS =
            Set.of(
                    "password",
                    "passwd",
                    "pwd",
                    "pass",
                    "secret",
                    "token",
                    "credential",
                    "credentials",
                    "auth",
                    "authorization",
                    "session",
                    "sessionid",
                    "cookie");

    private SensitiveDataRedactor() {}

    static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        // Pass order affects fingerprint-v1 identity. Reordering requires version review.
        return redactAssignments(redactJwt(redactUrlUserinfo(value)));
    }

    private static String redactUrlUserinfo(String input) {
        Matcher schemes = URL_SCHEME.matcher(input);
        StringBuilder output = new StringBuilder(input.length());
        int copiedThrough = 0;
        int searchFrom = 0;

        while (schemes.find(searchFrom)) {
            int authorityStart = schemes.end();
            int authorityEnd = authorityStart;
            int lastAt = -1;
            int colon = -1;
            while (authorityEnd < input.length()
                    && input.charAt(authorityEnd) != '/'
                    && !Character.isWhitespace(input.charAt(authorityEnd))) {
                char current = input.charAt(authorityEnd);
                if (current == '@') {
                    lastAt = authorityEnd;
                } else if (current == ':' && colon < 0) {
                    colon = authorityEnd;
                }
                authorityEnd++;
            }

            // Empty usernames (redis://:pw@host) and empty hosts (pg://u:pw@/db) still redact.
            if (lastAt < authorityStart || colon < authorityStart || colon >= lastAt) {
                searchFrom = authorityStart;
                continue;
            }

            output.append(input, copiedThrough, colon + 1);
            output.append(MARKER);
            copiedThrough = lastAt;
            searchFrom = lastAt + 1;
        }

        return output.append(input, copiedThrough, input.length()).toString();
    }

    private static String redactJwt(String input) {
        return JWT.matcher(input).replaceAll(Matcher.quoteReplacement(MARKER));
    }


    private static String redactAssignments(String input) {
        Matcher assignments = ASSIGNMENT.matcher(input);
        StringBuilder output = new StringBuilder(input.length());
        int copiedThrough = 0;
        int searchFrom = 0;
        int lineEnd = -1;

        while (assignments.find(searchFrom)) {
            if (!isSensitiveKey(assignments.group(2))) {
                // A benign assignment must not hide another assignment in its value.
                searchFrom = assignments.end();
                continue;
            }

            int valueStart = assignments.end();
            if (valueStart > lineEnd) {
                // Reuse the line end so many values on one line stay linear.
                lineEnd = endOfLine(input, valueStart);
            }
            output.append(input, copiedThrough, valueStart);

            if (valueStart < lineEnd
                    && (input.charAt(valueStart) == '"'
                            || input.charAt(valueStart) == '\'')) {
                char quote = input.charAt(valueStart);
                Pattern quoted =
                        quote == '"' ? DOUBLE_QUOTED_VALUE : SINGLE_QUOTED_VALUE;
                Matcher value = quoted.matcher(input).region(valueStart, lineEnd);

                if (value.lookingAt()) {
                    output.append(quote).append(MARKER).append(quote);
                    copiedThrough = value.end();
                } else {
                    // Unclosed quoted value: consume the opening quote and the line.
                    output.append(MARKER);
                    copiedThrough = lineEnd;
                }
            } else {
                // Conservative fallback: never retain punctuation or trailing words.
                output.append(MARKER);
                copiedThrough = lineEnd;
            }
            searchFrom = copiedThrough;
        }

        return output.append(input, copiedThrough, input.length()).toString();
    }

    private static int endOfLine(String value, int from) {
        int index = from;
        while (index < value.length()
                && value.charAt(index) != '\r'
                && value.charAt(index) != '\n') {
            index++;
        }
        return index;
    }

    private static boolean isSensitiveKey(String key) {
        String[] segments = KEY_BOUNDARIES.split(key);
        String previous = "";
        for (String segment : segments) {
            String current = segment.toLowerCase(Locale.ROOT);
            if (SENSITIVE_SEGMENTS.contains(current)
                    || (previous.equals("api") && current.equals("key"))
                    || (previous.equals("access") && current.equals("key"))
                    || (previous.equals("private") && current.equals("key"))
                    || (previous.equals("session") && current.equals("id"))) {
                return true;
            }
            previous = current;
        }
        return false;
    }
}
