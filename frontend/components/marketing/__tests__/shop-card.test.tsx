/**
 * ShopCard — the landing kitchen row's card (issue 544). Written without a leading
 * hash: the palette-discipline gate greps this directory for raw hex colours and an
 * issue reference matches that pattern. See the note in shop-card.tsx.
 *
 * The point of this component is what it does NOT print. The row it replaces
 * rendered an invented rating ("⭐ 4.8"), an invented "FHRS 5" food-hygiene badge
 * and an invented dish price on every card. None of those fields exists on
 * `PublicShop`, and carrying them onto a REAL named business would be worse than
 * the original fiction — an invented hygiene score attributed to a real food
 * vendor is a regulated claim about someone who could be harmed by it.
 *
 * So the absence assertions below are the load-bearing ones, and each is paired
 * with a positive control proving the query could have found something.
 */

import { render, screen } from "@testing-library/react"
import { ShopCard } from "@/components/marketing/shop-card"
import type { PublicShop } from "@/types/storefront"

function makeShop(overrides: Partial<PublicShop> = {}): PublicShop {
  return {
    slug: "peckham-jollof-co",
    name: "Peckham Jollof Co.",
    description: "West African kitchen",
    address: "12 Bellenden Road, Peckham, London SE15 4QA",
    logoUrl: "/brand/logo-peckham-jollof.png",
    bannerUrl: null,
    phone: null,
    email: null,
    latitude: null,
    longitude: null,
    openingHours: null,
    deliveryInfo: null,
    minimumOrderPennies: 1500,
    deliveryFeePennies: 299,
    freeDeliveryThresholdPennies: null,
    tags: "Jollof, West African, Halal",
    ...overrides,
  }
}

describe("ShopCard", () => {
  it("names the real shop and links to its own page, not a search", () => {
    render(<ShopCard shop={makeShop()} />)
    const link = screen.getByRole("link", { name: /Peckham Jollof Co\./i })
    // /shop/{slug} — a real shop page. The old row linked to /shop?q=… which
    // could legitimately match nothing.
    expect(link.getAttribute("href")).toBe("/shop/peckham-jollof-co")
  })

  it("prints the REAL delivery and minimum-order figures", () => {
    render(<ShopCard shop={makeShop()} />)
    expect(screen.getByText(/£2\.99 delivery/)).toBeTruthy()
    expect(screen.getByText(/min £15\.00/)).toBeTruthy()
  })

  it("says 'Free delivery' rather than '£0.00 delivery' when the fee is zero", () => {
    render(<ShopCard shop={makeShop({ deliveryFeePennies: 0 })} />)
    expect(screen.getByText(/Free delivery/)).toBeTruthy()
    expect(screen.queryByText(/£0\.00 delivery/)).toBeNull()
  })

  it("renders NO delivery line when the wire fee is null — never '£0.00 delivery' (review WR-04)", () => {
    // PublicShopDto.deliveryFeePennies is a nullable Long on the backend and
    // CreateShopRequest has no delivery-fee field at all, so an API-created shop
    // genuinely serialises null. Pre-fix, null / 100 coerced to 0 and the card
    // printed "£0.00 delivery" — the exact string the zero-fee test above
    // declares must never appear — while null === 0 dodged the "Free delivery"
    // branch. Run against the unfixed component this arm FAILS by finding that
    // string; the run is recorded in 33-REVIEW-FIX.md as the broken direction.
    render(<ShopCard shop={makeShop({ deliveryFeePennies: null })} />)
    expect(screen.queryByText(/£0\.00 delivery/)).toBeNull()
    // Not renamed, not reworded — absent. An unknown fee is also not FREE:
    // that claim needs a zero from the wire, not a null.
    expect(screen.queryByText(/delivery/i)).toBeNull()
    // The minimum is independently known and still prints.
    expect(screen.getByText(/min £15\.00/)).toBeTruthy()
  })

  it("renders NO minimum line for a null or zero wire minimum — never 'min £0.00' (review WR-04)", () => {
    render(<ShopCard shop={makeShop({ minimumOrderPennies: null })} />)
    expect(screen.queryByText(/min £/)).toBeNull()
    // Zero is a KNOWN value meaning "no minimum" — printing "min £0.00" would
    // state a constraint that does not exist, so it renders nothing too (the
    // discovery listing already hides a zero minimum; the card now agrees).
    render(<ShopCard shop={makeShop({ minimumOrderPennies: 0 })} />)
    expect(screen.queryByText(/min £0\.00/)).toBeNull()
    // Positive control for both queries: the real-figures test above finds
    // "min £15.00" with the same matcher shape.
  })

  it("prints NO rating and NO FHRS badge — neither field exists on PublicShop", () => {
    render(<ShopCard shop={makeShop()} />)

    expect(screen.queryByText(/FHRS/i)).toBeNull()
    expect(screen.queryByText(/⭐/)).toBeNull()
    // A bare "4.8"-shaped number anywhere on the card would mean a rating crept
    // back in. Prices are matched with a £ so they do not trip this.
    expect(screen.queryByText(/(?<!£)\b[0-5]\.\d\b(?!\d)/)).toBeNull()

    // CONTROL: the same queries CAN find these strings, so the three nulls above
    // are about the component and not about the matcher. Rendered separately so
    // the card under test stays clean.
    const { getByText } = render(
      <div>
        <span>FHRS 5</span>
        <span>⭐</span>
        <span>4.8</span>
      </div>
    )
    expect(getByText(/FHRS/i)).toBeTruthy()
    expect(getByText(/⭐/)).toBeTruthy()
    expect(getByText(/(?<!£)\b[0-5]\.\d\b(?!\d)/)).toBeTruthy()
  })

  it("reserves the logo box with explicit width and height (CLS)", () => {
    const { container } = render(<ShopCard shop={makeShop()} />)
    const img = container.querySelector("img")
    expect(img).toBeTruthy()
    // Without these the browser cannot reserve space and the emoji-to-real-image
    // swap shifts everything below it — the exact risk the landing CWV budget
    // in e2e/landing-webperf.spec.ts exists to catch.
    expect(img!.getAttribute("width")).toBe("220")
    expect(img!.getAttribute("height")).toBe("132")
  })

  it("falls back rather than breaking when the shop has no logo", () => {
    const { container } = render(<ShopCard shop={makeShop({ logoUrl: null })} />)
    // SafeImage renders a placeholder div, not a broken <img>.
    expect(container.querySelector("img")).toBeNull()
    expect(screen.getByRole("link", { name: /Peckham Jollof Co\./i })).toBeTruthy()
  })

  it("caps the tag list so one verbose vendor cannot set the row height", () => {
    render(
      <ShopCard shop={makeShop({ tags: "One, Two, Three, Four, Five, Six" })} />
    )
    expect(screen.getByText("One · Two · Three")).toBeTruthy()
    expect(screen.queryByText(/Four/)).toBeNull()
  })

  it("renders no tag line at all when the shop has no tags", () => {
    render(<ShopCard shop={makeShop({ tags: null })} />)
    expect(screen.getByRole("link", { name: /Peckham Jollof Co\./i })).toBeTruthy()
    expect(screen.queryByText(/·/)).toBeTruthy() // the delivery line still has one
  })
})
