/**
 * Webhook endpoint detail + delivery-log browser (Surface B, COMMS-06).
 *
 * Covers the Task-3 acceptance criteria:
 *   - renders the subscription + a delivery row
 *   - filtering by status=FAILED narrows the visible rows (server-side re-fetch)
 *   - card-stacking below sm + table at sm+ (375px no-horizontal-overflow)
 *   - Replay issues a POST carrying an Idempotency-Key header
 *
 * jsdom cannot compute layout, so the 375px contract is asserted structurally
 * (responsive card/table split + no element declares a width beyond 375px).
 */
import { render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { webcrypto } from "node:crypto"
import WebhookDetailPage from "../[id]/page"
import apiClient from "@/lib/api-client"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"

/**
 * Every width-cap utility an element declares, as tokens. A token filter, never a
 * substring search — `classList` membership is what a browser resolves.
 */
const capTokens = (el: Element) =>
  Array.from(el.classList).filter((c) => c.startsWith("max-w-"))

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

// Stable toast identity across renders — mirrors the real module-level `toast`
// (an unstable one would spin the detail page's useCallback([id, toast]) deps).
jest.mock("@/hooks/use-toast", () => {
  const toast = jest.fn()
  return { useToast: () => ({ toast }) }
})

// Override the global next/navigation mock to supply useParams for [id].
jest.mock("next/navigation", () => ({
  useParams: () => ({ id: "sub-1" }),
  useRouter: () => ({
    push: jest.fn(),
    replace: jest.fn(),
    back: jest.fn(),
  }),
  usePathname: () => "/dashboard/webhooks/sub-1",
}))

const now = new Date().toISOString()

const subscription = {
  id: "sub-1",
  targetUrl: "https://a.example.com/hooks",
  eventTypes: ["ORDER_STATE_CHANGED"],
  status: "ACTIVE",
  consecutiveFailures: 0,
  createdAt: now,
  updatedAt: now,
}

const deliveredRow = {
  id: "d1",
  subscriptionId: "sub-1",
  eventId: "e1",
  eventType: "order.state.CONFIRMED",
  status: "DELIVERED",
  attemptCount: 1,
  lastHttpStatus: 200,
  lastError: null,
  replay: false,
  replayOf: null,
  nextAttemptAt: now,
  createdAt: now,
  updatedAt: now,
}

const failedRow = {
  id: "d2",
  subscriptionId: "sub-1",
  eventId: "e2",
  eventType: "order.state.CANCELLED",
  status: "FAILED",
  attemptCount: 8,
  lastHttpStatus: 500,
  lastError: "Connection timed out",
  replay: false,
  replayOf: null,
  nextAttemptAt: now,
  createdAt: now,
  updatedAt: now,
}

function deliveriesPage(rows: unknown[]) {
  return {
    data: {
      content: rows,
      totalPages: 1,
      totalElements: rows.length,
      number: 0,
      size: 20,
    },
  }
}

beforeAll(() => {
  // Radix Select needs these jsdom shims to open its listbox.
  window.HTMLElement.prototype.scrollIntoView = jest.fn()
  window.HTMLElement.prototype.hasPointerCapture = jest.fn()
  window.HTMLElement.prototype.releasePointerCapture = jest.fn()
  // Secure random for makeIdempotencyKey (replay).
  if (!globalThis.crypto || typeof globalThis.crypto.randomUUID !== "function") {
    Object.defineProperty(globalThis, "crypto", {
      value: webcrypto,
      configurable: true,
    })
  }
})

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.includes("/deliveries")) {
      const failedOnly = url.includes("status=FAILED")
      return Promise.resolve(
        deliveriesPage(failedOnly ? [failedRow] : [deliveredRow, failedRow])
      )
    }
    // subscription get
    return Promise.resolve({ data: subscription })
  })
})

describe("Webhook delivery-log browser (Surface B)", () => {
  it("renders the subscription and a delivery row", async () => {
    render(<WebhookDetailPage />)
    // subscription URL is shown in the summary card
    expect(
      (await screen.findAllByText("https://a.example.com/hooks")).length
    ).toBeGreaterThan(0)
    // a delivered row is present (badge label, table + card render)
    expect(screen.getAllByText("Delivered").length).toBeGreaterThan(0)
  })

  it("narrows the visible rows when filtering by status=Failed", async () => {
    const user = userEvent.setup()
    render(<WebhookDetailPage />)
    // both a delivered and a failed row initially
    expect((await screen.findAllByText("Delivered")).length).toBeGreaterThan(0)

    const statusTrigger = screen.getByRole("combobox", { name: /status/i })
    await user.click(statusTrigger)
    const failedOption = await screen.findByRole("option", { name: "Failed" })
    await user.click(failedOption)

    // after the re-fetch (status=FAILED) the delivered rows are gone
    await waitFor(() =>
      expect(screen.queryByText("Delivered")).not.toBeInTheDocument()
    )
    expect(screen.getAllByText("Failed").length).toBeGreaterThan(0)
  })

  it("card-stacks below sm and tables at sm+ (375px no-overflow contract)", async () => {
    Object.defineProperty(window, "innerWidth", {
      writable: true,
      configurable: true,
      value: 375,
    })
    const { container } = render(<WebhookDetailPage />)
    await screen.findAllByText("Delivered")

    const cards = container.querySelector('[data-testid="deliveries-cards"]')
    const table = container.querySelector('[data-testid="deliveries-table"]')
    expect(cards).toHaveClass("sm:hidden")
    expect(table).toHaveClass("hidden")

    // No element declares an inline width beyond the 375px viewport.
    const tooWide = Array.from(
      container.querySelectorAll<HTMLElement>("*")
    ).filter((el) => parseInt(el.style.width || "0", 10) > 375)
    expect(tooWide).toHaveLength(0)
  })

  it("replay issues a POST carrying an Idempotency-Key header", async () => {
    mockedApiClient.post.mockResolvedValue({
      data: { ...failedRow, id: "d3", replay: true, replayOf: "d2" },
    })
    const user = userEvent.setup()
    render(<WebhookDetailPage />)
    await screen.findAllByText("Delivered")

    const replayButtons = screen.getAllByRole("button", { name: /^replay$/i })
    await user.click(replayButtons[0])

    const confirmBtn = await screen.findByRole("button", {
      name: /replay delivery/i,
    })
    await user.click(confirmBtn)

    await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalled())
    const replayCall = mockedApiClient.post.mock.calls.find((c) =>
      String(c[0]).includes("/replay")
    )
    expect(replayCall).toBeTruthy()
    const config = replayCall?.[2] as
      | { headers?: Record<string, string> }
      | undefined
    expect(config?.headers?.["Idempotency-Key"]).toBeTruthy()
  })

  // --- Phase 35 / UIX-08: the width tier, and the exception it records ---
  //
  // PATTERNS A-3 — the one case in the phase where ROUTE shape and CONTENT shape
  // disagree. `/dashboard/webhooks/[id]` is a bracketed detail route whose body
  // is a wide, timestamp-heavy delivery table with its own horizontal scroll
  // region, so it takes the Index tier and NOT the reading tier its sibling
  // bracketed routes take.
  //
  // This is the tier value in the phase that would survive longest if it were
  // wrong: both tiers render plausibly here, and the mistake reads as a
  // consistency fix. Its fail direction is armed and recorded in plan 35-04's
  // Task 3 for exactly that reason.

  it("declares the index width tier, with no cap of its own, on the delivery log's root band", async () => {
    const { container } = render(<WebhookDetailPage />)
    await screen.findAllByText("Delivered")

    const root = container.firstElementChild as HTMLElement
    expect(root).toHaveAttribute("data-width-tier", "index")
    expect(capTokens(root)).toEqual([])

    // Non-vacuity control: the same filter over a real cap from the vocabulary
    // must find it, so the empty result above is about the page.
    const probe = document.createElement("div")
    probe.className = `mx-auto ${WIDTH_TIER_CLASS.detail}`
    expect(capTokens(probe)).toEqual([WIDTH_TIER_CLASS.detail])
  })

  it("takes the index tier and NOT the reading tier its route shape would imply", async () => {
    // Stated as its own case rather than folded into the one above, because
    // "detail" is the value a reader correcting this page would reach for. If
    // this ever reds, read the exception written at the site before changing it.
    const { container } = render(<WebhookDetailPage />)
    await screen.findAllByText("Delivered")

    const root = container.firstElementChild as HTMLElement
    expect(root.getAttribute("data-width-tier")).not.toBe("detail")
    expect(root.className).not.toContain(WIDTH_TIER_CLASS.detail)
  })

  it("declares the same tier on the spinner and error branches, so no paint is undeclared", async () => {
    // Spinner: hold the subscription fetch open.
    mockedApiClient.get.mockImplementation((() => new Promise(() => {})) as never)
    const loadingView = render(<WebhookDetailPage />)
    expect(loadingView.container.querySelector(".animate-spin")).not.toBeNull()
    expect(loadingView.container.firstElementChild).toHaveAttribute(
      "data-width-tier",
      "index"
    )
    loadingView.unmount()

    // Error: the subscription fetch rejects, so the "couldn't load" card renders.
    mockedApiClient.get.mockImplementation((() =>
      Promise.reject(new Error("boom"))) as never)
    const errorView = render(<WebhookDetailPage />)
    await screen.findByText(/couldn.t load this endpoint/i)
    expect(errorView.container.firstElementChild).toHaveAttribute(
      "data-width-tier",
      "index"
    )
  })
})
