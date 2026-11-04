/**
 * #827 (owner ruling 2026-11-04): the Finance page splits what was taken into "Paid online" and
 * "Not paid online". The server derives it from the order behind each ledger row and sends
 * `paidOnlineRevenuePennies` / `notPaidOnlineRevenuePennies` on the summary and `paidOnline` on every
 * listed row; the page shows them under exactly those two labels.
 *
 * Through the real page inside the real ShopSwitcherProvider (only the network and the shop list are
 * faked). An older server that sends neither field gets no split at all, never a made-up £0.00.
 */
import { render, screen, within } from "@testing-library/react"
import FinancePage from "../page"
import apiClient from "@/lib/api-client"
import { ShopSwitcherProvider } from "@/components/dashboard/shop-switcher-provider"
import { fetchAllMyShops, fetchMyShops, type MyAccess } from "@/lib/shops-api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

jest.mock("@/lib/shops-api", () => ({
  fetchMyShops: jest.fn(),
  fetchAllMyShops: jest.fn(),
  fetchMyAccess: jest.fn(),
}))
const mockedFetchMyShops = fetchMyShops as jest.MockedFunction<typeof fetchMyShops>
const mockedFetchAllMyShops = fetchAllMyShops as jest.MockedFunction<typeof fetchAllMyShops>

const SHOP_A = "11111111-1111-1111-1111-111111111111"
const SHOPS = [{ id: SHOP_A, name: "Mama Ade's Kitchen", slug: "mama-ades-kitchen" }]
const ADMIN: MyAccess = {
  userId: "user-1",
  groupAdmin: true,
  grantedShopIds: [SHOP_A],
  tenantWideRole: "GROUP_ADMIN",
  businessName: "Ade Foods Ltd",
} as MyAccess

const ROW = {
  tenantId: "t",
  vatRate: "ZERO",
  vatAmountPennies: 0,
  createdAt: "2026-11-04T12:00:00Z",
  entryKind: "SALE",
}
const ROWS = [
  { ...ROW, id: "tx-card", amountPennies: 3000, description: "Order ORD-CARD", paidOnline: true },
  { ...ROW, id: "tx-cash", amountPennies: 1500, description: "Order ORD-CASH", paidOnline: false },
]

function summaryBody(extra: Record<string, unknown>) {
  return {
    totalRevenuePennies: 4500,
    totalExpensesPennies: 0,
    netAmountPennies: 4500,
    totalVatPennies: 0,
    transactionCount: 2,
    vatBreakdown: [],
    scope: { shopId: null, shopName: null, from: null, to: null },
    ...extra,
  }
}

function serve(summary: Record<string, unknown>, rows: unknown[]) {
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url.startsWith("/api/v1/financial-transactions/summary")) return Promise.resolve({ data: summary })
    if (url.startsWith("/api/v1/financial-transactions")) {
      return Promise.resolve({
        data: { content: rows, totalPages: 1, totalElements: rows.length, scope: summary.scope },
      })
    }
    if (url.startsWith("/api/v1/onboarding/me")) return Promise.reject({ response: { status: 404 } })
    return Promise.resolve({ data: { content: [], totalElements: 0 } })
  }) as never)
}

async function renderLoaded() {
  mockedFetchMyShops.mockResolvedValue({
    shops: SHOPS as never,
    isGroupAdmin: true,
    userId: ADMIN.userId,
    access: ADMIN,
  })
  render(
    <ShopSwitcherProvider>
      <FinancePage />
    </ShopSwitcherProvider>
  )
  await screen.findByTestId("finance-scope-banner")
}

beforeEach(() => {
  jest.clearAllMocks()
  window.localStorage.clear()
  mockedFetchAllMyShops.mockResolvedValue(SHOPS as never)
})

describe("Finance paid online / not paid online (issue #827)", () => {
  it("shows the two revenue figures under the ruled labels", async () => {
    serve(summaryBody({ paidOnlineRevenuePennies: 3000, notPaidOnlineRevenuePennies: 1500 }), ROWS)
    await renderLoaded()

    const split = await screen.findByTestId("finance-payment-split")
    const paid = within(split).getByTestId("finance-paid-online")
    expect(within(paid).getByText("Paid online")).toBeInTheDocument()
    expect(within(paid).getByTestId("finance-paid-online-amount")).toHaveTextContent("£30.00")
    const notPaid = within(split).getByTestId("finance-not-paid-online")
    expect(within(notPaid).getByText("Not paid online")).toBeInTheDocument()
    expect(within(notPaid).getByTestId("finance-not-paid-online-amount")).toHaveTextContent("£15.00")
  })

  it("labels every ledger row Paid online or Not paid online", async () => {
    serve(summaryBody({ paidOnlineRevenuePennies: 3000, notPaidOnlineRevenuePennies: 1500 }), ROWS)
    await renderLoaded()

    const card = (await screen.findByText("Order ORD-CARD")).closest("tr") as HTMLElement
    const cash = (await screen.findByText("Order ORD-CASH")).closest("tr") as HTMLElement
    expect(screen.getByRole("columnheader", { name: "Payment" })).toBeInTheDocument()
    expect(within(card).getByText("Paid online")).toBeInTheDocument()
    expect(within(card).queryByText("Not paid online")).not.toBeInTheDocument()
    expect(within(cash).getByText("Not paid online")).toBeInTheDocument()
  })

  it("shows no split at all when the server sent neither field (an older server), never a made-up £0.00", async () => {
    serve(summaryBody({}), [{ ...ROWS[0], paidOnline: undefined }])
    await renderLoaded()

    await screen.findByText("Order ORD-CARD")
    expect(screen.queryByTestId("finance-payment-split")).not.toBeInTheDocument()
    expect(screen.queryByText("Paid online")).not.toBeInTheDocument()
    expect(screen.queryByText("Not paid online")).not.toBeInTheDocument()
  })
})
