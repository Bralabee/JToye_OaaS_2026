"use client"

import { useEffect, useRef, useState, useCallback } from "react"
import { m } from "framer-motion"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import * as z from "zod"
import apiClient from "@/lib/api-client"
import { fetchAllMyShops } from "@/lib/shops-api"
import { useToast } from "@/hooks/use-toast"
import { useShopContext } from "@/hooks/use-shop-context"
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
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Badge } from "@/components/ui/badge"
import { Pagination } from "@/components/ui/pagination"
import { Megaphone, Plus, Pencil, Trash2, Calendar, Filter, Info } from "lucide-react"
import { formatDistanceToNow } from "date-fns"
import type {
  Promotion,
  CreatePromotionRequest,
  Announcement,
  CreateAnnouncementRequest,
  Shop,
} from "@/types/api"

const PAGE_SIZE = 20

// --- Status helpers ---

type ItemStatus = "active" | "upcoming" | "expired" | "disabled"

function getPromotionStatus(promo: Promotion): ItemStatus {
  if (!promo.active) return "disabled"
  const now = new Date()
  if (new Date(promo.validUntil) < now) return "expired"
  if (new Date(promo.validFrom) > now) return "upcoming"
  return "active"
}

function getAnnouncementStatus(ann: Announcement): ItemStatus {
  if (!ann.active) return "disabled"
  const now = new Date()
  if (ann.validUntil && new Date(ann.validUntil) < now) return "expired"
  if (ann.validFrom && new Date(ann.validFrom) > now) return "upcoming"
  return "active"
}

function statusBadgeClass(status: ItemStatus): string {
  switch (status) {
    case "active":
      return "bg-emerald-100 text-emerald-700 hover:bg-emerald-100"
    case "upcoming":
      return "bg-amber-100 text-amber-700 hover:bg-amber-100"
    case "expired":
      return "bg-slate-100 text-slate-500 hover:bg-slate-100"
    case "disabled":
      return "bg-red-100 text-red-700 hover:bg-red-100"
  }
}

function statusLabel(status: ItemStatus): string {
  return status.charAt(0).toUpperCase() + status.slice(1)
}

function formatDiscount(promo: Promotion): string {
  if (promo.discountType === "PERCENTAGE") {
    return `${promo.discountPercent}% off`
  }
  const pounds = ((promo.discountAmountPennies || 0) / 100).toFixed(2)
  return `\u00A3${pounds} off`
}

function formatDate(dateString: string | null): string {
  if (!dateString) return ""
  return new Date(dateString).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
  })
}

function formatDateRelative(dateString: string | null): string {
  if (!dateString) return ""
  return formatDistanceToNow(new Date(dateString), { addSuffix: true })
}

function toDatetimeLocal(iso: string | null): string {
  if (!iso) return ""
  const d = new Date(iso)
  const pad = (n: number) => n.toString().padStart(2, "0")
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// --- Zod schemas ---

const promotionSchema = z
  .object({
    label: z.string().min(1, "Label is required").max(100, "Label too long"),
    discountType: z.enum(["PERCENTAGE", "FLAT_AMOUNT"]),
    discountPercent: z.coerce.number().optional(),
    discountAmountPounds: z.coerce.number().optional(),
    category: z.string().max(100).optional().or(z.literal("")),
    validFrom: z.string().min(1, "Start date is required"),
    validUntil: z.string().min(1, "End date is required"),
    active: z.boolean(),
    shopId: z.string().min(1, "Shop is required"),
  })
  .refine(
    (data) => {
      if (data.discountType === "PERCENTAGE") {
        return (
          data.discountPercent !== undefined &&
          data.discountPercent >= 1 &&
          data.discountPercent <= 100
        )
      }
      return (
        data.discountAmountPounds !== undefined &&
        data.discountAmountPounds > 0
      )
    },
    {
      message: "Please enter a valid discount value",
      path: ["discountPercent"],
    }
  )

type PromotionFormData = z.infer<typeof promotionSchema>

const announcementSchema = z.object({
  title: z.string().min(1, "Title is required").max(200, "Title too long"),
  body: z.string().max(2000).optional().or(z.literal("")),
  validFrom: z.string().optional().or(z.literal("")),
  validUntil: z.string().optional().or(z.literal("")),
  active: z.boolean(),
  shopId: z.string().min(1, "Shop is required"),
})

type AnnouncementFormData = z.infer<typeof announcementSchema>

// --- Filter types ---

type StatusFilter = "all" | "active" | "upcoming" | "expired"

// --- #306: telling the truth about a client-side filter ---

/**
 * The header count for a list whose status filter runs CLIENT-side over the
 * current server page.
 *
 * The bug this closes (#306): the header rendered `totalElements` — the
 * server's UNFILTERED total — regardless of the filter, so selecting "Active"
 * showed "3 promotions in total" above a table of 2 rows. That is wrong at
 * ANY size, including a single page, which is why the count is fixed here
 * rather than deferred with the paging half.
 *
 * `totalPages` decides whether a caveat is warranted, and it matters that it
 * is checked rather than assumed. On a single page the client-side filter has
 * seen every row, so `shown` is the exact and complete answer — captioning a
 * correct view with "on this page" would be a lie in the other direction, and
 * a caveat a vendor sees on every correct view is one they learn to ignore.
 */
function listCountDescription(args: {
  noun: string
  total: number
  totalPages: number
  shown: number
  status: StatusFilter
  scope: string
}): string {
  const { noun, total, totalPages, shown, status, scope } = args
  const nouns = (n: number) => (n === 1 ? noun : `${noun}s`)

  // Unfiltered: byte-identical to the pre-#306 string. The default view is the
  // one every vendor sees, and it was never wrong.
  if (status === "all") return `${total} ${nouns(total)}${scope}`

  // One page — the client-side filter is complete, so this count is exact.
  if (totalPages <= 1) return `${shown} ${status} of ${total} ${nouns(total)}${scope}`

  // More pages exist that this filter never saw. Say which number is which.
  return `${shown} ${status} on this page — of ${total} ${nouns(total)}${scope}`
}

/**
 * True when the status filter is narrowing only part of the set, i.e. the
 * vendor is being shown an arbitrary subset and needs telling.
 */
function isFilterPageLocal(status: StatusFilter, totalPages: number): boolean {
  return status !== "all" && totalPages > 1
}

/**
 * Disclosure for the half of #306 that CANNOT be fixed in the browser.
 *
 * `GET /promotions` and `GET /announcements` take no status parameter — the
 * live API returns the identical unfiltered page for `?status=active` and for
 * `?status=nonsense`, so sending one would look like a fix and change nothing.
 * Until those endpoints grow a date-window predicate, a multi-page list under a
 * status filter genuinely shows an arbitrary subset. The vendor is told so
 * rather than left to infer it from a count that no longer adds up.
 *
 * The live region is mounted unconditionally so the announcement is reliable:
 * an `aria-live` element inserted at the same moment as its text is announced
 * inconsistently across screen readers. `empty:` keeps the spacing honest when
 * there is nothing to say.
 */
function PageLocalFilterNotice({
  status,
  totalPages,
  noun,
}: {
  status: StatusFilter
  totalPages: number
  noun: string
}) {
  return (
    <div role="status" aria-live="polite" className="mb-4 empty:mb-0">
      {isFilterPageLocal(status, totalPages) && (
        <div className="flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900">
          <Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span>
            The <strong className="font-semibold">{status}</strong> filter narrows this
            page only. Other pages may hold more {status} {noun}s — page through to see
            them.
          </span>
        </div>
      )}
    </div>
  )
}

/**
 * Shown when a status filter matches nothing on the current page but the list
 * itself is not empty.
 *
 * Before #306 this branch did not exist: the table rendered its header row over
 * zero body rows and said nothing. Measured on the live stack with the seeded
 * tenant (2 active + 1 expired promotion, 0 upcoming) — clicking "Upcoming"
 * produced a captioned but bodyless table under a header still reading "3
 * promotions in total". An empty result is a legitimate answer; a blank band is
 * not, so this names the reason and offers the way back.
 */
function NoFilterMatches({
  status,
  noun,
  total,
  complete,
  onClear,
}: {
  status: StatusFilter
  noun: string
  total: number
  complete: boolean
  onClear: () => void
}) {
  return (
    <div className="flex flex-col items-center justify-center py-12 text-center">
      <Filter className="mb-4 h-12 w-12 text-slate-300" aria-hidden="true" />
      <h3 className="mb-2 text-lg font-semibold text-slate-900">
        No {status} {noun}s{complete ? "" : " on this page"}
      </h3>
      <p className="mb-4 max-w-sm text-sm text-slate-500">
        {complete
          ? `None of your ${total} ${total === 1 ? noun : `${noun}s`} are ${status} right now.`
          : `Nothing on this page is ${status}. Other pages may have some — the filter only sees the page you are on.`}
      </p>
      <Button onClick={onClear} variant="outline">
        Show all {noun}s
      </Button>
    </div>
  )
}

// --- Component ---

export default function MarketingPage() {
  const [activeTab, setActiveTab] = useState<"promotions" | "announcements">("promotions")
  const { toast } = useToast()

  // Shops (shared for dropdowns)
  const [shops, setShops] = useState<Shop[]>([])

  // VSA-03: the persisted switcher selection. `null` = All shops (no narrow).
  const { contextShopId } = useShopContext()

  // Promotions state
  const [promotions, setPromotions] = useState<Promotion[]>([])
  const [promoLoading, setPromoLoading] = useState(true)
  const [promoPage, setPromoPage] = useState(0)
  const [promoTotalPages, setPromoTotalPages] = useState(0)
  const [promoTotalElements, setPromoTotalElements] = useState(0)
  const [promoDialogOpen, setPromoDialogOpen] = useState(false)
  const [promoDeleteDialogOpen, setPromoDeleteDialogOpen] = useState(false)
  const [editingPromotion, setEditingPromotion] = useState<Promotion | null>(null)
  const [deletingPromotion, setDeletingPromotion] = useState<Promotion | null>(null)
  const [promoSubmitting, setPromoSubmitting] = useState(false)
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all")

  // Announcements state
  const [announcements, setAnnouncements] = useState<Announcement[]>([])
  const [announcementsLoading, setAnnouncementsLoading] = useState(true)
  const [announcementsPage, setAnnouncementsPage] = useState(0)
  const [announcementsTotalPages, setAnnouncementsTotalPages] = useState(0)
  const [announcementsTotalElements, setAnnouncementsTotalElements] = useState(0)
  const [announcementDialogOpen, setAnnouncementDialogOpen] = useState(false)
  const [announcementDeleteDialogOpen, setAnnouncementDeleteDialogOpen] = useState(false)
  const [editingAnnouncement, setEditingAnnouncement] = useState<Announcement | null>(null)
  const [deletingAnnouncement, setDeletingAnnouncement] = useState<Announcement | null>(null)
  const [announcementSubmitting, setAnnouncementSubmitting] = useState(false)
  const [announcementStatusFilter, setAnnouncementStatusFilter] = useState<StatusFilter>("all")

  // Forms — type the form with z.input (pre-coerce) and z.output (post-coerce)
  // explicitly so react-hook-form reconciles zod@v4 coerced fields and
  // refinements without needing `as any`. The resolver's TContext is void.
  const promoForm = useForm<
    z.input<typeof promotionSchema>,
    unknown,
    z.output<typeof promotionSchema>
  >({
    resolver: zodResolver(promotionSchema),
    defaultValues: { discountType: "PERCENTAGE", active: true },
  })

  const annForm = useForm<AnnouncementFormData>({
    resolver: zodResolver(announcementSchema),
    defaultValues: { active: true },
  })

  // --- Data fetching ---

  const fetchShops = useCallback(async () => {
    try {
      // #485 (call site :357): was a single `/api/v1/shops?page=0&size=100&...`,
      // whose first page was treated as the whole list. Past 100 shops the tail
      // could not be targeted by a promotion or an announcement at all. The
      // `name,asc` sort is passed through so the dropdown stays alphabetical.
      setShops(await fetchAllMyShops("name,asc"))
    } catch {
      // Non-critical — dropdown will be empty
    }
  }, [])

  const fetchPromotions = useCallback(async () => {
    try {
      setPromoLoading(true)
      // VSA-03 / WR-04 (#280): narrow SERVER-side so rows, count and pager agree.
      const shopScope = contextShopId ? `&shopId=${contextShopId}` : ""
      const response = await apiClient.get(
        `/api/v1/promotions?page=${promoPage}&size=${PAGE_SIZE}&sort=createdAt,desc${shopScope}`
      )
      setPromotions(response.data.content || [])
      setPromoTotalPages(response.data.totalPages || 0)
      setPromoTotalElements(response.data.totalElements || 0)
    } catch (error: unknown) {
      const msg = error instanceof Error ? error.message : "Failed to load promotions"
      toast({ variant: "destructive", title: "Error loading promotions", description: msg })
    } finally {
      setPromoLoading(false)
    }
  }, [promoPage, contextShopId, toast])

  const fetchAnnouncements = useCallback(async () => {
    try {
      setAnnouncementsLoading(true)
      // VSA-03 / WR-04 (#280): narrow SERVER-side so rows, count and pager agree.
      const shopScope = contextShopId ? `&shopId=${contextShopId}` : ""
      const response = await apiClient.get(
        `/api/v1/announcements?page=${announcementsPage}&size=${PAGE_SIZE}&sort=createdAt,desc${shopScope}`
      )
      setAnnouncements(response.data.content || [])
      setAnnouncementsTotalPages(response.data.totalPages || 0)
      setAnnouncementsTotalElements(response.data.totalElements || 0)
    } catch (error: unknown) {
      const msg = error instanceof Error ? error.message : "Failed to load announcements"
      toast({ variant: "destructive", title: "Error loading announcements", description: msg })
    } finally {
      setAnnouncementsLoading(false)
    }
  }, [announcementsPage, contextShopId, toast])

  useEffect(() => {
    fetchShops()
  }, [fetchShops])

  // WR-04 (#280): a shop change sends BOTH pagers back to page 0 — the new shop
  // may have fewer pages than the current index, which would strand the vendor on
  // an out-of-range empty page. Both fetchers already refetch on their own (the
  // switcher is in their useCallback deps); this only corrects the page index.
  // When a pager is already at 0 — the common case — setState is a no-op and React
  // bails out, so the switch still costs exactly one request per list.
  const prevShopRef = useRef<string | null | undefined>(undefined)
  useEffect(() => {
    if (prevShopRef.current !== undefined && prevShopRef.current !== contextShopId) {
      setPromoPage(0)
      setAnnouncementsPage(0)
    }
    prevShopRef.current = contextShopId
  }, [contextShopId])

  useEffect(() => {
    fetchPromotions()
  }, [fetchPromotions])

  useEffect(() => {
    if (activeTab === "announcements") {
      fetchAnnouncements()
    }
  }, [activeTab, fetchAnnouncements])

  // --- Promotions CRUD ---

  const openCreatePromo = () => {
    setEditingPromotion(null)
    promoForm.reset({
      label: "",
      discountType: "PERCENTAGE",
      discountPercent: undefined,
      discountAmountPounds: undefined,
      category: "",
      validFrom: "",
      validUntil: "",
      active: true,
      // D-08: a single-shop context does single-shop writes only.
      shopId: contextShopId ?? "",
    })
    setPromoDialogOpen(true)
  }

  const openEditPromo = (promo: Promotion) => {
    setEditingPromotion(promo)
    promoForm.reset({
      label: promo.label,
      discountType: promo.discountType,
      discountPercent: promo.discountPercent ?? undefined,
      discountAmountPounds: promo.discountAmountPennies
        ? promo.discountAmountPennies / 100
        : undefined,
      category: promo.category || "",
      validFrom: toDatetimeLocal(promo.validFrom),
      validUntil: toDatetimeLocal(promo.validUntil),
      active: promo.active,
      shopId: promo.shopId,
    })
    setPromoDialogOpen(true)
  }

  const onSubmitPromo = async (data: PromotionFormData) => {
    try {
      setPromoSubmitting(true)
      const payload: CreatePromotionRequest = {
        label: data.label,
        discountType: data.discountType,
        discountPercent:
          data.discountType === "PERCENTAGE" ? data.discountPercent : undefined,
        discountAmountPennies:
          data.discountType === "FLAT_AMOUNT"
            ? Math.round((data.discountAmountPounds || 0) * 100)
            : undefined,
        category: data.category || undefined,
        validFrom: new Date(data.validFrom).toISOString(),
        validUntil: new Date(data.validUntil).toISOString(),
        active: data.active,
        shopId: data.shopId,
      }

      if (editingPromotion) {
        await apiClient.put(`/api/v1/promotions/${editingPromotion.id}`, payload)
        toast({ title: "Promotion updated", description: `${data.label} has been updated.` })
      } else {
        await apiClient.post("/api/v1/promotions", payload)
        toast({ title: "Promotion created", description: `${data.label} has been created.` })
      }

      setPromoDialogOpen(false)
      promoForm.reset()
      if (promoPage === 0) fetchPromotions()
      else setPromoPage(0)
    } catch (error: unknown) {
      const msg =
        error instanceof Error
          ? error.message
          : `Failed to ${editingPromotion ? "update" : "create"} promotion`
      toast({
        variant: "destructive",
        title: editingPromotion ? "Error updating promotion" : "Error creating promotion",
        description: msg,
      })
    } finally {
      setPromoSubmitting(false)
    }
  }

  const handleDeletePromo = async () => {
    if (!deletingPromotion) return
    try {
      setPromoSubmitting(true)
      await apiClient.delete(`/api/v1/promotions/${deletingPromotion.id}`)
      toast({
        title: "Promotion deleted",
        description: `${deletingPromotion.label} has been deleted.`,
      })
      setPromoDeleteDialogOpen(false)
      setDeletingPromotion(null)
      if (promoPage === 0) fetchPromotions()
      else setPromoPage(0)
    } catch (error: unknown) {
      const msg = error instanceof Error ? error.message : "Failed to delete promotion"
      toast({ variant: "destructive", title: "Error deleting promotion", description: msg })
    } finally {
      setPromoSubmitting(false)
    }
  }

  // --- Announcements CRUD ---

  const openCreateAnnouncement = () => {
    setEditingAnnouncement(null)
    annForm.reset({
      title: "",
      body: "",
      validFrom: "",
      validUntil: "",
      active: true,
      // D-08: a single-shop context does single-shop writes only.
      shopId: contextShopId ?? "",
    })
    setAnnouncementDialogOpen(true)
  }

  const openEditAnnouncement = (ann: Announcement) => {
    setEditingAnnouncement(ann)
    annForm.reset({
      title: ann.title,
      body: ann.body || "",
      validFrom: toDatetimeLocal(ann.validFrom),
      validUntil: toDatetimeLocal(ann.validUntil),
      active: ann.active,
      shopId: ann.shopId,
    })
    setAnnouncementDialogOpen(true)
  }

  const onSubmitAnnouncement = async (data: AnnouncementFormData) => {
    try {
      setAnnouncementSubmitting(true)
      const payload: CreateAnnouncementRequest = {
        title: data.title,
        body: data.body || undefined,
        validFrom: data.validFrom ? new Date(data.validFrom).toISOString() : undefined,
        validUntil: data.validUntil ? new Date(data.validUntil).toISOString() : undefined,
        active: data.active,
        shopId: data.shopId,
      }

      if (editingAnnouncement) {
        await apiClient.put(`/api/v1/announcements/${editingAnnouncement.id}`, payload)
        toast({
          title: "Announcement updated",
          description: `${data.title} has been updated.`,
        })
      } else {
        await apiClient.post("/api/v1/announcements", payload)
        toast({
          title: "Announcement created",
          description: `${data.title} has been created.`,
        })
      }

      setAnnouncementDialogOpen(false)
      annForm.reset()
      if (announcementsPage === 0) fetchAnnouncements()
      else setAnnouncementsPage(0)
    } catch (error: unknown) {
      const msg =
        error instanceof Error
          ? error.message
          : `Failed to ${editingAnnouncement ? "update" : "create"} announcement`
      toast({
        variant: "destructive",
        title: editingAnnouncement
          ? "Error updating announcement"
          : "Error creating announcement",
        description: msg,
      })
    } finally {
      setAnnouncementSubmitting(false)
    }
  }

  const handleDeleteAnnouncement = async () => {
    if (!deletingAnnouncement) return
    try {
      setAnnouncementSubmitting(true)
      await apiClient.delete(`/api/v1/announcements/${deletingAnnouncement.id}`)
      toast({
        title: "Announcement deleted",
        description: `${deletingAnnouncement.title} has been deleted.`,
      })
      setAnnouncementDeleteDialogOpen(false)
      setDeletingAnnouncement(null)
      if (announcementsPage === 0) fetchAnnouncements()
      else setAnnouncementsPage(0)
    } catch (error: unknown) {
      const msg = error instanceof Error ? error.message : "Failed to delete announcement"
      toast({
        variant: "destructive",
        title: "Error deleting announcement",
        description: msg,
      })
    } finally {
      setAnnouncementSubmitting(false)
    }
  }

  // --- Filtering ---

  // WR-04 (#280): the SHOP narrow moved SERVER-side (`?shopId=`), so the rows
  // arriving here are already the selected shop's — and the count and pager now
  // describe that same set instead of the whole tenant's.
  //
  // #306: the STATUS filter is still client-side, because it cannot be anything
  // else from here. `GET /promotions` and `GET /announcements` accept no status
  // parameter — verified against the live API on 2026-08-03, where
  // `?status=active` and `?status=nonsense-value-xyz` both returned the same
  // unfiltered page (totalElements=3) as no parameter at all. Sending one would
  // be a fail-open no-op that merely LOOKS like a fix. The server-side
  // date-window predicate is a backend change and is escalated, not faked here.
  //
  // What this file CAN own, and now does, is not lying about it: the header
  // count describes the rows actually on screen (`listCountDescription`), a
  // multi-page narrow says so out loud (`PageLocalFilterNotice`), and a filter
  // that matches nothing renders a reason instead of a bodyless table
  // (`NoFilterMatches`).
  const contextShopName = contextShopId
    ? shops.find((s) => s.id === contextShopId)?.name || "Selected shop"
    : undefined

  const filteredPromotions = promotions.filter(
    (p) => statusFilter === "all" || getPromotionStatus(p) === statusFilter
  )

  const filteredAnnouncements = announcements.filter(
    (a) =>
      announcementStatusFilter === "all" ||
      getAnnouncementStatus(a) === announcementStatusFilter
  )

  // --- Shared filter bar ---

  const shopName = (shopId: string) => shops.find((s) => s.id === shopId)?.name || "Unknown"

  const watchDiscountType = promoForm.watch("discountType")

  // --- Render ---

  if (activeTab === "promotions" && promoLoading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-blue-600"></div>
      </div>
    )
  }

  if (activeTab === "announcements" && announcementsLoading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-blue-600"></div>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <m.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
      >
        <h1 className="text-4xl font-bold text-slate-900">Marketing</h1>
        <p className="mt-2 text-slate-600">Manage promotions and announcements</p>
      </m.div>

      {/* Tab bar */}
      <div className="flex border-b border-slate-200">
        <button
          onClick={() => setActiveTab("promotions")}
          className={`px-4 py-2.5 text-sm font-medium transition-colors ${
            activeTab === "promotions"
              ? "border-b-2 border-blue-600 text-blue-600"
              : "text-slate-500 hover:text-slate-700"
          }`}
        >
          Promotions
        </button>
        <button
          onClick={() => setActiveTab("announcements")}
          className={`px-4 py-2.5 text-sm font-medium transition-colors ${
            activeTab === "announcements"
              ? "border-b-2 border-blue-600 text-blue-600"
              : "text-slate-500 hover:text-slate-700"
          }`}
        >
          Announcements
        </button>
      </div>

      {/* ==================== PROMOTIONS TAB ==================== */}
      {activeTab === "promotions" && (
        <m.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1 }}
        >
          {/* Status filter bar */}
          <div className="mb-4 flex items-center gap-2">
            {(["all", "active", "upcoming", "expired"] as StatusFilter[]).map((f) => (
              <Button
                key={f}
                variant={statusFilter === f ? "default" : "outline"}
                size="sm"
                onClick={() => setStatusFilter(f)}
              >
                {f === "all" ? "All" : f.charAt(0).toUpperCase() + f.slice(1)}
              </Button>
            ))}
            <div className="flex-1" />
            <Button onClick={openCreatePromo} className="gap-2">
              <Plus className="h-4 w-4" />
              Create Promotion
            </Button>
          </div>

          <PageLocalFilterNotice
            status={statusFilter}
            totalPages={promoTotalPages}
            noun="promotion"
          />

          <Card>
            <CardHeader>
              <CardTitle>Promotions</CardTitle>
              <CardDescription>
                {listCountDescription({
                  noun: "promotion",
                  total: promoTotalElements,
                  totalPages: promoTotalPages,
                  shown: filteredPromotions.length,
                  status: statusFilter,
                  scope: contextShopId ? ` in ${contextShopName}` : " in total",
                })}
              </CardDescription>
            </CardHeader>
            <CardContent>
              {promotions.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-12 text-center">
                  <Megaphone className="mb-4 h-12 w-12 text-slate-300" />
                  <h3 className="mb-2 text-lg font-semibold text-slate-900">
                    No promotions yet
                  </h3>
                  <p className="mb-4 text-sm text-slate-500">
                    Create your first promotion to attract more customers
                  </p>
                  <Button onClick={openCreatePromo} variant="outline">
                    <Plus className="mr-2 h-4 w-4" />
                    Create Promotion
                  </Button>
                </div>
              ) : filteredPromotions.length === 0 ? (
                <NoFilterMatches
                  status={statusFilter}
                  noun="promotion"
                  total={promoTotalElements}
                  complete={promoTotalPages <= 1}
                  onClear={() => setStatusFilter("all")}
                />
              ) : (
                <div className="overflow-x-auto">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>Label</TableHead>
                        <TableHead>Discount</TableHead>
                        <TableHead>Shop</TableHead>
                        <TableHead>Status</TableHead>
                        <TableHead>Valid From</TableHead>
                        <TableHead>Valid Until</TableHead>
                        <TableHead className="text-right">Actions</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {filteredPromotions.map((promo) => {
                        const status = getPromotionStatus(promo)
                        return (
                          <m.tr
                            key={promo.id}
                            initial={{ opacity: 0 }}
                            animate={{ opacity: 1 }}
                            className="group"
                          >
                            <TableCell className="font-medium">{promo.label}</TableCell>
                            <TableCell>{formatDiscount(promo)}</TableCell>
                            <TableCell className="text-slate-600">
                              {shopName(promo.shopId)}
                            </TableCell>
                            <TableCell>
                              <Badge className={statusBadgeClass(status)}>
                                {statusLabel(status)}
                              </Badge>
                            </TableCell>
                            <TableCell className="text-slate-600">
                              <div className="flex items-center gap-2">
                                <Calendar className="h-4 w-4" />
                                <div>
                                  <div>{formatDate(promo.validFrom)}</div>
                                  <div className="text-xs text-slate-400">
                                    {formatDateRelative(promo.validFrom)}
                                  </div>
                                </div>
                              </div>
                            </TableCell>
                            <TableCell className="text-slate-600">
                              <div className="flex items-center gap-2">
                                <Calendar className="h-4 w-4" />
                                <div>
                                  <div>{formatDate(promo.validUntil)}</div>
                                  <div className="text-xs text-slate-400">
                                    {formatDateRelative(promo.validUntil)}
                                  </div>
                                </div>
                              </div>
                            </TableCell>
                            <TableCell className="text-right">
                              <div className="flex justify-end gap-2">
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  onClick={() => openEditPromo(promo)}
                                  className="h-8 w-8 p-0"
                                >
                                  <Pencil className="h-4 w-4" />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  onClick={() => {
                                    setDeletingPromotion(promo)
                                    setPromoDeleteDialogOpen(true)
                                  }}
                                  className="h-8 w-8 p-0 text-red-600 hover:bg-red-50 hover:text-red-700"
                                >
                                  <Trash2 className="h-4 w-4" />
                                </Button>
                              </div>
                            </TableCell>
                          </m.tr>
                        )
                      })}
                    </TableBody>
                  </Table>
                </div>
              )}
              <Pagination
                currentPage={promoPage}
                totalPages={promoTotalPages}
                totalElements={promoTotalElements}
                pageSize={PAGE_SIZE}
                onPageChange={setPromoPage}
              />
            </CardContent>
          </Card>
        </m.div>
      )}

      {/* ==================== ANNOUNCEMENTS TAB ==================== */}
      {activeTab === "announcements" && (
        <m.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1 }}
        >
          {/* Status filter bar */}
          <div className="mb-4 flex items-center gap-2">
            {(["all", "active", "upcoming", "expired"] as StatusFilter[]).map((f) => (
              <Button
                key={f}
                variant={announcementStatusFilter === f ? "default" : "outline"}
                size="sm"
                onClick={() => setAnnouncementStatusFilter(f)}
              >
                {f === "all" ? "All" : f.charAt(0).toUpperCase() + f.slice(1)}
              </Button>
            ))}
            <div className="flex-1" />
            <Button onClick={openCreateAnnouncement} className="gap-2">
              <Plus className="h-4 w-4" />
              Create Announcement
            </Button>
          </div>

          <PageLocalFilterNotice
            status={announcementStatusFilter}
            totalPages={announcementsTotalPages}
            noun="announcement"
          />

          <Card>
            <CardHeader>
              <CardTitle>Announcements</CardTitle>
              <CardDescription>
                {listCountDescription({
                  noun: "announcement",
                  total: announcementsTotalElements,
                  totalPages: announcementsTotalPages,
                  shown: filteredAnnouncements.length,
                  status: announcementStatusFilter,
                  scope: contextShopId ? ` in ${contextShopName}` : " in total",
                })}
              </CardDescription>
            </CardHeader>
            <CardContent>
              {announcements.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-12 text-center">
                  <Megaphone className="mb-4 h-12 w-12 text-slate-300" />
                  <h3 className="mb-2 text-lg font-semibold text-slate-900">
                    No announcements yet
                  </h3>
                  <p className="mb-4 text-sm text-slate-500">
                    Share news and updates with your customers
                  </p>
                  <Button onClick={openCreateAnnouncement} variant="outline">
                    <Plus className="mr-2 h-4 w-4" />
                    Create Announcement
                  </Button>
                </div>
              ) : filteredAnnouncements.length === 0 ? (
                <NoFilterMatches
                  status={announcementStatusFilter}
                  noun="announcement"
                  total={announcementsTotalElements}
                  complete={announcementsTotalPages <= 1}
                  onClear={() => setAnnouncementStatusFilter("all")}
                />
              ) : (
                <div className="overflow-x-auto">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>Title</TableHead>
                        <TableHead>Body</TableHead>
                        <TableHead>Shop</TableHead>
                        <TableHead>Status</TableHead>
                        <TableHead>Valid From</TableHead>
                        <TableHead>Valid Until</TableHead>
                        <TableHead className="text-right">Actions</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {filteredAnnouncements.map((ann) => {
                        const status = getAnnouncementStatus(ann)
                        return (
                          <m.tr
                            key={ann.id}
                            initial={{ opacity: 0 }}
                            animate={{ opacity: 1 }}
                            className="group"
                          >
                            <TableCell className="font-medium">{ann.title}</TableCell>
                            <TableCell className="max-w-[200px] truncate text-slate-600">
                              {ann.body
                                ? ann.body.length > 80
                                  ? ann.body.slice(0, 80) + "..."
                                  : ann.body
                                : ""}
                            </TableCell>
                            <TableCell className="text-slate-600">
                              {shopName(ann.shopId)}
                            </TableCell>
                            <TableCell>
                              <Badge className={statusBadgeClass(status)}>
                                {statusLabel(status)}
                              </Badge>
                            </TableCell>
                            <TableCell className="text-slate-600">
                              {ann.validFrom ? (
                                <div className="flex items-center gap-2">
                                  <Calendar className="h-4 w-4" />
                                  <div>
                                    <div>{formatDate(ann.validFrom)}</div>
                                    <div className="text-xs text-slate-400">
                                      {formatDateRelative(ann.validFrom)}
                                    </div>
                                  </div>
                                </div>
                              ) : (
                                <span className="text-slate-400">Always</span>
                              )}
                            </TableCell>
                            <TableCell className="text-slate-600">
                              {ann.validUntil ? (
                                <div className="flex items-center gap-2">
                                  <Calendar className="h-4 w-4" />
                                  <div>
                                    <div>{formatDate(ann.validUntil)}</div>
                                    <div className="text-xs text-slate-400">
                                      {formatDateRelative(ann.validUntil)}
                                    </div>
                                  </div>
                                </div>
                              ) : (
                                <span className="text-slate-400">No end</span>
                              )}
                            </TableCell>
                            <TableCell className="text-right">
                              <div className="flex justify-end gap-2">
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  onClick={() => openEditAnnouncement(ann)}
                                  className="h-8 w-8 p-0"
                                >
                                  <Pencil className="h-4 w-4" />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  onClick={() => {
                                    setDeletingAnnouncement(ann)
                                    setAnnouncementDeleteDialogOpen(true)
                                  }}
                                  className="h-8 w-8 p-0 text-red-600 hover:bg-red-50 hover:text-red-700"
                                >
                                  <Trash2 className="h-4 w-4" />
                                </Button>
                              </div>
                            </TableCell>
                          </m.tr>
                        )
                      })}
                    </TableBody>
                  </Table>
                </div>
              )}
              <Pagination
                currentPage={announcementsPage}
                totalPages={announcementsTotalPages}
                totalElements={announcementsTotalElements}
                pageSize={PAGE_SIZE}
                onPageChange={setAnnouncementsPage}
              />
            </CardContent>
          </Card>
        </m.div>
      )}

      {/* ==================== PROMOTION CREATE/EDIT DIALOG ==================== */}
      <Dialog open={promoDialogOpen} onOpenChange={setPromoDialogOpen}>
        <DialogContent className="max-w-2xl max-h-[85vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>
              {editingPromotion ? "Edit Promotion" : "Create New Promotion"}
            </DialogTitle>
            <DialogDescription>
              {editingPromotion
                ? "Update the promotion details below."
                : "Create a new promotion for your customers."}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={promoForm.handleSubmit(onSubmitPromo)} className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="promo-label">Label *</Label>
                <Input
                  id="promo-label"
                  placeholder="e.g., Summer Sale 20% Off"
                  {...promoForm.register("label")}
                />
                {promoForm.formState.errors.label && (
                  <p className="text-xs text-red-600">
                    {promoForm.formState.errors.label.message}
                  </p>
                )}
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="promo-shop">Shop *</Label>
                {/* D-08: pinned to the selected shop outside the All-shops context. */}
                <select
                  id="promo-shop"
                  {...promoForm.register("shopId")}
                  disabled={!!contextShopId}
                  className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-70"
                >
                  {contextShopId ? (
                    <option value={contextShopId}>{contextShopName}</option>
                  ) : (
                    <>
                      <option value="">Select a shop</option>
                      {shops.map((shop) => (
                        <option key={shop.id} value={shop.id}>
                          {shop.name}
                        </option>
                      ))}
                    </>
                  )}
                </select>
                {promoForm.formState.errors.shopId && (
                  <p className="text-xs text-red-600">
                    {promoForm.formState.errors.shopId.message}
                  </p>
                )}
              </div>
            </div>

            {/* Discount type toggle */}
            <div className="space-y-1.5">
              <Label>Discount Type</Label>
              <div className="flex gap-2">
                <Button
                  type="button"
                  variant={watchDiscountType === "PERCENTAGE" ? "default" : "outline"}
                  size="sm"
                  onClick={() => promoForm.setValue("discountType", "PERCENTAGE")}
                >
                  Percentage
                </Button>
                <Button
                  type="button"
                  variant={watchDiscountType === "FLAT_AMOUNT" ? "default" : "outline"}
                  size="sm"
                  onClick={() => promoForm.setValue("discountType", "FLAT_AMOUNT")}
                >
                  Fixed Amount
                </Button>
              </div>
            </div>

            {watchDiscountType === "PERCENTAGE" ? (
              <div className="space-y-1.5">
                <Label htmlFor="promo-percent">Discount Percentage *</Label>
                <div className="relative">
                  <Input
                    id="promo-percent"
                    type="number"
                    min={1}
                    max={100}
                    placeholder="15"
                    {...promoForm.register("discountPercent")}
                  />
                  <span className="absolute right-3 top-2.5 text-sm text-slate-400">%</span>
                </div>
              </div>
            ) : (
              <div className="space-y-1.5">
                <Label htmlFor="promo-amount">Discount Amount *</Label>
                <div className="relative">
                  <span className="absolute left-3 top-2.5 text-sm text-slate-400">
                    {"\u00A3"}
                  </span>
                  <Input
                    id="promo-amount"
                    type="number"
                    step="0.01"
                    min="0.01"
                    placeholder="3.50"
                    className="pl-7"
                    {...promoForm.register("discountAmountPounds")}
                  />
                </div>
              </div>
            )}

            {promoForm.formState.errors.discountPercent && (
              <p className="text-xs text-red-600">
                {promoForm.formState.errors.discountPercent.message}
              </p>
            )}

            <div className="space-y-1.5">
              <Label htmlFor="promo-category">Category (optional)</Label>
              <Input
                id="promo-category"
                placeholder="e.g., Starters, Drinks"
                {...promoForm.register("category")}
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="promo-from">Valid From *</Label>
                <input
                  id="promo-from"
                  type="datetime-local"
                  {...promoForm.register("validFrom")}
                  className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
                {promoForm.formState.errors.validFrom && (
                  <p className="text-xs text-red-600">
                    {promoForm.formState.errors.validFrom.message}
                  </p>
                )}
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="promo-until">Valid Until *</Label>
                <input
                  id="promo-until"
                  type="datetime-local"
                  {...promoForm.register("validUntil")}
                  className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
                {promoForm.formState.errors.validUntil && (
                  <p className="text-xs text-red-600">
                    {promoForm.formState.errors.validUntil.message}
                  </p>
                )}
              </div>
            </div>

            <div className="flex items-center gap-2">
              <input
                type="checkbox"
                id="promo-active"
                {...promoForm.register("active")}
                className="h-4 w-4 rounded border-slate-300"
              />
              <Label htmlFor="promo-active" className="text-sm font-normal">
                Active (visible to customers)
              </Label>
            </div>

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setPromoDialogOpen(false)}
                disabled={promoSubmitting}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={promoSubmitting}>
                {promoSubmitting
                  ? editingPromotion
                    ? "Updating..."
                    : "Creating..."
                  : editingPromotion
                    ? "Update Promotion"
                    : "Create Promotion"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Promotion Delete Confirmation Dialog */}
      <Dialog open={promoDeleteDialogOpen} onOpenChange={setPromoDeleteDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Promotion</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete{" "}
              <span className="font-semibold">{deletingPromotion?.label}</span>? This
              action cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setPromoDeleteDialogOpen(false)}
              disabled={promoSubmitting}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleDeletePromo}
              disabled={promoSubmitting}
            >
              {promoSubmitting ? "Deleting..." : "Delete Promotion"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ==================== ANNOUNCEMENT CREATE/EDIT DIALOG ==================== */}
      <Dialog open={announcementDialogOpen} onOpenChange={setAnnouncementDialogOpen}>
        <DialogContent className="max-w-2xl max-h-[85vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>
              {editingAnnouncement ? "Edit Announcement" : "Create New Announcement"}
            </DialogTitle>
            <DialogDescription>
              {editingAnnouncement
                ? "Update the announcement details below."
                : "Share news and updates with your customers."}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={annForm.handleSubmit(onSubmitAnnouncement)} className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="ann-title">Title *</Label>
                <Input
                  id="ann-title"
                  placeholder="e.g., New Menu Available"
                  {...annForm.register("title")}
                />
                {annForm.formState.errors.title && (
                  <p className="text-xs text-red-600">
                    {annForm.formState.errors.title.message}
                  </p>
                )}
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="ann-shop">Shop *</Label>
                {/* D-08: pinned to the selected shop outside the All-shops context. */}
                <select
                  id="ann-shop"
                  {...annForm.register("shopId")}
                  disabled={!!contextShopId}
                  className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-70"
                >
                  {contextShopId ? (
                    <option value={contextShopId}>{contextShopName}</option>
                  ) : (
                    <>
                      <option value="">Select a shop</option>
                      {shops.map((shop) => (
                        <option key={shop.id} value={shop.id}>
                          {shop.name}
                        </option>
                      ))}
                    </>
                  )}
                </select>
                {annForm.formState.errors.shopId && (
                  <p className="text-xs text-red-600">
                    {annForm.formState.errors.shopId.message}
                  </p>
                )}
              </div>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="ann-body">Body (optional)</Label>
              <textarea
                id="ann-body"
                placeholder="Tell your customers about the news..."
                {...annForm.register("body")}
                rows={3}
                className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="ann-from">Valid From</Label>
                <p className="text-xs text-slate-400">Leave blank for immediate start</p>
                <input
                  id="ann-from"
                  type="datetime-local"
                  {...annForm.register("validFrom")}
                  className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="ann-until">Valid Until</Label>
                <p className="text-xs text-slate-400">Leave blank for no expiry</p>
                <input
                  id="ann-until"
                  type="datetime-local"
                  {...annForm.register("validUntil")}
                  className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
              </div>
            </div>

            <div className="flex items-center gap-2">
              <input
                type="checkbox"
                id="ann-active"
                {...annForm.register("active")}
                className="h-4 w-4 rounded border-slate-300"
              />
              <Label htmlFor="ann-active" className="text-sm font-normal">
                Active (visible to customers)
              </Label>
            </div>

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setAnnouncementDialogOpen(false)}
                disabled={announcementSubmitting}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={announcementSubmitting}>
                {announcementSubmitting
                  ? editingAnnouncement
                    ? "Updating..."
                    : "Creating..."
                  : editingAnnouncement
                    ? "Update Announcement"
                    : "Create Announcement"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Announcement Delete Confirmation Dialog */}
      <Dialog
        open={announcementDeleteDialogOpen}
        onOpenChange={setAnnouncementDeleteDialogOpen}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Announcement</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete{" "}
              <span className="font-semibold">{deletingAnnouncement?.title}</span>? This
              action cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setAnnouncementDeleteDialogOpen(false)}
              disabled={announcementSubmitting}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleDeleteAnnouncement}
              disabled={announcementSubmitting}
            >
              {announcementSubmitting ? "Deleting..." : "Delete Announcement"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
