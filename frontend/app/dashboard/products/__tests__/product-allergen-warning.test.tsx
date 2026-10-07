/**
 * 31.1-19 Task 1 — #787 vendor side (D-09, D-18, Pitfalls 6 and 11).
 *
 * The persona typed "rice, butter (MILK), pepper", ticked nothing, and their own products list
 * then said "No allergens". Since 31.1-06 the save SUCCEEDS and returns a typed warning naming
 * Milk; this file proves the vendor is shown it, can act on it, and that the list stops saying
 * "No allergens" for that dish.
 *
 * The server is mocked at the api-client seam with the exact warning shape 31.1-06 returns
 * (ProductSaveAllergenWarningIntegrationTest), so these tests exercise the form's handling, not
 * the reconciliation itself.
 */
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

const MILK_WARNING = {
  code: "UNDECLARED_INGREDIENT_ALLERGEN",
  allergenBit: 6,
  allergen: "Milk",
  message: "The ingredients name Milk, but it is not ticked as an allergen. Tick it, or check the ingredients.",
}

const baseProduct = {
  tenantId: "tenant-1",
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
  createdAt: "2026-10-03T12:00:00Z",
  updatedAt: "2026-10-03T12:00:00Z",
}

const PERSONA_INGREDIENTS = "rice, butter (MILK), pepper"

const savedWithWarning = {
  ...baseProduct,
  id: "p-1",
  sku: "JOL-1",
  title: "Jollof Rice",
  ingredientsText: PERSONA_INGREDIENTS,
  allergenMask: 0,
  allergenWarnings: [MILK_WARNING],
}

function listOf(content: unknown[]) {
  return { data: { content, totalElements: content.length, totalPages: 1 } }
}

async function openCreateAndSubmitPersonaDish() {
  render(<ProductsPage />)
  await waitFor(() =>
    expect(screen.getAllByRole("button", { name: /add product/i }).length).toBeGreaterThan(0)
  )
  fireEvent.click(screen.getAllByRole("button", { name: /add product/i })[0])
  await screen.findByText("Create New Product")
  fireEvent.change(screen.getByLabelText("SKU"), { target: { value: "JOL-1" } })
  fireEvent.change(screen.getByLabelText("Product Title"), { target: { value: "Jollof Rice" } })
  fireEvent.change(screen.getByLabelText("Ingredients"), { target: { value: PERSONA_INGREDIENTS } })
  fireEvent.change(screen.getByLabelText(/^Price/), { target: { value: "4.50" } })
  fireEvent.click(screen.getByRole("button", { name: /create product/i }))
}

function declaredGroup() {
  return screen.getByRole("group", { name: /^Allergens/ })
}

describe("#787 vendor side: the save-time warning (D-09)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockResolvedValue(listOf([]))
    mockedApiClient.post.mockResolvedValue({ data: savedWithWarning })
  })

  it("a save whose response carries a warning shows an alert naming Milk, with Tick Milk and Keep as it is", async () => {
    await openCreateAndSubmitPersonaDish()

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent("The ingredients mention Milk, but Milk is not ticked")
    expect(within(alert).getByRole("button", { name: "Tick Milk" })).toBeInTheDocument()
    expect(within(alert).getByRole("button", { name: "Keep as it is" })).toBeInTheDocument()
    // Focus is moved to the alert so a keyboard or screen-reader user lands on it.
    await waitFor(() => expect(alert).toHaveFocus())
    // The save itself happened once, with the vendor's own (empty) declaration.
    expect(mockedApiClient.post).toHaveBeenCalledTimes(1)
    expect(mockedApiClient.post.mock.calls[0][1]).toMatchObject({ allergenMask: 0 })
    // The dialog stays open: the vendor has a decision to make.
    expect(screen.getByRole("dialog")).toBeInTheDocument()
  })

  it("'Tick Milk' ticks the Milk box and re-saves the SAME product with the Milk bit set", async () => {
    mockedApiClient.put.mockResolvedValue({
      data: { ...savedWithWarning, allergenMask: 64, allergenWarnings: [] },
    })
    await openCreateAndSubmitPersonaDish()
    const alert = await screen.findByRole("alert")

    fireEvent.click(within(alert).getByRole("button", { name: "Tick Milk" }))

    await waitFor(() => expect(mockedApiClient.put).toHaveBeenCalledTimes(1))
    // A re-save of the product just created, not a second create.
    expect(mockedApiClient.post).toHaveBeenCalledTimes(1)
    expect(mockedApiClient.put.mock.calls[0][0]).toBe("/api/v1/products/p-1")
    expect(mockedApiClient.put.mock.calls[0][1]).toMatchObject({
      allergenMask: 64,
      ingredientsText: PERSONA_INGREDIENTS,
    })
    // The agreement clears the warning and closes the form.
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument())
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
  })

  it("the Milk box is ticked by the explicit action, never by the warning arriving", async () => {
    // The re-save never resolves, so the form stays open to be read.
    mockedApiClient.put.mockImplementation(() => new Promise(() => {}))
    await openCreateAndSubmitPersonaDish()
    const alert = await screen.findByRole("alert")
    const milk = within(declaredGroup()).getByRole("checkbox", { name: "Milk" })
    expect(milk).not.toBeChecked()

    fireEvent.click(within(alert).getByRole("button", { name: "Tick Milk" }))

    await waitFor(() =>
      expect(within(declaredGroup()).getByRole("checkbox", { name: "Milk" })).toBeChecked()
    )
  })

  it("'Keep as it is' closes without saving again", async () => {
    await openCreateAndSubmitPersonaDish()
    const alert = await screen.findByRole("alert")

    fireEvent.click(within(alert).getByRole("button", { name: "Keep as it is" }))

    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument())
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
    expect(mockedApiClient.post).toHaveBeenCalledTimes(1)
    expect(mockedApiClient.put).not.toHaveBeenCalled()
  })

  it("control: a save with no warning closes the form with no alert", async () => {
    mockedApiClient.post.mockResolvedValue({
      data: { ...savedWithWarning, allergenMask: 64, allergenWarnings: [] },
    })
    await openCreateAndSubmitPersonaDish()

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
  })
})

describe("Pitfall 11: the vendor list never says 'No allergens' for the persona's dish (D-18)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it("a product with an undeclared ingredient allergen reads 'Ingredients name: MILK – not ticked'", async () => {
    mockedApiClient.get.mockResolvedValue(listOf([savedWithWarning]))
    render(<ProductsPage />)

    expect(await screen.findByText("Ingredients name: MILK – not ticked")).toBeInTheDocument()
    expect(screen.queryByText("No allergens declared")).not.toBeInTheDocument()
    expect(screen.queryByText("No allergens")).not.toBeInTheDocument()
  })

  it("an empty mask with no warning reads 'No allergens declared'; the bare old label is gone", async () => {
    mockedApiClient.get.mockResolvedValue(
      listOf([{ ...savedWithWarning, id: "p-2", sku: "WAT-1", title: "Water", allergenWarnings: [] }])
    )
    render(<ProductsPage />)

    expect(await screen.findByText("No allergens declared")).toBeInTheDocument()
    expect(screen.queryByText("No allergens")).not.toBeInTheDocument()
  })

  it("declared allergens stay as badges beside the not-ticked line", async () => {
    mockedApiClient.get.mockResolvedValue(
      listOf([{ ...savedWithWarning, allergenMask: 1 /* Gluten */ }])
    )
    render(<ProductsPage />)

    expect(await screen.findByText("Gluten")).toBeInTheDocument()
    expect(screen.getByText("Ingredients name: MILK – not ticked")).toBeInTheDocument()
  })
})

describe("Pitfall 6: the ingredients field says how to emphasise an allergen", () => {
  it("helper text mentions CAPITALS and **double asterisks** and is linked to the field", async () => {
    jest.clearAllMocks()
    mockedApiClient.get.mockResolvedValue(listOf([]))
    render(<ProductsPage />)
    await waitFor(() =>
      expect(screen.getAllByRole("button", { name: /add product/i }).length).toBeGreaterThan(0)
    )
    fireEvent.click(screen.getAllByRole("button", { name: /add product/i })[0])
    await screen.findByText("Create New Product")

    const field = screen.getByLabelText("Ingredients")
    const describedBy = field.getAttribute("aria-describedby")
    expect(describedBy).toBeTruthy()
    const help = document.getElementById(describedBy as string)
    expect(help).toHaveTextContent("CAPITALS")
    expect(help).toHaveTextContent("**double asterisks**")
  })
})
