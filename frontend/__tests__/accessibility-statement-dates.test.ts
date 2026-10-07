/**
 * @jest-environment node
 *
 * The expiry gate for the published accessibility statement (LGL-02, D-12).
 *
 * WHAT THIS IS FOR. A conformance statement is a dated representation. The
 * failure mode it has is not being wrong on the day it is written — it is
 * being right on that day and staying published, unchanged, long after it
 * stopped being true. Nobody discovers that by reading it, because a stale
 * statement looks exactly like a fresh one. So the expiry is asserted here, and
 * the build reds on the day the statement goes out of date rather than whenever
 * somebody next happens to look.
 *
 * The same argument applies one level down: an exception list where the
 * remediation dates have all quietly passed is not a commitment any more, it is
 * a list. So an overdue `remediationBy` reds the build too.
 *
 * THIS GATE READS THE REAL CLOCK, DELIBERATELY. It would be easy to write these
 * assertions against a frozen date and watch them pass forever. They compare
 * against `Date.now()` precisely so that the passage of time is what makes them
 * fail — which is the only property that makes the gate worth having. The fail
 * direction was run by setting a past date and observing the real failure, not
 * by reasoning about it.
 *
 * NON-VACUITY. Every per-item assertion below is trivially satisfied by an
 * EMPTY list, and an empty exception list looks identical to a passing one in
 * the test output. The first test in the file therefore asserts the lists are
 * populated at all, before anything iterates over them.
 */
import {
  ACCESSIBILITY_STATEMENT as S,
  formatStatementDate,
} from "@/lib/accessibility-statement"

/** Parse an ISO date to a UTC timestamp, VOIDing rather than returning NaN. */
function isoToUtc(iso: string, what: string): number {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!m) throw new Error(`VOID: ${what} is not an ISO date: "${iso}"`)
  const t = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
  if (Number.isNaN(t)) throw new Error(`VOID: ${what} is not a real date: "${iso}"`)
  return t
}

/** Today at UTC midnight — the same resolution the declared dates carry. */
function todayUtc(): number {
  const n = new Date()
  return Date.UTC(n.getUTCFullYear(), n.getUTCMonth(), n.getUTCDate())
}

const DAY = 24 * 60 * 60 * 1000

describe("accessibility statement — the constant parsed at all", () => {
  it("NON-VACUITY CONTROL: the statement, its routes and its exceptions are all populated", () => {
    // Without this, every assertion in the rest of this file passes over an
    // empty array and reports green. An empty exception list is not a clean
    // bill of health; it is an unpublished one, and the two look the same.
    if (!S || typeof S !== "object") {
      throw new Error("VOID: the accessibility statement constant did not load")
    }
    expect(S.claim).toBe("partial")
    expect(S.standard).toBe("WCAG 2.2")
    expect(S.level).toBe("AA")

    expect(S.inScopeRoutes.length).toBeGreaterThan(0)
    expect(S.excludedSurfaces.length).toBeGreaterThan(0)
    expect(S.exceptions.length).toBeGreaterThan(0)

    // Sharper than "non-empty": the declared scope must actually name the
    // surfaces the claim is about, and the exclusion must actually be there.
    const paths = S.inScopeRoutes.map((r) => r.path)
    expect(paths).toContain("/shop/[slug]/checkout")
    expect(paths).toContain("/legal/accessibility")
  })

  it("covers the basket, the order confirmation, the per-shop order page and /track (#878 UXT-114)", () => {
    // These four are where the screen-reader persona's worst problems were, and
    // the statement used to stop at checkout. Each is asserted by its own entry,
    // so dropping one names which.
    const paths = S.inScopeRoutes.map((r) => r.path)
    expect(paths).toContain("/shop/[slug]/cart")
    expect(paths).toContain("/shop/[slug]/orders/[orderNumber]")
    expect(paths).toContain("/track")
    // The cash confirmation is a STATE of the checkout URL, not a route of its
    // own, so it is identified by its label rather than by a path.
    const confirmation = S.inScopeRoutes.filter((r) => /order confirmation/i.test(r.label))
    expect(confirmation.map((r) => r.path)).toEqual(["/shop/[slug]/checkout"])
  })

  it("names one standard throughout — the one the axe gate tests — and never the superseded one", () => {
    // The statement and its gate move together (e2e/public-a11y.spec.ts carries
    // wcag22aa). A stale "WCAG 2.1" left in any field would tell a reader two
    // different things about what was tested.
    expect(S.standard).toBe("WCAG 2.2")
    expect(JSON.stringify(S)).not.toContain("WCAG 2.1")
    // CONTROL: the serialisation is the real statement and does name the
    // standard it claims, so the absence above cannot pass over an empty value.
    expect(JSON.stringify(S)).toContain("WCAG 2.2")
  })
})

/**
 * A duplicate is how a list like this goes wrong quietly: a copied entry renders
 * twice and reads as two things, or two entries share an anchor id and the deep
 * link lands on the wrong one. The helper below is the instrument, and the first
 * test proves it can fail before the real lists are judged with it.
 */
function duplicates(values: readonly string[]): string[] {
  const seen = new Set<string>()
  const dupes = new Set<string>()
  for (const v of values) {
    if (seen.has(v)) dupes.add(v)
    seen.add(v)
  }
  return [...dupes]
}

describe("accessibility statement — every entry appears exactly once", () => {
  it("INSTRUMENT: the duplicate finder reports a duplicate when there is one", () => {
    expect(duplicates(["a", "b", "a"])).toEqual(["a"])
    expect(duplicates(["a", "b"])).toEqual([])
  })

  it("lists every exception id once", () => {
    expect(S.exceptions.length).toBeGreaterThan(0) // control
    expect(duplicates(S.exceptions.map((e) => e.id))).toEqual([])
  })

  it("lists every in-scope surface once — by its label, and by its path and label together", () => {
    // A path alone may legitimately repeat: the dish panel and the cash order
    // confirmation are STATES of a URL that is also listed for its main page.
    // What must never repeat is the surface itself, so the label is unique and
    // so is the (path, label) pair.
    expect(S.inScopeRoutes.length).toBeGreaterThan(0) // control
    expect(duplicates(S.inScopeRoutes.map((r) => r.label))).toEqual([])
    expect(duplicates(S.inScopeRoutes.map((r) => `${r.path} | ${r.label}`))).toEqual([])
  })

  it("lists each route once inside an exception", () => {
    const repeated = S.exceptions.filter((e) => duplicates(e.routes).length > 0).map((e) => e.id)
    expect(repeated).toEqual([])
  })

  it("only names routes the statement declares — in scope, or the excluded dashboard", () => {
    // A typo in an exception's route would publish a page that does not exist.
    const declared = new Set([...S.inScopeRoutes.map((r) => r.path), "/dashboard"])
    const unknown = S.exceptions.flatMap((e) => e.routes.filter((r) => !declared.has(r)).map((r) => `${e.id}: ${r}`))
    expect(unknown).toEqual([])
  })
})

describe("accessibility statement — the date parser", () => {
  it("VOIDs rather than skips on a date it cannot parse", () => {
    // Proves the instrument fails loudly on bad input rather than passing over
    // it. Without this, `isoToUtc` returning NaN would make every comparison
    // below silently false-y instead of red.
    expect(() => isoToUtc("not-a-date", "probe")).toThrow(/^VOID:/)
    expect(() => formatStatementDate("16/08/2026")).toThrow(/^VOID:/)
    // …and the same function succeeds on a real value, so the throws above are
    // not "this function always throws".
    expect(formatStatementDate("2026-08-15")).toBe("15 August 2026")
  })
})

describe("accessibility statement — the dates", () => {
  it("has not expired: nextReviewDue is still in the future", () => {
    // THE GATE. When this date passes, this line reds the build. That is the
    // whole point of the file.
    const due = isoToUtc(S.nextReviewDue, "nextReviewDue")
    const today = todayUtc()
    expect(due).toBeGreaterThan(today)
  })

  it("schedules the next review within 12 months of the last one", () => {
    const reviewed = isoToUtc(S.lastReviewedOn, "lastReviewedOn")
    const due = isoToUtc(S.nextReviewDue, "nextReviewDue")
    expect(due).toBeGreaterThan(reviewed)
    // 366 days covers a leap year without needing calendar arithmetic.
    expect(due - reviewed).toBeLessThanOrEqual(366 * DAY)
  })

  it("was prepared from evidence captured on or before the last review, and not in the future", () => {
    const prepared = isoToUtc(S.preparedOn, "preparedOn")
    const reviewed = isoToUtc(S.lastReviewedOn, "lastReviewedOn")
    expect(prepared).toBeLessThanOrEqual(reviewed)
    expect(prepared).toBeLessThanOrEqual(todayUtc())
  })
})

describe("accessibility statement — the exception list", () => {
  it("gives every exception a description, a reason and a remediation date", () => {
    // Counted rather than asserted per-item so the failure names HOW MANY are
    // wrong, and so the total is asserted in the same test — a zero count of
    // bad entries over zero entries is not a pass.
    expect(S.exceptions.length).toBeGreaterThan(0)

    const missingDate = S.exceptions.filter((e) => !e.remediationBy)
    const missingText = S.exceptions.filter(
      (e) => !e.description?.trim() || !e.reason?.trim() || !e.title?.trim()
    )

    expect(missingDate.map((e) => e.id)).toEqual([])
    expect(missingText.map((e) => e.id)).toEqual([])
  })

  it("has no overdue remediation date — an exception list with passed dates is decoration", () => {
    const today = todayUtc()
    const overdue = S.exceptions
      .filter((e) => !e.resolved)
      .filter((e) => isoToUtc(e.remediationBy, `${e.id}.remediationBy`) <= today)
      .map((e) => `${e.id} (due ${e.remediationBy})`)

    expect(overdue).toEqual([])
  })

  it("gives every exception a unique, stable id and a known category", () => {
    const ids = S.exceptions.map((e) => e.id)
    expect(new Set(ids).size).toBe(ids.length)
    expect(ids.every((id) => /^[a-z0-9-]+$/.test(id))).toBe(true)

    const allowed = new Set([
      "out-of-scope",
      "third-party",
      "known-defect",
      "published-information",
    ])
    const unknown = S.exceptions.filter((e) => !allowed.has(e.category))
    expect(unknown.map((e) => e.id)).toEqual([])
  })

  it("names the third-party surfaces a reader would otherwise assume are covered", () => {
    // These two sit INSIDE pages we do claim, so their absence from the list
    // would be read as coverage. Asserted by category so the entries cannot be
    // downgraded to prose elsewhere.
    const thirdParty = S.exceptions.filter((e) => e.category === "third-party")
    expect(thirdParty.length).toBeGreaterThanOrEqual(2)
  })

  it("does not name the dissolved company, on any field", () => {
    // The ACTIVE company is 16471464; a dissolved namesake exists and must
    // never appear. Asserted as an ABSENCE over the serialised constant,
    // because a presence check on the correct number passes either way.
    const serialised = JSON.stringify(S)
    expect(serialised).not.toContain("13434105")
    // CONTROL that the serialisation is the real statement, not an empty
    // object. It used to be the active number 16471464, which only the
    // registered-office exception carried; that entry was removed when the
    // address was published (31.1-27, #794), so the control is now every
    // exception's own description.
    expect(S.exceptions.length).toBeGreaterThan(0)
    for (const e of S.exceptions) expect(serialised).toContain(JSON.stringify(e.description))
  })
})
