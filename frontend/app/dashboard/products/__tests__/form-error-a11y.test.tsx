/**
 * QA council 20260902-134741 — A11Y-7 (WCAG 3.3.1 Error Identification), the
 * products form only.
 *
 * The three (four, with price) field errors were bare
 * `<p className="text-sm text-red-600">{errors.X.message}</p>` with no id,
 * and the paired inputs carried no aria-invalid / aria-describedby — so a
 * screen-reader user tabbing into a rejected field heard nothing wrong with
 * it, while the customer checkout form does this correctly
 * (app/shop/[slug]/checkout/page.tsx:781-834, tested by
 * checkout-form-a11y.test.tsx — this file clones that shape).
 *
 * WHY COUNTS ARE COMPARED, NOT ONE FIELD SPOT-CHECKED. A fix on `title` alone
 * would pass a single-field assertion while sku/ingredients/price stayed
 * silent. The number of rendered error messages must equal the number of
 * inputs flagged invalid, with a FLOOR so a form that rendered nothing (0 == 0)
 * cannot pass.
 *
 * WHY THE ERROR STATE IS INDUCED FIRST. Probe 18's first run reported the
 * checkout attributes "absent" and was wrong — they are conditional on an
 * error existing. Submit empty, wait for a message, THEN read.
 *
 * The other six dashboard forms with the same shape are a named follow-up
 * (plan-frontend-a11y §9), not fixed blind here.
 */
import { render, screen, fireEvent, waitFor } from "@testing-library/react"
import ProductsPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

const FIELDS: Array<{ label: string | RegExp; message: string }> = [
  { label: "SKU", message: "SKU is required" },
  { label: "Product Title", message: "Title is required" },
  { label: "Ingredients", message: "Ingredients are required" },
  { label: /^Price/, message: "Price is required" },
]

async function openEmptyFormAndSubmit() {
  render(<ProductsPage />)
  await waitFor(() => expect(screen.getAllByRole("button", { name: /add product/i }).length).toBeGreaterThan(0))
  fireEvent.click(screen.getAllByRole("button", { name: /add product/i })[0])
  await screen.findByText("Create New Product")
  const title = screen.getByLabelText("Product Title")
  // Control: before validation runs nothing is flagged — the attribute is
  // conditional on an error, not stamped on every render.
  expect(title).not.toHaveAttribute("aria-invalid")
  fireEvent.click(screen.getByRole("button", { name: /create product/i }))
  await screen.findByText("Title is required")
}

describe("Products form — field errors are programmatically associated (A11Y-7)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockResolvedValue({ data: { content: [], totalElements: 0, totalPages: 0 } })
  })

  it.each(FIELDS)("$message: the input is aria-invalid and aria-describedby resolves to the message", async ({ label, message }) => {
    await openEmptyFormAndSubmit()
    const input = screen.getByLabelText(label)
    expect(input).toHaveAttribute("aria-invalid", "true")
    const describedBy = input.getAttribute("aria-describedby")
    expect(describedBy).toBeTruthy()
    const target = document.getElementById(describedBy as string)
    expect(target).not.toBeNull()
    expect(target).toHaveTextContent(message)
  })

  it("every rendered error message has a flagged input, and there are at least four of each", async () => {
    await openEmptyFormAndSubmit()
    const messages = FIELDS.map((f) => screen.getByText(f.message))
    const flagged = document.querySelectorAll('#product-form [aria-invalid="true"]')
    expect(messages.length).toBeGreaterThanOrEqual(4)
    expect(flagged.length).toBe(messages.length)
    // Nothing is submitted while the form is invalid.
    expect(mockedApiClient.post).not.toHaveBeenCalled()
  })
})
