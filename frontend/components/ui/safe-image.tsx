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
}

/**
 * Image component with error fallback and lazy loading.
 * When the image fails to load (broken URL, 404, network error),
 * shows a graceful placeholder instead of a broken image icon.
 */
export function SafeImage({
  src,
  alt,
  className = "",
  fallbackClassName,
  fallbackIcon,
  loading = "lazy",
}: SafeImageProps) {
  const [failed, setFailed] = useState(false)
  const [loaded, setLoaded] = useState(false)

  if (!src || failed) {
    return (
      <div className={`flex items-center justify-center bg-slate-100 ${fallbackClassName || className}`}>
        {fallbackIcon || <ImageIcon className="h-1/3 w-1/3 text-slate-300" />}
      </div>
    )
  }

  return (
    <>
      {/* Loading skeleton shown until image loads */}
      {!loaded && (
        <div className={`animate-pulse bg-slate-200 ${className}`} />
      )}
      <img
        src={src}
        alt={alt}
        className={`${className} ${loaded ? "" : "hidden"}`}
        loading={loading}
        onLoad={() => setLoaded(true)}
        onError={() => setFailed(true)}
      />
    </>
  )
}
