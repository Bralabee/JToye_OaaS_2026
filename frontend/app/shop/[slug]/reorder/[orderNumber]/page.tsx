"use client"

import { use, useCallback, useEffect, useRef, useState } from "react"
import Link from "next/link"
import { AlertTriangle, Loader2 } from "lucide-react"

import { useCart } from "@/components/storefront/cart-provider"
import { CustomerSignInPrompt } from "@/components/storefront/customer-signin-prompt"
import publicApiClient from "@/lib/public-api-client"
import { planReorder, type ReorderPlan, type ReorderResponse } from "@/lib/reorder"
import type { ProductsByCategory, PublicProduct, PublicShop } from "@/types/storefront"

/**
 * "Order again" (#845): /shop/{slug}/reorder/{orderNumber}, reached from a past order on My Orders.
 *
 * It does four things and stops: reads the order's lines through the signed-in proxy
 * (/api/customer-orders/reorder, which forwards the customer's HttpOnly token; no email is sent or known),
 * reads the shop's LIVE menu, puts what is still available into THIS shop's basket, and says what changed.
 * It never checks out: the next step is the customer's, as two links.
 *
 * THE BASKET IS WRITTEN ONLY THROUGH THE PROVIDER (`addItem`), the one serialize path that stamps the owner
 * (R-16), never by writing localStorage from here: a second way to write a user-scoped basket is a second
 * way to get the stamp wrong. The items are added to whatever the basket already holds (said so, below),
 * never silently replacing it.
 *
 * ONCE. The fetch-and-add runs behind a ref, not an effect cleanup flag: a cleanup flag would drop the
 * result of the first run under StrictMode's effect replay, and running twice would add every line twice.
 * Retry after a failure resets the ref on purpose.
 *
 * Money is not decided here. The basket takes the menu's price (lib/reorder.ts) and the server reprices at
 * checkout (D-16); "price changed" is information.
 */

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

type View =
  | { kind: "loading" }
  | { kind: "signin" }
  | { kind: "notfound" }
  | { kind: "failed" }
  | { kind: "done"; plan: ReorderPlan; added: number; hadItems: boolean }

export default function ReorderPage({ params }: { params: Promise<{ slug: string; orderNumber: string }> }) {
  const { slug, orderNumber } = use(params)
  const { addItem, itemCount } = useCart()
  const [view, setView] = useState<View>({ kind: "loading" })
  const started = useRef(false)
  const headingRef = useRef<HTMLHeadingElement>(null)
  // The basket's size as of the latest render, read once just BEFORE this page adds anything: "already
  // in your basket" is about what was there, not the result. A ref, synced in an effect, because the
  // provider hydrates from storage after mount, so the first render's count would say 0 for a full basket.
  const itemCountRef = useRef(itemCount)
  useEffect(() => {
    itemCountRef.current = itemCount
  })

  const run = useCallback(async () => {
    setView({ kind: "loading" })
    try {
      const res = await fetch(
        `/api/customer-orders/reorder?shop=${encodeURIComponent(slug)}&order=${encodeURIComponent(orderNumber)}`
      )
      if (res.status === 401) return setView({ kind: "signin" })
      if (res.status === 404) return setView({ kind: "notfound" })
      if (!res.ok) return setView({ kind: "failed" })
      const order = (await res.json()) as ReorderResponse

      const [menuRes, shopRes] = await Promise.all([
        publicApiClient.get<ProductsByCategory>(`/public/shops/${slug}/products`),
        // The limit is a convenience: a failed shop read is "no client limit", and the server's 422 gates.
        publicApiClient.get<PublicShop>(`/public/shops/${slug}`).catch(() => null),
      ])
      const menu: PublicProduct[] = Object.values(menuRes.data).flat()
      const plan = planReorder(order.lines, menu, shopRes?.data.maxLineQuantity)

      const hadItems = itemCountRef.current > 0
      let added = 0
      for (const { item, quantity } of plan.add) {
        for (let i = 0; i < quantity; i++) addItem(item)
        added += quantity
      }
      setView({ kind: "done", plan, added, hadItems })
    } catch {
      setView({ kind: "failed" })
    }
  }, [slug, orderNumber, addItem])

  useEffect(() => {
    if (started.current) return
    started.current = true
    void run()
  }, [run])

  // Focus the heading when the answer arrives, so a keyboard or screen-reader customer lands on it.
  useEffect(() => {
    if (view.kind !== "loading") headingRef.current?.focus()
  }, [view.kind])

  if (view.kind === "signin") {
    return (
      <CustomerSignInPrompt
        message="Your session has expired. Sign in again to order this again."
        nextPath={`/shop/${slug}/reorder/${orderNumber}`}
      />
    )
  }

  return (
    <div className="mx-auto max-w-lg px-4 py-10">
      <h1 ref={headingRef} tabIndex={-1} className="text-xl font-bold text-slate-900 focus:outline-none">
        Order again
      </h1>

      {view.kind === "loading" && (
        <p className="mt-6 flex items-center gap-2 text-sm text-slate-600" aria-live="polite">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
          Getting your order…
        </p>
      )}

      {(view.kind === "notfound" || view.kind === "failed") && (
        <div role="alert" className="mt-6 rounded-xl border border-red-300 bg-red-50 p-4">
          <p className="flex items-start gap-2 text-sm font-medium text-red-800">
            <AlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0" aria-hidden="true" />
            {view.kind === "notfound"
              ? "We couldn't find that order on your account."
              : "We couldn't load that order. Nothing was added to your basket."}
          </p>
          <div className="mt-3 flex flex-wrap gap-3">
            {view.kind === "failed" && (
              <button
                type="button"
                onClick={() => {
                  started.current = true
                  void run()
                }}
                className="inline-flex min-h-11 items-center rounded-lg bg-oxblood px-4 text-sm font-semibold text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
              >
                Try again
              </button>
            )}
            <Link href="/shop/orders" className="inline-flex min-h-11 items-center text-sm font-semibold text-oxblood underline underline-offset-2">
              Back to your orders
            </Link>
          </div>
        </div>
      )}

      {view.kind === "done" && <Result slug={slug} view={view} />}
    </div>
  )
}

function Result({ slug, view }: { slug: string; view: Extract<View, { kind: "done" }> }) {
  const { plan, added, hadItems } = view
  const nothing = added === 0
  return (
    <div data-testid="reorder-result" className="mt-6 space-y-4">
      <div role="status" className="rounded-xl border border-cream-100 bg-white p-4 shadow-sm">
        <p className="text-sm font-semibold text-slate-900">
          {nothing
            ? "Nothing from this order is available right now."
            : `Added ${added} ${added === 1 ? "item" : "items"} to your basket.`}
        </p>
        {!nothing && hadItems && (
          <p className="mt-1 text-sm text-slate-600">
            These were added to the items already in your basket, so check the quantities.
          </p>
        )}
      </div>

      {plan.unavailable.length > 0 && (
        <div data-testid="reorder-unavailable" className="rounded-xl border border-amber-600 bg-amber-50 p-4 text-sm text-amber-900">
          <p className="font-semibold">Not available now, so not added:</p>
          <ul className="mt-1 list-disc pl-5">
            {plan.unavailable.map((u, i) => (
              <li key={`${u.name}-${i}`}>{u.name}</li>
            ))}
          </ul>
        </div>
      )}

      {plan.priceChanged.length > 0 && (
        <div data-testid="reorder-price-changed" className="rounded-xl border border-amber-600 bg-amber-50 p-4 text-sm text-amber-900">
          <p className="font-semibold">The price has changed:</p>
          <ul className="mt-1 list-disc pl-5">
            {plan.priceChanged.map((p, i) => (
              <li key={`${p.name}-${i}`}>
                {p.name}: you paid {formatPrice(p.wasPennies)}, now {formatPrice(p.nowPennies)}
              </li>
            ))}
          </ul>
        </div>
      )}

      {plan.reduced.length > 0 && (
        <div data-testid="reorder-reduced" className="rounded-xl border border-amber-600 bg-amber-50 p-4 text-sm text-amber-900">
          <p className="font-semibold">Fewer than you ordered, because of the shop&apos;s limit:</p>
          <ul className="mt-1 list-disc pl-5">
            {plan.reduced.map((r, i) => (
              <li key={`${r.name}-${i}`}>
                {r.name}: ordered {r.asked}, added {r.got}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="flex flex-col gap-3 sm:flex-row">
        {!nothing && (
          <>
            <Link
              href={`/shop/${slug}/cart`}
              className="inline-flex min-h-11 flex-1 items-center justify-center rounded-xl border border-cream-100 bg-white px-4 text-sm font-semibold text-oxblood focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
            >
              Go to basket
            </Link>
            <Link
              href={`/shop/${slug}/checkout`}
              className="inline-flex min-h-11 flex-1 items-center justify-center rounded-xl bg-oxblood px-4 text-sm font-bold text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
            >
              Go to checkout
            </Link>
          </>
        )}
        {nothing && (
          <Link
            href={`/shop/${slug}`}
            className="inline-flex min-h-11 flex-1 items-center justify-center rounded-xl bg-oxblood px-4 text-sm font-bold text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 focus-visible:ring-offset-2"
          >
            See the menu
          </Link>
        )}
      </div>
    </div>
  )
}
