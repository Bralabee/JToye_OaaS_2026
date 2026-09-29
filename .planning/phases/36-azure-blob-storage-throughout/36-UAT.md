---
status: complete
phase: 36-azure-blob-storage-throughout
source: [36-VERIFICATION.md]
started: 2026-09-29T17:10:00Z
updated: 2026-09-29T18:20:00Z
---

## Current Test

none — all tests resolved

## Tests

### 1. Storefront photography and vendor review queue after the Phase 36 reseed (390px and 1280px)
steps: Open http://localhost:3000, go to a seeded shop at 390px and 1280px widths, scroll to the bottom first; right-click a product image and copy its address. Then sign in as the dev vendor and open the media review queue.
expected: Real, non-broken food photography at both widths whose URL starts with http://localhost:10000/devstoreaccount1/jtoye-images; reseed-affected assets show FAILED with the reason "Bytes not carried over in the Phase 36 dev reseed (D-04) -- re-upload" and a Re-upload control that reads clearly.
result: pass — approved by the owner 2026-09-29 from four full-page screenshots, taken after scrolling, of /shop/brixton-village-grill and /dashboard/media/review at 390px and 1280px (stack rebuilt from eefb46fa). Measured alongside: 9 of 9 Azurite-origin storefront images loaded (naturalWidth > 0) at both widths; the review queue listed the 3 rejected uploads for the tenant, including the reseed one with its reason text, each with a Re-upload button, and no horizontal overflow at either width. The first capture was invalid and was retaken: core-java had been recreated onto host port 9091, so every browser-side API call failed. The fix was recreating core-java on 9090. That failed capture also exposed a misleading empty state on this page, filed as issue #762 (it predates Phase 36).

## Summary

total: 1
passed: 1
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
