/**
 * @jest-environment node
 *
 * Cross-language VAT-rate parity (COR-6, QA-council 20260902-134741).
 *
 * The UK VAT percentage table now exists TWICE and must never drift:
 *
 *   TypeScript  frontend/lib/vat.ts                 PERCENT = { STANDARD: 20, ... }
 *   Java        .../finance/VatCalculator.java      switch { case REDUCED -> 5; ... }
 *
 * The TypeScript copy exists because the checkout must PREVIEW the VAT before the order is
 * written; the Java copy is authoritative and is what reaches the HMRC-facing ledger. A silent
 * divergence means the customer is shown one VAT figure before paying and charged against
 * another — which is exactly the defect COR-6 was filed for, in its hardcoded-20% form.
 *
 * This gate reads BOTH files off disk. It carries POSITIVE CONTROLS first: a regex that stopped
 * matching would otherwise compare two empty sets and pass, which is the vacuous shape this
 * project's proof standards forbid. Anything unreadable or unparseable `throw`s with a message
 * beginning `VOID:` rather than skipping — missing input is never "clean".
 *
 * This file lives in `__tests__/` (outside app/ + components/) so its own literals are never
 * scanned by the static discipline gates.
 *
 * EXTERNAL AUTHORITY, re-fetched 2026-09-03 as the fix plan requires:
 *   https://www.gov.uk/vat-rates — standard 20%, reduced 5%, zero 0%; some supplies exempt.
 *   HMRC VAT Notice 709/1 (updated 2026-06-08) — hot takeaway standard-rated, most food
 *   zero-rated; its temporary reduced rate for children's meals ran 25 Jun – 1 Sep 2026 and had
 *   closed by the retrieval date.
 */
import fs from "fs"
import path from "path"

const REPO_ROOT = path.resolve(__dirname, "..", "..")
const TS_SOURCE = path.join(REPO_ROOT, "frontend", "lib", "vat.ts")
const JAVA_SOURCE = path.join(
  REPO_ROOT,
  "core-java",
  "src",
  "main",
  "java",
  "uk",
  "jtoye",
  "core",
  "finance",
  "VatCalculator.java",
)

/** The contract: exactly four rate categories, no more and no fewer. */
const EXPECTED_RATES = 4

function read(file: string): string {
  if (!fs.existsSync(file)) throw new Error(`VOID: ${file} does not exist`)
  const text = fs.readFileSync(file, "utf8")
  if (text.trim().length === 0) throw new Error(`VOID: ${file} is empty`)
  return text
}

/** Parse the TypeScript PERCENT record: `STANDARD: 20,` inside the PERCENT literal. */
function tsPercents(): Map<string, number> {
  const text = read(TS_SOURCE)
  const block = /const PERCENT: Record<VatRateName, number> = \{([\s\S]*?)\}/.exec(text)
  if (!block) throw new Error("VOID: could not locate the PERCENT table in lib/vat.ts")
  const out = new Map<string, number>()
  for (const m of block[1].matchAll(/([A-Z]+)\s*:\s*(\d+)\s*,/g)) {
    out.set(m[1], Number(m[2]))
  }
  return out
}

/** Parse the Java switch arms: `case ZERO, EXEMPT -> 0;` / `case REDUCED -> 5;`. */
function javaPercents(): Map<string, number> {
  const text = read(JAVA_SOURCE)
  const out = new Map<string, number>()
  for (const m of text.matchAll(/case\s+([A-Z, ]+?)\s*->\s*(\d+)\s*;/g)) {
    const pct = Number(m[2])
    for (const name of m[1].split(",").map((s) => s.trim()).filter(Boolean)) {
      out.set(name, pct)
    }
  }
  return out
}

describe("COR-6: the client VAT preview table matches VatCalculator", () => {
  it("POSITIVE CONTROL: both parsers find all four rates (a broken regex must not pass)", () => {
    const ts = tsPercents()
    const java = javaPercents()
    expect(ts.size).toBe(EXPECTED_RATES)
    expect(java.size).toBe(EXPECTED_RATES)
  })

  it("agrees on every rate's percentage", () => {
    const ts = tsPercents()
    const java = javaPercents()
    // Compared as sorted entry lists so a missing key on either side fails loudly rather than
    // being skipped by a one-directional loop.
    const asList = (m: Map<string, number>) =>
      [...m.entries()].sort(([a], [b]) => a.localeCompare(b))
    expect(asList(ts)).toEqual(asList(java))
  })

  it("pins the UK rates re-fetched from GOV.UK on 2026-09-03", () => {
    const java = javaPercents()
    expect(java.get("STANDARD")).toBe(20)
    expect(java.get("REDUCED")).toBe(5)
    expect(java.get("ZERO")).toBe(0)
    expect(java.get("EXEMPT")).toBe(0)
  })
})
