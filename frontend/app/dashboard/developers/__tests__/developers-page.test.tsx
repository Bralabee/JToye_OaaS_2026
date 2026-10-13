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

/** Open the create dialog from the page header. */
async function openCreate(): Promise<HTMLElement> {
  render(<DevelopersPage />)
  const buttons = await screen.findAllByRole("button", { name: "Create credential" })
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
