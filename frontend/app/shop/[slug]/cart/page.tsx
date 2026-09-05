"use client"

import { use, useEffect, useState } from "react"
import Link from "next/link"
import { ArrowLeft, Minus, Plus, Trash2, ShoppingBag, Store } from "lucide-react"
import { useCart } from "@/components/storefront/cart-provider"
import { SafeImage } from "@/components/ui/safe-image"
import { minimumShortfallPennies } from "@/lib/minimum-order"
import { previewDeliveryFeePennies } from "@/lib/delivery-fee"
import publicApiClient from "@/lib/public-api-client"
import type { PublicShop } from "@/types/storefront"

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

export default function CartPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = use(params)
  const { items, updateQuantity, removeItem, clearCart, itemCount, totalPennies } = useCart()

  // The shop is the ONLY source for the delivery fee, the free-delivery
  // threshold and the minimum order (COR-2/COR-3). Same fetch shape and same
  // graceful degradation as checkout: on failure `shop` stays null, the page
  // makes NO delivery or minimum claim at all, and the server — which recomputes
  // the total and enforces the minimum authoritatively in createGuestOrder —
  // remains the only thing that decides either.
  //
  // Declared ABOVE the empty-basket early return: hook order is not allowed to
  // depend on whether the basket has items.
  const [shop, setShop] = useState<PublicShop | null>(null)
  useEffect(() => {
    let cancelled = false
    publicApiClient
      .get<PublicShop>(`/public/shops/${slug}`)
      .then((res) => {
        if (!cancelled) setShop(res.data)
      })
      .catch(() => {
        /* No fee line, no minimum line. Silence, never a guessed £0.00. */
      })
    return () => {
      cancelled = true
    }
  }, [slug])

  if (items.length === 0) {
    return (
      <div className="mx-auto flex min-h-[60vh] max-w-2xl flex-col items-center justify-center px-4 text-center">
        <ShoppingBag className="h-16 w-16 text-oxblood/25" />
        <h2 className="mt-4 text-lg font-semibold text-slate-900">Your basket is empty</h2>
        <p className="mt-1 text-sm text-slate-600">Add items from the menu to get started.</p>
        <Link
          href={`/shop/${slug}`}
          className="mt-6 inline-flex items-center gap-2 rounded-full bg-amber-500 px-5 py-2.5 text-sm font-semibold text-amber-ink hover:bg-amber-400 transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to menu
        </Link>
      </div>
    )
  }

  // Money this screen is allowed to CLAIM. Every input is server-authoritative:
  // the fee, the waiver threshold and the minimum all come from the shop DTO,
  // never from a literal — a hardcoded "£3.50" is wrong for every other shop and
  // silently wrong for this one the day the vendor edits it.
  const previewedDeliveryPennies = previewDeliveryFeePennies(
    totalPennies,
    "DELIVERY",
    shop?.deliveryFeePennies,
    shop?.freeDeliveryThresholdPennies
  )
  // A null fee is NOT RECORDED, not "free" (the WR-04 rule stated on PublicShop):
  // a shop that has never set one renders no delivery line at all rather than a
  // confident "£0.00".
  const deliveryFeeKnown = shop != null && shop.deliveryFeePennies != null
  // The same arithmetic the floating bar and checkout call (#718 F-3), so the
  // three screens cannot quote three different shortfalls for one basket.
  const minimumOrderPennies = shop?.minimumOrderPennies ?? 0
  const shortfallPennies = minimumShortfallPennies(totalPennies, minimumOrderPennies)
  const belowMinimum = shortfallPennies !== null
  // One label for both CTA shapes — the enabled link and the disabled button
  // must never drift into saying different things.
  const ctaContent = (
    <>
      Proceed to checkout
      <span className="text-gold">{formatPrice(totalPennies)} subtotal</span>
    </>
  )

  return (
    <div className="mx-auto max-w-2xl px-4 sm:px-6 py-6">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <Link
            href={`/shop/${slug}`}
            className="inline-flex items-center gap-1 text-sm text-slate-600 hover:text-slate-700 transition-colors mb-1"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to menu
          </Link>
          <h1 className="text-xl font-bold text-slate-900">Your basket</h1>
          {/* data-testid, not a class or a text shape: e2e/cart-identity-boundary.verify.mjs
              reads this count, and it has been broken twice by incidental coupling — once when
              PR #522's contrast pass changed this paragraph's colour token, and once (nearly)
              by cart-drawer.tsx rendering the identical "N items" string. A testid moves only
              when someone means it to.
              The colour token is deliberately NOT named here: __tests__/contrast-literals.test.ts
              scans this file for Tailwind literals and cannot tell a live class from a comment,
              so naming the old one reds that gate. Measured, not guessed. */}
          <p data-testid="cart-item-count" className="text-sm text-slate-600">{itemCount} item{itemCount !== 1 ? "s" : ""}</p>
        </div>
        <button
          onClick={clearCart}
          className="text-xs text-slate-600 hover:text-red-500 transition-colors"
        >
          Clear all
        </button>
      </div>

      {/* Items */}
      <div className="space-y-3">
        {items.map((item) => (
          <div
            key={item.productId}
            className="flex items-center gap-3 rounded-xl bg-white border border-cream-100 p-3 shadow-sm"
          >
            {/* Image with branded fallback — no broken <img> ever renders */}
            <div className="h-16 w-16 flex-shrink-0 rounded-lg overflow-hidden">
              <SafeImage
                src={item.imageUrl}
                alt={item.title}
                className="h-full w-full object-cover"
                fallbackClassName="h-full w-full bg-cream"
                fallbackIcon={<Store className="h-6 w-6 text-slate-300" />}
              />
            </div>

            {/* Details */}
            <div className="flex-1 min-w-0">
              <h3 className="text-sm font-semibold text-slate-900 truncate">{item.title}</h3>
              {item.category && (
                <p className="text-xs text-slate-600">{item.category}</p>
              )}
              <p className="text-sm font-bold text-slate-900 mt-0.5">
                {formatPrice(item.pricePennies * item.quantity)}
              </p>
            </div>

            {/* Quantity controls */}
            <div className="flex items-center gap-0">
              <button
                onClick={() => updateQuantity(item.productId, item.quantity - 1)}
                aria-label={
                  item.quantity === 1
                    ? `Remove ${item.title} from basket`
                    : `Decrease quantity of ${item.title}`
                }
                className="flex h-8 w-8 items-center justify-center rounded-full border border-cream-100 text-oxblood-600 hover:bg-cream active:scale-95 transition-all"
              >
                {item.quantity === 1 ? <Trash2 className="h-3.5 w-3.5 text-red-400" /> : <Minus className="h-3.5 w-3.5" />}
              </button>
              <span className="min-w-[2rem] text-center text-sm font-bold text-slate-900">
                {item.quantity}
              </span>
              <button
                onClick={() => updateQuantity(item.productId, item.quantity + 1)}
                aria-label={`Increase quantity of ${item.title}`}
                className="flex h-8 w-8 items-center justify-center rounded-full border border-cream-100 text-oxblood-600 hover:bg-cream active:scale-95 transition-all"
              >
                <Plus className="h-3.5 w-3.5" />
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* Order summary.

          THERE IS DELIBERATELY NO "TOTAL" LINE (COR-2). This screen cannot know
          the fulfilment type — the customer chooses delivery or collection on
          the NEXT screen — so any total here is a claim about a decision not yet
          made. It used to render the item subtotal under the word "Total" and
          was contradicted one tap later: £3.00 shown, £6.50 payable. Subtotal
          plus the delivery FLOOR removes the false claim without inventing a
          new one, and it cannot be wrong for a customer who then picks
          collection. */}
      <div className="mt-6 rounded-xl bg-white border border-cream-100 p-4 shadow-sm">
        <div className="flex items-center justify-between text-sm">
          <span className="text-slate-600">Subtotal</span>
          <span className="font-semibold text-slate-900">{formatPrice(totalPennies)}</span>
        </div>
        {deliveryFeeKnown && (
          <>
            <div className="mt-2 flex items-center justify-between text-sm">
              <span className="text-slate-600">Delivery</span>
              {previewedDeliveryPennies === 0 ? (
                <span className="font-semibold text-emerald-700">Free</span>
              ) : (
                <span className="font-semibold text-slate-900">
                  from {formatPrice(previewedDeliveryPennies)}
                </span>
              )}
            </div>
            {previewedDeliveryPennies > 0 && (
              <p className="mt-1 text-xs text-slate-600">
                Added at checkout, where collection is free
                {shop?.freeDeliveryThresholdPennies != null
                  ? ` and delivery is free over ${formatPrice(shop.freeDeliveryThresholdPennies)}`
                  : ""}
                .
              </p>
            )}
          </>
        )}
      </div>

      {/* Actions */}
      <div className="mt-6 space-y-3">
        {/* The blocking rule, stated where the customer can still act on it
            (COR-3). Word-for-word the floating bar's label, from the same
            lib/minimum-order arithmetic, so the bar, this screen and checkout
            read as ONE rule rather than three. It is a PERMANENT hint sitting
            directly above the control it explains — the condition checkout's own
            comment sets for disabling a control at all, because a disabled
            button gives no feedback on touch. */}
        {belowMinimum && (
          <p id="basket-minimum-hint" className="text-center text-sm font-medium text-amber-800">
            Add {formatPrice(shortfallPennies!)} to order &middot; min {formatPrice(minimumOrderPennies)}
          </p>
        )}
        {belowMinimum ? (
          <button
            type="button"
            disabled
            aria-describedby="basket-minimum-hint"
            className="flex w-full items-center justify-center gap-2 rounded-2xl bg-oxblood py-3.5 text-sm font-bold text-white shadow-lg disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {ctaContent}
          </button>
        ) : (
          <Link
            href={`/shop/${slug}/checkout`}
            className="flex w-full items-center justify-center gap-2 rounded-2xl bg-oxblood py-3.5 text-sm font-bold text-white hover:bg-oxblood-700 active:scale-[0.98] transition-all shadow-lg"
          >
            {ctaContent}
          </Link>
        )}
        <Link
          href={`/shop/${slug}`}
          className="flex w-full items-center justify-center gap-1 rounded-2xl border border-cream-100 py-3 text-sm font-medium text-slate-600 hover:bg-cream transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
          Add more items
        </Link>
      </div>
    </div>
  )
}
