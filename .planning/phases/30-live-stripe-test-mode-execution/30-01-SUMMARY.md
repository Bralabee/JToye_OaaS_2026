---
phase: 30-live-stripe-test-mode-execution
plan: 01
subsystem: payments
tags: [stripe, nextjs, docker, ci, build-args, deploy-gate, issue-957]

requires:
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: deploy-staging / staging-edge-probe jobs, the CR-02 baked-origin guard pattern, check-gate-enforcement.sh
provides:
  - "Optional Dockerfile ARG/ENV NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY, compose build arg, CI build-arg from vars.FRONTEND_PUBLIC_STRIPE_PUBLISHABLE_KEY"
  - "frontend/lib/stripe-loader.ts: publishableKey(), loadStripeOnce(stripeAccount?), STRIPE_APPEARANCE, resetStripeLoaderForTests()"
  - "scripts/check-frontend-stripe-key-baked.sh: served-chunk key check (--url/--path, --scan-dir, --expect-prefix, --control, --selftest)"
  - "scripts/check-stripe-pk-var.sh: per-environment publishable-key deploy guard (--environment staging|production, --selftest, env STRIPE_PK_VAR)"
  - "CI: guard steps in deploy-staging and deploy-production, a live chunk read in staging-edge-probe, two per-PR selftests in ops-contracts"
affects: [30-02, 30-03, 30-pay-link-page, phase-32-per-environment-images]

actuals:
  tokens: 22516
  tasks: 2
  commits: 6
plan_head_before: 1339b92106073916f4dac9f2e2b5d75cea57f619
plan_head_after: 1dda455777a3d84fb97b85664c51905ad617c098

tech-stack:
  added: []
  patterns:
    - "One module reads a NEXT_PUBLIC_* value, through the literal member expression Next inlines and check-env-contract.sh reads"
    - "Deploy guard + served-artifact read for an inlined build value (pairs with CR-02's baked-origin guard)"
    - "Selftest arms assert the refusal REASON as well as the exit code, so a redundant second check cannot hide a removed first one"

key-files:
  created:
    - frontend/lib/stripe-loader.ts
    - frontend/lib/__tests__/stripe-loader.test.ts
    - scripts/check-frontend-stripe-key-baked.sh
    - scripts/check-stripe-pk-var.sh
    - .planning/phases/30-live-stripe-test-mode-execution/deferred-items.md
  modified:
    - frontend/Dockerfile
    - docker-compose.full-stack.yml
    - frontend/app/shop/[slug]/checkout/page.tsx
    - frontend/app/shop/[slug]/checkout/__tests__/stripe-lazy-load.test.tsx
    - .github/workflows/ci-cd.yaml
    - k8s/scripts/check-env-contract.sh
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "The Stripe loader memo is module-level, keyed by connected account; a rejected or null load is forgotten so a retry loads again"
  - "The #793 lazy-load harness mock now resolves a stand-in Stripe instance: null is Stripe.js's failure value, which the loader forgets"
  - "check-stripe-pk-var.sh names an sk_/rk_ value as a SECRET to rotate, beyond the plan's prefix rules (T-30-02)"
  - "Workflow comments describe the pk-var guard without naming its file, so check-gate-enforcement.sh depends on the real invocations"

patterns-established:
  - "Served-chunk check: control string required, VOID on 0 control / fetch failure / no chunks, prefix+length only in output"

requirements-completed: [PAY-03, PAY-04]

coverage:
  - id: D1
    description: "A key passed at build time is inlined in the image's served checkout chunks; a keyless image carries none"
    requirement: PAY-03
    verification:
      - kind: integration
        ref: "docker build keyed/keyless + scripts/check-frontend-stripe-key-baked.sh --scan-dir (evidence/30-01-tracer-keyed-keyless.txt)"
        status: pass
    human_judgment: false
  - id: D2
    description: "frontend/lib/stripe-loader.ts is the only reader of the key; the checkout loads Stripe only through loadStripeOnce from /pure (#793 kept)"
    requirement: PAY-03
    verification:
      - kind: unit
        ref: "frontend/lib/__tests__/stripe-loader.test.ts (12 tests)"
        status: pass
      - kind: unit
        ref: "frontend/app/shop/[slug]/checkout/__tests__/stripe-lazy-load.test.tsx (4 tests)"
        status: pass
      - kind: other
        ref: "rg -uu -n 'process\\.env\\.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY' frontend/app frontend/components frontend/lib (non-test) -> stripe-loader.ts only"
        status: pass
    human_judgment: false
  - id: D3
    description: "Served-chunk key check with --selftest (11 arms), run against the live staging edge after every staging deploy"
    requirement: PAY-03
    verification:
      - kind: unit
        ref: "bash scripts/check-frontend-stripe-key-baked.sh --selftest"
        status: pass
      - kind: other
        ref: "live app-staging read-only run (evidence/30-01-live-staging-and-gate-arms.txt): FAIL rc 1 on the pre-fix image, as expected"
        status: pass
    human_judgment: false
  - id: D4
    description: "Deploy guard: staging requires pk_test_, production refuses pk_test_ and empty; selftest first in both jobs and per PR"
    requirement: PAY-04
    verification:
      - kind: unit
        ref: "bash scripts/check-stripe-pk-var.sh --selftest (11 arms)"
        status: pass
      - kind: other
        ref: "workflow structure check: 6/6 True on the tree, 1/6 on origin/main"
        status: pass
    human_judgment: false
  - id: D5
    description: "check-env-contract.sh passes with the allowlist row removed and reports STALE if it returns"
    verification:
      - kind: other
        ref: "bash k8s/scripts/check-env-contract.sh && bash scripts/check-gate-enforcement.sh; STALE break arm"
        status: pass
    human_judgment: false
  - id: D6
    description: "The deploy and probe steps behave on GitHub Actions as they do locally (repository variable empty until plan 30-03)"
    verification: []
    human_judgment: true
    rationale: "The CI jobs only run on main after merge; locally only the scripts and the YAML structure were exercised. Plan 30-03 sets the variable and watches Deploy to Staging + Staging Edge Probe."

duration: 18min
completed: 2026-10-22
status: complete
---

# Phase 30 Plan 01: The Stripe Publishable Key Reaches the Browser Through an Enforced Build Channel Summary

**The frontend image can now carry the Stripe publishable key. It goes in as an optional Dockerfile build-arg from a repo variable and is read in one loader module (`loadStripeOnce`, memoised per connected account). Two new gates keep the channel honest: deploys refuse a missing or wrong-mode key (staging needs `pk_test_`, production refuses it), and after every staging deploy a check reads the key back out of the live checkout chunks.**

## Performance

- **Duration:** 18 min
- **Started:** 2026-10-22T03:30:26Z
- **Completed:** 2026-10-22T03:48:32Z
- **Tasks:** 2/2
- **Files modified:** 22 (incl. evidence), 1137 insertions, 36 deletions

## Accomplishments

- Opened the #957 build channel. A keyed image inlines the key: `distinct_pk=1`, `residues=0`, `secrets=0`, `control=14`. A keyless image has none (`distinct_pk=0`).
- Moved the checkout's key read and Stripe memo into `frontend/lib/stripe-loader.ts`. The page no longer reads the env key, and #793's lazy load still holds (properties 1 to 4 green).
- `scripts/check-frontend-stripe-key-baked.sh` reads the key out of the running artifact. Run against today's staging it reproduces the research numbers exactly: 22 chunks, 0 keys, 1 un-inlined lookup, control 1, so FAIL rc 1.
- `scripts/check-stripe-pk-var.sh` is wired into deploy-staging (D-06) and deploy-production (D-08), with its selftest first in each job. Both selftests also run on every PR in ops-contracts.
- Removed the env-contract reviewed-omission row. The gate went STALE when the ARG landed, and goes STALE again if the row comes back.

## Task Commits

1. **Task 1 RED:** `fe0f5eb9` test(30-01): add failing tests for the Stripe publishable-key loader (#957)
2. **Task 1 GREEN:** `d6074304` feat(30-01): the Stripe publishable key reaches the served checkout chunks (#957)
3. **Task 2 RED:** `35e2f954` test(30-01): add failing selftest arms for the publishable-key deploy guard (#957)
4. **Task 2 GREEN:** `d1275842` feat(30-01): CI bakes the publishable key; deploys refuse a wrong-mode key (#957)
5. **Task 2 fix:** `90df31a8` fix(30-01): keep the guard's filename out of a workflow comment (#957)
6. **Metrics:** `1dda4557` chore(30-01): refresh test metrics for the stripe-loader suite (#957)

## TDD Gate Compliance

- RED before GREEN for both features: `fe0f5eb9` comes before `d6074304`, and `35e2f954` before `d1275842`.
- **Task 1 RED, two records:**
  - `evidence/30-01-red-loader-absent.json`: 12/12 fail with the module absent. Classifier: RED_EVIDENCE_OK / target_test_failed. Semantically this is an import failure. It is the plan's designated RED condition, but on its own it is weak evidence.
  - `evidence/30-01-red-loader-stub.json` (stronger, assertion level): an uncommitted scratch stub holding the page's old `getStripe()` verbatim. 5 of 12 fail on their planned assertions (empty key not null, no per-account load, rejected and null loads not forgotten, colour `#f97316`); the 7 behaviours the old code already had pass. Target "forgets a load that REJECTS": expected 2 calls, received 1. Classifier: RED_EVIDENCE_OK.
  - Chunk-check script, script absent: rc 127, all 10 arms missing (`evidence/30-01-red-chunkcheck-absent.txt`).
- **Task 2 RED:** `evidence/30-01-red-pkvar.json`. Selftest 0/11 against a `VOID: not implemented` body, each arm failing on its own assertion (wrong rc; for bad-environment the missing `USAGE ERROR`; for no-echo the non-vacuity control). Classifier: RED_EVIDENCE_OK.
- No REFACTOR commit was needed.

## Verification (both directions)

| Check | Fail direction (broken input) | Pass direction (tree) |
|---|---|---|
| Loader + lazy-load Jest (plan verify 1) | module absent: 12/12 fail; old-logic stub: 5/12 fail | `Tests: 16 passed`, 2 suites, rc 0 |
| Chunk-check selftest (plan verify 2) | script absent: rc 127, missing=10. Scratch copies with one mutation each: control VOID removed → `FAIL control-absent`; secret reason removed → `FAIL secret-literal`; key value echoed → `FAIL no-echo`; residue regex blinded → `FAIL residue` | 11/11, missing=0, fail_lines=0, rc 0 |
| Tracer (docker build ×2, `--scan-dir`) | keyless image: `FAIL` rc 1, `distinct_pk=0`; independent `grep -rlF` of the key: 0 files | keyed image: `PASS` rc 0, `distinct_pk=1 keys=pk_test_(length=32) residues=0 secrets=0 control=14`; independent grep: 1 file. Both tags removed |
| Compose (`True False`) | origin/main compose: `False True` | `True False` |
| Single literal reader | planted read in a scratch copy under `frontend/app/` is listed (then removed); origin/main page has a read at l.777 | only `frontend/lib/stripe-loader.ts` (l.13 comment, l.35 read) |
| pk-var selftest (plan verify T2-1) | stub: 0/11. Mutations: value echoed → `FAIL no-echo`; staging accepts anything → `FAIL staging-live` + `FAIL staging-malformed`; staging prefix check removed → `FAIL staging-live` (reason); production pk_test_ refusal removed → `FAIL production-test` (reason) | 11/11, rc 0 |
| Env-contract + gate-enforcement (T2-2) | before the row was removed: rc 1, `STALE entry 'NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY'`; break arm (row restored on the committed tree): rc 1 with the same STALE line; restore `git=0 cmp=0` | rc 0, bad_lines=0 (clean before and after the arm) |
| Gate-enforcement wiring | chunk-check invocations removed → rc 1 naming the script; pk-var invocations removed → rc 1 (after `90df31a8`) | rc 0, restores `git=0 cmp=0` |
| Workflow structure (T2-3) | origin/main ci-cd.yaml: 1/6 True | 6/6 True, rc 0; actionlint rc 0 |
| Doc-only wiring (T2-4) | the gate's own selftest plants each break (all arms behaved) | rc 0, `mixed=[docs_only=false] docs=[docs_only=true]` |
| Deploy dry run | n/a | `STRIPE_PK_VAR="" … --environment staging` → rc 1 (staging's state until 30-03) |
| docs-freshness / doc-metrics | before the refresh: rc 1, and 10 stale prose claims | both rc 0; check-claims rc 0 |
| Full-program tsc | planted error in stripe-loader.ts reported at its line (restore hash-identical) | no error in any 30-01 file (5 pre-existing errors elsewhere, see Issues) |
| ESLint on touched frontend files | n/a | rc 0 |

## Decisions Made

- **The memo moves to the module, keyed by connected account.** This is now "at most once per page load" rather than per component mount. That is how Stripe.js expects to be loaded, and it is what makes "forget a failed load" possible.
- **The lazy-load harness mock resolves a stand-in Stripe instance.** See deviation 1.
- **The pk-var guard refuses an `sk_`/`rk_` value with a rotate-now message.** The plan's prefix rules already refuse it; the message tells the operator what the value is (T-30-02).
- **Selftest arms check the refusal reason, not only rc.** Two mutations survived the first round because a second check preserved the verdict.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] stripe-lazy-load.test.tsx harness mock resolved `null`**
- **Found during:** Task 1 GREEN
- **Issue:** The plan requires the loader to forget a load that resolves null, and also requires lazy-load property 3 to stay green. The harness mocked `loadStripe` as `Promise.resolve(null)`, so the resubmission in property 3 became a retry of a "failed" load. Measured: `Expected number of calls: 1, Received: 2` at stripe-lazy-load.test.tsx:255.
- **Fix:** The mock now resolves `{ __fakeStripe: true }` (what a successful load resolves to). `beforeEach` calls `resetStripeLoaderForTests()` because the memo is module-level now. No assertion changed.
- **Files modified:** `frontend/app/shop/[slug]/checkout/__tests__/stripe-lazy-load.test.tsx` (not in the plan's `files_modified`)
- **Verification:** 4/4 lazy-load tests pass; the 16 checkout suites pass 159/159.
- **Committed in:** `d6074304`

**2. [Rule 1 - Bug] A workflow comment masked the guard's wiring from check-gate-enforcement.sh**
- **Found during:** Task 2 meta-gate break arm
- **Issue:** With every invocation of `check-stripe-pk-var.sh` removed, my build-and-push comment naming the file kept the meta-gate at rc 0.
- **Fix:** The comment describes the guard without the filename. Re-run: invocations removed → rc 1.
- **Committed in:** `90df31a8`. The meta-gate weakness itself is out of scope and recorded in `deferred-items.md`.

**3. [Rule 3 - Blocking] docs-freshness gate red from the new Jest suite**
- **Found during:** post-task gate sweep
- **Fix:** `scripts/docs-freshness.sh --write`, and the prose counts in README/CLAUDE/AGENTS updated (3157 Jest blocks / 268 files / 6521 total).
- **Committed in:** `1dda4557`

**4. [Rule 2 - Missing critical] Extra selftest arms beyond the required ten**
- `check-frontend-stripe-key-baked.sh` has `no-chunks` (VOID on an empty scan); `check-stripe-pk-var.sh` has `staging-secret` (an sk_ value refused). Both are 11/11; the plan's verify requires ten named PASS lines and no FAIL, and both scripts meet that.

**Process notes:**
- I wrote the RED test file before running the root-pin guard the first time. The pin passed when run immediately afterwards, and before every commit.
- While grepping for references I echoed the local `.env` publishable key (a `pk_test_` value, public by design) into a tool output. It is in no file and no commit.
- Two scratch edits used `/usr/bin/python3` (the sanctioned stdlib form) rather than the jtoye-ops env.

---

**Total deviations:** 4 auto-fixed (2 blocking, 1 bug, 1 missing critical). **Impact:** all are needed for correctness or for CI to pass; no scope creep.

## Issues Encountered

- **Stale local `node_modules` in the primary checkout.** tailwindcss 3.4.19 is installed while 4.3.3 is locked. The full Jest run is 3142/3157: 15 failures in 3 Tailwind palette/config suites, all with v3 hex values where the tests pin v4 oklch. Full-program tsc reports 5 errors in `layout-widths-css.test.ts`. Neither comes from 30-01, which touches no package, Tailwind or style file. Not fixed: `npm ci` in a shared checkout is an environment change. Recorded in `deferred-items.md`.
- `*.log` is gitignored, so the tracer evidence is committed as `.txt`.

## Known Stubs

None. A keyless image inlines `""`, which the loader reads as "no key". The checkout's loud "card payments unavailable" state is plan 30-02's scope, as the plan states; until then a keyless build behaves as it did before (no Stripe step).

## Threat Flags

None. The only new network surface is staging-edge-probe's public read-only GETs (T-30-04, mitigated by the VOID rules), and it is in the plan's threat model.

## User Setup Required

None for this plan. The repository variable `FRONTEND_PUBLIC_STRIPE_PUBLISHABLE_KEY` (a staging `pk_test_` key) is set by the owner in plan 30-03. Until then deploy-staging refuses every deploy after this merges, by design.

## Next Phase Readiness

- **Before the PR merges (orchestrator):**
  - `docs/CHANGELOG.md` needs an entry citing the PR number. `check-changelog-cites-pr.sh` runs at PR time, so the entry can only be written once the number exists.
  - The merge makes deploy-staging refuse every deploy until plan 30-03 sets the variable. Sequence the merge with 30-03, or accept the refusal window.
- **Plan 30-02** adopts `STRIPE_APPEARANCE` and adds the fail-loud states. It should call `publishableKey()` (not `process.env`) for the pre-POST check.
- **Branch state:** `git log HEAD..origin/main` is empty (local ref; not re-fetched).

---
*Phase: 30-live-stripe-test-mode-execution*
*Completed: 2026-10-22*
