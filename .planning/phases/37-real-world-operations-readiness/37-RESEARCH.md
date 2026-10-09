# Phase 37: Real-world operations readiness - Research

**Researched:** 2026-10-07
**Tree:** `/home/sanmi/IdeaProjects/JToye_OaaS_2026-phase37`, branch `phase-37-ops-readiness` at `394edcda` (merge of `origin/main` incl. Phase 38 #898 and Phase 31.1 #901). Every file:line below was read on this tree this session unless tagged otherwise.
**Domain:** Multi-tenant Spring Boot 4.1 / Next.js 16 / Go edge / MCP: access control, kitchen ops, checkout integrity, abuse resistance, integrator surface, accessibility
**Confidence:** HIGH for current-behaviour evidence (code read), MEDIUM for fix shapes, LOW for the three external Keycloak/browser details flagged `[ASSUMED]`

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### Phase shape and order
- **D-01:** **Phase 37 covers the P0/P1 clusters only. P2/P3 go to a 37.x follow-up phase,** except 37-F (D-05). The 87-cluster scope is about three times any earlier phase, and only the P0/P1 set gates Phase 32 (a first tenant).
- **D-02:** **37-B (staff access) goes first.** Every other sub-theme's tests assert "staff see only what they were granted", so they must be written against the new default (D-06), not the old one.
- **D-03:** **One PR per sub-theme.** Each runs its own D3 review series to zero admissible findings and merges independently. The phase completes when all its sub-theme PRs have merged.
- **D-04:** **37-C waits for Phase 31.1 to merge.** 31.1 D-05/D-06 change checkout submit and the order's allergen record. 37-A, B, D, E, F and G may run in parallel with 31.1. Like 31.1 (D-21), Phase 37 is written against the Phase 38 tree (Boot 4.1 / Jackson 3) once that is on `main`. Merge `main` before executing each sub-theme.
- **D-05:** **37-F accessibility stays in Phase 37 despite being all P2.** A screen-reader customer who cannot follow their order is blocked on a core journey, and it is an Equality Act 2010 exposure before a real tenant. It is small and contained, and ships as its own PR.

#### Staff access (37-B)
- **D-06:** **Ungranted means no access: `jtoye.access.strict-scoping` defaults to `true` in every profile.** This uses the existing V57 off-ramp: no new automatic provisioning, automatically created tenant-wide GROUP_ADMIN rows are no longer honoured, OPERATOR grants and realm admins still are, and the oldest automatic admin is kept as a WARN-logged bootstrap so no tenant locks itself out. Compose and the k8s overlays must not override it back to false. — **Reversibility:** costly — the moment the default flips, users who only held an automatic grant lose access; flipping back silently re-escalates them, so it needs an owner ruling, never a config edit.
- **D-07:** **Staff are onboarded by email invite, and the invite carries the grant.**
  - The Group admin enters an email, a role and a shop (or all shops).
  - The person follows the link, registers or signs in, and lands with exactly that grant (OPERATOR grant source).
  - Self-registering without an invite yields no access (D-06).
  - The invite is tenant-scoped, single-use and expires. It is sent through `EmailNotificationService`.
- **D-08:** **Revoking the last grant ends access on the next request.**
  - Evict the per-user membership cache (the existing D-05 per-user eviction in `ShopAccessService`).
  - The next API call returns a typed 403, and the dashboard shows a clear "You no longer have access" page.
  - The Keycloak session is NOT ended (no dependency on `KeycloakAdminClient` for revoke).
  - Proven with the staff member's own token through the API (roadmap SC-2).
- **D-09:** **The Staff page lists everyone who has signed in to the tenant (from `user_directory`) with their actual effective access, including "No access" with a Grant button.** The effective access shown is read from the same source `ShopAccessService` decides with, never derived separately. This addresses theme 2: say ≠ data.
- **D-10:** **A COMPLETED (or any post-PENDING) order can no longer be deleted. Only DRAFT/PENDING orders are deletable. Anything later is voided instead.**
  - Delete returns a typed 409 once the order is CONFIRMED or later.
  - A new Group-admin-only "void" requires a reason. It keeps the order, writes a reversing ledger entry, and leaves an audit trail.
  - The ledger can never reference an order that returns 404.
  - — **Reversibility:** one-way — a void and its reversing ledger rows are financial records and cannot be un-written once vendors use them.

#### Kitchen order model (37-A)
- **D-11:** **PENDING orders appear on the kitchen board in a "New" lane, and Accept there moves them to CONFIRMED.** The kitchen no longer depends on someone confirming on the Orders page. The vendor still accepts every order; there is no auto-confirm.
- **D-12:** **The new-order alert repeats until the order is accepted.**
  - A looping sound every few seconds, a flashing card, and a tab title like "(2) New orders".
  - A mute is honoured and is visibly shown as muted.
  - Browsers allow audio only after a user gesture, so the board needs an explicit "Enable sound" tap at the start of a shift.
  - No push notifications in this phase.
  - The kitchen card must show the customer's notes and the fulfilment type (UXT-011, P0). The printed ticket already does.
- **D-13:** **MCP `create_order` produces a PENDING order, like the storefront,** so it lands in the New lane and emits the same events. The tool description states the resulting status. No `submit_order` tool. This is part of theme 6 (one domain path for every writer).
- **D-14:** **When the session lapses, the kitchen board enters a loud "board stopped" state, never a quiet redirect.**
  - A full-screen red "Signed out: new orders are NOT showing" with an alarm sound.
  - After sign-in, return to `/dashboard/kitchen`.
  - The board refreshes its token silently while the SSO session lasts.
  - Realm session lifetimes are NOT changed.
  - A lapsed session, a muted handover or an unreachable API is never shown as "Live" (roadmap SC-1).
- **D-15:** **A one-tap pause (20 min, 40 min, or until reopened) plus structured opening hours.**
  - The pause is enforced server-side by both the storefront and checkout, with a customer-facing message.
  - Free-text hours become structured open/close times per day.
  - Empty or unparseable hours read as CLOSED, not open. This reverses `PublicStorefrontService.validateShopIsOpen`'s fail-open.
  - Existing free-text hours need a migration path. Each existing shop's rendered behaviour must not silently change; any shop whose hours cannot be parsed is surfaced to its vendor.
  - — **Reversibility:** costly — the hours data model changes for every shop.

#### Checkout integrity and abuse (37-C, 37-D)
- **D-16:** **Checkout re-validates the basket at submit: the client sends what it showed, and the server refuses with a typed diff.**
  - Submit carries the unit prices and total the customer saw.
  - On any change (price, availability, sold out, minimum), the server returns a typed RFC 7807 409 listing each change.
  - The UI shows each change (for example "Suya £9.00 → £14.00") and requires re-confirmation.
  - No server-side quote store. This is deliberately the same shape as 31.1 D-05's stale-acknowledgement refusal, and must be built to compose with it, not beside it.
- **D-17:** **UXT-008: stop rendering any promotion that the order path does not apply.** A real promotions engine is a separate capability (Deferred). This is the theme-2 fix (say ≠ data): no claim without data behind it.
- **D-18:** **Cash checkout is bounded by per-identity caps plus an "unverified contact" flag. No captcha, and no email verification gate.**
  - Caps on open cash orders per email, per phone and per client IP. The limits are config-declared, never literals.
  - Over the cap, the server returns a typed 429.
  - Guest orders show an "unverified contact" badge on the kitchen board.
  - Vendors can bulk-reject junk orders (roadmap SC-4).
  - A third-party captcha is rejected because it conflicts with the cookie policy.
- **D-19:** **Client IP comes from a config-declared trusted-proxy CIDR list, and the default trusts none.**
  - `X-Forwarded-For` is read only when the immediate peer is a trusted proxy, taking the right-most untrusted hop. Otherwise the socket peer address is used.
  - The k8s overlays declare their ingress range.
  - This fixes `ClientIpResolver` (UXT-006), which is a regression of closed #88.
  - The edge gateway's single process-wide bucket (UXT-039) moves to per-tenant limiting after auth, with a typed 429, `Retry-After` and rate headers.
- **D-20:** **Item quantity gets a per-line ceiling and a basket-total sanity cap** (UXT-042), both config-declared. They are enforced in the domain service that every writer calls, not only on the storefront DTO (theme 6).
- **D-21:** **A review is accepted only when the cited order belongs to the reviewed shop** (UXT-005, `order.shopId == shop.id` in `ReviewService`).

#### Integrator credentials (37-E)
- **D-22:** **A credential is a Keycloak service-account client created per credential through `KeycloakAdminClient`.**
  - Scopes come from the existing #206 client scopes (`docs/security-scopes.md`).
  - A hard-coded `tenant_id` claim mapper ties each client to the issuing tenant.
  - The secret is shown once, and can be rotated and deleted.
  - Tokens stay ordinary JWTs, so core-java, edge-go and the MCP server need no new authentication path.
  - Depends on `KeycloakAdminClient` as changed by Phase 38 (Jackson 3) and armed in compose by 31.1 D-22. Re-prove the admin calls on the merged tree.
  - Platform-issued API keys in Postgres were rejected: a second auth path that every gate would have to honour.
  - — **Reversibility:** costly — the credentials vendors issue live in Keycloak; changing mechanism means re-issuing every integrator's credential.
- **D-23:** **A credential's service-account user gets an ordinary `shop_staff` grant** (a role, plus a shop or all shops), so `ShopAccessService` limits it exactly like a person. This composes with D-06.
- **D-24:** **Only a Group admin can create, rotate or revoke a credential.**
  - Each action records who did it and when.
  - The Developers page lists every credential with its scopes, shops and last-used time.

### Claude's Discretion
- How each remaining in-scope cluster is fixed, where the discussion did not settle a design choice: UXT-016 (partial update / PATCH semantics), UXT-017/#727 (sync requires a shop), UXT-026, UXT-027, UXT-029, UXT-037, UXT-038, UXT-040, UXT-044, UXT-046/047 (VAT-rate choice and registration status), UXT-023, UXT-028, and the 37-F fixes. Each must be shown to FAIL on the pre-fix tree, and must keep the 40 entries in `goods-to-preserve.md`.
- The exact config keys and default values for the D-18 caps and D-20 limits.
- Invite expiry and link lifetime (D-07).

### Deferred Ideas (OUT OF SCOPE)
- **Phase 37.x (P2/P3 persona clusters, plan after 37):**
  - the 50 remaining 37-A..37-E/37-G clusters at P2/P3 (measured: `catalogue.json`, subTheme 37-* excluding 37-F, priority not P0/P1), including UXT-049 (double-tap skips a status), UXT-050 (mute survives sign-out) and UXT-052 (unanswered orders pending forever, i.e. an auto-reject timeout);
  - the two unassigned clusters UXT-120 and UXT-121 (#873, #874).
- **A real promotions engine** (D-17): advertised discounts applied and recorded server-side. A new capability, so its own phase.
- **Web Push notifications to the owner's phone for new orders** (D-12). Needs a service worker and VAPID keys.
- **Ending the Keycloak session on revoke** (D-08).
- **Longer vendor-realm SSO lifetime for shift tablets** (D-14).
- **A per-tenant strict-scoping switch** (D-06 chose a global default).
- **Email verification gating cash orders** (D-18 chose caps and a flag).
</user_constraints>

<phase_requirements>
## Phase Requirements

The roadmap has no REQ-IDs for this phase; each in-scope UXT cluster is a requirement. **Scope count re-measured:** filtering `catalogue.json` on `subTheme` starting `"37"` and `priority in (P0, P1)` gives **29** clusters (7 B, 7 E, 5 D, 4 C, 4 A, 2 G), matching CONTEXT's table exactly. With the six 37-F clusters that makes **35**. The issue mapping in `filed-issues.json` also matches CONTEXT (UXT-017 is a comment on #727 and UXT-038 a comment on #587). `[VERIFIED: catalogue.json + filed-issues.json, filtered this session]`

| ID | Pri | Issue | Description | Research support (section) |
|----|-----|-------|-------------|----------------------------|
| UXT-003 | P0 | #779 | Revoking the last shop grant makes the user an implicit tenant-wide GROUP_ADMIN | §37-B.1 (D-06 flip), §37-B.2 (D-08/D-09) |
| UXT-004 | P0 | #780 | Every staff login is GROUP_ADMIN by default (JIT, strict-scoping off) | §37-B.1 |
| UXT-018 | P0 | #791 | A COMPLETED order can be deleted, leaving ledger rows pointing at a 404 | §37-B.4 (D-10) |
| UXT-026 | P1 | #799 | CSV import ignores the selected shop; no copy-menu | §37-B.6 |
| UXT-027 | P1 | #800 | Per-shop finance shows tenant totals; site managers get "No financial data yet" | §37-B.5 |
| UXT-046 | P1 | #815 | No VAT-rate choice in the product form | §37-B.7 |
| UXT-047 | P1 | #816 | "VAT (incl. 20%)" shown for every vendor regardless of registration | §37-B.7 |
| UXT-011 | P0 | #786 | Kitchen screen card hides customer notes and fulfilment type | §37-A.1 |
| UXT-020 | P1 | #795 | New order makes no sound and never reaches the kitchen until confirmed elsewhere | §37-A.1 (D-11/D-12) |
| UXT-021 | P1 | #796 | Session lapse silently turns the board into a sign-in page | §37-A.2 (D-14) |
| UXT-022 | P1 | #797 | No pause; free-text hours fail open and cannot be cleared | §37-A.3 (D-15) |
| UXT-007 | P0 | #783 | Checkout never re-validates the stored basket | §37-C.1 (D-16) |
| UXT-008 | P1 | #792 | Advertised promotion displayed but never applied | §37-C.2 (D-17) |
| UXT-029 | P1 | #802 | Confirmation rendered in place at /checkout: unannounced, lost on refresh | §37-C.3 |
| UXT-044 | P1 | #813 | Reviews publish the reviewer's full name | §37-C.4 |
| UXT-005 | P0 | #781 | A buyer of shop A can review shop B in the same tenant | §37-D.5 (D-21) |
| UXT-006 | P0 | #782 | Public limiter trusts any X-Forwarded-For (regression of #88) | §37-D.1 (D-19) |
| UXT-039 | P1 | #808 | Edge gateway's single pre-auth bucket; untyped 429 | §37-D.2 (D-19) |
| UXT-041 | P1 | #810 | Many fake cash orders in seconds | §37-D.3 (D-18) |
| UXT-042 | P1 | #811 | No upper bound on quantity (£19bn order) | §37-D.4 (D-20) |
| UXT-016 | P0 | #790 | PUT without quantityInStock turns stock tracking off | §37-E.1 |
| UXT-017 | P0 | #727 (comment) | /sync/batch products belong to no shop, orderable anywhere, wrong allergens | §37-E.2 |
| UXT-035 | P1 | #805 | No way to issue a scoped API credential | §37-E.5 (D-22..D-24) |
| UXT-036 | P1 | #806 | MCP orders stay DRAFT; kitchen never sees them | §37-E.3 (D-13) |
| UXT-037 | P1 | #807 | /sync/batch has no stock/availability and silently drops unknown items | §37-E.2 |
| UXT-038 | P1 | #587 (comment) | Webhooks auto-pause after ~46 s, 5 of 8 attempts, nobody told | §37-E.4 |
| UXT-040 | P1 | #809 | MCP list_products ignores shopId; allergens only as an integer | §37-E.3 |
| UXT-023 | P1 | #798 | No delivery fee / free-delivery threshold / collection-only in the shop form | §37-G.1 |
| UXT-028 | P1 | #801 | Every shop update regenerates the public slug | §37-G.2 |
| UXT-080 | P2 | #849 | Large text truncates basket, tracker and shop header | §37-F |
| UXT-081 | P2 | #850 | Tracking pages silent for screen readers | §37-F |
| UXT-082 | P2 | #851 | Shop cards have no accessible name | §37-F |
| UXT-083 | P2 | #852 | Add/Remove drops focus; basket announcement doubles the count | §37-F |
| UXT-084 | P2 | #853 | Basket/checkout/confirmation/tracking share one title | §37-F |
| UXT-085 | P2 | #854 | Mobile cookie banner covers focus; 32nd tab stop | §37-F |

**Not in this table, but substantively delivered:** UXT-025 (#452, "no way to invite staff") is homed to Phase 33 in the catalogue, yet D-07 builds exactly that invite. The planner should record that 37-B closes #452's gap 2, so Phase 33 does not rebuild it. `[VERIFIED: catalogue.json UXT-025 proposedHome = "Phase 33 – The Consumer Product"]`
</phase_requirements>

## Summary

The tree is ready for Phase 37: Boot 4.1 and 31.1 are merged, the Flyway head is **V75** (`V75__dsar_access_export.sql`, 75 files), and `docs/metrics.json` reads `"schema_version": 75`. All 35 in-scope clusters reproduce from the code as described, but several CONTEXT file:line references have drifted. The most important drift is `OrderService.deleteOrder`, now at **659-667** (not 599). A corrected table is in §File:line re-verification.

The single riskiest item is **D-06**. Three artefacts on this tree say the strict-scoping flip is "blocked on #285, the /sync/batch shop predicate, and the integration-orders-rw UUID-subject client": the WARN at `ShopAccessService.java:177-180`, `.env.example:153-155`, and `k8s/base/configmap.yaml:386-390`. Two of the three are already resolved or resolved by this phase. SEC-5/#648 shipped the sync shop gate (`SyncService.java:183` calls `require(... SHOP_MANAGER)`). D-23's shop_staff grant for service accounts dissolves the third. The `integration-orders-rw` service-account user has a fixed UUID, `5c0c16be-1aa0-4181-b808-2c7575f03b95`, in the realm import, so a dev-seeded OPERATOR grant keeps MCP `create_order` working. #285 (bulk revoke of JIT rows) is a UX nicety under strict ON, not a blocker, because the JIT rows become inert.

The test blast radius is real but bounded. 21 test files describe themselves as depending on the day-one implicit admin. 11 classes flip the field by reflection and **reset it to a literal `false` in `@AfterEach`**, which would silently re-arm OFF for every later class sharing the cached Spring context. E2E vendor specs log in as `admin-user`, a realm admin, so they are unaffected.

Three sub-themes carry hidden structural work the CONTEXT does not mention:

1. **D-07 invites need a Keycloak user-profile change.** The vendor realm has `registrationAllowed: false`, and KC24 strips an undeclared `tenant_id` attribute on admin-API user creation. The attribute must be declared admin-edit-only. `ENABLED` would let users edit their own tenant.
2. **D-10 void needs a ledger schema change.** V40's partial unique index `uq_fin_tx_tenant_order (tenant_id, order_id) WHERE order_id IS NOT NULL` forbids a second ledger row for the same order.
3. **37-E sync fixes are invisible through the edge.** `edge-go/internal/core/client.go:137-153` turns every non-2xx from core into an untyped 502 and re-encodes only `status`/`processed_count`.

**Primary recommendation:** Plan 37-B first as specified, opening with a "flip in isolation" plan. That plan flips the default and fixes the test-teardown leak to capture/restore the booted value. It seeds the `integration-orders-rw` OPERATOR grant, makes the directory upsert happen on read paths, runs the full `integrationTest` once, and enumerates the reds. Plan the other six sub-themes as self-contained plan groups, one PR each, with migration numbers **reserved up front** (V76-V82, §Migration numbering). At about 45-55 plans the phase is plannable but large; see §Size assessment.

## Project Constraints (from CLAUDE.md)

Directives with the same authority as locked decisions:

- **Stack fixed:** Spring Boot 4.1.1 (Jackson 3 `tools.jackson`; annotations stay `com.fasterxml.jackson.annotation`), Next.js 16.3.7, Go 1.27, PostgreSQL 15, JDK 25 / Gradle 9.7.1. No new stack.
- **Multi-tenancy:** every new feature respects RLS + TenantContext. Every new tenant table is ENABLE+FORCE RLS via `current_tenant_id()` (never the raw `::uuid` cast; `RlsContractTest.noPolicyUsesRawTenantGucCast`), with a NOSUPERUSER proof. An exemption is made BY ADDITION to `RlsContractTest.EXEMPT_TABLES` with written justification.
- **Testing:** all new code requires tests. Counts are single-sourced in `docs/metrics.json`, enforced by `scripts/docs-freshness.sh` and `scripts/check-doc-metrics.sh` (both fail on drift; regenerate with `--write`). README/AGENTS/CLAUDE prose numbers must match.
- **Docker:** rebuild ALL containers after code changes before E2E. `docker compose start` never rebuilds. Prove runtime parity with `scripts/check-runtime-freshness.sh` (`.Metadata.LastTagTime`, not `.Created`) and `scripts/check-branch-behind-base.sh`.
- **Runtime topology:** Compose (`docker-compose.full-stack.yml`) is the canonical local + E2E runtime. k8s kustomize (`k8s/base` + overlays) is the deploy target. Changes to env/config must land in both, plus `.env.example`, `k8s/goldens/*` (regenerated by `k8s/scripts/render-golden.sh`), and `k8s/scripts/check-env-contract.sh` expectations.
- **Incremental Betterment Doctrine:** any plan that reworks a user-visible surface enumerates the goods it displaces. Regression by omission is a defect (40 goods, §Goods-to-preserve map).
- **Cross-cutting quality contracts (standing acceptance criteria):**
  - Web-perf (mobile-first, throttled) on touched pages.
  - SEO on public surfaces (storefront/tracking).
  - Agent-readiness: Idempotency-Key or provable idempotency on mutating endpoints, RFC 7807 typed errors, scoped creds, OpenAPI matches live, and an MCP tool per core capability or a recorded reason.
  - Security `<threat_model>` per plan (ASVS L2 here: `security_asvs_level: 2`, `security_block_on: medium`).
  - Client-persisted identity lifecycle: basket localStorage touched by D-16; test THROUGH sign-in/out and assert the stored owner stamp by content.
  - **Falsifiable evidence:** every criterion shown to FAIL on a broken input, both directions recorded.
- **Errors:** custom exceptions in `uk.jtoye.core.exception`, mapped once in `GlobalExceptionHandler` to `application/problem+json` with a stable `type` URI `https://jtoye.uk/errors/<slug>` and a `code` property.
- **Config-declared limits, never literals** (D-18/D-19/D-20).
- **GSD workflow enforcement:** edits only inside `/gsd-execute-phase`.
- **No AI-attribution small print** in commits/PRs (global ruling). Review rounds narrow (`/code-review <PR> --comment`, then `/review-verify`).
- **Search hygiene (proof standards):** use `rg -uu` when a count or absence is evidence, print rc, and never prove absence through a truncating filter.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Strict-scoping decision, effective access (D-06/D-08/D-09/D-23) | API (`ShopAccessService`) | DB (`shop_staff`, RLS) | One decision ladder (`isGroupAdminForUser`) must serve HTTP, STOMP and the Staff page |
| Staff invite issue/accept (D-07) | API (core) | Keycloak admin API; Browser (accept page) | Grant + KC user creation must be atomic-ish and server-side; browser only collects consent/password |
| Void + reversing ledger (D-10) | API (`OrderService`/`FinancialTransactionService`) | DB (ledger index, `_aud`) | Financial record; never client-derived |
| Per-shop finance (UXT-027) | API + DB (`financial_transactions.shop_id`) | Browser (dashboard) | Aggregation must be DB-side (existing `aggregateForCurrentTenant` pattern) |
| Kitchen New lane, alert, board-stopped (D-11/D-12/D-14) | Browser (kitchen page) | API (`KITCHEN_STATUSES`, confirm transition) | Audio/visual/session are browser concerns; which orders appear is the server's (`OrderService.KITCHEN_STATUSES`) |
| Pause + structured hours + open-now (D-15) | API (storefront + checkout enforcement) | DB (shops columns); Browser renders server `openNow` | Server is authoritative; today client and server disagree |
| Basket re-validation diff (D-16) | API (`PublicStorefrontService.placeGuestOrder` loop) | Browser (diff UI, cart update) | Same loop/transaction as 31.1's allergen ack |
| Client IP trust (D-19) | API (`ClientIpResolver` → bean) | k8s config (ingress CIDRs) | Trust is deployment topology, declared in config |
| Per-tenant edge limit (UXT-039) | Edge (Go, after JWT) | — | Edge owns pre-core protection |
| Per-identity cash caps + unverified flag (D-18) | API (storefront create) | DB (open-order counts); Browser (badge) | Counting open orders is a DB query; badge reads a stored fact |
| Quantity caps (D-20) | API (domain service used by every writer) | — | Theme 6: storefront, vendor, MCP all pass through it |
| Integrator credentials (D-22..D-24) | API (credential service) | Keycloak admin API; DB (credential table + `shop_staff`); Browser (Developers page) | KC holds the client; core holds tenancy, grant, audit, last-used |
| Sync per-item results (UXT-017/037) | API (`SyncService`) | Edge (must pass results + typed errors through) | Edge currently drops both |
| MCP PENDING orders, product filter (D-13, UXT-040) | API (`OrderService` submit-on-create flag; `ProductDto` allergen names) | MCP (thin forwarder) | MCP must stay a forwarder; facts live in core |
| A11y live regions, focus, titles (37-F) | Browser | — | Pure client rendering |

## File:line re-verification (CONTEXT `code_context` against this tree)

| CONTEXT reference | This tree | Status |
|---|---|---|
| `ClientIpResolver.java:32,47` | `:32` `XFF_HEADER = "X-Forwarded-For"`; `:44-54` `resolveClientIp`, `:47` first hop. Static utility, 2 callers: `RateLimitInterceptor.java:322`, `DsarIntakeRateLimiter.java:146` | accurate |
| `OrderService.java:599 deleteOrder` | `OrderService.java:659-667`; only `require(order.getShopId(), SHOP_MANAGER)` at `:663`, no status guard | **drifted** |
| `ReviewService.java:70-95` | `createReview` `:64-110`; email `:75`, COMPLETED `:80`, duplicate `:85`; full name stored `:96` | shifted |
| `GuestOrderItemRequest.java:13` | `:13` `@Min(value = 1, ...)`, no `@Max` | accurate |
| `PublicStorefrontService.validateShopIsOpen` | `:1359-1395`; called once at `:773` (guest order only); empty → open (`:1361-1364`), unparseable → open (`:1374-1377`) | accurate |
| `SyncService.java` never sets shopId | `upsertProduct` `:172-221` has no `setShopId`; unknown `type` → `return false` (`processItem` `:116-131`, `default:` `:128`) while response says `"SUCCESS"` (`:110-113`) | accurate |
| edge-go process-wide limiter | `edge-go/cmd/edge/main.go:166-200` `rateLimiter`, registered pre-auth at `:281`; 429 body `{"error":"rate limit exceeded"}` `:198` | accurate |
| `mcp-server/src/tools/create-order.ts` DRAFT | Tool forwards to `POST /api/v1/orders`; DRAFT comes from `OrderService.java:144` `order.setStatus(OrderStatus.DRAFT)` | accurate |
| kitchen page | `frontend/app/dashboard/kitchen/page.tsx` (1064 lines); `KITCHEN_STATUSES` `frontend/lib/kitchen-orders-api.ts:14` and `OrderService.java:63-64`; beep `page.tsx:500-504` (per-call `new AudioContext()` `:118-136`); renders `allergyNote` (31.1) but not `order.notes` or `fulfilmentType`; printed `components/dashboard/kitchen/kitchen-ticket.tsx:66,195` does | accurate (31.1 added allergy note) |
| `ShopAccessService.java:49-62,104-106,178` | `@Value strict-scoping:false` `:108-109`; posture WARN `:177-180`; JIT `:631-642`; decision ladder `:374-391`; bootstrap `:413-429` | shifted |
| `application.yml §jtoye.access ~178-193` | `:194-213`; `strict-scoping: ${ACCESS_STRICT_SCOPING:false}` `:209` | **drifted** |
| ShopService slug regen (catalogue `:268-270`) | `ShopService.java:271-276` | shifted |

## 37-B Staff access & finance (FIRST, D-02)

### 37-B.1 D-06: flip strict-scoping (UXT-003, UXT-004)

**Current behaviour.**

The default is OFF at `application.yml:209`. Compose pins `ACCESS_STRICT_SCOPING: ${ACCESS_STRICT_SCOPING:-false}` at `docker-compose.full-stack.yml:324`, `.env.example:156` has `ACCESS_STRICT_SCOPING=false`, `k8s/base/configmap.yaml:391` has `access.strict-scoping: "false"`, and `k8s/base/core-java-deployment.yaml:567-571` wires it as `configMapKeyRef`. The rendered goldens repeat it at `k8s/goldens/staging.yaml:47,598-601` and `k8s/goldens/production.yaml:47,598-601`.

Under OFF, an ungranted user is an implicit GROUP_ADMIN: `ShopAccessService.java:390` reads `return !strictScoping && membership.perShopRole().isEmpty();`. The first write JIT-inserts a GROUP_ADMIN row (`:631-642`). This is exactly UXT-003: revoking the last per-shop grant empties `perShopRole`, and line 390 turns the user back into an admin.

**What ON does (already built, V57/CR-07).** It stops JIT provisioning. It de-honours JIT-sourced tenant-wide GROUP_ADMIN rows. OPERATOR rows and `ROLE_admin` stay honoured. When no OPERATOR GROUP_ADMIN exists, the oldest JIT admin is kept and WARN-logged (`isBootstrapAdmin`, `:413-429`). Enum values, read from source: `GrantSource { JIT, OPERATOR }` `[VERIFIED: GrantSource.java:16-31]` and `ShopRole { STAFF(0), SHOP_MANAGER(1), GROUP_ADMIN(2) }` `[VERIFIED: ShopRole.java:22-25]`.

**Fix shape.**
1. `application.yml:209` → `${ACCESS_STRICT_SCOPING:true}`. Compose default `:-true`. `.env.example` → `true`. `configmap.yaml` → `"true"`. Regenerate goldens. Rewrite the comments in all of these: each currently says "do not flip" and names the blockers.
2. Rewrite `logScopingPosture` (`:169-181`) and its test `ShopAccessServiceScopingPostureTest` (asserts the WARN names "#285 … integration-orders-rw"). Under the new default the INFO branch is the expected one; OFF should log loudly that it was an explicit override.
3. **Guard against D-06's "must not override it back to false".** Add a falsifiable check: a render invariant in `k8s/scripts/check-render-invariants.sh`, or a new `scripts/check-strict-scoping-default.sh`. It fails if `application.yml`, `docker-compose*.yml`, `.env.example`, any k8s overlay/golden or any `application-*.yml` sets the value to false. Show it red on a deliberately broken copy.
4. **Seed the `integration-orders-rw` service account.** Its token `sub` is a UUID (`5c0c16be-1aa0-4181-b808-2c7575f03b95`, tenant `00000000-0000-0000-0000-000000000001`) `[VERIFIED: infra/keycloak/realm-export.json users[]]`. `isDeclaredMachineClient` (`:697-711`) therefore never fires. Its only path to GROUP_ADMIN today is line 390. Seed an OPERATOR `shop_staff` row and a `user_directory` row for it in `DemoDataSeeder` (dev only). Without this, MCP `create_order` breaks in compose. The same applies to `service-account-integration-catalog-ro` (`9e0bf075-…`) if any read path it uses is shop-gated. Product list reads are authenticated-only, so it probably needs nothing; verify.
5. **Directory upsert on read paths (prerequisite for D-09).** `onRequest()` returns early on read-only transactions (`:609-611`). A newly signed-in, ungranted user under strict ON only ever makes reads (every write 403s), so they never reach `user_directory` and never appear on the Staff page. Move the throttled upsert into a `REQUIRES_NEW` best-effort write (the V49/afterCommit idiom). Alternatively make `GET /api/v1/staff/me` (which the dashboard calls on load) non-readOnly and upsert there. JIT stays OFF either way.

**Tests that change.**
- 21 test files self-describe a dependency on the implicit admin: `rg -uu -l -i 'implicit (tenant-wide )?GROUP_ADMIN|day-one implicit|implicit-GA|implicit GA' core-java/src/test/java`. The list: `OrderIdempotencyIntegrationTest`, `LocationHeaderContractTest`, `ShopControllerIntegrationTest`, `MediaUploadControllerTest`, `OnboardingGoLiveIntegrationTest`, `AllergyNoteAckIntegrationTest`, `OrderSseGrantRecheckTest`, `ProductSearchFtsIntegrationTest`, `CrossTenantAuthzIntegrationTest`, `ShopAccessCacheBypassIntegrationTest`, `ShopAccessEnforcementIntegrationTest`, `ShopAccessJitProvisionTest`, `ShopAccessServiceScopingPostureTest`, `StaffManagementIntegrationTest`, `StrictScopingTighteningIntegrationTest`, `ScopedCatalogAccessIntegrationTest`, `ScopedWriteAccessIntegrationTest`, `SecurityHeadersIntegrationTest`, `SyncBatchAuthorizationIntegrationTest`, `TenantJwts` (helper), `WebhookAuthzIntegrationTest`. `[VERIFIED: rg -uu, rc=0]`
- `TenantJwts.vendorJwt(sub, tenantId)` (`testsupport/TenantJwts.java:56-63`) builds a `user`-role token whose access is "what its shop_staff rows (or the day-one implicit-admin rule) say". Every test that uses it without seeding a grant flips from 2xx to 403. `adminJwt` (realm `admin`) is unaffected.
- **Teardown leak.** These classes reset with a literal `setStrictScoping(false)` in `@AfterEach` or per test: `AllergyNoteAckIntegrationTest:131`, `CrossTenantAuthzIntegrationTest:106`, `ShopAccessEnforcementIntegrationTest:108`, `ShopAccessJitProvisionTest:85`, `StaffManagementIntegrationTest:114`, `ShopAccessFailClosedIntegrationTest:98`, `ShopAccessCacheBypassIntegrationTest:123`, `StrictScopingTighteningIntegrationTest:86`, `TenantChannelInterceptorShopGateIntegrationTest:97`, `MediaKeepShopScopeIntegrationTest:73`, `MediaRedriveControllerTest:162`. They mutate the singleton of a **cached** Spring context. After the flip, any class that runs later in the same JVM and context sees OFF, so its green proves nothing about the new default. Fix: capture the value in `@BeforeEach`, restore that value, and add one assertion that the booted bean reads `true` (e.g. in `ShopAccessServiceScopingPostureTest`, from the context, not by reflection).
- **Recipe (scope-gate integrationTest regression trap):** do the flip in its own plan, run the full `./gradlew :core-java:test :core-java:integrationTest` once, and enumerate every red before fixing. Each fixed test seeds an explicit OPERATOR grant (preferred) or uses `adminJwt`. Do not just set strict false in the test.
- Frontend: `frontend/lib/shops-api.ts:9-12` and `frontend/app/dashboard/staff/page.tsx:75-89,606-647` (copy about JIT and "cannot send an invite") change with D-07/D-09. `frontend/app/dashboard/__tests__/staff-page.test.tsx` references strict-scoping.
- E2E: vendor specs log in as `VENDOR_USERNAME ?? "admin-user"` (`frontend/e2e/vendor-credentials.ts:30`), a realm admin `[VERIFIED: realm-export.json admin-user realmRoles ['admin']]`, so they are unaffected. `tenant-a-user`/`tenant-b-user` (no realm roles) lose all access, which is the intended UXT-004 fix.

### 37-B.2 D-08 / D-09: revoke ends access; Staff page shows effective access

- The revoke path already evicts after commit (`StaffManagementService.revoke` `:268-304` → `evictAfterCommit` → `ShopAccessService.evictMembershipAfterCommit` `:554-565`). Goods P2-KEM-21 ("revoking takes effect on the very next request") already holds. The UXT-003 bug is line 390, which strict ON removes.
- `StaffManagementService.list()` (`:120-131`) returns the raw directory plus raw grants. The frontend derives meaning: `staff/page.tsx:620` comments "A grant with no directory entry is a JIT / service-account row". That is the theme-2 "say ≠ data" defect.
- **Fix:** add an explicit-identity effective-access read to `ShopAccessService`, for example `EffectiveAccess effectiveAccessFor(UUID userId)`. It funnels through the same `isGroupAdminForUser` plus `resolveMembership` as `canAccessShop` (`:463-482`), returning `{groupAdmin, bootstrapAdmin, perShopRole}`. The Staff list maps every directory row through it, so "No access" is a computed fact. Add a "Grant" action.
- **Pitfall (say ≠ data):** realm admins (`ROLE_admin`) are invisible to the DB. `user_directory` stores no realm roles (`UserDirectory.java`), so effective access for a realm admin would read "No access". Record `realm_admin_seen` (and when) at the directory upsert, from the token authorities. Otherwise the page must label realm-admin status "unknown from data". Planner decides; the recommendation is a column, since every directory row is written from a token anyway.
- **No-access page:** every refusal is the same `https://jtoye.uk/errors/shop-access-denied` 403 (`GlobalExceptionHandler.java:370-380`). The dashboard should call `GET /api/v1/staff/me`. `groupAdmin=false` with `grantedShopIds=[]` means "You no longer have access", and that page renders instead of empty states (UXT-098 "403 renders as No endpoints yet" is P3 and out of scope, but the no-access page fixes the root).
- **Live socket residual:** STOMP checks access only on SUBSCRIBE (`TenantChannelInterceptor.java:67,234`), so a revoked user's open kitchen socket keeps receiving `OrderStateChangeEvent` frames (order id, number, status). The board's per-frame `/api/v1/orders/{id}/detail` fetch and 60-s poll will 403. The kitchen page must treat a shop-access-denied 403 as "access ended", disconnect, and show the page. Record the frame residual (order numbers until the socket closes) as an accepted residual or a follow-up.

### 37-B.3 D-07: email invite carries the grant

**Hard constraints found.**
- **The vendor realm has `registrationAllowed: false`** and `verifyEmail: false` `[VERIFIED: infra/keycloak/realm-export.template.json]`. "Registers" cannot mean Keycloak self-registration. The accept flow must create the account itself through `KeycloakAdminClient`.
- **KC24 strips an undeclared `tenant_id` on admin-API user creation.** Both realms have no user-profile config in the template (`components` holds no `UserProfileProvider`), and the strip is documented in `docs/security-scopes.md` §5 (read this session) and the project memory `reference_keycloak24_user_profile_trap`. Without a fix the invited user's token has no `tenant_id`, and `JwtTenantFilter` leaves no tenant. **Fix:** declare `tenant_id` as a managed attribute with `view: ["admin"]`, `edit: ["admin"]` in the vendor realm's user profile, in **both** `realm-export.template.json` and the rendered `realm-export.json`.
  - **Do NOT use `unmanagedAttributePolicy: ENABLED`.** Per the Keycloak docs it "enables unmanaged attributes to all user profile contexts", including the user's own account console. A vendor user could then set their own `tenant_id` and cross the tenant wall. `ADMIN_EDIT` is acceptable; a declared admin-only attribute is better. `[CITED: docs.redhat.com RHBK 24 server admin guide, "Managing users"]` The exact realm-JSON key for the profile config is `[ASSUMED]`; prove it by re-import plus a GET of `/admin/realms/jtoye-dev/users/profile` and a created user's attributes.
- **A realm change needs a re-import.** `start-dev --import-realm` only creates absent realms, and with Postgres-backed KC a volume drop is a no-op. Use `kc.sh import --override true` (`docs/security-scopes.md` §4, `infra/keycloak/README.md`). Staging/production realms are not in this repo, so the deploy step is an operator task (USER-SETUP).
- **`grant()` requires a `user_directory` row** (`StaffManagementService.java:224-226`). The accept path must write the directory row and the OPERATOR `shop_staff` row itself, inside the pinned tenant as `SystemPrincipal.asSystem`. It must not call `grant()` as the invitee.
- **One user, one tenant.** `tenant_id` is single-valued. If the invite email already exists in the vendor realm under a different tenant, refuse with a typed 409. If it exists in the same tenant (a "No access" user), grant to that existing `sub`.
- **Created users need `firstName`, `lastName` and `emailVerified=true`.** Otherwise the token mint fails "Account is not fully set up" (project memory, measured 2026-07-14). Email possession is proven by the invite link.

**Recommended shape (Claude's discretion on lifetime).**
- **Table `staff_invite` (V76):** `id, tenant_id, email_normalised, shop_id NULL, role, token_sha256 UNIQUE, expires_at, created_by, created_at, accepted_at, accepted_user_id, revoked_at`. ENABLE+FORCE RLS via `current_tenant_id()`; NOSUPERUSER proof; `CHECK (role <> 'GROUP_ADMIN' OR shop_id IS NULL)` mirrors the `grant()` rule. Hold the token only as SHA-256 (V62 rule). Single use: `accepted_at` is set once (`UPDATE … WHERE accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()`, and check rowcount).
- **Tenant resolution without an RLS exemption.** The accept request is anonymous, with no JWT and no GUC, which is the V62/V75 liveness problem. Avoid an exemption by putting the tenant id in the link: `/invite/{tenantId}.{token}`. The server pins that tenant (`TenantContext.set` plus GUC), then reads by `token_sha256` under RLS. A wrong tenant or token gives one non-disclosing 404, the same body for every cause (the `DsarExportUnavailableException` pattern, `GlobalExceptionHandler.java` around `:796`).
- **Lifetime:** recommend 72 h, config-declared (`jtoye.staff.invite.ttl-hours`). Re-issue revokes the previous row.
- **Endpoints:**
  - `POST /api/v1/staff/invites`, GROUP_ADMIN, Idempotency-Key or naturally idempotent per (email, shop, role).
  - `GET /api/v1/staff/invites`.
  - `DELETE /api/v1/staff/invites/{id}`.
  - Public `GET /public/staff-invites/{tenant}.{token}` (shows shop/role/tenant name).
  - Public `POST /public/staff-invites/{tenant}.{token}/accept` with first/last name and password.
  - Rate-limit the public pair (IP + token-digest axes, the `DsarIntakeRateLimiter` shape).
- **Email:** add `EmailNotificationService.sendStaffInvite(...)` (`@Async`, plain text, the existing pattern at `:147-235`). Link host is the frontend public URL, not core's (31.1 PGC-839 lesson).
- **Password handling:** the accept page collects a password that transits core to the KC admin API and is never stored or logged. Keycloak enforces the realm password policy and returns 400, which must map to a typed 422. The alternative, KC `execute-actions-email`, sends a second email via KC's own SMTP (`smtpServer.host = mailhog`), against D-07's "sent through `EmailNotificationService`". **Open question 1.**
- **MCP:** no tool, by recorded reason (an agent must not mint human staff).

### 37-B.4 D-10: delete guard plus void with a reversing ledger entry (UXT-018)

**Current behaviour.** `deleteOrder` (`OrderService.java:659-667`) has no status guard. There is no delete control in the dashboard UI: `rg -uu 'apiClient\.delete'` over `frontend/app,components,lib` lists only staff, promotions, announcements, customers, shops and products (control: products page count 2, rc=0). The persona used the API.

**Ledger facts.**
- **One row per order:** `uq_fin_tx_tenant_order ON financial_transactions (tenant_id, order_id) WHERE order_id IS NOT NULL` `[VERIFIED: V40__vat_ledger_correctness.sql:99-101]`.
- `FinancialTransactionService.createTransaction` (`:68`) has an idempotency fast path on `findByOrderId` returning `Optional` (`:78`), plus a race backstop that re-queries the same finder (`:102`).
- The row is written on COMPLETED by `OrderService.java:590-598` and on card settlement by `PaymentService.java:342`.
- RLS is `financial_transactions_rls_policy FOR ALL USING/WITH CHECK (tenant_id = current_tenant_id())` (`V2__rls_policies.sql:44-47`). Immutability is code-only ("No update or delete methods").
- The table is `@Audited`, so `financial_transactions_aud` exists.
- The aggregate treats negative amounts as **expenses**, not negative revenue (`FinancialTransactionRepository.aggregateForCurrentTenant`). A reversing row with a negative amount would report as "expenses" unless the query changes.
- `RefundService` never touches the ledger (`rg 'financialTransaction' payment/` matched only `PaymentService`). Refunds already leave the ledger unreversed. Out of scope; record it in the resolution.

**Fix shape.**
- **V77:** `financial_transactions.entry_kind VARCHAR(16) NOT NULL DEFAULT 'SALE'` (`CHECK IN ('SALE','REVERSAL','MANUAL')`) plus a `_aud` mirror. Replace the partial unique index with `(tenant_id, order_id, entry_kind) WHERE order_id IS NOT NULL`, giving one sale and at most one reversal per order. Add `reverses_transaction_id UUID NULL`.
- Change `findByOrderId` to `findByOrderIdAndEntryKind`, or the fast path throws `IncorrectResultSizeDataAccessException` once two rows exist.
- Update both aggregates so revenue is `SALE − REVERSAL`. Keep `MANUAL` negatives as expenses. This preserves goods P2-KEM-22 ("ledger VAT correct"); the 1k golden `financial-summary-1k.golden.json` must stay green.
- **Orders V77 part:** `voided_at, voided_by, void_reason` plus `orders_aud` mirrors in the SAME migration (Order is `@Audited`; the V30/V38 runtime-failure lesson). **Recommend NOT adding a `VOIDED` status.** `orders_status_check` is a CHECK whose late extension already forced a rewrite (`V36__refunds_and_outbox_exchange.sql:84-85`, "V6 landmine"). A new status would also ripple through `OrderStatus`, the state machine, `KITCHEN_STATUSES`, the frontend status maps, MCP and webhook payloads. Columns are additive. If the planner prefers a status, it is a constraint-rewrite migration plus a state-machine transition.
- **Delete guard:** in `deleteOrder`, if status is not DRAFT or PENDING, throw a new `OrderNotDeletableException`, mapped to 409 `https://jtoye.uk/errors/order-not-deletable`, with `code`, `status` and a hint to void. `OrderStatus` values, read from source: `DRAFT, PENDING, CONFIRMED, PREPARING, READY, COMPLETED, CANCELLED, REFUNDED` `[VERIFIED: OrderStatus.java]`. CANCELLED from PENDING has no ledger row; whether a CANCELLED order is deletable is a planner choice. D-10 says "only DRAFT/PENDING".
- **Void:** `POST /api/v1/orders/{id}/void {reason}`, `requireGroupAdmin()`, `orders:write` scope. It is idempotent by construction (a second void returns the existing void; a different reason gives a 409). The reversing row has negative amount, same `vat_rate`, `entry_kind=REVERSAL` and `reverses_transaction_id`. It is written only if a SALE row exists, in the same transaction.
- The order stays readable (`GET` 200 with void fields). That satisfies "the ledger can never reference an order that returns 404".
- **Test:** an IT with COMPLETED → DELETE gives 409 → void → GET 200 → ledger summary nets to 0. Fail direction: remove the guard and watch the IT red.

### 37-B.5 Per-shop finance (UXT-027)

**Current behaviour.**
- `FinancialTransactionController` is `@PreAuthorize("hasRole('admin')")` at class level (`:40`), so a site manager's 403 renders as "No financial data yet".
- `GET /summary` takes no parameters (`:95`).
- Ledger rows have no `shop_id` (`FinancialTransaction.java`, fields `id, tenantId, createdAt, amountPennies, vatRate, reference, orderId`).

**Fix.**
- **V78:** `financial_transactions.shop_id UUID NULL` plus `_aud`. Backfill from `orders.shop_id` via `order_id`. **FORCE-RLS backfill trap:** a bare UPDATE matches 0 rows under `jtoye_app`, so loop over `tenants` with `set_config('app.current_tenant_id', t.id::text, true)`. That is the V44/V57 pattern, quoted in §Code Examples.
- Add a stepwise-Flyway test seeding rows across ≥2 tenants, mirroring `V57GrantSourceBackfillIntegrationTest`. Testcontainers cannot catch the trap on a fresh DB.
- Writers set `shop_id`: `OrderService` completion and `PaymentService`.
- **API:** `GET /api/v1/financial-transactions/summary?shopId=&from=&to=` and a CSV export (`text/csv`, SC-2 "can be exported").
- **Gate:** with `shopId`, `shopAccessService.require(shopId, SHOP_MANAGER)`; without it, GROUP_ADMIN. Replacing the class-level `hasRole('admin')` changes who can read the tenant ledger. Keep the tenant-wide view GROUP_ADMIN-or-realm-admin, so goods P2-TUN-15 ("money endpoints refuse a non-admin") still holds for STAFF.
- **Frontend:** dashboard home plus `/dashboard/finance` pass the switcher's shop. The "scoped to this shop" banner must read from what the API filtered on (say ≠ data).

### 37-B.6 CSV import shop and copy-menu (UXT-026)

`POST /api/v1/products/bulk/csv` takes only `file` (`ProductController.java:130-132`). `BulkImportService` reads an optional per-row `shop_id` column (`:54-59,113-150`) and otherwise creates `shop_id NULL` products. The storefront shows none of those: `PublicStorefrontService.getShopProducts` (`:567`) reads `productRepository.findAvailableByShopOrderedByCategory(shop.getId())`, commented "the shop_id IS NULL 'tenant-wide' bleed was removed" (UIX-05).

Fix: an optional `shopId` request param used as the default for rows without `shop_id`, gated `require(shopId, SHOP_MANAGER)`. The frontend passes the switcher shop; show the target shop on the import page. Add a "Copy to shop" action, `POST /api/v1/products/copy {sourceShopId, targetShopId, productIds?}`, that goes through `ProductService.createProduct` so allergen spans, may-contain and media are handled (theme 6). Make it idempotent with an Idempotency-Key. Carry allergen data verbatim; never derive it.

### 37-B.7 VAT rate choice and registration display (UXT-046/047)

- **UXT-046.** The API already accepts `vatRate`: `CreateProductRequest.java:72`, no Java default; `ProductService.java:145` defaults STANDARD on create; PUT preserves via IGNORE. Only the dashboard product form lacks the field. `VatRate { ZERO, REDUCED, STANDARD, EXEMPT }` `[VERIFIED: finance/VatRate.java]`. Fix is frontend only, plus a jest test, plus a label explaining zero-rated cold takeaway (copy needs owner sign-off; avoid giving tax advice).
- **UXT-047.** 31.1 V71 added `trader_identity.vat_number` (NULL = no registration declared), exposed through `SellerIdentityDto.vatNumber` (`storefront/dto/SellerIdentityDto.java:26,43`). The checkout prints `VAT ({vatRateLabel(...)})` unconditionally (`checkout/page.tsx:711,829`). Fix: show the VAT line only when the seller declares a VAT number; otherwise say "Prices include no VAT" or omit it.
- **Open question 2 (legal):** should the ledger compute VAT for a non-registered trader? Today every row derives VAT from `vat_rate` (`FinancialTransaction.calculateVatAmount`). The catalogue itself says "The legal effect needs legal confirmation".

## 37-A Kitchen & order operations

### 37-A.1 New lane, repeating alert, notes and fulfilment on the card (UXT-011, UXT-020, D-11, D-12)

**Current behaviour.**
- `OrderService.KITCHEN_STATUSES = List.of(CONFIRMED, PREPARING, READY)` (`:63-64`), and the frontend copy is `lib/kitchen-orders-api.ts:14`.
- The beep fires only when a status enters the kitchen set from outside, i.e. on CONFIRMED (`page.tsx:500-504`). Each beep creates a new `AudioContext` and swallows errors (`:118-136`).
- The card renders `allergyNote` (31.1) but neither `order.notes` nor `fulfilmentType`. The printed ticket renders both (`kitchen-ticket.tsx:66,83,195-197`).

**Fix shape.**
- **Backend:** add PENDING to `KITCHEN_STATUSES` and keep the frontend constant in sync (the comment at `OrderService.java:55-62` says they must agree; consider a parity test). Accept uses the existing `POST /api/v1/orders/{id}/confirm`. `transitionOrder` already requires STAFF on the shop (`:542`) plus `SCOPE_orders:write`. CONFIRMED decrements stock and may throw `InsufficientStockException` (`:559-561`), so the Accept button must surface that typed error.
- **Frontend:**
  - A "New" lane listing PENDING.
  - One shared `AudioContext` created by an explicit "Enable sound" tap.
  - A loop every N seconds while any PENDING order is unaccepted and not muted.
  - Card flash (respect `prefers-reduced-motion`: flash colour without animation).
  - `document.title = "(n) New orders"`.
  - A visible "Sound OFF / blocked" state when `ctx.state !== "running"` or muted. The board must not claim sound it cannot make (theme 2).
  - Notes and a DELIVERY/COLLECTION badge on the card.
- **Browser audio:** starting Web Audio outside a user-input handler "is subject to autoplay rules" `[CITED: developer.mozilla.org Autoplay guide]`. `navigator.getAutoplayPolicy()` exists for checking `[CITED: same]`. Its availability in Chromium is `[ASSUMED]`; feature-detect it and fall back to `AudioContext.state`.
- **Goods:**
  - P2-FUN-17 (orders list live in 1-5 s).
  - P2-FUN-18 (big bump buttons).
  - P2-FUN-20 (38-minute stay-live).
  - P2-TUN-13 (honest offline banner).
  - P2-CHA-15/P2-TUN-15 (allergen banner and chips).
  - Claire's per-shop banner and "Print all".
- **UXT-050 is NOT in scope.** Mute surviving sign-out is P2 and moves to 37.x, but the new mute UI must not make it worse.

### 37-A.2 Board-stopped state on session lapse (UXT-021, D-14)

**Current behaviour.** `lib/api-client.ts:144-162`: on a 401 it refreshes once via `getSession()`; if still unauthenticated it sets `window.location.href = "/auth/signin"` globally. The sign-in page hardcodes `signIn("keycloak", { callbackUrl: "/dashboard" })` (`app/auth/signin/page.tsx:104`). NextAuth refreshes when the session is read (`auth.ts:80-97`). On refresh failure it sets `error: "RefreshTokenError"` and drops the access token.

**Fix shape.**
- Make the global redirect overridable. One option: the interceptor dispatches a `jtoye:session-lapsed` event, and redirects only if no listener claims it. The kitchen page claims it and enters the full-screen red "Signed out: new orders are NOT showing" state, with a looping alarm through the already-unlocked shared `AudioContext`.
- The sign-in page honours a validated same-origin `callbackUrl` param. The kitchen's "Sign in again" passes `/dashboard/kitchen`. Validate it (open-redirect threat: relative paths starting `/` only, not `//`).
- **"Live" indicator:** derive from `connected && lastSyncedAt fresh && session valid && !syncFailed`. A muted board shows a "Muted" chip, never a plain "Live".
- **Silent refresh:** poll `getSession()` before `expiresAt` so the access token (300 s, `accessTokenLifespan`) never lapses while the SSO session (idle 1800 s, max 7200 s, read from the template) lasts. Realm lifetimes are unchanged (D-14).
- **Goods:** P2-TUN-14 (shared-tablet sign-out really ends the session). The board-stopped state must not survive an intentional sign-out as an alarm. Distinguish a deliberate `signOut` from a lapse.

### 37-A.3 Pause and structured hours (UXT-022, D-15)

**Current data shape.** `shops.opening_hours` is `jsonb` mapped `Map<String,String>` (`Shop.java:57-59`). Keys `mon..sun`; values `"HH:MM - HH:MM"`, `"closed"`, or free text.

**Every reader:**
- Backend:
  - `PublicStorefrontService.validateShopIsOpen` (`:1359-1395`), the only server gate, guest orders only.
  - `toPublicShopDto` (`:1332`).
  - `ShopDto`, `CreateShopRequest`.
  - `ShopMapper` (IGNORE-null, so hours cannot be cleared, P2-CHA-10).
- Frontend:
  - `lib/opening-hours.ts` (`isOpenNow`, `parseHoursRange`).
  - `lib/storefront-server.ts:188` (SSR `isOpen`).
  - `lib/structured-data.ts:65` (JSON-LD `openingHoursSpecification`).
  - `app/shop/shop-discovery-client.tsx`, `app/shop/[slug]/shop-detail-client.tsx`, `app/dashboard/shops/page.tsx` (free-text editor).
  - `e2e/helpers/public-surface.ts:48`.
  - About 20 jest test files reference `openingHours` (`rg -uu -l` list).

**The two parsers already disagree today** (measured by reading both):

| Input | Server `validateShopIsOpen` | Client `isOpenNow` |
|---|---|---|
| null / `{}` | open (`:1361-1364`) | open (`opening-hours.ts:74`) |
| today's key missing | closed (`:1369`) | closed (`parseHoursRange(undefined)` → null → false) |
| `"closed"` | closed | closed |
| unparseable text | **open (fail-open, `:1374-1377`)** | **closed** |

So an unparseable day already *renders* Closed while orders are accepted. D-15's "unparseable → CLOSED" aligns the server with what customers already see. **Empty hours are the dangerous case.** `DemoDataSeeder` sets no hours on any shop (`rg -i 'hours|setOpening'` over it returned nothing), and `e2e/helpers/public-surface.ts:48` has `openingHours: null`. So every demo and E2E shop is "always open" today. Flipping empty to CLOSED with no migration closes every demo storefront and breaks every order-placing E2E. That would be a regression by omission.

**Recommended migration strategy (V79), honouring "rendered behaviour must not silently change":**
- New column `opening_schedule jsonb` holding structured windows, e.g. `{"mon":[{"open":"09:00","close":"17:00"}], "tue":[], …}`, where `[]` means closed and overnight windows are allowed. Add `hours_source VARCHAR(24)` in `STRUCTURED | MIGRATED_FROM_TEXT | NEEDS_REVIEW | LEGACY_ALWAYS_OPEN`, plus `shops_aud` mirrors in the same migration (Shop is `@Audited`, `Shop.java:17`). Keep `opening_hours` read-only for one release (dual-read, the V53 D-03a precedent).
- Backfill in a **tenant loop** (FORCE-RLS trap) with the same regex both parsers use (`(\d{2}):(\d{2})\s*-\s*(\d{2}):(\d{2})`):
  - parseable day → window;
  - `"closed"` or missing day → `[]`;
  - unparseable day → `[]` plus `NEEDS_REVIEW`, matching what the storefront already renders;
  - NULL/empty map → `LEGACY_ALWAYS_OPEN`, which the server honours as open but the vendor dashboard flags ("Your shop has no opening hours, so customers can order at any time. Set them.").
- From the migration on, a **new or edited** shop with no windows is CLOSED (D-15). `LEGACY_ALWAYS_OPEN` is never written by the application, only by the migration. That keeps a fabricated "24/7" statement distinguishable from a vendor's (the V63/V66 rule). **Open question 3** asks the owner to confirm this, versus closing empty-hours shops immediately.
- The seeder and E2E fixtures get explicit hours, so demo storefronts stop depending on the legacy state.
- **Server computes `openNow`, `nextOpenAt` and `pausedUntil`** and returns them on `PublicShopDto`. The client stops computing open/closed itself, removing the disagreement at the source (theme 2). Check SSR caching/revalidate so a cached page cannot show stale "Open".
- **Pause:** `shops.orders_paused_until TIMESTAMPTZ NULL` plus `orders_paused_indefinitely BOOLEAN NOT NULL DEFAULT false` (or a sentinel), with `_aud` mirrors. Endpoints `PUT /api/v1/shops/{id}/pause {minutes|untilReopened}` and `DELETE …/pause`. They are absolute and therefore idempotent, and gated `require(shopId, STAFF)`, because the kitchen hand pauses during a rush (discretion; SHOP_MANAGER is the stricter alternative).
- Enforcement goes into **one** `ShopAvailabilityPolicy` called by the storefront shop read (customer message), `createGuestOrder`, and the checkout config. Throw a typed error, e.g. `https://jtoye.uk/errors/shop-closed` with `reason: PAUSED|CLOSED_NOW|CLOSED_TODAY` and `reopensAt`, replacing today's `IllegalArgumentException` prose. Keep the human wording (goods P2-CHA-14: "server refuses in clear, human words … shop closed").
- **Vendor/MCP orders while paused:** D-15 says storefront and checkout. Recommend the vendor path is not blocked (a phone order during a pause is legitimate); record the reason.
- **Time zone:** keep `Europe/London` (goods P2-CHA-18, UK time across midnight); test the DST boundary.
- **MCP:** no pause tool; record the reason (operational kitchen control, human-in-loop).

## 37-C Checkout integrity (after 31.1, which is merged)

### 37-C.1 D-16 basket re-validation, composed with 31.1's stale-acknowledgement 409 (UXT-007)

**How 31.1 built the refusal (the composition point).**
- `PublicStorefrontService.placeGuestOrder` loops over `request.getItems()` (`:1010-1066`, `order.addItem` at `:1062`). In the **same loop and transaction** that reads each `Product`, it:
  - snapshots the allergen mask (`OrderAllergenSnapshot.capture`, `:1056`);
  - ORs `currentAllergenMask`;
  - builds `List<AllergenAcknowledgementStaleException.StaleLine>`.
- After the loop, a missing ack throws `AllergenAcknowledgementRequiredException` (422, `:1072`) and a mismatch throws `AllergenAcknowledgementStaleException(currentMask, names, ackMask, lines)` (409, `:1079`).
- The handler (`GlobalExceptionHandler.java:773-786`) sets `type https://jtoye.uk/errors/allergen-acknowledgement-stale`, `code ALLERGEN_ACKNOWLEDGEMENT_STALE`, and properties `currentAllergenMask`, `currentAllergens`, `acknowledgedAllergenMask`, `lines`.
- Both are thrown **inside** `idempotencyService.executeWithoutStoringResponse(...)` (`:790`), so the reservation rolls back and a corrected resubmit under the same key succeeds (T-31.1-10).
- The frontend recognises the type by suffix (`checkout/page.tsx:114-131`).

**Inside the same loop today**, other failures throw immediately, one line at a time:
- not found / other shop → `ResourceNotFoundException` 404 (`:1011-1026`, shop check `:1023`, non-disclosing);
- unavailable → `IllegalArgumentException` (`:1029`);
- insufficient stock → `IllegalArgumentException` (`:1033-1037`);
- below minimum → `IllegalArgumentException` (`:1105-1109`).

Unit prices are always the server's (`:1042`, goods P2-KYL-P2).

**Fix shape (compose, not beside):**
1. **Request:** `GuestOrderItemRequest.expectedUnitPricePennies` (Long) and `GuestOrderRequest.expectedTotalPennies` (Long). **Both must be `@JsonInclude(JsonInclude.Include.NON_NULL)`**, exactly as 31.1 did for `acknowledgedAllergenMask` (`GuestOrderRequest.java:87-94`). Otherwise `IdempotencyFingerprintGoldenTest` (`storefront.guest-order` and `.legacy` rows, Boot-3.5 hashes in `src/test/resources/jackson2-golden/`) goes red and every in-flight key 422s across the deploy.
2. **Server:** in the existing loop, *collect* `BasketChange` entries instead of throwing per line:
   - `PRICE_CHANGED {productId, name, expected, current}`;
   - `UNAVAILABLE {productId, name}`;
   - `SOLD_OUT {productId, name, requested, available}`;
   - `REMOVED {productId}` with no name, preserving the non-disclosure of another shop's product (goods P2-KYL-P3);
   - after the loop, `BELOW_MINIMUM {minimum, subtotal}` and `TOTAL_CHANGED {expected, current}` (delivery fee).
   If any change exists, throw one `BasketChangedException`, mapped to 409 `https://jtoye.uk/errors/basket-changed`, `code BASKET_CHANGED`, `changes[]`. If the allergen set also differs, carry the same four allergen properties on the same problem so the client re-renders both panels and asks for one re-confirmation. If **only** allergens differ, throw the 31.1 exception unchanged, so its contract and its jest suites `allergen-ack-stale.test.tsx` and e2e `allergen-ack-race.spec.ts` stay green.
3. **Missing expected prices:** recommend a 422 `basket-confirmation-required`, mirroring 31.1's missing-ack 422, with the frontend always sending them. This is the planner's choice: optional-when-absent keeps unknown clients working but makes the check skippable. **Open question 4.**
4. **Order of checks:** shop pause/closed first (`validateShopIsOpen` today runs before the idempotency call), then the basket diff, then the allergen ack. Keep the minimum check's human wording inside the diff entry.
5. **UI:** render "Suya £9.00 → £14.00" per change. Update the stored cart's prices **without** touching its ownership stamp (client-persisted identity lifecycle contract; R-16 trap). Require re-confirmation, re-ticking the allergen box if the set changed, and resubmit with the **same** Idempotency-Key (goods P2-CHA-12/13, P2-RAV-20).
6. **Failing-direction test:** the UXT-007 repro (vendor reprices between render and submit) must red on the pre-fix tree. Today it gives 201 with a higher total.

### 37-C.2 Stop rendering unapplied promotions (UXT-008, D-17)

Promotions are read for display only:
- `ShopConfigDto.activePromotions` (`PublicStorefrontService.java:242-246`);
- a dedicated `GET /public/shops/{slug}/promotions` fetched by `shop-detail-client.tsx:377` (per-product chip at `:246`, `promotionsByCategory` at `:454-460`);
- the SSR initial payload (`lib/storefront-server.ts`).

The order path has no promotion logic. Fix:
- Stop returning active promotions on public endpoints, or return `[]` behind a `promotions.applied-at-checkout=false` flag.
- Remove the storefront rendering.
- On `/dashboard/marketing`, tell the vendor promotions are not shown to customers until checkout applies them.

Vendor-authored *announcements* (free text, e.g. "20% off selected dishes") cannot be policed by code. Recommend copy guidance on the announcement form, and record that as the residual. Keep the dashboard promotion CRUD (Incremental Betterment: don't delete vendor data).

### 37-C.3 Confirmation route (UXT-029)

The card path already does `saveLocalOrder({orderNumber, email, shopSlug, placedAt})` then `router.push('/shop/{slug}/orders/{orderNumber}')` (`checkout/page.tsx:211-219`). The COD path renders `codConfirmation` in place (`:605-626,676-…`).

Fix:
- Give COD the same route, with a one-shot "just placed" marker so the page announces "Order confirmed, ORD-…", moves focus to its `h1`, and sets the title.
- Refresh must not re-POST (goods table: "a confirmation route must not re-POST on refresh"). The route reads tracking data, so no POST happens on refresh.
- **Carry the COD breakdown** (subtotal, delivery, VAT, total, recorded allergen set) to the order page. `PublicOrderStatus` has `vatRate` and the allergen record, but the page today shows only `totalAmountPennies` (`orders/[orderNumber]/page.tsx:21-38`). Dropping the breakdown would be regression by omission (goods P2-REG-17 no drip pricing).
- Tracking stays bound to number + email (goods F3/P2-KYL-P5). `saveLocalOrder` supplies the email.

### 37-C.4 Reviewer name (UXT-044)

`ReviewService.java:96` stores `order.getCustomerName()` and `toDto` (`:173`) returns it publicly. `shop-detail-client.tsx:738` renders `review.customerName || "Anonymous"`. JSON-LD carries only the aggregate (`lib/structured-data.ts:189-198`).

Fix: derive the public display name at read time ("Grace P.": first name plus initial), keep the stored value for erasure (V67 already scrubs it), and add a notice on the review form. Changing the public DTO field semantics is an OpenAPI description change; regenerate the snapshot.

## 37-D Abuse resistance

### 37-D.1 Trusted-proxy client IP (UXT-006, D-19)

**Algorithm:** if `request.getRemoteAddr()` is inside a configured trusted CIDR, walk `X-Forwarded-For` from the **right**, skipping trusted addresses; the first untrusted address is the client. Otherwise use `getRemoteAddr()`. MDN's "trusted proxy list" method: "searched from the rightmost, skipping all addresses that are on the trusted proxy list. The first non-matching address is the target address", and "Leftmost (untrusted) values must only be used for cases where there is no negative impact" `[CITED: developer.mozilla.org X-Forwarded-For]`.

**Shape:**
- Make it a Spring bean with `jtoye.security.trusted-proxies` (list of CIDRs, default empty).
- Parse with `java.net.InetAddress` plus prefix math. Handle IPv6 and IPv4-mapped IPv6. No new dependency is needed. Spring Security's `IpAddressMatcher` (`org.springframework.security.web.util.matcher.IpAddressMatcher`) already matches CIDRs `[ASSUMED: present in spring-security-web on the classpath; verify import compiles]`.
- Update both callers (`RateLimitInterceptor:322`, `DsarIntakeRateLimiter:146`) and the `DsarIntakeRateLimiter` javadoc (`:57-60`, which describes the first-hop trust as intentional).

**Pitfalls.**
- `server.forward-headers-strategy` is not set (the only grep hit on `forwarded` in `application*.yml` is the DSAR comment at `:293`), so Tomcat does not rewrite `remoteAddr`. Keep it unset, otherwise RemoteIpValve's own internal-proxy regex becomes a second, silent trust list.
- **Compose:** browsers call core directly via `NEXT_PUBLIC_API_URL` (`lib/public-api-client.ts`), so under default-trust-none every host client appears as the docker bridge gateway. All local clients share **one** public bucket (compose sets 600/min + burst 120 at `docker-compose.full-stack.yml:280-281`) and, after D-18, one per-IP cash-order cap. Compose needs a config-declared cap high enough for the E2E suite, or E2E will 429.
- **k8s:** ingress-nginx runs in namespace `ingress-nginx` (`k8s/base/networkpolicies/30-edge-go.yaml:38`; `k8s/DEPLOYMENT.md:51-55`). The controller pod CIDR is cluster-specific (AKS kubenet/Azure CNI) and **not in this repo**. Put the overlay value (`k8s/staging`, `k8s/production`, `k8s/local`) into `configmap-patch.yaml`; the exact CIDRs are an operator input `[ASSUMED]`, so add a USER-SETUP entry. A too-wide CIDR (e.g. `0.0.0.0/0`) re-opens UXT-006, so the render invariant should refuse `/0` and anything wider than a private /8.

### 37-D.2 Edge per-tenant limiter (UXT-039)

`main.go:166-200` is one channel-based bucket (20 rps, burst 40 from `RATE_LIMIT_RPS`/`RATE_LIMIT_BURST`), applied to all routes before auth (`:281`), with an untyped body. The protected group is `r.Group("/")` with `jwtMiddleware.Validate()` and only `POST /api/v1/sync/batch` (`:328-330`). The JWT middleware sets `c.Set("tenant_id", …)` (`internal/middleware/jwt.go:213`).

Fix:
- Keep a (much larger) pre-auth process-wide valve as the DoS guard.
- Add a per-tenant limiter **after** `jwtMiddleware.Validate()` in the protected group.
- Use a bounded map (an LRU or idle-eviction cap, the `DsarIntakeRateLimiter` overflow-bucket idea), and refill lazily per bucket rather than with one goroutine per tenant.
- 429 as `application/problem+json` with `type https://jtoye.uk/errors/rate-limited` (core's type, `RateLimitInterceptor.java:230`), plus headers `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` (core's names, `:54-57`).
- Document 429 in the edge swag annotations. The edge OpenAPI test `cmd/edge/openapi_test.go` and the contract gate apply.
- **Library:** `golang.org/x/time/rate` is not in `go.mod`/`go.sum` (`rg` rc=1). Either add it (Go-project extended library, `[ASSUMED]` legitimacy, needs a checkpoint) or hand-roll lazy-refill buckets, as the edge already hand-rolls one.

### 37-D.3 Per-identity caps and the unverified flag (UXT-041, D-18)

- "Open" = PENDING, CONFIRMED, PREPARING or READY, with `payment_status` NONE / COD path.
- **Email and phone:** count open orders in the shop's tenant by normalised `customer_email` / `customer_phone`, with an index such as `(tenant_id, lower(customer_email)) WHERE status IN (...)`. Do it before the save, inside the reserved idempotency work, so a capped request rolls back its key.
- **Per IP:** counting open orders per IP requires the IP on the order, which is personal data. Options:
  - (a) Persist an HMAC-SHA256 of the resolved IP (keyed, since a plain hash of the IPv4 space is reversible). The erasure scrub nulls it, and a retention purge runs.
  - (b) A Redis/Bucket4j per-IP counter of cash orders per window, which is a rate rather than an "open" count.
  D-18 locks "open cash orders per … client IP", so (a) is the literal reading. **Open question 5** (PII/retention).
- **429 type must NOT be `rate-limited`.** `lib/api-client.ts:134-141` auto-retries a 429 whose type is `https://jtoye.uk/errors/rate-limited`. A cap is not time-based, so use `https://jtoye.uk/errors/order-cap-exceeded` (`code ORDER_CAP_EXCEEDED`, `axis: EMAIL|PHONE|IP`). `publicApiClient` has no retry interceptor.
- **Unverified flag:** the guest-order POST receives no customer token. `X-Customer-Token` is read only by `/orders/mine` (`PublicStorefrontController.java:255-278`). So every storefront cash order is "unverified contact" unless the request carries a verified customer token (`CustomerJwtVerifier.verifiedEmail`, `require-verified-email`) whose email matches `customerEmail`. Record `orders.contact_verified BOOLEAN NULL` plus `_aud` (NULL = not recorded, for historic rows; no backfill, the V63 rule). The kitchen card badge reads the stored fact.
- **Bulk reject (SC-4):** `POST /api/v1/orders/bulk-cancel {orderIds[], reason}`, STAFF+ on each order's shop, each going through `transitionOrder(CANCEL)` so stock restore, outbox events and customer emails behave exactly as a single cancel. Return per-id results and require an Idempotency-Key. UI: multi-select in the New lane and on the Orders page.

### 37-D.4 Quantity and basket caps (UXT-042, D-20)

`GuestOrderItemRequest.java:13` has `@Min(1)` only. The vendor path's `OrderItemRequest` is similar (verify).

Enforce in one domain validator called by `placeGuestOrder`, `OrderService.createOrder` (covering vendor, REST and MCP) and `updateOrder`. Bean Validation alone protects only DTOs (theme 6). Proposed keys under `jtoye.order-limits`:
- `max-line-quantity`, `max-basket-units`, `max-lines`, `max-basket-total-pennies`;
- typed 422 `https://jtoye.uk/errors/order-limit-exceeded` with the limit and value.

**Defaults must clear goods P2-CHA-16** ("A 20-line, 40-unit basket is comfortable: no limits hit") and the UXT-007 repro's real baskets (about 35 items, £198.50 for an office lunch). For example: line quantity 50, units 200, lines 50, total £1,000 `[ASSUMED]`. Owner confirms (Claude's discretion per CONTEXT).

### 37-D.5 Review shop check (UXT-005, D-21)

Add `if (!shop.getId().equals(order.getShopId())) throw new IllegalArgumentException("This order was not placed at this shop")` after the email check (`ReviewService.java:75-77`). Keep the completed, matching-email and one-per-order gates (goods P2-REG-18, P2-KYL-P4). Fail direction: the cross-shop repro gives 201 today.

## 37-E Integrator surface

### 37-E.1 Partial update must not turn stock tracking off (UXT-016)

**Root cause.** `ProductMapper.updateEntity` is bean-level `IGNORE` *except* `quantityInStock`, which is `SET_TO_NULL` on purpose. The dashboard sends `trackInventory ? qty : null`, and without this a vendor could never turn tracking off (`ProductMapper.java` comment block above `updateEntity`). A DTO cannot tell an absent field from an explicit null.

**Fix.**
- Add `Boolean trackInventory` to `CreateProductRequest`:
  - null/absent → preserve both stock and tracking;
  - `false` → clear stock;
  - `true` → require `quantityInStock`.
- Make `quantityInStock` IGNORE like the others.
- Change the dashboard form to send `trackInventory` **in the same PR**, otherwise the ability to turn tracking off silently regresses.
- Add a narrow `PATCH /api/v1/products/{id}` for `available`, `quantityInStock` and `trackInventory` (and, if the planner wants, `pricePennies`). It must exclude allergen and ingredients fields, so an EPOS availability flip can never overwrite allergen data (the UXT-016 impact). Gate it with `SCOPE_catalog:write` plus `require(product.shopId, SHOP_MANAGER)`, the same as PUT.
- Product create/update is not an idempotency adopter (`IdempotencyFingerprintGoldenTest.REQUEST_SAMPLES` lists `customers.create, media.reprocess, media.upload, orders.create, storefront.guest-order(.legacy), webhooks.replay`), so the golden is untouched. Regenerate the OpenAPI snapshot.

### 37-E.2 Sync requires a shop; per-item results (UXT-017, UXT-037)

**Current behaviour** (`SyncService.java`):
- No `shopId` on `SyncItem` (fields `type, sku, title, ingredientsText, allergenMask, pricePennies, name, address`).
- Products are created shop-less.
- Unknown types and missing sku/name `return false`, yet the response is `{status: "SUCCESS", processedCount}`.
- It bypasses `ProductService` (no allergen spans, may-contain or media).
- `findBySku(sku)` is tenant-wide.

`OrderService.createOrder` accepts shop-less products at any shop, deliberately (`:214-222`, "the null arm is DELIBERATE … V20", check at `:219`), while the storefront rejects them (`PublicStorefrontService.java:1023`).

**Fix.**
- **`SyncItem.shopId`:** required on product create (typed 400); on update it must match the existing product's shop. Add `available` and `quantityInStock`, and route the write through `ProductService` so the allergen-span parse runs.
- **Per-item results:** `results: [{index, sku|name, outcome: CREATED|UPDATED|REJECTED, code, message}]`. The batch status becomes `SUCCESS | PARTIAL | REJECTED`. The planner chooses whether a batch with any rejected item is a 207-style 200 with per-item outcomes, or an all-or-nothing typed 422.
- **Orders:** make `createOrder` refuse shop-less products (`ResourceNotFoundException`, the non-disclosing 404 shape already used). That aligns all writers on "a product must belong to the order's shop" (SC-5) and closes the wrong-allergen line (a shop-less "Jollof Rice" mask 0 on a Mama Ade's order).
  - This changes the V20 "tenant-wide product" semantic for orders. Existing shop-less products remain listable but are no longer orderable anywhere, which they already are not on the storefront.
  - UXT-064 (P2, editing an orphan moves it to All Shops) is out of scope.
- **The edge drops everything new.** `edge-go/internal/core/client.go:137-153`: any status other than 200/202 → error → handler responds `502 {"error":"failed to sync with core API"}` (`cmd/edge/handlers.go:180-185`). The success body is re-encoded into `BatchSyncResponse{Status, ProcessedCount}` only (`:79-82,148-153`). Fix in the same PR:
  - pass core 4xx `application/problem+json` through verbatim with its status;
  - add `Results` to both edge structs;
  - update `cmd/edge/types.go:48-53` swag docs, the edge OpenAPI test and the edge↔core contract gate (`internal/core/contract.go` `EdgeCoreCalls`, `TestEdgeCoreContract`).

### 37-E.3 MCP orders reach the kitchen; list_products filters (UXT-036, UXT-040, D-13)

- **D-13 without removing a capability.** The dashboard deliberately creates DRAFT orders with a "Submit" action (`app/dashboard/orders/page.tsx:127,189,209-212,518`). Flipping `createOrder` to PENDING for every caller would remove the draft capability. **Recommend** an optional `CreateOrderRequest.submit` (Boolean, `@JsonInclude(NON_NULL)`, because `orders.create` is a fingerprinted idempotency adopter). When true, the server runs the existing SUBMIT transition (DRAFT→PENDING) in the same transaction, through `stateMachineService`, emitting the same outbox `publishStateChange(DRAFT, PENDING)` the storefront COD path emits (`PublicStorefrontService.java:1218-1221`). The MCP `create_order` tool always sends `submit: true` and states "The order is created as PENDING and appears in the kitchen's New lane" in its description (`create-order.ts:177-185`). The dashboard is unchanged. One domain path, no `submit_order` tool.
- **UXT-040.** `list_products` accepts only `page`/`size` (`list-products.ts:21-24`). Zod strips `shopId` silently. Core already supports `?shopId=` (`ProductController.java:65`).
  - Add `shopId` (uuid) and `availableOnly` (filter in core, so the MCP stays a thin forwarder).
  - Add `allergens: string[]` (names, `AllergenCatalog.namesFor(mask)`) to `ProductDto`, rather than a third copy of the 14-name catalogue in TypeScript. A parity test already binds Java ↔ `frontend/types/api.ts`.
  - Regenerate the OpenAPI snapshot. The MCP schemas are "verified against docs/api/openapi-snapshot.json" (goods P2-RAV-19).

### 37-E.4 Webhook auto-pause (UXT-038)

**Root cause** (`WebhookDeliveryWorker.java:161-262`).
- `consecutiveFailures` is per **subscription** and increments on every failed **attempt** across all deliveries (`:235-243`). With `autoPauseThreshold = 10` and `maxAttempts = 8` (`WebhookProperties.java`), two events × 5 attempts pause the endpoint in about 46 s.
- Once paused, `attemptDelivery` marks every queued delivery **terminally FAILED** (`:163-169`), so events are lost.
- Nobody is notified.
- `WebhookSubscription.Status { ACTIVE, PAUSED, AUTO_PAUSED, REVOKED }` and `WebhookDelivery.Status { PENDING, DELIVERED, RETRYING, FAILED }` `[VERIFIED: WebhookSubscription.java:43-48, WebhookDelivery.java:43-48]`. Neither column has a CHECK constraint (V55:30, V56:34 are comments; the only `CHECK` hits are the RLS `WITH CHECK`), so adding a status needs no constraint rewrite.

**Fix.**
- Count consecutive **failed deliveries** (exhausted attempts), not attempts, or require failures to span a config-declared minimum window.
- A non-ACTIVE subscription leaves deliveries `HELD` (new status), and resume/replay re-queues them.
- On auto-pause, email the tenant contact (`Tenant.contactEmail`, `Tenant.java:57-58`, V48) or the tenant's group admins, through `EmailNotificationService` (new `@Async` method), once per pause.
- Keep the goods: P2-RAV-21 (secret UX, delivery log, auto-pause banner, replay) and F2 (SSRF on the resolved IP).

### 37-E.5 Integrator credentials (UXT-035, D-22..D-24)

**What `KeycloakAdminClient` supports on the Boot 4.1 tree** (`tenant/keycloak/KeycloakAdminClient.java`, 257 lines; Jackson 3 `tools.jackson` nodes since 38-07):
- `obtainAdminToken()`: master `admin-cli` password grant (`:83-108`);
- `searchUsersByTenant`;
- `setUserEnabled`;
- `logoutUser`;
- `findUsersByEmail`;
- `deleteUser`.

There are **no client-management operations.** Config: `jtoye.keycloak.admin.{enabled, base-url, realms, customer-realm, username, password}` (`application.yml:354-370`); inert unless `configured()`.

**Keycloak 24.0.5 admin REST calls needed** `[CITED: keycloak.org/docs-api/24.0.5/rest-api]`:

| Operation | Call |
|---|---|
| Create client | `POST /admin/realms/{realm}/clients` (ClientRepresentation) → 201; the new client UUID is in the `Location` header `[ASSUMED: Location header; fall back to GET ?clientId=]` |
| Find by clientId | `GET /admin/realms/{realm}/clients?clientId={clientId}` |
| Service-account user (for D-23) | `GET /admin/realms/{realm}/clients/{client-uuid}/service-account-user` → UserRepresentation; its `id` is the token `sub` |
| Read secret (show once) | `GET /admin/realms/{realm}/clients/{client-uuid}/client-secret` |
| Rotate | `POST /admin/realms/{realm}/clients/{client-uuid}/client-secret` (regenerates; returns CredentialRepresentation) |
| Default scopes | `PUT /admin/realms/{realm}/clients/{client-uuid}/default-client-scopes/{clientScopeId}` (ids from `GET /admin/realms/{realm}/client-scopes`), or `defaultClientScopes: [names]` inline in the create body `[ASSUMED: honoured on POST /clients]` |
| Protocol mappers | inline `protocolMappers[]` in the create body; the realm import in this repo uses exactly that representation per client. A client-level `…/clients/{client-uuid}/protocol-mappers/models` endpoint is `[ASSUMED]` (not visible in the summarised docs excerpt) |
| Delete | `DELETE /admin/realms/{realm}/clients/{client-uuid}` |

**ClientRepresentation** (copy `integration-orders-rw`'s wiring from `docs/security-scopes.md` §2): `serviceAccountsEnabled: true`, `standardFlowEnabled: false`, `directAccessGrantsEnabled: false`, `publicClient: false`, `clientAuthenticatorType: client-secret`. Mappers:
- **hardcoded claim** `protocolMapper: oidc-hardcoded-claim-mapper`, config `claim.name=tenant_id`, `claim.value=<tenant uuid>`, `jsonType.label=String`, `access.token.claim=true`, `id.token.claim=false`, `userinfo.token.claim=false` `[ASSUMED: config keys; prove by decoding a minted token]`. Unlike the existing `oidc-usermodel-attribute-mapper` on the SA user attribute, this **sidesteps the KC24 attribute strip entirely** (`docs/security-scopes.md` §5);
- **audience** `oidc-audience-mapper` with `included.client.audience=core-api`, `access.token.claim=true`. **Mandatory:** without it the #88 `AudienceValidator` 401s the token at decode (`docs/security-scopes.md` §2).

Allowed scopes are an allow-list of the six realm scopes. Existing realm scopes, verbatim: `catalog:read, catalog:write, orders:read, orders:write, customers:read, customers:write` `[VERIFIED: realm-export.json clientScopes[]]`. Never `roles`/admin roles.

**Service-account user id → D-23 grant.** After create, `GET …/service-account-user` → `id`. Write an OPERATOR `shop_staff` row (`user_id = SA id`, `shop_id` or NULL, role) directly, **not** via `StaffManagementService.grant()`, which requires a directory row. Record `created_by`. Under strict ON (D-06) this is the credential's only access. Its `sub` is a UUID, so `isDeclaredMachineClient` does not apply. Do **not** add credentials to `jtoye.access.machine-client-ids`.

**Table `integration_credential` (V81).**
- Columns: `id, tenant_id, name, kc_client_id, kc_client_uuid, service_account_user_id, scopes TEXT[], shop_id NULL, role, created_by, created_at, rotated_by, rotated_at, revoked_by, revoked_at, last_used_at`.
- ENABLE+FORCE RLS, NOSUPERUSER proof.
- Either Envers `_aud` with the V65 INSERT-policy shape `(tenant_id IS NULL) OR (tenant_id = current_tenant_id())`, or explicit who/when columns (D-24 "records who did it and when"). Explicit columns are simpler and enough; `_aud` adds full history.
- `kc_client_id` unique, generated server-side (`jtoye-int-<random>`). Keycloak clientIds are realm-unique, so never let a tenant choose it, which would be a collision/impersonation vector.

**Last-used.** Keycloak has no cheap per-client last-use. Record it in core with a throttled best-effort `REQUIRES_NEW` write keyed on the token's `azp`, at most once per N minutes (the D-09 directory-throttle idea). `onRequest` skips read-only transactions, so this must not live there.

**Security findings for the threat model.**
- **Escalation chain:** a credential granted GROUP_ADMIN could call `requireGroupAdmin()` endpoints, including credential and staff management, and mint further credentials. Refuse service-account callers on `/api/v1/credentials/**` and `/api/v1/staff/**` mutations (detect the `azp` of a credential client or the SA `sub` in `integration_credential`), or cap credential roles at SHOP_MANAGER. Recommend both.
- **Secret exposure:** the secret appears only in the create/rotate response, with `Cache-Control: no-store`, and is never persisted or logged (`KeycloakAdminClient` already never logs bearer or password). The Developers page shows it once.
- **Admin privilege in core:** core already holds the master-realm admin password (`username/password` above, `admin-cli` password grant). Client creation does not change that, but widens what it is used for. Recommend recording a residual, or a follow-up least-privilege service account (realm-management `manage-clients` + `view-users` + `manage-users`) instead of master admin.
- **Feature off** (`configured()` false): endpoints return the existing typed 400 "not configured" pattern (the #102 deprovision precedent).

**Endpoints:**
- `POST /api/v1/credentials` (Idempotency-Key; returns the secret once);
- `GET /api/v1/credentials`;
- `POST /api/v1/credentials/{id}/rotate`;
- `DELETE /api/v1/credentials/{id}` (delete KC client, delete the `shop_staff` row, evict membership, stamp `revoked_*`).

All are `requireGroupAdmin()` and human-only. MCP: no tool (recorded reason: credential minting by an agent is out of scope).

**Re-prove the admin calls on the merged tree** (D-22): a by-content `KeycloakAdminClientTest` (MockRestServiceServer, Jackson-3 bodies) for each new call, plus one **live** compose proof. That proof mints a token with the new secret, decodes it, asserts `tenant_id`, `aud=core-api` and the scopes, then calls core and gets 200/403 per scope, then deletes the client and checks the token mint fails. The 38-07 lesson: a Jackson-2 `ObjectNode` handed to Boot 4's RestClient serialises as a bean (`KeycloakAdminClient.java:46-55`).

## 37-F Accessibility (all P2, D-05)

**Current axe coverage.** `frontend/e2e/public-a11y.spec.ts` scans with `withTags(WCAG_TAGS)` (`:207`): the public routes, `/shop`, a storefront, the dish modal, checkout and cart (seeded basket), the cash order confirmation, the per-shop order page, `/track` lookup and result (`:253-571`). It has instrument arms proving it can fail (`:712-773`). `dashboard-a11y-nightly.spec.ts` covers the dashboard. axe reports **0 violations** on the UXT-082 cards (the catalogue says so), so every 37-F test must observe what axe misses.

| Cluster | Surface | Fix | Test that sees what axe misses |
|---|---|---|---|
| UXT-081 | `/shop/[slug]/orders/[orderNumber]/page.tsx` and `/track` poll every 15 s; no live region (only `storefront-nav.tsx:185` `aria-live="polite"`) | `role="status"` region announcing each status change ("Order confirmed by the shop"); `aria-current="step"`; labelled copy button | Playwright: install a `MutationObserver` on `[role=status],[aria-live]` before the vendor transition; assert the announced text. Jest: assert region text updates on poll |
| UXT-082 | card `<a>` wraps `<article>` (`app/shop/shop-discovery-client.tsx`, `components/marketing/*`) | name the link (`aria-label`/`aria-labelledby` the heading), or restructure so the link sits on the heading | **Chrome's AX tree via CDP** (`Accessibility.getFullAXTree`), the instrument the persona used. Playwright `getByRole(..., {name})` uses Playwright's own accname and may return a name Chrome does not `[ASSUMED]`, so it is not a valid oracle |
| UXT-083 | Add → stepper swap drops focus to body; the live region reads "1 1 item in basket" | move focus to the stepper's increment button after add (and back to Add after the last remove); name the item in the announcement ("Mango Lassi added, 1 item in basket") | Playwright `document.activeElement` after Enter; live-region text |
| UXT-084 | cart/checkout/orders pages are `"use client"` with no metadata; only `app/track/layout.tsx:34` exports `metadata` | per-route `layout.tsx` with `generateMetadata` (title per step, including the shop name) | Playwright `page.title()` at each step; it also fixes the Next.js route announcer |
| UXT-085 | `components/public/cookie-notice.tsx`, `consent-banner.tsx`, `bottom-notice-shell.tsx`; banner last in DOM, covers focus | move the banner early in DOM order (or a skip target); `scroll-padding-bottom` equal to banner height so focused elements are not obscured (WCAG 2.2 SC 2.4.11) | Playwright on iPhone 13: for the first 30 tab stops, assert the focused rect does not intersect the banner rect |
| UXT-080 | basket/tracker/shop header at 360 px, root 130% | wrap instead of ellipsis-truncate | Playwright: set `html{font-size:130%}`, assert `scrollWidth <= clientWidth` and no text-overflow clipping on item names |

**Goods:** P2-MAR-P1 (textbook modal), P2-MAR-P2 (allergen gate announced), P2-MAR-P3 (structure and naming), P2-GRA-24 (large-text product sheet/checkout fit), and Priya's focus rings and skip link.

## 37-G Catalogue & shop admin

### 37-G.1 Delivery fee, threshold, collection-only (UXT-023)

The entity has `delivery_fee_pennies` and `free_delivery_threshold_pennies` (`Shop.java:67-71`), but `ShopDto` and `CreateShopRequest` lack both (field lists read: `ShopDto.java:8-31`, `CreateShopRequest.java:18-71`). Nothing models collection-only.

Fix:
- Add both fields to the DTOs and the shop form.
- **V82:** `shops.offers_delivery BOOLEAN NOT NULL DEFAULT true`, `offers_collection BOOLEAN NOT NULL DEFAULT true` plus `_aud`. The defaults preserve today's behaviour, so this is not a fabricated statement: today every shop takes both.
- Enforce with a new `FulfilmentPolicy.requireOffered(shop, type)`. `FulfilmentPolicy` is already the shared seam called by both writers (`OrderService.java:188-191,265`; `PublicStorefrontService.java:975,1115`).
- Show "Collection only" on the storefront (goods P2-REG-17: fees shown everywhere, no drip pricing).
- `FulfilmentType { DELIVERY, COLLECTION }` `[VERIFIED: FulfilmentType.java:8-13]`.

### 37-G.2 Slug stability (UXT-028)

`ShopService.updateShop` regenerates when the request slug is blank (`:271-276`), and the form never sends one. Fix: on update, a blank slug means "keep". Change it only when explicitly sent and validated (`assertSlugNotReserved`). Old-slug redirects are out of scope.

## Standard Stack

No new runtime libraries are required. Everything is built from what the tree already has.

### Core (in use, verified in-tree)
| Library | Version | Purpose | Evidence |
|---------|---------|---------|----------|
| Spring Boot | 4.1.1 | API, security, validation | CLAUDE.md; Phase 38 merged |
| Spring `RestClient` + Jackson 3 (`tools.jackson`) | Boot-managed | `KeycloakAdminClient` | `KeycloakAdminClient.java:11,65-77` |
| Bucket4j (Redis) | 8.10.1 | public/tenant rate limits | `RateLimitInterceptor.java` |
| Flyway | 12 | migrations V76+ (`spring.flyway.out-of-order=true`) | CLAUDE.md |
| Hibernate Envers | Boot-managed | `_aud` mirrors | `@Audited` on Shop/Order/FinancialTransaction |
| Next.js / React | 16.3.7 / 19 | dashboard, storefront | `frontend/package.json:37` |
| `@axe-core/playwright` | 4.13.0 | a11y scans | `frontend/package.json:48` |
| `@playwright/test` | ^1.63.0 | E2E, CDP AX tree | `frontend/package.json:49` |
| Jest | ^30.5.2 | frontend unit | `frontend/package.json:61` |
| Gin | v1.12.0 | edge | `edge-go/go.mod` |
| `@modelcontextprotocol/sdk` | ^1.29.0 | MCP tools | `mcp-server/package.json:15` |
| vitest | ^4 | MCP tests | `mcp-server/package.json:25` |

### Supporting (optional)
| Library | Purpose | Status |
|---------|---------|--------|
| `golang.org/x/time/rate` | per-tenant token buckets in edge | NOT present (`rg` rc=1 on go.mod/go.sum). `[ASSUMED]` legitimacy (Go project's x-repo). Gate behind `checkpoint:human-verify`, or hand-roll lazy-refill buckets |
| `IpAddressMatcher` (spring-security-web) | CIDR match for D-19 | `[ASSUMED]` on classpath; no new dependency if so |

**Installation:** none for npm/Maven. Go only if `x/time/rate` is chosen: `cd edge-go && go get golang.org/x/time/rate`.

## Package Legitimacy Audit

No npm, PyPI or crates package is recommended for installation. The only candidate is the Go module `golang.org/x/time`, and the `package-legitimacy` seam covers npm/PyPI/crates only, so it could not be checked.

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| golang.org/x/time | Go module proxy | not checked | not checked | go.googlesource.com/time `[ASSUMED]` | not run (ecosystem unsupported) | Optional. Planner adds `checkpoint:human-verify` before `go get`, or chooses the hand-rolled bucket |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram (data flow, key Phase 37 paths)

```
Browser storefront ──POST /public/shops/{slug}/orders──► RateLimitInterceptor (ClientIpResolver bean: trusted CIDRs → client IP) ──►
  PublicStorefrontService.createGuestOrder
    ├─ ShopAvailabilityPolicy (pause / structured hours) ──refuse──► 4xx shop-closed
    ├─ IdempotencyService.executeWithoutStoringResponse (reservation; rolls back on any refusal)
    │    └─ placeGuestOrder loop over items (ONE read of each Product)
    │         ├─ collect BasketChange (price/unavailable/sold-out/removed) ──► 409 basket-changed (+allergen props)
    │         ├─ OrderLimits (D-20) ──► 422 order-limit-exceeded
    │         └─ allergen ack (31.1) ──► 422 required / 409 allergen-acknowledgement-stale
    ├─ CashOrderCaps (email/phone/IP open counts) ──► 429 order-cap-exceeded
    └─ save PENDING + outbox publishStateChange(DRAFT→PENDING) ──► RabbitMQ ──► STOMP /topic/kitchen.{tenant}.{shop}
                                                                               └► Kitchen board "New" lane ──Accept──► POST /orders/{id}/confirm
MCP create_order(submit=true) ─► POST /api/v1/orders ─► OrderService.createOrder ─► same SUBMIT transition + same outbox event ─┘
Integrator ─► edge (per-tenant limiter after JWT) ─► /api/v1/sync/batch ─► SyncService (shop required, per-item results) ─► edge passes results/problem+json through
Group admin ─► /api/v1/credentials ─► KeycloakAdminClient (create client, mappers, SA user) ─► shop_staff OPERATOR grant ─► ShopAccessService decides like a person
Group admin ─► /api/v1/staff/invites ─► EmailNotificationService ─► invitee /invite/{tenant}.{token} ─► accept: KC user (tenant_id managed attr) + directory + OPERATOR grant
```

### Recommended structure (new code; follow existing packages)
```
core-java/src/main/java/uk/jtoye/core/
├── security/access/      # EffectiveAccess, StaffInvite*, (credential grant writer)
├── security/ClientIpResolver.java   # → @Component with trusted CIDRs
├── integration/credential/          # IntegrationCredential{,Service,Controller,Repository}
├── order/                # OrderLimits, BulkCancel, void, delete guard
├── storefront/           # BasketChange, ShopAvailabilityPolicy
├── finance/              # entry_kind, shop_id, export
└── exception/            # BasketChangedException, OrderNotDeletableException, OrderCapExceededException, ...
```

### Pattern: typed refusal = exception + one handler + stable type
Follow `AllergenAcknowledgementStaleException` plus `GlobalExceptionHandler.handleAllergenAcknowledgementStale`: an immutable exception carrying the data, mapped once, `type` and `code` stable, properties for client re-render. Throw it inside the idempotency work so the reservation rolls back.

### Anti-patterns to avoid
- **Deriving status in the browser** (staff "No access", open/closed, "Live"). Read the server's fact (theme 2).
- **A second copy of a rule** (hours parser, allergen names, KITCHEN_STATUSES). One source plus a parity test.
- **Literal limits** (D-18/19/20). Config keys with documented defaults.
- **Resetting a shared bean to a literal in test teardown.** Restore the captured value.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| CIDR matching / IPv6 | string-prefix compare | `InetAddress` + prefix math or Spring's `IpAddressMatcher` | IPv4-mapped IPv6, zone ids, `/0` mistakes |
| Token-at-rest for invites | readable token column | SHA-256 digest (V62/V75 rule) | a readable token at rest is a bearer credential |
| Credential issuance | Postgres API keys | Keycloak service-account client (D-22, locked) | one auth path for every gate |
| tenant claim on credentials | SA user attribute | `oidc-hardcoded-claim-mapper` | avoids the KC24 unmanaged-attribute strip |
| Idempotent create | ad hoc dedupe | `IdempotencyService` (V50) or provable idempotency | contract already enforced, fingerprint golden |
| Ledger aggregation | Java-side summing | DB-side JPQL aggregate (`aggregateForCurrentTenant`) | 1k golden, performance |
| Migration backfill on FORCE RLS | bare UPDATE | tenant loop with `set_config` (V44/V57) | bare UPDATE hits 0 rows and reports success |
| Screen-reader name oracle | Playwright `getByRole` name | CDP `Accessibility.getFullAXTree` | Playwright's accname differs from Chrome's tree |

**Key insight:** almost every bug in this phase is a second, divergent copy of a decision (client vs server hours, frontend vs backend statuses, DTO vs domain validation, the Staff page vs `ShopAccessService`). The fix shape is always to delete the copy and read the one source.

## Runtime State Inventory

Not a rename phase, but three decisions change state that lives outside the repo.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | Dev/compose DB: JIT `shop_staff` rows (`grant_source=JIT`) become de-honoured under D-06 (bootstrap kept). `shops.opening_hours` free text in every non-fresh DB (V79 tenant-loop migration). `financial_transactions` lacks `shop_id` (V78 tenant-loop backfill). No real tenant exists yet (Phase 32 gates the first). | Data migrations V78/V79 with two-tenant stepwise tests; no action for JIT rows (inert by design) |
| Live service config | Keycloak `jtoye-dev` realm is Postgres-backed. Re-import does NOT overwrite (`--import-realm` creates only). The D-07 user-profile change must be applied with `kc.sh import --override true` locally and by an operator in staging/prod (realm not in repo). Credential clients created by D-22 live only in Keycloak. | Operator step in USER-SETUP; re-import command in plan |
| OS-registered state | None. Verified: no scheduler/OS registration is touched by these decisions. | none |
| Secrets/env vars | `ACCESS_STRICT_SCOPING` (compose, `.env.example`, k8s configmap, goldens) flips to true. New: `jtoye.security.trusted-proxies` (per overlay), invite TTL, cap and limit keys. `KC_ADMIN_*` already armed in compose (31.1 D-22). | Code/config edits plus `check-env-contract.sh` and goldens regenerate |
| Build artifacts / caches | Redis `shopMembership` cache: strict-scoping is applied outside the cache (`ShopAccessService.java:364-367`), so no stale decision, but evict on grant writes as today. Running containers must be rebuilt after each sub-theme (CLAUDE.md). | rebuild all images; `check-runtime-freshness.sh` |

## Migration numbering (reserve up front)

Head is **V75** `[VERIFIED: ls db/migration, 75 files, last V75__dsar_access_export.sql]`. `spring.flyway.out-of-order=true` is set in all profiles (CLAUDE.md, V44 history), so independently merged PRs may land out of numeric order. The paused Phase 29 branch stops at V61 (`git ls-tree origin/phase-29-research`), so there is no collision. **Reserve numbers per sub-theme in the plans**, so parallel PRs never pick the same V:

| V | Sub-theme | Content | RLS/proof |
|---|---|---|---|
| V76 | 37-B | `staff_invite` (+ `user_directory.realm_admin_seen_at` if chosen) | new table ENABLE+FORCE via `current_tenant_id()`; NOSUPERUSER cross-tenant read/update/insert proof; `RlsContractTest` sweep |
| V77 | 37-B | `financial_transactions.entry_kind`, `reverses_transaction_id`, index swap; `orders.voided_*` + `_aud` | existing policies; stepwise test that the old unique index is replaced |
| V78 | 37-B | `financial_transactions.shop_id` + `_aud` + tenant-loop backfill | two-tenant stepwise backfill test |
| V79 | 37-A | `shops.opening_schedule`, `hours_source`, pause columns + `shops_aud` + tenant-loop backfill | two-tenant stepwise test incl. NULL, `closed`, unparseable, overnight |
| V80 | 37-D | `orders.contact_verified` (+ client-IP digest if chosen) + `_aud`; cap indexes | no backfill (NULL = not recorded) |
| V81 | 37-E | `integration_credential` (+ `_aud` if Envers) | new table ENABLE+FORCE; NOSUPERUSER proof; `_aud` V65 INSERT shape |
| V82 | 37-G | `shops.offers_delivery/offers_collection` + `shops_aud` | defaults preserve behaviour |

The V43 lesson: if any new column takes a CHECK, list every value it will ever need now. Late CHECK extensions force constraint rewrites (V36 REFUNDED). The no-`CREATE EXTENSION` gate (`scripts/check-no-create-extension.sh`) applies, and Flyway substitutes `${…}` even in comments (memory trap).

## Common Pitfalls

### Pitfall 1: The strict-scoping flip looks green while tests run OFF
**What goes wrong:** 11 test classes reset the singleton to a literal `false`; later classes in the cached context run OFF.
**How to avoid:** capture/restore the original value; assert the booted value from the context; run the full integration suite once after the flip, alone.
**Warning signs:** a test class that passes alone but fails when run first in the suite.

### Pitfall 2: Invited users get no tenant
**What goes wrong:** KC24 silently drops `tenant_id` on admin-API create; the token has no tenant claim, and every call 4xxs or returns RLS-empty data.
**How to avoid:** declare the attribute (admin-only edit), re-import with `--override true`, and decode a real token in the live proof.

### Pitfall 3: Self-service tenant hopping
**What goes wrong:** `unmanagedAttributePolicy: ENABLED` lets users edit `tenant_id` from the account console.
**How to avoid:** a declared attribute with `edit: ["admin"]`, or `ADMIN_EDIT`; add a threat-model entry and a negative test (user PUT to the account API is refused).

### Pitfall 4: The reversing entry collides with V40's unique index
**What goes wrong:** the second row for an order fails `uq_fin_tx_tenant_order`, or `findByOrderId` throws `IncorrectResultSize`.
**How to avoid:** add `entry_kind` to the index and the finder in the same migration and PR.

### Pitfall 5: The FORCE-RLS backfill updates zero rows
Applies to V78 and V79. Use the tenant loop; seed ≥2 tenants in a stepwise Flyway test. Fresh Testcontainers DBs cannot catch it.

### Pitfall 6: New request fields break the idempotency fingerprint
Any field added to `GuestOrderRequest`, `GuestOrderItemRequest` or `CreateOrderRequest` without `@JsonInclude(NON_NULL)` changes the Boot-3.5-pinned hash (`IdempotencyFingerprintGoldenTest`). In-flight keys then 422 across the deploy.

### Pitfall 7: The edge swallows the new contract
Core's typed 4xx becomes an edge 502, and new response fields are dropped (`edge-go/internal/core/client.go:137-153`). The 37-E and 37-D edge work must land with the core change, together with the contract gate.

### Pitfall 8: A cap 429 auto-retried by the dashboard client
`api-client.ts` replays 429s whose type is `rate-limited`. Use a distinct type for caps.

### Pitfall 9: Compose sees one client IP
Default-trust-none plus direct browser→core means the per-IP caps and public buckets are shared by every local client and E2E run. Set compose overrides; do not loosen the trust default.

### Pitfall 10: Demo storefronts close on the hours flip
No seeded shop has hours, so empty → CLOSED would close every demo shop and break order E2E. Use the migration's `LEGACY_ALWAYS_OPEN` plus explicit seeder hours (§37-A.3).

### Pitfall 11: Kitchen audio that claims to be on
A per-beep `new AudioContext()` with swallowed errors (`page.tsx:118-136`) fails silently. Use one context unlocked by a tap and show its real state.

### Pitfall 12: The unverified-contact badge with no data behind it
The guest POST carries no customer token today. Without recording `contact_verified`, the badge would be derived, not stored.

### Pitfall 13: "Effective access" for realm admins
The DB cannot see `ROLE_admin`. Record it at directory upsert or label it as unknown.

### Pitfall 14: Vacuous criteria
A grep that already returns 0 (for example "no `strict-scoping: false` in compose") must be shown red on a broken copy. Kitchen "Live" assertions must test each degraded state (lapsed, muted, offline, API down), never only the happy path.

## Code Examples

### Tenant-loop backfill (the V44/V57 pattern, from the project's recorded trap)
```sql
-- Source: memory trap_rls_migration_backfill (V44 canonical write-up); required for V78/V79
DO $$
DECLARE t RECORD;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.current_tenant_id', t.id::text, true);
    UPDATE financial_transactions ft
       SET shop_id = o.shop_id
      FROM orders o
     WHERE ft.tenant_id = t.id AND o.tenant_id = t.id
       AND ft.order_id = o.id AND ft.shop_id IS NULL;
  END LOOP;
  PERFORM set_config('app.current_tenant_id', '', true);
END $$;
```

### Composed refusal (shape only; names per this research)
```java
// Inside PublicStorefrontService.placeGuestOrder, after the existing per-item loop (:1010-1066)
if (!basketChanges.isEmpty()) {
    boolean allergenStale = ack != null && ack != currentAllergenMask;
    throw new BasketChangedException(basketChanges,
            allergenStale ? currentAllergenMask : null,
            allergenStale ? AllergenCatalog.namesFor(currentAllergenMask) : null,
            ack, allergenStale ? allergenLines : List.of());
}
// else: the unchanged 31.1 checks (AllergenAcknowledgementRequiredException / ...StaleException)
```

### Request field that keeps the fingerprint golden green (31.1 precedent)
```java
// Source: GuestOrderRequest.java:87-94 (31.1)
@JsonInclude(JsonInclude.Include.NON_NULL)
private Long expectedUnitPricePennies;
```

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| Leftmost XFF hop | Rightmost-untrusted from a trusted-proxy list (MDN) | Closes UXT-006 |
| KC user attribute `tenant_id` + unmanaged attrs | KC24 declarative user profile (attributes must be declared) | Invites must declare the attribute |
| Implicit admin (strict OFF) | Deny-by-default plus explicit grants | UXT-003/004 |

**Deprecated/outdated in this tree after the phase:** the WARN text and comments naming "#285 / SEC-5 / integration-orders-rw" as blockers. Rewrite them, not just the value.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The realm-JSON location/key for the KC24 declarative user-profile config | 37-B.3 | Invite users lack `tenant_id`; caught by the live proof |
| A2 | `POST /clients` returns the client UUID in `Location`, and honours inline `protocolMappers`/`defaultClientScopes` | 37-E.5 | Need follow-up GET/PUT calls; small |
| A3 | `oidc-hardcoded-claim-mapper` config keys (`claim.name`, `claim.value`, `jsonType.label`, `access.token.claim`) | 37-E.5 | Tokens lack `tenant_id`; caught by decode |
| A4 | A client-level `protocol-mappers/models` endpoint exists | 37-E.5 | Use inline mappers instead |
| A5 | `IpAddressMatcher` is on the classpath | 37-D.1 | Hand-write prefix math (small) |
| A6 | Playwright's accname can differ from Chrome's tree for `<a><article>` | 37-F | Test could pass vacuously; use CDP regardless |
| A7 | `navigator.getAutoplayPolicy()` availability in Chromium | 37-A.1 | Feature-detect; fall back to `AudioContext.state` |
| A8 | Ingress pod CIDRs per environment | 37-D.1 | Wrong CIDR = spoofable or shared buckets; operator input |
| A9 | Default caps (line 50, units 200, lines 50, £1,000; open cash orders per email/phone 3, per IP 5) | 37-D.3/4 | False refusals of real large orders; owner confirms |
| A10 | Invite TTL 72 h | 37-B.3 | UX only |
| A11 | `golang.org/x/time` legitimacy | Stack | Use the hand-rolled bucket instead |

## Open Questions (owner/planner decisions)

1. **Invite password flow (D-07).**
   - What we know: registration is disabled in the vendor realm; D-07 says the invite is sent via `EmailNotificationService`.
   - What's unclear: whether our accept page collects the password (it transits core) or Keycloak's `execute-actions-email` sends a second email.
   - Recommendation: our page collects it; never log it; map KC's password-policy 400 to a typed 422.
2. **VAT for non-registered traders (UXT-047).**
   - What we know: the display rule is clear.
   - What's unclear: whether the ledger should stop computing VAT. Needs legal confirmation, per the catalogue itself.
   - Recommendation: display fix in this phase; ledger change only on an owner ruling.
3. **Empty-hours shops (D-15).**
   - Recommendation: `LEGACY_ALWAYS_OPEN` (migration-only, flagged to the vendor), versus closing them on deploy.
   - Recommend the former, plus explicit seeder hours.
4. **Expected prices required or optional (D-16).**
   - Recommendation: required (422 when missing), the same as 31.1's ack; the frontend always sends them.
5. **Per-IP cap storage (D-18).**
   - Recommendation: a keyed HMAC digest of the IP on the order (erasure + retention covered), versus a Redis rate counter. This is a GDPR choice.
6. **Void as columns vs a `VOIDED` status (D-10).**
   - Recommendation: columns.
7. **Credential role ceiling (D-23/D-24).**
   - Recommendation: forbid GROUP_ADMIN for credentials, and refuse service-account callers on staff/credential management.
8. **Roadmap SC re-statement conflicts the planner must resolve** (D-01: "Do not plan a criterion for a cluster that moved to 37.x"):
   - SC-1: drop "a double-tap cannot skip a status" (UXT-049, P2). "Full order number" stays (UXT-011 card).
   - SC-5: "API, MCP, sync and CSV refuse what the storefront refuses (**unavailable products** … **missing contact details**)". Those are UXT-097 and UXT-072, both **P2** → 37.x. Keep "shop-less items" (UXT-017). Restate SC-5 as shop-less refusal, partial update preserving stock, MCP order reaching the kitchen, scoped revocable credential, sync per-item reporting, webhook pause notification, MCP shop filter.
   - SC-6: "Keycloak sign-in and registration pass WCAG 2.2 AA" is **UXT-079 (P2, homed to Phase 33)**, not one of UXT-080..085. Drop it from Phase 37's SC-6, or record the owner's choice to pull UXT-079 in.
   - SC-2 "Per-shop finance figures are the shop's own and can be exported": UXT-027 (in scope), so export is in scope.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK | core-java | ✓ | openjdk 25.0.4.1 | — |
| Gradle wrapper | core-java | ✓ | 9.7.1 (`gradle-wrapper.properties`) | — |
| Node | frontend, mcp-server | ✓ | v22.23.3 (CLAUDE.md says 24+; no `.nvmrc`/`engines` pins it) | prior phases ran jest/build locally on 22 |
| npm | frontend, mcp | ✓ | 12.2.0 | — |
| Go | edge-go | ✓ | go1.27.1 | — |
| Docker | Testcontainers ITs, compose E2E | present (not invoked, per instructions; stack rebuilding) | — | ITs and E2E wait for the stack |
| Keycloak (compose) | D-07/D-22 live proofs | compose service; KC_ADMIN armed by 31.1 | 24.0.5 | MockRestServiceServer unit tests; live proof is a checkpoint |
| Knowledge graph | — | ✗ (`graphify status`: no graph) | — | not needed |

**Missing dependencies with no fallback:** none for planning. Live KC/E2E proofs need the rebuilt compose stack.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Testcontainers (Java); Jest 30 (frontend); Playwright 1.63 + axe-core 4.13 (E2E); `go test` (edge); vitest 4 (mcp-server) |
| Config file | `core-java/build.gradle.kts` (`test` excludes tag `testcontainers` `:387-391`; `integrationTest` includes it `:417-423`; outputs in `core-java/build-local/`, NOT `build/`); `frontend/jest.config.js`; `frontend/playwright.config.ts`; `mcp-server/package.json` (`vitest run`) |
| Quick run command | `./gradlew :core-java:test --tests '<FQCN>'` · `./gradlew :core-java:integrationTest --tests '<FQCN>'` · `cd frontend && npx jest <path>` · `cd edge-go && go test ./...` · `cd mcp-server && npm test` |
| Full suite command | `./gradlew :core-java:test :core-java:integrationTest && (cd frontend && npx jest && npm run build && npx playwright test) && (cd mcp-server && npm test) && (cd edge-go && go test ./...)` (the 31.1 VALIDATION command plus Go) |
| Type check | `cd frontend && npm run build` (jest does not type-check; memory `feedback_frontend_typecheck_gate`) |
| Gates | `scripts/docs-freshness.sh` (+`--write`), `scripts/check-doc-metrics.sh`, `./gradlew :core-java:updateOpenApiSnapshot` then `scripts/check-openapi-snapshot-fresh.sh`, `k8s/scripts/render-golden.sh` + `check-render-invariants.sh` + `check-env-contract.sh`, `scripts/check-no-create-extension.sh`, `scripts/check-branch-behind-base.sh`, `scripts/check-runtime-freshness.sh` (after rebuild) |

### Phase Requirements → Test Map
(❌ = Wave 0, write RED first; tdd_mode is on. Class names are proposals following existing naming.)

| Req | Behavior | Type | Automated Command | Exists? |
|-----|----------|------|-------------------|---------|
| UXT-004 | ungranted vendor token → 403 on writes; no JIT row | IT | `integrationTest --tests '*StrictScopingDefaultIntegrationTest'`; existing `ShopAccessJitProvisionTest` | ❌ / ✅ (update) |
| UXT-003 | revoke last grant → staff/me groupAdmin=false, orders 403, with own token | IT | `--tests '*StaffManagementIntegrationTest'` (add arm) | ✅ update |
| D-06 config | no profile/manifest sets false | gate | `scripts/check-strict-scoping-default.sh` (red on broken copy) | ❌ |
| D-09 | Staff list rows carry computed effective access incl. No access | IT + jest | `--tests '*StaffEffectiveAccessIntegrationTest'`; `npx jest app/dashboard/__tests__/staff-page.test.tsx` | ❌ / ✅ update |
| D-07 | invite → accept → KC user with tenant_id → grant exact; expired/used/wrong tenant → one 404 | IT + unit + live | `--tests '*StaffInviteIntegrationTest'`, `*StaffInviteRlsIntegrationTest` (NOSUPERUSER); `test --tests '*KeycloakAdminClientTest'`; compose checkpoint | ❌ |
| UXT-018 | COMPLETED delete 409; void → reversal row; ledger nets 0; order GET 200 | IT | `--tests '*OrderVoidIntegrationTest'`, `*LedgerEntryKindMigrationIntegrationTest` | ❌ |
| UXT-027 | per-shop summary/export; manager 200 own shop, 403 other | IT + jest | `--tests '*FinanceShopScopeIntegrationTest'`, `*LedgerShopBackfillMigrationIntegrationTest` (2 tenants) | ❌ |
| UXT-026 | CSV import default shop; copy-menu | IT + jest | `--tests '*BulkImportShopDefaultIntegrationTest'` | ❌ |
| UXT-046/047 | VAT select in form; VAT line hidden without vatNumber | jest | `npx jest app/dashboard/products app/shop/[slug]/checkout` | ❌ |
| UXT-011/020 | PENDING on board; alert loop; notes + fulfilment on card | unit + jest + e2e | `test --tests '*OrderServiceKitchenStatusesTest'`; `npx jest app/dashboard/kitchen`; `npx playwright test e2e/kitchen-flow.spec.ts` | ✅ update |
| UXT-021 | lapse → board-stopped, never "Live"; return to kitchen | jest + e2e | `npx jest app/dashboard/kitchen lib/__tests__/api-client*`; `npx playwright test e2e/kitchen-session-lapse.spec.ts` | ❌ |
| UXT-022 | pause refuses storefront/checkout; hours migration preserves render; unparseable→closed; DST | IT + jest | `--tests '*ShopPauseIntegrationTest'`, `*OpeningScheduleMigrationIntegrationTest`; `npx jest lib/__tests__/opening-hours.test.ts` | ❌ / ✅ update |
| UXT-007 | vendor reprice between render and submit → 409 basket-changed with diff; allergen-only stays 31.1 409; same key resubmit succeeds | IT + jest + e2e | `--tests '*GuestOrderBasketRevalidationIntegrationTest'`; `IdempotencyFingerprintGoldenTest`; `npx jest app/shop/[slug]/checkout`; `npx playwright test e2e/basket-revalidation.spec.ts` | ❌ |
| UXT-008 | no promotion rendered/returned | IT + jest | `--tests '*PublicPromotionsHiddenTest'`; `npx jest app/shop` | ❌ |
| UXT-029 | COD → route; focus + title + announcement; refresh no re-POST | jest + e2e | `npx jest app/shop/[slug]/checkout`; `npx playwright test e2e/public-a11y.spec.ts` (+arm) | ✅ update |
| UXT-044 | public name "First L." | unit | `test --tests '*ReviewServiceTest'` | ✅ update |
| UXT-005 | cross-shop review → 400 | IT | `--tests '*ReviewCrossShopIntegrationTest'` | ❌ |
| UXT-006 | rotated XFF from untrusted peer → same bucket; trusted peer → rightmost untrusted | unit + IT | `test --tests '*ClientIpResolverTest'` | ❌ |
| UXT-039 | per-tenant 429 typed + headers after auth; other tenant unaffected | go | `cd edge-go && go test ./cmd/edge/ -run RateLimit` | ✅ update |
| UXT-041 | 4th open cash order same email → 429 order-cap-exceeded; unverified flag stored; bulk cancel | IT + jest | `--tests '*CashOrderCapIntegrationTest'`, `*OrderBulkCancelIntegrationTest` | ❌ |
| UXT-042 | qty over cap → 422 on storefront AND vendor/MCP paths; 20-line/40-unit basket passes | IT | `--tests '*OrderLimitsIntegrationTest'` | ❌ |
| UXT-016 | PUT without stock preserves; trackInventory=false clears; PATCH availability keeps allergens | IT + jest | `--tests '*ProductPartialUpdateIntegrationTest'`; `npx jest app/dashboard/products` | ❌ |
| UXT-017/037 | sync requires shopId; per-item results; unknown type REJECTED; createOrder refuses shop-less; edge passes problem+json and results | IT + go | `--tests '*SyncBatchShopRequiredIntegrationTest'`; `cd edge-go && go test ./...` (contract gate) | ❌ |
| UXT-036 | MCP create_order → PENDING, outbox event, appears on kitchen board | IT + vitest | `--tests '*OrderCreateSubmitIntegrationTest'`; `cd mcp-server && npx vitest run src/tools/create-order.test.ts` | ❌ / ✅ update |
| UXT-040 | list_products shopId/availableOnly; allergen names in DTO | vitest + IT | `npx vitest run src/tools/list-products.test.ts`; `--tests '*ProductDtoAllergenNamesTest'` | ✅ update / ❌ |
| UXT-038 | 2 events × failures do not pause; exhausted deliveries pause; queued deliveries HELD; owner emailed once | IT | `--tests '*WebhookAutoPauseIntegrationTest'` | ❌ |
| UXT-035 | create/rotate/delete credential; token tenant_id/aud/scopes; SA grant enforced; human-only | unit + IT + live | `test --tests '*KeycloakAdminClientTest'`; `--tests '*IntegrationCredentialIntegrationTest'`, `*IntegrationCredentialRlsIntegrationTest`; compose checkpoint | ❌ |
| UXT-080..085 | live regions, CDP names, focus, titles, banner overlap, large text | e2e + jest | `npx playwright test e2e/a11y-persona-gaps.spec.ts` (new), `e2e/public-a11y.spec.ts` | ❌ |
| UXT-023 | fee/threshold editable; collection-only refuses DELIVERY on all writers | IT + jest | `--tests '*FulfilmentOfferedIntegrationTest'` | ❌ |
| UXT-028 | edit without slug keeps slug | IT | `--tests '*ShopControllerIntegrationTest'` (add arm) | ✅ update |
| SC-7 goods | 40 goods re-verified | mixed | existing suites listed in §Goods map | ✅ |

### Sampling Rate
- **Per task commit:** the touched class (`--tests`) plus `npx jest <touched dir>`.
- **Per wave merge:** `./gradlew :core-java:test`, the wave's integration classes, `npx jest`, `npm run build`, `go test ./...` / `npm test` where touched.
- **Per sub-theme PR (D-03):** full suite, OpenAPI snapshot, docs-metrics gates, render goldens, a rebuild of all images, `check-runtime-freshness.sh`, then Playwright on the rebuilt compose stack.
- **Phase gate:** all 7 sub-theme PRs merged and the 40 goods re-verified.

### Wave 0 Gaps
- [ ] 37-B: the strict-default IT, the teardown capture/restore fix, the config gate script, invite/void/finance ITs and stepwise migration tests.
- [ ] 37-A: kitchen session-lapse spec, pause/hours ITs plus the hours migration stepwise test (2 tenants, NULL/closed/unparseable/overnight rows).
- [ ] 37-C: basket-revalidation IT plus e2e (the vendor-reprice race, modelled on `allergen-ack-race.spec.ts`).
- [ ] 37-D: `ClientIpResolverTest` (IPv4/IPv6/mapped, trusted/untrusted peer, rotation), cap ITs, limits IT across both writers.
- [ ] 37-E: sync, credential and webhook ITs; edge passthrough Go tests; MCP vitest arms.
- [ ] 37-F: one new a11y spec using CDP `Accessibility.getFullAXTree` plus a MutationObserver live-region recorder, with an INSTRUMENT arm proving it can fail (the `public-a11y.spec.ts:712` precedent).
- [ ] 37-G: fulfilment-offered IT.
- [ ] Metrics: `docs/metrics.json` regenerated after each PR (`scripts/docs-freshness.sh --write`) and prose counts in CLAUDE.md/AGENTS.md/README.md updated.

## Security Domain

`security_enforcement` is enabled, `security_asvs_level: 2`, `security_block_on: medium` `[VERIFIED: .planning/config.json]`.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V1 Architecture | yes | trust boundaries: edge ↔ core, core ↔ Keycloak admin, ingress ↔ core (trusted proxies) |
| V2 Authentication | yes | Keycloak for invitees and credentials; KC password policy; no new auth path (D-22) |
| V3 Session Management | yes | kitchen lapse handling; NextAuth refresh; no session lengthening (D-14) |
| V4 Access Control | yes | `ShopAccessService` deny-by-default (D-06); GROUP_ADMIN-only void/credentials; human-only staff/credential management; per-shop finance gate |
| V5 Validation | yes | Bean Validation plus domain `OrderLimits`; structured hours parser; CIDR parse; `callbackUrl` same-origin validation |
| V6 Cryptography | yes | SHA-256 token digests (invites); HMAC for an IP digest if chosen; no custom crypto |
| V7 Errors/Logging | yes | RFC 7807 types; never log secrets, invite tokens or passwords; audit who/when on void and credentials |
| V8 Data Protection | yes | review name minimisation (UXT-044); IP storage (D-18); `Cache-Control: no-store` on secret responses |
| V11 Business Logic | yes | caps, bulk reject, basket re-validation, review shop check |
| V13 API | yes | Idempotency-Key, typed 429 with Retry-After, OpenAPI fidelity, edge passthrough |

### Known Threat Patterns
| Pattern | STRIDE | Mitigation |
|---------|--------|------------|
| XFF spoofing to evade limits | Spoofing | trusted-proxy CIDRs, rightmost-untrusted, default none, render invariant against `/0` |
| User edits own `tenant_id` | Elevation | declared admin-only attribute; never `ENABLED` |
| Invite token theft/replay | Spoofing | digest at rest, single-use UPDATE…WHERE, short TTL, one 404 for every cause, rate limit |
| Credential mints credentials | Elevation | human-only management endpoints; role ceiling |
| Secret leakage | Info disclosure | show once, `no-store`, never persisted/logged |
| Open redirect via `callbackUrl` | Spoofing | relative same-origin only |
| Cross-tenant invite accept | Elevation | tenant pinned from link, RLS read by digest, KC email-in-other-tenant → 409 |
| Order flood | DoS | caps, per-IP, bulk reject, edge per-tenant bucket |
| Fake reviews across shops | Tampering | `order.shopId == shop.id` |
| Ledger tampering via delete | Repudiation | delete guard, void with reason and audit, reversing entry |

## Size Assessment

Reference point: 31.1 needed **30 plans** for 17 clusters (`ls 31.1-*-PLAN.md | wc -l` = 30).

| Sub-theme | Clusters | Estimated plans | Heaviest items |
|---|---|---|---|
| 37-B | 7 (+#452 gap) | 13-15 | flip + test repair (2), effective access + no-access page (2), invites incl. KC profile (3), void + ledger (2), per-shop finance + export (2), CSV/copy (1-2), VAT UI/display (1-2) |
| 37-A | 4 | 7-8 | kitchen lane/alert/card (2), board-stopped (1), pause (2), structured hours + migration (2-3) |
| 37-C | 4 | 5 | basket diff server (1) + UI (1), promotions (1), confirmation route (1), review name (1) |
| 37-D | 5 | 6-7 | client IP (1), edge limiter (1), caps + flag + bulk reject (2-3), limits (1), review check (folded) |
| 37-E | 7 | 9-11 | product PATCH (1), sync + edge passthrough (2), MCP PENDING + list filters (2), webhooks (1-2), credentials (3) |
| 37-F | 6 | 3 | grouped by surface |
| 37-G | 2 | 2 | fulfilment offered (1-2), slug (folded or 1) |
| **Total** | **35** | **≈45-51** | |

**Verdict:** plannable as one phase at fine granularity, because D-03 already decomposes it into seven independently mergeable plan groups with their own wave 0 and verification. It is about 1.6× 31.1. If the planner's per-phase budget is exceeded, split **along the existing sub-theme boundaries** (37 = B, A, D, i.e. most P0s and Phase-32 gating; a decimal phase = C, E, F, G). This needs an owner ruling, because D-03 defines phase completion as all seven PRs merged. Reserve the migration numbers before splitting either way.

## Goods-to-Preserve Map (40 entries → sub-theme)

| Sub-theme | Goods it touches (IDs from `goods-to-preserve.md`) |
|---|---|
| 37-A | P2-FUN-17, P2-FUN-18, P2-FUN-20, P2-TUN-13, P2-TUN-14, P2-CHA-15, P2-TUN-15, P2-REG-15, P2-CHA-14 (shop-closed wording), P2-CHA-18 (UK time), P2-KEM-22 (one-shop-at-a-time kitchen) |
| 37-B | P2-KEM-20, P2-KEM-21, P2-KEM-22 (last-admin guard, ledger VAT), F1, F4, P2-TUN-15 (money endpoints refuse non-admin), P2-RAV-19 |
| 37-C | P2-CHA-12, P2-CHA-13, P2-RAV-20, P2-KYL-P2, P2-KYL-P3, P2-FUN-19, P2-CHA-14, P2-CHA-16, P2-REG-15, P2-REG-16, P2-MAR-P2, P2-REG-17, P2-GRA-21, P2-GRA-23, F3, P2-KYL-P5, P2-GRA-22, P2-REG-18, P2-KYL-P4 |
| 37-D | P2-KYL-P2, P2-KYL-P3, P2-CHA-16 (caps must allow 20 lines / 40 units), F3, P2-KYL-P5, P2-REG-18, P2-KYL-P4, P2-RAV-20, P2-CHA-12, P2-CHA-13 |
| 37-E | P2-RAV-19, P2-RAV-20, P2-RAV-21, P2-RAV-22, F2, F4, P2-KYL-P3, P2-REG-15, P2-KYL-P1 |
| 37-F | P2-MAR-P1, P2-MAR-P2, P2-MAR-P3, P2-GRA-24, P2-GRA-22 |
| 37-G | P2-REG-17, P2-CHA-14 (minimum wording), P2-KYL-P2, P2-RAV-19 |

All 40 IDs appear at least once: P2-CHA-12/13/14/15/16/18, P2-RAV-19/20/21/22, P2-KYL-P1..P5, P2-FUN-17/18/19/20, P2-REG-15/16/17/18, P2-MAR-P1/P2/P3, P2-TUN-13/14/15, P2-KEM-20/21/22, P2-GRA-21/22/23/24, F1-F4.

## Sources

### Primary (HIGH confidence: read on this tree this session)
- `.planning/phases/37-real-world-operations-readiness/37-CONTEXT.md`, `37-DISCUSSION-LOG.md`, `.planning/ROADMAP.md` §Phase 37, `.planning/STATE.md`
- `.planning/ux-persona-test-20261003-pass2/consolidated/{catalogue.json, CATALOGUE.md, filed-issues.json, goods-to-preserve.md}`
- core-java: `ShopAccessService`, `StaffManagementService`, `StaffController`, `ShopRole`, `GrantSource`, `KeycloakAdminClient`, `KeycloakAdminProperties`, `OrderService`, `OrderStatus`, `OrderStateMachineConfig`, `FulfilmentPolicy`, `FulfilmentType`, `OrderChannel`, `FinancialTransaction*`, `VatRate`, `PublicStorefrontService`, `GuestOrderRequest`/`GuestOrderItemRequest`, `AllergenAcknowledgementStaleException`, `GlobalExceptionHandler`, `ClientIpResolver`, `RateLimitInterceptor`, `DsarIntakeRateLimiter`, `ReviewService`, `ProductMapper`, `CreateProductRequest`, `SyncService`/`SyncItem`, `WebhookDeliveryWorker`/`WebhookProperties`/statuses, `Shop`, `ShopService`, `application.yml`, `application-test.yml`, migrations V2/V36/V40/V43/V55/V56, `build.gradle.kts`, `IdempotencyFingerprintGoldenTest`, `TenantJwts`
- frontend: kitchen page, `kitchen-orders-api.ts`, `kitchen-ticket.tsx`, `api-client.ts`, `auth.ts`, sign-in page, `opening-hours.ts`, checkout page, order page, `shop-detail-client.tsx`, `public-a11y.spec.ts`, `vendor-credentials.ts`, staff page
- edge-go: `cmd/edge/main.go`, `handlers.go`, `types.go`, `internal/core/client.go`, `contract.go`, `go.mod`
- mcp-server: `create-order.ts`, `list-products.ts`
- infra: `infra/keycloak/realm-export{,.template}.json`, theme; `docker-compose.full-stack.yml`; `.env.example`; `k8s/base/configmap.yaml`, `core-java-deployment.yaml`, goldens; `docs/security-scopes.md`

### Secondary (MEDIUM confidence: official docs, summarised fetch)
- [Keycloak 24.0.5 Admin REST API](https://www.keycloak.org/docs-api/24.0.5/rest-api/index.html): clients, service-account-user, client-secret GET/POST, default-client-scopes, client-scopes, delete
- [Red Hat build of Keycloak 24 Server Administration Guide, Managing users](https://docs.redhat.com/en/documentation/red_hat_build_of_keycloak/24.0/html/server_administration_guide/assembly-managing-users_server_administration_guide): unmanaged attribute policies; undeclared attributes ignored
- [MDN X-Forwarded-For](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/X-Forwarded-For): trusted-proxy-list, rightmost selection
- [MDN Autoplay guide](https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Autoplay): Web Audio autoplay rules, `getAutoplayPolicy`

### Tertiary (LOW confidence: web search, needs live proof)
- [keycloak-config-cli: user-profile unmanaged attribute policy](https://adorsys.github.io/keycloak-config-cli/config/user-profile-unmanaged-attribute/) and [terraform-provider-keycloak PR #976](https://github.com/keycloak/terraform-provider-keycloak/pull/976): policy values DISABLED/ENABLED/ADMIN_VIEW/ADMIN_EDIT
- [epam/edp-keycloak-operator #418](https://github.com/epam/edp-keycloak-operator/issues/418): KC24+ silently drops undeclared attributes

## Metadata

**Confidence breakdown:**
- Current behaviour / file:line: HIGH (read this session; corrected drift table)
- Fix shapes: MEDIUM (consistent with in-tree patterns; owner questions listed)
- Keycloak admin specifics: MEDIUM/LOW (paths cited; mapper config keys and profile JSON assumed; live proof required)
- Pitfalls: HIGH (each grounded in a read file or a recorded project trap)

**Research date:** 2026-10-07
**Valid until:** 2026-11-06 for tree facts (re-verify after any further `main` merge); Keycloak facts are tied to 24.0.5.
