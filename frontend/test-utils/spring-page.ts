import type { PageResponse, Product, Shop } from "@/types/api"

/**
 * core-java's `spring.data.web.pageable.max-page-size` (application.yml).
 *
 * This is not a test-only detail — it is the reason #485's fixtures look the way
 * they do. The clamp applies to EVERY paged endpoint, so no single request can ever
 * return more than this many rows however large a `?size=` the browser sends.
 */
export const SERVER_MAX_PAGE_SIZE = 100

/**
 * A faithful Spring `PageImpl` page over `all` — the SHAPE, and the LIMITS, of what
 * the real API returns.
 *
 * Two behaviours are reproduced rather than invented, and a fixture missing either
 * one passes against the very bugs these tests exist to catch:
 *
 * 1. **The server clamps `?size=`.** A request for 200 is served
 *    {@link SERVER_MAX_PAGE_SIZE} rows, and the page reports that clamped number back
 *    as `size`. #476's fixture honoured `?size=200` literally, so its 250-shop
 *    fixture came back in two generous pages and its pager looked correct — while
 *    against the real API the same code read a full 100-row page as a short page and
 *    stopped there. A non-clamping fixture cannot see that class of bug at all.
 *
 * 2. **`new PageImpl<>(content, pageable, total)` RECOMPUTES `total`** as
 *    `offset + content.size()` whenever `offset + pageSize > total`. A fixture whose
 *    total does not genuinely exceed the page size makes CORRECT paging look broken.
 *
 * @param maxPageSize override the clamp — pass `Infinity` to model an API that
 *   honours any size, which is what makes the clamp itself falsifiable.
 */
export function springPage<T>(
  all: T[],
  page: number,
  requestedSize: number,
  maxPageSize: number = SERVER_MAX_PAGE_SIZE
): PageResponse<T> {
  const size = Math.min(requestedSize, maxPageSize)
  const offset = page * size
  const content = all.slice(offset, offset + size)
  const total =
    content.length > 0 && offset + size > all.length
      ? offset + content.length
      : all.length
  const totalPages = size > 0 ? Math.ceil(total / size) : 1
  return {
    content,
    totalElements: total,
    totalPages,
    // The EFFECTIVE size, as Spring reports it — not the size that was asked for.
    size,
    number: page,
    first: page === 0,
    last: page + 1 >= totalPages,
  }
}

/**
 * `count` shops named `Shop 1` … `Shop {count}`.
 *
 * Call sites use a count GREATER than {@link SERVER_MAX_PAGE_SIZE} and then assert
 * on the LAST one. A fixture of three shops cannot tell paged code from unpaged
 * code — both return all three — so it would be worthless for #485.
 */
export function manyShops(count: number): Shop[] {
  return Array.from({ length: count }, (_, i) => ({
    id: `shop-${String(i + 1).padStart(4, "0")}`,
    tenantId: "t-1",
    name: `Shop ${i + 1}`,
    address: `${i + 1} High Street`,
    slug: `shop-${i + 1}`,
    description: null,
    logoUrl: null,
    bannerUrl: null,
    phone: null,
    email: null,
    latitude: null,
    longitude: null,
    openingHours: null,
    deliveryInfo: null,
    minimumOrderPennies: 0,
    published: true,
    tags: null,
    createdAt: "2026-07-01T10:00:00Z",
    updatedAt: "2026-07-01T10:00:00Z",
  }))
}

/** `count` products titled `Product 1` … `Product {count}`. See {@link manyShops}. */
export function manyProducts(count: number, shopId = "shop-0001"): Product[] {
  return Array.from({ length: count }, (_, i) => ({
    id: `product-${String(i + 1).padStart(4, "0")}`,
    tenantId: "t-1",
    sku: `SKU-${i + 1}`,
    title: `Product ${i + 1}`,
    ingredientsText: "flour, water",
    allergenMask: 0,
    pricePennies: 500,
    description: null,
    imageUrl: null,
    additionalImageUrls: [],
    category: "Mains",
    displayOrder: 0,
    available: true,
    featured: false,
    preparationTimeMinutes: null,
    dietaryTags: null,
    shopId,
    quantityInStock: null,
    createdAt: "2026-07-01T10:00:00Z",
    updatedAt: "2026-07-01T10:00:00Z",
  }))
}

/** Read one query parameter out of a request URL. */
export function param(url: string, key: string): string | null {
  return new URLSearchParams(url.split("?")[1] ?? "").get(key)
}

/**
 * Serve `all` from a fake endpoint that HONOURS `?page=` and `?size=` and applies
 * the server's clamp.
 *
 * An endpoint that ignored the parameters would return everything on page 0, the
 * pre-fix single-request code would look complete, and every case would pass against
 * the bug — which is the acceptance criterion #485 was filed with.
 */
export function pagedResponse<T>(
  url: string,
  all: T[],
  maxPageSize: number = SERVER_MAX_PAGE_SIZE
): { data: PageResponse<T> } {
  const page = Number(param(url, "page") ?? 0)
  const size = Number(param(url, "size") ?? 20)
  return { data: springPage(all, page, size, maxPageSize) }
}
