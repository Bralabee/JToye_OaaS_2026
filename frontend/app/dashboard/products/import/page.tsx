"use client"

import { useState, useRef, useCallback } from "react"
import { motion } from "framer-motion"
import Link from "next/link"
import apiClient from "@/lib/api-client"
import { useToast } from "@/hooks/use-toast"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import {
  Upload, FileSpreadsheet, Camera, ArrowLeft, Download,
  CheckCircle2, XCircle, Loader2, ImageIcon, AlertTriangle, Package
} from "lucide-react"
import { fadeUp, useReducedMotionSafe } from "@/lib/motion"
import { cn } from "@/lib/utils"

interface RowError {
  row: number
  field: string
  message: string
}

interface ImportResult {
  totalRows: number
  successCount: number
  errorCount: number
  created: Array<{ id: string; title: string; sku: string; imageUrl: string | null; pricePennies: number }>
  errors: RowError[]
}

type Tab = "csv" | "photos"

export default function ImportProductsPage() {
  const [activeTab, setActiveTab] = useState<Tab>("csv")
  const pageVariants = useReducedMotionSafe(fadeUp)

  return (
    <motion.div
      variants={pageVariants}
      initial="hidden"
      animate="visible"
      className="space-y-6"
    >
      <div className="flex items-center gap-4">
        <Link
          href="/dashboard/products"
          aria-label="Back to products"
          className="text-ink-tertiary hover:text-ink-primary transition-colors duration-fast"
        >
          <ArrowLeft className="h-5 w-5" strokeWidth={1.5} />
        </Link>
        <div>
          <h1 className="font-display text-display-sm font-medium tracking-tight text-ink-primary">
            Import products
          </h1>
          <p className="mt-1 text-body-sm text-ink-secondary">
            Add products in bulk — CSV spreadsheet or photo scan
          </p>
        </div>
      </div>

      {/* Tab selector */}
      <div className="flex gap-2 border-b border-border-tone-subtle">
        <button
          type="button"
          onClick={() => setActiveTab("csv")}
          aria-pressed={activeTab === "csv"}
          className={cn(
            "flex items-center gap-2 px-4 py-2.5 text-body-sm font-medium border-b-2 transition-colors duration-fast motion-reduce:transition-none",
            activeTab === "csv"
              ? "border-brand-primary text-brand-primary"
              : "border-transparent text-ink-tertiary hover:text-ink-primary",
          )}
        >
          <FileSpreadsheet className="h-4 w-4" strokeWidth={1.5} />
          CSV spreadsheet
        </button>
        <button
          type="button"
          onClick={() => setActiveTab("photos")}
          aria-pressed={activeTab === "photos"}
          className={cn(
            "flex items-center gap-2 px-4 py-2.5 text-body-sm font-medium border-b-2 transition-colors duration-fast motion-reduce:transition-none",
            activeTab === "photos"
              ? "border-accent-editorial text-ink-primary"
              : "border-transparent text-ink-tertiary hover:text-ink-primary",
          )}
        >
          <Camera className="h-4 w-4" strokeWidth={1.5} />
          Photo scan (AI)
        </button>
      </div>

      {activeTab === "csv" ? <CsvImportTab /> : <PhotoImportTab />}
    </motion.div>
  )
}

// ============================================================
// CSV Import Tab
// ============================================================

function CsvImportTab() {
  const [uploading, setUploading] = useState(false)
  const [result, setResult] = useState<ImportResult | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const { toast } = useToast()

  const downloadTemplate = async () => {
    try {
      const res = await apiClient.get("/api/v1/products/template", { responseType: "blob" })
      const url = URL.createObjectURL(res.data)
      const a = document.createElement("a")
      a.href = url
      a.download = "product-import-template.csv"
      a.click()
      URL.revokeObjectURL(url)
    } catch {
      toast({ variant: "destructive", title: "Error", description: "Failed to download template" })
    }
  }

  const handleUpload = async (file: File) => {
    setUploading(true)
    setResult(null)

    const formData = new FormData()
    formData.append("file", file)

    try {
      const res = await apiClient.post("/api/v1/products/bulk/csv", formData, {
        headers: { "Content-Type": "multipart/form-data" },
      })
      setResult(res.data)
      if (res.data.successCount > 0) {
        toast({ title: "Import complete", description: `${res.data.successCount} products created` })
      }
    } catch {
      toast({ variant: "destructive", title: "Import failed", description: "Could not process CSV file" })
    } finally {
      setUploading(false)
    }
  }

  return (
    <div className="space-y-6">
      {/* Step 1: Download template */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Step 1: Download the template</CardTitle>
          <CardDescription>
            Fill in your product details in the spreadsheet. Required columns: title, price_pounds.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Button variant="secondary" onClick={downloadTemplate}>
            <Download className="h-4 w-4" strokeWidth={1.5} />
            Download CSV template
          </Button>
          <p className="mt-3 text-caption text-ink-tertiary">
            Columns: title, sku, price_pounds, ingredients, category, description, dietary_tags, prep_time_minutes, allergen_mask
          </p>
        </CardContent>
      </Card>

      {/* Step 2: Upload */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Step 2: Upload your filled CSV</CardTitle>
        </CardHeader>
        <CardContent>
          <div
            role="button"
            tabIndex={0}
            aria-label="Upload CSV file"
            className={cn(
              "border-2 border-dashed rounded-lg p-8 text-center transition-colors duration-fast cursor-pointer motion-reduce:transition-none",
              uploading
                ? "border-brand-primary-subtle bg-brand-primary-subtle/40"
                : "border-border-tone hover:border-brand-primary hover:bg-brand-primary-subtle/30",
            )}
            onClick={() => !uploading && fileRef.current?.click()}
            onKeyDown={(e) => {
              if ((e.key === "Enter" || e.key === " ") && !uploading) {
                e.preventDefault()
                fileRef.current?.click()
              }
            }}
          >
            {uploading ? (
              <div className="flex flex-col items-center gap-3">
                <Loader2 className="h-8 w-8 text-brand-primary animate-spin motion-reduce:animate-none" strokeWidth={1.5} />
                <span className="text-body-sm text-brand-primary">Importing products…</span>
              </div>
            ) : (
              <div className="flex flex-col items-center gap-3">
                <Upload className="h-8 w-8 text-ink-tertiary" strokeWidth={1.5} />
                <span className="text-body-sm text-ink-secondary">
                  Click to select CSV file or drag &amp; drop
                </span>
                <span className="text-caption text-ink-tertiary">
                  UTF-8 encoded, comma-separated
                </span>
              </div>
            )}
          </div>
          <input
            ref={fileRef}
            type="file"
            accept=".csv,text/csv"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0]
              if (file) handleUpload(file)
              e.target.value = ""
            }}
          />
        </CardContent>
      </Card>

      {/* Results */}
      {result && <ImportResultsPanel result={result} />}
    </div>
  )
}

// ============================================================
// Photo Import Tab (AI)
// ============================================================

function PhotoImportTab() {
  const [files, setFiles] = useState<File[]>([])
  const [previews, setPreviews] = useState<string[]>([])
  const [uploading, setUploading] = useState(false)
  const [progress, setProgress] = useState("")
  const [result, setResult] = useState<ImportResult | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const { toast } = useToast()

  const handleFiles = useCallback((newFiles: FileList | File[]) => {
    const imageFiles = Array.from(newFiles).filter(f => f.type.startsWith("image/"))
    if (imageFiles.length === 0) return

    setFiles(prev => [...prev, ...imageFiles])
    setPreviews(prev => [...prev, ...imageFiles.map(f => URL.createObjectURL(f))])
    setResult(null)
  }, [])

  const removeFile = (index: number) => {
    URL.revokeObjectURL(previews[index])
    setFiles(prev => prev.filter((_, i) => i !== index))
    setPreviews(prev => prev.filter((_, i) => i !== index))
  }

  const handleScan = async () => {
    if (files.length === 0) return

    setUploading(true)
    setProgress(`Analyzing ${files.length} images with AI…`)

    const formData = new FormData()
    files.forEach(f => formData.append("files", f))

    try {
      const res = await apiClient.post("/api/v1/products/bulk/images", formData, {
        headers: { "Content-Type": "multipart/form-data" },
        timeout: 300000, // 5 min — AI analysis takes time
      })
      setResult(res.data)
      const r = res.data as ImportResult
      toast({
        title: "Scan complete",
        description: `${r.successCount} products identified, ${r.errorCount} could not be recognized`,
      })
    } catch {
      toast({ variant: "destructive", title: "Scan failed", description: "Could not process images" })
    } finally {
      setUploading(false)
      setProgress("")
    }
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <Camera className="h-4 w-4 text-ink-secondary" strokeWidth={1.5} />
            Photograph your menu
          </CardTitle>
          <CardDescription>
            Take photos of each dish or product. AI will identify the item, suggest a name,
            ingredients, category, and dietary tags. Products are created as drafts — you set
            the price and review before publishing.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {/* Drop zone */}
          <div
            role="button"
            tabIndex={0}
            aria-label="Upload food photos"
            className="border-2 border-dashed border-accent-editorial/50 rounded-lg p-8 text-center transition-colors duration-fast cursor-pointer hover:border-accent-editorial hover:bg-accent-editorial/10 motion-reduce:transition-none"
            onClick={() => fileRef.current?.click()}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault()
                fileRef.current?.click()
              }
            }}
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault()
              handleFiles(e.dataTransfer.files)
            }}
          >
            <div className="flex flex-col items-center gap-3">
              <ImageIcon className="h-8 w-8 text-accent-editorial" strokeWidth={1.5} />
              <span className="text-body-sm text-ink-secondary">
                Drop food photos here or click to select
              </span>
              <span className="text-caption text-ink-tertiary">
                JPEG, PNG, WebP — select multiple at once
              </span>
            </div>
          </div>
          <input
            ref={fileRef}
            type="file"
            accept="image/*"
            multiple
            capture="environment"
            className="hidden"
            onChange={(e) => {
              if (e.target.files) handleFiles(e.target.files)
              e.target.value = ""
            }}
          />

          {/* Preview grid */}
          {files.length > 0 && (
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <span className="text-body-sm font-medium text-ink-secondary">
                  <span className="font-mono tabular-nums">{files.length}</span> image
                  {files.length !== 1 ? "s" : ""} selected
                </span>
                <div className="flex gap-2">
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => {
                      previews.forEach(URL.revokeObjectURL)
                      setFiles([])
                      setPreviews([])
                      setResult(null)
                    }}
                  >
                    Clear all
                  </Button>
                  <Button
                    variant="editorial"
                    size="sm"
                    onClick={handleScan}
                    disabled={uploading}
                    isLoading={uploading}
                  >
                    {!uploading && <Camera className="h-4 w-4" strokeWidth={1.5} />}
                    {uploading ? "Scanning…" : `Scan ${files.length} image${files.length !== 1 ? "s" : ""}`}
                  </Button>
                </div>
              </div>
              <div className="grid grid-cols-4 sm:grid-cols-6 md:grid-cols-8 gap-2">
                {previews.map((url, i) => (
                  <div key={i} className="relative aspect-square rounded-md overflow-hidden group">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={url} alt="" className="w-full h-full object-cover" />
                    <button
                      type="button"
                      aria-label="Remove image"
                      onClick={() => removeFile(i)}
                      className="absolute top-1 right-1 bg-danger text-ink-on-danger rounded-pill p-0.5 opacity-0 group-hover:opacity-100 focus-visible:opacity-100 transition-opacity duration-fast motion-reduce:transition-none"
                    >
                      <XCircle className="h-3.5 w-3.5" strokeWidth={1.5} />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Progress */}
          {uploading && progress && (
            <div
              className="flex items-center gap-2 text-body-sm text-brand-primary bg-brand-primary-subtle rounded-md px-3 py-2"
              aria-live="polite"
            >
              <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" strokeWidth={1.5} />
              {progress}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Results */}
      {result && <ImportResultsPanel result={result} isPhotoImport />}
    </div>
  )
}

// ============================================================
// Shared Results Panel
// ============================================================

function ImportResultsPanel({ result, isPhotoImport }: { result: ImportResult; isPhotoImport?: boolean }) {
  return (
    <Card variant="lifted">
      <CardHeader>
        <CardTitle className="text-base flex items-center gap-2">
          {result.successCount > 0 ? (
            <CheckCircle2 className="h-5 w-5 text-success" strokeWidth={1.5} />
          ) : (
            <AlertTriangle className="h-5 w-5 text-warning" strokeWidth={1.5} />
          )}
          Import results
        </CardTitle>
        <CardDescription>
          <span className="font-mono tabular-nums">{result.successCount}</span> of{" "}
          <span className="font-mono tabular-nums">{result.totalRows}</span>{" "}
          {isPhotoImport ? "images" : "rows"} imported successfully
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Summary */}
        <div className="flex gap-4">
          <div className="flex-1 rounded-md bg-success-subtle border border-success/30 p-3 text-center">
            <div className="font-display text-2xl font-semibold tabular-nums text-success">
              {result.successCount}
            </div>
            <div className="text-caption text-ink-secondary">Created</div>
          </div>
          <div className="flex-1 rounded-md bg-danger-subtle border border-danger/30 p-3 text-center">
            <div className="font-display text-2xl font-semibold tabular-nums text-danger">
              {result.errorCount}
            </div>
            <div className="text-caption text-ink-secondary">Errors</div>
          </div>
        </div>

        {/* Created products */}
        {result.created.length > 0 && (
          <div>
            <h4 className="text-body-sm font-medium text-ink-secondary mb-2">Created products</h4>
            <div className="space-y-1.5 max-h-60 overflow-y-auto">
              {result.created.map((p) => (
                <div
                  key={p.id}
                  className="flex items-center gap-3 text-body-sm bg-success-subtle/50 rounded-md px-3 py-2"
                >
                  <CheckCircle2 className="h-4 w-4 text-success flex-shrink-0" strokeWidth={1.5} />
                  {p.imageUrl ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      src={p.imageUrl}
                      alt=""
                      className="h-8 w-8 rounded-sm object-cover flex-shrink-0"
                    />
                  ) : (
                    <div className="h-8 w-8 rounded-sm bg-surface-subtle flex items-center justify-center flex-shrink-0">
                      <Package className="h-4 w-4 text-ink-quaternary" strokeWidth={1.5} />
                    </div>
                  )}
                  <div className="flex-1 min-w-0">
                    <span className="font-medium text-ink-primary truncate block">{p.title}</span>
                    <span className="font-mono tabular-nums text-caption text-ink-tertiary">
                      {p.sku}
                    </span>
                  </div>
                  <span className="font-mono tabular-nums text-caption font-medium text-ink-secondary">
                    {p.pricePennies > 0 ? `£${(p.pricePennies / 100).toFixed(2)}` : "Price needed"}
                  </span>
                </div>
              ))}
            </div>
            {isPhotoImport && (
              <p className="mt-2 text-caption text-ink-secondary flex items-center gap-1">
                <Badge variant="warning" size="sm" className="rounded-pill">
                  <AlertTriangle className="h-3 w-3" strokeWidth={1.5} />
                  Draft
                </Badge>
                AI-created products are drafts — set prices and review before publishing
              </p>
            )}
          </div>
        )}

        {/* Errors */}
        {result.errors.length > 0 && (
          <div>
            <h4 className="text-body-sm font-medium text-danger mb-2">Errors</h4>
            <div className="space-y-1 max-h-40 overflow-y-auto">
              {result.errors.map((err, i) => (
                <div
                  key={i}
                  className="flex items-start gap-2 text-body-sm bg-danger-subtle/50 rounded-md px-3 py-2"
                >
                  <XCircle className="h-4 w-4 text-danger flex-shrink-0 mt-0.5" strokeWidth={1.5} />
                  <div>
                    <span className="font-medium text-danger">
                      {isPhotoImport ? `Image ${err.row}` : `Row ${err.row}`}
                      {err.field && err.field !== "row" ? ` (${err.field})` : ""}:
                    </span>{" "}
                    <span className="text-ink-secondary">{err.message}</span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Actions */}
        <div className="flex gap-2 pt-2">
          <Button asChild variant="secondary">
            <Link href="/dashboard/products">
              <ArrowLeft className="h-4 w-4" strokeWidth={1.5} />
              Back to products
            </Link>
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
