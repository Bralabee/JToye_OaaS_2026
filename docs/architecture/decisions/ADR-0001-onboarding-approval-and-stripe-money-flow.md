# ADR-0001: Vendor onboarding approval stance & Stripe money-flow

- **Status:** Accepted (2026-07-11) — Decision 2's MARKETPLACE destination-charge flow
  IMPLEMENTED 2026-07-12 (issue #102 slice: V48 Connect linkage on the tenant registry,
  `StripeConnectService` Express onboarding + `account.updated` sync, destination-charge
  routing in `PaymentService.createPaymentIntent`). WHITE_LABEL direct charges remain future.
- **Deciders:** J'Toye engineering (developer decision, 2026-07-11)
- **Tracking:** GitHub #178 (onboarding auto-approve), GitHub #102 (Stripe money flow)
- **Related:** [VENDOR_ONBOARDING_STATE_MODEL.md](../VENDOR_ONBOARDING_STATE_MODEL.md) §9 item 1, Phase 18 UAT item 5

This is the first ADR in the repository and seeds the `docs/architecture/decisions/`
convention. It records two related product decisions taken while closing out the Phase 18
vendor-onboarding first slice: (1) *when* an onboarding is allowed to move past
`PENDING_APPROVAL` without a human, and (2) *how* money flows for the two commercial
models once Stripe Connect is implemented in a future phase.

---

## Decision 1 — Onboarding approval: hybrid auto-approve keyed to model

### Context

The Phase 18 first slice ships the `VendorOnboarding` aggregate, the automatic compliance
gates (`BUSINESS_VERIFIED`, `FOOD_HYGIENE_RATING`, `ALLERGEN_DATA_COMPLETE`) and the state
machine that is the sole writer of `Shop.published`. In that slice **nothing moves an
onboarding out of `PENDING_APPROVAL` except the auto-approve recompute** — the admin
approve/reject queue is deferred to #178 slice 2. So the production behaviour of the
`onboarding.auto-approve` flag is a live product decision: leave it off and no vendor can
reach `LIVE`; turn it on globally and every model self-approves with no human in the loop.

The two commercial models have different risk profiles:

- **WHITE_LABEL** — the vendor runs their own storefront and PSP; J'Toye never touches their
  funds. A fully-green compliance application carries little platform risk, so a human review
  step adds latency without materially reducing risk.
- **MARKETPLACE** — J'Toye is the merchant of record and hosts the storefront, so the
  platform carries the commercial and reputational risk of every vendor it lists. A human
  approval gate is warranted before a marketplace vendor can transact.
  *(See the 2026-09-02 amendment below: under the current interim that human is an
  employee of the applicant, so this rationale is not yet met by an independent approver.)*

### Options Considered

| # | Option | Pros | Cons |
|---|--------|------|------|
| a | Global auto-approve ON for all models | Zero onboarding latency; no admin tooling needed | MARKETPLACE vendors go live with no human review — unacceptable platform risk |
| b | Always manual (auto-approve OFF for all) | Maximum control | Blocks WHITE_LABEL needlessly; nothing can reach LIVE until the admin queue (#178 slice 2) ships |
| c | **Hybrid keyed to model** | WHITE_LABEL ships now with no new tooling; MARKETPLACE keeps a human gate | MARKETPLACE still blocked on #178 slice 2; slightly more config surface |

### Decision

**Option (c): hybrid auto-approve keyed to `OnboardingModel`.**

- **WHITE_LABEL** auto-approves (`PENDING_APPROVAL → APPROVED`) once all mandatory gates are
  `PASSED`/`WAIVED`.
- **MARKETPLACE** always requires human approval — *performed, under the Phase 21 interim,
  by the applicant tenant's own realm `admin` (amendment below).*
- New config `onboarding.auto-approve-models` (default `[WHITE_LABEL]`) expresses the per-model
  policy. `onboarding.auto-approve` is retained as a **global force-on override**: when `true`,
  every model auto-approves; when `false` (the default), per-model policy applies.
- The APPROVE step fires when `onboarding.auto-approve` is true **OR** the onboarding's model is
  in `onboarding.auto-approve-models`. These are evaluated as two distinct external calls on the
  `OnboardingProperties` bean so the existing Phase 18 `@SpyBean` E2E stub on `isAutoApprove()`
  still governs the global-force path.

### Consequences

- MARKETPLACE cannot reach `LIVE` until the admin approve/reject queue ships — **#178 slice 2 is
  still required** and #178 remains OPEN.
- Auto-approve is **not** a compliance bypass: the APPROVE guard and the GO_LIVE guard continue to
  enforce that every mandatory gate is `PASSED`/`WAIVED` (including the WR-03 fresh
  `ALLERGEN_DATA_COMPLETE` re-check at go-live), so auto-approving a WHITE_LABEL application only
  skips the human review step for a fully-green application.
- A vetoed auto-APPROVE is swallowed (WR-01): the onboarding parks at `PENDING_APPROVAL` and all
  committed gate evidence survives.

### Amendment (2026-09-02, QA council 20260902-134741 — INT-5, adjudication A13)

**What the rationale above assumes, and what actually runs.** Decision 1 justifies the
MARKETPLACE human-approval gate on the grounds that "the platform carries the commercial and
reputational risk". As shipped, the human who performs that step is **not independent of the
applicant**:

- `POST /api/v1/onboarding/admin/{id}/approve`, `/reject` and
  `/gates/{gateType}/resolve` are gated by `@PreAuthorize("hasRole('admin')")` — the Keycloak
  realm role — and every JWT carries a single `tenant_id` that FORCE-RLS pins all reads to.
  There is no cross-tenant platform-operator identity, so the only actor who can approve a
  tenant's application, or PASS its Companies House / FHRS gates, is an `admin` **of that same
  tenant**. The compliance chain is therefore self-certified end to end (measured live: the
  applicant's own admin took an application with an unmatchable company number and no FSA
  match to `LIVE`, and the shop appeared on `GET /api/v1/public/shops`).
- This is an **owner-ratified interim**, not an oversight:
  `.planning/phases/21-onboarding-blocker-ux/21-CONTEXT.md` D-01 ("Seams now, J'Toye console
  later" — *interim resolver = the existing tenant `admin` role*, documented explicitly as
  interim) and D-02 (the J'Toye platform console is deferred to its own phase because it needs
  platform-operator RBAC plus a cross-tenant RLS bypass). The Phase 21 discussion log scoped
  that interim to *gate resolution*; it did not record that the same realm role also holds
  `/approve`, and hence that **this Decision's risk rationale is inert until the operator console
  ships.** That is the gap this amendment closes on paper.
- **No authority change is made here, deliberately.** Gating approve/resolve on
  `OPERATOR`-sourced `shop_staff` grants was weighed and rejected on measurement: zero `OPERATOR`
  grants exist on the runtime (tenant 1 holds one `JIT` grant, tenant 2 holds none), so the gate
  would lock **2 of 2** tenants out of ever approving anything. Refusing resolution after a
  definitive external no-match was likewise rejected for FHRS (fuzzy name/address search — a
  legitimate new premises is a no-match) and redirected to the Companies House gate's own
  mapping (404 → MANUAL_REVIEW, never WAIVED; see `CompaniesHouseGate`).
- What changes with this amendment: the vendor-facing copy on `/dashboard/onboarding` names
  the real actor and route ("an administrator on your own account … Onboarding → Approvals")
  instead of "our team", and this ADR now states the limitation. The independent approver
  remains **tracked as open work in GitHub #453** (P1), which carries the operator-console
  deferral and the reviewer-notification gap as sub-items.

---

## Decision 2 — Stripe money flow: Connect keyed to model (#102)

### Context

J'Toye must **never hold customer money for white-label vendors** — for that model the vendor is
the merchant of record and owns the customer relationship end-to-end. For marketplace vendors,
J'Toye is the platform and routes funds to the vendor. Stripe supports both shapes via Connect
charge types, and the choice is structural, so it must be pinned before the money-flow phase
begins. This ADR records the decision only — **no Stripe implementation code is part of this task.**

### Options Considered

| # | Option | Pros | Cons |
|---|--------|------|------|
| a | Single charge flow for both models | Simplest integration | Cannot satisfy the white-label "never touch their funds" constraint; wrong merchant of record |
| b | **Stripe Connect keyed to model** | Correct merchant of record per model; matches the platform's legal position | More Connect wiring; two flows to build and test |

### Decision

**Option (b): Stripe Connect, charge type keyed to `OnboardingModel`.**

- **MARKETPLACE → destination charges.** The platform is the merchant of record; funds are routed
  to the vendor's connected account with an application fee.
- **WHITE_LABEL → direct charges + application fee.** The vendor is the merchant of record on their
  own connected account; J'Toye takes an application fee and never becomes the merchant of record.

### Consequences

- The **destination-charge (MARKETPLACE) flow is implemented first** in a future planned phase; the
  direct-charge WHITE_LABEL flow follows.
- **No Stripe code ships in this task** — this ADR satisfies the "documented decision" acceptance
  criterion on #102 only. #102 remains OPEN pending implementation.
- The onboarding `PAYMENTS_CONNECTED`/`IDENTITY_KYC` gates (driven by the Stripe
  `account.updated` webhook) will attach to whichever connected-account shape the model selects.

### Implementation note (2026-07-12, issue #102 slice)

The destination-charge (MARKETPLACE) flow is now implemented:

- **V48** adds `stripe_account_id` + `stripe_connect_status` (NONE/PENDING/ENABLED/DISABLED)
  to the `tenants` registry, alongside the tenant lifecycle columns (status/plan/contacts).
- **`StripeConnectService`** creates Express connected accounts + Stripe-hosted onboarding
  links (admin endpoint `POST /api/v1/admin/tenants/{id}/stripe/connect`) and syncs the
  capability state from the `account.updated` webhook (behind the existing
  `processed_stripe_events` idempotency guard).
- **`PaymentService.createPaymentIntent`** routes an order as a destination charge —
  `transfer_data[destination]` + `application_fee_amount` (`stripe.platform-fee-bps`) —
  **only when** the tenant's onboarding model is MARKETPLACE **and** its connected account
  is ENABLED. WHITE_LABEL and unlinked tenants keep the previous single-account behaviour
  unchanged (their direct-charge flow is still future work, per this decision).
- Verification is unit/integration level with the Stripe SDK stubbed — the dev stack has
  empty Stripe keys, so **live Connect verification is deferred to a keyed environment**.
- Still open after this slice: WHITE_LABEL direct charges, `PAYMENTS_CONNECTED`/`IDENTITY_KYC`
  onboarding gates wiring, per-Connect-endpoint webhook secret, billing/metering.

### Implementation note (2026-07-12, issue #102 tenant-lifecycle remainder)

Keycloak **user deprovisioning on offboard** is now implemented, closing the
tenant-lifecycle follow-up called out in the V48 offboard path:

- **V49** adds `tenants.keycloak_deprovisioned_at` (nullable), stamped only when ALL of an
  offboarded tenant's Keycloak users are disabled + logged out across the configured realms.
- A new `KeycloakAdminClient` (RestClient seam: master-realm token, paginated `tenant_id`
  search, disable-via-full-rep PUT, session logout) + `KeycloakDeprovisionService` orchestrate
  the sweep. Core was a pure OAuth2 resource server before this — it is the first Java-side
  Keycloak admin caller (the customer realm is excluded: it has no `tenant_id` attributes).
- The sweep runs **best-effort AFTER the offboard tx commits** (`TransactionSynchronization.
  afterCommit` → a `REQUIRES_NEW` service tx), so a Keycloak outage can never roll back or
  fail the offboard: the tenant still reaches OFFBOARDED, the marker stays NULL, an ERROR is
  logged. This is the identity-layer complement to `TenantStatusInterceptor`'s API-layer 403.
- An admin **re-trigger endpoint** `POST /api/v1/admin/tenants/{id}/keycloak/deprovision`
  (OFFBOARDED-only, admin-gated, idempotent) recovers a tenant whose after-commit sweep failed.
- **Fully inert by default** (`jtoye.keycloak.admin.enabled=false` + empty base-url): the
  service no-ops with one WARN, the endpoint returns an RFC 7807 400 "not configured". Live
  E2E enablement is deferred to a wired environment (in-cluster admin host, not localhost:8085).
