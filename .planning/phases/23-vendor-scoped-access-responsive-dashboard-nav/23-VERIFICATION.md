---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
verified: 2026-07-21T13:05:00Z
status: human_needed
score: 5/5 roadmap success criteria verified (automated proof); 1 human-verification item outstanding (live 375px/vendor-authenticated Playwright run)
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 3/5 roadmap success criteria verified (VSA-01, VSA-03, MOBL-01 pass; VSA-02, VSA-04 fail)
  gaps_closed:
    - "VSA-02: @Cacheable cache-hit bypass on ShopService.getShopById / ProductService.getProductById — require() ran only on cache miss (23-10)"
    - "VSA-02: STOMP kitchen-topic subscription checked only the tenant segment, not the shop segment — any tenant user could subscribe to any shop's KDS feed (23-11)"
    - "VSA-04: isSystemPrincipal() fail-opened any non-UUID-subject authenticated JWT to unrestricted GROUP_ADMIN on /api/v1/staff, with no independent backstop on StaffController (23-08)"
  gaps_remaining: []
  regressions: []
human_verification:
  - test: "Live vendor-authenticated Playwright run of frontend/e2e/dashboard-mobile.spec.ts, both the 390px full-route sweep and the '375px — MOBL-01 + switcher regression' describe block, plus a manual /dashboard/staff grant/revoke click-through at 375px and desktop widths"
    expected: "No horizontal overflow at 375px, sidebar hidden, tab bar + shop-context switcher visible and reachable (docScrollWidth <= viewportWidth+1, mainWidth >= 300); staff screen renders the directory/grant/revoke UI correctly and a grant/revoke round-trip behaves as coded (immediate unlock / immediate 403 on next request)"
    why_human: "The Playwright spec requires a real Keycloak SSO login (E2E_VENDOR_PASSWORD); that credential and a rebuilt frontend container serving the current code were not available in this verification session. The Jest-level 375px assertion (dashboard-shell.test.tsx) passes but only checks Tailwind responsive classes (md:hidden, fixed) in jsdom — it does not measure actual viewport geometry (scrollWidth/overflow), which only the Playwright spec does. Per explicit instruction for this verification: an unexecuted Playwright spec is a human_verification item, not an automated pass, even though its Jest proxy is green."
---

# Phase 23: Vendor-Scoped Access + Responsive Dashboard Nav Verification Report

**Phase Goal:** A vendor group can scope staff to individual shops — a shop manager only touches their shop while RLS stays the tenant wall — and the dashboard nav (carrying the shop-context switcher) works on a phone. Incremental Betterment: every existing tenant user is backfilled to GROUP_ADMIN so day-one behaviour is identical.
**Verified:** 2026-07-21
**Status:** human_needed
**Re-verification:** Yes — after gap-closure wave (plans 23-08..23-16), following a prior `gaps_found` verification dated 2026-07-20

## Re-Verification Summary

The prior verification (2026-07-20) found three concrete authorization bypasses that defeated VSA-02 and VSA-04 despite all surrounding artifacts being present and wired at the source level. This re-verification re-read every one of those exact code locations against the current HEAD (clean working tree, all gap-closure plans 23-08..23-16 committed) and **independently executed the proof suites fresh in this session** (not relying on SUMMARY.md claims or stale/cached Gradle results). All three gaps are closed with sound fixes; no regressions were found in the previously-passing criteria (VSA-01, VSA-03, MOBL-01).

## Goal Achievement

### Roadmap Success Criteria

| # | Truth (roadmap SC) | Status | Evidence |
|---|------|--------|----------|
| 1 | VSA-01: shop_staff mapping table, RLS-proven under NOSUPERUSER, realm-admin implicit GROUP_ADMIN, zero day-one regression | ✓ VERIFIED (regression check — unchanged since prior pass) | `V52__shop_staff.sql` — ENABLE+FORCE RLS on all 3 tables, routed through the safe `current_tenant_id()` helper, zero raw `::uuid` casts (confirmed by re-reading the file). `RlsContractTest` is a generic `pg_policy`/`pg_class` sweep (not table-enumerated), so it auto-covers `shop_staff`/`shop_staff_aud`/`user_directory`. Fresh execution this session: `ShopStaffRlsPolicyIntegrationTest` 3/3 green (13:00:38), `ShopAccessJitProvisionTest` 4/4 green (13:00:32) — includes `jitProvisionIsIdempotentUnderConcurrentFirstRequests` and `realmAdminIsImplicitGroupAdminWithoutAnyRow`. |
| 2 | VSA-02: application-layer enforcement, deny-by-default for shop-scoped reads/writes, distinct 403 | ✓ VERIFIED (previously FAILED — now closed) | **Cache-bypass fix (CR-01, plan 23-10):** `ShopService.getShopById`/`ProductService.getProductById` now call `shopAccessService.require(...)` directly in the service method, on *every* invocation, and only then delegate to a separate `@Component` cache-loader bean (`ShopCacheLoader`/`ProductCacheLoader`) for the actual cached fetch — confirmed by reading the current source (ShopService.java:104-111, 348-369; ProductService.java:118-135, 377-405). The gate can no longer be short-circuited by a cache hit. `ShopAccessCacheBypassIntegrationTest` proves this with two genuinely different scoped users (userX on shop A populates the cache; userY on shop B is denied reading the same warm cache entry) — fresh run this session: 5/5 green (13:57:38), including `warmCacheDoesNotBypassShopGate`, `warmCacheDoesNotBypassProductGate`, and `authorizedCallerStillServedFromCache` (proves the fix didn't break the caching performance good). **STOMP fix (CR-02, plan 23-11):** `TenantChannelInterceptor.validateSubscription` now parses the kitchen topic's shop segment (`parts[4]`) and calls the new explicit-identity `ShopAccessService.canAccessShop(tenantId, userId, realmAdmin, shopId)` before permitting a kitchen SUBSCRIBE — confirmed by reading the current source (lines 147-216). `TenantChannelInterceptorTest` fresh run: 28/28 green, including `shouldRejectSubscriptionToUngrantedShop`/`shouldAllowSubscriptionToGrantedShop`/`shouldRejectMalformedShopSegment`. `ShopAccessEnforcementIntegrationTest` (cross-shop 403, STAFF read-only, read-scope narrowing, 403≠404 type) fresh run: 12/12 green (13:00:13). |
| 3 | VSA-03: persisted shop-context switcher, all shop-scoped screens operate on selected shop, "apply to all" GROUP_ADMIN-only | ✓ VERIFIED (regression check — unchanged since prior pass) | `shop-context.ts`/`use-shop-context.ts`/`shop-switcher.tsx`/`shop-switcher-provider.tsx` re-read: single-guard `isGroupAdmin && selected === ALL_SHOPS_CONTEXT` for "apply to all" (shop-switcher.tsx:126), server-authoritative `isGroupAdmin` sourced from `GET /api/v1/staff/me` (CR-08 fix, 23-12/23-13) rather than a client-side realm-role guess. Products/orders/marketing/kitchen `page.tsx` all consume `useShopContext()`. Fresh Jest run this session: 157/157 green across `components/dashboard app/dashboard hooks` (one transient cross-suite flake on `marketing-kitchen-shop-scope.test.tsx` reproduced as a false-red under concurrent CPU load from a parallel Gradle run — reran clean twice, isolated run also clean; not a code defect). **Known, disclosed, non-blocking caveat (WR-04, unchanged since 23-07/23-15):** products/marketing screens narrow the already server-scoped list *client-side* over a single server-paginated page — a genuine pagination-correctness limitation (not a security bypass; the underlying set is already grant-scoped server-side) — tracked in `deferred-items.md`, not re-litigated here. |
| 4 | VSA-04: GROUP_ADMIN can list/grant/revoke staff roles per shop; grant unlocks immediately, revoke 403s immediately | ✓ VERIFIED (previously FAILED — now closed) | **System-principal fail-open fix (CR-03, plan 23-08):** the old `isSystemPrincipal()` (which fail-opened any non-UUID-subject JWT to unrestricted GROUP_ADMIN) is **removed**. `ShopAccessService.isGroupAdmin()` (lines 229-244) now composes `isRealmAdmin() \|\| isInternalCaller()` (true ONLY when `Authentication == null` on the thread — the narrow, non-externally-reachable internal bypass) `\|\| isDeclaredMachineClient(jwt)` (explicit, empty-by-default allowlist keyed on `azp`/`client_id`) — every other authenticated-but-unparseable-identity request is denied via `requireVendorUserId()` throwing the typed `ShopAccessDeniedException` (403), confirmed by reading the current source end-to-end. `StaffController` still has no independent `@PreAuthorize` (deferred to #206 per `deferred-items.md`, and D-10 explicitly forbids the `hasRole('admin')` form since it would exclude a non-realm-admin tenant GROUP_ADMIN) — this is now an accepted, disclosed design choice resting on a genuinely fail-closed `ShopAccessService`, not a live bypass. `ShopAccessFailClosedIntegrationTest` fresh run: 7/7 green (12:58:36), including `nonUuidSubjectIsDeniedOnStaffList` and the CR-04 null-shop 403-not-500 cases. `StaffManagementIntegrationTest` fresh run: 19/19 green (12:58:43), including `revokingLastGroupAdminIsBlockedWith409`, `concurrentRevokesCannotEmptyTheTenantOfGroupAdmins` (real `ExecutorService`/`CountDownLatch` concurrency proof), `grantGivesAccess_thenRevokeProduces403`, and the CR-08 `/me` + WR-05 grant-validation cases. `StrictScopingTighteningIntegrationTest` fresh run: 5/5 green (13:01:28) — proves the strict-scoping tightening genuinely de-honours JIT-sourced tenant-wide admin while honouring operator grants and the oldest-JIT bootstrap. |
| 5 | MOBL-01: sidebar no longer overlays content at 375px, verified by a 375px Jest/Playwright viewport spec | ⚠️ AUTOMATED PROOF GREEN, LIVE-BROWSER PROOF NOT EXECUTED | `frontend/e2e/dashboard-mobile.spec.ts` contains a dedicated `describe("Dashboard mobile shell (375px) — MOBL-01 + switcher regression")` block asserting `docScrollWidth <= viewportWidth + 1`, sidebar hidden, tab bar + switcher visible. This spec requires a REAL Keycloak vendor login (`E2E_VENDOR_PASSWORD`), which is not available in this session, and a frontend rebuild to serve current code on port 3000/3100 — the spec was NOT run live. The Jest-level proxy (`dashboard-shell.test.tsx` — "mounts the shop-context switcher in the md:hidden mobile top bar (375px chrome, MOBL-01)") passes (verified fresh this session, 157/157), but jsdom has no real viewport geometry: it only asserts the `md:hidden`/`fixed` Tailwind classes are present on the right elements, not that the document actually fits 375px without overflow. Per the phase's own `23-VALIDATION.md` and `deferred-items.md`, this exact gap (live Playwright run env-deferred) has been carried and disclosed since 23-05/23-07/23-13/23-15 — it is not new. **Escalated here as a human_verification item rather than silently accepted as PASSED**, per the honesty standard for this re-verification. |

**Score:** 5/5 roadmap success criteria have sound, independently-re-executed automated proof at the code and Testcontainers/Jest level. One (MOBL-01) additionally requires a live-browser confirmation that could not be run in this session — hence `human_needed`, not `passed`.

### Fresh Test Execution (this verification session, NOT prior-session cache)

All of the following were re-run from a clean state (`--rerun` where noted, or a first invocation with no prior same-session cache) against the current, clean HEAD (`0f01b32`) during this verification:

| Suite | Command | Result | Timestamp (fresh) |
|---|---|---|---|
| `TenantChannelInterceptorTest` | `./gradlew :core-java:test --tests "*TenantChannelInterceptorTest" --rerun` | 28/28 green | this session |
| `ShopAccessErrorTypeTest` | same invocation | green | this session |
| `ShopAccessCacheBypassIntegrationTest` | `./gradlew :core-java:integrationTest --tests "*ShopAccessCacheBypassIntegrationTest" --rerun` | 5/5 green | 2026-07-21T12:57:38 |
| `ShopAccessFailClosedIntegrationTest` | `./gradlew :core-java:integrationTest --tests "*ShopAccessFailClosedIntegrationTest" --tests "*StaffManagementIntegrationTest" --rerun` | 7/7 green | 2026-07-21T12:58:36 |
| `StaffManagementIntegrationTest` | same invocation | 19/19 green | 2026-07-21T12:58:43 |
| `StrictScopingTighteningIntegrationTest` | `./gradlew :core-java:integrationTest --tests "*StrictScopingTighteningIntegrationTest" --tests "*ShopAccessEnforcementIntegrationTest" --tests "*ShopStaffRlsPolicyIntegrationTest" --tests "*ShopAccessJitProvisionTest" --rerun` | 5/5 green | 2026-07-21T13:01:28 |
| `ShopAccessEnforcementIntegrationTest` | same invocation | 12/12 green | 2026-07-21T13:00:13 |
| `ShopStaffRlsPolicyIntegrationTest` | same invocation | 3/3 green | 2026-07-21T13:00:38 |
| `ShopAccessJitProvisionTest` | same invocation | 4/4 green | 2026-07-21T13:00:32 |
| Frontend Jest (`components/dashboard app/dashboard hooks`) | `npx jest components/dashboard app/dashboard hooks` | 157/157 green (2 consecutive runs; 1 transient cross-suite flake under concurrent Gradle CPU load, reproduced-clean on isolation and on rerun) | this session |
| Frontend TypeScript build | `npm run build` | Compiled + typechecked clean, `/dashboard/staff` route present | this session |

A full 7-class `--rerun` invocation was also attempted but exceeded a 590s timeout (fresh Testcontainers + full Spring context boot per class, ~7 classes, no incremental caching) and was killed (exit 143) partway through — superseded by the smaller, complete, fresh sub-runs above, which cover every one of the same 8 proof classes individually with 0 failures. This is disclosed for transparency, not hidden.

**Not independently re-run this session (regression risk judged low, unchanged code path):** the full `:core-java:integrationTest` task (331 tests) and the remaining ~320 non-Phase-23 test classes — the 23-16 SUMMARY's "331 tests, 0 failed" claim was **not** re-executed in full here; the fresh, scoped runs above cover every Phase-23-specific proof class directly. `docs/api/openapi-snapshot.json` / `docs-freshness` counts were not independently re-verified numerically (no code-behaviour risk).

### Required Artifacts (regression + gap-closure re-check)

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `core-java/.../db/migration/V52__shop_staff.sql`, `V57__shop_staff_grant_source.sql` | shop_staff/_aud/user_directory RLS + provenance column | VERIFIED | Unchanged sound RLS shape; V57 adds `grant_source` (JIT/OPERATOR) for CR-07 |
| `core-java/.../security/access/ShopAccessService.java` | require/isGroupAdmin/grantedShopIds/evictMembership, fail-closed | VERIFIED, WIRED | `isSystemPrincipal()` removed; replaced with the fail-closed `isRealmAdmin() \|\| isInternalCaller() \|\| isDeclaredMachineClient()` ladder feeding `requireVendorUserId()`'s typed-403 default-deny |
| `core-java/.../security/access/StaffManagementService.java` + `StaffController.java` | GROUP_ADMIN-gated staff CRUD | VERIFIED, WIRED | Gate (`requireGroupAdmin()`) is now backed by a genuinely fail-closed `ShopAccessService`; no `@PreAuthorize` backstop (disclosed, deferred to #206, not a live gap) |
| `core-java/.../shop/ShopService.java` + `ShopCacheLoader`, `.../product/ProductService.java` + `ProductCacheLoader` | deny-by-default single-read paths, cache-hit-safe | VERIFIED, WIRED | `require()` runs on every call, outside the `@Cacheable` boundary (relocated to a separate loader bean) |
| `core-java/.../websocket/TenantChannelInterceptor.java` | shop-scoped STOMP subscription | VERIFIED, WIRED | `validateShopSubscription` now grant-checks the kitchen topic's shop segment via the explicit-identity `canAccessShop` |
| `frontend/lib/shop-context.ts`, `hooks/use-shop-context.ts`, `components/dashboard/shop-switcher.tsx`, `shop-switcher-provider.tsx` | switcher + persistence + server-authoritative hook | VERIFIED, WIRED, DATA-FLOWING | Unchanged from prior pass; `isGroupAdmin` now server-sourced (CR-08) |
| `frontend/app/dashboard/staff/page.tsx` + sidebar nav | staff management UI | VERIFIED, WIRED | `npm run build` confirms the route compiles; self-identification via server `sub` (WR-12), not an email guess |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| ShopService/ProductService single-read paths | ShopAccessService.require() | direct call, outside `@Cacheable` | WIRED, cache-hit-safe | Fresh `ShopAccessCacheBypassIntegrationTest` proof with two different users |
| STOMP SUBSCRIBE `/topic/kitchen/{tenantId}/{shopId}` | ShopAccessService.canAccessShop() | explicit-identity call from `TenantChannelInterceptor` | WIRED | Fresh `TenantChannelInterceptorTest` + `StrictScopingTighteningIntegrationTest.stompLadder_tightensToo` |
| StaffController | ShopAccessService.requireGroupAdmin() → isGroupAdmin() | direct call, no `@PreAuthorize` backstop | WIRED, fail-closed | `isGroupAdmin()` no longer trusts unparseable identity; fresh `ShopAccessFailClosedIntegrationTest` |
| shop-switcher.tsx / page.tsx x4 | use-shop-context.ts / shop-context.ts | import + hook call | WIRED, DATA-FLOWING | Unchanged, regression-checked |
| ShopSwitcherProvider | `GET /api/v1/staff/me` (MyAccessDto) | `fetchMyShops()` | WIRED, DATA-FLOWING | Server-authoritative `isGroupAdmin`/`grantedShopIds`, resolves the CR-08 empty-set-sentinel ambiguity explicitly (null=unrestricted vs empty=no-access) |

### Requirements Coverage

| Requirement | Source Plans (all declared, cross-checked against REQUIREMENTS.md) | Status | Evidence |
|---|---|---|---|
| VSA-01 | 23-01, 23-02 | SATISFIED | Fresh RLS + JIT proofs green |
| VSA-02 | 23-02, 23-03, 23-08, 23-10, 23-11, 23-14, 23-16 | SATISFIED (previously BLOCKED) | Cache-bypass + STOMP fixes confirmed by direct code read + fresh two-user/mocked proofs |
| VSA-03 | 23-05, 23-07 | SATISFIED | Unchanged; WR-04 pagination caveat disclosed, non-blocking |
| VSA-04 | 23-04, 23-06, 23-08, 23-09, 23-12, 23-13, 23-14 | SATISFIED (previously BLOCKED) | System-principal fail-open fixed and fresh-proven; last-GROUP_ADMIN + concurrency guard fresh-proven |
| MOBL-01 | 23-05, 23-06 | AUTOMATED-SATISFIED / LIVE-VERIFICATION OUTSTANDING | Jest proxy green; Playwright 375px live run env-deferred (human_verification) |

No orphaned requirements: every plan's `requirements:` frontmatter field (16 plans checked) maps to one of VSA-01..04/MOBL-01, and REQUIREMENTS.md's Phase 23 row set (VSA-01..04, MOBL-01) has no entries missing plan coverage.

### Anti-Patterns Found

Swept all core gap-closure files (`ShopAccessService.java`, `StaffManagementService.java`, `StaffController.java`, `TenantChannelInterceptor.java`, `ShopService.java`, `ProductService.java`, `staff/page.tsx`, `shop-switcher.tsx`, `shop-switcher-provider.tsx`, `dashboard-shell.tsx`, `shop-context.ts`, `staff-api.ts`) for `TBD`/`FIXME`/`XXX`/`TODO`/`HACK`/`PLACEHOLDER` — **zero matches**. No blocker-level anti-patterns found.

### Known, Already-Accepted / Disclosed Items (not re-litigated as gaps)

- **WR-04** — products/marketing screens narrow client-side over a single server-paginated page (pagination correctness, not a security bypass). Disclosed in `deferred-items.md`, tracked for its own future plan.
- **`@PreAuthorize` backstop on `StaffController`** — deferred to issue #206 (scoped credentials); D-10 explicitly forbids the naive `hasRole('admin')` form. No longer load-bearing for the fail-closed guarantee now that `ShopAccessService.isGroupAdmin()` itself is fail-closed.
- **`asSystem()` marker for the retained `auth == null` internal bypass** — narrow, non-externally-reachable (Spring Security 401s before any gated service on an unauthenticated request); deferred as a larger refactor.
- **IN-01** — `fetchMyShops` hard-codes `size=200`; no known tenant exceeds this. Confirmed present in `frontend/lib/shops-api.ts:65`.
- **OpenAPI snapshot / docs-freshness counts** — reported RESOLVED in `deferred-items.md` (23-15, commit `adc1c58`); not independently re-verified numerically in this session (no behavioural risk).

## Human Verification Required

### 1. Live vendor-authenticated Playwright 375px + staff-screen click-through

**Test:** Set `E2E_VENDOR_PASSWORD`, rebuild the frontend container to serve current code, then run `cd frontend && npx playwright test dashboard-mobile.spec` (both the 390px sweep and the 375px MOBL-01 describe block) and manually click through `/dashboard/staff` grant → revoke on a real vendor session at both desktop and 375px widths.
**Expected:** No horizontal overflow at 375px (`docScrollWidth <= viewportWidth + 1`), sidebar hidden, tab bar + shop switcher visible and reachable; staff screen renders correctly and a grant/revoke round-trip behaves as coded (immediate unlock, immediate 403 on next request after revoke).
**Why human:** Requires a real Keycloak SSO login not available in this session; the Jest-level 375px assertion is a Tailwind-class proxy in jsdom, not an actual viewport-geometry measurement.

## Gaps Summary

None outstanding. All three concrete bypasses identified in the 2026-07-20 verification (`@Cacheable` cache-hit bypass on shop/product single-reads; STOMP kitchen-topic shop-segment omission; `isSystemPrincipal()` fail-open on non-UUID JWT subjects) are closed with sound, narrowly-targeted fixes, each independently re-confirmed in this session by (a) direct re-reading of the exact previously-cited code locations against current HEAD, and (b) fresh (not cached, not SUMMARY-trusted) execution of the named proof test suites, all green. VSA-01, VSA-03 show no regression. MOBL-01's automated (Jest) proof is green but its Playwright live-browser proof remains environmentally deferred — surfaced here as a human-verification item per the explicit anti-false-green standard for this phase, not silently accepted as a pass.

---

_Verified: 2026-07-21_
_Verifier: Claude (gsd-verifier)_
