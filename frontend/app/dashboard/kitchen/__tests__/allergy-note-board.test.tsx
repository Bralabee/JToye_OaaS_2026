/**
 * 31.1-22 (#812, D-15): the customer's allergy note on the kitchen BOARD, and its print rules.
 *
 * The board card is where the kitchen sees the note and marks it read; the printed ticket
 * (KitchenTicket, display:none on screen) is where it travels with the food. These tests assert:
 *   - POSITION: inside the card header, after the allergen banner and before the customer name
 *     and the items — the same glance contract the 31-15 banner is held to;
 *   - the acknowledgement round trip from the card, and that the board's own copy of the order
 *     learns the server's who/when (so a re-print says "Read by");
 *   - the goods already on the card (bump button, print button, {n} items) survive;
 *   - the print stylesheet carries the note block in black and white, bordered, unsplit.
 */
import fs from "fs"
import path from "path"
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import KitchenPage from "../page"

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

const NOTE = "My son has a peanut allergy. Please no satay sauce."
// 17:05Z on 3 Oct 2026 is 18:05 in London (BST).
const ACK_AT = "2026-10-03T17:05:00Z"

const shopsPayload = {
  content: [{
    id: "shop-1", tenantId: "tenant-1", name: "Test Shop", address: "1 Main St", slug: "test",
    description: null, logoUrl: null, bannerUrl: null, phone: null, email: null,
    latitude: null, longitude: null, openingHours: null, deliveryInfo: null,
    minimumOrderPennies: 0, published: true, tags: null,
    createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
  }],
}

function boardPayload(extra: Record<string, unknown>) {
  const ts = new Date().toISOString()
  return {
    content: [{
      id: "order-0", tenantId: "tenant-1", shopId: "shop-1", orderNumber: "ORD-order-0",
      status: "CONFIRMED", customerName: "Alice", totalAmountPennies: 1000,
      notes: "Ring the bell",
      items: [
        { id: "it-1", productId: "p-1", productName: "Burger", quantity: 2, unitPricePennies: 500, totalPricePennies: 1000, createdAt: ts, allergenNames: ["Gluten"] },
      ],
      createdAt: ts, updatedAt: ts,
      allergenMask: 1, allergenNames: ["Gluten"], allergenFlags: [],
      ...extra,
    }],
    totalElements: 1, totalPages: 1, size: 100, number: 0, first: true, last: true,
  }
}

function stubBoard(extra: Record<string, unknown>) {
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/shops")) return Promise.resolve({ data: shopsPayload })
    if (url.startsWith("/api/v1/orders/kitchen")) return Promise.resolve({ data: boardPayload(extra) })
    return Promise.resolve({ data: {} })
  })
  mockPost.mockReset()
}

async function findCard(): Promise<HTMLElement> {
  const title = await screen.findByText("ORD-order-0")
  const card = title.closest(".transition-colors")
  expect(card).not.toBeNull()
  return card as HTMLElement
}

beforeEach(() => {
  localStorage.clear()
  document.body.querySelectorAll("#kds-print-root").forEach((n) => n.remove())
})

describe("KDS card — the allergy note (31.1-22)", () => {
  it("sits in the card header after the allergen banner, before the customer and the items", async () => {
    stubBoard({ allergyNote: NOTE })
    render(<KitchenPage />)

    const card = await findCard()
    const header = within(card).getByTestId("kds-card-header")
    const block = within(header).getByTestId("allergy-note")
    expect(block).toHaveTextContent("ALLERGY NOTE")
    expect(block).toHaveTextContent(NOTE)

    const banner = within(header).getByTestId("kds-allergen-banner")
    const customer = within(card).getByText("Alice")
    const items = within(card).getByTestId("kds-item-list")
    const follows = (a: Node, b: Node) => !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)
    expect(follows(banner, block)).toBe(true)
    expect(follows(block, customer)).toBe(true)
    expect(follows(block, items)).toBe(true)
  })

  it("marks the note read from the card and keeps every existing control", async () => {
    stubBoard({ allergyNote: NOTE })
    mockPost.mockImplementation((url: string) =>
      url.endsWith("/allergy-note/acknowledgement")
        ? Promise.resolve({ data: { id: "order-0", allergyNoteAcknowledgedAt: ACK_AT, allergyNoteAcknowledgedBy: "kim" } })
        : Promise.resolve({ data: {} })
    )
    render(<KitchenPage />)

    const card = await findCard()
    fireEvent.click(within(card).getByRole("button", { name: /^Mark allergy note as read/ }))

    expect(await within(card).findByTestId("allergy-note-read")).toHaveTextContent(/Read by kim at 18:05/)
    expect(mockPost).toHaveBeenCalledTimes(1)
    expect(mockPost).toHaveBeenCalledWith("/api/v1/orders/order-0/allergy-note/acknowledgement")

    // The goods already on the card (P2-FUN-18, 31-15): the big bump and the print button.
    const bump = within(card).getByRole("button", { name: /Start Preparing/ })
    expect(bump.className).toMatch(/\bh-11\b/)
    expect(within(card).getByRole("button", { name: /Print ticket ORD-order-0/ })).toBeInTheDocument()
    expect(within(card).getByText("1 item")).toBeInTheDocument()
  })

  it("a re-print after marking it read says who read it — the board's copy learned the 200", async () => {
    stubBoard({ allergyNote: NOTE })
    mockPost.mockResolvedValue({
      data: { id: "order-0", allergyNoteAcknowledgedAt: ACK_AT, allergyNoteAcknowledgedBy: "kim" },
    })
    render(<KitchenPage />)

    const card = await findCard()
    fireEvent.click(within(card).getByRole("button", { name: /^Mark allergy note as read/ }))
    await within(card).findByTestId("allergy-note-read")

    await act(async () => {
      fireEvent.click(within(card).getByRole("button", { name: /Print ticket ORD-order-0/ }))
    })
    const sheet = await screen.findByTestId("kds-print-root")
    await waitFor(() =>
      expect(sheet.querySelector(".kds-ticket__allergy-note")).toHaveTextContent(/Read by kim at 18:05/)
    )
  })

  it("a card with no allergy note renders no block and no extra button", async () => {
    stubBoard({})
    render(<KitchenPage />)

    const card = await findCard()
    expect(within(card).queryByTestId("allergy-note")).toBeNull()
    expect(within(card).queryByRole("button", { name: /allergy note/i })).toBeNull()
  })
})

describe("The print stylesheet — the allergy note block", () => {
  const css = fs
    .readFileSync(path.join(__dirname, "..", "..", "..", "globals.css"), "utf8")
    .replace(/\/\*[\s\S]*?\*\//g, "")

  const printBlock = (() => {
    const blocks: string[] = []
    let from = 0
    for (;;) {
      const start = css.indexOf("@media print", from)
      if (start < 0) break
      const open = css.indexOf("{", start)
      let depth = 0
      let end = -1
      for (let j = open; j < css.length; j++) {
        if (css[j] === "{") depth++
        else if (css[j] === "}" && --depth === 0) {
          end = j
          break
        }
      }
      if (end < 0) throw new Error("unterminated @media print block")
      blocks.push(css.slice(open + 1, end))
      from = end
    }
    const mine = blocks.filter((b) => b.includes("#kds-print-root"))
    if (mine.length !== 1) throw new Error(`expected one #kds-print-root print block, found ${mine.length}`)
    return mine[0]
  })()

  function ruleBody(selector: string): string {
    const i = printBlock.indexOf(selector)
    expect(i).toBeGreaterThanOrEqual(0)
    const open = printBlock.indexOf("{", i)
    return printBlock.slice(open + 1, printBlock.indexOf("}", open))
  }

  it("borders the note block in black on white, with an uppercase label", () => {
    const body = ruleBody(".kds-ticket__allergy-note {")
    expect(body).toMatch(/border:\s*\d/)
    for (const hex of body.match(/#[0-9a-fA-F]{3,8}/g) || []) {
      expect(["#000", "#fff", "#000000", "#ffffff"]).toContain(hex.toLowerCase())
    }
    expect(ruleBody(".kds-ticket__allergy-note span:first-child {")).toMatch(/text-transform:\s*uppercase/)
  })

  it("never splits the note block across two pages", () => {
    const i = printBlock.indexOf("break-inside: avoid")
    expect(i).toBeGreaterThan(0)
    const ruleStart = printBlock.lastIndexOf("}", i) + 1
    expect(printBlock.slice(ruleStart, i)).toContain(".kds-ticket__allergy-note")
  })
})
