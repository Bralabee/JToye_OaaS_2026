"use client"

import { useState } from "react"
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { useToast } from "@/hooks/use-toast"
import { copyProducts } from "@/lib/products-api"
import { withFullStop } from "@/lib/sentence-end"
import { cn } from "@/lib/utils"
import type { CopyProductsResult, Shop } from "@/types/api"

/**
 * "1 product" / "3 products". The UI-SPEC writes the copy as "{n} products"; the singular is the
 * same sentence read correctly for a row copy of one product.
 */
export function productCountCopy(n: number): string {
  return n === 1 ? "1 product" : `${n} products`
}

/**
 * UI-SPEC § Copywriting B4-B6 "Copy result": the server's created, skipped and failed counts,
 * each sentence only when its count is non-zero. The counts are the server's (37-13
 * CopyProductsResult) and are never recomputed here.
 */
export function copyResultSentence(result: CopyProductsResult, shopName: string): string {
  const parts: string[] = []
  if (result.created > 0) parts.push(`${result.created} copied to ${withFullStop(shopName)}`)
  if (result.skipped > 0) {
    parts.push(
      `${result.skipped} skipped because ${shopName} already has ${result.skipped === 1 ? "it" : "them"}.`
    )
  }
  if (result.failed > 0) parts.push(`${result.failed} couldn't be copied.`)
  return parts.length > 0 ? parts.join(" ") : `Nothing was copied to ${withFullStop(shopName)}`
}

export interface CopyToShopDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The shop the products are copied from. Never offered as a target. */
  sourceShop: { id: string; name: string }
  /** The caller's shops (read-scoped by the server); the source is excluded here. */
  shops: Shop[]
  /** The products to copy; omitted copies the source shop's whole menu. */
  productIds?: string[]
  /** How many products the dialog says it will copy. */
  count: number
  /** After the server answered, with its counts. */
  onCopied?: (result: CopyProductsResult) => void
}

/**
 * UXT-026 (#799, 37-14, UI-SPEC § B6 "Copy to shop"). Copies products to another shop through
 * POST /products/copy (37-13): allergens, ingredients, price, VAT rate and photos travel verbatim,
 * and a product the target already has is skipped, so the operation is safe to repeat.
 *
 * Both shops need SHOP_MANAGER; the server decides that, and a refusal is said inline in its own
 * words. The dialog cannot be closed while the copy is in flight, so the result is never lost.
 */
export function CopyToShopDialog({
  open,
  onOpenChange,
  sourceShop,
  shops,
  productIds,
  count,
  onCopied,
}: CopyToShopDialogProps) {
  const { toast } = useToast()
  const [targetShopId, setTargetShopId] = useState("")
  const [pending, setPending] = useState(false)
  const [result, setResult] = useState<CopyProductsResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  const targets = shops.filter((shop) => shop.id !== sourceShop.id)
  const targetName = targets.find((shop) => shop.id === targetShopId)?.name ?? "the other shop"
  const countText = productCountCopy(count)

  const close = (next: boolean) => {
    if (pending) return
    onOpenChange(next)
  }

  const submit = async () => {
    if (!targetShopId || pending) return
    setPending(true)
    setError(null)
    try {
      const answer = await copyProducts({
        sourceShopId: sourceShop.id,
        targetShopId,
        ...(productIds ? { productIds } : {}),
      })
      const sentence = copyResultSentence(answer, targetName)
      setResult(answer)
      toast({ title: answer.created > 0 ? "Products copied" : "Nothing new copied", description: sentence })
      onCopied?.(answer)
    } catch (err: unknown) {
      const detail = (err as { response?: { data?: { detail?: unknown } } } | null)?.response?.data?.detail
      const message =
        typeof detail === "string" && detail.trim() ? detail : "Couldn't copy the products. Try again."
      setError(message)
      toast({ variant: "destructive", title: "Copy failed", description: message })
    } finally {
      setPending(false)
    }
  }

  const failedReasons = result
    ? Array.from(
        new Set(
          result.items
            .filter((item) => item.outcome === "FAILED" && item.reason)
            .map((item) => item.reason as string)
        )
      )
    : []

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="break-words">Copy {countText} to another shop</DialogTitle>
          <DialogDescription className="break-words">
            {countText} will be copied with {count === 1 ? "its" : "their"} allergens, ingredients and
            photos.
          </DialogDescription>
        </DialogHeader>

        <p className="text-sm text-slate-600 break-words">
          From <strong className="font-semibold text-slate-900">{sourceShop.name}</strong>
        </p>

        {!result && (
          <div className="space-y-2">
            <Label id="copy-target-label" htmlFor="copy-target-shop">
              Copy to
            </Label>
            <Select value={targetShopId} onValueChange={setTargetShopId} disabled={pending}>
              <SelectTrigger
                id="copy-target-shop"
                aria-labelledby="copy-target-label"
                aria-required="true"
                className="h-11"
              >
                <SelectValue placeholder="Choose a shop" />
              </SelectTrigger>
              <SelectContent>
                {targets.map((shop) => (
                  <SelectItem key={shop.id} value={shop.id}>
                    {shop.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )}

        {error && (
          <div
            role="alert"
            className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800 break-words"
          >
            {error}
          </div>
        )}

        {result && (
          <div
            role="status"
            className={cn(
              "rounded-md border p-3 text-sm break-words",
              result.failed > 0
                ? "border-amber-300 bg-amber-50 text-amber-950"
                : result.created > 0
                  ? "border-emerald-200 bg-emerald-50 text-emerald-900"
                  : "border-slate-200 bg-slate-50 text-slate-800"
            )}
          >
            <p>{copyResultSentence(result, targetName)}</p>
            {failedReasons.length > 0 && (
              <ul className="mt-2 list-disc space-y-1 pl-5">
                {failedReasons.map((reason) => (
                  <li key={reason}>{reason}</li>
                ))}
              </ul>
            )}
          </div>
        )}

        <DialogFooter className="gap-2 sm:gap-2">
          {result ? (
            <Button type="button" className="h-11" onClick={() => close(false)}>
              Done
            </Button>
          ) : (
            <>
              <Button
                type="button"
                variant="outline"
                className="h-11"
                onClick={() => close(false)}
                disabled={pending}
              >
                Cancel
              </Button>
              <Button
                type="button"
                className="h-11"
                onClick={submit}
                disabled={pending || !targetShopId}
              >
                {pending ? "Copying…" : `Copy ${countText}`}
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
