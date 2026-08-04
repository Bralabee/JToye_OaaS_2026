/**
 * #485 (kitchen/page.tsx:229) — the kitchen board follows the order list to its end.
 *
 * THE FAKE ENDPOINT HONOURS ?page= AND ?size=, and clamps size at 100 exactly as the
 * live API was measured to (`size=500` returned 100 rows). Both matter:
 *   - a fake that ignored paging would return everything on page 0, and every test
 *     here would pass against the bug it exists to catch;
 *   - a fake that honoured an over-large `size` would let "just ask for more" pass,
 *     which is the fix the server makes impossible.
 * `totalElements` genuinely exceeds the page size in the deep fixture, so the
 * PageImpl total-recompute trap cannot make correct paging look broken.
 */
import {
  fetchActiveKitchenOrders,
  fetchKitchenOrderDetails,
  MAX_KITCHEN_ORDER_PAGES,
} from "../kitchen-orders-api"
import { DEFAULT_KITCHEN_ORDERS_PAGE_SIZE } from "../env-validation"

const mockGet = jest.fn()
jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

const SHOP = "shop-1"
/** The real API's ceiling, measured 2026-08-04 against core-java. */
const SERVER_MAX_PAGE_SIZE = 100

function order(i: number, status: string) {
  return {
    id: `o-${i}`,
    tenantId: "t",
    shopId: SHOP,
    orderNumber: `ORD-${i}`,
    status,
    totalAmountPennies: 100,
    itemCount: 1,
    createdAt: new Date(Date.now() - i * 1000).toISOString(),
    updatedAt: new Date(Date.now() - i * 1000).toISOString(),
  }
}

/** A fake server over `rows` that pages honestly. Returns the URLs it was asked for. */
function serve(rows: ReturnType<typeof order>[]) {
  const seen: string[] = []
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) => {
    seen.push(url)
    const q = new URLSearchParams(url.split("?")[1] ?? "")
    const page = Number(q.get("page") ?? 0)
    const size = Math.min(Number(q.get("size") ?? 20), SERVER_MAX_PAGE_SIZE)
    const start = page * size
    const content = rows.slice(start, start + size)
    const totalPages = Math.ceil(rows.length / size)
    return Promise.resolve({
      data: {
        content,
        totalElements: rows.length,
        totalPages,
        size,
        number: page,
        first: page === 0,
        last: page + 1 >= totalPages,
      },
    })
  })
  return seen
}

describe("fetchActiveKitchenOrders", () => {
  it("issues exactly ONE request for a shop whose history fits on a page", async () => {
    // The control for the deep case below. A "fix" that always pages would satisfy
    // every tail assertion here and be a different, quieter bug.
    const rows = [order(0, "CONFIRMED"), order(1, "COMPLETED")]
    const seen = serve(rows)

    const result = await fetchActiveKitchenOrders(SHOP)

    expect(seen).toHaveLength(1)
    expect(result.pagesRead).toBe(1)
    expect(result.truncated).toBe(false)
    expect(result.orders.map((o) => o.id)).toEqual(["o-0"])
  })

  it("recovers a kitchen ticket that exists ONLY past the first page", async () => {
    // 125 orders; the single CONFIRMED one is at index 110, i.e. page 1 at size 100.
    // Pre-#485 the board issued one `?size=100` request and this ticket never rendered.
    const rows = Array.from({ length: 125 }, (_, i) =>
      order(i, i === 110 ? "CONFIRMED" : "COMPLETED")
    )
    const seen = serve(rows)

    const result = await fetchActiveKitchenOrders(SHOP)

    expect(result.orders.map((o) => o.id)).toEqual(["o-110"])
    expect(seen.some((u) => u.includes("page=1"))).toBe(true)
    expect(result.pagesRead).toBe(2)
    expect(result.truncated).toBe(false)
  })

  it("stops as soon as the server says this is the last page", async () => {
    const rows = Array.from({ length: 125 }, (_, i) => order(i, "COMPLETED"))
    const seen = serve(rows)

    await fetchActiveKitchenOrders(SHOP)

    expect(seen.some((u) => u.includes("page=2"))).toBe(false)
    expect(seen).toHaveLength(2)
  })

  it("keeps shopId as the FIRST query parameter", async () => {
    // The board's own tests and the VSA-03 scoping test both match on the literal
    // prefix `/api/v1/orders?shopId=`; reordering the params breaks them silently.
    const seen = serve([order(0, "CONFIRMED")])
    await fetchActiveKitchenOrders(SHOP)
    expect(seen[0].startsWith(`/api/v1/orders?shopId=${SHOP}&`)).toBe(true)
  })

  it("asks for the configured page size, which defaults to the API's own ceiling", async () => {
    const seen = serve([order(0, "CONFIRMED")])
    await fetchActiveKitchenOrders(SHOP)
    expect(seen[0]).toContain(`size=${DEFAULT_KITCHEN_ORDERS_PAGE_SIZE}`)
    // Asking for more than the server serves would be a silent no-op, not a fix.
    expect(DEFAULT_KITCHEN_ORDERS_PAGE_SIZE).toBeLessThanOrEqual(SERVER_MAX_PAGE_SIZE)
  })

  it("terminates after one request when the response carries no paging metadata", async () => {
    mockGet.mockReset()
    mockGet.mockResolvedValue({ data: { content: [order(0, "READY")] } })

    const result = await fetchActiveKitchenOrders(SHOP)

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(result.orders).toHaveLength(1)
    expect(result.truncated).toBe(false)
  })

  it("terminates on an empty page even if the server claims there are more", async () => {
    mockGet.mockReset()
    mockGet.mockResolvedValue({
      data: { content: [], totalElements: 999, totalPages: 99, size: 100, number: 0, first: true, last: false },
    })

    const result = await fetchActiveKitchenOrders(SHOP)

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(result.truncated).toBe(false)
  })

  it("stops at the circuit breaker and REPORTS it when the API never ends the list", async () => {
    // Falsifiability for the board's truncation notice: without this arm the notice
    // could never fire and nobody would know.
    //
    // The warn is asserted, not merely swallowed — an operator signal that stopped
    // being emitted would otherwise be invisible.
    const warn = jest.spyOn(console, "warn").mockImplementation(() => {})
    mockGet.mockReset()
    mockGet.mockImplementation((url: string) => {
      const q = new URLSearchParams(url.split("?")[1] ?? "")
      const size = Math.min(Number(q.get("size") ?? 20), SERVER_MAX_PAGE_SIZE)
      return Promise.resolve({
        data: {
          content: Array.from({ length: size }, (_, i) => order(i, "COMPLETED")),
          totalElements: 999999,
          totalPages: 9999,
          size,
          number: Number(q.get("page") ?? 0),
          first: false,
          last: false,
        },
      })
    })

    const result = await fetchActiveKitchenOrders(SHOP)

    expect(mockGet).toHaveBeenCalledTimes(MAX_KITCHEN_ORDER_PAGES)
    expect(result.truncated).toBe(true)
    expect(result.pagesRead).toBe(MAX_KITCHEN_ORDER_PAGES)
    expect(warn).toHaveBeenCalledWith(expect.stringContaining("stopped paging"))
    warn.mockRestore()
  })

  it("keeps only kitchen statuses, from every page it read", async () => {
    const rows = [
      order(0, "PENDING"),
      order(1, "CONFIRMED"),
      order(2, "DRAFT"),
      order(3, "PREPARING"),
      order(4, "READY"),
      order(5, "COMPLETED"),
      order(6, "CANCELLED"),
    ]
    serve(rows)

    const result = await fetchActiveKitchenOrders(SHOP)

    expect(result.orders.map((o) => o.status)).toEqual([
      "CONFIRMED",
      "PREPARING",
      "READY",
    ])
  })
})

describe("fetchKitchenOrderDetails", () => {
  it("requests one detail per order and preserves order", async () => {
    mockGet.mockReset()
    mockGet.mockImplementation((url: string) =>
      Promise.resolve({ data: { id: url.match(/orders\/([^/]+)\/detail/)![1] } })
    )

    const details = await fetchKitchenOrderDetails([order(1, "CONFIRMED"), order(2, "READY")])

    expect(details.map((d) => d.id)).toEqual(["o-1", "o-2"])
    expect(mockGet).toHaveBeenCalledTimes(2)
  })

  it("issues nothing for an empty list", async () => {
    mockGet.mockReset()
    await expect(fetchKitchenOrderDetails([])).resolves.toEqual([])
    expect(mockGet).not.toHaveBeenCalled()
  })
})
