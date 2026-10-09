/**
 * UXT-046 (#815), UI-SPEC § B6 — a vendor chooses each product's VAT rate.
 *
 * The API has accepted `vatRate` (ZERO | REDUCED | STANDARD | EXEMPT) since #81, defaulting to
 * STANDARD on create and preserving the stored rate when a PUT omits it. The form never offered it,
 * so every product a vendor created was silently standard-rated, and a zero-rated cold dish could
 * not be corrected from the dashboard at all.
 *
 * The contract: a "VAT rate" Select after Price, four options in the UI-SPEC wording, Standard
 * pre-selected on a new product (the server default made visible), the chosen value sent as
 * vatRate on create and update, and the helper copy (owner sign-off pending, 37-15) as the
 * control's description.
 */
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

const VAT_HELPER =
  "The rate that applies to this item when you sell it. Hot takeaway food is usually standard-rated, and much cold food is zero-rated, but there are exceptions. If you're not sure, check HMRC VAT Notice 709/1 or ask your accountant."

const product = {
  id: "p-1",
  tenantId: "tenant-1",
  sku: "ZOBO-1",
  title: "Zobo Drink",
  ingredientsText: "hibiscus, ginger, pineapple",
  allergenMask: 0,
  pricePennies: 250,
  vatRate: "ZERO",
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

function listOf(content: unknown[]) {
  return { data: { content, totalElements: content.length, totalPages: 1 } }
}

const vatSelect = () => screen.getByRole("combobox", { name: "VAT rate" })

async function openCreate() {
  render(<ProductsPage />)
  await waitFor(() =>
    expect(screen.getAllByRole("button", { name: /add product/i }).length).toBeGreaterThan(0)
  )
  fireEvent.click(screen.getAllByRole("button", { name: /add product/i })[0])
  await screen.findByText("Create New Product")
  fireEvent.change(screen.getByLabelText("SKU"), { target: { value: "ZOBO-1" } })
  fireEvent.change(screen.getByLabelText("Product Title"), { target: { value: "Zobo Drink" } })
  fireEvent.change(screen.getByLabelText("Ingredients"), { target: { value: "hibiscus, ginger" } })
  fireEvent.change(screen.getByLabelText(/^Price/), { target: { value: "2.50" } })
}

async function openEditOf(p: Record<string, unknown>) {
  mockedApiClient.get.mockResolvedValue(listOf([p]))
  render(<ProductsPage />)
  fireEvent.click(await screen.findByRole("button", { name: `Edit product ${p.title}` }))
  await screen.findByText("Edit Product")
}

/** Radix renders the options only while the listbox is open; ArrowDown is the path jsdom drives. */
async function choose(optionName: string) {
  fireEvent.keyDown(vatSelect(), { key: "ArrowDown" })
  fireEvent.click(await screen.findByRole("option", { name: optionName }))
  await waitFor(() => expect(vatSelect()).toHaveTextContent(optionName))
}

describe("UXT-046: the VAT rate Select in the product form (UI-SPEC § B6)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockResolvedValue(listOf([]))
    mockedApiClient.post.mockResolvedValue({ data: { ...product, allergenWarnings: [] } })
    mockedApiClient.put.mockResolvedValue({ data: { ...product, allergenWarnings: [] } })
  })

  it("a new product shows 'Standard rate (20%)' pre-selected, after Price, described by the helper copy", async () => {
    await openCreate()
    const select = vatSelect()
    expect(select).toHaveTextContent("Standard rate (20%)")
    expect(select).toHaveAccessibleDescription(VAT_HELPER)
    // After Price in the form's reading order.
    const price = screen.getByLabelText(/^Price/)
    expect(price.compareDocumentPosition(select) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("offers exactly the four UI-SPEC options", async () => {
    await openCreate()
    fireEvent.keyDown(vatSelect(), { key: "ArrowDown" })
    const listbox = await screen.findByRole("listbox")
    expect(within(listbox).getAllByRole("option").map((o) => o.textContent)).toEqual([
      "Standard rate (20%)",
      "Reduced rate (5%)",
      "Zero rate (0%)",
      "Exempt",
    ])
  })

  it("an untouched new product sends vatRate STANDARD — the default is stated, not implied", async () => {
    await openCreate()
    fireEvent.click(screen.getByRole("button", { name: /create product/i }))
    await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalledTimes(1))
    const body = mockedApiClient.post.mock.calls[0][1] as Record<string, unknown>
    expect(body.vatRate).toBe("STANDARD")
  })

  it("choosing Zero rate sends vatRate ZERO on create", async () => {
    await openCreate()
    await choose("Zero rate (0%)")
    fireEvent.click(screen.getByRole("button", { name: /create product/i }))
    await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalledTimes(1))
    const body = mockedApiClient.post.mock.calls[0][1] as Record<string, unknown>
    expect(body.vatRate).toBe("ZERO")
  })

  it("an edit shows the product's stored rate and sends the changed one on update", async () => {
    await openEditOf(product)
    expect(vatSelect()).toHaveTextContent("Zero rate (0%)")
    await choose("Reduced rate (5%)")
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))
    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    const body = mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>
    expect(body.vatRate).toBe("REDUCED")
  })

  it("an edit of a product whose rate was not returned sends no vatRate, so the server keeps the stored one", async () => {
    const { vatRate: _omitted, ...withoutRate } = product
    void _omitted
    await openEditOf(withoutRate)
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))
    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    const body = mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>
    expect("vatRate" in body && body.vatRate !== undefined).toBe(false)
  })

  it("turning stock tracking off still sends quantityInStock null (it must not silently regress)", async () => {
    await openEditOf({ ...product, quantityInStock: 5 })
    fireEvent.click(screen.getByRole("checkbox", { name: /track inventory/i }))
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))
    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    const body = mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>
    expect(body.quantityInStock).toBeNull()
  })
})
