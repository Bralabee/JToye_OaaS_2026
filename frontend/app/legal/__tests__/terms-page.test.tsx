/**
 * /legal/terms — the platform's terms of use (SW-SAM-12, swarm epic #964).
 *
 * WHAT THIS FILE DEFENDS. The 2026-10-19 persona swarm found /legal/terms
 * answering 404 and the legal hub linking no terms at all. The fix is a page
 * that RESTATES copy the site already publishes elsewhere — it adds no legal
 * term of its own. So the assertions are of two kinds:
 *
 *  1. QUOTED, NOT RETYPED. The seller statement, the no-cancellation statement
 *     and the no-VAT line are imported from the module the checkout renders
 *     them from, and asserted to appear on this page verbatim. A paraphrase here
 *     would be a second, unreviewed version of legally operative wording.
 *  2. NOTHING INVENTED. No governing-law clause, no liability cap, no
 *     indemnity, no arbitration — none of those is published anywhere on the
 *     site or in docs/, so their ABSENCE is asserted on the rendered text (a
 *     comment in the source cannot satisfy it). The control first proves the
 *     page actually rendered its body, so the absences are not vacuous.
 */
import fs from "node:fs"
import path from "node:path"
import { render, screen, within } from "@testing-library/react"
import { LEGAL_PROSE_LINK_CLASS } from "@/components/legal/policy-page"
import "@testing-library/jest-dom"
import TermsPage, { metadata } from "@/app/legal/terms/page"
import LegalPage from "@/app/legal/page"
import {
  CANCELLATION_STATEMENT_COPY,
  PLATFORM_NOT_SELLER_COPY,
} from "@/components/storefront/seller-block"
import { NO_VAT_REGISTRATION_COPY } from "@/components/storefront/no-vat-registration-note"
import {
  formatSetupLine,
  PRICING,
  PRICING_CARD_FEE_LINE,
  STRIPE_UK_PRICING_LINK_TEXT,
  STRIPE_UK_PRICING_URL,
} from "@/lib/pricing"
import { getCompanyInfo } from "@/lib/company"
import { OPERATOR_APPLICATION_REVIEW_LINE, OPERATOR_FBO_LINE } from "@/lib/operator-copy"
import { getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(),
  customerLogout: jest.fn(),
}))

beforeEach(() => {
  ;(getCustomerSession as jest.Mock).mockReset()
  ;(getCustomerSession as jest.Mock).mockResolvedValue(null)
})

/** The link's VISIBLE text: the sr-only new-tab announcement removed, whitespace trimmed. */
function visibleLinkText(link: HTMLElement): string {
  const copy = link.cloneNode(true) as HTMLElement
  copy.querySelectorAll(".sr-only").forEach((n) => n.remove())
  return (copy.textContent ?? "").trim()
}

/** The page's own <main>, so the shared footer is never what an assertion reads. */
function renderMain(): HTMLElement {
  const view = render(<TermsPage />)
  const main = view.container.querySelector("main")
  if (!main) throw new Error("VOID: the terms page rendered no main landmark")
  return main
}

describe("/legal/terms exists and renders through the policy shell (SW-SAM-12)", () => {
  it("renders a single h1 and its sections inside main", () => {
    const main = renderMain()
    expect(within(main).getAllByRole("heading", { level: 1 })).toHaveLength(1)
    expect(within(main).getByRole("heading", { level: 1 })).toHaveTextContent(/^terms of use$/i)
    // Control: the body is there before anything below asserts an absence.
    expect(within(main).getAllByRole("heading", { level: 2 }).length).toBeGreaterThanOrEqual(4)
  })

  it("declares its own canonical and title", () => {
    const alt = metadata.alternates as { canonical?: string } | undefined
    expect(alt?.canonical).toBe("/legal/terms")
    expect(String(metadata.title)).toMatch(/terms of use/i)
  })
})

describe("/legal/terms quotes the checkout's operative wording verbatim", () => {
  it.each([
    ["the platform-not-seller statement", PLATFORM_NOT_SELLER_COPY],
    ["the no-cancellation statement", CANCELLATION_STATEMENT_COPY],
    ["the no-VAT-registration line", NO_VAT_REGISTRATION_COPY],
    // PR 1080 round 1: the two operator-page sentences, imported rather than retyped.
    ["the operator application-review line", OPERATOR_APPLICATION_REVIEW_LINE],
    ["the Food Business Operator line", OPERATOR_FBO_LINE],
  ])("carries %s", (_label, copy) => {
    const main = renderMain()
    expect(main.textContent).toContain(copy)
  })

  it("names the operator from lib/company.ts, not from a typed literal", () => {
    const c = getCompanyInfo()
    const main = renderMain()
    expect(main.textContent).toContain(c.legalName)
    expect(main.textContent).toContain(c.companyNumber)
  })

  it("prints the operator pricing from the shared PRICING module", () => {
    const main = renderMain()
    const text = main.textContent ?? ""
    expect(text).toContain(`£${PRICING.coreMonthly}`)
    expect(text).toContain(PRICING.usageLabel)
    expect(text).toContain(`£${PRICING.flatLow}–£${PRICING.flatHigh}`)
    expect(text).toContain(PRICING.paygLabel)
    expect(text).toContain(formatSetupLine(PRICING))
    expect(text).toContain(PRICING_CARD_FEE_LINE)
  })

  // SW-CLR-06 (owner ruling 2026-11-01): the quoted card-fee line carries the same
  // Stripe UK pricing link as /for-operators, from the same constant.
  it("links Stripe's UK pricing page beside the card-fee line, in a new tab", () => {
    const main = renderMain()
    const link = within(main).getByRole("link", { name: /stripe.s uk pricing/i })
    expect(link).toHaveAttribute("href", STRIPE_UK_PRICING_URL)
    expect(link).toHaveAttribute("target", "_blank")
    expect(link.getAttribute("rel") ?? "").toMatch(/\bnoopener\b/)
    expect(link.getAttribute("rel") ?? "").toMatch(/\bnoreferrer\b/)
    // Round 1: the text must BE the shared constant, so a page that retypes it fails.
    expect(visibleLinkText(link)).toBe(STRIPE_UK_PRICING_LINK_TEXT)
    expect(link).toHaveAccessibleName(/opens in a new tab/i)
    expect(link.closest("p")).toHaveTextContent(PRICING_CARD_FEE_LINE)
  })

  // Round 1: one shared component renders the sentence on both pages.
  it("renders the Stripe pricing sentence through the shared component, not a local copy", () => {
    const src = fs.readFileSync(path.join(process.cwd(), "app/legal/terms/page.tsx"), "utf8")
    expect(src).toMatch(/from "@\/components\/marketing\/stripe-uk-pricing-sentence"/)
    expect(src).toMatch(/<StripeUkPricingSentence\b/)
    expect(src).not.toMatch(/STRIPE_UK_PRICING_URL|STRIPE_UK_PRICING_LINK_TEXT|opens in a new tab/)
  })
})

describe("/legal/terms invents no legal term the site has never published", () => {
  it.each([
    ["a governing-law clause", /governing law|laws of england|courts of/i],
    ["a liability clause", /liabilit|liable for any|consequential/i],
    ["an indemnity", /indemn/i],
    ["an arbitration clause", /arbitrat/i],
  ])("does not contain %s", (_label, pattern) => {
    const main = renderMain()
    // Control: the operative copy rendered, so this absence is about the page.
    expect(main.textContent).toContain(PLATFORM_NOT_SELLER_COPY)
    expect(main.textContent).not.toMatch(pattern)
  })
})

describe("the legal hub links the terms (SW-SAM-12)", () => {
  it("lists /legal/terms among its policy documents", () => {
    render(<LegalPage />)
    const main = document.querySelector("main")
    if (!main) throw new Error("VOID: the legal hub rendered no main landmark")
    expect(within(main).getByRole("link", { name: /^terms of use$/i })).toHaveAttribute(
      "href",
      "/legal/terms"
    )
    // Control: the existing documents are still listed beside it.
    expect(within(main).getByRole("link", { name: /^privacy notice$/i })).toBeInTheDocument()
  })

  it("links /contact from its own body, not only from the footer", () => {
    render(<LegalPage />)
    const main = document.querySelector("main")
    if (!main) throw new Error("VOID: the legal hub rendered no main landmark")
    // Control: the hub's identity list rendered.
    expect(main.textContent).toContain("Company number")
    expect(main.querySelector('a[href="/contact"]')).not.toBeNull()
  })

  it("the terms page links back to the contact route", () => {
    renderMain()
    expect(screen.getAllByRole("link", { name: /contact page/i })[0]).toHaveAttribute(
      "href",
      "/contact"
    )
  })
})

/** Same rule as /contact (PR 1080 round 1): the prose link style is shared, not re-typed. */
describe("/legal/terms reuses the legal-surface prose link style", () => {
  it("renders its in-prose links with LEGAL_PROSE_LINK_CLASS", () => {
    const main = renderMain()
    const link = main.querySelector('a[href="/legal/privacy"]')
    expect(link).not.toBeNull()
    expect(link?.getAttribute("class")).toBe(LEGAL_PROSE_LINK_CLASS)
  })

  it("does not re-type the link class string in its source", () => {
    const src = fs.readFileSync(path.join(process.cwd(), "app/legal/terms/page.tsx"), "utf8")
    expect(src).toMatch(/<Link/)
    expect(src).not.toMatch(/text-amber-700/)
  })
})

describe("the /legal hub reuses the legal-surface link style", () => {
  it("does not re-type the link class string in its source", () => {
    const src = fs.readFileSync(path.join(process.cwd(), "app/legal/page.tsx"), "utf8")
    expect(src).toMatch(/<Link/)
    expect(src).not.toMatch(/text-amber-700/)
  })
})
