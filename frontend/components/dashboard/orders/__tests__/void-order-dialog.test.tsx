/**
 * 37-12 Task 2 (UI-SPEC § B4, RWO-018 / UXT-018, D-10): void an order from its detail.
 *
 * - "Void order" (outline, Ban icon) is offered ONLY to a Group admin and only for a
 *   CONFIRMED-or-later order that is not voided; no Delete control exists (D-10).
 * - VoidOrderDialog: the ConfirmActionDialog shape with a REQUIRED reason; the
 *   destructive "Void order" stays disabled, beside the permanent hint, until the reason
 *   has non-whitespace text; "Working…" while pending, and it cannot close mid-flight.
 * - Card-paid orders add "Voiding doesn't refund the customer. Use Refund for that."
 * - After a 200: a "Voided" badge and "Voided by {name} on {date, time} · Reason: …".
 * - A 409 order-already-voided renders "This order was already voided by {name} on {date}."
 *   inline with role=alert.
 * - The Orders list row shows the same Voided badge beside the status.
 *
 * The Group-admin decision comes from the dashboard shell's ShopSwitcherProvider
 * (staff/me via fetchMyShops, mocked here); the panel is rendered inside the REAL
 * provider, so the test drives the same source the shell does.
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { OrderDetailPanel } from "../OrderDetailPanel"
import OrdersPage from "@/app/dashboard/orders/page"
import { ShopSwitcherProvider } from "@/components/dashboard/shop-switcher-provider"
import apiClient from "@/lib/api-client"
import { fetchMyShops, type MyAccess } from "@/lib/shops-api"
import { fetchStaff } from "@/lib/staff-api"
import type { OrderDetail, OrderStatus } from "@/types/api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

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

jest.mock("../RefundDialog", () => ({
  RefundDialog: () => null,
}))

const ME = "aaaaaaaa-1111-2222-3333-444444444444"
const KIM = "bbbbbbbb-1111-2222-3333-444444444444"
const STRANGER = "cccccccc-1111-2222-3333-444444444444"
const VOIDED_AT = "2026-10-08T12:00:00Z" // 13:00 in the UK (BST)
const NO_REFUND_COPY = "Voiding doesn't refund the customer. Use Refund for that."

function access(over: Partial<MyAccess> = {}): MyAccess {
  return {
    userId: ME,
    groupAdmin: true,
    grantedShopIds: null,
    tenantWideRole: null,
    businessName: "Ade Foods Ltd",
    ...over,
  }
}

function makeOrder(over: Partial<OrderDetail> = {}): OrderDetail {
  return {
    id: "order-abc-123",
    tenantId: "tenant-xyz",
    shopId: "shop-1",
    orderNumber: "ORD-2026-0001",
    status: "COMPLETED",
    customerName: "Jane Doe",
    totalAmountPennies: 2450,
    items: [],
    createdAt: "2026-10-08T10:00:00Z",
    updatedAt: "2026-10-08T10:00:00Z",
    refunds: [],
    ...over,
  }
}

function renderPanel(order: OrderDetail, acc: MyAccess = access()) {
  mockedFetchMyShops.mockResolvedValue({
    shops: [],
    isGroupAdmin: acc.groupAdmin,
    userId: acc.userId,
    access: acc,
  })
  return render(
    <ShopSwitcherProvider>
      <OrderDetailPanel order={order} />
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

const voidButton = () => screen.queryByRole("button", { name: "Void order" })

beforeEach(() => {
  jest.clearAllMocks()
  mockedFetchStaff.mockResolvedValue({
    directory: [],
    grants: [],
    people: [
      { userId: KIM, maskedEmail: "k***@example.com", displayName: "Kim Ade", lastSeen: null },
    ],
  })
})

describe("Who is offered 'Void order' (UI-SPEC § B4 'Where')", () => {
  it.each<OrderStatus>(["CONFIRMED", "PREPARING", "READY", "COMPLETED"])(
    "a Group admin sees an outline 'Void order' with the Ban icon on a %s order",
    async (status) => {
      renderPanel(makeOrder({ status }))
      const button = await screen.findByRole("button", { name: "Void order" })
      expect(button.className).toContain("border")
      expect(button.querySelector("svg.lucide-ban")).not.toBeNull()
    }
  )

  it("a shop manager is not offered it", async () => {
    renderPanel(makeOrder(), access({ groupAdmin: false, grantedShopIds: ["shop-1"] }))
    await settled()
    expect(voidButton()).not.toBeInTheDocument()
  })

  it.each<OrderStatus>(["DRAFT", "PENDING"])(
    "a %s order offers no void (it is deleted instead) and no new delete control appears",
    async (status) => {
      renderPanel(makeOrder({ status }))
      await settled()
      expect(voidButton()).not.toBeInTheDocument()
      expect(screen.queryByRole("button", { name: /delete/i })).not.toBeInTheDocument()
    }
  )

  it("an already-voided order offers no second void", async () => {
    renderPanel(makeOrder({ voidedAt: VOIDED_AT, voidedBy: ME, voidReason: "Duplicate" }))
    await settled()
    expect(voidButton()).not.toBeInTheDocument()
  })

  it("no Delete control exists for a CONFIRMED-or-later order (D-10)", async () => {
    renderPanel(makeOrder({ status: "CONFIRMED" }))
    await screen.findByRole("button", { name: "Void order" })
    expect(screen.queryByRole("button", { name: /delete/i })).not.toBeInTheDocument()
  })
})

describe("VoidOrderDialog (UI-SPEC § B4 'Confirm')", () => {
  async function openDialog(order = makeOrder()) {
    renderPanel(order)
    fireEvent.click(await screen.findByRole("button", { name: "Void order" }))
    return screen.findByRole("dialog")
  }

  it("asks with the order number, says what voiding does, and keeps the order on 'Keep order'", async () => {
    const dialog = await openDialog()
    expect(within(dialog).getByRole("heading", { name: "Void order ORD-2026-0001?" })).toBeInTheDocument()
    expect(dialog).toHaveTextContent(
      "The order stays on record, marked as voided, and a reversing entry is added to your ledger so your totals stay correct. This can't be undone."
    )
    fireEvent.click(within(dialog).getByRole("button", { name: "Keep order" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
    expect(mockedApiClient.post).not.toHaveBeenCalled()
  })

  it("requires a reason: the destructive 'Void order' is disabled beside the permanent hint until the reason has text", async () => {
    const dialog = await openDialog()
    const reason = within(dialog).getByLabelText("Reason (kept on the order)")
    expect(reason.tagName).toBe("TEXTAREA")
    expect(reason).toHaveAttribute("aria-required", "true")
    expect(Number(reason.getAttribute("rows"))).toBeGreaterThanOrEqual(3)

    const confirm = within(dialog).getByRole("button", { name: "Void order" })
    expect(confirm).toBeDisabled()
    expect(confirm.className).toContain("bg-destructive")
    expect(within(dialog).getByText("Add a reason to void this order")).toBeInTheDocument()

    fireEvent.change(reason, { target: { value: "   \n  " } })
    expect(confirm).toBeDisabled()

    fireEvent.change(reason, { target: { value: "Customer cancelled by phone" } })
    expect(confirm).toBeEnabled()
    // The hint is permanent: it does not disappear once the button is enabled.
    expect(within(dialog).getByText("Add a reason to void this order")).toBeInTheDocument()
  })

  it("says a card-paid void does not refund", async () => {
    const dialog = await openDialog(makeOrder({ paymentStatus: "CAPTURED", paymentMethod: "card", paymentReference: "pi_1" }))
    expect(dialog).toHaveTextContent(NO_REFUND_COPY)
  })

  it("does not mention refunds for an order with no card payment", async () => {
    const dialog = await openDialog(makeOrder({ paymentStatus: undefined, paymentMethod: null }))
    expect(dialog).not.toHaveTextContent(NO_REFUND_COPY)
  })

  it("posts {reason}, then shows the Voided badge and who, when and why; the order stays open", async () => {
    mockedApiClient.post.mockResolvedValue({
      data: makeOrder({ voidedAt: VOIDED_AT, voidedBy: ME, voidReason: "Customer cancelled by phone" }),
    })
    const dialog = await openDialog()
    fireEvent.change(within(dialog).getByLabelText("Reason (kept on the order)"), {
      target: { value: "  Customer cancelled by phone  " },
    })
    fireEvent.click(within(dialog).getByRole("button", { name: "Void order" }))

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
    expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/orders/order-abc-123/void", {
      reason: "Customer cancelled by phone",
    })
    expect(screen.getByText("Voided")).toBeInTheDocument()
    expect(screen.getByTestId("order-voided")).toHaveTextContent(
      "Voided by you on 8 October 2026, 13:00 · Reason: Customer cancelled by phone"
    )
    // Still the same order detail, with its status, and no second void offered.
    expect(screen.getByText("Completed")).toBeInTheDocument()
    expect(voidButton()).not.toBeInTheDocument()
  })

  it("shows 'Working…' while the void is pending, and cannot be closed mid-flight", async () => {
    let resolvePost: (v: unknown) => void = () => {}
    mockedApiClient.post.mockReturnValue(new Promise((r) => (resolvePost = r)) as never)
    const dialog = await openDialog()
    fireEvent.change(within(dialog).getByLabelText("Reason (kept on the order)"), {
      target: { value: "Wrong shop" },
    })
    fireEvent.click(within(dialog).getByRole("button", { name: "Void order" }))

    expect(await within(dialog).findByRole("button", { name: "Working…" })).toBeDisabled()
    expect(within(dialog).getByRole("button", { name: "Keep order" })).toBeDisabled()
    fireEvent.keyDown(dialog, { key: "Escape" })
    expect(screen.getByRole("dialog")).toBeInTheDocument()

    await act(async () => {
      resolvePost({ data: makeOrder({ voidedAt: VOIDED_AT, voidedBy: ME, voidReason: "Wrong shop" }) })
    })
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument())
  })

  it("a 409 order-already-voided says who voided it and when, inline as an alert", async () => {
    mockedApiClient.post.mockRejectedValue({
      response: {
        status: 409,
        data: {
          type: "https://jtoye.uk/errors/order-already-voided",
          status: 409,
          voidedBy: KIM,
          voidedAt: VOIDED_AT,
        },
      },
    })
    const dialog = await openDialog()
    fireEvent.change(within(dialog).getByLabelText("Reason (kept on the order)"), {
      target: { value: "Different reason" },
    })
    fireEvent.click(within(dialog).getByRole("button", { name: "Void order" }))

    const alert = await within(dialog).findByRole("alert")
    expect(alert).toHaveTextContent("This order was already voided by Kim Ade on 8 October 2026.")
    expect(mockToast).not.toHaveBeenCalled()
  })

  it("any other failure is a toast, and the dialog stays open with the reason kept", async () => {
    mockedApiClient.post.mockRejectedValue({ response: { status: 500 } })
    const dialog = await openDialog()
    const reason = within(dialog).getByLabelText("Reason (kept on the order)")
    fireEvent.change(reason, { target: { value: "Test order" } })
    fireEvent.click(within(dialog).getByRole("button", { name: "Void order" }))

    await waitFor(() => expect(mockToast).toHaveBeenCalledWith(expect.objectContaining({ variant: "destructive" })))
    expect(screen.getByRole("dialog")).toBeInTheDocument()
    expect(reason).toHaveValue("Test order")
    expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument()
  })
})

describe("The voided block (UI-SPEC § B4 'After')", () => {
  it("names another Group admin from the staff directory", async () => {
    renderPanel(makeOrder({ voidedAt: VOIDED_AT, voidedBy: KIM, voidReason: "Duplicate" }))
    await waitFor(() =>
      expect(screen.getByTestId("order-voided")).toHaveTextContent(
        "Voided by Kim Ade on 8 October 2026, 13:00 · Reason: Duplicate"
      )
    )
  })

  it("never shows a raw account id: an unknown account reads 'a Group admin'", async () => {
    renderPanel(makeOrder({ voidedAt: VOIDED_AT, voidedBy: STRANGER, voidReason: "Duplicate" }))
    await waitFor(() =>
      expect(screen.getByTestId("order-voided")).toHaveTextContent(
        "Voided by a Group admin on 8 October 2026, 13:00 · Reason: Duplicate"
      )
    )
    expect(screen.getByTestId("order-voided")).not.toHaveTextContent(STRANGER)
  })

  it("a non-admin viewer sees the void without the directory being read", async () => {
    renderPanel(
      makeOrder({ voidedAt: VOIDED_AT, voidedBy: KIM, voidReason: "Duplicate" }),
      access({ groupAdmin: false, grantedShopIds: ["shop-1"] })
    )
    await settled()
    expect(screen.getByText("Voided")).toBeInTheDocument()
    expect(screen.getByTestId("order-voided")).toHaveTextContent("Voided by a Group admin on 8 October 2026, 13:00")
    expect(mockedFetchStaff).not.toHaveBeenCalled()
  })

  it("wraps a 1,000-character reason instead of overflowing (375px backstop)", async () => {
    const long = "x".repeat(1000)
    renderPanel(makeOrder({ voidedAt: VOIDED_AT, voidedBy: ME, voidReason: long }))
    const block = await screen.findByTestId("order-voided")
    expect(block).toHaveTextContent(long)
    expect(block.className).toContain("[overflow-wrap:anywhere]")
    expect(block.className).not.toMatch(/\b(truncate|whitespace-nowrap)\b/)
  })
})

describe("Orders list (UI-SPEC § B4 'After': the list row shows the same badge)", () => {
  function row(id: string, over: Record<string, unknown> = {}) {
    return {
      id,
      tenantId: "tenant-1",
      shopId: "shop-1",
      orderNumber: `ORD-${id}`,
      status: "COMPLETED",
      customerName: "Jane Doe",
      totalAmountPennies: 2450,
      itemCount: 1,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      ...over,
    }
  }

  it("a voided order stays listed with a Voided badge beside its status; others carry none", async () => {
    mockedApiClient.get.mockImplementation(((url: string) => {
      if (url.startsWith("/api/v1/orders")) {
        return Promise.resolve({
          data: {
            content: [row("V1", { voidedAt: VOIDED_AT, voidedBy: ME, voidReason: "Duplicate" }), row("K1")],
            totalPages: 1,
            totalElements: 2,
          },
        })
      }
      return Promise.resolve({ data: { content: [] } })
    }) as never)
    render(<OrdersPage />)

    const voidedRow = (await screen.findByText("ORD-V1")).closest("tr")!
    expect(within(voidedRow).getByText("Completed")).toBeInTheDocument()
    expect(within(voidedRow).getByText("Voided")).toBeInTheDocument()

    const keptRow = screen.getByText("ORD-K1").closest("tr")!
    expect(within(keptRow).queryByText("Voided")).not.toBeInTheDocument()
  })
})
