/**
 * #959 (swarm SW-ADE-01) — a dish saved with the default "All Shops" never appeared on any
 * storefront, and the form said nothing.
 *
 * A product belongs to exactly one shop (UIX-05): the menu, checkout, per-shop stock and the label's
 * business identity are all keyed on it. So the All-shops context no longer offers "All Shops": a new
 * product must pick a shop, the form refuses to save without one and says why, a vendor with one
 * shop has it chosen already, and an older shop-less product is flagged in the list and in its edit
 * form instead of sitting there looking on sale.
 */
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"
import { TEST_SHOP, getWithShop, chooseTestShop } from "@/test-utils/shop-assignment"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/lib/shop-context", () => ({
  ALL_SHOPS_CONTEXT: "all",
  getShopContext: jest.fn(() => "all"),
  setShopContext: jest.fn(),
  subscribeShopContext: jest.fn(() => () => {}),
}))

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

const SECOND_SHOP = { id: "6b6b6b6b-2222-4222-8222-222222222222", name: "Brixton Bakery", published: true }

const product = {
  id: "p-1",
  tenantId: "tenant-1",
  sku: "FISH-1",
  title: "Fried Fish",
  ingredientsText: "fish, flour",
  allergenMask: 0,
  pricePennies: 1100,
  description: null,
  imageUrl: null,
  additionalImageUrls: [],
  category: "Mains",
  displayOrder: 0,
  available: true,
  featured: false,
  preparationTimeMinutes: null,
  dietaryTags: null,
  shopId: null as string | null,
  quantityInStock: null,
  allergenWarnings: [],
  createdAt: "2026-10-19T12:00:00Z",
  updatedAt: "2026-10-19T12:00:00Z",
}

/** Fake API: `shops` for /api/v1/shops, `rows` for products. */
function api(shops: Array<Record<string, unknown>>, rows: unknown[]) {
  return (url: string) => {
    const content = String(url).startsWith("/api/v1/shops") ? shops : rows
    return Promise.resolve({ data: { content, totalElements: content.length, totalPages: 1 } })
  }
}

async function openCreateForm() {
  render(<ProductsPage />)
  await waitFor(() =>
    expect(screen.getAllByRole("button", { name: /add product/i }).length).toBeGreaterThan(0)
  )
  fireEvent.click(screen.getAllByRole("button", { name: /add product/i })[0])
  await screen.findByText("Create New Product")
  fireEvent.change(screen.getByLabelText("SKU"), { target: { value: "FISH-1" } })
  fireEvent.change(screen.getByLabelText("Product Title"), { target: { value: "Fried Fish" } })
  fireEvent.change(screen.getByLabelText("Ingredients"), { target: { value: "fish, flour" } })
  fireEvent.change(screen.getByLabelText(/^Price/), { target: { value: "11.00" } })
}

describe("#959: Shop Assignment on a new product (All-shops context)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.post.mockResolvedValue({ data: { ...product, shopId: TEST_SHOP.id } })
    mockedApiClient.put.mockResolvedValue({ data: { ...product, shopId: TEST_SHOP.id } })
  })

  describe("a vendor with two shops", () => {
    beforeEach(() => {
      mockedApiClient.get.mockImplementation(api([TEST_SHOP, SECOND_SHOP], []) as jest.Mock)
    })

    it("offers the shops and 'Choose a shop…', and no 'All Shops'", async () => {
      await openCreateForm()
      const select = (await screen.findByLabelText("Shop Assignment")) as HTMLSelectElement
      await screen.findByRole("option", { name: TEST_SHOP.name })
      expect(screen.queryByRole("option", { name: /all shops/i })).not.toBeInTheDocument()
      expect(select.value).toBe("")
      expect(within(select).getByRole("option", { name: "Choose a shop…" })).toBeInTheDocument()
      expect(select).toHaveAttribute("aria-required", "true")
      expect(
        screen.getByText(/goes on sale at this shop only/i)
      ).toBeInTheDocument()
    })

    it("refuses to save without a shop, says why next to the field, and sends nothing", async () => {
      await openCreateForm()
      await screen.findByRole("option", { name: TEST_SHOP.name })
      fireEvent.click(screen.getByRole("button", { name: /create product/i }))

      // The message is a live alert tied to the select; the select is flagged invalid and focused.
      expect(await screen.findByRole("alert")).toHaveTextContent(
        "Choose which shop sells this product. A product with no shop does not appear on any storefront."
      )
      const select = screen.getByLabelText("Shop Assignment")
      await waitFor(() => expect(select).toHaveAttribute("aria-invalid", "true"))
      expect(select).toHaveAccessibleDescription(
        /Choose which shop sells this product\. A product with no shop does not appear on any storefront\./
      )
      expect(document.activeElement).toBe(select)
      expect(mockedApiClient.post).not.toHaveBeenCalled()
    })

    it("the error clears when a shop is chosen, and the create carries that shopId", async () => {
      await openCreateForm()
      await screen.findByRole("option", { name: TEST_SHOP.name })
      fireEvent.click(screen.getByRole("button", { name: /create product/i }))
      await waitFor(() =>
        expect(screen.getByLabelText("Shop Assignment")).toHaveAttribute("aria-invalid", "true")
      )

      await chooseTestShop()
      expect(screen.getByLabelText("Shop Assignment")).not.toHaveAttribute("aria-invalid")
      fireEvent.click(screen.getByRole("button", { name: /create product/i }))

      await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalledTimes(1))
      const body = mockedApiClient.post.mock.calls[0][1] as Record<string, unknown>
      expect(body.shopId).toBe(TEST_SHOP.id)
    })

    it("shows the server's plain-words refusal when it answers 400 shop-required", async () => {
      const detail = "Choose a shop for this product. A product with no shop does not appear on any shop's menu and cannot be ordered."
      mockedApiClient.post.mockRejectedValue(
        Object.assign(new Error("Request failed with status code 400"), {
          response: { status: 400, data: { type: "https://jtoye.uk/errors/shop-required", detail } },
        })
      )
      await openCreateForm()
      await chooseTestShop()
      fireEvent.click(screen.getByRole("button", { name: /create product/i }))

      await waitFor(() =>
        expect(mockToast).toHaveBeenCalledWith(
          expect.objectContaining({ variant: "destructive", description: detail })
        )
      )
    })
  })

  it("a vendor with exactly one shop has it chosen already", async () => {
    mockedApiClient.get.mockImplementation(getWithShop([]) as jest.Mock)
    await openCreateForm()
    await screen.findByRole("option", { name: TEST_SHOP.name })
    expect((screen.getByLabelText("Shop Assignment") as HTMLSelectElement).value).toBe(TEST_SHOP.id)

    fireEvent.click(screen.getByRole("button", { name: /create product/i }))
    await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalledTimes(1))
    expect((mockedApiClient.post.mock.calls[0][1] as Record<string, unknown>).shopId).toBe(TEST_SHOP.id)
  })
})

describe("#959: a product that already has no shop", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockImplementation(api([TEST_SHOP, SECOND_SHOP], [product]) as jest.Mock)
    mockedApiClient.put.mockResolvedValue({ data: { ...product, shopId: TEST_SHOP.id } })
  })

  it("is flagged in the list as on no storefront", async () => {
    render(<ProductsPage />)
    const badge = await screen.findByTestId("shop-less-badge")
    expect(badge).toHaveTextContent("No shop: not on any storefront")
  })

  it("a product with a shop carries no such flag", async () => {
    mockedApiClient.get.mockImplementation(
      api([TEST_SHOP], [{ ...product, shopId: TEST_SHOP.id }]) as jest.Mock
    )
    render(<ProductsPage />)
    await screen.findByText("Fried Fish")
    expect(screen.queryByTestId("shop-less-badge")).not.toBeInTheDocument()
  })

  it("its edit form says so, and choosing a shop saves the shopId", async () => {
    render(<ProductsPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Edit product Fried Fish" }))
    await screen.findByText("Edit Product")

    expect(screen.getByTestId("shop-less-note")).toHaveTextContent(/not on any storefront and cannot be ordered/i)
    const select = screen.getByLabelText("Shop Assignment") as HTMLSelectElement
    expect(select.value).toBe("")
    expect(within(select).getByRole("option", { name: "No shop (not on any storefront)" })).toBeInTheDocument()

    await screen.findByRole("option", { name: TEST_SHOP.name })
    fireEvent.change(select, { target: { value: TEST_SHOP.id } })
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))

    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    expect((mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>).shopId).toBe(TEST_SHOP.id)
  })

  it("can still be saved as it is (an edit never strands an older row), sending no shopId", async () => {
    render(<ProductsPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Edit product Fried Fish" }))
    await screen.findByText("Edit Product")
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))

    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    expect((mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>).shopId).toBeUndefined()
  })
})
