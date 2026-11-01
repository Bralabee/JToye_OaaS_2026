# Handoff: Phase 30 (The Money Path, Executed) executing, 25/32 plans done; PR 6 (#1065) MERGED 6b3ff3e1 2026-11-01 after a green branch nightly; #1072 and #1073 merged (issue #1071 closed on the live edge); 30-24 COMPLETE (the nightly on main, run 41299664811, green); the 30-20..30-24 records MERGED as #1074 (`d013fad9`); Klarna and Link ruled and proven on /pay (PR #1077); next is `/gsd-execute-phase 30` at 30-26

- **2026-11-01 (RESUME HERE — Phase 30 at 25/32 incl. 30-25 via #1067; the 30-20..30-24 record branch merged as PR #1074 `d013fad9`; this block now sits on `docs/30-pay-klarna-link-proof`, PR #1077, which merged `origin/main` after #1076).** The main checkout is on that docs branch; worktree `JToye_OaaS_2026-p30-15` still holds the merged `feat/30-pay01-refund-e2e` and can be removed. **Next:** end PR #1077's review series and merge it; then `/gsd-execute-phase 30` at 30-26 (the local Compose stack is STALE against main: `scripts/sync-runtime.sh` before any local E2E). Done today:
  - **Base merge done (2026-11-01):** `git merge origin/main` brought in #1068, #1067, #1066, #1072, #1073 and #1065 (squash `6b3ff3e1`, whose own HANDOFF.md block now sits under this one as history). Only HANDOFF.md conflicted; the record branch's `.planning` records for 30-20..30-24 stand, and main's 30-25 SUMMARY and quick-task records came in unchanged. The branch was then pushed as PR #1074.
  - **30-23 COMPLETE** (`30-23-SUMMARY.md`, `evidence/30-23-pay01-branch-nightly.txt`): CI sandbox `jtoye-ci` = `acct_1UWtvjD29H13ktT5` (staging's is `acct_1U0tklDpS9ICNeRt`); repo secret `STRIPE_TEST_SECRET_KEY` + variable `CI_STRIPE_PUBLISHABLE_KEY` stored and verified. Four dispatched nightlies on PR 6's branch: 41288249740 (stripe listen needs `--all-snapshot`), 41291041531 (recreated core-java moved to :9091), 41293948964 (the CLI forwards the account default API version `2026-07-29.dahlia`, stripe-java pins `2026-09-30.endive`, typed deserialization empty → silent 400; staging works because its endpoints are pinned on endive; also the cart-identity gate timed out after the Stripe recreate), 41297105542 GREEN on `b8d31922` (`--latest --all-snapshot`, :9090 pin, gate before the recreate, skip-budget gates Phase 1 alone when the Stripe run did not run): Stripe 1/1, `STRIPE-REFUND … refund=re_3UWwFrD29H13ktT51yMfGTP3 amount=100 livemode=false`, both events rows, skip budget 4/4, artifact scan clean.
  - **PR 6 #1065 MERGED `6b3ff3e1`** (30-24 Task 1): review series round 3 (`/review-verify`) zero admissible, Review-Record on `b8d3192253e51ad84a7c7bc726bcdc539a66bb22`; pre-merge sitting head equality / 0 behind / required checks green / `check-stripe-pk-var.sh --environment staging` rc 0 with `STRIPE_PK_VAR` supplied (it is UNSET-red without it). Deploy run 41299627034 SUCCESS (Deploy to Staging + Staging Edge Probe). **30-24 COMPLETE** (`30-24-SUMMARY.md`, `evidence/30-24-pr6-merge-and-main-nightly.txt`): the nightly dispatched on main (run 41299664811 on `6b3ff3e1`) is green end to end — Stripe phase 1/1, `STRIPE-REFUND order=ORD-00000000-20261101-5A93F80C intent=pi_3UWwsID29H13ktT50iiQkxiB refund=re_3UWwsID29H13ktT50mHhqB4b amount=100 livemode=false`, both events rows, skip budget 4/4, forwarded API version `2026-10-28.endive`, artifact secret-free. Tonight's scheduled nightly is the second proof. **Next: this record branch is PR #1074 (open at the time of writing); when its review series ends and it merges, `/gsd-execute-phase 30` resumes at 30-26 (30-25's SUMMARY came in via #1067).**
  - **#1072 MERGED `50dee65a`** (owner-approved /pay trio: no cookie notice on /pay, Try again on the resume path for Stripe.js and Payment Element failures except with no publishable key, an unreadable intent is "couldn't check" never "Payment received"; UI-SPEC amended; two-round series). **#1073 MERGED `fd01dc0c`** (issue #1071 option "the apps own Referrer-Policy" + `metadata.referrer` on /pay + `check-staging-edge.sh` pay-referrer arm and strict `--only`; deploy 41295872214 success; live edge read: `/pay` → `referrer-policy: no-referrer` + `<meta name="referrer" content="no-referrer"/>`; WINDOWS 52 fixed, `evidence/30-20-pay-live.txt` §9).
  - **Owner items still open:** persist the owning Stripe account on `orders` (nullable + `_aud`, no backfill, read-time fallback) as a follow-up PR now that #1065 is merged — the owner is undecided (2026-11-01); the recommendation stands in `evidence/30-20-pay-klarna-link.txt` §8; `GlobalExceptionHandler` maps `IllegalStateException` to 400 without logging (recorded on #1065 round 3); the /invite meta-referrer second layer (recorded on #1073); three `Referrer-Policy` lines remain on the grafana/keycloak/mailpit staging Ingresses (left deliberately).
  - **RULED 2026-11-01: Klarna and Link stay on /pay** (no code change: every intent has `automatic_payment_methods`, the Element shows what the sandbox turns on). Proven on staging the same day through the owner's Chrome: a Klarna pay-later payment (`py_3UWyTMDpS9ICNeRt4EC1gCGV`, type klarna, returned through the #1072 redirect path to "Payment received") and a Link payment with the saved card (`ch_3UWyZ0DpS9ICNeRt119DMbxZ`, type link), plus the card+Link-sign-up payment between them; all three orders left DRAFT (`LIST-DRAFTS count=0`). `evidence/30-20-pay-klarna-link.txt`, screenshots `30-20-pay-klarna-received.jpg` / `30-20-pay-link-received.jpg`. Not proven: a Klarna refund, the checkout page's redirect return, the 360 px Klarna arm.


**Generated 2026-08-24; updated 2026-08-28 (nightly-E2E resolution), 2026-08-31 (customer-surface fixes), 2026-09-02 (QA council `20260902-134741` planned), 2026-09-04 (remediation recorded), 2026-09-05 (review remediated + housekeeping) 2026-09-07 (round 2 concluded, branch reconciled with main) later on 2026-09-07 (dependabot queue + architecture diagrams merged), 2026-09-22 (runtime re-proven, stack torn down for a planned pause), 2026-09-28 (state analysis, `main` green again, Phase 36 opened), 2026-09-29 (Phase 36 executed through plan 36-17) 2026-09-30 (Phase 36 merged), 2026-09-30 evening (#764 and #771 fixed and merged), 2026-10-01 (Spring Boot 4.1 spike, #648 closed), 2026-10-04 (persona testing merged as Phase 37, Spring Boot renumbered to Phase 38, `main` red on a Trivy time-bomb) and 2026-10-04 afternoon (Jackson 2.21.7, nightly 429 fix, review gate re-vendored; pg-backup red) and 2026-10-04 evening (pg-backup red cleared by #890) 2026-10-04 late (de-dup #892, orgos sync, main checkout resolved) and 2026-10-04 night (Phase 38 planned, runtime rebuilt) and 2026-10-08 (Phase 37 executing, worktrees consolidated, #903 merged), then through Phases 38, 39, 29 and 30 to 2026-11-01 (Phase 30 at 25/32; #1065, #1072, #1073, #1074, #1076 merged; Klarna and Link ruled on /pay, PR #1077). Replaces the 2026-08-18 block.** This is the only live block in this file.

**2026-11-01 delta, PR 6 (#1065, `agy/30-21-22-refund-e2e`, plans 30-21/30-22/30-24) (history, merged in from `origin/main` 2026-11-01: written on the #1065 branch before it merged as `6b3ff3e1`; superseded by the 2026-11-01 RESUME HERE block above).**
- **The branch nightly is the open item.** Its first real dispatched runs each found one defect in `e2e-nightly.yml`, each fixed on the branch:
  - `stripe listen` needs `--all-snapshot` (run 41288249740: without it stripe-cli 1.55.6 exits at once, "must specify events to forward").
  - The core-java recreate is pinned to `:9090` with a `ports: !override` file (run 41291041531: the compose range `9090-9091` let Docker move it to `:9091`; the step now asserts the published port).
  - The skip budget gates `report.json` alone when the Stripe run was skipped, and both reports otherwise, so a red step before Phase 2 no longer adds a VOID (round-1 recorded finding 9, observed on 41288249740).
  - `stripe listen --latest` (run 41293948964: the account default `2026-07-29.dahlia` vs stripe-java 34.0.0's `2026-09-30.endive` made `payment_intent.succeeded` a silent 400; staging's endpoints are pinned to endive). The step prints the forwarded API version from the CLI's Ready line.
  - The cart-identity gate runs before the Stripe recreate (C4 timed out placing a cash order once core-java ran with the CI Stripe key, run 41293948964).
- **Three 30-24 merges from the base** (`3fbc86ab` #1066, `a033dfdc` #1072, `ce9c92a1` #1073): counts regenerated with `docs-freshness.sh --write`, every CHANGELOG entry kept.
- **Next:** a green dispatched branch nightly (Stripe phase passed, STRIPE-REFUND line, events in `processed_stripe_events`, skip budget 4/4); then round 3 of the review series, which is the owner's under the 2026-10-25 ruling.

**2026-11-01 round-1 fix delta (history; superseded by the 2026-11-01 PR 6 block above).**
- A round-1 review of `1f69a7f0` admitted two findings. Both are fixed in ONE commit, **`0ebfaf66`**, which was then the **#1065 head** (the PR was a draft and still open at the time; read back then with `gh pr view 1065 --json headRefOid`). It was a fast-forward push from the detached scratch checkout, and Antigravity's worktree was not touched. The other eight findings are recorded by the orchestrator and were not changed.
  - **F1:** the refund `ALLOW` was stale, because #1065 deletes the test it exempted. It is deleted, and `MAX_SKIPS` goes from 6 to 4, the number measured in nightly 41158370941 (6 minus the deleted test's 2). Before the fix, the 4+0 two-report run was rc 1 on S-3, and a fifth skip passed under ceiling 6. After it, the same run is rc 0 and a fifth skip is rc 1 on S-1.
  - **F1 and 30-23:** 30-23's "ALLOW retired" step is **already done**. Its first branch nightly should be skip-budget GREEN at 4 of 4, not red on S-3. Any skip count other than 4 is the finding.
  - **F4:** the Stripe events check no longer pipes into `head`, which dies 141 under pipefail with no `::error`. Instead, jq picks `first(...)`. The window starts at the PaymentIntent's `created` (`created[gte]`) with `limit=100`, and a miss is an `::error` naming `has_more`.
    - The old step text died rc 141 with no output on 20000 matching events. The new text returns the first match.
    - Nightly structure: 10/10, with a new no-head arm that reads 9/10 on the old YAML.
  - Gates on `0ebfaf66` are all rc 0, and branch-behind-base reports 0 behind. actionlint finds the same 2 pre-existing SC2129 style issues at l.139 on both heads.
  - Evidence: `evidence/30-22-fix-1065.txt`, section "ROUND 1 FIXES".
- **Review series on #1065 ENDED (2026-11-01):** round 1 `/code-review` on `1f69a7f0` admitted 2 (fixed in `0ebfaf66`, 8 recorded); round 2 `/review-verify` on `0ebfaf66` admitted 0 (2 recorded) and carries `Review-Record: R-3, round 2 … on 0ebfaf66ef0f79a01cebd311f5981d77a4734772`. Both resolutions are on the PR from the owner's account. The branch is 1 behind `origin/main` (#1066; `check-changelog-contract.sh` is red on main itself for #1066's missing entry) — the merge from the base before 30-24's merge is the review gate's base exemption and needs no further round.
- **Next:**
  1. 30-23 (owner checkpoint): store the CI sandbox keys — safe now, the trace/artifact leak fix is on the branch — then a real nightly on `agy/30-21-22-refund-e2e` (`STRIPE_E2E=1` phase; skip budget expected GREEN 4/4, the "ALLOW retired" step is already done), then mark #1065 ready. Recorded round-1 items 2 (events check has no deadline loop) and 9 (skip-budget gate after a Phase-1 failure) are what that nightly may surface; fix them there as observed if it does.
  2. 30-24: merge `origin/main` into the branch (base exemption), CI green, squash-merge #1065 (the ship shape of 30-04 Task 1), watch main's nightly.
  3. Open owner items: issue #1071 fix option (a: app owns Referrer-Policy / b: separate /pay ingress / c: conditional snippet); persist the owning Stripe account on the order at intent creation (30-17, #1064 round-1 finding 2); the 360 px screenshot `evidence/30-20-pay-360.png` and the cookie banner covering Expiry/Security code; the "You've paid £X" and resume dead-end copy for UI review.
- **Antigravity:** its `-agy-p30-w21` worktree is behind again. Run `git pull --ff-only` there.

**2026-11-01 fix delta (history; superseded by the round-1 fix block above).**
- **Owner ruling 2026-11-01: "you fix it. you tackle the gaps."** Both 30-22 gaps are fixed on #1065. The pushes were fast-forwards from a detached scratch checkout; Antigravity's worktree was not touched. New head: **`1f69a7f0`** (the PR was a draft and still open at the time; read back then with `gh pr view 1065 --json headRefOid`). Commits:
  - `b7a827ac`: RED, a Jest read-back test (8/11 fail on the old rule)
  - `29d419c4`: the fix
  - `7537f8df`: merge of `origin/main` `bf98cde5` (#1068, #1067)
  - `1f69a7f0`: counts, 6778
- **30-22 is COMPLETE** (`30-22-SUMMARY.md`, evidence `evidence/30-22-fix-1065.txt`). WINDOWS 53 and 54 are fixed. **Phase 30 is 22/32.**
  1. **T-30-87, the secret in an artifact.** The probe found a second path. On a transport error, Playwright's request call log prints `Authorization: Bearer <key>` into the JSON report and `error-context.md`, so `trace: "off"` alone does not close it. The fixes:
     - Stripe calls now go through `frontend/e2e/stripe-test-api.ts`, which uses Node `fetch`, so Playwright never sees the key.
     - `test.use(STRIPE_SPEC_ARTIFACTS)` turns traces and video off.
     - The Stripe run writes its output to `$RUNNER_TEMP`, which is not uploaded. It also no longer empties the main run's `test-results/`.
     - A new `always()` step runs `check-stripe-ci-keys.sh --scan-artifacts` before the upload. It deletes any file holding the `sk_test_` or the whsec (zip members are read) and fails the job.
     - Results: probe RED 4 files, GREEN 0. The selftest is 18/18, with 2 break arms.
  2. **Exactly one refund.** `pollRefundReadback` settles only on exactly one succeeded, 100-pence refund that has a charge id, seen on two consecutive reads within 30 s. The charge's livemode is always read. The page must show `Refunds (1)`, `£1.00` and the `Refunded` badge. Jest: 11/11 GREEN.
  - Gates on `1f69a7f0` are all rc 0, including the changelog contract (green again), doc metrics 37/37, gate enforcement, both test-count oracles, gitleaks, and branch-behind-base (0 behind).
  - **Not run here, by design:** the live refund against Stripe. That is 30-23 Task 2.
- **Next, in order:**
  1. **D3 round 3 on #1065:** `/review-verify 1065`, over the fix commits only (`b7a827ac`..`1f69a7f0`; the merge brings in main). The orchestrator runs it, and round 3+ is the owner's decision under §14.4. The PR body still describes the 6-arm selftest and the `>= 1` read-back; review against the evidence file, not the body.
  2. **30-23, the owner checkpoint.** The CI keys can now be stored, because the trace fix is on the branch. Create the CI Stripe sandbox, then run `gh secret set STRIPE_TEST_SECRET_KEY` and `gh variable set CI_STRIPE_PUBLISHABLE_KEY`, then say "ci keys set". After that:
     - the branch nightly on `agy/30-21-22-refund-e2e` (S-3 red expected);
     - the ALLOW retired from the measured count;
     - nightly 2 green;
     - mark #1065 ready.
  3. Then 30-24.
- **Antigravity:** its `JToye_OaaS_2026-agy-p30-w21` worktree is behind the pushed branch. Run `git pull --ff-only` there before any further work.
- Note: 30-25 (#1067) merged on main with its own SUMMARY. This record branch (cut from `bff82097`) does not have it yet, so after main is merged, the phase count on main reads one higher than 22.
- This worktree's `feat/30-pay01-refund-e2e` holds only record commits and is NOT pushed.

**2026-11-01 adoption delta (history; superseded by the fix block above).**
- **PR 6 is #1065** (a draft at the time; it merged as `6b3ff3e1` on 2026-11-01), by Antigravity, on `agy/30-21-22-refund-e2e`, head `217003b5`.
  - It holds plans 30-21 and 30-22. Its D3 series is complete: round 1 admitted 4 and fixed 4, round 2 admitted 0, and the Review-Record is on `217003b5`.
  - The owner's ruling (2026-11-01) was to adopt it if proven. It was re-measured in a detached checkout of that head. Evidence: `evidence/30-21-22-adopt-1065.txt`.
  - This session did not push to, commit on, mark ready or post on #1065.
- **30-21 is ADOPTED** (`30-21-SUMMARY.md`), and every must-have is MET.
  - The S-5 selftest is 14/14. With the S-5 call removed, exactly the S-5 arms fail.
  - `--lint-conf` is rc 0 on the real conf and rc 1 on a bare re-added ALLOW.
  - The union probes behave: titles unioned, counts summed, a missing report VOID.
  - The ops-contracts steps run on every PR.
- **30-22 is HALTED, NOT MET** (`30-22-SUMMARY.md`, `status: halted`). There are two gaps, both on #1065:
  1. The Stripe refund spec (frontend/e2e/stripe-refund-flow.spec.ts, line 304, which exists only on #1065) asserts **at least one** refund, where the must-have says exactly one. The fix is `toBe(1)`, polled up to 30 s. The plan's "page shows Refunded" assertion is also missing.
  2. **T-30-87 (secret in an artifact):** `playwright.config.ts` has `trace: "retain-on-failure"`. The spec sends `Authorization: Bearer <sk_test_>` through APIRequestContext, and the nightly uploads `frontend/test-results/`. The repo is **PUBLIC**. Reproduced locally: a failing trace keeps the header in plaintext.
     - Fix options: `test.use({ trace: "off" })` in the Stripe spec; or make the Stripe calls with Node `fetch`, which is not traced; or keep the Stripe run's test-results out of the upload.
     - **Do not store the CI keys until this fix is on #1065.**
  - Everything else measured MET:
    - the collection switch, with the main listing losing exactly the moved test in each project;
    - the DRAFT test byte-identical;
    - the key guard at 6/6, with arms;
    - nightly structure 8/8 (main 1/8);
    - gate enforcement;
    - the Stripe CLI 1.55.6 sha256 equal to the release's `stripe-linux-checksums.txt`, with a corrupted copy refused;
    - A12: the CLI reads `STRIPE_API_KEY` from the environment.
- **#1065 is now 1 behind main** (#1068 merged at 00:17Z during the measurement).
  - It conflicts in `AGENTS.md`, `CLAUDE.md` and `docs/CHANGELOG.md`.
  - The changelog contract is red on both trees until a base merge brings in #1068's entry.
  - After the merge, run `docs-freshness --write`.
- **Next, in order (the owner's or Antigravity's, on #1065):**
  1. Fix both gaps.
  2. Merge `origin/main`.
  3. Run a D3 round on the fix commits (round 3+ is the owner's call, §14.4).
  4. Re-measure 30-22's two failed checks and re-summarize it `complete`.
  5. **Then 30-23** (owner checkpoint): create the CI Stripe sandbox, then `gh secret set STRIPE_TEST_SECRET_KEY` and `gh variable set CI_STRIPE_PUBLISHABLE_KEY`, then "ci keys set". After that come the branch nightly (S-3 red expected), the ALLOW retired from the measured count, the second nightly green, and #1065 marked ready.
  6. Then 30-24: squash-merge, deploy watch, the main nightly, and the #61 comment.
  - Branch-name note: 30-23 and 30-24 name `feat/30-pay01-refund-e2e`, but PR 6's branch is `agy/30-21-22-refund-e2e`. Dispatch with `--ref agy/30-21-22-refund-e2e`, or move the commits; the owner chooses.
  - This worktree's `feat/30-pay01-refund-e2e` holds only the record commits (30-20 and the adoption), and is NOT pushed.
- **Owner items carried forward:**
  1. Choose the #1071 fix (edge Referrer-Policy on `/pay`).
  2. Decide whether to persist the owning Stripe account on the order (30-17, round-1 finding 2).
  3. Eyeball `evidence/30-20-pay-360.png`: does it fit 360 px? The cookie banner covers Expiry and Security code.
  4. UI copy: the "You've paid £X" redirect copy, the resume-path dead-end copy, and the round-2 residual ("Payment received" shown for a garbage client secret).
  5. New: #1065's PR body says `--report` counts a skip only if every report skipped it. The code unions, which is right; the body is wrong.
  6. New: this session's A12 control printed the `stripe listen` signing secret of the account your local Stripe CLI is logged in to, read from the desktop keyring. It is not in any file. Rotate it if any local `.env` relies on it.

**2026-11-01 small-hours delta (history; superseded by the 2026-11-01 adoption block above).**
- **PR 5 (#1064, /pay) is MERGED** as `bff82097` (2026-10-31T23:01:48Z), after a two-round D3 series ending in a Review-Record on `e6df7cd3`. It is deployed: run 41250761893 succeeded, and digest parity is rc 0 for core-java, edge-go and frontend.
- **30-20 is DONE.** See `30-20-SUMMARY.md` and `evidence/30-20-pay-live.txt` §7–8. **Phase 30 is 20/32.**
  - GREEN beside RED:
    - `/pay` is 200 (was 404);
    - robots has `Disallow: /pay`;
    - the unknown-token preview returns 404 with detail "Payment link not found." (was "Resource not found"), and GET on it is 405 (was 404);
    - the OTP seam WARN appears on staging.
  - A real link was paid through `/pay` on staging: `STAGING-PAY … intent=pi_3UWm0PDpS9ICNeRt2UOAYD7l status=succeeded livemode=false account=none`, and `stripe-get` agrees. The mobile arm reads `scroll_width=360 pointer=coarse`. The mobile order was cancelled, and both link files were deleted.
  - **FAIL, open: issue #1071 (WINDOWS 52).** The edge sends `Referrer-Policy: strict-origin-when-cross-origin` on `/pay`. The frontend sends `no-referrer`, but the ingress `more_set_headers` (`k8s/base/ingress.yaml:38`, `k8s/staging/ingress-hosts-patch.yaml:56`) replaces it. A fix PR is needed, and the options are in the issue. The WINDOWS ledger blocks `/gsd-ship` until it is resolved.
- **Next: 30-21 (PR 6, refund e2e)** on `feat/30-pay01-refund-e2e` in this worktree. The branch is cut from `origin/main` `bff82097`, carries the 30-20 evidence, SUMMARY and tracking, and is NOT pushed.
  - **Before 30-21 starts:** Antigravity's `JToye_OaaS_2026-agy-p30-w21` worktree holds a parallel 30-21/22 attempt on `agy/30-21-22-refund-e2e`. Reconcile it first; do not run both.
- **Owner items:**
  1. Eyeball `evidence/30-20-pay-360.png`: does it fit 360 px, with tabs visible and no clipped controls? The cookie banner is drawn over Expiry and Security code.
  2. Choose the #1071 fix.
  3. Decide whether to persist the owning Stripe account on the order at intent creation (30-17, round-1 finding 2).
  4. UI review of the "You've paid £X" redirect copy and the resume-path dead-end copy.
  5. Round-2 residual (display-only): `/pay?payment_intent_client_secret=<garbage>&redirect_status=succeeded`, with the retrieve rejecting, still shows "Payment received".
- **Recurring flake (recorded, not filed):** Frontend E2E (public surfaces) fails on the Turbopack `next/font/google` resolve (a Google Fonts fetch on the runner). It hit PR attempt 1 and main's run for `eff54aa0`. It does not gate Deploy to Staging.

**2026-10-31 midnight delta (history; superseded by the 2026-11-01 small-hours block above).**
- **PR 5 was #1064, open at the time, from `feat/30-pay-link-page`** (it merged as bff82097) (plans 30-16 to 30-20, PAY-04 / D-03). 30-20 Task 1 is done; the plan is not complete until Task 3, so there is no `30-20-SUMMARY.md` yet. Evidence: `evidence/30-20-pay-live.txt`.
  - Merged from the base: `e310356b` (picking up #1058). `HANDOFF.md` was resolved by hand with both sides kept (checked by content).
  - OTP seam (owner decision expose-on-staging, `27ffb459`): base `"false"`, staging `"true"`, production `"false"`. Env `JTOYE_PHONE_VERIFICATION_EXPOSE_OTP_FOR_TEST`, plus a `${}` placeholder in `application.yml`. Production render: `true`=0, tag=1. Staging render: `true`=1.
  - OpenAPI snapshot (`fd6250de`): red before (rc 1), green after (rc 0). Additive only: 4 paths, 2 schemas, 3 `stripeAccountId`.
  - `.gitleaks.toml` (`6536d143`): one exact-value entry for the 30-18 fixture `pi_3018_secret_abc`. The pre-push range scan refused the first push because of it.
  - RED on today's staging: `/pay` 404 with `strict-origin-when-cross-origin`; the preview returns 404 `not-found` "Resource not found".
  - Per-PR suite all rc 0: core-java test+integrationTest (2045 + 1402), Jest 3260/3260, lint, and the script gates.
- **Next: 30-20 Task 2, the owner's.** Run the D3 review series on #1064 from the repo checkout:
  - round 1 `/code-review 1064 --comment`, with `oaas-tenancy-security` on the preview's tenant pinning;
  - a resolution comment after every round;
  - `/review-verify 1064` until a round admits zero.
  - Then squash-merge per plan 30-04 Task 1 steps 1-4, and reply "merged <sha>".
- **Then 30-20 Task 3.** Watch Deploy to Staging and the Staging Edge Probe, then check digest parity. Then the GREEN reads:
  - `/pay` 200 with `no-referrer`;
  - robots `Disallow: /pay`;
  - an unknown-token preview returns 404 with detail "Payment link not found.". Status and type alone are identical to today's route-absent 404, so they cannot tell the two apart; or GET on the preview returns 405 (404 today).
  - After that, the two `--stage pay-link` runs and the spec, as in the block below.
- **Gap found:** no gate checks that a non-optional configMapKeyRef names a key present in every render. Deleting the base key left the whole render chain at rc 0, while the local render had no key. This is recorded only, not filed.

**2026-10-31 late-night delta (history; superseded by the 2026-10-31 midnight block above).**
- **Phase 30 is 19/32.** 30-16 to 30-19 are done on `feat/30-pay-link-page`, in worktree `JToye_OaaS_2026-p30-15`. Nothing is pushed and no PR is open. **Next is 30-20, the owner checkpoint for PR 5.**
  - **30-19 (`30-19-SUMMARY.md`).**
    - `robots.ts` disallows `/pay`.
    - `next.config.mjs` sends `Referrer-Policy: no-referrer` on `/pay/:path*`, after the default. The header test asserts the order and the match.
    - `ssr-routes.conf` declares `STATIC pay/page.tsx`; the SSR gate is 44/44 PASS.
    - `scripts/staging-seed.sh --stage pay-link --link-out FILE` makes a real payable link. It sends the public OTP first and refuses `[otp-hidden]` with nothing written if staging does not return `testPreviewOtp`. Then it makes a vendor order with the phone, runs the public verify and issues the vendor link. The URL goes only to FILE, mode 600, and the stage prints `PAY-LINK order=… link=… expires=…`.
    - `frontend/e2e/staging-pay-link.spec.ts` (`STAGING_E2E=1` only) has two arms:
      - The desktop arm pays the link. It reloads after Continue and must get the card form back with one redeem. It reads the intent from Stripe on the owning account and prints `STAGING-PAY … account=…`.
      - A `@mobile-only` arm at 360 px checks for sideways scroll.
    - `staging-card-checkout.spec.ts` now reads on the owning account too.
  - Counts are 6753. Full Jest: 3260/3260. Build and tsc: rc 0.
- **30-20 must, in order:**
  1. **Merge from the base.** The branch is 1 behind `origin/main` (`eff54aa0`, #1058). `HANDOFF.md` is the one file both sides touch: keep both blocks, do not "take theirs".
  2. **Regenerate the OpenAPI snapshot.** `OpenApiSnapshotTest` is red by design, additive only.
  3. **Set the staging overlay.** `jtoye.phone-verification.expose-otp-for-test=true` on staging only (owner decision expose-on-staging).
  4. **Ship PR 5.** Push, open the PR, run the D3 review series, merge, then watch Deploy to Staging and the Staging Edge Probe.
  5. **Run the live proof:**
     - Two `--stage pay-link` runs, desktop and mobile, with the `--link-out` files in the scratchpad and never in evidence.
     - The spec, with `STAGING_PAY_LINK_FILE`, `STAGING_PAY_LINK_FILE_MOBILE` and `STAGING_STRIPE_PUBLISHABLE_KEY=pk_test_…`.
     - Record the `STAGING-PAY`, `STAGING-PAY-RELOAD` and `STAGING-PAY-MOBILE` lines. The reload line is the only proof that Next keeps the `history.state` entry across a real reload.
     - Cancel the mobile arm's unpaid order with `--stage cancel-drafts` (D-02).
- **Carried for UI review (from 30-18):** after a Stripe redirect the page says "You've paid £X. You can close this page.", which is outside the copy contract.

**2026-10-31 night delta (history; superseded by the 2026-10-31 late-night block above).**
- **Phase 30 is 18/32.** 30-16, 30-17 and 30-18 are done on `feat/30-pay-link-page`, in worktree `JToye_OaaS_2026-p30-15`. Nothing is pushed and no PR is open; plan 30-20 does the push.
  - **30-18 (`30-18-SUMMARY.md`).** `frontend/app/pay/page.tsx` (metadata only, noindex, PublicShell) and `pay-client.tsx`:
    - The page reads `#token=` and previews the link (shop, purpose heading, amount, order, London expiry).
    - "Continue to payment" checks the key, awaits `loadStripeOnce()`, and only then redeems.
    - It then calls `replaceState({...history.state, jtoyePayResume}, "", "/pay")`. The entry holds the client secret and display fields, never the token.
    - It loads Stripe on `stripeAccountId`, retrieves the intent and mounts the shared CardPaymentForm.
    - Every dead, failed and returning state is built. `/pay` without a token reads Stripe's return query, then the resume entry, and otherwise shows "Open your payment link".
    - The checkout passes `confirmation.stripeAccountId` to the loader.
  - Counts are 6750. Full Jest: 3259/3259. Build and tsc: rc 0.
  - `frontend/node_modules` was installed in this worktree with `npm ci` from the lockfile.
- **Red until 30-19:** `scripts/check-ssr-coverage-contract.sh` reports "R-1 undeclared page: pay/page.tsx". 30-19 owns `scripts/gates/ssr-routes.conf`. Declare `STATIC pay/page.tsx`; there is no `[token]` page.
- **Red by design until 30-20 Task 1:** `OpenApiSnapshotTest` (additive only, unchanged by 30-18).
- **The branch is 1 behind origin/main** (`eff54aa0`, #1058), with no file overlap. Merge from the base before the 30-20 push.
- **Resume:** 30-19.
  - robots `"/pay"`, and `Referrer-Policy: no-referrer` on `/pay/:path*` after the default.
  - The SSR-gate line.
  - `scripts/staging-seed.sh --stage pay-link`.
  - `staging-pay-link.spec.ts`. Its reload-after-Continue arm is the only proof that Next keeps the `history.state` entry across a real reload.
  - Account-aware Stripe reads in both staging specs.

**2026-10-31 late delta (history; superseded by the 2026-10-31 night block above).**
- **Phase 30 is 17/32.** 30-16 and 30-17 are done on `feat/30-pay-link-page`, in worktree `JToye_OaaS_2026-p30-15`. Nothing is pushed and no PR is open; plan 30-20 does the push.
  - **30-16 (`30-16-SUMMARY.md`).** The owner chose fragment tokens and expose-on-staging. Links are issued as `/pay#token=…`. `POST /api/v1/public/payment-links/preview {token}` reads a link without spending it, and a body-token redeem sits beside the path redeem.
  - **30-17 (`30-17-SUMMARY.md`).** `PaymentIntentResult` carries `stripeAccountId`, the connected account a direct-charge or split-deposit intent lives on (null for destination and platform intents). The confirmation (first response and replay), the balance session and the redeem all pass it on, as JSON null when it is null, never omitted. The replay now fetches a DRAFT's secret order-aware; before this it 404ed for direct charges. `expose-otp-for-test=true` refuses startup unless `stripe.environment-tag` is exactly `local`, `test` or `staging`.
  - Counts are 6710. Unit tests: 2045/0.
- **Red by design:** `OpenApiSnapshotTest` stays red on this branch until 30-20 Task 1 regenerates the snapshot. Its diff is additive only: 30-16's 4 paths and 2 schemas, plus 3 `stripeAccountId` properties.
- **The branch is 1 behind origin/main** (`eff54aa0`, #1058, toolchain gaps), with no file overlap. Merge from the base before the 30-20 push.
- **Resume:** 30-18 is the `/pay` page.
  - Read `#token=` from `location.hash`.
  - Call preview, then redeem.
  - When `stripeAccountId` is non-null, call `loadStripe(pk, { stripeAccount })`.
  - Then `history.replaceState(null, "", "/pay")`.
  - The contract is in the "Notes for later plans" sections of `30-16-SUMMARY.md` and `30-17-SUMMARY.md`.

**2026-10-31 delta (history; superseded by the 2026-10-31 late block above).**
- **PR 4 (#1046, plans 30-12 to 30-14) merged 2026-10-31 as squash `ec73ddee`.** 30-14 Task 2/3 done 2026-10-30: Connect endpoint `we_1UWN2NDpS9ICNeRtxSQMsc2M` (TEST, API 2026-09-30.endive, 7 events), its secret stored as `stripe-credentials--connect-webhook-secret` in `jtoye-stg-kv`, V1-V3, `--assign-access` and the preflight green; see `evidence/30-14-connect-endpoint.txt`. Review series 1 -> 0 -> 0 admissible.
- **#1053 (issue #1027 shop-card stretched link, plus the rabbitmq 4.3 horizon refresh) merges after #1046**, with main merged in.
- **#1053 (`d837b295`), #1055 (`e09d322e`), #1056 (`502e3383`), and #1057 (`480782d7`) merged 2026-10-31** (stretched shop-card link for issue #1027, Key Vault CSI secret-sync gate for issue #1054, and CI Testcontainers/k8s-validate/buildx speedup).
- **Phase 30 is 15/32: 30-14 and 30-15 are done** (`30-14-SUMMARY.md`, `30-15-SUMMARY.md`, `evidence/30-15-connect-live.txt`, on `feat/30-pay-link-page`). 30-15's own env/Secret verify read `secret_key=0` after a fully green deploy: core-java ran without the Connect secret, because Deployments were applied before SecretProviderClasses and the addon never resyncs a synced Secret. Repaired on staging, and fixed in CI by #1055/#1056; issue #1054 is CLOSED. Re-read on the `480782d7` pod: secret present, unsigned and forged Connect events 400 with 0 ledger rows beside a 1-row positive control, and the staging card checkout CAPTURED.
- **Resume:** 30-16 (PR 5, PAY-04 `/pay/{token}` page) on `feat/30-pay-link-page`. It opens with two owner decisions: the token in the path or the fragment, and how staging handles the OTP. Issue #1027 is still OPEN for the owner's hand-close.
- **Resume (main's copy, superseded by the line above once 30-15 ran):** 30-15, the staging proof for PR 4: pod digests equal the ghcr digests of the merge tag, and the Connect route answers a signed TEST event. Then close issue #1027 and the hand-closes listed on the PRs.
- **Watch:** RabbitMQ 4.3 EOL is 2027-01-31; the H-2 90-day warn window opens about 2026-11-02, so plan the 4.4 upgrade.

**Parallel lane, 2026-11-01: swarm storefront allergen lane (PR #1075, branch `fix/swarm-storefront-allergen`, worktree `JToye_OaaS_2026-swarm-allergen`).** Not Phase 30 work. No plan, no `.planning/STATE.md`, no migration, frontend only. It fixes seven storefront findings from the staging persona swarm (epic #964): SW-ADE-06 (card half), SW-PRY-02, SW-PRY-03, SW-JRD-10/SW-MRC-06, SW-MRC-9, SW-MRC-13, SW-CLR-13. Files: `lib/allergen-copy.ts`, `lib/product-allergens.ts`, `app/shop/[slug]/shop-detail-client.tsx`, `app/shop/[slug]/cart/page.tsx`, `components/storefront/product-detail-modal.tsx`, their Jest suites, one new suite (`__tests__/shop/storefront-bar-overlap-hero-hydration.test.tsx`), and wording or heading updates in four e2e files. Proof is in the PR: each test was RED with the pre-fix file restored from origin/main and GREEN after restore, full Jest is green, eslint and tsc are clean, and a Chromium probe showed focus scrolling honours `scroll-margin`. Still open, and needing core-java: the order-snapshot half of SW-ADE-06 (`OrderItem` keeps only the declared mask, so it needs a migration), and telling "may contain: none" from "not recorded" on the public DTO.

**Parallel lane, 2026-11-01: swarm dashboard staff lane (PR #1076, branch `fix/swarm-dashboard-staff`, worktree `JToye_OaaS_2026-swarm-staff`).** Not Phase 30 work: frontend only, no plan, no `.planning/STATE.md`, no migration, no core-java change. It fixes eight 2026-10-19 swarm findings (epic #964), each re-checked on main 6b3ff3e1 first. SW-TND-05: the nav items are gated by role from staff/me `shopRoles`, in the sidebar and the More sheet. SW-ADE-11: a Staff invite or grant starts on the selected shop and needs one. SW-ADE-10: orders search. SW-ADE-08/SW-TND-07: the VAT tile states. SW-TND-12: 44px Shops row actions on a phone. SW-ADE-02: the image dialog leaves Processing. SW-ADE-09: the 409 in-use delete copy. SW-ADE-13: plain copy on the finance savings card. SW-TND-08 (hiding customer email from STAFF) is SKIPPED: it conflicts with owner ruling option B, and the API would still send the email. Files: `frontend/components/dashboard/{sidebar,mobile-tab-bar,merchant-roi-card}.tsx`, `frontend/components/dashboard/staff/invite-staff-form.tsx`, `frontend/components/ui/{icon-button,image-uploader}.tsx`, `frontend/app/dashboard/{page,orders/page,products/page,shops/page,staff/page}.tsx`, `frontend/lib/media-api.ts`, `frontend/types/api.ts`, plus tests, `docs/CHANGELOG.md`, this block and the doc counts. Proof: every new test went RED against origin/main's file and GREEN restored (table in #1076). The full Jest suite, eslint on every touched file and `tsc --noEmit` are clean. No browser run against a live stack was done.

**Parallel lane, 2026-11-01: swarm public pages lane (PR #1080, branch `fix/swarm-public-pages`, worktree `JToye_OaaS_2026-swarm-public`).** Not Phase 30 work: frontend only, no plan, no `.planning/STATE.md`, no migration, no core-java change. It fixes six 2026-10-19 swarm findings (epic #964), each re-checked on main 22e6404f. SW-SAM-12: new `/legal/terms`, compiled only from copy already published, with the checkout's seller, cancellation and no-VAT strings imported. **Owner sign-off on the terms wording is needed before merge.** SW-CLR-04: new `/contact`, with `NEXT_PUBLIC_CONTACT_EMAIL`/`_PHONE` empty by default (not in `ci-cd.yaml` build-args yet, like the data-protection email). SW-CLR-06: a terms link on `/for-operators`. SW-CLR-03 PARTIAL: "go live in a day" removed, positioning kept per `design/brief.md` (owner decision open). SW-CLR-05: strategy pages unlinked and noindexed (sitemap entry is Phase 30's follow-up). SW-CLR-08 PARTIAL: app-wide `metadataBase`, no og:image (no brand asset exists). Files: `frontend/app/{legal/terms,contact}/page.tsx`, `frontend/app/{layout,page}.tsx`, `frontend/app/legal/page.tsx`, `frontend/app/{business-model-guide,competitive}/page.tsx`, `frontend/components/public/public-footer.tsx`, `frontend/components/marketing/operator-pitch.tsx`, `frontend/lib/{company,pricing,env-validation}.ts`, `frontend/Dockerfile`, `frontend/.env.local.example`, three new Jest suites and four edited ones; outside frontend `.env.example`, `docker-compose.full-stack.yml`, `scripts/gates/{ssr-routes,object-store-residue-allowlist}.conf`, doc counts, `docs/CHANGELOG.md`, this block. Proof: every new test was RED against origin/main's file and GREEN restored (table in #1080). Full Jest, tsc and eslint are clean, and the count oracle passes for jest. Live `next dev` on :3204 showed absolute canonicals and `noindex` on the strategy pages, with no overflow at 375px.

**Parallel lane, 2026-11-01: swarm contact build-args lane (PR #1081, branch `chore/swarm-contact-build-args`, worktree `JToye_OaaS_2026-swarm-contact`).** Not Phase 30 work: no plan, no `.planning/STATE.md`, no migration, no core-java change. Two owner rulings of 2026-11-01 (SW-CLR-04 / SW-CLR-06, epic #964). (1) `ci-cd.yaml` passes `NEXT_PUBLIC_DATA_PROTECTION_EMAIL`/`_CONTACT_EMAIL`/`_CONTACT_PHONE` as frontend build args from `vars.FRONTEND_PUBLIC_DATA_PROTECTION_EMAIL`, `vars.FRONTEND_PUBLIC_CONTACT_EMAIL`, `vars.FRONTEND_PUBLIC_CONTACT_PHONE`. **Owner action: set those three repository variables**; unset builds to the old empty value, and the page says "not yet published". No deploy guard is added (empty is valid). The env-contract gate reads only the Dockerfile ARGs, so a dropped CI build-arg line would pass silently (recorded, not filed). (2) The card-fee line on `/for-operators` and `/legal/terms` links `STRIPE_UK_PRICING_URL` (`frontend/lib/pricing.ts`, https://stripe.com/gb/pricing), and no rate is printed. Files: `frontend/lib/pricing.ts`, `frontend/components/marketing/operator-pitch.tsx`, `frontend/app/legal/terms/page.tsx`, three edited Jest suites; outside frontend `.github/workflows/ci-cd.yaml`, `docs/{FAILURE_MODES,SITEMAP,CHANGELOG}.md`, doc counts, this block. Proof: 4 new cases RED vs origin/main's sources, GREEN restored; full Jest green through the count oracle; env-contract break arm (ARG removed) rc 1, restored rc 0.

**2026-10-25 late delta (history; superseded by the 2026-10-31 block above).**
- **Phase 30 is executing: 13/32 plans done.** PR 3 (issue #108, Stripe half) merged as #1044, squash 9d204778, and was proven on staging by 30-11: pod digests equal the ghcr digests of that tag, and the new startup line reads `connectTimeoutMs=5000 readTimeoutMs=20000 maxNetworkRetries=2`. #1045 (the bundle of four finished Antigravity branches) merged after it as 5fa3a955.
- **PR 4 is #1046 (open at the time; merged 2026-10-31), from `feat/30-connect-webhook`** (plans 30-12, 30-13, 30-14). It holds the Connected-accounts webhook route, the per-overlay `stripe.environment-tag`, the Express capabilities and the Key Vault, SPC and env wiring. See `30-12-SUMMARY.md`, `30-13-SUMMARY.md` and `evidence/30-14-connect-endpoint.txt`.
- **Do not merge PR 4 until `stripe-credentials--connect-webhook-secret` exists in `jtoye-stg-kv`.** The SecretProviderClass names that object, so the CSI mount fails without it.
- **Owner actions pending (30-14 Task 2):**
  - Register a Connected-accounts webhook endpoint in Stripe TEST, pinned to API version 2026-09-30.endive, at `https://api-staging.olajay.co.uk/api/v1/public/payments/webhook/connect`.
  - Store its signing secret with `scripts/staging-secrets.sh --set-external stripe-credentials--connect-webhook-secret` (hidden prompt).
  - Reply with the endpoint id.
  The read-only list taken before the checkpoint shows one TEST endpoint only, `we_1USBcpDpS9ICNeRt3kIwcIvC` at the platform URL, so step 0 (delete or retarget extras) has nothing to act on.
- **Resume:** 30-14 Task 3 verifies the endpoint, the Key Vault object, `--assign-access` and the preflight. Then 30-15 runs the review series and the merge.

**2026-10-25 delta (history; superseded by the 2026-10-25 late block above).**
- **Phase 30 is executing: 10/32 plans done.** Merged and deployed on 2026-10-25:
  - PR #1041 (issue #1038: a stale card DRAFT's PaymentIntent is cancelled before its order is deleted), squash b728254e.
  - PR #1043 (30-09, D-02 done: 6 orphaned TEST intents cancelled at Stripe and read back `canceled`, `livemode=false`), squash 201ac06e.
  - Both main runs were green through Deploy to Staging and the Staging Edge Probe. The #1043 run's Frontend E2E failed once on a Google Fonts fetch and passed on rerun, with no code change.
- **Plan 30-10 (issue #108, Stripe half) is done on `fix/108-stripe-timeouts`, and PR 3 is that branch.** The Antigravity session built it in worktree `-agy-p30-w10` (`agy/30-10-stripe-timeouts`) and never pushed it. This session adopted it and re-measured it; the original evidence could not be reproduced. See `30-10-SUMMARY.md` and `evidence/30-10-108-tdd.txt`.
- **Resume:** plan 30-11.
  - Task 1 (staging RED read, counts 6636, PR open) is done or in progress on this branch.
  - Task 2: D3 review series, then squash-merge.
  - Task 3: watch the deploy, read `Stripe client configured (key redacted) connectTimeoutMs=5000 readTimeoutMs=20000 maxNetworkRetries=2` from the new pods, and comment on issue #108.
- **Other Antigravity work, still local, with no PRs, on branches each one commit behind main:**
  - `fix/1016-1011-963-frontend-swarm`
  - `fix/1007-1008-1009-ops-gates-and-gitleaks`
  - `fix/992-render-invariants-pyyaml`
  - `feature/synthesis-governance-gate`

  The plan is one integration PR with one review round, after PR 3 is merged and deployed.
- **Staging residue:** C97DCE79's order is CONFIRMED and unpaid, and its intent is still payable (issue #1031 class). `DEPLOY_STAGING_ENABLED` is still true.

**2026-10-24 delta (history; superseded by the 2026-10-25 block above).**
- **Phase 30 is executing: 8/32 plans done. PR 1 (#1029, 43d047fb) and PR 2 (#1030, issue #958, squash 7b117162) are merged, deployed and proven on staging.** Plan 30-08's record: `.planning/phases/30-live-stripe-test-mode-execution/30-08-SUMMARY.md` and `evidence/30-08-958-live.txt`.
  - Live on staging 2026-10-24: a fresh unpaid card DRAFT's SUBMIT is refused 409 `https://jtoye.uk/errors/awaiting-payment` and stays DRAFT (then cancelled, intent canceled); a vendor NONE DRAFT submits 200; the paid ORD-1AAC0CDE-20261022-D69764AE confirms 200. Pod digests equal the ghcr digests of tag 7b117162 for core-java, edge-go and frontend.
  - Review: round 1 admitted 4 (3 fixed, 1 filed as issue #1031), round 2 admitted 0.
- **Found during the live gate, issue #1038:** `ScheduledCleanupService.cleanupStaleDraftOrders` (03:00Z daily) deletes DRAFTs older than 24 h, so the 30-03 DRAFT ORD-…0C094A2C and the six 2026-10-19 swarm DRAFTs are gone while their 7 PaymentIntents stay `requires_payment_method` in Stripe TEST, payable with no order.
- **Resume:** plan 30-09 (D-02 hand-cancellation, owner-gated). Its route assumed those DRAFTs exist; `--stage cancel-drafts` looks the order up first, so it has nothing to act on. Re-route it (cancel the 7 intents in Stripe TEST by id, and decide whether issue #1038 is fixed first) before running it. Branch `fix/108-stripe-timeouts` is cut from main 7b117162, carries the 30-08 SUMMARY and evidence, and is not pushed.
- **Staging kubectl:** kubelogin's `azurecli` mode follows the ambient az default, so mint the AKS token pinned to the JToye subscription and pass it: `kubectl --context jtoye-stg-aks --token "$(az account get-access-token --subscription c483d353-5f61-4587-a790-addb9ab5fb94 --resource 6dae42f8-4368-4678-94ff-3960e28e3630 --query accessToken -o tsv)" …` (for `scripts/staging-seed.sh`, a PATH shim that adds that flag). `DEPLOY_STAGING_ENABLED` is still true.

**2026-10-22 delta (history; superseded by the 2026-10-24 block above).**
- **Phase 29 is complete.** The close is #1002: `29-VERIFICATION.md` passed 5/5, `29-SECURITY.md` closed, R-3 accepted by the owner on 2026-10-21 (staging only, conditional on the generated edge credential).
- **Phase 30 ("The Money Path, Executed") is executing: 7/32 plans done, PR 1 of 8 complete (#1029, merged as 43d047fb), PR 2 (issue #958, plans 30-05 to 30-07) open for review as #1030 (https://github.com/Bralabee/JToye_OaaS_2026/pull/1030).**
  - **Resume:** plan 30-08 Task 2 is the owner's: run the D3 review series on PR #1030 to a zero-admissible round, then squash-merge it (plan 30-04 Task 1 steps 1-4: head equals the PR head, branch-behind rc 0 immediately before merging, squash message scanned, `gh pr merge --squash --body-file`). Then `/gsd-execute-phase 30` resumes 30-08 Task 3: watch the merge's deploy, digest parity, the live 409 on the unpaid DRAFT, the vendor NONE DRAFT and paid-order controls, on a new branch `fix/108-stripe-timeouts` cut from the merged main. Branch `fix/958-awaiting-payment-gate` (cut from main 43d047fb) carries 30-04's record and the 30-05, 30-06 and 30-07 commits; nothing is pushed to it after the review starts. The single resume source is `.planning/STATE.md` on that branch.
  - **PR 2 pre-open gates (2026-10-22, at 58db5b95), each rc 0:**
    - core-java: unit 1947 and integration 1369 tests, 0 failures;
    - full Jest 3207/3207;
    - lint, 0 errors;
    - `staging-seed.sh --selftest`;
    - env-contract, gate-enforcement, docs-freshness, doc-metrics and branch-behind-base.
  - **gitleaks over the branch range:** it was rc 1 on 30-07's own break-arm record (09beb869 quotes the stub's synthetic client secret). That commit came after 30-07's scan. A commit-pinned `.gitleaksignore` fingerprint brings it to rc 0.
  - **30-05 done (server half of #958):** SUBMIT, CONFIRM and START_PREP of an order still awaiting its card or link payment answer 409 `https://jtoye.uk/errors/awaiting-payment` ("Awaiting payment", UI-SPEC detail, no `#`) and write nothing; vendor/REST/MCP orders with paymentStatus NONE are unchanged (D-01). `payment_intent.succeeded` now moves only a DRAFT; a paid CANCELLED/REFUNDED/voided order logs `WARN event=payment_on_closed_order`. Full core-java suite green at 791b7a16; counts 6562. See `30-05-SUMMARY.md`.
  - **30-06 done (staff half of #958):** an "Awaiting payment" badge (`isAwaitingPayment`, the server rule transcribed, absent status with a reference counts) on Orders-list rows and on New-lane and grid cards; the Orders list offers no Submit/Confirm for such an order, and every DRAFT now offers Cancel (D-02); the kitchen grid toasts "Order not updated" with the server's detail (or a named-action fallback with no response) and the New lane shows the detail in the card alert. Frontend jest 3207/3207, `npm run build` rc 0, counts 6593. `frontend/node_modules` was restored with `npm ci` (it was stale against the lock; the 30-01 deferred item is resolved). See `30-06-SUMMARY.md`.
  - **30-07 done (D-02 hand tools; built and selftested against stubs only, no live call):** `scripts/staging-seed.sh` gained five stages: `--stage list-drafts` (read-only DRAFT listing, a jq projection with no customer field), `vendor-order` (one vendor NONE DRAFT, the D-01 control 30-08 needs), `order-transition --orders ORD-x --event submit|confirm|start-preparation` (prints `TRANSITION … http= type= detail=`), `cancel-drafts --orders ORD-a,ORD-b` (per order: refused `[not-draft]` unless a card DRAFT awaiting payment, refused `[paid]` for a succeeded/processing/requires_capture intent, the intent cancelled in Stripe TEST and read back canceled BEFORE the vendor cancel and its CANCELLED read-back; no sweeper) and `stripe-get --path /v1/… [--account acct_…]` (allowlisted, TEST-only, client secret and personal fields removed; refund and account objects have no livemode field and print `LIVEMODE n/a`). Selftest 20 -> 41 arms, every new arm RED first, 7 break arms red. gitleaks over the branch range is rc 0 after three commit-pinned fingerprints in `.gitleaksignore` (one 30-07 fixture line, two 30-05 evidence lines holding synthetic `key=30-05-<uuid>` Idempotency-Keys). See `30-07-SUMMARY.md`.
  - **PR 1 (issue #957) merged as #1029 (43d047fb), deployed (run 39803113150) and proven live by 30-04.** The live checkout chunks carry one `pk_test_` key (rc 0, rc 1 before), all three services pass digest parity at 43d047fb, and one TEST card payment was captured and read back from Stripe (`pi_3UTPKkDpS9ICNeRt0VWSmyrM`, `livemode` false). The evidence is on issue #957, now CLOSED (2026-11-01).
  - **Unplanned prerequisite:** staging's ingress CORS omitted `Idempotency-Key`, so every browser order POST was refused (issue #1025). It was fixed by #1026 (4208b08e, INV-24, `scripts/check-cors-preflight.sh`, live preflight rc 0).
  - **The repository variable `FRONTEND_PUBLIC_STRIPE_PUBLISHABLE_KEY` is set** (pk_test_, the pair of the Key Vault secret `stripe-credentials--api-key`). The staging guard is rc 0 and the production guard refuses it (D-08).
  - **Staging residue:** the paid TEST order ORD-1AAC0CDE-20261022-D69764AE, and the unpaid DRAFT ORD-1AAC0CDE-20261022-0C094A2C for 30-09 to cancel (with `staging-seed.sh --stage list-drafts`, then `--stage cancel-drafts --orders …` behind the owner's checkpoint).
  - **Owner items from 30-04:** the sticky storefront header overlaps the card form in the full-page screenshot, and the focus ring of a focused card field is still to be judged by eye.
  - **Traps hit this session:**
    - A merge resolved with a whole-file `git checkout --ours` dropped the other side's non-conflicting doc version claims, and `check-doc-versions` caught it.
    - A CI watch that read a GraphQL error as "no pending checks" reported green early. Watch loops must count real check rows.
    - Local `awk` is mawk while CI's is gawk.
    - This shell's default kube context `jtoye-staging` is dead; always pass `--context jtoye-stg-aks`.
- **Merged since the 2026-10-21 block:**
  - #1002, the Phase 29 close;
  - #1004, CI skips integration tests, images and deploys for documentation-only changes;
  - #1018, the Phase 30 plan;
  - #1019, an integration of #1005 (deploy.sh waits on every workload; issues 969, 970, 999) and #1006 (Mailpit cert reload; issue 972). Both source PRs are now CLOSED unmerged, because their reviewed heads are ancestors of #1019's head and #1019's tree is main's f858d5cc;
  - #1021, an authorization fix: shop-child updates now authorise a changed shopId;
  - #1022, an integration of the second swarm bundle (a product must name its shop, the allergen label's 422, STAFF refused the shop-admin controls; issues 959, 960, 961);
  - #1026, the ingress CORS fix (issue #1025);
  - #1028, the Dependabot rollup #993 to #998 plus the wordmark link name (merged from another session);
  - #1029, Phase 30 PR 1 (issue #957).
- **At 87a1f772 (#1022), `ci-cd.yaml` passed, including Deploy to Staging and the Staging Edge Probe, so staging runs that commit.** `docs-freshness` FAILED at that sha (run 39669304589) on H-3 of `scripts/check-handoff-contract.sh`: HANDOFF.md was 4 merged commits behind main, over its budget of 3. Updating this file clears that.
- **Still open, none blocking Phase 30:** follow-ups #992 and #971, the Grafana 24 h restart watch, `29-VALIDATION.md` still `draft`, and issue #1027 (the `/shop` card link's axe 4.14 design exception). The six Dependabot PRs #993 to #998 were rolled up and merged as #1028.
- **Merge discipline for Phase 30.** `DEPLOY_STAGING_ENABLED=true`, so every merge to main redeploys staging (about 75 to 95 min of CI). Merge one PR at a time and watch each deploy to green before the next. Never merge while the owner's persona swarm is running: ask the owner first. If a deploy goes red, set the variable to `false`, read it back, and stop.
- **Keep this file current during Phase 30.** H-3 lets HANDOFF.md fall at most 3 merged commits behind main, and Phase 30 merges 8 PRs. It counts from the PR's own copy, so each Phase 30 PR should update this block (the plans done, the next plan). Otherwise `docs-freshness` goes red on the 4th merge.
- **Unchanged:**
  - kube context `jtoye-stg-aks` only;
  - Python gates under `conda activate jtoye-ops`;
  - Azure always with `--subscription c483d353-5f61-4587-a790-addb9ab5fb94`.

**Parallel lane, 2026-10-22: the Dependabot rollup (branch `chore/dependabot-rollup-993-998`, worktree `JToye_OaaS_2026-deps`).** Not Phase 30 work; it touches no plan, no `.planning/STATE.md` and no migration. It consolidates the six open Dependabot PRs (#993 to #998) the way 39-10 did (`.planning/phases/39-platform-runtime-upgrades/39-10-SUMMARY.md`), fixes one defect the frontend bump revealed (the wordmark link's name omitted its visible "J" on four surfaces, WCAG 2.5.3) and takes axe-core 4.14, which turns `label-content-name-mismatch` on by default: the `/shop` card link (UXT-082, named by `aria-labelledby`) fails it on every viewport, which is the owner's design call, issue #1027, so `public-a11y.spec.ts` excludes that one rule on that one element (`DESIGN_EXCEPTION_1027`, asserted to fire on `/shop`) and nothing else. Merge it only when main's pipeline is idle and the Phase 30 session is not between a PR open and its staging proof: `DEPLOY_STAGING_ENABLED=true` means this merge redeploys staging too.

**Parallel lane, 2026-10-24/25: graph tooling plan (#1032, #1033, #1034) and two unblockers (#1037, #1039).** Not Phase 30 work; no plan, no `.planning/STATE.md`, no migration, no runtime code. #1039 moves `mcp-server` to body-parser 2.4.0 and zod 4.6.7, with floors in `package.json`, because two new HIGH advisories had turned the required Security Scan red on every PR. #1037 carries #1030's three gitleaks fingerprints to its squash commit `7b117162`: they were pinned to branch commits, so main's gitleaks run was red and pre-push P-3 refused every branch merged with main. #1032 sets GSD's `graphify.enabled` to false. #1033 binds the core-java diagram's counts (33 controllers, V88) to `scripts/gates/claims.manifest` and adds `scripts/note-diagram-evidence-drift.sh`, a notice that never blocks; it is named `note-`, not `check-`, so H-1's gate count stays at 61. #1034 adds `scripts/graph.sh` (code-structure questions answered from a graph rebuilt from `git archive HEAD` and refused when the graph cannot answer; graphify pinned at 0.9.65 in `scripts/graph.conf`). Its acceptance arms are `scripts/test-graph-sh.sh`, which needs graphify installed and so does not run in CI. Kill date: merge + 28 days, unless it is still being used. None of those merges deployed: the core-java image gate failed on each (the brotli CVE, see the block below), so #1034 merges only after #1040's staging deploy is green.

**Parallel lane, 2026-10-25: the core-java brotli CVE (branch `fix/core-java-brotli-cve`, worktree `JToye_OaaS_2026-brotli`).** Not Phase 30 work. main's `ci-cd.yaml` went red on 0f559fc2 (#1039), c519e689 (#1037) and f3381c9a (#1032) at the core-java Trivy image gate: a new advisory, CVE-2026-111497, against `brotli-libs` 1.2.0-r1 in the eclipse-temurin:25-jre-alpine base, fixed in 1.2.1-r0. No deploy ran, so staging still runs the last green commit. This branch adds `brotli-libs` to the named `apk upgrade` in `core-java/Dockerfile`, as with libexpat, p11-kit and OpenSSL before it. The fourth red run, f51cd607 (#1033), failed instead in the integration suite: `WebhookAutoPauseIntegrationTest` (2 of 1369) after its Testcontainers Postgres refused connections; the same suite passed on the three runs before it, so it is recorded as a flake, not fixed. Still queued in this lane after this merges: #1034 (`scripts/graph.sh`), reviewed to admissible 0. Merge discipline is unchanged: one merge at a time, each staging deploy watched to green.

**Parallel lane, 2026-10-30: issue #1027 and the Antigravity re-proof (#1053, branch `fix/agy-integration-1027`, worktree `JToye_OaaS_2026-integration`).** Not Phase 30 work; frontend only, no plan, no `.planning/STATE.md`, no migration. Both shop cards now use the stretched link (owner ruling 2026-10-22, option 1) and `DESIGN_EXCEPTION_1027` is gone from `e2e/public-a11y.spec.ts`. The four local Antigravity branches were NOT merged: #1045 had already landed them with their review fixes, so merging would only have brought back pre-review code and the synthesis-gate hooks the owner declined. Each of their fixes was re-proven on main instead (red with the pre-fix file, green restored); the record is in #1053. Issues #1007, #1009, #992, #1011, #1016 and the synthesis gate are proven; #1008's quoted staging series still needs a re-measure on the pod, and #963 still lacks its Playwright request count and quiet-estate re-measure.

**2026-10-21 delta (history; superseded by the 2026-10-22 block above).**
- **Phase 29.** Merged to main as #984; all 29 plans have a SUMMARY. Still to do: phase verification (gsd-verifier) and `29-SECURITY.md`.
- **Staging deploys from CI.** `DEPLOY_STAGING_ENABLED=true`, so every merge to main runs "Deploy to Staging" (about 75 min of CI). Merges go one at a time, each deploy green before the next. If a deploy goes red: set the variable to `false`, read it back, stop.
- **Since #984:**
  - #986, digest parity waits for old replicas to drain;
  - #987, Grafana memory 320Mi/512Mi;
  - #991, 29-28 close-out records;
  - #988, Keycloak `KC_PROXY_TRUSTED_ADDRESSES=10.244.0.0/16` plus render invariant INV-20, proven live 2026-10-21: a spoofed `X-Forwarded-Host` from an untrusted peer no longer moves `token_endpoint`, and the staging-login spec passed 2/2.
- **Queue after this block:**
  - Grafana edge basic auth (INV-21), whose Key Vault objects were already written from its branch;
  - the CI-hardening batch (selftests wired, action pins, the `staging-edge-probe` job).
- **Resume document:** `.planning/phases/29-deployable-staging-with-its-own-monitoring/29-ORCHESTRATOR-LEDGER.md`.
- **Unchanged:**
  - kube context `jtoye-stg-aks` only;
  - Python gates under `conda activate jtoye-ops`;
  - Azure always with `--subscription c483d353-5f61-4587-a790-addb9ab5fb94`.

**2026-10-19 delta (history; superseded by the 2026-10-21 block above).**
- Phase 29 is executing on branch `phase-29-deployable-staging`, pushed, NOT merged to main. 25/29 plans have a SUMMARY.
- A NEW live Azure staging estate exists: AKS `jtoye-stg-aks` (4×B2s), PG `jtoye-stg-pg`, KV `jtoye-stg-kv`.
- Staging is persona-ready at https://app-staging.olajay.co.uk, and its monitoring pages ola.ibitoye@olajay.co.uk.
- The single resume document is `.planning/phases/29-deployable-staging-with-its-own-monitoring/29-ORCHESTRATOR-LEDGER.md`, section "END OF DAY 2026-10-19". It holds every in-session owner ruling (D-33, D-34, Stripe test writes, the backup drill, lock-after-verified-backup) and the ordered next steps:
  1. 29-20 backup check, then the owner's WORM lock, after 02:00Z.
  2. 29-19 cost check, from ~10:00Z.
  3. 29-27 close-out.
  4. 29-28 merge, with CI's first staging deploy.
- Start the session with `/gsd-execute-phase 29`.
- Do not deploy staging while the owner's persona swarm runs.
- Kube context is always `jtoye-stg-aks`; current-context `jtoye-staging` is dead.
- Python gates and owner-run scripts need `conda activate jtoye-ops`.

**2026-10-17 evening delta (history; the consolidation delta below is older history).** Merged after it: #947 (Phase 37 owner decisions), #950 (ledger reconcile), #948 (GTM track 2: culinary pack seeder, 62mm thermal PPDS labels, slot/settlement UI), #949 (Phase 30, `V88`: billing tiers, Connect payouts, phone OTP, payment links; built outside GSD plans, `30-VERIFICATION.md` only) and #951 (ledger records Phase 30 as PARTIAL: PAY-02/PAY-04 evidenced but unticked pending owner acceptance; PAY-01 open because `vendor-refund-flow.spec.ts` is still `ALLOW` in `scripts/gates/e2e-skip-budget.conf`; PAY-03 open because its criterion says "on staging").

- **Local Postgres upgraded 15 → 18.** Phase 39 changed compose to `postgres:18-alpine` mounted at `/var/lib/postgresql` but never migrated this machine's dev volume. The old PG 15 container kept running until `scripts/sync-runtime.sh` (`--force-recreate` also recreates dependencies) replaced it, and PG 18 then refused the data. Fixed by `pg_dumpall` from a temporary `postgres:15-alpine`, re-init on PG 18 without `initdb.d`, and restore. Per-table row counts, `pg_policies`, RLS/FORCE flags, functions and roles were identical (planted-diff control caught). Backup volume `jtoye_oaas_2026_postgres_data_pg15_backup_20261017` is KEPT until the owner rules. **Any other machine with a pre-Phase-39 volume hits the same thing:** read the volume's `PG_VERSION` before recreating.
- **DEF-29-6 (STARTTLS) fixed by #952.** `application.yml` spelled `mail.smtp.starttls.enabled`, but JavaMail reads `.enable`, so STARTTLS was off for every real SMTP server. Unit test RED/GREEN; a live invite reached Mailhog on the rebuilt runtime.
- **Phase 29 salvage audit (git-only).** All ten executed plans on `phase-29-research` are BRANCH-ONLY. A trial merge gives 23 non-`.planning` conflicts, every image pin is behind main, and 29-11..16 assume the retired object store, PG16 and old line numbers. Verdict: **re-plan Phase 29 on main** (`/gsd-discuss-phase 29`), carrying named scripts and decisions D-01..D-10, D-13, D-16..D-19 by hand, minus D-11/D-12 (superseded by the 2026-09-28 Azure Blob ruling). ADR-0002 "Accepted" exists only on the branch.
- **Azure (read-only, `--subscription c483d353…`):** `jtoye-staging-aks` Stopped, `jtoye-staging-pg` (PG16 B2s) Stopped, Redis gone; public IPs and the load balancer remain. Azure auto-starts a stopped PG flexible server after 7 days. The cost is not quantified; keep or delete is the owner's call.
- **Worktrees:** `-agy-phase30` and the merged branches were removed. `-agy-synthesis-gate` (unpushed `feature/synthesis-governance-gate`) is LEFT WITH ANTIGRAVITY by owner ruling.
- **Next:** merge #952 after its review series → small PR landing ADR-0002 acceptance on main → `/gsd-discuss-phase 29` on main.
- **Public marketing surfaces refreshed (2026-10-19, outside GSD, PR #965 and #966).** `/for-operators` now states the October 2026 position (both rhythms, V88 pricing from one `PRICING` const, one caveat list), the July pilot-pack PDF is gone with a 308 redirect to `/for-operators`, and `/business-model-guide` and `/competitive` are reconciled to the same sources (#966, with a shared `frontend/lib/pricing.ts`). A redesign of `/for-operators` (five sections, product images from route-mocked captures, fit check removed) follows on `feature/operators-redesign`. None of this touched `.planning/`, the Phase 29 branch, the shared containers or staging.

**2026-10-17 consolidation delta: Phase 37 (59/59 plans, UAT 15/15, verification passed, `quick-261016-edv` forced-light dashboard theme), Phase 39 (10/10 runtime upgrades, `active-exemptions=0`), and `ADR-0007` (`GAP-GTM-01..04, 06, 07B`, Schema `V87`) are all merged to `main` and consolidated. Next roadmap phase: Phase 29 (note: its paused body lives on branch `phase-29-research`; do not plan it blindly on `main`). Resume here; the blocks below it are history.**

- **GAP-GTM-04 & GAP-GTM-06 (PR 944 MERGED `7b4d327d`).** Extends `PaymentService` with `createBalancePaymentIntent` (`payment_stage=BALANCE`, rejecting `DRAFT`/inactive orders) and balance-stage webhook settlement (clears `balance_due_pennies` to `0`, preserves `CONFIRMED`/`PREPARING`/`READY` status, writes the full-order `SALE` ledger row, preserves captured deposit status/reference on `payment_failed`, and passes connected-account `RequestOptions` to `Charge.retrieve`), exposes `GET /api/v1/orders/{id}/balance-payment-link` (`OrderController`) and `POST /api/v1/public/orders/{orderNumber}/balance-payment-intent` (`PublicStorefrontController`, verified under `orders_guest_tracking` RLS), and adds `GET /api/v1/shops/{id}/whatsapp-preorder-link[?slotId=...]` (`ShopController` / `ShopFulfilmentSlotService` with active/cutoff/capacity validation).
- **`GAP-GTM-02` & `GAP-GTM-03` Frontend UI (`feature/gtm-02-03-cooksheet-slots-ui`, PR 945 MERGED `ce742ec8`).** Consumes the `V87` (`ADR-0007`, PR 943) backend: adds `KdsCookSheetPanel` + `KdsScheduledWindowBadge` (`frontend/components/dashboard/kitchen/kds-cook-sheet-panel.tsx`, `frontend/lib/kitchen-orders-api.ts`, `frontend/components/dashboard/kitchen/kitchen-route-calls.ts`) behind `data-testid="kds-cook-sheet-toggle"` on `/dashboard/kitchen` with date/slot filtering, aggregated dish quantities, V63 allergen badges, V73 allergy alerts, and print export; and adds `FulfilmentSlotPicker`, `isCheckoutBlockedByAvailability`, and `describeSlotCheckoutError` (`frontend/components/storefront/fulfilment-slot-picker.tsx`, `frontend/app/shop/[slug]/checkout/page.tsx`, `frontend/lib/checkout-idempotency.ts`) on `/shop/[slug]/checkout` with `fulfilmentSlotId` idempotency fingerprinting, closed-hours pre-order allowance, and RFC 9457 slot error mapping, covered by 14 new Jest unit tests across `kds-cook-sheet-panel.test.tsx` and `fulfilment-slot-picker.test.tsx`.
- **ADR-0007 (`GAP-GTM-01`..`03`, `V87`, PR 943 MERGED `11d408f6`).** Implements `V87__scheduled_fulfilment_slots_and_commercial_profile.sql` (`shops.fulfilment_timing_mode`, `shops.settlement_policy_mode`, `shops.deposit_bps` + `shops_aud` mirrors; `shop_fulfilment_slots` with `ENABLE + FORCE ROW LEVEL SECURITY`, `shop_fulfilment_slots_tenant_isolation` + `shop_fulfilment_slots_public_read`, proven under `NOSUPERUSER NOBYPASSRLS` by `ScheduledFulfilmentSlotRlsIntegrationTest`; `orders.fulfilment_slot_id`, `scheduled_for_date`, `scheduled_window_label`, `deposit_amount_pennies`, `balance_due_pennies` + `orders_aud` mirrors + `idx_orders_shop_scheduled_date`), `StripeConnectService.resolveSettlementRouting` + `PaymentService` direct-charge/deposit-split routing (`GAP-GTM-01`), `ShopFulfilmentSlotService` + `PublicStorefrontService` atomic slot capacity reservation (`GAP-GTM-02`), and `OrderCookSheetService` (`GET /api/v1/orders/cook-sheet`, `GAP-GTM-03`).
- **`GAP-GTM-07` Part B — Merchant Value & ROI Telemetry Card (PR 942 MERGED `1061f4c7`).** Ships `frontend/lib/finance-roi.ts` and `frontend/components/dashboard/merchant-roi-card.tsx` wired into `/dashboard/finance` (`frontend/app/dashboard/finance/page.tsx`, `frontend/lib/finance-api.ts`) with 8 new Jest unit & page-integration tests (`frontend/app/dashboard/finance/__tests__/merchant-roi-card.test.tsx`).
- **G-37-6 & Phase 37 Completion (PR 941 MERGED `c216e95e` + `quick-261016-edv` forced-light dashboard theme + Phase 37 `.planning/` closeout).** Closed UAT test 6's gap G-37-6 (plans 37-56..37-59: finance container-width grids, `withFullStop` sentence-end parity across TypeScript and Java, pagination container-width tiers, void dialog proven at 390/768/1280 px); re-tested UAT 15/15 PASS (`37-VERIFICATION.md` 8/8 roadmap criteria, 35/35 requirements, `threats_open: 0`); and shipped `quick-261016-edv` (`DARK_THEME_AVAILABLE = false` in `frontend/hooks/use-theme.ts`, withholding the dashboard theme toggle while keeping stored preferences intact until a dashboard-wide dark pass lands).
- **Strategic Positioning & `ADR-0007` (PR 940 MERGED `e1c7dabf`).** Reframes J'Toye OaaS around its four code-verified UK West African food operator advantages (`£39/location/mo + 0.5% GTV` with Vendor as MoR, `V81`/`V80` abuse-hardened counter ops, Cohort B scheduled batch/catering fulfilment, and `V63`–`V86` UK FSA/Natasha's Law/GDPR statutory defensibility), records `GAP-GTM-01`..`07`, and specifies the 4-axis composable policy engine in `docs/architecture/decisions/ADR-0007-multi-model-commercial-and-fulfilment-policy-engine.md`.
- **Phase 39 (`39-01..39-10`, 10/10 complete).** `39-01` (PR 926) authored `39-CONTEXT.md`, `39-RESEARCH.md`, `39-DEPENDABOT-TRIAGE.md` and `ADR-0002`, and advanced the `2026-10-26` `rabbitmq-k8s` review window to `2027-01-16`. `39-08` (PR 927, `V85__customer_origin_shop_scope.sql`) shipped Option C per-shop customer narrowing (`customers.origin_shop_id` + `customers_aud` mirror, GUC-pinned backfill under `NOSUPERUSER NOBYPASSRLS`). `39-09` (PR 933, `V86__shop_slug_alias.sql`) shipped old-slug `301` storefront redirects (`shop_slug_alias`, RLS-exempt by addition in `RlsContractTest.EXEMPT_TABLES`, proven under `NOSUPERUSER NOBYPASSRLS` by `ShopSlugAliasIntegrationTest`). `39-10` (PR 934) consolidated all 9 open Dependabot PRs (`#905`, `#907`, `#909`, `#910`, `#928..#932`). `39-02` + `39-03` (PR 935) upgraded `alpine:3.24`, `rabbitmq:4.3.6-management-alpine`, Prometheus `v3.13.4` LTS, Grafana `13.2.4`, Alertmanager `v0.34.1`, `redis-exporter:v1.94.0`, and `postgres-exporter:v0.20.1`. `39-07` (PR 936) migrated the frontend to Tailwind CSS `4.3.3` (`@tailwindcss/postcss`). `39-04` (PR 937) upgraded Keycloak to `26.8.1` and retired the final `DEFERRED-27` horizon exemption (`active-exemptions=0`). `39-05` + `39-06` (PR 939) upgraded PostgreSQL `15` -> `18.6` (`postgres:18-alpine`, `jtoye-pg-backup:18-blob`, 10-site `check-postgres-major-parity.sh`, `IntegrationTestSupport.ensureDowngradableTestRole` for `NOSUPERUSER` RLS tests) and Redis `7.4` -> `8.10-alpine`.
- **Phase 37 Owner Decisions Resolved (2026-10-17).** (1) **Per-IP cash-order cap for signed-in customers (`37-31` H4):** `PublicStorefrontService.placeGuestOrder` now skips `cashOrderCaps.checkIp(...)` and leaves `orders.client_ip_digest` `null` when `contactVerified == true` (verified customer-realm token matches the order email), preventing proxy-peer IP bucket exhaustion through `/api/customer-orders` while keeping the 3/email and 3/phone caps enforced (`PublicStorefrontServiceTest`, `CashOrderCapIntegrationTest`). (2) **Keycloak `core-api` `serviceAccountsEnabled` (`T-37-107`):** set `"serviceAccountsEnabled": false` on `core-api` (and `"enabled": false` on `service-account-core-api`) in `infra/keycloak/realm-export.template.json` and updated `docs/runbooks/credential-rotation.md`. (3) **Staging/production `security.trusted-proxies` & `CLIENT_IP_HMAC_KEY`:** kept `security.trusted-proxies: ""` in git (`k8s/staging/configmap-patch.yaml`, `k8s/production/configmap-patch.yaml`, `INV-12` `WARN`, fail-closed) until Phase 29 Wave 7 live cluster bring-up verifies `externalTrafficPolicy: Local` on `ingress-nginx` and measures the live ingress pod CIDR, alongside minting environment-isolated 64-hex `client-ip-credentials/hmac-key` and `dsar-credentials/encryption-key` Secrets (**Phase 29 merge note:** when merging `main` into `phase-29-research`, add `client-ip-credentials` and `dsar-credentials` to `scripts/staging-secrets.sh` and set `"serviceAccountsEnabled": false` on `core-api` in `k8s/base/keycloak/realm-import-configmap.yaml`).

**2026-10-14 delta: 37-E is MERGED as PR 920 (squash d6c3d308); the nightly E2E is fixed by PR 921 (squash ae245840); main is merged back (f596a032, pushed) and the stack is rebuilt and fresh. Resume here; the blocks below it are history.**

- **37-E (PR 920).** Opened by hand in the 916-918 shape, changelog on the PR branch. Round 1: 9 findings, 1 admissible (a Create retried after a lost response sent a new Idempotency-Key and would issue a second live credential; fixed with one key per intended body, RED then GREEN). Round 2: zero; Review-Record on 2ad45a42. The "Drafts" note (submitOrder does not re-check `available` on DRAFT to PENDING) is recorded in the round-1 resolution, as agreed, and is not fixed.
- **Nightly (issue 683).** Six causes, fixed in PR 921 over five proof dispatches: dashboard footer CLS, an incomplete dish-modal fixture product, `KC_ADMIN_ENABLED=false` copied from `.env.example`, a live order event unmounting the bulk-reject summary, a skip-budget breach masked behind the failing steps, and a per-tenant 429 with the whole suite on one tenant. The final dispatch (run 38542281432) was green: 387 passed, 0 failed, 6 skipped. Issue 683 stays OPEN until a SCHEDULED run on main is green. The squash subject says "five causes"; there were six.
- **Runtime.** `scripts/sync-runtime.sh`: freshness rc 0 (4/4, 0 unverified); `check-openapi-snapshot-fresh.sh` rc 0; V82 and V84 applied. The fix-targeted specs against port 3000 passed 30, with 2 declared skips.
- **Worktrees.** `-37e` and `-nightly` were removed. `-37a` remains.
- **Next.** `/gsd-execute-phase 37` resumes at 37-48 (37-G, V83). Owner items unchanged: the proxy-peer IP cap, staging/production trusted-proxies and `CLIENT_IP_HMAC_KEY`, Phase 39 by 2026-10-26, and `core-api serviceAccountsEnabled` (recommended off).

**2026-10-13 morning delta: 37-39..37-46 are DONE (46/53, each recorded and pushed); 37-47 (the 37-E gate) finished Task 1 and is PARKED at its Task 2 blocking-human checkpoint. Resume here; the blocks below it are history.**

- **Where things stand.** Branch `phase-37-ops-readiness`, pushed, tree clean apart from the untracked `.planning/milestone.lock` (leave it). 37-47 Task 1 commits a1237bdc (five static gates red from 37-E drift: stale citations, the new /dashboard/developers missing from layout-tiers.tsv and ssr-routes.conf, V82 still "reserved" in AGENTS.md) and b62fe1ac (evidence Part 1). D-03 reconciliation a proven no-op. Suites on the 37-E head: unit 1821/0, integration 1221/0, JaCoCo 91.6%, jest 2790/240, build + full tsc + lint 0 errors, go 112, vitest 70/70. OpenAPI live gate RED (10 keys) only because the stack still runs the 37-C images built 2026-10-12 19:18Z.
- **THE CHECKPOINT (owner's, never auto-selected):** may 37-E take the shared compose stack, including creating/rotating/deleting a credential client in the jtoye-dev Keycloak realm? On **proceed**: spawn a continuation gsd-executor with the original dispatch (`dispatch-37-47.md`) plus the `<continuation>` block saved in this session's scratchpad as `continuation-37-47.md` (replace `__ANSWER__`); then push both branches, open the PR by hand in the #916/#917/#918 shape, changelog entry on the PR branch, D3 series (`/code-review <PR> --comment`, then `/review-verify`), merge on the first zero-admissible round, merge origin/main back. On **wait**: nothing runs.
- **What 37-E shipped (per-plan detail in each SUMMARY):** 37-39 trackInventory semantics + narrow product PATCH (RWO-016); 37-40 shop-scoped /sync/batch with per-item outcomes, shop-less products unorderable (RWO-017/037); 37-41 edge relays core results and 4xx problems, outside the breaker; 37-42 MCP orders land PENDING, list_products by shop with allergen names (RWO-036/040); 37-43 delivery-level webhook auto-pause, HELD events, owner email (RWO-038); 37-44 Keycloak credential-client admin calls; 37-45 V82 integration_credential + issue/rotate/revoke + HumanCallerGuard; 37-46 Developers page, Staff "Integration" rows. RWO-035 is left for the gate to tick.
- **Migrations.** V82 shipped (37-45, applies out of order after V84). V83 still reserved for 37-48. Next free V85.
- **gitleaks.** RED-evidence files that capture `key=<prefix>-<uuid>` idempotency log lines trip generic-api-key on push; 37-40 and 37-42 each added a content-anchored allowlist block (measured both ways). Dispatches now tell executors to scan evidence first.
- **Owner items open.** (1) Turn off `serviceAccountsEnabled` on `core-api` in the dev realm? Researched: no in-repo consumer of its client-credentials grant (edge uses edge-api, MCP uses integration-*); recommended: off. (2) 37-45's staging/production `keycloak.client-id` question is answered by construction (ACCESS_HUMAN_CLIENT_IDS reads the same app-config key the frontend signs in with). (3) 37-43 UAT items: held only on pause, manual-pause events unrecorded, fresh 8 attempts on resume, button copy with nothing held, email time format. (4) Carried: the 37-31 H4 proxy-peer IP-cap question, staging/production trusted-proxies and CLIENT_IP_HMAC_KEY secrets, Phase 39 planning before 2026-10-26, the 37-23 §2.11 UAT items. (5) The vendored `scripts/gates/review-record-check.sh` drifted from canonical; refresh with `bash ~/dotfiles/gates/review-gate-onboard.sh enroll --repo-dir <this repo>` if wanted.
- **Per-plan routine (unchanged).** Dispatch file per plan generated from the previous one (`mkdispatch.sh` in the scratchpad: lines 1-6, 85, 91, 93 are plan-specific; the inlined GSD docs were verified identical to the installed copies, ignoring trailing blank lines); gsd-executor reads it in two chunks; verify commits + SUMMARY + gitleaks over the unpushed range; hand-edit STATE (stopped_at, last_updated, state_head, completed_plans, decisions, Session Continuity), ROADMAP (phase row, `**Plans:** N/53`, checkbox), REQUIREMENTS; one commit; push.

**2026-10-12 evening delta: 37-32..37-38 are DONE (38/53); sub-theme 37-C is MERGED as PR 918 (squash bfef0f6e, 21:23Z); main is merged back (3b10a328, pushed); 37-E starts at 37-39 (wave 31, autonomous). Resume here; the blocks below it are history.**

- **Where things stand.** Branch `phase-37-ops-readiness`, merge-back commit 3b10a328 plus this HANDOFF commit, pushed; its tree equals origin/main outside `.planning`; `git log HEAD..origin/main` empty. Untracked `.planning/milestone.lock` belongs to a dead pid (80992): leave it. Remote branch `phase-37-c-checkout` and tag `cut/phase-37-c-checkout` kept; the `../JToye_OaaS_2026-37c` worktree was removed (clean, pushed).
- **What 37-C shipped.** Submit-time basket re-validation (422 `basket-confirmation-required`, 409 `basket-changed`, `BasketChangedPanel`); public promotions empty and reviewers "First L."; the cash-order confirmation route; and **V84 `order_items_guest_tracking`** (owner option A at the gate): the anonymous tracking read saw orders but no lines since 31.1 (#901), so the recorded allergen set read "not recorded" on /track, the card route and the new confirmation. Proven under NOSUPERUSER (4/7 RED → 7/7).
- **Migration numbering.** V82 (37-45) and V83 (37-48) stay reserved and apply out of order after V84 (proven on scratch Postgres 15). The next free number is **V85**.
- **Review series #918.** Round 1: 7 findings, admissible 4 (fee-mismatch resubmit loop; always-below-minimum 409 loop for agents → the pre-37-33 400 restored; "Pemberton, Grace" published the surname; the server-rewrite flag carried the allergen tick across a sibling-tab change), all fixed RED → GREEN on the PR branch; round 2 zero; Review-Record on 261025417ec3. CI's `Frontend E2E (public surfaces)` failed once on the mobile /track contrast scan of `recorded-ack` and passed on re-run with identical code: the /track result fades in over 240 ms (kept under reduced motion), so axe can scan mid-fade. A flake in the spec, not fixed; if it recurs, make the scan wait for the entrance to settle.
- **Runtime.** The compose stack runs the 37-C images cut at d060d0af (freshness was PASS 4/4). The four review fixes changed core-java and frontend AFTER that build, so the runtime is now BEHIND the branch: the next gate (or any live check) must rebuild first.
- **Owner rulings this session.** 37-34 safe-resume: close out. 37-38: "Proceed" on the shared stack; the review-form notice check N/A with WINDOWS row 43 left OPEN (RWO-044 ticked, its review-form clause unmet: no review form exists); option A for the allergen-tracking blocker.
- **Still open for the owner.** The 37-31 H4 proxy-peer IP-cap question (signed-in cash orders share one connection digest while `TRUSTED_PROXY_CIDRS` is empty); staging/production trusted-proxies and `CLIENT_IP_HMAC_KEY` secrets; Phase 39 planning before 2026-10-26; the 37-23 §2.11 UAT items.
- **Known flakes / deferred.** `abuse-bounds.spec.ts` bulk reject on mobile (Radix Select still animating; 3 of 7 runs); the /track contrast scan above; the storefront header overflows 4px at 360 px / 130% root font (37-52 F6).
- **Resume.** `/gsd-execute-phase 37` → 37-39 (37-E: trackInventory semantics, IGNORE-null stock mapping, an integrator stock/availability PATCH that cannot touch allergen data; P0 RWO-016).

**2026-10-12 morning delta: 37-31 is DONE (31/53); sub-theme 37-D is MERGED as PR 917 (squash 078c0c11); main is merged back; 37-C starts at 37-32.**

**Where things stand.** Branch `phase-37-ops-readiness`, head 5c626c63 at writing, pushed; tree clean apart from the untracked `.planning/milestone.lock` (leave it); `git log HEAD..origin/main` empty. The owner answered **proceed** on the 37-31 checkpoint ("yes 37-D may take the shared compose stack now"); the continuation executor ran Task 3 (commits 1e34500f, 75abfcee, 127e8d33 = tag `cut/phase-37-d-abuse`, 9f3cda75, 3eb9f284), tracked by b2ef2b20 (STATE 31/53, `completed_plans` 216, RWO-005/006/039/041/042 ticked in REQUIREMENTS). PR 917 (`phase-37-d-abuse` = squash b69bb8b4 on main 14a2297b + changelog b005a07b + round-1 fix 528b691d) was opened by hand in the #916 shape, reviewed (round 1 `/code-review`: 9 findings, 1 admissible and fixed, 8 recorded; round 2 `/review-verify`: zero admissible, Review-Record on 528b691d), merged squash at 2026-10-12T08:52Z, and origin/main merged back as 5c626c63 (`-s ours` + the post-cut diff b69bb8b4..528b691d, the 37-23 precedent, because the squash three-way-merges with conflicts). The integration tree now differs from main only by the tracking docs and the post-cut gate records (37-31-SUMMARY.md, evidence 2.12), which ride in the 37-C PR.

**The compose stack runs the 37-D images** (rebuilt and recreated in Task 3; freshness PASS 4/4, V81 live, `check-openapi-snapshot-fresh.sh` rc 0, the Task 1 tracer line all zeros). Evidence `evidence/37-31-gate-37D.md` Part 2: every live proof RED on the 37-A images and GREEN after (caps 429 by axis, 422 order-limit-exceeded at 1,000,000, 400 review-order-shop-mismatch, edge problem+json 429 with Retry-After for tenant A while tenant B's sync passes, one bucket under 760 spoofed hops with a no-header control at request 721); SQL digest check clean; ten goods; specs 74/0 after spec-side hygiene only (`frontend/e2e/order-cleanup.ts`, per-run contacts, cancel-by-number: the caps count what earlier runs left open, so every ordering spec now cancels what it places); 16 screens in `evidence/37-31-screens/`. Residue on the dev tenant: two COMPLETED probe orders at Peckham with ledger rows (a review needs a completed order); the pre-existing 91/22/3 open cash orders at Mama Ade's/Brixton/Peckham from older runs left as found.

**Next, in order.** (1) `/gsd-execute-phase 37` resuming at 37-32 (wave 26, 37-C: the guest-order contract carries the prices shown) through 37-37, then the 37-C gate 37-38 (same shape: checkpoint before the rebuild, cut `phase-37-c-*` as one squash on origin/main, PR by hand, D3 series, merge, main merged back). The per-plan routine is unchanged (dispatch file from the previous by sed, gsd-executor reads it in two chunks, verify commits + SUMMARY, hand-edit STATE/ROADMAP, commit, push). (2) Phase 39 planning before 2026-10-26.

**Owner rulings carried open (deferred-items, evidence 1.6, PR 917 body and round-1 resolution).** (a) A signed-in customer's order reaches core through the Next proxy, so with `TRUSTED_PROXY_CIDRS` empty every signed-in cash order at a shop shares one connection digest and the per-IP cap (5) can refuse a legitimate sixth customer. Recommended: `CashOrderCaps` skips the IP axis for a verified contact. The alternative, trusting the frontend's range, is safe ONLY behind an ingress that appends the real client to `X-Forwarded-For` (`k8s/base/ingress.yaml` does): the proxy forwards the browser's header verbatim and cannot append the peer (Next 16 route handlers expose none), so a directly reachable trusted frontend would let a browser choose the address core keys on (round 1 finding 2). (b) Staging/production `security.trusted-proxies` is `""` behind the ingress, so every per-IP key there resolves to the ingress pod until the operator sets it (round 1 finding 3; INV-12 WARNs; `37-USER-SETUP.md`). (c) The four reject-reason labels and the cap/limit defaults (per-email 3, per-phone 3, per-IP 5; line 50, units 200, lines 50, £1,000), SUMMARY rows H1–H4, in the phase UAT. Recorded, not filed, in the round-1 resolution: the bulk-cancel mid-batch failure copy ("Nothing was changed."), `isTrustedProxy` dead on the resolver, the double access check in `cancelOneForBulk`, the phone axis as a list read, `bulk-cancel.max-ids` as a literal; the squash message labels 37-29 RWO-005 where the plan says RWO-041.

**Other owner items.** 37-23 §2.11 kitchen UAT (chime, alarm, timings; 21 screens). The sealed `client-ip-credentials/hmac-key` Secret for staging/production (core refuses to start without it). Dependabot #904–#911 open. Worktree `../JToye_OaaS_2026-37a` (branch `phase-37-a-kitchen`) can be removed when no longer wanted.

**2026-10-12 night delta (history): 37-24..37-30 are DONE (30/53); 37-31, the 37-D gate, finished Task 1 and waited at its blocking-human checkpoint (answered proceed the next morning).**

**Where things stand.** Branch `phase-37-ops-readiness`, head 130f81f4 at writing, pushed; tree clean apart from the untracked `.planning/milestone.lock` (leave it). Each plan was run by one gsd-executor in this checkout (isolation none) and recorded by a `docs(37-NN): record …` commit: 37-24 ClientIpResolver (c66a9faf), 37-25 edge per-tenant limiter (eb7c2bb8), 37-26 OrderLimits typed 422 (0449b35b), 37-27 V81 + CashOrderCaps email/phone + contact_verified (f7e822ec), 37-28 keyed IP digest + per-IP cap + retention + erasure (7ebf8594), 37-29 bulk reject with a kept reason (1fba16a1), 37-30 frontend: RejectOrdersDialog, UnverifiedContactBadge, refusal copy, stepper limit, customer-token proxy, R-16 row (9942de0d). STATE.md and ROADMAP.md are current to 37-30; the per-plan detail is in each `37-NN-SUMMARY.md` and the tail of `deferred-items.md`.

**37-31 Task 1 is done (commits 120c723f, 87b29328, 130f81f4; evidence `evidence/37-31-gate-37D.md` Part 1).** PR 916 reads MERGED by PR state; the D-03 reconciliation is a proven no-op (origin/main is an ancestor of HEAD, `git merge origin/main` = Already up-to-date, no merge commit recorded). Suites on the 37-D head: unit 1753/0, FULL integrationTest 1127/0 (the first full run since 37-23 had 19 failures from one cause: 37-28's ClientIpDigester refuses to start without a key and seven `@ActiveProfiles("dev")` IT classes plus the ownership test had none; fixed test-side with a per-run random key, never a default in a profile file), jest 2672/0 in 229 suites, full-program tsc rc 0 (the CI step red since 37-17 is GREEN again on this tree), lint 0 errors, build rc 0, go ok, vitest 61/0. Static gates all rc 0 except `check-openapi-snapshot-fresh.sh` (the expected live RED: 8 keys absent from the running service, which still runs 6a825865's images). `docs/metrics.json` regenerated with `--write` (5629 invocations) and the prose counts moved with it. `frontend/e2e/abuse-bounds.spec.ts` written (6 tests), runs in Task 3. gitleaks over the range clean (three CONTENT allowlist blocks were added during 37-27/37-28 for RED-evidence fixture values; the pre-push P-3 scan covers the whole pushed range, so a fixture value in a JUnit assertion message trips it).

**THE CHECKPOINT (owner's decision, never auto-selected).** 37-31 Task 2 asks: *may 37-D take the shared compose stack now?* Task 3 rebuilds EVERY image (`docker compose build` then `up -d`, never `start`), applies V81 to the live dev database, floods the edge and the storefront with probe orders (caps, the 1,000,000-unit refusal, cross-shop review, bulk reject), runs `abuse-bounds.spec.ts` plus storefront-flows, allergen-ack-race, cash-checkout-no-stripe and kitchen-flow, re-verifies the ten 37-D goods, then cuts `phase-37-d-abuse` as ONE squash on origin/main (tag `cut/phase-37-d-abuse`, not pushed). `CLIENT_IP_HMAC_KEY` is ALREADY in the untracked local `.env` (37-28; `scripts/verify-env.sh` PASS), so nothing new is generated. On **proceed**: spawn a CONTINUATION gsd-executor for 37-31 (the 37-23 pattern: the original dispatch `dispatch-37-31.md` in this session's scratchpad plus a `<continuation>` block with the completed-tasks table for Task 1's three commits, `<user_response>proceed</user_response>`, `<resume_task_number>3</resume_task_number>`; the Task 3 `<human-check>` is harvested into the phase UAT under `human_verify_mode = end-of-phase`), then: push both branches, open the PR BY HAND in the #913/#916 shape (gsd-ship blocks mid-phase), changelog entry on the PR branch, round 1 `/code-review <PR> --comment`, later rounds `/review-verify`, resolution comment after each, merge on the first zero-admissible round, then merge origin/main back into `phase-37-ops-readiness` (the pre-push P-2 gate demands it). On **wait**: nothing runs; the OpenAPI gate stays RED and freshness stale until the rebuild.

**OPEN PRODUCT FINDING for the owner (37-30; evidence 1.6; deferred-items), decide before the 37-D PR merges.** A signed-in customer's storefront order goes through the Next proxy route `POST /api/customer-orders?shop=`, so core's socket peer is the frontend container and `ClientIpResolver` trusts `X-Forwarded-For` only from `TRUSTED_PROXY_CIDRS` (empty by default): every signed-in cash order at a shop shares ONE IP digest and the per-IP cap (5) can refuse a legitimate sixth signed-in customer. Recommended: `CashOrderCaps` skips the IP axis for a verified contact (a verified identity is already bounded per email; the IP axis exists for anonymous floods inventing fresh addresses), as a small server plan before the 37-D PR or as a fix commit on it; the alternative is the operator declaring the frontend's range trusted per environment. Not fixed anywhere yet; the live IP-cap proof places its six orders as a guest, so Task 3 is not blocked by it.

**Other owner items.** 37-23 §2.11 kitchen UAT (chime audible and repeating until Accept, board-stopped alarm distinguishable, 5 s / 3 s / 120 s sign-off, 21 screens in `evidence/37-23-screens/`). Staging/production operator inputs recorded in `37-USER-SETUP.md`: `jtoye.security.trusted-proxies` (37-24) and the sealed `client-ip-credentials/hmac-key` Secret (37-28; core refuses to start without it). Phase 39 must be planned before 2026-10-26 (`/gsd-discuss-phase 39` then `/gsd-plan-phase 39`). Dependabot #904–#911 open. Worktree `../JToye_OaaS_2026-37a` (branch `phase-37-a-kitchen`) can be removed when no longer wanted.

**Machine notes for the next session.** `/usr/bin/python3 -I script.py` is refused by the block-base-python hook in this project (no `.conda-env`); hand-edit STATE/ROADMAP with the editor. GSD `roadmap.update-plan-progress` and every `state.*` verb corrupt their files; the per-plan routine is hand edits to the phase row, `**Plans:** N/53`, the plan checkbox, `stopped_at`, `last_updated`, `state_head`, `completed_plans`, a metrics row, three decisions and the Session Continuity lines, then one commit and a push. The `phase-plan-index` readiness query is the dispatch gate; `phase.tdd-applicable` reports "Plan file not found" for every 37-NN and was not relied on (the plan frontmatter carries `type:`). Memory for this project lives in `~/.claude/projects/-home-sanmi-IdeaProjects-JToye-OaaS-2026/memory/` and is current to this delta.

**2026-10-11 evening delta (history): 37-23 is DONE (23/53); sub-theme 37-A is MERGED as PR 916 (squash 14a2297b).**
**Where things stand.** Read the head with `git log -1` on `phase-37-ops-readiness` (this checkout) and `git log -1 origin/main` (14a2297b at writing). `origin/main` (the #916 squash) is MERGED into the integration branch (D-04; the pre-push P-2 gate demanded it): the add/add conflicts were resolved by taking main's version of every code and docs file the PR touched and ours for the four planning files that moved after the cut, proven by an empty `git diff origin/main -- . ':!.planning' ':!HANDOFF.md'`. So the integration branch already carries the three #916 review fixes and the CI type-check step is green on it again; the 37-D gate's D-03 reconciliation of `origin/phase-37-a-kitchen` (final reviewed head bc8e2929, branch deliberately KEPT) will find nothing left to apply and must treat that as satisfied, not as an error.
- **37-23 Task 2** was answered `proceed` by the owner (reply "carry on once the review is back"). **Task 3** ran as a CONTINUATION executor (prompt = the original dispatch with the root-pin guard's unbound check fixed to the empty case, plus a `<continuation>` block; the Task 3 human-check was harvested into the phase UAT under `human_verify_mode` end-of-phase, as 37-15 did): the pre-rebuild runtime (261010-ppb at 75bb3211) already carried 37-A, so `kitchen-flow` and `kitchen-session-lapse` were 30 passed BEFORE the rebuild (recorded as found, not relabelled RED); the RED arm was the freshness gate (3 of 4 `image-not-rebuilt`) and `next` 16.3.7 / `x/net` 0.58.0 inside the images. After rebuilding ALL images: freshness PASS 4/4 (mcp-server mode-B retag force-recreated; order metric re-seeded), `next` 16.3.8, `x/net` 0.60.0, V80 head, OpenAPI PASS, strict scoping ON; the plan's five specs 98 passed / 0 skipped, public-a11y 40; live: lane arrival 4.3 s, chime every 5000 ms until Accept, pause -> Paused / 409 `shop-closed` in words -> resume 201, 440 Hz alarm at 3000 ms capped 120 s with the overlay kept, MCP `create_order` DRAFT (expected until 37-42), seeded schedule open, legacy Brixton fixture open with the vendor notice and restored by md5, 38-minute stay-live. Evidence `evidence/37-23-gate-37A.md` Part 2, 21 screens in `evidence/37-23-screens/`. Commits 6a825865 (Part 2), a28f393c (branch pointer), 2a001483 (SUMMARY), 5fec0732 (STATE 23/53, ROADMAP, RWO-011/020/021/022 complete).
- **Dev tenant state after Task 3:** Peckham's 17 stale PENDING probe orders (2026-08-31..10-03) were CANCELLED (rows kept) so the chime could be shown to stop; two probe orders are left in PREPARING (`EEB79298`, `0F6DD717`); every pause resumed. The stack (all 11 services) runs the images built from 6a825865's tree.
- **PR 916** (`phase-37-a-kitchen`, cut 05b9ea01 = one squash on origin/main 7364b215, tag `cut/phase-37-a-kitchen` = 6a825865) was opened by hand in the ship shape, changelog entry 57defea5 on the PR branch (worktree `../JToye_OaaS_2026-37a`, still present; remove with `git worktree remove ../JToye_OaaS_2026-37a` when no longer wanted). D3 series: round 1 `/code-review` 7 findings, 2 admissible, both reproduced by tests RED on the reviewed head: (1) checkout kept Place order disabled after a timed pause ended because the shop snapshot was read once on mount — fixed 347626c4, the page re-reads the shop 500 ms after the server's stated reopening time (the 37-21 `PauseOrdersControl` precedent); (3) a shop created after V80 with no hours is NO_HOURS-closed by D-15 but the dashboard said nothing — fixed 2bbd36d8, notice-card block + "No hours: not taking orders" badge (metrics 2622 -> 2627 jest, total 5476). Round 2 zero admissible. Then CI's "Type-check the full frontend program" step (the ONLY gate reading jest test files; `next build` uses `tsconfig.build.json`, which excludes tests) was found RED on the PR: `kds-sound.test.tsx(200,24) TS2339 getAutoplayPolicy` — a gate break, fixed bc8e2929 (`installAutoplayPolicy` returns its mock); round 3 zero admissible; Review-Record on bc8e2929554341d46d8f5876175d364256701277; merged. **The same red has been on the integration branch's own CI since 37-17 landed the file** (every `CI/CD Pipeline` run on `phase-37-ops-readiness` failed at that step and the dependent "Verify docs/metrics.json against the Jest runner"); the merge of origin/main above brings bc8e2929's change across, so the run on this head should be the first green one — check it. Recorded, not filed (5 in round 1: the NEEDS_REVIEW zero-length / out-of-range day shapes, an owner question against the D-25 ruling; the generic 400 on a malformed window time; an orphaned Javadoc on the shop-closed handler; duplicated `trim`/`SPACE_CHARS`; parse-before-pause efficiency) and 1 in round 2 (a device clock >500 ms ahead of the server makes the checkout re-read fire early and never re-arm).
- **Owner UAT items owed from 37-23 §2.11 (end-of-phase):** on the rebuilt stack tap "Turn on sound", place a storefront order, confirm the chime repeats until Accept and is audible in a noisy room; confirm the board-stopped alarm is distinguishable by ear; sign off 5 s chime / 3 s alarm / 120 s cap (UI-SPEC open item 1); review the 21 screens.

**Next, in order:**
1. `/gsd-execute-phase 37` resumes at **37-24** (wave 18, the first 37-D plan, RWO-006: the public limiter trusts the first X-Forwarded-For hop; autonomous). The 37-D gate plan does the #916 reconciliation (D-03/D-04) before its own gates.
2. Plan Phase 39 before 2026-10-26 (unchanged). Eight Dependabot PRs #904..#911 are open; #906 duplicates the `next` bump.

**2026-10-11 delta: 37-B is MERGED (PR 913, squash 7364b215); 37-23 (the 37-A gate) is PARKED at its Task 2 blocking-human checkpoint. Resume here.**
**Where things stand.** Read the head with `git log -1` on `phase-37-ops-readiness` (this checkout, 93b23cec at writing) and `git log -1 origin/main` (7364b215 at writing). `scripts/check-branch-behind-base.sh` passes (0 behind).
- **Step 1 of the 2026-10-10 list is done.** PR 913 was opened by hand (the `/gsd-ship` preflight blocks a mid-phase sub-theme: `verification.status` is `missing` until the whole phase verifies), went through a three-round D3 series (round 1: 10 findings, 1 admissible: a card-paid PENDING order was still deletable and orphaned its SALE, fixed by `OrderService.isSettled` on the delete guard AND the void; round 2: 1 admissible, the dialog predicate over-offered the void on a bare `paymentReference`, now keyed on `paymentStatus` CAPTURED/REFUNDED; round 3: zero, Review-Record on aec7e0a7) and merged 2026-10-11T13:58Z. Nine round-1 findings are RECORDED in its resolution comments (machine-client bypass on the ledger is a design question for the 37-23 security pass; the page-local Voided badge, exportCsv memory, reviewQueue N+1, grantedShopIds re-query, saveBlob revoke timing, the invite-accept lock span, the REQUIRES_NEW directory touch, no event on void). The remote branch `phase-37-b-staff-access` is KEPT (37-23 Task 1 merged it).
- **Trivy was red on main since 2026-10-08** (next 16.3.7 CVE-2026-94483, golang.org/x/net 0.58.0 CVE-2026-78669). Fixed by PR 914 (merged b3b87a47); its review caught a `go build` binary a sweeping `git add` had committed, removed before merge and ignored by path in `.gitignore`.
- **37-23 Task 1 is COMMITTED** (fbf3eee1 reconciliation merge of the PR head, d490d15c merge of origin/main, 7068e640 F23 arm + countable 37-17 jest table + metrics at V80, 93b23cec evidence in `evidence/37-23-gate-37A.md`). Static gates all rc 0; suites core-java unit 1683/0, integration 1084/0, jest 2622 in 222 suites, build green. Plan head before: `.git/gsd-plan-head-before-37-23` = 4f478bd8. The docs-freshness VOID on the 37-17 table is resolved.
- **Two notes from the executor:** (a) the orchestrator's `<project_root_pin>` guard as embedded always stopped at `pin-unbound` because the placeholder was also substituted into the unbound check; the executor ran a copy with that one check reduced to the empty case and it passed here and failed from another repo. Build the pin from the template's own bound form next time. (b) `frontend/node_modules` has next 16.3.6 while the lockfile pins 16.3.8; local jest and build ran on 16.3.6; Task 3's image rebuild runs `npm ci` from the lockfile.
- **Runtime.** UNTOUCHED by 37-23 so far (one read-only GET by the OpenAPI gate). The stack is still the 261010-ppb build; it is BEHIND this branch (37-B review fixes, #914, the merge) until Task 3 rebuilds it.

**Next, in order:**
1. **Owner answers the 37-23 Task 2 checkpoint: `proceed` or `wait`** (may 37-A take the shared compose stack: Task 3 rebuilds every image and writes test orders and shop pauses on the dev tenant). On `proceed`, spawn the CONTINUATION executor for 37-23 from Task 3 (continuation-prompt.md; do not redo Task 1). Task 3's verify has a human check: listen to the chime and the board-stopped alarm on the rebuilt stack, approve the 5 s / 3 s timings, review the screenshots. Task 3 ends by cutting `phase-37-a-kitchen` as one squash on origin/main; the owner then opens that PR by hand in the ship shape (see the 37-B route above) and runs the series.
2. Then `/gsd-execute-phase 37` continues at 37-24 (waits on the 37-A PR merge the same way).
3. Plan Phase 39 before 2026-10-26 (unchanged). Eight Dependabot PRs #904..#911 are open; #906 duplicates the `next` bump.

**2026-10-10 delta: Phase 37 at 22 of 53 (37-01..37-22 complete); quick tasks 261009-g8m and 261010-ppb done; execution waits on the 37-B PR merge. Resume here.**
**Where things stand.** Read the head with `git log -1` on `phase-37-ops-readiness` (this checkout, 75bb3211 at writing) and `git log -1 origin/main` (1773fe53 at writing).
- **Progress.** Waves 1-16 complete. Sub-theme 37-B (37-08..37-15 plus the WINDOWS #29 customer gate) is cut as ONE squash on `phase-37-b-staff-access` (36d7f74b; tree equals tag `cut/phase-37-b-staff-access-v2`; 1 commit ahead of origin/main, 0 behind; unpushed). No PR exists for it yet: the owner opens it with `/gsd-ship` (executors never push). 37-23 (wave 17, the 37-A gate, autonomous:false) has a Task 1 precondition that this PR is merged, and 37-24..37-53 depend on 37-23, so execution waits here.
- **Runtime.** The compose stack was rebuilt by 37-22 and again by quick 261010-ppb: `scripts/check-runtime-freshness.sh` passes 4/4 at 75bb3211 and `scripts/check-openapi-snapshot-fresh.sh` passes. The vendor realm `jtoye-dev` was set to `ssoSessionMaxLifespan` 50400 through the admin API (3 live users exist outside the template, so no `--override` import); the template matches. The dev tenant carries ~20 stale PENDING seed orders plus two PENDING probe orders from 37-22.
- **Owner rulings 2026-10-10** (STATE.md decisions, WINDOWS #32-#42, 37-CONTEXT D-14 and D-25): D-25 applied per day (an unreadable legacy day stays open until hours are saved); customers follow option B then option E (reads need any honoured grant, create/update need SHOP_MANAGER, delete needs Group admin); option C (per-shop narrowing) and old-slug 301 aliases are deferred to Phase 39; the slug-keep fix stays in 37-48; the hours editor pre-fills an unreadable day as an all-day window; a pause 403 is answered by the K2 overlay only; the board-stopped alarm stops after 2 minutes; D-14 amended to a 14 h vendor SSO max with idle 30 min unchanged, refresh-token rotation deferred (needs a cross-replica guard before it can be turned on).
- **Suites at 75bb3211:** jest 2616 tests in 221 suites; core-java unit 1683/0/0/1 skipped, integration 1081/0/0/1 skipped. `scripts/docs-freshness.sh` VOIDs (rc 2) on a 37-17 `.each(TABLE.filter(...))` jest table the block counter cannot read (deferred-items; fix before the phase PR regeneration).
- **Ledger entries #37-#42 are open by design** (jest-only proofs owed to the 37-23 UAT, the rotation deferral, the staging/production operator step, the Phase 39 items); `workflow.windows_enforce` is false, so they do not block shipping.

**Next, in order:**
1. Owner: `/gsd-ship` from `phase-37-b-staff-access`, review, merge. The PR body can note that WINDOWS #30 (live proof of the customer gate) is fixed on the phase branch by 37-22's probes.
2. Then `/gsd-execute-phase 37` from this checkout. It resumes at 37-23, which stops at its blocking-human checkpoint (may 37-A take the shared compose stack) after Task 1.
3. Plan Phase 39 before 2026-10-26 (unchanged; option C and the slug aliases are noted there).


**2026-10-08 delta: Phase 37 is EXECUTING in this checkout (8 of 53 plans done); #903 merged; the worktrees are consolidated. Resume here.**
**Where things stand.** Read the current head with `git log -1` on `phase-37-ops-readiness` (this checkout) and `git log -1 origin/main`.
- **Checkout layout.** The main checkout `~/IdeaProjects/JToye_OaaS_2026` is on `phase-37-ops-readiness`, which contains `origin/main` (1773fe53). The `-phase37`, `-phase37-ops` and `-bizalign` worktrees are removed; the merged `phase-31.1-persona-gap-closure` branch is deleted locally and on origin. Only `../oaas-archify-diagrams` remains (leave it alone).
- **Phase 37 progress.** 37-01..37-08 complete. 37-08 (invite accept, Keycloak user creation, admin-only `tenant_id`) was committed by one session that died before its SUMMARY; the next session wrote `37-08-SUMMARY.md` from the commit record, re-ran every gate, ran both acceptance break arms red, and ran the full suite (`:test` 1610/0, `:integrationTest` 946/0 at 610d9bbe). Its two operator steps are in `37-08-USER-SETUP.md`.
- **Execution mode.** `workflow.use_worktrees=false` (commit e9a7ec7a): executors run sequentially on this tree, because the compose runtime, the gradle `build-local` cache and the compose project name all key on this checkout's path.
- **Wave 8 state (2026-10-08 18:40 BST):** 37-09 is COMPLETE (12 commits, SUMMARY 6926181c; jest 2313/0, core-java 1610/0 + 947/0; it added an optional `businessName` to `GET /api/v1/staff/me`, WINDOWS entry 24, for the security pass to rule on). 37-10 is PARKED at its blocking-human checkpoint: Task 1 (delete guard, typed 409 `order-not-deletable`) is committed as 89717aee + cb339d0f with the full suite 1610/0 + 956/0; Task 2 asks the OWNER to choose the ledger representation for a void — `entry-kind` (an `entry_kind` SALE/REVERSAL/MANUAL column + `reverses_transaction_id`, unique index becomes (tenant_id, order_id, entry_kind); the plan and RESEARCH 37-B.4 recommend it) or `reversal-table` (a second table with its own RLS and `_aud`; Task 3 would need re-planning). The plan-head-before sha for 37-10 is in `.git/gsd-plan-head-before-37-10` (495c8180). Every later plan depends on 37-10, so the phase waits for that answer; on resume, spawn the CONTINUATION executor for 37-10 from Task 3 with the chosen option (continuation-prompt.md), not a fresh executor. 37-09 must follow deferred-items §7 / WINDOWS entry 21: the token is in the URL fragment, the page is `/invite`, and the API is `POST /api/v1/public/staff-invites/preview|accept` with the ref in the body.
- **Gates that drift on this branch.** 37-07/37-08 inserted lines into application.yml, .env.example and the k8s base manifests; five line-pinned citations and two residue-allowlist lines were re-pointed (610d9bbe). Run `scripts/check-doc-citations.sh` and `scripts/check-no-object-store-residue.sh` before every push. gitleaks flags `#token=<uuid>` in a test fixture; the exact value is allowlisted in `.gitleaks.toml` (90c5665e), measured on 8.30.1 and the CI pin 8.27.2.
- **Runtime.** The compose stack is UP but built 2026-10-07 from a8119e53: BEHIND `main` (DSAR lease + strict-mode fixes) and behind this branch (V76/V77, the invite endpoints, the realm user-profile change). 37-15 is the rebuild gate (`scripts/sync-runtime.sh`, then `kc.sh import --override true`, then `scripts/check-runtime-freshness.sh`).
- **Docs metrics drift** (`scripts/docs-freshness.sh` rc=1, 4968 invocations): owed to the pre-PR step of each 37 sub-theme PR, not fixed per plan.
- **PR #903** (business-alignment planning docs, rulings D-B1/B6/B9/B27) merged as 1773fe53 after a two-round review (round 1: three reproduced factual fixes; round 2: zero). Six non-admissible findings are recorded in its resolution comments (B4 homed in a complete phase, an unfalsifiable B1 drill, `D-B27` naming no requirement, a `[SIM v2 R5]` tag the report does not support, an undefined "book complete" trigger).

**Next, in order:**
1. `/gsd-execute-phase 37` from this checkout. It resumes at the first plan without a SUMMARY. If 37-10 returned its checkpoint, answer it (the owner's ruling on the reversing-ledger representation, D-10) and the orchestrator spawns the continuation.
2. **Plan Phase 39 before 2026-10-26.** `docs/runbooks/rabbitmq-broker-upgrade.md` and ADR-0002 carry `manual_review` rows that expire that day; an expired row reds the required Operational Contracts check on every branch. `/gsd-discuss-phase 39` then `/gsd-plan-phase 39`.
3. Issues #900 (frontend base-image CVEs) and #897 (Google Fonts build fetch) are independent of Phase 37.

**2026-10-06 delta: Phase 38 (Spring Boot 4.1.1) is merged as #898; Phase 31.1 is next. Resume here.**
**Where things stand.** Read the current head with `git log -1 origin/main`.
- **Phase 38 is merged (#898, squash).** It closed #706 and superseded Dependabot #739 (closed). Before the merge:
  - **Verification:** re-run after the review fixes; 14/14.
  - **Security:** `38-SECURITY.md` reports `threats_open: 0` at ASVS L2.
  - **Local full suites:** unit 1495/0, integration 772/0.
  - **CI:** every required check passed on the merged head.
  - **Review series:** ended at round 2 with 0 admissible. Round 1's one finding, the asynchronous `RedisCache.evict` under Spring Data Redis 4, is fixed: `TenantCacheEvictor` uses the synchronous `evictIfPresent`, and `TenantCacheEvictorTest` goes red if `evict` returns.
- **Runtime is current.** core-java was rebuilt from the Phase 38 tree after the review fixes, with owner approval. `scripts/check-runtime-freshness.sh` passes 4/4, and both fixes were read out of the running `/app/app.jar`.
- **Changelog:** #898 merged without an entry. #899 adds it together with this handoff delta.
- **Open:**
  - GitGuardian (not a required check) flags a placeholder JWT in `ProtectedResourceMetadataSuppressionFilterTest`. Only the owner can dismiss it in the dashboard.
  - Review info items IN-01..IN-03 are recorded in `38-REVIEW-DISPOSITION.md`. IN-04 is this stale handoff, now fixed.
- **Worktrees:** `../JToye_OaaS_2026-phase37` now carries the #899 branch; the Phase 38 branch is deleted locally and on origin. `../JToye_OaaS_2026-bizalign` and `../JToye_OaaS_2026-phase37-ops` each hold one UNPUSHED planning commit from other sessions. They are surfaced here and were not touched.

**Next, in order:**
1. In the main checkout (branch `phase-31.1-persona-gap-closure`), merge `origin/main`, then `/gsd-execute-phase 31.1`. Plan 31.1-01 re-checks that Boot 4.1 is on `origin/main` (D-21) and reserves V68..V75.
2. Then Phase 37 via `/gsd-discuss-phase 37`, the owner's order.
3. Plan Phase 39 before its first dated horizon row expires on 2026-10-26. An expired row reds the required Operational Contracts check on every branch.
4. Leave `../oaas-archify-diagrams` and `phase-29-research` alone.

**2026-10-04 (night) delta: Phase 38 is planned and ready to execute; the local runtime is current.** (SUPERSEDED by the 2026-10-06 delta above.)
**Where things stand.**
- **Runtime rebuilt and verified.** The owner fast-forwarded the main checkout to `5936c644`. Then `scripts/sync-runtime.sh` rebuilt core-java and frontend, and its freshness gate passes for all 4 services. Inside the running `/app/app.jar`, `jackson-core` and `jackson-databind` are 2.21.7. The frontend is running #888.
- **Phase 38 (Spring Boot 4.1) is PLANNED** on `phase-37-spring-boot-4-1` (worktree `../JToye_OaaS_2026-phase37`, pushed, head `767f5658`). It merged `main` after #893/#894, and outside `.planning/` it is byte-identical to `main`.
  - **Artifacts:** `38-RESEARCH.md`, `38-VALIDATION.md`, `38-PATTERNS.md`, `COVERAGE.md` and 19 plans in 11 waves. Requirements BOOT4-01..14 are in that branch's `REQUIREMENTS.md` and `ROADMAP.md`.
  - **Checks:** the plan-checker passed after one targeted revision. In that revision, 38-12's 33-file Jackson-2 test sweep moved to new plan 38-19, and the BOOT4-14 doc edits moved from 38-03 to 38-16. The verify-path and failing-direction probes report severity `none` on all 92 commands. Decision coverage is 5/5.
  - **New owner decision D-05 (2026-10-04):** Spring Security 7.1 always serves `/.well-known/oauth-protected-resource` and falsely claims cert-bound tokens. The ruling is to suppress it with a 404 filter (Boot 3.5 parity), not to serve corrected RFC 9728 metadata. D-01..D-05 are reformatted to `**D-NN — title**` so the decision-coverage parser can read them.
  - **Research findings that change the plan:**
    - Jackson 3 sorts properties alphabetically, which would 422 idempotency keys reserved before the deploy; the fix is a dedicated frozen hashing mapper.
    - Explicit starters silently drop zipkin tracing and the RestClient/WebClient builders.
    - 5 tests' Redis excludes name a class that Boot 4 renamed.
    - CVE floors: drop the netty pin, Tomcat 11.0.26, Jackson 2 re-keyed to `jackson-2-bom.version` 2.22.3, Jackson 3 at 3.1.7. Whether CVE-2026-91777 is fixed on 3.1.7 is UNCONFIRMED; 38-15's local Trivy run settles it.
  - **Owner checkpoints during execution:**
    - 38-05: keep Jackson 3 defaults or restore Jackson 2 defaults, decided after the measured wire diff (one-way).
    - 38-17: who drives the shared compose stack for the rebuild.
    - 38-18: pushing for CI and the nightly E2E.
  - **Expected interim red:** between 38-03 and 38-16, `check-dependency-horizons` and `check-doc-versions` exit 2. This is recorded in 38-03 and cleared by 38-16.
- **GSD traps hit while planning:**
  - `state.planned-phase` overwrote the STATE "Phase:" history line and miscounted `total_plans`, so STATE was hand-edited. This is the same family as `state.record-session`.
  - The decision-coverage gate fails closed on `**D-01 Title.**` bullets; it needs `**D-01 — Title**`.
  - An EnterWorktree-isolated session refuses compound shell commands. Plan from a session OPENED in the worktree, not one entered mid-session.
- **Housekeeping (this delta):** every doc gate passes on `main`: claims 47, doc-metrics 37, docs-freshness, citations 28, doc-versions 151, handoff-contract and gate-enforcement. Local branch `pr892` was deleted; it was a review fetch whose tip equalled #892's merged head. Toolchain: carl-core is PIN-BEHIND (1.0.2 pinned, 2.0.0 released). Nothing was applied, because toolchain changes are machine-session work.

**Next, in order:**
1. Execute Phase 38 from a FRESH session opened in `~/IdeaProjects/JToye_OaaS_2026-phase37`: `/gsd-execute-phase 38`. Wave 1 (38-01, 38-02) captures Jackson-2 golden fixtures and Boot-3.5 baselines BEFORE any Jackson-3 change, so do not reorder it.
2. Then plan Phase 31.1 (the 17 persona clusters on completed Phase 31), then Phase 37 via `/gsd-discuss-phase 37`. The owner set this order.
3. After the next scheduled nightly, check `onboarding-blocked-flow.spec.ts` by content (#888), and check that a failure refreshes #683 rather than filing a new issue (#892).
4. Leave `../oaas-archify-diagrams` and `phase-29-research` alone.

**2026-10-04 (late) delta: duplicate-issue filing fixed, the orgos charters re-synced, the main checkout resolved.** (SUPERSEDED by the 2026-10-04 night delta above.)
**Where things stand.** Read the current head with `git log -1 origin/main`.
- **`main` is green.** The post-merge CI/CD run for #890 (run 37199749497) passed in full.
- **The Spring Boot branch is green.** `phase-37-spring-boot-4-1` merged `main` at `fecfd6c3`, and that is the branch's first passing run since 10-01 (run 37212702610). In it, the core-java Trivy image gate ran and passed. Outside `.planning/` the branch was byte-identical to `main`. It is behind `main` again by the PRs merged after it; merge `main` before Phase 38 execution starts.
- **#892 fixed #885.** Three issue de-dup sites (the e2e-nightly escalation and the base-image-freshness findings and VOID steps) now use `--search "in:title …"` and keep the exact-title jq filter. Their rendered step scripts, run against the live repo, give: `main` misses #683 at all three sites, the branch finds it, and a substring-title control is rejected. The next scheduled failure is the in-situ proof.
- **Duplicate pair:** issue #884 was closed as a duplicate of issue #759.
- **Orgos charters re-synced** (the OaaS side; PR named in the merge that carries this delta). `sync_agents.py --target oaas` was run with a temporary `targets.yaml` aimed at a clean worktree, because the real target root is the main checkout. Orgos invariant T03 maps `oaas-platform` to `docker-compose.frontend-3100.yml`. That file is GITIGNORED, so T03 passes only on a machine that has the local file. This is an orgos-side defect: copy the file into a fresh worktree before syncing. `~/.claude/agents` was not touched; that is machine-session work.
- **The main checkout was resolved by owner ruling:**
  - The Jackson 2.21.7 hunk was superseded by #886 and restored.
  - The two 2026-10-03 positioning memos (UK competitive, Nigeria delivery) moved to the PRIVATE `jtoye-market-intel` repo, `gtm/research/` (market-intel #67, `077c8eb5`), because this repo is public. Their OaaS links are pinned to `43ed6bbf`. The `docs/analysis/README.md` index rows were dropped.
  - Quick-task 260831-jz4's `evidence/` (41 captured login-page files) moved to `~/IdeaProjects/JToye_OaaS_2026-evidence/quick-260831-jz4-evidence/`.
  - Everything was backed up first, byte-verified, to `~/IdeaProjects/JToye_OaaS_2026-evidence/main-checkout-uncommitted-20261004/`.
  - `.planning/state.json` and `.gsd/` are GSD runtime state, now gitignored by the PR carrying this delta.
  - The checkout itself is still on an old `main`. A fast-forward there is refused by the protected-branch guard, so the owner runs `git -C ~/IdeaProjects/JToye_OaaS_2026 pull --ff-only`.
- **Tooling trap:** a review launched from this OaaS-directory session against ANOTHER repo's PR (`/code-review <url>`, `/review-verify <url>`) is resolved by the round gate against OaaS's PR of the same number. It printed OaaS's sha as "ran on", and round 2 needed `--force-round`. Run cross-repo reviews from that repo's checkout. This is a note for a machine session.

**Next, in order** (owner-chosen planning order: 38, then 31.1, then 37):
1. Plan Phase 38: `/gsd-plan-phase 38` from `../JToye_OaaS_2026-phase37`, after merging `main` into that branch. Inputs: `38-CONTEXT.md` and `38-SPIKE.md`. D-01 must pick a Jackson 3 release that clears CVE-2026-89407, and the Jackson key is re-keyed, not deleted.
2. Plan Phase 31.1: a gap-closure plan for the 17 persona clusters that belong to completed Phase 31 (see `.planning/ISSUE-DISPOSITION.md` § "Persona user-testing 2026-10-03").
3. Phase 37: run `/gsd-discuss-phase 37` before planning. It has 87 clusters in 7 sub-themes, 9 of them P0 that gate Phase 32.
4. After the next scheduled nightly, check `onboarding-blocked-flow.spec.ts` (desktop) by content (#888), and that a failure refreshes #683 rather than filing a new issue (#892).
5. Leave `../oaas-archify-diagrams` and the `phase-29-research` branch alone.

**2026-10-04 (evening) delta: the pg-backup red is cleared (#890).** (SUPERSEDED by the 2026-10-04 late delta above.)
**Where things stand.** `main` = `14e8a5c2` (#890) plus the PR that merged this delta. Read the current head with `git log -1 origin/main`.
- **#890 cleared step 1 of the afternoon list below.** `infra/backups/Dockerfile` now names `libpcre2-8-0` on the existing `apt-get install -y --no-install-recommends ca-certificates` line, so the image takes `10.42-1+deb12u2`. A comment records why there is no `--only-upgrade` and no `=version` pin.
- **Proof before merge.** Both Dockerfiles were built with `--pull` on one base (`postgres@sha256:539ceaaa…`).
  - Trivy 0.70.0 with the gate's flags: the `main` Dockerfile gave rc=1, its only finding CVE-2026-103111; the branch gave rc=0 over 145 debian packages plus blobctl.
  - In the branch image: pcre2 is `deb12u2`, `ca-certificates` is installed, and `/etc/ssl/certs` holds 303 entries, the same as `main`.
  - `check-postgres-major-parity.sh` and `check-dependency-horizons.sh` both gave rc=0.
- **Proof after merge.** `Build and Push Images (pg-backup)` passed on `14e8a5c2` (run 37199749497, 12:37Z). Its Trivy image-gate step ran and passed, and the CronJob tag was published from the gated image. When this was written, the core-java and frontend image legs of that run were still in progress; read the run's final conclusion rather than assuming it.
- **Review.** Round 1 found 0 admissible findings and recorded 1: the quick-task record cites a branch commit, which does not survive a squash-merge, as the earlier rows already do. The series ended at round 1. Quick-task record: `.planning/quick/261004-hhy-fix-pg-backup-pcre2-cve-2026-103111/`.
- **Housekeeping.**
  - The `../JToye_OaaS_2026-pcre2` worktree and its branch were removed, local and remote; the tip equalled #890's head.
  - The worktrees `../JToye_OaaS_2026-jackson2217`, `-reviewgate` and `-jackson` from afternoon step 7 no longer exist on disk.
  - **Trap:** a `git push origin --delete` from the main checkout is refused by pre-push P-2, because that checkout is behind `main`, even though a delete lands nothing. The branch was deleted with `gh api -X DELETE repos/Bralabee/JToye_OaaS_2026/git/refs/heads/<branch>` instead of `--no-verify`.
- **Unchanged:** the main checkout (`../JToye_OaaS_2026`, on `c55e545a`) still holds the other session's uncommitted work. Leave it to that session.

**Next, in order** (the afternoon list below, renumbered, with step 1 done and step 7 mostly done):
1. Merge `main` into `phase-37-spring-boot-4-1`. It was 5 commits behind at 12:45Z (#887, #888, #886, #889, #890), and one more once this delta merges; count it with `git rev-list --count origin/phase-37-spring-boot-4-1..origin/main`. The Phase 38 STATE entry rule, the Jackson re-key, and D-01's Jackson 3 choice clearing CVE-2026-89407 all still hold.
2. After the next scheduled nightly, check `onboarding-blocked-flow.spec.ts` (desktop) by content (afternoon step 3).
3. Fix the de-dup (#885) before the next nightly failure (afternoon step 4).
4. Plan Phases 37, 38 and 31.1, in an order the owner picks.
5. Sync the OaaS side of the orgos charters.
6. Housekeeping: leave `../oaas-archify-diagrams` and the `phase-29-research` branch alone. Never clean up `phase-29-research`.

**2026-10-04 (afternoon) delta: the Jackson red is closed, the nightly flake is fixed at its cause, the review gate is re-vendored, and `main` is red again on a NEW base-image CVE.** (SUPERSEDED by the 2026-10-04 evening delta above.)
**Where things stand.** `main` is #886 (`bc3341d4`) plus the PR that merged this delta. Read the current head with `git log -1 origin/main`, not from this line.
- **`main` is red on `Build and Push Images (pg-backup)`** (run 37193466380). No code change caused it.
  - Trivy found CVE-2026-103111 (HIGH, a pcre2 out-of-bounds write) in `libpcre2-8-0 10.42-1+deb12u1`. It is fixed in `10.42-1+deb12u2`.
  - The package comes from the floating base `FROM postgres:15-bookworm` in `infra/backups/Dockerfile`.
  - The same job passed at 01:24 on `c55e545a` (run 37165437798). It then failed on every run from 09:19Z onwards (`c0c97541` #774, `19cd4572` #882, `f5682f5d`, `a76ebb74`, `bc3341d4`), and none of those touched `infra/backups/`. So the vulnerability DB moved between 01:24Z and 09:19Z. This is the daily-DB time-bomb again.
  - Like the Jackson gate, `build-and-push` never runs on a pull request.
- Three PRs merged this afternoon. All were squash-merged, and each has a closed review series:
- **#887: the review gate was re-vendored to canonical** (`f5682f5d`).
  - The old `review-record.yml` ran on `pull_request` and `pull_request_review`, so it executed a PR's own copy of a workflow that holds `statuses: write`. A PR could grade itself.
  - It now runs on `pull_request_target`, plus `workflow_run` from the new `review-record-relay.yml`.
  - The vendored `review-record-check.sh` counts only OWNER, MEMBER or the Copilot bot, and judges freshness by the head a record names.
  - `gates/review-gate-onboard.sh status` reports **ENFORCED, C1-C4 ok**.
  - The new path is proven live. On #888 a failing `review-record` status posted for the unreviewed head, then flipped to success on the comment carrying the Review-Record line.
  - Round 1 found 0 admissible findings and recorded 9. Findings 1, 2 and 5 concern the canonical gate code in dotfiles, so they are machine-session work. None passes the admission test from this repo, so none was filed.
- **#888: the nightly failure is fixed at its cause** (`a76ebb74`).
  - **Cause:** the 10-02 and 10-04 nightlies failed `onboarding-blocked-flow.spec.ts` because core-java's per-tenant `RateLimitInterceptor` (a bucket of 120, `refillIntervally` at 100/min) returned 429s to the serial one-tenant Playwright run. `GET /shops` got one with `Retry-After: 3`, and `POST /onboarding` got one with `Retry-After: 9`.
  - **The product bug:** the onboarding page showed any shops-fetch error as "Create a shop first".
  - **Page fix:** the page now shows an error panel with "Try again", which also re-runs a failed shops fetch, plus a loading state.
  - **Client fix:** `lib/api-client.ts` replays ONE 429, and only the limiter's own (problem type `https://jtoye.uk/errors/rate-limited`). It retries when `Retry-After` is 0-10 and waits `Retry-After` + 1 s, because the server floors the wait.
  - **Tests:** Jest passes 1891/1891, and every new test was shown failing on the pre-fix source.
  - **Not yet proven on the real stack:** the next scheduled nightly is the first E2E run on a tree that contains #888.
  - **Open question:** the E2E stack still runs the tenant limiter at the production default. Widening it, as #409 did for the public limit, is an owner call. On its own it would hide this bug rather than fix it.
- **#886: `jackson-bom.version` moved from 2.21.6 to 2.21.7** (`bc3341d4`). This fixes CVE-2026-89407 and CVE-2026-89425 (jackson-core) and CVE-2026-91776 and CVE-2026-91777 (jackson-databind).
  - **Comment block:** it now explains why 2.21.7 was chosen, says to delete the pin once Boot manages ≥ 2.21.7, and notes that CVE-2026-89407 also lists Jackson 3.
  - **Proof before merge:**
    - `dependencyInsight` resolves 2.21.6 on `main` and 2.21.7 on the branch.
    - Trivy 0.70.0, run over each boot jar with the gate's flags: rc=1 with the four CVEs on `main`, rc=0 on the branch.
    - Unit tests: 1330/0/1.
  - **Doc citations:** six live citations into `build.gradle.kts` moved +5 lines and were re-pointed by content. Three of them, in TESTING.md and CONCERNS.md, are outside `check-doc-citations.sh`'s scan set, and its token match passes a wrong wide range anyway.
  - **Merge:** it merged with a recorded merge-from-base waiver. The head was a merge of `main` into the reviewed head, the CHANGELOG kept both entries, and the reviewed files were byte-identical.
  - **After merge:** `Build and Push Images (core-java)` passed on `bc3341d4` (run 37193466380), so the Jackson red is closed.
- **Still open from the morning delta below:**
  - #683 is OPEN.
  - #883 is CLOSED, but not by triage. #774's own squash commit `c0c97541` closed it at 08:29Z through the `close #883` text in its message. So the morning rule ("do NOT close #883 while #885 is unfixed") was already broken when that delta merged.
  - #885 (the de-dup only scans the 100 newest open issues) is still OPEN, and #683 is outside that window. **The next nightly failure will file a third copy.**
  - Phases 37 and 38 and the 31.1 gap-closure plan are not planned yet.
  - The OaaS side of the orgos charter sync is not done.
- **The main checkout (`../JToye_OaaS_2026`, on `c55e545a`) still holds the other session's uncommitted work.**
  - Its `core-java/build.gradle.kts` hunk only changes the value to 2.21.7. #886 now carries that change on `main`, together with the comment updates.
  - Pulling in that checkout needs that one hunk dropped first. Leave that, and the `docs/analysis/` files, to the session that wrote them.

**Next, in order:**
1. **Clear the pg-backup red.**
   - On its own branch, add `libpcre2-8-0` to the package list of the existing `apt-get install -y --no-install-recommends ca-certificates` line in `infra/backups/Dockerfile`. Installing a package that is already present upgrades it to the candidate version. Bump that exact dependency, not the whole base image.
   - **Do NOT add `--only-upgrade` to that line.** It also applies to `ca-certificates`, which the base image lacks, so `ca-certificates` would silently never be installed. This was reproduced in the #889 review: rc=0, pcre2 at `deb12u2`, and `/etc/ssl/certs` nearly empty. Trivy and the `dpkg` check would both pass, but blobctl's TLS to Blob and Entra fails, and backups stop.
   - **Do NOT pin `=10.42-1+deb12u2`.** Bookworm mirrors keep only the current version, so the pin breaks the build as soon as a newer revision lands.
   - Prove it before merge:
     - Build both images with `docker build --pull -f infra/backups/Dockerfile infra/backups`, one from the main Dockerfile and one from the branch Dockerfile. Without `--pull`, the main-side result depends on whatever base image is cached locally. Record the base digest of each build.
     - Run Trivy 0.70.0 with the gate's flags (`image --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1`) on both images. Expect rc=1 naming CVE-2026-103111 on main and rc=0 on the branch.
     - In the branch image, confirm `dpkg -s libpcre2-8-0` reads `deb12u2`, that `ca-certificates` is installed, and that `/etc/ssl/certs` is populated.
   - After merge, check the result of `Build and Push Images (pg-backup)` on `main`.
2. Merge `main` into `phase-37-spring-boot-4-1`. It is behind by #887, #888, #886 and the PR that merged this delta; count it with `git rev-list --count origin/phase-37-spring-boot-4-1..origin/main`.
   - The Phase 38 STATE entry rule below still holds.
   - On that branch the Jackson key is re-keyed, not deleted.
   - D-01 must pick a Jackson 3 release that clears CVE-2026-89407.
3. After the next scheduled nightly, check `onboarding-blocked-flow.spec.ts` (desktop) by content; it should pass. If it fails again, pull the trace with `gh run download <id>` and look for a 429 that was not replayed.
4. Fix the de-dup (#885) before the next nightly failure. #883 is already closed, so the only open tracker is #683, and the de-dup cannot see it.
5. Plan Phases 37, 38 and 31.1, in an order the owner picks.
6. Sync the OaaS side of the orgos charters (step 5 of the morning delta).
7. Housekeeping. Remove the worktrees `../JToye_OaaS_2026-jackson2217` (#886 merged), `../JToye_OaaS_2026-reviewgate` (the branch of the PR that merged this delta) and `../JToye_OaaS_2026-jackson` (#760 merged). Leave `../oaas-archify-diagrams` and the `phase-29-research` branch alone. Never clean up `phase-29-research`.

**2026-10-04 delta — persona testing became Phase 37; Spring Boot 4.1 is now Phase 38; `main` is red on new Jackson CVEs. Resume here.** (SUPERSEDED by the 2026-10-04 afternoon delta above.)
**Where things stand.** `main` = `c55e545a` (#881).
- **`main` is red in two places, neither caused by a code change:**
  - CI/CD failed at `Build and Push Images (core-java)`. Trivy found 4 HIGH CVEs in Jackson 2.21.6
    (CVE-2026-89407 and CVE-2026-89425 in jackson-core; CVE-2026-91776 and CVE-2026-91777 in
    jackson-databind), all fixed in 2.21.7. That job (`build-and-push`) runs on pushes to `main`,
    `phase-*` and `phase/**` and on releases, never on pull requests, so a PR goes green without
    it. The Spring Boot branch has failed the same gate since 2026-10-01 (runs on `fc3bc4ed` and
    `77d140dd`). CVE-2026-89407 also lists Jackson 3 (`tools.jackson.core`) as affected.
  - The main checkout has uncommitted work from the evening of 2026-10-03, on no branch: the
    Jackson value bumped to 2.21.7 in `core-java/build.gradle.kts`, a modified
    `docs/analysis/README.md`, and two untracked `docs/analysis/*-POSITIONING-2026-10-03.md`
    files. These are the only copies. They belong to whichever session wrote them: do not stash,
    discard or sweep them into another commit.
  - The 2026-10-04 nightly (run 37171812464, on `c55e545a`) failed: 325 tests, 318 passed,
    1 failed, 6 skipped. The failing test is `onboarding-blocked-flow.spec.ts` "bad company number
    -> fix inline -> re-run checks -> honest in-review" (desktop): `#onboarding-shop` was never
    visible within 10 s. It is not yet triaged. The run's report artifact expires 2026-10-18. The
    nightly failed on 2026-10-02 and passed on 2026-10-03.
  - That failure filed #883 as a duplicate of #683. The escalation step's de-dup only looks at
    the 100 newest open issues, and #881's 103 issues pushed #683 out of that window. That is
    issue #885.
  - `docs-freshness` was red on `main` from #881 onwards, because this file was more than 3 merged
    commits behind. This update clears that.
- **Persona user-testing passes 1-2 (#881, epic #880) are merged.** 15 personas produced 123
  clusters (P0 16, P1 32, P2 49, P3 26), filed as 103 issues plus the epic, labelled
  `ux-persona-test`.
  - **Phase 37 = Real-world operations readiness:** the 87 clusters with no other home. It is not
    planned yet.
  - Phase 31's 17 clusters need a 31.1 gap-closure plan. 19 more go to Phases 29, 30, 32, 33 and 34.
    The mapping is in `.planning/ISSUE-DISPOSITION.md`.
  - Raw evidence (screenshots, scripts, session-state files) lives OUTSIDE the repo, in
    `~/IdeaProjects/JToye_OaaS_2026-evidence/`. Never commit the session-state files.
- **Phase 38 = Spring Boot 4.1 migration (#706).** It was opened 2026-10-01 as Phase 37 on branch
  `phase-37-spring-boot-4-1` and renumbered to 38 on that branch by owner ruling 2026-10-04
  (`37afb5a5`). The branch keeps its name. It is pushed, has no PR, and is checked out at
  `../JToye_OaaS_2026-phase37`. **Its plans are not written yet.**
  - The spike on 4.1.1, run on OpenJDK 25 because the host has no Temurin, was feasible:
    unit 1330/2 fail, integration 745/1 fail. Flyway is proven to run. spring-statemachine 4.0.2
    works on Framework 7.
  - The spike found three defects that no test catches today:
    - `KeycloakAdminClient` sends a garbage body under Jackson 3.
    - 18 `application*.yml` keys are silently ignored, including the Zipkin endpoint and prod log retention.
    - The netty and Tomcat CVE pins must move to their Boot-4 lines.
  - Owner decisions D-01..D-04: Jackson 3 now, explicit starters, keep statemachine, keep a plain
    `Bearer` 401. For D-01, pick a Jackson 3 release that fixes CVE-2026-89407, or the branch's
    image gate stays red after Jackson 2 is gone.
  - On that branch, read `38-CONTEXT.md` first, then `38-SPIKE.md`, both in
    `.planning/phases/38-spring-boot-4-1-migration/`. `38-SPIKE.patch` is a map of the package
    moves only: it used the classic starters, which D-02 reverses.
- **The phase-number collision.** `gsd_run query phase.add` scans sibling git worktrees, not
  branches. It skipped 37 only because `../JToye_OaaS_2026-phase37` had the Spring Boot branch
  checked out. #882 corrects the Phase 37 note on `main` and reserves Phase 38 there with a
  roadmap bullet. The same text is on the Spring Boot branch (`6f813335`). When that branch next
  merges `main`, `.planning/STATE.md` conflicts only on its own Phase 38 entry. `main`'s side of
  that conflict is empty, so resolve it by taking the branch's side (the Phase 38 entry). Taking
  `main`'s side deletes the entry.
- **Issue #648 is CLOSED.** The #726 fix was proven on the production path (edge → core, real chain,
  real converter). Bracketed break arms, run on `43ed6bbf`:

  | Run | Tests | Failures |
  |---|---|---|
  | clean | 16 | 0 |
  | `@PreAuthorize` removed | 16 | 2 |
  | `require()` ×2 removed | 16 | 3 |
  | `requireGroupAdmin()` removed | 16 | 1 |
  | clean again | 16 | 0 |

  The evidence is in the issue's closing comment.
- **orgos charters.** The jtoye-orgos pull request that makes the seven OaaS charters version-free
  merged 2026-10-01. They now point to `package.json`, `go.mod` and the build files instead of
  restating versions, because otherwise every dependabot bump either reds `check-doc-versions`
  (it checks the ORGOS block too) or gets reverted by the next sync. The OaaS side is not synced
  yet: the ORGOS block in `AGENTS.md` still names Next 16.3.6.
- Open pull requests besides this one and #882: dependabot #739 (springdoc 3.1.1, which cannot
  land before Boot 4.1), #775 and #776.

**Next, in order:**
1. Merge #882, then merge `main` into `phase-37-spring-boot-4-1` (keep the branch's Phase 38 STATE entry).
2. Move the Jackson pin to 2.21.7 on its own branch and PR, so `main`'s core-java image builds
   again. Changing the value is not enough: the comment block above it still says 2.21.6 in its
   reasoning ("WHY 2.21.6 and not 2.21.7") and in its delete condition, so update both. PR CI
   never runs the image gate, so the PR must prove the resolved version with
   `./gradlew dependencyInsight --dependency com.fasterxml.jackson.core:jackson-databind --configuration runtimeClasspath`.
   Then merge `main` into the Spring Boot branch again.
3. Triage the nightly failure named above before 2026-10-18. Do NOT close #883 as a duplicate
   while issue #885 is unfixed: the next failure would file a third copy. Close #683 into #883
   instead, or fix the de-dup first.
4. Plan Phases 37 and 38 (`/gsd-plan-phase 37` on `main`, `/gsd-plan-phase 38` from
   `../JToye_OaaS_2026-phase37`), plus the Phase 31.1 gap-closure plan. The order is the owner's call.
5. Sync the OaaS side of the orgos charters in its own PR: the ORGOS block in `AGENTS.md` plus the
   generated `.github/chatmodes`, `.github/instructions` and `.cursor/rules` files. All of them
   predate the R-16 charter sections, so the re-sync also adds those. Rewriting `~/.claude/agents`
   is machine-session work, so leave it to a machine session.
6. After Phase 38 merges, rebase or recreate #739. springdoc 3.1.1 is then part of the migration.

**Housekeeping (2026-10-01, still true 2026-10-04):**
- Left in place:
  - `feature/jackson-2.21.6-cve-2026-68497`: #760 merged, but the branch is still checked out at
    `../JToye_OaaS_2026-jackson`.
  - `feature/archify-architecture-diagrams`: #734 merged, but the branch is checked out at
    `../oaas-archify-diagrams`.
  - `phase-29-research`: never clean it up.
- After their PRs merge, remove the worktrees `../JToye_OaaS_2026-p37note` (#882) and
  `../JToye_OaaS_2026-handoff1001` (this update).
- Toolchain drift was reported on 2026-10-01 only, nothing applied: ripgrep, docker-ce and
  antigravity-hub are DRIFT, and carl-core is PIN-BEHIND.

**2026-09-30 (evening) delta — GDPR erasure fixed twice over (#764, #771); `main` green; the stack runs `eb2e98fd`.** (SUPERSEDED by the 2026-10-04 delta above.)
**Where things stand.** `main` = `eb2e98fd`. Items 1-3 of the morning delta below have merged:
#768 (terminal-state deferrals, which cleared the required Operational Contracts red), #766 (the
review-gate vendored copy refreshed), #767 (Phase 36 state) and #770 (issue #764: V67 `reviews`
UPDATE policy, tenant-scoped review lookup, photos deleted after commit). Then **#772 closed issue
#771**. A review's client-supplied `photoUrls` could name the shop's own catalogue images, and
erasure deleted them. Erasure now deletes a photo only if both hold:
- its key is `<tenant>/reviews/<orderId>/<name>` (`ReviewPhotoKeys`);
- no product, shop or media_asset row references it.

Review creation refuses anything else with a 400 of type `invalid-review-photo`. Since nothing
writes under that path yet, **no non-empty photoUrls value is accepted until a review-photo upload
path exists**. No migration: V67 is still head. Record:
`.planning/quick/260930-l63-fix-771-review-photourls-unvalidated-so-/`.

Measured, not remembered:
- **CI on #772's head (`e7b6cbdb`) passed.** The squash commit's tree is identical to that
  head's tree (tree `81aa953f`).
- **Nightly E2E:** ONE scheduled green, on 09-30 (on `c5d16ff6`). The 09-29 scheduled run
  FAILED (on `db725c94`); the 09-29 green was a manual `workflow_dispatch` (on `536daf41`).
  Neither green ran on a tree containing #770 or #772, so there is no full-suite E2E evidence
  on `eb2e98fd` yet. Issue #683 stays OPEN per its own rule.
- **Runtime:** core-java was rebuilt via `scripts/sync-runtime.sh`, and the freshness gate
  passes 4/4 on `eb2e98fd`.
- **Live probe:** a POST naming a real product image URL returned 400
  `https://jtoye.uk/errors/invalid-review-photo`. The wrong-email control returned a different
  400, and no row was written.

**Review series on #772 ended at round 1 with 0 admissible findings; 10 were RECORDED on the PR.**
Carry two forward to the review-photo upload design:
- a catalogue-referenced own photo survives erasure;
- the catalogue check runs in the transaction, but the delete runs in `afterCommit` with no
  re-check.

Neither is reachable through the application today: nothing in `core-java/src/main` writes
under `reviews/`. An object placed there some other way (a manual upload, a fixture, a restored
backup) is not excluded, and would make both reachable.

Two pre-existing defects were seen but not filed; they fail the admission test:
- `createReview` never checks that the order belongs to the slug's shop;
- the review endpoint takes the reviewer's email as a bare query parameter.

**Housekeeping (this evening).** Every gate passed: the claims gate (47/47), `check-doc-metrics`
(37/37), `docs-freshness`, and edge-go vet/build/tidy/`test -race`. Eight merged branches were
deleted locally and remotely; each tip equalled its merged PR's head.

Deliberately kept:
- `phase-29-research`, the canonical paused body;
- `feature/archify-architecture-diagrams` and `feature/jackson-2.21.6-cve-2026-68497`, both
  checked out in other worktrees.

The jackson branch shows as "unpushed" only because its remote was deleted after PR #760 merged.
Its tip `51fc3279` is #760's head, and the pin is on `main`. Its worktree
`../JToye_OaaS_2026-jackson` can be removed by hand.

Toolchain drift is reported, not applied: conda, npm, claude-code, gemini-cli, copilot, uv and
antigravity-hub, plus carl-core PIN-BEHIND.

**Next, in order:**
1. The dependabot queue: #765, #756, #751 and #739. Their check state moves while they are
   worked, so read it live (`gh pr checks <N>`) rather than from here. Durable facts: #756's
   `Run Tests` red is 6 Jest timeouts, all in
   `frontend/app/dashboard/__tests__/marketing-kitchen-shop-scope.test.tsx`, under the group
   bump; #739 (springdoc 3.x) needs Boot 4.1 (issue #706) and reds the OpenAPI Breaking-Change
   Gate, tests, integration tests, security scan and docs-freshness; a dependabot bump that
   changes a version the docs quote reds docs-freshness via `scripts/check-doc-versions.sh`
   until the live docs are updated on the PR.
2. Issue #648: a within-tenant BOLA on `/api/v1/sync/batch`. #726 added the fix
   (`@PreAuthorize` on `SyncController` plus `shopAccessService.require` in `SyncService`) and
   `SyncBatchAuthorizationIntegrationTest`. Verify the authorization is on the production path
   and the test fails with it removed, then close it. Issue #727 (sync-created products
   orphaned) is in the same code.
3. Gate-class issues that fail open: #761 and #769 (`check-doc-citations.sh`); also #758
   (workflow `run:` shell unparsed), #762 and #759.
4. Phase 29 stays PAUSED on the owner (staging DNS plus 3 operator secrets).
5. Upstream, not this repo: dotfiles issues #279 and #280 (review-gate holes found by #766's
   review).

**2026-09-30 delta (SUPERSEDED by the evening delta above; #768, #766 and #770 merged) — Phase 36 is merged; `main` passes CI but its required Operational Contracts check is red until #768 merges.**
**Where things stand.** Phase 36 is complete (18/18 plans with a SUMMARY). It merged as PR #763,
squash commit `c5d16ff6` "Phase 36: Azure Blob Storage Throughout (#763)", on 2026-09-29 at
22:14 UTC, after the D3 review series ended on round 2 with 0 admissible findings. The
`phase-36-azure-blob-storage` branch is finished. Read from GitHub on 2026-09-30, not remembered:
- **CI/CD run 36638381316 on `c5d16ff6` succeeded.** Every test job passed, including unit,
  Testcontainers RLS integration, public E2E and MCP, as did lint, the security scan, operational
  contracts (it ran on 09-29, the day before the deferrals expired), the OpenAPI gate and the k8s secret guard. All four image builds
  passed: core-java, edge-go, frontend and pg-backup. pg-backup published `:15-blob` only after
  its Trivy gate, with the scanned image's digest. The deploy jobs were skipped, as they were on
  the previous `main` run (`cce0723b`).
- **The first scheduled nightly on `c5d16ff6` (run 36658969040) succeeded.** Of 325 Playwright
  tests, 319 ran and passed, 0 failed and 6 were skipped (budget 6). The restore drill passed,
  with arm A restoring 0 and arm B restoring 23 = live 23. The five scheduled nightlies before it
  (09-25 to 09-29) were red; the last green one was 09-24. Issue #683 is still OPEN. Per its own
  rule, close it only when you can name the change that fixed it: the most likely one is Phase
  36's removal of the withdrawn retired-store images, and one green run does not make the lane
  trustworthy.
- **Required check red on every branch since 2026-09-30.** Fourteen deferrals in
  `docs/ops/terminal-states.yaml` expired that day, so `check-terminal-states.sh` X-2 fails the
  required **Operational Contracts** check on `main` and on every PR. **PR #768 fixes it:** 7
  rows resolved, because their rules already exist, and 7 re-dated to 2026-12-31 by owner
  ruling. Nothing else merges until #768 does; after that, merge `main` into each open PR.
- **Local runtime.** The compose stack is UP (10 containers healthy, core-java on :9090). The
  post-merge hook reports core-java and frontend as DRIFT, because their images predate the squash
  commit. The images were built from the phase branch, so whether the content actually differs is
  not measured. Run `bash scripts/sync-runtime.sh` before trusting the runtime for E2E.
**Next, in order:**
1. **Merge PR #768**, the terminal-state deferrals. It unblocks everything below.
2. **Merge PR #766** (review-gate vendored copy refreshed to canonical; its series ended at
   round 1) and **this PR**, each after a merge from `main`.
3. **Issue #764, the most serious open defect. It is TWO defects, and fixing only the first
   makes things worse.**
   - (a) `reviews` has no UPDATE policy, so erasure's anonymising UPDATE matches 0 rows and the
     erasure rolls back, but only after the review photos are deleted.
   - (b) `findByCustomerEmail` runs under `reviews_tenant_read`, which shows PUBLISHED reviews
     across tenants.
   - Shipping the UPDATE policy (V67) without the tenant-scoped lookup would let an erasure
     anonymise, and delete photos for, **another tenant's** reviews. Photo deletion must also
     move to after the commit.
   - In progress as quick task `260930-bvp` on branch `fix/764-gdpr-erasure-reviews`.
4. **Phase 29 stays PAUSED** on the owner's staging DNS and the 3 remaining operator secrets.
   Phase 36 no longer blocks it. Its hand-off is
   `.planning/phases/36-azure-blob-storage-throughout/36-PHASE29-HANDOFF.md`.
5. Still open from before: dependabot PRs #765, #756, #751 and #739 (#739 needs Boot 4.1,
   #706); issues #758 and #648. Two review-gate holes found by #766's review are filed
   upstream as dotfiles issues #279 and #280.

**2026-09-29 delta (SUPERSEDED by the 2026-09-30 delta above; 36-18 ran and the phase merged) — Phase 36 executed through 36-17; only 36-18 (the nightly on a runner) remains.**
**Where things stand.** Branch `phase-36-azure-blob-storage`, 17 of 18 plans with a SUMMARY
(`.planning/phases/36-azure-blob-storage-throughout/`). The remote copy of the branch is still at
`5b6e76bd` (pushed when the phase opened, measured with `git ls-remote` on 2026-09-29), so the
plan work is local only, and no PR exists for the branch. core-java stores
media through the Azure Blob SDK: Azurite locally (the digest-pinned 3.37.0 image, loopback 10000,
public `jtoye-images` and private `jtoye-quarantine`), and Workload Identity against `jtoyestgmedia`
/ `jtoyestgbackup` in staging (`jtoyeprodmedia` / `jtoyeprodbackup` in production). The pg-backup
image is `:15-blob` and uploads with `blobctl`. A repo-wide gate,
`scripts/check-no-object-store-residue.sh`, keeps the retired store out.
`docs/metrics.json` was regenerated once, in 36-17, to **4130** logical invocations.
**Requirements:** BLOB-01, 03, 05, 07, 08, 09 and 10 are complete. BLOB-02 and BLOB-06 are partial
until Phase 29 provisions the real accounts. BLOB-04 is partial until 36-18 runs the nightly. Each
row in `.planning/REQUIREMENTS.md` names its remaining limb.
**Phase 29 hand-off:** `.planning/phases/36-azure-blob-storage-throughout/36-PHASE29-HANDOFF.md`
covers the superseded decisions, the operator secrets dropping from 7 to 3, the provisioning order
(the runbook `docs/runbooks/azure-blob-provisioning.md`), the verification items, and a
35-file merge-conflict map for `phase-29-research`. That branch was read, never written.
**Resume:** run plan 36-18. It is not autonomous: it needs the owner's approval to push the branch
and dispatch one nightly run, then it reads that run to its report and takes the final
`scripts/check-runtime-freshness.sh` and `scripts/check-branch-behind-base.sh` readings. The broken
windows open in `.planning/WINDOWS.md` (#1 name check, #2 nightly drill never run on a runner, #3
live AKS admission path) belong to 36-18 and Phase 29.

**2026-09-28 delta — state analysis, `main` green again, and Phase 36 (Azure Blob) opened.**
**Where things stand.** `main` = `db725c94`. PR #757 is MERGED: `amqp-client` 5.33.1 → 5.34.0 for
CVE-2026-75516 (issue #754 CLOSED) plus the missing `fi` that had kept
`base-image-freshness.yml`'s tracking-issue step from ever filing. `main`'s post-merge run
`36457853757` succeeded with the Trivy image gate step itself green on core-java — the first green
image gate on `main` since 2026-09-13. The gap that let the `fi` hide (nothing parses shell inside
workflow `run:` blocks) is issue #758, OPEN.
**Why the stack cannot simply be restarted.** Two findings from the analysis, both still true: the
weekly `docker-prune-weekly` run on 2026-09-27 deleted EVERY project image because the stack was
down (volumes survived — `postgres_data` 448.2MB and the retired object store's data volume 3.6MB,
matching the 09-22 checkpoint); and the retired object store's vendor withdrew its community images
for good (repo archived 2026-04-25, its `quay.io` images answer 401 to anonymous pulls — control
images on the same registry answer 200).
That second fact is the whole cause of the nightly E2E being red since 2026-09-25 (issue #683 OPEN;
its body still points at core-java health, which is wrong). Do NOT try to re-source the retired store.
**Owner ruling: object storage becomes Azure Blob throughout (Phase 36).** Staging/prod on Azure
Blob Storage, Azurite (`mcr.microsoft.com/azure-storage/azurite`) locally and in the nightly;
the self-hosted store it replaced and the never-provisioned AWS object-store target retired. Four
decisions are locked in
`.planning/phases/36-azure-blob-storage-throughout/36-CONTEXT.md`: backups to a separate immutable
Azure account in another region (SUPERSEDES Phase 29 D-12, the subscription-loss trade recorded),
AKS Workload Identity (no stored key), reseed local media (leave the retired store's data volume in place, 36-CONTEXT D-04), raw Blob
endpoint (custom domain deferred to Phase 32). Phase 36 blocks Phase 29 plans 29-11 onward and cuts
Phase 29's unfilled operator secrets from 7 to 3.
**Resume:** Phase 36 is planned and in execution on branch `phase-36-azure-blob-storage`; plan
progress is in `.planning/ROADMAP.md` and each plan's SUMMARY under
`.planning/phases/36-azure-blob-storage-throughout/`. The runtime now stores media in Azurite
locally (containers `jtoye-images` public, `jtoye-quarantine` private) and targets Azure Blob
Storage with Workload Identity in staging/production.
**Still open from the analysis, not started:** dependabot PR #739 (springdoc 3.1.1) cannot merge on
Boot 3.5 — its parent POM is Boot 4.1.0 — and belongs after #706; #751/#749 are low-risk but need
the doc-quoted versions updated; #756 fails 6 Jest tests under React 19.3; issue #648 is fixed in
code by #726 (`SyncBatchAuthorizationIntegrationTest`) but still OPEN; the Base Image Freshness
workflow has been red daily since 2026-09-08 (libexpat CVE-2026-93990 on the published image); the
review-gate script vendored at `scripts/gates/review-record-check.sh` lags the canonical copy
(missing the #249 cancelled-run supersession); `.planning/state.json` is an untracked GSD Core
artefact, deliberately never committed.

**2026-09-22 delta — the runtime was re-proven fresh, then deliberately torn down; ordinary work resumes 2026-10-01.**
The local stack was found RUNNING but stale: `scripts/check-runtime-freshness.sh` failed with the
frontend image tagged 2026-09-07 against build inputs that moved on 2026-09-13 (`0a0e277f`, sharp
0.35.4). Rebuilding it surfaced a composition of two known traps that is worth carrying forward,
because neither one alone predicts it. First, `docker compose -f docker-compose.full-stack.yml up
-d --build frontend` is **not frontend-only**: `frontend` depends_on `core-java`, so compose
rebuilt and recreated core-java as well. Second, the recreated core-java then took host port
**:9091** out of its `9090-9091:9090` range, because the container it replaced was still releasing
:9090 at creation time. The frontend bundle bakes `NEXT_PUBLIC_API_URL=http://localhost:9090` at
build time, so that pair silently moves the API off the port the browser calls while SSR keeps
rendering pages perfectly — the #671 failure mode, reached through a door the #671 note does not
name. Recovery was `up -d --force-recreate --no-deps core-java` (it took :9090 first try), after
which the monitoring stack was started so Prometheus pins :9091 and the range cannot re-roll.
**That mitigation has a price, and it is not free:** `docker-compose.full-stack.yml` declares the
`9090-9091:9090` range specifically so `--scale core-java=2` can give replica 2 host :9091, and
Prometheus squatting that port makes a 2-replica scale-up fail on port allocation. It buys a
correct single-replica dev stack at the cost of the documented 2-replica one. The durable fix is
at the root and is NOT in this change: either pin `"9090:9090"` for the single-replica dev
topology, or teach `scripts/check-runtime-freshness.sh` to assert the PUBLISHED HOST PORT — it was
green throughout this incident while the smoke suite was failing every test, because it compares
image timestamps and never looks at where the container is published.
Re-proven afterwards, in this order: the freshness gate green on all four built services; the
smoke suite passing every test — and it had been observed failing every test minutes earlier while
the port was wrong, so the instrument was shown able to fire; and the real browser-side call,
`GET /public/shops`, returning three shops, which is the exact request
`frontend/app/shop/shop-discovery-client.tsx` issues. Both compose projects were then brought down
**without `-v`**, so all six named volumes survive. The checkpoint to compare against on the next
bring-up, read from the live database rather than remembered: schema head **V66**, and the
`shops` TABLE at 5 rows. Note the two shop numbers in this paragraph are different measurements
and the reconciliation was NOT established before teardown: `GET /public/shops` returned 3, the
table holds 5, and whether the other 2 are unpublished/inactive or something else is unverified.
Use the table count as the volume checkpoint, and do not read a 3 from the public endpoint as
evidence of loss until that filter is confirmed.
**Opened in the same session:** a PR raising the `netty.version` pin to 4.1.137.Final for
CVE-2026-75595 (an SNI/mTLS bypass, CVSS 9.1) and CVE-2026-75596. It had been sitting as an
uncommitted working-tree edit that a rebuild had already baked into the running image — live in the
runtime, on no branch and in no review — which is the state this delta exists to end.

**2026-08-31 delta — customer-surface P0/P1 fixes (PR #711, quick task 260831-gnm).** A five-lane
human-like utilisation audit of the customer surfaces found 15 defects; PR #711 fixed the six with
root causes in frontend code — the **P0**: vendor Sign Out never ended the Keycloak SSO session
(one click silently re-entered the dashboard) — plus the `/shop` search-revert, the stale-response
race, the hero paint-then-vanish, the non-fail-safe customer signout teardown, and the systemic
cookie-notice overlay. All six browser-verified fail→pass in two rounds with the audit's own repro
scripts. Full record: `.planning/quick/260831-gnm-fix-p0-p1-customer-surface-audit-finding/` and
the memory file `project_customer_surface_audit_20260831.md`.
**Same-day follow-ups (later on 2026-08-31):** the Keycloak realm half (R-05/R-06/R-11) shipped —
SMTP via Mailhog + a custom `jtoye` login theme, quick task 260831-jz4, merged as `44bf842e`; the
owner then found **R-16**, the "anonymous downgrade" cart-ownership leak (a newly registered
customer inherited the previous account's basket — a signed-out render re-stamps `owner: null`,
which any next sign-in adopts), fixed in quick task 260831-lxf via PR #715 with the lesson
institutionalised (memory `trap_identity_transition_state_laundering`, qa-discover
identity-transition sweep, three agent charters via jtoye-orgos PR #29 — that PR awaits an
owner-side GitHub Actions billing fix, then `make agents-all`). **Still open from the audit**: the
`/shop` CLS 0.1616 breach — **not** a `size-adjust` fix: measured 2026-09-02, the running build
already ships `size-adjust:111.93%` for the Work Sans fallback, and the cause is the flex-wrap
chip row at `frontend/app/shop/shop-discovery-client.tsx:524` (20+12+2 chip + 8 gap = 42 px exactly);
`frontend/e2e/perf-budgets.ts`'s docstring still misattributes landing CLS to hydration —
vendor back-channel logout (front-channel only today), the P2/P3 register, and two filed follow-ups: #714 (Keycloak theme-contract gate) and the
sibling-`.verify.mjs` wiring gap issue from the #715 review. Known residuals recorded in the PR
descriptions of #711, #713 and #715.
**2026-08-31 evening delta — basket bar branded + landing redesign parked.** The owner reported the
first add-to-basket popup rendering unbranded; investigation showed no toast at all — the floating
cart bar's deliberate grey below-minimum state (colour-as-state reading as a dead control). Fixed
via PR #718: always oxblood, the signal moved to an amber label stating delta AND rule
("Add £1.50 to order · min £10.00"), arithmetic unified in `lib/minimum-order.ts` (bar + checkout),
the bar extracted to `components/storefront/floating-cart-bar.tsx`, and all five findings of the
in-session /code-review applied (the interim contrast-scan test-file exclusion was reverted; the
suite lives in `frontend/__tests__/` instead — the gate's own documented placement). Verified live
on the rebuilt 4/4-fresh stack, both directions, at brixton-village-grill. In parallel the landing
redesign ran three sketch rounds (005 all rejected as re-skins; 006 winner A "The Pass" after a
malformed round taught the eyeball-390/768/1280/1920 rule; 007 full-page elaboration), merged as
PRs #719/#720 — and the owner then ruled: **the shipped 004-D landing stays as-is; 006-A/007 are
parked for later, not rejected** (PR #722 records it in the sketch MANIFEST; do not reopen
unprompted). PR #721 fixed the STATE.md row that cited a pre-squash sha. Residual filed in STATE:
dashboard toasts are still stock-shadcn white (`--secondary/--muted/--accent` never got the brand
refresh) plus two upstream `use-toast` staleness bugs.
**2026-09-02 delta — QA council run `20260902-134741` discovered and planned; remediation is the
next command.** Full `/qa-discover` + `/qa-plan` against `1833fd3b` on the proven-fresh 4/4 stack
(gate sweep 41: 39 PASS / 2 FAIL, both pre-existing — `check-alert-metrics` post-restart, and the
`check-dependency-horizons` **RabbitMQ 4.3 EOL 2026-11-30** in-window breach the manifest predicted —
since filed as #724 and deferred to the horizon by PR #725, merged 2026-09-02 evening, so the gate
is green again until 2026-12-01). **97 findings (C:2 H:23 M:36 L:36)**, 83 probes each
with a recorded fail arm, regression-by-omission NONE. The Criticals: **API-1** — the documented
read-only `integration-catalog-ro` credential writes the catalogue via `POST /api/v1/sync/batch`
(`SyncController` has no `@PreAuthorize`; the human BOLA is live for any explicitly-granted user
per `ShopAccessService:326-328`) — and **FE-1** — vendor Sign Out leaves the NextAuth cookie valid
because `@auth/core` re-issues the JWT on every session GET and `frontend/lib/api-client.ts:31`
fires ~24 per dashboard load; the other half of #711. Plan state `planned`: 20 HIGH ids, 52 LOW, 14 opt-in, 11
deferred; 26 adjudications; a separate refuter (5 REFUTED / 14 WEAKENED / 9 SURVIVES, every
REFUTED re-verified by the orchestrator — two would have been outages: a Redis allowlist missing
`java.lang.`, and a Companies House 404→FAILED remap with no zero-padding). Owner resolutions:
**E-1 yes** (vendors take delivery orders by phone/API/MCP → COR-1 ships as Fix A+B, Fix A alone
forbidden); **E-2** the target is a payment request to the customer's registered *confirmed* phone
(= #461, which now depends on #462; INT-9's interim string is `"Unpaid"`); **E-5** fail-safe —
config-injected `post_logout_redirect_uri` + new `scripts/check-keycloak-logout-uri.sh` +
`VENDOR_LOGOUT_COMPLETE_ENABLED` off outside compose. Everything lives in the git-excluded
`.qa-council/20260902-134741/` (`plan.md`, `findings.json`, `refutation.md`, `RETROSPECTIVE.md`)
and memory `project_qa_council_20260902.md`; the procedure lessons went into
`~/.claude/commands/qa-*.md` (dotfiles PR). **Not yet done — an owner checkpoint:** nothing is
filed except the RabbitMQ horizon (#724, deferred to 2026-11-30 by PR #725) — still unfiled: 2
Criticals, the top Highs, a docs epic, amendments to #648/#453/#711, and 9 new defects the refuter
surfaced — and `/qa-remediate 20260902-134741` has not run.
The gate expectation at "Resume here" is 47 (46 `scripts/check-*.sh` + `scripts/docs-freshness.sh`,
which is exactly what `check-handoff-contract.sh` H-1 counts) — this line said 41 until 2026-09-04,
contradicting the EXPECT claim below it in the same file (43 then; 44 since plan 36-08 added
`scripts/check-backup-restore-drill.sh`, 2026-09-29; 45 since plan 36-12 added
`scripts/check-media-urls-resolve.sh`, 2026-09-29; 46 since plan 36-16 added
`scripts/check-no-object-store-residue.sh`, 2026-09-29; 47 since plan 38-11 added
`scripts/check-boot-config-keys.sh`, 2026-10-05). H-1 does not read this sentence (no `**`
marker), which is the semantic rot that gate's own closing NOTE says it cannot detect.

**2026-09-04 delta — the remediation RAN, and this file said it had not.** The block above ends
"`/qa-remediate 20260902-134741` has not run"; it had, across ten `qa/cluster-*` lanes, and this
file simply was not updated. Corrected here rather than rewritten above, so the record shows what
was believed and when. **Branch `feature/qa-remediate-20260902` is now PR #726 (MERGED 2026-09-07), 76 commits /
245 files / +16k-3.5k against main.** Both Criticals are in: **API-1** `9980ad17` gates
`POST /api/v1/sync/batch` by scope + shop grant, validates items and derives the shop slug (the
read-only `integration-catalog-ro` credential could write the catalogue); **FE-1** `fe0c4a42` clears
the app session server-side on the Keycloak return leg (`@auth/core` re-issued the JWT on every
session GET, so Sign Out left the cookie live). Two migrations ship: **V65** narrows the six legacy
Envers `_aud` INSERT policies off `WITH CHECK (true)` — the `IS NULL` arm is load-bearing, because
Envers DELETE revisions carry `tenant_id` NULL by construction and the naive predicate breaks every
product/order/customer DELETE — and **V66** adds `orders.unit_count` beside an untouched
`item_count`, no backfill, NULL ≠ 0. `origin/main` is still stamped V64 until this merges, so
CLAUDE.md's "Current schema version" is correct for merged state and goes stale on merge.
**CI on `60132305`: 15 pass / 1 fail / 4 skipped.** The single failure is `review-record`, which is
head-scoped and wants a human — "no review record for head 60132305". Two CI failures were fixed en
route and neither was the remediation's fault: the OpenAPI gate + `OpenApiSnapshotTest` failed on a
stale `docs/api/openapi-snapshot.json`, regenerated in `2f39df00` — oasdiff calls it breaking
because three `200`s disappear, but `HttpStatus.CREATED` is unchanged between main and the branch
(1×/1× in `OrderController`, 2×/2× in `PublicStorefrontController`), so the snapshot had documented
a status no client ever received; and Trivy failed on four HIGH `fast-uri` CVEs that this branch
never introduced (`3.1.5` on main too, `mcp-server/package-lock.json` diff EMPTY against main),
bumped to 3.1.7 in `60132305` — the daily-DB time-bomb, not a regression. Docs and gates closed
alongside: `docs/metrics.json` was stale at 3572 against a tree measuring 3912 and is regenerated
with 22 prose claims reconciled; `.planning/codebase/` remapped (four of seven documents dated
2026-04-18); and `scripts/check-doc-versions.sh` gained a `Go` row after passing 119 claims while
every doc said Go 1.26 and the module had been on 1.27 since #674 — coverage 119 claims/6 docs ->
147/7, proven able to fail before being trusted. Full record:
`.planning/quick/260903-psy-fix-docs-freshness-metrics-drift-and-sta/`. **Not this session's work:**
`b3fd1f05` (enable graphify + ignore the 163 MB it generates) came from a concurrent session on the
same checkout and was carried up by the push — unreviewed here.

**2026-09-05 delta — the PR #726 review was remediated on the branch, then `/housekeeping` ran.**
The review (M1–M7 + Lows: tenant-scoped sync shop upsert, same-shop order guard, payload-bound
checkout key, `state`-bound vendor logout, one redirect sanitiser, body≠header idempotency key
refused 400, `it.each<[...]>` counter hole) landed as `b88480b8`; the Tomcat 10.1.59 pin went to its
own branch as PR #733 with the same `fast-uri` 3.1.7 lock, so whichever merges second rebases cleanly.
**Both PRs sit at 15 pass / 4 skipped / 1 fail, and the one fail is `review-record` on each** — the
human gate, deliberately not self-satisfied (author ≠ verifier); merge is yours. Housekeeping
findings, all measured: every doc gate green from this tree (`check-claims` 47/47, `docs-freshness`
4003, `check-doc-metrics` 37/37, `check-doc-versions` 147/7 docs, `check-changelog-contract` 24/24,
`check-doc-citations` 43 verified — that last one was RED locally only because a stale duplicate of
#733's `build.gradle.kts` hunk sat uncommitted in this tree, shifting two cited line numbers; dropped
with owner OK). `docs/CHANGELOG.md` gained the `fast-uri` entry `60132305` had no line for.
`edge-go`: gofmt/vet/build/tidy/`test -race` all clean. **The compose stack is STALE for this
branch** — images built 2026-08-31 21:23Z, source last moved 2026-09-05 — pages render (`/`, `/shop`,
`/auth/signin`, `/track` 200; 404 page works) but that is the old code: `bash scripts/sync-runtime.sh`
before any E2E. Project memory hygiene went 41 FAILs → 0 (13 `type:` nestings, 27 kebab→snake link
stems, 1 missing description); the capture is committed on dotfiles branch `feature/oaas-memory-hygiene`
(`a7d334b`) but **unpushed by owner choice**: dotfiles' pre-push self-tests fail on the `master`
baseline itself (hook batteries 4/11, review-gate-onboard 16) — `feature/guard-prefix-splitter` looks
like the fix. ⚠ **New dotfiles trap, repaired here:** pushing from a LINKED WORKTREE hands the
pre-push hook an absolute `GIT_DIR`, and its test batteries' `git init` / `git -C /tmp/… commit` then
re-initialise the SHARED repo (`core.bare = true`, working tree unrecognised) and commit their fixtures
(`VERSION`, `f.txt`, a `.env` "leak") onto the pushing branch. Fixed with `git config core.bare false`
+ `update-ref` back to the real tip; nothing reached the remote. Neither the hook nor the tests unset
`GIT_DIR` — that is the dotfiles fix to make. Branch audit: nothing unpushed; no merged-PR branch left
local or remote; `phase-29-research` kept again (never had a PR, 112 unique files — k8s/base +
staging manifests and planning — an owner decision, not cleanup); the ten `qa/cluster-*` branches are
fully contained in `origin/feature/qa-remediate-20260902` but held by another session's worktrees under
`/tmp/claude-1000/`, so they are deletable only after #726 merges and those worktrees go. Toolchain
doctor: 8 DRIFT rows (gh, claude-code, gemini-cli, copilot, docker-ce, fabric-cli, fabric-cicd,
antigravity-hub) + `conda` MISSING — surfaced, not applied. Left as found: untracked
`.planning/quick/260831-jz4-fix-keycloak-realm-config-branded-login-/evidence/`.

**2026-09-07 delta — round 2 committed, the merge-with-main conflict resolved, the branch
concluded.** PR #733 (the Tomcat pin) merged to main on 2026-09-05 and made #726 CONFLICTING on
`core-java/build.gradle.kts`; the reconciliation merge `d3155923` (main → branch, in
`.worktrees/pr-726-fix`) resolved it. The 21-file round-2 review remediation found UNCOMMITTED in
that worktree (a JetBrains Junie session's work) was reviewed in full and committed as `9474805e`:
the unprotected logout-url GET made session-read-only (clearing lives solely on the state-verified
logout-complete leg), `safeReturnTo` control-character hardening with the in-band
`/\t/evil.example` foreign-origin proof, the checkout key re-bound to the last SUBMITTED payload,
the guest idempotency identity bound to the shop UUID with a legacy-compat arm behind an ownership
check, the webhook replay cache-hit authz gate, the `user_directory` refresh moved to afterCommit
REQUIRES_NEW, and a unit-count overflow guard. Two independent reviews then ran on that delta
(tenancy-security + release-QA): **no blocker**; the one SHOULD-FIX — `orders.create` carrying the
same cache-hit-bypasses-authz shape round 2 fixed for webhooks — closed in `2d8a4426` with the
webhook denial-test pattern ported and the full bracket run (pre-hoist controller: test FAILS;
restored by content: PASSES). OpenAPI snapshot regenerated for the new 410 + shop-bound wording
(`2906b8bd`); metrics 4041 → 4042 with the three prose docs reconciled each time. Instrument note:
the first full-suite run failed 116/318 integrationTest classes on `Could not connect to Ryuk at
localhost:32768` — Testcontainers sidecar churn under maxParallelForks=4, an environment failure,
not code; green on re-run with `TESTCONTAINERS_RYUK_DISABLED=true`. Frontend full suites: 172/172
Jest suites (1873 tests) + `next build` clean. **Reviewer residuals deliberately NOT chased into
this PR** (recorded in the PR description too): `media.upload`/`media.reprocess` share the
cache-hit shape at NIT severity (a hit discloses only asset id + status); the legacy-hash compat
arm has no sunset date; flag-OFF logout deployments rely on the client-side clear alone (the
documented E-5 residual). Main-checkout residue resolved: the uncommitted `build.gradle.kts`
Tomcat hunk was the SAME stale duplicate of #733 the 09-05 session already dropped once from this
tree — dropped again; its two companion pins (commons-lang3 3.20.0 over springdoc's 3.17.0,
commons-compress 1.28.0 over Testcontainers' 1.24.0, both scanner-suggested) are recorded HERE as
a candidate follow-up PR against main, not swept into #726.

**2026-09-07 later delta — the dependabot queue and the architecture diagrams merged (quick task
260907-a30).** Five PRs merged in one pass: #734 MERGED (edge-go + core-java interactive diagrams;
its counted claims were re-pinned to a990551e and migrations corrected 64→66 after #726 landed
V65/V66 — the staleness its own DIAGRAMS.md predicts), #730 MERGED (jest 29.7→30.5.1; jest 30
rejects the legacy goo.gl snapshot-header link, one line rewritten by `jest -u`), #728 MERGED
(AWS SDK 2.54.9 + Stripe Java 33.4.0 with the 6 doc version claims), #731 MERGED (codeql-action
4.37.9), #732 MERGED (download-artifact v8 — residual: its only usage sits behind the integration
path filter, so the v8-download/v7-upload pairing first executes on the next main run taking that
path; failure mode is check-jacoco rc=2 VOID, not a silent pass). The sixth, #729 (6 frontend
minor/patch bumps), is the queue's last PR and carries this delta. Its two 09-04 CI timeouts were
neither flaky nor its own bumps: bisection convicted the lockfile regeneration's TRANSITIVE float
`nwsapi` 2.2.24→2.2.27 (jsdom's selector engine; both Radix-Select role-query suites pass 20/20
~9x faster with only that pin reverted). The pin lives in `frontend/package.json` `overrides`;
issue #736 OPEN holds the exit criteria. The shared Security Scan red on all five dependabot PRs
was the Trivy `fast-uri` 3.1.5 daily-DB time-bomb, fixed on main by #733 after the branches were
cut — rebasing onto main cleared it, no code change. Full record:
`.planning/quick/260907-a30-shepherd-pr-734-and-dependabot-queue/`.

**Re-measure every figure here before quoting it forward** — that is this file's standing rule, and
the 2026-08-24 session broke it once itself (see "The truncating filter", below).

> **History moved out on 2026-08-18.** Five stacked session blocks are archived verbatim at
> **`docs/archive/HANDOFF-history-through-2026-08-17.md`** — Phase 28 close-out, Phase 33 shipped,
> and two process-forensics sessions. They carry measured break-arm results and trap mechanisms
> recorded nowhere else. The archive is **not** covered by `scripts/check-handoff-contract.sh`,
> which reads this file only — so its stale claims can neither red the build nor be trusted.

## Resume here

**#733 merged 2026-09-05; #726 was concluded by the 2026-09-07 session** — verify the actual
merge state with `gh pr view 726 --json state,mergedAt` rather than trusting this sentence.
The block below describes the `main` checkout once #726 has merged. After that merge the ten
`qa/cluster-*` branches and the `/tmp/claude-1000/…/qa/wt-*` worktrees holding them become
deletable, and `.worktrees/pr-726-fix` + `feature/fix-pr-726` go with them.

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git checkout main && git pull --ff-only && git status --short   # expect clean

# Gates. EXPECT 63 x rc=0 — and a VOID (2) is NOT a pass.
for g in scripts/check-*.sh scripts/docs-freshness.sh; do
  bash "$g" >/dev/null 2>&1 || echo "rc=$? $(basename "$g")"
done
# 2026-10-31 (agy/30-21-22-refund-e2e, Phase 30 30-21/30-22): 62 -> 63. check-stripe-ci-keys
# refuses unset, empty, live or swapped CI Stripe sandbox keys unless STRIPE_TEST_SECRET_KEY and
# CI_STRIPE_PUBLISHABLE_KEY are set; CI runs its --selftest in ops-contracts and the guard in e2e-nightly.
# 2026-10-25 (PR #1045, the Antigravity bundle): 61 -> 62. check-synthesis-gate is rc 1 in
# this bare loop until the active milestone's synthesis review (/qa-council + persona test)
# is recorded in qa/synthesis-ledger.json with --record; CI runs only its --selftest.
# 2026-10-22 (fix/957-card-checkout-payment-step, Phase 30 PR 1): 59 -> 61.
# Both take arguments, so in this bare loop each prints rc=2 (usage error, measured
# 2026-10-22): run them as CI does. check-stripe-pk-var refuses a deploy whose publishable
# key is empty or of the wrong mode (`--selftest`, or STRIPE_PK_VAR in the environment plus
# `--environment staging|production`). check-frontend-stripe-key-baked reads the key out of
# the live staging checkout chunks (the staging-edge-probe arguments in ci-cd.yaml); it is
# rc 1 until a keyed image is deployed (plan 30-04).
# 2026-10-22 (fix/1025-ingress-cors-idempotency-key): 58 -> 59. check-cors-preflight asks the
# live staging api host whether the order POST's preflight admits the browser's headers
# (issue #1025); it curls a public host, so from a laptop it needs network, not a stack.
# 2026-10-21 (ci/doc-only-skip): 57 -> 58. check-ci-docs-skip-wiring fails a doc-only skip that
# reaches a cheap gate, or an Integration Tests condition that no longer runs the suite when
# the `changes` job itself fails.
# 2026-10-20 (PR #984 review round 1, p29/r1-platform): 56 -> 57. check-deploy-rollback-guard
# fails a deploy-staging rollback that can run before the apply was reached (F2).
# 2026-10-20 (phase 29, branch phase-29-deployable-staging): 48 -> 56. Phase 29 added
# check-alertmanager-render, check-az-subscription-pin, check-deploy-digest-parity,
# check-dply02-dispositions, check-frontend-baked-origin, check-k8s-copy-parity,
# check-networkpolicy-enforcement and check-staging-edge (H-1 caught the stale 48).
# 2026-10-11 (phase 37 plan 37-04, PR #913): 47 -> 48. scripts/check-strict-scoping-default.sh fails any
# runtime that overrides jtoye.access.strict-scoping back to false (D-06).
# 2026-10-05 (phase 38 plan 38-11): 46 -> 47. scripts/check-boot-config-keys.sh runs
# ConfigKeyContractTest through Gradle (a JDK, ~1 min) and fails on any Boot-4-unbound key.
# 2026-08-29 actual (phase 34 closeout, plan 34-10): all 40 rc=0 from the MAIN checkout,
# including check-e2e-skip-budget re-earned at 6 skips / budget 6 on a fresh full-suite
# run (297 tests, 0 failures), and check-jacoco-coverage at 88.07/71.95/87.55/87.53.
# 2026-08-30 (phase 35 plan 35-13): the count moved 40 -> 41. Plan 35-10 added
# scripts/check-layout-width-contract.sh, which is the 41st gate; H-1 caught the stale
# EXPECT immediately, which is the gate working. Phase-35 sweep from the MAIN checkout:
# 41/41 rc=0, but only after two ENVIRONMENT repairs that are not code defects — a stale
# untracked edge-go/coverage.out (regenerate with: cd edge-go && go test -coverprofile=coverage.out ./...)
# and a jtoye-redis-exporter still holding a REDIS_PASSWORD rotated out of .env
# (repair: docker compose -f infra/monitoring/docker-compose.monitoring.yml up -d --force-recreate redis-exporter).
# Neither is repaired by `docker restart`; the exporter needs a compose RECREATE.
# check-e2e-skip-budget is rc=1 not rc=0 on the phase-35 branch: 7 skips / budget 6, one
# undeclared (onboarding-blocked-flow) — that is open issue #686, not a phase-35 regression.
# THREE gates must be run from the MAIN checkout, not a worktree, and VOID elsewhere:
#   check-runtime-freshness  — compose project name comes from the DIRECTORY
#   check-infra-exposure     — parses `docker compose config`, needs .env to interpolate
#   check-container-config-drift — same .env dependency
# If check-alert-metrics is the only rc=1 after a core-java rebuild, its standing
# remedy is: bash scripts/seed-order-metric.sh   (restart zeroes the counter). Note it can
# also be green without the remedy if a full E2E run has just placed real orders.
```

| | |
|---|---|
| `main` HEAD | tip of `main` at or after the **PR #658** merge — deliberately NOT a sha, see below |
| Phase 31 | `42ac6dc3` — `feat(31): consumer safety and the legal floor (#633)`, 18/18 plans |
| Working tree | NOT re-measured by the 2026-09-30 delta — at the time of writing, work was in flight on `fix/764-gdpr-erasure-reviews` (main checkout) and in linked worktrees for the #767/#768 branches; run `git status` and `git worktree list` before trusting this row |
| Schema head | **V66** (re-measured 2026-09-29 from `core-java/src/main/resources/db/migration/`; V66 is COR-4's `orders.unit_count`) |
| Test manifest | **4137** logical invocations (Java 1972/303 files, Jest 1878/172, Playwright 128/28, Go 98/13, MCP 61/8) — `docs/metrics.json` as merged to `main` in PR #763 (36-17's 4130 plus 7 Java tests added on the branch after 36-17); re-measured 2026-09-30, `docs-freshness.sh` rc=0 |
| Gate sweep 2026-08-25 | **36 PASS, 1 FAIL, 0 VOID** across all 37 gate scripts, measured after the runtime re-sync AND the E2E run. The one non-pass is `check-e2e-skip-budget` **FAIL** (65 skipped vs a budget of 8, plus an undeclared skip) — it was VOID until a completed run replaced the stale report, so this is a real answer rather than an unanswerable one. Progression that day: 34/2/1 → 36/0/1 → 36/1/0 |

> **Why the HEAD row names a PR and not a sha — do not "helpfully" put one back.** A document that
> records its own repository's HEAD cannot be correct at rest: writing the sha IS a commit, so the
> act of correcting it falsifies it. Measured 2026-08-18 — the row was set to `44cacbaf`, and
> merging that update made `main` `b17bef59`, stale again in one step. `check-handoff-contract`
> reaches the same conclusion from the other side: H-3 allows a budget of **3** commits rather than
> demanding exactness. The OTHER shas in this file are historical facts and are fine to keep.

### What shipped 2026-08-24

- **#657 CLOSED** — the amqp-client CVE pin was setting `rabbitmq-amqp-client.version`, a key
  nothing reads. The Boot BOM declares `rabbit-amqp-client.version`. A redundant direct
  `implementation()` pin was forcing the right version anyway, which is exactly why the typo was
  invisible. Property corrected, direct pin removed.
- **#658 CLOSED** — `base-image-freshness.yml` **had never scanned anything, in its entire life.**
  It assembled `ghcr.io/${OWNER}/...` from `github.repository_owner` = `Bralabee`; GHCR repository
  names must be lowercase, so every leg tripped the VOID arm before reaching Trivy. Present in the
  workflow's first commit (`e705d38f`, #520, 2026-08-04). **21 consecutive scheduled runs, all
  failure, zero successes ever.** Fixed on both the scheduled and the dispatch path, plus a
  `$GITHUB_OUTPUT` injection, the missing VOID report arm, a `gh` stderr fold, and `X-6` in
  `check-image-supply-chain.sh` so a revert fails loudly.
- **#659 — fixed in PR #664** (`ec2f44f1`, merged 2026-08-25), having been filed-not-fixed the
  previous morning: `ci-cd.yaml` hardcoded the image owner as a lowercase literal in twelve places
  while deriving it from `github.repository_owner` in two. Same defect one layer down from #658;
  broke both deploy jobs on a fork, transfer or rename. **The two sides of `kustomize edit set
  image` are NOT the same string** — the LHS is a selector into the checked-in manifests, so
  deriving it from the owner too makes kustomize fall back *silently* to the immutable 2.1.0
  default. Proven by rendering the staging overlay under owner `Acme-Fork`: selector-from-file
  pins correctly (rc=0), selector-also-derived FATALs (rc=1). `X-7` in
  `check-image-supply-chain.sh` now holds all three halves — no owner literal, a lowercased
  derivation per pinning step, and `ci-cd.yaml`/`base-image-freshness.yml` still describing the
  same image. ⚠ **The reworked deploy steps have still never executed** — both deploy jobs are
  `vars.DEPLOY_*_ENABLED`-gated and skipped on every run. The overlay render is the evidence.

### What shipped 2026-08-25

- **#652 CLOSED** (`f975f83b`) — codeql-action `upload-sarif` 4.37.6 → 4.37.8, both sites.
- **#655 CLOSED** (`a6eecf70`) — docker/setup-buildx-action v3.12.0 → **v4.3.0, a MAJOR**, and the
  verification is the point. The action sits at exactly one site, inside `build-and-push`, which is
  gated `if: github.event_name == 'push' || 'release'` and **skips on every pull request** — so its
  14 green PR checks were not weak evidence, they were *no* evidence, because the job that loads it
  never ran. It was executed for real on a throwaway `phase-*` branch (whose push event does run
  `build-and-push`): all three legs green, log confirming the v4.3.0 SHA was downloaded. The
  post-merge tree was then verified **byte-identical** to that tested tree (`054cc1fc`), which is
  why it merged 1-behind-base without a re-run — a rebase would only have re-run the 14 jobs that
  cannot test buildx. Main has since built green twice more.
- **Generalisable:** before trusting a green PR on a dependency bump, check whether any job that
  ran actually **loads** the thing being bumped. Map the symbol to its enclosing job; if that job
  is `push`-gated, a `phase-*` branch is the only way to exercise it pre-merge.
- ⚠ **Left behind: nine GHCR tags** from that probe — versions `1169332429` (core-java),
  `1169264525` (edge-go), `1169267851` (frontend), three tags each including the probe's full-sha
  tag. Deleting them needs a `delete:packages` scope the working token does not carry
  (`gh auth refresh -h github.com -s delete:packages,read:packages`). Nothing references them.
- **#661 CLOSED, and #647 CLOSED with it** — the nightly full-suite E2E had been dark for **14
  consecutive nights** (2026-08-11 to 2026-08-24) without executing one Playwright test. core-java
  crash-looped every ~27s on `permission denied for table postcode_centroid` / `TRUNCATE`, resetting
  its health clock each time, so compose aborted every service depending on it. Since the SEC-04
  runtime-migrator split the app connects as the DML-only `jtoye_runtime`, and TRUNCATE is a
  DISTINCT privilege not implied by DELETE; the grant lived only in `create-runtime-role.sql`, a
  MANUAL script, and the nightly's `down -v` meant nobody ever ran it. **V64** moves the grant into
  the schema. The nightly now escalates a scheduled failure into an issue instead of dumping logs
  into the run that already failed.
- **Two blind spots kept it alive, and both are worth remembering.** It cannot reproduce locally —
  this machine's role was granted TRUNCATE out of band and `postcode_centroid` already holds
  1,748,230 rows, so the importer short-circuits before the TRUNCATE. And
  `RuntimeRoleGrantContractTest` asserts *exactly this grant* and was green throughout, because its
  `@BeforeEach` runs `create-runtime-role.sql` itself: **it certifies the script, not the
  deployment.** `PostcodeTruncateGrantMigrationTest` is the falsifiable sibling.

### What shipped 2026-08-30 (afternoon — continues the block below)

- **#684 CLOSED via PR #694** — a fresh volume provisions its own migrator credential
  (`00-create-db.sql` creates `jtoye_app` with `DB_MIGRATION_PASSWORD`, fallback to
  `DB_PASSWORD`); proven on a real `down -v` cycle with digest-confirmed differing
  credentials: healthy, RestartCount 0, V64 64/64, 45/45 E2E smoke. Instrument lesson:
  in-container `psql -h 127.0.0.1` is loopback-`trust` and accepts ANY password — auth
  probes must run over the docker network (now in project memory).
- **#688 CLOSED via PR #696** — dashed count subtitles under `loadFailed` on all four
  dashboard list pages + six raw-axios toasts routed through `describeLoadError`; jest
  now 141/1505, metrics 3494, browser-proven both directions on a rebuilt container.
- **#690 CLOSED** — owner ratified the approvals queue on the Index tier, viewed with a
  real MANUAL_REVIEW application (a side effect of #684's fresh-volume smoke).
- **Phase 35 recorded complete in STATE.md via PR #695** (counters re-measured from
  disk: 119/119 plans, 11/14 phases; percent scoped "of written plans").
- **The unexamined-defaults audit ran** (quick-260830-p2o): four findings filed —
  #699 (md:768 gives tablets the desktop sidebar), #700 (TOAST_LIMIT=1 displaces unread
  error toasts), #701 (12 dialogs on the 512px default, no density policy), #702
  (unclamped table titles) — and eight surfaces examined clean with an instrument-armed
  method. Report: `.planning/quick/260830-p2o-unexamined-defaults-audit/AUDIT.md`.
- **#697 (another session) added the review-record required-status backstop** — every
  PR now needs a review artifact (a PR review, an inline review comment, or a
  `Review-Record:` comment); the audit PR was the first judged by it.

### What shipped 2026-08-30

- **#687 CLOSED — the marketing-motion flake, via PR #692** (squash `b7b2099e`). All 7
  network-idle waits in marketing-motion + csp-no-violations replaced with deterministic
  anchors on a new inert both-branches `data-motion-decided` stamp; three break arms
  observed failing (the planned ARM C createElement vector was VACUOUS under
  `'strict-dynamic'` and was corrected to an inline event handler); clean pass 18/18
  against a rebuilt compose frontend. One CI red en route: `check-changelog-cites-pr`
  wants the entry heading to cite the PR itself, not only the issue — the gate's own
  error text says this has redded main six times.
- **#686 — the undeclared-skip cause is scripted, via PR #693** (branch
  `feature/686-skip-budget-fixture-reset`). `seed-e2e-fixtures.sh` now resets a
  LIVE/terminal demo-tenant `vendor_onboarding` (row + gates, vendor tenant only,
  `Shop.published` untouched; `RESET_ONBOARDING=0` preserves state but verification still
  fails on it). Arms: spec `1 skipped` on WITHDRAWN, opt-out rc=1, post-reset full journey
  `1 passed (6.1s)`. `check-e2e-skip-budget` re-earned VOID → PASS on a fresh full-suite
  run: **323 total / 317 passed / 6 skipped / 0 failed**, budget 6, all declared. The
  dark-lane half is #683's: the nightly's escalation covers the gate step, and tonight's
  ~02:25 UTC run is the confirming instrument for the #687 fix.
- Runtime parity after the #692 merge: `sync-runtime.sh` rc=0, 4/4 FRESH re-asserted by
  the gate's own re-check (core-java + frontend rebuilt and force-recreated, healthy).

### What shipped 2026-08-28

- **#666 CLOSED — the nightly lane is fixed, in two halves.** V64 (#661) had fixed the stack half;
  the remaining `253 passed / 6 failed` were three instrument defects in
  `frontend/e2e/storefront-flows.spec.ts`, fixed in **PR #670** (squash `7ac23442`). Details in the
  E2E section above, now corrected in place. Falsified clean → arms → clean with restores verified
  by `git hash-object`; the closing arm created **4 real orders** carrying V63 `allergen_mask`
  snapshots — the first E2E-driven successful order placements since Phase 31 merged, because
  `placeOrder()` has exactly one caller and the un-ticked LGL-03 gate had silently swallowed every
  submit since 2026-08-17. Full suite on the live stack: **266 total / 258 passed / 8 skipped /
  0 failed**; `check-e2e-skip-budget` PASS. **The confirming instrument is the next scheduled
  nightly (~02:25 UTC)** — a red there re-files automatically via the escalation step.
- **#671 OPEN — the compose port-range trap, filed with its full mechanism.** core-java publishes
  `"9090-9091:9090"` while the frontend bundle bakes `localhost:9090`, AND Prometheus binds
  `127.0.0.1:9091` from the separate monitoring compose project — so monitoring-down-after-reboot
  frees 9091 for core-java, killing browser-side API calls and blocking Prometheus in one move.
  Measured: six local-only test failures cleared by a `--force-recreate` with no code change.
- The handoff PR **#668** and the fix PR both merged; local + remote branch cleanup done
  (`chore/agents-md-resync`, the retired-SDK + Stripe dependency-bump branch, `docs/system-tour-2026-08-19`
  deleted against verified MERGED PR state; `phase-29-research` kept deliberately).

## Environment state — measured 2026-08-24, not remembered

All compose services running. Runtime is **Docker Compose** (`docker-compose.full-stack.yml`), the
canonical local dev/E2E runtime; do not start a local minikube alongside it (they share the dev DB).

| Service | Host port | Probe |
|---|---|---|
| frontend | `3000` | `/legal` → 200 |
| core-java | `9090` | `/health` → 200 (**note: 9090, not 8081** — known port shift) |
| edge-go | **`8089`** → container 8080 | `/health` → 200 |
| mcp-server | `9100` | — |
| postgres | — | schema head V63 |

**`edge-go` is published on host `8089`, not `8080`.** A probe against `localhost:8080` returns
`000`, which reads like a dead service and is not one.

**`check-runtime-freshness` is PASS, 4 of 4, 0 unverified — re-synced 2026-08-25 22:15 UTC.**
It had been FAIL, 2 of 4, from merge drift (core-java behind #665's `build.gradle.kts`, frontend
behind #653's `package.json`). Cleared with `bash scripts/sync-runtime.sh`, rc=0.

Verified **by identity, not by the script's verdict** — the container's image ID must move, not
just the tag's, because a fully CACHED rebuild advances the tag while compose leaves the container
on the old image (that is the `[image-not-rebuilt]` arm, and the reason #662 added
`--force-recreate`):

| service | before | after | container == tag |
|---|---|---|---|
| core-java | `fc187d1903fd` (08-24 20:49) | `eca36448263f` (08-25 22:15) | MATCH |
| frontend  | `64d20e2f668b` (08-17 19:57) | `86ce3c5c4be9` (08-25 22:14) | MATCH |

⚠ **A rebuild is not finished when the tag moves.** Mid-recreate, `docker compose ps -q frontend`
returned EMPTY while the new tag already existed — a wait-loop watching only image IDs fires there
and would hand the next step a stack with no frontend. Wait on the CONTAINER being up, not the tag.

**`check-alert-metrics` fired its documented standing remedy, both directions recorded**: rc=1
(`M-1 rule 'NoOrdersCreated' selector matches ZERO series`) → `bash scripts/seed-order-metric.sh`
→ rc=0 (19 live rules / 25 selectors, 3 dormant). A core-java restart zeroes the counter; this is
restart behaviour, not a defect, and it will recur after every rebuild.

`bash scripts/seed-e2e-fixtures.sh` rc=0 — vendor-refund-flow's DRAFT test and storefront-flows'
STFR-06 can assert non-vacuously again. **vendor-refund-flow's REFUND test stays skipped by
design**: it needs real Stripe test-mode keys (`STRIPE_API_KEY`), not a fixture, so a skip there
is expected and is not a coverage gap.

### E2E run 2026-08-25 23:16–23:23 UTC, against the freshly-synced stack

`cd frontend && PLAYWRIGHT_JSON_OUTPUT_NAME=e2e-artifacts/report.json npx playwright test --reporter=json,list`

**195 passed · 6 failed · 65 skipped (6.6m), rc=1.** All 6 failures are **3 tests × 2 projects**
(mobile + desktop), every one in `e2e/storefront-flows.spec.ts`. Two different root causes, and
they must not be lumped together:

**(a) 4 of 6 — INSTRUMENT defects, loose locators colliding with new legal copy.** Playwright named
the colliding elements outright, so this is not inference:

| test | locator | also matched |
|---|---|---|
| `:125` shop card renders | `getByRole('link', {name:'Browse'})` | `<a href="/legal/cookies">Cookie and browser-storage policy</a>` — `browse` ⊂ `brows`**er** |
| `:541` add items → checkout | `locator('text=Your basket')` | the cookie-policy paragraph (`We only use cookies and browser storage that are …`) |

Both are `strict mode violation … resolved to 2 elements`. The product is fine: `/shop` renders the
shop name, the cuisine tag and the nav; the sibling test `search filters shops` uses the same route
and PASSED. The fix is tighter locators (scope to the nav, or `exact: true`), not product work.
⚠ The nav link is now **"Shops"**, not "Browse" — the `:125` comment ("scope to the nav link")
describes a page that no longer exists.
**Both (a) locators fixed in PR #670 (2026-08-28)**: the card asserted by its own href (nav-scoping fails on mobile, which has only a hamburger), the basket matched by heading role.

**(b) 2 of 6 — `:770` order confirmation email: ATTRIBUTED 2026-08-28, fixed in PR #670.** The LGL-03 allergen acknowledgement (Phase 31) refuses the submit BEFORE any network call, with the button left deliberately enabled — the spec never ticked it, so the click was silently swallowed and no order row was created. Not a broken checkout; the gate doing its job. The pre-attribution record below is kept because its method notes still hold:
`getByRole('heading', {name: 'Order confirmed!'})` → `element(s) not found` after 15s, and
**no order was created**: the only rows in the window are `ORD-E2E-DRAFT-FIXTURE` (22:16:21, from
`seed-e2e-fixtures.sh`) and `metric-seed@jtoye.local` (22:16:09, from `seed-order-metric.sh`) —
both seeds, both predating the 23:16 run. No `email-<timestamp>@test.com` order exists. So the
checkout did not complete server-side; this is not merely a UI assertion problem.

- The heading itself is still in the source: `frontend/app/shop/[slug]/checkout/page.tsx:514`.
- `placeOrder()` is called by **exactly one test**, so there is no passing sibling to cross-check
  the checkout path against — worth fixing, since it makes every checkout failure a single point.
- ⚠ **Ruled out, so nobody repeats it:** the "shop no longer offers delivery" hypothesis. `shops`
  has **no** `delivery_available`/`collection_available` column (only `delivery_info`,
  `minimum_order_pennies`, `delivery_fee_pennies`, `free_delivery_threshold_pennies`), so
  fulfilment is not gated that way.
- ⚠ **Method note:** a bare `count(*)` of recent orders returned **2** and reads as "checkout
  works". It is not — both rows are seed artifacts. Read the ROWS, not the count.

**`check-e2e-skip-budget` moved VOID → FAIL**, which is progress (a completed run replaced the
stale report) but not a pass: `S-1 65 skipped exceeds the declared budget of 8`, plus `S-2` an
undeclared skip in `dashboard-interface-corrections.spec.ts`. **RESOLVED 2026-08-28: the 65 was
not budget drift** — a correctly-ported stack with freshly-seeded fixtures reports **8 skips, all
declared, gate PASS**, and the nightly independently reports 7. The 65 was the both-projects
artefact suspected here, compounded by the port-range condition now filed as #671. One genuinely
new mechanism surfaced: **the suite displaces its own DRAFT fixture on a non-fresh volume** (every
run places orders; 22 accumulated newer than `ORD-E2E-DRAFT-FIXTURE`, pushing it off the top-20
page its test reads, which skips the test undeclared) — re-run `scripts/seed-e2e-fixtures.sh`
before trusting any local skip count.

**vendor-refund-flow's REFUND test stays skipped by design** — it needs real Stripe test-mode keys
(`STRIPE_API_KEY`), not a fixture.

PRIOR, 2026-08-24, the previous clean-runtime record: PASS 4 of 4, 0 unverified. core-java rebuilt
twice, container recreated onto image `2218aa93…`; `amqp-client-5.33.1.jar` read out of the running
`/app/app.jar`, with `5.25.0`, `5.22.0`, `5.3.6` and `5.4.2` at **0 occurrences** under escaping
that finds `5.33.1`/`5.4.3`/`5.5.2` — so the zeros were about the jar, not the pattern. Broker
connected, both outboxes zero `PENDING`/`FAILED`.

## What to do next

**The roadmap says Phase 29. Phase 29 still cannot start.** It reached 9/16 and is PAUSED at its
wave-7 boundary on two owner actions, **re-measured 2026-08-24 and both still unmet**:

| Blocker | Measured 2026-08-24 |
|---|---|
| staging DNS | `dig +short` returns **no answer** for `staging.olajay.co.uk` and `api.staging.olajay.co.uk` |
| operator secrets | **0 populated / 7 declared** in `~/.jtoye/staging-operator.env` |

Neither is an engineering task. Phase 29's authoritative body — including the correct counters
(`total_plans: 93`, `completed_plans: 78`) — lives on branch **`phase-29-research`**, not on `main`.
The `progress:` counters in `main`'s `.planning/STATE.md` remain knowingly corrupt
(`completed_plans` exceeds `total_plans`) and must be repaired **there**.

If the owner has not cleared those, the highest-value available work is:

1. **The dependabot batch opened 2026-08-21 — triaged 2026-08-24, and this batch DID contain
   stale-base artifacts.** The 2026-08-18 note that "every failure was REAL — none a flake, none a
   stale-base artifact" held for *that* batch and does **not** generalise. Five of these six were
   blocked, wholly or partly, by `golang.org/x/mod` CVE-2026-56864/56865 sitting in their **base**,
   closed on main by #656. Proof: **#652** changes nothing but a workflow file, yet its Security
   Scan failed on a Go module CVE. Re-read each PR, but read the BASE as well as the diff.

   - **#653 CLOSED** — jest-axe 10→11. Only failure was the base CVE; `@dependabot rebase` cleared
     it (14 pass) and it merged as `ba33e554`. Major bump, but exercised by 22 `toHaveNoViolations`
     assertions across 10 files plus the self-testing `axe-instrument.test.tsx`.
   - **#650 CLOSED** — superseded by **#665 CLOSED**, which merged as `9a0370e3` and auto-closed
     it. Two of #650's three failures were stale-base (Security Scan; and a doc-citation pointing
     at `build.gradle.kts:103`, which #657 moved to
     `:141`). The real one is prose version pins, which dependabot structurally cannot edit — the
     same reason #604 was superseded by #638. **Note the SEVENTH site:**
     `.planning/codebase/INTEGRATIONS.md:9` carries the literal `com.stripe:stripe-java:<version>`
     token, matched by `check-doc-citations.sh`, not by the four-doc version gate. Fix the six and
     that one still reds.
   - **#652 CLOSED**, **#655 CLOSED** — both merged 2026-08-25 after #664 landed, in that order
     (`f975f83b`, `a6eecf70`). See "What shipped 2026-08-25" above for how #655 was verified;
     the short version is that its 14 green PR checks were incapable of testing it.
   - **#654 MERGED** (2026-08-30, `966d987b`) — framer-motion 12→13, a MAJOR; the owner call was
     made and it landed via the merge queue. Historical note of its pre-merge state: the remaining
     failure is the same prose-pin class (4 sites). Given #605/#606 were closed for being majors,
     this is an owner call, not a mechanical one.
   - **#651 CLOSED** — closed unmerged by dependabot itself on 2026-08-28T15:25:33Z, with the
     comment "Looks like these dependencies are updatable in another way, so this is no longer
     needed". Not an operator decision. Of its five failures, two were stale-base and were already
     clear; the rest were real work in two classes — three latent type errors that `next build`
     structurally cannot see (it type-checks the pages/app graph, not the whole tsconfig program),
     and the prose version pins dependabot structurally cannot edit, the same class that took #604
     to #638 and #650 to #665. Superseded by branch `feature/deps-frontend-651-supersede`, which
     lands the same 10-package bump with both classes fixed.
2. **#654 was the last dependabot PR needing real judgement** — MERGED 2026-08-30 (`966d987b`); see
   item 1. #651 is no longer in that set; its disposition changed on 2026-08-28 and is recorded
   above. Everything else in that batch is closed.
3. Then Phase 30 (The Money Path), Phase 32 or Phase 34.

### The H-2 self-falsification trap, learned twice on 2026-08-24/25

Worth keeping because it cost two red gates in one day, in both directions.

A HANDOFF entry that states its own PR's status **falsifies itself the moment that PR merges**, and
reds H-2 on `main` for whoever opens the next PR. #665 shipped an entry claiming its own PR was
still open; it was false within the hour. The #659/#664 entries here were deliberately written with
**no capitalised state word** for exactly that reason, and survived their own merge untouched.

Three mechanics that are not obvious until they bite:

- **H-2's vocabulary is `(CLOSED|OPEN)` only.** `MERGED` parses as *no claim at all* and slips
  through unchecked — it is not the safe way to record a merged PR, it is the way to record nothing.
- **Quoting the bad claim re-creates it.** H-2 extracts on `#NNN … WORD` proximity with no notion of
  quotation, so a sentence *illustrating* a false claim is scored as that claim. Writing this
  section required wording it to avoid naming the pattern.
- **Fix every stale claim in one pass, not one at a time.** Correcting #665 alone just moved the red
  to #650, which had auto-closed as superseded. Cross-check the whole set against the forge.

### Dependabot: what the 2026-08-18 triage concluded, and why it still applies

**#606 CLOSED** (node 24→25-alpine): endoflife.date says node 25 is `lts: false`, EOL **2026-06-01**,
already passed; node 24 is LTS to 2028-04-30. The support-horizon gate is correct.
**#605 CLOSED** (springdoc 2.8.6→3.1.0, MAJOR): the OpenAPI spec cannot be generated, so the app
likely does not boot. Needs real work. **#631 CLOSED** (frontend npm, 10 updates): real break across
the frontend build. **#604 CLOSED** (the retired object-store SDK) was superseded by **#638 CLOSED**, merged as `9387b3bf`;
its failure was `scripts/check-doc-versions.sh`, because dependabot cannot know to edit `CLAUDE.md`,
`AGENTS.md` and `.planning/codebase/STACK.md`, which each pin the version in prose. **Any future SDK
bump carries the same four-site requirement.**

**One security item was deferred and this branch discharges its precondition:** `next` 16.3.0
updates vendored lodash to 4.17.23 for **CVE-2025-13465** (prototype pollution in
`_.unset`/`_.omit`). The tree now declares `next` `^16.3.2` (lockfile resolves 16.3.3), so the
16.3.0 migration this was waiting on has happened. **The 4.17.23 figure is upstream's, carried
from the original advisory and NOT verified by content on this tree** — Next strips the version
banner from its minified vendor bundles, so `4.17.21` being absent from `node_modules/next` is
evidence about a missing string, not about the version that shipped.
**Assessed 2026-08-19: MEDIUM, LOW reachability, not urgent.** Scorers disagree only on
availability impact (NVD 5.3, GitHub 6.5, vendor 6.9, Red Hat 8.2) — quote the range, not one end.
Our code never imports lodash; the vulnerable internals live only in two vendored Next bundles whose
callers pass fixed internal keys. Fix it when the `next` 16.3.0 migration happens.
### Owner-facing, unresolved — carried from Phase 31

1. **`privacy@olajay.co.uk` must exist and be MONITORED** before the `/legal` pages naming it are
   publicly reachable. Unverifiable from this repository. A published DSAR route nobody reads is
   the same fail-open shape as no route at all, only worse — it looks discharged while a one-month
   statutory clock runs. **This is the load-bearing one.**
2. **Registered office is not published** — `NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE` ships empty by
   owner decision, published as a dated exception. Recoverable by one build arg **plus a frontend
   image rebuild**, since `NEXT_PUBLIC_*` is inlined at build time.
3. **`PublicFooter:189` renders the platform's company identity on every tenant storefront**, while
   `frontend/lib/company.ts:9-12` states it must render *"never on tenant storefronts"*. Pre-existing since
   PR #232. Both readings are defensible, so it is a legal-content call, not an engineering one.
   A test pins the count at exactly one so neither answer is silently pre-empted. The number
   rendered is the ACTIVE `16471464`, not the dissolved namesake `13434105`.
4. **`contrast-literals.test.ts`'s `SCAN_ROOTS` excludes `components/legal` and `app/legal`** — the
   ledger is structurally blind to the five `/legal` routes that ARE declared in-scope surfaces.
   That blind spot is exactly why a 4.41:1 mobile contrast failure survived to the final plan.
   Widening it will likely surface further literals.
5. **31-07's Article 26 effectiveness-gate box stays UNTICKED.**

Phase 31's own deferred register is `.planning/phases/31-consumer-safety-and-legal-floor/deferred-items.md`
(DEF-31-11-01 plus five items from 31-17/31-18).

## Four more instrument failures, 2026-08-24 — one in the prescribed remedy, one already archived

These are additions to the 2026-08-18 list below, not replacements. All three produced a
*confident wrong answer*, not an error.

1. **`docker compose up -d --build <svc>` does NOT recreate the container when every layer is
   CACHED — and that command is what `check-runtime-freshness.sh`'s own failure message tells you
   to run.** Measured: after a rebuild whose inputs were byte-identical, buildx still exported a new
   manifest digest (image `89a31b0b` → `5a41e87d` → `2218aa93`), while compose printed
   `Container jtoye_oaas_2026-core-java-1 Running` and left the container on the OLD image for
   seven minutes. The health check said `healthy` throughout — of the *stale* container. The gate
   then correctly stayed red, pointing at a remedy that could not clear it.
   **`docker compose up -d --force-recreate --no-deps <svc>` is what actually works.** The repo
   already warns that `start` and `restart` do not rebuild; this extends it to the prescribed fix.
   Note also that a squash-merge re-dates the commit, so `check-runtime-freshness` goes red on
   merge even when the build inputs are byte-identical — verify with
   `git diff <built-from> <merged> -- <build paths>` before assuming real drift.

   **`scripts/sync-runtime.sh` carried the same defect, and proved it against itself.** Measured on
   the merge of #661: the script ran `up -d --build core-java`, the image moved
   `3f5b80ed -> fc187d19`, the container stayed on the old one, and the script's own re-check
   printed `FAIL: drift REMAINS after rebuilding: core-java` and exited 1 — telling the operator to
   run the command it had just run. It now passes `--force-recreate`. This is the strongest form of
   the lesson: not a claim that a command is insufficient, but a repair script reporting its own
   failure to repair.


   **CORRECTION, and the more useful finding: this was already diagnosed on 2026-08-07 and
   archived.** `docs/archive/HANDOFF-history-through-2026-08-17.md` line 673 reads, verbatim:
   *"`sync-runtime.sh` rebuilds but does not always recreate. It left both `frontend` and
   `core-java` on their previous image IDs; the gate correctly said `[container-not-recreated]` and
   both needed `docker compose up -d --force-recreate --no-deps <svc>`."* The squash-merge re-date
   finding sits in the same bullet block (*"Any PR touching a build input costs a rebuild AFTER
   merge"*). Both were correct, both were actionable, and the script stayed broken for 17 days —
   because archiving the handoff moved the diagnosis somewhere no gate reads and nobody re-reads.
   **The archive's own header warns that its CLAIMS cannot be trusted; its DIAGNOSES were fine.**
   Before concluding you have found something in this repo, grep the archive: #658's reviewer found
   the same shape there (`.planning/quick` had recorded "metadata-action lowercases owner" a month
   before that workflow was written).
2. **`awk … > new && mv new old` silently drops a file's executable bit**, and a defensive `chmod`
   in CI hid it. Two scripts went `100755 -> 100644` this way this session:
   `check-image-supply-chain.sh` (in #658) and `sync-runtime.sh` (in #662). Nothing failed, because
   `ci-cd.yaml` already runs `chmod +x ./scripts/check-image-supply-chain.sh` immediately before
   executing it directly, and every other call site uses `bash scripts/…`. So the regression was
   invisible in both directions: CI compensated, and the humans use `bash`. It surfaced only in a
   merge diff line — `mode change 100755 => 100644`. Prefer editing in place (`sed -i`), or restore
   the bit with `git update-index --chmod=+x` and CHECK THE MERGE DIFF for mode changes.

3. **The truncating filter, walked into while fixing a blind detector.**
   `gh run list --workflow X --limit 8` was used to establish *how long* a workflow had been
   failing, and answered "eight days". `--limit 100` returns **21** rows, all failure, back to the
   day the workflow landed. A bounded stream cannot answer a question about the EXTENT of
   something — that is the one thing it structurally cannot do. The wrong figure reached a commit
   message, a changelog entry and a PR body before code review caught it. This trap was already
   recorded in this repo, which is the point.

4. **`gh pr view --json statusCheckRollup` lags the job it reports.** The Testcontainers job
   completed `success` at 16:01:26Z while the PR rollup still showed it `PENDING` for minutes
   afterwards, across repeated polls. A poll loop that only reads the rollup will sit past a
   finished run. Read the JOB (`gh run view <id> --json jobs`) when the answer matters; the rollup
   is a summary, not the source.

## The instrument lied five times — read this before trusting any check

Every one of these produced a *confident wrong answer*, not an error. This is the most transferable
thing this session produced.

1. **Four background watchers reported false completions**, all from transient
   `error connecting to api.github.com`. One exited **0** with jobs still `pending`; another printed
   `SETTLED` over an **empty result table**. Every one would have read as "CI passed" from the exit
   code alone. **An empty result table is VOID, never an answer** — and `gh pr checks` returns rc=1
   both for "a check failed" and for a network error, so rc alone cannot distinguish them. Poll on
   *content*: require rows > 0 AND pending == 0.
2. **`SELECT max(version) FROM flyway_schema_history` returns `9`, not `63`.** The column is TEXT,
   so it sorts lexically. Use
   `ORDER BY (regexp_replace(version,'\D','','g'))::int DESC LIMIT 1`.
3. **A served-page assertion over conditionally-rendered elements is vacuous.** Checking the
   contrast fix on `/shop`, both the fixed and the old class pattern returned **0** — the elements
   simply do not render with current seed data. "0 occurrences of the bad pattern" is byte-identical
   to "fixed" and to "never rendered". Reading the built chunk out of the container settled it.
   This is the same shape as 31-18's own finding that a scan over nothing looks like a flawless page.
4. **`mergeStateStatus=UNKNOWN` means ALREADY MERGED, not "still computing".** On PR #641 it was
   read as "GitHub has not finished calculating", polled three times, and only caught when an
   independent check errored with `fatal: Not a valid object name origin/<branch>` — the branch was
   gone because another session had merged it. Worse, the conflict count printed beside that error
   read `0`, which was VACUOUS: `git merge-tree` had FAILED, so the grep counted nothing. **An empty
   result and a failed command look identical if you only read the number.** Before merging, check
   the PR is still `OPEN`.

5. **`git log --first-parent` is the only reason the changelog gate survived the merge.** The
   branch carried **44 `feat`/`fix` commits, none citing a PR**. Had `check-changelog-contract.sh`
   scanned all commits, a merge commit would have redded `main` with 44 uncited subjects. It scans
   first-parent, so `main` sees one commit. **Squash with an explicit subject ending `(#NNN)` is the
   only safe merge method here** — rebase strips the citation and voids the gate.

### Where the durable learnings live — READ THEM BEFORE RE-DERIVING THEM

Cross-session learnings are NOT in this file. They are in the per-project memory at
`~/.claude/projects/-home-sanmi-IdeaProjects-JToye-OaaS-2026/memory/`, indexed by `MEMORY.md`
(124 entries). A selective snapshot is versioned in the `Bralabee/dotfiles` repo under
`claude/projects/.../memory/` — 9 of the 124 as of 2026-08-19, via dotfiles PR #106. The memory
directory itself is NOT a git repo; the files are plain files on disk.

This session added `project_phase_31`, `trap_doc_recording_own_head_sha` and
`trap_gh_checks_polling_semantics`, and extended `trap_grep_pattern_shape_false_negative` (a new
SCOPE axis), `trap_rebase_merge_voids_changelog_gate` and `env_gotchas_local_stack`.

**The lesson that cost the most was one already recorded.** `trap_handoff_residue_count_stale`
already said "#429's preamble refuses to quote HEAD SHAs for exactly this reason" — and the HEAD-row
sha problem was still re-derived across three PRs before anyone noticed. Search the memory index
before concluding something is new.

### Writing in this file is itself gated

`scripts/check-handoff-contract.sh` asserts this document against reality:

- **H-1** — a bold-marked `N of N rc=0` claim requires **BOTH** numbers to equal the live
  gate-script count (currently **37**). A truthful "36 of 37" in that bold form FAILS the gate, which
  is why the summary table above states `36 PASS, 0 FAIL, 1 VOID` instead. Any `EXPECT N x rc=0`
  line must also read 37. **Note the trap: this bullet cannot quote the token it describes** —
  writing the bold form here makes the gate fire on its own definition. It did, on the first draft.
- **H-2** — a claim opts in by CAPITALISING its state word: `#116 CLOSED` is checked against the
  forge, `#116 is closed` is narrative and is not. Do not capitalise a state you have not verified.
- **H-3** — this document must stay within **3** merged commits of the base branch.

**THIS FILE WENT STALE FIVE TIMES ON 2026-08-18 ALONE**, four of them within an hour of being
written, and one caused by a DIFFERENT session merging #634. The cause is structural, not
carelessness: H-2 claims mirror forge state, and any PR merge by ANYONE falsifies them. H-2 caught
every one within minutes and named the PR — that is the design working. What it can NEVER see is
shas, prose and runtime facts, which drifted silently every single time. So: after editing forge
state, re-run the gate; and re-read the prose by eye, because nothing else will.
