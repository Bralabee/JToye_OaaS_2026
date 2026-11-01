import apiClient from "@/lib/api-client"
import { makeIdempotencyKey } from "@/lib/webhooks-api"
import type { AcceptedAssetOutcome, MediaAsset, MediaUploadAccepted, Product } from "@/types/api"

/**
 * Typed client for the vendor media review/rejection queue (Phase 24 IMG-04,
 * extended by 27-01).
 *
 * Wraps the default authed `apiClient` (Bearer + X-Tenant-Id interceptors) so the
 * dashboard never hand-builds a media URL. Endpoints + DTO shape come from the
 * backend contract:
 *   - GET  /api/v1/media/review-queue        -> MediaAsset[]        (FAILED + flagged-ACTIVE + stalled PENDING)
 *   - POST /api/v1/media/{assetId}/keep      -> MediaAsset          (Keep: dismiss the flag)
 *   - POST /api/v1/media/{assetId}/reprocess -> MediaUploadAccepted (Re-process the retained bytes)
 *
 * <b>Re-process is not Replace.</b> Re-process (27-01) re-runs the pipeline over
 * the bytes the vendor ALREADY uploaded, and is offered exactly while
 * `MediaAsset.redrivable` is true. Replace — different bytes — is still not a
 * media endpoint: it is a re-upload through the product image accept
 * (POST /api/v1/products/{id}/image, 24-03); on worker success that mints a new
 * asset and repoints, and a FAILED replacement never clobbers the live image
 * (D-04a). {@link productImageUploadUrl} builds that target for the uploader.
 */

const BASE = "/api/v1/media"

/**
 * The tenant's media assets needing attention: FAILED uploads (a vendor-visible
 * failureReason + re-upload/re-process), flagged-ACTIVE assets (content-relevance
 * review — Keep or Replace), and, since 27-01 / D-10, PENDING uploads that have
 * visibly stalled. Tenant-isolated by RLS at the API.
 */
export async function fetchReviewQueue(): Promise<MediaAsset[]> {
  const res = await apiClient.get<MediaAsset[]>(`${BASE}/review-queue`)
  return res.data ?? []
}

/**
 * Keep (dismiss the content flag) — the asset stays ACTIVE, `flagged` clears,
 * and it drops out of the review queue (D-04). A foreign assetId returns 404
 * (RLS-scoped). Returns the updated asset.
 */
export async function keepAsset(assetId: string): Promise<MediaAsset> {
  const res = await apiClient.post<MediaAsset>(`${BASE}/${assetId}/keep`)
  return res.data
}

/**
 * Re-process (27-01 / D-04) — re-run the normalization pipeline over the RAW
 * bytes the vendor already uploaded. Valid only while `asset.redrivable` is true;
 * the API answers 409 with a machine-parseable `code` otherwise
 * (`media.quarantine_not_retained` / `media.already_active` /
 * `media.redrive_budget_exhausted`), and callers should surface that code rather
 * than a generic message. Returns 202 with the asset back in PENDING.
 *
 * Carries an `Idempotency-Key` from the same `makeIdempotencyKey` the uploader
 * uses (secure-random or throw — never `Math.random`), so a double-click or a
 * retried request never enqueues the work twice.
 */
export async function reprocessAsset(assetId: string): Promise<MediaUploadAccepted> {
  const res = await apiClient.post<MediaUploadAccepted>(
    `${BASE}/${assetId}/reprocess`,
    undefined,
    { headers: { "Idempotency-Key": makeIdempotencyKey() } }
  )
  return res.data
}

/**
 * The product image accept endpoint a Replace/Re-upload targets (24-03). The
 * ImageUploader owns the multipart POST + Idempotency-Key; this only builds the
 * URL so callers never hand-assemble the path.
 */
export function productImageUploadUrl(productId: string): string {
  return `/api/v1/products/${productId}/image`
}

/**
 * SW-ADE-02 (swarm epic #964): where an accepted product-image upload has got to. There is no
 * GET-by-asset endpoint, so this reads the two places the server reports one:
 *   1. GET /api/v1/products/{id} — its `media` list carries the asset once it is placed on the
 *      product (on worker success, or at accept time for a dedup share), with its status and,
 *      when ACTIVE, the derivative URL;
 *   2. GET /api/v1/media/review-queue — a FAILED upload is never placed (D-04a: a failed
 *      replacement never clobbers the live image), so it is found here, with its failureReason.
 * Neither listing the asset means it is still PENDING.
 */
export async function fetchAcceptedAssetOutcome(
  productId: string,
  assetId: string
): Promise<AcceptedAssetOutcome> {
  const productRes = await apiClient.get<Product>(`/api/v1/products/${productId}`)
  const placed = productRes.data?.media?.find((m) => m.assetId === assetId)
  if (placed && placed.status !== "PENDING") {
    return { status: placed.status, url: placed.url, failureReason: placed.failureReason }
  }
  const queued = (await fetchReviewQueue()).find((m) => m.assetId === assetId)
  if (queued?.status === "FAILED") {
    return { status: "FAILED", url: null, failureReason: queued.failureReason }
  }
  return { status: "PENDING", url: null, failureReason: null }
}

export const mediaApi = {
  fetchReviewQueue,
  keepAsset,
  reprocessAsset,
  productImageUploadUrl,
  fetchAcceptedAssetOutcome,
}

export default mediaApi
