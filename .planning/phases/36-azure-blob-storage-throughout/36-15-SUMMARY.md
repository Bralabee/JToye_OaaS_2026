---
phase: 36-azure-blob-storage-throughout
plan: 15
subsystem: docs
tags: [azure-blob, azurite, documentation, doc-gates, check-doc-versions, agent-context, codebase-map]

requires:
  - phase: 36-01
    provides: Azure Blob SDK rows in check-doc-versions; the storage/ package the docs describe
  - phase: 36-08
    provides: backup runbook on Blob (no in-job prune; WORM + soft delete + lifecycle)
  - phase: 36-10
    provides: k8s docs and secrets template on Workload Identity; flagged sealed-secrets.md:37,175
  - phase: 36-12
    provides: the runtime these docs now describe (Azurite in compose, containers, probe)
provides:
  - Live docs, project instructions and agent-context files that describe Azure Blob Storage + Azurite
  - check-doc-versions Azurite row (actual read from the compose azurite image default)
  - ADR-0002 "Superseded in part (2026-09-28)" note (append-only)
  - FAILURE_MODES F-6.7 (storage is a hard startup dependency)
  - A documented replacement for the retired console (az + UseDevelopmentStorage=true, Storage Explorer)
  - Codebase map (.planning/codebase) that a regeneration will not turn back
  - A line-level residue exception list for 36-16
affects: [36-16, 36-17, phase-29-handoff, jtoye-orgos-registry]

actuals:
  tokens: 27976
  tasks: 3
  commits: 3
plan_head_before: bbb4ecc03024a305f2123686651f620c4448b0ff

tech-stack:
  added: []
  patterns:
    - "A version claim for a digest-pinned image is gated by reading the compose default's tag (the part before @sha256), anchored on the image path"
    - "Historical records get an appended, dated supersession note, never an edited body"

key-files:
  created: []
  modified:
    - CLAUDE.md
    - AGENTS.md
    - HANDOFF.md
    - scripts/check-doc-versions.sh
    - docs/AI_CONTEXT.md
    - docs/HOW_IT_WORKS.md
    - docs/FAILURE_MODES.md
    - docs/CREDITS-demo-images.md
    - docs/architecture/ARCHITECTURE.md
    - docs/architecture/ESSENTIAL_ARCHITECTURE.md
    - docs/architecture/SYSTEM_DESIGN_V2.md
    - docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md
    - docs/guides/DOCKER_QUICK_START.md
    - docs/guides/QA_TEST_PLAN.md
    - docs/runbooks/sealed-secrets.md
    - .cursor/rules/oaas-core-java.mdc
    - .cursor/rules/oaas-platform.mdc
    - .github/instructions/oaas-core-java.instructions.md
    - .github/instructions/oaas-platform.instructions.md
    - .github/chatmodes/oaas-core-java.chatmode.md
    - .github/chatmodes/oaas-platform.chatmode.md
    - .planning/codebase/ARCHITECTURE.md
    - .planning/codebase/CONCERNS.md
    - .planning/codebase/INTEGRATIONS.md
    - .planning/codebase/STACK.md
    - .planning/codebase/STRUCTURE.md
    - .planning/codebase/TESTING.md

key-decisions:
  - "CLAUDE.md/AGENTS.md GSD blocks were hand-edited, not regenerated. A trial generate-claude-md in a scratch worktree rewrote the whole curated stack block (258 diff lines) and wrote to .claude/CLAUDE.md, not the root file. The source (.planning/codebase/STACK.md) was fixed as well, so a later regeneration cannot bring the retired store back."
  - "The AGENTS.md specialist roster and its six mirrors (.cursor, .github) are generated from the external jtoye-orgos registry. They were hand-edited the same way (the #530 precedent). The upstream charters still carry the retired text, so a registry regeneration would revert these lines. That is an open item for the owner; no other repository was touched."
  - "HANDOFF.md: retired-store references reworded without changing any claim. The stale 'Run /gsd-plan-phase 36' resume line now says the phase is planned and in execution and points at ROADMAP. It carries no plan count, so it cannot go stale before 36-17 refreshes the block."
  - "SYSTEM_DESIGN_V2 TARGET sections (§3.2, §7) now name Azure Blob as the WAL/backup target. After D-01 an S3 target would contradict the recorded decision. The current-state notes drop 'S3 prune': since 36-08 the job never prunes."
  - "BLOB-09 is NOT marked complete. requirements.ready-ids reports 0/1, because 36-16 and 36-17 also declare it."

patterns-established:
  - "Residue proof uses a check stricter than the plan's greps: 36-16's full R-1 (including s3-(media|backup)-credentials, AWS_* and the S3-host form) plus a case-sensitive S3_ and the R-2 prose rule"

requirements-completed: []   # plan declares [BLOB-09]; still open for 36-16 (gate) and 36-17 (metrics/handoff)

coverage:
  - id: D1
    description: "CLAUDE.md, AGENTS.md and HANDOFF.md name Azure Blob / Azurite and no retired-store token"
    requirement: "BLOB-09"
    verification:
      - kind: other
        ref: "git grep -n -I -i -E 'minio|awssdk|S3Client|localhost:9000|aws s3|awscli' -- CLAUDE.md AGENTS.md HANDOFF.md (rc=1; planted arm rc=0)"
        status: pass
      - kind: other
        ref: "git grep -n -I -P '(?<!UI-SPEC )\\bS3\\b' -- CLAUDE.md AGENTS.md HANDOFF.md (rc=1; planted arm rc=0)"
        status: pass
    human_judgment: false
  - id: D2
    description: "check-doc-versions enforces the documented Azurite version against the compose image default"
    requirement: "BLOB-09"
    verification:
      - kind: other
        ref: "bash scripts/check-doc-versions.sh (rc=0, 157 claims; arms: CLAUDE 3.36.0 -> rc=1, AGENTS image form 3.36.0 -> rc=1, compose unresolvable -> rc=2)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Ten live docs describe the current storage; the retired console has a documented, live-verified replacement"
    requirement: "BLOB-09"
    verification:
      - kind: other
        ref: "plan Task 2 grep over the 10 docs (rc=1; base bbb4ecc0 = 14 hits; planted arm rc=0) + strict residue check (rc clean apart from ADR-0002:21)"
        status: pass
      - kind: other
        ref: "az storage blob list --connection-string UseDevelopmentStorage=true -c jtoye-images (rc=0, 3 blobs listed; control container rc=3 ContainerNotFound)"
        status: pass
    human_judgment: false
  - id: D4
    description: "ADR-0002 gains an append-only 'Superseded in part (2026-09-28)' note for D-01/D-06"
    verification:
      - kind: other
        ref: "git diff bbb4ecc0 -- ADR-0002: 0 removed lines, 19 added; arm (delete line 21) -> 1 removed line"
        status: pass
    human_judgment: false
  - id: D5
    description: "Agent-context files and the codebase map carry no retired-store token"
    requirement: "BLOB-09"
    verification:
      - kind: other
        ref: "Task 3 greps over .cursor .github/instructions .github/chatmodes .planning/codebase (rc=1 both; planted arms rc=0; UI-SPEC S3 negative control rc=1)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Prose accuracy of the rewritten storage descriptions (a gate checks tokens, not meaning)"
    verification: []
    human_judgment: true
    rationale: "The residue greps prove the retired names are gone. They cannot prove the new sentences are accurate. Each fact was checked against application.yml, docker-compose.full-stack.yml, StorageStartupValidator and the k8s configmaps during authoring, but a reader should still confirm the storage paragraphs read correctly."

duration: 13min
completed: 2026-09-29
status: complete
---

# Phase 36 Plan 15: Live docs on Azure Blob and Azurite Summary

**27 live docs, project-instruction files, agent-context files and codebase-map files now describe Azure Blob Storage (Workload Identity, raw Blob endpoint) and Azurite 3.37.0. check-doc-versions checks the Azurite version against the compose image default. ADR-0002 gains an append-only supersession note. Azurite's missing web console is replaced by a documented, live-verified `az` listing.**

## Performance

- **Duration:** about 13 min (08:31Z to 08:44Z)
- **Started:** 2026-09-29T08:31:47Z
- **Completed:** 2026-09-29T08:44Z
- **Tasks:** 3/3
- **Files modified:** 27 (170 insertions, 78 deletions)

## Accomplishments

- **CLAUDE.md, AGENTS.md:**
  - Key Dependencies now says `Azurite 3.37.0 - Azure Blob emulator (local, hybrid, nightly); Azure Blob Storage in staging/production`.
  - Platform Requirements now says `Azure Blob Storage (Azurite locally)`.
  - The pinned-versions list has `Azurite: 3.37.0`.
  - The Core API "Depends on" line names Azure Blob Storage.
  - The V53 history paragraph says "physical object delete". Only that noun changed.
  - The AGENTS.md specialist roster names `azurite:3.37.0` and reads media back out of Blob storage.
  - The quoted test counts are unchanged; 36-17 owns them.
- **HANDOFF.md:**
  - Seven retired-store references are reworded without changing what they claim. The retired SDK's bump branch and PR #604 are now described by role, not by name.
  - The stale resume line is corrected.
  - check-handoff-contract is still green: the gate count of 45 still holds.
- **scripts/check-doc-versions.sh:** new `Azurite` row.
  - The actual version is read from the `azure-storage/azurite:${AZURITE_IMAGE_TAG:-X@sha256` default. The pattern is anchored on the image path, so a comment cannot feed it, and an operator's tag override is never read.
  - It matches the stack-list form ("Azurite 3.37.0", "Azurite: 3.37.0") and the image form (`azurite:3.37.0`).
  - A three-part version is required, so a port such as `azurite:10000` never matches.
  - The gate now checks 157 claims (was 148). The new claims are 2 in CLAUDE.md, 3 in AGENTS.md, 1 in ESSENTIAL_ARCHITECTURE and 3 in STACK.md: the Blob SDK, Identity and Azurite.
- **Architecture docs:**
  - AI_CONTEXT lists the real `STORAGE_*` env set and Azurite on 10000.
  - HOW_IT_WORKS explains why the public container is not listable (access level `blob`, enforced at boot).
  - ARCHITECTURE and ESSENTIAL_ARCHITECTURE: diagrams, the stateful volume set (`azurite_data`) and the remotePatterns trap now name the raw Blob endpoint.
  - SYSTEM_DESIGN_V2:
    - The §1 as-built diagram and service table name Azure Blob / Azurite. Box widths are preserved.
    - The TARGET designs now name Azure Blob as the WAL/backup store.
    - The current-state backup notes say "no in-job prune; retention is the container's immutability + soft delete + lifecycle rule".
- **FAILURE_MODES:**
  - F-4.1 names the raw Blob endpoint and the Azurite `remotePatterns` origin.
  - New **F-6.7** records the boot-time storage probe: `StorageStartupValidator` at `ApplicationStartedEvent`, and compose `depends_on: azurite: service_healthy`. It says Azurite is a hard startup dependency, and names the recovery steps and the probe's known blind spot (the account-level public-access setting).
- **DOCKER_QUICK_START:**
  - Now lists 13 services (was 14). Measured with `docker compose ... config --services`.
  - Azurite is listed on 10000 with no credential, and the console rows are gone.
  - A new "Inspecting stored images" section gives the `az storage blob list --connection-string "UseDevelopmentStorage=true" -c jtoye-images` command, Azure Storage Explorer, direct URLs, and `check-media-urls-resolve.sh`.
  - The Credentials Reference has an Azurite row that names no key.
- **QA_TEST_PLAN:**
  - Now says 13 services, with azurite and two init/render jobs.
  - A new "Object storage" block runs `check-media-urls-resolve.sh` and `check-media-content-types.sh`. Their expected PASS lines were taken from real runs on the live stack: both rc=0, 23 references, 0 bad Content-Types.
- **sealed-secrets.md:**
  - The backup storage Secret is removed from both the required-secrets table and the overlay `resources:` list.
  - A new paragraph says object storage uses Workload Identity, that there is no Secret to seal, and that the render invariants fail a build that has one. It links runbook §5-§6.
- **CREDITS-demo-images:** the seed images go to the object store (Azurite locally, `jtoye-images`).
- **ADR-0002:** appended "## Superseded in part (2026-09-28)".
  - D-01: the logical dump goes to a separate immutable Azure account in another region, and the job no longer prunes.
  - D-06: media is served from the raw Blob endpoint.
  - It links 36-CONTEXT.md and both runbooks, and says the ADR's status is unchanged.
  - The body is untouched: 0 removed lines, 19 added.
- **Agent-context files and codebase map:**
  - The six `.cursor/.github` files carry the same two edits as the AGENTS.md roster.
  - STACK.md (the upstream of the AGENTS.md stack block) names the Blob SDK 12.35.1, Identity 1.18.6, the Azurite remotePatterns origin and Azure Blob Storage. These are now gated. This closes the 36-01 note about `STACK.md:70`.
  - INTEGRATIONS describes the auth-mode switch, the two containers, the probe, Workload Identity, the raw endpoint and the backup account, and drops the retired credential env vars.
  - ARCHITECTURE, CONCERNS, STRUCTURE (the `storage/` package now has 8 files) and TESTING are updated too.

## Task Commits

1. **Task 1: Project instructions and the doc-version gate:** `0af6d9da` (docs)
2. **Task 2: Architecture docs, guides and live runbooks:** `1183eda2` (docs)
3. **Task 3: Agent-context files and the generated codebase map:** `94cd22d1` (docs)

**Plan metadata:** the commit that adds this SUMMARY (docs(36-15): complete ...)

## Evidence, both directions

Each break arm below was run **after** its task was committed. Each restore was done by `git show HEAD:<file> > <file>` and confirmed by sha256. Every arm set ended with 0 tracked changes.

| Criterion | Real tree | Planted break |
|---|---|---|
| T1 verify: `check-doc-versions` | rc=0, `PASS: all 153 … 7 doc(s)` after T1 (157 after T3) | CLAUDE.md `Azurite 3.36.0`: **rc=1** `DRIFT Azurite doc=3.36.0 actual=3.37.0`. AGENTS.md roster `azurite:3.36.0`: **rc=1** (same DRIFT line). Compose image path broken: **rc=2** `VOID: could not resolve the real version for 'Azurite'`. All restored by hash (a04e8ff1…, fc13539a…, 13bfd786…) |
| T1 verify: `check-handoff-contract` | rc=0 (1 gate-count claim, 28 state claims) | `EXPECT 46 x rc=0`: **rc=1** `H-1 … says 'EXPECT 46 x rc=0' but the repo has 45 gate script(s)` |
| T1 verify: token grep over CLAUDE/AGENTS/HANDOFF | `rc=1`, no lines | a `MinIO` line appended to HANDOFF.md: **rc=0** `HANDOFF.md:810:planted…` |
| T1 AC: `(?<!UI-SPEC )\bS3\b` over the same | `rc=1` | `an S3 bucket` appended to AGENTS.md: **rc=0** `AGENTS.md:762` |
| T1 AC: `check-doc-metrics` | rc=0, `PASS: all 37 prose metric claim(s)`. It is NOT red: the quoted counts still match metrics.json, so 36-17 regenerates both together | CLAUDE.md 4042 changed to 4043: **rc=1** `doc says 4043, docs/metrics.json says 4042` |
| T2 verify: plan grep over the 10 docs | `rc=1`, no lines | at base `bbb4ecc0`: **rc=0, 14 hits** (positive control, listed below). A MinIO row appended to FAILURE_MODES: **rc=0** |
| T2 AC: ADR added-lines-only | `git diff bbb4ecc0 -- ADR`: removed=0, added=19. `Superseded in part (2026-09-28)` found once (l.80) | line 21 deleted: removed=**1** |
| T2 AC: `UseDevelopmentStorage=true` in quick start | 2 lines (68, 348) | n/a (presence check) |
| T2 AC: `git grep AccountKey -- docs` | `rc=1` | `AccountKey=planted` appended: **rc=0** |
| T2 AC: `check-doc-citations` | rc=1, **the same 5 violations as baseline** (diffed: identical), all in `.planning/codebase` (STACK.md:23, :35, :51, :69; INTEGRATIONS.md:14). None fixed and none added | a bad `core-java/build.gradle.kts:1` citation added to STACK.md:70: **violations 6** |
| T2 AC: `check-claims` | rc=0, 47 claims | AGENTS.md schema V66 changed to V65: **rc=1** `claims '65', metrics says '66'` |
| T3 verify: token grep over .cursor/.github/.planning/codebase | `rc=1` | a `minio` line appended to a chatmode: **rc=0** |
| T3 AC: R-2 prose over the same | `rc=1` | `an S3 bucket` appended: **rc=0**. Negative control `UI-SPEC S3` appended: **rc=1** (not matched, correct) |
| STACK.md SDK claim now gated | rc=0 | `Blob SDK 12.35.0`: **rc=1** `DRIFT Azure Storage Blob SDK doc=12.35.0 actual=12.35.1` |
| `check-no-measured-placeholders` (scans no file this plan edits) | rc=0 | a placeholder appended to .env.example: **rc=1** |
| `check-gate-enforcement` | rc=0 (44 gates, 7 workflows) | not armed: no gate was added or wired |
| Displaced console replacement works | `az storage blob list … -c jtoye-images` against the running Azurite: **rc=0**, 3 blobs listed | control container `no-such-container-xyz`: **rc=3** `ErrorCode:ContainerNotFound` |

Positive control for the whole plan (the residue check before any edit): 86 hit lines across the 26 target files. The pre-edit plan greps found 53 R-1 lines and 30 R-2 lines. After the edits, the stricter check finds exactly **one** hit across all 27 files: `ADR-0002:21`, which is historical text left there on purpose.

## Files Created/Modified

See `key-files.modified` (27 files). No file created, none deleted (`git diff --diff-filter=D` = 0).

## Decisions Made

See `key-decisions` above. The two that matter downstream:
1. **Hand-edit instead of regenerate.** GSD's `generate-claude-md` would replace the curated blocks. Fixing STACK.md is what stops a regeneration from bringing the retired store back.
2. **The jtoye-orgos registry is upstream of seven files.** It still says the retired store at `charters/oaas-core-java.md:46` and `charters/oaas-platform.md:18` (read-only check; that repository was not touched).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Unfalsifiable criterion] Task 2's verify grep cannot see the retired Secret names**
- **Found during:** Task 2
- **Issue:** The plan's Task 2 pattern lacks `s3-(media|backup)-credentials`. At base it reports 0 hits for `docs/runbooks/sealed-secrets.md`, although lines 37 and 175 named the retired backup Secret, which is exactly what the task says to remove. A planted `s3-media-credentials` row gives **rc=1** with the plan grep (blind) and **rc=1 with a hit printed** with the stricter check.
- **Fix:** Every task was also verified with a stricter check: 36-16's full R-1 token set plus case-sensitive `S3_` and R-2. The plan's own grep was kept and recorded too.
- **Files modified:** none (verification only). The sealed-secrets lines were rewritten in Task 2.
- **Commit:** 1183eda2

**2. [Rule 2 - Accuracy] Service counts and job counts corrected with the storage rows**
- **Found during:** Task 2
- **Issue:** Removing the retired store's service and its init job made DOCKER_QUICK_START's "14 services" and QA_TEST_PLAN's "14 services … three *-init / *-render jobs" false.
- **Fix:** Changed to 13 services and two jobs, measured with `docker compose -f docker-compose.full-stack.yml config --services`. No gate reads these numbers (checked `scripts/gates/claims.manifest`).
- **Commit:** 1183eda2

**3. [Rule 2 - Accuracy] SYSTEM_DESIGN_V2 and HANDOFF.md claims that the phase itself made false**
- **Found during:** Tasks 1 and 2
- **Issue:** SYSTEM_DESIGN_V2's current-state backup notes said "S3 prune", but the job has not pruned since 36-08. HANDOFF.md's resume line told the reader to run `/gsd-plan-phase 36`, but the phase has been planned and is executing.
- **Fix:** Both reworded to the current truth, with no count that could go stale.
- **Commits:** 0af6d9da, 1183eda2

---

**Total deviations:** 3 (1 unfalsifiable criterion strengthened, 2 accuracy corrections the rewrite required). **Impact:** no scope creep. Every edit is inside the plan's 27 files.

## Issues Encountered

- Trying `generate-claude-md` (in a scratch worktree, which was removed afterwards) showed it is not how this repo maintains CLAUDE.md. See Decisions.

## Exception list for 36-16 (line/region level, measured on HEAD after 94cd22d1)

Measured with 36-16's R-1/R-2 over `git ls-files` outside `.planning/`. Each line below is still a hit.

**A. Historical records: exempt by path, as 36-16 already plans.** Hit counts:
- docs/audit: 04-devops-remediation 30, 04-devops-sre 11, 02-security-engineer 4, 05-frontend-remediation 4, REMEDIATION-PLAN-2026-04-27 3, 02-security-remediation 3, COUNCIL-AUDIT-2026-04-27 2, 05-frontend-ux 2, 07-edge-absorb-remediation 2, 03-database-remediation 1, 06-qa-remediation 1, 08-market-analyst 1
- docs/archive: backups-rehearsal-evidence-2026-07 13, HANDOFF-history-through-2026-08-17 4
- docs/analysis: REMEDIATION-BACKLOG-2026-07-08 6, ENTERPRISE_STRATEGIC_ANALYSIS 1
- docs/planning: PLATFORM-STATE-AND-POSITIONING-2026-04-19 5, SESSION-HANDOFF-2026-04-21 1, STOREFRONT_PLAN 1
- docs/reports: PRODUCTION_READINESS_REPORT 1
- Other historical files: docs/CHANGELOG.md 28, docs/security/MEDIA-BACKFILL-PLAN-2026-08-10.md 4

**B. Single lines in live files that are deliberately historical. Allow each line, not the file:**
- `docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md:21`: the original option text "archiving to Blob/S3". The rest of the file is clean. The note at l.80 carries the supersession.
- `core-java/src/main/resources/db/migration/V42__gdpr_erasure_completeness.sql:6`: an applied migration (changing it changes the checksum).
- `docs/runbooks/azure-blob-provisioning.md:20` (Supersedes Phase 29 D-11/D-12, "AWS S3") and `:196` (the removed `aws s3 rm` prune): both record what was superseded.
- `k8s/LOCAL.md` (36-10's region-aware case):
  - `:139`: the stale-Secret delete instruction. The same line exists at `.env.example:488`.
  - Fenced Phase 26 records under the pre-Phase-36 banner: `:849, :1079, :1082, :1105, :1106, :1380, :1381, :1388, :1389, :1403, :1435, :1833, :2009`. That is 13 lines; 36-10 counted 9 with a narrower pattern. This check also catches `s3://` at 1380-1381, `aws-cli` at 1435 and R-2 `S3` at 1388.
- `infra/backups/blobctl/config_test.go:36`: the test-case name `"retired S3 vocabulary"`, a deliberate negative test.
- `infra/dependency-horizons.yaml:204` and `scripts/check-media-urls-resolve.sh:9, :22, :91`: they name the retired origin because they reject it. `RETIRED_ORIGIN="http://localhost:9000/"` is functional.
- `scripts/dev-media-reseed.sh:6, :12, :71, :105`: the one-off reseed tool's old-origin default is functional.
- `k8s/base/networkpolicies/20-core-java.yaml:119`, `40-datastores.yaml:77`, `k8s/scripts/check-render-invariants.sh:161, :421`, `k8s/scripts/check-env-contract.sh:105`: each is a "removed in Phase 36" note that names what was removed.

**C. R-2 false positives: "S3" is a step or section label, not the store.** Either narrow R-2 or allow these lines. The `UI-SPEC S3` lookbehind does not cover them:
- `frontend/components/storefront/order-allergen-panel.tsx:10`, `frontend/components/storefront/__tests__/order-allergen-panel.a11y.test.tsx:2, :100`, `frontend/app/shop/[slug]/checkout/__tests__/allergen-acknowledgement.test.tsx:2`, `frontend/types/api.ts:268`: the Phase 31-14 "S3" section label.
- `frontend/e2e/customer-signout-idp-session.verify.mjs:21, :22, :36, :72, :180, :220, :231, :240, :256`: verification step "S3".

**D. Still live residue. Not historical, not in 36-15's file list:**
- Already in 36-16 Task 1's files: `scripts/seed-media-review-fixtures.sh:54`, `scripts/check-dependency-horizons.sh:63, :212`, `scripts/check-no-measured-placeholders.sh:11`, `infra/load-testing/baseline.sh:38`, `infra/load-testing/media-pipeline-arm.sh:20`, `.github/dependabot.yml:20`, `.gitignore:153`.
- In 36-16 Task 3: `.github/workflows/ci-cd.yaml:412, :636` (the plan said ~399 and ~623, which have since shifted).
- **In no plan's file list:**
  - `.env.example:192` (R-2 "S3-prefixed …")
  - `k8s/scripts/check-env-contract.sh:17` (a comment: "dev MinIO endpoint")
  - `k8s/base/networkpolicies/10-frontend.yaml:11, :60` and `k8s/base/networkpolicies/README.md:15` ("S3 public URLs"; already logged in deferred-items by 36-09)
  - `scripts/restore-drill.sh:57, :290` ("artifact from S3", "real S3 artifact")
  - `scripts/gates/postgres-major-parity.conf:22` (`apk add aws-cli` in a dated incident comment)
  - `infra/monitoring/README.md:234` ("Export to S3/GCS via Thanos", generic advice)
  - `k8s/PRODUCTION_READINESS_REPORT.md:626, :631, :634`: the 2026-01-16 report carries a historical banner, but this Phase 26 pre-activation section still names the retired media Secret. Allow the whole file only if it is classed as historical.
- 36-16 Task 1 says a comment-only miss may be fixed there. `restore-drill.sh:290` is an `echo`, so it is user-visible output, but changing it is still a text edit.

## Open items

- **jtoye-orgos registry (outside this repo):** `charters/oaas-core-java.md:46` and `charters/oaas-platform.md:18` still name the retired store. Regenerating the roster would revert AGENTS.md's ORGOS block and the six `.cursor/.github` mirrors. The owner should update the registry.
- **check-doc-citations:** still the 5 pre-existing `.planning/codebase` violations. This plan neither fixed nor added any.
- **Inherited reds, not touched:** `scripts/docs-freshness.sh` and the Playwright count oracle (128 vs 127) are both owned by 36-17.
- **BLOB-09 stays open:** it closes after 36-16 (the gate) and 36-17 (metrics and the handoff).

## User Setup Required

None.

## Next Phase Readiness

Ready for 36-16. Every live file 36-15 owns is clean under 36-16's full pattern set, apart from ADR-0002:21. The exception list above tells the gate's allowlist exactly which lines to allow, so no whole live file needs to be allowlisted.

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-29*

## Self-Check: PASSED

- Commits found: 0af6d9da, 1183eda2, 94cd22d1 (`git cat-file -e`)
- All 27 modified files exist; 0 deletions in `bbb4ecc0..HEAD`
- `git rev-list --count bbb4ecc0..HEAD` = 3 before this SUMMARY commit
- Closing gate run: doc-versions 0 (157), doc-metrics 0, claims 0, placeholders 0, handoff-contract 0, gate-enforcement 0, doc-citations 1 (baseline 5, unchanged)
