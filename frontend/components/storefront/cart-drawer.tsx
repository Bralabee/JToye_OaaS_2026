"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { m, AnimatePresence } from "framer-motion"
import { Minus, Plus, Trash2, ShoppingBag, Store, X } from "lucide-react"
import { useCart } from "@/components/storefront/cart-provider"
import { SafeImage } from "@/components/ui/safe-image"
import {
  Sheet,
  SheetContent,
  SheetClose,
  SheetTitle,
} from "@/components/ui/sheet"
import { springSoft } from "@/lib/motion"

// Local mirror of the cart page's formatter so the drawer stays a drop-in
// companion (same money presentation, £X.XX) without coupling the two files.
function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

/**
 * Slide-over basket. A NEW affordance (not a replacement) that lives INSIDE the
 * CartProvider tree so it can read/mutate the full cart. It is opened by the
 * storefront nav basket badge — which renders OUTSIDE the provider — via the
 * `jtoye:cart-open` window CustomEvent. The intact cart PAGE remains reachable
 * from both the nav href (progressive enhancement) and the drawer's "View full
 * basket" link, so no capability is displaced.
 */
export function CartDrawer() {
  const { items, updateQuantity, removeItem, clearCart, itemCount, totalPennies, shopSlug } =
    useCart()
  const [open, setOpen] = useState(false)
  const pathname = usePathname()

  // Open when the (out-of-tree) nav badge dispatches the cross-document event.
  useEffect(() => {
    const onOpen = () => setOpen(true)
    window.addEventListener("jtoye:cart-open", onOpen)
    return () => window.removeEventListener("jtoye:cart-open", onOpen)
  }, [])

  // Dismiss on navigation (e.g. tapping Checkout / View full basket) so the
  // drawer never lingers over a freshly-entered route.
  useEffect(() => {
    setOpen(false)
  }, [pathname])

  // `removeItem` is exposed for parity with the cart page's context surface;
  // the stepper reaches removal via updateQuantity(..., 0), matching the page.
  void removeItem

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent
        side="right"
        hideCloseButton
        className="flex w-full flex-col gap-0 p-0 sm:max-w-md"
      >
        {/* Header */}
        <div className="flex h-16 flex-shrink-0 items-center justify-between border-b border-slate-200 px-4">
          <div className="min-w-0">
            <SheetTitle className="text-base font-bold text-slate-900">
              Your basket
            </SheetTitle>
            <p className="text-xs text-slate-500">
              {itemCount} item{itemCount !== 1 ? "s" : ""}
            </p>
          </div>
          <div className="flex items-center gap-1">
            {items.length > 0 && (
              <button
                onClick={clearCart}
                className="px-2 py-1 text-xs text-slate-400 hover:text-red-500 transition-colors"
              >
                Clear all
              </button>
            )}
            <SheetClose
              aria-label="Close basket"
              className="inline-flex h-9 w-9 items-center justify-center rounded-lg text-slate-700 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-orange-300"
            >
              <X className="h-5 w-5" />
            </SheetClose>
          </div>
        </div>

        {items.length === 0 ? (
          /* Empty state — mirrors the cart page's copy + iconography. */
          <div className="flex flex-1 flex-col items-center justify-center px-4 text-center">
            <ShoppingBag className="h-16 w-16 text-slate-200" />
            <h2 className="mt-4 text-lg font-semibold text-slate-900">
              Your basket is empty
            </h2>
            <p className="mt-1 text-sm text-slate-500">
              Add items from the menu to get started.
            </p>
            <SheetClose className="mt-6 inline-flex items-center gap-2 rounded-full bg-orange-500 px-5 py-2.5 text-sm font-semibold text-white hover:bg-orange-600 transition-colors">
              Back to menu
            </SheetClose>
          </div>
        ) : (
          <>
            {/* Scrollable body */}
            <div className="flex-1 overflow-y-auto px-4 py-4">
              <AnimatePresence initial={false}>
                {items.map((item) => (
                  <m.div
                    key={item.productId}
                    layout
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: "auto" }}
                    exit={{ opacity: 0, height: 0 }}
                    transition={springSoft}
                    className="overflow-hidden"
                  >
                    <div className="mb-3 flex items-center gap-3 rounded-xl border border-slate-100 bg-white p-3 shadow-sm">
                      {/* Branded fallback — never renders a broken <img>. */}
                      <div className="h-16 w-16 flex-shrink-0 overflow-hidden rounded-lg">
                        <SafeImage
                          src={item.imageUrl}
                          alt={item.title}
                          className="h-full w-full object-cover"
                          fallbackClassName="h-full w-full bg-slate-100"
                          fallbackIcon={<Store className="h-6 w-6 text-slate-300" />}
                        />
                      </div>

                      <div className="min-w-0 flex-1">
                        <h3 className="truncate text-sm font-semibold text-slate-900">
                          {item.title}
                        </h3>
                        {item.category && (
                          <p className="text-xs text-slate-400">{item.category}</p>
                        )}
                        <p className="mt-0.5 text-sm font-bold text-slate-900">
                          {formatPrice(item.pricePennies * item.quantity)}
                        </p>
                      </div>

                      <div className="flex items-center gap-0">
                        <m.button
                          whileTap={{ scale: 0.9 }}
                          onClick={() => updateQuantity(item.productId, item.quantity - 1)}
                          aria-label={
                            item.quantity === 1 ? "Remove item" : "Decrease quantity"
                          }
                          className="flex h-8 w-8 items-center justify-center rounded-full border border-slate-200 text-slate-600 transition-colors hover:bg-slate-50"
                        >
                          {item.quantity === 1 ? (
                            <Trash2 className="h-3.5 w-3.5 text-red-400" />
                          ) : (
                            <Minus className="h-3.5 w-3.5" />
                          )}
                        </m.button>
                        <span className="min-w-[2rem] text-center text-sm font-bold text-slate-900">
                          {item.quantity}
                        </span>
                        <m.button
                          whileTap={{ scale: 0.9 }}
                          onClick={() => updateQuantity(item.productId, item.quantity + 1)}
                          aria-label="Increase quantity"
                          className="flex h-8 w-8 items-center justify-center rounded-full border border-slate-200 text-slate-600 transition-colors hover:bg-slate-50"
                        >
                          <Plus className="h-3.5 w-3.5" />
                        </m.button>
                      </div>
                    </div>
                  </m.div>
                ))}
              </AnimatePresence>
            </div>

            {/* Sticky footer */}
            <div className="flex-shrink-0 space-y-3 border-t border-slate-200 bg-white p-4">
              <div className="flex items-center justify-between text-sm">
                <span className="text-slate-600">Subtotal</span>
                <span className="font-semibold text-slate-900">
                  {formatPrice(totalPennies)}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-base font-bold text-slate-900">Total</span>
                <span className="text-base font-bold text-slate-900">
                  {formatPrice(totalPennies)}
                </span>
              </div>

              <Link
                href={`/shop/${shopSlug}/checkout`}
                onClick={() => setOpen(false)}
                className="flex w-full items-center justify-center gap-2 rounded-2xl bg-orange-500 py-3.5 text-sm font-bold text-white shadow-lg transition-all hover:bg-orange-600 active:scale-[0.98]"
              >
                Checkout · {formatPrice(totalPennies)}
              </Link>
              {/* Guaranteed bridge to the still-intact full cart page. */}
              <Link
                href={`/shop/${shopSlug}/cart`}
                onClick={() => setOpen(false)}
                className="flex w-full items-center justify-center gap-1 rounded-2xl border border-slate-200 py-3 text-sm font-medium text-slate-600 transition-colors hover:bg-slate-50"
              >
                View full basket
              </Link>
            </div>
          </>
        )}
      </SheetContent>
    </Sheet>
  )
}
