# Phase 30: The Money Path, Executed - Context

**Gathered:** 2026-10-21 (no discuss-phase: the owner chose "continue without context" and ruled the
research's open questions at plan time; this file records the scope and those rulings)
**Status:** Ready for planning

<domain>
## Phase Boundary

Gap closure against what PR #949 already put on main. That PR's `30-VERIFICATION.md` (2026-10-17) is a
laptop-run report and is NOT staging evidence (30-RESEARCH.md: its Stripe object ids belong to a different
test account than the staging key). This phase closes:

- **issue #957 (P0):** staging card checkout never shows a payment step. Root cause per 30-RESEARCH.md: the
  frontend image is built without `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` (no Dockerfile ARG, no CI build-arg),
  so `getStripe()` returns null and the page silently falls back to the form.
- **issue #958 (P1):** an unpaid card DRAFT can be submitted and confirmed (emails "Received"/"Confirmed");
  the payment guard only covers CONFIRMED→PREPARING. Also the payment-success webhook resets a CONFIRMED
  order to PENDING, and the kitchen toast drops the server's refusal reason.
- **PAY-01:** the refund E2E (#61) runs and passes against Stripe TEST mode (not a mock), its ALLOW entries
  leave `scripts/gates/e2e-skip-budget.conf`, and the gate is shown to fail on a bare ALLOW.
- **PAY-03:** the full loop on STAGING (onboard → order → capture → payout to a connected account → refund),
  both settlement modes, every step read from Stripe's API. Needs the Connect webhook endpoint.
- **PAY-02 / PAY-04:** built; this phase adds only what the rulings below put in scope.
- **issue #108 (Stripe half):** explicit Stripe SDK timeouts; refund/retrieve behind the breaker.

**Out of scope:** live mode (TEST mode only, always); production cutover (Phase 32); per-environment frontend
images (Phase 32).

</domain>

<decisions>
## Implementation Decisions

### Owner rulings at plan time (2026-10-21, after 30-RESEARCH.md)
- **D-01: vendor-created orders (`paymentStatus=NONE`) are NOT guarded in Phase 30.** The #958 guard covers
  card/link orders awaiting payment only; a vendor dashboard or MCP order keeps its current flow. Recorded as
  a known gap in the phase's verification, with a route to a later phase.
- **D-02: no DRAFT sweeper.** The abandoned card DRAFTs on staging (incl. the 2026-10-19 swarm's) are
  cancelled by hand as an operational step of this phase, with the PaymentIntents cancelled in Stripe test
  mode and the result read back. An automated sweeper is a recorded gap, not built.
- **D-03: the `/pay/{token}` payment-link page is IN Phase 30.** It reuses the #957 Stripe Elements path; a
  link PAY-04 sends must be payable end-to-end on staging.
- **D-04: PAY-02 bills per LOCATION.** The subscription quantity follows the tenant's active shops (synced
  on shop add/remove/deactivate), proven by a test-mode subscription whose quantity matches and an invoice
  retrieved by id.

### Settled policy (do not re-open)
- **D-05:** the consumer pays, by link or card, before service; no cash at fulfilment (ROADMAP Phase 30,
  owner 2026-08-02 / 2026-10-05). ADR-0007's policy axes stand.
- **D-06:** Stripe TEST mode only; every evidence object must read `livemode: false`.
- **D-07:** merges to main redeploy staging (`DEPLOY_STAGING_ENABLED=true`), so plans ship as serial,
  separately mergeable PRs, each watched through Deploy to Staging + Staging Edge Probe.

### Claude's Discretion (research recommendations, taken as defaults; owner may override)
- **D-08:** a production deploy refuses a frontend image with a `pk_test_` key baked in (refusal now;
  per-environment images in Phase 32).
- **D-09:** CI uses a dedicated Stripe sandbox account, not the staging account; the owner supplies its
  `sk_test_`/`pk_test_` as a GH secret/variable.
- **D-10:** the non-Stripe half of #108 (SMTP, axios, S3, the unused email breaker) stays on the issue.

### Owner actions the plans must gate on (not tasks Claude can do)
- set the staging `pk_test_` repo variable; create the CI sandbox key; register the Stripe Connect webhook
  endpoint pinned to the API version the code uses; complete the hosted Connect onboarding. Each is a
  `checkpoint:human-action` before the PR that needs it merges.

</decisions>

<canonical_refs>
## Canonical References
- `.planning/phases/30-live-stripe-test-mode-execution/30-RESEARCH.md` (root causes with file:line)
- `.planning/phases/30-live-stripe-test-mode-execution/30-VERIFICATION.md` (PR #949's laptop report; unverified)
- `.planning/ROADMAP.md` § Phase 30; `.planning/REQUIREMENTS.md` PAY-01..04
- `.planning/phases/29-deployable-staging-with-its-own-monitoring/29-ORCHESTRATOR-LEDGER.md` "2026-10-21 — PHASE CLOSE"
- GitHub issues #957, #958, #61, #102, #108, #461, #462
</canonical_refs>
