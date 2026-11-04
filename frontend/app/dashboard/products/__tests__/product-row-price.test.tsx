/**
 * #858 (owner ruling 2026-11-04): changing a price took four taps through the long edit form. The row
 * now carries an inline price control that sends ONLY `{pricePennies}` through the narrow
 * PATCH /api/v1/products/{id} - the same endpoint the availability switch uses - so a price edit can
 * never resend (and overwrite) an allergen declaration or an ingredient list.
 *
 * Asserted through the real page: what is on the wire, what the row shows afterwards, and that a
 * refused or malformed price changes nothing.
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

function product(id: string, title: string, pricePennies: number) {
  return {
    id,
    tenantId: "tenant-1",
    sku: `SKU-${id}`,
    title,
    ingredientsText: "rice, water",
    allergenMask: 0,
    pricePennies,
    vatRate: "STANDARD",
    description: null,
    imageUrl: null,
    additionalImageUrls: [],
    category: null,
    displayOrder: 0,
    available: true,
    featured: false,
    preparationTimeMinutes: null,
    dietaryTags: null,
    shopId: "shop-1",
    quantityInStock: null,
    allergenWarnings: [],
    createdAt: "2026-10-12T12:00:00Z",
    updatedAt: "2026-10-12T12:00:00Z",
  }
}

const list = (rows: unknown[]) => ({ data: { content: rows, totalElements: rows.length, totalPages: 1 } })

async function loaded() {
  mockedApiClient.get.mockResolvedValue(list([product("p1", "Jollof Rice", 900), product("p2", "Suya", 1250)]))
  render(<ProductsPage />)
  await screen.findByText("Jollof Rice")
}

const openPriceEditor = (title: string, current: string) =>
  fireEvent.click(screen.getByRole("button", { name: `Change price of ${title}, now ${current}` }))
const priceInput = (title: string) => screen.getByLabelText(`New price of ${title} in pounds`)

beforeEach(() => {
  jest.clearAllMocks()
})

describe("Products list: inline price edit through PATCH (issue #858)", () => {
  it("one edit sends ONLY {pricePennies} through PATCH - never the whole product - and the row shows the new price", async () => {
    await loaded()
    mockedApiClient.patch.mockResolvedValue({ data: { ...product("p1", "Jollof Rice", 1050) } })

    openPriceEditor("Jollof Rice", "£9.00")
    fireEvent.change(priceInput("Jollof Rice"), { target: { value: "10.50" } })
    fireEvent.click(screen.getByRole("button", { name: "Save price for Jollof Rice" }))

    await waitFor(() => expect(mockedApiClient.patch).toHaveBeenCalledTimes(1))
    expect(mockedApiClient.patch).toHaveBeenCalledWith("/api/v1/products/p1", { pricePennies: 1050 })
    expect(mockedApiClient.put).not.toHaveBeenCalled()
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Change price of Jollof Rice, now £10.50" })).toBeInTheDocument()
    )
    expect(screen.queryByLabelText("New price of Jollof Rice in pounds")).not.toBeInTheDocument()
    // The other row is untouched.
    expect(screen.getByRole("button", { name: "Change price of Suya, now £12.50" })).toBeInTheDocument()
  })

  it("Enter saves, Escape cancels without a request", async () => {
    await loaded()
    mockedApiClient.patch.mockResolvedValue({ data: {} })

    openPriceEditor("Suya", "£12.50")
    fireEvent.change(priceInput("Suya"), { target: { value: "13" } })
    fireEvent.keyDown(priceInput("Suya"), { key: "Escape" })
    expect(mockedApiClient.patch).not.toHaveBeenCalled()
    expect(screen.queryByLabelText("New price of Suya in pounds")).not.toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Change price of Suya, now £12.50" })).toBeInTheDocument()

    openPriceEditor("Suya", "£12.50")
    fireEvent.change(priceInput("Suya"), { target: { value: "13" } })
    fireEvent.keyDown(priceInput("Suya"), { key: "Enter" })
    await waitFor(() => expect(mockedApiClient.patch).toHaveBeenCalledWith("/api/v1/products/p2", { pricePennies: 1300 }))
  })

  it.each([["", "blank"], ["abc", "text"], ["-1", "negative"], ["1.234", "sub-penny"], ["100000000", "above £10,000,000"]])(
    "a %j price (%s) is refused inline and asks the server for nothing",
    async (typed) => {
      await loaded()
      openPriceEditor("Jollof Rice", "£9.00")
      fireEvent.change(priceInput("Jollof Rice"), { target: { value: typed } })
      fireEvent.click(screen.getByRole("button", { name: "Save price for Jollof Rice" }))

      expect(await screen.findByRole("alert")).toHaveTextContent(/price/i)
      expect(mockedApiClient.patch).not.toHaveBeenCalled()
    }
  )

  it("a refused PATCH keeps the old price, keeps the editor open and shows the server's reason", async () => {
    await loaded()
    mockedApiClient.patch.mockRejectedValue({
      response: { status: 403, data: { type: "https://jtoye.uk/errors/shop-access-denied", detail: "You need to be a shop manager here." } },
    })

    openPriceEditor("Jollof Rice", "£9.00")
    fireEvent.change(priceInput("Jollof Rice"), { target: { value: "10" } })
    fireEvent.click(screen.getByRole("button", { name: "Save price for Jollof Rice" }))

    await waitFor(() =>
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          variant: "destructive",
          title: "Couldn't change the price of Jollof Rice",
          description: "You need to be a shop manager here.",
        })
      )
    )
    expect(priceInput("Jollof Rice")).toHaveValue("10")
  })

  it("the price control and its buttons are 44px targets", async () => {
    await loaded()
    expect(screen.getByRole("button", { name: "Change price of Jollof Rice, now £9.00" }).className).toContain("min-h-11")
    openPriceEditor("Jollof Rice", "£9.00")
    expect(screen.getByRole("button", { name: "Save price for Jollof Rice" }).className).toContain("min-h-11")
    expect(screen.getByRole("button", { name: "Cancel price change for Jollof Rice" }).className).toContain("min-h-11")
  })
})
