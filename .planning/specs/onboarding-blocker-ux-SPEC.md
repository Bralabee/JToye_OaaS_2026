# SPEC — Onboarding Blocker UX: visible blockers, correctable data, reachable exits

**Status:** DECIDED 2026-07-14 — ready for a future milestone phase (spec-now, build-later)
**Decided by:** user, session 2026-07-14 (scope quoted verbatim from the verified assessment)
**Origin:** user observation: "if a user hits a blocker whilst trying to onboard, there's really nothing to prompt them what the issue is, how to proceed, what actions will follow, and options to pause or progress — to fix an error or provide alternative valid info."

## Problem (verified 2026-07-14, file:line evidence)

1. **Manual review is a black hole for everyone.** `GateChainRunner.java:167-199` advances only on all-PASSED/WAIVED (`GATES_PASSED`) or any-FAILED (`GATE_FAILED`); MANUAL_REVIEW is neither, so the application stays in `VERIFYING` indefinitely. The vendor page (`frontend/app/dashboard/onboarding/page.tsx`) shows "Running your compliance checks. This usually takes under a minute" and polls every 4s forever. Worse, the admin queue `GET /onboarding/admin/pending` lists ONLY `PENDING_APPROVAL` (`OnboardingAdminController.java:62-72`) — a manual-review application appears in NO queue, vendor or admin, so the "needs a human decision (never hard-fails a vendor)" promise in `GateStatus.MANUAL_REVIEW` is structurally unfulfillable. The FHRS gate returns MANUAL_REVIEW for the *ordinary* fuzzy name/address mismatch, so this is the common real-world path, not an edge case.
2. **No way to provide alternative valid info.** Vendor endpoints are only create / submit / resubmit / go-live / me (`OnboardingController.java`). `companyNumber` is captured once at creation; RESUBMIT re-runs the gates against the same data. A typo'd company number or an unmatchable shop address is a permanent dead end.
3. **Pause/withdraw is unreachable.** `OnboardingState.WITHDRAWN` exists (and is in the V43 status CHECK) but `OnboardingEvent` has no WITHDRAW and no endpoint fires one — the state cannot be reached.
4. **Rejection reasons never reach the vendor.** `RejectOnboardingRequest.reason` is `@NotBlank` (admin must record it) but only `AdminOnboardingDto` exposes it; the vendor-facing `OnboardingDto` omits it. The REJECTED screen says "Contact support for details" with no support channel.
5. **Reasons are diagnosis, not guidance.** Gate reasons render verbatim (e.g. "company status is 'dissolved' (not active)", "No FSA establishment matched the shop name/address") with no what-to-do-next and no link to the fix surface.

## Locked scope (user, 2026-07-14)

**Backend**
- Add the `WITHDRAW` event + `POST /onboarding/withdraw` (valid from DRAFT / VERIFYING / ACTION_REQUIRED; terminal; state machine remains the sole authority).
- Update endpoint valid in DRAFT / ACTION_REQUIRED: company number, sole-trader flag, FHRS establishment override.
- Route manual review into a visible state (resolution below) — vendors see "in review", admins see a queue entry.
- Expose `rejectionReason` on the vendor DTO.

**Frontend**
- Per-gate remediation blocks: each FAILED / MANUAL_REVIEW gate renders *why → what to do → a button that goes there* (company number inline edit; "fix these N products" deep link for allergen offenders; address confirm / establishment picker for FHRS).
- Honest in-review copy with an expectation ("a reviewer checks these within N business days") instead of "under a minute"; polling backs off once in review.
- A Withdraw action (confirm dialog; terminal, restart = new application).
- Rejection reason + a real support channel (configurable mailto/link, not a bare "contact support") on terminal states.

**Later tie-in:** state-change notifications (email/webhook) ride the pending #205 webhooks work (V46 outbox).

## Resolution of the "visible state" choice (recommended)

**Derive, don't migrate.** Keep `VERIFYING` in the DB; the DTO layer derives `reviewPending = status==VERIFYING && anyGate==MANUAL_REVIEW && noGate==PENDING` and the UI renders it as "In review". The admin surface gains a review queue (extend `/pending` or add `/reviews`) listing these applications, plus the missing human-decision mechanism: `POST /onboarding/admin/{id}/gates/{gateType}/resolve {decision: PASS|WAIVE|FAIL, reason}` — writes the gate row (audited via the V43 `_aud` mirror), then triggers the existing recompute so the state machine advances normally. Zero schema changes, no state-machine surgery, CHECK constraints untouched. (Alternative — an explicit `IN_REVIEW` state — documented and rejected for this slice: it costs a V5x CHECK migration + transitions for no additional user-visible value.)

## Explicitly deferred
- #205 notifications (email/webhook on state change) — separate track, this spec only leaves the seams.
- Reviewer SLA tracking/escalation; multi-reviewer workflow.
- Reapply-after-REJECTED flow (terminal stays terminal this slice; support channel is the path).

## Constraints
- The state machine remains the sole writer of `Shop.published`; every transition goes through events (no direct status writes) — matches the existing `OnboardingAdminController` doctrine.
- Zero Flyway migrations in the recommended path (verify at plan time; `WITHDRAWN` is already in the V43 CHECK).
- Update endpoint must re-validate like create (bounded company number format) and is rejected outside DRAFT/ACTION_REQUIRED with RFC 7807.
- Tests per project standard: state-machine transition tests (WITHDRAW, gate-resolve recompute), controller tests (update-endpoint state guard), Jest for remediation blocks/in-review copy/withdraw dialog; reconcile `docs/metrics.json` via `scripts/docs-freshness.sh --write`.
- Journey-matrix addition: drive at least one blocked onboarding end-to-end (bad company number → fix inline → resubmit → live) in Playwright.
