/**
 * Webhook subscriptions list (Surface A, COMMS-06).
 *
 * Verifies the list renders each subscription URL + its icon/label status
 * badge, exposes the Add endpoint CTA, and ships BOTH a responsive card
 * container (below sm) and a table container (sm+) so nothing overflows at
 * 375px (card-stacking vs horizontal-scroll — cards win on mobile).
 */
import { render, screen } from "@testing-library/react"
import WebhooksPage from "../page"
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

jest.mock("@/hooks/use-toast", () => {
  const toast = jest.fn()
  return { useToast: () => ({ toast }) }
})

const now = new Date().toISOString()

const subscriptions = [
  {
    id: "sub-1",
    targetUrl: "https://a.example.com/hooks",
    eventTypes: ["ORDER_STATE_CHANGED", "ORDER_REFUNDED"],
    status: "ACTIVE",
    consecutiveFailures: 0,
    createdAt: now,
    updatedAt: now,
  },
  {
    id: "sub-2",
    targetUrl: "https://b.example.com/hooks",
    eventTypes: ["PAYMENT_EVENT"],
    status: "AUTO_PAUSED",
    consecutiveFailures: 10,
    createdAt: now,
    updatedAt: now,
  },
]

beforeEach(() => {
  jest.clearAllMocks()
  mockedApiClient.get.mockResolvedValue({ data: subscriptions })
})

describe("Webhook subscriptions list (Surface A)", () => {
  it("renders each subscription URL and its status badge", async () => {
    render(<WebhooksPage />)
    // URL appears in both the table row and the mobile card (responsive)
    expect(
      (await screen.findAllByText("https://a.example.com/hooks")).length
    ).toBeGreaterThan(0)
    expect(screen.getAllByText("https://b.example.com/hooks").length).toBeGreaterThan(0)
    // status badges carry a text label (never colour alone)
    expect(screen.getAllByText("Active").length).toBeGreaterThan(0)
    expect(screen.getAllByText("Auto-paused").length).toBeGreaterThan(0)
  })

  it("exposes the Add endpoint CTA", async () => {
    render(<WebhooksPage />)
    await screen.findAllByText("https://a.example.com/hooks")
    expect(
      screen.getAllByRole("button", { name: /add endpoint/i }).length
    ).toBeGreaterThan(0)
  })

  it("ships a card container (below sm) and a table container (sm+) for 375px no-overflow", async () => {
    const { container } = render(<WebhooksPage />)
    await screen.findAllByText("https://a.example.com/hooks")
    const cards = container.querySelector('[data-testid="webhooks-cards"]')
    const table = container.querySelector('[data-testid="webhooks-table"]')
    expect(cards).toHaveClass("sm:hidden")
    expect(table).toHaveClass("hidden")
  })

  // --- Phase 35 / UIX-08: the width tier, declared rather than inherited ---

  it("declares the index width tier, with no cap of its own, on the list's root band", async () => {
    const { container } = render(<WebhooksPage />)
    await screen.findAllByText("https://a.example.com/hooks")

    const root = container.firstElementChild as HTMLElement
    expect(root).toHaveAttribute("data-width-tier", "index")
    expect(capTokens(root)).toEqual([])

    // Non-vacuity control: the same filter over a real cap from the vocabulary
    // must find it, so the empty result above is about the page.
    const probe = document.createElement("div")
    probe.className = `mx-auto ${WIDTH_TIER_CLASS.detail}`
    expect(capTokens(probe)).toEqual([WIDTH_TIER_CLASS.detail])
  })

  it("leaves the responsive card/table split untouched while declaring the tier", async () => {
    // The displaced-goods control. The tier is an attribute on the root; the
    // breakpoint visibility rules live on descendants and must be byte-identical,
    // because a widened band changes WHEN the table overflows and this split is
    // what keeps 375px card-stacked (COMMS-06).
    const { container } = render(<WebhooksPage />)
    await screen.findAllByText("https://a.example.com/hooks")

    expect(container.querySelector('[data-testid="webhooks-cards"]')).toHaveClass("sm:hidden")
    expect(container.querySelector('[data-testid="webhooks-table"]')).toHaveClass("hidden")
    expect(container.querySelector('[data-testid="webhooks-table"]')).toHaveClass("sm:block")
  })

  it("declares the same tier on the spinner branch, so the first paint is not undeclared", () => {
    mockedApiClient.get.mockImplementation((() => new Promise(() => {})) as never)

    const { container } = render(<WebhooksPage />)

    expect(container.querySelector(".animate-spin")).not.toBeNull()
    expect(container.firstElementChild).toHaveAttribute("data-width-tier", "index")
  })
})
