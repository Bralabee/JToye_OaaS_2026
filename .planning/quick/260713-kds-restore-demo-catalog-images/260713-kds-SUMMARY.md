---
phase: quick-260713-kds
plan: 01
subsystem: dev-seeder / storage / storefront
status: complete
tags: [demo-data, images, minio, storefront, dev-only, regression-fix]
requires:
  - StorageService (S3Client + StorageProperties)
  - DemoDataSeeder curated menus (3 shops x 7 items)
  - MinIO jtoye-images bucket (anonymous download policy)
provides:
  - DemoImageManifest (classpath manifest loader + shop->slug map)
  - StorageService.putSeedImage (deterministic, idempotent seed upload seam)
  - StorageService.productUploadUrlPrefix(tenant[, productId]) (vendor-vs-seed distinguisher)
  - DemoDataSeeder.seedProductImages (additive seeder-owns image step)
  - docs/CREDITS-demo-images.md (CC attribution)
affects:
  - dev demo storefront rendering (21 curated products now show real dish photos)
tech-stack:
  added: []          # Jackson + AWS SDK v2 already on classpath — zero new deps
  patterns:
    - "deterministic seed key + HeadObject idempotency (skip re-upload on restart)"
    - "seeder-owns overwrite policy keyed to the product's OWN upload folder"
    - "single-source curated menus derived for both seeding and tests"
key-files:
  created:
    - core-java/src/main/resources/dev/demo-images/manifest.json
    - core-java/src/main/resources/dev/demo-images/(21 *.jpg)
    - core-java/src/main/java/uk/jtoye/core/dev/DemoImageManifest.java
    - core-java/src/test/java/uk/jtoye/core/dev/DemoImageManifestTest.java
    - docs/CREDITS-demo-images.md
  modified:
    - core-java/src/main/java/uk/jtoye/core/storage/StorageService.java
    - core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
    - docs/metrics.json
    - CLAUDE.md
decisions:
    - "Explicit shop-name->slug map (period/apostrophe tolerant), NOT a hand-rolled slugify"
    - "putSeedImage is a byte[] seam (no MultipartFile) — dev classpath bytes, HeadObject idempotency"
    - "Seeder-owns vendor-protection scoped to the product's OWN id folder, not the coarse /products/ prefix"
    - "Curated menus exposed as a single source of truth so the test cannot silently rot"
metrics:
  duration: ~55min
  completed: 2026-07-13
  tasks: 3 (+1 deviation fix)
  total_logical_invocations: 1243
---

# Phase quick-260713-kds Plan 01: Restore Demo Catalog Images Summary

Restored real dish photography to the dev demo catalog: 21 license-verified (CC0/CC-BY/CC-BY-SA) Wikimedia photos are bundled on the classpath and the dev-only `DemoDataSeeder` idempotently uploads each to MinIO and stamps the matching curated product's `imageUrl` — reversing the Phase 19-09 "no image_url" regression-by-omission WITHOUT weakening any of the seeder's existing invariants. All 21 curated products across the 3 demo storefronts now render `<img>` with `naturalWidth>0`.

## What Shipped

- **21 classpath assets + manifest** under `core-java/src/main/resources/dev/demo-images/` (zero NC/ND).
- **`DemoImageManifest`** — Jackson loader + explicit shop-name→slug map (tolerant of the "Peckham Jollof Co" no-period and "Mama Ade's Kitchen" apostrophe traps) + classpath byte loader.
- **`StorageService.putSeedImage`** — deterministic `<tenant>/products/seed/<file>` key, `HeadObject` existence-check idempotency, reuses the vendor public-URL + immutable cache-control; magic-byte sanity guard. No `MultipartFile` (main runtime has no `MockMultipartFile`).
- **`DemoDataSeeder.seedProductImages`** — additive step; the seeder-owns overwrite policy fills null/blank slots, re-affirms prior seed URLs, replaces foreign/legacy URLs, and never clobbers a genuine vendor upload. All pre-existing invariants (idempotent upsert, RLS-GUC transaction, quarantine, WR-10 unpublish exception, tenant-B probe, `@Profile("dev")`, not-a-Flyway-migration) are intact — the image step is purely additive.
- **`docs/CREDITS-demo-images.md`** — 21-row attribution table (dish/shop/author/license+link/source).
- **`DemoImageManifestTest`** — 5 pure classpath/POJO tests (no Spring/MinIO/Testcontainers).
- **Docs reconciled** — `docs/metrics.json` regenerated (1238→1243); `CLAUDE.md` count sentence made honest (also fixed pre-existing Jest 231/32→234/33 prose drift) + `## Incremental Betterment Doctrine` section added.

## Browser Proof (Task 3)

Live: core-java rebuilt from the worktree and recreated in the running `jtoye_oaas_2026` stack; container healthy; seeder logs show 21 images uploaded to MinIO on first boot, then 21 idempotent "already present" skips + 2 `imageUrl` changes on the fix boot. Playwright-core (chromium) visited the 3 demo shop pages and read `naturalWidth` for every product `<img>` (identified by the decoded `/products/seed/` origin, de-duped by filename):

| Shop | naturalWidth>0 |
|------|----------------|
| mama-ades-kitchen | 7/7 |
| peckham-jollof-co | 7/7 |
| brixton-village-grill | 7/7 |
| **TOTAL** | **21/21** |

- **Peri Peri Chicken** (brixton-village-grill): `naturalWidth=960` — seed image (was a legacy URL).
- **Suya Platter** (peckham-jollof-co): `naturalWidth=900` — seed image (was a legacy URL).

Screenshot of Brixton Village Grill (human-check) confirms real dish photos on every card, no SafeImage fallback tiles. Screenshots: `scratchpad/PROOF-{slug}.png`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Seeder-owns policy misclassified two legacy URLs as genuine vendor uploads**
- **Found during:** Task 3 (live verification — first boot seeded only 19/21, leaving Peri Peri Chicken + Suya Platter untouched).
- **Root cause:** The plan's landmine assumed the Peri Peri Chicken + Suya Platter legacy URLs did NOT start with the vendor prefix `<publicUrl>/<tenant>/products/`. Empirically they DO — they are vendor-shaped (`.../products/<uuid>/<uuid>.jpg`) and resolve HTTP 200. The coarse `/products/` prefix test therefore classified them as genuine vendor uploads and skipped them, contradicting the explicit success criteria (all 21 render; these two replaced).
- **Fix:** A genuine vendor upload always keys on the product's OWN id (`StorageService.upload` uses `entityId` = product id). The two legacy URLs embed a DIFFERENT entity id than the product's own id (Peri Peri product `47d0d894…` vs URL folder `2b76f67d…`; Suya product `f3551ea7…` vs URL folder `41ea6ce5…`) — they are foreign/legacy artifacts. Refined the policy to test against `<publicUrl>/<tenant>/products/<thisProductId>/` via a new `StorageService.productUploadUrlPrefix(tenant, productId)` overload. A URL under a different entity id is "ours to overwrite."
- **Why it is strictly safer:** This makes the vendor-protection invariant MORE precise, not weaker — it now ties "genuine vendor upload" to the product's own id exactly as `StorageService.upload` builds keys, so a real vendor upload (under the product's own folder) is still never clobbered.
- **Files modified:** `StorageService.java`, `DemoDataSeeder.java`
- **Commit:** `4dcb807`
- **Verified:** fix boot logged "2 demo image(s) seeded"; DB shows 21/21 curated products under `/products/seed/`; browser proof 21/21.

## Threat Flags

None. No new trust boundary introduced. T-KDS-01 (never clobber a vendor upload) is preserved and tightened by the deviation fix; T-KDS-02 (dev-only, no Flyway, schema stays V50) and T-KDS-03 (attribution) are satisfied. Schema version unchanged at V50 (verified). No new dependency (T-KDS-SC).

## Known Stubs

None. Every curated product resolves to a real, license-verified image; SafeImage remains only as a genuine fallback for products with no mapped seed image.

## Commits

- `572faf4` — feat(seed): restore license-verified demo catalog images
- `ab1c656` — test(seed): assert demo-image manifest maps to curated products
- `23ba31e` — docs: reconcile metrics + add Incremental Betterment Doctrine
- `4dcb807` — fix(seed): scope vendor-upload protection to the product's own id folder

## Verification Results

- `./gradlew -p core-java compileJava` — clean (2 pre-existing unrelated ShopMapper warnings).
- `./gradlew -p core-java test --tests 'uk.jtoye.core.dev.DemoImageManifestTest'` — 5/5 green (0 failures).
- `bash scripts/docs-freshness.sh` (check mode) — OK (total 1243, matches source).
- Live seeder INFO logs show image seeding; idempotent on restart (HeadObject skip).
- Playwright-core probe: 21/21 curated product `<img>` `naturalWidth>0` across the 3 demo shop pages.
- No Flyway migration added (schema stays V50); `DemoDataSeeder` remains `@Profile("dev")`.

## Self-Check: PASSED

- Created files present: manifest.json + 21 jpgs, DemoImageManifest.java, DemoImageManifestTest.java, docs/CREDITS-demo-images.md, SUMMARY.md — all FOUND.
- Commits present: 572faf4, ab1c656, 23ba31e, 4dcb807 — all FOUND.
