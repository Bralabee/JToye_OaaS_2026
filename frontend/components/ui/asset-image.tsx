"use client"

import { AlertTriangle, Loader2, RefreshCw } from "lucide-react"
import { SafeImage } from "@/components/ui/safe-image"
import type { MediaAssetStatus } from "@/types/api"

/**
 * Status-aware wrapper over {@link SafeImage} (Phase 24 IMG-04).
 *
 * A product image passes through the safe async pipeline, so its media asset has
 * a lifecycle the UI must render honestly rather than assuming a synchronous URL:
 *
 *   - PENDING          → a "Processing…" skeleton/spinner (the worker is
 *                        validating + normalizing to WebP off-thread).
 *   - ACTIVE           → the validated WebP derivative via SafeImage, with the
 *                        intrinsic `width`/`height` forwarded so the browser
 *                        reserves layout space (CLS/LCP — D-07). `alt` is
 *                        preserved through to the <img> (the only present SEO
 *                        surface; the JSON-LD baseline is null — 24-RESEARCH).
 *   - ACTIVE & flagged → the derivative PLUS a "Needs review" badge (a
 *                        content-relevance flag awaiting Keep/Replace — D-04).
 *   - FAILED           → an error card carrying the vendor-visible
 *                        `failureReason` and a "Re-upload" affordance (no
 *                        auto-retry this phase — D-04).
 *
 * No `next.config.mjs` change is needed: derivatives stay on the same MinIO
 * host/path the existing remotePatterns already allow.
 */
export interface AssetImageProps {
  status: MediaAssetStatus
  /** The ACTIVE derivative URL (null for PENDING/FAILED). */
  url: string | null | undefined
  /** The ACTIVE thumbnail URL — used for grid cards when `useThumbnail`. */
  thumbnailUrl?: string | null
  alt: string
  width?: number | null
  height?: number | null
  /** Content-relevance flag on an ACTIVE asset → renders the "Needs review" badge. */
  flagged?: boolean
  /** Vendor-visible worker message, surfaced in the FAILED state. */
  failureReason?: string | null
  className?: string
  loading?: "lazy" | "eager"
  /** Render the low-res thumbnail (grid cards) instead of the full derivative. */
  useThumbnail?: boolean
  /** Invoked by the FAILED-state "Re-upload" control. */
  onReupload?: () => void
}

export function AssetImage({
  status,
  url,
  thumbnailUrl,
  alt,
  width,
  height,
  flagged = false,
  failureReason,
  className = "",
  loading = "lazy",
  useThumbnail = false,
  onReupload,
}: AssetImageProps) {
  // PENDING — the async worker is still validating/normalizing. No servable
  // object yet, so show a processing indicator rather than a broken <img>.
  if (status === "PENDING") {
    return (
      <div
        role="status"
        aria-live="polite"
        className={`flex flex-col items-center justify-center gap-2 bg-slate-100 text-slate-500 ${className}`}
      >
        <Loader2 className="h-6 w-6 animate-spin text-orange-500" aria-hidden="true" />
        <span className="text-xs font-medium">Processing…</span>
      </div>
    )
  }

  // FAILED — the upload was rejected (allowlist/decode/oversize). Surface the
  // reason + a Re-upload control. A FAILED replacement never clobbered the live
  // image (CoW mints on worker success only — D-04a), so this is safe to retry.
  if (status === "FAILED") {
    return (
      <div
        role="alert"
        className={`flex flex-col items-center justify-center gap-2 bg-red-50 p-3 text-center ${className}`}
      >
        <AlertTriangle className="h-6 w-6 text-red-500" aria-hidden="true" />
        <p className="text-xs font-medium text-red-700">
          {failureReason || "Image processing failed."}
        </p>
        <button
          type="button"
          onClick={() => onReupload?.()}
          className="inline-flex items-center gap-1 rounded-md bg-red-600 px-2.5 py-1 text-xs font-medium text-white transition-colors hover:bg-red-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
        >
          <RefreshCw className="h-3 w-3" aria-hidden="true" />
          Re-upload
        </button>
      </div>
    )
  }

  // ACTIVE — render the validated WebP derivative (or thumbnail for grid cards).
  const src = useThumbnail && thumbnailUrl ? thumbnailUrl : url

  return (
    <div className={`relative ${className}`}>
      <SafeImage
        src={src}
        alt={alt}
        className="h-full w-full object-cover"
        loading={loading}
        width={width ?? undefined}
        height={height ?? undefined}
      />
      {flagged && (
        <span className="absolute left-2 top-2 inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700 shadow-sm">
          <AlertTriangle className="h-3 w-3" aria-hidden="true" />
          Needs review
        </span>
      )}
    </div>
  )
}
