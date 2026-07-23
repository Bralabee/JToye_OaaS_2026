# Phase 24: Image Architecture — CoW Assets + Safe Upload Pipeline - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-07-23
**Phase:** 24-image-architecture-cow-assets-safe-upload-pipeline
**Areas discussed:** Product↔asset link shape, Derivative policy (size/format), Backfill of existing images, Failure & review-queue UX

**Framing:** Phase is heavily pre-locked by `image-architecture-SPEC.md` (D1–D4) + REQUIREMENTS IMG-01..04. Discussion covered only the four areas the SPEC deliberately left open (the "FK or join table" question and the un-pinned normalize/backfill/UX specifics). User selected all four to decide now, then chose the recommended option in each.

---

## Product↔asset link shape

| Option | Description | Selected |
|--------|-------------|----------|
| Unified join table | `product_media(product_id, asset_id, is_primary, sort_order)` — one model for primary + gallery; CoW repoint = swap asset_id on one row | ✓ |
| Primary FK + gallery join | `products.primary_asset_id` FK (fast primary read) + join table for gallery; two code paths | |
| Keep image_url column | Minimal migration; column points at object_key; weakest ref-count/CoW | |

**User's choice:** Unified join table
**Notes:** Cleanest single model; native ordering; backfill maps image_url→is_primary row, additional_image_urls[]→sorted rows.

---

## Derivative policy (size/format)

| Option | Description | Selected |
|--------|-------------|----------|
| WebP, config budget | Transcode all → WebP + thumbnail; max-dim/quality/thumb as config (1600px/q80/400px defaults) | ✓ |
| Preserve source format | jpeg-as-jpeg etc.; resize+re-encode+EXIF-strip+thumb; larger bytes, exact format | |
| WebP + responsive set | WebP @ 400/800/1600 for srcset; best CWV, most storage (borderline scope) | |

**User's choice:** WebP, config budget
**Notes:** Storefront product images own mobile Core Web Vitals. Numbers declared as `jtoye.media.*` config, not literals. Responsive variants deferred.

---

## Backfill of existing images

| Option | Description | Selected |
|--------|-------------|----------|
| Wrap as ACTIVE as-is | Existing objects → ACTIVE assets at current object_key, no re-normalize; asset-first dual-read; drop image_url later | ✓ |
| Re-run through pipeline | Enqueue existing images through the worker; uniform derivatives but risks failing a working image | |
| New-uploads-only (no backfill) | Only new uploads use media_asset; misses IMG-01 backfill AC | |

**User's choice:** Wrap as ACTIVE as-is
**Notes:** Existing images are trusted/working; re-normalizing risks breakage. Backfill migration must use the per-tenant set_config RLS loop (trap_rls_migration_backfill). image_url dropped in a later phase.

---

## Failure & review-queue UX

| Option | Description | Selected |
|--------|-------------|----------|
| Re-upload + keep/dismiss | FAILED → reason + re-upload; flagged ACTIVE → Keep/Replace; replace-fail keeps old image via CoW | ✓ |
| Auto-retry then fail | Worker retries N times before FAILED; adds retry bookkeeping | |
| Block product on FAILED | Hold product from publish until valid image; fights SPEC D3 intent | |

**User's choice:** Re-upload + keep/dismiss
**Notes:** CoW mints a new asset only on worker success → a failed replacement never clobbers the live image (the load-bearing safety property). No auto-retry this phase; no publish-block.

---

## Claude's Discretion

- `object_key` naming scheme (content-addressed sha256 + tenant prefix is the natural fit).
- Shared `payment_event_outbox` vs. a dedicated `media_event_outbox` — if shared, extend the flusher dispatch in the same change (outbox_flusher_dispatch_trap).
- JVM decode/normalize/WebP library choice (with decompression-bomb limits) — researcher recommends.
- PENDING-row reaper for crashed workers (orphan quarantine cleanup) — planner sizes.

## Deferred Ideas

- Responsive multi-width srcset variants (400/800/1600 WebP).
- Platform-wide stock image library / cross-tenant sharing (SPEC D1 later slice).
- Dropping `image_url`/`additional_image_urls[]` columns (later phase, after asset model proven live).
- Vision-provider hosting decision (Ollama :11434 vs. hosted) — blocks only IMG-03 stage 6.
- Auto-retry/backoff on transient worker failure.
