/**
 * 37-36 (D-17, UXT-008, UI-SPEC § C2): the vendor is told why customers cannot see
 * their promotions, and is warned off putting a discount into an announcement.
 *
 * The storefront no longer renders promotions (checkout applies no discount). The
 * vendor's promotions are NOT deleted — the CRUD stays — so without a word of
 * explanation the dashboard would look as if promotions still went live. The panel
 * sits ABOVE the promotions list, and the announcement form carries the helper,
 * wired to the field by aria-describedby so a screen reader hears it on focus.
 *
 * Harness cloned from marketing-control-names.test.tsx (stable toast identity,
 * shop-context stub, URL-dispatching fake server).
 */
import { configure, render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import MarketingPage from "../marketing/page"
import apiClient from "@/lib/api-client"

configure({ asyncUtilTimeout: 5000 })

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

const SHOP_A = "aaaaaaaa-1111-1111-1111-111111111111"
const shops = [{ id: SHOP_A, tenantId: "t-1", name: "Peckham Kitchen", published: true }]
const DAY = 24 * 60 * 60 * 1000
const iso = (offsetDays: number) => new Date(Date.now() + offsetDays * DAY).toISOString()

const promo = {
  id: "promo-1",
  shopId: SHOP_A,
  label: "Summer 10% off",
  discountType: "PERCENTAGE",
  discountPercent: 10,
  discountAmountPennies: null,
  category: null,
  validFrom: iso(-30),
  validUntil: iso(30),
  active: true,
  createdAt: iso(-40),
}

const PANEL_HEADING = "Customers can't see your promotions yet"
const PANEL_BODY =
  "J'Toye doesn't apply discounts at checkout yet, so showing a promotion would advertise a price customers wouldn't be charged. Your promotions are saved here and will appear once checkout can apply them."
const HELPER =
  "Don't advertise discounts or prices here. Checkout can't apply them, so customers would be charged the full price."

function paged<T>(rows: T[]) {
  return { data: { content: rows, totalElements: rows.length, totalPages: 1, size: 20 } }
}

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url.startsWith("/api/v1/promotions")) return Promise.resolve(paged([promo]))
    if (url.startsWith("/api/v1/announcements")) return Promise.resolve(paged([]))
    if (url.startsWith("/api/v1/shops")) return Promise.resolve(paged(shops))
    return Promise.resolve(paged([]))
  }) as jest.Mock)
})

describe("Marketing — customers can't see promotions yet (UI-SPEC § C2, D-17)", () => {
  it("shows the info panel, with the UI-SPEC copy, ABOVE the promotions list", async () => {
    render(<MarketingPage />)
    // Control: the promotions list really rendered (the vendor's data is kept).
    await waitFor(() => expect(screen.getByText("Summer 10% off")).toBeInTheDocument())

    const panel = screen.getByTestId("promotions-hidden-notice")
    expect(within(panel).getByText(PANEL_HEADING)).toBeInTheDocument()
    expect(within(panel).getByText(PANEL_BODY)).toBeInTheDocument()
    expect(panel).toHaveClass("rounded-lg", "border", "border-slate-200", "bg-white", "p-4")
    // The Info icon is decorative; the words carry the meaning.
    const icon = panel.querySelector("svg")
    expect(icon).not.toBeNull()
    expect(icon).toHaveAttribute("aria-hidden", "true")

    // ABOVE the list: the panel precedes the promotions table in document order.
    const table = screen.getByRole("table")
    expect(panel.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it("keeps the promotion CRUD: create, edit and delete are all still offered", async () => {
    render(<MarketingPage />)
    await waitFor(() => expect(screen.getByText("Summer 10% off")).toBeInTheDocument())
    expect(screen.getAllByRole("button", { name: /create promotion/i }).length).toBeGreaterThan(0)
    expect(screen.getByRole("button", { name: "Edit promotion Summer 10% off" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Delete promotion Summer 10% off" })).toBeInTheDocument()
  })

  it("is not shown on the Announcements tab", async () => {
    render(<MarketingPage />)
    await waitFor(() => expect(screen.getByText("Summer 10% off")).toBeInTheDocument())
    fireEvent.click(screen.getByRole("button", { name: /^announcements$/i }))
    await waitFor(() => expect(screen.queryByText("Summer 10% off")).not.toBeInTheDocument())
    expect(screen.queryByTestId("promotions-hidden-notice")).not.toBeInTheDocument()
  })
})

describe("Marketing — the announcement form warns against advertising a discount (UI-SPEC § C2)", () => {
  async function openAnnouncementForm() {
    render(<MarketingPage />)
    await waitFor(() => expect(screen.getByText("Summer 10% off")).toBeInTheDocument())
    fireEvent.click(screen.getByRole("button", { name: /^announcements$/i }))
    const create = await screen.findAllByRole("button", { name: /create announcement/i })
    fireEvent.click(create[0])
    return screen.findByRole("dialog")
  }

  it("renders the helper under the announcement text and describes the text fields with it", async () => {
    const dialog = await openAnnouncementForm()
    const helper = within(dialog).getByText(HELPER)
    expect(helper).toHaveClass("text-sm", "text-slate-600")
    expect(helper.id).not.toBe("")

    const body = within(dialog).getByLabelText(/body/i)
    const title = within(dialog).getByLabelText(/title/i)
    expect(body.getAttribute("aria-describedby")?.split(/\s+/)).toContain(helper.id)
    expect(title.getAttribute("aria-describedby")?.split(/\s+/)).toContain(helper.id)
    expect(body).toHaveAccessibleDescription(HELPER)
  })
})
