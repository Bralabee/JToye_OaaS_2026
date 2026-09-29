---
phase: 36-azure-blob-storage-throughout
plan: 09
subsystem: infra
tags: [k8s, kustomize, azure-blob, workload-identity, azurite, networkpolicy, render-invariants, env-contract, goldens, pg-backup, D-02, D-10]
status: complete

requires:
  - phase: 36-azure-blob-storage-throughout
    provides: "36-01 the STORAGE_* env names and the connection-string | workload-identity switch; 36-05 D-11 account names, container names, the WI identity spec; 36-06 the fail-fast shape rules every k8s value must pass; 36-08 the :15-blob image and the backup script's env contract (STORAGE_AUTH_MODE, STORAGE_ENDPOINT, STORAGE_CONNECTION_STRING, BACKUP_CONTAINER, BACKUP_PREFIX)"
provides:
  - "app-config storage.blob.* and backup.blob.* in base (production accounts) and every overlay (staging jtoyestgmedia/jtoyestgbackup, production jtoyeprodmedia/jtoyeprodbackup, local Azurite)"
  - "ServiceAccounts core-java and pg-backup (k8s/base/serviceaccounts.yaml), automountServiceAccountToken false"
  - "k8s/staging|production/workload-identity-patch.yaml: azure.workload.identity/use \"true\" on the core-java and pg-backup pod templates"
  - "k8s/local/storage-env-patch.yaml: STORAGE_CONNECTION_STRING (emulator string, no key) on core-java and pg-backup, local only"
  - "No port 9000 in any NetworkPolicy; Blob + Entra token endpoint ride the existing 443 rule"
  - "Render invariants INV-8, INV-9, INV-10, LOC-7; INV-4/INV-7/LOC-1/LOC-3 updated; RENDER_PATHS_AWK path walker"
  - "check-env-contract green: STORAGE_CONNECTION_STRING reasoned omission; UseDevelopmentStorage=true is a local-only word"
  - "D-10: ghcr.io/bralabee/jtoye-pg-backup:15-blob in the CronJob, Dockerfile build line, horizons pin and goldens, one commit"
affects: [36-10, 36-17, 36-18, phase-29-provisioning]

actuals:
  tokens: 27744        # chars/4 over git diff d17cb269..5f415ae0 (the four code commits), before this SUMMARY
  tasks: 3
  commits: 4           # MEASURED: git rev-list --count d17cb269..HEAD before the SUMMARY commit
plan_head_before: d17cb269c10ceceb731565de2e961bb610e6c1aa

tech-stack:
  added: []
  patterns:
    - "Render assertions read values by full key PATH (kind, name, a::b::[i]::c), never by a forward scan: kustomize emits a container's env before its name"
    - "Invariants scoped to real-cluster overlays (WI_TARGETS) must prove every listed target was rendered, or parse_fail"
    - "A local-only config key is legitimate only when its env wiring lives in the same overlay and an invariant (LOC-7) asserts the wiring"
    - "Structural gate plus functional probe: rendered values fed to the real consumers (core-java validateShape, blobctl in the built image, --network none)"

key-files:
  created:
    - k8s/base/serviceaccounts.yaml
    - k8s/staging/workload-identity-patch.yaml
    - k8s/production/workload-identity-patch.yaml
    - k8s/local/storage-env-patch.yaml
  modified:
    - k8s/base/configmap.yaml
    - k8s/base/core-java-deployment.yaml
    - k8s/base/pg-backup-cronjob.yaml
    - k8s/base/kustomization.yaml
    - k8s/base/networkpolicies/20-core-java.yaml
    - k8s/base/networkpolicies/40-datastores.yaml
    - k8s/base/networkpolicies/README.md
    - k8s/staging/configmap-patch.yaml
    - k8s/staging/kustomization.yaml
    - k8s/production/configmap-patch.yaml
    - k8s/production/kustomization.yaml
    - k8s/local/configmap-patch.yaml
    - k8s/local/kustomization.yaml
    - k8s/scripts/check-render-invariants.sh
    - k8s/scripts/check-env-contract.sh
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - infra/dependency-horizons.yaml
    - infra/backups/Dockerfile
    - k8s/LOCAL.md
    - scripts/restore-drill.sh

key-decisions:
  - "LOC-1's shim list names the two connection strings, not storage.blob.endpoint/backup.blob.endpoint as the plan said: local's endpoints are deliberately empty (unused in connection-string mode), and the host shim lives in DevelopmentStorageProxyUri"
  - "INV-8..INV-10 are strictly stronger than the plan text: INV-8 also pins the D-07 container names; INV-9 also catches envFrom secretRef, volume/projected secrets and AZURE_CLIENT_SECRET; INV-10 also refuses the label on any OTHER pod template and requires the quoted string \"true\"; LOC-7 checks pg-backup as well as core-java"
  - "The plan's stale-tag criterion (git grep 'jtoye-pg-backup:15\"') only matched the quoted horizons pin and was blind to the CronJob and Dockerfile spellings; replaced by jtoye-pg-backup:15([^-]|$), recorded both directions"
  - "The golang and jtoye-core-java horizons site lines moved with the comment lines this plan added (H-5 notes back to the 2 that predate it)"
  - "BLOB-06 not marked complete although requirements.ready-ids reports it ready: its traceability row still needs the nightly drill to run on a runner (36-18), which declares no BLOB-06, so the tool cannot see it. BLOB-07 blocked on 36-10"

patterns-established:
  - "WI opt-in is a pod-TEMPLATE label patched per real overlay; base and local never carry it"
  - "A tag bump whose contents change (D-10) moves the manifest, the documented build line, the horizons pin and the goldens in one commit"

requirements-completed: []   # plan declares [BLOB-07, BLOB-06]; neither fully closed (BLOB-07 needs 36-10; BLOB-06 needs the 36-18 nightly run)

coverage:
  - id: D1
    description: "Staging/production render Workload Identity for media and backup on separate D-11 accounts with raw endpoints and public-url = media endpoint + /jtoye-images"
    requirement: BLOB-07
    verification:
      - kind: other
        ref: "bash k8s/scripts/check-render-invariants.sh -> INV-8 OK on k8s/staging and k8s/production (pre-plan tree + new gate: INV-8 FAIL x8 per target; arms 4, 10, 11, 12 red)"
        status: pass
      - kind: other
        ref: "Probe.java: rendered staging/production values + webhook env -> ACCEPTED by StorageProperties.Blob.validateShape; create-containers true -> REFUSED"
        status: pass
    human_judgment: false
  - id: D2
    description: "No stored storage credential renders in staging/production (no storage Secret ref, no connection-string/AWS_/AZURE_STORAGE_ env, no connection-string key)"
    requirement: BLOB-07
    verification:
      - kind: other
        ref: "INV-9 OK x2 (arm 3 storage-creds secretKeyRef -> red; pre-plan tree -> 4 Secret refs + 3 AWS_ env named); gitleaks dir on k8s/* and git range d17cb269..HEAD no leaks (planted AccountKey -> 1 leak); rg -uu key shapes rc=1 (planted rc=0)"
        status: pass
    human_judgment: false
  - id: D3
    description: "core-java and pg-backup run as dedicated ServiceAccounts (automount false) and carry azure.workload.identity/use \"true\" on their pod templates in staging/production only"
    requirement: BLOB-07
    verification:
      - kind: other
        ref: "INV-10 OK x2 (arms 2, 13, 14, 15 red); local render wi=0 (planted label into local -> 2)"
        status: pass
    human_judgment: false
  - id: D4
    description: "core-java fails fast while the Phase 29 client-id annotation is absent"
    requirement: BLOB-07
    verification:
      - kind: other
        ref: "Probe.java: staging values without AZURE_* -> REFUSED StorageConfigurationException naming AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_FEDERATED_TOKEN_FILE; blobctl in :15-blob -> 'config: workload-identity mode needs AZURE_CLIENT_ID...'"
        status: pass
    human_judgment: true
    rationale: "The validator behaviour is proven; that the real AKS webhook injects the variables once Phase 29 annotates the ServiceAccounts (and does NOT before) needs a WI-enabled cluster (WINDOWS.md entry 3)"
  - id: D5
    description: "k8s/local runs the emulator path against the host Azurite with STORAGE_CONNECTION_STRING from local-only ConfigMap keys"
    requirement: BLOB-07
    verification:
      - kind: other
        ref: "LOC-3 + LOC-7 OK (arms 6 and 9 red); Probe.java local values -> ACCEPTED (port in proxy URI -> REFUSED); blobctl with local env reaches host.minikube.internal:10000 (without the patch -> 'STORAGE_CONNECTION_STRING is required')"
        status: pass
    human_judgment: false
  - id: D6
    description: "No NetworkPolicy permits port 9000"
    requirement: BLOB-07
    verification:
      - kind: other
        ref: "INV-7 OK on 4 targets (arm 1 re-adding 9000 -> INV-7 red on base/local/production/staging); p9000=0 across renders (pre-plan 6)"
        status: pass
    human_judgment: false
  - id: D7
    description: "pg-backup image tag :15-blob moved in the CronJob, Dockerfile build line, horizons pin and goldens in one commit (D-10)"
    requirement: BLOB-06
    verification:
      - kind: other
        ref: "git show --name-only 5f1159de lists all five; check-postgres-major-parity rc=0 (arm :16-blob rc=1); horizons rc=0 (arm pin back to :15 -> H-1 missing-row rc=1)"
        status: pass
    human_judgment: false
  - id: D8
    description: "check-env-contract green for the right reason"
    requirement: BLOB-07
    verification:
      - kind: other
        ref: "bash k8s/scripts/check-env-contract.sh rc=0 (pre-plan rc=1: 6 S3_* direction-(a) + STORAGE_PUBLIC_URL direction-(b); arm 7 removing the allowlist entry -> rc=1 naming STORAGE_CONNECTION_STRING)"
        status: pass
    human_judgment: false
  - id: D9
    description: "Goldens show only the storage change"
    requirement: BLOB-07
    verification:
      - kind: other
        ref: "render-golden.sh --diff-since pre-36-09 resolve_exit=0, 356 lines; structural diff: CHANGED ConfigMap app-config, Deployment core-java, CronJob pg-backup, NetworkPolicy core-java-allow, pg-backup-allow; ADDED ServiceAccount core-java, pg-backup (per env); arm 16 drift -> render-golden rc=1"
        status: pass
    human_judgment: false

duration: 19min
completed: 2026-09-29
---

# Phase 36 Plan 09: k8s Blob Storage with Workload Identity Summary

**Every k8s target now renders the Azure Blob model: staging and production use Workload Identity through dedicated `core-java` / `pg-backup` ServiceAccounts on separate media and backup accounts (D-11), with no stored credential and no port 9000. Local runs the Azurite emulator string from a local-only key. Four new render invariants (INV-8/9/10, LOC-7) enforce all of it and were each seen failing. The pg-backup tag moved to `:15-blob` in one commit together with the goldens.**

## Performance

- **Duration:** 19 min
- **Started:** 2026-09-29T06:04:46Z
- **Completed:** 2026-09-29T06:23:09Z
- **Tasks:** 3 (plus one follow-up fix commit for line citations the edits shifted)
- **Files modified:** 25 (4 created)

## Accomplishments

- **Base:** app-config `storage.blob.*` (workload-identity, `https://jtoyeprodmedia.blob.core.windows.net`, `jtoye-images` / `jtoye-quarantine`, public-url = endpoint + `/jtoye-images`, create-containers `"false"`) and `backup.blob.*` (`https://jtoyeprodbackup.blob.core.windows.net`, `jtoye-db-backups`, `backups`). core-java injects six `STORAGE_*` env vars and pg-backup injects `STORAGE_AUTH_MODE` / `STORAGE_ENDPOINT` / `BACKUP_CONTAINER` / `BACKUP_PREFIX`, all from app-config. Every S3/AWS env and both credential `secretKeyRef`s are gone.
- **Identities:** new ServiceAccounts `core-java` and `pg-backup`, both with `automountServiceAccountToken: false`. The Workload Identity pod-template label is patched in only by staging and production.
- **Local:** connection-string mode with `UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://host.minikube.internal`, supplied by `storage-env-patch.yaml` from local-only keys. The browser-facing public-url stays `http://localhost:10000/devstoreaccount1/jtoye-images`.
- **NetworkPolicies:** the 9000 port entry (core-java-allow) and the whole in-cluster object-store rule (pg-backup-allow) are deleted. `egress.1` is still the single-port Postgres rule in both, so the `db.port` replacement still hits it (local renders 5433).
- **Gates:** the render gate gained INV-8/9/10 and LOC-7 on a new path walker. INV-4 bans emulator strings and account keys outside local, INV-7 expects no 9000, and LOC-1/LOC-3 follow the Azurite model. `check-env-contract` is green again.
- **D-10:** `ghcr.io/bralabee/jtoye-pg-backup:15-blob` in the CronJob, the Dockerfile build line and the horizons pin, with regenerated goldens, in commit `5f1159de`.

## Task Commits

1. **Task 1: base manifests** — `733485a6` (feat)
2. **Task 2: overlays** — `25ed67ec` (feat)
3. **Task 3: gates, D-10 tag move, goldens** — `5f1159de` (feat)
4. **Follow-up: citations shifted by the edits** — `5f415ae0` (fix)

**Plan metadata:** see the docs commit that adds this file.

## Verification evidence (both directions)

All break arms ran against the committed tree. Every restore was verified by `git hash-object` == HEAD blob, the opening and closing clean runs are green, and `git diff --quiet HEAD` rc=0 at close.

| # | Criterion / arm | Fail direction (break) | Pass direction (real tree) |
|---|---|---|---|
| T1 | renders build; no 9000; SAs | pre-plan renders: p9000=6, sa=0, saname=0 | staging/production/local rc=0; p9000=0; sa=2; saname=2 |
| T1 | retired names absent from k8s/base (scoped, see Deviation 1) | pre-plan scoped grep: 19 hits; renders 16 hits each | scoped `git grep` rc=1; renders 0 hits each |
| T1 | Postgres egress = db.port | the #271 replacement is shown live by local: authored literal 5432, rendered 5433 | staging/production: core-java-allow and pg-backup-allow egress.1 = 5432 = db.port; local 5433 = db.port |
| T2 | WI label x2, no conn string (staging/prod); local env x2, no WI | pre-plan: wi=0, local cs=0; planted WI patch into a local copy -> local wi=2 | staging wi=2 cs=0; production wi=2 cs=0; local cs=2 wi=0 |
| T2 | four distinct D-11 hosts | (INV-8 arm 10: backup = media -> red) | jtoyestgmedia, jtoyestgbackup, jtoyeprodmedia, jtoyeprodbackup read out of the renders |
| T2 | no minio/:9000 in overlays | pre-plan `git grep` 7 hits; planted comment in a copy -> `rg -uu` rc=0 | `git grep` rc=1 and `rg -uu` (covers the new files) rc=1 |
| 1 | re-add 9000 to 20-core-java.yaml | INV-7 FAIL on base, local, production, staging | INV-7 OK x4 |
| 2 | delete staging WI patch entry | INV-10 FAIL: label (ABSENT) on both pod templates | INV-10 OK |
| 3 | secretKeyRef to `storage-creds` on staging core-java | INV-9 FAIL naming `storage-creds` | INV-9 OK (24 Secret refs, none storage) |
| 4 | staging storage.blob.auth-mode connection-string | INV-8 FAIL, only that key | INV-8 OK |
| 5 | `UseDevelopmentStorage=true` in a staging value | INV-4 FAIL, render line 50 | INV-4 OK |
| 6 | drop local storage-env patch entry | LOC-7 FAIL on core-java and pg-backup | LOC-7 OK |
| 7 | remove STORAGE_CONNECTION_STRING allowlist entry | env-contract rc=1: direction (b) STORAGE_CONNECTION_STRING, local-only token `UseDevelopmentStorage=true` | rc=0 |
| 8 | horizons pin only back to `:15` | rc=1: `H-1 ghcr.io/bralabee/jtoye-pg-backup:15-blob ... has NO horizon row` | rc=0 |
| 9 | local backup auth-mode workload-identity | LOC-3 FAIL | LOC-3 OK |
| 10 | staging backup endpoint = media (D-01) | INV-8 FAIL "SAME account" | — |
| 11 | staging public-url on the production account (D-06) | INV-8 FAIL | — |
| 12 | production endpoint with `:443` | INV-8 FAIL (endpoint shape + public-url) | — |
| 13 | WI label on Deployment metadata, not pod template | INV-10 FAIL twice (template ABSENT; label at wrong path) | — |
| 14 | frontend pod template opts in | INV-10 FAIL (least privilege) | — |
| 15 | SA core-java automount true | INV-10 FAIL on staging and production | — |
| 16 | staging drops its media override (golden drift) | render-golden rc=1 `[staging]: render DRIFTED` | rc=0 |
| 17 | CronJob `:16-blob` | parity rc=1 `TOOLING majors disagree: 15 16` | parity rc=0 (all TOOLING rows -> 15, `-blob` parsed) |
| — | new gate vs pre-plan tree (d17cb269 export) | rc=1: INV-7 x2 per target, INV-8 x8, INV-9 (s3-*-credentials, AWS_*), INV-10 x6 | — |
| — | secret material | planted random AccountKey in a copy: gitleaks 1 leak (rc=1), `rg -uu` rc=0 | gitleaks `dir` on k8s/base, staging, production, local, goldens: no leaks; `git` range d17cb269..HEAD: 3 commits, no leaks; `rg -uu` key shapes rc=1 |

**Final gate run (closing clean):** inv=0 (`PASS: INV-1..INV-7 hold across 4 kustomize target(s); INV-8, INV-9, INV-10 hold on k8s/staging k8s/production; LOC-1..LOC-6, LOC-7 checked on k8s/local.`), golden=0, env=0, secrets=0 (25 resources each, 0 Secrets), hz=0 (conda jtoye-ops; H-5 notes 2, the pre-plan count), parity=0, connection-math=0, validate-networkpolicies=0, check-no-measured-placeholders=0, check-gate-enforcement=0, check-alert-rules=0. check-doc-citations rc=1 with exactly the 5 pre-plan `.planning/codebase` failures (it was 6 after Task 3; see Deviation 4).

**Functional probes (proof standard 5):** the rendered values were fed to the real consumers.
- core-java `StorageProperties.Blob.validateShape` (build-local classes; the source is clean and compiled after its last commit):
  - staging and production with the webhook env: ACCEPTED.
  - staging without it: REFUSED, naming AZURE_CLIENT_ID / AZURE_TENANT_ID / AZURE_FEDERATED_TOKEN_FILE.
  - local: ACCEPTED.
  - break arms: create-containers true is REFUSED, and so is a proxy URI with a port.
- blobctl inside `:15-blob`, run with `--network none`:
  - staging env: passes config and reaches `login.microsoftonline.com` (the 443 rule's target). Without the webhook env it fails at config.
  - local env: dials `host.minikube.internal:10000`. Without the patch: `STORAGE_CONNECTION_STRING is required`.

**Golden diff review:** `--diff-since pre-36-09` resolved (exit 0, 356 lines). Every `<`/`>` line was tallied and falls into one of these classes:
- storage config keys;
- STORAGE_* / BACKUP_* env, including removed S3_* / AWS_* env and credential secretKeyRefs;
- ServiceAccount documents and serviceAccountName;
- the WI label;
- the removed 9000 rules;
- the image tag.

Per resource, in each golden: CHANGED ConfigMap/app-config, Deployment/core-java, CronJob/pg-backup, NetworkPolicy/core-java-allow, NetworkPolicy/pg-backup-allow; ADDED ServiceAccount/core-java, ServiceAccount/pg-backup; 23 -> 25 resources. No selector, no immutable field and no other env changed.

## Decisions Made

See `key-decisions` above. The two that change what the plan literally says are the LOC-1 key list (connection strings, not endpoints) and the stale-tag criterion (replaced by a stronger form).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Task 1 criterion 1 cannot pass inside this plan's file scope**
- **Found during:** Task 1
- **Issue:** `git grep ... -- k8s/base` matches `k8s/base/secrets-template.yaml.example` (7 hits). That file belongs to 36-10 and is not a kustomize resource.
- **Fix:** Ran the criterion scoped with `':!k8s/base/secrets-template.yaml.example'` (rc=1; 19 hits pre-plan), plus a stronger render-level form (0 hits in each render; 16 pre-plan). The unscoped form stays red until 36-10.
- **Committed in:** n/a (verification only)

**2. [Rule 1 - Bug] The plan's LOC-1 key rename would fail on the plan's own local values**
- **Found during:** Task 3
- **Issue:** The plan renames LOC-1's keys to storage.blob.endpoint / backup.blob.endpoint. Task 2 sets both to `""` locally (unused in connection-string mode), so they could never carry `host.minikube.internal`.
- **Fix:** LOC-1 shims `storage.blob.connection-string` and `backup.blob.connection-string`, which carry the host as DevelopmentStorageProxyUri. The explanatory echo names `storage.blob.public-url` as the deliberate browser-reachable exception.
- **Committed in:** 5f1159de

**3. [Rule 1 - Bug] Horizon site lines moved by this plan's comment lines**
- **Found during:** Task 3
- **Issue:** H-5 advisory notes went from 2 to 4. `golang` Dockerfile:32 moved to 34 and `jtoye-core-java` core-java-deployment.yaml:50 moved to 55, both from comments this plan added.
- **Fix:** Updated those two sites; H-5 is back to the 2 go-ci-setup notes that predate the plan.
- **Committed in:** 5f1159de

**4. [Rule 1 - Bug] Line citations broken by the added comment lines**
- **Found during:** post-Task-3 gate sweep
- **Issue:** check-doc-citations went from 5 to 6 failures (k8s/LOCAL.md:1499 citing core-java-deployment.yaml:307-311). Two more citations the gate does not check had also been accurate before the plan and were now wrong: check-env-contract.sh DB_USER/DB_PASSWORD at 98-102/103-107, and restore-drill.sh at pg-backup-cronjob.yaml:68.
- **Fix:** 312-316, 103-107/108-112 and 75. check-doc-citations is back to its 5 pre-plan failures. Three citations that were already wrong before this plan are logged in `deferred-items.md`, not fixed.
- **Files modified:** k8s/LOCAL.md, k8s/scripts/check-env-contract.sh, scripts/restore-drill.sh
- **Committed in:** 5f415ae0

**5. [Criterion strengthened] Stale `:15` check was partly vacuous**
- **Issue:** `git grep 'jtoye-pg-backup:15"'` requires a closing quote, so it only ever matched the horizons pin. It was blind to the CronJob (`:15$`) and the Dockerfile (`:15 `) spellings.
- **Result:** The literal form gives rc=1 now and rc=0 at d17cb269 (1 hit). The stronger form `jtoye-pg-backup:15([^-]|$)` over tracked k8s + infra gives rc=1 now and rc=0 at d17cb269 (6 hits: Dockerfile, horizons, CronJob, both goldens, local kustomization comment). It excludes `k8s/LOCAL.md` (four historical run records of the old image; 36-10 owns that doc).

**6. [Criteria strengthened] New invariants go beyond the plan text**
- INV-8 also pins the D-07 container names.
- INV-9 also covers envFrom secretRef, volume and projected secrets, and AZURE_CLIENT_SECRET.
- INV-10 also refuses the label on any other pod template and on controller metadata, and requires the quoted string.
- LOC-7 checks pg-backup too.
- Each extension has its own break arm (13, 14; 3 covers the base secret path).

---

**Total deviations:** 4 auto-fixed (1 blocking, 3 bugs) + 2 criteria strengthened. **Impact:** no scope creep. The three files outside `files_modified` (LOCAL.md, restore-drill.sh, and the citation line in check-env-contract.sh) received one-token line-number corrections for breakage this plan caused.

## Issues Encountered

- The first run of the break-arm harness appended a patch path at the end of a kustomization, inside `replacements:`. The render failed with rc=1, which would have been a false red. It was caught because the wi count stayed 0, and re-run by inserting under `patches:`.
- One display pattern in arms 2-5 matched the OK summary lines first. All four had already exited rc=1; they were re-run so the output shows the FAIL line that names each invariant.

## Known Stubs

None.

## Threat Flags

None. No new surface beyond the plan's threat model. T-36-32/33/34/36 are mitigated by INV-9/INV-10/INV-4/INV-10 with break arms 3, 2+13, 5 and 15. T-36-35 (broad 443) is accepted as planned, and removing 9000 narrows both policies.

## User Setup Required

None in this plan. Phase 29 must add the `azure.workload.identity/client-id` annotation to both ServiceAccounts per overlay (docs/runbooks/azure-blob-provisioning.md). Until then core-java refuses to boot in staging/production by design.

## Open Items

- WINDOWS.md #3 (unrun-verify): the live AKS admission path (webhook mutation, projected token with automount false, the Entra exchange) needs a WI-enabled cluster. jtoye-staging-aks has WI off and is stopped (36-05).
- 36-10 still owns: `secrets-template.yaml.example` (the two object-store Secret templates), k8s/LOCAL.md / DEPLOYMENT.md / QUICK_START.md prose, `scripts/k8s-local-secrets.sh` / `k8s-local-up.sh` (MinIO bucket bootstrap, the Azurite bind host). Compose publishes Azurite on 127.0.0.1 by default (#441), so a minikube pod reaches it only once 36-10 changes the bind.
- Under an enforcing CNI the local pods' egress to `host.minikube.internal:10000` would be denied. This is the same pre-existing, documented inert limit that 9000 had (k8s/local/kustomization.yaml D-11 note; issue #297).
- `deferred-items.md`: three citations that were already wrong before this plan; the stale "S3" prose in 10-frontend.yaml; pg-backup's unread `RETENTION_DAYS`.
- Inherited red not touched: `scripts/docs-freshness.sh` (36-17); check-doc-citations' 5 `.planning/codebase` failures.

## Next Phase Readiness

Ready for 36-10 (local scripts, secrets template, k8s docs). Every k8s gate this plan owns is green.

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-29*

## Self-Check: PASSED

- 4 created files present; 4 commits (733485a6, 25ed67ec, 5f1159de, 5f415ae0) found; closing clean gate run green; tree == HEAD by content before this file.
