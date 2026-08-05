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
  fetchKitchenBoard,
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

describe("fetchKitchenBoard", () => {
  // NOTE ON THE FIXTURES BELOW (#564). `serve()` now stands in for `/orders/kitchen`,
  // which returns ONLY active orders — the status filter moved to the server. So these
  // fixtures are boards, not histories. Where a test still needs a second page it uses
  // 125 ACTIVE tickets, which is a real if unhappy kitchen, rather than 125 rows of
  // history hiding one live ticket — the shape that no longer reaches the client at all.

  it("issues exactly ONE request for a board that fits on a page", async () => {
    // The control for the deep case below. A "fix" that always pages would satisfy
    // every tail assertion here and be a different, quieter bug.
    const rows = [order(0, "CONFIRMED"), order(1, "READY")]
    const seen = serve(rows)

    const result = await fetchKitchenBoard(SHOP)

    expect(seen).toHaveLength(1)
    expect(result.pagesRead).toBe(1)
    expect(result.truncated).toBe(false)
    expect(result.orders.map((o) => o.id)).toEqual(["o-0", "o-1"])
  })

  it("recovers a ticket that exists ONLY past the first page", async () => {
    // 125 live tickets, so the 111th sits on page 1 at size 100. #485's contract is
    // kept, not inherited by accident: a board bigger than one page must still be read
    // to its end rather than silently truncated at 100.
    const rows = Array.from({ length: 125 }, (_, i) => order(i, "CONFIRMED"))
    const seen = serve(rows)

    const result = await fetchKitchenBoard(SHOP)

    expect(result.orders.map((o) => o.id)).toContain("o-110")
    expect(result.orders).toHaveLength(125)
    expect(seen.some((u) => u.includes("page=1"))).toBe(true)
    expect(result.pagesRead).toBe(2)
    expect(result.truncated).toBe(false)
  })

  it("stops as soon as the server says this is the last page", async () => {
    const rows = Array.from({ length: 125 }, (_, i) => order(i, "COMPLETED"))
    const seen = serve(rows)

    await fetchKitchenBoard(SHOP)

    expect(seen.some((u) => u.includes("page=2"))).toBe(false)
    expect(seen).toHaveLength(2)
  })

  it("keeps shopId as the FIRST query parameter", async () => {
    // The board's own tests and the VSA-03 scoping test both match on the literal
    // prefix `/api/v1/orders/kitchen?shopId=`; reordering the params breaks them silently.
    const seen = serve([order(0, "CONFIRMED")])
    await fetchKitchenBoard(SHOP)
    expect(seen[0].startsWith(`/api/v1/orders/kitchen?shopId=${SHOP}&`)).toBe(true)
  })

  it("asks for the configured page size, which defaults to the API's own ceiling", async () => {
    const seen = serve([order(0, "CONFIRMED")])
    await fetchKitchenBoard(SHOP)
    expect(seen[0]).toContain(`size=${DEFAULT_KITCHEN_ORDERS_PAGE_SIZE}`)
    // Asking for more than the server serves would be a silent no-op, not a fix.
    expect(DEFAULT_KITCHEN_ORDERS_PAGE_SIZE).toBeLessThanOrEqual(SERVER_MAX_PAGE_SIZE)
  })

  it("terminates after one request when the response carries no paging metadata", async () => {
    mockGet.mockReset()
    mockGet.mockResolvedValue({ data: { content: [order(0, "READY")] } })

    const result = await fetchKitchenBoard(SHOP)

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(result.orders).toHaveLength(1)
    expect(result.truncated).toBe(false)
  })

  it("terminates on an empty page even if the server claims there are more", async () => {
    mockGet.mockReset()
    mockGet.mockResolvedValue({
      data: { content: [], totalElements: 999, totalPages: 99, size: 100, number: 0, first: true, last: false },
    })

    const result = await fetchKitchenBoard(SHOP)

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

    const result = await fetchKitchenBoard(SHOP)

    expect(mockGet).toHaveBeenCalledTimes(MAX_KITCHEN_ORDER_PAGES)
    expect(result.truncated).toBe(true)
    expect(result.pagesRead).toBe(MAX_KITCHEN_ORDER_PAGES)
    expect(warn).toHaveBeenCalledWith(expect.stringContaining("stopped paging"))
    warn.mockRestore()
  })

  // --- #564: the two properties that changed shape ---

  it("does not filter by status — the SERVER decides what is on the board", async () => {
    // This replaces "keeps only kitchen statuses, from every page it read".
    //
    // The client used to page the shop's WHOLE history and filter here, so the work
    // scaled with how long the shop had been trading rather than with how many tickets
    // were live. Filtering client-side is only possible if you have fetched the rows you
    // then throw away — which was the cost. `/orders/kitchen` returns the board.
    //
    // Asserting the client is a pass-through matters: if it filtered as well, a status
    // the two definitions disagreed about would vanish from the board with no error, and
    // the server's version — the one that bounds the query — would be the one overruled.
    serve([order(0, "CONFIRMED"), order(1, "PREPARING"), order(2, "READY")])

    const result = await fetchKitchenBoard(SHOP)

    expect(result.orders.map((o) => o.status)).toEqual([
      "CONFIRMED",
      "PREPARING",
      "READY",
    ])
  })

  it("issues ONE request for a busy board — the cost does not scale with ticket count", async () => {
    // #564's acceptance, at unit level. Before this change a board of N tickets cost
    // 1 + N requests: this fixture would have been 51. The number below must stay 1 no
    // matter how large the fixture grows, which is the property the issue asked for.
    const rows = Array.from({ length: 50 }, (_, i) => order(i, "CONFIRMED"))
    const seen = serve(rows)

    const result = await fetchKitchenBoard(SHOP)

    expect(result.orders).toHaveLength(50)
    expect(seen).toHaveLength(1)
    // Named explicitly so a future reader sees the intent rather than a bare `1`.
    expect(seen.filter((u) => u.includes("/detail"))).toHaveLength(0)
  })

  it("carries the line items with the order, so no follow-up read is needed", async () => {
    // The reason one request is enough: detail arrives WITH the ticket. If the payload
    // stopped carrying items the board would silently render empty tickets, and the
    // request-count test above would still pass.
    mockGet.mockReset()
    mockGet.mockResolvedValue({
      data: {
        content: [{ ...order(0, "CONFIRMED"), items: [{ id: "i-1", productName: "Jollof Rice", quantity: 2 }] }],
      },
    })

    const result = await fetchKitchenBoard(SHOP)

    expect(result.orders[0].items).toHaveLength(1)
    expect(result.orders[0].items[0].productName).toBe("Jollof Rice")
  })
})


/**
 * `fetchKitchenOrderDetails` and its four tests were RETIRED here by #564, not lost.
 *
 * They existed because the board fetched detail one request per ticket, and #561 proved
 * that a partly-refused burst (ten 429s out of nineteen) discarded an otherwise-complete
 * read and left the board warning over data it was still holding. #563 fixed that with
 * `Promise.allSettled` and a `failedIds` contract, and these tests pinned it.
 *
 * With one request there is no burst, so a refusal is TOTAL — "some succeeded, some did
 * not" is now unreachable, and a test for it would assert a state the code cannot enter.
 * Removing the burst is strictly better than tolerating it, which is why the tolerance
 * goes with it rather than being kept as decoration.
 *
 * What survives, and is tested above and in the page's own suite: a read the board cannot
 * complete still raises the banner, and a successful one clears it.
 */
