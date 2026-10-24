/**
 * #961 review F1: the onboarding lifecycle needs SHOP_MANAGER on the onboarded shop (the API answers STAFF
 * 403 shop-access-denied), so the page must not offer Submit / Re-run / Go live / Withdraw / the company
 * number edit to a STAFF member, and a 403 that does arrive must show the server's own detail.
 * CONTROL arms (a SHOP_MANAGER on the same fixture) show the buttons render when the role allows.
 */
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import OnboardingPage from "../page"
import apiClient from "@/lib/api-client"
import type { MyAccess } from "@/lib/shops-api"
import type { OnboardingDto, OnboardingState } from "@/types/api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

let mockSwitcher: { loading: boolean; access: MyAccess | null } | null = null
jest.mock("@/components/dashboard/shop-switcher-provider", () => ({
  useOptionalShopSwitcherData: () => mockSwitcher,
}))

function onboarding(status: OnboardingState, shopId: string | null = "shop-1"): OnboardingDto {
  return {
    id: "onb-1",
    status,
    model: "MARKETPLACE",
    shopId,
    companyNumber: null,
    submittedAt: null,
    approvedAt: null,
    wentLiveAt: null,
    rejectionReason: null,
    reviewPending: false,
    gates: [],
  } as OnboardingDto
}

function access(role: "STAFF" | "SHOP_MANAGER", over: Partial<MyAccess> = {}): MyAccess {
  return {
    userId: "u1",
    groupAdmin: false,
    grantedShopIds: ["shop-1"],
    tenantWideRole: null,
    businessName: null,
    shopRoles: { "shop-1": role },
    ...over,
  }
}

function load(o: OnboardingDto) {
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url.startsWith("/api/v1/onboarding/me")) return Promise.resolve({ data: o })
    if (url.startsWith("/api/v1/shops")) return Promise.resolve({ data: { content: [] } })
    return Promise.resolve({ data: {} })
  }) as jest.Mock)
}

describe("onboarding page: lifecycle controls follow the caller's role on the onboarded shop (#961 F1)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockSwitcher = null
  })

  const cases: [string, OnboardingState, RegExp][] = [
    ["Submit for verification", "DRAFT", /submit for verification/i],
    ["Re-run checks", "ACTION_REQUIRED", /re-run checks/i],
    ["Go live", "APPROVED", /^go live$/i],
  ]

  it.each(cases)("CONTROL: a SHOP_MANAGER of the shop is offered %s", async (_label, status, name) => {
    mockSwitcher = { loading: false, access: access("SHOP_MANAGER") }
    load(onboarding(status))
    render(<OnboardingPage />)
    expect(await screen.findByRole("button", { name })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: /withdraw application/i })).toBeInTheDocument()
  })

  it.each(cases)("a STAFF member is NOT offered %s, nor Withdraw", async (_label, status, name) => {
    mockSwitcher = { loading: false, access: access("STAFF") }
    load(onboarding(status))
    render(<OnboardingPage />)
    // Instrument: the status view rendered (the status badge is on screen) before asserting absence.
    await waitFor(() => expect(mockedApiClient.get).toHaveBeenCalledWith(expect.stringContaining("/onboarding/me")))
    await screen.findByRole("heading", { level: 1 })
    expect(screen.queryByRole("button", { name })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /withdraw application/i })).not.toBeInTheDocument()
  })

  it("a SHOP_MANAGER of a DIFFERENT shop is not offered the controls", async () => {
    mockSwitcher = { loading: false, access: access("SHOP_MANAGER", { shopRoles: { "shop-2": "SHOP_MANAGER" } }) }
    load(onboarding("DRAFT"))
    render(<OnboardingPage />)
    await screen.findByRole("heading", { level: 1 })
    expect(screen.queryByRole("button", { name: /submit for verification/i })).not.toBeInTheDocument()
  })

  it("an onboarding with no shop is Group-admin only", async () => {
    mockSwitcher = { loading: false, access: access("SHOP_MANAGER") }
    load(onboarding("DRAFT", null))
    render(<OnboardingPage />)
    await screen.findByRole("heading", { level: 1 })
    expect(screen.queryByRole("button", { name: /submit for verification/i })).not.toBeInTheDocument()
  })

  it("shows the server's problem detail, not a generic line, when a withdraw is refused 403", async () => {
    mockSwitcher = null // no provider: the controls are offered and the server is the boundary
    load(onboarding("DRAFT"))
    mockedApiClient.post.mockRejectedValue({
      response: { status: 403, data: { detail: "You need the SHOP_MANAGER role on this shop to do that." } },
    })
    render(<OnboardingPage />)
    fireEvent.click(await screen.findByRole("button", { name: /withdraw application/i }))
    const dialog = await screen.findByRole("dialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Withdraw application" }))
    await waitFor(() =>
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          variant: "destructive",
          description: "You need the SHOP_MANAGER role on this shop to do that.",
        })
      )
    )
  })

  it("shows the server's problem detail when a submit is refused 403", async () => {
    mockSwitcher = null
    load(onboarding("DRAFT"))
    mockedApiClient.post.mockRejectedValue({
      response: { status: 403, data: { detail: "Ask a shop manager to submit this." } },
    })
    render(<OnboardingPage />)
    fireEvent.click(await screen.findByRole("button", { name: /submit for verification/i }))
    await waitFor(() =>
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({ description: "Ask a shop manager to submit this." })
      )
    )
  })
})
