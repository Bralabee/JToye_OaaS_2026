/**
 * `lib/delivery-fee.ts` — behaviour, and the guarantee that it is the ONLY copy
 * (COR-2, closed by PR #726 low (b)).
 *
 * The basket page and the checkout page must never quote different delivery fees
 * for the same basket. Until PR #726 the checkout page carried a byte-identical
 * second body and this file ran both and compared; that block could only ever
 * say "the two agree TODAY". The copy is now deleted and the checkout page
 * imports the lib, so the guard below is structural: the page must import the
 * lib and must not define a function of that name. Re-introducing the copy —
 * the way the drift would actually start — turns it red.
 */
import { readFileSync } from "node:fs"
import { join } from "node:path"
import { previewDeliveryFeePennies } from "@/lib/delivery-fee"
import type { FulfilmentType } from "@/types/api"

const CHECKOUT_PAGE = join(process.cwd(), "app", "shop", "[slug]", "checkout", "page.tsx")
const CART_PAGE = join(process.cwd(), "app", "shop", "[slug]", "cart", "page.tsx")

/**
 * Every case the server waiver distinguishes, plus the two null shapes
 * `PublicShop` really puts on the wire (WR-04: a fee is nullable, and an
 * API-created shop genuinely serialises `deliveryFeePennies: null`).
 */
const CASES: {
  name: string
  args: [number, FulfilmentType, number | null | undefined, number | null | undefined]
  expected: number
}[] = [
  { name: "collection is always free", args: [300, "COLLECTION", 350, 2500], expected: 0 },
  { name: "collection is free even above the threshold", args: [9999, "COLLECTION", 350, 2500], expected: 0 },
  { name: "delivery below the threshold charges the shop's fee", args: [300, "DELIVERY", 350, 2500], expected: 350 },
  { name: "delivery exactly AT the threshold is waived", args: [2500, "DELIVERY", 350, 2500], expected: 0 },
  { name: "delivery one penny below the threshold is charged", args: [2499, "DELIVERY", 350, 2500], expected: 350 },
  { name: "delivery above the threshold is waived", args: [9999, "DELIVERY", 350, 2500], expected: 0 },
  { name: "no threshold means the fee is never waived", args: [999999, "DELIVERY", 350, null], expected: 350 },
  { name: "a null fee previews as zero", args: [300, "DELIVERY", null, 2500], expected: 0 },
  { name: "an undefined shop (not loaded) previews as zero", args: [300, "DELIVERY", undefined, undefined], expected: 0 },
  { name: "a wire zero fee is genuinely free delivery", args: [300, "DELIVERY", 0, 2500], expected: 0 },
]

describe("previewDeliveryFeePennies mirrors the server waiver", () => {
  it.each(CASES)("$name", ({ args, expected }) => {
    expect(previewDeliveryFeePennies(...args)).toBe(expected)
  })
})

describe("the basket and checkout previews are ONE rule (COR-2)", () => {
  it("both storefront pages import the lib's previewDeliveryFeePennies", () => {
    const importLine = /import\s*\{[^}]*\bpreviewDeliveryFeePennies\b[^}]*\}\s*from\s*"@\/lib\/delivery-fee"/
    expect(readFileSync(CHECKOUT_PAGE, "utf8")).toMatch(importLine)
    expect(readFileSync(CART_PAGE, "utf8")).toMatch(importLine)
  })

  it("the checkout page no longer defines its own copy (PR #726 low b)", () => {
    // The exact shape the duplicate had. A re-export would also be a second
    // name for the same rule and is caught by the same pattern's `export` arm.
    const source = readFileSync(CHECKOUT_PAGE, "utf8")
    expect(source).not.toMatch(/function\s+previewDeliveryFeePennies\s*\(/)
    expect(source).not.toMatch(/export\s*\{\s*previewDeliveryFeePennies\s*\}/)
    // Anti-vacuity: the file really was read and really does call the preview.
    expect(source).toMatch(/previewDeliveryFeePennies\(/)
    expect(CASES.length).toBeGreaterThanOrEqual(10)
    // A deliberately WRONG expectation must be caught by the lib itself —
    // proof the behaviour block above can fail at all.
    expect(previewDeliveryFeePennies(300, "DELIVERY", 350, 2500)).not.toBe(0)
  })
})
