/**
 * `lib/checkout-idempotency.ts` — the guest-checkout intent and its signature (PR #726, M3).
 *
 * The server refuses the same Idempotency-Key with a DIFFERENT body (422
 * `errors/idempotency-payload-mismatch`). The checkout page rotates its key whenever this
 * signature changes, so the signature must change for EVERY field the body carries — not just
 * the basket lines the first version signed — and must NOT change for a retyped-but-equivalent
 * value, or a pure retry would duplicate the order instead of replaying it.
 */
import {
  buildGuestOrderIntent,
  guestOrderIntentSignature,
  type GuestOrderFields,
} from "@/lib/checkout-idempotency"

const BASE: GuestOrderFields = {
  customerName: "Ade Johnson",
  customerEmail: "ade@example.com",
  customerPhone: "07700 900000",
  notes: "",
  fulfilmentType: "DELIVERY",
  address1: "12 Coldharbour Lane",
  address2: "",
  city: "London",
  postcode: "SW9 8LF",
  items: [{ productId: "p1", quantity: 1 }],
}

const sig = (overrides: Partial<GuestOrderFields> = {}) =>
  guestOrderIntentSignature(buildGuestOrderIntent({ ...BASE, ...overrides }))

describe("guestOrderIntentSignature — same payload, same key", () => {
  it("is deterministic for an identical payload (a pure retry replays)", () => {
    expect(sig()).toBe(sig())
  })

  it("ignores whitespace and postcode casing the wire body also normalises away", () => {
    expect(sig({ customerPhone: " 07700 900000 ", postcode: " sw9 8lf" })).toBe(sig())
  })

  it("treats blank notes and blank address2 as absent, exactly as the body does", () => {
    expect(sig({ notes: "   ", address2: "  " })).toBe(sig())
    expect(buildGuestOrderIntent({ ...BASE, notes: "  " }).notes).toBeUndefined()
  })
})

describe("guestOrderIntentSignature — any submitted field changes the key", () => {
  // One arm per field the POST body carries. A field missing from this table is a field that
  // could change the body without changing the key — which is the M3 defect.
  it.each<[string, Partial<GuestOrderFields>]>([
    ["customerName", { customerName: "Bola Johnson" }],
    ["customerEmail", { customerEmail: "bola@example.com" }],
    ["customerPhone", { customerPhone: "07700 900001" }],
    ["notes", { notes: "No chilli" }],
    ["fulfilmentType", { fulfilmentType: "COLLECTION" }],
    ["address1", { address1: "13 Coldharbour Lane" }],
    ["address2", { address2: "Flat 2" }],
    ["city", { city: "Brixton" }],
    ["postcode", { postcode: "SW9 8LG" }],
    ["basket quantity", { items: [{ productId: "p1", quantity: 2 }] }],
    ["basket line added", { items: [{ productId: "p1", quantity: 1 }, { productId: "p2", quantity: 1 }] }],
    ["basket line swapped", { items: [{ productId: "p2", quantity: 1 }] }],
  ])("changing only %s produces a different signature", (_field, overrides) => {
    expect(sig(overrides)).not.toBe(sig())
  })

  it("on a COLLECTION order the address is ABSENT from the body, so editing it does not churn the key", () => {
    const collection = { fulfilmentType: "COLLECTION" as const }
    const intent = buildGuestOrderIntent({ ...BASE, ...collection })
    expect(intent.addressLine1).toBeUndefined()
    expect(intent.addressPostcode).toBeUndefined()
    // Stale address state left over from the Delivery tab is not submitted, so it is not signed.
    expect(sig({ ...collection, address1: "somewhere else" })).toBe(sig(collection))
  })
})

describe("buildGuestOrderIntent — the body IS the signed object", () => {
  it("serialises to the flat GuestOrderRequest shape the server expects", () => {
    expect(JSON.parse(sig())).toEqual({
      customerName: "Ade Johnson",
      customerEmail: "ade@example.com",
      customerPhone: "07700 900000",
      fulfilmentType: "DELIVERY",
      addressLine1: "12 Coldharbour Lane",
      addressCity: "London",
      addressPostcode: "SW9 8LF",
      items: [{ productId: "p1", quantity: 1 }],
    })
  })
})
