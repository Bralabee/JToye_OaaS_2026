/**
 * The order page tells a screen-reader customer when their order MOVES ON (37-51, UI-SPEC § F1,
 * RWO-081 / UXT-081 #850, D-05).
 *
 * Before 37-51 the page polled the tracking endpoint every 15 s and re-rendered the stepper, but the
 * only live region on it was the 37-37 first-view confirmation, so a status change was silent to a
 * screen reader: the persona waited at the page and was never told the shop had accepted the order.
 *
 * What this suite holds the page to:
 *   - ONE role=status aria-live=polite region (37-37's region, reused — not a second one);
 *   - a poll whose status DIFFERS from the previous one writes "Order update: {label}. {description}."
 *     into it; a poll with the SAME status writes nothing at all (no DOM mutation inside the region,
 *     measured with a MutationObserver — a clear-and-rewrite of the same text is still an announcement);
 *   - the first load is not a change, so it announces nothing (UI-SPEC F loading);
 *   - a failed poll announces nothing and leaves the region as it was (UI-SPEC F1 error, planner
 *     assumption); the visible error copy stays outside the region;
 *   - 37-37's first-view message stays the region's FIRST message: an unchanged first poll does not
 *     replace it, a real change after it does;
 *   - the current tracker step carries aria-current="step", and only that one; the copy button is
 *     named "Copy order number".
 *
 * Polls are driven by capturing the page's own 15 s interval callback and invoking it, so every
 * poll in this file is a real call through the page's fetch path, not a re-render.
 */
import { Suspense } from "react"
import { act, render, screen, waitFor } from "@testing-library/react"
import OrderTrackingPage from "@/app/shop/[slug]/orders/[orderNumber]/page"

const mockGet = jest.fn()
jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: (...args: unknown[]) => mockGet(...args) },
}))

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => Promise.resolve(null),
}))

const SLUG = "mama-ade"
const ORDER_NUMBER = "ORD-3751LIVE-20261014-REGION01"
const EMAIL = "ade@example.com"
const MARKER = `jtoye-just-placed:${ORDER_NUMBER}`

const ORDER = {
  orderNumber: ORDER_NUMBER,
  status: "PENDING",
  paymentStatus: "NONE",
  shopName: "Mama Ade's Kitchen",
  fulfilmentType: "COLLECTION",
  totalAmountPennies: 1200,
  itemCount: 1,
  unitCount: 1,
  createdAt: "2026-10-14T10:00:00Z",
  updatedAt: "2026-10-14T10:00:05Z",
}

/** What the tracking endpoint answers next. "fail" rejects, as a dropped connection does. */
let trackingAnswer: Record<string, unknown> | "fail" = ORDER

function mockEndpoints() {
  mockGet.mockImplementation((url: string) => {
    if (url === `/public/orders/${ORDER_NUMBER}`) {
      return trackingAnswer === "fail"
        ? Promise.reject(new Error("network"))
        : Promise.resolve({ data: trackingAnswer })
    }
    if (url === `/public/shops/${SLUG}`) return Promise.reject(new Error("not needed here"))
    return Promise.reject(new Error(`unexpected GET ${url}`))
  })
}

function resolvedThenable<T>(value: T): Promise<T> {
  const p: Promise<T> & { status?: string; value?: T } = Promise.resolve(value)
  p.status = "fulfilled"
  p.value = value
  return p
}

function renderOrderPage() {
  return render(
    <Suspense fallback={<div>loading</div>}>
      <OrderTrackingPage params={resolvedThenable({ slug: SLUG, orderNumber: ORDER_NUMBER })} />
    </Suspense>
  )
}

/** The page's one status region. Asserting the count here is what makes "one region" a check. */
function statusRegion(): HTMLElement {
  const regions = screen.getAllByRole("status")
  expect(regions).toHaveLength(1)
  expect(regions[0]).toHaveAttribute("aria-live", "polite")
  return regions[0]
}

let intervalSpy: jest.SpyInstance

/** The page's CURRENT 15 s poll callback (the effect re-registers it whenever the order changes). */
function currentPoll(): () => unknown {
  const polls = intervalSpy.mock.calls.filter(([, delay]) => delay === 15000)
  expect(polls.length).toBeGreaterThan(0)
  return polls[polls.length - 1][0] as () => unknown
}

/** One real poll: the tracking endpoint answers `next`, and the page's own poll callback runs. */
async function poll(next: Record<string, unknown> | "fail") {
  trackingAnswer = next
  const calls = mockGet.mock.calls.length
  await act(async () => {
    await currentPoll()()
  })
  // Non-vacuity: the poll really went through the tracking read.
  expect(mockGet.mock.calls.length).toBeGreaterThan(calls)
}

/** Records every DOM mutation inside `el` from now on. */
function recordMutations(el: HTMLElement) {
  const records: MutationRecord[] = []
  const observer = new MutationObserver((batch) => records.push(...batch))
  observer.observe(el, { childList: true, characterData: true, subtree: true })
  return {
    /** Flush pending records synchronously, then stop observing. */
    stop: () => {
      records.push(...observer.takeRecords())
      observer.disconnect()
      return records
    },
  }
}

beforeEach(() => {
  mockGet.mockReset()
  trackingAnswer = ORDER
  mockEndpoints()
  localStorage.clear()
  sessionStorage.clear()
  localStorage.setItem(`jtoye-checkout-email-${SLUG}`, EMAIL)
  intervalSpy = jest.spyOn(globalThis, "setInterval")
})

afterEach(() => {
  intervalSpy.mockRestore()
})

describe("F1: a status change is announced once, through the one status region", () => {
  it("the first load is not a change: the region is empty after the order loads", async () => {
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })
    expect(statusRegion()).toHaveTextContent(/^$/)
  })

  it("PENDING then CONFIRMED: the region says 'Order update: Confirmed. Shop accepted your order.'", async () => {
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })

    await poll({ ...ORDER, status: "CONFIRMED", updatedAt: "2026-10-14T10:01:00Z" })

    await waitFor(() =>
      expect(statusRegion()).toHaveTextContent("Order update: Confirmed. Shop accepted your order.")
    )
  })

  it("CONFIRMED polled again: nothing inside the region changes, so nothing is re-announced", async () => {
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })
    await poll({ ...ORDER, status: "CONFIRMED" })
    const region = statusRegion()
    await waitFor(() => expect(region).toHaveTextContent("Order update: Confirmed."))

    const recorder = recordMutations(region)
    await poll({ ...ORDER, status: "CONFIRMED", updatedAt: "2026-10-14T10:01:15Z" })
    await poll({ ...ORDER, status: "CONFIRMED", updatedAt: "2026-10-14T10:01:30Z" })
    const mutations = recorder.stop()

    expect(mutations).toHaveLength(0)
    expect(statusRegion()).toHaveTextContent("Order update: Confirmed. Shop accepted your order.")
  })

  it("each later change is announced in turn: PREPARING, then READY", async () => {
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })

    await poll({ ...ORDER, status: "PREPARING" })
    await waitFor(() =>
      expect(statusRegion()).toHaveTextContent("Order update: Preparing. Being prepared now.")
    )
    await poll({ ...ORDER, status: "READY" })
    await waitFor(() =>
      expect(statusRegion()).toHaveTextContent("Order update: Ready. Ready for collection.")
    )
  })

  it("a cancellation is a change too, and is announced in the same form", async () => {
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })

    await poll({ ...ORDER, status: "CANCELLED" })
    await waitFor(() =>
      expect(statusRegion()).toHaveTextContent("Order update: Cancelled. This order was cancelled.")
    )
  })

  it("a failed poll announces nothing: the region keeps its last message and no error enters it", async () => {
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })
    await poll({ ...ORDER, status: "CONFIRMED" })
    const region = statusRegion()
    await waitFor(() => expect(region).toHaveTextContent("Order update: Confirmed."))

    const recorder = recordMutations(region)
    await poll("fail")
    const mutations = recorder.stop()

    expect(mutations).toHaveLength(0)
    expect(statusRegion()).toHaveTextContent("Order update: Confirmed. Shop accepted your order.")
    expect(statusRegion()).not.toHaveTextContent(/could not|couldn't|error/i)
  })
})

describe("F1 with C3: the first-view confirmation stays the region's first message", () => {
  it("an unchanged first poll keeps 'Order confirmed, {number}'; a real change then replaces it", async () => {
    sessionStorage.setItem(MARKER, ORDER_NUMBER)
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: "Order confirmed" })
    await waitFor(() => expect(statusRegion()).toHaveTextContent(`Order confirmed, ${ORDER_NUMBER}`))

    const region = statusRegion()
    const recorder = recordMutations(region)
    await poll({ ...ORDER, status: "PENDING" })
    expect(recorder.stop()).toHaveLength(0)
    expect(statusRegion()).toHaveTextContent(`Order confirmed, ${ORDER_NUMBER}`)

    await poll({ ...ORDER, status: "CONFIRMED" })
    await waitFor(() =>
      expect(statusRegion()).toHaveTextContent("Order update: Confirmed. Shop accepted your order.")
    )
  })
})

describe("F1 step semantics and the copy button", () => {
  it("only the current tracker step carries aria-current='step', and it moves with the status", async () => {
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })

    let current = document.querySelectorAll('[aria-current="step"]')
    expect(current).toHaveLength(1)
    expect(current[0]).toHaveTextContent("Received")

    await poll({ ...ORDER, status: "PREPARING" })
    await waitFor(() => {
      current = document.querySelectorAll('[aria-current="step"]')
      expect(current).toHaveLength(1)
      expect(current[0]).toHaveTextContent("Preparing")
    })
  })

  it("the copy button's accessible name is 'Copy order number'", async () => {
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })
    expect(screen.getByRole("button", { name: "Copy order number" })).toBeInTheDocument()
  })

  it("tracker step labels are never clipped to one line (UXT-080: no truncate, no nowrap, no ellipsis)", async () => {
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })
    const steps = document.querySelectorAll("[data-tracker-step]")
    expect(steps).toHaveLength(5)
    for (const step of Array.from(steps)) {
      for (const el of [step, ...Array.from(step.querySelectorAll("*"))]) {
        expect(el.className.toString()).not.toMatch(/\b(truncate|whitespace-nowrap|text-ellipsis|line-clamp-\d)\b/)
      }
    }
  })
})

describe("37-F review F2: READY is described for THIS order's fulfilment type", () => {
  it("a DELIVERY order at READY is announced and shown as ready for delivery, never 'for collection'", async () => {
    trackingAnswer = { ...ORDER, fulfilmentType: "DELIVERY" }
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })

    await poll({ ...ORDER, fulfilmentType: "DELIVERY", status: "READY" })
    await waitFor(() =>
      expect(statusRegion()).toHaveTextContent("Order update: Ready. Ready for delivery.")
    )
    const current = document.querySelector('[aria-current="step"]')
    expect(current).toHaveTextContent("Ready for delivery")
    expect(document.body).not.toHaveTextContent(/ready for collection/i)
  })

  it("an order whose fulfilment type is not recorded gets the neutral description on both", async () => {
    trackingAnswer = { ...ORDER, fulfilmentType: null }
    renderOrderPage()
    await screen.findByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` })

    await poll({ ...ORDER, fulfilmentType: null, status: "READY" })
    await waitFor(() =>
      expect(statusRegion()).toHaveTextContent("Order update: Ready. Your order is ready.")
    )
    expect(document.querySelector('[aria-current="step"]')).toHaveTextContent("Your order is ready")
    expect(document.body).not.toHaveTextContent(/ready for (collection|delivery)/i)
  })
})
