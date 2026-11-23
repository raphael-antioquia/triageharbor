# TriageHarbor × TimeMetrics Synthetic Demo Runbook

This runbook repeats the historical synthetic-only TimeMetrics demonstration against TriageHarbor's public ingest path.

It is a procedure, not evidence that a later demonstration occurred. Every run must create its own dated evidence record.

**Applies to:** the retained TimeMetrics branch `test/triageharbor-preview-ingest`, a deployment-protected Vercel Preview, and a TriageHarbor environment whose operator has approved the demonstration.

**Last inspected implementation:** TimeMetrics commit `f48682841a15dae77706260df3319d462a701cc6`, Next.js 16.3.3, Node.js 24, `@vercel/functions` 3.11.3.

**Safety boundary:** The branch contains two routes that intentionally return HTTP `500`. Never merge them into `main`, expose them without Vercel deployment protection, or broaden the reporter to capture arbitrary errors. Never reuse the revoked November 2026 demonstration credential.

## Status meanings

Keep these outcomes distinct throughout the demo:

| Status | Meaning |
|---|---|
| Application HTTP `500` | Expected output from an intentionally throwing TimeMetrics route |
| Reporter HTTP `202` | TriageHarbor accepted and synchronously completed that ingest request; verify dashboard state separately |
| Reporter HTTP `401` | TriageHarbor rejected the ingest credential; during the negative test this must occur after confirmed revocation |

HTTP `202` is not, by itself, the dashboard persistence observation. It also does not prove exactly-once delivery or deduplication.

## Non-negotiable controls

- Use one newly issued, dedicated credential for this run.
- Keep at least one identified pre-existing credential active; do not use `--force-last` for demo cleanup.
- Scope the new Vercel Secret to Preview and the exact test branch only.
- Confirm Vercel **Require Log In** protection with no active bypass before creating or visiting the crash routes.
- Record baseline incident counts before the first request and compare deltas rather than assuming historical counts.
- Check Vercel, TriageHarbor, and Sentry independently.
- Revoke the demo credential before removing the Vercel Secret or deployments that contain its environment snapshot.
- Preserve safe evidence metadata, never the raw token or credential-bearing headers.
- Retain the unmerged test branch after deleting its ephemeral deployment resources.

The authoritative credential procedure is [Ingest Credential Operations](../prod/credential-operations.md). Its preflight, issue, inventory, revoke, and recovery rules take precedence over abbreviated references here. This runbook deliberately does not duplicate its production commands.

## 1. Prepare an evidence record

Create a private, non-secret note using the template at the end of this runbook. Record both UTC and Toronto time.

Before changing anything, identify:

- The approved TriageHarbor environment and operator.
- The TimeMetrics repository, exact branch, and full commit SHA.
- The Vercel team/project and current Preview-protection policy.
- The current Vercel CLI version and supported environment-variable flags.
- The TriageHarbor project and its current credential inventory.
- The existing render and handler incident identities and counts, if present.

Stop if any identity or environment is ambiguous.

## 2. Validate the retained TimeMetrics branch

On the TimeMetrics workstation:

```bash
git switch test/triageharbor-preview-ingest
git status --short
git branch --show-current
git log -3 --oneline

npm run lint
npm run typecheck
npm test
npm run build
```

Require a clean working tree, the exact test branch, and successful checks. Record the actual test count; do not assume the historical 153-test total remains current.

Confirm the branch still contains:

- The exact render and handler sentinels.
- Exact message, `routeType`, and `routePath` allowlisting.
- Fixed three-field serialization.
- Independent Sentry composition.
- Node-only TriageHarbor reporting.
- One 2,500 ms HTTPS attempt registered through `waitUntil`, without automatic retry.
- Both intentional crash routes.

If the branch has drifted from the inspected implementation, review that difference before provisioning a credential. Do not relax the allowlist to work around a mismatch.

### Optional fresh incident identities

Reusing the existing sentinels and comparing count deltas is the lowest-risk replay. If visibly new incidents are required, change both sentinel constants to a fresh run suffix, update the corresponding tests, rerun all checks, and commit only to the test branch. Do not change the allowlist shape or merge the routes into `main`.

## 3. Preflight and issue one demo credential

On the TriageHarbor production host, follow these authoritative sections exactly:

1. [Rules for every operation](../prod/credential-operations.md#1-rules-for-every-operation)
2. [Preflight before any credential command](../prod/credential-operations.md#3-preflight-before-any-credential-command)
3. [Inventory](../prod/credential-operations.md#4-inventory)
4. [Issue a credential](../prod/credential-operations.md#5-issue-a-credential)

Do not reset the database, recommission the environment, change deployment images, start operator profiles with `up`, or alter unrelated credentials.

If the host requires privileged Docker access, use the environment's established privilege mechanism consistently. Do not mix user and privileged Docker contexts. Stop on an unexpected host, Docker context, Compose project, volume, database health state, or environment override.

From the initial inventory, record the UUID of at least one active, pre-existing non-demo credential that must survive cleanup. Do not hard-code a historical credential UUID or inventory size; rotations and earlier demos change the inventory.

Issue exactly one new credential. Preserve its non-secret credential UUID and issuance receipt long enough to reconcile it with inventory. Never print or paste the token. Issuance is non-idempotent: if its outcome is unclear, stop and follow [Recovery](../prod/credential-operations.md#9-recovery) rather than issuing again blindly.

Inventory again and confirm:

- The recorded pre-existing credential is still active.
- The new demo credential is active.
- The new credential UUID matches the issuance receipt.

## 4. Transfer and scope the Secret

Transfer the exact new token file through an approved private channel to a mode-700 directory and mode-600 file on the TimeMetrics workstation. Do not place the token in source control, chat, screenshots, shell arguments, terminal recordings, or command output.

Check the installed Vercel CLI rather than assuming historical syntax:

```bash
vercel --version
vercel whoami
vercel env add --help
vercel env ls --help
vercel env rm --help
```

Add `TRIAGEHARBOR_INGEST_TOKEN` as a Secret with all of these properties:

- Environment: **Preview** only.
- Git branch: **`test/triageharbor-preview-ingest`** only.
- Value: read from the private token file through standard input or another non-echoing supported method.

Inspect the Vercel dashboard and supported CLI listings. Confirm there is no Production-scoped or broad Preview copy. Do not change unrelated Supabase or Sentry variables.

Vercel environment changes apply only to newly created deployments. Do not reuse a deployment created before the Secret was added.

## 5. Create a protected Preview deployment

Before deploying, verify in Vercel:

- Project and team identity.
- **Require Log In** protection for Preview.
- No active protection exception or shareable bypass.
- Required Preview application variables are present.
- Sentry's Preview DSN is present if Sentry observation is part of this run.

Prefer the normal Git-based Preview flow for the exact test branch. If a deliberate deployment trigger is needed, use the repository's approved branch workflow; do not force-push or touch `main`.

For the new deployment, record:

- Full TimeMetrics commit SHA.
- Vercel deployment ID and immutable Preview URL.
- Preview environment and test branch.
- Ready status.
- Protection result from an unauthenticated/incognito check.
- Function duration budget relative to the 2,500 ms reporter timeout.

Verify normal TimeMetrics login and dashboard behavior before visiting either synthetic route. An unrelated application `500` is a stop condition, not a successful test input.

## 6. Record baselines

Open independent views for:

1. Vercel Function/runtime logs filtered to the new deployment.
2. The authenticated TriageHarbor incident dashboard.
3. TimeMetrics Sentry Issues.

Record current counts for the render and handler incidents. If new sentinels were used, record that the corresponding incidents are absent before the test.

Capture safe outcome lines and timestamps immediately; runtime-log retention may be short. Never capture the ingest header, raw token, or environment dump.

## 7. Run the positive demonstration

Using the authenticated, deployment-protected Preview URL:

1. Load `/triageharbor-preview/render` exactly once.
   - Expect the page request to return intentional HTTP `500`.
   - Require one `triageharbor:sent:202` reporter outcome.
   - Verify the render incident count increased by one.
   - Verify the matching synthetic render error in Sentry.
2. Request `/triageharbor-preview/handler` exactly once.
   - Expect the route request to return intentional HTTP `500`.
   - Require one `triageharbor:sent:202` reporter outcome.
   - Verify the handler incident count increased by one.
   - Verify the matching synthetic handler error in Sentry.
3. For the grouping demonstration, choose a small integer `N` and make exactly `N` additional handler requests, one at a time.
   - Preserve each individual `sent:202` outcome and timestamp.
   - Verify the handler count changed by `1 + N` from its baseline.
   - Verify the render count changed only by its single expected request.

Stop and record the discrepancy if logs and counts disagree. Do not retry an ambiguous request, induce production network failures, exhaust rate limits, or weaken the allowlist. Local tests cover modeled `401`, `429`, `500`, network, and timeout outcomes; this demonstration is not permission to create avoidable production faults.

## 8. Revoke and run the negative check

Keep the protected Preview deployment available. Return to the TriageHarbor host and repeat the full authoritative preflight and inventory.

Reconcile the demo credential UUID against the saved issuance receipt or recorded non-secret UUID plus current inventory. Confirm the pre-existing credential identified at the start remains active.

Follow [Revoke](../prod/credential-operations.md#7-revoke) for the demo credential only.

- Require a successful revoke outcome.
- Never add `--force-last` for this demo.
- If the last-active guard fires, stop and investigate; do not override it.
- Record the exact UTC revocation timestamp and post-revoke inventory.

After revocation has committed, invoke the old protected handler deployment once:

- The intentional TimeMetrics route still returns application HTTP `500`.
- The reporter must log HTTP `401`, not `sent:202`.
- The handler incident count must remain unchanged.
- Sentry must still capture the intentionally thrown exception independently.

Record the exact `401` timestamp, the comparison with the revocation time, both post-request incident counts, and the Sentry result. A `401` recorded before revocation committed does not prove revocation denial.

## 9. Remove ephemeral resources

Only after revocation and negative evidence are recorded:

1. Remove only the branch-scoped `TRIAGEHARBOR_INGEST_TOKEN` Preview Secret. Confirm no Production or broad Preview copy exists.
2. Delete only the test branch's Preview deployments and demo aliases that retain token snapshots. Do not delete the Vercel project.
3. After the demonstrated client is verified, remove the exact workstation token file and private directory if empty.
4. Remove the exact token and receipt files created for this run according to the authoritative credential runbook. Preserve the non-secret credential UUID and timestamps in the evidence record first. Never use a wildcard against the secrets directory.
5. Repeat the production preflight and final inventory. Confirm the pre-existing credential remains active, this run's credential is revoked, and earlier demo credentials remain revoked. Do not expect a fixed inventory size.
6. Re-query Vercel configuration and deployments to confirm removal rather than relying only on the deletion action.
7. Keep `test/triageharbor-preview-ingest` locally and remotely, unmerged. Inspect future pushes because they may create new Preview deployments automatically.

## Stop and recovery conditions

| Condition | Response |
|---|---|
| Wrong or ambiguous host, Docker context, Compose project, volume, project, branch, or Vercel project | Stop without performing a credential or deployment mutation |
| Unhealthy PostgreSQL or unexpected deployment override | Stop and investigate the production environment |
| No identified pre-existing active credential | Stop; do not issue/revoke as part of this demo until the baseline is reconciled |
| Issue outcome uncertain or receipt missing | Inventory and follow the authoritative recovery procedure; never issue again blindly |
| Secret has the wrong environment or branch scope | Remove the incorrect copy safely; do not deploy until scope is correct |
| Preview protection is missing or bypassable | Stop; do not visit the intentional crash routes |
| Normal TimeMetrics behavior fails | Fix or record the unrelated Preview problem before synthetic testing |
| Reporter says `skipped:not-allowlisted` | Compare safe framework route metadata with the exact source constants; never broaden capture to arbitrary errors |
| Positive run reports `401`, `429`, `500`, timeout, or network | Stop and record; do not retry automatically |
| Revoke requests `--force-last` | Stop; the preserved active-credential assumption is false |
| CLI syntax differs from this runbook | Read the installed command's help or use the exact-scope dashboard UI; do not guess |

## Evidence record template

```text
Demo date/time (UTC and Toronto):
Operator:
TriageHarbor environment/project identifier:
Pre-existing active credential UUID preserved (never token):
Demo credential UUID (never token):
TimeMetrics branch and full commit SHA:
Sentinel run suffix:
Vercel project, deployment ID, and immutable Preview URL:
Preview protection verification method/result:
Secret scope verification:
Local lint/typecheck/test/build results and actual test count:
Baseline render count / handler count:
Render application 500 time:
Render reporter 202 time:
Render persisted count and Sentry observation:
Handler application 500 time:
Handler reporter 202 time:
Handler persisted count and Sentry observation:
Additional handler requests N and each reporter outcome/time:
Final positive render count / handler count:
Revocation time UTC and inventory result:
Post-revoke handler application 500 time:
Post-revoke reporter 401 time:
Post-401 render count / handler count:
Post-401 Sentry result:
Secret removal verified:
Deployment/alias deletion verified:
Workstation and production token files absent:
Final credential inventory:
Retained branch confirmed unmerged:
Discrepancies, missing evidence, or cleanup exceptions:
```

## Evidence boundary

A successful replay establishes only the observations recorded for that run. It does not establish safe capture of arbitrary real errors, guaranteed delivery, exactly-once ingestion, deduplication, retry-safe counting, durable queuing, universal failure containment, or an always-live public demo.

For the 2026 historical result and its missing evidence, see [Testing and Verification](testing.md#historical-acceptance-evidence).
