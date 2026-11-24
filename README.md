# TriageHarbor

**An incident-monitoring platform built around backend correctness, concurrency, and database security.**

TriageHarbor receives application error events, groups related failures into incidents, tracks their occurrence counts, and presents incident summaries through a React dashboard.

Built with Java, Spring Boot, and PostgreSQL, it is a backend-focused portfolio project emphasizing enforceable data integrity, concurrency-safe persistence, credential management, integration testing, and deployment.

Production acceptance in November 2026 verified fresh commissioning and synthetic HTTPS ingestion. A later controlled demonstration connected a protected TimeMetrics Vercel Preview to the live ingest path. Neither milestone approved arbitrary production error collection or established guaranteed delivery.

## Overview

TriageHarbor implements a small, functional incident-monitoring system:

- **Event ingestion:** An HTTP API accepts authenticated application-error events.
- **Incident grouping:** Events are grouped using project-scoped, versioned fingerprints.
- **Atomic aggregation:** PostgreSQL creates or updates incidents without application-side read-modify-write.
- **Event persistence:** Each successfully committed ingestion appends an event record, but individual-event retrieval is not yet exposed through the API or dashboard.
- **Credential lifecycle:** Operators can issue, rotate, list metadata for, and revoke project-scoped ingest credentials.
- **Admission controls:** Global and per-project rate limits protect ingestion within a single application instance.
- **Sensitive-data scrubbing:** Defined secret patterns are redacted before fingerprinting, title derivation, and persistence.
- **Incident dashboard:** A React application displays incident titles, statuses, counts, and first/last-seen timestamps.

The project deliberately uses a single Spring Boot backend and PostgreSQL rather than adding distributed infrastructure before it is required.

## Engineering Highlights

### 1. Concurrency-safe incident aggregation

Incident identity is protected by a PostgreSQL unique constraint on:

`(project_id, fingerprint_version, fingerprint)`

An `INSERT ... ON CONFLICT DO UPDATE` statement handles both new-incident creation and updates to existing incidents.

The occurrence count increases inside PostgreSQL using:

```sql
occurrence_count = incidents.occurrence_count + 1
```

This avoids the application-side read-modify-write race that can lose concurrent updates.

**Test evidence:** Two PostgreSQL integration tests exercise 50 concurrent service calls:

- Fresh incident: one incident, occurrence count 50, and 50 event rows.
- Existing incident: one incident, occurrence count 51, and 51 event rows after the initial event.

These tests passed in the backend CI suite. They establish correctness for the tested concurrent writes within one JVM against PostgreSQL, not multi-instance performance or exactly-once processing.

[View concurrency tests](backend/src/test/java/com/triageharbor/event/EventIngestConcurrencyTests.java) · [Backend CI](https://github.com/raphael-antioquia/triageharbor/actions/runs/43971516028)

### 2. Database-enforced integrity and transactional writes

Flyway migrations define the database schema and its integrity constraints.

PostgreSQL enforces incident identity, valid occurrence counts and timestamps, supported incident statuses, and project/incident/event relationships. A composite foreign key prevents an event from referencing an incident belonging to another project.

Incident aggregation and event insertion execute within one Spring-managed transaction.

A PostgreSQL integration test deliberately causes the event insert to fail and verifies that the preceding incident mutation is rolled back.

This establishes an all-or-nothing persistence boundary for the two writes.

[Database migrations](backend/src/main/resources/db/migration) · [Transaction test](backend/src/test/java/com/triageharbor/event/EventIngestServiceTests.java)

### 3. Least-privilege database access

TriageHarbor separates database responsibilities across three identities:

| Identity | Responsibility |
|---|---|
| `triageharbor_admin` | Database initialization and explicit role provisioning |
| `triageharbor_migrator` | Flyway migrations and operator-managed database operations |
| `triageharbor_app` | Restricted application runtime |

The application role can read projects and credential hashes, read and update incidents as required, and insert events. It cannot directly manage credentials, modify projects, read raw event history, or perform schema administration.

A portable SQL contract checks 47 aspects of the intended PostgreSQL role and privilege topology.

Integration tests exercise permitted operations and verify that prohibited SQL operations fail with insufficient-privilege errors.

These guarantees have been tested against PostgreSQL; the CI results are not a substitute for inspecting privileges on the deployed production database.

[Database role contract](backend/src/main/resources/db/verification/database-role-contract.sql) · [Privilege tests](backend/src/test/java/com/triageharbor/database/DatabaseRuntimePrivilegesTests.java)

### 4. Hashed credentials and controlled ingestion

Ingest credentials are generated from 32 cryptographically secure random bytes and represented as `th_`-prefixed base64url tokens.

The database stores SHA-256 hashes of the full tokens rather than the raw credentials.

Credentials support overlapping rotation and independent revocation. Each authenticated request resolves its project from the active credential, and multiple credentials belonging to the same project share that project's rate limit.

A standalone operator CLI manages issuance, inventory, and revocation. Revoking the final active credential normally requires an explicit override.

Fresh credential lookups reject revoked credentials, but revocation does not cancel work that has already been authorized.

The ingestion path also applies a 256 KiB request-body limit, JVM-local Bucket4j rate limits, and pattern-based sensitive-data scrubbing. Scrubbing is not a guarantee that all sensitive information is removed.

[Credential operations](prod/credential-operations.md)

### 5. Containerized delivery and CI verification

The backend uses Java 21 and a multi-stage Dockerfile with extracted Spring Boot application layers. Its runtime container uses a non-root UID/GID.

GitHub Actions checks formatting, performs SpotBugs analysis, runs the backend tests, builds the container image, verifies image invariants, and publishes to GitHub Container Registry.

Production Compose uses a digest-selected backend image, a separate migration execution mode, and a migration-success gate before starting the backend.

[Backend CI](https://github.com/raphael-antioquia/triageharbor/actions/runs/43971516028) · [Backend image publication](https://github.com/raphael-antioquia/triageharbor/actions/runs/43971515959)

## How It Works

```text
Monitored application
        |
        | POST /events
        | X-TriageHarbor-Ingest-Key
        v
   HTTPS / Caddy
        |
        v
   Spring Boot API
        |
        +-- Global admission limit
        +-- Request size and JSON validation
        +-- Credential resolution and project limit
        |
        v
   Transactional ingestion
        |
        +-- Scrub event content
        +-- Compute versioned fingerprint
        +-- UPSERT incident
        +-- INSERT event
        |
        v
     PostgreSQL
        |
        | Project-scoped incident read
        v
   Spring Boot API
        |
        | GET /projects/{projectId}/incidents
        v
   Caddy /api/* + Basic Auth
        |
        v
   React dashboard
```

The public ingestion endpoint uses an ingest credential, whereas the production Caddy configuration protects the dashboard and its read path with Basic Auth.

The Spring read API scopes its SQL queries by project ID but does not yet perform application-level user/project ownership authorization.

### API endpoints

| Endpoint | Purpose | Response |
|---|---|---|
| `POST /events` | Submit an application event | `202 Accepted` on success |
| `GET /projects/{projectId}/incidents` | Retrieve incident summaries for a project | `200 OK`, or `404` for an unknown project |

Ingestion requires `X-TriageHarbor-Ingest-Key`. Missing, malformed, unknown, or revoked credentials return `401 Unauthorized` when the request reaches credential validation.

An event request uses:

```json
{
  "occurredAt": "2026-11-21T12:00:00Z",
  "exceptionType": "ExampleException",
  "message": "Synthetic application failure",
  "stackTrace": "Optional stack trace"
}
```

`occurredAt` and `message` are required. The other two fields are optional.

The incident read endpoint returns at most 100 summaries, ordered by descending last-seen timestamp and then descending incident ID. It currently has no pagination.

## Technology Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4.1.1, Spring MVC |
| Database | PostgreSQL 17, Flyway, JDBC, Hibernate schema validation |
| Ingestion controls | Bucket4j, Jakarta Bean Validation, custom scrubbing |
| Frontend | React 19, TypeScript, Vite |
| Reverse proxy | Caddy |
| Testing | JUnit 5, Spring Boot Test, Testcontainers |
| Code quality | Spotless, SpotBugs |
| Containers and delivery tooling | Docker, Docker Compose, GitHub Actions CI, GHCR image publication |

## Getting Started

### Prerequisites

- Java 21
- Docker Engine and Docker Compose
- Git and curl
- Node.js 24 and npm if running the frontend

The backend includes a Gradle wrapper; a separate Gradle installation is unnecessary.

The commands below describe the repository-matched local setup. They have not been executed as part of preparing this README.

### 1. Start PostgreSQL

From the repository root:

```bash
docker compose up -d postgres
```

The development Compose file starts PostgreSQL 17.11 on localhost port 5432.

On an empty database volume, its initialization script creates separate migration and application roles using development-only credentials.

Previously initialized local database volumes may not have the required role topology. See [Local PostgreSQL setup](dev/postgres/README.md) before attempting any reset, which would delete local data.

### 2. Start the backend

From the repository root:

```bash
cd backend
./gradlew bootRun
```

The default local configuration connects to the PostgreSQL container at `localhost:5432`.

Local configuration defaults are defined in [`application.properties`](backend/src/main/resources/application.properties). The principal database variables are:

| Variable | Purpose |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME` | PostgreSQL connection |
| `DB_USER`, `DB_PASSWORD` | Application runtime identity |
| `DB_MIGRATOR_USER`, `DB_MIGRATOR_PASSWORD` | Flyway migration identity |

The local defaults are development-only. Production uses separately configured passwords and runs migration and serving as distinct execution modes.

Flyway applies schema migrations; Hibernate validates the mapped schema rather than generating it independently.

### 3. Create a local project and credential

Starting PostgreSQL and the backend does not automatically create a project or issue an ingest credential.

For a disposable local demo on Linux, create a project using the development database administrator. From a **second terminal at the repository root**:

```bash
PROJECT_ID="$(
  docker compose exec -T postgres \
    psql -U triageharbor -d triageharbor -Atc \
    "INSERT INTO projects (name) VALUES ('Local demo') RETURNING id"
)"
```

Build a local backend image containing the standalone credential-administration program:

```bash
(
  cd backend
  ./gradlew bootJar
  docker build -t triageharbor-backend:local .
)
```

Issue a credential. The token goes to a private temporary file (stdout) and the receipt to a second file (stderr). The command is accepted only if it exits with status zero, the token file holds exactly one token line (47 bytes), and the receipt confirms a commit for this project:

```bash
TOKEN_FILE="$(mktemp)"
RECEIPT_FILE="$(mktemp)"
ISSUED=0

if docker run --rm --network host \
     -e DB_HOST=127.0.0.1 \
     -e DB_PORT=5432 \
     -e DB_NAME=triageharbor \
     -e DB_MIGRATOR_USER=triageharbor_migrator \
     -e DB_MIGRATOR_PASSWORD=triageharbor_migrator \
     --entrypoint java \
     triageharbor-backend:local \
     -cp application.jar com.triageharbor.CredentialAdmin \
     issue --project "$PROJECT_ID" \
     > "$TOKEN_FILE" 2> "$RECEIPT_FILE" \
   && [ "$(wc -c < "$TOKEN_FILE")" -eq 47 ] \
   && grep -Eq "^Credential [0-9a-f-]{36} committed for project ${PROJECT_ID} \(name=" "$RECEIPT_FILE"
then
  ISSUED=1
  cat "$RECEIPT_FILE"
else
  cat "$RECEIPT_FILE" >&2
  echo "Issuance failed or its outcome is uncertain. Do not retry; follow prod/credential-operations.md." >&2
fi
```

This example uses Linux Docker host networking and the known development-only database credentials. It is not a production provisioning procedure.

Issuance is not idempotent. If the check fails, stop: a non-zero exit can mean a credential was committed but its token was not delivered. Do not rerun `issue` automatically; use the recovery procedure in [Credential operations](prod/credential-operations.md).

### 4. Send a synthetic event

Build a private curl header file only if issuance was confirmed:

```bash
if [ "$ISSUED" = 1 ]; then
  HEADER_FILE="$(mktemp)"
  printf 'X-TriageHarbor-Ingest-Key: %s\n' \
    "$(head -n 1 "$TOKEN_FILE")" > "$HEADER_FILE"
else
  echo "No confirmed credential; not building the header file." >&2
fi
```

Submit an event:

```bash
curl -q -i \
  -H @"$HEADER_FILE" \
  -H 'Content-Type: application/json' \
  --data '{
    "occurredAt": "2026-11-21T12:00:00Z",
    "exceptionType": "DemoException",
    "message": "Synthetic local failure"
  }' \
  http://localhost:8080/events
```

A successful request returns `202 Accepted`.

Check the resulting incident summaries:

```bash
curl -sS \
  "http://localhost:8080/projects/$PROJECT_ID/incidents"
```

The response should contain the incident created by the accepted event.

Delete the local temporary credential, receipt, and header files after the demonstration:

```bash
rm -f -- "${HEADER_FILE:-}" "$TOKEN_FILE" "$RECEIPT_FILE"
```

Do not commit tokens, credentials, or private header files to the repository.

### 5. Run the frontend (optional)

From the repository root in another terminal:

```bash
cd frontend
npm ci
cp .env.example .env.local
```

Set `VITE_PROJECT_ID` in `.env.local` to the local project's UUID, then run:

```bash
npm run dev
```

The Vite development server proxies `/api` requests to the backend on port 8080 and removes the `/api` prefix.

The project ID is public configuration, **not** an ingest credential. The development frontend displays incidents for one configured project.

For details, see [Frontend development](frontend/README.md).

## Testing and CI

The primary backend quality gate is:

```bash
cd backend
./gradlew spotlessCheck spotbugsMain test
```

Integration tests use real PostgreSQL instances through Testcontainers rather than mocked database behavior for constraints, concurrent updates, transaction rollback, and role privileges.

The tests cover:

- Concurrent creation and updating of incidents.
- Tenant-aware foreign keys and incident uniqueness.
- Transaction rollback when event insertion fails.
- Credential validation, rotation overlap, and revocation behavior.
- Restricted database privileges and role-contract violations.
- Rate-limit admission, rejection, and refill behavior.
- HTTP ingestion, redaction, and incident-read contracts.

The backend CI workflow runs on qualifying pull requests and pushes to `main`. Its successful run for `e2305b0` completed formatting, static analysis, and tests.

The image-publication workflow separately repeats those checks before packaging and pushing the backend image. The frontend image workflow runs linting and verifies its built artifact before publication.

[Backend CI results](https://github.com/raphael-antioquia/triageharbor/actions/runs/43971516028) · [Testing details](docs/testing.md)

## Deployment and Current Limitations

### Deployment status

**Initial production walking-skeleton acceptance was completed on November 19, 2026.**

The accepted deployment used commit `e2305b0` on a single DigitalOcean host with Docker Compose, PostgreSQL 17.11, the Spring Boot backend, React, and Caddy. Commissioning completed role bootstrap, Flyway V1–V4 migration with `current=4, pending=0`, project provisioning, and initial credential issuance.

Acceptance checks through the public HTTP/2 HTTPS boundary included:

- Caddy Basic Auth rejecting an unauthenticated dashboard request.
- A synthetic event returning HTTP `202` and appearing in the dashboard with one occurrence; dashboard login and visibility were operator-reported.
- Malformed and well-formed unknown ingest credentials returning backend HTTP `401`.
- Caddy route-variant behavior, including authenticated `/api/events` forwarding to Spring; persistence through that alias was not attempted.
- A temporary credential succeeding with `202`, being revoked, then returning `401`, while the primary credential continued to return `202`.
- Exact raw credential values absent from the inspected Docker service logs.

The accepted backend artifact was `ghcr.io/raphael-antioquia/triageharbor-backend@sha256:aa9acee069c0855aad1fe638f90fe99d10a2053a8b00538130a8ad8c5691b10b`; the frontend/edge artifact was `ghcr.io/raphael-antioquia/triageharbor-frontend:a05553989c37b57907d982583dc445b2ee8c312f`. The deployment used `https://triageharbor.com`.

During a later controlled TimeMetrics demonstration, a deployment-protected Preview generated one intentional render failure and one Route Handler failure. Each application request returned HTTP `500`, while the reporter separately received TriageHarbor HTTP `202`. The dashboard showed two distinct persisted incidents. Three additional handler requests increased that incident from one occurrence to four while the render incident remained at one. Sentry independently captured both synthetic error types.

The demonstration client used a strict synthetic allowlist, fixed three-field payload, Node-only reporting, one 2,500 ms HTTPS attempt, Vercel `waitUntil` registration, and no automatic retry. The demonstration was implemented on a separate, unmerged test branch at commit f486828. The remote branch is no longer present on GitHub as of November 24, 2026. The intentional crash routes are not production TimeMetrics application code.

After the demonstration, the dedicated Preview credential was revoked while the primary acceptance credential remained active. A post-revocation reporter HTTP `401` was operator-reported, but its exact timestamp, unchanged incident counts, and Sentry result for that final request were not retained as independent evidence.

These are historical acceptance results; current production state was not re-inspected for this documentation update.

The production Compose configuration separates database bootstrap, migration, project provisioning, credential administration, and normal serving. Operational commands and recovery procedures are maintained in [Credential operations](prod/credential-operations.md).

See [Testing and Verification](docs/testing.md#historical-acceptance-evidence) for the evidence classification and [TimeMetrics synthetic demo runbook](docs/timemetrics-demo-runbook.md) for a controlled replay procedure.

### Current limitations

TriageHarbor remains an evolving backend system, with important boundaries:

- **No ingestion retry idempotency:** Repeating an accepted event creates another event and increases the incident count. A retry after an ambiguous HTTP outcome may double-count.
- **Best-effort client delivery:** The demonstrated TimeMetrics reporter makes one bounded HTTP attempt without automatic retries. `waitUntil` improves the opportunity to finish but does not guarantee delivery.
- **No automatic incident reopening:** Matching new events do not currently change a resolved incident back to open.
- **No application-level read authorization:** Deployment-level Caddy Basic Auth protects the dashboard, but the backend read endpoint does not independently authorize project ownership.
- **Instance-local rate limits:** Bucket4j quotas do not provide coordinated limits across multiple backend instances.
- **Pattern-based redaction only:** The scrubber covers defined patterns and cannot guarantee removal of all secrets or personal information.
- **Limited investigation UI:** The current dashboard displays incident summaries, not individual raw-event details or incident-management controls.
- **No background processing pipeline:** Queues, leased workers, notifications, and automated recovery workflows are not part of the implemented walking skeleton.
- **Additional edge hardening deferred:** The production Caddy configuration can also forward an authenticated `POST /api/events` to the ingestion endpoint. Restricting that route to read methods remains deferred.

The historical TimeMetrics exercise covered only two fixed, allowlisted synthetic errors. It did not establish safe handling of arbitrary messages or stacks, an always-on integration, real-error capture, deduplication, exactly-once accounting, durable delivery, or a monitoring SLO.

## Further Reading

### Engineering documentation

- [Architecture](docs/architecture.md) — Components, request flows, trust boundaries, and design tradeoffs.
- [Database](docs/database.md) — Schema, integrity constraints, atomic aggregation, and privileges.
- [Testing](docs/testing.md) — Verification matrix, concurrency evidence, negative controls, and limits.
- [TimeMetrics synthetic demo runbook](docs/timemetrics-demo-runbook.md) — Safe replay, evidence capture, revocation, and cleanup.

### Existing operational and development documentation

- [Frontend development](frontend/README.md)
- [Local PostgreSQL setup](dev/postgres/README.md)
- [Credential operations](prod/credential-operations.md)

The implementation, Flyway migrations, tests, Docker configuration, and GitHub Actions workflows remain the authoritative sources for current behavior.

---

**TriageHarbor is built to demonstrate engineering depth through explicit invariants, deliberate failure testing, and evidence-backed implementation decisions.**
