---
phase: 30-live-stripe-test-mode-execution
plan: 20
subsystem: payment-links-staging-proof
tags: [stripe, payment-link, pay-04, d-03, staging, deploy, digest-parity, referrer-policy, ingress, otp-seam, playwright]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-16 owner decisions (fragment token, expose-on-staging); 30-17 production refusal + stripeAccountId; 30-18 /pay page; 30-19 robots, next.config no-referrer, staging-seed --stage pay-link, staging-pay-link.spec.ts"
provides:
  - "PR 5 (#1064) squash-merged as bff8209764135d4b864ffcc222c1f5ada983cbf1 after a two-round D3 series (Review-Record on e6df7cd3) and deployed to staging (run 41250761893, digest parity rc 0)"
  - "OTP seam: app-config jtoye.phone-verification.expose-otp-for-test base false / staging true / production false, env JTOYE_PHONE_VERIFICATION_EXPOSE_OTP_FOR_TEST; live WARN on staging"
  - "docs/api/openapi-snapshot.json: +4 payment-link paths (body-token preview and redeem), +2 schemas, +3 nullable stripeAccountId"
  - "D-03 on staging: a vendor's payment link paid through /pay with 4242; Stripe reads succeeded, livemode false, on the spec and on an independent stripe-get"
  - "evidence/30-20-pay-live.txt (RED and GREEN side by side), evidence/30-20-pay-360.png"
  - "Issue #1071: the staging edge replaced /pay's Referrer-Policy no-referrer; fixed 2026-11-01 by PR #1073 (fd01dc0c), the live edge now sends no-referrer (evidence/30-20-pay-live.txt §9), issue closed, WINDOWS 52 fixed"
affects: [30-21, 30-22, 30-32]

estimate:
  tokens: 70000
  tasks: 3
actuals:
  tokens: 2976      # chars/4 over git diff bff82097..abe442d9 (this branch: evidence only); PR 5's own diff is the squash bff82097 (84 files, +6407/-121) and is not re-counted here
  tasks: 3
  commits: 1        # MEASURED: git rev-list --count bff82097..HEAD before this SUMMARY's own commit (Tasks 1-2 landed as the squash bff82097)
plan_head_before: bff8209764135d4b864ffcc222c1f5ada983cbf1
plan_head_after: abe442d9d5a575dcfa3e7060e3d03e268b0b66ab

tech-stack:
  added: []
  patterns:
    - "A response header asserted on the app is not asserted at the edge: read it through the public host AND through the Service (port-forward), and the difference names the layer that rewrote it"
    - "When a GREEN arm's status and type equal the RED arm's (route absent vs unknown token, both 404 not-found), assert the field that differs (the detail) or a second request whose answer differs (GET 404 -> 405)"
    - "kubectl through a scratchpad PATH shim that mints the AKS token on the pinned subscription, so scripts that call bare kubectl never touch the ambient az default"

key-files:
  created:
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-20-pay-360.png
    - .planning/phases/30-live-stripe-test-mode-execution/30-20-SUMMARY.md
  modified:
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-20-pay-live.txt
    - k8s/base/configmap.yaml, k8s/base/core-java-deployment.yaml, k8s/staging/configmap-patch.yaml, k8s/production/configmap-patch.yaml, k8s/goldens/* (in PR 5)
    - core-java/src/main/resources/application.yml, docs/api/openapi-snapshot.json, .gitleaks.toml, docs/CHANGELOG.md (in PR 5)

key-decisions:
  - "The /pay edge Referrer-Policy defect is NOT fixed inside 30-20 (Rule 4): the fix touches every overlay's ingress and the staging edge gate and needs its own reviewed PR and deploy. Filed as issue #1071 [observed]; open WINDOWS entry 52. Superseded 2026-11-01: fixed by PR #1073 (fd01dc0c), entry 52 fixed"
  - "The spec's screenshot name (30-20-pay-mobile-360.png) was renamed to the plan's artifact name (30-20-pay-360.png) rather than editing the merged spec"

requirements-completed: [PAY-04]

coverage:
  - id: D1
    description: "PR 5 merged after a zero-admissible D3 round and deployed; the running pods are the merge's images"
    requirement: PAY-04
    verification:
      - kind: other
        ref: "bash scripts/check-deploy-digest-parity.sh --context jtoye-stg-aks --tag bff82097 (rc 0; eff54aa0 fail arm rc 1)"
        status: pass
    human_judgment: false
  - id: D2
    description: "/pay answers 200 at the edge, robots.txt disallows /pay, an unknown token's preview is 404 'Payment link not found.' and GET on the preview is 405"
    requirement: PAY-04
    verification:
      - kind: other
        ref: "evidence/30-20-pay-live.txt section 8.3"
        status: pass
    human_judgment: false
  - id: D3
    description: "/pay carries Referrer-Policy no-referrer at the public edge"
    requirement: PAY-04
    verification:
      - kind: other
        ref: "2026-11-01, after PR #1073 (fd01dc0c) and deploy run 41295872214: GET https://app-staging.olajay.co.uk/pay on the live edge -> HTTP/2 200, referrer-policy: no-referrer, <meta name=referrer content=no-referrer> (evidence/30-20-pay-live.txt §9); issue #1071 closed. Supersedes plan verify 1 at 2026-10-31T23:46:51Z: no_referrer=0 (the edge sent strict-origin-when-cross-origin), the fail direction"
        status: pass
    human_judgment: false
  - id: D4
    description: "A real payment link is paid through /pay on staging (4242), read back from Stripe as succeeded/livemode false; the 360 px phone arm has no sideways scroll"
    requirement: PAY-04
    verification:
      - kind: e2e
        ref: "frontend/e2e/staging-pay-link.spec.ts (2 passed, STAGING-PAY ... status=succeeded livemode=false)"
        status: pass
      - kind: other
        ref: "staging-seed.sh --stage stripe-get --path /v1/payment_intents/pi_3UWm0PDpS9ICNeRt2UOAYD7l"
        status: pass
    human_judgment: false
  - id: D5
    description: "The 360 px /pay card form fits the width with the payment-method tabs visible and no clipped controls"
    verification:
      - kind: automated_ui
        ref: "playwright:evidence/30-20-pay-360.png"
        status: unknown
    human_judgment: true
    rationale: "The plan's human-check: the screenshot is read by eye. Awaiting the owner."

duration: 2h30m
completed: 2026-10-31
---

# Phase 30 Plan 20: PR 5 merged, deployed and a payment link paid on staging Summary

**PR 5 (#1064, the /pay page) merged as `bff82097` and deployed with digest parity. On staging, a vendor's payment link was paid through `/pay` with 4242, and Stripe reads the intent back as succeeded and livemode false. But the public edge still sends `Referrer-Policy: strict-origin-when-cross-origin` on `/pay`: the frontend sends `no-referrer`, and the ingress `more_set_headers` replaces it (issue #1071).** **Superseded 2026-11-01:** PR #1073 (`fd01dc0c`) fixed it; the live edge now sends `referrer-policy: no-referrer` on `/pay` (`evidence/30-20-pay-live.txt` §9), issue #1071 is closed and WINDOWS entry 52 is fixed.

## Performance

- **Duration:** about 2h30m across the three tasks (Task 1 from 21:25Z; Task 3 from 23:03Z, about 52 min of which was the deploy run)
- **Started:** 2026-10-31T21:25:15Z
- **Completed:** 2026-10-31T23:52Z
- **Tasks:** 3 of 3
- **Files modified:** 2 on this branch (evidence); 84 in PR 5

## Accomplishments

- **Task 1 (in PR 5):**
  - The OTP seam is on for staging only (production render `true`=0, staging `true`=1).
  - The OpenAPI snapshot was regenerated after a recorded red: additive only.
  - RED reads were taken on staging, and the per-PR suite was rc 0.
- **Task 2 (owner):** the D3 series ran to zero:
  - round 1 found 2 admissible, both fixed;
  - the tenancy pass found 0, with a live arm;
  - round 2 found 0.
  - Then the pre-merge sitting (14/14 required checks pass) and the squash-merge as `bff82097`.
- **Task 3:**
  - The merge run 41250761893 succeeded, including Deploy to Staging and the Staging Edge Probe.
  - Digest parity rc 0 for core-java, edge-go and frontend; the fail arm on the previous tag was rc 1.
  - GREEN reads beside the RED ones.
  - Two pay-link runs; the spec passed twice; an independent `stripe-get`.
  - The mobile arm's order was cancelled, and both link files were deleted.

### RED (Task 1, runtime `eff54aa0`) and GREEN (runtime `bff82097`)

| Read | RED 21:30Z | GREEN 23:46–23:47Z |
|---|---|---|
| `GET /pay` (edge) | 404 | **200** |
| `/pay` Referrer-Policy at the edge | strict-origin-when-cross-origin | strict-origin-when-cross-origin, **FAIL** |
| `/pay` Referrer-Policy from the frontend Service | n/a | **no-referrer** (`/payments` and `/` control: the default) |
| robots.txt `Disallow: /pay` | not read | **1** |
| `POST …/payment-links/preview` with an unknown token | 404 not-found, detail "Resource not found" | 404 not-found, detail **"Payment link not found."** |
| `GET …/payment-links/preview` | 404 (route absent) | **405** (route present) |
| OTP seam | n/a | configMap `true`, `configMapKeyRef` env, startup WARN at 23:43:48; `testPreviewOtp` returned (pay-link rc 0 twice) |

### The staging payment

```
PAY-LINK order=ORD-1AAC0CDE-20261031-A22F26BE link=2d0d8957-517e-4589-9481-b9507c0713ed expires=2026-11-01T00:48:12.540466513Z
PAY-LINK order=ORD-1AAC0CDE-20261031-F9B8BA14 link=b843e70d-9554-4ec3-8d72-904eafcd7980 expires=2026-11-01T00:48:17.21209339Z
STAGING-PAY-MOBILE order=ORD-1AAC0CDE-20261031-F9B8BA14 scroll_width=360 inner_width=360 pointer=coarse
STAGING-PAY-RELOAD order=ORD-1AAC0CDE-20261031-A22F26BE card_form=remounted redeems=1
STAGING-PAY order=ORD-1AAC0CDE-20261031-A22F26BE intent=pi_3UWm0PDpS9ICNeRt2UOAYD7l status=succeeded livemode=false at=2026-10-31T23:48:45.738Z account=none
```

- **Independent `stripe-get`** (platform account): `status=succeeded livemode=False amount_received=950 gbp`, `metadata.order_number` equal to the order above, `metadata.environment=staging`.
- **Mobile arm's order:** `SEED-CANCEL … intent_status=canceled order_status=CANCELLED livemode=false`.
- **Link files:** `test ! -e "$LINK1" && test ! -e "$LINK2"` rc 0. The control, on a file that exists, gave rc 1.

## Task Commits

1. **Task 1** (on `feat/30-pay-link-page`, squashed into `bff82097`):
   - `27ffb459` OTP seam
   - `fd6250de` OpenAPI snapshot
   - `7d63a580` evidence
   - `6536d143` gitleaks fixture allowlist
   - `a3734d88` CHANGELOG/state
2. **Task 2** (the owner's series):
   - `68532b1c` RED and `05fd74a2` fix for round 1
   - `31ec8b03`, `c4bc1492`, `e6df7cd3` CI gate fixes
   - merge `bff82097` (#1064)
3. **Task 3:** `abe442d9` (docs), with the evidence and the 360 px screenshot.

**Plan metadata:** the SUMMARY/STATE/ROADMAP/HANDOFF commit follows on `feat/30-pay01-refund-e2e`.

## Decisions Made

- The edge Referrer-Policy fix is out of this plan's scope (Rule 4). It is surfaced to the owner and filed, not patched inside an evidence-only task.
- The screenshot was renamed to the plan's artifact name. The merged spec was left untouched.
- `requirements.mark-complete PAY-04` was deliberately NOT run. In `REQUIREMENTS.md`, PAY-04 is unticked "pending owner acceptance", and its scope (delivery to a real phone, the WhatsApp bridge) is wider than this plan. `requirements-completed` above names the plan's requirement, not the tick.

## Deviations from Plan

1. **[Rule 4 - must-have FAILED, surfaced] `/pay` Referrer-Policy at the edge.**
   - **Found during:** Task 3, plan verify 1 (`no_referrer=0`, verify rc 1).
   - **Cause:** `k8s/base/ingress.yaml:38` and `k8s/staging/ingress-hosts-patch.yaml:56` set `more_set_headers "Referrer-Policy: strict-origin-when-cross-origin"`, which replaces the app's header. Proven by reading the frontend Service directly: `/pay` `no-referrer`, `/payments` default.
   - **Why not fixed here:** the fix touches every overlay's ingress and the staging edge gate, and needs its own reviewed PR and deploy.
   - **Tracking:** issue #1071 [observed]; `.planning/WINDOWS.md` entry 52 (unmet-truth). Superseded 2026-11-01: fixed by PR #1073 (`fd01dc0c`, deploy run 41295872214), entry 52 fixed (resolved 2026-11-01T10:50:25Z), issue closed; it no longer blocks `/gsd-ship`.
   - **Consequence:** the a11y must-have ("Stripe.js completes under no-referrer") is proven by the app-level Jest arms only, not on staging. The staging payment ran under strict-origin-when-cross-origin.
2. **[Rule 3 - tooling] kubectl token.** Neither `staging-seed.sh` nor `check-deploy-digest-parity.sh` adds `--token`, so I used a scratchpad PATH shim. It mints the AKS token on the pinned subscription `c483d353…`. No repo change.
3. **[Rule 3 - naming] Screenshot.** The spec wrote `30-20-pay-mobile-360.png`; it was renamed to the plan's `30-20-pay-360.png`.

**Total deviations:** 1 surfaced (Rule 4, must-have unmet), 2 auto (Rule 3).

## Issues Encountered

- **Frontend E2E flake:** Frontend E2E (public surfaces) failed twice on the Turbopack `next/font/google` resolve. Once was PR attempt 1; once was main's run 41238312724 for `eff54aa0`. It passed on rerun and on the merge run. It is a recurring runner-network flake. It does not gate Deploy to Staging (`eff54aa0` deployed despite it). Recorded, not filed.
- **GitGuardian** (not a required check) reported "12 secrets": the fixture class. Recorded.

## Known Stubs

None.

## Threat Flags

None. No new surface in this task. T-30-81 (link URLs on the operator host) is mitigated: mode-600 scratchpad files, zero token hits in any log, both files deleted with the deletion recorded.

## For the Owner

- **Eyeball** `/home/sanmi/IdeaProjects/JToye_OaaS_2026-p30-15/.planning/phases/30-live-stripe-test-mode-execution/evidence/30-20-pay-360.png` (the human-check: fits 360 px, tabs visible, no clipped controls). **Awaiting the owner's eye; not claimed.**
  - Seen while checking it for personal data: the cookie banner is drawn over the Expiry date and Security code fields. At 360x780 it covers part of the card form until "Got it" is pressed.
- **Issue #1071:** no longer an owner item. The option "the apps own Referrer-Policy" was taken and shipped in PR #1073 (`fd01dc0c`, 2026-11-01); the issue is closed.
- **Carried forward:**
  - Whether to persist the owning Stripe account on the order at intent creation (30-17, round-1 finding 2).
  - For UI review: the "You've paid £X" redirect copy and the resume-path dead-end copy.
  - Round-2 recorded residual (display-only): `/pay?payment_intent_client_secret=<garbage>&redirect_status=succeeded` with the retrieve rejecting still shows "Payment received".

## Next Phase Readiness

- 30-21 continues on `feat/30-pay01-refund-e2e` (cut from `origin/main` `bff82097`, not pushed).
- Antigravity's `-agy-p30-w21` worktree holds a parallel 30-21/22 attempt on `agy/30-21-22-refund-e2e`. Reconcile it before 30-21 starts.

## Self-Check: PASSED

- Files: the evidence txt, the 360 px png and this SUMMARY were all FOUND.
- Commits:
  - The PR-branch commits 27ffb459, fd6250de, 7d63a580, 6536d143, a3734d88, 68532b1c, 05fd74a2, 31ec8b03, c4bc1492 and e6df7cd3 are present as objects. They are squashed into bff82097, so they are not ancestors of HEAD.
  - bff82097 and abe442d9 are ancestors of HEAD.
- Issue #1071 was open at this check (2026-10-31). Re-read 2026-11-01: closed at 10:04:59Z by PR #1073.
- Note: the self-check passes on what the SUMMARY claims. The plan's must-have on the edge Referrer-Policy FAILED at this check and is recorded above. Superseded 2026-11-01: MET on the live edge after PR #1073 (`evidence/30-20-pay-live.txt` §9; coverage D3 pass).
