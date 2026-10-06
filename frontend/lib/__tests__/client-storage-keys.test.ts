/**
 * #840 — the storage-key registry is held against the CODE, not against itself.
 *
 * THE SOURCE SCAN. Every `.setItem(` call in `app/`, `components/`, `lib/` and
 * `hooks/` (tests, e2e and `*.test.*` / `*.spec.*` excluded) is found, its key
 * argument resolved, and the resolved key required to be declared in
 * `lib/client-storage-keys.ts` for that storage area. The cookie policy renders
 * its tables from that registry, so a key that reaches the code without a
 * registry entry is a key the policy does not disclose — and this test is what
 * turns that into a red build instead of a silent omission.
 *
 * HOW A KEY IS RESOLVED, in order:
 *   1. a string literal                          "jtoye-guest-orders"
 *   2. a template literal, reduced to its static prefix
 *                                                `jtoye-checkout-email-${slug}` -> "jtoye-checkout-email-"
 *   3. an identifier bound by `const X = <1|2|identifier>` in the same file, or
 *      imported (`import { X } from "@/..."`, aliases honoured) from a module
 *      in the scanned tree, followed to a literal (bounded depth)
 * Anything else — a function parameter, a computed expression, an unknown
 * receiver — is UNRESOLVABLE, and an unresolvable call site FAILS the test
 * unless it is named in CALL_SITE_KEYS below with the registry key(s) it
 * writes. Nothing is skipped silently; the map is the written record of every
 * place the scan cannot read for itself.
 *
 * Comments and string contents are blanked before the scan (a small lexer, not
 * a regex), so a comment or string that MENTIONS `localStorage.setItem(` is not a
 * call. The key argument itself is then read from the original text.
 *
 * FAIL DIRECTION (recorded in the 31.1-05 SUMMARY): a scratch file
 * `lib/__scan_fixture__.ts` writing an unregistered literal key turns
 * "every setItem call site writes a registered key" red, naming the file.
 */
import fs from "fs"
import path from "path"

import manifest from "../../../docs/retention-manifest.json"
import {
  CHECKOUT_EMAIL_KEY_PREFIX,
  CLIENT_STORAGE_KEYS,
  PERSONAL_KEYS_CLEARED_ON_SIGN_OUT,
  entryMatches,
  isRegisteredStorageKey,
  type StorageArea,
} from "@/lib/client-storage-keys"

const FRONTEND = path.resolve(__dirname, "..", "..")
const SCAN_ROOTS = ["app", "components", "lib", "hooks"]
const PRINT = process.env.STORAGE_SCAN_PRINT === "1"

/**
 * Call sites whose key the scan cannot resolve statically, each with the
 * registry key(s) it writes. Keyed by `<file>::<key expression as written>`.
 * An entry here that no longer matches a call site FAILS too (stale map).
 */
const CALL_SITE_KEYS: Record<string, { area: StorageArea; keys: string[]; why: string }> = {
  "lib/consent.ts::key": {
    area: "localStorage",
    keys: ["jtoye-cookie-notice-ack", "jtoye-cookie-consent-choices"],
    why: "writeRaw(key, value) — called only with COOKIE_NOTICE_ACK_KEY and CONSENT_CHOICES_KEY",
  },
  "hooks/use-stored-state.ts::key": {
    area: "localStorage",
    keys: ["jtoye-cart-<shop>"],
    why: "useStoredState(key) — its one caller, components/storefront/cart-provider.tsx, passes cartStorageKey(slug)",
  },
}

// ---------------------------------------------------------------- lexer

/** Replace comments with spaces and string/template CONTENTS with a placeholder, keeping offsets. */
function stripCommentsAndStrings(src: string): string {
  const out = src.split("")
  type Mode = "code" | "sq" | "dq" | "tpl" | "line" | "block"
  const stack: Mode[] = ["code"]
  const braceDepth: number[] = [] // for ${ } inside templates
  let i = 0
  const blank = (j: number) => {
    if (out[j] !== "\n") out[j] = " "
  }
  while (i < src.length) {
    const mode = stack[stack.length - 1]
    const c = src[i]
    const n = src[i + 1]
    if (mode === "code") {
      if (c === "/" && n === "/") { stack.push("line"); blank(i); blank(i + 1); i += 2; continue }
      if (c === "/" && n === "*") { stack.push("block"); blank(i); blank(i + 1); i += 2; continue }
      if (c === "'") { stack.push("sq"); i++; continue }
      if (c === '"') { stack.push("dq"); i++; continue }
      if (c === "`") { stack.push("tpl"); i++; continue }
      if (braceDepth.length && c === "{") braceDepth[braceDepth.length - 1]++
      if (braceDepth.length && c === "}") {
        if (braceDepth[braceDepth.length - 1] === 0) { braceDepth.pop(); stack.pop(); i++; continue }
        braceDepth[braceDepth.length - 1]--
      }
      i++
      continue
    }
    if (mode === "line") { if (c === "\n") stack.pop(); else blank(i); i++; continue }
    if (mode === "block") {
      if (c === "*" && n === "/") { blank(i); blank(i + 1); stack.pop(); i += 2; continue }
      blank(i); i++; continue
    }
    if (mode === "sq" || mode === "dq") {
      if (c === "\\") { blank(i); blank(i + 1); i += 2; continue }
      if ((mode === "sq" && c === "'") || (mode === "dq" && c === '"')) { stack.pop(); i++; continue }
      blank(i); i++; continue
    }
    // template
    if (c === "\\") { blank(i); blank(i + 1); i += 2; continue }
    if (c === "`") { stack.pop(); i++; continue }
    if (c === "$" && n === "{") { stack.push("code"); braceDepth.push(0); i += 2; continue }
    blank(i); i++
  }
  return out.join("")
}

/** The first argument of the call whose `(` is at `open`, as written (outer depth comma or `)`). */
function firstArgument(src: string, open: number): string {
  let depth = 0
  let mode: "code" | "sq" | "dq" | "tpl" = "code"
  for (let j = open + 1; j < src.length; j++) {
    const c = src[j]
    if (mode !== "code") {
      if (c === "\\") { j++; continue }
      if ((mode === "sq" && c === "'") || (mode === "dq" && c === '"') || (mode === "tpl" && c === "`")) mode = "code"
      continue
    }
    if (c === "'") mode = "sq"
    else if (c === '"') mode = "dq"
    else if (c === "`") mode = "tpl"
    else if (c === "(" || c === "[" || c === "{") depth++
    else if (c === ")" || c === "]" || c === "}") {
      if (depth === 0) return src.slice(open + 1, j).trim()
      depth--
    } else if (c === "," && depth === 0) return src.slice(open + 1, j).trim()
  }
  return src.slice(open + 1).trim()
}

// ------------------------------------------------------------- resolution

const fileCache = new Map<string, string>()
function readSource(rel: string): string | null {
  if (fileCache.has(rel)) return fileCache.get(rel)!
  const abs = path.join(FRONTEND, rel)
  if (!fs.existsSync(abs)) return null
  const text = fs.readFileSync(abs, "utf8")
  fileCache.set(rel, text)
  return text
}

/** "@/lib/x" -> "lib/x.ts" | "lib/x.tsx" | "lib/x/index.ts"; null when outside the tree. */
function resolveModule(spec: string): string | null {
  if (!spec.startsWith("@/")) return null
  const base = spec.slice(2)
  for (const cand of [`${base}.ts`, `${base}.tsx`, `${base}/index.ts`, `${base}/index.tsx`]) {
    if (fs.existsSync(path.join(FRONTEND, cand))) return cand
  }
  return null
}

const IDENT = /^[A-Za-z_$][\w$]*$/

/** A literal value, or the static prefix of a template literal (marked `prefix`). */
function literalOf(expr: string): { value: string; prefix: boolean } | null {
  const e = expr.trim()
  const sq = /^'((?:[^'\\]|\\.)*)'$/.exec(e)
  if (sq) return { value: sq[1], prefix: false }
  const dq = /^"((?:[^"\\]|\\.)*)"$/.exec(e)
  if (dq) return { value: dq[1], prefix: false }
  const tpl = /^`((?:[^`\\]|\\.)*)`$/.exec(e)
  if (tpl) {
    const body = tpl[1]
    const at = body.indexOf("${")
    if (at === -1) return { value: body, prefix: false }
    if (at === 0) return null // no static prefix to resolve against
    return { value: body.slice(0, at), prefix: true }
  }
  return null
}

function resolveIdentifier(file: string, ident: string, depth = 0): { value: string; prefix: boolean } | null {
  if (depth > 6) return null
  const src = readSource(file)
  if (src === null) return null
  const decl = new RegExp(`(?:^|\\n)\\s*(?:export\\s+)?const\\s+${ident}\\s*(?::[^=]+)?=\\s*([^\\n;]+)`).exec(src)
  if (decl) return resolveExpression(file, decl[1], depth + 1)
  const imports = src.matchAll(/import\s*(?:type\s*)?\{([^}]*)\}\s*from\s*["']([^"']+)["']/g)
  for (const m of imports) {
    for (const part of m[1].split(",")) {
      const [orig, alias] = part.trim().replace(/^type\s+/, "").split(/\s+as\s+/)
      if ((alias ?? orig)?.trim() === ident) {
        const target = resolveModule(m[2])
        if (target) return resolveIdentifier(target, orig.trim(), depth + 1)
      }
    }
  }
  return null
}

function resolveExpression(file: string, expr: string, depth = 0): { value: string; prefix: boolean } | null {
  const lit = literalOf(expr)
  if (lit) return lit
  const e = expr.trim().replace(/\s+as\s+const$/, "")
  if (IDENT.test(e)) return resolveIdentifier(file, e, depth)
  return null
}

// ----------------------------------------------------------------- scan

type CallSite = {
  file: string
  line: number
  area: StorageArea | null
  expr: string
  resolved: { value: string; prefix: boolean } | null
}

function walk(dir: string, acc: string[] = []): string[] {
  const abs = path.join(FRONTEND, dir)
  if (!fs.existsSync(abs)) return acc
  for (const ent of fs.readdirSync(abs, { withFileTypes: true })) {
    const rel = path.join(dir, ent.name)
    if (ent.isDirectory()) {
      if (["__tests__", "e2e", "node_modules", ".next"].includes(ent.name)) continue
      walk(rel, acc)
    } else if (/\.(ts|tsx)$/.test(ent.name) && !/\.(test|spec)\.(ts|tsx)$/.test(ent.name) && !ent.name.endsWith(".d.ts")) {
      acc.push(rel.split(path.sep).join("/"))
    }
  }
  return acc
}

function scan(): CallSite[] {
  const sites: CallSite[] = []
  for (const file of SCAN_ROOTS.flatMap((r) => walk(r))) {
    const raw = readSource(file)!
    const code = stripCommentsAndStrings(raw)
    for (const m of code.matchAll(/\.\s*setItem\s*\(/g)) {
      const dot = m.index!
      const open = dot + m[0].length - 1
      // The receiver as written, immediately left of the dot.
      const before = code.slice(Math.max(0, dot - 80), dot)
      const recv = /([A-Za-z_$][\w$.]*)\s*$/.exec(before)?.[1] ?? ""
      const area: StorageArea | null = /(^|\.)localStorage$/.test(recv)
        ? "localStorage"
        : /(^|\.)sessionStorage$/.test(recv)
          ? "sessionStorage"
          : null
      // Read the argument from the RAW text (strings intact) at the same offset.
      const expr = firstArgument(raw, open)
      sites.push({
        file,
        line: raw.slice(0, dot).split("\n").length,
        area,
        expr,
        resolved: resolveExpression(file, expr),
      })
    }
  }
  return sites
}

/** Does a resolved key belong to the registry in `area`? */
function declared(area: StorageArea, r: { value: string; prefix: boolean }): boolean {
  if (!r.prefix) return isRegisteredStorageKey(area, r.value)
  // A template's static prefix must BE a registered prefix entry, exactly.
  return CLIENT_STORAGE_KEYS.some((e) => e.area === area && e.match === "prefix" && e.name === r.value)
}

/** "jtoye-cart-<shop>" (a CALL_SITE_KEYS spelling) -> is it declared in `area`? */
function declaredSpelling(area: StorageArea, spelling: string): boolean {
  return spelling.endsWith("<shop>")
    ? declared(area, { value: spelling.slice(0, -"<shop>".length), prefix: true })
    : isRegisteredStorageKey(area, spelling)
}

let SITES: CallSite[] = []
beforeAll(() => {
  SITES = scan()
})

function inventory(): string {
  return SITES.map((s) => {
    const key = s.resolved ? s.resolved.value + (s.resolved.prefix ? "<shop>" : "") : `<unresolved: ${s.expr}>`
    return `${s.area ?? "<unknown area>"}\t${key}\t${s.file}:${s.line}`
  }).join("\n")
}

describe("#840 source scan — every setItem key is declared in the registry", () => {
  it("finds a real number of call sites (non-vacuity control)", () => {
    if (PRINT) console.info(`storage inventory (${SITES.length} call sites):\n${inventory()}`)
    // 31-11 measured 9 localStorage and 5 sessionStorage keys; the tree has more
    // call sites than keys (several keys are written from two places).
    expect(SITES.length).toBeGreaterThanOrEqual(14)
  })

  it("every setItem call site writes a registered key (unresolvable sites only via CALL_SITE_KEYS)", () => {
    const problems: string[] = []
    for (const s of SITES) {
      const where = `${s.file}:${s.line}`
      if (s.resolved && s.area) {
        if (!declared(s.area, s.resolved)) {
          problems.push(`${where} writes ${s.area} key "${s.resolved.value}${s.resolved.prefix ? "<shop>" : ""}", which lib/client-storage-keys.ts does not declare`)
        }
        continue
      }
      const mapped = CALL_SITE_KEYS[`${s.file}::${s.expr}`]
      if (!mapped) {
        problems.push(`${where} setItem(${s.expr}) — ${s.area ? "key" : "receiver"} not statically resolvable and not in CALL_SITE_KEYS`)
        continue
      }
      if (s.area && s.area !== mapped.area) problems.push(`${where} CALL_SITE_KEYS area ${mapped.area} != ${s.area}`)
      for (const k of mapped.keys) {
        if (!declaredSpelling(mapped.area, k)) problems.push(`${where} CALL_SITE_KEYS names "${k}", which the registry does not declare`)
      }
    }
    expect({ problems, inventory: problems.length ? inventory() : "(ok)" }).toEqual({ problems: [], inventory: "(ok)" })
  })

  it("every CALL_SITE_KEYS entry still matches a real call site (no stale map)", () => {
    const live = new Set(SITES.filter((s) => !(s.resolved && s.area)).map((s) => `${s.file}::${s.expr}`))
    expect(Object.keys(CALL_SITE_KEYS).filter((k) => !live.has(k))).toEqual([])
  })

  it("resolves at least the 9 localStorage and 5 sessionStorage keys 31-11 measured", () => {
    const keys: Record<StorageArea, Set<string>> = { localStorage: new Set(), sessionStorage: new Set() }
    for (const s of SITES) {
      if (s.resolved && s.area) keys[s.area].add(s.resolved.value + (s.resolved.prefix ? "<shop>" : ""))
      const mapped = CALL_SITE_KEYS[`${s.file}::${s.expr}`]
      if (mapped) mapped.keys.forEach((k) => keys[mapped.area].add(k))
    }
    if (PRINT) console.info(`resolved keys:\n${JSON.stringify({ localStorage: [...keys.localStorage].sort(), sessionStorage: [...keys.sessionStorage].sort() }, null, 1)}`)
    expect(keys.localStorage.size).toBeGreaterThanOrEqual(9)
    expect(keys.sessionStorage.size).toBe(5)
  })

  it("every registry entry is written by some call site (no over-disclosure)", () => {
    const written = (area: StorageArea, e: (typeof CLIENT_STORAGE_KEYS)[number]) =>
      SITES.some((s) => {
        if (s.area !== area) {
          const m = CALL_SITE_KEYS[`${s.file}::${s.expr}`]
          if (!m || m.area !== area) return false
        }
        if (s.resolved && s.area) {
          return s.resolved.prefix
            ? e.match === "prefix" && e.name === s.resolved.value
            : entryMatches(e, s.resolved.value)
        }
        const m = CALL_SITE_KEYS[`${s.file}::${s.expr}`]
        return !!m && m.keys.some((k) => (k.endsWith("<shop>") ? e.match === "prefix" && e.name === k.slice(0, -6) : entryMatches(e, k)))
      })
    expect(CLIENT_STORAGE_KEYS.filter((e) => !written(e.area, e)).map((e) => `${e.area}:${e.name}`)).toEqual([])
  })
})

describe("#840 registry semantics", () => {
  it("per-shop keys are matched by their prefix entry, in their own area only (adjacency)", () => {
    expect(isRegisteredStorageKey("localStorage", "jtoye-cart-rosies")).toBe(true)
    expect(isRegisteredStorageKey("localStorage", "jtoye-checkout-email-rosies")).toBe(true)
    expect(isRegisteredStorageKey("localStorage", `${CHECKOUT_EMAIL_KEY_PREFIX}peckham-jollof-co`)).toBe(true)
    // A bare prefix has no shop and is not a key anything writes.
    expect(isRegisteredStorageKey("localStorage", "jtoye-cart-")).toBe(false)
    // Same name, wrong area.
    expect(isRegisteredStorageKey("sessionStorage", "jtoye-cart-rosies")).toBe(false)
    expect(isRegisteredStorageKey("localStorage", "jtoye-track-email")).toBe(false)
    // Control: something genuinely unregistered.
    expect(isRegisteredStorageKey("localStorage", "jtoye-not-a-key")).toBe(false)
  })

  it("an explicit sign-out removes every personal key, and #840's named keys are among them", () => {
    const cleared = PERSONAL_KEYS_CLEARED_ON_SIGN_OUT
    const clearedMatches = (area: StorageArea, key: string) => cleared.some((e) => e.area === area && entryMatches(e, key))
    expect(CLIENT_STORAGE_KEYS.filter((e) => e.personal && !e.clearedOnSignOut).map((e) => e.name)).toEqual([])
    for (const key of [
      "jtoye-guest-orders",
      "jtoye-checkout-email-rosies",
      "jtoye-customer-last-signin",
      "jtoye-cart-rosies",
      "jtoye-customer-id",
      "jtoye-customer-logged-in",
      "jtoye-customer-expires-at",
    ]) {
      expect([key, clearedMatches("localStorage", key)]).toEqual([key, true])
    }
    // Control: a preference is NOT cleared on sign-out.
    expect(clearedMatches("localStorage", "theme")).toBe(false)
    expect(clearedMatches("localStorage", "jtoye-cookie-notice-ack")).toBe(false)
  })

  it("names are unique per area", () => {
    for (const area of ["localStorage", "sessionStorage"] as const) {
      const names = CLIENT_STORAGE_KEYS.filter((e) => e.area === area).map((e) => e.name)
      expect(new Set(names).size).toBe(names.length)
    }
  })
})

describe("#840 the retention schedule says what sign-out now does", () => {
  // The two local-storage email items an explicit sign-out now removes, by
  // their retention-schedule row.
  const ROWS_REMOVED_ON_SIGN_OUT: Record<string, string> = {
    "R-12": "jtoye-checkout-email-rosies",
    "R-13": "jtoye-guest-orders",
  }

  it("R-12 and R-13 publish 'until you sign out or clear', for keys the registry clears on sign-out", () => {
    for (const [id, key] of Object.entries(ROWS_REMOVED_ON_SIGN_OUT)) {
      const row = manifest.rows.find((r) => r.id === id)
      expect(row).toBeDefined()
      // The premise: the registry really does clear this key on sign-out.
      expect([id, PERSONAL_KEYS_CLEARED_ON_SIGN_OUT.some((e) => entryMatches(e, key))]).toEqual([id, true])
      expect([id, row!.period_display]).toEqual([id, "Until you sign out or clear your browser's site data"])
    }
  })
})
