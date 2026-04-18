"use client"

import { useEffect, useState, useCallback, useMemo } from "react"
import Link from "next/link"
import { motion } from "framer-motion"
import { MapPin, Clock, Search, Store } from "lucide-react"
import { SafeImage } from "@/components/ui/safe-image"
import { Card, CardContent } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { BrandPlaceholder } from "@/components/storefront/brand-placeholder"
import { BRAND } from "@/lib/brand"
import {
  fadeUp,
  listStagger,
  listItem,
  useReducedMotionSafe,
} from "@/lib/motion"
import publicApiClient from "@/lib/public-api-client"
import { PublicShop } from "@/types/storefront"
import { PageResponse } from "@/types/api"

type FilterKey = "all" | "near-me" | "open-now" | "new"

const FILTERS: Array<{ key: FilterKey; label: string }> = [
  { key: "all", label: "All" },
  { key: "near-me", label: "Near me" },
  { key: "open-now", label: "Open now" },
  { key: "new", label: "New" },
]

function isOpenNow(hours: Record<string, string> | null): boolean {
  if (!hours || Object.keys(hours).length === 0) return true // No hours = always open (matches backend)
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
    <Link
      href={`/shop/${shop.slug}`}
      className="group block focus:outline-none focus-visible:ring-2 focus-visible:ring-border-tone-focus focus-visible:ring-offset-2 focus-visible:ring-offset-surface-canvas rounded-xl"
    >
      <Card
        variant="lifted"
        className="overflow-hidden h-full rounded-xl border-border-tone-subtle"
      >
        {/* Banner — 4:3, brand placeholder when empty */}
        <div className="relative aspect-[4/3] overflow-hidden bg-surface-muted">
          {shop.bannerUrl ? (
            <SafeImage
              src={shop.bannerUrl}
              alt={`${shop.name} banner`}
              className="absolute inset-0 h-full w-full object-cover transition-transform duration-moderate ease-standard group-hover:scale-[1.02]"
              loading="lazy"
            />
          ) : (
            <BrandPlaceholder
              aspect="aspect-[4/3]"
              className="absolute inset-0 transition-transform duration-moderate ease-standard group-hover:scale-[1.02]"
            />
          )}

          {/* Open/closed status chip with a legibility wrapper */}
          <div className="absolute right-3 top-3 inline-flex items-center rounded-pill bg-surface-card/80 backdrop-blur-sm px-0.5">
            <Badge
              variant={open ? "success" : "subtle"}
              size="sm"
              className="rounded-pill"
            >
              <span
                aria-hidden="true"
                className={
                  "h-1.5 w-1.5 rounded-full " +
                  (open ? "bg-success" : "bg-ink-tertiary")
                }
              />
              {open ? "Open" : "Closed"}
            </Badge>
          </div>
        </div>

        <CardContent className="p-5 pt-5">
          <h3 className="font-display text-heading-md font-semibold tracking-tight text-ink-primary line-clamp-1">
            {shop.name}
          </h3>

          {tags.length > 0 && (
            <div className="mt-2 flex flex-wrap gap-1.5">
              {tags.slice(0, 3).map((tag) => (
                <Badge key={tag} variant="subtle" size="sm">
                  {tag}
                </Badge>
              ))}
            </div>
          )}

          {shop.description && (
            <p className="mt-3 text-body-sm text-ink-secondary line-clamp-2">
              {shop.description}
            </p>
          )}

          {/* Meta row — address + delivery signals */}
          <div className="mt-4 flex flex-col gap-1.5 text-caption text-ink-tertiary">
            {shop.address && (
              <span className="inline-flex items-center gap-1.5">
                <MapPin className="h-3.5 w-3.5 flex-shrink-0" strokeWidth={1.5} />
                <span className="truncate">{shop.address}</span>
              </span>
            )}
            <span className="inline-flex items-center gap-1.5">
              <Clock className="h-3.5 w-3.5 flex-shrink-0" strokeWidth={1.5} />
              {shop.deliveryFeePennies > 0 ? (
                <span>
                  Delivery {formatPennies(shop.deliveryFeePennies)}
                  {shop.freeDeliveryThresholdPennies ? (
                    <span className="text-success ml-1">
                      · Free over {formatPennies(shop.freeDeliveryThresholdPennies)}
                    </span>
                  ) : null}
                </span>
              ) : (
                <span className="text-success">Free delivery</span>
              )}
              {shop.minimumOrderPennies > 0 && (
                <span className="ml-1">
                  · Min {formatPennies(shop.minimumOrderPennies)}
                </span>
              )}
            </span>
          </div>
        </CardContent>
      </Card>
    </Link>
  )
}

export default function ShopDiscoveryPage() {
  const [shops, setShops] = useState<PublicShop[]>([])
  const [loading, setLoading] = useState(true)
  const [searchQuery, setSearchQuery] = useState("")
  const [activeFilter, setActiveFilter] = useState<FilterKey>("all")
  const [totalPages, setTotalPages] = useState(0)
  const [page, setPage] = useState(0)

  const heroVariants = useReducedMotionSafe(fadeUp)
  const gridVariants = useReducedMotionSafe(listStagger)
  const itemVariants = useReducedMotionSafe(listItem)

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

  // Client-side filter layer — server returns the full page; filters refine it
  // without extra round-trips for now. Real filtering lands in a follow-up wave.
  const visibleShops = useMemo(() => {
    if (activeFilter === "open-now") {
      return shops.filter((s) => isOpenNow(s.openingHours))
    }
    return shops
  }, [shops, activeFilter])

  // Tagline split so the final phrase carries the brand accent.
  const taglineParts = BRAND.tagline.split(". ")
  const headlineLead =
    taglineParts.slice(0, -1).map((p) => `${p}.`).join(" ") + " "
  const headlineFinal = taglineParts[taglineParts.length - 1]

  return (
    <div className="bg-surface-canvas">
      {/* Editorial hero */}
      <motion.section
        variants={heroVariants}
        initial="hidden"
        animate="visible"
        className="mx-auto max-w-content px-4 sm:px-6 lg:px-8 pt-12 pb-10 sm:pt-24 sm:pb-16"
      >
        <p className="text-overline uppercase tracking-widest text-ink-tertiary">
          {BRAND.fullName}
        </p>
        <h1 className="mt-4 font-display text-display-xl font-medium tracking-tight text-ink-primary">
          {headlineLead}
          <span className="text-brand-primary">{headlineFinal}</span>
        </h1>
        <p className="mt-5 max-w-prose text-body-lg text-ink-secondary">
          Browse independent food vendors near you, explore their menus, and
          order directly.
        </p>
      </motion.section>

      {/* Search + filters */}
      <section className="mx-auto max-w-content px-4 sm:px-6 lg:px-8 pb-8">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="relative w-full sm:max-w-md">
            <Search
              className="pointer-events-none absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-tertiary"
              strokeWidth={1.5}
              aria-hidden="true"
            />
            <Input
              type="search"
              tone="brand"
              size="lg"
              placeholder="Search by name or cuisine"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-11"
              aria-label="Search shops"
            />
          </div>

          <div className="flex flex-wrap items-center gap-2" role="tablist" aria-label="Shop filters">
            {FILTERS.map((f) => {
              const active = activeFilter === f.key
              return (
                <button
                  key={f.key}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  onClick={() => setActiveFilter(f.key)}
                  className="focus:outline-none focus-visible:ring-2 focus-visible:ring-border-tone-focus focus-visible:ring-offset-2 focus-visible:ring-offset-surface-canvas rounded-pill"
                >
                  <Badge
                    variant={active ? "brand" : "subtle"}
                    size="md"
                    className="cursor-pointer rounded-pill px-3"
                  >
                    {f.label}
                  </Badge>
                </button>
              )
            })}
          </div>
        </div>
      </section>

      {/* Grid */}
      <section className="mx-auto max-w-content px-4 sm:px-6 lg:px-8 pb-24">
        {loading ? (
          <div
            className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
            aria-hidden="true"
          >
            {Array.from({ length: 12 }).map((_, i) => (
              <div
                key={i}
                className="rounded-xl border border-border-tone-subtle bg-surface-card overflow-hidden"
              >
                <div className="aspect-[4/3] bg-surface-muted animate-pulse" />
                <div className="p-5 space-y-3">
                  <div className="h-4 w-2/3 rounded bg-surface-muted animate-pulse" />
                  <div className="h-3 w-full rounded bg-surface-muted/70 animate-pulse" />
                  <div className="h-3 w-3/4 rounded bg-surface-muted/70 animate-pulse" />
                </div>
              </div>
            ))}
          </div>
        ) : visibleShops.length === 0 ? (
          <div className="mx-auto flex max-w-prose flex-col items-center gap-4 py-24 text-center">
            <BrandPlaceholder aspect="h-24 w-24" className="rounded-pill" />
            <div>
              <h2 className="font-display text-heading-lg text-ink-primary">
                No shops yet
              </h2>
              <p className="mt-2 text-body text-ink-tertiary">
                {searchQuery
                  ? "Nothing matched that search. Try another term."
                  : "No shops are live right now. Check back soon."}
              </p>
            </div>
          </div>
        ) : (
          <>
            <motion.div
              variants={gridVariants}
              initial="hidden"
              animate="visible"
              className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
            >
              {visibleShops.map((shop) => (
                <motion.div key={shop.slug} variants={itemVariants}>
                  <ShopCard shop={shop} />
                </motion.div>
              ))}
            </motion.div>

            {totalPages > 1 && (
              <div className="mt-12 flex items-center justify-center gap-3">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={page === 0}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                >
                  Previous
                </Button>
                <span className="text-caption text-ink-tertiary px-2 tabular-nums">
                  Page {page + 1} of {totalPages}
                </span>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={page >= totalPages - 1}
                  onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                >
                  Next
                </Button>
              </div>
            )}
          </>
        )}
      </section>

      {/* Empty-state fallback icon kept available for a11y tooling */}
      <span className="sr-only">
        <Store aria-hidden="true" />
      </span>
    </div>
  )
}
