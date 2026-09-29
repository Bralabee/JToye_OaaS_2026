---
status: testing
phase: 36-azure-blob-storage-throughout
source: [36-VERIFICATION.md]
started: 2026-09-29T17:10:00Z
updated: 2026-09-29T17:10:00Z
---

## Current Test

number: 1
name: Storefront photography and vendor review queue after the Phase 36 reseed (390px and 1280px)
expected: |
  Real, non-broken food photography renders at both widths from the Azurite origin
  (image address starts with http://localhost:10000/devstoreaccount1/jtoye-images);
  the vendor media review queue clearly surfaces the FAILED / Re-upload state for
  reseed-affected assets rather than looking like a silent error or blank tile.
awaiting: user response

## Tests

### 1. Storefront photography and vendor review queue after the Phase 36 reseed (390px and 1280px)
steps: Open http://localhost:3000, go to a seeded shop at 390px and 1280px widths, scroll to the bottom first; right-click a product image and copy its address. Then sign in as the dev vendor and open the media review queue.
expected: Real, non-broken food photography at both widths whose URL starts with http://localhost:10000/devstoreaccount1/jtoye-images; reseed-affected assets show FAILED with the reason "Bytes not carried over in the Phase 36 dev reseed (D-04) -- re-upload" and a Re-upload control that reads clearly.
result: [pending]

## Summary

total: 1
passed: 0
issues: 0
pending: 1
skipped: 0
blocked: 0

## Gaps
