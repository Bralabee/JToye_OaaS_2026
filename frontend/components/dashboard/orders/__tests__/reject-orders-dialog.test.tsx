/**
 * Phase 37-30 (UI-SPEC § D1 "RejectOrdersDialog"; D-18, SC-4; RWO-041).
 *
 * The one reject dialog for the kitchen and the Orders page. It is the ConfirmActionDialog
 * shape (title, body, "Keep …" / destructive confirm, "Working…" while pending) plus the two
 * things that pattern has no slot for: a REQUIRED reason, kept on the order, and a per-order
 * result summary after the submit, because a batch can be partly refused (an order someone
 * accepted on another screen a second earlier reads ALREADY_ACCEPTED, not failure).
 *
 * The server contract is 37-29's: POST /api/v1/orders/bulk-cancel {orderIds, reasonCode} ->
 * {results: [{orderId, outcome}]}. One id is a batch of one, so the race above is handled the
 * same way whether the kitchen rejected one order or twenty.
 *
 * Asserted here (only the network and the Radix Select are faked):
 *   - plural / singular titles, confirm and cancel labels (zero-one-many);
 *   - the body says the reason stays on the order and is not sent to the customer;
 *   - confirm is disabled until a reason is chosen; the chosen code is what is posted;
 *   - "Working…" while the request is in flight;
 *   - the result summary with zero counts omitted, a "Close summary" button, no auto-close;
 *   - onRejected receives exactly the ids the server reported REJECTED;
 *   - a failed request keeps the dialog open with an inline alert and no summary.
 */
import { act, fireEvent, render, screen, within } from "@testing-library/react"
import {
  RejectOrdersDialog,
  type RejectableOrder,
} from "@/components/dashboard/orders/reject-orders-dialog"

const mockPost = jest.fn()
jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: { post: (...args: unknown[]) => mockPost(...args) },
}))

// Radix Select needs pointer-capture APIs jsdom lacks; a native <select> keeps the contract
// (value in, onValueChange out) and exposes the combobox role the dialog's Label points at.
jest.mock("@/components/ui/select", () => {
  const React = jest.requireActual("react")
  const Select = ({
    children,
    value,
    onValueChange,
    disabled,
  }: {
    children: React.ReactNode
    value?: string
    onValueChange: (v: string) => void
    disabled?: boolean
  }) => (
    <select
      data-testid="mock-select"
      value={value ?? ""}
      disabled={disabled}
      onChange={(e) => onValueChange(e.target.value)}
    >
      <option value="">—</option>
      {children}
    </select>
  )
  const passthrough = ({ children }: { children?: React.ReactNode }) => <>{children}</>
  const SelectItem = ({ value, children }: { value: string; children: React.ReactNode }) => (
    <option value={value}>{children}</option>
  )
  return { Select, SelectTrigger: passthrough, SelectValue: () => null, SelectContent: passthrough, SelectItem }
})

const THREE: RejectableOrder[] = [
  { id: "a", label: "ORD-A" },
  { id: "b", label: "ORD-B" },
  { id: "c", label: "ORD-C" },
]

function renderDialog(orders: RejectableOrder[], extra: Partial<React.ComponentProps<typeof RejectOrdersDialog>> = {}) {
  const onOpenChange = jest.fn()
  const onRejected = jest.fn()
  const onClosedAfterSubmit = jest.fn()
  render(
    <RejectOrdersDialog
      open
      onOpenChange={onOpenChange}
      orders={orders}
      onRejected={onRejected}
      onClosedAfterSubmit={onClosedAfterSubmit}
      {...extra}
    />
  )
  return { onOpenChange, onRejected, onClosedAfterSubmit }
}

const dialog = () => screen.getByRole("dialog")
const reasonSelect = () => within(dialog()).getByRole("combobox")
const confirmButton = (name: string | RegExp) => within(dialog()).getByRole("button", { name })

beforeEach(() => {
  mockPost.mockReset()
})

describe("RejectOrdersDialog: titles, labels and the reason (UI-SPEC § D1, § Copywriting D1)", () => {
  it("three orders: 'Reject 3 orders?', the four default reasons, 'Reject 3 orders' disabled until a reason is chosen, 'Keep orders'", () => {
    renderDialog(THREE)

    expect(within(dialog()).getByText("Reject 3 orders?")).toBeInTheDocument()
    expect(within(dialog()).getByText("Reason (kept on the order)")).toBeInTheDocument()
    expect(dialog()).toHaveTextContent(
      "The reason stays on the order for your records and isn't sent to the customer."
    )
    expect(dialog()).toHaveTextContent("The customers are told the shop couldn't accept the order.")

    const options = Array.from(reasonSelect().querySelectorAll("option")).map((o) => o.textContent)
    expect(options).toEqual(
      expect.arrayContaining([
        "Looks like a fake or test order",
        "We can't make this right now",
        "The customer asked to cancel",
        "Something else",
      ])
    )

    expect(confirmButton("Reject 3 orders")).toBeDisabled()
    expect(confirmButton("Keep orders")).toBeInTheDocument()

    fireEvent.change(reasonSelect(), { target: { value: "CANNOT_FULFIL" } })
    expect(confirmButton("Reject 3 orders")).toBeEnabled()
  })

  it("one order: 'Reject order ORD-A?', 'Reject order', 'Keep order', and the singular body", () => {
    renderDialog([THREE[0]])

    expect(within(dialog()).getByText("Reject order ORD-A?")).toBeInTheDocument()
    expect(confirmButton("Reject order")).toBeInTheDocument()
    expect(confirmButton("Keep order")).toBeInTheDocument()
    expect(dialog()).toHaveTextContent("The customer is told the shop couldn't accept the order.")
  })

  it("'Keep orders' closes the dialog and posts nothing", () => {
    const { onOpenChange } = renderDialog(THREE)
    fireEvent.click(confirmButton("Keep orders"))
    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(mockPost).not.toHaveBeenCalled()
  })
})

describe("RejectOrdersDialog: the submit and the per-order summary (SC-4)", () => {
  it("posts the ids and the chosen code to /bulk-cancel, reads 'Working…' in flight, then shows the summary and does not auto-close", async () => {
    let release: (v: unknown) => void = () => {}
    mockPost.mockImplementation(() => new Promise((r) => { release = r }))
    const { onOpenChange, onRejected } = renderDialog(THREE)

    fireEvent.change(reasonSelect(), { target: { value: "SUSPECTED_FAKE" } })
    await act(async () => {
      fireEvent.click(confirmButton("Reject 3 orders"))
    })

    expect(mockPost).toHaveBeenCalledTimes(1)
    expect(mockPost).toHaveBeenCalledWith("/api/v1/orders/bulk-cancel", {
      orderIds: ["a", "b", "c"],
      reasonCode: "SUSPECTED_FAKE",
    })
    expect(confirmButton("Working…")).toBeDisabled()
    expect(confirmButton("Keep orders")).toBeDisabled()

    await act(async () => {
      release({
        data: {
          results: [
            { orderId: "a", outcome: "REJECTED" },
            { orderId: "b", outcome: "ALREADY_ACCEPTED" },
            { orderId: "c", outcome: "REJECTED" },
          ],
        },
      })
    })

    const summary = within(dialog()).getByRole("status")
    expect(summary).toHaveTextContent("2 rejected. 1 couldn't be rejected because it had already been accepted.")
    expect(onRejected).toHaveBeenCalledWith(["a", "c"])
    // Not auto-closed: the summary is on screen and nobody asked the dialog to close.
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
    expect(within(dialog()).queryByRole("button", { name: "Working…" })).toBeNull()

    fireEvent.click(confirmButton("Close summary"))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it("omits zero counts: every order rejected reads '3 rejected.' and nothing else", async () => {
    mockPost.mockResolvedValue({
      data: {
        results: THREE.map((o) => ({ orderId: o.id, outcome: "REJECTED" })),
      },
    })
    const { onClosedAfterSubmit } = renderDialog(THREE)

    fireEvent.change(reasonSelect(), { target: { value: "OTHER" } })
    await act(async () => {
      fireEvent.click(confirmButton("Reject 3 orders"))
    })

    const summary = within(dialog()).getByRole("status")
    expect(summary.textContent?.trim()).toBe("3 rejected.")
    expect(summary).not.toHaveTextContent("couldn't")

    fireEvent.click(confirmButton("Close summary"))
    expect(onClosedAfterSubmit).toHaveBeenCalledTimes(1)
  })

  it("plural refusals: 'they'd already been accepted', and the already-rejected and not-found sentences when present", async () => {
    mockPost.mockResolvedValue({
      data: {
        results: [
          { orderId: "a", outcome: "ALREADY_ACCEPTED" },
          { orderId: "b", outcome: "ALREADY_ACCEPTED" },
          { orderId: "c", outcome: "NOT_FOUND" },
        ],
      },
    })
    const { onRejected } = renderDialog(THREE)

    fireEvent.change(reasonSelect(), { target: { value: "OTHER" } })
    await act(async () => {
      fireEvent.click(confirmButton("Reject 3 orders"))
    })

    const summary = within(dialog()).getByRole("status")
    expect(summary).toHaveTextContent("2 couldn't be rejected because they'd already been accepted.")
    expect(summary).toHaveTextContent("1 couldn't be found.")
    expect(summary).not.toHaveTextContent("0 rejected")
    expect(onRejected).toHaveBeenCalledWith([])
  })

  it("a failed request keeps the dialog open with an inline alert, no summary, and the reason kept", async () => {
    mockPost.mockRejectedValue(new Error("Network Error"))
    const { onOpenChange, onRejected } = renderDialog(THREE)

    fireEvent.change(reasonSelect(), { target: { value: "CUSTOMER_REQUEST" } })
    await act(async () => {
      fireEvent.click(confirmButton("Reject 3 orders"))
    })

    expect(within(dialog()).getByRole("alert")).toHaveTextContent(/Couldn't reject/)
    expect(within(dialog()).queryByRole("status")).toBeNull()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
    expect(onRejected).not.toHaveBeenCalled()
    expect((reasonSelect() as HTMLSelectElement).value).toBe("CUSTOMER_REQUEST")
    expect(confirmButton("Reject 3 orders")).toBeEnabled()
  })
})
