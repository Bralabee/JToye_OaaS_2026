"use client"

import { AlertTriangle, Clock, Loader2, RefreshCw, RotateCcw } from "lucide-react"
import { SafeImage } from "@/components/ui/safe-image"
import type { MediaAssetStatus } from "@/types/api"

/**
 * Status-aware wrapper over {@link SafeImage} (Phase 24 IMG-04, extended by 27-01).
 *
 * A product image passes through the safe async pipeline, so its media asset has
 * a lifecycle the UI must render honestly rather than assuming a synchronous URL:
 *
 *   - PENDING          → a "Processing…" skeleton/spinner (the worker is
 *                        validating + normalizing to WebP off-thread).
 *   - PENDING & delayed → 27-01 / D-10: the upload has visibly STALLED (older
 *                        than the reaper grace). An indefinite spinner is a
 *                        dead end — the vendor cannot tell a slow upload from a
 *                        broken pipeline and has nothing to act on. This state
 *                        says so and offers a re-check.
 *   - ACTIVE           → the validated WebP derivative via SafeImage, with the
 *                        intrinsic `width`/`height` forwarded so the browser
 *                        reserves layout space (CLS/LCP — D-07). `alt` is
 *                        preserved through to the <img> (the only present SEO
 *                        surface; the JSON-LD baseline is null — 24-RESEARCH).
 *   - ACTIVE & flagged → the derivative PLUS a "Needs review" badge (a
 *                        content-relevance flag awaiting Keep/Replace — D-04).
 *   - FAILED           → an error card carrying the vendor-visible
 *                        `failureReason` and a "Re-upload" affordance, PLUS
 *                        (27-01) a secondary "Re-process" when `redrivable` —
 *                        the vendor's original bytes are still retained, so the
 *                        pipeline can simply be re-run over them.
 *
 * <b>Incremental Betterment.</b> The fresh-PENDING spinner and the FAILED
 * Re-upload control are the working goods here and are preserved exactly. The
 * delayed card is a NEW branch, not a replacement; Re-process is a SECONDARY
 * action beside Re-upload, not instead of it. When `delayed`/`redrivable` are
 * false this component renders what it rendered before 27-01.
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
  /**
   * 27-01 / D-10: this PENDING asset has stalled past the reaper grace. Swaps the
   * indefinite spinner for an explained, actionable card. Server-derived — never
   * compute it here from a timestamp.
   */
  delayed?: boolean
  /**
   * 27-01: the vendor's original quarantine bytes are still retained, so
   * Re-process can work. Gates the secondary FAILED action; when false the FAILED
   * card is exactly what it was before 27-01.
   */
  redrivable?: boolean
  className?: string
  loading?: "lazy" | "eager"
  /** Render the low-res thumbnail (grid cards) instead of the full derivative. */
  useThumbnail?: boolean
  /** Invoked by the FAILED-state "Re-upload" control. */
  onReupload?: () => void
  /** Invoked by the delayed-PENDING "Check again" control — re-fetch the asset. */
  onCheckAgain?: () => void
  /** Invoked by the FAILED-state "Re-process" control (shown only when `redrivable`). */
  onReprocess?: () => void
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
  delayed = false,
  redrivable = false,
  className = "",
  loading = "lazy",
  useThumbnail = false,
  onReupload,
  onCheckAgain,
  onReprocess,
}: AssetImageProps) {
  // PENDING & delayed (27-01 / D-10) — the upload has stalled. The bytes are
  // retained for 72 h, so nothing is lost; what the vendor needs is to be TOLD
  // that, and given something to do. Checked before the plain-PENDING branch.
  if (status === "PENDING" && delayed) {
    return (
      <div
        role="status"
        aria-live="polite"
        className={`flex flex-col items-center justify-center gap-2 bg-amber-50 p-3 text-center ${className}`}
      >
        <Clock className="h-6 w-6 text-amber-500" aria-hidden="true" />
        <p className="text-xs font-medium text-amber-800">Taking longer than usual</p>
        <p className="text-xs text-amber-700">
          Your upload is safe and still queued — we&apos;re retrying.
        </p>
        <button
          type="button"
          onClick={() => onCheckAgain?.()}
          className="inline-flex items-center gap-1 rounded-md bg-amber-600 px-2.5 py-1 text-xs font-medium text-white transition-colors hover:bg-amber-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-500"
        >
          <RefreshCw className="h-3 w-3" aria-hidden="true" />
          Check again
        </button>
      </div>
    )
  }

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
  // 27-01 adds Re-process as a SECONDARY action when the original bytes survive.
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
        {/* Mobile-first: the two actions STACK at 320 px rather than shrinking —
            a squeezed-to-fit control row is how the second action gets clipped. */}
        <div className="flex w-full flex-col items-center gap-2 sm:w-auto sm:flex-row sm:justify-center">
          <button
            type="button"
            onClick={() => onReupload?.()}
            className="inline-flex items-center gap-1 rounded-md bg-red-600 px-2.5 py-1 text-xs font-medium text-white transition-colors hover:bg-red-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
          >
            <RefreshCw className="h-3 w-3" aria-hidden="true" />
            Re-upload
          </button>
          {redrivable && (
            <button
              type="button"
              onClick={() => onReprocess?.()}
              className="inline-flex items-center gap-1 rounded-md border border-red-300 bg-white px-2.5 py-1 text-xs font-medium text-red-700 transition-colors hover:bg-red-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
            >
              <RotateCcw className="h-3 w-3" aria-hidden="true" />
              Re-process
            </button>
          )}
        </div>
        {redrivable && (
          <p className="text-xs text-red-600">Your original upload is still saved.</p>
        )}
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
