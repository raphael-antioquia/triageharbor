# Ingest credential operations

Operator procedures for TriageHarbor ingest credentials: commissioning, issuance,
rotation, revocation, recovery, cutover and rollback. Applies to
`compose.prod.yaml` from PR #12 onward.

TriageHarbor currently holds no real production data. Everything in the
deployment is synthetic and disposable. Deletion is still a deliberate,
approved action against one exact, verified target.

## 1. Rules for every operation

Run all commands on the production host, from the repository root, with the
production `.env` in place.

- Never pass `-p` or set `COMPOSE_PROJECT_NAME`. The file pins
  `name: triageharbor-prod`. An operator run under another project name
  silently creates an empty `<name>_postgres_data` volume, and a later `up`
  under that name starts an empty database.
- Never run `docker compose down -v`. Remove volumes only by exact name, after
  inspecting them.
- Never run `up` with `--profile credential-admin`. `up` also starts the
  unprofiled services, including `migrate`.
- Changing `BACKEND_DIGEST` and running `up -d` applies pending migrations
  automatically, because `migrate` is a dependency of `backend`.
- Run every credential command exactly as:

  ```bash
  docker compose -f compose.prod.yaml --profile credential-admin \
    run --rm --no-deps -T credential-admin <command> ... < /dev/null
  ```

  - `--no-deps`: never start or recreate PostgreSQL from an operator command.
    PostgreSQL must already be running and healthy.
  - `-T`: never allocate a TTY. A TTY merges stderr into stdout.
  - `< /dev/null`: the command reads no input.
- The service uses Docker's `none` logging driver, so `docker logs` cannot
  recover its output. That does not protect terminal scrollback, shell history,
  redirected files, `tee`, CI logs or terminal recorders. Always redirect
  `issue` stdout to a private file. Never print it.

Set these once per shell:

```bash
dc() { docker compose -f compose.prod.yaml "$@"; }
PROJECT_ID=$(tr -d '\r\n' < frontend/production-project-id)
install -d -m 700 ~/triageharbor-secrets
```

## 2. Exit codes

| Exit | Meaning |
|---|---|
| 0 | Done. A connection-cleanup warning printed with exit 0 needs no action. |
| 1 | Read stderr before doing anything. The CLI uses 1 for failures and uncertain outcomes, but the JVM ("Could not find or load main class") and Compose also exit 1 before the CLI runs. Never infer from exit 1 alone whether a database change committed. |
| 2 | Invalid command or arguments. Nothing executed. |

## 3. Preflight before any credential command

```bash
hostname
docker context show
docker info --format 'docker_engine_host={{.Name}}'
compgen -e | grep -E '^(COMPOSE_|DOCKER_HOST$|DOCKER_CONTEXT$|DB_|BACKEND_DIGEST$|TRIAGEHARBOR_|FRONTEND_DEPLOY_SHA$|SITE_ADDRESS$|DASHBOARD_)' \
  || echo 'no deployment environment overrides'
docker volume ls --filter label=com.docker.compose.project=triageharbor-prod
dc ps postgres
```

Expect the production host and Docker engine, no overrides (names only are
printed), `triageharbor-prod_postgres_data` present and PostgreSQL `healthy`.
If anything is unexpected, stop. A familiar project name alone does not
identify the right host.

## 4. Inventory

```bash
dc --profile credential-admin run --rm --no-deps -T credential-admin \
  list --project "$PROJECT_ID" < /dev/null
```

Tab-separated: `project_id`, `project_name`, `credential_id`, `key_prefix`,
`created_at_utc`, `revoked_at_utc`. A provisioned project with no credentials
prints one row with empty credential fields. `key_prefix` (`th_` plus eight
characters) identifies a credential; it never authenticates. Use
`credential_id` to revoke.

## 5. Issue a credential

Each issuance writes to a new, unique private file created by `mktemp`, so it
never truncates a token captured earlier:

```bash
TOKEN_FILE=$(mktemp "$HOME/triageharbor-secrets/ingest-token.XXXXXXXX")
( umask 077
  dc --profile credential-admin run --rm --no-deps -T credential-admin \
    issue --project "$PROJECT_ID" \
    < /dev/null \
    > "$TOKEN_FILE" \
    2> "$TOKEN_FILE.stderr" )
echo "issue_exit=$?"
printf 'token_file=%s\n' "$TOKEN_FILE"
stat -c 'mode=%a bytes=%s' "$TOKEN_FILE"
grep -F ' committed for project ' "$TOKEN_FILE.stderr"
```

Success: `issue_exit=0`, `mode=600 bytes=47` (`th_`, 43 base64url characters,
newline) and one receipt line naming the new credential ID. Printing the path
is safe; never `cat`, `echo` or paste the file's contents.

Always generate a new `TOKEN_FILE` before another issuance. Never repeat only
the `dc ... > "$TOKEN_FILE"` part: the shell truncates the earlier token file
before the CLI starts.

Hand the token to the client through its own secret configuration. Keep the
file until the client is verified (section 8.4), then delete it:

```bash
rm -- "$TOKEN_FILE" "$TOKEN_FILE.stderr"
```

The frontend uses only the project ID. Never put an ingest credential in
frontend build arguments, environment files or the repository.

Issuance is not idempotent. Never rerun `issue` after an unclear failure; see
section 9.

## 6. Rotate (overlap)

1. Issue a new credential (section 5).
2. Reconfigure the client with the new token.
3. Confirm the client's events arrive using the new credential.
4. Revoke the old credential by ID (section 7).

Both credentials authenticate during the overlap. One successful event cannot
prove that no stale client instance still holds the old token.

## 7. Revoke

```bash
dc --profile credential-admin run --rm --no-deps -T credential-admin \
  revoke --project "$PROJECT_ID" --credential <credential-id> < /dev/null
```

Success (exit 0):
`Credential <id> revoked for project <project-id> (name=<name>).`

Revoking a credential that is already revoked exits 0, reports the original
`revoked_at` and changes nothing:
`Credential <id> already revoked at <timestamp> for project ... No change made.`

### Last active credential

Without `--force-last`, revoking the last active credential is refused with
exit 1 and nothing changes:
`Credential <id> is the last active credential for project <project-id>. ...`

Adding `--force-last` revokes it and prints:

```text
WARNING: project <project-id> now has no active ingest credential; ingestion fails until a new credential is issued.
```

`--force-last` is a deliberate ingestion outage. New requests receive 401
until a new credential is issued and the client is reconfigured.

The guard is enforced by the CLI under a project-row lock. It is cooperative,
not a database constraint: a writer that bypasses the CLI, such as direct SQL
as `triageharbor_migrator`, can leave a project with zero active credentials.

### Revocation contract

> A request whose credential lookup began before revocation committed may still persist its event, even if its transaction commits afterward. A lookup that begins after revocation commits, against the same primary database, rejects the credential.

Revocation prevents new authentication; it does not cancel requests already
authenticated. No upper bound on how long such a request can remain in flight
is claimed. The WARNING above describes new requests only.

## 8. Fresh commissioning and cutover

The rehearsed path is a fresh database. It applies to the initial deployment
and to the V3 to V4 cutover. V4 drops `projects.ingest_key`: legacy keys stop
working, and the client needs a newly issued credential.

### 8.1 Confirm the image

`BACKEND_DIGEST` in `.env` must reference an image that contains PR #12. With
no arguments, the CLI must exit 2 and print its usage:

```bash
digest=$(grep -E '^BACKEND_DIGEST=' .env | cut -d= -f2-)
docker run --rm --network none --entrypoint java \
  "ghcr.io/raphael-antioquia/triageharbor-backend@$digest" \
  -cp application.jar com.triageharbor.CredentialAdmin
echo "exit=$?"
```

### 8.2 Reset the database (explicit approval required)

Before anything else, obtain explicit approval for the planned outage and for
resetting this exact PostgreSQL volume, and confirm the Docker host and project
(section 3). `dc down` itself starts the outage.

```bash
dc down
docker ps -a --filter label=com.docker.compose.project=triageharbor-prod
docker volume inspect triageharbor-prod_postgres_data \
  --format 'project={{index .Labels "com.docker.compose.project"}} created={{.CreatedAt}}'
```

`down` without `-v` keeps volumes. Continue only if no containers remain and
the label is `project=triageharbor-prod`:

```bash
docker volume rm triageharbor-prod_postgres_data
```

Keep `triageharbor-prod_caddy_data`; it holds TLS certificate state.

### 8.3 Commission

```bash
dc up -d --wait postgres
dc --profile role-bootstrap run --rm role-bootstrap
dc run --rm migrate
dc --profile project-provision run --rm -T project-provision
```

Expected: `triageharbor role bootstrap complete`, a successful migration
ending in `Flyway migration verification succeeded: current=4, pending=0`, and
`project provisioning: converged`. Provisioning never issues a credential; an
inventory (section 4) shows the project with no credentials.

### 8.4 Issue, start, activate

1. Issue the first credential (section 5).
2. Start the stack: `dc up -d`.
3. Configure the client with the new token.
4. Verify a synthetic event through the public HTTPS boundary
   (`https://<SITE_ADDRESS>/events`) and confirm it in the dashboard.

To check the route without the client, send one synthetic event using the
`TOKEN_FILE` from section 5. The unique header file is removed when the
subshell exits:

```bash
( umask 077
  header_file=$(mktemp "$HOME/triageharbor-secrets/ingest-header.XXXXXXXX") || exit 1
  trap 'rm -f -- "$header_file"' EXIT
  python3 - "$TOKEN_FILE" "$header_file" <<'PY'
import sys
from pathlib import Path

header = Path(sys.argv[2])
header.write_bytes(b'X-TriageHarbor-Ingest-Key: ' + Path(sys.argv[1]).read_bytes())
header.chmod(0o600)
PY
  curl -q -sS -o /dev/null -w '%{http_code}\n' \
    -H @"$header_file" \
    -H 'Content-Type: application/json' \
    --data "{\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"message\":\"cutover check $(date +%s)\"}" \
    "https://<SITE_ADDRESS>/events" )
```

Expect `202`, then confirm the `cutover check` event in the dashboard. A 202
alone is not the acceptance criterion. `-q` must stay the first curl option: it
stops curl from loading `~/.curlrc`. Never use `curl -v` or `--trace`: both
print request headers. The trap does not run if the process is killed outright;
the private directory still protects a leftover file.

## 9. Recovery

| Situation | What it means | Do |
|---|---|---|
| `issue` stderr: `Commit outcome uncertain for credential <id>` | The credential may exist; nobody has its token | Run `list`. If `<id>` is present, issue a replacement, then revoke `<id>`. Never rerun `issue` blindly. |
| `issue` stderr: `Credential <id> committed; stdout failed` | Committed; token lost | Issue a replacement, then revoke `<id>`. |
| `issue` stderr: `No credential was issued.` | Nothing committed | Fix the cause, then issue again. |
| `revoke` stderr: `Revocation commit outcome uncertain` | May or may not be revoked | Re-run the identical revoke command, including `--force-last` if it was given. If the guard rejects the retry, issue a replacement first, then revoke. |
| `revoke` stderr: `No credential was revoked.` or `No change was committed.` | Nothing changed | Fix the cause, then retry. |
| `Database migration preflight failed. No command executed.` | Image and database migration state disagree | Check `BACKEND_DIGEST` against the database; never edit Flyway history. |
| `Missing or invalid database configuration.` | Nothing executed | Check the database variables in `.env`. |
| Compose configuration error, `No such image`, or `Could not find or load main class` | Failed before the CLI started | Nothing reached the database. Fix the invocation. |
| Exit 1 with no recognizable CLI message | Outcome unknown. A missing receipt does not prove nothing committed. | Preserve the captured output. Run `list` and check the credential's state before any retry. Never blindly repeat `issue`. |

If the terminal shows an operator container that `--rm` did not remove (for
example after an interrupted Docker command), inspect it and remove it by exact
name. Its configuration contains the migrator password:

```bash
docker ps -a --filter label=com.docker.compose.project=triageharbor-prod \
  --filter label=com.docker.compose.service=credential-admin
```

## 10. Rollback after V4

A pre-V4 backend cannot run against a V4 database. Its migration gate rejects
the newer schema (`FUTURE_SUCCESS`) and its ingest path reads the dropped
`projects.ingest_key`. Redeploying the old image is not a rollback.

- Preferred: a forward fix on a PR #12-or-later image.
- Because the data is disposable: an approved reset (8.2) and recommissioning
  (8.3, 8.4) with a PR #12-or-later image.

The commissioning and issuance procedure here requires an image that contains
V4 and `CredentialAdmin`. Returning to a pre-V4 image would need a separate,
explicitly approved V3 procedure and legacy client configuration; it is not a
rollback this runbook supports.

Never edit `flyway_schema_history` or weaken `MigrationVerifier`.

## 11. Verification status

Rehearsed in a disposable, production-shaped local environment (Docker 29.1.3,
Compose 2.40.3, PostgreSQL 17.11, the packaged PR #12 image): commissioning
from an empty volume, `-T` stream separation, 47-byte issuance, `docker logs`
unreadability, authenticated ingest, unknown and malformed key rejection,
overlapping rotation, revocation, the last-credential guard, `--force-last`,
idempotent repeat revocation, and no verbatim token or password in operator,
client, backend or PostgreSQL output.

Not rehearsed: production HTTPS and Caddy routing, an in-place V3 to V4
upgrade, a request in flight while revocation commits, and leak detection
beyond exact raw values.
