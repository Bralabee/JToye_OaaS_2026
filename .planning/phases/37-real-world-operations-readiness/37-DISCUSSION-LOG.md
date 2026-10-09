# Phase 37: Real-world operations readiness - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-10-05
**Phase:** 37-real-world-operations-readiness
**Areas discussed:** Phase shape & order, Staff access default, Kitchen order model, Checkout & cash abuse, Integrator credentials (added), Accessibility scope (raised as a consequence of the shape decision)

Every selection below was the option offered as recommended. No free-text answers were given.

---

## Phase shape & order

| Question | Options | Selected |
|---|---|---|
| Shape of 87 clusters | 37 = P0/P1 gate, P2/P3 → 37.x · One phase, seven waves · Split 37.A..37.G | 37 = P0/P1 gate ✓ |
| First sub-theme | 37-B staff access · 37-A kitchen · 37-D abuse | 37-B ✓ |
| PR shape | One PR per sub-theme · One PR for the phase | Per sub-theme ✓ |
| Ordering vs 31.1 | 37-C waits for 31.1 only · All of 37 after 31.1 · No constraint | 37-C waits ✓ |

## Staff access default

| Question | Options | Selected |
|---|---|---|
| Ungranted login | No access, strict-scoping ON by default · Delete the JIT path · Per-tenant switch | Strict-scoping ON ✓ |
| Onboarding | Email invite carries grant · Self-register then approve · Owner creates account | Email invite ✓ |
| Last grant revoked | Access ends next request · Also end Keycloak session | Next request ✓ |
| Staff page | List all incl. "No access" · Only granted | List all ✓ |
| Completed-order delete (UXT-018) | Only DRAFT/PENDING deletable, else void · Never delete | DRAFT/PENDING + void ✓ |

## Kitchen order model

| Question | Options | Selected |
|---|---|---|
| Arrival | New lane + accept on board · Auto-confirm · Per-shop setting | New lane ✓ |
| Alert | Repeating sound + flash until accepted · Single chime · Plus Web Push | Repeating ✓ |
| MCP orders | Enter as PENDING · Stay DRAFT + submit_order tool | PENDING ✓ |
| Session lapse | Loud board-stopped + return to Kitchen · Also lengthen SSO | Board-stopped ✓ |
| Pause (UXT-022) | One-tap pause + structured hours · Pause only | Pause + structured hours ✓ |

## Checkout & cash abuse

| Question | Options | Selected |
|---|---|---|
| Re-validation | Client sends shown prices, typed 409 diff · Server quote id · Refresh on load | Typed 409 diff ✓ |
| Promo (UXT-008) | Remove claim · Apply server-side | Remove claim ✓ |
| Cash abuse | Per-identity caps + unverified flag · Email verification · Both | Caps + flag ✓ |
| Client IP | Trusted-proxy CIDRs, default none · Ignore XFF | Trusted proxies ✓ |

## Integrator credentials

| Question | Options | Selected |
|---|---|---|
| Mechanism | Keycloak service-account client · Platform API keys in Postgres | Keycloak client ✓ |
| Shop scope | Bind via shop_staff · Tenant-wide only | shop_staff ✓ |
| Who issues | Group admin + audit · Any shop manager | Group admin ✓ |

## Accessibility scope

All six 37-F clusters are P2, so the shape rule would have moved them to 37.x. Asked separately.

| Options | Selected |
|---|---|
| Keep 37-F in Phase 37 · Follow the rule into 37.x | Keep ✓ |

## Claude's Discretion

The fix shape for the in-scope clusters not discussed: UXT-016, 017, 023, 026, 027, 028, 029, 037, 038, 040, 044, 046, 047, and 37-F. Also the config keys and values for the caps and limits, and the invite lifetime.

## Deferred Ideas

- 37.x: 50 P2/P3 clusters plus UXT-120/121.
- A promotions engine.
- Web Push.
- Keycloak logout on revoke.
- Longer shift SSO.
- Per-tenant strict-scoping.
- Email verification for cash orders.
