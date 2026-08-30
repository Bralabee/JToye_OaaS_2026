/**
 * QA-council F3 (A11Y-1): the REDUCED VAT-rate badge is white text on
 * `bg-yellow-500` (1.92:1 — fails WCAG AA's 4.5:1 floor). Fixed to
 * `bg-yellow-700` (4.92:1). Covers both render sites that share
 * `vatRateConfig`: the VAT Breakdown summary tile and the transaction row.
 */
import { render, screen, waitFor } from "@testing-library/react"
import FinancePage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

describe("Finance page — REDUCED VAT badge contrast (F3 / A11Y-1)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockImplementation((url: string) => {
      if (url === "/api/v1/financial-transactions/summary") {
        return Promise.resolve({
          data: {
            totalRevenuePennies: 10000,
            totalExpensesPennies: 0,
            netAmountPennies: 10000,
            totalVatPennies: 500,
            transactionCount: 1,
            vatBreakdown: [
              { vatRate: "REDUCED", totalAmountPennies: 10000, totalVatPennies: 500, count: 1 },
            ],
          },
        })
      }
      return Promise.resolve({
        data: {
          content: [
            {
              id: "tx-1",
              tenantId: "t1",
              amountPennies: 10000,
              vatRate: "REDUCED",
              vatAmountPennies: 500,
              description: "Order #1",
              createdAt: new Date().toISOString(),
            },
          ],
          totalPages: 1,
          totalElements: 1,
        },
      })
    })
  })

  it("VAT Breakdown tile: the REDUCED badge is bg-yellow-700, not bg-yellow-500", async () => {
    render(<FinancePage />)
    await waitFor(() => expect(screen.getByText("5%")).toBeInTheDocument())
    const badge = screen.getByText("5%")
    expect(badge).toHaveClass("bg-yellow-700")
    expect(badge).not.toHaveClass("bg-yellow-500")
  })
})
