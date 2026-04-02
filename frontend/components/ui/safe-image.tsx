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
 * Shows a placeholder when the image fails to load or src is null.
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
      onError={() => setFailed(true)}
    />
  )
}
