/**
 * 31-15 (LGL-03 / D-04, UI-SPEC S4) — allergen surfacing on the kitchen display:
 * where the banner sits on the card, the restructured item list, and the printed ticket.
 *
 * THREE THINGS THESE TESTS EXIST TO CATCH, none of which a screenshot would.
 *
 * 1. POSITION, not presence. "The banner is somewhere on the card" is satisfied by a
 *    banner below the fold of a scrolled card, which is the same as no banner to someone
 *    glancing at a wall screen. So position is asserted RELATIVE to the order number and
 *    the customer name, and containment inside the CardHeader.
 *
 * 2. REGRESSION BY OMISSION. The "{n} items" summary, the status badge, the age border
 *    and the 44px controls were all working before this plan. Adding a banner while
 *    quietly dropping one of them is a defect even with a green suite, so each is
 *    asserted still present on the same card that carries the new banner.
 *
 * 3. THE PRINT SHEET. The kitchen acts on the printed ticket, and jsdom loads no
 *    stylesheet — a passing render says nothing about `@media print`. So the print half
 *    is asserted in two places that together are falsifiable: the component emits the
 *    block (jest), AND `app/globals.css` carries rules for it INSIDE the same
 *    `@media print` block as the `#kds-print-root` guard, bordered, uppercase, and using
 *    no colour but black and white. A warning carried by an amber fill prints as an
 *    indistinct grey, which is not a warning.
 */

import fs from "fs"
import path from "path"
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react"
import KitchenPage from "../page"
import { KitchenTicket } from "@/components/dashboard/kitchen/kitchen-ticket"
import type { OrderDetail } from "@/types/api"

jest.mock("@/hooks/use-stomp", () => ({
  useStomp: jest.fn(() => ({ connected: true, reconnecting: false })),
}))

jest.mock("@/hooks/use-toast", () => ({
  useToast: () => ({ toast: jest.fn() }),
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

// Radix Select is portal + focus logic; a plain <select> keeps this test about allergens.
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

type AllergenShape = Pick<OrderDetail, "allergenMask" | "allergenNames" | "allergenFlags">

/**
 * One ticket, with the allergen picture under test. `items` carry their own per-line
 * snapshot so the badge has something to say WHICH dish.
 */
function boardPayload(allergens: Partial<AllergenShape>, itemAllergens: (string[] | null)[]) {
  const ts = new Date().toISOString()
  return {
    content: [{
      id: "order-0", tenantId: "tenant-1", shopId: "shop-1", orderNumber: "ORD-order-0",
      status: "CONFIRMED", customerName: "Alice", totalAmountPennies: 1000,
      items: [
        { id: "it-1", productId: "p-1", productName: "Burger", quantity: 2, unitPricePennies: 500, totalPricePennies: 1000, createdAt: ts, allergenNames: itemAllergens[0] },
        { id: "it-2", productId: "p-2", productName: "Salad", quantity: 1, unitPricePennies: 400, totalPricePennies: 400, createdAt: ts, allergenNames: itemAllergens[1] },
      ],
      createdAt: ts, updatedAt: ts,
      ...allergens,
    }],
    totalElements: 1, totalPages: 1, size: 100, number: 0, first: true, last: true,
  }
}

function stubBoard(allergens: Partial<AllergenShape>, itemAllergens: (string[] | null)[] = [null, null]) {
  mockGet.mockReset()
  mockGet.mockImplementation((url: string) => {
    if (url.startsWith("/api/v1/shops")) return Promise.resolve({ data: shopsPayload })
    if (url.startsWith("/api/v1/orders/kitchen")) return Promise.resolve({ data: boardPayload(allergens, itemAllergens) })
    return Promise.resolve({ data: {} })
  })
  mockPost.mockReset()
  mockPost.mockResolvedValue({ data: {} })
}

/** The order card, reached the same way the existing KDS suite reaches it. */
async function findCard(): Promise<HTMLElement> {
  const title = await screen.findByText("ORD-order-0")
  const card = title.closest(".transition-colors")
  expect(card).not.toBeNull()
  return card as HTMLElement
}

const DECLARED = { allergenMask: 5, allergenNames: ["Gluten", "Sesame"], allergenFlags: [] }
const NONE_DECLARED = { allergenMask: 0, allergenNames: [], allergenFlags: [] }
const NOT_RECORDED = { allergenMask: null, allergenNames: null, allergenFlags: null }

beforeEach(() => {
  localStorage.clear()
})

describe("KDS card — where the banner sits", () => {
  it("puts the banner inside CardHeader, AFTER the order number and BEFORE the customer name", async () => {
    // Position, not presence. A banner that renders below the customer name — or worse,
    // at the bottom of the card — is invisible to a glance at a wall screen, and a
    // presence-only assertion cannot tell the two apart.
    stubBoard(DECLARED)
    render(<KitchenPage />)

    const card = await findCard()
    const title = within(card).getByText("ORD-order-0")
    const header = title.closest("[data-testid='kds-card-header']")
    expect(header).not.toBeNull()

    // Inside the header...
    const banner = within(header as HTMLElement).getByTestId("kds-allergen-banner")

    // ...after the order number...
    expect(
      title.compareDocumentPosition(banner) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()

    // ...and before the customer name, which lives outside the header in CardContent.
    const customer = within(card).getByText("Alice")
    expect((header as HTMLElement).contains(customer)).toBe(false)
    expect(
      banner.compareDocumentPosition(customer) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()
  })

  it("renders the complete declared set on the card, in words", async () => {
    stubBoard(DECLARED)
    render(<KitchenPage />)

    const card = await findCard()
    const banner = within(card).getByTestId("kds-allergen-banner")
    expect(banner).toHaveTextContent("ALLERGENS")
    expect(banner).toHaveTextContent("Gluten")
    expect(banner).toHaveTextContent("Sesame")
  })

  it("shows NO banner for an order that declared none — and the card is otherwise intact", async () => {
    stubBoard(NONE_DECLARED)
    render(<KitchenPage />)

    const card = await findCard()
    expect(within(card).queryByTestId("kds-allergen-banner")).toBeNull()
    expect(within(card).queryByTestId("kds-allergen-unrecorded")).toBeNull()

    // Otherwise unchanged: status badge, customer, items, controls.
    expect(within(card).getByText("Confirmed")).toBeInTheDocument()
    expect(within(card).getByText("Alice")).toBeInTheDocument()
    expect(within(card).getByText("2 items")).toBeInTheDocument()
  })

  it("distinguishes a NOT RECORDED ticket from an allergen-free one", async () => {
    // The paired directions in one test. "No banner" MEANS "the vendor declared none";
    // a pre-V63 ticket rendering the same nothing would be making that claim on data
    // that does not exist.
    stubBoard(NOT_RECORDED)
    render(<KitchenPage />)

    const card = await findCard()
    const strip = within(card).getByTestId("kds-allergen-unrecorded")
    expect(strip).toHaveTextContent(/NOT RECORDED/)
    // Not the amber warning either — the platform is not claiming allergens are present.
    expect(within(card).queryByTestId("kds-allergen-banner")).toBeNull()
  })
})

describe("KDS card — the restructured item list", () => {
  it("renders one <li> per item at 14px with the quantity first", async () => {
    stubBoard(DECLARED, [["Gluten", "Sesame"], null])
    render(<KitchenPage />)

    const card = await findCard()
    const list = within(card).getByTestId("kds-item-list")
    expect(list.tagName).toBe("UL")
    expect(list.className).toContain("text-sm")

    const rows = within(list).getAllByRole("listitem")
    expect(rows).toHaveLength(2)
    expect((rows[0].textContent || "").trim().startsWith("2x")).toBe(true)
    expect(rows[0]).toHaveTextContent("Burger")
    expect((rows[1].textContent || "").trim().startsWith("1x")).toBe(true)
    expect(rows[1]).toHaveTextContent("Salad")
  })

  it("puts the per-item badge on the row that carries the allergen, and only that row", async () => {
    // The whole reason a per-item badge exists: an order-level aggregate does not tell
    // a cook WHICH of the dishes to be careful with.
    stubBoard(DECLARED, [["Gluten", "Sesame"], null])
    render(<KitchenPage />)

    const card = await findCard()
    const rows = within(within(card).getByTestId("kds-item-list")).getAllByRole("listitem")

    const badge = within(rows[0]).getByTestId("kds-item-allergen-badge")
    expect(badge).toHaveTextContent("Gluten")
    expect(within(rows[1]).queryByTestId("kds-item-allergen-badge")).toBeNull()
  })

  it("KEEPS the '{n} items' summary line above the list — this is additive", async () => {
    // Regression by omission is a defect even when the suite is green.
    stubBoard(DECLARED, [["Gluten"], null])
    render(<KitchenPage />)

    const card = await findCard()
    const summary = within(card).getByText("2 items")
    const list = within(card).getByTestId("kds-item-list")
    expect(
      summary.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()
  })

  it("no longer joins the items into one inline comma-separated run", async () => {
    // The DOM half is the <li> count above. This is the source half: the old construct
    // is gone rather than merely unreachable.
    const src = fs.readFileSync(path.join(__dirname, "..", "page.tsx"), "utf8")
    expect(src).not.toContain('{i > 0 && ", "}')
  })
})

describe("KDS card — the goods that were already there", () => {
  it("keeps both 44px controls, the status badge and the age border", async () => {
    // h-10 measured 40x40 on a 375px profile and was raised to h-11 because the control
    // is pressed by a cook's thumb. Adding a banner must not have cost either of them.
    stubBoard(DECLARED, [["Gluten"], null])
    render(<KitchenPage />)

    const card = await findCard()
    const tall = within(card).getAllByRole("button").filter((b) => b.className.includes("h-11"))
    expect(tall).toHaveLength(2)

    expect(within(card).getByText("Confirmed")).toBeInTheDocument()
    // The age border is AGE, not allergens — deliberately untouched by this plan.
    expect(card.className).toContain("border-green-500")
  })
})

describe("The printed ticket", () => {
  const PRINTED_AT = Date.UTC(2026, 7, 4, 18, 30)

  const printableOrder: OrderDetail = {
    id: "11111111-2222-3333-4444-555555555555",
    tenantId: "t-1", shopId: "s-1", orderNumber: "ORD-2026-0042", status: "PREPARING",
    customerName: "Adeola", totalAmountPennies: 2350,
    items: [
      { id: "i1", productId: "p1", productName: "Jollof Rice", quantity: 2, unitPricePennies: 800, totalPricePennies: 1600, createdAt: "2026-08-04T17:00:00Z", allergenNames: ["Gluten"] },
      { id: "i2", productId: "p2", productName: "Suya Wrap", quantity: 1, unitPricePennies: 750, totalPricePennies: 750, createdAt: "2026-08-04T17:00:00Z", allergenNames: [] },
    ],
    createdAt: "2026-08-04T17:00:00Z", updatedAt: "2026-08-04T17:00:00Z",
    fulfilmentType: "COLLECTION",
    allergenMask: 1, allergenNames: ["Gluten", "Sesame"],
    allergenFlags: [{ productName: "Suya Wrap", allergenBit: 10, allergenName: "Sesame" }],
  }

  it("carries the allergen block, in words, with the CHECK line", () => {
    const { container } = render(
      <KitchenTicket order={printableOrder} shopName="Peckham" printedAt={PRINTED_AT} />
    )
    const block = container.querySelector(".kds-ticket__allergens")
    expect(block).not.toBeNull()
    expect(block).toHaveTextContent("ALLERGENS")
    expect(block).toHaveTextContent("Gluten")
    expect(block).toHaveTextContent("Sesame")
    expect(block).toHaveTextContent("CHECK:")
    expect(block).toHaveTextContent("Suya Wrap")
  })

  it("still prints everything it printed before — reference, items and quantities", () => {
    // The additive half. A ticket that gained an allergen block and lost its items is
    // a worse ticket than the one it replaced.
    const { container } = render(
      <KitchenTicket order={printableOrder} shopName="Peckham" printedAt={PRINTED_AT} />
    )
    expect(container.querySelector(".kds-ticket__ref")).toHaveTextContent("ORD-2026-0042")
    const items = container.querySelectorAll(".kds-ticket__items li")
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent("Jollof Rice")
    expect(within(items[0] as HTMLElement).getByText("2×")).toBeInTheDocument()
    expect(items[1]).toHaveTextContent("Suya Wrap")
  })

  it("names the allergen on the ITEM line too, so the cook knows which dish", () => {
    const { container } = render(
      <KitchenTicket order={printableOrder} shopName="Peckham" printedAt={PRINTED_AT} />
    )
    const items = container.querySelectorAll(".kds-ticket__items li")
    expect(within(items[0] as HTMLElement).getByText(/Gluten/)).toBeInTheDocument()
    expect(within(items[1] as HTMLElement).queryByText(/Gluten/)).toBeNull()
  })

  it("prints nothing at all about allergens when the vendor declared none", () => {
    const { container } = render(
      <KitchenTicket
        order={{ ...printableOrder, allergenMask: 0, allergenNames: [], allergenFlags: [] }}
        shopName="Peckham"
        printedAt={PRINTED_AT}
      />
    )
    expect(container.querySelector(".kds-ticket__allergens")).toBeNull()
  })

  it("prints ALLERGENS NOT RECORDED for a ticket whose allergen data predates the snapshot", () => {
    const { container } = render(
      <KitchenTicket
        order={{ ...printableOrder, allergenMask: null, allergenNames: null, allergenFlags: null }}
        shopName="Peckham"
        printedAt={PRINTED_AT}
      />
    )
    const block = container.querySelector(".kds-ticket__allergens")
    expect(block).not.toBeNull()
    expect(block).toHaveTextContent("ALLERGENS NOT RECORDED")
  })

  it("reaches the sheet the printer actually receives, inside #kds-print-root", async () => {
    stubBoard(DECLARED, [["Gluten", "Sesame"], null])
    render(<KitchenPage />)

    await screen.findByText("ORD-order-0")
    fireEvent.click(screen.getByRole("button", { name: /^Print ticket/i }))

    const sheet = await screen.findByTestId("kds-print-root")
    const block = sheet.querySelector(".kds-ticket__allergens")
    expect(block).not.toBeNull()
    expect(block).toHaveTextContent("Gluten")
    await waitFor(() => expect(window.print).toHaveBeenCalled())
  })
})

/**
 * The print STYLESHEET, read as text.
 *
 * jsdom loads no stylesheet, so the tests above prove the markup exists and prove
 * nothing about how it prints. These read `app/globals.css` directly and assert the
 * properties a thermal printer can actually carry. Without them, "the warning survives
 * printing" would be an unfalsifiable claim backed by a screen render.
 */
describe("The print stylesheet", () => {
  const css = fs.readFileSync(path.join(__dirname, "..", "..", "..", "globals.css"), "utf8")

  /** The body of the `@media print` block, by brace counting — not by regex. */
  const printBlock = (() => {
    const start = css.indexOf("@media print")
    if (start < 0) throw new Error("no @media print block in globals.css")
    const open = css.indexOf("{", start)
    let depth = 0
    for (let j = open; j < css.length; j++) {
      if (css[j] === "{") depth++
      else if (css[j] === "}") {
        depth--
        if (depth === 0) return css.slice(open + 1, j)
      }
    }
    throw new Error("unterminated @media print block")
  })()

  /** The declarations of one rule inside the print block. */
  function ruleBody(selector: string): string {
    const i = printBlock.indexOf(selector)
    expect(i).toBeGreaterThanOrEqual(0)
    const open = printBlock.indexOf("{", i)
    const close = printBlock.indexOf("}", open)
    expect(open).toBeGreaterThan(0)
    expect(close).toBeGreaterThan(open)
    return printBlock.slice(open + 1, close)
  }

  it("carries the allergen rules inside the SAME @media print block as the #kds-print-root guard", () => {
    // The guard is load-bearing: without it these rules would hide every child of
    // <body> on any print at all.
    expect(printBlock).toContain("body:has(#kds-print-root)")
    expect(printBlock).toContain(".kds-ticket__allergens")
    expect(printBlock).toContain(".kds-ticket__item-allergens")
  })

  it("carries the warning in a border and in uppercase — the two things monochrome CAN carry", () => {
    const body = ruleBody(".kds-ticket__allergens {")
    expect(body).toMatch(/border:\s*\d/)
    expect(body).toMatch(/text-transform:\s*uppercase/)
  })

  it("uses NO colour but black and white — an amber fill prints as an indistinct grey", () => {
    // The fail direction this exists for: someone reuses `bg-amber-800` on the print
    // sheet, it looks right on screen, and the ticket comes out of the thermal printer
    // with a grey box that reads as decoration.
    for (const selector of [".kds-ticket__allergens {", ".kds-ticket__item-allergens {"]) {
      const hexes = ruleBody(selector).match(/#[0-9a-fA-F]{3,8}/g) || []
      for (const hex of hexes) {
        expect(["#000", "#fff", "#000000", "#ffffff"]).toContain(hex.toLowerCase())
      }
    }
  })

  it("adds no animation or transition to the print sheet", () => {
    // Any CSS transition added here would need its own prefers-reduced-motion block —
    // MotionConfig reducedMotion="user" covers framer-motion only. The simplest way to
    // satisfy that is to add none, which is what this asserts.
    const body = ruleBody(".kds-ticket__allergens {")
    expect(body).not.toMatch(/transition/)
    expect(body).not.toMatch(/animation/)
  })

  it("never splits the allergen block across two pages", () => {
    // Half a warning is worse than none, the same reason the ticket itself is
    // break-inside: avoid.
    const i = printBlock.indexOf("break-inside: avoid")
    expect(i).toBeGreaterThan(0)
    const ruleStart = printBlock.lastIndexOf("}", i) + 1
    expect(printBlock.slice(ruleStart, i)).toContain(".kds-ticket__allergens")
  })
})
