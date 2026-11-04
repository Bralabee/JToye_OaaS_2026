/**
 * #858: 86ing an item took four taps and ~30 s inside a long form (open edit, scroll to the
 * Available checkbox, untick, save). The row now carries a one-tap Available / Sold out switch that
 * calls the narrow PATCH /api/v1/products/{id} {available} - the endpoint built (37-39) so that
 * flipping availability can never overwrite allergen or ingredient data - with an optimistic flip
 * that is rolled back, with the server's reason, if the request fails.
 *
 * Price is deliberately NOT inline: PATCH refuses price by design (37-39), and a PUT built from a
 * list row would resend the whole product. See the SUMMARY for that open decision.
 */
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

function product(id: string, title: string, available: boolean) {
  return {
    id,
    tenantId: "tenant-1",
    sku: `SKU-${id}`,
    title,
    ingredientsText: "rice, water",
    allergenMask: 0,
    pricePennies: 900,
    vatRate: "STANDARD",
    description: null,
    imageUrl: null,
    additionalImageUrls: [],
    category: null,
    displayOrder: 0,
    available,
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

const availabilitySwitch = (title: string) =>
  screen.getByRole("switch", { name: new RegExp(`^${title}: `) })

async function loaded(rows: unknown[]) {
  mockedApiClient.get.mockResolvedValue(list(rows))
  render(<ProductsPage />)
  await screen.findByText(String((rows[0] as { title: string }).title))
}

beforeEach(() => {
  jest.clearAllMocks()
})

describe("Products list: one-tap Available / Sold out (issue #858)", () => {
  it("shows the state as a switch with a name that says what a tap does", async () => {
    await loaded([product("p1", "Jollof Rice", true), product("p2", "Suya", false)])

    const on = availabilitySwitch("Jollof Rice")
    expect(on).toHaveAttribute("aria-checked", "true")
    expect(on).toHaveAccessibleName("Jollof Rice: available. Tap to mark sold out")
    const off = availabilitySwitch("Suya")
    expect(off).toHaveAttribute("aria-checked", "false")
    expect(off).toHaveAccessibleName("Suya: sold out. Tap to mark available")
  })

  it("one tap sends ONLY {available:false} through PATCH - never the whole product - and flips the row", async () => {
    await loaded([product("p1", "Jollof Rice", true)])
    mockedApiClient.patch.mockResolvedValue({ data: { ...product("p1", "Jollof Rice", false) } })

    await act(async () => {
      fireEvent.click(availabilitySwitch("Jollof Rice"))
    })

    expect(mockedApiClient.patch).toHaveBeenCalledTimes(1)
    expect(mockedApiClient.patch).toHaveBeenCalledWith("/api/v1/products/p1", { available: false })
    expect(mockedApiClient.put).not.toHaveBeenCalled()
    expect(availabilitySwitch("Jollof Rice")).toHaveAttribute("aria-checked", "false")
    expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({ title: "Jollof Rice marked sold out" })
    )
  })

  it("a sold-out item goes back on sale with {available:true}", async () => {
    await loaded([product("p2", "Suya", false)])
    mockedApiClient.patch.mockResolvedValue({ data: product("p2", "Suya", true) })

    await act(async () => {
      fireEvent.click(availabilitySwitch("Suya"))
    })

    expect(mockedApiClient.patch).toHaveBeenCalledWith("/api/v1/products/p2", { available: true })
    expect(availabilitySwitch("Suya")).toHaveAttribute("aria-checked", "true")
  })

  it("flips at once, and a second tap while the first is in flight is ignored (no double PATCH)", async () => {
    await loaded([product("p1", "Jollof Rice", true)])
    let settle!: (v: unknown) => void
    mockedApiClient.patch.mockImplementation(() => new Promise((resolve) => { settle = resolve }))

    await act(async () => {
      fireEvent.click(availabilitySwitch("Jollof Rice"))
    })
    const sw = availabilitySwitch("Jollof Rice")
    expect(sw).toHaveAttribute("aria-checked", "false") // optimistic
    expect(sw).toBeDisabled()
    await act(async () => {
      fireEvent.click(sw)
    })
    expect(mockedApiClient.patch).toHaveBeenCalledTimes(1)

    await act(async () => {
      settle({ data: product("p1", "Jollof Rice", false) })
    })
    expect(availabilitySwitch("Jollof Rice")).toBeEnabled()
  })

  it("a refused PATCH rolls the row back and says why, with the server's detail", async () => {
    await loaded([product("p1", "Jollof Rice", true)])
    mockedApiClient.patch.mockRejectedValue({
      response: {
        status: 403,
        data: { type: "https://jtoye.uk/errors/shop-access-denied", title: "Forbidden", status: 403, detail: "You need to be a shop manager here." },
      },
    })

    await act(async () => {
      fireEvent.click(availabilitySwitch("Jollof Rice"))
    })

    await waitFor(() => expect(availabilitySwitch("Jollof Rice")).toHaveAttribute("aria-checked", "true"))
    expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({
        variant: "destructive",
        title: "Couldn't update Jollof Rice",
        description: "You need to be a shop manager here.",
      })
    )
  })

  it("the switch is a 44px target and the row's other actions are unchanged", async () => {
    await loaded([product("p1", "Jollof Rice", true)])
    expect(availabilitySwitch("Jollof Rice").className).toContain("min-h-11")
    expect(screen.getByRole("button", { name: "Edit product Jollof Rice" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Delete product Jollof Rice" })).toBeInTheDocument()
  })
})
