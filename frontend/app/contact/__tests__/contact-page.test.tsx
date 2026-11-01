/**
 * /contact — the platform's contact route (SW-CLR-04, swarm epic #964).
 *
 * WHY. The 2026-10-19 persona swarm found no contact or sales route anywhere:
 * "Start your application" ends at a Keycloak sign-in and there was not one
 * mailto: or tel: on any public marketing surface. This page renders the
 * company facts lib/company.ts already holds, plus a contact email and phone
 * read from configuration.
 *
 * BOTH DIRECTIONS, AND THE UNSET ONE MATTERS MORE. A contact value is never
 * hardcoded, so on a deployment that sets neither the page must say so plainly
 * and must NOT render a "Email" or "Phone" term with nothing after it — that is
 * a page that looks like a rendering bug and is a dead contact route at once.
 * The configured arm is driven by writing the variables into process.env before
 * render, which also proves the values are read at call time.
 */
import fs from "node:fs"
import path from "node:path"
import { render, within } from "@testing-library/react"
import "@testing-library/jest-dom"
import ContactPage, { metadata } from "@/app/contact/page"
import { getCompanyInfo, resolvePublicContact } from "@/lib/company"
import { LEGAL_LINK_CLASS } from "@/components/legal/policy-page"
import { getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(),
  customerLogout: jest.fn(),
}))

const EMAIL_VAR = "NEXT_PUBLIC_CONTACT_EMAIL"
const PHONE_VAR = "NEXT_PUBLIC_CONTACT_PHONE"
const SAMPLE_EMAIL = "hello@example.test"
const SAMPLE_PHONE = "+44 20 7946 0000"

const saved = { [EMAIL_VAR]: process.env[EMAIL_VAR], [PHONE_VAR]: process.env[PHONE_VAR] }

beforeEach(() => {
  delete process.env[EMAIL_VAR]
  delete process.env[PHONE_VAR]
  ;(getCustomerSession as jest.Mock).mockReset()
  ;(getCustomerSession as jest.Mock).mockResolvedValue(null)
})

afterAll(() => {
  for (const [k, v] of Object.entries(saved)) {
    if (v === undefined) delete process.env[k]
    else process.env[k] = v
  }
})

function renderMain(): HTMLElement {
  const view = render(<ContactPage />)
  const main = view.container.querySelector("main")
  if (!main) throw new Error("VOID: the contact page rendered no main landmark")
  return main
}

describe("/contact renders the operator's facts (SW-CLR-04)", () => {
  it("has one h1 and its own canonical", () => {
    const main = renderMain()
    expect(within(main).getAllByRole("heading", { level: 1 })).toHaveLength(1)
    expect(within(main).getByRole("heading", { level: 1 })).toHaveTextContent(/contact/i)
    const alt = metadata.alternates as { canonical?: string } | undefined
    expect(alt?.canonical).toBe("/contact")
  })

  it("names the operator from lib/company.ts", () => {
    const c = getCompanyInfo()
    const main = renderMain()
    expect(main.textContent).toContain(c.legalName)
    expect(main.textContent).toContain(c.companyNumber)
    expect(main.textContent).toContain(c.registrationJurisdiction)
  })
})

describe("/contact — UNSET contact configuration", () => {
  it("renders no mailto:/tel: and no empty Email or Phone term", () => {
    const main = renderMain()
    // Control: the identity list rendered, so the absences below are real.
    expect(main.textContent).toContain(getCompanyInfo().companyNumber)
    expect(main.querySelector('a[href^="tel:"]')).toBeNull()
    expect(main.querySelector(`a[href="mailto:${SAMPLE_EMAIL}"]`)).toBeNull()
    const terms = Array.from(main.querySelectorAll("dt")).map((d) => d.textContent?.trim())
    expect(terms).not.toContain("Email")
    expect(terms).not.toContain("Phone")
  })

  it("says plainly that no general contact route is published", () => {
    const main = renderMain()
    expect(main.textContent).toMatch(/not yet published a general contact/i)
  })
})

describe("/contact — CONFIGURED contact", () => {
  it("renders the configured email as a mailto: link and the phone as a tel: link", () => {
    process.env[EMAIL_VAR] = SAMPLE_EMAIL
    process.env[PHONE_VAR] = SAMPLE_PHONE
    const main = renderMain()
    expect(within(main).getByRole("link", { name: SAMPLE_EMAIL })).toHaveAttribute(
      "href",
      `mailto:${SAMPLE_EMAIL}`
    )
    expect(within(main).getByRole("link", { name: SAMPLE_PHONE })).toHaveAttribute(
      "href",
      "tel:+442079460000"
    )
    expect(main.textContent).not.toMatch(/not yet published a general contact/i)
  })

  it("publishes one route when only one is configured", () => {
    process.env[PHONE_VAR] = SAMPLE_PHONE
    const main = renderMain()
    expect(main.querySelector('a[href^="tel:"]')).not.toBeNull()
    const terms = Array.from(main.querySelectorAll("dt")).map((d) => d.textContent?.trim())
    expect(terms).toContain("Phone")
    expect(terms).not.toContain("Email")
  })
})

describe("resolvePublicContact", () => {
  it("treats whitespace as unconfigured and reads at call time", () => {
    process.env[EMAIL_VAR] = "   "
    expect(resolvePublicContact().anyRoute).toBe(false)
    process.env[EMAIL_VAR] = SAMPLE_EMAIL
    const r = resolvePublicContact()
    expect(r.email).toBe(SAMPLE_EMAIL)
    expect(r.emailHref).toBe(`mailto:${SAMPLE_EMAIL}`)
    expect(r.phone).toBeNull()
    expect(r.anyRoute).toBe(true)
  })
})

/**
 * Round 1 review (PR 1080, comment 4436097495): the tel: href used to strip only
 * separators, so the letterhead spelling "+44 (0)20 7946 0000" became
 * tel:+4402079460000, which dials +44 0 20 and fails while the visible text
 * looks right. A literal table, so scripts/count-test-blocks.mjs can count it.
 */
const PHONE_SPELLINGS: Array<[string, string, string]> = [
  ["international with a (0) trunk group", "+44 (0)20 7946 0000", "tel:+442079460000"],
  ["plain national", "020 7946 0000", "tel:02079460000"],
  ["already clean E.164", "+442079460000", "tel:+442079460000"],
  ["international with an interior plus", "+44 20 7946+0000", "tel:+442079460000"],
]

describe("resolvePublicContact builds a dialable tel: href", () => {
  it.each(PHONE_SPELLINGS)("%s: %s -> %s", (_label, configured, href) => {
    process.env[PHONE_VAR] = configured
    const r = resolvePublicContact()
    expect(r.phoneHref).toBe(href)
    // The visible text stays exactly as configured.
    expect(r.phone).toBe(configured)
  })
})

describe("no contact value is hardcoded", () => {
  it.each(["app/contact/page.tsx", "lib/company.ts"])(
    "%s carries no email address or phone literal",
    (rel) => {
      const src = fs.readFileSync(path.join(process.cwd(), rel), "utf8")
      // Control: the file is the one that reads the variable.
      expect(src).toMatch(/CONTACT_EMAIL|resolvePublicContact/)
      expect(src).not.toMatch(/[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z]{2,}/)
      expect(src).not.toMatch(/(?:\+44|\b0)\s?\d{2,4}\s?\d{3,4}\s?\d{3,4}/)
    }
  )
})

/**
 * One link style for the legal surface (PR 1080 round 1, comment 4436097726).
 * The rendered link must carry the shared constant, and the source must not
 * re-type the class string, so a style ruling cannot miss this page.
 */
describe("/contact reuses the legal-surface link style", () => {
  it("renders its operator-page link with LEGAL_LINK_CLASS", () => {
    const main = renderMain()
    const link = main.querySelector('a[href="/for-operators"]')
    expect(link).not.toBeNull()
    expect(link?.getAttribute("class")).toBe(LEGAL_LINK_CLASS)
  })

  it("does not re-type the link class string in its source", () => {
    const src = fs.readFileSync(path.join(process.cwd(), "app/contact/page.tsx"), "utf8")
    // Control: the page does use links.
    expect(src).toMatch(/<Link/)
    expect(src).not.toMatch(/text-amber-700/)
  })
})
