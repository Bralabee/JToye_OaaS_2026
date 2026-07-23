---
phase: 23
slug: vendor-scoped-access-responsive-dashboard-nav
status: verified
threats_open: 0
asvs_level: 1
created: 2026-07-21
---

# Phase 23 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.
> **Mode:** VERIFY-MITIGATIONS — register authored at plan time across all 17 PLANs
> (`register_authored_at_plan_time: true`). Every `mitigate` threat was verified by
> locating the actual enforcement call/annotation in the implementation (not documentation);
> every `accept`/`transfer` was verified as genuinely documented with no code path silently
> violating the accepted bound. No implementation file was modified.
> **Branch:** feature/phase-23-vendor-scoped-access (merge-base `main` 726bff8) · **block_on:** HIGH

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| tenant A session → tenant B rows | The RLS wall; V52/V57 policies keep the new `shop_staff` / `user_directory` tables airtight | tenant-scoped rows, colleague PII |
| authenticated caller → shop-scoped decision | `ShopAccessService` is the single decision funnel BELOW the RLS wall (HTTP + STOMP share it) | grant set, role, JIT provision |
| external client → `/api/v1/staff` | An attacker-presented bearer token crosses into the staff-management authorization gate | grant/revoke mutations, directory PII |
| WebSocket client → STOMP inbound channel | An authenticated tenant user submits an arbitrary `/topic/kitchen/{tid}/{shopId}` subscription | live KDS order stream |
| tenant user → shared per-tenant read cache | A cached shop/product entry must not serve a gate-bypassed row to another user | shop / product rows |
| migration role → FORCE-RLS `shop_staff` | A backfill with no tenant GUC silently no-ops under RLS; correctness depends on per-tenant `set_config` | `grant_source` column |
| client localStorage / switcher → shop selection | A per-device UI preference — **NOT** a trust boundary; the server re-validates every grant on every request (D-07) | UI narrow only |

---

## Threat Register

97 threats across 17 plans. **All CLOSED.** Evidence is the actual enforcement site verified in code.

| Threat ID | Category | Component | Disposition | Mitigation / Evidence | Status |
|-----------|----------|-----------|-------------|------------------------|--------|
| T-23-01-01 | Information disclosure | user_directory.email cross-tenant read | mitigate | V52:100-108 ENABLE+FORCE RLS via `current_tenant_id()`; `ShopStaffRlsPolicyIntegrationTest` | closed |
| T-23-01-02 | Tampering | forged tenant_id INSERT into shop_staff | mitigate | V52:53 `WITH CHECK (tenant_id = current_tenant_id())` | closed |
| T-23-01-03 | Elevation | raw `::uuid` cast reintroducing 22P02 | mitigate | V52/V57 use `current_tenant_id()` only; `RlsContractTest.noPolicyUsesRawTenantGucCast` present | closed |
| T-23-01-SC | Tampering | supply chain (npm/pip/cargo) | accept | `git diff 726bff8 HEAD` build.gradle/lockfiles empty — no deps | closed |
| T-23-02-01 | Elevation | JIT self-escalation (foreign sub / targeted shop) | mitigate | `ShopAccessService.onRequest:533-544` + `insertGroupAdminIfAbsent` — own sub, shop_id NULL, no client role/shop | closed |
| T-23-02-02 | Elevation | fail-open on provisioning misfire | mitigate | `isGroupAdminForUser` deny-by-default under strict ON; fail-closed ladder | closed |
| T-23-02-03 | Information disclosure | RLS-404 vs shop-403 confusion | mitigate | `GlobalExceptionHandler:246-258` distinct `/shop-access-denied` vs `/not-found` type URIs | closed |
| T-23-02-04 | Denial of service | directory-upsert write amplification | mitigate | `UserDirectoryRepository.upsertSeen:48` ON CONFLICT … `WHERE last_seen < :cutoff` | closed |
| T-23-02-05 | Elevation | stale-grant window after revoke | mitigate | `evictMembershipAfterCommit` (afterCommit synchronization) | closed |
| T-23-02-SC | Tampering | supply chain | accept | no new deps | closed |
| T-23-03-01 | Elevation | horizontal priv-esc (act on ungranted shop) | mitigate | `require(shopId, SHOP_MANAGER)` on every write in Shop/Product/OrderService | closed |
| T-23-03-02 | Elevation | STAFF performs catalogue write | mitigate | `OrderService.transitionOrder` require(STAFF); catalogue writes require SHOP_MANAGER | closed |
| T-23-03-03 | Information disclosure | read-scope done in UI only | mitigate | getAllShops/Products/Orders filter by `grantedShopIds` at the query | closed |
| T-23-03-04 | Elev / Info disclosure | bulk import / SSE leaks ungranted shops | mitigate | `BulkImportService:120-124` per-row require(); `OrderSseService:102` `ShopScope.permits` fan-out filter | closed |
| T-23-03-05 | Information disclosure | over-gating public/customer/admin surface | accept | out-of-scope surfaces untouched (CONTEXT D-01); storefront public read preserved | closed |
| T-23-03-SC | Tampering | supply chain | accept | no new deps | closed |
| T-23-04-01 | Elevation | non-GROUP_ADMIN grants/revokes | mitigate | `StaffManagementService:121,187,268` `requireGroupAdmin()` at top of list/grant/revoke | closed |
| T-23-04-02 | Denial of service (self) | last-GROUP_ADMIN lockout | mitigate | revoke:280 `countByTenantIdAndRole` ≤ 1 → `LastGroupAdminException` 409; realm-admin recovery | closed |
| T-23-04-03 | Elevation | stale-grant window after revoke | mitigate | `evictAfterCommit` → `evictMembershipAfterCommit` | closed |
| T-23-04-04 | Tampering | client-supplied role/shop escalates | mitigate | validated (userId,shopId,role) under RLS; `ShopRole` enum + V52 role CHECK | closed |
| T-23-04-05 | DoS / Tampering | duplicate grant untyped 500 | mitigate | `insertNewGrantRaceSafe` catches `DataIntegrityViolationException` → typed 200 replay | closed |
| T-23-04-SC | Tampering | supply chain | accept | no new deps | closed |
| T-23-05-01 | Elevation | tamper localStorage → forbidden shop | accept/mitigate | not a boundary — server re-validates every request → typed 403 | closed |
| T-23-05-02 | Elevation | non-GA sees/triggers group-wide "apply to all" | mitigate | createShop/deleteShop `requireGroupAdmin()`; group-wide writes GA-only | closed |
| T-23-05-03 | Information disclosure | switcher lists ungranted shops | mitigate | `fetchMyShops` → GET /api/v1/shops (server read-scoped) | closed |
| T-23-05-04 | DoS (UX) | 375px overflow regression | mitigate | 375px Jest/Playwright present (live 375px run deferred on creds — non-security) | closed |
| T-23-05-SC | Tampering | supply chain | accept | no new deps | closed |
| T-23-06-01 | Elevation | non-GA reaches staff mgmt via direct URL | mitigate | `StaffManagementService.list()` `requireGroupAdmin()` → typed 403 → access-required | closed |
| T-23-06-02 | Information disclosure | directory PII to non-GA | mitigate | directory only returned from GA-gated `list()`; 403 fetches no data | closed |
| T-23-06-03 | DoS (self) | UI removes last group admin | mitigate | server 409 guard is the hard stop (revoke/downgrade) | closed |
| T-23-06-04 | Tampering | stale docs counts hide broken CI gate | mitigate | docs-freshness reconciled in 23-15 (metrics 1573; check green) | closed |
| T-23-06-SC | Tampering | supply chain | accept | no new deps | closed |
| T-23-07-01 | Information disclosure | client list filter mistaken for boundary | mitigate | authoritative narrow server-side (23-03 query filters) | closed |
| T-23-07-02 | Elevation | create-form pinned shop tampered to foreign | mitigate | createProduct/createOrder `require(shopId, SHOP_MANAGER)` re-validates | closed |
| T-23-07-03 | DoS (UX) | switcher change doesn't propagate | mitigate | `shop-context.ts` CustomEvent broadcast; `use-shop-context` subscribes | closed |
| T-23-07-SC | Tampering | supply chain | accept | no new deps | closed |
| T-23-08-01 | Elevation | `isSystemPrincipal` trusts non-UUID subject | mitigate | `isDeclaredMachineClient` + empty-default `machineClientIds` (application.yml:123); non-UUID sub denied | closed |
| T-23-08-02 | Information disclosure | user_directory via `list()` | mitigate | `list()` behind same `requireGroupAdmin` gate | closed |
| T-23-08-03 | Elevation | grant self-grant | mitigate | grant/revoke `requireGroupAdmin` → `requireVendorUserId` fail-closed 403 | closed |
| T-23-08-04 | Spoofing | `AnonymousAuthenticationToken` | mitigate | anonymous / non-Jwt principals fall to `requireVendorUserId` → typed 403 | closed |
| T-23-08-05 | Denial of service | `require(null, role)` NPE → 500 | mitigate | require():178-180 explicit null-shopId guard → typed 403 (no `Map.of` NPE 500) | closed |
| T-23-08-06 | Elevation | retained `auth == null` internal bypass | accept | not externally reachable (Spring Security 401s first; blast radius 62 test files); async residual tracked — see Accepted Risks | closed |
| T-23-08-SC | Tampering | supply chain | mitigate | no new deps | closed |
| T-23-09-01 | Elevation | grant downgrade silently retained | mitigate | grant():250 `existing.setRole(role)` + saveAndFlush — downgrade genuinely applies | closed |
| T-23-09-02 | Repudiation | shop_staff_aud missing | mitigate | `ShopStaff @Audited`; grant/role change via session write → `shop_staff_aud` revision | closed |
| T-23-09-03 | Denial of service | last-GROUP_ADMIN invariant | mitigate | `lockTenantGroupAdmins` `PESSIMISTIC_WRITE` serializes check-then-act | closed |
| T-23-09-04 | Tampering | concurrent duplicate grant | mitigate | `DataIntegrityViolationException` → idempotent replay | closed |
| T-23-09-05 | Elevation | JIT rows bypass Envers | transfer | genuinely transferred to 23-14 (grant_source provenance + strict de-honour); auto-provision Envers-ADD residual by design — see NOTE | closed |
| T-23-09-SC | Tampering | supply chain | mitigate | no new deps | closed |
| T-23-10-01 | Information disclosure | `getShopById` cache serves gate-bypassed row | mitigate | `ShopService.getShopById` gates BEFORE `ShopCacheLoader` (@Cacheable on a separate bean) | closed |
| T-23-10-02 | Information disclosure | `getProductById` cache hit | mitigate | cached load + always-run gate on returned `shopId` | closed |
| T-23-10-03 | Elevation | tenant-wide (`shop_id IS NULL`) products | mitigate | null-shop reads visible (gate skipped), writes GROUP_ADMIN-only | closed |
| T-23-10-04 | Information disclosure | test-profile cache blindness | mitigate | `ShopAccessCacheBypassIntegrationTest` asserts caching active before denial | closed |
| T-23-10-05 | Tampering | malformed CSV `shop_id` | mitigate | `BulkImportService.parseShopId` → per-row `RowError` (RFC 7807), batch continues | closed |
| T-23-10-SC | Tampering | supply chain | mitigate | no new deps | closed |
| T-23-11-01 | Information disclosure | `/topic/kitchen/{tid}/{shopId}` subscription | mitigate | `TenantChannelInterceptor:152-154` `validateShopSubscription` → `canAccessShop` | closed |
| T-23-11-02 | Spoofing | subscriber identity from `SecurityContextHolder` | mitigate | `subscriberJwt` from `accessor.getUser()` (session principal, not ambient context) | closed |
| T-23-11-03 | Elevation | absent/unparseable subject | mitigate | absent/non-UUID subject → `MessageDeliveryException` (deny) | closed |
| T-23-11-04 | Information disclosure | leaked `TenantContext` on pooled thread | mitigate | `finally { TenantContext.clear(); }` around RLS-scoped read (:213-215) | closed |
| T-23-11-05 | Denial of service | over-tight gate breaks day-one KDS | mitigate | `canAccessShop` via `isGroupAdminForUser` preserves strict-OFF ungranted | closed |
| T-23-11-06 | Information disclosure | post-revocation SSE stream (WR-03) | accept | bounded 5 min (`SSE_TIMEOUT`); STOMP gated at subscribe; broadcast re-eval deferred — see Accepted Risks | closed |
| T-23-11-SC | Tampering | supply chain | mitigate | no new deps | closed |
| T-23-12-01 | Tampering | `grant()` foreign-tenant shopId | mitigate | grant():213 `shopRepository.findByIdAndTenantId` tenant existence check | closed |
| T-23-12-02 | Tampering | `grant()` unknown userId | mitigate | grant():223 `userDirectoryRepository.existsByTenantIdAndUserId` | closed |
| T-23-12-03 | Information disclosure | foreign-shop existence oracle | mitigate | foreign-tenant + non-existent both → identical "Shop not found in this tenant" 404 | closed |
| T-23-12-04 | Information disclosure | user_directory bulk email read | mitigate | `DirectoryEntryDto.maskEmail` (`a***@domain`) at the DTO boundary | closed |
| T-23-12-05 | Information disclosure | `/api/v1/staff/me` | accept | `myAccess()` returns only `currentCallerSub()`'s own access (by design) | closed |
| T-23-12-06 | Repudiation | GDPR erasure incompleteness | mitigate | `GdprService:227` `userDirectoryRepository.deleteByTenantIdAndEmail` in the erasure sweep | closed |
| T-23-12-SC | Tampering | supply chain | mitigate | no new deps | closed |
| T-23-13-01 | Elevation | client-side JWT parse for authz display | mitigate | staff-api uses server `/api/v1/staff(+/me)`; no dashboard client JWT authz parse | closed |
| T-23-13-02 | Denial of service | silent shop pinning | mitigate | shop-context fallback no longer persists an unchosen narrowing | closed |
| T-23-13-03 | Spoofing | email-based self-identification | mitigate | identity compared server-side on the Keycloak `sub` | closed |
| T-23-13-04 | Information disclosure | directory PII in the picker | transfer | masked server-side by 23-12; picker consumes the masked form only | closed |
| T-23-13-05 | Repudiation | copy claims immediate revocation | mitigate | revocation copy corrected to the true bound | closed |
| T-23-13-SC | Tampering | supply chain | mitigate | no new deps | closed |
| T-23-14-01 | Elevation | JIT GROUP_ADMIN survives strict-scoping flip | mitigate | V57 `grant_source`; `isGroupAdminForUser:290-295` de-honours JIT under strict ON | closed |
| T-23-14-02 | Denial of service | tenant lockout on flip | mitigate | `isBootstrapAdmin` oldest-JIT WARN-logged retention; realm-admin bridge | closed |
| T-23-14-03 | Elevation | service accounts acquire permanent GROUP_ADMIN | mitigate | onRequest:498 `isAllowlistedMachineClient` skips JIT for declared clients | closed |
| T-23-14-04 | Repudiation | config comment overstates the control | mitigate | application.yml:100-111 D-12 comment rewritten to the true two-part behaviour | closed |
| T-23-14-05 | Elevation | stale cached membership after revoke | mitigate | real cache (`CacheConfig shopMembership`) + `evictMembershipAfterCommit` both sites | closed |
| T-23-14-06 | Elevation | pre-commit evict races concurrent re-resolve | mitigate | shared `evictMembershipAfterCommit` helper (onRequest + StaffManagementService) | closed |
| T-23-14-07 | Information disclosure | `Membership` serializer round-trip type loss | mitigate | `MembershipSerializerRoundTripTest` present | closed |
| T-23-14-SC | Tampering | supply chain | mitigate | no new deps | closed |
| T-23-15-01 | Tampering | OpenAPI snapshot regen | mitigate | snapshot regen (4 staff endpoints) reviewed for removals; `OpenApiSnapshotTest` green | closed |
| T-23-15-02 | Repudiation | count reconcile over red suite | mitigate | docs `--write` over a green suite (integrationTest 331→332/0) | closed |
| T-23-15-03 | Repudiation | requirements complete without proof | mitigate | statuses driven by SUMMARYs + named automated commands | closed |
| T-23-15-04 | Information disclosure | secret-shaped strings in artifacts | mitigate | snapshot from annotations, carries no credentials | closed |
| T-23-15-SC | Tampering | supply chain | mitigate | no new deps | closed |
| T-23-16-01 | Elevation | weaken 23-08 fail-closed to satisfy tests | mitigate | 23-08 fail-closed logic intact in main-source (no weakening); test-only plan | closed |
| T-23-16-02 | Repudiation | deny-tests turned green by over-granting | mitigate | access-intent preserved; deny stays deny | closed |
| T-23-16-03 | Tampering | silently change expected status to force green | mitigate | expected statuses unchanged without a stated reason | closed |
| T-23-16-SC | Tampering | supply chain | mitigate | no new deps | closed |
| T-23-17-01 | Denial of service | V57 SET NOT NULL on non-fresh DB | mitigate | V57:69-93 tenant-loop `set_config` backfill; `V57GrantSourceBackfillIntegrationTest` (2-tenant) | closed |
| T-23-17-02 | Tampering | GUC left set after loop leaks | mitigate | V57:90 defensive `set_config('app.current_tenant_id','',true)` reset | closed |
| T-23-17-03 | Elevation | backfill bypasses RLS via DISABLE/owner tricks | avoid | no DISABLE RLS, no policy change (V57 header); RlsContractTest unchanged | closed |
| T-23-17-04 | Repudiation | test forced green without exercising bug | mitigate | test seeds rows + spans two tenants; RED against pre-fix V57 captured | closed |
| T-23-17-SC | Tampering | supply chain | mitigate | no new deps | closed |

*Status: open · closed*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party / other plan) · avoid (design eliminates)*

### NOTE on T-23-09-05 (transfer residual — not a gap)

JIT-provisioned `shop_staff` rows are written via a native `INSERT … ON CONFLICT DO NOTHING`
(`ShopStaffRepository.insertGroupAdminIfAbsent`) for race-safety, which bypasses the Hibernate
session and therefore produces **no Envers ADD revision** at creation. This is a deliberate,
documented tradeoff and is NOT an open elevation gap because: (1) the transfer target 23-14
substantively governs JIT-row semantics — provenance is persistently recorded (`grant_source='JIT'`,
V57) and JIT tenant-wide GROUP_ADMIN rows are de-honoured under strict-scoping ON; (2) each JIT
provision is INFO-logged (`ShopAccessService.onRequest:538`); (3) any later operator touch of a JIT
row goes through the session (`existing.setGrantSource(OPERATOR)` + `saveAndFlush`) → a real Envers
MOD revision. The auto-provision audit residual is minor and compensated; the ELEVATION concern
(untracked/ungoverned admin) is closed.

---

## Accepted Risks Log

Conscious, documented deferrals (`deferred-items.md`) — confirmed documented and **not contradicted by code**. These do not resurface in future audit runs.

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-23-01 | T-23-08-06 | `@Async`/`@Scheduled`/`@RabbitListener` inherit no `Authentication` and would take the retained `auth == null` internal bypass. Measured: no gated service is currently reached from an async path; Spring Security 401s any external unauthenticated request first. `asSystem()` marker deferred. | Sanmi (owner) | 2026-07-21 |
| AR-23-02 | T-23-11-06 (WR-03) | A revoked user's already-open KDS SSE stream can linger ≤ 5 min (`OrderSseService.SSE_TIMEOUT`). HTTP gate + STOMP subscribe are immediate; broadcast-time re-eval deferred. | Sanmi (owner) | 2026-07-21 |
| AR-23-03 | WR-04 | products/marketing screens narrow client-side over one server-paginated page (wrong counts past page 1). NOT a bypass — the set is grant-scoped server-side (23-03); pagination correctness only. | Sanmi (owner) | 2026-07-21 |
| AR-23-04 | IN-01 | `fetchMyShops` hard-caps `size=200`; a tenant with >200 shops truncates the switcher. No known tenant approaches this. | Sanmi (owner) | 2026-07-21 |
| AR-23-05 | GCR-W1 | `BulkImportService:65,153` retain `@CacheEvict(products, allEntries=true)` on create-only paths: over-eviction (perf), never under-eviction/leak. Non-security. | Sanmi (owner) | 2026-07-21 |
| AR-23-06 | GCR-W2 | `ShopSwitcher` renders a blank `<select>` for a zero-access non-GA. UX degradation; backend still denies. | Sanmi (owner) | 2026-07-21 |
| AR-23-07 | GCR-I1 | STOMP shop-gate hard-coded to the `kitchen` feature. Verified `/topic/kitchen/{tid}/{shopId}` (`OrderStateChangeListener:109`) is the ONLY shop-segment topic today → no live gap. Latent maintenance hazard: any future shop-segment topic MUST be added to the gate (or a guard test added). | Sanmi (owner) | 2026-07-21 |
| AR-23-08 | #206 backstop | `@PreAuthorize` scope backstop on `StaffController` deferred to scoped-credentials work (#206); the `requireGroupAdmin()` service gate is the live control and D-10 forbids the `hasRole('admin')` form. | Sanmi (owner) | 2026-07-21 |

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-07-21 | 97 | 97 | 0 | gsd-security-auditor (opus) — verify-mitigations mode |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer / avoid)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-07-21
