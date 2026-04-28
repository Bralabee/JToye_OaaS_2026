/**
 * Tests for RefundDialog (Phase 17-04 / VOPS-02).
 *
 * Validates:
 *   - Idempotency-Key header is forwarded on every submit
 *   - Zod amount validation catches over-refund and non-numeric input
 *   - Submit button shows "Refunding…" while in flight
 *   - Server error from ProblemDetail is surfaced via role=alert
 *   - amountPennies is omitted when the user leaves the amount blank
 */

import { render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { RefundDialog } from "../RefundDialog"

// Mock the api client so we can assert the headers + body shape.
jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: {
    post: jest.fn(),
  },
}))

import apiClient from "@/lib/api-client"
const mockedPost = apiClient.post as jest.MockedFunction<typeof apiClient.post>

const STABLE_UUID = "11111111-2222-3333-4444-555555555555"

beforeEach(() => {
  mockedPost.mockReset()
  // crypto.randomUUID is provided by jsdom in newer Node versions but we
  // stub it deterministically so the assertion on Idempotency-Key is
  // value-equal, not just shape-equal.
  Object.defineProperty(globalThis, "crypto", {
    value: { randomUUID: jest.fn(() => STABLE_UUID) },
    configurable: true,
  })
})

function renderDialog(overrides: Partial<React.ComponentProps<typeof RefundDialog>> = {}) {
  const onOpenChange = jest.fn()
  const onSuccess = jest.fn()
  render(
    <RefundDialog
      open
      onOpenChange={onOpenChange}
      orderId="order-abc"
      remainingPennies={1000}
      onSuccess={onSuccess}
      {...overrides}
    />
  )
  return { onOpenChange, onSuccess }
}

describe("RefundDialog", () => {
  it("renders amount placeholder showing remaining as £X.XX", () => {
    renderDialog({ remainingPennies: 1234 })
    const input = screen.getByLabelText(/amount/i) as HTMLInputElement
    expect(input.placeholder).toMatch(/12\.34/)
    // Helper text mentions the remaining amount as well.
    expect(screen.getByText(/leave blank for full remaining/i)).toHaveTextContent(
      /£12\.34/
    )
  })

  it("submits with correct payload and Idempotency-Key header", async () => {
    const user = userEvent.setup()
    mockedPost.mockResolvedValueOnce({
      data: {
        id: "refund-1",
        tenantId: "t",
        orderId: "order-abc",
        stripeRefundId: "re_test",
        idempotencyKey: STABLE_UUID,
        amountPennies: 250,
        currency: "gbp",
        reason: "REQUESTED_BY_CUSTOMER",
        reasonNote: "missing item",
        status: "succeeded",
        failureReason: null,
        requestedAt: "2026-04-28T10:00:00Z",
        updatedAt: "2026-04-28T10:00:00Z",
      },
    } as unknown as Awaited<ReturnType<typeof mockedPost>>)

    const { onSuccess } = renderDialog({ remainingPennies: 1000 })

    await user.type(screen.getByLabelText(/amount/i), "2.50")
    await user.selectOptions(
      screen.getByLabelText(/reason/i),
      "REQUESTED_BY_CUSTOMER"
    )
    await user.type(screen.getByLabelText(/note/i), "missing item")
    await user.click(screen.getByRole("button", { name: /^Issue refund$/i }))

    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    const [url, body, config] = mockedPost.mock.calls[0]
    expect(url).toBe("/api/v1/orders/order-abc/refund")
    expect(body).toEqual({
      amountPennies: 250,
      reason: "REQUESTED_BY_CUSTOMER",
      note: "missing item",
    })
    expect(config?.headers).toMatchObject({ "Idempotency-Key": STABLE_UUID })
    expect(onSuccess).toHaveBeenCalledTimes(1)
  })

  it("rejects amount exceeding remaining and does not call apiClient", async () => {
    const user = userEvent.setup()
    renderDialog({ remainingPennies: 1000 })

    await user.type(screen.getByLabelText(/amount/i), "15.00")
    await user.click(screen.getByRole("button", { name: /^Issue refund$/i }))

    expect(
      await screen.findByText(/exceeds remaining/i)
    ).toBeInTheDocument()
    expect(mockedPost).not.toHaveBeenCalled()
  })

  it("rejects non-numeric amount input", async () => {
    const user = userEvent.setup()
    renderDialog({ remainingPennies: 1000 })

    await user.type(screen.getByLabelText(/amount/i), "abc")
    await user.click(screen.getByRole("button", { name: /^Issue refund$/i }))

    expect(
      await screen.findByText(/use a valid amount/i)
    ).toBeInTheDocument()
    expect(mockedPost).not.toHaveBeenCalled()
  })

  it("disables submit button and shows 'Refunding…' while in flight", async () => {
    const user = userEvent.setup()
    // Never-resolving promise so we can observe the in-flight state.
    mockedPost.mockImplementationOnce(() => new Promise(() => {}))
    renderDialog({ remainingPennies: 1000 })

    await user.click(screen.getByRole("button", { name: /^Issue refund$/i }))

    const submit = await screen.findByRole("button", {
      name: /Refunding/i,
    })
    expect(submit).toBeDisabled()
  })

  it("surfaces ProblemDetail.detail from a failed POST", async () => {
    const user = userEvent.setup()
    mockedPost.mockRejectedValueOnce({
      response: {
        data: { detail: "Order is already REFUNDED" },
      },
    })
    renderDialog({ remainingPennies: 1000 })

    await user.click(screen.getByRole("button", { name: /^Issue refund$/i }))

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(/already REFUNDED/i)
    // Submit button is enabled again so the user can retry or cancel.
    expect(screen.getByRole("button", { name: /^Issue refund$/i })).not.toBeDisabled()
  })

  it("omits amountPennies from payload when user leaves the field blank", async () => {
    const user = userEvent.setup()
    mockedPost.mockResolvedValueOnce({
      data: {
        id: "refund-2",
        tenantId: "t",
        orderId: "order-abc",
        stripeRefundId: "re_full",
        idempotencyKey: STABLE_UUID,
        amountPennies: 1000,
        currency: "gbp",
        reason: "REQUESTED_BY_CUSTOMER",
        reasonNote: null,
        status: "succeeded",
        failureReason: null,
        requestedAt: "2026-04-28T10:00:00Z",
        updatedAt: "2026-04-28T10:00:00Z",
      },
    } as unknown as Awaited<ReturnType<typeof mockedPost>>)
    renderDialog({ remainingPennies: 1000 })

    await user.click(screen.getByRole("button", { name: /^Issue refund$/i }))

    await waitFor(() => expect(mockedPost).toHaveBeenCalledTimes(1))
    const [, body] = mockedPost.mock.calls[0]
    expect(body).toEqual({
      reason: "REQUESTED_BY_CUSTOMER",
      // amountPennies is undefined → axios serializes as omitted JSON key.
    })
  })
})
