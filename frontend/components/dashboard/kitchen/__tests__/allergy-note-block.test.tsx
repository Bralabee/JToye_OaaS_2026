/**
 * 31.1-22 (#812, D-15): the allergy note block on the kitchen board and the vendor order detail.
 *
 * What these tests exist to catch:
 *   - the note shown as INERT text (T-31.1-76): a customer's free text reaches staff screens;
 *   - NO optimistic "read" (T-31.1-77): the read line appears only after the server's 200, and
 *     carries the server's who/when, never the click time;
 *   - a refusal or a failure is ANNOUNCED (role=alert) and the button stays (T-31.1-78);
 *   - one press, one request, however impatient the cook is.
 *
 * The request is asserted at the HTTP client, not at a mocked `acknowledgeAllergyNote`, so the
 * URL the endpoint lives at is part of what is tested.
 */
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { useSession } from "next-auth/react"
import { AllergyNoteBlock } from "../allergy-note-block"

const mockPost = jest.fn()
jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: { post: (...args: unknown[]) => mockPost(...args) },
}))

const NOTE = "My son has a peanut allergy. Please no satay sauce."
const ACK_URL = "/api/v1/orders/order-1/allergy-note/acknowledgement"
// 17:05Z on 3 Oct 2026 is 18:05 in London (BST).
const ACK_AT = "2026-10-03T17:05:00Z"

function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

/** A JWT-shaped string whose payload carries `sub` (the signature is never checked here). */
function tokenFor(sub: string): string {
  const b64url = (o: object) =>
    Buffer.from(JSON.stringify(o)).toString("base64").replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_")
  return `${b64url({ alg: "RS256" })}.${b64url({ sub })}.sig`
}

function renderBlock(props: Partial<React.ComponentProps<typeof AllergyNoteBlock>> = {}) {
  return render(
    <AllergyNoteBlock
      orderId="order-1"
      orderLabel="ORD-1"
      note={NOTE}
      acknowledgedAt={null}
      acknowledgedBy={null}
      {...props}
    />
  )
}

beforeEach(() => {
  mockPost.mockReset()
})

describe("AllergyNoteBlock — shown", () => {
  it("shows the ALLERGY NOTE label, the note and a large 'Mark allergy note as read' button", () => {
    renderBlock()
    const block = screen.getByTestId("allergy-note")
    expect(block).toHaveTextContent("ALLERGY NOTE")
    expect(block).toHaveTextContent(NOTE)
    const button = screen.getByRole("button", { name: /^Mark allergy note as read/ })
    // The 44px floor the KDS controls are held to (31 UI-SPEC; page.tsx bump/print buttons).
    expect(button.className).toMatch(/\bmin-h-11\b/)
    // Named per order: a board has many of these buttons.
    expect(button).toHaveAccessibleName("Mark allergy note as read for order ORD-1")
  })

  it("is a named group, so a screen reader hears 'ALLERGY NOTE' before the text", () => {
    renderBlock()
    expect(screen.getByRole("group", { name: "ALLERGY NOTE" })).toBe(screen.getByTestId("allergy-note"))
  })

  it("renders a note carrying markup as inert text (T-31.1-76)", () => {
    const hostile = '<script>window.__pwned = 1</script><img src=x onerror="alert(1)">'
    const { container } = renderBlock({ note: hostile })
    expect(container.querySelector("script")).toBeNull()
    expect(container.querySelector("img")).toBeNull()
    expect(screen.getByTestId("allergy-note-text")).toHaveTextContent(hostile)
  })

  it("renders nothing at all for an order with no note", () => {
    const { container } = renderBlock({ note: null })
    expect(container.innerHTML).toBe("")
  })

  it("an already-acknowledged note shows who and when, and no button", () => {
    renderBlock({ acknowledgedAt: ACK_AT, acknowledgedBy: "kim" })
    expect(screen.getByTestId("allergy-note-read")).toHaveTextContent(/Read by kim at 18:05/)
    expect(screen.queryByRole("button")).toBeNull()
  })
})

describe("AllergyNoteBlock — marking it read", () => {
  it("one press sends ONE acknowledgement, and 'read' appears only after the 200 (T-31.1-77)", async () => {
    const pending = deferred<{ data: unknown }>()
    mockPost.mockReturnValue(pending.promise)
    const onAcknowledged = jest.fn()
    renderBlock({ onAcknowledged })

    const button = screen.getByRole("button", { name: /^Mark allergy note as read/ })
    fireEvent.click(button)
    fireEvent.click(button)
    fireEvent.click(button)

    expect(mockPost).toHaveBeenCalledTimes(1)
    expect(mockPost).toHaveBeenCalledWith(ACK_URL)
    // Not optimistic: nothing says "read" while the request is in flight.
    expect(screen.queryByTestId("allergy-note-read")).toBeNull()
    expect(button).toBeDisabled()

    await act(async () => {
      pending.resolve({
        data: { id: "order-1", allergyNoteAcknowledgedAt: ACK_AT, allergyNoteAcknowledgedBy: "kim" },
      })
    })

    expect(await screen.findByTestId("allergy-note-read")).toHaveTextContent(/Read by kim at 18:05/)
    expect(screen.queryByRole("button", { name: /^Mark allergy note as read/ })).toBeNull()
    expect(onAcknowledged).toHaveBeenCalledWith({ acknowledgedAt: ACK_AT, acknowledgedBy: "kim" })
  })

  it("shows the SERVER's who and when — a colleague's earlier acknowledgement, not this click", async () => {
    // First write wins on the server (31.1-13): a second press anywhere returns the first.
    mockPost.mockResolvedValue({
      data: { allergyNoteAcknowledgedAt: "2026-10-03T16:58:00Z", allergyNoteAcknowledgedBy: "ade" },
    })
    renderBlock()
    fireEvent.click(screen.getByRole("button", { name: /^Mark allergy note as read/ }))
    expect(await screen.findByTestId("allergy-note-read")).toHaveTextContent(/Read by ade at 17:58/)
  })

  it("a 403 is announced and the button stays (T-31.1-78)", async () => {
    mockPost.mockRejectedValue({ response: { status: 403 } })
    renderBlock()
    fireEvent.click(screen.getByRole("button", { name: /^Mark allergy note as read/ }))

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Not marked as read: your account cannot act on this shop's orders."
    )
    expect(screen.getByRole("button", { name: /^Mark allergy note as read/ })).toBeEnabled()
    expect(screen.queryByTestId("allergy-note-read")).toBeNull()
  })

  it("a network failure is announced and the button stays", async () => {
    mockPost.mockRejectedValue(new Error("Network Error"))
    renderBlock()
    fireEvent.click(screen.getByRole("button", { name: /^Mark allergy note as read/ }))

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Not marked as read: the request did not go through. Try again."
    )
    expect(screen.getByRole("button", { name: /^Mark allergy note as read/ })).toBeEnabled()
  })

  it("a 200 that carries no acknowledgement is NOT shown as read", async () => {
    mockPost.mockResolvedValue({ data: { id: "order-1" } })
    renderBlock()
    fireEvent.click(screen.getByRole("button", { name: /^Mark allergy note as read/ }))

    expect(await screen.findByRole("alert")).toHaveTextContent("Not marked as read")
    expect(screen.queryByTestId("allergy-note-read")).toBeNull()
  })

  it("a retry after a failure can succeed", async () => {
    mockPost
      .mockRejectedValueOnce(new Error("Network Error"))
      .mockResolvedValueOnce({ data: { allergyNoteAcknowledgedAt: ACK_AT, allergyNoteAcknowledgedBy: "kim" } })
    renderBlock()
    fireEvent.click(screen.getByRole("button", { name: /^Mark allergy note as read/ }))
    await screen.findByRole("alert")
    fireEvent.click(screen.getByRole("button", { name: /^Mark allergy note as read/ }))
    expect(await screen.findByTestId("allergy-note-read")).toHaveTextContent(/Read by kim at 18:05/)
    await waitFor(() => expect(screen.queryByRole("alert")).toBeNull())
  })
})

describe("AllergyNoteBlock — who read it", () => {
  // 31.1-13 stores the JWT subject (a Keycloak user UUID), and a STAFF user cannot read the staff
  // directory (GROUP_ADMIN only), so a name is not available here. The block never shows the raw
  // id: it says "you" when the viewer is the one who read it, and "a member of staff" otherwise.
  const SUB = "0f6c2a1e-3b4d-4e5f-8a9b-0c1d2e3f4a5b"

  it("says 'you' when the signed-in account is the one that read it", () => {
    jest.mocked(useSession).mockReturnValue({
      data: { user: { name: "Kim" }, accessToken: tokenFor(SUB), expires: "2099-12-31" },
      status: "authenticated",
    } as unknown as ReturnType<typeof useSession>)
    renderBlock({ acknowledgedAt: ACK_AT, acknowledgedBy: SUB })
    expect(screen.getByTestId("allergy-note-read")).toHaveTextContent(/Read by you at 18:05/)
  })

  it("says 'a member of staff' for another account, never the raw id", () => {
    jest.mocked(useSession).mockReturnValue({
      data: { user: { name: "Ade" }, accessToken: tokenFor("11111111-2222-4333-8444-555555555555"), expires: "2099-12-31" },
      status: "authenticated",
    } as unknown as ReturnType<typeof useSession>)
    renderBlock({ acknowledgedAt: ACK_AT, acknowledgedBy: SUB })
    const read = screen.getByTestId("allergy-note-read")
    expect(read).toHaveTextContent(/Read by a member of staff at 18:05/)
    expect(read).not.toHaveTextContent(SUB)
  })
})
