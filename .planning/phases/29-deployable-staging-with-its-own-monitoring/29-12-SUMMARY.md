---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 12
subsystem: infra
tags: [kubernetes, kustomize, azure-key-vault, secrets-store-csi, workload-identity, render-gates, tdd]
status: complete

requires:
  - phase: 29-09
    provides: "k8s/staging/azure-identity.yaml: tenant_id, keyvault_name and client_id_<sa> for 10 federated ServiceAccounts, read back from Azure"
  - phase: 29-10
    provides: "the keycloak, redis and rabbitmq components; RabbitmqCluster externalSecret rabbitmq-default-user and its three keys"
  - phase: 29-08
    provides: "postgres-credentials migration-username/migration-password consumed by core-java"
provides:
  - "Base renames: frontend reads frontend-oidc-credentials/client-secret as ServiceAccount frontend; pg-backup reads pg-backup-credentials"
  - "k8s/components/azure-keyvault: six SecretProviderClasses, the secret-sync ServiceAccount/Deployment/NetworkPolicy, WI label + csi mount patches for five workloads"
  - "k8s/staging wiring by replacements from azure-identity.yaml into every SPC and six ServiceAccount client-id annotations"
  - "INV-10 widened: Workload Identity opt-in allowed for the Blob workloads plus every pod template mounting a secrets-store.csi.k8s.io volume, derived from the render"
  - "k8s/scripts/keyvault-chain.py (check, objects, --verbose) and k8s/scripts/check-keyvault-chain.sh (--selftest), wired in k8s-validate"
  - "The derived Key Vault object list (27 names) that plan 29-13 consumes"
  - "Issue #100 reopened, with a recorded reason"
affects: [29-13, 29-14, 29-23, 29-24, 32]

actuals:
  tokens: 35400
  tasks: 3
  commits: 6
plan_head_before: 56aeee3416ed80d2c0170503bae2cb991c5786aa
plan_head_after: 0c0b4ec881b8efd833a9cb8b183003c41a4d52ea

tech-stack:
  added: ["registry.k8s.io/pause:3.10.2 (digest-pinned, secret-sync)", "Secrets Store CSI SecretProviderClass (secrets-store.csi.x-k8s.io/v1, provider azure)"]
  patterns:
    - "A Secret has exactly one producing SPC. A second consumer reads a Secret of its own name, mapped to the same Key Vault object"
    - "Key Vault object = <secret>--<key> with _ and . mapped to -. The object list is derived from the render (keyvault-chain.py objects) and never hand-written"
    - "Component identifiers are the placeholder REPLACED-BY-OVERLAY until the overlay's replacements land. An unreplaced value fails the chain gate's GUID check, so the gate fails closed"
    - "A consumer that no pod represents (the RabbitMQ operator's externalSecret) is served by an always-on pause pod that mounts the SPC"

key-files:
  created:
    - k8s/components/azure-keyvault/kustomization.yaml
    - k8s/components/azure-keyvault/secretproviderclasses.yaml
    - k8s/components/azure-keyvault/secret-sync.yaml
    - k8s/components/azure-keyvault/workload-patches.yaml
    - k8s/scripts/keyvault-chain.py
    - k8s/scripts/check-keyvault-chain.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-12-t3-red-*.json (6 records)
  modified:
    - k8s/base/frontend-deployment.yaml
    - k8s/base/pg-backup-cronjob.yaml
    - k8s/base/serviceaccounts.yaml
    - scripts/k8s-local-secrets.sh
    - k8s/staging/kustomization.yaml
    - k8s/scripts/check-render-invariants.sh
    - infra/dependency-horizons.yaml
    - .github/workflows/ci-cd.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - k8s/base/networkpolicies/README.md
    - docs/runbooks/backups.md
    - k8s/DEPLOYMENT.md, k8s/base/secrets-template.yaml.example, k8s/local/kustomization.yaml, k8s/local/configmap-patch.yaml, k8s/scripts/check-env-contract.sh, scripts/restore-drill.sh (pointers and citations that moved)
  deleted:
    - k8s/staging/workload-identity-patch.yaml (moved into the component, with the same label at the same paths)

key-decisions:
  - "A Secret two workloads read gets a second name for the second consumer (pg-backup-credentials, frontend-oidc-credentials, keycloak-server-credentials, redis-server-credentials, rabbitmq-default-user). Both names map to one Key Vault object, so the values cannot disagree and no SPC needs another workload's objects"
  - "The Keycloak bootstrap admin and core-java's admin-API pair are the same Key Vault objects (keycloak-credentials--admin-*). The broker's default user is core-java's rabbitmq-credentials username/password plus one new object, rabbitmq-default-user--default-user-conf"
  - "Optional refs (smtp, notification, stripe) are produced like every other key (T-29-45). Plan 29-13's preflight must require all 27 objects"
  - "The chain gate counts a pod as 'mounting' an SPC only when a container mounts the csi volume, because the kubelet skips a pod volume that no container references. It also refuses two secret-reading ServiceAccounts that share one client-id"
  - "The chain gate runs on the runner's python3 + PyYAML (the interpreter the horizons gate already uses). Locally it runs under the jtoye-ops conda env"

patterns-established:
  - "Derived-not-listed: INV-10's allowed Workload Identity set and the Key Vault object list both come from the render"
  - "A second-consumer Secret rename is a base change. Local (k8s-local-secrets.sh) supplies the new name from the same .env value, and production repeats the pattern in Phase 32"

requirements-completed: [DPLY-02, DPLY-01]

coverage:
  - id: D1
    description: "No Secret name has two consumers; frontend has its own ServiceAccount; local still creates every Secret the local render reads"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "scratch path walker over kubectl kustomize k8s/staging, before and after (per-Secret consumer list: one workload each)"
        status: pass
      - kind: other
        ref: "bash scripts/k8s-local-secrets.sh --help (12 names; comm against the k8s/local render's secretKeyRef names is empty; arm without the frontend-oidc apply names it)"
        status: pass
      - kind: other
        ref: "check-render-invariants.sh && render-golden.sh && check-env-contract.sh && check-no-plaintext-secrets.sh, rc 0"
        status: pass
    human_judgment: false
  - id: D2
    description: "The azure-keyvault component, wired in staging by replacements: 6 SPCs, 0 placeholders, 0 kind: Secret, azure-identity not emitted"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "Task 2 verify 2: spc=6 placeholders=0 (control overlay without replacements: 18 placeholders, exit 1)"
        status: pass
      - kind: other
        ref: "check-render-invariants.sh (INV-10 6 templates / 6 SPC mounts), --selftest-inv7 5/5, --selftest-inv15 8/8, render-golden, check-no-plaintext-secrets, check-dependency-horizons: all rc 0"
        status: pass
    human_judgment: false
  - id: D3
    description: "INV-10 derives the Workload Identity allow-list from SPC mounts"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "scratch arms: edge-go labelled rc 1, frontend SA client-id absent rc 1, secret-sync unlabelled rc 1, clean rc 0"
        status: pass
    human_judgment: false
  - id: D4
    description: "Key Vault chain gate + derived object list, CI-wired"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "bash k8s/scripts/check-keyvault-chain.sh --selftest (11/11; RED 5/10 then 10/11 with RED_EVIDENCE_OK x6)"
        status: pass
      - kind: other
        ref: "Task 3 verify 2: objects=27 unique=27; set diff against the Interfaces mapping empty"
        status: pass
    human_judgment: false
  - id: D5
    description: "Issue #100 reopened with a recorded reason"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "gh issue view 100 --json state -> OPEN (CLOSED before); stored comment read back byte-equal to the file sent, contains #955, no 'Closes'"
        status: pass
    human_judgment: false
  - id: D6
    description: "The chain works on the live cluster: pods mount Key Vault through CSI, the Secrets sync, and rabbitmq-default-user survives its consumer"
    requirement: DPLY-01
    verification: []
    human_judgment: true
    rationale: "Render-level only in this plan: nothing was applied to jtoye-stg-aks and nothing was written to Key Vault (owner rule). Plan 29-13 writes the objects and role assignments, and plan 29-14 proves the mounts live (A7 and the CronJob path included)."

duration: 20min
completed: 2026-10-19
---

# Phase 29 Plan 12: Key Vault Through Secrets Store CSI, Checked on the Render Summary

**Every secret-reading staging workload now mounts its own SecretProviderClass and authenticates as its own Workload Identity ServiceAccount. Six SPCs produce each synced Secret exactly once, and a pause-image secret-sync pod keeps the RabbitMQ operator's Secret alive. The identifiers are wired from the committed azure-identity.yaml by replacements. A new CI gate checks the chain secretKeyRef → SPC → objectName → Key Vault on the render and derives the 27-name Key Vault object list that plan 29-13 consumes. Issue #100 is open again, with the reason recorded.**

## Performance

- **Duration:** about 20 min (2026-10-19T06:44:37Z to 07:04Z)
- **Tasks:** 3 of 3
- **Commits:** 6 task commits (3d4817c2, c3550e46, ff75ab6c, 5acb610e, 04699a78, 0c0b4ec8)
- **Files:** 31 changed (8 created, 1 deleted)

## Accomplishments

- **Task 1 (3d4817c2).** No Secret has two consumers any more:
  - `frontend` reads `frontend-oidc-credentials/client-secret` as its own ServiceAccount `frontend`.
  - `pg-backup` reads `pg-backup-credentials` (host, port, database, backup-username, backup-password).
  - `k8s-local-secrets.sh` creates both from the same `.env` values. Its new `--help` lists the names, derived from its own calls.
  - Issue #100 is reopened.
- **Task 2 (c3550e46).** Added `k8s/components/azure-keyvault` and wired it in staging:
  - Six SPCs, all `usePodIdentity "false"`.
  - `secret-sync`: pause 3.10.2, digest-pinned, 1m/8Mi. Its NetworkPolicy admits nothing.
  - WI label, csi volume and read-only mount patches for core-java, frontend, pg-backup, keycloak and redis.
  - Staging uses `azure-identity.yaml` as a local-config replacements source: 8 entries, 6 SPC clientIDs, 6 SA annotations, tenantId and keyvaultName.
  - `workload-identity-patch.yaml` is deleted.
  - INV-10 is widened, INV-7 has a `secret-sync-allow` row, and the horizons file has a pause row.
- **Task 3 (TDD: ff75ab6c RED, 5acb610e GREEN, then 04699a78 RED, 0c0b4ec8 fix).** Added `keyvault-chain.py` and `check-keyvault-chain.sh`:
  - The selftest has 11 arms.
  - Both are wired in the k8s-validate job.
  - A real-tree break arm after GREEN found a gap: two workloads could share one identity. It is now closed.

## Evidence (both directions)

### Task 1

**Per-workload secretKeyRef listing** (scratch path walker over `kubectl kustomize k8s/staging`):

- Before:
  - `Deployment/frontend keycloak-credentials frontend-client-secret`
  - `CronJob/pg-backup postgres-credentials {backup-password, backup-username, database, host, port}`
- After:
  - `Deployment/frontend frontend-oidc-credentials client-secret`
  - `CronJob/pg-backup pg-backup-credentials {…the same five keys}`
- Diff: exactly those 6 lines.
- After the change, each of the 14 Secret names has one consumer: `postgres-credentials` → core-java only, `pg-backup-credentials` → pg-backup only, and so on.

**Goldens:**
- Production gains only the `frontend` SA, the env rename and the 5 pg-backup renames (32 lines).
- Staging gains the same.

**Gates.** `check-render-invariants`, `render-golden`, `check-env-contract` and `check-no-plaintext-secrets` all rc 0. FAIL/PARSE ERROR lines: 0.

**`k8s-local-secrets.sh --help`:**
- rc 0, 12 names.
- `comm` against the 12 secretKeyRef names in the k8s/local render shows nothing missing.
- Arms:
  - A copy with no apply/skip calls gives rc 2 `PARSE ERROR`.
  - A copy without the `frontend-oidc-credentials` apply names `frontend-oidc-credentials` as missing.

**Citations moved with the lines:**
- Horizon site `frontend-deployment.yaml:44`. The arm at :40 gives `pin-not-at-site=1`; the fixed file gives 0.
- `check-env-contract` NEXTAUTH_URL 152-156.
- `restore-drill.sh` pg-backup DB_USER :81.
- `check-doc-citations` rc 0, 46 verified.

**#100:**
- State before: `CLOSED`, closedAt 2026-10-18T08:17:37Z, stateReason COMPLETED. `closedByPullRequestsReferences` = [955], and #955 merged at 08:17:36Z (`29facb94`).
- `git grep -E 'SecretProviderClass|secrets-store\.csi'` over k8s/scripts/infra gives rc 1 at that merge commit and rc 1 at `origin/main` `cd30b21a`. The control `kind: ServiceAccount` gives rc 0.
- `gh issue reopen 100` rc 0. The comment was posted with `-F` from a quoted-heredoc file.
- `gh issue view 100 --json state` gives `OPEN`.
- The stored comment is byte-equal to the file sent: `has955=2`, `hasCloses=0`. Control: `Closes #100` counts 1.

### Task 2

**Verify 1:** `check-render-invariants`, `render-golden`, `check-no-plaintext-secrets` and `check-dependency-horizons` (jtoye-ops) give rc 0, with 0 FAIL/PARSE lines. INV-9 reports 32 Secret refs, none of them storage.

**Verify 2, as written:** `spc=6 placeholders=0`, exit 0.
- Fail direction: the same script over the control overlay (the component without replacements) gives `spc=6 placeholders=18`, exit 1.

**Placeholder control:**
- The scratch overlay in `mktemp -d` renders with rc 0 (170548 bytes).
- `command grep -c 'REPLACED-BY-OVERLAY'` = **18** (6 SPCs × 3 fields), at render lines 4037 4038 4104 4188 4189 4198 4223 4224 4236 4260 4261 4279 4307 4308 4320 4344 4345 4351.

**Staging render** (rc 0, 177928 bytes):
- `^kind: SecretProviderClass$` = **6**
- `^kind: Secret$` = **0**
- `^  name: azure-identity$` = **0**
- Fail arm for the last grep: a copy with the local-config annotation removed reads **1**.

**Rendered identifiers:** every SPC clientID equals its ServiceAccount's annotation. core-java `3a14636b…`, frontend `348fa334…`, pg-backup `ca38c385…`, keycloak `ccf8147d…`, redis `ec5b9f1a…`, secret-sync `231ad919…`. tenantId `b56df236…`, keyvaultName `jtoye-stg-kv`. These six SA names are exactly the 29-09 federated subjects, `system:serviceaccount:jtoye-staging:{core-java,frontend,pg-backup,keycloak,redis,secret-sync}`.

**INV-10 before widening:** the new render failed 4 templates (frontend, keycloak, redis, secret-sync) and INV-7 failed `secret-sync-allow`.

**INV-10 after widening:**
- Staging: `OK (6 pod templates labelled + own SA, automount false; 6 mount a SecretProviderClass with a client-id SA; no other opt-in)`.
- Production: `OK (2 …; 0 mount …)`.

**INV-10 arms** (scratch copies of k8s/):
- edge-go labelled gives rc 1, `Deployment/edge-go carries azure.workload.identity/use … only the pod templates of … may opt in`.
- The frontend SA client-id replacement removed gives rc 1, `… ServiceAccount 'frontend' has azure.workload.identity/client-id='(ABSENT)', expected a GUID`.
- secret-sync with its label removed gives rc 1, `label … is (ABSENT)`.
- The clean copy gives rc 0.

**Other gates:**
- `--selftest-inv7`: 5/5, CLEAN rc 0.
- `--selftest-inv15`: 8/8, CLEAN rc 0. (INV-14 has no separate selftest. It holds in the main run, and its arms live in `--selftest-inv15`.)
- connection-math and its selftest, the platform render check (4 kustomizations, 0 bad), and the staging-bootstrap, realm-import and seed selftests: all rc 0.

**Horizons:**
- Without the pause row: rc 1, `FAIL: H-1 registry.k8s.io/pause:3.10.2@sha256:f548… has NO horizon row`.
- With it: rc 0, `missing-row=0`, `pin-not-at-site=0`.
- The row is UNKNOWN by construction: endoflife.date returns 404 for `pause` on both API paths, and the dated manual_review expires 2027-01-19.
- Pause digest: the `docker-content-digest` header equals the sha256 of the downloaded index body, `f548e0e8…baf4`. The index lists linux/amd64. Tags list read 2026-10-19 (newest 3.10.2).

**MS Learn** (fetched 2026-10-19):
- `csi-secrets-store-identity-access` (updated 2026-09-30): the WI SPC shape, the SA annotation and the pod label.
- `csi-secrets-store-configuration-options` (updated 2026-01-30): "When you delete the pods that consume the secrets, your Kubernetes secret is also deleted", plus the objectName = mounted file name rule.

### Task 3 (TDD)

**RED** (`ff75ab6c`): the library validated rule (c) only. `--selftest` gave rc 1 and **5/10**:
- undeclared-key, two-producers, clientid-mismatch, sync-lifetime and zero-spc were `caught=no … got rc=0`.
- object-not-mounted (c), pyyaml-missing and kubectl-missing were caught.
- clean-library and clean-gate were rc 0.

Classifier (TAP): `RED_EVIDENCE_OK target_test_failed` ×5, records `evidence/29-12-t3-red-{undeclared-key,two-producers,clientid-mismatch,sync-lifetime,zero-spc}.json`. A control record that targets the passing arm returns `INVALID_RED no_target_test_failure`.

**Semantic assessment:** every target arm's plant applied (`plant()` exits 3 on an unchanged render, and none did). The checker exited 0 only because the RED library checks only (c), and the harness was sound in the same run.

**GREEN** (`5acb610e`): `--selftest` 10/10, then the gate:
`OK: 6 SecretProviderClasses, 35 consumed (Secret, key) reference(s), 27 Key Vault objects`, rc 0.

**Real-tree break arm after GREEN** (clean → arm → clean, on the committed tree):
- Arm: re-point staging's `client_id_frontend` replacement at `client_id_keycloak`, so the frontend SPC and SA agree with each other.
- Result: the chain gate gave **rc 0** and the render invariants gave **rc 0**, a real gap (T-29-43).

**Fix:**
- **RED** (`04699a78`): new arm `shared-identity`, 10/11, `RED_EVIDENCE_OK` (`evidence/29-12-t3-red-shared-identity.json`).
- **GREEN** (`0c0b4ec8`): 11/11. The same real-tree arm now gives rc 1, `ServiceAccounts frontend, keycloak share azure.workload.identity/client-id 'ccf8147d-…'`. The restore was verified by sha256 (`fda23e26…` before and after), and the gate gives rc 0.

**Verify 2, as written:** `objects=27 unique=27`, exit 0.
- Arm: one well-formed extra object gives `objects=28 unique=28`, exit 1.
- Arm: a malformed `objects` string makes `objects` exit 1 (`parameters.objects is not parseable`).
- Arm: a two-producer render makes `objects` exit 1 with 0 stdout lines (the list is refused, not printed).

**Derived list vs the Interfaces mapping:** the expected list was typed from the plan by brace expansion. `diff` is empty. Control: dropping `stripe-credentials--api-key` from the expected list makes the diff name it, rc 1.

**CI wiring:** `command grep -c 'check-keyvault-chain.sh' .github/workflows/ci-cd.yaml` = **2** (the `--selftest` step and the gate step). `ci-cd.yaml` parses. `check-gate-enforcement` rc 0.

**The derived Key Vault object list** (`keyvault-chain.py objects`, staging):

```
client-ip-credentials--hmac-key
dsar-credentials--encryption-key
keycloak-credentials--admin-password
keycloak-credentials--admin-username
keycloak-credentials--frontend-client-secret
keycloak-server-credentials--db-password
nextauth-secret--secret
notification-credentials--unsubscribe-signing-secret
postgres-credentials--backup-password
postgres-credentials--backup-username
postgres-credentials--database
postgres-credentials--host
postgres-credentials--migration-password
postgres-credentials--migration-username
postgres-credentials--password
postgres-credentials--port
postgres-credentials--username
rabbitmq-credentials--password
rabbitmq-credentials--stomp-login
rabbitmq-credentials--stomp-passcode
rabbitmq-credentials--username
rabbitmq-default-user--default-user-conf
redis-credentials--password
smtp-credentials--password
smtp-credentials--username
stripe-credentials--api-key
stripe-credentials--webhook-secret
```

## TDD Gate Compliance

| Gate | Commit | Notes |
|------|--------|-------|
| RED | `ff75ab6c` | test(29-12), with the library checking (c) only. RED_EVIDENCE_OK ×5 |
| GREEN | `5acb610e` | feat(29-12), 10/10 |
| RED (gap) | `04699a78` | test(29-12), shared-identity, RED_EVIDENCE_OK |
| GREEN (gap) | `0c0b4ec8` | fix(29-12), 11/11 |

Tasks 1 and 2 are not `tdd="true"`. Their `feat(29-12)` commits precede the RED commit by design, because they are the manifests the gate checks.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical] The chain gate let two workloads share one identity**
- **Found during:** Task 3, in the real-tree break arm after GREEN.
- **Issue:** rule (d) compares each SPC with its own pod's ServiceAccount. Re-pointing frontend's replacement at keycloak's client id kept that pair consistent. Both the chain gate and INV-10 stayed at rc 0, which breaks T-29-43 (one identity per workload).
- **Fix:** client-ids are now required to be distinct across secret-reading ServiceAccounts. A selftest arm (`shared-identity`) covers it.
- **Files modified:** k8s/scripts/keyvault-chain.py, k8s/scripts/check-keyvault-chain.sh, .github/workflows/ci-cd.yaml (the comment)
- **Committed in:** 04699a78 (RED), 0c0b4ec8 (fix)

**2. [Rule 3 - Blocking] INV-7 refused the new NetworkPolicy**
- **Found during:** Task 2.
- **Issue:** INV-7 failed with `undeclared policy 'secret-sync-allow'`.
- **Fix:** added the row `secret-sync-allow@k8s/staging` (`sel:app=secret-sync types:Egress,Ingress`, no rules) and the same row in `k8s/base/networkpolicies/README.md`. Staging now has 11 policies.
- **Files modified:** k8s/scripts/check-render-invariants.sh, k8s/base/networkpolicies/README.md
- **Committed in:** c3550e46

**3. [Rule 3 - Blocking] Citations and pointers moved with the edits**
- **Found during:** Tasks 1 and 2.
- **Issue:**
  - The frontend image moved from line 40 to line 44 (horizon site; H-5 NOTE).
  - `check-env-contract.sh` cited `frontend-deployment.yaml:148-152`.
  - `restore-drill.sh` cited `pg-backup-cronjob.yaml:75`.
  - DEPLOYMENT.md, secrets-template.yaml.example and serviceaccounts.yaml named the deleted staging patch.
  - backups.md, the local kustomization and configmap-patch comments, and three gate messages named the old Secret.
- **Fix:** every pointer and citation now names the current line or file.
- **Committed in:** 3d4817c2, c3550e46

**4. [Rule 2 - Missing critical] The plan's "mount" was a volume, but the kubelet needs a volumeMount**
- **Found during:** Task 2 design.
- **Issue:** the kubelet's desired-state populator skips a pod volume that no container references, so a csi volume alone would never sync anything.
- **Fix:** every workload patch adds a read-only container mount at /mnt/secrets-store, and the chain gate counts an SPC as mounted only through a container volumeMount (an unmounted csi volume FAILS).
- **Committed in:** c3550e46, 5acb610e

**5. [Rule 3 - Blocking] `k8s-local-secrets.sh` had no names-only mode**
- **Found during:** Task 1.
- **Issue:** the acceptance criterion needed one.
- **Fix:** added `--help`. It runs before every guard and mutation, derives the names from the script's own apply/skip calls, and exits with rc 2 when it finds nothing.
- **Committed in:** 3d4817c2

**Total deviations:** 5 auto-fixed (2 missing-critical, 3 blocking). **Impact:** each one keeps a gate falsifiable or the manifests functional, with no scope beyond the plan's files and the pointers into them.

## Issues Encountered

- The host's `block-base-python` hook refused one Bash call that ran python through `PATH=` rather than `conda activate`. It was re-run under `conda activate jtoye-ops`. Nothing was lost.
- Nothing was applied to `jtoye-stg-aks`, and nothing was written to or read from Key Vault. The only Azure-adjacent action was reading the committed identity file and the 29-09 evidence.

## Known Stubs

None that block the plan's goal. `REPLACED-BY-OVERLAY` in the component is a deliberate fail-closed placeholder: k8s/staging replaces all 18, and the chain gate fails any that remain.

## Threat Flags

None beyond the register:
- T-29-43 is mitigated by one SA and identity per workload plus the shared-identity rule.
- T-29-44 is mitigated by rule (b) (the two-producers arm).
- T-29-45 is mitigated: every consumed key, optional or not, needs a producer.
- T-29-46 is mitigated: `kind: Secret` = 0 in every render, and the SPCs carry identifiers only.

`secret-sync` adds no network surface: its NetworkPolicy has no ingress or egress rule.

## Next Phase Readiness

**Plan 29-13:**
- Write the 27 objects. The derived list above comes from `python3 k8s/scripts/keyvault-chain.py objects <staging render>`.
- `rabbitmq-default-user--default-user-conf` must be composed from the two rabbitmq-credentials values (`default_user = …` / `default_pass = …`). `keycloak-server-credentials--db-password` is new.
- Assign each identity only its own SPC's objects:
  - core-java: 21
  - frontend: 2
  - pg-backup: 5
  - keycloak: 3
  - redis: 1
  - secret-sync: 3
- The preflight must treat the optional smtp, notification and stripe objects as required.

**Plan 29-14 (live):**
- Prove the mounts sync.
- Prove that `rabbitmq-default-user` survives while only secret-sync mounts it (A7).
- Prove the pg-backup Job's mount-then-env ordering, which is `[ASSUMED]` in the SPC comment.

**#100:** stays OPEN until the 29-13 preflight fail arm and the 29-14 live mounts exist.

## Self-Check: PASSED

- FOUND: k8s/components/azure-keyvault/{kustomization,secretproviderclasses,secret-sync,workload-patches}.yaml, k8s/scripts/keyvault-chain.py, k8s/scripts/check-keyvault-chain.sh, evidence/29-12-t3-red-{undeclared-key,two-producers,clientid-mismatch,sync-lifetime,zero-spc,shared-identity}.json
- FOUND (ancestors of HEAD): 3d4817c2, c3550e46, ff75ab6c, 5acb610e, 04699a78, 0c0b4ec8
- `git rev-list --count 56aeee34..HEAD` = 6 at SUMMARY write
- Final gate run, all rc 0:
  - check-render-invariants, --selftest-inv7, --selftest-inv15
  - render-golden, check-env-contract, check-no-plaintext-secrets
  - check-keyvault-chain --selftest (11/11) and the gate
  - check-dependency-horizons, check-doc-citations, check-gate-enforcement
  - check-connection-math
  - staging-bootstrap, realm-import and seed selftests
- Issue #100 state: OPEN
