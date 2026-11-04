/**
 * #841 (contact plausibility) and #460 (delivery radius) on the customer side.
 *
 * The contact rules mirror core's ContactPlausibility case for case: the same fixtures are accepted and
 * refused on both sides, so the checkout never lets through what core refuses nor refuses what core takes.
 * Core stays the authority; this is the inline message before the round trip.
 */
import {
  isPlausibleEmail,
  isPlausibleName,
  isPlausiblePhone,
  CONTACT_COPY,
} from "../contact-plausibility"
import {
  DELIVERY_RADIUS_UNRECOGNISED_TYPE,
  DELIVERY_OUT_OF_RANGE_TYPE,
  deliveryRadiusFieldCopy,
  deliveryRadiusHintCopy,
  readDeliveryRadiusProblem,
} from "../delivery-radius"

describe("phone (mirrors core)", () => {
  it.each([
    "x", "no", "call me", "0", "1", "12", "0000000000", "07700 90012", "077009001234567",
    "+44 7700", "+0123456789", "phone: 07700900123", "<script>", "",
  ])("refuses %p", (p) => expect(isPlausiblePhone(p)).toBe(false))

  it.each([
    "07700900123", "07700 900 123", "(020) 7946 0958", "020-7946-0958", "+447700900123",
    "+44 7700 900123", "+44 (0)7700 900123", "00447700900123", "+353 85 123 4567", "+1 415 555 2671",
  ])("accepts %p", (p) => expect(isPlausiblePhone(p)).toBe(true))
})

// Review of PR 1122: core's ContactPlausibilityWhitespaceParityTest runs these same code points. JavaScript's
// \s (and trim) cover the Unicode spaces a phone pasted from a contact card carries; core must agree.
describe("phone whitespace (mirrors core's ContactPlausibilityWhitespaceParityTest)", () => {
  const accepted = [0x00a0, 0x202f, 0x2009, 0x3000, 0xfeff, 0x1680, 0x2000, 0x200a, 0x205f, 0x2028, 0x2029, 0x0009, 0x000b, 0x000c]
  it.each(accepted)("accepts U+%s as a separator and as padding", (cp) => {
    const space = String.fromCodePoint(cp)
    expect(isPlausiblePhone(`07700${space}900123`)).toBe(true)
    expect(isPlausiblePhone(`${space}07700 900123${space}`)).toBe(true)
  })
  it.each([0x200b, 0x0085, 0x00ad, 0x2060])("refuses U+%s: not whitespace to \\s", (cp) => {
    expect(isPlausiblePhone(`07700${String.fromCodePoint(cp)}900123`)).toBe(false)
  })
})

describe("email (mirrors core)", () => {
  it.each(["name@example", "name@", "@example.com", "name example@x.com", "a@b", "a@b.c", "plain", "a@@b.com", "a@b..com"])(
    "refuses %p", (e) => expect(isPlausibleEmail(e)).toBe(false))
  it.each(["ravi@example.com", "ravi.shah+orders@mail.example.co.uk", "o'brien@example.org"])(
    "accepts %p", (e) => expect(isPlausibleEmail(e)).toBe(true))
})

describe("name (mirrors core)", () => {
  it.each(["x", "0", "1", "12345", "--", "?!", " a "])("refuses %p", (n) => expect(isPlausibleName(n)).toBe(false))
  it.each(["Li", "Ng", "Ravi Shah", "O'Brien", "Zoë Müller", "李雷", "Mary-Jane Watson-Parker", "Jo"])(
    "accepts %p", (n) => expect(isPlausibleName(n)).toBe(true))
})

it("has a message for each field that names what to enter", () => {
  expect(CONTACT_COPY.name).toMatch(/name/i)
  expect(CONTACT_COPY.email).toMatch(/email/i)
  expect(CONTACT_COPY.phone).toMatch(/phone/i)
})

describe("delivery radius problems (#460)", () => {
  const outOfRange = {
    response: {
      status: 422,
      data: {
        type: DELIVERY_OUT_OF_RANGE_TYPE,
        code: "DELIVERY_OUT_OF_RANGE",
        detail: "This shop delivers within 10 km, and that address is about 530.2 km away. Choose collection, or a delivery address closer to the shop.",
        deliveryRadiusKm: 10,
        distanceKm: 530.2,
      },
    },
  }

  it("reads the out-of-range refusal with its typed members", () => {
    expect(readDeliveryRadiusProblem(outOfRange)).toEqual({
      kind: "OUT_OF_RANGE",
      deliveryRadiusKm: 10,
      distanceKm: 530.2,
    })
  })

  it("reads the unrecognised-postcode refusal", () => {
    expect(
      readDeliveryRadiusProblem({
        response: { status: 422, data: { type: DELIVERY_RADIUS_UNRECOGNISED_TYPE, deliveryRadiusKm: 10 } },
      }),
    ).toEqual({ kind: "POSTCODE_NOT_RECOGNISED", deliveryRadiusKm: 10, distanceKm: null })
  })

  it("tells a customer whose postcode cannot be placed to contact the shop, and still refuses it (2026-11-04 ruling)", () => {
    const p = { kind: "POSTCODE_NOT_RECOGNISED" as const, deliveryRadiusKm: 10, distanceKm: null }
    const withCollection = deliveryRadiusFieldCopy(p, true)
    expect(withCollection).toBe(
      "We couldn't place that postcode to check it is within 10 km. Check it, or choose collection. If it is correct, contact the shop to arrange delivery.",
    )
    expect(deliveryRadiusFieldCopy(p, false)).toContain("contact the shop")
    expect(deliveryRadiusFieldCopy({ kind: "OUT_OF_RANGE", deliveryRadiusKm: 10, distanceKm: 12.3 }, true)).not.toContain("contact the shop")
  })

  it("is null for any other problem", () => {
    expect(readDeliveryRadiusProblem({ response: { status: 422, data: { type: "https://jtoye.uk/errors/fulfilment-not-offered" } } })).toBeNull()
    expect(readDeliveryRadiusProblem({ response: { status: 500, data: {} } })).toBeNull()
    expect(readDeliveryRadiusProblem(new Error("network"))).toBeNull()
  })

  it("states the radius before the customer types a postcode, and says nothing when there is none", () => {
    expect(deliveryRadiusHintCopy(10)).toBe("This kitchen delivers within 10 km.")
    expect(deliveryRadiusHintCopy(7.5)).toBe("This kitchen delivers within 7.5 km.")
    expect(deliveryRadiusHintCopy(null)).toBeNull()
    expect(deliveryRadiusHintCopy(undefined)).toBeNull()
  })
})
