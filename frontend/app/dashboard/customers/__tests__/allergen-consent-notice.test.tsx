/**
 * UK GDPR Art. 9 — the allergen checkboxes carry a consent notice.
 *
 * WHY THIS TEST EXISTS: the notice is the only thing telling a vendor that
 * allergy details are health data and that the consent duty is theirs (they are
 * the controller — see docs/legal/article-9-allergen-basis.md). It is pure copy,
 * so nothing else would fail if someone deleted it during a redesign. This
 * asserts it renders in the same dialog as the checkboxes it qualifies, because
 * a notice on a different screen from the control is not a notice.
 */
import { render, screen, fireEvent, within } from "@testing-library/react"
import CustomersPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockResolvedValue({ data: { content: [], totalElements: 0 } } as never)
})

const openAddCustomerDialog = async () => {
  render(<CustomersPage />)
  // Wait for the button, not for the api call: the mock resolves the moment it is
  // invoked, but setLoading(false) only runs in the finally block afterwards, so
  // waiting on the call leaves the page still rendering its loading spinner.
  const addButtons = await screen.findAllByRole("button", { name: /add customer/i })
  fireEvent.click(addButtons[0])
}

describe("allergen consent notice", () => {
  it("tells the vendor allergy details are health data", async () => {
    await openAddCustomerDialog()

    const notice = await screen.findByRole("note")
    expect(notice).toHaveTextContent(/health data/i)
  })

  it("names explicit consent and places the duty on the vendor", async () => {
    await openAddCustomerDialog()

    const notice = await screen.findByRole("note")
    expect(notice).toHaveTextContent(/explicit consent/i)
    // "you're responsible for that consent" — the controller determination is
    // that the vendor holds the Art. 9(2) condition, not the platform.
    expect(notice).toHaveTextContent(/responsible/i)
  })

  it("tells the vendor what to do when consent is withdrawn", async () => {
    await openAddCustomerDialog()

    expect(await screen.findByRole("note")).toHaveTextContent(/withdraw/i)
  })

  it("renders in the same dialog as the allergen checkboxes it qualifies", async () => {
    await openAddCustomerDialog()

    const notice = await screen.findByRole("note")
    const dialog = notice.closest('[role="dialog"]')
    expect(dialog).not.toBeNull()
    // A checkbox for one of the 14 FSA allergens must live in that same dialog.
    expect(within(dialog as HTMLElement).getByText("Milk")).toBeInTheDocument()
  })
})
