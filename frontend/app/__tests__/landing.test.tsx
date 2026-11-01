/**
 * Tests for the public landing page (UIX-01, backlog #4).
 *
 * Contract:
 *  - `/` renders a real server-rendered landing page (NO redirect), with a
 *    split-persona heading that names BOTH audiences.
 *  - A customer door links `/shop`; an operator door links `/for-operators`.
 *  - The shared public header + footer render on `/`.
 *  - The page is a Server Component (no "use client") so the CSP nonce reaches
 *    it — asserted structurally against the source file (the #89 failure mode).
 */

import fs from "fs"
import path from "path"
import { render, screen, within } from "@testing-library/react"
import Home, { metadata as homeMetadata } from "@/app/page"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"
import type { PublicShop } from "@/types/storefront"

/**
 * MIGRATED for 33-03: `Home()` is now `async`, so the five rendering tests below
 * were passing a PROMISE to `render()` and would have broken. They are migrated
 * to `render(await Home())`, NOT deleted and NOT weakened — each one guards a
 * displaced good the kitchen-row rewrite promised to preserve: the split-persona
 * H1, both persona door routes, the shared header and the shared footer.
 *
 * The sixth test (the source-level `use client` check) is deliberately untouched.
 * It does not render, so it never broke, and it is the guard that both this plan
 * and 33-07 depend on to keep `lib/storefront-server.ts` — which resolves the
 * INTERNAL core host — out of every client import chain.
 *
 * `loadShopList` is mocked rather than left to hit the network: these five assert
 * server-rendered chrome, not the shop row, and a real fetch would make them
 * depend on a running stack. The row itself is asserted against the SERVED HTML
 * in e2e/storefront-ssr-seo.spec.ts, which is the only instrument that can prove
 * the names arrive before JavaScript.
 */

const FIXTURE_SHOP: PublicShop = {
  slug: "mama-ades-kitchen",
  name: "Mama Ade's Kitchen",
  description: "Nigerian home cooking",
  address: "48 Rye Lane, Peckham, London SE15 5BS",
  logoUrl: "/brand/logo-mama-ades.png",
  bannerUrl: null,
  phone: null,
  email: null,
  latitude: null,
  longitude: null,
  openingHours: null,
  deliveryInfo: null,
  minimumOrderPennies: 1000,
  deliveryFeePennies: 250,
  freeDeliveryThresholdPennies: null,
  tags: "Nigerian, Jollof",
}

jest.mock("@/lib/storefront-server", () => ({
  loadShopList: jest.fn(async () => ({
    state: "ok",
    data: { content: [FIXTURE_SHOP], totalElements: 1, totalPages: 1, number: 0, size: 8 },
  })),
}))

/**
 * `next/headers` throws "`headers` was called outside a request scope" under jest —
 * there is no request store. `Home()` reads it for the CSP nonce it puts on the
 * JSON-LD script.
 *
 * This is not a test convenience: it caught a real gap. Adding the `headers()`
 * call broke all eight rendering tests here, and `npm run build` (rc=0, zero type
 * errors) and 41 Playwright tests were ALL GREEN over it, because neither runs
 * jest. A type-check and an E2E suite cannot see a unit suite that does not run.
 *
 * Returning an empty Map is the honest stub: `.get("x-nonce")` yields undefined,
 * which is exactly what the page gets when middleware has not set one, so the
 * component takes its real no-nonce path rather than a fabricated one.
 */
jest.mock("next/headers", () => ({
  headers: jest.fn(async () => new Map<string, string>()),
}))

describe("Public landing page (/)", () => {
  it("renders a split-persona H1 naming both audiences (no redirect)", async () => {
    render(await Home())
    const h1 = screen.getByRole("heading", { level: 1 })
    // Names both audiences: local kitchens (customer) + running one (operator).
    expect(h1.textContent).toMatch(/kitchen/i)
    expect(h1.textContent).toMatch(/run/i)
  })

  it("routes the customer door to /shop", async () => {
    render(await Home())
    const customerDoor = screen.getByRole("link", {
      name: /order food near you/i,
    })
    expect(customerDoor.getAttribute("href")).toBe("/shop")
  })

  it("routes the operator door to /for-operators", async () => {
    render(await Home())
    const operatorDoor = screen.getByRole("link", {
      name: /run your food business/i,
    })
    expect(operatorDoor.getAttribute("href")).toBe("/for-operators")
  })

  it("renders the shared public header (persona nav)", async () => {
    render(await Home())
    // Header nav exposes the four public routes; "For operators" is header-only
    // copy (the footer says "Become a vendor"), so it proves the header rendered.
    const headerLink = screen.getByRole("link", { name: /^for operators$/i })
    expect(headerLink.getAttribute("href")).toBe("/for-operators")
  })

  it("renders the shared public footer (allergen note + vendor link)", async () => {
    render(await Home())
    expect(
      screen.getByText(/allergen info available on all products/i)
    ).toBeTruthy()
    // Was the business-model-guide link, which SW-CLR-05 (swarm epic #964)
    // removed from the footer; "Become a vendor" is footer-only copy, so it
    // still proves the footer rendered.
    const vendorLink = screen.getByRole("link", {
      name: /become a vendor/i,
    })
    expect(vendorLink.getAttribute("href")).toBe("/for-operators")
  })

  it("renders REAL shops in the kitchen row, and none of the invented vendors (#544)", async () => {
    render(await Home())

    // The real shop, from the data source.
    expect(screen.getByText("Mama Ade's Kitchen")).toBeTruthy()
    // ...linking to its own page, not to a search that might match nothing.
    const card = screen.getByRole("link", { name: /Mama Ade's Kitchen/i })
    expect(card.getAttribute("href")).toBe("/shop/mama-ades-kitchen")

    // The five invented vendors are gone. `Mama's Kitchen` is listed on purpose:
    // it is a near-duplicate of the real `Mama Ade's Kitchen`, so a substring
    // check would pass on the real name. queryByText is exact by default.
    for (const invented of [
      "Mama's Kitchen",
      "Spice Route",
      "Olive & Vine",
      "Crumb & Co",
      "Hanoi House",
    ]) {
      expect(screen.queryByText(invented)).toBeNull()
    }

    // The invented rating and FHRS badge are gone with them.
    expect(screen.queryByText(/FHRS/i)).toBeNull()
  })

  it("keeps the DishScroller affordance, with its label byte-identical", async () => {
    render(await Home())
    // This exact string is marketing-dish-scroller.spec.ts:19's selector. It is
    // an aria-label on a scroll region, not a heading, so the no-locality-claim
    // criterion deliberately does not reach it.
    expect(screen.getByRole("region", { name: "Dishes cooking near you" })).toBeTruthy()
  })

  it("has no HEADING claiming proximity while no coordinate is held (#544)", async () => {
    render(await Home())

    // SCOPED to headings, deliberately. The blanket form — "the string 'near
    // you' is absent from the landing DOM" — is UNSATISFIABLE: / renders it at
    // four sites and three are legitimate. See the comment in
    // e2e/storefront-ssr-seo.spec.ts for the full record.
    const headings = screen.getAllByRole("heading")
    const offending = headings.filter((h) => /near you/i.test(h.textContent ?? ""))
    expect(offending.map((h) => h.textContent)).toEqual([])

    // ...and the CONTROL that proves this is scoping rather than narrowing: the
    // two deliberately out-of-scope sites must STILL be present.
    expect(screen.getByText("Order food near you")).toBeTruthy()
    expect(screen.getByText(/Find independent kitchens near you/i)).toBeTruthy()
  })

  it("is a Server Component — no client directive in the source", () => {
    const src = fs.readFileSync(
      path.join(process.cwd(), "app/page.tsx"),
      "utf8"
    )
    expect(src).not.toMatch(/["']use client["']/)
    // And it must NOT blind-redirect any more.
    expect(src).not.toMatch(/redirect\(["']\/dashboard["']\)/)
  })
})

/**
 * The landing page's content bands at the Marketing tier (ORCH-04, orchestrator
 * decision 2026-08-29 — CONTEXT.md section 4b).
 *
 * THE DEFECT, MEASURED. This page rendered its four content bands at 1152px
 * while the header and footer rails wrapped around them rendered at 1280px, so
 * the landing content sat 128px inside its own chrome and the nav and the hero
 * did not share a left edge (PATTERNS F-2). This is the one surface in phase 35
 * whose width VALUE actually changes; every other surface in the phase is a
 * rename at the same number.
 *
 * THE COUNT IS THE IMPORTANT ASSERTION. A partial migration — three bands moved,
 * one left behind — is the likeliest real defect here, it is invisible to a spot
 * check, and it ships a page that disagrees with itself down its own length. So
 * the number of tiered bands is pinned, not merely their existence (T-35-21).
 *
 * SCOPED TO `main`, DELIBERATELY. The shared chrome now declares the same tier,
 * so a document-wide query returns SIX (four bands plus two rails) and a count
 * of four against it would be wrong in a way that looks right. Scoping to the
 * main landmark also makes the assertion say what it means: these are the
 * PAGE's bands, not the shell's.
 *
 * The old stock token's absence is asserted separately, because twMerge does NOT
 * resolve it against a tier class — measured in this plan: `max-w-7xl` plus the
 * tier class survives twMerge as BOTH tokens. A half-done rename therefore
 * leaves two caps on one element and renders at whichever wins the cascade.
 */
describe("Landing content bands declare the Marketing tier (ORCH-04)", () => {
  /** The stock scale token the four bands carried before phase 35. */
  const SHED_WIDTH_TOKEN = "max-w-6xl"

  /** How many content bands this page has. Four, and the number is the point. */
  const EXPECTED_BAND_COUNT = 4

  function tieredBandsInMain(): HTMLElement[] {
    const main = screen.getByRole("main")
    return Array.from(
      main.querySelectorAll<HTMLElement>(`[data-width-tier="marketing"]`)
    )
  }

  it("renders exactly four Marketing-tier bands, so a partial migration reds", async () => {
    render(await Home())

    // NON-VACUITY. The kitchen-row band is conditional on there being published
    // shops, so a broken fixture would drop the count to three and read as a
    // partial migration. Prove the row rendered before trusting the number.
    expect(screen.getByRole("region", { name: "Dishes cooking near you" })).toBeTruthy()

    expect(tieredBandsInMain()).toHaveLength(EXPECTED_BAND_COUNT)
  })

  it("puts the Marketing tier CLASS on every one of those bands", async () => {
    render(await Home())
    const bands = tieredBandsInMain()

    // Control: the loop below is vacuous over an empty array.
    expect(bands.length).toBeGreaterThan(0)
    for (const band of bands) {
      // Token match, never a substring: `max-w-marketing` is a substring of
      // nothing here today, but that is a property of today's class strings.
      expect(band.classList.contains(WIDTH_TIER_CLASS.marketing)).toBe(true)
    }
  })

  it("leaves no page band on the narrower stock token it was renamed from", async () => {
    const { container } = render(await Home())
    const main = screen.getByRole("main")

    // CONTROL, first: the instrument can find a max-width class in this DOM at
    // all — otherwise the absence below is a statement about the query.
    expect(container.querySelectorAll('[class*="max-w-"]').length).toBeGreaterThan(0)

    const stragglers = Array.from(main.querySelectorAll<HTMLElement>("*")).filter((el) =>
      el.classList.contains(SHED_WIDTH_TOKEN)
    )
    expect(stragglers.map((el) => el.className)).toEqual([])
  })

  it("keeps the auto margin and every padding class on all four bands", async () => {
    render(await Home())
    const bands = tieredBandsInMain()
    expect(bands).toHaveLength(EXPECTED_BAND_COUNT)

    // The displaced-goods ledger made executable: horizontal padding is what
    // stops a 1280px cap from touching the viewport edge at small sizes, and it
    // is the good most easily lost to a careless class swap.
    for (const band of bands) {
      for (const preserved of ["mx-auto", "px-4", "sm:px-6", "lg:px-8"]) {
        expect(band.classList.contains(preserved)).toBe(true)
      }
    }
  })

  it("shares its tier with the chrome — content and rails now agree", async () => {
    const { container } = render(await Home())

    // The whole point of ORCH-04. Four bands inside main plus the two shared
    // rails outside it, all speaking the same tier. Before this change the
    // rails were at 1280 and the bands at 1152.
    const everywhere = container.querySelectorAll(`[data-width-tier="marketing"]`)
    expect(everywhere).toHaveLength(EXPECTED_BAND_COUNT + 2)

    const banner = screen.getByRole("banner")
    const contentinfo = screen.getByRole("contentinfo")
    expect(banner.querySelector(`[data-width-tier="marketing"]`)).not.toBeNull()
    expect(contentinfo.querySelector(`[data-width-tier="marketing"]`)).not.toBeNull()
  })

  it("does NOT touch the hero reading measure or the search form width", async () => {
    render(await Home())

    // PRESERVED GOOD 1 — the hero sub-paragraph's typographic measure. It is
    // nested INSIDE the hero band, so widening the band cannot widen it, and it
    // must not have been swept up in the migration (PATTERNS 1c).
    const heroCopy = screen.getByText(/J'Toye connects hungry customers/i)
    expect(heroCopy.classList.contains("max-w-xl")).toBe(true)
    expect(heroCopy.hasAttribute("data-width-tier")).toBe(false)

    // PRESERVED GOOD 2 — the landing search form, named in CONTEXT.md as
    // CLS-sensitive. Its own width is a control, not a page band.
    const search = screen.getByRole("search")
    expect(search.classList.contains("max-w-xl")).toBe(true)
    expect(search.hasAttribute("data-width-tier")).toBe(false)
  })

  it("keeps the hero band's own motion hooks and section chrome intact", async () => {
    const { container } = render(await Home())

    // A NEW DOM NODE is the change that moves bounding-box and scroll-reveal
    // assertions, so the migration is in place on the existing elements. These
    // are the hooks e2e/marketing-motion.spec.ts positions against.
    for (const hook of ["data-hero-section", "data-hero-headline", "data-hero-door"]) {
      expect(container.querySelectorAll(`[${hook}]`).length).toBeGreaterThan(0)
    }

    // The hero band is still the DIRECT child of the hero section — no wrapper
    // was inserted between them.
    const heroSection = container.querySelector<HTMLElement>("[data-hero-section]")
    expect(heroSection).not.toBeNull()
    const heroBand = within(heroSection as HTMLElement)
      .getByRole("heading", { level: 1 })
      .closest(`[data-width-tier="marketing"]`)
    expect(heroBand?.parentElement).toBe(heroSection)
  })
})

/**
 * SW-CLR-03 (swarm epic #964). The landing page promised "go live in a day"
 * while /for-operators — the page the operator door leads to — says a person
 * reviews every application and the setup is assisted. Two pages on one site
 * making incompatible offers is the finding. The promise is removed here, in the
 * rendered page and in both descriptions, and replaced with /for-operators'
 * own wording.
 *
 * DELIBERATELY NOT CHANGED: the "Independent UK kitchens" positioning. The
 * swarm also flagged it against the pilot's West African, London scope, but
 * design/brief.md (confirmed by the owner 2026-11-01) describes the subject as
 * independent UK food businesses. Which one is right is an owner decision, so
 * this file does not pin either way.
 *
 * The overclaim is asserted ABSENT, after a control that the hero rendered.
 */
describe("Landing copy no longer promises go-live in a day (SW-CLR-03)", () => {
  it("the rendered page no longer says go live in a day", async () => {
    const { container } = render(await Home())
    // Control: the hero rendered.
    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument()
    expect(container.textContent).not.toMatch(/go live in a day/i)
  })

  it("the metadata no longer says go live in a day", () => {
    const og = homeMetadata.openGraph as { description?: string } | undefined
    // Control: both descriptions exist.
    expect(String(homeMetadata.description).length).toBeGreaterThan(0)
    expect(String(og?.description).length).toBeGreaterThan(0)
    expect(String(homeMetadata.description)).not.toMatch(/go live in a day/i)
    expect(String(og?.description)).not.toMatch(/go live in a day/i)
  })

  it("says what /for-operators says instead: an assisted setup and a reviewed application", async () => {
    const { container } = render(await Home())
    const text = container.textContent ?? ""
    expect(text).toMatch(/assisted setup/i)
    expect(text).toMatch(/a person reviews every application/i)
    expect(String(homeMetadata.description)).toMatch(/assisted setup/i)
  })
})

/**
 * D14 (design/decisions.md, owner rulings 2026-11-01): the elevated landing.
 * Each assertion here was shown failing against the page as it was before the
 * elevate (design/runs/2026-11-01-landing-elevate/README.md records the run).
 */
describe("Landing elevate (D14)", () => {
  // Emoji ranges the old page used (food, flags), not every symbol.
  const EMOJI = /[\u{1F300}-\u{1FAFF}\u{1F1E6}-\u{1F1FF}]/u

  it("Track live promises only status changes, in the owner's accepted words", async () => {
    render(await Home())
    expect(
      screen.getByText(
        "See your order's status change as the kitchen confirms it, prepares it and gets it ready."
      )
    ).toBeTruthy()
    expect(document.body.textContent).not.toMatch(/to your door in real time/i)
  })

  it("has no eyebrow above the h1 and no emoji anywhere in main", async () => {
    render(await Home())
    const main = screen.getByRole("main")
    // Control: the matcher finds an emoji when one is there.
    expect(EMOJI.test("🍚 Jollof")).toBe(true)
    expect(EMOJI.test(main.textContent ?? "")).toBe(false)
    expect(main.textContent).not.toMatch(/Independent UK kitchens/i)
    const h1 = screen.getByRole("heading", { level: 1 })
    expect(h1.previousElementSibling).toBeNull()
  })

  it("has no radial wash: the heat-wash hook and every gradient class are gone from main", async () => {
    const { container } = render(await Home())
    const main = screen.getByRole("main")
    expect(container.querySelectorAll("[data-hero-heatwash]")).toHaveLength(0)
    const gradients = Array.from(main.querySelectorAll<HTMLElement>("*")).filter((el) =>
      /\bbg-(gradient|\[radial-gradient)/.test(el.getAttribute("class") ?? "")
    )
    expect(gradients.map((el) => el.getAttribute("class"))).toEqual([])
    // Control: the hero section the wash sat in is still there.
    expect(container.querySelector("[data-hero-section]")).not.toBeNull()
  })

  it("shows dish photographs: one per chip, the order door's strip, and the credit line", async () => {
    render(await Home())
    for (const label of ["Grill", "Jollof", "Caribbean", "South Asian", "Vegan", "Desserts"]) {
      const chip = screen.getByRole("link", { name: label })
      expect(chip.getAttribute("href")).toMatch(/^\/shop\?q=/)
      expect(chip.querySelector("img")?.getAttribute("src")).toMatch(/marketing%2Flanding|marketing\/landing/)
    }
    const door = screen.getByRole("link", { name: /Order food near you/ })
    expect(door.querySelector("img")?.getAttribute("alt")).toBe("A plate of jollof rice with fried plantain")
    expect(screen.getByText(/^Photographs/)).toBeTruthy()
  })

  it("credits every photograph the way CC BY-SA 4.0 asks: title linked to its source, creator, licence linked, modification stated (review 1085)", async () => {
    render(await Home())
    const credits = JSON.parse(
      fs.readFileSync(path.resolve(__dirname, "../../public/marketing/landing/credits.json"), "utf8")
    ) as Array<{ title: string; creator: string; page: string; license: string; licenseUrl: string }>
    expect(credits.length).toBe(8)
    const line = screen.getByText(/^Photographs/).closest("p")!
    expect(line.textContent).toMatch(/resized/i)
    for (const c of credits) {
      const title = within(line).getByRole("link", { name: c.title })
      expect(title.getAttribute("href")).toBe(c.page)
      expect(line.textContent).toContain(c.creator)
      const licence = within(line).getAllByRole("link", { name: c.license })
      expect(licence.map((a) => a.getAttribute("href"))).toContain(c.licenseUrl)
    }
    // Control: a name that is not in the ledger is not on the page.
    expect(line.textContent).not.toContain("Nobody In Particular")
  })

  it("the How-it-works step icon still answers the data-step-active toggle HeroScene scrubs (review 1085)", async () => {
    const { container } = render(await Home())
    const steps = container.querySelectorAll("[data-hero-step]")
    expect(steps.length).toBe(3)
    for (const step of Array.from(steps)) {
      const icon = step.querySelector("span")!
      // hero-scene.tsx sets data-step-active on the step as the rail passes it;
      // the icon is the only thing that ever read it, so it must still react.
      expect(icon.getAttribute("class")).toMatch(/group-data-\[step-active=true\]:bg-/)
    }
  })

  it("keeps the kitchen card's example-photo caption in D13's words", async () => {
    render(await Home())
    // FIXTURE_SHOP is tagged "Nigerian, Jollof" with no banner.
    expect(screen.getByText("Nigerian — example photo")).toBeTruthy()
  })
})
