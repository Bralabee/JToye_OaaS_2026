# J'Toye OaaS — Progression Plan

**Date:** 2026-04-21
**Branch at time of writing:** `main` (clean, all in-session work for PR #54 already merged)
**Inputs that produced this plan:**

- [`docs/planning/PLATFORM-STATE-AND-POSITIONING-2026-04-19.md`](./PLATFORM-STATE-AND-POSITIONING-2026-04-19.md) — competitive positioning and gap analysis (vendor-direct peer class, 70–80% parity).
- [`.planning/ROADMAP.md`](../../.planning/ROADMAP.md) — v2.2 milestone in progress.
- [`HANDOFF.md`](../../HANDOFF.md) — prior-session handoff, Phase 17 scoped and research-complete.
- [`.planning/PROJECT.md`](../../.planning/PROJECT.md) — milestone scope of record.

**Question this document answers:** Given what the platform is, what the positioning doc found, and what's already in flight, how should work progress?

**Verdict in one sentence:** Finish v2.2, ship a v2.3 that closes push/SMS + scheduled orders + tips, land one real vendor pilot, and only then decide whether v2.4 is more parity features or the first touch of a marketplace layer — because doing it in that order is what makes each subsequent decision evidence-backed rather than speculative.

---

## Wave 1 — Close v2.2 (next 2 weeks)

Finish what's already started before opening anything new.

1. **Phase 17** — vendor order detail + Stripe refund flow + refund state in the machine + V35 migration + RabbitMQ `order.refunded` event. This is the only unbuilt v2.2 phase. Research lives in PR #51 and the HANDOFF.md. Run `/gsd-plan-phase 17` on a fresh feature branch.
2. **Phase 12 Task 07** — the manual Spring Security response-headers cutover gate.
3. **Phase 15** — cluster-admin rollout of NetworkPolicies + Sealed Secrets. Four-step checklist already written in `.planning/phases/15-*/15-01-SUMMARY.md`.
4. **Docs-freshness pass** — update `CLAUDE.md` so its test counts (390 → 439, 48 → 60, 13 → 20, 5 → 6) and schema head (V33 → V34; V35 when Phase 17 ships) reflect reality. Five-minute edit with real consequence: stops future agents and operators from reasoning on stale numbers.
5. Run `/gsd-complete-milestone` to archive v2.2 and tag `v2.2` once Phase 17 ships.

**Exit criterion:** `v2.2` tag cut, ROADMAP shows all six phases checked, CLAUDE.md reflects reality.

## Wave 2 — v2.3 "Vendor Parity I" (6–10 weeks)

Pick the three gap items that are highest customer-value and lowest architectural risk. These are all additive, isolated, and build on patterns already in the codebase. Each corresponds to a gap surfaced in §4.1 of the positioning doc.

### 2.1 Multi-channel notifications

**Why:** Email-only is the most visible parity gap vs Flipdish/Slerp.

**What:** Extend `core-java/src/main/java/uk/jtoye/core/notification/` beyond `EmailNotificationService.java` to add push (Firebase Cloud Messaging + APNs) and SMS (Twilio). Backfill branches into `order/OrderStateChangeListener.java` — the listener pattern is already shaped for this, so the change is mechanical additions, not a restructure.

**Scope signals:**

- Per-customer channel preferences on the `Customer` entity.
- Per-tenant sender identities (shop-branded sender IDs where the carrier allows).
- Delivery receipts + retry logic, probably reusing the payment outbox pattern from `payment/PaymentEventOutbox.java`.
- GDPR surface (`gdpr/GdprController.java`) extended to cover push-token deletion on erasure requests.

**Typical build:** 4–6 weeks.

### 2.2 Scheduled / pre-orders

**Why:** Customers expect to pre-order; vendors need a way to flatten demand peaks.

**What:** Add a `scheduledFor` timestamp to `Order`. A separate KDS lane for future tickets. A cron-driven promoter that moves scheduled orders into the live queue at the right moment.

**Scope signals:**

- No state-machine changes needed — a scheduled `DRAFT` simply remains `DRAFT` until the promoter submits it.
- New `scheduled_orders` filter on the KDS subscription so kitchens can browse the upcoming-day queue without clutter.
- Stock reservation question: reserve at schedule time or at promotion time? The v2.2 Phase 14 work (optimistic-lock stock race fix at `CONFIRM`) is the relevant reference — same pattern, at promotion.

**Typical build:** 3–5 weeks.

### 2.3 Tips / gratuities

**Why:** Standard food-service expectation; direct vendor revenue.

**What:** Optional tip line item at checkout; adjust the Stripe payment intent; route tip portion to the vendor's ledger as a distinct `FinancialTransaction` type.

**Typical build:** 1–2 weeks.

**Wave 2 total:** realistically an **8-week milestone** (three items in overlapping streams). Closes three of the eight vendor-parity gaps catalogued in the positioning doc.

## Wave 3 — v2.4 "Vendor Parity II" (3–4 months out)

Larger pieces that are each real work. Gate Wave 3 on **at least one live vendor customer** using what's shipped in Wave 2. Building multi-outlet before a single-outlet customer uses it is a reliable way to over-abstract.

### 3.1 Mobile app shell via Capacitor

**Why:** Native app presence is a category expectation and a credibility signal. A Capacitor wrapper over the existing Next.js storefront is the low-risk path.

**What:** Capacitor project wrapping `/home/sanmi/IdeaProjects/JToye_OaaS_2026/frontend/` with per-vendor white-label build pipeline (app name, icon, splash, deep-link scheme). Push notifications integrate cleanly with Wave 2.1 work (FCM/APNs already wired).

**Typical build:** 8–12 weeks for a thin, honest shell. Substantially more if native-feeling navigation or offline mode becomes a hard requirement.

### 3.2 Multi-outlet / franchise

**Why:** The single biggest scale-shape gap. Chains cannot currently be modelled; one `Shop` per `Tenant` is the only tested shape.

**What:** Parent `Organisation` entity. `Shop.organisation_id` FK. Shared product catalog with per-outlet overrides. Admin roles scoped at organisation vs outlet level.

**Warning:** This is structurally more invasive than anything in Wave 2. It touches the schema, the permission model, and the dashboard IA. Run `/gsd-discuss-phase` before planning — do not dive straight to `/gsd-plan-phase`.

**Typical build:** 6–10 weeks.

### 3.3 Loyalty + referrals

**Why:** Retention surface. Flipdish leads the UK market on this specifically.

**What:** New `loyalty/` package alongside `shop/`. Points accrual rules, redemption integrated into the promotion engine (`shop/PromotionService.java`). Referral codes are a low-complexity extension of the same engine.

**Typical build:** 4–8 weeks for the loyalty core; 1–2 weeks for referrals.

## Wave 4 — Strategic branching point (6+ months out)

After two parity waves ship, the strategic question the positioning doc framed becomes actionable:

- **Path A continuation (default).** Compete heads-up with Flipdish and Slerp on the UK vendor-direct market. Invest in sales motion, not more features. Lowest regret, highest chance of real revenue within 12 months of focused work.
- **Path C overlay.** Add an opt-in "J'Toye Marketplace" surface on top of `storefront/PublicStorefrontController.java`: cross-vendor browse, consumer account, third-party courier integration (Stuart, Uber Direct) rather than own fleet. Defer indefinitely if vendor-direct sales are traction-positive; or start small with a single-city marketplace pilot if there's a specific geography to anchor.
- **Path B (aggregator pivot).** Only if capitalised for £10M+ and committed to a 2–4-year programme. The positioning doc is explicit that half-building this is worse than not building it.

## Cross-cutting housekeeping

Independent of wave sequencing. These are low-effort, high-compounding.

### CH-1 — Git-init the learning site

`/home/sanmi/Documents/J'TOYE_DIGITAL/OaaS_Learning_Site` is unversioned. Not urgent, but one `git init` + first commit away from being a shareable companion artefact and a CI-able site. Consider publishing to `learn.jtoye.digital` when direction is signed off.

### CH-2 — Keep planning docs refreshed at milestone boundaries

Run a doc-freshness audit at the **end** of every milestone cycle, not when docs feel wrong. The current `CLAUDE.md` drift from reality (test counts, schema head) is a symptom of audit-on-symptom rather than audit-on-schedule. Add it to the `/gsd-complete-milestone` ritual.

### CH-3 — Protect the architectural strengths

The five-layer tenant defence (`JwtTenantFilter` + `TenantContext` + `TenantSetLocalAspect` + RLS + `TenantContextCleanupFilter`), the formal state machine, the payment outbox pattern, and Envers audit are the specific things that make this platform better than category-typical. Any "quick fix" that erodes one of them should require explicit architectural review, not a passing PR.

### CH-4 — Use the existing GSD discipline

Run each phase through `/gsd-discuss-phase` → `/gsd-plan-phase` → `/gsd-execute-phase` → `/gsd-verify-work` → `/gsd-ship`. The positioning doc's "engineering rigor is above category-typical" finding is a direct product of this discipline. Skipping it for speed trades against the very thing the comparison ranks you highly on.

## Decision summary

| Horizon | Milestone | Scope | Effort | Gate |
|---|---|---|---|---|
| 0–2 weeks | Close v2.2 | Phase 17 + manual cutovers + docs refresh + tag | ~2 weeks | Phase 17 ships |
| 6–10 weeks | v2.3 Vendor Parity I | Push/SMS + scheduled orders + tips | ~8 weeks | v2.2 tag cut |
| 3–4 months | v2.4 Vendor Parity II | Mobile shell + multi-outlet + loyalty | ~4 months | ≥1 live vendor pilot on v2.3 |
| 6+ months | Strategic branch | Path A continuation, Path C overlay, or Path B (caveat) | — | v2.4 ships + traction data |

## Not doing (and why)

- **Courier/dispatch domain.** Out of category scope. Only enter via a third-party courier integration layer (Stuart/Uber Direct) if Path C is chosen, not via a first-party build.
- **A/B experimentation platform.** Premature; no customer volume yet to run meaningful experiments against.
- **Native consumer apps (as opposed to white-label vendor apps).** Only under Path B.
- **Fraud ML.** Premature; Stripe Radar + existing outbox + Envers audit covers category-typical risk until volume justifies a dedicated model.
- **Multi-currency.** Only when there's a non-UK vendor pipeline, not as a hypothetical future-proof.
