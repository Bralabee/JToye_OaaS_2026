import apiClient from "@/lib/api-client"
import type { PageResponse } from "@/types/api"

/**
 * The outcome of following a paged collection to its end.
 */
export interface PagedFetchResult<T> {
  /** Every item found across the pages that were actually read. */
  items: T[]
  /** Requests issued. 1 for a collection that fits in a single page. */
  pagesRead: number
  /**
   * True when {@link PagedFetchOptions.maxPages} stopped the loop before the API
   * said there was no next page — i.e. the list may be incomplete. Callers that
   * can say so in the UI should; an incomplete list that admits it is a different
   * thing from one that lies, which is the whole of #282 and #485.
   */
  truncated: boolean
}

export interface PagedFetchOptions {
  /** Builds the request URL for one page. Owns its own sort/filter params. */
  buildUrl: (page: number, size: number) => string
  /** Items to ask for per request. Resolved from config by the caller, never a literal. */
  size: number
  /** Circuit breaker: the most requests this loop may ever issue. */
  maxPages: number
  /** Prefix for the circuit-breaker warning, e.g. `[shops-api] /api/v1/shops`. */
  label: string
}

/**
 * Follow a Spring `Page` collection until the API says there is nothing after this
 * page — the single paging primitive behind #282 and #485.
 *
 * WHY THIS EXISTS AS ONE FUNCTION. The defect it fixes ("issue one request with a
 * hardcoded size and treat the first page as the whole list") was found at ten call
 * sites across two issues. #476 fixed one by writing a loop; #535 fixed two more by
 * writing the loop again. A third copy would have made the truncation bug cheaper to
 * reintroduce than to fix, so the loop now lives here once and every caller is a thin
 * wrapper over it.
 *
 * Stops on the FIRST of: an empty page, `last: true`, the last of `totalPages`, a
 * short page, or `maxPages`. The short-page and empty-page exits mean a response
 * carrying no paging metadata at all still terminates after one request.
 *
 * ⚠ THE SHORT-PAGE EXIT COMPARES AGAINST THE SERVER'S PAGE SIZE, NOT OURS. This is
 * the whole reason the primitive is not a straight copy of the #476 loop. core-java
 * sets `spring.data.web.pageable.max-page-size: 100` (application.yml), which clamps
 * EVERY paged endpoint — so a request for 200 is served 100, and comparing the 100
 * items received against the 200 requested reads as "short page, we are done" on the
 * server's first FULL page. #476's `fetchAllMyShops` had exactly that shape with a
 * default size of 200, so against the real API it still stopped at 100 shops; its
 * fixture honoured `?size=200` literally, so the clamp never appeared and the test
 * passed over the bug. Spring reports the effective size back as `size`, so that is
 * what a full page is measured against; the requested size is only the fallback for a
 * response that omits it.
 */
export async function fetchAllPages<T>({
  buildUrl,
  size,
  maxPages,
  label,
}: PagedFetchOptions): Promise<PagedFetchResult<T>> {
  const items: T[] = []

  for (let page = 0; page < maxPages; page++) {
    const res = await apiClient.get<PageResponse<T>>(buildUrl(page, size))
    const body = res.data
    const content = body?.content ?? []
    items.push(...content)

    const pagesRead = page + 1
    const done = (): PagedFetchResult<T> => ({ items, pagesRead, truncated: false })

    if (content.length === 0) return done()
    if (body?.last === true) return done()
    if (typeof body?.totalPages === "number" && pagesRead >= body.totalPages) {
      return done()
    }
    // The server's own page size when it reports one — see the warning above.
    const effectiveSize =
      typeof body?.size === "number" && body.size > 0 ? body.size : size
    if (content.length < effectiveSize) return done()
  }

  console.warn(
    `${label}: stopped paging at the ${maxPages}-page bound (${items.length} items); ` +
      `the API never reported a final page.`
  )
  return { items, pagesRead: maxPages, truncated: true }
}
