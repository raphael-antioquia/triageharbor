package com.triageharbor.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.triageharbor.support.PostgresTestConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class EventIngestConcurrencyTests {

    private static final int CONCURRENT_INGESTS = 50;
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration BURST_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(10);

    private static final EventIngestRequest MATCHING_REQUEST =
            new EventIngestRequest(
                    Instant.parse("2026-09-06T12:00:00Z"),
                    "java.lang.IllegalStateException",
                    "Something failed",
                    "stack trace");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EventIngestService eventIngestService;

    @Autowired
    private IngestCredentialResolver ingestCredentialResolver;

    /*
     * Static so an unsafe state survives JUnit's default per-method
     * test-instance lifecycle within this class.
     *
     * False means this class could not establish that all concurrency-test
     * workers stopped touching PostgreSQL. In that state TRUNCATE is unsafe
     * because a worker may still hold a row lock while TRUNCATE waits for
     * ACCESS EXCLUSIVE.
     *
     * This guard does not protect other test classes that share the same
     * Spring context; it only prevents this class from making the failure
     * worse.
     */
    private static boolean databaseCleanupSafe = true;

    @BeforeEach
    void requireSafeDatabaseState() {
        assertThat(databaseCleanupSafe)
                .as(
                        "A previous concurrency test could not establish worker "
                                + "termination; refusing to touch the shared "
                                + "PostgreSQL test context")
                .isTrue();
    }

    @AfterEach
    void cleanUp() {
        if (!databaseCleanupSafe) {
            return;
        }
        jdbcTemplate.execute("TRUNCATE TABLE events, incidents, projects");
    }

    @Test
    void concurrentIngestsCreateOneIncidentWithoutLosingOccurrences() {
        ProjectCredential project = insertProject("Project A");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        runConcurrentIngests(
                authenticatedProject,
                MATCHING_REQUEST,
                CONCURRENT_INGESTS);

        assertThat(incidentCount(project.projectId()))
                .as("concurrent creation must produce exactly one incident")
                .isEqualTo(1L);

        assertThat(occurrenceCount(project.projectId()))
                .as(
                        "all workers completed without failure, so a short "
                                + "occurrence count would indicate a potential lost update")
                .isEqualTo(CONCURRENT_INGESTS);

        assertThat(eventCount(project.projectId()))
                .as("every accepted occurrence must append one event")
                .isEqualTo(CONCURRENT_INGESTS);
    }

    @Test
    void concurrentIngestsUpdateExistingIncidentWithoutLosingOccurrences() {
        ProjectCredential project = insertProject("Project A");

        AuthenticatedProject authenticatedProject =
                ingestCredentialResolver.resolve(project.ingestKey());

        eventIngestService.ingest(
                authenticatedProject,
                MATCHING_REQUEST);

        assertThat(incidentCount(project.projectId())).isEqualTo(1L);
        assertThat(occurrenceCount(project.projectId())).isEqualTo(1L);
        assertThat(eventCount(project.projectId())).isEqualTo(1L);

        runConcurrentIngests(
                authenticatedProject,
                MATCHING_REQUEST,
                CONCURRENT_INGESTS);

        assertThat(incidentCount(project.projectId()))
                .as("concurrent updates must not create another incident")
                .isEqualTo(1L);

        assertThat(occurrenceCount(project.projectId()))
                .as(
                        "all workers completed without failure, so a short "
                                + "occurrence count would indicate a potential lost update")
                .isEqualTo(CONCURRENT_INGESTS + 1L);

        assertThat(eventCount(project.projectId()))
                .as("the seed plus every concurrent occurrence must be persisted")
                .isEqualTo(CONCURRENT_INGESTS + 1L);
    }

    /*
     * Concurrent coverage targets the writer's atomic upsert;
     * read-only concurrent credential lookups are intentionally
     * not exercised here.
     */
    private void runConcurrentIngests(
            AuthenticatedProject authenticatedProject,
            EventIngestRequest request,
            int ingestCount) {

        ExecutorService executor =
                Executors.newFixedThreadPool(ingestCount);

        CountDownLatch workersReady =
                new CountDownLatch(ingestCount);

        CountDownLatch startGate =
                new CountDownLatch(1);

        List<Future<Void>> futures =
                new ArrayList<>(ingestCount);

        List<Throwable> failures =
                new ArrayList<>();

        boolean abortWork = false;
        boolean restoreInterrupt = false;
        int nextUnobservedFuture = 0;

        try {
            try {
                for (int index = 0; index < ingestCount; index++) {
                    Future<Void> future =
                            executor.submit(
                                    () -> {
                                        workersReady.countDown();
                                        startGate.await();

                                        eventIngestService.ingest(
                                                authenticatedProject,
                                                request);

                                        return null;
                                    });

                    futures.add(future);
                }

                boolean allWorkersReady =
                        workersReady.await(
                                READY_TIMEOUT.toMillis(),
                                TimeUnit.MILLISECONDS);

                if (!allWorkersReady) {
                    failures.add(
                            new IllegalStateException(
                                    "Not all concurrency-test workers reached "
                                            + "the start gate within "
                                            + READY_TIMEOUT));
                    abortWork = true;
                }
            } catch (InterruptedException exception) {
                failures.add(exception);
                restoreInterrupt = true;
                abortWork = true;
            } catch (RuntimeException exception) {
                /*
                 * Submission itself may fail after some workers have already
                 * started and parked on startGate. Keep the failure, but still
                 * release those workers and terminate the executor below.
                 */
                failures.add(exception);
                abortWork = true;
            } finally {
                /*
                 * Before this point workers can only be parked on startGate.
                 * Releasing it is the boundary where they may reach PostgreSQL.
                 */
                databaseCleanupSafe = false;
                startGate.countDown();
            }

            if (!abortWork) {
                /*
                 * One deadline for the whole burst rather than a fresh budget
                 * per worker, so the bound is BURST_TIMEOUT overall and not
                 * ingestCount * BURST_TIMEOUT in the worst case.
                 */
                long deadlineNanos =
                        System.nanoTime() + BURST_TIMEOUT.toNanos();

                for (;
                        nextUnobservedFuture < futures.size();
                        nextUnobservedFuture++) {

                    Future<Void> future =
                            futures.get(nextUnobservedFuture);

                    long remainingNanos =
                            deadlineNanos - System.nanoTime();

                    if (remainingNanos <= 0L) {
                        failures.add(
                                new IllegalStateException(
                                        "The concurrent ingest burst exceeded its "
                                                + "overall deadline of "
                                                + BURST_TIMEOUT
                                                + " after observing "
                                                + nextUnobservedFuture
                                                + " of "
                                                + futures.size()
                                                + " workers"));
                        abortWork = true;
                        break;
                    }

                    try {
                        future.get(
                                remainingNanos,
                                TimeUnit.NANOSECONDS);
                    } catch (ExecutionException exception) {
                        Throwable cause = exception.getCause();

                        failures.add(
                                cause != null
                                        ? cause
                                        : exception);
                    } catch (TimeoutException exception) {
                        failures.add(
                                new IllegalStateException(
                                        "Concurrency-test worker "
                                                + (nextUnobservedFuture + 1)
                                                + " did not return before the "
                                                + "burst deadline of "
                                                + BURST_TIMEOUT,
                                        exception));
                        abortWork = true;
                        break;
                    } catch (InterruptedException exception) {
                        failures.add(exception);
                        restoreInterrupt = true;
                        abortWork = true;
                        break;
                    }
                }
            }
        } finally {
            if (abortWork) {
                cancelUnfinished(futures);
                executor.shutdownNow();
            } else {
                executor.shutdown();
            }

            boolean terminated = false;

            try {
                terminated =
                        executor.awaitTermination(
                                TERMINATION_TIMEOUT.toMillis(),
                                TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                failures.add(exception);
                restoreInterrupt = true;
            }

            if (!terminated) {
                cancelUnfinished(futures);
                executor.shutdownNow();

                try {
                    terminated =
                            executor.awaitTermination(
                                    TERMINATION_TIMEOUT.toMillis(),
                                    TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    failures.add(exception);
                    restoreInterrupt = true;
                }
            }

            databaseCleanupSafe = terminated;

            if (!terminated) {
                failures.add(
                        new IllegalStateException(
                                "Concurrency-test executor did not terminate. "
                                        + "Database cleanup is being skipped "
                                        + "because workers may still hold "
                                        + "PostgreSQL locks."));
            }

            /*
             * If we stopped waiting early, collect failures from any remaining
             * workers that did terminate. Do not block here: termination has
             * already been established or cleanup has been marked unsafe.
             */
            if (terminated) {
                for (int index = nextUnobservedFuture;
                        index < futures.size();
                        index++) {

                    Future<Void> future = futures.get(index);

                    if (!future.isDone() || future.isCancelled()) {
                        continue;
                    }

                    try {
                        future.get();
                    } catch (ExecutionException exception) {
                        Throwable cause = exception.getCause();

                        failures.add(
                                cause != null
                                        ? cause
                                        : exception);
                    } catch (InterruptedException exception) {
                        failures.add(exception);
                        restoreInterrupt = true;
                    }
                }
            }

            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }

        throwIfFailures(failures);
    }

    private static void cancelUnfinished(
            List<Future<Void>> futures) {

        for (Future<Void> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static void throwIfFailures(
            List<Throwable> failures) {

        if (failures.isEmpty()) {
            return;
        }

        StringBuilder message =
                new StringBuilder(
                        "Concurrent ingest workers produced "
                                + failures.size()
                                + " failure(s):");

        for (Throwable failure : failures) {
            message.append(System.lineSeparator())
                    .append(" - ")
                    .append(failure.getClass().getSimpleName());

            if (failure.getMessage() != null) {
                message.append(": ")
                        .append(failure.getMessage());
            }
        }

        AssertionError error =
                new AssertionError(message.toString());

        for (Throwable failure : failures) {
            error.addSuppressed(failure);
        }

        throw error;
    }

    private ProjectCredential insertProject(String name) {
        UUID projectId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO projects (id, name) VALUES (?, ?)",
                projectId,
                name);

        UUID ingestKey =
                jdbcTemplate.queryForObject(
                        "SELECT ingest_key FROM projects WHERE id = ?",
                        UUID.class,
                        projectId);

        return new ProjectCredential(projectId, ingestKey);
    }

    private long incidentCount(UUID projectId) {
        Long count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM incidents
                        WHERE project_id = ?
                        """,
                        Long.class,
                        projectId);

        return count;
    }

    private long occurrenceCount(UUID projectId) {
        Long count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT occurrence_count
                        FROM incidents
                        WHERE project_id = ?
                        """,
                        Long.class,
                        projectId);

        return count;
    }

    private long eventCount(UUID projectId) {
        Long count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM events
                        WHERE project_id = ?
                        """,
                        Long.class,
                        projectId);

        return count;
    }

    private record ProjectCredential(
            UUID projectId,
            UUID ingestKey) {}
}
