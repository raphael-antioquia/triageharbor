# Testing and Verification

TriageHarbor verifies backend correctness through focused unit tests, Spring Boot integration tests, real PostgreSQL integration tests, and deliberately injected failure scenarios.

The goal is not simply to demonstrate that successful requests work. Tests target the invariants and failure modes that matter most: concurrent incident aggregation, transactional rollback, project isolation, credential lifecycle safety, database privileges, sensitive-data handling, and ingestion admission controls.

This document distinguishes implemented enforcement, automated test assertions, observed CI outcomes, and properties that remain unverified.

**Repository baseline:** [`main` at `e2305b0`](https://github.com/raphael-antioquia/triageharbor/commit/e2305b00bff543b19cf15a307ee30a8fc92a924a), November 16, 2026.

## Evidence Baseline

Claims in this document rest on three kinds of evidence, kept distinct throughout:

- **Checked-in sources at `e2305b0`:** implementation, migrations, and test assertions. Test classes are named where they support a claim; this document is not an inventory of every test.
- **CI runs on matching contents:** Backend CI run [`43967175273`](https://github.com/raphael-antioquia/triageharbor/actions/runs/43967175273) succeeded on [PR #12](https://github.com/raphael-antioquia/triageharbor/pull/12)'s final head `ea5880c`, with formatting, static-analysis, and test steps reporting success. GitHub's comparison of `ea5880c` with merge commit `e2305b0` reports no changed files, so that run exercised the same file contents as this baseline. The [post-merge Backend CI run](https://github.com/raphael-antioquia/triageharbor/actions/runs/43971516028) on `main` also succeeded.
- **Historical production acceptance evidence:** dated operator observations of fresh commissioning and the deployed HTTPS/Caddy path. Results identified as operator-reported retain that status here.
- **Historical TimeMetrics integration evidence:** preserved screenshot descriptions, operator output, operator reports, and reported TimeMetrics test execution from the November 19–20 synthetic demonstration. The original screenshots and raw logs are not committed to this repository, so the evidence classes remain explicit below.

## Testing Approach

### Test layers

TriageHarbor currently uses several complementary test layers.

| Layer | Main tools | What it establishes |
|---|---|---|
| Unit and component tests | JUnit 5, AssertJ, Mockito, Spring mock requests | Fingerprinting, redaction, validation, limiter behavior, argument handling, and controlled failure responses |
| Spring context and configuration tests | `@SpringBootTest`, `ApplicationContextRunner`, `@JsonTest` | Application startup, configuration binding, JSON mapping, and dependency wiring |
| PostgreSQL integration tests | Testcontainers, PostgreSQL 17.11, JDBC | Actual constraints, UPSERT behavior, transaction rollback, privileges, SQL errors, and locking |
| HTTP integration tests | `@SpringBootTest(RANDOM_PORT)`, Java `HttpClient` | Embedded-server routing, request processing, authentication, status codes, and persistence |
| Operator failure tests | Real JDBC connections, dynamic proxies, subprocess tests | Credential issuance, revocation, locking, uncertain commits, output failures, and recovery behavior |

Real PostgreSQL matters because the most important correctness guarantees depend on database behavior.

A mocked repository cannot establish whether PostgreSQL serializes conflicting updates, rejects a cross-project foreign key, enforces a privilege boundary, or releases a lock after a transaction.

Mocks remain useful where the property under test is specifically the application's response to a failure. For example, a mocked `DataAccessException` can verify HTTP 500 handling, while a separate real-database permission failure establishes that an actual PostgreSQL error follows the same path.

### Test environment

Two principal PostgreSQL configurations are checked in.

`PostgresTestConfiguration` creates a PostgreSQL 17.11 Testcontainer using the `triageharbor` database and `db/test-role-bootstrap.sql`. It uses `@ServiceConnection`, so its Spring database connection configuration originates from the container's administrative identity.

`RoleSeparatedPostgresTestConfiguration` creates the same PostgreSQL version and bootstrap but explicitly provides separate connection details:

| Responsibility | Test database identity |
|---|---|
| Application JDBC operations | `triageharbor_app` |
| Flyway migrations | `triageharbor_migrator` |
| Privileged fixture setup and verification | Testcontainers administrative identity |

The bootstrap gives the two restricted roles distinct login credentials and bounded database/schema privileges.

Spring integration contexts load Flyway migrations, and `spring.jpa.hibernate.ddl-auto=validate` checks mapping compatibility with the resulting schema.

`TriageHarborApplicationTests.contextLoads` is the basic startup and Hibernate-validation smoke test. It does not independently prove foreign keys, CHECK constraints, or SQL privilege sufficiency. Those require explicit database tests.

Some integration tests start the embedded web server on a random port and send actual HTTP requests. Others call Spring-managed services directly to isolate the writer from HTTP admission behavior.

### Test isolation

Tests use several isolation mechanisms, depending on the database identity and behavior being exercised:

- Many ingestion and read tests clear `events`, `incidents`, `project_ingest_credentials`, and `projects` using `TRUNCATE` in `@AfterEach`.
- Tests operating as `triageharbor_app` use separate administrative connections for fixture creation or cleanup because the application role cannot perform those operations.
- Credential operator tests commonly use unique project UUIDs and independently inspect committed database state.
- Database-role verifier mutation tests run deliberate changes inside transactions and roll them back afterward.
- Tests that alter shared quotas or database grants use controlled state restoration or `@DirtiesContext`.
- Rate-limit tests use injectable monotonic clocks instead of waiting for real time to pass.

The ingestion concurrency suite also contains a cleanup guard. If worker termination cannot be established, it skips its own database cleanup rather than risking a `TRUNCATE` blocked by still-running workers.

These mechanisms reduce interference but do not establish complete cross-class isolation. In particular, not every operator test performs a shared-table `TRUNCATE`, and the concurrency cleanup guard does not protect unrelated test classes using the same Spring context.

## Running the Checks

These commands match the Gradle build, wrapper, and Backend CI workflow at `e2305b0`. They were **not executed while preparing this document**; the CI evidence above is the execution record.

Prerequisites are Java 21 and an available Docker environment for Testcontainers-backed tests.

Run commands from `backend/`.

### Full backend quality gate

```bash
./gradlew spotlessCheck spotbugsMain test
```

This is equivalent to the three quality-check tasks configured in Backend CI.

For a run that forces Gradle to re-execute tasks instead of treating them as up-to-date:

```bash
./gradlew spotlessCheck spotbugsMain test --rerun-tasks
```

`--rerun-tasks` is useful after changing a test or when collecting fresh local evidence. It does not replace the need to inspect the resulting test reports.

### Individual checks

```bash
# Formatting and whitespace checks
./gradlew spotlessCheck

# SpotBugs analysis of main source
./gradlew spotbugsMain

# All tests discovered by the configured test task
./gradlew test
```

The build disables `spotbugsTest`, so the configured SpotBugs gate analyzes main-source bytecode rather than test-source bytecode.

`test` alone does not invoke `spotlessCheck` or `spotbugsMain` as separate quality gates. Run all three when matching CI.

### Targeted suites

```bash
# Database constraints, roles, privileges, and SQL diagnostics
./gradlew test --tests 'com.triageharbor.database.*'

# Ingestion, authentication, redaction, concurrency, and rate limits
./gradlew test --tests 'com.triageharbor.event.*'

# Incident read API
./gradlew test --tests 'com.triageharbor.incident.*'

# Credential operator lifecycle
./gradlew test \
  --tests 'com.triageharbor.CredentialAdmin*' \
  --tests 'com.triageharbor.CredentialRevocationInFlightTests'

# One focused concurrency class
./gradlew test \
  --tests 'com.triageharbor.event.EventIngestConcurrencyTests'
```

The `--tests` option selects matching compiled test classes. Targeted runs are useful for debugging, but a successful targeted run does not replace the full quality gate after consequential changes.

Application startup and migration-mode tests, including `TriageHarborApplicationTests`, `TriageHarborApplicationModeTests`, and `MigrationVerifierTests`, are also included in the full test task.

Gradle's JUnit XML and HTML reports are generated under `backend/build/test-results/test/` and `backend/build/reports/tests/test/`, respectively, when the test task executes.

## Verification Matrix

The matrix intentionally concentrates on consequential properties rather than cataloguing every test method.

| Property | Enforcement | Automated evidence | Limit |
|---|---|---|---|
| One incident per project/fingerprint identity | PostgreSQL unique constraint and `INSERT ... ON CONFLICT DO UPDATE` | `EventIngestConcurrencyTests`: 50 competing new-incident ingests yield one incident, count 50, 50 events | Single JVM and PostgreSQL instance; runs under the administrative test datasource, not `triageharbor_app` |
| No lost updates for matching successful writes | Database-side `occurrence_count + 1` | Existing-incident contention: seed 1 plus 50 calls yields count 51 and 51 events | Not a throughput or multi-instance test |
| Incident/event atomicity | `@Transactional` writer | `eventInsertFailureRollsBackIncidentUpsert` forces a not-null failure and asserts zero persisted incident/event rows | Does not make admission or credential lookup transactional with the writer |
| Tenant and relational integrity | Project-scoped uniqueness, foreign keys, composite event FK, credential-derived project | `DatabaseConstraintTests`, service tests, HTTP spoofing test | Incident reads have no application-level ownership authorization |
| Credential authentication and revocation | Issued-token format and resolver shape validation, SHA-256 hash lookup, `revoked_at IS NULL` | `IngestCredentialResolverTests`, `EventIngestControllerTests`, `CredentialRevocationInFlightTests` | Previously resolved requests are not cancelled |
| Restricted database privileges | Separate app/migrator identities, V3/V4 grants, 47-check SQL contract | Role topology, runtime privilege tests, contract mutation tests | Does not inspect live production ACLs |
| Sensitive-data protection on tested paths | Redaction before persistence/derivation, controlled diagnostics, pgJDBC error-detail setting | Redaction golden vectors, persistence tests, HTTP log canaries, JDBC contrast tests | Pattern-bound; not universal secret removal |
| Ingestion quota behavior | Atomic Bucket4j probes, global and project buckets, monotonic refill | Concurrent-capacity, refill, 429, path-routing, and HTTP precedence tests | JVM-local quotas; no shared cross-instance budget |
| Operator credential lifecycle | Scoped JDBC operations, project-row locking, conditional updates, explicit commit outcomes | Issue/list/revoke, competing revoke, lost-acknowledgment, cleanup, and subprocess tests | Cooperative last-key guard; injected failures are modeled interleavings |

Each row's limits are collected under [Verification Boundaries](#verification-boundaries). For schema details, see [database.md](database.md); for request and trust boundaries, see [architecture.md](architecture.md).

## Concurrency and Transaction Tests

### Concurrent incident creation

`EventIngestConcurrencyTests.concurrentIngestsCreateOneIncidentWithoutLosingOccurrences` tests the creation race for a previously absent incident.

It creates a project, resolves an ingest credential before the burst, and submits 50 calls to the actual Spring-managed `EventIngestService`.

The test uses a 50-thread executor, a readiness latch, and a shared start gate. Workers signal readiness and wait; releasing the gate allows them to race into independent writer transactions.

Successful completion requires:

```text
Incident rows:      1
Occurrence count:  50
Raw event rows:    50
Worker failures:    0
```

This catches failures such as duplicate-key exceptions from missing conflict handling, duplicate logical incidents, incorrect counter aggregation, or missing event inserts.

The readiness gate increases contention but does not guarantee that all 50 SQL transactions execute simultaneously. Actual database concurrency remains constrained by the connection pool and PostgreSQL scheduling.

### Concurrent updates to an existing incident

`concurrentIngestsUpdateExistingIncidentWithoutLosingOccurrences` first persists one occurrence. It then releases another 50 service calls targeting the same incident identity.

The final assertions are:

```text
Incident rows:      1
Occurrence count:  51
Raw event rows:    51
Worker failures:    0
```

This isolates the lost-update regression more directly because incident creation is no longer part of the disputed operation.

The production SQL increments the current database value inside the conflict update, rather than reading a count into Java and writing back a calculated value.

Both tests operate on the actual PostgreSQL UPSERT, not a repository mock.

### Worker and cleanup controls

Every submitted `Future` is inspected for failure. The helper records worker exceptions, combines them into one assertion failure with suppressed causes, and uses a single deadline for the burst.

It also bounds executor shutdown and checks termination before marking database cleanup safe.

These checks matter because a test that verifies only the final count could otherwise miss worker exceptions, and an abandoned worker holding database resources could cause later cleanup to hang.

The tests resolve the ingest credential once before the burst. Consequently, they establish the writer's aggregation behavior, not concurrent credential-resolution performance or revocation behavior.

Both tests use `PostgresTestConfiguration`, so they run under the Testcontainers administrative datasource. Separate role-restricted integration tests verify that ingestion operates as `triageharbor_app`, but sequentially. A 50-call contention test under the restricted runtime identity has not been performed. This is a missing test combination, not evidence against the database-side increment mechanism.

### Transaction rollback and timing

`EventIngestServiceTests` covers ordinary persistence and transaction semantics:

- Identical successful ingests create two events and increment one incident twice. This is **not retry idempotency**.
- Different errors create separate incidents; identical errors in distinct projects remain separate.
- One server receipt timestamp is used for incident and event persistence, while client-provided `occurredAt` remains distinct.
- Out-of-order receipt times preserve the minimum first-seen and maximum last-seen values.
- Matching events do not rewrite the initial incident-title snapshot.
- A forced event INSERT failure rolls back the incident UPSERT.

`EventIngestTransactionStartFailureTests` verifies that occupying the only Hikari connection prevents the Spring-proxied writer from beginning a transaction. The test expects `CannotCreateTransactionException` and confirms that neither an incident nor an event was persisted.

`EventIngestConnectionRetentionTests` exercises the application's `JpaTransactionManager` with a one-connection pool. It observes one active connection inside a transaction before any SQL statement in the callback, and zero afterward.

This supports the conclusion that the current transaction setup can retain a connection during pre-SQL work. It is not a measurement of real ingestion latency or pool saturation.

## Security and Failure Tests

### Database integrity and migrations

`DatabaseConstraintTests` sends valid and invalid SQL directly to PostgreSQL.

It verifies project ownership, fingerprint uniqueness, version and occurrence-count positivity, timestamp ordering, accepted incident statuses, and referential restrictions.

The cross-project event test deliberately supplies an incident ID belonging to a different project and expects SQLState `23503` from `events_incident_project_fk`. This prevents an event from being attached to an incident owned by another project, even if an application path accidentally supplies inconsistent identifiers.

V4-specific cases exercise credential-hash uniqueness, 32-byte hash length, display-prefix format, required fields, and project foreign-key integrity.

Migration and schema tests provide complementary coverage:

- `TriageHarborApplicationTests` starts Spring against migrated PostgreSQL with Hibernate validation enabled.
- `BeforeMigrateGuardTests` provisions only the migrator role and verifies that Flyway refuses migration before creating the application schema.
- `MigrationVerifierTests` exercises success, failed, pending, future, missing, and absent-current migration states using mocked Flyway metadata.
- `CredentialAdminDatabaseTests` uses a separate PostgreSQL container to test pending V4, simulated future V5, and deliberately changed V4 checksums. Failed preflights preserve Flyway history.

These tests catch schema drift and incompatible deployment states, but successful startup alone does not establish the correctness of every database constraint.

### Database role separation

`DatabaseRoleProvisioningTests` checks restricted role attributes, database/schema permissions, successful authentication, and incorrect-password rejection.

`DatabaseRoleTopologyTests` checks the active runtime connection identity and migration ownership. Its ownership assertion explicitly covers the original application tables and Flyway history; the broader four-table V4 object inventory belongs to the 47-check contract.

`DatabaseRuntimePrivilegesTests` exercises the real application resolver, writer, and read service under `triageharbor_app`. It then attempts unauthorized SQL operations.

Negative probes include project and credential writes, raw-event reads and updates, incident deletion, `TRUNCATE`, table creation, and reading Flyway history. Denied operations are checked for SQLState `42501`.

`DatabaseRoleContractVerificationTests` executes the portable contract against PostgreSQL. It asserts exactly 47 uniquely named passing checks, deliberately introduces 20 privilege/schema violations as parameterized cases, and verifies that each expected check fails. A separate test ensures legitimate default-privilege hardening remains acceptable.

The result supports the designed runtime privilege boundary in the test topology, not a claim about the privileges currently configured on the production host.

### Authentication, HTTP validation, and read isolation

`IngestCredentialResolverTests` verifies independent project resolution, a known SHA-256 golden vector, unknown-token rejection, and malformed-token cases such as incorrect length, whitespace, padding, and prefix changes.

This is issued-token format and resolver shape validation: the resolver checks the `th_` prefix, length, and base64url alphabet without trimming, then hashes the exact supplied string. It does not decode the token to prove canonical base64url encoding; only a hash matching an active issued credential authenticates.

`EventIngestControllerTests` exercises the embedded HTTP server and database together. It checks accepted ingestion, empty-body 401 responses, duplicate header rejection, malformed JSON, invalid or missing required fields, oversized requests, credential rotation overlap, and post-revocation rejection.

The caller-supplied `projectId` test is especially important: a request authenticating as project A includes project B's ID in its JSON, but persisted incident and event ownership remains project A.

`EventIngestRequestJsonTests` confirms that unknown JSON fields are ignored and that a missing timestamp deserializes as null before validation.

`EventRequestSizeFilterTests` checks the 256 KiB boundary, rejects 256 KiB plus one byte, and proves the size check does not trust the declared `Content-Length`.

`IncidentReadControllerTests` exercises the actual HTTP read endpoint against PostgreSQL. Its fixtures cover project filtering, descending timestamps, PostgreSQL UUID tie-breaking, empty and unknown projects, malformed UUIDs, selected response fields, and ingest-header independence.

These read tests establish SQL project scoping, response mapping, ordering, and deterministic tie-breaking, not application authorization. They do not exercise the 101-row boundary or establish snapshot consistency between the separate project-existence and incident-list queries. The controller accepts the requested project UUID without a caller-ownership check. Deployment-level dashboard protection is a separate Caddy concern.

### Fingerprinting and sensitive-data handling

`EventIngestDerivationTests` pins v1 and v2 fingerprint vectors, UTF-8 length encoding, null-versus-empty behavior, and incident-title derivation.

It also checks that expanded credential redaction changes fingerprint semantics explicitly in v2 rather than redefining historical v1 identity.

`SensitiveDataRedactorTests` covers golden vectors for URL credentials, JWT-shaped values, sensitive assignments, headers, ingest-token patterns, false positives, known misses, idempotence, and adversarial inputs.

The performance tests use bounded JUnit timeouts to catch severe regex regressions. They are regression gates, not production throughput benchmarks.

`EventIngestPersistenceScrubbingTests` reads stored PostgreSQL rows, including serialized row values, to verify that recognized secret canaries are absent from the tested event fields and incident-derived data. It also checks that different raw secrets can aggregate under one sanitized fingerprint.

HTTP diagnostic tests check that malformed input, invalid timestamps, and selected validation failures do not echo synthetic canaries into responses or captured logs.

`PostgresJdbcDiagnosticTests` performs a useful contrast: ordinary pgJDBC error messages expose synthetic failing-row data with `logServerErrorDetail=true`, while the configured `false` setting suppresses those details in the tested exception messages. SQLState remains available.

These checks do not prove universal scrubbing, safety at every logging level, or protection in all external log sinks.

### Rate limiting

Global limiter tests use a controlled monotonic clock to check capacity, greedy refill, `Retry-After` rounding, and concurrent admission.

One component test releases 32 competing callers against capacity eight and asserts exactly eight admissions and 24 rejections.

Per-project limiter tests similarly race 50 callers against capacity eight and assert eight admissions, confirming that concurrent first use does not create multiple independent buckets for the same project.

HTTP tests verify that:

- Global admission precedes backend body buffering; an exhausted global bucket produces 429 before the size filter's 413.
- Project quotas apply only after successful credential resolution and request validation.
- Separate projects have separate project budgets.
- Two active credentials for one project share its project quota.
- Rejected project requests do not append events.
- Rejection metrics use controlled reason/project tags without raw credentials.
- Invalid configuration values prevent startup.
- Servlet path variants and HTTP methods consume the intended number of global tokens.

`GlobalIngestRateLimitPathHttpTests` exercises important embedded-Tomcat routing variants, including `/even%74s`, `/events;probe=1`, `/events/`, and `//events`, and checks that every HTTP method on `/events`, including `OPTIONS`, spends exactly one global token.

Those results establish the embedded application behavior. They do not automatically establish identical behavior at the Caddy routing boundary.

### Credential issuance, revocation, and recovery

The credential operator tests verify behavior against real PostgreSQL, often using injectable JDBC connection proxies to force failures at specific stages.

`CredentialAdminIssueTests` verifies canonical token output, its SHA-256 correspondence to the stored hash, issuance after confirmed commit, non-idempotent repeated issuance, project-lock timeout, and compatibility between the issuance project lock and a new incident insert.

It also tests uncertain commit acknowledgment and stdout failures after a committed issue. These cases distinguish a committed credential from a token that the operator actually received.

`CredentialAdminListTests` covers project-scoped inventory, empty inventory, timestamp formatting and ordering, read-only connection behavior, escaped project names, and omission of raw tokens and hashes.

`CredentialAdminRevokeTests` checks project ownership, idempotent repeated revocation, original timestamp preservation, last-active-credential protection, explicit forced revocation, bounded lock waits, and committed-state verification.

Its strongest concurrency case uses two real JDBC sessions targeting the last two active credentials. The first revocation pauses after counting credentials while holding the project lock. The second attempts to revoke the other key and is observed blocked by PostgreSQL. After release, the test requires one credential revoked, one active, and the second unforced revocation rejected.

This catches the race where two operators could otherwise each observe two active keys and revoke both.

Other checked-in scenarios inject a target credential's out-of-band revocation or movement to another project before the conditional UPDATE. They establish that the scoped update does not silently overwrite the earlier change or revoke a credential moved elsewhere.

Commit-failure tests distinguish a COMMIT successfully sent to PostgreSQL with its acknowledgment lost from a COMMIT intercepted before reaching the server. In both cases the operator reports uncertainty rather than pretending to infer the outcome from SQLState.

Additional tests cover post-commit connection cleanup, failed output delivery, and recovery after an uncertain revoke.

`CredentialRevocationInFlightTests` establishes the accepted deterministic ordering:

1. A credential resolves successfully.
2. Its revocation commits.
3. The previously authenticated project identity still permits a writer call.
4. A fresh HTTP request using the revoked credential returns 401.
5. An active sibling credential still works, when present.

The tests also cover forced revocation of the project's final credential.

This is a characterization of already-resolved work, not cancellation or a continuously overlapping HTTP/revocation race.

`CredentialAdminProcessTests` starts an actual Java subprocess and checks stdout/stderr separation, dedicated logging configuration, and password-canary omission for a failed login.

For operator procedures and recovery instructions, see [credential-operations.md](../prod/credential-operations.md).

## Negative Controls

Negative controls test whether a verification mechanism can detect a deliberately introduced defect.

### Checked-in and automated

The repository includes several forms of permanent negative-control testing.

**Database role contract mutations:** The verifier tests deliberately grant forbidden privileges, add memberships, change ownership, introduce schema objects, and modify default privileges. The verifier must report the corresponding contract violation. The transaction is then rolled back.

**pgJDBC diagnostic contrast:** The enabled-error-detail test demonstrates that the synthetic failing-row marker really appears when error detail is enabled. The disabled case proves that its absence is attributable to the tested driver setting rather than an input that never reached PostgreSQL.

**Real database permission failure:** The resolver HTTP failure test revokes the app role's credential-table SELECT grant, verifies PostgreSQL returns `42501`, and then checks the application's sanitized HTTP 500 response before restoring the grant.

**Credential commit and lock fault injection:** JDBC proxies create precise failure windows, including lost COMMIT acknowledgments, failed cleanup, out-of-band credential changes, and blocked concurrent operator operations. Assertions check durable database state as well as the reported outcome.

These tests remain part of the normal test source and can run in CI.

### Historical manual controls

The project context records earlier temporary negative controls against the ingestion aggregation design, including removing conflict handling or replacing atomic database mutation with application-side read-modify-write.

Those experiments were reported to expose duplicate-key failures or lost updates under concurrent calls.

They support the historical rationale for the concurrency suite, but the mutations are **not checked-in regression tests** at this baseline and have not been reproduced since.

A historical manual control must not be counted as a permanent CI protection.

## Historical Acceptance Evidence

The following matrices keep two milestones separate:

1. The initial TriageHarbor production acceptance on November 19, 2026.
2. The later synthetic-only TimeMetrics integration on November 19–20, 2026.

Implementation and automated-test evidence can establish mechanisms without proving that a historical deployment executed them. Conversely, a historical screenshot or operator observation can establish a dated result without proving every internal mechanism or the system's current state.

Production acceptance and the later TimeMetrics integration are distinct historical evidence sets. The original TimeMetrics screenshots and raw Vercel/Sentry logs are not committed to this repository. The classifications below therefore distinguish preserved screenshot descriptions, operator output, operator reports, source inspection, and CI rather than presenting them as newly reproduced observations.

### TriageHarbor production acceptance

Historical acceptance evidence covers fresh commissioning of commit `e2305b0` and synthetic checks through the production HTTPS/Caddy boundary. It applies to that specific deployment and test period, not continuous verification of current production state.

| Property | Enforcement | Verification | Evidence | Status | Limitations |
|---|---|---|---|---|---|
| Public HTTPS ingestion | Caddy public `/events` route; Spring ingest controller | Primary-key synthetic request returned HTTP `202` over HTTP/2; dashboard incident visibility was operator-reported | Historical operator acceptance; Caddy and ingest source | **Passed — historical production acceptance** | HTTP `202` and dashboard observation answer different questions; no current availability or production load claim |
| Credential authentication and revocation | Canonical token shape, SHA-256 lookup, `revoked_at IS NULL`, empty HTTP `401` | Malformed and well-formed unknown keys returned `401`; temporary key returned `202`, then `401` after revoke; primary key still returned `202` | Historical operator acceptance; resolver/controller/revocation tests | **Passed — historical production acceptance** | An in-flight request during revocation was not exercised; results apply to one deployment and primary database |
| Persistence and incident grouping | Transactional incident UPSERT plus event INSERT; project/version/fingerprint uniqueness | Synthetic primary-key event appeared in the dashboard with occurrence count one; grouping/concurrency covered separately by CI | Historical operator acceptance; current code and CI evidence | **Persistence observed; grouping mechanism CI-tested** | Dashboard visibility is operator-reported; production acceptance did not run a repeated-event grouping test |
| Dashboard behavior | Caddy Basic Auth, project-scoped read SQL, React summary UI | Unauthenticated `/` returned a Caddy Basic challenge; login and incident visibility were operator-reported | Historical operator acceptance; read service/controller/UI source | **Access boundary observed; authenticated behavior operator-reported** | No application-level project ownership authorization; current public dashboard was not inspected |
| Migration and deployment readiness | Flyway V1–V4, migration verifier, Compose migration-success gate, digest-selected backend | Fresh host commissioning recorded role bootstrap, `current=4, pending=0`, project provisioning, and healthy PostgreSQL | Historical operator acceptance; migrations, tests, and `compose.prod.yaml` | **Passed — historical production acceptance** | Does not inspect current image state, live ACLs, backups, restore behavior, or later upgrades |
| Operational credential safeguards | Inventory, unique-file issue, scoped revoke, last-key guard, recovery procedures | Initial and temporary 47-byte issuance, receipt matching, inventory, scoped revoke, surviving primary key, exact-token log scans, and token-file cleanup recorded | Historical operator acceptance; credential tests and operations runbook | **Passed for the exercised lifecycle** | Leak detection covered exact raw values only; primary token retirement and a real-client cutover were deferred |
| Caddy route-variant acceptance | `handle /events` and `handle_path /api/*` rules | Recorded responses distinguish direct, encoded, parameterized, double-slash, and Basic-authenticated `/api/events` paths | Historical operator acceptance and Caddy source | **Passed for the recorded frontend/edge image** | Re-run after Caddy/image changes; `/api/events` reached Spring but persistence with both credentials was not attempted |

### TimeMetrics synthetic integration

The retained TimeMetrics branch was inspected separately from the TriageHarbor repository. It remains unmerged at `f48682841a15dae77706260df3319d462a701cc6`, based on `main` commit `6b8cf63dac22b2588ba898d0ef9cd639b8e82ac1`. Reporter and instrumentation commits are `fa5730f` and `db76ca1`. The historical deployment ID was `dpl_EjAUdtzfmngKMpsXUzXF4GvBYKVj`; operator cleanup notes say that deployment was later deleted.

| Property | Enforcement | Verification | Evidence | Status | Limitations |
|---|---|---|---|---|---|
| Exact synthetic capture boundary | Exact message, route type, and route path match; fixed three-field JSON | Current branch source inspection; reported TimeMetrics unit-test execution | Reporter source/tests at retained branch | **Implemented; tests present and historical pass reported** | Establishes only the two fixed synthetic cases, not safe handling of arbitrary real errors |
| Node-only, independent Sentry composition | `NEXT_RUNTIME === "nodejs"`; separate contained Sentry and reporter paths | Current source/tests; positive live Sentry observations | Instrumentation source/tests and preserved Sentry screenshot descriptions | **Implemented; tests present and positive live cases observed** | Sentry capture for the final failed-ingest request was not independently retained |
| One bounded delivery attempt | One `fetch`, 2,500 ms abort signal, `redirect: "error"`, no retry; promise registered with `waitUntil` | Current source/tests; reported lint, typecheck, build, and 153 Vitest tests across 16 TimeMetrics files | Reporter/instrumentation tests and historical execution summary | **Implemented; historical test run reported passed** | The 153 tests are TimeMetrics tests, not TriageHarbor backend tests; no live timeout or universal platform-failure test |
| Protected Preview and branch-only credential | Vercel Require Log In plus dedicated branch-scoped Preview Secret | Operator verification for the historical deployment | Preserved operator evidence | **Operator-reported historical configuration** | Not enforced by Git source; current Vercel settings and absence of broader token scope were not inspected |
| Intentional render and handler failures | Two branch-only routes throw fixed sentinels | Vercel screenshots described at the historical deployment | Preserved Vercel screenshot descriptions and timestamps | **Observed — preserved screenshot evidence** | Intentional application HTTP `500` is test input, not an ingest failure; routes must never be merged to `main` |
| Reporter transport acceptance | HTTPS POST outcome logged independently from the application response | One `triageharbor:sent:202` shown for each initial route | Vercel screenshot descriptions | **Observed — preserved screenshot record** | HTTP `202` alone does not independently prove persistence or unique delivery |
| Two persisted incident identities | TriageHarbor fingerprints the fixed exception type/message within one project | Dashboard showed distinct render and handler incidents, initially one occurrence each | TriageHarbor dashboard screenshot descriptions | **Observed — preserved screenshot record** | Screenshots do not independently expose the fingerprint algorithm; source establishes that separately |
| Repeated-request grouping | Database UPSERT increments the matching incident | Handler count changed `1 → 4`; render remained `1` | Final dashboard screenshot description | **Observed count aggregation** | The three individual additional `sent:202` log entries were not retained; this is not per-request traceability or exactly-once proof |
| Positive Sentry preservation | Existing Sentry reporter remains independently invoked | Matching render and handler issues, one initial event each | Sentry screenshot descriptions | **Observed for the two positive cases** | Does not establish Sentry behavior after the final HTTP `401` or under every failure mode |
| Dedicated credential lifecycle | Separate credential permits scoped revocation while a pre-existing credential remains active | Final inventory showed the Preview credential revoked and one pre-existing credential active | Preserved operator inventory output | **Observed — operator output** | Deleted issuance receipt prevents a later independent receipt-to-revocation ID comparison; inventory is not source attribution for events |
| Post-revocation denial | Active-credential lookup rejects a revoked token with HTTP `401` | Operator reported `triageharbor:status:401` from the old deployment | Preserved operator report | **Reported — operator only** | Exact `401` timestamp, post-`401` counts, and final-request Sentry result were not independently retained |
| Secret, deployment, and token cleanup | Revoke before deleting deployment/Secret snapshots; delete exact token files | Droplet file absence was checked; Vercel and laptop cleanup were operator-confirmed | Operator-output and operator-report summaries | **Mixed: droplet absence observed; other cleanup reported** | Current external state was not re-queried; cleanup claims must retain their evidence class |
| Retained demonstration branch | Git branch kept separate from `main` | Local and remote-tracking refs inspected at `f486828`; branch is not merged | Current TimeMetrics Git state | **Verified by current source inspection** | Retention does not mean an old deployment or credential remains active; a future push may trigger a new Preview |

The demonstration did **not** establish real production error collection, safe serialization of arbitrary messages or stacks, guaranteed delivery, exactly-once ingestion, deduplication, retry-safe occurrence counts, durable queuing, monitoring SLOs, universal network-failure containment, or an always-on public demo.

For a controlled future run, use the [TimeMetrics Synthetic Demo Runbook](timemetrics-demo-runbook.md). A future demo creates new evidence; it cannot repair missing evidence from the original November 2026 run retroactively.

## Verification Boundaries

Verification evidence has different strengths and scopes.

| Evidence level | What it supports | What it does not establish |
|---|---|---|
| Static source inspection | Code paths, SQL, constraints, test assertions, intended mechanisms | That the behavior passed when executed |
| Local automated test output | Observed behavior in the reported local setup | CI equivalence or production deployment |
| GitHub Actions | Successful checks on a specific commit and runner environment | Production runtime correctness or untested interleavings |
| Historical deployed observation | A specifically recorded external behavior in a dated environment | Unrecorded steps, exhaustive guarantees, or current external state |

The properties in the [Verification Matrix](#verification-matrix) are verified by automated tests that passed in CI on contents matching `e2305b0`, under their test conditions. The sections below record what that evidence leaves open, separated by why.

### Tested in CI, with limits beyond the matrix

- **Concurrency under the restricted role:** the 50-call contention tests run under the administrative test datasource; role-restricted ingestion is tested sequentially. The combination has not been tested.
- **Incident reads:** the `LIMIT 100` boundary (101 or more rows) is not exercised, and snapshot consistency between the project-existence and incident-list queries is not established.
- **Credential revocation timing:** the deterministic resolve–revoke–write ordering is tested; a revocation committing during an already-running writer transaction is not.
- **Database diagnostics:** exception-message suppression is tested; all PostgreSQL structured error fields, framework log levels, and downstream sinks are not.
- **Migration and operator preflight:** fresh and deliberately altered schema states are tested; arbitrary production upgrade paths are not.
- **Concurrency test isolation:** worker termination and local cleanup safeguards exist, but complete suite isolation across every failure is not established.
- **Redaction execution-time bounds:** adversarial timeout tests are regression guards, not production latency or throughput measurements.
- **Routing variants:** path-variant tests establish embedded-Tomcat behavior, not identical behavior at the Caddy boundary.

### Historical external observations

Production acceptance and TimeMetrics integration evidence are classified separately in [Historical Acceptance Evidence](#historical-acceptance-evidence). No automated test exercises the complete Caddy → Spring → PostgreSQL production topology, and neither dated run establishes current external state.

### Not tested

- **Persistence through the `/api/events` alias:** production routing to Spring was observed after dashboard authentication; a request with both dashboard auth and a valid ingest key was not attempted.
- **Production admission behavior:** 413, 429, their precedence, and CORS preflight were not exercised in production.
- **Live production database ACLs:** the role contract has run against the test topology, not an inspected production ACL snapshot.
- **Multiple deployed backend processes:** PostgreSQL constraints provide the mechanism, but multi-instance ingestion, sustained load, and connection-pool saturation have not been exercised.
- **Real-event payload safety:** The TimeMetrics demonstration used two fixed synthetic cases and did not authorize or test arbitrary production error capture.
- **Frontend automated tests:** `frontend/package.json` defines build and lint scripts but no test script.

### Not implemented

Cross-instance quota enforcement, incident reopening, application-level read ownership checks, event deduplication, retry-idempotent ingestion, and background queues or workers are not implemented. They are recorded as limitations in [architecture.md](architecture.md), not as gaps in existing tests.

For local database setup and reset procedures, see [dev/postgres/README.md](../dev/postgres/README.md). For frontend development, see [frontend/README.md](../frontend/README.md).

The central testing principle is that **each consequential claim needs an enforcement mechanism, an adversarial test where practical, and a stated limit on what that test proves**.
