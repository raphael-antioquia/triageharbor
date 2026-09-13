package com.triageharbor;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

final class MigrationVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationVerifier.class);

    private MigrationVerifier() {}

    static void verify(ConfigurableApplicationContext context) {
        LOGGER.info("Verifying Flyway migration state: mode=migrate");

        Flyway flyway = context.getBeanProvider(Flyway.class).getIfAvailable();

        if (flyway == null) {
            throw new IllegalStateException(
                    "Migration mode requires an enabled Flyway bean, but none was available.");
        }

        verify(flyway.info());
    }

    static void verify(MigrationInfoService migrationInfo) {
        MigrationInfo[] migrations = migrationInfo.all();

        for (MigrationInfo migration : migrations) {
            MigrationState state = migration.getState();

            LOGGER.info(
                    "Flyway migration: identifier={}, state={}, description={}",
                    migrationIdentifier(migration),
                    state,
                    migration.getDescription());

            if (state.isFailed()) {
                throw new IllegalStateException(
                        "Migration verification failed: "
                                + migrationIdentifier(migration)
                                + " is in a failed state: "
                                + state
                                + ".");
            }

            if (state == MigrationState.FUTURE_SUCCESS) {
                throw new IllegalStateException(
                        "Migration verification failed: "
                                + migrationIdentifier(migration)
                                + " was applied to the database but is not packaged in this image "
                                + "and is newer than this image's migration set.");
            }

            if (state == MigrationState.MISSING_SUCCESS) {
                throw new IllegalStateException(
                        "Migration verification failed: "
                                + migrationIdentifier(migration)
                                + " was applied to the database but is missing from this image.");
            }
        }

        MigrationInfo current = migrationInfo.current();

        if (current == null) {
            throw new IllegalStateException(
                    "Migration verification failed: Flyway reports no current migration.");
        }

        MigrationInfo[] pending = migrationInfo.pending();

        if (pending.length != 0) {
            throw new IllegalStateException(
                    "Migration verification failed: Flyway reports "
                            + pending.length
                            + " pending migration(s).");
        }

        LOGGER.info(
                "Flyway migration verification succeeded: mode=migrate, current={}, pending=0",
                current.getVersion());
    }

    private static String migrationIdentifier(MigrationInfo migration) {
        if (migration.getVersion() != null) {
            return "version " + migration.getVersion();
        }

        return "repeatable migration '" + migration.getDescription() + "'";
    }
}
