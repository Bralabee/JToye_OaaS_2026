---
phase: 33-the-consumer-product
verified: 2026-08-09T10:12:06Z
status: passed
score: 3/6 roadmap success criteria verified (CUST-01 x2, CUST-03 x1); 3 deferred by owner override (CUST-02 x2, CUST-04 x1)
overrides_applied: 3
override_authority: "Owner (Sanmi), 2026-08-09, in-session response to this report: 'accept the deferrals'"
gaps:
  - truth: "Onboarding has no two-actor dead-end — MANUAL_REVIEW appears on a surface a human can act from, OR a recorded decision names who adjudicates it (roadmap SC-3 / CUST-02)"
    status: deferred
    override: "Accepted as intentionally deferred by the owner, 2026-08-09 (extends D-2). The unresolved substance — naming a MANUAL_REVIEW adjudicator — is routed to the next phase's decision queue, not silently dropped."
    reason: "D-2 (CONTEXT.md, dated 2026-08-08, owner-approved before execution) explains why no code was shipped for #453 — it rejects tenant GROUP_ADMIN self-review and a new cross-tenant operator identity — but it does not name who adjudicates a stalled MANUAL_REVIEW onboarding. The roadmap's own SC-3 requires one of two limbs; neither is met. This is not a SUMMARY overclaim — REQUIREMENTS.md and 33-07-SUMMARY.md both state it plainly and the phase's own ROADMAP scope note calls it 'one gap escalated rather than silently absorbed.'"
    artifacts:
      - path: ".planning/phases/33-the-consumer-product/CONTEXT.md"
        issue: "D-2 rejects two candidate adjudicators and names none"
    missing:
      - "A named adjudicator (person, role, or process) for MANUAL_REVIEW onboarding stalls, or an actionable surface a human can act from"
  - truth: "A signed-in customer sees only what applies to them: tracking moves into the profile and auto-populates, second-shop onboarding and staff invite exist (roadmap SC-4, rewritten form / CUST-02)"
    status: deferred
    override: "Accepted as intentionally deferred by the owner, 2026-08-09 (per D-3). /profile + #458 dispatch half + #452 second-shop/staff-invite remain open backlog items for a future phase."
    reason: "The nav-gating half (For operators / Track order gated behind session state) shipped before this phase (b9f80f81, 96d8432f) and is confirmed live in frontend/components/public/public-header.tsx — that part is not a gap. But no /profile route exists anywhere under frontend/app, so order tracking has not moved into a customer profile, and #452's second-shop onboarding / staff-invite paths were not built. Both are out of scope per D-3, and REQUIREMENTS.md records CUST-02 as 'not closed' rather than claiming this shipped."
    artifacts:
      - path: "frontend/app"
        issue: "No profile/ directory exists — confirmed by directory listing"
    missing:
      - "A /profile route with auto-populated order tracking (#458 dispatch half)"
      - "Second-shop onboarding path and staff-invite flow (#452)"
  - truth: "The customer-facing surface has been reviewed against what it actually renders: a look-and-feel pass on web and mobile, Keycloak stops shipping the stock theme on both realms, and the staff screen gains bulk-revoke of JIT-provisioned rows (roadmap SC-6 / CUST-04)"
    status: deferred
    override: "Accepted as intentionally deferred by the owner, 2026-08-09 (per D-3). Recorded as never-measured, not clean — the look-and-feel pass, Keycloak themes, and staff bulk-revoke carry to a future phase."
    reason: "Confirmed by an empty file-diff: no theme file and no staff bulk-revoke file was touched anywhere across the phase's full commit range (main..phase/33-the-consumer-product). This was never measured, per D-3 scope and the roadmap's own decay-audit correction ('SC-6 was NOT measured'). REQUIREMENTS.md correctly records CUST-04 as 'Unknown, not clean' rather than implying it is fine — the distinction the requirement itself insists on."
    artifacts:
      - path: "infra/keycloak"
        issue: "No custom theme added for either realm in this phase"
      - path: "frontend/app/dashboard/staff"
        issue: "No bulk-revoke-of-JIT-rows affordance added in this phase"
    missing:
      - "A look-and-feel review pass on web and mobile"
      - "Keycloak custom theme replacing the stock theme on jtoye-dev and jtoye-customers"
      - "Staff screen bulk-revoke of JIT-provisioned rows"
---

# Phase 33: The Consumer Product — Verification Report

**Phase Goal:** A real person who is not a vendor can find a shop near them, understand what they
are buying, sign up safely, and be shown something true.
**Requirements:** CUST-01, CUST-02, CUST-03, CUST-04
**Verified:** 2026-08-09T10:12:06Z
**Status:** passed (3 gaps deferred by owner override)
**Re-verification:** No — initial verification

## Owner overrides (2026-08-09)

The owner reviewed this report in-session and accepted all three CUST-02/CUST-04 gaps as
**intentionally deferred**, extending the dated D-2/D-3 scope decisions (CONTEXT.md, 2026-08-08)
that produced them. Nothing is reclassified as done: REQUIREMENTS.md continues to record CUST-02
as open and CUST-04 as never-measured. The single substantive loose end — **who adjudicates a
MANUAL_REVIEW onboarding stall** — is explicitly carried forward as a decision item for the next
phase, alongside the deferred /profile route (#458), second-shop/staff-invite (#452), Keycloak
themes, and staff bulk-revoke.

## Summary judgement

This phase shipped a genuinely working, functionally-proven locality feature (CUST-01) and a
properly documented, dated deliberate decision for consumer-realm sign-up (CUST-03). Both are
verified below with evidence gathered independently of SUMMARY.md, including a live functional
spot-check against the running dev stack.

CUST-02 and CUST-04 are **not** closed, and — critically — **this phase's own documentation says
so plainly and repeatedly**: REQUIREMENTS.md ticks CUST-01 and CUST-03 only, ROADMAP.md's Phase 33
scope note calls the CUST-02 gap "escalated rather than silently absorbed," and 33-07-SUMMARY.md
has a "What did NOT close" section naming exactly these two gaps. This is not a SUMMARY overclaim
this verifier is catching — it is a self-disclosed, pre-approved scope decision (CONTEXT.md D-2/D-3,
dated 2026-08-08, before any execution began). Goal-backward verification against the ROADMAP's own
six success criteria nonetheless resolves two of them to FAILED, because the roadmap's own wording
("the criterion fails if the phase ships code without settling it," and SC-6 "was NOT measured")
makes them falsifiable-and-failing by design, not by oversight.

**This is an escalation, not a defect report.** The gaps are structured below for
`/gsd:plan-phase --gaps` in case the developer wants CUST-02/CUST-04 picked up as their own
follow-on work; alternatively, since these were already deliberately scoped out by an owner
decision made *before* the phase's plans were written, the developer may prefer to record a
formal override in this file's frontmatter to accept the phase as complete with those two
requirements consciously left open. Both are legitimate outcomes — this report does not choose
between them.

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria 1–6)

| # | Truth (roadmap SC) | Req | Status | Evidence |
|---|---|---|---|---|
| 1 | Locality exists as a concept — device location used, shop coordinates read, a delivery radius enforced | CUST-01 | ✓ VERIFIED | Five-link chain closed end to end: V61 migration + `postcode_centroid` table (DDL-only, no `CREATE EXTENSION` — `check-no-create-extension.sh` passes locally, 61/61 migrations clean); `PostcodeGeocoder.locate` (offline, never `(0,0)`, table-authoritative); `ShopCoordinateBackfill` populated the live dev DB (proven via `check-runtime-freshness.sh` FRESH + live query below); `ShopRepository.findPublishedNear` native asin-haversine with leakproof bounding-box prefilter and `CORNER` fixture that distinguishes box-filtering from radius-filtering (`PublicStorefrontDistanceIntegrationTest.java:53-69,110-117,274-290`); `near-you-row.tsx` gesture-gated `navigator.geolocation.getCurrentPosition` (`:205-215`), `Permissions-Policy: geolocation=(self)` confirmed live via `curl -sSI http://localhost:3000/`. **Live functional proof**: `GET /public/shops?lat=51.4681&lon=-0.0714&radiusKm=8` on the running stack returned 3 real shops ordered by real SQL-computed distance (0.188 km, 0.491 km, 2.865 km) |
| 2 | Nothing on the storefront is fictional — "Cooking near you" resolves to real published shops | CUST-01 | ✓ VERIFIED | `frontend/app/page.tsx` no longer contains `featuredDishes` or any of the five invented vendors (0 matches); renders `loadShopList` + `NearYouRow`. **Live proof**: `curl http://localhost:3000/` HTML contains "Mama Ade's Kitchen" (3x), "Peckham Jollof Co." (5x), "Brixton Village Grill" (5x); 0 matches for any of the five old fictional names (Spice Route, Olive & Vine, Crumb & Co, Hanoi House, Mama's Kitchen) |
| 3 | Onboarding has no two-actor dead-end — `MANUAL_REVIEW` reaches an actionable surface, or a recorded decision names who adjudicates it | CUST-02 | ✗ FAILED | D-2 (`CONTEXT.md`) is a real, dated, owner-approved decision, but it explicitly rejects two candidate adjudicators and **names none**. No onboarding/notification/dispatch file was touched anywhere in this phase (confirmed by empty `git diff --name-only` for those paths across the full phase commit range). REQUIREMENTS.md agrees: "Gap survives the phase open." See gaps below |
| 4 | A signed-in customer sees only what applies to them: gating (done), tracking moves into the profile, second-shop onboarding + staff invite exist | CUST-02 | ✗ FAILED | Nav gating pre-dates this phase and is confirmed live (`public-header.tsx:104,107,192,197` — "For operators" / "Track order" behind `useCustomerSession`). No `frontend/app/profile` directory exists at all (`ls frontend/app` — confirmed absent). No second-shop-onboarding or staff-invite code was added. Roadmap itself rewrote this criterion 2026-08-08 to the unmet remainder only, and it remains unmet |
| 5 | Consumer sign-up has more than one route in, or a recorded dated decision | CUST-03 | ✓ VERIFIED | `docs/architecture/decisions/ADR-0005-customer-realm-identity-providers.md` exists, dated 2026-08-08, Status: Accepted, cites the specific technical blocker (Google's HTTPS-on-a-resolving-host redirect-URI requirement, re-measured against `jtoye.co.uk`'s live parking-page state with positive AND negative DNS/HTTPS controls). `infra/keycloak/realm-export-customers.template.json:67-87` carries a `google` identityProviders entry with `enabled: false` and zero committed credentials (`clientSecret: "${GOOGLE_CLIENT_SECRET}"`, sourced from env, not hardcoded) — inert groundwork, not a functioning second route. Consumer sign-up remains genuinely single-route today, exactly as ADR-0005 states |
| 6 | The customer-facing surface has been reviewed against what it renders: look-and-feel pass, Keycloak stock theme replaced on both realms, staff bulk-revoke of JIT rows | CUST-04 | ✗ FAILED | Zero theme files and zero staff-bulk-revoke files touched anywhere in this phase's commit range. `33-07-SUMMARY.md:227` states plainly: "nobody looked at the Keycloak theme on either realm or at the staff screen's bulk-revoke." REQUIREMENTS.md correctly records this as "Unknown, not clean," not as done |

**Score:** 3/6 truths verified (CUST-01 x2, CUST-03 x1); 3 failed (CUST-02 x2, CUST-04 x1)

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `core-java/src/main/resources/db/migration/V61__postcode_centroid.sql` | `postcode_centroid` DDL + `shops(latitude,longitude)` partial btree, no `CREATE EXTENSION` | ✓ VERIFIED | Present, DDL-only as designed; `check-no-create-extension.sh` passes (61 migrations scanned, 1 documented exemption) |
| `core-java/src/main/java/uk/jtoye/core/geo/PostcodeGeocoder.java` | Single offline postcode→coordinate implementation | ✓ VERIFIED | `locate()` never throws, never returns `(0,0)`, table-authoritative lookup; used by both `ShopService` (write path) and `DemoDataSeeder` (`DemoDataSeeder.java:602,615,627-628`) |
| `core-java/src/main/java/uk/jtoye/core/geo/GeoBounds.java` | Radius → leakproof, pole-guarded lat/lon box | ✓ VERIFIED | Present, consumed by `ShopRepository.findPublishedNear` bounding-box prefilter |
| `core-java/src/main/java/uk/jtoye/core/geo/ShopCoordinateBackfill.java` | Idempotent, tenant-looped backfill under FORCE RLS | ✓ VERIFIED | Present; `ApplicationReadyEvent`-hooked, explicit `TenantContext.set` pin documented and reasoned against the recorded V25/V44/V57 bare-UPDATE trap |
| `core-java/src/main/java/uk/jtoye/core/shop/dto/CreateShopRequest.java` | Range-validated `latitude`/`longitude` | ✓ VERIFIED | `@DecimalMin`/`@DecimalMax` -90..90 / -180..180 confirmed present |
| `core-java/src/main/java/uk/jtoye/core/shop/ShopRepository.java` | `findPublishedNear` — native asin-haversine, leakproof box prefilter, explicit `countQuery` | ✓ VERIFIED | Confirmed: `nativeQuery = true`, separate `countQuery`, `published = true` filter, `latitude`/`longitude IS NOT NULL` guards |
| `frontend/app/page.tsx` | Async Server Component: real shops via `loadShopList`, `shopListStructuredData` JSON-LD | ✓ VERIFIED | Confirmed present and wired; live HTML carries `ItemList` JSON-LD (2 occurrences) and real shop names |
| `frontend/components/marketing/near-you-row.tsx` | Client island: gesture-gated geolocation, three states, exclusion disclosure | ✓ VERIFIED | `"use client"`, `navigator.geolocation.getCurrentPosition` behind explicit gesture, no `localStorage`/`sessionStorage` use (grep confirmed absent) |
| `docs/architecture/decisions/ADR-0005-customer-realm-identity-providers.md` | Dated decision for CUST-03's second limb | ✓ VERIFIED | Present, Accepted 2026-08-08, names the specific blocker and revisit triggers |
| `infra/keycloak/realm-export-customers.template.json` | `identityProviders` groundwork, inert by default | ✓ VERIFIED | `google` entry present, `enabled: false`, credentials from env vars, no secret committed |
| `frontend/app/profile` (implied by rewritten SC-4) | Customer order-tracking surface | ✗ MISSING | No such directory exists — confirmed by `ls frontend/app` |
| Keycloak custom theme (either realm) | Replace stock theme (CUST-04) | ✗ MISSING | No theme file touched this phase |
| Staff screen bulk-revoke of JIT rows (CUST-04) | Bulk-revoke affordance | ✗ MISSING | No such file touched this phase |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `PublicStorefrontController` | `ShopRepository.findPublishedNear` | `lat`/`lon`/`radiusKm` query params via `PublicStorefrontService` | ✓ WIRED | Confirmed in both files; range/finiteness validation in `PublicStorefrontService.java:230-263` before the repository call |
| `DemoDataSeeder` | `PostcodeGeocoder` | Same geocoder as the API write path | ✓ WIRED | `DemoDataSeeder.java:602-628` calls `postcodeGeocoder.locate(...)`, sets `latitude`/`longitude` on the shop entity |
| `frontend/app/page.tsx` | `NearYouRow` | `<NearYouRow serverShops={shops} />` | ✓ WIRED | Confirmed in `page.tsx:266` |
| `.github/workflows/docs-freshness.yml` | `scripts/check-geo-attribution.sh` | CI step | ✓ WIRED | `docs-freshness.yml:81` |
| `.github/workflows/ci-cd.yaml` | `scripts/check-no-create-extension.sh` | CI step | ✓ WIRED | `ci-cd.yaml:688-689` |
| `.github/workflows/e2e-nightly.yml` | `scripts/check-openapi-snapshot-fresh.sh` | CI step | ✓ WIRED | `e2e-nightly.yml:247`; `check-live-shop-coordinates.sh` deliberately absent from CI with a recorded, reasoned exemption in `scripts/gates/gate-enforcement.conf:27,35` (no container to exec into on a hosted runner) |
| `RlsContractTest.EXEMPT_TABLES` | `postcode_centroid` | Documented exemption, sweep itself untouched | ✓ WIRED | `RlsContractTest.java:128-134` — reasoned (no `tenant_id`, public reference data), does not weaken the general sweep |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| `frontend/app/page.tsx` | `shops` (from `loadShopList`) | `PublicStorefrontService` → `ShopRepository` → live Postgres `shops` table | Yes — live `GET /public/shops` call returned 3 real shops with real addresses, real distances | ✓ FLOWING |
| `near-you-row.tsx` | located shop list | `GET /public/shops?lat=&lon=&radiusKm=` via device geolocation | Yes — same endpoint verified live above, ordered by real SQL-computed `distanceKm` | ✓ FLOWING |
| `ShopWithDistance` projection | `distanceKm` | SQL-computed in the same query used for ordering | Yes — confirmed identical formula in the `SELECT` and `ORDER BY` clause of `findPublishedNear` | ✓ FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| Distance endpoint returns real, ordered, radius-filtered shops | `curl "http://localhost:9090/api/v1/public/shops?lat=51.4681&lon=-0.0714&radiusKm=8&size=10"` on the live rebuilt stack | 3 shops returned, `distanceKm` ascending (0.188, 0.491, 2.865), real addresses/names, `totalElements: 3` | ✓ PASS |
| Landing page HTML contains real shop names, zero fictional names | `curl http://localhost:3000/` | 3 real shop names present (13 total occurrences across row + JSON-LD), 0 occurrences of any of the 5 fictional names | ✓ PASS |
| Permissions-Policy permits geolocation on `/` | `curl -sSI http://localhost:3000/` | `Permissions-Policy: camera=(), microphone=(), geolocation=(self), browsing-topics=()` | ✓ PASS |
| Delivered runtime matches the branch (all 4 built services) | `bash scripts/check-runtime-freshness.sh` | `PASS: 4 running built service(s) match the source tree (0 unverified)`; frontend image tagged `2026-08-09 09:49:44Z` ≥ latest commit `159b135f` (09:48:22Z, the miles-correction commit) | ✓ PASS |
| No `CREATE EXTENSION` in any migration | `bash scripts/check-no-create-extension.sh` | `PASS: none of the 61 migration(s) create a PostgreSQL extension` | ✓ PASS |
| Docs metrics match source tree, and prose quotes match the manifest | `bash scripts/docs-freshness.sh && bash scripts/check-doc-metrics.sh` | Both PASS: 2628 total logical invocations, 37/37 prose claims match | ✓ PASS |
| Every CI gate either runs or has a declared reason it cannot | `bash scripts/check-gate-enforcement.sh` | `PASS: every gate either runs in CI or has a declared reason it cannot` (33 gates, 6 workflows, 5 declared exemptions) | ✓ PASS |
| Branch is not behind its base | `git log phase/33-the-consumer-product..origin/main` | Empty | ✓ PASS |

### Probe Execution

No `scripts/*/tests/probe-*.sh` convention used by this phase; the phase's own verification scripts
(`check-live-shop-coordinates.sh`, `check-openapi-snapshot-fresh.sh`, `check-geo-attribution.sh`,
`check-no-create-extension.sh`) were run directly above under Behavioral Spot-Checks / Key Link
Verification rather than treated as a separate probe category — they are CI-wired gates, not
plan-declared probes.

### CI Status (informational, not a blocker)

The head commit's CI/CD Pipeline run (`31307349729`) had 8/9 jobs green
(`OpenAPI Breaking-Change Gate`, `MCP Server Tests`, `Frontend E2E (public surfaces)`, `Run Tests`,
`Lint`, `K8s Kustomize Secret Guard`, `Operational Contracts`, `Security Scan`) at verification time,
with `Integration Tests (Testcontainers RLS)` still in progress after 100+ seconds of polling and
`Branch Not Behind Base` queued behind it. This is consistent with the documented known context
("in flight on 33-07's head"). The prior commit (33-06's head, `31301402536`) completed with 100%
green including Integration Tests, and 33-07's diff from that point is frontend-only (the miles
correction + its tests), which does not touch any Java/RLS surface — so this is not expected to flip,
but it was not observed to completion and should be confirmed green before this branch merges.

### Requirements Coverage

| Requirement | Source Plan(s) | Description | Status | Evidence |
|---|---|---|---|---|
| CUST-01 | 33-01, 33-02, 33-03, 33-05, 33-06, 33-07 | Locality exists, nothing on the storefront is fictional | ✓ SATISFIED | Verified independently above, including live functional proof beyond SUMMARY claims |
| CUST-02 | 33-04 (D-2 decision only) | No lifecycle dead-ends: MANUAL_REVIEW adjudication, signed-in nav + profile tracking, second-shop onboarding, staff invite | ✗ BLOCKED | D-2 explains but does not settle the adjudicator question; no code shipped for any of #453/#458-dispatch/#452, consistent with D-3 scope and REQUIREMENTS.md's own "Not closed" |
| CUST-03 | 33-04 | Consumer sign-up has more than one route in, or a recorded decision | ✓ SATISFIED | ADR-0005 verified, dated, reasoned, groundwork inert and credential-free — recorded-decision limb genuinely met |
| CUST-04 | not planned — out of scope per D-3 | Customer-facing surface reviewed: look-and-feel, Keycloak theme, staff bulk-revoke | ✗ BLOCKED (unmeasured) | Confirmed zero files touched for either sub-item; correctly recorded in REQUIREMENTS.md as "Unknown, not clean" rather than falsely as fine |

No orphaned requirements: all four CUST-* IDs declared in `.planning/REQUIREMENTS.md` for Phase 33
appear in at least one plan's `requirements:` frontmatter (33-00, 33-01, 33-02, 33-03, 33-04, 33-05,
33-06, 33-07 collectively cover CUST-01/CUST-03; CUST-02 and CUST-04 are explicitly scoped out by
D-2/D-3 rather than silently dropped).

### Anti-Patterns Found

None. A full scan of all 65 non-planning, non-markdown files modified in this phase
(`git diff --name-only $(git merge-base main phase/33-the-consumer-product)..phase/33-the-consumer-product`)
for `TBD|FIXME|XXX`, `TODO|HACK|PLACEHOLDER`, "coming soon" / "not yet implemented" / "not available"
phrasing, and bare `return null|{}|[]` / `=> {}` stub shapes turned up zero genuine debt markers or
stubs. The handful of raw string matches (`XX99XXX` in a Javadoc example, `PLACEHOLDER` in gitleaks
allowlist descriptions and Spring config-placeholder discussion, a `SafeImage` placeholder-div
comment, `return []` in a tags-parsing helper) are all legitimate technical usage, not incomplete
work. No debt-marker gate violation.

### Human Verification Required

None outstanding for CUST-01. The located-journey walkthrough already happened
(2026-08-09, "Walkthrough was a success"), the two corrections it produced (miles conversion) are
shipped in `159b135f`, and the two search findings it surfaced were filed as #619 and #207 with the
owner's explicit agreement rather than absorbed into this phase — confirmed both are genuinely OPEN
GitHub issues, correctly scoped outside CUST-01.

**One decision-level item is escalated to the developer, not a test-execution item:** whether to
(a) accept CUST-02 and CUST-04 as intentionally left open (in which case a formal override entry in
this file's frontmatter would let the phase report as fully passed) or (b) route the two structured
gaps below into a follow-on closure plan. Both are legitimate given D-2/D-3 were owner decisions
made *before* this phase's plans were written — this report does not choose for you.

### Gaps Summary

Two of the phase's own four requirements are genuinely not satisfied against the ROADMAP's success
criteria as written — **CUST-02** (no named adjudicator for `MANUAL_REVIEW`, no `/profile` route, no
second-shop onboarding, no staff invite) and **CUST-04** (customer-facing look-and-feel pass, Keycloak
theme, staff bulk-revoke — all never measured, not merely unfinished). Both are pre-existing, dated,
owner-approved scope decisions (`CONTEXT.md` D-2/D-3, 2026-08-08) rather than defects introduced by
this phase's execution, and both are disclosed with unusual candour in REQUIREMENTS.md and
33-07-SUMMARY.md's own "What did NOT close" section — this verifier found no instance of the SUMMARY
overclaiming what the codebase actually contains.

**CUST-01 and CUST-03, the requirements this phase's eight plans actually targeted, are fully and
independently verified** — including a live functional check against the running, freshly-rebuilt
dev stack that goes beyond what any SUMMARY claimed (real distance-ordered shops returned from the
anonymous `/public/shops` endpoint, zero fictional vendor names in the served HTML, the
`geolocation=(self)` header live, and `check-runtime-freshness.sh` proving the running frontend image
matches the miles-correction commit byte-for-byte in timestamp terms).

Because CUST-02 and CUST-04 map to two of the ROADMAP's own six numbered success criteria (SC-3,
SC-4, SC-6), and those criteria are written to fail exactly under the conditions found on this tree,
overall phase status is `gaps_found` per the standard decision tree — not because the phase executed
poorly, but because the roadmap's own bar for "done" was set higher than what D-2/D-3 scoped this
phase to deliver, and that mismatch was visible in the phase's own planning documents before a single
task ran.

---

_Verified: 2026-08-09T10:12:06Z_
_Verifier: Claude (gsd-verifier)_
