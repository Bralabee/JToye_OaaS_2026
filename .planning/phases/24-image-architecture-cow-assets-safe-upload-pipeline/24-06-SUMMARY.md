---
phase: 24-image-architecture-cow-assets-safe-upload-pipeline
plan: 06
subsystem: media
tags: [img-04, asset-image, review-queue, uploader-202, phase-gate-reconcile, docs-freshness, openapi-snapshot, cls, idempotency-key]

# Dependency graph
requires:
  - phase: 24-image-architecture-cow-assets-safe-upload-pipeline (plan 24-05)
    provides: MediaAssetDto (assetId/status/flagged/failureReason/url/thumbnailUrl/width/height) + GET /api/v1/media/review-queue + POST /{assetId}/keep + ProductDto.media — the IMG-04 backend data contract this UI renders
  - phase: 24-image-architecture-cow-assets-safe-upload-pipeline (plan 24-03)
    provides: the 202 accept (POST /api/v1/products/{id}/image → { assetId, status:PENDING }, Idempotency-Key) the uploader now consumes
provides:
  - "AssetImage: status-aware wrapper over SafeImage (PENDING→processing / ACTIVE→WebP derivative w/ alt+width+height / FAILED→reason+Re-upload / flagged→Needs-review badge)"
  - "ReviewQueue dashboard screen (/dashboard/media/review) + sidebar nav: lists FAILED (reason + Re-upload) and flagged-ACTIVE (Keep/Replace), wired to media-api.ts"
  - "ImageUploader consumes the 202/PENDING accept + sends an Idempotency-Key (D-06); shows the processing state; legacy synchronous path preserved"
  - "MediaAssetStatus/MediaAsset/MediaUploadAccepted TS types (api.ts + storefront.ts mirror); SafeImage forwards width/height"
  - "Phase-24 gate reconcile: docs/metrics.json (total 1636/schema 58) + docs-freshness green + OpenAPI snapshot regen (media endpoints + 202) + CLAUDE/AGENTS counts"
affects: [closes IMG-04 + Phase 24 CI gates; /gsd:verify-work live E2E next]

# Tech tracking
tech-stack:
  added: []   # no new libraries — React components + a lib client + Jest specs over the 24-05 contract
  patterns:
    - "AssetImage wraps SafeImage (plain <img>, not next/image) with a status switch; explicit width/height forwarded for CLS/LCP (D-07)"
    - "ReviewQueue mirrors the onboarding-approvals queue: use-client + apiClient-backed lib + Card/Dialog/Button/Badge + m. (LazyMotion-strict) + orange/emerald/slate/amber palette with defensive fallbacks"
    - "Uploader Idempotency-Key reuses makeIdempotencyKey (webhooks-api WR-07 secure-random contract) — never Math.random"
    - "Sidebar nav item added once to the shared `navigation` array → flows into the mobile 'More' sheet automatically (mobile-tab-bar re-derives from it)"
    - "Phase-gate reconcile recipe (22-07/23-15 precedent): docs-freshness.sh --write + gradle updateOpenApiSnapshot, committed together"

key-files:
  created:
    - frontend/components/ui/asset-image.tsx
    - frontend/components/ui/__tests__/asset-image.test.tsx
    - frontend/components/dashboard/media/ReviewQueue.tsx
    - frontend/components/dashboard/media/__tests__/ReviewQueue.test.tsx
    - frontend/app/dashboard/media/review/page.tsx
    - frontend/lib/media-api.ts
  modified:
    - frontend/types/api.ts
    - frontend/types/storefront.ts
    - frontend/components/ui/safe-image.tsx
    - frontend/components/ui/image-uploader.tsx
    - frontend/components/dashboard/sidebar.tsx
    - docs/metrics.json
    - docs/api/openapi-snapshot.json
    - CLAUDE.md
    - AGENTS.md

key-decisions:
  - "Replace/Re-upload routes to the product page (a real, working destination) because the 24-05 review-queue MediaAssetDto carries NO productId — a direct in-queue re-upload against a specific product would require changing the merged backend contract (out of scope for this UI plan). Keep is fully wired; Replace initiates the re-upload flow via a dialog that links to /dashboard/products where the uploader's 202 path runs. Follow-up: add productId to the review-queue DTO to enable an inline uploader."
  - "SafeImage gains optional width/height forwarded to the <img> — the ACTIVE state MUST set explicit intrinsic dimensions (CLS/LCP, D-07), and AssetImage genuinely wraps SafeImage rather than re-implementing the onError fallback. Backward-compatible (both optional, default undefined)."
  - "The uploader detects the async accept shape ({assetId,status}) via a type-guard and branches: 202/PENDING → processing state + onUploadAccepted; a legacy DTO with imageUrl/logoUrl/bannerUrl → the existing synchronous onUploadComplete path (shop logo/banner endpoints unmigrated). Zero regression to sync callers (Incremental Betterment)."
  - "PublicProduct.media + Product.media are OPTIONAL (dual-read D-03a) — the storefront/dashboard fall back to the flat imageUrl/imageUrls when absent, matching the backend's detail-only enrichment (absent on list/search to avoid an N+1)."

patterns-established:
  - "asset-image.test.tsx = the four-state render proof (PENDING processing / ACTIVE img+alt+dims / FAILED reason+Re-upload / flagged badge) — IMG-04 acceptance at the component level"
  - "ReviewQueue.test.tsx = queue-behaviour proof over a mocked media-api (list FAILED+flagged, Keep drops the row, Replace/Re-upload opens the flow, empty state); a STABLE toast mock is required or the load useEffect refetches every render"

requirements-completed: [IMG-04]

# Metrics
duration: ~45min
completed: 2026-07-23
---

# Phase 24 Plan 06: Vendor UI (IMG-04) + Phase-Gate Reconcile Summary

**The last IMG-04 half — the vendor-facing product-image UI — plus the Phase-24 CI gate closer: a status-aware `AssetImage` (PENDING→processing / ACTIVE→WebP derivative with `alt`+width/height / FAILED→reason+Re-upload / flagged→Needs-review), a mobile-first review/rejection queue screen (FAILED reason + Re-upload; flagged Keep/Replace) wired to the 24-05 `/api/v1/media` contract, an `ImageUploader` that consumes the 202 accept with an `Idempotency-Key`, and the docs/metrics.json + OpenAPI-snapshot + CLAUDE/AGENTS reconcile that turns the two phase gates green.**

## Performance
- **Duration:** ~45 min
- **Tasks:** 3 (all `type="auto"`)
- **Files:** 15 (6 created, 9 modified)

## Accomplishments
- **`AssetImage` (status-aware wrapper over `SafeImage`)** — a `status` switch rendering: `PENDING`→a "Processing…" spinner (no not-yet-servable `<img>`), `ACTIVE`→the WebP derivative via `SafeImage` with `alt` preserved (only present SEO surface, D-07) and explicit `width`/`height` forwarded for CLS/LCP, `ACTIVE&flagged`→the derivative plus a "Needs review" badge, `FAILED`→an error card with the vendor-visible `failureReason` + a "Re-upload" control. `next.config.mjs` untouched (derivatives keep the MinIO host/path).
- **`ReviewQueue` screen + route + nav** — a mobile-first `/dashboard/media/review` (cards stacking below `sm`, row layout at `sm+`) listing **Rejected uploads** (FAILED reason + Re-upload) and **Flagged for review** (a thumbnail via `AssetImage` + Keep/Replace), modelled on the onboarding-approvals queue: `use-client`, `useState`/`useEffect`/`useCallback` fetch via `media-api`, `Card`/`Dialog`/`Button`/`Badge`, lucide icons, `m.` from `framer-motion` (LazyMotion-strict), the orange/emerald/slate/amber palette. An **"Image review"** sidebar item (added once to the shared `navigation` array → flows into the mobile "More" sheet automatically).
- **`media-api.ts`** — `fetchReviewQueue()` (GET `/api/v1/media/review-queue`) + `keepAsset(assetId)` (POST `/api/v1/media/{assetId}/keep`) over the default authed `apiClient`, plus `productImageUploadUrl(productId)`.
- **`ImageUploader` 202 handling** — sends an `Idempotency-Key` (secure-random, D-06/T-24-24) and consumes the 202 `{ assetId, status:"PENDING" }` accept: a type-guard branches to a processing state + `onUploadAccepted` for the async pipeline, while the legacy synchronous path (shop logo/banner endpoints carrying a ready `imageUrl`) is preserved unchanged. The client canvas pre-compress + `SERVER_MAX_BYTES` stay as defence-in-depth.
- **Types** — `MediaAssetStatus`/`MediaAsset`/`MediaUploadAccepted` in `types/api.ts` (mirrored into `storefront.ts`; `Product.media`/`PublicProduct.media` optional for dual-read D-03a); `SafeImage` forwards optional `width`/`height`.
- **Phase-gate reconcile (Task 3)** — `docs/metrics.json` recomputed from source: Java `@Test` **1065→1116** across **181→196** files (the merged 24-01..24-05 backend tests), controllers **21→23**, Jest **365→376** across **56→58** files (this plan's two specs), **schema 57→58** (V53 media_asset + V58 media_event_outbox already tracked), **total 1574→1636**; `scripts/docs-freshness.sh` check-mode **exit 0**. OpenAPI snapshot regenerated (`updateOpenApiSnapshot`): adds `/api/v1/media/review-queue` + `/api/v1/media/{assetId}/keep`, the product image POST is now **202** (the sync 200 handler removed in 24-03), `ProductDto.media` present — `OpenApiSnapshotTest` **green in check mode**. CLAUDE.md + AGENTS.md count prose (identical mirrors) + schema-version line updated to V58 with the Phase-24 media note.

## Tests (11 Jest across 2 specs, all green + full-suite 371/371)
- `asset-image.test.tsx` **6/6**: PENDING→processing indicator (no `<img>`); ACTIVE→`<img>` with `alt` + `width`/`height`; ACTIVE+useThumbnail→thumbnail src; FAILED→`failureReason` + a working Re-upload; FAILED with no reason still shows Re-upload; ACTIVE&flagged→Needs-review badge over the derivative.
- `ReviewQueue.test.tsx` **5/5**: lists a FAILED row (reason + Re-upload) and a flagged row (Keep + Replace); Keep calls `keepAsset(assetId)` and drops the row (empty state); Replace opens the re-upload dialog routing to `/dashboard/products`; Re-upload on a FAILED row opens the same flow; empty state when nothing needs review.
- **Full frontend Jest: 58 suites / 371 tests green** (no regression from the shared `safe-image`/`image-uploader`/types changes). **`npm run build` (tsc) exit 0** — the load-bearing type gate (`feedback_frontend_typecheck_gate`).

## Incremental Betterment — no capability displaced
- **`ImageUploader`**: the synchronous `onUploadComplete(imageUrl)` path is **preserved** for the still-flat shop logo/banner endpoints (a type-guard routes only the new 202/PENDING shape to the async branch). No existing caller broke; `onUploadAccepted` is additive + optional.
- **`SafeImage`**: `width`/`height` are additive optional props; every existing `SafeImage` call renders byte-for-byte the same.
- **Storefront/dashboard render**: `Product.media`/`PublicProduct.media` are optional; the flat `imageUrl`/`imageUrls` dual-read fallback is intact (D-03a).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Extended `SafeImage` (beyond the plan's Task-1 file list) to forward width/height**
- **Found during:** Task 1
- **Issue:** The IMG-04 must-have "ACTIVE renders the WebP derivative with width/height" + the D-07 CLS/LCP requirement need explicit intrinsic dimensions on the `<img>`, but `SafeImage` (which `AssetImage` wraps) did not accept `width`/`height`. Re-implementing the `<img>`+onError inside `AssetImage` would have abandoned the "wrapper over SafeImage" design.
- **Fix:** Added optional `width?`/`height?` to `SafeImage`, forwarded to the `<img>`. Backward-compatible (default undefined; existing calls unchanged). `safe-image.tsx` was not in the plan's `files_modified` — recorded here.
- **Files modified:** frontend/components/ui/safe-image.tsx
- **Commit:** 6c3cb36

**2. [Rule 2 - Correctness] Replace/Re-upload routes to the product page (the 24-05 DTO has no productId)**
- **Found during:** Task 2
- **Issue:** D-04's "Replace = re-upload" targets `POST /api/v1/products/{id}/image`, but the merged 24-05 `MediaAssetDto` on the review queue carries **no productId**, so an inline product-scoped re-upload cannot be wired without changing the merged backend contract (out of scope — the orchestrator directed "do NOT recreate backend").
- **Fix:** Keep is fully wired to `keepAsset`. Replace/Re-upload opens a dialog explaining the CoW-safe swap (a FAILED replacement never clobbers the live image, D-04a) with a working link to `/dashboard/products`, where the product's uploader runs the 202 path. This is an honest, non-dead-end capability given the contract — NOT a stub. Follow-up recorded below.
- **Files:** frontend/components/dashboard/media/ReviewQueue.tsx
- **Commit:** 572ac96

**Total deviations:** 2 (1 Rule 3, 1 Rule 2). No architectural change (Rule 4 not triggered); no scope creep; no backend recreated.

## Learnings
- **Stable-toast test mock is load-bearing.** The `ReviewQueue` fetch effect depends on `load` (a `useCallback` over `[toast]`). The real `useToast` returns a module-level-stable `toast`; a naive `useToast: () => ({ toast: jest.fn() })` mock returns a NEW `toast` each render → `load` changes → the `useEffect` refetches every render and re-adds a just-removed row. The mock must return a stable `toast` (factory-closure singleton) to reflect real behaviour. Also: a state update inside an awaited handler continuation commits reliably in the test only when the click is wrapped in `act(async …)` (React 19).

## Threat Model Coverage
- **T-24-23 (Information Disclosure — review-queue data in the browser):** mitigated — `ReviewQueue` fetches only the tenant-scoped, RLS-isolated `/api/v1/media/review-queue` (24-05) and renders only what the API returns; no client-side cross-tenant surface.
- **T-24-24 (Tampering — Replace re-upload without idempotency):** mitigated — the uploader sends a fresh secure-random `Idempotency-Key` on every accept (`makeIdempotencyKey`), so a double-submit never mints a duplicate asset (the 24-03 contract).
- **T-24-25 (Web-perf/SEO regression on storefront render):** mitigated — `AssetImage` preserves `alt` (the only present SEO surface; JSON-LD baseline null) and sets explicit `width`/`height` on the WebP derivative for CLS/LCP; no `next.config` change.

## Known Stubs
None. Keep is fully wired; the four `AssetImage` states each render real UI; the review queue lists real API data. The Replace→products routing is a deliberate, working navigation forced by the 24-05 DTO shape (no productId), documented as a follow-up — not a placeholder.

## Deferred / Follow-ups (by design, not this plan's failure)
- **Add `productId` to the review-queue `MediaAssetDto`** so Replace/Re-upload can host an inline uploader (POST `/products/{id}/image`) instead of routing to the products page. Out of scope here (merged backend contract; UI-only plan).
- **Live E2E** (a real upload→PENDING→ACTIVE + FAILED path + review-queue Keep/Replace at 375px in the running Compose stack with a real Keycloak vendor login) is the standard `/gsd:verify-work` manual step — per 24-VALIDATION Manual-Only. Needs MinIO + the RabbitMQ worker + vendor creds, and a **REBUILD-ALL** first (24-01 `apk add libwebp-tools`). Run at the phase PR.

## Self-Check: PASSED
- All 6 created files present on disk (asset-image.tsx + its test, ReviewQueue.tsx + its test, review/page.tsx, media-api.ts) and all 9 modified files present.
- All 3 task commits present in git: `6c3cb36` (Task 1), `572ac96` (Task 2), `c6b5993` (Task 3).
- Frontend: `npm run build` (tsc) **exit 0**; full Jest **58 suites / 371 tests green** (asset-image 6/6, ReviewQueue 5/5 included).
- Phase gates: `scripts/docs-freshness.sh` check mode **exit 0** (total 1636 / schema 58); `OpenApiSnapshotTest` **green in check mode** (media endpoints + 202 present) via `:core-java:integrationTest`.

---
*Phase: 24-image-architecture-cow-assets-safe-upload-pipeline*
*Completed: 2026-07-23*
