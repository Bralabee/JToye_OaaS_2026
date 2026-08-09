/**
 * The kilometres-to-miles conversion the customer-facing surfaces render through
 * (issue 460, CUST-01; the miles correction from 33-07's human gate).
 *
 * EVERY EXPECTATION BELOW IS A HARDCODED STRING, NEVER ONE COMPUTED FROM
 * `MILES_PER_KM`. That is the whole point of the file. A test written as
 * `expect(formatMiles(5)).toBe(`${(5 * MILES_PER_KM).toFixed(1)} miles`)` passes
 * for ANY value of the constant, including 1 — it re-states the implementation
 * instead of checking it, and the defect it exists to catch (kilometres reaching
 * the customer unconverted) is exactly the one it would sail past.
 *
 * Measured, 33-07: setting `MILES_PER_KM` to 1 reds five of the assertions here
 * and two more in `near-you-row.test.tsx`.
 */

import { MILES_PER_KM, kmToMiles, formatMiles } from "@/lib/distance"

describe("kmToMiles", () => {
  it("converts a kilometre to the internationally defined mile", () => {
    // 1 mile is exactly 1.609344 km by definition, so this many kilometres is
    // one mile — checked against 1, not against the constant restated.
    expect(kmToMiles(1.609344)).toBeCloseTo(1, 6)
    expect(kmToMiles(0)).toBe(0)
  })

  it("is the reciprocal of the defining constant to six decimal places", () => {
    expect(MILES_PER_KM).toBeCloseTo(1 / 1.609344, 6)
  })

  it("preserves order, so the figure shown cannot disagree with the ranking", () => {
    // 33-06 orders by distanceKm and the card prints a CONVERSION of it. A
    // conversion that reordered anything would make the nearest shop print a
    // larger number than the second — this is what rules that out.
    const km = [0.097, 0.271, 0.417, 3.01, 3.183, 260]
    const miles = km.map(kmToMiles)
    expect([...miles].sort((a, b) => a - b)).toEqual(miles)
  })
})

describe("formatMiles", () => {
  it.each([
    // The two real distances 33-06 returned from Peckham and Brixton.
    [0.2707795900623579, "0.2 miles"],
    [3.0104, "1.9 miles"],
    [0.097, "0.1 miles"],
    // The radius the row asks for: 5 km, which the copy must quote as 3.1 miles
    // and never as "3 miles" — 3 miles is 4.83 km, a radius nothing applied.
    [5, "3.1 miles"],
    // Above ten miles the decimal is dropped: it is well past the point where a
    // tenth of a mile tells a customer anything.
    [16.0, "9.9 miles"],
    [16.1, "10 miles"],
    [260, "162 miles"],
  ])("renders %p km as %p", (km, expected) => {
    expect(formatMiles(km)).toBe(expected)
  })

  it("does NOT print the kilometre figure with a miles label", () => {
    // The failure this correction exists to prevent, stated as its own case: a
    // dropped conversion would relabel 3.0104 as "3.0 miles" — a number that is
    // wrong by 61% and looks completely plausible.
    expect(formatMiles(3.0104)).not.toBe("3.0 miles")
    expect(formatMiles(5)).not.toBe("5.0 miles")
  })

  it("never labels a distance in kilometres", () => {
    // Paired with the positive assertions above, so this absence is evidence:
    // the same function demonstrably produces a matchable string.
    for (const km of [0.097, 0.271, 3.01, 260]) {
      expect(formatMiles(km)).toMatch(/^\d+(\.\d)? miles$/)
      expect(formatMiles(km)).not.toMatch(/km/)
    }
  })
})
