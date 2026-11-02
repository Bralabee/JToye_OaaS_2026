---
phase: quick
plan: 261102-r6u
subsystem: storefront landing, public API, k8s config, staging seed
status: complete
tags: [featured-kitchens, landing, staging-seed, demo-kitchens, D17]
requires: [origin/main 03eb939a]
provides:
  - GET /api/v1/public/featured-shops (+ legacy /public/featured-shops)
  - jtoye.storefront.featured-shop-slugs / JTOYE_FEATURED_SHOP_SLUGS (k8s app-config storefront.featured-shop-slugs)
  - landing row featured-first, empty-radius featured state, Featured label
  - scripts/staging-seed.sh --stage demo-kitchens | order --kitchen | list-open | clear-orders
  - scripts/fixtures/demo-kitchens.json + DemoKitchensFixtureParityTest
  - design D17
affects: [frontend landing /, k8s staging render, compose dev, staging-seed selftest]
tech-stack:
  added: []
  patterns: [record @ConfigurationProperties validated in its constructor, per-caller selftest stub responses]
key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/storefront/FeaturedShopsProperties.java
    - core-java/src/main/java/uk/jtoye/core/storefront/FeaturedShopsConfig.java
    - core-java/src/main/java/uk/jtoye/core/storefront/FeaturedShopsService.java
    - core-java/src/main/java/uk/jtoye/core/storefront/FeaturedShopsController.java
    - core-java/src/test/java/uk/jtoye/core/storefront/FeaturedShopsPropertiesTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/FeaturedShopsServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/FeaturedShopsIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/dev/DemoKitchensFixtureParityTest.java
    - frontend/lib/featured-shops.ts
    - frontend/lib/__tests__/featured-shops.test.ts
    - scripts/fixtures/demo-kitchens.json
  modified:
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java
    - core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
    - core-java/src/main/resources/application.yml
    - docs/api/openapi-snapshot.json
    - docker-compose.full-stack.yml
    - k8s/base/configmap.yaml
    - k8s/base/core-java-deployment.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/production/configmap-patch.yaml
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - frontend/lib/storefront-server.ts
    - frontend/app/page.tsx
    - frontend/components/marketing/near-you-row.tsx
    - frontend/components/marketing/shop-card.tsx
    - frontend/e2e/near-you-row.spec.ts
    - frontend/app/__tests__/landing.test.tsx
    - frontend/app/__tests__/metadata-base.test.ts
    - frontend/lib/__tests__/storefront-server.test.ts
    - frontend/components/marketing/__tests__/near-you-row.test.tsx
    - frontend/components/marketing/__tests__/shop-card.test.tsx
    - scripts/staging-seed.sh
    - design/decisions.md
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - docs/CHANGELOG.md
decisions:
  - "Featured kitchens are per-environment config (staging = 3 demo slugs, production empty), published-only, editorial; label 'Featured', 'Sponsored' if ever paid (D17)"
  - "Staging demo kitchens: three tenants 'J'Toye Demo — <kitchen>', one kitchen each, seller '<kitchen> (demo)', no billing, platform-account TEST charges (D17)"
  - "Demo kitchens' success path deletes the operator explicitly before printing the verdict, so the verdict is the last line and the DELETE is the last call"
metrics:
  started: 2026-11-02T20:03:35Z
  completed: 2026-11-02T21:07:02Z
  duration: 63m
estimate:
  tokens: 170000
actuals:
  tokens: 59265     # chars/4 over the realized diff excluding .planning/ (237059 chars); 112311 including plan + evidence
  tasks: 3
  commits: 15        # MEASURED: git rev-list --count plan_head_before..HEAD at SUMMARY write; the metadata commit (SUMMARY + STATE) follows as the 16th
plan_head_before: 03eb939a1ddf75832f6590aaa2016c3cc4e11454
plan_head_after: 56a63f74007a6284a7c930a1638120dd3362f917
---

# Phase quick Plan 261102-r6u: Featured kitchens config + staging demo kitchens Summary

An ordered, per-environment, startup-validated list of featured shop slugs now leads the landing
kitchen row through a published-only public endpoint (staging lists three demo kitchens, production
ships empty), with an honest "featured on J'Toye" empty-radius state and an editorial "Featured"
label; staging-seed.sh gains a demo-kitchens stage (three tenants through the real APIs), a
platform-charge demo order proof and stale-order listing and clearing, all proven by --selftest only.

## Status

**complete.** Every task's automated verification passed on the final tree, every break arm was
shown RED then GREEN, and the items that need a running stack or a tool this machine lacks are
recorded NOT RUN below (never as passed).

## What was built

- **Task 1 (tracer, D-02/D-03):** `FeaturedShopsProperties` (record, `@ConfigurationProperties
  "jtoye.storefront"`, trims, refuses blank / duplicate / non-slug / more than 12 entries, naming
  the key), `FeaturedShopsConfig`, `ShopRepository.findByPublishedTrueAndSlugIn`,
  `PublicStorefrontService.listPublishedShopsBySlugs` (one query, configured order, the existing
  `toPublicShopDto`, no seller, no constructor change), `FeaturedShopsService` (no query when empty,
  one WARN per distinct skipped set via `AtomicReference`, no caching), `FeaturedShopsController`
  (new controller; the three `@WebMvcTest` slices of `PublicStorefrontController` untouched).
  application.yml `featured-shop-slugs: ${JTOYE_FEATURED_SHOP_SLUGS:}`; compose dev default lists the
  three dev demo slugs; k8s base `""` + env wiring, staging patch the three slugs, production patch
  `""` explicitly; goldens regenerated. Frontend: `loadFeaturedShops` (optional, `[]` on any failure),
  `orderWithFeatured` / `featuredSlugsOf`, page.tsx `Promise.all`, merge only when the listing loaded,
  JSON-LD from the merged list.
- **Task 2 (D-03/D-04):** `NearYouRow` `featuredSlugs` prop; nothing-in-radius with featured shows the
  featured kitchens alone under "No kitchens within 3.1 miles — featured on J'Toye"; nearby wins in
  distance order; label passed in every phase. `ShopCard` `featured` prop: a bottom-left, absolutely
  positioned white-on-brand-900 pill reading exactly "Featured", id in `aria-describedby`. e2e spec
  extended (no new `test()` blocks).
- **Task 3 (D-01 as ruled, D-05):** behaviour-neutral `DemoDataSeeder` refactor (`CuratedShop`
  list, package-private `MenuItem` / `curatedMenusBySlug`, named constants, `curatedShopEmail`);
  `scripts/fixtures/demo-kitchens.json` (3 kitchens, 21 dishes) generated from the seeder's own data
  by a throwaway test (deleted, never committed) and held equal by `DemoKitchensFixtureParityTest`;
  staging-seed.sh `load_demo_fixture` (VOID / REFUSED [phase30-tenant] / REFUSED [kitchen] before any
  tool call), `demo_vendor_user` (fresh password through reset-password PUT, mode 600),
  `--stage demo-kitchens`, `--stage order --kitchen` (asserts no Stripe-Account and no
  `transfer_data`, prints `charge=platform`), `--stage list-open` and `--stage clear-orders`
  (`--kitchen` required); helpers generalised with defaults that keep the Phase 30 paths unchanged
  (`ORDER_WHO`, `onboard_to_live WHO LABEL REASON`, `partial_import_vendor USER PWFILE FIRST`); 15
  demo-* selftest arms and a per-caller stub. D17 appended. Metrics 7027 -> 7065.

## Verification (real output; files under `evidence/`)

| Check | RED (fail direction) | GREEN |
|---|---|---|
| FeaturedShopsIntegrationTest (NOSUPERUSER NOBYPASSRLS, both prefixes, [B, A], one WARN) | `t1-red-it.txt`: compileTestJava FAILED (no FeaturedShopsService) | 1/1, 0 failures (`t1-green.txt`) |
| landing featured-first / dedup / JSON-LD | `t1-red-landing.txt`: 3 failed, 33 passed | 36/36 |
| FeaturedShopsPropertiesTest | arm (c): 4 failures (the 4 refusal arms) | 8/8 |
| FeaturedShopsServiceTest | (written with the code; WARN behaviour also RED in the IT arms) | 3/3 |
| k8s render values (staging 3 slugs, production "" , local "", env on core-java in all three) | `t1-render-values.txt`: origin/main tree rc=1 (all 6 checks FAIL); arm (f) production rc=1 | rc=0 |
| check-env-contract | `t1-env-contract-arm.txt`: yml line removed -> rc=1 naming JTOYE_FEATURED_SHOP_SLUGS | rc=0 |
| render-golden | `t1-golden-check-before-write.txt`: rc=1 before --write | rc=0; diff adds only the 2 x 6 featured lines (`t1-golden-diff.txt`) |
| check-render-invariants | — (no new invariant) | rc=0 (`t1-k8s-gates.txt`) |
| OpenApiSnapshotTest | `t1-openapi-red.txt`: 1 failure before regeneration | 1/1; diff adds only `/api/v1/public/featured-shops` and `/public/featured-shops` (`t1-openapi-diff.txt`) |
| no migration (D-02) | positive control: same command over `core-java/src/main/java` lists 6 files | `git diff --name-only origin/main -- .../db/migration` = [] rc=0 |
| Task 2 Jest (near-you-row, shop-card) | `t2-red.txt`: 8 failed, 52 passed | 60/60; Task 2 verify set 101/101 |
| existing near-you-row / shop-card tests unedited | control: the same removed-line pattern finds 5 in near-you-row.tsx's own diff | 154 added, 0 removed (`t2-tests-additive.txt`) |
| near-you-row fetch call sites <= 1 | planted second fetch: 2, gate rc=1 | 1, rc=0 (`t2-fetch-gate.txt`) |
| DemoKitchensFixtureParityTest | fixture absent: 1 failure (`t3-red-parity.txt`); arm (a1) fixture price 1 failure; arm (a2) seeder price 1 failure | 4/4 |
| dev seeder tests unmodified (refactor neutral) | — | DemoImageManifestTest 8, AddressTest 4, Hours IT 3, TraderIdentity IT 3, ServiceAccountGrant IT 5, all 0 failures (`t3-dev-tests.txt`) |
| staging-seed.sh --selftest | `t3-red-selftest.txt`: 89 PASS / 14 FAIL (demo-clear-needs-kitchen passed vacuously there: any unknown stage is a usage error; arm (h) proves it can fail) | **103 PASS / 0 FAIL, rc=0** (`t3-selftest.txt`; baseline at HEAD 88 PASS / 0 FAIL, `t3-selftest-baseline.txt`) |
| arm-list gate (20 named arms) | over arm (e)'s output: `missing PASS arm: demo-seller-label-missing` rc=1 | rc=0 |
| D17 present | origin/main: 0 (rc=1) | 1 |
| docs-freshness | `t3-metrics-before.txt`: rc=1 (3184/437/33 vs 3200/441/34) | rc=0, 7065 |
| check-doc-metrics | `t3-check-doc-metrics-before.txt`: 15 FAIL lines, rc=1 | 37/37 PASS rc=0 (`t3-metrics-after.txt`) |
| changelog contract (C-1) | — | rc=0 |
| frontend full Jest | — | 293 suites, 3486 tests, rc=0 |
| frontend lint | — | rc=0, 0 errors, 26 warnings, none in a touched file (control: the matcher finds `landing.test`) |
| frontend build | — | `npm run build` rc=0 (after Task 1 and after Task 2) |
| frontend tsc --noEmit (includes e2e + tests) | — | rc=0, 0 errors |
| shellcheck -S warning staging-seed.sh | — | 3 findings, the same 3 as origin/main (2 SC1122 heredoc, 1 SC2034); none new |
| core-java full unit (`:core-java:test`) | — | 231 suites, 2098 tests, 0 failures, 0 errors, 1 skipped, rc=0 |
| core-java full integration (`:core-java:integrationTest`) | — | 235 suites, 1434 tests, 0 failures, 0 errors, 1 skipped, rc=0 (14m 51s; `final-java-full.txt`) |
| check-branch-behind-base | — | rc=0, 15 ahead / 0 behind origin/main 03eb939a (re-run immediately before the PR) |

### Break arms

| Arm | Break | RED | GREEN / restore |
|---|---|---|---|
| T1 (a) | finder without published predicate (`findBySlugIn`) | IT: superuser read got [B, U, A] (`t1-arm-a.txt`) | restore `git diff --exit-code` rc=0 |
| T1 (b) | re-ordering removed | IT: got [A, B] (`t1-arm-b.txt`) | rc=0 |
| T1 (c) | properties validation removed | 4 refusal tests FAILED (`t1-arm-c.txt`) | 8/8 |
| T1 (d) | page.tsx passes the listing alone | 3 landing cases failed (`t1-arm-d.txt`) | 36/36 |
| T1 (e) | orderWithFeatured without dedup | 2 failed (`t1-arm-e.txt`) | 41/41 |
| T1 (f) | production patch = demo slugs | render-value rc=1 "FAIL production value not empty" (`t1-arm-f.txt`) | rc=0 |
| T2 (a) | empty state keeps the here-is-everything heading | 1 failed | 60/60 (`t2-arm-a.txt`) |
| T2 (b) | "near you" planted in that heading | 1 failed (the case also holds the no-locality assertion; its exact-heading check fires first) | 60/60 |
| T2 (c) | `featured` only in the idle phase | 3 failed | 60/60 |
| T2 (d) | label id dropped from aria-describedby | 1 failed | 60/60 |
| T3 (a1/a2) | price drift in the fixture / in DemoDataSeeder | parity 1 failure each (`t3-arm-a-parity.txt`) | 4/4 |
| T3 (b) | 409 refusal removed | demo-slug-taken FAIL | real file untouched: `git diff --exit-code` rc=0 (`t3-arms-b-h.txt`) |
| T3 (c) | Phase 30 guard removed | demo-phase30-refused FAIL | idem |
| T3 (d) | operator DELETE skipped | demo-first-run FAIL (and 14 other operator arms) | idem |
| T3 (d2) | EXIT-trap delete only skipped | demo-slug-taken, demo-seller-label-missing FAIL | idem |
| T3 (e) | seller-label check removed | demo-seller-label-missing FAIL | idem |
| T3 (f) | Stripe-Account sent on the demo intent read | demo-order-card FAIL | idem |
| T3 (g) | accept skipped before a PENDING refund | demo-clear-orders FAIL | idem |
| T3 (h) | --kitchen requirement on clear-orders removed | demo-clear-needs-kitchen FAIL | idem |

### NOT RUN (with reason; none reported as passed)

- **Runtime parity** (`scripts/check-runtime-freshness.sh`, `curl :8080/api/v1/public/featured-shops`,
  landing HTML hrefs, `scripts/check-openapi-snapshot-fresh.sh` against the stack) and the
  **near-you-row Playwright spec**: this lane does not own the shared Compose stack (another session
  may), and the orchestrator forbade starting, stopping or rebuilding it. Run at integration after
  a rebuild from this branch. Recorded in `.planning/WINDOWS.md` (#55, #56).
- **`scripts/openapi-gate.sh` (oasdiff):** oasdiff is not installed here and installing a package is
  not auto-fixable. The byte-equality half (OpenApiSnapshotTest) passed and the diff only adds two GET
  paths, so the change is additive by construction; CI runs the gate. WINDOWS #57.
- **Any staging stage:** by instruction, only `--selftest` ran. Post-deploy, with the owner's
  go-ahead, the orchestrator runs `--stage demo-kitchens`, then `--stage order --kitchen
  mama-ades-kitchen` (the runtime proof of the measured card path), then `--stage list-open --kitchen
  mama-ades-kitchen` and `--stage clear-orders` on that order.

## Measured payment path (D-05; read in code on 03eb939a)

- Platform-wide decision: `PublicStorefrontService.java:1603` `if (paymentService.isConfigured())`;
  card branch `:1603-1657` (DRAFT + PaymentIntent), cash/COD `:1658-1689` (PENDING, NONE, "Unpaid").
- `PaymentService.isConfigured()` `PaymentService.java:164-166` = STRIPE_API_KEY non-blank
  (`application.yml:836`), injected by `k8s/base/core-java-deployment.yaml:650-655`; `GuestOrderRequest`
  has no payment-method field. So on staging every storefront order, demo kitchens included, is a card
  order.
- No ENABLED Connect account: `StripeConnectService.resolveSettlementRouting` `:342-362` and
  `resolveDestinationAccount` `:398-407` return empty, so `PaymentService.createPaymentIntent`
  `:239-266` sets neither `transfer_data` nor `Stripe-Account` (javadoc `:199-202`): a plain
  platform-account TEST charge, settled CAPTURED by the webhook. Refunds `RefundService.java:158-163`
  (CONFIRMED..COMPLETED only), `:224-241` (no routing). Cancel `OrderStateMachineConfig.java:87-120`
  refunds nothing, which is why clear-orders refunds a paid order instead.
- `--stage order --kitchen` asserts exactly this (no Stripe-Account header, `transfer_data` absent,
  `charge=platform`).
- **Follow-up (out of scope):** routing a demo kitchen's card money to the kitchen needs per-tenant
  Connect onboarding, the owner's hosted Stripe step.

## Design-time contract dispositions

- **Agent-readiness:** the new GET is read-only and idempotent by nature (no Idempotency-Key needed);
  errors come from the existing typed RFC 7807 handlers (429 rate-limited via RateLimitInterceptor on
  `/api/v1/public/**`); it is in the regenerated OpenAPI snapshot. **MCP tool: N/A** — the mcp-server's
  tools are tenant-scoped vendor operations, and this is a public, anonymous ordering hint any agent
  already reads through the documented public API.
- **Web performance:** no new client fetch and no new client dependency on `/` (fetch-site gate 1, shown
  failing at 2); one extra server-side call in parallel (`Promise.all`); the label is out of flow, so
  card height is unchanged (no CLS). Throttled-profile measurement NOT RUN (needs the running stack).
- **SEO:** featured cards are server-rendered crawlable `<a href="/shop/{slug}">` links and are in the
  landing JSON-LD (landing test "puts the featured shops in the landing JSON-LD", RED then GREEN).
- **Security:** threat register T-r6u-01..10 mitigated as planned (published predicate in the query,
  proven by the superuser read and arm T1(a); startup validation with no CR/LF possible; production
  render empty, arm T1(f); operator deleted on every path and before any order read/write; fresh demo
  passwords never printed or in argv; Phase 30 guards; slug-taken refusal; owner-list-only clearing
  with TEST-mode reads; staging-only api-base).
- **Client-persisted identity lifecycle:** N/A — nothing is persisted client-side.
- **Falsifiable evidence + runtime parity:** every criterion RED then GREEN (tables above); runtime
  parity NOT RUN with the reason above.

## Deviations from Plan

### Auto-fixed / adjusted

**1. [Rule 1 - falsifiability] The IT also reads as the superuser.**
- **Found during:** Task 1 break arm (a). Under the anonymous NOSUPERUSER role `shops_public_read`
  alone hides the unpublished shop, so a finder without the published predicate stayed GREEN: the
  plan's arm (a) could not fail against the IT as specified.
- **Fix:** the IT first reads as the Testcontainers superuser (which sees the unpublished row), so the
  query predicate is the only filter there; arm (a) then went RED. Commit 73de0385.

**2. [Plan conflict] Operator deletion order in `--stage demo-kitchens`.**
- The plan wanted both "stdout ends with DEMO-KITCHENS" and "the last call is the operator DELETE",
  relying on the EXIT trap, whose `OPERATOR-DELETED` line would then follow the verdict. The stage
  now reads the featured list, deletes the operator explicitly (the last call), then prints
  DEMO-FEATURED and the verdict. Consequently arm T3(d) "EXIT-trap delete skipped" does not red
  demo-first-run; it was run in two forms: (d) the DELETE skipped everywhere -> demo-first-run FAIL;
  (d2) only the trap's delete skipped -> the failure-path arms (demo-slug-taken,
  demo-seller-label-missing) FAIL.

**3. [House style] demo-dry-run-plan allows the guards' read-only kubectl calls.**
- The plan said kubectl logs zero lines; the script's documented `--dry-run` contract is "run every
  guard (they only read), then print the plan", and the existing dry-run arms assert exactly that.
  The arm asserts az=0, curl=0 and no kubectl call beyond the guards' `config view` and pod read.
  Likewise demo-prod-api-refused uses the existing prod-api arm's bound (one local config read).

**4. [Instrument] Fixture generated, not hand-copied.**
- The fixture was written by a temporary JUnit test from `DemoDataSeeder`'s own data (deleted, never
  committed), then held equal by the parity test; RED was shown with the fixture absent and by both
  drift arms.

**5. [Recovery] A break-arm restore discarded uncommitted work once.**
- During Task 2 the fetch-gate arm restored `near-you-row.tsx` with `git checkout --` before the
  implementation was committed, which reverted it to the RED commit (the recorded
  "break-arm revert eats fixes" trap). The same deterministic edits were re-applied, all Task 2 tests
  re-run GREEN, the build re-run, and the work committed BEFORE any further arm; later arms on the
  staging-seed script ran on copies, leaving the real file untouched.

**6. Task 3 break arms (b)-(h) ran on copies** of `scripts/staging-seed.sh` beside it (removed after),
  in parallel; the committed script was never edited, proven by `git diff --exit-code` rc=0.

### CHANGELOG

`docs/CHANGELOG.md` carries the entry heading with **`(#PR-PENDING)`**: the orchestrator replaces it
with the PR number once the PR exists (`check-changelog-cites-pr.sh` P-1 checks it in PR context).

## Known Stubs

None. (The selftest stub curl is test infrastructure, not product data.)

## Threat Flags

None beyond the plan's register: the new endpoint is the planned public read; no auth path, file
access or schema change was added.

## Commits

- 56a63f74 docs(quick-261102-r6u): changelog entry (PR number pending)
- f609e226 docs(quick-261102-r6u): metrics for the featured-kitchens and demo-kitchens tests (7027 -> 7065)
- dddc6310 docs(quick-261102-r6u): design D17, featured kitchens and the staging demo kitchens
- 2fd20502 test(quick-261102-r6u): Task 3 break arms (a)-(h) RED then GREEN; the arm-list gate shown failing
- 538f163a feat(quick-261102-r6u): staging demo kitchens that take orders (demo-kitchens, order --kitchen, list-open, clear-orders)
- 42eb5fb6 test(quick-261102-r6u): RED fifteen demo-* staging-seed selftest arms and the per-caller stub
- 28ad3c72 feat(quick-261102-r6u): staging demo-kitchens fixture, held equal to DemoDataSeeder by a parity test
- e254356b test(quick-261102-r6u): Task 2 break arms (a)-(d) RED then GREEN, restores proven by content
- 6077a98c feat(quick-261102-r6u): empty-radius featured state, nearby still winning, and the Featured label
- 399ab968 test(quick-261102-r6u): RED empty-radius featured state, nearby still winning, and the Featured label
- 59eda371 test(quick-261102-r6u): Task 1 break arms (a)-(f) RED then GREEN, restores proven by content
- 73de0385 test(quick-261102-r6u): featured IT also reads as the superuser, so the query's published predicate is falsifiable
- b2658d3d feat(quick-261102-r6u): featured kitchens lead the landing row (config, k8s, compose, public API)
- 0e8cbafc test(quick-261102-r6u): RED featured kitchens IT and landing featured-first cases
- 66f5aaac docs(quick-261102-r6u): plan

The metadata commit (this SUMMARY, STATE.md, WINDOWS.md) follows.

## Self-Check: PASSED

- 11/11 created files FOUND; 15/15 task commits are ancestors of HEAD; no Co-Authored-By, Claude-Session or "Generated with" line in any commit message.
