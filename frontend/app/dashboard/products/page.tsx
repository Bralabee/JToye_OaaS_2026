"use client"

import { useEffect, useRef, useState, type FormEvent } from "react"
import { m } from "framer-motion"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import apiClient from "@/lib/api-client"
import { fetchAcceptedAssetOutcome } from "@/lib/media-api"
import { fetchAllMyShops } from "@/lib/shops-api"
import { describeLoadError } from "@/lib/human-error"
import { useToast } from "@/hooks/use-toast"
import { useShopContext } from "@/hooks/use-shop-context"
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
import { IngredientText } from "@/components/ui/ingredient-text"
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import Link from "next/link"
import { Package, Plus, Pencil, Trash2, AlertCircle, Search, FileText, Star, Eye, EyeOff, ImageIcon, Sparkles, Check, Upload, Copy as CopyIcon, UtensilsCrossed } from "lucide-react"
import { CopyToShopDialog } from "@/components/dashboard/products/copy-to-shop-dialog"
import { SeedCulinaryPackDialog } from "@/components/dashboard/products/seed-culinary-pack-dialog"
import { ImageUploader, type AiSuggestions } from "@/components/ui/image-uploader"
import { SafeImage } from "@/components/ui/safe-image"
import { Pagination } from "@/components/ui/pagination"
import type { Product, CreateProductRequest, Shop, ProductAllergenWarning, VatRate, LabelFormat } from "@/types/api"
import {
  ALLERGENS,
  hasAllergen,
  toggleAllergen,
  getAllergenNames,
} from "@/types/api"
import {
  INGREDIENTS_EMPHASIS_HELP_COPY,
  KEEP_AS_IS_COPY,
  MAY_CONTAIN_HELP_COPY,
  NO_ALLERGENS_DECLARED_COPY,
  tickAllergenCopy,
  undeclaredIngredientCopy,
  undeclaredIngredientVendorCopy,
  vendorSaveWarningCopy,
} from "@/lib/allergen-copy"

// The form schema lives in its own module so it can be unit-tested — a Next
// App Router page.tsx may not export non-route symbols (A11Y-8 / A11Y-11).
import {
  mayContainMaskSchema,
  parseDurabilityInputs,
  productSchema,
  toPricePennies,
  type DurabilityInputs,
  type ProductFormData,
} from "./product-form-schema"

function AiSuggestionRow({ label, value, onAccept }: { label: string; value: string; onAccept: () => void }) {
  return (
    <div className="flex items-start gap-2 bg-white rounded-md px-2 py-1.5 border border-violet-100">
      <div className="flex-1 min-w-0">
        <span className="text-xs font-medium text-violet-500 uppercase">{label}</span>
        <p className="text-xs text-slate-700 line-clamp-2">{value}</p>
      </div>
      <button
        type="button"
        onClick={onAccept}
        className="flex-shrink-0 mt-1 inline-flex items-center gap-1 rounded bg-violet-600 hover:bg-violet-700 text-white px-2 py-0.5 text-xs font-medium transition-colors"
      >
        <Check className="h-2.5 w-2.5" />
        Apply
      </button>
    </div>
  )
}

const PAGE_SIZE = 20

/**
 * UXT-046 (#815, 37-14, UI-SPEC § B6): the four VAT liabilities core-java accepts, in the
 * UI-SPEC's order and wording. STANDARD first because it is the server's default on create.
 */
const VAT_RATE_OPTIONS: ReadonlyArray<{ value: VatRate; label: string }> = [
  { value: "STANDARD", label: "Standard rate (20%)" },
  { value: "REDUCED", label: "Reduced rate (5%)" },
  { value: "ZERO", label: "Zero rate (0%)" },
  { value: "EXEMPT", label: "Exempt" },
]

/**
 * UI-SPEC § Copywriting B4-B6 "VAT helper". Signed off by the owner on 2026-10-16 (UAT test 6
 * ruling: accepted as written). It must not read as tax advice, so it names the rule of thumb,
 * says there are exceptions and points at HMRC and an accountant rather than deciding for the
 * vendor.
 */
const VAT_RATE_HELP_COPY =
  "The rate that applies to this item when you sell it. Hot takeaway food is usually standard-rated, and much cold food is zero-rated, but there are exceptions. If you're not sure, check HMRC VAT Notice 709/1 or ask your accountant."

/**
 * D-09: the catalogue name for a warning's bit. The bit is the fact; the server's name is the
 * fallback for a bit this build's catalogue does not know.
 */
function warningAllergenName(warning: ProductAllergenWarning): string {
  return ALLERGENS.find((a) => a.bit === warning.allergenBit)?.name ?? warning.allergen
}

/**
 * D-17: today's date in the UK, ISO-shaped (en-CA formats as YYYY-MM-DD), whatever zone the
 * browser is in. A label printed at 00:30 BST is dated the UK day, not the UTC one; the server
 * judges "future" on the same Europe/London date (31.1-14 ClockConfig).
 */
function ukTodayIso(): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Europe/London",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date())
}

function blobText(blob: Blob): Promise<string> {
  if (typeof blob.text === "function") return blob.text()
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(blob)
  })
}

/**
 * The status and RFC 7807 detail of a failed request. A blob request (the label PDF) delivers
 * its error body as a Blob too, so the problem JSON is read out of it.
 */
async function readProblem(
  error: unknown
): Promise<{ status?: number; detail?: string; type?: string; missingFields?: string[] }> {
  const response = (error as { response?: { status?: number; data?: unknown } } | null)?.response
  if (!response) return {}
  let body: unknown = response.data
  if (typeof Blob !== "undefined" && body instanceof Blob) {
    try {
      body = JSON.parse(await blobText(body))
    } catch {
      body = undefined
    }
  }
  const problem = body as { detail?: unknown; type?: unknown; missingFields?: unknown } | undefined
  return {
    status: response.status,
    detail: typeof problem?.detail === "string" ? problem.detail : undefined,
    type: typeof problem?.type === "string" ? problem.type : undefined,
    missingFields: Array.isArray(problem?.missingFields)
      ? problem.missingFields.filter((f): f is string => typeof f === "string")
      : undefined,
  }
}

/** #959: shown under Shop Assignment when a new product is saved without a shop. */
const SHOP_REQUIRED_COPY =
  "Choose which shop sells this product. A product with no shop does not appear on any storefront."

/** #960: shown if the server's incomplete-label answer carries no detail. */
const LABEL_INCOMPLETE_COPY =
  "This allergen label cannot be printed yet. Add a shelf life and choose use-by or best-before on the product."

const LABEL_DATE_REQUIRED_COPY = "Enter the date the food was made."
const LABEL_DATE_FUTURE_COPY = "The production date cannot be in the future."
const LABEL_DATE_REFUSED_COPY = "This production date cannot be used for a label."

/** The allergens a product's ingredients name but its declaration omits, once each. */
function undeclaredAllergenNames(warnings: ProductAllergenWarning[] | null | undefined): string[] {
  return Array.from(new Set((warnings ?? []).map(warningAllergenName)))
}

export default function ProductsPage() {
  const [products, setProducts] = useState<Product[]>([])
  const [loading, setLoading] = useState(true)
  const [searchQuery, setSearchQuery] = useState("")
  const [currentPage, setCurrentPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [editingProduct, setEditingProduct] = useState<Product | null>(null)
  const [deletingProduct, setDeletingProduct] = useState<Product | null>(null)
  const [allergenMask, setAllergenMask] = useState(0)
  // D-09 (#787): the warnings the LAST save returned. The save succeeded; these ask the vendor
  // to tick the named allergen or keep the declaration as it is. Nothing here is persisted.
  const [allergenWarnings, setAllergenWarnings] = useState<ProductAllergenWarning[]>([])
  const allergenWarningRef = useRef<HTMLDivElement>(null)
  // D-16 (#861): "may contain" (cross-contact), its own mask. null = not recorded (an untouched
  // fieldset stays null), 0 = the vendor recorded no risk. Never derived from allergenMask and
  // never written into it.
  const [mayContainMask, setMayContainMask] = useState<number | null>(null)
  // UXT-046: STANDARD on a new product (the server default, made visible). null on an edit whose
  // response carried no rate: nothing is sent then, and the server keeps the stored rate.
  const [vatRate, setVatRate] = useState<VatRate | null>("STANDARD")
  const [available, setAvailable] = useState(true)
  // D-17 (#861): the label dialog. The production date is asked for every time; nothing is
  // remembered between labels (no batch record, CONTEXT deferred).
  const [labelProduct, setLabelProduct] = useState<Product | null>(null)
  const [labelDate, setLabelDate] = useState("")
  const [labelMaxDate, setLabelMaxDate] = useState("")
  const [labelFormat, setLabelFormat] = useState<LabelFormat>("STANDARD_100X60MM")
  const [labelError, setLabelError] = useState<string | null>(null)
  // #960: the label endpoint's plain-words 422 (errors/incomplete-label-data): what to add on the product.
  const [labelIncomplete, setLabelIncomplete] = useState<string | null>(null)
  const [labelDownloading, setLabelDownloading] = useState(false)
  const [seedDialogOpen, setSeedDialogOpen] = useState(false)
  const [featured, setFeatured] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [aiSuggestions, setAiSuggestions] = useState<AiSuggestions | null>(null)
  const [shops, setShops] = useState<Shop[]>([])
  const [selectedShopId, setSelectedShopId] = useState<string>("")
  // #959: a new product must name its shop. Set when a create is submitted without one.
  const [shopError, setShopError] = useState(false)
  // #960: the two inputs the allergen label is dated from. Held as typed; parsed at save.
  const [shelfLifeText, setShelfLifeText] = useState("")
  const [durabilityType, setDurabilityType] = useState("")
  const [durabilityError, setDurabilityError] = useState<string | null>(null)
  const [trackInventory, setTrackInventory] = useState(false)
  const [quantityInStock, setQuantityInStock] = useState<number>(0)
  // F2 (FEB-1): a 429/network failure must render an error panel, never the
  // "No products yet" empty state — the catch blocks below deliberately do
  // NOT reset `products` to `[]`, so this is keyed off list length nowhere.
  const [loadFailed, setLoadFailed] = useState(false)
  const [loadErrorMessage, setLoadErrorMessage] = useState("")
  // UXT-026 (37-14): one copy at a time. `key` remounts the dialog so each opening starts clean.
  const [copyRequest, setCopyRequest] = useState<{
    key: number
    source: { id: string; name: string }
    productIds?: string[]
    count: number
  } | null>(null)
  // The selected shop's whole-menu size, from the unsearched list (a search narrows the rows on
  // screen, never what the header copy sends).
  const [menuCount, setMenuCount] = useState(0)
  const { toast } = useToast()
  // VSA-03: the persisted switcher selection. `null` = All shops (no narrow).
  const { contextShopId } = useShopContext()

  // WR-04 (#280): the shop narrow now happens SERVER-side via `?shopId=`
  // (ProductService.getProductsByShop, gated by the 23-03 grant check), so the
  // rendered rows, the count and the pager all describe the same result set.
  // The previous client-side `products.filter(...)` ran over a single already
  // paginated page, which produced a count that was really "matches on this
  // page", a false "No products in this shop" when a shop's rows began on page
  // 2, and rows past page 1 that could not be reached at all.
  const contextShopName = contextShopId
    ? shops.find((s) => s.id === contextShopId)?.name
    : undefined

  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
    setValue,
  } = useForm<ProductFormData>({
    resolver: zodResolver(productSchema),
  })

  // WR-04 (#280): tracks the previous switcher selection so a shop change can
  // send the pager back to page 0 in the SAME effect pass. Without the reset, a
  // vendor sitting on page 3 who switches to a shop with one page of products
  // would be left staring at an out-of-range empty page.
  const prevShopRef = useRef<string | null | undefined>(undefined)

  const fetchShops = async () => {
    try {
      // #485 (call site :158): was a single `/api/v1/shops?size=100`, whose first
      // page was treated as the whole list. Past 100 shops the tail could not be
      // chosen in the create-product "Shop assignment" select, so those shops were
      // unaddressable — and the switcher's shop could not be named in the header.
      setShops(await fetchAllMyShops())
    } catch {
      // Shops are optional — fail silently
    }
  }

  const fetchProducts = async () => {
    try {
      setLoading(true)
      // VSA-03 / WR-04: outside the All-shops context the list narrows SERVER-side.
      const shopScope = contextShopId ? `&shopId=${contextShopId}` : ""
      const response = await apiClient.get(
        `/api/v1/products?page=${currentPage}&size=${PAGE_SIZE}&sort=createdAt,desc${shopScope}`
      )
      setProducts(response.data.content || [])
      setTotalPages(response.data.totalPages || 0)
      setTotalElements(response.data.totalElements || 0)
      setMenuCount(response.data.totalElements || 0)
      setLoadFailed(false)
    } catch (error: unknown) {
      // A11Y-2 (#688): `error.message` on an axios error is its own transport
      // string ("Request failed with status code 500") — route the toast
      // through the shared classifier so it says what the panel says.
      const { message } = describeLoadError(error, "Failed to load products")
      toast({
        variant: "destructive",
        title: "Error loading products",
        description: message,
      })
      // F2 (FEB-1): `products` is deliberately left untouched above — a false
      // "No products yet" empty state is worse than briefly stale rows.
      setLoadFailed(true)
      setLoadErrorMessage(message)
    } finally {
      setLoading(false)
    }
  }

  const searchProducts = async (query: string) => {
    try {
      // WR-04 (#280): search obeys the switcher too. Without this the narrow
      // would silently stop applying the moment the vendor typed two characters,
      // because this screen swaps to /search at searchQuery.length >= 2.
      const shopScope = contextShopId ? `&shopId=${contextShopId}` : ""
      const response = await apiClient.get(
        `/api/v1/products/search?q=${encodeURIComponent(query)}${shopScope}`
      )
      setProducts(response.data || [])
      setTotalPages(1)
      setTotalElements(response.data?.length || 0)
      setLoadFailed(false)
    } catch (error: unknown) {
      // F2 (FEB-1): this catch used to be fully silent, leaving whatever was on
      // screen before the search with no indication the search itself failed.
      setLoadFailed(true)
      setLoadErrorMessage(describeLoadError(error).message)
    }
  }

  useEffect(() => {
    // A switcher change must refetch so the list narrows live (no reload).
    const shopChanged =
      prevShopRef.current !== undefined && prevShopRef.current !== contextShopId
    prevShopRef.current = contextShopId
    if (shopChanged && currentPage !== 0) {
      // Re-enters this effect with currentPage 0; deliberately does NOT fetch
      // here, so the shop change costs exactly one request, not two.
      setCurrentPage(0)
      return
    }
    fetchProducts()
    fetchShops()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentPage, contextShopId])

  useEffect(() => {
    if (searchQuery.length >= 2) {
      const timer = setTimeout(() => searchProducts(searchQuery), 300)
      return () => clearTimeout(timer)
    } else if (searchQuery.length === 0) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch/refresh-on-change effect; the traced sync loading-state prefix is the loading-UI contract. One extra render accepted
      fetchProducts()
    }
    // contextShopId is a dependency so switching shop mid-search re-runs the
    // search against the new shop rather than leaving stale rows on screen.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchQuery, contextShopId])

  const retryLoad = () => {
    if (searchQuery.length >= 2) searchProducts(searchQuery)
    else fetchProducts()
  }

  const openCreateDialog = () => {
    setEditingProduct(null)
    reset({ sku: "", title: "", ingredientsText: "", pricePounds: "" })
    setAllergenMask(0)
    setMayContainMask(null)
    setVatRate("STANDARD")
    setAvailable(true)
    setFeatured(false)
    // D-08: outside the All-shops context a create is a single-shop write —
    // default the assignment to the selected shop (the select is pinned below).
    // #959: outside a shop context there is no "All Shops" any more — a product belongs to one shop.
    // A vendor with exactly one shop has nothing to choose, so it is pre-selected; with several, the
    // select starts on "Choose a shop…" and the form refuses to save until one is picked.
    setSelectedShopId(contextShopId ?? (shops.length === 1 ? shops[0].id : ""))
    setShopError(false)
    setShelfLifeText("")
    setDurabilityType("")
    setDurabilityError(null)
    setTrackInventory(false)
    setQuantityInStock(0)
    setAiSuggestions(null)
    setAllergenWarnings([])
    setDialogOpen(true)
  }

  const openEditDialog = (product: Product) => {
    setEditingProduct(product)
    setValue("sku", product.sku)
    setValue("title", product.title)
    setValue("ingredientsText", product.ingredientsText)
    setValue("pricePounds", ((product.pricePennies || 0) / 100).toFixed(2))
    setAllergenMask(product.allergenMask)
    setMayContainMask(product.mayContainMask ?? null)
    setVatRate(product.vatRate ?? null)
    setAvailable(product.available ?? true)
    setFeatured(product.featured ?? false)
    setSelectedShopId(product.shopId || "")
    setShopError(false)
    setShelfLifeText(product.shelfLifeDays != null ? String(product.shelfLifeDays) : "")
    setDurabilityType(product.durabilityType ?? "")
    setDurabilityError(null)
    setTrackInventory(product.quantityInStock != null)
    setQuantityInStock(product.quantityInStock ?? 0)
    setAiSuggestions(null)
    setAllergenWarnings([])
    setDialogOpen(true)
  }

  // The form dialog closing, by any route, drops a pending save-time warning with it.
  const onFormDialogOpenChange = (open: boolean) => {
    setDialogOpen(open)
    if (!open) setAllergenWarnings([])
  }

  const closeFormAfterSave = () => {
    setAllergenWarnings([])
    setDialogOpen(false)
    reset()
    setAllergenMask(0)
    setMayContainMask(null)
  }

  // D-09: move focus to the warning when a save returns one, so a keyboard or screen-reader
  // user lands on the decision rather than on a form that still looks finished.
  useEffect(() => {
    if (allergenWarnings.length > 0) allergenWarningRef.current?.focus()
  }, [allergenWarnings])

  // UXT-026 (UI-SPEC § B6 "Copy to shop"): offered only where there is a source shop and somewhere
  // else to copy to. The server still checks SHOP_MANAGER on both shops.
  const shopNameOf = (id: string) => shops.find((s) => s.id === id)?.name ?? "this shop"
  const hasOtherShop = (sourceId: string) => shops.some((s) => s.id !== sourceId)

  const openCopyForProduct = (product: Product) => {
    if (!product.shopId) return
    setCopyRequest({
      key: Date.now(),
      source: { id: product.shopId, name: shopNameOf(product.shopId) },
      productIds: [product.id],
      count: 1,
    })
  }

  const openCopyForMenu = () => {
    if (!contextShopId) return
    setCopyRequest({
      key: Date.now(),
      source: { id: contextShopId, name: shopNameOf(contextShopId) },
      count: menuCount,
    })
  }

  const openDeleteDialog = (product: Product) => {
    setDeletingProduct(product)
    setDeleteDialogOpen(true)
  }

  const toggleAllergenBit = (bit: number) => {
    setAllergenMask(toggleAllergen(allergenMask, bit))
  }

  // D-16: the first tick turns "not recorded" into a recorded mask; unticking the last box
  // leaves 0 (recorded: no risk), not null.
  const toggleMayContainBit = (bit: number) => {
    setMayContainMask(toggleAllergen(mayContainMask ?? 0, bit))
  }

  /**
   * Create or update. `mask` is passed explicitly by the "Tick …" action, which sets a bit and
   * saves in the same gesture: reading `allergenMask` state there would send the value from
   * before the tick.
   */
  const saveProduct = async (data: ProductFormData, mask: number = allergenMask) => {
    // #959 (SW-ADE-01): a new product with no shop is on no storefront and cannot be ordered, so it is
    // refused here, in words, instead of being saved and never seen. (The server refuses it too:
    // 400 errors/shop-required.) An edit of an older shop-less product may still be saved as it is;
    // the form tells the vendor it is off the menu and offers the choice.
    if (!editingProduct && !selectedShopId) {
      setShopError(true)
      document.getElementById("shopId")?.focus()
      return
    }
    // #960: shelf life and use-by / best-before go together, in range, or are both left blank. That rule
    // judges what the vendor TYPED: on an edit where neither input was touched, the stored values (which
    // may be a lone half, or from before the bound existed) are not re-validated and not re-sent, so the
    // server keeps them and an unrelated edit, like a new price, still saves (review round 1, F2).
    const durabilityTouched =
      !editingProduct ||
      shelfLifeText !== (editingProduct.shelfLifeDays != null ? String(editingProduct.shelfLifeDays) : "") ||
      durabilityType !== (editingProduct.durabilityType ?? "")
    const durability: DurabilityInputs = durabilityTouched
      ? parseDurabilityInputs(shelfLifeText, durabilityType)
      : { ok: true }
    if (!durability.ok) {
      setDurabilityError(durability.error)
      document.getElementById(shelfLifeText.trim() === "" ? "shelfLifeDays" : "durabilityType")?.focus()
      return
    }
    const clearsStoredDurability =
      durabilityTouched &&
      durability.ok &&
      durability.shelfLifeDays === undefined &&
      durability.durabilityType === undefined &&
      editingProduct != null &&
      (editingProduct.shelfLifeDays != null || editingProduct.durabilityType != null)
    try {
      setSubmitting(true)

      // Read storefront fields from form elements (not zod-validated)
      const form = document.getElementById("product-form") as HTMLFormElement
      const descEl = form?.querySelector<HTMLTextAreaElement>("[name=description]")
      const imageUrlEl = form?.querySelector<HTMLInputElement>("[name=imageUrl]")
      const categoryEl = form?.querySelector<HTMLInputElement>("[name=category]")
      const displayOrderEl = form?.querySelector<HTMLInputElement>("[name=displayOrder]")
      const prepTimeEl = form?.querySelector<HTMLInputElement>("[name=preparationTimeMinutes]")
      const dietaryTagsEl = form?.querySelector<HTMLInputElement>("[name=dietaryTags]")

      const payload: CreateProductRequest = {
        sku: data.sku,
        title: data.title,
        ingredientsText: data.ingredientsText,
        allergenMask: mask,
        // D-16: sent as held — null stays null (not recorded), 0 stays 0.
        mayContainMask: mayContainMaskSchema.parse(mayContainMask),
        pricePennies: toPricePennies(data.pricePounds),
        // UXT-046: the chosen rate; undefined (dropped from the JSON) only when an edit did not
        // learn the stored rate, so the PUT keeps it rather than overwriting it with a guess.
        vatRate: vatRate ?? undefined,
        available,
        featured,
        description: descEl?.value || undefined,
        imageUrl: imageUrlEl?.value || undefined,
        category: categoryEl?.value || undefined,
        displayOrder: displayOrderEl?.value ? parseInt(displayOrderEl.value) : undefined,
        preparationTimeMinutes: prepTimeEl?.value ? parseInt(prepTimeEl.value) : undefined,
        dietaryTags: dietaryTagsEl?.value || undefined,
        shopId: selectedShopId || undefined,
        // #960: omitted when blank, so an edit keeps what is stored and a create stores nothing.
        shelfLifeDays: durability.shelfLifeDays,
        durabilityType: durability.durabilityType,
        // Review F3: emptying inputs that held values is a stated clear. Omitting both would leave the
        // server keeping them while the toast said "updated".
        clearDurability: clearsStoredDurability ? true : undefined,
        // UXT-016 (37-39): the tracking intent is stated, not inferred from a null. The server keeps the
        // stored stock when trackInventory is absent, so without it unticking would no longer stop tracking.
        trackInventory,
        quantityInStock: trackInventory ? quantityInStock : null,
      }

      let saved: Product | undefined
      if (editingProduct) {
        // Update existing product
        saved = (await apiClient.put(`/api/v1/products/${editingProduct.id}`, payload))?.data
        toast({
          title: "Product updated",
          description: `${data.title} has been updated successfully.`,
        })
      } else {
        // Create new product
        saved = (await apiClient.post("/api/v1/products", payload))?.data
        toast({
          title: "Product created",
          description: `${data.title} has been created successfully.`,
        })
      }

      if (currentPage === 0) fetchProducts()
      else setCurrentPage(0)

      // D-09 (#787): the save SUCCEEDED, but the ingredients name an allergen the declaration
      // omits. Keep the form open on the product just saved (so "Tick …" updates it rather than
      // creating a second one) and ask the vendor to decide.
      const warnings = saved?.allergenWarnings ?? []
      if (saved && warnings.length > 0) {
        setEditingProduct(saved)
        setAllergenWarnings(warnings)
        return
      }

      closeFormAfterSave()
    } catch (error: unknown) {
      // The server's RFC 7807 detail is written for the vendor (e.g. errors/shop-required); the axios
      // message is only "Request failed with status code 400".
      const problem = await readProblem(error)
      const errorMessage =
        problem.detail ??
        (error instanceof Error ? error.message : `Failed to ${editingProduct ? "update" : "create"} product`)
      toast({
        variant: "destructive",
        title: editingProduct ? "Error updating product" : "Error creating product",
        description: errorMessage,
      })
    } finally {
      setSubmitting(false)
    }
  }

  const onSubmit = (data: ProductFormData) => saveProduct(data)

  // D-09 "Tick Milk": an explicit vendor action — the warning arriving never ticks anything
  // (T-31.1-69). Sets the bit on the declaration and saves the same product again.
  const tickAllergenAndResave = (bit: number) => {
    const next = allergenMask | (1 << bit)
    setAllergenMask(next)
    void handleSubmit((data) => saveProduct(data, next))()
  }

  // D-09 "Keep as it is": the product is already saved; the storefront keeps showing the
  // disagreement until the ingredients and the declaration agree. Nothing is recorded.
  const keepDeclarationAsIs = () => {
    closeFormAfterSave()
  }

  const openLabelDialog = (product: Product) => {
    const today = ukTodayIso()
    setLabelDate(today)
    setLabelMaxDate(today)
    setLabelFormat("STANDARD_100X60MM")
    setLabelError(null)
    setLabelIncomplete(null)
    setLabelProduct(product)
  }

  const closeLabelDialog = () => {
    setLabelProduct(null)
    setLabelError(null)
    setLabelIncomplete(null)
  }

  // #960: the label dialog's way out of the dead end — straight to the product's edit form, where the
  // shelf life and use-by / best-before inputs are.
  const editProductFromLabelDialog = () => {
    const target = labelProduct
    closeLabelDialog()
    if (target) openEditDialog(target)
  }

  const downloadLabel = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!labelProduct) return
    if (!labelDate) {
      setLabelError(LABEL_DATE_REQUIRED_COPY)
      return
    }
    // ISO dates compare correctly as strings.
    if (labelDate > labelMaxDate) {
      setLabelError(LABEL_DATE_FUTURE_COPY)
      return
    }
    setLabelError(null)
    setLabelIncomplete(null)
    setLabelDownloading(true)
    try {
      const formatParam = labelFormat === "THERMAL_62MM" ? "&format=THERMAL_62MM" : ""
      const res = await apiClient.get(
        `/api/v1/products/${labelProduct.id}/label?productionDate=${encodeURIComponent(labelDate)}${formatParam}`,
        { responseType: "blob" }
      )
      const url = URL.createObjectURL(res.data)
      const a = document.createElement("a")
      a.href = url
      a.download =
        labelFormat === "THERMAL_62MM"
          ? `label-62mm-${labelProduct.sku}.pdf`
          : `label-${labelProduct.sku}.pdf`
      a.click()
      URL.revokeObjectURL(url)
      closeLabelDialog()
    } catch (error: unknown) {
      const problem = await readProblem(error)
      if (problem.status === 422 && problem.type?.endsWith("/incomplete-label-data")) {
        // #960: the product lacks what the label is made from. The server's detail says what to add
        // in plain words; it is not about the date, so it is not shown under the date field.
        setLabelIncomplete(problem.detail ?? LABEL_INCOMPLETE_COPY)
      } else if (problem.status === 422) {
        // 31.1-14: errors/invalid-production-date, field productionDate — a future date, or
        // one whose use-by has already passed. Said under the field it is about.
        setLabelError(problem.detail ?? LABEL_DATE_REFUSED_COPY)
      } else {
        toast({ variant: "destructive", title: "Error", description: "Failed to download label" })
      }
    } finally {
      setLabelDownloading(false)
    }
  }

  const handleDelete = async () => {
    if (!deletingProduct) return

    try {
      setSubmitting(true)
      await apiClient.delete(`/api/v1/products/${deletingProduct.id}`)
      toast({
        title: "Product deleted",
        description: `${deletingProduct.title} has been deleted successfully.`,
      })
      setDeleteDialogOpen(false)
      setDeletingProduct(null)
      if (currentPage === 0) fetchProducts()
      else setCurrentPage(0)
    } catch (error: unknown) {
      // SW-ADE-09 (swarm epic #964): a product an order still references is refused with a typed
      // 409 (errors/in-use). Say why and what to do instead, rather than the raw axios string.
      const res = (error as { response?: { status?: number; data?: { type?: unknown } } } | null)
        ?.response
      const inUse =
        res?.status === 409 &&
        typeof res.data?.type === "string" &&
        res.data.type.endsWith("/errors/in-use")
      toast(
        inUse
          ? {
              variant: "destructive",
              title: `${deletingProduct.title} can't be deleted`,
              description:
                "It is on past orders, so it stays in your records. To take it off your menu, edit it and untick Available.",
            }
          : {
              variant: "destructive",
              title: "Error deleting product",
              description: describeLoadError(error, "Couldn't delete the product. Please try again.")
                .message,
            }
      )
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
    // Adding a max-width here would also change when the table's scroll region
    // below overflows, and that region carries the #685 keyboard-reachability
    // fix. Do not "tidy" this by capping it.
    <div data-width-tier="index" className="space-y-6">
      {/* Header */}
      <m.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        // FEB-2: this was a no-wrap `flex justify-between` — the h1 plus two
        // min-width buttons exceed a 390px viewport and clip "Add Product".
        // `flex-wrap` lets the button row drop below the title on narrow
        // screens; `gap-3` replaces the space `justify-between` no longer
        // supplies once the row can wrap.
        className="flex flex-wrap items-center justify-between gap-3"
      >
        <div>
          <h1 className="text-4xl font-bold text-slate-900">Products</h1>
          <p className="mt-2 text-slate-600">
            Manage your product catalog with allergen information
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {contextShopId && hasOtherShop(contextShopId) && menuCount > 0 && (
            <Button variant="outline" className="gap-2" onClick={openCopyForMenu}>
              <CopyIcon className="h-4 w-4" aria-hidden="true" />
              Copy to another shop…
            </Button>
          )}
          <Button
            variant="outline"
            className="gap-2"
            data-testid="open-seed-culinary-pack-btn"
            onClick={() => setSeedDialogOpen(true)}
          >
            <UtensilsCrossed className="h-4 w-4" aria-hidden="true" />
            Seed Cuisine Pack…
          </Button>
          <Link href="/dashboard/products/import">
            <Button variant="outline" className="gap-2">
              <Upload className="h-4 w-4" />
              Bulk Import
            </Button>
          </Link>
          <Button onClick={openCreateDialog} className="gap-2">
            <Plus className="h-4 w-4" />
            Add Product
          </Button>
        </div>
      </m.div>

      {/*
        A11Y-6 (extended in QA integration): the sections below are all
        `CardTitle` (<h3>). Without a real <h2> between the page's <h1> and
        the first of those <h3>s the outline skips a level (axe
        heading-order). `sr-only` keeps it invisible but in the a11y tree.
      */}
      <h2 className="sr-only">Product catalog</h2>

      {/* Products Table */}
      <m.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.1 }}
      >
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0">
            <div>
              <CardTitle>All Products</CardTitle>
              <CardDescription>
                {/* #688: never assert a count nothing loaded — the panel below
                    says the load failed, so the subtitle must not say "0". */}
                {loadFailed
                  ? "—"
                  : `${totalElements} product${totalElements !== 1 ? "s" : ""}${
                      contextShopId
                        ? ` in ${contextShopName || "the selected shop"}`
                        : " in total"
                    }`}
              </CardDescription>
            </div>
            <div className="relative w-[220px]">
              <Search className="absolute left-2 top-2.5 h-4 w-4 text-slate-400" />
              <Input
                placeholder="Search products..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-8"
              />
            </div>
          </CardHeader>
          <CardContent>
            {loadFailed ? (
              <LoadErrorPanel
                subject="products"
                message={loadErrorMessage}
                onRetry={retryLoad}
              />
            ) : products.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <Package className="mb-4 h-12 w-12 text-slate-300" />
                <h3 className="mb-2 text-lg font-semibold text-slate-900">
                  {contextShopId ? "No products in this shop" : "No products yet"}
                </h3>
                <p className="mb-4 text-sm text-slate-500">
                  {contextShopId
                    ? `Add a product to ${contextShopName || "this shop"}, or switch shop context to see others`
                    : "Get started by creating your first product"}
                </p>
                <Button onClick={openCreateDialog} variant="outline">
                  <Plus className="mr-2 h-4 w-4" />
                  Add Product
                </Button>
              </div>
            ) : (
              // The Table primitive's own overflow div is the named, focusable region
              // (A11Y-5, QA council 20260902-134741); the wrapper that used to sit here
              // nested a second scroll container inside it.
              <Table containerLabel="Products table, scroll horizontally for more columns">
                <TableHeader>
                  <TableRow>
                    <TableHead>SKU</TableHead>
                    <TableHead>Title</TableHead>
                    <TableHead>Category</TableHead>
                    <TableHead>Allergens</TableHead>
                    <TableHead className="text-right">Price</TableHead>
                    <TableHead className="text-center">Status</TableHead>
                    <TableHead className="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {products.map((product) => {
                    const allergenNames = getAllergenNames(product.allergenMask)
                    // Pitfall 11 / D-18: the persona's dish must never read "no allergens" here.
                    const undeclaredLine = undeclaredIngredientVendorCopy(
                      undeclaredAllergenNames(product.allergenWarnings)
                    )
                    return (
                      <m.tr
                        key={product.id}
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        className="group"
                      >
                        <TableCell className="font-mono text-sm font-medium">
                          {product.sku}
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center gap-2">
                            <SafeImage
                              src={product.imageUrl}
                              alt={product.title}
                              className="h-8 w-8 rounded-lg object-cover"
                              fallbackClassName="h-8 w-8 rounded-lg bg-blue-100"
                              fallbackIcon={<Package className="h-4 w-4 text-blue-600" />}
                            />
                            <div>
                              {/* A11Y-10 / #702: a 224-char title took the row from 72px to 189px;
                                  two lines (a title is the row's identifier), break-words so an
                                  unbreakable token wraps instead of overflowing the table. */}
                              <div className="font-medium line-clamp-2 break-words">{product.title}</div>
                              {!product.shopId && (
                                <span
                                  data-testid="shop-less-badge"
                                  className="mt-0.5 inline-flex items-center gap-1 text-xs font-medium text-amber-800"
                                >
                                  <AlertCircle className="h-3 w-3 flex-shrink-0" aria-hidden="true" />
                                  No shop: not on any storefront
                                </span>
                              )}
                              <IngredientText
                                text={product.ingredientsText}
                                className="line-clamp-1 block text-xs text-slate-500"
                              />
                            </div>
                          </div>
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center gap-1.5">
                            {product.category ? (
                              <Badge variant="outline" className="text-xs">{product.category}</Badge>
                            ) : (
                              <span className="text-xs text-muted-foreground">—</span>
                            )}
                          </div>
                        </TableCell>
                        <TableCell>
                          <div className="flex flex-wrap gap-1">
                            {allergenNames.length === 0 && !undeclaredLine ? (
                              <span className="text-sm text-muted-foreground">
                                {NO_ALLERGENS_DECLARED_COPY}
                              </span>
                            ) : (
                              <>
                                {allergenNames.map((name) => (
                                  <Badge
                                    key={name}
                                    variant="outline"
                                    className="bg-orange-50 text-orange-700 border-orange-200"
                                  >
                                    {name}
                                  </Badge>
                                ))}
                                {undeclaredLine && (
                                  <span className="inline-flex items-center gap-1 text-xs font-medium text-amber-800">
                                    <AlertCircle className="h-3 w-3 flex-shrink-0" aria-hidden="true" />
                                    {undeclaredLine}
                                  </span>
                                )}
                              </>
                            )}
                          </div>
                        </TableCell>
                        <TableCell className="text-right font-semibold">
                          {product.pricePennies != null
                            ? `£${(product.pricePennies / 100).toFixed(2)}`
                            : "—"}
                        </TableCell>
                        <TableCell className="text-center">
                          <div className="flex items-center justify-center gap-1.5">
                            {product.available ? (
                              <span title="Available"><Eye className="h-3.5 w-3.5 text-emerald-500" /></span>
                            ) : (
                              <span title="Unavailable"><EyeOff className="h-3.5 w-3.5 text-slate-300" /></span>
                            )}
                            {product.featured && (
                              <span title="Featured"><Star className="h-3.5 w-3.5 text-amber-500 fill-amber-500" /></span>
                            )}
                          </div>
                        </TableCell>
                        <TableCell className="text-right">
                          <div className="flex justify-end gap-2">
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => openLabelDialog(product)}
                              className="h-8 w-8 p-0 text-blue-600 hover:bg-blue-50 hover:text-blue-700"
                              title="Download allergen label"
                              aria-label={`Download allergen label for ${product.title}`}
                            >
                              <FileText className="h-4 w-4" />
                            </Button>
                            {product.shopId && hasOtherShop(product.shopId) && (
                              <IconButton
                                onClick={() => openCopyForProduct(product)}
                                label={`Copy ${product.title} to another shop…`}
                                icon={<CopyIcon className="h-4 w-4" />}
                              />
                            )}
                            <IconButton
                              onClick={() => openEditDialog(product)}
                              label={`Edit product ${product.title}`}
                              icon={<Pencil className="h-4 w-4" />}
                            />
                            <IconButton
                              onClick={() => openDeleteDialog(product)}
                              className="text-red-600 hover:bg-red-50 hover:text-red-700"
                              label={`Delete product ${product.title}`}
                              icon={<Trash2 className="h-4 w-4" />}
                            />
                          </div>
                        </TableCell>
                      </m.tr>
                    )
                  })}
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

      {copyRequest && (
        <CopyToShopDialog
          key={copyRequest.key}
          open
          onOpenChange={(next) => {
            if (!next) setCopyRequest(null)
          }}
          sourceShop={copyRequest.source}
          shops={shops}
          productIds={copyRequest.productIds}
          count={copyRequest.count}
          onCopied={(result) => {
            // A copy into the shop on screen (or into any shop, under All shops) adds rows here.
            if (result.created > 0) fetchProducts()
          }}
        />
      )}

      <SeedCulinaryPackDialog
        open={seedDialogOpen}
        onOpenChange={setSeedDialogOpen}
        shops={shops}
        defaultShopId={contextShopId}
        onSeeded={(result) => {
          if (result.created > 0) fetchProducts()
        }}
      />

      {/* Create/Edit Dialog */}
      <Dialog open={dialogOpen} onOpenChange={onFormDialogOpenChange}>
        <DialogContent className="max-h-[90vh] overflow-y-auto max-w-2xl">
          <DialogHeader>
            <DialogTitle>
              {editingProduct ? "Edit Product" : "Create New Product"}
            </DialogTitle>
            <DialogDescription>
              {editingProduct
                ? "Update the product details below."
                : "Add a new product to your catalog."}
            </DialogDescription>
          </DialogHeader>
          {allergenWarnings.length > 0 && (
            // D-09 (#787): the save-time warning, above the form. role=alert announces it; focus
            // is moved here by the effect above. The two actions are the vendor's decision.
            <div
              ref={allergenWarningRef}
              role="alert"
              tabIndex={-1}
              data-testid="allergen-save-warning"
              className="rounded-lg border border-amber-300 bg-amber-50 p-4 text-amber-950 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber-600 focus-visible:ring-offset-2"
            >
              <div className="flex items-start gap-2">
                <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-700" aria-hidden="true" />
                <div className="min-w-0 flex-1 space-y-3">
                  <p className="text-sm font-semibold">
                    Saved. Check the allergens you ticked.
                  </p>
                  <ul className="space-y-2">
                    {allergenWarnings.map((warning) => {
                      const name = warningAllergenName(warning)
                      return (
                        <li
                          key={warning.allergenBit}
                          className="flex flex-wrap items-center justify-between gap-2 text-sm"
                        >
                          <span>{vendorSaveWarningCopy(name)}</span>
                          <Button
                            type="button"
                            size="sm"
                            disabled={submitting}
                            onClick={() => tickAllergenAndResave(warning.allergenBit)}
                          >
                            {tickAllergenCopy(name)}
                          </Button>
                        </li>
                      )
                    })}
                  </ul>
                  <p className="text-sm">
                    Until they agree, customers see &ldquo;
                    {undeclaredIngredientCopy(undeclaredAllergenNames(allergenWarnings))}
                    &rdquo; on this dish.
                  </p>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={submitting}
                    onClick={keepDeclarationAsIs}
                  >
                    {KEEP_AS_IS_COPY}
                  </Button>
                </div>
              </div>
            </div>
          )}
          <form id="product-form" onSubmit={handleSubmit(onSubmit)} className="space-y-6">
            <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Product Details</h4>
            <div className="space-y-2">
              <Label htmlFor="sku">SKU</Label>
              {/* A11Y-7 (QA council 20260902-134741; WCAG 3.3.1): errors were visual
                  only. Mirrors the checkout form — aria-invalid + aria-describedby to
                  the message id, both conditional on the error existing. */}

              <Input
                id="sku"
                placeholder="e.g., PROD-001"
                aria-invalid={errors.sku ? "true" : undefined}
                aria-describedby={errors.sku ? "sku-error" : undefined}
                {...register("sku")}

              />

              {errors.sku && (
                <p id="sku-error" className="text-sm text-red-600">{errors.sku.message}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="title">Product Title</Label>
              <Input
                id="title"

                placeholder="e.g., Chocolate Chip Cookies"

                aria-invalid={errors.title ? "true" : undefined}

                aria-describedby={errors.title ? "title-error" : undefined}

                {...register("title")}
              />
              {errors.title && (
                <p id="title-error" className="text-sm text-red-600">{errors.title.message}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="ingredientsText">Ingredients</Label>
              <textarea
                id="ingredientsText"
                placeholder="e.g., Flour, sugar, butter, chocolate chips..."
                className="flex min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                aria-invalid={errors.ingredientsText ? "true" : undefined}
                // Pitfall 6: the helper is the field's description; while a validation error
                // is showing, the error replaces it (A11Y-7 resolves this to ONE message id).
                aria-describedby={errors.ingredientsText ? "ingredientsText-error" : "ingredientsText-help"}
                {...register("ingredientsText")}
              />
              <p id="ingredientsText-help" className="text-xs text-slate-500">
                {INGREDIENTS_EMPHASIS_HELP_COPY}
              </p>
              {errors.ingredientsText && (
                <p id="ingredientsText-error" className="text-sm text-red-600">
                  {errors.ingredientsText.message}
                </p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="pricePounds">Price (£)</Label>
              <Input
                id="pricePounds"
                type="number"
                step="0.01"
                min="0"
                placeholder="e.g., 12.50"

                aria-invalid={errors.pricePounds ? "true" : undefined}

                aria-describedby={errors.pricePounds ? "pricePounds-error" : undefined}

                {...register("pricePounds")}
              />
              {errors.pricePounds && (
                <p id="pricePounds-error" className="text-sm text-red-600">{errors.pricePounds.message}</p>
              )}
            </div>

            {/* UXT-046 (UI-SPEC § B6): after Price, Standard pre-selected on a new product. */}
            <div className="space-y-2">
              <Label id="vatRate-label" htmlFor="vatRate">VAT rate</Label>
              <Select
                value={vatRate ?? ""}
                onValueChange={(value) => setVatRate(value as VatRate)}
              >
                <SelectTrigger
                  id="vatRate"
                  aria-labelledby="vatRate-label"
                  aria-describedby="vatRate-help"
                >
                  <SelectValue placeholder="Keep the current rate" />
                </SelectTrigger>
                <SelectContent>
                  {VAT_RATE_OPTIONS.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p id="vatRate-help" className="text-xs text-slate-500">
                {VAT_RATE_HELP_COPY}
              </p>
            </div>

            {/* Storefront Presentation */}
            <div className="space-y-3">
              <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Storefront Presentation</h4>
              <div className="space-y-2">
                <Label htmlFor="description">Customer Description</Label>
                <textarea
                  id="description"
                  name="description"
                  placeholder="Describe this product for customers..."
                  defaultValue={editingProduct?.description || ""}
                  rows={2}
                  className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
              </div>
              {editingProduct ? (
                <ImageUploader
                  currentImageUrl={editingProduct.imageUrl}
                  uploadUrl={`/api/v1/products/${editingProduct.id}/image`}
                  // SW-ADE-02 (swarm epic #964): leave "Processing…" once the server settles the asset.
                  resolveAcceptedAsset={(assetId) =>
                    fetchAcceptedAssetOutcome(editingProduct.id, assetId)
                  }
                  onUploadComplete={(url) => {
                    setEditingProduct({ ...editingProduct, imageUrl: url })
                    fetchProducts()
                  }}
                  onAiSuggestions={(suggestions) => {
                    setAiSuggestions(suggestions)
                    toast({ title: "AI Analysis Complete", description: `Identified: ${suggestions.identifiedName || "Unknown"}` })
                  }}
                  onRemove={async () => {
                    try {
                      await apiClient.delete(`/api/v1/products/${editingProduct.id}/image`)
                      setEditingProduct({ ...editingProduct, imageUrl: null })
                      setAiSuggestions(null)
                      fetchProducts()
                    } catch {
                      toast({ variant: "destructive", title: "Error", description: "Failed to remove image" })
                    }
                  }}
                  label="Product Image"
                />
              ) : (
                <div className="flex items-center gap-2 rounded-md border border-dashed border-slate-300 bg-slate-50 px-3 py-3 text-sm text-slate-500">
                  <ImageIcon className="h-4 w-4" />
                  <span>Save the product first, then add an image</span>
                </div>
              )}

              {/* AI Suggestions Panel */}
              {aiSuggestions && aiSuggestions.confidence && aiSuggestions.confidence > 0.3 && (
                <div className="rounded-lg border border-violet-200 bg-violet-50 p-3 space-y-3">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <Sparkles className="h-4 w-4 text-violet-600" />
                      <span className="text-sm font-semibold text-violet-800">AI Suggestions</span>
                      <span className="text-xs text-violet-500">
                        {Math.round((aiSuggestions.confidence || 0) * 100)}% confidence
                      </span>
                    </div>
                    <button
                      type="button"
                      onClick={() => setAiSuggestions(null)}
                      className="text-xs text-violet-400 hover:text-violet-600"
                    >
                      Dismiss
                    </button>
                  </div>
                  {aiSuggestions.cuisineOrigin && (
                    <p className="text-xs text-violet-600">Cuisine: {aiSuggestions.cuisineOrigin}</p>
                  )}
                  <div className="grid grid-cols-1 gap-2">
                    {aiSuggestions.identifiedName && (
                      <AiSuggestionRow
                        label="Product Name"
                        value={aiSuggestions.identifiedName}
                        onAccept={() => {
                          setValue("title", aiSuggestions.identifiedName!)
                          toast({ title: "Applied", description: `Title set to "${aiSuggestions.identifiedName}"` })
                        }}
                      />
                    )}
                    {aiSuggestions.description && (
                      <AiSuggestionRow
                        label="Description"
                        value={aiSuggestions.description}
                        onAccept={() => {
                          const el = document.querySelector<HTMLTextAreaElement>("[name=description]")
                          if (el) el.value = aiSuggestions.description!
                          toast({ title: "Applied", description: "Description updated" })
                        }}
                      />
                    )}
                    {aiSuggestions.ingredients && (
                      <AiSuggestionRow
                        label="Ingredients"
                        value={aiSuggestions.ingredients}
                        onAccept={() => {
                          setValue("ingredientsText", aiSuggestions.ingredients!)
                          toast({ title: "Applied", description: "Ingredients updated" })
                        }}
                      />
                    )}
                    {aiSuggestions.category && (
                      <AiSuggestionRow
                        label="Category"
                        value={aiSuggestions.category}
                        onAccept={() => {
                          const el = document.querySelector<HTMLInputElement>("[name=category]")
                          if (el) el.value = aiSuggestions.category!
                          toast({ title: "Applied", description: `Category set to "${aiSuggestions.category}"` })
                        }}
                      />
                    )}
                    {aiSuggestions.dietaryTags && aiSuggestions.dietaryTags.length > 0 && (
                      <AiSuggestionRow
                        label="Dietary Tags"
                        value={aiSuggestions.dietaryTags.join(", ")}
                        onAccept={() => {
                          const el = document.querySelector<HTMLInputElement>("[name=dietaryTags]")
                          if (el) el.value = aiSuggestions.dietaryTags!.join(", ")
                          toast({ title: "Applied", description: "Dietary tags updated" })
                        }}
                      />
                    )}
                    {aiSuggestions.allergenWarnings && aiSuggestions.allergenWarnings.length > 0 && (
                      <div className="text-xs text-amber-700 bg-amber-50 rounded px-2 py-1.5">
                        <AlertCircle className="inline h-3 w-3 mr-1" />
                        Allergen warnings: {aiSuggestions.allergenWarnings.join(", ")}
                      </div>
                    )}
                  </div>
                </div>
              )}

              {/* Keep hidden input for backwards compatibility with form submission */}
              <input type="hidden" name="imageUrl" value={editingProduct?.imageUrl || ""} />
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-1.5">
                  <Label htmlFor="category">Category</Label>
                  <Input id="category" name="category" placeholder="e.g., Mains" defaultValue={editingProduct?.category || ""} list="category-list" />
                  <datalist id="category-list">
                    {Array.from(new Set(products.map(p => p.category).filter(Boolean))).map(cat => (
                      <option key={cat} value={cat!} />
                    ))}
                  </datalist>
                </div>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="shopId">Shop Assignment</Label>
                {/* D-08: a single-shop context does single-shop writes only — the
                    assignment is pinned to the selected shop (no cross-shop swap).
                    #959: the All-shops context lists the shops and nothing else. There is no
                    "All Shops" choice: a product belongs to one shop, and one saved with none was on
                    no storefront. A new product must pick a shop; an older shop-less product shows
                    that it has none. */}
                <select
                  id="shopId"
                  value={selectedShopId}
                  onChange={(e) => {
                    setSelectedShopId(e.target.value)
                    setShopError(false)
                  }}
                  disabled={!!contextShopId}
                  aria-required={!editingProduct}
                  aria-invalid={shopError || undefined}
                  aria-describedby={shopError ? "shopId-error" : undefined}
                  className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-70"
                >
                  {contextShopId ? (
                    <option value={contextShopId}>
                      {contextShopName || "Selected shop"}
                    </option>
                  ) : (
                    <>
                      {(!editingProduct || !editingProduct.shopId) && (
                        <option value="">
                          {editingProduct ? "No shop (not on any storefront)" : "Choose a shop…"}
                        </option>
                      )}
                      {shops.map((shop) => (
                        <option key={shop.id} value={shop.id}>{shop.name}</option>
                      ))}
                    </>
                  )}
                </select>
                {shopError && (
                  <p id="shopId-error" role="alert" className="text-sm text-red-600">
                    {SHOP_REQUIRED_COPY}
                  </p>
                )}
                {contextShopId && (
                  <p className="text-xs text-slate-500">
                    Creating in your selected shop context. Switch to “All shops” to choose a different shop.
                  </p>
                )}
                {!contextShopId && !editingProduct && (
                  <p className="text-xs text-slate-500">
                    The product goes on sale at this shop only. To sell it at another shop too, copy it from the products list.
                  </p>
                )}
                {!contextShopId && editingProduct && !editingProduct.shopId && (
                  <p className="text-xs font-medium text-amber-800" data-testid="shop-less-note">
                    This product has no shop, so it is not on any storefront and cannot be ordered. Choose a shop to put it on sale.
                  </p>
                )}
              </div>
              <div className="space-y-1.5">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={trackInventory}
                    onChange={(e) => setTrackInventory(e.target.checked)}
                    className="h-4 w-4 rounded border-slate-300"
                  />
                  <span className="text-sm font-medium">Track inventory</span>
                </label>
                {trackInventory && (
                  <div className="mt-1.5">
                    <Label htmlFor="quantityInStock">Stock Quantity</Label>
                    <Input
                      id="quantityInStock"
                      type="number"
                      min="0"
                      value={quantityInStock}
                      onChange={(e) => setQuantityInStock(parseInt(e.target.value) || 0)}
                    />
                  </div>
                )}
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                <div className="space-y-1.5">
                  <Label htmlFor="displayOrder">Display Order</Label>
                  <Input id="displayOrder" name="displayOrder" type="number" min="0" placeholder="0" defaultValue={editingProduct?.displayOrder ?? 0} />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="preparationTimeMinutes">Prep Time (min)</Label>
                  <Input id="preparationTimeMinutes" name="preparationTimeMinutes" type="number" min="0" placeholder="15" defaultValue={editingProduct?.preparationTimeMinutes || ""} />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="dietaryTags">Dietary Tags</Label>
                  <Input id="dietaryTags" name="dietaryTags" placeholder="Vegan, GF" defaultValue={editingProduct?.dietaryTags || ""} />
                </div>
              </div>
              {/* #960: what the allergen (PPDS / Natasha's Law) label is dated from. Optional here, but the
                  label cannot be printed without both. */}
              <fieldset className="space-y-1.5" aria-describedby="durability-help">
                <legend className="text-sm font-medium">Allergen label dating</legend>
                <p id="durability-help" className="text-xs text-slate-500">
                  Needed to print this product&apos;s allergen label: the label shows a use-by or
                  best-before date counted from the day the food is made.
                </p>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div className="space-y-1.5">
                    <Label htmlFor="shelfLifeDays">Shelf life (days)</Label>
                    <Input
                      id="shelfLifeDays"
                      name="shelfLifeDays"
                      type="number"
                      inputMode="numeric"
                      placeholder="3"
                      value={shelfLifeText}
                      onChange={(e) => {
                        setShelfLifeText(e.target.value)
                        setDurabilityError(null)
                      }}
                      aria-invalid={durabilityError ? "true" : undefined}
                      aria-describedby={durabilityError ? "durability-error" : undefined}
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="durabilityType">Use by or best before</Label>
                    <select
                      id="durabilityType"
                      name="durabilityType"
                      value={durabilityType}
                      onChange={(e) => {
                        setDurabilityType(e.target.value)
                        setDurabilityError(null)
                      }}
                      aria-invalid={durabilityError ? "true" : undefined}
                      aria-describedby={durabilityError ? "durability-error" : undefined}
                      className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                    >
                      <option value="">Not set</option>
                      <option value="USE_BY">Use by</option>
                      <option value="BEST_BEFORE">Best before</option>
                    </select>
                  </div>
                </div>
                {durabilityError && (
                  <p id="durability-error" role="alert" className="text-sm text-red-600">
                    {durabilityError}
                  </p>
                )}
              </fieldset>
              <div className="flex gap-6">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input type="checkbox" checked={available} onChange={(e) => setAvailable(e.target.checked)} className="h-4 w-4 rounded border-slate-300" />
                  <span className="text-sm">Available</span>
                </label>
                <label className="flex items-center gap-2 cursor-pointer">
                  <input type="checkbox" checked={featured} onChange={(e) => setFeatured(e.target.checked)} className="h-4 w-4 rounded border-slate-300" />
                  <span className="text-sm">Featured (Popular)</span>
                </label>
              </div>
            </div>

            {/* A fieldset so the declared set is a named group: D-16 adds a second set of the
                same 14 names (may contain), and the two must never be confused. */}
            <fieldset className="space-y-3" aria-describedby="allergens-help">
              <legend className="flex items-center gap-2 text-sm font-medium leading-none">
                <AlertCircle className="h-4 w-4 text-orange-600" aria-hidden="true" />
                Allergens
              </legend>
              <p id="allergens-help" className="text-sm text-slate-600">
                Select all allergens present in this product
              </p>
              <div className="grid grid-cols-2 gap-3 rounded-lg border p-4 bg-slate-50">
                {ALLERGENS.map((allergen) => (
                  <label
                    key={allergen.bit}
                    className="flex items-center gap-3 cursor-pointer rounded-md p-2 hover:bg-white transition-colors"
                  >
                    <input
                      type="checkbox"
                      checked={hasAllergen(allergenMask, allergen.bit)}
                      onChange={() => toggleAllergenBit(allergen.bit)}
                      className="h-4 w-4 rounded border-gray-300 text-orange-600 focus:ring-orange-500"
                    />
                    <span className="text-sm font-medium">{allergen.name}</span>
                  </label>
                ))}
              </div>
            </fieldset>

            {/* D-16 (#861): cross-contact, recorded apart from the declaration and shown to
                customers as its own "May contain" line. Visually distinct (dashed, neutral) so
                it never reads as a second copy of the declared set. */}
            <fieldset className="space-y-3" aria-describedby="may-contain-help">
              <legend className="text-sm font-medium leading-none">
                May contain (cross-contact)
              </legend>
              <p id="may-contain-help" className="text-sm text-slate-600">
                {MAY_CONTAIN_HELP_COPY}
              </p>
              <div className="grid grid-cols-2 gap-3 rounded-lg border border-dashed border-slate-300 p-4">
                {ALLERGENS.map((allergen) => (
                  <label
                    key={allergen.bit}
                    className="flex items-center gap-3 cursor-pointer rounded-md p-2 hover:bg-slate-50 transition-colors"
                  >
                    <input
                      type="checkbox"
                      checked={hasAllergen(mayContainMask ?? 0, allergen.bit)}
                      onChange={() => toggleMayContainBit(allergen.bit)}
                      className="h-4 w-4 rounded border-gray-300 text-slate-700 focus:ring-slate-500"
                    />
                    <span className="text-sm">{allergen.name}</span>
                  </label>
                ))}
              </div>
            </fieldset>

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setDialogOpen(false)}
                disabled={submitting}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={submitting}>
                {submitting
                  ? editingProduct
                    ? "Updating..."
                    : "Creating..."
                  : editingProduct
                  ? "Update Product"
                  : "Create Product"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* D-17 (#861): the production-date dialog in front of every label download */}
      <Dialog
        open={labelProduct !== null}
        onOpenChange={(open) => {
          if (!open) closeLabelDialog()
        }}
      >
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>Print allergen label</DialogTitle>
            <DialogDescription>
              {labelProduct?.title}: the use-by date on the label is counted from the day the
              food was made.
            </DialogDescription>
          </DialogHeader>
          {labelIncomplete && (
            <div
              role="alert"
              data-testid="label-incomplete"
              className="space-y-2 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900"
            >
              <p>{labelIncomplete}</p>
              <Button type="button" size="sm" variant="outline" onClick={editProductFromLabelDialog}>
                Edit this product
              </Button>
            </div>
          )}
          <form onSubmit={downloadLabel} noValidate className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="label-production-date">Production date</Label>
              <Input
                id="label-production-date"
                type="date"
                value={labelDate}
                max={labelMaxDate}
                required
                onChange={(e) => {
                  setLabelDate(e.target.value)
                  setLabelError(null)
                }}
                aria-invalid={labelError ? "true" : undefined}
                aria-describedby={labelError ? "label-production-date-error" : undefined}
              />
              {labelError && (
                <p id="label-production-date-error" className="text-sm text-red-600">
                  {labelError}
                </p>
              )}
            </div>
            <div className="space-y-2">
              <Label htmlFor="label-format-select">Label size / printer</Label>
              <select
                id="label-format-select"
                data-testid="label-format-select"
                value={labelFormat}
                onChange={(e) => setLabelFormat(e.target.value as LabelFormat)}
                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              >
                <option value="STANDARD_100X60MM">Standard 100×60mm sheet</option>
                <option value="THERMAL_62MM">62mm continuous thermal roll (Brother QL)</option>
              </select>
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={closeLabelDialog}
                disabled={labelDownloading}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={labelDownloading}>
                {labelDownloading ? "Preparing label..." : "Download label"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation Dialog */}
      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Product</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete{" "}
              <span className="font-semibold">{deletingProduct?.title}</span>? This
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
              {submitting ? "Deleting..." : "Delete Product"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
