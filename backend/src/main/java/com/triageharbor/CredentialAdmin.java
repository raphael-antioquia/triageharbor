package com.triageharbor;

import java.io.PrintStream;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.output.ValidateResult;

public final class CredentialAdmin {

    private static final String USAGE =
            """
            Usage:
              issue --project <uuid>
              list --project <uuid>
              revoke --project <uuid> --credential <uuid>
              revoke --project <uuid> --credential <uuid> --force-last
            """;

    private CredentialAdmin() {}

    public static void main(String[] args) {
        // Reserve the original stdout for command output only.
        PrintStream commandOut = System.out;
        System.setOut(System.err);

        // Configure logging before any SLF4J logger initializes.
        System.setProperty(
                "logback.configurationFile",
                "credential-admin-logback.xml");

        System.exit(
                run(args, System.getenv(), commandOut, System.err));
    }

    static int run(
            String[] args,
            Map<String, String> env,
            PrintStream out,
            PrintStream err) {

        ParsedCommand command;

        // Parse before opening any database connection.
        try {
            command = parseCommand(args);
        } catch (IllegalArgumentException exception) {
            return report(
                    err, 2, "Invalid command or arguments.", true);
        }

        DatabaseConfig config;

        try {
            config = DatabaseConfig.from(env);
        } catch (IllegalArgumentException exception) {
            return report(
                    err,
                    1,
                    "Missing or invalid database configuration.",
                    false);
        }

        try {
            verifySchema(config);
        } catch (PreflightException exception) {
            return report(
                    err,
                    1,
                    "Database migration preflight failed. No command executed. "
                            + exception.getMessage(),
                    false);
        } catch (RuntimeException exception) {
            // Never print unclassified exceptions or cause chains.
            return report(
                    err,
                    1,
                    "Database migration preflight failed. No command executed.",
                    false);
        }

        // Batch A: credential operations are intentionally unavailable.
        return report(
                err,
                1,
                "Command " + command.verb()
                        + " is not implemented in Batch A.",
                false);
    }

    // Package-private entry point for preflight integration tests.
    static void verifySchema(Map<String, String> env) {
        verifySchema(DatabaseConfig.from(env));
    }

    private static void verifySchema(DatabaseConfig config) {
        // Read-only path: no migrate, repair, clean, or baseline calls.
        Flyway flyway =
                Flyway.configure()
                        .dataSource(
                                config.jdbcUrl,
                                config.username,
                                config.password)
                        .locations("classpath:db/migration")
                        .cleanDisabled(true)
                        .load();

        ValidateResult result;

        try {
            result = flyway.validateWithResult();
        } catch (FlywayException exception) {
            throw new PreflightException(
                    "Connection or validation error (SQLState "
                            + sqlState(exception) + ").");
        }

        if (!result.validationSuccessful) {
            throw new PreflightException(
                    "Packaged migrations do not validate against the database: "
                            + invalidMigrations(result) + ".");
        }

        MigrationInfoService info;

        try {
            info = flyway.info();
        } catch (FlywayException exception) {
            throw new PreflightException(
                    "Connection error while reading migration state (SQLState "
                            + sqlState(exception) + ").");
        }

        try {
            MigrationVerifier.verify(info);
        } catch (IllegalStateException exception) {
            // Migration metadata is privileged DB state.
            // Control-character sanitization is not implemented.
            throw new PreflightException(exception.getMessage());
        }
    }

    private static String invalidMigrations(ValidateResult result) {
        if (result.invalidMigrations == null
                || result.invalidMigrations.isEmpty()) {
            return "no details";
        }

        return result.invalidMigrations.stream()
                .map(
                        migration ->
                                migration.version
                                        + ":"
                                        + (migration.errorDetails == null
                                                ? "UNKNOWN"
                                                : migration.errorDetails.errorCode))
                .collect(Collectors.joining(", "));
    }

    private static String sqlState(Throwable throwable) {
        Throwable current = throwable;

        // Bound cause traversal in case a cause chain is malformed.
        for (int depth = 0;
                current != null && depth < 16;
                depth++) {

            if (current instanceof SQLException sql
                    && sql.getSQLState() != null) {
                return sql.getSQLState();
            }

            current = current.getCause();
        }

        return "unavailable";
    }

    private static ParsedCommand parseCommand(String[] args) {
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException();
        }

        return switch (args[0]) {
            case "issue", "list" -> {
                if (args.length != 3
                        || !"--project".equals(args[1])) {
                    throw new IllegalArgumentException();
                }

                yield new ParsedCommand(
                        args[0], parseUuid(args[2]), null, false);
            }

            case "revoke" -> {
                if ((args.length != 5 && args.length != 6)
                        || !"--project".equals(args[1])
                        || !"--credential".equals(args[3])
                        || (args.length == 6
                                && !"--force-last".equals(args[5]))) {
                    throw new IllegalArgumentException();
                }

                yield new ParsedCommand(
                        "revoke",
                        parseUuid(args[2]),
                        parseUuid(args[4]),
                        args.length == 6);
            }

            default -> throw new IllegalArgumentException();
        };
    }

    private static UUID parseUuid(String value) {
        UUID uuid = UUID.fromString(value);

        // Reject abbreviated UUID strings.
        if (!uuid.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException();
        }

        return uuid;
    }

    private static int report(
            PrintStream err,
            int exitCode,
            String message,
            boolean usage) {

        err.println(message);

        if (usage) {
            err.print(USAGE);
        }

        return err.checkError() ? 1 : exitCode;
    }

    private record ParsedCommand(
            String verb,
            UUID projectId,
            UUID credentialId,
            boolean forceLast) {}

    private static final class DatabaseConfig {

        private final String jdbcUrl;
        private final String username;
        private final String password;

        private DatabaseConfig(
                String jdbcUrl,
                String username,
                String password) {

            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        private static DatabaseConfig from(Map<String, String> env) {
            String host = required(env, "DB_HOST");
            String portValue = required(env, "DB_PORT");
            String database = required(env, "DB_NAME");
            String username = required(env, "DB_MIGRATOR_USER");
            String password = required(env, "DB_MIGRATOR_PASSWORD");

            if (!host.matches("[A-Za-z0-9][A-Za-z0-9.-]*")
                    || !database.matches("[A-Za-z0-9_][A-Za-z0-9_-]*")
                    || !portValue.matches("[0-9]{1,5}")
                    || !"triageharbor_migrator".equals(username)) {
                throw new IllegalArgumentException();
            }

            int port = Integer.parseInt(portValue);

            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException();
            }

            String jdbcUrl =
                    "jdbc:postgresql://" + host + ":" + port + "/" + database
                            + "?ApplicationName=triageharbor-credential-admin"
                            + "&logServerErrorDetail=false"
                            + "&connectTimeout=10"
                            + "&loginTimeout=10"
                            + "&socketTimeout=30";

            return new DatabaseConfig(
                    jdbcUrl, username, password);
        }

        private static String required(
                Map<String, String> env, String name) {

            String value = env.get(name);

            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException();
            }

            return value;
        }
    }

    private static final class PreflightException
            extends RuntimeException {

        private static final long serialVersionUID = 1L;

        // Safe diagnostic only; do not retain the original cause.
        PreflightException(String safeMessage) {
            super(safeMessage, null, false, false);
        }
    }
}
