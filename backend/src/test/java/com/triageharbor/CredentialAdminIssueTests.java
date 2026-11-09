package com.triageharbor;

import static org.assertj.core.api.Assertions.assertThat;

import com.triageharbor.support.RoleSeparatedPostgresTestConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(RoleSeparatedPostgresTestConfiguration.class)
class CredentialAdminIssueTests {

    private static final String PASSWORD = "test-migrator-password";

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Test
    void issuePrintsCanonicalTokenOnlyAfterCommitAndStoresItsHash()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        insertProject(projectId, "issue-project");

        CommitObservingOutputStream stdout =
                new CommitObservingOutputStream(projectId);

        CommitObservingOutputStream stderr =
                new CommitObservingOutputStream(projectId);

        int exitCode;

        try (PrintStream out =
                        new PrintStream(
                                stdout, true, StandardCharsets.UTF_8);
                PrintStream err =
                        new PrintStream(
                                stderr, true, StandardCharsets.UTF_8)) {

            exitCode = CredentialAdmin.run(
                    issueArgs(projectId),
                    environment(),
                    out,
                    err);
        }

        assertThat(exitCode).isZero();
        assertThat(stdout.firstWriteObserved()).isTrue();
        assertThat(stdout.committedAtFirstWrite()).isTrue();

        String output = stdout.content();
        String token = output.stripTrailing();

        assertThat(output).isEqualTo(token + System.lineSeparator());
        assertThat(token).matches("th_[A-Za-z0-9_-]{43}");

        byte[] randomBytes =
                Base64.getUrlDecoder().decode(token.substring(3));

        assertThat(randomBytes).hasSize(32);

        assertThat(
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(randomBytes))
                .isEqualTo(token.substring(3));

        byte[] expectedHash =
                MessageDigest.getInstance("SHA-256")
                        .digest(token.getBytes(StandardCharsets.UTF_8));

        String hashHex = HexFormat.of().formatHex(expectedHash);
        UUID credentialId;

        try (Connection connection = migratorConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT id, project_id, key_hash,
                                       key_prefix, created_at, revoked_at
                                FROM project_ingest_credentials
                                WHERE project_id = ?
                                """)) {

            statement.setObject(1, projectId);

            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();

                credentialId = rows.getObject("id", UUID.class);

                assertThat(rows.getObject("project_id", UUID.class))
                        .isEqualTo(projectId);

                assertThat(rows.getBytes("key_hash"))
                        .containsExactly(expectedHash);

                assertThat(rows.getString("key_prefix"))
                        .isEqualTo(token.substring(0, 11));

                assertThat(rows.getObject("created_at")).isNotNull();
                assertThat(rows.getObject("revoked_at")).isNull();

                assertThat(rows.next()).isFalse();
            }
        }

        assertThat(stderr.firstWriteObserved()).isTrue();
        assertThat(stderr.committedAtFirstWrite()).isTrue();

        String diagnostics = stderr.content();

        assertThat(diagnostics)
                .contains(
                        projectId.toString(),
                        credentialId.toString(),
                        "issue-project")
                .doesNotContain(token, hashHex);
    }

    @Test
    void missingProjectDoesNotIssueCredential() throws Exception {
        UUID missingProject = UUID.randomUUID();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode;

        try (PrintStream out =
                        new PrintStream(
                                stdout, true, StandardCharsets.UTF_8);
                PrintStream err =
                        new PrintStream(
                                stderr, true, StandardCharsets.UTF_8)) {

            exitCode = CredentialAdmin.run(
                    issueArgs(missingProject),
                    environment(),
                    out,
                    err);
        }

        // The FK already prevents orphan rows; this message is what
        // distinguishes an explicit existence check from a 23503 failure.
        assertThat(exitCode).isEqualTo(1);
        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .contains("Project not found.")
                .doesNotContainPattern("th_[A-Za-z0-9_-]{43}");
    }

    @Test
    void stdoutFailureAfterCommitReportsRecoveryWithoutToken()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        insertProject(projectId, "output-failure-project");

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        OutputStream failingOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("Synthetic stdout failure");
            }
        };

        int exitCode;

        try (PrintStream out =
                        new PrintStream(
                                failingOutput, true, StandardCharsets.UTF_8);
                PrintStream err =
                        new PrintStream(
                                stderr, true, StandardCharsets.UTF_8)) {

            exitCode = CredentialAdmin.run(
                    issueArgs(projectId),
                    environment(),
                    out,
                    err);

            assertThat(out.checkError()).isTrue();
        }

        assertThat(exitCode).isEqualTo(1);

        UUID credentialId;

        try (Connection connection = migratorConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT id
                                FROM project_ingest_credentials
                                WHERE project_id = ?
                                  AND revoked_at IS NULL
                                """)) {

            statement.setObject(1, projectId);

            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();

                credentialId = rows.getObject(1, UUID.class);

                assertThat(rows.next()).isFalse();
            }
        }

        String diagnostics =
                stderr.toString(StandardCharsets.UTF_8);

        assertThat(diagnostics)
                .contains(
                        "Credential " + credentialId + " committed",
                        "stdout",
                        "issue a replacement",
                        "revoke")
                .doesNotContain("not committed", "rolled back")
                .doesNotContainPattern("th_[A-Za-z0-9_-]{43}");
    }

    @Test
    void repeatedIssueCreatesDistinctActiveCredentials()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        insertProject(projectId, "repeat-project");

        String first = issueToken(projectId);
        String second = issueToken(projectId);

        assertThat(first).isNotEqualTo(second);

        try (Connection connection = migratorConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT count(*), count(DISTINCT key_hash)
                                FROM project_ingest_credentials
                                WHERE project_id = ?
                                  AND revoked_at IS NULL
                                """)) {

            statement.setObject(1, projectId);

            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
                assertThat(rows.getInt(2)).isEqualTo(2);
            }
        }
    }

    @Test
    void commitSucceedsButAcknowledgmentIsLost() throws Exception {
        UUID projectId = UUID.randomUUID();
        insertProject(projectId, "lost-ack-project");

        AtomicReference<Connection> underlyingConnection =
                new AtomicReference<>();

        CredentialAdmin.ConnectionOpener opener =
                (jdbcUrl, username, password) -> {
                    Connection actual =
                            DriverManager.getConnection(
                                    jdbcUrl, username, password);

                    underlyingConnection.set(actual);

                    return (Connection) Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, arguments) -> {
                                try {
                                    Object result =
                                            method.invoke(actual, arguments);

                                    // The actual database COMMIT succeeded.
                                    // Simulate losing its acknowledgment.
                                    if ("commit".equals(method.getName())) {
                                        throw new SQLException(
                                                "synthetic lost acknowledgment",
                                                "08006");
                                    }

                                    return result;
                                } catch (InvocationTargetException exception) {
                                    // Preserve the original JDBC exception.
                                    throw exception.getCause();
                                }
                            });
                };

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try {
            int exitCode;

            try (PrintStream out =
                            new PrintStream(
                                    stdout, true, StandardCharsets.UTF_8);
                    PrintStream err =
                            new PrintStream(
                                    stderr, true, StandardCharsets.UTF_8)) {

                exitCode = CredentialAdmin.run(
                        issueArgs(projectId),
                        environment(),
                        out,
                        err,
                        opener);
            }

            assertThat(exitCode).isEqualTo(1);
            assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();

            UUID credentialId;

            // Another connection must see the committed credential.
            try (Connection connection = migratorConnection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    """
                                    SELECT id
                                    FROM project_ingest_credentials
                                    WHERE project_id = ?
                                      AND revoked_at IS NULL
                                    """)) {

                statement.setObject(1, projectId);

                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();

                    credentialId = rows.getObject(1, UUID.class);

                    assertThat(rows.next()).isFalse();
                }
            }

            String diagnostics =
                    stderr.toString(StandardCharsets.UTF_8);

            assertThat(diagnostics)
                    .contains(
                            "Commit outcome uncertain for credential "
                                    + credentialId,
                            "SQLState 08006",
                            "Run list for project " + projectId,
                            "issue a replacement credential",
                            "then revoke " + credentialId)
                    .doesNotContain(
                            "Credential " + credentialId + " committed",
                            "synthetic lost acknowledgment",
                            "No credential was issued")
                    .doesNotContainPattern("th_[A-Za-z0-9_-]{43}");

            // Verify the documented recovery path with the real CLI.
            ByteArrayOutputStream listStdout =
                    new ByteArrayOutputStream();
            ByteArrayOutputStream listStderr =
                    new ByteArrayOutputStream();

            int listExitCode;

            try (PrintStream out =
                            new PrintStream(
                                    listStdout, true, StandardCharsets.UTF_8);
                    PrintStream err =
                            new PrintStream(
                                    listStderr, true, StandardCharsets.UTF_8)) {

                listExitCode = CredentialAdmin.run(
                        new String[] {
                            "list", "--project", projectId.toString()
                        },
                        environment(),
                        out,
                        err);
            }

            assertThat(listExitCode).isZero();

            assertThat(listStdout.toString(StandardCharsets.UTF_8))
                    .contains(credentialId.toString())
                    .doesNotContainPattern("th_[A-Za-z0-9_-]{43}");

            assertThat(listStderr.toString(StandardCharsets.UTF_8))
                    .isEmpty();

            assertThat(underlyingConnection.get()).isNotNull();
            assertThat(underlyingConnection.get().isClosed()).isTrue();

        } finally {
            // Do not leak the real PostgreSQL connection if an
            // assertion fails before the normal close check.
            Connection actual = underlyingConnection.get();

            if (actual != null && !actual.isClosed()) {
                actual.close();
            }
        }
    }

    @Test
    void commitInterceptedBeforeServerDoesNotPersistCredential()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        insertProject(projectId, "intercepted-commit-project");

        AtomicReference<Connection> underlyingConnection =
                new AtomicReference<>();

        AtomicBoolean commitAttempted = new AtomicBoolean();
        AtomicBoolean rollbackAttempted = new AtomicBoolean();
        AtomicReference<Boolean> autoCommitAtCommit =
                new AtomicReference<>();

        CredentialAdmin.ConnectionOpener opener =
                (jdbcUrl, username, password) -> {
                    Connection actual =
                            DriverManager.getConnection(
                                    jdbcUrl, username, password);

                    underlyingConnection.set(actual);

                    return (Connection) Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, arguments) -> {
                                String methodName = method.getName();

                                if ("commit".equals(methodName)) {
                                    commitAttempted.set(true);
                                    autoCommitAtCommit.set(
                                            actual.getAutoCommit());

                                    // Never forward COMMIT to PostgreSQL.
                                    throw new SQLException(
                                            "synthetic intercepted commit",
                                            "08006");
                                }

                                if ("rollback".equals(methodName)) {
                                    rollbackAttempted.set(true);
                                }

                                try {
                                    return method.invoke(actual, arguments);
                                } catch (InvocationTargetException exception) {
                                    throw exception.getCause();
                                }
                            });
                };

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try {
            int exitCode;

            try (PrintStream out =
                            new PrintStream(
                                    stdout, true, StandardCharsets.UTF_8);
                    PrintStream err =
                            new PrintStream(
                                    stderr, true, StandardCharsets.UTF_8)) {

                exitCode = CredentialAdmin.run(
                        issueArgs(projectId),
                        environment(),
                        out,
                        err,
                        opener);
            }

            assertThat(exitCode).isEqualTo(1);
            assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();

            // Outcome first: an independent connection must see no
            // credential after the intercepted COMMIT.
            try (Connection connection = migratorConnection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    """
                                    SELECT count(*)
                                    FROM project_ingest_credentials
                                    WHERE project_id = ?
                                    """)) {

                statement.setObject(1, projectId);

                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isZero();
                }
            }

            // Mechanism second: explain why the outcome held.
            assertThat(commitAttempted.get()).isTrue();
            assertThat(autoCommitAtCommit.get()).isEqualTo(false);
            assertThat(rollbackAttempted.get()).isTrue();

            String diagnostics =
                    stderr.toString(StandardCharsets.UTF_8);

            assertThat(diagnostics)
                    .contains(
                            "SQLState 08006",
                            "Run list for project " + projectId,
                            "issue a replacement credential",
                            "then revoke ")
                    .containsPattern(
                            "Commit outcome uncertain for credential "
                                    + "[0-9a-f]{8}-[0-9a-f]{4}-"
                                    + "[0-9a-f]{4}-[0-9a-f]{4}-"
                                    + "[0-9a-f]{12}")
                    .doesNotContain(
                            "No credential was issued",
                            "synthetic intercepted commit")
                    .doesNotContainPattern(
                            "Credential [0-9a-f-]{36} committed")
                    .doesNotContainPattern("th_[A-Za-z0-9_-]{43}");

            assertThat(underlyingConnection.get()).isNotNull();
            assertThat(underlyingConnection.get().isClosed()).isTrue();

        } finally {
            // A failed assertion must not leak the real connection
            // or leave a project-row lock open.
            Connection actual = underlyingConnection.get();

            if (actual != null && !actual.isClosed()) {
                actual.close();
            }
        }
    }

    @Test
    void connectionCloseFailureAfterCommitIsWarningOnly()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        insertProject(projectId, "close-failure-project");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        AtomicReference<Connection> underlyingConnection =
                new AtomicReference<>();

        AtomicReference<String> stdoutAtClose =
                new AtomicReference<>();
        AtomicReference<String> stderrAtClose =
                new AtomicReference<>();

        AtomicBoolean commitConfirmed = new AtomicBoolean();

        CredentialAdmin.ConnectionOpener opener =
                (jdbcUrl, username, password) -> {
                    Connection actual =
                            DriverManager.getConnection(
                                    jdbcUrl, username, password);

                    underlyingConnection.set(actual);

                    return (Connection) Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, arguments) -> {
                                String methodName = method.getName();

                                try {
                                    Object result =
                                            method.invoke(actual, arguments);

                                    if ("commit".equals(methodName)) {
                                        commitConfirmed.set(true);
                                    }

                                    if ("close".equals(methodName)) {
                                        // Real connection is already closed.
                                        // Capture output present at close.
                                        stdoutAtClose.set(
                                                stdout.toString(
                                                        StandardCharsets.UTF_8));
                                        stderrAtClose.set(
                                                stderr.toString(
                                                        StandardCharsets.UTF_8));

                                        throw new SQLException(
                                                "synthetic close failure",
                                                "08006");
                                    }

                                    return result;
                                } catch (InvocationTargetException exception) {
                                    throw exception.getCause();
                                }
                            });
                };

        try {
            int exitCode;

            try (PrintStream out =
                            new PrintStream(
                                    stdout, true, StandardCharsets.UTF_8);
                    PrintStream err =
                            new PrintStream(
                                    stderr, true, StandardCharsets.UTF_8)) {

                exitCode = CredentialAdmin.run(
                        issueArgs(projectId),
                        environment(),
                        out,
                        err,
                        opener);
            }

            assertThat(exitCode).isZero();
            assertThat(commitConfirmed.get()).isTrue();

            String output =
                    stdout.toString(StandardCharsets.UTF_8);

            String token = output.stripTrailing();

            assertThat(token).matches("th_[A-Za-z0-9_-]{43}");
            assertThat(output)
                    .isEqualTo(token + System.lineSeparator());

            // Token must exist before connection.close().
            assertThat(stdoutAtClose.get()).isEqualTo(output);

            byte[] expectedHash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8));

            UUID credentialId;

            // Independent database connection confirms persistence.
            try (Connection connection = migratorConnection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    """
                                    SELECT id, key_hash, key_prefix,
                                           revoked_at
                                    FROM project_ingest_credentials
                                    WHERE project_id = ?
                                    """)) {

                statement.setObject(1, projectId);

                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();

                    credentialId = rows.getObject("id", UUID.class);

                    assertThat(rows.getBytes("key_hash"))
                            .containsExactly(expectedHash);

                    assertThat(rows.getString("key_prefix"))
                            .isEqualTo(token.substring(0, 11));

                    assertThat(rows.getObject("revoked_at")).isNull();
                    assertThat(rows.next()).isFalse();
                }
            }

            String committedMetadata =
                    "Credential " + credentialId
                            + " committed for project " + projectId;

            // Metadata precedes close; the warning follows close.
            assertThat(stderrAtClose.get())
                    .contains(committedMetadata)
                    .doesNotContain(
                            "database connection cleanup failed");

            String diagnostics =
                    stderr.toString(StandardCharsets.UTF_8);

            assertThat(diagnostics)
                    .contains(
                            committedMetadata,
                            "Credential " + credentialId
                                    + " committed; database connection "
                                    + "cleanup failed (SQLState 08006).")
                    .doesNotContain(
                            token,
                            "synthetic close failure",
                            "No credential was issued")
                    .doesNotContainPattern("th_[A-Za-z0-9_-]{43}");

            assertThat(underlyingConnection.get()).isNotNull();
            assertThat(underlyingConnection.get().isClosed()).isTrue();

        } finally {
            // Close underlying JDBC connection if an assertion fails.
            Connection actual = underlyingConnection.get();

            if (actual != null && !actual.isClosed()) {
                actual.close();
            }
        }
    }

    private String issueToken(UUID projectId) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode;

        try (PrintStream out =
                        new PrintStream(
                                stdout, true, StandardCharsets.UTF_8);
                PrintStream err =
                        new PrintStream(
                                stderr, true, StandardCharsets.UTF_8)) {

            exitCode = CredentialAdmin.run(
                    issueArgs(projectId),
                    environment(),
                    out,
                    err);
        }

        assertThat(exitCode).isZero();
        return stdout.toString(StandardCharsets.UTF_8).stripTrailing();
    }

    private static String[] issueArgs(UUID projectId) {
        return new String[] {
            "issue", "--project", projectId.toString()
        };
    }

    private Map<String, String> environment() {
        return Map.of(
                "DB_HOST", postgresContainer.getHost(),
                "DB_PORT",
                String.valueOf(postgresContainer.getMappedPort(5432)),
                "DB_NAME", postgresContainer.getDatabaseName(),
                "DB_MIGRATOR_USER", "triageharbor_migrator",
                "DB_MIGRATOR_PASSWORD", PASSWORD);
    }

    private Connection migratorConnection() throws SQLException {
        return DriverManager.getConnection(
                postgresContainer.getJdbcUrl(),
                "triageharbor_migrator",
                PASSWORD);
    }

    private void insertProject(UUID projectId, String name)
            throws SQLException {

        try (Connection connection = migratorConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                INSERT INTO projects (id, name)
                                VALUES (?, ?)
                                """)) {

            statement.setObject(1, projectId);
            statement.setString(2, name);

            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private final class CommitObservingOutputStream
            extends OutputStream {

        private final UUID projectId;
        private final ByteArrayOutputStream delegate =
                new ByteArrayOutputStream();

        private boolean firstWriteObserved;
        private boolean committedAtFirstWrite;

        private CommitObservingOutputStream(UUID projectId) {
            this.projectId = projectId;
        }

        @Override
        public void write(int value) throws IOException {
            observeCommit();
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length)
                throws IOException {

            observeCommit();
            delegate.write(bytes, offset, length);
        }

        private void observeCommit() throws IOException {
            if (firstWriteObserved) {
                return;
            }

            firstWriteObserved = true;

            try (Connection connection = migratorConnection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    """
                                    SELECT count(*)
                                    FROM project_ingest_credentials
                                    WHERE project_id = ?
                                    """)) {

                statement.setObject(1, projectId);

                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IOException(
                                "Commit observation returned no row");
                    }

                    committedAtFirstWrite = rows.getInt(1) == 1;
                }
            } catch (SQLException exception) {
                throw new IOException(
                        "Could not inspect committed issuance",
                        exception);
            }
        }

        private boolean firstWriteObserved() {
            return firstWriteObserved;
        }

        private boolean committedAtFirstWrite() {
            return committedAtFirstWrite;
        }

        private String content() {
            return delegate.toString(StandardCharsets.UTF_8);
        }
    }
}
