"use client"

import { useState, useRef, useCallback } from "react"
import { Upload, X, ImageIcon, Loader2 } from "lucide-react"
import apiClient from "@/lib/api-client"

interface ImageUploaderProps {
  currentImageUrl?: string | null
  uploadUrl: string
  onUploadComplete: (imageUrl: string) => void
  onRemove?: () => void
  aspectRatio?: "square" | "banner" | "logo"
  label?: string
  disabled?: boolean
}

export function ImageUploader({
  currentImageUrl,
  uploadUrl,
  onUploadComplete,
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
  const fileInputRef = useRef<HTMLInputElement>(null)

  const aspectClass =
    aspectRatio === "banner"
      ? "aspect-[3/1]"
      : aspectRatio === "logo"
        ? "aspect-square max-w-[160px]"
        : "aspect-square max-w-[200px]"

  const displayUrl = preview || currentImageUrl

  const handleFile = useCallback(
    async (file: File) => {
      setError(null)

      // Client-side validation
      const allowed = ["image/jpeg", "image/png", "image/webp", "image/gif"]
      if (!allowed.includes(file.type)) {
        setError("Invalid file type. Use JPEG, PNG, WebP, or GIF.")
        return
      }
      if (file.size > 5 * 1024 * 1024) {
        setError("File too large. Maximum 5MB.")
        return
      }

      // Show local preview immediately
      const objectUrl = URL.createObjectURL(file)
      setPreview(objectUrl)

      // Upload
      setUploading(true)
      setProgress(0)

      const formData = new FormData()
      formData.append("file", file)

      try {
        const response = await apiClient.post(uploadUrl, formData, {
          headers: { "Content-Type": "multipart/form-data" },
          onUploadProgress: (e) => {
            if (e.total) {
              setProgress(Math.round((e.loaded * 100) / e.total))
            }
          },
        })

        // The response contains the updated entity DTO with the new image URL
        const data = response.data
        const newUrl =
          data.imageUrl || data.logoUrl || data.bannerUrl || displayUrl
        onUploadComplete(newUrl)
        setPreview(null) // Clear preview, use the real URL now
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
    [uploadUrl, onUploadComplete, displayUrl]
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
            <span className="text-sm">
              {dragOver ? "Drop image here" : "Drag & drop or click to upload"}
            </span>
            <span className="text-xs text-slate-300">
              JPEG, PNG, WebP, GIF up to 5MB
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
          e.target.value = "" // Reset so same file can be re-selected
        }}
        disabled={disabled || uploading}
      />

      {/* Error message */}
      {error && <p className="text-sm text-red-500">{error}</p>}
    </div>
  )
}
