/**
 * Public-content lane of the persona close-out (issues 803, 804, 878; swarm SW-CLR-03 and
 * SW-CLR-06). Written BEFORE the pages, and recorded failing on the unfixed tree.
 *
 * Owner rulings 2026-11-04 this file defends:
 *  - VAT basis: prices include VAT for VAT-registered businesses and carry no VAT for businesses
 *    that are not VAT-registered, stated plainly beside the price lines.
 *  - Positioning: UK-wide, launching first in Birmingham and the West Midlands, not London.
 *  - Merchant terms: drafted here, visibly a draft for solicitor review, never claimed in force.
 *
 * Every assertion reads RENDERED text (or an exported metadata object), because a comment in a
 * source file satisfies a source grep and tells a reader nothing.
 */
import fs from "node:fs"
import path from "node:path"
import { render, within } from "@testing-library/react"
import "@testing-library/jest-dom"
import type { Metadata } from "next"
import PricingPage, { metadata as pricingMetadata } from "@/app/pricing/page"
import AboutPage, { metadata as aboutMetadata } from "@/app/about/page"
import MerchantTermsPage, { metadata as merchantMetadata } from "@/app/legal/merchant-terms/page"
import ForOperatorsPage, { metadata as operatorsMetadata } from "@/app/for-operators/page"
import ContactPage from "@/app/contact/page"
import LegalPage from "@/app/legal/page"
import SignInPage from "@/app/auth/signin/page"
import { PublicFooter } from "@/components/public/public-footer"
import sitemap from "@/app/sitemap"
import {
  PRICING,
  PRICING_VAT_BASIS_LINE,
  STRIPE_UK_PRICING_URL,
} from "@/lib/pricing"
import { LAUNCH_AREA, POSITIONING_LINE } from "@/lib/positioning"
import { OPERATOR_CONFIDENTIALITY_LINE } from "@/lib/operator-copy"
import { getCompanyInfo } from "@/lib/company"
import { getCustomerSession } from "@/lib/customer-auth"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(),
  customerLogout: jest.fn(),
}))
jest.mock("@/lib/storefront-server", () => ({
  loadAllShopSlugs: jest.fn(() => Promise.resolve([])),
}))
jest.mock("next-auth/react", () => ({ signIn: jest.fn() }))

beforeEach(() => {
  ;(getCustomerSession as jest.Mock).mockReset()
  ;(getCustomerSession as jest.Mock).mockResolvedValue(null)
})

function mainOf(ui: React.ReactElement): HTMLElement {
  const view = render(ui)
  const main = view.container.querySelector("main")
  if (!main) throw new Error("VOID: page rendered no main landmark")
  return main
}

function canonicalOf(m: Metadata): string | undefined {
  return (m.alternates as { canonical?: string } | undefined)?.canonical
}

const DRAFT_MARKER = "Draft — to be reviewed by a solicitor before it applies to paying vendors"

describe("VAT basis (owner ruling 2026-11-04)", () => {
  it("is one plain sentence in the ruled form", () => {
    expect(PRICING_VAT_BASIS_LINE).toMatch(/include[sd]? VAT.*VAT-registered/i)
    expect(PRICING_VAT_BASIS_LINE).toMatch(/not VAT-registered/i)
    // No invented registration number or status.
    expect(PRICING_VAT_BASIS_LINE).not.toMatch(/GB\s?\d{9}|vat (number|no)/i)
  })

  it.each([
    ["/pricing", () => <PricingPage />],
    ["/legal/merchant-terms", () => <MerchantTermsPage />],
    ["/for-operators", () => <ForOperatorsPage />],
  ])("%s states it", (_route, ui) => {
    const main = mainOf(ui())
    expect(main.textContent).toContain(PRICING_VAT_BASIS_LINE)
  })
})

describe("/pricing (issue 804)", () => {
  it("has one h1 and prints every plan from the shared PRICING module", () => {
    const main = mainOf(<PricingPage />)
    expect(within(main).getAllByRole("heading", { level: 1 })).toHaveLength(1)
    const text = main.textContent ?? ""
    expect(text).toContain(`£${PRICING.coreMonthly}`)
    expect(text).toContain(PRICING.usageLabel)
    expect(text).toContain(`£${PRICING.flatLow}`)
    expect(text).toContain(`£${PRICING.flatHigh}`)
    expect(text).toContain(PRICING.paygLabel)
    expect(text).toContain(`£${PRICING.setupFrom}`)
    // The internal tier is never printed.
    expect(text).not.toMatch(/195|stepdown/i)
  })

  it("prints no card-fee figure and links Stripe's UK pricing page instead (ruling 2026-11-01)", () => {
    const main = mainOf(<PricingPage />)
    const link = within(main).getByRole("link", { name: /stripe.s uk pricing/i })
    expect(link).toHaveAttribute("href", STRIPE_UK_PRICING_URL)
    expect(main.textContent).not.toMatch(/1\.5\s?%|2\.5\s?%|\+\s?20p|\b20p\b/)
  })

  it("links the merchant terms, the application route and the contact route with real anchors", () => {
    const main = mainOf(<PricingPage />)
    expect(main.querySelector('a[href="/legal/merchant-terms"]')).not.toBeNull()
    expect(main.querySelector('a[href="/contact#apply"]')).not.toBeNull()
    expect(main.querySelector('a[href="/contact"]')).not.toBeNull()
  })

  it("carries its own title, description, absolute-resolvable canonical and Open Graph", () => {
    expect(String(pricingMetadata.title)).toMatch(/pricing/i)
    expect(String(pricingMetadata.description).length).toBeGreaterThan(40)
    expect(canonicalOf(pricingMetadata)).toBe("/pricing")
    const og = pricingMetadata.openGraph as { title?: unknown; description?: unknown; url?: string }
    expect(og.url).toBe("/pricing")
    expect(String(og.title).length).toBeGreaterThan(0)
    expect(String(og.description).length).toBeGreaterThan(0)
  })
})

describe("/about (issue 804)", () => {
  it("says who J'Toye is, with the company facts from lib/company.ts", () => {
    const main = mainOf(<AboutPage />)
    const c = getCompanyInfo()
    expect(within(main).getAllByRole("heading", { level: 1 })).toHaveLength(1)
    expect(main.textContent).toContain(c.legalName)
    expect(main.textContent).toContain(c.companyNumber)
  })

  it("states the ruled positioning and names no London pilot", () => {
    const main = mainOf(<AboutPage />)
    expect(main.textContent).toContain(POSITIONING_LINE)
    expect(main.textContent).toContain("Birmingham")
    expect(main.textContent).toContain("West Midlands")
    expect(main.textContent).not.toMatch(/london|nigerian|west african operators/i)
  })

  it("links the contact route and the application route", () => {
    const main = mainOf(<AboutPage />)
    expect(main.querySelector('a[href="/contact"]')).not.toBeNull()
    expect(main.querySelector('a[href="/contact#apply"]')).not.toBeNull()
  })

  it("carries title, description, canonical and Open Graph", () => {
    expect(String(aboutMetadata.title)).toMatch(/about/i)
    expect(String(aboutMetadata.description)).toContain("Birmingham")
    expect(canonicalOf(aboutMetadata)).toBe("/about")
    expect((aboutMetadata.openGraph as { url?: string }).url).toBe("/about")
  })
})

describe("/legal/merchant-terms (issue 804, owner asked for a draft)", () => {
  const SECTION_PATTERNS: Array<[string, RegExp]> = [
    ["parties", /who the parties are/i],
    ["fees and billing", /fees and billing/i],
    ["VAT", /\bVAT\b/],
    ["payouts via Stripe Connect", /payouts/i],
    ["refunds and chargebacks", /refunds.*chargebacks|chargebacks/i],
    ["food safety and allergens", /food safety.*allergen|allergen/i],
    ["data protection roles", /personal data/i],
    ["suspension and offboarding", /suspension.*termination|offboarding/i],
    ["liability", /liability/i],
    ["governing law", /governing law/i],
    ["contact", /contact/i],
  ]

  it("is visibly a draft, with a version and a date, and never claims to be in force", () => {
    const main = mainOf(<MerchantTermsPage />)
    expect(main.textContent).toContain(DRAFT_MARKER)
    expect(main.textContent).toMatch(/version\s*0\.\d/i)
    expect(main.textContent).toMatch(/4 November 2026/)
    expect(main.textContent).toMatch(/not (yet )?in force|does not yet apply/i)
    expect(main.textContent).not.toMatch(/these terms (are|come) in force|in force from/i)
  })

  it("renders one h1 and a section for each subject the owner asked for", () => {
    const main = mainOf(<MerchantTermsPage />)
    expect(within(main).getAllByRole("heading", { level: 1 })).toHaveLength(1)
    const headings = within(main)
      .getAllByRole("heading", { level: 2 })
      .map((h) => h.textContent ?? "")
      .join(" | ")
    for (const [label, pattern] of SECTION_PATTERNS) {
      expect({ label, found: pattern.test(headings) }).toEqual({ label, found: true })
    }
  })

  it("takes its fees from PRICING and its card-fee wording from the shared sentence", () => {
    const main = mainOf(<MerchantTermsPage />)
    const text = main.textContent ?? ""
    expect(text).toContain(`£${PRICING.coreMonthly}`)
    expect(text).toContain(PRICING.usageLabel)
    expect(text).toContain(`£${PRICING.flatLow}–£${PRICING.flatHigh}`)
    expect(text).toContain(PRICING.paygLabel)
    expect(within(main).getByRole("link", { name: /stripe.s uk pricing/i })).toHaveAttribute(
      "href",
      STRIPE_UK_PRICING_URL
    )
  })

  it("names J'Toye Digital by number and the vendor as seller and merchant of record", () => {
    const main = mainOf(<MerchantTermsPage />)
    const c = getCompanyInfo()
    expect(main.textContent).toContain(c.legalName)
    expect(main.textContent).toContain(c.companyNumber)
    expect(main.textContent).toMatch(/seller/i)
    expect(main.textContent).toMatch(/merchant of record/i)
  })

  it("says Stripe processing fees are the vendor's and that Stripe, not J'Toye, collects losses", () => {
    const main = mainOf(<MerchantTermsPage />)
    expect(main.textContent).toMatch(/processing fees/i)
    expect(main.textContent).toMatch(/Stripe[^.]*(collects|is responsible for collecting)[^.]*(negative balance|loss)/i)
  })

  it("keeps food-safety duties with the vendor and mentions Natasha's Law and the 14 allergens", () => {
    const main = mainOf(<MerchantTermsPage />)
    expect(main.textContent).toMatch(/Food Business Operator/i)
    expect(main.textContent).toMatch(/Natasha.s Law/i)
    expect(main.textContent).toMatch(/14 allergens/i)
  })

  it("matches the privacy notice's roles instead of contradicting them", () => {
    const main = mainOf(<MerchantTermsPage />)
    const text = main.textContent ?? ""
    expect(text).toMatch(/jointly responsible|joint controllers/i)
    expect(text).toMatch(/processor/i)
    expect(main.querySelector('a[href="/legal/privacy"]')).not.toBeNull()
    // Never "J'Toye is only a processor for order data": the notice says jointly.
    expect(text).not.toMatch(/J.Toye (is|acts as) (only )?(a|the) processor for (all|consumer|order)/i)
  })

  it("carries the vendor-to-vendor confidentiality statement and governing law England and Wales", () => {
    const main = mainOf(<MerchantTermsPage />)
    expect(main.textContent).toContain(OPERATOR_CONFIDENTIALITY_LINE)
    expect(main.textContent).toMatch(/England and Wales/)
  })

  it("invents no VAT registration for J'Toye", () => {
    const main = mainOf(<MerchantTermsPage />)
    expect(main.textContent).not.toMatch(/GB\s?\d{9}|our VAT (number|registration)/i)
  })

  it("declares title, description, canonical and Open Graph, and says draft in the title", () => {
    expect(String(merchantMetadata.title)).toMatch(/merchant terms/i)
    expect(String(merchantMetadata.title)).toMatch(/draft/i)
    expect(String(merchantMetadata.description).length).toBeGreaterThan(40)
    expect(canonicalOf(merchantMetadata)).toBe("/legal/merchant-terms")
    expect((merchantMetadata.openGraph as { url?: string }).url).toBe("/legal/merchant-terms")
  })

  it("is listed on the legal hub", () => {
    const main = mainOf(<LegalPage />)
    expect(main.querySelector('a[href="/legal/merchant-terms"]')).not.toBeNull()
  })
})

describe("discoverability: footer links and sitemap entries", () => {
  it("the footer links /pricing, /about and the merchant terms as real anchors", () => {
    const { container } = render(<PublicFooter />)
    for (const href of ["/pricing", "/about", "/legal/merchant-terms"]) {
      expect(container.querySelector(`a[href="${href}"]`)).not.toBeNull()
    }
  })

  it("the sitemap lists the new pages, /contact and /legal/terms", async () => {
    const prev = process.env.NEXT_PUBLIC_SITE_URL
    process.env.NEXT_PUBLIC_SITE_URL = "https://example.test"
    try {
      const entries = await sitemap()
      // Control: the sitemap rendered at all.
      expect(entries.length).toBeGreaterThan(3)
      const paths = entries.map((e) => new URL(e.url).pathname)
      for (const p of ["/pricing", "/about", "/contact", "/legal/terms", "/legal/merchant-terms"]) {
        expect(paths).toContain(p)
      }
    } finally {
      if (prev === undefined) delete process.env.NEXT_PUBLIC_SITE_URL
      else process.env.NEXT_PUBLIC_SITE_URL = prev
    }
  })
})

describe("positioning: UK-wide, launching first in Birmingham and the West Midlands (SW-CLR-03, UXT-119)", () => {
  it("defines the ruled line once", () => {
    expect(POSITIONING_LINE).toBe("UK-wide, launching first in Birmingham and the West Midlands.")
    expect(LAUNCH_AREA).toBe("Birmingham and the West Midlands")
  })

  it("/for-operators page copy and metadata name no London pilot and no single-community pilot", () => {
    const main = mainOf(<ForOperatorsPage />)
    const text = main.textContent ?? ""
    expect(text).toMatch(/Birmingham and the West Midlands/)
    expect(text).not.toMatch(/\bin London\b|trade in London|london cluster/i)
    expect(text).not.toMatch(/established West African|Nigerian and West African operators/i)
    const desc = String(operatorsMetadata.description)
    expect(desc).toMatch(/Birmingham/)
    expect(desc).not.toMatch(/london|nigerian|west african/i)
    const og = operatorsMetadata.openGraph as { description?: unknown }
    expect(String(og.description)).not.toMatch(/london|nigerian|west african/i)
  })

  it("/for-operators carries the vendor-to-vendor confidentiality statement (UXT-119 F6)", () => {
    const main = mainOf(<ForOperatorsPage />)
    expect(main.textContent).toContain(OPERATOR_CONFIDENTIALITY_LINE)
  })

  it("the confidentiality line is a statement about data kept apart, not a promise about public pages", () => {
    expect(OPERATOR_CONFIDENTIALITY_LINE).toMatch(/other (food )?businesses/i)
    expect(OPERATOR_CONFIDENTIALITY_LINE).toMatch(/cannot see/i)
  })
})

describe("the way in for a prospective vendor (issue 803)", () => {
  it("both 'Start your application' calls to action reach /contact#apply, not a login", () => {
    const main = mainOf(<ForOperatorsPage />)
    const ctas = within(main).getAllByRole("link", { name: /start your application/i })
    expect(ctas.length).toBe(2)
    for (const cta of ctas) {
      expect(cta).toHaveAttribute("href", "/contact#apply")
      expect(cta.getAttribute("href")).not.toMatch(/dashboard|auth/)
    }
  })

  it("/contact has an apply section with the steps, the merchant terms and a sign-in route for approved operators", () => {
    const main = mainOf(<ContactPage />)
    const apply = main.querySelector("#apply")
    expect(apply).not.toBeNull()
    expect(within(apply as HTMLElement).getByRole("heading", { level: 2 })).toHaveTextContent(
      /apply/i
    )
    expect(apply?.querySelector('a[href="/legal/merchant-terms"]')).not.toBeNull()
    expect(apply?.querySelector('a[href="/auth/signin"]')).not.toBeNull()
    expect(apply?.textContent).toContain(POSITIONING_LINE)
  })

  it("/contact#apply offers a mailto route with an application subject when an address is configured", () => {
    const prev = process.env.NEXT_PUBLIC_CONTACT_EMAIL
    process.env.NEXT_PUBLIC_CONTACT_EMAIL = "hello@example.test"
    try {
      const main = mainOf(<ContactPage />)
      const apply = main.querySelector("#apply") as HTMLElement
      const mail = apply.querySelector('a[href^="mailto:hello@example.test"]')
      expect(mail).not.toBeNull()
      expect(mail?.getAttribute("href")).toMatch(/subject=/i)
    } finally {
      if (prev === undefined) delete process.env.NEXT_PUBLIC_CONTACT_EMAIL
      else process.env.NEXT_PUBLIC_CONTACT_EMAIL = prev
    }
  })

  it("/contact#apply never renders an empty route when no address is configured", () => {
    const prevE = process.env.NEXT_PUBLIC_CONTACT_EMAIL
    const prevP = process.env.NEXT_PUBLIC_CONTACT_PHONE
    delete process.env.NEXT_PUBLIC_CONTACT_EMAIL
    delete process.env.NEXT_PUBLIC_CONTACT_PHONE
    try {
      const main = mainOf(<ContactPage />)
      const apply = main.querySelector("#apply") as HTMLElement
      expect(apply).not.toBeNull()
      expect(apply.querySelector('a[href^="mailto:"], a[href^="tel:"]')).toBeNull()
      expect(apply.textContent).toMatch(/not yet published/i)
    } finally {
      if (prevE !== undefined) process.env.NEXT_PUBLIC_CONTACT_EMAIL = prevE
      if (prevP !== undefined) process.env.NEXT_PUBLIC_CONTACT_PHONE = prevP
    }
  })

  it("the vendor sign-in page offers an apply route to someone who has no account yet", () => {
    render(<SignInPage />)
    const main = document.querySelector("main") as HTMLElement
    expect(main.querySelector('a[href="/contact#apply"]')).not.toBeNull()
  })
})

describe("source hygiene: the rulings are cited where the copy lives", () => {
  it.each([
    "lib/pricing.ts",
    "lib/positioning.ts",
    "app/pricing/page.tsx",
    "app/about/page.tsx",
    "app/legal/merchant-terms/page.tsx",
  ])("%s cites the 2026-11-04 owner ruling", (rel) => {
    const src = fs.readFileSync(path.join(process.cwd(), rel), "utf8")
    expect(src).toMatch(/2026-11-04/)
  })
})
