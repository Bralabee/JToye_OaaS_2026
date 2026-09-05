/**
 * QA council 20260902-134741 — A11Y-3 (WCAG 4.1.2 Name, Role, Value).
 *
 * The edit and delete controls in both marketing tables were
 * `<Button variant="ghost" size="sm" className="h-8 w-8 p-0">` whose only
 * child was a lucide <svg> — no aria-label, no title, no sr-only text — so a
 * destructive action had no accessible name at all (axe button-name, 2 nodes
 * on desktop and mobile; probes/a11y/03 + 21). This route is also outside
 * the blocking per-PR jsdom axe gate, which is why a static-lint-clean tree
 * still shipped it: jsx-a11y cannot compute a name through <Button><Pencil/>.
 *
 * Names are ENTITY-QUALIFIED ("Edit promotion Summer 10%"), not bare
 * "Edit"/"Delete" — two controls per row times N rows with one name would be
 * A11Y-4's duplicate-name shape on this table. Same pattern as
 * customers/page.tsx's "View orders for {name}".
 *
 * Harness cloned from marketing-status-filter-honesty.test.tsx (stable toast
 * identity, shop-context stub, URL-dispatching fake server).
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
const announcement = {
  id: "ann-1",
  shopId: SHOP_A,
  title: "Bank holiday hours",
  body: null,
  validFrom: iso(-30),
  validUntil: iso(30),
  active: true,
  createdAt: iso(-40),
}

function paged<T>(rows: T[]) {
  return { data: { content: rows, totalElements: rows.length, totalPages: 1, size: 20 } }
}

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url.startsWith("/api/v1/promotions")) return Promise.resolve(paged([promo]))
    if (url.startsWith("/api/v1/announcements")) return Promise.resolve(paged([announcement]))
    if (url.startsWith("/api/v1/shops")) return Promise.resolve(paged(shops))
    return Promise.resolve(paged([]))
  }) as jest.Mock)
})

const rowOf = (text: string) => {
  const row = screen.getByText(text).closest("tr")
  if (!row) throw new Error(`no table row contains "${text}"`)
  return row
}

describe("Marketing tables — edit/delete controls carry entity-qualified names (A11Y-3)", () => {
  it("promotion row: 'Edit promotion {label}' and 'Delete promotion {label}'", async () => {
    render(<MarketingPage />)
    await waitFor(() => expect(screen.getByText("Summer 10% off")).toBeInTheDocument())
    const row = rowOf("Summer 10% off")
    expect(within(row).getByRole("button", { name: "Edit promotion Summer 10% off" })).toBeInTheDocument()
    expect(within(row).getByRole("button", { name: "Delete promotion Summer 10% off" })).toBeInTheDocument()
    // No control in the row is left unnamed (the icon-only shape that shipped).
    for (const b of within(row).getAllByRole("button")) expect(b).toHaveAccessibleName()
  })

  it("announcement row: 'Edit announcement {title}' and 'Delete announcement {title}'", async () => {
    render(<MarketingPage />)
    await waitFor(() => expect(screen.getByText("Summer 10% off")).toBeInTheDocument())
    fireEvent.click(screen.getByRole("button", { name: /^announcements$/i }))
    await waitFor(() => expect(screen.getByText("Bank holiday hours")).toBeInTheDocument())
    const row = rowOf("Bank holiday hours")
    expect(within(row).getByRole("button", { name: "Edit announcement Bank holiday hours" })).toBeInTheDocument()
    expect(within(row).getByRole("button", { name: "Delete announcement Bank holiday hours" })).toBeInTheDocument()
    for (const b of within(row).getAllByRole("button")) expect(b).toHaveAccessibleName()
  })

  it("the icons inside those controls are decorative (aria-hidden), so the name is the label alone", async () => {
    render(<MarketingPage />)
    await waitFor(() => expect(screen.getByText("Summer 10% off")).toBeInTheDocument())
    const del = within(rowOf("Summer 10% off")).getByRole("button", { name: "Delete promotion Summer 10% off" })
    const svg = del.querySelector("svg")
    expect(svg).not.toBeNull()
    expect(svg).toHaveAttribute("aria-hidden", "true")
  })
})
