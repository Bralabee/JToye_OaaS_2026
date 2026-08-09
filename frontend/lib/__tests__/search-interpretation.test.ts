/**
 * The `X-Search-Interpretation` parser (issue 619, CUST-01).
 *
 * WHAT IS LOAD-BEARING HERE. The degradation matrix, not the happy path. This
 * parser is the only thing standing between "the server said proximity" and
 * "the page claimed proximity", so every arm below asserts that a MALFORMED,
 * INCOMPLETE or ABSENT disclosure lands on `text`. The happy-path arms exist as
 * the positive control for those: without them, a parser that returned
 * `{kind:"text"}` unconditionally would satisfy the whole degradation matrix.
 *
 * EXPECTATIONS ARE LITERALS. `"3.1 miles"` is written out, never derived from
 * the conversion constant in `lib/distance.ts`. 33-07 records why: an
 * expectation computed with the same constant the code uses passes for EVERY
 * factor including 1, so it cannot fail in the one direction it exists to catch.
 *
 * The constant is described rather than NAMED here on purpose. The acceptance
 * criterion for this file is that its identifier appears zero times — and a
 * comment stating the rule would have been the only match, making an
 * expected-zero read as one on a perfectly correct tree. A rule that fires on
 * its own definition is a recorded vacuity shape on this project.
 */

import {
  PROXIMITY_EXCLUSION_NOTE,
  SEARCH_INTERPRETATION_HEADER,
  formatPostcodeForDisplay,
  parseSearchInterpretation,
  searchSummary,
} from "@/lib/search-interpretation"

/** Exactly what 33-08 emits, copied from `33-08-SUMMARY.md`. */
const DISTRICT_HEADER = "proximity; postcode=SE22; precision=district; radiusKm=5.0"
const UNIT_HEADER = "proximity; postcode=SE155BS; precision=unit; radiusKm=5.0"

describe("parseSearchInterpretation — the happy path (the control for every absence below)", () => {
  it("reads the district form 33-08 ships, verbatim", () => {
    expect(parseSearchInterpretation(DISTRICT_HEADER)).toEqual({
      kind: "proximity",
      postcode: "SE22",
      precision: "district",
      radiusKm: 5,
    })
  })

  it("reads the unit form 33-08 ships, verbatim", () => {
    expect(parseSearchInterpretation(UNIT_HEADER)).toEqual({
      kind: "proximity",
      postcode: "SE155BS",
      precision: "unit",
      radiusKm: 5,
    })
  })

  it("tolerates padding the server does not currently emit", () => {
    expect(
      parseSearchInterpretation(
        "  proximity ;  postcode=SE22 ;   precision=district ;  radiusKm=5.0  "
      )
    ).toEqual({ kind: "proximity", postcode: "SE22", precision: "district", radiusKm: 5 })
  })

  it("does not care what order the pairs arrive in", () => {
    expect(
      parseSearchInterpretation("proximity; radiusKm=5.0; precision=district; postcode=SE22")
    ).toEqual({ kind: "proximity", postcode: "SE22", precision: "district", radiusKm: 5 })
  })

  it("exposes the header name lower-cased, because axios lower-cases its keys", () => {
    expect(SEARCH_INTERPRETATION_HEADER).toBe("x-search-interpretation")
  })
})

describe("parseSearchInterpretation — every unparseable input degrades to text", () => {
  it.each([
    ["null", null],
    ["undefined", undefined],
    ["an empty string", ""],
    ["whitespace only", "   "],
    ["the literal text form", "text"],
    ["an unknown kind", "semantic; postcode=SE22; precision=district; radiusKm=5.0"],
    ["a capitalised kind the server never emits", "Proximity; postcode=SE22; precision=district; radiusKm=5.0"],
    ["a missing radiusKm", "proximity; postcode=SE22; precision=district"],
    ["an empty radiusKm", "proximity; postcode=SE22; precision=district; radiusKm="],
    ["a non-numeric radiusKm", "proximity; postcode=SE22; precision=district; radiusKm=abc"],
    ["a non-finite radiusKm", "proximity; postcode=SE22; precision=district; radiusKm=Infinity"],
    ["a zero radiusKm", "proximity; postcode=SE22; precision=district; radiusKm=0"],
    ["a negative radiusKm", "proximity; postcode=SE22; precision=district; radiusKm=-5"],
    ["a missing precision", "proximity; postcode=SE22; radiusKm=5.0"],
    ["an unknown precision", "proximity; postcode=SE22; precision=borough; radiusKm=5.0"],
    ["a missing postcode", "proximity; precision=district; radiusKm=5.0"],
    ["an empty postcode", "proximity; postcode=; precision=district; radiusKm=5.0"],
    ["a lower-case postcode the server never emits", "proximity; postcode=se22; precision=district; radiusKm=5.0"],
    ["a postcode with a space the server strips", "proximity; postcode=SE15 5BS; precision=unit; radiusKm=5.0"],
    ["a postcode longer than the server's 8-character key", "proximity; postcode=SE155BSSS; precision=unit; radiusKm=5.0"],
    ["a postcode shorter than the server's 2-character floor", "proximity; postcode=S; precision=district; radiusKm=5.0"],
    ["a value carrying an embedded newline", "proximity; postcode=SE22\nSet-Cookie: x=1; precision=district; radiusKm=5.0"],
    ["a value carrying an embedded carriage return", "proximity; postcode=SE22\r; precision=district; radiusKm=5.0"],
    ["a bare semicolon", ";"],
    ["pairs with no kind at all", "postcode=SE22; precision=district; radiusKm=5.0"],
  ])("degrades to text on %s", (_label, raw) => {
    expect(parseSearchInterpretation(raw as string | null | undefined)).toEqual({ kind: "text" })
  })

  it("PARSER FAIL-SAFE ARM: an incomplete disclosure is not a disclosure", () => {
    // The plan names this one explicitly. `postcode` alone would be enough to
    // write a heading with; refusing it is the point.
    expect(parseSearchInterpretation("proximity; postcode=SE22")).toEqual({ kind: "text" })
  })
})

describe("formatPostcodeForDisplay — formatting only; it invents no claim", () => {
  it("renders a district key exactly as the server sent it", () => {
    expect(formatPostcodeForDisplay("SE22", "district")).toBe("SE22")
  })

  it("restores the space in a full unit key", () => {
    expect(formatPostcodeForDisplay("SE155BS", "unit")).toBe("SE15 5BS")
  })

  it("restores the space in a short-outward unit key", () => {
    expect(formatPostcodeForDisplay("M11AE", "unit")).toBe("M1 1AE")
  })

  it("leaves a key too short to be a unit alone rather than mangling it", () => {
    expect(formatPostcodeForDisplay("M1A", "unit")).toBe("M1A")
  })

  it("never inserts a space into a district key, however long", () => {
    expect(formatPostcodeForDisplay("SW1A", "district")).toBe("SW1A")
  })
})

describe("searchSummary — the text branch renders exactly today's copy", () => {
  it("keeps the plural form", () => {
    const s = searchSummary({ kind: "text" }, 2, "jollof")
    expect(s.kind).toBe("text")
    expect(s.text).toBe("2 kitchens for “jollof”")
  })

  it("keeps the singular form", () => {
    expect(searchSummary({ kind: "text" }, 1, "jollof").text).toBe("1 kitchen for “jollof”")
  })

  it("keeps the zero-result form", () => {
    expect(searchSummary({ kind: "text" }, 0, "jollof").text).toBe("No kitchens match “jollof”")
  })

  it("trims the term the way the island trims it before sending", () => {
    const s = searchSummary({ kind: "text" }, 2, "  jollof  ")
    expect(s.kind === "text" && s.term).toBe("jollof")
  })
})

describe("searchSummary — the proximity branch, in miles, from the radius actually applied", () => {
  const DISTRICT = {
    kind: "proximity",
    postcode: "SE22",
    precision: "district",
    radiusKm: 5,
  } as const

  it("states the count, the radius in miles and the district", () => {
    // LITERAL, not derived: 5 km is 3.1 miles. A tidier "3 miles" would be
    // 4.83 km — a radius nothing applied (33-07's recorded rule).
    expect(searchSummary(DISTRICT, 3, "SE22").text).toBe("3 kitchens within 3.1 miles of SE22")
  })

  it("uses the singular for one kitchen", () => {
    expect(searchSummary(DISTRICT, 1, "SE22").text).toBe("1 kitchen within 3.1 miles of SE22")
  })

  it("says so plainly when nothing is inside the radius", () => {
    expect(searchSummary(DISTRICT, 0, "SE22").text).toBe("No kitchens within 3.1 miles of SE22")
  })

  it("renders a unit key with its space restored", () => {
    expect(
      searchSummary(
        { kind: "proximity", postcode: "SE155BS", precision: "unit", radiusKm: 5 },
        1,
        "SE15 5BS"
      ).text
    ).toBe("1 kitchen within 3.1 miles of SE15 5BS")
  })

  it("quotes the radius the SERVER applied, not a hardcoded one", () => {
    // 8 km is 5.0 miles. If the copy carried a literal "3.1 miles" this arm reds.
    expect(
      searchSummary(
        { kind: "proximity", postcode: "SE22", precision: "district", radiusKm: 8 },
        2,
        "SE22"
      ).text
    ).toBe("2 kitchens within 5.0 miles of SE22")
  })

  it("never echoes the raw query term on the proximity branch", () => {
    // T-33-09-05: the postcode rendered comes from the server's key, not from
    // whatever the customer typed.
    const s = searchSummary(DISTRICT, 3, "se 22 <script>")
    expect(s.text).not.toContain("<script>")
    expect(s.text).toContain("SE22")
  })
})

describe("the exclusion disclosure (D-D)", () => {
  it("is generic and states no count, because no count was measured", () => {
    expect(PROXIMITY_EXCLUSION_NOTE).toBe(
      "Kitchens we cannot place, and any further away, are not shown."
    )
    expect(PROXIMITY_EXCLUSION_NOTE).not.toMatch(/\d/)
  })
})
