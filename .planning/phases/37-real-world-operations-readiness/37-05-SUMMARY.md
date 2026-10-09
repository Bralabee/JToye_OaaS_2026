---
phase: 37-real-world-operations-readiness
plan: 05
subsystem: auth
tags: [d-08, d-09, d-07, d-23, effective-access, staff-page, user-directory, uxt-003, flyway-v76, membership-cache, testcontainers]

requires:
  - phase: 37-04
    provides: "strict scoping ON by default (D-06); StrictScopingDefaultIntegrationTest; shop-scoped media review queue"
  - phase: 37-02
    provides: "ShopGrants.grantOperator, StrictScopingGuard"
provides:
  - "GET /api/v1/staff people[]: every user_directory row plus every grant holder without one, each with an EffectiveAccess computed by ShopAccessService.effectiveAccessFor (the isGroupAdminForUser + resolveMembership decision path, never raw grant rows)"
  - "EffectiveAccess record (Level GROUP_ADMIN | REALM_ADMIN | SHOP_ROLES | NONE, bootstrapAdmin, allShops, tenantWideRole, perShopRole, realmAdminSeenAt) and StaffPersonDto"
  - "UserDirectoryToucher: user_directory sign-in record on READ requests too, in its own REQUIRES_NEW transaction (TransactionTemplate inside the try), best-effort, throttled in SQL and in-process; also reached from isGroupAdmin() so admin reads record the caller"
  - "V76 user_directory.realm_admin_seen_at (nullable, no backfill, no default), stamped the first time a token carries the realm admin role (throttle bypassed for exactly that case)"
  - "Membership.tenantWideRole + roleOn(shopId): a NULL-shop STAFF/SHOP_MANAGER grant confers that role on every shop of the caller's tenant, capped at its rank; applied in require (with the FC-1 tenant check), canAccessShop, grantedShopIds, and the SSE grant re-check"
  - "MyAccessDto.tenantWideRole (additive)"
  - "UXT-003 proven with the revoked manager's own token over HTTP, with the membership cache off and with it on and warm"
  - "OpenAPI snapshot regenerated: additive only (112 insertions, 0 deletions)"
affects: [37-B-staff-page-frontend, 37-07-invites, 37-15, phase-pr-docs-metrics]

actuals:
  tokens: 61532
  tasks: 3
  commits: 7
plan_head_before: 5164f5fa0ffab162ad6d033b1863f34b7c000f7b
plan_head_after: 8f8b3b6de07a0708709841e6eaa148fca965083e

tech-stack:
  added: []
  patterns:
    - "A best-effort side write runs in a TransactionTemplate(REQUIRES_NEW) INSIDE the try, not in a @Transactional method that catches in its body: the annotated form throws UnexpectedRollbackException at commit, after the catch (measured)"
    - "A test double for a database failure must fail THROUGH a Spring Data interceptor (abort the transaction, then make a real repository call), or it cannot see the rollback-only path"
    - "Status shown to a person is computed by the enforcement decision itself; grant rows decide only WHO is listed, never WHAT they may do"
    - "A tenant-wide role is honoured on a shop only after the shop is shown to be in the caller's tenant (explicit tenant predicate; shops_public_read exposes published foreign shops)"
    - "A @Nested class with @Import(LiveCacheTestSlice) gives one arm a real cache without changing the outer class's context"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/security/access/EffectiveAccess.java
    - core-java/src/main/java/uk/jtoye/core/security/access/UserDirectoryToucher.java
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/StaffPersonDto.java
    - core-java/src/main/resources/db/migration/V76__user_directory_realm_admin_seen.sql
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffEffectiveAccessIntegrationTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-05-red-people-list.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-05-red-grant-shapes.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java
    - core-java/src/main/java/uk/jtoye/core/security/access/Membership.java
    - core-java/src/main/java/uk/jtoye/core/security/access/UserDirectory.java
    - core-java/src/main/java/uk/jtoye/core/security/access/UserDirectoryRepository.java
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/MyAccessDto.java
    - core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffManagementIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/MembershipSerializerRoundTripTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopAccessServiceScopingPostureTest.java
    - docs/api/openapi-snapshot.json
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md

key-decisions:
  - "The directory touch runs through a TransactionTemplate(REQUIRES_NEW) inside a try, not a @Transactional(REQUIRES_NEW) method: measured, the annotated form with a catch in its body returns 500 (UnexpectedRollbackException) when the upsert fails through the repository interceptor"
  - "An in-process throttle (keyed tenant:user:realmAdmin) sits in front of the SQL throttle so a read does not check out a second pooled connection on every gated call (T-37-11); a failed write is not recorded, so it is retried"
  - "isGroupAdmin() also records the caller: GROUP_ADMIN and realm-admin reads short-circuit before onRequest(), so without it a realm admin's staff/me never reached the directory (found by the realm-admin arm)"
  - "effectiveAccessFor passes realmAdmin=false to isGroupAdminForUser (the database cannot see a Keycloak role); REALM_ADMIN comes only from the V76 observation, ranked below a decision-honoured GROUP_ADMIN and above shop roles"
  - "A tenant-wide role is honoured on a shop only after the FC-1 tenant check (require: the same non-disclosing 404; canAccessShop and grantedShopIds: explicit tenant predicates). Break arm: without it a tenant-wide SHOP_MANAGER wrote a product into another tenant's published shop (201)"
  - "A holder of a tenant-wide STAFF/SHOP_MANAGER grant is never the strict-OFF implicit admin: it is an explicit grant"
  - "Writes to a NULL-shop resource stay GROUP_ADMIN-only; a tenant-wide shop role does not widen the CR-04 write rule"
  - "Membership keeps a three-argument constructor and deserialises a pre-37-05 cached value with tenantWideRole null; no golden fixture pins the Jackson-3 shape, so a literal captured from the production serializer at 46a5435f was added as a unit test instead"
  - "Docs metrics (schema_version 76, new test counts) are left to the pre-PR step already recorded in deferred-items §1, which now names V76"

patterns-established:
  - "Effective access for display comes from ShopAccessService.effectiveAccessFor; any new consumer of Membership must use roleOn(shopId), not perShopRole().get(shopId)"

requirements-completed: [RWO-003, RWO-004]

coverage:
  - id: D1
    description: "A signed-in user with no grant (who has only read) appears in GET /api/v1/staff people[] with effectiveAccess NONE; directory and grants stay; the email is masked"
    requirement: "RWO-003"
    verification:
      - kind: integration
        ref: "StaffEffectiveAccessIntegrationTest#signedInUngrantedUser_isListed_asNone: RED on 6d9dbe94 (RED_EVIDENCE_OK, evidence/37-05-red-people-list.json), GREEN on 2a08876c, green in the full suite at 8f8b3b6d"
        status: pass
    human_judgment: false
  - id: D2
    description: "The people list is the enforcement decision: OPERATOR admin GROUP_ADMIN; oldest JIT admin GROUP_ADMIN + bootstrapAdmin; a younger JIT admin NONE; raw-row derivation is caught"
    requirement: "RWO-003"
    verification:
      - kind: integration
        ref: "StaffEffectiveAccessIntegrationTest operator + JIT arms; break arm A (raw rows) red at line 196, arm A2 (raw rows + a JIT bootstrap guess) red at line 205 'expected NONE but was GROUP_ADMIN'; closing clean 4/0"
        status: pass
    human_judgment: false
  - id: D3
    description: "A failed directory write never fails the request that triggered it (T-37-11)"
    verification:
      - kind: integration
        ref: "StaffEffectiveAccessIntegrationTest#directoryWriteFailure_doesNotFailTheRequest: green; arm B (no catch) 409; arm C (@Transactional REQUIRES_NEW with catch in body) 500 UnexpectedRollbackException"
        status: pass
    human_judgment: false
  - id: D4
    description: "A NULL-shop SHOP_MANAGER writes on every tenant shop (SHOP_ROLES, tenantWideRole, allShops); a NULL-shop STAFF reads every shop's orders, is refused a product write, and staff/me names tenantWideRole STAFF and every tenant shop; neither crosses the tenant wall"
    requirement: "RWO-003"
    verification:
      - kind: integration
        ref: "StaffEffectiveAccessIntegrationTest tenant-wide arms: RED 4/9 on 9c6f7565 (RED_EVIDENCE_OK, evidence/37-05-red-grant-shapes.json), GREEN 9/0 on 945a25d6; arm a (no tenant check) foreign-shop write 201; arm c (uncapped STAFF) product write 201; closing clean 9/0"
        status: pass
    human_judgment: false
  - id: D5
    description: "A realm admin is stamped in user_directory.realm_admin_seen_at at sign-in (even on a fresh row) and lists as REALM_ADMIN; an ordinary user stays NULL / NONE"
    requirement: "RWO-003"
    verification:
      - kind: integration
        ref: "StaffEffectiveAccessIntegrationTest#realmAdmin_isStamped_andReadsRealmAdmin: RED (NONE), GREEN; arm d (throttle-bypass clause removed) red 'not No access ... NONE'"
        status: pass
    human_judgment: false
  - id: D6
    description: "UXT-003: after a Group admin revokes a manager's last grant, the manager's own token gets 403 shop-access-denied on the next request and staff/me says groupAdmin=false, grantedShopIds=[] — with no cache, and with the cache on and warm"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: "StaffManagementIntegrationTest$Uxt003RevokeEndsAccess and $Uxt003RevokeEndsAccessWithAWarmCache: default run 21/0; ACCESS_STRICT_SCOPING=false run rc=1 with both arms red (201 after revoke, booted strict=false); revoke-eviction removed: warm-cache arm red, cache-off arm green"
        status: pass
    human_judgment: false
  - id: D7
    description: "A Membership cached before tenantWideRole existed still deserialises; a tenant-wide role round-trips"
    verification:
      - kind: unit
        ref: "MembershipSerializerRoundTripTest 5/0 (literal captured from CacheConfig.jsonRedisSerializer at 46a5435f); CacheSerializerTypeAllowlistTest 16/0; GoldenFixturesIntegrityTest 5/0; CacheFormatIsolationIntegrationTest 2/0"
        status: pass
    human_judgment: false
  - id: D8
    description: "Published contract updated, additive only"
    verification:
      - kind: integration
        ref: "OpenApiSnapshotTest green on the regenerated snapshot, red on the previous one; diff 112 insertions / 0 deletions"
        status: pass
    human_judgment: false
  - id: D9
    description: "Full core-java suite green with ACCESS_STRICT_SCOPING unset"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: "8f8b3b6d: :test 187 files / 1599 tests / 0 failed / 1 skipped; :integrationTest 179 files / 919 tests / 0 failed / 1 skipped; booted strict=true"
        status: pass
    human_judgment: false
  - id: D10
    description: "The running compose stack shows the people list and realm-admin stamping"
    verification: []
    human_judgment: true
    rationale: "Not rebuilt here, by design: the strict-scoping flip and everything layered on it reach the shared runtime at the 37-15 gate (deferred-items §4). V76 will apply on that rebuild; parity must then be proven with scripts/check-runtime-freshness.sh"

duration: 69m
completed: 2026-10-08
status: complete
---

# Phase 37 Plan 05: Server-computed effective access (D-08, D-09) Summary

**`GET /api/v1/staff` now carries `people[]`: everyone who has signed in to the tenant, plus every grant holder without a directory row. Each person comes with an `EffectiveAccess` computed by `ShopAccessService.effectiveAccessFor`, the same decision path enforcement uses. A user who has only read now reaches the directory and reads `NONE`. A NULL-shop STAFF or SHOP_MANAGER grant now confers its role on every shop of the tenant. A realm admin reads `REALM_ADMIN` (V76) instead of "No access". UXT-003 is proven with the revoked manager's own token, including against a warm membership cache.**

## Performance

- **Duration:** 69 min (30 of them the full-suite run)
- **Started:** 2026-10-08T11:22:42Z
- **Completed:** 2026-10-08T12:31:06Z
- **Tasks:** 3
- **Files modified:** 20 (7 created, 13 modified)

## Accomplishments

- **Task 1 (tracer).** `UserDirectoryToucher` records the caller on reads as well as writes.
  - It runs in its own `REQUIRES_NEW` transaction, which works under a read-only caller and cannot poison the caller's transaction.
  - It is best-effort and throttled twice: in SQL as before, and in-process so a read does not check out a second pooled connection on every gated call.
  - `EffectiveAccess` + `effectiveAccessFor(userId)` compute access through `isGroupAdminForUser` + `resolveMembership`.
  - `people[]` maps every listed person through it. The grant rows are used only to decide who is listed.
  - Tracer gate: the `<verify>` was re-run end to end (closing clean 4/0) before expansion.
- **Task 2.**
  - **V76** adds `user_directory.realm_admin_seen_at`. The new `recordSignIn` upsert stamps it the first time a token carries the realm admin role, and bypasses the throttle only for that case.
  - **`Membership.tenantWideRole`** records a NULL-shop STAFF or SHOP_MANAGER row. `roleOn(shopId)` gives the higher of the specific grant and the tenant-wide role.
  - That role is applied in `require` (behind the FC-1 tenant check), `canAccessShop`, `grantedShopIds` (every current tenant shop) and `OrderSseService`'s per-emit re-check.
  - `MyAccessDto.tenantWideRole` is added.
  - `isGroupAdmin()` now also records the caller, so admin reads reach the directory.
- **Task 3.** Two UXT-003 arms in nested classes, both over HTTP with the manager's own token. One runs with the test profile's absent cache. The other runs with a real cache, warmed by the manager's `staff/me`; its entry is asserted present before the revoke and absent after. The OpenAPI snapshot was regenerated.

## Task Commits

1. **Task 1 RED: people-list test** - `6d9dbe94` (test)
2. **Task 1 GREEN: effective access + read-path touch** - `2a08876c` (feat)
3. **Task 1: test double fails through the repository interceptor** - `46a5435f` (test)
4. **Task 2 RED: tenant-wide roles + realm-admin tests** - `9c6f7565` (test)
5. **Task 2 GREEN: tenant-wide roles, V76, REALM_ADMIN** - `945a25d6` (feat)
6. **Task 3: UXT-003 arms** - `180a8233` (test)
7. **Task 3: OpenAPI snapshot** - `8f8b3b6d` (docs)

**Plan metadata:** the commit that adds this SUMMARY and the deferred-items note (docs). STATE.md and ROADMAP.md are not touched; the orchestrator owns them.

## Evidence (both directions)

| Check | Pass direction | Fail direction |
|-------|----------------|----------------|
| People list (Task 1, 4 arms) | 4/0 on `2a08876c`; closing clean 4/0 | RED 4/4 on `6d9dbe94`, RED_EVIDENCE_OK. The list had no `people`, and its `directory` held only the admin: the read-only `staff/me` never reached `user_directory`. |
| Raw-row derivation (T-37-09) | as above | **Arm A** (level from `shop_staff` rows) red at line 196: bootstrapAdmin `expected true but was false`. **Arm A2** (raw rows plus a JIT bootstrap guess) red at line 205: younger JIT admin `expected "NONE" but was "GROUP_ADMIN"`. |
| Best-effort directory write (T-37-11) | 200 with the double, WARN `Directory upsert skipped (best-effort) … DataIntegrityViolationException` | **Arm B** (catch removed): 409. **Arm C** (`@Transactional(REQUIRES_NEW)` with the catch in the method body): 500 `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only`. Arm C passed against the first double, a bare thrown stub, so the double was strengthened (`46a5435f`). |
| Tenant-wide roles + realm admin (Task 2) | 9/0 on `945a25d6`; closing clean 9/0 (in the Task 3 run) | RED 4/9 on `9c6f7565`, RED_EVIDENCE_OK. The target got `403 shop-access-denied` for a NULL-shop SHOP_MANAGER write. |
| Tenant wall for a tenant-wide role (T-37-10) | 403/404 and 0 rows written | **Arm a** (no tenant check in `require`): the product was written into another tenant's published shop, `201`. |
| Rank cap (T-37-10) | tenant-wide STAFF product write 403 | **Arm c** (tenant-wide role uncapped): `201` |
| Realm-admin stamp on a fresh row | REALM_ADMIN | **Arm d** (throttle-bypass clause removed): level `NONE` |
| UXT-003 (both arms) | default: 21/0 | `ACCESS_STRICT_SCOPING=false`: gradle rc=1, both arms red with `201` after the revoke (booted `strict=false`). This is the UXT-003 repro: the revoked manager becomes the implicit admin. |
| Eviction, not expiry | warm-cache arm green | revoke eviction removed: the warm-cache arm is red (`expected: null but was: ValueWrapper for [Membership…]`) and the cache-off arm stays green |
| OpenAPI snapshot | `OpenApiSnapshotTest` 1/0 on the new snapshot | the previous snapshot against the new code: red, "no longer matches the reviewed snapshot". Restored by `cp`, hash verified. |
| V76 criteria | `rg -n realm_admin_seen_at` prints 4 lines including line 37 `ALTER TABLE … ADD COLUMN IF NOT EXISTS realm_admin_seen_at TIMESTAMPTZ`; `rg -n -i '^\s*UPDATE\b'` rc 1 | a copy with an appended `UPDATE user_directory SET realm_admin_seen_at = now();` → rc 0, line 47 |
| Full suite (setting unset) | `:test` 187 / 1599 / 0 failed; `:integrationTest` 179 / 919 / 0 failed (head `8f8b3b6d`) | n/a. The arms above are the falsifying runs. |

Every break arm ran on a committed tree. Each restore was verified by sha256 against a hash recorded before the arm.

Note on the plan's V76 criterion: "`grep realm_admin_seen_at` prints at least one line" is weak on its own, because it also matches the header comment. The ALTER line (37) was checked by content as the stronger form.

### OpenAPI diff (additive only: 112 insertions, 0 deletions)

```
components.schemas.EffectiveAccess        NEW  {allShops, bootstrapAdmin, level enum[GROUP_ADMIN,REALM_ADMIN,SHOP_ROLES,NONE],
                                                perShopRole map<uuid,ShopRole>, realmAdminSeenAt date-time|null,
                                                tenantWideRole ShopRole|null, userId uuid}
components.schemas.StaffPersonDto          NEW  {displayName string|null, effectiveAccess $ref EffectiveAccess,
                                                lastSeen date-time|null, maskedEmail string|null, userId uuid}
components.schemas.MyAccessDto             +    tenantWideRole: ShopRole|null
components.schemas.StaffListResponse       +    people: array<$ref StaffPersonDto>
```

No path, status or existing property changed.

## Decisions Made

See `key-decisions` in the frontmatter.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The planned `@Transactional(propagation = REQUIRES_NEW)` toucher would throw past its own catch**
- **Found during:** Task 1, by break arm C
- **Issue:** the plan specified a `@Transactional(REQUIRES_NEW)` `touch()` that catches and logs. When the upsert fails through Spring Data's interceptor, the transaction is marked rollback-only. The proxy then throws `UnexpectedRollbackException` at commit, after the catch, straight into the request. Measured: 500.
- **Fix:** `touch()` drives a `TransactionTemplate(PROPAGATION_REQUIRES_NEW)` inside the `try`, the same shape as the `VendorOnboardingService.recordSubmitterInDirectory` precedent. The test double was strengthened so that it fails through a repository interceptor; the bare thrown stub had let arm C pass.
- **Files:** `UserDirectoryToucher.java`, `StaffEffectiveAccessIntegrationTest.java`
- **Commits:** `2a08876c`, `46a5435f`

**2. [Rule 1 - Bug] A realm admin's reads never reached the directory**
- **Found during:** Task 2 GREEN (the realm-admin arm stayed red)
- **Issue:** `myAccess()` and every read for a GROUP_ADMIN or realm admin returns from `isGroupAdmin()` before `onRequest()` runs. A realm admin's `staff/me` therefore wrote nothing, and `realm_admin_seen_at` could never be stamped through the dashboard's first call.
- **Fix:** the directory part of `onRequest()` was extracted into `touchDirectory()`, and `isGroupAdmin()` calls it. The in-process throttle makes the repeat call a map lookup. JIT provisioning is unchanged and stays in `onRequest()`.
- **Commit:** `945a25d6`

**3. [Rule 2 - Missing critical] A tenant-wide role must stop at the tenant wall (T-37-10)**
- **Issue:** honouring "role on all shops" for any `shopId` would let a tenant-wide SHOP_MANAGER write into another tenant's PUBLISHED shop: `shops_public_read` makes that shop visible. Break arm a measured 201.
- **Fix:**
  - `require` runs `requireShopInCallerTenant` (the FC-1 non-disclosing 404) whenever the role comes from the tenant-wide grant alone.
  - `canAccessShop` checks `findByIdAndTenantId`.
  - `grantedShopIds` uses the new `ShopRepository.findIdsByTenantId`, which has an explicit tenant predicate.
- **Files:** `ShopAccessService.java`, `ShopRepository.java` (not in the plan's file list)
- **Commit:** `945a25d6`

**4. [Rule 1 - Bug] Two consumers outside the plan's list would have ignored a tenant-wide role**
- `isGroupAdminForUser`'s strict-OFF line treated "no per-shop rows" as fully ungranted. A tenant-wide STAFF holder would therefore have become the implicit GROUP_ADMIN under the OFF override. It now also requires `tenantWideRole == null`.
- `OrderSseService.stillPermitted` checked `perShopRole().containsKey(shopId)`, so a tenant-wide holder's kitchen stream would have dropped every event. It now uses `roleOn(shopId)`.
- **Commit:** `945a25d6`

**5. [Rule 3 - Blocking] New repository method for the stamp**
- `UserDirectoryRepository.recordSignIn` (not in the plan's list) carries the realm-admin stamp. `upsertSeen` is unchanged, because `VendorOnboardingService` still uses it.
- **Commit:** `945a25d6`

**6. [Rule 3 - Blocking] `ShopAccessServiceScopingPostureTest` builds the service by hand**
- The new constructor argument (`UserDirectoryToucher`) is supplied as a mock in `freshService()` and in the `ApplicationContextRunner`.
- **Commit:** `2a08876c`

**7. [Rule 3 - Blocking] Python-free verification; compound commands split**
- No python step was needed. Runs used scratchpad scripts (`p05/run-it.sh`, `run-unit.sh`, `run-full.sh`, `check-xml.sh`, `red-record.sh`).
- `check-xml.sh` prints a VOID line when it is given a `--tests` wildcard as a class name. For those runs the whole result directory was checked instead (all files, counted).

---

**Total deviations:** 7 auto-fixed (3 bugs, 1 missing critical, 3 blocking).
**Impact on plan:** each change is required for the plan's own truths to hold: a best-effort write that is really best-effort, realm admins visible, and "all shops" meaning all of this tenant's shops. No refusal was weakened.

## Issues Encountered

- **The first RED run had one arm failing on an NPE, not an assertion** (the operator arm dereferenced a missing `people` entry). An `isNotNull` assertion was added before the RED commit, and every arm then failed on a planned assertion.
- **Task 2's RED could not include the Membership unit tests.** They do not compile against the three-component record, and a compile error would have voided the integration RED, because both tasks compile the same test source set. They were set aside for the RED run and land with GREEN (`945a25d6`). They are compatibility guards, not behaviour RED.
- **The cache-format golden does not pin the Jackson-3 Membership shape.** `jackson2-golden/cache/shopMembership-Membership.bin` is the Boot-3.5 format, which is deliberately unreadable. The compatibility literal was therefore captured from the production serializer with a throwaway test at `46a5435f`; that test was deleted and never committed.
- The plan ledger is kept in the session scratchpad (`p05/plan-head-before` = `5164f5fa…`), as in 37-02..37-04.

## Known Stubs

None.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: information-disclosure | StaffManagementService.java / EffectiveAccess.java | A tenant's GROUP_ADMIN now sees which signed-in accounts are realm (platform) admins (`level REALM_ADMIN`, `realmAdminSeenAt`), and each colleague's computed access. This is by design (Pitfall 13, D-09). It is behind the existing `requireGroupAdmin()` gate, and emails stay masked (WR-10). |
| threat_flag: dos-surface | UserDirectoryToucher.java | Reads now write a directory row in a second transaction. Mitigated by the in-process throttle (one write per tenant:user per interval per instance) and by best-effort failure handling (T-37-11). A cold start under heavy concurrency is the residual: each user's first call still takes a second connection once. |

## TDD Gate Compliance

- **Task 1:** RED `6d9dbe94` `test(37-05)` (RED_EVIDENCE_OK) → GREEN `2a08876c` `feat(37-05)`. A test-only follow-up, `46a5435f`, strengthened the double after break arm C.
- **Task 2:** RED `9c6f7565` `test(37-05)` (RED_EVIDENCE_OK) → GREEN `945a25d6` `feat(37-05)`.
- **Task 3:** per the plan, no RED was expected on this tree (37-04 already removed the escalation). The fail direction is the `ACCESS_STRICT_SCOPING=false` run (rc 1, both arms red) plus the no-eviction break arm. Commits: `180a8233` (test) and `8f8b3b6d` (docs).
- No REFACTOR commits.

## User Setup Required

None.

## Next Phase Readiness

- **Staff-page frontend (D-09 UI):** render `people[].effectiveAccess` directly (NONE → "No access" + Grant, REALM_ADMIN → "Admin account", `bootstrapAdmin` → a hint to grant an explicit admin, `allShops` / `tenantWideRole` → "All shops"). Do not derive access in the browser. Deferred-items §3 (old OFF-default comments in `staff-api.ts` / `staff/page.tsx`) belongs to that work.
- **D-07 invites:** a "role + all shops" grant is now honoured end to end. `grant()` already accepts a NULL-shop STAFF or SHOP_MANAGER.
- **37-15 gate:** V76 applies on the next rebuild of the shared runtime. Prove parity there (deferred-items §4).
- **Before the phase PR:** docs metrics. `schema_version` is now 76, and test counts moved (deferred-items §1, updated). Re-measure.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-08*

## Self-Check: PASSED

- Created files exist: EffectiveAccess.java, UserDirectoryToucher.java, StaffPersonDto.java, V76__user_directory_realm_admin_seen.sql, StaffEffectiveAccessIntegrationTest.java, evidence/37-05-red-people-list.json, evidence/37-05-red-grant-shapes.json
- Commits on this branch: 6d9dbe94, 2a08876c, 46a5435f, 9c6f7565, 945a25d6, 180a8233, 8f8b3b6d (`git rev-list --count 5164f5fa..HEAD` = 7 at SUMMARY write)
- Acceptance re-run: V76 grep (4 lines, ALTER at 37) and no UPDATE (rc 1, broken copy rc 0); OpenAPI diff 112/0; the full suite at `8f8b3b6d` is green
