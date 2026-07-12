import { render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import OnboardingApprovalsPage from "../page"
import apiClient from "@/lib/api-client"
import type { AdminOnboardingDto, GateStatus } from "@/types/api"

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

const forbidden = { response: { status: 403 } }
const guardVeto = { response: { status: 400 } }

function routePending(impl: () => Promise<unknown>) {
  mockedApiClient.get.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/onboarding/admin/pending")) return impl() as Promise<never>
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
})
