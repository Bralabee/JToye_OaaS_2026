/**
 * VSA-04 (23-06) — GROUP_ADMIN staff-management screen; D-09 (37-06) — the People card.
 *
 * NOTE ON WHAT THIS PROVES: the screen is a UX mirror of a server-side gate, NOT
 * the security boundary. `GET /api/v1/staff` is GROUP_ADMIN-gated in 23-04, so a
 * non-GROUP_ADMIN gets a typed `/shop-access-denied` 403 and NO directory data
 * ever reaches the client (T-23-06-02). These cases prove the screen renders that
 * 403 as an honest access-required state rather than a crash/blank, that
 * list/grant/revoke are wired to the 23-04 endpoints, and that the last-GROUP_ADMIN
 * `/last-group-admin` 409 (D-11) surfaces as a clear in-UI message.
 *
 * D-09 (37-06): the Access column renders `people[].effectiveAccess` exactly as the
 * server computed it (37-05, `ShopAccessService.effectiveAccessFor`). The browser
 * derives no access value: a person whose server value is missing reads "Not
 * recorded", never a level reconstructed from grant rows (T-37-12).
 */
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import StaffPage from "../staff/page"
import apiClient from "@/lib/api-client"
import { fetchMyShops } from "@/lib/shops-api"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"

/**
 * Every width-cap utility an element declares, as tokens. A token filter, never a
 * substring search — `classList` membership is what a browser resolves.
 */
const capTokens = (el: Element) =>
  Array.from(el.classList).filter((c) => c.startsWith("max-w-"))

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

/**
 * Directory emails are MASKED at the DTO boundary (23-12 / WR-10,
 * `DirectoryEntryDto.maskEmail`: first local-part character + `***` + the full
 * domain). `ga@vendor.co.uk` is therefore a response the API cannot produce, and
 * fixtures carrying it describe a shape that does not occur — which matters here
 * because #290 was PRECISELY a masked-email rendering bug ("j***@vendor.co.uk
 * (j***@vendor.co.uk)"), a class an unmasked fixture cannot catch. These are the
 * exact strings `maskEmail("ga@vendor.co.uk")` / `maskEmail("sam@vendor.co.uk")`
 * return.
 */
const EMAIL_GA = "g***@vendor.co.uk"
const EMAIL_SAM = "s***@vendor.co.uk"

const directory = [
  {
    userId: USER_GA,
    email: EMAIL_GA,
    displayName: "Ada Owner",
    lastSeen: "2026-07-19T10:00:00Z",
  },
  {
    userId: USER_SAM,
    email: EMAIL_SAM,
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

/** The 37-05 `EffectiveAccess` shape, with every field the server always sends. */
function access(
  userId: string,
  level: "GROUP_ADMIN" | "REALM_ADMIN" | "SHOP_ROLES" | "NONE",
  extra: Partial<{
    bootstrapAdmin: boolean
    allShops: boolean
    tenantWideRole: string | null
    perShopRole: Record<string, string>
    realmAdminSeenAt: string | null
  }> = {}
) {
  return {
    userId,
    level,
    bootstrapAdmin: false,
    allShops: level === "GROUP_ADMIN" || level === "REALM_ADMIN",
    tenantWideRole: null,
    perShopRole: {},
    realmAdminSeenAt: null,
    ...extra,
  }
}

/** A `StaffPersonDto` for a directory entry. */
function person(
  d: { userId: string; email: string | null; displayName: string | null; lastSeen: string | null },
  effectiveAccess: unknown
) {
  return {
    userId: d.userId,
    maskedEmail: d.email,
    displayName: d.displayName,
    lastSeen: d.lastSeen,
    effectiveAccess,
  }
}

const people = [
  person(directory[0], access(USER_GA, "GROUP_ADMIN")),
  person(directory[1], access(USER_SAM, "SHOP_ROLES", { perShopRole: { [SHOP_A]: "STAFF" } })),
]

/** An axios-shaped rejection — the screen reads `err.response.status`. */
const httpError = (status: number, type?: string) =>
  Object.assign(new Error(`Request failed with status code ${status}`), {
    response: { status, data: type ? { type, status } : undefined },
  })

/** The People-table row (`<tr>`) that names this person. */
function rowOf(name: string): HTMLElement {
  const row = screen.getByText(name).closest("tr")
  expect(row).not.toBeNull()
  return row as HTMLElement
}

beforeEach(() => {
  jest.clearAllMocks()
  mockedFetchMyShops.mockResolvedValue({
    shops: shops as never,
    isGroupAdmin: true,
    userId: USER_GA,
  } as never)
  mockedApiClient.get.mockResolvedValue({ data: { directory, grants, people } } as never)
  mockedApiClient.post.mockResolvedValue({ data: {} } as never)
  mockedApiClient.delete.mockResolvedValue({ data: undefined } as never)
})

describe("Staff management screen (VSA-04)", () => {
  it("lists everyone with the access the server computed for them", async () => {
    render(<StaffPage />)

    await waitFor(() => {
      expect(screen.getByText("Sam Cook")).toBeInTheDocument()
    })

    // The list call hits the 23-04 endpoint.
    expect(mockedApiClient.get).toHaveBeenCalledWith("/api/v1/staff")

    // Identities are visible...
    const samRow = rowOf("Sam Cook")
    const shownEmail = within(samRow).getByText(EMAIL_SAM)
    expect(shownEmail).toBeInTheDocument()
    expect(screen.getByText("Ada Owner")).toBeInTheDocument()

    // …in the MASKED form the API actually returns. Without this, the fixtures can
    // drift back to `sam@vendor.co.uk` — a response shape that cannot occur — and
    // every case here still passes, because they all read the same constant. This
    // is the assertion that fires on that drift.
    expect(shownEmail.textContent).toMatch(/^[^@]\*\*\*@[^@]+$/)

    // ...and each person's access reads as the server decided it.
    expect(within(rowOf("Ada Owner")).getByText("Group admin · all shops")).toBeInTheDocument()
    expect(within(samRow).getByText("Staff · Peckham Kitchen")).toBeInTheDocument()

    // D-09: the picker is login-populated — a short list must not read as a bug.
    expect(screen.getByText(/signed in once with their own/i)).toBeInTheDocument()
  })

  it("grants a shop-scoped role and refreshes the list", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    fireEvent.change(screen.getByLabelText(/team member/i), {
      target: { value: USER_SAM },
    })
    fireEvent.change(screen.getByLabelText(/^shop$/i), { target: { value: SHOP_B } })
    fireEvent.change(screen.getByLabelText(/^role$/i), {
      target: { value: "SHOP_MANAGER" },
    })

    const newGrant = {
      id: "77777777-7777-7777-7777-777777777777",
      userId: USER_SAM,
      shopId: SHOP_B,
      role: "SHOP_MANAGER",
      grantSource: "OPERATOR",
      createdAt: "2026-07-19T12:00:00Z",
      createdBy: USER_GA,
    }
    mockedApiClient.post.mockResolvedValueOnce({ data: newGrant } as never)
    mockedApiClient.get.mockResolvedValueOnce({
      data: {
        directory,
        grants: [...grants, newGrant],
        people: [
          people[0],
          person(
            directory[1],
            access(USER_SAM, "SHOP_ROLES", {
              perShopRole: { [SHOP_A]: "STAFF", [SHOP_B]: "SHOP_MANAGER" },
            })
          ),
        ],
      },
    } as never)

    fireEvent.click(screen.getByRole("button", { name: /^grant access$/i }))

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/staff/grant", {
        userId: USER_SAM,
        shopId: SHOP_B,
        role: "SHOP_MANAGER",
      })
    })

    // The list refreshed and the new access is visible as the server computed it.
    await waitFor(() => {
      expect(screen.getByText("Shop manager · Brixton Bakery")).toBeInTheDocument()
    })
  })

  it("sends shopId null for a tenant-wide (all shops) grant", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    fireEvent.change(screen.getByLabelText(/team member/i), {
      target: { value: USER_SAM },
    })
    // "All shops" is the default shop option → shopId null.
    fireEvent.change(screen.getByLabelText(/^role$/i), {
      target: { value: "GROUP_ADMIN" },
    })
    fireEvent.click(screen.getByRole("button", { name: /^grant access$/i }))

    await waitFor(() => {
      expect(mockedApiClient.post).toHaveBeenCalledWith("/api/v1/staff/grant", {
        userId: USER_SAM,
        shopId: null,
        role: "GROUP_ADMIN",
      })
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
    expect(screen.queryByText(EMAIL_SAM)).not.toBeInTheDocument()
    expect(screen.queryByText("Sam Cook")).not.toBeInTheDocument()
    expect(
      screen.queryByRole("button", { name: /grant access/i })
    ).not.toBeInTheDocument()
  })

  it("renders the load-error panel, not an empty list, when the list fails to load", async () => {
    mockedApiClient.get.mockRejectedValueOnce(httpError(500))

    render(<StaffPage />)

    const panel = await screen.findByTestId("load-error-panel")
    expect(within(panel).getByText(/couldn't load staff/i)).toBeInTheDocument()
    // No access claim is made about anyone while the list is unknown.
    expect(screen.queryByText("No access")).not.toBeInTheDocument()

    // Retry re-reads the list.
    fireEvent.click(within(panel).getByRole("button", { name: /try again/i }))
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())
  })

  it("warns that a grant belongs to the signed-in user (self-downgrade, D-11)", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    // The GROUP_ADMIN row is the caller — identified by userId (USER_GA), not email.
    expect(within(rowOf("Ada Owner")).getByText(/this is you/i)).toBeInTheDocument()
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
    const jit = { userId: USER_JIT, email: MASKED, displayName: null, lastSeen: null }
    mockedApiClient.get.mockResolvedValue({
      data: {
        directory: [...directory, jit],
        grants,
        people: [...people, person(jit, access(USER_JIT, "NONE"))],
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
    const MASKED = EMAIL_SAM
    mockedApiClient.get.mockResolvedValue({
      data: {
        directory: [directory[1]],
        grants,
        people: [people[1]],
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

  /**
   * #450 item 2 — the grant card used to promise "invite them to log in once",
   * and then (until 37-06) said in so many words that the page could not invite.
   * D-07 (37-07) builds invitations, so that sentence is about to be false; the
   * card now just says how the picker is populated.
   */
  it("does not promise an invite, and no longer denies one either", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    expect(screen.queryByText(/invite them/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/cannot send.*invite/i)).not.toBeInTheDocument()
    expect(screen.getByText(/signed in once with their own/i)).toBeInTheDocument()
  })
})

/**
 * D-09 (37-06) — the People card shows the access the server enforces, and nothing
 * the browser worked out for itself (T-37-12).
 */
describe("People card: server-computed effective access (D-09)", () => {
  const USER_NONE = "44444444-4444-4444-4444-444444444444"
  const USER_BOOT = "55555555-5555-5555-5555-555555555555"
  const USER_REALM = "66666666-6666-6666-6666-666666666666"
  const USER_MULTI = "77777777-7777-7777-7777-777777777777"
  const USER_TW = "12121212-1212-1212-1212-121212121212"
  const USER_SVC = "13131313-1313-1313-1313-131313131313"

  const dirOf = (userId: string, name: string, email: string) => ({
    userId,
    email,
    displayName: name,
    lastSeen: "2026-10-01T10:00:00Z",
  })
  const noneDir = dirOf(USER_NONE, "Nora Nobody", "n***@vendor.co.uk")
  const bootDir = dirOf(USER_BOOT, "Bola First", "b***@vendor.co.uk")
  const realmDir = dirOf(USER_REALM, "Rex Platform", "r***@jtoye.uk")
  const multiDir = dirOf(USER_MULTI, "Mo Twoshops", "m***@vendor.co.uk")
  const twDir = dirOf(USER_TW, "Tia Everywhere", "t***@vendor.co.uk")

  const wide = {
    directory: [...directory, noneDir, bootDir, realmDir, multiDir, twDir],
    grants,
    people: [
      ...people,
      person(noneDir, access(USER_NONE, "NONE")),
      person(bootDir, access(USER_BOOT, "GROUP_ADMIN", { bootstrapAdmin: true })),
      person(
        realmDir,
        access(USER_REALM, "REALM_ADMIN", { realmAdminSeenAt: "2026-10-02T09:00:00Z" })
      ),
      person(
        multiDir,
        access(USER_MULTI, "SHOP_ROLES", {
          perShopRole: { [SHOP_A]: "SHOP_MANAGER", [SHOP_B]: "STAFF" },
        })
      ),
      person(
        twDir,
        access(USER_TW, "SHOP_ROLES", { allShops: true, tenantWideRole: "STAFF" })
      ),
      {
        userId: USER_SVC,
        maskedEmail: null,
        displayName: null,
        lastSeen: null,
        effectiveAccess: access(USER_SVC, "SHOP_ROLES", { perShopRole: { [SHOP_A]: "STAFF" } }),
      },
    ],
  }

  beforeEach(() => {
    mockedApiClient.get.mockResolvedValue({ data: wide } as never)
  })

  it("renders a NONE person as 'No access' with an outline badge", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Nora Nobody")).toBeInTheDocument())

    const badge = within(rowOf("Nora Nobody")).getByText("No access")
    expect(badge.closest("[data-access-badge]")).not.toBeNull()
  })

  it("offers 'Grant access' on a No access row, pre-filling the grant form with that person", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Nora Nobody")).toBeInTheDocument())

    const grantButton = within(rowOf("Nora Nobody")).getByRole("button", {
      name: /grant access/i,
    })
    expect(grantButton).toHaveClass("h-11")
    fireEvent.click(grantButton)

    const picker = screen.getByLabelText(/team member/i) as HTMLSelectElement
    expect(picker.value).toBe(USER_NONE)

    // A row WITH access offers no Grant button — its actions are its removals.
    expect(
      within(rowOf("Sam Cook")).queryByRole("button", { name: /grant access/i })
    ).toBeNull()
  })

  it("renders a bootstrap admin as Group admin with the bootstrap line", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Bola First")).toBeInTheDocument())

    const row = rowOf("Bola First")
    expect(within(row).getByText("Group admin · all shops")).toBeInTheDocument()
    expect(
      within(row).getByText(
        "Kept automatically as the first person to sign in. Grant Group admin to someone to change this."
      )
    ).toBeInTheDocument()
    // The bootstrap line is the bootstrap admin's alone.
    expect(within(rowOf("Ada Owner")).queryByText(/kept automatically/i)).toBeNull()
  })

  it("renders a realm admin as 'Admin account' with its line, never 'No access'", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Rex Platform")).toBeInTheDocument())

    const row = rowOf("Rex Platform")
    expect(within(row).getByText("Admin account")).toBeInTheDocument()
    expect(
      within(row).getByText("Full access from their sign-in account, not from this page.")
    ).toBeInTheDocument()
    expect(within(row).queryByText("No access")).toBeNull()
  })

  it("renders one line per shop, never comma-joined", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Mo Twoshops")).toBeInTheDocument())

    const row = rowOf("Mo Twoshops")
    const a = within(row).getByText("Shop manager · Peckham Kitchen")
    const b = within(row).getByText("Staff · Brixton Bakery")
    expect(a).not.toBe(b)
    expect(a.textContent).not.toContain(",")
  })

  it("renders a tenant-wide role as '{Role} · all shops'", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Tia Everywhere")).toBeInTheDocument())

    expect(within(rowOf("Tia Everywhere")).getByText("Staff · all shops")).toBeInTheDocument()
  })

  it("names a grant holder with no directory row 'Integration account'", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Integration account")).toBeInTheDocument())

    expect(
      within(rowOf("Integration account")).getByText("Staff · Peckham Kitchen")
    ).toBeInTheDocument()
  })

  it("wraps long names and access lines rather than truncating them", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Mo Twoshops")).toBeInTheDocument())

    const row = rowOf("Mo Twoshops")
    for (const el of [
      within(row).getByText("Mo Twoshops"),
      within(row).getByText("m***@vendor.co.uk"),
      within(row).getByText("Shop manager · Peckham Kitchen"),
    ]) {
      expect(el).toHaveClass("[overflow-wrap:anywhere]")
      expect(el).not.toHaveClass("truncate")
    }
  })

  it("shows no automatic-grant badge: the computed access replaces it", async () => {
    const jitGrant = {
      id: "65656565-6565-6565-6565-656565656565",
      userId: USER_NONE,
      shopId: null,
      role: "GROUP_ADMIN",
      grantSource: "JIT",
      createdAt: "2026-07-03T10:00:00Z",
      createdBy: null,
    }
    mockedApiClient.get.mockResolvedValue({
      data: { ...wide, grants: [...grants, jitGrant] },
    } as never)

    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Nora Nobody")).toBeInTheDocument())

    // The de-honoured JIT row does not show as access, and is not flagged either:
    // the person reads exactly what the server enforces.
    expect(screen.queryByText(/granted on first sign-in/i)).toBeNull()
    expect(within(rowOf("Nora Nobody")).getByText("No access")).toBeInTheDocument()
    expect(within(rowOf("Nora Nobody")).queryByText(/group admin/i)).toBeNull()
  })

  /**
   * FAIL ARM (T-37-12). A person whose server value is missing must read as
   * unknown. A page that falls back to the grant rows would print "Staff ·
   * Peckham Kitchen" here — the say≠data defect D-09 exists to end.
   */
  it("renders nothing derived when the server sends no effectiveAccess", async () => {
    const { effectiveAccess: _dropped, ...samWithout } = people[1]
    void _dropped
    mockedApiClient.get.mockResolvedValue({
      data: { directory, grants, people: [people[0], samWithout] },
    } as never)

    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    const row = rowOf("Sam Cook")
    expect(within(row).getByText("Not recorded")).toBeInTheDocument()
    for (const label of [/staff ·/i, /shop manager/i, /group admin/i, /^no access$/i, /admin account/i]) {
      expect(within(row).queryByText(label)).toBeNull()
    }
  })

  it("after removing a person's last grant the row reads 'No access', never Group admin (UXT-003)", async () => {
    mockedApiClient.get.mockResolvedValue({ data: { directory, grants, people } } as never)
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    mockedApiClient.get.mockResolvedValue({
      data: {
        directory,
        grants: [grants[0]],
        people: [people[0], person(directory[1], access(USER_SAM, "NONE"))],
      },
    } as never)

    fireEvent.click(
      within(rowOf("Sam Cook")).getByRole("button", { name: /remove staff at peckham kitchen/i })
    )
    await removeConfirmIfShown()

    await waitFor(() => {
      expect(mockedApiClient.delete).toHaveBeenCalledWith(`/api/v1/staff/${GRANT_SAM}`)
    })
    await waitFor(() => {
      expect(within(rowOf("Sam Cook")).getByText("No access")).toBeInTheDocument()
    })
    expect(within(rowOf("Sam Cook")).queryByText(/group admin/i)).toBeNull()
  })
})

/**
 * Removing access. The last-GROUP_ADMIN refusals keep their copy (P2-KEM-22).
 */
describe("Remove access", () => {
  it("surfaces the last-GROUP_ADMIN 409 as a clear message and keeps the access", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    mockedApiClient.delete.mockRejectedValueOnce(
      httpError(409, "/last-group-admin")
    )

    fireEvent.click(
      within(rowOf("Ada Owner")).getByRole("button", { name: /remove group admin/i })
    )
    await removeConfirmIfShown()

    await waitFor(() => {
      expect(
        screen.getByText(/cannot remove the last group admin/i)
      ).toBeInTheDocument()
    })

    // D-11: the access survives — the 409 is a refusal, not a silent failure.
    expect(within(rowOf("Ada Owner")).getByText("Group admin · all shops")).toBeInTheDocument()
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
    fireEvent.click(screen.getByRole("button", { name: /^grant access$/i }))
    await waitFor(() =>
      expect(
        screen.getByText(/change the last group admin's role/i)
      ).toBeInTheDocument()
    )
    const grantCopy = screen.getByRole("alert").textContent

    // Revoke path 409 → removal wording (distinct).
    mockedApiClient.delete.mockRejectedValueOnce(httpError(409, "/last-group-admin"))
    fireEvent.click(
      within(rowOf("Ada Owner")).getByRole("button", { name: /remove group admin/i })
    )
    await removeConfirmIfShown()
    await waitFor(() =>
      expect(
        screen.getByText(/remove the last group admin/i)
      ).toBeInTheDocument()
    )
    const revokeCopy = screen.getByRole("alert").textContent

    expect(grantCopy).not.toEqual(revokeCopy)
  })
})

/**
 * Remove access is confirmed in a dialog from 37-06 Task 3 on. Until then the
 * click acts directly; this helper presses the dialog's confirm when one is open,
 * so the cases above state the outcome rather than the mechanism.
 */
async function removeConfirmIfShown() {
  const dialog = screen.queryByRole("dialog")
  if (dialog) {
    fireEvent.click(within(dialog).getByRole("button", { name: /^remove access$/i }))
  }
}

/**
 * #454 — CLS. The route measured 0.1805 at the repo's declared throttle profile
 * (390px / Fast-3G / 4x CPU; budget `CLS < 0.1`, webhooks-webperf.spec.ts:37),
 * the worst in the app, because the loading state was a centred 128px spinner
 * that handed over to a ~1200px page.
 *
 * jsdom has no layout, so these cases prove the MECHANISM — that the loading
 * state is the page's own shape rather than a spinner. The geometry itself is
 * asserted in e2e/dashboard-interface-corrections.spec.ts, which measures CLS in
 * a real browser at that profile.
 */
describe("staff loading state (#454)", () => {
  /** Hold both fetches open so the loading branch is what renders. */
  function renderLoading() {
    mockedApiClient.get.mockImplementation((() => new Promise(() => {})) as never)
    mockedFetchMyShops.mockImplementation((() => new Promise(() => {})) as never)
    return render(<StaffPage />)
  }

  it("renders a content-shaped skeleton, not a bare spinner", () => {
    const { container } = renderLoading()

    const loading = screen.getByTestId("staff-loading")
    expect(loading).toHaveAttribute("aria-busy", "true")
    // The spinner this replaced. Its absence is the fix.
    expect(container.querySelector(".animate-spin")).toBeNull()
    // The same vertical rhythm as the loaded page, so the swap moves nothing.
    expect(loading).toHaveClass("space-y-6")
    // Two cards (Grant access, People), in the same order as the loaded page.
    expect(container.querySelectorAll(".rounded-lg.border")).toHaveLength(2)
  })

  it("renders the static chrome for real, and bars only where data is unknown", () => {
    renderLoading()

    // Static: heading, subtitle, both card titles, both descriptions and the
    // field labels are known before the fetch, so withholding them would buy
    // nothing and cost a shift when they arrive.
    expect(
      screen.getByRole("heading", { name: /staff & access/i, level: 1 })
    ).toBeInTheDocument()
    expect(screen.getByText("Who can work on which shop")).toBeInTheDocument()
    expect(screen.getByText("Grant access")).toBeInTheDocument()
    expect(screen.getByText("People")).toBeInTheDocument()
    expect(screen.getByText(/signed in once with their own/i)).toBeInTheDocument()
    expect(screen.getByText(/up to 5 minutes/i)).toBeInTheDocument()
    for (const label of ["Team member", "Shop", "Role"]) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0)
    }
    // The People table keeps its real column headers, so the table box is the
    // same width and height it will be when rows arrive.
    for (const head of ["Person", "Access", "Actions"]) {
      expect(screen.getAllByText(head).length).toBeGreaterThan(0)
    }

    // Unknown: no <select> is rendered, and no pressable "Grant access" button —
    // a real one here would look actionable and do nothing.
    expect(screen.queryAllByRole("combobox")).toHaveLength(0)
    expect(screen.queryByRole("button", { name: /grant access/i })).toBeNull()
  })
})

/**
 * Phase 35 / UIX-08 — the staff screen's width tier, declared rather than
 * inherited.
 *
 * PATTERNS A-7 resolved this surface to the Index tier. The grant form is inside
 * a Card that is already narrower than the band and keeps its own width, so
 * tiering the whole page to the reading width would cap the People table and buy
 * the form nothing.
 *
 * Every render branch is asserted — loaded, skeleton, access-denied and load
 * error — because a page that declares its tier only on one branch has undeclared
 * branches, which is exactly the state ORCH-03's marker exists to make visible.
 */
describe("staff width tier (UIX-08)", () => {
  it("declares the index width tier, with no cap of its own, on the loaded root band", async () => {
    const { container } = render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    const root = container.firstElementChild as HTMLElement
    expect(root).toHaveAttribute("data-width-tier", "index")
    expect(capTokens(root)).toEqual([])

    // Non-vacuity control: the same filter over a real cap from the vocabulary
    // must find it, so the empty result above is about the page.
    const probe = document.createElement("div")
    probe.className = `mx-auto ${WIDTH_TIER_CLASS.detail}`
    expect(capTokens(probe)).toEqual([WIDTH_TIER_CLASS.detail])
  })

  it("declares the same tier on the skeleton branch, so the first paint is not undeclared", () => {
    mockedApiClient.get.mockImplementation((() => new Promise(() => {})) as never)
    mockedFetchMyShops.mockImplementation((() => new Promise(() => {})) as never)

    const { container } = render(<StaffPage />)

    expect(screen.getByTestId("staff-loading")).toBeInTheDocument()
    expect(container.firstElementChild).toHaveAttribute("data-width-tier", "index")
  })

  it("declares the same tier on the access-denied branch", async () => {
    mockedApiClient.get.mockRejectedValueOnce(
      httpError(403, "/shop-access-denied")
    )

    const { container } = render(<StaffPage />)
    await waitFor(() =>
      expect(screen.getByText(/group admin access required/i)).toBeInTheDocument()
    )

    expect(container.firstElementChild).toHaveAttribute("data-width-tier", "index")
  })

  it("declares the same tier on the load-error branch", async () => {
    mockedApiClient.get.mockRejectedValueOnce(httpError(500))

    const { container } = render(<StaffPage />)
    await screen.findByTestId("load-error-panel")

    expect(container.firstElementChild).toHaveAttribute("data-width-tier", "index")
  })
})
