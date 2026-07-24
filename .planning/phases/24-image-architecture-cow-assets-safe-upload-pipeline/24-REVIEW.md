---
phase: 24-image-architecture-cow-assets-safe-upload-pipeline
reviewed: 2026-07-23T19:17:33Z
depth: standard
files_reviewed: 43
files_reviewed_list:
  - core-java/src/main/java/uk/jtoye/core/media/MediaNormalizer.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaProperties.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaConfig.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaAsset.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaAssetStatus.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaAssetRepository.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java
  - core-java/src/main/java/uk/jtoye/core/media/ProductMedia.java
  - core-java/src/main/java/uk/jtoye/core/media/ProductMediaRepository.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaUploadController.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaController.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaAcceptDto.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaAssetDto.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaEventOutbox.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaEventOutboxRepository.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaEventOutboxFlusher.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaProcessingEvent.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaProcessingWorker.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaPendingReaper.java
  - core-java/src/main/java/uk/jtoye/core/media/exception/DecompressionBombException.java
  - core-java/src/main/java/uk/jtoye/core/media/exception/PayloadTooLargeException.java
  - core-java/src/main/java/uk/jtoye/core/media/exception/UnreadableImageException.java
  - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
  - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java
  - core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductController.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductMapper.java
  - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
  - core-java/src/main/java/uk/jtoye/core/product/dto/ProductDto.java
  - core-java/src/main/java/uk/jtoye/core/storage/StorageService.java
  - core-java/src/main/resources/db/migration/V53__media_asset.sql
  - core-java/src/main/resources/db/migration/V58__media_event_outbox.sql
  - core-java/src/main/resources/application.yml
  - frontend/components/ui/asset-image.tsx
  - frontend/components/ui/safe-image.tsx
  - frontend/components/ui/image-uploader.tsx
  - frontend/components/dashboard/media/ReviewQueue.tsx
  - frontend/app/dashboard/media/review/page.tsx
  - frontend/components/dashboard/sidebar.tsx
  - frontend/lib/media-api.ts
  - frontend/types/api.ts
findings:
  critical: 1
  warning: 5
  info: 3
  total: 9
status: issues_found
---

# Phase 24: Code Review Report

**Reviewed:** 2026-07-23T19:17:33Z
**Depth:** standard
**Files Reviewed:** 43
**Status:** issues_found

## Summary

This phase ships the copy-on-write `media_asset` model (V53), a dedicated transactional
outbox (V58 + `MediaEventOutboxFlusher`), the async normalize worker
(`MediaProcessingWorker`), the reject-early accept controller, and the vendor review-queue
UI. The multi-tenant RLS surface is **strong**: every new table (V53 `media_asset`,
`product_media`, `media_asset_aud`; V58 `media_event_outbox`) carries ENABLE + FORCE RLS
routed through the safe `current_tenant_id()` helper (no raw `::uuid` cast — the 22P02 class
is avoided), the V53 backfill correctly loops tenants and pins the GUC per tenant, and the
three off-request runners (worker, reaper, outbox flusher) all pin the tenant GUC before any
RLS-bound query — the worker/reaper via an explicit `set_config` AND the just-in-time
`TenantSetLocalAspect` repository advice, the flusher via that same aspect firing on its
Spring-Data repository claim. The untrusted-upload surface is also well-built: magic-byte
sniff (not client Content-Type), a header-only megapixel bomb guard BEFORE decode (with a
real WebP ImageIO reader present via twelvemonkeys), EXIF-stripping re-encode, and the stored
artifact is always the produced WebP derivative. The ambiguous-mapping risk is resolved — the
old synchronous `ProductController.uploadImage` was retired and `MediaUploadController` is the
sole owner of `POST /api/v1/products/{id}/image`.

**However**, there is one BLOCKER: the sha256 **dedup short-circuit on accept never attaches
the reused asset to the target product** — a deduplicated upload returns 202 success but
silently fails to set the product's image (a "regression by omission"). Five warnings and
three info items follow, concentrated on the CoW/reaper race surface, a shop-scoping gap on
the Keep action, and the reject-early gate semantics.

No structural findings block was provided, so this report is entirely narrative.

## Narrative Findings (AI reviewer)

## Critical Issues

### CR-01: Dedup short-circuit on accept never links the reused asset to the target product — a deduplicated upload silently fails to attach the image

**File:** `core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java:113-121`
**Issue:**
`acceptQuarantineAndQueue` short-circuits on `(tenant_id, sha256)` dedup and returns the
existing asset **before** it records any placement intent, inserts a `media_event_outbox`
row, or creates a `product_media` link:

```java
Optional<MediaAsset> existing = findDedup(tenantId, sha256);
if (existing.isPresent()) {
    MediaAsset a = existing.get();
    log.info("Dedup short-circuit on accept: product {} reuses existing asset {} (status {})", ...);
    return new MediaAcceptDto(a.getId(), a.getStatus().name());   // <-- returns; no product_media, no event
}
```

The **only** runtime code path that creates a `product_media` join row is the worker's
`placeOnActive` → `attachPlacement`/`repoint` (`MediaProcessingWorker.java:194-219`,
`MediaAssetService.java:191-201`), and that path is reached **only** via a
`media_event_outbox` event, which is emitted **only** for a freshly-inserted PENDING asset
(`MediaAssetService.java:143-148`). On a dedup hit none of that runs. Confirmed by exhaustive
search: the sole `product_media` writers are the worker (`attachPlacement`), the worker's
`repoint`, and the V53 backfill — there is no accept-time or client-side attach.

Because `findDedup` matches on `(tenant_id, sha256)` **tenant-wide across all products**
(`MediaAssetRepository.findByTenantIdAndSha256`), the trigger is realistic:

- Product B uploads bytes that Product A already uploaded through the new pipeline (same stock
  photo, or the vendor re-uses one file for two products). Product B gets `202 {assetId,
  status:"ACTIVE"}`, the uploader shows success, the client re-fetches the product — and
  Product B has **no `product_media` row and no image**. The flat `image_url` (if any) is left
  untouched, so a brand-new product stays imageless and a replacement never replaces.
- Re-uploading the identical file to a *different slot* of the same product (e.g. also as a
  gallery image) likewise no-ops.

This defeats the core IMG-01 goal (safe sharing/dedup) for the exact case dedup exists to
serve: a reused asset must still be **attached** to the new product. It is a data-integrity /
silently-dropped-capability failure, not a cosmetic one. The `Media*IntegrationTest` suite is
green, but green does not prove the cross-product dedup-attach case is covered — trace it
explicitly.

**Fix:** On a dedup hit, still perform the placement against the reused asset. If the asset is
already `ACTIVE`, attach/repoint the `product_media` slot immediately (reuse `placeOnActive`'s
attach-or-repoint logic against the existing asset id); if it is still `PENDING`, record the
new product's placement intent and let the worker place it (note: the single-asset design
cannot carry two products' placement intents on one row — so the ACTIVE case must attach
directly, and the PENDING case needs its own `product_media` creation on the second product
rather than overwriting the first asset's `product_id`/`is_primary`/`sort_order`). Sketch for
the ACTIVE branch:

```java
if (existing.isPresent()) {
    MediaAsset a = existing.get();
    if (a.getStatus() == MediaAsset.Status.ACTIVE) {
        // Attach/repoint THIS product's slot to the shared asset (CoW share).
        placeExistingActiveAsset(tenantId, productId, a.getId(), placement);
    }
    // else PENDING/FAILED: see note above — do not silently drop the placement.
    return new MediaAcceptDto(a.getId(), a.getStatus().name());
}
```

Also add an integration test that uploads the same bytes to two different products and asserts
BOTH products end up with a `product_media` row pointing at the one shared asset.

## Warnings

### WR-01: A FAILED asset permanently poisons its `(tenant, sha256)` dedup slot with no reprocessing path

**File:** `core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java:115` and `MediaProcessingWorker.java:255-261`
**Issue:** `uq_media_asset_tenant_sha` (V53:74) allows exactly one `media_asset` per
`(tenant_id, sha256)` regardless of status, and `findByTenantIdAndSha256` returns any status.
When the worker fails an upload it keeps the FAILED row and **deletes the quarantine object**
(`fail(...)` → `storageService.deleteByKey(quarantineKey)`). Any later upload of the identical
bytes (any product) hits the dedup short-circuit and returns the FAILED asset — no
reprocessing, and the raw bytes are already gone, so even a deliberate reprocess is
impossible. Combined with CR-01, the second upload both "fails" and fails to attach. A single
transient/decoder failure thus blocks those exact bytes tenant-wide.
**Fix:** Treat a FAILED (or non-ACTIVE) dedup match as a cache miss for the accept path — i.e.
scope `findDedup` to `status = ACTIVE` (or PENDING) so a FAILED row does not short-circuit a
fresh attempt. This requires reconciling the unique index (e.g. exclude FAILED from the unique
constraint, or delete the FAILED row when its bytes are purged) so a re-upload can insert a
new PENDING row for the same sha.

### WR-02: No optimistic lock on `MediaAsset` — the reaper can race an in-flight worker (lost update / quarantine deleted mid-process)

**File:** `core-java/src/main/java/uk/jtoye/core/media/MediaAsset.java:40-99` (no `@Version`); interaction `MediaPendingReaper.java:72-93` vs `MediaProcessingWorker.java:126-185`
**Issue:** `MediaAsset` has no `@Version` column. The reaper flips any `PENDING` row older than
`reaper-grace-ms` (default 15 min) to FAILED and deletes its quarantine object, in its own
transaction. A worker that is legitimately still processing a grace-exceeded asset runs in a
separate transaction with no version guard, so last-write-wins: (a) the reaper can delete the
quarantine object between the worker's `getBytes` read and a retry, or (b) the reaper can
overwrite the worker's `ACTIVE` flip back to FAILED **after** the worker already stored the
derivative and repointed the `product_media` slot — leaving a FAILED asset that is
simultaneously a product's live primary image. The 15-minute grace makes this unlikely, but it
is an unguarded correctness hazard on the exact "FAILED reprocess must never clobber a live
ACTIVE image" invariant this phase promises.
**Fix:** Add `@Version` to `MediaAsset` (and the column to V53) so a concurrent
reaper/worker write fails fast with an optimistic-lock exception rather than silently
last-write-wins; alternatively have the reaper claim rows with `FOR UPDATE SKIP LOCKED` and
re-check `status = PENDING` under the lock, and only ever transition PENDING→FAILED (never
touch a row the worker has already moved to ACTIVE).

### WR-03: `POST /api/v1/media/{assetId}/keep` is only tenant-scoped, not shop-scoped — a SHOP_MANAGER of one shop can dismiss another shop's content flag

**File:** `core-java/src/main/java/uk/jtoye/core/media/MediaController.java:69-84` and `MediaAssetService.dismissFlag` (`MediaAssetService.java:255-262`)
**Issue:** Upload accept and image delete both enforce the VSA-02 shop-scoped write gate
(`shopAccessService.require(shopId, SHOP_MANAGER)` — see
`MediaAssetService.acceptQuarantineAndQueue:111` and `ProductService.removeImage:338`). The
Keep action, which mutates asset state (`flagged=false`), enforces only the tenant-wide
`SCOPE_catalog:write` scope and does no shop-role check. `dismissFlag` just does an RLS-scoped
`findById` + `setFlagged(false)`. So within a tenant, a user scoped as SHOP_MANAGER of shop A
can clear the content-review flag on shop B's flagged image — a shop-scoping bypass on a
mutating action, inconsistent with the rest of the media write surface.
**Fix:** In `dismissFlag`, resolve the asset's owning shop (via its `product_id` →
`product.shopId`, or the `product_media`→product join) and call
`shopAccessService.require(shopId, ShopRole.SHOP_MANAGER)` before clearing the flag, mirroring
the upload/delete gate. Where the shop cannot be resolved (shared/legacy asset), fall back to
the GROUP_ADMIN rule already used for null-shop resources.

### WR-04: Reject-early Content-Length gate uses the wrong budget and runs after multipart is already parsed

**File:** `core-java/src/main/java/uk/jtoye/core/media/MediaUploadController.java:98-103`
**Issue:** Two problems: (1) The guard compares the **whole multipart request** `Content-Length`
against the **file** cap `max-upload-bytes` (5MB). A valid near-limit file whose multipart
envelope pushes the request just over 5MB is spuriously rejected with 413, even though the file
itself is within the file cap and `max-request-size` is 6MB. (2) Spring resolves the
`@RequestParam MultipartFile file` argument (forcing Tomcat to parse/buffer the multipart, and
throw `MaxUploadSizeExceededException` on a genuinely oversize body) **before** the method body
runs — so by the time this Content-Length check executes, the body is already parsed. The
guard is therefore not the "refuse BEFORE touching a single file byte" DoS backstop the Javadoc
claims; the real backstop is `spring.servlet.multipart.max-file-size/max-request-size`
(correctly set to 5MB/6MB). Net effect: the guard adds no DoS protection but can false-positive
on legitimate uploads.
**Fix:** Either remove the redundant Content-Length check and rely on the multipart size config
(already mapped to 413 via `handleMaxUploadSizeExceeded`), or compare Content-Length against
`max-request-size` (6MB) rather than the file cap, and correct the Javadoc so it does not claim
pre-parse protection it cannot provide.

### WR-05: `thumbnailUrlFor` advertises a non-existent `_thumb.webp` for a backfilled `.webp` asset

**File:** `core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java:302-308`
**Issue:** `thumbnailUrlFor` returns `<base>_thumb.webp` for any ACTIVE asset whose
`object_key` ends in `.webp`. Pipeline-produced derivatives always have a sibling
`<id>_thumb.webp`, but a **backfilled** ACTIVE asset (V53 wraps existing object keys as-is)
that happens to be a `.webp` original has no thumbnail sibling. The DTO then advertises a
`thumbnailUrl` that 404s. The frontend degrades to a fallback icon (`SafeImage` onError), so
it is not fatal, but the API returns a knowingly-broken URL and grid cards using
`useThumbnail` show the placeholder instead of the image.
**Fix:** Only compute a thumbnail URL for keys under the pipeline's `<tenant>/media/<id>.webp`
convention (e.g. check the `/media/` path segment or a per-asset flag), not for any `.webp`
suffix; otherwise return `null` so the caller falls back to the full `url`.

## Info

### IN-01: Non-transactional MinIO writes inside the accept/worker transactions can orphan objects on rollback

**File:** `core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java:130` and `MediaProcessingWorker.java:156-157`
**Issue:** The quarantine PUT (accept) and the derivative/thumbnail PUTs (worker) are external
side effects executed inside a JPA transaction. If the transaction later rolls back (e.g. the
idempotency completion UPDATE fails, or the worker's `placeOnActive` throws a
`DataIntegrityViolation`), the object is written but the row is not. Content-addressed
quarantine keys bound that leak (a retry overwrites the same key), but a rolled-back worker
leaves an orphaned `<tenant>/media/<id>.webp` derivative that the reaper will not clean — the
reaper deletes `asset.getObjectKey()`, which after rollback is still the quarantine key, not
the derivative.
**Fix:** Acceptable for now given content-addressed keys and idempotent reprocessing; if
tightened, delete-derivative-on-failure in the worker's catch path, or move the PUTs to an
after-commit synchronization.

### IN-02: `SafeImage` never resets its `failed` state when `src` changes

**File:** `frontend/components/ui/safe-image.tsx:34-42`
**Issue:** `failed` is component state set by `onError` and never reset. If the same `SafeImage`
instance receives a new, valid `src` after a prior load error (e.g. a thumbnail/url toggle
within the ACTIVE branch), it keeps showing the fallback. The Phase 24 PENDING→ACTIVE flow
happens to remount a fresh `SafeImage` (different subtree), so it is not hit there, but the
component is now reused with dynamic `src` and the latent stale-error bug is easy to trip.
**Fix:** Reset `failed` when `src` changes, e.g. `useEffect(() => setFailed(false), [src])`, or
key the `<img>` on `src`.

### IN-03: V53 backfill writes 1-based gallery `sort_order`; accept/worker default 0-based

**File:** `core-java/src/main/resources/db/migration/V53__media_asset.sql:216-248` vs `MediaProcessingWorker.placeOnActive` (`MediaProcessingWorker.java:200-204`)
**Issue:** The backfill stores gallery `sort_order` from `WITH ORDINALITY` (1-based), while a
new accept defaults `sort_order=0` and the worker's non-primary placement matches a slot by
**exact** `sort_order` (`findFirstByProductIdAndPrimaryFalseAndSortOrder`). A gallery-slot
re-upload against a backfilled row would not line up on exact `sort_order`. The positional
delete path (`releaseGalleryAssetAt`) is robust to this, and the current UI does not wire a
gallery-slot re-upload (Replace routes to the product page with the primary default), so it is
latent — but the 0-vs-1 inconsistency is a trap for the next gallery feature.
**Fix:** Normalize gallery `sort_order` to a single convention (0-based) in the backfill, or
make the worker's non-primary placement positional rather than exact-match, and document the
chosen base.

---

_Reviewed: 2026-07-23T19:17:33Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
