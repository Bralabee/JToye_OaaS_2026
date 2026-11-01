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
import { render, screen, within } from "@testing-library/react"
import "@testing-library/jest-dom"
import TermsPage, { metadata } from "@/app/legal/terms/page"
import LegalPage from "@/app/legal/page"
import {
  CANCELLATION_STATEMENT_COPY,
  PLATFORM_NOT_SELLER_COPY,
} from "@/components/storefront/seller-block"
import { NO_VAT_REGISTRATION_COPY } from "@/components/storefront/no-vat-registration-note"
import { formatSetupLine, PRICING, PRICING_CARD_FEE_LINE } from "@/lib/pricing"
import { getCompanyInfo } from "@/lib/company"
import { getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(),
  customerLogout: jest.fn(),
}))

beforeEach(() => {
  ;(getCustomerSession as jest.Mock).mockReset()
  ;(getCustomerSession as jest.Mock).mockResolvedValue(null)
})

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
