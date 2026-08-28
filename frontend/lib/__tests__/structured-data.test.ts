/**
 * schema.org JSON-LD builders (#447 / F-H9-SEOMETA).
 *
 * The council's acceptance criterion is that the markup is "parsed and
 * schema-validated, not merely present", so these blocks assert the SHAPE
 * consumers read — @type, Offer price/currency/availability, the day URLs — and
 * the two things a naive implementation gets wrong: writing a hostname, and
 * asserting a rating the page cannot substantiate.
 */

import {
  abs,
  serialiseJsonLd,
  productNode,
  shopStructuredData,
  shopListStructuredData,
} from "@/lib/structured-data"
import type { PublicProduct, PublicShop } from "@/types/storefront"

const shop: PublicShop = {
  slug: "brixton-village-grill",
  name: "Brixton Village Grill",
  description: "Flame-grilled peri peri chicken, kebabs and loaded sides.",
  address: "Unit 74, Brixton Village Market, London SW9 8PS",
  logoUrl: "/brand/logo-brixton-grill.png",
  bannerUrl: null,
  phone: "020 7123 4567",
  email: "hello@example.com",
  latitude: 51.46,
  longitude: -0.11,
  openingHours: { mon: "09:00 - 17:00", tue: "closed", wed: "18:00 - 02:00" },
  deliveryInfo: null,
  minimumOrderPennies: 1000,
  deliveryFeePennies: 399,
  freeDeliveryThresholdPennies: 2000,
  tags: "Grill, Peri Peri, Halal",
}

const product: PublicProduct = {
  id: "p1",
  title: "Peri Peri Chicken",
  description: "Half a flame-grilled bird.",
  imageUrl: "/img/peri.jpg",
  imageUrls: [],
  ingredientsText: "chicken",
  allergenMask: 0,
  pricePennies: 850,
  category: "Mains",
  dietaryTags: "Halal",
  preparationTimeMinutes: 15,
  featured: true,
  inStock: true,
}

const ORIGIN = "https://example.test"

function build(overrides: Partial<Parameters<typeof shopStructuredData>[0]> = {}) {
  return shopStructuredData({
    shop,
    products: { Mains: [product] },
    avgRating: 0,
    ratedCount: 0,
    origin: ORIGIN,
    ...overrides,
  })
}

describe("abs", () => {
  it("resolves against a configured origin", () => {
    expect(abs(ORIGIN, "/shop/x")).toBe("https://example.test/shop/x")
  })

  it("leaves the path RELATIVE when there is no trustworthy origin", () => {
    // The degradation that matters: the production domain is unsettled, so
    // "no origin" must never become an invented hostname.
    expect(abs(null, "/shop/x")).toBe("/shop/x")
  })
})

describe("serialiseJsonLd", () => {
  it("escapes < so vendor copy cannot close the <script> element", () => {
    const out = serialiseJsonLd({ name: "</script><img onerror=alert(1)>" })
    expect(out).not.toContain("</script>")
    expect(out).toContain("\\u003c")
    // Still valid JSON, and still the same string once parsed.
    expect(JSON.parse(out).name).toBe("</script><img onerror=alert(1)>")
  })
})

describe("productNode", () => {
  const node = productNode(product, {
    origin: ORIGIN,
    shopUrl: `${ORIGIN}/shop/brixton-village-grill`,
    shopName: shop.name,
  }) as Record<string, unknown> & { offers: Record<string, string> }

  it("is a Product with a GBP Offer priced in pounds, not pennies", () => {
    expect(node["@type"]).toBe("Product")
    expect(node.offers["@type"]).toBe("Offer")
    // 850 pennies is £8.50 — emitting "850" would advertise the wrong price.
    expect(node.offers.price).toBe("8.50")
    expect(node.offers.priceCurrency).toBe("GBP")
    expect(node.offers.availability).toBe("https://schema.org/InStock")
  })

  it("reports OutOfStock from the vendor's own flag rather than assuming", () => {
    const out = productNode({ ...product, inStock: false }, {
      origin: ORIGIN,
      shopUrl: `${ORIGIN}/shop/x`,
      shopName: shop.name,
    }) as { offers: { availability: string } }
    expect(out.offers.availability).toBe("https://schema.org/OutOfStock")
  })
})

describe("shopStructuredData", () => {
  it("emits Restaurant (a LocalBusiness subtype), an ItemList of Products and a BreadcrumbList", () => {
    const types = build().map((n) => (n as { "@type": string })["@type"])
    expect(types).toEqual(["Restaurant", "ItemList", "BreadcrumbList"])
  })

  it("maps opening hours to day URLs, dropping 'closed' and unparseable rows", () => {
    const [restaurant] = build() as [
      { openingHoursSpecification: Array<{ dayOfWeek: string; opens: string; closes: string }> },
    ]
    const spec = restaurant.openingHoursSpecification
    expect(spec.map((s) => s.dayOfWeek)).toEqual([
      "https://schema.org/Monday",
      // tue is "closed" and is omitted — publishing it as 00:00-00:00 would
      // advertise the shop as open at midnight.
      "https://schema.org/Wednesday",
    ])
    // The overnight window survives as-is; schema.org expresses it with
    // closes < opens, exactly as the vendor entered it.
    expect(spec[1]).toMatchObject({ opens: "18:00", closes: "02:00" })
  })

  it("omits aggregateRating entirely when nothing has been rated", () => {
    const [restaurant] = build() as [Record<string, unknown>]
    expect(restaurant.aggregateRating).toBeUndefined()
    expect("aggregateRating" in restaurant).toBe(false)
  })

  it("counts the reviews it AVERAGED, never the shop's total", () => {
    // The page averages a 5-review sample. Publishing 4.3 against a total of 41
    // claims 41 reviews produced that mean, which the site cannot substantiate.
    const [restaurant] = build({ avgRating: 4.3, ratedCount: 3 }) as [
      { aggregateRating: { ratingValue: number; reviewCount: number } },
    ]
    expect(restaurant.aggregateRating).toMatchObject({ ratingValue: 4.3, reviewCount: 3 })
  })

  it("derives priceRange from the menu, and omits it when there is no menu", () => {
    const [withMenu] = build({
      products: { Mains: [product, { ...product, id: "p2", pricePennies: 1250 }] },
    }) as [{ priceRange?: string }]
    expect(withMenu.priceRange).toBe("£8.50-£12.50")

    const [noMenu] = build({ products: {} }) as [Record<string, unknown>]
    expect(noMenu.priceRange).toBeUndefined()
    // ...and with no dishes there is no ItemList to emit either.
    expect(build({ products: {} }).map((n) => (n as { "@type": string })["@type"])).toEqual([
      "Restaurant",
      "BreadcrumbList",
    ])
  })

  it("writes NO hostname when no origin is configured", () => {
    const json = JSON.stringify(build({ origin: null }))
    expect(json).not.toContain("http://")
    // schema.org enumeration URLs are vocabulary, not site URLs, and are the
    // only https:// the payload may legitimately contain.
    for (const m of json.match(/https:\/\/[^"]+/g) ?? []) {
      expect(m).toMatch(/^https:\/\/schema\.org(\/|$)/)
    }
    expect(json).toContain('"url":"/shop/brixton-village-grill"')
  })

  it("carries the menu as MenuSections so a food result has items", () => {
    const [restaurant] = build() as [
      { hasMenu: { hasMenuSection: Array<{ name: string; hasMenuItem: unknown[] }> } },
    ]
    expect(restaurant.hasMenu.hasMenuSection[0].name).toBe("Mains")
    expect(restaurant.hasMenu.hasMenuSection[0].hasMenuItem).toHaveLength(1)
  })
})

describe("shopListStructuredData", () => {
  it("lists every shop as a Restaurant with its own URL", () => {
    const nodes = shopListStructuredData(
      [shop, { ...shop, slug: "mama-ades-kitchen", name: "Mama Ade's Kitchen" }],
      ORIGIN
    ) as Array<{ "@type": string; itemListElement?: Array<{ item: { url: string } }> }>

    expect(nodes.map((n) => n["@type"])).toEqual(["ItemList", "BreadcrumbList"])
    expect(nodes[0].itemListElement!.map((li) => li.item.url)).toEqual([
      "https://example.test/shop/brixton-village-grill",
      "https://example.test/shop/mama-ades-kitchen",
    ])
  })

  it("still emits breadcrumbs when the directory is empty", () => {
    const nodes = shopListStructuredData([], ORIGIN) as Array<{ "@type": string }>
    expect(nodes.map((n) => n["@type"])).toEqual(["BreadcrumbList"])
  })
})
