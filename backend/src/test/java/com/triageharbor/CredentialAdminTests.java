package com.triageharbor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CredentialAdminTests {

    private static final String PROJECT =
            "11111111-1111-1111-1111-111111111111";

    private static final String CREDENTIAL =
            "22222222-2222-2222-2222-222222222222";

    @Test
    void noCommandReturnsUsageError() {
        Result result = invoke(new String[0], Map.of());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("Usage:");
    }

    @Test
    void unknownCommandFailsBeforeDatabaseConfiguration() {
        Result result =
                invoke(new String[] {"unknown"}, Map.of());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("Invalid command");
        assertThat(result.stderr())
                .doesNotContain("database configuration");
    }

    @Test
    void missingProjectFailsBeforeDatabaseConfiguration() {
        Result result =
                invoke(new String[] {"issue"}, Map.of());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("Usage:");
    }

    @Test
    void invalidProjectUuidIsRejected() {
        Result result =
                invoke(
                        new String[] {
                            "issue", "--project", "not-a-uuid"
                        },
                        Map.of());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void noncanonicalUuidIsRejected() {
        Result result =
                invoke(
                        new String[] {
                            "list", "--project", "1-1-1-1-1"
                        },
                        Map.of());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void revokeRequiresCredentialId() {
        Result result =
                invoke(
                        new String[] {
                            "revoke", "--project", PROJECT
                        },
                        Map.of());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void forceLastIsNotAllowedForIssue() {
        Result result =
                invoke(
                        new String[] {
                            "issue",
                            "--project",
                            PROJECT,
                            "--force-last"
                        },
                        Map.of());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void missingMigratorPasswordReturnsOperationalFailure() {
        Map<String, String> env =
                Map.of(
                        "DB_HOST", "localhost",
                        "DB_PORT", "5432",
                        "DB_NAME", "triageharbor",
                        "DB_MIGRATOR_USER", "triageharbor_migrator");

        Result result =
                invoke(
                        new String[] {"list", "--project", PROJECT},
                        env);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("database configuration");
    }

    @Test
    void applicationRoleIsNotAccepted() {
        Map<String, String> env =
                Map.of(
                        "DB_HOST", "localhost",
                        "DB_PORT", "5432",
                        "DB_NAME", "triageharbor",
                        "DB_MIGRATOR_USER", "triageharbor_app",
                        "DB_MIGRATOR_PASSWORD", "secret-canary");

        Result result =
                invoke(
                        new String[] {"list", "--project", PROJECT},
                        env);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr())
                .doesNotContain("secret-canary");

        // Confirm configuration rejected the role before preflight.
        assertThat(result.stderr())
                .contains("database configuration")
                .doesNotContain("preflight");
    }

    @Test
    void malformedDatabaseHostDoesNotLeakConfiguration() {
        Map<String, String> env =
                Map.of(
                        "DB_HOST", "localhost/?password=secret-canary",
                        "DB_PORT", "5432",
                        "DB_NAME", "triageharbor",
                        "DB_MIGRATOR_USER", "triageharbor_migrator",
                        "DB_MIGRATOR_PASSWORD", "test-migrator-password");

        Result result =
                invoke(
                        new String[] {"list", "--project", PROJECT},
                        env);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr())
                .doesNotContain("secret-canary");

        // Confirm the unsafe host was rejected before preflight.
        assertThat(result.stderr())
                .contains("database configuration")
                .doesNotContain("preflight");
    }

    @Test
    void invalidDatabasePortIsRejected() {
        Map<String, String> env =
                Map.of(
                        "DB_HOST", "localhost",
                        "DB_PORT", "65536",
                        "DB_NAME", "triageharbor",
                        "DB_MIGRATOR_USER", "triageharbor_migrator",
                        "DB_MIGRATOR_PASSWORD", "test-migrator-password");

        Result result =
                invoke(
                        new String[] {"list", "--project", PROJECT},
                        env);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr())
                .contains("database configuration")
                .doesNotContain("preflight");
    }

    @Test
    void validRevokeArgumentsReachConfigurationValidation() {
        Result result =
                invoke(
                        new String[] {
                            "revoke",
                            "--project",
                            PROJECT,
                            "--credential",
                            CREDENTIAL,
                            "--force-last"
                        },
                        Map.of());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr())
                .contains("database configuration");
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void stderrWriteFailureOverridesUsageExitCode() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        // Simulate a broken stderr output stream.
        try (PrintStream failingErr =
                        new PrintStream(
                                new OutputStream() {
                                    @Override
                                    public void write(int b)
                                            throws IOException {
                                        throw new IOException("stderr closed");
                                    }
                                },
                                true,
                                StandardCharsets.UTF_8);
                PrintStream out =
                        new PrintStream(
                                stdout, true, StandardCharsets.UTF_8)) {

            int exitCode =
                    CredentialAdmin.run(
                            new String[0],
                            Map.of(),
                            out,
                            failingErr);

            // Usage normally returns 2; output failure takes priority.
            assertThat(exitCode).isEqualTo(1);
            assertThat(stdout.toString(StandardCharsets.UTF_8))
                    .isEmpty();
        }
    }

    private static Result invoke(
            String[] args, Map<String, String> env) {

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode;

        try (PrintStream out =
                        new PrintStream(
                                stdout, true, StandardCharsets.UTF_8);
                PrintStream err =
                        new PrintStream(
                                stderr, true, StandardCharsets.UTF_8)) {

            exitCode = CredentialAdmin.run(args, env, out, err);
        }

        return new Result(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record Result(
            int exitCode, String stdout, String stderr) {}
}
