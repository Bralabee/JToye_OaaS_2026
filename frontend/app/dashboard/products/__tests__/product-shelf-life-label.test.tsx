/**
 * #960 (swarm SW-ADE-03) — the allergen (PPDS / Natasha's Law) label could not be printed: the 422 named
 * database columns and a product id, and the product form had no field for the shelf life or the
 * use-by / best-before choice the label is dated from (V41 added the columns; nothing in the vendor UI
 * set them).
 *
 * The form now has both inputs, validated as a pair; and when the label endpoint still answers 422
 * errors/incomplete-label-data, the dialog shows the server's plain-words detail and a button that opens
 * the product's edit form.
 */
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"
import {
  DURABILITY_PAIR_COPY,
  SHELF_LIFE_MAX_DAYS,
  SHELF_LIFE_RANGE_COPY,
  parseDurabilityInputs,
} from "../product-form-schema"
import { TEST_SHOP, chooseTestShop } from "@/test-utils/shop-assignment"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/lib/shop-context", () => ({
  ALL_SHOPS_CONTEXT: "all",
  getShopContext: jest.fn(() => "all"),
  setShopContext: jest.fn(),
  subscribeShopContext: jest.fn(() => () => {}),
}))

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

const product = {
  id: "ea66e02b-4f1c-4d0e-9a3b-0c9d1e2f3a4b",
  tenantId: "tenant-1",
  sku: "JOL-1",
  title: "Jollof Rice",
  ingredientsText: "rice, **fish**",
  allergenMask: 64,
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
  shopId: TEST_SHOP.id,
  quantityInStock: null,
  allergenWarnings: [],
  createdAt: "2026-10-19T12:00:00Z",
  updatedAt: "2026-10-19T12:00:00Z",
}

const PLAIN_422 =
  "This allergen label cannot be printed yet. Add a shelf life (in days) and choose use-by or best-before on the product."

function api(rows: unknown[], label?: () => Promise<unknown>) {
  return (url: string) => {
    if (String(url).includes("/label") && label) return label()
    const content = String(url).startsWith("/api/v1/shops") ? [TEST_SHOP] : rows
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
  fireEvent.change(screen.getByLabelText("SKU"), { target: { value: "JOL-1" } })
  fireEvent.change(screen.getByLabelText("Product Title"), { target: { value: "Jollof Rice" } })
  fireEvent.change(screen.getByLabelText("Ingredients"), { target: { value: "rice, **fish**" } })
  fireEvent.change(screen.getByLabelText(/^Price/), { target: { value: "11.00" } })
  await chooseTestShop()
}

const shelfInput = () => screen.getByLabelText("Shelf life (days)") as HTMLInputElement
const durabilitySelect = () => screen.getByLabelText("Use by or best before") as HTMLSelectElement

async function submittedCreate(): Promise<Record<string, unknown>> {
  fireEvent.click(screen.getByRole("button", { name: /create product/i }))
  await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalledTimes(1))
  return mockedApiClient.post.mock.calls[0][1] as Record<string, unknown>
}

describe("#960: the product form has the label's dating inputs", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockImplementation(api([]) as jest.Mock)
    mockedApiClient.post.mockResolvedValue({ data: { ...product, shelfLifeDays: 3, durabilityType: "USE_BY" } })
    mockedApiClient.put.mockResolvedValue({ data: { ...product, shelfLifeDays: 3, durabilityType: "USE_BY" } })
  })

  it("the create form offers Shelf life (days) and Use by / Best before, with help saying what they are for", async () => {
    await openCreateForm()
    expect(shelfInput()).toHaveAttribute("type", "number")
    expect(within(durabilitySelect()).getByRole("option", { name: "Use by" })).toBeInTheDocument()
    expect(within(durabilitySelect()).getByRole("option", { name: "Best before" })).toBeInTheDocument()
    expect(screen.getByRole("group", { name: /allergen label dating/i })).toHaveAccessibleDescription(
      /needed to print this product's allergen label/i
    )
  })

  it("a product created with both sends shelfLifeDays and durabilityType", async () => {
    await openCreateForm()
    fireEvent.change(shelfInput(), { target: { value: "3" } })
    fireEvent.change(durabilitySelect(), { target: { value: "USE_BY" } })
    const body = await submittedCreate()
    expect(body.shelfLifeDays).toBe(3)
    expect(body.durabilityType).toBe("USE_BY")
  })

  it("best-before and a shelf life of 0 are sent as chosen", async () => {
    await openCreateForm()
    fireEvent.change(shelfInput(), { target: { value: "0" } })
    fireEvent.change(durabilitySelect(), { target: { value: "BEST_BEFORE" } })
    const body = await submittedCreate()
    expect(body.shelfLifeDays).toBe(0)
    expect(body.durabilityType).toBe("BEST_BEFORE")
  })

  it("leaving both blank is allowed and sends neither", async () => {
    await openCreateForm()
    const body = await submittedCreate()
    expect("shelfLifeDays" in body && body.shelfLifeDays !== undefined).toBe(false)
    expect("durabilityType" in body && body.durabilityType !== undefined).toBe(false)
  })

  it.each([
    ["a shelf life without a choice", "3", ""],
    ["a choice without a shelf life", "", "USE_BY"],
  ])("%s is refused beside the fields; nothing is sent", async (_name, days, kind) => {
    await openCreateForm()
    fireEvent.change(shelfInput(), { target: { value: days } })
    fireEvent.change(durabilitySelect(), { target: { value: kind } })
    fireEvent.click(screen.getByRole("button", { name: /create product/i }))

    expect(await screen.findByRole("alert")).toHaveTextContent(DURABILITY_PAIR_COPY)
    expect(shelfInput()).toHaveAttribute("aria-invalid", "true")
    expect(durabilitySelect()).toHaveAttribute("aria-invalid", "true")
    expect(durabilitySelect()).toHaveAccessibleDescription(DURABILITY_PAIR_COPY)
    expect(mockedApiClient.post).not.toHaveBeenCalled()
  })

  it.each(["3651", "-1", "2.5"])("a shelf life of %p is refused with the range copy", async (bad) => {
    await openCreateForm()
    fireEvent.change(shelfInput(), { target: { value: bad } })
    fireEvent.change(durabilitySelect(), { target: { value: "USE_BY" } })
    fireEvent.click(screen.getByRole("button", { name: /create product/i }))

    expect(await screen.findByRole("alert")).toHaveTextContent(SHELF_LIFE_RANGE_COPY)
    expect(mockedApiClient.post).not.toHaveBeenCalled()
  })

  it("the error clears as soon as either input changes", async () => {
    await openCreateForm()
    fireEvent.change(shelfInput(), { target: { value: "3" } })
    fireEvent.click(screen.getByRole("button", { name: /create product/i }))
    await screen.findByRole("alert")
    fireEvent.change(durabilitySelect(), { target: { value: "USE_BY" } })
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
  })

  it("editing a product shows its stored values; saved untouched they are not re-sent (the server keeps them)", async () => {
    mockedApiClient.get.mockImplementation(
      api([{ ...product, shelfLifeDays: 5, durabilityType: "BEST_BEFORE" }]) as jest.Mock
    )
    render(<ProductsPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Edit product Jollof Rice" }))
    await screen.findByText("Edit Product")

    expect(shelfInput().value).toBe("5")
    expect(durabilitySelect().value).toBe("BEST_BEFORE")
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))
    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    const body = mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>
    // Untouched (review F2): omitted, so a lone half or an out-of-bound legacy value cannot block the save.
    expect(body.shelfLifeDays).toBeUndefined()
    expect(body.durabilityType).toBeUndefined()
  })

  it("editing a product with nothing recorded shows blanks and sends neither (the server keeps what it has)", async () => {
    mockedApiClient.get.mockImplementation(api([product]) as jest.Mock)
    render(<ProductsPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Edit product Jollof Rice" }))
    await screen.findByText("Edit Product")

    expect(shelfInput().value).toBe("")
    expect(durabilitySelect().value).toBe("")
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))
    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    const body = mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>
    expect(body.shelfLifeDays).toBeUndefined()
    expect(body.durabilityType).toBeUndefined()
  })
})

/**
 * Review round 1, F2: the pair rule is for what the vendor TYPED. A product that already stores one of the
 * two (or a shelf life above today's bound) must still save when the vendor only changes something else.
 */
describe("#960 review F2: untouched stored durability values round-trip", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.put.mockResolvedValue({ data: { ...product } })
  })

  async function editOnlyThePrice(stored: Record<string, unknown>) {
    mockedApiClient.get.mockImplementation(api([{ ...product, ...stored }]) as jest.Mock)
    render(<ProductsPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Edit product Jollof Rice" }))
    await screen.findByText("Edit Product")
    fireEvent.change(screen.getByLabelText(/^Price/), { target: { value: "12.00" } })
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))
  }

  it.each([
    ["a shelf life with no use-by / best-before", { shelfLifeDays: 3 }],
    ["a use-by / best-before with no shelf life", { durabilityType: "USE_BY" }],
    ["a shelf life above the bound the form now enforces", { shelfLifeDays: 5000, durabilityType: "USE_BY" }],
  ])("saves a price change on a product storing %s", async (_name, stored) => {
    await editOnlyThePrice(stored)

    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    expect(screen.queryByText(DURABILITY_PAIR_COPY)).not.toBeInTheDocument()
    const body = mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>
    expect(body.pricePennies).toBe(1200)
    // Untouched: nothing is sent, so the server keeps exactly what it holds.
    expect(body.shelfLifeDays).toBeUndefined()
    expect(body.durabilityType).toBeUndefined()
    expect(body.clearDurability).toBeUndefined()
  })

  it("still enforces the pair once the vendor touches either input", async () => {
    mockedApiClient.get.mockImplementation(api([{ ...product, shelfLifeDays: 3 }]) as jest.Mock)
    render(<ProductsPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Edit product Jollof Rice" }))
    await screen.findByText("Edit Product")
    fireEvent.change(shelfInput(), { target: { value: "4" } })
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))

    expect(await screen.findByRole("alert")).toHaveTextContent(DURABILITY_PAIR_COPY)
    expect(mockedApiClient.put).not.toHaveBeenCalled()
  })
})

describe("#960: the label dialog on a product with no shelf life", () => {
  const PDF = new Blob(["%PDF-1.4"], { type: "application/pdf" })
  const createObjectURL = jest.fn(() => "blob:label")
  const revokeObjectURL = jest.fn()
  let anchorClick: jest.SpyInstance
  let labelReady = false

  const problem422 = () =>
    Promise.reject(
      Object.assign(new Error("Request failed with status code 422"), {
        response: {
          status: 422,
          data: new Blob(
            [
              JSON.stringify({
                type: "https://jtoye.uk/errors/incomplete-label-data",
                title: "Incomplete Label Data",
                status: 422,
                detail: PLAIN_422,
                missingFields: ["shelfLifeDays", "durabilityType"],
                productId: product.id,
              }),
            ],
            { type: "application/problem+json" }
          ),
        },
      })
    )

  beforeEach(() => {
    jest.clearAllMocks()
    labelReady = false
    Object.defineProperty(URL, "createObjectURL", { value: createObjectURL, configurable: true })
    Object.defineProperty(URL, "revokeObjectURL", { value: revokeObjectURL, configurable: true })
    anchorClick = jest.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {})
    mockedApiClient.get.mockImplementation(
      api([product], () => (labelReady ? Promise.resolve({ data: PDF }) : problem422())) as jest.Mock
    )
    mockedApiClient.put.mockImplementation(() => {
      labelReady = true
      return Promise.resolve({ data: { ...product, shelfLifeDays: 3, durabilityType: "USE_BY" } })
    })
  })

  afterEach(() => anchorClick.mockRestore())

  it("shows the server's plain words, with no product id or column name, and a way to fix it", async () => {
    render(<ProductsPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Download allergen label for Jollof Rice" }))
    const dialog = await screen.findByRole("dialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Download label" }))

    const notice = await within(dialog).findByTestId("label-incomplete")
    expect(notice).toHaveTextContent(PLAIN_422)
    expect(notice.textContent).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-/)
    expect(notice.textContent).not.toMatch(/shelf_life_days|durability_type/)
    expect(within(dialog).getByRole("button", { name: "Edit this product" })).toBeInTheDocument()
    expect(createObjectURL).not.toHaveBeenCalled()
  })

  it("the whole route: label refused, edit the product through the form, print it", async () => {
    render(<ProductsPage />)
    // 1. The vendor's dead end of the swarm run: download -> refusal.
    fireEvent.click(await screen.findByRole("button", { name: "Download allergen label for Jollof Rice" }))
    let dialog = await screen.findByRole("dialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Download label" }))
    fireEvent.click(await within(dialog).findByRole("button", { name: "Edit this product" }))

    // 2. The edit form is open on that product, with the two inputs that were missing.
    await screen.findByText("Edit Product")
    expect(shelfInput().value).toBe("")
    fireEvent.change(shelfInput(), { target: { value: "3" } })
    fireEvent.change(durabilitySelect(), { target: { value: "USE_BY" } })
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))
    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    expect(mockedApiClient.put.mock.calls[0][0]).toBe(`/api/v1/products/${product.id}`)
    const body = mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>
    expect(body.shelfLifeDays).toBe(3)
    expect(body.durabilityType).toBe("USE_BY")
    await waitFor(() => expect(screen.queryByText("Edit Product")).not.toBeInTheDocument())

    // 3. The label now downloads.
    fireEvent.click(screen.getByRole("button", { name: "Download allergen label for Jollof Rice" }))
    dialog = await screen.findByRole("dialog")
    expect(within(dialog).queryByTestId("label-incomplete")).not.toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole("button", { name: "Download label" }))
    await waitFor(() => expect(createObjectURL).toHaveBeenCalledWith(PDF))
    expect(anchorClick).toHaveBeenCalledTimes(1)
  })

  it("a date refusal is still shown under the date field, not as the incomplete-data notice", async () => {
    const detail = "productionDate 5 October 2026 is after today."
    mockedApiClient.get.mockImplementation(
      api([product], () =>
        Promise.reject(
          Object.assign(new Error("422"), {
            response: {
              status: 422,
              data: new Blob(
                [JSON.stringify({ type: "https://jtoye.uk/errors/invalid-production-date", detail })],
                { type: "application/problem+json" }
              ),
            },
          })
        )
      ) as jest.Mock
    )
    render(<ProductsPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Download allergen label for Jollof Rice" }))
    const dialog = await screen.findByRole("dialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Download label" }))

    await waitFor(() => expect(within(dialog).getByText(detail)).toBeInTheDocument())
    expect(within(dialog).queryByTestId("label-incomplete")).not.toBeInTheDocument()
  })
})

describe("#960: parseDurabilityInputs", () => {
  it("both blank is valid and yields neither value", () => {
    expect(parseDurabilityInputs("", "")).toEqual({ ok: true })
    expect(parseDurabilityInputs("  ", "")).toEqual({ ok: true })
  })

  it("a pair is parsed", () => {
    expect(parseDurabilityInputs("3", "USE_BY")).toEqual({ ok: true, shelfLifeDays: 3, durabilityType: "USE_BY" })
    expect(parseDurabilityInputs(" 0 ", "BEST_BEFORE")).toEqual({
      ok: true,
      shelfLifeDays: 0,
      durabilityType: "BEST_BEFORE",
    })
    expect(parseDurabilityInputs("3650", "USE_BY").ok).toBe(true)
  })

  it("one without the other is refused with the pair copy", () => {
    expect(parseDurabilityInputs("3", "")).toEqual({ ok: false, error: DURABILITY_PAIR_COPY })
    expect(parseDurabilityInputs("", "USE_BY")).toEqual({ ok: false, error: DURABILITY_PAIR_COPY })
  })

  it.each(["3651", "-1", "2.5", "1e2", "3abc", "99999"])("a shelf life of %p is out of range", (bad) => {
    expect(parseDurabilityInputs(bad, "USE_BY")).toEqual({ ok: false, error: SHELF_LIFE_RANGE_COPY })
  })

  it("the bound is the server's own @Max", () => {
    expect(SHELF_LIFE_MAX_DAYS).toBe(3650)
  })

  it("an unknown kind is refused", () => {
    expect(parseDurabilityInputs("3", "FOREVER").ok).toBe(false)
  })
})
