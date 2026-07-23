---
phase: 24-image-architecture-cow-assets-safe-upload-pipeline
plan: 04
subsystem: media
tags: [img-02, img-03, async-worker, rabbitlistener, tenant-guc-pin, copy-on-write, webp, gate-strictness, reaper, pipeline-unification, testcontainers]

# Dependency graph
requires:
  - phase: 24-image-architecture-cow-assets-safe-upload-pipeline (plan 24-01)
    provides: MediaNormalizer.normalize(byte[]) (sniff/bomb-guard/decode-verify/EXIF-strip/WebP) + MediaProperties budget + Vision advisory config
  - phase: 24-image-architecture-cow-assets-safe-upload-pipeline (plan 24-02)
    provides: media_asset model (pending-placement cols) + MediaAssetService.repoint/releaseAsset + ProductMediaRepository.countByAssetId + StorageService.getBytes/putBytes/deleteByKey
  - phase: 24-image-architecture-cow-assets-safe-upload-pipeline (plan 24-03)
    provides: media.events topology (MEDIA_EVENTS_QUEUE) + MediaProcessingEvent{tenantId,assetId} + MediaAssetService.acceptQuarantineAndQueue + MediaEventOutboxFlusher publish
provides:
  - "MediaProcessingWorker: @RabbitListener(media.process) GUC-pinned pipeline — read quarantine -> MediaNormalizer -> store ONLY the WebP derivative+thumbnail -> ACTIVE -> delete raw; idempotent skip when the re-read asset is not PENDING"
  - "Gate strictness (IMG-03 D3): decode/bomb/allowlist/encode failure -> FAILED + vendor-visible failure_reason (never a served derivative, never a repoint); low content-relevance -> ACTIVE + flagged=true (review queue), never rejected; vision advisory-gated OFF by default"
  - "CoW-on-success placement (D-04a): attach/repoint the product_media slot ONLY on ACTIVE, releasing the displaced asset at ref-count 0 — a FAILED replacement never clobbers the live image"
  - "MediaPendingReaper: @Scheduled per-tenant GUC-pinned sweep flipping crashed-worker PENDING orphans (> jtoye.media.reaper-grace-ms) to FAILED + quarantine cleanup"
  - "BulkImportService.importFromImages unified onto the ONE pipeline (acceptQuarantineAndQueue, primary placement) — the legacy synchronous storageService.upload image route removed"
  - "MediaAssetService.attachPlacement (create first product_media slot on ACTIVE) + ProductMediaRepository slot finders + MediaAssetRepository.findStalePending + MediaProperties.reaperGraceMs"
affects: [24-05 review-queue backend reads status/flagged/failure_reason semantics + the (FAILED OR flagged) selection query, 24-06 frontend surfaces PENDING/ACTIVE/FAILED/flagged, secure-phase 24]

# Tech tracking
tech-stack:
  added: []   # no new libraries — reuses 24-01 MediaNormalizer/Scrimage-cwebp + 24-02/24-03 model/topology
  patterns:
    - "Worker tenant-GUC pin (OrderStateChangeListener idiom): TenantContext.set + set_config('app.current_tenant_id', ?, true) BEFORE any RLS-bound read, finally clear — the @Async-tenant landmine (T-24-17)"
    - "Idempotent redelivery via DB source-of-truth: re-read asset by id, skip if status != PENDING (no processed_* table)"
    - "CoW-on-success ordering is load-bearing (D-04a): mint+repoint happen ONLY after the worker succeeds, so a FAILED replacement leaves the live image untouched"
    - "Advisory vision gate: jtoye.media.vision.enabled (default OFF) AND imageAnalysisService.isEnabled(); below minConfidence FLAGS not rejects; any provider error leaves ACTIVE unflagged"
    - "Persist ACTIVE via saveAndFlush BEFORE the @Modifying(clearAutomatically) repoint, or the dirty status update is discarded by the context clear"
    - "Per-tenant TransactionTemplate + GUC-pin reaper (WebhookRetentionCleanup clone) — a bare query sees ZERO rows under FORCE RLS"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/media/MediaProcessingWorker.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaPendingReaper.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaProcessingWorkerIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/media/GateStrictnessTest.java
    - core-java/src/test/java/uk/jtoye/core/media/CowSafetyIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaPendingReaperTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/media/ProductMediaRepository.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetRepository.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaProperties.java
    - core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java
    - core-java/src/test/java/uk/jtoye/core/product/BulkImportServiceTest.java

key-decisions:
  - "CoW placement logic orchestrated in the worker (repoint/releaseAsset via MediaAssetService, attachPlacement for the first-slot case) — keeps the D-04a mint-on-success ordering explicit at the pipeline seam"
  - "FAILED path ALSO deletes the quarantine object (raw is terminally unneeded, no auto-retry this phase) — no orphan the reaper would miss (the reaper only targets PENDING)"
  - "Vision gate requires BOTH jtoye.media.vision.enabled AND imageAnalysisService.isEnabled() — the advisory flag defaults OFF, so vision never even consults the provider by default"
  - "Bulk image STORAGE unified onto the pipeline; the AI product-suggestion analysis is PRESERVED (Incremental Betterment) — only the second synchronous upload route is removed"
  - "Reaper cutoff is a NEW config jtoye.media.reaper-grace-ms (default 15m), distinct from reaper-interval-ms (sweep cadence) — a legitimately in-flight upload is never reaped"

patterns-established:
  - "MediaProcessingWorker = the media analog of OrderStateChangeListener: a GUC-pinned @RabbitListener whose first act is the tenant pin; future off-request media consumers copy it"
  - "CowSafetyIntegrationTest#failedReplacementDoesNotClobber = the durable D-04a regression guard (RED-provable against a naive repoint-on-accept)"

requirements-completed: [IMG-02]   # IMG-03 held PENDING (anti-false-green) — 24-05 review-queue backend still contributes its GET queue + Keep

# Metrics
duration: ~55min
completed: 2026-07-23
---

# Phase 24 Plan 04: Async Worker (GUC-Pinned Pipeline) + Gate Strictness + CoW Safety + Reaper + Bulk Unification Summary

**The async worker half of the safe pipeline: a tenant-GUC-pinned `@RabbitListener` that transforms quarantined raw bytes into a stored WebP derivative (never the raw), drives `PENDING -> ACTIVE/FAILED(+flagged)`, repoints the `product_media` slot ONLY on success (so a FAILED replacement never clobbers the live image — D-04a), reaps crashed-worker orphans, and collapses `BulkImportService` onto the ONE pipeline — IMG-02 completed end-to-end + IMG-03 gate strictness proven over real Postgres + cwebp.**

## Performance
- **Duration:** ~55 min
- **Tasks:** 3 (all `type="auto"`)
- **Files:** 12 (6 created, 6 modified)

## Accomplishments
- **`MediaProcessingWorker`** — a competing-consumer `@RabbitListener(media.process)` that pins the tenant GUC FIRST (the `OrderStateChangeListener` idiom: `TenantContext.set` + `set_config('app.current_tenant_id', ?, true)`) so RLS does not hide the PENDING row off the request thread (T-24-17), re-reads the asset and skips if it is no longer PENDING (idempotent redelivery, DB source-of-truth), then runs the pipeline: read quarantine → `MediaNormalizer` (magic-byte sniff + decompression-bomb header guard + decode-verify + EXIF-strip + resize + WebP) → store **ONLY** the WebP derivative + thumbnail (Content-Type from the PRODUCED type) → `ACTIVE` → delete the raw quarantine object → CoW-on-success placement. `finally { TenantContext.clear() }`.
- **Gate strictness (IMG-03, SPEC D3)** — a decode / bomb / allowlist / encode failure is a hard veto: `FAILED` + a vendor-visible `failure_reason`, no derivative served, no repoint. A below-threshold content-relevance FLAGS the ACTIVE asset for the review queue — **never** a reject. The vision stage is advisory-gated OFF by default (`jtoye.media.vision.enabled` AND `ImageAnalysisService.isEnabled()`); a provider error leaves the asset ACTIVE and unflagged.
- **CoW-on-success (D-04a)** — placement (`attachPlacement` for a first slot, or `repoint` + `releaseAsset` for a replacement) runs ONLY once the asset is ACTIVE. `CowSafetyIntegrationTest` proves a FAILED replacement leaves the product_media slot pointing at the ORIGINAL ACTIVE asset (live image untouched), and a SUCCESSFUL replacement repoints the slot + releases the displaced asset at ref-count 0.
- **`MediaPendingReaper`** — a `@Scheduled` per-tenant `TransactionTemplate` + GUC-pinned sweep (clone of `WebhookRetentionCleanup`) that flips crashed-worker PENDING orphans older than `jtoye.media.reaper-grace-ms` (default 15m) to `FAILED` + deletes their quarantine object.
- **BulkImportService unification** — `importFromImages` now routes each image through the SAME `acceptQuarantineAndQueue` pipeline (quarantine + PENDING + outbox, primary placement) as a single upload; the legacy synchronous `storageService.upload` image route is removed (`grep -c 'storageService.upload(' == 0`). The AI product-suggestion analysis is preserved (Incremental Betterment — only the storage path is unified).
- **Tests (11 new across 4 classes, all green):** `MediaProcessingWorkerIntegrationTest` 4/4 (GUC pin proven under a NOSUPERUSER role downgrade; WebP-derivative-stored/raw-deleted; magic-byte `.jpg`-that-is-PDF veto; idempotent redelivery), `GateStrictnessTest` 3/3 (FAILED veto / low-confidence ACTIVE+flagged / advisory-off), `CowSafetyIntegrationTest` 2/2 (failed-replacement-no-clobber + successful-replacement-repoints-and-releases), `MediaPendingReaperTest` 2/2 (stale→FAILED+quarantine-deleted / fresh untouched via the grace cutoff), plus `BulkImportServiceTest` extended (reroute proven — `acceptQuarantineAndQueue` called, `upload` never; primary placement + raw bytes captured).

## FAILED / flagged field semantics (24-05 review backend reads this)
- **`media_asset.status`** — `PENDING` (queued) → `ACTIVE` (normalized, servable WebP) **or** `FAILED` (rejected). The worker is the sole writer of the terminal transition; the reaper is the sole writer of the timeout `FAILED`.
- **`media_asset.flagged`** (`boolean NOT NULL DEFAULT false`) — set `true` **only** on an `ACTIVE` asset when the advisory vision confidence `< jtoye.media.vision.min-confidence` (default 0.35). Never `true` on a `FAILED` asset. Vendor action: Keep (dismiss the flag) or Replace (D-04).
- **`media_asset.failure_reason`** (`TEXT`, ≤500 chars) — set **only** on `FAILED` (vendor-visible rejection reason); `null` otherwise.
- **Review-queue selection query (24-05):** tenant-scoped by RLS, then
  `status = 'FAILED' OR (status = 'ACTIVE' AND flagged = true)` — FAILED rows offer re-upload, flagged rows offer Keep/Replace.

## Task Commits
1. **Task 1: GUC-pinned MediaProcessingWorker + CoW-on-success + advisory vision** — `002d3cc` (feat)
2. **Task 2: MediaPendingReaper + BulkImportService one-pipeline unification** — `af2bd98` (feat)
3. **Task 3: IMG-03 gate-strictness + D-04a CoW-safety Wave-0 tests** — `b468f62` (test)

**Plan metadata:** _this commit_ (docs: complete plan)

## Decisions Made
- **CoW placement orchestrated at the worker seam** — `mediaAssetService.repoint` + `releaseAsset` for a replacement, `attachPlacement` for a first slot — keeping the D-04a mint-on-success ordering explicit where the pipeline succeeds.
- **FAILED also deletes the quarantine object** — the raw is terminally unneeded (re-upload-only, D-04), and the reaper only targets PENDING, so deleting on FAILED avoids an orphan the reaper would never sweep.
- **Vision gated on BOTH the advisory flag AND provider availability** — default OFF means the worker never even consults Ollama unless a tenant opts in.
- **Bulk STORAGE unified; AI suggestions preserved** — Incremental Betterment: the bulk image now flows through the one safe pipeline (async processing → ACTIVE), while the AI product-suggestion analysis that creates the draft product is unchanged.
- **New `jtoye.media.reaper-grace-ms` (15m) distinct from the sweep cadence** — the age threshold before a PENDING is an orphan, so an in-flight upload is never reaped.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added `MediaProperties.reaperGraceMs` + `MediaAssetRepository.findStalePending`**
- **Found during:** Task 2 (reaper)
- **Issue:** The reaper needs an age cutoff to select orphaned PENDING rows; 24-01 added only `reaperIntervalMs` (the sweep cadence), and there was no stale-PENDING query.
- **Fix:** Added `jtoye.media.reaper-grace-ms` (default 900_000ms) + `MediaAssetRepository.findStalePending(cutoff)` (`status = PENDING AND createdAt < cutoff`).
- **Files:** MediaProperties.java, MediaAssetRepository.java
- **Commit:** `af2bd98`

**2. [Rule 3 - Blocking] Added `ProductMediaRepository` slot finders + `MediaAssetService.attachPlacement`**
- **Found during:** Task 1 (CoW-on-success placement)
- **Issue:** The worker must place the ACTIVE asset — either create the product's first `product_media` row or repoint an existing slot. 24-02 provided `repoint`/`releaseAsset`/`countByAssetId` but no slot lookup and no create-row path.
- **Fix:** `findByProductIdAndPrimaryTrue` + `findFirstByProductIdAndPrimaryFalseAndSortOrder` (slot resolution) + `MediaAssetService.attachPlacement` (first-slot insert).
- **Files:** ProductMediaRepository.java, MediaAssetService.java
- **Commit:** `002d3cc`

**3. [Rule 1 - Correctness] `saveAndFlush(asset)` before the CoW repoint**
- **Found during:** Task 1
- **Issue:** `ProductMediaRepository.repoint` is `@Modifying(clearAutomatically = true)` — it clears the persistence context. Setting the asset ACTIVE (dirty entity) and THEN repointing would discard the un-flushed ACTIVE update.
- **Fix:** the worker `saveAndFlush`es the ACTIVE (+ maybe flagged) row before calling `placeOnActive`.
- **Files:** MediaProcessingWorker.java
- **Commit:** `002d3cc`

**4. [Rule 3 - Blocking] `BulkImportServiceTest` image tests updated for the reroute**
- **Found during:** Task 2
- **Issue:** The two existing image tests stubbed/verified `storageService.upload`; after the reroute that path no longer exists, so the stubs/verifies were stale.
- **Fix:** Re-stubbed `mediaAssetService.acceptQuarantineAndQueue`, verified it is called + `upload` never, and added a dedicated `RoutesThroughOnePipeline` test asserting primary placement + captured raw bytes.
- **Files:** BulkImportServiceTest.java
- **Commit:** `af2bd98`

**Total deviations:** 4 (3 Rule 3, 1 Rule 1) — all necessary to complete the worker/reaper/unification correctly. No scope creep; no architectural change (Rule 4 not triggered).

## Threat Model Coverage
- **T-24-13 (content-type spoof)** — mitigated: the worker's `MediaNormalizer` magic-byte-sniffs + allowlists (jpeg/png/webp) + decode-verifies; a `.jpg` that is a PDF → FAILED (`MediaProcessingWorkerIntegrationTest#magicByteMismatchVetoes`, `GateStrictnessTest#normalizeFailMarksFailed`).
- **T-24-14 (decompression bomb)** — mitigated: the header-only megapixel cap runs in `MediaNormalizer` (24-01) before decode; the worker maps `DecompressionBombException` → FAILED.
- **T-24-15 (EXIF/GPS PII)** — mitigated: decode→WebP re-encode drops all source metadata; only the re-encoded derivative is stored.
- **T-24-16 (polyglot)** — mitigated: the stored object is fresh WebP bytes from cwebp; embedded payloads do not survive transcode.
- **T-24-17 (cross-tenant worker write)** — mitigated: `set_config` GUC pin before any DB write; `#workerPinsTenantGuc` proves the PENDING row is invisible without the pin and visible+updatable with it under a NOSUPERUSER downgrade.
- **T-24-18 (raw served as the object)** — mitigated: the stored artifact is ALWAYS the normalized WebP derivative; the raw lives only in quarantine and is deleted on success (and on FAILED).

## Known Stubs
None — every worker/reaper/unification path is implemented and proven by tests. MinIO is a `@SpyBean` in the integration tests (the derivative write / raw read / physical delete are asserted without a live object store), and the vision provider is advisory-off by default; neither is a stub of the pipeline behaviour.

## Deferred / Known-Red (by design, not this plan's failure)
- **IMG-03 stays PENDING (anti-false-green)** — the gate-strictness ENGINE (FAILED veto / flagged-not-blocked / advisory-off) is delivered + green here, but IMG-03's full acceptance also needs the **vendor-visible review queue** (GET queue + Keep/dismiss-flag), which is **24-05**'s scope. IMG-03 closes in 24-05.
- **Live upload E2E** (processing→ACTIVE + a real FAILED path in the running Compose stack, 375px) is the standard **/gsd:verify-work** manual step (needs MinIO + the RabbitMQ worker + Keycloak vendor login) — deferred per 24-VALIDATION Manual-Only.
- **REBUILD-ALL still applies** — the core-java image needs the 24-01 `apk add libwebp-tools` + `-D…webp.binary.dir` before any Compose/E2E (bundled glibc cwebp will not exec on Alpine).
- **`docs/metrics.json` count reconcile + OpenAPI snapshot regen** remain 24-06 phase-gate tasks.

## Self-Check: PASSED
- All 6 created files present on disk (MediaProcessingWorker, MediaPendingReaper + 4 test classes).
- All 3 task commits present in git: `002d3cc`, `af2bd98`, `b468f62`.
- `:core-java:compileJava`/`compileTestJava` clean. Full media integration suite green: **31 tests, 0 failures / 0 errors** — `MediaProcessingWorkerIntegrationTest` 4/4, `GateStrictnessTest` 3/3, `CowSafetyIntegrationTest` 2/2, plus the 24-01/24-02/24-03 media suites unregressed (`MediaCopyOnWriteIntegrationTest` 4/4, `MediaAssetRlsPolicyIntegrationTest` 4/4, `MediaBackfillMigrationIntegrationTest` 1/1, `MediaUploadControllerTest` 4/4, `MediaUploadIdempotencyTest` 4/4, `MediaEventOutboxRepositoryTest` 2/2, `MediaWebpMuslSmokeTest` 1/1). Unit: `MediaPendingReaperTest` 2/2 + `BulkImportServiceTest` green. `grep -c 'storageService.upload(' BulkImportService.java == 0`.

---
*Phase: 24-image-architecture-cow-assets-safe-upload-pipeline*
*Completed: 2026-07-23*
