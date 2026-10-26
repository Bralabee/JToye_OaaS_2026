/**
 * A live update must not tear down the order page (E2E Nightly 'G-37-6 item 4',
 * runs 40153383032 and 40258034071).
 *
 * Every SSE event for the open order re-reads its detail. Before this fix that
 * re-read set the page's loading state, and the loading branch REPLACES the whole
 * page, so the OrderDetailPanel unmounted and an open Void dialog went with it:
 * a Group admin typing a void reason lost the dialog and the text whenever any
 * event for that order arrived.
 *
 * What is pinned here:
 * - A: an SSE event for the order on screen refreshes it IN PLACE. No spinner,
 *   the same dialog node stays connected, the typed reason survives, and the
 *   refreshed data then renders.
 * - B: a failed background refresh keeps the loaded order and says so in a
 *   toast, never the error page.
 * - C: a different route id is a fresh load (spinner, the old order gone). This
 *   is what keeps the background decision keyed on WHICH order is on screen
 *   rather than on "some order is loaded".
 *
 * The Group-admin harness is the one void-order-dialog.test.tsx uses: the panel
 * decides who may void from the real ShopSwitcherProvider, fed by a mocked
 * fetchMyShops.
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { useParams } from "next/navigation"
import OrderDetailPage from "../page"
import { ShopSwitcherProvider } from "@/components/dashboard/shop-switcher-provider"
import apiClient from "@/lib/api-client"
import { useOrderEvents } from "@/hooks/use-order-events"
import { fetchMyShops, type MyAccess } from "@/lib/shops-api"
import { fetchStaff } from "@/lib/staff-api"
import { VOID_REASON_LABEL } from "@/components/dashboard/orders/void-order-dialog"
import type { OrderDetail, OrderStateChangeEvent } from "@/types/api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

// Module-level, so the page sees ONE stable function and the test can read its calls.
const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

jest.mock("@/hooks/use-order-events", () => ({
  useOrderEvents: jest.fn(),
}))

jest.mock("@/lib/shops-api", () => {
  const actual = jest.requireActual("@/lib/shops-api")
  return { ...actual, fetchMyShops: jest.fn(), fetchAllMyShops: jest.fn().mockResolvedValue([]) }
})
const mockedFetchMyShops = fetchMyShops as jest.MockedFunction<typeof fetchMyShops>

jest.mock("@/lib/staff-api", () => {
  const actual = jest.requireActual("@/lib/staff-api")
  return { ...actual, fetchStaff: jest.fn() }
})
const mockedFetchStaff = fetchStaff as jest.MockedFunction<typeof fetchStaff>

jest.mock("@/components/dashboard/orders/RefundDialog", () => ({
  RefundDialog: () => null,
}))

const ORDER_ID = "11111111-2222-3333-4444-555555555555"
const OTHER_ID = "99999999-8888-7777-6666-555555555555"
const ME = "aaaaaaaa-1111-2222-3333-444444444444"
const ORDER_NUMBER = "ORD-00000000-20261026-ABCDEF01"
const OTHER_NUMBER = "ORD-00000000-20261026-FEDCBA09"
const TYPED_REASON = "Customer rang to cancel, refund by cash"

function access(): MyAccess {
  return {
    userId: ME,
    groupAdmin: true,
    grantedShopIds: null,
    tenantWideRole: null,
    businessName: "Ade Foods Ltd",
  }
}

function makeOrder(over: Partial<OrderDetail> = {}): OrderDetail {
  return {
    id: ORDER_ID,
    tenantId: "tenant-1",
    shopId: "shop-1",
    orderNumber: ORDER_NUMBER,
    status: "COMPLETED",
    customerName: "Jane Doe",
    totalAmountPennies: 2450,
    items: [],
    createdAt: "2026-10-26T10:00:00.000Z",
    updatedAt: "2026-10-26T10:05:00.000Z",
    refunds: [],
    ...over,
  }
}

let latestHandler: ((event: OrderStateChangeEvent) => void) | null = null

/** Fire an SSE event through the handler the page registered on its LATEST render. */
async function fireOrderEvent(orderId: string) {
  expect(latestHandler).not.toBeNull()
  await act(async () => {
    latestHandler!({ orderId } as OrderStateChangeEvent)
    await Promise.resolve()
  })
}

function deferred<T>() {
  let resolve: (v: T) => void = () => {}
  let reject: (e: unknown) => void = () => {}
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function renderPage() {
  mockedFetchMyShops.mockResolvedValue({
    shops: [],
    isGroupAdmin: true,
    userId: ME,
    access: access(),
  })
  return render(
    <ShopSwitcherProvider>
      <OrderDetailPage />
    </ShopSwitcherProvider>
  )
}

/** Wait until the provider has answered (staff/me), so an absence is a decision, not a race. */
async function settled() {
  await waitFor(() => expect(mockedFetchMyShops).toHaveBeenCalled())
  await act(async () => {
    await Promise.resolve()
  })
}

const spinner = () => document.querySelector(".animate-spin")
const detailPath = (id: string) => `/api/v1/orders/${id}/detail`

beforeEach(() => {
  jest.clearAllMocks()
  latestHandler = null
  ;(useOrderEvents as jest.Mock).mockImplementation((h: (event: OrderStateChangeEvent) => void) => {
    latestHandler = h
  })
  ;(useParams as jest.Mock).mockReturnValue({ id: ORDER_ID })
  mockedFetchStaff.mockResolvedValue({ directory: [], grants: [], people: [] })
})

describe("Order detail page: a live update refreshes in place", () => {
  it("an SSE event for the open order refreshes it in place: the Void dialog stays open with its typed reason", async () => {
    mockedApiClient.get.mockResolvedValueOnce({ data: makeOrder() })
    renderPage()
    await settled()

    fireEvent.click(await screen.findByRole("button", { name: "Void order" }))
    const dialog = await screen.findByRole("dialog")
    fireEvent.change(within(dialog).getByLabelText(VOID_REASON_LABEL), {
      target: { value: TYPED_REASON },
    })

    const next = deferred<{ data: OrderDetail }>()
    mockedApiClient.get.mockReturnValueOnce(next.promise as never)
    await fireOrderEvent(ORDER_ID)

    // The refresh really fired, so nothing below can pass vacuously.
    expect(mockedApiClient.get).toHaveBeenCalledTimes(2)
    expect(mockedApiClient.get).toHaveBeenNthCalledWith(2, detailPath(ORDER_ID))
    // In flight: no spinner, the SAME dialog node, the reason intact.
    expect(spinner()).toBeNull()
    expect(dialog.isConnected).toBe(true)
    expect(within(dialog).getByLabelText(VOID_REASON_LABEL)).toHaveValue(TYPED_REASON)

    await act(async () => {
      next.resolve({ data: makeOrder({ customerName: "Janet Doe-Smith" }) })
    })
    expect(await screen.findByText("Janet Doe-Smith")).toBeInTheDocument()
    expect(dialog.isConnected).toBe(true)
    expect(within(dialog).getByLabelText(VOID_REASON_LABEL)).toHaveValue(TYPED_REASON)
  })

  it("a failed background refresh keeps the loaded order on screen and says so in a toast, not the error page", async () => {
    mockedApiClient.get.mockResolvedValueOnce({ data: makeOrder() })
    renderPage()
    await settled()
    expect((await screen.findAllByText(ORDER_NUMBER)).length).toBeGreaterThan(0)

    mockedApiClient.get.mockRejectedValueOnce({ response: { status: 500 } })
    await fireOrderEvent(ORDER_ID)
    await act(async () => {
      await Promise.resolve()
    })

    expect(mockedApiClient.get).toHaveBeenCalledTimes(2)
    expect(screen.getAllByText(ORDER_NUMBER).length).toBeGreaterThan(0)
    expect(screen.queryByText("Failed to load order. Please try again.")).not.toBeInTheDocument()
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
    expect(mockToast).toHaveBeenCalledTimes(1)
    expect(mockToast).toHaveBeenCalledWith(expect.objectContaining({ variant: "destructive" }))
  })

  it("a different route id is a fresh load, not a background refresh", async () => {
    mockedApiClient.get.mockResolvedValueOnce({ data: makeOrder() })
    const view = renderPage()
    await settled()
    expect((await screen.findAllByText(ORDER_NUMBER)).length).toBeGreaterThan(0)

    ;(useParams as jest.Mock).mockReturnValue({ id: OTHER_ID })
    mockedApiClient.get.mockReturnValueOnce(new Promise(() => {}) as never)
    await act(async () => {
      view.rerender(
        <ShopSwitcherProvider>
          <OrderDetailPage />
        </ShopSwitcherProvider>
      )
    })

    expect(mockedApiClient.get).toHaveBeenLastCalledWith(detailPath(OTHER_ID))
    expect(spinner()).not.toBeNull()
    expect(screen.queryByText(ORDER_NUMBER)).not.toBeInTheDocument()
    expect(screen.queryByText(OTHER_NUMBER)).not.toBeInTheDocument()
  })
})
