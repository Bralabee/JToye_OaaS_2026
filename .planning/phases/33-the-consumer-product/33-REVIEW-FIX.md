---
phase: 33-the-consumer-product
fixed_at: 2026-08-09T11:23:13Z
review_path: .planning/phases/33-the-consumer-product/33-REVIEW.md
iteration: 1
findings_in_scope: 4
fixed: 4
skipped: 0
status: all_fixed
---

# Phase 33: Code Review Fix Report

**Fixed at:** 2026-08-09T11:23:13Z
**Source review:** .planning/phases/33-the-consumer-product/33-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 4 (fix_scope critical_warning: 0 Critical, 4 Warning; 7 Info out of scope)
- Fixed: 4
- Skipped: 0

Every fix follows the project's Proof Standard 1: the new test arms were run against the
unfixed tree FIRST and observed failing with exactly the defect the review describes, then
again after the fix. Both directions' outputs are recorded per finding below.

## Fixed Issues

### WR-01: NearYouRow's exclusion disclosure computed false statements past one page

**Files modified:** `frontend/components/marketing/near-you-row.tsx`,
`frontend/app/page.tsx`, `frontend/components/marketing/__tests__/near-you-row.test.tsx`
**Commit:** 16a59924
**Applied fix:** The "N more are further than 3.1 miles away" arithmetic now runs ONLY when
both lists are provably complete: the landing page passes the unlocated listing's
`totalElements` into the island as a new `serverTotal` prop, the island captures the located
response's `totalElements` as `nearbyTotal`, and `beyondRadius` is computed only when each
page's length covers its total (falling back to `length < PAGE_SIZE` when a total is
unavailable — a full page is then treated as possibly truncated, so suppression, never
fabrication). The `unranked` disclosure is deliberately left rendering when truncated: "N
kitchens have no location data" stays true of the shops it counted, merely incomplete,
while the subtraction becomes wrong — per the review, its true total needs an API the
backend does not yet expose.
**Fail direction (recorded):** with 8 server shops of 12 (`serverTotal={12}`) and 6 located,
the pre-fix component rendered `2 more kitchens are further than 3.1 miles away.` — a claim
derived from two truncated samples of different orderings. Post-fix: suppressed.
**Non-vacuity control:** a companion arm pins that when the totals confirm the page IS the
census (8 of 8, 6 of 6), the identical arithmetic still renders `2 more kitchens are further
than 3.1 miles away` — the clause was gated, not killed. Suite: 20/20; `npm run build` clean.

### WR-02: The shop write path could persist a half-updated coordinate pair

**Files modified:** `core-java/src/main/java/uk/jtoye/core/shop/dto/CreateShopRequest.java`,
`core-java/src/main/java/uk/jtoye/core/shop/ShopService.java`,
`core-java/src/test/java/uk/jtoye/core/shop/ShopServiceGeocodeTest.java`
**Commit:** 8e961779
**Applied fix:** Both of the review's suggested layers, because each covers a hole the other
cannot:
- **DTO pairing constraint** — `@AssertTrue @JsonIgnore isCoordinatePaired()` on
  `CreateShopRequest` ("latitude and longitude must be supplied together"), surfacing as the
  RFC 7807 typed 400 with `errors.coordinatePaired`. `@JsonIgnore` keeps the derived
  property out of Jackson and the springdoc schema.
- **Service last line** — the review's snippet checked the ENTITY, which cannot work on
  update: the IGNORE-null mapper has already merged the client's lone half next to the
  persisted other half, so the entity always looks like a full pair. `applyCoordinate` now
  receives the REQUEST and, on a geocode miss, a lone request axis resets the whole pair to
  the persisted state — the adaptation is documented in the code.
**Fail direction (recorded):** 5 new arms, all failing pre-fix: a lone latitude produced
zero constraint violations; POST with a lone latitude returned 201; create persisted
`(51.47, null)`; update persisted the Frankenstein `(51.5074, -0.070047)` — the client's
latitude fused with the persisted longitude. Post-fix: 22/22 in the class, plus
`ShopServiceTest` (44 total unit tests) and `PublicStorefrontDistanceIntegrationTest`
(14 Testcontainers tests) green.
**OpenAPI contract proof:** `./gradlew :core-java:generateOpenApiSpec` (full context boot,
`outputs.upToDateWhen false`) produced a spec byte-identical to the committed
`docs/api/openapi-snapshot.json`; `coordinatePaired` appears 0 times. No snapshot
regeneration required.

### WR-03: The client-coordinate fallback was a self-placement vector in public ranking

**Files modified:** `core-java/src/main/java/uk/jtoye/core/shop/ShopService.java`,
`core-java/src/test/java/uk/jtoye/core/shop/ShopServiceGeocodeTest.java`
**Commit:** 4f1f9d6f
**Applied fix:** Exactly the review's cheap containment, no precedence redesign: a
client-supplied fallback pair must lie inside the UK bounding box (lat 49.8–60.9,
lon −8.7–1.8 — includes Northern Ireland, the legitimate population the fallback exists
for). Outside the box it is discarded (falling through to the persisted coordinate, or
none) with an `event=client_coordinate_rejected` WARN; an accepted fallback leaves an
`event=client_coordinate_accepted` WARN naming the shop slug so operator review is
possible. The precedence docblock now records the integrity consequence and its accepted
residual (placement abuse WITHIN the box remains possible until the `coordinate_source`
column / verified-override feature — the longer-term work the review itself defers).
**Fail direction (recorded):** 3 new arms, all failing pre-fix: create persisted New York
`(40.7128, -74.006)`; update let Tokyo `(35.6762, 139.6503)` displace a persisted London
pair; and no `client_coordinate_accepted` WARN existed. Post-fix: all green, including the
accept-direction control — a Belfast pair `(54.5973, -5.9301)` still stands and is
WARN-logged (asserted via logback ListAppender, the project's established pattern).

### WR-04: ShopCard rendered "£0.00 delivery" / "min £0.00" for null wire values

**Files modified:** `frontend/types/storefront.ts`,
`frontend/components/marketing/shop-card.tsx`,
`frontend/components/marketing/__tests__/shop-card.test.tsx`,
`frontend/app/shop/shop-discovery-client.tsx`,
`frontend/app/shop/[slug]/shop-detail-client.tsx`
**Commit:** f3e1d29f
**Applied fix:** Backend premise verified before deciding, per instruction:
`PublicShopDto.deliveryFeePennies`/`minimumOrderPennies` ARE nullable `Long`s and
`CreateShopRequest` has no delivery-fee field, so an API-created shop genuinely sends
`deliveryFeePennies: null`. The honest direction, not a default-to-0: `PublicShop` now
declares both as `number | null`; the card renders NO delivery line for a null fee (an
unknown fee is neither "£0.00" nor "Free" — free needs a wire 0) and no minimum line for a
null OR zero minimum (matching the discovery listing); the "·" separator prints only when
the fee line before it exists. The two sibling surfaces were made null-explicit to keep the
type gate green with byte-identical behaviour (their pre-existing null-renders-as-free is
annotated in place as its own recorded question, deliberately not changed under this
finding). `FloatingCartBar` receives `?? 0`, which is what its `> 0` gates already made of
null.
**Fail direction (recorded):** pre-fix the card rendered
`£0.00 delivery · min £15.00` for a null fee and ` · min £0.00` for a null minimum — the
former being the exact string the suite's own zero-fee test forbids. Post-fix: full Jest
suite 97/97 suites, 884/884 tests; `npm run build` (the frontend type gate) clean.

## Notes for the verifier

- **Metrics reconciliation** (commit fac076cc): the fixes add 9 Java `@Test` methods and 4
  Jest `it` blocks. `docs/metrics.json` regenerated via `scripts/docs-freshness.sh --write`
  (1524→1533 Java, 880→884 Jest, 2628→2641 total); prose counts reconciled in `README.md`,
  `CLAUDE.md`, `AGENTS.md`. Both gates re-run green — and the prose gate was observed
  FAILING with 10 findings between the regen and the prose edit, so it can fail.
- **Suites run:** full frontend Jest + `next build`; Java `ShopServiceGeocodeTest`,
  `ShopServiceTest`, `GeoBoundsTest`, `PostcodeGeocoderTest` (unit) and
  `PublicStorefrontDistanceIntegrationTest` (Testcontainers). The full Java suite was not
  re-run here; the touched surface is the shop write path and DTO validation, and no
  existing test sends a coordinate on a shop request (checked, with a proven-capable
  pattern). A full-suite pass remains the phase gate's job.
- **Runtime parity:** these commits change core-java and frontend sources; any handed-back
  runtime must be REBUILT before E2E (compose start does not rebuild).

---

_Fixed: 2026-08-09T11:23:13Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
