// Stub core for the 37-52 runtime proof: answers the public storefront reads for SSR
// (CORE_API_INTERNAL_URL) and for the browser (NEXT_PUBLIC_API_URL), with CORS. Logs every request.
// Nothing here talks to the shared compose stack.
const http = require("http")
const PORT = Number(process.env.STUB_PORT || 9352)
const shop = (slug, name, extra = {}) => ({
  slug, name, description: `${name} — proof fixture.`, address: "1 Proof Street, London SE15 4QA",
  logoUrl: null, bannerUrl: null, phone: null, email: null, latitude: 51.47, longitude: -0.07,
  openingHours: null, deliveryInfo: null, minimumOrderPennies: 1000, deliveryFeePennies: 299,
  freeDeliveryThresholdPennies: 3000, tags: "Jollof, West African, Halal", acceptsCardPayments: false,
  offersDelivery: true, offersCollection: true, ...extra,
})
const SHOPS = [
  shop("mama-ades-kitchen", "Mama Ade's Kitchen", { openNow: true, distanceKm: 1.2 }),
  shop("brixton-village-grill", "Brixton Village Grill", { openNow: false, nextOpenAt: "2026-10-15T08:00:00Z" }),
  shop("peckham-jollof-co", "Peckham Jollof Co.", {}),
]
// STUB_SHORT_NAMES=1: the goods run (dish-modal spec's focus-return probe reads at most 60 characters
// of the focused element's text, so a >60-character first dish fails it on ANY build).
const LONG_DISH = process.env.STUB_SHORT_NAMES === "1" ? "Jollof Rice" : "Jollof Rice with Fried Plantain, Peppered Chicken and Coleslaw"
const DISHES = [
  [LONG_DISH, "Mains", 1250],
  ["Egusi Soup with Pounded Yam", "Mains", 1100], ["Suya Beef Skewers", "Grills", 900],
  ["Peppered Snail", "Grills", 1300], ["Moi Moi", "Sides", 450], ["Dodo (Fried Plantain)", "Sides", 350],
  ["Puff Puff", "Desserts", 300], ["Chin Chin", "Desserts", 250], ["Mango Lassi", "Drinks", 350],
  ["Zobo", "Drinks", 300], ["Chapman", "Drinks", 400], ["Kunu", "Drinks", 300],
]
const products = {}
DISHES.forEach(([title, category, price], i) => {
  ;(products[category] ||= []).push({
    id: `00000000-0000-4000-8000-0000000000${String(i + 10)}`, title, description: `${title}, made fresh.`,
    imageUrl: null, imageUrls: [], ingredientsText: "rice, tomato, pepper", allergenMask: 0, pricePennies: price,
    category, dietaryTags: "Halal", preparationTimeMinutes: 15, featured: false, inStock: true, vatRate: "STANDARD",
  })
})
const page = (content) => ({ content, totalElements: content.length, totalPages: 1, size: 12, number: 0, first: true, last: true })
const routes = [
  [/^\/public\/shops(\?.*)?$/, () => page(SHOPS)],
  [/^\/public\/shops\/([^/?]+)$/, (m) => SHOPS.find((s) => s.slug === m[1])],
  [/^\/public\/shops\/([^/?]+)\/products$/, () => products],
  [/^\/public\/shops\/([^/?]+)\/reviews(\?.*)?$/, () => ({ content: [], totalElements: 0 })],
  [/^\/public\/shops\/([^/?]+)\/announcements$/, () => []],
  [/^\/public\/shops\/([^/?]+)\/config$/, () => ({ announcements: [], featuredProducts: [] })],
]
http.createServer((req, res) => {
  const cors = { "access-control-allow-origin": req.headers.origin || "*", "access-control-allow-headers": "*", "access-control-allow-methods": "GET,OPTIONS", "access-control-allow-credentials": "true" }
  console.log(`${new Date().toISOString()} ${req.method} ${req.url} origin=${req.headers.origin || "-"}`)
  if (req.method === "OPTIONS") { res.writeHead(204, cors); res.end(); return }
  for (const [re, fn] of routes) {
    const m = req.url.match(re)
    if (m) {
      const body = fn(m)
      if (body === undefined) break
      res.writeHead(200, { ...cors, "content-type": "application/json" }); res.end(JSON.stringify(body)); return
    }
  }
  res.writeHead(404, cors); res.end()
}).listen(PORT, "127.0.0.1", () => console.log(`stub core on ${PORT}`))
