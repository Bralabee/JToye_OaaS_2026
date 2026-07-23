---
phase: 24-image-architecture-cow-assets-safe-upload-pipeline
plan: 02
subsystem: database
tags: [rls, postgres, flyway, copy-on-write, media-asset, dedup, envers, dual-read, testcontainers]

# Dependency graph
requires:
  - phase: 23-vendor-scoped-access
    provides: V52 shop_staff (V53 media_asset migration slot follows it; out-of-order=true already set) + the RLS-under-NOSUPERUSER + non-fresh-DB backfill test templates
  - phase: 24-image-architecture-cow-assets-safe-upload-pipeline (plan 24-01)
    provides: MediaProperties/MediaNormalizer transform layer (no direct dependency this plan; parallel-safe Wave-1 sibling)
provides:
  - "V53 media_asset + product_media + media_asset_aud — ENABLE+FORCE RLS via the safe current_tenant_id() helper; sha256 per-tenant dedup unique index; per-tenant set_config backfill loop"
  - "media_asset pending-placement intent columns (product_id/is_primary/sort_order) — 24-03 sets them on accept, 24-04's worker consumes them on ACTIVE"
  - "MediaAssetService: repoint (one-row CoW UPDATE, D-01), releaseAsset (physical MinIO delete ONLY at ref-count 0, IMG-01), findDedup (sha256 short-circuit)"
  - "StorageService key-addressed helpers: putBytes/getBytes/deleteByKey/urlForKey (single MinIO I/O owner; Content-Type = detected/produced type, never client header)"
  - "ProductService asset-first dual-read resolver (D-03a) wired at every toDto site, resolved outside the @Cacheable loader; flat image_url fallback"
  - "ProductMediaRepository.countByAssetId (ref-count) + repoint (CoW) + findPrimaryActiveObjectKey (dual-read); MediaAssetRepository.findByTenantIdAndSha256 (dedup)"
affects: [24-03 accept endpoint sets media_asset pending-placement columns, 24-04 worker consumes them + calls repoint on ACTIVE, 24-05 vendor delete paths call releaseAsset, secure-phase 24]

# Tech tracking
tech-stack:
  added: []   # no new libraries — schema + service + tests only (mirrors V52/V50/V47 precedent)
  patterns:
    - "V53 media_asset mirrors V52 shop_staff EXACTLY: ENABLE+FORCE RLS via current_tenant_id(), _aud nullable-tenant predicate, un-audited high-churn join"
    - "Per-tenant set_config backfill loop (V44 pattern) — a bare INSERT..SELECT migrates ZERO rows under FORCE RLS (trap_rls_migration_backfill)"
    - "Copy-on-write repoint = one-row UPDATE product_media SET asset_id; reference count = COUNT(product_media WHERE asset_id=?); physical delete only at 0"
    - "Asset-first dual-read resolver applied OUTSIDE the @Cacheable boundary so a PENDING->ACTIVE flip is never served stale"
    - "SQL-native sha256 backfill: encode(sha256(convert_to(key,'UTF8')),'hex') (PG built-in, no pgcrypto); content_type derived from the extension"

key-files:
  created:
    - core-java/src/main/resources/db/migration/V53__media_asset.sql
    - core-java/src/main/java/uk/jtoye/core/media/MediaAsset.java
    - core-java/src/main/java/uk/jtoye/core/media/ProductMedia.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetRepository.java
    - core-java/src/main/java/uk/jtoye/core/media/ProductMediaRepository.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaAssetRlsPolicyIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaCopyOnWriteIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaBackfillMigrationIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/storage/StorageService.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java

key-decisions:
  - "media_asset is @Audited (media_asset_aud mirror); product_media is un-audited (RESEARCH Open-Q2 — high-churn derived link)"
  - "Backfill computes sha256 of the object_key (not the raw bytes — unavailable in SQL) so dedup/ref-count are correct AND two products sharing a URL dedup to one asset via ON CONFLICT DO NOTHING"
  - "object_key parsed from the URL via position('<tenant>/' IN url) — environment-independent (does not need the configured publicUrl); defensive fallback stores the URL as-is for external URLs"
  - "Dual-read resolver lives in ProductService (post-mapping override), NOT ProductMapper — a MapStruct mapper must not do DB lookups; resolved OUTSIDE @Cacheable to avoid caching a stale flat URL"
  - "Added StorageService.urlForKey (beyond the plan's put/get/deleteByKey) because the dual-read resolver must turn a stored object_key back into a public URL"

patterns-established:
  - "V53 = the media CoW analog of V52 shop_staff — future media tables copy this RLS+_aud shape"
  - "MediaBackfillMigrationIntegrationTest = the V57GrantSourceBackfillIntegrationTest non-fresh-DB proof re-applied to V53 (NOSUPERUSER rls_migrator, two tenants, RED vs bare no-GUC UPDATE)"

requirements-completed: [IMG-01]

# Metrics
duration: ~35min
completed: 2026-07-23
---

# Phase 24 Plan 02: Copy-on-Write media_asset Model + Safe Dual-Read Summary

**The reference-counted, sha256-deduped, RLS-forced `media_asset` model + `product_media` join (D-01), with per-tenant `image_url` backfill and an asset-first dual-read resolver — IMG-01 delivered in full and proven over real Postgres under a NOSUPERUSER role-downgrade.**

## Performance

- **Duration:** ~35 min
- **Started:** 2026-07-23T17:44 (approx, local)
- **Completed:** 2026-07-23T18:00 (approx, local)
- **Tasks:** 3 (all `type="auto"`)
- **Files modified:** 13 (9 created, 4 modified)

## Accomplishments
- **V53 migration** mirrors V52 `shop_staff` exactly: `media_asset` (ref-counted, tenant-scoped ENABLE+FORCE RLS via the safe `current_tenant_id()` helper — never a raw `::uuid` cast; `uq_media_asset_tenant_sha` per-tenant dedup index; pending-placement `product_id`/`is_primary`/`sort_order` intent columns for the worker), the `product_media` join (D-01) carrying its own `tenant_id` for RLS with a partial-unique primary index + an `asset_id` ref-count index, and the `media_asset_aud` Envers mirror (nullable-tenant `_aud` predicate). `product_media` stays un-audited.
- **Per-tenant backfill** (V44 pattern) wraps every product's `image_url`→`is_primary` row and `additional_image_urls[]`→`sort_order` rows into `status='ACTIVE'` assets AS-IS without re-piping (D-03); sha256 computed in-SQL, content_type from the extension, seed images excluded (SPEC D1). A bare no-GUC backfill would migrate ZERO rows under FORCE RLS — the loop is load-bearing.
- **MediaAssetService** owns the three IMG-01 invariants: `repoint` (one-row CoW UPDATE), `releaseAsset` (physical MinIO delete ONLY at reference-count 0), `findDedup` (sha256 short-circuit). `StorageService` gains key-addressed `putBytes`/`getBytes`/`deleteByKey`/`urlForKey` so it stays the single MinIO I/O owner and always sets the DETECTED Content-Type.
- **Asset-first dual-read resolver** (D-03a) wired at every `ProductService.toDto` site (list/search/by-id), resolved outside the `@Cacheable` loader so a `PENDING→ACTIVE` flip is never cached; falls back to the flat `image_url` kept this phase.
- **Wave-0 IMG-01 proofs green over real Postgres:** RLS cross-tenant read+forge denied under the NOSUPERUSER downgrade; CoW repoint touches one row; delete only at ref-count 0 (with the `deleteByKey` call boundary verified); per-tenant dedup unique-index violation; non-fresh two-tenant backfill (RED vs a bare no-GUC UPDATE).

## Task Commits

Each task was committed atomically:

1. **Task 1: V53 migration + entities + repos** — `0e47df6` (feat)
2. **Task 2: MediaAssetService + key-addressed StorageService + dual-read resolver** — `e1147ea` (feat)
3. **Task 3: Wave-0 IMG-01 integration tests + RlsContract sentinel** — `f583291` (test)

**Plan metadata:** _this commit_ (docs: complete plan)

## Files Created/Modified
- `core-java/src/main/resources/db/migration/V53__media_asset.sql` — media_asset + product_media + media_asset_aud, RLS via current_tenant_id(), per-tenant backfill loop
- `core-java/src/main/java/uk/jtoye/core/media/MediaAsset.java` — @Entity @Audited, inline Status{PENDING,ACTIVE,FAILED}, pending-placement intent columns
- `core-java/src/main/java/uk/jtoye/core/media/ProductMedia.java` — @Entity join (un-audited), tenant_id carried for RLS
- `core-java/src/main/java/uk/jtoye/core/media/MediaAssetRepository.java` — findByTenantIdAndSha256 (dedup)
- `core-java/src/main/java/uk/jtoye/core/media/ProductMediaRepository.java` — countByAssetId (ref-count), repoint (CoW), findPrimaryActiveObjectKey (dual-read)
- `core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java` — repoint / releaseAsset / findDedup
- `core-java/src/main/java/uk/jtoye/core/storage/StorageService.java` — putBytes / getBytes / deleteByKey / urlForKey
- `core-java/src/main/java/uk/jtoye/core/product/ProductService.java` — resolveAssetFirst dual-read wired at all toDto sites
- `core-java/src/test/java/uk/jtoye/core/media/MediaAssetRlsPolicyIntegrationTest.java` — RLS under NOSUPERUSER (4 tests)
- `core-java/src/test/java/uk/jtoye/core/media/MediaCopyOnWriteIntegrationTest.java` — CoW / ref-count / dedup / dual-read (4 tests)
- `core-java/src/test/java/uk/jtoye/core/media/MediaBackfillMigrationIntegrationTest.java` — non-fresh two-tenant backfill (1 test)
- `core-java/src/test/java/uk/jtoye/core/product/ProductServiceTest.java` — dual-read unit proof + constructor wiring
- `core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java` — img01_mediaTablesAreForced sentinel

## Decisions Made
- **media_asset @Audited, product_media un-audited** (RESEARCH Open-Q2) — the join is a high-churn derived link.
- **Backfill sha256 = hash of the object_key** (raw bytes are in MinIO, unreachable from SQL). Two products sharing a URL dedup to one asset via `ON CONFLICT (tenant_id, sha256) DO NOTHING` + a re-`SELECT` of the asset id — the correct CoW/ref-count outcome.
- **object_key parsed via `position('<tenant>/' IN url)`** so the backfill is environment-independent (does not need the configured `publicUrl`), with a defensive whole-URL fallback for external URLs.
- **Dual-read resolver in ProductService, not ProductMapper** — a MapStruct mapper must not do DB lookups; resolved outside `@Cacheable` so a resolved derivative URL is never cached across an async status flip.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added `StorageService.urlForKey(String)` beyond the plan's put/get/deleteByKey**
- **Found during:** Task 2 (dual-read resolver)
- **Issue:** The resolver returns a public URL, but `media_asset` stores only the `object_key`. Without a key→URL helper the resolver could not produce a value comparable to the flat `image_url`.
- **Fix:** Added `public String urlForKey(String objectKey)` (`publicUrl + "/" + key`); the resolver maps the resolved key through it.
- **Files modified:** core-java/src/main/java/uk/jtoye/core/storage/StorageService.java
- **Verification:** MediaCopyOnWriteIntegrationTest dual-read + ProductServiceTest dual-read unit proof green.
- **Committed in:** `e1147ea` (Task 2 commit)

**2. [Rule 3 - Blocking] ProductService constructor gained a `ProductMediaRepository` dependency → ProductServiceTest updated**
- **Found during:** Task 2 (wiring the resolver)
- **Issue:** The resolver needs `findPrimaryActiveObjectKey`; ProductService is constructed manually in ProductServiceTest, so the new dependency broke that construction.
- **Fix:** Added the dependency + a `@Mock ProductMediaRepository` (Mockito returns `Optional.empty()` for unstubbed Optional methods, so every existing assertion keeps the flat fallback unchanged) + a dedicated dual-read unit test.
- **Files modified:** core-java/src/main/java/uk/jtoye/core/product/ProductService.java, core-java/src/test/java/uk/jtoye/core/product/ProductServiceTest.java
- **Verification:** `:core-java:test --tests ProductServiceTest` BUILD SUCCESSFUL.
- **Committed in:** `e1147ea` (Task 2 commit)

**3. [Rule 3 - Non-blocking] ProductMapper.java left unmodified (listed in the plan's Task 2 files)**
- **Found during:** Task 2
- **Issue:** The plan lists ProductMapper among Task 2 files, but the dual-read resolver must not live in a MapStruct mapper (no DB access in mappers).
- **Fix:** Implemented the resolver in ProductService as a post-mapping override; the mapper's direct `imageUrl` mapping is preserved and overridden only when an ACTIVE derivative exists. No ProductMapper change was needed.
- **Files modified:** (none)
- **Verification:** dual-read proven at both integration (repository query) and unit (ProductService wiring) level.
- **Committed in:** n/a

---

**Total deviations:** 3 (2 blocking, 1 non-blocking / no-change). **Impact on plan:** all necessary for a correct dual-read; no scope creep — the CoW/RLS/backfill core is exactly as specified.

## Issues Encountered
- **Gradle wrapper at repo root, not `core-java/`** (same as 24-01): the plan's `<verify>` uses `cd core-java && ./gradlew …`; ran all gradle commands from the repo root as `./gradlew :core-java:…`. No functional impact.
- **Integration-test teardown log noise:** `SimpleMessageListenerContainer - Failed to check/redeclare auto-delete queue(s)` + `Connection refused` on container shutdown between classes are the known dead-port-broker / per-class-container teardown noise (memory: fleet-supervision) — not test failures; all four suites reported 0 failures / 0 errors.

## Known Stubs
None — every code path is implemented and proven by tests. The flat `image_url`/`additional_image_urls[]` columns are deliberately KEPT this phase (dual-read window, D-03a) and dropped in a later phase; this is a documented design decision, not a stub.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- **IMG-01 complete + marked** in REQUIREMENTS.md. 24-03 (accept endpoint) sets `media_asset.product_id`/`is_primary`/`sort_order` at accept time; 24-04's worker consumes them and calls `MediaAssetService.repoint` on ACTIVE; 24-05 wires `ProductService.removeImage`/`removeAdditionalImage` to `MediaAssetService.releaseAsset` (they consume this plan's `releaseAsset` + `countByAssetId`).
- **REBUILD-ALL note carried from 24-01:** the core-java image still must be rebuilt (`apk add libwebp-tools` + the `-D` flag) before any compose/E2E — unchanged by this plan (no bytes flow through cwebp here; this plan is schema + service + dual-read only).
- **Dual-read window is live:** storefront + dashboard product reads resolve asset-first with a flat fallback, so 24-03/24-04 can start producing ACTIVE derivatives without a read-path change.

## Self-Check: PASSED

- All 9 created files present on disk (V53 migration, MediaAsset/ProductMedia entities, both repos, MediaAssetService, 3 media tests).
- All 3 task commits present in git: `0e47df6`, `e1147ea`, `f583291`.
- `:core-java:compileJava`/`compileTestJava` clean; `:core-java:integrationTest` — MediaAssetRlsPolicyIntegrationTest 4/4, MediaCopyOnWriteIntegrationTest 4/4, MediaBackfillMigrationIntegrationTest 1/1, RlsContractTest 5/5 (all 0 failures / 0 errors); ProductServiceTest unit suite green (incl. RLS-under-NOSUPERUSER proof).

---
*Phase: 24-image-architecture-cow-assets-safe-upload-pipeline*
*Completed: 2026-07-23*
