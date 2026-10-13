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

## From 37-18 (board-stopped overlay, safe sign-in return, silent refresh)

- **The board WILL stop every two hours of a shift.** D-14 keeps the realm session lifetimes, and
  auth.ts records the vendor realm's SSO maximum as 2 h (idle 30 min). The silent refresh keeps the
  access token fresh, and the board's 60 s poll keeps the SSO session from idling out, but nothing
  extends the 2 h maximum: at that point the refresh fails, and the board goes red and sounds the
  alarm. That is the overlay doing its job. The 37-23 UAT should expect it, and the CONTEXT's
  deferred "longer vendor-realm SSO lifetime for shift tablets" is the remedy. Not measured on a
  2 h run here.
- **The alarm ignores the "kds-muted" preference (a 37-18 decision, open to an owner ruling).** Mute
  silences the new-order chime; the overlay's own "Silence alarm" silences the alarm. The reason: a
  muted handover board that stops is the case the alarm exists for. If the owner wants mute to
  silence both, it is one condition in `useKitchenAlert` (`alarming = alarm && running`).
- **At 375x667 with 130% text the overlay opens scrolled to its focused primary button**
  (evidence/37-18-screens/k2-signed-out-375x667-root130.png): the icon and the first headline line
  sit above the fold, reachable by scrolling. This is the plan's recorded overflow assumption (scroll
  rather than scale type) and keeps focus visible. A design look for the 37-23 UAT, not a defect.
- **A deliberate sign-out on a NON-kitchen route can still race (pre-existing, not measured).**
  The kitchen leaves a lapse unclaimed after Sign out, which keeps today's behaviour everywhere: a
  poll that 401s between `vendorLogout`'s cookie drop and its navigation to Keycloak triggers the
  api-client's own redirect to /auth/signin, and the later assignment wins. If that lands last, the
  Keycloak end-session half of R-01 is skipped. Measure before filing; the fix belongs in
  `vendorLogout` or the api-client, not the kitchen.
- **The kitchen treats every shop-access-denied 403 on its route as "access ended".** Today every
  API call on that route is the board's own (the read, the detail, the bumps, Accept, Reject, the
  allergy acknowledgement). The shell's `staff/me` and shop list are not known to answer with that type (not measured). If a
  shell-level read ever does, the board would stop on it. Recorded in the page comment.
- **A bare `npx tsc --noEmit -p tsconfig.json` reports exactly one error, and it is 37-17's:**
  `components/dashboard/kitchen/__tests__/kds-sound.test.tsx(200,24)` (`getAutoplayPolicy` is not
  on `Navigator`). `npm run build` and the e2e tsconfig are green; 37-18 adds none. A one-line
  cast fixes it; not this plan's file.
- **docs-metrics.** 37-18's jest files are countable (count-test-blocks: 5/27/2/7/24, equal to the
  executed counts; jest is now 2516 tests in 213 suites against the re-measured 2472/210 at
  4e27e771). The new Playwright spec counts 5 `test()` blocks (10 listed across two projects). The
  37-17 `.each(TABLE.filter(...))` VOID is untouched; regenerate `docs/metrics.json` after it is
  fixed.
- **The pre-rebuild frontend image (7e5a7401) was pruned before it could be read**, so the
  "old image lacks the new strings" direction of the bundle check is VOID (docker run rc 125). The
  in-image positive control (a 37-17 string) and the absent-token negative control stand in for it.

## From 37-22 (customer surfaces read the server's availability)

- **37-21 must keep the storefront's "Opening hours" list honest.** `/shop/[slug]` still renders
  that collapsible list from the free-text `openingHours` (display only; it decides nothing since
  37-22). Saving structured hours (`ShopService.applySchedule`) leaves the old text in
  `opening_hours`, so once 37-21's editor saves a STRUCTURED schedule the list would show the OLD
  text beside a pill driven by the new hours. 37-21 (or a follow-up) should render the list from
  `openingSchedule` for STRUCTURED and MIGRATED_FROM_TEXT shops, and keep the text for
  NEEDS_REVIEW (whose unreadable days are `[]` in the schedule but OPEN on the server, so a
  schedule-driven list would print "Closed" for a day customers can order on). Not measured on a
  saved shop: the editor does not exist yet.
- **37-21 copy under the D-25 ruling (nothing new learned beyond 37-20's note).** The customer side
  now says exactly what the server decides, so a NEEDS_REVIEW shop's unreadable day reads "Open now"
  on the storefront and in the cards. The vendor-facing line 37-21 needs is still the owner's
  wording, along the lines of "customers can still order on this day until you save your hours".
  JSON-LD does NOT publish those days (37-22 decision: only declared windows), so search engines see
  the shop as closed on an unreadable day while customers can order. That under-claim is deliberate
  (no hours the vendor never stated); it ends when the vendor saves hours.
- **Docs metrics.** 37-22 adds jest `shop-availability-notice.test.tsx` (14) and
  `shop-closed.test.tsx` (8), changes `storefront-server` (+2), `structured-data` (+8),
  `opening-hours` (8 -> 4), `shop-card` (+5) and `shop-discovery-client` (+1) (jest 2550/215
  against the re-measured 2516/213), one JUnit case in `ShopAvailabilityPolicyTest` (26) and a new
  3-method IT `DemoDataSeederHoursIntegrationTest`. `count-test-blocks.mjs --per-file` counts every
  changed jest file equal to its executed count (116 = 116 over the 8 files). The 37-17
  `.each(TABLE.filter(...))` VOID is untouched; regenerate `docs/metrics.json` after it is fixed.

## From 37-21 (kitchen pause, structured hours editor, hours notices)

- **The server still regenerates a shop's slug on every update that carries none.** `ShopService.updateShop`
  sets `generateSlug(name)` (name + a random 8-character suffix) whenever `request.getSlug()` is blank; its
  own comment says the regeneration is meant for a name change, but the code does not check one. The shop
  dialog never sent a slug, so every dialog save moved the shop to a new `/shop/{slug}`. 37-21 fixed the
  DIALOG (it now sends the current slug unless the name changed; RED a834abcb, GREEN d5e1490f, live: the
  hours save kept `mama-ades-kitchen`). Any other client that updates a shop without a slug (REST, an
  agent) still renames it. Not fixed server-side: no Java in this plan, and whether a rename should move
  the storefront address at all is an owner question (links and SEO break on every rename). Found by
  reading the source before the live run; the pre-fix live fail direction was deliberately not run.
- **A 403 on pause also stops the kitchen board.** The pause endpoint and the board's own read are both
  gated at STAFF on the shop, so a `shop-access-denied` 403 from a pause means the grant is gone, and
  37-18's D-08 listener shows the "access ended" overlay. The K3 refusal copy ("You can't pause orders
  for this shop. Ask a manager.") renders under the control, behind the overlay. Proven in jest only
  (WINDOWS #37); not provoked live.
- **"Closes after midnight (next day)" under the all-day windows.** Every seeded demo shop holds
  00:00-00:00 on all seven days, and the editor shows the overnight hint under each (close <= open is
  overnight, the server's rule; the storefront list words the same window "Open 24 hours"). Correct, but
  seven identical hints read oddly. A design look for the 37-23 UAT, not a defect.
- **The kitchen reads the shop once a minute.** The pause state re-reads `GET /api/v1/shops/{id}` on the
  board's existing 60 s poll (KITCHEN_POLL_INTERVAL_MS), after each pause or resume, and at a timed
  pause's stated end. Measured live: a second device showed a pause, and cleared a resume, 60 s after the
  first device acted. One extra request per board per minute against the tenant's 100/min budget.
- **Partially read NEEDS_REVIEW days get no quote.** The editor quotes the original text only for days
  the server keeps open all day under the D-25 ruling (schedule empty, text present and not "closed").
  A day where V80 read the first window and ignored the rest ("09:00-17:00 (lunch 2-3)") opens with that
  window and no quote. Not measured on real data: the dev DB has no NEEDS_REVIEW shop since 37-22.
- **Docs metrics.** 37-21 adds 5 jest files: `pause-orders` 16, `opening-schedule-editor` 23,
  `shop-hours-list` 8, `hours-notice-card` 7, `hours-notice-home` 3 (`count-test-blocks.mjs --per-file`
  57 = 57 executed). jest 2607 tests / 220 suites against the re-measured 2550/215. No Java change. The
  37-17 `.each(TABLE.filter(...))` VOID is untouched; regenerate `docs/metrics.json` after it is fixed.
- **RWO-022 stays open.** 37-23 (the UAT) also declares it; `requirements.ready-ids` reports it blocked
  for 37-21. Every implementation truth across 37-19..37-22 and 37-21 is met; the human-verified rows
  (WINDOWS #35, #37) are 37-23's.

## From quick 261010-ppb (owner rulings 2026-10-10)

- **Option C, per-shop customer narrowing: DEFERRED to Phase 39 (owner ruling 2026-10-10).** Option E
  (write floors) shipped here; which customers a granted user SEES is still tenant-wide. The shape the
  ruling gives it:
  - the next free Flyway version at Phase 39 plan time (the ruling named V81, which 37-27 reserves for
    `V81__order_abuse_bounds`; 37-45 reserves V82 and 37-48 V83) adds nullable
    `customers.origin_shop_id` REFERENCES `shops(id)`, plus the `customers_aud` mirror in the SAME
    migration (Customer is `@Audited`);
  - a tenant-looped, GUC-pinned backfill from orders (`idx_orders_customer` exists), because a bare
    UPDATE under FORCE RLS matches zero rows (the V25/V44/V57/V78 trap);
  - the non-Group-admin predicate `origin_shop_id IN granted OR EXISTS an order of that customer in a
    granted shop`, applied to list, by-id, PUT, DELETE AND the two tenant-wide lookups in
    `OrderService.createOrder` (`OrderService.java:170`) and `updateOrder` (`:348`), which today call
    `customerRepository.findById` and are the bypass option C must close;
  - a refused id answers 404, not 403 (no existence oracle);
  - a NOSUPERUSER Testcontainers proof.
  Phase 39 in the ROADMAP is "Platform runtime upgrades" (39-A..39-E), a different subject: the owner
  decides at `/gsd-plan-phase 39` whether this becomes a 39-F or a phase of its own.
- **Old-slug 301 aliases: DEFERRED to Phase 39 (owner ruling 2026-10-10).** A `shop_slug_alias` table
  (RLS-exempt by addition, like V62/V75, because the anonymous storefront resolves it before any tenant
  is known) so printed links and QR codes survive a DELIBERATE slug change. The slug-keep fix itself
  (an update without a slug keeps it) stays in 37-48 with items (a)-(e).
- **A pre-filled Monday can block a save (consequence of the D-25 pre-fill, recorded, not special-cased).**
  A NEEDS_REVIEW shop with an overnight Sunday window (e.g. 18:00-02:00) and an unreadable Monday:
  the pre-filled 00:00-00:00 Monday overlaps Sunday's spill, so the editor (and the server, which
  applies the same rule) refuses the week until Monday's real times are set. That is what the amber
  line asks for. Pinned by a jest case in `opening-schedule-editor.test.tsx`.
- **Refresh-token rotation: DEFERRED (orchestrator revision 2026-10-10, recorded for the owner).** The
  D-14 amendment changes only the vendor realm's SSO max (14 h). `revokeRefreshToken` stays false.
  Why: with rotation on (`revokeRefreshToken` true, `refreshTokenMaxReuse` 0) a refresh token is
  single-use, and the api-client calls `getSession()` on every request, so parallel requests in a
  token's last minute redeem one refresh token several times and sign the user out (the #465 notes in
  docs/CHANGELOG.md and lib/customer-token-refresh.ts). A per-process single-flight cannot protect the
  3 k8s frontend replicas, nor `middleware.ts`, which wraps auth and runs it in another runtime, and a
  single-replica compose proof could not fail on that gap. Turning it on needs a shared guard (a
  Redis-backed single-flight keyed by refresh token, or sticky sessions proven) or a Keycloak
  `refreshTokenMaxReuse` policy, and then its own ruling. `frontend/auth.ts:100` still says
  "max lifespan 2h"; that comment is stale with the 14 h change and is left for the rotation work.
- **Customer DELETE under the strict-OFF override JIT-provisions an ungranted caller.** Delete now
  calls `requireGroupAdmin()`, which calls `onRequest()` first, exactly as shop delete does today:
  with strict scoping OFF an ungranted caller is JIT-provisioned as the day-one Group admin and the
  delete proceeds. Shared posture, unchanged here; `requireAnyRole` (create/update) writes no grant.
- **STAFF still sees the customer write controls.** The customers page shows Add / Edit / Delete to
  every granted user; a STAFF user's write now answers 403 shop-access-denied, which the page reports
  as its destructive toast (as on the products page). Role-aware hiding was not in the ruling.
- **Docs metrics.** 261010-ppb adds 1 jest file (`kitchen-route-calls` 2) and 7 blocks to existing
  files (editor 23 -> 26, card 7 -> 8, pause 16 -> 17, board-stopped 27 -> 29): jest 2616 tests / 221
  suites against the re-measured 2607/220. Java: `CustomerAccessGateIntegrationTest` 12 -> 22. The
  37-17 `.each(TABLE.filter(...))` VOID is untouched; regenerate `docs/metrics.json` after it is fixed.
- **`scripts/check-doc-citations.sh` reports a VOID on `docs/ops/terminal-states.yaml` on this machine (37-24, pre-existing, out of scope).**
  status: open
  **What:** the gate's YAML-citation extractor runs a bare `python3 -c` (around line 187); the
  machine's python shim refuses that spelling outside a declared env, so the extractor prints the
  shim's refusal and the gate records `citations=0` for that file as VOID. The gate still exits 0
  when the other 29 citations verify (37-24 measured rc=0 with the VOID present), so it is not a
  gate break here, and CI has no shim. The fix belongs to the gate (spell `/usr/bin/python3 -c`,
  the sanctioned stdlib one-liner form, or declare an env), not to this plan.
- **The local trusted-proxy range is the minikube default, not a measurement (37-24).**
  status: open
  **What:** `k8s/local/configmap-patch.yaml` declares `security.trusted-proxies: "10.244.0.0/16"`.
  The `jtoye` minikube profile had no container on 2026-10-11 (`minikube profile list`: "No such
  container: jtoye"; `kubectl --context jtoye`: context does not exist), so the ingress pod CIDR
  could not be read. When the cluster is next up, confirm with
  `kubectl --context jtoye get pods -n ingress-nginx -o wide` and narrow the range if it differs.
- **`edge-go/README.md` describes a rate limiter that never shipped (37-25, pre-existing, out of scope).**
  status: open
  **What:** the README's "Rate Limiting" and "Troubleshooting" sections say "100 requests/second",
  list `uber-go/ratelimit` as a dependency and `MicahParks/keyfunc`, and document `JWKS_URL` /
  `JWT_ISSUER` and `POST /api/sync/batch` — none of which is the code (hand-rolled buckets,
  `KC_ISSUER_URI` / `JWT_EXPECTED_ISSUER`, `/api/v1/sync/batch`). 37-25 made its "Per-tenant rate
  limiting support" line true and added the `RATE_LIMIT_*` keys to `edge-go/.env.example`, but the
  README was not in the plan's file list and is not touched here. A rewrite belongs with the
  sub-theme PR's docs pass; `docs/audit/**` and `docs/analysis/**` descriptions of the old single
  bucket are dated records and stay as written.
- **Docs metrics drift from 37-25 (owed to the sub-theme PR pre-step, as for every Phase 37 plan).**
  status: open
  **What:** `scripts/docs-freshness.sh` rc 1 after 37-25: `go_test_funcs` 98 -> 103 and
  `go_test_files` 13 -> 14 are this plan's (`ratelimit_tenant_test.go` x4, `openapi_test.go` +1);
  `java_test_methods` 2534 -> 2558 and `java_test_files` 371 -> 372 were already drifted before it
  (37-24). Regenerate `docs/metrics.json` with `--write` and re-point the prose counts in CLAUDE.md,
  AGENTS.md and README.md in the 37-D PR's docs step, not per plan.
- **Docs metrics drift from 37-26 (owed to the sub-theme PR pre-step, as for every Phase 37 plan).**
  status: open
  **What:** `scripts/docs-freshness.sh` rc 1 after 37-26 (`total_logical_invocations` 5526 at the
  measurement): this plan adds 3 Java test files (`OrderLimitsTest` 13, `OrderLimitsIntegrationTest` 8,
  `ReviewCrossShopIntegrationTest` 7) and 1 method to `ReviewServiceTest`, on top of the drift 37-24 and
  37-25 recorded. Regenerate `docs/metrics.json` with `--write` and re-point the prose counts in CLAUDE.md,
  AGENTS.md and README.md in the 37-D PR's docs step, not per plan.
- **The compose runtime does not yet carry 37-26 (the 37-D gate rebuilds).**
  status: open
  **What:** the stack still runs the images built from `6a825865`'s tree. Measured, not assumed:
  `scripts/check-openapi-snapshot-fresh.sh` is FAIL (rc 1) on A-1 because the committed snapshot now
  declares `PublicShopDto.maxLineQuantity` and the running core-java cannot emit it; the two typed
  refusals (`order-limit-exceeded`, `review-order-shop-mismatch`) and the four `ORDER_LIMIT_*` env entries
  likewise reach no running container until the rebuild. Live probes for the gate: a storefront line of 51
  -> 422 `order-limit-exceeded` with `field`/`value`/`limit`; a dashboard order of 1,000,000 -> the same 422;
  `GET /api/v1/public/shops/{slug}` -> `maxLineQuantity: 50`; a review of shop B citing a shop A order ->
  400 `review-order-shop-mismatch`.
- **The MCP `create_order` schema still says only `quantity >= 1` (37-26, recorded, out of scope).**
  status: open
  **What:** `mcp-server/src/tools/create-order.ts:86` validates `z.number().int().min(1)` with no maximum.
  The tool calls `POST /api/v1/orders`, which now refuses an oversized order with the typed 422, so the
  rule holds on the server (theme 6); the client schema could mirror the limit so an agent is told before
  the round trip. A `.max()` there would be a second copy of a config value, which is why it is recorded
  rather than added here.
- **Docs metrics drift from 37-27 (owed to the sub-theme PR pre-step, as for every Phase 37 plan).**
  status: open
  **What:** `scripts/docs-freshness.sh` rc 1 after 37-27 (`java_test_methods` 2534 -> 2613, `java_test_files`
  371 -> 377, `total_logical_invocations` 5560 at the measurement): this plan adds `CashOrderCapIntegrationTest`
  (12) and `CashOrderCapsTest` (20) and two methods to `PublicStorefrontServiceTest`, on top of the 37-24..37-26
  drift. `schema_version` 80 -> 81 WAS moved by hand in `docs/metrics.json` (with CLAUDE.md, AGENTS.md and
  README.md; `check-doc-metrics` PASS 37/37), because the dispatch requires the schema line to move with the
  migration; the counts are regenerated with `--write` in the 37-D PR's docs step, not per plan.
- **The compose runtime does not yet carry 37-27 (the 37-D gate rebuilds).**
  status: open
  **What:** the stack still runs the images built from `6a825865`'s tree. Measured, not assumed:
  `scripts/check-openapi-snapshot-fresh.sh` is FAIL (rc 1) on A-1 because the committed snapshot now declares
  `OrderDto.contactVerified` and `OrderDetailDto.contactVerified` (beside 37-26's `maxLineQuantity`) and the
  running core-java cannot emit them; V81, the typed 429 `order-cap-exceeded` and the `X-Customer-Token` read on
  the guest-order POST reach no running container until the rebuild. Live probes for the gate: three cash orders
  for one email at one shop then a fourth spelled in another case -> 429 `order-cap-exceeded` axis `EMAIL`, no
  `Retry-After`; three with `07700 ...` then `+44 7700 ...` -> axis `PHONE`; completing one -> the same
  Idempotency-Key is 201; a signed-in customer's storefront order -> `GET /api/v1/orders/{id}/detail` carries
  `contactVerified: true`, a guest's `false`, a dashboard order none; `SELECT version FROM flyway_schema_history
  ORDER BY installed_rank DESC LIMIT 1` -> 81 inside the running Postgres.
- **`orders.client_ip_digest` is reserved by V81 and written by nobody until 37-28 (recorded so the erasure path is not forgotten).**
  status: resolved
  **Resolved by 37-28:** the storefront writes the digest (a54b679a); `GdprService.anonymiseSubjectInTenant` nulls it, `OrderService` nulls it at COMPLETED/CANCELLED/REFUNDED and `ClientIpDigestRetentionSweep` nulls it after 72 h per tenant (65ba6565), each proven by `ClientIpDigestRetentionIntegrationTest` under NOSUPERUSER.
  **What:** V81's COMMENT and CLAUDE.md say the digest is "nulled by the retention purge and by Article 17
  erasure". Today the column is NULL on every row, so no erasure gap exists; 37-28, which writes it, must in
  the SAME plan null it in `GdprService.anonymiseSubjectInTenant` (the live-row anonymisation; the two
  `orders_aud` scrubs need nothing, there is no audit mirror by design) and declare its retention window with a
  purge. `CashOrderCaps.perIp()` is bound and refused below 1 from day one but counts nothing until then.
- **The `jtoye.storefront.cash-caps.*` keys are declared in `application.yml` only (no env placeholders, no compose / `.env.example` / k8s wiring).**
  status: resolved
  **Resolved by 37-28:** the three caps are `${CASH_CAP_PER_EMAIL:3}` / `_PHONE` / `_IP` in `application.yml`, declared in `k8s/base/configmap.yaml` and read by the deployment (7b811046); compose keeps the defaults by design (see the 37-28 entry below).
  **What:** deliberate for this plan (its file list names only `application.yml`, and the probe note says compose
  keeps the defaults; Spring relaxed binding still lets an operator override them as
  `JTOYE_STOREFRONT_CASHCAPS_PEREMAIL` etc.), and unlike 37-26's `ORDER_LIMIT_*` they are therefore not in
  `k8s/base/configmap.yaml`. If a per-overlay tuning is wanted, 37-28 — which adds the HMAC key for the IP axis
  and must wire THAT through compose, `.env.example` and k8s with the env-contract gate — is the plan to carry all
  three caps across in one change.
- **Docs metrics drift from 37-28 (owed to the sub-theme PR pre-step, as for every Phase 37 plan).**
  status: open
  **What:** `scripts/docs-freshness.sh` rc 1 after 37-28 (`java_test_methods` 2534 -> 2626, `java_test_files`
  371 -> 380, `total_logical_invocations` 5573 at the measurement): this plan adds `ClientIpDigesterTest` (12,
  one of them a 5-value parameterised method), `ClientIpCapIntegrationTest` (3) and
  `ClientIpDigestRetentionIntegrationTest` (3), on top of the 37-24..37-27 drift. `check-doc-metrics` stays
  PASS 37/37 (no schema or count moved by hand). Regenerate `docs/metrics.json` with `--write` and re-point the
  prose counts in CLAUDE.md, AGENTS.md and README.md in the 37-D PR's docs step, not per plan.
- **The compose runtime does not yet carry 37-28 (the 37-D gate rebuilds).**
  status: open
  **What:** the stack still runs the images built from `6a825865`'s tree. Measured, not assumed:
  `scripts/check-openapi-snapshot-fresh.sh` is FAIL (rc 1) on A-1 (the 37-26 and 37-27 fields the running
  service cannot emit); the per-IP axis, the digest write, the terminal-state null, the sweep and the erasure
  null reach no running container until the rebuild. The rebuild REQUIRES `CLIENT_IP_HMAC_KEY` in the local
  `.env` (added, 64 hex, untracked; `scripts/verify-env.sh` PASS 20/20) or compose refuses to interpolate
  (`docker compose config` rc 1 naming the key, measured). Live probes for the gate: six cash orders from one
  connection with a fresh email and phone each -> five `201` then `429 order-cap-exceeded` axis `IP`, no
  `Retry-After`; `SELECT client_ip_digest FROM orders WHERE ...` -> 64 hex on the five, NULL after
  `/complete`; no column of those rows contains the probe's address.
- **The retention register has no row for the client-IP digest yet (37-30 owns the customer-facing copy, per the plan's probe note).**
  status: open
  **What:** `jtoye.storefront.client-ip-digest.retention-hours: 72` is enforced (terminal-state null,
  `ClientIpDigestRetentionSweep` daily per tenant, erasure null) but `docs/retention-manifest.json` has no R-16
  row, so `scripts/check-retention-enforcement.sh` (PASS 14 rows) does not yet gate the value. 37-30 adds the row
  (`enforced_by` = `application.yml` key `retention-hours`, consumer `ClientIpDigestRetentionSweep.java`
  must_contain `jtoye.storefront.client-ip-digest.retention-hours`, unit hours, 72) with the customer-facing
  wording; until then the number is declared in config and the k8s configmap only.
- **The sweep's hour is a config literal, not an env tunable (37-28, recorded).**
  status: open
  **What:** `jtoye.storefront.client-ip-digest.sweep-cron: "0 20 3 * * *"` (03:20 server time daily) has no
  `${ENV:default}` placeholder, deliberately: the window (`retention-hours`) is the tunable and is wired through
  the configmap; the hour is incidental and an env placeholder would need a configmap key the env-contract gate
  then demands. If an operator ever needs to move it, add `CLIENT_IP_DIGEST_SWEEP_CRON` and its configmap key
  together.
- **The cash-cap and retention env names are k8s-only; compose keeps the application defaults (37-28, recorded).**
  status: open
  **What:** `CASH_CAP_PER_EMAIL` / `_PHONE` / `_IP` and `CLIENT_IP_DIGEST_RETENTION_HOURS` are declared in
  `k8s/base/configmap.yaml` and read by the deployment; `docker-compose.full-stack.yml` and `.env.example` do
  not carry them (RESEARCH Pitfall 9: an E2E spec that trips a cap uses unique contact details, never a raised
  default). This closes the 37-27 entry above about the caps living in `application.yml` only.
- **Docs metrics drift from 37-29 (owed to the sub-theme PR pre-step, as for every Phase 37 plan).**
  status: open
  **What:** `scripts/docs-freshness.sh` rc 1 after 37-29 (`java_test_methods` 2534 -> 2634, `java_test_files`
  371 -> 381, `total_logical_invocations` 5581 at the measurement): this plan adds `OrderBulkCancelIntegrationTest`
  (8 methods, 1 file) on top of the 37-24..37-28 drift. `check-doc-metrics` stays PASS 37/37 (no schema or count
  moved by hand; V81 was already recorded by 37-27). Regenerate `docs/metrics.json` with `--write` and re-point
  the prose counts in CLAUDE.md, AGENTS.md and README.md in the 37-D PR's docs step, not per plan.
- **The compose runtime does not yet carry 37-29 (the 37-D gate rebuilds).**
  status: open
  **What:** the stack still runs the images built from `6a825865`'s tree. Measured, not assumed:
  `scripts/check-openapi-snapshot-fresh.sh` is FAIL (rc 1) on A-1 — the committed snapshot now declares
  `/api/v1/orders/bulk-cancel`, `BulkCancelRequest`, `BulkCancelResult` (+ `Entry`) and `CancelOrderRequest`
  beside the 37-26..37-28 fields, and the running core-java emits none of them. Live probes for the gate: three
  storefront cash orders at one shop, then `POST /api/v1/orders/bulk-cancel {orderIds:[the three + one random],
  reasonCode:"SUSPECTED_FAKE"}` as a shop STAFF user -> 200 with REJECTED x3 and NOT_FOUND; the same body again
  -> ALREADY_CANCELLED x3; `SELECT status, cancel_reason_code FROM orders WHERE id IN (...)` -> CANCELLED /
  SUSPECTED_FAKE; Mailhog shows one "Cancelled" email per order whose source contains neither `SUSPECTED_FAKE`
  nor `fake`; `GET /api/v1/public/orders/{number}?email=` -> `"status":"CANCELLED"` and no reason;
  `POST /api/v1/orders/bulk-cancel` with 101 ids -> 400 `bulk-cancel-too-large` (`count` 101, `limit` 100);
  `POST /api/v1/orders/{id}/cancel` with `{"reasonCode":"OTHER"}` -> the row reads OTHER, without a body NULL.
- **`jtoye.orders.bulk-cancel.max-ids` is declared in `application.yml` only (no env placeholder, no compose / `.env.example` / k8s wiring; 37-29, recorded).**
  status: open
  **What:** deliberate, the 37-27 cash-caps shape: the plan's file list names only `application.yml`, the
  default (100) exceeds any kitchen board page, and an env placeholder would make `k8s/scripts/check-env-contract.sh`
  demand a configmap key. Spring relaxed binding still honours `JTOYE_ORDERS_BULKCANCEL_MAXIDS` if an operator must
  move it. If a per-overlay value is ever wanted, add `BULK_CANCEL_MAX_IDS` with its configmap key together, as
  37-28 did for the caps.
- **A DRAFT order in a bulk-cancel list reads `ALREADY_ACCEPTED` (37-29, recorded for 37-30's copy).**
  status: open
  **What:** the four outcomes are the plan's (D-18, SC-4): `REJECTED | ALREADY_ACCEPTED | ALREADY_CANCELLED |
  NOT_FOUND`. A DRAFT is before PENDING, not after it, so neither "accepted" nor "cancelled" names it exactly;
  it is reported `ALREADY_ACCEPTED` ("not waiting in the New-orders lane; left as it was") rather than a fifth
  value the contract does not have. Unreachable from the UI-SPEC D1 surfaces, which offer the checkbox on
  PENDING rows only; an API caller who lists a draft gets the order left untouched, which is the safe outcome.
  If 37-30's per-order result copy needs to name it, widen the enum there (the OpenAPI snapshot and this IT
  move with it).
- **A per-order failure inside a bulk cancel ends the batch with the earlier orders committed (37-29, recorded).**
  status: open
  **What:** each id runs in its own REQUIRES_NEW transaction, so an unexpected exception on one order (an
  optimistic-lock collision with a kitchen that accepts it at the same instant, a database error) propagates
  as that error's typed response after the earlier ids have committed; nothing is rolled back and nothing is
  reported for the later ids. The recovery is the contract itself: re-send the same request, and every order
  already rejected reads `ALREADY_CANCELLED`. A "partial batch" shape (a FAILED outcome per id) was not added
  because the plan's outcome set is closed; record here in case the kitchen E2E (37-31) ever observes the race.
- **Docs metrics drift from 37-30 (owed to the sub-theme PR pre-step, as for every Phase 37 plan).**
  status: open
  **What:** `scripts/docs-freshness.sh` rc 1 after 37-30 (`jest_blocks` 2627 -> 2672, `jest_files` 222 -> 229,
  `total_logical_invocations` 5626 at the measurement; the Java counts are 37-24..37-29's): this plan adds seven
  jest suites (reject-orders-dialog, bulk-reject, kds-bulk-reject, unverified-contact-badge, abuse-refusals,
  stepper-limit, retention-r16) and extends three. `check-doc-metrics` stays PASS 37/37 (no count moved by hand).
  Regenerate `docs/metrics.json` with `--write` and re-point the prose counts in CLAUDE.md, AGENTS.md and
  README.md in the 37-D PR's docs step, not per plan.
- **The compose runtime does not yet carry 37-30 (the 37-D gate rebuilds).**
  status: open
  **What:** the stack still runs the images built from `6a825865`'s tree (core-java and frontend Up 7 h at
  measurement). `scripts/check-openapi-snapshot-fresh.sh` is FAIL (rc 1) on A-1 as recorded by 37-27..37-29.
  Live probes for the gate: three storefront cash orders at one shop as a guest -> on `/dashboard/orders` each
  PENDING row has a checkbox, the header checkbox selects them, the bar reads "3 selected", "Reject 3 orders" ->
  the dialog requires a reason -> the summary reads "3 rejected." and the rows are CANCELLED after "Close
  summary"; the same three on `/dashboard/kitchen` show "Unverified contact" beside the name (a vendor-created
  order shows none); the fourth guest cash order with the same email is refused in the checkout error slot with
  "You already have the most open cash orders this shop allows for one customer…" (", or pay by card" iff the
  shop takes cards) and the network log shows ONE POST; a line above `jtoye.order-limits.max-line-quantity`
  cannot be reached (the "+" is disabled with "Max {n} per order"); `/legal/retention` lists R-16 "Up to 72
  hours…"; a signed-in customer's order shows no badge and the request went to `/api/customer-orders?shop=`.
- **Signed-in orders through the proxy and the per-IP cap (37-30, found; a server or operator decision, not the frontend's).**
  status: open
  **What:** the access token is an HttpOnly cookie, so a signed-in customer's order is posted through
  `POST /api/customer-orders?shop=` (the `/orders/mine` shape), and core then sees the FRONTEND CONTAINER as
  the socket peer. `ClientIpResolver` (37-24, D-19) reads `X-Forwarded-For` only from a peer declared in
  `jtoye.security.trusted-proxies` (`TRUSTED_PROXY_CIDRS`, empty by default), and a Next 16 route handler cannot
  read its own socket peer, so unless an ingress sets the header AND the frontend's range is declared trusted,
  every signed-in customer's cash order at a shop is digested under ONE address (the frontend's) and the per-IP
  cap (5 open) can refuse legitimate signed-in customers. The route forwards the chain it receives, which is
  enough once the two conditions hold. **Recommendation (research-then-recommend ruling 2026-10-10):** the
  principled fix is in `CashOrderCaps` — do not count or check the IP axis for an order whose contact is
  VERIFIED (the IP axis exists for anonymous floods; a verified contact is already bounded by the email axis),
  which also removes the dependency on proxy topology; the operator alternative is declaring the frontend's
  range in `TRUSTED_PROXY_CIDRS` per environment behind an ingress that sets `X-Forwarded-For`. Either is a
  one-line decision for the owner before the first shared environment takes signed-in cash orders; 37-31's IP-cap
  spec must place its six orders as a GUEST (direct to core) or it measures the frontend's address.
- **The reject dialog's single-order path uses /bulk-cancel, not the single cancel's optional body (37-30, recorded decision).**
  status: resolved
  **What:** the dispatch named "the optional {reasonCode} body on the single cancel"; the dialog sends every
  reject, one or many, as a batch so an order accepted on another screen a second earlier is reported
  ALREADY_ACCEPTED instead of being cancelled (the single cancel accepts a CONFIRMED order). The single cancel's
  body stays an API-caller feature (37-29's IT covers it). Resolved by decision; here so a reader of 37-29's
  "Next Phase Readiness" is not left looking for the UI call site.
- **UI-SPEC D1 "empty" and the selection-mode edges were planner assumptions, now implemented one way (37-30, recorded).**
  status: open
  **What:** no PENDING row on the Orders page -> no selection column and no header checkbox; the Orders bar
  renders only while >= 1 row is selected; the kitchen bar renders throughout selection mode ("0 selected",
  Reject disabled) because "Stop selecting" lives on it; selection mode ends when a batch's summary closes; the
  per-card single reject leaves the card on "Close summary". If the owner wants the kitchen bar hidden at 0 or
  selection mode kept after a batch, each is a one-line change with a test to flip. Also: the basket drawer's
  stepper limit is null until the shop page has been read in this provider (a deep link to /checkout) — the
  server's 422 is the gate, the spec's declared other branch.
- **The review-form notice (UI-SPEC C4, 37-36 truth 3) has no form to sit on (37-36, open).**
  status: open
  **What:** the plan and UI-SPEC § C4 ask for "Your review will show your first name and the first letter of
  your last name, like "{First} {L}."." as a 14px slate-600 notice above the review form's Submit. The frontend
  has NO review form: `rg -uu` over `frontend/app`, `components`, `lib` and `hooks` finds no caller of
  `POST /public/shops/{slug}/reviews` (only the GET in `storefront-server.ts` and the shop client), and the
  UXT-044 repro itself reads "Create a review for a completed order" through the API. Reviews are created only
  by API callers today, so no customer sees a form, and none can be annotated. Building one (completed-order
  binding by order number + email, food/delivery ratings, comment, photos, the UXT-005 cross-shop refusal copy,
  its error states) is a new customer surface with no UI-SPEC contract, so 37-36 did not invent it. What
  shipped: the public name is minimised server-side (37-35) and rendered verbatim with the "Customer" fallback
  (37-36), so the data-protection half of UXT-044 holds with or without a form. **Recommendation:** when a
  review form is built, the notice goes above its Submit with the copy above, `{First} {L}.` derived from the
  order's name by the same rule as `ReviewerDisplayName.of`; 37-38's human check "read the review form notice"
  cannot be performed and should be recorded N/A with this reference.
- **The storefront header overflows by 4px at 360px with root font-size 130% (found in 37-37, open).**
  status: open
  **What:** measuring the C3 backstop truth ("the carried-over breakdown rows fit at 360px with root 130%") on a
  local stack-free build, every breakdown row fit exactly (scrollWidth 275 = clientWidth 275 for Subtotal,
  Delivery and Total, with and without the just-placed marker), but the DOCUMENT is 364px wide on a 360px
  viewport: the overflowing elements are the shared storefront header (`nav.flex items-center gap-3 sm:gap-4
  text-sm`, "Shops / For operators / Track order / 0 items", and its `sm:hidden` 44px menu button), plus the
  fixed toaster `ol` and the cookie-notice `section`, which take their width from it. Nothing on the order page
  37-37 changed causes it; it is the header every storefront route shares. **Owner:** 37-52 (UXT-080 F6: "the
  shop header h1 wrap ... no horizontal overflow") is the plan that holds the header to 360px/130%; its
  instrument should include an order page. Not fixed in 37-37 (scope boundary: a shared component another plan
  owns).

## From 37-38 (the 37-C gate)

- **abuse-bounds' mobile bulk-reject arm is flaky on a Radix element that is "not stable" (found in 37-38, open).**
  status: open
  **What:** `e2e/abuse-bounds.spec.ts:205` ("three guest cash orders are rejected in one action with a per-order
  summary"), mobile project only, timed out at 60 s on Playwright's stability check in 3 of 7 mobile runs at the 37-C gate (after the spec sent a basket expectation):
  twice on `getByRole('option', { name: 'Looks like a fake or test order' })` (the reason Select's option) and once on
  `getByRole('button', { name: 'Reject 3 orders' }).last()`, each "element is not stable" while it animated; the other
  runs passed in 4–5 s, and desktop never failed. The orders the failed runs placed were cancelled by the spec's
  `afterEach` (open count unchanged). Nothing 37-C changed touches the dialog (37-30's `RejectOrdersDialog`).
  **Suggested owner:** whichever plan next edits the dialog or the spec — wait for the Select content / dialog to
  finish its open animation (e.g. assert `data-state="open"` then a stable bounding box) rather than raising the
  timeout. Not fixed at the gate (scope boundary: a 37-D spec, no 37-C cause).

## From 37-40 (shop-scoped sync, createOrder refuses shop-less products)

- **The dashboard's new-order form offers every tenant product, not only the selected shop's (found in 37-40, open).**
  status: open
  **What:** `frontend/app/dashboard/orders/page.tsx` fills the line-item Select from `fetchAllProducts` (the whole
  tenant catalogue) whatever shop is chosen in the form. Since PR #726 (M2) a product of ANOTHER shop is refused
  by `OrderService.createOrder` with `404 Product not found`, and since 37-40 a shop-less (shop_id NULL) product is
  refused the same way (RWO-017 / roadmap SC-5). So a vendor can still pick an item the server will refuse, and is
  told only "not found". No product was ever correct to order there; the form just never filtered. **Suggested
  owner:** the 37-E frontend plan or 37.x: narrow the Select to products whose `shopId` equals the selected shop
  (`fetchAllProducts` already returns `shopId`), and say in the form why a shop-less product is not listed ("assign it
  a shop first"). Not fixed in 37-40 (scope boundary: no frontend file is in the plan, and the refusal is the plan's
  intended behaviour). The 37-E gate's live run should place a dashboard order to confirm nothing it seeds is shop-less.
