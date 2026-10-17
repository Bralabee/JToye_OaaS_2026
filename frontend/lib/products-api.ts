import apiClient from "@/lib/api-client"
import { fetchAllPages } from "@/lib/paged-fetch"
import type {
  BulkImportResult,
  CopyProductsRequest,
  CopyProductsResult,
  CulinaryPackDto,
  CulinaryPackSeedRequest,
  CulinaryPackSeedResult,
  LabelFormat,
  Product,
} from "@/types/api"

/**
 * Products per request. A code-level constant, NOT a `NEXT_PUBLIC_*` knob — and the
 * deliberate exception to the rule that a page size belongs in the config layer
 * (GLOBAL_RULE_6), because here there is provably nothing for an operator to tune.
 *
 * core-java sets `spring.data.web.pageable.max-page-size: 100` (application.yml),
 * which clamps every paged endpoint. So 100 is the MAXIMUM this API will serve: a
 * larger value is a no-op on the wire, and a smaller one only buys more round trips
 * for the same rows. Absence cannot lose a product either, because the caller pages
 * until the API reports no next page and a bound that fires returns `truncated`,
 * which the dialog renders as a visible notice.
 *
 * Its two siblings — `NEXT_PUBLIC_SHOPS_PAGE_SIZE` and
 * `NEXT_PUBLIC_KITCHEN_ORDERS_PAGE_SIZE` — are env vars carrying allowlist entries in
 * `k8s/scripts/check-env-contract.sh` that exist to explain why they do nothing. A
 * third knob would need a third such paragraph. Not adding the knob says the same
 * thing in less space, and keeps the env contract honest by construction rather than
 * by exemption. (The kitchen entry already makes this argument to reject a build ARG
 * for such a value as dead config, D-18; this applies it one step earlier.)
 */
const PRODUCTS_PAGE_SIZE = 100

/**
 * A defensive bound on the paging loop in {@link fetchAllProducts} — a code-level
 * circuit breaker, exactly as `MAX_SHOP_PAGES` is, not config.
 *
 * Deliberately lower than the shop bound: a catalogue is the one collection here
 * that can plausibly be large, and this loop feeds a PICKER, not a report. At the
 * server's clamped page size 20 pages is 2,000 products — past that, a dropdown is
 * the wrong control and the honest answer is to say the list is incomplete (the
 * caller gets `truncated`) rather than to spend 50 requests populating a `<select>`
 * nobody can scroll.
 */
export const MAX_PRODUCT_PAGES = 20

export interface AllProducts {
  products: Product[]
  /**
   * True when {@link MAX_PRODUCT_PAGES} stopped the loop before the API said the
   * catalogue had ended, i.e. the picker is missing entries. Surfaced so the UI can
   * say so — an incomplete list that admits it is a different thing from #485's
   * silent truncation.
   */
  truncated: boolean
}

/**
 * Page GET /api/v1/products until the API says there is nothing after this page
 * (#485, call site `orders/page.tsx:298`).
 *
 * The previous single `?size=100` request treated the first page as the whole
 * catalogue, so a tenant past 100 products could not select products 101+ as line
 * items on a manually-created order — with no error and no indicator that the list
 * was partial.
 *
 * @param shopId narrows server-side when the caller wants one shop's catalogue.
 *   Omitted by the order-line picker, which keeps the cross-shop list it has always
 *   shown (the picker's own shop constraint is applied by the form, not this fetch).
 */
export async function fetchAllProducts(shopId?: string): Promise<AllProducts> {
  const shopScope = shopId ? `&shopId=${shopId}` : ""

  const { items, truncated } = await fetchAllPages<Product>({
    buildUrl: (page, pageSize) =>
      `/api/v1/products?page=${page}&size=${pageSize}${shopScope}`,
    size: PRODUCTS_PAGE_SIZE,
    maxPages: MAX_PRODUCT_PAGES,
    label: "[products-api] /api/v1/products",
  })
  return { products: items, truncated }
}

/**
 * Import a CSV menu INTO one shop (37-13/37-14, UXT-026): POST /api/v1/products/bulk/csv?shopId=.
 *
 * A row with its own shop_id cell goes there; every other row goes into `shopId`. The server
 * requires SHOP_MANAGER on `shopId` before it reads a row, and a row naming a shop the caller does
 * not manage comes back as a per-row error. The shop is a required argument, not an option: a
 * file posted with no shop creates nothing ("shop required" on every row), which is the failure
 * this signature makes impossible to write by accident (WINDOWS #27).
 */
export async function importCsv(file: File, shopId: string): Promise<BulkImportResult> {
  const formData = new FormData()
  formData.append("file", file)
  const res = await apiClient.post<BulkImportResult>(
    `/api/v1/products/bulk/csv?shopId=${encodeURIComponent(shopId)}`,
    formData,
    { headers: { "Content-Type": "multipart/form-data" } }
  )
  return res.data
}

/**
 * Copy products to another shop of the tenant (37-13, UXT-026): POST /api/v1/products/copy.
 * Allergens, ingredients, price, VAT rate and photos travel verbatim; a product the target already
 * has is SKIPPED, so a repeat creates nothing. The counts are the server's and are shown as such.
 */
export async function copyProducts(req: CopyProductsRequest): Promise<CopyProductsResult> {
  const res = await apiClient.post<CopyProductsResult>("/api/v1/products/copy", req)
  return res.data
}

/**
 * Fetch the 10 Pan-African & Caribbean regional cuisine packs (52 dish templates) from
 * GET /api/v1/products/culinary-packs (GTM-BLK-02).
 */
export async function fetchCulinaryPacks(): Promise<CulinaryPackDto[]> {
  const res = await apiClient.get<CulinaryPackDto[]>("/api/v1/products/culinary-packs")
  return Array.isArray(res.data) ? res.data : []
}

/**
 * Idempotently seed a shop's product catalogue from selected regional cuisine packs or dish slugs
 * via POST /api/v1/products/culinary-packs/seed (GTM-BLK-02).
 */
export async function seedCulinaryPack(
  req: CulinaryPackSeedRequest
): Promise<CulinaryPackSeedResult> {
  const res = await apiClient.post<CulinaryPackSeedResult>(
    "/api/v1/products/culinary-packs/seed",
    req
  )
  return res.data
}

/**
 * Download a PPDS (Natasha's Law) allergen label PDF for a product in either standard 100x60mm
 * or 62mm continuous thermal roll format (GTM-BLK-06).
 */
export async function downloadProductLabelBlob(params: {
  productId: string
  productionDate?: string
  format?: LabelFormat
}): Promise<Blob> {
  const query = new URLSearchParams()
  if (params.productionDate) {
    query.set("productionDate", params.productionDate)
  }
  if (params.format) {
    query.set("format", params.format)
  }
  const qs = query.toString()
  const res = await apiClient.get<Blob>(
    `/api/v1/products/${encodeURIComponent(params.productId)}/label${qs ? `?${qs}` : ""}`,
    { responseType: "blob" }
  )
  return res.data
}

