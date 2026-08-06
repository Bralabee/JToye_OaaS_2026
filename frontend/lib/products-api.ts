import { resolveProductsPageSize } from "@/lib/env-validation"
import { fetchAllPages } from "@/lib/paged-fetch"
import type { Product } from "@/types/api"

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
  const size = resolveProductsPageSize(process.env.NEXT_PUBLIC_PRODUCTS_PAGE_SIZE)
  const shopScope = shopId ? `&shopId=${shopId}` : ""

  const { items, truncated } = await fetchAllPages<Product>({
    buildUrl: (page, pageSize) =>
      `/api/v1/products?page=${page}&size=${pageSize}${shopScope}`,
    size,
    maxPages: MAX_PRODUCT_PAGES,
    label: "[products-api] /api/v1/products",
  })
  return { products: items, truncated }
}
