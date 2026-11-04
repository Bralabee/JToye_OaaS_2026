/**
 * #879 / UXT-122: an integrator can send a test event to an endpoint and delete a revoked one.
 *
 *   - the endpoint detail page has "Send test event" for an ACTIVE endpoint: it POSTs /{id}/test, says
 *     the ping is queued and the log will show it, and refreshes the delivery log
 *   - a paused endpoint offers no test (the API would refuse 409; the UI does not invite it)
 *   - a refused ping (typed 409) is shown with the server's own sentence
 *   - the list offers "Delete" on a REVOKED endpoint only, behind a destructive confirmation, and calls
 *     DELETE /{id}; a live endpoint has no Delete
 */
import { render, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import WebhookDetailPage from "../[id]/page"
import WebhooksPage from "../page"
import apiClient from "@/lib/api-client"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/hooks/use-toast", () => {
  const toast = jest.fn()
  return { useToast: () => ({ toast }), __toast: toast }
})

jest.mock("next/navigation", () => ({
  useParams: () => ({ id: "sub-1" }),
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn() }),
  usePathname: () => "/dashboard/webhooks/sub-1",
}))

const toastMock = () => (jest.requireMock("@/hooks/use-toast") as { __toast: jest.Mock }).__toast
const now = new Date().toISOString()

function subscription(status: string, id = "sub-1") {
  return {
    id,
    targetUrl: `https://${id}.example.com/hooks`,
    eventTypes: ["ORDER_STATE_CHANGED"],
    status,
    consecutiveFailures: 0,
    heldCount: 0,
    createdAt: now,
    updatedAt: now,
  }
}

const emptyLog = { data: { content: [], totalPages: 1, totalElements: 0, number: 0, size: 20 } }

function serveDetail(status: string) {
  mockedApiClient.get.mockImplementation((url: string) =>
    url.includes("/deliveries") ? Promise.resolve(emptyLog) : Promise.resolve({ data: subscription(status) }),
  )
}

beforeAll(() => {
  window.HTMLElement.prototype.scrollIntoView = jest.fn()
  window.HTMLElement.prototype.hasPointerCapture = jest.fn()
  window.HTMLElement.prototype.releasePointerCapture = jest.fn()
})

beforeEach(() => {
  jest.clearAllMocks()
})

describe("Send test event (#879)", () => {
  it("POSTs /{id}/test for an ACTIVE endpoint, says the ping is queued, and reloads the delivery log", async () => {
    serveDetail("ACTIVE")
    mockedApiClient.post.mockResolvedValue({
      data: { deliveryId: "d-1", eventId: "e-1", status: "PENDING" },
    })
    render(<WebhookDetailPage />)

    await userEvent.click(await screen.findByRole("button", { name: /send test event/i }))

    await waitFor(() =>
      expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/webhooks/sub-1/test"),
    )
    await waitFor(() =>
      expect(toastMock()).toHaveBeenCalledWith(
        expect.objectContaining({ title: "Test event queued" }),
      ),
    )
    const description = toastMock().mock.calls[0][0].description as string
    expect(description).toMatch(/delivery log/i)
    // The log was fetched again after the ping (initial load + refresh).
    const logFetches = mockedApiClient.get.mock.calls.filter(([url]) => String(url).includes("/deliveries"))
    expect(logFetches.length).toBeGreaterThanOrEqual(2)
  })

  it("offers no test event on a paused endpoint (the API would answer 409)", async () => {
    serveDetail("PAUSED")
    render(<WebhookDetailPage />)

    await screen.findByRole("button", { name: /resume delivery/i })
    expect(screen.queryByRole("button", { name: /send test event/i })).toBeNull()
  })

  it("shows the server's own sentence when the ping is refused", async () => {
    serveDetail("ACTIVE")
    mockedApiClient.post.mockRejectedValue({
      response: {
        status: 409,
        data: {
          type: "https://jtoye.uk/errors/webhook-subscription-state",
          detail: "A test event can only be sent to an active endpoint, and this one is PAUSED. Resume it first.",
        },
      },
    })
    render(<WebhookDetailPage />)

    await userEvent.click(await screen.findByRole("button", { name: /send test event/i }))

    await waitFor(() =>
      expect(toastMock()).toHaveBeenCalledWith(
        expect.objectContaining({
          variant: "destructive",
          description: expect.stringContaining("Resume it first."),
        }),
      ),
    )
  })
})

describe("Delete a revoked endpoint (#879)", () => {
  function serveList() {
    mockedApiClient.get.mockResolvedValue({
      data: [subscription("ACTIVE", "live"), subscription("REVOKED", "dead")],
    })
  }

  it("offers Delete on the revoked endpoint only", async () => {
    serveList()
    render(<WebhooksPage />)
    await screen.findAllByText("https://dead.example.com/hooks")

    const deadCard = screen.getAllByText("https://dead.example.com/hooks")[0].closest("[data-testid]") ?? document.body
    expect(within(document.body).getAllByRole("button", { name: /^delete$/i }).length).toBeGreaterThan(0)
    // Every Delete belongs to the revoked endpoint: the live one's actions never include it.
    const liveCard = screen.getAllByText("https://live.example.com/hooks")[0].closest("div[class*='rounded'], tr") as HTMLElement
    expect(within(liveCard).queryByRole("button", { name: /^delete$/i })).toBeNull()
    expect(deadCard).toBeTruthy()
  })

  it("asks first, then DELETEs /{id} and reloads the list", async () => {
    serveList()
    mockedApiClient.delete.mockResolvedValue({ status: 204 })
    render(<WebhooksPage />)
    await screen.findAllByText("https://dead.example.com/hooks")

    await userEvent.click(screen.getAllByRole("button", { name: /^delete$/i })[0])
    expect(mockedApiClient.delete).not.toHaveBeenCalled()
    expect(await screen.findByText("Delete this endpoint?")).toBeInTheDocument()

    await userEvent.click(screen.getByRole("button", { name: /delete endpoint/i }))

    await waitFor(() =>
      expect(mockedApiClient.delete).toHaveBeenCalledWith("/api/v1/webhooks/dead"),
    )
    await waitFor(() =>
      expect(toastMock()).toHaveBeenCalledWith(expect.objectContaining({ title: "Endpoint deleted" })),
    )
  })

  it("Cancel leaves it alone", async () => {
    serveList()
    render(<WebhooksPage />)
    await screen.findAllByText("https://dead.example.com/hooks")

    await userEvent.click(screen.getAllByRole("button", { name: /^delete$/i })[0])
    await userEvent.click(await screen.findByRole("button", { name: /keep endpoint/i }))

    expect(mockedApiClient.delete).not.toHaveBeenCalled()
  })
})
