/**
 * orderWithFeatured (quick 261102-r6u, D-03): featured first in configured
 * order, then the listing in its own order, no slug twice. Featured [] returns
 * the listing unchanged.
 */
import { featuredSlugsOf, orderWithFeatured } from "@/lib/featured-shops"
import type { PublicShop } from "@/types/storefront"

function shop(slug: string): PublicShop {
  return {
    slug,
    name: slug,
    description: null,
    address: null,
    logoUrl: null,
    bannerUrl: null,
    phone: null,
    email: null,
    latitude: null,
    longitude: null,
    openingHours: null,
    deliveryInfo: null,
    minimumOrderPennies: null,
    deliveryFeePennies: null,
    freeDeliveryThresholdPennies: null,
    tags: null,
  } as PublicShop
}

const slugs = (list: PublicShop[]) => list.map((s) => s.slug)

describe("orderWithFeatured", () => {
  it("puts the featured shops first, in CONFIGURED order, then the listing in its own order", () => {
    // Configured order (p, m) disagrees with alphabetical order (m, p) on purpose.
    const merged = orderWithFeatured([shop("p"), shop("m")], [shop("a"), shop("b")])
    expect(slugs(merged)).toEqual(["p", "m", "a", "b"])
  })

  it("never shows a slug twice: a featured shop also on the listing keeps its featured position", () => {
    const merged = orderWithFeatured([shop("m"), shop("p")], [shop("a"), shop("m"), shop("b"), shop("p")])
    expect(slugs(merged)).toEqual(["m", "p", "a", "b"])
    expect(new Set(slugs(merged)).size).toBe(merged.length)
  })

  it("returns the listing unchanged, in identical order, when nothing is featured", () => {
    const listing = [shop("c"), shop("a"), shop("b")]
    const merged = orderWithFeatured([], listing)
    expect(slugs(merged)).toEqual(["c", "a", "b"])
    expect(merged).not.toBe(listing) // a copy: the caller's array is never aliased
    expect(merged.every((s, i) => s === listing[i])).toBe(true)
  })

  it("does not reorder the listing's own entries", () => {
    const merged = orderWithFeatured([shop("x")], [shop("z"), shop("y"), shop("a")])
    expect(slugs(merged)).toEqual(["x", "z", "y", "a"])
  })
})

describe("featuredSlugsOf", () => {
  it("returns the slugs in configured order", () => {
    expect(featuredSlugsOf([shop("p"), shop("m")])).toEqual(["p", "m"])
    expect(featuredSlugsOf([])).toEqual([])
  })
})
