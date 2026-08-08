---
phase: 33-the-consumer-product
plan: 02
subsystem: database
tags: [geo, postcode, flyway, rls, testcontainers, copy, ci-gate, leakproof]

requires:
  - phase: 33-the-consumer-product
    provides: "33-01's postcode-centroids.csv.gz (1,748,230 rows) and its generated SOURCE.md provenance"
provides:
  - "V61 postcode_centroid — DDL only, plus the partial btree on shops(latitude, longitude) 33-06 prefilters with"
  - "PostcodeGeocoder.locate — the single offline postcode->coordinate implementation, table-authoritative"
  - "GeoBounds.boxAround — radius to a leakproof, pole-guarded lat/lon box"
  - "PostcodeCentroidImporter — COPY-based, idempotent, ON by default, fails startup rather than serving a partial table"
  - "The whole jtoye.geo.* config block, including the two keys 33-05 and 33-06 read"
  - "scripts/check-no-create-extension.sh — CI-wired, with the one measured exemption"
affects: [33-05, 33-06, 33-07]

tech-stack:
  added: []
  patterns:
    - "A committed artefact's expected row count is READ OUT of its generated manifest, never copied into code"
    - "An exemption table that stops matching is a VOID, not a pass — a stale exemption is a silent hole"
    - "A migration must discuss a forbidden statement without spelling it, because the gate cannot tell comment from code"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V61__postcode_centroid.sql
    - core-java/src/main/java/uk/jtoye/core/geo/PostcodeCentroid.java
    - core-java/src/main/java/uk/jtoye/core/geo/PostcodeCentroidRepository.java
    - core-java/src/main/java/uk/jtoye/core/geo/PostcodeCentroidImporter.java
    - core-java/src/main/java/uk/jtoye/core/geo/PostcodeGeocoder.java
    - core-java/src/main/java/uk/jtoye/core/geo/GeoBounds.java
    - core-java/src/test/java/uk/jtoye/core/geo/PostcodeGeocoderTest.java
    - core-java/src/test/java/uk/jtoye/core/geo/GeoBoundsTest.java
    - core-java/src/test/java/uk/jtoye/core/geo/PostcodeCentroidImportIntegrationTest.java
    - core-java/src/test/resources/geo/postcode-centroids-fixture.csv
    - core-java/src/test/resources/geo/postcode-centroids-nullisland.csv
    - core-java/src/test/resources/geo/fixture-SOURCE.md
    - core-java/src/test/resources/geo/nullisland-SOURCE.md
    - core-java/src/test/resources/geo/README.md
    - scripts/check-no-create-extension.sh
  modified:
    - core-java/src/main/resources/application.yml
    - core-java/src/test/resources/application-test.yml
    - core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java
    - .github/workflows/ci-cd.yaml
    - docs/metrics.json
    - k8s/LOCAL.md

key-decisions:
  - "The TABLE is the authority, not a regex — SE15 4QA satisfies every plausible pattern, is in our own seeded data, and returns 404 from api.postcodes.io"
  - "jtoye.geo.postcode-import.enabled defaults TRUE, asserted by RESOLVING it under the dev profile rather than by reading the comment that claims it"
  - "The expected row count is parsed from the generated SOURCE.md, so regenerating the dataset updates data and assertion atomically"
  - "The no-extension gate carries ONE exemption (V1/uuid-ossp) with a measurement, because the plan's 'exit non-zero on ANY hit' would have been permanently red on a correct tree"
  - "postcode_centroid exempted from RLS BY ADDITION — with no tenant_id there is no predicate to write, so a FORCE'd policy would return zero rows to everyone"

patterns-established:
  - "Flyway substitutes placeholders inside migration SQL INCLUDING COMMENTS — a comment naming a property in dollar-brace form takes the whole application down at startup"
  - "Prove a data-loading path against the REAL artefact on the delivered runtime; a fixture proves the mechanism, not the scale assertion"

requirements-completed: []

duration: 2h
completed: 2026-08-08
---

# Phase 33 Plan 02: Schema + Java Surface — Summary

**A UK postcode resolves to a WGS84 coordinate offline, with no API key and no runtime network call — proven on the delivered runtime at 1,748,230 rows, not just against a 7-row fixture.**

CUST-01 is **not** closed by this plan: it needs 33-05 (write path), 33-06 (radius query) and 33-07 (the located journey). This is the substrate all three stand on.

## What shipped

| | |
|---|---|
| `V61` | `postcode_centroid` DDL + the partial btree on `shops(latitude, longitude)`. **No data** — a ~46 MB migration would run on every Testcontainers container |
| `PostcodeGeocoder` | Permissive trailing-postcode regex, primary-key lookup, `Optional` out. Never `(0,0)`, never throws |
| `GeoBounds` | Radius → leakproof lat/lon box, pole-guarded, containment-biased |
| `PostcodeCentroidImporter` | `COPY` into a TEMP staging table then promote; idempotent; **ON by default** |
| `jtoye.geo.*` | The whole block, including `coordinate-backfill.enabled` (33-05) and `default-radius-km`/`max-radius-km` (33-06) |
| `check-no-create-extension.sh` | CI-wired in `ops-contracts`, with one measured exemption |

## The design claim, and why the obvious alternative is wrong

**The table is the authority, not a regex.** Validating with a "correct" UK postcode pattern is wrong in *both* directions, and this repo contains a live example of each:

- **It accepts what does not exist.** `SE15 4QA` is in our own seeded demo data, satisfies every plausible pattern, and is not a real postcode — `api.postcodes.io` returns **404** for it while returning 200 for `SE15 5BS` and `SW9 8PS` (checked 2026-08-08). A regex-validating geocoder accepts it and then has to invent a coordinate.
- **It rejects what does exist.** A pattern stricter than the dataset turns away real vendors at signup, with no way to argue.

So the regex only finds a candidate at the end of the string; the primary-key lookup decides. `SE15 4QA`'s absence from the fixture is **asserted**, not left to convention — adding it would turn the unknown-postcode test green by making the product wrong.

## Verified on the delivered runtime, not only in tests

The fixture proves the mechanism at 7 rows. It cannot prove the scale assertion, the gzip path, or `COPY` against a real dataset. So core-java was rebuilt and recreated, and the values read back out of the running database:

```
Flyway            Migrating schema "public" to version "61 - postcode centroid"  → v61
Importer          Imported 1748230 postcode centroids from classpath:geo/postcode-centroids.csv.gz
                  (~18 s)
restart           postcode_centroid already holds 1748230 rows, matching
                  classpath:geo/SOURCE.md — skipping import        ← idempotent, proven

rows                = 1748230        (SOURCE.md records 1748230)
null island rows    = 0
distinct keys       = 1748230        (no duplicates)
SE155BS             = 51.472435, -0.070047
SE154QA             = 0             (the negative control holds in production data too)
lat range           = 49.895171 .. 60.800694   (Isles of Scilly → Shetland: real GB coverage)
idx_shops_lat_lon   = 1
RLS on table        = false          (correctly exempt)
```

One scare checked rather than assumed: startup logs a Spring Data *"could not safely identify store assignment"* warning for `PostcodeCentroidRepository`. It fires for **all 24** repositories and the scan resolved **24 JPA / 0 Redis** — pre-existing and benign, not a mis-wired repository.

## Control arms — every criterion observed failing

| Criterion | Break | Result |
|---|---|---|
| Unknown postcode yields empty | add `SE154QA` to the fixture | 2 tests fail — the unknown-postcode case **and** the fixture guard |
| Import is ON by default in dev | flip the dev default to `false` | the resolved-property assertion fails, alone |
| Loader rejects Null Island | inject a `(0,0)` row | aborts; table left **empty**, not partially loaded |
| Row-count mismatch aborts | manifest claims 8, file has 7 | aborts naming "partial postcode table" |
| Unreadable manifest is VOID | point the manifest at the CSV | aborts rather than defaulting |
| No migration creates an extension | add the statement to V61 / a new migration | rc=1 both times |
| The extension gate fails CLOSED | empty dir / missing dir | rc=**2**, never 0 |
| The exemption still describes the tree | change V1's extension to `pgcrypto` | rc=2 VOID, naming the drift |
| The RLS sweep still bites | add a public table with no RLS | fails naming `rls_arm_probe` |

Every restore verified by content (`git hash-object`), and the clean state asserted again afterwards.

## Three defects the tests caught before they could ship

**1. Flyway substitutes placeholders inside migration SQL — including comments.** The V61 comment explaining which role Flyway runs as named the property in dollar-brace form, and the whole application failed to start with *"No value provided for placeholder"*. Fixed — then reproduced a **second time** by the note explaining the first one, which quoted the very syntax it was warning about.

**2. The manifest regex ignored the `**` emphasis markers** around the markdown cell, so it matched neither the fixture **nor the real `SOURCE.md`**. That would have aborted startup in every environment; the integration test caught it before it ran anywhere.

**3. `docker compose`/Flyway aside, the same shape recurred a third time** in the gate: the scan is case-insensitive and cannot tell a statement from a comment quoting one, so V61's quoted PostgreSQL error had to be wrapped across two lines. Not skipping comments is deliberate — a commented-out extension statement still deserves a human look.

## The plan was wrong about the extension gate, and the correction matters

The plan specified *"exit non-zero on ANY hit"* and asserted *"measured 0 hits today, so the gate starts green"*. The real tree has one:

```
V1__base_schema.sql:6   CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
```

Written as specified the gate would have been **permanently red on a correct tree** — and this repo already records that a permanently-red required job is worse than none, because it teaches people to add `|| true`. Deleting the line to go green would be worse: a **fresh** database loses uuid-ossp and V1 itself fails. That is exactly the *"expected-0 that is actually 1, whose fix causes an outage"* shape.

So I measured why it works, live and rolled back:

```
BEGIN; SET ROLE jtoye_app;
SELECT rolsuper …                            →  f
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
NOTICE:  extension "uuid-ossp" already exists, skipping
CREATE EXTENSION                             →  SUCCEEDED as jtoye_app
```

PostgreSQL checks the privilege **only when it actually has to create**. Where the extension is already present, `IF NOT EXISTS` skips and an unprivileged role sails through. `uuid-ossp` is pre-provisioned here; `cube` and `earthdistance` are not, which is why the identical statement for them is denied.

The true invariant — *no migration may create an extension that is not already present everywhere* — is undecidable from a text scan. The gate enforces the conservative checkable form with one exemption by file **and** extension name, in the same shape as `EXEMPT_TABLES`, carrying that measurement as its justification. It **VOIDs** if the exemption stops matching, because an exemption that no longer describes the tree is a silent hole.

## Suites

- **Unit:** 138 classes, **997 tests**, 0 failures (26 new: 7 `GeoBounds`, 19 geocoder)
- **Integration:** 117 classes, **507 tests**, 0 failures (9 new import tests)
- `docs/metrics.json` regenerated **by script**: java methods 1461 → 1484, files 248 → 251, total 2528 → 2551
- Gate loop **29 of 32** rc=0

## Deviations

- **`k8s/LOCAL.md` (outside `files_modified`)** — the `jtoye.geo.*` block shifted `application.yml`, breaking two citations that 33-04 had just repaired. Same self-inflicted class, caught by the same gate, repointed 351 → 386 and 350-353 → 385-388.
- **`HANDOFF.md` gate count** 31 → 32, because this plan adds a gate script. H-1 exists to catch exactly that drift.
- **The extension gate carries an exemption**, against the plan's literal "any hit" wording — see above.
- The `docs/metrics.json` regeneration is expected; it is a generated artefact no plan owns.

## Notes for the next plan

- **33-05 and 33-06 must READ `jtoye.geo.*`, not add to it.** This plan owns that block; they share a wave and two plans editing the file is a merge conflict by design.
- `PostcodeGeocoder.locate` returns `Optional<PostcodeGeocoder.Coordinate>`; `GeoBounds.boxAround(lat, lon, radiusKm)` returns a record with `contains()`.
- **Northern Ireland does not geocode**, permanently — Code-Point Open is GB-only, and a NI vendor keeps their storefront but is absent from distance-ranked results. That is a licence-containment choice recorded in `SOURCE.md`, not a bug to fix in 33-05.
- The dev database now holds 1,748,230 rows; a restart is a no-op, so no plan needs to re-import.

## Self-Check: PASSED

Task 1 `test` rc=0 (26 tests) · Task 2 `integrationTest` rc=0 (9 tests) + radius/backfill greps ≥2/≥1 · Task 3 `RlsContractTest` rc=0, extension gate rc=0, CI wiring 2 refs, `check-gate-enforcement` rc=0, `docs-freshness` rc=0 · full unit 997/0 · full integration 507/0 · `check-runtime-freshness` rc=0 · branch 0 behind `origin/main`.
