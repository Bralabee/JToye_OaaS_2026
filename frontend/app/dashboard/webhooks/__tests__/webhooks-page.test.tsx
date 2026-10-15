/**
 * Webhook subscriptions list (Surface A, COMMS-06).
 *
 * Verifies the list renders each subscription URL + its icon/label status
 * badge, exposes the Add endpoint CTA, and ships BOTH a responsive card
 * container (below sm) and a table container (sm+) so nothing overflows at
 * 375px (card-stacking vs horizontal-scroll — cards win on mobile).
 */
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import WebhooksPage from "../page"
import apiClient from "@/lib/api-client"
import { WEBHOOKS_ACCESS_REQUIRED_COPY } from "@/lib/webhooks-api"
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
  return { useToast: () => ({ toast }), __toast: toast }
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

  // 37-06: the confirm dialog's cancel says what it keeps (no bare "Cancel"), and
  // the non-destructive Rotate confirm is the accent Button (orange-700, 5.18:1),
  // not the orange-500 literal that measured 2.80:1.
  it("confirms Rotate with 'Keep current secret' and an accent confirm", async () => {
    render(<WebhooksPage />)
    await screen.findAllByText("https://a.example.com/hooks")

    fireEvent.click(screen.getAllByRole("button", { name: /^rotate$/i })[0])
    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByRole("button", { name: "Keep current secret" })).toBeInTheDocument()
    expect(within(dialog).queryByRole("button", { name: /^cancel$/i })).toBeNull()
    expect(within(dialog).getByRole("button", { name: "Rotate secret" })).toHaveClass("bg-primary")
  })

  it("confirms Revoke with 'Keep endpoint' and a destructive confirm", async () => {
    render(<WebhooksPage />)
    await screen.findAllByText("https://a.example.com/hooks")

    fireEvent.click(screen.getAllByRole("button", { name: /^revoke$/i })[0])
    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByRole("button", { name: "Keep endpoint" })).toBeInTheDocument()
    expect(within(dialog).queryByRole("button", { name: /^cancel$/i })).toBeNull()
    expect(within(dialog).getByRole("button", { name: "Revoke endpoint" })).toHaveClass(
      "bg-destructive"
    )
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

/*
 * G-37-12 (37-54, D-08): a refused read is shown as a refusal, and a list that was
 * never read is never shown as an empty one (QA-council F2). Measured in the
 * 2026-10-15 UAT: a Staff user's typed 403 rendered "No webhook endpoints yet" and
 * two "Add endpoint" buttons.
 */
const SHOP_ACCESS_DENIED = {
  response: {
    status: 403,
    data: {
      type: "https://jtoye.uk/errors/shop-access-denied",
      title: "Shop access denied",
      requiredRole: "GROUP_ADMIN",
    },
  },
}

const SERVER_ERROR = { response: { status: 500, data: {} } }

const toastMock = () =>
  (jest.requireMock("@/hooks/use-toast") as { __toast: jest.Mock }).__toast

describe("Webhooks page: refused and unknown reads (G-37-12)", () => {
  it("renders the Group admin refusal, not the empty state, for the typed shop-access-denied 403", async () => {
    mockedApiClient.get.mockRejectedValueOnce(SHOP_ACCESS_DENIED)

    const { container } = render(<WebhooksPage />)
    await screen.findByRole("heading", { level: 1, name: "Webhooks" })

    expect(screen.queryByText("No webhook endpoints yet")).toBeNull()
    expect(
      screen.getByRole("heading", { level: 2, name: "Group admin access required" })
    ).toBeInTheDocument()
    expect(
      screen.getByText(
        "Managing webhook endpoints needs the group-admin role. Ask a group admin in your business for access."
      )
    ).toBeInTheDocument()
    expect(WEBHOOKS_ACCESS_REQUIRED_COPY?.title).toBe("Group admin access required")
    expect(screen.queryAllByRole("button", { name: /add endpoint/i })).toHaveLength(0)
    expect(toastMock()).not.toHaveBeenCalled()
    expect(container.firstElementChild).toHaveAttribute("data-width-tier", "index")
  })

  it("renders the load-error panel with Retry, never the empty state, when the first read fails", async () => {
    mockedApiClient.get.mockRejectedValueOnce(SERVER_ERROR)

    const { container } = render(<WebhooksPage />)
    await screen.findByRole("heading", { level: 1, name: "Webhooks" })

    expect(screen.queryByText("No webhook endpoints yet")).toBeNull()
    const panel = screen.getByTestId("load-error-panel")
    expect(within(panel).getByText("Couldn't load webhook endpoints")).toBeInTheDocument()
    expect(screen.queryAllByRole("button", { name: /add endpoint/i })).toHaveLength(0)
    expect(toastMock()).not.toHaveBeenCalled()
    expect(container.firstElementChild).toHaveAttribute("data-width-tier", "index")

    // Retry reads again; the next answer is the real list.
    fireEvent.click(within(panel).getByRole("button", { name: /try again/i }))
    expect((await screen.findAllByText("https://a.example.com/hooks")).length).toBeGreaterThan(0)
    expect(screen.getAllByText("https://b.example.com/hooks").length).toBeGreaterThan(0)
    expect(screen.queryByTestId("load-error-panel")).toBeNull()
  })

  it("treats a 403 that is not shop-access-denied as a load failure, not as the refusal or the empty state", async () => {
    mockedApiClient.get.mockRejectedValueOnce({
      response: { status: 403, data: { type: "https://jtoye.uk/errors/forbidden" } },
    })

    render(<WebhooksPage />)
    await screen.findByRole("heading", { level: 1, name: "Webhooks" })

    expect(screen.queryByText("No webhook endpoints yet")).toBeNull()
    expect(screen.getByTestId("load-error-panel")).toBeInTheDocument()
    expect(screen.queryByText("Group admin access required")).toBeNull()
  })

  it("keeps the genuine empty state: an empty list says 'No webhook endpoints yet' with Add endpoint", async () => {
    mockedApiClient.get.mockResolvedValueOnce({ data: [] })

    render(<WebhooksPage />)

    expect(await screen.findByText("No webhook endpoints yet")).toBeInTheDocument()
    expect(screen.getAllByRole("button", { name: /add endpoint/i }).length).toBeGreaterThan(0)
    expect(screen.queryByTestId("load-error-panel")).toBeNull()
  })

  it("keeps the list on screen and toasts when a refresh after an action fails", async () => {
    render(<WebhooksPage />)
    await screen.findAllByText("https://a.example.com/hooks")

    mockedApiClient.post.mockResolvedValueOnce({ data: subscriptions[0] })
    mockedApiClient.get.mockRejectedValueOnce(SERVER_ERROR)
    fireEvent.click(screen.getAllByRole("button", { name: /pause/i })[0])

    await waitFor(() =>
      expect(toastMock()).toHaveBeenCalledWith(
        expect.objectContaining({ title: "Error loading webhooks" })
      )
    )
    expect(screen.getAllByText("https://a.example.com/hooks").length).toBeGreaterThan(0)
    expect(screen.getAllByText("https://b.example.com/hooks").length).toBeGreaterThan(0)
    expect(screen.queryByTestId("load-error-panel")).toBeNull()
  })
})
