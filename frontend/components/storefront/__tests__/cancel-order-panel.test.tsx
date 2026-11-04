/**
 * #868 (owner ruling 2026-11-04): a customer can cancel their own order only while it is PENDING.
 * After that there is no button, and the copy says to contact the shop.
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { CancelOrderPanel } from "@/components/storefront/cancel-order-panel"

const fetchMock = jest.fn()
beforeEach(() => {
  fetchMock.mockReset()
  global.fetch = fetchMock as unknown as typeof fetch
})

function response(status: number, body: unknown) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: async () => body })
}

const base = { orderNumber: "ORD-1", shopName: "Suya House", email: "a@b.co" }

describe("CancelOrderPanel", () => {
  it("PENDING: a Cancel button, and nothing is sent until the customer confirms", () => {
    render(<CancelOrderPanel {...base} status="PENDING" paymentStatus="NONE" onCancelled={jest.fn()} />)
    expect(screen.getByRole("button", { name: /cancel this order/i })).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it.each(["CONFIRMED", "PREPARING", "READY"])("%s: no button, and it says to contact the shop", (status) => {
    render(<CancelOrderPanel {...base} status={status} paymentStatus="NONE" onCancelled={jest.fn()} />)
    expect(screen.queryByRole("button", { name: /cancel/i })).toBeNull()
    expect(screen.getByText(/contact suya house/i)).toBeInTheDocument()
  })

  it.each(["COMPLETED", "CANCELLED", "REFUNDED", "DRAFT"])("%s: renders nothing", (status) => {
    const { container } = render(<CancelOrderPanel {...base} status={status} paymentStatus="NONE" onCancelled={jest.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })

  it("asks first, says a card payment is refunded in full, and 'Keep my order' backs out without a request", () => {
    render(<CancelOrderPanel {...base} status="PENDING" paymentStatus="CAPTURED" onCancelled={jest.fn()} />)
    fireEvent.click(screen.getByRole("button", { name: /cancel this order/i }))
    expect(screen.getByText(/refunded in full to the card you paid with/i)).toBeInTheDocument()
    fireEvent.click(screen.getByRole("button", { name: /keep my order/i }))
    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByRole("button", { name: /cancel this order/i })).toBeInTheDocument()
  })

  it("a cash order's confirmation does not mention a refund", () => {
    render(<CancelOrderPanel {...base} status="PENDING" paymentStatus="NONE" onCancelled={jest.fn()} />)
    fireEvent.click(screen.getByRole("button", { name: /cancel this order/i }))
    expect(screen.queryByText(/refund/i)).toBeNull()
  })

  it("confirming posts to the proxy with the email and an Idempotency-Key, then reports the result", async () => {
    const onCancelled = jest.fn()
    fetchMock.mockReturnValue(
      response(200, { orderNumber: "ORD-1", status: "CANCELLED", paymentStatus: "REFUNDED", refunded: true, refundAmountPennies: 1500 })
    )
    render(<CancelOrderPanel {...base} status="PENDING" paymentStatus="CAPTURED" onCancelled={onCancelled} />)
    fireEvent.click(screen.getByRole("button", { name: /cancel this order/i }))
    fireEvent.click(screen.getByRole("button", { name: /yes, cancel order/i }))

    await waitFor(() => expect(onCancelled).toHaveBeenCalled())
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe("/api/customer-orders/ORD-1/cancel")
    expect(init.method).toBe("POST")
    expect(init.headers["Idempotency-Key"]).toMatch(/.{8,}/)
    expect(JSON.parse(init.body)).toEqual({ email: "a@b.co" })
    expect(onCancelled.mock.calls[0][0]).toMatchObject({ status: "CANCELLED", refunded: true })
  })

  it("a retry after a failure re-uses the same Idempotency-Key", async () => {
    fetchMock.mockReturnValueOnce(response(502, { code: "REFUND_FAILED", detail: "We couldn't refund the card" }))
    fetchMock.mockReturnValueOnce(response(200, { status: "CANCELLED", paymentStatus: "REFUNDED", refunded: true }))
    render(<CancelOrderPanel {...base} status="PENDING" paymentStatus="CAPTURED" onCancelled={jest.fn()} />)
    fireEvent.click(screen.getByRole("button", { name: /cancel this order/i }))
    fireEvent.click(screen.getByRole("button", { name: /yes, cancel order/i }))
    expect(await screen.findByRole("alert")).toHaveTextContent(/couldn't refund the card/i)

    fireEvent.click(screen.getByRole("button", { name: /yes, cancel order/i }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(fetchMock.mock.calls[1][1].headers["Idempotency-Key"]).toBe(fetchMock.mock.calls[0][1].headers["Idempotency-Key"])
  })

  it("a 409 from core (the kitchen accepted in the meantime) shows core's contact-the-shop sentence and the order is not reported cancelled", async () => {
    const onCancelled = jest.fn()
    const onRefused = jest.fn()
    fetchMock.mockReturnValue(
      response(409, {
        code: "ORDER_NOT_CANCELLABLE_BY_CUSTOMER",
        detail: "Order ORD-1 can't be cancelled online any more: the kitchen has already accepted it. Please contact the shop to change or cancel it.",
      })
    )
    render(<CancelOrderPanel {...base} status="PENDING" paymentStatus="NONE" onCancelled={onCancelled} onRefused={onRefused} />)
    fireEvent.click(screen.getByRole("button", { name: /cancel this order/i }))
    fireEvent.click(screen.getByRole("button", { name: /yes, cancel order/i }))

    expect(await screen.findByRole("alert")).toHaveTextContent(/contact the shop/i)
    expect(onCancelled).not.toHaveBeenCalled()
    expect(onRefused).toHaveBeenCalled()
  })

  it.each(["PENDING", "AUTHORIZED", "FAILED"])(
    "F5: a PENDING order whose payment is %s is not offered a cancel button: core refuses it permanently",
    (paymentStatus) => {
      render(<CancelOrderPanel {...base} status="PENDING" paymentStatus={paymentStatus} onCancelled={jest.fn()} />)
      expect(screen.queryByRole("button", { name: /cancel/i })).toBeNull()
      expect(screen.getByText(/contact suya house/i)).toBeInTheDocument()
    }
  )

  it("F5: a 409 payment-state refusal (a deposit order) swaps the button for the contact-the-shop copy and does not loop", async () => {
    const onRefused = jest.fn()
    fetchMock.mockReturnValue(
      response(409, {
        code: "ORDER_NOT_CANCELLABLE_BY_CUSTOMER",
        reason: "PAYMENT_STATE",
        contactShop: true,
        detail: "Order ORD-1 can't be cancelled online: it is paid in parts. Please contact the shop.",
      })
    )
    // The page refetches on onRefused and the order is STILL PENDING: the same props come back.
    const { rerender } = render(
      <CancelOrderPanel {...base} status="PENDING" paymentStatus="CAPTURED" onCancelled={jest.fn()} onRefused={onRefused} />
    )
    fireEvent.click(screen.getByRole("button", { name: /cancel this order/i }))
    fireEvent.click(screen.getByRole("button", { name: /yes, cancel order/i }))

    expect(await screen.findByRole("alert")).toHaveTextContent(/paid in parts/i)
    rerender(<CancelOrderPanel {...base} status="PENDING" paymentStatus="CAPTURED" onCancelled={jest.fn()} onRefused={onRefused} />)
    expect(screen.queryByRole("button", { name: /cancel/i })).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it("F6: the Next proxy's own 502 (core unreachable) is a generic try-again, never the card-refund copy", async () => {
    fetchMock.mockReturnValue(response(502, { error: "upstream_unavailable" }))
    render(<CancelOrderPanel {...base} status="PENDING" paymentStatus="NONE" onCancelled={jest.fn()} />)
    fireEvent.click(screen.getByRole("button", { name: /cancel this order/i }))
    fireEvent.click(screen.getByRole("button", { name: /yes, cancel order/i }))

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(/couldn't reach suya house/i)
    expect(alert).toHaveTextContent(/try again/i)
    expect(alert).not.toHaveTextContent(/refund/i)
    expect(alert).not.toHaveTextContent(/card/i)
  })

  it("F6: core's typed REFUND_FAILED says the order is cancelled and the refund did not complete", async () => {
    fetchMock.mockReturnValue(response(502, { code: "REFUND_FAILED" }))
    render(<CancelOrderPanel {...base} status="PENDING" paymentStatus="CAPTURED" onCancelled={jest.fn()} />)
    fireEvent.click(screen.getByRole("button", { name: /cancel this order/i }))
    fireEvent.click(screen.getByRole("button", { name: /yes, cancel order/i }))

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(/has been cancelled/i)
    expect(alert).toHaveTextContent(/refund/i)
    expect(alert).not.toHaveTextContent(/has not been cancelled/i)
  })

  it("#868 ruling: a deposit order with the balance still due offers Cancel and states the DEPOSIT as the amount refunded", () => {
    render(
      <CancelOrderPanel
        {...base}
        status="PENDING"
        paymentStatus="CAPTURED"
        totalAmountPennies={3000}
        depositAmountPennies={900}
        balanceDuePennies={2100}
        onCancelled={jest.fn()}
      />
    )
    fireEvent.click(screen.getByRole("button", { name: /cancel this order/i }))
    expect(screen.getByText(/£9\.00 deposit you paid will be refunded/i)).toBeInTheDocument()
    expect(screen.queryByText(/£30\.00/)).toBeNull()
  })

  it("#868 ruling: a deposit order whose balance is also paid, and an ordinary card order, state the whole amount paid", () => {
    const { unmount } = render(
      <CancelOrderPanel
        {...base}
        status="PENDING"
        paymentStatus="CAPTURED"
        totalAmountPennies={3000}
        depositAmountPennies={900}
        balanceDuePennies={0}
        onCancelled={jest.fn()}
      />
    )
    fireEvent.click(screen.getByRole("button", { name: /cancel this order/i }))
    expect(screen.getByText(/£30\.00 you paid will be refunded in full/i)).toBeInTheDocument()
    unmount()

    render(<CancelOrderPanel {...base} status="PENDING" paymentStatus="CAPTURED" totalAmountPennies={1500} onCancelled={jest.fn()} />)
    fireEvent.click(screen.getByRole("button", { name: /cancel this order/i }))
    expect(screen.getByText(/£15\.00 you paid will be refunded in full/i)).toBeInTheDocument()
  })
})
