package com.triageharbor;

import static org.assertj.core.api.Assertions.assertThat;

import com.triageharbor.support.RoleSeparatedPostgresTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(RoleSeparatedPostgresTestConfiguration.class)
class CredentialAdminProcessTests {

    private static final String PROJECT =
            "11111111-1111-1111-1111-111111111111";

    private static final String CANARY =
            "CANARY_CLI_PASSWORD_5c2e";

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @TempDir
    private Path tempDir;

    @Test
    void realProcessKeepsStdoutEmptyAndLogsToStderr()
            throws Exception {

        ProcessResult result = launch("test-migrator-password");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();

        // Confirm that the subprocess reached migration verification.
        assertThat(result.stderr())
                .contains("Flyway migration verification succeeded")
                .contains("not implemented")
                .doesNotContain("mode=migrate");

        // Positive evidence that the dedicated Logback file loaded.
        assertThat(result.stderr())
                .containsPattern(
                        "(?m)^\\d{4}-\\d{2}-\\d{2} "
                                + "\\d{2}:\\d{2}:\\d{2},\\d{3} INFO ");

        // Reject the characteristic default DEBUG logging pattern.
        assertThat(result.stderr())
                .doesNotContainPattern(
                        "(?m)^\\S+ \\S+ DEBUG ");
    }

    @Test
    void wrongPasswordFailsWithoutLeakingThroughLibraryLogging()
            throws Exception {

        ProcessResult result = launch(CANARY);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();

        assertThat(result.stderr())
                .contains("migration preflight failed")
                .contains("28P01")
                .doesNotContain(CANARY);
    }

    private ProcessResult launch(String password)
            throws Exception {

        Path stdout = tempDir.resolve("stdout.txt");
        Path stderr = tempDir.resolve("stderr.txt");

        ProcessBuilder builder =
                new ProcessBuilder(
                        Path.of(
                                        System.getProperty("java.home"),
                                        "bin",
                                        "java")
                                .toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        "com.triageharbor.CredentialAdmin",
                        "list",
                        "--project",
                        PROJECT);

        // Do not inherit DB_* or unrelated secrets from the shell.
        builder.environment().clear();

        builder.environment()
                .putAll(
                        Map.of(
                                "DB_HOST",
                                postgresContainer.getHost(),
                                "DB_PORT",
                                String.valueOf(
                                        postgresContainer.getMappedPort(5432)),
                                "DB_NAME",
                                postgresContainer.getDatabaseName(),
                                "DB_MIGRATOR_USER",
                                "triageharbor_migrator",
                                "DB_MIGRATOR_PASSWORD",
                                password));

        // Files prevent pipe-buffer deadlocks on large output.
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());

        Process process = builder.start();

        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                throw new AssertionError(
                        "CredentialAdmin subprocess timed out");
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();

                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw new AssertionError(
                            "CredentialAdmin subprocess could not be terminated");
                }
            }
        }

        return new ProcessResult(
                process.exitValue(),
                Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8));
    }

    private record ProcessResult(
            int exitCode,
            String stdout,
            String stderr) {}
}
