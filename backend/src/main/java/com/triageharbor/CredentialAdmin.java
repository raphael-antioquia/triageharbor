package com.triageharbor;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
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

    private static final DateTimeFormatter UTC_MICROS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                    .withZone(ZoneOffset.UTC);

    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

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

    @FunctionalInterface
    interface ConnectionOpener {
        Connection open(
                String jdbcUrl,
                String username,
                String password) throws SQLException;
    }

    static int run(
            String[] args,
            Map<String, String> env,
            PrintStream out,
            PrintStream err) {

        return run(args, env, out, err, DriverManager::getConnection);
    }

    static int run(
            String[] args,
            Map<String, String> env,
            PrintStream out,
            PrintStream err,
            ConnectionOpener connectionOpener) {

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

        if ("list".equals(command.verb())) {
            try {
                return listCredentials(
                        config, command.projectId(), out, err);
            } catch (SQLException exception) {
                return report(
                        err,
                        1,
                        "Credential inventory failed (SQLState "
                                + sqlState(exception) + ").",
                        false);
            } catch (RuntimeException exception) {
                // Never expose database values or exception messages.
                return report(
                        err,
                        1,
                        "Credential inventory failed.",
                        false);
            }
        }

        if ("issue".equals(command.verb())) {
            return issueCredential(
                    config,
                    command.projectId(),
                    out,
                    err,
                    connectionOpener);
        }

        return revokeCredential(
                config,
                command.projectId(),
                command.credentialId(),
                command.forceLast(),
                err,
                connectionOpener);
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

    // Package-private entry point for read-only connection tests.
    static Connection openReadOnlyConnection(Map<String, String> env)
            throws SQLException {
        return openReadOnlyConnection(DatabaseConfig.from(env));
    }

    private static Connection openReadOnlyConnection(DatabaseConfig config)
            throws SQLException {

        Connection connection =
                DriverManager.getConnection(
                        config.jdbcUrl,
                        config.username,
                        config.password);

        try {
            // Effective under autocommit with readOnlyMode=always.
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }

            throw exception;
        }
    }

    private enum CommitOutcome {
        NOT_COMMITTED,
        COMMIT_UNCERTAIN,
        COMMITTED
    }

    private static String lockProjectForCredentialMutation(
            Connection connection, UUID projectId) throws SQLException {

        // Revoke's last-key guard needs a fresh snapshot per statement
        // after waiting for the lock; do not raise this level.
        connection.setTransactionIsolation(
                Connection.TRANSACTION_READ_COMMITTED);

        // Both must precede the first transactional statement.
        connection.setAutoCommit(false);

        try (Statement timeout = connection.createStatement()) {
            timeout.execute("SET LOCAL lock_timeout = '5s'");
        }

        try (PreparedStatement lock =
                connection.prepareStatement(
                        """
                        SELECT name
                        FROM projects
                        WHERE id = ?
                        FOR NO KEY UPDATE
                        """)) {

            lock.setObject(1, projectId);

            try (ResultSet rows = lock.executeQuery()) {
                if (!rows.next()) {
                    throw new MissingProjectException();
                }

                return rows.getString(1);
            }
        }
    }

    private static int issueCredential(
            DatabaseConfig config,
            UUID projectId,
            PrintStream out,
            PrintStream err,
            ConnectionOpener connectionOpener) {

        CommitOutcome outcome = CommitOutcome.NOT_COMMITTED;
        UUID credentialId = null;
        String metadata = null;
        Exception failure = null;

        // Generate the secret before opening a database transaction.
        String token;
        byte[] keyHash;

        try {
            byte[] randomBytes = new byte[32];
            TOKEN_RANDOM.nextBytes(randomBytes);

            token = "th_"
                    + Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(randomBytes);

            // Best effort; the token String itself cannot be erased.
            Arrays.fill(randomBytes, (byte) 0);

            keyHash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));

        } catch (NoSuchAlgorithmException | RuntimeException exception) {
            return report(
                    err,
                    1,
                    "Credential generation failed. No credential was issued.",
                    false);
        }

        // Connection acquisition is separate from transactional work.
        // If opening fails, no SQL has executed.
        Connection connection;

        try {
            connection = connectionOpener.open(
                    config.jdbcUrl,
                    config.username,
                    config.password);
        } catch (SQLException | RuntimeException exception) {
            return report(
                    err,
                    1,
                    "Database connection failed (SQLState "
                            + sqlState(exception)
                            + "). No credential was issued.",
                    false);
        }

        try {
            String projectName =
                    lockProjectForCredentialMutation(connection, projectId);

            try (PreparedStatement insert =
                    connection.prepareStatement(
                            """
                            INSERT INTO project_ingest_credentials (
                                project_id,
                                key_hash,
                                key_prefix
                            )
                            VALUES (?, ?, ?)
                            RETURNING id
                            """)) {

                insert.setObject(1, projectId);
                insert.setBytes(2, keyHash);
                insert.setString(3, token.substring(0, 11));

                try (ResultSet rows = insert.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException(
                                "Credential INSERT returned no row",
                                "XX000");
                    }

                    credentialId = rows.getObject(1, UUID.class);

                    if (credentialId == null || rows.next()) {
                        throw new SQLException(
                                "Unexpected credential INSERT result",
                                "XX000");
                    }
                }
            }

            // Prepare safe metadata before attempting COMMIT.
            metadata = "Credential " + credentialId
                    + " committed for project " + projectId
                    + " (name=" + escapeTsv(projectName) + ").";

            // Do not commit if stderr is already known to be unusable.
            if (err.checkError()) {
                throw new IllegalStateException(
                        "Diagnostic stream already unavailable");
            }

            // A COMMIT exception always produces an uncertain outcome.
            outcome = CommitOutcome.COMMIT_UNCERTAIN;

            connection.commit();

            outcome = CommitOutcome.COMMITTED;

        } catch (SQLException | RuntimeException exception) {
            failure = exception;
        }

        if (outcome != CommitOutcome.COMMITTED) {
            // Rollback is attempted for both definitely uncommitted
            // and uncertain outcomes. Never replace the original error.
            try {
                connection.rollback();
            } catch (SQLException | RuntimeException rollbackFailure) {
                if (failure != null) {
                    failure.addSuppressed(rollbackFailure);
                }
            }

            try {
                connection.close();
            } catch (SQLException | RuntimeException closeFailure) {
                if (failure != null) {
                    failure.addSuppressed(closeFailure);
                }
            }

            if (outcome == CommitOutcome.COMMIT_UNCERTAIN) {
                return report(
                        err,
                        1,
                        "Commit outcome uncertain for credential "
                                + credentialId
                                + " (SQLState " + sqlState(failure) + "). "
                                + "Run list for project " + projectId
                                + ". If credential " + credentialId
                                + " is present, issue a replacement credential, "
                                + "then revoke " + credentialId + ".",
                        false);
            }

            if (failure instanceof MissingProjectException) {
                return report(
                        err,
                        1,
                        "Project not found. No credential was issued.",
                        false);
            }

            return report(
                    err,
                    1,
                    "Credential issuance failed (SQLState "
                            + sqlState(failure)
                            + "). No credential was issued.",
                    false);
        }

        // COMMIT has been confirmed.
        // Never attempt rollback after this point.

        // Write metadata first, then attempt token output exactly once.
        // Both happen before potentially slow connection cleanup.
        boolean stderrOk = writeLine(err, metadata);
        boolean stdoutOk = writeLine(out, token);

        Throwable closeFailure = null;

        try {
            connection.close();
        } catch (SQLException | RuntimeException exception) {
            closeFailure = exception;
        }

        if (closeFailure != null) {
            boolean warningOk = writeLine(
                    err,
                    "Credential " + credentialId
                            + " committed; database connection cleanup failed "
                            + "(SQLState " + sqlState(closeFailure) + ").");

            stderrOk = warningOk && stderrOk;
        }

        if (!stdoutOk) {
            boolean recoveryOk = writeLine(
                    err,
                    "Credential " + credentialId
                            + " committed; stdout failed; "
                            + "issue a replacement credential, then revoke "
                            + credentialId + ".");

            stderrOk = recoveryOk && stderrOk;
        }

        return stdoutOk && stderrOk && !outputHasError(out)
                        && !outputHasError(err)
                ? 0
                : 1;
    }

    private static int revokeCredential(
            DatabaseConfig config,
            UUID projectId,
            UUID credentialId,
            boolean forceLast,
            PrintStream err,
            ConnectionOpener connectionOpener) {

        CommitOutcome outcome = CommitOutcome.NOT_COMMITTED;
        boolean alreadyRevoked = false;
        boolean revokesLastActive = false;
        String receipt = null;
        Exception failure = null;

        // If opening fails, no SQL has executed.
        Connection connection;

        try {
            connection = connectionOpener.open(
                    config.jdbcUrl,
                    config.username,
                    config.password);
        } catch (SQLException | RuntimeException exception) {
            return report(
                    err,
                    1,
                    "Database connection failed (SQLState "
                            + sqlState(exception)
                            + "). No credential was revoked.",
                    false);
        }

        try {
            String projectName =
                    lockProjectForCredentialMutation(connection, projectId);

            String project = "project " + projectId
                    + " (name=" + escapeTsv(projectName) + ")";

            OffsetDateTime revokedAt;

            // Scoped by project: another project's credential never matches.
            try (PreparedStatement lookup =
                    connection.prepareStatement(
                            """
                            SELECT revoked_at
                            FROM project_ingest_credentials
                            WHERE project_id = ?
                              AND id = ?
                            """)) {

                lookup.setObject(1, projectId);
                lookup.setObject(2, credentialId);

                try (ResultSet rows = lookup.executeQuery()) {
                    if (!rows.next()) {
                        throw new MissingCredentialException();
                    }

                    revokedAt = rows.getObject(1, OffsetDateTime.class);
                }
            }

            if (revokedAt != null) {
                // Idempotent success, checked before the last-key guard.
                receipt = "Credential " + credentialId
                        + " already revoked at "
                        + UTC_MICROS.format(revokedAt.toInstant())
                        + " for " + project + ". No change made.";

                // Last statement in this try: nothing can fail after it.
                alreadyRevoked = true;
            } else {
                long activeCount;

                // Guard and UPDATE both run under the project lock.
                try (PreparedStatement count =
                        connection.prepareStatement(
                                """
                                SELECT count(*)
                                FROM project_ingest_credentials
                                WHERE project_id = ?
                                  AND revoked_at IS NULL
                                """)) {

                    count.setObject(1, projectId);

                    try (ResultSet rows = count.executeQuery()) {
                        if (!rows.next()) {
                            throw new SQLException(
                                    "Active credential count returned no row",
                                    "XX000");
                        }

                        activeCount = rows.getLong(1);
                    }
                }

                // The target is active, so the count includes it.
                if (activeCount < 1) {
                    throw new SQLException(
                            "Inconsistent active credential count",
                            "XX000");
                }

                revokesLastActive = activeCount == 1;

                if (revokesLastActive && !forceLast) {
                    throw new LastActiveCredentialException();
                }

                try (PreparedStatement update =
                        connection.prepareStatement(
                                """
                                UPDATE project_ingest_credentials
                                SET revoked_at = now()
                                WHERE id = ?
                                  AND project_id = ?
                                  AND revoked_at IS NULL
                                RETURNING id
                                """)) {

                    update.setObject(1, credentialId);
                    update.setObject(2, projectId);

                    try (ResultSet rows = update.executeQuery()) {
                        // Zero rows: changed outside the operator lock.
                        if (!rows.next() || rows.next()) {
                            throw new SQLException(
                                    "Unexpected credential UPDATE result",
                                    "XX000");
                        }
                    }
                }

                // Prepare output before COMMIT so reporting cannot throw after it.
                receipt = "Credential " + credentialId
                        + " revoked for " + project + ".";

                // A COMMIT exception always produces an uncertain outcome.
                outcome = CommitOutcome.COMMIT_UNCERTAIN;

                connection.commit();

                outcome = CommitOutcome.COMMITTED;
            }
        } catch (SQLException | RuntimeException exception) {
            failure = exception;
        }

        if (outcome == CommitOutcome.COMMITTED) {
            // COMMIT confirmed and lock released; never roll back.
            boolean ok = writeLine(err, receipt);

            if (revokesLastActive) {
                ok = writeLine(
                                err,
                                "WARNING: project " + projectId
                                        + " now has no active ingest credential; "
                                        + "ingestion fails until a new credential "
                                        + "is issued.")
                        && ok;
            }

            Throwable closeFailure = null;

            try {
                connection.close();
            } catch (SQLException | RuntimeException exception) {
                closeFailure = exception;
            }

            if (closeFailure != null) {
                ok = writeLine(
                                err,
                                "Credential " + credentialId
                                        + " revoked; database connection cleanup failed "
                                        + "(SQLState " + sqlState(closeFailure) + ").")
                        && ok;
            }

            return ok && !outputHasError(err) ? 0 : 1;
        }

        if (alreadyRevoked && failure == null) {
            // The revoked state was committed by an earlier transaction.
            // Release the project lock before reporting success.
            Exception cleanupFailure = null;

            try {
                connection.rollback();
            } catch (SQLException | RuntimeException exception) {
                cleanupFailure = exception;
            }

            try {
                connection.close();
            } catch (SQLException | RuntimeException exception) {
                if (cleanupFailure == null) {
                    cleanupFailure = exception;
                } else {
                    cleanupFailure.addSuppressed(exception);
                }
            }

            boolean ok = writeLine(err, receipt);

            if (cleanupFailure != null) {
                ok = writeLine(
                                err,
                                "Database connection cleanup failed (SQLState "
                                        + sqlState(cleanupFailure)
                                        + "); no change was needed.")
                        && ok;
            }

            return ok && !outputHasError(err) ? 0 : 1;
        }

        // NOT_COMMITTED or COMMIT_UNCERTAIN. Never replace the original error.
        try {
            connection.rollback();
        } catch (SQLException | RuntimeException rollbackFailure) {
            if (failure != null) {
                failure.addSuppressed(rollbackFailure);
            }
        }

        try {
            connection.close();
        } catch (SQLException | RuntimeException closeFailure) {
            if (failure != null) {
                failure.addSuppressed(closeFailure);
            }
        }

        if (outcome == CommitOutcome.COMMIT_UNCERTAIN) {
            return report(
                    err,
                    1,
                    "Revocation commit outcome uncertain for credential "
                            + credentialId
                            + " (SQLState " + sqlState(failure) + "). "
                            + "The credential may still be active. "
                            + "Re-run the identical revoke command"
                            + (forceLast
                                    ? ", including --force-last."
                                    : ". If the last-credential guard rejects "
                                            + "the retry, issue a replacement "
                                            + "credential, then revoke "
                                            + credentialId + ".")
                            + (revokesLastActive
                                    ? " If the revocation committed, project "
                                            + projectId
                                            + " has no active credential."
                                    : ""),
                    false);
        }

        if (failure instanceof MissingProjectException) {
            return report(
                    err,
                    1,
                    "Project not found. No credential was revoked.",
                    false);
        }

        if (failure instanceof MissingCredentialException) {
            return report(
                    err,
                    1,
                    "Credential " + credentialId
                            + " not found in project " + projectId
                            + ". No credential was revoked.",
                    false);
        }

        if (failure instanceof LastActiveCredentialException) {
            return report(
                    err,
                    1,
                    "Credential " + credentialId
                            + " is the last active credential for project "
                            + projectId
                            + ". Issue a replacement credential first, or re-run "
                            + "with --force-last to leave the project without an "
                            + "active credential. No credential was revoked.",
                    false);
        }

        return report(
                err,
                1,
                "Credential revocation failed (SQLState "
                        + sqlState(failure)
                        + "). No change was committed.",
                false);
    }

    private static boolean writeLine(PrintStream stream, String value) {
        try {
            stream.print(value + System.lineSeparator());
            return !stream.checkError();
        } catch (RuntimeException exception) {
            // Preserve transaction outcome; never expose the cause.
            return false;
        }
    }

    private static boolean outputHasError(PrintStream stream) {
        try {
            return stream.checkError();
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private static final class MissingCredentialException
            extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private MissingCredentialException() {
            super(null, null, false, false);
        }
    }

    private static final class LastActiveCredentialException
            extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private LastActiveCredentialException() {
            super(null, null, false, false);
        }
    }

    private static final class MissingProjectException
            extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private MissingProjectException() {
            super(null, null, false, false);
        }
    }

    private static int listCredentials(
            DatabaseConfig config,
            UUID projectId,
            PrintStream out,
            PrintStream err)
            throws SQLException {

        StringBuilder listing =
                new StringBuilder(
                        "project_id\tproject_name\tcredential_id"
                                + "\tkey_prefix\tcreated_at_utc"
                                + "\trevoked_at_utc\n");

        boolean projectExists = false;

        try (Connection connection = openReadOnlyConnection(config)) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            """
                            SELECT p.id AS project_id,
                                   p.name AS project_name,
                                   c.id AS credential_id,
                                   c.key_prefix,
                                   c.created_at,
                                   c.revoked_at
                            FROM projects p
                            LEFT JOIN project_ingest_credentials c
                              ON c.project_id = p.id
                            WHERE p.id = ?
                            ORDER BY c.created_at, c.id
                            """)) {

                statement.setObject(1, projectId);

                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        projectExists = true;

                        listing.append(
                                        rows.getObject(
                                                "project_id", UUID.class))
                                .append('\t')
                                .append(
                                        escapeTsv(
                                                rows.getString("project_name")))
                                .append('\t');

                        UUID credentialId =
                                rows.getObject(
                                        "credential_id", UUID.class);

                        if (credentialId == null) {
                            // Project exists but has zero credentials.
                            listing.append("\t\t\t");
                        } else {
                            OffsetDateTime created =
                                    rows.getObject(
                                            "created_at",
                                            OffsetDateTime.class);

                            OffsetDateTime revoked =
                                    rows.getObject(
                                            "revoked_at",
                                            OffsetDateTime.class);

                            listing.append(credentialId)
                                    .append('\t')
                                    .append(rows.getString("key_prefix"))
                                    .append('\t')
                                    .append(
                                            UTC_MICROS.format(
                                                    created.toInstant()))
                                    .append('\t');

                            if (revoked != null) {
                                listing.append(
                                        UTC_MICROS.format(
                                                revoked.toInstant()));
                            }
                        }

                        listing.append('\n');
                    }
                }
            }
        }

        if (!projectExists) {
            return report(
                    err, 1, "Project not found.", false);
        }

        // Build the complete listing before writing anything.
        out.print(listing);

        if (out.checkError()) {
            return report(
                    err, 1, "Credential list output failed.", false);
        }

        return 0;
    }

    private static String escapeTsv(String value) {
        StringBuilder escaped =
                new StringBuilder(value.length());

        for (int index = 0; index < value.length(); ) {
            int cp = value.codePointAt(index);
            index += Character.charCount(cp);

            if (cp == '\\') {
                escaped.append("\\\\");
            } else if (cp == '\t') {
                escaped.append("\\t");
            } else if (cp == '\n') {
                escaped.append("\\n");
            } else if (cp == '\r') {
                escaped.append("\\r");
            } else if (Character.isISOControl(cp)
                    || Character.getType(cp) == Character.FORMAT
                    || Character.getType(cp)
                            == Character.LINE_SEPARATOR
                    || Character.getType(cp)
                            == Character.PARAGRAPH_SEPARATOR) {

                escaped.append("\\u{")
                        .append(Integer.toHexString(cp))
                        .append('}');
            } else {
                escaped.appendCodePoint(cp);
            }
        }

        return escaped.toString();
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
                            + "&socketTimeout=30"
                            + "&readOnlyMode=always";

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
