# Phase 24: Image Architecture — CoW Assets + Safe Upload Pipeline - Context

**Gathered:** 2026-07-23
**Status:** Ready for planning

<domain>
## Phase Boundary

Vendor image uploads are backed by a shared **copy-on-write `media_asset` model** (ref-counted,
tenant-scoped ENABLE+FORCE RLS, sha256 per-tenant dedup) and every upload passes through a **safe
async pipeline** (reject-early size guard → quarantine + PENDING row → AMQP outbox → worker that
magic-byte-sniffs, allowlists jpeg/png/webp, decode-verifies, strips EXIF, normalizes) that stores
**only a validated, normalized derivative — never the raw bytes**. Products reference assets and
never own bytes; editing a shared asset mints a new asset and repoints only that product; a physical
MinIO delete happens only at reference-count 0. Requirements: **IMG-01..IMG-04**.

**Scope anchor (locked, do NOT re-litigate)** — from `image-architecture-SPEC.md` D1–D4:
tenant-only sharing (platform-wide stock library = LATER slice), async-via-RabbitMQ,
compress-fail = hard veto / low-content-relevance = review queue (never a hard content reject),
stored artifact = normalized derivative, vision stage behind an advisory flag (Ollama unreliable),
worker pins the tenant GUC before any DB write (@Async-tenant landmine).

</domain>

<decisions>
## Implementation Decisions

*(These four areas were deliberately left open by the SPEC — decided here 2026-07-23. Everything
else is locked by `image-architecture-SPEC.md` + REQUIREMENTS IMG-01..04.)*

### Product↔asset link shape
- **D-01:** A single **`product_media` join table** carries both the primary image and the ordered
  gallery: `(product_id FK, asset_id FK→media_asset, is_primary bool, sort_order int)`. The
  primary image is the `is_primary=true` row; the gallery is the `sort_order` rows. **Copy-on-write
  repoint = `UPDATE product_media SET asset_id=<new>` on the one affected row.** Chosen over a
  primary FK + separate gallery table (rejected: two code paths) and over keeping `image_url`
  (rejected: weakest ref-count/CoW guarantees).
- **D-01a:** Backfill maps `products.image_url` → the `is_primary` row and
  `products.additional_image_urls[]` → `sort_order` rows preserving array order.

### Normalized-derivative policy (storefront mobile CWV)
- **D-02:** The worker **transcodes ALL uploads to WebP** + a thumbnail. This is a user-facing
  storefront surface → it **owns Core Web Vitals** (cross-cutting web-perf contract). Smallest bytes
  / best mobile LCP.
- **D-02a:** Max-dimension / quality / thumbnail-size are a **config-declared budget** (NOT literals —
  GLOBAL_RULE_6 / ARCHITECTURE_RULE_8). Proposed defaults, env-overridable under a `jtoye.media.*`
  key: **max_dimension=1600px, quality=80, thumbnail=400px**. Planner introduces the config key
  rather than hard-coding.
- **D-02b:** Responsive multi-width srcset variants are **deferred** (borderline scope) — see Deferred.

### Backfill of existing `image_url`
- **D-03:** Existing objects are **wrapped as `status=ACTIVE` media_asset rows as-is, pointing at the
  current `object_key`, WITHOUT re-running the pipeline** (they are already trusted/working;
  re-normalizing risks breaking a currently-fine image, and would FAIL nothing usefully). sha256 is
  computed at backfill for dedup/ref-count correctness.
- **D-03a:** **Dual-read window**: reads resolve **asset-first, fall back to `image_url`**. The
  `image_url`/`additional_image_urls[]` columns are **kept** this phase and **dropped in a later
  phase** once the asset model is proven live. Aligns with SPEC D1 ("seeded demo images stay on the
  current flat path until [the stock-library] slice").
- **D-03b:** Backfill migration MUST follow the RLS per-tenant `set_config` loop pattern — a bare
  UPDATE against a FORCE-RLS table updates ZERO rows as the migration role. See
  `trap_rls_migration_backfill` (recurring V25→V44→V57).

### Failure & review-queue UX (IMG-03/04)
- **D-04:** **FAILED** asset → vendor sees rejection + reason and **re-uploads** (no auto-retry this
  phase). **Content-flagged ACTIVE** asset → appears in a vendor-visible **review queue** with
  **Keep (dismiss the flag)** or **Replace**.
- **D-04a:** Because CoW mints a new asset **only on worker success**, a **replacement upload that
  FAILS never clobbers the live image** — the product keeps its existing ACTIVE asset. This is the
  load-bearing safety property behind D-04.
- **D-04b:** No "block product on FAILED" gate (rejected — fights SPEC D3's "don't wrongly block
  legitimate rare dishes" intent).

### Cross-cutting quality contracts (design-time acceptance criteria — J'Toye CLAUDE.md)
- **D-05 (Security):** The pipeline *is* a security control — each PLAN carries a `<threat_model>`
  (malicious/oversize upload, decompression-bomb, content-type spoof, EXIF PII leak, SSRF via
  remote-fetch if any). Reject-early size guard + magic-byte sniff + decode-verify + EXIF strip are
  the mitigations; routed through `/gsd:secure-phase 24`.
- **D-06 (AI agent-readiness):** The upload endpoint is a **mutating API surface** → carries the
  uniform **Idempotency-Key** contract (#204 pattern) on the 202-style accept, **RFC 7807** typed
  errors (oversize/allowlist/decode failures), and the OpenAPI snapshot matches the live async
  (202 + asset id) response.
- **D-07 (Web-perf):** Owned via D-02 (WebP + config budget). Storefront rendering of the new
  derivative must **not regress** existing Product/Offer/LocalBusiness JSON-LD or image `alt` text
  (SEO "don't-regress" — public shop pages; the dashboard review-queue UI is authenticated = SEO N/A).

### Claude's Discretion (planner/researcher decide — not user-facing)
- `object_key` naming scheme (content-addressed by sha256 + tenant prefix is the natural fit for
  immutable CoW + dedup, but the planner picks).
- Whether media upload events ride the **shared `payment_event_outbox`** or a **dedicated
  `media_event_outbox`** table. **If the shared table is reused, the `PaymentEventOutboxFlusher`
  dispatch branch MUST be extended in the SAME change** (exchange bean + producer + flusher branch
  land together) — see `outbox_flusher_dispatch_trap`. A dedicated table sidesteps that coupling.
- Image library for decode/normalize/WebP on the JVM (e.g. a native/pure-Java encoder) — researcher
  recommends; must handle decompression-bomb limits.
- PENDING-row reaper for workers that crash mid-process (orphan quarantine cleanup) — advisable, planner sizes it.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase spec + requirements (READ FIRST — locked scope)
- `.planning/specs/image-architecture-SPEC.md` — the 4 locked decisions (D1–D4), target design
  (Q1 media_asset model, Q2 async veto-pipeline stages 1–6), explicit deferrals, constraints. **The
  durable design artifact this phase implements.**
- `.planning/REQUIREMENTS.md` §"Image architecture (IMG)" (lines ~52–62) — IMG-01..IMG-04 with
  acceptance tests + source file:line evidence; migration numbering (media_asset = **V53**).
- `.planning/ROADMAP.md` §"Phase 24" — goal, 4 success criteria, plan skeleton 24-01..24-03.

### Migration + RLS precedent
- `core-java/src/main/resources/db/migration/` — V47 `processed_order_events` + V50 `idempotency_keys`
  are the ENABLE+FORCE RLS tenant-scoped table pattern to mirror for `media_asset`.
- `trap_rls_migration_backfill` (memory) — the per-tenant `set_config` backfill loop (D-03b).

### Outbox / AMQP infra (V46) — pipeline publish
- `core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutbox.java`,
  `PaymentEventOutboxRepository.java`, `PaymentEventOutboxFlusher.java` — the transactional-outbox
  precedent; `outbox_flusher_dispatch_trap` (memory) governs reuse vs. a dedicated table.

### AI-readiness track (D-06)
- `project_ai_readiness_track` (memory) — #204 Idempotency-Key contract (`IdempotencyService.execute`,
  V50) the async accept must adopt.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `core-java/.../storage/StorageService.java` — `validateAndRead` (line ~246) is the current sync
  entry; `upload` trusts `file.getContentType()` (lines 91/116) — the exact thing IMG-02 replaces
  with magic-byte sniffing. `putSeedImage` (line 170) is the seed path (stays flat per SPEC D1).
- `core-java/.../ai/ImageAnalysisService.java` — Ollama `llava:7b` vision; wire behind the advisory
  flag for stage-6 content-relevance (IMG-03). Provider unreliable → flag defaults advisory.
- `core-java/.../product/BulkImportService.java` — MUST share the ONE pipeline path (IMG-02), not a
  second upload route.
- `IdempotencyService.execute` (V50) — reuse for the D-06 Idempotency-Key contract on the accept.

### Established Patterns
- `Product.java`: `image_url` (line 57) + `additional_image_urls TEXT[]` (line 84) — the flat-string
  model being replaced by `product_media` (D-01); both columns retained this phase for dual-read (D-03).
- ENABLE+FORCE RLS proven under NOSUPERUSER role-downgrade is the project standard for every new table.

### Integration Points
- New `media_asset` (V53) + `product_media` join land AFTER `shop_staff` V52 (Phase 23) — `out-of-order=true`
  already set. Storefront + dashboard product rendering read via the asset-first dual-read resolver (D-03a).

</code_context>

<specifics>
## Specific Ideas

- Derivative budget defaults to pin as config: **WebP, 1600px max dimension, quality 80, 400px thumbnail**
  (`jtoye.media.*`) — planner introduces the key; numbers are the starting budget, not literals.
- CoW safety demo worth a test: replace a product's image with a file that FAILS → the original ACTIVE
  asset remains the product's live image (D-04a).

</specifics>

<deferred>
## Deferred Ideas

- **Responsive multi-width srcset variants** (400/800/1600 WebP) — best cross-device CWV but extra
  storage + worker output + UI srcset wiring. Revisit after the single-derivative pipeline is proven.
- **Platform-wide stock image library / cross-tenant asset sharing** — SPEC D1 explicitly a later slice
  (needs cross-tenant RLS read-exception design).
- **Dropping `image_url`/`additional_image_urls[]` columns** — a later phase once the asset model is
  proven live (D-03a keeps them this phase for dual-read).
- **Vision-provider hosting decision** (fix Ollama :11434 conflict vs. hosted model) — blocks only the
  IMG-03 stage-6 content-relevance quality; the pipeline ships behind the advisory flag without it.
- **Auto-retry/backoff on transient worker failure** — this phase is re-upload-only (D-04).

</deferred>
