---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 12
subsystem: security/access + gdpr
tags: [vendor-scoped-access, staff-management, gdpr, pii, rls, agent-readiness]
gap_closure: true
requires:
  - "23-09 (grant() reshaped to an audited session write — extended here, not reverted)"
  - "V52 shop_staff / user_directory data layer"
provides:
  - "grant() input validation (shop tenancy + user existence) — typed 404s"
  - "GET /api/v1/staff/me — server-authoritative effective-access answer (MyAccessDto)"
  - "masked directory email + user_directory under GDPR erasure"
affects:
  - "23-13 (consumes MyAccessDto; replaces client-side JWT parse + email-based isSelf)"
  - "23-15 (OpenAPI regen must now pick up FOUR /api/v1/staff endpoints)"
tech-stack:
  added: []
  patterns:
    - "tenant-scoped existence check over RLS (RI bypasses RLS) for privilege writes"
    - "empty-set sentinel resolved at the DTO boundary (null = unrestricted, empty = none)"
    - "email masking at the DTO edge; full value retained server-side only"
    - "GDPR email-match erasure of a no-_aud derived cache"
key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/MyAccessDto.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java
    - core-java/src/main/java/uk/jtoye/core/security/access/StaffController.java
    - core-java/src/main/java/uk/jtoye/core/security/access/UserDirectoryRepository.java
    - core-java/src/main/java/uk/jtoye/core/security/access/dto/DirectoryEntryDto.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
    - core-java/src/test/java/uk/jtoye/core/security/access/StaffManagementIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/gdpr/GdprErasureIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/gdpr/GdprServiceTest.java
decisions:
  - "MyAccessDto resolves the empty-set sentinel: groupAdmin=true → grantedShopIds=null (unrestricted); groupAdmin=false → exact possibly-empty set (empty = no access)"
  - "grant() user-existence check tightens grants to directory-known (logged-in) users — inside 23-CONTEXT locked decisions, not a scope change"
  - "foreign-tenant and non-existent shop return an identical 404 (no existence oracle)"
  - "user_directory erased by tenant_id + email; zero matches is normal, not a failure; no ErasureRecord schema change (accounting unchanged)"
  - "VSA-04 stays PENDING — 23-13/14/15 still contribute (anti-false-green)"
metrics:
  duration: ~40m
  tasks: 3
  files: 9
  completed: 2026-07-21
---

# Phase 23 Plan 12: Staff Backend Hardening (WR-05 + CR-08 backend + WR-10) Summary

Grant inputs are validated, the server now answers "what may I do" authoritatively, and the
staff-directory PII this phase introduced is masked at the edge and erasable on request.

## What shipped

### Task 1 — WR-05: grant() validates shop tenancy + user existence
`StaffManagementService.grant()` now rejects, with a typed 404 (`ResourceNotFoundException`),
a `shopId` that is not a shop in the caller's tenant and a `userId` absent from the tenant's
`user_directory`. Both checks run AFTER `requireGroupAdmin()` and the GROUP_ADMIN-shape rule
but BEFORE the D-11 downgrade guard and any write, so an invalid request never reaches the row
lock or the insert. The shop check uses the tenant-scoped `ShopRepository.findByIdAndTenantId`
finder — the FK cannot enforce tenancy because PostgreSQL referential-integrity checks bypass
RLS (exactly why a foreign-tenant shop id was accepted before). Foreign-tenant and non-existent
shops return an identical 404 (no existence oracle). Proven cross-tenant with a real second
tenant (`grantForeignTenantShopIsRejectedAndWritesNoRow`), asserting both the exception AND that
no `shop_staff` row was written.

### Task 2 — CR-08 backend: GET /api/v1/staff/me
New `MyAccessDto(userId, groupAdmin, grantedShopIds)` + `StaffManagementService.myAccess()` +
`StaffController.myAccess()`. It is **not** GROUP_ADMIN-gated (every caller may ask about
itself) and is `@Transactional(readOnly = true)`, so `ShopAccessService.onRequest()` returns
early — no JIT provision, no directory upsert on a pure self-query. It proves the exact case a
client-side realm-role parse gets wrong: a fully-ungranted user under strict-scoping OFF is the
day-one implicit GROUP_ADMIN and reports `groupAdmin: true`.

### Task 3 — WR-10: mask directory email + bring user_directory under GDPR erasure
`DirectoryEntryDto.from()` masks the email at the boundary (`alice@example.com → a***@example.com`);
the full value is retained server-side only and never leaves on `/api/v1/staff`. `GdprService`
now erases `user_directory` rows by `tenant_id + email` (mirroring the guest-order email sweep) —
a no-`_aud` derived cache (D-09), so a straight tenant-scoped DELETE is the complete erasure.
Zero matches is the normal case and is not a failure; the `ErasureRecord` accounting is unchanged
(no schema change). Grants still key on `userId`, so the picker stays fully functional under masking.

## MyAccessDto contract (consumed by plan 23-13)

The empty-set sentinel ambiguity in `ShopAccessService.grantedShopIds()` (empty = "unrestricted"
for a GROUP_ADMIN, but also "no access" for a scoped user) is **resolved at the DTO boundary**:

| Case | `groupAdmin` | `grantedShopIds` | Meaning |
|------|-------------|------------------|---------|
| realm-admin / tenant-wide GROUP_ADMIN / day-one implicit admin | `true` | `null` | unrestricted — all shops; **not** "no shops" |
| scoped SHOP_MANAGER/STAFF | `false` | `[shop ids]` | exactly those shops |
| scoped user with no grants (strict-scoping ON) | `false` | `[]` (empty) | no access |

So an empty `grantedShopIds` only ever occurs with `groupAdmin == false` and always means "no
access"; "unrestricted" is `null` + `groupAdmin == true`. `myAccess()` short-circuits on
`isGroupAdmin()` and only calls `grantedShopIds()` for a non-group-admin, so the service never
observes the ambiguous empty-set-as-unrestricted return. `userId` carries the caller's own
Keycloak `sub` so 23-13 can do its `isSelf` check without an email round-trip. Documented in the
record javadoc and the `@Schema` descriptions.

## One-wave isSelf warning gap (closed by 23-13)

`frontend/app/dashboard/staff/page.tsx` currently builds `emailByUserId` from the directory and
uses the session email for an email-based `isSelf` comparison (the "you are removing your own
access" warning). With email now masked, that email-based comparison can no longer match — so
during this one wave the self-revoke warning may not render (it already failed to render whenever
the session email was absent). 23-13 replaces that email-based `isSelf` with the `userId` now
carried by `MyAccessDto`, closing the gap. No functional regression to grant/revoke (they key on
`userId`); only the advisory copy is affected.

## OpenAPI snapshot — now FOUR /api/v1/staff endpoints

`GET /api/v1/staff/me` is registered, so the staff surface is now **four** endpoints —
`GET /` (list), `GET /me`, `POST /grant`, `DELETE /{id}`. Plan 23-15's
`./gradlew :core-java:updateOpenApiSnapshot` regeneration must pick up four, not three. The full
`integrationTest` task stays red on `OpenApiSnapshotTest` (check mode) until 23-15 regenerates —
this is the pre-existing, documented phase blocker (STATE.md + deferred-items.md), not a defect
introduced here. Scoped test runs are green.

## Falsifiability (Task 3 — MANDATORY gate)

The masking and erasure assertions were run against pre-fix source and observed RED before the fix
(so the tests genuinely observe the defect this phase's earlier work could not):

```
[the directory email is masked, not the full address]  expected: "a***@example.com"  but was: "alice.member@example.com"
[operator sees only the masked email]                  Expecting "bob.member@example.com" to contain: "***"
[the subject's tenant-A user_directory row is erased]  expected: 0L  but was: 1L   (directory survived erasure)
```

After the fix all three go GREEN; the `erasureWithNoDirectoryRowStillBalances` regression guard
was green before and after (the zero-match path was already correct). Task 1's validation tests
were likewise RED pre-fix (`grantForeignTenantShopIsRejectedAndWritesNoRow`,
`grantUnknownUserIsRejected`) — the foreign-shop/unknown-user grant succeeded and wrote a row.

## Verification

- `./gradlew :core-java:integrationTest --tests "*StaffManagementIntegrationTest"` — GREEN (all 19)
- `./gradlew :core-java:integrationTest --tests "*StaffManagementIntegrationTest" --tests "*GdprErasureIntegrationTest"` — GREEN
- `./gradlew :core-java:test` — GREEN (full unit suite; GdprServiceTest constructor wiring updated)
- Regression sweep: `*ShopAccessFailClosedIntegrationTest` + `*ShopStaffRlsPolicyIntegrationTest` — GREEN
- Cross-tenant grant rejection proven with a real second tenant (Testcontainers), not a mock
- `/me` proven for the ungranted-day-one case CR-08's client-side check gets wrong

## Deviations from Plan

### Rule 3 (blocking) — seed user_directory in pre-existing grant tests
The Task 1 user-existence check made every existing `StaffManagementIntegrationTest` grant target
(random UUIDs that never "logged in") fail the new precondition. Added a `seedDirectory(tenant,
userId)` helper and seeded the directory for each grant target across the pre-existing tests
(`grantGivesAccess…`, `duplicateGrant…`, `revokingLastGroupAdmin…`, `secondGroupAdmin…`,
`listReturnsCurrentGrants`, the four 23-09 role-change tests, and both concurrency tests). This is
required test infrastructure for the intended behavior change, not a scope change. Committed with
the RED test (9b57a9d) and the GREEN fix (38ad074). `ShopAccessFailClosedIntegrationTest`'s grant
is denied at `requireGroupAdmin()` first, so it needed no seed.

### Rule 3 (blocking) — GdprServiceTest mock wiring
`GdprService` gained a `UserDirectoryRepository` constructor dependency; added a matching
`@Mock` field (+import) to the `@InjectMocks` unit test so it wires. Unstubbed `deleteByTenantIdAndEmail`
returns 0 — the correct no-op for those mock-based tests.

Otherwise the plan executed as written.

## Threat register outcome

| Threat ID | Disposition | Status |
|-----------|-------------|--------|
| T-23-12-01 (foreign-tenant shopId tampering) | mitigate | closed — tenant-scoped check, proven |
| T-23-12-02 (unknown userId tampering) | mitigate | closed — directory membership check |
| T-23-12-03 (foreign-shop existence oracle) | mitigate | closed — identical 404 for both cases |
| T-23-12-04 (user_directory bulk email read) | mitigate | closed — masked at the DTO boundary |
| T-23-12-05 (/me self-access) | accept | as designed — caller's own access only |
| T-23-12-06 (GDPR erasure incompleteness) | mitigate | closed — user_directory in the sweep |
| T-23-12-SC (dependency installs) | mitigate | N/A — no new dependencies |

## Requirement status

VSA-04 remains **PENDING** (not marked complete) — consistent with the phase's anti-false-green
discipline: 23-13 (frontend consumes `MyAccessDto`), 23-14, and 23-15 (OpenAPI regen) still
contribute to closing it.

## Known Stubs

None. No hardcoded empty values, placeholders, or unwired data sources introduced.

## Self-Check: PASSED

- All 6 claimed files (1 created, 5 modified source) present on disk.
- All 5 task commits present in history (RED 9b57a9d, GREEN 38ad074, feat 95e67dd, RED dd7fbe1, feat 601116e).
- `grep -c "user_directory" GdprService.java` → 6 (was 0) — GDPR coverage confirmed.
