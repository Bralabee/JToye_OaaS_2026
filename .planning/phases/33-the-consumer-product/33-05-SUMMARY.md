---
phase: 33-the-consumer-product
plan: 05
subsystem: database
tags: [geo, postcode, rls, backfill, validation, asvs, testcontainers, runtime-parity, control-arm]

requires:
  - phase: 33-the-consumer-product
    provides: "33-02's PostcodeGeocoder, the postcode_centroid table (1,748,230 rows live) and the whole jtoye.geo.* config block"
  - phase: 33-the-consumer-product
    provides: "33-00's CA-1 control arm — the pre-population capture this plan expires and closes"
provides:
  - "shops.latitude/longitude POPULATED on the live dev database — 3 of 3 published shops, proven as the delivered runtime"
  - "Geocoding on the API write path (createShop + updateShop), with a documented precedence rule"
  - "Range validation on CreateShopRequest.latitude/longitude — an unvalidated latitude 999 used to persist"
  - "ShopCoordinateBackfill — idempotent, tenant-looped, RLS-aware data migration for pre-existing rows"
  - "scripts/check-live-shop-coordinates.sh — closes CA-1 against the running stack, as both database roles"
  - "DemoDataSeederAddressTest — the guard that makes the seeder-to-fixture link load-bearing"
affects: [33-06, 33-07]

tech-stack:
  added: []
  patterns:
    - "A backfill's write is a bulk update BECAUSE the row count is the RLS proof — a managed-entity flush would hide the same fact inside a swallowed exception"
    - "A runtime gate asserts a RELATION with an explicit denominator, never a census — a census reds on legitimate new data and a gate that reds on legitimate data gets ignored"
    - "An over-strict criterion is as much a defect as a vacuous one; replace it with a form that cannot fire on correct data, and RECORD the substitution"
    - "Startup data migrations hook ApplicationReadyEvent, not ApplicationRunner — that is the only ordering guarantee that does not race two unordered runners"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/geo/ShopCoordinateBackfill.java
    - core-java/src/test/java/uk/jtoye/core/geo/ShopCoordinateBackfillIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/shop/ShopServiceGeocodeTest.java
    - core-java/src/test/java/uk/jtoye/core/dev/DemoDataSeederAddressTest.java
    - scripts/check-live-shop-coordinates.sh
  modified:
    - core-java/src/main/java/uk/jtoye/core/shop/ShopService.java
    - core-java/src/main/java/uk/jtoye/core/shop/dto/CreateShopRequest.java
    - core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
    - core-java/src/test/java/uk/jtoye/core/shop/ShopServiceTest.java
    - scripts/gates/gate-enforcement.conf
    - .planning/phases/33-the-consumer-product/33-CONTROL-ARMS.md
    - docs/api/openapi-snapshot.json
    - docs/metrics.json

key-decisions:
  - "PRECEDENCE: the postcode is authoritative, a client coordinate is a FALLBACK never an override — and an update that re-geocodes to a miss must never NULL a coordinate the shop already had"
  - "SE15 4BW, not one of the on-street SE15 4Q* units: adding a 4Q* to the 7-row fixture would take it to 8 and destroy 33-02's row-count-mismatch control arm"
  - "The backfill writes with a bulk JPQL UPDATE and therefore generates no shops_aud revisions — traded deliberately, because the returned row count IS the RLS proof"
  - "The live gate asserts the narrower relation (postcode present in the reference table) plus the three curated slugs by name; the plan's wider predicate would red on a legitimate Northern Ireland vendor"
  - "The two address-less demo shops are load-bearing negative controls and are commented as such"

patterns-established:
  - "Every absence assertion carries its denominator in the SAME query — an emptied table and a broken filter both report a clean zero otherwise"
  - "Prove a database restore BY CONTENT: hash the rows before the arms, compare after each one, and re-run the clean direction LAST"
  - "A NOSUPERUSER downgrade needs its own control arm — the arm showing the SAME call succeeds without the downgrade is what proves the zero came from RLS"

requirements-completed: []

duration: 3h
completed: 2026-08-09
---

# Phase 33 Plan 05: Populate the Coordinates, and Close the Control Arm — Summary

**Every published shop on the live dev database now holds a real WGS84 coordinate — read back out of the running Postgres as two different database roles, with the control arm that made the claim falsifiable now closed against a runtime rebuilt from this branch.**

CUST-01 is **not** closed by this plan: 33-06 (the radius query) and 33-07 (the located journey) still stand on it. This is link 3 of #460's five-link chain — the one the roadmap does not name, and the one without which every downstream locality criterion is vacuous.

## Performance

- **Duration:** ~3h
- **Tasks:** 4 of 4
- **Commits:** 6 (one is a deviation fix the full suite caught)
- **Files:** 5 created, 8 modified

## The number that matters

```
                        BEFORE (CA-1, 2026-08-08)      AFTER (2026-08-09, rebuilt runtime)
  as superuser jtoye    5 total | 0 with lat | 3 pub    5 total | 3 with lat | 3 pub
  as jtoye_app, no GUC  3 total | 0 with lat | 3 pub    3 total | 3 with lat | 3 pub
```

Both roles, because they disagree by design: `shops_public_read` reduces to `published = true`
with no tenant GUC, so the app role sees 3 of 5 and cannot see either unpublished shop.
Recording `3|3|3` alone would look identical to a backfill that stopped two rows early.

## What shipped

| | |
|---|---|
| `ShopService` | Geocodes on create AND update, through 33-02's `PostcodeGeocoder` — the same implementation the seeder uses |
| `CreateShopRequest` | `@DecimalMin`/`@DecimalMax` on both axes. Before this, `latitude: 999` returned **201 Created** |
| `DemoDataSeeder` | Geocodes via the shared implementation; no coordinate parameters, no literal lat/lon; the non-existent postcode corrected |
| `ShopCoordinateBackfill` | Idempotent, tenant-looped, RLS-aware; runs at `ApplicationReadyEvent` |
| `check-live-shop-coordinates.sh` | Re-runs CA-1's queries against the running stack; exits 2 (VOID) over a dead one |
| `DemoDataSeederAddressTest` | Makes the seeder-to-fixture link a test rather than a comment |

## Three findings worth more than the feature

### 1. The write path accepted absurd coordinates, and had done all along

`CreateShopRequest.latitude`/`.longitude` carried **no** validation, and the generated
`ShopMapperImpl` writes both on create and update. Measured before the fix: `POST /shops` with
`latitude: 999` returned **201 Created**. That is threat T-33-05-01 realised, not hypothetical —
and once a distance sort exists, a shop at latitude 999 or `(0,0)` does not merely look wrong,
it outranks every real shop.

The RED is recorded verbatim in commit `88723239`: `Status expected:<400> but was:<201>`, twice.

### 2. `SE15 4QA` — a postcode in our own seeded data that does not exist

Well-formed, satisfies every plausible UK-postcode regex, and absent from **both** reference
datasets (no row in the committed 1,748,230-row Code-Point Open artefact, `SE15 5BS` as the
control returning one; HTTP 404 from ONSPD via `api.postcodes.io`). Nothing failed. The shop kept
its storefront and would have held NULL coordinates forever — vanishing from every distance
result introduced by the change meant to *fix* locality. A regression by omission, caused by the
fix.

It is now `SE15 4BW`, and the correction is guarded by a test rather than by a comment saying
"do not restore this".

### 3. The full integration suite caught what four per-task verifies could not

Adding the two bean-validation annotations changes the **published API contract**.
`OpenApiSnapshotTest` failed at 513 tests / 1 failure — a review-gated snapshot doing exactly its
job. The diff is four lines and nothing else:

```
  latitude    minimum -90    maximum 90
  longitude   minimum -180   maximum 180
```

This is the agent-readiness contract working in both directions: the machine-readable spec now
*tells* a caller the valid range instead of letting it discover the bound by getting a 400. It
also validates the recorded rule that a change to a shared service demands the whole suite, not
just the plan's own tests.

## The precedence rule, stated because it must not be inferred

**The postcode is authoritative; a client-supplied coordinate is a FALLBACK, never an override.**

1. Address geocodes → that centroid wins, overwriting anything the client sent.
2. It does not → the client's own coordinate stands, but only because it is now range-validated.
3. Neither → the previously persisted coordinate stands (update path).
4. None of the three → `null`. **Never `(0,0)`.**

A vendor cannot pin a coordinate more precise than their postcode centroid while their postcode
resolves. Accepted: D-1 already scopes the platform to ~100 m centroid accuracy, and a
vendor-typed coordinate has no provenance — a dropped minus sign puts a Peckham kitchen in the
North Sea and nothing in the product can tell. What the code did *before* was the worst of both:
silently accept anything, and geocode nothing.

## Control arms — every criterion observed failing

| Criterion | Break | Result |
|---|---|---|
| Out-of-range coordinates are refused | today's tree | `Status expected:<400> but was:<201>`, x2; DTO violations 0 |
| Create geocodes from the address | remove the call in `createShop` | 2 arms fail |
| Update re-geocodes | remove the call in `updateShop` | 1 arm fails |
| The seeder shares one geocoder | hardcode `setLatitude(51.47)` | literal-coordinate count 0 → 1 |
| The bad postcode is gone | restore it in the seeder | count 0 → 1, and 2 guard-test arms fail |
| The backfill is RLS-aware | remove `TenantContext.set` at `run()`'s own layer | `rows updated by run()` 0, expected >= 1 |
| …and the zero is caused by RLS | skip the NOSUPERUSER downgrade | the SAME unpinned call writes 1 row |
| Shops have coordinates in the RUNTIME | the pre-deployment runtime | A-1 2 of 2 NULL, A-3 0 of 3, `rc=1`; diagnostic named `peckham-jollof-co postcode=SE154QA` |
| The check cannot pass over a dead stack | `docker compose stop postgres` | **rc=2**, not 0 |
| No published shop lacks a coordinate | NULL one directly (`UPDATE 1`) | A-1 and A-3 both fire, `rc=1` |
| No shop sits at Null Island | move one to `(0,0)` (`UPDATE 1`) | A-4 fires, `rc=1` |
| The gate is accounted for in CI | delete its exemption entry | `rc=1` naming the script |

Every database restore verified **by content** — the five-row `slug|latitude|longitude` listing
hashed before the arms and compared after each one, `sha256 d1893eac…` identical at baseline and
after both restores. Every source restore verified by `git hash-object`. **The clean direction was
re-run LAST in every case**, because the restore is the part nothing watches.

## Deviations from Plan

### 1. `SE15 4BW`, not one of the plan's `SE15 4Q*` units — the plan's two constraints conflict

The plan required both *"a real Bellenden Road postcode — SE15 4QJ, 4QL, 4QN, 4QR, 4QS, 4QW or
4QY"* **and** *"it must also be present in 33-02's test fixture"*. No postcode satisfies both. All
seven 4Q* units are real (verified present in the live 1.7M-row table), and **none** is in the
fixture; the fixture carries `SE15 4BW`, which `33-02`'s own README designates as *"the
replacement 33-05 needs"*.

Adding a 4Q* row to the fixture would take it from 7 rows to 8 — and `33-02`'s
`rowCountMismatchAborts` control arm works precisely by pairing the 7-row fixture with a manifest
claiming 8. Growing the fixture would silently gut a working control arm to satisfy a wording
preference. Incremental Betterment: the upstream plan that owns the file made the choice, with a
recorded reason, and it stands.

Cost, stated plainly: `SE15 4BW` is the nearest real unit to Bellenden Road (~130 m from
`SE15 4QY`), not a Bellenden Road unit itself, so the seeded street and postcode are neighbours
rather than an exact pair. This is fictional demo data for a fictional shop; what matters is that
the postcode is **real** and lands in Peckham, and both are asserted.

### 2. The live gate asserts a narrower relation than the plan specified — recorded, not substituted silently

The plan's A-1 covered *every published shop whose address yields a postcode at all*. That is
strictly stronger, and it does fail on the pre-change tree — but **it reds on correct data**.
Code-Point Open is GB-only, so a Northern Ireland vendor's postcode is real, extractable, and
permanently absent; `33-02`'s SOURCE.md records that as a licence-containment choice, not a bug,
and such a vendor keeps their storefront while being absent from distance results. The wider
predicate would red the platform for behaving exactly as designed — the same species of
brittleness the plan itself rejected in `total = 5`, one level deeper.

So: the assertion is the narrower relation (postcode present in the reference table), the wider
figure is **printed alongside** (3 / 0 today, nothing hidden), any published shop whose postcode
is not in the table is **listed by slug** as a diagnostic, and a third limb (A-3) keeps the
plan's full original strength on the three curated demo slugs by name — where the false-positive
risk is zero and where a bad seeded postcode is exactly what shows.

### 3. The backfill generates no `shops_aud` revisions

The plan expected a revision spike and asked for it to be noted as expected. The implementation
writes with a **bulk JPQL update**, which bypasses Envers. Deliberate: a bulk update returns the
affected row count and never throws on zero, and that count is the entire RLS proof the plan
demands (*"assert the returned COUNT, never the exit status"*). A managed-entity flush raises a
stale-state exception on a zero-row update instead, so the same proof would rest on a swallowed
exception and a poisoned persistence context. Envers still audits the `address` the coordinate is
derived from. Recorded in the class comment so nobody "repairs" it back.

### 4. Files touched outside `files_modified`

| File | Why |
|---|---|
| `core-java/.../shop/ShopServiceTest.java` | Rule 3 — the `ShopService` constructor gained a parameter; the existing suite would not compile |
| `core-java/.../dev/DemoDataSeederAddressTest.java` | Rule 2 — without it, "the replacement is present in the fixture" is a convention, not a control |
| `docs/api/openapi-snapshot.json` | Rule 3 — the new bounds change the published contract; regenerated by `:core-java:updateOpenApiSnapshot`, never hand-edited |
| `docs/metrics.json` | Generated artefact no plan owns; regenerated **by script** |

### 5. `roadmap.update-plan-progress` mangled the table row

Run before this SUMMARY existed, the verb both left the count at 5/8 (it counts SUMMARY files on
disk) and rewrote the row's trailing cell as `In Progress|  ` instead of `In Progress | — `.
Reverted and re-run **after** the SUMMARY landed. Recorded because the corruption is cosmetic,
silent, and would otherwise be attributed to a human edit.

## Suites and gates

- **Unit:** 141 classes, **1017 tests**, 0 failures, 1 skipped (33-02 baseline 138 / 997 — the delta is exactly this plan's 26 new tests, 16 + 4 + 6)
- **Integration:** **513 tests**, 1 skipped — one failure (`OpenApiSnapshotTest`) found, fixed and re-verified
- `check-live-shop-coordinates` rc=0 · `check-runtime-freshness` rc=0 (4/4 FRESH, proven by `.Metadata.LastTagTime`) · `check-gate-enforcement` rc=0 (32 gates / 5 exempt) · `docs-freshness` rc=0 (2551 → 2577) · `check-branch-behind-base` rc=0 (0 behind `origin/main`) · `check-alert-metrics` rc=0 after the mandatory `seed-order-metric.sh`
- **Pre-existing red, NOT this plan's:** `check-doc-metrics` rc=1 and `check-claims` rc=1. Verified at `f11e3701` before any change here: `CLAUDE.md` claimed 1461 / 2509 / V60 while `docs/metrics.json` already held 1484 / 2551 / 61. Owned by 33-07 Task 4.

## Notes for the next plan

- **33-06 can now be falsified.** Distance ordering over an all-NULL column returned nothing before and nothing after; three published shops now hold real coordinates in Peckham (x2) and Brixton, ~3.1 km apart, so an ordering assertion has something to be wrong about.
- **`tenant-b-probe` and `unsorted-legacy-items` must keep NULL coordinates.** They are the only rows on the live database exercising the unresolvable-address branch, and they are unpublished — so any criterion about them **must name the superuser** or it passes vacuously.
- **Do not assert a shop census.** `check-live-shop-coordinates.sh` deliberately prints 5/3/3 as context. An E2E run creating a sixth shop is legitimate data.
- **Northern Ireland still does not geocode**, permanently. A NI vendor keeps their storefront and is absent from distance results — by design, not a bug to fix in 33-06.
- `ShopCoordinateBackfill.run()` is safe to call repeatedly; a second pass reports `updated=0`.

## Self-Check: PASSED

All five created files exist on disk; all six commits (`88723239`, `a73d8668`, `47361d11`,
`9e5076bc`, `a07166cd`, `03f57ff0`) resolve in `git log`.
