---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 14
subsystem: auth
tags: [rbac, vendor-scoped-access, strict-scoping, provenance, membership-cache, rls, testcontainers, flyway]
gap_closure: true

# Dependency graph
requires:
  - phase: 23-vendor-scoped-access-responsive-dashboard-nav (23-08)
    provides: fail-closed principal + empty-by-default machine-client allowlist (jtoye.access.machine-client-ids)
  - phase: 23-vendor-scoped-access-responsive-dashboard-nav (23-09)
    provides: session-based operator grant (persistNewGrant) that Envers audits — the OPERATOR write site
  - phase: 23-vendor-scoped-access-responsive-dashboard-nav (23-10)
    provides: caching-enabled @TestConfiguration harness (proxy-reached cached loader pattern)
  - phase: 23-vendor-scoped-access-responsive-dashboard-nav (23-11)
    provides: shared isGroupAdminForUser decision helper both HTTP + STOMP ladders route through
  - phase: 23-vendor-scoped-access-responsive-dashboard-nav (23-13)
    provides: staff screen identity handling (userId-based isSelf) the JIT label extends
provides:
  - V57 shop_staff.grant_source (JIT|OPERATOR) + shop_staff_aud mirror — explicit grant provenance
  - Strict-scoping ON genuinely tightens — JIT-sourced tenant-wide GROUP_ADMIN de-honoured; operator grants + realm admins unchanged (CR-07)
  - Deterministic oldest-JIT bootstrap admin (WARN-logged) — no tenant can lock itself out on the flip
  - WR-09 — a declared machine client no longer JIT-accumulates a tenant-wide GROUP_ADMIN row
  - WR-01/WR-11 — the per-user membership cache genuinely engages (proxy) and evicts AFTER commit at both call sites
  - StrictScopingTighteningIntegrationTest — CR-07 central proof (RED pre-fix on 4/5)
affects: [23-15, 24-image-architecture, 25-mutating-mcp-tools]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Config-dependent authZ policy applied in the decision helper, OUTSIDE the cached snapshot, so a flag change is never served stale"
    - "Grant provenance recorded explicitly at each write site (never inferred from a nullable created_by going forward)"
    - "Proxy-reached @Cacheable (ObjectProvider self-reference) so a proxy-mode caching interceptor actually runs"
    - "Single shared post-commit eviction helper used by every writer so eviction timing cannot drift"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V57__shop_staff_grant_source.sql
    - core-java/src/main/java/uk/jtoye/core/security/access/GrantSource.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StrictScopingTighteningIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/MembershipSerializerRoundTripTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
    - core-java/src/main/java/uk/jtoye/core/security/access/Membership.java
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopStaff.java
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopStaffRepository.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/StaffMemberDto.java
    - core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessCacheBypassIntegrationTest.java
    - frontend/lib/staff-api.ts
    - frontend/app/dashboard/staff/page.tsx
    - frontend/app/dashboard/__tests__/staff-page.test.tsx

key-decisions:
  - "Task 0 checkpoint: user selected ACCEPT (accept the revision of D-04/D-12/D-05 as specified) — full path incl. the bootstrap-admin rule; no modification requested"
  - "grant_source DEFAULT 'JIT' (fail-safe: an unspecified insert is de-honoured under strict ON, never a surviving operator grant); backfill created_by IS NULL -> JIT else OPERATOR, then NOT NULL"
  - "A deliberate operator role change stamps grant_source = OPERATOR (operator takes ownership); a same-role replay is a documented no-op and keeps its provenance"
  - "Strict-scoping policy applied in isGroupAdminForUser (outside the cached Membership snapshot) so a flag change is never stale; Membership carries only the raw groupAdminFromJit fact"
  - "Bootstrap admin = oldest JIT tenant-wide GROUP_ADMIN by created_at,id — consulted ONLY when no OPERATOR admin exists; WARN-logged"
  - "WR-09 uses a subject-shape-independent allowlist check (isAllowlistedMachineClient) so a UUID-sub Keycloak service account is also skipped in onRequest"

requirements-advanced: [VSA-02, VSA-04]

# Metrics
duration: 24min
completed: 2026-07-21
---

# Phase 23 Plan 14: Strict-Scoping Actually Tightens (CR-07 / WR-09 / WR-01 / WR-11) Summary

**Enabling `strict-scoping` now genuinely tightens access: JIT-provisioned day-one tenant-wide GROUP_ADMIN rows are recorded with explicit provenance (V57) and de-honoured under strict ON — a day-one user really becomes scoped — while deliberate operator grants and realm admins are unchanged and the tenant's oldest JIT admin is retained as a WARN-logged bootstrap so no tenant can lock itself out; machine principals stop accumulating admin by accident; and the D-05 membership cache is made real (proxy-reached) with post-commit eviction proven.**

## Task 0 — Decision Checkpoint (resolved before execution)

The plan's Task 0 is a `checkpoint:decision`. The orchestrator presented it to the user and the user selected **`accept`** — "Accept the revision of locked decisions D-04, D-12 and D-05 as specified." No modification was requested. Tasks 1–3 were therefore executed in full, **including the bootstrap-admin rule** (the `accept` path, not `accept-no-bootstrap`). This is recorded verbatim per the checkpoint's `<verify>` human-check.

## Revised decision semantics — AS SHIPPED (for 23-15 to annotate 23-CONTEXT.md)

- **D-04 (JIT lazy-provision) — REFINED.** Unchanged behaviour while strict-scoping is OFF: the first write-capable request from an ungranted tenant user still auto-creates a tenant-wide GROUP_ADMIN row. **New:** that row is stamped `grant_source = 'JIT'` (V57) so it is distinguishable from a deliberate operator grant (`'OPERATOR'`). D-04's rejection of fail-OPEN is unchanged.
- **D-12 (strict-scoping switch) — REVISED.** Turning it ON now (1) stops new auto-provisioning AND (2) **stops honouring JIT-sourced tenant-wide GROUP_ADMIN rows.** Operator-created grants and realm admins are honoured unchanged. **Lockout safety:** if de-honouring would leave a tenant with zero GROUP_ADMINs, the **oldest** JIT tenant-wide GROUP_ADMIN (by `created_at`, tie-broken by `id`) is retained as the bootstrap admin, logged at WARN. Only engaged when no `OPERATOR` tenant-wide GROUP_ADMIN exists.
- **D-05 (immediate revocation) — MECHANISM CORRECTED.** The property is preserved; the mechanism is now real. `resolveMembership` is reached through the bean proxy (`ObjectProvider` self-reference) so the `@Cacheable` interceptor actually runs; grant/revoke and JIT-provision evict the exact entry **after commit** via one shared helper. Proven by test, not resting on dead code.

## Pre-fix RED evidence (falsifiability gate — Task 2, CR-07 central proof)

`StrictScopingTighteningIntegrationTest` was run against the pre-fix `isGroupAdminForUser` (which honoured every tenant-wide GROUP_ADMIN unconditionally), with V57 + the neutral `Membership.groupAdminFromJit` extension in place so it compiled. Result: **5 tests, 4 failed** — exactly the CR-07 cases:

- `strictOn_deHonoursJitGroupAdmins_operatorGrantHonoured` — FAILED `org.opentest4j.AssertionFailedError at StrictScopingTighteningIntegrationTest.java:120` (a JIT GROUP_ADMIN was still `isGroupAdmin()==true` under strict ON — the exact CR-07 defect: the switch tightened nothing).
- `strictOn_retainsOldestJitAsBootstrap_whenAllGroupAdminsAreJit` — FAILED at line 159 (younger JIT admins were still honoured; nothing de-honoured).
- `declaredMachineClient_isNotJitProvisioned` — FAILED at line 215 (a UUID-sub allowlisted service account was still JIT-provisioned a persistent GROUP_ADMIN row — WR-09).
- `stompLadder_tightensToo` — FAILED at line 241 (the STOMP `canAccessShop` gate still permitted a JIT admin under strict ON).
- `strictOff_dayOneUnchanged` — **PASSED** pre-fix (day-one preservation guard; green before AND after).

Post-fix: **StrictScopingTighteningIntegrationTest 5/5 GREEN.**

## Accomplishments

- **CR-07 closed** — strict-scoping ON de-honours JIT-sourced tenant-wide GROUP_ADMIN rows (a day-one user genuinely becomes scoped and deny-by-default on shop-scoped calls); operator grants + realm admins honoured unchanged. The policy is applied in the shared `isGroupAdminForUser` decision helper, so BOTH the HTTP and STOMP (23-11) transports tighten at once.
- **Lockout safety** — the deterministic oldest JIT admin is retained as a WARN-logged bootstrap when no operator admin exists, so a config flip can never strand a tenant at zero GROUP_ADMINs. The realm-admin bridge remains an independent backstop.
- **V57 provenance** — `shop_staff.grant_source` (CHECK `JIT|OPERATOR`) + `shop_staff_aud` mirror; deterministic backfill (`created_by IS NULL → JIT`, else `OPERATOR`) then `NOT NULL DEFAULT 'JIT'`. No RLS policy added/altered — `RlsContractTest.noPolicyUsesRawTenantGucCast` stays green.
- **WR-09** — `onRequest` skips the directory upsert + JIT provision for an allowlisted machine client (`isAllowlistedMachineClient`, subject-shape-independent), so a Keycloak service account with a UUID `sub` no longer accumulates a permanent tenant-wide GROUP_ADMIN row.
- **WR-01** — the membership cache genuinely engages: all internal gate call sites reach `resolveMembership` through the bean proxy (`self()`), proven by a caching-enabled test that asserts the entry is POPULATED after a gate call, serves stale until evicted, then re-resolves and denies.
- **WR-11** — the JIT-provision eviction now fires AFTER commit via a single shared `evictMembershipAfterCommit` helper used by both `onRequest` and `StaffManagementService`, so a concurrent request cannot cache pre-commit (empty) state and pin it for the TTL.
- **Operator visibility** — `StaffMemberDto.grantSource` is exposed and the staff screen labels JIT rows "Auto-granted on first sign-in" (minimal badge, no layout shift — 23-13's layout preserved) so an operator can see which grants are deliberate before flipping the switch.

## Task Commits

Each task committed atomically:

1. **Task 1: V57 grant_source provenance on shop_staff** — `084bf1f` (feat)
2. **Task 2: strict-scoping actually tightens + WR-09 machine skip + bootstrap** — `ff25ba3` (feat)
3. **Task 3: make the membership cache real + prove eviction (WR-01/WR-11)** — `b0577b1` (feat)

## Verification (all against real Postgres 15 via Testcontainers unless noted)

- `StrictScopingTighteningIntegrationTest` — **5/5** (RED pre-fix on 4/5).
- `ShopAccessEnforcementIntegrationTest` — **12/12** (no regression; day-one + STOMP `canAccessShop` cases unchanged).
- `ShopAccessCacheBypassIntegrationTest` — **5/5** (incl. the new WR-01/WR-11 engagement + eviction case).
- `StaffManagementIntegrationTest` — **19/19** (grants now carry OPERATOR provenance; no assertion regressions).
- `ShopStaffRlsPolicyIntegrationTest` — **3/3**; `RlsContractTest` green incl. `noPolicyUsesRawTenantGucCast` (V57 adds no policy).
- `ShopAccessFailClosedIntegrationTest`, `ShopAccessJitProvisionTest`, `ShopAccessErrorTypeTest` — green (full access-boundary regression clean).
- `MembershipSerializerRoundTripTest` — **3/3** (unit): `Membership` + the Task-2 provenance field round-trip through the exact `CacheConfig` JSON serializer with the `Map<UUID,ShopRole>` intact.
- Frontend: `npx jest app/dashboard` **93/93** (11 suites, incl. the new JIT-label case); `npm run build` (tsc) green.
- V57 applies cleanly on a V56 database carrying existing `shop_staff` rows (StaffManagement + Enforcement suites seed and read `shop_staff` post-migration); backfill leaves zero NULL `grant_source`.

## Deviations from Plan

None materially — plan executed as written on the `accept` path. Two in-flight fixes during Task 2 (deviation Rule 3, blocking issues, fixed inline and re-verified, not architectural):

- **[Rule 3 - blocking] Missing `java.util.List` import** in `ShopAccessService` (the new bootstrap finders return `List<ShopStaff>`) → `compileJava` failed. Added the import; recompiled clean.
- **[Rule 3 - blocking] `application.yml` `access:` key dropped** by the D-12 comment-rewrite edit → Spring context failed to bootstrap (all tests in the run failed with a YAML "block mapping" parse error). Restored the `  access:` mapping key; context boots, tests green. No secret/config value changed — only the comment and the accidentally-removed key line.

No architectural changes (Rule 4) were needed. No package installs (the plan adds no dependencies — threat T-23-14-SC not engaged).

## Deferred with Reason

- **Bulk-revoke of JIT rows in the staff screen** — deferred (per the plan's `<output>`). Individual revoke works correctly (23-09), and enabling strict-scoping de-honours JIT rows without any revoke at all. A bulk "revoke all auto-granted" affordance is operator convenience, not a security boundary — out of scope for this gap-closure.
- **`docs/metrics.json` reconcile (schema_version 56 → 57; new test counts)** — deferred to plan **23-15**, which owns the phase-gate `docs-freshness.sh --write` + `updateOpenApiSnapshot` reconcile (per STATE.md and the established 22-07 / 23-06 pattern). This plan adds 1 Flyway migration (V57) and **+9 Java `@Test` methods** (StrictScopingTightening 5, MembershipSerializerRoundTrip 3, ShopAccessCacheBypass +1) and **+1 Jest block** (staff-page JIT-label). The full `integrationTest` task remains red on `OpenApiSnapshotTest` until 23-15 regenerates the snapshot — a pre-existing phase blocker, unchanged by this plan. Scoped `--tests` runs stay green.
- **VSA-02 / VSA-04 NOT marked complete** — anti-false-green (phase discipline): both are *advanced* by CR-07/WR-09 closure, but 23-15 (OpenAPI + docs reconcile) still contributes to the phase before the requirements close.

## Threat Model — dispositions delivered

- T-23-14-01 (EoP: JIT rows surviving the flip) — mitigated (V57 provenance + de-honour, Tasks 1–2).
- T-23-14-02 (DoS: tenant lockout on flip) — mitigated (oldest-JIT bootstrap admin, WARN-logged, Task 2).
- T-23-14-03 (EoP: service accounts acquiring permanent GROUP_ADMIN) — mitigated (WR-09 onRequest skip, Task 2).
- T-23-14-04 (Repudiation: config comment overstating the control) — mitigated (application.yml + javadoc rewritten, Task 2).
- T-23-14-05 (EoP: stale cached membership after revoke) — mitigated (cache real + post-commit evict, Task 3).
- T-23-14-06 (EoP: pre-commit evict racing a re-resolve) — mitigated (single shared afterCommit helper, Task 3).
- T-23-14-07 (Info disclosure: serializer round-trip type fidelity) — mitigated (round-trip assertion; 23-08's null guard keeps `require()` cache-state-independent).

No new security-relevant surface beyond the plan's threat model was introduced (no new endpoints/auth paths; V57 adds a column under the existing RLS policy).

## User Setup Required

None. The revised `strict-scoping` remains OFF by default (day-one behaviour unchanged). A vendor who sets `ACCESS_STRICT_SCOPING=true` will now observe real tightening for day-one users and should first grant an explicit operator GROUP_ADMIN (via `/api/v1/staff/grant`) to any day-one admins they wish to keep — otherwise only the oldest JIT admin is retained (WARN-logged) as the bootstrap.

---
*Phase: 23-vendor-scoped-access-responsive-dashboard-nav*
*Completed: 2026-07-21*

## Self-Check: PASSED

- Files verified present: V57__shop_staff_grant_source.sql, GrantSource.java, StrictScopingTighteningIntegrationTest.java, MembershipSerializerRoundTripTest.java, 23-14-SUMMARY.md
- Commits verified in git: 084bf1f (Task 1), ff25ba3 (Task 2), b0577b1 (Task 3)
