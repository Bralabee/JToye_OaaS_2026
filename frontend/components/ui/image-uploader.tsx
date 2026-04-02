"use client"

import { useState, useRef, useCallback } from "react"
import { X, ImageIcon, Loader2 } from "lucide-react"
import apiClient from "@/lib/api-client"

const DIMENSION_REQUIREMENTS = {
  square: { minWidth: 400, minHeight: 400, label: "400x400px" },
  banner: { minWidth: 600, minHeight: 200, label: "600x200px" },
  logo: { minWidth: 100, minHeight: 100, label: "100x100px" },
} as const

/** Max dimension before we compress — keeps uploads fast and storage lean */
const MAX_DIMENSION = 1600
const JPEG_QUALITY = 0.85

export interface AiSuggestions {
  identifiedName?: string
  description?: string
  ingredients?: string
  category?: string
  dietaryTags?: string[]
  allergenWarnings?: string[]
  cuisineOrigin?: string
  confidence?: number
}

interface ImageUploaderProps {
  currentImageUrl?: string | null
  uploadUrl: string
  onUploadComplete: (imageUrl: string) => void
  onAiSuggestions?: (suggestions: AiSuggestions) => void
  onRemove?: () => void
  aspectRatio?: "square" | "banner" | "logo"
  label?: string
  disabled?: boolean
}

/**
 * Load an image file into an HTMLImageElement to read its dimensions.
 */
function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error("Could not read image. File may be corrupted."))
    img.src = URL.createObjectURL(file)
  })
}

/**
 * Compress and resize an image using canvas.
 * - Scales down to MAX_DIMENSION if larger
 * - Outputs as JPEG at JPEG_QUALITY (unless PNG with transparency needed)
 */
async function compressImage(file: File): Promise<File> {
  const img = await loadImage(file)
  const { naturalWidth: w, naturalHeight: h } = img

  // Skip compression for small images or GIFs (animation lost on canvas)
  if ((w <= MAX_DIMENSION && h <= MAX_DIMENSION && file.size < 500_000) || file.type === "image/gif") {
    URL.revokeObjectURL(img.src)
    return file
  }

  // Calculate target dimensions (maintain aspect ratio)
  let targetW = w
  let targetH = h
  if (w > MAX_DIMENSION || h > MAX_DIMENSION) {
    const scale = Math.min(MAX_DIMENSION / w, MAX_DIMENSION / h)
    targetW = Math.round(w * scale)
    targetH = Math.round(h * scale)
  }

  const canvas = document.createElement("canvas")
  canvas.width = targetW
  canvas.height = targetH
  const ctx = canvas.getContext("2d")!
  ctx.drawImage(img, 0, 0, targetW, targetH)
  URL.revokeObjectURL(img.src)

  // Output as JPEG (best compression for photos) unless PNG is needed for transparency
  const outputType = file.type === "image/png" ? "image/png" : "image/jpeg"
  const quality = outputType === "image/jpeg" ? JPEG_QUALITY : undefined

  return new Promise((resolve) => {
    canvas.toBlob(
      (blob) => {
        if (!blob) {
          resolve(file) // Fallback to original
          return
        }
        const ext = outputType === "image/png" ? ".png" : ".jpg"
        const name = file.name.replace(/\.[^.]+$/, ext)
        resolve(new File([blob], name, { type: outputType }))
      },
      outputType,
      quality
    )
  })
}

export function ImageUploader({
  currentImageUrl,
  uploadUrl,
  onUploadComplete,
  onAiSuggestions,
  onRemove,
  aspectRatio = "square",
  label = "Upload image",
  disabled = false,
}: ImageUploaderProps) {
  const [preview, setPreview] = useState<string | null>(null)
  const [uploading, setUploading] = useState(false)
  const [progress, setProgress] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [dragOver, setDragOver] = useState(false)
  const [imgBroken, setImgBroken] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const aspectClass =
    aspectRatio === "banner"
      ? "aspect-[3/1]"
      : aspectRatio === "logo"
        ? "aspect-square max-w-[160px]"
        : "aspect-square max-w-[200px]"

  const displayUrl = preview || (imgBroken ? null : currentImageUrl)
  const dimReq = DIMENSION_REQUIREMENTS[aspectRatio]

  const handleFile = useCallback(
    async (file: File) => {
      setError(null)
      setImgBroken(false)

      // Type check
      const allowed = ["image/jpeg", "image/png", "image/webp", "image/gif"]
      if (!allowed.includes(file.type)) {
        setError("Invalid file type. Use JPEG, PNG, WebP, or GIF.")
        return
      }
      if (file.size > 10 * 1024 * 1024) {
        setError("File too large. Maximum 10MB (will be compressed before upload).")
        return
      }

      // Dimension validation
      try {
        const img = await loadImage(file)
        const { naturalWidth: w, naturalHeight: h } = img
        URL.revokeObjectURL(img.src)

        if (w < dimReq.minWidth || h < dimReq.minHeight) {
          setError(
            `Image too small (${w}x${h}). Minimum ${dimReq.label} required.`
          )
          return
        }
      } catch {
        setError("Could not read image. File may be corrupted.")
        return
      }

      // Show local preview immediately
      const objectUrl = URL.createObjectURL(file)
      setPreview(objectUrl)
      setUploading(true)
      setProgress(0)

      try {
        // Compress before upload
        const compressed = await compressImage(file)
        const savedPct = file.size > 0 ? Math.round((1 - compressed.size / file.size) * 100) : 0
        if (savedPct > 5) {
          console.log(`Image compressed: ${(file.size / 1024).toFixed(0)}KB → ${(compressed.size / 1024).toFixed(0)}KB (-${savedPct}%)`)
        }

        const formData = new FormData()
        formData.append("file", compressed)

        const response = await apiClient.post(uploadUrl, formData, {
          headers: { "Content-Type": "multipart/form-data" },
          onUploadProgress: (e) => {
            if (e.total) {
              setProgress(Math.round((e.loaded * 100) / e.total))
            }
          },
        })

        const data = response.data

        // Handle both wrapped response (ImageUploadResponse) and direct DTO
        const product = data.product || data
        const newUrl =
          product.imageUrl || product.logoUrl || product.bannerUrl || displayUrl
        onUploadComplete(newUrl)

        // Pass AI suggestions to parent if available
        if (data.aiSuggestions && onAiSuggestions) {
          onAiSuggestions(data.aiSuggestions)
        }

        setPreview(null)
      } catch (err: unknown) {
        setPreview(null)
        const message =
          err instanceof Error ? err.message : "Upload failed. Please try again."
        if (typeof err === "object" && err !== null && "response" in err) {
          const axiosErr = err as { response?: { data?: { message?: string } } }
          setError(axiosErr.response?.data?.message || message)
        } else {
          setError(message)
        }
      } finally {
        setUploading(false)
        setProgress(0)
        URL.revokeObjectURL(objectUrl)
      }
    },
    [uploadUrl, onUploadComplete, displayUrl, dimReq]
  )

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault()
      setDragOver(false)
      const file = e.dataTransfer.files[0]
      if (file) handleFile(file)
    },
    [handleFile]
  )

  const handleRemove = useCallback(async () => {
    if (onRemove) {
      onRemove()
      setImgBroken(false)
    }
  }, [onRemove])

  return (
    <div className="space-y-2">
      {label && (
        <label className="text-sm font-medium text-slate-700">{label}</label>
      )}

      <div
        className={`relative ${aspectClass} w-full rounded-lg border-2 border-dashed transition-colors overflow-hidden ${
          dragOver
            ? "border-blue-400 bg-blue-50"
            : displayUrl
              ? "border-slate-200 bg-slate-50"
              : "border-slate-300 bg-slate-50 hover:border-slate-400"
        } ${disabled || uploading ? "pointer-events-none opacity-60" : "cursor-pointer"}`}
        onDragOver={(e) => {
          e.preventDefault()
          setDragOver(true)
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
        onClick={() => !uploading && fileInputRef.current?.click()}
      >
        {/* Current or preview image */}
        {displayUrl ? (
          <>
            <img
              src={displayUrl}
              alt="Preview"
              className="absolute inset-0 w-full h-full object-cover"
              onError={() => setImgBroken(true)}
            />
            {/* Overlay with replace action */}
            {!uploading && (
              <div className="absolute inset-0 bg-black/0 hover:bg-black/40 transition-colors flex items-center justify-center group">
                <span className="text-white text-sm font-medium opacity-0 group-hover:opacity-100 transition-opacity">
                  Click to replace
                </span>
              </div>
            )}
          </>
        ) : (
          /* Empty state */
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 text-slate-400">
            <ImageIcon className="h-8 w-8" />
            <span className="text-sm text-center px-2">
              {dragOver ? "Drop image here" : "Drag & drop or click to upload"}
            </span>
            <span className="text-xs text-slate-300 text-center">
              JPEG, PNG, WebP, GIF &middot; min {dimReq.label}
            </span>
          </div>
        )}

        {/* Upload progress overlay */}
        {uploading && (
          <div className="absolute inset-0 bg-black/50 flex flex-col items-center justify-center gap-3">
            <Loader2 className="h-6 w-6 text-white animate-spin" />
            <div className="w-3/4 bg-white/20 rounded-full h-2">
              <div
                className="bg-white h-2 rounded-full transition-all duration-300"
                style={{ width: `${progress}%` }}
              />
            </div>
            <span className="text-white text-sm">{progress}%</span>
          </div>
        )}

        {/* Remove button */}
        {displayUrl && !uploading && onRemove && (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation()
              handleRemove()
            }}
            className="absolute top-2 right-2 bg-red-500 hover:bg-red-600 text-white rounded-full p-1 shadow-sm transition-colors"
          >
            <X className="h-4 w-4" />
          </button>
        )}
      </div>

      {/* Hidden file input */}
      <input
        ref={fileInputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp,image/gif"
        capture="environment"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) handleFile(file)
          e.target.value = ""
        }}
        disabled={disabled || uploading}
      />

      {/* Error message */}
      {error && <p className="text-sm text-red-500">{error}</p>}
    </div>
  )
}
