/**
 * UXT-038 (37-43, UI-SPEC E2): held deliveries and an honest auto-pause banner.
 *
 *   - a HELD delivery renders the outline "Held" badge (PauseCircle) and can be filtered
 *   - the auto-pause banner says how many events are held, from the API's heldCount:
 *     "3 events are held …", "1 event is held …", and NO held sentence at 0 (37-43 assumption:
 *     never "0 events are held")
 *   - its primary button reads "Resume and deliver held events" and resumes the endpoint
 *   - held, failed and delivered rows coexist, each with its own badge (E2 partial)
 *
 * The goods it keeps (P2-RAV-21): the delivery log, the last-error line and replay are untouched;
 * delivery-log.test.tsx still covers them.
 */
import { render, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import WebhookDetailPage from "../[id]/page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => {
  const toast = jest.fn()
  return { useToast: () => ({ toast }) }
})

jest.mock("next/navigation", () => ({
  useParams: () => ({ id: "sub-1" }),
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn() }),
  usePathname: () => "/dashboard/webhooks/sub-1",
}))

const now = new Date().toISOString()

function subscription(status: string, heldCount: number) {
  return {
    id: "sub-1",
    targetUrl: "https://a.example.com/hooks",
    eventTypes: ["ORDER_STATE_CHANGED"],
    status,
    consecutiveFailures: status === "AUTO_PAUSED" ? 3 : 0,
    heldCount,
    createdAt: now,
    updatedAt: now,
  }
}

function row(id: string, status: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    subscriptionId: "sub-1",
    eventId: `e-${id}`,
    eventType: "order.ready",
    status,
    attemptCount: status === "FAILED" ? 8 : 1,
    lastHttpStatus: status === "DELIVERED" ? 200 : 503,
    lastError: status === "DELIVERED" ? null : "HTTP 503",
    replay: false,
    replayOf: null,
    nextAttemptAt: now,
    createdAt: now,
    updatedAt: now,
    ...extra,
  }
}

const heldRow = row("h1", "HELD")
const failedRow = row("f1", "FAILED")
const deliveredRow = row("d1", "DELIVERED")

function page(rows: unknown[]) {
  return { data: { content: rows, totalPages: 1, totalElements: rows.length, number: 0, size: 20 } }
}

/**
 * Serves the subscription and its delivery log. The banner arms serve a log WITHOUT a held row
 * (`withHeldRow` false), so each one is judged on the banner alone, not on the badge map.
 */
function serve(sub: ReturnType<typeof subscription>, withHeldRow = true) {
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.includes("/deliveries")) {
      if (url.includes("status=HELD")) return Promise.resolve(page([heldRow]))
      return Promise.resolve(page(withHeldRow ? [heldRow, failedRow, deliveredRow] : [failedRow, deliveredRow]))
    }
    return Promise.resolve({ data: sub })
  })
}

beforeAll(() => {
  window.HTMLElement.prototype.scrollIntoView = jest.fn()
  window.HTMLElement.prototype.hasPointerCapture = jest.fn()
  window.HTMLElement.prototype.releasePointerCapture = jest.fn()
})

beforeEach(() => {
  jest.clearAllMocks()
})

/** The auto-pause banner region (role=status), found by its heading. */
async function banner() {
  const heading = await screen.findByText("Deliveries to this endpoint are paused")
  const region = heading.closest('[role="status"]')
  expect(region).not.toBeNull()
  return region as HTMLElement
}

describe("Webhook held deliveries and the auto-pause banner (UXT-038, UI-SPEC E2)", () => {
  it("renders a HELD delivery as an outline 'Held' badge with the PauseCircle icon", async () => {
    serve(subscription("AUTO_PAUSED", 1))
    render(<WebhookDetailPage />)

    const held = await screen.findAllByText("Held")
    // table + mobile card both render the row
    expect(held.length).toBeGreaterThan(0)
    for (const label of held) {
      const badge = label.closest('[data-delivery-status="HELD"]') as HTMLElement
      expect(badge).not.toBeNull()
      expect(badge).toHaveAttribute("data-badge-variant", "outline")
      expect(badge).not.toHaveClass("border-transparent")
      expect(badge.querySelector("svg.lucide-circle-pause")).not.toBeNull()
    }
  })

  it("shows held, failed and delivered rows side by side, each with its own badge", async () => {
    serve(subscription("AUTO_PAUSED", 1))
    render(<WebhookDetailPage />)

    expect((await screen.findAllByText("Held")).length).toBeGreaterThan(0)
    expect(screen.getAllByText("Failed").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Delivered").length).toBeGreaterThan(0)
  })

  it("offers Held in the status filter and asks the server for status=HELD", async () => {
    serve(subscription("AUTO_PAUSED", 1))
    const user = userEvent.setup()
    render(<WebhookDetailPage />)
    await screen.findAllByText("Delivered")

    await user.click(screen.getByRole("combobox", { name: /status/i }))
    await user.click(await screen.findByRole("option", { name: "Held" }))

    await waitFor(() =>
      expect(mockedApiClient.get.mock.calls.some((c) => String(c[0]).includes("status=HELD"))).toBe(true)
    )
    await waitFor(() => expect(screen.queryByText("Delivered")).not.toBeInTheDocument())
  })

  it("AUTO_PAUSED with 3 held: says '3 events are held' and offers 'Resume and deliver held events'", async () => {
    serve(subscription("AUTO_PAUSED", 3), false)
    render(<WebhookDetailPage />)

    const region = await banner()
    expect(region).toHaveTextContent("We paused it after deliveries kept failing.")
    expect(region).toHaveTextContent("3 events are held and will be delivered when you resume.")
    expect(region).toHaveTextContent("Fix the endpoint first, then:")
    expect(within(region).getByRole("button", { name: "Resume and deliver held events" })).toBeInTheDocument()
  })

  it("AUTO_PAUSED with 1 held: the sentence is singular", async () => {
    serve(subscription("AUTO_PAUSED", 1), false)
    render(<WebhookDetailPage />)

    const region = await banner()
    expect(region).toHaveTextContent("1 event is held and will be delivered when you resume.")
    expect(region.textContent).not.toMatch(/1 events/)
  })

  it("AUTO_PAUSED with 0 held: the held-events sentence is omitted, never '0 events are held'", async () => {
    serve(subscription("AUTO_PAUSED", 0), false)
    render(<WebhookDetailPage />)

    const region = await banner()
    expect(region).toHaveTextContent("We paused it after deliveries kept failing.")
    expect(region.textContent).not.toMatch(/held and will be delivered/)
    expect(region.textContent).not.toMatch(/0 events/)
    expect(within(region).getByRole("button", { name: "Resume and deliver held events" })).toBeInTheDocument()
  })

  it("the banner's primary button resumes the endpoint", async () => {
    serve(subscription("AUTO_PAUSED", 2), false)
    mockedApiClient.post.mockResolvedValue({ data: subscription("ACTIVE", 0) })
    const user = userEvent.setup()
    render(<WebhookDetailPage />)

    const region = await banner()
    await user.click(within(region).getByRole("button", { name: "Resume and deliver held events" }))

    await waitFor(() =>
      expect(mockedApiClient.post.mock.calls.some((c) => String(c[0]).endsWith("/sub-1/resume"))).toBe(true)
    )
  })
})
