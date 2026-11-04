/**
 * #833 (UXT-064 / ADE-05): deleting a shop orphaned its products. The server now refuses (409,
 * https://jtoye.uk/errors/shop-has-products, with productCount) while the shop still has products.
 * The dashboard must say so in plain words, name the count, point at the way out (move the products
 * to another shop, or delete them) and keep the shop in the list.
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import ShopsPage from "@/app/dashboard/shops/page"

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

const mockGet = jest.fn()
const mockDelete = jest.fn()
jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockGet(...args),
    put: jest.fn(),
    post: jest.fn(),
    delete: (...args: unknown[]) => mockDelete(...args),
  },
}))

const SHOP = {
  id: "kitchen-1",
  tenantId: "tenant-1",
  name: "Rosie's Kitchen",
  address: "1 High St",
  slug: "kitchen-1",
  description: null,
  logoUrl: null,
  bannerUrl: null,
  phone: null,
  email: null,
  openingHours: null,
  deliveryInfo: null,
  minimumOrderPennies: 0,
  published: false,
  tags: null,
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z",
  ordersPausedIndefinitely: false,
}

beforeEach(() => {
  jest.clearAllMocks()
  mockGet.mockImplementation((url: string) =>
    url.startsWith("/api/v1/shops?")
      ? Promise.resolve({ data: { content: [SHOP], totalElements: 1, totalPages: 1 } })
      : Promise.resolve({ data: SHOP })
  )
})

async function openDeleteDialog() {
  render(<ShopsPage />)
  const del = await screen.findByRole("button", { name: "Delete shop Rosie's Kitchen" })
  await act(async () => {
    fireEvent.click(del)
  })
  return screen.findByRole("dialog")
}

describe("deleting a shop that still has products (#833)", () => {
  it("shows the count and the way out, inline, and keeps the dialog's shop", async () => {
    mockDelete.mockRejectedValue({
      isAxiosError: true,
      message: "Request failed with status code 409",
      response: {
        status: 409,
        data: {
          type: "https://jtoye.uk/errors/shop-has-products",
          status: 409,
          detail: "This shop still has 3 products. Move them to another shop or delete them first, then delete the shop.",
          productCount: 3,
        },
      },
    })
    const dialog = await openDeleteDialog()
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Delete Shop" }))
    })
    const alert = await waitFor(() => within(dialog).getByRole("alert"))
    expect(alert).toHaveTextContent("3 products")
    expect(alert).toHaveTextContent(/move them to another shop or delete them/i)
    expect(within(alert).getByRole("link", { name: /go to products/i })).toHaveAttribute("href", "/dashboard/products")
    expect(JSON.stringify(mockToast.mock.calls)).not.toMatch(/status code/i)
  })

  it("a shop with no products still deletes with the success toast", async () => {
    mockDelete.mockResolvedValue({ status: 204 })
    const dialog = await openDeleteDialog()
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Delete Shop" }))
    })
    await waitFor(() => expect(mockToast).toHaveBeenCalledWith(expect.objectContaining({ title: "Shop deleted" })))
  })
})
