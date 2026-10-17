---
phase: "37"
slug: "real-world-operations-readiness"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
# audit-milestone §5.5 distinguishes NOT-VALIDATED (draft) from PARTIAL (validated + nyquist_compliant: false) (#2117)
status: validated
nyquist_compliant: true
wave_0_complete: true
created: "2026-10-07"
audited: "2026-10-16"
audited_at_head: "eb7636ac"
requirements_total: 35
requirements_covered: 35
requirements_partial: 0
requirements_missing: 0
---

# Phase 37 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `37-RESEARCH.md` § Validation Architecture. tdd_mode is on: every ❌ row is written RED on the pre-fix tree first.
> **Audited 2026-10-15 by `/gsd-validate-phase 37` at `afd2aaf2` (branch `phase-37-ops-readiness`).** The per-requirement
> map below replaces the planning-time map, which was keyed by UXT cluster with every row pending. Every test file and
> title named below was confirmed present with `rg -uu` at that head; SUMMARY prose was not used as evidence of a test.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers (Java); Jest 30 (frontend); Playwright 1.63 + axe-core (E2E); `go test` (edge-go); vitest (mcp-server) |
| **Config file** | `core-java/build.gradle.kts` (`test` excludes tag `testcontainers`; `integrationTest` includes it; outputs in `core-java/build-local/`, NOT `build/`); `frontend/jest.config.js`; `frontend/playwright.config.ts`; `mcp-server/package.json` |
| **Quick run command** | `./gradlew :core-java:test --tests '<FQCN>'` · `./gradlew :core-java:integrationTest --tests '<FQCN>'` · `cd frontend && npx jest <path>` · `cd edge-go && go test ./...` · `cd mcp-server && npm test` |
| **Full suite command** | `./gradlew :core-java:test :core-java:integrationTest && (cd frontend && npx jest && npm run build && npx playwright test) && (cd mcp-server && npm test) && (cd edge-go && go test ./...)` |
| **Type check** | `cd frontend && npm run build` (jest does not type-check) |
| **Estimated runtime** | quick: ~30–120 s per class/dir; full: ~40–60 min |

Gates run per sub-theme PR: `scripts/docs-freshness.sh` (+`--write`), `scripts/check-doc-metrics.sh`, `./gradlew :core-java:updateOpenApiSnapshot` then `scripts/check-openapi-snapshot-fresh.sh`, `k8s/scripts/render-golden.sh` + `check-render-invariants.sh` + `check-env-contract.sh`, `scripts/check-no-create-extension.sh`, `scripts/check-branch-behind-base.sh`, `scripts/check-runtime-freshness.sh` (after a rebuild of all images).

---

## Sampling Rate

- **After every task commit:** the touched class (`--tests`) plus `npx jest <touched dir>` / `go test` / `npx vitest run <file>` as touched
- **After every plan wave:** `./gradlew :core-java:test`, the wave's integration classes, `npx jest`, `npm run build`, `go test ./...` / `npm test` where touched
- **Per sub-theme PR (D-03):** full suite + all gates above + rebuild + `check-runtime-freshness.sh` + Playwright on the rebuilt compose stack
- **Before `/gsd-verify-work`:** Full suite must be green; the 40 goods in `goods-to-preserve.md` re-verified (SC-7)
- **Max feedback latency:** 120 seconds per task-level command

---

## Suite Results Behind the "green" Status (audit 2026-10-15)

| Suite | Where measured | Result |
|-------|----------------|--------|
| core-java unit | re-run by this audit at `afd2aaf2`: `./gradlew :core-java:cleanTest :core-java:test`, rc 0; 210 of 210 XML newer than the run start | 1828 tests, 0 failures, 0 errors, 1 skipped |
| core-java integration (Testcontainers) | NOT re-run by this audit (32 min). Recorded in `evidence/37-53-gate-37F.md` §1.4 at `94f06286` with `cleanIntegrationTest` forcing execution; `git diff --name-only 94f06286 HEAD -- core-java` is empty, so the result applies to this head. **Corrected 2026-10-16:** that diff is NOT empty: the T-37-59 fix (43e908ae, 85b6e190) changed 9 core-java files after 94f06286. The core-java/edge-go/mcp-server tree at this head is byte-identical to the 37-F PR head 60b683ed (`git diff 60b683ed HEAD` empty; control: 94f06286..60b683ed lists the 9 files), where CI's Integration Tests (Testcontainers RLS) passed (runs 38706569802, 38706576834) | 1242 tests, 0 failures, 0 errors, 1 skipped |
| frontend jest | re-run by this audit, rc 0 | 248 suites, 2927 tests passed |
| mcp-server vitest | re-run by this audit, rc 0 | 8 files, 70 tests passed |
| edge-go | re-run by this audit, `go -C edge-go test -count=1 ./...`, rc 0 | 5 packages ok, 0 FAIL |
| Playwright (live stack) | NOT re-run by this audit (needs the rebuilt compose stack). Recorded in `evidence/37-53-gate-37F.md` (84 passed / 0 failed on the 37-F specs; review fix: `a11y-tracking` 16, `a11y-storefront` 12, `public-a11y` 42 passed) | green as recorded |

Six of the seven sub-theme PRs are merged with CI green (37-B #913, 37-A #916, 37-D #917, 37-C #918, 37-E #920, 37-G #923); 37-F is on `phase-37-f-accessibility`, PR not yet opened.

---

## Per-Requirement Verification Map

Status key: ✅ COVERED (test exists, targets the behaviour, runs green) · ⚠️ PARTIAL (test exists but incomplete, or the behaviour is open) · ❌ MISSING.
Test titles are given as `file :: title`. Java paths are under `core-java/src/test/java/uk/jtoye/core/`.

### 37-B — Staff access and finance

| Req | Plans | Behaviour | Tests (file :: title) | Status |
|-----|-------|-----------|-----------------------|--------|
| RWO-003 | 37-02,04..09,15,18 | revoke last grant → own token 403 next call; dashboard "no longer have access"; Staff page "No access" + Grant from the ShopAccessService source | `security/access/StaffManagementIntegrationTest :: grantGivesAccess_thenRevokeProduces403`; `security/access/StaffEffectiveAccessIntegrationTest :: signedInUngrantedUser_isListed_asNone`; `frontend/components/dashboard/__tests__/no-access-page.test.tsx :: never says 'You no longer have access' without having seen access in this session`; `frontend/app/dashboard/__tests__/staff-page.test.tsx :: grants a shop-scoped role and refreshes the list`; gap closure 37-54/55 (audit 2026-10-16): `frontend/app/dashboard/webhooks/__tests__/webhooks-page.test.tsx :: Webhooks page: refused and unknown reads (G-37-12)`, `frontend/app/dashboard/__tests__/staff-page.test.tsx :: Staff-page form controls are readable in both themes (G-37-13)`, `:: Grant picker labels say only what the directory holds (G-37-15)`, `frontend/app/dashboard/__tests__/staff-invites.test.tsx :: Pending invitations follow the card's width, not the viewport (G-37-14)`, `frontend/e2e/staff-webhooks-uat.spec.ts :: UAT gaps G-37-12..G-37-15, live (37-55)` | ✅ |
| RWO-004 | 37-02..09,15 | strict-scoping default true everywhere; ungranted login has no access, no JIT row; OPERATOR/realm/bootstrap admins kept; single-use expiring tenant-scoped invite → exact grant | `security/access/StrictScopingDefaultIntegrationTest :: ungrantedVendor_isRefusedAShopWrite_andNoJitRowIsWritten`, `:: ungrantedVendor_staffMe_isNotAGroupAdmin_andHasNoShops`; `security/access/StrictScopingTighteningIntegrationTest :: strictOn_deHonoursJitGroupAdmins_operatorGrantHonoured`, `:: strictOn_retainsOldestJitAsBootstrap_whenAllGroupAdminsAreJit`; `scripts/check-strict-scoping-default.sh` (gate); `security/access/StaffInviteAcceptIntegrationTest :: newInvitee_fromTheEmailedLink_previewThenAccept_landsWithExactlyTheInvitedGrant`; `security/access/StaffInviteRlsIntegrationTest :: databaseRefusesCrossTenantReadUpdateAndInsert`; `frontend/e2e/staff-invite.spec.ts :: a Group admin invites, the invitee joins with exactly the invited access`; gap closure 37-54/55: `frontend/app/dashboard/__tests__/staff-invites.test.tsx :: (G-37-14)`, `frontend/e2e/staff-webhooks-uat.spec.ts :: G-37-14: pending invitations keep every token on one line and both actions in reach, 390 to 1440` | ✅ |
| RWO-018 | 37-10,12,15 | only DRAFT/PENDING delete; later → 409; GA-only void with reason reverses the sale; nothing dangles | `order/OrderVoidIntegrationTest :: completedOrderWithLedgerRow_deleteIsRefused_andNothingDangles`, `:: draftOrPendingOrder_stillDeletes`, `:: completedOrder_voidedByGroupAdmin_reversesTheSale`, `:: differentReason_isAlreadyVoided409`; `finance/LedgerEntryKindMigrationIntegrationTest :: v78ClassifiesExistingLedgerRowsAcrossTenantsUnderRls`; `frontend/components/dashboard/orders/__tests__/void-order-dialog.test.tsx` | ✅ |
| RWO-026 | 37-13,14,15 | CSV import files rows under the selected shop; "Copy to shop" copies allergen data verbatim under an Idempotency-Key | `product/BulkImportShopDefaultIntegrationTest :: groupAdminImportIntoShopA_showsOnShopAsStorefront`; `product/ProductCopyIntegrationTest :: copyAtoB_carriesEveryAllergenFactVerbatim`, `:: repeatingTheCopy_createsNothing`; `frontend/app/dashboard/products/__tests__/import-copy.test.tsx` | ✅ |
| RWO-027 | 37-11,12,15 | ledger rows carry the shop (tenant-loop backfill); site manager sees own shop's finance/export; tenant view GA only | `finance/FinanceShopScopeIntegrationTest :: managerOfA_seesOwnShopSummary`, `:: managerOfA_isRefusedShopB_andTheTenantView`; `finance/LedgerShopBackfillMigrationIntegrationTest :: v79AttributesExistingLedgerRowsToTheirOrdersShopAcrossTenantsUnderRls`; `frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx` | ✅ |
| RWO-046 | 37-14,15 | product form VAT select (four rates) | `frontend/app/dashboard/products/__tests__/product-vat-rate.test.tsx :: offers exactly the four UI-SPEC options`, `:: an untouched new product sends vatRate STANDARD — the default is stated, not implied` | ✅ |
| RWO-047 | 37-14,15 | VAT line only when seller declares a VAT number | `frontend/app/shop/[slug]/checkout/__tests__/vat-registration.test.tsx :: a seller with no VAT number: no VAT row, no VAT figure, and the B7 note under Total`, `:: a VAT-registered seller keeps the VAT row with vatRateLabel and the figure, and no note` | ✅ |

### 37-A — Kitchen and order operations

| Req | Plans | Behaviour | Tests (file :: title) | Status |
|-----|-------|-----------|-----------------------|--------|
| RWO-011 | 37-16,23 | every board card shows notes + fulfilment type | `frontend/components/dashboard/kitchen/__tests__/kds-card-additions.test.tsx :: shows NOTE FROM CUSTOMER, the note and DELIVERY on a New card and on a grid card`, `:: places the note after the customer name and before the items` | ✅ |
| RWO-020 | 37-16,17,23 | PENDING in a New lane, Accept → CONFIRMED; repeating alert after Enable sound; flashing card; "(N) New orders" title; mute honoured and shown | `order/KitchenStatusesParityTest :: pendingIsAKitchenStatus`; `frontend/components/dashboard/kitchen/__tests__/kds-new-lane.test.tsx :: renders a PENDING order in the 'New orders' region, not in the board grid`, `:: Accept posts /orders/{id}/confirm and, on 200, the card moves from the lane to the grid as Confirmed`; `frontend/app/dashboard/kitchen/__tests__/kitchen-alert.test.tsx :: rings while a PENDING order waits once sound is turned on, and Accept silences it`, `:: puts the New-lane count in the tab title, and drops it when the lane empties`, `:: F4: a persisted kds-muted=true is visible on load (bar + control), never silent`; `kds-sound.test.tsx :: is silent while muted, with an order waiting` | ✅ |
| RWO-021 | 37-17,18,23 | lapse → full-screen "Signed out" + alarm, back to /dashboard/kitchen; silent refresh while SSO lasts; never "Live" when lapsed/muted/unreachable | `frontend/e2e/kitchen-session-lapse.spec.ts :: lapse: overlay, unchanged URL, 'Signed out' pill, STOPPED title; Sign in again returns to the kitchen` (+ INSTRUMENT arm); `frontend/lib/__tests__/api-client-session-lapse.test.ts :: dispatches a cancellable jtoye:session-lapsed event on a 401 the refresh cannot fix`; `kds-board-stopped.test.tsx :: describe useSessionKeepAlive: the board refreshes its token before it expires (D-14)`; `frontend/__tests__/session-callback.test.ts :: describe accessTokenNeedsRefresh (37-18)`; `kds-feed-status.test.tsx :: the not-Live rows are exactly rows 2..9 of the table` | ✅ |
| RWO-022 | 37-19..23 | pause 20/40/until reopened + structured hours enforced server-side; unparseable → CLOSED for new shops; legacy shops always open and told to set hours | `storefront/ShopPauseIntegrationTest :: pause20_refusesGuestOrder_thenResumeAcceptsTheSameKey`, `:: pauseUntilReopened_refusesWithNoReopeningTime_andIsAbsolute`, `:: onlyTwentyFortyOrUntilReopened`; `shop/V80OpeningScheduleBackfillIntegrationTest :: emptyHoursShopsBecomeLegacyAlwaysOpenInEveryTenant`, `:: migrationMatchesTheJavaParser`; `storefront/ShopAvailabilityPolicyTest :: legacyAlwaysOpen`, `:: needsReview_unreadableDayStaysOpen`; `frontend/app/shop/[slug]/checkout/__tests__/shop-closed.test.tsx :: paused: Place order is disabled with '{Shop} isn't taking orders right now'`; `frontend/components/dashboard/__tests__/hours-notice-card.test.tsx :: one LEGACY shop: '1 shop has no opening hours' …` | ✅ |

### 37-D — Abuse resistance

| Req | Plans | Behaviour | Tests (file :: title) | Status |
|-----|-------|-----------|-----------------------|--------|
| RWO-005 | 37-26,31 | review accepted only for an order of the reviewed shop | `review/ReviewCrossShopIntegrationTest :: completedOrderAtShopA_cannotReviewShopB`, `:: completedOrderAtShopA_reviewsShopA` | ✅ |
| RWO-006 | 37-24,28,31 | trusted-proxy CIDR list (default none), right-most untrusted hop; rotating XFF shares one bucket | `security/ClientIpResolverTest :: xffSingleValue_fromAnUntrustedPeer_isIgnored`, `:: xffMultipleHops_fromAnUntrustedPeer_isIgnored`; `security/PublicRateLimitXffSpoofIntegrationTest :: rotatingXffFromAnUntrustedPeerSharesOneBucket_andIs429OnceTheBurstIsSpent` | ✅ |
| RWO-039 | 37-25,31 | edge per-tenant limit after JWT; typed 429 + Retry-After; other tenant unaffected | `edge-go/cmd/edge/ratelimit_tenant_test.go :: TestTenantRateLimit_OneTenantsBurstIsRefusedTypedWhileAnotherTenantPasses`, `:: TestTenantRateLimit_RefillsLazilyAfterRetryAfter`, `:: TestTenantRateLimit_PublicRoutesAreNotTenantLimited` | ✅ |
| RWO-041 | 37-27..31 | caps per email/phone/IP-digest, typed 429; digest nulled by erasure + retention; unverified badge; bulk reject | `storefront/CashOrderCapIntegrationTest :: fourthOpenCashOrderForTheSameEmail_isRefusedTyped_andTheSameKeySucceedsOnceOneCompletes`, `:: fourthOpenCashOrderForTheSamePhone_normalised_isRefusedOnPhone`; `storefront/ClientIpCapIntegrationTest :: sixthOpenCashOrderFromOneConnection_isRefusedOnIp_andOnlyADigestIsStored`; `storefront/ClientIpDigestRetentionIntegrationTest :: erasure_nullsTheDigest`, `:: sweep_nullsDigestsPastRetention_inEveryTenant_underNoSuperuser`; `order/OrderBulkCancelIntegrationTest :: mixedBatch_rejectsThePendingOnes_andReportsTheRest`; `frontend/components/dashboard/kitchen/__tests__/unverified-contact-badge.test.tsx :: contactVerified === false renders 'Unverified contact' …` | ✅ |
| RWO-042 | 37-26,30,31 | per-line ceiling + basket cap in the domain service, every writer | `order/OrderLimitsIntegrationTest :: storefrontLineAboveTheLimit_isRefusedTyped_andTheSameKeyCorrectedSucceeds`, `:: vendorLineAboveTheLimit_isRefusedTyped_andTheLimitItselfIsAccepted`, `:: vendorTooManyLines_isRefusedOnLines`; `order/OrderLimitsTest` | ✅ |

### 37-C — Checkout integrity

| Req | Plans | Behaviour | Tests (file :: title) | Status |
|-----|-------|-----------|-----------------------|--------|
| RWO-007 | 37-32,33,34,38 | submit carries seen prices/total (missing → 422); change → typed 409 listing each change; UI re-confirms | `storefront/GuestOrderBasketRevalidationIntegrationTest :: vendorRepriceBetweenRenderAndSubmit_isRefusedBasketChanged_thenAcceptedUnderTheSameKey`, `:: missingExpectedPrices_isRefused422_andWritesNothing_thenAcceptedUnderTheSameKey`, `:: clientClaimingALowerPrice_isRefused_neverCharged`; `common/idempotency/IdempotencyFingerprintGoldenTest`; `frontend/e2e/basket-revalidation.spec.ts :: is shown as '{Item} · £was → £now', refused until accepted, then placed once at the new total under the same key` (+ INSTRUMENT arm) | ✅ |
| RWO-008 | 37-35,36,38 | no storefront promotion rendered/returned; vendor told on marketing page | `storefront/PublicPromotionsHiddenTest :: publicPromotions_isEmpty_evenWithAnActivePromotion`, `:: publicConfig_activePromotions_isEmpty`; `frontend/app/shop/__tests__/shop-promotions-hidden.test.tsx :: a server-seeded payload that still carries a 20% OFF promotion renders no banner and no chip`; `frontend/app/dashboard/__tests__/marketing-promotions-notice.test.tsx :: shows the info panel, with the UI-SPEC copy, ABOVE the promotions list` | ✅ |
| RWO-029 | 37-37,38 | cash confirmation route: focus, announcement, title, refresh without re-POST, full breakdown | `frontend/app/shop/[slug]/orders/[orderNumber]/__tests__/order-confirmation.test.tsx :: h1 'Order confirmed' takes focus, the status region says 'Order confirmed, {number}', the title is the confirmed variant`, `:: F16: no POST on the first view or on the refresh …`, `:: a cash DELIVERY order: 'Pay on delivery', subtotal, delivery fee, VAT (incl. 20%), total`; `frontend/e2e/cash-checkout-no-stripe.spec.ts` | ✅ |
| RWO-044 | 37-35,36,38 | public reviewer name "First L."; stored value kept | `review/PublicReviewerNameIntegrationTest :: postedReview_namesTheAuthorAsFirstAndInitial`, `:: publicList_namesAuthorsAsFirstAndInitial`; `review/ReviewerDisplayNameTest`; `frontend/app/shop/__tests__/review-display.test.tsx :: renders the server's display name, the single-word name as given, and 'Customer' for no name` | ✅ |

### 37-E — The integrator surface

| Req | Plans | Behaviour | Tests (file :: title) | Status |
|-----|-------|-----------|-----------------------|--------|
| RWO-016 | 37-39,47 | PUT without stock keeps stock + tracking; explicit false turns it off; PATCH cannot touch allergens | `product/ProductPartialUpdateIntegrationTest :: putWithoutStockFieldsKeepsTrackingAndCount`, `:: putTrackInventoryFalseStopsTrackingAndClearsStock`, `:: patchAvailabilityLeavesAllergenDataUnchanged`, `:: patchRefusesAllergenMaskByName`; `frontend/app/dashboard/products/__tests__/product-track-inventory.test.tsx :: unticking 'Track inventory' on a tracked product sends trackInventory false and no quantity` | ✅ |
| RWO-017 | 37-40,41,47 | sync requires shopId, writes through ProductService; orders refuse a shop-less product | `sync/SyncBatchShopRequiredIntegrationTest :: mixedBatchReportsEveryItemAndOnlyTheShopScopedProductLands`, `:: syncedProductHasItsShopAndTheSameSpansAsADashboardCreate`; `order/OrderShopLessProductIntegrationTest :: shopLessProductIsNotFoundOnTheVendorRoute` | ✅ |
| RWO-037 | 37-40,41,47 | per-item CREATED/UPDATED/REJECTED; batch never SUCCESS with a skip; edge passes typed responses | `sync/SyncBatchShopRequiredIntegrationTest :: updateNamingADifferentShopIsRejectedShopMismatchAndChangesNothing`, `:: updateInTheSameShopStoresAvailabilityAndStock`, `:: allItemsWrittenIsSuccess`, `:: noItemWrittenIsRejected`; `edge-go/internal/core/client_test.go :: TestClient_SyncBatch_PassesPerItemResultsThrough`, `:: TestClient_SyncBatch_CoreProblemIsReturnedTyped` | ✅ |
| RWO-035 | 37-44,45,46,47 | issue/rotate/revoke a scoped credential; secret once; SA grant ≤ issuer; who/when; Developers page with scopes, shops, last used | `integration/credential/IntegrationCredentialIntegrationTest :: groupAdmin_createsCredential_secretShownOnce_andTheServiceAccountIsGrantedLikeAPerson`, `:: credentialToken_isLimitedToItsGrant`, `:: groupAdminRole_isRefused`, `:: rotate_returnsTheNewSecretOnce_andStampsWhoAndWhen`, `:: delete_revokesEverywhere_andTheNextCallIsRefused`, `:: lastUsed_isWrittenAtMostOncePerInterval`; `integration/credential/IntegrationCredentialRlsIntegrationTest :: createWorksUnderForceRls`; `tenant/keycloak/KeycloakAdminClientTest :: createClient_postsAServiceAccountClient_withTenantAndAudienceMappers_byContent_andReturnsTheUuidFromLocation`; `frontend/app/dashboard/developers/__tests__/developers-page.test.tsx :: sends the chosen name, scopes, role and shop, then shows the client ID and secret once` | ✅ |
| RWO-036 | 37-42,47 | MCP create_order → PENDING, New lane, same events | `order/OrderCreateSubmitIntegrationTest :: submitTrueLandsPendingOnTheKitchenBoard`, `:: submitTrueStopsAtPendingForTheVendorToAccept`; `mcp-server/src/tools/create-order.test.ts :: D-13 / UXT-036: always sends submit: true, so core creates the order PENDING for the kitchen` | ✅ |
| RWO-038 | 37-43,47 | auto-pause counts exhausted deliveries; paused → held, re-queued on resume; one email per pause | `webhook/WebhookAutoPauseIntegrationTest :: twoFlakyEventsDoNotPauseTheEndpoint`, `:: threeExhaustedDeliveriesInARowPauseTheEndpoint`, `:: aDeliveredEventResetsTheCount`, `:: pauseHoldsQueuedEventsEmailsTheOwnerOnceAndResumeDeliversThem`; `notification/EmailNotificationServiceWebhookPausedTest` | ✅ |
| RWO-040 | 37-42,47 | MCP list_products honours shopId + availableOnly; allergen names with the mask | `mcp-server/src/tools/list-products.test.ts :: UXT-040: forwards shopId and availableOnly through the allow-listed query string`; `product/ProductDtoAllergenNamesTest :: glutenAndMilkAreNamed`; `product/ProductListAvailableOnlyIntegrationTest :: oneShopsMenuOnSaleWithNamedAllergens` | ✅ |

### 37-G — Catalogue and shop admin

| Req | Plans | Behaviour | Tests (file :: title) | Status |
|-----|-------|-----------|-----------------------|--------|
| RWO-023 | 37-48,49,50 | fee/threshold/offers set on the shop form; enforced for every writer via FulfilmentPolicy; "Collection only" shown | `order/FulfilmentOfferedIntegrationTest :: storefrontDelivery_toCollectionOnlyShop_isRefusedTyped_andCollectionSucceeds`, `:: vendorDelivery_toCollectionOnlyShop_isRefusedTyped_andCollectionSucceeds`, `:: vendorFallbackCollection_toDeliveryOnlyShop_isRefusedTyped`; `order/FulfilmentPolicyTest`; `frontend/app/dashboard/shops/__tests__/shop-fulfilment-form.test.tsx`; `frontend/app/shop/__tests__/shop-fulfilment-chip.test.tsx :: collection only: a 'Collection only' chip …` | ✅ |
| RWO-028 | 37-48,49,50 | update keeps slug unless a new one is sent and valid | `integration/ShopControllerIntegrationTest :: uxt028_updateWithoutSlug_keepsTheSlug_evenWhenTheNameChanges`, `:: uxt028_blankSlug_keepsTheSlug`, `:: uxt028_explicitSlug_changesIt_andTheSameSlugIsANoOp` | ✅ |

### 37-F — Accessibility

| Req | Plans | Behaviour | Tests (file :: title) | Status |
|-----|-------|-----------|-----------------------|--------|
| RWO-080 | 37-51,52,53 | at 130% text on 360 px the basket, tracker and shop header wrap, never truncate or overflow | `frontend/e2e/a11y-storefront.spec.ts :: F6: signed out at 360px with 130% text the header row fits on the menu, basket and checkout; INSTRUMENT: a widened control is caught`; `frontend/e2e/a11y-storefront.spec.ts :: F6: at 360px with 130% text the shop name and the basket names wrap; INSTRUMENT: a forced ellipsis is caught`; `frontend/e2e/a11y-tracking.spec.ts :: ${width}px at ${rootPercent}% text: no label is split inside a word, and the page does not scroll sideways` (review fix `3a105fcb`/`291fda9d`); `frontend/__tests__/shop/large-text-wrap.test.tsx :: storefront header: the shop name <h1> wraps` | ✅ COVERED |
| RWO-081 | 37-51,53 | tracking live region, aria-current step, labelled copy button and lookup | `frontend/app/__tests__/track-live-region.test.tsx :: PENDING then CONFIRMED: 'Order update: Confirmed. Shop accepted your order.'`; `frontend/app/shop/[slug]/orders/[orderNumber]/__tests__/order-live-region.test.tsx :: the first load is not a change: the region is empty after the order loads`; `frontend/e2e/a11y-tracking.spec.ts :: titles at every step, the status change announced on the order page and on /track, …` | ✅ |
| RWO-082 | 37-52,53 | every /shop card link named in Chrome's AX tree | `frontend/e2e/a11y-storefront.spec.ts :: F2: Chrome names every /shop card link by its shop; INSTRUMENT: stripped aria-labelledby is caught`, `:: CDP name probe: a labelled card link reads as its shop; an unlabelled <a><article> does not` | ✅ |
| RWO-083 | 37-52,53 | focus to stepper / back to Add; announcement names the item once with the right count | `frontend/e2e/a11y-storefront.spec.ts :: F3: Add moves focus to '+' and announces the dish once; the last Remove returns focus to Add; INSTRUMENT: no region, no record`; `frontend/lib/__tests__/basket-announcement.test.ts :: names the one dish that went in`; `frontend/__tests__/shop/product-card-quantity-a11y.test.tsx` | ✅ |
| RWO-084 | 37-51,53 | basket/checkout/confirmation/tracking titles name the step and shop | `frontend/app/shop/__tests__/route-titles.test.ts :: basket: 'Basket · {Shop} — J'Toye'`, `:: checkout: 'Checkout · {Shop} — J'Toye'`; `frontend/e2e/a11y-tracking.spec.ts :: titles at every step, …` | ✅ |
| RWO-085 | 37-52,53 | cookie banner never covers focus; reachable early in tab order | `frontend/e2e/a11y-storefront.spec.ts :: F5: the notice is met first and never covers the focused element in the first 30 tab stops; INSTRUMENT: a notice forced over focus is caught`, `:: overlap probe: a fixed notice over the focused element is reported; a clear one is not`; `frontend/components/public/__tests__/cookie-notice.test.tsx` | ✅ |

| Cross-cutting | Behaviour | Evidence | Status |
|---|---|---|---|
| SC-7 | 40 goods re-verified | `evidence/37-53-goods-40.md` (mixed API/browser battery, instrument corrections recorded there) | ✅ as recorded |

### G-37-6 gap closure (37-56..37-59, delta over `36f9aee3`, audited 2026-10-16 at `eb7636ac`)

No new requirement; G-37-6 (UAT test 6: finance figures overflow, pagination on narrow widths, void-dialog fit, sentence-end copy) is closure work under rows already COVERED above. Added tests, each re-run by this audit (Java XML timestamps 2026-10-16T15:37Z; jest rc 0):

| Requirement(s) | Plan | Test | Result |
|---|---|---|---|
| RWO-003, -004, -018, -026 | 37-56 | `frontend/lib/__tests__/sentence-end.test.ts` (literal table + exhaustive whitespace-set row); `frontend/__tests__/sentence-end-call-sites.test.ts` (six call sites + source guard with planted controls); touched `order-allergen-panel.a11y`, `import-copy`, `staff-page` suites | ✅ jest |
| RWO-004 (+ customer emails) | 37-57 | `core-java/.../common/SentencesTest` (8: case table, 0x0000-0xFFFF whitespace row, parity with both TS declarations); `EmailNotificationServiceTest` (29), `EmailNotificationServiceStaffInviteTest` (7), `EmailTemplateRendererTest` (17) | ✅ 61/61 |
| RWO-027, RWO-018, RWO-046 | 37-58 | `frontend/components/ui/__tests__/pagination.test.tsx` (container tiers, 44 px controls, `paginationWindow`); `frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx` (container-query grids, tabular figures) | ✅ jest |
| G-37-6 live | 37-59 | `frontend/e2e/screens-copy-uat.spec.ts` (5 live tests, each with an instrument arm; finance grids measured at each `@container` threshold) | ✅ as recorded: RED rc 1 (1 passed / 4 failed on gap assertions) on the pre-fix runtime, GREEN `11 passed` on the rebuilt runtime, arms failed as designed (`evidence/37-59-g376-runtime.md` §3-4). NOT re-run by this audit (it writes to the live stack). |

Jest re-run by this audit: 7 suites, 145 tests passed (sentence-end 2 suites, pagination, finance-scope, staff-page 45, import-copy, order-allergen-panel.a11y).

*Planning-time names that do not exist and were replaced by the real tests (confirmed absent with `rg -uu --files`, rc 1):*
`OrderServiceKitchenStatusesTest` → `order/KitchenStatusesParityTest`; `OpeningScheduleMigrationIntegrationTest` →
`shop/V80OpeningScheduleBackfillIntegrationTest`; `e2e/a11y-persona-gaps.spec.ts` → `e2e/a11y-storefront.spec.ts` + `e2e/a11y-tracking.spec.ts`.

---

## Gaps

**Gap 1 — RWO-080 (RESOLVED 2026-10-15).** Closed on this branch: `3b13a7a8` (F6 now measures the settled layout and adds the signed-out menu/basket/checkout arm; RED 364/360 on the old image), `013acbca` + `7816464d` (the header row fits; GREEN 360/360 on the rebuilt image), re-measured independently by the phase verifier (37-VERIFICATION.md re_verification). Original finding, kept for the record: Signed OUT, at 360x740 with `html{font-size:130%}`, the storefront menu, basket and
checkout measure `document.scrollWidth/clientWidth` = 364/360: the header row (logo, basket link, "Sign in", "Open
menu") overflows by 4 px (`deferred-items.md`, "From 37-53", status open; `evidence/37-53-gate-37F.md` 2.9 item 1). It
predates 37-F (measured on `b68f0505`), but RWO-080 asks for no overflow on the shop header at this condition.
The F6 test passes only by timing: `largeTextNarrow` is followed immediately by the document check while the basket
link is mid-`transition-all` (360/360 at t=0, 364/360 at 300 ms), so on the settled page it cannot fail.
- **Missing behaviour:** (a) the header row fits at 360 px / 130% signed out; (b) a test that measures the SETTLED
  layout and is RED on today's tree.
- **Suggested test file:** `frontend/e2e/a11y-storefront.spec.ts`, the F6 block: make `largeTextNarrow` wait for
  `document.getAnimations()` to finish (or a frame after `transitionend`) before the document check, add a signed-out
  arm on menu, basket and checkout, assert `scrollWidth <= clientWidth`, record RED before the header fix and GREEN after.
- **Not generated in this run** (orchestrator constraint: no test code after the 37-F gate). REQUIREMENTS.md already
  shows RWO-080 unchecked; 37-53-SUMMARY records it "held".

The /track mid-word label break (37-53 gate item 0) was a second RWO-080 defect; it is resolved on this branch
(`3a105fcb` RED, `291fda9d` GREEN, deferred-items status resolved) and is covered by the `a11y-tracking.spec.ts`
whole-word block named above.

---

## Wave 0 Requirements

- [x] 37-B: strict-default IT; teardown capture/restore of strict-scoping; `scripts/check-strict-scoping-default.sh`; invite/void/finance ITs; stepwise migration tests
- [x] 37-A: kitchen session-lapse spec; pause/hours ITs; hours migration stepwise test (`V80OpeningScheduleBackfillIntegrationTest`)
- [x] 37-C: basket-revalidation IT + e2e
- [x] 37-D: `ClientIpResolverTest`; cap ITs; limits IT across both writers
- [x] 37-E: sync, credential and webhook ITs; edge passthrough Go tests; MCP vitest arms
- [x] 37-F: a11y specs (CDP AX-tree name probe + live-region recorder) with INSTRUMENT arms (`a11y-storefront.spec.ts`, `a11y-tracking.spec.ts`)
- [x] 37-G: fulfilment-offered IT
- [x] Metrics: `docs/metrics.json` regenerated (6088 invocations at `c2850e5f`)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Repeating kitchen alert is audible in a noisy room | RWO-020 / D-12 | Loudness and audibility cannot be asserted by a browser test (autoplay needs a user gesture; tests assert the loop fires, not that it is heard) | On the compose stack, tap "Enable sound", place a storefront order, confirm the sound repeats until Accept and the mute indicator shows when muted |
| Invite email → Keycloak registration → landing with the grant | RWO-004 / D-07 | Crosses Mailhog + live Keycloak user-profile config | Covered live by `e2e/staff-invite.spec.ts` on the compose stack; the human checkpoint repeats it from the Mailhog link |
| Credential token issued by live Keycloak carries tenant_id and scopes | RWO-035 / D-22 | Live Keycloak admin API on the merged tree | Proved live in 37-47 (evidence); compose checkpoint: create credential, client-credentials grant, decode token, call one scoped endpoint |
| Screen reader on the tracking journey; phone tab-through with the cookie notice | RWO-081, RWO-085 | Assistive-technology output is not what the AX-tree probe reads | The 37-53 `<human-check>`: VoiceOver/TalkBack through /track and the order page; tab from the top on a phone with the notice showing |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 120s
- [x] `nyquist_compliant: true` set in frontmatter (Gap 1 resolved 2026-10-15)

**Approval:** validated, 35 of 35 covered (RWO-080 closed by the gap fix, 2026-10-15)

## Validation Audit 2026-10-15

| Metric | Count |
|---|---|
| Gaps found | 1 |
| Resolved | 1 (gap fix 3b13a7a8 / 013acbca / 7816464d, owner-approved) |
| Escalated | 0 |

## Validation Audit 2026-10-16

| Metric | Count |
|---|---|
| Gaps found | 0 |
| Resolved | 0 |
| Escalated | 0 |
| Plans added since last audit | 3 |
| Jest tests re-run | 101 |

Plans added since the 2026-10-15 audit: 37-54, 37-55 (gap closure G-37-12..15, mapped under RWO-003/RWO-004) and quick 261016-edv (forced light theme, a presentation change with no requirement row). Jest re-run 2026-10-16: staff-page, staff-invites, webhooks-page, theme-cross-surface, use-theme: 5 suites, 101 passed.

## Validation Audit 2026-10-16

| Metric | Count |
|---|---|
| Gaps found | 0 |
| Resolved | 0 |
| Escalated | 0 |
| Plans added since last audit | 4 |
| Jest tests re-run | 145 |
| Java tests re-run | 61 |

Plans added since the previous 2026-10-16 audit: 37-56..37-59 (gap G-37-6), mapped under RWO-003/004/018/026/027/046 in the "G-37-6 gap closure" block above. No requirement gap; nyquist_compliant stays true.
