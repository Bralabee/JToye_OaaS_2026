/**
 * Completeness contract of the cookie and browser-storage policy (LGL-01).
 *
 * THE ONLY CLAIM THIS PAGE MAKES IS COMPLETENESS, so that is what is tested,
 * and it is tested by ITERATION over the whole inventory rather than by
 * spot-check. The distinction is the whole design of this file: a spot-check
 * that happens to name three keys stays green when a fourth is dropped, and a
 * disclosure that is silently missing an item is indistinguishable from a
 * correct one to every reader who is not holding the source.
 *
 * The inventory below is the one re-derived from source for this plan, by
 * sweeping `localStorage.setItem`, `sessionStorage.setItem` and every cookie
 * write across `app`, `components`, `lib` and `hooks` with tests excluded, plus
 * the identity library's own cookie defaults. It is duplicated here on purpose:
 * this list is the ASSERTION, and if it were imported from the page the test
 * would be checking the page against itself and could never fail.
 *
 * OVER-DISCLOSURE IS ALSO TESTED. Two legacy keys are removed by the sign-out
 * path and never written by anything. Publishing them would claim storage that
 * does not exist, which misleads in the opposite direction and is just as wrong.
 */
import { render, screen } from "@testing-library/react"
import "@testing-library/jest-dom"
import CookiePolicyPage, { metadata } from "@/app/legal/cookies/page"
import { getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(),
  customerLogout: jest.fn(),
}))

const mockedSession = getCustomerSession as jest.Mock

beforeEach(() => {
  mockedSession.mockReset()
  mockedSession.mockResolvedValue(null)
})

/**
 * Cookie names. `authjs.*` is a family whose members are the Auth.js defaults —
 * asserted individually, because "we set some dashboard cookies" is not a
 * disclosure.
 */
const COOKIE_NAMES = [
  "jtoye-customer-access",
  "jtoye-customer-refresh",
  "jtoye-customer-id",
  "authjs.session-token",
  "authjs.callback-url",
  "authjs.csrf-token",
  "authjs.pkce.code_verifier",
  "authjs.state",
  "authjs.nonce",
  "authjs.challenge",
] as const

const LOCAL_STORAGE_KEYS = [
  "jtoye-cart-",
  "jtoye-checkout-email-",
  "jtoye-customer-id",
  "jtoye-customer-logged-in",
  "jtoye-customer-expires-at",
  "jtoye-guest-orders",
  "jtoye-cookie-notice-ack",
  "shopContext",
  "theme",
  "kds-muted",
] as const

const SESSION_STORAGE_KEYS = [
  "jtoye-track-email",
  "jtoye-auth-return",
  "jtoye-pkce-verifier",
  "jtoye-oauth-state",
  "jtoye-oauth-nonce",
] as const

/**
 * Written by no code path; removed only, as legacy cleanup. Disclosing these
 * would be a claim that the platform stores them.
 */
const NEVER_WRITTEN_LEGACY_KEYS = [
  "jtoye-customer-tokens",
  "jtoye-customer-profile",
] as const

/** The two keys that hold an email address, with the retention each must state. */
const EMAIL_BEARING = [
  { key: "jtoye-checkout-email-", retention: /until you clear|no expiry/i },
  { key: "jtoye-track-email", retention: /tab/i },
] as const

function renderPolicy() {
  const utils = render(<CookiePolicyPage />)
  const main = utils.container.querySelector("main")
  if (!main) throw new Error("no main landmark — the shell did not render")
  return { ...utils, main: main as HTMLElement }
}

function normalisedText(el: HTMLElement): string {
  return (el.textContent || "").replace(/\s+/g, " ")
}

/** The row whose <th scope="row"> names `key`, or null. */
function rowFor(main: HTMLElement, key: string): HTMLTableRowElement | null {
  const headers = Array.from(main.querySelectorAll('th[scope="row"]'))
  const match = headers.find((th) => (th.textContent || "").includes(key))
  return (match?.closest("tr") as HTMLTableRowElement) || null
}

describe("cookie policy — the tables are real tables", () => {
  it("renders at least three tables, each with more than one body row", () => {
    const { main } = renderPolicy()

    // NON-VACUITY CONTROL. Every assertion below walks table cells; over a page
    // that rendered no tables, all of them pass.
    const tables = Array.from(main.querySelectorAll("table"))
    expect(tables.length).toBeGreaterThanOrEqual(3)

    for (const table of tables) {
      expect(table.querySelectorAll("tbody tr").length).toBeGreaterThan(1)
    }
  })

  it("gives every table a caption, column headers and row headers", () => {
    const { main } = renderPolicy()
    const tables = Array.from(main.querySelectorAll("table"))
    expect(tables.length).toBeGreaterThanOrEqual(3)

    for (const table of tables) {
      expect(table.querySelector("caption")?.textContent || "").not.toBe("")
      expect(
        table.querySelectorAll('th[scope="col"]').length
      ).toBeGreaterThanOrEqual(3)
      expect(
        table.querySelectorAll('th[scope="row"]').length
      ).toBeGreaterThan(1)
    }
  })

  it("wraps each table in a focusable, labelled scroll region", () => {
    const { main } = renderPolicy()
    const regions = Array.from(main.querySelectorAll('[role="region"]'))
    expect(regions.length).toBeGreaterThanOrEqual(3)

    for (const region of regions) {
      // An unfocusable scroll container is what axe's
      // scrollable-region-focusable rule fails, and it strands the table for
      // keyboard users if it ever does overflow.
      expect(region.getAttribute("tabindex")).toBe("0")
      expect(region.getAttribute("aria-label") || "").not.toBe("")
    }
  })

  it("does not duplicate the data into a mobile-only copy of the DOM", () => {
    const { main } = renderPolicy()

    // The duplicated-DOM trap this repo has paid for twice (#556, #593). A
    // duplicate-mobile-list build renders the SAME table twice, so the check is
    // "once per table", not "once per page".
    //
    // SCOPED PER TABLE BECAUSE ONE NAME LEGITIMATELY APPEARS TWICE.
    // `jtoye-customer-id` is BOTH a cookie and a local-storage key, with
    // different contents in each: the cookie holds the identity token issued at
    // sign-in, and the local-storage item holds the opaque subject id used to
    // stamp a basket. A page-wide uniqueness assertion called that a defect —
    // it is not, it is a genuine collision across two storage mechanisms, and
    // collapsing the two rows would under-disclose one of them.
    const tables = Array.from(main.querySelectorAll("table"))
    expect(tables.length).toBeGreaterThanOrEqual(3)

    for (const table of tables) {
      const names = Array.from(table.querySelectorAll('th[scope="row"]')).map(
        (th) => (th.textContent || "").trim()
      )
      expect(names.length).toBeGreaterThan(1)
      expect(new Set(names).size).toBe(names.length)
    }
  })
})

describe("cookie policy — completeness, asserted by iteration", () => {
  it("discloses every cookie, including the vendor-dashboard family", () => {
    const { main } = renderPolicy()
    const text = normalisedText(main)
    expect(text.length).toBeGreaterThan(2000)

    const missing = COOKIE_NAMES.filter((name) => !text.includes(name))
    expect(missing).toEqual([])
  })

  it("discloses every local-storage key", () => {
    const { main } = renderPolicy()
    const text = normalisedText(main)
    expect(text.length).toBeGreaterThan(2000)

    const missing = LOCAL_STORAGE_KEYS.filter((key) => !text.includes(key))
    expect(missing).toEqual([])
  })

  it("discloses every session-storage key", () => {
    const { main } = renderPolicy()
    const text = normalisedText(main)
    expect(text.length).toBeGreaterThan(2000)

    const missing = SESSION_STORAGE_KEYS.filter((key) => !text.includes(key))
    expect(missing).toEqual([])
  })

  it("gives every disclosed item a purpose and a lifetime, not just a name", () => {
    const { main } = renderPolicy()

    const thin: string[] = []
    for (const key of [...LOCAL_STORAGE_KEYS, ...SESSION_STORAGE_KEYS]) {
      const row = rowFor(main, key)
      if (!row) {
        thin.push(`${key}: no row`)
        continue
      }
      const cells = Array.from(row.querySelectorAll("td"))
      if (cells.length < 2) {
        thin.push(`${key}: ${cells.length} data cells`)
        continue
      }
      // A name with an empty purpose or lifetime is a row that discloses
      // nothing while looking complete.
      //
      // The bar is "a real statement, and not a placeholder" rather than a
      // length quota. An arbitrary quota was tried first at 20 characters and
      // flagged "Until you sign out." — a complete and correct lifetime — which
      // would have pushed the page towards padding its cells to satisfy a
      // number. A published "TBD" in a legal disclosure is the actual defect.
      cells.forEach((cell, i) => {
        const value = (cell.textContent || "").trim()
        if (value.length < 8) {
          thin.push(`${key}: cell ${i} is empty or near-empty ("${value}")`)
        }
        if (/^(tbd|todo|n\/a|-+|\?+)$/i.test(value)) {
          thin.push(`${key}: cell ${i} is a placeholder ("${value}")`)
        }
      })
    }
    expect(thin).toEqual([])
  })
})

describe("cookie policy — it does not over-disclose", () => {
  it("does not list the legacy keys that are only ever removed", () => {
    const { main } = renderPolicy()
    const text = normalisedText(main)

    // Control: the page must have rendered real content for this absence to
    // mean anything.
    expect(text).toContain("jtoye-customer-logged-in")

    const wronglyListed = NEVER_WRITTEN_LEGACY_KEYS.filter((key) =>
      text.includes(key)
    )
    expect(wronglyListed).toEqual([])
  })
})

describe("cookie policy — the two email-bearing keys", () => {
  it("names each one AND states its retention in the same row", () => {
    const { main } = renderPolicy()

    for (const { key, retention } of EMAIL_BEARING) {
      const row = rowFor(main, key)
      expect(row).not.toBeNull()
      const rowText = normalisedText(row as HTMLElement)
      expect(rowText).toMatch(/email address/i)
      expect(rowText).toMatch(retention)
    }
  })

  it("also calls both out in prose, not only inside a table cell", () => {
    const { main } = renderPolicy()
    const paragraphs = Array.from(main.querySelectorAll("p"))
      .map((p) => normalisedText(p as HTMLElement))
      .filter((t) => /email address/i.test(t))

    // Burying personal data in a table row is how this page would be
    // technically complete and practically misleading.
    expect(paragraphs.length).toBeGreaterThanOrEqual(2)
  })
})

describe("cookie policy — numbers this repository does not own", () => {
  it("describes the access cookie's lifetime instead of publishing it", () => {
    const { main } = renderPolicy()
    const row = rowFor(main, "jtoye-customer-access")
    expect(row).not.toBeNull()
    const rowText = normalisedText(row as HTMLElement)

    expect(rowText).toMatch(/identity provider/i)

    // The realm's accessTokenLifespan lives in Keycloak, not here. A number
    // published in one place and configured in another goes wrong silently.
    expect(rowText).not.toMatch(/\b300\b/)
    expect(normalisedText(main)).not.toMatch(/\b300\s*second/i)
  })

  it("publishes no bare seconds figure anywhere on the page", () => {
    const { main } = renderPolicy()
    const text = normalisedText(main)
    expect(text.length).toBeGreaterThan(2000)
    expect(text).not.toMatch(/\b\d+\s*seconds?\b/i)
  })
})

describe("cookie policy — framing and honesty", () => {
  it("says 'browser storage', not 'cookies only'", () => {
    const { main } = renderPolicy()
    expect(normalisedText(main).toLowerCase()).toContain("browser storage")
  })

  it("states there are no analytics, advertising or tracking scripts", () => {
    const { main } = renderPolicy()
    const text = normalisedText(main).toLowerCase()
    expect(text).toContain("no advertising cookies")
    expect(text).toContain("no analytics or tracking scripts")
  })

  it("discloses Stripe rather than claiming zero third parties", () => {
    const { main } = renderPolicy()
    const text = normalisedText(main)

    // MEASURED, not assumed: app/shop/[slug]/checkout/page.tsx calls
    // loadStripe(), and lib/security-headers.ts allow-lists js.stripe.com in
    // both script-src and frame-src. A claim of "no third-party scripts" would
    // be false on this tree, so the page must name Stripe and scope it.
    expect(text).toMatch(/Stripe/)
    expect(text).toMatch(/js\.stripe\.com/)
    expect(text).toMatch(/payment step/i)
    expect(main.querySelector('a[href^="https://stripe.com"]')).toBeInTheDocument()
  })

  it("does not claim the site loads no third-party code at all", () => {
    const { main } = renderPolicy()
    const text = normalisedText(main).toLowerCase()
    for (const overclaim of [
      "no third-party scripts",
      "no third party scripts",
      "we load no third-party code",
    ]) {
      expect(text).not.toContain(overclaim)
    }
  })
})

describe("cookie policy — the prose actually reads as prose", () => {
  it("loses no space where an inline element meets the text after it", () => {
    const { main } = renderPolicy()

    // A TOOLCHAIN BUG THIS PAGE TRIPPED, FOUND BY READING RENDERED OUTPUT.
    // Measured with a four-arm control: when the JSXText node FOLLOWING an
    // inline element contains an HTML entity anywhere in it — `&apos;`, several
    // words later — the transform drops that node's LEADING space, and the
    // words run together in the delivered HTML:
    //
    //   inline element, no entity in the following text   -> space kept
    //   inline element, `&apos;` later in the same text   -> SPACE LOST
    //   `&apos;` only BEFORE the inline element           -> space kept
    //   explicit {" "} at the boundary, entity present    -> space kept
    //
    // It shipped "js.stripe.comso your card details", "<shop>there is one
    // item" and "Clearing site datain your browser" — invisible in source
    // review, because the source has the space. This project's own
    // react/no-unescaped-entities rule REQUIRES `&apos;` in JSX text, so the
    // trap is reachable from any paragraph on any page. The fix is an explicit
    // {" "} at the boundary; this test is what stops it coming back.
    const containers = Array.from(main.querySelectorAll("p, li, h2, h3, td, th"))
    expect(containers.length).toBeGreaterThan(20)

    const runTogether: string[] = []
    for (const el of containers) {
      const hits = el.innerHTML.match(
        /<\/(?:code|span|a|strong|em|b|i)>[A-Za-z]\w*/g
      )
      if (hits) runTogether.push(...hits)
    }
    expect(runTogether).toEqual([])
  })
})

describe("cookie policy — document structure and metadata", () => {
  it("carries one h1 and at least four h2 sections with stable ids", () => {
    const { main } = renderPolicy()

    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1)

    const h2s = Array.from(main.querySelectorAll("h2"))
    expect(h2s.length).toBeGreaterThanOrEqual(4)
    expect(h2s.filter((h) => !h.id || h.id.trim() === "")).toEqual([])
  })

  it("skips no heading level", () => {
    const { main } = renderPolicy()
    const headings = Array.from(
      main.querySelectorAll("h1, h2, h3, h4, h5, h6")
    )
    expect(headings.length).toBeGreaterThanOrEqual(5)

    const levels = headings.map((h) => Number(h.tagName.slice(1)))
    const skips: string[] = []
    for (let i = 1; i < levels.length; i++) {
      if (levels[i] > levels[i - 1] + 1) {
        skips.push(
          `${headings[i - 1].tagName} "${headings[i - 1].textContent}" -> ` +
            `${headings[i].tagName} "${headings[i].textContent}"`
        )
      }
    }
    expect(skips).toEqual([])
  })

  it("uses no weight-700 type", () => {
    const { main } = renderPolicy()
    const all = Array.from(main.querySelectorAll("*"))
    expect(all.length).toBeGreaterThan(50)
    expect(
      all
        .filter((el) => (el.getAttribute("class") || "").includes("font-bold"))
        .map((el) => el.tagName)
    ).toEqual([])
  })

  it("declares its own canonical, title and description", async () => {
    expect(metadata.alternates?.canonical).toBe("/legal/cookies")
    expect(metadata.title).toBe("Cookie and browser-storage policy — J'Toye")

    const privacy = await import("@/app/legal/privacy/page")
    const legalIndex = await import("@/app/legal/page")
    for (const other of [privacy.metadata, legalIndex.metadata]) {
      expect(metadata.title).not.toBe(other.title)
      expect(metadata.description).not.toBe(other.description)
      expect(metadata.alternates?.canonical).not.toBe(
        other.alternates?.canonical
      )
    }
  })
})
