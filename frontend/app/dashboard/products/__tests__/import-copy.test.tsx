/**
 * UXT-026 (#799), UI-SPEC § B6 — the dashboard half: import a CSV menu INTO a chosen shop, and copy
 * a menu to another shop.
 *
 * 37-13 made the server refuse a shop-less CSV row ("shop required") and added POST
 * /products/copy. Until this plan the import page posted the file with no shopId, so every
 * dashboard import created nothing (WINDOWS #27), and nothing in the UI could copy a menu.
 *
 * Import: the page says which shop it imports into. With a shop selected in the switcher that is
 * the shop; with All shops a required "Shop to import into" Select appears and the upload control
 * stays disabled beside the permanent hint "Choose a shop to import into". No request may leave
 * without a target (T-37-39), whatever route the file takes to the input.
 *
 * Copy: "Copy to another shop…" on a row (that product) and in the header (the selected shop's
 * whole menu) opens a dialog whose target Select excludes the source shop; the result reports the
 * server's created, skipped and failed counts with zero counts omitted.
 */
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import ImportProductsPage from "../import/page"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"
import { getShopContext } from "@/lib/shop-context"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/lib/shop-context", () => ({
  ALL_SHOPS_CONTEXT: "all",
  getShopContext: jest.fn(() => "all"),
  setShopContext: jest.fn(),
  subscribeShopContext: jest.fn(() => () => {}),
}))
const mockedGetShopContext = getShopContext as jest.MockedFunction<typeof getShopContext>

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

const SHOP_A = "aaaaaaaa-1111-1111-1111-111111111111"
const SHOP_B = "bbbbbbbb-2222-2222-2222-222222222222"
const SHOP_C = "cccccccc-3333-3333-3333-333333333333"
const LONG_NAME = "The Very Long Named Peckham Rye Jollof And Suya Kitchen Ltd." // 60 characters

const SHOPS = [
  { id: SHOP_A, name: "Peckham Kitchen", published: true },
  { id: SHOP_B, name: "Brixton Bakery", published: true },
  { id: SHOP_C, name: "Camden Grill", published: true },
]

const product = (id: string, sku: string, title: string, shopId: string | null) => ({
  id,
  tenantId: "t-1",
  sku,
  title,
  ingredientsText: "rice, tomato",
  allergenMask: 0,
  pricePennies: 500,
  vatRate: "STANDARD",
  description: null,
  imageUrl: null,
  additionalImageUrls: [],
  category: "Mains",
  displayOrder: 0,
  available: true,
  featured: false,
  preparationTimeMinutes: null,
  dietaryTags: null,
  shopId,
  quantityInStock: null,
  allergenWarnings: [],
  createdAt: "2026-10-09T10:00:00Z",
  updatedAt: "2026-10-09T10:00:00Z",
})

const PRODUCTS = [
  product("p-a1", "JOL-1", "Jollof Rice", SHOP_A),
  product("p-a2", "SUY-1", "Suya Skewers", SHOP_A),
  product("p-b1", "BRD-1", "Sourdough Loaf", SHOP_B),
]

function serve(shops = SHOPS) {
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url.startsWith("/api/v1/shops")) {
      return Promise.resolve({ data: { content: shops, totalPages: 1, totalElements: shops.length } })
    }
    if (url.startsWith("/api/v1/products")) {
      const shopId = new URLSearchParams(url.split("?")[1] ?? "").get("shopId")
      const rows = shopId ? PRODUCTS.filter((p) => p.shopId === shopId) : PRODUCTS
      return Promise.resolve({ data: { content: rows, totalPages: 1, totalElements: rows.length } })
    }
    return Promise.resolve({ data: {} })
  }) as jest.Mock)
}

const IMPORT_RESULT = { totalRows: 1, successCount: 1, errorCount: 0, created: [], errors: [] }

function csvFile() {
  return new File(["title,price_pounds\nJollof Rice,5.00\n"], "menu.csv", { type: "text/csv" })
}

function fileInput(container: HTMLElement) {
  const input = container.querySelector<HTMLInputElement>('input[type="file"][accept=".csv,text/csv"]')
  if (!input) throw new Error("no CSV file input")
  return input
}

const uploadButton = () => screen.getByRole("button", { name: /click to select csv file/i })
const csvPosts = () =>
  mockedApiClient.post.mock.calls.filter(([url]) => String(url).startsWith("/api/v1/products/bulk/csv"))
const copyPosts = () =>
  mockedApiClient.post.mock.calls.filter(([url]) => String(url) === "/api/v1/products/copy")

/** Radix renders options only while open; ArrowDown is the path jsdom drives. */
async function choose(combobox: HTMLElement, optionName: string) {
  fireEvent.keyDown(combobox, { key: "ArrowDown" })
  fireEvent.click(await screen.findByRole("option", { name: optionName }))
  await waitFor(() => expect(combobox).toHaveTextContent(optionName))
}

beforeEach(() => {
  jest.clearAllMocks()
  mockedGetShopContext.mockReturnValue("all")
  serve()
  mockedApiClient.post.mockResolvedValue({ data: IMPORT_RESULT })
})

describe("UXT-026 import: the CSV goes into a chosen shop (UI-SPEC § B6 'CSV import target')", () => {
  it("with a shop selected, says 'Importing into: {Shop}' and the upload carries that shopId", async () => {
    mockedGetShopContext.mockReturnValue(SHOP_A)
    const { container } = render(<ImportProductsPage />)

    const line = await screen.findByTestId("import-target")
    await waitFor(() => expect(line).toHaveTextContent("Importing into: Peckham Kitchen"))
    expect(within(line).getByText("Peckham Kitchen").tagName).toBe("STRONG")
    expect(screen.queryByRole("combobox", { name: "Shop to import into" })).not.toBeInTheDocument()
    expect(uploadButton()).toBeEnabled()

    fireEvent.change(fileInput(container), { target: { files: [csvFile()] } })
    await waitFor(() => expect(csvPosts()).toHaveLength(1))
    expect(String(csvPosts()[0][0])).toBe(`/api/v1/products/bulk/csv?shopId=${SHOP_A}`)
    expect((csvPosts()[0][1] as FormData).get("file")).toBeInstanceOf(File)
  })

  it("on All shops, a required 'Shop to import into' Select appears and Import is disabled beside the permanent hint", async () => {
    render(<ImportProductsPage />)

    const target = await screen.findByRole("combobox", { name: "Shop to import into" })
    expect(target).toHaveAttribute("aria-required", "true")
    expect(uploadButton()).toBeDisabled()
    expect(screen.getByText("Choose a shop to import into")).toBeInTheDocument()
    expect(uploadButton()).toHaveAccessibleDescription("Choose a shop to import into")
    expect(screen.queryByTestId("import-target")).not.toBeInTheDocument()
  })

  it("on All shops, no request is sent without a target, even when a file reaches the input", async () => {
    const { container } = render(<ImportProductsPage />)
    // Waits on the upload control, not on the new Select, so the old page (which posted the file
    // with no shopId at all, WINDOWS #27) fails here on the request rather than on a missing control.
    await screen.findByRole("button", { name: /click to select csv file/i })

    fireEvent.change(fileInput(container), { target: { files: [csvFile()] } })
    // Give any request the page might send the chance to leave.
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(csvPosts()).toHaveLength(0)
  })

  it("choosing a shop enables Import, names the target, and the upload carries the chosen shopId", async () => {
    const { container } = render(<ImportProductsPage />)
    const target = await screen.findByRole("combobox", { name: "Shop to import into" })

    await choose(target, "Brixton Bakery")
    expect(uploadButton()).toBeEnabled()
    expect(screen.getByTestId("import-target")).toHaveTextContent("Importing into: Brixton Bakery")
    expect(screen.queryByText("Choose a shop to import into")).not.toBeInTheDocument()

    fireEvent.change(fileInput(container), { target: { files: [csvFile()] } })
    await waitFor(() => expect(csvPosts()).toHaveLength(1))
    expect(String(csvPosts()[0][0])).toBe(`/api/v1/products/bulk/csv?shopId=${SHOP_B}`)
  })

  it("a 60-character shop name wraps in the target line instead of being cut off (375px backstop)", async () => {
    serve([{ id: SHOP_A, name: LONG_NAME, published: true }])
    mockedGetShopContext.mockReturnValue(SHOP_A)
    render(<ImportProductsPage />)

    const line = await screen.findByTestId("import-target")
    await waitFor(() => expect(line).toHaveTextContent(LONG_NAME))
    expect(line).toHaveClass("break-words")
    expect(line.className).not.toMatch(/truncate|line-clamp|whitespace-nowrap/)
  })
})

describe("UXT-026 copy to another shop (UI-SPEC § B6 'Copy to shop')", () => {
  it("the row action opens a dialog whose target Select excludes the product's own shop", async () => {
    render(<ProductsPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Copy Jollof Rice to another shop…" }))

    const dialog = await screen.findByRole("dialog", { name: "Copy 1 product to another shop" })
    expect(within(dialog).getByText("1 product will be copied with its allergens, ingredients and photos.")).toBeInTheDocument()
    const target = within(dialog).getByRole("combobox", { name: "Copy to" })
    fireEvent.keyDown(target, { key: "ArrowDown" })
    const listbox = await screen.findByRole("listbox")
    expect(within(listbox).getAllByRole("option").map((o) => o.textContent)).toEqual([
      "Brixton Bakery",
      "Camden Grill",
    ])
  })

  it("Copy is disabled until a target is chosen, reads 'Copying…' in flight, and sends this product only", async () => {
    let resolveCopy: (value: unknown) => void = () => {}
    mockedApiClient.post.mockImplementation((url: string) =>
      url === "/api/v1/products/copy"
        ? new Promise((resolve) => { resolveCopy = resolve })
        : Promise.resolve({ data: IMPORT_RESULT })
    )
    render(<ProductsPage />)
    fireEvent.click(await screen.findByRole("button", { name: "Copy Jollof Rice to another shop…" }))
    const dialog = await screen.findByRole("dialog", { name: "Copy 1 product to another shop" })

    const copy = within(dialog).getByRole("button", { name: "Copy 1 product" })
    expect(copy).toBeDisabled()
    await choose(within(dialog).getByRole("combobox", { name: "Copy to" }), "Brixton Bakery")
    expect(copy).toBeEnabled()
    fireEvent.click(copy)

    const pending = await within(dialog).findByRole("button", { name: "Copying…" })
    expect(pending).toBeDisabled()
    expect(copyPosts()).toHaveLength(1)
    expect(copyPosts()[0][1]).toEqual({ sourceShopId: SHOP_A, targetShopId: SHOP_B, productIds: ["p-a1"] })

    resolveCopy({ data: { created: 1, skipped: 0, failed: 0, items: [] } })
    expect(await within(dialog).findByRole("status")).toHaveTextContent("1 copied to Brixton Bakery.")
  })

  it("the header action copies the selected shop's whole menu: '{n} products will be copied…', no productIds", async () => {
    mockedGetShopContext.mockReturnValue(SHOP_A)
    mockedApiClient.post.mockResolvedValue({ data: { created: 2, skipped: 0, failed: 0, items: [] } })
    render(<ProductsPage />)
    await screen.findByText("Jollof Rice")
    fireEvent.click(screen.getByRole("button", { name: "Copy to another shop…" }))

    const dialog = await screen.findByRole("dialog", { name: "Copy 2 products to another shop" })
    expect(within(dialog).getByText("2 products will be copied with their allergens, ingredients and photos.")).toBeInTheDocument()
    await choose(within(dialog).getByRole("combobox", { name: "Copy to" }), "Camden Grill")
    fireEvent.click(within(dialog).getByRole("button", { name: "Copy 2 products" }))

    await waitFor(() => expect(copyPosts()).toHaveLength(1))
    expect(copyPosts()[0][1]).toEqual({ sourceShopId: SHOP_A, targetShopId: SHOP_C })
  })

  it("reports created, skipped and failed as the server returns them, in the toast and inline", async () => {
    mockedGetShopContext.mockReturnValue(SHOP_A)
    mockedApiClient.post.mockResolvedValue({ data: { created: 3, skipped: 2, failed: 1, items: [] } })
    render(<ProductsPage />)
    await screen.findByText("Jollof Rice")
    fireEvent.click(screen.getByRole("button", { name: "Copy to another shop…" }))
    const dialog = await screen.findByRole("dialog", { name: "Copy 2 products to another shop" })
    await choose(within(dialog).getByRole("combobox", { name: "Copy to" }), "Brixton Bakery")
    fireEvent.click(within(dialog).getByRole("button", { name: "Copy 2 products" }))

    const expected =
      "3 copied to Brixton Bakery. 2 skipped because Brixton Bakery already has them. 1 couldn't be copied."
    expect(await within(dialog).findByRole("status")).toHaveTextContent(expected)
    expect(mockToast).toHaveBeenCalledWith(expect.objectContaining({ description: expected }))
  })

  it("omits zero counts (a partial copy reports each non-zero count only)", async () => {
    mockedGetShopContext.mockReturnValue(SHOP_A)
    mockedApiClient.post.mockResolvedValue({ data: { created: 0, skipped: 2, failed: 0, items: [] } })
    render(<ProductsPage />)
    await screen.findByText("Jollof Rice")
    fireEvent.click(screen.getByRole("button", { name: "Copy to another shop…" }))
    const dialog = await screen.findByRole("dialog", { name: "Copy 2 products to another shop" })
    await choose(within(dialog).getByRole("combobox", { name: "Copy to" }), "Brixton Bakery")
    fireEvent.click(within(dialog).getByRole("button", { name: "Copy 2 products" }))

    const status = await within(dialog).findByRole("status")
    expect(status).toHaveTextContent("2 skipped because Brixton Bakery already has them.")
    expect(status.textContent).not.toMatch(/copied to|couldn't be copied|^0 | 0 /)
  })

  it("a refused copy says why inline, and nothing is reported as copied", async () => {
    mockedGetShopContext.mockReturnValue(SHOP_A)
    mockedApiClient.post.mockRejectedValue({
      response: { status: 403, data: { detail: "You do not manage the shop you are copying to." } },
    })
    render(<ProductsPage />)
    await screen.findByText("Jollof Rice")
    fireEvent.click(screen.getByRole("button", { name: "Copy to another shop…" }))
    const dialog = await screen.findByRole("dialog", { name: "Copy 2 products to another shop" })
    await choose(within(dialog).getByRole("combobox", { name: "Copy to" }), "Brixton Bakery")
    fireEvent.click(within(dialog).getByRole("button", { name: "Copy 2 products" }))

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("You do not manage the shop you are copying to.")
    expect(within(dialog).queryByRole("status")).not.toBeInTheDocument()
  })

  it("a 60-character target name wraps in the result line (375px backstop) inside the w-full max-w-lg dialog", async () => {
    serve([...SHOPS, { id: "dddddddd-4444-4444-4444-444444444444", name: LONG_NAME, published: true }])
    mockedGetShopContext.mockReturnValue(SHOP_A)
    mockedApiClient.post.mockResolvedValue({ data: { created: 2, skipped: 0, failed: 0, items: [] } })
    render(<ProductsPage />)
    await screen.findByText("Jollof Rice")
    fireEvent.click(screen.getByRole("button", { name: "Copy to another shop…" }))
    const dialog = await screen.findByRole("dialog", { name: "Copy 2 products to another shop" })
    expect(dialog).toHaveClass("w-full")
    expect(dialog).toHaveClass("max-w-lg")
    expect(within(dialog).getByText(/will be copied with their allergens/)).toHaveClass("break-words")
    await choose(within(dialog).getByRole("combobox", { name: "Copy to" }), LONG_NAME)
    fireEvent.click(within(dialog).getByRole("button", { name: "Copy 2 products" }))

    const status = await within(dialog).findByRole("status")
    expect(status).toHaveTextContent(`2 copied to ${LONG_NAME}.`)
    expect(status).toHaveClass("break-words")
  })

  it("no header copy on All shops (no source shop), and no row copy for a product with no shop", async () => {
    PRODUCTS.push(product("p-x", "OLD-1", "Legacy Pie", null))
    try {
      render(<ProductsPage />)
      await screen.findByText("Legacy Pie")
      expect(screen.queryByRole("button", { name: "Copy to another shop…" })).not.toBeInTheDocument()
      expect(screen.queryByRole("button", { name: "Copy Legacy Pie to another shop…" })).not.toBeInTheDocument()
      expect(screen.getByRole("button", { name: "Copy Jollof Rice to another shop…" })).toBeInTheDocument()
    } finally {
      PRODUCTS.pop()
    }
  })
})
