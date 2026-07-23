# Phase 24: Image Architecture — CoW Assets + Safe Upload Pipeline - Research

**Researched:** 2026-07-23
**Domain:** JVM image processing (WebP encode/resize/EXIF-strip) + safe async upload pipeline + copy-on-write ref-counted asset model on Spring Boot 3.5.16 / JDK 21 / PostgreSQL 15 RLS + MinIO
**Confidence:** HIGH on the model/RLS/outbox/frontend patterns (grounded in live repo precedent); MEDIUM on the WebP-encode library because the runtime image is Alpine (musl) and every WebP encoder is native — the native/musl interaction MUST be validated in-container (Wave 0 spike). One claim marked ASSUMED accordingly.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** A single **`product_media` join table** carries both primary image and ordered gallery: `(product_id FK, asset_id FK→media_asset, is_primary bool, sort_order int)`. Primary = `is_primary=true` row; gallery = `sort_order` rows. **Copy-on-write repoint = `UPDATE product_media SET asset_id=<new>` on the one affected row.** (Chosen over primary-FK + separate gallery table, and over keeping `image_url`.)
- **D-01a:** Backfill maps `products.image_url` → the `is_primary` row and `products.additional_image_urls[]` → `sort_order` rows preserving array order.
- **D-02:** Worker **transcodes ALL uploads to WebP** + a thumbnail (user-facing storefront → owns Core Web Vitals).
- **D-02a:** Max-dimension / quality / thumbnail-size are a **config-declared budget** under `jtoye.media.*`. Proposed defaults: **max_dimension=1600px, quality=80, thumbnail=400px**. Planner introduces the key; numbers are the starting budget, not literals.
- **D-02b:** Responsive multi-width srcset variants are **deferred**.
- **D-03:** Existing objects wrapped as `status=ACTIVE` media_asset rows **as-is** pointing at the current `object_key`, **WITHOUT re-running the pipeline**. sha256 computed at backfill for dedup/ref-count correctness.
- **D-03a:** **Dual-read window** — reads resolve **asset-first, fall back to `image_url`**. `image_url`/`additional_image_urls[]` columns **kept** this phase, dropped later.
- **D-03b:** Backfill migration MUST follow the RLS per-tenant `set_config` loop (bare UPDATE on FORCE-RLS table updates ZERO rows). See `trap_rls_migration_backfill` (V25→V44→V57).
- **D-04:** **FAILED** asset → vendor sees rejection + reason and **re-uploads** (no auto-retry this phase). **Content-flagged ACTIVE** asset → vendor-visible **review queue** with **Keep (dismiss flag)** or **Replace**.
- **D-04a:** CoW mints a new asset **only on worker success**, so a **replacement upload that FAILS never clobbers the live image**. (Load-bearing safety property.)
- **D-04b:** No "block product on FAILED" gate.
- **D-05 (Security):** The pipeline *is* a security control — each PLAN carries a `<threat_model>` (malicious/oversize upload, decompression-bomb, content-type spoof, EXIF PII leak, SSRF via remote-fetch if any). Mitigations: reject-early size guard + magic-byte sniff + decode-verify + EXIF strip. Routed through `/gsd:secure-phase 24`.
- **D-06 (AI agent-readiness):** Upload endpoint is a **mutating API surface** → uniform **Idempotency-Key** contract (#204 pattern) on the 202-style accept, **RFC 7807** typed errors, OpenAPI snapshot matches the live async (202 + asset id) response.
- **D-07 (Web-perf/SEO):** Owned via D-02 (WebP + config budget). Storefront rendering must **not regress** existing Product/Offer/LocalBusiness JSON-LD or image `alt` text. Dashboard review-queue UI is authenticated = SEO N/A.

### Claude's Discretion (researcher recommends; planner picks)

- `object_key` naming scheme (content-addressed by sha256 + tenant prefix is the natural fit — planner picks). → **Recommendation in §"media_asset object_key scheme".**
- Media events on the **shared `payment_event_outbox`** vs a **dedicated `media_event_outbox`**. If shared is reused, the `PaymentEventOutboxFlusher` dispatch branch + exchange bean + producer MUST land in the SAME change (`outbox_flusher_dispatch_trap`). A dedicated table sidesteps that coupling. → **Recommendation: dedicated table, §"Async worker + outbox wiring".**
- Image library for decode/normalize/WebP on the JVM (must handle decompression-bomb limits). → **Recommendation in §"Standard Stack".**
- PENDING-row reaper for crashed workers (orphan quarantine cleanup) — advisable, planner sizes it. → **Recommendation in §"Pattern: PENDING-row reaper".**

### Deferred Ideas (OUT OF SCOPE — do not research/plan)

- Responsive multi-width srcset variants (400/800/1600 WebP).
- Platform-wide stock image library / cross-tenant asset sharing (SPEC D1 later slice).
- Dropping `image_url`/`additional_image_urls[]` columns (later phase).
- Vision-provider hosting decision (Ollama :11434 conflict vs hosted) — pipeline ships behind advisory flag without it.
- Auto-retry/backoff on transient worker failure — this phase is re-upload-only.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| **IMG-01** | `media_asset` (V53) CoW model: ENABLE+FORCE RLS tenant-scoped, `sha256` unique-per-tenant dedup, products reference (never own bytes), CoW mints new asset + repoints, ref-counted physical MinIO delete at ref-count 0, `image_url` backfill dual-read | §"Architecture Patterns" (media_asset schema mirrors V52 `shop_staff` exactly; safe `current_tenant_id()` helper; `product_media` join per D-01; ref-count = `COUNT(product_media WHERE asset_id=?)`; per-tenant `set_config` backfill loop per `trap_rls_migration_backfill`) |
| **IMG-02** | Safe async pipeline: reject-early size guard (Content-Length + multipart limits + streaming) BEFORE buffering; quarantine store + PENDING row + AMQP outbox publish (202-style); worker magic-byte sniff + jpeg/png/webp allowlist + decode-verify + EXIF strip + normalize (resize/re-encode/thumbnail) storing only the derivative; delete raw on success; tenant-GUC pinned; single + BulkImportService one path | §"Standard Stack" (Scrimage + Alpine libwebp-tools), §"Pitfall: decompression bomb", §"Reject-early oversize", §"Async worker + outbox wiring" (cites `IdempotencyService.pinTenantGuc` + `OrderStateChangeListener` + `PaymentEventOutboxFlusher`) |
| **IMG-03** | Gate strictness: normalize/decode/allowlist fail → FAILED + reason; content-relevance below threshold → ACTIVE + review queue; vision stage behind advisory flag (Ollama unreliable) | §"Architecture Patterns" (stage state machine PENDING→ACTIVE/FAILED + FLAGGED sub-state); `ImageAnalysisService.isEnabled()` gates stage-6 (already flag-guarded in repo) |
| **IMG-04** | Product UI asset states: "processing" while PENDING; FAILED (reason) + content-flagged (ACTIVE) surfaced in vendor review/rejection queue | §"Frontend (IMG-04)" (SafeImage `<img>`, no next.config change; add `status` to media DTO; new dashboard review-queue screen reusing design system) |
</phase_requirements>

## Summary

The **data model, RLS, outbox, tenant-GUC-pinning, reaper, and frontend patterns for this phase already exist verbatim in the codebase** — Phase 24 is largely an exercise in *mirroring proven precedent*, not inventing. `media_asset` (V53) is a near-copy of V52 `shop_staff` (ENABLE+FORCE RLS via the safe `current_tenant_id()` helper); the async worker is a `@RabbitListener` that pins the tenant GUC exactly like `OrderStateChangeListener`; the outbox is `PaymentEventOutbox`/`Flusher` (V46); the idempotent accept reuses `IdempotencyService.execute` (V50); the PENDING-row reaper is a per-tenant `TransactionTemplate` sweep exactly like `WebhookRetentionCleanup`. Copy-on-write is a one-row `UPDATE product_media SET asset_id=…`; reference counting is `COUNT(*) FROM product_media WHERE asset_id=?`, physical MinIO delete only at 0.

**The one genuinely hard, non-precedented problem is WebP encoding on the JVM — and it collides with the deployment target.** Stock JDK `ImageIO` cannot read *or* write WebP. Every WebP encoder on the JVM is native (subprocess `cwebp` or JNI `libwebp`), and the core-java runtime image is **`eclipse-temurin:21-jre-alpine` (musl libc)** — where the glibc-linked binaries bundled by Scrimage / gotson-webp-imageio **will not exec**. The clean resolution is to install **Alpine's own musl-native `cwebp`** (`apk add --no-cache libwebp-tools`, confirmed to exist) and point **Scrimage 4.6.6** at it via `-Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin`. This keeps the small Alpine image. Decompression-bomb defense is the ImageIO *header-only* dimension read (`ImageReader.getWidth/getHeight` after `setInput`, before `read()`) with a megapixel cap — the widely-recommended JVM pattern.

**Primary recommendation:** Mirror V52 `shop_staff` for `media_asset` + a `product_media` join; publish media events on a **dedicated `media_event_outbox`** (a small flusher cloned from `PaymentEventOutboxFlusher`) to sidestep the `outbox_flusher_dispatch_trap`; encode WebP with **Scrimage 4.6.6 delegating to Alpine `libwebp-tools` (musl cwebp)** after a **Wave-0 in-container smoke test** proves the musl exec; guard decode with the ImageIO header-only megapixel cap; pin the tenant GUC in the worker via the exact `set_config` idiom already in `OrderStateChangeListener`/`IdempotencyService`.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Reject-early size guard (Content-Length + multipart) | API / Backend (controller + servlet/Tomcat) | Browser (client mirror `SERVER_MAX_BYTES`) | A 2GB body must be refused at the request thread before any buffering — a server concern; the browser cap is defence-in-depth only, never the boundary |
| Quarantine store + PENDING row + outbox publish | API / Backend (request thread) | Database (RLS row) + MinIO (quarantine prefix) | Cheap synchronous work; returns 202 immediately |
| Magic-byte sniff / decode-verify / EXIF strip / normalize / WebP encode / thumbnail | API / Backend (async worker `@RabbitListener`) | MinIO (derivative store) | CPU-bound, must be off the request thread; the only tier that can safely run native `cwebp` |
| Copy-on-write repoint + reference counting | API / Backend (service) | Database (`product_media` join) | Business invariant — never own bytes, delete at ref-count 0 |
| RLS tenant isolation of `media_asset` / `product_media` | Database (RLS policies) | API (TenantContext + GUC pin) | Project non-negotiable; RLS is the tenant wall, proven under NOSUPERUSER |
| Dedup (sha256 unique per tenant) | Database (unique index) | API (compute sha256) | Prevents duplicate bytes + backs ref-count/CoW correctness |
| Asset-state rendering (processing/FAILED/flagged) + review queue | Frontend Server + Browser (Next.js dashboard) | API (media DTO with `status`) | Vendor-facing dashboard UI; authenticated (SEO N/A) |
| Serve WebP derivative + thumbnail to storefront | Browser (`<img>`/SafeImage) + CDN/MinIO | API (asset-first dual-read resolver) | Owns storefront CWV; public (SEO applies — keep `alt`) |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.sksamuel.scrimage:scrimage-core` | 4.6.6 | Decode (via ImageIO) + resize + produce clean BufferedImage/ImmutableImage; re-encode inherently drops EXIF | Actively maintained (latest 4.6.6), Apache-2.0, idiomatic Java/Kotlin API over ImageIO; the resize + metadata-drop happen in pure JVM `[VERIFIED: Maven Central com.sksamuel.scrimage]` `[CITED: sksamuel.github.io/scrimage]` |
| `com.sksamuel.scrimage:scrimage-webp` | 4.6.6 | WebP encode (derivative + thumbnail) via `cwebp` subprocess; WebP decode via `dwebp` | Only reliable JVM WebP **encode** path that can target a system binary; bundles linux/mac/win binaries but supports overriding to a system binary — required on Alpine `[CITED: sksamuel.github.io/scrimage/webp]` |
| Alpine `libwebp-tools` (OS package, NOT a Maven dep) | (apk) | Provides **musl-native** `cwebp`/`dwebp`/`gif2webp` in the runtime container | The scrimage-webp bundled binaries are glibc-linked and will NOT exec on `eclipse-temurin:21-jre-alpine`; the apk package is musl-native `[VERIFIED: pkgs.alpinelinux.org libwebp-tools exists]` |
| `com.twelvemonkeys.imageio:imageio-webp` | 3.14.0 | **Decode-verify of WebP _inputs_** (stock ImageIO cannot read WebP at all) | Read-only WebP ImageIO plugin — lets `ImageReader` header-read + decode-verify a WebP *upload* before re-encoding. BSD-3. **Cannot encode** (read-only, confirmed) `[VERIFIED: Maven Central + haraldk wiki]` `[CITED: github.com/haraldk/TwelveMonkeys/wiki/WebP-Plugin]` |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| JDK `javax.imageio.ImageIO` / `ImageReader` | JDK 21 built-in | Header-only dimension read for the decompression-bomb megapixel cap; JPEG/PNG/GIF decode | Always — the pixel-count guard runs before any full decode `[CITED: docs.oracle.com Java Image I/O Guide]` |
| `net.coobird:thumbnailator` | 0.4.21 | *Alternative* pure-Java resize (MIT) if Scrimage's resize is not wanted | Optional — Scrimage already covers resize; listed as a lighter-weight resize-only fallback. **Cannot write WebP** `[VERIFIED: Maven Central]` |
| Existing manual magic-byte check (`StorageService.detectContentType`) | in-repo | jpeg/png/webp/gif signature allowlist | **Reuse, do not replace** — see §"Don't Hand-Roll"; Apache Tika is unnecessary weight for a 4-format allowlist |
| `IdempotencyService.execute` | in-repo (V50) | Idempotency-Key contract on the async accept (D-06) | The accept endpoint wraps its reserve+respond in `execute("media.upload", key, req, …)` |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Scrimage + Alpine libwebp-tools | **Switch base image** `eclipse-temurin:21-jre-alpine` → `eclipse-temurin:21-jre` (Ubuntu Noble, glibc) + Scrimage bundled cwebp | Simplest code (no apk, no binary-dir override) but a larger image and a base-image change touching every deploy overlay. Viable fallback if the musl smoke test fails |
| Scrimage subprocess | `com.github.gotson:webp-imageio` 0.2.2 (JNI ImageIO writer) | Integrates as a native ImageIO writer (no subprocess), but ships glibc `.so` — **same Alpine/musl problem, and no musl build published**. No advantage on Alpine `[VERIFIED: Maven Central com.github.gotson:webp-imageio 0.2.2]` |
| Scrimage/cwebp | libvips FFM bindings (e.g. `app.photofox.vips-ffm`) | Fastest + native decompression-bomb caps, but a heavy glibc native dep, Alpine-hostile, and overkill for single-derivative transcode `[ASSUMED]` |
| WebP output (D-02) | (locked — not open) | — |

**Installation (core-java `build.gradle.kts`):**
```kotlin
// WebP + image normalize pipeline (Phase 24)
implementation("com.sksamuel.scrimage:scrimage-core:4.6.6")
implementation("com.sksamuel.scrimage:scrimage-webp:4.6.6")
// Read-only WebP ImageIO plugin — lets us DECODE-VERIFY WebP uploads (stock ImageIO can't)
implementation("com.twelvemonkeys.imageio:imageio-webp:3.14.0")
implementation("com.twelvemonkeys.imageio:imageio-core:3.14.0")
```

**Dockerfile (`core-java/Dockerfile`, runtime stage — the load-bearing line):**
```dockerfile
FROM eclipse-temurin:21-jre-alpine
# musl-native cwebp/dwebp/gif2webp for the WebP transcode step (Phase 24).
# scrimage-webp's BUNDLED binaries are glibc-linked and will NOT exec on Alpine.
RUN apk add --no-cache curl libwebp-tools
# ... then run the JVM with -Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin
```

**Version verification (performed this session):**
- `scrimage-core` / `scrimage-webp` latest = **4.6.6** `[VERIFIED: repo1.maven.org/…/scrimage-webp/maven-metadata.xml]`
- `thumbnailator` latest = **0.4.21** `[VERIFIED: Maven Central]`
- `imageio-webp` (TwelveMonkeys) latest = **3.14.0**, read-only `[VERIFIED: Maven Central]`
- `webp-imageio` (gotson) latest = **0.2.2** `[VERIFIED: Maven Central]`
- Alpine `libwebp-tools` package exists (musl cwebp) `[VERIFIED: pkgs.alpinelinux.org]`

## Package Legitimacy Audit

> Ecosystem is **Maven (Gradle)**, not npm/PyPI. slopcheck targets npm/PyPI/crates and is not applicable to Maven coordinates; verification was done directly against **Maven Central** (`repo1.maven.org`) coordinates + official project docs, which is the authoritative registry for this ecosystem. No `postinstall`-equivalent risk exists for Maven jars (no install-time script execution).

| Package (groupId:artifactId) | Registry | Latest | Source Repo | Legitimacy | Disposition |
|------------------------------|----------|--------|-------------|-----------|-------------|
| `com.sksamuel.scrimage:scrimage-core` | Maven Central | 4.6.6 | github.com/sksamuel/scrimage | Widely used, active, Apache-2.0 | Approved |
| `com.sksamuel.scrimage:scrimage-webp` | Maven Central | 4.6.6 | github.com/sksamuel/scrimage | Same project | Approved |
| `com.twelvemonkeys.imageio:imageio-webp` | Maven Central | 3.14.0 | github.com/haraldk/TwelveMonkeys | Canonical ImageIO extension suite, BSD-3, last release Feb 2026 | Approved |
| `com.twelvemonkeys.imageio:imageio-core` | Maven Central | 3.14.0 | github.com/haraldk/TwelveMonkeys | Core of the above | Approved |
| `net.coobird:thumbnailator` | Maven Central | 0.4.21 | github.com/coobird/thumbnailator | Long-standing MIT resize lib | Approved (optional) |
| `libwebp-tools` (Alpine apk) | Alpine pkgs | (apk) | Google libwebp | OS-vendor package; musl-native cwebp | Approved |

**Packages removed due to slop verdict:** none.
**Packages flagged suspicious:** none. **Licenses:** Scrimage Apache-2.0; TwelveMonkeys BSD-3; Thumbnailator MIT; bundled cwebp binaries BSD (Google) — all compatible with the project.

## Architecture Patterns

### System Architecture Diagram

```
  Vendor browser (client-compresses to JPEG already — image-uploader.tsx canvas)
        │  multipart POST /api/v1/products/{id}/image  (+ Idempotency-Key header)
        ▼
  ┌─────────────────────────── REQUEST THREAD (cheap, reject-early) ───────────────────────────┐
  │ 1. Content-Length header check ──► >cap? ─► 413 RFC 7807 (BEFORE reading body)              │
  │ 2. Tomcat/Spring multipart limit (max-file-size/max-request-size) ─► MaxUploadSizeExceeded  │
  │ 3. IdempotencyService.execute("media.upload", key, req):                                    │
  │      • PUT raw bytes → MinIO  <tenant>/quarantine/<assetId>.<ext>                            │
  │      • INSERT media_asset(status=PENDING, sha256(raw), uploaded_by, tenant_id)              │
  │      • INSERT media_event_outbox(PENDING, {tenantId, assetId})   ← same tx (outbox)         │
  │ 4. return 202  { assetId, status:"PENDING" }                                                 │
  └──────────────────────────────────────────────┬──────────────────────────────────────────────┘
                                                  │  (async, after commit)
             MediaEventOutboxFlusher (@Scheduled, per-tenant tx, SKIP LOCKED, backoff)
                                                  │  AMQP publish → media.events exchange
                                                  ▼
  ┌────────────────── ASYNC WORKER  @RabbitListener(media.events) ──────────────────┐
  │  TenantContext.set(tenantId); set_config('app.current_tenant_id', …, true)  ◄── GUC PIN │
  │  re-read media_asset by id (source of truth; skip if not PENDING = idempotent)          │
  │  a. GET quarantine bytes from MinIO                                                      │
  │  b. magic-byte sniff (reuse detectContentType) → allowlist jpeg/png/webp   ─fail─► FAILED│
  │  c. ImageReader header read: width*height > MAX_MEGAPIXELS ? ─────────────► FAILED (bomb)│
  │  d. decode-to-verify (ImageIO/Scrimage read) ─────────────fail────────────► FAILED      │
  │  e. resize ≤ jtoye.media.max-dimension  (EXIF dropped by re-decode)                      │
  │  f. cwebp encode q=jtoye.media.quality → derivative;  thumbnail ≤ thumbnail-size         │
  │  g. PUT derivative + thumb → MinIO  <tenant>/media/<assetId>.webp                        │
  │  h. UPDATE media_asset SET status=ACTIVE, object_key=…, width/height/bytes               │
  │  i. DELETE quarantine object                                                             │
  │  j. (advisory flag ON) ImageAnalysisService.analyze → confidence < threshold ? flag row  │
  └──────────────────────────────────────────────┬──────────────────────────────────────────┘
                                                  ▼
   Frontend polls / re-fetches product → media DTO {status}: PENDING=spinner,
   ACTIVE=render webp derivative, FAILED=reason+re-upload, FLAGGED=review-queue badge
```

### Recommended Project Structure
```
core-java/src/main/java/uk/jtoye/core/media/          # NEW package (sibling of storage/)
├── MediaAsset.java                 # @Entity @Audited — mirrors shop_staff shape
├── MediaAssetRepository.java       # findBySha256AndTenant, refCount query
├── ProductMedia.java               # @Entity join (product_id, asset_id, is_primary, sort_order)
├── ProductMediaRepository.java     # ref-count = countByAssetId; CoW repoint
├── MediaAssetService.java          # accept(), copyOnWriteRepoint(), deleteIfUnreferenced()
├── MediaUploadController.java      # 202-style accept; @Idempotent; RFC 7807
├── MediaEventOutbox.java           # dedicated outbox row (recommended)
├── MediaEventOutboxFlusher.java    # cloned from PaymentEventOutboxFlusher
├── MediaProcessingWorker.java      # @RabbitListener(media.events) — the pipeline stages
├── MediaNormalizer.java            # Scrimage/cwebp resize+encode+thumbnail (pure transform)
├── MediaPendingReaper.java         # @Scheduled — orphan PENDING/quarantine cleanup
└── MediaProperties.java            # @ConfigurationProperties(prefix="jtoye.media")
core-java/src/main/resources/db/migration/V53__media_asset.sql   # + product_media (+ _aud)
frontend/
├── components/dashboard/media/ReviewQueue.tsx     # NEW — FAILED + flagged queue
├── components/ui/asset-image.tsx                  # status-aware wrapper over SafeImage
└── (extend) types/api.ts + types/storefront.ts    # add MediaAssetStatus to product DTO
```

### Pattern 1: `media_asset` mirrors V52 `shop_staff` EXACTLY (RLS)
**What:** ENABLE+FORCE RLS, policy gated through the **safe `current_tenant_id()` helper — NEVER the raw `::uuid` cast** (else `RlsContractTest.noPolicyUsesRawTenantGucCast` fails the build). `_aud` mirror admits NULL tenant_id.
**When to use:** the V53 migration.
```sql
-- Source: core-java/.../db/migration/V52__shop_staff.sql (verbatim pattern to mirror)
CREATE TABLE IF NOT EXISTS media_asset (
    id           UUID PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    object_key   TEXT        NOT NULL,          -- <tenant>/media/<id>.webp (ACTIVE) or quarantine key (PENDING)
    sha256       CHAR(64)    NOT NULL,          -- of the RAW upload — tenant-unique dedup
    content_type VARCHAR(32) NOT NULL,
    width        INT,
    height       INT,
    bytes        BIGINT,
    status       VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','ACTIVE','FAILED')),
    flagged      BOOLEAN     NOT NULL DEFAULT false,   -- IMG-03 content-relevance (ACTIVE + flagged)
    failure_reason TEXT,                                -- IMG-03 vendor-visible reason
    uploaded_by  UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_media_asset_tenant_sha ON media_asset (tenant_id, sha256);
ALTER TABLE media_asset ENABLE ROW LEVEL SECURITY;
ALTER TABLE media_asset FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='media_asset' AND policyname='media_asset_tenant_policy') THEN
    CREATE POLICY media_asset_tenant_policy ON media_asset
        FOR ALL
        USING      (tenant_id = current_tenant_id())   -- V51 safe helper, NOT ::uuid cast
        WITH CHECK (tenant_id = current_tenant_id());
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS product_media (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,                    -- carry tenant_id so the join is RLS-scoped too
    product_id UUID NOT NULL REFERENCES products(id),
    asset_id   UUID NOT NULL REFERENCES media_asset(id),
    is_primary BOOLEAN NOT NULL DEFAULT false,
    sort_order INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_product_media_asset ON product_media (asset_id);   -- ref-count query
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_media_primary
    ON product_media (product_id) WHERE is_primary;   -- at most one primary per product
-- ENABLE+FORCE RLS + current_tenant_id() policy, same block as above.
```
> **Sizing note:** `dietaryTags`-style flat columns stay; `media_asset` is the only sha256/dedup owner. `product_media.tenant_id` is duplicated deliberately so the join row is itself RLS-scoped (do not rely on the FK to `products` for isolation).

### Pattern 2: Copy-on-write repoint (the one-row UPDATE) + reference-counted delete
**What:** editing a shared asset never mutates bytes; it mints a new asset and repoints only the one `product_media` row. Physical MinIO delete only when no `product_media` row references the asset.
```java
// CoW repoint — D-01. asset shared by >1 product_media row → new asset, repoint only this row.
@Transactional
public void repoint(UUID productMediaRowId, UUID newAssetId) {
    productMediaRepository.updateAssetId(productMediaRowId, newAssetId);   // UPDATE ... SET asset_id=?
}

// Reference-counted delete — IMG-01. Called when a product_media row is removed/repointed away.
@Transactional
public void releaseAsset(UUID oldAssetId) {
    long refs = productMediaRepository.countByAssetId(oldAssetId);   // ref count
    if (refs == 0) {
        MediaAsset a = mediaAssetRepository.findById(oldAssetId).orElseThrow();
        storageService.deleteByKey(a.getObjectKey());   // physical MinIO delete ONLY at ref-count 0
        mediaAssetRepository.delete(a);
    }
}
```
> **CoW safety property (D-04a, a required test):** a replacement upload creates a NEW PENDING asset; the product keeps its existing ACTIVE asset until the worker succeeds and the repoint runs. A FAILED replacement never repoints → live image untouched.

### Pattern 3: Async worker tenant-GUC pin (the @Async-tenant landmine)
**What:** the worker touches FORCE-RLS tables off the request thread, so `TenantContext` + the connection GUC must be set explicitly. The repo has this exact idiom in two places — copy it.
```java
// Source: core-java/.../order/OrderStateChangeListener.java (lines 83-90) + IdempotencyService.pinTenantGuc (169-177)
@RabbitListener(queues = RabbitMQConfig.MEDIA_EVENTS_QUEUE)
@Transactional
public void onMediaEvent(MediaProcessingEvent event) {
    TenantContext.set(event.tenantId());
    entityManager.unwrap(Session.class).doWork(c -> {
        try (var s = c.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
            s.setString(1, event.tenantId().toString());
            s.execute();                         // ← GUC pin BEFORE any DB write (RLS would hide rows otherwise)
        }
    });
    try {
        // ... pipeline stages; re-read asset by id, skip if status != PENDING (idempotent redelivery)
    } finally {
        TenantContext.clear();
    }
}
```

### Anti-Patterns to Avoid
- **Raw `::uuid` cast in the RLS policy** — `RlsContractTest.noPolicyUsesRawTenantGucCast` sweeps `pg_policy` and fails the build; also reintroduces the 22P02 empty-GUC crash. Use `current_tenant_id()`.
- **Bare `UPDATE` in the V53 backfill** — updates ZERO rows as the RLS-bound migration role (NULL GUC hides all rows). Use the per-tenant `set_config` loop (`trap_rls_migration_backfill`, V44 pattern).
- **Storing the raw upload as the served object** — the stored artifact is ALWAYS the normalized WebP derivative (SPEC Q2 stage 4). Raw lives only in quarantine, deleted on success.
- **Trusting `file.getContentType()` for the stored object's Content-Type** — the current `StorageService.upload` (lines 91/116) sets the S3 object Content-Type from the *client* header. The worker must set it from the *detected/produced* type (`image/webp`).
- **Reusing the shared `payment_event_outbox` without extending the flusher dispatch in the same change** — `outbox_flusher_dispatch_trap`: a new event type poison-dead-letters. See recommendation below.
- **Bundled glibc cwebp on Alpine** — it silently fails to exec on musl. Install `libwebp-tools` and override the binary dir.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| WebP encode | A pure-Java VP8 encoder | Scrimage → `cwebp` (libwebp) | There is no robust pure-Java WebP encoder; libwebp is the reference codec |
| Image resize | Manual `Graphics2D` scaling loops | `scrimage-core` (or Thumbnailator) | Correct downscale filtering, aspect-fit, orientation handling are already solved |
| Decompression-bomb guard | Decode-then-check dimensions | `ImageReader.getWidth/getHeight` before `read()` | Header-only read avoids allocating the bomb's pixel buffer at all `[CITED: Oracle Image I/O Guide]` |
| Transactional outbox | A new bespoke queue-publish-then-mark scheme | Clone `PaymentEventOutbox`/`Flusher` (V46) | SKIP LOCKED multi-replica safety + backoff + resurrection are already hardened |
| Idempotency-Key contract | A new dedup store | `IdempotencyService.execute` (V50) | Reserve-first ON CONFLICT + 409/422 semantics + GUC pin already correct |
| PENDING/orphan reaper | A new scheduler shape | Clone `WebhookRetentionCleanup` | Per-tenant `TransactionTemplate` + GUC pin + `listTenantIds` already correct |
| Magic-byte sniff | Apache Tika (adds ~10MB + transitive deps) | Existing `StorageService.detectContentType` | A 4-signature allowlist (jpeg/png/webp/gif) is already implemented and correct; Tika is overkill |
| EXIF strip | An EXIF parser to delete tags | Decode → re-encode | Decoding to a `BufferedImage` and re-encoding drops ALL source metadata; `cwebp` omits metadata by default (no `-metadata`) |

**Key insight:** every hard sub-problem here except "WebP encode on musl" already has a shipped, test-proven precedent in this repo. The research value is in *pointing the planner at the precedent*, not designing anew. The only true unknown is the native/musl WebP exec, which must be de-risked in a container smoke test in Wave 0.

## Runtime State Inventory

> Phase 24 has a **backfill migration (V53)** and physical MinIO objects — treat it as a migration phase.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | `products.image_url` (flat string) + `products.additional_image_urls TEXT[]` hold existing MinIO URLs for all live products. `Product` is `@Audited` (Envers `products_aud`). | **Data migration (V53):** per-tenant `set_config` loop wrapping each product's `image_url`→`is_primary` `product_media` row + `additional_image_urls[]`→`sort_order` rows, each pointing at a `status=ACTIVE` `media_asset` (as-is, no re-pipeline, sha256 computed). Columns **kept** (dual-read D-03a). |
| Live service config | MinIO bucket `jtoye-images` (dev `http://localhost:9000/jtoye-images`, key layout `<tenant>/products/<productId>/<uuid>.<ext>` and seed `<tenant>/products/seed/<file>`). No config lives outside git except MinIO bucket contents themselves. | **None** for config; the backfill reads existing `object_key`s from the URLs. Seed images stay on the flat path (SPEC D1) — do NOT wrap seed images into the pipeline. |
| OS-registered state | None. No Task Scheduler / systemd / pm2 references the image subsystem. | None — verified by grep (no scheduler entries reference storage/media). |
| Secrets/env vars | S3/MinIO creds under `storage.s3.*` (`StorageProperties`); unchanged by this phase. New `jtoye.media.*` config keys are non-secret. | None — new config keys only; no secret rename. |
| Build artifacts / installed packages | core-java runtime image `eclipse-temurin:21-jre-alpine` currently has **no** WebP-capable binary. Frontend `next.config.mjs` `remotePatterns` allows `localhost:9000/jtoye-images/**`. | **Container change:** `apk add libwebp-tools` in the runtime stage + `-D…webp.binary.dir=/usr/bin`. **Rebuild ALL containers** before E2E (project rule). `next.config` needs **no** change — derivative URLs stay on the same MinIO host/path. |

**Nothing found in OS-registered state:** confirmed by grep — no scheduler/registry state references the image subsystem.

## Common Pitfalls

### Pitfall 1: WebP encoder exec-fails on Alpine (musl) — the phase's #1 risk
**What goes wrong:** Scrimage/gotson bundle glibc-linked `cwebp`; on `eclipse-temurin:21-jre-alpine` (musl) the binary throws `Error loading shared library ld-linux-x86-64.so.2` / "not found" at runtime — invisible to unit tests (which run on the dev host's glibc).
**Why it happens:** Alpine uses musl libc; the bundled binaries are dynamically linked against glibc.
**How to avoid:** `apk add --no-cache libwebp-tools` (musl-native cwebp) + `-Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin`. **De-risk in Wave 0** with an in-container smoke test that encodes one image to WebP and asserts a valid magic-byte `RIFF….WEBP` output.
**Warning signs:** green unit tests but the worker flips every asset to FAILED in the compose stack; `cwebp: not found` / loader errors in worker logs.

### Pitfall 2: Decompression bomb (a 200MB PNG that decodes to 40000×40000)
**What goes wrong:** `ImageIO.read()` on a bomb allocates `width*height*4` bytes → OOM / GC death, even though the *file* passed the size guard.
**Why it happens:** a small compressed file can encode enormous dimensions.
**How to avoid:** header-only dimension read *before* decode; reject above a megapixel cap.
```java
// Source pattern: docs.oracle.com Java Image I/O Guide — read dimensions without decoding pixels
try (ImageInputStream iis = ImageIO.createImageInputStream(quarantineStream)) {
    Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
    if (!it.hasNext()) throw new UnreadableImageException();      // → FAILED
    ImageReader reader = it.next();
    reader.setInput(iis, true, true);
    long px = (long) reader.getWidth(0) * reader.getHeight(0);
    if (px > mediaProps.getMaxMegapixels() * 1_000_000L) throw new DecompressionBombException(); // → FAILED
    // only NOW decode: reader.read(0)  (or ImageReadParam subsampling for extra safety)
}
```
**Warning signs:** worker OOM under a crafted upload; heap spikes on decode.

### Pitfall 3: `outbox_flusher_dispatch_trap` if the shared outbox is reused
**What goes wrong:** if media events ride `payment_event_outbox`, `PaymentEventOutboxFlusher.publishRow` (lines 264-275) hardcodes deserialization by exchange (`order.events`/`onboarding.events`/else→`PaymentEvent`). A media payload falls into the `else` → cast to `PaymentEvent` → `JsonProcessingException` → **poison-FAILED, dead-lettered**, and the event never processes.
**Why it happens:** the flusher's dispatch is a closed set of event families.
**How to avoid (recommended):** use a **dedicated `media_event_outbox` + `MediaEventOutboxFlusher` + `media.events` exchange** — no dispatch coupling. If reuse is chosen instead, the exchange constant + a `MEDIA_EVENTS` deserialization branch + the producer MUST land in the SAME change.
**Warning signs:** media assets stuck PENDING; `payment.outbox.dead_letter` counter climbing on media rows.

### Pitfall 4: Backfill updates zero rows (`trap_rls_migration_backfill`)
**What goes wrong:** a bare `UPDATE`/`INSERT … SELECT` in V53 runs as the RLS-bound migration role with no tenant GUC → FORCE RLS hides every row → 0 rows migrated → non-fresh DBs silently ship an empty asset model while Testcontainers (fresh DB) stays green.
**How to avoid:** loop tenants with `PERFORM set_config('app.current_tenant_id', t.id::text, false)` per tenant inside the migration (V44 pattern), then insert media_asset + product_media rows for that tenant.
**Warning signs:** live products render via `image_url` fallback only; `media_asset` empty in staging after deploy despite green CI.

### Pitfall 5: Oversize refused only after full buffering
**What goes wrong:** relying solely on `MultipartFile.getSize()` (current `StorageService.validateAndRead` line 250) means Tomcat may already have buffered/spooled the whole 2GB body.
**How to avoid:** three gates, cheapest first — (1) a `Content-Length` header check at the controller/filter returning 413 immediately; (2) `spring.servlet.multipart.max-file-size` + `max-request-size` (Tomcat streams to `fileSizeThreshold` then aborts with `MaxUploadSizeExceededException`); (3) cap Tomcat `maxSwallowSize` so a rejected body isn't drained.
```yaml
# application.yml
spring:
  servlet:
    multipart:
      max-file-size: 5MB          # keep in sync with jtoye.media + client SERVER_MAX_BYTES
      max-request-size: 6MB
server:
  tomcat:
    max-swallow-size: 2MB          # don't read the whole rejected body
```
**Warning signs:** memory spikes on large POSTs; slow 413s (body fully drained before rejection).

## Code Examples

### Reject-early Content-Length gate + RFC 7807 (D-06)
```java
// MediaUploadController — refuse BEFORE touching MultipartFile bytes.
@PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
@PreAuthorize("hasAuthority('SCOPE_catalog:write')")
public ResponseEntity<MediaAcceptDto> accept(
        @PathVariable UUID id,
        @RequestHeader(value = "Content-Length", required = false) Long contentLength,
        @RequestHeader("Idempotency-Key") String idemKey,
        @RequestParam("file") MultipartFile file) {
    if (contentLength != null && contentLength > mediaProps.getMaxUploadBytes()) {
        throw new PayloadTooLargeException(...);   // → GlobalExceptionHandler → RFC 7807 413
    }
    var outcome = idempotencyService.execute("media.upload", idemKey, /*reqHash src*/ id,
            MediaAcceptDto.class, () -> mediaAssetService.acceptQuarantineAndQueue(id, file));
    return ResponseEntity.status(202).body(outcome.value());   // 202 + { assetId, status:"PENDING" }
}
```

### WebP normalize via Scrimage delegating to system cwebp
```java
// Source: sksamuel.github.io/scrimage/webp — WebpWriter(z, q, m); binary dir set via -D at startup.
byte[] normalizeToWebp(byte[] cleanInput, int maxDim, int quality) throws IOException {
    ImmutableImage img = ImmutableImage.loader().fromBytes(cleanInput);  // ImageIO decode (EXIF dropped)
    ImmutableImage fitted = img.bound(maxDim, maxDim);                    // aspect-fit ≤ maxDim
    return fitted.bytes(WebpWriter.DEFAULT.withQ(quality).withM(6));      // → cwebp subprocess
}
// Thumbnail: fitted.bound(thumbSize, thumbSize).bytes(WebpWriter.DEFAULT.withQ(quality))
// JVM flag (Dockerfile ENTRYPOINT / JAVA_OPTS): -Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin
```

### Asset-first dual-read resolver (D-03a)
```java
// Storefront/dashboard image URL resolution during the dual-read window.
String resolveImageUrl(Product p) {
    return productMediaRepository.findPrimaryActiveDerivativeUrl(p.getId())   // media_asset ACTIVE webp
            .orElse(p.getImageUrl());                                         // fall back to flat column
}
```

### PENDING-row reaper (clone of WebhookRetentionCleanup)
```java
// Source: core-java/.../webhook/WebhookRetentionCleanup.java — per-tenant tx + GUC pin.
@Scheduled(fixedDelayString = "${jtoye.media.reaper-interval-ms:600000}")
public void reapOrphans() {
    for (UUID tenantId : listTenantIds()) {
        TenantContext.set(tenantId);
        try {
            transactionTemplate.executeWithoutResult(s -> {
                // PENDING rows older than N min with no successful worker → FAILED + delete quarantine
                mediaAssetRepository.findStalePending(cutoff()).forEach(a -> {
                    storageService.deleteByKey(a.getObjectKey());   // quarantine object
                    a.setStatus(FAILED); a.setFailureReason("processing timed out");
                });
            });
        } finally { TenantContext.clear(); }
    }
}
```

## Frontend (IMG-04)

**How images render today (verified):**
- `frontend/components/ui/safe-image.tsx` uses a **plain `<img>`** (NOT `next/image`) with an `onError` fallback. Storefront (`app/shop/[slug]/page.tsx`, `product-detail-modal.tsx`) and dashboard render via `SafeImage`.
- `frontend/components/ui/image-uploader.tsx` **already client-compresses** to JPEG on a canvas (`MAX_DIMENSION=1600`, quality ladder `0.85/0.75/0.65`, `SERVER_MAX_BYTES=5MB`). This is pre-upload defence-in-depth; the server pipeline is still authoritative.
- Product DTOs (`types/api.ts`, `types/storefront.ts`) expose `imageUrl: string|null` + `imageUrls: string[]`. No `status` field today.
- `next.config.mjs` `remotePatterns` allows `http://localhost:9000/jtoye-images/**` — **no change needed** (derivatives keep the same MinIO host/path).

**What to add for IMG-04:**
1. Extend the product/media DTO with `MediaAssetStatus` (`PENDING|ACTIVE|FAILED` + `flagged` + `failureReason`) per gallery entry.
2. A status-aware `AssetImage` wrapper over `SafeImage`: `PENDING`→skeleton/spinner ("Processing…"), `ACTIVE`→render the WebP derivative (+ thumbnail as `<img>` low-res), `FAILED`→error card with `failureReason` + "Re-upload", `ACTIVE&flagged`→a "Needs review" badge.
3. A **new dashboard review-queue screen** (`components/dashboard/media/ReviewQueue.tsx`) listing FAILED (reason + re-upload) and flagged-ACTIVE (Keep / Replace per D-04) assets — reuse the existing dashboard design system (Tailwind + Radix, orange/emerald/slate palette; the dashboard chrome already uses the blue→orange refresh per motion memory). Jest tests for processing/failed/flagged states (IMG-04 acceptance).

**SEO / web-perf (D-07):**
- **No existing storefront JSON-LD/OpenGraph found** — grep of `frontend/app/shop` returned no `generateMetadata`/`application/ld+json`/`openGraph`. So "don't regress Product/Offer/LocalBusiness JSON-LD" is currently a **null baseline**; the real, present SEO surface to preserve is the product image **`alt` text** on `SafeImage` and valid crawlable image URLs. Do not drop `alt`; keep `<img>` server-reachable.
- Web-perf: the WebP derivative + explicit width/height (from `media_asset.width/height`) reduce CLS/LCP; serve the 400px thumbnail for grid cards and the ≤1600px derivative for detail. This is the CWV mechanism D-02 buys.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Sync `StorageService.upload` trusts client Content-Type, stores raw JPEG/PNG | Async pipeline stores validated WebP derivative only | This phase | Removes content-type spoof + oversize-in-memory + EXIF-leak vectors |
| Flat `products.image_url` / `additional_image_urls[]` | `media_asset` + `product_media` CoW ref-counted | This phase | Enables safe sharing, dedup, ref-counted delete |
| Stock ImageIO (no WebP at all) | Scrimage+cwebp encode / TwelveMonkeys decode-verify | — | WebP encode is native-only on the JVM; must run on musl |

**Deprecated/outdated:**
- gotson `webp-imageio` as an Alpine encoder — ships glibc `.so`, no musl build; no advantage over `libwebp-tools` on Alpine.
- Apache Tika for this allowlist — the repo's 4-signature `detectContentType` already covers it.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Scrimage 4.6.6's `WebpWriter` will successfully invoke a **musl-native** `cwebp` from `libwebp-tools` when `com.sksamuel.scrimage.webp.binary.dir=/usr/bin` is set, inside `eclipse-temurin:21-jre-alpine` | Standard Stack / Pitfall 1 | HIGH — if false, WebP encode fails in-container; mitigation = Wave-0 smoke test; fallback = switch base image to glibc `eclipse-temurin:21-jre`. **Must be validated before committing to the library choice.** |
| A2 | Decoding to a `BufferedImage`/`ImmutableImage` and re-encoding via `cwebp` (default flags) strips all EXIF/GPS metadata | Don't Hand-Roll / EXIF | LOW — well-established; `cwebp` omits metadata unless `-metadata` is passed. Verify with an assert that output has no EXIF. |
| A3 | libvips FFM bindings are heavier/Alpine-hostile relative to cwebp | Alternatives | LOW — informational only; not the recommended path |
| A4 | A dedicated `media_event_outbox` is lower-risk than extending `PaymentEventOutboxFlusher` | Outbox wiring | LOW — both are viable; recommendation is a judgment call the planner may override, documented either way |

## Open Questions

1. **Does the musl `cwebp` path actually work in-container?** (A1)
   - What we know: `libwebp-tools` exists as an Alpine package (musl cwebp) `[VERIFIED]`; scrimage supports a system-binary override `[CITED]`.
   - What's unclear: the exact scrimage-4.6.6 ↔ system-binary invocation on musl (does it still try to extract the bundled binary first?).
   - Recommendation: **Wave-0 spike** — a single Testcontainers-or-compose smoke test that boots the Alpine image, encodes one image, asserts valid WebP output. Only then lock the library choice. If it fails, switch runtime base to `eclipse-temurin:21-jre` (glibc) and use scrimage's bundled cwebp.

2. **`_aud` mirror for `media_asset` and/or `product_media`?**
   - What we know: `Product` is `@Audited`; `shop_staff` has `_aud`; dedup stores (`idempotency_keys` V50, `processed_order_events` V47) skip `_aud`.
   - Recommendation: **`media_asset` gets `@Audited`/`media_asset_aud`** (durable domain data with a status lifecycle + `uploaded_by` — audit is valuable; use the nullable-tenant_id `_aud` RLS policy from `shop_staff_aud`). **`product_media` un-audited** (Envers on a pure join adds complexity; CoW history is captured by `media_asset` lifecycle). Planner may override — it's a discretion area.

3. **object_key: content-addressed vs asset-id-addressed?** — see next section; recommend asset-id-addressed derivative + sha256(raw) dedup column. Planner decides.

4. **Content-relevance threshold value** for the advisory flag (IMG-03)?
   - Recommendation: config-declared under `jtoye.media.vision.*` (e.g. `min-confidence: 0.35`), flag `jtoye.media.vision.enabled=false` (advisory-only default, since Ollama :11434 is unreliable). Reuse `ImageAnalysisService.isEnabled()` (already flag-guarded).

## media_asset object_key scheme (Claude's Discretion — recommendation)

**Recommended:** asset-id-addressed derivative + raw-dedup column.
- `media_asset.sha256` = SHA-256 of the **raw upload bytes** → the `(tenant_id, sha256)` unique index dedups identical uploads per tenant (an identical re-upload can short-circuit to the existing ACTIVE asset — cheap CoW/dedup). This is what IMG-01's "dedup test" proves.
- `object_key` for the ACTIVE derivative = `<tenant_id>/media/<assetId>.webp` (+ `<assetId>_thumb.webp`). Asset-id keying is immutable (a CoW edit = new asset id = new key), simplest to reason about, and needs no second hash pass over the derivative.
- Quarantine raw = `<tenant_id>/quarantine/<assetId>.<ext>`, deleted on worker success.
- **Alternative (content-addressed):** key the derivative by *its own* sha256 (`<tenant>/media/<derivativeSha>.webp`) for byte-level derivative dedup across assets. Marginal storage benefit; adds a hash pass and complicates ref-counting (multiple asset ids → one physical object). Not recommended for the first slice.

## Async worker + outbox wiring (Claude's Discretion — recommendation)

**Recommended: a dedicated `media_event_outbox` + `MediaEventOutboxFlusher` + `media.events` exchange.**
- **Rationale:** reusing `payment_event_outbox` triggers `outbox_flusher_dispatch_trap` — `PaymentEventOutboxFlusher.publishRow` deserializes by a closed set of exchanges (`order.events`/`onboarding.events`/else→`PaymentEvent`); a media payload poison-dead-letters unless the flusher dispatch branch + exchange constant + producer all land in the same change. A dedicated table + a ~120-line flusher cloned from `PaymentEventOutboxFlusher` (per-tenant `TransactionTemplate`, `FOR UPDATE SKIP LOCKED`, exponential backoff, resurrection) keeps media failures isolated from payment events and needs no edit to the payment flusher.
- **Cost:** duplicates the hardened flusher shape (mitigate by copying `PaymentEventOutboxFlusher` and its repository claim query verbatim; the logic is already unit-tested — `computeBackoffMillis` is a pure function you can reuse).
- **Payload:** trivial `{tenantId, assetId}` — the worker re-reads `media_asset` by id (DB is source of truth) and skips if `status != PENDING`, so redelivery is naturally idempotent (no separate `processed_*` table needed).
- **If the planner prefers reuse:** add `RabbitMQConfig.MEDIA_EVENTS_EXCHANGE` + a `MEDIA_EVENTS.equals(exchange)` branch in `publishRow` deserializing a `MediaProcessingEvent`, and the producer, **in the same commit** (the trap).

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| MinIO (S3) | quarantine + derivative store | ✓ (compose) | latest | AWS S3 in prod (same SDK) |
| RabbitMQ | outbox → worker | ✓ (compose) | 3.12 | none (locked infra) |
| PostgreSQL 15 RLS | media_asset / product_media | ✓ | 15-alpine | none |
| `cwebp` (musl, `libwebp-tools`) | WebP encode | ✗ **not yet in image** | — | **switch base image to glibc `eclipse-temurin:21-jre`** + bundled cwebp |
| Ollama (`llava:7b`) | IMG-03 stage-6 content-relevance | ✗ (`:11434` conflict) | — | **advisory flag defaults OFF** — pipeline ships without it (SPEC/CONTEXT) |
| Scrimage/TwelveMonkeys jars | encode/decode | ✗ not on classpath | 4.6.6 / 3.14.0 | add to build.gradle.kts |

**Missing dependencies with no fallback:** none that block — the two ✗-with-fallback items (cwebp, Ollama) both have documented fallbacks.
**Missing dependencies with fallback:** `cwebp` (add `libwebp-tools` OR switch base image); Ollama (advisory flag OFF).

## Validation Architecture

> `workflow.nyquist_validation = true` (config.json) → this section is REQUIRED.

### Test Framework
| Property | Value |
|----------|-------|
| Java framework | JUnit 5 + Spring Boot Test + **Testcontainers 1.21.3** (real Postgres + RLS), `@Testcontainers` |
| RLS-under-NOSUPERUSER precedent | `core-java/src/test/java/uk/jtoye/core/security/access/ShopStaffRlsPolicyIntegrationTest.java`, `common/idempotency/IdempotencyKeysRlsPolicyIntegrationTest.java` — role-downgrade template to clone for `MediaAssetRlsPolicyIntegrationTest` |
| RLS policy sweep | `security/RlsContractTest.java` — `everyPublicTableHasRlsAndForce` + `noPolicyUsesRawTenantGucCast` (both will now cover `media_asset`/`product_media`) |
| Frontend | Jest 29.7.0 + @testing-library/react (dashboard); Playwright 1.59.1 for E2E (375px) |
| Java quick run | `./gradlew :core-java:test --tests uk.jtoye.core.media.*` |
| Java full/RLS | `./gradlew :core-java:integrationTest` (Testcontainers) |
| Frontend quick | `cd frontend && npm test -- media` |
| Frontend build gate | `cd frontend && npm run build` (tsc — Jest does NOT type-check, per `feedback_frontend_typecheck_gate`) |

### Phase Requirements → Test Map
| Req ID | Behavior (observable proof) | Test Type | Automated Command | File Exists? |
|--------|------------|-----------|-------------------|-------------|
| IMG-01 | `media_asset` RLS hides cross-tenant rows under NOSUPERUSER role-downgrade | integration (Testcontainers) | `./gradlew :core-java:integrationTest --tests *MediaAssetRlsPolicyIntegrationTest` | ❌ Wave 0 (clone ShopStaffRls…) |
| IMG-01 | RLS sweep: media_asset/product_media FORCE + no raw `::uuid` cast | integration | `./gradlew :core-java:integrationTest --tests *RlsContractTest` | ✅ (extend fixtures) |
| IMG-01 | CoW repoint: editing a shared asset mints new asset, repoints only that product_media row; other product unchanged | integration | `…--tests *MediaCopyOnWriteIntegrationTest#repointOnlyAffectsOneRow` | ❌ Wave 0 |
| IMG-01 | ref-count-0 delete: releasing the last product_media ref triggers physical MinIO delete; a still-referenced asset is NOT deleted | integration | `…#deletesOnlyAtRefCountZero` | ❌ Wave 0 |
| IMG-01 | dedup: identical raw upload (same sha256) per tenant reuses the asset (unique index) | integration | `…#identicalUploadDedupsPerTenant` | ❌ Wave 0 |
| IMG-01 | backfill migrates image_url → is_primary product_media under per-tenant GUC (NOT zero rows) | integration (non-fresh DB fixture) | `…*MediaBackfillMigrationIntegrationTest` | ❌ Wave 0 |
| IMG-02 | oversize refused BEFORE buffering (Content-Length gate → 413 RFC 7807) | integration (MockMvc) | `…*MediaUploadControllerTest#rejectsOversizeBeforeBuffering` | ❌ Wave 0 |
| IMG-02 | magic-byte mismatch (e.g. `.jpg` that is a PDF) → allowlist veto → FAILED | unit + integration | `…*MediaProcessingWorkerTest#magicByteMismatchVetoes` | ❌ Wave 0 |
| IMG-02 | decompression-bomb (small file, huge dimensions) rejected at header read, no full decode | unit | `…*MediaNormalizerTest#bombRejectedBeforeDecode` | ❌ Wave 0 |
| IMG-02 | normalized derivative stored is WebP (RIFF/WEBP magic), raw quarantine deleted on success | integration | `…*MediaProcessingWorkerIntegrationTest#storesWebpDerivativeDeletesRaw` | ❌ Wave 0 |
| IMG-02 | worker pins tenant GUC before DB write (RLS row visible under downgraded role) | integration (NOSUPERUSER) | `…#workerPinsTenantGuc` | ❌ Wave 0 |
| IMG-02 | BulkImportService routes through the ONE pipeline (no second upload path) | integration | `…*BulkImportPipelineUnificationTest` | ❌ Wave 0 (BulkImportServiceTest exists — extend) |
| IMG-02 | **Wave-0 musl smoke:** Alpine image encodes one image to valid WebP via system cwebp | container smoke | compose/Testcontainers boot of core image + one encode assert (A1) | ❌ Wave 0 (spike) |
| IMG-03 | compress/decode fail → status FAILED + vendor-visible `failure_reason` | integration | `…*GateStrictnessTest#normalizeFailMarksFailed` | ❌ Wave 0 |
| IMG-03 | content-relevance below threshold → asset stays ACTIVE + `flagged=true` (review queue), NOT rejected | integration | `…#lowConfidenceGoesActiveAndFlagged` | ❌ Wave 0 |
| IMG-03 | vision flag OFF → advisory-only, asset ACTIVE, never flagged from vision | unit | `…#visionFlagOffIsAdvisoryOnly` | ❌ Wave 0 |
| IMG-03 | **CoW safety:** replacement that FAILS leaves the product's existing ACTIVE asset live (D-04a) | integration | `…*CowSafetyIntegrationTest#failedReplacementDoesNotClobber` | ❌ Wave 0 |
| IMG-04 | product UI: PENDING→processing, FAILED→reason+re-upload, ACTIVE&flagged→review badge | Jest | `npm test -- AssetImage ReviewQueue` | ❌ Wave 0 |
| IMG-04 | review-queue screen lists FAILED + flagged and offers Keep/Replace | Jest | `npm test -- ReviewQueue` | ❌ Wave 0 |
| D-06 | Idempotency-Key replay returns the original 202/asset id, not a duplicate; RFC 7807 on oversize/allowlist/decode | integration (MockMvc) | `…*MediaUploadIdempotencyTest` | ❌ Wave 0 (clone idempotency tests) |
| D-07 | storefront renders WebP derivative with width/height + preserved `alt` | Jest/Playwright | `npm test -- product-card` / `npx playwright test storefront` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew :core-java:test --tests uk.jtoye.core.media.*` (fast, no containers) + `npm test -- media`
- **Per wave merge:** `./gradlew :core-java:integrationTest` (Testcontainers RLS/CoW/backfill) + `npm run build`
- **Phase gate:** full suite green + the Wave-0 **musl WebP smoke test** green in the compose stack, then `docs/metrics.json` reconciled via `scripts/docs-freshness.sh` and `/gsd:verify-work` live E2E (upload → processing → ACTIVE, and a FAILED path).

### Wave 0 Gaps
- [ ] `MediaAssetRlsPolicyIntegrationTest.java` — clone `ShopStaffRlsPolicyIntegrationTest` (IMG-01, NOSUPERUSER)
- [ ] `MediaCopyOnWriteIntegrationTest.java` — CoW repoint + ref-count-0 delete + dedup (IMG-01)
- [ ] `MediaBackfillMigrationIntegrationTest.java` — non-fresh-DB backfill under per-tenant GUC (IMG-01)
- [ ] `MediaUploadControllerTest.java` — reject-before-buffer + idempotency + RFC 7807 (IMG-02/D-06)
- [ ] `MediaProcessingWorkerIntegrationTest.java` — sniff/decode-verify/EXIF-strip/normalize/GUC-pin (IMG-02)
- [ ] `MediaNormalizerTest.java` — decompression-bomb header-read guard (IMG-02)
- [ ] `GateStrictnessTest.java` / `CowSafetyIntegrationTest.java` — FAILED vs flagged + D-04a (IMG-03)
- [ ] `AssetImage`/`ReviewQueue` Jest specs (IMG-04)
- [ ] **Container smoke test** for the musl `cwebp` exec (A1) — the single most important Wave-0 item
- [ ] Extend `RlsContractTest` fixtures + `BulkImportServiceTest` for pipeline unification
- [ ] `docs/metrics.json` reconcile (new `@Test`/`it` counts) — `scripts/docs-freshness.sh` gate

## Security Domain

> D-05 mandates a `<threat_model>` per plan (routed through `/gsd:secure-phase 24`); `security_enforcement` is not disabled in config → include.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V1 Architecture | yes | The pipeline itself is the control; document trust boundary (raw = untrusted quarantine, derivative = trusted) |
| V4 Access Control | yes | RLS (`current_tenant_id()`) + `@PreAuthorize("hasAuthority('SCOPE_catalog:write')")` on the accept endpoint (mirror ProductController); ref-count delete is tenant-scoped |
| V5 Validation / Sanitization | **yes (core)** | Magic-byte sniff + jpeg/png/webp allowlist + decode-to-verify + **decompression-bomb megapixel cap** + reject-early size guard |
| V8 Data Protection | yes | **EXIF/GPS strip** on re-encode (PII leak vector); FORCE RLS on media_asset (object_key is not PII but uploaded_by is tenant data) |
| V12 Files & Resources | **yes (core)** | Never store/serve raw upload; store only the normalized derivative; quarantine prefix isolated; no path traversal in object_key (server-generated, never client-supplied) |
| V13 API | yes | RFC 7807 typed errors; Idempotency-Key contract; OpenAPI snapshot matches the 202 async response |
| V6 Cryptography | partial | SHA-256 for dedup only (not a security control) — use `MessageDigest` as in `IdempotencyService.sha256Hex` |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Oversize upload (2GB body) → memory DoS | Denial of Service | Content-Length gate + multipart limits + `max-swallow-size`, refuse before buffering (Pitfall 5) |
| Decompression bomb (small file, huge dims) | Denial of Service | ImageReader header-only dimension read + megapixel cap before `read()` (Pitfall 2) |
| Content-type spoof (`.jpg` that is HTML/SVG/PDF) | Spoofing / Tampering | Magic-byte sniff + decode-to-verify; never trust `file.getContentType()` (the exact gap in current `StorageService`) |
| EXIF/GPS PII leak in served image | Information Disclosure | Decode→re-encode drops metadata; assert output has no EXIF |
| Cross-tenant asset read/delete | Info Disclosure / Tampering | ENABLE+FORCE RLS via `current_tenant_id()`, proven under NOSUPERUSER; ref-count delete inside tenant GUC |
| Path traversal via object_key | Tampering | object_key is server-generated (`<tenant>/media/<assetId>.webp`); never interpolate client filename into the key |
| Malicious polyglot (valid JPEG + embedded script) | Tampering | Re-encode to WebP produces fresh bytes — embedded payloads do not survive transcode |
| SSRF (if any remote-fetch of image URLs) | Info Disclosure | N/A this phase (uploads only, no server-side remote fetch) — record N/A; if a URL-import path is added later, apply the Netty validated-IP resolver pattern from Phase 22 (`project_phase_22` CR-01) |

## Sources

### Primary (HIGH confidence)
- **Live repo files** (grounding, HIGH): `storage/StorageService.java`, `storage/StorageProperties.java`, `payment/PaymentEventOutbox.java` + `PaymentEventOutboxFlusher.java`, `common/idempotency/IdempotencyService.java`, `order/OrderStateChangeListener.java`, `security/TenantSetLocalAspect.java`, `webhook/WebhookRetentionCleanup.java`, `config/RabbitMQConfig.java`, `product/Product.java` + `ProductController.java` + `BulkImportService.java`, `ai/ImageAnalysisService.java`, migrations `V47`/`V50`/`V51`/`V52`, tests `security/RlsContractTest.java` + `access/ShopStaffRlsPolicyIntegrationTest.java` + `common/idempotency/IdempotencyKeysRlsPolicyIntegrationTest.java`; frontend `ui/safe-image.tsx` + `ui/image-uploader.tsx` + `next.config.mjs` + `types/api.ts`/`storefront.ts`.
- **Maven Central** (`repo1.maven.org` maven-metadata.xml): scrimage-core/webp 4.6.6, thumbnailator 0.4.21, imageio-webp 3.14.0, gotson webp-imageio 0.2.2 — versions VERIFIED.
- **Oracle Java Image I/O Guide** — `ImageReader.getWidth/getHeight` header-only decode `[CITED: docs.oracle.com/javase/8/docs/technotes/guides/imageio/spec/apps.fm3.html]`.
- **Scrimage WebP docs** `[CITED: sksamuel.github.io/scrimage/webp]` — binary-dir override + WebpWriter.
- **TwelveMonkeys WebP wiki** `[CITED: github.com/haraldk/TwelveMonkeys/wiki/WebP-Plugin]` — read-only confirmation.

### Secondary (MEDIUM confidence)
- Alpine `libwebp-tools` package existence `[VERIFIED: pkgs.alpinelinux.org]` — provides musl cwebp; the *scrimage-delegates-to-it-on-musl* interaction is A1 (needs container smoke test).
- Project memory: `trap_rls_migration_backfill`, `outbox_flusher_dispatch_trap`, `feedback_frontend_typecheck_gate`, `feedback_rebuild_containers`, `project_phase_22` (SSRF resolver), `arch_no_platform_operator`.

### Tertiary (LOW confidence — flagged)
- WebSearch summaries of gotson/webp-imageio Alpine support (no explicit musl build found) — treated as "no musl advantage", not a positive claim.

## Metadata

**Confidence breakdown:**
- Data model / RLS / CoW / backfill: **HIGH** — direct mirror of shipped V52 `shop_staff` + `trap_rls_migration_backfill`; RlsContractTest already enforces the rules.
- Outbox / worker / GUC-pin / reaper / idempotency: **HIGH** — verbatim precedents (`PaymentEventOutboxFlusher`, `OrderStateChangeListener`, `IdempotencyService`, `WebhookRetentionCleanup`).
- WebP encode library: **MEDIUM** — versions/licenses VERIFIED; the musl/cwebp runtime interaction (A1) is ASSUMED pending a Wave-0 in-container smoke test. This is the phase's primary execution risk.
- Decompression-bomb defense: **HIGH** — standard ImageIO header-read pattern, official docs.
- Frontend: **HIGH** — components/types/config read directly; JSON-LD baseline confirmed null by grep.

**Research date:** 2026-07-23
**Valid until:** ~2026-08-22 (stack stable; re-check scrimage/TwelveMonkeys versions and the musl smoke result if planning slips a month).
