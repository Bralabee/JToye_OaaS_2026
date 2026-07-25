"use client"

import { Suspense, useEffect, useState, useCallback, useRef } from "react"
import Link from "next/link"
import { useSearchParams } from "next/navigation"
import { MapPin, Search, Store, ChevronRight, Loader2, X } from "lucide-react"
import { SafeImage } from "@/components/ui/safe-image"
import publicApiClient from "@/lib/public-api-client"
import {
  isRateLimitError,
  getRetryDelayMs,
  MAX_RETRY_ATTEMPTS,
} from "@/lib/public-fetch-retry"
import { PublicShop } from "@/types/storefront"
import { PageResponse } from "@/types/api"

function isOpenNow(hours: Record<string, string> | null): boolean {
  if (!hours || Object.keys(hours).length === 0) return true  // No hours = always open (matches backend)
  const days = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"]
  // Use UK timezone explicitly to match backend validation
  const now = new Date(new Date().toLocaleString("en-GB", { timeZone: "Europe/London" }))
  const dayKey = days[now.getDay()]
  const todayHours = hours[dayKey]
  if (!todayHours || todayHours.toLowerCase() === "closed") return false

  const match = todayHours.match(/(\d{2}):(\d{2})\s*-\s*(\d{2}):(\d{2})/)
  if (!match) return false

  const nowMinutes = now.getHours() * 60 + now.getMinutes()
  const openMinutes = parseInt(match[1]) * 60 + parseInt(match[2])
  const closeMinutes = parseInt(match[3]) * 60 + parseInt(match[4])
  return nowMinutes >= openMinutes && nowMinutes < closeMinutes
}

function formatPennies(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

function ShopCard({ shop }: { shop: PublicShop }) {
  const open = isOpenNow(shop.openingHours)
  const tags = shop.tags?.split(",").map((t) => t.trim()).filter(Boolean) || []

  return (
    <Link href={`/shop/${shop.slug}`} className="group block">
      <article className="bg-white rounded-2xl overflow-hidden shadow-sm border border-cream-100 transition-all duration-200 group-hover:shadow-md group-hover:border-amber-200 group-hover:-translate-y-0.5">
        {/* Banner */}
        <div className="relative h-36 sm:h-44 bg-gradient-to-br from-amber-300 via-amber-500 to-oxblood-600 overflow-hidden">
          {shop.bannerUrl && (
            <SafeImage
              src={shop.bannerUrl}
              alt={`${shop.name} banner`}
              className="absolute inset-0 w-full h-full object-cover"
              loading="lazy"
            />
          )}
          <div className="absolute inset-0 bg-gradient-to-t from-black/50 to-transparent" />

          {/* Open/Closed badge */}
          <div className="absolute top-3 right-3">
            <span
              className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-medium backdrop-blur-sm ${
                open
                  ? "bg-emerald-500/90 text-white"
                  : "bg-slate-800/70 text-slate-300"
              }`}
            >
              <span className={`h-1.5 w-1.5 rounded-full ${open ? "bg-white animate-pulse" : "bg-slate-400"}`} />
              {open ? "Open" : "Closed"}
            </span>
          </div>

          {/* Logo overlay */}
          <div className="absolute bottom-3 left-3 h-12 w-12 rounded-xl bg-white shadow-lg ring-2 ring-white overflow-hidden">
            <SafeImage
              src={shop.logoUrl}
              alt={shop.name}
              className="h-full w-full object-cover"
              fallbackIcon={<Store className="h-6 w-6 text-oxblood-600" />}
              loading="lazy"
            />
          </div>
        </div>

        {/* Content */}
        <div className="p-4">
          <div className="flex items-start justify-between gap-2">
            <h3 className="font-semibold text-slate-900 text-base leading-tight group-hover:text-oxblood transition-colors">
              {shop.name}
            </h3>
            <ChevronRight className="h-4 w-4 text-slate-400 group-hover:text-amber-600 transition-colors flex-shrink-0 mt-0.5" />
          </div>

          {shop.description && (
            <p className="mt-1 text-sm text-slate-500 line-clamp-2 leading-relaxed">
              {shop.description}
            </p>
          )}

          {/* Tags */}
          {tags.length > 0 && (
            <div className="mt-2.5 flex flex-wrap gap-1.5">
              {tags.slice(0, 3).map((tag) => (
                <span
                  key={tag}
                  className="inline-flex items-center rounded-md bg-cream px-2 py-0.5 text-xs font-medium text-oxblood-600"
                >
                  {tag}
                </span>
              ))}
              {tags.length > 3 && (
                <span className="text-xs text-slate-400">+{tags.length - 3}</span>
              )}
            </div>
          )}

          {/* Meta row */}
          <div className="mt-3 flex items-center gap-3 text-xs text-slate-500">
            {shop.address && (
              <span className="inline-flex items-center gap-1 truncate">
                <MapPin className="h-3 w-3 flex-shrink-0" />
                <span className="truncate">{shop.address}</span>
              </span>
            )}
            {shop.minimumOrderPennies > 0 && (
              <span className="whitespace-nowrap font-medium text-slate-600">
                Min {formatPennies(shop.minimumOrderPennies)}
              </span>
            )}
          </div>

          <div className="mt-1.5 flex flex-wrap gap-2 text-xs">
            {shop.deliveryFeePennies > 0 ? (
              <span className="text-slate-500">
                Delivery {formatPennies(shop.deliveryFeePennies)}
                {shop.freeDeliveryThresholdPennies && (
                  <span className="text-emerald-600 ml-1">
                    Free over {formatPennies(shop.freeDeliveryThresholdPennies)}
                  </span>
                )}
              </span>
            ) : (
              <span className="text-emerald-600 font-medium">Free delivery</span>
            )}
            {shop.deliveryInfo && (
              <span className="text-slate-400 truncate">{shop.deliveryInfo}</span>
            )}
          </div>
        </div>
      </article>
    </Link>
  )
}

// Suggested searches — the same vocabulary the landing hero advertises, so a
// shopper who arrives here directly gets the same "search by X" affordance
// instead of a bare box (the guide text used to suggest terms nothing acted on).
const SUGGESTIONS = [
  { emoji: "🍗", label: "Grill", q: "grill" },
  { emoji: "🍚", label: "Jollof", q: "jollof" },
  { emoji: "🥘", label: "Caribbean", q: "caribbean" },
  { emoji: "🍛", label: "South Asian", q: "south asian" },
  { emoji: "🥗", label: "Vegan", q: "vegan" },
  { emoji: "🍰", label: "Desserts", q: "dessert" },
]

function ShopDiscovery() {
  const searchParams = useSearchParams()
  const urlQuery = searchParams.get("q") ?? ""

  const [shops, setShops] = useState<PublicShop[]>([])
  const [loading, setLoading] = useState(true)
  // Seeded from ?q= so the landing search / category chips / a shared link all
  // arrive with the query already applied, not on a blank index.
  const [searchQuery, setSearchQuery] = useState(urlQuery)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [page, setPage] = useState(0)
  // Tracks the ?q= we have already reflected into state, so the URL->state and
  // state->URL syncs below can never ping-pong.
  const appliedUrlQuery = useRef(urlQuery)
  // F-RATE (#88): a public 429 must surface a transient "busy / retrying" state,
  // never the authoritative "No shops found" empty state.
  const [rateLimited, setRateLimited] = useState(false)
  const [retriesExhausted, setRetriesExhausted] = useState(false)
  const retryAttemptRef = useRef(0)
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // Latest fetch fn, so the retry timer always calls the current closure without
  // a self-referential useCallback dependency.
  const fetchRef = useRef<() => void>(() => {})

  const fetchShops = useCallback(async () => {
    setLoading(true)
    try {
      const params: Record<string, string | number> = { page, size: 12 }
      if (searchQuery.trim()) params.q = searchQuery.trim()

      const res = await publicApiClient.get<PageResponse<PublicShop>>(
        "/public/shops",
        { params }
      )
      setShops(res.data.content)
      setTotalPages(res.data.totalPages)
      setTotalElements(res.data.totalElements)
      // A real (possibly empty) 200 clears the busy state and resets the budget.
      setRateLimited(false)
      setRetriesExhausted(false)
      retryAttemptRef.current = 0
    } catch (err) {
      if (isRateLimitError(err)) {
        // Rate limited — show busy and auto-retry with bounded backoff.
        setRateLimited(true)
        const attempt = retryAttemptRef.current
        if (attempt < MAX_RETRY_ATTEMPTS) {
          const delay = getRetryDelayMs(err, attempt)
          retryAttemptRef.current = attempt + 1
          if (retryTimerRef.current) clearTimeout(retryTimerRef.current)
          retryTimerRef.current = setTimeout(() => fetchRef.current(), delay)
        } else {
          setRetriesExhausted(true)
        }
      } else {
        // Genuine failure / empty — preserve the existing empty behaviour.
        setShops([])
        setTotalElements(0)
        setRateLimited(false)
        setRetriesExhausted(false)
      }
    } finally {
      setLoading(false)
    }
  }, [page, searchQuery])

  useEffect(() => {
    fetchRef.current = fetchShops
  }, [fetchShops])

  useEffect(() => {
    fetchShops()
  }, [fetchShops])

  // Clear any pending retry timer on unmount to avoid leaks / act() warnings.
  useEffect(() => {
    return () => {
      if (retryTimerRef.current) clearTimeout(retryTimerRef.current)
    }
  }, [])

  const handleManualRetry = useCallback(() => {
    retryAttemptRef.current = 0
    setRetriesExhausted(false)
    fetchShops()
  }, [fetchShops])

  useEffect(() => {
    setPage(0)
  }, [searchQuery])

  // URL -> state: a category chip clicked while already on /shop (or a back/
  // forward step) changes ?q= without remounting; adopt it.
  useEffect(() => {
    if (urlQuery !== appliedUrlQuery.current) {
      appliedUrlQuery.current = urlQuery
      setSearchQuery(urlQuery)
    }
  }, [urlQuery])

  // state -> URL: typing rewrites ?q= (debounced) so the result set is
  // shareable and survives a reload. replaceState keeps it out of history so
  // Back still leaves the storefront rather than replaying every keystroke.
  useEffect(() => {
    const timer = setTimeout(() => {
      const term = searchQuery.trim()
      if (term === appliedUrlQuery.current) return
      const params = new URLSearchParams(window.location.search)
      if (term) params.set("q", term)
      else params.delete("q")
      const qs = params.toString()
      appliedUrlQuery.current = term
      window.history.replaceState(
        null,
        "",
        qs ? `${window.location.pathname}?${qs}` : window.location.pathname
      )
    }, 400)
    return () => clearTimeout(timer)
  }, [searchQuery])

  return (
    <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-6 sm:py-10">
      {/* Hero */}
      <div className="mb-6 sm:mb-8">
        <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-oxblood">
          Discover local kitchens
        </h1>
        <p className="mt-2 text-sm sm:text-base text-slate-600 max-w-xl">
          Browse independent food vendors, explore their menus and order directly.
        </p>
      </div>

      {/* Search */}
      <div className="mb-4 max-w-xl">
        <label htmlFor="shop-search" className="sr-only">
          Search kitchens, dishes or a postcode
        </label>
        <div className="relative">
          <Search
            aria-hidden
            className="pointer-events-none absolute left-5 top-1/2 -translate-y-1/2 h-4 w-4 text-oxblood-600"
          />
          <input
            id="shop-search"
            type="search"
            autoComplete="off"
            placeholder="Try “jollof”, “vegan” or your postcode…"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full rounded-full border border-cream-100 bg-white py-3 pl-12 pr-10 text-sm text-slate-900 shadow-sm placeholder:text-slate-500 hover:border-amber-300 focus:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-200 transition-colors"
          />
          {searchQuery && (
            <button
              type="button"
              onClick={() => setSearchQuery("")}
              aria-label="Clear search"
              className="absolute right-2 top-1/2 -translate-y-1/2 inline-flex h-8 w-8 items-center justify-center rounded-full text-slate-500 transition-colors hover:bg-cream hover:text-oxblood focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300"
            >
              <X className="h-4 w-4" />
            </button>
          )}
        </div>
      </div>

      {/* Suggested searches — same vocabulary as the landing hero */}
      <div className="mb-6 sm:mb-8 flex flex-wrap gap-2">
        {SUGGESTIONS.map((s) => {
          const active = searchQuery.trim().toLowerCase() === s.q
          return (
            <button
              key={s.q}
              type="button"
              onClick={() => setSearchQuery(active ? "" : s.q)}
              aria-pressed={active}
              className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-sm font-medium shadow-sm transition-colors ${
                active
                  ? "border-oxblood bg-oxblood text-white"
                  : "border-cream-100 bg-white text-slate-700 hover:border-amber-300 hover:text-oxblood"
              }`}
            >
              <span aria-hidden>{s.emoji}</span> {s.label}
            </button>
          )
        })}
      </div>

      {/* Result summary — confirms the query actually ran */}
      {!loading && !rateLimited && searchQuery.trim() && (
        <p aria-live="polite" className="mb-4 text-sm text-slate-600">
          {totalElements === 0
            ? "No kitchens match "
            : `${totalElements} ${totalElements === 1 ? "kitchen" : "kitchens"} for `}
          <span className="font-semibold text-oxblood">
            &ldquo;{searchQuery.trim()}&rdquo;
          </span>
        </p>
      )}

      {/* Results */}
      {rateLimited ? (
        // F-RATE (#88): busy/retrying state — NEVER the "No shops found" empty
        // state. Static copy only; the 429 body carries no useful detail.
        <div className="text-center py-16">
          <Loader2 className="mx-auto h-10 w-10 text-amber-500 animate-spin" />
          <h3 className="mt-4 text-base font-semibold text-oxblood">
            High demand right now
          </h3>
          {retriesExhausted ? (
            <>
              <p className="mt-1 text-sm text-slate-600">
                The marketplace is still busy. Please try again in a moment.
              </p>
              <button
                onClick={handleManualRetry}
                className="mt-4 inline-flex items-center gap-1.5 rounded-full bg-amber-500 px-5 py-2.5 text-sm font-bold text-amber-ink hover:-translate-y-0.5 active:scale-95 transition-all"
              >
                Try again
              </button>
            </>
          ) : (
            <p className="mt-1 text-sm text-slate-600">
              The marketplace is busy — retrying automatically…
            </p>
          )}
        </div>
      ) : loading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 sm:gap-6">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="bg-white rounded-2xl overflow-hidden animate-pulse">
              <div className="h-36 sm:h-44 bg-cream-100" />
              <div className="p-4 space-y-3">
                <div className="h-4 bg-cream-100 rounded w-2/3" />
                <div className="h-3 bg-cream rounded w-full" />
                <div className="flex gap-2">
                  <div className="h-5 bg-cream rounded w-16" />
                  <div className="h-5 bg-cream rounded w-20" />
                </div>
              </div>
            </div>
          ))}
        </div>
      ) : shops.length === 0 ? (
        <div className="text-center py-16">
          <Store className="mx-auto h-12 w-12 text-cream-100" />
          <h3 className="mt-4 text-base font-semibold text-oxblood">
            No kitchens found
          </h3>
          <p className="mt-1 text-sm text-slate-600">
            {searchQuery
              ? "Try a different dish, cuisine or postcode — or browse everything."
              : "No kitchens are currently available."}
          </p>
          {searchQuery && (
            // Never a dead end: a zero-result query keeps one tap back to the
            // full catalogue.
            <button
              type="button"
              onClick={() => setSearchQuery("")}
              className="mt-4 inline-flex items-center rounded-full bg-amber-500 px-5 py-2.5 text-sm font-bold text-amber-ink shadow-sm transition-transform hover:-translate-y-0.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300 focus-visible:ring-offset-2"
            >
              Browse all kitchens
            </button>
          )}
        </div>
      ) : (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 sm:gap-6">
            {shops.map((shop) => (
              <ShopCard key={shop.slug} shop={shop} />
            ))}
          </div>

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="mt-8 flex items-center justify-center gap-2">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="rounded-full border border-cream-100 bg-white px-4 py-1.5 text-sm font-medium text-oxblood-600 hover:border-amber-300 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              >
                Previous
              </button>
              <span className="text-sm text-slate-500 px-3">
                {page + 1} of {totalPages}
              </span>
              <button
                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1}
                className="rounded-full border border-cream-100 bg-white px-4 py-1.5 text-sm font-medium text-oxblood-600 hover:border-amber-300 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              >
                Next
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}

/**
 * useSearchParams needs a Suspense boundary; the fallback mirrors the loaded
 * layout (heading + pill search + card grid) so arriving from the landing hero
 * does not flash an empty page.
 */
export default function ShopDiscoveryPage() {
  return (
    <Suspense
      fallback={
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-6 sm:py-10">
          <div className="h-8 w-64 rounded bg-cream-100 animate-pulse" />
          <div className="mt-4 h-12 w-full max-w-xl rounded-full bg-cream-100 animate-pulse" />
          <div className="mt-8 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 sm:gap-6">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="bg-white rounded-2xl overflow-hidden animate-pulse">
                <div className="h-36 sm:h-44 bg-cream-100" />
                <div className="p-4 space-y-3">
                  <div className="h-4 bg-cream-100 rounded w-2/3" />
                  <div className="h-3 bg-cream rounded w-full" />
                </div>
              </div>
            ))}
          </div>
        </div>
      }
    >
      <ShopDiscovery />
    </Suspense>
  )
}
