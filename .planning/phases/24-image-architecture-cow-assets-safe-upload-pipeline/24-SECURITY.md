---
phase: 24-image-architecture-cow-assets-safe-upload-pipeline
audited: 2026-07-23
auditor: gsd-security-auditor
asvs_level: L1
verdict: SECURED
threats_total: 28
threats_closed: 28
threats_open: 0
block_on: high
branch_head: ddf8ea3 (feature/phase-24-image-architecture)
register_authored_at_plan_time: true
---

# Phase 24 — Image Architecture (CoW Assets + Safe Upload Pipeline): Security Audit

**Verdict:** SECURED — every declared mitigation is present in the implemented code at HEAD.
**Threats closed:** 28 / 28 (25 `mitigate` verified in code, 3 `accept` with documented rationale).
**Open (BLOCKER):** 0.
**ASVS level:** L1. **block_on:** high.

This audit verified each threat by its declared disposition against the CURRENT code
(post gap-fix), not against documentation or intent. All six gap-fix commits
(`f36a222` CR-01, `58283bd` WR-01, `6036e48` WR-02, `4804fe8` WR-03, `3acfa41` WR-04,
`cea0cb5` WR-05) are present at HEAD and the working tree is clean. The two threat
surfaces called out as strengthened (T-24-09 oversize gate, T-24-20 flag-dismiss) were
verified against their hardened form.

## Threat Verification (mitigate)

| Threat ID | Category | Evidence (file:line) |
|-----------|----------|----------------------|
| T-24-01 | DoS / decode bomb | `MediaNormalizer.java:83-84,115-142` — `guardAgainstDecompressionBomb` reads header dimensions via `ImageReader.getWidth/getHeight(0)` and throws `DecompressionBombException` above `maxMegapixels*1_000_000` BEFORE `ImmutableImage.loader().fromBytes` decode |
| T-24-02 | Spoofing/Tampering / format gate | `MediaNormalizer.java:76-92,154-168` — magic-byte `detectContentType` (JPEG/PNG/RIFF+WEBP) + `ALLOWED_INPUT_TYPES` allowlist veto + decode-to-verify; never reads `file.getContentType()` |
| T-24-03 | Info Disclosure / EXIF | `MediaNormalizer.java:87-102` — decode→`bound`→WebP re-encode drops source EXIF/GPS; `MediaNormalizerTest` EXIF-strip assertion (ran green per 24-VERIFICATION) |
| T-24-04 | Tampering / polyglot | `MediaNormalizer.java:97-102,144-151` — `WebpWriter` produces fresh bytes; embedded payloads do not survive transcode |
| T-24-05 | Info Disclosure/Tampering / cross-tenant | `V53__media_asset.sql:76-77,107-108,145-146` ENABLE+FORCE RLS on media_asset/product_media/media_asset_aud via `current_tenant_id()` (:82-83,113-114,151-152); `MediaAssetService.releaseAsset:300-311` ref-count-0 delete inside tenant GUC; proven under NOSUPERUSER (`MediaAssetRlsPolicyIntegrationTest`) |
| T-24-06 | Tampering / path traversal | Server-generated keys only: quarantine `MediaAssetService.java:154` (`<tenant>/quarantine/<sha256>.<ext>`), derivative `MediaProcessingWorker.java:151-152` (`<tenant>/media/<id>.webp`); `V53:187-192` backfill parses the existing trusted key, never a client filename |
| T-24-07 | DoS (data-integrity) / backfill zero-rows | `V53__media_asset.sql:173-176,255` per-tenant `set_config('app.current_tenant_id', t.id::text, true)` loop (V44 pattern); `MediaBackfillMigrationIntegrationTest` RED-provable against a bare UPDATE |
| T-24-08 | EoP / raw ::uuid cast | `RlsContractTest.java:242 noPolicyUsesRawTenantGucCast` dynamic `pg_policy` sweep + `:125 everyPublicTableHasRlsAndForce` `pg_class` sweep (`:35` "not a hardcoded list") + `:189` explicit media sentinel; V53/V58 policies use `current_tenant_id()` only (the 3 `::uuid` in V53 are comment text) |
| T-24-09 | DoS / oversize body | `MediaUploadController.java:109-114` Content-Length vs `getMaxRequestBytes()` (WR-04 fix: request budget, not per-file cap); `application.yml:11-12` multipart max-file 5MB/max-request 6MB + `:244 server.tomcat.max-swallow-size: 2MB`; `MediaProperties.java:52,90` maxRequestBytes=6MB; `GlobalExceptionHandler:409-414 handleMaxUploadSizeExceeded` 413 |
| T-24-10 | Tampering/Repudiation / replay | `MediaUploadController.java:126-129` `idempotencyService.execute("media.upload", idemKey, request…)`; fingerprint includes sha256+placement → same-key/diff-body = 422; `MediaUploadIdempotencyTest` |
| T-24-11 | EoP / cross-scope upload | `MediaUploadController.java:78` `@PreAuthorize("hasAuthority('SCOPE_catalog:write')")` + `MediaAssetService.java:111` `shopAccessService.require(shopId, SHOP_MANAGER)` (VSA-02 preserved) + RLS scopes inserted rows |
| T-24-12 | Info Disclosure / prose errors | `GlobalExceptionHandler.java:396-399` (413 `payload-too-large`), `:409-414` (413 max-upload), `:368-371` (409 idempotency-conflict), `:382-385` (422 idempotency-mismatch) — all typed RFC 7807 ProblemDetail; generic handler `:419-424` returns no stack trace |
| T-24-13 | Spoofing/Tampering / content-type spoof | `MediaProcessingWorker.java:138-147` calls `MediaNormalizer.normalize` (magic-byte sniff+allowlist+decode-verify); mismatch → `fail()` FAILED (`:239-245`) |
| T-24-14 | DoS / bomb at worker | `MediaProcessingWorker.java:139-141` maps `DecompressionBombException` (from the normalizer header guard) → `fail()`; no OOM |
| T-24-15 | Info Disclosure / EXIF PII | Worker stores only `normalized.derivativeBytes()` (`:153-157`); metadata dropped by the normalizer re-encode |
| T-24-16 | Tampering / polyglot | Worker stores fresh WebP bytes only (`:151-157`) |
| T-24-17 | Info Disclosure/Tampering / worker cross-tenant write | `MediaProcessingWorker.java:95-102` `TenantContext.set` + `set_config('app.current_tenant_id', ?, true)` on the session connection BEFORE any read/write; `finally clear` `:118-120`; `#workerPinsTenantGuc` under NOSUPERUSER |
| T-24-18 | Tampering / raw served | `MediaProcessingWorker.java:151-157` stored object is always the WebP derivative; raw quarantine deleted on success `:177` (and on FAILED `:243`) |
| T-24-19 | Info Disclosure / cross-tenant queue read | `MediaController.java:65` → `MediaAssetService.reviewQueue:332-337` → `findReviewQueue` scoped by RLS request-thread GUC; `MediaReviewQueueIntegrationTest` asserts foreign rows invisible |
| T-24-20 | Tampering / cross-tenant flag-dismiss | `MediaAssetService.dismissFlag:356-364` — RLS `findById` (foreign → 404, no oracle) AND (WR-03 hardening) `shopAccessService.require(resolveOwningShopId(asset), SHOP_MANAGER)` `:359,372-386`; null-shop → GROUP_ADMIN rule |
| T-24-22 | EoP / unauthenticated queue/keep | `SecurityConfig.java:154` `anyRequest().authenticated()` (`/api/v1/media` not in permitAll) covers the GET; `MediaController.java:69` `@PreAuthorize("hasAuthority('SCOPE_catalog:write')")` gates keep |
| T-24-23 | Info Disclosure / browser queue data | `media-api.ts:27-30` fetch `/api/v1/media/review-queue` via authed `apiClient`; `ReviewQueue.tsx:71` renders only API-returned rows (RLS-isolated backend) |
| T-24-24 | Tampering / Replace without idempotency | `image-uploader.tsx:370` sends `Idempotency-Key: makeIdempotencyKey()` on every accept POST; ReviewQueue Replace routes to the uploader (`ReviewQueue.tsx:206`) |
| T-24-25 | Info Disclosure (SEO/web-perf) / storefront render | `asset-image.tsx:111` preserves required `alt` prop through to `SafeImage`; `:114-115` sets explicit `width`/`height` on the WebP derivative (CLS/LCP) |
| T-24-26 | Tampering/Info Disclosure / orphaning delete | `ProductService.java:338→341→417-422` `removeImage`→`releasePrimaryAsset`→`releaseAsset` (ref-count-0); `:391→399→434-444` `removeAdditionalImage`→`releaseGalleryAssetAt`→`releaseAsset`; both keep `shopAccessService.require(SHOP_MANAGER)`; `ProductImageDeleteIntegrationTest` proves release-at-0 vs preserve-when-shared |

## Accepted Risks Log (accept)

| Threat ID | Category | Accepted rationale (verified) |
|-----------|----------|-------------------------------|
| T-24-SC | Tampering (supply chain) / scrimage + twelvemonkeys + apk libwebp-tools | 24-01 PLAN §Package Legitimacy Audit: all Maven coordinates verified on Maven Central (Apache-2.0/BSD-3/MIT, all `Approved`); Maven has no postinstall-script risk; libwebp-tools is the Google/Alpine OS-vendor package. Ecosystem is Maven (not npm/pip/cargo) → no blocking checkpoint required. Accepted at plan time. |
| T-24-SSRF | Info Disclosure / server-side remote fetch | 24-03 PLAN: N/A this phase — uploads only, no server-side URL-fetch path exists. Code confirms: `MediaAssetService.acceptQuarantineAndQueue` and `MediaProcessingWorker` consume the uploaded `MultipartFile`/quarantine bytes only; no outbound URL fetch. If a URL-import path is later added, apply the Phase-22 Netty validated-IP resolver. Accepted. |
| T-24-21 | Info Disclosure / failure_reason leaking internal detail | 24-05 PLAN: `failure_reason` is a vendor-facing message set by the worker, tenant-scoped so only the owner sees it. Code confirms `MediaProcessingWorker.fail:239-252` writes vendor-facing strings (e.g. "Upload is not an allowed image type", "…exceed the megapixel cap", "Could not read the quarantined upload") and truncates to 500 chars — never a raw stack trace. Accepted. |

## Unregistered Flags

None. The SUMMARY files carry no `## Threat Flags` section; each SUMMARY's
`## Threat Model Coverage` entry maps to an existing registered threat ID (T-24-01..26,
SC, SSRF). No new attack surface appeared during implementation without a threat mapping.

## Notes

- Register authored at plan time (`register_authored_at_plan_time: true`) — this audit
  verified declared mitigations, not new-vulnerability discovery.
- The concurrent code-review gate (`24-REVIEW.md`) found 1 BLOCKER + 5 warnings; all six
  are fixed at HEAD and independently confirmed here in the current code (CR-01 dedup-attach
  via `MediaAssetService.placeAsset:278-293`; WR-01 `reprocessFailed:190-220`; WR-02
  `V59__media_asset_version.sql` `@Version`; WR-03 `dismissFlag` shop-scope; WR-04 request-budget
  gate; WR-05 `thumbnailKeyFor:441-448` `/media/`-only).
- 3 INFO items (IN-01 non-transactional MinIO writes, IN-02 SafeImage stale-error, IN-03
  0-vs-1 sort_order) are documented low-severity, non-blocking, and out of the L1 register scope.
- Implementation files were treated as READ-ONLY; only this SECURITY.md was written.
