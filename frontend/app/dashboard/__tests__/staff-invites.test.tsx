/**
 * D-07 (37-09) — the Staff page's two invitation parts (UI-SPEC § B1): the
 * "Invite someone" form and the "Pending invitations" table.
 *
 * WHAT THIS PROVES, and what it does not: the page is a client of the 37-07 API
 * (`POST/GET /api/v1/staff/invites`, `POST /{id}/resend`, `DELETE /{id}`), and the
 * server is the authority — it validates the address, refuses a shop-scoped Group
 * admin and decides each invitation's status. These cases prove the page sends what
 * the operator chose, says only what the server answered, keeps the Group-admin rule
 * visible before the server has to refuse it, and makes every open link visible and
 * cancellable. The server half is StaffInviteIntegrationTest.
 *
 * Driven through `StaffPage`, not the components in isolation, because the contract
 * is the page: where the cards sit, that a sent invitation APPEARS in Pending (a
 * re-read of the server's list, not a local append), and that zero open invitations
 * render no card at all.
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import StaffPage from "../staff/page"
import apiClient from "@/lib/api-client"
import { fetchMyShops } from "@/lib/shops-api"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/lib/shops-api", () => ({
  fetchMyShops: jest.fn(),
}))
const mockedFetchMyShops = fetchMyShops as jest.MockedFunction<typeof fetchMyShops>

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

jest.mock("next-auth/react", () => ({
  useSession: () => ({ data: { user: {} } }),
}))

const SHOP_A = "aaaaaaaa-1111-1111-1111-111111111111"
const SHOP_B = "bbbbbbbb-2222-2222-2222-222222222222"
const USER_GA = "11111111-1111-1111-1111-111111111111"

const shops = [
  { id: SHOP_A, name: "Peckham Kitchen", published: true },
  { id: SHOP_B, name: "Brixton Bakery", published: true },
]

const staff = {
  directory: [
    { userId: USER_GA, email: "g***@vendor.co.uk", displayName: "Ada Owner", lastSeen: null },
  ],
  grants: [
    {
      id: "99999999-9999-9999-9999-999999999999",
      userId: USER_GA,
      shopId: null,
      role: "GROUP_ADMIN",
      grantSource: "OPERATOR",
      createdAt: "2026-07-01T10:00:00Z",
      createdBy: USER_GA,
    },
  ],
  people: [
    {
      userId: USER_GA,
      maskedEmail: "g***@vendor.co.uk",
      displayName: "Ada Owner",
      lastSeen: null,
      effectiveAccess: {
        userId: USER_GA,
        level: "GROUP_ADMIN",
        bootstrapAdmin: false,
        allShops: true,
        tenantWideRole: null,
        perShopRole: {},
        realmAdminSeenAt: null,
      },
    },
  ],
}

/** The 37-07 `StaffInviteDto` shape, every field the server sends. */
function invite(
  id: string,
  email: string,
  extra: Partial<{
    role: string
    shopId: string | null
    status: string
    expiresAt: string
  }> = {}
) {
  return {
    id,
    email,
    role: "SHOP_MANAGER",
    shopId: SHOP_A,
    status: "OPEN",
    // 13:32Z is 14:32 in London in October (BST): the page must render UK time.
    expiresAt: "2026-10-10T13:32:00Z",
    createdAt: "2026-10-07T13:32:00Z",
    createdBy: USER_GA,
    acceptedAt: null,
    revokedAt: null,
    ...extra,
  }
}

const OPEN = invite("10000000-0000-0000-0000-000000000001", "maya@example.test")
const EXPIRED = invite("10000000-0000-0000-0000-000000000002", "old@example.test", {
  status: "EXPIRED",
  role: "STAFF",
  shopId: null,
  expiresAt: "2026-10-01T09:00:00Z",
})
const ACCEPTED = invite("10000000-0000-0000-0000-000000000003", "done@example.test", {
  status: "ACCEPTED",
})
const CANCELLED = invite("10000000-0000-0000-0000-000000000004", "gone@example.test", {
  status: "CANCELLED",
})

/** The invitation list the server holds, re-read after every write. */
let serverInvites: unknown[] = []

/** An axios-shaped rejection carrying an RFC 7807 body. */
const httpError = (status: number, type?: string) =>
  Object.assign(new Error(`Request failed with status code ${status}`), {
    response: { status, data: type ? { type, status } : undefined },
  })

beforeEach(() => {
  jest.clearAllMocks()
  serverInvites = []
  mockedFetchMyShops.mockResolvedValue({
    shops: shops as never,
    isGroupAdmin: true,
    userId: USER_GA,
  } as never)
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url === "/api/v1/staff/invites") return Promise.resolve({ data: serverInvites })
    if (url === "/api/v1/staff") return Promise.resolve({ data: staff })
    return Promise.reject(new Error(`unexpected GET ${url}`))
  }) as never)
  mockedApiClient.post.mockResolvedValue({ status: 201, data: OPEN } as never)
  mockedApiClient.delete.mockResolvedValue({ status: 204, data: undefined } as never)
})

async function renderLoaded() {
  render(<StaffPage />)
  await waitFor(() => expect(screen.getByText("Ada Owner")).toBeInTheDocument())
}

const inviteCard = () => screen.getByRole("region", { name: "Invite someone" })
const pendingCard = () => screen.getByRole("region", { name: "Pending invitations" })

describe("Invite someone (UI-SPEC § B1)", () => {
  it("sits under the h1, above People, and Pending invitations sits below People", async () => {
    serverInvites = [OPEN]
    await renderLoaded()
    await screen.findByRole("region", { name: "Pending invitations" })

    const order = screen
      .getAllByRole("heading")
      .map((h) => h.textContent?.trim())
      .filter((t) =>
        ["Staff & access", "Invite someone", "People", "Pending invitations"].includes(t ?? "")
      )
    expect(order).toEqual(["Staff & access", "Invite someone", "People", "Pending invitations"])

    expect(
      within(inviteCard()).getByText(
        "We'll email them a link. When they accept, they get exactly the access you choose here, nothing more."
      )
    ).toBeInTheDocument()
  })

  it("posts {email, role, shopId}, toasts the copy with the UK expiry, and the invitation appears in Pending", async () => {
    await renderLoaded()
    // Zero invitations: no Pending card at all.
    expect(screen.queryByRole("region", { name: "Pending invitations" })).toBeNull()

    const card = inviteCard()
    fireEvent.change(within(card).getByLabelText("Email"), {
      target: { value: "maya@example.test" },
    })
    fireEvent.change(within(card).getByLabelText("Role"), { target: { value: "SHOP_MANAGER" } })
    fireEvent.change(within(card).getByLabelText("Shop"), { target: { value: SHOP_A } })

    mockedApiClient.post.mockImplementationOnce((() => {
      serverInvites = [OPEN]
      return Promise.resolve({ status: 201, data: OPEN })
    }) as never)
    fireEvent.click(within(card).getByRole("button", { name: "Send invitation" }))

    await waitFor(() =>
      expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/staff/invites", {
        email: "maya@example.test",
        role: "SHOP_MANAGER",
        shopId: SHOP_A,
      })
    )
    await waitFor(() =>
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          title:
            "Invitation sent to maya@example.test. The link works once and expires on 10 Oct, 14:32.",
        })
      )
    )

    // The list was RE-READ from the server, and the new row is there.
    const pending = await screen.findByRole("region", { name: "Pending invitations" })
    const row = within(pending).getByText("maya@example.test").closest("tr") as HTMLElement
    expect(within(row).getByText("Shop manager · Peckham Kitchen")).toBeInTheDocument()
    expect(within(row).getByText("10 Oct, 14:32")).toBeInTheDocument()
  })

  it("sends shopId null for All shops", async () => {
    await renderLoaded()
    const card = inviteCard()
    fireEvent.change(within(card).getByLabelText("Email"), {
      target: { value: "sam@example.test" },
    })
    fireEvent.change(within(card).getByLabelText("Role"), { target: { value: "STAFF" } })
    fireEvent.click(within(card).getByRole("button", { name: "Send invitation" }))
    await waitFor(() =>
      expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/staff/invites", {
        email: "sam@example.test",
        role: "STAFF",
        shopId: null,
      })
    )
  })

  it("locks the shop to All shops for Group admin, with the hint, and sends shopId null", async () => {
    await renderLoaded()
    const card = inviteCard()
    const shop = within(card).getByLabelText("Shop") as HTMLSelectElement
    // A shop chosen first must not survive the switch to Group admin.
    fireEvent.change(shop, { target: { value: SHOP_B } })
    expect(shop).not.toBeDisabled()

    fireEvent.change(within(card).getByLabelText("Role"), { target: { value: "GROUP_ADMIN" } })

    expect(shop).toBeDisabled()
    expect(shop.selectedOptions[0].textContent).toBe("All shops")
    expect(within(card).getByText("Group admin always covers every shop.")).toBeInTheDocument()

    fireEvent.change(within(card).getByLabelText("Email"), {
      target: { value: "boss@example.test" },
    })
    fireEvent.click(within(card).getByRole("button", { name: "Send invitation" }))
    await waitFor(() =>
      expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/staff/invites", {
        email: "boss@example.test",
        role: "GROUP_ADMIN",
        shopId: null,
      })
    )
  })

  it("offers each role with its plain-English hint and every shop plus All shops", async () => {
    await renderLoaded()
    const card = inviteCard()
    const roles = Array.from(
      (within(card).getByLabelText("Role") as HTMLSelectElement).options
    ).map((o) => o.textContent)
    expect(roles).toEqual([
      "Staff — order ops on one shop",
      "Shop manager — full CRUD on one shop",
      "Group admin — all shops + staff management",
    ])
    const shopOptions = Array.from(
      (within(card).getByLabelText("Shop") as HTMLSelectElement).options
    ).map((o) => o.textContent)
    expect(shopOptions).toEqual(["All shops", "Peckham Kitchen", "Brixton Bakery"])
  })

  it("renders the other-business 409 inline as an alert, not a toast", async () => {
    await renderLoaded()
    mockedApiClient.post.mockRejectedValueOnce(
      httpError(409, "https://api.jtoye.uk/problems/staff-invite-email-in-other-business") as never
    )
    const card = inviteCard()
    fireEvent.change(within(card).getByLabelText("Email"), {
      target: { value: "taken@example.test" },
    })
    fireEvent.click(within(card).getByRole("button", { name: "Send invitation" }))

    const alert = await within(card).findByRole("alert")
    expect(alert).toHaveTextContent(
      "This email address already has a J'Toye account with another business. Ask them for a different email address."
    )
    expect(mockToast).not.toHaveBeenCalled()
  })

  it("refuses an empty email inline, focuses it, and sends nothing", async () => {
    await renderLoaded()
    const card = inviteCard()
    fireEvent.click(within(card).getByRole("button", { name: "Send invitation" }))

    const email = within(card).getByLabelText("Email")
    expect(await within(card).findByRole("alert")).toHaveTextContent("Enter an email address.")
    expect(email).toHaveAttribute("aria-invalid", "true")
    expect(email).toHaveFocus()
    expect(mockedApiClient.post).not.toHaveBeenCalled()
  })

  it("says an invitation is already open on the 200 replay, rather than claiming a send", async () => {
    await renderLoaded()
    mockedApiClient.post.mockResolvedValueOnce({ status: 200, data: OPEN } as never)
    const card = inviteCard()
    fireEvent.change(within(card).getByLabelText("Email"), {
      target: { value: "maya@example.test" },
    })
    fireEvent.click(within(card).getByRole("button", { name: "Send invitation" }))

    await waitFor(() =>
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          title:
            "An invitation to maya@example.test is already open. It expires on 10 Oct, 14:32.",
        })
      )
    )
  })
})

describe("Pending invitations (UI-SPEC § B1)", () => {
  it("lists open and expired invitations with Email · Access offered · Expires · Actions, and hides accepted and cancelled ones", async () => {
    serverInvites = [OPEN, EXPIRED, ACCEPTED, CANCELLED]
    await renderLoaded()
    const pending = await screen.findByRole("region", { name: "Pending invitations" })

    const heads = within(pending)
      .getAllByRole("columnheader")
      .map((h) => h.textContent)
    expect(heads).toEqual(["Email", "Access offered", "Expires", "Actions"])

    expect(within(pending).getByText("maya@example.test")).toBeInTheDocument()
    expect(within(pending).getByText("old@example.test")).toBeInTheDocument()
    expect(within(pending).queryByText("done@example.test")).toBeNull()
    expect(within(pending).queryByText("gone@example.test")).toBeNull()

    // The expired row stays listed, greyed, with "Expired"; the open one is not greyed.
    const expiredRow = within(pending).getByText("old@example.test").closest("tr") as HTMLElement
    expect(within(expiredRow).getByText("Expired")).toBeInTheDocument()
    expect(within(expiredRow).getByText("Staff · all shops")).toBeInTheDocument()
    expect(expiredRow).toHaveClass("bg-slate-50")
    const openRow = within(pending).getByText("maya@example.test").closest("tr") as HTMLElement
    expect(within(openRow).queryByText("Expired")).toBeNull()
    expect(openRow).not.toHaveClass("bg-slate-50")
  })

  it("renders no Pending card when only accepted or cancelled invitations exist", async () => {
    serverInvites = [ACCEPTED, CANCELLED]
    await renderLoaded()
    expect(screen.queryByRole("region", { name: "Pending invitations" })).toBeNull()
    expect(screen.queryByText("Pending invitations")).toBeNull()
  })

  it("'Send invitation again' calls resend, toasts the new expiry and re-reads the list", async () => {
    serverInvites = [EXPIRED]
    await renderLoaded()
    const pending = await screen.findByRole("region", { name: "Pending invitations" })

    const renewed = invite("10000000-0000-0000-0000-000000000009", "old@example.test", {
      role: "STAFF",
      shopId: null,
    })
    mockedApiClient.post.mockImplementationOnce((() => {
      serverInvites = [renewed]
      return Promise.resolve({ status: 201, data: renewed })
    }) as never)
    fireEvent.click(
      within(pending).getByRole("button", { name: "Send invitation again to old@example.test" })
    )

    await waitFor(() =>
      expect(mockedApiClient.post).toHaveBeenCalledWith(
        `/api/v1/staff/invites/${EXPIRED.id}/resend`
      )
    )
    await waitFor(() =>
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          title:
            "Invitation sent to old@example.test. The link works once and expires on 10 Oct, 14:32.",
        })
      )
    )
    await waitFor(() => {
      const row = within(pendingCard()).getByText("old@example.test").closest("tr") as HTMLElement
      expect(within(row).queryByText("Expired")).toBeNull()
    })
  })

  it("'Cancel invitation' asks first, with 'Keep invitation' keeping it", async () => {
    serverInvites = [OPEN]
    await renderLoaded()
    const pending = await screen.findByRole("region", { name: "Pending invitations" })
    fireEvent.click(
      within(pending).getByRole("button", { name: "Cancel invitation for maya@example.test" })
    )

    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByText("Cancel this invitation?")).toBeInTheDocument()
    expect(
      within(dialog).getByText(
        "The link sent to maya@example.test will stop working. You can send a new one later."
      )
    ).toBeInTheDocument()
    expect(within(dialog).getByRole("button", { name: "Cancel invitation" })).toHaveClass(
      "bg-destructive"
    )

    fireEvent.click(within(dialog).getByRole("button", { name: "Keep invitation" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(mockedApiClient.delete).not.toHaveBeenCalled()
    expect(within(pendingCard()).getByText("maya@example.test")).toBeInTheDocument()
  })

  it("confirming the cancel deletes the invitation and the card goes when none are left", async () => {
    serverInvites = [OPEN]
    await renderLoaded()
    const pending = await screen.findByRole("region", { name: "Pending invitations" })
    fireEvent.click(
      within(pending).getByRole("button", { name: "Cancel invitation for maya@example.test" })
    )
    const dialog = await screen.findByRole("dialog")
    mockedApiClient.delete.mockImplementationOnce((() => {
      serverInvites = [{ ...OPEN, status: "CANCELLED" }]
      return Promise.resolve({ status: 204, data: undefined })
    }) as never)
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Cancel invitation" }))
    })

    await waitFor(() =>
      expect(mockedApiClient.delete).toHaveBeenCalledWith(`/api/v1/staff/invites/${OPEN.id}`)
    )
    await waitFor(() =>
      expect(screen.queryByRole("region", { name: "Pending invitations" })).toBeNull()
    )
  })
})
