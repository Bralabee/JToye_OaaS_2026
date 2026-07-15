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

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

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
})
