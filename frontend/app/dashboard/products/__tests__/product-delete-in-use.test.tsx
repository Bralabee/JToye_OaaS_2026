/**
 * SW-ADE-09 (swarm epic #964): deleting a dish that was ever ordered failed with the raw toast
 * "Request failed with status code 409". The server answers that delete with a typed problem detail
 * (409, type https://jtoye.uk/errors/in-use, ProductService.deleteProduct), because an order still
 * references the product. The vendor is now told why, in plain words, and what to do instead (take it
 * off the menu with the Available tick). No archive feature is added.
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

const product = {
  id: "p-1",
  tenantId: "tenant-1",
  sku: "JOL-1",
  title: "Jollof Rice",
  ingredientsText: "rice, tomato",
  allergenMask: 0,
  pricePennies: 950,
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
  shopId: null,
  quantityInStock: null,
  allergenWarnings: [],
  createdAt: "2026-10-09T12:00:00Z",
  updatedAt: "2026-10-09T12:00:00Z",
}

const httpError = (status: number, data?: Record<string, unknown>) =>
  Object.assign(new Error(`Request failed with status code ${status}`), {
    response: { status, data },
  })

async function deleteJollof() {
  mockedApiClient.get.mockResolvedValue({
    data: { content: [product], totalElements: 1, totalPages: 1 },
  } as never)
  render(<ProductsPage />)
  fireEvent.click(await screen.findByRole("button", { name: "Delete product Jollof Rice" }))
  fireEvent.click(await screen.findByRole("button", { name: "Delete Product" }))
  await waitFor(() => expect(mockToast).toHaveBeenCalled())
  return mockToast.mock.calls.at(-1)?.[0] as { title: string; description: string }
}

beforeEach(() => {
  jest.clearAllMocks()
})

describe("deleting an ordered dish (SW-ADE-09)", () => {
  it("explains the 409 in-use refusal and says to untick Available instead", async () => {
    mockedApiClient.delete.mockRejectedValueOnce(
      httpError(409, {
        type: "https://jtoye.uk/errors/in-use",
        title: "Resource In Use",
        status: 409,
        detail: "Product cannot be deleted: it is still referenced by an existing order",
      }) as never
    )
    const t = await deleteJollof()
    expect(t.title).toBe("Jollof Rice can't be deleted")
    expect(t.description).toMatch(/on past orders/i)
    expect(t.description).toMatch(/untick Available/i)
    expect(t.description).not.toMatch(/status code/i)
  })

  it("shows the server's problem detail, not the raw axios message, for another refusal", async () => {
    mockedApiClient.delete.mockRejectedValueOnce(
      httpError(403, {
        type: "https://jtoye.uk/errors/shop-access-denied",
        title: "Forbidden",
        status: 403,
        detail: "You need shop manager access to change this shop's menu.",
      }) as never
    )
    const t = await deleteJollof()
    expect(t.description).toBe("You need shop manager access to change this shop's menu.")
  })

  it("falls back to plain words when the refusal has no body", async () => {
    mockedApiClient.delete.mockRejectedValueOnce(httpError(500) as never)
    const t = await deleteJollof()
    expect(t.description).not.toMatch(/status code/i)
    expect(t.description).toBe("Couldn't delete the product. Please try again.")
  })
})
