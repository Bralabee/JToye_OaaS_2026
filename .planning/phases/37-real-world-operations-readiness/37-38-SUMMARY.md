---
phase: 37-real-world-operations-readiness
plan: 38
subsystem: gate
tags: [runtime-parity, compose, checkout-integrity, basket-revalidation, promotions, reviewer-name, cash-confirmation, rls, order-tracking, allergens, v84, nosuperuser, playwright, perf, pr-cut, d-03, d-04, uxt-007, uxt-008, uxt-029, uxt-044]
status: complete

requires:
  - phase: 37-31
    provides: "the sub-theme gate shape and the PR-cut procedure; PR 917 (37-D) merged, reconciled by 5c626c63"
  - phase: 37-32
    provides: "expectedUnitPricePennies / expectedTotalPennies on the guest order"
  - phase: 37-33
    provides: "422 basket-confirmation-required and the one 409 basket-changed"
  - phase: 37-34
    provides: "BasketChangedPanel, Accept changes, resubmit under the same Idempotency-Key"
  - phase: 37-35
    provides: "promotions unpublished server-side; ReviewerDisplayName ('First L.')"
  - phase: 37-36
    provides: "no promotion on the storefront; review cards render the minimised name"
  - phase: 37-37
    provides: "the cash confirmation on its own route, fed by the tracking read"
provides:
  - "Every static gate rc 0 and the full suites green on the 37-C head (unit 1769/0, integration 1152/0 after V84, jest 2744/235, build, tsc, lint, go, vitest)"
  - "RED on the 37-D runtime (a reprice charged at the new price with no refusal, the cash confirmation never leaving the checkout, the promotion and full surnames published), every image rebuilt, GREEN live; perf on /shop/[slug] measured before and after inside the budgets"
  - "V84 order_items_guest_tracking (owner option A): an anonymous tracking read sees its order's lines, so /track, the card route and the cash confirmation report the recorded allergen set; RED 4/7 -> GREEN 7/0 under NOSUPERUSER NOBYPASSRLS, a drop-policy break arm and a widening arm"
  - "V82/V83 left reserved for 37-45/37-48, applying out of order after V84 (measured)"
  - "Local PR branch phase-37-c-checkout (caf945c0), one squash commit on origin/main 078c0c11, tree equal to tag cut/phase-37-c-checkout (d060d0af), upstream unset; not pushed"
affects: [gsd-ship-37-C, 37-39, 37-45, 37-48, phase-37-uat, k8s-staging-rollout, k8s-production-rollout]

actuals:
  tokens: 60300
  tasks: 3
  commits: 9
plan_head_before: 7e618eee5e50e83cb6d3273ad6a0532a06e156d0
plan_head_after: efc9fa5400606721fe8819bb1f3d3623b5c040e7

tech-stack:
  added: []
  patterns:
    - "A line table under FORCE RLS needs the SAME anonymous read policy as its parent row; a parent-only guest policy reads as 'nothing recorded', not as an error"
    - "A guest SELECT policy on a child table re-checks the parent's own predicate inside an EXISTS that the parent's policies also filter: the mirrored clause is defence in depth and the parent's RLS is the second wall (proven by a widening break arm that stays green)"
    - "An RLS IT downgrades the datasource role to NOSUPERUSER NOBYPASSRLS and asserts both before any arm, with a tenant-pinned positive control so a 0 is a reading"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V84__order_items_guest_tracking.sql
    - core-java/src/test/java/uk/jtoye/core/storefront/GuestTrackingOrderLinesRlsIntegrationTest.java
    - .planning/phases/37-real-world-operations-readiness/evidence/37-38-gate-37C.md
    - .planning/phases/37-real-world-operations-readiness/evidence/37-38-screens/ (16 PNGs)
  modified:
    - core-java/src/test/java/uk/jtoye/core/storefront/CustomerRecordedAllergenSetIntegrationTest.java
    - frontend/e2e/storefront-flows.spec.ts
    - frontend/e2e/abuse-bounds.spec.ts
    - frontend/e2e/seller-identity.spec.ts
    - frontend/e2e/storage-keys-signout.spec.ts
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - .planning/phases/37-real-world-operations-readiness/evidence/37-01-baseline.md
    - .planning/phases/37-real-world-operations-readiness/deferred-items.md

key-decisions:
  - "37-38 Task 2: 'Proceed' - 37-C may take the shared compose stack now (owner, 2026-10-12)"
  - "37-38 open item 1.6 (the review-form notice, UI-SPEC C4): 'N/A, keep window open' - the human check is N/A and WINDOWS row 43 stays OPEN (owner, 2026-10-12)"
  - "37-38 blocker 2.9: 'Option A - one migration adding an order_items guest-tracking SELECT policy that mirrors orders_guest_tracking (narrowest access, DB-enforced)' (owner, 2026-10-12)"
  - "37-38: the migration is V84; V82/V83 stay reserved for 37-45/37-48 and apply out of order after it (spring.flyway.out-of-order=true, measured with planted V82/V83 on a database at V84)"
  - "37-38: the line policy re-checks the TRACKING pair, not 'any order the caller can see', because V18 orders_guest_history exposes orders by app.customer_email alone and lines must not follow the history GUC"
  - "37-38: the orchestrator pushes phase-37-c-checkout and opens the 37-C PR by hand in the ship shape (the 37-23/37-31 precedent: /gsd-ship cannot open a sub-theme PR mid-phase)"

patterns-established:
  - "A gate that finds a defect at an RLS boundary stops for the owner (Rule 4), then fixes it as a TDD deviation with its own RED, GREEN and break-arm commits before the cut"

requirements-completed: [RWO-007, RWO-008, RWO-029, RWO-044]

duration: about 3h 40m over three sessions
completed: 2026-10-12
---

# Phase 37 Plan 38: Sub-theme 37-C gate (checkout integrity) and the PR cut Summary

**37-C is proven on the rebuilt runtime, and the gate found and fixed one more defect first. The old runtime charged a customer a price they were never shown, kept the cash confirmation inside the checkout, published a promotion checkout never applied, and published reviewers' full surnames. After every image was rebuilt, the same probes refused the stale price with the change listed, confirmed on the order's own route, showed no promotion, and showed "Grace P.". The new confirmation then exposed an older bug: an anonymous tracking read could see the order but none of its lines, so every guest order said "Allergen information not recorded". This had been true on /track and on the card route since 31.1. The owner chose option A, and V84 adds a SELECT-only `order_items` policy that mirrors `orders_guest_tracking`. It was proven under a NOSUPERUSER NOBYPASSRLS role: RED 4/7, then GREEN 7/0, with a break arm. The live confirmation now reads "Recorded on your order: Peanuts". The plan's spec line passes 114/0. The PR branch `phase-37-c-checkout` (`caf945c0`) is one squash commit on origin/main.**

## Performance

- **Duration:** about 3h 40m over three sessions. Part 1 started 16:54Z, Part 2 ran 17:41Z to about 19:12Z, and Part 3 (the fix, re-proof and cut) ran 18:35Z to 19:31Z. The clock times overlap because two sessions report their times differently. The plan's start time is in the evidence file header.
- **Started:** 2026-10-12T16:54:25Z
- **Completed:** 2026-10-12
- **Tasks:** 3 (Task 2 was the owner's decision)
- **Files modified:** 30 in this plan's commits (14 text files and 16 PNGs), plus this summary

## Accomplishments
- **Reconciliation, static gates and suites (Task 1, Part 1).** PR 917 was confirmed MERGED. Reconciliation by `5c626c63` was proven by content. Every gate passed except the expected OpenAPI live RED. Unit tests 1769/0, integration tests 1145/0, jest 2744/235, and the build, tsc, lint, go and vitest were all green.
- **RED on the 37-D runtime (2.1–2.2).** basket-revalidation: an order went through at the new price with no refusal. The public-a11y confirmation arms failed because there was no route. STFR-06 failed because the promotion was published. The API showed the promotion and full surnames. The runtime's identity and jar listing are on record.
- **Rebuild and parity (2.4).** Every image was rebuilt and recreated. The freshness gate caught a mode-B retag and cleared it, ending at 0 unverified. The OpenAPI gate went from rc 1 to rc 0.
- **GREEN (2.5–2.8).** Promotions are absent from both the DOM and the SSR HTML. Reviews show "First L.". The spec line passed 110/4 (the 4 failures are STFR-06's announcement and allergen-ack-race, which was the blocker). The goods re-verified include tampered prices (refused with a 409, never charged), item isolation, a 40-unit basket and idempotency. Perf: LCP stayed within 4424→4440 ms and CLS moved 0.0271→0.0258 on the promotion shop, both inside the 8000 ms / 0.1 budgets.
- **The blocker and its fix (2.9, Part 3).** V84 `order_items_guest_tracking`, written test-first. Under NOSUPERUSER NOBYPASSRLS the new IT went from 4/7 RED to 7/0 GREEN. Dropping the policy turned the same four arms red. A widening variant stayed GREEN, which shows the orders policies filter the EXISTS. RlsContractTest is 7/0. The full suites are unit 1769/0 and integration 1152/0. After rebuilding core-java: freshness PASS 4/4 with 0 unverified, V84 is in `flyway_schema_history`, and the file read out of the running jar is byte-identical to the committed one.
- **Live after V84 (3.6).** The tracking API returns `recordedAllergens` for every 2.9 order (it was null before). The spec line passed **114/0**, including allergen-ack-race 2/2. The confirmation and /track were re-shot and read "Recorded on your order".
- **PR cut (3.8).** Tag `cut/phase-37-c-checkout` = `d060d0af`. Branch `phase-37-c-checkout` = `caf945c0`: one commit above origin/main, trees equal, no upstream, not pushed. The D-04 merge-back was "Already up-to-date".

## Task Commits

1. **Task 1: static gates and full suites:** `0090392e` (docs: Part 1 evidence)
2. **Task 2: checkpoint:decision, blocking-human.** The owner answered "Proceed" (2026-10-12). No commit.
3. **Task 3: RED, rebuild, GREEN, perf, goods, the fix, the branch pointer:**
   - `7c78d56f` (test: STFR-06 looks for discounts outside the vendor's announcement)
   - `60ac0e08` (test: the regression specs send the basket they saw and clean up)
   - `4876f979` (docs: Part 2 evidence and the blocker)
   - `6d70a451` (test: the NOSUPERUSER tracking-lines IT, RED)
   - `a5eb8b18` (fix: V84)
   - `14c7869e` (docs: metrics and prose at V84 / 5734)
   - `d060d0af` (docs: Part 3 evidence and screens; this is the cut tag)
   - `efc9fa54` (docs: the 3.8 branch pointer, after the cut)
   - PR branch `phase-37-c-checkout` at `caf945c0` (a squash of `cut/phase-37-c-checkout` = `d060d0af`; local only, not pushed)

`commits: 9` is `git rev-list --count 7e618eee..efc9fa54`, measured when this summary was written. This summary's own commit and the tracking commit come after it.

## Files Created/Modified
- `core-java/src/main/resources/db/migration/V84__order_items_guest_tracking.sql`: the SELECT-only guest-tracking policy on `order_items`. Its header records the numbering and why the policy mirrors the tracking predicate.
- `core-java/src/test/java/.../GuestTrackingOrderLinesRlsIntegrationTest.java`: 7 tests run under a NOSUPERUSER NOBYPASSRLS role (a control, arms a–d, and a catalog check).
- `CustomerRecordedAllergenSetIntegrationTest.java`: the Javadoc now names its NOSUPERUSER arm.
- `frontend/e2e/storefront-flows.spec.ts`, `abuse-bounds.spec.ts`, `seller-identity.spec.ts`, `storage-keys-signout.spec.ts`:
  - STFR-06 now looks for discounts outside the announcement band.
  - abuse-bounds sends the basket expectation.
  - seller-identity and storage-keys-signout use per-run contacts and cancel by order number.
- `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md`, `README.md`: schema V84, 2664 Java tests in 386 files, 5734 in total, and "82 Flyway migrations, V1 through V84; V82/V83 reserved".
- `evidence/37-38-gate-37C.md`: Parts 1–3. `evidence/37-38-screens/`: 16 PNGs. `evidence/37-01-baseline.md`: the V84 reservation addendum. `deferred-items.md`: the abuse-bounds mobile flake.

## Decisions Made
- **The owner's answers (2026-10-12), verbatim:**
  - Task 2: "Proceed".
  - Open item 1.6: "N/A, keep window open".
  - Blocker 2.9: "**Option A** — one migration adding an `order_items` guest-tracking SELECT policy that mirrors `orders_guest_tracking` (narrowest access, DB-enforced)."
- The migration number is V84. V82 and V83 are not renumbered.
- The line policy re-checks the tracking pair, so the V18 history GUC does not open lines.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug, scoped by the owner's option A] Guest tracking read zero order lines under RLS**
- **Found during:** Task 3, by allergen-ack-race on the rebuilt runtime (evidence 2.9).
- **Issue:** `orders` has had `orders_guest_tracking` since V17, but `order_items` only had the tenant SELECT policy. An anonymous tracking read therefore saw the order and none of its lines. `recordedAllergens` came back null, and the confirmation, /track and the card route all said "Allergen information not recorded". The guarding IT missed it because it ran as the Testcontainers SUPERUSER.
- **Fix:** V84 `order_items_guest_tracking` (SELECT only; it mirrors the tracking predicate through an EXISTS that the orders RLS also filters). The new IT runs under NOSUPERUSER NOBYPASSRLS.
- **Files modified:** V84, GuestTrackingOrderLinesRlsIntegrationTest, CustomerRecordedAllergenSetIntegrationTest (Javadoc), metrics and prose.
- **Commits:** `6d70a451` (RED), `a5eb8b18` (fix), `14c7869e` (docs).

**2. [Rule 1 - Bug] Three e2e specs predated 37-33 or lacked order hygiene** (evidence 2.10 items 2–4)
- STFR-06 claimed more than 37-36's contract: it forbade "% off" inside the vendor's announcement.
- abuse-bounds sent no basket expectation, so every order was refused with 422.
- seller-identity and storage-keys-signout used fixed phones and never cancelled, so the 37-D phone cap refused them.
- All three were fixed spec-side only (`7c78d56f`, `60ac0e08`).

**3. My own instrument, corrected before the record:** the first RED run's INSERT arm was `INSERT … SELECT`, which inserts nothing when nothing is readable. I rewrote it as `VALUES` before the recorded RED.

### Not fixed (scope boundary)
- The abuse-bounds mobile bulk-reject arm failed with "element is not stable" in 3 of 7 mobile runs; desktop never failed. It is a 37-D spec flake and is recorded in deferred-items.

**Total deviations:** 2 auto-fixed issue classes plus 1 instrument correction. The RLS change was made only after the owner chose it.

## Issues Encountered
- 37-37's SUMMARY says the in-place confirmation's recorded allergens are "still there". That was false on the delivered runtime until V84. It is corrected by this plan rather than edited in place.
- The tracer gate for Task 1 could not pass before the rebuild because of the OpenAPI live RED. On the rebuilt runtime the line reads `rc=0 0 0 0 0 0`.

## Known Stubs
None introduced. The review-form notice (UI-SPEC C4) has not shipped because the frontend has no review form. `.planning/WINDOWS.md` row 43 (`unmet-truth`) stays **OPEN**, as the owner ruled.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: rls-read-widening | core-java/src/main/resources/db/migration/V84__order_items_guest_tracking.sql | An anonymous caller holding an order number AND its exact email can now read that order's LINES (it could already read the order row). This is narrower than the parent: SELECT only, the line's own order, and the same tenant. It is filtered by the orders policies, does not follow the V18 history GUC, and widens no write. Proven by arms (b)–(d) and the catalog arm. |

## Notes for the PR body (orchestrator)
- **Title shape:** "feat(37-C): checkout integrity". Plans 37-32..37-38; RWO-007, RWO-008, RWO-029, RWO-044; UXT-007/008/029/044.
- **Say plainly:** this PR carries **V84**, an RLS change found at the gate. It is a SELECT-only `order_items_guest_tracking` that mirrors `orders_guest_tracking`, chosen by the owner (option A). It fixes "Allergen information not recorded" on /track and on the card route (broken since 31.1, #901) and on the new cash confirmation. Proof is `GuestTrackingOrderLinesRlsIntegrationTest` under NOSUPERUSER NOBYPASSRLS: RED 4/7, GREEN 7/0, plus a drop-policy break arm.
- **Migration numbering:** V82/V83 are reserved for 37-45/37-48 and apply out of order after V84. `out-of-order` is on in every profile, which was measured.
- **Staging/production:** V84 applies on the next core-java rollout. It needs no secret, no data change and no extension.
- **The owner's open items carried forward:**
  - WINDOWS row 43 (the review-form notice) is OPEN.
  - The 37-31 H4 proxy-peer IP-cap question is still the owner's.
  - The 360px/130% header overflow belongs to 37-52.
  - The abuse-bounds mobile flake is in deferred-items.
- **Changelog:** the `docs/CHANGELOG.md` entry that cites the PR number goes on `phase-37-c-checkout` after the PR exists (D-03).
- **Do not add AI-attribution lines** (owner ruling).

## Next Phase Readiness
- `phase-37-c-checkout` is ready to push and open. Review rounds land on that branch.
- 37-39 is next by index.
- 37-45 and 37-48 must keep the schema prose at V84 (see the 37-01 addendum).
- Phase UAT: the human checks from Task 3 are ready.
  - Screens: the basket-changed panel, the cash confirmation (first view and after V84), and the storefront without promotions at 390/768/1280.
  - "Read the review form notice" is N/A.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-12*

## Self-Check: PASSED

- FOUND: V84__order_items_guest_tracking.sql, GuestTrackingOrderLinesRlsIntegrationTest.java, evidence/37-38-gate-37C.md (contains "phase-37-c-checkout", "check-runtime-freshness" and "LastTagTime"), evidence/37-38-screens/ (16 PNGs)
- FOUND commits (ancestors of HEAD): 0090392e, 7c78d56f, 60ac0e08, 4876f979, 6d70a451, a5eb8b18, 14c7869e, d060d0af, efc9fa54; `git rev-list --count 7e618eee..efc9fa54` = 9
- FOUND: phase-37-c-checkout caf945c0, one commit above origin/main 078c0c11, tree equal to cut/phase-37-c-checkout (d060d0af), no upstream, 0 remote heads
