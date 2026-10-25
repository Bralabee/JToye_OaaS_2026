import React from "react"
import { render, screen, fireEvent, waitFor } from "@testing-library/react"
import {
  computeMerchantRoiTelemetry,
  PAYG_UPGRADE_THRESHOLD_PENNIES,
} from "@/lib/finance-roi"
import { MerchantRoiCard } from "@/components/dashboard/merchant-roi-card"
import FinancePage from "../page"
import apiClient from "@/lib/api-client"
import type { FinancialSummary, FinancialTransaction, Order } from "@/types/api"
import type { FinanceScope } from "@/lib/finance-api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

function makeSummary(overrides: Partial<FinancialSummary> = {}): FinancialSummary {
  return {
    totalRevenuePennies: 1_500_000, // £15,000.00
    totalExpensesPennies: 200_000,
    netAmountPennies: 1_300_000,
    totalVatPennies: 250_000,
    transactionCount: 300,
    vatBreakdown: [],
    ...overrides,
  }
}

function makeSaleTx(id: string, amountPennies: number): FinancialTransaction {
  return {
    id,
    tenantId: "tenant-1",
    orderId: `order-${id}`,
    entryKind: "SALE",
    amountPennies,
    vatRate: "STANDARD",
    vatAmountPennies: Math.round(amountPennies / 6),
    description: `Order #${id}`,
    createdAt: "2026-10-16T12:00:00Z",
  }
}

function makeReversalTx(id: string, amountPennies: number): FinancialTransaction {
  return {
    id,
    tenantId: "tenant-1",
    orderId: `order-${id}`,
    entryKind: "REVERSAL",
    reversesTransactionId: "tx-orig",
    amountPennies: -Math.abs(amountPennies),
    vatRate: "STANDARD",
    vatAmountPennies: -Math.round(Math.abs(amountPennies) / 6),
    description: `Void reversal #${id}`,
    createdAt: "2026-10-16T13:00:00Z",
  }
}

function makeOrder(overrides: Partial<Order> & { id: string }): Order {
  return {
    tenantId: "tenant-1",
    shopId: "shop-a",
    status: "COMPLETED",
    totalAmountPennies: 2500,
    itemCount: 2,
    createdAt: "2026-10-16T12:00:00Z",
    updatedAt: "2026-10-16T12:30:00Z",
    ...overrides,
  }
}

describe("computeMerchantRoiTelemetry (GAP-GTM-07)", () => {
  it("computes exact commission savings across the £5k, £15k, and £30k GTM decision-guide benchmarks under SAAS_HYBRID_STANDARD (£39/mo + 0.5%)", () => {
    // £5,000/mo Direct GTV
    const roi5k = computeMerchantRoiTelemetry(
      makeSummary({ totalRevenuePennies: 500_000, transactionCount: 200 }),
      [],
      [],
      "SAAS_HYBRID_STANDARD"
    )
    expect(roi5k.directGtvPennies).toBe(500_000)
    expect(roi5k.aggregatorCommissionPennies).toBe(140_000) // £1,400.00 (28%)
    expect(roi5k.jtoyeUsageFeePennies).toBe(2_500) // £25.00 (0.5%)
    expect(roi5k.jtoyeMonthlyBasePennies).toBe(3_900) // £39.00
    expect(roi5k.jtoyeTotalCostPennies).toBe(6_400) // £64.00
    expect(roi5k.grossCommissionSavedPennies).toBe(137_500) // £1,375.00 (27.5%)
    expect(roi5k.netMonthlySavingsPennies).toBe(133_600) // £1,336.00

    // £15,000/mo Direct GTV
    const roi15k = computeMerchantRoiTelemetry(
      makeSummary({ totalRevenuePennies: 1_500_000, transactionCount: 600 }),
      [],
      [],
      "SAAS_HYBRID_STANDARD"
    )
    expect(roi15k.aggregatorCommissionPennies).toBe(420_000) // £4,200.00
    expect(roi15k.jtoyeUsageFeePennies).toBe(7_500) // £75.00
    expect(roi15k.jtoyeTotalCostPennies).toBe(11_400) // £114.00
    expect(roi15k.grossCommissionSavedPennies).toBe(412_500) // £4,125.00
    expect(roi15k.netMonthlySavingsPennies).toBe(408_600) // £4,086.00

    // £30,000/mo Direct GTV
    const roi30k = computeMerchantRoiTelemetry(
      makeSummary({ totalRevenuePennies: 3_000_000, transactionCount: 1200 }),
      [],
      [],
      "SAAS_HYBRID_STANDARD"
    )
    expect(roi30k.aggregatorCommissionPennies).toBe(840_000) // £8,400.00
    expect(roi30k.jtoyeUsageFeePennies).toBe(15_000) // £150.00
    expect(roi30k.jtoyeTotalCostPennies).toBe(18_900) // £189.00
    expect(roi30k.grossCommissionSavedPennies).toBe(825_000) // £8,250.00
    expect(roi30k.netMonthlySavingsPennies).toBe(821_100) // £8,211.00

    // D-04 (#1011): £39/mo is per active location, not per tenant. A two-shop tenant at £15k GTV
    // pays 2 * £39.00 = £78.00 base + £75.00 usage = £153.00 total, netting £4,047.00 vs £4,200.00.
    const roi15kTwoShops = computeMerchantRoiTelemetry(
      makeSummary({ totalRevenuePennies: 1_500_000, transactionCount: 600 }),
      [],
      [],
      "SAAS_HYBRID_STANDARD",
      2
    )
    expect(roi15kTwoShops.activeLocationCount).toBe(2)
    expect(roi15kTwoShops.jtoyeMonthlyBasePennies).toBe(7_800)
    expect(roi15kTwoShops.jtoyeTotalCostPennies).toBe(15_300)
    expect(roi15kTwoShops.netMonthlySavingsPennies).toBe(404_700)
  })

  it("computes SAAS_FLAT_PREDICTABLE (£99/mo + 0%) and BATCH_PAYG_STARTER (£0/mo + 6%) with the £710 upgrade crossover", () => {
    const flat15k = computeMerchantRoiTelemetry(
      makeSummary({ totalRevenuePennies: 1_500_000 }),
      [],
      [],
      "SAAS_FLAT_PREDICTABLE"
    )
    expect(flat15k.jtoyeUsageFeePennies).toBe(0)
    expect(flat15k.jtoyeMonthlyBasePennies).toBe(9_900)
    expect(flat15k.netMonthlySavingsPennies).toBe(410_100) // £4,200 - £99 = £4,101.00
    expect(flat15k.recommendHybridUpgrade).toBe(false)

    // At £710.00 (71,000 pennies): 6% = £42.60 vs £39 + 0.5% (£3.55) = £42.55 (crossover threshold)
    const paygBelow = computeMerchantRoiTelemetry(
      makeSummary({ totalRevenuePennies: PAYG_UPGRADE_THRESHOLD_PENNIES }),
      [],
      [],
      "BATCH_PAYG_STARTER"
    )
    expect(paygBelow.recommendHybridUpgrade).toBe(false)

    // At £1,000.00 (100,000 pennies): 6% = £60.00 vs Hybrid £39 + £5 = £44.00 -> saves £16.00/mo by upgrading
    const paygAbove = computeMerchantRoiTelemetry(
      makeSummary({ totalRevenuePennies: 100_000 }),
      [],
      [],
      "BATCH_PAYG_STARTER"
    )
    expect(paygAbove.jtoyeUsageFeePennies).toBe(6_000)
    expect(paygAbove.recommendHybridUpgrade).toBe(true)
    expect(paygAbove.hybridUpgradeSavingsPennies).toBe(1_600)
  })

  it("clamps negative revenue to 0 Direct GTV and counts SALE vs REVERSAL ledger rows", () => {
    const txs: FinancialTransaction[] = [
      makeSaleTx("1", 3000),
      makeSaleTx("2", 4500),
      makeReversalTx("3", 3000),
    ]
    const roi = computeMerchantRoiTelemetry(
      makeSummary({ totalRevenuePennies: -500, transactionCount: 3 }),
      txs,
      [],
      "SAAS_HYBRID_STANDARD"
    )
    expect(roi.directGtvPennies).toBe(0)
    expect(roi.aggregatorCommissionPennies).toBe(0)
    expect(roi.grossCommissionSavedPennies).toBe(0)
    expect(roi.settledSaleCount).toBe(2)
    expect(roi.voidReversalCount).toBe(1)
  })

  it("computes Repeat Buyer % from non-cancelled, non-voided orders and returns null when sample has 0 identified customers", () => {
    const emptyRoi = computeMerchantRoiTelemetry(makeSummary(), [], [], "SAAS_HYBRID_STANDARD")
    expect(emptyRoi.repeatBuyerStats.identifiedCustomers).toBe(0)
    expect(emptyRoi.repeatBuyerStats.repeatCustomers).toBe(0)
    expect(emptyRoi.repeatBuyerStats.repeatBuyerPercent).toBeNull()

    const orders: Order[] = [
      // Alice: 2 completed orders (repeat buyer)
      makeOrder({ id: "o1", customerEmail: "Alice@Example.com", status: "COMPLETED" }),
      makeOrder({ id: "o2", customerEmail: "alice@example.com", status: "READY" }),
      // Bob: 2 orders by customerId (repeat buyer)
      makeOrder({ id: "o3", customerId: "cust-bob", status: "COMPLETED" }),
      makeOrder({ id: "o4", customerId: "cust-bob", status: "CONFIRMED" }),
      // Charlie: 1 completed order + 1 cancelled order + 1 voided order (single valid order -> not repeat)
      makeOrder({ id: "o5", customerEmail: "charlie@example.com", status: "COMPLETED" }),
      makeOrder({ id: "o6", customerEmail: "charlie@example.com", status: "CANCELLED" }),
      makeOrder({
        id: "o7",
        customerEmail: "charlie@example.com",
        status: "COMPLETED",
        voidedAt: "2026-10-16T15:00:00Z",
      }),
    ]

    const roi = computeMerchantRoiTelemetry(makeSummary(), [], orders, "SAAS_HYBRID_STANDARD")
    expect(roi.repeatBuyerStats.identifiedCustomers).toBe(3)
    expect(roi.repeatBuyerStats.repeatCustomers).toBe(2)
    expect(roi.repeatBuyerStats.repeatBuyerPercent).toBe(67) // 2 / 3 = 66.67% -> 67%
  })

  it("tracks V81 unverified-contact cash orders strictly when contactVerified === false (never when null/absent)", () => {
    const orders: Order[] = [
      makeOrder({ id: "o1", contactVerified: false, status: "CANCELLED" }),
      makeOrder({ id: "o2", contactVerified: false, status: "PENDING" }),
      makeOrder({ id: "o3", contactVerified: true, status: "CANCELLED" }),
      makeOrder({ id: "o4", contactVerified: null, status: "CANCELLED" }),
      makeOrder({ id: "o5", status: "CANCELLED" }), // contactVerified undefined
    ]

    const roi = computeMerchantRoiTelemetry(makeSummary(), [], orders, "SAAS_HYBRID_STANDARD")
    expect(roi.abuseProtectionStats.unverifiedOrdersCount).toBe(2)
    expect(roi.abuseProtectionStats.cancelledUnverifiedCount).toBe(1)
    expect(roi.abuseProtectionStats.v81CapsLabel).toBe("3/email · 3/phone · 5/IP")
  })
})

describe("MerchantRoiCard component (GAP-GTM-07)", () => {
  const scopeBrixton: FinanceScope = {
    shopId: "shop-a",
    shopName: "Brixton Kitchen",
    from: null,
    to: null,
  }

  it("renders ROI telemetry for the active shop scope and updates figures when switching ADR-0007 billing plans", () => {
    const orders: Order[] = [
      makeOrder({ id: "o1", customerEmail: "a@test.uk", status: "COMPLETED" }),
      makeOrder({ id: "o2", customerEmail: "a@test.uk", status: "COMPLETED" }),
      makeOrder({ id: "o3", customerEmail: "b@test.uk", status: "COMPLETED", contactVerified: false }),
      makeOrder({ id: "o4", customerEmail: "c@test.uk", status: "CANCELLED", contactVerified: false }),
    ]

    render(
      <MerchantRoiCard
        summary={makeSummary({ totalRevenuePennies: 1_500_000, transactionCount: 300 })}
        transactions={[makeSaleTx("1", 1_500_000)]}
        orders={orders}
        scope={scopeBrixton}
      />
    )

    const card = screen.getByTestId("merchant-roi-card")
    expect(card).toBeInTheDocument()
    expect(card).toHaveTextContent("Brixton Kitchen")

    // Default SAAS_HYBRID_STANDARD (£39/mo + 0.5%) on £15,000.00 GTV:
    // Gross variable saved = £4,125.00, Net after £39/mo = £4,086.00
    expect(screen.getByTestId("roi-commission-saved")).toHaveTextContent("£4,125.00")
    expect(screen.getByTestId("roi-net-savings")).toHaveTextContent("£4,086.00")

    // Repeat buyer rate: 1 of 2 identified buyers (a@test.uk has 2, b@test.uk has 1; c@test.uk was cancelled) -> 50%
    expect(screen.getByTestId("roi-repeat-buyers")).toHaveTextContent("50%")
    expect(screen.getByTestId("roi-repeat-buyers")).toHaveTextContent("1 of 2 repeat buyers")

    // V81 abuse protection: 1 cancelled unverified order, 2 total unverified-contact orders
    expect(screen.getByTestId("roi-v81-protection")).toHaveTextContent("1")
    expect(screen.getByTestId("roi-v81-protection")).toHaveTextContent("3/email · 3/phone · 5/IP")

    // Switch to SAAS_FLAT_PREDICTABLE (£99/mo + 0%):
    // Gross variable saved = £4,200.00, Net after £99/mo = £4,101.00
    fireEvent.click(screen.getByTestId("roi-plan-SAAS_FLAT_PREDICTABLE"))
    expect(screen.getByTestId("roi-commission-saved")).toHaveTextContent("£4,200.00")
    expect(screen.getByTestId("roi-net-savings")).toHaveTextContent("£4,101.00")

    // Switch to BATCH_PAYG_STARTER (£0/mo + 6%) at £15,000 GTV (> £710 crossover):
    // Shows automatic upgrade tip banner
    fireEvent.click(screen.getByTestId("roi-plan-BATCH_PAYG_STARTER"))
    expect(screen.getByTestId("roi-hybrid-upgrade-tip")).toBeInTheDocument()
  })

  it("renders a truthful empty-sample message when no customer orders are present and scales subscription cost across locations (#1011)", () => {
    render(
      <MerchantRoiCard
        summary={makeSummary({ totalRevenuePennies: 500_000, transactionCount: 200 })}
        transactions={[]}
        orders={[]}
        scope={{ shopId: null, shopName: "All shops", from: null, to: null }}
        locationCount={2}
      />
    )

    expect(screen.getByTestId("roi-repeat-buyers")).toHaveTextContent("—")
    expect(screen.getByTestId("roi-repeat-buyers")).toHaveTextContent(
      "No identified buyers in sample"
    )
    // £5,000 GTV across 2 shops under £39/location/mo + 0.5%:
    // 28% aggregator = £1,400.00; J'Toye = 2 * £39.00 (£78.00) + £25.00 = £103.00 -> net £1,297.00
    expect(screen.getByTestId("roi-net-savings")).toHaveTextContent("£1,297.00")
    expect(screen.getByTestId("roi-net-savings")).toHaveTextContent("2 locations")
  })
})

describe("FinancePage integration with MerchantRoiCard (GAP-GTM-07)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/financial-transactions/summary")) {
        return Promise.resolve({
          data: {
            totalRevenuePennies: 500_000, // £5,000.00
            totalExpensesPennies: 50_000,
            netAmountPennies: 450_000,
            totalVatPennies: 83_333,
            transactionCount: 200,
            vatBreakdown: [],
            scope: {
              shopId: "shop-a",
              shopName: "Peckham Suya Hub",
              from: null,
              to: null,
            },
          },
        })
      }
      if (url.startsWith("/api/v1/orders")) {
        return Promise.resolve({
          data: {
            content: [
              makeOrder({ id: "ord-1", customerEmail: "loyal@customer.uk", status: "COMPLETED" }),
              makeOrder({ id: "ord-2", customerEmail: "loyal@customer.uk", status: "COMPLETED" }),
              makeOrder({
                id: "ord-3",
                customerEmail: "phantom@cash.uk",
                status: "CANCELLED",
                contactVerified: false,
              }),
            ],
            totalPages: 1,
            totalElements: 3,
          },
        })
      }
      return Promise.resolve({
        data: {
          content: [makeSaleTx("tx-1", 500_000)],
          totalPages: 1,
          totalElements: 1,
          scope: {
            shopId: "shop-a",
            shopName: "Peckham Suya Hub",
            from: null,
            to: null,
          },
        },
      })
    })
  })

  it("mounts MerchantRoiCard inside /dashboard/finance with the server-echoed scope and live repeat-buyer & V81 metrics", async () => {
    render(<FinancePage />)

    const roiCard = await waitFor(() => screen.getByTestId("merchant-roi-card"))
    expect(roiCard).toHaveTextContent("Peckham Suya Hub")
    // £5,000.00 Direct GTV under SAAS_HYBRID_STANDARD (£39/mo + 0.5%):
    // Variable saved = £1,375.00, Net saved = £1,336.00
    expect(screen.getByTestId("roi-commission-saved")).toHaveTextContent("£1,375.00")
    expect(screen.getByTestId("roi-net-savings")).toHaveTextContent("£1,336.00")
    // 1 of 1 non-cancelled customer has 2 orders -> 100% repeat buyer rate
    expect(screen.getByTestId("roi-repeat-buyers")).toHaveTextContent("100%")
    // 1 cancelled unverified order
    expect(screen.getByTestId("roi-v81-protection")).toHaveTextContent("1")
  })
})
