"use client"

import { useState, useRef, useCallback } from "react"
import { motion } from "framer-motion"
import Link from "next/link"
import apiClient from "@/lib/api-client"
import { useToast } from "@/hooks/use-toast"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Upload, FileSpreadsheet, Camera, ArrowLeft, Download,
  CheckCircle2, XCircle, Loader2, ImageIcon, AlertTriangle, Package
} from "lucide-react"

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
  const { toast } = useToast()

  return (
    <div className="space-y-6">
      <motion.div initial={{ opacity: 0, y: -20 }} animate={{ opacity: 1, y: 0 }}
        className="flex items-center gap-4">
        <Link href="/dashboard/products" className="text-slate-400 hover:text-slate-600">
          <ArrowLeft className="h-5 w-5" />
        </Link>
        <div>
          <h1 className="text-3xl font-bold text-slate-900">Import Products</h1>
          <p className="mt-1 text-slate-600">Add products in bulk — CSV spreadsheet or photo scan</p>
        </div>
      </motion.div>

      {/* Tab selector */}
      <div className="flex gap-2 border-b border-slate-200">
        <button
          onClick={() => setActiveTab("csv")}
          className={`flex items-center gap-2 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
            activeTab === "csv"
              ? "border-blue-600 text-blue-600"
              : "border-transparent text-slate-500 hover:text-slate-700"
          }`}
        >
          <FileSpreadsheet className="h-4 w-4" />
          CSV Spreadsheet
        </button>
        <button
          onClick={() => setActiveTab("photos")}
          className={`flex items-center gap-2 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
            activeTab === "photos"
              ? "border-violet-600 text-violet-600"
              : "border-transparent text-slate-500 hover:text-slate-700"
          }`}
        >
          <Camera className="h-4 w-4" />
          Photo Scan (AI)
        </button>
      </div>

      {activeTab === "csv" ? <CsvImportTab /> : <PhotoImportTab />}
    </div>
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
      const res = await apiClient.get("/products/template", { responseType: "blob" })
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
      const res = await apiClient.post("/products/bulk/csv", formData, {
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
          <CardDescription>Fill in your product details in the spreadsheet. Required columns: title, price_pounds.</CardDescription>
        </CardHeader>
        <CardContent>
          <Button variant="outline" onClick={downloadTemplate} className="gap-2">
            <Download className="h-4 w-4" />
            Download CSV Template
          </Button>
          <p className="mt-3 text-xs text-slate-400">
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
            className={`border-2 border-dashed rounded-xl p-8 text-center transition-colors cursor-pointer
              ${uploading ? "border-blue-300 bg-blue-50" : "border-slate-300 hover:border-blue-400 hover:bg-blue-50/50"}`}
            onClick={() => !uploading && fileRef.current?.click()}
          >
            {uploading ? (
              <div className="flex flex-col items-center gap-3">
                <Loader2 className="h-8 w-8 text-blue-500 animate-spin" />
                <span className="text-sm text-blue-600">Importing products...</span>
              </div>
            ) : (
              <div className="flex flex-col items-center gap-3">
                <Upload className="h-8 w-8 text-slate-400" />
                <span className="text-sm text-slate-600">Click to select CSV file or drag & drop</span>
                <span className="text-xs text-slate-400">UTF-8 encoded, comma-separated</span>
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
    setProgress(`Analyzing ${files.length} images with AI...`)

    const formData = new FormData()
    files.forEach(f => formData.append("files", f))

    try {
      const res = await apiClient.post("/products/bulk/images", formData, {
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
            <Camera className="h-4 w-4 text-violet-600" />
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
            className="border-2 border-dashed border-violet-300 rounded-xl p-8 text-center transition-colors cursor-pointer hover:border-violet-400 hover:bg-violet-50/50"
            onClick={() => fileRef.current?.click()}
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault()
              handleFiles(e.dataTransfer.files)
            }}
          >
            <div className="flex flex-col items-center gap-3">
              <ImageIcon className="h-8 w-8 text-violet-400" />
              <span className="text-sm text-slate-600">Drop food photos here or click to select</span>
              <span className="text-xs text-slate-400">JPEG, PNG, WebP — select multiple at once</span>
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
                <span className="text-sm font-medium text-slate-700">{files.length} image{files.length !== 1 ? "s" : ""} selected</span>
                <div className="flex gap-2">
                  <Button variant="outline" size="sm" onClick={() => { previews.forEach(URL.revokeObjectURL); setFiles([]); setPreviews([]); setResult(null) }}>
                    Clear all
                  </Button>
                  <Button size="sm" onClick={handleScan} disabled={uploading} className="gap-2 bg-violet-600 hover:bg-violet-700">
                    {uploading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Camera className="h-4 w-4" />}
                    {uploading ? "Scanning..." : `Scan ${files.length} image${files.length !== 1 ? "s" : ""}`}
                  </Button>
                </div>
              </div>
              <div className="grid grid-cols-4 sm:grid-cols-6 md:grid-cols-8 gap-2">
                {previews.map((url, i) => (
                  <div key={i} className="relative aspect-square rounded-lg overflow-hidden group">
                    <img src={url} alt="" className="w-full h-full object-cover" />
                    <button
                      onClick={() => removeFile(i)}
                      className="absolute top-1 right-1 bg-red-500 text-white rounded-full p-0.5 opacity-0 group-hover:opacity-100 transition-opacity"
                    >
                      <XCircle className="h-3.5 w-3.5" />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Progress */}
          {uploading && progress && (
            <div className="flex items-center gap-2 text-sm text-violet-600 bg-violet-50 rounded-lg px-3 py-2">
              <Loader2 className="h-4 w-4 animate-spin" />
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
    <Card>
      <CardHeader>
        <CardTitle className="text-base flex items-center gap-2">
          {result.successCount > 0 ? (
            <CheckCircle2 className="h-5 w-5 text-emerald-500" />
          ) : (
            <AlertTriangle className="h-5 w-5 text-amber-500" />
          )}
          Import Results
        </CardTitle>
        <CardDescription>
          {result.successCount} of {result.totalRows} {isPhotoImport ? "images" : "rows"} imported successfully
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Summary */}
        <div className="flex gap-4">
          <div className="flex-1 rounded-lg bg-emerald-50 border border-emerald-200 p-3 text-center">
            <div className="text-2xl font-bold text-emerald-700">{result.successCount}</div>
            <div className="text-xs text-emerald-600">Created</div>
          </div>
          <div className="flex-1 rounded-lg bg-red-50 border border-red-200 p-3 text-center">
            <div className="text-2xl font-bold text-red-700">{result.errorCount}</div>
            <div className="text-xs text-red-600">Errors</div>
          </div>
        </div>

        {/* Created products */}
        {result.created.length > 0 && (
          <div>
            <h4 className="text-sm font-medium text-slate-700 mb-2">Created Products</h4>
            <div className="space-y-1.5 max-h-60 overflow-y-auto">
              {result.created.map((p) => (
                <div key={p.id} className="flex items-center gap-3 text-sm bg-emerald-50/50 rounded-lg px-3 py-2">
                  <CheckCircle2 className="h-4 w-4 text-emerald-500 flex-shrink-0" />
                  {p.imageUrl ? (
                    <img src={p.imageUrl} alt="" className="h-8 w-8 rounded object-cover flex-shrink-0" />
                  ) : (
                    <div className="h-8 w-8 rounded bg-slate-100 flex items-center justify-center flex-shrink-0">
                      <Package className="h-4 w-4 text-slate-300" />
                    </div>
                  )}
                  <div className="flex-1 min-w-0">
                    <span className="font-medium text-slate-800 truncate block">{p.title}</span>
                    <span className="text-xs text-slate-400">{p.sku}</span>
                  </div>
                  <span className="text-xs font-medium text-slate-600">
                    {p.pricePennies > 0 ? `£${(p.pricePennies / 100).toFixed(2)}` : "Price needed"}
                  </span>
                </div>
              ))}
            </div>
            {isPhotoImport && (
              <p className="mt-2 text-xs text-amber-600 flex items-center gap-1">
                <AlertTriangle className="h-3 w-3" />
                AI-created products are drafts — set prices and review before publishing
              </p>
            )}
          </div>
        )}

        {/* Errors */}
        {result.errors.length > 0 && (
          <div>
            <h4 className="text-sm font-medium text-red-700 mb-2">Errors</h4>
            <div className="space-y-1 max-h-40 overflow-y-auto">
              {result.errors.map((err, i) => (
                <div key={i} className="flex items-start gap-2 text-sm bg-red-50/50 rounded-lg px-3 py-2">
                  <XCircle className="h-4 w-4 text-red-400 flex-shrink-0 mt-0.5" />
                  <div>
                    <span className="font-medium text-red-700">
                      {isPhotoImport ? `Image ${err.row}` : `Row ${err.row}`}
                      {err.field && err.field !== "row" ? ` (${err.field})` : ""}:
                    </span>{" "}
                    <span className="text-red-600">{err.message}</span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Actions */}
        <div className="flex gap-2 pt-2">
          <Link href="/dashboard/products">
            <Button variant="outline" className="gap-2">
              <ArrowLeft className="h-4 w-4" />
              Back to Products
            </Button>
          </Link>
        </div>
      </CardContent>
    </Card>
  )
}
