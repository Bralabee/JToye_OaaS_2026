---
schema_version: 1
open_count: 20
waived_count: 0
fixed_count: 31
total_count: 51
last_updated: 2026-10-19T13:54:56.914Z
---

# Broken Windows Ledger

> Cross-phase defect register. With `workflow.windows_enforce` enabled, `/gsd-ship` blocks while `open_count > 0`.
> Waive with `gsd-tools windows waive <id> "<reason>"` (reason required).
> Mark fixed with `gsd-tools windows fixed <id>`.

| id | phase | kind | file | line | description | status | reason | recorded_at | resolved_at |
|----|-------|------|------|------|-------------|--------|--------|-------------|-------------|
| 1 | 36 | unrun-verify | .planning/phases/36-azure-blob-storage-throughout/36-CONTEXT.md |  | 36-05: az storage account check-name gave no verdict for the four D-11 names (Microsoft.Storage NotRegistered -> SubscriptionNotFound); DNS NXDOMAIN only; register the RP and re-run (runbook 2.1) | open |  | 2026-09-28T22:52:29.731Z |  |
| 2 | 36 | unrun-verify | .github/workflows/e2e-nightly.yml |  | 36-08: the nightly drill step (build pg-backup :15-blob, ephemeral DB_BACKUP_PASSWORD, check-backup-restore-drill.sh) was validated statically only (actionlint 0, bash -n on all 18 run blocks, append logic simulated); it has never run on a GitHub runner. 36-18 runs the nightly. | fixed |  | 2026-09-29T01:01:30.322Z | 2026-09-29T11:34:43.738Z |
| 3 | 36 | unrun-verify | k8s/staging/workload-identity-patch.yaml |  | 36-09: Workload Identity is proven at render level (INV-8/9/10) and against the real validators (core-java validateShape, blobctl config) only; that the AKS webhook mutates the labelled pods, projects its token with automountServiceAccountToken false (A6) and the Entra exchange succeeds needs a WI-enabled cluster and the Phase 29 client-id annotations (jtoye-staging-aks has WI off and is stopped) | open |  | 2026-09-29T06:23:37.497Z |  |
| 4 | 31.1 | unrun-verify | scripts/openapi-gate.sh |  | 31.1-14: oasdiff-based OpenAPI compat gate not run locally (oasdiff not installed); OpenApiSnapshotTest byte-equality ran green after regeneration | fixed |  | 2026-10-06T17:35:41.148Z | 2026-10-07T15:36:39.818Z |
| 5 | 31.1 | unrun-verify | frontend/e2e/allergen-ack-race.spec.ts |  | 31.1-15 #785 race spec listed only; RED (pre-rebuild runtime) and GREEN runs owed to 31.1-30 | fixed |  | 2026-10-06T18:25:22.970Z | 2026-10-07T11:12:28.951Z |
| 6 | 31.1 | unrun-verify | core-java/src/main/java/uk/jtoye/core/gdpr/DsarOutcomeMailer.java |  | 31.1-16 ACCESS link email proven only against a mocked JavaMailSender; live Mailhog capture, live customer-realm lookup and a click-through to /data-request/download (page built by 31.1-17) owed to 31.1-30 | fixed |  | 2026-10-06T19:52:27.849Z | 2026-10-07T11:12:29.131Z |
| 7 | 31.1 | stub | frontend/app/data-request/download/download-client.tsx |  | Unavailable state links 'Request a new copy' to /shop/account, which 31.1-26 builds; until then the link 404s | fixed |  | 2026-10-06T22:47:38.670Z | 2026-10-07T11:12:29.314Z |
| 8 | 31.1 | unrun-verify | frontend/app/data-request/download/page.tsx |  | Live click-through of the emailed Article 15 link (Mailhog -> /data-request/download -> Show my data once, second open unavailable) not run: shared stack not rebuilt; owed to 31.1-30 | fixed |  | 2026-10-06T22:47:38.806Z | 2026-10-07T11:12:29.500Z |
| 9 | 31.1 | unrun-verify | frontend/e2e/dsar-verify-link.spec.ts |  | 31.1-20 #839 compose-stack spec (Mailhog link -> /data-request/confirm, press, already confirmed) listed only; live RED/GREEN owed to 31.1-30 after the stack rebuild | fixed |  | 2026-10-07T00:04:51.201Z | 2026-10-07T11:12:29.675Z |
| 10 | 31.1 | unrun-verify | frontend/e2e/storefront-dish-modal-a11y.spec.ts |  | 31.1-21: 'Dish modal allergen section (31.1-21)' (200% zoom placement + three statements on fixture SKU E2E-31121-DISH-ALLERGENS) listed only; live RED/GREEN owed to 31.1-30 on the rebuilt stack | fixed |  | 2026-10-07T00:21:26.304Z | 2026-10-07T11:12:29.863Z |
| 11 | 31.1 | unrun-verify | frontend/e2e/kitchen-allergy-note.spec.ts |  | 31.1-22 kitchen allergy note browser spec (4 tests: board note + 44px button + ack round trip, order detail UK dates + channel line) written, type-checked and listed; not run, the live stack rebuild and click-through are owed to 31.1-30 | fixed |  | 2026-10-07T00:42:00.356Z | 2026-10-07T11:12:30.045Z |
| 12 | 31.1 | unrun-verify | frontend/e2e/seller-identity.spec.ts |  | 31.1-24 #789 seller-identity spec listed only (3 tests); live RED (pre-rebuild runtime) and GREEN runs owed to 31.1-30; its Mailhog half stays red until 31.1-25 ships the email seller block | fixed |  | 2026-10-07T02:03:55.160Z | 2026-10-07T11:12:30.220Z |
| 13 | 31.1 | unrun-verify | scripts/openapi-gate.sh |  | 31.1-24: oasdiff compat gate not run locally (oasdiff not installed); OpenApiSnapshotTest red on the old snapshot, green on the regenerated one (additive: 49 insertions, 0 deletions) | fixed |  | 2026-10-07T02:03:55.293Z | 2026-10-07T15:36:39.930Z |
| 14 | 31.1 | unrun-verify | core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java |  | 31.1-25 #789/#785 order emails proven by MimeMessage content tests (unit + Testcontainers, mocked sender) and by OrderNotificationListenerIntegrationTest landing one Confirmed email in the local Mailhog from its own test context; the compose-stack Mailhog capture (rebuilt core-java, seller-identity.spec.ts email half) is owed to 31.1-30 | fixed |  | 2026-10-07T03:00:22.755Z | 2026-10-07T11:12:30.404Z |
| 15 | 31.1 | unrun-verify | frontend/e2e/customer-account.spec.ts |  | 31.1-26 #838 customer-account spec listed only (3 tests: served-HTML wall on mobile+desktop, @desktop-only signed-in click-through lodging ACCESS+ERASURE with one Mailhog email each); live RED (pre-rebuild runtime has no /shop/account) and GREEN owed to 31.1-30 | fixed |  | 2026-10-07T03:21:29.218Z | 2026-10-07T11:12:30.592Z |
| 16 | 37 | unrun-verify | frontend/e2e/dashboard-interface-corrections.spec.ts | 185 | 37-06 edited two staff-page e2e assertions (no invite-denial copy; People heading in the CLS test); not run, the shared runtime is not rebuilt until the 37-15 gate | open |  | 2026-10-08T12:56:26.518Z |  |
| 17 | 37 | unrun-verify | frontend/components/dashboard/no-access-page.tsx |  | 37-06 B3 backstop not run: Playwright at 375px asserting no horizontal overflow on the no-access page body with a 60-character business name; the body wraps with overflow-wrap:anywhere (jsdom cannot measure) | open |  | 2026-10-08T12:56:28.291Z |  |
| 18 | 37 | deviation | frontend/components/dashboard/no-access-page.tsx |  | 37-06 B3 body says 'your business' where UI-SPEC copy has {business}: a person with no access cannot read any tenant record, and the session carries no business name | open |  | 2026-10-08T12:56:29.946Z |  |
| 19 | 37 | unrun-verify | core-java/src/main/java/uk/jtoye/core/security/access/StaffInviteService.java |  | 37-07 invitation email not yet sent through the running compose stack (Mailhog): V77 and /api/v1/staff/invites reach the shared runtime only at the 37-15 rebuild; proven by integration tests with a re-parsed MimeMessage | fixed |  | 2026-10-08T13:58:26.371Z | 2026-10-09T09:35:11.042Z |
| 20 | 37 | unrun-verify | infra/keycloak/realm-export.template.json |  | 37-08 vendor-realm user profile (tenant_id admin-only) not yet on the shared compose Keycloak: needs kc.sh import --override true at the 37-15 rebuild, then the live chain (profile GET, accepted invitee keeps tenant_id, token claim, account-console edit refused). Proven on a throwaway Keycloak 24.0.5 only | fixed |  | 2026-10-08T14:40:02.180Z | 2026-10-09T09:35:11.153Z |
| 21 | 37 | deviation | core-java/src/main/java/uk/jtoye/core/security/access/StaffInviteService.java |  | 37-08 moved the invite token from the URL path to the fragment ({base}#token={tenantId}.{token}) and the public API to POST preview/accept with the ref in the body; 37-09's /invite/[token] route, GET /{ref} and POST /{ref}/accept wording are superseded: the page must read location.hash and POST the ref | fixed |  | 2026-10-08T14:40:06.592Z | 2026-10-08T16:22:20.362Z |
| 22 | 37 | unrun-verify | frontend/e2e/staff-invite.spec.ts |  | 37-09 live invite journey listed only (1 test, @desktop-only): Staff-form invite, Mailhog link, origin allow-list and no /invite Referer, 375px overflow backstop, account + Keycloak login_hint sign-in, joined toast, 'Shop manager · {shop}' with an instrument arm, second visit unusable; live RED (pre-rebuild runtime has no invite card) and GREEN owed to 37-15 | fixed |  | 2026-10-08T16:22:20.043Z | 2026-10-09T09:35:11.260Z |
| 23 | 37 | unrun-verify | frontend/e2e/dashboard-interface-corrections.spec.ts | 174 | 37-09 inverted test 2: it asserted zero invite buttons on the Staff page and now asserts exactly one 'Send invitation' in the 'Invite someone' region; not run, the shared runtime is rebuilt at 37-15 | fixed |  | 2026-10-08T16:22:20.149Z | 2026-10-09T09:35:11.364Z |
| 24 | 37 | deviation | core-java/src/main/java/uk/jtoye/core/security/access/dto/MyAccessDto.java |  | 37-09 added a nullable businessName to GET /api/v1/staff/me (additive, OpenAPI +7/-0): no endpoint a staff member can read named the business, and the D-26 joined toast must be built from server data; reaches the shared runtime only at the 37-15 rebuild (until then the toast says 'your new business') | fixed |  | 2026-10-08T16:22:20.257Z | 2026-10-09T09:35:11.472Z |
| 25 | 37 | unrun-verify | frontend/app/dashboard/finance/page.tsx |  | 37-12 per-shop finance (API-echoed scope banner, Choose-a-shop state, Export CSV under the server filename, reversal rows) and the void dialog/voided badges are proven in jest only; no live browser run, because the shared runtime predates 37-10/37-11 and is rebuilt at 37-15 | fixed |  | 2026-10-09T00:29:01.557Z | 2026-10-09T09:35:11.582Z |
| 26 | 37 | deviation | frontend/app/dashboard/finance/page.tsx |  | 37-12 finance marks a SALE 'Voided' only when its REVERSAL is on the same transactions page (the ledger DTO carries no voided flag on the sale); a sale whose reversal sits on another page of 20 shows no Voided badge | open |  | 2026-10-09T00:29:01.668Z |  |
| 27 | 37 | deviation | frontend/app/dashboard/products/import/page.tsx |  | 37-13 made a CSV row with no shop_id and no shopId the per-row error 'shop required' (for a Group admin too); the dashboard import page still posts the file without shopId, so every dashboard import reports 'shop required' and creates nothing until 37-14 sends the switcher shop (same 37-B PR) | fixed |  | 2026-10-09T01:23:06.434Z | 2026-10-09T01:42:02.683Z |
| 28 | 37 | unrun-verify | frontend/app/shop/[slug]/checkout/page.tsx |  | 37-14 VAT display rule (no VAT row for a seller without a VAT number, the B7 note), the product-form VAT rate Select, the import target and the copy-to-shop dialog are proven in jest only; no live browser run, because the shared runtime predates 37-13 and is rebuilt at 37-15 | fixed |  | 2026-10-09T01:42:09.320Z | 2026-10-09T09:35:11.689Z |
| 29 | 37 | unmet-truth | core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java |  | 37-15 live: D-06 'ungranted means no access' does not hold for /api/v1/customers. A tenant user with no shop_staff grant (fresh, or revoked) read all 8 tenant A customers (names, emails, phones) with GET 200 and created one with POST 201; Phase 23 left CustomerController tenant-scoped, not shop-scoped, by design, which strict OFF hid. Needs an owner ruling on the customer access rule before the 37-B PR merges RULED 2026-10-09 (owner, option B): every /api/v1/customers operation requires an honoured grant under D-06 (ShopAccessService.requireAnyAccess, the staff/me predicate), checked before the Idempotency-Key replay; proven in Testcontainers by CustomerAccessGateIntegrationTest; option C (per-shop narrowing) and option E (a SHOP_MANAGER floor on customer writes and staff DELETE) are separate future rulings. | fixed |  | 2026-10-09T09:35:11.795Z | 2026-10-09T11:24:15.166Z |
| 30 | 37 | unrun-verify | core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java |  | WINDOWS #29 customer gate is proven in Testcontainers only; the compose runtime predates it, so check-runtime-freshness reports core-java and check-openapi-snapshot-fresh reports the five new 403s until the next rebuild; live proof owed at the 37-B ship: ungranted GET /api/v1/customers 403 shop-access-denied, STAFF 200, an ungranted dashboard login lands on the no-access page | fixed | closed by 37-22 at cb8cf9d1 (core-java rebuilt, first since the customer gate): ungranted GET /api/v1/customers 403 shop-access-denied (admin control 200); STAFF grant -> 200; revoke 204 -> same token 403 (evidence/37-22-live-probes.md) | 2026-10-09T11:24:20.204Z | 2026-10-10T15:09:55.578Z |
| 31 | 37 | stub | frontend/app/dashboard/kitchen/page.tsx | 612 | 37-17 passes useKitchenAlert stopped:false and deriveFeedState no signedOut/noAccess: the K2 board-stopped state, the session-lapse and the access-ended inputs are 37-18's to wire (the hook and the pill already honour them, tested). Close when 37-18 wires all three. | fixed | closed by 37-18 at a67cf8f0 (stopped + signedOut wired at b98a6608, noAccess at a67cf8f0) | 2026-10-09T13:23:23.843Z | 2026-10-10T13:56:51.218Z |
| 32 | 37 | deviation | core-java/src/main/java/uk/jtoye/core/storefront/ShopAvailabilityPolicy.java |  | D-25 owner ruling 2026-10-10 (accepted consumer-safety risk): a NEEDS_REVIEW shop's day whose free text V80 could not read keeps taking storefront orders all day (the pre-D-15 fail-open) until the vendor saves structured hours; read days and days written Closed are enforced as before deploy. Close when no shop is NEEDS_REVIEW, or by a new owner ruling. | open |  | 2026-10-10T13:14:21.463Z |  |
| 33 | 37 | unrun-verify | core-java/src/main/java/uk/jtoye/core/shop/ShopController.java |  | 37-20 pause endpoints, the 409 shop-closed refusal and the new ShopDto/PublicShopDto fields are proven in Testcontainers only; the compose core-java predates them (and V80, WINDOWS #30). Owed at the next core-java rebuild: PUT /shops/{id}/pause then a storefront order 409 PAUSED, DELETE then 201, GET /public/shops/{slug} openNow, the V80 NOTICE counts, check-openapi-snapshot-fresh green. | fixed | closed by 37-22 at cb8cf9d1: V80 applied on the dev DB (LEGACY_ALWAYS_OPEN=5, MIGRATED_FROM_TEXT=0, NEEDS_REVIEW=0); ungranted pause 403; STAFF pause 200 -> public openNow false -> guest order 409 shop-closed PAUSED, nothing written; resume 200 -> same key 201; check-openapi-snapshot-fresh PASS (121 paths), check-runtime-freshness PASS 4/4 (evidence/37-22-live-probes.md) | 2026-10-10T13:14:21.570Z | 2026-10-10T15:09:55.693Z |
| 34 | 37 | unrun-verify | frontend/components/dashboard/kitchen/kds-board-stopped.tsx |  | 37-18 proven in jest only, not live: the alarm (3x 440 Hz square every 3 s through the shared AudioContext; audibility and distinctness from the chime are a by-ear 37-23 UAT row), Silence alarm, the silent token refresh over real 5-minute Keycloak tokens (fake timers only; the jwt skew is a unit rule), a REAL grant revoke ending the board (live only via a route-stubbed typed 403) and the STOMP socket closing on it, and the deliberate-sign-out race. Live 10/10 on frontend 809cf75c covers the lapse overlay, URL, pill, title, Sign in again, deliberate Sign out and the K2 backstop; 37-23 re-runs e2e/kitchen-session-lapse.spec.ts | open |  | 2026-10-10T13:57:09.943Z |  |
| 35 | 37 | unrun-verify | frontend/app/shop/[slug]/checkout/page.tsx |  | 37-22 proven in jest only, not live: the checkout RACE UI (a shop pausing while its checkout page is open: the race copy in role=alert and the stored basket unchanged by content) - live covers the API 409 shop-closed PAUSED and the at-load disable; and the NEEDS_REVIEW JSON-LD rule (only declared windows, an unreadable day never published) - no NEEDS_REVIEW shop exists on the dev DB. 37-23 UAT: pause from the kitchen while a customer is on checkout. | open |  | 2026-10-10T15:10:21.406Z |  |
| 36 | 37 | deviation | frontend/components/dashboard/shops/opening-schedule-editor.tsx |  | 37-21 orchestrator copy decision under the D-25 owner ruling (2026-10-10), pending the owner's own wording: the editor keeps pre-filling an unreadable NEEDS_REVIEW day as Closed (a save makes it closed, D-15) but its amber line reads 'We couldn't read "{original text}". Customers can still order on this day at any time until you save your hours.' instead of the UI-SPEC's 'This day shows as closed.' (untrue under the ruling); the dashboard needs-review card reads '...for {days}. Customers can order on those days at any time until you save your hours.'; UI-SPEC rows 458/791/794 amended to match. Close when the owner approves or replaces the wording. | fixed | closed by 261010-ppb at 2752ff50: owner ruling 2026-10-10 - an unreadable day pre-fills as one all-day window with the ruled editor line and card sentence; UI-SPEC 458/791/794 amended | 2026-10-10T15:54:22.775Z | 2026-10-10T18:36:24.564Z |
| 37 | 37 | unrun-verify | frontend/components/dashboard/kitchen/pause-orders-control.tsx |  | 37-21 and 261010-ppb proven in jest only, not live: a pause refused with shop-access-denied now shows the K2 Access ended overlay and no inline copy (owner ruling 2026-10-10; live it is the same STAFF gate as the board read, not provoked live); the in-flight disabled state, the stated-end hide (fake timers), the editor overlap / missing-time / next-day-spill validation with role=alert, aria-invalid and focus, and the dashboard-home pending / failed-load states. A NEEDS_REVIEW and a LEGACY shop were shown live only through a browser-side rewrite of the shops LIST response (no such shop exists on the dev DB since 37-22 seeder). The slug fix fail direction (a save renaming /shop/{slug}) was deliberately not run live. 37-23 UAT: tap twice fast, provoke a validation error by hand, and read the pause 403 as a revoked STAFF user: expect the K2 Access ended overlay (Your access has ended) and no inline refusal copy. | open |  | 2026-10-10T15:54:22.887Z |  |
| 38 | 37 | unrun-verify | frontend/components/dashboard/kitchen/kds-sound.tsx |  | 261010-ppb alarm cap (owner ruling 2026-10-10) proven in jest only: an unsilenced board-stopped alarm stops by itself after 120 s while the red overlay stays; 37-23 UAT by ear: a stopped board rings, then falls silent at about 2 minutes, red screen still up | fixed |  | 2026-10-10T18:36:59.063Z | 2026-10-11T18:37:35.663Z |
| 39 | 37 | todo | core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java |  | DEFERRED to Phase 39 (owner ruling 2026-10-10): option C, per-shop customer narrowing. Shape: the next free Flyway version at Phase 39 plan time (the ruling named V81, which 37-27 reserves) adds nullable customers.origin_shop_id REFERENCES shops(id), plus the customers_aud mirror in the same migration (Customer is @Audited); a tenant-looped, GUC-pinned backfill from orders (idx_orders_customer exists); the non-Group-admin predicate origin_shop_id IN granted OR EXISTS an order of that customer in a granted shop, on list, by-id, PUT, DELETE and the OrderService.java:170 and :348 lookups (the tenant-wide findById bypass); a refused id answers 404, not 403; a NOSUPERUSER Testcontainers proof. Phase 39 is Platform runtime upgrades: /gsd-plan-phase 39 decides 39-F or a phase of its own. | open |  | 2026-10-10T18:48:49.187Z |  |
| 40 | 37 | todo | core-java/src/main/java/uk/jtoye/core/shop/ShopService.java |  | DEFERRED to Phase 39 (owner ruling 2026-10-10): old-slug 301 aliases (a shop_slug_alias table, RLS-exempt by addition like V62/V75) so printed links and QR codes survive a deliberate slug change; the slug-keep fix itself is 37-48 | open |  | 2026-10-10T18:48:49.311Z |  |
| 41 | 37 | todo | infra/keycloak/realm-export.template.json |  | staging and production vendor realms are not in this repo: an operator must set ssoSessionMaxLifespan 50400 (D-14 amended 2026-10-10); refresh-token rotation stays off there as here | open |  | 2026-10-10T18:48:49.440Z |  |
| 42 | 37 | todo | infra/keycloak/realm-export.template.json | 9 | DEFERRED (orchestrator revision 2026-10-10, recorded for the owner): refresh-token rotation stays off (revokeRefreshToken false). A per-process single-flight cannot protect the 3 k8s frontend replicas (nor middleware.ts, which runs auth in another runtime). Turning it on needs a shared guard (a Redis-backed single-flight keyed by refresh token, or sticky sessions proven) or a Keycloak refreshTokenMaxReuse policy, then its own ruling | open |  | 2026-10-10T18:48:49.557Z |  |
| 43 | 37 | unmet-truth | frontend/app/shop/[slug]/shop-detail-client.tsx |  | 37-36 truth 3 / UI-SPEC C4: the review-form notice was not shipped because the frontend has no review form (reviews are API-only); see 37 deferred-items | open |  | 2026-10-12T16:10:54.367Z |  |
| 44 | 37 | unrun-verify | core-java/src/main/resources/db/migration/V83__shop_fulfilment_offered.sql |  | 37-48 V83 (offers_delivery/offers_collection), the four shop DTO fields, 422 fulfilment-not-offered on both writers, the slug-keep rule and 409 shop-slug-taken are proven in Testcontainers and jest only; the compose core-java predates them. Owed at 37-50 after rebuilding ALL images: V83 applied out of order, a vendor sets fee 299 and threshold 3000 and the storefront charges them, a collection-only shop refuses DELIVERY on the storefront and the vendor API, a shop edit keeps /shop/{slug}, check-openapi-snapshot-fresh green | fixed | closed by 37-50 on the rebuilt runtime (core 0beed8d18f5c): V83 applied out of order; a vendor PUT set fee 299 and threshold 3000 and the storefront charged 299 at 1600 and 2000 and 0 at 3000; Brixton collection-only refused DELIVERY 422 fulfilment-not-offered on the storefront and on POST /orders with 0 rows; a name edit kept /shop/brixton-village-grill (200, canonical unchanged); check-openapi-snapshot-fresh PASS; RED recorded on the old runtime (evidence/37-50-gate-37G.md Part 2) | 2026-10-14T11:33:57.563Z | 2026-10-14T16:35:40.317Z |
| 45 | 37 | unrun-verify | frontend/app/shop/[slug]/shop-detail-client.tsx |  | 37-49: G1/G2 visual and live proof owed to 37-50 - the Collection only chip and the wrapped status row at a real 375px render, the web-address row and create preview at 375/768/1280, and a collection-only shop set in the live dialog then ordered at checkout on rebuilt images | fixed | closed by 37-50: the Collection only chip wraps under the open state and rating at 375/390/768/1280 with page scrollWidth equal to the viewport; the web-address row and the create preview at 390/768/1280 and a long slug at 375 stay inside the dialog; Brixton set collection-only in the live dialog while a Delivery checkout was open, which met the 422 copy, switched to the static row and ordered COLLECTION (evidence/37-50-gate-37G.md 2.5-2.7, evidence/37-50-screens/) | 2026-10-14T11:50:46.206Z | 2026-10-14T16:35:40.427Z |
| 46 | 29 | unrun-verify | scripts/staging-db-bootstrap.sh |  | 29-07: role bootstrap proven only against a postgres:18 stand-in (fake_admin, a CREATEROLE CREATEDB BYPASSRLS non-superuser, not azure_pg_admin): A18 (admin may ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app after self-membership, pre-create uuid-ossp, own/ALTER schema public), BYPASSRLS for jtoye_backup, and --stage monitor (needs ADMIN OPTION on pg_monitor, failed without it on the stand-in) owed to the live readbacks of 29-14 and 29-23 | open |  | 2026-10-18T23:32:14.216Z |  |
| 47 | 29 | unrun-verify | scripts/azure-staging-datastores.sh |  | 29-07: dry-run only; the live proofs are owed to 29-09 Task 2 / 29-14: the server holds the Key Vault pg-admin--password value (CLI @FILE expansion, cited from azure-cli 2.91.0 source), exactly one firewall rule, azure.extensions UUID-OSSP, and sslmode=verify-full sslrootcert=system with the mounted host CA bundle on the first --stage pre login | open |  | 2026-10-18T23:32:14.332Z |  |
| 48 | 29 | deviation | k8s/staging/kustomization.yaml |  | 29-08: the plan said delete the staging replicas: block; kept at count 1 (= the HPA floor) instead, because deleting it renders base spec.replicas 3/5/3 and every kubectl apply would request 11 app pods on 3 x B2s until HPA scale-down stabilisation. Owner may prefer removing spec.replicas from base Deployments (affects production) - a separate decision | open |  | 2026-10-19T00:01:27.604Z |  |
| 49 | 29 | deviation | k8s/components/keycloak/keycloak.yaml |  | 29-10: added KC_CACHE=local (single replica; jdbc-ping would seek peers on 7800, which keycloak-allow does not admit) and JAVA_OPTS_KC_HEAP MaxRAMPercentage=60 (the image default 70% of the 1Gi limit plus ~300 MB non-heap sits at the limit). Heap headroom is ASSUMED; plan 29-14 measures working set against the limit | open |  | 2026-10-19T00:30:16.913Z |  |
| 50 | 29 | unmet-truth | core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java | 1776 | 29-14: storefront never shows a pipeline-uploaded image (toPublicProductDto reads flat image_url; asset-first resolution is vendor-only); the 'storefront renders it with naturalWidth > 0' truth is unmet at a784e7e2 | fixed | closed by 29-29 at 6e95ee6c (deployed to staging): the anonymous menu's Pelau imageUrl and imageUrls[0] are its ACTIVE pipeline derivative (asset 14add455, which the vendor uploaded in 29-22 in place of 29-14's 1475a89c, now released); the storefront image renders with naturalWidth 640 on mobile and desktop and the Product JSON-LD image equals its src (evidence/29-29-storefront-image.txt) | 2026-10-19T10:40:14.320Z | 2026-10-19T13:54:56.914Z |
| 51 | 29 | deviation | k8s/base/core-java-deployment.yaml |  | 29-14: core-java/keycloak 1Gi surge pods fit no B2s node (661/399/803Mi free by requests); first deploy unstuck by scaling the crash-looping revision-1 ReplicaSets to 0; every later roll of either will stick until capacity is decided | open |  | 2026-10-19T10:40:14.440Z |  |

````json
[
  {
    "id": 1,
    "kind": "unrun-verify",
    "phase": "36",
    "file": ".planning/phases/36-azure-blob-storage-throughout/36-CONTEXT.md",
    "line": null,
    "description": "36-05: az storage account check-name gave no verdict for the four D-11 names (Microsoft.Storage NotRegistered -> SubscriptionNotFound); DNS NXDOMAIN only; register the RP and re-run (runbook 2.1)",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-09-28T22:52:29.731Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 2,
    "kind": "unrun-verify",
    "phase": "36",
    "file": ".github/workflows/e2e-nightly.yml",
    "line": null,
    "description": "36-08: the nightly drill step (build pg-backup :15-blob, ephemeral DB_BACKUP_PASSWORD, check-backup-restore-drill.sh) was validated statically only (actionlint 0, bash -n on all 18 run blocks, append logic simulated); it has never run on a GitHub runner. 36-18 runs the nightly.",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-09-29T01:01:30.322Z",
    "resolved_at": "2026-09-29T11:34:43.738Z",
    "milestone": "v2.3"
  },
  {
    "id": 3,
    "kind": "unrun-verify",
    "phase": "36",
    "file": "k8s/staging/workload-identity-patch.yaml",
    "line": null,
    "description": "36-09: Workload Identity is proven at render level (INV-8/9/10) and against the real validators (core-java validateShape, blobctl config) only; that the AKS webhook mutates the labelled pods, projects its token with automountServiceAccountToken false (A6) and the Entra exchange succeeds needs a WI-enabled cluster and the Phase 29 client-id annotations (jtoye-staging-aks has WI off and is stopped)",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-09-29T06:23:37.497Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 4,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "scripts/openapi-gate.sh",
    "line": null,
    "description": "31.1-14: oasdiff-based OpenAPI compat gate not run locally (oasdiff not installed); OpenApiSnapshotTest byte-equality ran green after regeneration",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-06T17:35:41.148Z",
    "resolved_at": "2026-10-07T15:36:39.818Z",
    "milestone": "v2.3"
  },
  {
    "id": 5,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/e2e/allergen-ack-race.spec.ts",
    "line": null,
    "description": "31.1-15 #785 race spec listed only; RED (pre-rebuild runtime) and GREEN runs owed to 31.1-30",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-06T18:25:22.970Z",
    "resolved_at": "2026-10-07T11:12:28.951Z",
    "milestone": "v2.3"
  },
  {
    "id": 6,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "core-java/src/main/java/uk/jtoye/core/gdpr/DsarOutcomeMailer.java",
    "line": null,
    "description": "31.1-16 ACCESS link email proven only against a mocked JavaMailSender; live Mailhog capture, live customer-realm lookup and a click-through to /data-request/download (page built by 31.1-17) owed to 31.1-30",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-06T19:52:27.849Z",
    "resolved_at": "2026-10-07T11:12:29.131Z",
    "milestone": "v2.3"
  },
  {
    "id": 7,
    "kind": "stub",
    "phase": "31.1",
    "file": "frontend/app/data-request/download/download-client.tsx",
    "line": null,
    "description": "Unavailable state links 'Request a new copy' to /shop/account, which 31.1-26 builds; until then the link 404s",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-06T22:47:38.670Z",
    "resolved_at": "2026-10-07T11:12:29.314Z",
    "milestone": "v2.3"
  },
  {
    "id": 8,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/app/data-request/download/page.tsx",
    "line": null,
    "description": "Live click-through of the emailed Article 15 link (Mailhog -> /data-request/download -> Show my data once, second open unavailable) not run: shared stack not rebuilt; owed to 31.1-30",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-06T22:47:38.806Z",
    "resolved_at": "2026-10-07T11:12:29.500Z",
    "milestone": "v2.3"
  },
  {
    "id": 9,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/e2e/dsar-verify-link.spec.ts",
    "line": null,
    "description": "31.1-20 #839 compose-stack spec (Mailhog link -> /data-request/confirm, press, already confirmed) listed only; live RED/GREEN owed to 31.1-30 after the stack rebuild",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T00:04:51.201Z",
    "resolved_at": "2026-10-07T11:12:29.675Z",
    "milestone": "v2.3"
  },
  {
    "id": 10,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/e2e/storefront-dish-modal-a11y.spec.ts",
    "line": null,
    "description": "31.1-21: 'Dish modal allergen section (31.1-21)' (200% zoom placement + three statements on fixture SKU E2E-31121-DISH-ALLERGENS) listed only; live RED/GREEN owed to 31.1-30 on the rebuilt stack",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T00:21:26.304Z",
    "resolved_at": "2026-10-07T11:12:29.863Z",
    "milestone": "v2.3"
  },
  {
    "id": 11,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/e2e/kitchen-allergy-note.spec.ts",
    "line": null,
    "description": "31.1-22 kitchen allergy note browser spec (4 tests: board note + 44px button + ack round trip, order detail UK dates + channel line) written, type-checked and listed; not run, the live stack rebuild and click-through are owed to 31.1-30",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T00:42:00.356Z",
    "resolved_at": "2026-10-07T11:12:30.045Z",
    "milestone": "v2.3"
  },
  {
    "id": 12,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/e2e/seller-identity.spec.ts",
    "line": null,
    "description": "31.1-24 #789 seller-identity spec listed only (3 tests); live RED (pre-rebuild runtime) and GREEN runs owed to 31.1-30; its Mailhog half stays red until 31.1-25 ships the email seller block",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T02:03:55.160Z",
    "resolved_at": "2026-10-07T11:12:30.220Z",
    "milestone": "v2.3"
  },
  {
    "id": 13,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "scripts/openapi-gate.sh",
    "line": null,
    "description": "31.1-24: oasdiff compat gate not run locally (oasdiff not installed); OpenApiSnapshotTest red on the old snapshot, green on the regenerated one (additive: 49 insertions, 0 deletions)",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T02:03:55.293Z",
    "resolved_at": "2026-10-07T15:36:39.930Z",
    "milestone": "v2.3"
  },
  {
    "id": 14,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java",
    "line": null,
    "description": "31.1-25 #789/#785 order emails proven by MimeMessage content tests (unit + Testcontainers, mocked sender) and by OrderNotificationListenerIntegrationTest landing one Confirmed email in the local Mailhog from its own test context; the compose-stack Mailhog capture (rebuilt core-java, seller-identity.spec.ts email half) is owed to 31.1-30",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T03:00:22.755Z",
    "resolved_at": "2026-10-07T11:12:30.404Z",
    "milestone": "v2.3"
  },
  {
    "id": 15,
    "kind": "unrun-verify",
    "phase": "31.1",
    "file": "frontend/e2e/customer-account.spec.ts",
    "line": null,
    "description": "31.1-26 #838 customer-account spec listed only (3 tests: served-HTML wall on mobile+desktop, @desktop-only signed-in click-through lodging ACCESS+ERASURE with one Mailhog email each); live RED (pre-rebuild runtime has no /shop/account) and GREEN owed to 31.1-30",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-07T03:21:29.218Z",
    "resolved_at": "2026-10-07T11:12:30.592Z",
    "milestone": "v2.3"
  },
  {
    "id": 16,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "frontend/e2e/dashboard-interface-corrections.spec.ts",
    "line": 185,
    "description": "37-06 edited two staff-page e2e assertions (no invite-denial copy; People heading in the CLS test); not run, the shared runtime is not rebuilt until the 37-15 gate",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-08T12:56:26.518Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 17,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "frontend/components/dashboard/no-access-page.tsx",
    "line": null,
    "description": "37-06 B3 backstop not run: Playwright at 375px asserting no horizontal overflow on the no-access page body with a 60-character business name; the body wraps with overflow-wrap:anywhere (jsdom cannot measure)",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-08T12:56:28.291Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 18,
    "kind": "deviation",
    "phase": "37",
    "file": "frontend/components/dashboard/no-access-page.tsx",
    "line": null,
    "description": "37-06 B3 body says 'your business' where UI-SPEC copy has {business}: a person with no access cannot read any tenant record, and the session carries no business name",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-08T12:56:29.946Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 19,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "core-java/src/main/java/uk/jtoye/core/security/access/StaffInviteService.java",
    "line": null,
    "description": "37-07 invitation email not yet sent through the running compose stack (Mailhog): V77 and /api/v1/staff/invites reach the shared runtime only at the 37-15 rebuild; proven by integration tests with a re-parsed MimeMessage",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-08T13:58:26.371Z",
    "resolved_at": "2026-10-09T09:35:11.042Z",
    "milestone": "v2.3"
  },
  {
    "id": 20,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "infra/keycloak/realm-export.template.json",
    "line": null,
    "description": "37-08 vendor-realm user profile (tenant_id admin-only) not yet on the shared compose Keycloak: needs kc.sh import --override true at the 37-15 rebuild, then the live chain (profile GET, accepted invitee keeps tenant_id, token claim, account-console edit refused). Proven on a throwaway Keycloak 24.0.5 only",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-08T14:40:02.180Z",
    "resolved_at": "2026-10-09T09:35:11.153Z",
    "milestone": "v2.3"
  },
  {
    "id": 21,
    "kind": "deviation",
    "phase": "37",
    "file": "core-java/src/main/java/uk/jtoye/core/security/access/StaffInviteService.java",
    "line": null,
    "description": "37-08 moved the invite token from the URL path to the fragment ({base}#token={tenantId}.{token}) and the public API to POST preview/accept with the ref in the body; 37-09's /invite/[token] route, GET /{ref} and POST /{ref}/accept wording are superseded: the page must read location.hash and POST the ref",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-08T14:40:06.592Z",
    "resolved_at": "2026-10-08T16:22:20.362Z",
    "milestone": "v2.3"
  },
  {
    "id": 22,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "frontend/e2e/staff-invite.spec.ts",
    "line": null,
    "description": "37-09 live invite journey listed only (1 test, @desktop-only): Staff-form invite, Mailhog link, origin allow-list and no /invite Referer, 375px overflow backstop, account + Keycloak login_hint sign-in, joined toast, 'Shop manager · {shop}' with an instrument arm, second visit unusable; live RED (pre-rebuild runtime has no invite card) and GREEN owed to 37-15",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-08T16:22:20.043Z",
    "resolved_at": "2026-10-09T09:35:11.260Z",
    "milestone": "v2.3"
  },
  {
    "id": 23,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "frontend/e2e/dashboard-interface-corrections.spec.ts",
    "line": 174,
    "description": "37-09 inverted test 2: it asserted zero invite buttons on the Staff page and now asserts exactly one 'Send invitation' in the 'Invite someone' region; not run, the shared runtime is rebuilt at 37-15",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-08T16:22:20.149Z",
    "resolved_at": "2026-10-09T09:35:11.364Z",
    "milestone": "v2.3"
  },
  {
    "id": 24,
    "kind": "deviation",
    "phase": "37",
    "file": "core-java/src/main/java/uk/jtoye/core/security/access/dto/MyAccessDto.java",
    "line": null,
    "description": "37-09 added a nullable businessName to GET /api/v1/staff/me (additive, OpenAPI +7/-0): no endpoint a staff member can read named the business, and the D-26 joined toast must be built from server data; reaches the shared runtime only at the 37-15 rebuild (until then the toast says 'your new business')",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-08T16:22:20.257Z",
    "resolved_at": "2026-10-09T09:35:11.472Z",
    "milestone": "v2.3"
  },
  {
    "id": 25,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "frontend/app/dashboard/finance/page.tsx",
    "line": null,
    "description": "37-12 per-shop finance (API-echoed scope banner, Choose-a-shop state, Export CSV under the server filename, reversal rows) and the void dialog/voided badges are proven in jest only; no live browser run, because the shared runtime predates 37-10/37-11 and is rebuilt at 37-15",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-09T00:29:01.557Z",
    "resolved_at": "2026-10-09T09:35:11.582Z",
    "milestone": "v2.3"
  },
  {
    "id": 26,
    "kind": "deviation",
    "phase": "37",
    "file": "frontend/app/dashboard/finance/page.tsx",
    "line": null,
    "description": "37-12 finance marks a SALE 'Voided' only when its REVERSAL is on the same transactions page (the ledger DTO carries no voided flag on the sale); a sale whose reversal sits on another page of 20 shows no Voided badge",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-09T00:29:01.668Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 27,
    "kind": "deviation",
    "phase": "37",
    "file": "frontend/app/dashboard/products/import/page.tsx",
    "line": null,
    "description": "37-13 made a CSV row with no shop_id and no shopId the per-row error 'shop required' (for a Group admin too); the dashboard import page still posts the file without shopId, so every dashboard import reports 'shop required' and creates nothing until 37-14 sends the switcher shop (same 37-B PR)",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-09T01:23:06.434Z",
    "resolved_at": "2026-10-09T01:42:02.683Z",
    "milestone": "v2.3"
  },
  {
    "id": 28,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "frontend/app/shop/[slug]/checkout/page.tsx",
    "line": null,
    "description": "37-14 VAT display rule (no VAT row for a seller without a VAT number, the B7 note), the product-form VAT rate Select, the import target and the copy-to-shop dialog are proven in jest only; no live browser run, because the shared runtime predates 37-13 and is rebuilt at 37-15",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-09T01:42:09.320Z",
    "resolved_at": "2026-10-09T09:35:11.689Z",
    "milestone": "v2.3"
  },
  {
    "id": 29,
    "kind": "unmet-truth",
    "phase": "37",
    "file": "core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java",
    "line": null,
    "description": "37-15 live: D-06 'ungranted means no access' does not hold for /api/v1/customers. A tenant user with no shop_staff grant (fresh, or revoked) read all 8 tenant A customers (names, emails, phones) with GET 200 and created one with POST 201; Phase 23 left CustomerController tenant-scoped, not shop-scoped, by design, which strict OFF hid. Needs an owner ruling on the customer access rule before the 37-B PR merges RULED 2026-10-09 (owner, option B): every /api/v1/customers operation requires an honoured grant under D-06 (ShopAccessService.requireAnyAccess, the staff/me predicate), checked before the Idempotency-Key replay; proven in Testcontainers by CustomerAccessGateIntegrationTest; option C (per-shop narrowing) and option E (a SHOP_MANAGER floor on customer writes and staff DELETE) are separate future rulings.",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-09T09:35:11.795Z",
    "resolved_at": "2026-10-09T11:24:15.166Z",
    "milestone": "v2.3"
  },
  {
    "id": 30,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java",
    "line": null,
    "description": "WINDOWS #29 customer gate is proven in Testcontainers only; the compose runtime predates it, so check-runtime-freshness reports core-java and check-openapi-snapshot-fresh reports the five new 403s until the next rebuild; live proof owed at the 37-B ship: ungranted GET /api/v1/customers 403 shop-access-denied, STAFF 200, an ungranted dashboard login lands on the no-access page",
    "status": "fixed",
    "reason": "closed by 37-22 at cb8cf9d1 (core-java rebuilt, first since the customer gate): ungranted GET /api/v1/customers 403 shop-access-denied (admin control 200); STAFF grant -> 200; revoke 204 -> same token 403 (evidence/37-22-live-probes.md)",
    "recorded_at": "2026-10-09T11:24:20.204Z",
    "resolved_at": "2026-10-10T15:09:55.578Z",
    "milestone": "v2.3"
  },
  {
    "id": 31,
    "kind": "stub",
    "phase": "37",
    "file": "frontend/app/dashboard/kitchen/page.tsx",
    "line": 612,
    "description": "37-17 passes useKitchenAlert stopped:false and deriveFeedState no signedOut/noAccess: the K2 board-stopped state, the session-lapse and the access-ended inputs are 37-18's to wire (the hook and the pill already honour them, tested). Close when 37-18 wires all three.",
    "status": "fixed",
    "reason": "closed by 37-18 at a67cf8f0 (stopped + signedOut wired at b98a6608, noAccess at a67cf8f0)",
    "recorded_at": "2026-10-09T13:23:23.843Z",
    "resolved_at": "2026-10-10T13:56:51.218Z",
    "milestone": "v2.3"
  },
  {
    "id": 32,
    "kind": "deviation",
    "phase": "37",
    "file": "core-java/src/main/java/uk/jtoye/core/storefront/ShopAvailabilityPolicy.java",
    "line": null,
    "description": "D-25 owner ruling 2026-10-10 (accepted consumer-safety risk): a NEEDS_REVIEW shop's day whose free text V80 could not read keeps taking storefront orders all day (the pre-D-15 fail-open) until the vendor saves structured hours; read days and days written Closed are enforced as before deploy. Close when no shop is NEEDS_REVIEW, or by a new owner ruling.",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-10T13:14:21.463Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 33,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "core-java/src/main/java/uk/jtoye/core/shop/ShopController.java",
    "line": null,
    "description": "37-20 pause endpoints, the 409 shop-closed refusal and the new ShopDto/PublicShopDto fields are proven in Testcontainers only; the compose core-java predates them (and V80, WINDOWS #30). Owed at the next core-java rebuild: PUT /shops/{id}/pause then a storefront order 409 PAUSED, DELETE then 201, GET /public/shops/{slug} openNow, the V80 NOTICE counts, check-openapi-snapshot-fresh green.",
    "status": "fixed",
    "reason": "closed by 37-22 at cb8cf9d1: V80 applied on the dev DB (LEGACY_ALWAYS_OPEN=5, MIGRATED_FROM_TEXT=0, NEEDS_REVIEW=0); ungranted pause 403; STAFF pause 200 -> public openNow false -> guest order 409 shop-closed PAUSED, nothing written; resume 200 -> same key 201; check-openapi-snapshot-fresh PASS (121 paths), check-runtime-freshness PASS 4/4 (evidence/37-22-live-probes.md)",
    "recorded_at": "2026-10-10T13:14:21.570Z",
    "resolved_at": "2026-10-10T15:09:55.693Z",
    "milestone": "v2.3"
  },
  {
    "id": 34,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "frontend/components/dashboard/kitchen/kds-board-stopped.tsx",
    "line": null,
    "description": "37-18 proven in jest only, not live: the alarm (3x 440 Hz square every 3 s through the shared AudioContext; audibility and distinctness from the chime are a by-ear 37-23 UAT row), Silence alarm, the silent token refresh over real 5-minute Keycloak tokens (fake timers only; the jwt skew is a unit rule), a REAL grant revoke ending the board (live only via a route-stubbed typed 403) and the STOMP socket closing on it, and the deliberate-sign-out race. Live 10/10 on frontend 809cf75c covers the lapse overlay, URL, pill, title, Sign in again, deliberate Sign out and the K2 backstop; 37-23 re-runs e2e/kitchen-session-lapse.spec.ts",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-10T13:57:09.943Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 35,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "frontend/app/shop/[slug]/checkout/page.tsx",
    "line": null,
    "description": "37-22 proven in jest only, not live: the checkout RACE UI (a shop pausing while its checkout page is open: the race copy in role=alert and the stored basket unchanged by content) - live covers the API 409 shop-closed PAUSED and the at-load disable; and the NEEDS_REVIEW JSON-LD rule (only declared windows, an unreadable day never published) - no NEEDS_REVIEW shop exists on the dev DB. 37-23 UAT: pause from the kitchen while a customer is on checkout.",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-10T15:10:21.406Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 36,
    "kind": "deviation",
    "phase": "37",
    "file": "frontend/components/dashboard/shops/opening-schedule-editor.tsx",
    "line": null,
    "description": "37-21 orchestrator copy decision under the D-25 owner ruling (2026-10-10), pending the owner's own wording: the editor keeps pre-filling an unreadable NEEDS_REVIEW day as Closed (a save makes it closed, D-15) but its amber line reads 'We couldn't read \"{original text}\". Customers can still order on this day at any time until you save your hours.' instead of the UI-SPEC's 'This day shows as closed.' (untrue under the ruling); the dashboard needs-review card reads '...for {days}. Customers can order on those days at any time until you save your hours.'; UI-SPEC rows 458/791/794 amended to match. Close when the owner approves or replaces the wording.",
    "status": "fixed",
    "reason": "closed by 261010-ppb at 2752ff50: owner ruling 2026-10-10 - an unreadable day pre-fills as one all-day window with the ruled editor line and card sentence; UI-SPEC 458/791/794 amended",
    "recorded_at": "2026-10-10T15:54:22.775Z",
    "resolved_at": "2026-10-10T18:36:24.564Z",
    "milestone": "v2.3"
  },
  {
    "id": 37,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "frontend/components/dashboard/kitchen/pause-orders-control.tsx",
    "line": null,
    "description": "37-21 and 261010-ppb proven in jest only, not live: a pause refused with shop-access-denied now shows the K2 Access ended overlay and no inline copy (owner ruling 2026-10-10; live it is the same STAFF gate as the board read, not provoked live); the in-flight disabled state, the stated-end hide (fake timers), the editor overlap / missing-time / next-day-spill validation with role=alert, aria-invalid and focus, and the dashboard-home pending / failed-load states. A NEEDS_REVIEW and a LEGACY shop were shown live only through a browser-side rewrite of the shops LIST response (no such shop exists on the dev DB since 37-22 seeder). The slug fix fail direction (a save renaming /shop/{slug}) was deliberately not run live. 37-23 UAT: tap twice fast, provoke a validation error by hand, and read the pause 403 as a revoked STAFF user: expect the K2 Access ended overlay (Your access has ended) and no inline refusal copy.",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-10T15:54:22.887Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 38,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "frontend/components/dashboard/kitchen/kds-sound.tsx",
    "line": null,
    "description": "261010-ppb alarm cap (owner ruling 2026-10-10) proven in jest only: an unsilenced board-stopped alarm stops by itself after 120 s while the red overlay stays; 37-23 UAT by ear: a stopped board rings, then falls silent at about 2 minutes, red screen still up",
    "status": "fixed",
    "reason": "",
    "recorded_at": "2026-10-10T18:36:59.063Z",
    "resolved_at": "2026-10-11T18:37:35.663Z",
    "milestone": "v2.3"
  },
  {
    "id": 39,
    "kind": "todo",
    "phase": "37",
    "file": "core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java",
    "line": null,
    "description": "DEFERRED to Phase 39 (owner ruling 2026-10-10): option C, per-shop customer narrowing. Shape: the next free Flyway version at Phase 39 plan time (the ruling named V81, which 37-27 reserves) adds nullable customers.origin_shop_id REFERENCES shops(id), plus the customers_aud mirror in the same migration (Customer is @Audited); a tenant-looped, GUC-pinned backfill from orders (idx_orders_customer exists); the non-Group-admin predicate origin_shop_id IN granted OR EXISTS an order of that customer in a granted shop, on list, by-id, PUT, DELETE and the OrderService.java:170 and :348 lookups (the tenant-wide findById bypass); a refused id answers 404, not 403; a NOSUPERUSER Testcontainers proof. Phase 39 is Platform runtime upgrades: /gsd-plan-phase 39 decides 39-F or a phase of its own.",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-10T18:48:49.187Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 40,
    "kind": "todo",
    "phase": "37",
    "file": "core-java/src/main/java/uk/jtoye/core/shop/ShopService.java",
    "line": null,
    "description": "DEFERRED to Phase 39 (owner ruling 2026-10-10): old-slug 301 aliases (a shop_slug_alias table, RLS-exempt by addition like V62/V75) so printed links and QR codes survive a deliberate slug change; the slug-keep fix itself is 37-48",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-10T18:48:49.311Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 41,
    "kind": "todo",
    "phase": "37",
    "file": "infra/keycloak/realm-export.template.json",
    "line": null,
    "description": "staging and production vendor realms are not in this repo: an operator must set ssoSessionMaxLifespan 50400 (D-14 amended 2026-10-10); refresh-token rotation stays off there as here",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-10T18:48:49.440Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 42,
    "kind": "todo",
    "phase": "37",
    "file": "infra/keycloak/realm-export.template.json",
    "line": 9,
    "description": "DEFERRED (orchestrator revision 2026-10-10, recorded for the owner): refresh-token rotation stays off (revokeRefreshToken false). A per-process single-flight cannot protect the 3 k8s frontend replicas (nor middleware.ts, which runs auth in another runtime). Turning it on needs a shared guard (a Redis-backed single-flight keyed by refresh token, or sticky sessions proven) or a Keycloak refreshTokenMaxReuse policy, then its own ruling",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-10T18:48:49.557Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 43,
    "kind": "unmet-truth",
    "phase": "37",
    "file": "frontend/app/shop/[slug]/shop-detail-client.tsx",
    "line": null,
    "description": "37-36 truth 3 / UI-SPEC C4: the review-form notice was not shipped because the frontend has no review form (reviews are API-only); see 37 deferred-items",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-12T16:10:54.367Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 44,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "core-java/src/main/resources/db/migration/V83__shop_fulfilment_offered.sql",
    "line": null,
    "description": "37-48 V83 (offers_delivery/offers_collection), the four shop DTO fields, 422 fulfilment-not-offered on both writers, the slug-keep rule and 409 shop-slug-taken are proven in Testcontainers and jest only; the compose core-java predates them. Owed at 37-50 after rebuilding ALL images: V83 applied out of order, a vendor sets fee 299 and threshold 3000 and the storefront charges them, a collection-only shop refuses DELIVERY on the storefront and the vendor API, a shop edit keeps /shop/{slug}, check-openapi-snapshot-fresh green",
    "status": "fixed",
    "reason": "closed by 37-50 on the rebuilt runtime (core 0beed8d18f5c): V83 applied out of order; a vendor PUT set fee 299 and threshold 3000 and the storefront charged 299 at 1600 and 2000 and 0 at 3000; Brixton collection-only refused DELIVERY 422 fulfilment-not-offered on the storefront and on POST /orders with 0 rows; a name edit kept /shop/brixton-village-grill (200, canonical unchanged); check-openapi-snapshot-fresh PASS; RED recorded on the old runtime (evidence/37-50-gate-37G.md Part 2)",
    "recorded_at": "2026-10-14T11:33:57.563Z",
    "resolved_at": "2026-10-14T16:35:40.317Z",
    "milestone": "v2.3"
  },
  {
    "id": 45,
    "kind": "unrun-verify",
    "phase": "37",
    "file": "frontend/app/shop/[slug]/shop-detail-client.tsx",
    "line": null,
    "description": "37-49: G1/G2 visual and live proof owed to 37-50 - the Collection only chip and the wrapped status row at a real 375px render, the web-address row and create preview at 375/768/1280, and a collection-only shop set in the live dialog then ordered at checkout on rebuilt images",
    "status": "fixed",
    "reason": "closed by 37-50: the Collection only chip wraps under the open state and rating at 375/390/768/1280 with page scrollWidth equal to the viewport; the web-address row and the create preview at 390/768/1280 and a long slug at 375 stay inside the dialog; Brixton set collection-only in the live dialog while a Delivery checkout was open, which met the 422 copy, switched to the static row and ordered COLLECTION (evidence/37-50-gate-37G.md 2.5-2.7, evidence/37-50-screens/)",
    "recorded_at": "2026-10-14T11:50:46.206Z",
    "resolved_at": "2026-10-14T16:35:40.427Z",
    "milestone": "v2.3"
  },
  {
    "id": 46,
    "kind": "unrun-verify",
    "phase": "29",
    "file": "scripts/staging-db-bootstrap.sh",
    "line": null,
    "description": "29-07: role bootstrap proven only against a postgres:18 stand-in (fake_admin, a CREATEROLE CREATEDB BYPASSRLS non-superuser, not azure_pg_admin): A18 (admin may ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app after self-membership, pre-create uuid-ossp, own/ALTER schema public), BYPASSRLS for jtoye_backup, and --stage monitor (needs ADMIN OPTION on pg_monitor, failed without it on the stand-in) owed to the live readbacks of 29-14 and 29-23",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-18T23:32:14.216Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 47,
    "kind": "unrun-verify",
    "phase": "29",
    "file": "scripts/azure-staging-datastores.sh",
    "line": null,
    "description": "29-07: dry-run only; the live proofs are owed to 29-09 Task 2 / 29-14: the server holds the Key Vault pg-admin--password value (CLI @FILE expansion, cited from azure-cli 2.91.0 source), exactly one firewall rule, azure.extensions UUID-OSSP, and sslmode=verify-full sslrootcert=system with the mounted host CA bundle on the first --stage pre login",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-18T23:32:14.332Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 48,
    "kind": "deviation",
    "phase": "29",
    "file": "k8s/staging/kustomization.yaml",
    "line": null,
    "description": "29-08: the plan said delete the staging replicas: block; kept at count 1 (= the HPA floor) instead, because deleting it renders base spec.replicas 3/5/3 and every kubectl apply would request 11 app pods on 3 x B2s until HPA scale-down stabilisation. Owner may prefer removing spec.replicas from base Deployments (affects production) - a separate decision",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-19T00:01:27.604Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 49,
    "kind": "deviation",
    "phase": "29",
    "file": "k8s/components/keycloak/keycloak.yaml",
    "line": null,
    "description": "29-10: added KC_CACHE=local (single replica; jdbc-ping would seek peers on 7800, which keycloak-allow does not admit) and JAVA_OPTS_KC_HEAP MaxRAMPercentage=60 (the image default 70% of the 1Gi limit plus ~300 MB non-heap sits at the limit). Heap headroom is ASSUMED; plan 29-14 measures working set against the limit",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-19T00:30:16.913Z",
    "resolved_at": null,
    "milestone": "v2.3"
  },
  {
    "id": 50,
    "kind": "unmet-truth",
    "phase": "29",
    "file": "core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java",
    "line": 1776,
    "description": "29-14: storefront never shows a pipeline-uploaded image (toPublicProductDto reads flat image_url; asset-first resolution is vendor-only); the 'storefront renders it with naturalWidth > 0' truth is unmet at a784e7e2",
    "status": "fixed",
    "reason": "closed by 29-29 at 6e95ee6c (deployed to staging): the anonymous menu's Pelau imageUrl and imageUrls[0] are its ACTIVE pipeline derivative (asset 14add455, which the vendor uploaded in 29-22 in place of 29-14's 1475a89c, now released); the storefront image renders with naturalWidth 640 on mobile and desktop and the Product JSON-LD image equals its src (evidence/29-29-storefront-image.txt)",
    "recorded_at": "2026-10-19T10:40:14.320Z",
    "resolved_at": "2026-10-19T13:54:56.914Z",
    "milestone": "v2.3"
  },
  {
    "id": 51,
    "kind": "deviation",
    "phase": "29",
    "file": "k8s/base/core-java-deployment.yaml",
    "line": null,
    "description": "29-14: core-java/keycloak 1Gi surge pods fit no B2s node (661/399/803Mi free by requests); first deploy unstuck by scaling the crash-looping revision-1 ReplicaSets to 0; every later roll of either will stick until capacity is decided",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-10-19T10:40:14.440Z",
    "resolved_at": null,
    "milestone": "v2.3"
  }
]
````
