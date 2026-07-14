import { render, screen, waitFor, fireEvent, act, within } from "@testing-library/react"

// Config-injected support channel + review SLA (GLOBAL_RULE_6). Set BEFORE the
// component reads them at render time — resolveSupportChannel prefers the URL, so
// the rejection support link resolves to NEXT_PUBLIC_SUPPORT_URL below.
process.env.NEXT_PUBLIC_SUPPORT_EMAIL = "support@jtoye.test"
process.env.NEXT_PUBLIC_SUPPORT_URL = "https://help.jtoye.test/onboarding"
process.env.NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS = "2"

import OnboardingPage from "../page"
import apiClient from "@/lib/api-client"
import type { GateDto, OnboardingDto, OnboardingState, GateStatus, GateType } from "@/types/api"

// Mock the API client (mirrors the products-page test idiom)
jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

// Stable toast spy so we can assert on the destructive channel across renders.
const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

// --- Fixtures ---------------------------------------------------------------

const GATE_TYPES: GateType[] = [
  "BUSINESS_VERIFIED",
  "FOOD_HYGIENE_RATING",
  "ALLERGEN_DATA_COMPLETE",
]

function gates(status: GateStatus) {
  return GATE_TYPES.map((gateType) => ({
    gateType,
    status,
    mandatory: true,
    reason: null,
    checkedAt: null,
  }))
}

// A single gate at a chosen status, the rest PASSED — for isolating one blocker.
function gatesWith(gateType: GateType, status: GateStatus, reason: string | null): GateDto[] {
  return GATE_TYPES.map((gt) => ({
    gateType: gt,
    status: gt === gateType ? status : "PASSED",
    mandatory: true,
    reason: gt === gateType ? reason : null,
    checkedAt: null,
  }))
}

function onboarding(status: OnboardingState, overrides: Partial<OnboardingDto> = {}): OnboardingDto {
  return {
    id: "onb-1",
    status,
    model: "MARKETPLACE",
    shopId: "shop-1",
    companyNumber: null,
    submittedAt: null,
    approvedAt: null,
    wentLiveAt: null,
    rejectionReason: null,
    reviewPending: false,
    gates: gates("PENDING"),
    ...overrides,
  }
}

const notFound = { response: { status: 404 } }
const guardVeto = { response: { status: 400 } }

const shops = [
  { id: "shop-1", name: "Mama's Kitchen" },
  { id: "shop-2", name: "Lagos Grill" },
]

// Route apiClient.get by URL: /onboarding/me vs /shops vs anything else.
function routeGet(meImpl: () => Promise<unknown>, shopList = shops) {
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/onboarding/me")) return meImpl() as Promise<never>
    if (url.startsWith("/api/v1/shops")) {
      return Promise.resolve({ data: { content: shopList } }) as Promise<never>
    }
    return Promise.resolve({ data: {} }) as Promise<never>
  })
}

describe("Onboarding Page", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockToast.mockClear()
  })

  it("renders the empty state + create form when GET /me is 404", async () => {
    routeGet(() => Promise.reject(notFound))

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("Take your shop live")).toBeInTheDocument()
    })
    // Model toggle copy from the LOCKED spec
    expect(screen.getByText("On the J'Toye marketplace")).toBeInTheDocument()
    expect(screen.getByText("On my own storefront")).toBeInTheDocument()
    // Shop select populated from GET /api/v1/shops
    expect(screen.getByRole("option", { name: "Mama's Kitchen" })).toBeInTheDocument()
    expect(screen.getByRole("option", { name: "Lagos Grill" })).toBeInTheDocument()
    // Create CTA
    expect(screen.getByRole("button", { name: /create application/i })).toBeInTheDocument()
  })

  it("creates an application (POST /onboarding) from the create form", async () => {
    routeGet(() => Promise.reject(notFound))
    mockedApiClient.post.mockResolvedValue({ data: onboarding("DRAFT") })

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /create application/i })).toBeInTheDocument()
    })

    fireEvent.change(screen.getByLabelText(/which shop/i), { target: { value: "shop-1" } })
    fireEvent.click(screen.getByRole("button", { name: /create application/i }))

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith(
        "/api/v1/onboarding",
        expect.objectContaining({ model: "MARKETPLACE", shopId: "shop-1" })
      )
    })
  })

  it("renders the DRAFT status view and submits for verification", async () => {
    routeGet(() => Promise.resolve({ data: onboarding("DRAFT") }))
    mockedApiClient.post.mockResolvedValue({ data: onboarding("VERIFYING") })

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("Draft")).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole("button", { name: /submit for verification/i }))

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/onboarding/submit", expect.anything())
    })
  })

  it("re-runs checks from ACTION_REQUIRED via POST /resubmit (not /submit)", async () => {
    // CR-03: the "Re-run checks" CTA must hit the dedicated /resubmit endpoint
    // (RESUBMIT: ACTION_REQUIRED -> VERIFYING), NOT /submit (which the state machine
    // only accepts from DRAFT, so it always 400'd — the old dead-end wiring).
    routeGet(() =>
      Promise.resolve({ data: onboarding("ACTION_REQUIRED", { gates: gates("FAILED") }) })
    )
    mockedApiClient.post.mockResolvedValue({ data: onboarding("VERIFYING") })

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /re-run checks/i })).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole("button", { name: /re-run checks/i }))

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith(
        "/api/v1/onboarding/resubmit",
        expect.anything()
      )
    })
    expect(mockedApiClient.post).not.toHaveBeenCalledWith(
      "/api/v1/onboarding/submit",
      expect.anything()
    )
  })

  it("polls GET /me every 4s while VERIFYING and stops once it leaves", async () => {
    jest.useFakeTimers()
    const me = jest
      .fn()
      .mockResolvedValueOnce({ data: onboarding("VERIFYING") }) // mount
      .mockResolvedValueOnce({ data: onboarding("APPROVED", { gates: gates("PASSED") }) }) // poll -> leaves VERIFYING
    routeGet(() => me())

    render(<OnboardingPage />)

    // Flush the mount fetch
    await act(async () => {
      await Promise.resolve()
    })
    expect(me).toHaveBeenCalledTimes(1)
    expect(screen.getAllByText("Checking…").length).toBeGreaterThan(0)

    // First 4s tick -> a second /me fetch fires
    await act(async () => {
      await jest.advanceTimersByTimeAsync(4000)
    })
    expect(me).toHaveBeenCalledTimes(2)

    // Now APPROVED — polling must stop; no further /me calls
    await act(async () => {
      await jest.advanceTimersByTimeAsync(8000)
    })
    expect(me).toHaveBeenCalledTimes(2)

    jest.useRealTimers()
  })

  it("clears the poll interval on unmount", async () => {
    jest.useFakeTimers()
    const me = jest.fn().mockResolvedValue({ data: onboarding("VERIFYING") })
    routeGet(() => me())

    const { unmount } = render(<OnboardingPage />)
    await act(async () => {
      await Promise.resolve()
    })
    expect(me).toHaveBeenCalledTimes(1)

    unmount()
    await act(async () => {
      await jest.advanceTimersByTimeAsync(12000)
    })
    // No further polling after unmount
    expect(me).toHaveBeenCalledTimes(1)

    jest.useRealTimers()
  })

  it("enables Go live when APPROVED and publishes via the confirm dialog", async () => {
    routeGet(() => Promise.resolve({ data: onboarding("APPROVED", { gates: gates("PASSED") }) }))
    mockedApiClient.post.mockResolvedValue({ data: onboarding("LIVE", { gates: gates("PASSED") }) })

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("Ready to go live")).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole("button", { name: "Go live" }))

    // Confirm dialog appears
    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText("Go live?")).toBeInTheDocument()

    fireEvent.click(within(dialog).getByRole("button", { name: "Go live" }))

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/onboarding/go-live", expect.anything())
    })
  })

  it("surfaces a destructive toast on a go-live 400 guard veto and keeps the gate breakdown visible", async () => {
    routeGet(() => Promise.resolve({ data: onboarding("APPROVED", { gates: gates("PASSED") }) }))
    mockedApiClient.post.mockRejectedValue(guardVeto)

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("Ready to go live")).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole("button", { name: "Go live" }))
    const dialog = await screen.findByRole("dialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Go live" }))

    await waitFor(() => {
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          variant: "destructive",
          title: "Not ready to go live yet",
        })
      )
    })

    // No crash: the gate breakdown remains rendered
    expect(screen.getByText("Allergen data")).toBeInTheDocument()
    expect(screen.getByText("Business verification")).toBeInTheDocument()
  })

  it("renders the LIVE state with no go-live button", async () => {
    routeGet(() => Promise.resolve({ data: onboarding("LIVE", { gates: gates("PASSED") }) }))

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("Live")).toBeInTheDocument()
    })
    expect(screen.queryByRole("button", { name: "Go live" })).not.toBeInTheDocument()
  })

  it("shows a destructive toast when GET /me fails with a server error", async () => {
    routeGet(() => Promise.reject({ response: { status: 500 } }))

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          variant: "destructive",
          title: "Couldn't load your onboarding",
        })
      )
    })
  })

  // --- ONBD-03: honest in-review state + polling back-off --------------------

  it("renders honest in-review copy (config SLA, not 'under a minute') and backs polling off to 30s", async () => {
    jest.useFakeTimers()
    const me = jest.fn().mockResolvedValue({
      data: onboarding("VERIFYING", {
        reviewPending: true,
        gates: gatesWith("FOOD_HYGIENE_RATING", "MANUAL_REVIEW", "Awaiting a reviewer."),
      }),
    })
    routeGet(() => me())

    render(<OnboardingPage />)

    await act(async () => {
      await Promise.resolve()
    })
    expect(me).toHaveBeenCalledTimes(1)

    // Honest, config-driven SLA copy — the dishonest "under a minute" is gone.
    expect(screen.getByText(/within 2 business days/i)).toBeInTheDocument()
    expect(screen.queryByText(/under a minute/i)).not.toBeInTheDocument()
    // The "In review" badge is shown instead of "Running checks".
    expect(screen.getByText("In review")).toBeInTheDocument()

    // Backed off: the old fast 4s tick must NOT fire a poll.
    await act(async () => {
      await jest.advanceTimersByTimeAsync(4000)
    })
    expect(me).toHaveBeenCalledTimes(1)

    // The backed-off 30s poll fires.
    await act(async () => {
      await jest.advanceTimersByTimeAsync(26000)
    })
    expect(me).toHaveBeenCalledTimes(2)

    jest.useRealTimers()
  })

  // --- ONBD-01: withdraw confirm dialog + terminal copy ----------------------

  it("withdraws from a confirm dialog (POST /withdraw) and shows the terminal WITHDRAWN copy", async () => {
    routeGet(() =>
      Promise.resolve({ data: onboarding("PENDING_APPROVAL", { gates: gates("PASSED") }) })
    )
    mockedApiClient.post.mockResolvedValue({
      data: onboarding("WITHDRAWN", { gates: gates("PASSED") }),
    })

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /withdraw application/i })).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole("button", { name: /withdraw application/i }))
    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText("Withdraw your application?")).toBeInTheDocument()

    fireEvent.click(within(dialog).getByRole("button", { name: "Withdraw application" }))

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/onboarding/withdraw", {})
    })
    await waitFor(() => {
      expect(
        screen.getByText(/starting again begins a fresh application/i)
      ).toBeInTheDocument()
    })
  })

  // --- ONBD-02: inline company-number edit -----------------------------------

  it("edits the company number inline (POST /company-number) seeded from the loaded application", async () => {
    routeGet(() =>
      Promise.resolve({
        data: onboarding("ACTION_REQUIRED", {
          companyNumber: "OLD123",
          gates: gatesWith("BUSINESS_VERIFIED", "FAILED", "We couldn't verify your company."),
        }),
      })
    )
    mockedApiClient.post.mockResolvedValue({
      data: onboarding("ACTION_REQUIRED", { companyNumber: "SC654321" }),
    })

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByLabelText("Companies House number")).toBeInTheDocument()
    })
    const input = screen.getByLabelText("Companies House number") as HTMLInputElement
    // Seeded from the loaded application (not blank).
    expect(input.value).toBe("OLD123")

    fireEvent.change(input, { target: { value: "SC654321" } })
    fireEvent.click(screen.getByRole("button", { name: /save company number/i }))

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/onboarding/company-number", {
        companyNumber: "SC654321",
      })
    })
  })

  // --- ONBD-04: per-(gateType,status) remediation block ----------------------

  it("renders a per-gate remediation block: reason preserved + guidance + a deep link", async () => {
    routeGet(() =>
      Promise.resolve({
        data: onboarding("ACTION_REQUIRED", {
          gates: gatesWith(
            "ALLERGEN_DATA_COMPLETE",
            "FAILED",
            "Missing allergen data on SKU-123, SKU-456"
          ),
        }),
      })
    )

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("What needs your attention")).toBeInTheDocument()
    })
    // why: the specific gate reason (names the offending SKUs) is preserved — it
    // renders in both the gate breakdown and the remediation block, so assert >= 1.
    expect(screen.getAllByText(/SKU-123, SKU-456/).length).toBeGreaterThan(0)
    // what + where: allergen failures deep-link to the products screen (D-08).
    const fix = screen.getByRole("link", { name: /fix these products/i })
    expect(fix).toHaveAttribute("href", "/dashboard/products")
  })

  it("renders the FHRS manual-review remediation deep-linking to the shop edit screen", async () => {
    routeGet(() =>
      Promise.resolve({
        data: onboarding("VERIFYING", {
          reviewPending: true,
          gates: gatesWith("FOOD_HYGIENE_RATING", "MANUAL_REVIEW", "No confident FHRS match."),
        }),
      })
    )

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("What needs your attention")).toBeInTheDocument()
    })
    const edit = screen.getByRole("link", { name: /edit shop details/i })
    expect(edit).toHaveAttribute("href", "/dashboard/shops")
  })

  // --- ONBD-05: rejection reason + configured support channel -----------------

  it("renders the rejection reason and a configured support link on REJECTED", async () => {
    routeGet(() =>
      Promise.resolve({
        data: onboarding("REJECTED", {
          rejectionReason: "Hygiene evidence inconsistent with the registered premises",
          gates: gates("PASSED"),
        }),
      })
    )

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText(/hygiene evidence inconsistent/i)).toBeInTheDocument()
    })
    // The support link derives from NEXT_PUBLIC_SUPPORT_URL (config-injected).
    const support = screen.getByRole("link", { name: /contact support/i })
    expect(support).toHaveAttribute("href", "https://help.jtoye.test/onboarding")
  })

  it("renders the recorded reason + support link on SUSPENDED", async () => {
    routeGet(() =>
      Promise.resolve({
        data: onboarding("SUSPENDED", {
          rejectionReason: "Repeated late fulfilment complaints",
          gates: gates("PASSED"),
        }),
      })
    )

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText(/repeated late fulfilment complaints/i)).toBeInTheDocument()
    })
    expect(screen.getByRole("link", { name: /contact support/i })).toBeInTheDocument()
  })
})
