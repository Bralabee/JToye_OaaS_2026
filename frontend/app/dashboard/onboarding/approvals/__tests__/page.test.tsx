import { render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import OnboardingApprovalsPage from "../page"
import apiClient from "@/lib/api-client"
import type { AdminOnboardingDto, GateDto, GateStatus } from "@/types/api"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"

/**
 * Every width-cap utility an element declares, as tokens. A token filter, never a
 * substring search — `classList` membership is what a browser resolves.
 */
const capTokens = (el: Element) =>
  Array.from(el.classList).filter((c) => c.startsWith("max-w-"))

// Mock the API client (mirrors the onboarding-page test idiom)
jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

// --- Fixtures ---------------------------------------------------------------

function gates(status: GateStatus) {
  return (["BUSINESS_VERIFIED", "FOOD_HYGIENE_RATING", "ALLERGEN_DATA_COMPLETE"] as const).map(
    (gateType) => ({
      gateType,
      status,
      mandatory: true,
      reason: null,
      checkedAt: null,
    })
  )
}

function application(overrides: Partial<AdminOnboardingDto> = {}): AdminOnboardingDto {
  return {
    id: "onb-1",
    status: "PENDING_APPROVAL",
    model: "MARKETPLACE",
    shopId: "shop-1",
    shopName: "Mama's Kitchen",
    companyNumber: "SC123456",
    submittedAt: new Date().toISOString(),
    approvedAt: null,
    rejectionReason: null,
    gates: gates("PASSED"),
    ...overrides,
  }
}

// A VERIFYING application parked on a MANUAL_REVIEW gate — the review-queue shape.
const reviewGates: GateDto[] = [
  {
    gateType: "FOOD_HYGIENE_RATING",
    status: "MANUAL_REVIEW",
    mandatory: true,
    reason: "No confident FHRS match",
    checkedAt: null,
  },
  { gateType: "BUSINESS_VERIFIED", status: "PASSED", mandatory: true, reason: null, checkedAt: null },
  {
    gateType: "ALLERGEN_DATA_COMPLETE",
    status: "PASSED",
    mandatory: true,
    reason: null,
    checkedAt: null,
  },
]

function reviewApplication(overrides: Partial<AdminOnboardingDto> = {}): AdminOnboardingDto {
  return application({
    id: "rev-1",
    status: "VERIFYING",
    shopName: "Lagos Grill",
    gates: reviewGates,
    ...overrides,
  })
}

const forbidden = { response: { status: 403 } }
const guardVeto = { response: { status: 400 } }

// Routes only the /pending queue; /reviews (and anything else) resolves empty.
function routePending(impl: () => Promise<unknown>) {
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/onboarding/admin/pending")) return impl() as Promise<never>
    return Promise.resolve({ data: [] }) as Promise<never>
  })
}

// Routes both admin queues independently.
function routeQueues(
  pendingImpl: () => Promise<unknown>,
  reviewsImpl: () => Promise<unknown>
) {
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/onboarding/admin/pending")) return pendingImpl() as Promise<never>
    if (url.startsWith("/api/v1/onboarding/admin/reviews")) return reviewsImpl() as Promise<never>
    return Promise.resolve({ data: [] }) as Promise<never>
  })
}

describe("Onboarding Approvals Page", () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockToast.mockClear()
  })

  it("lists pending applications with shop name, model, gate summary and submitted date", async () => {
    routePending(() => Promise.resolve({ data: [application()] }))

    render(<OnboardingApprovalsPage />)

    await waitFor(() => {
      expect(screen.getByText("Mama's Kitchen")).toBeInTheDocument()
    })
    expect(screen.getByText("Marketplace")).toBeInTheDocument()
    expect(screen.getByText(/3\/3 required checks green/)).toBeInTheDocument()
    expect(screen.getByText(/Submitted/)).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Reject" })).toBeInTheDocument()
  })

  it("renders the empty state when the queue is empty", async () => {
    routePending(() => Promise.resolve({ data: [] }))

    render(<OnboardingApprovalsPage />)

    await waitFor(() => {
      expect(screen.getByText("No applications waiting")).toBeInTheDocument()
    })
  })

  it("renders the admin-access-required state on 403 (non-admin)", async () => {
    routePending(() => Promise.reject(forbidden))

    render(<OnboardingApprovalsPage />)

    await waitFor(() => {
      expect(screen.getByText("Admin access required")).toBeInTheDocument()
    })
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument()
  })

  it("approves via the confirm dialog (POST /approve) and removes the row", async () => {
    routePending(() => Promise.resolve({ data: [application()] }))
    mockedApiClient.post.mockResolvedValue({ data: application({ status: "APPROVED" }) })

    render(<OnboardingApprovalsPage />)
    await waitFor(() => expect(screen.getByText("Mama's Kitchen")).toBeInTheDocument())

    fireEvent.click(screen.getByRole("button", { name: "Approve" }))
    const dialog = await screen.findByRole("dialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Approve" }))

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith(
        "/api/v1/onboarding/admin/onb-1/approve",
        {}
      )
    })
    await waitFor(() => {
      expect(screen.queryByText("Mama's Kitchen")).not.toBeInTheDocument()
    })
    expect(screen.getByText("No applications waiting")).toBeInTheDocument()
  })

  it("surfaces a destructive toast when the approve guard vetoes (400) and keeps the row", async () => {
    routePending(() => Promise.resolve({ data: [application()] }))
    mockedApiClient.post.mockRejectedValue(guardVeto)

    render(<OnboardingApprovalsPage />)
    await waitFor(() => expect(screen.getByText("Mama's Kitchen")).toBeInTheDocument())

    fireEvent.click(screen.getByRole("button", { name: "Approve" }))
    const dialog = await screen.findByRole("dialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Approve" }))

    await waitFor(() => {
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({ variant: "destructive", title: "Approval blocked" })
      )
    })
    // The row stays so the admin can inspect the now-red gates.
    expect(screen.getByText("Mama's Kitchen")).toBeInTheDocument()
  })

  it("requires a reason to reject: button disabled until text entered, then POSTs it", async () => {
    routePending(() => Promise.resolve({ data: [application()] }))
    mockedApiClient.post.mockResolvedValue({
      data: application({ status: "REJECTED", rejectionReason: "Evidence inconsistent" }),
    })

    render(<OnboardingApprovalsPage />)
    await waitFor(() => expect(screen.getByText("Mama's Kitchen")).toBeInTheDocument())

    fireEvent.click(screen.getByRole("button", { name: "Reject" }))
    const dialog = await screen.findByRole("dialog")

    // Reason is REQUIRED — the confirm stays disabled while blank.
    const confirm = within(dialog).getByRole("button", { name: "Reject application" })
    expect(confirm).toBeDisabled()

    fireEvent.change(within(dialog).getByLabelText("Reason"), {
      target: { value: "Evidence inconsistent" },
    })
    expect(confirm).not.toBeDisabled()
    fireEvent.click(confirm)

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith(
        "/api/v1/onboarding/admin/onb-1/reject",
        { reason: "Evidence inconsistent" }
      )
    })
    await waitFor(() => {
      expect(screen.queryByText("Mama's Kitchen")).not.toBeInTheDocument()
    })
  })

  it("shows a destructive toast when loading the queue fails with a server error", async () => {
    routePending(() => Promise.reject({ response: { status: 500 } }))

    render(<OnboardingApprovalsPage />)

    await waitFor(() => {
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          variant: "destructive",
          title: "Couldn't load the approval queue",
        })
      )
    })
  })

  // --- ONBD-03: review-pending queue + gate-resolve --------------------------

  it("lists review-pending applications (VERIFYING + manual review) with a per-gate resolve control", async () => {
    routeQueues(
      () => Promise.resolve({ data: [] }),
      () => Promise.resolve({ data: [reviewApplication()] })
    )

    render(<OnboardingApprovalsPage />)

    await waitFor(() => {
      expect(screen.getByText("In manual review")).toBeInTheDocument()
    })
    expect(screen.getByText("Lagos Grill")).toBeInTheDocument()
    expect(
      screen.getByRole("button", { name: /resolve food hygiene rating/i })
    ).toBeInTheDocument()
    // The approve/reject queue is empty, but the "nothing waiting" card must NOT show
    // while review-pending work exists.
    expect(screen.queryByText("No applications waiting")).not.toBeInTheDocument()
  })

  // INT-1 (QA council 20260902-134741 / A15): a MANUAL_REVIEW gate parked beside a FAILED
  // one lands the application in ACTION_REQUIRED. The queue now lists it and the reviewer
  // keeps the Resolve control — with an honest note that the vendor also has work to do.
  it("lists an ACTION_REQUIRED application that still carries a manual-review gate, with the resolve control and a vendor-fixing note", async () => {
    routeQueues(
      () => Promise.resolve({ data: [] }),
      () =>
        Promise.resolve({
          data: [
            reviewApplication({
              id: "rev-2",
              status: "ACTION_REQUIRED",
              shopName: "Suya Spot",
              gates: [
                { gateType: "BUSINESS_VERIFIED", status: "MANUAL_REVIEW", mandatory: true, reason: "Business register temporarily unavailable — a reviewer will check this manually", checkedAt: null },
                { gateType: "FOOD_HYGIENE_RATING", status: "PASSED", mandatory: true, reason: null, checkedAt: null },
                { gateType: "ALLERGEN_DATA_COMPLETE", status: "FAILED", mandatory: true, reason: "Missing allergen data on SKU-9", checkedAt: null },
              ],
            }),
          ],
        })
    )

    render(<OnboardingApprovalsPage />)

    await waitFor(() => {
      expect(screen.getByText("In manual review")).toBeInTheDocument()
    })
    expect(screen.getByText("Suya Spot")).toBeInTheDocument()
    // The reviewer's control is present for the parked gate (the guard accepts ACTION_REQUIRED).
    expect(
      screen.getByRole("button", { name: /resolve business verification/i })
    ).toBeInTheDocument()
    // Honest sequencing: the vendor still owns the failed check and re-runs afterwards.
    expect(
      screen.getByText(/vendor still has a failed check to fix/i)
    ).toBeInTheDocument()
  })

  it("resolves a stuck gate: posts {decision, reason} to the resolve endpoint and refreshes", async () => {
    routeQueues(
      () => Promise.resolve({ data: [] }),
      () => Promise.resolve({ data: [reviewApplication()] })
    )
    mockedApiClient.post.mockResolvedValue({ data: {} })

    render(<OnboardingApprovalsPage />)
    await waitFor(() => expect(screen.getByText("Lagos Grill")).toBeInTheDocument())

    fireEvent.click(screen.getByRole("button", { name: /resolve food hygiene rating/i }))
    const dialog = await screen.findByRole("dialog")

    fireEvent.change(within(dialog).getByLabelText("Decision"), { target: { value: "WAIVE" } })
    fireEvent.change(within(dialog).getByLabelText(/reason/i), {
      target: { value: "Verified manually against the FSA register" },
    })
    fireEvent.click(within(dialog).getByRole("button", { name: /resolve check/i }))

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith(
        "/api/v1/onboarding/admin/rev-1/gates/FOOD_HYGIENE_RATING/resolve",
        { decision: "WAIVE", reason: "Verified manually against the FSA register" }
      )
    })
    // Refresh re-fetches both queues (the initial load already called each once).
    await waitFor(() => {
      expect(mockedApiClient.get).toHaveBeenCalledWith("/api/v1/onboarding/admin/reviews")
    })
  })

  it("requires a reason when failing a gate (confirm disabled until entered)", async () => {
    routeQueues(
      () => Promise.resolve({ data: [] }),
      () => Promise.resolve({ data: [reviewApplication()] })
    )

    render(<OnboardingApprovalsPage />)
    await waitFor(() => expect(screen.getByText("Lagos Grill")).toBeInTheDocument())

    fireEvent.click(screen.getByRole("button", { name: /resolve food hygiene rating/i }))
    const dialog = await screen.findByRole("dialog")

    fireEvent.change(within(dialog).getByLabelText("Decision"), { target: { value: "FAIL" } })
    const confirm = within(dialog).getByRole("button", { name: /resolve check/i })
    expect(confirm).toBeDisabled()

    fireEvent.change(within(dialog).getByLabelText(/reason/i), {
      target: { value: "Rating below the required threshold" },
    })
    expect(confirm).not.toBeDisabled()
  })

  it("keeps the approve/reject queue working alongside the review queue", async () => {
    routeQueues(
      () => Promise.resolve({ data: [application()] }),
      () => Promise.resolve({ data: [reviewApplication()] })
    )
    mockedApiClient.post.mockResolvedValue({ data: application({ status: "APPROVED" }) })

    render(<OnboardingApprovalsPage />)

    await waitFor(() => {
      expect(screen.getByText("In manual review")).toBeInTheDocument()
    })
    // Both queues render: the review app and the approve/reject app.
    expect(screen.getByText("Lagos Grill")).toBeInTheDocument()
    expect(screen.getByText("Mama's Kitchen")).toBeInTheDocument()
    expect(screen.getByText("Awaiting approval")).toBeInTheDocument()

    // The existing approve flow still posts to /approve unchanged.
    fireEvent.click(screen.getByRole("button", { name: "Approve" }))
    const dialog = await screen.findByRole("dialog")
    fireEvent.click(within(dialog).getByRole("button", { name: "Approve" }))

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith(
        "/api/v1/onboarding/admin/onb-1/approve",
        {}
      )
    })
  })
})

/**
 * Phase 35 / UIX-08 — the approvals queue's width tier.
 *
 * PATTERNS A-8, and the phase's own LOWEST-CONFIDENCE tier call. It is flagged
 * for the human-verification pass in plan 35-13; the reasoning is written out at
 * the site in `../page.tsx` rather than only here.
 */
describe("approvals width tier (UIX-08)", () => {
  it("declares the index width tier, with no cap of its own, on the queue's root band", async () => {
    routePending(() => Promise.resolve({ data: [application()] }))

    const { container } = render(<OnboardingApprovalsPage />)
    await waitFor(() => expect(screen.getByText("Mama's Kitchen")).toBeInTheDocument())

    const root = container.firstElementChild as HTMLElement
    expect(root).toHaveAttribute("data-width-tier", "index")
    expect(capTokens(root)).toEqual([])

    // Non-vacuity control: the same filter over a real cap from the vocabulary
    // must find it, so the empty result above is about the page.
    const probe = document.createElement("div")
    probe.className = `mx-auto ${WIDTH_TIER_CLASS.detail}`
    expect(capTokens(probe)).toEqual([WIDTH_TIER_CLASS.detail])
  })

  it("declares the same tier on the spinner branch, so the first paint is not undeclared", () => {
    routePending(() => new Promise(() => {}))

    const { container } = render(<OnboardingApprovalsPage />)

    expect(container.querySelector(".animate-spin")).not.toBeNull()
    expect(container.firstElementChild).toHaveAttribute("data-width-tier", "index")
  })

  it("declares the same tier on the admin-access-required branch", async () => {
    routePending(() => Promise.reject(forbidden))

    const { container } = render(<OnboardingApprovalsPage />)
    await waitFor(() =>
      expect(screen.getByText("Admin access required")).toBeInTheDocument()
    )

    expect(container.firstElementChild).toHaveAttribute("data-width-tier", "index")
  })
})
