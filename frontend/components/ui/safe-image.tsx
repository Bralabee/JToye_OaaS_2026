"use client"

import { useState } from "react"
import { ImageIcon } from "lucide-react"

interface SafeImageProps {
  src: string | null | undefined
  alt: string
  className?: string
  fallbackClassName?: string
  fallbackIcon?: React.ReactNode
  loading?: "lazy" | "eager"
  // Intrinsic pixel dimensions of the source. Forwarded to the <img> so the
  // browser can reserve layout space and avoid CLS (Phase 24 D-07: the WebP
  // derivative carries media_asset.width/height). Optional + backward-compatible.
  width?: number
  height?: number
  // FE-2: a resource-fetch hint for the browser's preload scanner, forwarded
  // verbatim to the <img>'s `fetchpriority` attribute. Optional and
  // undefined by default — every existing call site is unaffected, and only
  // the ONE genuine LCP candidate on a page should ever pass "high" (marking
  // several images "high" defeats the hint by spreading priority evenly).
  fetchPriority?: "high" | "low" | "auto"
}

/**
 * Image component with error fallback and lazy loading.
 * Shows a placeholder when the image fails to load or src is null.
 */
export function SafeImage({
  src,
  alt,
  className = "",
  fallbackClassName,
  fallbackIcon,
  loading = "lazy",
  width,
  height,
  fetchPriority,
}: SafeImageProps) {
  const [failed, setFailed] = useState(false)

  if (!src || failed) {
    return (
      <div className={`flex items-center justify-center bg-slate-100 ${fallbackClassName || className}`}>
        {fallbackIcon || <ImageIcon className="h-1/3 w-1/3 text-slate-300" />}
      </div>
    )
  }

  return (
    <img
      src={src}
      alt={alt}
      className={className}
      loading={loading}
      width={width}
      height={height}
      fetchPriority={fetchPriority}
      onError={() => setFailed(true)}
    />
  )
}
