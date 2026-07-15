# Phase 19: Full-Frontend Experience Overhaul - Context

**Gathered:** 2026-07-11
**Status:** Ready for UI-SPEC / planning

<domain>
## Phase Boundary

Close the 15-item remediation backlog from the full-frontend UI audit (`.planning/phases/18-vendor-onboarding-first-slice/18-UI-REVIEW.md`, whole-app 42/72): public landing page + information architecture, responsive dashboard shell, kitchen/order product-name fix, checkout fulfilment + fee transparency, per-shop menus, comparator-grade polish. Backend fixes required by UX findings (OrderItem name snapshot, ProductRepository shop scoping, V45 fulfilment schema) are IN scope; new feature surfaces (admin approval queue, Stripe Connect) are OUT (tracked by #178/#102).

</domain>

<decisions>
## Implementation Decisions (USER — locked 2026-07-11)

### Landing page (`/`)
- **Split persona hero.** Above the fold: brand statement + two equal doors — "Order food near you" → shop directory, "Run your food business" → `/for-operators`. Shared public header (Shops, For operators, Sign in) + footer (track order, business-model guide, sign in). No blind redirect.

### Dashboard mobile navigation
- **Bottom tab bar + drawer.** 4 primary tabs (Dashboard, Orders, Products, Kitchen) in the bottom thumb zone; remaining routes behind a "More" drawer. Desktop keeps the existing 256px sidebar. Square/Toast operator pattern.

### Checkout fulfilment
- **Delivery + collection.** Checkout asks fulfilment type first; address form only for delivery. Fee breakdown (subtotal + delivery + VAT) visible BEFORE payment. Schema via **V45** (`fulfilment_type` + nullable address columns) — **V44 stays reserved for issue #96**.

### Standing user directives (from this session + durable feedback)
- "Maintain the highest standards possible and don't regress what we already have. The final outcome should be functional, and aesthetically impressive. Take cues from established similar organisations."
- Comparator bar: Deliveroo/Just Eat (storefront + public), Square/Toast/Shopify admin (dashboard).
- Palette LOCKED: existing orange/emerald/slate food-delivery scheme. A serif/editorial redesign was previously rejected and reverted (PR #49/#52) — do not reintroduce.
- Mobile-first. Production-grade, no "AI-looking" output. Playwright visual validation throughout; images verified rendering (naturalWidth > 0), not just markup.
- Phase 18 onboarding UI (`/dashboard/onboarding`) matched its design contract — treat as the internal quality reference; do not regress it.

### Claude's Discretion
- Landing hero art direction within the locked palette (photography vs illustration vs gradient treatment).
- Exact tab iconography, drawer contents ordering, breakpoint values.
- Whether `ProductRepository`'s `shopId IS NULL` fallback is removed or rendered as deliberate "tenant-wide items" — decide during planning from data reality (24/25 live products have NULL shop_id and need assignment either way).

</decisions>

<specifics>
## Specific Ideas

- Zero orphan routes: every route reachable via ≥1 inbound nav link, enforced by a link-graph test so IA cannot silently regress again.
- Kitchen/order fix is a data-integrity fix, not cosmetic: populate `OrderItem.productName` snapshot at order creation (`OrderItem.java:34` default must never render for an existing product).
- "Unknown Product", duplicated menus, and the missing address are all AUDIT BLOCKERS — they land before polish items.

</specifics>

<canonical_refs>
## Canonical References

- `.planning/phases/18-vendor-onboarding-first-slice/18-UI-REVIEW.md` — audit findings, per-surface scores, 15-item remediation backlog (the phase's source of truth)
- `docs/SITEMAP.md` — route inventory with audience classification
- `frontend/tailwind.config.ts`, `frontend/app/globals.css` — locked design tokens
- Screenshots: `/tmp/claude-1000/-home-sanmi-IdeaProjects-JToye-OaaS-2026/43b38d22-44cc-4260-a3b4-f7416e832cf1/scratchpad/ui-audit/` (49 PNGs, session-lived — re-capture if needed)

</canonical_refs>
