---
phase: 33-the-consumer-product
reviewed: 2026-08-09T10:14:48Z
depth: standard
files_reviewed: 67
files_reviewed_list:
  - core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
  - core-java/src/main/java/uk/jtoye/core/geo/GeoBounds.java
  - core-java/src/main/java/uk/jtoye/core/geo/PostcodeCentroidImporter.java
  - core-java/src/main/java/uk/jtoye/core/geo/PostcodeCentroid.java
  - core-java/src/main/java/uk/jtoye/core/geo/PostcodeCentroidRepository.java
  - core-java/src/main/java/uk/jtoye/core/geo/PostcodeGeocoder.java
  - core-java/src/main/java/uk/jtoye/core/geo/ShopCoordinateBackfill.java
  - core-java/src/main/java/uk/jtoye/core/shop/dto/CreateShopRequest.java
  - core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java
  - core-java/src/main/java/uk/jtoye/core/shop/ShopService.java
  - core-java/src/main/java/uk/jtoye/core/shop/ShopWithDistance.java
  - core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicShopDto.java
  - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java
  - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
  - core-java/src/main/resources/application.yml
  - core-java/src/main/resources/db/migration/V61__postcode_centroid.sql
  - core-java/src/main/resources/geo/SOURCE.md
  - core-java/src/test/java/uk/jtoye/core/dev/DemoDataSeederAddressTest.java
  - core-java/src/test/java/uk/jtoye/core/geo/GeoBoundsTest.java
  - core-java/src/test/java/uk/jtoye/core/geo/PostcodeCentroidImportIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/geo/PostcodeGeocoderTest.java
  - core-java/src/test/java/uk/jtoye/core/geo/ShopCoordinateBackfillIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java
  - core-java/src/test/java/uk/jtoye/core/shop/ShopServiceGeocodeTest.java
  - core-java/src/test/java/uk/jtoye/core/shop/ShopServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontDistanceIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java
  - core-java/src/test/resources/application-test.yml
  - core-java/src/test/resources/geo/fixture-SOURCE.md
  - core-java/src/test/resources/geo/nullisland-SOURCE.md
  - core-java/src/test/resources/geo/postcode-centroids-fixture.csv
  - core-java/src/test/resources/geo/postcode-centroids-nullisland.csv
  - core-java/src/test/resources/geo/README.md
  - docs/api/openapi-snapshot.json
  - docs/architecture/decisions/ADR-0005-customer-realm-identity-providers.md
  - docs/metrics.json
  - frontend/app/page.tsx
  - frontend/app/__tests__/landing.test.tsx
  - frontend/components/marketing/near-you-row.tsx
  - frontend/components/marketing/shop-card.tsx
  - frontend/components/marketing/__tests__/near-you-row.test.tsx
  - frontend/components/marketing/__tests__/shop-card.test.tsx
  - frontend/components/public/public-footer.tsx
  - frontend/e2e/landing-webperf.spec.ts
  - frontend/e2e/marketing-dish-scroller.spec.ts
  - frontend/e2e/near-you-row.spec.ts
  - frontend/e2e/perf-budgets.ts
  - frontend/e2e/storefront-ssr-seo.spec.ts
  - frontend/lib/distance.ts
  - frontend/lib/__tests__/distance.test.ts
  - frontend/next.config.mjs
  - frontend/types/storefront.ts
  - .github/workflows/ci-cd.yaml
  - .github/workflows/docs-freshness.yml
  - .github/workflows/e2e-nightly.yml
  - infra/keycloak/README.md
  - infra/keycloak/realm-export-customers.template.json
  - k8s/LOCAL.md
  - scripts/check-doc-citations.sh
  - scripts/check-geo-attribution.sh
  - scripts/check-live-shop-coordinates.sh
  - scripts/check-no-create-extension.sh
  - scripts/check-openapi-snapshot-fresh.sh
  - scripts/gates/gate-enforcement.conf
  - scripts/osgb36-to-wgs84.awk
  - scripts/regen-postcode-centroids.sh
  - scripts/verify-env.sh
findings:
  critical: 0
  warning: 4
  info: 7
  total: 11
status: issues_found
---

# Phase 33: Code Review Report

**Reviewed:** 2026-08-09T10:14:48Z
**Depth:** standard
**Files Reviewed:** 67
**Status:** issues_found

## Summary

Adversarial review of the consumer-product phase: the V61 postcode-centroid substrate and
importer, write-path geocoding and startup backfill, the RLS-sensitive distance query, the
landing "near you" client island, miles-display conversion, and the new fail-closed gates.

The areas named as highest-risk were each traced end-to-end and hold up:

- **Tenant/RLS on the public storefront** — the distance query is deliberately cross-tenant
  over `published = true` only, with the predicate in BOTH the row and count queries; the
  hydration pass (`shopRepository.findAllById` with no tenant GUC) is constrained by the same
  `shops_public_read` policy, so it cannot widen the result set. The cross-tenant and
  unpublished-absence properties are pinned in `PublicStorefrontDistanceIntegrationTest`,
  including the count-query leak (page size 2, so the count really executes).
  `postcode_centroid`'s RLS exemption is by addition in `RlsContractTest.EXEMPT_TABLES` with a
  written justification; the schema-walk sweep is untouched.
- **The leakproof-prefilter claim** — all values reach the native query as named JPA
  parameters; the box is computed in Java (`GeoBounds.boxAround`); no client `Sort` can reach
  the native `ORDER BY` (the service forces `Sort.unsorted()`); the `asin` haversine avoids the
  `acos` domain error and the test suite contains a genuinely falsifying fixture for it
  (`ACOS_TRAP_LAT`). One latent coupling in the `asin` form is recorded below (IN-04).
- **Query-param validation** — lat/lon/radius are range-checked with explicit finiteness
  guards (NaN cannot pass the range comparisons alone, and the code knows it); the radius
  ceiling refuses rather than clamps; errors are RFC 7807 typed 400s, all integration-tested in
  both directions.
- **Browser PII** — the coordinate lives only in React state, is rounded to 4 dp before it
  enters a URL, is requested only from a click handler, and is never logged server-side; no
  browser-storage sink exists in the island.
- **Gate scripts** — all five new/changed gates fail closed (exit 2 VOID on missing input,
  empty scans, unreadable manifests); `check-no-create-extension.sh` VOIDs when its exemption
  table stops matching the tree; the regeneration script writes the artefact only after md5
  verification and atomically.

No Critical findings. Four Warnings — one incorrect user-facing disclosure computation in the
landing island, two coordinate-integrity gaps on the shop write path, and one null-handling
defect on the new shop card — plus seven Info items.

## Warnings

### WR-01: NearYouRow's exclusion disclosure computes false statements once results exceed one page

**File:** `frontend/components/marketing/near-you-row.tsx:196-199`
**Issue:** The disclosure counts are derived from two page-truncated samples that are treated
as totals:

```ts
const unranked = located ? withoutCoordinates(serverShops) : []
const beyondRadius = located
  ? Math.max(0, serverShops.length - nearby.length - unranked.length)
  : 0
```

`serverShops` is the first page of the name-ordered listing (`size: 8` in `app/page.tsx`) and
`nearby` is the first page of the distance-ordered result (`PAGE_SIZE = 8`). With more than 8
published shops the two pages are different *samples* of different orderings, and the
arithmetic over them is meaningless: a shop genuinely inside the radius but ranked 9th is
counted into `beyondRadius`, so the row renders "N more are further than 3.1 miles away" about
shops that are *within* the radius — exactly the class of untruth this component's own docblock
(issue 544) exists to stop. `unranked` likewise only sees coordinate-less shops that happen to
land in the first 8 by name. The `Math.max(0, …)` clamp hides the negative case rather than the
defect. Today's seed (3 shops) cannot exercise it, so every test passes; growth past one page
is the product's stated goal.
**Fix:** Derive the counts from totals, not page contents: use `body.totalElements` from the
located response for the in-radius count, and pass the unlocated listing's `totalElements`
(already returned by `loadShopList`) into the island alongside `serverShops`. `unranked`
genuinely requires a total the API does not yet expose — until it does, suppress the
"further than" clause whenever `nearby.length === PAGE_SIZE || serverShops.length === PAGE_SIZE`
so a truncated sample can never be presented as a census:

```ts
const truncated = nearby.length >= PAGE_SIZE || serverShops.length >= PAGE_SIZE
const beyondRadius = located && !truncated
  ? Math.max(0, serverShops.length - nearby.length - unranked.length)
  : 0
```

### WR-02: The shop write path can persist a half-updated coordinate pair (no lat/lon pairing validation)

**File:** `core-java/src/main/java/uk/jtoye/core/shop/dto/CreateShopRequest.java:41-47`, `core-java/src/main/java/uk/jtoye/core/shop/ShopService.java:428-455`
**Issue:** `latitude` and `longitude` are range-validated independently; nothing requires them
to be supplied together (the *query* endpoint enforces pairing; the *write* DTO does not).
`ShopMapper.updateEntity` is IGNORE-null, so a `PUT` carrying only `latitude` copies one axis
onto the entity. When the address does not geocode (Northern Ireland vendor, unresolvable
address — the exact population the fallback exists for), `applyCoordinate`'s miss branch then
preserves each axis independently:

```java
if (shop.getLatitude() == null && persistedLatitude != null) { ... }
if (shop.getLongitude() == null && persistedLongitude != null) { ... }
```

Result: latitude from the request merged with longitude from the previous persisted value — a
coordinate nobody supplied, range-valid, and ranked in public distance results. On create the
same shape yields a lone axis, which the partial index's `IS NOT NULL AND IS NOT NULL` filter
happens to hide, but the update path publishes the Frankenstein pair.
**Fix:** Treat the client pair atomically. Either a class-level constraint on the DTO
(`@AssertTrue` — "latitude and longitude must be supplied together"), or in `applyCoordinate`
discard a lone axis before the miss branch runs:

```java
// A single axis is not a coordinate: never merge a client half with a persisted half.
if ((shop.getLatitude() == null) != (shop.getLongitude() == null)) {
    shop.setLatitude(persistedLatitude);
    shop.setLongitude(persistedLongitude);
}
```

### WR-03: The client-coordinate fallback is a trivially reachable self-placement vector in public ranking

**File:** `core-java/src/main/java/uk/jtoye/core/shop/ShopService.java:428-455` (precedence rule 2)
**Issue:** The precedence rule accepts the client's own `latitude`/`longitude` whenever the
address fails to geocode. The docblock frames this as safe because "the postcode is
authoritative … a client-supplied coordinate is a FALLBACK, never an override", and notes a
vendor cannot out-pin their centroid "while their postcode resolves". But a geocode miss is
under the vendor's control: append ", United Kingdom" after the postcode (the extractor is
end-anchored — see IN-02), or use a well-formed non-existent unit (the repo's own `SE15 4QA`
demonstrates that regexes cannot tell). Any authenticated `SHOP_MANAGER` can therefore force
the fallback and place their shop at an arbitrary valid point on Earth — e.g. central London
while operating from Belfast — and rank as "nearest kitchen" on the anonymous discovery
surface. The range validation (±90/±180) bounds absurdity, not abuse. The comment records the
deliberate trade for *accuracy* (D-1 centroid tolerance) but not this *integrity* consequence.
**Fix:** Cheap containment without breaking the legitimate NI use-case: bound client-supplied
fallback coordinates to the UK bounding box (approx. lat 49.8–60.9, lon −8.7–1.8 — includes
Northern Ireland), and log a structured `event=client_coordinate_accepted` WARN naming the shop
so operator review is possible. Longer term, carry a `coordinate_source` column
(`GEOCODED | CLIENT`) so the ranking surface can discount or badge unverified positions — the
"verified vendor-supplied override" the docblock already names as future work.

### WR-04: ShopCard renders "£0.00 delivery" / "min £0.00" for null wire values; the TS type masks it

**File:** `frontend/components/marketing/shop-card.tsx:40-42,129-131`; `frontend/types/storefront.ts:14-15`
**Issue:** `PublicShopDto.deliveryFeePennies` and `minimumOrderPennies` are nullable `Long`s on
the backend, and `CreateShopRequest` has no delivery-fee field at all, so an API-created shop
serialises `deliveryFeePennies: null`. The TS interface declares both as non-nullable `number`,
so the compiler cannot see the hazard, and:

```ts
function pounds(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`   // null / 100 === 0 → "£0.00"
}
...
{shop.deliveryFeePennies === 0 ? "Free delivery" : `${pounds(shop.deliveryFeePennies)} delivery`}
```

`null === 0` is false, `null / 100` coerces to `0`, so a null-fee shop renders
"£0.00 delivery" — the exact string `shop-card.test.tsx:58-62` declares must never appear —
plus "min £0.00" for a null minimum. The sibling surface already disagrees:
`app/shop/shop-discovery-client.tsx:118` uses `deliveryFeePennies > 0`, so null renders as
free there. The seeded demo shops all carry values, which is why nothing fails today.
**Fix:** Correct the type (`deliveryFeePennies: number | null`, `minimumOrderPennies: number | null`)
and null-guard the card — render the delivery line only when the value is known:

```tsx
{shop.deliveryFeePennies != null && (
  <>{shop.deliveryFeePennies === 0 ? "Free delivery" : `${pounds(shop.deliveryFeePennies)} delivery`}</>
)}
{shop.minimumOrderPennies != null && shop.minimumOrderPennies > 0 && (
  <span className="font-normal text-slate-600"> · min {pounds(shop.minimumOrderPennies)}</span>
)}
```

## Info

### IN-01: formatMiles prints "10.0 miles" just below the whole-mile boundary

**File:** `frontend/lib/distance.ts:63-66`
**Issue:** `miles < 10 ? miles.toFixed(1) : Math.round(miles)` — for km in ~16.01–16.09, miles
is 9.95–9.99, `toFixed(1)` rounds to `"10.0 miles"`, while anything at or above 10 prints
`"10 miles"`. Two spellings of the same figure straddle the boundary.
**Fix:** Round before branching: `const m = kmToMiles(km); return m < 9.95 ? `${m.toFixed(1)} miles` : `${Math.round(m)} miles``.

### IN-02: The trailing-postcode extractor misses common address suffixes — a silent-absence blind spot

**File:** `core-java/src/main/java/uk/jtoye/core/geo/PostcodeGeocoder.java:63-64`
**Issue:** `TRAILING_POSTCODE` is anchored to the end of the string (`\s{0,8}$`). An address
ending "…SE15 4BW, United Kingdom" or "…SE15 4BW." never extracts, so a real vendor with a real
postcode silently holds NULL coordinates and vanishes from distance ranking — the phase's own
named failure mode. The WARN log and the backfill's `notGeocoded` counter partially surface it,
and `check-live-shop-coordinates.sh` REPORTS (does not fail on) unresolvable postcodes, so the
blind spot is observable but nothing distinguishes "genuinely no postcode" from "postcode
present but not last".
**Fix:** Either search for the *last* postcode-shaped candidate anywhere in the string (still
bounded, still table-authoritative), or strip a small set of trailing country tokens/punctuation
before matching. At minimum record the limitation next to the regex.

### IN-03: GeoBounds clamps at ±180 instead of wrapping — containment contract broken at the antimeridian

**File:** `core-java/src/main/java/uk/jtoye/core/geo/GeoBounds.java:89-93`
**Issue:** `boxAround`'s contract is "guaranteed to contain every point within radiusKm", but a
circle crossing ±180° (e.g. lon 179.9, radius 50 km) is clamped, not wrapped: points on the far
side of the antimeridian are outside the box and would be silently dropped. Unreachable for GB
data, but the public endpoint accepts any lon in [−180, 180], so the class's stated contract is
false for valid inputs.
**Fix:** Record the limitation in the javadoc ("clamped, not wrapped — correct only while all
indexed shops are far from ±180°"), or return `lonDelta = 180` when the box would cross the
antimeridian, mirroring the polar clamp.

### IN-04: The asin haversine's domain safety silently depends on the bounding-box prefilter

**File:** `core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java:101-137`
**Issue:** The comment block documents the `acos` domain error at coincident points, but `asin`
has its own: for near-antipodal pairs the haversine term can exceed 1.0 in floating point and
`asin(sqrt(a))` raises the same "input is out of range". This is unreachable *today* only
because the BETWEEN prefilter confines candidate shops to a ≤50 km box around the query point,
so no pair can approach antipodal. That coupling — "asin is safe *because of* the box" — is not
recorded, and a future edit that widens or removes the prefilter (or reuses the expression
elsewhere) re-opens the unauthenticated-500 class the comment warns about for `acos`.
**Fix:** One sentence in the existing comment: the box prefilter is load-bearing for asin's
domain, not only for index eligibility; if the expression is ever used without it, wrap the
argument in `LEAST(1.0, ...)`.

### IN-05: regen-postcode-centroids.sh feeds unsorted files to `join`; a divergence would be misreported

**File:** `scripts/regen-postcode-centroids.sh:144-167`
**Issue:** `MINE` and `REF` are appended in `SAMPLE` order (AB…, EH…, NE…, M… — not
lexicographic), and `join -t,` requires sorted input. It works today only because both files are
built in the identical order and always fully pair, which GNU join tolerates; if any future
change makes one line unpairable after the disorder point, `join` errors and the `|| die` arm
reports "transform accuracy exceeded … the transform is wrong" — a wrong diagnosis for an
instrument failure. `sort` is already a declared dependency of this script.
**Fix:** `LC_ALL=C sort -t, -k1,1 -o "$MINE" "$MINE"` (and the same for `$REF`) before the join,
or pass `--nocheck-order` with a comment stating why identical construction order makes it safe.

### IN-06: The importer's idempotent skip never re-validates the Null-Island invariant on an existing table

**File:** `core-java/src/main/java/uk/jtoye/core/geo/PostcodeCentroidImporter.java:128-134`
**Issue:** `importIfNeeded` skips entirely when the live row count matches the manifest. The
(0,0) guard runs only on the staging table during a load, so a `postcode_centroid` populated by
any other means (manual COPY, an older importer, a restore) that happens to match the count is
served without the Null-Island check ever running against it. `check-live-shop-coordinates.sh`
A-4 covers *shops* at (0,0) but nothing re-checks the reference table after first load.
**Fix:** Add the one cheap query to the skip path:
`SELECT COUNT(*) FROM postcode_centroid WHERE latitude = 0 AND longitude = 0` must be 0, else
fall through to a reload (or abort loudly).

### IN-07: check-geo-attribution.sh verifies the component source, not the rendered surface

**File:** `scripts/check-geo-attribution.sh:36-41,70-96`
**Issue:** The gate greps `public-footer.tsx` for the three rights-holder phrases and the
`GEO_ATTRIBUTION_YEAR` usages. Its banner calls the footer the "rendered surface", but a footer
that stops *mounting* (removed from `PublicShell`, or gated behind a condition) passes the gate
while nothing renders — the licence obligation is about what a user can reach. The usage-count
check (declaration + 3 interpolations) covers the declared-but-unrendered-constant case inside
the file, not the unmounted-component case.
**Fix:** Acceptable trade for a static CI gate, but record the gap in the header, and note that
the e2e layer is the place a served-HTML assertion (three attribution lines present in `GET /`
bytes) would close it — one `expect(html).toContain("Ordnance Survey data")` triple in
`storefront-ssr-seo.spec.ts` would do.

---

_Reviewed: 2026-08-09T10:14:48Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
