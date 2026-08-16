"use client"

import { useState, useRef, useCallback } from "react"
import { X, ImageIcon, Loader2 } from "lucide-react"
import apiClient from "@/lib/api-client"
import { AssetImage } from "@/components/ui/asset-image"
import { makeIdempotencyKey } from "@/lib/webhooks-api"
import type { MediaUploadAccepted } from "@/types/api"

const DIMENSION_REQUIREMENTS = {
  square: { minWidth: 400, minHeight: 400, label: "400x400px" },
  banner: { minWidth: 600, minHeight: 200, label: "600x200px" },
  logo: { minWidth: 100, minHeight: 100, label: "100x100px" },
} as const

/** Max dimension before we compress — keeps uploads fast and storage lean */
const MAX_DIMENSION = 1600
/** First rung of the JPEG quality ladder (best visual quality). */
const JPEG_QUALITY = 0.85

/**
 * Authoritative upload cap, mirrored from the server so we never POST a file the
 * server will reject. Keep in sync with core-java application.yml:
 *   spring.servlet.multipart.max-file-size: 5MB
 *   storage.max-file-size-bytes: 5242880
 * The server is the source of truth — do NOT relax this client mirror.
 */
export const SERVER_MAX_BYTES = 5 * 1024 * 1024

/**
 * Browser-safety cap. This is NOT the upload limit — it only guards the canvas
 * decoder from absurdly large files that could exhaust browser memory/CPU before
 * compression even gets a chance. Normal phone photos (12-20MB) sit well under it.
 */
export const BROWSER_SAFETY_MAX_BYTES = 50 * 1024 * 1024

/**
 * JPEG re-encode quality ladder. Compression steps down through these rungs until
 * the output fits under SERVER_MAX_BYTES, trading a little quality for size only
 * when a file is stubbornly large.
 */
export const JPEG_QUALITY_LADDER = [JPEG_QUALITY, 0.75, 0.65]

const ALLOWED_TYPES = ["image/jpeg", "image/png", "image/webp", "image/gif"]

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
  // Phase 24 (IMG-04): the product image endpoint is now an async accept — it
  // returns 202 { assetId, status:"PENDING" } and the worker validates +
  // normalizes off-thread. Callers that target that endpoint receive the
  // accepted asset here (the uploader shows the "Processing…" state meanwhile).
  // Optional so legacy synchronous callers (shop logo/banner) are unaffected.
  onUploadAccepted?: (asset: MediaUploadAccepted) => void
  onAiSuggestions?: (suggestions: AiSuggestions) => void
  onRemove?: () => void
  aspectRatio?: "square" | "banner" | "logo"
  label?: string
  disabled?: boolean
}

/**
 * Type-guard for the Phase 24 async accept shape. The upload endpoint returns
 * 202 { assetId, status:"PENDING" } (NO servable image URL yet); a legacy
 * synchronous endpoint returns a DTO carrying imageUrl/logoUrl/bannerUrl.
 */
function isAsyncAccept(data: unknown): data is MediaUploadAccepted {
  return (
    typeof data === "object" &&
    data !== null &&
    typeof (data as { assetId?: unknown }).assetId === "string" &&
    typeof (data as { status?: unknown }).status === "string"
  )
}

/**
 * Pure type gate. Returns an error string for unsupported types, else null.
 * DOM-free so it is unit-testable without a browser.
 */
export function validateFileType(fileType: string): string | null {
  if (!ALLOWED_TYPES.includes(fileType)) {
    return "Invalid file type. Use JPEG, PNG, WebP, or GIF."
  }
  return null
}

/**
 * Pure door gate that runs BEFORE compression. Its job is only to reject files
 * that compression genuinely cannot rescue — it must let normal large phone
 * photos (12-20MB) through so the canvas pipeline can shrink them.
 *
 * Rules:
 *  (a) Animated GIFs cannot survive canvas compression (animation is lost), so
 *      the 5MB server cap is firm for GIFs and enforced up front.
 *  (b) Any file above the 50MB browser-safety cap is rejected honestly — we do
 *      not promise compression we cannot safely deliver on a huge file.
 *  (c) Everything else passes; the 5MB enforcement happens AFTER compression.
 *
 * DOM-free (reads only file.type / file.size) so it is unit-testable.
 */
export function preflightSizeGate(file: { type: string; size: number }): string | null {
  if (file.type === "image/gif" && file.size > SERVER_MAX_BYTES) {
    return "Animated GIFs can't be compressed without losing their animation, so the 5MB limit is firm for GIFs. Please upload a GIF under 5MB, or use a JPEG or PNG."
  }
  if (file.size > BROWSER_SAFETY_MAX_BYTES) {
    return "That image is too large to process in your browser (over 50MB). Please choose an image under 50MB."
  }
  return null
}

/**
 * Pure encoding policy. Decides the output type and JPEG quality ladder for the
 * canvas orchestrator:
 *  - GIF -> keep as GIF, no re-encode (animation must be preserved).
 *  - PNG WITH transparency -> keep as lossless PNG (JPEG has no alpha channel).
 *  - Everything else (JPEG, WebP, and NON-transparent PNG) -> JPEG with the
 *    quality ladder (JPEG is the right format for opaque photos).
 * DOM-free so it is unit-testable.
 */
export function chooseEncoding(
  fileType: string,
  hasAlpha: boolean
): { type: string; qualities: number[] } {
  if (fileType === "image/gif") {
    return { type: "image/gif", qualities: [] }
  }
  if (fileType === "image/png" && hasAlpha) {
    return { type: "image/png", qualities: [] }
  }
  return { type: "image/jpeg", qualities: JPEG_QUALITY_LADDER }
}

/**
 * Pure server-limit enforcement, applied to the COMPRESSED result. Returns an
 * honest error when compression could not get the image under the 5MB server
 * cap, else null. DOM-free so it is unit-testable.
 */
export function enforceServerLimit(sizeBytes: number): string | null {
  if (sizeBytes > SERVER_MAX_BYTES) {
    return "We couldn't compress this image under the 5MB upload limit. Please try a smaller image."
  }
  return null
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
 * Sample a 2D canvas and report whether any pixel is even partially transparent.
 * Canvas-dependent, so it stays internal and is not unit-tested in jsdom.
 */
function hasAlphaChannel(
  ctx: CanvasRenderingContext2D,
  width: number,
  height: number
): boolean {
  const { data } = ctx.getImageData(0, 0, width, height)
  for (let i = 3; i < data.length; i += 4) {
    if (data[i] < 255) {
      return true
    }
  }
  return false
}

/**
 * Encode a canvas to a Blob at the given type/quality, promisified.
 */
function canvasToBlob(
  canvas: HTMLCanvasElement,
  type: string,
  quality?: number
): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, type, quality))
}

/**
 * Compress and resize an image using canvas — the orchestrator that turns a big
 * phone photo into an upload-sized file.
 *  - GIFs reaching here are already <= 5MB (preflight rejects larger); returned
 *    unchanged so animation is preserved.
 *  - Dimensions are only ever shrunk to MAX_DIMENSION, never upscaled.
 *  - Non-transparent PNGs are re-encoded as JPEG; JPEG quality steps down through
 *    the ladder until the output fits under the 5MB server cap.
 * The final 5MB enforcement lives in handleFile via enforceServerLimit — this
 * function does its best and never upscales to try harder.
 */
async function compressImage(file: File): Promise<File> {
  // Animated GIFs cannot be redrawn without losing animation. Preflight already
  // guaranteed GIFs here are within the server cap, so pass through untouched.
  if (file.type === "image/gif") {
    return file
  }

  const img = await loadImage(file)
  const { naturalWidth: w, naturalHeight: h } = img

  // Only shrink; never upscale. Keep native size when already within bounds.
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

  // Transparency only matters for PNG (the one lossless-with-alpha format we keep).
  const hasAlpha = file.type === "image/png" ? hasAlphaChannel(ctx, targetW, targetH) : false
  const plan = chooseEncoding(file.type, hasAlpha)

  const toFile = (blob: Blob, type: string): File => {
    const ext = type === "image/png" ? ".png" : ".jpg"
    const name = file.name.replace(/\.[^.]+$/, ext)
    return new File([blob], name, { type })
  }

  // Transparent PNG: single lossless encode, no quality ladder.
  if (plan.qualities.length === 0) {
    const blob = await canvasToBlob(canvas, plan.type)
    return blob ? toFile(blob, plan.type) : file
  }

  // JPEG ladder: step down quality until the output fits under the server cap.
  // Track the smallest blob produced so we can return it if none fit — we never
  // upscale or raise dimensions; handleFile's enforceServerLimit rejects honestly.
  let smallest: Blob | null = null
  for (const quality of plan.qualities) {
    const blob = await canvasToBlob(canvas, plan.type, quality)
    if (!blob) {
      continue
    }
    if (!smallest || blob.size < smallest.size) {
      smallest = blob
    }
    if (blob.size <= SERVER_MAX_BYTES) {
      return toFile(blob, plan.type)
    }
  }

  return smallest ? toFile(smallest, plan.type) : file
}

export function ImageUploader({
  currentImageUrl,
  uploadUrl,
  onUploadComplete,
  onUploadAccepted,
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
  // Phase 24 (IMG-04): true once the async accept (202/PENDING) has come back —
  // the worker is normalizing off-thread, so we show a "Processing…" state
  // instead of a stale preview or a not-yet-servable derivative URL.
  const [processing, setProcessing] = useState(false)
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
      setProcessing(false)

      // 1. Type gate.
      const typeError = validateFileType(file.type)
      if (typeError) {
        setError(typeError)
        return
      }

      // 2. Door gate — browser-safety cap only. This lets normal large phone
      // photos through; the 5MB server cap is enforced AFTER compression.
      const preflightError = preflightSizeGate(file)
      if (preflightError) {
        setError(preflightError)
        return
      }

      // 3. Dimension validation.
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

      // 4. Show local preview immediately.
      const objectUrl = URL.createObjectURL(file)
      setPreview(objectUrl)
      setUploading(true)
      setProgress(0)

      try {
        // 5. Compress before the size gate.
        const compressed = await compressImage(file)

        // 6. Enforce the 5MB server cap on the compressed result. If it still
        // does not fit, reject honestly and do NOT POST an oversized multipart.
        const overLimit = enforceServerLimit(compressed.size)
        if (overLimit) {
          setError(overLimit)
          setPreview(null)
          return
        }

        // 7. Upload the compressed file. Send an Idempotency-Key so a
        // double-submit against the async accept never mints a duplicate asset
        // (D-06 / T-24-24). The key is a secure-random UUID (never Math.random).
        const formData = new FormData()
        formData.append("file", compressed)

        const response = await apiClient.post(uploadUrl, formData, {
          headers: {
            "Content-Type": "multipart/form-data",
            "Idempotency-Key": makeIdempotencyKey(),
          },
          onUploadProgress: (e) => {
            if (e.total) {
              setProgress(Math.round((e.loaded * 100) / e.total))
            }
          },
        })

        const data = response.data ?? {}

        // Phase 24 async accept: the endpoint returned 202 { assetId, status:
        // "PENDING" } — the worker is validating + normalizing off-thread and
        // there is NO servable image URL yet. Surface the processing state and
        // hand the accepted asset to the parent; drop the local preview since we
        // show the "Processing…" indicator instead.
        if (isAsyncAccept(data)) {
          setProcessing(true)
          setPreview(null)
          onUploadAccepted?.({ assetId: data.assetId, status: data.status })
          // AI suggestions come from the advisory vision stage the worker runs
          // off-thread — they are not part of the synchronous 202 accept body.
          return
        }

        // Legacy synchronous path (shop logo/banner endpoints not migrated to
        // the async pipeline): the response carries a ready image URL.
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

      {/* The drop target keeps ONLY the drag handlers. Its `onClick` moved to a
          stretched <button> rendered last inside it (31-02 / LGL-02) — the same
          idiom the storefront dish card uses (#446), and for the same reason:
          `role="button"` on this wrapper would make the "Remove" control inside
          it presentational, trading one a11y defect for another. `group` is on
          this element now so the "Click to replace" veil still lights up when
          the pointer is over the stretched button, which is a sibling of the
          veil rather than its ancestor. */}
      <div
        className={`group relative ${aspectClass} w-full rounded-lg border-2 border-dashed transition-colors overflow-hidden ${
          dragOver
            ? "border-blue-400 bg-blue-50"
            : displayUrl
              ? "border-slate-200 bg-slate-50"
              : "border-slate-300 bg-slate-50 hover:border-slate-400"
        } ${disabled || uploading || processing ? "pointer-events-none opacity-60" : "cursor-pointer"}`}
        onDragOver={(e) => {
          e.preventDefault()
          setDragOver(true)
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
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
              <div className="absolute inset-0 bg-black/0 group-hover:bg-black/40 transition-colors flex items-center justify-center">
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

        {/* Async processing overlay (Phase 24): the accept came back 202/PENDING
            and the worker is normalizing the upload to WebP off-thread. */}
        {processing && (
          <AssetImage
            status="PENDING"
            url={null}
            alt="Processing uploaded image"
            className="absolute inset-0"
          />
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
            aria-label="Remove image"
            className="absolute top-2 right-2 z-20 bg-red-500 hover:bg-red-600 text-white rounded-full p-1 shadow-sm transition-colors"
          >
            <X className="h-4 w-4" />
          </button>
        )}

        {/* Stretched picker trigger. Rendered LAST so it stacks over the
            (positioned) preview image and the veil; `z-10` keeps it under the
            `z-20` Remove control, which must stay independently clickable. It
            has no box of its own, so the layout is unchanged. */}
        <button
          type="button"
          disabled={disabled || uploading || processing}
          onClick={() => !uploading && !processing && fileInputRef.current?.click()}
          className="absolute inset-0 z-10 h-full w-full cursor-pointer rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-blue-500"
        >
          <span className="sr-only">
            {displayUrl ? "Replace image" : "Upload an image"}
            {label ? ` for ${label}` : ""}
          </span>
        </button>
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
