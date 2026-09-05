/**
 * @jest-environment node
 *
 * Cross-language onboarding enum parity (INT-6, QA council 20260902-134741).
 *
 * The onboarding vocabulary exists TWICE and must never drift:
 *
 *   Java        .../onboarding/GateType.java, GateStatus.java, OnboardingState.java
 *   TypeScript  frontend/types/api.ts   export type GateType | GateStatus | OnboardingState
 *
 * The TypeScript unions are hand-maintained, and `Record<GateType, …>` copy maps are typed
 * against THEM — so when the union is a subset of the Java enum, tsc is satisfied and every
 * missing constant renders the literal fallback "Check" (INT-6: five of eight did). This gate
 * reads all four files off disk and compares each enum's constants as ORDERED lists.
 *
 * A POSITIVE CONTROL runs first: a regex that stopped matching would otherwise compare two
 * empty lists and pass — the vacuous shape this project's proof standards forbid. Unreadable
 * or unparseable input `throw`s with a message beginning `VOID:`; it never skips.
 *
 * Lives in `__tests__/` (outside app/ + components/) so its own pattern literals are never
 * scanned by the static discipline gates. Mirrors allergen-table-parity.test.ts.
 */
import fs from "fs"
import path from "path"

const REPO_ROOT = path.resolve(__dirname, "..", "..")
const TS_TYPES = path.join(REPO_ROOT, "frontend", "types", "api.ts")
const JAVA_DIR = path.join(REPO_ROOT, "core-java", "src", "main", "java", "uk", "jtoye", "core", "onboarding")

/** The contract, restated so a count regression is named and not silently absorbed. */
const EXPECTED = {
  GateType: 8,
  GateStatus: 5,
  OnboardingState: 9,
} as const

type EnumName = keyof typeof EXPECTED

function read(file: string, label: string): string {
  if (!fs.existsSync(file)) {
    throw new Error(`VOID: ${label} not found at ${file} — cannot compare enums`)
  }
  const src = fs.readFileSync(file, "utf8")
  if (src.trim().length === 0) {
    throw new Error(`VOID: ${label} at ${file} is empty — cannot compare enums`)
  }
  return src
}

/**
 * Constants of `public enum <name> { … }` in declaration order. Javadoc and line comments are
 * stripped first so a constant named only in prose can never be counted.
 */
function extractJavaEnum(name: EnumName): string[] {
  const raw = read(path.join(JAVA_DIR, `${name}.java`), `Java enum ${name}`)
  // Comments are stripped over the WHOLE file before the body is located: the constants'
  // Javadoc carries `{@code …}` / `{@link …}` tags, and locating the closing brace on the
  // raw text stopped at the first `}` inside one of those (measured: OnboardingState came
  // back with 5 of 9, cut at `{@code Shop.published = true}`).
  const src = raw.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "")
  const head = src.indexOf(`public enum ${name}`)
  if (head === -1) {
    throw new Error(`VOID: no 'public enum ${name}' in ${name}.java`)
  }
  const open = src.indexOf("{", head)
  const close = src.indexOf("}", open)
  if (open === -1 || close === -1) {
    throw new Error(`VOID: unterminated enum body in ${name}.java`)
  }
  const body = src.slice(open + 1, close)
  return body
    .split(/[,;]/)
    .map((t) => t.trim())
    .filter((t) => /^[A-Z][A-Z0-9_]*$/.test(t))
}

/** String-literal members of `export type <name> = "A" | "B" …` in declaration order. */
function extractTypeScriptUnion(name: EnumName): string[] {
  const src = read(TS_TYPES, "TypeScript api.ts")
  const head = src.indexOf(`export type ${name} =`)
  if (head === -1) {
    throw new Error(`VOID: no 'export type ${name} =' in api.ts`)
  }
  // The union ends at the next top-level declaration or a blank line.
  const rest = src.slice(head + `export type ${name} =`.length)
  const endMatch = rest.search(/\n\s*\n|\nexport\s/)
  const body = endMatch === -1 ? rest : rest.slice(0, endMatch)
  const out: string[] = []
  const lit = /"([A-Z][A-Z0-9_]*)"/g
  let m: RegExpExecArray | null
  while ((m = lit.exec(body)) !== null) {
    out.push(m[1])
  }
  return out
}

describe("onboarding enum parity: Java <-> TypeScript (INT-6)", () => {
  // --- positive controls ---------------------------------------------------
  it("POSITIVE CONTROL: the Java extraction finds 8 GateType, 5 GateStatus and 9 OnboardingState constants", () => {
    expect(extractJavaEnum("GateType")).toHaveLength(EXPECTED.GateType)
    expect(extractJavaEnum("GateStatus")).toHaveLength(EXPECTED.GateStatus)
    expect(extractJavaEnum("OnboardingState")).toHaveLength(EXPECTED.OnboardingState)
  })

  it("POSITIVE CONTROL: the TypeScript extraction finds a non-empty union for each of the three names", () => {
    expect(extractTypeScriptUnion("GateType").length).toBeGreaterThan(0)
    expect(extractTypeScriptUnion("GateStatus").length).toBeGreaterThan(0)
    expect(extractTypeScriptUnion("OnboardingState").length).toBeGreaterThan(0)
  })

  // --- the contract -----------------------------------------------------------
  it("GateType: TypeScript declares exactly the Java constants, in the same order", () => {
    const java = extractJavaEnum("GateType")
    expect(java).toHaveLength(EXPECTED.GateType)
    expect(extractTypeScriptUnion("GateType")).toEqual(java)
  })

  it("GateStatus: TypeScript declares exactly the Java constants, in the same order", () => {
    const java = extractJavaEnum("GateStatus")
    expect(java).toHaveLength(EXPECTED.GateStatus)
    expect(extractTypeScriptUnion("GateStatus")).toEqual(java)
  })

  it("OnboardingState: TypeScript declares exactly the Java constants, in the same order", () => {
    const java = extractJavaEnum("OnboardingState")
    expect(java).toHaveLength(EXPECTED.OnboardingState)
    expect(extractTypeScriptUnion("OnboardingState")).toEqual(java)
  })
})
