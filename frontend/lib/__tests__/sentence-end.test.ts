/**
 * The one TypeScript rule for ending a sentence with a value (G-37-6 defect 2, 37-56).
 *
 * A vendor-supplied name that already ends a sentence ("Peckham Jollof Co.") must not gain a
 * second full stop. The table below is literal on purpose: each row is one input a vendor or a
 * person can type, and the expected output is written out rather than derived.
 */
import {
  SENTENCE_TERMINATORS,
  TRAILING_SPACE_CODE_POINTS,
  endsSentence,
  withFullStop,
} from "@/lib/sentence-end"

/**
 * The trailing whitespace the rule drops, written out here independently of the module's own
 * declaration: the JavaScript whitespace class (WhiteSpace + LineTerminator) as code points.
 */
const EXPECTED_TRAILING_SPACE = [
  0x0009, 0x000a, 0x000b, 0x000c, 0x000d, 0x0020, 0x00a0, 0x1680, 0x2000, 0x2001, 0x2002,
  0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a, 0x2028, 0x2029, 0x202f,
  0x205f, 0x3000, 0xfeff,
]

const hex = (cp: number) => `U+${cp.toString(16).toUpperCase().padStart(4, "0")}`

describe("withFullStop — a sentence ends once (G-37-6)", () => {
  it("appends a full stop to a name that does not end a sentence", () => {
    expect(withFullStop("Mama Ade's Kitchen")).toBe("Mama Ade's Kitchen.")
  })

  it("keeps a name whose own full stop already ends the sentence", () => {
    expect(withFullStop("Peckham Jollof Co.")).toBe("Peckham Jollof Co.")
  })

  it("keeps a name ending with an exclamation mark", () => {
    expect(withFullStop("Yum!")).toBe("Yum!")
  })

  it("keeps a name ending with a question mark", () => {
    expect(withFullStop("Really?")).toBe("Really?")
  })

  it("keeps a name ending with an ellipsis character", () => {
    expect(withFullStop("Wait…")).toBe("Wait…")
  })

  it("drops a trailing space before deciding", () => {
    expect(withFullStop("Peckham Jollof Co. ")).toBe("Peckham Jollof Co.")
  })

  it("drops a trailing tab before appending", () => {
    expect(withFullStop("Mama Ade's Kitchen\t")).toBe("Mama Ade's Kitchen.")
  })

  it("drops a trailing no-break space before deciding", () => {
    expect(withFullStop("Peckham Jollof Co. ")).toBe("Peckham Jollof Co.")
  })

  it("gives a bare full stop for empty text", () => {
    expect(withFullStop("")).toBe(".")
  })

  it("treats a closing quote as text, not as a terminator", () => {
    expect(withFullStop('Joe\'s "Best!"')).toBe('Joe\'s "Best!".')
  })

  it("treats markup-like text as text", () => {
    expect(withFullStop("<b>Co.</b>")).toBe("<b>Co.</b>.")
  })

  it("decides on the last character only", () => {
    expect(withFullStop("Mid. Name Ltd")).toBe("Mid. Name Ltd.")
  })

  it("never repairs its input: two full stops stay two", () => {
    expect(withFullStop("Odd Name..")).toBe("Odd Name..")
  })

  it("declares the four terminators in order: full stop, !, ?, ellipsis", () => {
    expect(Array.from(SENTENCE_TERMINATORS)).toEqual([".", "!", "?", "…"])
  })

  it("drops exactly the 25 declared whitespace code points, over every code unit 0x0000-0xFFFF", () => {
    const declared = new Set<number>(TRAILING_SPACE_CODE_POINTS)
    const disagreements: string[] = []
    for (let cu = 0; cu <= 0xffff; cu++) {
      const dropped = withFullStop(`Co.${String.fromCharCode(cu)}`) === "Co."
      if (dropped !== declared.has(cu)) {
        disagreements.push(`${hex(cu)} ${dropped ? "dropped but not declared" : "declared but kept"}`)
      }
    }
    // The behaviour is checked first, so a drifted declaration is reported by the code unit
    // it gets wrong (e.g. "U+FEFF dropped but not declared"), then the literal list.
    expect(disagreements).toEqual([])
    expect([...TRAILING_SPACE_CODE_POINTS]).toEqual(EXPECTED_TRAILING_SPACE)

    // Near misses that must NOT be dropped (Java's Character.isWhitespace differs on some).
    for (const cu of [0x001c, 0x001d, 0x001e, 0x001f, 0x0085, 0x180e, 0x200b]) {
      expect(withFullStop(`Co.${String.fromCharCode(cu)}`)).toBe(`Co.${String.fromCharCode(cu)}.`)
    }
  })

  it("endsSentence is true exactly where withFullStop appends nothing", () => {
    const rows = [
      "Mama Ade's Kitchen",
      "Peckham Jollof Co.",
      "Yum!",
      "Really?",
      "Wait…",
      "Peckham Jollof Co. ",
      "Mama Ade's Kitchen\t",
      "Peckham Jollof Co. ",
      "",
      'Joe\'s "Best!"',
      "<b>Co.</b>",
      "Mid. Name Ltd",
      "Odd Name..",
    ]
    for (const row of rows) {
      expect(endsSentence(row)).toBe(withFullStop(row) === row.trimEnd())
    }
  })
})
