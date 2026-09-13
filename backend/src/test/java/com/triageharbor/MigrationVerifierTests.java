package com.triageharbor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

class MigrationVerifierTests {

    @Test
    void completeMigrationStatePassesVerifierBranches() {
        MigrationInfoService migrationInfo = mock(MigrationInfoService.class);
        MigrationInfo current =
                migration(MigrationState.SUCCESS, "2", "add project ingest key");

        when(migrationInfo.all()).thenReturn(new MigrationInfo[] {current});
        when(migrationInfo.current()).thenReturn(current);
        when(migrationInfo.pending()).thenReturn(new MigrationInfo[0]);

        assertDoesNotThrow(() -> MigrationVerifier.verify(migrationInfo));
    }

    @Test
    void failedMigrationStateFailsVerification() {
        MigrationInfoService migrationInfo = mock(MigrationInfoService.class);
        MigrationInfo failed =
                migration(MigrationState.FAILED, "2", "add project ingest key");

        when(migrationInfo.all()).thenReturn(new MigrationInfo[] {failed});

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> MigrationVerifier.verify(migrationInfo));

        assertTrue(exception.getMessage().contains("failed state"));
    }

    @Test
    void futureSuccessfulMigrationFailsVerification() {
        MigrationInfoService migrationInfo = mock(MigrationInfoService.class);
        MigrationInfo future =
                migration(MigrationState.FUTURE_SUCCESS, "3", "future migration");

        when(migrationInfo.all()).thenReturn(new MigrationInfo[] {future});

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> MigrationVerifier.verify(migrationInfo));

        assertTrue(exception.getMessage().contains("not packaged in this image"));
    }

    @Test
    void missingSuccessfulMigrationFailsVerification() {
        MigrationInfoService migrationInfo = mock(MigrationInfoService.class);
        MigrationInfo missing =
                migration(MigrationState.MISSING_SUCCESS, "7", "deleted migration");

        when(migrationInfo.all()).thenReturn(new MigrationInfo[] {missing});

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> MigrationVerifier.verify(migrationInfo));

        assertTrue(exception.getMessage().contains("missing from this image"));
    }

    @Test
    void noCurrentMigrationFailsVerification() {
        MigrationInfoService migrationInfo = mock(MigrationInfoService.class);

        when(migrationInfo.all()).thenReturn(new MigrationInfo[0]);
        when(migrationInfo.current()).thenReturn(null);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> MigrationVerifier.verify(migrationInfo));

        assertTrue(exception.getMessage().contains("no current migration"));
    }

    @Test
    void pendingMigrationFailsVerification() {
        MigrationInfoService migrationInfo = mock(MigrationInfoService.class);
        MigrationInfo current =
                migration(MigrationState.SUCCESS, "2", "add project ingest key");
        MigrationInfo pending =
                migration(MigrationState.PENDING, "3", "next migration");

        when(migrationInfo.all()).thenReturn(new MigrationInfo[] {current, pending});
        when(migrationInfo.current()).thenReturn(current);
        when(migrationInfo.pending()).thenReturn(new MigrationInfo[] {pending});

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> MigrationVerifier.verify(migrationInfo));

        assertTrue(exception.getMessage().contains("1 pending migration"));
    }

    @Test
    void repeatableMigrationUsesDescriptionAsIdentifier() {
        MigrationInfoService migrationInfo = mock(MigrationInfoService.class);
        MigrationInfo failed =
                migration(MigrationState.FAILED, null, "refresh reporting view");

        when(migrationInfo.all()).thenReturn(new MigrationInfo[] {failed});

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> MigrationVerifier.verify(migrationInfo));

        assertTrue(
                exception.getMessage()
                        .contains("repeatable migration 'refresh reporting view'"));
    }

    @Test
    void missingFlywayBeanFailsVerification() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.refresh();

            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> MigrationVerifier.verify(context));

            assertTrue(exception.getMessage().contains("Flyway bean"));
        }
    }

    private static MigrationInfo migration(
            MigrationState state, String version, String description) {
        MigrationInfo migration = mock(MigrationInfo.class);

        when(migration.getState()).thenReturn(state);
        when(migration.getDescription()).thenReturn(description);

        if (version != null) {
            when(migration.getVersion())
                    .thenReturn(MigrationVersion.fromVersion(version));
        }

        return migration;
    }
}
