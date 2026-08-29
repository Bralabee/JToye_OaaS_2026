/**
 * QA-council F2 (FEB-1): the products list must discriminate a load FAILURE
 * from a genuine empty catalogue. Before this fix, `fetchProducts`'s catch
 * block only toasted and left `products` at its initial `[]`, so a 429 (or
 * any other fetch failure) rendered the identical "No products yet / Add
 * Product" empty state a vendor with zero products sees — a false claim
 * about their own catalogue.
 */
import { render, screen, waitFor } from "@testing-library/react"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

function mockNonProductEndpoints() {
  return (url: string) => {
    if (url.startsWith("/api/v1/products")) return undefined // caller overrides
    // /api/v1/staff/me and /api/v1/shops?... feed the shop picker — non-critical.
    return Promise.resolve({ data: { content: [], groupAdmin: true, userId: "u1" } })
  }
}

describe("Products page — load-failure vs genuine-empty (F2 / FEB-1)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it("shows the error panel, not the empty state, when the initial load 429s", async () => {
    const other = mockNonProductEndpoints()
    mockedApiClient.get.mockImplementation((url: string) => {
      const fallback = other(url)
      if (fallback) return fallback
      return Promise.reject({ response: { status: 429, headers: {}, data: {} } })
    })

    render(<ProductsPage />)

    expect(await screen.findByTestId("load-error-panel")).toBeInTheDocument()
    expect(screen.getByRole("alert")).toHaveTextContent(/Couldn.t load products/i)
    expect(screen.getByRole("button", { name: /try again/i })).toBeInTheDocument()

    // The false-zero empty state must NOT render alongside/instead of the panel.
    expect(screen.queryByText("No products yet")).not.toBeInTheDocument()
  })

  it("CONTROL: a genuine 200 with zero rows still shows the real empty state", async () => {
    mockedApiClient.get.mockResolvedValue({
      data: { content: [], totalElements: 0, totalPages: 0 },
    })

    render(<ProductsPage />)

    await waitFor(() => {
      expect(screen.getByText("No products yet")).toBeInTheDocument()
    })
    expect(screen.queryByTestId("load-error-panel")).not.toBeInTheDocument()
  })

  it("retry button re-issues the products fetch", async () => {
    const other = mockNonProductEndpoints()
    // Flag-driven rather than call-count-driven: the page fires two mount
    // effects (page/shop-context + search-query), so a count-based mock is
    // fragile to how many times THOSE happen to run before the user retries.
    let shouldFail = true
    mockedApiClient.get.mockImplementation((url: string) => {
      const fallback = other(url)
      if (fallback) return fallback
      if (shouldFail) {
        return Promise.reject({ response: { status: 429, headers: {}, data: {} } })
      }
      return Promise.resolve({ data: { content: [], totalElements: 0, totalPages: 0 } })
    })

    render(<ProductsPage />)

    const retryButton = await screen.findByRole("button", { name: /try again/i })
    shouldFail = false
    retryButton.click()

    await waitFor(() => {
      expect(screen.getByText("No products yet")).toBeInTheDocument()
    })
  })
})
