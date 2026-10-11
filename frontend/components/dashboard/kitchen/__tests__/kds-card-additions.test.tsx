/**
 * Phase 37-16 (UXT-011, P0; D-12 card part): the customer's note and the fulfilment type on EVERY
 * kitchen card, New lane and board grid alike (UI-SPEC § K1 "Card additions").
 *
 * The repro (persona run, #786): a customer wrote "severe peanut allergy" in the order notes and
 * chose delivery. The printed ticket carried both; the kitchen SCREEN carried neither, so the cook
 * reading the board saw no note and could not tell a delivery from a collection.
 *
 * Driven through the real page (network, socket and Radix Select faked), asserting:
 *   - "NOTE FROM CUSTOMER" + the note text, and "DELIVERY" / "COLLECTION" with its own icon, on a
 *     New card AND on a grid card;
 *   - the note sits after the customer name and before the items, never clamped or ellipsised,
 *     even at 400 characters, and HTML in it renders as text (no element is created: T-37-42);
 *   - empty notes render no block; the badge is flex-shrink-0 and the number wraps (break-words).
 */
import { render, screen, within } from "@testing-library/react"
import KitchenPage from "@/app/dashboard/kitchen/page"

jest.mock("@/hooks/use-stomp", () => ({
  useStomp: jest.fn(() => ({ connected: true, reconnecting: false })),
}))

const mockToast = jest.fn()
jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}))

const mockGet = jest.fn()
const mockPost = jest.fn()
jest.mock("@/lib/api-client", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => mockGet(...args),
    post: (...args: unknown[]) => mockPost(...args),
  },
}))

jest.mock("@/components/ui/select", () => {
  const React = jest.requireActual("react")
  const Select = ({ children, value, onValueChange }: {
    children: React.ReactNode
    value: string
    onValueChange: (v: string) => void
  }) => (
    <select data-testid="shop-select" value={value} onChange={(e) => onValueChange(e.target.value)}>
      {children}
    </select>
  )
  const passthrough = ({ children }: { children?: React.ReactNode }) => <>{children}</>
  const SelectItem = ({ value, children }: { value: string; children: React.ReactNode }) =>
    <option value={value}>{children}</option>
  return { Select, SelectTrigger: passthrough, SelectValue: passthrough, SelectContent: passthrough, SelectItem }
})

beforeAll(() => {
  Object.defineProperty(window, "print", { value: jest.fn(), writable: true })
  ;(global as unknown as { AudioContext: unknown }).AudioContext = jest.fn().mockImplementation(() => ({
    createOscillator: () => ({
      connect: jest.fn(), start: jest.fn(), stop: jest.fn(), type: "sine",
      frequency: { setValueAtTime: jest.fn(), value: 0 },
    }),
    createGain: () => ({ connect: jest.fn(), gain: { setValueAtTime: jest.fn(), exponentialRampToValueAtTime: jest.fn() } }),
    destination: {}, currentTime: 0, close: jest.fn(),
  }))
})

const shopsPayload = {
  content: [{
    id: "shop-1", tenantId: "tenant-1", name: "Test Shop", address: "1 Main St", slug: "test",
    description: null, logoUrl: null, bannerUrl: null, phone: null, email: null,
    latitude: null, longitude: null, openingHours: null, deliveryInfo: null,
    minimumOrderPennies: 0, published: true, tags: null,
    createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
  }],
}

function orderFixture(id: string, status: string, extra: Record<string, unknown> = {}) {
  const ts = new Date().toISOString()
  return {
    id, tenantId: "tenant-1", shopId: "shop-1", orderNumber: `ORD-${id}`,
    status, customerName: `Customer ${id}`, totalAmountPennies: 1000,
    items: [
      { id: `${id}-it-1`, productId: "p-1", productName: "Jollof Rice", quantity: 2, unitPricePennies: 500, totalPricePennies: 1000, createdAt: ts },
    ],
    createdAt: ts, updatedAt: ts,
    ...extra,
  }
}

function stubBoard(orders: Array<Record<string, unknown>>) {
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/shops")) return Promise.resolve({ data: shopsPayload })
    if (url.startsWith("/api/v1/orders/kitchen")) {
      return Promise.resolve({
        data: { content: orders, totalElements: orders.length, totalPages: 1, size: 100, number: 0, first: true, last: true },
      })
    }
    return Promise.resolve({ data: {} })
  })
  mockPost.mockReset()
  mockPost.mockResolvedValue({ data: {} })
}

/** The card element that holds an order number, in the lane or the grid. */
async function cardFor(orderNumber: string): Promise<HTMLElement> {
  const title = await screen.findByText(orderNumber)
  const card = title.closest("[data-testid='kds-new-card'], [data-testid='kds-grid-card']")
  expect(card).not.toBeNull()
  return card as HTMLElement
}

const follows = (a: Node, b: Node) => !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)

beforeEach(() => {
  localStorage.clear()
})

describe("KDS card additions (UXT-011): the customer's note and the fulfilment type", () => {
  it("shows NOTE FROM CUSTOMER, the note and DELIVERY on a New card and on a grid card", async () => {
    stubBoard([
      orderFixture("new-1", "PENDING", { notes: "severe peanut allergy", fulfilmentType: "DELIVERY" }),
      orderFixture("cook-1", "CONFIRMED", { notes: "severe peanut allergy", fulfilmentType: "DELIVERY" }),
    ])
    render(<KitchenPage />)

    for (const number of ["ORD-new-1", "ORD-cook-1"]) {
      const card = await cardFor(number)
      const note = within(card).getByTestId("kds-customer-note")
      expect(note).toHaveTextContent("NOTE FROM CUSTOMER")
      expect(within(note).getByTestId("kds-customer-note-text")).toHaveTextContent("severe peanut allergy")

      const badge = within(card).getByTestId("kds-fulfilment-badge")
      expect(badge).toHaveTextContent("DELIVERY")
      expect(badge.querySelector("svg.lucide-bike")).not.toBeNull()
      expect(badge).toHaveClass("flex-shrink-0")
    }
  })

  it("places the note after the customer name and before the items", async () => {
    stubBoard([orderFixture("cook-1", "CONFIRMED", { notes: "Ring the bell twice", fulfilmentType: "DELIVERY" })])
    render(<KitchenPage />)

    const card = await cardFor("ORD-cook-1")
    const customer = within(card).getByText("Customer cook-1")
    const note = within(card).getByTestId("kds-customer-note")
    const items = within(card).getByTestId("kds-item-list")
    expect(follows(customer, note)).toBe(true)
    expect(follows(note, items)).toBe(true)
  })

  it("renders a 400-character note in full: no clamp, no ellipsis, line breaks kept", async () => {
    const long = ("Please knock, baby asleep. Peanut allergy in the house. ".repeat(8)).slice(0, 399) + "Z"
    expect(long).toHaveLength(400)
    stubBoard([orderFixture("new-1", "PENDING", { notes: long, fulfilmentType: "COLLECTION" })])
    render(<KitchenPage />)

    const card = await cardFor("ORD-new-1")
    const text = within(card).getByTestId("kds-customer-note-text")
    expect(text.textContent).toBe(long)
    expect(text).toHaveClass("whitespace-pre-wrap")
    expect(text).toHaveClass("break-words")
    const block = within(card).getByTestId("kds-customer-note")
    for (const el of [block, text]) {
      expect(Array.from(el.classList).some((c) => c.startsWith("line-clamp") || c === "truncate" || c === "text-ellipsis")).toBe(false)
    }
  })

  it("renders HTML in a note as text: no element is created from it (T-37-42)", async () => {
    const hostile = `<img src=x onerror="alert(1)"><b>bold</b>`
    stubBoard([orderFixture("new-1", "PENDING", { notes: hostile, fulfilmentType: "DELIVERY" })])
    render(<KitchenPage />)

    const card = await cardFor("ORD-new-1")
    const text = within(card).getByTestId("kds-customer-note-text")
    expect(text.textContent).toBe(hostile)
    expect(text.querySelector("img")).toBeNull()
    expect(text.querySelector("b")).toBeNull()
    expect(text.children).toHaveLength(0)
  })

  it("renders no note block for empty notes, and Store + COLLECTION for a collection", async () => {
    stubBoard([
      orderFixture("cook-1", "PREPARING", { notes: "", fulfilmentType: "COLLECTION" }),
      orderFixture("new-1", "PENDING", { fulfilmentType: "COLLECTION" }),
    ])
    render(<KitchenPage />)

    for (const number of ["ORD-cook-1", "ORD-new-1"]) {
      const card = await cardFor(number)
      expect(within(card).queryByTestId("kds-customer-note")).toBeNull()
      expect(within(card).queryByText("NOTE FROM CUSTOMER")).toBeNull()
      const badge = within(card).getByTestId("kds-fulfilment-badge")
      expect(badge).toHaveTextContent("COLLECTION")
      expect(badge).not.toHaveTextContent("DELIVERY")
      expect(badge.querySelector("svg.lucide-store")).not.toBeNull()
    }
  })

  it("keeps the order number whole beside the badge: it wraps (break-words), the badge does not shrink", async () => {
    stubBoard([orderFixture("cook-1", "CONFIRMED", { orderNumber: "ORD-20261009-LONG-NUMBER-0001", fulfilmentType: "DELIVERY" })])
    render(<KitchenPage />)

    const title = await screen.findByText("ORD-20261009-LONG-NUMBER-0001")
    expect(title).toHaveClass("min-w-0")
    expect(title).toHaveClass("break-words")
    const card = title.closest("[data-testid='kds-grid-card']") as HTMLElement
    expect(within(card).getByTestId("kds-fulfilment-badge")).toHaveClass("flex-shrink-0")
  })

  // Found by the live desktop run of kitchen-flow.spec.ts (1280px, four columns): in a NON-wrapping
  // row, `min-w-0` lets flex shrink the number to zero width between the fulfilment badge and the
  // status badge, and `truncate`'s overflow:hidden then hides it entirely (Playwright: "hidden").
  // jsdom cannot measure that, so this pins the structural cause: the row that holds the number and
  // the badges WRAPS, so a badge drops to the next line instead of squeezing the number out. The
  // browser proof is the desktop project of e2e/kitchen-flow.spec.ts.
  it("the number and the badges share a wrapping row on both card kinds, so a badge never squeezes the number out", async () => {
    stubBoard([
      orderFixture("cook-1", "CONFIRMED", { fulfilmentType: "DELIVERY" }),
      orderFixture("new-1", "PENDING", { fulfilmentType: "COLLECTION" }),
    ])
    render(<KitchenPage />)

    for (const number of ["ORD-cook-1", "ORD-new-1"]) {
      const title = await screen.findByText(number)
      const row = title.parentElement as HTMLElement
      expect(row).toContainElement(
        within(title.closest("[data-testid='kds-grid-card'], [data-testid='kds-new-card']") as HTMLElement)
          .getByTestId("kds-fulfilment-badge")
      )
      expect(row).toHaveClass("flex")
      expect(row).toHaveClass("flex-wrap")
    }
  })
})
