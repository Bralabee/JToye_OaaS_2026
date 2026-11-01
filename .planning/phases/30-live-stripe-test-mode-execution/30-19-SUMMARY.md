---
phase: 30-live-stripe-test-mode-execution
plan: 19
subsystem: payment-links-hygiene-and-staging-proof
tags: [stripe, payment-link, pay-04, d-03, robots, referrer-policy, ssr-gate, staging-seed, playwright, connect, direct-charge]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-16: links issued as /pay#token=; public phone send/verify; expose-otp-for-test (owner decision expose-on-staging)"
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-17: stripeAccountId on GuestOrderConfirmation and RedeemedPaymentLinkDto"
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-18: frontend/app/pay/page.tsx + pay-client.tsx (preview, redeem, history.state resume)"
provides:
  - "robots.ts DISALLOW \"/pay\" (prefix: /pay and /pay/...)"
  - "next.config.mjs '/pay/:path*' Referrer-Policy no-referrer, after '/:path*' and /invite; header-snapshot.test.ts asserts order, match and the effective value"
  - "ssr-routes.conf: STATIC pay/page.tsx; check-ssr-coverage-contract.sh 44/44 PASS"
  - "scripts/staging-seed.sh --stage pay-link --link-out FILE: send (probe) -> vendor order -> verify -> issue; PAY-LINK order=... link=... expires=...; URL only in a mode-600 file; REFUSED [otp-hidden] before any write"
  - "frontend/e2e/staging-pay-link.spec.ts: desktop pay arm (reload-resume, 4242, Stripe read on the owning account, spent link) and @mobile-only 360 px overflow arm; verdict STAGING-PAY ... account=<acct_|none>"
  - "frontend/e2e/staging-card-checkout.spec.ts: Stripe-Account from the confirmation's stripeAccountId; verdict STAGING-CARD ... account=<acct_|none>"
affects: [30-20, 30-32]

estimate:
  tokens: 75000
  tasks: 3
actuals:
  tokens: 20068     # chars/4 over git diff beb6cf37..8a9fbccd (code, tests, evidence, counts); excludes this SUMMARY
  tasks: 3
  commits: 9        # MEASURED: git rev-list --count beb6cf37..HEAD before this SUMMARY's own commit
plan_head_before: beb6cf376152bf923d789585f73e502537d6477e
plan_head_after: 8a9fbccd26cc669eb0bf57db388b57c07e1a9d6b

tech-stack:
  added: []
  patterns:
    - "A probe that decides whether a run may proceed goes before the first write: the OTP send runs before the vendor order, so a keep-hidden staging refuses with nothing created"
    - "A bearer URL leaves a tool only through a file chmod 600 BEFORE its contents are written (a pre-existing 644 file would otherwise keep its mode); its value is never echoed and the response file is removed at once"
    - "A spec that handles a bearer URL routes every printed URL, console line and error message through one redact(), including page.goto errors, which quote the URL"
    - "A presence grep must target the operative line (headers[\"Stripe-Account\"] = account), not the token: docblocks and error strings that name the token keep a token count green when the operative line is deleted"

key-files:
  created:
    - frontend/e2e/staging-pay-link.spec.ts
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-19-ssr-before.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-19-red-task1.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-19-task1-verify.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-19-task1-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-19-red-task2.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-19-task2-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-19-task3-verify.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-19-task3-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-19-counts.txt
  modified:
    - frontend/app/robots.ts
    - frontend/next.config.mjs
    - frontend/__tests__/header-snapshot.test.ts
    - frontend/__tests__/__snapshots__/header-snapshot.test.ts.snap
    - frontend/__tests__/csp-headers.test.ts
    - scripts/gates/ssr-routes.conf
    - scripts/staging-seed.sh
    - frontend/e2e/staging-card-checkout.spec.ts
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md

key-decisions:
  - "One SSR declaration, STATIC pay/page.tsx: the fragment decision means one route serves the link, Stripe's return and the reload; there is no pay/[token] page and no separate return page"
  - "The pay-link stage sends the OTP challenge BEFORE creating the order, so the keep-hidden refusal writes nothing (the plan's must-have truth), where the plan's action listed the order first"
  - "The OTP is read from testPreviewOtp, the field PhoneVerificationService.ChallengeResult actually carries (the plan said .otp)"
  - "The issued URL must have the /pay#token= fragment shape; a path-token URL (pre-30-16 core-java) is refused without being printed"
  - "The mobile arm does not pay: its order is left with an unpaid TEST intent for cancel-drafts (D-02)"

requirements-completed: [PAY-04]

coverage:
  - id: D1
    description: "/pay is disallowed in robots.txt, sends Referrer-Policy no-referrer (after the default), and is absent from the sitemap"
    requirement: PAY-04
    verification:
      - kind: test
        ref: "header-snapshot.test.ts /pay arm + csp-headers.test.ts route list; evidence/30-19-task1-verify.txt 24/24 rc 0; sitemap-robots-consistency 3/3"
        status: pass
      - kind: other
        ref: "evidence/30-19-task1-arms.txt: /pay entry moved before the default -> 5 red; clean 24/24 before and after, sha256 restore verified"
        status: pass
    human_judgment: false
  - id: D2
    description: "The SSR gate declares the /pay route with a true class and reason"
    requirement: PAY-04
    verification:
      - kind: other
        ref: "evidence/30-19-ssr-before.txt rc 1 (R-1 undeclared pay/page.tsx); 30-19-task1-verify.txt rc 0, 44/44"
        status: pass
    human_judgment: false
  - id: D3
    description: "staging-seed --stage pay-link makes a payable link, keeps the URL out of output, refuses keep-hidden before any write"
    requirement: PAY-04
    verification:
      - kind: test
        ref: "selftest 51 PASS 0 FAIL: pay-link-ok, -otp-hidden, -no-out, -unverified + controls; evidence/30-19-red-task2.txt (4 not ok at RED)"
        status: pass
      - kind: other
        ref: "evidence/30-19-task2-arms.txt: printed response -> pay-link-ok red (3 hits); no chmod -> mode 644 red; no otp refusal -> otp-hidden red"
        status: pass
    human_judgment: false
  - id: D4
    description: "Both staging payment specs read the intent on the owning account and print account=; the pay spec reloads and asserts the resume"
    requirement: PAY-04
    verification:
      - kind: test
        ref: "evidence/30-19-task3-verify.txt: --list desktop=1 mobile=1 rc 0 (STAGING_E2E=1), 0 tests rc 1 without; tsc rc 0; check-e2e-typecheck 56 clean"
        status: pass
      - kind: other
        ref: "evidence/30-19-task3-arms.txt: Stripe-Account line deleted -> plan grep form still green (vacuous), strict form reds naming the card spec"
        status: pass
    human_judgment: false
  - id: D5
    description: "Live: a pay-link run on staging, the desktop arm paid and read succeeded/livemode false, the reload-resume seen on real Next.js, the 360 px arm without sideways scroll"
    requirement: PAY-04
    verification:
      - kind: other
        ref: "plan 30-20, after the push, merge and deploy (and the expose-otp-for-test overlay change)"
        status: pending
    human_judgment: false

duration: 17min
completed: 2026-10-31
---

# Phase 30 Plan 19: /pay hygiene and the staging payment-link proof Summary

**The `/pay` page is now disallowed in robots.txt and sends no Referer. It is declared STATIC to the SSR gate, which passes again. An operator can make a real payable payment link on staging the way a vendor and a customer would: the phone OTP, a vendor order, the verification, then the vendor's link. The link's URL goes only into a mode-600 file. The staging spec built on it pays the link, reloads mid-payment to prove the resume, and reads the intent from Stripe on the account that owns it. The existing card-checkout spec now reads on the owning account too.**

## Performance

- **Duration:** about 17 min, from the base commit (`beb6cf37`, 21:02:52Z) to the last task commit (`8a9fbccd`, 21:19:47Z).
- **Tasks:** 3 of 3.
- **Commits:** 9 before this SUMMARY. All are on `feat/30-pay-link-page` and none is pushed; the push belongs to 30-20.

## Task results

**Task 1 (tracer): robots, Referer, SSR gate.**
- RED (`40f855e3`): the new `/pay` arm in `header-snapshot.test.ts` fails with `payIdx` -1, 1 failed and 3 passed, rc 1. Before the conf edit the SSR gate was rc 1 with `R-1 undeclared page: pay/page.tsx`, recorded in `evidence/30-19-ssr-before.txt`.
- GREEN (`53fbcfaf`):
  - `robots.ts` adds `"/pay"`.
  - `next.config.mjs` adds `'/pay/:path*'` no-referrer after the default and after `/invite`.
  - The snapshot gained one entry and nothing else; the diff was reviewed.
  - `ssr-routes.conf` declares `STATIC pay/page.tsx`. `--classify` reported SERVER for `page.tsx` and CLIENT for `pay-client.tsx`.
  - Plan verify:
    - header + CSP tests: 24/24, rc 0.
    - SSR contract: 44/44 PASS, rc 0.
    - sitemap-robots-consistency: 3/3, rc 0. `sitemap.ts` names no `/pay`; the `rg` returns rc 1, and the positive control on `path: "/` returns rc 0.
- Tracer gate: the verify was re-run on the committed tree (24/24) before expansion.
- Break arm (`10ea52e9`), run as a bracket: clean 24/24, then the arm, then clean 24/24. The restore was verified by sha256 (`79d911e4…` both times).
  - **Arm:** the `/pay` entry moved before the default. 5 red: the `/pay` order arm, the `/invite` order arm (its `findIndex` now finds `/pay` first), the snapshot, and two route-list assertions in `csp-headers`.

**Task 2 (TDD): `--stage pay-link`.**
- RED (`051c4117`): four arms plus the curl-stub routes for send, verify and issue. TAP: 4 not ok, 47 ok, rc 1. The failures were "unknown flag `--link-out`" and an unknown stage. Saved as `evidence/30-19-red-task2.txt`.
- GREEN (`e161db1d`). The plan's verify gives rc 0, missing=0, fail_lines=0, with 51 PASS. These include:
  - `pay-link-ok`: the call order is send, then order, then verify, then issue; the file is mode 600, starting from a pre-existing 644 file; 0 hits for the OTP, token, URL or client secret in stdout, stderr or argv.
  - `pay-link-otp-hidden`: rc 1, tag `otp-hidden`, 0 order or link POSTs, no file.
  - `pay-link-no-out`: rc 2, 0 az calls, 0 curl calls.
  - `pay-link-unverified`: rc 1, verify answered 422, 0 issue calls.
  - The controls `cancel-one`, `stripe-get-sanitized` and `order-first-run`.
  - `dry-run-tools`, which now covers 8 stages.
- Break arms (`a42be210`), each against a sha256-verified restore, clean 51/51 after:
  - **A, the issue response printed:** `pay-link-ok` goes red with 3 leak hits.
  - **B, no `chmod 600`:** `pay-link-ok` goes red; the file stays mode 644.
  - **C, the OTP refusal skipped:** `pay-link-otp-hidden` goes red with no tag.
- shellcheck: `scripts/staging-seed.sh` reports 2 SC1122 errors at the `print_*_plan` heredocs. They are pre-existing: the base copy fails the same way. Out of scope.

**Task 3: the staging specs and counts.**
- `d9c84bed` adds `staging-pay-link.spec.ts` and the account read in `staging-card-checkout.spec.ts`.
  - `--list` with `STAGING_E2E=1`: rc 0, desktop=1, mobile=1.
  - `--list` without it: "Total: 0 tests", rc 1.
  - Account-read check, plan form: miss=0. Reload and no-link check: 2 and 2. The strict code-only form gives reload=1 and no-link=1.
  - `tsc --noEmit` rc 0. `check-e2e-typecheck`: 56 files clean. `check-e2e-baseurl-contract`: PASS. eslint rc 0.
- Break arm (`4a2aebeb`): the `Stripe-Account` line was deleted from the card spec.
  - The plan's grep form stayed **green** (header=2, miss=0): the docblock and an error message also name the header.
  - The strict form, `headers["Stripe-Account"] = account` on a code line, went red with `MISSING header assignment in frontend/e2e/staging-card-checkout.spec.ts`.
  - After the restore, both forms were green again.
- Counts (`8a9fbccd`):
  - `docs-freshness` was rc 1, then rc 0 after `--write`.
  - `check-doc-metrics` was rc 1 with 13 FAIL lines, then rc 0 after the prose edit, 37/37.
  - New figures: Jest 3260, Playwright 196 across 49 specs, total 6753.

**Wide run on the final tree:**
- Full Jest: 275 suites, 3260/3260, rc 0.
- `npm run build` rc 0. TypeScript ran (2.7 s) and `/pay` is listed. The compile reported 843 ms, from the Turbopack cache.
- eslint over the touched frontend files: rc 0.

## Deviations from Plan

**1. [Decision-driven] One route, one declaration.** The plan names `pay/[token]/page.tsx` and a separate return or no-link page. Under the owner's fragment decision (30-16), `frontend/app/pay/page.tsx` is the only page; it serves the link, Stripe's return and the reload. It is declared `STATIC` with a reason that is true of that file: metadata only, fetches nothing server-side. The header source `'/pay/:path*'` still matches `/pay` and `/pay/<x>`, as the plan asks.

**2. [Rule 3 - Blocking] `csp-headers.test.ts` updated.** It was not in `files_modified`, but its route-list assertion pinned the list to `["/:path*", "/invite/:path*"]`, so adding `/pay` turned it red. It now names the third entry and asserts that entry's header.

**3. [Rule 1 - Interface] The OTP field is `testPreviewOtp`.** The plan said `.otp`. `PhoneVerificationService.ChallengeResult` carries `testPreviewOtp`, and it is non-null only under `expose-otp-for-test`. Reading `.otp` would have refused `[otp-hidden]` on every staging run.

**4. [Truth over action order] The send runs before the order.** The plan's action lists the order first. Its must-have truth says that under keep-hidden the stage "refuses with [otp-hidden] before any write". The send is the probe, so it now runs first. The challenge it creates is the only side effect of a refused run, and the selftest asserts 0 order or link POSTs. Order statuses DRAFT, PENDING and CONFIRMED are all "open" to `verifyOtp`, and the issue falls back to `isPhoneVerified`, so either order verifies the order's phone.

**5. [Criterion form] The account-read criterion was vacuous.** It was replaced with a stronger form, and both are recorded. The plan's `grep -c 'Stripe-Account'` form stays ≥1 when the operative line is deleted. The strict form greps for the header assignment on code lines (not `//`, `*` or `/*`). For the record: the reload and no-link counts in the plan's form also count docblock lines (2 and 2); the strict counts are 1 and 1.

**6. [Rule 2 - Correctness] Additions not in the plan:**
- The issued URL must have the `/pay#token=` shape. A path-token URL from a pre-30-16 core-java is refused without being printed.
- `--link-out` must not be a directory, its parent must exist, and any other stage refuses it.
- A `--dry-run` plan for `pay-link`. `dry-run-tools` covers it, and that PASS line changed from `stages=7` to `stages=8`.
- The desktop arm counts redeem requests and asserts it is 1 after the reload, so a re-redeem cannot pass as a resume.
- `redact()` covers `page.goto` errors, which quote the URL.

## Threat surface

No surface beyond the plan's `<threat_model>`.
- **T-30-77** (token in Referer and indexes): mitigated by the header, robots, the sitemap check, and the existing noindex.
- **T-30-78** (link, OTP or client secret in output): the mode-600 file plus `pay-link-ok`'s 0-hit leak assertion and break arm A.
- **T-30-79** (wrong tenant): the shop slug is fixed and `run_guards` runs first.
- **T-30-SC**: no package installed.

## Known Stubs

None.

## Notes for 30-20

- **Base sync.** The branch is 1 behind `origin/main` (`eff54aa0`, #1058). The only file both sides touch is `HANDOFF.md`, so expect a conflict there and resolve it by hand. Keep both blocks: do not "take theirs".
- **OpenApiSnapshotTest** is still red by design (additive only), and 30-20 Task 1 regenerates it.
- **Staging overlay:** `jtoye.phone-verification.expose-otp-for-test=true` on staging only, per the owner's decision (30-16 Task 2, "applied to the overlays by plan 30-20 Task 1"). Without it the stage refuses `[otp-hidden]`, and D-03's staging proof is recorded as not met on staging.
- **The live run, after deploy:**
  1. Run `scripts/staging-seed.sh --context jtoye-stg-aks --stage pay-link --link-out <scratch>/pay-desktop.url`.
  2. Run it again with `--link-out <scratch>/pay-mobile.url`. A link opens once, so each arm needs its own run.
  3. Run `cd frontend && STAGING_E2E=1 PLAYWRIGHT_BASE_URL=https://app-staging.olajay.co.uk STAGING_PAY_LINK_FILE=… STAGING_PAY_LINK_FILE_MOBILE=… STAGING_STRIPE_PUBLISHABLE_KEY=pk_test_… [STAGING_EVIDENCE_DIR=…] npx playwright test e2e/staging-pay-link.spec.ts --reporter=line`.
  4. Record `STAGING-PAY … account=…`, `STAGING-PAY-RELOAD … redeems=1` and `STAGING-PAY-MOBILE …`. The reload line is the only proof that Next keeps the `history.state` entry across a real reload (30-18 D6).
  5. Then cancel the mobile arm's order with `--stage cancel-drafts --orders <n>` (D-02).
  6. Keep the `--link-out` files in the scratchpad, never in evidence.
- **UI review, carried from 30-18:** the post-redirect copy "You've paid £X. You can close this page." is outside the copy contract.
- **30-32** can run both staging specs on a direct-charge shop: when the response names an account, the verdict line ends `account=acct_…`.

## Self-Check: PASSED

- All 10 created files exist (`[ -f ]`, FOUND), as do the modified sources.
- All 9 commits (`40f855e3`, `53fbcfaf`, `10ea52e9`, `051c4117`, `e161db1d`, `a42be210`, `d9c84bed`, `4a2aebeb`, `8a9fbccd`) are ancestors of HEAD (`merge-base --is-ancestor`, FOUND).
- The working tree was clean before this SUMMARY.
