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
import { render, screen } from "@testing-library/react"
import Home from "@/app/page"
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

  it("renders the shared public footer (allergen note + guide link)", async () => {
    render(await Home())
    expect(
      screen.getByText(/allergen info available on all products/i)
    ).toBeTruthy()
    const guideLink = screen.getByRole("link", {
      name: /business model guide/i,
    })
    expect(guideLink.getAttribute("href")).toBe("/business-model-guide")
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
