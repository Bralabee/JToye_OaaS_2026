/**
 * @jest-environment node
 *
 * Cross-language allergen-table parity (Phase 31 · LGL-03 · D-03/D-04).
 *
 * The UK FSA 14-allergen bit table exists TWICE and must never drift:
 *
 *   TypeScript  frontend/types/api.ts            ALLERGENS = [{ bit, name }, ...]
 *   Java        .../product/AllergenCatalog.java new Allergen(bit, "Name"), ...
 *
 * The Java copy exists because D-04 puts the order-level aggregate on the KDS, which
 * is fed from the backend; the TypeScript copy is what the storefront and the vendor
 * form already render. A silent divergence between them means the kitchen and the
 * consumer are shown different allergen names for the same integer — on the one
 * surface in this product that can physically injure someone.
 *
 * This gate reads BOTH files off disk and compares all 14 bit->name pairs as ORDERED
 * lists. It deliberately carries a POSITIVE CONTROL first: a regex that stopped
 * matching would otherwise compare two empty lists and pass, which is the exact
 * vacuous shape this project's proof standards forbid. Any unreadable or unparseable
 * input `throw`s with a message beginning `VOID:` rather than skipping — missing
 * tooling is never "clean".
 *
 * This file lives in `__tests__/` (outside app/ + components/) so its own pattern
 * literals are never scanned by the static discipline gates.
 */
import fs from "fs"
import path from "path"

const REPO_ROOT = path.resolve(__dirname, "..", "..")
const TS_TABLE = path.join(REPO_ROOT, "frontend", "types", "api.ts")
const JAVA_TABLE = path.join(
  REPO_ROOT,
  "core-java",
  "src",
  "main",
  "java",
  "uk",
  "jtoye",
  "core",
  "product",
  "AllergenCatalog.java",
)

/** The contract: exactly the UK FSA 14, no more and no fewer. */
const EXPECTED_PAIRS = 14

interface Pair {
  bit: number
  name: string
}

function read(file: string, label: string): string {
  if (!fs.existsSync(file)) {
    throw new Error(`VOID: ${label} not found at ${file} — cannot compare tables`)
  }
  const src = fs.readFileSync(file, "utf8")
  if (src.trim().length === 0) {
    throw new Error(`VOID: ${label} at ${file} is empty — cannot compare tables`)
  }
  return src
}

/**
 * Extract `{ bit: N, name: "X" }` entries from the TypeScript ALLERGENS array.
 * Scoped to the array body rather than the whole file so an unrelated object
 * literal elsewhere in api.ts can never be mistaken for a table row.
 */
function extractTypeScriptPairs(): Pair[] {
  const src = read(TS_TABLE, "TypeScript allergen table")
  const start = src.indexOf("export const ALLERGENS")
  if (start === -1) {
    throw new Error(`VOID: no 'export const ALLERGENS' in ${TS_TABLE}`)
  }
  const open = src.indexOf("[", start)
  const close = src.indexOf("]", open)
  if (open === -1 || close === -1) {
    throw new Error(`VOID: unterminated ALLERGENS array in ${TS_TABLE}`)
  }
  const body = src.slice(open, close)
  const pairs: Pair[] = []
  const row = /\{\s*bit:\s*(\d+)\s*,\s*name:\s*"([^"]+)"\s*\}/g
  let m: RegExpExecArray | null
  while ((m = row.exec(body)) !== null) {
    pairs.push({ bit: Number(m[1]), name: m[2] })
  }
  return pairs
}

/**
 * Extract `new Allergen(N, "X")` entries from the Java catalogue. The Java table is
 * written in that structural shape precisely so this extraction reads a declaration
 * and not a comment — a comment-keyed table would let the two drift while the gate
 * stayed green.
 */
function extractJavaPairs(): Pair[] {
  const src = read(JAVA_TABLE, "Java allergen table")
  const pairs: Pair[] = []
  const row = /new\s+Allergen\(\s*(\d+)\s*,\s*"([^"]+)"\s*\)/g
  let m: RegExpExecArray | null
  while ((m = row.exec(src)) !== null) {
    pairs.push({ bit: Number(m[1]), name: m[2] })
  }
  return pairs
}

describe("allergen bit table parity: Java <-> TypeScript (LGL-03)", () => {
  // --- positive control -----------------------------------------------------
  // Runs FIRST and on its own. Without it, a broken regex on either side yields
  // [] === [] and the comparison below passes having compared nothing.

  it("POSITIVE CONTROL: the TypeScript extraction found exactly 14 pairs", () => {
    const ts = extractTypeScriptPairs()
    expect(ts.length).toBe(EXPECTED_PAIRS)
  })

  it("POSITIVE CONTROL: the Java extraction found exactly 14 pairs", () => {
    const java = extractJavaPairs()
    expect(java.length).toBe(EXPECTED_PAIRS)
  })

  // --- the contract ---------------------------------------------------------

  it("agrees on all 14 bit->name pairs, as ordered lists", () => {
    const ts = extractTypeScriptPairs()
    const java = extractJavaPairs()

    // Restated here so this assertion is not silently vacuous when read alone.
    expect(ts.length).toBe(EXPECTED_PAIRS)
    expect(java.length).toBe(EXPECTED_PAIRS)

    // Compared as rendered strings so a mismatch names the differing pair in the
    // failure output rather than dumping two object arrays.
    const render = (p: Pair[]) => p.map((x) => `${x.bit}=${x.name}`)
    expect(render(java)).toEqual(render(ts))
  })

  it("covers bits 0..13 with no gap and no duplicate, on both sides", () => {
    const ts = extractTypeScriptPairs()
    const java = extractJavaPairs()
    const expectedBits = Array.from({ length: EXPECTED_PAIRS }, (_, i) => i)

    expect(ts.map((p) => p.bit)).toEqual(expectedBits)
    expect(java.map((p) => p.bit)).toEqual(expectedBits)
    expect(new Set(ts.map((p) => p.name)).size).toBe(EXPECTED_PAIRS)
    expect(new Set(java.map((p) => p.name)).size).toBe(EXPECTED_PAIRS)
  })

  it("both tables carry the two most-often-misspelt names verbatim", () => {
    // `Molluscs` (not the US `Mollusks`) and `Lupin` (not `Lupine`) are the two the
    // UK FSA list is routinely mis-transcribed on.
    const names = (p: Pair[]) => p.map((x) => x.name)
    expect(names(extractTypeScriptPairs())).toEqual(expect.arrayContaining(["Molluscs", "Lupin"]))
    expect(names(extractJavaPairs())).toEqual(expect.arrayContaining(["Molluscs", "Lupin"]))
  })
})
