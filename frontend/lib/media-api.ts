import apiClient from "@/lib/api-client"
import type { MediaAsset } from "@/types/api"

/**
 * Typed client for the vendor media review/rejection queue (Phase 24 IMG-04).
 *
 * Wraps the default authed `apiClient` (Bearer + X-Tenant-Id interceptors) so the
 * dashboard never hand-builds a media URL. Endpoints + DTO shape come from the
 * backend contract shipped in 24-05:
 *   - GET  /api/v1/media/review-queue    -> MediaAsset[]  (FAILED + flagged-ACTIVE)
 *   - POST /api/v1/media/{assetId}/keep  -> MediaAsset    (Keep: dismiss the flag)
 *
 * Replace is deliberately NOT a media endpoint — a replacement is a re-upload
 * through the product image accept (POST /api/v1/products/{id}/image, 24-03);
 * on worker success it mints a new asset and repoints, and a FAILED replacement
 * never clobbers the live image (D-04a). {@link productImageUploadUrl} builds
 * that target for the uploader.
 */

const BASE = "/api/v1/media"

/**
 * The tenant's media assets needing attention: FAILED uploads (a vendor-visible
 * failureReason + re-upload) and flagged-ACTIVE assets (content-relevance review
 * — Keep or Replace). Tenant-isolated by RLS at the API.
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
 * The product image accept endpoint a Replace/Re-upload targets (24-03). The
 * ImageUploader owns the multipart POST + Idempotency-Key; this only builds the
 * URL so callers never hand-assemble the path.
 */
export function productImageUploadUrl(productId: string): string {
  return `/api/v1/products/${productId}/image`
}

export const mediaApi = { fetchReviewQueue, keepAsset, productImageUploadUrl }

export default mediaApi
