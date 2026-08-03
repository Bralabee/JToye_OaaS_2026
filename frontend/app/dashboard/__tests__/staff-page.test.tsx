/**
 * VSA-04 (23-06) — GROUP_ADMIN staff-management screen.
 *
 * NOTE ON WHAT THIS PROVES: the screen is a UX mirror of a server-side gate, NOT
 * the security boundary. `GET /api/v1/staff` is GROUP_ADMIN-gated in 23-04, so a
 * non-GROUP_ADMIN gets a typed `/shop-access-denied` 403 and NO directory data
 * ever reaches the client (T-23-06-02). These cases prove the screen renders that
 * 403 as an honest access-required state rather than a crash/blank, that
 * list/grant/revoke are wired to the 23-04 endpoints, and that the last-GROUP_ADMIN
 * `/last-group-admin` 409 (D-11) surfaces as a clear in-UI message.
 */
import { render, screen, waitFor, fireEvent } from "@testing-library/react"
import StaffPage from "../staff/page"
import apiClient from "@/lib/api-client"
import { fetchMyShops } from "@/lib/shops-api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/lib/shops-api", () => ({
  fetchMyShops: jest.fn(),
}))
const mockedFetchMyShops = fetchMyShops as jest.MockedFunction<typeof fetchMyShops>

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}))

// WR-12: the screen no longer reads the session email — self-identification is on
// the Keycloak `sub` (the userId carried by MyAccessDto via fetchMyShops). We give
// the session NO email to prove the self-revoke warning no longer depends on it.
jest.mock("next-auth/react", () => ({
  useSession: () => ({ data: { user: {} } }),
}))

const SHOP_A = "aaaaaaaa-1111-1111-1111-111111111111"
const SHOP_B = "bbbbbbbb-2222-2222-2222-222222222222"
const USER_GA = "11111111-1111-1111-1111-111111111111"
const USER_SAM = "22222222-2222-2222-2222-222222222222"
const GRANT_GA = "99999999-9999-9999-9999-999999999999"
const GRANT_SAM = "88888888-8888-8888-8888-888888888888"

const shops = [
  { id: SHOP_A, name: "Peckham Kitchen", published: true },
  { id: SHOP_B, name: "Brixton Bakery", published: true },
]

const directory = [
  {
    userId: USER_GA,
    email: "ga@vendor.co.uk",
    displayName: "Ada Owner",
    lastSeen: "2026-07-19T10:00:00Z",
  },
  {
    userId: USER_SAM,
    email: "sam@vendor.co.uk",
    displayName: "Sam Cook",
    lastSeen: "2026-07-18T09:00:00Z",
  },
]

const grants = [
  {
    id: GRANT_GA,
    userId: USER_GA,
    shopId: null,
    role: "GROUP_ADMIN",
    grantSource: "OPERATOR",
    createdAt: "2026-07-01T10:00:00Z",
    createdBy: USER_GA,
  },
  {
    id: GRANT_SAM,
    userId: USER_SAM,
    shopId: SHOP_A,
    role: "STAFF",
    grantSource: "OPERATOR",
    createdAt: "2026-07-02T10:00:00Z",
    createdBy: USER_GA,
  },
]

/** An axios-shaped rejection — the screen reads `err.response.status`. */
const httpError = (status: number, type?: string) =>
  Object.assign(new Error(`Request failed with status code ${status}`), {
    response: { status, data: type ? { type, status } : undefined },
  })

beforeEach(() => {
  jest.clearAllMocks()
  mockedFetchMyShops.mockResolvedValue({
    shops: shops as never,
    isGroupAdmin: true,
    userId: USER_GA,
  })
  mockedApiClient.get.mockResolvedValue({ data: { directory, grants } } as never)
  mockedApiClient.post.mockResolvedValue({ data: {} } as never)
  mockedApiClient.delete.mockResolvedValue({ data: undefined } as never)
})

describe("Staff management screen (VSA-04)", () => {
  it("lists the login-populated directory and the current grants for a GROUP_ADMIN", async () => {
    render(<StaffPage />)

    await waitFor(() => {
      expect(screen.getByText("Sam Cook")).toBeInTheDocument()
    })

    // The list call hits the 23-04 endpoint.
    expect(mockedApiClient.get).toHaveBeenCalledWith("/api/v1/staff")

    // Directory identities are visible...
    expect(screen.getByText("sam@vendor.co.uk")).toBeInTheDocument()
    expect(screen.getByText("Ada Owner")).toBeInTheDocument()

    // ...and the grants resolve to human-readable shop + role.
    expect(screen.getAllByText("Peckham Kitchen").length).toBeGreaterThan(0)
    expect(screen.getByText("Group admin")).toBeInTheDocument()
    expect(screen.getByText("Staff")).toBeInTheDocument()

    // D-09: the directory is login-populated — a short list must not read as a bug.
    expect(
      screen.getByText(/appear here after they sign in/i)
    ).toBeInTheDocument()
  })

  it("grants a shop-scoped role and refreshes the grants list", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    fireEvent.change(screen.getByLabelText(/team member/i), {
      target: { value: USER_SAM },
    })
    fireEvent.change(screen.getByLabelText(/shop/i), { target: { value: SHOP_B } })
    fireEvent.change(screen.getByLabelText(/role/i), {
      target: { value: "SHOP_MANAGER" },
    })

    const newGrant = {
      id: "77777777-7777-7777-7777-777777777777",
      userId: USER_SAM,
      shopId: SHOP_B,
      role: "SHOP_MANAGER",
      createdAt: "2026-07-19T12:00:00Z",
      createdBy: USER_GA,
    }
    mockedApiClient.post.mockResolvedValueOnce({ data: newGrant } as never)
    mockedApiClient.get.mockResolvedValueOnce({
      data: { directory, grants: [...grants, newGrant] },
    } as never)

    fireEvent.click(screen.getByRole("button", { name: /grant access/i }))

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/staff/grant", {
        userId: USER_SAM,
        shopId: SHOP_B,
        role: "SHOP_MANAGER",
      })
    })

    // The list refreshed and the new grant is visible.
    await waitFor(() => {
      expect(screen.getByText("Shop manager")).toBeInTheDocument()
    })
    expect(screen.getAllByText("Brixton Bakery").length).toBeGreaterThan(0)
  })

  it("sends shopId null for a tenant-wide (all shops) grant", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    fireEvent.change(screen.getByLabelText(/team member/i), {
      target: { value: USER_SAM },
    })
    // "All shops / tenant-wide" is the default shop option → shopId null.
    fireEvent.change(screen.getByLabelText(/role/i), {
      target: { value: "GROUP_ADMIN" },
    })
    fireEvent.click(screen.getByRole("button", { name: /grant access/i }))

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/staff/grant", {
        userId: USER_SAM,
        shopId: null,
        role: "GROUP_ADMIN",
      })
    })
  })

  it("revokes a grant and removes it from the list", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    mockedApiClient.get.mockResolvedValueOnce({
      data: { directory, grants: [grants[0]] },
    } as never)

    fireEvent.click(
      screen.getByRole("button", { name: /revoke sam@vendor.co.uk/i })
    )

    await waitFor(() => {
      expect(mockedApiClient.delete).toHaveBeenCalledWith(
        `/api/v1/staff/${GRANT_SAM}`
      )
    })

    await waitFor(() => {
      expect(screen.queryByText("Staff")).not.toBeInTheDocument()
    })
  })

  it("renders the access-required state on a 403 and leaks no directory data", async () => {
    mockedApiClient.get.mockRejectedValueOnce(
      httpError(403, "/shop-access-denied")
    )

    render(<StaffPage />)

    await waitFor(() => {
      expect(screen.getByText(/group admin access required/i)).toBeInTheDocument()
    })

    // T-23-06-02: no directory PII on a 403 — and no crash/blank.
    expect(screen.queryByText("sam@vendor.co.uk")).not.toBeInTheDocument()
    expect(screen.queryByText("Sam Cook")).not.toBeInTheDocument()
    expect(
      screen.queryByRole("button", { name: /grant access/i })
    ).not.toBeInTheDocument()
  })

  it("surfaces the last-GROUP_ADMIN 409 as a clear message and keeps the grant", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    mockedApiClient.delete.mockRejectedValueOnce(
      httpError(409, "/last-group-admin")
    )

    fireEvent.click(
      screen.getByRole("button", { name: /revoke ga@vendor.co.uk/i })
    )

    await waitFor(() => {
      expect(
        screen.getByText(/cannot remove the last group admin/i)
      ).toBeInTheDocument()
    })

    // D-11: the grant survives — the 409 is a refusal, not a silent failure.
    expect(screen.getByText("Group admin")).toBeInTheDocument()
  })

  it("warns that a grant belongs to the signed-in user (self-downgrade, D-11)", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    // The GROUP_ADMIN row is the caller — identified by userId (USER_GA), not email.
    expect(screen.getByText(/this is you/i)).toBeInTheDocument()
  })

  // WR-12: this case FAILS against the pre-fix screen, whose email-based isSelf
  // could never match once the session email was absent (and 23-12 now masks
  // directory emails anyway). Identity is the Keycloak `sub`.
  it("renders the self-revoke warning by userId even with no session email (WR-12)", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    // No session email is provided (see the useSession mock) — the warning and the
    // "This is you" badge still render because the caller's userId matches a grant.
    expect(screen.getByText(/removing your own access/i)).toBeInTheDocument()
    expect(screen.getByText(/this is you/i)).toBeInTheDocument()
  })

  // IN-02: a 409 on the GRANT path is a downgrade refusal, not a removal — its
  // copy must differ from the revoke path's copy.
  it("shows downgrade-specific 409 copy on the grant path, distinct from the revoke path (IN-02)", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    // Grant path 409 → downgrade wording.
    mockedApiClient.post.mockRejectedValueOnce(httpError(409, "/last-group-admin"))
    fireEvent.change(screen.getByLabelText(/team member/i), {
      target: { value: USER_SAM },
    })
    fireEvent.click(screen.getByRole("button", { name: /grant access/i }))
    await waitFor(() =>
      expect(
        screen.getByText(/change the last group admin's role/i)
      ).toBeInTheDocument()
    )
    const grantCopy = screen.getByRole("alert").textContent

    // Revoke path 409 → removal wording (distinct).
    mockedApiClient.delete.mockRejectedValueOnce(httpError(409, "/last-group-admin"))
    fireEvent.click(screen.getByRole("button", { name: /revoke ga@vendor.co.uk/i }))
    await waitFor(() =>
      expect(
        screen.getByText(/remove the last group admin/i)
      ).toBeInTheDocument()
    )
    const revokeCopy = screen.getByRole("alert").textContent

    expect(grantCopy).not.toEqual(revokeCopy)
  })

  // 23-14 (V57 / CR-07): a JIT grant (auto-provisioned on first sign-in) is labelled so
  // a group admin can distinguish it from a deliberate operator grant before enabling
  // strict-scoping — operator grants carry no such label.
  it("labels an auto-granted (JIT) row and leaves operator grants unlabelled (CR-07)", async () => {
    const jitGrant = {
      id: "66666666-6666-6666-6666-666666666666",
      userId: USER_SAM,
      shopId: null,
      role: "GROUP_ADMIN",
      grantSource: "JIT",
      createdAt: "2026-07-03T10:00:00Z",
      createdBy: null,
    }
    // grants[0] is an OPERATOR group-admin; the second row here is the JIT one.
    mockedApiClient.get.mockResolvedValue({
      data: { directory, grants: [grants[0], jitGrant] },
    } as never)

    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    // Exactly one row is auto-granted — the JIT one, not the operator GROUP_ADMIN.
    expect(screen.getAllByText(/auto-granted on first sign-in/i)).toHaveLength(1)
  })

  // #290: the grant picker built its option label as
  // `(displayName || email) + " (" + email + ")"`, so a directory entry with NO
  // display name rendered its masked email TWICE — "j***@vendor.co.uk
  // (j***@vendor.co.uk)". Directory emails are masked at the DTO boundary (WR-10:
  // first local-part character + full domain), which is exactly the form a group
  // admin has to read to recognise a colleague — so printing it twice is noise on
  // the one string that carries the meaning.
  it("renders a display-name-less member's masked email exactly once in the grant picker (#290)", async () => {
    const MASKED = "j***@vendor.co.uk"
    const USER_JIT = "33333333-3333-3333-3333-333333333333"
    mockedApiClient.get.mockResolvedValue({
      data: {
        directory: [
          ...directory,
          { userId: USER_JIT, email: MASKED, displayName: null, lastSeen: null },
        ],
        grants,
      },
    } as never)

    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    const picker = screen.getByLabelText(/team member/i) as HTMLSelectElement
    const option = Array.from(picker.options).find((o) => o.value === USER_JIT)
    expect(option).toBeDefined()
    expect(option!.textContent!.split(MASKED).length - 1).toBe(1)
  })

  // The companion half of #290: a member WITH a display name is labelled
  // "Name (masked-email)" — one name, one address. Constrains the de-dupe so it
  // cannot be "fixed" by dropping the email from the label entirely.
  it("labels a named member as 'name (masked email)' — email still present, still once", async () => {
    const MASKED = "s***@vendor.co.uk"
    mockedApiClient.get.mockResolvedValue({
      data: {
        directory: [
          {
            userId: USER_SAM,
            email: MASKED,
            displayName: "Sam Cook",
            lastSeen: "2026-07-18T09:00:00Z",
          },
        ],
        grants,
      },
    } as never)

    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    const picker = screen.getByLabelText(/team member/i) as HTMLSelectElement
    const option = Array.from(picker.options).find((o) => o.value === USER_SAM)
    expect(option!.textContent).toContain("Sam Cook")
    expect(option!.textContent!.split(MASKED).length - 1).toBe(1)
  })

  // 23-11: revocation is NOT unconditionally immediate — an already-open live
  // stream persists up to the 5-minute SSE timeout.
  it("states the real revocation-timing bound, not unqualified immediacy (23-11)", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    expect(screen.getAllByText(/up to 5 minutes/i).length).toBeGreaterThan(0)
    expect(screen.queryByText(/take effect immediately/i)).not.toBeInTheDocument()
  })
})
