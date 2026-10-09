/**
 * D-26 (37-09) — the one-time welcome after an invitation is accepted.
 *
 * The accept page signs the person in through the ordinary Keycloak flow with
 * callbackUrl /dashboard?joined=1. The dashboard then says "You've joined {business}.
 * You can work on {shop / all shops}." built ONLY from GET /api/v1/staff/me and the
 * grant-scoped shop list, and drops the parameter so the welcome happens once.
 *
 * The parameter is a trigger, never a source of words: these cases prove the copy
 * follows the server's answer (a group admin reads "all shops", a scoped person reads
 * their shop's name, a person with no access gets no welcome), and that a page
 * opened without the parameter asks for nothing extra.
 */
import { render, waitFor } from "@testing-library/react"
import React from "react"
import DashboardPage from "../page"
import apiClient from "@/lib/api-client"
import { fetchAllMyShops, fetchMyAccess } from "@/lib/shops-api"

jest.mock("recharts", () => {
  const Pass = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>
  return {
    ResponsiveContainer: Pass, PieChart: Pass, Pie: Pass, Cell: Pass, BarChart: Pass, Bar: Pass,
    XAxis: Pass, YAxis: Pass, CartesianGrid: Pass, Tooltip: Pass, Legend: Pass,
  }
})

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/lib/shops-api", () => ({
  fetchAllMyShops: jest.fn(),
  fetchMyAccess: jest.fn(),
}))
const mockedShops = fetchAllMyShops as jest.MockedFunction<typeof fetchAllMyShops>
const mockedAccess = fetchMyAccess as jest.MockedFunction<typeof fetchMyAccess>

jest.mock("@/lib/shop-context", () => ({
  ALL_SHOPS_CONTEXT: "all",
  getShopContext: jest.fn(() => "all"),
  setShopContext: jest.fn(),
  subscribeShopContext: jest.fn(() => () => {}),
}))

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({ useToast: () => ({ toast: mockToast }) }))

global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const SHOP_A = "aaaaaaaa-1111-1111-1111-111111111111"
const SHOP_B = "bbbbbbbb-2222-2222-2222-222222222222"
const shops = [
  { id: SHOP_A, name: "Peckham Kitchen" },
  { id: SHOP_B, name: "Brixton Bakery" },
]

function access(extra: Partial<Awaited<ReturnType<typeof fetchMyAccess>>>) {
  return {
    userId: "11111111-1111-1111-1111-111111111111",
    groupAdmin: false,
    grantedShopIds: [] as string[] | null,
    tenantWideRole: null,
    businessName: "Mama Ade's Kitchen Ltd",
    ...extra,
  }
}

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url.startsWith("/api/v1/onboarding/me")) return Promise.reject({ response: { status: 404 } })
    if (url === "/api/v1/financial-transactions/summary") return Promise.resolve({ data: null })
    return Promise.resolve({ data: { content: [], totalElements: 0 } })
  }) as never)
  mockedShops.mockResolvedValue(shops as never)
})

const welcomes = () =>
  mockToast.mock.calls.map((c) => c[0]?.title).filter((t) => /joined/.test(String(t)))

it("welcomes a scoped person to their shop by name, once, and drops the parameter", async () => {
  window.history.replaceState(null, "", "/dashboard?joined=1")
  mockedAccess.mockResolvedValue(access({ grantedShopIds: [SHOP_A] }) as never)
  render(<DashboardPage />)

  await waitFor(() =>
    expect(welcomes()).toEqual([
      "You've joined Mama Ade's Kitchen Ltd. You can work on Peckham Kitchen.",
    ])
  )
  expect(window.location.search).toBe("")
  expect(window.location.pathname).toBe("/dashboard")
})

it("says 'all shops' for a group admin", async () => {
  window.history.replaceState(null, "", "/dashboard?joined=1")
  mockedAccess.mockResolvedValue(access({ groupAdmin: true, grantedShopIds: null }) as never)
  render(<DashboardPage />)
  await waitFor(() =>
    expect(welcomes()).toEqual(["You've joined Mama Ade's Kitchen Ltd. You can work on all shops."])
  )
})

it("says 'all shops' for a tenant-wide role, and keeps other parameters", async () => {
  window.history.replaceState(null, "", "/dashboard?tab=x&joined=1")
  mockedAccess.mockResolvedValue(
    access({ tenantWideRole: "STAFF", grantedShopIds: [SHOP_A, SHOP_B] }) as never
  )
  render(<DashboardPage />)
  await waitFor(() =>
    expect(welcomes()).toEqual(["You've joined Mama Ade's Kitchen Ltd. You can work on all shops."])
  )
  expect(window.location.search).toBe("?tab=x")
})

it("names every granted shop", async () => {
  window.history.replaceState(null, "", "/dashboard?joined=1")
  mockedAccess.mockResolvedValue(access({ grantedShopIds: [SHOP_A, SHOP_B] }) as never)
  render(<DashboardPage />)
  await waitFor(() =>
    expect(welcomes()).toEqual([
      "You've joined Mama Ade's Kitchen Ltd. You can work on Peckham Kitchen and Brixton Bakery.",
    ])
  )
})

it("welcomes nobody who, by the server's answer, has no access", async () => {
  window.history.replaceState(null, "", "/dashboard?joined=1")
  mockedAccess.mockResolvedValue(access({ grantedShopIds: [] }) as never)
  render(<DashboardPage />)
  await waitFor(() => expect(mockedAccess).toHaveBeenCalled())
  await new Promise((r) => setTimeout(r, 20))
  expect(welcomes()).toEqual([])
  expect(window.location.search).toBe("")
})

it("asks staff/me nothing and shows no welcome without the parameter", async () => {
  window.history.replaceState(null, "", "/dashboard")
  mockedAccess.mockResolvedValue(access({ groupAdmin: true }) as never)
  render(<DashboardPage />)
  await waitFor(() => expect(mockedShops).toHaveBeenCalled())
  await new Promise((r) => setTimeout(r, 20))
  expect(mockedAccess).not.toHaveBeenCalled()
  expect(welcomes()).toEqual([])
})
