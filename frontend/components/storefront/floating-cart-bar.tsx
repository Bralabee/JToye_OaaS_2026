"use client"

import { useRef } from "react"
import Link from "next/link"
import { m, AnimatePresence } from "framer-motion"
import { ShoppingBag } from "lucide-react"
import { springPop } from "@/lib/motion"
import { useCart } from "@/components/storefront/cart-provider"
import { useBottomChromeHeight } from "@/hooks/use-bottom-chrome-height"
import { minimumShortfallPennies } from "@/lib/minimum-order"

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

/**
 * The fixed "View basket" bar on the shop detail page. Its own module (#718
 * review F-5): the previous home inside the ~870-line shop-detail-client
 * forced its Jest suite to stub that file's unrelated module-level imports.
 *
 * ALWAYS branded oxblood (owner ruling 2026-08-31): the old below-minimum
 * bg-slate-700 state read as a dead control on the customer's very first
 * basket interaction. The below-minimum signal lives in the amber sub-label,
 * which states the shortfall AND the shop's absolute minimum (F-2: the delta
 * alone is a moving target the customer cannot learn the rule from).
 */
export function FloatingCartBar({ slug, minimumOrderPennies }: { slug: string; minimumOrderPennies: number }) {
  const { itemCount, totalPennies } = useCart()
  // R-07: publish this bar's height as `--jt-bottom-chrome` so the cookie
  // notice sits ABOVE it and its "Got it" control stays clickable. THIS
  // component is mounted unconditionally and never unmounts; the ref-bearing
  // element below is inside <AnimatePresence> and gated on `itemCount > 0`, so
  // the two lifecycles are decoupled and the hook deliberately carries no
  // dependency array (see hooks/use-bottom-chrome-height.ts).
  const barRef = useRef<HTMLDivElement>(null)
  useBottomChromeHeight(barRef)

  const shortfallPennies = minimumShortfallPennies(totalPennies, minimumOrderPennies)

  return (
    <AnimatePresence>
      {itemCount > 0 && (
        <m.div
          ref={barRef}
          initial={{ y: 96, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          exit={{ y: 96, opacity: 0 }}
          transition={springPop}
          className="fixed bottom-0 left-0 right-0 z-50 p-3 sm:p-4 pb-[max(0.75rem,env(safe-area-inset-bottom))] sm:pb-[max(1rem,env(safe-area-inset-bottom))]"
        >
          <div className="mx-auto max-w-4xl">
            <Link
              href={`/shop/${slug}/cart`}
              className="flex items-center justify-between rounded-2xl px-5 py-3.5 shadow-lg transition-all active:scale-[0.98] bg-oxblood hover:bg-oxblood-700 text-white"
            >
              <div className="flex items-center gap-3">
                <div className="relative">
                  <ShoppingBag className="h-5 w-5" />
                  <span className="absolute -top-1.5 -right-1.5 flex h-4 w-4 items-center justify-center rounded-full bg-white text-xs font-bold text-oxblood">
                    {itemCount}
                  </span>
                </div>
                <span className="text-sm font-medium">View basket</span>
              </div>
              <div className="text-right">
                <span className="text-sm font-bold">{formatPrice(totalPennies)}</span>
                {shortfallPennies !== null && (
                  <p className="text-xs text-amber-300">
                    Add {formatPrice(shortfallPennies)} to order · min {formatPrice(minimumOrderPennies)}
                  </p>
                )}
              </div>
            </Link>
          </div>
        </m.div>
      )}
    </AnimatePresence>
  )
}
