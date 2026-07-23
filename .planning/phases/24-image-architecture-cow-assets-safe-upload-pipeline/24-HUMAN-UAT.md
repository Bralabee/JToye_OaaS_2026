---
status: partial
phase: 24-image-architecture-cow-assets-safe-upload-pipeline
source: [24-VERIFICATION.md]
started: 2026-07-23T21:10:00Z
updated: 2026-07-23T21:10:00Z
---

## Current Test

[awaiting human testing — run after a REBUILD-ALL of all containers]

## Tests

### 1. Live upload → PENDING → ACTIVE flow (375px, real vendor session)
expected: After a REBUILD-ALL of all containers (core-java picks up the 24-01 `apk add libwebp-tools` Dockerfile change), sign in as a vendor and upload a valid JPEG/PNG. The accept returns 202 immediately; the product image shows the `AssetImage` PENDING/processing state; within a few seconds the RabbitMQ worker normalizes the upload and the product renders the WebP derivative with `naturalWidth > 0` and no CLS jump (explicit width/height present).
result: [pending]

### 2. FAILED + flagged review-queue flow (375px)
expected: Upload a non-image file renamed with an image extension (e.g. a PDF as `fake.jpg`) → `/dashboard/media/review` shows a FAILED row with the vendor-visible `failure_reason` and a working Re-upload control. With the vision advisory flag ON and a low-confidence result, a flagged-ACTIVE row shows a "Needs review" badge with working Keep (drops the row, image stays live) and Replace (routes to `/dashboard/products`, a documented DTO-shape deviation).
result: [pending]

## Summary

total: 2
passed: 0
issues: 0
pending: 2
skipped: 0
blocked: 0

## Gaps
