---
phase: 24
slug: image-architecture-cow-assets-safe-upload-pipeline
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-23
---

# Phase 24 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Derived from `24-RESEARCH.md` §Validation Architecture (requirement→test map) + §Security Domain (threat model).

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework (Java)** | JUnit 5 + Spring Boot Test + Testcontainers 1.21.3 (real Postgres + RLS), `@Testcontainers` |
| **Framework (frontend)** | Jest 29.7.0 + @testing-library/react; Playwright 1.59.1 (E2E, 375px) |
| **RLS-under-NOSUPERUSER precedent** | `core-java/src/test/java/uk/jtoye/core/security/access/ShopStaffRlsPolicyIntegrationTest.java` + `common/idempotency/IdempotencyKeysRlsPolicyIntegrationTest.java` (role-downgrade template to clone for `MediaAssetRlsPolicyIntegrationTest`) |
| **RLS policy sweep** | `core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java` — `everyPublicTableHasRlsAndForce` + `noPolicyUsesRawTenantGucCast` (must now cover `media_asset`/`product_media`) |
| **Java quick run** | `./gradlew :core-java:test --tests uk.jtoye.core.media.*` (no containers) |
| **Java full/RLS suite** | `./gradlew :core-java:integrationTest` (Testcontainers) |
| **Frontend quick** | `cd frontend && npm test -- media` |
| **Frontend build gate** | `cd frontend && npm run build` (tsc — Jest does NOT type-check, per `feedback_frontend_typecheck_gate`) |
| **Estimated runtime** | quick ~30s; integrationTest ~34 min (Testcontainers) |

---

## Sampling Rate

- **After every task commit:** `./gradlew :core-java:test --tests uk.jtoye.core.media.*` + `npm test -- media`
- **After every plan wave:** `./gradlew :core-java:integrationTest` (Testcontainers RLS/CoW/backfill) + `npm run build`
- **Before `/gsd:verify-work`:** full suite green + the **Wave-0 musl WebP smoke test** green in the compose stack, then `docs/metrics.json` reconciled via `scripts/docs-freshness.sh`
- **Max feedback latency:** ~30s (quick) between commits

---

## Per-Task / Per-Requirement Verification Map

| Requirement | Behavior (observable proof) | Threat Ref | Test Type | Automated Command | File Exists | Status |
|-------------|-----------------------------|------------|-----------|-------------------|-------------|--------|
| IMG-01 | `media_asset` RLS hides cross-tenant rows under NOSUPERUSER role-downgrade | T-cross-tenant | integration | `:core-java:integrationTest --tests *MediaAssetRlsPolicyIntegrationTest` | ❌ W0 (clone ShopStaffRls…) | ⬜ pending |
| IMG-01 | RLS sweep: media_asset/product_media FORCE + no raw `::uuid` cast | T-cross-tenant | integration | `…--tests *RlsContractTest` | ✅ (extend fixtures) | ⬜ pending |
| IMG-01 | CoW repoint: editing a shared asset mints a new asset, repoints only that `product_media` row; other product unchanged | — | integration | `…*MediaCopyOnWriteIntegrationTest#repointOnlyAffectsOneRow` | ❌ W0 | ⬜ pending |
| IMG-01 | ref-count-0 delete: last-ref release → physical MinIO delete; still-referenced asset NOT deleted | — | integration | `…#deletesOnlyAtRefCountZero` | ❌ W0 | ⬜ pending |
| IMG-01 | dedup: identical raw upload (same sha256) per tenant reuses the asset | — | integration | `…#identicalUploadDedupsPerTenant` | ❌ W0 | ⬜ pending |
| IMG-01 | backfill migrates `image_url` → `is_primary` `product_media` under per-tenant GUC (NOT zero rows) | — | integration (non-fresh DB) | `…*MediaBackfillMigrationIntegrationTest` | ❌ W0 | ⬜ pending |
| IMG-02 | oversize refused BEFORE buffering (Content-Length gate → 413 RFC 7807) | T-oversize-DoS | integration (MockMvc) | `…*MediaUploadControllerTest#rejectsOversizeBeforeBuffering` | ❌ W0 | ⬜ pending |
| IMG-02 | magic-byte mismatch (`.jpg` that is a PDF) → allowlist veto → FAILED | T-ctype-spoof | unit + integration | `…*MediaProcessingWorkerTest#magicByteMismatchVetoes` | ❌ W0 | ⬜ pending |
| IMG-02 | decompression-bomb (small file, huge dims) rejected at header read, no full decode | T-decomp-bomb | unit | `…*MediaNormalizerTest#bombRejectedBeforeDecode` | ❌ W0 | ⬜ pending |
| IMG-02 | normalized derivative stored is WebP (RIFF/WEBP magic), raw quarantine deleted on success | T-raw-serve | integration | `…*MediaProcessingWorkerIntegrationTest#storesWebpDerivativeDeletesRaw` | ❌ W0 | ⬜ pending |
| IMG-02 | worker pins tenant GUC before DB write (RLS row visible under downgraded role) | T-cross-tenant | integration (NOSUPERUSER) | `…#workerPinsTenantGuc` | ❌ W0 | ⬜ pending |
| IMG-02 | BulkImportService routes through the ONE pipeline (no second upload path) | — | integration | `…*BulkImportPipelineUnificationTest` | ❌ W0 (extend BulkImportServiceTest) | ⬜ pending |
| IMG-02 | **Wave-0 musl smoke:** Alpine image encodes one image to valid WebP via system `cwebp` (A1) | — | container smoke | compose/Testcontainers boot of core image + one encode assert | ❌ W0 (spike) | ⬜ pending |
| IMG-03 | compress/decode fail → status FAILED + vendor-visible `failure_reason` | — | integration | `…*GateStrictnessTest#normalizeFailMarksFailed` | ❌ W0 | ⬜ pending |
| IMG-03 | content-relevance below threshold → asset stays ACTIVE + `flagged=true` (review queue), NOT rejected | — | integration | `…#lowConfidenceGoesActiveAndFlagged` | ❌ W0 | ⬜ pending |
| IMG-03 | vision flag OFF → advisory-only, asset ACTIVE, never flagged from vision | — | unit | `…#visionFlagOffIsAdvisoryOnly` | ❌ W0 | ⬜ pending |
| IMG-03 | **CoW safety:** replacement that FAILS leaves the product's existing ACTIVE asset live (D-04a) | — | integration | `…*CowSafetyIntegrationTest#failedReplacementDoesNotClobber` | ❌ W0 | ⬜ pending |
| IMG-04 | product UI: PENDING→processing, FAILED→reason+re-upload, ACTIVE&flagged→review badge | — | Jest | `npm test -- AssetImage ReviewQueue` | ❌ W0 | ⬜ pending |
| IMG-04 | review-queue screen lists FAILED + flagged and offers Keep/Replace | — | Jest | `npm test -- ReviewQueue` | ❌ W0 | ⬜ pending |
| D-06 | Idempotency-Key replay returns the original 202/asset id (no duplicate); RFC 7807 on oversize/allowlist/decode | T-ctype-spoof | integration (MockMvc) | `…*MediaUploadIdempotencyTest` | ❌ W0 (clone idempotency tests) | ⬜ pending |
| D-07 | storefront renders WebP derivative with width/height + preserved `alt` | — | Jest/Playwright | `npm test -- product-card` / `npx playwright test storefront` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] **Container smoke test** for the musl `cwebp` exec (A1) — **the single most important Wave-0 item; gates the WebP library choice** (else switch base image to glibc `eclipse-temurin:21-jre`)
- [ ] `MediaAssetRlsPolicyIntegrationTest.java` — clone `ShopStaffRlsPolicyIntegrationTest` (IMG-01, NOSUPERUSER)
- [ ] `MediaCopyOnWriteIntegrationTest.java` — CoW repoint + ref-count-0 delete + dedup (IMG-01)
- [ ] `MediaBackfillMigrationIntegrationTest.java` — non-fresh-DB backfill under per-tenant GUC (IMG-01)
- [ ] `MediaUploadControllerTest.java` — reject-before-buffer + idempotency + RFC 7807 (IMG-02/D-06)
- [ ] `MediaProcessingWorkerIntegrationTest.java` — sniff/decode-verify/EXIF-strip/normalize/GUC-pin (IMG-02)
- [ ] `MediaNormalizerTest.java` — decompression-bomb header-read guard (IMG-02)
- [ ] `GateStrictnessTest.java` / `CowSafetyIntegrationTest.java` — FAILED vs flagged + D-04a (IMG-03)
- [ ] `AssetImage` / `ReviewQueue` Jest specs (IMG-04)
- [ ] Extend `RlsContractTest` fixtures + `BulkImportServiceTest` for pipeline unification
- [ ] `docs/metrics.json` reconcile (new `@Test`/`it` counts) — `scripts/docs-freshness.sh` gate

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Live upload → processing → ACTIVE, and a real FAILED path, in the running Compose stack | IMG-02/03/04 | Requires the full async stack (MinIO + RabbitMQ worker + core) + real Keycloak vendor login; Testcontainers proves units but not the deployed pipeline (`feedback_rebuild_containers`) | `/gsd:verify-work` — log in as `admin-user`/`JtoyeDev!2026`, upload a valid image (assert processing→ACTIVE + WebP served) and an oversize/spoofed file (assert FAILED + reason) at 375px |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references (esp. the A1 musl WebP smoke)
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s (quick)
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
