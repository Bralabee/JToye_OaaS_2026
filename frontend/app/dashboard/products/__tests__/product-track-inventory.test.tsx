/**
 * RWO-016 / UXT-016 (37-39) — the product form says what it means about stock tracking.
 *
 * The API used to read "stop tracking" from an explicit `quantityInStock: null`, and a DTO cannot tell that
 * null from an omitted field, so every integrator's partial PUT that did not mention stock turned tracking
 * off. 37-39 made the intent its own field: `trackInventory` (absent = keep, false = stop and clear, true =
 * track with the quantity, which is then required). The server now KEEPS the stock when the field is absent,
 * so a form that went on sending only `quantityInStock: null` could no longer turn tracking off — the
 * vendor's ability would silently regress. The form therefore sends `trackInventory` on every create and
 * update, and looks exactly as it did.
 */
import { render, screen, waitFor, fireEvent } from "@testing-library/react"
import ProductsPage from "../page"
import { chooseTestShop, getWithShop } from "@/test-utils/shop-assignment"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

const product = {
  id: "p-1",
  tenantId: "tenant-1",
  sku: "SUYA-1",
  title: "Suya",
  ingredientsText: "beef, **peanuts**, yaji spice",
  allergenMask: 8,
  pricePennies: 900,
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
  quantityInStock: null as number | null,
  allergenWarnings: [],
  createdAt: "2026-10-12T12:00:00Z",
  updatedAt: "2026-10-12T12:00:00Z",
}

function listOf(content: unknown[]) {
  return { data: { content, totalElements: content.length, totalPages: 1 } }
}

const trackBox = () => screen.getByRole("checkbox", { name: /track inventory/i })

async function openCreate() {
  render(<ProductsPage />)
  await waitFor(() =>
    expect(screen.getAllByRole("button", { name: /add product/i }).length).toBeGreaterThan(0)
  )
  fireEvent.click(screen.getAllByRole("button", { name: /add product/i })[0])
  await screen.findByText("Create New Product")
  fireEvent.change(screen.getByLabelText("SKU"), { target: { value: "SUYA-1" } })
  fireEvent.change(screen.getByLabelText("Product Title"), { target: { value: "Suya" } })
  fireEvent.change(screen.getByLabelText("Ingredients"), { target: { value: "beef, yaji spice" } })
  fireEvent.change(screen.getByLabelText(/^Price/), { target: { value: "9.00" } })
  await chooseTestShop()
}

async function openEditOf(p: Record<string, unknown>) {
  mockedApiClient.get.mockResolvedValue(listOf([p]))
  render(<ProductsPage />)
  fireEvent.click(await screen.findByRole("button", { name: `Edit product ${p.title}` }))
  await screen.findByText("Edit Product")
}

async function submittedUpdate(): Promise<Record<string, unknown>> {
  fireEvent.click(screen.getByRole("button", { name: /update product/i }))
  await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
  return mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>
}

async function submittedCreate(): Promise<Record<string, unknown>> {
  fireEvent.click(screen.getByRole("button", { name: /create product/i }))
  await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalledTimes(1))
  return mockedApiClient.post.mock.calls[0][1] as Record<string, unknown>
}

describe("UXT-016: the product form sends trackInventory explicitly", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockImplementation(getWithShop([]) as jest.Mock)
    mockedApiClient.post.mockResolvedValue({ data: { ...product } })
    mockedApiClient.put.mockResolvedValue({ data: { ...product } })
  })

  it("unticking 'Track inventory' on a tracked product sends trackInventory false and no quantity", async () => {
    await openEditOf({ ...product, quantityInStock: 5 })
    expect(trackBox()).toBeChecked()
    fireEvent.click(trackBox())
    const body = await submittedUpdate()
    expect(body.trackInventory).toBe(false)
    expect(body.quantityInStock).toBeNull()
  })

  it("ticking 'Track inventory' with a quantity sends trackInventory true and that quantity", async () => {
    await openEditOf(product)
    expect(trackBox()).not.toBeChecked()
    fireEvent.click(trackBox())
    fireEvent.change(screen.getByLabelText("Stock Quantity"), { target: { value: "7" } })
    const body = await submittedUpdate()
    expect(body.trackInventory).toBe(true)
    expect(body.quantityInStock).toBe(7)
  })

  it("saving a tracked product untouched restates trackInventory true with its stored quantity", async () => {
    await openEditOf({ ...product, quantityInStock: 12 })
    const body = await submittedUpdate()
    expect(body.trackInventory).toBe(true)
    expect(body.quantityInStock).toBe(12)
  })

  it("a new product left untracked is created with trackInventory false and no quantity", async () => {
    await openCreate()
    const body = await submittedCreate()
    expect(body.trackInventory).toBe(false)
    expect(body.quantityInStock).toBeNull()
  })

  it("a new product tracked with a quantity is created with trackInventory true and that quantity", async () => {
    await openCreate()
    fireEvent.click(trackBox())
    fireEvent.change(screen.getByLabelText("Stock Quantity"), { target: { value: "3" } })
    const body = await submittedCreate()
    expect(body.trackInventory).toBe(true)
    expect(body.quantityInStock).toBe(3)
  })

  it("the form looks as before: one 'Track inventory' checkbox, and the quantity field only while it is ticked", async () => {
    await openEditOf(product)
    expect(screen.getAllByRole("checkbox", { name: /track inventory/i })).toHaveLength(1)
    expect(screen.queryByLabelText("Stock Quantity")).not.toBeInTheDocument()
    fireEvent.click(trackBox())
    expect(screen.getByLabelText("Stock Quantity")).toBeInTheDocument()
    expect(screen.queryByText(/trackInventory/)).not.toBeInTheDocument()
  })
})
