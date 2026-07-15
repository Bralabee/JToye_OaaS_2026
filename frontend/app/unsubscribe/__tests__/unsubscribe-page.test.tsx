/**
 * Public unsubscribe page (Surface C, COMMS-03 UI half).
 *
 * Covers the Task-1 acceptance criteria:
 *   - renders each of the four token states (loading / success /
 *     already-unsubscribed / invalid) from mocked publicApiClient responses
 *   - the page emits `metadata.robots.index === false` (noindex)
 *   - never prints the raw `token` query value into the rendered DOM (PII /
 *     link-integrity — the token is only ever sent to the API, never rendered)
 *
 * The content component reads `?tenant=&email=&category=&token=` via
 * useSearchParams and POSTs to the no-auth public endpoint via publicApiClient;
 * both are mocked so the four states render deterministically without a live
 * backend. jsdom cannot compute layout, so the mobile-first contract is proven
 * structurally (single-column max-w-lg card) — the 375px no-overflow assertion
 * lives in the Playwright pass (22-07 Task 2).
 */
import { render, screen, waitFor } from "@testing-library/react"
import { UnsubscribeContent } from "../unsubscribe-content"
import { metadata } from "../page"
import publicApiClient from "@/lib/public-api-client"
import { useSearchParams } from "next/navigation"

jest.mock("@/lib/public-api-client")
const mockedPublicApiClient = publicApiClient as jest.Mocked<typeof publicApiClient>

const RAW_TOKEN = "TAMPER3D-t0k3n-must-never-render-in-dom"

// Drive useSearchParams (globally mocked in jest.setup.js) with a real
// URLSearchParams so `.get(key)` returns the token/tenant/email/category.
function setSearchParams(params: Record<string, string>) {
  const sp = new URLSearchParams(params)
  ;(useSearchParams as jest.Mock).mockReturnValue(sp)
}

beforeEach(() => {
  jest.clearAllMocks()
  setSearchParams({
    tenant: "00000000-0000-0000-0000-000000000001",
    email: "recipient@example.com",
    category: "MARKETING",
    token: RAW_TOKEN,
  })
})

describe("Public unsubscribe page (Surface C)", () => {
  it("is noindex,nofollow in its page metadata (SEO/privacy contract)", () => {
    const robots = metadata.robots as { index?: boolean; follow?: boolean }
    expect(robots.index).toBe(false)
    expect(robots.follow).toBe(false)
  })

  it("renders the loading state before the API resolves", () => {
    // Never-resolving promise keeps the component in its loading state.
    mockedPublicApiClient.post.mockReturnValue(new Promise(() => {}))
    render(<UnsubscribeContent />)
    expect(screen.getByText(/updating your preferences/i)).toBeInTheDocument()
  })

  it("renders the success state for a valid token (status=unsubscribed)", async () => {
    mockedPublicApiClient.post.mockResolvedValue({ data: { status: "unsubscribed" } })
    render(<UnsubscribeContent />)
    const heading = await screen.findByRole("heading", { name: /you're unsubscribed/i })
    expect(heading).toBeInTheDocument()
    // Exactly one h1 (accessibility contract).
    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1)
  })

  it("renders the already-unsubscribed state (status=already_unsubscribed)", async () => {
    mockedPublicApiClient.post.mockResolvedValue({
      data: { status: "already_unsubscribed" },
    })
    render(<UnsubscribeContent />)
    expect(
      await screen.findByRole("heading", { name: /you're already unsubscribed/i })
    ).toBeInTheDocument()
  })

  it("renders the invalid state for a tampered token (status=invalid)", async () => {
    mockedPublicApiClient.post.mockResolvedValue({ data: { status: "invalid" } })
    render(<UnsubscribeContent />)
    expect(
      await screen.findByRole("heading", { name: /this link isn't valid/i })
    ).toBeInTheDocument()
  })

  it("falls back to the invalid state when the API call fails", async () => {
    mockedPublicApiClient.post.mockRejectedValue(new Error("network"))
    render(<UnsubscribeContent />)
    expect(
      await screen.findByRole("heading", { name: /this link isn't valid/i })
    ).toBeInTheDocument()
  })

  it("never renders the raw token (or email) into the visible DOM (PII-safe)", async () => {
    mockedPublicApiClient.post.mockResolvedValue({ data: { status: "unsubscribed" } })
    const { container } = render(<UnsubscribeContent />)
    await screen.findByRole("heading", { name: /you're unsubscribed/i })
    // The token and email are sent to the API, but must never be printed.
    expect(container.textContent).not.toContain(RAW_TOKEN)
    expect(container.textContent).not.toContain("recipient@example.com")
    expect(container.innerHTML).not.toContain(RAW_TOKEN)
  })

  it("POSTs the token to the public endpoint (never the authed apiClient)", async () => {
    mockedPublicApiClient.post.mockResolvedValue({ data: { status: "unsubscribed" } })
    render(<UnsubscribeContent />)
    await waitFor(() => expect(mockedPublicApiClient.post).toHaveBeenCalled())
    const call = mockedPublicApiClient.post.mock.calls[0]
    expect(String(call[0])).toContain("/public/unsubscribe")
    const config = call[2] as { params?: Record<string, string> } | undefined
    expect(config?.params?.token).toBe(RAW_TOKEN)
    expect(config?.params?.category).toBe("MARKETING")
  })
})
