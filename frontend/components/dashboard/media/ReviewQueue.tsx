"use client"

import { useCallback, useEffect, useState } from "react"
import { m } from "framer-motion"
import Link from "next/link"
import {
  AlertTriangle,
  CheckCircle2,
  Images,
  Inbox,
  Loader2,
  RefreshCw,
  XCircle,
} from "lucide-react"
import type { LucideIcon } from "lucide-react"
import { useToast } from "@/hooks/use-toast"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { AssetImage } from "@/components/ui/asset-image"
import { fetchReviewQueue, keepAsset } from "@/lib/media-api"
import type { MediaAsset } from "@/types/api"

// --- Static presentation metadata (orange/emerald/slate/amber palette) --------

const KIND_META: Record<"FAILED" | "FLAGGED", { label: string; badge: string; icon: LucideIcon }> = {
  // A rejected upload — red signals "this did not go live; re-upload".
  FAILED: { label: "Rejected", badge: "bg-red-100 text-red-700 hover:bg-red-100", icon: XCircle },
  // A flagged-but-live image — amber signals "your call: Keep or Replace".
  FLAGGED: { label: "Needs review", badge: "bg-amber-100 text-amber-700 hover:bg-amber-100", icon: AlertTriangle },
}

function httpStatus(err: unknown): number | undefined {
  if (err && typeof err === "object" && "response" in err) {
    return (err as { response?: { status?: number } }).response?.status
  }
  return undefined
}

function errorDetail(err: unknown, fallback: string): string {
  const e = err as { response?: { data?: { detail?: string; message?: string } }; message?: string }
  return e?.response?.data?.detail ?? e?.response?.data?.message ?? e?.message ?? fallback
}

export function ReviewQueue() {
  const { toast } = useToast()

  const [assets, setAssets] = useState<MediaAsset[]>([])
  const [loading, setLoading] = useState(true)
  const [keepingId, setKeepingId] = useState<string | null>(null)
  // Replace/Re-upload target — the queue asset carries no product linkage
  // (24-05 MediaAssetDto), so a replacement is performed on the product page.
  const [replaceTarget, setReplaceTarget] = useState<MediaAsset | null>(null)

  const load = useCallback(async () => {
    try {
      const data = await fetchReviewQueue()
      setAssets(data)
    } catch (err: unknown) {
      // A 401 is handled globally by the api-client (session refresh / signin).
      if (httpStatus(err) !== 401) {
        toast({
          variant: "destructive",
          title: "Couldn't load the review queue",
          description: "Check your connection and try again.",
        })
      }
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void load()
  }, [load])

  const handleKeep = useCallback(
    async (asset: MediaAsset) => {
      try {
        setKeepingId(asset.assetId)
        await keepAsset(asset.assetId)
        // Keep clears the flag → the asset drops out of the queue.
        setAssets((prev) => prev.filter((a) => a.assetId !== asset.assetId))
        toast({ title: "Image kept", description: "It stays live and clears the review flag." })
      } catch (err: unknown) {
        toast({
          variant: "destructive",
          title: "Couldn't keep the image",
          description: errorDetail(err, "Please try again."),
        })
      } finally {
        setKeepingId(null)
      }
    },
    [toast]
  )

  // --- Loading ----------------------------------------------------------------

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center py-16">
        <Loader2 className="h-10 w-10 animate-spin text-orange-500" aria-hidden="true" />
        <span className="sr-only">Loading the review queue…</span>
      </div>
    )
  }

  const failed = assets.filter((a) => a.status === "FAILED")
  const flagged = assets.filter((a) => a.status === "ACTIVE" && a.flagged)
  const nothingWaiting = failed.length === 0 && flagged.length === 0

  return (
    <div className="space-y-8">
      <Header />

      {nothingWaiting ? (
        <Card>
          <CardContent className="flex flex-col items-center justify-center py-12 text-center">
            <Inbox className="mb-4 h-12 w-12 text-slate-300" aria-hidden="true" />
            <h3 className="mb-2 text-lg font-semibold text-slate-900">Nothing needs review</h3>
            <p className="text-sm text-slate-500">
              Rejected uploads and images flagged for a content check appear here. When an upload
              fails, you&apos;ll see the reason and can re-upload from the product.
            </p>
          </CardContent>
        </Card>
      ) : (
        <>
          {/* Rejected (FAILED) — reason + Re-upload -------------------------- */}
          {failed.length > 0 && (
            <section className="space-y-4">
              <div>
                <h2 className="text-lg font-semibold text-slate-900">Rejected uploads</h2>
                <p className="text-sm text-slate-600">
                  These uploads didn&apos;t pass validation, so they never went live. Re-upload a new
                  image from the product — your existing image is untouched.
                </p>
              </div>
              {failed.map((asset) => (
                <FailedRow
                  key={asset.assetId}
                  asset={asset}
                  onReupload={() => setReplaceTarget(asset)}
                />
              ))}
            </section>
          )}

          {/* Flagged-ACTIVE — Keep / Replace -------------------------------- */}
          {flagged.length > 0 && (
            <section className="space-y-4">
              <div>
                <h2 className="text-lg font-semibold text-slate-900">Flagged for review</h2>
                <p className="text-sm text-slate-600">
                  These images are live but were flagged by an automated content check. Keep them as
                  they are, or replace them with a new upload.
                </p>
              </div>
              {flagged.map((asset) => (
                <FlaggedRow
                  key={asset.assetId}
                  asset={asset}
                  keeping={keepingId === asset.assetId}
                  disabled={keepingId !== null}
                  onKeep={() => handleKeep(asset)}
                  onReplace={() => setReplaceTarget(asset)}
                />
              ))}
            </section>
          )}
        </>
      )}

      {/* Replace / Re-upload dialog — the queue asset carries no product id, so
          the replacement happens on the product page (POST products/{id}/image
          via the uploader; a FAILED replacement never clobbers the live image). */}
      <Dialog open={replaceTarget !== null} onOpenChange={(open) => !open && setReplaceTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Replace this image</DialogTitle>
            <DialogDescription>
              Open the product this image belongs to and upload a new one. Your current image stays
              live until the new upload passes validation — a failed replacement never removes it.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter className="gap-2 sm:gap-0">
            <Button variant="outline" onClick={() => setReplaceTarget(null)}>
              Cancel
            </Button>
            <Button asChild className="bg-orange-600 text-white hover:bg-orange-700">
              <Link href="/dashboard/products" onClick={() => setReplaceTarget(null)}>
                Go to products
              </Link>
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

// --- Header -------------------------------------------------------------------

function Header() {
  return (
    <m.div initial={{ opacity: 0, y: -20 }} animate={{ opacity: 1, y: 0 }}>
      <div className="flex items-center gap-3">
        <Images className="h-8 w-8 text-orange-500" aria-hidden="true" />
        <h1 className="text-3xl font-semibold text-slate-900 sm:text-4xl">Image review</h1>
      </div>
      <p className="mt-2 text-sm text-slate-600">
        Rejected uploads and images flagged for a content check. Re-upload a rejected image, or Keep
        / Replace a flagged one.
      </p>
    </m.div>
  )
}

// --- Rejected (FAILED) row ----------------------------------------------------

function FailedRow({ asset, onReupload }: { asset: MediaAsset; onReupload: () => void }) {
  const meta = KIND_META.FAILED
  const Icon = meta.icon
  return (
    <m.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}>
      <Card>
        <CardHeader className="pb-3">
          <div className="flex flex-wrap items-center gap-3">
            <CardTitle className="text-base">Upload rejected</CardTitle>
            <Badge className={`${meta.badge} pointer-events-none`}>
              <Icon className="mr-1 h-3 w-3" aria-hidden="true" />
              {meta.label}
            </Badge>
          </div>
          <CardDescription>{asset.failureReason || "This image could not be processed."}</CardDescription>
        </CardHeader>
        <CardContent>
          <Button
            variant="outline"
            className="border-red-200 text-red-700 hover:bg-red-50 hover:text-red-800"
            onClick={onReupload}
          >
            <RefreshCw className="mr-2 h-4 w-4" aria-hidden="true" />
            Re-upload
          </Button>
        </CardContent>
      </Card>
    </m.div>
  )
}

// --- Flagged-ACTIVE row -------------------------------------------------------

function FlaggedRow({
  asset,
  keeping,
  disabled,
  onKeep,
  onReplace,
}: {
  asset: MediaAsset
  keeping: boolean
  disabled: boolean
  onKeep: () => void
  onReplace: () => void
}) {
  const meta = KIND_META.FLAGGED
  const Icon = meta.icon
  return (
    <m.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}>
      <Card>
        <CardContent className="flex flex-col gap-4 p-4 sm:flex-row sm:items-center">
          {/* Thumbnail of the live-but-flagged derivative */}
          <div className="h-24 w-24 shrink-0 overflow-hidden rounded-lg bg-slate-100">
            <AssetImage
              status="ACTIVE"
              url={asset.url}
              thumbnailUrl={asset.thumbnailUrl}
              alt="Flagged product image"
              width={asset.width ?? undefined}
              height={asset.height ?? undefined}
              useThumbnail
              className="h-full w-full"
            />
          </div>

          <div className="flex-1">
            <Badge className={`${meta.badge} pointer-events-none`}>
              <Icon className="mr-1 h-3 w-3" aria-hidden="true" />
              {meta.label}
            </Badge>
            <p className="mt-2 text-sm text-slate-600">
              An automated content check flagged this image. It&apos;s still live — keep it, or
              replace it with a new upload.
            </p>
          </div>

          <div className="flex shrink-0 gap-2">
            <Button
              className="bg-emerald-600 text-white hover:bg-emerald-700"
              onClick={onKeep}
              disabled={disabled}
            >
              {keeping ? (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
              ) : (
                <CheckCircle2 className="mr-2 h-4 w-4" aria-hidden="true" />
              )}
              {keeping ? "Keeping…" : "Keep"}
            </Button>
            <Button variant="outline" onClick={onReplace} disabled={disabled}>
              <RefreshCw className="mr-2 h-4 w-4" aria-hidden="true" />
              Replace
            </Button>
          </div>
        </CardContent>
      </Card>
    </m.div>
  )
}

export default ReviewQueue
