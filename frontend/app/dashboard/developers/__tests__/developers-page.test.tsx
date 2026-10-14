/**
 * The Developers page (37-46, UI-SPEC § E1; D-22, D-23, D-24; RWO-035 / UXT-035 UI half).
 *
 * A Group admin issues an API credential for a till, a website or an agent instead of sharing a
 * password, and sees its secret exactly once. What these cases prove, through the page as a vendor
 * uses it (the API is mocked at `apiClient`, so every request the page makes is observed):
 *
 * - Create sends exactly what was chosen (name, scopes, role, shop) to POST /api/v1/credentials
 *   with an Idempotency-Key, and opens the show-once dialog with the client ID, the secret and the
 *   token endpoint.
 * - The show-once dialog survives Esc and a click outside; after "I've saved it" the secret is in
 *   neither the DOM nor localStorage nor sessionStorage (F13, T-37-112).
 * - With no scope ticked the form refuses inline and sends nothing; the role picker offers Staff
 *   and Shop manager only (Q7: no credential can mint credentials).
 */
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import DevelopersPage from "../page"
import apiClient from "@/lib/api-client"
import { fetchMyShops } from "@/lib/shops-api"
import { formatUkDate, formatUkDateTime } from "@/lib/uk-datetime"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

jest.mock("@/lib/shops-api", () => ({ fetchMyShops: jest.fn() }))
const mockedFetchMyShops = fetchMyShops as jest.MockedFunction<typeof fetchMyShops>

const toast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({ useToast: () => ({ toast }) }))

const SHOP_A = "aaaaaaaa-1111-1111-1111-111111111111"
const SHOP_B = "bbbbbbbb-2222-2222-2222-222222222222"
const ADMIN = "11111111-1111-1111-1111-111111111111"
const CREDENTIAL_ID = "c0c0c0c0-0000-0000-0000-000000000001"

const SECRET = "Zx9-only-once-secret-for-the-page-test-77"
const CLIENT_ID = "jtoye-int-till-system-7f3a"
const TOKEN_ENDPOINT = "https://id.example.test/realms/jtoye/protocol/openid-connect/token"

const shops = [
  { id: SHOP_A, name: "Peckham Kitchen", published: true },
  { id: SHOP_B, name: "Brixton Bakery", published: true },
]

/** The list the page reads on mount: empty, so the create flow starts from the empty state. */
let listBody: { credentials: unknown[]; lastUsedIntervalMinutes: number }

function staffBody() {
  return {
    directory: [{ userId: ADMIN, email: "a***@vendor.co.uk", displayName: "Ada Owner", lastSeen: null }],
    grants: [],
    people: [],
  }
}

function created(overrides: Record<string, unknown> = {}) {
  return {
    id: CREDENTIAL_ID,
    name: "Till system",
    clientId: CLIENT_ID,
    clientSecret: SECRET,
    secretShown: true,
    tokenEndpoint: TOKEN_ENDPOINT,
    scopes: ["catalog:read", "orders:write"],
    shopId: null,
    role: "SHOP_MANAGER",
    status: "ACTIVE",
    createdBy: ADMIN,
    createdAt: "2026-10-13T09:00:00Z",
    rotatedBy: null,
    rotatedAt: null,
    ...overrides,
  }
}

beforeEach(() => {
  jest.clearAllMocks()
  window.localStorage.clear()
  window.sessionStorage.clear()
  listBody = { credentials: [], lastUsedIntervalMinutes: 15 }
  mockedFetchMyShops.mockResolvedValue({
    shops: shops as never,
    isGroupAdmin: true,
    userId: ADMIN,
  } as never)
  mockedApiClient.get.mockImplementation(((url: string) => {
    if (url === "/api/v1/credentials") return Promise.resolve({ data: listBody })
    if (url === "/api/v1/staff") return Promise.resolve({ data: staffBody() })
    return Promise.reject(new Error(`unexpected GET ${url}`))
  }) as never)
  mockedApiClient.post.mockImplementation(((url: string) => {
    if (url === "/api/v1/credentials") return Promise.resolve({ status: 201, data: created() })
    return Promise.reject(new Error(`unexpected POST ${url}`))
  }) as never)
})

/**
 * Open the create dialog from the page header. The header button is disabled until the page knows
 * creation is available (the list has loaded), so wait for that before pressing it.
 */
async function openCreate(): Promise<HTMLElement> {
  render(<DevelopersPage />)
  const buttons = await screen.findAllByRole("button", { name: "Create credential" })
  await waitFor(() => expect(buttons[0]).toBeEnabled())
  fireEvent.click(buttons[0])
  return screen.findByRole("dialog")
}

describe("Developers page: create a credential and see its secret once (Task 1)", () => {
  it("sends the chosen name, scopes, role and shop, then shows the client ID and secret once", async () => {
    const dialog = await openCreate()

    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Till system" } })
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /Read your menu/ }))
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /Create and update orders/ }))
    fireEvent.change(within(dialog).getByLabelText("Role"), { target: { value: "SHOP_MANAGER" } })
    // "All shops" is the default; the body must carry it as null.
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Create credential" }))
    })

    await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalledTimes(1))
    const [url, body, config] = mockedApiClient.post.mock.calls[0] as [
      string,
      Record<string, unknown>,
      { headers?: Record<string, string> } | undefined,
    ]
    expect(url).toBe("/api/v1/credentials")
    expect(body).toEqual({
      name: "Till system",
      scopes: ["catalog:read", "orders:write"],
      role: "SHOP_MANAGER",
      shopId: null,
    })
    expect(config?.headers?.["Idempotency-Key"]).toMatch(/^[0-9a-f-]{36}$/)

    const reveal = await screen.findByRole("dialog", { name: "Copy your client secret" })
    expect(within(reveal).getByLabelText("Client ID")).toHaveValue(CLIENT_ID)
    expect(within(reveal).getByLabelText("Client secret")).toHaveValue(SECRET)
    expect(within(reveal).getByText(TOKEN_ENDPOINT)).toBeInTheDocument()
  })

  it("keeps the show-once dialog through Esc and a click outside; 'I've saved it' removes the secret from the DOM and both storages (F13)", async () => {
    const dialog = await openCreate()
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Till system" } })
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /Read your menu/ }))
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /Read orders/ }))
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Create credential" }))
    })

    const reveal = await screen.findByRole("dialog", { name: "Copy your client secret" })
    fireEvent.keyDown(reveal, { key: "Escape" })
    fireEvent.pointerDown(document.body)
    expect(screen.getByRole("dialog", { name: "Copy your client secret" })).toBeInTheDocument()
    expect(within(reveal).queryByRole("button", { name: /close/i })).toBeNull()

    // While it is open the secret is on screen, and nowhere else.
    expect(document.body.innerHTML).toContain(SECRET)

    fireEvent.click(within(reveal).getByRole("button", { name: "I've saved it" }))
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "Copy your client secret" })).toBeNull()
    )

    expect(document.body.innerHTML).not.toContain(SECRET)
    const stored = (s: Storage) =>
      Array.from({ length: s.length }, (_, i) => `${s.key(i)}=${s.getItem(s.key(i) ?? "")}`).join("\n")
    expect(stored(window.localStorage)).not.toContain(SECRET)
    expect(stored(window.sessionStorage)).not.toContain(SECRET)
  })

  it("a Create pressed again after a lost response re-sends the same Idempotency-Key, so the server replays instead of issuing a second credential", async () => {
    // The first create reached core but its answer never came back (the api-client's own retries
    // exhausted on a network error). A different key on the second press would make core issue a
    // SECOND working credential and leave the first live with a secret nobody holds (37-E review).
    mockedApiClient.post
      .mockImplementationOnce((() => Promise.reject(Object.assign(new Error("Network Error"), { isAxiosError: true }))) as never)
      .mockImplementationOnce((() => Promise.resolve({ status: 200, data: created({ secretShown: false, clientSecret: null }) })) as never)
    const dialog = await openCreate()
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Till system" } })
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /Read your menu/ }))
    const press = async () =>
      act(async () => {
        fireEvent.click(within(dialog).getByRole("button", { name: "Create credential" }))
      })
    await press()
    await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(within(dialog).getByRole("alert")).toBeInTheDocument())
    await press()
    await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalledTimes(2))
    const keyOf = (i: number) =>
      (mockedApiClient.post.mock.calls[i][2] as { headers?: Record<string, string> } | undefined)?.headers?.[
        "Idempotency-Key"
      ]
    expect(keyOf(0)).toMatch(/^[0-9a-f-]{36}$/)
    // The retried create must replay the first, not mint a new credential.
    expect(keyOf(1)).toBe(keyOf(0))
  })

  it("a changed form after a failed create is a new request with a new Idempotency-Key", async () => {
    mockedApiClient.post
      .mockImplementationOnce((() => Promise.reject(Object.assign(new Error("Network Error"), { isAxiosError: true }))) as never)
      .mockImplementationOnce((() => Promise.resolve({ status: 201, data: created({ name: "Website" }) })) as never)
    const dialog = await openCreate()
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Till system" } })
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /Read your menu/ }))
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Create credential" }))
    })
    await waitFor(() => expect(within(dialog).getByRole("alert")).toBeInTheDocument())
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Website" } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Create credential" }))
    })
    await waitFor(() => expect(mockedApiClient.post).toHaveBeenCalledTimes(2))
    const keyOf = (i: number) =>
      (mockedApiClient.post.mock.calls[i][2] as { headers?: Record<string, string> } | undefined)?.headers?.[
        "Idempotency-Key"
      ]
    // Re-using the key for a different body would earn a 422 idempotency-payload-mismatch.
    expect(keyOf(1)).not.toBe(keyOf(0))
  })

  it("refuses inline with no scope ticked and sends nothing", async () => {
    const dialog = await openCreate()
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Till system" } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Create credential" }))
    })

    expect(within(dialog).getByRole("alert")).toHaveTextContent(
      "Choose at least one thing this credential can do."
    )
    expect(mockedApiClient.post).not.toHaveBeenCalled()
  })

  it("offers Staff and Shop manager only, never Group admin; six scopes, each with its id", async () => {
    const dialog = await openCreate()
    const role = within(dialog).getByLabelText("Role") as HTMLSelectElement
    expect(Array.from(role.options).map((o) => o.textContent)).toEqual(["Staff", "Shop manager"])
    expect(Array.from(role.options).map((o) => o.value)).toEqual(["STAFF", "SHOP_MANAGER"])

    const boxes = within(dialog).getAllByRole("checkbox")
    expect(boxes).toHaveLength(6)
    for (const id of [
      "catalog:read",
      "catalog:write",
      "orders:read",
      "orders:write",
      "customers:read",
      "customers:write",
    ]) {
      expect(within(dialog).getByText(id)).toBeInTheDocument()
    }

    const shop = within(dialog).getByLabelText("Shop") as HTMLSelectElement
    expect(Array.from(shop.options).map((o) => o.textContent)).toEqual([
      "All shops",
      "Peckham Kitchen",
      "Brixton Bakery",
    ])
  })
})

// --- Task 2: the list, rotate, delete and every page state --------------------------------------

const ROTATED_SECRET = "Rt7-the-rotated-secret-shown-once-0002"
const TWO_HOURS_AGO = new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString()

function credential(overrides: Record<string, unknown> = {}) {
  return {
    id: CREDENTIAL_ID,
    name: "Till system",
    clientId: CLIENT_ID,
    scopes: ["catalog:read", "orders:write"],
    shopId: null,
    role: "SHOP_MANAGER",
    status: "ACTIVE",
    createdBy: ADMIN,
    createdAt: "2026-10-01T09:00:00Z",
    rotatedBy: null,
    rotatedAt: null,
    revokedBy: null,
    revokedAt: null,
    lastUsedAt: null,
    serviceAccountUserId: "5a5a5a5a-0000-0000-0000-000000000001",
    ...overrides,
  }
}

const STOCK_ID = "c0c0c0c0-0000-0000-0000-000000000002"
const REVOKED_ID = "c0c0c0c0-0000-0000-0000-000000000003"

function populated() {
  return [
    credential(),
    credential({
      id: STOCK_ID,
      name: "Stock sync",
      clientId: "jtoye-int-stock-sync-91bc",
      scopes: ["catalog:read", "catalog:write", "orders:read", "orders:write", "customers:read", "customers:write"],
      shopId: SHOP_A,
      role: "STAFF",
      lastUsedAt: TWO_HOURS_AGO,
    }),
    credential({
      id: REVOKED_ID,
      name: "Old website",
      clientId: "jtoye-int-old-website-0000",
      status: "REVOKED",
      revokedBy: ADMIN,
      revokedAt: "2026-10-05T09:00:00Z",
    }),
  ]
}

/** An axios-shaped rejection carrying an RFC 7807 body. */
function problem(status: number, type: string, detail?: string, headers: Record<string, string> = {}) {
  return Object.assign(new Error(`Request failed with status code ${status}`), {
    response: {
      status,
      headers,
      data: { type: `https://jtoye.uk/errors/${type}`, status, ...(detail ? { detail } : {}) },
    },
  })
}

/**
 * The loaded table. The loading skeleton carries the same region name and headers (by design, so
 * nothing shifts), so "loaded" means the skeleton rows are gone and the region is still there.
 */
async function loadedTable(): Promise<HTMLElement> {
  await waitFor(() =>
    expect(document.querySelector('[data-testid="credential-skeleton-row"]')).toBeNull()
  )
  return screen.findByRole("region", { name: "API credentials table" })
}

/** The sm+ table region and the below-sm card list. */
function tableRegion(): HTMLElement {
  return screen.getByRole("region", { name: "API credentials table" })
}

function rowOf(name: string): HTMLElement {
  const row = within(tableRegion()).getByText(name).closest("tr")
  expect(row).not.toBeNull()
  return row as HTMLElement
}

describe("Developers page: the credentials list and every page state (Task 2)", () => {
  it("lists each active credential with what it can do, its shop, role, last use and creator", async () => {
    listBody = { credentials: populated(), lastUsedIntervalMinutes: 15 }
    render(<DevelopersPage />)
    await loadedTable()

    // Headings descend in order (axe heading-order): h1 page, h2 card.
    expect(screen.getByRole("heading", { level: 1, name: "Developers" })).toBeInTheDocument()
    expect(screen.getByRole("heading", { level: 2, name: "API credentials" })).toBeInTheDocument()

    const heads = within(tableRegion())
      .getAllByRole("columnheader")
      .map((h) => h.textContent?.replace("*", "").trim())
    expect(heads).toEqual(["Name", "Can", "Shops", "Role", "Last used", "Created", "Actions"])

    const till = rowOf("Till system")
    expect(within(till).getByText(CLIENT_ID)).toBeInTheDocument()
    expect(within(till).getByText("Read your menu")).toBeInTheDocument()
    expect(within(till).getByText("Create and update orders")).toBeInTheDocument()
    expect(within(till).getByText("All shops")).toBeInTheDocument()
    expect(within(till).getByText("Shop manager")).toBeInTheDocument()
    expect(within(till).getByText("Never used")).toBeInTheDocument()
    expect(within(till).getByText(`${formatUkDate("2026-10-01T09:00:00Z")} by Ada Owner`)).toBeInTheDocument()

    const stock = rowOf("Stock sync")
    expect(within(stock).getByText("Peckham Kitchen")).toBeInTheDocument()
    expect(within(stock).getByText("Staff")).toBeInTheDocument()
    for (const label of [
      "Read your menu",
      "Change your menu",
      "Read orders",
      "Create and update orders",
      "Read customers",
      "Create and update customers",
    ]) {
      expect(within(stock).getByText(label)).toBeInTheDocument()
    }
    // Relative time, with the absolute UK time in the title and for screen readers.
    const absolute = formatUkDateTime(TWO_HOURS_AGO)
    const when = within(stock).getByText(/2 hours ago/)
    expect(when.closest("[title]")).toHaveAttribute("title", absolute)
    expect(within(stock).getByText(`(${absolute})`)).toHaveClass("sr-only")

    // A revoked credential has stopped working; it is not listed as one that can.
    expect(screen.queryByText("Old website")).toBeNull()
  })

  it("says how often 'Last used' is updated, from the API's interval", async () => {
    listBody = { credentials: populated(), lastUsedIntervalMinutes: 15 }
    const { unmount } = render(<DevelopersPage />)
    expect(await screen.findByText("Updated at most every 15 minutes.")).toBeInTheDocument()
    unmount()

    // The footnote arm: another interval from the API, another sentence. No literal in the page.
    listBody = { credentials: populated(), lastUsedIntervalMinutes: 5 }
    render(<DevelopersPage />)
    expect(await screen.findByText("Updated at most every 5 minutes.")).toBeInTheDocument()
    expect(screen.queryByText("Updated at most every 15 minutes.")).toBeNull()
  })

  it("rotates after an accent confirm and shows the new secret once", async () => {
    listBody = { credentials: populated(), lastUsedIntervalMinutes: 15 }
    mockedApiClient.post.mockImplementation(((url: string) => {
      if (url === `/api/v1/credentials/${CREDENTIAL_ID}/rotate`) {
        return Promise.resolve({ data: created({ clientSecret: ROTATED_SECRET }) })
      }
      return Promise.reject(new Error(`unexpected POST ${url}`))
    }) as never)
    render(<DevelopersPage />)
    await loadedTable()

    fireEvent.click(within(rowOf("Till system")).getByRole("button", { name: /^Rotate secret/ }))
    const confirm = await screen.findByRole("dialog", { name: 'Rotate the secret for "Till system"?' })
    expect(within(confirm).getByText(/The current secret stops working immediately/)).toBeInTheDocument()
    expect(within(confirm).getByRole("button", { name: "Keep current secret" })).toBeInTheDocument()
    const go = within(confirm).getByRole("button", { name: "Rotate secret" })
    expect(go).toHaveClass("bg-primary")
    await act(async () => {
      fireEvent.click(go)
    })

    expect(mockedApiClient.post).toHaveBeenCalledWith(`/api/v1/credentials/${CREDENTIAL_ID}/rotate`)
    const reveal = await screen.findByRole("dialog", { name: "Copy your client secret" })
    expect(within(reveal).getByLabelText("Client secret")).toHaveValue(ROTATED_SECRET)
    fireEvent.click(within(reveal).getByRole("button", { name: "I've saved it" }))
    await waitFor(() => expect(document.body.innerHTML).not.toContain(ROTATED_SECRET))
  })

  it("deletes after a destructive confirm, removes the row and confirms with a toast", async () => {
    listBody = { credentials: populated(), lastUsedIntervalMinutes: 15 }
    // As the server does (37-45): the credential stays in the list, as REVOKED.
    mockedApiClient.delete.mockImplementation((async () => {
      listBody = {
        ...listBody,
        credentials: listBody.credentials.map((c) =>
          (c as { id: string }).id === CREDENTIAL_ID ? { ...(c as object), status: "REVOKED" } : c
        ),
      }
      return { status: 204 }
    }) as never)
    render(<DevelopersPage />)
    await loadedTable()

    fireEvent.click(within(rowOf("Till system")).getByRole("button", { name: /^Delete credential/ }))
    const confirm = await screen.findByRole("dialog", { name: 'Delete "Till system"?' })
    expect(within(confirm).getByText(/It stops working immediately and can't be restored/)).toBeInTheDocument()
    expect(within(confirm).getByRole("button", { name: "Keep credential" })).toBeInTheDocument()
    const go = within(confirm).getByRole("button", { name: "Delete credential" })
    expect(go).toHaveClass("bg-destructive")
    await act(async () => {
      fireEvent.click(go)
    })

    expect(mockedApiClient.delete).toHaveBeenCalledWith(`/api/v1/credentials/${CREDENTIAL_ID}`)
    await waitFor(() => expect(within(tableRegion()).queryByText("Till system")).toBeNull())
    expect(within(tableRegion()).getByText("Stock sync")).toBeInTheDocument()
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ title: "Credential deleted" }))
  })

  it("shows a refused rotate in place, in the server's words", async () => {
    listBody = { credentials: populated(), lastUsedIntervalMinutes: 15 }
    mockedApiClient.post.mockRejectedValue(
      problem(409, "credential-client-missing", "The Keycloak client behind this credential no longer exists. Delete the credential and issue a new one.") as never
    )
    render(<DevelopersPage />)
    await loadedTable()
    fireEvent.click(within(rowOf("Till system")).getByRole("button", { name: /^Rotate secret/ }))
    const confirm = await screen.findByRole("dialog", { name: 'Rotate the secret for "Till system"?' })
    await act(async () => {
      fireEvent.click(within(confirm).getByRole("button", { name: "Rotate secret" }))
    })
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The Keycloak client behind this credential no longer exists. Delete the credential and issue a new one."
    )
    expect(screen.queryByRole("dialog", { name: "Copy your client secret" })).toBeNull()
  })

  it("empty: 'No API credentials yet' with its body and a Create credential button", async () => {
    render(<DevelopersPage />)
    expect(await screen.findByRole("heading", { level: 3, name: "No API credentials yet" })).toBeInTheDocument()
    expect(
      screen.getByText(
        "Create a credential to let your till, website or an AI agent read your menu or place orders, without sharing your password."
      )
    ).toBeInTheDocument()
    // Header and empty state both offer it; there is no count copy anywhere.
    expect(screen.getAllByRole("button", { name: "Create credential" })).toHaveLength(2)
    expect(screen.queryByRole("region", { name: "API credentials table" })).toBeNull()
  })

  it("loading: skeleton rows under the real column headers", async () => {
    mockedApiClient.get.mockImplementation((() => new Promise(() => {})) as never)
    render(<DevelopersPage />)
    const region = await screen.findByRole("region", { name: "API credentials table" })
    const heads = within(region).getAllByRole("columnheader").map((h) => h.textContent?.trim())
    expect(heads).toEqual(["Name", "Can", "Shops", "Role", "Last used", "Created", "Actions"])
    expect(region.querySelectorAll('[data-testid="credential-skeleton-row"]').length).toBeGreaterThan(0)
    expect(screen.queryByText("No API credentials yet")).toBeNull()
  })

  it("load error: the LoadErrorPanel, never the empty state", async () => {
    mockedApiClient.get.mockImplementation(((url: string) => {
      if (url === "/api/v1/credentials") return Promise.reject(problem(500, "internal-error", "Boom."))
      return Promise.resolve({ data: staffBody() })
    }) as never)
    render(<DevelopersPage />)
    const panel = await screen.findByTestId("load-error-panel")
    expect(panel).toHaveTextContent("Couldn't load API credentials")
    expect(screen.queryByText("No API credentials yet")).toBeNull()
  })

  it("feature off: says credentials aren't available here and offers no Create", async () => {
    mockedApiClient.get.mockImplementation(((url: string) => {
      if (url === "/api/v1/credentials") {
        return Promise.reject(problem(400, "credentials-not-configured", "Integration credentials are not available."))
      }
      return Promise.resolve({ data: staffBody() })
    }) as never)
    render(<DevelopersPage />)
    expect(
      await screen.findByText("API credentials aren't available on this installation yet.")
    ).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Create credential" })).toBeNull()
    expect(screen.queryByText("No API credentials yet")).toBeNull()
  })

  it("not a Group admin: the refusal, and no credential request at all", async () => {
    mockedFetchMyShops.mockResolvedValue({ shops: shops as never, isGroupAdmin: false, userId: ADMIN } as never)
    render(<DevelopersPage />)
    expect(await screen.findByText("Only a Group admin can manage API credentials.")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Create credential" })).toBeNull()
    expect(mockedApiClient.get).not.toHaveBeenCalledWith("/api/v1/credentials")
  })

  it("the server's shop-access-denied 403 reads as the same refusal", async () => {
    mockedApiClient.get.mockImplementation(((url: string) => {
      if (url === "/api/v1/credentials") return Promise.reject(problem(403, "shop-access-denied"))
      return Promise.resolve({ data: staffBody() })
    }) as never)
    render(<DevelopersPage />)
    expect(await screen.findByText("Only a Group admin can manage API credentials.")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Create credential" })).toBeNull()
  })

  it("below sm each credential is a <dl> card; from sm the table keeps its focusable region", async () => {
    listBody = { credentials: populated(), lastUsedIntervalMinutes: 15 }
    const { container } = render(<DevelopersPage />)
    const region = await loadedTable()
    expect(region).toHaveAttribute("tabindex", "0")

    const table = container.querySelector('[data-testid="credentials-table"]')
    const cards = container.querySelector('[data-testid="credentials-cards"]')
    expect(table).toHaveClass("hidden", "sm:block")
    expect(cards).toHaveClass("sm:hidden")
    const lists = (cards as HTMLElement).querySelectorAll("dl")
    expect(lists).toHaveLength(2)
    const terms = Array.from(lists[0].querySelectorAll("dt")).map((d) => d.textContent)
    expect(terms).toEqual(["Can", "Shops", "Role", "Last used", "Created"])
    // Long values wrap rather than scroll: no field of a card declares a nowrap or a truncate.
    for (const dl of Array.from(lists)) {
      expect(dl.querySelector(".whitespace-nowrap, .truncate")).toBeNull()
    }
    expect(container.firstElementChild).toHaveAttribute("data-width-tier", "index")
  })
})
