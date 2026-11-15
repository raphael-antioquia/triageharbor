package com.triageharbor;

import static org.assertj.core.api.Assertions.assertThat;

import com.triageharbor.support.IngestCredentialFixtures;
import com.triageharbor.support.RoleSeparatedPostgresTestConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(RoleSeparatedPostgresTestConfiguration.class)
class CredentialAdminRevokeTests {

    private static final String PASSWORD = "test-migrator-password";

    private static final DateTimeFormatter UTC_MICROS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
                    .withZone(ZoneOffset.UTC);

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Test
    void revokeOneOfTwoCredentialsLeavesSiblingActive()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID targetId;
        UUID siblingId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            targetId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            siblingId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        assertThat(revokedAt(targetId)).isNull();
        assertThat(revokedAt(siblingId)).isNull();

        Result result = invoke(projectId, targetId, false);

        // Outcome first: committed state seen from a separate session.
        assertThat(revokedAt(targetId)).isNotNull();
        assertThat(revokedAt(siblingId)).isNull();

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEmpty();

        // Exactly one receipt line: no warning, no cleanup message.
        assertThat(result.stderr())
                .isEqualTo(
                        "Credential " + targetId
                                + " revoked for project " + projectId
                                + " (name=revoke-project)."
                                + System.lineSeparator());
    }

    @Test
    void repeatedRevokePreservesOriginalTimestamp()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID targetId;
        UUID siblingA;
        UUID siblingB;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            targetId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            siblingA = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            siblingB = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        Result first = invoke(projectId, targetId, false);

        OffsetDateTime original = revokedAt(targetId);

        assertThat(original).isNotNull();
        assertThat(revokedAt(siblingA)).isNull();
        assertThat(revokedAt(siblingB)).isNull();
        assertThat(first.exitCode()).isZero();

        Result repeat = invoke(projectId, targetId, false);

        // Check committed state before interpreting the CLI result.
        assertThat(revokedAt(targetId)).isEqualTo(original);
        assertThat(revokedAt(siblingA)).isNull();
        assertThat(revokedAt(siblingB)).isNull();

        assertThat(repeat.exitCode())
                .as("stderr: %s", repeat.stderr())
                .isZero();
        assertThat(repeat.stdout()).isEmpty();
        assertThat(repeat.stderr())
                .isEqualTo(
                        "Credential " + targetId
                                + " already revoked at "
                                + UTC_MICROS.format(original.toInstant())
                                + " for project " + projectId
                                + " (name=revoke-project). No change made."
                                + System.lineSeparator());
    }

    @Test
    void alreadyRevokedSucceedsWithoutForceWhenSiblingIsLastActive()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID revokedId;
        UUID lastActiveId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            revokedId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            lastActiveId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        Result first = invoke(projectId, revokedId, false);

        OffsetDateTime original = revokedAt(revokedId);

        assertThat(original).isNotNull();
        assertThat(revokedAt(lastActiveId)).isNull();
        assertThat(first.exitCode()).isZero();

        // One active credential remains; retry without --force-last.
        Result repeat = invoke(projectId, revokedId, false);

        assertThat(revokedAt(revokedId)).isEqualTo(original);
        assertThat(revokedAt(lastActiveId)).isNull();

        assertThat(repeat.exitCode())
                .as("stderr: %s", repeat.stderr())
                .isZero();
        assertThat(repeat.stdout()).isEmpty();
        assertThat(repeat.stderr())
                .isEqualTo(
                        "Credential " + revokedId
                                + " already revoked at "
                                + UTC_MICROS.format(original.toInstant())
                                + " for project " + projectId
                                + " (name=revoke-project). No change made."
                                + System.lineSeparator());
    }

    @Test
    void credentialFromAnotherProjectIsNotFound()
            throws Exception {

        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        UUID credentialA1;
        UUID credentialA2;
        UUID credentialB;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectA);
            insertProject(connection, projectB);

            credentialA1 = credentialId(
                    connection,
                    projectA,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectA));

            credentialA2 = credentialId(
                    connection,
                    projectA,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectA));

            credentialB = credentialId(
                    connection,
                    projectB,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectB));
        }

        Result wrongProject = invoke(projectA, credentialB, false);

        // The command must not modify either project.
        assertThat(revokedAt(credentialA1))
                .as("stderr: %s", wrongProject.stderr())
                .isNull();

        assertThat(revokedAt(credentialA2))
                .as("stderr: %s", wrongProject.stderr())
                .isNull();

        assertThat(revokedAt(credentialB))
                .as("stderr: %s", wrongProject.stderr())
                .isNull();

        assertThat(wrongProject.exitCode()).isEqualTo(1);
        assertThat(wrongProject.stdout()).isEmpty();

        assertThat(wrongProject.stderr())
                .isEqualTo(
                        "Credential " + credentialB
                                + " not found in project " + projectA
                                + ". No credential was revoked."
                                + System.lineSeparator());

        // Unknown and foreign credential IDs have equivalent
        // not-found behavior.
        UUID unknownCredential = UUID.randomUUID();

        Result unknown = invoke(
                projectA, unknownCredential, false);

        assertThat(unknown.exitCode()).isEqualTo(1);
        assertThat(unknown.stdout()).isEmpty();

        assertThat(unknown.stderr())
                .isEqualTo(
                        "Credential " + unknownCredential
                                + " not found in project " + projectA
                                + ". No credential was revoked."
                                + System.lineSeparator());

        assertThat(revokedAt(credentialA1)).isNull();
        assertThat(revokedAt(credentialA2)).isNull();
        assertThat(revokedAt(credentialB)).isNull();
    }

    @Test
    void lastActiveCredentialCannotBeRevokedWithoutForce()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID targetId;
        UUID revokedSiblingId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            targetId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            revokedSiblingId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            // Establish a revoked sibling independently of the CLI.
            try (PreparedStatement revoke =
                    connection.prepareStatement(
                            """
                            UPDATE project_ingest_credentials
                            SET revoked_at = now()
                            WHERE id = ?
                              AND project_id = ?
                              AND revoked_at IS NULL
                            """)) {

                revoke.setObject(1, revokedSiblingId);
                revoke.setObject(2, projectId);

                assertThat(revoke.executeUpdate()).isEqualTo(1);
            }
        }

        OffsetDateTime siblingOriginal =
                revokedAt(revokedSiblingId);

        assertThat(revokedAt(targetId)).isNull();
        assertThat(siblingOriginal).isNotNull();

        Result result = invoke(projectId, targetId, false);

        // One active credential must remain.
        assertThat(revokedAt(targetId))
                .as("stderr: %s", result.stderr())
                .isNull();

        // The already-revoked sibling must remain unchanged.
        assertThat(revokedAt(revokedSiblingId))
                .isEqualTo(siblingOriginal);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();

        assertThat(result.stderr())
                .isEqualTo(
                        "Credential " + targetId
                                + " is the last active credential for project "
                                + projectId
                                + ". Issue a replacement credential first, or re-run "
                                + "with --force-last to leave the project without an "
                                + "active credential. No credential was revoked."
                                + System.lineSeparator());
    }

    @Test
    void forceLastRevokesFinalActiveCredentialWithWarning()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID targetId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            targetId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        assertThat(revokedAt(targetId)).isNull();
        assertThat(activeCredentialCount(projectId)).isEqualTo(1);

        Result result = invoke(projectId, targetId, true);

        // Explicit override: the final active key is revoked.
        assertThat(revokedAt(targetId))
                .as("stderr: %s", result.stderr())
                .isNotNull();

        assertThat(activeCredentialCount(projectId))
                .as("stderr: %s", result.stderr())
                .isZero();

        assertThat(result.exitCode())
                .as("stderr: %s", result.stderr())
                .isZero();

        assertThat(result.stdout()).isEmpty();

        // Exactly two lines: success receipt followed by WARNING.
        assertThat(result.stderr())
                .isEqualTo(
                        "Credential " + targetId
                                + " revoked for project " + projectId
                                + " (name=revoke-project)."
                                + System.lineSeparator()
                                + "WARNING: project " + projectId
                                + " now has no active ingest credential; "
                                + "ingestion fails until a new credential "
                                + "is issued."
                                + System.lineSeparator());
    }

    @Test
    void unnecessaryForceLastRevokesWithoutZeroKeyWarning()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID targetId;
        UUID siblingId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            targetId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            siblingId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        assertThat(activeCredentialCount(projectId)).isEqualTo(2);

        Result result = invoke(projectId, targetId, true);

        // One sibling remains active despite the force flag.
        assertThat(revokedAt(targetId))
                .as("stderr: %s", result.stderr())
                .isNotNull();

        assertThat(revokedAt(siblingId))
                .as("stderr: %s", result.stderr())
                .isNull();

        assertThat(activeCredentialCount(projectId))
                .as("stderr: %s", result.stderr())
                .isEqualTo(1);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEmpty();

        // Exactly one receipt line: no zero-key WARNING.
        assertThat(result.stderr())
                .isEqualTo(
                        "Credential " + targetId
                                + " revoked for project " + projectId
                                + " (name=revoke-project)."
                                + System.lineSeparator());
    }

    @Test
    void issuingReplacementBeforeRevokePreservesActiveCredential()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID oldCredentialId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            oldCredentialId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        // Initial state: exactly one active credential.
        assertThat(revokedAt(oldCredentialId)).isNull();
        assertThat(activeCredentialCount(projectId)).isEqualTo(1);

        // Issue a replacement through the real admin CLI.
        Result issued = execute(
                "issue", "--project", projectId.toString());

        assertThat(issued.exitCode())
                .as("Credential issuance must succeed")
                .isZero();

        String issueStdout = issued.stdout();

        // Verify the entire stdout contract without printing
        // the raw token in an assertion failure.
        assertThat(issueStdout.matches(
                "th_[A-Za-z0-9_-]{43}"
                        + Pattern.quote(System.lineSeparator())))
                .as("Issue stdout must contain exactly one canonical token")
                .isTrue();

        // Three-character prefix plus 43 base64url characters.
        String replacementToken = issueStdout.substring(0, 46);

        UUID replacementId;

        try (Connection connection = migratorConnection()) {
            replacementId = credentialId(
                    connection, projectId, replacementToken);
        }

        assertThat(replacementId).isNotEqualTo(oldCredentialId);

        // Replacement must be committed and active before
        // the original credential is revoked.
        assertThat(revokedAt(oldCredentialId)).isNull();

        assertThat(revokedAt(replacementId))
                .as("Replacement must be active before old-key revocation")
                .isNull();

        assertThat(activeCredentialCount(projectId)).isEqualTo(2);

        String expectedIssueReceipt =
                "Credential " + replacementId
                        + " committed for project " + projectId
                        + " (name=revoke-project)."
                        + System.lineSeparator();

        // Avoid displaying unexpected sensitive CLI output on failure.
        assertThat(issued.stderr().equals(expectedIssueReceipt))
                .as("Issue stderr must contain only the metadata receipt")
                .isTrue();

        // No force flag: the replacement makes this an ordinary revoke.
        Result revoked = invoke(
                projectId, oldCredentialId, false);

        // Verify committed database state first.
        assertThat(revokedAt(oldCredentialId))
                .as("stderr: %s", revoked.stderr())
                .isNotNull();

        assertThat(revokedAt(replacementId))
                .as("stderr: %s", revoked.stderr())
                .isNull();

        assertThat(activeCredentialCount(projectId))
                .as("stderr: %s", revoked.stderr())
                .isEqualTo(1);

        assertThat(revoked.exitCode())
                .as("stderr: %s", revoked.stderr())
                .isZero();

        assertThat(revoked.stdout()).isEmpty();

        assertThat(revoked.stderr())
                .isEqualTo(
                        "Credential " + oldCredentialId
                                + " revoked for project " + projectId
                                + " (name=revoke-project)."
                                + System.lineSeparator());
    }

    @Test
    @Timeout(value = 35, unit = TimeUnit.SECONDS)
    void revokeTimesOutOnHeldProjectLockWithoutChangingCredentials()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID targetId;
        UUID siblingId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            targetId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            siblingId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        // Two active keys: the last-key guard cannot reject this revoke.
        assertThat(activeCredentialCount(projectId)).isEqualTo(2);

        ExecutorService executor =
                Executors.newSingleThreadExecutor();

        try (Connection lockHolder = migratorConnection()) {
            lockHolder.setAutoCommit(false);

            try {
                // Hold the same project-row lock revoke needs.
                try (PreparedStatement statement =
                        lockHolder.prepareStatement(
                                """
                                SELECT id
                                FROM projects
                                WHERE id = ?
                                FOR NO KEY UPDATE
                                """)) {

                    statement.setObject(1, projectId);

                    try (ResultSet rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();

                        assertThat(rows.getObject(1, UUID.class))
                                .isEqualTo(projectId);

                        assertThat(rows.next()).isFalse();
                    }
                }

                // Autocommit is off, so the lock remains held.
                long startedAt = System.nanoTime();

                Future<Result> pending =
                        executor.submit(
                                () -> invoke(projectId, targetId, false));

                // Fail independently if PostgreSQL does not return.
                Result result = pending.get(15, TimeUnit.SECONDS);

                long elapsedMillis =
                        TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - startedAt);

                // Committed state first, from separate sessions.
                assertThat(revokedAt(targetId))
                        .as("Target must remain active")
                        .isNull();

                assertThat(revokedAt(siblingId))
                        .as("Sibling must remain active")
                        .isNull();

                assertThat(result.exitCode()).isEqualTo(1);
                assertThat(result.stdout()).isEmpty();

                assertThat(result.stderr())
                        .isEqualTo(
                                "Credential revocation failed "
                                        + "(SQLState 55P03). "
                                        + "No change was committed."
                                        + System.lineSeparator());

                // Waited for lock_timeout rather than failing fast.
                assertThat(elapsedMillis)
                        .isBetween(3500L, 14999L);

            } finally {
                // Release the project lock even if the worker
                // times out or an assertion fails.
                lockHolder.rollback();
            }

        } finally {
            executor.shutdown();

            // Allow a previously blocked worker to finish
            // after the lock-holder transaction is released.
            if (!executor.awaitTermination(
                    10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrentRevokesOfLastTwoCredentialsLeaveOneActive()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID firstCredentialId;
        UUID secondCredentialId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            firstCredentialId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            secondCredentialId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        assertThat(activeCredentialCount(projectId)).isEqualTo(2);

        CountDownLatch firstReachedUpdate = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondConnected = new CountDownLatch(1);

        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();
        AtomicInteger hookCalls = new AtomicInteger();

        AtomicReference<String> firstIsolation =
                new AtomicReference<>();

        CredentialAdmin.ConnectionOpener firstOpener =
                (url, username, password) ->
                        pauseBeforeCredentialUpdate(
                                openWithBackendPid(
                                        url, username, password, firstPid),
                                hookCalls,
                                firstIsolation,
                                firstReachedUpdate,
                                releaseFirst);

        CredentialAdmin.ConnectionOpener secondOpener =
                (url, username, password) -> {
                    Connection actual = openWithBackendPid(
                            url, username, password, secondPid);

                    secondConnected.countDown();
                    return actual;
                };

        ExecutorService executor = Executors.newFixedThreadPool(2);

        boolean secondBlockedByFirst = false;
        Result firstResult;
        Result secondResult;

        try {
            // A holds the project lock, has counted two keys,
            // and pauses before its UPDATE.
            Future<Result> first = executor.submit(
                    () -> invokeWithOpener(
                            projectId, firstCredentialId, firstOpener));

            assertThat(firstReachedUpdate.await(15, TimeUnit.SECONDS))
                    .as("First revoke must reach its UPDATE")
                    .isTrue();

            // B attempts to revoke the other active credential.
            Future<Result> second = executor.submit(
                    () -> invokeWithOpener(
                            projectId, secondCredentialId, secondOpener));

            assertThat(secondConnected.await(15, TimeUnit.SECONDS))
                    .as("Second revoke must open its connection")
                    .isTrue();

            // Record instead of asserting here: the outcome
            // must remain the primary concurrency evidence.
            // Keep this short because B has a 5-second lock timeout.
            secondBlockedByFirst = waitForProjectLockBlock(
                    secondPid.get(), firstPid.get());

            // A can now UPDATE and COMMIT. B then resumes.
            releaseFirst.countDown();

            firstResult = first.get(12, TimeUnit.SECONDS);
            secondResult = second.get(12, TimeUnit.SECONDS);

        } finally {
            // Release A even if observation or an assertion fails.
            releaseFirst.countDown();

            executor.shutdown();

            if (!executor.awaitTermination(
                    10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }

        String mechanism = "blocked=" + secondBlockedByFirst
                + ", isolation=" + firstIsolation.get()
                + ", hookCalls=" + hookCalls.get()
                + ", firstPid=" + firstPid.get()
                + ", secondPid=" + secondPid.get();

        // PRIMARY EVIDENCE: committed state from separate sessions.
        assertThat(revokedAt(firstCredentialId))
                .as("First credential must be revoked; %s", mechanism)
                .isNotNull();

        assertThat(revokedAt(secondCredentialId))
                .as("Second credential must remain active; %s", mechanism)
                .isNull();

        assertThat(activeCredentialCount(projectId))
                .as("Unforced revokes must leave one key; %s", mechanism)
                .isEqualTo(1);

        // SECONDARY EVIDENCE: exact operator contract.
        assertThat(firstResult.exitCode())
                .as("First revoke must succeed; %s", mechanism)
                .isZero();

        assertThat(firstResult.stdout()).isEmpty();

        assertThat(firstResult.stderr())
                .isEqualTo(
                        "Credential " + firstCredentialId
                                + " revoked for project " + projectId
                                + " (name=revoke-project)."
                                + System.lineSeparator());

        assertThat(secondResult.exitCode())
                .as("Second revoke must reject the final key; %s", mechanism)
                .isEqualTo(1);

        assertThat(secondResult.stdout()).isEmpty();

        assertThat(secondResult.stderr())
                .isEqualTo(
                        "Credential " + secondCredentialId
                                + " is the last active credential for project "
                                + projectId
                                + ". Issue a replacement credential first, or re-run "
                                + "with --force-last to leave the project without an "
                                + "active credential. No credential was revoked."
                                + System.lineSeparator());

        // MECHANISM EVIDENCE: checked after committed state.
        assertThat(hookCalls.get())
                .as(mechanism)
                .isEqualTo(1);

        assertThat(firstIsolation.get())
                .as(mechanism)
                .isEqualTo("read committed");

        assertThat(firstPid.get()).isPositive();
        assertThat(secondPid.get()).isPositive();
        assertThat(secondPid.get()).isNotEqualTo(firstPid.get());

        assertThat(secondBlockedByFirst)
                .as("Backend %s must wait on backend %s's project-row lock",
                        secondPid.get(), firstPid.get())
                .isTrue();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void revokeDoesNotOverwriteOutOfBandRevocation()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID targetId;
        UUID siblingId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            targetId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            siblingId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        assertThat(activeCredentialCount(projectId)).isEqualTo(2);
        assertThat(revokedAt(targetId)).isNull();
        assertThat(revokedAt(siblingId)).isNull();

        // Fixed timestamp distinguishes the external write
        // from any later CLI overwrite using now().
        OffsetDateTime externalRevokedAt =
                OffsetDateTime.parse("2020-01-02T03:04:05.123456Z");

        AtomicInteger hookCalls = new AtomicInteger();
        AtomicInteger externalRows = new AtomicInteger();

        AtomicReference<String> lockProbeState =
                new AtomicReference<>();

        AtomicReference<Connection> underlyingConnection =
                new AtomicReference<>();

        CredentialAdmin.ConnectionOpener opener =
                (url, username, password) -> {

                    Connection actual =
                            DriverManager.getConnection(
                                    url, username, password);

                    underlyingConnection.set(actual);

                    return (Connection) Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, arguments) -> {

                                if ("prepareStatement".equals(
                                                method.getName())
                                        && arguments != null
                                        && arguments.length > 0
                                        && arguments[0] instanceof String sql
                                        && sql.contains(
                                                "UPDATE project_ingest_credentials")
                                        && sql.contains(
                                                "SET revoked_at = now()")) {

                                    hookCalls.incrementAndGet();

                                    // The CLI has acquired the project
                                    // lock and passed its last-key guard.
                                    // Independently probe that lock.
                                    try (Connection probe =
                                                    migratorConnection();
                                            PreparedStatement lock =
                                                    probe.prepareStatement(
                                                            """
                                                            SELECT id
                                                            FROM projects
                                                            WHERE id = ?
                                                            FOR NO KEY UPDATE NOWAIT
                                                            """)) {

                                        lock.setObject(1, projectId);

                                        try (ResultSet rows =
                                                lock.executeQuery()) {

                                            lockProbeState.set(
                                                    rows.next()
                                                            ? "lock acquired"
                                                            : "project missing");
                                        }

                                    } catch (SQLException exception) {
                                        lockProbeState.set(
                                                exception.getSQLState());
                                    }

                                    // Deliberately bypass the operator's
                                    // project-lock protocol. Only the
                                    // credential row is updated.
                                    //
                                    // Autocommit makes the external
                                    // mutation visible before CLI resumes.
                                    try (Connection external =
                                                    migratorConnection();
                                            PreparedStatement update =
                                                    external.prepareStatement(
                                                            """
                                                            UPDATE project_ingest_credentials
                                                            SET revoked_at = ?
                                                            WHERE id = ?
                                                              AND project_id = ?
                                                              AND revoked_at IS NULL
                                                            """)) {

                                        update.setQueryTimeout(3);
                                        update.setObject(
                                                1, externalRevokedAt);
                                        update.setObject(2, targetId);
                                        update.setObject(3, projectId);

                                        externalRows.set(
                                                update.executeUpdate());
                                    }
                                }

                                try {
                                    return method.invoke(
                                            actual, arguments);
                                } catch (
                                        InvocationTargetException exception) {

                                    throw exception.getCause();
                                }
                            });
                };

        Result result;

        try {
            result = invokeWithOpener(
                    projectId, targetId, opener);

        } finally {
            // Do not leak the real JDBC connection if
            // an injected failure bypasses normal cleanup.
            Connection actual = underlyingConnection.get();

            if (actual != null && !actual.isClosed()) {
                actual.close();
            }
        }

        String mechanism =
                "hookCalls=" + hookCalls.get()
                        + ", externalRows=" + externalRows.get()
                        + ", lockProbe=" + lockProbeState.get();

        // PRIMARY: independent committed database state.
        // The CLI must not overwrite the external revocation.
        assertThat(revokedAt(targetId))
                .as("External revocation timestamp must survive; %s",
                        mechanism)
                .isEqualTo(externalRevokedAt);

        assertThat(revokedAt(siblingId))
                .as("Sibling must remain active; %s", mechanism)
                .isNull();

        assertThat(activeCredentialCount(projectId))
                .as("Exactly one credential must remain; %s",
                        mechanism)
                .isEqualTo(1);

        // SECONDARY: the CLI must not report a successful revoke.
        assertThat(result.exitCode())
                .as("Zero-row UPDATE must fail; %s", mechanism)
                .isEqualTo(1);

        assertThat(result.stdout()).isEmpty();

        assertThat(result.stderr())
                .isEqualTo(
                        "Credential revocation failed "
                                + "(SQLState XX000). "
                                + "No change was committed."
                                + System.lineSeparator());

        // MECHANISM: the race occurred at the
        // conditional UPDATE boundary.
        assertThat(hookCalls.get()).isEqualTo(1);
        assertThat(externalRows.get()).isEqualTo(1);

        // The project lock was still held by the CLI.
        assertThat(lockProbeState.get()).isEqualTo("55P03");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void revokeDoesNotRevokeCredentialMovedToAnotherProject()
            throws Exception {

        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        UUID targetId;
        UUID siblingId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectA);
            insertProject(connection, projectB);

            targetId = credentialId(
                    connection,
                    projectA,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectA));

            siblingId = credentialId(
                    connection,
                    projectA,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectA));
        }

        assertThat(activeCredentialCount(projectA)).isEqualTo(2);
        assertThat(activeCredentialCount(projectB)).isZero();

        assertThat(credentialProjectId(targetId)).isEqualTo(projectA);
        assertThat(revokedAt(targetId)).isNull();
        assertThat(revokedAt(siblingId)).isNull();

        AtomicInteger hookCalls = new AtomicInteger();
        AtomicInteger externalRows = new AtomicInteger();

        AtomicReference<String> lockProbeState =
                new AtomicReference<>();

        AtomicReference<String> externalSqlState =
                new AtomicReference<>();

        AtomicReference<Connection> underlyingConnection =
                new AtomicReference<>();

        CredentialAdmin.ConnectionOpener opener =
                (url, username, password) -> {

                    Connection actual =
                            DriverManager.getConnection(
                                    url, username, password);

                    underlyingConnection.set(actual);

                    return (Connection) Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, arguments) -> {

                                if ("prepareStatement".equals(
                                                method.getName())
                                        && arguments != null
                                        && arguments.length > 0
                                        && arguments[0] instanceof String sql
                                        && sql.contains(
                                                "UPDATE project_ingest_credentials")
                                        && sql.contains(
                                                "SET revoked_at = now()")) {

                                    hookCalls.incrementAndGet();

                                    // The CLI has locked project A,
                                    // looked up the target, and passed
                                    // the last-active-credential guard.
                                    try (Connection probe =
                                                    migratorConnection();
                                            PreparedStatement lock =
                                                    probe.prepareStatement(
                                                            """
                                                            SELECT id
                                                            FROM projects
                                                            WHERE id = ?
                                                            FOR NO KEY UPDATE NOWAIT
                                                            """)) {

                                        lock.setObject(1, projectA);

                                        try (ResultSet rows =
                                                        lock.executeQuery()) {

                                            lockProbeState.set(
                                                    rows.next()
                                                            ? "lock acquired"
                                                            : "project missing");
                                        }

                                    } catch (SQLException exception) {
                                        lockProbeState.set(
                                                exception.getSQLState());
                                    }

                                    // Bypass the operator-lock protocol.
                                    // Autocommit makes the move visible
                                    // before the CLI resumes its UPDATE.
                                    try (Connection external =
                                                    migratorConnection();
                                            PreparedStatement move =
                                                    external.prepareStatement(
                                                            """
                                                            UPDATE project_ingest_credentials
                                                            SET project_id = ?
                                                            WHERE id = ?
                                                              AND project_id = ?
                                                              AND revoked_at IS NULL
                                                            """)) {

                                        move.setQueryTimeout(3);
                                        move.setObject(1, projectB);
                                        move.setObject(2, targetId);
                                        move.setObject(3, projectA);

                                        externalRows.set(
                                                move.executeUpdate());

                                    } catch (SQLException exception) {
                                        externalSqlState.set(
                                                exception.getSQLState());
                                        throw exception;
                                    }
                                }

                                try {
                                    return method.invoke(
                                            actual, arguments);
                                } catch (
                                        InvocationTargetException exception) {

                                    throw exception.getCause();
                                }
                            });
                };

        Result result;

        try {
            result = invokeWithOpener(
                    projectA, targetId, opener);

        } finally {
            // Do not leak the physical JDBC connection if
            // injected failure bypasses the CLI cleanup.
            Connection actual = underlyingConnection.get();

            if (actual != null && !actual.isClosed()) {
                actual.close();
            }
        }

        String mechanism =
                "hookCalls=" + hookCalls.get()
                        + ", externalRows=" + externalRows.get()
                        + ", lockProbe=" + lockProbeState.get()
                        + ", externalSqlState=" + externalSqlState.get()
                        + ", cliExit=" + result.exitCode()
                        + ", cliStderr=" + result.stderr().strip();

        // PRIMARY: independent committed database state.
        assertThat(credentialProjectId(targetId))
                .as("Target must have moved to B; %s", mechanism)
                .isEqualTo(projectB);

        assertThat(revokedAt(targetId))
                .as("Moved credential must remain active; %s", mechanism)
                .isNull();

        assertThat(credentialProjectId(siblingId))
                .as("Sibling must remain in A; %s", mechanism)
                .isEqualTo(projectA);

        assertThat(revokedAt(siblingId))
                .as("Sibling must remain active; %s", mechanism)
                .isNull();

        assertThat(activeCredentialCount(projectA))
                .as("A must retain its sibling; %s", mechanism)
                .isEqualTo(1);

        assertThat(activeCredentialCount(projectB))
                .as("B must own one active credential; %s", mechanism)
                .isEqualTo(1);

        // SECONDARY: exact CLI failure contract.
        assertThat(result.exitCode())
                .as("Scoped UPDATE must fail; %s", mechanism)
                .isEqualTo(1);

        assertThat(result.stdout()).isEmpty();

        assertThat(result.stderr())
                .isEqualTo(
                        "Credential revocation failed "
                                + "(SQLState XX000). "
                                + "No change was committed."
                                + System.lineSeparator());

        // MECHANISM: the move happened while the CLI held
        // the project lock, before its conditional UPDATE.
        assertThat(hookCalls.get())
                .as(mechanism)
                .isEqualTo(1);

        assertThat(externalRows.get())
                .as(mechanism)
                .isEqualTo(1);

        assertThat(externalSqlState.get())
                .as(mechanism)
                .isNull();

        assertThat(lockProbeState.get())
                .as(mechanism)
                .isEqualTo("55P03");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void revokeCommitSucceedsButAcknowledgmentIsLost()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID targetId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            targetId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        assertThat(activeCredentialCount(projectId)).isEqualTo(1);
        assertThat(revokedAt(targetId)).isNull();

        AtomicInteger commitCalls = new AtomicInteger();
        AtomicInteger successfulCommits = new AtomicInteger();
        AtomicInteger rollbackCalls = new AtomicInteger();
        AtomicInteger rollbackFailures = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();

        AtomicReference<SQLException> commitException =
                new AtomicReference<>();

        AtomicReference<Connection> underlyingConnection =
                new AtomicReference<>();

        CredentialAdmin.ConnectionOpener opener =
                (url, username, password) -> {

                    Connection actual =
                            DriverManager.getConnection(
                                    url, username, password);

                    underlyingConnection.set(actual);

                    return (Connection) Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, arguments) -> {

                                String methodName = method.getName();

                                if ("commit".equals(methodName)) {
                                    commitCalls.incrementAndGet();

                                    // The real PostgreSQL COMMIT succeeds.
                                    try {
                                        method.invoke(actual, arguments);
                                    } catch (
                                            InvocationTargetException exception) {
                                        throw exception.getCause();
                                    }

                                    successfulCommits.incrementAndGet();

                                    // Simulate losing the acknowledgment
                                    // when the connection becomes unusable.
                                    actual.close();

                                    SQLException lostAcknowledgment =
                                            new SQLException(
                                                    "Synthetic lost acknowledgment",
                                                    "08006");

                                    commitException.set(
                                            lostAcknowledgment);

                                    throw lostAcknowledgment;
                                }

                                if ("rollback".equals(methodName)) {
                                    rollbackCalls.incrementAndGet();

                                    // Forward rollback to the closed
                                    // physical connection. It should fail.
                                    try {
                                        return method.invoke(
                                                actual, arguments);
                                    } catch (
                                            InvocationTargetException exception) {

                                        rollbackFailures.incrementAndGet();
                                        throw exception.getCause();
                                    }
                                }

                                if ("close".equals(methodName)) {
                                    closeCalls.incrementAndGet();

                                    // The physical connection was already
                                    // closed after COMMIT. Inject a
                                    // cleanup error to verify suppression.
                                    throw new SQLException(
                                            "Synthetic close failure",
                                            "08003");
                                }

                                try {
                                    return method.invoke(
                                            actual, arguments);
                                } catch (
                                        InvocationTargetException exception) {

                                    throw exception.getCause();
                                }
                            });
                };

        Result first;

        try {
            ByteArrayOutputStream stdout =
                    new ByteArrayOutputStream();

            ByteArrayOutputStream stderr =
                    new ByteArrayOutputStream();

            int exitCode;

            try (PrintStream out = new PrintStream(
                            stdout, true, StandardCharsets.UTF_8);
                    PrintStream err = new PrintStream(
                            stderr, true, StandardCharsets.UTF_8)) {

                exitCode = CredentialAdmin.run(
                        new String[] {
                            "revoke",
                            "--project", projectId.toString(),
                            "--credential", targetId.toString(),
                            "--force-last"
                        },
                        environment(),
                        out,
                        err,
                        opener);
            }

            first = new Result(
                    exitCode,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));

        } finally {
            // Avoid leaking the physical connection if an
            // unexpected failure occurs before cleanup.
            Connection actual = underlyingConnection.get();

            if (actual != null && !actual.isClosed()) {
                actual.close();
            }
        }

        String mechanism =
                "commitCalls=" + commitCalls.get()
                        + ", successfulCommits="
                        + successfulCommits.get()
                        + ", rollbackCalls=" + rollbackCalls.get()
                        + ", rollbackFailures="
                        + rollbackFailures.get()
                        + ", closeCalls=" + closeCalls.get()
                        + ", firstExit=" + first.exitCode()
                        + ", firstStderr=" + first.stderr().strip();

        // PRIMARY: PostgreSQL committed the revocation,
        // despite the synthetic acknowledgment failure.
        OffsetDateTime originalRevokedAt = revokedAt(targetId);

        assertThat(originalRevokedAt)
                .as("Revocation must be committed; %s", mechanism)
                .isNotNull();

        assertThat(activeCredentialCount(projectId))
                .as("Forced revoke must leave zero active keys; %s",
                        mechanism)
                .isZero();

        // SECONDARY: no false success or definite rollback claim.
        assertThat(first.exitCode())
                .as(mechanism)
                .isEqualTo(1);

        assertThat(first.stdout()).isEmpty();

        assertThat(first.stderr())
                .as(mechanism)
                .isEqualTo(
                        "Revocation commit outcome uncertain for credential "
                                + targetId
                                + " (SQLState 08006). "
                                + "The credential may still be active. "
                                + "Re-run the identical revoke command, "
                                + "including --force-last. "
                                + "If the revocation committed, project "
                                + projectId
                                + " has no active credential."
                                + System.lineSeparator());

        // MECHANISM: COMMIT succeeded before the failure.
        assertThat(commitCalls.get())
                .as(mechanism)
                .isEqualTo(1);

        assertThat(successfulCommits.get())
                .as(mechanism)
                .isEqualTo(1);

        assertThat(underlyingConnection.get()).isNotNull();

        assertThat(underlyingConnection.get().isClosed())
                .isTrue();

        // Both cleanup attempts must occur and fail.
        assertThat(rollbackCalls.get())
                .as(mechanism)
                .isEqualTo(1);

        assertThat(rollbackFailures.get())
                .as(mechanism)
                .isEqualTo(1);

        assertThat(closeCalls.get())
                .as(mechanism)
                .isEqualTo(1);

        // Both cleanup failures must be suppressed onto
        // the ORIGINAL commit exception, not replace it.
        SQLException originalError = commitException.get();

        assertThat((Throwable) originalError).isNotNull();

        assertThat(originalError.getSQLState())
                .isEqualTo("08006");

        assertThat(originalError.getSuppressed())
                .as("Rollback and close errors must be suppressed")
                .hasSize(2);

        assertThat(originalError.getSuppressed()[0])
                .isInstanceOf(SQLException.class);

        assertThat(originalError.getSuppressed()[1])
                .isInstanceOf(SQLException.class);

        // RECOVERY: retry the identical command, including
        // the original --force-last flag.
        Result retry = invoke(projectId, targetId, true);

        // Already-revoked success must preserve the timestamp.
        assertThat(revokedAt(targetId))
                .as("Retry must preserve original timestamp")
                .isEqualTo(originalRevokedAt);

        assertThat(activeCredentialCount(projectId)).isZero();

        assertThat(retry.exitCode())
                .as("retry stderr: %s", retry.stderr())
                .isZero();

        assertThat(retry.stdout()).isEmpty();

        // No second zero-key warning on an idempotent retry.
        assertThat(retry.stderr())
                .isEqualTo(
                        "Credential " + targetId
                                + " already revoked at "
                                + UTC_MICROS.format(
                                        originalRevokedAt.toInstant())
                                + " for project " + projectId
                                + " (name=revoke-project). No change made."
                                + System.lineSeparator());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void revokeCommitInterceptedBeforeServerStillReportsUncertainty()
            throws Exception {

        UUID projectId = UUID.randomUUID();
        UUID targetId;
        UUID siblingId;

        try (Connection connection = migratorConnection()) {
            insertProject(connection, projectId);

            targetId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));

            siblingId = credentialId(
                    connection,
                    projectId,
                    IngestCredentialFixtures.insertForProject(
                            connection, projectId));
        }

        assertThat(activeCredentialCount(projectId)).isEqualTo(2);
        assertThat(revokedAt(targetId)).isNull();
        assertThat(revokedAt(siblingId)).isNull();

        AtomicInteger commitAttempts = new AtomicInteger();
        AtomicInteger rollbackAttempts = new AtomicInteger();
        AtomicInteger closeAttempts = new AtomicInteger();

        AtomicReference<Boolean> autoCommitAtCommit =
                new AtomicReference<>();

        AtomicReference<Connection> underlyingConnection =
                new AtomicReference<>();

        CredentialAdmin.ConnectionOpener opener =
                (url, username, password) -> {

                    Connection actual =
                            DriverManager.getConnection(
                                    url, username, password);

                    underlyingConnection.set(actual);

                    return (Connection) Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, arguments) -> {

                                String methodName = method.getName();

                                if ("commit".equals(methodName)) {
                                    commitAttempts.incrementAndGet();

                                    autoCommitAtCommit.set(
                                            actual.getAutoCommit());

                                    // Do not forward COMMIT to PostgreSQL.
                                    // The client still cannot know whether
                                    // a real commit was received.
                                    throw new SQLException(
                                            "Synthetic intercepted COMMIT",
                                            "40001");
                                }

                                if ("rollback".equals(methodName)) {
                                    rollbackAttempts.incrementAndGet();
                                }

                                if ("close".equals(methodName)) {
                                    closeAttempts.incrementAndGet();
                                }

                                try {
                                    return method.invoke(
                                            actual, arguments);
                                } catch (
                                        InvocationTargetException exception) {

                                    throw exception.getCause();
                                }
                            });
                };

        Result first;

        try {
            // No --force-last: two active credentials exist.
            first = invokeWithOpener(
                    projectId, targetId, opener);

        } finally {
            Connection actual = underlyingConnection.get();

            if (actual != null && !actual.isClosed()) {
                actual.close();
            }
        }

        String mechanism =
                "commitAttempts=" + commitAttempts.get()
                        + ", rollbackAttempts="
                        + rollbackAttempts.get()
                        + ", closeAttempts=" + closeAttempts.get()
                        + ", autoCommitAtCommit="
                        + autoCommitAtCommit.get()
                        + ", firstExit=" + first.exitCode()
                        + ", firstStderr=" + first.stderr().strip();

        // PRIMARY: COMMIT never reached the database.
        // The CLI must have rolled back the pending UPDATE.
        assertThat(revokedAt(targetId))
                .as("Target must remain active; %s", mechanism)
                .isNull();

        assertThat(revokedAt(siblingId))
                .as("Sibling must remain active; %s", mechanism)
                .isNull();

        assertThat(activeCredentialCount(projectId))
                .as("Both credentials must remain active; %s",
                        mechanism)
                .isEqualTo(2);

        // SECONDARY: even though this particular test knows
        // COMMIT was intercepted, the CLI must report uncertainty.
        assertThat(first.exitCode())
                .as(mechanism)
                .isEqualTo(1);

        assertThat(first.stdout()).isEmpty();

        assertThat(first.stderr())
                .as(mechanism)
                .isEqualTo(
                        "Revocation commit outcome uncertain for credential "
                                + targetId
                                + " (SQLState 40001). "
                                + "The credential may still be active. "
                                + "Re-run the identical revoke command. "
                                + "If the last-credential guard rejects "
                                + "the retry, issue a replacement "
                                + "credential, then revoke "
                                + targetId + "."
                                + System.lineSeparator());

        // MECHANISM: one COMMIT attempt, never forwarded,
        // followed by rollback and connection cleanup.
        assertThat(commitAttempts.get())
                .as(mechanism)
                .isEqualTo(1);

        assertThat(autoCommitAtCommit.get())
                .as(mechanism)
                .isEqualTo(false);

        assertThat(rollbackAttempts.get())
                .as(mechanism)
                .isEqualTo(1);

        assertThat(closeAttempts.get())
                .as(mechanism)
                .isEqualTo(1);

        assertThat(underlyingConnection.get()).isNotNull();

        assertThat(underlyingConnection.get().isClosed())
                .isTrue();

        // RECOVERY: retry the identical command.
        // This time the target is still active, so the retry
        // must perform the revocation rather than report
        // an already-revoked success.
        Result retry = invoke(projectId, targetId, false);

        assertThat(revokedAt(targetId))
                .as("Retry must revoke target")
                .isNotNull();

        assertThat(revokedAt(siblingId))
                .as("Retry must preserve sibling")
                .isNull();

        assertThat(activeCredentialCount(projectId))
                .isEqualTo(1);

        assertThat(retry.exitCode())
                .as("retry stderr: %s", retry.stderr())
                .isZero();

        assertThat(retry.stdout()).isEmpty();

        assertThat(retry.stderr())
                .isEqualTo(
                        "Credential " + targetId
                                + " revoked for project " + projectId
                                + " (name=revoke-project)."
                                + System.lineSeparator());
    }

    private Result invokeWithOpener(
            UUID projectId,
            UUID credentialId,
            CredentialAdmin.ConnectionOpener opener) {

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode;

        try (PrintStream out = new PrintStream(
                        stdout, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(
                        stderr, true, StandardCharsets.UTF_8)) {

            exitCode = CredentialAdmin.run(
                    new String[] {
                        "revoke",
                        "--project", projectId.toString(),
                        "--credential", credentialId.toString()
                    },
                    environment(),
                    out,
                    err,
                    opener);
        }

        return new Result(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private Connection openWithBackendPid(
            String url,
            String username,
            String password,
            AtomicInteger backendPid) throws SQLException {

        Connection actual =
                DriverManager.getConnection(url, username, password);

        try {
            try (PreparedStatement statement =
                            actual.prepareStatement(
                                    "SELECT pg_backend_pid()");
                    ResultSet rows = statement.executeQuery()) {

                if (!rows.next()) {
                    throw new SQLException(
                            "PostgreSQL backend PID unavailable",
                            "XX000");
                }

                backendPid.set(rows.getInt(1));

                if (rows.next()) {
                    throw new SQLException(
                            "Unexpected backend PID result",
                            "XX000");
                }
            }

            return actual;

        } catch (SQLException | RuntimeException exception) {
            try {
                actual.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }

            throw exception;
        }
    }

    private Connection pauseBeforeCredentialUpdate(
            Connection actual,
            AtomicInteger hookCalls,
            AtomicReference<String> isolation,
            CountDownLatch reachedUpdate,
            CountDownLatch releaseUpdate) {

        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {

                    if ("prepareStatement".equals(method.getName())
                            && arguments != null
                            && arguments.length > 0
                            && arguments[0] instanceof String sql
                            && sql.contains(
                                    "UPDATE project_ingest_credentials")
                            && sql.contains(
                                    "SET revoked_at = now()")) {

                        hookCalls.incrementAndGet();

                        // Inspect isolation on A's actual connection,
                        // inside the transaction after the count.
                        try (Statement show = actual.createStatement();
                                ResultSet rows = show.executeQuery(
                                        "SHOW transaction_isolation")) {

                            isolation.set(
                                    rows.next() ? rows.getString(1) : null);
                        }

                        // Signal that A has passed its last-key guard.
                        reachedUpdate.countDown();

                        try {
                            if (!releaseUpdate.await(
                                    20, TimeUnit.SECONDS)) {
                                throw new SQLException(
                                        "First revoke test gate timed out",
                                        "57014");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();

                            throw new SQLException(
                                    "First revoke test gate interrupted",
                                    "57014",
                                    exception);
                        }
                    }

                    try {
                        return method.invoke(actual, arguments);
                    } catch (InvocationTargetException exception) {
                        // Preserve the original JDBC exception.
                        throw exception.getCause();
                    }
                });
    }

    private boolean waitForProjectLockBlock(
            int blockedPid, int blockingPid)
            throws SQLException, InterruptedException {

        // Leave a margin below production's 5s lock_timeout.
        long deadline =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        // Keep autocommit enabled so each observation is fresh.
        try (Connection observer = migratorConnection();
                PreparedStatement check = observer.prepareStatement(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_stat_activity
                            WHERE pid = ?
                              AND wait_event_type = 'Lock'
                              AND ? = ANY(pg_blocking_pids(pid))
                              AND query LIKE '%FOR NO KEY UPDATE%'
                        )
                        """)) {

            check.setInt(1, blockedPid);
            check.setInt(2, blockingPid);

            while (System.nanoTime() < deadline) {
                try (ResultSet rows = check.executeQuery()) {
                    if (rows.next() && rows.getBoolean(1)) {
                        return true;
                    }
                }

                Thread.sleep(25);
            }

            return false;
        }
    }

    private Result invoke(
            UUID projectId, UUID credentialId, boolean forceLast) {

        if (forceLast) {
            return execute(
                    "revoke",
                    "--project", projectId.toString(),
                    "--credential", credentialId.toString(),
                    "--force-last");
        }

        return execute(
                "revoke",
                "--project", projectId.toString(),
                "--credential", credentialId.toString());
    }

    private Result execute(String... args) {

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode;

        try (PrintStream out = new PrintStream(
                        stdout, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(
                        stderr, true, StandardCharsets.UTF_8)) {

            exitCode = CredentialAdmin.run(
                    args, environment(), out, err);
        }

        return new Result(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
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

    private void insertProject(Connection connection, UUID projectId)
            throws SQLException {

        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO projects (id, name) VALUES (?, ?)")) {

            statement.setObject(1, projectId);
            statement.setString(2, "revoke-project");

            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private UUID credentialId(
            Connection connection, UUID projectId, String token)
            throws SQLException {

        try (PreparedStatement statement =
                connection.prepareStatement(
                        """
                        SELECT id
                        FROM project_ingest_credentials
                        WHERE project_id = ?
                          AND key_hash = sha256(convert_to(?, 'UTF8'))
                        """)) {

            statement.setObject(1, projectId);
            statement.setString(2, token);

            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                UUID id = rows.getObject(1, UUID.class);
                assertThat(rows.next()).isFalse();
                return id;
            }
        }
    }

    private long activeCredentialCount(UUID projectId)
            throws SQLException {

        try (Connection connection = migratorConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT count(*)
                                FROM project_ingest_credentials
                                WHERE project_id = ?
                                  AND revoked_at IS NULL
                                """)) {

            statement.setObject(1, projectId);

            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();

                long count = rows.getLong(1);

                assertThat(rows.next()).isFalse();

                return count;
            }
        }
    }

    private UUID credentialProjectId(UUID credentialId)
            throws SQLException {

        try (Connection connection = migratorConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT project_id
                                FROM project_ingest_credentials
                                WHERE id = ?
                                """)) {

            statement.setObject(1, credentialId);

            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();

                UUID projectId = rows.getObject(1, UUID.class);

                assertThat(rows.next()).isFalse();

                return projectId;
            }
        }
    }

    private OffsetDateTime revokedAt(UUID credentialId)
            throws SQLException {

        try (Connection connection = migratorConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                """
                                SELECT revoked_at
                                FROM project_ingest_credentials
                                WHERE id = ?
                                """)) {

            statement.setObject(1, credentialId);

            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                OffsetDateTime value =
                        rows.getObject(1, OffsetDateTime.class);
                assertThat(rows.next()).isFalse();
                return value;
            }
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}
