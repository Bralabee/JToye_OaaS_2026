import { render, screen, waitFor, fireEvent, act, within } from "@testing-library/react"

// Config-injected support channel + review SLA (GLOBAL_RULE_6). Set BEFORE the
// component reads them at render time — resolveSupportChannel prefers the URL, so
// the rejection support link resolves to NEXT_PUBLIC_SUPPORT_URL below.
process.env.NEXT_PUBLIC_SUPPORT_EMAIL = "support@jtoye.test"
process.env.NEXT_PUBLIC_SUPPORT_URL = "https://help.jtoye.test/onboarding"
process.env.NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS = "2"

import OnboardingPage from "../page"
import apiClient from "@/lib/api-client"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { manyShops, pagedResponse, param } from "@/test-utils/spring-page"
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
    // Phase 22 bound the onboarding.notifications consumer, and INT-4 (QA council
    // 20260902-134741) added the fallback recipient: the mail goes to tenants.contact_email
    // or, when that is blank, to the submitting user's directory email. This assertion is
    // about the COPY only — delivery is proven server-side (NotificationDispatchServiceTest,
    // OnboardingSubmitterResolverIntegrationTest) and live in Mailhog, never by this render.
    expect(screen.getByText(/email you/i)).toBeInTheDocument()
    expect(screen.queryByText(/check back here for an update/i)).not.toBeInTheDocument()
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

  // --- INT-5 (QA council 20260902-134741 / A13): honest actor copy ----------
  // Under the owner-ratified interim (Phase 21 D-01/D-02) there is no separate J'Toye
  // reviewer: the tenant's OWN realm admin resolves gates and approves from
  // /dashboard/onboarding/approvals. The copy must name that actor and route, and must
  // never imply an independent "team". Authority code is deliberately unchanged (A13).

  it("in-review copy names the real reviewer (an administrator on your own account, via Approvals) — never 'our team'", async () => {
    routeGet(() =>
      Promise.resolve({
        data: onboarding("VERIFYING", {
          reviewPending: true,
          gates: gatesWith("FOOD_HYGIENE_RATING", "MANUAL_REVIEW", "Awaiting a reviewer."),
        }),
      })
    )

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("In review")).toBeInTheDocument()
    })
    expect(screen.getByText(/an administrator on your own account/i)).toBeInTheDocument()
    expect(screen.getByText(/Approvals/)).toBeInTheDocument()
    expect(screen.queryByText(/our team/i)).not.toBeInTheDocument()
  })

  it("PENDING_APPROVAL copy names the real approver instead of a passive 'being finalised'", async () => {
    routeGet(() =>
      Promise.resolve({ data: onboarding("PENDING_APPROVAL", { gates: gates("PASSED") }) })
    )

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("Awaiting approval")).toBeInTheDocument()
    })
    expect(screen.getByText(/an administrator on your own account/i)).toBeInTheDocument()
    expect(screen.queryByText(/approval is being finalised/i)).not.toBeInTheDocument()
  })

  // --- INT-14 (QA council 20260902-134741): "re-run your checks" only where RESUBMIT exists
  // The state machine declares RESUBMIT from ACTION_REQUIRED only. The instruction must
  // therefore render there and nowhere else; every other state names the action it has.

  it("VERIFYING (in review) never says 're-run your checks', names the reviewer, and keeps the deep link", async () => {
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
    // Affordance by ROLE, not text: there is no Re-run control in VERIFYING.
    expect(screen.queryByRole("button", { name: /re-run checks/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/re-run your checks/i)).not.toBeInTheDocument()
    expect(screen.getAllByText(/reviewer is looking/i).length).toBeGreaterThan(0)
    expect(screen.getByRole("link", { name: /edit shop details/i })).toHaveAttribute(
      "href",
      "/dashboard/shops"
    )
  })

  it("CONTROL — ACTION_REQUIRED still says 're-run your checks' and offers the Re-run control", async () => {
    routeGet(() =>
      Promise.resolve({
        data: onboarding("ACTION_REQUIRED", {
          gates: gatesWith("ALLERGEN_DATA_COMPLETE", "FAILED", "Missing allergen data on SKU-1"),
        }),
      })
    )

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /re-run checks/i })).toBeInTheDocument()
    })
    expect(screen.getAllByText(/re-run your checks/i).length).toBeGreaterThan(0)
  })

  it("ACTION_REQUIRED with a parked (manual-review) gate beside a failed one states the sequencing: an administrator can resolve it now, then re-run", async () => {
    routeGet(() =>
      Promise.resolve({
        data: onboarding("ACTION_REQUIRED", {
          gates: [
            { gateType: "BUSINESS_VERIFIED", status: "PASSED", mandatory: true, reason: null, checkedAt: null },
            { gateType: "FOOD_HYGIENE_RATING", status: "MANUAL_REVIEW", mandatory: true, reason: "No confident FHRS match.", checkedAt: null },
            { gateType: "ALLERGEN_DATA_COMPLETE", status: "FAILED", mandatory: true, reason: "Missing allergen data on SKU-1", checkedAt: null },
          ],
        }),
      })
    )

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("What needs your attention")).toBeInTheDocument()
    })
    expect(screen.getByText(/administrator on your account can also resolve/i)).toBeInTheDocument()
    expect(screen.getByRole("button", { name: /re-run checks/i })).toBeInTheDocument()
  })

  it("DRAFT names 'submit', not 're-run', on the company-details card", async () => {
    routeGet(() => Promise.resolve({ data: onboarding("DRAFT", { companyNumber: "445790" }) }))

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByLabelText("Companies House number")).toBeInTheDocument()
    })
    expect(screen.queryByText(/re-run your checks/i)).not.toBeInTheDocument()
    expect(screen.getByText(/then submit your application/i)).toBeInTheDocument()
  })

  // --- INT-6 / FE-6 (QA council 20260902-134741): every GateType has copy; WAIVED reads honestly

  it("INT-6: renders every backend GateType with its own label — the literal 'Check' fallback never appears", async () => {
    const ALL_TYPES = [
      "BUSINESS_VERIFIED",
      "FOOD_HYGIENE_RATING",
      "FOOD_BUSINESS_REGISTRATION",
      "IDENTITY_KYC",
      "PAYMENTS_CONNECTED",
      "AGREEMENT_SIGNED",
      "ALLERGEN_DATA_COMPLETE",
      "MENU_MINIMUM",
    ] as const
    routeGet(() =>
      Promise.resolve({
        data: onboarding("VERIFYING", {
          gates: ALL_TYPES.map((gateType) => ({
            gateType: gateType as GateType,
            status: "PENDING" as GateStatus,
            mandatory: true,
            reason: null,
            checkedAt: null,
          })),
        }),
      })
    )

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("Compliance checks")).toBeInTheDocument()
    })
    for (const label of [
      "Business verification",
      "Food hygiene rating",
      "Food business registration",
      "Identity verification",
      "Payments connected",
      "Agreement signed",
      "Allergen data",
      "Menu minimum",
    ]) {
      expect(screen.getByText(label)).toBeInTheDocument()
    }
    expect(screen.queryAllByText(/^Check$/)).toHaveLength(0)
  })

  it("FE-6: a WAIVED gate's badge reads 'Not applicable' — never 'Not required' beside the 'Required' label", async () => {
    routeGet(() =>
      Promise.resolve({
        data: onboarding("VERIFYING", {
          gates: gatesWith("BUSINESS_VERIFIED", "WAIVED", "no company number — sole trader"),
        }),
      })
    )

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("Business verification")).toBeInTheDocument()
    })
    expect(screen.getByText("Not applicable")).toBeInTheDocument()
    expect(screen.queryByText("Not required")).not.toBeInTheDocument()
    // The requirement axis is untouched: the row still says Required.
    expect(screen.getAllByText("Required").length).toBeGreaterThan(0)
  })

  it("INT-6: BUSINESS_VERIFIED in MANUAL_REVIEW (the keyless-stack default) has remediation copy, and no dangling company-number CTA in VERIFYING", async () => {
    routeGet(() =>
      Promise.resolve({
        data: onboarding("VERIFYING", {
          reviewPending: true,
          companyNumber: "00445790",
          gates: gatesWith(
            "BUSINESS_VERIFIED",
            "MANUAL_REVIEW",
            "Business register temporarily unavailable — a reviewer will check this manually"
          ),
        }),
      })
    )

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("What needs your attention")).toBeInTheDocument()
    })
    // Rendered in the gate breakdown AND the remediation card.
    expect(screen.getAllByText("Business verification").length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText(/8 characters/i)).toBeInTheDocument()
    // The inline company-number card is DRAFT/ACTION_REQUIRED only, so the deep link
    // must not be offered where its target does not exist.
    expect(screen.queryByRole("button", { name: /edit company number/i })).not.toBeInTheDocument()
  })

  it("INT-6: the same BUSINESS_VERIFIED manual-review remediation offers the company-number CTA in ACTION_REQUIRED, where the target exists", async () => {
    routeGet(() =>
      Promise.resolve({
        data: onboarding("ACTION_REQUIRED", {
          companyNumber: "445790",
          gates: [
            { gateType: "BUSINESS_VERIFIED", status: "MANUAL_REVIEW", mandatory: true, reason: "Business register temporarily unavailable — a reviewer will check this manually", checkedAt: null },
            { gateType: "FOOD_HYGIENE_RATING", status: "PASSED", mandatory: true, reason: null, checkedAt: null },
            { gateType: "ALLERGEN_DATA_COMPLETE", status: "FAILED", mandatory: true, reason: "Missing allergen data on SKU-1", checkedAt: null },
          ],
        }),
      })
    )

    render(<OnboardingPage />)

    await waitFor(() => {
      expect(screen.getByText("What needs your attention")).toBeInTheDocument()
    })
    expect(screen.getByRole("button", { name: /edit company number/i })).toBeInTheDocument()
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
      // QA ONB-5: honest terminal copy — WITHDRAWN is terminal (re-create → 409), so
      // the page must not promise a self-serve fresh application it can't deliver.
      expect(
        screen.getByText(/contact support if you.?d like to onboard again/i)
      ).toBeInTheDocument()
    })
    expect(
      screen.queryByText(/starting again begins a fresh application/i)
    ).not.toBeInTheDocument()
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

/**
 * #485 call site :232 — the onboarding shop picker is followed to the end.
 *
 * This is the costliest of the six to lose: the onboarding state machine is the sole
 * writer of `Shop.published`, so a shop that cannot be picked here cannot be taken
 * live at all. 150 shops against a fake endpoint that honours `?page=`/`?size=` and
 * applies core-java's 100-row clamp; the assertion is on shop 150, because a
 * two-shop fixture cannot tell paged code from unpaged code.
 */
describe("#485 — the shop picker pages the whole list", () => {
  const SHOPS = manyShops(150)
  const TAIL = SHOPS[SHOPS.length - 1]

  beforeEach(() => {
    jest.clearAllMocks()
    mockedApiClient.get.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/onboarding/me")) return Promise.reject(notFound) as Promise<never>
      if (url.startsWith("/api/v1/shops")) {
        return Promise.resolve(pagedResponse(url, SHOPS)) as Promise<never>
      }
      return Promise.resolve({ data: {} }) as Promise<never>
    })
  })

  it("offers a shop past the first page, so it can still be taken live", async () => {
    render(<OnboardingPage />)

    await waitFor(() => expect(screen.getByText("Take your shop live")).toBeInTheDocument())
    expect(screen.getByRole("option", { name: TAIL.name })).toBeInTheDocument()
  })

  it("walks page 0 then page 1, keeping the alphabetical sort on both", async () => {
    // The single request this replaced carried `&sort=name,asc`. Dropping it while
    // fixing the truncation would have traded one defect for a scrambled select.
    render(<OnboardingPage />)

    await waitFor(() => {
      const shopUrls = mockedApiClient.get.mock.calls
        .map(([u]) => String(u))
        .filter((u) => u.startsWith("/api/v1/shops"))
      expect(shopUrls.map((u) => param(u, "page"))).toEqual(["0", "1"])
      expect(shopUrls.every((u) => param(u, "sort") === "name,asc")).toBe(true)
    })
  })
})

// ============================================================================
// Phase 35 plan 35-05 — the Detail tier, on EVERY page-level render branch.
//
// This page has three page-level returns: the loading spinner, the
// "Take your shop live" CREATE FORM when GET /me is a 404, and the loaded state
// machine. After plan 35-02 the dashboard band declares the Shell tier, so a
// branch that did NOT carry the Detail tier would render at the shell's content
// box while its siblings render at 1100 — the page would change width as the
// request settles. The create-form branch matters most of the three: it is a
// fully-rendered state a brand-new vendor sees FIRST, not a transient.
//
// The assertions compare branches TO EACH OTHER, not each to a literal, because
// the defect is the DIFFERENCE. `RemediationRow` and `GateRow` are sub-components
// rendered INSIDE the loaded branch; they inherit the tier and are deliberately
// not asserted here — a cap nested inside a cap is the defect, not the fix.
// ============================================================================

/** What an element SAYS about its width: the declared tier, and every cap on it. */
interface TierDeclaration {
  tier: string | null
  maxWidthClasses: string[]
}

// Token match, never a substring. Variant prefixes are matched too: a
// responsive second cap is still a second cap.
const MAX_WIDTH_TOKEN = /^(?:[A-Za-z0-9_[\]().%-]+:)*max-w-/

function declarationOf(el: Element): TierDeclaration {
  return {
    tier: el.getAttribute("data-width-tier"),
    maxWidthClasses: Array.from(el.classList)
      .filter((c) => MAX_WIDTH_TOKEN.test(c))
      .sort(),
  }
}

describe("Onboarding — the Detail tier on EVERY page-level render branch (35-05)", () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  // Each driver proves WHICH branch it got before handing back the root element.
  // A driver that silently returned the wrong branch would make every assertion
  // below vacuous.

  function renderLoadingBranch(): Element {
    // Never settles, so the component stays on its initial loading state.
    routeGet(() => new Promise<never>(() => {}))
    const { container } = render(<OnboardingPage />)
    expect(container.querySelector(".animate-spin")).not.toBeNull()
    return container.firstElementChild as Element
  }

  async function renderCreateFormBranch(): Promise<Element> {
    routeGet(() => Promise.reject(notFound))
    const { container } = render(<OnboardingPage />)
    await within(container as HTMLElement).findByText("Take your shop live")
    return container.firstElementChild as Element
  }

  async function renderLoadedBranch(): Promise<Element> {
    routeGet(() => Promise.resolve({ data: onboarding("DRAFT") }))
    const { container } = render(<OnboardingPage />)
    await within(container as HTMLElement).findByText("Go live")
    return container.firstElementChild as Element
  }

  // NO `describe.each` in this block, and the reason is a gate rather than a
  // style preference: `scripts/count-test-blocks.mjs` VOIDs (rc=2) on
  // `describe.each`, because it multiplies every block inside it and the count
  // cannot be resolved statically. It names the file and line and refuses to
  // guess, which is the behaviour we want — the cost is that a table-driven
  // describe here would leave the source half of the docs-metrics loop unable to
  // answer at all. Blocks are therefore written out one per branch.

  describe("CONTROL — the instrument can fail", () => {
    it("counts two max-width classes when an element carries two", () => {
      const el = document.createElement("div")
      el.className = `mx-auto ${WIDTH_TIER_CLASS.detail} ${WIDTH_TIER_CLASS.shell}`
      expect(declarationOf(el).maxWidthClasses).toHaveLength(2)
    })

    it("distinguishes a tiered declaration from an untiered one", () => {
      const tiered = document.createElement("div")
      tiered.className = `mx-auto ${WIDTH_TIER_CLASS.detail}`
      tiered.setAttribute("data-width-tier", "detail")
      const bare = document.createElement("div")
      bare.className = "space-y-6"
      expect(declarationOf(tiered)).not.toEqual(declarationOf(bare))
    })
  })

  describe("the LOADING branch", () => {
    it("declares the detail width tier", () => {
      expect(declarationOf(renderLoadingBranch()).tier).toBe("detail")
    })

    it("carries the detail max-width utility from the tier vocabulary", () => {
      expect(renderLoadingBranch().classList.contains(WIDTH_TIER_CLASS.detail)).toBe(true)
    })

    it("centres inside the wider Shell band", () => {
      expect(renderLoadingBranch().classList.contains("mx-auto")).toBe(true)
    })

    it("carries exactly ONE max-width class", () => {
      expect(declarationOf(renderLoadingBranch()).maxWidthClasses).toEqual([
        WIDTH_TIER_CLASS.detail,
      ])
    })
  })

  describe("the CREATE-FORM branch", () => {
    it("declares the detail width tier", async () => {
      expect(declarationOf(await renderCreateFormBranch()).tier).toBe("detail")
    })

    it("carries the detail max-width utility from the tier vocabulary", async () => {
      expect((await renderCreateFormBranch()).classList.contains(WIDTH_TIER_CLASS.detail)).toBe(
        true
      )
    })

    it("centres inside the wider Shell band", async () => {
      expect((await renderCreateFormBranch()).classList.contains("mx-auto")).toBe(true)
    })

    it("carries exactly ONE max-width class", async () => {
      expect(declarationOf(await renderCreateFormBranch()).maxWidthClasses).toEqual([
        WIDTH_TIER_CLASS.detail,
      ])
    })
  })

  describe("the LOADED branch", () => {
    it("declares the detail width tier", async () => {
      expect(declarationOf(await renderLoadedBranch()).tier).toBe("detail")
    })

    it("carries the detail max-width utility from the tier vocabulary", async () => {
      expect((await renderLoadedBranch()).classList.contains(WIDTH_TIER_CLASS.detail)).toBe(true)
    })

    it("centres inside the wider Shell band", async () => {
      expect((await renderLoadedBranch()).classList.contains("mx-auto")).toBe(true)
    })

    it("carries exactly ONE max-width class", async () => {
      expect(declarationOf(await renderLoadedBranch()).maxWidthClasses).toEqual([
        WIDTH_TIER_CLASS.detail,
      ])
    })
  })

  it("the LOADING branch renders the same declaration as the loaded branch", async () => {
    const loading = declarationOf(renderLoadingBranch())
    const loaded = declarationOf(await renderLoadedBranch())
    expect(loading).toEqual(loaded)
  })

  it("the CREATE-FORM branch renders the same declaration as the loaded branch", async () => {
    const createForm = declarationOf(await renderCreateFormBranch())
    const loaded = declarationOf(await renderLoadedBranch())
    expect(createForm).toEqual(loaded)
  })

  it("the LOADING branch keeps every rhythm class it already had", () => {
    expect(Array.from(renderLoadingBranch().classList)).toEqual(
      expect.arrayContaining(["flex", "h-full", "items-center", "justify-center"])
    )
  })

  it("the CREATE-FORM branch keeps every rhythm class it already had", async () => {
    expect(Array.from((await renderCreateFormBranch()).classList)).toEqual(
      expect.arrayContaining(["space-y-6"])
    )
  })

  it("the LOADED branch keeps every rhythm class it already had", async () => {
    expect(Array.from((await renderLoadedBranch()).classList)).toEqual(
      expect.arrayContaining(["space-y-6"])
    )
  })
})
