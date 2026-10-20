---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 13
subsystem: infra
tags: [azure-key-vault, workload-identity, rbac, secrets, stripe, write-once, preflight, tdd, live]
status: complete

requires:
  - phase: 29-12
    provides: "k8s/scripts/keyvault-chain.py objects (the 27 render-derived Key Vault object names) and the six SecretProviderClasses with their client ids"
  - phase: 29-09
    provides: "jtoye-stg-kv (RBAC, purge protection), the operator's Key Vault Secrets Officer role, the ten federated identities, k8s/staging/azure-identity.yaml"
  - phase: 29-04
    provides: "scripts/staging-realm-import.sh --list-kv-objects (6 names)"
  - phase: 29-06
    provides: "scripts/staging-seed.sh --list-kv-objects (4 names)"
  - phase: 29-07
    provides: "pg-admin--password (exists; never touched here)"
provides:
  - "scripts/staging-secrets.sh: --list, --generate, --set-external NAME, --assign-access, --leak-scan FILE, --selftest (11 arms)"
  - "scripts/staging-secrets-preflight.sh: derived presence + enabled + per-object access + Stripe test-mode check, gap lines MISSING / MISSING-ACCESS / NOT-TEST-MODE, --selftest (10 arms)"
  - "scripts/lib/staging-secrets-common.sh: one derivation (three sources), the ordered class table, a stateful stub vault"
  - "k8s/scripts/keyvault-chain.py access: the per-identity grant list (clientID, object), printed only when the chain holds"
  - "jtoye-stg-kv populated: 31 derived objects (29 machine-made, 2 Stripe TEST), each once; 35 per-secret Key Vault Secrets User assignments"
  - "Stripe TEST webhook endpoint we_1USBGuDpS9ICNeRtRPsoKuOM (livemode false)"
affects: [29-14, 29-18, 29-21, 29-23, 29-24]

actuals:
  tokens: 22400
  tasks: 3
  commits: 5
plan_head_before: 3163bcdad0b8e19affa3e53accc114a65cd56829
plan_head_after: 88c7661f6ddbe910c007ee9468f7ce81775a8690

tech-stack:
  added: []
  patterns:
    - "Write-once by construction: a pinned `secret show --query id` decides; rc 0 = exists (never written again), rc 3 = absent, anything else refuses, because a permission error is not 'absent'"
    - "A value lives only in a mode-600 file in a mode-700 mktemp dir (EXIT trap) and reaches the vault through `secret set --file`; a selftest arm greps the stub's argv log for every stored secret value"
    - "Grants are derived (keyvault-chain.py access) and assigned per secret (`<vault id>/secrets/<object>`), matched by role definition id, never by display name"
    - "A leak scan matches exact stored values (fixed strings through a descriptor), not a regex, and skips estate identifiers that a correct file legitimately contains"

key-files:
  created:
    - scripts/staging-secrets.sh
    - scripts/staging-secrets-preflight.sh
    - scripts/lib/staging-secrets-common.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-13-secrets.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-13-t1-red-write-once.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-13-t1-red-one-source.json
  modified:
    - k8s/scripts/keyvault-chain.py

key-decisions:
  - "Object list = keyvault-chain.py objects (27) + realm-import (6) + seed (4), de-duplicated = 31. Nothing is hand-listed. pg-admin--password is in no source and was never touched"
  - "Classes: 9 estate, 17 random, 3 composite, 2 external. stomp-login/-passcode copy the RabbitMQ user/password; default_user.conf is `default_user = <user>` / `default_pass = <pass>` built from those two objects as read back from the vault"
  - "Grants are per secret and exact. Each identity's Key Vault Secrets User scopes equal its SPC's objects (21/2/5/3/1/3), and none is vault-wide"
  - "Task 3's dashboard step was replaced by owner-approved automation (in-session 2026-10-19): the sk_test_ key from .env through stdin, and one TEST-mode endpoint created through the API. Only its whsec_ secret was kept, also through stdin"
  - "The leak scan's N counts secret-bearing objects. Estate identifiers (`jtoye`, `5432`, the pg host) are skipped and counted separately, because they would flag a correct evidence file"

requirements-completed: [DPLY-02, DPLY-01]

coverage:
  - id: D1
    description: "Write-once writer: cannot overwrite, cannot leak through argv, cannot invent an owner value, refuses an unclassified object"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "bash scripts/staging-secrets.sh --selftest (11/11; RED 8/11 RED_EVIDENCE_OK write-once-generate)"
        status: pass
      - kind: other
        ref: "real-tree break arm: a stored value on the set argv turns argv-no-value red (18 values found), restored by hash"
        status: pass
    human_judgment: false
  - id: D2
    description: "Preflight lists exactly what is missing and refuses a half-configured deploy"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "bash scripts/staging-secrets-preflight.sh --selftest (10/10; RED 8/10 RED_EVIDENCE_OK operator-script-objects)"
        status: pass
      - kind: other
        ref: "live: empty vault rc 1 (31 MISSING + 35 MISSING-ACCESS); after generate rc 1 naming exactly the two Stripe objects; after Task 3 rc 0, 0 gaps, test-mode yes x2"
        status: pass
    human_judgment: false
  - id: D3
    description: "jtoye-stg-kv populated once, with per-object access for each identity"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "evidence/29-13-secrets.txt: 29 written, second generate 0 written, version ids equal, 1 version each; per-principal scopes == SPC objects, vault-wide 0"
        status: pass
    human_judgment: false
  - id: D4
    description: "Stripe TEST webhook endpoint registered and its secret stored"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "POST /v1/webhook_endpoints HTTP 200, livemode=false, the 10 events, preflight test-mode yes x2"
        status: pass
    human_judgment: true
    rationale: "Registration is proven. Whether a delivered event is processed is not, because the endpoint sends the account default API version 2026-07-29.dahlia and stripe-java 34.0.0 pins 2026-09-30.endive. The first real delivery (29-14/29-18) must show it."

duration: 30min
completed: 2026-10-19
---

# Phase 29 Plan 13: Key Vault Populated Write-Once, With a Preflight That Refuses Half-Configured Deploys

**jtoye-stg-kv now holds every object the staging deploy reads: 31 names derived from the render plus the two operator scripts, nothing hand-listed. Each was written exactly once, through a mode-600 file and never on argv. Each workload identity can read only its own SPC's objects, granted per secret. The preflight went RED live twice (empty vault, then only the two Stripe objects missing) and is GREEN now. Stripe is in TEST mode: a `sk_test_` key and one `livemode: false` webhook endpoint.**

## Performance

- **Duration:** about 30 min (to 2026-10-19T07:49Z)
- **Tasks:** 3 of 3. Task 3 was automated under the owner's in-session ruling, so it was not a stop point.
- **Commits:** 5 (1cf2d0f3, 51fa31d2, 7a17bf16, f43ad03e, 88c7661f)
- **Files:** 7 changed (6 created, 1 modified)

## Accomplishments

- **1cf2d0f3:** `keyvault-chain.py access` prints one `<clientID>\t<objectName>` line per SPC object, and only when `check` holds.
  - Staging: 35 lines, split core-java 21, frontend 2, pg-backup 5, keycloak 3, redis 1, secret-sync 3.
  - Fail arm: frontend's clientID changed gives rc 1 and 0 lines.
- **Task 1 (TDD: 51fa31d2 RED, 7a17bf16 GREEN):** added the writer, the preflight, and the shared library with its stateful stub vault.
- **Task 2 (f43ad03e):** live generate, assign-access, three preflight runs, and the no-op proof.
- **Task 3 (88c7661f):** the Stripe TEST values and the preflight GREEN.

## The derived object list and its classes (names only)

| Class | Objects |
|---|---|
| estate (9) | postgres-credentials--{host, port, database, username, migration-username, backup-username}, rabbitmq-credentials--username, keycloak-credentials--admin-username, smtp-credentials--username |
| random (17) | client-ip-credentials--hmac-key, dsar-credentials--encryption-key, notification-credentials--unsubscribe-signing-secret (hex64); nextauth-secret--secret (b64url32); staging-seed--test-vendor-password (realm policy); keycloak-credentials--{admin-password, frontend-client-secret}, keycloak-realm--{edge-api-client-secret, integration-catalog-ro-secret, integration-orders-rw-secret}, keycloak-server-credentials--db-password, postgres-credentials--{password, migration-password, backup-password}, rabbitmq-credentials--password, redis-credentials--password, smtp-credentials--password (alnum32) |
| composite (3) | rabbitmq-credentials--stomp-login, rabbitmq-credentials--stomp-passcode, rabbitmq-default-user--default-user-conf |
| external (2) | stripe-credentials--api-key (sk_test_), stripe-credentials--webhook-secret (whsec_) |

## Stripe endpoint

- **id:** `we_1USBGuDpS9ICNeRtRPsoKuOM`
- **url:** `https://api-staging.olajay.co.uk/api/v1/public/payments/webhook`
- **livemode:** `false`. **status:** `enabled`
- **events:** account.updated, charge.refunded, customer.subscription.deleted, invoice.paid, invoice.payment_failed, payment_intent.payment_failed, payment_intent.succeeded, refund.created, refund.failed, refund.updated
- **api_version:** none pinned (the account default). See Open items.

## Evidence (both directions)

### Task 1

**Writer `--selftest`:**
- GREEN 11/11: write-once-generate, write-once-set-external, argv-no-value, value-shapes, second-generate-noop, unclassified, external-prefix, assign-access, leak-scan, guard-unpinned, void-no-python.
- RED (`exists()` always says "absent"): 8/11. write-once-generate, second-generate-noop ("29 write(s) on a full vault") and external-prefix ("second sk_test_ rc=0") were not ok.
- `gsd_run check tdd-red-evidence` gave `RED_EVIDENCE_OK target_test_failed` (`evidence/29-13-t1-red-write-once.json`).

**Preflight `--selftest`:**
- GREEN 10/10: full, two-missing, operator-script-objects, ghost, missing-access, disabled, not-test-mode, empty-vault, guard-unpinned, void-no-python.
- RED (render source only): 8/10. operator-script-objects gave rc=0, and empty-vault gave 27 MISSING of 31.
- Classifier: `RED_EVIDENCE_OK` (`evidence/29-13-t1-red-one-source.json`).

**Semantic assessment:** in both RED runs the target arm ran against the stub and failed on its planned assertion. The other arms passed in the same run, so the harness was sound.

**Real-tree break arm for argv-no-value.** RED never exercised this arm, so it was broken on purpose:
- The arm passed each value on the set argv (as a tag). argv-no-value turned FAIL with "18 generated value(s) found in the stub's argv log" (10/11, rc 1).
- Restored with `cp`. `git hash-object` = HEAD blob `0d0ca791…`, both before and after.

**Acceptance criteria:**
- The text check, first run: `count=1 rc=0`. It matched its own header comment `(never --value)`, the "rule names the token it forbids" shape.
- After rewording the comment: `count=0 rc=1`, exit 0.
- The same check on a missing file gives `rc=2`, exit 1.
- The pattern can match: `1` on the sample line.
- Branch: `git show phase-29-research:scripts/staging-secrets.sh` rc=0, `from-literal` = 35.
- `check-az-subscription-pin.sh` rc 0, findings=0, wrapper_calls 58.

### Task 2 (live; everything is in evidence/29-13-secrets.txt)

**Precondition:**
- RBAC is True and purge protection is True.
- The operator holds Key Vault Secrets Officer at vault scope.
- Role `4633458b-…` is Key Vault Secrets User.
- An absent-secret probe gives rc 3.
- The vault held only `pg-admin--password`.

**Runs:**
- **Preflight run 1 (empty vault):** rc 1, 31 `MISSING` and 35 `MISSING-ACCESS` lines. Every principal resolved.
- **`--generate`:** 29 written, 0 existing, 2 external. Every line names its object and version id, never a value. The temp dir is gone afterwards.
- **`--assign-access`:** 35 grants, 33 created, 0 existing, 2 deferred (the two Stripe objects).
- **`--list`:** 31 objects, 0 unclassified.
- **Preflight run 3, the Task 2 verify as written:** rc 1. The only lines are `MISSING stripe-credentials--{api-key,webhook-secret}` and `MISSING-ACCESS` for the same two on core-java's principal. `named` = exactly the pair, verify exit 0.
  - Fail direction: the same comparison over run 1's recorded output names 31 objects, exit 1.
- **Second `--generate`:** 0 written, 29 `exists (write-once)`.
  - Version ids of dsar-credentials--encryption-key (`5f7af981…`), postgres-credentials--password (`cffeb673…`) and rabbitmq-default-user--default-user-conf (`1c27b9fc…`) are byte-equal before and after.
  - `list-versions` gives 1 for each.
- **Live value shapes**, read in memory with only booleans printed: dsar=hex64, hmac=hex64, host=pg_host, stomp-login=username, stomp-passcode=password, default_user.conf=inputs, vendor=policy.

**Leak scan:**
- On the evidence file: `leak-scan: 19 objects checked, 0 found (10 estate identifiers not secret, skipped)`, rc 0. 19 + 10 = 29 present.
- Control: a mktemp copy with keycloak-server-credentials--db-password's value appended gives exactly `LEAK keycloak-server-credentials--db-password`, with no value printed, rc 1.

### Task 3 (live)

- `.env` is mode 600 and holds one `STRIPE_SECRET_KEY` line. It was read in memory only: prefix `sk_test_` yes, whitespace no.
- Both Stripe objects were absent (show rc 3).
- `--set-external stripe-credentials--api-key` on stdin wrote it, version `895afeae…`.
- `GET /v1/webhook_endpoints` (key via `curl -K -` on stdin) returned HTTP 200 with count 0, so there was nothing to duplicate.
- `POST` returned HTTP 200 with `livemode=false`. Only `.secret` was taken, in memory, and `--set-external stripe-credentials--webhook-secret` on stdin wrote it, version `5b56e3bd…`.
- **Verification as written:**
  - `--assign-access`: 2 created, 33 existing, **0 deferred**.
  - Preflight: **rc 0**, `preflight: 31 objects, 35 grants, 0 gap(s)`, 0 MISSING or MISSING-ACCESS lines, exactly **2** `test-mode: yes` lines.
- **Exactness (T-29-50):** for each of the six principals, the Key Vault Secrets User scopes equal its SPC's objects (secret-sync 3, frontend 2, core-java via media-id 21, pg-backup via backup-id 5, keycloak 3, redis 1). Vault-wide assignments: 0.
- The final leak scan, with both Stripe values present and scanned, found 0 (21 checked).

## TDD Gate Compliance

| Gate | Commit | Notes |
|------|--------|-------|
| RED | `51fa31d2` | test(29-13): selftests plus deliberately weak implementations. RED_EVIDENCE_OK ×2 |
| GREEN | `7a17bf16` | feat(29-13): writer 11/11, preflight 10/10 |

`1cf2d0f3` (the `access` mode) precedes RED because the selftests' derivation needs it. Without it every arm would VOID, which is an INVALID_RED fixture failure, not RED. Its own fail direction is recorded above.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The per-identity grant list needed a source**
- **Found during:** Task 1.
- **Issue:** `--assign-access` and the preflight must know which objects each SPC identity names. `keyvault-chain.py` printed only the flat object list.
- **Fix:** an additive `access` mode, printed only when the chain holds. `check-keyvault-chain.sh --selftest` is still 11/11 and the gate is rc 0.
- **Files modified:** k8s/scripts/keyvault-chain.py
- **Commit:** 1cf2d0f3

**2. [Rule 3 - Blocking] One derivation for two scripts**
- **Found during:** Task 1.
- **Fix:** `scripts/lib/staging-secrets-common.sh` holds the derivation, the class table and the stub. It is outside `files_modified`, but the alternative was two copies of the derivation that could drift. Both scripts still name `keyvault-chain.py`.
- **Commit:** 51fa31d2 / 7a17bf16

**3. [Rule 1 - Unfalsifiable as written] Leak-scan N**
- **Issue:** the plan's "N equal to the number of derived objects present" would scan estate values such as `jtoye`, `5432` and the pg host. A correct evidence file contains these (the vault name contains `jtoye`), so the scan would fail on a clean file. This is the "expected-0 that is 1 on the correct tree" shape.
- **Fix:** N counts secret-bearing objects, and the skipped estate identifiers are printed beside it. Live: 19 checked + 10 skipped = 29 present (later 21 + 10 = 31). Both directions are recorded above.
- **Commit:** 7a17bf16

**4. [Rule 1] The text acceptance check matched its own comment**
- **Fix:** reworded the header. Before: count 1, rc 0. After: count 0, rc 1. The behavioural proof is the argv-log arm and its break arm.
- **Commit:** 7a17bf16

**5. [Rule 2 - Missing critical] A live Stripe key already in the vault**
- **Issue:** the plan's preflight only printed `test-mode: yes`.
- **Fix:** a wrong prefix now prints `NOT-TEST-MODE <object>` and exits 1 (arm not-test-mode). The value is never printed.
- **Commit:** 7a17bf16

**6. [Owner-approved] Task 3's dashboard step was automated**
- The owner ruled in-session on 2026-10-19 that the executor performs Task 3.
- The resolution file was followed exactly:
  - the key came from `.env` into memory and went to the vault on stdin
  - the key reached curl through `-K -` on stdin
  - endpoints were listed before the create
  - exactly one endpoint was created, and `livemode: false` was asserted
  - only `.secret` was kept, on stdin
- No other Stripe write was made, and nothing was done in live mode.
- **Commit:** 88c7661f

**Total deviations:** 5 auto-fixed (2 blocking, 2 unfalsifiable-criterion fixes, 1 missing-critical) plus the owner-approved Task 3 automation. **Impact:** no scope beyond the plan's surface. Every addition makes a check able to fail, or keeps one derivation.

## Issues Encountered

- A read-only probe, `ad signed-in-user show`, rejected the appended `--subscription` (Graph calls take none) and returned nothing. Nothing changed. The operator's role was confirmed instead by the vault-scope assignment listing.
- `shellcheck` is not installed on this host, so the three scripts were not shellchecked. They run under `set -uo pipefail` in both selftests and live.

## Open items (recorded, not filed: none has broken a gate, lost data or been observed failing)

- **Stripe API version mismatch.** The endpoint pins no `api_version`, so events arrive at the account default, `2026-07-29.dahlia` (the `Stripe-Version` response header). stripe-java 34.0.0 pins `2026-09-30.endive`. The app reads event data with `getDataObjectDeserializer().getObject()`, which is documented to return empty when the event's version does not match the SDK's. If so, PaymentService throws "Failed to deserialize PaymentIntent".
  - It has not been observed: test mode has 0 events.
  - 29-14/29-18 must prove the first delivery.
  - Fixing it is an owner decision, because each option is a write this plan was not authorised to make:
    - recreate the endpoint with `api_version=2026-09-30.endive` (a new `whsec_`, so the write-once object goes through the rotation runbook)
    - move the account default
    - change the app
- **`postgres-exporter-credentials--password`.** `staging-db-bootstrap.sh --stage monitor` reads it, but no derivation source lists it, so it was not generated. The plan that wires the exporter must add it to a derived source. The writer then picks it up as random (`*password*`).
- **SMTP in staging.** `smtp-credentials--username` is `staging-mailpit` and its password is random, as the plan specifies. 29-14 should confirm that core-java does not try SMTP AUTH against Mailpit, or that Mailpit accepts it.
- **#100** stays OPEN as 29-12 recorded. The preflight's live fail arm now exists, and the 29-14 live mounts remain.

## Known Stubs

None.

## Threat Flags

None beyond the register:
- T-29-47 is mitigated: write-once by construction, the dsar arm, and live version ids unchanged.
- T-29-48 is mitigated: values go through `--file` only, the argv arm and its break arm, and the leak scan.
- T-29-49 is mitigated: the prefix refusal plus NOT-TEST-MODE, and the endpoint has livemode false.
- T-29-50 is mitigated: per-secret grants, exact per principal.
- T-29-51 is mitigated: the preflight was RED live twice and is GREEN now.

The Stripe webhook endpoint is a new inbound surface, but it is the one the plan's trust-boundary table names (Stripe → staging webhook, signed).

## Next Phase Readiness

- **29-14:** run `scripts/staging-secrets-preflight.sh` before the deploy. It is rc 0 now. Then prove the CSI mounts sync the 31 objects.
- **29-21:** the CI deploy job runs the preflight. It needs python3 + PyYAML and `JTOYE_AZURE_SUBSCRIPTION_ID`. Its identity reads the vault only through `secret list`/`show`, so it needs a role able to list (the CI principal holds no Key Vault role today).
- **29-24:** add the four alertmanager SMTP objects to a derived source and to the class table as external. Until then the writer refuses them as unclassified, by design.

## Self-Check: PASSED

- FOUND: scripts/staging-secrets.sh, scripts/staging-secrets-preflight.sh, scripts/lib/staging-secrets-common.sh, evidence/29-13-secrets.txt, evidence/29-13-t1-red-write-once.json, evidence/29-13-t1-red-one-source.json
- FOUND (ancestors of HEAD): 1cf2d0f3, 51fa31d2, 7a17bf16, f43ad03e, 88c7661f
- `git rev-list --count 3163bcda..HEAD` = 5 at SUMMARY write
- Final gates, all rc 0:
  - both selftests (11/11, 10/10)
  - check-keyvault-chain (the gate, and `--selftest` 11/11)
  - check-az-subscription-pin (findings=0)
  - check-doc-citations (46 verified)
  - check-gate-enforcement
  - live preflight (0 gaps, test-mode yes ×2)
  - leak scan of the evidence (0 found)
