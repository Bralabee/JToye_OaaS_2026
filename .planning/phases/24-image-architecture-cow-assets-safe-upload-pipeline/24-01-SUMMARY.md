---
phase: 24-image-architecture-cow-assets-safe-upload-pipeline
plan: 01
subsystem: media
tags: [webp, scrimage, libwebp-tools, imageio, alpine, musl, testcontainers, image-pipeline, security]

# Dependency graph
requires:
  - phase: 23-vendor-scoped-access
    provides: V52 shop_staff (V53 media_asset migration slot follows it; out-of-order=true already set)
provides:
  - "MediaProperties @ConfigurationProperties(jtoye.media) config budget (D-02a): max-dimension/quality/thumbnail/max-megapixels/max-upload-bytes/reaper + nested Vision block"
  - "MediaNormalizer pure-transform: magic-byte allowlist + header-only decompression-bomb guard + decode-verify + WebP derivative + WebP thumbnail + EXIF strip"
  - "WebP toolchain: scrimage-core/scrimage-webp 4.6.6 + twelvemonkeys imageio-webp/core 3.14.0 on the classpath; Dockerfile installs musl-native cwebp (apk libwebp-tools) + -Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin"
  - "DecompressionBombException + UnreadableImageException (media/exception) — the worker maps these to status=FAILED + failure_reason"
  - "PROVEN library decision: Scrimage + Alpine libwebp-tools path LOCKED (musl smoke test green); glibc-base fallback NOT needed"
  - "Reject-early multipart config (max-request-size headroom + tomcat.max-swallow-size)"
affects: [24-02 media_asset model + product_media, 24-04 async MediaProcessingWorker consumes MediaNormalizer, 24-03 gate strictness FAILED/flagged, secure-phase 24]

# Tech tracking
tech-stack:
  added:
    - "com.sksamuel.scrimage:scrimage-core:4.6.6"
    - "com.sksamuel.scrimage:scrimage-webp:4.6.6"
    - "com.twelvemonkeys.imageio:imageio-webp:3.14.0"
    - "com.twelvemonkeys.imageio:imageio-core:3.14.0"
    - "Alpine apk libwebp-tools (musl-native cwebp/dwebp) in the runtime image"
  patterns:
    - "Native-toolchain de-risk FIRST: an in-container musl smoke test GATES the WebP library choice before any pipeline wires to it (Wave-0 A1 spike)"
    - "Header-only decompression-bomb guard: ImageReader.getWidth/getHeight before any ImageIO.read() (RESEARCH Pitfall 2)"
    - "EXIF strip by decode->re-encode (cwebp omits metadata by default) — no EXIF parser"
    - "Config-declared image budget under jtoye.media.* — MediaNormalizer carries no numeric budget literal (GLOBAL_RULE_6)"

key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/media/MediaProperties.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaConfig.java
    - core-java/src/main/java/uk/jtoye/core/media/MediaNormalizer.java
    - core-java/src/main/java/uk/jtoye/core/media/exception/DecompressionBombException.java
    - core-java/src/main/java/uk/jtoye/core/media/exception/UnreadableImageException.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaNormalizerTest.java
    - core-java/src/test/java/uk/jtoye/core/media/MediaWebpMuslSmokeTest.java
  modified:
    - core-java/build.gradle.kts
    - core-java/Dockerfile
    - core-java/src/main/resources/application.yml

key-decisions:
  - "Scrimage + Alpine libwebp-tools path LOCKED after a passing in-container musl smoke test — the phase's #1 risk (A1) is retired; glibc-base fallback not needed"
  - "MediaProperties registered via a new MediaConfig @EnableConfigurationProperties (project has no @ConfigurationPropertiesScan) — mirrors StorageConfig"
  - "gif is vetoed for the STORED derivative allowlist (jpeg/png/webp only), even though StorageService's sniff also detects gif"
  - "IMG-02 stays PENDING (anti-false-green): this plan delivers only the transform layer + toolchain; the async pipeline (controller/quarantine/outbox/worker/BulkImport) lands in 24-02..24-06"

patterns-established:
  - "Wave-0 native de-risk: prove a native/musl toolchain in the actual runtime base image via Testcontainers BEFORE building on top of it"
  - "MediaNormalizer.normalize(byte[]) -> NormalizedImage{derivativeBytes,thumbnailBytes,width,height}: a DB-free/MinIO-free pure transform the async worker calls after pinning the tenant GUC"

requirements-completed: []  # IMG-02 intentionally NOT marked complete — see Decisions (anti-false-green); full pipeline acceptance unmet by this plan

# Metrics
duration: ~20min
completed: 2026-07-23
---

# Phase 24 Plan 01: WebP Transcode Toolchain + Pure-Transform Normalizer Summary

**Locked the JVM WebP toolchain against the Alpine/musl deployment target (proven in-container) and shipped MediaNormalizer — magic-byte allowlist + header-only decompression-bomb guard + decode-verify + WebP derivative/thumbnail with EXIF stripped — all driven by a config-declared jtoye.media budget.**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-07-23T17:12 (local)
- **Completed:** 2026-07-23T17:29 (local)
- **Tasks:** 3 (Task 3 was TDD: RED + GREEN)
- **Files modified:** 10 (7 created, 3 modified)

## Accomplishments
- **Retired the phase's #1 execution risk (A1).** A Testcontainers smoke test built FROM `eclipse-temurin:21-jre-alpine` + `apk add libwebp-tools` (the exact Dockerfile runtime step) proves system `cwebp` EXECs on musl and emits a valid `RIFF/WEBP` file — the glibc bundled `cwebp` would fail to exec there. **Decision: Scrimage + libwebp-tools path LOCKED**, glibc-base fallback not needed.
- **MediaNormalizer** implements async-worker stages b–f as a pure transform: (b) magic-byte allowlist jpeg/png/webp — gif vetoed for the stored derivative, never trusts the client content-type (T-24-02); (c) `ImageReader` header-only megapixel guard rejects a decompression bomb before any pixel decode (T-24-01); (d) Scrimage decode-verify; (e) `bound()` aspect-fit within max-dimension, re-decode drops EXIF/GPS (T-24-03/A2); (f) `cwebp` WebP derivative + thumbnail.
- **Config budget (D-02a)** under `jtoye.media.*` (max-dimension 1600 / quality 80 / thumbnail 400 / max-megapixels 40 / max-upload-bytes / reaper + nested Vision) — every value env-overridable; MediaNormalizer carries no numeric budget literal.
- **Reject-early multipart** config (max-request-size headroom + `server.tomcat.max-swallow-size`) so an oversize body is refused without being fully drained (RESEARCH Pitfall 5).
- **MediaNormalizerTest 5/5 green** (bomb-before-decode, magic-byte veto, WebP-within-budget derivative+thumbnail, EXIF/GPS strip, config-budget guard).

## Task Commits

Each task was committed atomically:

1. **Task 1: WebP toolchain wiring — deps + Dockerfile musl cwebp + multipart guard + MediaProperties** — `ca6231a` (feat)
2. **Task 2: Wave-0 musl WebP smoke test (A1 spike — GATES the library choice)** — `8fc3b5f` (test)
3. **Task 3: MediaNormalizer (TDD)** — `82c5311` (test, RED) → `a88cbf6` (feat, GREEN)

**Plan metadata:** _final docs commit_ (docs: complete plan)

## Files Created/Modified
- `core-java/build.gradle.kts` — added scrimage-core/scrimage-webp 4.6.6 + twelvemonkeys imageio-webp/core 3.14.0
- `core-java/Dockerfile` — `apk add libwebp-tools` (musl cwebp) + `-Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin` in JAVA_OPTS
- `core-java/src/main/resources/application.yml` — multipart max-request-size headroom, `tomcat.max-swallow-size`, `jtoye.media.*` default budget block
- `core-java/src/main/java/uk/jtoye/core/media/MediaProperties.java` — `@ConfigurationProperties(jtoye.media)` budget + nested Vision
- `core-java/src/main/java/uk/jtoye/core/media/MediaConfig.java` — `@EnableConfigurationProperties(MediaProperties.class)` registration
- `core-java/src/main/java/uk/jtoye/core/media/MediaNormalizer.java` — the sniff/bomb-guard/decode-verify/WebP-encode transform
- `core-java/src/main/java/uk/jtoye/core/media/exception/DecompressionBombException.java` — header-guard veto (→ FAILED)
- `core-java/src/main/java/uk/jtoye/core/media/exception/UnreadableImageException.java` — allowlist/decode veto (→ FAILED)
- `core-java/src/test/java/uk/jtoye/core/media/MediaNormalizerTest.java` — 5 unit tests
- `core-java/src/test/java/uk/jtoye/core/media/MediaWebpMuslSmokeTest.java` — Wave-0 musl A1 gate (Testcontainers)

## Decisions Made
- **Scrimage + libwebp-tools LOCKED** after the musl smoke test passed — no glibc base-image change.
- **MediaConfig created** to register MediaProperties (no `@ConfigurationPropertiesScan` in this project; mirrors `StorageConfig`).
- **gif vetoed** for the stored-derivative allowlist (jpeg/png/webp only).
- **IMG-02 NOT marked complete** — anti-false-green. This plan delivers the transform layer + toolchain only; IMG-02's acceptance (reject-early + quarantine + PENDING row + outbox + 202 accept + tenant-GUC-pinned worker + BulkImportService one path) is met by later plans (traceability table already maps IMG-02 → 24-02).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added MediaConfig.java to register MediaProperties**
- **Found during:** Task 1
- **Issue:** The plan lists only `MediaProperties.java` under `media/`, but the project uses explicit `@EnableConfigurationProperties` (no `@ConfigurationPropertiesScan`), so the bean would not bind without a registration point.
- **Fix:** Created `media/MediaConfig.java` with `@Configuration @EnableConfigurationProperties(MediaProperties.class)`, mirroring `storage/StorageConfig`.
- **Files modified:** core-java/src/main/java/uk/jtoye/core/media/MediaConfig.java
- **Verification:** `:core-java:compileJava` green; MediaNormalizerTest constructs MediaProperties directly and asserts the defaults (1600/80/400/40).
- **Committed in:** `ca6231a` (Task 1 commit)

**2. [Rule 3 - Blocking] Scrimage encode uses `forWriter(writer).bytes()`, not `bytes(writer)`**
- **Found during:** Task 3 (GREEN)
- **Issue:** The RESEARCH code example used `img.bytes(WebpWriter...)`, but scrimage-core 4.6.6 exposes the writer-encode via `ImmutableImage.forWriter(ImageWriter).bytes()` (verified by `javap`).
- **Fix:** Encoded via `image.forWriter(writer).bytes()`.
- **Files modified:** core-java/src/main/java/uk/jtoye/core/media/MediaNormalizer.java
- **Verification:** MediaNormalizerTest#encodesToWebpWithinBudget + #exifAndGpsStrippedFromOutput green.
- **Committed in:** `a88cbf6` (Task 3 GREEN commit)

**3. [Rule 3 - Non-blocking] Profile yml files (dev/test/prod) not modified**
- **Found during:** Task 1
- **Issue:** The Task 1 action says to mirror multipart/media values into the profile files "where those already override multipart/storage." None of `application-dev/test/prod.yml` currently override multipart or storage.
- **Fix:** Left the three profile files untouched; every `jtoye.media.*` value (and the multipart limits) is env-overridable in the base `application.yml` and inherited by all profiles, so no profile-specific override was needed.
- **Files modified:** (none)
- **Verification:** base config binds; per-environment tuning flows via env vars.
- **Committed in:** n/a

---

**Total deviations:** 3 (2 blocking, 1 non-blocking) — all necessary for correct binding/encode or a conditional that resolved to "no change". No scope creep.

## Issues Encountered
- **Gradle wrapper location:** the plan's `<verify>` commands say `cd core-java && ./gradlew ...`, but the wrapper is at the repo root. Ran all gradle commands from the repo root as `./gradlew :core-java:...`. No functional impact.
- **Testcontainers `Container` import collision** in the smoke test (JUnit `@Container` annotation vs the `Container` interface for `ExecResult`) — resolved by referencing `GenericContainer.ExecResult` (inherited nested type) and dropping the interface import.

## Known Stubs
None — MediaNormalizer is fully implemented and proven by tests; no placeholder/empty-return paths.

## Self-Check: PASSED

- All 7 created files present on disk (MediaProperties, MediaConfig, MediaNormalizer, DecompressionBombException, UnreadableImageException, MediaNormalizerTest, MediaWebpMuslSmokeTest).
- All 4 task commits present in git: `ca6231a`, `8fc3b5f`, `82c5311`, `a88cbf6`.
- `:core-java:compileJava` green; MediaNormalizerTest 5/5; MediaWebpMuslSmokeTest 1/1 (musl WebP encode proven).

## Next Phase Readiness
- **The WebP toolchain is proven on the deployment target** — 24-02 (media_asset/product_media model) and 24-04 (async MediaProcessingWorker) can build on `MediaNormalizer.normalize(byte[])` without re-litigating the encoder choice.
- The normalizer is DB-free/MinIO-free: the worker calls it after pinning the tenant GUC, then persists the derivative + thumbnail.
- **Blocker/note for E2E:** the runtime image MUST be rebuilt (`apk add libwebp-tools` + the new `-D` flag) before any compose/E2E run — bundled cwebp will not exec on Alpine (project rebuild-all rule).
- IMG-02 remains PENDING until the full async pipeline lands.

---
*Phase: 24-image-architecture-cow-assets-safe-upload-pipeline*
*Completed: 2026-07-23*
