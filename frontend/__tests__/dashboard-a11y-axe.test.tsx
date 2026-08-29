/**
 * A11Y-5 (E1) — a stack-free, per-PR axe scan of key vendor-dashboard pages.
 *
 * WHY THIS LIVES HERE AND NOT UNDER `app/dashboard/__tests__/`. This file is
 * owned by a remediation batch whose write boundary is `frontend/**` MINUS
 * `app/dashboard/**` — it may IMPORT dashboard page components (read-only)
 * but must not add a file inside that tree. Living in the project's existing
 * top-level `__tests__/` — the same home as `contrast-literals.test.ts` and
 * `axe-instrument.test.tsx` — is also where every other cross-cutting a11y
 * gate in this repo already lives.
 *
 * WHY THIS CAN BLOCK A PR WHEN `e2e/public-a11y.spec.ts` DELIBERATELY CANNOT
 * COVER THE DASHBOARD. That file's own header is explicit: it is scoped to
 * D-09's unauthenticated public surfaces, and the vendor dashboard is
 * "DELIBERATELY OUT" of it — a live-stack Playwright login there would break
 * its stack-free CI contract. jest-axe needs no server, no session, no
 * Keycloak realm: it renders the page component under jsdom with mocked data
 * (the SAME recipe `app/dashboard/__tests__/page.test.tsx`,
 * `products-orders-shop-scope.test.tsx` and the orders-page test already
 * use), so it can gate the dashboard on every PR without any of that.
 *
 * THREE "KEY" PAGES, NOT ALL ELEVEN. `/dashboard` (the overview a vendor
 * lands on), `/dashboard/orders` (the highest-traffic operational screen)
 * and `/dashboard/products` (the vendor's own content, including the image
 * upload path) are the pages a vendor spends the most time on. The full
 * eleven-route sweep already exists as a LIVE-STACK Playwright layout check
 * (`e2e/dashboard-mobile.spec.ts`); widening it to axe is future work, not
 * scope-crept into this file.
 *
 * NON-VACUITY FIRST, ALWAYS — same reasoning as `public-a11y.spec.ts`'s
 * `scanSurface()`: a page stuck on its loading spinner has almost no DOM and
 * would report a false "clean" scan. Each page's real, loaded heading is
 * asserted before axe ever runs.
 *
 * THE GATE IS PROVEN ABLE TO FAIL, WITH THIS FILE'S OWN SETUP. Reusing the
 * generic proof in `__tests__/axe-instrument.test.tsx` would only show that
 * `axe()`/`toHaveNoViolations` work in the abstract — it says nothing about
 * whether THIS file's mocks (recharts, api-client, shop-context) accidentally
 * strip away the very nodes a violation would live on. The break-arm test
 * below runs a deliberately unlabelled control through the SAME render +
 * scan path used for the real pages.
 */
import { render, screen, waitFor } from "@testing-library/react"
import { axe, toHaveNoViolations } from "jest-axe"
import DashboardPage from "@/app/dashboard/page"
import OrdersPage from "@/app/dashboard/orders/page"
import ProductsPage from "@/app/dashboard/products/page"
import apiClient from "@/lib/api-client"
import { getShopContext } from "@/lib/shop-context"

expect.extend(toHaveNoViolations)

// axe walks the whole rendered subtree under jsdom with no real layout —
// give it room, matching axe-instrument.test.tsx's own budget.
const AXE_TIMEOUT_MS = 30_000

// Mock recharts — jsdom has no SVG layout, and DashboardPage renders charts.
// Passthrough divs preserve DOM structure/children (so nothing a violation
// could live on disappears) without needing ResizeObserver-driven layout.
jest.mock("recharts", () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  PieChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Pie: () => <div />,
  Cell: () => <div />,
  BarChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Bar: () => <div />,
  XAxis: () => <div />,
  YAxis: () => <div />,
  CartesianGrid: () => <div />,
  Tooltip: () => <div />,
  Legend: () => <div />,
}))

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/lib/shop-context", () => ({
  ALL_SHOPS_CONTEXT: "all",
  getShopContext: jest.fn(() => "all"),
  setShopContext: jest.fn(),
  subscribeShopContext: jest.fn(() => () => {}),
}))
const mockedGetShopContext = getShopContext as jest.MockedFunction<typeof getShopContext>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

// The orders page's SSE stream needs a real EventSource jsdom does not have.
jest.mock("@/hooks/use-order-events", () => ({
  useOrderEvents: jest.fn(),
}))

if (typeof global.ResizeObserver === "undefined") {
  global.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
}

const SHOP_A = "aaaaaaaa-1111-1111-1111-111111111111"

const shops = [{ id: SHOP_A, name: "Peckham Kitchen", published: true }]

const orderRow = {
  id: "11111111-2222-3333-4444-555555555555",
  tenantId: "tenant-1",
  shopId: SHOP_A,
  orderNumber: "ORD-00000000-20260712-F7C16B7F",
  status: "PENDING",
  customerName: "Jane Doe",
  customerEmail: "jane@example.com",
  totalAmountPennies: 2149,
  itemCount: 2,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
}

const product = {
  id: "p-a",
  tenantId: "t-1",
  sku: "SKU-A",
  title: "Jollof Rice",
  ingredientsText: "flour, water",
  allergenMask: 0,
  pricePennies: 500,
  description: null,
  imageUrl: null,
  additionalImageUrls: [],
  category: "Mains",
  displayOrder: 0,
  available: true,
  featured: false,
  preparationTimeMinutes: null,
  dietaryTags: null,
  shopId: SHOP_A,
  quantityInStock: null,
  createdAt: "2026-07-01T10:00:00Z",
  updatedAt: "2026-07-01T10:00:00Z",
}

const defaultMock = (url: string) => {
  if (url === "/api/v1/financial-transactions/summary") {
    return Promise.resolve({
      data: {
        totalRevenuePennies: 0,
        totalExpensesPennies: 0,
        netAmountPennies: 0,
        totalVatPennies: 0,
        transactionCount: 0,
        vatBreakdown: [],
      },
    })
  }
  if (url.startsWith("/api/v1/onboarding/me")) {
    return Promise.reject({ response: { status: 404 } })
  }
  if (url.startsWith("/api/v1/orders")) {
    return Promise.resolve({
      data: { content: [orderRow], totalPages: 1, totalElements: 1 },
    })
  }
  if (url.startsWith("/api/v1/products")) {
    return Promise.resolve({
      data: { content: [product], totalPages: 1, totalElements: 1 },
    })
  }
  if (url.startsWith("/api/v1/shops")) {
    return Promise.resolve({
      data: { content: shops, totalPages: 1, totalElements: shops.length },
    })
  }
  return Promise.resolve({ data: { content: [], totalPages: 0, totalElements: 0 } })
}

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockImplementation(defaultMock as jest.Mock)
  mockedGetShopContext.mockReturnValue("all")
})

/**
 * A DEBT LEDGER, NOT A CERTIFICATE — same shape as `contrast-literals.test.ts`'s
 * `UNASSERTED_SITES`. Each entry below is a REAL, MEASURED finding (not
 * assumed), with the exact root cause and why this batch cannot close it.
 *
 * `heading-order` on all three pages, ROOT-CAUSED: every one renders its own
 * `<h1>` (e.g. `app/dashboard/page.tsx:257`) immediately followed by a
 * `<Card><CardTitle>…` stat/section title, and `CardTitle`
 * (`components/ui/card.tsx`) hardcodes `<h3>` unconditionally — so every
 * dashboard page using `<h1>` + `Card` skips `<h2>` and axe correctly flags
 * it (moderate, `<h3>Shops</h3>` etc. — verified by direct axe-result
 * inspection, not inferred). This is NOT one page's typo; the same
 * `CardTitle` primitive is used dashboard-wide, so the same skip is almost
 * certainly present on every other route this scan does not yet cover.
 *
 * NOT FIXED HERE, for two independent reasons rather than one excuse:
 *   1. This remediation batch's write boundary is `frontend/**` MINUS
 *      `app/dashboard/**` — the `<h1>`/`<h2>` structure that would need to
 *      change lives entirely inside that excluded tree.
 *   2. Even from outside that boundary, widening `CardTitle`'s own semantic
 *      level (e.g. an accepting a `level`/`as` prop) is a design-system
 *      change touching EVERY page that renders a `<Card>` — dashboard and
 *      otherwise — which this charter names as something to escalate, not
 *      decide unilaterally inside a QA batch.
 * Recorded as a concrete, actionable follow-up (fix the h1->h2->h3 structure
 * per dashboard page, or give `CardTitle` a level prop) rather than silently
 * passed — the ledger tests below keep it from being forgotten OR quietly
 * absorbed by a future fix that never updates this file.
 */
const ALLOWLISTED_RULES: Record<string, string[]> = {
  dashboard: ["heading-order"],
  orders: ["heading-order"],
  products: ["heading-order"],
}

/**
 * Two directions, mirroring `contrast-literals.test.ts`'s ledger discipline:
 *  - no UNLISTED rule may fire (a new/different violation is a real
 *    regression this gate must still catch, allowlist or not);
 *  - every LISTED rule must still actually be firing (so the ledger cannot
 *    quietly outlive the finding it records — a page that stops producing
 *    `heading-order` must have this list trimmed, not left stale).
 */
function assertOnlyAllowlisted(
  page: keyof typeof ALLOWLISTED_RULES,
  violations: { id: string }[]
) {
  const allowed = new Set(ALLOWLISTED_RULES[page])
  const found = new Set(violations.map((v) => v.id))

  const unexpected = violations.filter((v) => !allowed.has(v.id))
  expect(unexpected.map((v) => v.id)).toEqual([])

  // Any id here means the ledger has outlived its finding for this page —
  // trim ALLOWLISTED_RULES rather than leaving a dead exemption in place.
  const stale = [...allowed].filter((id) => !found.has(id))
  expect(stale).toEqual([])
}

describe("Vendor dashboard — stack-free per-PR axe scan (A11Y-5)", () => {
  it("/dashboard renders its real heading (non-vacuity control) and clears axe", async () => {
    const { container } = render(<DashboardPage />)
    await waitFor(() => {
      expect(screen.getByText("Dashboard")).toBeInTheDocument()
    })

    const results = await axe(container)
    assertOnlyAllowlisted("dashboard", results.violations)
  }, AXE_TIMEOUT_MS)

  it("/dashboard/orders renders its seeded order (non-vacuity control) and clears axe", async () => {
    const { container } = render(<OrdersPage />)
    await waitFor(() => {
      expect(screen.getByText(orderRow.orderNumber)).toBeInTheDocument()
    })

    const results = await axe(container)
    assertOnlyAllowlisted("orders", results.violations)
  }, AXE_TIMEOUT_MS)

  it("/dashboard/products renders its seeded product (non-vacuity control) and clears axe", async () => {
    const { container } = render(<ProductsPage />)
    await waitFor(() => {
      expect(screen.getByText(product.title)).toBeInTheDocument()
    })

    const results = await axe(container)
    assertOnlyAllowlisted("products", results.violations)
  }, AXE_TIMEOUT_MS)
})

/**
 * THE GATE CAN FAIL, PROVEN WITH THIS FILE'S OWN RENDER + SCAN PATH. See the
 * file header for why the generic `axe-instrument.test.tsx` proof is not
 * enough on its own here.
 */
describe("dashboard axe gate — instrument falsification", () => {
  it(
    "BREAK ARM: an unlabelled control run through this file's own axe() call reports violations",
    async () => {
      function BrokenDashboardFixture() {
        return (
          <div>
            <button type="button" data-testid="probe-button" />
          </div>
        )
      }
      const { container } = render(<BrokenDashboardFixture />)
      expect(screen.getByTestId("probe-button")).toBeInTheDocument()

      const results = await axe(container)
      const ids = results.violations.map((v) => v.id)
      expect(results.violations.length).toBeGreaterThan(0)
      expect(ids).toContain("button-name")
    },
    AXE_TIMEOUT_MS
  )
})
