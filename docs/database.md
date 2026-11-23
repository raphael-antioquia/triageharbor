# Database

TriageHarbor uses PostgreSQL as the authority for relational integrity, incident identity, and concurrency-sensitive aggregation. Flyway owns schema evolution; Spring Boot uses JDBC for the current ingestion and incident-read paths, with JPA mappings validated against the migrated schema.

This document describes the implemented database model at **`main` commit `e2305b0`**. It distinguishes database-enforced invariants from SQL transaction behavior and application-level contracts. Exact DDL belongs in the migrations; test execution and verification results belong in [testing.md](testing.md).

## 1. Schema Authority and Evolution

### Flyway is the schema authority

Database changes are introduced through versioned Flyway migrations. Hibernate does not create or update the application schema.

The current migration history is:

| Migration | Responsibility |
|---|---|
| V1 — `initial_schema` | Creates `projects`, `incidents`, and `events`, including relational constraints and the incident-recency index |
| V2 — `add_project_ingest_key` | Adds a unique, generated UUID ingest key to each project |
| V3 — `grant_runtime_table_privileges` | Grants the runtime role the table operations required by the original ingestion and read paths |
| V4 — `project_ingest_credentials` | Adds hashed, independently revocable credentials, grants runtime read access to them, and removes `projects.ingest_key` |

V2 remains part of historical migration order, but its UUID ingest-key column is **not part of the current schema**. V4 retires that column without backfilling credentials. Existing installations using the legacy key must therefore plan credential issuance and client cutover explicitly; a migration does not automatically preserve legacy authentication.

Historical migrations remain unchanged after release. New changes require subsequent migrations, with their associated constraints, data-transition decisions, and privilege grants.

### Migration prerequisites and verification

`beforeMigrate.sql` is a Flyway callback requiring both `triageharbor_migrator` and `triageharbor_app` to exist. If either role is absent, it raises SQLState `55000` and refuses migration before application-table creation.

This is a **role-existence guard**, not a complete database-security audit. It does not independently establish correct memberships, ownership, or grants.

Production separates execution modes:

- `migrate` runs Flyway as `triageharbor_migrator`, without starting an HTTP server. `MigrationVerifier` checks Flyway's reported state, rejecting failed, missing-successful, future-successful, and pending migrations.
- `serve` runs the normal Spring application as `triageharbor_app`, with Flyway disabled in `compose.prod.yaml`.
- Project provisioning and credential administration are explicit operator activities, not schema migrations.

Normal backend startup depends on successful completion of the migration service in the production Compose configuration.

### Hibernate's responsibility

The application sets:

`spring.jpa.hibernate.ddl-auto=validate`

The current JPA mappings are `Project`, `Incident`, and `Event`. Their identifiers are UUIDs, and their timestamp properties use Java `Instant`.

Hibernate validation provides a mapping-consistency check. It does **not** establish that PostgreSQL has the intended checks, foreign keys, uniqueness, indexes, defaults, ownership, or runtime privileges.

The ingestion and incident-read paths use `JdbcTemplate` and explicit SQL; JPA entities are not the source of truth for those statements. No JPA entity is required for the current credential table.

## 2. Relational Structure

The current application model consists of four tables.

| Table | Responsibility | Principal relationships |
|---|---|---|
| `projects` | Defines the tenant/project boundary | Parent of incidents and ingest credentials |
| `incidents` | Holds one aggregate per project and fingerprint identity | Belongs to one project; parent of events |
| `events` | Stores individual accepted occurrences | References an incident using project and incident IDs together |
| `project_ingest_credentials` | Stores hashed ingest credentials and lifecycle metadata | Belongs to one project; multiple credentials may coexist |

Flyway separately maintains `flyway_schema_history`; it is not a business-domain table.

### Projects

`projects` contains:

| Column | Type | Rule |
|---|---|---|
| `id` | UUID | Primary key; default `gen_random_uuid()` |
| `name` | VARCHAR(200) | Required |
| `created_at` | TIMESTAMPTZ | Required; default `now()` |

The project ID identifies the tenant across the current persistence model.

Project names are not constrained to be unique. Project creation does not automatically create an ingest credential.

### Incidents

`incidents` contains an identifier, `project_id`, `fingerprint_version`, `fingerprint`, `title`, `status`, `occurrence_count`, `first_seen_at`, `last_seen_at`, and `created_at`.

Its primary key is `id`, with a default of `gen_random_uuid()`.

The two additional uniqueness constraints serve different purposes:

- `UNIQUE (project_id, id)` supplies a composite referenced key for enforcing event ownership.
- `UNIQUE (project_id, fingerprint_version, fingerprint)` establishes the logical identity of a grouped incident within one project.

The fingerprint is stored as `VARCHAR(128)`. PostgreSQL enforces its non-null presence and maximum length, but does not check that it is a SHA-256 digest or restrict it to fingerprint versions currently implemented by Java.

The incident title is required text. It is a snapshot derived from the first event that creates the incident, rather than a value automatically maintained as subsequent occurrences arrive.

### Events

`events` contains:

| Column | Type | Rule |
|---|---|---|
| `id` | UUID | Primary key; default `gen_random_uuid()` |
| `project_id` | UUID | Required |
| `incident_id` | UUID | Required |
| `occurred_at` | TIMESTAMPTZ | Required; sender-reported |
| `received_at` | TIMESTAMPTZ | Required; default `now()` |
| `exception_type` | TEXT | Nullable |
| `message` | TEXT | Required |
| `stack_trace` | TEXT | Nullable |

The central relationship is:

`events(project_id, incident_id)` → `incidents(project_id, id)`

This composite foreign key, named `events_incident_project_fk`, prevents an event from identifying project B while referencing an incident belonging to project A.

The event table does not need another standalone foreign key from `events.project_id` to `projects.id`: the composite incident relationship, together with the incident-to-project foreign key, already establishes project existence and consistency.

Events do not contain a generic raw JSON payload or a client-supplied deduplication identifier.

### Ingest credentials

`project_ingest_credentials` contains:

| Column | Type | Rule |
|---|---|---|
| `id` | UUID | Primary key; default `gen_random_uuid()` |
| `project_id` | UUID | Required; references a project |
| `key_hash` | BYTEA | Required; unique; exactly 32 bytes |
| `key_prefix` | VARCHAR(11) | Required; constrained display format |
| `created_at` | TIMESTAMPTZ | Required; default `now()` |
| `revoked_at` | TIMESTAMPTZ | Nullable |

The `key_prefix` check requires `th_` followed by eight base64url-alphabet characters. It is display metadata, not an authentication secret.

`key_hash` is unique across all stored credentials, including revoked credentials. The prefix is not unique: different keys may share the same visible prefix.

The database does not store the full raw ingest token. However, the database also cannot establish from these columns alone that the hash was computed from a securely generated canonical token or that the prefix matches the unhashed token.

`project_ingest_credentials_project_fk` references `projects(id)`.

## 3. Integrity Invariants

PostgreSQL protects relational facts even when application-side validation is bypassed.

### Project ownership and uniqueness

| Invariant | Enforcement |
|---|---|
| Each incident references an existing project | `incidents_project_fk` |
| Each event references an existing incident belonging to its declared project | `events_incident_project_fk` |
| Each credential references an existing project | `project_ingest_credentials_project_fk` |
| One incident row per `(project_id, fingerprint_version, fingerprint)` | `incidents_fingerprint_unique` |
| The same fingerprint may exist in separate projects or under different versions | Project ID and version participate in the unique key |
| Credential hashes cannot be duplicated across projects | `project_ingest_credentials_hash_unique` |

The composite event foreign key is particularly important: checking only `incident_id` existence would not establish that the supplied `project_id` agrees with the incident's owner.

### Check constraints

| Constraint | Database rule |
|---|---|
| `incidents_fingerprint_version_positive` | `fingerprint_version > 0` |
| `incidents_occurrence_count_positive` | `occurrence_count > 0` |
| `incidents_seen_order_valid` | `last_seen_at >= first_seen_at` |
| `incidents_status_valid` | Status is `open` or `resolved` |
| `project_ingest_credentials_hash_length` | `octet_length(key_hash) = 32` |
| `project_ingest_credentials_prefix_format` | Prefix matches `^th_[A-Za-z0-9_-]{8}$` |

Primary keys, foreign-key columns, and required business fields also use `NOT NULL` constraints.

The database permits a positive occurrence count without requiring an equal number of event rows. It likewise permits a legal status without checking how that status was reached.

The checked-in `DatabaseConstraintTests` exercises important violations through real PostgreSQL, asserting SQLState `23503` for foreign keys, `23505` for uniqueness, `23514` for checks, and `23502` for required-column violations. Execution evidence and its limits are recorded in [testing.md](testing.md).

### Delete behavior

All three domain relationships use `ON DELETE RESTRICT`:

- Project → incident
- Incident → event
- Project → ingest credential

Consequently, deleting a project while dependent incidents or credentials remain is rejected. Deleting an incident while events reference it is also rejected.

There is no automatic cascading deletion of incident history or credentials through these foreign keys.

An operator with sufficient privileges could deliberately delete dependent rows in the correct order. The constraints protect referential integrity; they do not prohibit all authorized destructive operations.

### Defaults and database-generated values

The database defines:

- UUID defaults through `gen_random_uuid()` on the four domain primary keys.
- `now()` defaults for project, incident, and credential creation timestamps.
- `events.received_at DEFAULT now()`.
- `incidents.status DEFAULT 'open'`.
- `incidents.occurrence_count DEFAULT 1`.

These defaults support direct SQL inserts and other callers that omit the relevant values.

They do not necessarily run for every application insert. The current JDBC ingestion writer explicitly supplies the incident count, fingerprint version, and receipt timestamps while allowing the database to generate incident and event IDs. JPA's `GenerationType.UUID` is a separate identifier-generation mechanism used when entities are persisted through Hibernate.

## 4. Indexes and Query Support

The primary and unique constraints create supporting PostgreSQL indexes.

Important indexes include:

| Index or constraint | Columns | Purpose |
|---|---|---|
| `incidents_fingerprint_unique` | `project_id`, `fingerprint_version`, `fingerprint` | Enforces incident identity and supplies the UPSERT conflict target |
| `incidents_project_id_id_unique` | `project_id`, `id` | Supports the composite event foreign key |
| `project_ingest_credentials_hash_unique` | `key_hash` | Enforces hash uniqueness and supports credential lookup |
| `incidents_project_last_seen_idx` | `project_id`, `last_seen_at DESC` | Supports project-scoped incident-recency queries |
| Primary-key indexes | Each table's `id` | Enforces row identity and supports ID lookups |

The current incident-read query filters by `project_id`, orders by `last_seen_at DESC, id DESC`, and limits the result to 100 rows.

The explicit recency index matches the project filter and leading timestamp sort key. It does not include the `id DESC` tie-breaker, so the index alone should not be claimed to fully satisfy the query's ordering.

### Intentionally absent indexes

There is no dedicated child-side index beginning with `events(project_id, incident_id)`. V1 explicitly defers it because incident-event listing and incident deletion are not current application workflows.

Before introducing either workflow, the project context calls for a supporting index. A potential design is `(project_id, incident_id, received_at DESC)`, subject to the actual query requirement.

V4 likewise documents the intentional absence of a child-side index on `project_ingest_credentials.project_id`. Credential inventory is currently small and operator-managed, and project deletion is not a normal workflow.

These omissions are deliberate scope decisions, not measured performance optimizations. Revisit indexing when a relevant query, deletion operation, or measured workload justifies it.

## 5. Atomic Incident Aggregation

### Incident identity is database-enforced

The logical incident key is:

`(project_id, fingerprint_version, fingerprint)`

Two transactions may concurrently encounter the same previously unseen error. The database unique constraint prevents both from committing separate incidents with that identity.

Uniqueness alone would cause one plain competing insert to fail. The writer also needs a conflict-handling statement to let successful concurrent ingests aggregate into the same row.

### The UPSERT

`EventIngestService.ingest()` uses `INSERT ... ON CONFLICT ... DO UPDATE ... RETURNING id`.

For a new incident, it inserts an initial occurrence count of 1 and uses the current receipt timestamp for both first-seen and last-seen.

For an existing incident with the same identity, PostgreSQL evaluates these updates against the existing row:

- `occurrence_count = incidents.occurrence_count + 1`
- `first_seen_at = LEAST(incidents.first_seen_at, EXCLUDED.first_seen_at)`
- `last_seen_at = GREATEST(incidents.last_seen_at, EXCLUDED.last_seen_at)`

The conflict target is the three-column logical incident identity.

This avoids an application-side read-modify-write sequence in which two concurrent callers might both read count 5 and then both write 6, losing one occurrence.

PostgreSQL coordinates conflicting row updates within the statement. `RETURNING id` supplies the incident identifier whether the row was newly inserted or updated, allowing the following event insert to reference the correct incident.

The positive-count and timestamp-order constraints are separate safeguards: they reject invalid stored values but do not themselves implement increments or timestamp aggregation.

### Transaction boundary

The writer is a Spring-managed service method annotated `@Transactional`.

Its current sequence is:

1. Redact event text.
2. Resolve the already-authenticated project ID from the supplied application object.
3. Capture one receipt timestamp and derive fingerprint and title.
4. UPSERT the incident and obtain its ID.
5. INSERT the event referencing that incident.
6. Commit the transaction.

The incident mutation and event insert participate in the same transaction. If the event insert fails and the transaction rolls back, its preceding incident mutation is rolled back too.

The checked-in `eventInsertFailureRollsBackIncidentUpsert` test forces a not-null violation in the event insert and asserts that no incident or event remains.

Credential resolution and rate-limit token consumption occur before the writer transaction. They are not rolled back when the writer fails. No external network call occurs inside this writer.

A separate checked-in connection-retention test asserts that the configured JPA transaction manager has already checked out a Hikari connection before the first SQL statement inside a transaction. Thus the pre-SQL redaction and fingerprint work is still relevant to connection occupancy; it is not evidence of network I/O or a reason to introduce an additional transaction.

### What the concurrency mechanism guarantees

For successful ingests using the current writer and the same logical incident identity:

- The unique constraint prevents two committed incident rows with that identity.
- The conflict update increments from the database's existing count rather than a stale application-read value.
- `LEAST` and `GREATEST` preserve the earliest and latest supplied receipt timestamps independently of update order.
- The transaction commits the incident mutation and associated event insert together, or rolls them back together.

The checked-in concurrency tests exercise 50 concurrent service calls against both a missing incident and an existing incident. Their assertions require one incident, 50 occurrences and 50 events in the creation case; the existing-row case requires 51 occurrences and events after its initial seed.

These tests use real PostgreSQL within one JVM. Multi-instance deployed contention, sustained hotspot throughput, and production connection-pool behavior have not been verified; see [testing.md](testing.md) for the evidence and its limits.

The mechanism protects the aggregate transition; it does not guarantee that every attempted request succeeds under resource exhaustion, lock timeouts, database failure, or other transaction errors.

## 6. Fingerprint and Event Semantics

### Versioned fingerprint identity

The application stores both `fingerprint_version` and `fingerprint` in each incident.

The historical v1 implementation and the current v2 implementation use length-delimited SHA-256 over the sanitized `exceptionType` and `message`. Each field is encoded using its UTF-8 byte length, followed by a separator and its contents; a null value has a distinct encoding.

The result is a lowercase hexadecimal digest.

V2 was introduced because expanded ingest-credential-aware redaction changes possible fingerprint inputs. It is a change in input semantics rather than a change from SHA-256 to another hash algorithm.

The current writer uses `EventFingerprintV2.VERSION`, which is 2.

Because the version is part of database uniqueness, historical v1 incidents are not silently rewritten, merged into v2 incidents, or re-fingerprinted. The same underlying failure may exist as separate incidents under different versions.

PostgreSQL stores and distinguishes these values; Java defines the algorithm and its canonical input representation.

### Sanitization and title snapshots

`RedactedEvent.from()` applies `SensitiveDataRedactor` to `exceptionType`, `message`, and `stackTrace` before the writer derives the fingerprint, derives the incident title, or persists event text.

The current redactor uses deterministic pattern-based handling for supported URL-userinfo, JWT-shaped, sensitive-assignment, and ingest-credential-shaped inputs. Its rule order participates in v2 semantics.

For a new incident, `IncidentTitle.derive()` selects a nonblank exception type when available, otherwise the message, and limits the title to 200 Unicode code points.

When a matching incident is updated, the UPSERT does not rewrite `title`. It remains the original snapshot.

**These are application behaviors, not PostgreSQL sanitization constraints.** A caller with direct SQL write privileges can supply arbitrary event text or incident titles consistent with the column constraints. Pattern-based redaction also does not establish removal of every possible secret or personal identifier.

### Timestamp meanings

All stored event and incident times use `TIMESTAMPTZ`, representing absolute instants rather than preserving a sender's original time-zone label.

Two different time concepts are retained:

| Timestamp | Meaning | Source |
|---|---|---|
| `events.occurred_at` | When the client says the event happened | Sender-provided |
| `events.received_at` | When TriageHarbor captured the occurrence for ingestion | Application `Clock`; database default for omitted values |
| `incidents.first_seen_at` | Earliest receipt timestamp among aggregated writes | UPSERT |
| `incidents.last_seen_at` | Latest receipt timestamp among aggregated writes | UPSERT |
| `created_at` | Database creation timestamp | `now()` default |
| `project_ingest_credentials.revoked_at` | Recorded credential revocation time, if revoked | Operator SQL using `now()` |

For each normal ingest, the writer captures `Clock.instant()` once and supplies that value to both incident aggregation and the event's `received_at`.

The incident first/last-seen values are **not** derived from the untrusted `occurred_at`.

The database enforces first-seen/last-seen ordering but does not enforce that a supplied receipt time truly came from the server clock, that a client timestamp is plausible, or that a revocation time is no earlier than credential creation.

### Repeated submissions are not deduplicated

Every successful repeated submission of the same error:

- increments the matching incident's occurrence count;
- inserts a new event row;
- keeps the existing incident identity and title.

An event UUID identifies a stored row, not the logical event submitted by a client. There is no client-event ID, deduplication unique constraint, or retry ledger in V1–V4.

If a client times out after the database commits, retrying may record another occurrence. The client cannot infer from a timeout alone whether the original transaction committed.

Therefore, atomic aggregation is **not** retry idempotency or exactly-once ingestion.

## 7. Database Identities and Privileges

TriageHarbor separates administrative, migration, and runtime responsibilities.

| Identity | Role |
|---|---|
| `triageharbor_admin` | PostgreSQL bootstrap superuser used for initialization and explicit role provisioning |
| `triageharbor_migrator` | Restricted login identity for Flyway and bounded operator-managed data operations |
| `triageharbor_app` | Restricted identity for normal application SQL |

### Database and schema access

The production bootstrap gives the migrator and application roles `CONNECT` on the application database, without `CREATE` or `TEMPORARY` database privileges.

Within the `public` schema:

- Migrator: `USAGE`, `CREATE`.
- Application: `USAGE`.
- `PUBLIC`: no schema privileges under the intended contract.

Both restricted roles are configured without superuser, database-creation, role-creation, replication, or row-level-security-bypass capabilities. The security contract also requires no memberships involving either restricted role.

The migrator creates and owns the migrated application tables. This does not require claiming that it owns the PostgreSQL `public` namespace itself; the bootstrap grants schema access, while the contract specifically checks table ownership.

### Runtime table allowlist

The runtime privileges are:

| Table | `triageharbor_app` privileges |
|---|---|
| `projects` | SELECT |
| `project_ingest_credentials` | SELECT |
| `incidents` | SELECT, INSERT, UPDATE |
| `events` | INSERT |
| `flyway_schema_history` | None |

V3 provides the original grants. V4 adds SELECT access on the credential table.

Incident SELECT access is needed by the conflict-update/returning path in addition to the incident-read API.

The runtime role cannot legitimately use its table privileges to create projects, issue or revoke credentials, read raw events directly, delete incidents, or modify Flyway history.

These are PostgreSQL privilege boundaries rather than controller conventions. `DatabaseRuntimePrivilegesTests` includes permitted end-to-end operations through the restricted connection and negative SQL probes expecting insufficient-privilege SQLState `42501`.

### Credential lookup and operator mutations

Credential issuance produces canonical unpadded base64url tokens from 32 cryptographically secure random bytes. The resolver checks the `th_` prefix, length, and permitted character alphabet, without trimming or normalization. It does not independently validate canonical base64url encoding. The exact supplied token is hashed with SHA-256 and matched against an active stored credential:

`project_ingest_credentials` using `key_hash = ? AND revoked_at IS NULL`.

The unique hash provides an unambiguous stored-credential match. The active-credential predicate is implemented in SQL by the resolver; PostgreSQL does not globally prohibit other privileged queries from accessing revoked rows.

Credential issue and revoke operations use the standalone `CredentialAdmin` program under the migrator identity.

`CredentialAdmin` serializes cooperating credential mutations with a project-row `SELECT ... FOR NO KEY UPDATE` lock at READ COMMITTED, using a bounded lock timeout. This lock choice remains compatible with the key-share lock used for foreign-key checks when ingestion inserts an incident.

Revocation conditionally updates the matching credential with `revoked_at = now()` using the credential ID, project ID, and `revoked_at IS NULL`, with `RETURNING id` to establish that the expected row changed.

The last-active-credential safeguard belongs to this operator protocol. It is **not a PostgreSQL constraint**: zero active credentials are valid database state, including after an explicitly forced last-key revocation.

Issuance is not idempotent. Revocation treats an already-revoked, correctly scoped credential as a no-op that preserves its original revocation time. An uncertain commit outcome still requires the documented operator recovery procedure.

Fresh credential lookups after committed revocation reject the revoked credential. A project identity resolved before revocation may still be used by an already-admitted writer afterward; the writer does not re-check the original token.

See [credential-operations.md](../prod/credential-operations.md) for issuance, rotation, revocation, and uncertain-outcome recovery procedures.

### Privilege contract verification

`backend/src/main/resources/db/verification/database-role-contract.sql` provides a portable PostgreSQL security verifier.

At this commit it returns **47 named Boolean checks**, covering role attributes and memberships, database/schema ACLs, ownership, exact table inventory, runtime grants, grant options, column privileges, and relevant default privileges. The expected public table inventory includes the four domain tables and `flyway_schema_history`.

The contract also requires the absence of unexpected public sequences, views, materialized views, and routines.

It is executable verification SQL, not a PostgreSQL constraint that automatically prevents future privileged DDL changes. Updating a migration or bootstrap script must preserve the contract and its associated tests.

The role-separated Testcontainers configuration supplies independent runtime and Flyway connection identities. Other integration tests use a Testcontainers service connection and should not be treated as proof that every workload was exercised under the restricted role.

## 8. Current Limits and Deferred Database Features

The following capabilities are not present in the migrated schema or current persistence path:

- **Retry idempotency:** no client event identity or deduplication constraint.
- **Incident regression:** the schema permits `open` and `resolved`, but the UPSERT does not change a resolved incident back to open when a matching occurrence arrives. The intended transition is deferred and must eventually be concurrency-safe.
- **Enforced occurrence/event equality:** no database constraint reconciles `occurrence_count` against the number of event rows.
- **Database-enforced tenant read authorization:** relational ownership is enforced, but the read service accepts a supplied project UUID. The application has table-level incident SELECT privileges and no implemented project-ownership authorization or PostgreSQL RLS policy in the schema.
- **Full sensitive-data protection:** scrubbing is application-side and pattern-bound, not a database-enforced property.
- **Retention and partitioning:** `events` is a single unpartitioned table. Automatic retention and time-based partitioning are deferred. If partitioning is introduced, server-controlled `received_at` is the intended time basis rather than client-controlled `occurred_at`.
- **Incident-event listing and deletion indexes:** supporting indexes are deferred until relevant operations are introduced.
- **Credential expiry and usage tracking:** there is no `expires_at`, `last_used_at`, credential label, or authentication cache.
- **Durable asynchronous work:** no queue, lease, or background-recovery table is defined by V1–V4.

Additional infrastructure, indexing, or data migration mechanisms should follow a demonstrated requirement rather than be introduced speculatively.

For broader service boundaries, see [architecture.md](architecture.md). For a claim-by-claim test matrix and verification limits, see [testing.md](testing.md). Local PostgreSQL setup and reset procedures belong in [dev/postgres/README.md](../dev/postgres/README.md).
