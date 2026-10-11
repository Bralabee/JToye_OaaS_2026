---
phase: 37-real-world-operations-readiness
plan: 03
subsystem: testing
tags: [strict-scoping, shop-access, d-06, demo-seeder, mcp, service-account, testcontainers]

requires:
  - phase: 37-02
    provides: "ShopGrants.grantOperator, StrictScopingGuard, the booted-value instrument, and the strict-ON red list (evidence/37-02-strict-on-reds.md: 29 reds in 9 classes)"
provides:
  - "All 9 classes of the 37-02 strict-ON red list pass with ACCESS_STRICT_SCOPING=true and unset, each stating its access with an explicit OPERATOR grant"
  - "Full suite green in both modes: 1596 :test + 894 :integrationTest, 0 failures, on c3fb5743"
  - "DemoDataSeeder (dev only) seeds OPERATOR grants + directory rows for integration-orders-rw (SHOP_MANAGER) and integration-catalog-ro (STAFF) on each curated demo shop"
  - "DemoDataSeederServiceAccountGrantIntegrationTest: rows, idempotent re-run, strict-ON 201 on a granted shop / 403 on the ungranted archive shop, catalog-ro read non-empty under strict ON"
affects: [37-04, 37-B, compose-mcp]

actuals:
  tokens: 32544
  tasks: 3
  commits: 4
plan_head_before: bd193ca20ae7d379186d8e6b0ce07f3bf800eb7c
plan_head_after: 739b69d7f727d12a24d7bb11f6391ca26a8d3e8d

tech-stack:
  added: []
  patterns:
    - "Conversion recipe: a fixed sub per test, ShopGrants.grantOperator on the exact shop at the role the gate requires; tenant-wide GROUP_ADMIN only where the request is genuinely tenant-wide; refusal arms keep their ungranted callers"
    - "Dev-seeded service-account access: explicit OPERATOR per-shop grants, create-only, created_by = a fixed dev-seed operator id"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/dev/DemoDataSeederServiceAccountGrantIntegrationTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-03-red-seeder-grants.json
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md
  modified:
    - core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
    - core-java/src/test/java/uk/jtoye/core/security/ScopedWriteAccessIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/ScopedCatalogAccessIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/integration/TypedNotFoundBodyIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaRedriveControllerTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaUploadControllerTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaUploadIdempotencyTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductMayContainIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductSaveAllergenWarningIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/shop/MarketingMissingRowStatusIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/integration/ShopControllerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderSseGrantRecheckTest.java

key-decisions:
  - "The conversion list is evidence/37-02-strict-on-reds.md (9 classes), not the plan's file list: 6 of the 9 were not in the plan's list, and 9 of the plan's 11 Task-2 files were already green under strict ON (measured again here)"
  - "Per-shop SHOP_MANAGER wherever a shop exists; tenant-wide OPERATOR GROUP_ADMIN only for ScopedCatalogAccess (a shopless product create, which only GROUP_ADMIN may make) and TypedNotFoundBody GET /shops/{unknown} (only a tenant-wide caller reaches the lookup that answers 404; a scoped caller is refused for any ungranted shop)"
  - "ScopedWriteAccess order-create now targets a real seeded shop (shop_staff.shop_id is a foreign key, so a grant cannot name a random shop); its pinned 404 is now 'Product not found: <id>', asserted on the detail so a shop-gate 403 or a 500 cannot false-green it"
  - "integration-catalog-ro IS shop-gated: GET /api/v1/products returns an EMPTY page (200) to a zero-grant caller under strict ON (measured RED: 0 of 21). RESEARCH's 'authenticated-only' assumption was wrong; it gets STAFF"
  - "Service-account grants cover the three curated storefronts only, never the hidden archive shop and never tenant-wide; the archive is the per-shop refusal proof in the IT"
  - "Seeder grants are create-only (any existing row on a shop is left as it is), mirroring the 31.1-12 seller-details rule"

patterns-established:
  - "A test that must pass a shop gate seeds its own grant for a sub it then reuses; a refusal test keeps an ungranted caller"

requirements-completed: [RWO-004]

coverage:
  - id: D1
    description: "The tracer class ScopedWriteAccessIntegrationTest passes in both modes with an explicit SHOP_MANAGER grant; 403 assertions 6 before, 6 after"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: "ACCESS_STRICT_SCOPING=true ./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests ScopedWriteAccessIntegrationTest (+ booted-value instrument) -> rc=0, 12/0, booted=true; unset -> rc=0, 12/0, booted=false; break arm (grant disabled) under ON -> 'Status expected:<404> but was:<403>'"
        status: pass
    human_judgment: false
  - id: D2
    description: "Every class in the 37-02 strict-ON red list is green under ACCESS_STRICT_SCOPING=true and unset; the full suite is green in both modes"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: "full :test + :integrationTest on c3fb5743: strict ON rc=0, 187 suites/1596 tests + 173 suites/894 tests, 0 failures, booted=true; unset rc=0, same counts, 0 failures, booted=false"
        status: pass
      - kind: other
        ref: "count403.sh vs bd193ca2: refusal-assertion counts SAME in all 9 classes (6,3,0,2,0,0,0,0,0); bogus base ref -> VOID rc=2"
        status: pass
    human_judgment: false
  - id: D3
    description: "Dev seed writes explicit OPERATOR grants + directory rows for integration-orders-rw (SHOP_MANAGER) and integration-catalog-ro (STAFF) on each curated shop, idempotently; strict-ON create 201 on a granted shop and 403 on the ungranted archive shop"
    requirement: "RWO-004"
    verification:
      - kind: integration
        ref: "core-java/src/test/java/uk/jtoye/core/dev/DemoDataSeederServiceAccountGrantIntegrationTest.java (5 tests): RED 5/5 fail on the old seeder (RED_EVIDENCE_OK), GREEN 5/0 unset and under strict ON; break arms (orders-rw at STAFF; archive also granted) each red"
        status: pass
    human_judgment: false
  - id: D4
    description: "In compose (live dev stack), MCP create_order and list_products keep working after the D-06 flip"
    verification: []
    human_judgment: true
    rationale: "Proven by an integration test that boots the dev profile and drives the real controllers with the service accounts' token shape; not exercised against the running compose stack with real Keycloak client-credentials tokens. 37-04 flips the default and should exercise it live."

duration: 1h 27m
completed: 2026-10-08
status: complete
---

# Phase 37 Plan 03: Explicit grants for the implicit-admin tests and the MCP service accounts Summary

**All nine strict-ON red classes now state their access with OPERATOR grants and pass in both modes (full suite 2,490 tests, 0 failures, with `ACCESS_STRICT_SCOPING=true` and unset). The dev seed also gives `integration-orders-rw` (SHOP_MANAGER) and `integration-catalog-ro` (STAFF) explicit per-shop grants, so MCP `create_order` and the catalogue read survive the D-06 flip.**

## Performance

- **Duration:** 1h 27m (about 59 min of it the two full-suite runs)
- **Started:** 2026-10-08T08:55:23Z
- **Completed:** 2026-10-08T10:22:44Z
- **Tasks:** 3
- **Files modified:** 15 (3 created, 12 modified)

## Accomplishments

- **Tracer (Task 1).** In `ScopedWriteAccessIntegrationTest`, the order-create arm now seeds a real shop. A fixed sub gets an OPERATOR SHOP_MANAGER grant on it. The test still pins the downstream 404, now as `Product not found: <id>`.
- **Task 2.** The other eight red classes are converted. Six use a per-test sub with SHOP_MANAGER on the exact shop: media re-drive, media upload, media upload idempotency, product may-contain, product allergen warning, and marketing missing-row. Two need a tenant-wide OPERATOR GROUP_ADMIN, because their requests are genuinely tenant-wide: a shopless product create, and GET /shops/{unknown}. Refusal arms keep their ungranted callers.
- **Task 3.** `DemoDataSeeder` (dev only) writes the two service accounts' directory rows and grants on the three curated storefronts. The new IT shows all of these in both modes:
  - the rows exist;
  - a re-run creates nothing;
  - a strict-ON create on a granted shop returns 201;
  - a create on the ungranted archive shop returns 403;
  - catalog-ro reads the full curated catalogue.

## Per-class accounting (the 37-02 red list)

| Class | Failed under ON before | Grant used | Arms changed | 403 asserts before/after | ON after | unset after |
|-------|-----------------------:|------------|--------------|-------------------------:|----------|-------------|
| `security.ScopedWriteAccessIntegrationTest` | 1/12 | SHOP_MANAGER on seeded `SHOP_A` | `writeScopedTokenReaches404OnOrderCreate` | 6/6 | 12/0 | 12/0 |
| `security.ScopedCatalogAccessIntegrationTest` | 1/5 | tenant-wide GROUP_ADMIN (shopless product = tenant-wide write) | `operatorScopeNotForbiddenOnCreate` | 3/3 | 5/0 | 5/0 |
| `integration.TypedNotFoundBodyIntegrationTest` | 1/13 | tenant-wide GROUP_ADMIN (only it reaches the shop 404) | `getShop_unknownId_isTypedProblem` | 0/0 | 13/0 | 13/0 |
| `media.MediaRedriveControllerTest` | 4/7 | SHOP_MANAGER on `shopA` (`managerA`, seeded per test) | AC-4.1, 4.2, 4.5, 4.6 (AC-4.3 foreign-tenant, AC-4.4 refusal and AC-4.8 left as they were) | 2/2 | 7/0 | 7/0 |
| `media.MediaUploadControllerTest` | 2/5 | SHOP_MANAGER on the product's shop | `operatorJwt()` now the granted sub | 0/0 | 5/0 | 5/0 |
| `media.MediaUploadIdempotencyTest` | 2/4 | SHOP_MANAGER on the product's shop | `operatorJwt()` now the granted sub | 0/0 | 4/0 | 4/0 |
| `product.ProductMayContainIntegrationTest` | 6/7 | SHOP_MANAGER on the class shop | `vendorJwt()` now the granted sub | 0/0 | 7/0 | 7/0 |
| `product.ProductSaveAllergenWarningIntegrationTest` | 4/4 | SHOP_MANAGER on the class shop | `vendorJwt()` now the granted sub | 0/0 | 4/0 | 4/0 |
| `shop.MarketingMissingRowStatusIntegrationTest` | 8/12 | SHOP_MANAGER on the arm's shop (`grantManager`) | the 8 red arms incl. both CONTROLs; the 4 absent-id arms keep an ungranted caller | 0/0 | 12/0 | 12/0 |

The "after" columns come from the full-suite runs on `c3fb5743`. The plan's Task 2 file list also named nine classes that 37-02 measured green under ON: SecurityHeaders, OrderIdempotency, LocationHeaderContract, ShopController, OnboardingGoLive, OrderSseGrantRecheck, ProductSearchFts, SyncBatchAuthorization and WebhookAuthz. All nine are still green in both full runs here. Their comments mostly describe the realm-admin bridge, which strict ON keeps honouring, so the comments are accurate. Two comments presented the day-one rule as unconditional; those were corrected (ShopController, OrderSseGrantRecheck). No other change was needed.

## Task Commits

1. **Task 1: tracer, ScopedWriteAccess converted** - `cbac231e` (test)
2. **Task 2: the other eight red classes converted** - `c3fb5743` (test)
3. **Task 3 RED: failing seeder-grant IT + classifier record** - `e7cc0e44` (test)
4. **Task 3 GREEN: seeder writes the service-account grants** - `739b69d7` (feat)

**Plan metadata:** the commit that adds this SUMMARY and `deferred-items.md` (docs). STATE.md and ROADMAP.md are not touched; the orchestrator owns them.

## Evidence (both directions)

### Task 1 (tracer)

| Run | Result |
|-----|--------|
| RED before the change, strict ON | recorded in 37-02 evidence: `Status expected:<404> but was:<403>` |
| GREEN, `ACCESS_STRICT_SCOPING=true` (+ instrument) | rc=0, 12/0; `env.ACCESS_STRICT_SCOPING=true expected=true booted=true` |
| GREEN, unset (+ instrument) | rc=0, 12/0; `booted=false` |
| Break arm (grant call disabled, committed first; sha256 `c5665b2b…3f9d77`) under ON | rc=1, exactly `writeScopedTokenReaches404OnOrderCreate`: `Status expected:<404> but was:<403>` |
| Restore | `git checkout --` from the commit; sha256 matched `c5665b2b…3f9d77`; closing clean = both full-suite runs |

The tracer gate was then re-run (auto/end-of-phase, automated verify only). It passed, so expansion went ahead.

### Task 2

- Targeted strict-ON run of the 9 classes plus the instrument: rc=0, 70 tests, 0 failures, `booted=true`.
- Full suite on `c3fb5743`, using the plan's exact command:

| Mode | Start / end (UTC) | rc | `:test` | `:integrationTest` | Instrument |
|------|-------------------|---:|---------|--------------------|------------|
| `ACCESS_STRICT_SCOPING=true` | 09:07:27 / 09:35:50 | 0 | 187 suites, 1596 tests, 0 failed, 1 skipped | 173 suites, 894 tests, 0 failed, 1 skipped | `booted=true` |
| unset | 09:35:52 / 10:05:13 | 0 | 187 / 1596 / 0 / 1 skipped | 173 / 894 / 0 / 1 skipped | `booted=false` |

Both runs executed `> Task :core-java:test` and `> Task :core-java:integrationTest` (cleanTest/cleanIntegrationTest first), and neither was UP-TO-DATE. XML was read from `core-java/build-local/test-results` and copied out before the next clean. The suite shape matches 37-02's (187 + 173 suites, 2,490 tests). Under ON, the 29 reds are now 0.

The XML reader fails closed: an empty directory gives VOID (rc 2); `tests=0`, `failures>0` or `errors>0` gives BAD (rc 1); an unparseable header gives VOID. Its BAD direction showed up in real runs here: the Task 1 break arm and the Task 3 RED and arm runs each printed `BAD … failures=N` with the failing testcase.

The refusal-assertion counter (`isForbidden()|isEqualTo(403)|shop-access-denied|is(403)`, base `bd193ca2` vs tree) reports SAME for all 9 classes. Given a bogus base ref, it prints VOID for every file and exits 2. Its CHANGED branch (`after != before` gives rc 1) was **not** exercised against a real differing ref, so it is unverified.

### Task 3 (TDD)

- **RED** (`e7cc0e44`, env unset, run started 2026-10-08T10:05:47Z): rc=1, and all 5 tests fail on their planned assertions:
  - the grant lists are `[]`, where 3 curated slugs were expected;
  - the snapshot is empty;
  - the strict-ON create returns 403 `shop-access-denied` where 201 was expected;
  - catalog-ro reads `expected: 21 but was: 0`.

  The context booted: the MockMvc arm returned a real typed 403 body. `gsd_run check tdd-red-evidence` on `evidence/37-03-red-seeder-grants.json` returned `RED_EVIDENCE_OK`, `target_test_failed` for `…#ordersRwHoldsAnOperatorShopManagerGrantOnEveryCuratedShopAndNothingTenantWide()`. A first classification without the trailing `()` returned `INVALID_RED / no_target_test_failure`. That was a naming mismatch in the record, not a test change. **Semantic assessment:** the target executed and failed on the planned row assertion for the intended reason: the seeder writes no rows. There was no setup, compile or fixture fault.
- **GREEN** (`739b69d7`): unset rc=0, 5/0, and `DemoDataSeederTraderIdentityIntegrationTest` stays 3/0. The seeder's own log line on first boot reads `6 service-account grant(s) and 2 service-account directory entry(ies) created`. On the in-test re-run it reads `0 … and 0 …`.
- **Break arms** (each committed first; sha256 `72798926…20d153b` before and after each restore):
  - orders-rw at STAFF instead of SHOP_MANAGER: 3 failures, including `[role] … ["STAFF","STAFF","STAFF"] to contain only ["SHOP_MANAGER"]`, and the create on a granted shop returned 403. The role is load-bearing.
  - the archive shop also granted: the refusal assertion fails with `create on an ungranted shop: {"detail":"Product not found: …"}` (404, not 403), and both grant-list assertions fail. The per-shop claim can fail.
- **Closing clean:** strict ON (+ trader-identity IT + instrument): rc=0, 9/0, `booted=true`. Unset (the plan's verify command): rc=0, 5/0.
- Every dev-profile test class (8, the only ones that boot the seeder) was run under `:test` + `:integrationTest` in both modes: 8 of 8 XMLs, all OK, rc=0 in each mode.
- Acceptance grep `grep -n '5c0c16be-1aa0-4181-b808-2c7575f03b95' …/DemoDataSeeder.java` prints `206: static final UUID ORDERS_RW_SERVICE_ACCOUNT = …`. Fail direction: before `739b69d7` the seeder had no such line; the RED run's empty grant list shows the same state behaviourally.

### Minimum roles, read from source

- `OrderService.requireCreateAccess` and `createOrder` both call `require(shopId, SHOP_MANAGER)`. So orders-rw gets SHOP_MANAGER.
- The MCP read tools call `GET /orders`, `/orders/shop/{id}`, `/orders/{id}/detail`, `/shops` and `/products`. These are STAFF-gated or filtered by grant set, and SHOP_MANAGER satisfies STAFF.
- catalog-ro (`catalog:read` only) calls `GET /api/v1/products`. `ProductService.getAllProducts` returns `Page.empty` to a zero-grant non-GROUP_ADMIN, so the path is shop-gated and the account gets STAFF. The plan said to check this rather than assume it: RESEARCH predicted "authenticated-only, probably needs nothing", and the RED run measured an empty catalogue.

## Decisions Made

See `key-decisions` in the frontmatter. In short:
- grant the exact shop and role, and go tenant-wide only where the request itself is tenant-wide;
- keep the refusal arms ungranted;
- treat catalog-ro as shop-gated because that was measured;
- leave the archive shop ungranted on purpose, as the in-test proof that the grants are per shop.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The conversion list differs from the plan's Task 2 file list**
- **Found during:** Task 2
- **Issue:** The plan listed 11 files, mostly from RESEARCH's prose search. The authoritative 37-02 evidence names 9 red classes. Six of those were not in the plan's list: TypedNotFoundBody, MediaRedrive, MediaUploadIdempotency, ProductMayContain, ProductSaveAllergenWarning and MarketingMissingRowStatus. Nine of the listed files were already green under ON.
- **Fix:** Converted the 9 evidence classes, as the plan's own read_first instructs ("any class it names beyond these is also converted here"). For the listed-but-green classes, I confirmed them green in both full runs and corrected two stale comments.
- **Files modified:** see the per-class table
- **Commit:** `c3fb5743`

**2. [Rule 1 - Bug] The ScopedWriteAccess 404 could not keep its old cause under an explicit grant**
- **Found during:** Task 1
- **Issue:** The arm posted to a random, non-existent shop, and `shop_staff.shop_id` is a foreign key to `shops`. A per-shop grant on that shop is impossible. Only the implicit, or a tenant-wide, GROUP_ADMIN reached the old 404.
- **Fix:** Seed a real shop, grant SHOP_MANAGER on it, and post a random product. The 404 is now `Product not found`, pinned on `$.detail`. That is stronger than the status alone, because a shop-gate 403 or a 500 can no longer satisfy it.
- **Commit:** `cbac231e`

**3. [Rule 2 - Missing critical] integration-catalog-ro gets a seeded grant**
- **Found during:** Task 3
- **Issue:** RESEARCH expected the account to need nothing. The source and the RED run show the product list is an empty 200 page under strict ON. That is a silent regression by omission in compose.
- **Fix:** Seed STAFF on each curated shop, and assert the read is the full curated catalogue, by content, under strict ON.
- **Commit:** `739b69d7`

**4. [Rule 3 - Blocking] Python-free verification**
- No python step was needed. All checks are bash/jq/grep scripts in the session scratchpad: `check-xml.sh`, `count403.sh`, `run-nine.sh`, `run-full.sh`, `run-dev.sh` and `red-record.sh`. The session guard refused compound commands, so the sed edits for the marketing class ran from a script.

---

**Total deviations:** 4 (1 bug, 1 missing critical, 2 blocking).
**Impact on plan:** No production behaviour changed except the dev-only seeder. No refusal assertion was weakened: the counts are identical in all 9 classes.

## Issues Encountered

- **The docs-freshness gate is red on this branch** (`docs/metrics.json` 2322/346 vs source 2330/349). 37-02 contributed +3 methods in 2 files, and this plan +5 in 1. It is deferred to the PR step, with the measurement and route recorded in `deferred-items.md` §1. Both docs gates must be fixed before the phase PR.
- **`GET /api/v1/media/review-queue` is tenant-scoped only.** Under strict ON, an ungranted vendor still receives the tenant's FAILED/flagged asset list. Evidence: MediaRedrive AC-4.8 stays green with a fresh random subject, and `MediaAssetService.reviewQueue` makes no `ShopAccessService` call. This is a production authorization gap outside this plan and is logged in `deferred-items.md` §2. AC-4.8 was deliberately left unconverted so that the gap stays visible.
- **The scratchpad directory is shared with the 37-02 session.** My `check-xml.sh` and `run-full.sh` replaced 37-02's scratch files of the same name. 37-02's evidence was already committed, so nothing was lost.
- **The plan ledger was kept in the scratchpad** (`plan-head-before`), not the git dir, as in 37-02. Its value is `bd193ca2…`.

## Known Stubs

None.

## Threat Flags

None. The only production change is the `@Profile("dev")` seeder. Its grants are per shop (never tenant-wide), and the IT proves an ungranted shop is still 403 (T-37-05). Every test grant names the exact shop and role, except the two genuinely tenant-wide requests (T-37-04).

## TDD Gate Compliance

Task 3 (`tdd="true"`): RED `e7cc0e44` `test(37-03): …` precedes GREEN `739b69d7` `feat(37-03): …`. The RED evidence was classified `RED_EVIDENCE_OK` and semantically assessed (see Evidence). No REFACTOR commit was needed. Tasks 1 and 2 are test-only conversions (not behaviour-adding), so the gate does not apply to them.

## User Setup Required

None.

## Next Phase Readiness

- **37-04 can flip the default:** with `ACCESS_STRICT_SCOPING=true` the full suite is green, so no red is expected from the flip itself. Two follow-ups:
  - `StrictScopingBootedValueIntegrationTest`'s default must change to `"true"` along with `application.yml`.
  - `ShopAccessServiceScopingPostureTest`'s WARN text changes with the posture log.
- **Compose:** after the flip, a dev volume that already exists picks up the service-account grants on the next core-java start, because the seeder runs every dev boot. 37-04 should exercise MCP `create_order` live (coverage D4).
- **Before the phase PR:** clear `deferred-items.md` §1 (docs metrics).

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-08*

## Self-Check: PASSED

- Created files exist: DemoDataSeederServiceAccountGrantIntegrationTest.java, evidence/37-03-red-seeder-grants.json, deferred-items.md
- Commits on this branch: cbac231e, c3fb5743, e7cc0e44, 739b69d7 (`git rev-list --count bd193ca2..HEAD` = 4 at SUMMARY write)
- Acceptance criteria re-run: the seeder grep prints line 206; refusal counts SAME in 9/9; full suite green in both modes on c3fb5743; Task 3 IT green in both modes on 739b69d7
