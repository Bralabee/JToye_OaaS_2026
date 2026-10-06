/**
 * 31.1-19 Tasks 2 and 3 — #861 vendor side (D-16, D-17).
 *
 * D-16: "may contain" (cross-contact) is its own 14-bit mask (31.1-14, V74). The form records it
 * in its own fieldset and sends it as mayContainMask; the DECLARED allergenMask carries only the
 * declared ticks. One is never computed from the other (T-31.1-69).
 *
 * NULL and 0 are different statements (31.1-14): absent/null = not recorded, 0 = "the vendor
 * recorded no cross-contact risk". An untouched fieldset must not turn the first into the second.
 */
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"
import { MAY_CONTAIN_HELP_COPY } from "@/lib/allergen-copy"
import { MAY_CONTAIN_MASK_MAX, mayContainMaskSchema } from "../product-form-schema"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

const SESAME_BIT = 1 << 10
const MILK_BIT = 1 << 6

const product = {
  id: "p-1",
  tenantId: "tenant-1",
  sku: "JOL-1",
  title: "Jollof Rice",
  ingredientsText: "rice, butter (MILK), pepper",
  allergenMask: MILK_BIT,
  pricePennies: 450,
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
  createdAt: "2026-10-03T12:00:00Z",
  updatedAt: "2026-10-03T12:00:00Z",
}

function listOf(content: unknown[]) {
  return { data: { content, totalElements: content.length, totalPages: 1 } }
}

const declaredGroup = () => screen.getByRole("group", { name: /^Allergens/ })
const mayContainGroup = () => screen.getByRole("group", { name: /^May contain \(cross-contact\)/ })

async function openCreate() {
  render(<ProductsPage />)
  await waitFor(() =>
    expect(screen.getAllByRole("button", { name: /add product/i }).length).toBeGreaterThan(0)
  )
  fireEvent.click(screen.getAllByRole("button", { name: /add product/i })[0])
  await screen.findByText("Create New Product")
  fireEvent.change(screen.getByLabelText("SKU"), { target: { value: "JOL-1" } })
  fireEvent.change(screen.getByLabelText("Product Title"), { target: { value: "Jollof Rice" } })
  fireEvent.change(screen.getByLabelText("Ingredients"), {
    target: { value: "rice, butter (MILK), pepper" },
  })
  fireEvent.change(screen.getByLabelText(/^Price/), { target: { value: "4.50" } })
}

async function openEditOf(p: Record<string, unknown>) {
  mockedApiClient.get.mockResolvedValue(listOf([p]))
  render(<ProductsPage />)
  fireEvent.click(await screen.findByRole("button", { name: `Edit product ${p.title}` }))
  await screen.findByText("Edit Product")
}

describe("D-16: the separate 'May contain' checkboxes", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockResolvedValue(listOf([]))
    mockedApiClient.post.mockResolvedValue({ data: { ...product, allergenWarnings: [] } })
    mockedApiClient.put.mockResolvedValue({ data: { ...product, allergenWarnings: [] } })
  })

  it("is its own named group of 14 checkboxes, apart from the declared allergens, with its helper text", async () => {
    await openCreate()
    const mayContain = mayContainGroup()
    const declared = declaredGroup()
    expect(mayContain).not.toBe(declared)
    expect(declared.contains(mayContain)).toBe(false)
    expect(mayContain.contains(declared)).toBe(false)
    expect(within(mayContain).getAllByRole("checkbox")).toHaveLength(14)
    expect(within(declared).getAllByRole("checkbox")).toHaveLength(14)
    expect(mayContain).toHaveAccessibleDescription(MAY_CONTAIN_HELP_COPY)
  })

  it("ticking Sesame there sends mayContainMask with the Sesame bit; allergenMask carries the declared ticks only", async () => {
    await openCreate()
    fireEvent.click(within(declaredGroup()).getByRole("checkbox", { name: "Milk" }))
    fireEvent.click(within(mayContainGroup()).getByRole("checkbox", { name: "Sesame" }))
    // The declared Sesame box is untouched by the may-contain tick.
    expect(within(declaredGroup()).getByRole("checkbox", { name: "Sesame" })).not.toBeChecked()

    fireEvent.click(screen.getByRole("button", { name: /create product/i }))

    await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalledTimes(1))
    const body = mockedApiClient.post.mock.calls[0][1] as Record<string, unknown>
    expect(body.allergenMask).toBe(MILK_BIT)
    expect(body.mayContainMask).toBe(SESAME_BIT)
  })

  it("an untouched fieldset on a new product sends null (not recorded), never 0", async () => {
    await openCreate()
    fireEvent.click(screen.getByRole("button", { name: /create product/i }))

    await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalledTimes(1))
    const body = mockedApiClient.post.mock.calls[0][1] as Record<string, unknown>
    expect(body.mayContainMask).toBeNull()
  })

  it("editing a product with mayContainMask null shows none ticked and saves null if untouched", async () => {
    await openEditOf({ ...product, mayContainMask: null })
    for (const box of within(mayContainGroup()).getAllByRole("checkbox")) {
      expect(box).not.toBeChecked()
    }
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))

    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    const body = mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>
    expect(body.mayContainMask).toBeNull()
    expect(body.allergenMask).toBe(MILK_BIT)
  })

  it("editing a product whose mayContainMask is absent (not recorded) also saves null", async () => {
    const { mayContainMask: _omit, ...withoutField } = { ...product, mayContainMask: undefined }
    await openEditOf(withoutField)
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))

    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    expect((mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>).mayContainMask).toBeNull()
  })

  it("0 stays 0", async () => {
    await openEditOf({ ...product, mayContainMask: 0 })
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))

    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    expect((mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>).mayContainMask).toBe(0)
  })

  it("a stored Sesame shows ticked under May contain only; unticking it records 0", async () => {
    await openEditOf({ ...product, mayContainMask: SESAME_BIT })
    const sesame = within(mayContainGroup()).getByRole("checkbox", { name: "Sesame" })
    expect(sesame).toBeChecked()
    expect(within(declaredGroup()).getByRole("checkbox", { name: "Sesame" })).not.toBeChecked()

    fireEvent.click(sesame)
    fireEvent.click(screen.getByRole("button", { name: /update product/i }))

    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    const body = mockedApiClient.put.mock.calls[0][1] as Record<string, unknown>
    expect(body.mayContainMask).toBe(0)
    expect(body.allergenMask).toBe(MILK_BIT)
  })
})

describe("D-16: mayContainMaskSchema mirrors CreateProductRequest @Min(0) @Max(16383)", () => {
  it("accepts null (not recorded), 0 and the full 14-bit mask", () => {
    expect(MAY_CONTAIN_MASK_MAX).toBe(16383)
    expect(mayContainMaskSchema.safeParse(null).success).toBe(true)
    expect(mayContainMaskSchema.safeParse(0).success).toBe(true)
    expect(mayContainMaskSchema.safeParse(16383).success).toBe(true)
  })

  it("rejects a 15th bit, a negative and a fraction", () => {
    expect(mayContainMaskSchema.safeParse(16384).success).toBe(false)
    expect(mayContainMaskSchema.safeParse(-1).success).toBe(false)
    expect(mayContainMaskSchema.safeParse(1.5).success).toBe(false)
  })
})

/**
 * Task 3 — D-17: the label asks for the production date.
 *
 * The clock is fixed at 2026-10-03T23:30Z, which is 00:30 on 4 October in London (BST): a
 * default computed in UTC, or in the browser's zone on a UTC machine, would say the 3rd and
 * date a label a day early. Only Date is faked; timers stay real so the async UI settles.
 */
describe("D-17: Download allergen label asks for the production date", () => {
  const LONDON_TODAY = "2026-10-04"
  const PDF = new Blob(["%PDF-1.4"], { type: "application/pdf" })
  const createObjectURL = jest.fn(() => "blob:label")
  const revokeObjectURL = jest.fn()
  let anchorClick: jest.SpyInstance

  beforeEach(() => {
    jest.clearAllMocks()
    jest.useFakeTimers({
      now: new Date("2026-10-03T23:30:00Z"),
      doNotFake: [
        "setTimeout",
        "clearTimeout",
        "setInterval",
        "clearInterval",
        "setImmediate",
        "clearImmediate",
        "queueMicrotask",
        "nextTick",
        "requestAnimationFrame",
        "cancelAnimationFrame",
        "requestIdleCallback",
        "cancelIdleCallback",
        "performance",
        "hrtime",
      ],
    })
    Object.defineProperty(URL, "createObjectURL", { value: createObjectURL, configurable: true })
    Object.defineProperty(URL, "revokeObjectURL", { value: revokeObjectURL, configurable: true })
    anchorClick = jest.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {})
    mockedApiClient.get.mockImplementation((url: string) =>
      url.includes("/label")
        ? Promise.resolve({ data: PDF })
        : Promise.resolve(listOf([{ ...product, mayContainMask: SESAME_BIT }]))
    )
  })

  afterEach(() => {
    anchorClick.mockRestore()
    jest.useRealTimers()
  })

  const labelCalls = () =>
    mockedApiClient.get.mock.calls.filter(([url]) => String(url).includes("/label"))

  async function openLabelDialog() {
    render(<ProductsPage />)
    fireEvent.click(
      await screen.findByRole("button", { name: "Download allergen label for Jollof Rice" })
    )
    return screen.findByRole("dialog")
  }

  it("the trigger keeps its accessible name and opens a dialog instead of downloading", async () => {
    const dialog = await openLabelDialog()
    expect(within(dialog).getByLabelText("Production date")).toBeInTheDocument()
    expect(labelCalls()).toHaveLength(0)
    expect(createObjectURL).not.toHaveBeenCalled()
  })

  it("defaults to today's Europe/London date, which is also the latest date allowed (00:30 BST case)", async () => {
    const dialog = await openLabelDialog()
    const input = within(dialog).getByLabelText("Production date") as HTMLInputElement
    expect(input.type).toBe("date")
    expect(input.value).toBe(LONDON_TODAY)
    expect(input.max).toBe(LONDON_TODAY)
  })

  it("confirming requests ?productionDate=YYYY-MM-DD as a blob and downloads it", async () => {
    const dialog = await openLabelDialog()
    fireEvent.click(within(dialog).getByRole("button", { name: "Download label" }))

    await waitFor(() => expect(labelCalls()).toHaveLength(1))
    expect(labelCalls()[0][0]).toBe(`/api/v1/products/p-1/label?productionDate=${LONDON_TODAY}`)
    expect(labelCalls()[0][1]).toEqual({ responseType: "blob" })
    await waitFor(() => expect(createObjectURL).toHaveBeenCalledWith(PDF))
    expect(anchorClick).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
  })

  it("a date the vendor picks is the date requested", async () => {
    const dialog = await openLabelDialog()
    fireEvent.change(within(dialog).getByLabelText("Production date"), {
      target: { value: "2026-10-02" },
    })
    fireEvent.click(within(dialog).getByRole("button", { name: "Download label" }))

    await waitFor(() => expect(labelCalls()).toHaveLength(1))
    expect(labelCalls()[0][0]).toBe("/api/v1/products/p-1/label?productionDate=2026-10-02")
  })

  it("a future date is refused before any request, under the field", async () => {
    const dialog = await openLabelDialog()
    const input = within(dialog).getByLabelText("Production date")
    fireEvent.change(input, { target: { value: "2026-10-05" } })
    fireEvent.click(within(dialog).getByRole("button", { name: "Download label" }))

    await waitFor(() => expect(input).toHaveAttribute("aria-invalid", "true"))
    expect(input).toHaveAccessibleDescription(/future/i)
    expect(labelCalls()).toHaveLength(0)
  })

  it("a 422 from the server is shown under the date field; nothing is downloaded", async () => {
    const detail = "The use-by date for this production date has already passed."
    mockedApiClient.get.mockImplementation((url: string) =>
      url.includes("/label")
        ? Promise.reject({
            response: {
              status: 422,
              data: new Blob(
                [
                  JSON.stringify({
                    type: "https://api.jtoye.uk/errors/invalid-production-date",
                    title: "Invalid production date",
                    status: 422,
                    detail,
                    field: "productionDate",
                  }),
                ],
                { type: "application/problem+json" }
              ),
            },
          })
        : Promise.resolve(listOf([{ ...product, mayContainMask: SESAME_BIT }]))
    )
    const dialog = await openLabelDialog()
    const input = within(dialog).getByLabelText("Production date")
    fireEvent.change(input, { target: { value: "2026-09-01" } })
    fireEvent.click(within(dialog).getByRole("button", { name: "Download label" }))

    await waitFor(() => expect(input).toHaveAttribute("aria-invalid", "true"))
    expect(input).toHaveAccessibleDescription(detail)
    expect(createObjectURL).not.toHaveBeenCalled()
    expect(anchorClick).not.toHaveBeenCalled()
    expect(screen.getByRole("dialog")).toBeInTheDocument()
  })
})
