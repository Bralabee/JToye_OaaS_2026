# Phase 23: Vendor-Scoped Access + Responsive Dashboard Nav - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-07-15
**Phase:** 23-Vendor-Scoped Access + Responsive Dashboard Nav
**Areas discussed:** Read-visibility model, Enforcement mechanism, Switcher UX + persistence, Grant lifecycle (backfill + revocation)

*Requirements were pre-locked by `.planning/specs/shop-scoped-access-SPEC.md`; discussion covered only the "defaults to confirm at discuss-phase" HOW decisions. User selected all four gray areas and confirmed the recommended option in every case.*

---

## Read-visibility model → D-01

| Option | Description | Selected |
|--------|-------------|----------|
| Scope reads too | Switcher + list endpoints show only granted shops; GROUP_ADMIN sees all + "All shops". Defense-in-depth; more code (list filtering). | ✓ |
| Gate writes only | Reads stay tenant-wide; only writes blocked. Spec's literal wording; less code but leaks unmanageable shops into the switcher/lists. | |

**User's choice:** Scope reads too (Recommended).
**Notes:** Refines the spec's "deny-by-default for writes" — reads are now scoped as well, so the switcher structurally cannot select a shop the user can't act on.

---

## Enforcement mechanism → D-02

| Option | Description | Selected |
|--------|-------------|----------|
| Explicit service-layer call | `shopAccessService.require(shopId, minRole)` at top of shop-scoped service methods; flexible when shopId is in a body/parent entity. | ✓ |
| Custom @RequireShopRole annotation | SpEL via already-active @EnableMethodSecurity; clean for path vars, awkward for body-derived shopId. | |
| Hybrid | Annotation for path vars, explicit call otherwise; most precise, two mechanisms. | |

**User's choice:** Explicit service-layer call (Recommended).
**Notes:** shopId often isn't a clean method arg (e.g. product → shop lookup), which annotations handle awkwardly.

---

## Grant lifecycle — Backfill → D-04

| Option | Description | Selected |
|--------|-------------|----------|
| JIT lazy-provision | First request from a tenant user with no shop_staff row auto-creates a GROUP_ADMIN row; realm admin ⇒ implicit GROUP_ADMIN. Fail-closed, no KC coupling. | ✓ |
| Keycloak admin-API sweep | Migration enumerates all realm users. Complete but couples to live KC + KC24 attribute trap. | |
| Implicit-only (no rows) | No row = GROUP_ADMIN. Zero backfill but fail-OPEN — risky for an auth boundary. | |

**User's choice:** JIT lazy-provision (Recommended).
**Notes:** No local users table to enumerate; JIT is fail-closed and preserves day-one behaviour exactly without touching Keycloak at migrate time.

---

## Grant lifecycle — Revocation freshness → D-05

| Option | Description | Selected |
|--------|-------------|----------|
| Immediate: evict on write | grant/revoke evicts the user's membership cache (reuse TenantCacheEvictor); next request re-resolves. No stale window. | ✓ |
| Short TTL only (~60s) | Simpler, no eviction wiring, but a revoked staffer keeps access up to the TTL. | |
| No cache (per request) | Always fresh, one indexed lookup per call. | |

**User's choice:** Immediate: evict on write (Recommended).
**Notes:** It's an auth boundary — no stale-access window acceptable. Short TTL kept as a backstop.

---

## Switcher UX — Placement + default → D-06

| Option | Description | Selected |
|--------|-------------|----------|
| Sidebar header, default "All shops" | Dropdown under the logo in sidebar.tsx; GROUP_ADMIN lands on "All shops" (zero behaviour change); rides existing mobile nav. | ✓ |
| Top app-bar, default first shop | Persistent top-bar switcher; forces single-shop context; needs a new top-bar slot. | |
| Sidebar header, default first shop | Sidebar placement but single-shop default; day-one behaviour change for admins. | |

**User's choice:** Sidebar header, default "All shops" (Recommended).

---

## Switcher UX — Persistence → D-07

| Option | Description | Selected |
|--------|-------------|----------|
| localStorage | Client-only, instant, mirrors existing theme-toggle persistence; server re-validates grants regardless. | ✓ |
| Server-side user preference | Survives device switches; needs a new preferences store/endpoint (over-scoped). | |
| URL query param (?shop=) | Shareable/deep-linkable; URL clutter, easily lost, complicates All-shops routing. | |

**User's choice:** localStorage (Recommended).

---

## Switcher UX — "Apply to all shops" affordance → D-08

| Option | Description | Selected |
|--------|-------------|----------|
| Only via "All shops" context | Group-wide writes available only when switcher is on "All shops"; explicit + GROUP_ADMIN-gated. | ✓ |
| Explicit per-mutation toggle | Per-form "apply to all" checkbox; more granular, more form state. | |
| Both (context + override) | Most flexible, most surface to build/QA — over-scoped. | |

**User's choice:** Only via "All shops" context (Recommended).

---

## Claude's Discretion

- **MOBL-01 is verify-first.** The sidebar is already `hidden md:flex` (not overlaying) and the
  nav array already feeds a mobile "More" sheet (`feature/ux-mobile-nav-rsc-fixes` seed). Research
  must verify the real 375px state in the browser before assuming a fix is needed; likely task is
  integrating the switcher into the existing mobile nav, not building a new drawer.
- `shop_staff` column types/index names, cache key shape, and the `ShopAccessService` API surface
  are planner/executor discretion within D-01..D-08 and the spec schema.

## Deferred Ideas

- Department tier (Vendor → Department → Shop) — future organizational layer, not v2.3.
- Self-serve user invitation / account creation — stays in Keycloak admin.
- Server-side (cross-device) switcher preference — deferred; revisit on per-device-drift reports.
- Fine-grained per-capability permissions beyond the three roles — out of scope.

---

# Reconciliation session (2026-07-15) — additive VSA-04 depth + refinements

> A parallel discuss-phase session (committed as `c13acc9` above) ran concurrently and decomposed the
> phase into four areas (read-visibility / enforcement / switcher / grant-lifecycle). This second
> session, with the same user, decomposed differently and went deep on **staff management (VSA-04)**
> — which the committed log left thin — plus two refinements. User chose the recommended option in
> every case. Decisions folded into CONTEXT.md as **D-09..D-13** (D-01..D-08 above unchanged).

**Areas discussed:** Switcher behaviour · Access boundary & grandfathering · Staff management UX · Responsive nav (MOBL-01)

## Staff-grant target (VSA-04) → D-09
| Option | Selected |
|--------|----------|
| Login-populated user directory (sub/email/name from JWT; RLS, no `_aud`) | ✓ |
| Keycloak-admin email search (fragile infra + KC24 trap) | |
| Raw Keycloak-sub entry (hostile UX) | |

## Staff screen placement (VSA-04) → D-10
| Option | Selected |
|--------|----------|
| GROUP_ADMIN-only nav item, mirroring Approvals | ✓ |
| Under a (non-existent) Settings area | |

## Last-GROUP_ADMIN lockout guard → D-11
| Option | Selected |
|--------|----------|
| Guard the last GROUP_ADMIN (RFC 7807 409) + self-downgrade warning | ✓ |
| No guard (rely on realm-admin backstop) | |

## Grandfathering off-ramp → D-12 (refines committed D-04)
| Option | Selected |
|--------|----------|
| Implicit-admin + lazy grandfather behind a config strict-scoping switch (default OFF) | ✓ |
| Implicit-admin only, strict from day one | |
| Bootstrap-enumerate from Keycloak | |

## Out-of-scope-shop UX → D-13 (refines committed typed-403)
| Option | Selected |
|--------|----------|
| In-page access-required state (reuse Approvals/Finance pattern) | ✓ |
| Redirect to default shop | |

## MOBL-01 → confirmed verify-first (agrees with committed discretion)
Sidebar already `hidden md:flex`; MobileTabBar already ships. Scope = 375px regression proof +
switcher integration into the existing mobile top bar; NO drawer rebuild.
