/**
 * /track tells a screen-reader customer when the order on screen MOVES ON (37-51, UI-SPEC § F1,
 * RWO-081 / UXT-081 #850, D-05) — the same contract as the per-shop order page
 * (order-live-region.test.tsx).
 *
 * Before 37-51 the /track result polled every 15 s and redrew its stepper and status chip, with no
 * live region anywhere on the page: a status change was silent to a screen reader.
 *
 * What this suite holds the page to:
 *   - ONE role=status aria-live=polite region, present before any result;
 *   - looking an order up is not a change, so it announces nothing (UI-SPEC F loading);
 *   - a poll whose status DIFFERS from the order's previous status writes
 *     "Order update: {label}. {description}."; the same status again writes nothing (no DOM
 *     mutation inside the region, MutationObserver); a failed poll writes nothing;
 *   - a signed-in customer's own order (#458 auto path) is announced the same way when it moves on;
 *   - the current tracker step carries aria-current="step", and only that one;
 *   - tracker step labels never clip: no truncate / nowrap / ellipsis, and each step column may
 *     shrink (min-w-0) so 130% text at 360px wraps instead of pushing the row wider (UXT-080).
 *
 * Tracking stays bound to number + email (goods F3): every guest read below carries the email.
 */
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react"
import { useSearchParams } from "next/navigation"
import TrackOrderPage from "@/app/track/page"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

const mockGetSession = jest.fn()
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => mockGetSession(),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedSearchParams = useSearchParams as jest.Mock

const ORDER_NUMBER = "ORD-3751TRAK-20261014-REGION02"
const EMAIL = "guest@example.com"
const ORDER = {
  orderNumber: ORDER_NUMBER,
  status: "PENDING",
  shopName: "Ada's Kitchen",
  totalAmountPennies: 1850,
  itemCount: 2,
  unitCount: 2,
  createdAt: "2026-10-14T10:00:00Z",
  updatedAt: "2026-10-14T10:00:05Z",
}
const SESSION = {
  profile: { sub: "u1", email: "alice@example.com", name: "Alice", emailVerified: true },
  expiresAt: Math.floor(Date.now() / 1000) + 300,
}

let trackingAnswer: Record<string, unknown> | "fail" = ORDER
let intervalSpy: jest.SpyInstance

function statusRegion(): HTMLElement {
  const regions = screen.getAllByRole("status")
  expect(regions).toHaveLength(1)
  expect(regions[0]).toHaveAttribute("aria-live", "polite")
  return regions[0]
}

function currentPoll(): () => unknown {
  const polls = intervalSpy.mock.calls.filter(([, delay]) => delay === 15000)
  expect(polls.length).toBeGreaterThan(0)
  return polls[polls.length - 1][0] as () => unknown
}

async function poll(next: Record<string, unknown> | "fail") {
  trackingAnswer = next
  const calls = mockGet.mock.calls.length
  await act(async () => {
    await currentPoll()()
  })
  expect(mockGet.mock.calls.length).toBeGreaterThan(calls)
  // F3: the poll is still the number + email read.
  expect(mockGet).toHaveBeenLastCalledWith(`/public/orders/${ORDER_NUMBER}`, { params: { email: EMAIL } })
}

function recordMutations(el: HTMLElement) {
  const records: MutationRecord[] = []
  const observer = new MutationObserver((batch) => records.push(...batch))
  observer.observe(el, { childList: true, characterData: true, subtree: true })
  return {
    stop: () => {
      records.push(...observer.takeRecords())
      observer.disconnect()
      return records
    },
  }
}

async function guestLookup() {
  render(<TrackOrderPage />)
  fireEvent.change(await screen.findByLabelText(/order number/i), { target: { value: ORDER_NUMBER } })
  fireEvent.change(screen.getByLabelText(/email/i), { target: { value: EMAIL } })
  fireEvent.click(screen.getByRole("button", { name: /track order/i }))
  await screen.findByText("Ada's Kitchen")
}

beforeEach(() => {
  mockGet.mockReset()
  mockGetSession.mockReset()
  mockGetSession.mockResolvedValue(null)
  mockedSearchParams.mockReturnValue({ get: () => null })
  sessionStorage.clear()
  trackingAnswer = ORDER
  mockGet.mockImplementation((url: string) => {
    if (url === `/public/orders/${ORDER_NUMBER}`) {
      return trackingAnswer === "fail"
        ? Promise.reject(new Error("network"))
        : Promise.resolve({ data: trackingAnswer })
    }
    return Promise.reject(new Error(`unexpected GET ${url}`))
  })
  global.fetch = jest.fn().mockResolvedValue({
    ok: false,
    status: 401,
    json: async () => ({}),
  }) as unknown as typeof fetch
  intervalSpy = jest.spyOn(globalThis, "setInterval")
})

afterEach(() => {
  intervalSpy.mockRestore()
})

describe("F1 on /track: one status region, announcing changes only", () => {
  it("the region is on the page before any result, and a lookup is not a change: it stays empty", async () => {
    render(<TrackOrderPage />)
    await screen.findByLabelText(/order number/i)
    expect(statusRegion()).toHaveTextContent(/^$/)

    fireEvent.change(screen.getByLabelText(/order number/i), { target: { value: ORDER_NUMBER } })
    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: EMAIL } })
    fireEvent.click(screen.getByRole("button", { name: /track order/i }))
    await screen.findByText("Ada's Kitchen")
    expect(statusRegion()).toHaveTextContent(/^$/)
  })

  it("PENDING then CONFIRMED: 'Order update: Confirmed. Shop accepted your order.'", async () => {
    await guestLookup()
    await poll({ ...ORDER, status: "CONFIRMED" })
    await waitFor(() =>
      expect(statusRegion()).toHaveTextContent("Order update: Confirmed. Shop accepted your order.")
    )
  })

  it("the same status polled again changes nothing inside the region", async () => {
    await guestLookup()
    await poll({ ...ORDER, status: "CONFIRMED" })
    const region = statusRegion()
    await waitFor(() => expect(region).toHaveTextContent("Order update: Confirmed."))

    const recorder = recordMutations(region)
    await poll({ ...ORDER, status: "CONFIRMED", updatedAt: "2026-10-14T10:01:15Z" })
    await poll({ ...ORDER, status: "CONFIRMED", updatedAt: "2026-10-14T10:01:30Z" })
    expect(recorder.stop()).toHaveLength(0)
    expect(statusRegion()).toHaveTextContent("Order update: Confirmed. Shop accepted your order.")
  })

  it("a failed poll writes nothing into the region", async () => {
    await guestLookup()
    await poll({ ...ORDER, status: "PREPARING" })
    const region = statusRegion()
    await waitFor(() => expect(region).toHaveTextContent("Order update: Preparing. Being prepared now."))

    const recorder = recordMutations(region)
    await poll("fail")
    expect(recorder.stop()).toHaveLength(0)
    expect(statusRegion()).toHaveTextContent("Order update: Preparing. Being prepared now.")
  })

  it("a signed-in customer's own order is announced when it moves on (the #458 auto path)", async () => {
    mockGetSession.mockResolvedValue({ ...SESSION, profile: { ...SESSION.profile, email: EMAIL } })
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ content: [{ ...ORDER, status: "PREPARING" }] }),
    }) as unknown as typeof fetch

    render(<TrackOrderPage />)
    await screen.findByText("Ada's Kitchen")
    expect(statusRegion()).toHaveTextContent(/^$/)

    // F2 (37-F review): "for collection" is said only for an order the response says is one.
    await poll({ ...ORDER, fulfilmentType: "COLLECTION", status: "READY" })
    await waitFor(() =>
      expect(statusRegion()).toHaveTextContent("Order update: Ready. Ready for collection.")
    )
  })
})

describe("F1 / F6 on /track: step semantics and wrapping", () => {
  it("only the current step carries aria-current='step', and it moves with the status", async () => {
    await guestLookup()
    let current = document.querySelectorAll('[aria-current="step"]')
    expect(current).toHaveLength(1)
    expect(current[0]).toHaveTextContent("Received")

    await poll({ ...ORDER, status: "READY" })
    await waitFor(() => {
      current = document.querySelectorAll('[aria-current="step"]')
      expect(current).toHaveLength(1)
      expect(current[0]).toHaveTextContent("Ready")
    })
  })

  it("step labels never clip, and each step column may shrink so the row cannot overflow (UXT-080)", async () => {
    await guestLookup()
    const steps = document.querySelectorAll("[data-tracker-step]")
    expect(steps).toHaveLength(5)
    for (const step of Array.from(steps)) {
      expect(step.className.toString()).toMatch(/\bmin-w-0\b/)
      for (const el of [step, ...Array.from(step.querySelectorAll("*"))]) {
        expect(el.className.toString()).not.toMatch(/\b(truncate|whitespace-nowrap|text-ellipsis|line-clamp-\d)\b/)
      }
    }
  })
})

describe("37-F review F2 on /track: READY is described for THIS order's fulfilment type", () => {
  it("a DELIVERY order is announced as ready for delivery", async () => {
    trackingAnswer = { ...ORDER, fulfilmentType: "DELIVERY" }
    await guestLookup()
    await poll({ ...ORDER, fulfilmentType: "DELIVERY", status: "READY" })
    await waitFor(() =>
      expect(statusRegion()).toHaveTextContent("Order update: Ready. Ready for delivery.")
    )
  })

  it("a COLLECTION order is announced as ready for collection", async () => {
    trackingAnswer = { ...ORDER, fulfilmentType: "COLLECTION" }
    await guestLookup()
    await poll({ ...ORDER, fulfilmentType: "COLLECTION", status: "READY" })
    await waitFor(() =>
      expect(statusRegion()).toHaveTextContent("Order update: Ready. Ready for collection.")
    )
  })

  it("a response with no fulfilment type gets the neutral description, not an invented one", async () => {
    await guestLookup()
    await poll({ ...ORDER, status: "READY" })
    await waitFor(() =>
      expect(statusRegion()).toHaveTextContent("Order update: Ready. Your order is ready.")
    )
  })
})
