/**
 * #846 + #813 (persona close-out 2026-11-04): a signed-in customer can review their COMPLETED order, and is
 * told at the point of writing that the review is public and shows their first name and initial.
 *
 * Until now the only way to review was an unauthenticated API call with the email in the URL; no screen
 * called it. This form posts to the same-origin proxy (/api/customer-orders/review), which adds the
 * customer's token from the HttpOnly cookie. The page never sends an email, and the URL it builds
 * carries only the shop and the order number.
 *
 * MODERATION IS OUT OF SCOPE (#813's remainder): there is no flag, hide or report mechanism, and this
 * form does not pretend there is. Its notice says reviews are published straight away.
 */
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react"

import { ReviewOrder, REVIEW_PUBLIC_NOTICE_COPY } from "@/components/storefront/review-order"

const fetchMock = jest.fn()

beforeEach(() => {
  fetchMock.mockReset()
  global.fetch = fetchMock as unknown as typeof fetch
})

function respond(status: number, body: unknown = {}) {
  fetchMock.mockResolvedValue({ ok: status >= 200 && status < 300, status, json: async () => body })
}

function open() {
  render(<ReviewOrder shopSlug="mama-ades-kitchen" orderNumber="ORD-20261104-ABC123" shopName="Mama Ade's Kitchen" />)
  fireEvent.click(screen.getByRole("button", { name: /rate your order/i }))
  return screen.getByRole("form", { name: /rate your order/i })
}

describe("the entry point", () => {
  it("is a disclosure button, collapsed to start with (aria-expanded=false), that opens a labelled form", () => {
    render(<ReviewOrder shopSlug="s" orderNumber="o" shopName="Mama Ade's Kitchen" />)
    const button = screen.getByRole("button", { name: /rate your order/i })
    expect(button).toHaveAttribute("aria-expanded", "false")
    expect(screen.queryByRole("form")).toBeNull()
    fireEvent.click(button)
    expect(button).toHaveAttribute("aria-expanded", "true")
    expect(screen.getByRole("form", { name: /rate your order/i })).toBeInTheDocument()
  })
})

describe("the notice at the point of writing (#813)", () => {
  it("says the review is public and shows first name and initial, inside the form, above the submit button", () => {
    const form = open()
    const notice = within(form).getByTestId("review-public-notice")
    expect(notice).toHaveTextContent(REVIEW_PUBLIC_NOTICE_COPY)
    expect(notice).toHaveTextContent(/public/i)
    expect(notice).toHaveTextContent(/first name and the first letter of your last name/i)
    expect(notice).toHaveTextContent(/straight away/i)
    const submit = within(form).getByRole("button", { name: /post review/i })
    expect(notice.compareDocumentPosition(submit) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // The comment box is described by it, so a screen reader hears it where the writing happens.
    expect(within(form).getByLabelText(/your review/i)).toHaveAttribute("aria-describedby", expect.stringContaining(notice.id))
  })
})

describe("submitting", () => {
  it("posts the rating and comment to the proxy, with NO email anywhere, and says it is published", async () => {
    respond(201, { id: "r1", customerName: "Grace P." })
    const form = open()
    fireEvent.click(within(form).getByRole("radio", { name: "4 out of 5" }))
    fireEvent.change(within(form).getByLabelText(/your review/i), { target: { value: "Lovely jollof" } })
    fireEvent.click(within(form).getByRole("button", { name: /post review/i }))

    await screen.findByText(/thanks, your review is public now/i)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe("/api/customer-orders/review?shop=mama-ades-kitchen&order=ORD-20261104-ABC123")
    expect(url).not.toMatch(/@|email/i)
    expect(init.method).toBe("POST")
    expect(JSON.parse(init.body as string)).toEqual({ foodRating: 4, comment: "Lovely jollof" })
    expect(String(init.body)).not.toMatch(/@/)
    // The form is replaced by the thanks, so it cannot be posted twice.
    expect(screen.queryByRole("form", { name: /rate your order/i })).toBeNull()
  })

  it("refuses to submit with no rating chosen, says so, and sends nothing", () => {
    const form = open()
    fireEvent.click(within(form).getByRole("button", { name: /post review/i }))
    expect(screen.getByRole("alert")).toHaveTextContent(/choose a rating/i)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it.each([
    [400, { detail: "You have already reviewed this order" }, /already reviewed/i],
    [401, {}, /sign in again/i],
    [404, {}, /couldn.t find this order/i],
    [502, {}, /couldn.t post your review/i],
  ])("a %s answer is shown as a specific, announced message and keeps what was typed", async (status, body, expected) => {
    respond(status, body)
    const form = open()
    fireEvent.click(within(form).getByRole("radio", { name: "5 out of 5" }))
    fireEvent.change(within(form).getByLabelText(/your review/i), { target: { value: "keep me" } })
    fireEvent.click(within(form).getByRole("button", { name: /post review/i }))
    expect(await screen.findByRole("alert")).toHaveTextContent(expected)
    expect(within(form).getByLabelText(/your review/i)).toHaveValue("keep me")
  })

  it("a dropped connection is a message, not an unhandled rejection", async () => {
    fetchMock.mockRejectedValue(new Error("Network Error"))
    const form = open()
    fireEvent.click(within(form).getByRole("radio", { name: "3 out of 5" }))
    fireEvent.click(within(form).getByRole("button", { name: /post review/i }))
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(/couldn.t post your review/i))
  })
})
