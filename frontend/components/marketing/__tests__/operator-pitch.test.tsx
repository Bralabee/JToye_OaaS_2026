import fs from "fs"
import path from "path"
import { fireEvent, render, screen, within } from "@testing-library/react"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import { OperatorPitch } from "@/components/marketing/operator-pitch"
import { PRICING_CARD_FEE_LINE, STRIPE_UK_PRICING_URL } from "@/lib/pricing"
import ForOperatorsPage from "@/app/for-operators/page"

/**
 * The stock scale token these bands used to carry, named ONCE per suite.
 *
 * The assertion it backs is that the swap is COMPLETE per site (T-35-24): a band
 * left carrying both the old token and the tier class renders identically today
 * and diverges silently the moment the tier's value moves, which is the whole
 * failure mode a declared contract exists to prevent.
 *
 * NOTE FOR PLAN 35-10: a static gate over this token must exclude `__tests__/`,
 * exactly as plan 35-02 recorded for the shed dashboard width class. The literal
 * below is the assertion, not a surviving usage.
 */
const STOCK_BAND_TOKEN = "max-w-7xl"

describe("OperatorPitch design tokens (Surface C re-skin)", () => {
  const src = fs.readFileSync(
    path.join(process.cwd(), "components/marketing/operator-pitch.tsx"),
    "utf8"
  )

  it("uses only design tokens — zero hardcoded hex", () => {
    // The re-skin migrates the bespoke navy/orange/yellow palette onto tokens.
    expect(src).not.toMatch(/#[0-9a-fA-F]{3,8}/)
  })

  it("renders on-token classes within the landing brand family (oxblood/cream/gold/amber)", () => {
    // The surface used to run its own navy/emerald/mono skin, which read as a
    // different product to anyone arriving from `/`. It now shares the landing
    // brand thread (jtoyedigital.co.uk oxblood + Work Sans + amber appetite).
    expect(src).toMatch(/bg-oxblood/)
    expect(src).toMatch(/text-cream|bg-cream/)
    // Gold appears on the hero only, as the secondary link's underline.
    expect(src).toMatch(/decoration-gold/)
    expect(src).toMatch(/bg-amber-500/)
  })

  it("does not regress to the old off-brand navy/emerald/mono skin", () => {
    expect(src).not.toMatch(/bg-slate-900|bg-slate-800|bg-emerald-50|border-emerald-200/)
    expect(src).not.toMatch(/font-mono/)
  })

  it("obeys the public display cap — no font-black / text-7xl / serif", () => {
    expect(src).not.toMatch(/font-black|text-7xl|text-8xl|font-serif/)
  })

  it("wraps the operator pitch in the shared PublicShell (connected surface)", () => {
    render(<ForOperatorsPage />)
    // The shared public header exposes a "For operators" nav link (header-only copy),
    // proving PublicShell rendered around the pitch.
    const headerLink = screen.getByRole("link", { name: /^for operators$/i })
    expect(headerLink.getAttribute("href")).toBe("/for-operators")
  })
})

describe("OperatorPitch", () => {
  it("leads with the one-kitchen headline and names both rhythms in the lede", () => {
    render(<OperatorPitch />)

    const h1s = screen.getAllByRole("heading", { level: 1 })
    expect(h1s).toHaveLength(1)
    expect(h1s[0]).toHaveTextContent("One kitchen. Weekday counter, weekend catering.")
    expect(screen.getByText(/established West African food businesses in London/i)).toBeInTheDocument()
    // The superseded headline must not survive anywhere.
    expect(screen.queryByText(/keep the order/i)).toBeNull()
  })

  it("has exactly the five sections, each named by its own heading", () => {
    render(<OperatorPitch />)
    const h2s = screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent)
    expect(h2s).toEqual([
      "Both rhythms, one kitchen",
      "Compliance is part of the order",
      "Pricing",
      "How the assisted pilot runs",
    ])
  })

  it("no longer carries the July pack, the fit check, or the stale framing", () => {
    const { container } = render(<OperatorPitch />)

    // Non-vacuity control: the instrument finds a link that IS there.
    expect(container.querySelectorAll('a[href="/dashboard/onboarding"]').length).toBeGreaterThan(0)
    expect(container.querySelector("a[href*='pilot-pack']")).toBeNull()
    expect(container.querySelector("a[href$='.pdf']")).toBeNull()
    expect(container.querySelector("a[download]")).toBeNull()
    expect(screen.queryByText(/download|return this pack/i)).toBeNull()
    expect(screen.queryByText(/edition 10 July 2026|UK pilot hypothesis/i)).toBeNull()
    expect(screen.queryByText(/validation tracks?/i)).toBeNull()
    expect(screen.queryByText(/should not be sold as the same job/i)).toBeNull()
    expect(screen.queryByText(/not production connected-account settlement/i)).toBeNull()
    expect(screen.queryByText(/£119|£195|£199|pricing test|high volume/i)).toBeNull()
    // The fit check (selects, copyable summary) is gone with its state.
    expect(container.querySelectorAll("select, textarea")).toHaveLength(0)
    expect(screen.queryByRole("button")).toBeNull()
    expect(screen.queryByText(/fit summary|check your pilot fit/i)).toBeNull()
  })

  it("quantifies the aggregator leak in section 2's intro, not as a display number", () => {
    render(<OperatorPitch />)
    expect(screen.getByText(/28% aggregator commission on £10k a month of orders hands over £2,800 a month/i)).toBeInTheDocument()
    expect(screen.getByText(/direct storefront cost £39 \+ 0\.5%, plus Stripe.s card fees, which you would pay either way\./i)).toBeInTheDocument()
    // £2,800 appears exactly once, as body copy.
    expect(screen.getAllByText(/£2,800/)).toHaveLength(1)
  })

  it("repeats one primary CTA (hero and close) and offers one secondary link, to the pricing section", () => {
    const { container } = render(<OperatorPitch />)

    const ctas = screen.getAllByRole("link", { name: /start your application/i })
    expect(ctas).toHaveLength(2)
    for (const cta of ctas) {
      expect(cta).toHaveAttribute("href", "/dashboard/onboarding")
      expect(cta.classList.contains("bg-amber-500")).toBe(true)
    }
    const secondary = screen.getByRole("link", { name: /see pricing/i })
    expect(secondary).toHaveAttribute("href", "#pricing")
    expect(secondary.classList.contains("bg-amber-500")).toBe(false)
    // The anchor target exists.
    expect(container.querySelector("#pricing")).not.toBeNull()
    expect(screen.getByText(/a person reviews every application, and nothing is charged until a plan is agreed/i)).toBeInTheDocument()
  })

  it("renders the hero image and the three section images with reserved boxes", () => {
    const { container } = render(<OperatorPitch />)
    const imgs = Array.from(container.querySelectorAll("img"))
    expect(imgs).toHaveLength(4)
    for (const img of imgs) {
      expect(img.getAttribute("src")).toMatch(/^\/marketing\/operators\/.+\.webp$/)
      expect(Number(img.getAttribute("width"))).toBeGreaterThan(0)
      expect(Number(img.getAttribute("height"))).toBeGreaterThan(0)
      expect((img.getAttribute("alt") ?? "").length).toBeGreaterThan(20)
    }
    // Only the hero is the LCP candidate.
    expect(imgs.filter((img) => img.getAttribute("fetchpriority") === "high")).toHaveLength(1)
    expect(imgs[0].closest("[data-op-media]")).not.toBeNull()
  })

  it("pins the margin number to the Standard plan in the shared PRICING module, once", () => {
    const { container } = render(<OperatorPitch />)
    const figure = container.querySelector("[data-op-figure]") as HTMLElement
    expect(figure).not.toBeNull()
    expect(figure).toHaveTextContent("0.5%")
    expect(figure).toHaveTextContent(/of your direct sales, plus £39 a month per location/i)
    expect(figure).toHaveTextContent(/card fees go to stripe, not to us/i)
    // The figure in the docket and the Standard row come from the same row.
    const standard = screen.getByRole("row", { name: /standard/i })
    expect(standard).toHaveTextContent("£39")
    expect(standard).toHaveTextContent("0.5%")
  })

  it("prints exactly the billed plans in one real table, and nothing the code does not bill", () => {
    const { container } = render(<OperatorPitch />)
    const tables = container.querySelectorAll("table")
    expect(tables).toHaveLength(1)
    const table = tables[0]
    expect(table.querySelector("caption")).toHaveTextContent(/platform pricing per location/i)
    expect(Array.from(table.querySelectorAll("thead th")).map((th) => th.textContent)).toEqual(["Plan", "Monthly", "Platform fee"])
    const rows = Array.from(table.querySelectorAll("tbody tr"))
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent(/Standard.*£39 per location.*0\.5% of direct sales/)
    expect(rows[1]).toHaveTextContent(/Flat.*£79–£149 per location.*0% of sales/)
    expect(rows[2]).toHaveTextContent(/Batch pay-as-you-go.*£0.*6% of direct sales/)
    // Every body row has a row header, and every figure cell carries its mobile label.
    for (const row of rows) {
      expect(row.querySelectorAll('th[scope="row"]')).toHaveLength(1)
      expect(Array.from(row.querySelectorAll("td")).map((td) => td.getAttribute("data-label"))).toEqual(["Monthly", "Platform fee"])
    }
    expect(screen.getByText(/assisted setup from £99, scoped to the work agreed/i)).toBeInTheDocument()
    expect(screen.getByText(/charged on your own stripe account/i)).toBeInTheDocument()
    // The four strings the billing code does not support stay absent.
    const text = container.textContent ?? ""
    for (const banned of ["£79-£119", "£79–£119", "£149-£199", "£149–£199", "£195", "£99 flat"]) {
      expect(text).not.toContain(banned)
    }
  })

  it("keeps the two rhythms distinct, each mapped to shipped features", () => {
    render(<OperatorPitch />)

    expect(screen.getByRole("heading", { name: /daily counter and local delivery/i })).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: /weekend batch and catering/i })).toBeInTheDocument()
    expect(screen.queryByText(/validation tracks?/i)).toBeNull()
    expect(screen.getByText(/instant web and qr ordering/i)).toBeInTheDocument()
    expect(screen.getByText(/one-tap kitchen pause/i)).toBeInTheDocument()
    expect(screen.getByText(/caps on cash orders/i)).toBeInTheDocument()
    expect(screen.getByText(/fulfilment slots with cut-offs and capacity/i)).toBeInTheDocument()
    expect(screen.getByText(/prep deposits, then a balance payment link/i)).toBeInTheDocument()
    expect(screen.getByText(/one consolidated cook sheet/i)).toBeInTheDocument()
    expect(screen.getByText(/a whatsapp pre-order link to share/i)).toBeInTheDocument()
  })

  it("states compliance and the dated limits plainly, once each", () => {
    render(<OperatorPitch />)

    expect(screen.getByText("Allergen engine").tagName).toBe("DT")
    expect(screen.getByText(/ppds labels for a 62 mm thermal printer/i)).toBeInTheDocument()
    expect(screen.getByText(/you remain the food business operator/i)).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: /not yet live/i })).toBeInTheDocument()
    expect(screen.getByText(/as of 19 October 2026/i)).toBeInTheDocument()
    expect(screen.getByText(/card payments are in stripe test mode until the first production tenant/i)).toBeInTheDocument()
    expect(screen.getByText(/online-only\. there is no offline mode/i)).toBeInTheDocument()
    expect(screen.getByText(/whatsapp is a generated pre-order link, not an automated whatsapp channel/i)).toBeInTheDocument()
    expect(screen.getByText(/a storefront does not bring customers by itself/i)).toBeInTheDocument()
    expect(screen.getAllByText(/does not bring customers/i)).toHaveLength(1)
    expect(screen.getByRole("heading", { name: /is this for your kitchen/i })).toBeInTheDocument()
  })

  it("keeps four pilot steps and step 3 names the kitchen display", () => {
    const { container } = render(<OperatorPitch />)
    const steps = container.querySelectorAll("[data-pilot-step]")
    expect(steps).toHaveLength(4)
    expect(steps[2]).toHaveTextContent(/kitchen display/i)
  })

  it("uses no uppercase-tracked eyebrows and no icon library", () => {
    const src = fs.readFileSync(path.join(process.cwd(), "components/marketing/operator-pitch.tsx"), "utf8")
    expect(src).not.toMatch(/\buppercase\b/)
    expect(src).not.toMatch(/tracking-\[0\.1\d?em\]/)
    expect(src).not.toMatch(/lucide-react/)
  })

  it("keeps the Standard row's amber stripe: no all-sides border colour at md+", () => {
    const src = fs.readFileSync(path.join(process.cwd(), "components/marketing/operator-pitch.tsx"), "utf8")
    // md:border-<colour> would set every side and repaint the stripe oxblood at desktop widths.
    expect(src).not.toMatch(/(?<!max-)md:border-oxblood\/15/)
    expect(src).toMatch(/md:border-b-oxblood\/15/)
    const { container } = render(<OperatorPitch />)
    const header = container.querySelector("tbody tr th") as HTMLElement
    expect(header.classList.contains("border-amber-500")).toBe(true)
    expect(header.className).not.toMatch(/(?<!max-)md:border-oxblood\/15/)
  })

  it("keeps the box and the description when an image file is missing", () => {
    const { container } = render(<OperatorPitch />)
    const hero = container.querySelector("[data-op-media] img") as HTMLImageElement
    const alt = hero.getAttribute("alt")!
    fireEvent.error(hero)
    const fallback = screen.getByRole("img", { name: alt })
    expect(fallback.className).toMatch(/aspect-\[8\/5\]/)
    expect(fallback.className).toMatch(/w-full/)
    // The three section images do the same at 3:2.
    for (const img of Array.from(container.querySelectorAll("img"))) fireEvent.error(img)
    expect(container.querySelectorAll('[role="img"][class*="aspect-[3/2]"]')).toHaveLength(3)
  })

  it("takes the docket's wording from the plan entry, not from comparing table copy", () => {
    const src = fs.readFileSync(path.join(process.cwd(), "components/marketing/operator-pitch.tsx"), "utf8")
    expect(src).not.toMatch(/fee\[1\]\s*===/)
    const { container } = render(<OperatorPitch />)
    expect(container.querySelector("[data-op-figure] strong")).toHaveTextContent("of your direct sales")
  })

  it("keeps every removed-feature token out of the source", () => {
    const src = fs.readFileSync(path.join(process.cwd(), "components/marketing/operator-pitch.tsx"), "utf8")
    expect(src).not.toMatch(/pilot-pack|pdf|validation track|fit-summary|copySummary/i)
  })

  /**
   * PHASE 35 / UIX-07 — the DECLARED Marketing width tier.
   *
   * This surface already rendered at the Marketing width before the phase, but
   * only because a stock scale token happens to equal that number. A coincidence
   * nothing asserts is the same class of problem as the inherited dashboard width
   * this phase exists to remove, so the tier is now DECLARED and the declaration
   * is what these cases read.
   *
   * The count is asserted per component rather than "at least one", because a
   * band left behind is invisible at today's value.
   */
  const MARKETING_BANDS = 5

  function marketingBands(): Element[] {
    const { container } = render(<OperatorPitch />)
    return Array.from(container.querySelectorAll('[data-width-tier="marketing"]'))
  }

  it("declares the Marketing tier on every one of its band elements", () => {
    expect(marketingBands()).toHaveLength(MARKETING_BANDS)
  })

  it("carries the marketing tier class on every declared band", () => {
    const bands = marketingBands()
    // Non-vacuity: a per-band loop over an empty list passes trivially.
    expect(bands.length).toBeGreaterThan(0)
    for (const band of bands) {
      expect(band.classList.contains(WIDTH_TIER_CLASS.marketing)).toBe(true)
    }
  })

  it("carries the tier class INSTEAD of the stock token, never beside it", () => {
    const bands = marketingBands()
    expect(bands.length).toBeGreaterThan(0)
    for (const band of bands) {
      // CONTROL, run first and on this very element: classList.contains
      // demonstrably finds a token that IS present here, so the absence below is
      // a statement about the token rather than about the instrument.
      expect(band.classList.contains("mx-auto")).toBe(true)
      expect(band.classList.contains(STOCK_BAND_TOKEN)).toBe(false)
    }
  })

  it("leaves the typographic measure clamps inside the band alone", () => {
    render(<OperatorPitch />)

    // A headline measure is not a page band: it is orthogonal to the tier and
    // must survive the swap untouched.
    const headline = screen.getByRole("heading", { level: 1 })
    expect(headline.classList.contains("max-w-4xl")).toBe(true)
    expect(headline.hasAttribute("data-width-tier")).toBe(false)
  })
})

/**
 * SW-CLR-04 and SW-CLR-06 (the 2026-10-19 persona swarm epic). The persona swarm found that the
 * only next step on this page was "Start your application", which ends at a
 * sign-in — no way to ask a question first — and that the pricing section
 * linked no terms. Each CTA now has a contact link beside it, and the pricing
 * footnote links the terms page.
 */
describe("OperatorPitch contact and terms routes (persona swarm, SW-CLR-04 and SW-CLR-06)", () => {
  it("offers a contact link beside each application CTA", () => {
    const { container } = render(<OperatorPitch />)
    const ctas = screen.getAllByRole("link", { name: /start your application/i })
    // Control: both CTAs are still there.
    expect(ctas).toHaveLength(2)
    for (const cta of ctas) {
      const group = cta.parentElement
      expect(group).not.toBeNull()
      expect(group?.querySelector('a[href="/contact"]')).not.toBeNull()
    }
    expect(container.querySelectorAll('a[href="/contact"]')).toHaveLength(2)
  })

  it("links the terms of use from the pricing section", () => {
    const { container } = render(<OperatorPitch />)
    const pricing = container.querySelector("#pricing")
    expect(pricing).not.toBeNull()
    const terms = pricing?.querySelector('a[href="/legal/terms"]')
    expect(terms).not.toBeNull()
    expect(terms).toHaveTextContent(/terms of use/i)
  })
  // SW-CLR-06 (owner ruling 2026-11-01): no rate is printed; the card-fee sentence
  // links Stripe's UK pricing page, from the constant lib/pricing.ts pins.
  it("links Stripe's UK pricing page from the card-fee sentence, in a new tab", () => {
    const { container } = render(<OperatorPitch />)
    const pricing = container.querySelector("#pricing") as HTMLElement
    expect(pricing).not.toBeNull()
    const link = within(pricing).getByRole("link", { name: /stripe.s uk pricing/i })
    expect(link).toHaveAttribute("href", STRIPE_UK_PRICING_URL)
    expect(link).toHaveAttribute("target", "_blank")
    expect(link.getAttribute("rel") ?? "").toMatch(/\bnoopener\b/)
    expect(link).toHaveAccessibleName(/opens in a new tab/i)
    // It sits beside the fee sentence, not elsewhere on the page.
    expect(link.closest("p")).toHaveTextContent(PRICING_CARD_FEE_LINE)
  })
})
