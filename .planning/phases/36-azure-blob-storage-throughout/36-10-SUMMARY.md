---
phase: 36-azure-blob-storage-throughout
plan: 10
subsystem: infra
tags: [k8s, k8s-local, azurite, workload-identity, secrets-template, runbooks, dependency-horizons, env-contract, D-02]
status: complete

requires:
  - phase: 36-azure-blob-storage-throughout
    provides: "36-09 k8s/local renders the Azurite emulator path from local-only ConfigMap keys; ServiceAccounts core-java/pg-backup and the WI label in staging/production; INV-8/9/10 + LOC-7. 36-08 blobctl creates the backup container PRIVATE in emulator mode. 36-05 the provisioning runbook. 36-02 Azurite in compose on JTOYE_BIND_HOST."
provides:
  - "scripts/k8s-local-secrets.sh with no object-store step, no client-image pull and no object-store credential Secrets"
  - "K8S_LOCAL_BACKING_SERVICES naming azurite; env key K8S_LOCAL_AZURITE_PORT (default 10000); K8S_LOCAL_BACKUP_BUCKET and the retired port key removed"
  - "k8s-local-up.sh STEP 5 probes the Azurite port; its unreachable-port refusal names the loopback JTOYE_BIND_HOST default (#441)"
  - "Retired client-image horizons row removed with its last site (rows 29 -> 28)"
  - "secrets-template.yaml.example, DEPLOYMENT.md, QUICK_START.md: no storage credential Secret; Workload Identity + the client-id annotation Phase 29 adds + the provisioning runbook"
  - "LOCAL.md operative sections on Azurite; Phase 26 records kept verbatim under a pre-Phase-36 banner"
  - "Local-context-only delete command for the two stale Secrets a pre-Phase-36 bootstrap left behind (.env.example and LOCAL.md)"
affects: [36-15, 36-16, 36-17, phase-29-provisioning]

actuals:
  tokens: 14791        # chars/4 over git diff de3f507f..ecbdb724 (the two task commits)
  tasks: 2
  commits: 2           # MEASURED: git rev-list --count de3f507f..HEAD before the SUMMARY commit
plan_head_before: de3f507ffa39c2b171d136f2a61ed479d82e9dcc

tech-stack:
  added: []
  patterns:
    - "A doc that must keep verbatim evidence naming a retired token is checked region-aware (fence + dated section), not by a whole-file grep that the evidence makes red by construction"
    - "An image pin's horizons row leaves in the same commit as its last site; H-5 VOIDs (rc=2) if the row outlives it"

key-files:
  created: []
  modified:
    - scripts/k8s-local-secrets.sh
    - scripts/lib/k8s-local-guards.sh
    - scripts/k8s-local-up.sh
    - .env.example
    - infra/dependency-horizons.yaml
    - scripts/restore-drill.sh
    - k8s/base/secrets-template.yaml.example
    - k8s/LOCAL.md
    - k8s/DEPLOYMENT.md
    - k8s/QUICK_START.md

key-decisions:
  - "No compose bind change. 36-09 said 36-10 must change Azurite's 127.0.0.1 bind so minikube can reach it. That premise is wrong: since #441 EVERY backing service (postgres, redis, rabbitmq, keycloak, mailhog, azurite) publishes on JTOYE_BIND_HOST, loopback by default, so Azurite is not a special case. Widening Azurite alone would add exposure and still leave the rehearsal broken. The operator sets a non-loopback JTOYE_BIND_HOST for a rehearsal (documented in LOCAL.md §3 and §8, .env.example, and the k8s-local-up.sh STEP 5 refusal). check-infra-exposure stays green and fails while that opt-in is set (shown). LOCAL.md §8's 'every compose port already publishes that way [0.0.0.0]' had been stale since #441 and is corrected."
  - "The stale-Secret cleanup names the two retired Secrets explicitly (in .env.example and LOCAL.md), although the plan's retired-token greps forbid those names. An operator needs the exact names, and a brace-expansion spelling that dodged the grep would also hide the instruction from anyone searching for it. Each criterion was replaced with a stricter form that allows exactly that one instruction line (Deviations 1 and 2)."
  - "Verbatim Phase 26 evidence in LOCAL.md keeps the retired names inside its fences, as the plan asks. That makes the plan's whole-file LOCAL.md grep red by construction. Its prose outside fences was reworded, both historical regions carry a banner, and a region-aware check replaces the grep."
  - "BLOB-07 marked complete: 36-09 delivered the render/config half and this plan the tooling/docs half. BLOB-09 NOT marked: the residue gate (36-16) and the docs/metrics work (36-15/36-17) are still open."

patterns-established:
  - "k8s/local bootstrap creates only what the render consumes; storage needs neither a bucket step nor a credential"

requirements-completed: [BLOB-07]   # plan declares [BLOB-07, BLOB-09]; BLOB-09 stays open for 36-15/36-16/36-17

coverage:
  - id: D1
    description: "k8s/local bootstrap pulls no retired client image, creates no bucket and applies no object-store credential Secret; the dump-role bootstrap is unchanged"
    requirement: BLOB-07
    verification:
      - kind: other
        ref: "git grep retired tokens over the 5 Task 1 files: only the .env.example delete-instruction line (refined form: non-delete hits 0); arm planting K8S_LOCAL_MINIO_PORT in the guard -> rc=0 hit; create-backup-role.sql still cited 3x; bash -n rc=0 x4 (broken copy rc=2)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Backing-service guard names azurite and requires K8S_LOCAL_AZURITE_PORT; the backup-bucket key is gone"
    requirement: BLOB-07
    verification:
      - kind: other
        ref: "k8s_local_load_env on scratch env: key unset -> rc=1 naming K8S_LOCAL_AZURITE_PORT; set 10000 -> rc=0 (12 keys); old-style env (MINIO port + bucket) -> rc=1. compose_xor fixture: azurite running -> rc=0; azurite exited -> rc=1 naming azurite; only the retired service running -> rc=1 naming azurite; live compose state lists 'azurite running'"
        status: pass
    human_judgment: false
  - id: D3
    description: "Retired client-image horizons row removed with its last site; horizons gate green"
    requirement: BLOB-09
    verification:
      - kind: other
        ref: "conda run -n jtoye-ops scripts/check-dependency-horizons.sh rc=0 (rows 28); arm re-adding the row -> rc=2 VOID, H-5 site-unresolvable=1 naming scripts/k8s-local-secrets.sh:299"
        status: pass
    human_judgment: false
  - id: D4
    description: "Secrets template and the three k8s runbooks describe Workload Identity (staging/production) and the Azurite emulator (local), with no instruction to create a storage credential Secret"
    requirement: BLOB-07
    verification:
      - kind: other
        ref: "/tmp/claude-36-10/retired-check.sh (region-aware) rc=0; arms: operative prose rc=1, fenced line in §9 rc=1, QUICK_START rc=1, template rc=1, second delete line rc=1, missing region marker rc=2"
        status: pass
      - kind: other
        ref: "awk azure-blob-provisioning count in DEPLOYMENT.md = 1 (copy without the link = 0); check-no-plaintext-secrets rc=0 (arm adding a Secret resource to base -> rc=1); value-shaped key/SAS grep over k8s rc=1 (planted AccountKey -> rc=0); gitleaks dir k8s no leaks (planted -> 1 leak)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The live k8s/local rehearsal with these scripts (minikube reaching the host Azurite, a CronJob upload via blobctl)"
    requirement: BLOB-07
    verification: []
    human_judgment: true
    rationale: "Recorded N/A by the plan: compose and a local cluster are XOR at runtime and the compose stack must stay up for the end-of-phase check. The scripts were exercised statically and at function level only; the first real run also needs JTOYE_BIND_HOST rebound and the operator's .env migrated."

duration: 11min
completed: 2026-09-29
---

# Phase 36 Plan 10: k8s/local Tooling and k8s Docs on Azurite + Workload Identity Summary

**The k8s/local bootstrap no longer touches the retired object store: no client-image pull, no bucket, no object-store credential Secrets. Its guards require `azurite` and `K8S_LOCAL_AZURITE_PORT=10000`, and the retired client-image horizons row went with its last site. The secrets template, DEPLOYMENT.md and QUICK_START.md now send operators to Workload Identity and the provisioning runbook, not to a storage Secret. LOCAL.md describes the Azurite path and keeps the Phase 26 evidence verbatim under a pre-Phase-36 banner.**

## Performance

- **Duration:** 11 min
- **Started:** 2026-09-29T08:17:35Z
- **Completed:** 2026-09-29T08:28:30Z
- **Tasks:** 2
- **Files modified:** 10 (none created)

## Accomplishments

- `scripts/k8s-local-secrets.sh`:
  - dropped the two object-store root-credential names from the value preflight;
  - deleted STEP 4 (container lookup, port resolution, the `docker run` of the retired client image) and put a comment in its place. The comment explains that blobctl creates `jtoye-db-backups` PRIVATE on the first emulator-mode upload (`commands.go:124-128`, `EnsurePrivateContainer`, no public-access header) and that the emulator string needs no credential;
  - removed both object-store credential Secrets from the apply list and the summary;
  - updated the header and banners. The dump-role bootstrap is unchanged.
- `scripts/lib/k8s-local-guards.sh`: `K8S_LOCAL_BACKING_SERVICES="postgres redis rabbitmq keycloak azurite mailhog"`; `K8S_LOCAL_AZURITE_PORT` replaces the retired port key; `K8S_LOCAL_BACKUP_BUCKET` is gone (12 required keys).
- `scripts/k8s-local-up.sh`:
  - STEP 5 probes `$K8S_LOCAL_AZURITE_PORT`;
  - the STEP 8 header, banner and step list read "secrets and dump role";
  - the unreachable-port refusal now names the loopback `JTOYE_BIND_HOST` default (#441) as the first host-side cause, ahead of the firewall.
- `.env.example`: `K8S_LOCAL_AZURITE_PORT=10000`; the published-ports comment names Azurite 10000 Blob; new REACHABILITY and MIGRATION notes; a local-context-only `kubectl --context jtoye -n jtoye-local delete secret … --ignore-not-found` for the two stale Secrets.
- `infra/dependency-horizons.yaml`: the retired client-image row is removed (35 lines). The header's slug-measurement line no longer names the product.
- `k8s/base/secrets-template.yaml.example`: the two object-store Secret templates and their index lines are removed. The header gains "OBJECT STORAGE HAS NO SECRET, BY DESIGN" covering Workload Identity via ServiceAccounts core-java/pg-backup, INV-9, the provisioning runbook, and the key-less local emulator string.
- `k8s/DEPLOYMENT.md`:
  - the storage Secret is removed from the required list and from the Option A commands;
  - new subsection "Object storage: Workload Identity (no Secret)". It covers what the manifests carry, the `azure.workload.identity/client-id` annotation Phase 29 adds (media identity on core-java, backup identity on pg-backup), the runbook link, the webhook flags, and INV-8/9/10;
  - the gate-table row states the current INV-4 literals and INV-8..10.
- `k8s/QUICK_START.md`: both storage Secret commands are removed; a Step 1 note gives the WI model, the annotation and the runbook link; "all four" optional refs is corrected to "all three" (smtp, notification, stripe — measured in core-java-deployment.yaml).
- `k8s/LOCAL.md`:
  - §2 backing set now names azurite;
  - §3 env block, .env migration, stale-Secret delete, and the bind-host reachability note;
  - §4 step 8;
  - §5: the 8 shims (the two connection strings replace the retired endpoints), the `storage.blob.public-url` split horizon, the auth-mode row, and why `--disableProductStyleUrl` is load-bearing;
  - §6 the port list (10000 in, 9000 out);
  - §8 troubleshooting: the stale "every compose port already publishes on 0.0.0.0" is corrected, and a 10000 probe is added;
  - §9 blobctl, the private container, and a read-only `az storage blob list` confirmation. The `az` command was run against the live Azurite and works: `jtoye-db-backups None`, `jtoye-images blob`.
- `scripts/restore-drill.sh:172`: the citation `k8s-local-secrets.sh:257` is now `:259`, because Task 1's edits shifted the line.

## Task Commits

1. **Task 1: local bootstrap, guard, env keys, horizons row** — `9cf922d9` (feat)
2. **Task 2: secrets template and k8s runbooks** — `ecbdb724` (docs)

**Plan metadata:** the docs commit that adds this file.

## Verification evidence (both directions)

Break arms ran against the committed tree. Every real-file arm was restored with `git show HEAD:<path> > <path>` and verified `git hash-object` == `git rev-parse HEAD:<path>`, and the run went clean-open 0, then the arms, then clean-close 0 (`git diff --quiet HEAD`). Document arms ran on fixture copies in `/tmp/claude-36-10/fx`.

| Criterion | Fail direction (break) | Pass direction (real tree) |
|---|---|---|
| T1 verify: `bash -n` on the three scripts (+ restore-drill.sh) | copy with `if then fi` appended: rc=2 | rc=0 x4 |
| T1 verify: `k8s-local-up.sh --help` | `--bogus`: rc=2 | rc=0, prints USAGE; no cluster call |
| T1 verify: horizons gate | row re-added while its site is gone: **rc=2 VOID**, `H-5 minio-mc: declared pin … NOT FOUND … (declared site scripts/k8s-local-secrets.sh:299)`, site-unresolvable=1 | rc=0, rows=28, H-6 UNKNOWN 7, H-5 NOTE 2 (baseline: rows=29, UNKNOWN 8, NOTE 2) |
| T1 verify: retired-token grep over the 5 files (plan literal) | guard with `K8S_LOCAL_MINIO_PORT` planted: rc=0, hit at guards.sh:134 | **rc=0, 1 hit:** `.env.example:488`, the delete instruction the plan's action requires. See Deviation 1. Refined form (hits other than that line): **0** |
| T1 AC: `azurite` in the guard's BACKING line | (the retired service in a fixture state no longer satisfies the guard, below) | guards.sh:65 printed |
| T1 AC: guard refuses with the port key unset | scratch env without the key: rc=1, `MISSING: K8S_LOCAL_AZURITE_PORT …`, `REFUSED [env-contract]`; old-style env (retired port + bucket keys): rc=1, same key | key=10000: rc=0, `all 12 K8S_LOCAL_* keys present` |
| T1 (extra): backing-service arm of compose XOR | fixture with azurite `exited`: rc=1 `not running: azurite`; fixture with only the retired service running: rc=1 `not running: azurite` | fixture all up: rc=0; live compose state (read-only) lists `azurite running` |
| T1 AC: role bootstrap kept | — (criterion is a presence check; its negative is a deletion the plan forbids) | `create-backup-role.sql` at secrets.sh:8, :198, :247 |
| T2 verify: retired-token grep over template + 3 docs (plan literal) | fixture copy with `The pods write to MinIO` in LOCAL.md §5: FAIL | **rc=0, 10 hits, all in LOCAL.md:** 9 inside fenced Phase 26 records (lines 849, 1079, 1082, 1105, 1106, 1389, 1403, 1833, 2009) plus the §3 delete instruction (139). Template, DEPLOYMENT and QUICK_START have 0. See Deviation 2 |
| T2 verify (replacement): region-aware check `/tmp/claude-36-10/retired-check.sh` | operative prose rc=1; fenced line in §9 rc=1; QUICK_START `s3-backup-credentials` rc=1; template `s3.backup.endpoint` rc=1; a second delete line rc=1; region marker renamed rc=2 VOID | rc=0: `historical-fenced=9 delete-instruction=1 other=0`; unmodified fixture copy rc=0 |
| T2 verify: check-no-plaintext-secrets | Secret `arm-storage-creds` added to k8s/base resources: rc=1 `FAIL [k8s/base] … Secret: arm-storage-creds` | rc=0 |
| T2 AC: `azure-blob-provisioning` in DEPLOYMENT.md >= 1 | copy without those lines: 0 | 1 |
| T2 AC: `git grep -E 'AccountKey\|SharedAccessSignature' -- k8s` prints only rc=1 | — | **rc=0 at the plan base de3f507f already** (3 lines in check-render-invariants.sh, the gate naming the tokens it forbids). My first DEPLOYMENT.md draft added a 4th, which I reworded. Scoped to exclude that gate: rc=1. See Deviation 3 |
| T2 AC (replacement): value-shaped key/SAS over k8s | planted `AccountKey=<64 random base64>` in QUICK_START: rc=0, 1 hit; gitleaks dir k8s: `leaks found: 1` rc=1 | rc=1; gitleaks `no leaks found` rc=0 |
| Exposure (override 2) | `JTOYE_BIND_HOST=0.0.0.0` (process env only): rc=1, `azurite 0.0.0.0 10000 ALL INTERFACES`, 18 non-exempt ports | `--static` rc=0, azurite `127.0.0.1 10000 loopback`, 22 ports |

**Closing sweep (committed tree):**
- rc=0:
  - bash -n x4
  - k8s-local-up `--help`
  - horizons (conda jtoye-ops)
  - infra-exposure `--static`
  - verify-env on the real `.env` (0 FAIL lines)
  - env-example-contract (18 names)
  - no-plaintext-secrets
  - render-invariants (INV-1..10, LOC-1..7)
  - render-golden
  - k8s env-contract
  - connection-math
  - gate-enforcement
  - branch-behind-base (0 behind origin/main)
  - runtime-freshness (`4 running built service(s) match the source tree (0 unverified)`)
  - check-doc-metrics
  - region-aware retired check
- rc=1:
  - check-doc-citations: the same 5 pre-existing `.planning/codebase` failures as 36-09.
  - docs-freshness: inherited `playwright_blocks 127 vs 128`, `playwright_specs 27 vs 28`, owned by 36-17. None of this plan's files is a Playwright file.

**Runtime untouched, no cluster:** every compose container's `StartedAt` predates plan start (08:17:35Z); azurite 08:09:21Z, the rest 07:20-07:58Z. Azurite is still published on `127.0.0.1:10000`. `minikube status -p jtoye` finds no node container. No `kubectl` call was made against any context, and `k8s-local-secrets.sh` was never executed: it has no `--help`, and running it is the mutating bootstrap.

**Build inputs:** none of the 10 files is a core-java or frontend build input; runtime-freshness is green.

## Decisions Made

See `key-decisions`. The one that changes 36-09's hand-off is the bind: no compose change is needed or safe. The rehearsal requirement is the pre-existing `JTOYE_BIND_HOST` opt-in for the whole backing set, and it is now documented where an operator hits it.

## Deviations from Plan

### Criteria corrected (plan internally inconsistent)

**1. [Criterion vs action] Task 1 retired-token grep forbids the delete instruction the action requires**
- **Found during:** Task 1
- **Issue:** The action tells `.env.example` to give the `kubectl … delete secret` command for the two object-store credential names. The verify grep `s3-(media|backup)-credentials` over `.env.example` must then print rc=0.
- **Fix:** The names are kept explicit. The criterion was run as written (rc=0, exactly that one line) and replaced by "no hit other than the local-context delete instruction" (0). Fail direction: a planted key in the guard is caught.
- **Files modified:** none beyond the plan's own.
- **Commit:** 9cf922d9

**2. [Criterion vs action] Task 2 whole-file grep over LOCAL.md is red by construction**
- **Found during:** Task 2
- **Issue:** The action keeps dated rehearsal evidence verbatim and requires the stale-Secret cleanup note. Both necessarily contain the forbidden tokens.
- **Fix:** Prose outside fences was reworded. Both historical regions (§10 "Phase 26 end state", §11) got a pre-Phase-36 banner. The grep was replaced by a region-aware check that allows only (a) fenced lines between `### Phase 26 end state` and `## Related documents`, and (b) the single delete-instruction line. It is falsified six ways, including a VOID arm.
- **Commit:** ecbdb724

**3. [Unfalsifiable criterion] `AccountKey|SharedAccessSignature -- k8s` was already red on the correct tree**
- **Found during:** Task 2
- **Issue:** At the plan base, `k8s/scripts/check-render-invariants.sh` names `AccountKey=` three times. That is INV-4's own forbidden-literal list, so the gate fires on its own definition, and "prints only rc=1" could never hold. My first DEPLOYMENT.md wording added a fourth mention (a description, not a key). I reworded it so the plan adds no mention.
- **Fix:** Recorded both. Added the stronger value-shaped form (`AccountKey=[A-Za-z0-9+/]{20,}`, `SharedAccessSignature=…`, `[?&]sig=…`) plus gitleaks, each shown failing on a planted random key and passing on the tree.

**4. [Rule 1 - Bug] Citation shifted by this plan's own edit**
- **Issue:** `scripts/restore-drill.sh:172` cited `k8s-local-secrets.sh:257` for the `rolbypassrls` check, which Task 1 moved to 259. check-doc-citations does not scan this citation, so no gate would have caught it.
- **Fix:** Changed to `:259` (verified against the file), in commit 9cf922d9. restore-drill.sh is outside `files_modified`; this is a one-token correction.

**5. [Scope clarification] "each script's --help"**
- `k8s-local-secrets.sh` has no flag parser, and `k8s-local-guards.sh` is a source-only library. Running the former is the mutating bootstrap, and once `.env` is migrated it reaches for `kubectl`. Only `k8s-local-up.sh --help` was run (rc=0, plus the `--bogus` rc=2 arm). The guard was exercised function-level instead.

**6. [Additive] Beyond the plan text**
- the k8s-local-up STEP 5 refusal names `JTOYE_BIND_HOST`;
- LOCAL.md §8's stale 0.0.0.0 claim is corrected, with a 10000 probe added;
- QUICK_START "all four" optional refs is corrected to "all three";
- LOCAL.md §9 has a working, live-verified `az` read-only listing.

All of these are text in this plan's files.

---

**Total deviations:** 3 criteria corrected (plan-internal inconsistency or unfalsifiable), 1 Rule-1 fix, 1 scope clarification, 1 additive group. **Impact:** no scope creep. The tokens the plan wanted gone are gone from every operative line. The remaining hits are verbatim evidence and one required instruction line, each allowlisted by line shape, not by file.

## Issues Encountered

- Twice an rc was read through a pipe (`| head`, `| cut`), which reports the filter's status. The printed output was right both times, but the rc was not evidence, so both arms were re-run with `out=$(cmd); rc=$?`. The table shows the re-run values.
- The first hit locator used `gawk`, which is absent. It ran on mawk after dropping `IGNORECASE` (the pattern is lower-case against `tolower($0)`).

## Known Stubs

None.

## Threat Flags

None. T-36-37 is mitigated: the bootstrap no longer creates the stale Secrets, and a local-context-only delete is documented in two places. T-36-38 is mitigated: the client image and its horizons row are gone, and the H-5 arm proves the row cannot outlive its site. T-36-39 is mitigated: no doc instructs creating a storage Secret, and INV-9 enforces it. The exposure surface is unchanged: Azurite stays loopback, and the documented opt-in trips check-infra-exposure (shown).

## User Setup Required

Only before the next k8s/local rehearsal, not for compose:
- In `.env`, rename `K8S_LOCAL_MINIO_PORT` to `K8S_LOCAL_AZURITE_PORT=10000` and delete `K8S_LOCAL_BACKUP_BUCKET`. The guard refuses by name until you do; the real `.env` was measured refusing, reported by name only.
- Set a non-loopback `JTOYE_BIND_HOST` for the rehearsal, recreate the backing services, and revert afterwards.
- On an existing cluster, run the local-context delete of the two stale Secrets.

## Open Items

- **36-16 (residue gate):** k8s/LOCAL.md holds 9 fenced historical hits plus one delete-instruction line, and `.env.example` holds that line too. A path-prefix allowlist would have to take all of LOCAL.md, which blinds the gate to its operative sections. Prefer a line-shape or region-aware allowance; `/tmp/claude-36-10/retired-check.sh`'s logic is the model. Also outside this plan: `docs/runbooks/sealed-secrets.md:37,175` and `k8s/PRODUCTION_READINESS_REPORT.md:631` still name the retired Secrets (36-15/36-16 scope).
- **Live rehearsal N/A** (D5, human_judgment): compose XOR minikube. The first real run proves minikube reaching the host Azurite and a blobctl upload.
- Inherited reds not touched: docs-freshness / Playwright counts (36-17); check-doc-citations' 5 `.planning/codebase` failures.

## Next Phase Readiness

BLOB-07 is complete. k8s/local tooling now matches what k8s/local renders. BLOB-09 continues in 36-15/36-16/36-17.

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-29*

## Self-Check: PASSED

- All 10 modified files are present. Commits 9cf922d9 and ecbdb724 are on phase-36-azure-blob-storage. The closing sweep is green apart from the two inherited reds. Before this file was written, `git diff --quiet HEAD` returned 0.
