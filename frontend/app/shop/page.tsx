"use client"

import { useEffect, useState, useCallback } from "react"
import Link from "next/link"
import { MapPin, Clock, Search, Store, ChevronRight } from "lucide-react"
import { SafeImage } from "@/components/ui/safe-image"
import publicApiClient from "@/lib/public-api-client"
import { PublicShop } from "@/types/storefront"
import { PageResponse } from "@/types/api"

function isOpenNow(hours: Record<string, string> | null): boolean {
  if (!hours) return false
  const days = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"]
  const now = new Date()
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
      <article className="bg-white rounded-2xl overflow-hidden shadow-sm border border-slate-100 transition-all duration-200 group-hover:shadow-md group-hover:border-slate-200 group-hover:-translate-y-0.5">
        {/* Banner */}
        <div className="relative h-36 sm:h-44 bg-gradient-to-br from-orange-400 via-orange-500 to-rose-500 overflow-hidden">
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
              fallbackIcon={<Store className="h-6 w-6 text-orange-500" />}
              loading="lazy"
            />
          </div>
        </div>

        {/* Content */}
        <div className="p-4">
          <div className="flex items-start justify-between gap-2">
            <h3 className="font-semibold text-slate-900 text-base leading-tight group-hover:text-orange-600 transition-colors">
              {shop.name}
            </h3>
            <ChevronRight className="h-4 w-4 text-slate-400 group-hover:text-orange-500 transition-colors flex-shrink-0 mt-0.5" />
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
                  className="inline-flex items-center rounded-md bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600"
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

export default function ShopDiscoveryPage() {
  const [shops, setShops] = useState<PublicShop[]>([])
  const [loading, setLoading] = useState(true)
  const [searchQuery, setSearchQuery] = useState("")
  const [totalPages, setTotalPages] = useState(0)
  const [page, setPage] = useState(0)

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
    } catch {
      setShops([])
    } finally {
      setLoading(false)
    }
  }, [page, searchQuery])

  useEffect(() => {
    fetchShops()
  }, [fetchShops])

  useEffect(() => {
    setPage(0)
  }, [searchQuery])

  return (
    <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-6 sm:py-10">
      {/* Hero */}
      <div className="mb-8 sm:mb-10">
        <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-slate-900">
          Discover local vendors
        </h1>
        <p className="mt-2 text-sm sm:text-base text-slate-500 max-w-xl">
          Browse independent food vendors, explore their menus and order directly.
        </p>
      </div>

      {/* Search */}
      <div className="relative mb-6 sm:mb-8 max-w-md">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" />
        <input
          type="text"
          placeholder="Search by name or cuisine..."
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          className="w-full rounded-xl border border-slate-200 bg-white py-2.5 pl-10 pr-4 text-sm text-slate-900 placeholder:text-slate-400 focus:border-orange-300 focus:outline-none focus:ring-2 focus:ring-orange-100 transition-colors"
        />
      </div>

      {/* Results */}
      {loading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 sm:gap-6">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="bg-white rounded-2xl overflow-hidden animate-pulse">
              <div className="h-36 sm:h-44 bg-slate-200" />
              <div className="p-4 space-y-3">
                <div className="h-4 bg-slate-200 rounded w-2/3" />
                <div className="h-3 bg-slate-100 rounded w-full" />
                <div className="flex gap-2">
                  <div className="h-5 bg-slate-100 rounded w-16" />
                  <div className="h-5 bg-slate-100 rounded w-20" />
                </div>
              </div>
            </div>
          ))}
        </div>
      ) : shops.length === 0 ? (
        <div className="text-center py-16">
          <Store className="mx-auto h-12 w-12 text-slate-300" />
          <h3 className="mt-4 text-base font-medium text-slate-900">No shops found</h3>
          <p className="mt-1 text-sm text-slate-500">
            {searchQuery
              ? "Try a different search term."
              : "No shops are currently available."}
          </p>
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
                className="rounded-lg border border-slate-200 px-3 py-1.5 text-sm font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              >
                Previous
              </button>
              <span className="text-sm text-slate-500 px-3">
                {page + 1} of {totalPages}
              </span>
              <button
                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1}
                className="rounded-lg border border-slate-200 px-3 py-1.5 text-sm font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
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
