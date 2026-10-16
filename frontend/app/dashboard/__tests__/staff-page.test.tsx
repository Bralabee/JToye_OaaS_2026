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
import { act, render, screen, waitFor, fireEvent, within } from "@testing-library/react"
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

/**
 * The grant form's card. 37-09 added an invite form with its own "Role" and "Shop"
 * fields above it, so a page-wide label query would now match two controls.
 */
function grantCard(): HTMLElement {
  const card = screen.getByLabelText(/team member/i).closest(".rounded-lg")
  expect(card).not.toBeNull()
  return card as HTMLElement
}

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
    fireEvent.change(within(grantCard()).getByLabelText(/^shop$/i), { target: { value: SHOP_B } })
    fireEvent.change(within(grantCard()).getByLabelText(/^role$/i), {
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
    fireEvent.change(within(grantCard()).getByLabelText(/^role$/i), {
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
 * Remove access is confirmed in a destructive dialog (UI-SPEC § B1, 37-06 Task 3).
 * The dialog must be open; this presses its confirm.
 */
async function removeConfirmIfShown() {
  const dialog = await screen.findByRole("dialog")
  fireEvent.click(within(dialog).getByRole("button", { name: /^remove access$/i }))
}

describe("Remove access confirm names the consequence (UI-SPEC § B1)", () => {
  const USER_MULTI = "77777777-7777-7777-7777-777777777777"
  const multiDir = {
    userId: USER_MULTI,
    email: "m***@vendor.co.uk",
    displayName: "Mo Twoshops",
    lastSeen: "2026-10-01T10:00:00Z",
  }
  const GRANT_MULTI_A = "70707070-7070-7070-7070-707070707070"
  const GRANT_MULTI_B = "71717171-7171-7171-7171-717171717171"
  const multiGrants = [
    {
      id: GRANT_MULTI_A,
      userId: USER_MULTI,
      shopId: SHOP_A,
      role: "SHOP_MANAGER",
      grantSource: "OPERATOR",
      createdAt: "2026-07-05T10:00:00Z",
      createdBy: USER_GA,
    },
    {
      id: GRANT_MULTI_B,
      userId: USER_MULTI,
      shopId: SHOP_B,
      role: "STAFF",
      grantSource: "OPERATOR",
      createdAt: "2026-07-05T10:00:00Z",
      createdBy: USER_GA,
    },
  ]

  beforeEach(() => {
    mockedApiClient.get.mockResolvedValue({
      data: {
        directory: [...directory, multiDir],
        grants: [...grants, ...multiGrants],
        people: [
          ...people,
          person(
            multiDir,
            access(USER_MULTI, "SHOP_ROLES", {
              perShopRole: { [SHOP_A]: "SHOP_MANAGER", [SHOP_B]: "STAFF" },
            })
          ),
        ],
      },
    } as never)
  })

  it("for a last grant, says they will have no access, and keeps access on 'Keep access'", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    fireEvent.click(
      within(rowOf("Sam Cook")).getByRole("button", { name: /remove staff at peckham kitchen/i })
    )

    const dialog = await screen.findByRole("dialog")
    expect(
      within(dialog).getByText("Remove Staff at Peckham Kitchen for Sam Cook?")
    ).toBeInTheDocument()
    expect(dialog).toHaveTextContent("After this they'll have no access to this business.")
    expect(dialog).toHaveTextContent("They'll lose it on their next action.")
    expect(within(dialog).getByRole("button", { name: /^remove access$/i })).toHaveClass(
      "bg-destructive"
    )

    fireEvent.click(within(dialog).getByRole("button", { name: "Keep access" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(mockedApiClient.delete).not.toHaveBeenCalled()
  })

  it("for one of several grants, names the access that remains", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Mo Twoshops")).toBeInTheDocument())

    fireEvent.click(
      within(rowOf("Mo Twoshops")).getByRole("button", {
        name: /remove shop manager at peckham kitchen/i,
      })
    )

    const dialog = await screen.findByRole("dialog")
    expect(dialog).toHaveTextContent("After this they'll have: Staff · Brixton Bakery.")
    expect(dialog).not.toHaveTextContent(/no access to this business/i)

    fireEvent.click(within(dialog).getByRole("button", { name: /^remove access$/i }))
    await waitFor(() =>
      expect(mockedApiClient.delete).toHaveBeenCalledWith(`/api/v1/staff/${GRANT_MULTI_A}`)
    )
  })
})

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
    // Three cards (Invite someone, Grant access, People), in the same order as the
    // loaded page (37-09 put the invite card first).
    expect(container.querySelectorAll(".rounded-lg.border")).toHaveLength(3)
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

// --- 37-46: credential service accounts read as integrations ----------------------------------------

describe("Staff page: API credentials are integrations, not people (37-46, UI-SPEC § B1 rows)", () => {
  const SA_TILL = "5a5a5a5a-0000-0000-0000-000000000001"
  const SA_UNKNOWN = "5a5a5a5a-0000-0000-0000-000000000002"
  const GRANT_TILL = "77777777-0000-0000-0000-000000000001"
  const GRANT_UNKNOWN = "77777777-0000-0000-0000-000000000002"

  /** A grant holder with no directory row: how a credential's service account arrives (37-05). */
  const integration = (userId: string) => ({
    userId,
    maskedEmail: null,
    displayName: null,
    lastSeen: null,
    effectiveAccess: access(userId, "SHOP_ROLES", { perShopRole: { [SHOP_A]: "STAFF" } }),
  })
  const integrationGrant = (id: string, userId: string) => ({
    id,
    userId,
    shopId: SHOP_A,
    role: "STAFF",
    grantSource: "OPERATOR",
    createdAt: "2026-10-01T09:00:00Z",
    createdBy: USER_GA,
  })

  const staffWithIntegrations = {
    directory,
    grants: [...grants, integrationGrant(GRANT_TILL, SA_TILL), integrationGrant(GRANT_UNKNOWN, SA_UNKNOWN)],
    people: [...people, integration(SA_TILL), integration(SA_UNKNOWN)],
  }
  const credentialList = {
    credentials: [
      {
        id: "c0c0c0c0-0000-0000-0000-000000000001",
        name: "Till system",
        clientId: "jtoye-int-till-system-7f3a",
        scopes: ["orders:read"],
        shopId: SHOP_A,
        role: "STAFF",
        status: "ACTIVE",
        createdBy: USER_GA,
        createdAt: "2026-10-01T09:00:00Z",
        lastUsedAt: null,
        serviceAccountUserId: SA_TILL,
      },
    ],
    lastUsedIntervalMinutes: 15,
  }

  beforeEach(() => {
    mockedApiClient.get.mockImplementation(((url: string) => {
      if (url === "/api/v1/staff") return Promise.resolve({ data: staffWithIntegrations })
      if (url === "/api/v1/staff/invites") return Promise.resolve({ data: [] })
      if (url === "/api/v1/credentials") return Promise.resolve({ data: credentialList })
      return Promise.reject(new Error(`unexpected GET ${url}`))
    }) as never)
  })

  it("names a credential's service account 'Integration: {credential name}'; an unknown one stays 'Integration account'", async () => {
    render(<StaffPage />)
    expect(await screen.findByText("Integration: Till system")).toBeInTheDocument()
    expect(mockedApiClient.get).toHaveBeenCalledWith("/api/v1/credentials")

    expect(
      within(rowOf("Integration: Till system")).getByText("Staff · Peckham Kitchen")
    ).toBeInTheDocument()
    expect(within(rowOf("Integration account")).getByText("Staff · Peckham Kitchen")).toBeInTheDocument()
  })

  it("offers no Remove access on a credential's row (it would leave the credential working with no access) and points to Developers", async () => {
    render(<StaffPage />)
    await screen.findByText("Integration: Till system")
    const row = rowOf("Integration: Till system")

    expect(within(row).queryByRole("button", { name: /remove/i })).toBeNull()
    expect(within(row).queryByRole("button", { name: /grant access/i })).toBeNull()
    const manage = within(row).getByRole("link", { name: /Developers page/ })
    expect(manage).toHaveAttribute("href", "/dashboard/developers")

    // Nor can it be picked as a grant target: the server refuses that anyway (409).
    const picker = screen.getByLabelText(/team member/i) as HTMLSelectElement
    expect(Array.from(picker.options).map((o) => o.value)).not.toContain(SA_TILL)

    // Control: a person's row on the same page still offers Remove access.
    expect(within(rowOf("Sam Cook")).getByRole("button", { name: /remove/i })).toBeInTheDocument()
  })

  it("explains the server's staff-target-is-credential 409 instead of the last-group-admin copy", async () => {
    mockedApiClient.delete.mockRejectedValueOnce(httpError(409, "https://jtoye.uk/errors/staff-target-is-credential") as never)
    render(<StaffPage />)
    await screen.findByText("Integration: Till system")

    // The credential list did not know this one, so its row still offers removal; the server refuses.
    fireEvent.click(within(rowOf("Integration account")).getByRole("button", { name: /remove/i }))
    const dialog = await screen.findByRole("dialog")
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Remove access" }))
    })

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(
      "This is an API credential, not a person, so its access can't be changed here. Delete it on the Developers page and create a new one."
    )
    expect(alert).not.toHaveTextContent(/last group admin/i)
  })

  // 37-E review: revoking deletes the service account's grant but not its directory row, so a
  // revoked credential used to stay listed as "Integration: {name} · No access" for ever, a row on
  // which nothing can be done. The Developers page lists only working credentials, and the server
  // keeps the record of who revoked it and when.
  describe("a revoked credential", () => {
    const SA_OLD = "5a5a5a5a-0000-0000-0000-000000000003"
    const revoked = {
      ...credentialList.credentials[0],
      id: "c0c0c0c0-0000-0000-0000-000000000003",
      name: "Old till",
      clientId: "jtoye-int-old-till-1b2c",
      status: "REVOKED",
      revokedBy: USER_GA,
      revokedAt: "2026-10-12T09:00:00Z",
      serviceAccountUserId: SA_OLD,
    }
    const revokedPerson = {
      userId: SA_OLD,
      maskedEmail: null,
      displayName: "service-account-jtoye-int-old-till-1b2c",
      lastSeen: "2026-10-12T08:55:00Z",
      effectiveAccess: access(SA_OLD, "NONE"),
    }
    const serve = (staff: unknown, credentials: unknown) =>
      mockedApiClient.get.mockImplementation(((url: string) => {
        if (url === "/api/v1/staff") return Promise.resolve({ data: staff })
        if (url === "/api/v1/staff/invites") return Promise.resolve({ data: [] })
        if (url === "/api/v1/credentials") return credentials
        return Promise.reject(new Error(`unexpected GET ${url}`))
      }) as never)

    it("is not listed once its grant is gone; an active credential and the people still are", async () => {
      serve(
        { ...staffWithIntegrations, people: [...staffWithIntegrations.people, revokedPerson] },
        Promise.resolve({ data: { ...credentialList, credentials: [...credentialList.credentials, revoked] } })
      )
      render(<StaffPage />)
      expect(await screen.findByText("Integration: Till system")).toBeInTheDocument()
      expect(screen.getByText("Sam Cook")).toBeInTheDocument()
      expect(screen.queryByText("Integration: Old till")).toBeNull()
      expect(screen.queryByText(revokedPerson.displayName)).toBeNull()
    })

    it("stays listed while the server still reports a grant for it, so no access is ever hidden", async () => {
      const lingering = { ...integrationGrant("77777777-0000-0000-0000-000000000003", SA_OLD) }
      serve(
        {
          ...staffWithIntegrations,
          grants: [...staffWithIntegrations.grants, lingering],
          people: [...staffWithIntegrations.people, integration(SA_OLD)],
        },
        Promise.resolve({ data: { ...credentialList, credentials: [...credentialList.credentials, revoked] } })
      )
      render(<StaffPage />)
      expect(await screen.findByText("Integration: Old till")).toBeInTheDocument()
    })

    it("is listed as before when the credential list cannot be read: nothing is hidden on a guess", async () => {
      serve(
        { ...staffWithIntegrations, people: [...staffWithIntegrations.people, revokedPerson] },
        Promise.reject(new Error("credentials down"))
      )
      render(<StaffPage />)
      expect(await screen.findByText(revokedPerson.displayName)).toBeInTheDocument()
    })
  })

  it("does not ask for credentials when the caller is not a Group admin", async () => {
    mockedFetchMyShops.mockResolvedValue({ shops: shops as never, isGroupAdmin: false, userId: USER_SAM } as never)
    render(<StaffPage />)
    await screen.findByText("Sam Cook")
    expect(mockedApiClient.get).not.toHaveBeenCalledWith("/api/v1/credentials")
    // Without the list nothing is named, and nothing is guessed.
    expect(screen.queryByText("Integration: Till system")).toBeNull()
    expect(screen.getAllByText("Integration account")).toHaveLength(2)
  })
})

/*
 * G-37-15 (37-54, D-09): a service account has no email (`DirectoryEntryDto.maskEmail`
 * passes a null through), so the picker printed "integration-orders-rw (null)" in the
 * 2026-10-15 UAT. The label says only what the data holds.
 */
describe("Grant picker labels say only what the directory holds (G-37-15)", () => {
  it("labels a no-email entry by its name, a nameless one as 'Integration account', and never prints null", async () => {
    const SA_NAMED = "44444444-4444-4444-4444-444444444444"
    const SA_BARE = "55555555-5555-5555-5555-555555555555"
    const named = { userId: SA_NAMED, email: null, displayName: "integration-orders-rw", lastSeen: null }
    const bare = { userId: SA_BARE, email: null, displayName: null, lastSeen: null }
    mockedApiClient.get.mockResolvedValue({
      data: {
        directory: [...directory, named, bare],
        grants,
        people: [
          ...people,
          person(named, access(SA_NAMED, "NONE")),
          person(bare, access(SA_BARE, "NONE")),
        ],
      },
    } as never)

    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    const picker = screen.getByLabelText(/team member/i) as HTMLSelectElement
    const label = (userId: string) =>
      Array.from(picker.options).find((o) => o.value === userId)?.textContent
    expect(label(SA_NAMED)).toBe("integration-orders-rw")
    expect(label(SA_BARE)).toBe("Integration account")
    for (const o of Array.from(picker.options)) {
      expect(o.textContent).not.toMatch(/null|undefined|\(\)/)
    }
    // #290 still holds beside it: a named member is "Name (masked email)".
    expect(label(USER_SAM)).toBe(`Sam Cook (${EMAIL_SAM})`)
  })
})

/*
 * G-37-13 (37-54): in the dark theme the five Staff-page selects were `bg-white` with no
 * text colour, so they inherited the dark theme's near-white foreground: about 1.0:1.
 * Their labels were slate-700 on the dark card, about 1.9:1.
 *
 * jsdom applies no stylesheet, so this resolves each element's colour from its CLASS
 * TOKENS the way Tailwind would: palette names from `tailwindcss/colors`, the theme
 * names (foreground, background, card) from app/globals.css `:root` / `.dark`. The
 * live computed-style measure is 37-55's.
 */
// eslint-disable-next-line @typescript-eslint/no-require-imports
const fsForTokens = require("fs") as typeof import("fs")
// eslint-disable-next-line @typescript-eslint/no-require-imports
const pathForTokens = require("path") as typeof import("path")
// eslint-disable-next-line @typescript-eslint/no-require-imports
const PALETTE = require("tailwindcss/colors") as Record<string, unknown>

type Rgb = [number, number, number]
type Theme = "light" | "dark"

const GLOBALS_CSS = fsForTokens.readFileSync(
  pathForTokens.resolve(__dirname, "..", "..", "..", "app", "globals.css"),
  "utf8"
)

function hslToRgbT(h: number, s: number, l: number): Rgb {
  const c = (1 - Math.abs(2 * l - 1)) * s
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1))
  const m = l - c / 2
  const [r, g, b] =
    h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x]
  return [(r + m) * 255, (g + m) * 255, (b + m) * 255]
}

function hexToRgbT(hex: string): Rgb {
  let h = hex.replace("#", "")
  if (h.length === 3) h = h.split("").map((c) => c + c).join("")
  return [0, 1, 2].map((i) => parseInt(h.substr(i * 2, 2), 16)) as Rgb
}

function luminanceT([r, g, b]: Rgb): number {
  const [R, G, B] = [r, g, b]
    .map((v) => v / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)))
  return 0.2126 * R + 0.7152 * G + 0.0722 * B
}

function contrast(a: Rgb, b: Rgb): number {
  const [hi, lo] = [luminanceT(a), luminanceT(b)].sort((x, y) => y - x)
  return Math.round(((hi + 0.05) / (lo + 0.05)) * 100) / 100
}

/** A theme variable out of the `:root` or `.dark` block (scoped: most are declared twice). */
function themeVar(name: string, theme: Theme): Rgb {
  const scope = theme === "dark" ? ".dark" : ":root"
  const start = GLOBALS_CSS.indexOf(`${scope} {`)
  if (start === -1) throw new Error(`VOID: no ${scope} block in globals.css`)
  const end = GLOBALS_CSS.indexOf("\n  }", start)
  const m = GLOBALS_CSS.slice(start, end).match(new RegExp(`--${name}:\\s*([^;]+);`))
  if (!m) throw new Error(`VOID: --${name} not found in ${scope}`)
  const parts = m[1].trim().match(/([\d.]+)\s+([\d.]+)%\s+([\d.]+)%/)
  if (!parts) throw new Error(`VOID: --${name} is not an HSL triple`)
  return hslToRgbT(Number(parts[1]), Number(parts[2]) / 100, Number(parts[3]) / 100)
}

const THEME_NAMES = ["foreground", "background", "card", "card-foreground"]

/** A colour token's value, or null for anything that is not a colour (text-sm, text-xs…). */
function colourOf(name: string, theme: Theme): Rgb | null {
  if (name === "white") return [255, 255, 255]
  if (name === "black") return [0, 0, 0]
  if (THEME_NAMES.includes(name)) return themeVar(name, theme)
  const m = name.match(/^([a-z]+)-(\d{2,3})$/)
  if (!m) return null
  const family = PALETTE[m[1]] as Record<string, string> | undefined
  const hex = family && typeof family === "object" ? family[m[2]] : undefined
  return typeof hex === "string" ? hexToRgbT(hex) : null
}

/** The colour one utility (`text` or `bg`) resolves to, by the variant precedence Tailwind's CSS order gives. */
function pickToken(classes: string[], kind: "text" | "bg", theme: Theme, disabled: boolean): Rgb | null {
  const levels: string[] = []
  if (theme === "dark" && disabled) levels.push("dark:disabled:")
  if (theme === "dark") levels.push("dark:")
  if (disabled) levels.push("disabled:")
  levels.push("")
  for (const variant of levels) {
    for (const c of classes) {
      if (!c.startsWith(`${variant}${kind}-`)) continue
      const colour = colourOf(c.slice(`${variant}${kind}-`.length), theme)
      if (colour) return colour
    }
  }
  return null
}

/** Text and background of an element that sits inside a Card (no token = the card's own pair). */
function resolveColours(classes: string[], theme: Theme, disabled = false): { text: Rgb; bg: Rgb } {
  return {
    text: pickToken(classes, "text", theme, disabled) ?? themeVar("card-foreground", theme),
    bg: pickToken(classes, "bg", theme, disabled) ?? themeVar("card", theme),
  }
}

const SELECT_IDS = ["invite-role", "invite-shop", "staff-user", "staff-shop", "staff-role"]

/** Every measured element after load, Group admin chosen (hint shown) and an empty email sent (error shown). */
async function renderMeasuredStaffForms(): Promise<{ name: string; el: HTMLElement }[]> {
  render(<StaffPage />)
  await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())
  fireEvent.change(document.getElementById("invite-role")!, { target: { value: "GROUP_ADMIN" } })
  fireEvent.click(screen.getByRole("button", { name: "Send invitation" }))
  await screen.findByText("Enter an email address.")

  const selects = Array.from(document.querySelectorAll("select"))
  expect(selects.map((s) => s.id).sort()).toEqual([...SELECT_IDS].sort())
  expect((document.getElementById("invite-shop") as HTMLSelectElement).disabled).toBe(true)

  const measured: { name: string; el: HTMLElement }[] = []
  for (const id of SELECT_IDS) {
    measured.push({ name: `select#${id}`, el: document.getElementById(id)! })
    const lbl = document.querySelector(`label[for="${id}"]`) as HTMLElement | null
    expect(lbl).not.toBeNull()
    measured.push({ name: `label[for=${id}]`, el: lbl! })
  }
  measured.push({ name: "group-admin hint", el: screen.getByText("Group admin always covers every shop.") })
  measured.push({ name: "grant hint", el: screen.getByText(/Granting the same access twice is safe/) })
  measured.push({ name: "email error", el: screen.getByText("Enter an email address.") })
  return measured
}

function belowAA(measured: { name: string; el: HTMLElement }[], theme: Theme): string[] {
  return measured.flatMap(({ name, el }) => {
    const { text, bg } = resolveColours(
      Array.from(el.classList),
      theme,
      (el as HTMLSelectElement).disabled === true
    )
    const r = contrast(text, bg)
    return r >= 4.5 ? [] : [`${name}: ${r}:1`]
  })
}

describe("Staff-page form controls are readable in both themes (G-37-13)", () => {
  it("brings every select, its label, the hints and the email error to 4.5:1 in the dark theme", async () => {
    const measured = await renderMeasuredStaffForms()
    expect(belowAA(measured, "dark")).toEqual([])
  })

  it("keeps every select, its label, the hints and the email error at 4.5:1 in the light theme", async () => {
    const measured = await renderMeasuredStaffForms()
    expect(belowAA(measured, "light")).toEqual([])
  })

  it("keeps today's light-theme colours on the two form cards", async () => {
    const measured = await renderMeasuredStaffForms()
    const hex = (rgb: Rgb) => rgb.map((v) => Math.round(v)).join(",")
    const colours = Object.fromEntries(
      measured.map(({ name, el }) => {
        const { text, bg } = resolveColours(
          Array.from(el.classList),
          "light",
          (el as HTMLSelectElement).disabled === true
        )
        return [name, `${hex(text)} on ${hex(bg)}`]
      })
    )
    const FG = hex(themeVar("foreground", "light"))
    const CARD = hex(themeVar("card", "light"))
    const tw = (name: string) => hex(colourOf(name, "light")!)
    expect(colours).toEqual({
      "select#invite-role": `${FG} on 255,255,255`,
      "label[for=invite-role]": `${tw("slate-700")} on ${CARD}`,
      "select#invite-shop": `${tw("slate-700")} on ${tw("slate-100")}`,
      "label[for=invite-shop]": `${tw("slate-700")} on ${CARD}`,
      "select#staff-user": `${FG} on 255,255,255`,
      "label[for=staff-user]": `${tw("slate-700")} on ${CARD}`,
      "select#staff-shop": `${FG} on 255,255,255`,
      "label[for=staff-shop]": `${tw("slate-700")} on ${CARD}`,
      "select#staff-role": `${FG} on 255,255,255`,
      "label[for=staff-role]": `${tw("slate-700")} on ${CARD}`,
      "group-admin hint": `${tw("slate-600")} on ${CARD}`,
      "grant hint": `${tw("slate-500")} on ${CARD}`,
      "email error": `${tw("red-700")} on ${CARD}`,
    })
  })

  it("instrument arm: the pre-fix select and label tokens measure below 4.5:1 in the dark theme", () => {
    const PRE_FIX_SELECT =
      "h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-sm disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-700"
    const select = resolveColours(PRE_FIX_SELECT.split(" "), "dark")
    expect(contrast(select.text, select.bg)).toBeLessThan(4.5)
    const label = resolveColours("text-sm font-medium text-slate-700".split(" "), "dark")
    expect(contrast(label.text, label.bg)).toBeLessThan(4.5)
    // ...and a size token is not read as a colour.
    expect(colourOf("sm", "dark")).toBeNull()
  })
})
