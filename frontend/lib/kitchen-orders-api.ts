import apiClient from "@/lib/api-client"
import { resolveKitchenOrdersPageSize } from "@/lib/env-validation"
import type { Order, OrderDetail, OrderStatus, PageResponse } from "@/types/api"

/**
 * The statuses that belong on a kitchen board. Exported so the page, the paging
 * loop and the tests all agree on one definition.
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

export interface KitchenOrdersPage {
  /** Every order in a kitchen status found across the pages that were read. */
  orders: Order[]
  /** Requests actually issued — 1 for a normal shop, more only when history is deep. */
  pagesRead: number
  /**
   * True when {@link MAX_KITCHEN_ORDER_PAGES} stopped the loop before the API said
   * there was no next page. The board renders a visible notice in this state: an
   * incomplete board that says so is a different thing from one that lies.
   */
  truncated: boolean
}

/**
 * Fetch every ACTIVE (kitchen-status) order for one shop, following the list to its
 * end instead of assuming the first page is all of it (#485, call site
 * `kitchen/page.tsx:229`).
 *
 * WHY PAGING AND NOT A BIGGER NUMBER. The old call was a single
 * `?shopId=…&size=100&sort=createdAt,desc`, and a shop past 100 lifetime orders lost
 * everything after the first page — including live tickets. The obvious repair is to
 * raise 100. Measured against the running core-java on 2026-08-04, for a shop with
 * 125 orders:
 *
 *     size=100 -> content 100, size 100, totalPages 2, last false
 *     size=500 -> content 100, size 100, totalPages 2, last false
 *
 * The server clamps the page size at 100, so a bigger request returns the same 100
 * rows. Raising the literal cannot work — not "moves the cliff", *cannot work*.
 * Following `last`/`totalPages` is the only fix available to a client.
 *
 * Cost is proportional to history, not to a constant: a shop with fewer than 100
 * lifetime orders issues exactly ONE request, the same as before.
 */
export async function fetchActiveKitchenOrders(
  shopId: string
): Promise<KitchenOrdersPage> {
  const size = resolveKitchenOrdersPageSize(
    process.env.NEXT_PUBLIC_KITCHEN_ORDERS_PAGE_SIZE
  )
  const orders: Order[] = []

  for (let page = 0; page < MAX_KITCHEN_ORDER_PAGES; page++) {
    // shopId stays the FIRST query parameter: it is the shape the kitchen board's
    // own tests and the VSA-03 scoping test match on (`/api/v1/orders?shopId=`).
    const res = await apiClient.get<PageResponse<Order>>(
      `/api/v1/orders?shopId=${shopId}&page=${page}&size=${size}&sort=createdAt,desc`
    )
    const body = res.data
    const content = body?.content ?? []
    for (const order of content) {
      if (KITCHEN_STATUSES.includes(order.status)) orders.push(order)
    }

    const pagesRead = page + 1
    if (content.length === 0) return { orders, pagesRead, truncated: false }
    if (body?.last === true) return { orders, pagesRead, truncated: false }
    if (typeof body?.totalPages === "number" && pagesRead >= body.totalPages) {
      return { orders, pagesRead, truncated: false }
    }
    // A response carrying no paging metadata at all still terminates here.
    if (content.length < size) return { orders, pagesRead, truncated: false }
  }

  console.warn(
    `[kitchen-orders-api] stopped paging /api/v1/orders at the ` +
      `${MAX_KITCHEN_ORDER_PAGES}-page bound for shop ${shopId}; the API never ` +
      `reported a final page. The board will show that it may be incomplete.`
  )
  return { orders, pagesRead: MAX_KITCHEN_ORDER_PAGES, truncated: true }
}

/**
 * Fetch the full detail (line items, customer, address) for each active order.
 *
 * Split out from {@link fetchActiveKitchenOrders} so the paging contract can be
 * tested without also stubbing N detail endpoints.
 */
export async function fetchKitchenOrderDetails(
  orders: Order[]
): Promise<OrderDetail[]> {
  return Promise.all(
    orders.map((o) =>
      apiClient
        .get<OrderDetail>(`/api/v1/orders/${o.id}/detail`)
        .then((r) => r.data)
    )
  )
}
