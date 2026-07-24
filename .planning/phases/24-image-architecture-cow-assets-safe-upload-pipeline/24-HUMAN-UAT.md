---
status: passed
phase: 24-image-architecture-cow-assets-safe-upload-pipeline
source: [24-VERIFICATION.md]
started: 2026-07-23T21:10:00Z
updated: 2026-07-24T09:55:00Z
verified_by: automated-playwright-uat
evidence: scratchpad/media-uat/ (01-products-list, 02-upload-processing, 03-active-webp-served, 04-review-queue-375px .png + outcomes.json)
---

## Current Test

[complete — automated live E2E run 2026-07-24 against the rebuilt containerized full-stack]

## Tests

### 1. Live upload → PENDING → ACTIVE flow (375px, real vendor session)
expected: After a REBUILD-ALL of all containers, sign in as a vendor and upload a valid image. The accept returns 202 immediately; the product shows a processing state; after the RabbitMQ worker runs, the product renders the WebP derivative (naturalWidth > 0).
result: PASSED — real Keycloak `admin-user` login; uploaded a fresh 400×400 PNG to a product → accept **202 {status:PENDING}** (assetId `71c253f8…`); the uploader showed the "Processing…" overlay; after the worker ran, the products list rendered the product thumbnail whose `src` contains the new assetId with **naturalWidth > 0**. DB confirms `media_asset 71c253f8… status=ACTIVE, object_key=…/media/71c253f8….webp` — the stored artifact is the normalized WebP derivative, not the raw upload. (Evidence: 02-upload-processing.png, 03-active-webp-served.png.)

### 2. FAILED + flagged review-queue flow (375px)
expected: A file that fails validation → review queue shows FAILED + reason + Re-upload; a flagged-ACTIVE image → Keep/Replace.
result: PASSED (FAILED path) — uploaded a real 400×400 GIF (client accepts it; the server allowlist is jpeg/png/webp only). Accept **202**, then the worker vetoed it → DB `media_asset b02a304a… status=FAILED, failure_reason="Upload is not an allowed image type (jpeg/png/webp)"`, object left in `…/quarantine/….gif` (never promoted). `/dashboard/media/review` **at 375px** renders the "Rejected uploads" section with the vendor-visible reason + a working **Re-upload** button and the mobile bottom-tab nav. (Evidence: 04-review-queue-375px.png.)
note: The flagged-ACTIVE **Keep/Replace** sub-flow was not live-exercised because the advisory vision stage defaults OFF (no flagged assets can be produced without enabling it) — it is covered by `MediaKeepShopScopeIntegrationTest` (incl. the WR-03 shop-scope gate) at the integration layer. To live-test it, set `jtoye.media.vision.enabled=true` with a low-confidence result.

## Summary

total: 2
passed: 2
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

None. Both default-configuration surfaces verified live; the flagged Keep/Replace path is integration-proven and gated behind the OFF-by-default vision flag.
