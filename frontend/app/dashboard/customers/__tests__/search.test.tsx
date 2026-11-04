/**
 * #822: Customers had no search at all - a vendor taking a phone call scrolled a paged table for the
 * caller. The box sends `q` to GET /api/v1/customers (name, phone, and email where the caller sees
 * it), which searches every page in the query.
 *
 * Asserted through the real page (network and toast faked):
 *   - the box exists and is labelled;
 *   - after a pause in typing the list is re-read with `q` from page 0, once, not per keystroke;
 *   - the box keeps its value and focus through the re-read (the page does not unmount into its spinner);
 *   - a server answer of nothing reads "No customers match", never "No customers yet";
 *   - an empty box sends no `q`, so every existing URL is unchanged.
 */
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react"
import CustomersPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

function customer(id: string, name: string) {
  return {
    id,
    tenantId: "tenant-1",
    name,
    email: `${id}@example.com`,
    phone: "07700 900123",
    allergenRestrictions: 0,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }
}

const ALL = [customer("c1", "Ada Lovelace"), customer("c2", "Grace Hopper")]
const FOUND = [customer("c9", "Katherine Johnson")]

const urls = () => mockedApiClient.get.mock.calls.map(([u]) => String(u))

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.includes("q=katherine")) {
      return Promise.resolve({ data: { content: FOUND, totalElements: 1, totalPages: 1 } })
    }
    if (url.includes("q=nobody-here")) {
      return Promise.resolve({ data: { content: [], totalElements: 0, totalPages: 0 } })
    }
    return Promise.resolve({ data: { content: ALL, totalElements: 41, totalPages: 3 } })
  })
})

async function loaded() {
  render(<CustomersPage />)
  await screen.findByText("Ada Lovelace")
  return screen.getByRole("searchbox", { name: /search customers/i })
}

describe("Customers search (issue #822)", () => {
  it("has a labelled search box, and an empty box sends no q", async () => {
    await loaded()
    for (const url of urls()) expect(url).not.toContain("q=")
  })

  it("re-reads with q from page 0 after a pause in typing - once, not per keystroke - and shows the server's rows", async () => {
    const search = await loaded()
    const before = urls().length
    for (const v of ["k", "ka", "kat", "katherine"]) fireEvent.change(search, { target: { value: v } })
    expect(urls().length).toBe(before)

    await waitFor(() => expect(urls().some((u) => u.includes("q=katherine"))).toBe(true), { timeout: 3000 })
    const searched = urls().find((u) => u.includes("q=katherine")) as string
    expect(searched).toContain("page=0")
    expect(urls().filter((u) => u.includes("q=")).length).toBe(1)

    expect(await screen.findByText("Katherine Johnson")).toBeInTheDocument()
    expect(screen.queryByText("Ada Lovelace")).toBeNull()
    // The box survived the re-read.
    expect(screen.getByRole("searchbox", { name: /search customers/i })).toHaveValue("katherine")
  })

  it("encodes the text so a & or # cannot split the query string", async () => {
    const search = await loaded()
    fireEvent.change(search, { target: { value: "a&b#c" } })
    await waitFor(() => expect(urls().some((u) => u.includes("q=a%26b%23c"))).toBe(true), { timeout: 3000 })
  })

  it("says nothing matched - not 'No customers yet' - and Clear search restores the list", async () => {
    const search = await loaded()
    fireEvent.change(search, { target: { value: "nobody-here" } })

    expect(await screen.findByText(/no customers match/i, undefined, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.queryByText("No customers yet")).toBeNull()

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /clear search/i }))
    })
    expect(await screen.findByText("Ada Lovelace", undefined, { timeout: 3000 })).toBeInTheDocument()
    expect(urls()[urls().length - 1]).not.toContain("q=")
  })
})
