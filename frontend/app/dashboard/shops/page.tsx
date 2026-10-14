"use client"

import { useEffect, useRef, useState } from "react"
import { m } from "framer-motion"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import * as z from "zod"
import apiClient from "@/lib/api-client"
import { describeLoadError } from "@/lib/human-error"
import { useToast } from "@/hooks/use-toast"
import { LoadErrorPanel } from "@/components/dashboard/load-error-panel"
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
import { IconButton } from "@/components/ui/icon-button"
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
import { Store, Plus, Pencil, Trash2, MapPin, Calendar, Search, Globe, ImageIcon, Clock, PauseCircle } from "lucide-react"
import { readPauseView } from "@/components/dashboard/kitchen/pause-orders-control"
import { formatUkTime } from "@/lib/shop-availability"
import { ImageUploader } from "@/components/ui/image-uploader"
import { SafeImage } from "@/components/ui/safe-image"
import { Pagination } from "@/components/ui/pagination"
import {
  OpeningScheduleEditor,
  type OpeningScheduleEditorHandle,
} from "@/components/dashboard/shops/opening-schedule-editor"
import type { OpeningScheduleMap } from "@/lib/opening-schedule"
import type { Shop, CreateShopRequest } from "@/types/api"
import { formatDistanceToNow } from "date-fns"

const PAGE_SIZE = 20

const shopSchema = z.object({
  name: z.string().min(1, "Name is required").max(100, "Name too long"),
  address: z.string().max(255, "Address too long").optional().or(z.literal("")),
  description: z.string().max(2000).optional().or(z.literal("")),
  phone: z.string().max(50).optional().or(z.literal("")),
  email: z.string().max(255).optional().or(z.literal("")),
  logoUrl: z.string().max(2000).optional().or(z.literal("")),
  bannerUrl: z.string().max(2000).optional().or(z.literal("")),
  deliveryInfo: z.string().max(500).optional().or(z.literal("")),
  tags: z.string().max(500).optional().or(z.literal("")),
  minimumOrderPounds: z.string().optional().or(z.literal("")),
})

type ShopFormData = z.infer<typeof shopSchema>

/**
 * 37-48 (UXT-028, UI-SPEC G2): the web address as the vendor types it, in the server's slug shape (lower-case
 * letters, digits and single hyphens). Apostrophes and other characters are dropped like the server's own
 * generator drops them; spaces and underscores become hyphens. A trailing hyphen is kept while typing (so
 * "new-" can become "new-place") and trimmed by {@link finalSlug} before sending.
 */
function slugifyAsTyped(value: string): string {
  return value
    .toLowerCase()
    .replace(/[\s_]+/g, "-")
    .replace(/[^a-z0-9-]/g, "")
    .replace(/-+/g, "-")
    .replace(/^-/, "")
    .slice(0, 100)
}

function finalSlug(value: string): string {
  return value.replace(/-+$/, "")
}

/** True when a save failure is the server refusing the requested web address (taken, reserved or malformed). */
function slugRefusal(error: unknown): string | null {
  const data = (error as { response?: { data?: { type?: string; detail?: string; errors?: Record<string, string> } } })
    ?.response?.data
  if (!data) return null
  if (data.type === "https://jtoye.uk/errors/shop-slug-taken" || data.type === "https://jtoye.uk/errors/reserved-shop-slug") {
    return data.detail?.trim() || "This web address can't be used. Choose a different one."
  }
  return data.errors?.slug?.trim() || null
}

export default function ShopsPage() {
  const [shops, setShops] = useState<Shop[]>([])
  const [loading, setLoading] = useState(true)
  const [searchQuery, setSearchQuery] = useState("")
  const [currentPage, setCurrentPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [editingShop, setEditingShop] = useState<Shop | null>(null)
  const [deletingShop, setDeletingShop] = useState<Shop | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [published, setPublished] = useState(false)
  // 37-21 (K4, D-15): the structured hours the dialog will send, or null while the editor is untouched
  // (the request then omits openingSchedule and the server keeps the stored hours and their source).
  // The seven free-text inputs that wrote `openingHours` are gone: that text decides nothing since
  // 37-20, and the original stays readable as ShopDto.legacyOpeningHours for the needs-review notice.
  const [scheduleDraft, setScheduleDraft] = useState<OpeningScheduleMap | null>(null)
  // 37-48 (UI-SPEC G2): the web address is read-only until "Change web address"; only an EDITED value is sent,
  // because the server keeps the slug whenever a request carries none (UXT-028).
  const [slugEditing, setSlugEditing] = useState(false)
  const [slugDraft, setSlugDraft] = useState("")
  const [slugTouched, setSlugTouched] = useState(false)
  const [slugError, setSlugError] = useState<string | null>(null)
  // Re-mounts the editor on every open, so it always starts from the shop being edited.
  const [editorSession, setEditorSession] = useState(0)
  const scheduleEditorRef = useRef<OpeningScheduleEditorHandle>(null)
  // 37-21: the dashboard home's notice links here as ?edit={id}&focus=hours; the dialog then opens
  // with focus on the hours fieldset instead of the first field.
  const [focusHoursOnOpen, setFocusHoursOnOpen] = useState(false)
  const deepLinkHandled = useRef(false)
  // When the list was read: a stored pause end is compared with this, never with a render-time clock.
  const [listedAt, setListedAt] = useState(0)
  // F2 sweep: a 429/network failure must render an error panel, never the
  // "No shops yet" empty state — `fetchShops`'s catch deliberately does not
  // reset `shops` to `[]`.
  const [loadFailed, setLoadFailed] = useState(false)
  const [loadErrorMessage, setLoadErrorMessage] = useState("")
  const { toast } = useToast()

  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
    setValue,
  } = useForm<ShopFormData>({
    resolver: zodResolver(shopSchema),
  })

  const fetchShops = async () => {
    try {
      setLoading(true)
      const response = await apiClient.get(`/api/v1/shops?page=${currentPage}&size=${PAGE_SIZE}&sort=createdAt,desc`)
      setShops(response.data.content || [])
      setListedAt(Date.now())
      setTotalPages(response.data.totalPages || 0)
      setTotalElements(response.data.totalElements || 0)
      setLoadFailed(false)
    } catch (error: unknown) {
      const { message } = describeLoadError(error, "Failed to load shops")
      toast({
        variant: "destructive",
        title: "Error loading shops",
        description: message,
      })
      // F2 sweep: `shops` is deliberately left untouched above.
      setLoadFailed(true)
      setLoadErrorMessage(message)
    } finally {
      setLoading(false)
    }
  }

  const searchShops = async (query: string) => {
    try {
      const response = await apiClient.get(`/api/v1/shops/search?q=${encodeURIComponent(query)}`)
      setShops(response.data || [])
      setListedAt(Date.now())
      setTotalPages(1)
      setTotalElements(response.data?.length || 0)
      setLoadFailed(false)
    } catch (error: unknown) {
      // F2 sweep: this catch used to be fully silent.
      setLoadFailed(true)
      setLoadErrorMessage(describeLoadError(error, "Failed to search shops").message)
    }
  }

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
    fetchShops()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentPage])

  useEffect(() => {
    if (searchQuery.length >= 2) {
      const timer = setTimeout(() => searchShops(searchQuery), 300)
      return () => clearTimeout(timer)
    } else if (searchQuery.length === 0) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
      fetchShops()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchQuery])

  const retryLoad = () => {
    if (searchQuery.length >= 2) searchShops(searchQuery)
    else fetchShops()
  }

  const openCreateDialog = () => {
    setEditingShop(null)
    reset({ name: "", address: "", description: "", phone: "", email: "", logoUrl: "", bannerUrl: "", deliveryInfo: "", tags: "", minimumOrderPounds: "" })
    setPublished(false)
    setScheduleDraft(null)
    resetSlugControl("")
    setEditorSession((n) => n + 1)
    setDialogOpen(true)
  }

  const resetSlugControl = (slug: string) => {
    setSlugEditing(false)
    setSlugDraft(slug)
    setSlugTouched(false)
    setSlugError(null)
  }

  const openEditDialog = (shop: Shop) => {
    setEditingShop(shop)
    setValue("name", shop.name)
    setValue("address", shop.address || "")
    setValue("description", shop.description || "")
    setValue("phone", shop.phone || "")
    setValue("email", shop.email || "")
    setValue("logoUrl", shop.logoUrl || "")
    setValue("bannerUrl", shop.bannerUrl || "")
    setValue("deliveryInfo", shop.deliveryInfo || "")
    setValue("tags", shop.tags || "")
    setValue("minimumOrderPounds", shop.minimumOrderPennies ? (shop.minimumOrderPennies / 100).toString() : "")
    setPublished(shop.published || false)
    setScheduleDraft(null)
    resetSlugControl(shop.slug || "")
    setEditorSession((n) => n + 1)
    setDialogOpen(true)
  }

  // 37-21 (K4 notices): /dashboard/shops?edit={id}&focus=hours, from the dashboard home's hours
  // notice. Handled once, after the first list load; the shop is read by id when it is not on the
  // page that loaded. The parameters are dropped so a reload does not reopen the dialog.
  useEffect(() => {
    if (loading || deepLinkHandled.current) return
    deepLinkHandled.current = true
    const params = new URLSearchParams(window.location.search)
    const id = params.get("edit")
    if (!id) return
    const atHours = params.get("focus") === "hours"
    params.delete("edit")
    params.delete("focus")
    const rest = params.toString()
    window.history.replaceState(
      window.history.state,
      "",
      window.location.pathname + (rest ? `?${rest}` : "") + window.location.hash
    )
    const open = (target: Shop) => {
      setFocusHoursOnOpen(atHours)
      openEditDialog(target)
    }
    const listed = shops.find((s) => s.id === id)
    if (listed) {
      open(listed)
      return
    }
    apiClient
      .get<Shop>(`/api/v1/shops/${id}`)
      .then((res) => {
        if (res?.data?.id) open(res.data)
      })
      .catch((error: unknown) => {
        toast({
          variant: "destructive",
          title: "Error loading shop",
          description: describeLoadError(error, "Could not open that shop").message,
        })
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading, shops])

  const openDeleteDialog = (shop: Shop) => {
    setDeletingShop(shop)
    setDeleteDialogOpen(true)
  }

  // K4: the hours are checked before anything is sent; the editor shows the error and takes focus.
  // Only when they were touched: untouched hours are not sent, so there is nothing to refuse.
  const submitForm = (event: React.FormEvent<HTMLFormElement>) => {
    if (scheduleDraft !== null && scheduleEditorRef.current && !scheduleEditorRef.current.validate()) {
      event.preventDefault()
      return
    }
    return handleSubmit(onSubmit)(event)
  }

  const onSubmit = async (data: ShopFormData) => {
    const editedSlug = finalSlug(slugDraft)
    const sentSlug =
      editingShop && slugTouched && editedSlug && editedSlug !== editingShop.slug ? editedSlug : null
    try {
      setSubmitting(true)
      setSlugError(null)

      const payload: CreateShopRequest = {
        name: data.name,
        address: data.address || undefined,
        description: data.description || undefined,
        phone: data.phone || undefined,
        email: data.email || undefined,
        logoUrl: data.logoUrl || undefined,
        bannerUrl: data.bannerUrl || undefined,
        deliveryInfo: data.deliveryInfo || undefined,
        tags: data.tags || undefined,
        minimumOrderPennies: data.minimumOrderPounds ? Math.round(parseFloat(data.minimumOrderPounds) * 100) : 0,
        published,
        // 37-48 (UXT-028): no slug unless the vendor edited the web address. The server keeps the slug when a
        // request carries none, so an ordinary save (hours, phone, a rename) never moves the shop. This
        // replaces the 37-21 workaround that re-sent the current slug.
        ...(sentSlug ? { slug: sentSlug } : {}),
        // Absent keeps the stored hours; {} saves closed every day; any save makes them STRUCTURED.
        // The key is left out entirely (not sent as undefined) while the editor is untouched.
        ...(scheduleDraft !== null ? { openingSchedule: scheduleDraft } : {}),
      }

      if (editingShop) {
        // Update existing shop
        await apiClient.put(`/api/v1/shops/${editingShop.id}`, payload)
        toast({
          title: "Shop updated",
          description: `${data.name} has been updated successfully.`,
        })
      } else {
        // Create new shop
        await apiClient.post("/api/v1/shops", payload)
        toast({
          title: "Shop created",
          description: `${data.name} has been created successfully.`,
        })
      }

      setDialogOpen(false)
      reset()
      if (currentPage === 0) fetchShops()
      else setCurrentPage(0)
    } catch (error: unknown) {
      // 37-48 (UI-SPEC G2): a refused web address is shown inline beside the field, not as a toast.
      const refusedSlug = sentSlug ? slugRefusal(error) : null
      if (refusedSlug) {
        setSlugError(refusedSlug)
        return
      }
      // A11Y-2 (#688): an axios error IS an Error whose .message is transport
      // text — classify it so an RFC 7807 detail wins and raw strings never show.
      const errorMessage = describeLoadError(
        error,
        `Failed to ${editingShop ? "update" : "create"} shop`
      ).message
      toast({
        variant: "destructive",
        title: editingShop ? "Error updating shop" : "Error creating shop",
        description: errorMessage,
      })
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async () => {
    if (!deletingShop) return

    try {
      setSubmitting(true)
      await apiClient.delete(`/api/v1/shops/${deletingShop.id}`)
      toast({
        title: "Shop deleted",
        description: `${deletingShop.name} has been deleted successfully.`,
      })
      setDeleteDialogOpen(false)
      setDeletingShop(null)
      if (currentPage === 0) fetchShops()
      else setCurrentPage(0)
    } catch (error: unknown) {
      // A11Y-2 (#688): same classification as onSubmit above.
      const errorMessage = describeLoadError(error, "Failed to delete shop").message
      toast({
        variant: "destructive",
        title: "Error deleting shop",
        description: errorMessage,
      })
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-blue-600"></div>
      </div>
    )
  }

  return (
    // Phase 35, Index tier: a resource index, deliberately uncapped below the
    // dashboard band. The tier adds NO width class on purpose — "fluid to the
    // shell" is the documented pattern for data-dense lists — and the
    // attribute is here so that being uncapped is a declaration a test can
    // falsify rather than an absence indistinguishable from a forgotten cap.
    // Do not "tidy" this by adding a max-width.
    <div data-width-tier="index" className="space-y-6">
      {/* Header */}
      <m.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="flex items-center justify-between"
      >
        <div>
          <h1 className="text-4xl font-bold text-slate-900">Shops</h1>
          <p className="mt-2 text-slate-600">Manage your shop locations</p>
        </div>
        <Button onClick={openCreateDialog} className="gap-2">
          <Plus className="h-4 w-4" />
          Add Shop
        </Button>
      </m.div>

      {/* Shops Table */}
      <m.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.1 }}
      >
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <div>
              <CardTitle>All Shops</CardTitle>
              <CardDescription>
                {/* #688: never assert a count nothing loaded (see products). */}
                {loadFailed
                  ? "—"
                  : `${totalElements} shop${totalElements !== 1 ? "s" : ""} in total`}
              </CardDescription>
            </div>
            <div className="relative w-[220px]">
              <Search className="absolute left-2 top-2.5 h-4 w-4 text-slate-400" />
              <Input
                placeholder="Search shops..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-8"
              />
            </div>
          </CardHeader>
          <CardContent>
            {loadFailed ? (
              <LoadErrorPanel
                subject="shops"
                message={loadErrorMessage}
                onRetry={retryLoad}
              />
            ) : shops.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <Store className="mb-4 h-12 w-12 text-slate-300" />
                <h3 className="mb-2 text-lg font-semibold text-slate-900">
                  No shops yet
                </h3>
                <p className="mb-4 text-sm text-slate-500">
                  Get started by creating your first shop
                </p>
                <Button onClick={openCreateDialog} variant="outline">
                  <Plus className="mr-2 h-4 w-4" />
                  Add Shop
                </Button>
              </div>
            ) : (
              <Table containerLabel="Shops table">
                <TableHeader>
                  <TableRow>
                    <TableHead>Name</TableHead>
                    <TableHead>Address</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Created</TableHead>
                    <TableHead className="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {shops.map((shop) => (
                    <m.tr
                      key={shop.id}
                      initial={{ opacity: 0 }}
                      animate={{ opacity: 1 }}
                      className="group"
                    >
                      <TableCell className="font-medium">
                        <div className="flex items-center gap-2">
                          <SafeImage
                            src={shop.logoUrl}
                            alt={shop.name}
                            className="h-8 w-8 rounded-lg object-cover"
                            fallbackClassName="h-8 w-8 rounded-lg bg-blue-100"
                            fallbackIcon={<Store className="h-4 w-4 text-blue-600" />}
                          />
                          {shop.name}
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="flex items-center gap-2 text-slate-600">
                          <MapPin className="h-4 w-4" />
                          {shop.address || "—"}
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap items-center gap-1.5">
                          {shop.published ? (
                            <Badge className="bg-emerald-100 text-emerald-700 hover:bg-emerald-100">
                              <Globe className="h-3 w-3 mr-1" />Published
                            </Badge>
                          ) : (
                            <Badge variant="secondary" className="text-slate-600">Draft</Badge>
                          )}
                          {/* 37-21 (UI-SPEC K4 notices): from hoursSource, never the text. No source at all
                              = created after V80 without hours = NO_HOURS, closed to orders (D-15; #916 r1 f3). */}
                          {!shop.hoursSource && (
                            <Badge variant="outline" className="border-amber-300 text-amber-800">
                              <Clock className="mr-1 h-3 w-3" aria-hidden="true" />No hours: not taking orders
                            </Badge>
                          )}
                          {shop.hoursSource === "LEGACY_ALWAYS_OPEN" && (
                            <Badge variant="outline" className="border-amber-300 text-amber-800">
                              <Clock className="mr-1 h-3 w-3" aria-hidden="true" />Hours not set
                            </Badge>
                          )}
                          {shop.hoursSource === "NEEDS_REVIEW" && (
                            <Badge variant="outline" className="border-amber-300 text-amber-800">
                              <Clock className="mr-1 h-3 w-3" aria-hidden="true" />Check hours
                            </Badge>
                          )}
                          {/* 37-21 (UI-SPEC K3 "Shops page"): the pause, from the same fields the kitchen reads. */}
                          {(() => {
                            const pause = readPauseView(shop, listedAt)
                            if (pause.kind === "none") return null
                            return (
                              <Badge variant="outline" className="text-slate-900">
                                <PauseCircle className="mr-1 h-3 w-3" aria-hidden="true" />
                                {pause.kind === "timed" ? `Paused until ${formatUkTime(pause.until) ?? ""}` : "Paused"}
                              </Badge>
                            )
                          })()}
                        </div>
                      </TableCell>
                      <TableCell className="text-slate-600">
                        <div className="flex items-center gap-2">
                          <Calendar className="h-4 w-4" />
                          {formatDistanceToNow(new Date(shop.createdAt), {
                            addSuffix: true,
                          })}
                        </div>
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-2">
                          <IconButton
                            onClick={() => openEditDialog(shop)}
                            label={`Edit shop ${shop.name}`}
                            icon={<Pencil className="h-4 w-4" />}
                          />
                          <IconButton
                            onClick={() => openDeleteDialog(shop)}
                            className="text-red-600 hover:bg-red-50 hover:text-red-700"
                            label={`Delete shop ${shop.name}`}
                            icon={<Trash2 className="h-4 w-4" />}
                          />
                        </div>
                      </TableCell>
                    </m.tr>
                  ))}
                </TableBody>
              </Table>
            )}
            <Pagination
              currentPage={currentPage}
              totalPages={totalPages}
              totalElements={totalElements}
              pageSize={PAGE_SIZE}
              onPageChange={setCurrentPage}
            />
          </CardContent>
        </Card>
      </m.div>

      {/* Create/Edit Dialog */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent
          className="max-w-2xl max-h-[85vh] overflow-y-auto"
          onOpenAutoFocus={(event) => {
            if (!focusHoursOnOpen) return
            // Opened from an hours notice: start at the hours, not the first field.
            event.preventDefault()
            setFocusHoursOnOpen(false)
            const fieldset = document.getElementById("opening-hours")
            fieldset?.scrollIntoView({ block: "start" })
            fieldset?.focus()
          }}
        >
          <DialogHeader>
            <DialogTitle>
              {editingShop ? "Edit Shop" : "Create New Shop"}
            </DialogTitle>
            <DialogDescription>
              {editingShop
                ? "Update the shop details below."
                : "Add a new shop to your system."}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={submitForm} className="space-y-6">
            {/* Basic Info */}
            <div className="space-y-3">
              <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Basic Info</h4>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-1.5">
                  <Label htmlFor="name">Shop Name *</Label>
                  <Input id="name" placeholder="e.g., Jollof Express" {...register("name")} />
                  {errors.name && <p className="text-xs text-red-600">{errors.name.message}</p>}
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="address">Address</Label>
                  <Input id="address" placeholder="e.g., 123 High St, London" {...register("address")} />
                </div>
              </div>
              {editingShop && (
                <div className="space-y-1.5">
                  {/* 37-48 (UXT-028, UI-SPEC G2): read-only until changed on purpose. */}
                  <p className="text-sm font-medium leading-none">Web address</p>
                  <div className="flex flex-col items-start gap-1 sm:flex-row sm:items-center sm:gap-3">
                    <span className="break-all font-mono text-sm text-slate-900">/shop/{editingShop.slug}</span>
                    {!slugEditing && (
                      <Button
                        type="button"
                        variant="link"
                        className="h-auto p-0"
                        onClick={() => setSlugEditing(true)}
                      >
                        Change web address
                      </Button>
                    )}
                  </div>
                  {slugEditing && (
                    <div className="space-y-1.5 pt-1">
                      <Label htmlFor="slug">New web address</Label>
                      <Input
                        id="slug"
                        value={slugDraft}
                        maxLength={100}
                        autoComplete="off"
                        spellCheck={false}
                        aria-invalid={slugError ? true : undefined}
                        aria-describedby={slugError ? "slug-warning slug-error" : "slug-warning"}
                        onChange={(e) => {
                          setSlugDraft(slugifyAsTyped(e.target.value))
                          setSlugTouched(true)
                          setSlugError(null)
                        }}
                        className="font-mono"
                      />
                      <p id="slug-warning" className="text-sm text-amber-800">
                        Changing the web address breaks links customers have saved or shared. Old links won&apos;t redirect.
                      </p>
                    </div>
                  )}
                  {slugError && (
                    <p id="slug-error" role="alert" className="text-sm text-red-600">
                      {slugError}
                    </p>
                  )}
                </div>
              )}
              <div className="space-y-1.5">
                <Label htmlFor="description">Description</Label>
                <textarea
                  id="description"
                  placeholder="Authentic Nigerian cuisine, fresh daily..."
                  {...register("description")}
                  rows={2}
                  className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
              </div>
              <div className="flex items-center gap-2">
                <input
                  type="checkbox"
                  id="published"
                  checked={published}
                  onChange={(e) => setPublished(e.target.checked)}
                  className="h-4 w-4 rounded border-slate-300"
                />
                <Label htmlFor="published" className="text-sm font-normal">Publish to storefront (visible to customers)</Label>
              </div>
            </div>

            {/* Storefront Presentation */}
            <div className="space-y-3">
              <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Storefront Presentation</h4>
              {editingShop ? (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <ImageUploader
                    currentImageUrl={editingShop.logoUrl}
                    uploadUrl={`/api/v1/shops/${editingShop.id}/logo`}
                    onUploadComplete={(url) => {
                      setEditingShop({ ...editingShop, logoUrl: url })
                      setValue("logoUrl", url)
                      fetchShops()
                    }}
                    onRemove={async () => {
                      try {
                        await apiClient.delete(`/api/v1/shops/${editingShop.id}/logo`)
                        setEditingShop({ ...editingShop, logoUrl: null })
                        setValue("logoUrl", "")
                        fetchShops()
                      } catch {
                        toast({ variant: "destructive", title: "Error", description: "Failed to remove logo" })
                      }
                    }}
                    aspectRatio="logo"
                    label="Shop Logo"
                  />
                  <ImageUploader
                    currentImageUrl={editingShop.bannerUrl}
                    uploadUrl={`/api/v1/shops/${editingShop.id}/banner`}
                    onUploadComplete={(url) => {
                      setEditingShop({ ...editingShop, bannerUrl: url })
                      setValue("bannerUrl", url)
                      fetchShops()
                    }}
                    onRemove={async () => {
                      try {
                        await apiClient.delete(`/api/v1/shops/${editingShop.id}/banner`)
                        setEditingShop({ ...editingShop, bannerUrl: null })
                        setValue("bannerUrl", "")
                        fetchShops()
                      } catch {
                        toast({ variant: "destructive", title: "Error", description: "Failed to remove banner" })
                      }
                    }}
                    aspectRatio="banner"
                    label="Banner Image"
                  />
                </div>
              ) : (
                <div className="flex items-center gap-2 rounded-md border border-dashed border-slate-300 bg-slate-50 px-3 py-3 text-sm text-slate-500">
                  <ImageIcon className="h-4 w-4" />
                  <span>Save the shop first, then add logo and banner images</span>
                </div>
              )}
              {/* Hidden inputs for form submission */}
              <input type="hidden" {...register("logoUrl")} />
              <input type="hidden" {...register("bannerUrl")} />
              <div className="space-y-1.5">
                <Label htmlFor="tags">Tags (comma-separated)</Label>
                <Input id="tags" placeholder="Nigerian, West African, Halal, Vegan options" {...register("tags")} />
              </div>
            </div>

            {/* Contact & Delivery */}
            <div className="space-y-3">
              <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Contact & Delivery</h4>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-1.5">
                  <Label htmlFor="phone">Phone</Label>
                  <Input id="phone" placeholder="020 1234 5678" {...register("phone")} />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="email">Email</Label>
                  <Input id="email" type="email" placeholder="shop@example.com" {...register("email")} />
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-1.5">
                  <Label htmlFor="deliveryInfo">Delivery Info</Label>
                  <Input id="deliveryInfo" placeholder="Free delivery over £30" {...register("deliveryInfo")} />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="minimumOrderPounds">Minimum Order (£)</Label>
                  <Input id="minimumOrderPounds" type="number" step="0.01" min="0" placeholder="0.00" {...register("minimumOrderPounds")} />
                </div>
              </div>
            </div>

            {/* Opening hours (37-21, UI-SPEC K4): structured, what the server enforces. */}
            <OpeningScheduleEditor
              key={`${editingShop?.id ?? "new"}-${editorSession}`}
              ref={scheduleEditorRef}
              shop={editingShop}
              onChange={setScheduleDraft}
            />

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setDialogOpen(false)} disabled={submitting}>
                Cancel
              </Button>
              <Button type="submit" disabled={submitting}>
                {submitting ? (editingShop ? "Updating..." : "Creating...") : (editingShop ? "Update Shop" : "Create Shop")}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation Dialog */}
      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Shop</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete{" "}
              <span className="font-semibold">{deletingShop?.name}</span>? This
              action cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setDeleteDialogOpen(false)}
              disabled={submitting}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleDelete}
              disabled={submitting}
            >
              {submitting ? "Deleting..." : "Delete Shop"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
