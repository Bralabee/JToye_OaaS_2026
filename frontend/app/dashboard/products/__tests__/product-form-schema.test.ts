/**
 * QA council 20260902-134741 — A11Y-8 (whitespace passes) + A11Y-11 (price
 * coerces junk), the products form's Zod schema.
 *
 * A11Y-8: `.min(1)` with no `.trim()` let "     " through for sku, title and
 * ingredients — and a blank ingredientsText feeds the V63 advisory allergen
 * reconciliation as "declared", not "missing".
 * A11Y-11: `parseFloat` stops at the first bad character ("5abc" -> 5),
 * accepts exponent notation ("1e3" -> 1000) and had no upper bound, so
 * "99999999999999" produced pricePennies 9999999999999900 — past
 * Number.MAX_SAFE_INTEGER before serialisation.
 *
 * The schema is the single source of truth for the form (zodResolver), so it
 * is tested directly; the rendered messages are exercised by
 * form-error-a11y.test.tsx.
 */
import { productSchema, toPricePennies, MAX_PRICE_PENNIES } from "../product-form-schema"

const valid = { sku: "PROD-001", title: "Jollof Rice", ingredientsText: "Rice, tomato", pricePounds: "4.50" }
const parse = (patch: Partial<typeof valid>) => productSchema.safeParse({ ...valid, ...patch })
const firstMessage = (r: ReturnType<typeof parse>, field: string) =>
  r.success ? null : r.error.issues.find((i) => i.path[0] === field)?.message ?? null

describe("productSchema — whitespace-only text fields are rejected (A11Y-8)", () => {
  it("control: a real product is accepted", () => {
    expect(parse({}).success).toBe(true)
  })
  it.each([
    ["title", "Title is required"],
    ["ingredientsText", "Ingredients are required"],
    ["sku", "SKU is required"],
  ])("%s = five spaces is rejected with the existing message", (field, message) => {
    const r = parse({ [field]: "     " } as Partial<typeof valid>)
    expect(r.success).toBe(false)
    expect(firstMessage(r, field)).toBe(message)
  })
  it("trims BEFORE the length check, so a space-padded 200-char title is still accepted", () => {
    expect(parse({ title: `  ${"x".repeat(200)}  ` }).success).toBe(true)
    expect(parse({ title: "x".repeat(201) }).success).toBe(false)
  })
})

describe("productSchema — price is a strict decimal within a declared bound (A11Y-11)", () => {
  it.each(["5abc", "1e3", "99999999999999", "-5", "4.505", "£4.50", "4,50"])("rejects %p", (bad) => {
    const r = parse({ pricePounds: bad })
    expect(r.success).toBe(false)
    expect(firstMessage(r, "pricePounds")).toBeTruthy()
  })
  it.each([
    ["4.50", 450],
    ["007.50", 750],
    ["7.5", 750],
    ["0", 0],
    ["12", 1200],
  ])("accepts %p and it submits as %i pennies", (good, pennies) => {
    expect(parse({ pricePounds: good }).success).toBe(true)
    expect(toPricePennies(good)).toBe(pennies)
  })
  it("the bound is the server's own @Max: £10,000,000.00 passes, one penny more fails", () => {
    expect(MAX_PRICE_PENNIES).toBe(1_000_000_000)
    expect(parse({ pricePounds: "10000000.00" }).success).toBe(true)
    expect(parse({ pricePounds: "10000000.01" }).success).toBe(false)
  })
  it("empty still reports 'Price is required' first (the message the form test relies on)", () => {
    expect(firstMessage(parse({ pricePounds: "" }), "pricePounds")).toBe("Price is required")
  })
  it("every accepted value maps to a safe integer number of pennies", () => {
    for (const v of ["0.01", "9999999.99", "10000000.00"]) {
      expect(parse({ pricePounds: v }).success).toBe(true)
      expect(Number.isSafeInteger(toPricePennies(v))).toBe(true)
    }
  })
})
