---
phase: 37-real-world-operations-readiness
plan: 50
subsystem: gate
tags: [runtime-parity, compose, v83, fulfilment-offered, delivery-fee, free-delivery-threshold, slug, shop-slug-taken, fulfilment-not-offered, storefront-chip, checkout, playwright, pr-cut, d-03, d-04, uxt-023, uxt-028]
status: complete

requires:
  - phase: 37-47
    provides: "the sub-theme gate shape and the PR-cut procedure; PR 920 (37-E) merged, reconciliation a proven no-op"
  - phase: 37-48
    provides: "V83 offers_delivery/offers_collection, the four shop DTO fields, 422 fulfilment-not-offered on both writers, the slug-keep rule and 409 shop-slug-taken"
  - phase: 37-49
    provides: "the shop-form fee/threshold/offered fields, the storefront chip, the checkout static row and 422 copy, the create-dialog web-address preview"
provides:
  - "Every static gate rc 0 and the full suites green on the 37-G head (unit 1827/0, integration 1242/0, JaCoCo 91.68%, jest 2834/243, cold build, tsc, lint 0 errors, go 112, vitest 70)"
  - "RED on the old runtime for all three live proofs, every image rebuilt (edge-go and mcp-server with --no-cache), freshness PASS 4/4 with 0 unverified, V83 applied out of order, OpenAPI PASS, GREEN live"
  - "The fee (299/3000 charged 299, 299, 0), the threshold clear, the live refusal race, 422 on both writers, the kept slug and 409 shop-slug-taken, the chip and the form at 375/390/768/1280; the four 37-G goods; the plan's Playwright line 64/0"
  - "Local PR branch phase-37-g-shop-admin (2a870c9f), one squash commit on origin/main fc1f0ab8, tree equal to tag cut/phase-37-g-shop-admin (c355ae16); upstream unset, not pushed"
  - "WINDOWS 44 and 45 fixed; two findings for the owner in deferred-items"
affects: [gsd-ship-37-G, 37-51, phase-37-uat, k8s-staging-rollout, k8s-production-rollout]

actuals:
  tokens: 15239
  tasks: 3
  commits: 4
plan_head_before: d0d2730aa49e56d9a38c3daf445edc262fd876f9
plan_head_after: a99d3209a8f8368d60b02d669e6c2c99c50fbaf7

tech-stack:
  added: []
  patterns:
    - "RED never writes a demo shop on a runtime whose save rewrites the slug: the dev seeder upserts curated shops by slug, so a changed slug would create a duplicate shop at the next start; RED vendor writes go to a throwaway shop"
    - "A fully cached image build is a vacuous rebuild: services whose sources did not change are rebuilt again with --no-cache"

key-files:
  created:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-50-screens/ (25 PNGs)
    - .planning/phases/37-real-world-operations-readiness/37-50-SUMMARY.md
  modified:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-50-gate-37G.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md
    - .planning/WINDOWS.md
    - scripts/check-no-object-store-residue.sh allowlist (Task 1, 2f5b88fc)

key-decisions:
  - "Owner answer to Task 2 (2026-10-14): proceed. Demo shop settings were NOT restored afterwards, as instructed; every original value is recorded (evidence 2.10)"
  - "Only Brixton Village Grill was changed (fee 299, threshold 3000, collection-only); Mama Ade's stays delivering because storefront-flows orders DELIVERY there"
  - "Findings that change product behaviour or cache format are recorded for the owner, not fixed at the gate (the 37-47 precedent)"
  - "The PR branch's upstream, set to origin/main by git switch -c, was unset so a bare push cannot target main"

patterns-established:
  - "A stale cache can make a fresh runtime look half-deployed: a pre-deploy GET's cached body is served by the rebuilt core until TTL or eviction"

requirements-completed: [RWO-023, RWO-028]

coverage:
  - id: D1
    description: "Static gates and full suites green on the 37-G head (Task 1, Part 1)"
    requirement: RWO-023
    verification:
      - kind: other
        ref: "evidence/37-50-gate-37G.md 1.3-1.5; the plan's two Task 1 <automated> lines"
        status: pass
    human_judgment: false
  - id: D2
    description: "Runtime parity: every image rebuilt and recreated, freshness PASS 4/4 (0 unverified), V83 applied out of order, OpenAPI contract matches live"
    requirement: RWO-023
    verification:
      - kind: other
        ref: "bash scripts/check-runtime-freshness.sh (rc 1 before, 0 after); bash scripts/check-openapi-snapshot-fresh.sh (rc 1 before, 0 after)"
        status: pass
    human_judgment: false
  - id: D3
    description: "A vendor-set fee and threshold are charged by the storefront; a collection-only shop refuses DELIVERY on the storefront and the vendor API; an edit keeps the slug and the old /shop/{slug} resolves (RED then GREEN)"
    requirement: RWO-028
    verification:
      - kind: e2e
        ref: "evidence/37-50-gate-37G.md 2.2, 2.4, 2.6 (probes.sh red|green a|b)"
        status: pass
    human_judgment: false
  - id: D4
    description: "Live UI: threshold clear, fee preview equals the server charge, the refusal race, the chip and static row, the form and the web-address preview"
    requirement: RWO-023
    verification:
      - kind: automated_ui
        ref: "evidence/37-50-gate-37G.md 2.5, 2.7; evidence/37-50-screens/"
        status: pass
      - kind: e2e
        ref: "(cd frontend && npx playwright test e2e/storefront-flows.spec.ts e2e/storefront-ssr-seo.spec.ts) 64 passed"
        status: pass
    human_judgment: true
    rationale: "The screens at 375/390/768/1280 are the UI-SPEC G1/G2 visual sign-off, and finding 2 (the directory card and basket on a collection-only shop) needs the owner's decision before merge"
  - id: D5
    description: "The PR branch phase-37-g-shop-admin cut as one squash on origin/main, tree equal to cut/phase-37-g-shop-admin, not pushed"
    verification:
      - kind: other
        ref: "bash scripts/check-branch-behind-base.sh && tree equality && rev-list --count origin/main..phase-37-g-shop-admin = 1 (rc 0)"
        status: pass
    human_judgment: false

duration: 59min active (Part 1 11:54Z-12:31Z, Task 3 16:18Z-16:41Z; the owner checkpoint in between)
completed: 2026-10-14
---

# Phase 37 Plan 50: Sub-theme 37-G gate (catalogue and shop admin) and the PR cut Summary

**37-G is proven on rebuilt images: the vendor's £2.99 fee and £30 threshold are charged, a collection-only shop refuses DELIVERY with a typed 422 on both writers and in a live checkout race, and edits keep the shop's web address. All three proofs went RED on the old runtime first. The 37-G change is cut locally as one squash, `phase-37-g-shop-admin`.**

## Performance

- **Duration:** about 59 min of work: Part 1 from 11:54Z to 12:31Z, then Task 3 from 16:18Z to 16:41Z. The owner checkpoint sat in between.
- **Started:** 2026-10-14T11:54:12Z
- **Completed:** 2026-10-14T16:41Z
- **Tasks:** 3 (Task 2 was the owner's checkpoint)
- **Files modified:** 4 tracked text files plus 25 screens

## Accomplishments
- **Task 1 (earlier session):** every static gate passes and the full suites are green on the 37-G head. One gate was red because 37-49 moved an allowlist line. It was renumbered at the source (`2f5b88fc`).
- **RED on the old runtime (core `23623cd44983`):**
  - The vendor API drops the fee, the threshold and the offered types.
  - It accepts both types off, and a fee of -1.
  - Every save rewrites the slug, even a phone-only save.
  - Brixton takes DELIVERY orders on both writers.
  - freshness rc 1, and the OpenAPI gate rc 1 with 15 markers.
- **Rebuild of every image:**
  - edge-go and mcp-server were fully cached, so they were rebuilt again with `--no-cache`.
  - All containers were recreated.
  - freshness PASS 4/4, with 0 unverified.
  - V83 applied out of order. Its sha in the jar matches the committed file.
  - The OpenAPI gate passes, and the Task 1 tracer line reads all zeros.
- **GREEN, the fee:** fee 299 with threshold 3000 is charged 299 at £16, 299 at £20 and 0 at £30. Before the rebuild the same subtotals were charged 399, 0 and 0.
- **GREEN, the threshold clear:** emptying "Free delivery over" sends an explicit null, and SQL reads it back as NULL.
- **GREEN, the refusal race (live):**
  - A Delivery checkout is open while the vendor unticks Delivery in the dashboard.
  - Place order gets a 422 with "This shop doesn't offer delivery. Choose collection to continue."
  - The control switches to the static row, and the order then goes through as COLLECTION.
- **GREEN, both writers:** the storefront and `POST /orders` each return 422 `fulfilment-not-offered` and write 0 rows.
- **GREEN, slugs:**
  - Slugs are kept on every edit. A demo shop's rename keeps `/shop/brixton-village-grill` resolving.
  - A taken slug gets the typed 409 `shop-slug-taken`.
  - The create-dialog preview matches the slug of the shop it creates.
- **Pixels:**
  - The chip wraps under the status at 375 and 390.
  - The static row, the form and a long web address stay inside the viewport and the dialog at 375, 390, 768 and 1280.
- **Goods:**
  - P2-KYL-P2: tampered prices are ignored, and a tampered expectation gets a 409.
  - P2-CHA-14: an order below the minimum is refused in plain words.
  - P2-REG-17: holds on the delivering shop. The collection-only shop has a gap (finding 2).
  - P2-RAV-19: the published contract matches the live service.
  - The plan's Playwright line: 64 passed, 0 failed.
- **PR branch:** `phase-37-g-shop-admin` at `2a870c9f` is one squash on origin/main `fc1f0ab8`, with the same tree as `cut/phase-37-g-shop-admin` (`c355ae16`). It is local only and its upstream is unset. The D-04 merge of origin/main is up to date.

## Task Commits

1. **Task 1: static gates and full suites (earlier session):** `2f5b88fc` (fix: renumber the object-store residue allowlist row), `629dd298` (docs: Part 1 evidence)
2. **Task 2: checkpoint:decision, blocking-human.** The owner answered "proceed". No commit.
3. **Task 3: live RED, rebuild, GREEN, goods, screens, branch pointer:**
   - `c355ae16`: Part 2 evidence, 25 screens, deferred items, and WINDOWS 44/45 fixed. **This is the cut tag.**
   - `a99d3209`: the 2.13 branch pointer, written after the cut.
   - PR branch `phase-37-g-shop-admin` at `2a870c9f`: a squash of `cut/phase-37-g-shop-admin`. Local only, not pushed.

`commits: 4` is `git rev-list --count d0d2730a..a99d3209`, measured before this summary's own commit.

## Files Created/Modified
- `evidence/37-50-gate-37G.md`: Part 2, sections 2.0 to 2.13:
  - pre-rebuild identity, RED, the rebuild and parity;
  - GREEN for the API, the UI, both writers and pixels;
  - goods, findings, the demo-shop originals and test orders, WINDOWS, gitleaks, and the branch pointer.
- `evidence/37-50-screens/`: 25 PNGs, each one opened and looked at.
- `deferred-items.md`: "From 37-50 (the 37-G gate, Task 3, live)", two findings.
- `.planning/WINDOWS.md`: rows 44 and 45 set to fixed, with their reasons.

## Decisions Made
- The owner answered "proceed". Demo shop settings are left changed, as instructed.
  - Brixton Village Grill was originally fee 399, threshold 2000, minimum 1000, Delivery and Collection both offered.
  - It is now fee 299, threshold 3000, collection-only.
  - Its name was changed and then restored inside the slug proof.
  - Mama Ade's and Peckham were not changed.
  - Restore steps, and the note that the dev seeder resets the fee but not the offered flag, are in evidence 2.10.
- RED made no vendor writes to a demo shop. Every save on the old runtime rewrites the slug, and the seeder upserts by slug, so a demo-shop write would have created a duplicate shop.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Task 1: an object-store residue allowlist row went stale after 37-49 moved its line**
- **Found during:** Task 1
- **Fix:** renumbered the row from 401 to 422; its reason is unchanged.
- **Committed in:** `2f5b88fc`

**2. [Rule 1 - Instrument] Some of the rebuild was vacuous**
- **Found during:** Task 3
- **Issue:** the edge-go and mcp-server builds were fully cached, so "rebuild ALL" executed nothing for them.
- **Fix:** rebuilt both with `--no-cache`. go build and npm ci both executed.
- **Committed in:** `c355ae16` (evidence 2.3)

**3. [Instrument] The first frontend content control could not fail**
- **Found during:** Task 3
- **Issue:** the control "Pay on delivery" exists only in test files, so it read 0 in the running image.
- **Fix:** discarded it and used "Opening hours" (6 files) as the control.

**4. The plan's wording versus the tree**
- The plan says "prose counts (head V83)". The head is V84, and V83 applies out of order. The docs already say so and were left alone (Part 1.2).
- The plan asks for screens at 390/768/1280. WINDOWS 45 asks for 375, so 375 was added for the chip and the long slug.

**5. Upstream unset on the PR branch**
- `git switch -c … origin/main` sets the new branch's upstream to origin/main.
- It was unset so that a bare `git push` cannot target main.

**Total deviations:** 1 auto-fixed blocking issue in Task 1, plus instrument corrections in Task 3. No product code changed in Task 3.

## Issues Encountered
Two findings at the gate, both in deferred-items "From 37-50" and evidence 2.9:

1. **For up to 15 minutes after a deploy, `GET /shops/{id}` can serve the pre-deploy ShopDto.**
   - Cause: the `shops` Redis region (`v4:` prefix, TTL 15 min) survives a restart.
   - Measured: a cached Brixton came back with none of the four 37-48 fields at 16:26:00Z, while an uncached shop at the same moment had all four.
   - Risk: the `?edit={id}` dialog would then send fee 0 and a null threshold, silently zeroing the fee and clearing the threshold.
   - Recommended: bump `CACHE_KEY_FORMAT_VERSION` from v4 to v5 as a review-fix on the PR branch.
2. **A collection-only shop still shows a delivery fee on the `/shop` directory card and on its basket page.**
   - The card reads "Delivery £2.99 Free over £30.00". The basket says delivery is "Added at checkout".
   - The header, the details bar and checkout are correct.
   - UI-SPEC G1 does not list these two surfaces.
   - Decide before merge. Candidate review-fix: `onlyOfferedType(shop)` on both surfaces, with screens.

## Known Stubs
None. Task 3 changed no product code.

## Threat Flags
None new. T-37-118 is mitigated: identity was recorded before the rebuild, every image was rebuilt, freshness reported 0 unverified, and each proof is recorded RED then GREEN. T-37-SC: no package was installed on the host. Gitleaks returns rc 0 on the evidence, screens, deferred-items and WINDOWS. A planted control returns rc 1.

## Human checks harvested for the phase UAT (human_verify_mode end-of-phase)
- Review the chip, checkout, form and web-address screens at 375/390/768/1280 in `evidence/37-50-screens/`. These are the UI-SPEC G1/G2 visual sign-off.
- Decide on finding 2, using `goods-list-390.png` and `goods-basket-brixton-collection-only-390.png`.

## Notes for the PR body (orchestrator)
- **Title shape:** "feat(37-G): catalogue and shop admin". Plans 37-48 to 37-50. Requirements RWO-023 and RWO-028.
- **Say plainly:**
  - the PR carries **V83** `shop_fulfilment_offered`, which applies out of order after V84;
  - every existing shop reads both types offered.
- **Decide before merge:** finding 2 (the directory card and the basket), and finding 1 (the cache key version bump).
- **Carried owner items:**
  - the 37-31 H4 proxy-peer IP-cap question;
  - trusted proxies and `CLIENT_IP_HMAC_KEY` for staging and production;
  - Phase 39 planning before 2026-10-26;
  - `core-api` `serviceAccountsEnabled`;
  - old-slug 301 aliases, deferred to Phase 39 by the 2026-10-10 ruling.
- **Changelog:** the entry citing the PR number goes on `phase-37-g-shop-admin` once the PR exists.
- **No AI-attribution lines** (owner ruling).

## User Setup Required
None.

## Next Phase Readiness
- `phase-37-g-shop-admin` is ready to push and open. Review rounds land on that branch.
- The runtime is current: all four images were built from `629dd298`. The commits after it change only `.planning`, which no image builds from.
- Brixton is left collection-only with fee 299 and threshold 3000. The next gate that orders DELIVERY at Brixton must re-tick Delivery first.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-14*

## Self-Check: PASSED

- The evidence file, `deferred-items.md`, and the 25 screens (including `shop-chip-375.png` and `checkout-race-refusal-1280.png`) exist.
- Commits `2f5b88fc`, `629dd298`, `c355ae16` and `a99d3209` are ancestors of HEAD. `gsd_run check evaluation-scope --plan 37-50 --commits-only` resolves them, rc 0.
- `phase-37-g-shop-admin` = `2a870c9f`, with the same tree as `cut/phase-37-g-shop-admin` (`c355ae16`), and is 1 commit above origin/main. The plan's third `<automated>` line returns rc 0.
- Re-run on the rebuilt runtime:
  - `check-runtime-freshness.sh` rc 0;
  - the Task 1 tracer line `rc=0 0 0 0 0 0`;
  - the Playwright line: 64 passed.
