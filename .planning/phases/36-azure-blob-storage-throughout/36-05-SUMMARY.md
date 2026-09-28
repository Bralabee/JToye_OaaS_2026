---
phase: 36-azure-blob-storage-throughout
plan: 05
subsystem: infra
tags: [azure-blob, storage-accounts, workload-identity, worm, immutability, rbac, runbook, phase-29-handoff, "#626"]
status: complete

requires:
  - phase: 36-azure-blob-storage-throughout
    provides: "36-01 container names/auth-mode vocabulary (jtoye-images public, jtoye-quarantine private, connection-string | workload-identity), 36-04 blobctl (never-overwrite, no delete, write-only-compatible upload), 36-08 plan (no post-upload list)"
provides:
  - "D-11 in 36-CONTEXT.md: owner option-a accounts jtoyestgmedia/jtoyestgbackup (staging), jtoyeprodmedia/jtoyeprodbackup (production); media uksouth, backup ukwest; Standard_LRS; WORM 30 d recorded (unlocked), soft delete 14 d, lifecycle delete 35 d"
  - "docs/runbooks/azure-blob-provisioning.md: the subscription-pinned Blob estate spec Phase 29 executes (sections 1-9)"
  - "Identity spec for Phase 29: jtoye-staging-media-id, jtoye-staging-backup-id; FIC subjects system:serviceaccount:jtoye-staging:core-java and :pg-backup, audience api://AzureADTokenExchange"
  - "Measured facts: Microsoft.Storage NotRegistered on c483d353; jtoye-staging-aks WI null / OIDC on / Stopped; uksouth<->ukwest paired; the backup-writer data actions exist"
affects: [36-09, 36-17, phase-29-provisioning]

actuals:
  tokens: 7415         # chars/4 over git diff 0a022927..50e88f76 (the two task commits), before this SUMMARY
  tasks: 2             # Task 1 resolved by the owner (checkpoint), Task 2 executed here
  commits: 2           # MEASURED: git rev-list --count 0a022927..HEAD before the SUMMARY commit
plan_head_before: 0a022927f43b6500e023ef5358b6482ade662c98

tech-stack:
  added: []
  patterns:
    - "Subscription resolved by full id AND asserted equal: an empty --subscription silently falls back to the ambient (employer) default"
    - "Containers and access levels created on the control plane (container-rm), so no data-plane role or shared key is needed to set them"
    - "Availability evidence without a registered RP: DNS on <name>.blob.core.windows.net with a known-existing and a random-name control"

key-files:
  created:
    - docs/runbooks/azure-blob-provisioning.md
    - .planning/WINDOWS.md
  modified:
    - .planning/phases/36-azure-blob-storage-throughout/36-CONTEXT.md
    - docs/DOCUMENTATION_INDEX.md

key-decisions:
  - "D-11 (owner, via orchestrator checkpoint, 2026-09-28): option-a names, media uksouth / backup ukwest, Standard_LRS, WORM 30 d recorded not locked, soft delete 14 d, lifecycle 35 d"
  - "Runbook creates containers with az storage container-rm (control plane), not --auth-mode login: Set Container ACL cannot use an Entra token and shared-key access is disabled, so the data-plane route cannot set jtoye-images to level blob"
  - "Runbook resolves $SUB with az account show --subscription <full id> plus an equality assertion, not the prefix-list form (account list cannot carry --subscription, and every runbook az line must)"
  - "Region pairing read via az rest --subscription against the Locations API: az account list-locations rejects --subscription and would read the employer default"
  - "#626 read-back for staging uses a URL core-java actually wrote; the operator gets no data-plane role just to upload a test blob"
  - "Restores are read by a human with a time-bound Storage Blob Data Reader on jtoye-db-backups, never by the write-only CronJob identity"

patterns-established:
  - "Every az line in a runbook carries --subscription (awk-checked); commands that cannot (account list, account list-locations) are replaced, not exempted"

requirements-completed: []   # plan declares [BLOB-10, BLOB-02]; requirements.ready-ids: 0/2 ready (BLOB-02 also 36-06/36-07; BLOB-10 also 36-17)

coverage:
  - id: D1
    description: "Owner's account decision recorded as D-11 with regions, SKU, recorded WORM/soft-delete/lifecycle values and availability evidence"
    requirement: BLOB-10
    verification:
      - kind: other
        ref: "awk '/D-11/ && all four names' 36-CONTEXT.md -> 1 (pre-plan 0, first commit 0)"
        status: pass
    human_judgment: false
  - id: D2
    description: "The four names checked available by az storage account check-name against c483d353"
    requirement: BLOB-10
    verification:
      - kind: other
        ref: "az storage account check-name --name <n> --subscription c483d353-... (rc=3 SubscriptionNotFound x4; RP NotRegistered)"
        status: unknown
    human_judgment: true
    rationale: "check-name returned no verdict because Microsoft.Storage is NotRegistered; registering it is an Azure write this plan may not make. Only DNS evidence (NXDOMAIN x4, both controls correct) exists. The owner must register the RP (or let Phase 29 do it) and the check re-run."
  - id: D3
    description: "Subscription-pinned, least-privilege Blob estate runbook (accounts, containers, WORM/soft delete/lifecycle, WI, RBAC, network, read-backs, taken-name rule) indexed in DOCUMENTATION_INDEX.md"
    requirement: BLOB-10
    verification:
      - kind: other
        ref: "awk az-pin check -> '40 0' (arm '41 1'); token counts all >0 (arm 0); git grep secret shapes rc=1 (arms rc=0); index grep 1 line (pre-plan 0); relative links ok (arm BROKEN)"
        status: pass
    human_judgment: true
    rationale: "The commands are checked for syntax (flags against az help) and scanned for pinning, but none can be executed until Phase 29 creates the estate; their correctness against real Azure is a Phase 29 read-back"
  - id: D4
    description: "#626 rule stated for the real staging account (anonymous GET 200, anonymous LIST non-200 with no EnumerationResults, anonymous quarantine GET non-200)"
    requirement: BLOB-02
    verification: []
    human_judgment: true
    rationale: "Specified as a Phase 29 read-back; no staging account exists yet, so nothing here can prove it"

duration: 8min
completed: 2026-09-28
---

# Phase 36 Plan 05: Azure Blob Estate Spec + D-11 Summary

**The owner's option-a storage accounts are recorded as D-11 (jtoyestgmedia/jtoyestgbackup and jtoyeprodmedia/jtoyeprodbackup; media in uksouth, backup in ukwest; LRS; WORM 30 days recorded and unlocked). `docs/runbooks/azure-blob-provisioning.md` is now the Blob estate specification Phase 29 executes. Every `az` line in it pins the subscription. Shared-key access is off on all accounts. Containers are created on the control plane. Roles are container-scoped, and the backup role is write-only. It creates nothing in Azure today. The name-availability check gave NO verdict: the `Microsoft.Storage` provider is not registered on the subscription. Only DNS evidence exists.**

## Performance

- **Duration:** 8 min (continuation from Task 2)
- **Started:** 2026-09-28T22:44:23Z
- **Completed:** 2026-09-28T22:52:24Z
- **Tasks:** 2. Task 1 (checkpoint:decision) was resolved by the owner in the orchestrator session. Task 2 was executed here.
- **Files modified:** 3 in the task commits, plus `.planning/WINDOWS.md` (ledger) in the SUMMARY commit

## Accomplishments

- **D-11 recorded** in `36-CONTEXT.md` under "Decided at plan time". It holds the four names, regions, SKU, the recorded (not locked) retention values, the availability evidence with date, and "Reversibility: costly". The attribution reads "owner, via orchestrator checkpoint, 2026-09-28".
- **Runbook written** (`docs/runbooks/azure-blob-provisioning.md`, 464 lines, sections 1-9 as the plan specified) and indexed under Runbooks.
- **Live read-only measurements** replaced several research assumptions with facts:
  - A12: uksouth and ukwest are a region pair.
  - A7: the backup-writer data-action names exist.
  - The WI webhook is absent on the live cluster, not just in the provision script.
  - Storage Blob Data Contributor carries the `containers/read` action that core-java's startup probe needs.
- **A blocker for Phase 29 was found early:** `Microsoft.Storage` is NotRegistered on the J'Toye subscription. Every `az storage account create` would fail on it, so the runbook's section 2.1 now registers the provider first.

## Task Commits

1. **Task 1: Owner decides account names, regions and redundancy.** Resolved by the owner (option-a + 30-day WORM), with no commit (a decision only).
2. **Task 2: Check availability read-only, write the runbook plus D-11.**
   - `2e25b0dd` docs(36-05): specify the Azure Blob estate for Phase 29 and record D-11
   - `50e88f76` fix(36-05): name all four accounts on the D-11 line itself

**Plan metadata:** the SUMMARY commit that follows (docs(36-05): complete ...)

## Evidence: availability and subscription (raw)

**Subscription resolution** (the plan's prefix form, run with `az account list --all`):

```
pass arm (c483d353):          SUB=c483d353-5f61-4587-a790-addb9ab5fb94 rc=0
fail arm (00000000):          VOID: prefix '00000000' resolved to 0 subscription ids (need exactly 1)   rc=2
fail arm ('' -> all subs):    VOID: prefix '' resolved to 4 subscription ids (need exactly 1)           rc=2
```

`az account show --subscription c483d353-…` returned `"name": "JToye Digital Production", "state": "Enabled", "isDefault": false, tenant b56df236-…` (rc=0). The ambient default is `Prod - HS2 Ltd` (isDefault True), which was never used.

The runbook's own form (`az account show --subscription <full id>` + equality assertion):

```
pass: SUB=c483d353-5f61-4587-a790-addb9ab5fb94 rc=0
fail(unknown id): ERROR: Subscription 'c483d353-0000-0000-0000-000000000000' not found. rc=2
fail(empty id):   VOID: resolved '8d1c4578-4129-40d5-a6be-fd24d96b7959' state 'Enabled' (want  Enabled) rc=2
```

The empty-id arm shows why the assertion is load-bearing: `--subscription ""` silently resolves the EMPLOYER subscription. A first draft of the form, with a wrong JMESPath, returned an empty `SUB` with rc=0. The equality assertion exists to catch exactly that.

**The four check-name results (2026-09-28T22:44:37Z)**, one block per name, all identical:

```
== jtoyestgmedia rc=3
ERROR: (SubscriptionNotFound) Subscription c483d353-5f61-4587-a790-addb9ab5fb94 was not found.
== jtoyestgbackup rc=3   (same)
== jtoyeprodmedia rc=3   (same)
== jtoyeprodbackup rc=3  (same)
```

Diagnosis (all read-only):
- The token for the subscription is valid (tenant b56df236, expires 2026-09-29 00:49).
- `az group show -n jtoye-rg` succeeded (uksouth, Succeeded).
- `az provider show -n Microsoft.Storage` returned **`NotRegistered`**, and `Microsoft.ManagedIdentity` returned `Registered`.

ARM answers `checkNameAvailability` for an unregistered provider with `SubscriptionNotFound`. Registering the provider is a subscription write, which the orchestrator forbade for this plan. **So no check-name verdict exists.**

**Secondary evidence: DNS** (2026-09-28T22:45:25Z; `dig <name>.blob.core.windows.net A`):

```
azureopendatastorage (control, known account):  NOERROR  CNAME->blob.blz25prdstr01a.store.core.windows.net. A->20.209.75.225
jtoyezzq9x7k2nonexist (control, random name):   NXDOMAIN
jtoyestgmedia:   NXDOMAIN
jtoyestgbackup:  NXDOMAIN
jtoyeprodmedia:  NXDOMAIN
jtoyeprodbackup: NXDOMAIN
```

This shows that no storage account held these names on that date. It is not the plan's instrument. It cannot see names Azure reserves for other reasons, such as a recently deleted account still inside its recovery window.

**Name format** (`^[a-z0-9]{3,24}$`): all four are valid (13/14/14/15 characters). The control names `JToyeStg`, `jtoye-stg-media`, `ab` and a 25-character name were all INVALID.

**Other read-only facts written into the runbook:**
- Region pairing, via `az rest --subscription … /locations`: `uksouth -> paired ukwest`, `ukwest -> paired uksouth`.
- `az account list-locations` rejected `--subscription` (rc=2), so it was not used unpinned.
- `az aks show jtoye-staging-aks`: `oidcEnabled: true`, `workloadIdentity: null`, `power: Stopped`, `location: uksouth`.
- `az provider operation show --namespace Microsoft.Storage`: 223 operations. `…/blobs/write` and `…/blobs/add/action` are FOUND with isDataAction True. A made-up control name is ABSENT.
- `Storage Blob Data Contributor` has actions `containers/{delete,read,write}` and `generateUserDelegationKey/action`, and data actions blobs `delete/read/write/move/add`.
- Flag existence for every runbook command came from `az … --help`. It includes `--audiences` (plural) on `federated-credential create`. The control flag `--not-a-real-flag-control` came back MISS. A `--subscription` scan of 25 commands returned yes for all 25, and the `account list-locations` control returned NO.

## Evidence: acceptance criteria, both directions

Break arms ran on the committed tree (`50e88f76`), bracketed. Before and after the arms, each file's `git hash-object` equalled its HEAD blob:

```
OPENING  CLEAN runbook 2f756f1b3f33 | k8s/base/configmap.yaml 8898ec6a2cdb | DOCUMENTATION_INDEX.md ba3fd78be6f6
CLOSING  CLEAN runbook 2f756f1b3f33 | k8s/base/configmap.yaml 8898ec6a2cdb | DOCUMENTATION_INDEX.md ba3fd78be6f6
```

| Criterion | Real tree | Fail direction |
|---|---|---|
| Subscription resolution exits non-zero on a non-existent prefix | `c483d353` → one id, rc=0 | `00000000` → rc=2 VOID; `''` → 4 ids, rc=2 VOID |
| `awk '/az /{…}'` prints n>0 and 0 | **`40 0`** | appended unpinned az line → **`41 1`**; control: appended kubectl line → `40 0` (not an az line) |
| `git grep -E 'AccountKey\|SharedAccessSignature\|sig='` rc=1 only | **rc=1** | appended `…;AccountKey=…` → rc=0 with the line; appended `…?sv=2024&sig=abc` → rc=0 with the line |
| `git grep -n 'D-11'` prints ≥1 line naming all four accounts | **as written this was VACUOUS for "≥1 line"**: the pre-plan tree already had one D-11 line (the "Phase 29 D-11 and D-12 SUPERSEDED" reference). Strengthened to "a D-11 line containing all four names": fixed tree **1** (l.84) | pre-plan tree **0**; first commit `2e25b0dd` **0** (the names were on continuation lines, which was a real defect fixed in `50e88f76`) |
| `git grep 'azure-blob-provisioning' DOCUMENTATION_INDEX.md` = 1 line | **1** (l.119) | pre-plan `HEAD~2`: **0** |
| Verify: token counts all >0 | `enable-workload-identity=1 allow-shared-key-access=3 immutability-policy=4 api://AzureADTokenExchange=3 public-access blob=1 ukwest=4` | two tokens sed-removed → those two print **0**; file absent → awk `cannot open`, no counts |
| Verify: `check-no-measured-placeholders.sh` rc=0 | **PASS rc=0** | a placeholder planted in `k8s/base/configmap.yaml` → **rc=1** (`matches: 1`). A placeholder planted **in the runbook** → still **PASS rc=0**. **The gate is VACUOUS for this plan's output**, because its scope is `application*.yml`, `.env.example` and `k8s/` only. Replacement: `rg -uu -n '<<(MEASURED\|TODO\|TBD)>>' <runbook>` → real rc=1, planted rc=0 |
| (extra) relative links in runbook + index resolve | all `ok` | planted `./no-such-runbook.md` → `BROKEN` |

## Files Created/Modified

- `docs/runbooks/azure-blob-provisioning.md` (new). Sections: (1) ownership and subscription pin, (2) accounts plus RP registration and check-name, (3) containers via container-rm, (4) soft delete → unlocked WORM → lifecycle → human lock checkpoint, (5) enabling WI plus identities, FICs and the SA annotation, (6) container-scoped RBAC with the write-only backup role, (7) network, (8) read-backs including #626 for staging, (9) the taken-name rule.
- `.planning/phases/36-azure-blob-storage-throughout/36-CONTEXT.md`: the D-11 bullet.
- `docs/DOCUMENTATION_INDEX.md`: a Runbooks row.
- `.planning/WINDOWS.md` (new, created by `gsd_run windows append`): entry #1, `unrun-verify` for the verdict-less check-name.

## Decisions Made

See `key-decisions` in the frontmatter. The two that change the plan's literal wording are recorded as deviations 2 and 3 below.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] D-11 names were not on the D-11 line**
- **Found during:** Task 2 criterion check
- **Issue:** The criterion greps single lines. The first write put the four names on continuation lines, so the check printed only unrelated D-11 lines.
- **Fix:** The D-11 line now lists all four names. The criterion was strengthened to "D-11 AND all four names on one line", because "≥1 line" was already true before the plan.
- **Files modified:** 36-CONTEXT.md
- **Verification:** pre-plan 0, `2e25b0dd` 0, fixed 1
- **Committed in:** `50e88f76`

**2. [Rule 1 - Bug] Container creation route**
- **Found during:** Task 2 (runbook section 3)
- **Issue:** The plan says to create the containers with `--auth-mode login` "because Set Container ACL cannot be authorized with Entra ID". Those two facts conflict. Shared-key access is disabled on every account, so a login-authenticated data-plane request cannot set `jtoye-images` to level `blob`.
- **Fix:** The runbook uses `az storage container-rm create --public-access blob|off`. That is the control plane (the resource provider), which needs neither shared key nor a data role. `--auth-mode login` is named as the fallback only. The read-back uses `container-rm show` for the same reason, instead of the plan's `az storage container show`.
- **Files modified:** docs/runbooks/azure-blob-provisioning.md
- **Verification:** the flags exist per `az … --help`. Real execution is a Phase 29 read-back.
- **Committed in:** `2e25b0dd`

**3. [Rule 3 - Blocking] Runbook subscription resolution and region lookup**
- **Found during:** Task 2
- **Issue:** The plan's `az account list …starts_with…` resolution and `az account list-locations` cannot carry `--subscription`. The latter rejects it (rc=2) and would otherwise read the employer default. Either would break the plan's own "every az line pins the subscription" criterion.
- **Fix:** The runbook uses `az account show --subscription <full id>` with an equality assertion (all three arms recorded above), and `az rest --subscription` against the Locations API. The plan's prefix form was still run for THIS task's own resolution, as specified.
- **Committed in:** `2e25b0dd`

**4. [Rule 2 - Missing critical] Resource-provider registration step**
- **Found during:** Task 2 (the availability check)
- **Issue:** `Microsoft.Storage` is NotRegistered, and the plan's runbook outline has no step for it. Every create in Phase 29 would fail.
- **Fix:** Runbook section 2.1 registers the provider (`--wait`), reads back `Registered`, then re-runs `check-name` for all four names before creating anything.
- **Committed in:** `2e25b0dd`

**5. [Plan criterion vacuous] `check-no-measured-placeholders.sh` does not scan docs/**
- Recorded in the evidence table. The gate still passes on the tree (it guards `k8s/`, which 36-09 fills). A strictly stronger `rg -uu` check on the runbook was added and run in both directions.

---

**Total deviations:** 5 (2 Rule 1, 1 Rule 2, 1 Rule 3, 1 vacuous-criterion replacement)
**Impact on plan:** All five keep the runbook executable and pinned. No scope creep: nothing was created in Azure and no file outside the plan's list was touched, except the ledger.

## Issues Encountered

- **The name-availability truth is NOT MET by the specified instrument.** The plan's must-have says "Every chosen name was checked available with a read-only az call". All four calls ran against the right subscription and returned `SubscriptionNotFound` because of the unregistered provider. That is no verdict, and it is not reported as a pass. The DNS evidence says no account held the names on 2026-09-28. **Owner action needed**, choose one:
  - (a) Authorise `az provider register --namespace Microsoft.Storage --wait --subscription c483d353-5f61-4587-a790-addb9ab5fb94`. It is free and reversible, and Phase 29 needs it anyway. Then re-run `check-name` for the four names and append the result to D-11.
  - (b) Accept the DNS evidence until Phase 29, whose runbook §2.1 runs the real check before any create.
  - This is tracked as `.planning/WINDOWS.md` entry #1 (`unrun-verify`).
- `jtoye-staging-aks` was measured `Stopped`. `az aks update --enable-workload-identity` may need the cluster started, which is an owner cost decision. It is noted in runbook §5.

## Known Stubs

None in code. The runbook contains one deliberate fill-in that is not a placeholder: `<clientId of jtoye-staging-media-id>` in the ServiceAccount patch example. The client id exists only after Phase 29 creates the identity. Until then core-java fails fast on the missing `AZURE_CLIENT_ID`, which is 36-09's intended, visible state.

## User Setup Required

None for this plan. Phase 29 executes the runbook. The one pending owner action is the provider registration choice under Issues Encountered.

## Next Phase Readiness

- **36-09** can take the account endpoints from D-11: `https://jtoyestgmedia.blob.core.windows.net`, `https://jtoyestgbackup.blob.core.windows.net`, and the prod equivalents. If a later `check-name` reports a name taken, runbook §9 governs: a new owner decision, then the overlays and goldens in one change.
- **36-17 (BLOB-10 handoff)** can cite this runbook as the provisioning spec. It still owns the operator-secret list change (7 → 3) and the conflict map.
- Requirements: `requirements.ready-ids` reports **0/2** ready. BLOB-02 is also declared by 36-06 and 36-07, and BLOB-10 by 36-17. Neither is marked complete here.

## Threat Flags

None. The runbook introduces no new surface beyond the plan's `<threat_model>` (T-36-16..20). Each mitigation is present:
- container-scoped roles only, with the write-only backup role (T-36-16)
- `--allow-shared-key-access false` everywhere, and no key or SAS in the file (T-36-17)
- the backup account private, separate and in ukwest (T-36-18)
- every az line pinned (T-36-19)
- the policy left unlocked, with a named human lock (T-36-20)

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-28*
