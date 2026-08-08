# Phase 33 — The Consumer Product — RESEARCH

**Researched:** 2026-08-08
**Domain:** UK open geospatial data · Postgres distance queries under FORCE RLS · Next.js 16 server/client boundary · Keycloak 24 identity brokering
**Confidence:** HIGH on D-1 (dataset, licence, shipping, distance mechanics — measured on the live stack and against the real dataset). MEDIUM on #432 (Google's verification policy is the one term I could not pin exactly).

> Every measurement in this document was taken on 2026-08-08 against the running
> `docker-compose.full-stack.yml` stack and the actual Code-Point Open 2026-08 release.
> **Re-measure before quoting any figure here** — same reason `CRITERIA-DECAY-2026-08-08.md`
> has a date in its title.

---

<user_constraints>

## User Constraints (from CONTEXT.md)

### Locked Decisions

**D-1 — Shop coordinates come from the postcode, via ONS/OS open data.**
Shops already store full UK addresses with postcodes (`48 Rye Lane, Peckham, London SE15 5BS`).
Coordinates derive from the postcode using ONS Postcode Directory / OS Open Names — **open data, no
API key, no per-call cost, no new vendor**.

*Why not a geocoding API:* this roadmap is already blocked on five commercial decisions (production
domain, hosting target, Stripe keys, ADR-0002 sign-off, WhatsApp Business account). A sixth would
gate the substrate the rest of the phase sits on.

*Why not manual entry:* food vendors do not know their coordinates, a dropped minus sign is
undetectable without a validation map, and it contradicts the platform's own "go live in a day"
promise.

*Accepted trade-off:* postcode-centroid accuracy (~100 m), not door-level, and UK-only. Both are
acceptable for "shops near me" ranking on a UK-only platform. **State this limit in the plan** — do
not let a later reader assume door-level precision.

**Open research item for the planner/researcher:** which specific dataset and licence (ONSPD vs OS
Open Names vs OS Code-Point Open), how it ships (bundled table, migration seed, or build-time
fetch), and its update cadence. This is the one genuinely unresearched part of D-1.

**D-2 — #453 is carved out as a decision ticket, not built.** The phase ships **no code** for #453
and satisfies SC-3's second limb with a recorded decision. Rejected in session: routing to the
tenant's own `GROUP_ADMIN` (the reviewed party becomes the reviewer); creating a cross-tenant
operator identity (contradicts the recorded architecture constraint).

**D-3 — Scope is the P1 substrate plus the visible lie.**

| | issue | why |
|---|---|---|
| **IN** | **#460** | populate coordinates, then locality/radius a customer can observe |
| **IN** | **#544** | "Cooking near you" resolves to real published shops |
| **IN** | **#432** | customer-realm identity providers, **or** a dated deliberate decision to skip |
| OUT | #453 | decision ticket — D-2 |
| OUT | #458 | its nav half already shipped; only the dispatch half is open — own slice |
| OUT | #452, #545, #546, #285 | later phase; #545/#546/#285 are SC-6 and were never measured |

Roughly three plans. The test is that the slice is falsifiable end to end: a customer can be shown
real shops ordered by real distance.

### Claude's Discretion

CONTEXT.md has no `## Claude's Discretion` section. The one item explicitly delegated is the D-1
open research item quoted above (dataset / licence / shipping / cadence) — answered in
[§ D-1 Resolution](#d-1-resolution-the-primary-research-question). Everything else in this document
that is not a locked decision is a **recommendation**, and any item that changes user-visible
behaviour or incurs a standing cost is surfaced in [§ Open Questions](#open-questions) rather than
assumed.

### Deferred Ideas (OUT OF SCOPE)

Fixing #453, #452, #545, #546, #285, or #458's dispatch half. Re-planning #458's nav half, which
shipped in `b9f80f81` (#508) and `96d8432f` (#591).

</user_constraints>

<phase_requirements>

## Phase Requirements

| ID | Description (from `.planning/REQUIREMENTS.md:148-151`) | Research Support |
|----|-------------|------------------|
| **CUST-01** | Locality exists as a concept and nothing on the storefront is fictional — device location used, shop coordinates read, a delivery radius enforced, and "Cooking near you" resolving to real published shops. Closes #460 and #544. | §§ D-1 Resolution, Distance Query Mechanics, Frontend Geolocation, The Unlocated Default |
| **CUST-02** | No lifecycle dead-ends… `MANUAL_REVIEW` reaches a surface a human can act from (or a recorded decision names who adjudicates it)… | **D-2: no code.** Recorded-decision limb only. No research needed beyond confirming the no-platform-operator constraint, which CONTEXT.md already carries. |
| **CUST-03** | Consumer sign-up has more than one route in — `jtoye-customers` realm's `identityProviders: 0` is populated or recorded as a dated deliberate decision. Closes #432. | § #432 Customer-Realm Identity Providers |
| **CUST-04** | Customer-facing surface reviewed against what it renders (#546, #545, #285). | **OUT OF SCOPE per D-3.** Not researched. |

</phase_requirements>

---

## Summary

The one genuinely open decision — D-1's dataset — resolves to **OS Code-Point Open**, not ONSPD, and
the deciding factor is not accuracy or convenience but **licence containment**, the same axis on
which ADR-0004 rejected a datastore over ODbL share-alike. ONSPD is the more convenient dataset (it
ships WGS84 latitude/longitude already computed) but it contains Northern Ireland postcodes, and ONS
states in terms that NI data carries an **internal-business-use-only** End User Licence with
commercial use requiring a separate licence direct from Land and Property Services. Choosing ONSPD
would therefore *create* the sixth commercial decision that D-1 exists to avoid. Code-Point Open is
Great-Britain-only by construction (`"areas":["GB"]`, verified from the OS product API), pure OGL
v3, commercially usable, and needs no negotiation with anyone.

Its one cost is that coordinates are British National Grid eastings/northings, not latitude and
longitude. That cost was measured, not estimated: a 7-parameter Helmert transform implemented from
the published OS constants reproduces ONSPD's own WGS84 values across a 25-postcode GB sample to a
**mean of 1.45 m and a maximum of 3.28 m** — between 30× and 70× tighter than the ~100 m
postcode-centroid error D-1 already accepted. The whole of GB transforms in **12 seconds**, and the
derived `postcode,lat,lon` artefact is **45.6 MB raw / 15.1 MB gzipped** over **1,748,230** rows.
Loaded into the live Postgres 15 that number is **149 MB** (98 MB heap + ~51 MB index) with a 0.03 ms
primary-key lookup.

PostGIS is confirmed absent and `earthdistance` is not usable here — not because it is missing but
because **Flyway runs as `jtoye_app`, which is `NOSUPERUSER` and lacks `CREATE` on the database**, so
`CREATE EXTENSION` fails for even the *trusted* `cube`. Verified empirically inside a rolled-back
transaction. The correct approach is a plain-SQL haversine with a leakproof bounding-box prefilter:
float8 comparison operators are `proleakproof = t` and can therefore be pushed beneath the RLS
security barrier and use a btree index, while `sin`/`cos`/`asin`/`radians` are `proleakproof = f` and
are evaluated above it — which is the exact mechanism V44 documented for `ts_match_vq`. The
unauthenticated storefront needs no new cross-tenant surface: the existing `shops_public_read` policy
already reads `((published = true) OR (tenant_id = current_tenant_id()))`.

The frontend question has an answer already living in the repo. `frontend/components/marketing/hero-search.tsx`
is a `"use client"` island inside the server-rendered landing page and its docblock states the rule
verbatim; `frontend/app/shop/page.tsx` is the fuller pattern (Server Component fetch via
`lib/storefront-server.ts` seeding a client island). Copy it. Do **not** add a geolocation cookie —
`#116`'s cookie banner has not shipped, precise location is personal data under UK GDPR, and a cookie
would create a PECR question this phase cannot close.

**Primary recommendation:** ship OS Code-Point Open, transformed offline to WGS84 by a committed
script, as a committed `postcode-centroid` seed loaded by an idempotent importer (Flyway owns the DDL,
not the data); rank with a bounding-box-prefiltered haversine in native SQL against the existing
`shops_public_read` policy; render the landing row server-side from real published shops under a
heading that is true without a location, and upgrade it to distance-ordered "near you" from a client
island only once the browser has actually returned a coordinate.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Postcode → WGS84 centroid reference data | Database / Storage | — | Static reference data, no tenant dimension, needed by any writer of `shops.latitude` |
| Postcode extraction from free-text `Shop.address` | API / Backend (`ShopService`) | — | One implementation, shared by the API write path and the dev seeder; must not be duplicated in SQL and Java |
| Shop coordinate population (new + updated shops) | API / Backend | — | `ShopMapperImpl` already writes `latitude`/`longitude` from `CreateShopRequest`; geocode fills the gap when the client supplies neither |
| Shop coordinate backfill (existing NULL rows) | API / Backend | Database | `shops` is `FORCE RLS`; a bare `UPDATE` sees zero rows (recorded V25→V44→V57 trap). Must be tenant-looped |
| Distance filter + ordering | Database (native SQL) | — | Ordering must be authoritative and pageable; doing it in Java would need the full result set in memory |
| Distance *display* value | API / Backend | — | Computed once in SQL and projected out, so the number shown and the order shown cannot diverge |
| Device location acquisition | Browser / Client | — | `navigator.geolocation` is browser-only, permission-gated, secure-context-only |
| Truthful default listing (no location) | Frontend Server (SSR) | — | Must be in the initial HTML — an effect-fetched row renders an empty band to a crawler and to a slow connection (#507's measured complaint) |
| Distance-ordered listing (located) | Browser / Client → API | Frontend Server | Client island holds the coordinate in React state only and calls the public API |
| Social identity brokering | Keycloak realm config | Frontend Server (CSP) | IdP lives in `jtoye-customers`; the browser-side PKCE flow means CSP `form-action` must permit the realm subtree |

---

## D-1 Resolution (the primary research question)

### 1. Which dataset

**Recommendation: OS Code-Point Open.**

| | ONSPD | **Code-Point Open** | OS Open Names |
|---|---|---|---|
| Coverage | Full UK incl. NI | **GB only** (`"areas":["GB"]`) | GB |
| Coordinates | Eastings/northings **and** WGS84 lat/long | Eastings/northings only (OSGB36) | Not confirmed for postcode units |
| Postcode granularity | Unit | **Unit** (1,749,109 rows measured) | Postcode presence advertised; unit-level granularity **UNVERIFIED** |
| Licence | OGL v3 **+ an NI internal-use-only EUL** | **OGL v3, clean** | "Free to use for everyone"; exact terms UNVERIFIED on the product page |
| Cadence | Quarterly (Feb/May/Aug/Nov) | Quarterly (current version `2026-08`) | Quarterly |
| Download | Free, Open Geography Portal | **Free, no API key** (verified: HTTP 200 unauthenticated) | OS Data Hub |

**Reasoning, in the order it actually decides the question:**

1. **ONSPD's Northern Ireland terms are the disqualifier.** ONS states: *"If you also use the
   Northern Ireland data (postcodes starting with 'BT'), you need a separate licence for commercial
   use direct from Land and Property Services"*, *"We only issue a Northern Ireland End User Licence
   (for internal business use only) with the data"*, and *"Use of the Northern Ireland data contained
   within the ONS postcode products constitutes acceptance of the Northern Ireland licensing terms
   and conditions."* [CITED: ons.gov.uk/methodology/geography/licences]. J'Toye is a commercial
   multi-tenant SaaS. Adopting ONSPD therefore introduces exactly the class of blocking commercial
   negotiation that D-1's own rationale forbids. **Whether filtering BT rows at ingest avoids the
   term is a question I cannot answer definitively** — the "constitutes acceptance" sentence is
   ambiguous about mere possession, and I am not qualified to resolve it. Code-Point Open makes the
   question moot by containing no NI data at all.
2. **Code-Point Open is unit-level and complete for GB.** Measured on the real 2026-08 archive:
   1,749,109 rows, of which 1,748,230 carry usable coordinates.
3. **OS Open Names is not the right shape.** It is a gazetteer of named places, roads and postcodes;
   the product page advertises "streets and postcodes" but does not state postcode-unit granularity
   or CRS, and I could not verify either. Using it would mean accepting an unverified granularity for
   a ranking substrate. Rejected on evidence grounds, not on merit.
4. **The cost of choosing Code-Point Open — the coordinate transform — is measurably negligible.**
   See §4 below.

**The product limitation this creates, which the plan must state explicitly:** Code-Point Open covers
**Great Britain only**. A vendor in Northern Ireland will not geocode. That is a deliberate, recorded
consequence of avoiding a sixth commercial decision, and the remedy (an LPS commercial licence) is a
business decision, not an engineering one. Do not let this be discovered later as a bug.

### 2. Licence

**OGL v3, commercial use permitted, three attribution statements required.**

The licence text shipped *inside* the archive (`Doc/licence.txt`, read verbatim from the 2026-08
download) is [VERIFIED: Code-Point Open 2026-08 `Doc/licence.txt`]:

```
ORDNANCE SURVEY DATA LICENCE

Your use of data is subject to terms at www.ordnancesurvey.co.uk/opendata/licence.

Contains Ordnance Survey data © Crown copyright and database right 2026.

Contains Royal Mail data © Royal Mail copyright and database right 2026.
Contains National Statistics data © Crown copyright and database right 2026.
```

- **Which licence.** OS replaced the bespoke OS OpenData Licence with **Open Government Licence v3**
  in February 2015 [CITED: wiki.openstreetmap.org/wiki/Ordnance_Survey_OpenData].
- **Commercial use.** OGL v3 permits copying, publishing, adapting and **exploiting the information
  commercially**, sub-licensed onward, subject to attribution. OS's own guidance states OpenData may
  be used "in any way and for any purpose" [CITED: same]. There is **no share-alike / copyleft
  clause** — this is the material contrast with the ODbL concern recorded in ADR-0004. Bundling the
  derived table inside a proprietary SaaS is permitted and does not infect the product's licence.
- **Attribution required.** All three lines above must be reproduced, with the year matching the
  release ingested. The plan must place them somewhere a user can reach — the obvious homes are a
  footer credit on the public storefront and an `ATTRIBUTION.md` / licence note beside the data file.
  **The year is not decorative**: it must be updated whenever the dataset is refreshed, which makes
  it a good candidate for a check that reads the year out of the committed artefact and compares it
  to the rendered credit.
- **What I could not confirm.** The exact current text at
  `www.ordnancesurvey.co.uk/opendata/licence` — the canonical URL in `licence.txt` — 404'd on fetch
  (the page has moved). The OGL-v3 identification rests on the OSM wiki and OS product pages rather
  than on that canonical page. Flagged in the Assumptions Log as **A1**; it does not change the
  recommendation but a lawyer-grade sign-off should read the live page.

### 3. How it ships

**Recommendation: Flyway owns the DDL; a committed, derived, gzipped CSV owns the data; an
idempotent application-side importer loads it with `COPY`.**

Measured costs, all on this machine / this stack:

| Quantity | Measured value |
|---|---|
| Upstream download (`codepo_gb.zip`, CSV) | **14,461,176 bytes (13.8 MB)**, md5 `42ecd9a7db141608dc6ab63f2dfb0bc3` |
| Upstream unpacked | 162,848,260 bytes across 129 files |
| Rows in upstream | 1,749,109 |
| Rows after dropping zero-coordinate rows | **1,748,230** |
| Derived `postcode,lat,lon` CSV | **45.6 MB raw / 15.1 MB gzip -9** |
| Transform wall time (whole of GB, awk) | **12.0 s** |
| Table in live `postgres:15-alpine` | **149 MB total** (98 MB heap + ~51 MB index) |
| PK lookup on 1.7M rows | **0.03 ms** (Index Scan, 4 buffers) |

**Why not a Flyway *data* migration.** A ~46 MB `INSERT`/`COPY` migration executes on every
Testcontainers-backed integration test that spins a fresh Postgres, and this repo has many
(`core-java` runs a dedicated `integrationTest` task with real Postgres + RLS). It would add minutes
per container for data no unit test needs. Keep Flyway to `CREATE TABLE postcode_centroid …` + index
— fast, ordinary, versioned.

**Why not a build-time fetch.** It makes `docker build` and CI network-dependent on `api.os.uk`, and
an offline or firewalled build silently produces a stack with no locality. The md5 is published (and
verified byte-exact above), so provenance *could* be proven — but availability could not.

**Why not a runtime service.** postcodes.io is excellent and OGL-licensed, and I used it here as an
*independent verification reference*. As a runtime dependency it is a new vendor, a network hop on
the unauthenticated storefront path, and a per-call availability risk. D-1 rules it out.

**The recommended shape:**

```
core-java/src/main/resources/db/migration/V61__postcode_centroid.sql   # DDL + index only
core-java/src/main/resources/geo/postcode-centroids.csv.gz            # 15.1 MB, committed
core-java/src/main/resources/geo/SOURCE.md                            # version, md5, attribution, regen command
scripts/regen-postcode-centroids.sh                                   # fetch → verify md5 → transform → gzip
```

- The importer runs once at startup, `COPY`s into a staging temp table only when
  `SELECT count(*) FROM postcode_centroid` is 0 (or when a stored `source_version` differs), and is a
  no-op thereafter. Gate it with `jtoye.geo.postcode-import.enabled` so `application-test.yml` can
  skip it and load a **small committed fixture** instead — that fixture is what makes integration
  tests deterministic and offline.
- `postcode_centroid` is reference data with no `tenant_id`. It must be added to
  `RlsContractTest.EXEMPT_TABLES` **with a written justification**, exactly as `tenants`,
  `revinfo` and `processed_stripe_events` are (`RlsContractTest.java:95-119`). Do not weaken the
  assertion; add the entry.

**The standing cost, stated plainly:** ~15 MB in git per refresh. Postcode centroids are stable;
annual refresh is defensible, quarterly is not required. See Open Question **Q-1** — a
sector-granularity table (~12k rows, sub-MB) is the alternative, and it trades ~100 m accuracy for
~500 m–1 km, which contradicts D-1's stated tolerance. That is the owner's call, not mine.

**Cadence.** Quarterly. The OS product API exposes the current version and per-file md5 with no
authentication:

```bash
curl -s https://api.os.uk/downloads/v1/products/CodePointOpen | jq -r .version           # 2026-08
curl -s https://api.os.uk/downloads/v1/products/CodePointOpen/downloads | jq -r '.[0].md5'
```

A cheap freshness gate can compare the committed `SOURCE.md` md5 against that endpoint and warn (not
fail) when upstream moves.

### 4. Distance query mechanics in Postgres 15 without PostGIS

**PostGIS is absent — verified with a control, not assumed.**

```
rg -uu -ni 'postgis|ST_Distance|ST_DWithin|earth_distance|earthdistance|cube' \
   core-java/ docker-compose.full-stack.yml k8s/ infra/     -> MATCH_RC=1, 0 lines
rg -uu -nil 'CREATE TABLE' core-java/src/main/resources/db/migration/ -> CONTROL_RC=0, 20 files
```

Live database, `postgres:15-alpine`:

```
SELECT count(*) FROM pg_available_extensions WHERE name='postgis';  ->  0
```

**`earthdistance`/`cube` are available but not installable by the migration role.** This is the
finding that decides the design, and it was measured, not reasoned:

```
     name      | version | trusted | superuser
---------------+---------+---------+-----------
 cube          | 1.5     | t       | t
 earthdistance | 1.1     | f       | t
```

`earthdistance` is **not trusted** and requires superuser. Flyway runs as
`${spring.datasource.username}` (`application.yml:105`) = `DB_USER` = **`jtoye_app`**, confirmed both
in `application-local.yml:9` and in the running container's env. `jtoye_app` is
`rolsuper=f, rolbypassrls=f` and `has_database_privilege('jtoye_app','jtoye','CREATE') = false`.
Empirically, inside a rolled-back transaction:

```
SET ROLE jtoye_app;  -- current_user=jtoye_app, is_super=f
CREATE EXTENSION IF NOT EXISTS cube;
ERROR:  permission denied to create extension "cube"
HINT:  Must have CREATE privilege on current database to create this extension.
```

So even the *trusted* extension fails. Adding an extension would mean either granting `jtoye_app`
`CREATE ON DATABASE` (a privilege escalation on the RLS-bound role — do not) or a V44-style
superuser-graceful `DO` block that degrades to a WARNING and leaves the feature dead in every
environment that matters. **Reject extensions. Use plain SQL.**

**The recommended query shape** — a leakproof bounding-box prefilter, an exact haversine above it:

```sql
-- ShopRepository, nativeQuery = true
SELECT s.*,
       2 * 6371.0088 * asin(sqrt(
           power(sin(radians(s.latitude - :lat) / 2), 2)
         + cos(radians(:lat)) * cos(radians(s.latitude))
         * power(sin(radians(s.longitude - :lon) / 2), 2)
       )) AS distance_km
  FROM shops s
 WHERE s.published = true
   AND s.latitude  IS NOT NULL
   AND s.longitude IS NOT NULL
   AND s.latitude  BETWEEN :latMin  AND :latMax     -- leakproof: float8ge / float8le
   AND s.longitude BETWEEN :lonMin  AND :lonMax
 ORDER BY distance_km ASC
```

**Why this shape and not `acos`.** The spherical-law-of-cosines form
(`acos(sin·sin + cos·cos·cos Δλ)`) is the one usually reached for, and it has a real failure mode:
for two nearly-identical points floating-point rounding pushes the argument to `1.0000000000000002`
and Postgres raises `ERROR: input is out of range`. That is triggered by a customer standing at the
shop's own postcode centroid — a plausible input, not a pathological one. The `asin`-of-haversine
form above is well-conditioned for small separations and needs no clamp. If you keep the `acos` form
for any reason it **must** be wrapped `LEAST(1.0, GREATEST(-1.0, …))`.

**Why the bounding box matters under RLS.** Measured on the live database:

```
 proname  | proleakproof        proname  | proleakproof
----------+-------------        ----------+-------------
 sin      | f                   float8lt  | t
 cos      | f                   float8le  | t
 acos     | f                   float8ge  | t
 asin     | f (not listed; same class)   float8gt  | t
 radians  | f                   float8eq  | t
```

Under a row-security barrier Postgres will not push a non-leakproof user qual beneath the policy, so
a haversine expression can never be an index qual — the same mechanism V44 documents at length for
`ts_match_vq`. The float8 comparisons **are** leakproof, so
`latitude BETWEEN … AND longitude BETWEEN …` can sit below the barrier and use a btree index on
`(latitude, longitude)`. Compute the box in Java:

```java
double latDelta = radiusKm / 111.045;
double lonDelta = radiusKm / (111.045 * Math.cos(Math.toRadians(lat)));   // guard cos → 0
```

At today's row counts (5 shops, 3 published) the index is irrelevant; the box is there so the query
does not need rewriting when it stops being irrelevant, and so the RLS interaction is correct by
construction rather than by luck.

**RLS and the unauthenticated storefront: no new surface is required.** The live policy set on
`shops` is:

```
 shops_rls_policy  | (tenant_id = current_tenant_id())
 shops_public_read | ((published = true) OR (tenant_id = current_tenant_id()))
```

With no tenant GUC set, `current_tenant_id()` returns NULL, the second limb is NULL, and
`published = true` is the sole limb — which is precisely how `findByPublishedTrue` already serves
`GET /public/shops` cross-tenant today. `ShopRepository.java:18-23` states this in a comment and
`PublicStorefrontService.java:171` is the call site. **Verified live:**
`GET http://localhost:9090/api/v1/public/shops?size=5` → HTTP 200, `totalElements=3`, three shops
from the demo tenant, `latitude=null`, `longitude=null`.

**Paging.** Follow the existing precedent — `fullTextSearchPublished` (`ShopRepository.java:54-57`)
is a native `Page<Shop>` with an explicit `countQuery`. Do **not** hand-build a `PageImpl` with a
supplied total: `new PageImpl<>(content, pageable, total)` silently *rewrites* `total` when
`offset + size > total` (recorded trap).

**Return the distance from SQL, not from Java.** Use a Spring Data interface projection carrying the
`PublicShopDto` fields plus `distanceKm`. If the ordering is computed in SQL and the displayed
number in Java, the two formulas can drift and produce a list whose printed distances are
non-monotonic — a defect that no unit test on either half would catch.

---

## Frontend Geolocation (secondary question 5)

**The pattern already exists in this repo. Copy it; do not invent one.**

`frontend/app/page.tsx` is a Server Component and its docblock states the constraint verbatim:

> *Server Component (no client directive) so the root layout's force-dynamic CSP nonce cascades
> through (the #89 failure mode).*

`frontend/app/layout.tsx:17` sets `export const dynamic = "force-dynamic"` and `frontend/middleware.ts`
mints the per-request nonce. Two client islands already sit inside that server page —
`hero-search.tsx` (whose own docblock says *"client island inside the Server-Component landing page —
app/page.tsx must stay server-rendered so the force-dynamic CSP nonce cascades, the #89 failure
mode"*) and `dish-scroller.tsx`. The fuller precedent is `frontend/app/shop/page.tsx`: a Server
Component that fetches via `lib/storefront-server.ts` (`loadShopList`) and seeds
`shop-discovery-client.tsx`. Its docblock records the measurement that motivated it — the page
previously served 36,829 bytes containing **zero** shop names because it fetched in a `useEffect`.

**The correct pattern for Phase 33:**

1. Make `page.tsx` `async` (still a Server Component — `async` does not require `"use client"`) and
   call `loadShopList({ page: 0, size: N })`.
2. Render real shops into the initial HTML under a heading that is true without a location.
3. Pass that server-rendered list as a `serverShops` prop into a new `"use client"` island
   (`components/marketing/near-you-row.tsx`).
4. The island renders `serverShops` verbatim until — and only until — it holds a real coordinate.

**Rejected: the cookie/header round trip.** It would work (the page is `force-dynamic`), but:
precise geolocation is personal data under UK GDPR; a cookie is sent on every matched request; and
**#116's cookie banner has not shipped** (it is Phase 31, LGL-01). Writing a location cookie now
creates a PECR consent question this phase cannot close. Keep the coordinate in **React state only** —
not `localStorage`, and `sessionStorage` only if the owner accepts the PECR argument that a
user-initiated "use my location" is storage strictly necessary for a service the user explicitly
requested. State-only sidesteps it entirely.

**Rejected: converting `page.tsx` to a client component.** It regresses the #89 nonce path and adds
one more page to #507/#542's count.

**Browser mechanics that must be in the plan:**

- **Secure context required.** *"This feature is available only in secure contexts (HTTPS)"*
  [CITED: MDN Geolocation API]. `http://localhost:3000` **is** a secure context, so local dev and
  Playwright work. Any plain-http non-localhost host (a LAN IP, a staging box without TLS) silently
  yields no location. Given `DEPLOY_*_ENABLED` is false and `jtoye.co.uk` DNS does not resolve, this
  is not testable outside localhost today.
- **Gesture-gate the prompt.** Calling `getCurrentPosition()` during render fires an unsolicited
  permission prompt on first paint. Trigger it from an explicit "Use my location" control.
- **`Permissions-Policy: geolocation`.** Default allowlist is `self`. Verify nothing in
  `next.config.mjs` or `lib/security-headers.ts` emits a `Permissions-Policy` that omits
  `geolocation` — if one exists the API is blocked with no console error worth reading.
- **Handle all three failures** — `PERMISSION_DENIED`, `POSITION_UNAVAILABLE`, `TIMEOUT` — with the
  same fallback as "never asked". A user who denies must not see a spinner.
- **Playwright has first-class support and this repo uses none of it yet** — verified:
  `rg -uu -n 'geolocation|setGeolocation|grantPermissions' frontend/playwright.config.ts frontend/e2e/`
  → `RC=1`, control (`rg -uu -nl 'test\(' frontend/e2e/`) → `RC=0`, 22 files. Use
  `context.grantPermissions(['geolocation'])` + `context.setGeolocation({latitude, longitude})`,
  which makes the located path **deterministic** — no real GPS, no flake, and both directions
  (granted / denied) are testable.

---

## The Unlocated Default (secondary question 6)

**Show real published shops, under a heading that does not claim to know where the user is.**

The roadmap's own words settle the content question: SC-2 requires *"'Cooking near you' resolves to
real published shops"* — **shops**, not dishes. Today `page.tsx:52-56` hardcodes five invented
*dishes* with invented vendors, ratings and prices. Replacing them with real shop cards is both the
literal criterion and the smaller change.

The seeded shops carry real logos (`DemoDataSeeder` sets `logoUrl` to `/brand/logo-mama-ades.png`
etc.), real `tags`, real `deliveryFeePennies` and real `minimumOrderPennies` — all already on
`PublicShop` (`frontend/types/storefront.ts:1-22`). Nothing needs inventing to fill a card.

**Three states, three headings — and the heading is the assertion:**

| State | Heading | Content |
|---|---|---|
| No location (server default, and after denial) | *"Kitchens on J'Toye"* — or any phrasing true without a coordinate | Real published shops, default order (`sort=name,ASC` as the endpoint already defaults) |
| Location granted, results found | *"Cooking near you right now"* | Same shops, distance-ordered, each card showing its real distance |
| Location granted, none within radius | *"No kitchens within {r} km — here's everything on J'Toye"* | Falls back to the unlocated list, explicitly |

This is what makes the criterion falsifiable in the direction that matters: **assert that the string
"near you" never appears in the DOM while no coordinate is held.** That check fails against today's
tree (the heading is unconditional at `page.tsx:180`) and fails again against any future
reintroduction — which is exactly the property CONTEXT.md demands.

⚠ **Do not reach for "Open now" as the unlocated heading without fixing the helper first.**
`frontend/lib/opening-hours.ts:74` reads `if (!hours || Object.keys(hours).length === 0) return true` —
`isOpenNow(null)` is **`true`**. A row headed "Open right now" built on that helper asserts openness
for every shop with no opening-hours data. That is a new fictional claim replacing an old one, which
is the precise failure #544 exists to stop.

⚠ **Row length.** There are 3 published shops and the current row renders 5 cards. The row must
render *N* real shops without padding, and must look deliberate at N=3. Do not backfill with
placeholders.

---

## #460's Five-Link Chain — What the Tree Actually Says

CONTEXT.md's chain re-verified independently, plus two links it does not mention.

| Link | State | Evidence (re-measured 2026-08-08) |
|---|---|---|
| 1 column exists | ✅ | `V16__public_storefront.sql:15-16` (`shops`), `:92-93` (`shops_aud`) |
| 2 entity ready | ✅ | `Shop.java:53-55`, getters/setters `:113-116` |
| 3 **populated** | ❌ | Live DB: `SELECT count(latitude) FROM shops` → **0** of 5 rows (3 published) |
| 4 read | pass-through | `PublicStorefrontService.java:720-721`; live API returns `latitude: null` |
| 5 used | ❌ | No distance logic, no device geolocation (controls in `CRITERIA-DECAY-2026-08-08.md`) |

**Two links CONTEXT.md does not name, both load-bearing:**

**Link 3a — the write path already exists and is unvalidated.** `CreateShopRequest` declares
`latitude`/`longitude` (`:24-25`) with **no** `@DecimalMin`/`@DecimalMax`, and the generated
`ShopMapperImpl` writes them on both paths:

```
ShopMapperImpl.java:67-68    shop.setLatitude( request.getLatitude() );      // create
ShopMapperImpl.java:111-115  if ( request.getLatitude() != null ) { … }      // update (null-safe)
```

So a tenant can `POST` `latitude: 999` today and it persists. Any geocoding work must (a) add range
validation and (b) decide precedence — client-supplied vs geocoded. **Recommendation:** geocode
authoritatively from the postcode and ignore client-supplied coordinates on the public write path,
or accept them only with explicit validation. The current silent-accept is the worse of both.

**Link 3b — `DemoDataSeeder` is `@Profile("dev")` and is not the production write path.**
`DemoDataSeeder.java:94-96` — `@Component @Profile("dev") … implements ApplicationRunner`. Fixing
the seeder fixes the demo; it does **not** populate a real vendor's shop. Both paths need the same
`PostcodeGeocoder`, called from `ShopService.createShop`/`updateShop` **and** from the seeder.

### ⚠ One of the three seeded postcodes does not exist

Measured against Code-Point Open 2026-08, with a control on the same corpus and the same machinery:

```
grep -rh -F '"SE15 4QA"' Data/CSV/   -> RC=1, no output          (Peckham Jollof Co.)
grep -rh -F '"SE15 5BS"' Data/CSV/   -> RC=0, row returned       (Mama Ade's Kitchen)  [CONTROL]
```

Independently confirmed against ONSPD via postcodes.io: `SE154QA` → **HTTP 404, "Postcode not
found"**, while `SE155BS` and `SW98PS` return 200. `12 Bellenden Road` is a real street; live
postcodes on it are `SE15 4QJ / 4QL / 4QN / 4QR / 4QS / 4QW / 4QY`.

Two consequences the plan must handle:

1. **Correct the seed data**, or "Peckham Jollof Co." will have NULL coordinates and vanish from
   every distance-ranked result — a regression-by-omission introduced by the very change meant to fix
   locality.
2. **This is a free, permanent negative control.** Keep one deliberately-unknown postcode in the
   fixtures so the "unknown postcode → coordinates stay NULL, shop excluded from distance results,
   no crash, no (0,0)" path is exercised by a test that can actually fail.

---

## Standard Stack

### Core — no new runtime dependencies

| Component | Version | Purpose | Why standard |
|---|---|---|---|
| PostgreSQL | 15-alpine (in use) | `postcode_centroid` table; haversine in SQL | Already the database; `earthdistance` unusable (see §4) |
| Spring Data JPA native query | Boot 3.5.16 | Distance filter/order + `Page` with `countQuery` | Existing precedent: `ShopRepository.fullTextSearchPublished` |
| Flyway | in use | `V61__postcode_centroid.sql` — **DDL only** | Data volume belongs outside the migration chain |
| Next.js App Router | 16.2.12 | Server Component + client island | Existing precedent: `app/shop/page.tsx` + `shop-discovery-client.tsx` |
| `navigator.geolocation` | Web platform | Device location | Zero-dependency; no library adds anything |
| Playwright | 1.62.1 | `grantPermissions` + `setGeolocation` | Deterministic located/denied E2E, no new package |
| Keycloak | 24.0.5 | `identityProviders[]` in `jtoye-customers` | Realm config, not code |

### Deliberately NOT added

| Tempting | Why not |
|---|---|
| PostGIS | Not available in `postgres:15-alpine` (`pg_available_extensions` → 0 rows); a heavyweight image swap for one `ORDER BY` |
| `earthdistance` + `cube` | `CREATE EXTENSION` fails as `jtoye_app` — measured. Enabling it means granting the RLS role `CREATE ON DATABASE` |
| A geocoding SDK (Google/Mapbox/HERE) | Excluded by D-1 — API key, per-call cost, sixth commercial decision |
| postcodes.io as a runtime dependency | New vendor + network hop on the anonymous storefront path. Excellent as a *verification reference*; used that way here |
| A UK-postcode npm/Maven validation library | The lookup table **is** the validator — a hit is proof, a miss is proof. A regex that disagrees with the dataset is worse than no regex |
| A JS geolocation wrapper (`react-geolocated` etc.) | ~40 lines of `useState` + `getCurrentPosition`; a dependency here is pure supply-chain surface |

### Alternatives considered

| Instead of | Could use | Tradeoff |
|---|---|---|
| Code-Point Open | ONSPD | Ships WGS84 directly (no transform) but drags in the NI/LPS commercial-licence question — see §1 |
| Unit-level table (15.1 MB) | Sector-level (~12k rows, sub-MB) | ~500 m–1 km accuracy vs D-1's stated ~100 m tolerance. **Open Question Q-1** |
| Committed derived CSV | Build-time fetch, md5-pinned | No repo growth; makes builds network-dependent on `api.os.uk` |
| SQL-computed distance | Java-computed distance | Java needs the whole result set in memory to sort, and duplicates the formula |

**Installation:** none. No package is added to `package.json`, `build.gradle.kts`, `go.mod` or
`mcp-server/package.json` by any recommendation in this document.

---

## Package Legitimacy Audit

**Not applicable — this phase installs no external packages.**

The Package Legitimacy Gate was not run because there is nothing to check: every recommendation
above uses a dependency already present in the tree, or the web platform. If the planner introduces
a package that is not in this document, the gate must run before that package reaches a plan.

One non-package external artefact **is** ingested, and it gets the equivalent treatment:

| Artefact | Source | Integrity | Disposition |
|---|---|---|---|
| `codepo_gb.zip` (Code-Point Open 2026-08) | `https://api.os.uk/downloads/v1/products/CodePointOpen/downloads?area=GB&format=CSV&redirect` | md5 `42ecd9a7db141608dc6ab63f2dfb0bc3` published by the OS API and **verified byte-exact on download** | Approved. The regeneration script must verify the md5 and **fail closed** on mismatch |

---

## Architecture Patterns

### System architecture — how a located request flows

```
  Browser (customer, unauthenticated)
    │
    │ (1) GET /                     ── no coordinate yet
    ▼
  Next.js Server Component  app/page.tsx           [force-dynamic, CSP nonce]
    │   loadShopList()  →  lib/storefront-server.ts
    │                          │  fetch(coreBaseUrl + /public/shops)   cache:no-store
    ▼                          ▼
  HTML with REAL shops   ◄── Core API  GET /api/v1/public/shops
  heading: location-free       │            PublicStorefrontService.listPublishedShops
    │                          │            ShopRepository.findByPublishedTrue
    │                          ▼
    │                     Postgres — RLS policy shops_public_read
    │                       ((published = true) OR (tenant_id = current_tenant_id()))
    │                       no tenant GUC ⇒ published-only, cross-tenant
    │
    │ (2) user clicks "Use my location"     ── client island only
    ▼
  near-you-row.tsx  "use client"
    │   navigator.geolocation.getCurrentPosition()
    │     ├─ denied / unavailable / timeout ─────────► keep serverShops, heading unchanged
    │     └─ granted → {lat, lon} in React state
    ▼
  GET /api/v1/public/shops?lat=&lon=&radiusKm=
    │
    ▼
  ShopRepository.findPublishedNear(...)  nativeQuery
    │   WHERE published AND lat/lon NOT NULL
    │     AND latitude BETWEEN … AND longitude BETWEEN …   ← leakproof, below the RLS barrier
    │   ORDER BY 2*R*asin(sqrt(haversine))                 ← non-leakproof, above the barrier
    ▼
  Page<ShopWithDistance>  →  PublicShopDto + distanceKm
    │
    ▼
  island re-renders: heading becomes "Cooking near you right now", cards show real km


  ── write side, how latitude/longitude ever become non-NULL ──

  POST/PUT /api/v1/shops ──► ShopService.createShop / updateShop
                               │  ShopMapper.toEntity / updateEntity
                               ▼
                             PostcodeGeocoder.locate(shop.getAddress())
                               │  extract trailing UK postcode, normalise (upper, strip spaces)
                               ▼
                             postcode_centroid  (no tenant_id, RLS-exempt, PK on postcode)
                               │  hit  → setLatitude/setLongitude
                               └─ miss → leave NULL, log WARN  (shop simply not distance-ranked)

  DemoDataSeeder (@Profile("dev"))  ──► same PostcodeGeocoder
  Backfill runner (idempotent)      ──► per tenant: TenantContext.set(t) → UPDATE … WHERE latitude IS NULL
                                        (FORCE RLS: a bare UPDATE sees ZERO rows — recorded trap)
```

### Recommended structure

```
core-java/src/main/java/uk/jtoye/core/geo/
├── PostcodeCentroid.java          # @Entity or plain JdbcTemplate row — no tenant_id
├── PostcodeCentroidRepository.java
├── PostcodeGeocoder.java          # extract → normalise → lookup.  ONE implementation
├── PostcodeCentroidImporter.java  # idempotent COPY from the gz resource; config-gated
└── GeoBounds.java                 # radius → lat/lon bounding box, poles guarded

core-java/src/main/resources/
├── db/migration/V61__postcode_centroid.sql     # DDL + btree(postcode) + index on shops(lat,lon)
└── geo/postcode-centroids.csv.gz               # 15.1 MB, committed
    geo/SOURCE.md                               # version, md5, attribution, regen command

frontend/components/marketing/
└── near-you-row.tsx               # "use client" island; props: serverShops
```

### Pattern 1 — Server Component seeds a client island

```tsx
// app/page.tsx — STAYS a Server Component. `async` does not require "use client".
import { loadShopList } from "@/lib/storefront-server"
import { NearYouRow } from "@/components/marketing/near-you-row"

export default async function Home() {
  const seed = await loadShopList({ page: 0, size: 8 })   // real shops, in the initial HTML
  return (
    /* … */
    <NearYouRow serverShops={seed.ok ? seed.data.content : []} />
    /* … */
  )
}
```

```tsx
// components/marketing/near-you-row.tsx
"use client"
import { useState } from "react"
import type { PublicShop } from "@/types/storefront"

type Located = { lat: number; lon: number }

export function NearYouRow({ serverShops }: { serverShops: PublicShop[] }) {
  const [located, setLocated] = useState<Located | null>(null)
  const [shops, setShops] = useState(serverShops)

  // Heading is derived from state, never hardcoded — this is the falsifiable bit.
  const heading = located ? "Cooking near you right now" : "Kitchens on J'Toye"

  function requestLocation() {                    // gesture-triggered, never on mount
    navigator.geolocation.getCurrentPosition(
      async (pos) => {
        const at = { lat: pos.coords.latitude, lon: pos.coords.longitude }
        setLocated(at)
        /* fetch /public/shops?lat=&lon=&radiusKm= and setShops(...) */
      },
      () => { /* denied | unavailable | timeout → keep serverShops, heading unchanged */ },
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 300000 },
    )
  }
  /* … */
}
```
Source: pattern verified against `/vercel/next.js` v16.2.9 docs (Server Component passing
serializable props to a `"use client"` child) and against the two existing islands in this repo.

### Pattern 2 — OSGB36 eastings/northings → WGS84

Transverse-Mercator inverse on Airy 1830, then a 7-parameter Helmert to WGS84. No library required
in any language; ~50 lines. The published OS parameters (OSGB36 → WGS84 is the negation of the
documented WGS84 → OSGB36 set):

```
Airy 1830   a = 6377563.396   b = 6356256.909   F0 = 0.9996012717
            lat0 = 49°N  lon0 = 2°W   N0 = -100000   E0 = 400000
WGS84       a = 6378137.000   b = 6356752.3142
Helmert     tx = +446.448  ty = -125.157  tz = +542.060
            s  = -20.4894e-6
            rx = +0.1502"   ry = +0.2470"   rz = +0.8421"
```

**Measured accuracy** — 25 postcodes sampled across GB, compared against ONSPD's own WGS84 values
served by postcodes.io:

```
N=25   mean = 1.45 m   max = 3.28 m
  AB101BY 1.85 m  ·  BS58EJ 0.22 m  ·  CB29BQ 2.40 m  ·  NR295EE 3.28 m (max)  ·  S981FS 2.00 m
```

Against D-1's accepted ~100 m centroid error this is 30–70× smaller. **The transform is not a source
of meaningful error and should not be discussed as a risk.** The full GB transform runs in 12.0 s.

### Anti-patterns to avoid

- **`ORDER BY acos(…)` without a clamp** — raises `input is out of range` when the user stands on the
  shop's centroid. Use the `asin` haversine.
- **Loading Code-Point Open rows verbatim** — 879 rows carry `positional_quality_indicator = 90` and
  eastings/northings of `0,0`. Ingested blindly they land at **Null Island** (0°N 0°E, in the Gulf of
  Guinea) and become "the nearest shop" to everyone. Filter `pq != 90 AND easting != 0`.
- **Bare `UPDATE shops SET latitude = …` in a migration** — `shops` is `ENABLE + FORCE RLS`; with no
  tenant GUC it matches **zero rows and reports success**. This exact defect is recorded three times
  (V25 → V44 → V57). Loop tenants with `set_config`, or do it in Java with `TenantContext`.
- **`"use client"` on `app/page.tsx`** — regresses the #89 nonce cascade and feeds #507/#542.
- **A geolocation prompt on mount** — unsolicited prompts are penalised by browsers and read as
  hostile.
- **Duplicating the haversine in SQL and Java** — the order and the printed numbers drift, and
  neither side's test sees it.
- **Assuming `isOpenNow(null) === false`** — it returns `true` (`opening-hours.ts:74`).

---

## Don't Hand-Roll

| Problem | Don't build | Use instead | Why |
|---|---|---|---|
| Postcode → coordinates | A geocoder, a heuristic, a per-city lookup | `postcode_centroid` from Code-Point Open | 1,748,230 authoritative rows; anything smaller silently drops vendors |
| Postcode validation | A "correct" UK postcode regex | A permissive trailing-postcode **extraction** regex + a table hit | The table is the authority. `GIR 0AA` is not in Code-Point Open; `SE15 4QA` looks valid and is not a real postcode. A regex that disagrees with the data is worse than none |
| Datum conversion | An ad-hoc "add 0.001 to longitude" fudge | The published Helmert parameters above | Measured 1.45 m mean; a fudge is 100 m+ and direction-dependent |
| Distance ranking | Fetch-all + sort in Java | Native SQL `ORDER BY` + `countQuery` page | Java sorting needs the whole table in memory and breaks paging |
| Device location | A geolocation npm wrapper | `navigator.geolocation` directly | ~40 lines; a wrapper adds supply-chain surface for nothing |
| Deterministic located E2E | Mocking `navigator.geolocation` by hand | `context.grantPermissions` + `context.setGeolocation` | First-class Playwright API; hand-mocks drift from real permission semantics |
| Postcode-table integrity | "It looked right" | The OS-published md5, verified in the regen script | Verified byte-exact here; make the script fail closed on mismatch |

**Key insight:** in this domain every hand-rolled shortcut fails *silently and asymmetrically* — a
bad regex rejects real vendors, an unfiltered load puts shops in the Gulf of Guinea, an RLS-blind
backfill reports success having changed nothing. None of these produce an error; all of them produce
a plausible-looking wrong answer. That is precisely the failure mode this phase was created to stop.

---

## Runtime State Inventory

Phase 33 populates data and changes rendered content — it is not a rename, but the same question
applies: after the branch is merged, what runtime state is still wrong?

| Category | Items found | Action required |
|---|---|---|
| **Stored data** | `shops.latitude` / `shops.longitude` are **NULL for all 5 rows** in the live dev DB (3 published). Correcting them is a **data migration**, not only a code edit — new shops get coordinates from the write path, existing ones do not | Tenant-looped backfill (FORCE RLS), plus corrected seed data. `shops_aud` mirrors exist (`V16:92-93`); the backfill will generate audit revisions — expected, not a defect |
| **Stored data (bad input)** | `Peckham Jollof Co.` is seeded with `SE15 4QA`, which exists in **neither** Code-Point Open nor ONSPD | Correct the seeded postcode; keep one known-bad postcode as a deliberate negative-control fixture |
| **Live service config** | Keycloak realms are **Postgres-backed**, and `start-dev --import-realm` *"skips"* any realm that already exists [CITED: keycloak.org/server/importExport]. An IdP added to `realm-export-customers.template.json` will **not** appear on an existing `jtoye-customers` realm | Either delete/re-import the realm, run `kc.sh import --override true` with the server stopped, or POST the IdP via the Admin API. Dropping the `keycloak_data` volume is a **no-op** (recorded trap) |
| **Live service config** | `infra/keycloak/realm-export-customers.json` is **gitignored** (`.gitignore:164`); the tracked source of truth is `realm-export-customers.template.json`, rendered by `envsubst` with an **explicit allow-list** of vars | Any `${GOOGLE_CLIENT_ID}` / `${GOOGLE_CLIENT_SECRET}` placeholder must be added to the customer `envsubst` allow-list in `docker-compose.full-stack.yml:116`, or it renders as an empty string with no error |
| **Secrets / env vars** | `.env` contains **0** `GOOGLE*` variables. `.env.example:227-242` carries the `CUSTOMER_*` block that a new IdP would extend | New `.env` + `.env.example` keys; `verify-env.sh` should require them **only** when the IdP is enabled, so the default stack still boots |
| **Build artefacts** | `core-java/build-local/` is the live build dir (`core-java/build/` is stale — recorded). `ShopMapperImpl` is generated by MapStruct at compile time | Rebuild all containers before E2E; the runtime-parity gate applies (`docker compose start` does not rebuild) |
| **Doc metrics** | `docs/metrics.json` is at `total_logical_invocations: 2509`. New Java/Jest/Playwright tests move it, and **two** CI gates fail on drift | Regenerate with `scripts/docs-freshness.sh --write` — never by arithmetic (recorded trap). `scripts/check-doc-metrics.sh` also checks the numbers quoted in `CLAUDE.md`, `AGENTS.md`, `README.md` |
| **OS-registered state** | None — verified: this phase registers no scheduled task, pm2 process or systemd unit | None |

---

## #432 — Customer-Realm Identity Providers

### Measured state

```
infra/keycloak/realm-export-customers.json   realm = jtoye-customers   identityProviders = 0
                                             identityProviderMappers   = 0
                                             registrationAllowed = true, loginWithEmailAllowed = true
                                             verifyEmail = false, resetPasswordAllowed = true
                                             clients = ["storefront-client"] (public, PKCE)
                                             redirectUris = http://localhost:3000|3001|3100/*
```

The `identityProviders` key is **absent entirely**, not present-and-empty. Confirms
`CRITERIA-DECAY-2026-08-08.md`.

### What adding a social IdP actually requires

**On the provider side (Google — the only realistic free option):**

- A Google Cloud project and an **OAuth 2.0 Client ID of type "Web application"**. Creating one is
  free; **no commercial account, no payment method** is required.
- **Authorized redirect URI** = `{keycloak-public-url}/realms/jtoye-customers/broker/google/endpoint`.
- Google's redirect-URI rules [CITED: developers.google.com/identity/protocols/oauth2/web-server]:
  *"Redirect URIs must use the HTTPS scheme, not plain HTTP. Localhost URIs (including localhost IP
  address URIs) are exempt from this rule."* Hosts **cannot** be raw IPs (localhost exempt) and
  **wildcards are prohibited**.
  ⇒ **`http://localhost:8085/realms/jtoye-customers/broker/google/endpoint` is a legal redirect URI**,
  so the whole flow is demonstrable end-to-end on the local compose stack.
- Scopes `openid email profile` are the standard set. Google's docs say verification is required for
  apps using *"scopes that permit access to certain user data"* but do **not**, on that page,
  enumerate which are non-sensitive. **UNVERIFIED** whether `email`/`profile` alone require app
  verification for a published external app — flagged **A2**.

**Not viable without a commercial account:** Apple Sign In requires an Apple Developer Program
membership (paid) — that is precisely the sixth-commercial-decision class D-1 rejects. Meta/Facebook
needs a developer account plus app review for public use.

**On the Keycloak side (24.0.5):**

1. An `identityProviders` entry with `alias: "google"`, `providerId: "google"`, `enabled: true`,
   `trustEmail: true` (Google verifies email; without this Keycloak re-verifies and the flow stalls
   on a mail Mailhog swallows), and `config: { clientId, clientSecret, defaultScope, syncMode }`.
2. `${GOOGLE_CLIENT_ID}` / `${GOOGLE_CLIENT_SECRET}` placeholders in
   `realm-export-customers.template.json` **and** added to the `envsubst` allow-list at
   `docker-compose.full-stack.yml:116`, plus keys in `.env` / `.env.example`.
3. **The import will not apply to the existing realm.** `--import-realm` *"skips"* existing realms.
   Plan an explicit realm replacement or an Admin-API POST to
   `/admin/realms/jtoye-customers/identity-provider/instances`.
4. **CSP.** Customer auth does PKCE **in the browser**, so CSP applies (unlike the staff realm, whose
   NextAuth exchange is server-side — `lib/security-headers.ts:60-74` explains this at length). The
   brokered flow adds a redirect to `accounts.google.com`, which needs `form-action` coverage.
   ⚠ **The recorded path trap applies:** a CSP source expression carrying a path matches that path
   **exactly** unless it ends in `/`. The existing code already emits each realm URL twice, bare and
   with a trailing slash, for exactly this reason. Any new source must follow the same rule.
5. ⚠ **The KC24 unmanaged-attribute trap.** KC24 strips unmanaged attributes on admin-API user
   create. A brokered first login provisions a user; if `tenant_id` (or any custom claim the
   storefront relies on) is expected on that user it must be **declared managed in the user profile
   first**, or it is silently dropped. Verify what `CustomerJwtVerifier` requires before enabling
   brokering.

### Recommendation

**Both limbs of SC-5 are achievable, but they are different amounts of work, and the second is
honest.**

- **Limb 1 (populate).** Fully achievable locally with a free Google OAuth client and
  `http://localhost:8085/...`. What is **not** achievable is a *production-usable* IdP: the
  production redirect URI must be HTTPS on a resolving host, and this project's `jtoye.co.uk` DNS
  does not resolve while `DEPLOY_*_ENABLED` is false. So a "populated" IdP would be
  **local-development-only** until the production-domain decision lands.
- **Limb 2 (dated deliberate decision).** Legitimate, explicitly permitted by D-3 and by SC-5's own
  wording.

**My recommendation is limb 2, with limb 1's groundwork committed but disabled** — i.e. land the
template placeholder, the env keys, the `envsubst` allow-list entry, the CSP source and the realm-
replacement procedure, all **off by default** (`enabled: false` / absent unless `GOOGLE_CLIENT_ID`
is set), plus a dated ADR recording *why*: Google's redirect URI cannot be finalised until the
production domain is decided, and Apple/Meta require a commercial account of exactly the class D-1
rejects. That satisfies SC-5 truthfully, leaves a one-variable switch for whoever resolves the
domain, and does not ship a login button that only works on a developer's laptop.

**This is a product decision and should be confirmed with the owner, not assumed by the planner** —
see Open Question **Q-3**.

---

## Common Pitfalls

### Pitfall 1 — Null Island
**What goes wrong:** 879 Code-Point Open rows have `positional_quality_indicator = 90` and
eastings/northings `0,0`. Transformed naively they become ~(49.77°N, 7.56°W)-ish garbage or, if the
raw zeros are stored as lat/lon, exactly (0, 0) — which is nearer to a Peckham customer than nothing,
so those postcodes win every ranking.
**Why it happens:** the sentinel is in a *different column* from the coordinates.
**How to avoid:** `WHERE pq != 90 AND easting != 0` at load. Assert `count(*) = 1748230` post-load
(exact for the 2026-08 release).
**Warning sign:** any shop appearing top of every distance list regardless of the query point.

### Pitfall 2 — the RLS-blind backfill
**What goes wrong:** `UPDATE shops SET latitude = … WHERE latitude IS NULL` matches **zero rows** and
reports success, because `shops` is `ENABLE + FORCE RLS` and no tenant GUC is set.
**Why it happens:** FORCE RLS applies to the table owner too; the migration role is not exempt.
**How to avoid:** loop `tenants` (no RLS) and `set_config('app.current_tenant_id', …, true)` per
tenant, exactly as V44 does; or do it in Java with `TenantContext.set`.
**Warning sign:** `UPDATE 0` where you expected `UPDATE n`. **Read the row counts, never the exit
code** — `docker exec` without `-i` will not even deliver a heredoc to `psql`, and that also exits 0.

### Pitfall 3 — `acos` out of range
**What goes wrong:** `ERROR: input is out of range` from the spherical-law-of-cosines distance when
two points coincide.
**Why it happens:** float rounding pushes the dot product marginally above 1.0.
**How to avoid:** use the `asin` haversine form; if `acos` is unavoidable, clamp with
`LEAST(1.0, GREATEST(-1.0, …))`.
**Warning sign:** a 500 that only reproduces when the test coordinate equals a fixture's coordinate.

### Pitfall 4 — the heading that lies in the other direction
**What goes wrong:** the row is fixed to render real shops, but the heading still says "near you"
when no coordinate was ever obtained. #544 is satisfied on the letter and violated in spirit.
**How to avoid:** derive the heading from state; assert that "near you" is absent from the DOM in the
unlocated state. Note the streaming staging buffer: `<div hidden id="S:n">` holds a second copy of
the shell for ~300 ms, visible to `getByTestId`/`getByTitle` but **not** to `getByRole`. Use
`getByRole('heading', …)`, or this becomes bug #556/#593 for the third time.

### Pitfall 5 — a coordinate criterion that cannot fail
**What goes wrong:** the acceptance check for "shops have coordinates" is written after the backfill
runs, so it has never been observed failing.
**How to avoid:** CONTEXT.md is explicit — the NULL-coordinate state **exists on the tree today**
(verified: `count(latitude) = 0` of 5 rows). **Capture that output before populating.** It is a free
control arm that expires the moment the first task runs.

### Pitfall 6 — an unrebuilt runtime
**What goes wrong:** the frontend serves the old landing page while the branch has the new one; HTTP
200 and a green suite are identical either way.
**How to avoid:** rebuild all containers; `scripts/check-runtime-freshness.sh` and
`scripts/check-branch-behind-base.sh`; compare `.Metadata.LastTagTime` (**not** `.Created`). Run
those from the **main checkout**, not a worktree — the compose project name comes from the directory.

### Pitfall 7 — a search that cannot see the evidence
**What goes wrong:** `grep`/`rg` here both honour `.gitignore`, and `.planning/` is 505 files that a
default search never traverses.
**How to avoid:** `rg -uu` with a **scoped path** when absence is the claim (bare `-uu` sweeps
`.next/`), and pair every zero with a control that returns non-zero on the same corpus. `rg -E` is
`--encoding`, not extended-regex — drop it. Every zero in this document carries its control.

---

## Code Examples

### Bounding box from a radius (Java)

```java
/** Half-extent of a lat/lon box that fully contains a circle of radiusKm around (lat, lon). */
public static double[] boxAround(double lat, double lon, double radiusKm) {
    double latDelta = radiusKm / 111.045;
    double cos = Math.cos(Math.toRadians(lat));
    // At |lat| ~ 90 the box degenerates. GB never gets there, but guard rather than divide by ~0.
    double lonDelta = Math.abs(cos) < 1e-6 ? 180.0 : radiusKm / (111.045 * cos);
    return new double[]{ lat - latDelta, lat + latDelta, lon - lonDelta, lon + lonDelta };
}
```

### Postcode normalisation and extraction

```java
// Code-Point Open stores "SE1 0AA" / "AL1 1AG" / "AB10 1AB" — always OUTCODE + ONE SPACE + INCODE.
// Measured field lengths across the whole of GB: 6 (45,237), 7 (870,174), 8 (833,698). No padding.
// Store the key SPACE-STRIPPED and UPPERCASED so every input shape normalises to one form.
private static final Pattern TRAILING_POSTCODE = Pattern.compile(
        "([A-Z]{1,2}[0-9][A-Z0-9]?)\\s*([0-9][A-Z]{2})\\s*$");

static Optional<String> extract(String address) {
    if (address == null) return Optional.empty();
    Matcher m = TRAILING_POSTCODE.matcher(address.toUpperCase(Locale.UK).trim());
    return m.find() ? Optional.of(m.group(1) + m.group(2)) : Optional.empty();
}
// Deliberately PERMISSIVE. The postcode_centroid table is the authority: a hit is proof the postcode
// is real, a miss is proof it is not. "SE15 4QA" passes this regex and is not a real postcode — and
// that is correct behaviour, because the table is what rejects it.
```

### Distance query (native, RLS-aware)

```java
@Query(value = """
    SELECT s.*,
           2 * 6371.0088 * asin(sqrt(
               power(sin(radians(s.latitude - :lat) / 2), 2)
             + cos(radians(:lat)) * cos(radians(s.latitude))
             * power(sin(radians(s.longitude - :lon) / 2), 2)
           )) AS distance_km
      FROM shops s
     WHERE s.published = true
       AND s.latitude IS NOT NULL AND s.longitude IS NOT NULL
       AND s.latitude  BETWEEN :latMin AND :latMax
       AND s.longitude BETWEEN :lonMin AND :lonMax
     ORDER BY distance_km ASC
    """,
    countQuery = """
    SELECT count(*) FROM shops s
     WHERE s.published = true
       AND s.latitude IS NOT NULL AND s.longitude IS NOT NULL
       AND s.latitude  BETWEEN :latMin AND :latMax
       AND s.longitude BETWEEN :lonMin AND :lonMax
    """,
    nativeQuery = true)
Page<ShopWithDistance> findPublishedNear(/* … */);
// No tenant GUC is set: shops_public_read reduces to `published = true`, cross-tenant, exactly as
// findByPublishedTrue already behaves for GET /public/shops today.
```

### Regenerating the dataset (reproduction of every measurement in this document)

```bash
curl -s https://api.os.uk/downloads/v1/products/CodePointOpen | jq -r .version   # 2026-08
curl -sL -o codepo_gb.zip \
  'https://api.os.uk/downloads/v1/products/CodePointOpen/downloads?area=GB&format=CSV&redirect'
md5sum codepo_gb.zip     # must equal the md5 from .../downloads — FAIL CLOSED on mismatch
unzip -q codepo_gb.zip
cat Data/CSV/*.csv \
  | awk -F, '{gsub(/"/,"",$1); gsub(/ /,"",$1); gsub(/"/,"",$2)} $2!=90 && $3!=0 {print $3" "$4" "$1}' \
  | awk -f scripts/osgb36-to-wgs84.awk \
  | sort > postcode-centroids.csv          # 1,748,230 rows, 45.6 MB, ~12 s
gzip -9 postcode-centroids.csv             # 15.1 MB
```

---

## State of the Art

| Old approach | Current approach | When changed | Impact |
|---|---|---|---|
| Bespoke "OS OpenData Licence" | **Open Government Licence v3** | February 2015 | Same terms as the rest of UK gov open data; no share-alike |
| Fixed-width 7-char Code-Point postcode field | Variable 6/7/8-char `OUTCODE SPACE INCODE` | (current 2026-08 release) | Old padding-based parsers mis-key. Verified across all 1.75M rows |
| `cookies()` / `headers()` synchronous | **`async`, must be awaited** | Next.js 15 | Already the repo's practice (`app/shop/page.tsx:69`, `app/shop/orders/page.tsx:41`) |
| `earthdistance` as the no-PostGIS answer | Plain-SQL haversine | n/a here | Not a version change — a **privilege** constraint: `jtoye_app` cannot `CREATE EXTENSION` at all |

**Deprecated / no longer true:**
- *"Code-Point Open has 1.4M postcodes"* — widely repeated; the 2026-08 release has **1,749,109**.
- *"OS OpenData requires a bespoke licence acceptance"* — superseded by OGL v3.
- *"`www.ordnancesurvey.co.uk/opendata/licence`"* — the URL printed inside the shipped
  `licence.txt` **404s**. The terms have moved; the licence has not changed.

---

## Assumptions Log

| # | Claim | Section | Risk if wrong |
|---|---|---|---|
| **A1** | Code-Point Open is licensed under **OGL v3** and permits commercial use in a proprietary SaaS with attribution. Established from the OSM wiki and OS product pages; the canonical URL printed in the shipped `licence.txt` **404s**. The three required attribution lines are `[VERIFIED]` — they were read out of the archive. | § Licence | Low-probability, high-impact. If wrong the whole D-1 substrate is unusable. **A human should read the live OS licence page before merge.** |
| **A2** | Google OAuth scopes `openid email profile` are non-sensitive and a published external app using only them does not require Google app verification. | § #432 | Medium. If wrong, a production Google IdP needs a verification process — which strengthens the "record a dated decision to skip" recommendation rather than weakening it |
| **A3** | ONSPD's NI restriction is not avoidable by filtering BT rows post-download. **Stated as unresolved, not as fact.** | § Which dataset | None — the recommendation avoids ONSPD entirely, so this never needs deciding |
| **A4** | `positional_quality_indicator` values 10/20/30/50/60 all denote usable coordinates of varying precision (10 = within-building at 1 m; 90 = none). The 90⇒`0,0` mapping is `[VERIFIED]` by measurement; the meanings of 20/30/50/60 are from general knowledge of the OS spec, not read from the technical spec in this session | § Pitfall 1 | Low. Only affects whether coarser rows should also be excluded; the ~100 m tolerance makes all of them acceptable |
| **A5** | OS Open Names does not provide postcode-**unit** centroids. The product page advertises "postcodes" without stating granularity or CRS. | § Which dataset | Low. Even if it did, it would be strictly worse than Code-Point Open (fewer verified properties) |
| **A6** | No `Permissions-Policy` header currently suppresses `geolocation`. **Not measured** — `lib/security-headers.ts` was read for CSP only | § Frontend Geolocation | Low, but check it in Wave 0: a `Permissions-Policy` omission blocks the API with a near-silent failure |

---

## Open Questions

1. **Q-1 — Is ~15 MB of committed binary per dataset refresh acceptable?**
   - What we know: 1,748,230 rows → 45.6 MB raw / 15.1 MB gzipped / 149 MB in Postgres. Current
     `.git` is 52 MB; the largest tracked file today is a 924 KB PNG. Sector-granularity would be
     sub-MB but degrades accuracy to ~500 m–1 km, contradicting D-1's stated ~100 m tolerance.
   - What's unclear: the owner's tolerance for repo growth versus a network-dependent build.
   - Recommendation: commit it, refresh annually not quarterly. **Confirm before planning.**

2. **Q-2 — What is the default radius, and is it a filter or only a sort?**
   - What we know: SC-1 says a delivery radius must be *"enforced somewhere a customer can observe"*.
     `Shop` has no radius column; `deliveryFeePennies` / `freeDeliveryThresholdPennies` exist but no
     distance.
   - What's unclear: whether the radius is a platform constant, a per-shop column (new migration +
     `_aud` mirror + vendor UI), or purely a query parameter.
   - Recommendation: a **query parameter with a platform default** for this phase — observable,
     falsifiable, no schema change. A per-shop delivery radius is its own slice.

3. **Q-3 — #432: populate the IdP, or record the dated decision?**
   - What we know: a free Google client works against `http://localhost:8085/...`; a *production*
     redirect URI needs HTTPS on a resolving host, and `jtoye.co.uk` does not resolve.
   - What's unclear: whether the owner wants a local-only social login shipped.
   - Recommendation: record the dated decision, commit the disabled groundwork. **Owner's call.**

4. **Q-4 — Should the landing row show shops or dishes?**
   - What we know: SC-2's wording is *"resolves to real published shops"*. The current row is
     dish-shaped (name, vendor, rating, price). Real dish data exists but needs a per-shop products
     call — N+1, or a new endpoint.
   - Recommendation: **shops**, per the criterion's own wording. Note that ratings and FHRS are not
     on `PublicShopDto` — the card must not invent them (the current one shows a hardcoded
     "⭐ 4.8 · FHRS 5"). Removing them is correct; leaving them is a new fiction.

5. **Q-5 — What happens to a shop whose postcode does not geocode?**
   - Recommendation: coordinates stay NULL, the shop keeps its storefront and appears in the
     unlocated list, and is simply absent from distance-ranked results. Log a WARN with the shop
     slug. **Do not** default to (0,0) and **do not** hide the shop. Surface it to the vendor in
     onboarding eventually — out of scope here.

---

## Environment Availability

| Dependency | Required by | Available | Version | Fallback |
|---|---|---|---|---|
| PostgreSQL | `postcode_centroid`, distance query | ✅ | `postgres:15-alpine`, running 19h | — |
| PostGIS | (not used) | ❌ | — | Plain-SQL haversine — **recommended path** |
| `earthdistance` / `cube` | (not used) | ⚠ available but **not installable** as `jtoye_app` | 1.1 / 1.5 | Plain-SQL haversine |
| `api.os.uk` downloads API | one-off dataset fetch | ✅ | unauthenticated, HTTP 200, md5 verified | Commit the derived artefact — removes the dependency after first fetch |
| `jq` | regen script, gate scripts | ✅ | present | — |
| `awk` | OSGB36→WGS84 transform | ✅ | present, 12 s for all GB | Any language; ~50 lines |
| `cs2cs` / `gdaltransform` / `proj` | (alternative transform) | ❌ | — | The awk/Java Helmert above — **measured to 1.45 m mean** |
| `pyproj` (Python) | (alternative transform) | ⚠ no project conda env declared (`.conda-env` absent; base python is hook-blocked) | — | Same |
| Keycloak | #432 | ✅ | 24.0.5, running | — |
| Frontend / core-java / Playwright | E2E | ✅ | Next 16.2.12, React 19.2.8, Playwright 1.62.1 | — |
| Public HTTPS host | production social IdP redirect URI | ❌ | `jtoye.co.uk` DNS does not resolve; `DEPLOY_*_ENABLED` false | `http://localhost:8085/...` for local demo only — see Q-3 |

**Missing with no fallback:** a public HTTPS host for a production-usable social IdP. This is the
only hard blocker, and it is confined to #432.

**Missing with fallback:** PostGIS, `earthdistance`, and every proj CLI — all replaced by the
measured plain-SQL/awk path at no accuracy cost.

---

## Validation Architecture

### Test framework

| Property | Value |
|---|---|
| Java unit | JUnit 5 via `./gradlew :core-java:test` |
| Java integration | `./gradlew :core-java:integrationTest` (Testcontainers, real Postgres + RLS) — registered at `core-java/build.gradle.kts:148` |
| Frontend unit | Jest — `npm test` (`frontend/package.json:10`) |
| Frontend typecheck | **`npm run build`** — jest does **not** type-check (recorded) |
| E2E | Playwright — `frontend/e2e/`, 22 spec/verify files |
| Metrics oracle | `docs/metrics.json` (2509 logical invocations), gated by `scripts/docs-freshness.sh` **and** `scripts/check-doc-metrics.sh` |

### Phase requirements → test map

| Req | Behaviour | Type | Command | Exists? |
|---|---|---|---|---|
| CUST-01 | Code-Point Open loads with exactly 1,748,230 rows and no `(0,0)` | integration | `:core-java:integrationTest --tests '*PostcodeCentroidImport*'` | ❌ Wave 0 |
| CUST-01 | `SE155BS` → 51.4724, −0.0700 within 10 m of the ONSPD reference | unit | `:core-java:test --tests '*PostcodeGeocoderTest*'` | ❌ Wave 0 |
| CUST-01 | An unknown postcode (`SE154QA`) yields **empty**, not `(0,0)` | unit | same | ❌ Wave 0 |
| CUST-01 | All three demo shops have non-NULL coordinates after seeding | integration | `--tests '*DemoDataSeeder*'` | ❌ Wave 0 |
| CUST-01 | Backfill updates > 0 rows **under FORCE RLS** with a tenant GUC, and provably 0 without one | integration | `--tests '*ShopCoordinateBackfill*'` | ❌ Wave 0 |
| CUST-01 | `findPublishedNear` orders by real distance and is cross-tenant with no GUC | integration | `--tests '*PublicStorefrontDistance*'` | ❌ Wave 0 |
| CUST-01 | Coincident points do not raise `input is out of range` | integration | same | ❌ Wave 0 |
| CUST-01 | `RlsContractTest` still green with `postcode_centroid` exempted | integration | `--tests '*RlsContractTest*'` | ✅ exists — must be **extended**, not weakened |
| CUST-01/02 | Denied geolocation keeps the server-rendered list and the location-free heading | e2e | `npx playwright test near-you-row` | ❌ Wave 0 |
| CUST-01 | Granted geolocation (`setGeolocation`) reorders by distance | e2e | same | ❌ Wave 0 |
| CUST-01 | Initial HTML contains a **real shop name** (not a skeleton) | e2e | extend `storefront-ssr-seo.spec.ts` | ⚠ file exists; assertion new |
| CUST-01 | `page.tsx` has **no** `"use client"` directive | unit/lint | jest or a gate script | ❌ Wave 0 |
| CUST-03 | Realm export carries the IdP **or** the ADR exists and is dated | gate | grep the realm template / the ADR | ❌ Wave 0 |

### Sampling rate

- **Per task commit:** `./gradlew :core-java:test` + `npm test` (fast, no containers)
- **Per wave merge:** `./gradlew :core-java:integrationTest` + `npm run build` + the touched
  Playwright specs
- **Phase gate:** full suite green, `docs/metrics.json` regenerated with
  `scripts/docs-freshness.sh --write`, `scripts/check-runtime-freshness.sh` and
  `scripts/check-branch-behind-base.sh` green (both fail closed at **exit 2 = VOID**), before
  `/gsd:verify-work`

### Wave 0 gaps

- [ ] `PostcodeGeocoderTest` — extraction + normalisation + known/unknown lookup
- [ ] `PostcodeCentroidImportIntegrationTest` — row count, no `(0,0)`, idempotent re-run
- [ ] `ShopCoordinateBackfillIntegrationTest` — **must assert 0 rows without a GUC and >0 with one**
- [ ] `PublicStorefrontDistanceIntegrationTest` — ordering, radius, coincident-point, cross-tenant
- [ ] `frontend/e2e/near-you-row.spec.ts` — granted + denied, `getByRole('heading')` only
- [ ] Test-profile postcode fixture (small, committed) so integration tests are offline-deterministic
- [ ] **Capture the NULL-coordinate control output before any task runs** — it expires on first write

### Falsifiability control arms — capture these first

| Criterion | Break input | Expected failure |
|---|---|---|
| "Shops have coordinates" | today's tree | `count(latitude) = 0` of 5 — **capture now, it expires** |
| "Row shows real shops" | reintroduce the `featuredDishes` literal | assertion finds `Mama's Kitchen`, not `Mama Ade's Kitchen` |
| "No 'near you' without a location" | hardcode the heading | denied-permission spec finds the string |
| "Distance ordering is real" | swap two shops' coordinates | the order changes; if it does not, the query is not driving the sort |
| "Loader rejects Null Island" | inject a `pq=90` row | count assertion fails |
| "Backfill is RLS-aware" | drop the `set_config` | `UPDATE 0` — assert the count, **never** the exit code |
| "`page.tsx` stays a Server Component" | add `"use client"` | the directive check fails |

---

## Security Domain

### Applicable ASVS categories

| Category | Applies | Standard control |
|---|---|---|
| V2 Authentication | yes (#432) | Keycloak 24 OIDC brokering; `trustEmail` only for a provider that verifies email |
| V3 Session Management | no change | Existing `storefront-client` PKCE + HttpOnly cookies |
| V4 Access Control | **yes** | `shops_public_read` already scopes the anonymous read to `published = true`. The distance query must **not** widen it. `postcode_centroid` is public reference data with no tenant dimension |
| V5 Input Validation | **yes** | `lat`, `lon`, `radiusKm` are new unauthenticated query params: bound `lat ∈ [-90, 90]`, `lon ∈ [-180, 180]`, `radiusKm ∈ (0, MAX]`. **`CreateShopRequest.latitude/longitude` have no validation today** (`:24-25`) — add `@DecimalMin`/`@DecimalMax` |
| V6 Cryptography | no | No new crypto. Google client secret via the existing `envsubst` + `.env` path, never committed |
| V7 Error Handling | yes | RFC 7807 via `GlobalExceptionHandler`; an out-of-range coordinate must be a typed 400, not a 500 from `acos` |
| V9 Data Protection | **yes** | Precise geolocation is personal data under UK GDPR. **Do not persist it** — no cookie, no DB row, and keep it out of access logs. React state only |
| V13 API | yes | New params are additive to a documented endpoint; the OpenAPI snapshot must be regenerated |

### Known threat patterns

| Pattern | STRIDE | Mitigation |
|---|---|---|
| SQL injection via `radiusKm`/`lat`/`lon` in a native query | Tampering | Named JPA parameters only — never string concatenation, and the bounding box is computed in Java |
| `Sort` parameter injection reaching the native ORDER BY | Tampering | The distance endpoint fixes its own ordering; do not thread client `Sort` into a native query |
| Enumeration of unpublished shops via distance | Info disclosure | `published = true` stays in **both** the main query and the `countQuery`; a count that ignores it leaks row existence |
| Location harvesting via logs | Info disclosure | Exclude `lat`/`lon` from access-log query strings and from any analytics payload |
| Unauthenticated scraping of the shop directory | DoS / Info | Existing Bucket4j limits apply; the island issues **one** fetch per grant, not per keystroke |
| `acos` domain error as an unauthenticated 500 | DoS | `asin` haversine, or clamp — reachable by any anonymous caller |
| IdP account takeover via unverified email | Spoofing | `trustEmail: true` **only** for a provider that verifies; otherwise a brokered login could claim an existing account's address |
| CSP blocks the brokered redirect silently | — (availability) | `form-action` must carry the realm **subtree** form ending `/` — the recorded path trap |

---

## Project Constraints (from CLAUDE.md)

Directives the planner must verify compliance against:

- **Stack is fixed** — Spring Boot 3.5.16 / Next.js 16 / Go 1.26 / PostgreSQL 15, JDK 21. Nothing
  here changes any of them.
- **Multi-tenancy** — all new features respect RLS and `TenantContext`. `postcode_centroid` is
  RLS-exempt reference data and **must** be added to `RlsContractTest.EXEMPT_TABLES` with a written
  justification (never by weakening the assertion).
- **All new code requires tests** — 2509 logical invocations is the standing count; `docs/metrics.json`
  is the single source of truth and two CI gates fail on drift. Regenerate with
  `scripts/docs-freshness.sh --write`.
- **Docker** — rebuild ALL containers after code changes before E2E.
- **Runtime topology** — compose is the canonical local dev/E2E runtime; k8s is the deploy target.
- **Incremental Betterment Doctrine** — replacing the dish row displaces goods (the horizontal
  scroller affordance, the emoji/gradient visual weight, the `/shop?q=` deep links, the fine-pointer
  hover gating, the GSAP `data-hero-*` hooks). **Enumerate and account for each.** Regression by
  omission is a defect even with a green suite: a 3-card row that reads as broken, or losing the
  scroll affordance, fails this rule regardless of tests.
- **Web performance (mobile-first)** — the landing page is the LCP-critical route. Real shop logos
  replace emoji: use `SafeImage` with explicit width/height (the CWV/D-07 precedent from Phase 24),
  measure on a **throttled mobile profile**, and note that a client-island refetch after a location
  grant is a layout-shift risk (CLS) if card heights are not reserved.
- **SEO** — `/` is a public surface. The server-rendered default must contain real shop names in the
  initial HTML (this is #507's measured complaint) and the structured data in `lib/structured-data.ts`
  should stay consistent with what renders.
- **AI agent-readiness** — the new query params are additive to an existing documented endpoint;
  regenerate the OpenAPI snapshot and keep errors RFC 7807. A read-only endpoint needs no
  Idempotency-Key. Consider whether an MCP tool is warranted, or record why not.
- **Falsifiable evidence + runtime parity** — every criterion shown to FAIL first, both directions
  recorded; the delivered runtime must match the branch.
- **Accessibility** — the "Use my location" control is a real `<button>` with an accessible name;
  the heading change must be announced (`aria-live`) or the update is invisible to a screen reader.

---

## Sources

### Primary (HIGH confidence — measured or read directly)

- **The live stack**, 2026-08-08: `pg_available_extensions`, `pg_available_extension_versions`,
  `pg_policy` on `shops`, `pg_proc.proleakproof`, `pg_roles`, `has_database_privilege`, a
  rolled-back `SET ROLE jtoye_app; CREATE EXTENSION` probe, `shops` row/coordinate counts, a
  1.7M-row table-sizing probe, and `GET http://localhost:9090/api/v1/public/shops`
- **Code-Point Open 2026-08**, downloaded and md5-verified (`42ecd9a7db141608dc6ab63f2dfb0bc3`):
  `Doc/licence.txt`, `Doc/Code-Point_Open_Column_Headers.csv`, all 129 data CSVs (row counts,
  quality-indicator distribution, postcode field lengths, presence/absence probes with controls)
- `https://api.os.uk/downloads/v1/products/CodePointOpen` and `/downloads` — version, formats,
  `"areas":["GB"]`, file sizes, md5. **Unauthenticated, HTTP 200**
- **The repo**: `V16__public_storefront.sql`, `V44__fts_leakproof_and_vector_backfill.sql`,
  `Shop.java`, `ShopRepository.java`, `ShopService.java`, `ShopMapper.java`,
  `build-local/.../ShopMapperImpl.java`, `CreateShopRequest.java`, `PublicStorefrontService.java`,
  `PublicStorefrontController.java`, `RlsContractTest.java`, `DemoDataSeeder.java`,
  `frontend/app/page.tsx`, `frontend/app/layout.tsx`, `frontend/app/shop/page.tsx`,
  `frontend/app/shop/shop-discovery-client.tsx`, `frontend/lib/storefront-server.ts`,
  `frontend/lib/security-headers.ts`, `frontend/lib/opening-hours.ts`, `frontend/middleware.ts`,
  `frontend/types/storefront.ts`, `infra/keycloak/*`, `docker-compose.full-stack.yml`,
  `docs/metrics.json`, `.env.example`, `.gitignore`
- `/vercel/next.js` v16.2.9 (Context7) — Server Component → client-island prop passing; async
  `cookies()`/`headers()`
- `https://www.ons.gov.uk/methodology/geography/licences` — NI/LPS terms and the required ONS
  postcode attribution statements, quoted verbatim
- `https://www.keycloak.org/server/importExport` — *"If a realm already exists in the server, the
  import operation is skipped"*
- `https://developers.google.com/identity/protocols/oauth2/web-server` — redirect-URI rules,
  localhost HTTP exemption, no wildcards, no raw IPs
- `https://developer.mozilla.org/en-US/docs/Web/API/Geolocation_API` — secure-context requirement,
  permission semantics, `Permissions-Policy: geolocation`

### Secondary (MEDIUM — cross-checked)

- `https://api.postcodes.io/postcodes` — used as an **independent ONSPD-derived reference** for the
  25-postcode transform-accuracy sample and to confirm `SE154QA` is a 404. Not proposed as a runtime
  dependency
- `https://www.ordnancesurvey.co.uk/products/code-point-open` — quarterly cadence, GB coverage
- `https://www.ons.gov.uk/methodology/geography/geographicalproducts/postcodeproducts` — ONSPD
  quarterly cadence, ONSPD vs NSPL, OGL v3
- `https://docs.os.uk/os-downloads/products/areas-and-zones-portfolio/code-point-open` — CSV +
  GeoPackage formats

### Tertiary (LOW — flagged in the Assumptions Log)

- `wiki.openstreetmap.org/wiki/Ordnance_Survey_OpenData` — the Feb-2015 move to OGL v3 and the
  Code-Point-specific attribution lines. **The canonical OS licence URL printed inside the shipped
  `licence.txt` returns 404**, so this is the best available source for the licence *identity*.
  See **A1**
- Community write-ups on Keycloak social login (`identityProviders` JSON shape,
  `/broker/{alias}/endpoint` redirect URI). Structurally consistent across four independent sources
  but not read from Keycloak's own reference

---

## Metadata

**Confidence breakdown:**

- **Dataset choice (D-1.1):** HIGH — decided on a licence term quoted verbatim from ONS, against a
  dataset whose GB-only scope was read from the OS product API
- **Licence (D-1.2):** HIGH on the *required attribution* (read out of the archive); MEDIUM on the
  *licence identity being OGL v3* (canonical URL 404s — **A1**)
- **Shipping (D-1.3):** HIGH — every size and timing figure was measured on this machine and this
  database, not estimated
- **Distance mechanics (D-1.4):** HIGH — PostGIS absence, extension privileges, leakproof status and
  the live RLS policy were all measured, several with rolled-back probes
- **Frontend pattern (Q5):** HIGH — the pattern already exists in this repo in two places with
  docblocks explaining why
- **Unlocated default (Q6):** MEDIUM — the mechanism is certain; the exact copy is a product choice
  (Q-4)
- **#432 (Q7):** MEDIUM — Keycloak and Google mechanics verified; Google's verification policy for
  basic scopes is **A2**, and the populate-vs-record choice is the owner's (Q-3)

**Research date:** 2026-08-08
**Valid until:** 2026-09-08 for the repo measurements (the tree moves); **2026-11** for Code-Point
Open (next quarterly release supersedes version `2026-08` and its md5). The NULL-coordinate control
arm expires the moment the first population task runs — **capture it first.**
