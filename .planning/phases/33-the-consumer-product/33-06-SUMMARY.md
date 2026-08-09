---
phase: 33-the-consumer-product
plan: 06
subsystem: api
tags: [geo, haversine, rls, leakproof, storefront, asvs, rfc7807, openapi, runtime-parity, control-arm]

requires:
  - phase: 33-the-consumer-product
    provides: "33-02's GeoBounds.boxAround and the jtoye.geo.default-radius-km / max-radius-km config keys"
  - phase: 33-the-consumer-product
    provides: "33-05's populated shops.latitude/longitude on the live dev database — without it this plan's criteria are vacuous"
provides:
  - "ShopRepository.findPublishedNear — native asin-haversine over a leakproof bounding-box prefilter, with an explicit countQuery"
  - "ShopWithDistance — the projection carrying the SQL-computed distance"
  - "GET /public/shops?lat=&lon=&radiusKm= — distance-ordered, radius-filtered, validated, additive"
  - "PublicShopDto.distanceKm — nullable, the same number the ordering used"
  - "scripts/check-openapi-snapshot-fresh.sh — the committed contract checked against the RUNNING service, failing closed at exit 2"
affects: [33-07]

tech-stack:
  added: []
  patterns:
    - "A Spring Data page whose size EXCEEDS the row count never runs the countQuery — a total assertion in that shape is measuring the content it already asserted"
    - "The acos domain-error hazard is LATITUDE-DEPENDENT: 4.0% of latitudes overflow, so a coincident-point arm at an arbitrary coordinate cannot distinguish asin from acos"
    - "A contract-vs-runtime gate must be SUBSUMPTION, not equality, when the two are generated under different Spring profiles — equality would be permanently red on a correct tree"
    - "Wiring a runtime gate into the one workflow that has a runtime beats exempting it; check-gate-enforcement short-circuits on refs > 0 before it reads the exemption table"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/shop/ShopWithDistance.java
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontDistanceIntegrationTest.java
    - scripts/check-openapi-snapshot-fresh.sh
  modified:
    - core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java
    - core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicShopDto.java
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java
    - scripts/gates/gate-enforcement.conf
    - .github/workflows/e2e-nightly.yml
    - docs/api/openapi-snapshot.json
    - docs/metrics.json

key-decisions:
  - "asin haversine, never the law of cosines — and the hazard was MEASURED: 3,600 of 90,001 sampled latitudes make acos raise 'input is out of range' on a coincident point"
  - "The radius predicate lives in a derived table over the box prefilter, so the ordering formula and the filtering formula are ONE expression that cannot drift"
  - "ShopWithDistance carries id + slug + distanceKm, not the DTO's fields: shops.opening_hours is jsonb and a second shop-mapping path would drift"
  - "The ceiling REFUSES, never clamps — a clamp returns 200 with the wrong result set and tells the caller nothing"
  - "q combined with lat/lon is a typed 400, not a silently-ignored parameter"
  - "The freshness gate asserts SUBSUMPTION (committed is a sub-tree of live), because compose runs the dev profile and the snapshot is generated under test"
  - "The gate is WIRED into e2e-nightly.yml rather than exempted — that workflow already brings the stack up and waits on localhost:9090"

patterns-established:
  - "Run the break arm against the arm you just wrote, not only against the code: two of this plan's own assertions were incapable of failing and neither was visible from the passing side"
  - "When a break arm passes, the next step is to MEASURE why — the acos arm's silence was a property of the fixture's latitude, not of the query"

requirements-completed: []

duration: 2h
completed: 2026-08-09
---

# Phase 33 Plan 06: Make the Coordinates Do Something — Summary

**A customer standing in Peckham and a customer standing in Brixton are now shown the same three
shops in opposite orders, by a distance the database computed — read out of the rebuilt runtime,
not out of a test.**

```
  from Peckham (51.4700, -0.0700)     from Brixton (51.4626, -0.1132)
    mama-ades-kitchen      0.271 km     brixton-village-grill   0.097 km
    peckham-jollof-co      0.417 km     peckham-jollof-co       2.813 km
    brixton-village-grill  3.010 km     mama-ades-kitchen       3.183 km

  with no coordinate at all: the same three shops, name-ascending, distanceKm null — unchanged
```

The inversion is the point. A list that is merely *sorted* looks identical to one sorted by name,
by insertion order, or by nothing; a list that **reorders when the caller moves** can only be
driven by the caller's position.

CUST-01 is **not** closed by this plan — 33-07 (the located journey) still stands on it. This is
link 5 of #460's chain, and CA-6 is the arm it expires: distance logic measured **0 files** on
2026-08-08.

## Performance

- **Duration:** ~2h
- **Tasks:** 3 of 3
- **Commits:** 5
- **Files:** 3 created, 9 modified

## What shipped

| | |
|---|---|
| `ShopRepository.findPublishedNear` | Native `asin` haversine over a leakproof `BETWEEN` prefilter, exact radius in a derived table, explicit `countQuery`, named parameters only |
| `ShopWithDistance` | The projection carrying the SQL-computed distance — never recomputed in Java |
| `GET /public/shops` | `lat` / `lon` / `radiusKm`, additive; typed RFC 7807 400s; the platform default and ceiling read from `33-02`'s config |
| `PublicShopDto.distanceKm` | Nullable, so the OpenAPI contract has one shape |
| `check-openapi-snapshot-fresh.sh` | The committed contract vs the **running** service, exit 2 over a dead one, wired into `e2e-nightly.yml` |

## Two of my own assertions could not fail, and neither was visible from the passing side

This is the finding worth more than the feature. Both arms were written, run, and **green** —
and both were measuring nothing.

### 1. The countQuery arm never ran the countQuery

The break the plan specifies — delete `published = true` from the `countQuery` **only** — left the
suite `BUILD SUCCESSFUL`. The unpublished shop sits on the nearest shop's exact coordinates, so
nothing but that predicate could have been hiding it, and the total still read 3.

The cause is `PageableExecutionUtils.getPage`: when the offset is 0 **and the page size exceeds
the number of rows returned**, Spring Data never issues the count query at all and reports
`content.size()`. Three rows in a page of twenty is precisely that shape. The assertion was
re-measuring the content it had already asserted, one line above.

Fixed by requesting page size 2 so the page fills. Re-running the identical break now fires:

```
  expected: 3L
   but was: 4L        x2, with the page CONTENT still correct
```

That is the leak exactly as the threat register describes it (T-33-06-03) — invisible to any
assertion that only inspects content.

### 2. The acos arm could not fire at the fixture's latitude

The break — swap the `asin` haversine for the unclamped law of cosines — also left the suite
green. Not because the hazard is imaginary, but because it is **latitude-dependent**. Measured on
the live PostgreSQL 15, sampling 90,001 latitudes from 0 to 90 at 0.001-degree steps:

```
  total latitudes sampled                                       90001
  ...where sin(lat)^2 + cos(lat)^2 * cos(0) > 1.0 in float8      3600      (4.0%)
  the fixture's own latitude, 51.4710                            exactly 1.0
```

So 96% of coordinates cannot tell the two formulations apart, and the one I had picked was among
them. A fixture was added at **54.900003** — the first latitude at or above 54.9 that overflows,
395 km from the query point so it cannot perturb the 5 km and 8 km arms. Live, at that latitude:

```
  acos form   ERROR:  input is out of range
  asin form   0
```

The same break now fires with that exact error in the report. The hazard is real, it is reachable
at a plausible GB coordinate, and an anonymous caller triggers it by standing on a shop's own
postcode centroid.

**The general lesson:** when a break arm passes, the next step is not to accept the pass — it is to
measure *why* it passed. Both of these looked like confirmations of correct code.

## Why the query has the shape it has

- **`asin`, not `acos`.** See above. If `acos` is ever reintroduced it must be wrapped
  `LEAST(1.0, GREATEST(-1.0, ...))`, and the comment in `ShopRepository` says so.
- **The bounding box.** `sin`, `cos` and `radians` are all `proleakproof = f`, so PostgreSQL will
  not push a haversine below the row-security barrier; `float8` comparisons are leakproof and can
  use V61's partial `shops(latitude, longitude)` btree. The box is computed in Java by
  `GeoBounds.boxAround` and passed as four more named parameters.
- **The radius predicate is in a derived table, not repeated in the WHERE clause.** A box contains
  its circle, so a shop at the corner is `r*sqrt(2)` away and must still be excluded — the CORNER
  fixture sits at ~1.27r and is the only one that distinguishes "filtered by a box" from "filtered
  by a radius". Writing the expression twice would let the ordering formula and the filtering
  formula drift apart.
- **`ORDER BY distance_km, id`.** Without the tiebreak, equidistant shops make page 2 repeat or
  skip a row.
- **`published = true` in the `countQuery` too** — see the finding above.

## The projection carries an id, not the DTO's fields — and why

The plan asked for a projection "exposing the `Shop` fields the DTO needs". That cannot be done:
`shops.opening_hours` is `jsonb`, mapped on the entity with `@JdbcTypeCode(SqlTypes.JSON)`, and a
native tuple hands back raw JSON that a `Map<String, String>` getter cannot convert. Reproducing
Hibernate's JSON handling in a second place would give the storefront **two** shop-mapping paths
that can disagree — and the located one would be the one quietly missing opening hours. A
regression by omission, caused by the fix, which is the failure class this project's own doctrine
names.

So the projection carries `id`, `slug` and `distanceKm`, and the service resolves the entities
through the **same** `toPublicShopDto` the unlocated listing uses. One extra query per page,
bounded by the page size; in exchange a located result differs from an unlocated one by exactly
one field. Asserted directly: the located response carries the full `address`, not a reduced DTO.

## Validation, as an ASVS V5 control

Three new **unauthenticated** numeric parameters cross into a native query. Every rejection is an
RFC 7807 typed 400 through `GlobalExceptionHandler`, asserted on `$.type` and not merely on the
status — a bare 400 tells a machine consumer nothing. Verified on the delivered runtime:

```
  lat=51.47                          400  https://jtoye.uk/errors/invalid-argument
  lon=-0.07                          400  https://jtoye.uk/errors/invalid-argument
  radiusKm=5    (no centre)          400  https://jtoye.uk/errors/invalid-argument
  lat=91&lon=0                       400  https://jtoye.uk/errors/invalid-argument
  lat=0&lon=181                      400  https://jtoye.uk/errors/invalid-argument
  lat&lon&radiusKm=0                 400  https://jtoye.uk/errors/invalid-argument
  lat&lon&radiusKm=51                400  https://jtoye.uk/errors/invalid-argument
  q=jollof&lat&lon                   400  https://jtoye.uk/errors/invalid-argument
  lat=north&lon=0                    400  https://jtoye.uk/errors/type-mismatch
  lat&lon&radiusKm=50                200                        <- the accepted boundary
```

`Double.isFinite` is a real check, not decoration: `lat=NaN` binds successfully and passes **both**
range comparisons.

**Nothing is silently ignored and nothing is clamped.** `radiusKm=50` is 200 and `50.0001` is 400.
A clamp would return 200 with the 50 km result set and the caller would believe their filter
applied — the same defect class as a silent drop, and invisible from a status code. `q` combined
with a coordinate is refused for the same reason rather than one of them being discarded.

## Privacy — the coordinate never reaches a log

`lat`/`lon` are personal data under UK GDPR (T-33-06-04, ASVS V9). They are not logged, not
persisted, and the debug line records the radius and page only. The exception messages name the
permitted **range** and never echo the value supplied, because a `detail` string travels into
client logs and error trackers. `GlobalExceptionHandler.handleIllegalArgument` does not log.

Measured with a control, because a zero from a search is a statement about the pattern:

```
  coordinate identifiers inside any log call, both consumer files    0
  the same check over a copy with the coordinates added back         1
```

## The config keys are read, and that is now falsifiable

`jtoye.geo.default-radius-km` and `max-radius-km` are declared by `33-02`; this plan **modifies no
`application.yml`**. Both directions measured:

```
  across the two consumer files at HEAD before Task 2     0
  after                                                   2
```

The plan recorded that the previous form of this check (`grep -qE 'jtoye:|geo:' application.yml`)
was already satisfied at line 139 and could not fail. Neither can the new form be vacuous by
accident: the located endpoint test relies on the configured default of 5 km **excluding** a shop
at 6.36 km, so a default that was not being read would fail the test rather than pass it quietly.

No inline `@Value` default on either key — a missing key must fail startup, not resolve silently
to a number nobody chose.

## The freshness gate, and the three things that forced its shape

`check-openapi-snapshot-fresh.sh` answers the half of the question nothing in this repository
previously asked:

| | |
|---|---|
| `OpenApiSnapshotTest` (in CI) | contract <-> **source tree**, exact. Green whether or not the deployed service was ever rebuilt |
| this gate | contract <-> **running service** |

Each normalisation rule below was **found by running the diff**, not predicted:

1. **Subsumption, not byte equality.** Live carries `/dev/tenants/ensure` and `/health/security`
   that the snapshot does not, because compose runs the `dev` profile while the snapshot is
   generated under `test`. An equality gate would be permanently red on a correct tree — and this
   repository records that a permanently-red required gate is worse than none. The direction that
   matters is asserted at full strength: **committed-only paths measured ZERO**, and any that
   appear fail A-1. Live-only paths and tags are listed on every run, so the tolerance is visible.
2. **Numbers canonicalised.** jq 1.7 preserves number literals, so Jackson's BigDecimal rendering
   of 90 as `9E+1` diffed against springdoc's `90.0` — a false failure over identical semantics.
3. **OIDC `authorizationUrl`/`tokenUrl` normalised on both sides.** `localhost:8085` under `test`
   vs `keycloak:8080` on the container network, by design (the recorded split-horizon issuer).
   Deployment configuration, not API contract.
4. **The root `tags` array re-keyed by name.** Compared positionally, the dev-only `Health` tag
   shifted the array and produced a cascade of phantom diffs plus a phantom "extra element".

## Control arms — every criterion observed failing

| Criterion | Break | Result |
|---|---|---|
| Distance ordering is real | swap two fixture shops' coordinates | order changes NEAR/MID/FAR -> NEAR/FAR/MID (a permanent test, not a one-off arm) |
| The radius filters, and is not just a box | CORNER inside the box at ~1.27r; widen 5 -> 8 km | absent at 5 km, present at 8 km |
| Unpublished shops are hidden | drop `published = true` from the `countQuery` ONLY | `expected 3L but was 4L`, content still correct |
| …and that arm can fail at all | the same break at page size 20 | **BUILD SUCCESSFUL** — the count query was never issued. Arm replaced |
| Coincident points do not 500 | unclamped `acos` at 54.900003 | `ERROR: input is out of range` |
| …and that arm can fail at all | the same break at latitude 51.4710 | **BUILD SUCCESSFUL** — 4.0% of latitudes overflow and this was not one. Fixture added |
| The unlocated default is unchanged | today's tree | 3 shops, name-ascending, `distanceKm` null, live and in test |
| The radius keys are read from config | tree at HEAD before Task 2 | grep 0 -> 2 |
| No coordinate reaches a log | a copy with the coordinates put back | 0 -> 1 |
| The snapshot matches live | the pre-rebuild runtime | rc=1, naming `distanceKm` and the three parameters ABSENT FROM THE RUNNING SERVICE |
| " | hand-edit `distanceKm` -> `distanceKilometres` | rc=1, naming it absent |
| The snapshot gate fails CLOSED | port 9099 (nothing listening) | rc=**2** |
| " | `docker compose stop core-java` | rc=**2**, not 0 |
| The gate is accounted for in CI | delete its `e2e-nightly.yml` reference | rc=1 naming the script |

**The pre-rebuild arm is the one that matters most**: it proves the clean pass required a real
rebuild rather than being a property of the script.

Every restore was verified **by content** (`git hash-object` against a pre-arm baseline), never by
`git diff --stat`. `git checkout --` was deliberately NOT used on `docs/api/openapi-snapshot.json`:
the regenerated snapshot was **uncommitted**, so a checkout would have restored the
pre-regeneration file and silently eaten this task's work — the recorded break-arm trap. The clean
direction was re-run **last**, after every restore.

## Deviations from Plan

### 1. The gate is WIRED into `e2e-nightly.yml`, not declared exempt

The plan devoted a paragraph to why this plan moved from wave 4 to wave 5: so it could append its
own entry to `scripts/gates/gate-enforcement.conf`, on the premise that no CI job has a running
service. **That premise is false.** `e2e-nightly.yml` brings the full compose stack up with
`--build` and already waits on `http://localhost:9090/actuator/health` before running Playwright.

The plan's own instruction covers this — *"wire it where one exists"* — so the gate runs there,
placed before the Playwright install so a contract failure costs seconds rather than the whole
suite. Measured: `check-gate-enforcement.sh` short-circuits on `refs > 0` **before** it reads the
exemption table, so an entry would have been both unnecessary and untrue in a file whose contract
is "gates that deliberately do NOT run in CI". A comment records the absence and the reason.
Falsified: deleting the workflow reference gives rc=1 naming the script.

Consequence: **`.github/workflows/ci-cd.yaml` is NOT modified**, though `files_modified` lists it.
Wiring it there would have produced a job that could only ever exit 2.

### 2. The radius predicate is in the query; the plan's literal query shape omitted it

`<interfaces>` gave a query shape with only the bounding box, while `<behavior>` requires that a
shop outside `radiusKm` be absent and that `getTotalElements()` match the number genuinely inside
the radius. A box is not a circle. The exact test is applied to both the row query and the count
query (Rule 2 — required for correctness).

### 3. `ShopWithDistance` carries an id rather than the DTO's fields

See above: `opening_hours` is `jsonb`.

### 4. Endpoint validation arms landed in Task 2 rather than Task 3

The plan lists the test file under Tasks 1 and 3 but not 2, which would have left Task 2 verified
only by greps. The arms were added with the code they verify.

### 5. Files touched outside `files_modified`

| File | Why |
|---|---|
| `core-java/.../storefront/PublicStorefrontServiceTest.java` | Rule 3 — the single construction site of `PublicStorefrontService`, which would not compile after the constructor gained the two config values |
| `.github/workflows/e2e-nightly.yml` | Deviation 1 — wired here instead of `ci-cd.yaml` |
| `docs/metrics.json` | Generated artefact no plan owns; regenerated **by script** |

## Agent-readiness judgements, recorded rather than left open

- **Idempotency-Key: not required, deliberately.** `GET /public/shops` is read-only and changes no
  state; it is provably idempotent by method. The cross-cutting contract asks mutating endpoints to
  carry the header, and this is not one. The machine-readable contract *did* change and was
  regenerated and proven against the running service, which is the part of that contract this plan
  does owe.
- **MCP: out of scope, with a reason.** `list_shops` already exists — but it targets
  `GET /api/v1/shops`, the **authenticated, tenant-scoped management** endpoint, and this plan
  changed the **anonymous public storefront**. The MCP server is a vendor-facing surface
  authenticated with a tenant Bearer; distance-ranked public discovery is a customer surface with
  no credential. Exposing it would mean either an unauthenticated MCP tool (a new trust boundary)
  or a tenant-scoped tool answering a deliberately cross-tenant question. Neither is a small change
  and neither belongs in this plan.

## Suites and gates

- **Unit:** 141 classes, **1017 tests**, 0 failures (unchanged — the new class is
  `@Tag("testcontainers")`)
- **Integration:** 119 classes, **527 tests**, 0 failures, 0 errors, 1 skipped, `BUILD SUCCESSFUL
  in 15m 12s`. The `33-05` baseline was 118 / 513; the delta is exactly this plan's 14 new tests
- **The full suite was run, not just this plan's verifies** — the recorded reason being that a
  change to a shared service is what `OpenApiSnapshotTest` catches, and it passes here because the
  snapshot was regenerated
- `check-openapi-snapshot-fresh` rc=0 · `check-runtime-freshness` rc=0 (**4/4 FRESH** by
  `.Metadata.LastTagTime`, core-java rebuilt and `--force-recreate`d twice — once for the contract,
  once after the final `core-java/src` commit) · `check-live-shop-coordinates` rc=0 ·
  `check-gate-enforcement` rc=0 (33 gates / 5 exempt) · `docs-freshness` rc=0 (2577 -> **2591**) ·
  `check-branch-behind-base` rc=0 (0 behind `origin/main`, 46 ahead) · `check-alert-metrics` rc=1
  before and rc=0 after the mandatory `seed-order-metric.sh` (a core-java rebuild always reds it)
- **Pre-existing red, NOT this plan's:** `check-doc-metrics` rc=1 and `check-claims` rc=1. This
  plan changed no prose in `CLAUDE.md`, `AGENTS.md` or `README.md`, and at the plan's base commit
  `dddc9120` `docs/metrics.json` already held `jest_files 95 / playwright_blocks 88 /
  playwright_specs 19` against docs claiming `94 / 80 / 18`. This plan's legitimate metrics
  regeneration moved `java_test_methods` 1510 -> 1524 and the total 2577 -> 2591, so two of the
  quoted figures in the failure changed; the red state did not. Owned by **33-07 Task 4**.

## Notes for the next plan

- **`GET /public/shops?lat=&lon=&radiusKm=` is live on the delivered runtime.** `distanceKm` is a
  number in kilometres, nullable, and is null on every unlocated response including
  `GET /shops/{slug}`.
- **The default radius is 5 km and the ceiling 50 km**, both from `jtoye.geo.*`. A request past the
  ceiling is a 400, not a clamp — the frontend must surface that rather than retry silently.
- **`q` and a coordinate cannot be combined.** If 33-07 wants "search near me" it is a new
  capability and needs its own decision, not a parameter.
- **CA-2 is still open and is a BLOCKER for the located journey.** `next.config.mjs` sends
  `geolocation=()` on every route — an empty allowlist that denies the API to the document's own
  origin before any permission prompt, and presents identically to a user denial. This plan's
  endpoint is reachable by any caller that has a coordinate; the browser cannot obtain one until
  33-03/33-07 changes that header.
- **Coordinates are postcode centroids (~100 m) and GB-only.** A Northern Ireland shop keeps its
  storefront and is permanently absent from distance results, by design.
- **Do not "simplify" the derived table out of `findPublishedNear`.** Flattening it means writing
  the haversine twice, and the two copies are the ordering and the filtering.

## Threat Flags

None. This plan adds query parameters to an endpoint already exposed anonymously; every new
surface it introduces is in the plan's threat register and dispositioned there.

## Self-Check: PASSED

All three created files exist on disk. All five commits (`7dc7cddb`, `6163f7f3`, `ef3fd46f`,
`82a91ad4`, `676d2294`) resolve in `git log`.
