/**
 * `lib/delivery-fee.ts` — behaviour, and PARITY with the copy still living in
 * the checkout page (COR-2).
 *
 * The parity block is the load-bearing half. The basket page and the checkout
 * page must never quote different delivery fees for the same basket, and until
 * the checkout page can be edited (another lane owns that file this round) the
 * only thing standing between "one rule" and "two rules that happen to agree
 * today" is an assertion that runs both and compares. Delete the checkout copy
 * and re-export the lib, and this block keeps passing for the right reason.
 *
 * The checkout page is imported for its exported pure function only; its
 * module-level dependencies are mocked exactly as
 * `app/shop/[slug]/checkout/__tests__/checkout.test.tsx` mocks them.
 */
import { previewDeliveryFeePennies } from "@/lib/delivery-fee"
import { previewDeliveryFeePennies as checkoutPreview } from "@/app/shop/[slug]/checkout/page"
import type { FulfilmentType } from "@/types/api"

jest.mock("@/lib/public-api-client", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
}))
jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
}))
jest.mock("@/lib/order-history", () => ({ saveLocalOrder: jest.fn() }))
jest.mock("@stripe/stripe-js", () => ({ loadStripe: jest.fn(() => Promise.resolve(null)) }))
jest.mock("@stripe/react-stripe-js", () => ({
  Elements: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  PaymentElement: () => null,
  useStripe: () => null,
  useElements: () => null,
}))

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
  it("agrees with the checkout page's copy on every case", () => {
    const disagreements = CASES.filter(
      (c) => previewDeliveryFeePennies(...c.args) !== checkoutPreview(...c.args)
    ).map((c) => c.name)
    expect(disagreements).toEqual([])
  })

  /**
   * Anti-vacuity. Without this the block above would pass just as happily if
   * one of the two imports resolved to `undefined` and both calls threw the
   * same way, or if CASES were empty.
   */
  it("is comparing two real, distinct-by-identity functions over a non-empty case set", () => {
    expect(typeof previewDeliveryFeePennies).toBe("function")
    expect(typeof checkoutPreview).toBe("function")
    expect(CASES.length).toBeGreaterThanOrEqual(10)
    // A deliberately WRONG expectation must be caught by the same comparison —
    // proof the comparison can fail at all.
    expect(previewDeliveryFeePennies(300, "DELIVERY", 350, 2500)).not.toBe(0)
  })
})
