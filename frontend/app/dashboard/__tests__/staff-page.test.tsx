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
    const shownEmail = screen.getByText(EMAIL_SAM)
    expect(shownEmail).toBeInTheDocument()
    expect(screen.getByText("Ada Owner")).toBeInTheDocument()

    // …in the MASKED form the API actually returns. Without this, the fixtures can
    // drift back to `sam@vendor.co.uk` — a response shape that cannot occur — and
    // every case here still passes, because they all read the same constant. This
    // is the assertion that fires on that drift.
    expect(shownEmail.textContent).toMatch(/^[^@]\*\*\*@[^@]+$/)

    // ...and the grants resolve to human-readable shop + role.
    expect(screen.getAllByText("Peckham Kitchen").length).toBeGreaterThan(0)
    expect(screen.getByText("Group admin")).toBeInTheDocument()
    expect(screen.getByText("Staff")).toBeInTheDocument()

    // D-09: the directory is login-populated — a short list must not read as a
    // bug. The wording changed in #450 item 2 (the old sentence promised an
    // invite that does not exist); the property being asserted has not.
    expect(
      screen.getByText(/appear here only after they have signed in/i)
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

    // The row's accessible name is the MASKED address (`Revoke ${email}` over a
    // DirectoryEntryDto), so this locator only resolves against a faithful fixture.
    fireEvent.click(screen.getByRole("button", { name: `Revoke ${EMAIL_SAM}` }))

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
    expect(screen.queryByText(EMAIL_SAM)).not.toBeInTheDocument()
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

    fireEvent.click(screen.getByRole("button", { name: `Revoke ${EMAIL_GA}` }))

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
    fireEvent.click(screen.getByRole("button", { name: `Revoke ${EMAIL_GA}` }))
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
    const MASKED = EMAIL_SAM
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

  /**
   * #450 item 2 — the grant card used to promise "invite them to log in once".
   * There is no invite anywhere in the product: `user_directory` is populated on
   * first sign-in, and the picker can only offer people already in it. The copy
   * has to describe that, and must not describe a control that does not exist.
   */
  it("does not promise an invite it cannot send", async () => {
    render(<StaffPage />)
    await waitFor(() => expect(screen.getByText("Sam Cook")).toBeInTheDocument())

    // The word the pre-fix copy used, in the sense it used it.
    expect(screen.queryByText(/invite them/i)).not.toBeInTheDocument()

    // …and there is genuinely no invite control to justify such copy. Scoped to
    // buttons/links, so the sentence that DENIES an invite cannot satisfy this.
    const invitish = [
      ...screen.queryAllByRole("button", { name: /invite/i }),
      ...screen.queryAllByRole("link", { name: /invite/i }),
    ]
    expect(invitish).toHaveLength(0)

    // What it says instead: sign-in first, and this page cannot invite.
    expect(screen.getByText(/signed in once with their own/i)).toBeInTheDocument()
    expect(screen.getByText(/cannot send them an invite/i)).toBeInTheDocument()
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
    // Three cards, in the same order as the loaded page.
    expect(container.querySelectorAll(".rounded-lg.border")).toHaveLength(3)
  })

  it("renders the static chrome for real, and bars only where data is unknown", () => {
    renderLoading()

    // Static: heading, subtitle, all three card titles, both descriptions and
    // the field labels are known before the fetch, so withholding them would buy
    // nothing and cost a shift when they arrive.
    expect(
      screen.getByRole("heading", { name: /staff & access/i, level: 1 })
    ).toBeInTheDocument()
    expect(screen.getByText("Who can work on which shop")).toBeInTheDocument()
    expect(screen.getByText("Grant access")).toBeInTheDocument()
    expect(screen.getByText("Team directory")).toBeInTheDocument()
    expect(screen.getByText("Current access")).toBeInTheDocument()
    expect(screen.getByText(/cannot send them an invite/i)).toBeInTheDocument()
    expect(screen.getByText(/up to 5 minutes/i)).toBeInTheDocument()
    // "Shop" and "Role" are also table column headers, hence getAllByText.
    for (const label of ["Team member", "Shop", "Role"]) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0)
    }
    // The grants table keeps its real column headers, so the table box is the
    // same width and height it will be when rows arrive.
    for (const head of ["Person", "Shop", "Role", "Actions"]) {
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
 * tiering the whole page to the reading width would cap the directory and grants
 * tables and buy the form nothing.
 *
 * All THREE render branches are asserted — loaded, skeleton and access-denied —
 * because a page that declares its tier only on one branch has undeclared
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
})
