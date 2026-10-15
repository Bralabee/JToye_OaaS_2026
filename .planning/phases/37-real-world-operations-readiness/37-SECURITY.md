---
phase: "37"
slug: "real-world-operations-readiness"
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 2
block_on: medium
threats_total: 175
threats_closed: 175
register_authored_at_plan_time: true
created: "2026-10-15"
---

# Phase 37 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.
> Built by `/gsd-secure-phase 37` (State B, from the 53 PLAN `<threat_model>` blocks and the SUMMARY `## Threat Flags`).
> Verified at ASVS L2 by seven parallel `gsd-security-auditor` runs (plans 01-08, 09-16, 17-24, 25-31, 32-38, 39-45, 46-53).
> Every CLOSED row cites code or test evidence as file:line, which the auditors read on branch `phase-37-ops-readiness`. No row relies on a SUMMARY claim alone.
> Paths are shortened to the file name; full paths are in the code tree under `core-java/`, `frontend/`, `edge-go/`, `mcp-server/`, `scripts/` and `k8s/`. `evidence/` is this phase directory's evidence folder.

**Verdict: SECURED. `threats_open: 0`.** T-37-59 (high) was open at the first audit. It was fixed on this branch in 43e908ae (RED tests), 85b6e190 (fix) and 15932090 (evidence). An independent re-audit on 2026-10-15 closed it; see "Resolved Threats".

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| origin/main → integration branch (37-01) | Merged code becomes the base every Phase 37 change is proven against | commits, migration reservations |
| test suite / fixtures → access decisions (37-02, 37-03) | A suite booted under the wrong strict-scoping value, or fixtures that grant too much, prove nothing | booted `strictScoping`, `shop_staff` seed rows |
| deploy config → core (37-04) | Env and configmap decide the access posture | `ACCESS_STRICT_SCOPING` |
| vendor token → ShopAccessService / directory upsert (37-04, 37-05) | Authenticated user with no grant; token claims written to `user_directory` | JWT sub, realm roles, tenant_id, email, name |
| Group admin → staff list / invites / credentials (37-05, 37-07, 37-45) | Sees colleagues' computed access; issues future grants and machine identities | masked emails, EffectiveAccess, invitee email, role, shop, client secret (returned once) |
| core → SMTP → invitee inbox (37-07) | Link is a bearer credential until used or expired | `#token={tenantId}.{token}` fragment |
| anonymous browser → public invite API (37-08, 37-09) | Link holder with no JWT; tenant pinned from the link | ref, names, password |
| core → Keycloak admin API (37-08, 37-44, 37-45) | Master-realm admin credentials create users and clients | user representation, password, client definition, tenant_id mapper |
| staff token → order delete/void, bulk cancel, pause (37-10, 37-20, 37-29) | Changes financial records and shop state | order state, void reason, ledger REVERSAL, pause fields |
| migration role → FORCE-RLS tables (37-10, 37-11, 37-19) | Backfills must reach every tenant | entry_kind, shop_id, hours classification |
| shop manager token → ledger reads; CSV → spreadsheet (37-11) | Per-shop financial data; vendor text opened in Excel | summary, list, CSV cells |
| vendor upload / copy → products (37-13, 37-39, 37-40) | CSV rows, partial bodies and sync batches that name shops | product rows, shop_id, stock, allergen data |
| customer notes → kitchen DOM (37-16) | Untrusted free text shown to staff | order.notes |
| query string → sign-in redirect (37-18) | callbackUrl is attacker-controlled | callbackUrl |
| internet client → ingress → core (37-24, 37-28) | X-Forwarded-For is attacker-controlled unless a trusted proxy appended it; client IP becomes an HMAC digest | XFF, socket peer, IP digest |
| internet → edge (37-25, 37-41) | Anonymous flood pre-auth; error bodies relayed outward | raw requests, validated tenant_id, 4xx problem+json |
| anonymous browser → guest order (37-26, 37-27, 37-32, 37-33) | Untrusted quantities, contact details and claimed prices; server price is charged | basket, email, phone, expected prices |
| customer token → verified flag (37-27, 37-30) | Only a verified token marks a contact verified | customer JWT, `contact_verified` |
| server → public pages, crawlers, reviews (37-22, 37-35, 37-36, 37-51) | Statements about a business, reviewer names and order status published | openNow, JSON-LD, "First L.", status labels |
| core → vendor webhook endpoint / tenant contact (37-43) | Outbound deliveries and pause notices | signed payloads, cleaned URL |
| MCP agent → core (37-42) | Orders and reads started by a machine | order create with `submit`, `shopId` |
| API response → browser memory (37-46) | Secret shown once | client secret |
| evidence file → repository (37-47) | Must never contain a live secret | lengths and sha256 prefixes only |
| branch → delivered runtime (37-15, 37-23, 37-31, 37-38, 37-50, 37-53) | Stale images certify nothing | image ids, LastTagTime, jar and `.next` contents |

---

## Threat Register

175 threats across 53 plans (T-37-SC repeats once per plan as the supply-chain row). Severity: 4 critical, 102 high, 50 medium, 19 low. Disposition: 169 mitigate, 6 accept.

| Plan | Threat ID | Category | Component | Severity | Disposition | Mitigation (plan) | Evidence (verified) | Status |
|------|-----------|----------|-----------|----------|-------------|-------------------|---------------------|--------|
| 37-01 | T-37-01 | Tampering | a stale or unexpected base tree | medium | mitigate | Fetch rc checked (VOID on failure), code-path conflicts stop the plan, baseline suites green before any change | 37-01-baseline.md:14-25 fetch rc=0, merge-base re-checked (cc860dc5 ancestor of afee7b04 rc=0) | closed |
| 37-01 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed in this plan | no manifest/lockfile in the 50 (37-0N) commits; control rc=0 | closed |
| 37-02 | T-37-02 | Repudiation | access tests passing under a leaked OFF value | high | mitigate | capture/restore in all 11 classes + booted-value instrument shown red then green in both modes | StrictScopingGuard.java:46-66; StrictScopingBootedValueIntegrationTest.java:56-74 | closed |
| 37-02 | T-37-03 | Elevation of Privilege | a test seeding access through a superuser connection, hiding an RLS gap | medium | mitigate | ShopGrants writes under the pinned GUC on the application role, never as superuser | ShopGrants.java:71-79 pin + read-back; ShopGrantsIntegrationTest.java:90-95,149,154 (NOSUPERUSER, 42501) | closed |
| 37-02 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 12a7333e..b53e65cb empty | closed |
| 37-03 | T-37-04 | Elevation of Privilege | tests granted GROUP_ADMIN where a shop role suffices, masking per-shop gaps | medium | mitigate | Recipe grants the exact shop and role; refusal-assertion counts unchanged per class | ScopedWriteAccessIntegrationTest.java:117,209 single-shop grants; refusal assertions kept | closed |
| 37-03 | T-37-05 | Elevation of Privilege | seeded service-account grant leaking outside dev | medium | mitigate | Seeder runs only in the dev profile (existing gate); grant is per shop, not tenant-wide; IT proves a non-granted shop is still 403 | DemoDataSeeder.java:112 @Profile("dev"), :855 per-shop grants; DemoDataSeederServiceAccountGrantIntegrationTest.java:173-181 | closed |
| 37-03 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff bd193ca2..739b69d7 empty | closed |
| 37-04 | T-37-06 | Elevation of Privilege | implicit tenant-wide GROUP_ADMIN for every login (UXT-004) | critical | mitigate | Default ON in every profile; IT under the shipped default; JIT stops | application.yml:265 strict default; ShopAccessService.java:114,534,854; StrictScopingDefaultIntegrationTest.java:128-148 | closed |
| 37-04 | T-37-07 | Tampering | a runtime overriding the default back to false | high | mitigate | check-strict-scoping-default.sh in CI, fail-closed (exit 2 on missing input), red on broken copies | scripts/check-strict-scoping-default.sh:76-79,223-235; ci-cd.yaml:987-990; re-run rc=0 real / rc=1 broken / rc=2 VOID | closed |
| 37-04 | T-37-08 | Denial of Service | a tenant locked out by the flip | medium | mitigate | V57 bootstrap admin retained and WARN-logged; realm admin backstop; IT arm | ShopAccessService.java:556-572 bootstrap admin kept + WARN; StrictScopingDefaultIntegrationTest.java:209-242 | closed |
| 37-04 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff c02a3ef9..8034ae89 empty | closed |
| 37-05 | T-37-09 | Information Disclosure | Staff list shows effective access diverging from enforcement | high | mitigate | effectiveAccessFor uses the decision path itself; break arm proves raw-row derivation is caught | StaffManagementService.java:162-172 -> ShopAccessService.java:688-706 (isGroupAdminForUser) | closed |
| 37-05 | T-37-10 | Elevation of Privilege | NULL-shop non-admin rows honoured more broadly than intended | high | mitigate | tenantWideRole caps at the row's role; STAFF write arm proves 403; GROUP_ADMIN semantics and JIT de-honouring unchanged | ShopAccessService.java:243-253 rank + tenant wall; Membership.java:57-66; StaffEffectiveAccessIntegrationTest.java:246-266,290 | closed |
| 37-05 | T-37-11 | Denial of Service | directory write failure breaking every read request | medium | mitigate | REQUIRES_NEW best-effort, failures logged and swallowed; test double arm | UserDirectoryToucher.java:77 REQUIRES_NEW, :101-112 failure swallowed; StaffEffectiveAccessIntegrationTest.java:146 | closed |
| 37-05 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 5164f5fa..8f8b3b6d empty | closed |
| 37-06 | T-37-12 | Information Disclosure | Staff page misstating access (say ≠ data) | medium | mitigate | Renders server effectiveAccess only; fail arm without the field | effective-access-cell.tsx:107-110 "Not recorded"; staff-page.test.tsx:610-624 | closed |
| 37-06 | T-37-13 | Repudiation | NoAccessPage claiming "no longer" without evidence | low | mitigate | In-memory observed-access flag; fail arm | shop-switcher-provider.tsx:87,115; no-access-page.tsx:83; no-access-page.test.tsx:95 | closed |
| 37-06 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 31ece774..034328bd empty | closed |
| 37-07 | T-37-14 | Spoofing | stolen/replayed invite token | high | mitigate | Digest at rest only, 32-byte random, 72h TTL, single use (37-08), resend kills old link | V77:43 digest column; StaffInviteService.java:68-69,78-79 (72h),184,208,211-216; StaffInviteAcceptService.java:94-98 | closed |
| 37-07 | T-37-15 | Elevation of Privilege | non-admin issuing invites | high | mitigate | requireGroupAdmin on every invite endpoint; SHOP_MANAGER 403 arm | StaffInviteService.java:113,140,156,176 (all 4 endpoints); StaffInviteIntegrationTest.java:259-273 | closed |
| 37-07 | T-37-16 | Information Disclosure | cross-tenant invite read via RLS gap | high | mitigate | ENABLE+FORCE via current_tenant_id(); NOSUPERUSER proof with break arm; no exemption | V77:74-81 ENABLE+FORCE via current_tenant_id(); StaffInviteRlsIntegrationTest.java:86-176; no EXEMPT_TABLES entry (rg -uu rc=1) | closed |
| 37-07 | T-37-17 | Information Disclosure | invitee email or token in logs | medium | mitigate | Mailer and service log event names only; grep criterion | StaffInviteService.java:125,163,186,228 ids only; EmailNotificationService.java:274-308 | closed |
| 37-07 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 70a5a695..28710c62 empty | closed |
| 37-08 | T-37-18 | Spoofing | brute-force or guessed invite links | high | mitigate | 256-bit token, digest lookup under the pinned tenant, public per-IP limit (429 arm), one 404 for all causes | StaffInviteAcceptService.java:272,380-387; RateLimitInterceptor.java:138,309-312; GlobalExceptionHandler.java:1129-1138 single 404 body | closed |
| 37-08 | T-37-19 | Elevation of Privilege | user edits own tenant_id via account console | critical | mitigate | Declared attribute, edit admin-only, no ENABLED unmanaged policy; template check + live negative test in 37-15 | realm-export.template.json:1633 tenant_id admin-only; evidence/37-15-gate-37B.md:426-429 live 400 with control (staging/prod realm re-import is a deploy residual, 37-USER-SETUP.md:24-27) | closed |
| 37-08 | T-37-20 | Elevation of Privilege | accepting into another business with an email already used elsewhere | high | mitigate | Vendor-realm lookup: other tenant -> 409, nothing written | StaffInviteAcceptService.java:191-192,229,322-333 | closed |
| 37-08 | T-37-21 | Tampering | double accept creating two grants | medium | mitigate | Single-use conditional UPDATE, rowcount 1; concurrent arm | StaffInviteAcceptService.java:94-98,197,210 conditional UPDATE must hit 1 row | closed |
| 37-08 | T-37-22 | Information Disclosure | password logged or echoed | high | mitigate | WRITE_ONLY field, toString omits it, char[] cleared, KC errors carry message only | AcceptStaffInviteRequest.java:31-42 WRITE_ONLY + redacted toString; StaffInviteAcceptService.java:178 char[] zeroed; KeycloakAdminClient.java:309-316,347-359 | closed |
| 37-08 | T-37-23 | Elevation of Privilege | widened use of core's master-realm admin credentials | medium | accept | Already held for offboarding/DSAR; recorded residual: a least-privilege realm-management service account is a follow-up | residual recorded at 37-08-PLAN.md:260 (see Accepted Risks Log) | closed (accepted risk) |
| 37-08 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 0d8bc261..610d9bbe empty | closed |
| 37-09 | T-37-24 | Information Disclosure | token leaking via Referer or third-party loads | medium | mitigate | Referrer-Policy no-referrer on /invite, no third-party resources, replaceState drops the token, header snapshot pinned | frontend/next.config.mjs:64-67 no-referrer on /invite; header-snapshot.test.ts:54-82; invite-client.tsx:175-182 replaceState | closed |
| 37-09 | T-37-25 | Information Disclosure | token in frontend access logs (path segment) | low | accept | UI-SPEC fixes the route shape; single-use, 72h TTL and digest-only storage bound the exposure; recorded residual | 37-09-PLAN.md:205; token now in URL fragment (frontend/app/invite/page.tsx:9-13) | closed (accepted risk) |
| 37-09 | T-37-26 | Information Disclosure | password persisted or echoed client-side | high | mitigate | Component state only; storage spy asserts zero writes; error copy never interpolates the value | invite-client.tsx:341,357,375 uncontrolled ref, cleared; invite-client.test.tsx:300-332 | closed |
| 37-09 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff e9a7ec7a..75228793 empty | closed |
| 37-10 | T-37-27 | Repudiation | deleting a completed order to hide a sale (UXT-018) | high | mitigate | 409 guard for every post-PENDING status; void requires reason and records who/when; _aud mirrors | OrderService.java:90 DELETABLE_STATUSES, :954-959 409, :896-898,916-918 void who/when/reason; V78:56-76 _aud mirrors | closed |
| 37-10 | T-37-28 | Elevation of Privilege | non-admin voiding | high | mitigate | requireGroupAdmin + SCOPE_orders:write; SHOP_MANAGER 403 arm | OrderService.java:893 requireGroupAdmin; OrderController.java:489 SCOPE_orders:write; OrderVoidIntegrationTest.java:360,473 | closed |
| 37-10 | T-37-29 | Tampering | backfill silently skipping tenants (bare UPDATE under FORCE RLS) | high | mitigate | Tenant loop with set_config; two-tenant stepwise test with bare-UPDATE break arm | V78:93-107 per-tenant set_config loop; LedgerEntryKindMigrationIntegrationTest.java:62-150 (NOSUPERUSER, 2 tenants) | closed |
| 37-10 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 495c8180..50860c85 empty | closed |
| 37-11 | T-37-30 | Information Disclosure | manager reading another shop's or the tenant's figures | high | mitigate | require(shopId, SHOP_MANAGER) with shop filter; no-shopId requires Group admin; cross-shop and STAFF 403 arms | FinancialTransactionController.java:214-222 authorise, :116,131; FinancialTransactionRepository.java:170; FinanceShopScopeIntegrationTest.java:163,182,385 | closed |
| 37-11 | T-37-31 | Tampering | CSV formula injection via references | medium | mitigate | Leading = + - @ neutralised; exact-text test | FinanceCsvExporter.java:76-82 formula neutralisation on :50,51,56; FinanceShopScopeIntegrationTest.java:400-430 | closed |
| 37-11 | T-37-32 | Repudiation | backfill skipping tenants | high | mitigate | Tenant loop; two-tenant stepwise test with bare-UPDATE break arm | V79:66-82 tenant loop; LedgerShopBackfillMigrationIntegrationTest.java:66 | closed |
| 37-11 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 4fcb54f2..14c83fd5 empty | closed |
| 37-12 | T-37-33 | Information Disclosure | banner claiming a narrower scope than the data shown | medium | mitigate | Banner reads the API's echoed scope; disagreement fail arm | finance/page.tsx:139,179,319-325 echoed scope; finance-scope.test.tsx:181,189,376 | closed |
| 37-12 | T-37-34 | Elevation of Privilege | void control shown to non-admins | low | mitigate | UI gate from staff/me.groupAdmin; the server refuses anyway (37-10 403 arm) | OrderDetailPanel.tsx:168-171,517,530; void-order-dialog.test.tsx:136-140; server OrderService.java:893 | closed |
| 37-12 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 0462bf80..d659795a empty | closed |
| 37-13 | T-37-35 | Elevation of Privilege | CSV row or copy targeting a shop the caller does not manage | high | mitigate | require(shop, SHOP_MANAGER) on the default shop, each row's shop and both copy shops; 403 arms | BulkImportService.java:105-107,163-167; ProductService.java:691-692; ProductCopyIntegrationTest.java:313,321; BulkImportShopDefaultIntegrationTest.java:196,205 | closed |
| 37-13 | T-37-36 | Tampering | allergen data altered on copy | high | mitigate | Verbatim field copy through createProduct; SQL fidelity assertions with break arm | ProductService.java:772-795,801-808 verbatim copy; ProductCopyIntegrationTest.java:185-210 | closed |
| 37-13 | T-37-37 | Denial of Service | duplicate copies under concurrency | low | mitigate | Target shop row lock + skip rule; concurrent arm | ProductService.java:697 PESSIMISTIC_WRITE (ShopRepository.java:55-57); ProductCopyIntegrationTest.java:276 | closed |
| 37-13 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 504b0db3..58a41a6e empty | closed |
| 37-14 | T-37-38 | Repudiation | showing VAT for an unregistered seller (misleading price statement) | medium | mitigate | Display gated on vatNumber; fail-arm test | checkout/page.tsx:795,1238,1611; orders/[orderNumber]/page.tsx:262,553 VAT rows gated on vatNumber; vat-registration.test.tsx:199,245,274 | closed |
| 37-14 | T-37-39 | Tampering | import landing in a shop the user did not pick | low | mitigate | Target shown explicitly; request carries shopId; server gates (37-13) | products/import/page.tsx:137,158,235; products-api.ts:91-95; server BulkImportService.java:106 | closed |
| 37-14 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 88d5f5dd..1c8ee404 empty | closed |
| 37-15 | T-37-40 | Repudiation | green specs against a stale runtime | high | mitigate | LastTagTime + in-jar config before; rebuild all; check-runtime-freshness 0 unverified after | evidence/37-15-gate-37B.md:298-310 RED, :330-345 rebuild, :366-371 freshness rc=0 0 unverified | closed |
| 37-15 | T-37-41 | Denial of Service | disrupting another session's stack | medium | mitigate | Blocking-human checkpoint before any stack action | 37-15-PLAN.md:115 blocking checkpoint; evidence/37-15-gate-37B.md:149-151,290 | closed |
| 37-15 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 67c0b9b4..96d5a94b empty | closed |
| 37-16 | T-37-42 | Tampering | stored XSS through customer notes on the new card | high | mitigate | Rendered as a React text node only; inert-HTML jest arm (goods P2-KYL-P1) | kds-customer-note.tsx:37-42 text node; no dangerouslySetInnerHTML in kitchen src (rg -uu rc=1, control hits); kds-card-additions.test.tsx:169-170 | closed |
| 37-16 | T-37-43 | Elevation of Privilege | accepting orders of an ungranted shop | medium | mitigate | Existing confirm gate (STAFF on the shop + SCOPE_orders:write); the lane reads the board's single shop | kds-new-lane.tsx:362 -> OrderController.java:309, OrderService.java:733 STAFF; bulk-cancel OrderController.java:421, OrderService.java:696 | closed |
| 37-16 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 2eb959cf..da1985e0 empty | closed |
| 37-17 | T-37-44 | Repudiation | board claiming Live/sound while not live (missed orders) | high | mitigate | Precedence table test with fail arm; non-dismissible sound bar | feed-state.ts:154-166; kitchen/page.tsx:784-787; kds-sound.tsx:304-313; feed-state.test.ts:154-180 | closed |
| 37-17 | T-37-45 | Denial of Service | alert loop running after accept (alarm fatigue -> mute) | low | mitigate | Loop gated on pending count; zero-starts-after-accept arm | kds-sound.tsx:238-246 loop only while pendingCount>0; kds-sound.test.tsx:103-112 | closed |
| 37-17 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 7 plan commits, 0 manifest changes | closed |
| 37-18 | T-37-46 | Spoofing | open redirect via callbackUrl | medium | mitigate | Single-leading-slash rule; hostile-URL table test | lib/safe-return-to.ts:43-63; app/auth/signin/page.tsx:31 sole reader; signin/__tests__/page.test.tsx:228-290 | closed |
| 37-18 | T-37-47 | Information Disclosure | revoked user's open socket still receiving order frames | low | accept | Board disconnects on the first 403; frames carry id/number/status only; recorded residual (subscribe-time check is the server rule) | residual in PLAN and SUMMARY:370; board drops topic on stop (kitchen/page.tsx:576) | closed (accepted risk) |
| 37-18 | T-37-48 | Denial of Service | silent kitchen after session lapse (missed orders) | high | mitigate | Claimed lapse event, loud overlay + alarm, silent refresh before expiry; live spec | session-lapse.ts:26-30 -> api-client.ts:166-167 -> kitchen/page.tsx:268-276; kds-board-stopped.tsx:40; e2e/kitchen-session-lapse.spec.ts:128 | closed |
| 37-18 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 10 plan commits, 0 manifest changes | closed |
| 37-19 | T-37-49 | Tampering | backfill updating 0 rows under FORCE RLS while reporting success | high | mitigate | Tenant loop with set_config; two-tenant stepwise test with bare-UPDATE break arm | V80:137-139 per-tenant set_config, :209-211 fails if unclassified; V80OpeningScheduleBackfillIntegrationTest.java:104-115,159 | closed |
| 37-19 | T-37-50 | Repudiation | migration fabricating a vendor statement (24/7 hours) | medium | mitigate | LEGACY_ALWAYS_OPEN written only by the migration; never by the application (37-20 asserts) | ShopMapper.java:26,40 hoursSource never from request; ShopService.java:485; ShopPauseIntegrationTest.java:469-486 | closed |
| 37-19 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 7 plan commits, 0 manifest changes | closed |
| 37-20 | T-37-51 | Elevation of Privilege | an ungranted user pausing a competitor's shop | high | mitigate | require(shopId, STAFF); 403 arm | ShopService.java:499,525 require(shopId, STAFF) first; no MCP pause tool (rg -uu rc=1); ShopPauseIntegrationTest.java:217-236 | closed |
| 37-20 | T-37-52 | Tampering | storefront order slipping past a pause or closed hours | medium | mitigate | Policy enforced server-side before the reservation; IT arms | PublicStorefrontService.java:833 before reservation :850; ShopAvailabilityPolicy.java:108-127 fails closed; ShopPauseIntegrationTest.java:113,171 | closed |
| 37-20 | T-37-53 | Denial of Service | indefinite pause abused to close a shop | low | accept | Only granted staff can pause; Resume is one tap; pause state shown on the dashboard and shops page | STAFF-gated (ShopService.java:499,525); one-tap resume; shops/page.tsx:645 | closed (accepted risk) |
| 37-20 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 9 plan commits, 0 manifest changes | closed |
| 37-21 | T-37-54 | Repudiation | banner showing a pause the server does not hold (client timer) | medium | mitigate | Banner from server fields; re-read at expiry; fail arm | pause-orders-control.tsx:45-54,94-128 server fields only; pause-orders.test.tsx:208,308,329,377 | closed |
| 37-21 | T-37-55 | Tampering | double tap sending conflicting pause/resume | low | mitigate | Controls disabled while in flight (backstop test) | pause-orders-control.tsx:92,132-148,219-244; ShopService.java:506-514 absolute write; pause-orders.test.tsx:346 | closed |
| 37-21 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 15 plan commits, 0 manifest changes | closed |
| 37-22 | T-37-56 | Repudiation | publishing 24/7 hours the vendor never declared | medium | mitigate | JSON-LD omitted for legacy shops; F9 fail arm | lib/structured-data.ts:84-85 omits hours for legacy shops; structured-data.test.ts:144 | closed |
| 37-22 | T-37-57 | Information Disclosure | stale cached page showing Open for a paused shop | low | mitigate | SSR fetch is no-store (measured); client reads server fields | storefront-server.ts:83,94 no-store; storefront-server.test.ts:128-143 | closed |
| 37-22 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 8 plan commits, 0 manifest changes | closed |
| 37-23 | T-37-58 | Repudiation | kitchen specs green against a stale runtime | high | mitigate | Identity before, rebuild all, freshness 0 unverified after, RED/GREEN recorded | evidence/37-23-gate-37A.md:254 freshness rc=1 before, :301 PASS 4/4 0 unverified | closed |
| 37-23 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 7 plan commits, 0 manifest changes | closed |
| 37-24 | T-37-59 | Spoofing | XFF rotation evading per-IP limits (UXT-006) | high | mitigate | Trust-none default; rightmost-untrusted walk; IT through the interceptor; no container valve ahead of the resolver | application.yml:682 `forward-headers-strategy: none` (no other setter, rg -uu); ForwardHeadersGuard.java:46-54,73-75 runs after Boot's order-0 customizer and refuses a non-none strategy or any RemoteIpValve; ClientIpResolver.java:104-137,144-156 is the only trust list; TrustedProxyForwardedProtoFilter.java:57-68 scheme only from trusted peers; re-audit run: KubernetesForwardHeadersTest 3/3, ForwardHeadersGuardTest 5/5, TrustedProxyForwardedProtoFilterTest 5/5, both env break arms refused (rc=1), PublicRateLimitKubernetesXffSpoofIntegrationTest 1/1; evidence/37-53-T-37-59-fix.md | closed |
| 37-24 | T-37-60 | Spoofing | operator configuring 0.0.0.0/0 or a public range | high | mitigate | Render invariant fails; bean refuses /0 at startup | ClientIpResolver.java:157-158,168-173 refuses /0; check-render-invariants.sh:558-630,1449-1477 INV-12; ci-cd.yaml:692; ClientIpResolverTest.java:212-228 | closed |
| 37-24 | T-37-61 | Denial of Service | all clients sharing the ingress bucket while staging/prod CIDRs are unset | medium | accept | Staging/production are not deployed yet (Phases 29/32); user_setup records the operator input and the invariant warns | user_setup 37-24-PLAN.md:26-31; warning check-render-invariants.sh:1461; staging/prod CIDRs empty (configmap-patch.yaml:90,98) | closed (accepted, pending owner) |
| 37-24 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 8 plan commits, 0 manifest changes | closed |
| 37-25 | T-37-62 | Denial of Service | anonymous traffic starving every tenant's sync (UXT-039) | high | mitigate | Per-tenant buckets after auth; larger pre-auth valve only as a flood guard | edge-go/cmd/edge/main.go:284 pre-auth valve, :314-316 validate then tenantRateLimiter; ratelimit_tenant_test.go:180 | closed |
| 37-25 | T-37-63 | Denial of Service | memory growth from forged/many tenant ids | medium | mitigate | Bounded map with eviction + overflow bucket; tenant id only after JWT validation | ratelimit_tenant.go:70,157-166 cap 10000 + eviction + overflow bucket; ratelimit_tenant_test.go:274 | closed |
| 37-25 | T-37-64 | Spoofing | tenant id taken from an unvalidated header | high | mitigate | Read only from c.Get("tenant_id") set by jwtMiddleware.Validate() | ratelimit_tenant.go:224 reads c.Get("tenant_id") only; written only at internal/middleware/jwt.go:213 after verification | closed |
| 37-25 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package or Go module added | c66a9faf..6fdf3a8b no manifest (rc=1); control found build.gradle.kts in Phase 38 commits | closed |
| 37-26 | T-37-65 | Tampering | absurd quantities polluting dashboards and ledgers (UXT-042) | medium | mitigate | OrderLimits on all three writers; vendor break arm | orderLimits.check at OrderService.java:334 and PublicStorefrontService.java:1248 (only two new Order() sites); OrderLimitsIntegrationTest.java:172-232 | closed |
| 37-26 | T-37-66 | Tampering | fake reviews across shops of one tenant (UXT-005) | high | mitigate | order.shopId == shop.id; typed 400; IT | review/ReviewService.java:86-87 order shop == reviewed shop; GlobalExceptionHandler.java:1030-1034; ReviewCrossShopIntegrationTest.java:103 | closed |
| 37-26 | T-37-67 | Denial of Service | refusal leaving an idempotency reservation | medium | mitigate | Thrown inside the reserved work; same-key retry arm | PublicStorefrontService.java:850-853 inside executeWithoutStoringResponse (IdempotencyService.java:146-162); OrderLimitsIntegrationTest.java:106 | closed |
| 37-26 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | eb7c2bb8..3e50f029 no manifest (rc=1) | closed |
| 37-27 | T-37-68 | Denial of Service | fake cash-order flood (UXT-041) | high | mitigate | Per-shop open-order caps by email and phone; typed 429; IT arms | CashOrderCaps.java:53-71; PublicStorefrontService.java:1348; GlobalExceptionHandler.java:1012-1020 typed 429; CashOrderCapIntegrationTest.java:147,196 | closed |
| 37-27 | T-37-69 | Spoofing | a forged token marking a contact verified | medium | mitigate | CustomerJwtVerifier signature + verified-email requirement; mismatch arm | PublicStorefrontController.java:269-277 CustomerJwtVerifier; PublicStorefrontService.java:1266-1268 email match; application.yml:442; CashOrderCapIntegrationTest.java:381 | closed |
| 37-27 | T-37-70 | Information Disclosure | pseudonymous IP digest copied into audit history | medium | mitigate | @NotAudited field, no _aud column (grep criterion) | Order.java:227-229 @NotAudited; V81:92-93 mirrors only two columns; no _aud client_ip_digest (rg -uu rc=1); CashOrderCapIntegrationTest.java:447 | closed |
| 37-27 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 0449b35b..68be2323 no manifest (rc=1) | closed |
| 37-28 | T-37-71 | Information Disclosure | IP stored or reversible from a plain hash | high | mitigate | Keyed HMAC-SHA256; never the raw IP; no _aud copy; IT scans the row | ClientIpDigester.java:58-59,89-99 HMAC-SHA256; PublicStorefrontService.java:823 digest before write; ClientIpCapIntegrationTest.java:155,334 | closed |
| 37-28 | T-37-72 | Information Disclosure | digest kept indefinitely (retention) | medium | mitigate | Nulled at terminal states, by the 72 h sweep in every tenant, and by erasure | OrderService.java:762-764; ClientIpDigestRetentionSweep.java:61-64,89,107-124; GdprService.java:302; ClientIpDigestRetentionIntegrationTest.java:133-184 | closed |
| 37-28 | T-37-73 | Information Disclosure | weak or default key in a runtime | high | mitigate | 64-hex validation at startup; compose :? guard; verify-env.sh; sealed secret (user_setup) | ClientIpDigester.java:70-73 refuses non-64-hex key; docker-compose.full-stack.yml:325; verify-env.sh:72,81; k8s/base/core-java-deployment.yaml:535-539 non-optional (staging/prod sealed secret: accepted-pending-owner) | closed |
| 37-28 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 5423a35e..d7e72448 no manifest (rc=1) | closed |
| 37-29 | T-37-74 | Elevation of Privilege | cancelling orders of an ungranted shop or tenant via a batch | high | mitigate | STAFF check per order; non-disclosing NOT_FOUND; IT arm | OrderService.java:690-697 RLS + permits(shopId, STAFF); OrderController.java:421 orders:write; OrderBulkCancelIntegrationTest.java:224 | closed |
| 37-29 | T-37-75 | Information Disclosure | internal "suspected fake" reason sent to a customer | medium | mitigate | Reason stored only on the order; no-leak arm with break | OrderService.java:754-755 reason on order row only; absent from notification/webhook/storefront/DTOs (rg -uu rc=1, control hits); OrderBulkCancelIntegrationTest.java:324 | closed |
| 37-29 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 7ebf8594..5998bec3 no manifest (rc=1) | closed |
| 37-30 | T-37-76 | Information Disclosure | customer token sent to the wrong origin | medium | mitigate | Sent only on the publicApiClient guest-order POST to core, exactly as /orders/mine does today | frontend/app/api/customer-orders/route.ts:85,100-101,108 HttpOnly cookie, server-only header, slug pattern | closed |
| 37-30 | T-37-77 | Denial of Service | client auto-retrying a cap 429 into a loop | low | mitigate | Distinct type; request-count arm | frontend/lib/api-client.ts:138 retry only on rate-limited; public-api-client.ts:11-14; abuse-refusals.test.tsx:186 | closed |
| 37-30 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 1fba16a1..9e1d3d42 package files unchanged (rc=1) | closed |
| 37-31 | T-37-78 | Repudiation | abuse bounds "proven" on a stale runtime | high | mitigate | Identity before, rebuild all, freshness 0 unverified, live RED/GREEN | evidence/37-31-gate-37D.md 2.1-2.6 identity, RED, rebuild, PASS 0 unverified, image id = tag id | closed |
| 37-31 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 9942de0d..9f3cda75 no manifest (rc=1) | closed |
| 37-32 | T-37-122 | Tampering | idempotency fingerprint drift turning in-flight retries into 422s across the deploy | medium | mitigate | NON_NULL on both fields; golden green with fixtures untouched; fail direction shown without NON_NULL | GuestOrderItemRequest.java:33, GuestOrderRequest.java:129 NON_NULL; IdempotencyFingerprintGoldenTest.java:74-75; jackson2-golden diff empty | closed |
| 37-32 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff fa5db105..5759d26e empty | closed |
| 37-33 | T-37-79 | Tampering | order recorded at a price the customer never saw (UXT-007) | high | mitigate | Equality check in the same transaction; typed 409; reprice race arm | PublicStorefrontService.java:1131-1140,1217-1224; GlobalExceptionHandler.java:1086-1100; GuestOrderBasketRevalidationIntegrationTest.java:122-175 | closed |
| 37-33 | T-37-80 | Tampering | client lowering expected prices to pay less | medium | mitigate | Expected prices are only compared; the charge is always the server price (P2-KYL-P2 arm) | PublicStorefrontService.java:1139 charges server price; GuestOrderBasketRevalidationIntegrationTest.java:181-198 | closed |
| 37-33 | T-37-81 | Information Disclosure | another shop's product named in the diff | medium | mitigate | REMOVED carries the id only; identical to a non-existent id | BasketChangedException.java:77-79 id-only; PublicStorefrontService.java:1100-1102; GuestOrderBasketRevalidationIntegrationTest.java:346-350 | closed |
| 37-33 | T-37-82 | Denial of Service | refusal leaving an idempotency reservation | medium | mitigate | Thrown inside the reserved work; same-key retry arms | PublicStorefrontService.java:850-853,1080,1219 inside idempotent work; GuestOrderBasketRevalidationIntegrationTest.java:159-161,230,352 | closed |
| 37-33 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 22d5a56a..a6c77b8e empty | closed |
| 37-34 | T-37-83 | Tampering | basket ownership stamp lost or swapped on update (R-16 class) | medium | mitigate | applyServerChanges leaves the stamp; content assertion through a sign-in transition | cart-provider.tsx:304-317,425-430; cart-provider-identity.test.tsx:475-501 | closed |
| 37-34 | T-37-84 | Tampering | duplicate order from rapid taps around the 409 | medium | mitigate | Same Idempotency-Key; refuse-until-accepted; e2e counts orders | checkout/page.tsx:904-908,946-954,1037 same key; basket-changed.test.tsx:360-392; e2e/basket-revalidation.spec.ts:352-356 | closed |
| 37-34 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 92b4b972..8f576254 empty | closed |
| 37-35 | T-37-85 | Information Disclosure | full reviewer names published (UXT-044) | medium | mitigate | Read-time display rule; body-wide surname absence assertion | ReviewService.java:189 -> ReviewerDisplayName.java:49-75; PublicReviewerNameIntegrationTest.java:106,128 | closed |
| 37-35 | T-37-86 | Repudiation | advertised discounts never applied (UXT-008, consumer-protection exposure) | medium | mitigate | Public promotion reads empty; no re-enable switch | PublicStorefrontService.java:274,284-287 List.of(); PublicPromotionsHiddenTest.java:109-130 | closed |
| 37-35 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 5124bbed..200d7058 empty | closed |
| 37-36 | T-37-87 | Repudiation | discounts shown but not applied (consumer protection) | medium | mitigate | Rendering removed client and SSR; F17 fail arm; announcement helper copy | shop-detail-client.tsx:310; storefront-server.ts:147-151; types/storefront.ts:208; shop-promotions-hidden.test.tsx:147,228 | closed |
| 37-36 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 321cbfed..5483dd41 empty | closed |
| 37-37 | T-37-88 | Tampering | duplicate order from a refresh on the confirmation | medium | mitigate | Route only reads; no-POST spy arm | orders/[orderNumber]/page.tsx:148,175 GET only; order-confirmation.test.tsx:139-145,215-228; e2e/public-a11y.spec.ts:549-577 | closed |
| 37-37 | T-37-89 | Information Disclosure | order data exposed via the marker or route | low | mitigate | Marker holds the number only; tracking stays bound to number + email (F3) | frontend/lib/just-placed.ts:28-31,40-45 order number only; PublicStorefrontController.java:338-341 number+email | closed |
| 37-37 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 70e528f0..f337abff empty | closed |
| 37-38 | T-37-90 | Repudiation | checkout integrity "proven" on a stale runtime | high | mitigate | Identity before, rebuild all, freshness 0 unverified, RED/GREEN recorded | evidence/37-38-gate-37C.md:192-258,507-529; check-runtime-freshness.sh:407-410 | closed |
| 37-38 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | manifest diff 7e618eee..efc9fa54 empty | closed |
| 37-39 | T-37-91 | Tampering | partial update disabling stock tracking (UXT-016) | high | mitigate | IGNORE-null mapping + explicit trackInventory; IT | ProductMapper.java:94 IGNORE-null; ProductService.java:404-409,479-499; ProductPartialUpdateIntegrationTest.java:152 | closed |
| 37-39 | T-37-92 | Tampering | stock-only integration overwriting allergen data | high | mitigate | Narrow PATCH DTO refusing unknown properties; SQL-unchanged assertion | ProductPatchRequest.java:53-56; ProductService.java:442-449 refuses unknown props; IT :246,264,279 | closed |
| 37-39 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 8 plan commits, 0 manifest changes; control b3b87a47 fires | closed |
| 37-40 | T-37-93 | Tampering | products orderable at every shop with wrong allergen data (UXT-017) | high | mitigate | shopId required; createOrder refuses shop-less; IT | SyncService.java:244-248 SHOP_REQUIRED; OrderService.java:282; OrderShopLessProductIntegrationTest.java:96,109 | closed |
| 37-40 | T-37-94 | Elevation of Privilege | sync writing into an ungranted shop | high | mitigate | Per-item require(shopId, SHOP_MANAGER); REJECTED SHOP_ACCESS_DENIED | SyncService.java:181-182,253 SHOP_MANAGER before SKU lookup; SyncBatchShopRequiredIntegrationTest.java:131-132 | closed |
| 37-40 | T-37-95 | Repudiation | batch reporting SUCCESS while dropping items (UXT-037) | medium | mitigate | Per-item results; status rule; break arm | BatchSyncResponse.java:43-45; SyncService.java:105-109,180; SyncBatchShopRequiredIntegrationTest.java:118,256 | closed |
| 37-40 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 7 plan commits, 0 manifest changes | closed |
| 37-41 | T-37-96 | Information Disclosure | relaying an internal core error body outward | low | mitigate | Only 4xx application/problem+json bodies (already client-facing at core) are relayed; 5xx stays the edge's generic 502 | edge-go/internal/core/client.go:126,131-137,191-204 4xx problem+json only, 64 KiB cap; handlers.go:228-230; handlers_test.go:394 | closed |
| 37-41 | T-37-97 | Repudiation | integrator told SUCCESS when items were rejected | medium | mitigate | Results and status pass through; contract gate | client.go:226-231; handlers.go:236-241; contract_test.go:75-88 VOIDs on nothing checked | closed |
| 37-41 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package or Go module added | 5 plan commits, no go.mod/package changes | closed |
| 37-42 | T-37-98 | Tampering | agent orders bypassing the kitchen's accept step | low | mitigate | submit -> PENDING only; the vendor still accepts every order (no auto-confirm, D-11) | OrderService.java:374-375 SUBMIT only; OrderStateMachineConfig.java:49-51; OrderCreateSubmitIntegrationTest.java:119-125 | closed |
| 37-42 | T-37-99 | Spoofing | query-string injection through list_products args | medium | mitigate | uuid validation + allow-listed URLSearchParams keys (T-20-04 pattern) | mcp-server/src/tools/list-products.ts:28-31 uuid, :52-56 allow-listed params; ProductController.java:68; list-products.test.ts:123-153 | closed |
| 37-42 | T-37-100 | Tampering | idempotency fingerprint drift breaking in-flight agent retries | medium | mitigate | NON_NULL submit; golden green with fixtures untouched | CreateOrderRequest.java:79 NON_NULL; IdempotencyFingerprintGoldenTest.java:73; golden dir untouched | closed |
| 37-42 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 10 plan commits, 0 manifest changes | closed |
| 37-43 | T-37-101 | Denial of Service | events lost when an endpoint pauses (UXT-038) | high | mitigate | HELD + resume re-queue; IT | WebhookDeliveryWorker.java:194-199,309-311; WebhookFanoutListener.java:178-180; WebhookDeliveryRepository.java:40; WebhookAutoPauseIntegrationTest.java:331 | closed |
| 37-43 | T-37-102 | Repudiation | owner never told of a pause | medium | mitigate | One email per AUTO_PAUSED transition; WARN when no contact | WebhookDeliveryWorker.java:315-338 after-commit email + WARN; IT :358,428 | closed |
| 37-43 | T-37-103 | Denial of Service | a flapping endpoint triggering repeated pause emails | low | mitigate | Email only on the transition; resume required before another pause | WebhookDeliveryWorker.java:292-299 ACTIVE->AUTO_PAUSED only; WebhookSubscriptionService.java:145-151; IT :389 | closed |
| 37-43 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 7 plan commits, 0 manifest changes | closed |
| 37-44 | T-37-104 | Elevation of Privilege | credential token for the wrong tenant | critical | mitigate | Hardcoded tenant_id claim set from the issuing tenant server-side; content assertion; live decode in 37-47 | KeycloakAdminClient.java:647-662 hard-coded tenant_id mapper; IntegrationCredentialService.java:155,450 TenantContext; KeycloakAdminClientTest.java:551-557 | closed |
| 37-44 | T-37-105 | Spoofing | token without the core-api audience accepted elsewhere / refused by core | medium | mitigate | Audience mapper mandatory; break arm | KeycloakAdminClient.java:570-590,639-640,665-676 audience mapper + read-back; KeycloakAdminClientTest.java:564-568 | closed |
| 37-44 | T-37-106 | Information Disclosure | client secret in logs or exception text | high | mitigate | Messages name the realm only; no logging of bodies | KeycloakAdminClient.java:774-784 realm-only messages; KeycloakAdminClientTest.java:934-959 | closed |
| 37-44 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 6 plan commits, 0 manifest changes | closed |
| 37-45 | T-37-107 | Elevation of Privilege | credential minting credentials or staff (escalation chain) | critical | mitigate | HumanCallerGuard on every credential and staff mutation; role ceiling SHOP_MANAGER; IT arms | HumanCallerGuard.java:68-76 fail-closed on azp; requireHuman first in IntegrationCredentialController.java:82,105,125,142, StaffController.java:104,125, StaffInviteController.java:69,102,117; IT :527,558,599 | closed |
| 37-45 | T-37-108 | Information Disclosure | secret persisted, cached or replayed | high | mitigate | Returned once with no-store; executeWithoutStoringResponse; SQL scan arm | IntegrationCredentialController.java:85,107 no-store; IntegrationCredentialService.java:162-165; IdempotencyService.java:205-210; CredentialSecretDto.java:53-56 | closed |
| 37-45 | T-37-109 | Information Disclosure | cross-tenant credential visibility | high | mitigate | ENABLE+FORCE RLS; NOSUPERUSER proof with break arm | V82:87-95 ENABLE+FORCE RLS; IntegrationCredentialRlsIntegrationTest.java:123,163-205 (NOSUPERUSER, 42501) | closed |
| 37-45 | T-37-110 | Spoofing | tenant choosing a Keycloak clientId to impersonate another client | medium | mitigate | kc_client_id generated server-side, UNIQUE | IntegrationCredentialService.java:389-394 SecureRandom clientId; V82:58,65 UNIQUE + prefix check; CreateCredentialRequest.java:22-35 | closed |
| 37-45 | T-37-111 | Elevation of Privilege | widened use of core's master-realm admin credentials | medium | accept | Same credentials as offboarding/DSAR/invites; residual recorded; least-privilege realm-management account is a follow-up | 37-45-PLAN.md:247; 37-45-SUMMARY.md:417 | closed (accepted risk) |
| 37-45 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 12 plan commits + all 69 37-39..37-45 commits, 0 manifest changes | closed |
| 37-46 | T-37-112 | Information Disclosure | secret lingering in DOM, storage or caches | high | mitigate | Component state only, dropped on close; F13 storage/DOM assertion; server no-store | developers/page.tsx:321-323; secret-reveal-dialog.tsx:120-146; IntegrationCredentialController.java:85,107; developers-page.test.tsx:166-170 | closed |
| 37-46 | T-37-113 | Elevation of Privilege | Developers page reachable by non-admins | low | mitigate | Sidebar gating + refusal copy; the server refuses anyway (37-45) | sidebar.tsx:74,92; page.tsx:105-124; server requireHuman + requireGroupAdmin (IntegrationCredentialService.java:154,230,251,290) | closed |
| 37-46 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 42ffb5f5..da50cead 0 manifest changes | closed |
| 37-47 | T-37-114 | Information Disclosure | credential secret written into committed evidence | high | mitigate | Record length + sha256 prefix only; grep criterion; gitleaks in CI | evidence/37-47-gate-37E.md:359,374,383 length + sha256 prefix only, :540-553 absence check with control; gitleaks re-run rc=0 (planted control rc=1) | closed |
| 37-47 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | cb29dc24..2c9e7ec2 0 manifest changes | closed |
| 37-48 | T-37-115 | Tampering | delivery ordered from a collection-only shop via the API | medium | mitigate | requireOffered on both writers; vendor-path break arm | FulfilmentPolicy.java:139 requireOffered called at OrderService.java:245 and PublicStorefrontService.java:1035; FulfilmentOfferedIntegrationTest.java:133,152 | closed |
| 37-48 | T-37-116 | Tampering | negative fees or thresholds | low | mitigate | @Min(0) + typed 400 | CreateShopRequest.java:106,112 @Min(0); ShopController.java:100,133 @Valid; FulfilmentOfferedIntegrationTest.java:281 | closed |
| 37-48 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | f665ac90..34ce2929 0 manifest changes | closed |
| 37-49 | T-37-117 | Repudiation | fees not shown before ordering (drip pricing) | low | mitigate | Server-driven fee preview at checkout; P2-REG-17 regression in the gate | checkout/page.tsx:697-701; lib/delivery-fee.ts:33-45 = FulfilmentPolicy.java:114-126; fulfilment-offered.test.tsx:210,220 | closed |
| 37-49 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 9a3cf989..f236319e 0 manifest changes | closed |
| 37-50 | T-37-118 | Repudiation | shop-admin fixes "proven" on a stale runtime | high | mitigate | Identity before, rebuild all, freshness 0 unverified, RED/GREEN recorded | check-runtime-freshness.sh:582-594 per-service VOID; evidence/37-50-gate-37G.md:285-347,507-534 | closed |
| 37-50 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | d0d2730a..a99d3209 0 manifest changes | closed |
| 37-51 | T-37-119 | Information Disclosure | titles or announcements exposing more than the page shows | low | mitigate | Titles use the order number and shop name already on the page; tracking stays bound to number + email | lib/storefront-step-title.ts:16-19; lib/order-status-announcement.ts:31-35; PublicStorefrontController.java:336-341 number+email | closed |
| 37-51 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | b68f0505..b0c01276 0 manifest changes | closed |
| 37-52 | T-37-120 | Denial of Service | keyboard/screen-reader customers blocked on a core journey (Equality Act exposure) | medium | mitigate | Named cards, focus management, non-obscuring banner, wrapping text; instrumented spec with fail arms | shop-card.tsx:101-102; shop-detail-client.tsx:111-114,278; bottom-notice-shell.tsx:130-152; a11y-storefront.spec.ts:64,273; evidence/37-53-gate-37F.md:350-370,437-440 | closed |
| 37-52 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | d9661e86..5d7c3549 0 manifest changes | closed |
| 37-53 | T-37-121 | Repudiation | phase declared done while a valued behaviour regressed | high | mitigate | All 40 goods re-run on the final rebuilt runtime; any red stops completion | evidence/37-53-goods-40.md:9 40/40; regression fixed and re-proven evidence/37-53-gate-37F.md:498-520,614-690 | closed |
| 37-53 | T-37-SC | Tampering | npm/pip/cargo installs | high | mitigate | No package installed | 602a1cfb..8a25330d 0 manifest changes; whole 42ffb5f5..HEAD 0 (control b3b87a47 finds 6) | closed |

*Status: open · closed · open — below medium threshold (non-blocking)*
*Severity: critical > high > medium > low. Only open threats at or above workflow.security_block_on (medium) count toward threats_open.*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Resolved Threats

### T-37-59 (37-24, Spoofing, high): on Kubernetes, Tomcat's RemoteIpValve decided the client IP before ClientIpResolver ran

**Status: CLOSED on 2026-10-15** by the independent re-audit of commits 43e908ae, 85b6e190 and 15932090. The reviewer did not write the fix.

**The finding (first audit).**
- `server.forward-headers-strategy` was unset. 37-24 believed that kept Tomcat's RemoteIpValve off.
- Spring Boot 4.1.1's `TomcatWebServerFactoryCustomizer` deduces the setting from the cloud platform when it is unset. KUBERNETES, which every pod is detected as, deduces it ON.
- So every k8s overlay installed the valve. It trusts all private and loopback ranges and rewrote the remote address, scheme and host from X-Forwarded-* before the resolver ran.
- The commit's RED arm measured `remote=198.51.100.1|scheme=https|host=evil.example` from a loopback peer, and a rotated-XFF flood was allowed 40 of 40.

**(a) Is it closed on Kubernetes? Yes.**
- **The property is set.** `core-java/src/main/resources/application.yml:682` sets `forward-headers-strategy: none`. `rg -uu` over `core-java/src/main/resources`, `k8s`, the compose files and `infra` finds no other setting of it and no `server.tomcat.remoteip.*` (rc=0; the only hits are this line, comments and constants).
- **The guard runs last.** In the Boot 4.1.1 jar (`spring-boot-tomcat-4.1.1.jar`, `javap`), `TomcatWebServerFactoryCustomizer.getOrder()` returns 0, and `customizeRemoteIpValve` adds the valve through `ConfigurableTomcatWebServerFactory.addEngineValves`. `ForwardHeadersGuard.java:73-75` orders itself `LOWEST_PRECEDENCE`, so it runs after Boot's customizer. It then checks both the input (`:46`, the strategy must be `none` in any case; unset is refused) and the result (`:47-54`, any `RemoteIpValve` in `getEngineValves()` is refused).
- **No other path rewrites the client address.** `rg -uu` over `core-java/src/main`, `k8s`, compose, `infra` and `edge-go` finds no `setRemoteAddr`, no `ForwardedHeaderFilter`, no other `addEngineValves` or `addContextValves`, and no `TomcatContextCustomizer` (rc=0; hits are the guard and comments only). `ClientIpResolver.java:104-137` reads `getRemoteAddr()` and believes XFF only from its own trusted list (`isTrustedProxy`, `:144-156`).
- **Tests and break arms, all run by this reviewer** (JDK 25, `./gradlew :core-java:test`, build dir `build-local`, `--rerun`; result XMLs time-stamped this run):
  - Unit run: `KubernetesForwardHeadersTest` 3/3, `ForwardHeadersGuardTest` 5/5 and `TrustedProxyForwardedProtoFilterTest` 5/5, rc=0.
  - `SERVER_FORWARD_HEADERS_STRATEGY=native` gave rc=1: the context refused to start with "T-37-59: server.forward-headers-strategy must be 'none'".
  - `SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER=X-Forwarded-For` gave rc=1: the context refused with "T-37-59: a Tomcat RemoteIpValve is in the engine pipeline".
  - A closing clean run gave 3/3, rc=0.
  - `PublicRateLimitKubernetesXffSpoofIntegrationTest`, run by `:core-java:integrationTest` with Testcontainers, passed 1/1 (rc=0). It boots with `spring.main.cloud-platform=kubernetes` on a random port and sends real HTTP. Exactly the capacity is allowed, and the first 429 lands at index = capacity (`:128-142`).
- **The precondition arm works.** `KubernetesForwardHeadersTest.java:77-84` asserts the context really is KUBERNETES, so the green result is not a context that fell back to no platform.

**(b) Does the scheme filter open anything new? No admissible finding.** `TrustedProxyForwardedProtoFilter.java` behaves as follows:
- It believes `X-Forwarded-Proto` only when the socket peer passes `ClientIpResolver.isTrustedProxy` (`:57`). With the empty `trusted-proxies` default this is never true, so compose is a no-op; `defaultResolver_trustsNoOne_soComposeIsUntouched` asserts it is the same request object.
- It needs exactly one header line (`:60-64`). A repeated header is ignored, and so is a comma-joined value, because "https, http" is not exactly http or https.
- Case is folded and the value is trimmed, then only `http` or `https` is accepted (`:65-68`).
- It never touches the remote address or the host.
- `ForwardedProtoFilterConfig.java:22` registers it at `HIGHEST_PRECEDENCE`, before Spring Security's filter chain (default order -100). So HSTS and `isSecure()` see the restored scheme, and no authorisation decision depends on the scheme.

**(c) Does any new code read X-Forwarded-Host? No.** No main-code reader of X-Forwarded-Host or X-Forwarded-Port exists (`rg -uu -i 'forwarded-host'` over `core-java/src/main`: comments only). The host comes from the request's own `Host` header (`getServerName()`), and `KubernetesForwardHeadersTest.java:119` asserts `host=127.0.0.1` against `X-Forwarded-Host: evil.example`.

**Non-blocking observations from the re-audit (no threat reopened):**
1. **The management port is not guarded.** Prod and staging bind actuator on a separate `management.server.port` (`application-prod.yml:121`, `application-staging.yml:119`). That child server is not inspected by `ForwardHeadersGuard`; the tests only inspect the main engine. It reads the same environment, so `none` applies to it too. Any override that would install a valve there also trips the guard on the main server, and actuator does not use `ClientIpResolver`. So no exposure, only a gap in coverage.
2. **Scheme trust depends on the ingress.** The filter's trust is only as good as the ingress's handling of a client-supplied `X-Forwarded-Proto`. If a declared trusted proxy passed the client's value through unchanged (for example ingress-nginx with `use-forwarded-headers: true`), a client could choose the scheme. The impact is limited to `Location` URLs and HSTS emission, and this applies only once the owner declares the proxy CIDRs (AR-37-05).
3. **A port quirk.** `ForwardedSchemeRequest.getServerPort` (`:90-97`) maps the socket scheme's default port to the forwarded scheme's default. So an explicit `Host: x:80` under a trusted `https` reads as port 443. This is benign.

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-37-01 | T-37-23 (37-08, medium) | Core's master-realm admin credentials are used more widely (vendor user creation). They are already held for offboarding and DSAR. The follow-up is a least-privilege realm-management service account. Recorded only in the 37-08 PLAN register (37-08-PLAN.md:260); there is no deferred-items entry. | plan register (37-08) | 2026-10-15 |
| AR-37-02 | T-37-25 (37-09, low) | Invite token in frontend access logs. It is single-use, has a 72 h TTL and is stored only as a digest. Since 37-08 it travels in the URL fragment, so it is in no request line. | plan register (37-09) | 2026-10-15 |
| AR-37-03 | T-37-47 (37-18, low) | A revoked user's open socket keeps receiving id/number/status frames until it closes. The server checks on SUBSCRIBE, and the board drops its topic on the first 403. | plan register (37-18) | 2026-10-15 |
| AR-37-04 | T-37-53 (37-20, low) | An indefinite pause could be used to close a shop. Only STAFF can pause, resume is one tap, and the pause is shown on the dashboard and shops page. | plan register (37-20) | 2026-10-15 |
| AR-37-05 | T-37-61 (37-24, medium) | Staging/production `TRUSTED_PROXY_CIDRS` are unset (`configmap-patch.yaml:90,98`) because those environments are not deployed yet (Phases 29/32). The 37-24 user_setup records the operator input, and `check-render-invariants.sh:1461` warns. **Pending owner.** With T-37-59 fixed, unset CIDRs on k8s mean every request through the ingress keys on the ingress pod's address (one shared bucket), as the plan stated. | owner (pending) | 2026-10-15 |
| AR-37-06 | T-37-111 (37-45, medium) | Same master-realm credentials, now also used to manage clients. The residual is recorded at 37-45-PLAN.md:247 and 37-45-SUMMARY.md:417, with the same least-privilege follow-up. | plan register (37-45) | 2026-10-15 |
| AR-37-07 | T-37-73 residual (37-28) | Staging/production `CLIENT_IP_HMAC_KEY` sealed secrets (`client-ip-credentials/hmac-key`) are not created yet. This fails closed: core refuses to start without a 64-hex key (`ClientIpDigester.java:70-73`), and the k8s secretKeyRef is non-optional. **Pending owner.** | owner (pending) | 2026-10-15 |
| AR-37-08 | 37-30 / 37-31 H4 | Proxy-peer IP cap while `TRUSTED_PROXY_CIDRS` is empty. Signed-in cash orders go through the Next `/api/customer-orders` proxy, so they share one IP digest per shop. If the owner settles H4 by trusting the frontend's range, note that `route.ts:104-105` forwards the client's XFF, so an ingress that overwrites XFF is a precondition. **Pending owner.** | owner (pending) | 2026-10-15 |
| AR-37-09 | T-37-107 / T-37-113 residual (37-45, 37-47) | The core-api client has `serviceAccountsEnabled: true` in the dev realm and carries the dashboard `azp`, so it passes HumanCallerGuard (`HumanCallerGuard.java:68-71`). It still needs a Group-admin grant, and none is issued to a service account. Staging/production `keycloak.client-id` must equal the dashboard's client id there. Carried in `evidence/37-47-gate-37E.md:231-238`. **Pending owner.** | owner (pending) | 2026-10-15 |
| AR-37-10 | T-37-19 deploy residual (37-08) | The `tenant_id` admin-only user profile is in the realm TEMPLATE. Staging/production realms need a re-import (`37-USER-SETUP.md:24-27`), and `unmanagedAttributePolicy` must never be ENABLED. This fails closed: an admin-API user with no tenant sees nothing. | plan register (37-08) | 2026-10-15 |

*Accepted risks do not resurface in future audit runs.*

---

## Unregistered Flags (warnings, no threat ID; not counted in threats_open)

Each flag is new attack surface reported in a SUMMARY `## Threat Flags` section or found by an auditor, with no register entry.

1. **37-38 / V84: RLS read widening.** `V84__order_items_guest_tracking.sql:43-62` adds an anonymous SELECT policy on `order_items`.
   - It is SELECT only and needs both tracking GUCs.
   - It is re-checked through an EXISTS on `orders`.
   - The owner chose option A.
   - Proven by `GuestTrackingOrderLinesRlsIntegrationTest` (8 tests, NOSUPERUSER NOBYPASSRLS).
   - It should get a register entry.
2. **37-45: `CredentialLastUsedRecorder.java:123-131` fails open on a DB error** in its revoked-credential refusal.
   - Today revocation also deletes the grant, and strict scoping is on by default (`application.yml:265`), so a revoked credential is refused.
   - If strict scoping were ever turned off, a fully ungranted account becomes an implicit admin (`ShopAccessService.java:534`). This filter would then be the only wall until the token expires.
3. **37-29: amplification.** One staff token can cause up to 100 state changes, outbox rows and emails per request. This is bounded by `BulkCancelLimit` (typed 400) and core's per-tenant `RateLimitInterceptor` (`WebConfig.java:71-72`). The 37-29 SUMMARY's claim that the edge limiter bounds it is wrong: the dashboard calls core directly.
4. **37-30: `POST /api/customer-orders` is a new unauthenticated same-origin proxy.** It forwards the client's X-Forwarded-For (`route.ts:104-105`). Cross-site use is limited by the `SameSite=Lax` cookie (`customer-auth-cookies.ts:35`). See AR-37-08.
5. **37-09: `GET /api/v1/staff/me` returns `businessName`** to any authenticated caller of the tenant, including one with no shop grant (`MyAccessDto.java:40,69`). It is read by the token's tenant only.
6. **37-15 (fixed outside the plans): a no-grant user could read and create customers under D-06.** This is now closed: `CustomerController.java:79,93,116,150,167` are gated (quick tasks `3af4bedc`, `8c79c178`).
7. **37-07 / 37-08:**
   - **GDPR retention.** `staff_invite.email_normalised` is kept on accepted, cancelled and expired rows, which are never deleted (V77:65-66).
   - **Unbounded resends.** Invitation resends have no per-address cap (only the tenant limiter and the Group-admin role).
   - **No per-token attempt cap** on the public invite preview/accept. The per-IP public limiter applies, and the token is 256-bit.
8. **37-05: Group admins can see realm-admin status and computed access.** This is by design (D-09), behind `requireGroupAdmin()`, with masked emails.
9. **37-06: a payload-free window event** fires on each shop-access-denied 403 (`api-client.ts:175`).
10. **37-46 / 37-48:**
    - `CredentialDto.serviceAccountUserId` is exposed to Group-admin humans; the same id is already in `GET /staff`.
    - A 409 `shop-slug-taken` confirms that a slug exists across tenants. This is low risk and no wider than the old `duplicate`.
11. **37-51: server-side `GET /public/shops/{slug}` on every basket, checkout and order page.** All these calls leave from one frontend address and share one per-client bucket. On a 429 or 5xx the title drops the shop name.
12. **37-18 / 37-21:**
    - The session carries the access token's `expiresAt`; the refresh token is still removed.
    - The kitchen re-reads the shop every 60 s.

**Observations recorded by auditors (no threat reopened):**
- **T-37-72:** `RefundService.java:206` moves an order to REFUNDED without clearing its IP digest. The 72 h sweep still bounds retention.
- **T-37-69:** `application-dev.yml:36` defaults `require-verified-email` to false. This predates the phase, and the setting is true outside dev.
- **T-37-22:** `KeycloakAdminClient.java:295` copies the password into a String that cannot be zeroed. The declared char[] control is in place.
- **T-37-18:** the public limiter fails open if Redis is down (pre-existing). The 256-bit token keeps guessing infeasible.
- **T-37-114:** gitleaks missed a `client_secret = "<32 chars>"` line when the previous line holds a keyword key (0/15 found, against 30/30 for the line alone). The control that actually protects the evidence is length plus a sha256 prefix.
- **T-37-121:** the full 40-goods run used frontend image `516c3320`. The later layout fix `291fda9d` was re-checked on the rebuilt image by three a11y specs, but the full 40 were not run again.
- **T-37-120:** a pre-existing 4px horizontal scroll at 360px/130% (`deferred-items.md:841`).
- **Re-run break arms:** the auditors could not edit files, so they did not re-run the break arms that the SUMMARYs report (for example removing `NON_NULL` or disabling the accept gate). Where evidence cites one, the auditors checked that the control and its test exist in code; the RED half rests on the recorded evidence files.

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-10-15 | 175 | 174 (168 mitigated + 6 accepted, 4 of them pending owner) | 1 (T-37-59, high, blocking) | /gsd-secure-phase 37, 7 parallel gsd-security-auditor runs (opus), ASVS L2, block_on medium |
| 2026-10-15 (re-audit) | 175 | 175 (169 mitigated + 6 accepted, 4 of them pending owner) | 0 | independent re-audit of the T-37-59 fix (43e908ae, 85b6e190, 15932090): code review against the Boot 4.1.1 bytecode, 3 unit classes 13/13, 2 env break arms refused, closing clean run, Testcontainers IT 1/1 |

**Run notes:**
- **Step 4 user gate (AskUserQuestion):** answered with the recommended option, "Verify all open threats", on orchestrator instruction.
- **Step 5 OPEN_THREATS accept/block choice:** T-37-59 is recorded as OPEN, not accepted, on orchestrator instruction. Fixes are recorded, not applied.
- **T-37-59 fix:** the owner chose to fix T-37-59 in the 37-F PR. Another agent implemented it and first edited this file's T-37-59 row and `threats_open`. This reviewer, who did not write the fix, re-verified it and rewrote the row's evidence, the counts, the verdict, the audit trail and the sign-off.
- **Step 7 commit:** skipped on orchestrator instruction. The orchestrator commits this file together with 37-VERIFICATION.md and 37-VALIDATION.md.

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed (re-audit 2026-10-15; T-37-59 closed)
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-10-15. Four accepted risks remain pending the owner: AR-37-05, AR-37-07, AR-37-08 and AR-37-09.
