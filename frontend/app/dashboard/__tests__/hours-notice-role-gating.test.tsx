/**
 * #961: the dashboard-home hours notice's button opens the shop editor, which the API refuses a STAFF member
 * (edit needs SHOP_MANAGER on the shop). The notice is therefore shown only for shops the caller can edit.
 * A CONTROL arm (a Group admin, same fixture) shows the notice does render, so its absence for STAFF is the
 * role gate and not a render that never loaded the shops.
 */
import React from "react"
import { render, screen, waitFor } from "@testing-library/react"
import DashboardPage from "../page"
import apiClient from "@/lib/api-client"
import type { MyAccess } from "@/lib/shops-api"

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

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

let mockSwitcher: { loading: boolean; access: MyAccess | null; shops: never[] } | null = null
jest.mock("@/components/dashboard/shop-switcher-provider", () => ({
  useOptionalShopSwitcherData: () => mockSwitcher,
}))

const legacyShop = {
  id: "s-legacy",
  tenantId: "tenant-1",
  name: "Legacy Grill",
  address: "1 High St",
  slug: "legacy-grill",
  published: true,
  minimumOrderPennies: 0,
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z",
  hoursSource: "LEGACY_ALWAYS_OPEN",
}

function api() {
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url.startsWith("/api/v1/shops")) {
      return Promise.resolve({ data: { content: [legacyShop], totalElements: 1, totalPages: 1, last: true, number: 0, size: 100 } })
    }
    if (url.startsWith("/api/v1/onboarding/me")) return Promise.reject({ response: { status: 404 } })
    return Promise.resolve({ data: { content: [], totalElements: 0 } })
  }) as jest.Mock)
}

function access(over: Partial<MyAccess>): MyAccess {
  return {
    userId: "u1",
    groupAdmin: false,
    grantedShopIds: ["s-legacy"],
    tenantWideRole: null,
    businessName: null,
    shopRoles: { "s-legacy": "STAFF" },
    ...over,
  }
}

describe("dashboard home hours notice follows the caller's role (#961)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    api()
  })

  it("CONTROL: a Group admin sees the notice for the legacy shop", async () => {
    mockSwitcher = { loading: false, shops: [], access: access({ groupAdmin: true, grantedShopIds: null, shopRoles: null }) }
    render(<DashboardPage />)
    expect(await screen.findByTestId("hours-notice-card")).toBeInTheDocument()
  })

  it("a STAFF member is not shown a notice whose button opens an editor the API refuses them", async () => {
    mockSwitcher = { loading: false, shops: [], access: access({}) }
    const { container } = render(<DashboardPage />)
    // Instrument: the shops list was requested AND the page left its loading state, so the missing card is
    // the role gate, not a list that had not resolved yet.
    await waitFor(() => expect(container.querySelector(".animate-spin")).not.toBeInTheDocument())
    expect(mockedApiClient.get).toHaveBeenCalledWith(expect.stringContaining("/api/v1/shops"))
    expect(screen.queryByTestId("hours-notice-card")).not.toBeInTheDocument()
  })

  it("a SHOP_MANAGER of the shop still sees it", async () => {
    mockSwitcher = { loading: false, shops: [], access: access({ shopRoles: { "s-legacy": "SHOP_MANAGER" } }) }
    render(<DashboardPage />)
    expect(await screen.findByTestId("hours-notice-card")).toBeInTheDocument()
  })
})
