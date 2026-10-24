---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 01
subsystem: infra
tags: [azure, az-cli, aks, cilium, key-vault, workload-identity, entra-rbac, ci-gate, bash]

requires:
  - phase: 36-azure-blob-storage-throughout
    provides: "the subscription-pin runbook (azure-blob-provisioning.md section 1), the media/backup identity names, the one exempt az storage listing"
provides:
  - "scripts/lib/azure-guard.sh: az_guard_init, az_mutate, az_read, az_guard_stub, az_guard_selftest (the guard contract every later Phase 29 Azure step uses)"
  - "scripts/check-az-subscription-pin.sh: static gate, wired into ci-cd.yaml ops-contracts (selftest + scan)"
  - "scripts/azure-staging-provision.sh: decision-gated, stub-only dry-run, stages providers/network/aks/keyvault/identities/ci"
  - "the naming contract in code: jtoye-stg-aks, jtoye-stg-ingress-ip, jtoye-stg-outbound-ip, jtoye-stg-kv, ten jtoye-staging-*-id identities, federated-credential names <sa>-jtoye-staging"
affects: [29-02, 29-03, 29-06, 29-07, 29-09, 29-11, 29-12, 29-13, 29-14]

actuals:
  tokens: 21105
  tasks: 3
  commits: 5
plan_head_before: edc2c94d3b8b14418600287bbbbf54a5807059d6
plan_head_after: 93844d71ea1ca9fa61dad1df23182bf4e12e2de7

tech-stack:
  added: []
  patterns:
    - "Every Azure CLI call goes through az_mutate/az_read; the pin is appended as the LAST two argv entries, and az_guard_init's own read-back carries it last too"
    - "One argv-recording az_guard_stub (marker .az-guard-stub) for every dry-run; a dry-run refuses any other az, a live run refuses the stub"
    - "Selftests print one PASS/FAIL line per named arm (plain is the CI contract) and render TAP 13 on request for the RED-evidence classifier"
    - "Static exemption table inside the gate, never a marker in the scanned file; a row matching no call is itself a finding"

key-files:
  created:
    - scripts/lib/azure-guard.sh
    - scripts/check-az-subscription-pin.sh
    - scripts/azure-staging-provision.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-01-t1-red-unset.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-01-t1-red-returns-to-caller.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-01-t2-red-raw-call.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-01-t2-red-unlisted.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-01-t2-red-stale-exemption.json
  modified:
    - .github/workflows/ci-cd.yaml

key-decisions:
  - "The network-policy engine is Cilium, chosen at cluster creation (assumption delta promoted: --network-dataplane cilium --network-policy cilium); manifests stay networking.k8s.io/v1 NetworkPolicy, and plan 29-02 pins the render to that dialect"
  - "Sourcing azure-guard.sh resets AZ_GUARD_OK to 0 and both wrappers re-check the id on every call, so an exported flag inherited from a parent can never stand in for an init"
  - "wrapper_calls in the pin gate counts consuming call sites only (the library's own selftest calls excluded), so Task 3's wrapper_calls>=1 criterion could fail before Task 3 (it read 0) and passes after (15)"
  - "The operator roles (RBAC Cluster Admin, Key Vault Secrets Officer) go only to a signed-in account whose user.type is user; a service-principal or managed-identity login is REFUSED [operator], so jtoye-ci can never receive them through this script"
  - "An existing cluster, vault, federated credential or jtoye-ci credential is read back and REFUSED on any one-way property mismatch; it is never recreated or adopted silently"

patterns-established:
  - "Guard-first provisioning: argument parse, decision record (no az call), stub check, az_guard_init, then stages"
  - "Live-only assertions are exercised locally against a marker-less copy of the stub with an answers table, never against Azure"

requirements-completed: [DPLY-01, DPLY-05]

coverage:
  - id: D1
    description: "Subscription guard library: refuses unset, empty, wrong-id (six near-miss shapes plus an inherited flag), wrong-name and disabled subscriptions before any mutating call; every wrapped call and the init read end with the pin; wrappers return to the caller"
    requirement: "DPLY-01"
    verification:
      - kind: unit
        ref: "bash -c 'source scripts/lib/azure-guard.sh && az_guard_selftest' (8/8 PASS, rc 0, refusal arms argv_count=0)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Static pin gate fails on any raw az call outside the wrappers, VOIDs on an empty discovery, admits only the declared az storage exemption, fails a stale exemption; wired into ci-cd.yaml ops-contracts"
    requirement: "DPLY-01"
    verification:
      - kind: unit
        ref: "bash scripts/check-az-subscription-pin.sh --selftest (9/9 PASS, rc 0)"
        status: pass
      - kind: other
        ref: "bash scripts/check-az-subscription-pin.sh (rc 0, SUMMARY discovered=3 wrapper_calls=15 exempt=1 findings=0)"
        status: pass
      - kind: other
        ref: "bash scripts/check-gate-enforcement.sh (rc 1 naming the gate before wiring; rc 0 after)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Compute/identity provisioning script, decision-gated and stub-only in dry-run, with every D-01/D-23/D-32 create flag and the Cilium engine (DPLY-05's enforcing engine chosen at creation)"
    requirement: "DPLY-05"
    verification:
      - kind: other
        ref: "Task 3 verify 1-3 (no decision file rc 1 / 0 calls; dry-run rc 0, 1 aks create with all 13 flags, 57/57 calls pinned; gate wrapper_calls>=1)"
        status: pass
    human_judgment: false
  - id: D4
    description: "The live run against the J'Toye subscription (plan 29-09): real create, polls and read-back assertions"
    verification: []
    human_judgment: true
    rationale: "Deliberately not executed here (no Azure mutation without the 29-09 owner checkpoint). The live-only assertions were exercised only against a local fake az; a real Azure response shape (tsv layout, guest-UPN resolution, BYO outbound IP permission) is still unmeasured."

duration: 19min
completed: 2026-10-18
status: complete
---

# Phase 29 Plan 01: Structural Subscription Pin and Compute/Identity Provisioning Summary

**A sourced guard library that refuses every wrong-subscription shape before any az call and appends `--subscription c483d353-…` as the last two argv entries of every call, a CI gate that fails any raw `az` in scripts/ (one declared Azurite `az storage` exemption), and a decision-gated AKS/Key Vault/identity provisioning script whose stub-only dry-run carries Cilium, overlay, ephemeral disks, Workload Identity, the Key Vault CSI add-on and Entra RBAC with local accounts disabled. Nothing in Azure was created.**

## Performance

- **Duration:** 19 min
- **Started:** 2026-10-18T20:56:02Z
- **Completed:** 2026-10-18T21:14:35Z
- **Tasks:** 3 of 3
- **Files modified:** 9 (3 scripts, 1 workflow, 5 RED-evidence records)

## Accomplishments

- `scripts/lib/azure-guard.sh`: `az_guard_init` (byte-for-byte id equality, then `account show --query '{name:name,state:state}' -o tsv --subscription <id>`, name `JToye Digital Production` and state `Enabled` required), `az_mutate [--no-echo]`, `az_read`, `az_guard_stub DIR`, `az_guard_selftest`. No function calls `exit` or `exec`.
- `scripts/check-az-subscription-pin.sh`: command-position detection (line start or after `;`, `&`, `|`, `$(`, a backtick, through the plan's prefix tokens), file-own line numbers, `AZ_PIN_EXEMPT` table in the gate, `EXCLUDED`/`EXEMPT`/`FINDING`/`SUMMARY` output, exit 0/1/2. Wired as two steps in `ops-contracts`.
- `scripts/azure-staging-provision.sh`: STEP 0 decision record (no az call), stub-only dry-run (rc 2 otherwise; a live run refuses the stub), `az_guard_init`, the six stages, identifiers-only evidence. Every live read-back assertion (one-way AKS properties, Key Vault RBAC/retention/purge, federated-credential issuer+subject, jtoye-ci subject and no admin role, quota, operator user.type) refuses on a mismatch.

## Task Commits

1. **Task 1: Guard library** - `198c89b2` (test, RED), `ba267907` (feat, GREEN)
2. **Task 2: Static pin gate wired into CI** - `157e957b` (test, RED), `cc127deb` (feat, GREEN + CI wiring)
3. **Task 3: Compute and identity provisioning script** - `93844d71` (feat)

**Plan metadata:** recorded in the docs commit that adds this file.

## Evidence (both directions, real output)

### Task 1 — guard library

RED (committed `198c89b2`: `az_guard_init` always passes, `az_read` uses `exec`), `bash -c 'source scripts/lib/azure-guard.sh && az_guard_selftest'`, rc=1:
```
FAIL unset expected init/mutate/read rc '1 1 1', got '<no output>'
FAIL empty expected init/mutate/read rc '1 1 1', got '<no output>'
FAIL wrong-id value '00000000-0000-0000-0000-000000000000' gave '<no output>'; … an inherited AZ_GUARD_OK=1 with a wrong id gave '<no output>';
FAIL wrong-name expected init/mutate rc '1 1', got '0 0'
FAIL disabled expected init/mutate rc '1 1', got '0 0'
FAIL pinned-argv first logged line is not az_guard_init's pinned account-show read: 'group create -n azg-selftest -l uksouth --subscription c483d353-5f61-4587-a790-addb9ab5fb94'
PASS no-echo argv_count=1
FAIL returns-to-caller expected both sentinels with rc=3, got '<no output>'
```
`gsd_run check tdd-red-evidence` on the TAP rendering: `unset RED_EVIDENCE_OK target_test_failed tap 8 7`, `returns-to-caller RED_EVIDENCE_OK target_test_failed tap 8 7`. Semantic assessment: both targets executed and failed on their planned assertion for the intended reason (placeholder init passes, exec replaces the arm shell); `no-echo` passing in the same run proves the stub and harness live.

RED variant 2 (scratch copy of the GREEN file with the init read reordered to `account show --subscription "$id" --query …`), rc=1:
```
FAIL pinned-argv only 2 of 3 logged lines end with '--subscription c483d353-5f61-4587-a790-addb9ab5fb94': account show --subscription c483d353-… --query {name:name,state:state} -o tsv|group create …|group show …|
```
(the other seven arms PASS in that run).

GREEN (`ba267907`), the plan's verify line, rc=0:
```
PASS unset argv_count=0
PASS empty argv_count=0
PASS wrong-id argv_count=0
PASS wrong-name argv_count=1
PASS disabled argv_count=1
PASS pinned-argv argv_count=3
PASS no-echo argv_count=2
PASS returns-to-caller argv_count=3
missing=0 fail_lines=0 refusal_arms_with_zero_calls=3
```
Also rc=0 with 8 PASS under a caller with `set -euo pipefail`. GREEN pinned-argv stub log (init line included, every line ends with the pin):
```
account show --query {name:name,state:state} -o tsv --subscription c483d353-5f61-4587-a790-addb9ab5fb94
group create -n azg-selftest -l uksouth --subscription c483d353-5f61-4587-a790-addb9ab5fb94
group show -n azg-selftest --query id -o tsv --subscription c483d353-5f61-4587-a790-addb9ab5fb94
```
`command grep -c 'c483d353-5f61-4587-a790-addb9ab5fb94' scripts/lib/azure-guard.sh` = 2 (>= 1).

### Task 2 — pin gate

RED (committed `157e957b`, `scan()` returns 0 without looking): all nine arms FAIL, rc=1, e.g. `FAIL raw-call expected rc 1, got 0`, `FAIL unlisted expected rc 1, got 0`, `FAIL stale-exemption expected rc 1, got 0`. Classifier: `raw-call`, `unlisted`, `stale-exemption` each `RED_EVIDENCE_OK target_test_failed tap 9 9`.

CI wiring fail direction (gate file present, before the ci-cd.yaml steps), `bash scripts/check-gate-enforcement.sh`, rc=1, stderr:
```
FAIL: 1 gate(s) are referenced by no workflow and carry no exemption:
        check-az-subscription-pin.sh
```
After wiring: `PASS: every gate either runs in CI or has a declared reason it cannot.`, rc=0, `named_unwired=0`. The gate was classified STATIC (no `invokes:` list), so it needs no gate-enforcement.conf row. `command grep -c 'check-az-subscription-pin.sh' .github/workflows/ci-cd.yaml` = 2.

GREEN selftest, rc=0: `PASS raw-call`, `PASS prefixed`, `PASS not-a-call`, `PASS empty-tree`, `PASS exempt-pass`, `PASS unlisted`, `PASS exempt-non-storage`, `PASS stale-exemption`, `PASS guard-lib-outside-wrapper`; `missing=0 fail_lines=0`.

The raw-call arm's own output (temp tree, planted `az group list` in scripts/staging-x.sh), rc=1:
```
FINDING scripts/staging-x.sh:1 — raw az call outside az_mutate/az_read (not pinned to the J'Toye subscription): az group list
SUMMARY discovered=3 wrapper_calls=0 exempt=1 findings=1
```
Real tree after Task 2, rc=0:
```
EXCLUDED scripts/check-az-subscription-pin.sh — the gate's own selftest fixtures
EXEMPT scripts/check-media-content-types.sh:196 — Azurite data-plane listing via AZURE_STORAGE_CONNECTION_STRING (Phase 36, #488); no subscription is involved, so the pin cannot apply
SUMMARY discovered=2 wrapper_calls=0 exempt=1 findings=0
```
Adversarial probe beyond the arms (not in the selftest): `timeout 30 az`, a backtick call, `&& az`, `sudo az`, `env FOO=1 az`, `nohup az &`, `do az`, `! az`, `{ az …; }`, a bare `az`, `FOO="a b" az` were 11 findings; `echo az …`, `jaz …`, `az_mutate …`, `$(az_read …)`, `aznot …`, `x="az group list"` were not.

actionlint 1.x at ~/go/bin/actionlint: base revision 0 findings, new file 0 findings (shellcheck integration absent: shellcheck is not installed on this host, so `run:` scripts were not shellchecked). Fail direction: a planted `bogus-key` under each job gave rc=1 with 13 `[syntax-check]` findings.

### Task 3 — provisioning script

Run 1 (no decision file), rc=1, 0 stub calls:
```
REFUSED [decisions]: no decision record at /tmp/tmp.cSiu2bJ914/absent-decisions.md. Plan 29-09's checkpoint writes it after the owner approves the one-way parameter set; nothing runs before that.
rc=1 refused_lines=1 stub_calls=0
```
Run 2 (temporary stub decision file in a temp dir, not committed), first attempt — the verify FAILED on a real defect:
```
rc=0 aks_create_lines=1 missing_flags=0 stub_calls=59 pinned_calls=57
role assignment create --assignee DRY-RUN skip: assert the signed-in account is a user …
```
(a `skip` line inside `operator_upn`, whose stdout is captured, became the `--assignee` value and split one argv line in two). Fixed (`skip` writes to stderr); re-run, rc=0:
```
rc=0 aks_create_lines=1 missing_flags=0 stub_calls=57 pinned_calls=57
aks create -g jtoye-rg -n jtoye-stg-aks -l uksouth --tier free --kubernetes-version stub-id --network-plugin azure --network-plugin-mode overlay --pod-cidr 10.244.0.0/16 --network-dataplane cilium --network-policy cilium --node-vm-size Standard_B2s --node-count 3 --node-osdisk-type Ephemeral --node-osdisk-size 30 --enable-oidc-issuer --enable-workload-identity --enable-addons azure-keyvault-secrets-provider --enable-aad --enable-azure-rbac --disable-local-accounts --load-balancer-outbound-ips stub-id --auto-upgrade-channel patch --node-os-upgrade-channel NodeImage --generate-ssh-keys --output none --subscription c483d353-5f61-4587-a790-addb9ab5fb94
```
Fail direction of the flag check: the same verify against a temp-tree copy with `--network-policy cilium` removed printed `MISSING --network-policy cilium`, `missing_flags=1`, verify rc=1.

Run 3 (stub NOT on PATH): with a marker-less fake `az` that logs its calls first on PATH, rc=2 and `real_calls=0`; with `/usr/bin/az` resolving, rc=2:
```
VOID [dry-run]: az on PATH is '/usr/bin/az', not an az_guard_stub (no .az-guard-stub beside it). A dry-run must never reach the real CLI: …
```
Pin gate after Task 3, rc=0: `SUMMARY discovered=3 wrapper_calls=15 exempt=1 findings=0` (the same criterion read `wrapper_calls=0` after Task 2, so `[1-9]` could fail).

Static counts on continuation-joined, comment-stripped statements: `aks_create_stmts=1 with_pod_cidr=1`, `kv_create_stmts=1 with_rbac=1`; on a scratch copy with both flags stripped: `with_pod_cidr=0 kv with_rbac=0`. (A plain line grep for `keyvault create .*--enable-rbac-authorization true` reads 0 on the real file because the flag sits on a continuation line; that form cannot see the property and was replaced by the joined form, recorded here.)

Further refusal arms, all with 0 stub calls: no APPROVED line, `APPROVED: option-c`, missing key, empty key, a key given twice with different values, bad CIDR, `KV_PURGE_PROTECTION=yes`, `KV_RETENTION_DAYS=3`, both options approved (each rc=1 `REFUSED [decisions]`); wrong and empty subscription id (rc=1 `REFUSED [subscription]`); a live run with the stub on PATH (rc=2 `VOID [live]`). `--enable-purge-protection true` appears only with `KV_PURGE_PROTECTION=true`. Each `--stage` alone: all calls pinned (providers 3/3, network 6/6, aks 10/10, keyvault 5/5, identities 32/32, ci 6/6). `--stage bogus` rc=2.

Live-only paths against a LOCAL marker-less fake az with an answers table (no Azure contact): the reuse path completes rc=0, 64/64 calls pinned, kubernetes version selected 1.36.3 from `1.36.1 1.36.3 1.36.2`; eight break arms each refuse: AKS policy `azure` (REFUSED [aks]), jtoye-ci subject `ref:refs/heads/main` (REFUSED [ci]), signed-in `servicePrincipal` (REFUSED [operator]), standardBSFamily 4 free (REFUSED [quota]), jtoye-ci holding 1 admin role (REFUSED [ci]), a wildcard federated subject (REFUSED [identities]), vault not in RBAC mode (REFUSED [keyvault]), provider read failing (FAIL, rc 1).

## Files Created/Modified

- `scripts/lib/azure-guard.sh` - the guard contract (init, wrappers, stub, selftest)
- `scripts/check-az-subscription-pin.sh` - the static pin gate and its nine-arm selftest
- `scripts/azure-staging-provision.sh` - compute/identity provisioning (providers, network, aks, keyvault, identities, ci)
- `.github/workflows/ci-cd.yaml` - two steps in `ops-contracts` beside the object-store residue step
- `.planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-01-t{1,2}-red-*.json` - RED evidence records (RED_EVIDENCE_OK)

## Decisions Made

- Assumption delta promoted: the network-policy engine is Cilium, at creation. Until 2026-10-18 the requirement said only "an enforcing engine (#297)". Rationale: Microsoft recommends Cilium, NPM retires on Linux 2028-09-30, Calico is supported for standard NetworkPolicy only; the manifests stay standard NetworkPolicy, so this adds no CRD dependency. Plan 29-02 implements the paired invariant (render = networking.k8s.io/v1 only; this argv = `--network-policy cilium`). Recorded in the script's aks-stage comment.
- See key-decisions in the frontmatter for the flag reset, wrapper_calls scope, operator user.type, and read-back-never-adopt choices.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] A dry-run skip line was captured into the operator UPN**
- **Found during:** Task 3 (verify 2, first run)
- **Issue:** `skip()` printed to stdout inside `operator_upn`, whose stdout is captured, so the role-assignment `--assignee` became the skip sentence and one argv line split in two (57 of 59 lines pinned).
- **Fix:** `skip()` writes to stderr, with a comment naming the measured failure.
- **Files modified:** scripts/azure-staging-provision.sh
- **Verification:** verify 2 re-run: 57/57 pinned, both `--assignee` values `stub-id`.
- **Committed in:** 93844d71

**2. [Rule 3 - Blocking] The stub could not answer a multi-line tsv read**
- **Found during:** Task 3 (exercising the live quota assertion against a local fake)
- **Issue:** an answers row could carry only one output line, so the two-row `vm list-usage` answer could not be expressed and the live parse VOIDed on the fake.
- **Fix:** az_guard_stub prints the answer with `printf %b`, so `\n` and `\t` expand; documented in the library header. Guard selftest re-run 8/8 PASS, gate selftest 9/9 PASS.
- **Files modified:** scripts/lib/azure-guard.sh
- **Committed in:** 93844d71

**3. [Rule 2 - Missing critical] Guard hardening beyond the literal contract**
- **Found during:** Task 1
- **Issue:** with `export AZ_GUARD_OK=1` as specified, a child process inheriting the flag could pass the wrappers' flag check against a different id.
- **Fix:** sourcing resets the flag to 0, and both wrappers re-check the id on every call; the `wrong-id` arm includes an inherited-flag case that must refuse (`skipped 1 1`).
- **Committed in:** ba267907

**4. [Rule 2 - Missing critical] Provisioning checks the plan did not spell out**
- **Found during:** Task 3
- **Issue/fix:** quota also checks the regional `cores` total (a family quota alone cannot prove the region fits); decision values are validated (CIDR shape, true/false, retention 7..90, one APPROVED option, no conflicting duplicates); the operator roles require `user.type == user`; existing resources are read back and refused on a mismatch instead of adopted; a live run refuses the stub; after the CI grants, jtoye-ci is asserted to hold no AKS admin role subscription-wide.
- **Committed in:** 93844d71

**5. [Rule 3 - Blocking] RED evidence needed a supported report format**
- **Found during:** Task 1 RED
- **Issue:** the plain `PASS/FAIL <arm>` lines are an unsupported format for `gsd_run check tdd-red-evidence` (`INVALID_RED invalid_record`).
- **Fix:** both selftests render TAP 13 when `AZ_GUARD_SELFTEST_FORMAT=tap` / `AZ_PIN_SELFTEST_FORMAT=tap`; the plain form stays the default and the CI/verify contract. Records reclassified `RED_EVIDENCE_OK`.
- **Committed in:** 198c89b2, 157e957b

---

**Total deviations:** 5 auto-fixed (1 bug, 2 blocking, 2 missing-critical). **Impact on plan:** all inside the three planned files; no scope beyond the plan's artifacts, no Azure contact.

## Issues Encountered

- `check-doc-citations.sh` and `check-terminal-states.sh` VOID (rc 2) on this host because the python PATH shim refuses a bare interpreter outside an env; pre-existing and unrelated to this plan (no file they read was touched). Not fixed (scope boundary).

## Open items for plan 29-09 (live run)

- **[ASSUMED] BYO outbound IP permission.** `--load-balancer-outbound-ips` names `jtoye-stg-outbound-ip` in jtoye-rg while the control plane has a system-assigned identity; the plan grants Network Contributor on the ingress IP ONLY, as written. If the create fails with a permission error on the outbound IP, the fix is a decision (a pre-created control-plane identity with `--assign-identity`, or a scoped grant), not an improvisation.
- **[ASSUMED] Operator UPN resolution.** `--assignee <user.name>` must resolve in the J'Toye tenant. If the owner signs in as a guest or Microsoft account, the UPN differs from the sign-in name and the role assignment will fail loudly; the script never falls back to `ad signed-in-user show`.
- **[ASSUMED] tsv layout of multi-value queries.** The script normalises tabs to rows, so it reads both one-row and one-value-per-line layouts; the real layout is measured only on the first live run.
- `--generate-ssh-keys` (as specified) writes `~/.ssh/id_rsa` on the operator host if absent.

## User Setup Required

None for this plan. Plan 29-09's checkpoint writes `29-ESTATE-DECISIONS.md` (`APPROVED: option-a|b`, `POD_CIDR`, `KV_PURGE_PROTECTION`, `KV_RETENTION_DAYS`) before any live run.

## Next Phase Readiness

- Every later Azure step can source `scripts/lib/azure-guard.sh` and use `az_guard_stub` for its dry-run; the CI gate rejects any script that does not.
- No Azure resource exists yet (no live command was run; no `az` call reached the real CLI in any test).

## Self-Check: PASSED

- FOUND: scripts/lib/azure-guard.sh, scripts/check-az-subscription-pin.sh, scripts/azure-staging-provision.sh, .github/workflows/ci-cd.yaml
- FOUND (ancestors of HEAD): 198c89b2, ba267907, 157e957b, cc127deb, 93844d71
- `gsd_run check evaluation-scope --plan 29-01 --commits-only`: status resolved, 5 commits on this branch
- TDD gates: RED `test(29-01)` commits 198c89b2 and 157e957b precede their GREEN `feat(29-01)` commits ba267907 and cc127deb
- `git rev-list --count edc2c94d..HEAD` = 5 at SUMMARY write
