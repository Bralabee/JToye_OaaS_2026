# SPEC — Image Architecture: Shared Assets (Copy-on-Write) + Safe Upload Pipeline

**Status:** DECIDED 2026-07-13 — ready for a future milestone phase (spec-now, build-later)
**Decided by:** user, session 2026-07-13 (post-v2.2, after QA-council run 20260713-152124)
**Origin:** design questions raised 2026-07-13; premise-checked against live data (0 products share an image_url — no data defect today; this is forward-looking hardening before real vendor uploads start)

## Problem

Two structural gaps that will bite once vendors upload real images:

1. **No asset model.** `products.image_url text` + `additional_image_urls text[]` are flat strings. Nothing owns the MinIO object, nothing counts references, nothing prevents mutating an object another product points at, and deletes can orphan or (worse) break shared usage.
2. **No safe-upload pipeline.** `StorageService.upload → validateAndRead` enforces max-file-size and trusts client content-type; large-image handling is a WARN + "client should compress" (StorageService ~line 314). No server-side compression/resize, no magic-byte sniffing, no decode-to-verify, no EXIF strip. `ai/ImageAnalysisService` (Ollama llava:7b) is advisory auto-naming only, and Ollama is currently not running (host :11434 conflict).

## Locked decisions (2026-07-13)

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| D1 | Asset sharing scope (first slice) | **Tenant-only** | Assets are tenant-scoped RLS rows; copy-on-write works across a vendor's own shops. Platform-wide stock library (`is_stock`/system tenant, cross-tenant read-only policy) is explicitly a LATER slice — avoids designing RLS exceptions now. Seeded demo images stay on the current flat path until that slice. |
| D2 | Upload pipeline execution | **Async via RabbitMQ** | Upload stores the raw object + a PENDING `media_asset` row; a queue worker normalizes/validates and flips it ACTIVE (or FAILED). Reuses the existing outbox/AMQP infra (V46). Product UI shows a "processing" state while PENDING. Applies to single uploads AND BulkImportService (one path). |
| D3 | Gate strictness | **Compress = hard veto; content-match = review queue** | Normalization failure (un-decodable, format not in allowlist, resize/re-encode fails) → asset FAILED, upload rejected. Content-relevance below threshold → asset goes live BUT lands in a vendor-visible review queue. A hard content reject would wrongly block legitimate rare dishes (vision model returns confidence 0.0 + "Unknown" for West-African dishes it doesn't know). |
| D4 | Routing | **Spec now, build later** | This doc is the durable artifact; next milestone picks it up as a ready phase. No build in the 2026-07-13 session. |

## Target design

### Q1 — media_asset model (tenant-only slice)

- New table `media_asset`: `id, tenant_id, object_key, sha256, content_type, width, height, bytes, status (PENDING|ACTIVE|FAILED), uploaded_by, created_at` — ENABLE+FORCE RLS tenant-scoped (mirror V47/V50 policy pattern). `sha256` unique per tenant for dedup.
- Products **reference** assets (FK or join table for the gallery); they never own bytes. Never mutate a MinIO object referenced by >1 product.
- **Copy-on-write:** editing "your" copy of a shared asset mints a NEW asset + repoints only your product.
- **Reference counting:** physical MinIO delete only at ref-count 0.
- Migration: Flyway V51+ (schema at V50 as of 2026-07-13); backfill existing `image_url` values into assets with a dual-read window; `_aud` mirrors per Envers convention if audited.

### Q2 — upload veto-pipeline (async worker)

Request thread (cheap, reject-early):
1. Content-Length + `spring.servlet.multipart.max-file-size` + streaming size guard — a 2GB body is refused BEFORE buffering (a 2GB in-memory MultipartFile is itself a DoS).
2. Store raw upload to a quarantine prefix; insert `media_asset` row status=PENDING; publish AMQP event (outbox pattern per V46); return 202-style response with asset id.

Queue worker (each stage can veto):
3. Magic-byte sniff (never trust client content-type), format allowlist (jpeg/png/webp), decode-to-verify, strip EXIF/metadata.
4. Normalize: resize to max dimension, re-encode at target quality, generate thumbnail. **The stored artifact is ALWAYS the normalized derivative — never the raw upload.** Raw quarantine object deleted after success.
5. Stage 3/4 failure → status=FAILED (vendor sees rejection + reason); success → status=ACTIVE.
6. Content-relevance check: vision `identifiedName`+`confidence` vs product title/description; below threshold → flag into review queue (asset stays ACTIVE). Requires Ollama running (or hosted vision) — ship behind a flag defaulting to advisory until the provider is reliably up (see AI-readiness track, Ollama :11434 conflict).

### Explicitly deferred

- Platform-wide stock library / cross-tenant sharing (D1 later slice).
- Backend public rate-limiter tuning (#88) — separate track.
- Vision-provider hosting decision (Ollama fix vs hosted model) — blocks stage 6 only; pipeline ships without it.

## Constraints

- All new tables RLS ENABLE+FORCE, tenant-scoped, proven under NOSUPERUSER role-downgrade (project standard).
- Tests per project standard; docs/metrics.json reconciled via scripts/docs-freshness.sh.
- Multi-tenancy: worker must pin tenant GUC before any DB write (see @Async-tenant landmine in vendor-onboarding planning notes).
- Sizing: schema migration + worker + UI states = a proper phase (likely 2+ plans), NOT a quick task.
