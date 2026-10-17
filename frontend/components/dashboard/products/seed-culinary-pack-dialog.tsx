"use client"

import { useEffect, useState } from "react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { Badge } from "@/components/ui/badge"
import { useToast } from "@/hooks/use-toast"
import { fetchCulinaryPacks, seedCulinaryPack } from "@/lib/products-api"
import { AlertTriangle, CheckCircle2, Loader2, UtensilsCrossed } from "lucide-react"
import type { CulinaryPackDto, CulinaryPackSeedResult, Shop } from "@/types/api"

export interface SeedCulinaryPackDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  shops: Shop[]
  defaultShopId?: string | null
  onSeeded?: (result: CulinaryPackSeedResult) => void
}

const DEFAULT_STATUTORY_NOTICE =
  "Starter templates only: dishes are seeded as unpublished drafts (available=false). Under FIR 2014 Reg. 5 & Reg. 8, the Food Business Operator must verify every ingredient, recipe variation, and 14-allergen declaration against their physical kitchen stock before publishing."

/**
 * 1-Click Pan-African & Caribbean Culinary Pack Seeder Dialog (GTM-BLK-02).
 *
 * Loads the 10 regional cuisine packs (52 dishes) from GET /api/v1/products/culinary-packs
 * and seeds the vendor's chosen shop with unpublished draft products (available=false)
 * via POST /api/v1/products/culinary-packs/seed.
 */
export function SeedCulinaryPackDialog({
  open,
  onOpenChange,
  shops,
  defaultShopId,
  onSeeded,
}: SeedCulinaryPackDialogProps) {
  const { toast } = useToast()
  const [packs, setPacks] = useState<CulinaryPackDto[]>([])
  const [loadingPacks, setLoadingPacks] = useState<boolean>(false)
  const [selectedShopId, setSelectedShopId] = useState<string>(
    defaultShopId || (shops.length === 1 ? shops[0].id : "")
  )
  const [selectedPackIds, setSelectedPackIds] = useState<string[]>([])
  const [submitting, setSubmitting] = useState<boolean>(false)
  const [result, setResult] = useState<CulinaryPackSeedResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setResult(null)
    setError(null)
    setSelectedPackIds([])
    setSelectedShopId(defaultShopId || (shops.length === 1 ? shops[0].id : ""))

    let cancelled = false
    setLoadingPacks(true)
    fetchCulinaryPacks()
      .then((list) => {
        if (!cancelled) {
          setPacks(list)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setError("Could not load cuisine starter packs. Please try again.")
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoadingPacks(false)
        }
      })

    return () => {
      cancelled = true
    }
  }, [open, defaultShopId, shops])

  const togglePack = (id: string) => {
    setSelectedPackIds((prev) =>
      prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id]
    )
  }

  const selectAllPacks = () => {
    if (selectedPackIds.length === packs.length) {
      setSelectedPackIds([])
    } else {
      setSelectedPackIds(packs.map((p) => p.cuisineKey))
    }
  }

  const handleClose = (nextOpen: boolean) => {
    if (submitting) return
    onOpenChange(nextOpen)
  }

  const handleSeed = async () => {
    if (!selectedShopId || selectedPackIds.length === 0 || submitting) return
    setSubmitting(true)
    setError(null)
    try {
      const res = await seedCulinaryPack({
        shopId: selectedShopId,
        cuisineKeys: selectedPackIds,
        defaultPricePennies: 950,
      })
      setResult(res)
      toast({
        title: res.created > 0 ? "Cuisine pack seeded" : "Already in catalog",
        description: `${res.created} draft dish${res.created === 1 ? "" : "es"} seeded (${res.skipped} existing skipped).`,
      })
      onSeeded?.(res)
    } catch (err: unknown) {
      const detail = (err as { response?: { data?: { detail?: unknown } } } | null)?.response?.data?.detail
      const message =
        typeof detail === "string" && detail.trim()
          ? detail
          : "Could not seed cuisine starter pack. Please try again."
      setError(message)
      toast({
        variant: "destructive",
        title: "Seeding failed",
        description: message,
      })
    } finally {
      setSubmitting(false)
    }
  }

  const totalSelectedDishes = packs
    .filter((p) => selectedPackIds.includes(p.cuisineKey))
    .reduce((sum, p) => sum + p.dishCount, 0)

  const statutoryCopy = DEFAULT_STATUTORY_NOTICE

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <UtensilsCrossed className="h-5 w-5 text-amber-700" aria-hidden="true" />
            Seed Pan-African &amp; Caribbean Cuisine Pack
          </DialogTitle>
          <DialogDescription>
            Populate your catalog with pre-configured regional dishes, typical ingredients, and 14-allergen bitmasks.
          </DialogDescription>
        </DialogHeader>

        {/* Statutory FBO Verification Notice (FIR 2014 Reg. 5 & Reg. 8) */}
        <div
          data-testid="culinary-statutory-notice"
          role="note"
          className="rounded-lg border border-amber-300 bg-amber-50 p-3.5 text-xs text-amber-950"
        >
          <div className="flex items-start gap-2">
            <AlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-800" aria-hidden="true" />
            <div>
              <p className="font-semibold text-amber-950">
                Statutory FBO Recipe &amp; Allergen Verification Required
              </p>
              <p className="mt-1 leading-relaxed text-amber-900">{statutoryCopy}</p>
            </div>
          </div>
        </div>

        {!result && (
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="seed-pack-shop-select">Target shop</Label>
              <select
                id="seed-pack-shop-select"
                data-testid="seed-pack-shop-select"
                value={selectedShopId}
                onChange={(e) => setSelectedShopId(e.target.value)}
                disabled={submitting}
                className="flex h-10 w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-slate-500 focus:outline-none disabled:opacity-60"
              >
                <option value="">Choose a shop…</option>
                {shops.map((shop) => (
                  <option key={shop.id} value={shop.id}>
                    {shop.name}
                  </option>
                ))}
              </select>
            </div>

            {loadingPacks ? (
              <div className="flex items-center justify-center py-8 text-sm text-slate-600">
                <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
                Loading regional cuisine starter packs…
              </div>
            ) : (
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <Label className="text-sm font-semibold text-slate-900">
                    Select regional cuisine packs ({selectedPackIds.length} of {packs.length} selected)
                  </Label>
                  {packs.length > 0 && (
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={selectAllPacks}
                      disabled={submitting}
                      className="h-8 text-xs text-slate-700"
                    >
                      {selectedPackIds.length === packs.length ? "Clear selection" : "Select all"}
                    </Button>
                  )}
                </div>

                <div
                  data-testid="culinary-pack-list"
                  className="grid max-h-64 grid-cols-1 gap-2 overflow-y-auto rounded-lg border border-slate-200 bg-slate-50 p-2.5 sm:grid-cols-2"
                >
                  {packs.map((pack) => {
                    const checked = selectedPackIds.includes(pack.cuisineKey)
                    return (
                      <label
                        key={pack.cuisineKey}
                        className={`flex cursor-pointer items-start gap-2.5 rounded-md border p-2.5 transition-colors ${
                          checked
                            ? "border-amber-600 bg-amber-50/70"
                            : "border-slate-200 bg-white hover:bg-slate-50"
                        }`}
                      >
                        <input
                          type="checkbox"
                          data-testid={`culinary-pack-checkbox-${pack.cuisineKey}`}
                          checked={checked}
                          onChange={() => togglePack(pack.cuisineKey)}
                          disabled={submitting}
                          className="mt-1 h-4 w-4 rounded border-slate-300 text-amber-700 focus:ring-amber-600"
                        />
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-center justify-between gap-1">
                            <span className="text-sm font-semibold text-slate-900">
                              {pack.cuisineLabel}
                            </span>
                            <Badge variant="outline" className="text-xs text-slate-700">
                              {pack.dishCount} {pack.dishCount === 1 ? "dish" : "dishes"}
                            </Badge>
                          </div>
                          <p className="text-xs text-slate-600">{pack.region}</p>
                        </div>
                      </label>
                    )
                  })}
                </div>
              </div>
            )}
          </div>
        )}

        {error && (
          <div
            role="alert"
            data-testid="seed-culinary-error"
            className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800"
          >
            {error}
          </div>
        )}

        {result && (
          <div
            role="status"
            data-testid="seed-culinary-result"
            className="rounded-md border border-emerald-200 bg-emerald-50 p-3.5 text-sm text-emerald-950"
          >
            <div className="flex items-start gap-2">
              <CheckCircle2 className="mt-0.5 h-4 w-4 flex-shrink-0 text-emerald-700" aria-hidden="true" />
              <div className="space-y-1">
                <p className="font-semibold">
                  {result.created} draft {result.created === 1 ? "dish" : "dishes"} seeded ({result.skipped} existing skipped).
                </p>
                <p className="text-xs text-emerald-900">{statutoryCopy}</p>
              </div>
            </div>
          </div>
        )}

        <DialogFooter className="gap-2 sm:gap-2">
          {result ? (
            <Button type="button" onClick={() => handleClose(false)}>
              Done
            </Button>
          ) : (
            <>
              <Button
                type="button"
                variant="outline"
                onClick={() => handleClose(false)}
                disabled={submitting}
              >
                Cancel
              </Button>
              <Button
                type="button"
                data-testid="seed-culinary-submit-btn"
                onClick={handleSeed}
                disabled={submitting || !selectedShopId || selectedPackIds.length === 0}
              >
                {submitting
                  ? "Seeding drafts…"
                  : totalSelectedDishes > 0
                    ? `Seed ${totalSelectedDishes} Draft Dishes`
                    : "Seed Selected Packs"}
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
