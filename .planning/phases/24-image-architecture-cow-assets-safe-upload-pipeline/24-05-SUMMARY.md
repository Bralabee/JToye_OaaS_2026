---
phase: 24-image-architecture-cow-assets-safe-upload-pipeline
plan: 05
subsystem: media
tags: [img-01, img-03, img-04, review-queue, keep-dismiss-flag, media-asset-dto, dual-read, ref-count-delete, rls, testcontainers, rfc7807]

# Dependency graph
requires:
  - phase: 24-image-architecture-cow-assets-safe-upload-pipeline (plan 24-02)
    provides: media_asset model + MediaAssetService.releaseAsset/countByAssetId + ProductMediaRepository join finders + asset-first dual-read resolver + StorageService.urlForKey/deleteByKey
  - phase: 24-image-architecture-cow-assets-safe-upload-pipeline (plan 24-04)
    provides: worker drives PENDING->ACTIVE/FAILED + flagged sub-state; the status/flagged/failure_reason field semantics + the (FAILED OR flagged-ACTIVE) review-queue selection this plan reads
provides:
  - "MediaController (/api/v1/media, hard-mounted): GET /review-queue (authenticated read) + POST /{assetId}/keep (SCOPE_catalog:write) — tenant-scoped, RFC 7807"
  - "MediaAssetStatus (public API enum) + MediaAssetDto (assetId/status/flagged/failureReason/url/thumbnailUrl/width/height) — the IMG-04 data contract 24-06 renders"
  - "MediaAssetService.reviewQueue() (FAILED OR flagged-ACTIVE selection) + dismissFlag() (Keep: clears flag, stays ACTIVE, cross-tenant 404) + mediaForProduct() (per-product asset list)"
  - "ProductDto.media: List<MediaAssetDto> (primary-first + gallery), asset-first, populated by ProductService (not the MapStruct mapper); flat imageUrl/additionalImageUrls retained (dual-read D-03a)"
  - "ProductService.removeImage/removeAdditionalImage wired to product_media removal + MediaAssetService.releaseAsset — IMG-01 physical-delete-only-at-ref-count-0 honoured at the vendor delete surface"
affects: [24-06 frontend consumes MediaAssetDto + the /api/v1/media endpoints; secure-phase 24]

# Tech tracking
tech-stack:
  added: []   # no new libraries — controller + DTO + service methods + tests over the 24-02/24-04 model
  patterns:
    - "Hard-mounted /api/v1/media controller (RefundController/WebhookSubscriptionController/MediaUploadController idiom — media pkg not in WebConfig.API_V1_PACKAGES, no WebConfig edit)"
    - "MediaAssetDto.from is a pure DB-free mapping (URLs resolved by the service and passed in) so it is unit-testable — the 24-02 convention that DB/storage access never lives in the mapping layer"
    - "Review-queue tenant-isolation proven via the NOSUPERUSER SET LOCAL ROLE downgrade + the TenantSetLocalAspect GUC pin re-applied before every repo/JDBC op (same path production uses)"
    - "Detail-only DTO enrichment (resolveDetail on by-id/create/update/add-image) — NOT on list/search, avoiding a per-row media N+1 (web-perf contract)"
    - "Positional gallery-row resolution (Nth non-primary product_media by sort_order = Nth flat-array entry) — robust to 0- vs 1-based ordinality"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/media/MediaController.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetDto.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetStatus.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaReviewQueueIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaAssetDtoMappingTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductImageDeleteIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetRepository.java
    - core-java/src/main/java/uk/jtoye/core/media/ProductMediaRepository.java
    - core-java/src/main/java/uk/jtoye/core/product/dto/ProductDto.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductMapper.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductServiceTest.java

key-decisions:
  - "GET /review-queue is authenticated-only (NO catalog:read scope gate) — mirrors ProductController reads EXACTLY: a scopeless/legacy vendor token still reads (ScopedCatalogAccessIntegrationTest). A catalog:read gate would have 403'd real vendor dashboard tokens that carry no scope claim (Incremental Betterment — don't break existing vendor read access). POST /keep gates on SCOPE_catalog:write like every ProductController mutation."
  - "MediaAssetStatus is a distinct public enum (not the MediaAsset.Status entity enum) so the wire contract is decoupled from persistence; mapped name-identically via valueOf(name())"
  - "ProductDto.media populated by ProductService (resolveDetail), NOT ProductMapper — a MapStruct mapper must not do DB lookups (24-02 convention); the mapper carries @Mapping(target=media, ignore=true) so the enrichment is a deliberate, service-owned seam not an accidental null"
  - "media enrichment is applied only on single-DTO sites (by-id/create/update/add-image), NOT list/search — a per-row product_media query on lists would be an N+1 the web-perf contract forbids (grid cards need only the primary imageUrl, already resolved by resolveAssetFirst)"
  - "Keep clears only flagged (status untouched) — the vendor keeps the flagged image live; a FAILED asset is not affected (its flag is already false)"
  - "Gallery delete resolves the join row positionally over sort_order (not by assuming sort_order == index) — robust to the V53 1-based ORDINALITY backfill and to drift; if no row exists at the index (a gallery image added via the still-flat path), the flat cleanup alone runs"

patterns-established:
  - "MediaReviewQueueIntegrationTest = the service-level RLS proof pattern: class-@Transactional + SET LOCAL ROLE rls_test_role + the TenantSetLocalAspect GUC pin lets a @Service call fire under the caller's tenant GUC as a NOSUPERUSER role (strong isolation proof through the service, not just raw JDBC)"
  - "ProductImageDeleteIntegrationTest = the delete-surface ref-count proof: @SpyBean StorageService (assert deleteByKey without live MinIO) + @MockBean ShopAccessService (gate no-op) over real Postgres"

requirements-completed: [IMG-01, IMG-03]   # IMG-04 held (backend data contract delivered here; its UI + Jest specs are 24-06 — anti-false-green, mirrors 24-04 holding IMG-03)

# Metrics
duration: ~40min
completed: 2026-07-23
---

# Phase 24 Plan 05: Vendor Review/Rejection Queue Backend + IMG-04 DTO Contract + IMG-01 Delete Surface Summary

**The vendor-facing review/rejection surface at the backend: a tenant-scoped `GET /api/v1/media/review-queue` (FAILED + flagged-ACTIVE), a `POST /{assetId}/keep` that dismisses a content flag, the product/media DTO enriched with `MediaAssetStatus`/`flagged`/`failureReason` (the seam 24-06 renders), and the vendor image-delete paths rewired to `product_media` removal + ref-counted `releaseAsset` — IMG-03 closed, IMG-01's delete surface closed, and the IMG-04 data contract delivered, all proven over real Postgres.**

## Performance
- **Duration:** ~40 min
- **Tasks:** 3 (all `type="auto"`)
- **Files:** 13 (6 created, 7 modified)

## Accomplishments
- **`MediaController` (hard-mounted `/api/v1/media`)** — `GET /review-queue` returns the tenant's `List<MediaAssetDto>` needing attention (FAILED + flagged-ACTIVE), authenticated-only exactly like the `ProductController` reads (so a scopeless vendor token still sees its queue); `POST /{assetId}/keep` (`SCOPE_catalog:write`) dismisses a content flag. Both carry full `@Operation`/`@ApiResponses` (RFC 7807 404/403). No Replace endpoint — Replace is a re-upload through the 24-03 accept.
- **`MediaAssetStatus` + `MediaAssetDto`** — the IMG-04 data contract: `{ assetId, status (PENDING|ACTIVE|FAILED), flagged, failureReason, url, thumbnailUrl, width, height }`. `MediaAssetDto.from` is a pure, DB-free mapping (the service resolves derivative/thumbnail URLs and passes them in) so it is unit-testable; URLs are populated only for ACTIVE assets (a PENDING/FAILED asset has no servable object).
- **`MediaAssetService`** — `reviewQueue()` (the `status='FAILED' OR (status='ACTIVE' AND flagged=true)` selection, newest first, RLS-scoped), `dismissFlag()` (Keep: clears `flagged`, leaves `status=ACTIVE`, 404 on a cross-tenant `assetId` via RLS-hidden `findById`), and `mediaForProduct()` (the per-product asset list, primary-first then gallery, backing the DTO enrichment). `MediaAssetRepository.findReviewQueue()` + `ProductMediaRepository.findAssetsForProduct()` back these.
- **Product DTO enrichment (IMG-04 seam)** — `ProductDto.media: List<MediaAssetDto>` carries `status`/`flagged`/`failureReason` per entry so the 24-06 UI can render PENDING→processing, FAILED→reason, ACTIVE→derivative, flagged→needs-review. Populated by `ProductService.resolveDetail` (asset-first) at the single-DTO sites (by-id/create/update/add-image) — deliberately NOT on list/search (a per-row media query would be an N+1). The flat `imageUrl`/`additionalImageUrls` are retained for the dual-read window (D-03a). `ProductMapper.toDto` carries `@Mapping(target="media", ignore=true)` — the enrichment is a service-owned seam, never a MapStruct DB lookup.
- **IMG-01 delete surface closed** — `ProductService.removeImage` drops the `is_primary` `product_media` row and calls `mediaAssetService.releaseAsset` (physical MinIO delete + `media_asset` removal ONLY at ref-count 0; a still-referenced shared asset is preserved) before the retained flat `image_url` cleanup. `removeAdditionalImage` does the same for the positionally-resolved gallery row. A vendor deletion no longer orphans the join row + asset.
- **Tests (10 across 3 classes, all green over real Postgres):** `MediaReviewQueueIntegrationTest` 3/3 (FAILED+flagged only / tenant-isolated under NOSUPERUSER RLS / Keep drops out + stays ACTIVE / cross-tenant Keep 404), `MediaAssetDtoMappingTest` 6/6 (PENDING/ACTIVE/FAILED/flagged surface + product DTO carries media AND retains flat dual-read fields + flat-only fallback), `ProductImageDeleteIntegrationTest` 3/3 (last-reference release physically deletes / still-referenced preserved / gallery release hits the correct row leaving others + the flat array intact).

## Incremental Betterment — the delete-surface rewire preserved capability
The displaced behavior at `removeImage`/`removeAdditionalImage` was: delete the flat object + null the flat column, so the image disappears from the product. That behavior is **preserved** (the flat `storageService.delete` + `setImageUrl(null)` / array removal are retained for the dual-read window) and **augmented** with ref-count safety: the asset-model join row is dropped and the asset released only at ref-count 0, so a shared asset another product still references is **never** physically deleted (`ProductImageDeleteIntegrationTest#deletingWhileStillReferencedDoesNotDeleteAsset` proves it, RED-provable against the pre-fix flat-only path). Vendor delete still works end-to-end; it is now also orphan-safe.

## Task Commits
1. **Task 1: MediaController review-queue GET + Keep POST + MediaAssetDto** — `674d008` (feat)
2. **Task 2: Expose MediaAssetStatus on the product/media DTOs (asset-first media list)** — `ae266bc` (feat)
3. **Task 3: Wire vendor image-delete to product_media + releaseAsset (IMG-01 delete surface)** — `2b8d69b` (feat)

**Plan metadata:** _this commit_ (docs: complete plan)

## Data contract 24-06 consumes (record)
- **Endpoints:** `GET /api/v1/media/review-queue` → `MediaAssetDto[]`; `POST /api/v1/media/{assetId}/keep` → `MediaAssetDto` (the dismissed asset).
- **`MediaAssetDto`** = `{ assetId: UUID, status: "PENDING"|"ACTIVE"|"FAILED", flagged: boolean, failureReason: string|null, url: string|null, thumbnailUrl: string|null, width: int|null, height: int|null }`. `url`/`thumbnailUrl` are populated only for ACTIVE; `failureReason` only for FAILED.
- **`ProductDto.media`** = `MediaAssetDto[]` (primary-first then gallery); present on by-id/create/update/add-image responses, absent (null) on list/search. Flat `imageUrl`/`additionalImageUrls` are still present (dual-read).
- **Replace** = re-upload through `POST /api/v1/products/{id}/image` (24-03 accept), NOT a media endpoint.

## Decisions Made
- **`GET /review-queue` is authenticated-only, no `catalog:read` gate** — mirrors `ProductController` reads exactly; a `catalog:read` gate would 403 scopeless/legacy vendor tokens that still read products today (`ScopedCatalogAccessIntegrationTest` AC-1a). `POST /keep` gates on `SCOPE_catalog:write` like every product mutation.
- **`MediaAssetStatus` is a distinct public enum** decoupling the wire contract from the `MediaAsset.Status` entity enum (name-identical, `valueOf(name())`).
- **`ProductDto.media` populated in `ProductService`, not `ProductMapper`** (no DB lookups in a MapStruct mapper — 24-02 convention); mapper `@Mapping(ignore=true)` makes it a deliberate seam.
- **Enrichment is detail-only (not list/search)** to avoid a per-row `product_media` N+1 (web-perf contract) — grid cards need only the primary `imageUrl`, already resolved.
- **Gallery delete resolves the join row positionally over `sort_order`** (robust to the V53 1-based ORDINALITY and to drift), not by assuming `sort_order == index`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] `ProductService` gained a `MediaAssetService` dependency → `ProductServiceTest` updated**
- **Found during:** Task 2 (media enrichment) + Task 3 (delete-surface release)
- **Issue:** Populating `ProductDto.media` and ref-count-releasing on delete both require `MediaAssetService`, which `ProductService` did not inject. `ProductServiceTest` constructs `ProductService` manually, so the new constructor param broke that construction.
- **Fix:** Added the `MediaAssetService` constructor dependency (no cycle — `MediaAssetService` does not depend on `ProductService`) + a `@Mock MediaAssetService` in `ProductServiceTest` (unstubbed → `mediaForProduct` returns null under Mockito, so every existing assertion is unchanged; the enrichment is proven separately).
- **Files:** ProductService.java, ProductServiceTest.java
- **Commits:** `ae266bc` (Task 2), `2b8d69b` (Task 3)

**2. [Rule 3 - Blocking] Added a public `MediaAssetStatus` enum (beyond the plan's named files)**
- **Found during:** Task 1
- **Issue:** `MediaAssetDto` needs a status type; the plan calls for a "`MediaAssetStatus` enum/string union matching the entity Status".
- **Fix:** Created `MediaAssetStatus` (PENDING|ACTIVE|FAILED) as a standalone public enum so the DTO/API contract is decoupled from the entity's nested `Status`.
- **Files:** MediaAssetStatus.java (new)
- **Commit:** `674d008`

**3. [Rule 2 - Correctness] Added `ProductMediaRepository.findAssetsForProduct` + `MediaAssetService.mediaForProduct`**
- **Found during:** Task 2
- **Issue:** The plan lists `ProductMapper.java` for Task 2, but the media list requires a `product_media`→`media_asset` join lookup that must NOT live in a MapStruct mapper (24-02 convention). Without a query + service method the `media` field would be a stub (always null).
- **Fix:** Added the join query (primary-first then `sort_order`) + a service method mapping each asset to a `MediaAssetDto` (URL-resolving ACTIVE entries); `ProductService.resolveDetail` wires it into the single-DTO responses. The mapper only gains `@Mapping(ignore=true)`.
- **Files:** ProductMediaRepository.java, MediaAssetService.java, ProductService.java, ProductMapper.java
- **Commit:** `ae266bc`

**Total deviations:** 3 (2 Rule 3, 1 Rule 2) — all necessary to deliver a non-stub media contract + a correct injected delete-release. No architectural change (Rule 4 not triggered); no scope creep.

## Threat Model Coverage
- **T-24-19 (cross-tenant review-queue read)** — mitigated: `findReviewQueue()` is RLS-scoped via `current_tenant_id()`; `MediaReviewQueueIntegrationTest#listsFailedAndFlaggedOnly` asserts tenant B's flagged row is invisible under the NOSUPERUSER downgrade.
- **T-24-20 (cross-tenant flag-dismiss)** — mitigated: `dismissFlag` is tenant-scoped; a foreign `assetId` is RLS-hidden → `findById` empty → `ResourceNotFoundException` → 404 (no oracle). `#keepOnCrossTenantAssetIs404` proves it.
- **T-24-21 (failure_reason leak)** — accepted: `failureReason` is the vendor-facing worker message (e.g. "unsupported image format"), tenant-scoped, surfaced only to the owner.
- **T-24-22 (unauthenticated queue/keep)** — mitigated: `anyRequest().authenticated()` covers the GET; `@PreAuthorize("hasAuthority('SCOPE_catalog:write')")` gates the keep, mirroring ProductController.
- **T-24-26 (cross-tenant / orphaning image delete)** — mitigated: `removeImage`/`removeAdditionalImage` keep the `shopAccessService.require(SHOP_MANAGER)` gate + RLS scope; `releaseAsset` runs in the tenant GUC and deletes a `media_asset` ONLY at ref-count 0 (never a shared asset). `ProductImageDeleteIntegrationTest` proves both the release-at-0 and preserve-when-shared paths.

## Known Stubs
None — every path is implemented and test-proven. `ProductDto.media` is populated by `ProductService.resolveDetail` at every single-DTO site (not a null stub); it is deliberately absent on list/search (an N+1-avoiding design decision, not a stub) where the flat `imageUrl` still renders the grid card.

## Deferred / Known-Red (by design, not this plan's failure)
- **`OpenApiSnapshotTest` is RED — expected contract drift, deferred to the 24-06 phase gate.** The regenerated `/v3/api-docs` differs from the committed snapshot by EXACTLY: (a) the two NEW paths `/api/v1/media/review-queue` + `/api/v1/media/{assetId}/keep` (this plan), (b) the `/api/v1/products/{id}/image` 202 change (24-03), and (c) the `ProductDto` schema gaining `media` (this plan). A jq path-diff confirms **no other** added/removed/changed paths. The snapshot regen (`./gradlew :core-java:updateOpenApiSnapshot`) + `docs/metrics.json` count reconcile are 24-06 phase-gate tasks (Phase 22/23 convention). This is the ONLY red check and is not a real failure.
- **IMG-04 stays PENDING (anti-false-green)** — the backend data contract (`MediaAssetDto` on the review queue + product DTO) is delivered + green here, but IMG-04's full acceptance is the 24-06 frontend (status-aware `AssetImage`, the review-queue screen, the Jest processing/failed/flagged specs). Mirrors 24-04 holding IMG-03 until its vendor-visible half existed. IMG-04 closes in 24-06.
- **Live E2E** (a real PENDING→ACTIVE→review-queue→Keep flow in the running Compose stack at 375px) is the standard `/gsd:verify-work` manual step (needs MinIO + the RabbitMQ worker + Keycloak vendor login) — per 24-VALIDATION Manual-Only. REBUILD-ALL (24-01 `apk add libwebp-tools`) still applies before any Compose/E2E.

## Self-Check: PASSED
- All 6 created files present on disk (MediaController, MediaAssetDto, MediaAssetStatus + 3 test classes) and all 7 modified files present.
- All 3 task commits present in git: `674d008`, `ae266bc`, `2b8d69b`.
- `:core-java:compileJava`/`compileTestJava` clean. New suites green over real Postgres: `MediaReviewQueueIntegrationTest` 3/3, `ProductImageDeleteIntegrationTest` 3/3, `MediaAssetDtoMappingTest` 6/6 (unit). Full `:core-java:test` unit suite BUILD SUCCESSFUL; `:core-java:integrationTest` for `uk.jtoye.core.media.*` + `uk.jtoye.core.product.*` BUILD SUCCESSFUL (no regressions — the RabbitMQ/Hikari teardown WARN/ERROR logs are the known per-class-container shutdown noise, 0 test failures).
- **ONLY** expected red: `OpenApiSnapshotTest` (contract drift = the two new media endpoints + the ProductDto.media schema + the 24-03 202 change), deferred to the 24-06 phase gate per plan critical-notes. Not counted as a failure.

---
*Phase: 24-image-architecture-cow-assets-safe-upload-pipeline*
*Completed: 2026-07-23*
