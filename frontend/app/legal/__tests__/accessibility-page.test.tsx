/**
 * The published conformance statement (LGL-02, D-12).
 *
 * WHAT THIS FILE IS DEFENDING. A conformance statement fails in ways that leave
 * every other gate green. It can overclaim — "fully" is one word away from
 * "partially" and no type checker cares. It can silently omit an exception that
 * is still sitting in the data, so the gate counting exceptions stays happy
 * while the page a regulator reads is wrong. It can publish a contact link
 * built from an empty string, which renders as a link that goes nowhere and
 * looks exactly like a working one. None of those is visible in a screenshot.
 *
 * NON-VACUITY FIRST, ALWAYS. This page is a long run of prose, and a broken
 * render produces a page with headings and no content — which satisfies every
 * `not.toContain` assertion in this file trivially. So the section count is
 * asserted BEFORE anything looks at content, and the exception count is
 * asserted against the constant rather than against a number typed here: a
 * hardcoded expected count silently becomes correct again the moment someone
 * edits both the page and the number, which is precisely the edit that drops an
 * exception.
 *
 * THE OVERCLAIM ASSERTION IS AN ABSENCE. Asserting the presence of "partially"
 * passes just as well on a page that says both words. Absence is the assertion
 * that can actually fail, and it is written against the rendered text rather
 * than the source so a comment cannot satisfy it.
 */
import fs from "node:fs"
import path from "node:path"
import { render, within } from "@testing-library/react"
import "@testing-library/jest-dom"
import AccessibilityStatementPage from "@/app/legal/accessibility/page"
import { metadata } from "@/app/legal/accessibility/page"
import { ACCESSIBILITY_STATEMENT as STATEMENT } from "@/lib/accessibility-statement"

const DPO_VAR = "NEXT_PUBLIC_DATA_PROTECTION_EMAIL"
const OFFICE_VAR = "NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE"
const SAMPLE_EMAIL = "privacy@example.test"

const savedDpo = process.env[DPO_VAR]
const savedOffice = process.env[OFFICE_VAR]

afterEach(() => {
  if (savedDpo === undefined) delete process.env[DPO_VAR]
  else process.env[DPO_VAR] = savedDpo
  if (savedOffice === undefined) delete process.env[OFFICE_VAR]
  else process.env[OFFICE_VAR] = savedOffice
})

const SOURCE = fs.readFileSync(
  path.join(process.cwd(), "app/legal/accessibility/page.tsx"),
  "utf8"
)

/** The page's own <main>, so the shared footer's headings are never counted. */
function renderPage() {
  const view = render(<AccessibilityStatementPage />)
  const main = view.container.querySelector("main")
  if (!main) throw new Error("VOID: the page rendered no main landmark")
  return { ...view, main }
}

describe("accessibility statement page — non-vacuity", () => {
  it("CONTROL: renders the six required sections inside main before anything else is asserted", () => {
    const { main } = renderPage()
    const h2s = within(main).getAllByRole("heading", { level: 2 })
    // Six sections are contracted; a page that failed to render its body would
    // still produce a title and chrome, and every absence assertion below would
    // pass over it.
    expect(h2s.length).toBeGreaterThanOrEqual(6)
    expect(within(main).getAllByRole("heading", { level: 1 })).toHaveLength(1)
  })

  it("CONTROL: renders the six sections in the contracted order", () => {
    const { main } = renderPage()
    const text = within(main)
      .getAllByRole("heading", { level: 2 })
      .map((h) => h.textContent?.trim())
    expect(text.slice(0, 6)).toEqual([
      "Conformance status",
      "Scope of this statement",
      "Known exceptions",
      "Dates and review",
      "Feedback and contact",
      "Enforcement procedure",
    ])
  })
})

describe("accessibility statement page — the claim", () => {
  it("claims PARTIAL conformance and never full conformance", () => {
    const { main } = renderPage()
    const text = main.textContent ?? ""
    expect(text.length).toBeGreaterThan(500) // control: there is prose to search
    expect(text).toContain("partially conformant")
    // The assertion that can actually fail. Case-insensitive, because the
    // overclaim would read just as badly capitalised.
    expect(text.toLowerCase()).not.toContain("fully conformant")
    expect(text.toLowerCase()).not.toContain("fully compliant")
  })

  it("names the standard and level it is claiming against", () => {
    const { main } = renderPage()
    const text = main.textContent ?? ""
    expect(text).toContain(STATEMENT.standard)
    expect(text).toContain(`level ${STATEMENT.level}`)
  })

  it("does not name the dissolved company anywhere on the page", () => {
    const { main } = renderPage()
    const text = main.textContent ?? ""
    // Presence of the ACTIVE number passes either way; the absence is the check.
    expect(text).toContain("16471464")
    expect(text).not.toContain("13434105")
  })
})

describe("accessibility statement page — scope", () => {
  it("states the in-scope routes as URLs", () => {
    const { main } = renderPage()
    const text = main.textContent ?? ""
    expect(STATEMENT.inScopeRoutes.length).toBeGreaterThan(0) // control
    for (const route of STATEMENT.inScopeRoutes) {
      expect(text).toContain(route.path)
    }
  })

  it("names the excluded vendor dashboard explicitly", () => {
    const { main } = renderPage()
    const text = (main.textContent ?? "").toLowerCase()
    expect(text).toContain("vendor dashboard")
    expect(text).toContain("not covered")
  })
})

describe("accessibility statement page — the exception list", () => {
  it("renders exactly as many exceptions as the constant declares", () => {
    const { main } = renderPage()
    const rendered = main.querySelectorAll("[data-exception-id]")
    // Asserted against the constant, never against a number typed here: a
    // literal would have to be edited to drop an exception, and whoever drops
    // one would edit it.
    expect(STATEMENT.exceptions.length).toBeGreaterThan(0) // control
    expect(rendered.length).toBe(STATEMENT.exceptions.length)
  })

  it("publishes every exception's id, description and remediation date", () => {
    const { main } = renderPage()
    const text = main.textContent ?? ""
    const missing: string[] = []
    for (const e of STATEMENT.exceptions) {
      if (!main.querySelector(`[data-exception-id="${e.id}"]`)) missing.push(e.id)
      if (!text.includes(e.title)) missing.push(`${e.id}:title`)
      if (!text.includes(e.description)) missing.push(`${e.id}:description`)
    }
    expect(missing).toEqual([])
    // Every entry carries a date sentence — the "in due course" D-12 forbids.
    const dueCount = (text.match(/We expect to address this by/g) ?? []).length
    expect(dueCount).toBe(STATEMENT.exceptions.length)
  })

  it("carries the registered-office exception with its company number", () => {
    const { main } = renderPage()
    const entry = main.querySelector(
      '[data-exception-id="registered-office-not-published"]'
    )
    expect(entry).not.toBeNull()
    expect(entry?.textContent).toContain("16471464")
    expect(entry?.textContent).not.toContain("13434105")
  })
})

describe("accessibility statement page — the contact route", () => {
  it("renders a resolvable mailto when the address is configured", () => {
    process.env[DPO_VAR] = SAMPLE_EMAIL
    const { main } = renderPage()
    const link = within(main).getByRole("link", { name: SAMPLE_EMAIL })
    expect(link).toHaveAttribute("href", `mailto:${SAMPLE_EMAIL}`)
  })

  it("FALLBACK: with no contact configured it names the routes that DO exist, and emits no empty mailto", () => {
    delete process.env[DPO_VAR]
    delete process.env[OFFICE_VAR]
    const { main } = renderPage()

    const hrefs = Array.from(main.querySelectorAll("a[href]")).map((a) =>
      a.getAttribute("href")
    )
    // The defect this guards: `mailto:` built from an empty string renders a
    // link that looks live and goes nowhere.
    expect(hrefs).not.toContain("mailto:")
    expect(hrefs.filter((h) => h === "mailto:" || h === "mailto:undefined")).toEqual([])

    // …and the block degrades to real routes rather than disappearing.
    expect(hrefs).toContain("/legal/privacy")
    expect(hrefs).toContain("/legal")
    expect((main.textContent ?? "").toLowerCase()).toContain(
      "not yet published a dedicated accessibility contact"
    )
  })

  it("never emits an empty mailto in EITHER configuration", () => {
    for (const configured of [true, false]) {
      if (configured) process.env[DPO_VAR] = SAMPLE_EMAIL
      else delete process.env[DPO_VAR]
      const { main, unmount } = renderPage()
      const bad = Array.from(main.querySelectorAll("a[href]"))
        .map((a) => a.getAttribute("href") ?? "")
        .filter((h) => /^mailto:\s*$/.test(h))
      expect(bad).toEqual([])
      unmount()
    }
  })

  it("does not publish an API endpoint as a consumer contact route", () => {
    process.env[DPO_VAR] = SAMPLE_EMAIL
    const { main } = renderPage()
    const text = main.textContent ?? ""
    const hrefs = Array.from(main.querySelectorAll("a[href]")).map(
      (a) => a.getAttribute("href") ?? ""
    )
    // These are endpoints, not pages. No frontend form exists behind them.
    expect(text).not.toContain("/api/v1/public/gdpr/dsar")
    expect(hrefs.some((h) => h.includes("/api/"))).toBe(false)
  })
})

describe("accessibility statement page — enforcement and metadata", () => {
  it("names the UK enforcement route", () => {
    const { main } = renderPage()
    const text = main.textContent ?? ""
    expect(text).toContain("Equality and Human Rights Commission")
    expect(text).toContain("Equality Advisory and Support Service")
  })

  it("carries its own title, description and canonical", () => {
    expect(metadata.title).toMatch(/accessibility statement/i)
    expect(String(metadata.description ?? "")).toMatch(/conformance|exceptions/i)
    expect(metadata.alternates?.canonical).toBe("/legal/accessibility")
  })
})

describe("accessibility statement page — dates come from the constant", () => {
  it("renders the declared dates, formatted", () => {
    const { main } = renderPage()
    const text = main.textContent ?? ""
    // 2026-08-15 -> "15 August 2026". Derived here from the constant, so the
    // assertion cannot drift from the source of truth.
    const long = (iso: string) => {
      const [y, m, d] = iso.split("-")
      const months = "January February March April May June July August September October November December".split(" ")
      return `${Number(d)} ${months[Number(m) - 1]} ${y}`
    }
    expect(text).toContain(long(STATEMENT.preparedOn))
    expect(text).toContain(long(STATEMENT.lastReviewedOn))
    expect(text).toContain(long(STATEMENT.nextReviewDue))
  })

  it("hardcodes no date in the page source — every year literal is an allow-listed statute citation", () => {
    // An EXACT allow-list, not a deny-list. A deny-list fails open: it only
    // catches the shapes somebody thought of. This asserts the complete SET of
    // four-digit-year literals in the file, so ANY new one reds — including the
    // review date somebody is tempted to write into the prose.
    const years = [...SOURCE.matchAll(/\b(?:19|20)\d{2}\b/g)].map((m) => m[0])
    expect([...new Set(years)].sort()).toEqual(["2010"]) // Equality Act 2010

    // And no date-shaped literal of any form.
    expect(SOURCE).not.toMatch(/\d{4}-\d{2}-\d{2}/)
    expect(SOURCE).not.toMatch(
      /(January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{4}/
    )
  })
})
