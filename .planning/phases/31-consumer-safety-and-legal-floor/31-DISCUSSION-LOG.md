# Phase 31: Consumer-Safety and Legal Floor - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-15
**Phase:** 31-consumer-safety-and-legal-floor
**Areas discussed:** Allergen lawful basis (LGL-03), Cookie/consent scope (LGL-01),
Accessibility target + gate placement (LGL-02), Controller/processor split (LGL-01)

---

## Allergen lawful basis (LGL-03)

### Q1 — Does checkout consult the stored `Customer.allergenRestrictions` bitmask?

| Option | Description | Selected |
|--------|-------------|----------|
| No — record the decision not to | Ship order-level aggregation + KDS surfacing from PRODUCT data only; record a dated lawful-basis decision. Article 9 consent is a heavy, revocable obligation | ✓ |
| Yes — on explicit Article 9 consent | Opt-in in the customer account; platform becomes controller of special-category health data | |
| Yes, but authenticated-only and ephemeral | Consult but never persist the comparison; "ephemeral" is hard to prove to a regulator | |

**User's choice:** No — record the decision not to.

### Q2 — When a conflict is detected, what does the platform do?

| Option | Description | Selected |
|--------|-------------|----------|
| Warn + explicit acknowledgement | Surface it and require active confirmation; creates a record the warning was shown | ✓ |
| Block the order line | Safest-sounding, but makes the platform liable for a vendor's hand-typed integer | |
| Inform only, no interruption | Lowest friction, close to the status quo | |

**User's choice:** Warn + explicit acknowledgement.

### Q3 — What does order-level aggregation actually aggregate?

| Option | Description | Selected |
|--------|-------------|----------|
| Declared masks + reconciliation flag | Also flag products whose ingredients text names an allergen absent from the mask; `IngredientMarkupParser` already exists | ✓ |
| Declared masks only | Honest about its source, but leaves the hand-typed-integer gap fully open | |
| You decide | Measure mask-vs-ingredients divergence on real data first | |

**User's choice:** Declared masks + reconciliation flag.

### Q4 — How does the conflict surface on the KDS?

| Option | Description | Selected |
|--------|-------------|----------|
| Order-level banner + per-item badge | Banner cannot be missed under time pressure; badge identifies the item | ✓ |
| Order-level banner only | Staff must re-derive which item caused it | |
| Per-item badge only | Precise but easily missed on a long ticket during a rush | |

**User's choice:** Order-level banner + per-item badge.

### Q5 (coherence check) — With no profile read, what is being warned about?

Raised because Q1 (no profile read) and Q2 (warn on conflict) need a shared subject, or the
planner would have had to guess what "conflict" means.

| Option | Description | Selected |
|--------|-------------|----------|
| The order's own allergen set | Checkout shows the aggregated set; customer acknowledges review. No health data read, stored or inferred | ✓ |
| A transient allergen selection at checkout | Would still be processing health data at the moment of comparison — the declined limb via another door | |
| Vendor-side data conflicts only | Cleanest legally, but the consumer sees nothing new at checkout | |

**User's choice:** The order's own allergen set.
**Notes:** This resolves Q1/Q2 into a coherent design — real consumer safety value with zero
special-category processing.

---

## Cookie and consent scope (LGL-01)

**Framing measurement:** no analytics, tag manager, or third-party scripts anywhere in
`frontend/app`, `frontend/components`, `frontend/lib`. Only strictly-necessary auth cookies, which
need no consent under UK PECR.

### Q1 — Given zero non-essential cookies, what ships?

| Option | Description | Selected |
|--------|-------------|----------|
| Consent store, dormant + notice | Policy page + essential-cookies notice + consent store/gating API with zero categories, so future scripts must pass the gate | ✓ |
| Essential-only notice, no consent gate | Matches today's reality exactly; nothing stops a future analytics addition | |
| Full categorised banner now | Reads "complete" to procurement, but consents to nothing and trains a dismiss reflex | |

**User's choice:** Consent store, dormant + notice.
**Notes:** Flagged in CONTEXT.md that a gate over zero categories is unfalsifiable as shipped and
must be proven with a fixture category.

### Q2 — Where do the policy pages live?

| Option | Description | Selected |
|--------|-------------|----------|
| Nest under `/legal` | `/legal` becomes an index; `/legal/privacy`, `/legal/cookies`, `/legal/retention`. Reuses `PublicShell` + canonical metadata | ✓ |
| Top-level `/privacy` and `/cookies` | Conventional URLs, but a second top-level surface and `/legal` orphaned | |
| One long `/legal` page with anchors | Simplest, but these are separately citable documents | |

**User's choice:** Nest under `/legal`.

### Q3 — How specific is the retention policy?

| Option | Description | Selected |
|--------|-------------|----------|
| Published per-category schedule | Category → period → lawful basis; what procurement actually asks for | ✓ |
| Public summary, internal detail | Less to get wrong publicly; does not satisfy a reviewer asking for the schedule | |
| You decide | Derive from measured enforcement first | |

**User's choice:** Published per-category schedule.

### Q4 — Tie published periods to enforcement with an executable check?

| Option | Description | Selected |
|--------|-------------|----------|
| Yes — a `check-*.sh` gate | Fails when a published period has no enforcement; matches repo doctrine | ✓ |
| No — prose this phase, gate later | Faster, but creates the exact drift the docs-freshness gates exist to catch | |
| You decide | Some periods may be operational-only and ungateable | |

**User's choice:** Yes — a `check-*.sh` gate.
**Notes:** CONTEXT.md carries the `check-gate-enforcement.sh` default-deny sequencing warning.

---

## Accessibility target + gate placement (LGL-02)

**Framing measurement:** zero `axe`/`pa11y`/`lighthouse` in `frontend/package.json` (control:
`playwright` → 1), zero a11y references in any workflow. Trap raised up front: a zero-violation axe
result is often an artefact — this project already had a meaningless "0 button-name violations"
because the tables under test never mounted.

### Q1 — Which surfaces must reach WCAG 2.1 AA?

| Option | Description | Selected |
|--------|-------------|----------|
| Public storefront + auth flows | Landing, shop listing, shop page, product detail, checkout, sign-in/sign-up | ✓ |
| Storefront + vendor dashboard | More defensible, but the 2026-07-08 audit's "essentially absent" finding makes this unclosable | |
| Public storefront only | Excludes the two flows where a blocked user loses most | |

**User's choice:** Public storefront + auth flows.

### Q2 — Where does the automated check run?

| Option | Description | Selected |
|--------|-------------|----------|
| Both — jest-axe + @axe-core/playwright | Component speed plus real composed-page fidelity | ✓ |
| `@axe-core/playwright` only | Highest fidelity, but per-PR CI runs 2 of 126 specs | |
| `jest-axe` only | Fast, but isolation is what produced the misleading zero | |

**User's choice:** Both.
**Notes:** CI placement of the E2E half recorded as an open research question, since per-PR CI runs
only 2 of 126 Playwright tests.

### Q3 — What does the gate demand?

| Option | Description | Selected |
|--------|-------------|----------|
| Zero on a declared surface list | Achievable, falsifiable, no baseline file to rot | ✓ |
| Ratcheted baseline across all routes | Blesses existing violations as the standard | |
| Zero everywhere immediately | Likely unreachable in one phase; a gate that blocks everything gets disabled | |

**User's choice:** Zero on a declared surface list.

### Q4 — What does the conformance statement claim?

| Option | Description | Selected |
|--------|-------------|----------|
| Partial conformance, dated, with exceptions | Gov-style statement; the only claim the evidence supports | ✓ |
| Full AA, no exceptions | Overclaiming is itself Equality Act exposure | |
| You decide | Let gate results determine the claim | |

**User's choice:** Partial conformance, dated, with exceptions.

---

## Controller/processor split (LGL-01)

**Framing constraint raised before the question:** `erasure_records` (V42) is tenant-scoped and the
project has a recorded *no cross-tenant operator identity* constraint, so a consumer who ordered
from three vendors spans three tenants nothing today can action in one place.

### Q1 — Who is the GDPR controller for consumer order data?

| Option | Description | Selected |
|--------|-------------|----------|
| Joint controllers, platform is the desk | Platform sole controller for accounts; single point of contact for data-subject requests | ✓ |
| Vendor controls, platform processes | Conventional marketplace shape; consumer must approach each vendor | |
| Platform is sole controller | Simplest, but claims control over relationships vendors consider theirs | |

**User's choice:** Joint controllers, platform is the desk.

### Q2 — Whose privacy notice does the consumer see at checkout?

| Option | Description | Selected |
|--------|-------------|----------|
| Layered — platform notice + vendor identity | One notice for shared processing, vendor named at point of order | ✓ |
| Platform notice only | Hides who actually handles the order | |
| Per-vendor notices | Platform must police tenant-authored legal text; blocks onboarding | |

**User's choice:** Layered.

### Q3 — How should the phase treat the cross-tenant DSAR gap?

| Option | Description | Selected |
|--------|-------------|----------|
| Build the DSAR path in this phase | A published commitment backed by nothing is the fail-open shape this project keeps paying for | ✓ |
| Publish the policy, file the gap | Bounded, but publishes an unmeetable commitment | |
| Manual runbook, no new code | No identity exists that can run it end to end | |

**User's choice:** Build the DSAR path in this phase.

### Q4 — Is a vendor-facing DPA in scope?

| Option | Description | Selected |
|--------|-------------|----------|
| In scope — template only, not signature flow | Author the document; leave acceptance tracking to Phase 32 | ✓ |
| Out of scope — defer to Phase 32 | Leaves the controller split with no instrument behind it | |
| In scope, including acceptance tracking | Adds onboarding state machinery to a documents-and-a11y phase | |

**User's choice:** In scope — template only.

### Q5 (conflict resolution) — Reconciling the DSAR desk with "no cross-tenant operator identity"

Raised because Q1 + Q3 together appeared to require the identity this project refused twice
(recorded constraint; Phase 33 D-2 rejected it explicitly). Verified on the tree first that
`SystemPrincipal.asSystem` and the `uk.jtoye.core.gdpr` package both exist.

| Option | Description | Selected |
|--------|-------------|----------|
| Request intake + background fan-out under `asSystem` | No human holds cross-tenant read; honours `ShopAccessService:640`'s request-thread rule | ✓ |
| Overturn the constraint with an ADR | Reopens a decision refused twice; grants one identity read across every tenant | |
| Scope DSAR to platform-controlled data only | Contradicts the joint-controller position just chosen | |

**User's choice:** Request intake + background fan-out under `asSystem`.

---

## Claude's Discretion

None. No gray area was answered "you decide" — every decision was the owner's. Discretion remains
only over implementation mechanics (file layout, component naming, test structure).

## Deferred Ideas

- DPA acceptance tracking / e-signature at onboarding → Phase 32.
- Accessibility remediation of the authenticated vendor dashboard → a later phase grows the
  declared surface list.
- Consent categories for real analytics → arrives with whichever phase introduces the first script.
- The rest of #427 (ADR-0004 ingredient graph) → no phase home yet; LGL-03 is Wave 1 only.
