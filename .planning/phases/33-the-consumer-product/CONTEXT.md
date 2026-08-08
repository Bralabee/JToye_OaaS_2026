# Phase 33 — The Consumer Product — CONTEXT

**Created 2026-08-08**, ahead of planning. Decisions below were made by the owner in session and are
binding on the plan. Measurements below were taken on the tree the same day and are recorded in
`.planning/CRITERIA-DECAY-2026-08-08.md`.

> **Read that audit before planning.** The roadmap's Phase 33 criteria were written 2026-08-01 and
> two of them changed underneath. `ROADMAP.md` now carries the corrections inline at the phase.

---

## Locked decisions

### D-1 — Shop coordinates come from the postcode, via ONS/OS open data

Shops already store full UK addresses with postcodes (`48 Rye Lane, Peckham, London SE15 5BS`).
Coordinates derive from the postcode using ONS Postcode Directory / OS Open Names — **open data, no
API key, no per-call cost, no new vendor**.

*Why not a geocoding API:* this roadmap is already blocked on five commercial decisions (production
domain, hosting target, Stripe keys, ADR-0002 sign-off, WhatsApp Business account). A sixth would
gate the substrate the rest of the phase sits on.

*Why not manual entry:* food vendors do not know their coordinates, a dropped minus sign is
undetectable without a validation map, and it contradicts the platform's own "go live in a day"
promise.

*Accepted trade-off:* postcode-centroid accuracy (~100 m), not door-level, and UK-only. Both are
acceptable for "shops near me" ranking on a UK-only platform. **State this limit in the plan** — do
not let a later reader assume door-level precision.

**Open research item for the planner/researcher:** which specific dataset and licence (ONSPD vs OS
Open Names vs OS Code-Point Open), how it ships (bundled table, migration seed, or build-time
fetch), and its update cadence. This is the one genuinely unresearched part of D-1.

### D-2 — #453 is carved out as a decision ticket, not built

`MANUAL_REVIEW` reaching nobody intersects the recorded **no-platform-operator** constraint: there is
no cross-tenant operator identity, so a stalled onboarding notifies nobody.

The roadmap's SC-3 has two limbs — *appears on a surface a human can act from* **or** *a recorded
decision states who adjudicates it* — and explicitly says the criterion **fails if the phase ships
code without settling it**. So the phase ships **no code** for #453 and satisfies the second limb
with a recorded decision.

Rejected in session: routing to the tenant's own `GROUP_ADMIN` (the reviewed party becomes the
reviewer, degrading `BUSINESS_VERIFIED` / `FOOD_HYGIENE_RATING` to self-attestation, which has
consumer-safety implications on a food platform), and creating a cross-tenant operator identity
(contradicts the recorded architecture constraint, and a new cross-tenant RLS surface is this repo's
highest-risk change class — realistically its own phase).

### D-3 — Scope is the P1 substrate plus the visible lie

| | issue | why |
|---|---|---|
| **IN** | **#460** | populate coordinates, then locality/radius a customer can observe |
| **IN** | **#544** | "Cooking near you" resolves to real published shops |
| **IN** | **#432** | customer-realm identity providers, **or** a dated deliberate decision to skip |
| OUT | #453 | decision ticket — D-2 |
| OUT | #458 | its nav half already shipped; only the dispatch half is open — own slice |
| OUT | #452, #545, #546, #285 | later phase; #545/#546/#285 are SC-6 and were never measured |

Roughly three plans. The test is that the slice is falsifiable end to end: a customer can be shown
real shops ordered by real distance.

---

## Measured state the plan must build on

**#460 is a five-link chain and the roadmap names only the last three.** Do not plan it as "read the
coordinates".

| link | state | coordinate |
|---|---|---|
| 1 column exists | yes | `V16__public_storefront.sql:15-16` (`shops`), `:92-93` (`shops_aud`) |
| 2 entity ready | yes | `Shop.java:53-55`, getters/setters `:113-116` |
| 3 **populated** | **NO** | `DemoDataSeeder.upsertShop` (`:508-510`) takes no coordinate parameters; the seeder never calls `setLatitude`/`setLongitude`. **Every seeded shop has NULL coordinates** |
| 4 read | pass-through only | `PublicStorefrontService.java:720-721` — set on the DTO, never queried, sorted or filtered |
| 5 used | **NO** | zero distance/radius logic backend-wide; zero device geolocation frontend-wide |

**Link 3 is load-bearing for falsifiability.** A ranking feature over NULL coordinates returns
nothing before *and* nothing after, so it cannot be shown to fail. **Populate first, and prove the
population**, or every downstream criterion in this phase is vacuous.

**#544's fix has real data waiting.** `DemoDataSeeder` creates three real shops the row never shows —
`Mama Ade's Kitchen`, `Peckham Jollof Co.`, `Brixton Village Grill` (`:249`, `:254`, `:259`), with
real products. The landing page instead hardcodes five invented vendors at `page.tsx:52-56`
(`featuredDishes`, mapped at `:192` under the `:180` heading), one of which — *Mama's Kitchen* — is a
near-duplicate of a real seeded shop.

The defect is not missing data. It is invented data shown **instead of** real data one query away.

**Sequence #460 population → #544.** #544 cannot resolve to "real published shops near you" until
shops have coordinates.

---

## Falsifiability requirements

This phase is the one most exposed to this repo's recorded traps. Binding on every plan:

- **Every acceptance criterion must be shown to FAIL first**, against a deliberately broken input,
  with both directions' real output recorded. The audit that preceded this phase found two roadmap
  criteria that could no longer fail; do not add more.
- **#544's check must fail against a reintroduced hardcoded list** — this is the roadmap's own
  requirement and it is the difference between "the row renders" and "the row is true".
- **#460's check must fail against NULL coordinates** — the pre-population state is the control arm,
  and it exists on the tree today, so capture it before populating.
- **A screenshot cannot verify motion**, and a screenshot taken without scrolling reads scroll-reveal
  content as empty bands. The landing page has a horizontal dish scroller and reveal animations.
- **The delivered runtime must match the branch.** `docker compose start` does not rebuild. Prove
  parity by content, not by HTTP 200.
- **Beware the streaming staging buffer**: `<div hidden id="S:n">` holds a second copy of the shell
  for ~300 ms. `getByTitle`/`getByTestId` see it, `getByRole` does not. This has been filed as a
  product bug twice (#556, #593) when it was a race.

## Out of scope

Fixing #453, #452, #545, #546, #285, or #458's dispatch half. Re-planning #458's nav half, which
shipped in `b9f80f81` (#508) and `96d8432f` (#591).

## Requirements

CUST-01, CUST-02, CUST-03, CUST-04 — see `.planning/REQUIREMENTS.md`. CUST-02's #453 half is
satisfied by D-2's recorded decision rather than by code.
