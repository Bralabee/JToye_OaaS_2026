/**
 * SW-ADE-08 / SW-TND-07 (swarm epic #964): the dashboard's "Revenue by VAT Category" tile said
 * "No financial data yet" while the summary was still loading AND when the finance API refused the
 * caller (403), the same words as a business with genuinely no transactions. Each is now its own
 * state: loading, refused (needs manager access), failed, and genuinely empty.
 */
import React from "react"
import { render, screen, within } from "@testing-library/react"
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

type Finance = "pending" | "forbidden" | "error" | "empty"

function api(finance: Finance) {
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url.startsWith("/api/v1/financial-transactions/summary")) {
      if (finance === "pending") return new Promise(() => {})
      if (finance === "forbidden")
        return Promise.reject({
          response: { status: 403, data: { type: "https://jtoye.uk/errors/shop-access-denied" } },
        })
      if (finance === "error") return Promise.reject({ response: { status: 503 } })
      return Promise.resolve({
        data: {
          totalRevenuePennies: 0,
          totalVatPennies: 0,
          vatBreakdown: [],
          scope: { kind: "TENANT", shopId: null, from: null, to: null },
        },
      })
    }
    if (url.startsWith("/api/v1/shops"))
      return Promise.resolve({ data: { content: [], totalElements: 0, totalPages: 1, last: true, number: 0, size: 100 } })
    if (url.startsWith("/api/v1/onboarding/me")) return Promise.reject({ response: { status: 404 } })
    return Promise.resolve({ data: { content: [], totalElements: 0 } })
  }) as jest.Mock)
}

async function vatTile(): Promise<HTMLElement> {
  render(<DashboardPage />)
  const title = await screen.findByText("Revenue by VAT Category")
  return title.closest("[class*='rounded']")?.parentElement as HTMLElement
}

beforeEach(() => {
  jest.clearAllMocks()
})

describe("Revenue by VAT Category tile states (SW-ADE-08, SW-TND-07)", () => {
  it("while the summary loads, says it is loading, not that there is no data", async () => {
    api("pending")
    const tile = await vatTile()
    expect(within(tile).getByText("Loading financial figures…")).toBeInTheDocument()
    expect(within(tile).queryByText("No financial data yet")).toBeNull()
    expect(within(tile).queryByText("No transactions to display")).toBeNull()
  })

  it("on a 403, says finance needs manager access, not that there is no data", async () => {
    api("forbidden")
    const tile = await vatTile()
    expect(
      await within(tile).findByText(/finance figures need manager access/i)
    ).toBeInTheDocument()
    expect(within(tile).queryByText("No financial data yet")).toBeNull()
    expect(within(tile).queryByText("No transactions to display")).toBeNull()
  })

  it("on another failure, says the figures could not be loaded", async () => {
    api("error")
    const tile = await vatTile()
    expect(await within(tile).findByText(/couldn.t load the financial figures/i)).toBeInTheDocument()
    expect(within(tile).queryByText("No financial data yet")).toBeNull()
  })

  it("a genuinely empty summary still reads as no transactions", async () => {
    api("empty")
    const tile = await vatTile()
    // The server's own (zero) totals are shown, so the summary really arrived.
    expect(await within(tile).findByText("Total: £0.00 revenue, £0.00 VAT")).toBeInTheDocument()
    expect(within(tile).getByText("No transactions to display")).toBeInTheDocument()
    expect(within(tile).queryByText("Loading financial figures…")).toBeNull()
  })
})
