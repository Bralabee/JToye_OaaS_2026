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

// Hoisted capture (the `mock` prefix is what jest.mock factories may close over):
// #688's toast assertion needs to read what the page actually toasted.
const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
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

  it("the count subtitle dashes out under a load failure instead of asserting 0 (#688)", async () => {
    const other = mockNonProductEndpoints()
    mockedApiClient.get.mockImplementation((url: string) => {
      const fallback = other(url)
      if (fallback) return fallback
      return Promise.reject({ response: { status: 500, headers: {}, data: {} } })
    })

    render(<ProductsPage />)

    await screen.findByTestId("load-error-panel")
    // The residual #688 filed: the header read "0 products in total" while the
    // panel said the load failed — a number presented as fact when nothing loaded.
    expect(screen.queryByText(/0 products in total/)).not.toBeInTheDocument()
    expect(screen.getByText("—")).toBeInTheDocument()
  })

  it("the load toast never shows raw axios transport text (#688 / A11Y-2)", async () => {
    const other = mockNonProductEndpoints()
    // An axios failure IS an Error whose .message is the transport string —
    // the exact shape that leaked to the toast before this fix.
    const axiosShaped = Object.assign(new Error("Request failed with status code 500"), {
      response: { status: 500, headers: {}, data: {} },
    })
    mockedApiClient.get.mockImplementation((url: string) => {
      const fallback = other(url)
      if (fallback) return fallback
      return Promise.reject(axiosShaped)
    })

    render(<ProductsPage />)

    await screen.findByTestId("load-error-panel")
    const descriptions = mockToast.mock.calls.map((c) => c[0]?.description)
    expect(descriptions.length).toBeGreaterThan(0)
    expect(descriptions).not.toEqual(
      expect.arrayContaining([expect.stringMatching(/Request failed with status code/)])
    )
    expect(descriptions).toEqual(expect.arrayContaining(["Failed to load products"]))
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
