/**
 * #822: the Orders search used to filter only the ONE page the browser held (20 rows), so an order on
 * page 3 of 7 was "not found". The text now goes to the server as `q`, which searches every page in
 * the query; the typed text still filters the loaded rows at once so the box feels instant while the
 * request is in flight.
 *
 * Asserted through the real page (network, SSE hook and toast faked):
 *   - after a pause in typing the list is re-read with `q`, from page 0;
 *   - the rows shown are the SERVER's (a phone match the client could never compute stays visible);
 *   - an empty box sends no `q` at all (the URL every other test pins is unchanged);
 *   - a server answer of nothing reads "No orders match", never "No orders yet";
 *   - clearing the box returns to the ordinary list.
 */
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react"
import OrdersPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

jest.mock("@/hooks/use-order-events", () => ({
  useOrderEvents: jest.fn(),
}))

function orderRow(id: string, orderNumber: string, customerName: string, phone?: string) {
  return {
    id,
    tenantId: "tenant-1",
    shopId: "shop-1",
    orderNumber,
    status: "PENDING",
    customerName,
    customerEmail: null,
    customerPhone: phone,
    totalAmountPennies: 2149,
    itemCount: 2,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }
}

const PAGE_ONE = [
  orderRow("11111111-0000-0000-0000-000000000001", "ORD-AAAA-0001", "Jane Doe"),
  orderRow("22222222-0000-0000-0000-000000000002", "ORD-BBBB-0002", "Kemi Ade"),
]
// An order from "page 3" - never in the rows the browser loaded - found only by the server.
const FAR_AWAY = orderRow("99999999-0000-0000-0000-000000000009", "ORD-ZZZZ-0099", "Olu Bright", "07700 900123")

const listUrls = () =>
  mockedApiClient.get.mock.calls.map(([u]) => String(u)).filter((u) => u.startsWith("/api/v1/orders"))

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/orders")) {
      if (url.includes("q=07700900123")) {
        return Promise.resolve({ data: { content: [FAR_AWAY], totalPages: 1, totalElements: 1 } })
      }
      if (url.includes("q=nobody-here")) {
        return Promise.resolve({ data: { content: [], totalPages: 0, totalElements: 0 } })
      }
      return Promise.resolve({ data: { content: PAGE_ONE, totalPages: 3, totalElements: 41 } })
    }
    return Promise.resolve({ data: { content: [] } })
  })
})

async function loaded() {
  render(<OrdersPage />)
  await screen.findByRole("table")
  return screen.getByRole("searchbox", { name: /search orders/i })
}

describe("Orders search goes to the server (issue #822)", () => {
  it("sends no q while the box is empty", async () => {
    await loaded()
    expect(listUrls().length).toBeGreaterThan(0)
    for (const url of listUrls()) expect(url).not.toContain("q=")
  })

  it("re-reads the list with q (from page 0) after a pause in typing, and shows the server's rows", async () => {
    const search = await loaded()
    fireEvent.change(search, { target: { value: "07700900123" } })

    await waitFor(() => expect(listUrls().some((u) => u.includes("q=07700900123"))).toBe(true), { timeout: 3000 })
    const searched = listUrls().find((u) => u.includes("q=07700900123")) as string
    expect(searched).toContain("page=0")
    expect(searched).toContain("size=20")

    // The phone match is the server's; the client-side box filter (name/number/email) could never keep it.
    expect(await screen.findByText("ORD-ZZZZ-0099")).toBeInTheDocument()
    expect(screen.queryByText("ORD-AAAA-0001")).toBeNull()
  })

  it("does not fire a request per keystroke", async () => {
    const search = await loaded()
    const before = listUrls().length
    for (const v of ["n", "no", "nob", "nobo"]) fireEvent.change(search, { target: { value: v } })
    expect(listUrls().length).toBe(before)

    await waitFor(() => expect(listUrls().some((u) => u.includes("q=nobo"))).toBe(true), { timeout: 3000 })
    expect(listUrls().filter((u) => u.includes("q=")).length).toBe(1)
  })

  it("says nothing matched - not 'No orders yet' - when the server finds none, and Clear search restores the list", async () => {
    const search = await loaded()
    fireEvent.change(search, { target: { value: "nobody-here" } })

    expect(await screen.findByText(/no orders match/i, undefined, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.queryByText("No orders yet")).toBeNull()
    expect(screen.queryByRole("table")).toBeNull()
    // The box survives the re-read (the page does not unmount into its spinner while searching).
    expect(screen.getByRole("searchbox", { name: /search orders/i })).toHaveValue("nobody-here")

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /clear search/i }))
    })
    expect(await screen.findByText("ORD-AAAA-0001", undefined, { timeout: 3000 })).toBeInTheDocument()
    expect(listUrls()[listUrls().length - 1]).not.toContain("q=")
  })
})
