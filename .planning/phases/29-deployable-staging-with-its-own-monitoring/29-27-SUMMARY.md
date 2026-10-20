---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 27
subsystem: docs
tags: [dply-02, dispositions, runbook, issue-hygiene, supersede-notes, owner-approval, manifest-hash]
status: complete   # Tasks 1-3 of THIS plan done; the issue state changes the table authorises belong to 29-28 and are NOT done (see "What is not done")

requires:
  - phase: 29-25
    provides: evidence/29-25-dply03.txt (the #98 and #112 evidence lines), deferred-items entries
  - phase: 29-26
    provides: evidence/29-26-config.txt (#299, #592, #859)
  - phase: 29-20
    provides: evidence/29-20-pitr.txt (#101)
provides:
  - 29-DPLY02-DISPOSITIONS.md - 20 rows, each with disposition, evidence path:line, comment file and expected post-29-28 state
  - scripts/check-dply02-dispositions.sh - static, --pre-close, --post-close, --scan-text, --write-manifest, --verify-approval, --selftest (64 arms); static mode + selftest wired into CI
  - evidence/29-27-comments/ - the exact public text for 18 issues (the 2 PR-reference rows have none)
  - evidence/29-27-approval.sha256 + 29-27-approval.txt - the owner's approval bound to content (sha256 1d50dd2c89e8...)
  - docs/runbooks/staging-operations.md - the D-18 runbook page
  - Supersede notes (sealed-secrets, DEPLOYMENT.md, QUICK_START.md, LOCAL.md), a staging section in credential-rotation.md, an appended 2026-10 record in PRODUCTION_READINESS_REPORT.md, a Phase 32 inputs section in deferred-items.md
affects: [29-28]

actuals:
  tasks: 3
  # commits counted from git log main..HEAD, 29-27-scoped (see Commits below): 12 plan commits + 2 fix-ups (0d77f624 before, 652ac77d after the owner approval)
  commits: 14
plan_head_before: 428c62ba37157c21fd15c5a0dc5109236978627c
plan_head_after: 652ac77ddedf5b3a59861ac2c8737a0f3178a58c   # last 29-27 commit; this SUMMARY's own commit is not counted.

tech-stack:
  added: []
  patterns:
    - "A required list is DERIVED from the document that names it (the ROADMAP section) and the record is held to set equality with it; a hand list is the thing that goes stale"
    - "The owner's approval quotes the sha256 of a manifest that pins the table and every comment file, so an edit after approval is refused rather than noticed"
    - "Public text is scanned lexically for closing keywords next to any issue reference form, because GitHub's parser is lexical"

key-files:
  created:
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/29-DPLY02-DISPOSITIONS.md
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-27-comments/ (18 files)
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-27-approval.sha256
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-27-approval.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-27-citations-baseline.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-27-measurements.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-27-t1-red-row-counter.json
    - scripts/check-dply02-dispositions.sh
    - docs/runbooks/staging-operations.md
  modified:
    - .github/workflows/ci-cd.yaml
    - docs/runbooks/credential-rotation.md
    - docs/runbooks/sealed-secrets.md
    - k8s/DEPLOYMENT.md
    - k8s/QUICK_START.md
    - k8s/LOCAL.md
    - k8s/PRODUCTION_READINESS_REPORT.md
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/deferred-items.md
    - HANDOFF.md
    - scripts/gates/object-store-residue-allowlist.conf

key-decisions:
  - "The dispositions gate derives its required list from the ROADMAP's Phase 29 section and unions #101 #296 #297 #859; the count (20 here: 18 issues + the 2 merged ADR-0002 PRs #953 and #954) is whatever it prints, never a remembered figure"
  - "#98 is recorded DEFERRED, not closed: criterion 1 reads 'scrapes all three services' and the frontend has no scrape job (#971), so closing it would claim more than 29-25's evidence shows; criterion 3 stays tracked on #98 itself"
  - "#112 is a minimal close under D-18 with two acceptance criteria deliberately unmet (first-response entries, SLOs); the remainder is #982 and the close depends on docs/runbooks/staging-operations.md"
  - "#592 closes the deployment wiring only; the customer order-state mail (legacy path) carries no List-Unsubscribe header and is tracked as #981"
  - "Dated records are appended, never rewritten: the readiness report and the supersede notes are pure additions (git diff --numstat 428c62ba..652ac77d shows 0 deleted lines on every pre-existing doc this plan touched)"

requirements-completed: []   # see "What is not done": DPLY-02's record exists and is approved, but no issue has changed state; DPLY-03 is carried by 29-25 and 29-19. DPLY-02 is marked on 29-28's SUMMARY, once the dispositions are applied.

coverage:
  - id: D1
    description: "A dispositions record with one row per ROADMAP-named reference, each with evidence and the exact comment text"
    requirement: DPLY-02
    verification:
      - kind: integration
        ref: "bash scripts/check-dply02-dispositions.sh -> SUMMARY derived=20 table=20 findings=0, rc 0 (re-run at draft time on the working tree)"
        status: pass
      - kind: unit
        ref: "--selftest 64 arms; RED against a row-counting stub: 2/52 passing (the two negative controls), RED_EVIDENCE_OK (evidence/29-27-t1-red-row-counter.json); ten arms additionally turned red by a deliberate break of the matching rule in a scratch copy (commit 06a6a7e5)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Public text cannot close an issue by accident or carry an attribution line"
    requirement: DPLY-02
    verification:
      - kind: unit
        ref: "selftest kw-* and attr-* arms plus negative controls (kw-negative-control, kw-prefix-word-control); 29-28 Task 1 recorded the PR body --scan-text rc 0 with controls: an attribution-trailer line rc 1, 'closes #99' rc 1 (evidence/29-28-merge-deploy.txt)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The owner's approval is bound to content"
    requirement: DPLY-02
    verification:
      - kind: integration
        ref: "bash scripts/check-dply02-dispositions.sh --verify-approval -> approved=1d50dd2c89e8... manifest=1d50dd2c89e8... pinned=19 findings=0, rc 0 (re-run at draft time). Commit 2d32870d records rc 0 on the record, rc 1 with one character of the approved hash changed, rc 0 once restored"
        status: pass
    human_judgment: true
    rationale: "Only the owner can approve the table; the approval is the reply 'approve 1d50dd2c89e8' at 2026-10-20T05:01:09Z"
  - id: D4
    description: "The D-18 runbook page exists and names the pinned cluster context"
    requirement: DPLY-03
    verification:
      - kind: integration
        ref: "grep -c -- '--context jtoye-stg-aks' docs/runbooks/staging-operations.md -> 12; grep -c -i TODO -> 0 (read at draft time)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Doc citations are no worse than the base measured on origin/main"
    requirement: DPLY-02
    verification:
      - kind: integration
        ref: "baseline origin/main a5dd4d94... rc=0 (f49a015d); check-doc-citations.sh rc=0 on 652ac77d (evidence/29-28-merge-deploy.txt)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Append-only diffs on dated records"
    requirement: DPLY-02
    verification:
      - kind: integration
        ref: "git diff --numstat 428c62ba..652ac77d: PRODUCTION_READINESS_REPORT.md 44/0; sealed-secrets.md 9/0; DEPLOYMENT.md 28/0; QUICK_START.md 9/0; LOCAL.md 12/0; credential-rotation.md 29/0; ADR-0002 16/0 (added later, D7)"
        status: pass
    human_judgment: false
  - id: D7
    description: "ADR-0002 D-24 amendment"
    requirement: DPLY-02
    verification:
      - kind: integration
        ref: "Written 2026-10-20 after 29-19 measured the estate: '## Amendment 2026-10-20 — D-24: Redis in-cluster for staging' appended to ADR-0002; git diff main -- the ADR file shows 16 additions, 0 removals"
        status: pass
    human_judgment: false

duration: about 3 h 13 min of wall clock between the first and last 29-27 commit (04:39 to 07:52 +01:00, 2026-10-20), with the owner approval at 06:01 +01:00
completed: 2026-10-20
---

# Phase 29 Plan 27: The DPLY-02 Record, Its Gate, the D-18 Runbook, and the Owner's Approval

**Every reference the ROADMAP's Phase 29 section names now has a written, evidence-linked disposition (20 rows: 18 issues, 2 merged ADR-0002 PRs), a gate that re-derives the list from the ROADMAP and refuses a missing, extra or unevidenced row, and exact public comment text that cannot close an issue by accident. The owner approved the table by quoting its manifest hash. No issue has changed state yet: that happens in plan 29-28, after the merge deploy.**

## What is not done (stated plainly)

- **No GitHub issue has been commented on, closed, or left deliberately open by an action of this plan.** The dispositions are a record and an approval. Applying them (`--pre-close`, the comments, the closes, `--post-close`) is plan 29-28 and runs after the merge deploy. The owner approved the table with `approve 1d50dd2c89e8` at 2026-10-20T05:01:09Z (full hash `1d50dd2c89e8a3a3025fa675239102a4e65c6f3de8a425a53366419dcd4b20c7`).
- **The ADR-0002 D-24 amendment was written late** (deviation 3), on 2026-10-20 once plan 29-19 had measured the estate at £156.00/month and the owner had moved the ceiling to £178.
- **Plan 29-19 completed after this plan's table was approved.** No row in the table cites it. It is a declared dependency of this plan (`depends_on` includes 29-19); deviation 4.

## Performance

- **Duration:** about 3 h 13 min between the first (`0c9b2e9d`, 04:39 +01:00) and last (`652ac77d`, 07:52 +01:00) 29-27 commit. The owner approved at 06:01 +01:00.
- **Tasks:** 3 of 3 (Task 3 is the checkpoint:decision, resolved by the owner's reply).
- **Live estate touched:** none. This plan makes no cluster, Azure or GitHub-write call; the `--pre-close` check reads issue and PR states with `gh` (read-only).

## Accomplishments

- **Task 1: the record and its gate** (`0c9b2e9d` RED, `06a6a7e5` GREEN, `d3ca4b04` table, comments, manifest and CI step)
  - **Fail direction:** the selftest harness, carrying 52 planted-file arms, ran against a stub that only counts rows and exits 0 in every mode: 2/52 passed (the two negative controls), classified `RED_EVIDENCE_OK` (`target_test_failed`, target arm `missing-row`).
  - **Pass direction:** the gate body makes the selftest 64/64; ten arms were additionally turned red by a deliberate break of the matching rule in a scratch copy.
  - **Table:** 20 rows. Expected end states: 10 CLOSED by 29-28 (#99, #100, #101, #112, #296, #297, #299, #300, #592, #859); 4 already CLOSED and recorded (#271, #292, #293, #302); 4 left OPEN as deferrals with a written reason (#98, #294, #301, #304); 2 `pr-reference` rows (#953, #954, MERGED, never changed).
  - **Comments:** 18 files under `evidence/29-27-comments/`, each citing its row's evidence path. `#99`'s carries a `RUN_URL` token that 29-28 substitutes into a temporary copy.
  - **Measurements** the earlier plans' evidence did not hold are in `evidence/29-27-measurements.txt` (#301, #304, #300, #100, #101, #112), each absence paired with a control that hits.
  - **CI:** the selftest and the static mode run in the same static CI job as the subscription-pin gate.
- **Task 2: the documentation** (`f49a015d` baseline first, then `fe74d1fb`, `9dcf2cb4`, `fe2742f8`, `40a41b93`, `7a8b6a80`, `2d512c30`)
  - **Citation baseline, measured before any doc edit:** `origin/main` at `a5dd4d94`, `check-doc-citations.sh` rc 0, no FAIL lines (header of `evidence/29-27-citations-baseline.txt`). The commit message records 46 verified and 7 uncheckable.
  - **`docs/runbooks/staging-operations.md`:** reach the cluster through the pinned `jtoye-stg-aks` context, read Grafana with the subscription pinned, restart a workload, run the restore drill, where alerts go and the only safe mute, why a mail fault turns the deploy red, the gates to run after a change. A second commit adds the known staging faults (#974, #968, #972, #969) and the rule to rotate Alertmanager's SMTP password through Key Vault only.
  - **`credential-rotation.md`:** a staging section, per object class; `DSAR_ENCRYPTION_KEY` marked as not a routine rotation.
  - **Superseded, not deleted:** `sealed-secrets.md` (banner, D-23), `DEPLOYMENT.md` and `QUICK_START.md` (ingress-nginx/Helm and sealed-secrets steps, D-29 and D-23); `DEPLOYMENT.md` gains `check-deploy-digest-parity.sh` in the runtime-parity gates; `LOCAL.md` gains a note that local names ingress-nginx through the controller-namespace replacement and that the staging components are not in the local render.
  - **`PRODUCTION_READINESS_REPORT.md`:** an appended `2026-10 — Phase 29 staging` record: what staging proves with its evidence files, and what it does not show about production.
  - **`deferred-items.md`:** the Phase 32 inputs this phase produced (production opts into the components, the connection budget at the reserved 15, per-environment frontend images, SES, DB HA, mcp-server, stomp-relay rework, #98 criterion 3, the #112 remainder, the Traefik-namespace egress policy, production trusted proxies, Key Vault for production).
- **Task 3: the owner's approval** (`f926c535` re-pin, `2d32870d` record)
  - **Before approval:** the five pinned files that cite follow-up issues changed (the #98 comment cites #971, #112 cites #982, #101 cites #983, #592 cites #981), so the manifest was re-written. The manifest hash moved from `2e574e5e5cdb...` (as first committed in `d3ca4b04`) to `1d50dd2c89e8...`; the owner approved the second.
  - **Approval record:** the reply `approve 1d50dd2c89e8`, the time `2026-10-20T05:01:09Z`, and `Approved-Manifest-SHA256: 1d50dd2c89e8a3a3025fa675239102a4e65c6f3de8a425a53366419dcd4b20c7`. `--verify-approval` rc 0; rc 1 with one character changed; rc 0 once restored (per the commit message of `2d32870d`).
  - **Decisions put to the owner and approved as written:** #98 recorded as DEFERRED, #112 closes with two criteria unmet (D-18 minimal close).

## Both Directions, Recorded

| Check | Fail direction | Pass direction |
|-------|----------------|----------------|
| Dispositions selftest | Row-counting stub: 2/52, `RED_EVIDENCE_OK` (`29-27-t1-red-row-counter.json`) | 64/64 (`06a6a7e5`); ten arms turned red by a scratch-copy break of the matching rule |
| Dispositions static mode | Selftest arms `missing-row`, `extra-row`, `evidence-missing-path`, `deferral-empty-reason`, `void-no-section`, `void-table-no-rows` each expect rc 1 or 2 | `SUMMARY derived=20 table=20 findings=0`, rc 0 |
| Public-text rule | `kw-does-not-close`, `kw-closes-colon`, `kw-owner-repo`, `kw-issue-url`, `attr-*` arms rc 1 | `kw-negative-control` ("closure of ... is recorded") and `kw-prefix-word-control` rc 0 |
| Approval binding | One character of the approved hash changed: rc 1 (commit `2d32870d`) | The committed record: rc 0 |
| Absence claims in `29-27-measurements.txt` | Each paired with a control that must hit (e.g. `rg -uu -i mcp k8s/scripts/connection-budget.conf` rc 1 beside `rg -uu -c k8s-staging ...` rc 0) | - |
| Plaintext Secret (#100) | A scratch kustomize build with one planted Secret: `kind: Secret=1` | staging, production, local builds: 0 plaintext Secrets (92, 27, 27 resources per `check-no-plaintext-secrets.sh`) |

## TDD Gate Compliance

| Cycle | RED | GREEN | Classifier |
|-------|-----|-------|------------|
| DPLY-02 dispositions gate | `0c9b2e9d`: 2/52 against the row-counting stub | `06a6a7e5`: 64/64 | `RED_EVIDENCE_OK` (`target_test_failed`, target `missing-row`) |

**Semantic assessment:** the target arm ran against the stub and failed on its planned assertion (rc 0 where rc 1 and a `MISSING row` finding were required). The two arms that passed against the stub are the negative controls, which a stub that finds nothing correctly passes. The harness itself was the real one.

## Deviations from Plan

### Auto-fixed issues

**1. [Rule 1 - Bug, gate] The docs lane did not run the object-store residue gate, and its insert broke it** (`652ac77d`)
- **Cause:** Task 2 inserted a 12-line note into `k8s/LOCAL.md` at line 46. `scripts/gates/object-store-residue-allowlist.conf` pins fenced Phase 26 records by line number, so every `LOCAL.md` entry below the insert moved down by 12.
- **Symptom:** `scripts/check-no-object-store-residue.sh` reported 16 violations and 14 stale entries, rc 1. It was recorded rc 1 on `2d32870d` in 29-28's Task 1 evidence.
- **Why it was missed:** none of the plan's verify commands runs that gate, and the docs lane ran only the gates the plan names.
- **Fix:** each `LOCAL.md` entry shifted by 12; no reason changed (14 insertions, 14 deletions). The gate went rc 1 to rc 0 (29-28 evidence: rc 0 on `652ac77d`).

**2. [Rule 1 - Bug, doc contract] The HANDOFF gate count was stale** (`0d77f624`, then `da31c859`)
- **Cause:** `check-handoff-contract.sh` H-1 failed on the branch head: the resume block still said `EXPECT 48` while `scripts/` held 56 gate scripts. Phase 29 added eight gates and never moved the expected count.
- **Fix:** 48 to 56 (`0d77f624`, named gates: check-alertmanager-render, check-az-subscription-pin, check-deploy-digest-parity, check-dply02-dispositions, check-frontend-baked-origin, check-k8s-copy-parity, check-networkpolicy-enforcement, check-staging-edge).
- **Afterwards:** `da31c859` moved it 56 to 57 for `check-deploy-rollback-guard`, added by a PR #984 round 1 fix. That second move belongs to 29-28's review round, not to this plan; it is recorded here only because it is the same line of the same file.

### Plan shape not followed

**3. [Late, with the measured figure] The ADR-0002 D-24 amendment**
- **What the plan asked:** an appended, dated `Amendment 2026-10-xx - D-24: Redis in-cluster for staging`, citing the measured cost (Managed Redis B0 HA 19.86 per month; the estate 145.02 once the load balancer meter bills).
- **Why it waited:** the amendment states a measured estate cost, and the only measured figure is plan 29-19's. Writing the research's projections into a signed ADR as if measured would be the failure the phase exists to avoid.
- **What was written (2026-10-20):** `## Amendment 2026-10-20 — D-24: Redis in-cluster for staging`, appended after the 2026-10-18 section. It cites the measured estate (£156.00/month, four B2s nodes; £169.80 once the load balancer bills), Managed Redis B0 HA at £19.86/month (research), the owner's new £178 ceiling, and that production decides in Phase 32. The plan's £145.02 figure is not used, because it was a 3-node projection that D-34 overtook.
- **Pure addition:** `git diff main --` the ADR file has 16 additions and 0 removals.

**4. [Order] This plan ran without its declared dependency 29-19**
- `depends_on` lists 29-19. Task 1, Task 2 (minus the amendment) and the owner approval were done first, because none of the 20 dispositions depends on the cost figure.

**5. [Re-pin before approval] The manifest hash changed once**
- The first committed manifest (`d3ca4b04`) had sha256 `2e574e5e5cdb...`. The follow-up issue numbers were then added to four comment texts and the table, and the manifest was re-written (`f926c535`). The owner approved the second hash. No approval was ever given to the first.

**Total:** 2 gate or contract breaks fixed, 1 plan item written late, 1 order deviation, 1 re-pin. **Impact:** the record and its approval stand; every issue state change remains for 29-28.

## Cross-Cutting Quality Contracts

Recorded for what 29-27 actually changed: documentation, issue-comment text, one static gate script and its CI step. No product code, page, API, or client storage was touched. None silently dropped.

| Contract | Applies? | Reason |
|----------|----------|--------|
| Web performance (mobile-first) | N/A | No user-facing page or bundle changed; the frontend image is not touched by this plan. |
| SEO / discoverability | N/A | No public surface changed. (Staging is non-indexable by design, plan 29-15; this plan does not alter it.) |
| AI agent-readiness / machine-consumability | N/A | No API surface added or changed, so no Idempotency-Key, RFC 7807 or MCP-tool obligation arises. The gate script's modes are CLI exit codes (0 clean, 1 findings, 2 VOID), not an API. |
| Security | Applies, limited | The plan's threat model: T-29-98 (closure without evidence) mitigated by the evidence-path check and the owner approval; T-29-99 (lexical auto-close) by the public-text rule; T-29-104 (edit after approval) by the manifest and `--verify-approval`. T-29-100 (secrets in runbooks) is mitigated by naming Key Vault objects only, never values, and by the leak scan, run late on 2026-10-20: `scripts/staging-secrets.sh --leak-scan` over all 14 files the docs commits touched, against the 29 live Key Vault values, rc 0 with 0 found on every file; its selftest arm names a planted value (rc 1). Evidence: `evidence/29-27-leakscan.txt`. `/gsd-secure-phase` is a phase-level step not yet run. |
| Client-persisted identity lifecycle | N/A | No client-side storage or user-scoped state changed. |
| Falsifiable evidence + runtime parity | Applies (evidence half); parity half N/A for this plan | Every gate arm was shown failing first (2/52 RED against a stub, ten scratch-copy breaks, absence claims paired with hitting controls, the approval binding refused on a one-character change). Two breaks that the plan's own checks did not catch, the residue allowlist and the HANDOFF count, were found by the wider gate suite run in 29-28 and are deviations 1 and 2. No runtime was restored or handed back by this plan, so runtime parity is 29-28's proof. |

## Findings

- **Instrument gap, found by the wider suite and not by this plan's verify:** a doc insert shifts line-pinned allowlist entries. `scripts/check-no-object-store-residue.sh` and `scripts/check-handoff-contract.sh` are not in the plan's verify commands. The record is in deviations 1 and 2; whether to add them to docs-lane plans is for the orchestrator. It is not filed as an issue here.
- **Open follow-ups already filed** (cited in the table): #971 (frontend scrape job), #981 (order-state mail has no List-Unsubscribe), #982 (runbook stubs and SLOs), #983 (DB HA), #980 (`FRONTEND_PUBLIC_API_URL` bakes the staging origin), #974, #968, #972, #969, #976.

## Threat Flags

None beyond the plan's threat model.

## Commits (29-27-scoped, from `git log main..HEAD`)

| Commit | Subject |
|--------|---------|
| `0c9b2e9d` | test(29-27): RED - the DPLY-02 dispositions selftest against a gate that only counts rows |
| `06a6a7e5` | feat(29-27): GREEN - the DPLY-02 dispositions gate, derived from the ROADMAP |
| `d3ca4b04` | docs(29-27): the DPLY-02 dispositions table, its comment texts, the pinned manifest, and the CI step |
| `f49a015d` | docs(29-27): measure the doc-citations baseline on origin/main before editing any doc |
| `fe74d1fb` | docs(29-27): add the D-18 staging operations runbook page |
| `9dcf2cb4` | docs(29-27): add the staging Key Vault rotation section, per object class |
| `fe2742f8` | docs(29-27): mark the ingress-nginx, Helm and sealed-secrets instructions superseded for staging |
| `40a41b93` | docs(29-27): record the known staging faults and the Alertmanager password trap in the runbook |
| `7a8b6a80` | docs(29-27): append the 2026-10 Phase 29 staging record to the readiness report |
| `2d512c30` | docs(29-27): list the Phase 32 inputs Phase 29 produced in deferred-items |
| `f926c535` | docs(29-27): cite the filed follow-up issues in the disposition comment texts and re-pin the manifest |
| `2d32870d` | docs(29-27): record the owner's approval of the DPLY-02 disposition table |
| `0d77f624` | docs(29): move the HANDOFF gate count from 48 to 56 for the eight Phase 29 gates |
| `652ac77d` | fix(29-27): renumber the k8s/LOCAL.md residue allowlist entries after the staging note |

(`a4516cd2`, "docs(29): file every open Phase 29 finding as an issue and write the resume point", was committed after `d3ca4b04`. It is a phase-level commit, not a 29-27 commit, and is not counted.)

## Self-Check

- **Commits:** all 14 listed above are ancestors of HEAD (`git merge-base --is-ancestor` rc 0 for each, read at draft time). `plan_head_before` 428c62ba is the parent of `0c9b2e9d`.
- **Gate re-runs at draft time, working tree clean apart from two untracked planning paths:** static mode rc 0 (`derived=20 table=20 findings=0`); `--verify-approval` rc 0 (`pinned=19 findings=0`).
- **Re-run on the final docs head, 2026-10-20 (static suite, jtoye-ops env):** branch-behind-base, render invariants, render goldens, env contract, no plaintext secrets, connection math, dependency horizons, no CREATE EXTENSION, gate enforcement, alert rules, object-store residue, doc citations, handoff contract, deploy rollback guard and the dispositions gate all rc 0; `--verify-approval` rc 0 against `1d50dd2c89e8`. The dispositions gate was first rc 1 on that head (`MISSING row #984`): a draft ROADMAP annotation had named the PR inside the Phase 29 section, which the gate reads as a reference. The annotation was removed, not given a row, so the approved table is unchanged. `--selftest` and `--pre-close` were not re-run; their results come from the commit messages and `evidence/29-28-merge-deploy.txt`.
- **Files:** FOUND `29-DPLY02-DISPOSITIONS.md`, `evidence/29-27-approval.txt`, `evidence/29-27-approval.sha256`, `evidence/29-27-citations-baseline.txt`, `evidence/29-27-measurements.txt`, `evidence/29-27-t1-red-row-counter.json`, and 18 comment files.
- **FOUND (added late):** the ADR-0002 D-24 amendment (deviation 3) and `evidence/29-27-leakscan.txt`.
