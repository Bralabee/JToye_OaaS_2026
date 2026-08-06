import { resolveKitchenOrdersPageSize } from "@/lib/env-validation"
import { fetchAllPages } from "@/lib/paged-fetch"
import type { OrderDetail, OrderStatus } from "@/types/api"

/**
 * The statuses that belong on a kitchen board.
 *
 * ⚠ Since #564 this no longer decides what a READ returns — the server does, in
 * `OrderService.KITCHEN_STATUSES`, which is the only place that can bound the query.
 * This copy still earns its keep for the live socket: when a status change arrives over
 * STOMP the page has to decide whether that ticket belongs on the board without asking
 * the API again. The two must agree; the server is the authority.
 */
export const KITCHEN_STATUSES: OrderStatus[] = ["CONFIRMED", "PREPARING", "READY"]

/**
 * A defensive bound on the paging loop — a code-level circuit breaker, not config,
 * exactly as `MAX_SHOP_PAGES` in `shops-api.ts` is.
 *
 * Every normal exit is a signal FROM the server (`last`, `totalPages`, a short page,
 * an empty page). This only fires if the API keeps claiming there is another full
 * page forever. At the default page size that is 2,000 orders of history scanned,
 * which is far past any real kitchen's live board; hitting it is reported to the
 * caller as `truncated` so the UI can SAY the board may be incomplete rather than
 * silently dropping the tail — which is the whole defect in #485.
 */
export const MAX_KITCHEN_ORDER_PAGES = 20

export interface KitchenBoardPage {
  /** Every active order found across the pages that were read, WITH its line items. */
  orders: OrderDetail[]
  /**
   * Requests actually issued. Before #564 this was "1 for a normal shop, more only when
   * history is deep" — history no longer enters into it, because the server filters by
   * status, so this is 1 unless a shop genuinely has more live tickets than one page.
   */
  pagesRead: number
  /**
   * True when {@link MAX_KITCHEN_ORDER_PAGES} stopped the loop before the API said
   * there was no next page. The board renders a visible notice in this state: an
   * incomplete board that says so is a different thing from one that lies.
   */
  truncated: boolean
}

/**
 * Fetch the kitchen board for one shop — active orders WITH their line items — in a
 * number of requests that does not depend on how many tickets are on the board (#564).
 *
 * WHAT THIS REPLACED, AND WHY IT MATTERED. The board asked one question and paid
 * `1 + N` requests for it: a list read, then one `/orders/{id}/detail` per ticket,
 * concurrently. Measured on the dev tenant's 18-ticket board that is 19 requests — and
 * the page's `online` handler re-reads immediately on recovery, so an offline blip fired
 * the whole burst twice inside ~400 ms against a tenant bucket of
 * `capacity(120).refillIntervally(100, 1min)`: ONE LUMP per minute, shared by everything
 * else the tenant is doing. Ten of those came back 429. The cost scaled with how BUSY
 * the kitchen was, which is exactly backwards.
 *
 * IT ALSO STOPPED READING HISTORY TO FIND THE PRESENT. The old list call paged the
 * shop's ENTIRE order list and filtered for kitchen statuses here in the browser, so the
 * work scaled with how long the shop had been trading: 43 lifetime rows for 18 live
 * tickets on the dev tenant, and a shop past ~2,000 could exhaust the page bound below
 * before reaching its live tickets. The server filters by status now, so the result is
 * bounded by what is ON the board.
 *
 * THE PAGING CONTRACT FROM #485 IS KEPT, not dropped, because it is defence against a
 * different failure: a server that never reports a final page. It should now terminate
 * on the first page in every realistic case — an incomplete board still SAYS it is
 * incomplete rather than silently dropping the tail.
 */
export async function fetchKitchenBoard(shopId: string): Promise<KitchenBoardPage> {
  const size = resolveKitchenOrdersPageSize(
    process.env.NEXT_PUBLIC_KITCHEN_ORDERS_PAGE_SIZE
  )

  // #485: the loop this used to inline is now `fetchAllPages`, shared with the shop
  // and product readers. Exit conditions are unchanged — with one repair the board
  // inherits for free: a short page is measured against the SERVER's page size, so a
  // clamped response can no longer read as "the list ended here".
  const { items, pagesRead, truncated } = await fetchAllPages<OrderDetail>({
    // shopId stays the FIRST query parameter: it is the shape the kitchen board's
    // own tests and the VSA-03 scoping test match on.
    buildUrl: (page, pageSize) =>
      `/api/v1/orders/kitchen?shopId=${shopId}&page=${page}&size=${pageSize}&sort=createdAt,desc`,
    size,
    maxPages: MAX_KITCHEN_ORDER_PAGES,
    label: `[kitchen-orders-api] /api/v1/orders/kitchen for shop ${shopId}`,
  })

  return { orders: items, pagesRead, truncated }
}
