# Phase 24: Image Architecture — CoW Assets + Safe Upload Pipeline - Pattern Map

**Mapped:** 2026-07-23
**Files analyzed:** 27 (18 backend + 5 frontend + config/build/docker + tests)
**Analogs found:** 24 / 27 (near-total precedent — this phase is "mirror the shipped V52/V46/V50 patterns"; the only genuine no-analog is the WebP normalizer)

All analog line numbers below are from live `feature/phase-23-vendor-scoped-access` HEAD and were read this session. RESEARCH.md already named these analogs; this map verifies each against the repo and pins the exact excerpt the planner copies from.

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `db/migration/V53__media_asset.sql` (NEW) | migration | CRUD + backfill | `db/migration/V52__shop_staff.sql` (RLS) + `V44__fts_leakproof_and_vector_backfill.sql` (per-tenant loop) | exact |
| `media/MediaAsset.java` (NEW) | model | CRUD | `security/access/ShopStaff.java` (@Entity @Audited shape) + `payment/PaymentEventOutbox.java` (status enum) | exact |
| `media/MediaAssetRepository.java` (NEW) | repository | CRUD | `security/access/ShopStaffRepository.java` (tenant-scoped + ON CONFLICT insert) | exact |
| `media/ProductMedia.java` (NEW) | model | CRUD (join) | `security/access/ShopStaff.java` (entity shape, tenant_id carried) | role-match |
| `media/ProductMediaRepository.java` (NEW) | repository | CRUD | `security/access/ShopStaffRepository.java` (countBy… ref-count; @Modifying UPDATE repoint) | exact |
| `media/MediaAssetService.java` (NEW) | service | CRUD + transform | RESEARCH Pattern 2 + `common/idempotency/IdempotencyService.java` (accept) | role-match |
| `media/MediaUploadController.java` (NEW) | controller | request-response | `product/ProductController.java` (`/{id}/image`, @PreAuthorize, @Operation) | exact |
| `media/MediaEventOutbox.java` (NEW) | model | event-driven (outbox) | `payment/PaymentEventOutbox.java` | exact (near-clone) |
| `media/MediaEventOutboxRepository.java` (NEW) | repository | event-driven | `payment/PaymentEventOutboxRepository.java` (SKIP LOCKED claim + resurrect) | exact (near-clone) |
| `media/MediaEventOutboxFlusher.java` (NEW) | service | event-driven (pub) | `payment/PaymentEventOutboxFlusher.java` | exact (near-clone) |
| `media/MediaProcessingWorker.java` (NEW) | service | event-driven (consume) | `order/OrderStateChangeListener.java` (@RabbitListener + GUC pin) | exact |
| `media/MediaNormalizer.java` (NEW) | utility | transform (file-I/O) | `storage/StorageService.java` `detectContentType` (magic-byte only); Scrimage/cwebp is NEW | **partial (no analog for encode)** |
| `media/MediaProperties.java` (NEW) | config | — | `storage/StorageProperties.java` | exact |
| `media/MediaPendingReaper.java` (NEW) | service (scheduled) | batch | `webhook/WebhookRetentionCleanup.java` | exact (near-clone) |
| `config/RabbitMQConfig.java` (MODIFY) | config | event-driven | itself (existing exchange/queue/binding beans, lines 64–176) | exact |
| `common/GlobalExceptionHandler.java` (MODIFY) | middleware | request-response | itself (add 413 handler; lines 202–213 `ResponseStatusException` already covers it) | exact |
| `application*.yml` (MODIFY) | config | — | RESEARCH §Pitfall 5 (multipart limits) + `StorageProperties` (`storage.max-file-size-bytes`) | exact |
| `core-java/Dockerfile` (MODIFY) | config | — | RESEARCH §Dockerfile (`apk add libwebp-tools`) | (new build step) |
| `core-java/build.gradle.kts` (MODIFY) | config | — | RESEARCH §Standard Stack install block | (new deps) |
| `product/BulkImportService.java` (MODIFY) | service | batch | itself + `MediaAssetService` (route to ONE pipeline, IMG-02) | role-match |
| `product/ProductService.java` / mapper (MODIFY) | service | CRUD | RESEARCH "asset-first dual-read resolver" code example (D-03a) | role-match |
| `frontend/components/ui/asset-image.tsx` (NEW) | component | request-response | `frontend/components/ui/safe-image.tsx` | exact |
| `frontend/components/dashboard/media/ReviewQueue.tsx` (NEW) | component | CRUD | `frontend/app/dashboard/onboarding/approvals/page.tsx` (queue + dialog + Keep/Replace) | exact |
| `frontend/types/api.ts` (MODIFY) | model | — | itself (`Product` interface line 55, `imageUrl` line 64) | exact |
| `frontend/types/storefront.ts` (MODIFY) | model | — | itself (`PublicProduct` line 24, `imageUrl`/`imageUrls` lines 28–29) | exact |
| `frontend/components/ui/image-uploader.tsx` (MODIFY) | component | request-response | itself (add 202/PENDING handling; `SERVER_MAX_BYTES` line 25) | exact |
| Tests (Wave 0, ~11 files) | test | — | `ShopStaffRlsPolicyIntegrationTest.java` + idempotency/outbox tests | exact |

---

## Shared Patterns

These cross-cut every backend media file. Apply verbatim rather than re-deriving.

### Tenant-GUC pin before any FORCE-RLS write (the @Async-tenant landmine)
**Source:** `order/OrderStateChangeListener.java` lines 83–90; identical idiom in `common/idempotency/IdempotencyService.java` `pinTenantGuc` lines 169–177 and `webhook/WebhookRetentionCleanup.java` lines 91–100.
**Apply to:** `MediaProcessingWorker` (@RabbitListener), `MediaEventOutboxFlusher` (per-tenant tx), `MediaPendingReaper` (per-tenant tx). Any code that touches `media_asset`/`product_media` off the request thread.
```java
// OrderStateChangeListener.java:83-90 — set ThreadLocal AND the connection GUC
TenantContext.set(event.tenantId());
Session session = entityManager.unwrap(Session.class);
session.doWork(connection -> {
    try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
        stmt.setString(1, event.tenantId().toString());
        stmt.execute();
    }
});
// ... work ... finally { TenantContext.clear(); }
```
> The worker MUST re-read `media_asset` by id inside this GUC and **skip if `status != PENDING`** — that is how redelivery stays idempotent (no `processed_*` table needed; DB is source of truth). Same "0-rows-means-duplicate, skip side effects" shape as `OrderStateChangeListener` lines 95–103.

### RLS policy via the SAFE `current_tenant_id()` helper — NEVER the raw `::uuid` cast
**Source:** `db/migration/V52__shop_staff.sql` lines 46–55 (table), 75–84 (`_aud` with `tenant_id IS NULL OR …`).
**Apply to:** V53 `media_asset`, `product_media`, and `media_asset_aud` (RESEARCH Open-Q2 recommends `media_asset` audited, `product_media` not).
```sql
-- V52__shop_staff.sql:46-55 — the block to mirror verbatim for media_asset
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
```
> `RlsContractTest.noPolicyUsesRawTenantGucCast` + `everyPublicTableHasRlsAndForce` (`security/RlsContractTest.java`) sweep `pg_policy` and WILL fail the build if the raw cast is used or FORCE is missing. `product_media` carries its own `tenant_id` column so the join row is itself RLS-scoped (do not lean on the FK to `products`).

### RFC 7807 typed errors (D-06)
**Source:** `common/GlobalExceptionHandler.java` — `@RestControllerAdvice` returning `ProblemDetail`. Every handler follows: `ProblemDetail.forStatusAndDetail(status, detail)` + `setTitle` + `setType(URI.create("https://jtoye.uk/errors/<slug>"))` + optional `setProperty(...)` for machine-parseable fields (see `handleShopAccessDenied` lines 246–258 which attaches `shopId`/`requiredRole`).
**Apply to:** oversize (413), allowlist/decode failures. `ResponseStatusException` (lines 202–213) already maps any controller-thrown status → ProblemDetail, so a `throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ...)` at the Content-Length gate needs **no new handler**. A dedicated typed exception (e.g. `PayloadTooLargeException`) with its own `.../errors/payload-too-large` type slug is the more agent-readable choice, mirroring the idempotency handlers (lines 365–385).
```java
// GlobalExceptionHandler.java:365-371 — the per-exception handler shape to clone
@ExceptionHandler(IdempotencyConflictException.class)
public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    problem.setTitle("Idempotency Conflict");
    problem.setType(URI.create("https://jtoye.uk/errors/idempotency-conflict"));
    return problem;
}
```

### Idempotency-Key contract on the async accept (D-06)
**Source:** `common/idempotency/IdempotencyService.java` `execute(endpoint, key, requestBody, responseType, work)` lines 101–162; private `sha256Hex` lines 195–209.
**Apply to:** `MediaUploadController.accept` — wrap the quarantine-PUT + PENDING-insert + outbox-insert in `idempotencyService.execute("media.upload", key, id, MediaAcceptDto.class, () -> ...)`. Reserve-first `INSERT … ON CONFLICT DO NOTHING` gives 409 in-flight / 422 body-mismatch / replay-same-response for free. Handlers already exist in `GlobalExceptionHandler` (409 lines 365–371, 422 lines 379–385).

### @ConfigurationProperties budget key (D-02a, GLOBAL_RULE_6 — no literals)
**Source:** `storage/StorageProperties.java` (whole file, 42 lines) — `@ConfigurationProperties(prefix="storage")`, hand-written getters/setters, sensible defaults (`maxFileSizeBytes = 5_242_880`), nested static `S3Properties`.
**Apply to:** new `media/MediaProperties.java` `@ConfigurationProperties(prefix="jtoye.media")` with `maxDimension=1600`, `quality=80`, `thumbnail=400`, `maxMegapixels`, `maxUploadBytes`, `reaperIntervalMs`, plus a nested `vision` block (`enabled=false`, `minConfidence=0.35`). Register with `@EnableConfigurationProperties` the same way `StorageProperties` is.

---

## Pattern Assignments

### `db/migration/V53__media_asset.sql` (migration, CRUD + backfill)

**Analog:** `db/migration/V52__shop_staff.sql` (table + RLS + `_aud`) and `db/migration/V44__fts_leakproof_and_vector_backfill.sql` (per-tenant backfill loop).

**Table + RLS** — copy the V52 block above. `media_asset` columns per RESEARCH Pattern 1 (id, tenant_id, object_key, sha256 CHAR(64), content_type, width, height, bytes, status CHECK IN ('PENDING','ACTIVE','FAILED'), flagged, failure_reason, uploaded_by, created_at) + `CREATE UNIQUE INDEX uq_media_asset_tenant_sha ON media_asset (tenant_id, sha256)`. `product_media` (id, tenant_id, product_id FK, asset_id FK, is_primary, sort_order) + `idx_product_media_asset` (ref-count) + partial unique `uq_product_media_primary … WHERE is_primary`. `media_asset_aud` mirrors V52 `shop_staff_aud` (lines 62–84): all cols nullable, PK (id, rev), FK rev→revinfo, RLS predicate `tenant_id IS NULL OR tenant_id = current_tenant_id()`.

**Backfill (D-01a / D-03 / D-03b)** — mirror the V44 per-tenant loop EXACTLY (bare UPDATE hits 0 rows under FORCE RLS — `trap_rls_migration_backfill`):
```sql
-- V44:91-133 — the per-tenant GUC loop to clone for the image_url → product_media backfill
DO $$
DECLARE t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.current_tenant_id', t.id::text, true);  -- same GUC the app sets
        -- INSERT media_asset(status='ACTIVE', object_key=<existing key>, sha256=<computed>) per product image
        -- INSERT product_media(is_primary=true) from products.image_url,
        --        product_media(sort_order=n) from products.additional_image_urls[] preserving order
    END LOOP;
    PERFORM set_config('app.current_tenant_id', '', true);  -- defensive reset (V44:129)
END $$;
```
> D-03: existing objects wrapped as-is (no re-pipeline). Header of V52 (lines 16–17) confirms **V53 was reserved** for this and `spring.flyway.out-of-order=true` is already set → no Flyway config change needed. Seed images stay on the flat path (do NOT wrap `/products/seed/`).

---

### `media/MediaAsset.java` (model, CRUD)

**Analog:** `security/access/ShopStaff.java` (entity conventions) + `payment/PaymentEventOutbox.java` (status enum).

**Entity conventions** (`ShopStaff.java` lines 32–72): `@Entity @Table(name="media_asset") @Audited`, `@GeneratedValue(strategy = GenerationType.UUID)`, `@Column(name="tenant_id", nullable=false)`, `@Enumerated(EnumType.STRING) @Column(length=16)` for status, `@CreationTimestamp @Column(name="created_at", updatable=false)`, **hand-written getters/setters (no Lombok on entities — house rule, ShopStaff.java:74–94)**.

**Status enum** — inline like `PaymentEventOutbox.java` lines 28–32:
```java
public enum Status { PENDING, ACTIVE, FAILED }
```

---

### `media/MediaAssetRepository.java` + `media/ProductMediaRepository.java` (repository, CRUD)

**Analog:** `security/access/ShopStaffRepository.java`.

**Ref-count + dedup lookups** mirror the derived-query + count style (`ShopStaffRepository.java` lines 24–30):
```java
// ProductMediaRepository — ref count for the delete-at-0 rule (RESEARCH Pattern 2)
long countByAssetId(UUID assetId);
// MediaAssetRepository — dedup short-circuit (IMG-01 dedup test)
Optional<MediaAsset> findByTenantIdAndSha256(UUID tenantId, String sha256);
```

**CoW repoint** = one-row `@Modifying` UPDATE, same shape as `ShopStaffRepository.insertGroupAdminIfAbsent` (lines 91–99):
```java
@Modifying
@Query("UPDATE ProductMedia pm SET pm.assetId = :newAssetId WHERE pm.id = :rowId")
void repoint(@Param("rowId") UUID rowId, @Param("newAssetId") UUID newAssetId);
```
> If the accept needs a race-safe reserve on `(tenant_id, sha256)`, copy the native `INSERT … ON CONFLICT DO NOTHING` idiom verbatim from `ShopStaffRepository.java` lines 91–99.

---

### `media/MediaUploadController.java` (controller, request-response)

**Analog:** `product/ProductController.java` — the incumbent `/{id}/image` endpoint (lines 173–196) is the exact surface being extended.

**Endpoint + guard** (`ProductController.java` lines 173–183): `@PreAuthorize("hasAuthority('SCOPE_catalog:write')")`, `@PostMapping(value="/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)`, `@Operation`/`@ApiResponses` OpenAPI annotations (keep the snapshot matching per D-06), `@RequestParam("file") MultipartFile file`.

**Reject-early + idempotency + 202** — the RESEARCH code example, grounded in the two live analogs (ProductController guard + IdempotencyService.execute):
```java
@PreAuthorize("hasAuthority('SCOPE_catalog:write')")
@PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<MediaAcceptDto> accept(
        @PathVariable UUID id,
        @RequestHeader(value = "Content-Length", required = false) Long contentLength,
        @RequestHeader("Idempotency-Key") String idemKey,
        @RequestParam("file") MultipartFile file) {
    if (contentLength != null && contentLength > mediaProps.getMaxUploadBytes()) {
        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "...");  // GlobalExceptionHandler → RFC 7807 413
    }
    var outcome = idempotencyService.execute("media.upload", idemKey, id,
            MediaAcceptDto.class, () -> mediaAssetService.acceptQuarantineAndQueue(id, file));
    return ResponseEntity.status(202).body(outcome.value());
}
```
> `MissingRequestHeaderException` (absent `Idempotency-Key`) already maps to 400 via `GlobalExceptionHandler` lines 168–174. Note `IdempotencyService` is hardcoded to stamp 201 (lines 69–70) — the 202-accept status is a documented parameterization follow-up; planner decides whether to extend `execute` or store status separately.

---

### `media/MediaEventOutbox.java` + `…Repository.java` + `…Flusher.java` (event-driven, dedicated outbox — Claude's Discretion → RESEARCH recommends dedicated table)

**Analog:** `payment/PaymentEventOutbox.java` (150 lines), `payment/PaymentEventOutboxRepository.java` (63 lines), `payment/PaymentEventOutboxFlusher.java` (314 lines) — clone all three, trivial `{tenantId, assetId}` payload.

**Entity** (`PaymentEventOutbox.java` lines 26–89): `status` enum PENDING/SENT/FAILED, `attempts`, `next_attempt_at` (backoff), `poison`, `last_error`, `sent_at`, `@CreationTimestamp created_at`.

**Claim query** (`PaymentEventOutboxRepository.java` lines 33–41) — copy verbatim, rename table:
```java
@Query(value = """
        SELECT * FROM media_event_outbox
        WHERE status = 'PENDING' AND next_attempt_at <= now()
        ORDER BY created_at ASC LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
List<MediaEventOutbox> claimPendingBatch(@Param("batchSize") int batchSize);
```
Plus `resurrectFailed()` `@Modifying(clearAutomatically = true)` (lines 53–62).

**Flusher** — clone `PaymentEventOutboxFlusher`: per-tenant `TransactionTemplate` (lines 168–185, the C1 self-invocation-trap fix), `computeBackoffMillis` pure fn (lines 112–128, reuse as-is), `flushPending`/`resurrectFailed` `@Scheduled` (lines 156–229), `listTenantIds` (lines 231–236). **Critical simplification vs. the analog:** `publishRow` (lines 238–283) has a closed-set exchange dispatch (`order.events`/`onboarding.events`/else→PaymentEvent) — a dedicated media outbox has ONE exchange, so `publishRow` reduces to a single `objectMapper.readValue(payload, MediaProcessingEvent.class)` + `rabbitTemplate.convertAndSend(MEDIA_EVENTS_EXCHANGE, routingKey, event)`. **This is exactly why the dedicated table sidesteps `outbox_flusher_dispatch_trap`** (RESEARCH Pitfall 3): reusing `payment_event_outbox` would require editing the `PaymentEventOutboxFlusher.publishRow` dispatch in the same change or the media payload poison-dead-letters.

---

### `media/MediaProcessingWorker.java` (service, event-driven consume)

**Analog:** `order/OrderStateChangeListener.java` (@RabbitListener + GUC pin, full file 154 lines).

Structure: `@RabbitListener(queues = RabbitMQConfig.MEDIA_EVENTS_QUEUE) @Transactional`, GUC-pin block (lines 83–90, shared pattern above), re-read asset & skip if not PENDING (idempotent, mirrors dedup skip lines 95–103), pipeline stages a–j from RESEARCH diagram, `finally { TenantContext.clear() }` (line 120). Stage-6 vision gate reuses `ImageAnalysisService.analyze` behind `isEnabled()` (see `ai/ImageAnalysisService.java` lines 130–134 — already returns `Optional.empty()` when disabled; advisory flag defaults OFF).

---

### `media/MediaNormalizer.java` (utility, transform) — **PARTIAL / no full analog**

**Partial analog:** `storage/StorageService.java` `detectContentType` (lines 284–293) + the magic-byte constants (lines 32–37) — **reuse this 4-signature allowlist verbatim** (RESEARCH "Don't Hand-Roll": Tika is overkill). Decompression-bomb header-read (`ImageReader.getWidth/getHeight` before `read()`) is the ImageIO pattern in RESEARCH Pitfall 2. WebP encode via Scrimage→cwebp is **genuinely new** (no repo precedent) — see "No Analog Found".

---

### `media/MediaProperties.java` (config) → `storage/StorageProperties.java` (exact).
### `media/MediaPendingReaper.java` (scheduled batch) → `webhook/WebhookRetentionCleanup.java` (exact clone: `@Scheduled(fixedDelayString=...)` line 49, per-tenant `TransactionTemplate` + `pinTenantGuc` lines 68–100, `listTenantIds` lines 85–89).
### `config/RabbitMQConfig.java` (MODIFY) → add `MEDIA_EVENTS_EXCHANGE`/`MEDIA_EVENTS_QUEUE` constants + `TopicExchange`/durable `Queue`(with DLX)/`Binding` beans, mirroring the payment topology (lines 129–162). The onboarding-exchange comment (lines 164–176) documents the "own constant so the flusher dispatches the right type" rule.

---

### `frontend/components/ui/asset-image.tsx` (NEW component)

**Analog:** `frontend/components/ui/safe-image.tsx` (full file, 46 lines) — a plain `<img>` (NOT `next/image`) with `useState(failed)` + `onError` fallback.
```tsx
// safe-image.tsx:37-45 — the render to wrap with a status switch
return <img src={src} alt={alt} className={className} loading={loading} onError={() => setFailed(true)} />
```
Add a `status` prop (`PENDING`→skeleton/"Processing…", `ACTIVE`→render WebP derivative + width/height for CLS, `FAILED`→error card + "Re-upload", `ACTIVE&flagged`→"Needs review" badge). **Keep `alt` (D-07 SEO — the only present SEO surface; JSON-LD baseline is null per RESEARCH).** `next.config.mjs` needs no change (derivatives stay on the same MinIO host/path).

---

### `frontend/components/dashboard/media/ReviewQueue.tsx` (NEW component)

**Analog:** `frontend/app/dashboard/onboarding/approvals/page.tsx` (633 lines) — the closest live "vendor-facing queue with per-row action dialog" screen. Copy: `"use client"` + `useState`/`useEffect`/`useCallback` data-fetch via `apiClient` (lines 1–6), `Card`/`Dialog`/`Button`/`Badge` design-system imports (lines 7–24), lucide icons, `Record<Status, {label, badge, icon}>` status-meta maps with the orange/emerald/slate/amber palette + defensive fallbacks (lines 50–75), and the `m.` (LazyMotion-strict — import `m` from `framer-motion`, line 4, NOT `motion`; see `project_phase_22` merge trap). Render FAILED (reason + Re-upload) and flagged-ACTIVE (Keep / Replace per D-04) rows. `frontend/app/dashboard/staff/page.tsx` (491 lines) is a secondary analog for the plainer list-management shape.

---

### `frontend/types/api.ts` + `storefront.ts` (MODIFY)

**Analog:** themselves. `api.ts` `Product` interface (line 55, `imageUrl: string | null` line 64); `storefront.ts` `PublicProduct` (line 24, `imageUrl`/`imageUrls` lines 28–29). Add a `MediaAssetStatus = 'PENDING'|'ACTIVE'|'FAILED'` union + per-gallery-entry `{ status, flagged, failureReason }`, mirroring the existing status unions already in `api.ts` (`OrderStatus`, `GateStatus`, `OnboardingState` at lines 163/355/363). **Run `npm run build` (tsc) — Jest does NOT type-check** (`feedback_frontend_typecheck_gate`).

---

### Tests (Wave 0)

**Analog:** `security/access/ShopStaffRlsPolicyIntegrationTest.java` (170 lines) is the exact template for `MediaAssetRlsPolicyIntegrationTest`: `@SpringBootTest @Testcontainers @ActiveProfiles("test") @Tag("testcontainers") @Transactional`, provision `rls_test_role NOSUPERUSER NOBYPASSRLS` (lines 74–81), `SET LOCAL ROLE` downgrade per RLS-sensitive tx (`dropSuperuserForTransaction` lines 116–118), seed under `TenantContext.set(tenantA)` (lines 94–108), assert cross-tenant reads return 0 and cross-tenant writes throw `.hasStackTraceContaining("row-level security")` (lines 124–168). `IdempotencyKeysRlsPolicyIntegrationTest` + the `PaymentEventOutboxFlusher*Test` set are the templates for the idempotency/outbox tests. Extend `RlsContractTest` fixtures and `BulkImportServiceTest`. Reconcile `docs/metrics.json` via `scripts/docs-freshness.sh` (CI gate).

---

## No Analog Found

Planner should use RESEARCH.md §"Standard Stack" + §"Code Examples" for these — they have no close codebase precedent:

| File / Concern | Role | Data Flow | Reason |
|----------------|------|-----------|--------|
| `media/MediaNormalizer.java` — WebP encode/resize/thumbnail | utility | transform | Stock `ImageIO` cannot read or write WebP; Scrimage 4.6.6 → `cwebp` subprocess is new to the repo. **Magic-byte sniff and header-read bomb-guard DO have analogs** (`StorageService.detectContentType` + ImageIO) — only the encode is greenfield. RESEARCH §"WebP normalize via Scrimage" is the reference. |
| `core-java/Dockerfile` — `apk add libwebp-tools` + `-Dcom.sksamuel.scrimage.webp.binary.dir=/usr/bin` | config | — | The runtime image (`eclipse-temurin:21-jre-alpine`, musl) has no WebP binary; the apk-add + JVM-flag step is new. RESEARCH Pitfall 1 + Assumption A1 — **Wave-0 in-container musl smoke test is the #1 execution risk**; fallback is switching the base image to glibc `eclipse-temurin:21-jre`. |
| `core-java/build.gradle.kts` — Scrimage / TwelveMonkeys deps | config | — | No image-processing library on the classpath today; add per RESEARCH §"Standard Stack" install block. |

## Metadata

**Analog search scope:** `core-java/src/main/java/uk/jtoye/core/{media(new),security/access,payment,order,common/idempotency,webhook,storage,config,product,ai,exception}`, `core-java/src/main/resources/db/migration`, `core-java/src/test/.../security/access`, `frontend/{components/ui,components/dashboard,app/dashboard,types}`.
**Files scanned:** 20 backend + 6 frontend analog files read this session (all ≤ 633 lines, single-pass).
**Pattern extraction date:** 2026-07-23
