/**
 * G-37-6 defect 2 (37-56): every frontend sentence that ends with a vendor- or person-supplied
 * value ends it through `withFullStop` (lib/sentence-end.ts), so a name that already ends a
 * sentence ("Peckham Jollof Co.") is followed by one full stop, not two.
 *
 * Each call site has a case for a name ending in a full stop (fails on a template that appends
 * its own literal stop) and a case for an ordinary name (the good: today's copy, byte for byte).
 */
import fs from "fs"
import path from "path"

import { ALLERGY_NOTE_SENT_COPY } from "@/components/storefront/recorded-allergen-set"
import { copyResultSentence } from "@/components/dashboard/products/copy-to-shop-dialog"
import { alreadyVoidedCopy } from "@/components/dashboard/orders/void-order-dialog"
import { joinedToastTitle } from "@/lib/joined-toast"
import type { MyAccess } from "@/lib/shops-api"

const SHOP_ID = "aaaaaaaa-1111-1111-1111-111111111111"

function scopedAccess(businessName: string | null): MyAccess {
  return {
    userId: "kc-user-1",
    groupAdmin: false,
    grantedShopIds: [SHOP_ID],
    tenantWideRole: null,
    businessName,
  }
}

describe("call sites end a vendor-named sentence once (G-37-6)", () => {
  it("allergy note sent: a shop named 'Peckham Jollof Co.' gets one full stop", () => {
    expect(ALLERGY_NOTE_SENT_COPY("Peckham Jollof Co.")).toBe(
      "Your allergy note was sent to Peckham Jollof Co."
    )
  })

  it("allergy note sent: an ordinary shop name reads as today", () => {
    expect(ALLERGY_NOTE_SENT_COPY("Mama Ade's Kitchen")).toBe(
      "Your allergy note was sent to Mama Ade's Kitchen."
    )
  })

  it("already voided, no date: a voider named 'A. Smith Jr.' gets one full stop", () => {
    expect(alreadyVoidedCopy("A. Smith Jr.", null)).toBe(
      "This order was already voided by A. Smith Jr."
    )
  })

  it("already voided, no date: 'you' reads as today", () => {
    expect(alreadyVoidedCopy("you", null)).toBe("This order was already voided by you.")
  })

  it("already voided, with a date: the sentence ends with the date and one full stop", () => {
    expect(alreadyVoidedCopy("A. Smith Jr.", "2026-10-08T12:00:00Z")).toBe(
      "This order was already voided by A. Smith Jr. on 8 October 2026."
    )
  })

  it("joined toast: a business and a shop both named 'Peckham Jollof Co.' each end once", () => {
    expect(
      joinedToastTitle(scopedAccess("Peckham Jollof Co."), [
        { id: SHOP_ID, name: "Peckham Jollof Co." },
      ])
    ).toBe("You've joined Peckham Jollof Co. You can work on Peckham Jollof Co.")
  })

  it("joined toast: an ordinary business and shop read as today", () => {
    expect(
      joinedToastTitle(scopedAccess("Ade Foods Ltd"), [{ id: SHOP_ID, name: "Mama Ade's Kitchen" }])
    ).toBe("You've joined Ade Foods Ltd. You can work on Mama Ade's Kitchen.")
  })

  it("copy result: copied to a shop named 'Kitchen Ltd.' gets one full stop", () => {
    expect(copyResultSentence({ created: 2, skipped: 0, failed: 0, items: [] }, "Kitchen Ltd.")).toBe(
      "2 copied to Kitchen Ltd."
    )
  })

  it("copy result: nothing copied to a shop named 'Kitchen Ltd.' gets one full stop", () => {
    expect(copyResultSentence({ created: 0, skipped: 0, failed: 0, items: [] }, "Kitchen Ltd.")).toBe(
      "Nothing was copied to Kitchen Ltd."
    )
  })

  it("copy result: the skipped sentence still ends with its own words", () => {
    expect(copyResultSentence({ created: 0, skipped: 2, failed: 0, items: [] }, "Kitchen Ltd.")).toBe(
      "2 skipped because Kitchen Ltd. already has them."
    )
  })
})

/*
 * THE GUARD. withFullStop is the one way a sentence ends with a free-text value; this scan keeps
 * it that way. It lives in __tests__/ (outside the scanned trees, the palette-discipline
 * precedent) so its own pattern literals are never scanned, and it uses Node fs and a JavaScript
 * RegExp only: no child process, because grep on this machine is ugrep, where braces are
 * metacharacters.
 *
 * A finding is an interpolation — template `${…}` or JSX `{…}` — whose expression names free text
 * (FREE_TEXT_TOKENS, case-insensitive) and is followed IMMEDIATELY by a full stop that ends the
 * sentence: the stop is followed by whitespace, a backtick, a quote, a "<" or the end of the line.
 * A stop followed by anything else (kitchen.${shopId}.orders) is not a sentence end. Expressions
 * with nested braces are not matched; none of the call sites routed by 37-56 has one.
 */
const FRONTEND_ROOT = path.resolve(__dirname, "..")
const SCANNED_DIRS = ["app", "components", "lib", "hooks"]
/** The one place a full stop is appended after a value. */
const HELPER = "lib/sentence-end.ts"
const FREE_TEXT_TOKENS = ["name", "business", "shop", "vendor", "seller", "trader", "scope", "remaining", "reason"]
const FREE_TEXT = new RegExp(FREE_TEXT_TOKENS.join("|"), "i")
const VALUE_BEFORE_STOP = /\$?\{([^{}]*)\}\.(?=[\s`'"<]|$)/g

/** Platform-authored text that never ends in punctuation. Each entry must match exactly once. */
const ALLOWLIST: { file: string; expression: string; reason: string }[] = [
  {
    file: "app/legal/accessibility/page.tsx",
    expression: "surface.name",
    reason: "STATEMENT surfaces are constants authored in that file, none ending in punctuation",
  },
  {
    file: "app/shop/[slug]/checkout/page.tsx",
    expression: 'fulfilmentType === "COLLECTION" ? "shop" : "driver"',
    reason: "a ternary of two literal words, never free text",
  },
]

interface Finding {
  file: string
  line: number
  expression: string
}

/** Every free-text value placed immediately before a sentence-ending full stop on one line. */
function sentenceEndings(line: string): string[] {
  const trimmed = line.trimStart()
  if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) return []
  const found: string[] = []
  for (const m of line.matchAll(VALUE_BEFORE_STOP)) {
    if (FREE_TEXT.test(m[1])) found.push(m[1])
  }
  return found
}

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === "__tests__" || entry.name === "node_modules") continue
      sourceFiles(full, out)
    } else if (/\.tsx?$/.test(entry.name) && !/\.(test|spec)\./.test(entry.name)) {
      out.push(full)
    }
  }
  return out
}

function scan(): { files: number; findings: Finding[] } {
  const files = SCANNED_DIRS.flatMap((d) => sourceFiles(path.join(FRONTEND_ROOT, d)))
  const findings: Finding[] = []
  for (const full of files) {
    const file = path.relative(FRONTEND_ROOT, full).split(path.sep).join("/")
    if (file === HELPER) continue
    fs.readFileSync(full, "utf8")
      .split("\n")
      .forEach((text, i) => {
        for (const expression of sentenceEndings(text)) findings.push({ file, line: i + 1, expression })
      })
  }
  return { files: files.length, findings }
}

describe("guard: a free-text value ends a sentence only through withFullStop (G-37-6)", () => {
  it("planted controls: an unrouted template and JSX ending are found; routed, count and dotted are not", () => {
    expect(sentenceEndings("  return `Your order was sent to ${shopName}.`")).toEqual(["shopName"])
    expect(sentenceEndings("      <p>Prepared by {vendorName}.</p>")).toEqual(["vendorName"])
    expect(sentenceEndings("  return `Your order was sent to ${withFullStop(shopName)}`")).toEqual([])
    expect(sentenceEndings("  return `${count} copied.`")).toEqual([])
    expect(sentenceEndings("  return `${count}.`")).toEqual([])
    expect(sentenceEndings("  const topic = `/topic/kitchen.${tenantId}`")).toEqual([])
    expect(sentenceEndings("  const topic = `/topic/kitchen.${shopId}.orders`")).toEqual([])
    expect(sentenceEndings("   * Sent to ${shopName}. (a JSDoc line)")).toEqual([])
  })

  it("scans the source trees and finds the helper it exempts", () => {
    const { files } = scan()
    expect(files).toBeGreaterThan(100)
    expect(fs.existsSync(path.join(FRONTEND_ROOT, HELPER))).toBe(true)
  })

  it("every allowlist entry matches exactly once", () => {
    const { findings } = scan()
    const counts = ALLOWLIST.map(
      (a) =>
        `${a.file} ${a.expression}: ${findings.filter((f) => f.file === a.file && f.expression === a.expression).length}`
    )
    expect(counts).toEqual(ALLOWLIST.map((a) => `${a.file} ${a.expression}: 1`))
  })

  it("no free-text value outside the allowlist is followed by a literal sentence full stop", () => {
    const { findings } = scan()
    const unrouted = findings
      .filter((f) => !ALLOWLIST.some((a) => a.file === f.file && a.expression === f.expression))
      .map((f) => `${f.file}:${f.line} {${f.expression}}`)
    expect(unrouted).toEqual([])
  })
})
