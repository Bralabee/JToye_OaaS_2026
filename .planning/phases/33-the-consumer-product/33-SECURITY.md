---
phase: 33
slug: the-consumer-product
status: verified
threats_open: 0
threats_total: 53
threats_closed: 53
asvs_level: 1
block_on: critical
created: 2026-08-09
updated: 2026-08-09
remediation_round: 1
---

# Phase 33 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.
> The security surface of this phase is: one new public reference table (`postcode_centroid`, V61),
> one new **anonymous** query surface (`GET /public/shops?lat&lon&radiusKm`, a native SQL distance
> query), a bulk write across the FORCE-RLS tenant wall (`ShopCoordinateBackfill`), a browser
> geolocation prompt that puts **customer personal data** into application memory, a `Permissions-Policy`
> widening, a disabled Google identity provider on the customer realm, one ingested external artefact
> (OS Code-Point Open), and four new gate scripts. Register authored at plan time across all eight
> plans (`T-33-00-01` … `T-33-07-SC`, 53 rows); verified here in verify-mitigations mode against
> the current tree at `HEAD` on `phase/33-the-consumer-product`.

**Verification stance (FORCE).** Every threat was treated as OPEN until a match in the *cited
location* proved otherwise. No mitigation was accepted on a SUMMARY's word, and no `accept`
disposition was waved through as "not our problem". Where the evidence is a negative (a grep that
found nothing), a **positive control** was run first to prove the pattern is capable of matching —
per the project's recorded `grep Pattern Shape False Negative` trap, an empty grep is evidence about
the pattern, not about the code. Controls are cited inline as `CONTROL:`. Where the evidence is a
gate, the gate was **executed** rather than read. The working tree was never modified.

**Post-review fixes accounted for as evidence.** Four commits landed after `33-REVIEW.md` and are
part of the verified tree: `8e961779` (WR-02, coordinate pairing), `4f1f9d6f` (WR-03, UK bounding
box on the client-coordinate fallback), `16a59924` (WR-01, disclosure arithmetic) and `f3e1d29f`
(WR-04, null-fee rendering). WR-02 and WR-03 **strengthen** `T-33-05-01`; WR-01 strengthens
`T-33-07-06`. WR-03 also introduced the phase's single register deviation, recorded at
[§ Unregistered flags](#unregistered-flags) as `UF-33-01`.

---

## Trust Boundaries

| Boundary | Description | Data / privilege crossing |
|----------|-------------|---------------------------|
| `api.os.uk` → repo | An external archive crosses into the build and then into customer-visible ranking | 1,748,230 postcode centroids, md5-gated |
| committed artefact → `postcode_centroid` | Reference rows cross into a table driving public ranking | Coordinates; a surviving `(0,0)` row would rank nearest to every customer |
| anonymous browser → `GET /public/shops` | Unauthenticated, untrusted numeric input crosses into a **native SQL** query | `lat` / `lon` / `radiusKm`, and the bounding box derived from them |
| query → `shops` under RLS | An anonymous read crosses the tenant wall via `shops_public_read` | Every published shop, cross-tenant by design |
| device → browser JS → network | A precise customer coordinate enters memory and then crosses to an unauthenticated endpoint | UK GDPR personal data |
| browser → storage | Anything persisted here becomes a UK GDPR / PECR question (#116's cookie banner has not shipped) | Would-be location cookie / `localStorage` entry |
| vendor client → `POST/PUT /api/v1/shops` | Vendor-supplied coordinates cross into storage and then into public ranking | `latitude` / `longitude` — a self-placement vector |
| backfill → `shops` | A bulk write crosses the FORCE RLS tenant wall | Every tenant's shop rows |
| free-text vendor address → geocoder regex | Untrusted vendor text crosses into a pattern matcher | ReDoS surface |
| migration → database role | A DDL statement crosses into `jtoye_app`, which cannot execute all DDL | `CREATE EXTENSION` would need `CREATE ON DATABASE` — a privilege escalation on the RLS role |
| `.env` → rendered realm JSON | Secrets cross an `envsubst` boundary with an explicit allow-list | Google client id / secret |
| external IdP → Keycloak realm | A brokered assertion crosses into account provisioning | `trustEmail` decides whether an unverified address can claim an account |
| core API → Next.js server → browser | Untrusted shop content crosses into server-rendered HTML and into a `ld+json` script | Shop name, tags, description, `logoUrl` |
| live database → evidence file → git history | Pasted command output can carry credentials permanently | `psql` / `curl` transcripts in `33-CONTROL-ARMS.md` |

---

## Threat Register — verification results

**53 threats · 53 CLOSED · 0 OPEN.** 40 `mitigate`, 13 `accept`, 0 `transfer`.

### 33-00 — Phase groundwork (4)

| ID | Category | Disposition | Result | Evidence |
|----|----------|-------------|--------|----------|
| T-33-00-01 | Repudiation | mitigate | CLOSED | `33-CONTROL-ARMS.md` — 20 arm headings, 44 code-fence delimiters; every arm records the command that produced it, so a reader re-runs rather than trusts |
| T-33-00-02 | Info disclosure | mitigate | CLOSED | `.gitleaks.toml` — new allowlist entry `'''\.planning/phases/.*-CONTROL-ARMS\.md$'''` with the bound stated in the comment ("forbids pasting any command line carrying a literal password value"). Sweep of `.planning/phases/33-the-consumer-product/` for `PASSWORD=…` / `PGPASSWORD=…` value shapes → **0 hits**. CONTROL: the same pattern matches `.env.example`, so the zero is real |
| T-33-00-03 | Tampering | mitigate | CLOSED | `33-00-SUMMARY.md:69` (Task 2 = human licence gate, ordered first), `:130` (the gate was found resting on secondary sources and was re-based on OS's own OpenData page + National Archives OGL v3 before being put to the owner; two contrary signals recorded, not hidden), `:145` ("ordering held; gate answered before the decision gate was presented"). Commit `11ce3156` |
| T-33-00-SC | Tampering | accept | CLOSED | No dependency manifest in the branch diff — see [§ Supply chain](#supply-chain-t-33-xx-sc) |

### 33-01 — Dataset ingest + licence (5)

| ID | Category | Disposition | Result | Evidence |
|----|----------|-------------|--------|----------|
| T-33-01-01 | Tampering | mitigate | CLOSED | `scripts/regen-postcode-centroids.sh:69` resolves the OS-published md5, `:92-96` compares and `die`s on mismatch ("No artefact written"), `:72` VOIDs when the API publishes no md5. `CODEPO_ZIP_OVERRIDE` explicitly does **not** skip the check (`:22-23`). The derived artefact is committed (`core-java/src/main/resources/geo/postcode-centroids.csv.gz`), so the runtime never re-fetches |
| T-33-01-02 | Tampering | mitigate | CLOSED | `scripts/osgb36-to-wgs84.awk:50` — `if (pq == 90 \|\| E == 0) next` ("both columns, never one"). Verified **against the committed artefact itself**, not the script: 1,748,230 rows, **0** with `lat==0 && lon==0` |
| T-33-01-03 | Repudiation | mitigate | CLOSED | `scripts/check-geo-attribution.sh` ties all three rights holders (`:41`) AND the rendered year to `SOURCE.md`'s attribution year (`:58`, `:85-87`). CI-wired at `.github/workflows/docs-freshness.yml:81`. **Executed**: PASS — dataset year 2026 = footer year 2026, all three lines present. Runtime half: `frontend/components/public/public-footer.tsx:193-197` |
| T-33-01-04 | Denial of service | mitigate | CLOSED | `check-geo-attribution.sh:44` — `void() { … exit 2; }`, VOID on missing/unreadable/unparseable input (`:50`, `:59`). No `cmd \| grep -q` outside comments in any of the four new gates (the four matches are the comment that *names* the forbidden shape) |
| T-33-01-SC | Tampering | accept | CLOSED | See [§ Supply chain](#supply-chain-t-33-xx-sc) |

### 33-02 — `postcode_centroid` table + loader (6)

| ID | Category | Disposition | Result | Evidence |
|----|----------|-------------|--------|----------|
| T-33-02-01 | Tampering | mitigate | CLOSED | `PostcodeCentroidImporter.java:148-157` — Null-Island guard runs **before** promotion and throws `IllegalStateException`. `PostcodeCentroidImportIntegrationTest:120` asserts zero `(0,0)` rows loaded; `:152-165` injects the `nullisland` fixture (which carries `ZZ999ZZ,0.0,0.0`) and asserts the load **aborts and stores nothing** |
| T-33-02-02 | Elevation of privilege | mitigate | CLOSED | No `CREATE EXTENSION` statement in `V61__postcode_centroid.sql` (the three matches are comments explaining the rejection). `scripts/check-no-create-extension.sh` **executed**: PASS over 61 migrations, 1 exempted occurrence. The exemption is scoped by file+name to `V1__base_schema.sql\|uuid-ossp` and the gate **VOIDs** (`:146-147`) if the exemption count drifts from the table. CI-wired at `.github/workflows/ci-cd.yaml:688-689`. `CREATE ON DATABASE` appears nowhere as a grant — only in the gate's own rejection message |
| T-33-02-03 | Info disclosure | accept | CLOSED | Logged at [§ Accepted risks AR-1](#ar-1--postcode_centroid-is-exempt-from-the-rls-schema-walk) |
| T-33-02-04 | Denial of service | mitigate | CLOSED | `PostcodeGeocoder.java:63-64` — `([A-Za-z]{1,2}[0-9]{1,2}[A-Za-z]?)\s{0,4}([0-9][A-Za-z]{2})\s{0,8}$`: anchored to string end, **every** quantifier bounded, no nested repetition. Lookup is `repository.findById(key)` (`:99`) — a primary-key hit — with a length cap at `:95` |
| T-33-02-05 | Denial of service | mitigate | CLOSED | `PostcodeCentroidImportIntegrationTest:199-219` **resolves** `jtoye.geo.postcode-import.enabled` under the dev profile rather than asserting a comment ("A comment claiming a default is not a default"). Row-count mismatch aborts rather than serving a partial table: `:168-179` |
| T-33-02-SC | Tampering | accept | CLOSED | See [§ Supply chain](#supply-chain-t-33-xx-sc) |

### 33-03 — Landing page / server-rendered surface (7)

| ID | Category | Disposition | Result | Evidence |
|----|----------|-------------|--------|----------|
| T-33-03-01 | Elevation of privilege | mitigate | CLOSED | `frontend/next.config.mjs:50` — `camera=(), microphone=(), geolocation=(self), browsing-topics=()`. Exactly the declared widening: `geolocation` same-origin only, the other three still fully denied. Asserted live at `e2e/storefront-ssr-seo.spec.ts:278` and `e2e/near-you-row.spec.ts:111`, and pinned in `__tests__/__snapshots__/header-snapshot.test.ts.snap:10` |
| T-33-03-02 | Tampering | mitigate | CLOSED | `frontend/app/page.tsx:163` uses `serialiseJsonLd(jsonLd)` — the shared serialiser, not a hand-rolled one — and `lib/structured-data.ts:48` is `JSON.stringify(data).replace(/</g, "\\u003c")`, which escapes the `<` that could close the `ld+json` script tag. No shop field is interpolated directly |
| T-33-03-03 | Tampering | mitigate | CLOSED | `frontend/components/marketing/shop-card.tsx:2,95-96` — `logoUrl` is rendered only through `SafeImage`, which constrains sources to the configured `remotePatterns`. `ShopCard` is the sole renderer on the new row (`near-you-row.tsx:7`) |
| T-33-03-04 | Info disclosure | mitigate | CLOSED | Every module importing `lib/storefront-server` (`app/page.tsx`, `app/shop/page.tsx`, `app/shop/[slug]/page.tsx`, `app/sitemap.ts`, `types/storefront.ts`) was checked line-by-line: **none** carries a `"use client"` directive — all matches are prose inside docblocks. `app/__tests__/landing.test.tsx:57-62` mocks `@/lib/storefront-server`, which is the guard |
| T-33-03-05 | Info disclosure | mitigate | CLOSED | `app/page.tsx:130` calls the pre-existing `loadShopList({ page: 0, size: 8 })` — this plan adds no query. `/public/shops` remains served under `shops_public_read`, which with no tenant GUC reduces to `published = true` |
| T-33-03-06 | Denial of service | mitigate | CLOSED | `frontend/e2e/perf-budgets.ts:27` `LCP_BUDGET_MS`, `:35` `CLS_BUDGET`, `:74` `LANDING_CLS_KNOWN_BASELINE = 0.1793` with the control/treatment measurement recorded at `:55-56` and an explicit refusal to raise the budget to go green (`:64-66`). Bundle baseline recorded at `:81` |
| T-33-03-SC | Tampering | accept | CLOSED | See [§ Supply chain](#supply-chain-t-33-xx-sc) |

### 33-04 — Customer-realm identity provider (7)

| ID | Category | Disposition | Result | Evidence |
|----|----------|-------------|--------|----------|
| T-33-04-01 | Spoofing | mitigate | CLOSED | `infra/keycloak/realm-export-customers.template.json:67-72` — a single `identityProviders` entry (`alias: google`), `enabled: false`, `trustEmail: true`. Condition recorded in the template (`_note_trustEmail`, `:88`) and in `ADR-0005:146-151` ("safe only for a provider that actually verifies email … not a default to copy") |
| T-33-04-02 | Info disclosure | mitigate | CLOSED | No secret value is committed anywhere: `.env` is **not tracked** (`git ls-files` → no match); `.env.example:265-266` are empty assignments; `docker-compose.full-stack.yml:109` passes `${GOOGLE_CLIENT_SECRET:-}`. The secret reaches the realm only through the `envsubst` render |
| T-33-04-03 | Tampering | mitigate | CLOSED | `docker-compose.full-stack.yml:131` — the **second** (CUSTOMER) `envsubst` invocation names **both** `$$GOOGLE_CLIENT_ID` and `$$GOOGLE_CLIENT_SECRET`. This is the load-bearing detail: an unlisted name survives as the literal `${GOOGLE_CLIENT_ID}` token and Keycloak would use it as the client id (`_note_credentials`, template `:89`). Cross-recorded in `ADR-0005:91` |
| T-33-04-04 | Denial of service | mitigate | CLOSED | `scripts/verify-env.sh:346-367` reads the **realm template's own** `enabled` flag and requires the credentials only when it is `true` (`:367` info-skips when disabled). Fails **closed**: VOID when the template is missing (`:343`), unparseable (`:350`) or declares no flag (`:356`). The `VAR=  # comment` shape is caught on the raw file whether or not the provider is enabled (`:320-336`) |
| T-33-04-05 | Elevation of privilege | accept | CLOSED | Logged at [§ Accepted risks AR-2](#ar-2--keycloak-24-strips-unmanaged-attributes-on-brokered-provisioning) |
| T-33-04-06 | Denial of service | accept | CLOSED | Logged at [§ Accepted risks AR-3](#ar-3--csp-governance-of-the-brokered-redirect-is-out-of-scope) |
| T-33-04-SC | Tampering | accept | CLOSED | See [§ Supply chain](#supply-chain-t-33-xx-sc) |

### 33-05 — Shop coordinates: write path + backfill (7)

| ID | Category | Disposition | Result | Evidence |
|----|----------|-------------|--------|----------|
| T-33-05-01 | Tampering | mitigate | CLOSED — **strengthened** | `CreateShopRequest.java:43-49` — `@DecimalMin/@DecimalMax` on both axes. **All entry points checked**: exactly two HTTP consumers exist (`ShopController.java:99` create, `:132` update) and **both** carry `@Valid`. WR-02 adds `@AssertTrue @JsonIgnore isCoordinatePaired()` (`:62-66`) so a lone axis is a typed 400, and `ShopService.applyCoordinate:485-488` resets a lone **request** axis to the persisted pair — the entity cannot be consulted because the IGNORE-null mapper has already merged. WR-03 adds the UK bounding box at `:496-500`. Precedence rule documented at `:403-455` |
| T-33-05-02 | Elevation of privilege | mitigate | CLOSED | `ShopCoordinateBackfill.java:164-166` loops the `tenants` registry and pins per tenant via `TenantContext.set`; `:290-297` issues `set_config('app.current_tenant_id', ?, true)` (transaction-local) derived from `TenantContext`, never from a parameter. `ShopCoordinateBackfillIntegrationTest:215-231` asserts **zero rows updated and the write REFUSED, not skipped** without a pin, under a `NOSUPERUSER NOBYPASSRLS` role (`:112`). CONTROL at `:237-250`: without the downgrade the same unpinned call **does** write — so the arm can fail |
| T-33-05-03 | Denial of service | mitigate | CLOSED | Same regex and PK lookup as `T-33-02-04` |
| T-33-05-04 | Info disclosure | mitigate | CLOSED — **deviation recorded** | Address suppression verified: `ShopService.java:466-475` logs the **slug only** on a geocode miss, with the reason stated in-code; `PostcodeGeocoder.java:106` logs the extracted postcode only. Evidence-file clause verified: 0 password-shaped values in the phase directory (CONTROL as above). **Deviation:** the WR-03 fix added two WARNs that also log the vendor-supplied `latitude`/`longitude` (`:502`, `:508`) — beyond the register's "slug and extracted postcode **only**". Raised as `UF-33-01` |
| T-33-05-05 | Repudiation | accept | CLOSED | Logged at [§ Accepted risks AR-4](#ar-4--the-backfill-legitimately-generates-audit-revisions) |
| T-33-05-06 | Denial of service | mitigate | CLOSED | `scripts/check-live-shop-coordinates.sh:95-101` — `VOID=2`, exit 2 on missing docker, a stopped container, an unreadable role or an empty result (`:66`, `:134` "An empty result is never silently treated as zero"). **Executed against the running stack**: PASS on all five relations (A-1…A-5), 3/3 published shops with a coordinate, 0 at Null Island, 1,748,230 centroid rows — i.e. the *delivered runtime*, not the source, satisfies the claim |
| T-33-05-SC | Tampering | accept | CLOSED | See [§ Supply chain](#supply-chain-t-33-xx-sc) |

### 33-06 — Anonymous distance query (9)

| ID | Category | Disposition | Result | Evidence |
|----|----------|-------------|--------|----------|
| T-33-06-01 | Tampering | mitigate | CLOSED | `ShopRepository.java:101-146` — the entire native query uses named JPA parameters (`:lat`, `:lon`, `:latMin`, `:latMax`, `:lonMin`, `:lonMax`, `:radiusKm`), each bound via `@Param` to a **`double`** (not a String). The bounding box is computed in Java (`PublicStorefrontService.java:255`, `GeoBounds.boxAround`) and passed as four more named parameters. FORCE sweep of every phase-touched main-Java file for SQL concatenation found only `PostcodeCentroidImporter` interpolating compile-time constants (`STAGING_TABLE`, `:86`, a `private static final String`) — no untrusted input reaches any concatenation. CONTROL: the concat pattern matches 4 other files in the tree |
| T-33-06-02 | Tampering | mitigate | CLOSED | `PublicStorefrontService.java:259` — `PageRequest.of(…, Sort.unsorted())`; the client `Pageable`'s sort is discarded before the repository call at `:263-266`. The query owns its `ORDER BY` (`ShopRepository.java:119`) |
| T-33-06-03 | Info disclosure | mitigate | CLOSED | `published = true` present in the main query (`ShopRepository.java:112`) **and** in the `countQuery` (`:130`). `PublicStorefrontDistanceIntegrationTest:327-334` asserts the unpublished shop is absent from the **total**, separately from the content, with the leak explained at `:76-77` |
| T-33-06-04 | Info disclosure | mitigate | CLOSED | Prohibition recorded at `PublicStorefrontService.java:214-221`; the debug line at `:260` logs radius and page only, and the exception messages (`:236`, `:239`) name the permitted *range* and never echo the value. `PublicStorefrontController.java` logs nothing. **Whole-phase sweep**: across every phase-touched main-Java file, the only log statements carrying a coordinate are the two vendor-shop WARNs of `UF-33-01`; **no** log, metric tag or write anywhere carries the customer coordinate |
| T-33-06-05 | Denial of service | mitigate | CLOSED | `asin` haversine at `ShopRepository.java:106` and `:124`, with the `acos` domain-error trap and the `LEAST/GREATEST` requirement documented at `:63-70`. Coincident-point arms: `PublicStorefrontDistanceIntegrationTest:351` and `:369` (the second at a deliberately `acos`-hostile latitude, chosen because only 4% of sampled latitudes expose it — `:125-131`) |
| T-33-06-06 | Denial of service | accept | CLOSED | Logged at [§ Accepted risks AR-5](#ar-5--unauthenticated-scraping-of-the-public-directory) — **verified**, not assumed: `WebConfig.java:71-79` registers `rateLimitInterceptor` on `/**`, excluding only actuator/health/swagger, so `/api/v1/public/shops` is covered unchanged |
| T-33-06-07 | Elevation of privilege | mitigate | CLOSED | `V61__postcode_centroid.sql` contains no `POLICY`, `ROW LEVEL` or `ALTER TABLE … shops` statement. `PublicStorefrontDistanceIntegrationTest:529-555` asserts that with **no** tenant GUC the distance endpoint still returns shops resolving to **two distinct tenants** — tenant ids read back out of the database, not assumed from the fixture (`:542-550`) — while the unpublished shop, planted on the nearest shop's exact coordinates, stays absent (`:554`) |
| T-33-06-08 | Repudiation | mitigate | CLOSED | `scripts/check-openapi-snapshot-fresh.sh:99-109` — `VOID=2`, fails closed on missing tooling, an unreachable service, or empty/unparseable output. Wired at `.github/workflows/e2e-nightly.yml:247`; its absence from `gate-enforcement.conf`'s CI table is **declared with a reason** (`:27`). `scripts/check-gate-enforcement.sh` **executed**: PASS, 33 gates, 5 declared exemptions. Contract content verified: the committed `docs/api/openapi-snapshot.json` carries `lat`, `lon` and `radiusKm`, and `coordinatePaired` appears **0** times (the `@JsonIgnore` held) |
| T-33-06-SC | Tampering | accept | CLOSED | See [§ Supply chain](#supply-chain-t-33-xx-sc) |

### 33-07 — Browser geolocation island (8)

| ID | Category | Disposition | Result | Evidence |
|----|----------|-------------|--------|----------|
| T-33-07-01 | Info disclosure | mitigate | CLOSED | `near-you-row.tsx:194-197` — the coordinate lives in `useState` only. All four sinks (`localStorage`, `sessionStorage`, `document.cookie`, `cookieStore`) → **0 matches** in the island **and** in `app/page.tsx`, `lib/distance.ts`, `shop-card.tsx`. CONTROL: the identical pattern matches 6 other frontend modules (`lib/cart-identity.ts`, `lib/customer-auth.ts`, `lib/order-history.ts`, `lib/shop-context.ts`, …), so the zero is a fact about the island, not about the pattern |
| T-33-07-02 | Info disclosure | mitigate | CLOSED | Exactly **one** `fetch(` in the island (`:261`), to `${API_BASE}/public/shops` — no analytics or telemetry call of any kind. Server half closed under `T-33-06-04` |
| T-33-07-03 | Elevation of privilege | mitigate | CLOSED | `navigator.geolocation.getCurrentPosition` appears once (`:248`), inside `requestLocation` (a `useCallback`, `:237`) bound to the `<button>`'s `onClick` (`:360-362`). The island imports **no `useEffect` at all** (`:3` — `useCallback, useState`), so no mount-time or render-time path can reach the prompt |
| T-33-07-04 | Denial of service | mitigate | CLOSED | One `fetch` per grant: the single call site sits in the `getCurrentPosition` success callback. `watchPosition` appears **0** times in code (the one match is the comment at `:251` explaining why it is not used) |
| T-33-07-05 | Spoofing | mitigate | CLOSED | `e2e/near-you-row.spec.ts:126` (unlocated) and `:182` (post-denial) both assert `nearYouHeading(page)` `toHaveCount(0)` while the location-free heading is present. Non-vacuity guards on both sides: `:114` fails loudly if geolocation is denied at the header (which would make every located arm pass for the wrong reason), and `:176` proves the denial actually landed |
| T-33-07-06 | Repudiation | mitigate | CLOSED — **strengthened** | Disclosure rendered at `near-you-row.tsx:331-350` naming the count, with a route to the full list. WR-01 gates the "further than" arithmetic on **both** lists being provably complete (`:229-234`, using `serverTotal` and `nearbyTotal`), falling back to suppression rather than fabrication; the "no location data" clause deliberately survives truncation because it stays true of what it counted (`:219-226`). E2E arm with a NULL-coordinate published fixture at `e2e/near-you-row.spec.ts:267-358`, including the escape link assertion at `:358` |
| T-33-07-07 | Info disclosure | mitigate | CLOSED | `near-you-row.tsx:3-10` — the island's only type import is `import type { PublicShop } from "@/types/storefront"`. `lib/storefront-server` is **not** imported; the single textual match (`:268`) is a comment explaining the distinction |
| T-33-07-SC | Tampering | accept | CLOSED | See [§ Supply chain](#supply-chain-t-33-xx-sc) |

---

## Supply chain (`T-33-XX-SC`)

All eight per-plan supply-chain rows share one disposition (`accept`) and one claim: **the phase adds
no package**. Verified once, for all eight, against the branch diff rather than against the plans:

```
git diff --name-only main...HEAD | rg 'package\.json|package-lock|build\.gradle|go\.mod|go\.sum|requirements|Cargo|pom\.xml'
  -> NONE — no dependency manifest changed
```

The phase's one ingested external artefact is the OS Code-Point Open archive, which is md5-gated
(`T-33-01-01`) and committed as a derived CSV so the runtime never fetches it. Two rejected
alternatives are recorded on measured grounds rather than preference: **PostGIS** and
**`earthdistance`** — the latter is not installable as `jtoye_app` at all (`T-33-02-02`). A
geolocation npm wrapper was rejected for `33-07` as "pure supply-chain surface" against ~40 lines of
`useState` plus `getCurrentPosition`.

**Result: all 8 SC threats CLOSED.**

---

## Accepted risks

Each entry is a `accept`-disposition threat from the plan-time register, reproduced here so the
acceptance is logged rather than implied. Every one was verified to be *true of the tree*, not
merely asserted.

### AR-1 — `postcode_centroid` is exempt from the RLS schema walk
**Threat:** `T-33-02-03` (Information disclosure) · **Accepted**

`RlsContractTest.EXEMPT_TABLES` gains `"postcode_centroid"` (`:133`). The table is public reference
data: no `tenant_id`, no customer rows, no `_aud` mirror (`V61__postcode_centroid.sql:57`). The
exemption is **by addition** — the schema-walk assertion itself (`everyPublicTableHasRlsAndForce`,
`:140-145`) is untouched, so the sweep still bites for every other table. The comment at `:130-132`
records why RLS on this table would be actively harmful: a tenant-scoped policy would return zero
rows to every caller and silently disable locality platform-wide while every test stayed green.
**Residual:** none material — the data is published by Ordnance Survey under OGL v3.

### AR-2 — Keycloak 24 strips unmanaged attributes on brokered provisioning
**Threat:** `T-33-04-05` (Elevation of privilege) · **Accepted**

The Google IdP ships `enabled: false`, so **no brokered user is provisioned this phase** and the
KC24 unmanaged-attribute strip cannot bite. `ADR-0005:153-157` records it as a precondition to
re-check against `CustomerJwtVerifier` before enabling, and notes the mitigating fact that the
verifier reads only `email` and `email_verified` — both standard OIDC claims, neither affected by
the strip. **Residual:** carried by whoever flips `enabled` to `true`; the ADR is the handoff.

### AR-3 — CSP governance of the brokered redirect is out of scope
**Threat:** `T-33-04-06` (Denial of service) · **Accepted**

The J'Toye → realm hop is already permitted by the existing CSP; the Keycloak → `accounts.google.com`
hop is governed by **Keycloak's** CSP, not this repository's. `ADR-0005:103-117` records the analysis
and, notably, the trailing-slash detail that has already caused a live outage on this project (a CSP
source *with a path* matches exactly unless it ends `/`). **Residual:** an enabler must re-verify the
CSP against the live redirect chain; recorded in the ADR.

### AR-4 — The backfill legitimately generates audit revisions
**Threat:** `T-33-05-05` (Repudiation) · **Accepted**

Envers `shops_aud` mirrors exist by design and a one-off coordinate backfill legitimately produces a
revision spike. Documented so a future auditor does not misread it as tampering.
`ShopCoordinateBackfill.java:62` additionally records that the write is a bulk JPQL `UPDATE`, which
does **not** generate `shops_aud` rows — so the expected spike is bounded. **Residual:** none.

### AR-5 — Unauthenticated scraping of the public directory
**Threat:** `T-33-06-06` (Denial of service) · **Accepted**

The distance parameters are additive to `GET /public/shops`, which was already anonymously exposed;
the phase widens no authentication boundary. Existing Bucket4j limits apply unchanged — **verified**
rather than assumed: `WebConfig.java:71-79` registers `rateLimitInterceptor` on `/**` with only
`/actuator/**`, `/health` and the swagger paths excluded. **Residual:** the endpoint remains
scrapeable at the configured rate (100 req/min per tenant, burst 20); accepted as the pre-existing
posture of a public storefront directory.

---

## Unregistered flags

`33-06-SUMMARY.md` and `33-07-SUMMARY.md` both declare `## Threat Flags: None`. Per the standing
warning against treating that section as a complete inventory, the six other summaries were checked
and **carry no `## Threat Flags` section at all** — so for `33-00` … `33-05` the executor-side flag
channel is simply absent, and independent sweeps were run over the phase-touched files instead
(SQL concatenation, coordinate logging/metrics/persistence, browser storage sinks, `"use client"`
chains, dependency manifests). Those sweeps surfaced one item.

### UF-33-01 — WARNING — the WR-03 fix logs a vendor-supplied coordinate
**Surface:** `core-java/src/main/java/uk/jtoye/core/shop/ShopService.java:502` and `:508`
**Introduced by:** `4f1f9d6f` (WR-03), *after* the threat register was authored
**Maps to:** `T-33-05-04`, whose mitigation reads "Log the shop slug and extracted postcode **only**"

The two new WARNs (`event=client_coordinate_accepted` / `event=client_coordinate_rejected`) log the
shop slug **and** the vendor-supplied `latitude`/`longitude`. This is deliberate and defensible — the
log line exists precisely so an operator can review an unverified vendor-supplied position on a
public ranking surface, which is the whole point of the WR-03 containment — but it is a widening of
the declared mitigation that no register row covers.

**Why this is a WARNING and not a BLOCKER:**
- The value is a **vendor business** coordinate, not the customer coordinate that `T-33-06-04` /
  `T-33-07-01` protect. The customer coordinate is logged nowhere (verified by whole-phase sweep).
- On the *accepted* branch the same coordinate is about to be published on the discovery surface, so
  the log discloses nothing the API does not.
- The address free-text — the actual vendor-identifying detail `T-33-05-04` was written to suppress —
  is still never logged (`ShopService.java:466-475`).

**Residual, for the owner to accept or close:** on the *rejected* branch the coordinate never becomes
public, and a home-kitchen vendor's supplied pair could be a residential position. If that matters,
the cheap close is to log the rejected pair at coarse precision (or drop it and keep the slug), which
is a one-line change and needs no redesign. Recorded, not patched — implementation files are
read-only to this audit.

---

## Audit trail

| Item | Value |
|------|-------|
| Tree audited | `phase/33-the-consumer-product` @ `HEAD`, working tree clean |
| Plans read | `33-00` … `33-07` `-PLAN.md` (8 `<threat_model>` blocks, 53 rows) |
| Summaries read | all 8 `-SUMMARY.md`; `## Threat Flags` present in 2 of 8 |
| Review evidence | `33-REVIEW.md`, `33-REVIEW-FIX.md` (4 findings, 4 fixed, iteration 1) |
| Gates executed | `check-no-create-extension.sh` (PASS), `check-geo-attribution.sh` (PASS), `check-gate-enforcement.sh` (PASS), `check-live-shop-coordinates.sh` (PASS, live runtime) |
| Positive controls run | storage-sink pattern (6 matches elsewhere), SQL-concat pattern (4 matches elsewhere), password-shape pattern (matches `.env.example`) |
| Implementation files modified | **none** — this audit is read-only |
| ASVS level | L1 |
| `block_on` | `critical` — no critical finding; nothing blocks |

_Audited: 2026-08-09 · Auditor: gsd-security-auditor · Round 1_
