/**
 * 37-21 Task 3: the dashboard home shows the hours notice from the shops list it already loads
 * (UI-SPEC K4 loading assumption): nothing until the list resolves, the card once it has, and no card
 * when the load fails.
 */

import React from "react"
import { render, screen, waitFor } from "@testing-library/react"
import DashboardPage from "../page"
import apiClient from "@/lib/api-client"

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

function api({ shops }: { shops: "ok" | "fail" | "pending" }) {
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url.startsWith("/api/v1/shops")) {
      if (shops === "fail") return Promise.reject({ response: { status: 503 } })
      if (shops === "pending") return new Promise(() => {})
      return Promise.resolve({ data: { content: [legacyShop], totalElements: 1, totalPages: 1, last: true, number: 0, size: 100 } })
    }
    if (url.startsWith("/api/v1/onboarding/me")) return Promise.reject({ response: { status: 404 } })
    if (url === "/api/v1/staff/me") return Promise.resolve({ data: { userId: "u1", groupAdmin: true, grantedShopIds: null } })
    return Promise.resolve({ data: { content: [], totalElements: 0 } })
  }) as jest.Mock)
}

describe("dashboard home hours notice (UI-SPEC K4 loading assumption)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it("renders the card once the shops list resolves with a legacy shop", async () => {
    api({ shops: "ok" })
    render(<DashboardPage />)
    expect(await screen.findByText("1 shop has no opening hours")).toBeInTheDocument()
    expect(screen.getByTestId("hours-notice-card")).toBeInTheDocument()
  })

  it("renders no card while the shops list is pending", async () => {
    api({ shops: "pending" })
    const { container } = render(<DashboardPage />)
    expect(container.querySelector(".animate-spin")).toBeInTheDocument()
    expect(screen.queryByTestId("hours-notice-card")).not.toBeInTheDocument()
  })

  it("renders no card when the shops list fails to load", async () => {
    api({ shops: "fail" })
    render(<DashboardPage />)
    await waitFor(() => expect(screen.getByText("Dashboard")).toBeInTheDocument())
    expect(screen.queryByTestId("hours-notice-card")).not.toBeInTheDocument()
  })
})
