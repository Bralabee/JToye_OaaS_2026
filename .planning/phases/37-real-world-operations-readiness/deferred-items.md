# Phase 37 deferred items

Out-of-scope discoveries logged by executors. Each names who found it, what was measured, and
where it must be resolved.

## 1. docs-freshness gate is red on the integration branch (found in 37-03)

- **Measured 2026-10-08 on `739b69d7`:** `bash scripts/docs-freshness.sh` exits 1:
  `docs/metrics.json` says `java_test_methods 2322 / java_test_files 346 / total 4868`; the
  source computes `2330 / 349 / 4876`.
- **Who moved it:** 37-02 added 3 test methods in 2 files (`ShopGrantsIntegrationTest` 2,
  `StrictScopingBootedValueIntegrationTest` 1); 37-03 added 5 in 1
  (`DemoDataSeederServiceAccountGrantIntegrationTest`).
- **Why deferred:** the fix is `scripts/docs-freshness.sh --write` plus the prose counts that
  `scripts/check-doc-metrics.sh` checks in `CLAUDE.md`, `AGENTS.md` and `README.md`. Every later
  37 plan adds tests too, so regenerating per plan only churns those files.
- **Must be resolved before the phase PR opens** (both gates run in
  `.github/workflows/docs-freshness.yml` and fail the build on drift). Re-measure at that point;
  do not copy the numbers above.
- **37-05 adds a second dimension:** migration `V76__user_directory_realm_admin_seen.sql`. So
  `schema_version` in `docs/metrics.json` (computed by `docs-freshness.sh` from the tracked
  migration files) moves from 75 to 76. The prose in `CLAUDE.md` / `AGENTS.md` that names the current
  schema version and the migration count ("V75", "75 Flyway migrations, V1 through V75") needs a V76
  entry at the same pre-PR step. 37-05 also added Java test methods
  (`StaffEffectiveAccessIntegrationTest` 9 in a new file, `MembershipSerializerRoundTripTest` 2,
  `StaffManagementIntegrationTest` 2 in nested classes). Re-measure; do not copy these numbers.
- **37-07 adds V77** (`V77__staff_invite.sql`, a new tenant table), so `schema_version` moves to 77
  and the prose naming the current schema / migration count needs a V77 entry at the same pre-PR
  step. 37-07 also added three test files (`StaffInviteIntegrationTest`,
  `StaffInviteRlsIntegrationTest`, `EmailNotificationServiceStaffInviteTest`). Re-measure.

## 6. 37-07 invites are not on the shared runtime, and the accept page does not exist yet

- 37-07 changed core-java, config and manifests only; the compose stack was not rebuilt (the
  strict-scoping flip and everything layered on it reach the shared runtime at the 37-15 gate,
  §4). So V77, the four `/api/v1/staff/invites` endpoints and the invitation email through
  Mailhog are proven by integration tests only (a mocked `JavaMailSender` whose message is
  serialised and re-parsed). The live send through Mailhog is owed to the 37-15 rebuild, with
  `scripts/check-runtime-freshness.sh`. Recorded in `.planning/WINDOWS.md`.
- The emailed link `{STAFF_INVITE_ACCEPT_BASE_URL}/{tenantId}.{token}` points at the frontend
  `/invite/[token]` page, which 37-08/37-09 build. Until then an invitation emailed from a
  rebuilt stack opens a 404 page. No staff-page UI was added in 37-07, so the 37-06 jest test and
  e2e spec that assert "no invite control" are unchanged; they change with the UI plan.

## 2. `GET /api/v1/media/review-queue` is not shop-scoped (found in 37-03)

- **Measured:** `MediaRedriveControllerTest` AC-4.8 stays green under
  `ACCESS_STRICT_SCOPING=true` with a fresh, ungranted vendor subject: the review queue returns the
  tenant's FAILED / flagged / stalled assets to a caller with no shop access.
- **Source:** `MediaAssetService.reviewQueue()` calls `mediaAssetRepository.findReviewQueue(...)`
  with no `ShopAccessService` call; its Javadoc says it is tenant-scoped by RLS only.
- **Why deferred:** a production authorization change, not a test conversion; outside 37-03's
  files. Under D-06 an ungranted user can still read this tenant-wide list (asset ids, product ids,
  failure reasons). Route to the 37-B shop-read work or a dedicated plan.
- **RESOLVED in 37-04** (deviation, Rule 2), because 37-04's flip made it live:
  RED `b2ff028a` (`MediaReviewQueueShopScopeIntegrationTest`, RED_EVIDENCE_OK), fix `4c55677b`.
  The queue now follows the product-list read rule: GROUP_ADMIN sees the tenant, any other caller
  only its granted shops' assets, an ungranted caller nothing, a shop-less asset GROUP_ADMIN only.
  AC-4.8 now reads as shopA's manager. The companion `POST /api/v1/media/{assetId}/keep` was
  checked and already required SHOP_MANAGER on the owning shop (WR-03,
  `MediaKeepShopScopeIntegrationTest`); no change. Evidence: 37-04-SUMMARY.md.

## 3. Comments in the frontend still describe the old OFF default (found in 37-04)

- `frontend/lib/shops-api.ts:9-12` ("under the default strict-scoping = false is every
  JIT-provisioned user"), `frontend/lib/staff-api.ts:19` and
  `frontend/app/dashboard/staff/page.tsx:~639` (copy about "before enabling strict-scoping").
- **Why deferred:** comment/copy only, no behaviour; RESEARCH §37-B.1 routes this copy to the
  D-07 / D-09 Staff-page work, which rewrites the same lines. Leaving it to them avoids two
  rewrites of one paragraph.
- **RESOLVED in 37-06.** `shops-api.ts` and `staff-api.ts` now describe strict scoping ON by
  default; the staff-page block (the automatic-grant badge and its comment) is gone, replaced
  by the server-computed access column (`916d101a`).

## 5. 37-06 frontend checks owed to the runtime gate

- 37-06 changed only frontend code. Two staff-page assertions in
  `frontend/e2e/dashboard-interface-corrections.spec.ts` were edited to follow the new page
  (no invite-denial sentence; the CLS test now waits for the "People" heading), and the B3
  backstop (Playwright at 375px: no horizontal overflow on the no-access page) was not
  written or run. Both need the rebuilt stack, because the People column reads `people[]`,
  which the running core-java does not serve until the 37-15 rebuild. Recorded in
  `.planning/WINDOWS.md` (entries 16 and 17).
- 37-06 also moved the Jest count (staff-page, no-access-page, confirm-action-dialog,
  interceptor and webhooks cases). The full suite reads 197 suites / 2270 tests at `034328bd`.
  Fold this into the §1 regeneration; re-measure, do not copy.

## 4. The flip is not on the shared runtime yet (by design, 37-04)

- 37-04 changed code and config only. The running compose stack was NOT rebuilt: per the plan's
  must-have, the flip reaches a shared runtime only at the 37-15 gate, after the owner has seen
  the list of existing users who hold only an automatic grant.
- So MCP `create_order` / `list_products` under strict ON with real Keycloak client-credentials
  tokens (37-03 coverage D4) is still proven by integration tests only; the live check is owed
  to the 37-15 gate, together with `scripts/check-runtime-freshness.sh`.

## 7. 37-08 accept flow: what binds 37-09 and 37-15

- **The invite link changed shape (37-08 deviation, the V75 rule).** It is now
  `{STAFF_INVITE_ACCEPT_BASE_URL}#token={tenantId}.{token}`: the token rides in the URL
  **fragment**, which a browser never sends to a server. The public API takes the reference in a
  JSON body, never in a path: `POST /api/v1/public/staff-invites/preview {ref}` and
  `POST /api/v1/public/staff-invites/accept {ref, firstName, lastName, password}`.
  **37-09 must follow:** the page is `/invite` (not `/invite/[token]`), reads `location.hash`
  client-side, drops it with `history.replaceState`, and POSTs the ref. 37-09-PLAN's
  `GET /api/v1/public/staff-invites/{ref}` and `POST .../{ref}/accept` wording is superseded
  (WINDOWS.md entry 21). Measured reasons the path form was unsafe, beyond access logs: the
  ProblemDetail `instance` member echoes the request path, so a path token would be written back
  into every error body, and `RateLimitInterceptor` logs the request path on every public 429.
- **Shared-runtime Keycloak is stale.** The vendor-realm user profile (`tenant_id` admin-only)
  reaches the running compose Keycloak only by `kc.sh import --override true` at the 37-15
  rebuild. Until then an invitation accepted against the shared stack would create a user whose
  `tenant_id` is stripped. The chain is proven on a throwaway Keycloak 24.0.5 only
  (`infra/keycloak/README.md`, "User profile"); the live proof is owed to 37-15 (WINDOWS.md
  entry 20). Staging/production need the operator step in `37-USER-SETUP.md`.
- **`.env.example` ships `KC_ADMIN_ENABLED=false`** while compose defaults it to `true`. An
  operator who copies `.env.example` verbatim turns the admin seam off, and then every preview
  and accept answers 503 `staff-invite-account-service-unavailable` (DSAR account deletion is
  already NOT_CONFIGURED in the same state). Pre-existing; not changed here.
- **Keycloak 24.0.5's password-policy refusal is generic.** Measured against the shared
  `jtoye-dev` realm: `{"errorMessage":"Password policy not met"}`, with no rule named. The 422
  shows it verbatim, as UI-SPEC B2 requires, so the invitee is not told which rule failed.
  37-09 may add the realm's rule text as static help copy; the server cannot name the rule.
- **Docs metrics (§1).** 37-08 adds `StaffInviteAcceptIntegrationTest` (11 tests, new file) and
  5 test methods to `KeycloakAdminClientTest`. Re-measure at the pre-PR step.
- **GDPR residual (from 37-07, unchanged).** A cancelled or expired invitation keeps the
  invitee's address in `staff_invite.email_normalised` indefinitely; an accepted one keeps it
  too. Recorded for `/gsd-secure-phase`; not redesigned here.

## 8. 37-09 invite UI: what binds 37-15 and the phase PR

- **The route is `/invite`, and WINDOWS.md entry 21 is closed.** The page reads `#token=` once,
  drops it with `history.replaceState`, and POSTs `{ref}` to `preview` and
  `{ref, firstName, lastName, password}` to `accept`. `Referrer-Policy: no-referrer` is served on
  `/invite/:path*` (measured on `next start` of this tree: `/invite` no-referrer, `/for-operators`
  strict-origin-when-cross-origin), robots disallows `/invite`, and `scripts/gates/ssr-routes.conf`
  declares it STATIC.
- **`GET /api/v1/staff/me` now carries `businessName` (37-09 deviation, WINDOWS.md entry 24).** No
  endpoint a staff member can read named the business, and the D-26 "You've joined {business}"
  toast must come from server data. Additive (OpenAPI +7/-0). Until the 37-15 rebuild the running
  core-java omits it and the toast reads "You've joined your new business." Re-check after the
  rebuild with the live spec below.
- **B3 can now name the business.** WINDOWS.md entry 18 (37-06: the no-access body says "your
  business" because the session carries no name) has its data source now: `MyAccess.businessName`
  from staff/me, which the no-access page's shell already fetches. Not changed here (37-06's
  component, not in this plan's files); route to whichever plan next touches B3, or close it at the
  phase PR.
- **Live proof owed to 37-15** (WINDOWS.md entries 22 and 23): `e2e/staff-invite.spec.ts` (RED on the
  pre-rebuild runtime: no "Invite someone" card; GREEN after the rebuild and the realm re-import of
  entry 20), and the inverted `dashboard-interface-corrections.spec.ts` test 2.
- **The invite form's 409 copy is ready but dormant.** UI-SPEC B1 maps a 409 on
  `POST /api/v1/staff/invites` to "This email address already has a J'Toye account with another
  business…", but 37-07's issue endpoint never answers 409 (the other-business check runs at
  preview/accept, 37-08). The accept page shows that state from the preview's `accountState`. No
  change made; recorded so nobody reads the form's 409 branch as server behaviour.
- **Docs metrics (§1).** 37-09 adds jest `staff-invites.test.tsx` (13), `invite-client.test.tsx`
  (23), `joined-toast.test.tsx` (6), one header test, and one Java IT method
  (`StaffEffectiveAccessIntegrationTest`); one Playwright test. Full jest at 37-09: 200 suites / 2313
  tests. Re-measure at the pre-PR step.

## 9. 37-13 CSV shop and copy-to-shop: what binds 37-14, 37-15 and the phase PR

- **The dashboard import is broken until 37-14 ships (WINDOWS.md entry 27).** A CSV row with no
  `shop_id` and no `shopId` parameter is now the per-row error "shop required", for a Group admin
  too. `frontend/app/dashboard/products/import/page.tsx` still posts only the file, so until 37-14
  sends the switcher shop every dashboard import creates nothing. Before this plan those rows were
  created shop-less, and the storefront never showed them. 37-14 is in the same 37-B PR, so the
  PR must not merge without it.
- **The copy SKU is not the source SKU.** SKUs are unique per tenant (`idx_products_tenant_sku`,
  V3), so a copy cannot keep its source's SKU. The copy is `{source SKU}-{first 8 characters of
  the target shop id}`. 37-14's copy dialog and its result toast should not promise "same SKU".
- **Stock is copied as stored** (`quantity_in_stock`), as the plan says. A tracked item with 50 in
  shop A arrives with 50 in shop B. This needs an owner look at the phase PR. If shop B does not
  hold that stock, the copy can oversell until the vendor corrects it.
- **Suspected pre-existing issue, not verified (out of scope).** `product_media.product_id` has no
  `ON DELETE CASCADE` (V53) and `ProductService.deleteProduct` does not drop the product's links.
  So deleting a product that has any `product_media` row should fail with 23503 and come back as
  the misleading 409 "still referenced by an existing order". Copies carry links, so this would
  affect them as well as uploaded products. Not run here; measure before filing.
- **Runtime parity is owed to 37-15.** `POST /products/copy`, the `shopId` parameter on
  `/products/bulk/csv` and the shared-image delete guard reach the compose stack only at the 37-15
  rebuild.

## From 37-16 (kitchen New lane, card additions)

- **Old PENDING orders now fill the New lane on the dev tenant.** Measured on the rebuilt stack
  (2026-10-09, evidence/37-16-screens): Brixton Village Grill's lane holds 20 PENDING orders, many
  of them `seed-order-metric.sh` probes up to 39 days old, each with the NEW strip and the 1 Hz ring.
  This is D-11 working (they are unaccepted orders), but 37-17's repeating alert will ring for all of
  them, and a vendor's first look at the board is a wall of stale orders. Two things for 37-17 and
  the 37-A gate: reject the dev-tenant seed probes before any screenshot or persona pass, and decide
  whether `scripts/seed-order-metric.sh` should cancel its own probe order after the counter exists.
  Not changed here: the lane must show every unaccepted order, and hiding old ones would be a choice
  the owner has not made.
- **Pre-existing, observed only (out of scope).** On a 4-column desktop card (1280px) the 20px
  "ALLERGENS" label of `OrderAllergenBanner` touches or overruns the right edge of its amber box
  (screenshot 37-16-kitchen-1280.jpeg). The banner and the card width are unchanged by 37-16 (UI-SPEC
  L6: the banner is untouched), so grid cards had the same width before. Measure before filing.

## From 37-17 (repeating alert, sound state, truthful pill)

- **The dev tenant's 20 stale PENDING orders now RING.** Measured live on the rebuilt frontend
  (2026-10-09, evidence/37-17-screens): Brixton Village Grill's tab reads "(20) New orders · Kitchen
  Display — J'Toye", and once "Turn on sound" is tapped the chime repeats every 5 s until all 20 are
  accepted or rejected. That is D-12 working (37-16's note predicted it). Before any persona pass or
  human audio check on the dev tenant, reject the `seed-order-metric.sh` probe orders, or the first
  thing the tester hears is a board that will not stop. Whether the seed script should cancel its
  own probe is still the 37-16 question; nothing was changed here.
- **The header sound control takes its own row.** The labelled control ("Turn on sound", h-11) is
  wider than the icon button it replaced, so the header's `flex-wrap` control row gains a row: at
  390 px it is pill / selector + Print all / sound control, and at 1280 px the control wraps under
  the pill beside a long shop name. It is there from the first paint (no CLS) and the pill keeps a
  constant 240 px in every state (evidence/37-17-browser-probe.json). A design look at the 37-23
  UAT, not a defect.
- **Persisted mute and the server render (pre-existing pattern, not measured).** The page reads
  `kds-muted` in a `useState` initialiser, so the server renders the "Sound is off" bar and a muted
  client renders the "Alerts are muted" bar: the same hydration text mismatch the old icon button
  already had. The two bars are the same box, so nothing moves. Measure the console before filing.
- **`docker compose build frontend` does not retag core-java.** Measured here: core-java's image ID
  and LastTagTime were identical before and after, unlike `up -d --build frontend` (the recorded
  trap). Build alone, then `up -d --no-deps --force-recreate frontend`, touches only the frontend.
- **Dead AudioContext mocks.** `kds-new-lane`, `kds-card-additions`, `allergy-note-board` and
  `allergen-surfacing` tests still install a per-beep `AudioContext` mock "so playBeep() does not
  explode". Nothing constructs one without a tap any more, so they are inert; harmless, left alone
  (not this plan's files).

## From 37-20 (pause and structured-hours enforcement)

- **The NEEDS_REVIEW copy in 37-21 and the UI-SPEC is now untrue.** Owner ruling 2026-10-10 (D-25
  honoured literally): a day V80 could not read stays OPEN on the server until the vendor saves
  structured hours, and `PublicShopDto.openNow` says so. The copy "We couldn't read "{original text}".
  This day shows as closed." (37-21 must-have, UI-SPEC lines 458 and 791) describes the old client
  render, not what customers get. 37-21 pre-filling such a day as Closed in the EDITOR is still right
  (a save makes it closed, D-15); the explanatory line needs the owner's wording, along the lines of
  "customers can still order on this day until you save your hours". 37-22 must render `openNow`, not
  re-parse `openingHours`, or the storefront shows Closed while checkout accepts the order.
- **The docs-metrics gate now VOIDs (rc 2), a new cause on top of item 1.** Measured 2026-10-10 on
  `935bf0a6`: `scripts/docs-freshness.sh` stops at `count-test-blocks.mjs could not count family
  'jest'`: `frontend/components/dashboard/kitchen/__tests__/kds-feed-status.test.tsx:244` (37-17)
  has an `.each(TABLE.filter(...))` table the counter's POLICY cannot resolve. Extend the counter
  (or rewrite the table as a literal) before the pre-PR regeneration. 37-20 adds 27 unit test methods
  (`ShopAvailabilityPolicyTest` 25, `ShopDtoScheduleCacheRoundTripTest` 2) and one 14-method IT
  file (`ShopPauseIntegrationTest`). Re-measure; do not copy.
- **Interim, until 37-21 replaces the free-text editor:** a vendor saving free-text `openingHours`
  through today's dashboard changes only the legacy text, not `hours_source`, so it no longer
  affects whether the shop takes orders. A LEGACY shop stays open at any time; a STRUCTURED or new
  shop follows its schedule (a new shop with none takes no orders, D-15). Both run on this phase
  branch only.
- **The runtime is not rebuilt.** core-java on the compose stack predates the customer gate, V80
  and 37-20 (WINDOWS #30, and the WINDOWS entry 37-20 adds). The pause endpoints, the 409
  shop-closed and the new DTO fields are proven in Testcontainers only; `check-openapi-snapshot-fresh.sh`
  will report the 274 added snapshot lines until the next rebuild.
