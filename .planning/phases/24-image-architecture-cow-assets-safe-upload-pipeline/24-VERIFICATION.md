---
phase: 24-image-architecture-cow-assets-safe-upload-pipeline
verified: 2026-07-23T20:30:00Z
status: human_needed
score: 4/4 must-haves verified
overrides_applied: 0
gaps: []
human_verification:
  - test: "Live upload -> PENDING -> ACTIVE flow in the running Compose stack (real Keycloak vendor login), 375px"
    expected: "Uploading a valid JPEG/PNG returns 202 with an assetId; the product shows a processing/skeleton state; after the RabbitMQ worker runs, the product image renders the WebP derivative with dimensions set (naturalWidth > 0)."
    why_human: "Requires a REBUILD-ALL of the core-java image (24-01 added `apk add libwebp-tools` to the Dockerfile — the currently-running container's bundled cwebp will not exec on Alpine) + a live MinIO + RabbitMQ worker + real Keycloak vendor session. Cannot be proven by static grep/unit test alone; deferred to the standard /gsd:verify-work manual step per 24-VALIDATION Manual-Only."
  - test: "A deliberately corrupt/non-image upload (e.g. a renamed .pdf as .jpg) in the running stack -> review queue shows FAILED + reason; a below-threshold vision-flagged image (vision flag turned ON) -> ACTIVE + Needs-review badge -> Keep/Replace in /dashboard/media/review"
    expected: "FAILED row shows the vendor-visible failure_reason and a working Re-upload control; a flagged row shows Keep (dismisses, drops from queue) and Replace (routes to /dashboard/products per the documented DTO-shape deviation)."
    why_human: "Same REBUILD-ALL + live-stack + Keycloak vendor login dependency as above; this is real browser/API behavior under the running pipeline, not statically verifiable."
---

# Phase 24: Image Architecture (CoW Assets + Safe Upload Pipeline) Verification Report

**Phase Goal:** media_asset (V53) copy-on-write + reference counting + safe async RabbitMQ upload/normalize pipeline that stores ONLY the validated normalized derivative (never the raw upload).
**Verified:** 2026-07-23T20:30:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | (IMG-01) Products reference `media_asset` rows via `product_media`; CoW mints+repoints one row; physical delete only at ref-count 0; sha256 per-tenant dedup; proven under NOSUPERUSER; `image_url` backfilled with dual-read | VERIFIED | `V53__media_asset.sql` (RLS via `current_tenant_id()`, `uq_media_asset_tenant_sha`, per-tenant `set_config` backfill loop); `MediaAssetService.repoint`/`releaseAsset`/`findDedup`; `ProductService.resolveAssetFirst` dual-read. Ran live: `MediaAssetRlsPolicyIntegrationTest` 4/4, `MediaCopyOnWriteIntegrationTest` 4/4, `MediaBackfillMigrationIntegrationTest` 1/1, `ProductImageDeleteIntegrationTest` 3/3 (delete-surface ref-count-release, 24-05) — 0 failures/errors, executed by the verifier, not just claimed |
| 2 | (IMG-02) Oversize refused before buffering; valid upload = quarantine + PENDING + 202; async worker sniffs/allowlists/decode-verifies/strips EXIF/pins tenant GUC before any DB write; single + BulkImportService share one path | VERIFIED | `MediaUploadController.accept` (Content-Length gate before reading `MultipartFile`), `MediaAssetService.acceptQuarantineAndQueue` (quarantine PUT + PENDING + same-tx outbox), `MediaProcessingWorker` (GUC pin via `set_config` before any read, `MediaNormalizer` sniff/bomb-guard/decode/EXIF-strip/WebP encode), `BulkImportService.importFromImages` rerouted to `acceptQuarantineAndQueue` (`grep -c 'storageService.upload(' == 0`). Ran live: `MediaUploadControllerTest` 4/4, `MediaUploadIdempotencyTest` 4/4, `MediaProcessingWorkerIntegrationTest` 4/4, `MediaNormalizerTest` 5/5, `MediaWebpMuslSmokeTest` 1/1 (in-container musl cwebp proof), `MediaEventOutboxRepositoryTest` 2/2, `BulkImportServiceTest` 15/15, `MediaPendingReaperTest` 2/2 — all 0 failures/errors |
| 3 | (IMG-03) Normalization/decode/allowlist failure -> FAILED + vendor-visible reason; below-relevance -> ACTIVE + vendor-visible review queue (never rejected); vision advisory-gated, default OFF | VERIFIED | `MediaProcessingWorker.fail()` sets `status=FAILED` + `failure_reason`, never repoints (D-04a); `applyAdvisoryVision` flags ACTIVE (never rejects) only when `jtoye.media.vision.enabled` AND `ImageAnalysisService.isEnabled()` (both default false/OFF). Ran live: `GateStrictnessTest` 3/3 (FAILED veto / flagged-not-blocked / advisory-off), `CowSafetyIntegrationTest` 2/2 (failed replacement never clobbers the live image), `MediaReviewQueueIntegrationTest` 3/3 (tenant-scoped `FAILED OR flagged-ACTIVE` selection, Keep dismisses) — 0 failures/errors |
| 4 | (IMG-04) Product UI shows processing while PENDING; surfaces FAILED (reason) + flagged (ACTIVE) in a vendor-visible review/rejection queue | VERIFIED | `frontend/components/ui/asset-image.tsx` (PENDING->spinner, FAILED->reason+Re-upload, ACTIVE&flagged->badge, ACTIVE->WebP w/ alt+dimensions), `frontend/components/dashboard/media/ReviewQueue.tsx` (FAILED + flagged sections, Keep wired to `keepAsset`, Replace routes to `/dashboard/products` per documented DTO-shape deviation), sidebar nav item, `image-uploader.tsx` 202/PENDING handling. Ran live: Jest `asset-image.test.tsx` + `ReviewQueue.test.tsx` — 2 suites / 11 tests green (executed by the verifier) |

**Score:** 4/4 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `core-java/src/main/resources/db/migration/V53__media_asset.sql` | media_asset + product_media + media_asset_aud, RLS + per-tenant backfill | VERIFIED | Read in full; matches plan exactly — `current_tenant_id()` (never `::uuid`), `uq_media_asset_tenant_sha`, per-tenant `set_config` loop, seed-image exclusion |
| `core-java/src/main/resources/db/migration/V58__media_event_outbox.sql` | dedicated media outbox, RLS-forced | VERIFIED | Read in full; ENABLE+FORCE RLS via safe helper, claim-query index |
| `core-java/src/main/java/uk/jtoye/core/media/MediaNormalizer.java` | sniff+bomb-guard+decode-verify+WebP+thumbnail | VERIFIED | Backed by `MediaNormalizerTest` 5/5 + `MediaWebpMuslSmokeTest` 1/1 (in-container musl proof) |
| `core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java` | CoW repoint + ref-count delete + dedup + accept + review queue + Keep | VERIFIED | Read in full; every method backed by a passing integration/unit test |
| `core-java/src/main/java/uk/jtoye/core/media/MediaProcessingWorker.java` | GUC-pinned pipeline, ACTIVE/FAILED/flagged, CoW-on-success | VERIFIED | Read in full; matches plan design precisely (pin GUC first, idempotent skip, fail-fast on bomb/decode/allowlist, repoint only on success) |
| `core-java/src/main/java/uk/jtoye/core/media/MediaPendingReaper.java` | orphan PENDING sweep | VERIFIED | `MediaPendingReaperTest` 2/2 green |
| `core-java/src/main/java/uk/jtoye/core/media/MediaUploadController.java` | reject-early 413 + Idempotency-Key + 202 | VERIFIED | Read in full; `MediaUploadControllerTest` 4/4 + `MediaUploadIdempotencyTest` 4/4 |
| `core-java/src/main/java/uk/jtoye/core/media/MediaController.java` | review-queue GET + keep POST | VERIFIED | Read in full; `MediaReviewQueueIntegrationTest` 3/3 |
| `core-java/src/main/java/uk/jtoye/core/product/BulkImportService.java` | one pipeline path | VERIFIED | `grep -c 'storageService.upload(' == 0`; `BulkImportServiceTest` 15/15 (includes reroute proof) |
| `frontend/components/ui/asset-image.tsx` | status-aware wrapper (PENDING/ACTIVE/FAILED/flagged), alt preserved | VERIFIED | Read in full; real UI (spinner/error-card/badge/img), not a stub; Jest 6/6 |
| `frontend/components/dashboard/media/ReviewQueue.tsx` | FAILED + flagged review queue, Keep/Replace | VERIFIED | Read in full; real fetch/render/action wiring; Jest 5/5 |
| `docs/metrics.json` | reconciled counts, docs-freshness green | VERIFIED | `total_logical_invocations: 1636`, `schema_version: 58`; `scripts/docs-freshness.sh` exits 0 (ran live) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `MediaAssetService.releaseAsset` | `StorageService.deleteByKey` | ref-count-0 physical delete | WIRED | `countByAssetId==0` gate proven by `MediaCopyOnWriteIntegrationTest`/`ProductImageDeleteIntegrationTest` |
| `ProductService` dual-read resolver | `product_media` ACTIVE derivative | asset-first, `image_url` fallback | WIRED | `resolveAssetFirst` + `ProductServiceTest` dual-read proof |
| V53 backfill | per-tenant `set_config` loop | `trap_rls_migration_backfill` pattern | WIRED | `MediaBackfillMigrationIntegrationTest` RED-provable against a bare no-GUC UPDATE (per plan design) |
| `MediaUploadController.accept` | `IdempotencyService.execute` | media.upload wrap | WIRED | `MediaUploadIdempotencyTest` (replay/409/422) |
| `MediaAssetService.acceptQuarantineAndQueue` | `media_event_outbox` | same-tx outbox insert | WIRED | `MediaEventOutboxRepositoryTest`; `MediaAssetService` reads confirm the same-tx insert |
| `MediaEventOutboxFlusher` | `media.events` exchange | single-exchange publish | WIRED | `RabbitMQConfig` MEDIA_EVENTS_* constants + beans present; `PaymentEventOutboxFlusher.java` has no Phase-24 commits (git log confirms untouched — trap avoided) |
| `MediaProcessingWorker` | `app.current_tenant_id` GUC | `set_config` before any DB write | WIRED | Worker source read in full; `MediaProcessingWorkerIntegrationTest` (GUC-pin proof under NOSUPERUSER downgrade) |
| `MediaProcessingWorker` success | `MediaAssetService.repoint` | repoint only on ACTIVE (D-04a) | WIRED | `CowSafetyIntegrationTest#failedReplacementDoesNotClobber` proves the ordering is load-bearing |
| `BulkImportService` | `MediaAssetService.acceptQuarantineAndQueue` | one pipeline path | WIRED | `grep` confirms zero legacy `storageService.upload(` calls; `BulkImportServiceTest` proves the reroute |
| `MediaController` review-queue | `media_asset` FAILED OR flagged | tenant-scoped selection | WIRED | `MediaReviewQueueIntegrationTest` (tenant isolation under NOSUPERUSER) |
| `ProductMapper.toDto` | `MediaAssetDto` status | asset-first + status surfacing | WIRED | `ProductDto.media` populated by `ProductService.resolveDetail`; `MediaAssetDtoMappingTest` 6/6 |
| `ProductService.removeImage`/`removeAdditionalImage` | `MediaAssetService.releaseAsset` | delete row + ref-count-0 physical delete | WIRED | `releasePrimaryAsset`/`releaseGalleryAssetAt` read in full; `ProductImageDeleteIntegrationTest` 3/3 |
| `asset-image.tsx` | `MediaAssetStatus` | status switch | WIRED | Read in full; Jest 6/6 |
| `ReviewQueue.tsx` | `/api/v1/media/review-queue` | `media-api.ts` fetch + keep action | WIRED | Read in full; Jest 5/5; `media-api.ts` confirmed calling the real endpoints |

### Behavioral Spot-Checks (live test execution, not static grep)

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full Phase-24 backend integration suite (media package + RlsContractTest + ProductImageDeleteIntegrationTest) | `./gradlew :core-java:integrationTest --tests 'uk.jtoye.core.media.*' --tests 'uk.jtoye.core.security.RlsContractTest' --tests 'uk.jtoye.core.product.ProductImageDeleteIntegrationTest'` | 13 test classes / 40 tests, all `failures="0" errors="0"` (JUnit XML inspected directly) | PASS |
| Phase-24 unit tests not covered above | `./gradlew :core-java:test --tests '...BulkImportServiceTest' --tests '...MediaPendingReaperTest' --tests '...MediaAssetDtoMappingTest' --tests '...MediaNormalizerTest'` | 4 test classes / 28 tests, all `failures="0" errors="0"` | PASS |
| Frontend Jest for the two new IMG-04 components | `npm test -- asset-image ReviewQueue` | 2 suites / 11 tests passed | PASS |
| docs-freshness gate | `bash scripts/docs-freshness.sh` | `docs-freshness OK: metrics match source (total logical invocations: 1636).` | PASS |
| OpenAPI snapshot carries the new media contract | inspected `docs/api/openapi-snapshot.json` | `/api/v1/media/review-queue` (GET), `/api/v1/media/{assetId}/keep` (POST), `/api/v1/products/{id}/image` now returns 202 (responses: 202/400/403/404/409/413/422) | PASS |
| Payment outbox flusher untouched (trap avoidance claim) | `git log -- .../payment/PaymentEventOutboxFlusher.java` | last commit predates Phase 24 (Phase 21 era) | PASS |

### Probe Execution

No `scripts/*/tests/probe-*.sh` conventions apply to this phase (Java/Next.js feature phase, not a migration/CLI-tooling phase). Skipped — no probes declared in any 24-0X PLAN/SUMMARY.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| IMG-01 | 24-02 (+24-05 delete surface) | CoW media_asset model, ref-counted delete, dedup, RLS, backfill | SATISFIED | See Truth #1 + artifacts/links above |
| IMG-02 | 24-01, 24-03, 24-04 | Safe async upload pipeline, reject-early, one path | SATISFIED | See Truth #2 + artifacts/links above |
| IMG-03 | 24-04, 24-05 | Gate strictness + vendor-visible review queue backend | SATISFIED | See Truth #3 + artifacts/links above |
| IMG-04 | 24-05 (DTO contract), 24-06 (UI) | Product UI processing/failed/flagged states + review queue screen | SATISFIED | See Truth #4 + artifacts/links above |

**No orphaned requirements** — REQUIREMENTS.md's Phase-24 checkbox section (IMG-01..04, lines 56-62) lists exactly these four IDs, all `[x]`, and all four are claimed across the six 24-0X plans' `requirements:` frontmatter (24-01:[IMG-02], 24-02:[IMG-01], 24-03:[IMG-02], 24-04:[IMG-02,IMG-03], 24-05:[IMG-01,IMG-03,IMG-04], 24-06:[IMG-04]).

**⚠ Traceability table inconsistency (flag for `update_roadmap`):** `.planning/REQUIREMENTS.md` lines 127-130 (the "Traceability" table at the file foot) still read:
```
| IMG-01 | Phase 24 | 24-01 | Pending |
| IMG-02 | Phase 24 | 24-04 | Complete |
| IMG-03 | Phase 24 | 24-03 | Pending |
| IMG-04 | Phase 24 | 24-03 | Pending |
```
This is stale on two axes: (a) status — IMG-01/IMG-03/IMG-04 show "Pending" even though the checkbox list above (lines 56-62) already marks all four `[x]`, and this verification independently confirms all four are code-complete and test-proven; (b) plan mapping — IMG-01's actual delivering plans are 24-02 (model) + 24-05 (delete surface), not just 24-01 (which only delivered the normalizer/toolchain, IMG-02); IMG-03's actual plans are 24-04 (gate engine) + 24-05 (vendor-visible queue), not 24-03; IMG-04's actual plans are 24-05 (DTO contract) + 24-06 (UI), not 24-03. This is a documentation-drift artifact of the anti-false-green pattern used across the phase (each plan intentionally left its requirement un-marked in its own `requirements-completed:` frontmatter until the FULL acceptance closed in a later plan) — not a functional gap. Recommend `update_roadmap` rewrite the table to:
```
| IMG-01 | Phase 24 | 24-02, 24-05 | Complete |
| IMG-02 | Phase 24 | 24-01, 24-03, 24-04 | Complete |
| IMG-03 | Phase 24 | 24-04, 24-05 | Complete |
| IMG-04 | Phase 24 | 24-05, 24-06 | Complete |
```

### Anti-Patterns Found

None. Scanned every Phase-24 created/modified media + product + frontend file for `TBD`/`FIXME`/`XXX`/`TODO`/`HACK`/`PLACEHOLDER`/"not yet implemented"/"coming soon" — zero hits. No empty-return stubs (`return null`/`return {}`/`=> {}`) found in the reviewed files; every code path traced to a real implementation backed by a passing test.

**Documented, accepted deviations (not anti-patterns):**
- `ReviewQueue.tsx` Replace/Re-upload routes to `/dashboard/products` rather than an inline re-upload, because the 24-05 `MediaAssetDto` on the review queue carries no `productId`. This is an honest, working navigation (not a dead end) and is explicitly recorded as a follow-up in the 24-06 SUMMARY. Per the task brief, not reported as a gap.
- Live browser E2E (upload -> PENDING -> ACTIVE/FAILED, review-queue Keep/Replace at 375px with a real Keycloak vendor login) is deferred to the standard `/gsd:verify-work` manual step, requiring a REBUILD-ALL of the core-java image first (24-01's `apk add libwebp-tools` Dockerfile change is not yet baked into the running container). Per the task brief, reported below as `human_needed`, not a gap.

### Human Verification Required

#### 1. Live upload -> PENDING -> ACTIVE flow (375px, real vendor session)

**Test:** After a REBUILD-ALL of all containers (core-java picks up the 24-01 `apk add libwebp-tools` Dockerfile change), sign in as a vendor and upload a valid JPEG/PNG product image.
**Expected:** The accept returns 202 immediately; the product image shows the `AssetImage` PENDING/processing state; within a few seconds the RabbitMQ worker normalizes the upload and the product image renders the WebP derivative with `naturalWidth > 0` and no CLS jump (explicit width/height present).
**Why human:** Requires a rebuilt container image + live MinIO + a running RabbitMQ worker + a real Keycloak vendor JWT — none of which a static code/grep verifier can execute. This is the standard deferred manual-verify step (24-VALIDATION Manual-Only), not a code gap.

#### 2. FAILED + flagged review-queue flow (375px)

**Test:** Upload a non-image file renamed with an image extension (e.g. `fake.jpg` that is really a PDF) and observe `/dashboard/media/review`; separately, with the vision advisory flag turned ON and a stubbed/real low-confidence result, observe a flagged-ACTIVE row and exercise Keep and Replace.
**Expected:** The FAILED row shows the vendor-visible `failure_reason` and a working Re-upload button; the flagged row shows a "Needs review" badge with working Keep (drops the row, image stays live) and Replace (routes to `/dashboard/products`, a documented deviation) controls.
**Why human:** Same live-stack + Keycloak dependency as item 1 — this is real end-to-end browser behavior under the running pipeline (worker timing, MinIO round-trip, RLS-scoped fetch), not statically provable.

### Gaps Summary

No code-level gaps found **by goal-backward verification**. All four phase must-haves (IMG-01..04) are implemented, wired end-to-end, and proven by tests the verifier independently executed against real Postgres (Testcontainers, NOSUPERUSER RLS downgrades), a real musl/Alpine container (WebP toolchain smoke test), and real Jest suites — not merely claimed by SUMMARY.md. The only outstanding items are (1) the standard deferred live-browser E2E verification (requires a container rebuild + running compose stack + real vendor login — explicitly out of static-verification scope per the task brief) and (2) a stale REQUIREMENTS.md traceability-table status/plan-mapping inconsistency (reconciled by `update_roadmap`, see below).

### Post-Verification Remediation — Code Review (blocker fixed after this report)

The concurrent **code-review** gate (`24-REVIEW.md`) surfaced **1 BLOCKER + 5 warnings** that goal-backward verification did NOT catch — because **no test exercised the broken path** (the green-by-construction trap: passing tests prove code does what it claims, not that the product does what users need). The orchestrator independently confirmed the blocker against source before acting. The user approved a fix-now (TDD) pass; all six were fixed on `feature/phase-24-image-architecture`, each with a RED→GREEN test and an atomic commit:

| Finding | Sev | Commit | Fix + new proof |
|---------|-----|--------|-----------------|
| **CR-01** — dedup short-circuit on accept returned 202 but never created a `product_media` link → a deduplicated upload to a 2nd product silently attached no image (ref-count could never exceed 1). Direct regression-by-omission on the IMG-01 CoW/dedup goal. | BLOCKER | `f36a222` | `placeAsset` attach-or-repoint helper now shared by the worker AND the dedup branch: ACTIVE dedup shares the asset directly (ref-count++), PENDING shares the in-flight asset. `MediaDedupAttachIntegrationTest` (both products get a row → shared asset). |
| **WR-01** — a FAILED row permanently poisoned its `(tenant,sha256)` dedup slot (quarantine purged, no reprocess). | WARNING | `58283bd` | FAILED dedup hit now `reprocessFailed`: reset row→PENDING with the new placement, re-quarantine the incoming bytes, enqueue a fresh outbox event (same tx). +1 test. |
| **WR-02** — no optimistic lock → reaper could clobber a worker's ACTIVE flip back to FAILED. | WARNING | `6036e48` | `V59__media_asset_version.sql` + `@Version` on `MediaAsset`; `MediaAssetOptimisticLockIntegrationTest` proves a stale reaper write is rejected. |
| **WR-03** — Keep (`dismissFlag`) was tenant-scoped, not shop-scoped (a SHOP_MANAGER of shop A could clear shop B's flag). | WARNING | `4804fe8` | resolves owning shop and enforces `require(shopId, SHOP_MANAGER)` (null-shop → GROUP_ADMIN); `MediaKeepShopScopeIntegrationTest` denial proof. |
| **WR-04** — reject-early gate compared whole-request Content-Length to the per-file cap → false-413 on legit near-limit files. | WARNING | `3acfa41` | now compared to `jtoye.media.max-request-bytes` (6MB, synced with multipart max-request-size); Javadoc corrected. |
| **WR-05** — `thumbnailUrlFor` advertised a non-existent `_thumb.webp` for backfilled `.webp` originals. | WARNING | `cea0cb5` | only emits a thumb URL for pipeline `/media/` keys, else null (caller falls back to full url); `MediaAssetDtoMappingTest` extended. |

3 INFO items (IN-01 non-transactional MinIO writes, IN-02 `SafeImage` stale-error, IN-03 0-vs-1 `sort_order`) were recorded but deliberately left out of scope (documented, low-severity, non-blocking).

**Re-verification of the remediation (orchestrator, independent):** `:core-java:integrationTest` for the 3 new classes → `BUILD SUCCESSFUL` (and a bogus-class control run → `BUILD FAILED: No tests found`, proving the pass is non-vacuous); full media + `ProductImageDeleteIntegrationTest` integration suite + media unit tests re-run green, no regressions; `docs/metrics.json` updated (schema V59; **total_logical_invocations 1636 → 1648**) and `scripts/docs-freshness.sh` exits 0. Net: the blocker is genuinely closed with test coverage that now protects the dedup-attach path.

---
*Verified: 2026-07-23T20:30:00Z (initial); remediation appended 2026-07-23 after code-review blocker fix*
*Verifier: Claude (gsd-verifier) + orchestrator remediation*
