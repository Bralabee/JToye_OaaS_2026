# Phase 33 (additive plan 33-08): Postcode-Proximity Search — Pattern Map

**Mapped:** 2026-08-09
**Scope:** ONLY the surface issue **#619** touches. Plans 33-00..33-07 are shipped; nothing below
re-plans them.
**Files analysed:** 9 new/modified (5 backend, 2 frontend, plus tests)
**Analogs found:** 9 / 9 — every file has an in-repo analog. Nothing needs a RESEARCH.md pattern.

> **The one-line problem.** `GET /public/shops?q=SE22` runs FTS over `shops.search_vector`, matches
> nothing, and returns 0 shops — while `postcode_centroid` holds 507 units under `SE22` whose mean
> is 2 km from three published kitchens. 33-06 deliberately made `q` + `lat`/`lon` a **typed 400**,
> so this is a *new capability*, not a parameter.

---

## Live measurements taken while mapping (use these; do not re-derive)

Run on the running `jtoye-postgres` (read-only, `EXPLAIN (COSTS OFF)` + counts). These are the
three facts that decide 33-08's query shape.

| # | Measurement | Result |
|---|---|---|
| M-1 | `postcode_centroid` keys are **full units, space-stripped** (`SE155BS`); the only index is `postcode_centroid_pkey` btree | 1 index, PK only |
| M-2 | DB collation is **`en_US.utf8`**, so `WHERE postcode LIKE 'SE22%'` → **Parallel Seq Scan over 1,748,230 rows** | seq scan; unacceptable on an anonymous endpoint |
| M-3 | `WHERE postcode >= 'SE22' AND postcode < 'SE23'` → **Index Only Scan using postcode_centroid_pkey**, and returns the **same 507 rows** as the `LIKE` | index scan, control agrees (507 = 507) |
| M-4 | The naive prefix is **wrong for single-digit districts**: `LIKE 'M1%'` = **6,422** rows spanning M1, M11…M19. Adding the unit-length guard `AND length(postcode) = length(outward) + 3` gives **548** (the true M1) and still uses the index (`Index Scan` + `Filter`) | 6,422 → 548 |
| M-5 | Resulting district centroids are plausible: `SE22` → (51.454445, −0.072403) East Dulwich; `M1` → (53.477526, −2.236137) central Manchester | sane |

**Consequence for the planner:** an outward-code lookup needs **no new migration and no new index**
provided it is written as a half-open range **plus a unit-length guard**. Write it as `LIKE` and it
seq-scans 1.7 M rows; write it as a bare range and `M1` silently answers with the centroid of nine
districts.

---

## File Classification

| New/Modified file | Role | Data flow | Closest analog | Match |
|---|---|---|---|---|
| `core-java/.../geo/PostcodeGeocoder.java` (**modify** — add outward-code lookup) | service | transform / request-response | itself: `locate(String)` at `:83-111` | exact (extend) |
| `core-java/.../geo/PostcodeCentroidRepository.java` (**modify** — add district query) | repository | CRUD read | `ShopRepository.findPublishedNear` `:101-146` (native `@Query` + named params) | role-match |
| `core-java/.../storefront/PublicStorefrontService.java` (**modify** — route a postcode-shaped `q`) | service | request-response | itself: `listPublishedShopsNear` `:229-292` and `searchPublishedShops` `:297-310` | exact |
| `core-java/.../storefront/PublicStorefrontController.java` (**modify** — the `q`-vs-coordinate branch) | controller | request-response | itself: `listShops` `:85-104` | exact |
| `core-java/.../storefront/dto/PublicShopDto.java` **or** a new envelope DTO (disclosure field) | model/DTO | response | `PublicShopDto.distanceKm` `:33-50` (additive + nullable, contract has one shape) | exact |
| `frontend/app/shop/shop-discovery-client.tsx` (**modify** — disclosed interpretation) | component (client island) | request-response | itself `:361-371` (result summary) + `near-you-row.tsx:205-209` (derived heading) | exact |
| `frontend/lib/storefront-server.ts` (**modify** if the SSR seed must carry the interpretation) | service (server data loader) | request-response | itself: `loadShopList` `:104-116` | exact |
| `core-java/src/test/.../storefront/PublicStorefrontPostcodeSearchIntegrationTest.java` (**new**) | test | — | `PublicStorefrontDistanceIntegrationTest.java` (575 lines) | exact |
| `frontend/app/shop/__tests__/…` + `frontend/e2e/…` (**new/modify**) | test | — | `near-you-row.test.tsx` (464) / `near-you-row.spec.ts` (376) | exact |

---

## Pattern Assignments

### 1. `PostcodeGeocoder` — the geocoder EXISTS but **cannot** answer `SE22`

**File:** `core-java/src/main/java/uk/jtoye/core/geo/PostcodeGeocoder.java`
**Role:** service · **Flow:** transform

This is the surprise the planner most needs. The class is `PostcodeGeocoder` (not
`PostcodeCentroidImporter`, which is only the startup loader), it is a plain `@Service`, and its
whole public API is:

```java
@Service
public class PostcodeGeocoder {

    // Group 1 = outward (SE15), group 2 = inward (5BS). Anchored to end-of-string,
    // bounded quantifiers, no nested repetition (ReDoS on untrusted vendor text).
    private static final Pattern TRAILING_POSTCODE = Pattern.compile(
            "([A-Za-z]{1,2}[0-9]{1,2}[A-Za-z]?)\\s{0,4}([0-9][A-Za-z]{2})\\s{0,8}$");

    private static final int MAX_KEY_LENGTH = 8;   // XX99XXX; the column is length = 8

    public Optional<Coordinate> locate(String address) { … }   // never throws, never (0,0)

    public record Coordinate(double latitude, double longitude) { }
}
```

**The inward code is mandatory** in that pattern, and the geocoder's own unit test asserts it:

```java
// PostcodeGeocoderTest.java:147-152
@ParameterizedTest
@DisplayName("degenerate input returns empty without throwing")
@ValueSource(strings = { "—", "", "   ", ",,,", "SE15", "5BS", "0000 000" })
void degenerateInputYieldsEmpty(String address) {
    assertThat(geocoder.locate(address)).isEmpty();
}
```

`"SE15"` is a **committed assertion that a bare outward code returns empty**. Plan 33-08 must add a
*second, differently-named* entry point (e.g. `locateDistrict(String)` / `locateOutward(String)`)
rather than loosening `TRAILING_POSTCODE` — loosening it changes the **write path** too
(`ShopService.applyCoordinate` calls `locate` on vendor addresses) and would start assigning
district centroids to shops, silently degrading `distanceKm` for every vendor whose address the
extractor half-parses.

**Normalisation rules to reuse verbatim** (`:94-97`): concatenate group 1 + group 2,
`toUpperCase(Locale.ROOT)`, reject `key.length() > MAX_KEY_LENGTH`. Miss behaviour (`:102-109`):
`log.warn` naming **the postcode only, never the address line** (threat T-33-05-04), and return
`Optional.empty()`.

**Miss behaviour to copy** — never a sentinel:

```java
if (located.isEmpty()) {
    log.warn("Postcode '{}' is not in the Code-Point Open dataset — address not geocoded. "
            + "This is expected for Northern Ireland (the dataset is GB-only) and for "
            + "postcodes that do not exist.", key);
}
return located;
```

The class doc's design claim is binding on 33-08: **the table is the authority, not the regex.**
A regex-shaped "is this a postcode?" test must only *nominate a candidate*; the presence of rows in
`postcode_centroid` is what decides. `SE15 4QA` is the repo's permanent negative control (in the
seeded demo data, matches every plausible pattern, returns 404 from `api.postcodes.io`).

---

### 2. `PostcodeCentroidRepository` — today it is `findById` only, deliberately

**File:** `core-java/src/main/java/uk/jtoye/core/geo/PostcodeCentroidRepository.java` (20 lines)

```java
/**
 * Primary-key access to {@link PostcodeCentroid}.
 *
 * <p>The only lookup this needs is {@code findById}, and that is the point: the key is the
 * normalised postcode, so a hit is a primary-key hit — O(log n) on 1.7 M rows with no scan,
 * no {@code LIKE}, and nothing an untrusted address string can steer.
 *
 * <p>No tenant filter and no {@code set_config} pin: {@code postcode_centroid} carries no
 * {@code tenant_id} and has no RLS policy, so this repository is safe to call before or
 * outside a {@code TenantContext}.
 */
@Repository
public interface PostcodeCentroidRepository extends JpaRepository<PostcodeCentroid, String> {
}
```

33-08 adds the **first non-PK query on this table**, so the class comment's "no `LIKE`, nothing an
untrusted string can steer" claim must be updated in the same change, not left contradicting the
code.

**Query pattern to copy** — from `ShopRepository.findPublishedNear` (`:101-146`): a native `@Query`,
a text block, **named parameters only**, nothing concatenated, and a long comment block above it
stating why the shape is what it is. Applied here (measurements M-3/M-4):

```java
// Half-open RANGE, never LIKE: the database collates en_US.utf8, so a LIKE prefix cannot use
// postcode_centroid_pkey and plans as a Parallel Seq Scan over 1,748,230 rows on an anonymous
// endpoint. Measured 2026-08-09 on the live stack:
//   LIKE 'SE22%'                               -> Parallel Seq Scan
//   postcode >= 'SE22' AND postcode < 'SE23'   -> Index Only Scan, SAME 507 rows
//
// The LENGTH guard is not optional. A bare range on 'M1'..'M2' also matches M11..M19 — 6,422
// units across nine districts, whose mean is not Manchester city centre. Every Code-Point Open
// key is outward + a 3-character inward code, so the unit's outward code is exactly
// length(postcode) - 3. With the guard: 548 rows, and the plan is still an Index Scan.
@Query(value = """
        SELECT avg(latitude) AS latitude, avg(longitude) AS longitude, count(*) AS units
          FROM postcode_centroid
         WHERE postcode >= :outward
           AND postcode <  :upperBound
           AND length(postcode) = :unitLength
        """, nativeQuery = true)
```

`:upperBound` is the successor string computed in **Java** (as `GeoBounds.boxAround` computes the
box in Java for exactly the same "keep the predicate leakproof/index-eligible" reason). Successor
computation for outward codes ending in a letter (`SW1A` → `SW1B`) was measured and agrees with
`LIKE` at 145 = 145 — but the planner must **falsify it for the `Z`-suffix and digit-suffix cases**
before trusting it.

---

### 3. `PublicStorefrontService` — how the service decides which query to run TODAY

**File:** `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java` (934 lines)
**Role:** service · **Flow:** request-response

There are **three** entry points and the decision is currently made in the *controller*, not here.

**(a) The unlocated listing** (`:191-195`):

```java
public Page<PublicShopDto> listPublishedShops(Pageable pageable) {
    log.debug("Listing published shops, page {}", pageable.getPageNumber());
    return shopRepository.findByPublishedTrue(pageable)
            .map(this::toPublicShopDto);
}
```

**(b) The FTS search path** (`:294-310`) — note the two-tier fallback, which is exactly where
`SE22` dies today:

```java
/**
 * Search published shops by name or tags.
 */
public Page<PublicShopDto> searchPublishedShops(String query, Pageable pageable) {
    log.debug("Searching published shops: '{}'", query);
    // Use full-text search for ranked results; fall back to LIKE for short queries
    if (query != null && query.length() >= 2) {
        // Use unsorted Pageable for native queries — ts_rank handles ordering
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.unsorted());
        Page<Shop> results = shopRepository.fullTextSearchPublished(query, unsorted);
        if (results.hasContent()) {
            return results.map(this::toPublicShopDto);
        }
    }
    return shopRepository.searchPublished(query, pageable)
            .map(this::toPublicShopDto);
}
```

> **Read the fallback carefully.** There is already a "FTS returned nothing → try something else"
> seam at `:304`. 33-08's postcode branch is the natural third tier, and slotting it in preserves
> 33-07's measured healthy behaviour (`SE15` → 2, `SE15 5BS` → 1, `jollof` → 2) without touching it.
> Whether the postcode attempt runs **before** FTS (interpretation-first) or **after** it
> (fallback-only) is a real product decision the plan must state and defend — a shop literally named
> "SE22 Kitchen" is the case that separates them.

**(c) The distance path** (`:229-292`) — the validation prologue and the projection→DTO tail that a
postcode search should re-use rather than reimplement:

```java
public Page<PublicShopDto> listPublishedShopsNear(Double latitude, Double longitude,
                                                 Double radiusKm, Pageable pageable) {
    if (latitude == null || longitude == null) {
        throw new IllegalArgumentException(
                "'lat' and 'lon' must be supplied together to search by distance");
    }
    if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
        throw new IllegalArgumentException("'lat' must be a number between -90 and 90");
    }
    if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
        throw new IllegalArgumentException("'lon' must be a number between -180 and 180");
    }

    double radius = radiusKm != null ? radiusKm : defaultRadiusKm;
    if (!Double.isFinite(radius) || radius <= 0.0) {
        throw new IllegalArgumentException("'radiusKm' must be a number greater than 0");
    }
    if (radius > maxRadiusKm) {
        // Named ceiling, no clamp. The caller must learn that their request was refused.
        throw new IllegalArgumentException("'radiusKm' must not exceed " + maxRadiusKm);
    }

    GeoBounds box = GeoBounds.boxAround(latitude, longitude, radius);

    // Unsorted on purpose: the query owns its ordering (nearest first, id as tiebreak) and a
    // client-supplied Sort must never reach a native ORDER BY (T-33-06-02).
    Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.unsorted());
    log.debug("Listing published shops by distance, radiusKm={}, page {} — coordinates deliberately not logged",
            radius, unsorted.getPageNumber());

    Page<ShopWithDistance> page = shopRepository.findPublishedNear(
            latitude, longitude,
            box.minLatitude(), box.maxLatitude(), box.minLongitude(), box.maxLongitude(),
            radius, unsorted);

    Map<UUID, Shop> byId = shopRepository.findAllById(
                    page.getContent().stream().map(ShopWithDistance::getId).toList())
            .stream()
            .collect(Collectors.toMap(Shop::getId, s -> s));

    // Page.map, never `new PageImpl<>(content, pageable, total)`: the hand-built form REWRITES
    // the total it is handed whenever offset + size exceeds it (recorded trap).
    return page.map(projection -> {
        Shop shop = byId.get(projection.getId());
        …
        PublicShopDto dto = toPublicShopDto(shop);
        dto.setDistanceKm(projection.getDistanceKm());
        return dto;
    });
}
```

**Conventions 33-08 must respect, each visible above:**

| Convention | Where | Note for 33-08 |
|---|---|---|
| `@Service @Transactional(readOnly = true)` on the class | `:62-64` | no change |
| Config read via `@Value` with **no inline default** | `:88-97` | a postcode radius must come from `jtoye.geo.*`; a missing key must fail startup |
| **Never** `new PageImpl<>(content, pageable, total)` | `:276-278` | recorded `PageImpl` total-recompute trap |
| Native query ⇒ `Sort.unsorted()` pageable | `:259`, `:302` | client `Sort` must never reach a native `ORDER BY` |
| Coordinates are **never logged** | `:214-221`, `:260-261` | a *postcode* the customer typed is also personal-ish; state a decision either way |
| One `toPublicShopDto` for every path | `:288` | a postcode result must differ from an unlocated one by exactly the disclosure/distance fields |
| Reject, never silently clamp or drop | `:246-250` | if a postcode is unresolvable the answer is **FTS fall-through**, not an empty 200 |

**33-02's own note carried forward, and it binds this plan:**
*"33-05 and 33-06 must READ `jtoye.geo.*`, not add to it."* 33-08 inherits that. The block lives at
`application.yml:330-358`:

```yaml
  geo:
    postcode-import:
      enabled: ${POSTCODE_IMPORT_ENABLED:true}
      resource: ${POSTCODE_IMPORT_RESOURCE:classpath:geo/postcode-centroids.csv.gz}
      manifest: ${POSTCODE_IMPORT_MANIFEST:classpath:geo/SOURCE.md}
    coordinate-backfill:
      enabled: ${COORDINATE_BACKFILL_ENABLED:true}
    # Read by 33-06. Q-2 settled radius as a QUERY PARAMETER, so these are the platform
    # default and the ceiling a caller may request …
    default-radius-km: ${GEO_DEFAULT_RADIUS_KM:5}
    max-radius-km: ${GEO_MAX_RADIUS_KM:50}
```

---

### 4. `PublicStorefrontController` — the `q` / coordinate branch that must change

**File:** `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java`
**Role:** controller · **Flow:** request-response

```java
@RestController
@RequestMapping({"/public", "/api/v1/public"})
@Tag(name = "Public Storefront", description = "…")
public class PublicStorefrontController {
```

The exact code 33-08 edits (`:85-104`):

```java
@GetMapping("/shops")
@Operation(summary = "List published shops", description = "…")
public Page<PublicShopDto> listShops(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) Double lat,
        @RequestParam(required = false) Double lon,
        @RequestParam(required = false) Double radiusKm,
        @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
    boolean located = lat != null || lon != null || radiusKm != null;
    if (located) {
        if (q != null && !q.isBlank()) {
            throw new IllegalArgumentException(
                    "'q' cannot be combined with a distance search ('lat'/'lon'/'radiusKm'); "
                            + "ranked text search and distance ordering are separate results");
        }
        return storefrontService.listPublishedShopsNear(lat, lon, radiusKm, pageable);
    }
    if (q != null && !q.isBlank()) {
        return storefrontService.searchPublishedShops(q.trim(), pageable);
    }
    return storefrontService.listPublishedShops(pageable);
}
```

**Two things the plan must decide explicitly, because both are load-bearing invariants today:**

1. **The `q` + `lat`/`lon` 400 stays.** 33-07's own note: *"`q` and a coordinate cannot be
   combined. If 33-07 wants 'search near me' it is a new capability and needs its own decision, not
   a parameter."* A postcode inside `q` is a *derived* coordinate, not a caller-supplied one, so the
   guard is untouched — but the plan must say so, or a reviewer will read the new branch as
   contradicting it.
2. **The docblock at `:58-74` is a contract.** It states "with no `lat`/`lon`/`radiusKm` this
   endpoint behaves exactly as it did before". A postcode-shaped `q` changes that sentence, and the
   sentence is what `check-openapi-snapshot-fresh.sh` renders into the published description.

**Endpoint-shape precedent:** additive optional parameters, never a new path. 33-06 added three
parameters to this same handler.

---

### 5. `PublicShopDto` + the interpretation disclosure

**File:** `core-java/src/main/java/uk/jtoye/core/storefront/dto/PublicShopDto.java` (88 lines)

The additive-field precedent, with the exact rationale to copy (`:33-50`):

```java
/**
 * Great-circle distance in kilometres from the coordinate the caller supplied …
 *
 * <p><b>Null when the caller supplied no coordinate</b> … Nullable rather than
 * absent so the OpenAPI contract has ONE shape for {@code PublicShopDto} and a machine consumer
 * does not have to discover a second one at runtime.
 *
 * <p>This is the SAME number the ordering used …
 */
private Double distanceKm;

public Double getDistanceKm() { return distanceKm; }
public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }
```

Plain POJO with hand-written getters/setters — **no Lombok and no MapStruct on this DTO**. (MapStruct
is the project convention for entity↔DTO mappers generally, but the storefront DTOs are built by
hand in `toPublicShopDto`; do not introduce a mapper here.)

**The disclosure ("shops near SE22") has no per-shop home.** `Page<PublicShopDto>` is the response
body, and a `Page` has no room for a query-level annotation. Three options, all with in-repo
precedent — the plan must pick one and record why:

| Option | Precedent | Cost |
|---|---|---|
| A response **header** (e.g. `X-Search-Interpretation`) | none in this repo | invisible to the OpenAPI schema unless declared |
| A **wrapper DTO** `{ interpretation, page }` | `ShopConfigDto`, `GuestOrderConfirmation` | **breaking** — `Page<PublicShopDto>` is consumed by `shop-discovery-client.tsx`, `loadShopList`, `near-you-row.tsx` and the MCP `list_shops` tool |
| Frontend **re-derives** the interpretation from the `q` it sent | `near-you-row.tsx:205-209` derives its heading from local state | zero contract change; but the UI would then claim an interpretation the server might not have made — the exact "row lying about itself" failure class the phase exists to close |

Option B is the honest one and is a breaking change; option C is the cheap one and re-introduces the
untruth. **Say which, and why, in the plan.** Whatever is chosen, `docs/api/openapi-snapshot.json`
must be regenerated (`./gradlew :core-java:updateOpenApiSnapshot`) and
`scripts/check-openapi-snapshot-fresh.sh` re-run against a **rebuilt** runtime — 33-06 records that
the pre-rebuild arm is the one that proves the pass was real.

---

### 6. Frontend — the search input, and the three-state honesty heading

#### 6a. Where `q` is sent

**File:** `frontend/app/shop/shop-discovery-client.tsx` (517 lines) · client island, axios

Input (`:307-337`) — note the placeholder **already promises postcode search**, which is why #619
is a broken promise rather than a missing feature:

```tsx
<label htmlFor="shop-search" className="sr-only">
  Search kitchens, dishes or a postcode
</label>
…
<input
  id="shop-search"
  type="search"
  autoComplete="off"
  placeholder="Try “jollof”, “vegan” or your postcode…"
  value={searchQuery}
  onChange={(e) => setSearchQuery(e.target.value)}
  …
/>
```

The fetch (`:194-234`) — **keeps axios `publicApiClient` deliberately** (it pages, searches and
consumes axios-shaped 429s; 33-07's `fetch` decision applies to the landing island only):

```tsx
const params: Record<string, string | number> = { page, size: SHOPS_PAGE_SIZE }
if (searchQuery.trim()) params.q = searchQuery.trim()

const res = await publicApiClient.get<PageResponse<PublicShop>>("/public/shops", { params })
setShops(res.data.content)
setTotalPages(res.data.totalPages)
setTotalElements(res.data.totalElements)
```

The result summary that a disclosure would sit beside or replace (`:361-371`):

```tsx
{!loading && !rateLimited && searchQuery.trim() && (
  <p aria-live="polite" className="mb-4 text-sm text-slate-600">
    {totalElements === 0
      ? "No kitchens match "
      : `${totalElements} ${totalElements === 1 ? "kitchen" : "kitchens"} for `}
    <span className="font-semibold text-oxblood">&ldquo;{searchQuery.trim()}&rdquo;</span>
  </p>
)}
```

The zero-result state that #619 currently lands in (`:416-438`) — it already names postcodes in its
copy, which is the second half of the broken promise:

```tsx
<h2 className="mt-4 text-base font-semibold text-oxblood">No kitchens found</h2>
<p className="mt-1 text-sm text-slate-600">
  {searchQuery
    ? "Try a different dish, cuisine or postcode — or browse everything."
    : "No kitchens are currently available."}
</p>
```

The server seed (`frontend/app/shop/page.tsx:55-84`) hands page 0 down and must stay server-rendered
(SSR/SEO, `#507`/`#447`); `loadShopList` (`lib/storefront-server.ts:104-116`) is the only place `q`
crosses to the internal core host:

```ts
export async function loadShopList({ page = 0, size = 12, q }: ShopListParams = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  const term = q?.trim()
  if (term) params.set("q", term)
  const r = await getJson<ShopList>(`/public/shops?${params.toString()}`)
  return r.state === "notfound" ? { state: "defer" } : r
}
```

`getJson` (`:71-83`) is the **three-valued** `ok | notfound | defer` contract — a non-answer must
never be presented as an authoritative one. A postcode search that fails upstream is a `defer`.

#### 6b. The three-state honesty heading — the exact strings and conditions to mirror

**File:** `frontend/components/marketing/near-you-row.tsx` (400 lines) · client island, `fetch`

This is the pattern #619's disclosure must copy, and its docblock is the written rule:

```
 *   no coordinate (initial, AND after a denial)  "Kitchens on J'Toye"
 *   coordinate held, shops inside the radius     "Kitchens near you"
 *   coordinate held, nothing inside the radius   "No kitchens within N miles —
 *                                                 here is everything on J'Toye"
```

The derivation (`:194-235`) — **derived, never hardcoded**:

```tsx
type Phase = "idle" | "locating" | "located" | "empty" | "error"

const [phase, setPhase] = useState<Phase>("idle")
const [nearby, setNearby] = useState<PublicShop[] | null>(null)

const located = phase === "located" && nearby !== null
// The server list is the fallback for EVERY non-located phase, including both
// error phases and the nothing-in-radius one.
const shops = located ? nearby : serverShops

const heading = located
  ? "Kitchens near you"
  : phase === "empty"
    ? `No kitchens within ${NEAR_YOU_RADIUS_TEXT} — here is everything on J'Toye`
    : "Kitchens on J'Toye"
```

The radius constants, and the rule that a quoted number must be **derived from the number sent**
(`:90-105`):

```tsx
export const NEAR_YOU_RADIUS_KM = 5
const NEAR_YOU_RADIUS_TEXT = formatMiles(NEAR_YOU_RADIUS_KM)   // "3.1 miles", NOT "3 miles"
const PAGE_SIZE = 8
```

The exclusion disclosure — **counted, never subtracted** (`:164-235`, `:330-356`):

```tsx
function withoutCoordinates(shops: PublicShop[]): PublicShop[] {
  return shops.filter((s) => s.latitude == null || s.longitude == null)
}
…
const serverListComplete =
  serverTotal != null ? serverShops.length >= serverTotal : serverShops.length < PAGE_SIZE
const nearbyListComplete =
  located && (nearbyTotal != null ? nearby.length >= nearbyTotal : nearby.length < PAGE_SIZE)
const beyondRadius =
  located && serverListComplete && nearbyListComplete
    ? Math.max(0, serverShops.length - nearby.length - unranked.length)
    : 0
```

```tsx
<div aria-live="polite" className="min-w-0">
  <h2 className="text-2xl font-bold text-oxblood">{heading}</h2>
  <p className="mt-1 min-h-[1.25rem] text-sm text-slate-600">
    {located && unranked.length > 0 && (
      <>
        {unranked.length === 1
          ? "1 kitchen has no location data yet, so it is not ranked here"
          : `${unranked.length} kitchens have no location data yet, so they are not ranked here`}
        {beyondRadius > 0 &&
          `, and ${beyondRadius} more ${beyondRadius === 1 ? "is" : "are"} further than ${NEAR_YOU_RADIUS_TEXT} away`}
        . <Link href="/shop" …>See every kitchen</Link>.
      </>
    )}
    {note}
  </p>
</div>
```

Not-ok handling (`:264-294`) — a 429/5xx is **not an answer**:

```tsx
if (!res.ok) throw new Error(`HTTP ${res.status}`)
```

**Rules 33-08 inherits from this file:**

- **Never write "near you" (or any proximity claim) into a branch that can render without a real
  coordinate.** Asserted as `getByRole('heading', { name: /near you/i }).toHaveCount(0)`, scoped to
  **headings** because `/` legitimately carries the phrase at three non-heading sites (primary CTA,
  Browse step body, `DishScroller` accessible name). Do not break those three.
- **Miles for the customer, kilometres on the wire.** `frontend/lib/distance.ts` is the single
  conversion (`MILES_PER_KM = 0.621371`, `formatMiles` → one decimal below ten miles, whole miles
  above). If the postcode search quotes a radius, quote it through `formatMiles`, never a second
  literal.
- **Reserved height + `aria-live="polite"`** around any status line that appears after an
  interaction — a shifting row reads as broken even where CLS excludes it.
- **Absolutely-positioned distance pill** (`shop-card.tsx:102-119`) so located and unlocated cards
  are byte-identical in height. `app/shop/shop-discovery-client.tsx`'s own local `ShopCard`
  (`:25-142`) is a **different component** and currently renders **no** distance at all — if postcode
  search returns `distanceKm`, that card needs the pill added, and its height must not change.

Wire type (`frontend/types/storefront.ts:30-41`) — optional **and** nullable, for old-backend
tolerance:

```ts
  /**
   * Kilometres from the coordinate the caller supplied — 33-06's
   * `GET /public/shops?lat=&lon=&radiusKm=`. NULL on every unlocated response,
   * and absent entirely from an older backend, hence optional-AND-nullable …
   */
  distanceKm?: number | null
```

---

## Shared Patterns

### RFC 7807 typed errors

**Source:** `core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java:69-73`
**Apply to:** every new rejection on `GET /public/shops`

```java
@ExceptionHandler(IllegalArgumentException.class)
public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    problem.setType(URI.create("https://jtoye.uk/errors/invalid-argument"));
    …
}
```

Live type URIs already asserted by tests: `https://jtoye.uk/errors/invalid-argument` (thrown
`IllegalArgumentException`) and `https://jtoye.uk/errors/type-mismatch` (bind failure). Assert
`$.type`, **not just the status** — a bare 400 tells a machine consumer nothing. `handleIllegalArgument`
deliberately **does not log** (the detail may carry caller-supplied text).

### RLS / tenant handling

**Apply to:** the whole 33-08 read path.

- `postcode_centroid` has **no `tenant_id`, no RLS policy, no `_aud`** (`V61__postcode_centroid.sql:54-80`)
  and is exempt **by addition** in `RlsContractTest.EXEMPT_TABLES:133`. Safe to query with no
  `TenantContext` and no `set_config` pin.
- The anonymous storefront read is **cross-tenant by design**: `shops_public_read` is
  `((published = true) OR (tenant_id = current_tenant_id()))`, so with no GUC only the first limb
  applies. `PublicStorefrontDistanceIntegrationTest:529-537` asserts that a distance result resolves
  to **more than one tenant** — copy that arm, because "some shops came back" would pass while a
  stray tenant filter silently halved the directory.
- Do **not** add a tenant filter, and do **not** call `TenantContext.set` on this path.

### Configuration

**Source:** `application.yml:330-358` (owned by 33-02) + `PublicStorefrontService:76-97`
**Apply to:** any new tunable.

```java
@Value("${jtoye.geo.default-radius-km}") double defaultRadiusKm,
@Value("${jtoye.geo.max-radius-km}") double maxRadiusKm
```

**No inline `:default`.** A missing key must fail startup loudly (the recorded eight-outbox-tunables
defect). Test profile note: `application-test.yml:32-43` sets
`jtoye.geo.postcode-import.enabled: false`, so **`postcode_centroid` is EMPTY in every integration
test** unless the test seeds it or re-enables the importer against the 7-row fixture.

### No `PageImpl`

`PublicStorefrontService:276-278` — always `page.map(…)`, never
`new PageImpl<>(content, pageable, total)`, which rewrites the total when `offset + size > total`.

---

## Test Patterns

### Backend integration — `PublicStorefrontDistanceIntegrationTest.java` (575 lines)

The exact analog for a new `PublicStorefrontPostcodeSearchIntegrationTest`.

```java
@SpringBootTest
@AutoConfigureMockMvc          // (see the file's own header for the full annotation stack)
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class PublicStorefrontDistanceIntegrationTest {

    @Autowired private ShopRepository shopRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void seed() {
        // A fresh fixture per test: two arms below MUTATE coordinates, and a leftover swap would
        // silently invert the expectations of whichever test ran next.
        jdbc.update("DELETE FROM shops");
        …
        // NEAR is in a DIFFERENT tenant from MID and FAR on purpose … an ordering that spans
        // tenants is also the assertion that no tenant filter crept into this path.
        seedShop(tenantB, NEAR, NEAR_LAT, SHARED_LON, true);
        seedShop(tenantA, MID,  MID_LAT,  SHARED_LON, true);
    }

    private void seedShop(UUID tenantId, String slug, Double latitude, Double longitude, boolean published) {
        TenantContext.set(tenantId);
        try {
            Shop shop = new Shop();
            shop.setTenantId(tenantId);
            shop.setName(slug);
            shop.setSlug(slug);
            shop.setAddress("1 Fixture Street, London");
            shop.setLatitude(latitude);
            shop.setLongitude(longitude);
            shop.setPublished(published);
            shopRepository.saveAndFlush(shop);
        } finally {
            TenantContext.clear();
        }
    }
}
```

**Seeding `postcode_centroid` in a test** — `ShopCoordinateBackfillIntegrationTest:126-129`:

```java
// The reference table carries no tenant column and no RLS (33-02), so it is seeded as
// the bootstrap role. Real centroids, not invented numbers.
jdbc.update("INSERT INTO postcode_centroid (postcode, latitude, longitude) VALUES (?, ?, ?) "
        + "ON CONFLICT (postcode) DO NOTHING", "SE155BS", SE15_5BS_LAT, SE15_5BS_LON);
```

MockMvc + typed-error assertion style (`:466-513`):

```java
@Test
@DisplayName("out-of-range and incoherent parameters are typed 400s, not 500s and not clamps")
void invalidParametersAreTypedBadRequests() throws Exception {
    mockMvc.perform(get("/public/shops").param("q", "jollof")
                    .param("lat", String.valueOf(P_LAT)).param("lon", String.valueOf(P_LON)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/invalid-argument"));
}
```

Non-vacuity arms already proven in this file, to be mirrored:

- `.param("radiusKm", "8")` **widens** the result set → the parameter is honoured, not decorative
  (`:449-459`). The postcode equivalent: a postcode that is genuinely far away must return a
  *different* set, not merely a non-empty one.
- `jsonPath("$.totalElements")` is asserted **separately from content**, and the page size must be
  **smaller than the row count** or Spring Data never issues the `countQuery` at all
  (`PageableExecutionUtils.getPage`) and the assertion re-measures the content.
- The unlocated default is re-asserted unchanged (`:418-427`): 6 published shops, name-ascending,
  `distanceKm` absent. 33-08 must add the same regression arm for `q=jollof` / `q=SE15 5BS`.

### Backend unit — `PublicStorefrontServiceTest.java` (821 lines)

Mockito, and the **single construction site** of the service — it will not compile if the
constructor changes:

```java
@Mock private ShopRepository shopRepository;
…
service = new PublicStorefrontService(shopRepository, productRepository, orderRepository,
        eventPublisher, entityManager, paymentService, promotionRepository,
        announcementRepository, 5.0, 50.0);
```

### Geocoder unit — `PostcodeGeocoderTest.java` (187 lines)

Fixture-driven (`/geo/postcode-centroids-fixture.csv`, 7 real rows), repository mocked with a
`thenAnswer` over the fixture map, plus **an independent reference value** so the accuracy assertion
cannot compare the fixture to itself:

```java
/**
 * Independent reference for SE15 5BS — ONSPD-derived, via api.postcodes.io, fetched
 * 2026-08-08. Deliberately NOT read from the fixture: an accuracy assertion whose
 * expected value comes from the thing under test cannot fail.
 */
private static final double SE15_5BS_REF_LAT = 51.472436;
```

Plus the fixture's own invariant test (`:87-94`) — `assertThat(fixture).doesNotContainKey("SE154QA")`
— which exists so nobody can turn the unknown-postcode test green by making the product wrong.
**A district-centroid test needs the same guard**: the 7-row fixture has no two units sharing an
outward code, so a district test must extend the fixture and assert what it does *not* contain.

### Frontend Jest — `near-you-row.test.tsx` (464 lines)

```tsx
import { render, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { NearYouRow, NEAR_YOU_RADIUS_KM } from "@/components/marketing/near-you-row"

const mockFetch = jest.fn()
global.fetch = mockFetch as unknown as typeof fetch
```

Naming convention: `describe("<Component> — <state under test>")`, `it("<the claim, in prose>")`.
Two conventions worth copying verbatim:

- **Pair every absence assertion with a positive control.** `it("CONTROL: a spinner IS shown while
  the fix is outstanding")` at `:416` is what makes "no spinner" evidence.
- **`getByRole`, not `getByTestId`/`getByTitle`** — the streaming staging buffer (`<div hidden
  id="S:n">`) holds a second copy of the shell for ~300 ms and the latter two see it.

### Playwright — `near-you-row.spec.ts` (376 lines)

Role-scoped locators, a text-regex distance reader that makes the **unit** load-bearing, and
`domcontentloaded` (never `networkidle` — SSE breaks it):

```ts
const row = (page: Page) => page.getByRole("region", { name: "Dishes cooking near you" })
const nearYouHeading = (page: Page) => page.getByRole("heading", { name: /near you/i })

async function distancesShown(page: Page): Promise<string[]> {
  const text = await row(page).innerText()
  return text.match(/\d+(\.\d)? miles/g) ?? []
}

async function openLanding(page: Page) {
  await page.goto("/", { waitUntil: "domcontentloaded" })
  await expect(row(page)).toBeVisible({ timeout: 20_000 })
  await row(page).scrollIntoViewIfNeeded()
}
```

DB-mutating arms (`:284-336`) — the exact restore discipline to copy: `test.describe.serial`, a
**non-vacuity precondition** before the break, `psql`'s **command tag** as the evidence (never the
process exit status), and a restore **verified by content** in `afterAll`:

```ts
const tag = psql(`UPDATE shops SET latitude = NULL, longitude = NULL WHERE slug = '${VICTIM}';`)
expect(tag, "the UPDATE did not report one affected row").toBe("UPDATE 1")
expect(coordsOf(VICTIM), "the coordinates were not actually cleared").toBe("")
…
expect(coordsOf(VICTIM), "RESTORE FAILED: the coordinates were not put back")
  .toBe(`${original.lat}|${original.lon}`)
```

The existing storefront-search E2E is thin and is the natural place to extend —
`frontend/e2e/storefront-flows.spec.ts:136-146`:

```ts
test("search filters shops", async ({ page }) => {
  await page.fill('#shop-search', "Nigerian")
  …
  await page.fill('#shop-search', "xyznonexistent")
})
```

---

## No Analog Found

| Concern | Why there is no analog | What the planner should do |
|---|---|---|
| Query-level **interpretation disclosure** in a `Page<T>` response | Every `/public/**` list endpoint returns a bare `Page<Dto>`; nothing carries a query-level annotation | Pick from the three options in §5 and record the reason. `ShopConfigDto` / `GuestOrderConfirmation` are the wrapper-DTO precedents |
| **Outward-code (district) centroid** lookup | `PostcodeCentroidRepository` is `findById`-only by design, and `PostcodeGeocoderTest` asserts `"SE15"` → empty | Use measurements M-3/M-4; new method, not a loosened `TRAILING_POSTCODE` |
| Backend **"is this a postcode?"** shape test | None. The only UK-postcode regex in the repo is frontend-side: `frontend/app/shop/[slug]/checkout/page.tsx:27` — `/^[A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2}$/`, which requires the **inward** code and so does **not** match `SE22` either | A shape test may only *nominate a candidate*; the table decides (`PostcodeGeocoder` class doc) |
| **MCP tool** for postcode discovery | 33-06 recorded MCP as out of scope with a reason: `list_shops` targets the authenticated tenant-scoped `/api/v1/shops`, not the anonymous storefront | Re-record the same judgement, or state why it changed |

---

## Metadata

**Analog search scope:** `core-java/src/main/java/uk/jtoye/core/{storefront,shop,geo,common}`,
`core-java/src/test/java/uk/jtoye/core/{storefront,geo,security}`,
`core-java/src/main/resources/{application.yml,db/migration}`,
`core-java/src/test/resources/application-test.yml`,
`frontend/{app/shop,components/marketing,lib,types,e2e}`, `mcp-server/src/tools`
**Files read:** 24 · **Live DB measurements:** 7 queries against `jtoye-postgres`
**Pattern extraction date:** 2026-08-09
