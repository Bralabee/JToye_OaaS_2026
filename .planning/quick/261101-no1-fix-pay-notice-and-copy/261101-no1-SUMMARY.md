---
phase: quick
plan: 261101-no1
subsystem: frontend-public, frontend-pay, docs
tags: [pay, payment-link, cookie-notice, stripe, redirect-return, ui-spec]
status: complete
requires: []
provides:
  - cookie notice suppressed on /pay and /pay/<segment> (ConsentBanner precedence kept)
  - resume-path Stripe.js load failure offers Try again (reload)
  - Stripe redirect return decided by the readable intent only; unreadable intent is the check-failed state with a re-read
affects:
  - /pay card form on a 360 px phone (first visit)
  - 30-UI-SPEC.md copy contract rows for /pay
tech-stack:
  added: []
  patterns:
    - "route carve-out after the consent branch, never before it"
    - "navigation seam object (payNavigation.reload) because jsdom 26 Location is unforgeable"
    - "retry target carried in the phase (checkFailed.returnRead) so one Try again re-runs the right read"
key-files:
  created:
    - .planning/quick/261101-no1-fix-pay-notice-and-copy/261101-no1-PLAN.md
    - .planning/quick/261101-no1-fix-pay-notice-and-copy/evidence/fix1-cookie-notice-tdd.txt
    - .planning/quick/261101-no1-fix-pay-notice-and-copy/evidence/fix2-resume-retry-tdd.txt
    - .planning/quick/261101-no1-fix-pay-notice-and-copy/evidence/fix3-unreadable-intent-tdd.txt
    - .planning/quick/261101-no1-fix-pay-notice-and-copy/evidence/gates.txt
  modified:
    - frontend/components/public/cookie-notice.tsx
    - frontend/components/public/__tests__/cookie-notice.test.tsx
    - frontend/app/pay/pay-client.tsx
    - frontend/app/pay/__tests__/pay-client.test.tsx
    - .planning/phases/30-live-stripe-test-mode-execution/30-UI-SPEC.md
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - docs/CHANGELOG.md
decisions:
  - "The /pay carve-out is /pay exactly or /pay/<segment>, never a bare prefix (/payments keeps the notice)"
  - "ConsentBanner is checked before the /pay carve-out: a consent gate a route can opt out of is not one"
  - "Only the resume path's Stripe.js load failure is reloadable; the first-visit linkOpened state keeps no button"
  - "redirect_status is never read as the outcome; only a readable PaymentIntent status decides"
metrics:
  duration: "~60 min"
  completed: 2026-11-01
actuals:
  tokens: 8500
  tasks: 3
  commits: 8
plan_head_before: 6c1fdc93c533267a4289281758417407f0ac879a
plan_head_after: ffb80341ef720cb836de80f47fd90f698dda97c0
---

# Quick 261101-no1: /pay Notice and Copy Fixes Summary

`/pay` no longer shows the cookie notice over its card form. After a reload, if Stripe.js fails to load, the page now offers a "Try again" that reloads. On Stripe's redirect return, an intent the page cannot read now shows "We couldn't check this link just now" with a re-read, never "Payment received".

`commits: 8` was counted from the ledger as 7 at write time, plus the commit that carries this SUMMARY and the CHANGELOG entry. `plan_head_after` is the HEAD before that commit, because a file cannot record its own commit's sha.

## Commits

| Fix | Commit | Subject |
|-----|--------|---------|
| 1 (red) | f6f91f3f | test(public): the cookie notice must not render on /pay (red) |
| 1 (fix) | a8df5fcd | fix(public): no cookie notice over the /pay card form |
| 2 (red) | cd32c224 | test(pay): a resume-path Stripe.js load failure must offer Try again (red) |
| 2 (fix) | 978e01fd | fix(pay): a resume-path Stripe.js load failure offers Try again (reload) |
| 3 (red) | 5ce434b5 | test(pay): an unreadable intent on Stripe's redirect return must not read as paid (red) |
| 3 (fix) | 82b93d32 | fix(pay): an unreadable intent on Stripe's redirect return is "couldn't check", not "Payment received" |
| docs | ffb80341 | docs: test counts for the /pay notice and copy fixes |
| record | (this commit) | docs: changelog (#1072) and quick-task summary |

I read every message back with `git log -1 --format=%B`. None has an attribution trailer or footer.

## Evidence (in `evidence/`)

### fix1-cookie-notice-tdd.txt

- **RED on the old component, rc=1.** The `/pay` and `/pay/anything` arms failed. The `/` and `/payments` control and the banner-precedence arm passed (2 failed, 28 passed).
- **GREEN, rc=0:** 30/30.
- **Break arms after a8df5fcd.** Each was restored and checked by sha256 against the committed file.
  - A, the carve-out removed: both `/pay` arms RED.
  - B, a bare `startsWith("/pay")`: the `/payments` control RED.
  - C, the carve-out moved before the banner: the precedence arm RED.
- **Clean again:** 30/30, and `git diff --exit-code` rc=0.

### fix2-resume-retry-tdd.txt

- **RED, rc=1.**
  - The resume block failed: no "Try again" button.
  - The seam block failed: `payNavigation` was undefined.
- **GREEN:** 40/40. Then a first-visit guard block was added (the connected-account Stripe.js load fails after redeem: no Try again), giving 41/41.
- **Break arms after 978e01fd**, each restored by sha256:
  - A, never reloadable: the resume block RED.
  - B, always reloadable: the first-visit guard RED.
  - C, the seam is a no-op: the seam block RED.
- **act() warnings.** Two "not wrapped in act" warnings show in both the RED and GREEN runs, from `continueToPayment`'s setPhase. They are not from this change.

### fix3-unreadable-intent-tdd.txt

- **Old fallback tests.** No existing block ASSERTED the `redirect_status` fallback (measured with `rg -uu redirect_status frontend/`). The bare-`redirect_status` test's comment described the fallback; that comment is corrected. So no existing block went RED.
- **RED, rc=1:** all three new blocks failed. The DOM dump shows "Payment received" rendered in each. The three blocks are:
  - Stripe answers `{ error }`;
  - the read rejects, then Try again re-reads;
  - there is no publishable key.
- **GREEN, rc=0:** 44/44.
- **Break arms after 82b93d32**, each restored by sha256:
  - A, unreadable shown as redirectFailed: all three new blocks RED.
  - B, Try again re-runs the preview: the retry block RED.
  - C, a readable `requires_payment_method` shown as checkFailed: the existing `redirect_status=failed` arm RED.

### gates.txt

| Gate | Fail direction | Result |
|------|----------------|--------|
| `npx tsc --noEmit` | rc=2 on a deliberate one-argument `runReturn` call (restored by sha256) | rc=0 |
| `npm run lint` | not run | rc=0, 0 errors and 27 warnings. Eslint scoped to `app/pay` and `components/public` prints nothing |
| `npx jest --ci app/pay components/public` | not run | 9 suites, 136 tests, rc=0 |
| full `npx jest --ci` | not run | 275/275 suites, 3271/3271 tests, rc=0 |
| `scripts/docs-freshness.sh` | rc=1 before `--write` | rc=0 after |
| `scripts/check-doc-metrics.sh` | rc=1 before the prose edits (7 FAIL lines) | rc=0 after, 37/37 |
| `scripts/check-doc-citations.sh` | not run | rc=0 |
| `scripts/check-no-object-store-residue.sh` | not run | rc=0 |
| `scripts/check-handoff-contract.sh` | not run | rc=0 |
| `scripts/check-branch-behind-base.sh` | not run | rc=0, 0 behind origin/main 6c1fdc93 |
| `scripts/check-changelog-contract.sh` | its built-in self-test fires | rc=0, 86/86 cited |
| `scripts/check-changelog-cites-pr.sh` | not run | rc=0, SKIP: no PR context locally; CI runs it |

Where the fail direction is "not run", the rc=0 is **unverified** as a discriminating check on this tree.

## Measured counts

- jest_blocks went from 3261 to 3271. jest_files stayed at 275.
- total_logical_invocations went from 6773 to 6783.

## Deviations from Plan

**1. [Rule 3 - Blocking] The reload goes through a seam, not a `window.location.reload` mock**
- **Found during:** Fix 2 (RED)
- **Issue:** the brief asked for a test that mocks `window.location.reload`. In jsdom 26 that cannot be done; both probes were run:
  - `jest.spyOn(window.location, "reload")` throws "Cannot assign to read only property 'reload'".
  - `Object.defineProperty(window, "location", …)` throws "Cannot redefine property: location".
- **Fix:** `pay-client.tsx` exports `payNavigation = { reload: () => window.location.reload() }`, looked up at click time.
  - The click test spies on `payNavigation.reload`.
  - A second block proves the seam's default really navigates: jsdom logs "Not implemented: navigation".
  - Break arm C shows that second block goes RED when the seam stops calling reload.
- **Commit:** 978e01fd

**2. [Rule 2 - Missing guard] A first-visit block for the post-redeem Stripe.js failure**
- **Found during:** Fix 2 (GREEN)
- **Issue:** nothing tested that a first visit has no reload button. An "always reloadable" implementation would have stayed green.
- **Fix:** one guard block was added. Break arm B shows it goes RED.
- **Commit:** 978e01fd

**3. Probe files.** Two throwaway jsdom probes ran under `frontend/__probe__/`. Both were deleted, and `git status` was clean afterwards.

**4. STATE.md not updated.** The quick-task table row was not added, because the brief listed only PLAN, SUMMARY and evidence for this record. A parallel PR in flight may also touch STATE.md. The row can be added when this PR merges.

## Cross-cutting quality contracts

- **Web performance:** the card form is no longer covered on a phone. No dependency was added, and nothing new renders on `/pay`.
- **SEO:** N/A. `/pay` is noindex,nofollow, and its metadata is not touched here.
- **Agent-readiness:** N/A, no API change.
- **Client-persisted identity:** N/A. No storage is written, and the existing setItem-spy test still passes.
- **Security:**
  - T-no1-01 is mitigated. A success state now needs a readable intent, and `redirect_status` is ignored.
  - T-no1-02 and T-no1-03 are accepted as planned.
- **Falsifiable evidence:** every new assertion was shown RED, or is a guard proven by a break arm. The gates are listed above with their fail direction.
- **Runtime parity:** no runtime was handed back or rebuilt.
- **Browser check:** no 360 px re-check was run. The next staging pass is the visual proof for fix 1.

## PR #1072 review round 1 (on b80b1dcf)

The coordinator ruled two of the eight inline findings admissible. A third was a comment correction. The other five are recorded in the resolution, not fixed: the checkout-page overlap, the "just now" copy for deterministic failures, the jsdom wording assertion, reusing the route helper, and the 360 px visual check.

| Finding | Commit | Change |
|---------|--------|--------|
| 2 (no-key reload loop) | 1683c07c (red), 4d189f50 (fix) | `reloadable: fromResume && promise !== null`. A bundle with no key is a build constant, so no Try again is offered |
| 3 (resume-path Payment Element failure) | 1683c07c (red), 4d189f50 (fix) | The `paying` phase carries `fromResume`, and `onLoadError` sets `reloadable: phase.fromResume` |
| 1 (comment only) | 4d189f50 | The CookieNotice header now states the true premise (details below). The carve-out's behaviour is unchanged |

**Finding 1, the corrected premise.** Stripe.js sets first-party fraud-prevention cookies on `/pay`. They are strictly necessary, and `/legal/cookies` ("Stripe, on the payment step") discloses them. `/pay`'s PublicShell footer links that page.

**Correction to this record.** The PLAN's T-no1-02 rationale ("/pay writes no client storage") and the first SUMMARY's cross-cutting line had the same false premise. Both are superseded by the corrected comment.

**Evidence: `evidence/review-r1-fixes-tdd.txt`.**
- **RED, rc=1:** the 2 new blocks failed (the no-key block found a Try again; the Payment Element block found none). The first-visit Payment Element block, which now also asserts no Try again, passed.
- **GREEN, rc=0:** 46/46.
- **Break arms after 4d189f50**, each restored by sha256:
  - A, no key reloadable: the no-key block RED.
  - B, the element failure never reloadable: the resume element block RED.
  - C, the element failure always reloadable: the first-visit element block RED.

**Gates: `evidence/review-r1-gates.txt`.**

| Gate | Result |
|------|--------|
| pay + public suites | 9 suites, 138 tests, rc=0 |
| full frontend jest | 275/275 suites, 3273/3273 tests, rc=0 |
| `npm run lint` | rc=0 |
| `npx tsc --noEmit` | rc=0 |
| `scripts/docs-freshness.sh` | rc=1 before `--write`, rc=0 after (jest_blocks 3271 to 3273, total 6783 to 6785) |
| `scripts/check-doc-metrics.sh` | rc=1 before the prose edits, rc=0 after |
| `check-doc-citations.sh`, `check-no-object-store-residue.sh`, `check-branch-behind-base.sh`, `check-handoff-contract.sh` | rc=0 each |

## Self-Check: PASSED

- Commits on HEAD: FOUND f6f91f3f, a8df5fcd, cd32c224, 978e01fd, 5ce434b5, 82b93d32, ffb80341.
- Files: FOUND for all four evidence files and the PLAN.
