/**
 * Product images for /for-operators (frontend/public/marketing/operators/*.webp).
 *
 * WHY THIS EXISTS. The shared dev database holds the names of real people,
 * test strings in reviews and aged test orders, so a screenshot of it cannot be
 * published.
 * Every image here is therefore rendered by the REAL application (this repo's
 * production build) against FICTIONAL data that this script serves in place of
 * the backend: a fictional shop, "Adaeze's Kitchen", initials-only customers
 * ("A. Okafor"), four dishes (jollof, egusi, suya, moin moin), no allergy note
 * that names anyone.
 *
 * HOW THE DATA IS REPLACED. The page's API reads go to NEXT_PUBLIC_API_URL
 * (http://localhost:9090 by default). Playwright `context.route` answers every
 * request to that origin with the fixtures below, so the backend is never
 * contacted. Anything the fixtures do not cover is answered 404 and listed in
 * the run summary. The storefront also fetches on the server; point the
 * server at a dead address so it defers to the browser (which this script
 * answers) instead of reading the dev database:
 *
 *   CORE_API_INTERNAL_URL=http://127.0.0.1:1 npx next start -p <PORT>
 *
 * NOTHING IS WRITTEN. Every non-GET request is aborted before it leaves the
 * browser and recorded; the run FAILS if there was any, and also if a request
 * tried to reach a host other than the app, the mocked API origin or a
 * data: / blob: URL. The only state the script touches is the browser's own
 * local cart (one "Add" click) so the checkout page has something to show.
 *
 * RE-RUN (from frontend/, with a production build serving on PORT):
 *
 *   npm run build
 *   CORE_API_INTERNAL_URL=http://127.0.0.1:1 npx next start -p 3203 &
 *   CAPTURE_BASE_URL=http://localhost:3203 \
 *     node scripts/marketing-captures/capture-operators.mjs storefront slots
 *
 * Targets (default: all that can run): storefront, slots, kitchen, label.
 *
 *   storefront  /shop/adaeze-kitchen            -> storefront-menu.webp   (public)
 *   slots       checkout "Fulfilment Window"    -> slot-picker.webp       (public)
 *   kitchen     /dashboard/kitchen, cook sheet  -> kitchen-cook-sheet.webp
 *   label       the 62 mm PPDS label            -> ppds-label.webp
 *
 * `kitchen` needs a signed-in vendor session because the dashboard is gated
 * server-side. Two ways, same page and same mocked data:
 *
 *   1. The e2e recipe (e2e/vendor-credentials.ts + vendorLogin()): export
 *      E2E_VENDOR_PASSWORD (or KC_SEED_USER_PASSWORD), optionally
 *      E2E_VENDOR_USERNAME (default admin-user), and run with the shared
 *      Keycloak up. The login is the only thing that leaves the machine's own
 *      stack; every dashboard API read is still answered by the fixtures.
 *   2. No credentials: see ./capture-kitchen-route/README.md. A throwaway,
 *      uncommitted route renders the same shell and the same kitchen page
 *      without the server-side session gate; run with CAPTURE_KITCHEN_PATH set
 *      to that route.
 *
 * `label` rasterises page 1 of a real 62 mm PPDS label PDF (the file the
 * cook-sheet "62 mm label" button downloads). Generate it with the backend's
 * own renderer (see ./label-fixture/README.md) and pass LABEL_PDF=<path>. It
 * is rasterised with poppler's `pdftoppm` (headless Chromium has no PDF
 * viewer, so "open it in the browser" is not available) and set on a plain
 * ground at 3:2. This target makes no network request at all.
 *
 * Output: WebP via sharp, each under 200 KB (the script fails if one is not).
 */
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { execFileSync } from "node:child_process"
import os from "node:os"
import { chromium } from "@playwright/test"
import sharp from "sharp"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const OUT_DIR = path.resolve(HERE, "../../public/marketing/operators")
const BASE = process.env.CAPTURE_BASE_URL || "http://localhost:3203"
const API_ORIGIN = process.env.CAPTURE_API_ORIGIN || "http://localhost:9090"
const MAX_BYTES = 200 * 1024
const SLUG = "adaeze-kitchen"

// ---------------------------------------------------------------------------
// Fictional fixtures. Nothing below is, or is derived from, real data.
// ---------------------------------------------------------------------------

const SHOP_ID = "00000000-0000-4000-8000-0000000000a1"
const TENANT_ID = "00000000-0000-4000-8000-0000000000f1"
const id = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`

// Bit layout of the UK FSA 14 (types/api.ts ALLERGENS): Gluten 0, Crustaceans 1,
// Eggs 2, Fish 3, Peanuts 4, Soybeans 5, Milk 6, Nuts 7, Celery 8 ...
const bit = (...bits) => bits.reduce((m, b) => m | (1 << b), 0)

const PRODUCTS = [
  { n: 11, title: "Jollof rice with chicken", price: 950, cat: "Mains", mask: bit(8), prep: 20, featured: false,
    desc: "Smoky party-style jollof, grilled chicken thigh and fried plantain.",
    ing: "Long-grain rice, tomato, red pepper, onion, chicken thigh, vegetable stock (celery), thyme." },
  { n: 12, title: "Egusi soup with pounded yam", price: 1100, cat: "Mains", mask: bit(1, 3), prep: 25, featured: false,
    desc: "Ground melon seed and spinach stew with a portion of pounded yam.",
    ing: "Ground melon seed, spinach, palm oil, stockfish, ground crayfish, onion, pepper, yam." },
  { n: 13, title: "Beef suya skewers", price: 850, cat: "Mains", mask: bit(4), prep: 15, featured: false,
    desc: "Thin-sliced beef rubbed with yaji spice, grilled over charcoal.",
    ing: "Beef sirloin, ground roasted peanut spice mix, paprika, onion powder, vegetable oil." },
  { n: 14, title: "Moin moin", price: 450, cat: "Mains", mask: bit(2, 3), prep: 10, featured: false,
    desc: "Steamed black-eyed bean pudding with egg and mackerel.",
    ing: "Black-eyed beans, pepper, onion, boiled egg, mackerel, vegetable oil." },
  { n: 15, title: "Fried plantain", price: 350, cat: "Sides and snacks", mask: 0, prep: 8, featured: false,
    desc: "Ripe plantain, fried until the edges caramelise.",
    ing: "Ripe plantain, vegetable oil, salt." },
  { n: 16, title: "Chin chin", price: 300, cat: "Sides and snacks", mask: bit(0, 2, 6), prep: 5, featured: false,
    desc: "Crunchy fried dough bites, lightly sweet.",
    ing: "Wheat flour, egg, butter (milk), sugar, nutmeg, vegetable oil." },
]

function productDto(p) {
  return {
    id: id(p.n), title: p.title, description: p.desc, imageUrl: null, imageUrls: [], media: null,
    ingredientsText: p.ing, allergenMask: p.mask, pricePennies: p.price, vatRate: "ZERO",
    category: p.cat, dietaryTags: null, preparationTimeMinutes: p.prep, featured: p.featured, inStock: true,
    undeclaredIngredientAllergens: [], mayContainAllergens: [],
  }
}

const PRODUCTS_BY_CATEGORY = PRODUCTS.reduce((acc, p) => {
  (acc[p.cat] ||= []).push(productDto(p))
  return acc
}, {})

const SHOP = {
  slug: SLUG, name: "Adaeze's Kitchen",
  description: "West African home cooking: jollof, egusi, suya and moin moin, made to order.",
  address: "Example Row, Peckham, London", logoUrl: null, bannerUrl: null, phone: null, email: null,
  latitude: 51.4741, longitude: -0.0691,
  openingHours: null, deliveryInfo: "Local delivery within two miles, or collect from the kitchen.",
  minimumOrderPennies: 1000, deliveryFeePennies: 300, freeDeliveryThresholdPennies: 3000, tags: "Nigerian, West African",
  acceptsCardPayments: true, maxLineQuantity: 20,
  openNow: true, nextOpenAt: null, pausedUntil: null, pausedIndefinitely: false,
  openingSchedule: null, legacyAlwaysOpen: true, hoursSource: "LEGACY_ALWAYS_OPEN",
  offersDelivery: true, offersCollection: true,
  fulfilmentTimingMode: "HYBRID_IMMEDIATE_AND_SLOTS",
  seller: { legalName: "Adaeze's Kitchen Ltd", entityType: "COMPANY", addressLines: ["Example Row", "London"], email: "hello@example.test" },
}

/** Two weekend slots. windowStart/windowEnd are plain HH:mm (the component prints them raw). */
function slots() {
  const day = (offset) => {
    const d = new Date(); d.setDate(d.getDate() + offset)
    while (d.getDay() !== 6 && d.getDay() !== 0) d.setDate(d.getDate() + 1)
    return d
  }
  const sat = day(2)
  while (sat.getDay() !== 6) sat.setDate(sat.getDate() + 1)
  const sun = new Date(sat); sun.setDate(sat.getDate() + 1)
  const ymd = (d) => d.toLocaleDateString("en-CA")
  const cutoff = (d) => { const c = new Date(d); c.setDate(c.getDate() - 1); c.setHours(18, 0, 0, 0); return c.toISOString() }
  const mk = (n, d, label, max, reserved) => ({
    id: id(100 + n), shopId: SHOP_ID, serviceDate: ymd(d), windowStart: label.start, windowEnd: label.end,
    windowLabel: label.text, cutoffAt: cutoff(d), maxOrders: max, maxUnits: null,
    reservedOrders: reserved, reservedUnits: 0, remainingOrders: max - reserved, remainingUnits: null, active: true,
  })
  return [
    mk(1, sat, { start: "12:00", end: "14:00", text: "Saturday lunch batch" }, 30, 18),
    mk(2, sat, { start: "17:00", end: "19:00", text: "Saturday evening batch" }, 30, 9),
    mk(3, sun, { start: "12:00", end: "14:00", text: "Sunday lunch batch" }, 30, 4),
  ]
}

// ---------------------------------------------------------------------------
// Request firewall: mocked API, no writes, no foreign hosts.
// ---------------------------------------------------------------------------

function makeLedger() {
  return { served: new Set(), unmocked: new Set(), blockedWrites: [], foreign: new Set() }
}

const APP_ORIGIN = new URL(BASE).origin

async function installFirewall(context, ledger, handlers) {
  const cors = { "access-control-allow-origin": APP_ORIGIN, "access-control-allow-credentials": "true", "access-control-allow-headers": "*", "access-control-allow-methods": "GET" }

  await context.route("**/*", async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const method = req.method()

    if (url.origin === APP_ORIGIN) {
      if (method !== "GET" && method !== "HEAD") {
        ledger.blockedWrites.push(`${method} ${url.pathname}`)
        return route.abort()
      }
      if (url.pathname === "/api/auth/session" && handlers.session) {
        ledger.served.add("GET /api/auth/session (app)")
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(handlers.session()) })
      }
      return route.continue()
    }

    if (url.origin === API_ORIGIN) {
      if (method === "OPTIONS") return route.fulfill({ status: 204, headers: cors })
      if (method !== "GET" && method !== "HEAD") {
        ledger.blockedWrites.push(`${method} ${url.pathname}`)
        return route.abort()
      }
      const hit = handlers.api(url, method)
      if (hit) {
        ledger.served.add(`GET ${url.pathname}`)
        return route.fulfill({ status: hit.status ?? 200, headers: { ...cors, ...(hit.headers || {}) }, contentType: hit.contentType || "application/json", body: hit.raw ?? JSON.stringify(hit.json) })
      }
      ledger.unmocked.add(`GET ${url.pathname}${url.search}`)
      return route.fulfill({ status: 404, headers: cors, contentType: "application/json", body: "{}" })
    }

    if (url.protocol === "data:" || url.protocol === "blob:") return route.continue()
    ledger.foreign.add(`${method} ${url.origin}`)
    return route.abort()
  })

  // STOMP: complete the handshake so the board reads "live", deliver nothing.
  await context.routeWebSocket(/\/ws(\?|$)/, (ws) => {
    ws.onMessage((msg) => {
      if (typeof msg === "string" && msg.startsWith("CONNECT")) ws.send("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0")
    })
  })
}

function assertClean(ledger, label) {
  const problems = []
  if (ledger.blockedWrites.length) problems.push(`non-GET requests were attempted (and aborted): ${ledger.blockedWrites.join(", ")}`)
  if (ledger.foreign.size) problems.push(`requests to foreign hosts (aborted): ${[...ledger.foreign].join(", ")}`)
  console.log(`[${label}] served: ${[...ledger.served].sort().join(" | ")}`)
  if (ledger.unmocked.size) console.log(`[${label}] unmocked GETs answered 404: ${[...ledger.unmocked].join(" | ")}`)
  if (problems.length) throw new Error(`[${label}] ${problems.join("; ")}`)
}

// ---------------------------------------------------------------------------
// Output
// ---------------------------------------------------------------------------

async function writeWebp(png, name, width, height) {
  fs.mkdirSync(OUT_DIR, { recursive: true })
  const file = path.join(OUT_DIR, name)
  let q = 82
  let buf
  for (; q >= 40; q -= 6) {
    buf = await sharp(png).resize(width, height, { fit: "cover", position: "top" }).webp({ quality: q, effort: 6 }).toBuffer()
    if (buf.length <= MAX_BYTES) break
  }
  if (buf.length > MAX_BYTES) throw new Error(`${name} is ${buf.length} bytes at quality ${q}, over ${MAX_BYTES}`)
  fs.writeFileSync(file, buf)
  console.log(`wrote ${path.relative(process.cwd(), file)} ${width}x${height} q${q} ${(buf.length / 1024).toFixed(1)} KB`)
}

async function dismissNotices(page) {
  const got = page.getByRole("button", { name: /^got it$/i })
  if ((await got.count()) > 0) await got.first().click({ timeout: 3000 }).catch(() => {})
}

// ---------------------------------------------------------------------------
// Targets
// ---------------------------------------------------------------------------

function storefrontApi(url) {
  const p = url.pathname
  if (p === `/public/shops/${SLUG}`) return { json: SHOP }
  if (p === `/public/shops/${SLUG}/products`) return { json: PRODUCTS_BY_CATEGORY }
  if (p === `/public/shops/${SLUG}/reviews`) return { json: { content: [], totalElements: 0 } }
  if (p === `/public/shops/${SLUG}/config`) return { json: { announcements: [], featuredProducts: [] } }
  if (p === `/public/shops/${SLUG}/announcements`) return { json: [] }
  if (p === `/public/shops/${SLUG}/slots`) return { json: slots() }
  return null
}

async function captureStorefront(browser) {
  const ledger = makeLedger()
  const context = await browser.newContext({ viewport: { width: 1280, height: 960 }, deviceScaleFactor: 2, reducedMotion: "reduce" })
  await installFirewall(context, ledger, { api: storefrontApi })
  const page = await context.newPage()
  await page.goto(`${BASE}/shop/${SLUG}`, { waitUntil: "networkidle" })
  await dismissNotices(page)
  await page.getByRole("heading", { level: 1, name: /adaeze/i }).waitFor({ timeout: 15000 })
  await page.getByText("Jollof rice with chicken").first().waitFor({ timeout: 15000 })
  await page.waitForTimeout(600)
  const png = await page.screenshot({ clip: { x: 0, y: 0, width: 1280, height: 853 } })
  await writeWebp(png, "storefront-menu.webp", 960, 640)
  assertClean(ledger, "storefront")
  await context.close()
}

async function captureSlots(browser) {
  const ledger = makeLedger()
  const context = await browser.newContext({ viewport: { width: 1280, height: 960 }, deviceScaleFactor: 2, reducedMotion: "reduce" })
  await installFirewall(context, ledger, { api: storefrontApi })
  const page = await context.newPage()
  await page.goto(`${BASE}/shop/${SLUG}`, { waitUntil: "networkidle" })
  await dismissNotices(page)
  await page.getByText("Jollof rice with chicken").first().waitFor({ timeout: 15000 })
  // Local cart state only: nothing is sent anywhere.
  await page.getByRole("button", { name: /^add/i }).first().click()
  await page.waitForTimeout(400)
  await page.goto(`${BASE}/shop/${SLUG}/checkout`, { waitUntil: "networkidle" })
  await dismissNotices(page)
  const picker = page.getByTestId("fulfilment-slot-picker")
  await picker.waitFor({ timeout: 15000 })
  // Choose a weekend slot (local component state): the image should show scheduled fulfilment selected.
  await page.getByTestId("fulfilment-slot-picker").getByText("Saturday lunch batch").click()
  await picker.scrollIntoViewIfNeeded()
  await page.waitForTimeout(500)
  const box = await picker.boundingBox()
  if (!box) throw new Error("slot picker has no box")
  // 3:2 crop centred on the picker with context around it.
  const w = 1000, h = Math.round((w * 2) / 3)
  const x = Math.max(0, Math.round(box.x + box.width / 2 - w / 2))
  const y = Math.max(0, Math.round(box.y - 40))
  const png = await page.screenshot({ clip: { x, y, width: Math.min(w, 1280 - x), height: h } })
  await writeWebp(png, "slot-picker.webp", 960, 640)
  assertClean(ledger, "slots")
  await context.close()
}

// ---------------------------------------------------------------------------
// Dashboard (kitchen display): fictional owner, one shop, five tickets, a cook sheet.
// ---------------------------------------------------------------------------

const KITCHEN_PATH = process.env.CAPTURE_KITCHEN_PATH || "/dashboard/kitchen"
const iso = (minsAgo) => new Date(Date.now() - minsAgo * 60000).toISOString()

function nextSaturday() {
  const d = new Date()
  do { d.setDate(d.getDate() + 1) } while (d.getDay() !== 6)
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/London", year: "numeric", month: "2-digit", day: "2-digit" }).format(d)
}

const P = Object.fromEntries(PRODUCTS.map((p) => [p.n, p]))
const allergenNames = (mask) => ["Gluten", "Crustaceans", "Eggs", "Fish", "Peanuts", "Soybeans", "Milk", "Nuts", "Celery", "Mustard", "Sesame", "Sulphites", "Lupin", "Molluscs"].filter((_, i) => (mask & (1 << i)) !== 0)

function line(orderN, lineN, productN, qty) {
  const p = P[productN]
  return { id: id(orderN * 10 + lineN), productId: id(productN), productName: p.title, quantity: qty, unitPricePennies: p.price,
    totalPricePennies: p.price * qty, createdAt: iso(5), allergenMask: p.mask, allergenNames: allergenNames(p.mask) }
}

function order(n, number, status, customer, minsAgo, lines, extra = {}) {
  const items = lines.map(([productN, qty], i) => line(n, i + 1, productN, qty))
  const mask = items.reduce((m, it) => m | (it.allergenMask || 0), 0)
  return {
    id: id(n), tenantId: TENANT_ID, shopId: SHOP_ID, orderNumber: number, status, customerName: customer,
    totalAmountPennies: items.reduce((t, it) => t + it.totalPricePennies, 0), unitCount: items.reduce((t, it) => t + it.quantity, 0),
    items, createdAt: iso(minsAgo), updatedAt: iso(minsAgo), paymentStatus: "PAID", paymentMethod: "CARD",
    fulfilmentType: "COLLECTION", allergenMask: mask, allergenNames: allergenNames(mask), allergenFlags: [],
    allergenAckMask: mask, allergenAckAt: iso(minsAgo), placedVia: "STOREFRONT", acknowledgedAllergenNames: allergenNames(mask),
    contactVerified: true, ...extra,
  }
}

const SAT = nextSaturday()
const KITCHEN_ORDERS = [
  order(41, "AK-1041", "PENDING", "A. Okafor", 2, [[11, 2], [15, 2]]),
  order(42, "AK-1042", "PENDING", "B. Adeyemi", 1, [[13, 3], [14, 1]]),
  order(43, "AK-1043", "CONFIRMED", "T. Bello", 9, [[11, 6], [13, 4]], { scheduledForDate: SAT, scheduledWindowLabel: "Saturday lunch batch", fulfilmentSlotId: id(101), depositAmountPennies: 2900, balanceDuePennies: 4350 }),
  order(44, "AK-1044", "PREPARING", "C. Nwosu", 14, [[12, 1], [13, 2]]),
  order(45, "AK-1045", "READY", "D. Eze", 22, [[11, 1], [14, 2]]),
]

const COOK_SHEET = {
  shopId: SHOP_ID, scheduledForDate: null, fulfilmentSlotId: null, totalOrders: 4, totalUnits: 27,
  items: [
    { productId: id(11), productName: P[11].title, totalQuantity: 9, orderCount: 3, allergenMask: P[11].mask, allergenNames: allergenNames(P[11].mask) },
    { productId: id(13), productName: P[13].title, totalQuantity: 9, orderCount: 3, allergenMask: P[13].mask, allergenNames: allergenNames(P[13].mask) },
    { productId: id(14), productName: P[14].title, totalQuantity: 5, orderCount: 3, allergenMask: P[14].mask, allergenNames: allergenNames(P[14].mask) },
    { productId: id(12), productName: P[12].title, totalQuantity: 4, orderCount: 2, allergenMask: P[12].mask, allergenNames: allergenNames(P[12].mask) },
  ],
  allergyAlerts: [{ orderId: id(43), orderNumber: "AK-1043", customerName: "T. Bello", allergyNote: "Peanut allergy, please keep the suya separate.", acknowledged: false }],
}

function kitchenApi(url) {
  const p = url.pathname
  if (p === "/api/v1/staff/me") return { json: { userId: "capture-owner", groupAdmin: true, grantedShopIds: null, tenantWideRole: null, businessName: "Adaeze's Kitchen Ltd" } }
  if (p === "/api/v1/shops") return { json: { content: [KITCHEN_SHOP], totalElements: 1, totalPages: 1, size: 20, number: 0, first: true, last: true } }
  if (p === `/api/v1/shops/${SHOP_ID}`) return { json: KITCHEN_SHOP }
  if (p === "/api/v1/orders/kitchen") return { json: { content: KITCHEN_ORDERS, totalElements: KITCHEN_ORDERS.length, totalPages: 1, size: 100, number: 0, first: true, last: true } }
  if (p === "/api/v1/orders/cook-sheet") return { json: { ...COOK_SHEET, scheduledForDate: url.searchParams.get("scheduledForDate") } }
  if (p === `/api/v1/shops/${SHOP_ID}/slots`) return { json: slots() }
  const m = p.match(/^\/api\/v1\/orders\/([^/]+)\/detail$/)
  if (m) { const o = KITCHEN_ORDERS.find((x) => x.id === m[1]); if (o) return { json: o } }
  return null
}

const KITCHEN_SHOP = {
  id: SHOP_ID, tenantId: TENANT_ID, name: "Adaeze's Kitchen", address: "Example Row, Peckham, London", slug: SLUG,
  description: SHOP.description, logoUrl: null, bannerUrl: null, phone: null, email: null, latitude: 51.4741, longitude: -0.0691,
  openingHours: null, deliveryInfo: SHOP.deliveryInfo, minimumOrderPennies: 1000, published: true, tags: SHOP.tags,
  createdAt: iso(60 * 24 * 90), updatedAt: iso(60 * 24), hoursSource: "LEGACY_ALWAYS_OPEN", offersDelivery: true, offersCollection: true,
  fulfilmentTimingMode: "HYBRID_IMMEDIATE_AND_SLOTS",
}

async function vendorSignIn(page) {
  const user = process.env.E2E_VENDOR_USERNAME || "admin-user"
  const pass = process.env.E2E_VENDOR_PASSWORD || process.env.KC_SEED_USER_PASSWORD || ""
  if (!pass) throw new Error("no vendor credential: set E2E_VENDOR_PASSWORD (or KC_SEED_USER_PASSWORD), or use the throwaway route (CAPTURE_KITCHEN_PATH, see capture-kitchen-route/README.md)")
  await page.goto(`${BASE}/auth/signin`, { waitUntil: "domcontentloaded" })
  const sso = page.getByRole("button", { name: /sign in with keycloak/i })
  await sso.waitFor({ timeout: 15000 })
  await page.waitForLoadState("networkidle").catch(() => {})
  await page.waitForTimeout(400)
  await sso.click()
  await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 25000 })
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", user)
    await page.fill("#password", pass)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 30000 })
}

async function captureKitchen(browser) {
  const ledger = makeLedger()
  const context = await browser.newContext({ viewport: { width: 1440, height: Number(process.env.CAPTURE_KITCHEN_HEIGHT || 900) }, deviceScaleFactor: process.env.CAPTURE_KITCHEN_HEIGHT ? 1 : 2, reducedMotion: "reduce" })
  const viaThrowawayRoute = KITCHEN_PATH !== "/dashboard/kitchen"
  // The session endpoint answers a fictional owner only on the throwaway route; on the real
  // route the genuine session cookie is needed server-side, and the identity it carries
  // (the dev vendor) is replaced in the browser by the same fictional answer.
  await installFirewall(context, ledger, {
    api: (u, m) => kitchenApi(u, m) || storefrontApi(u),
    session: () => ({ user: { name: "Shop owner", email: "owner@example.test", tenantId: TENANT_ID }, expires: new Date(Date.now() + 864e5).toISOString(), accessToken: "capture-token", expiresAt: Math.floor(Date.now() / 1000) + 86400 }),
  })
  const page = await context.newPage()
  if (!viaThrowawayRoute) await vendorSignIn(page)
  await page.goto(`${BASE}${KITCHEN_PATH}`, { waitUntil: "domcontentloaded" })
  await page.waitForTimeout(1500)
  await dismissNotices(page)
  const toggle = page.locator('[data-testid="kds-cook-sheet-toggle"]:visible').first()
  await toggle.waitFor({ timeout: 20000 })
  await toggle.click()   // local React state; the panel then GETs the (mocked) cook sheet
  await page.getByTestId("kds-cook-sheet-panel").waitFor({ timeout: 10000 })
  await page.getByTestId("cook-sheet-item-row").first().waitFor({ timeout: 10000 })
  await page.waitForLoadState("networkidle").catch(() => {})
  await page.waitForTimeout(1200)
  // The board scrolls inside <main>. Start the frame at the cook sheet so it shows the batch
  // totals, the allergy instruction, the dishes with their 62 mm label buttons, and the top of
  // the new-order lane beneath it.
  await page.evaluate(() => {
    const main = document.getElementById("main")
    const panel = document.querySelector('[data-testid="kds-cook-sheet-panel"]')
    if (main && panel) main.scrollTop += panel.getBoundingClientRect().top - main.getBoundingClientRect().top - 12
  })
  await page.waitForTimeout(400)
  const png = await page.screenshot()
  if (process.env.CAPTURE_DEBUG_DIR) fs.writeFileSync(path.join(process.env.CAPTURE_DEBUG_DIR, "kitchen-raw.png"), png)
  await writeWebp(png, "kitchen-cook-sheet.webp", 1280, 800)
  assertClean(ledger, "kitchen")
  await context.close()
}

async function captureLabel() {
  const pdf = process.env.LABEL_PDF
  if (!pdf || !fs.existsSync(pdf)) throw new Error("set LABEL_PDF to a rendered 62 mm label (see label-fixture/README.md)")
  if (fs.readFileSync(pdf).subarray(0, 4).toString() !== "%PDF") throw new Error("LABEL_PDF is not a PDF")
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "ppds-"))
  execFileSync("pdftoppm", ["-r", "600", "-png", "-f", "1", "-l", "1", pdf, path.join(tmp, "label")])
  const raster = path.join(tmp, fs.readdirSync(tmp).find((n) => n.endsWith(".png")))
  // The renderer leaves the foot of the page empty; trim to the last inked row plus a margin.
  const trimmed = await sharp(raster).trim({ background: "#ffffff", threshold: 8 }).toBuffer({ resolveWithObject: true })
  const full = await sharp(raster).metadata()
  const inkedBottom = -trimmed.info.trimOffsetTop + trimmed.info.height
  const inked = await sharp(raster).extract({ left: 0, top: 0, width: full.width, height: Math.min(full.height, inkedBottom + 140) }).toBuffer()
  const meta = await sharp(inked).metadata()
  // A bordered label sheet on a warm ground, 3:2.
  const W = 1920, H = 1280, labelH = 1120
  const labelW = Math.round((meta.width / meta.height) * labelH)
  const label = await sharp(inked).resize(labelW, labelH).extend({ top: 3, bottom: 3, left: 3, right: 3, background: "#cfc6ba" }).toBuffer()
  const shadow = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${labelW + 6}" height="${labelH + 6}"><rect width="100%" height="100%" rx="10" fill="#3a0b0d" opacity="0.18"/></svg>`)
  const png = await sharp({ create: { width: W, height: H, channels: 3, background: "#efe6da" } })
    .composite([
      { input: await sharp(shadow).blur(18).toBuffer(), left: Math.round((W - labelW) / 2) + 6, top: Math.round((H - labelH) / 2) + 14 },
      { input: label, left: Math.round((W - labelW) / 2), top: Math.round((H - labelH) / 2) },
    ]).png().toBuffer()
  await writeWebp(png, "ppds-label.webp", 960, 640)
  fs.rmSync(tmp, { recursive: true, force: true })
}

const TARGETS = { label: captureLabel, storefront: captureStorefront, slots: captureSlots, kitchen: captureKitchen }

const wanted = process.argv.slice(2)
const names = wanted.length ? wanted : Object.keys(TARGETS)
for (const n of names) if (!TARGETS[n]) { console.error(`unknown target "${n}" (have: ${Object.keys(TARGETS).join(", ")})`); process.exit(2) }

const browser = await chromium.launch()
let failed = false
try {
  for (const n of names) {
    try { await TARGETS[n](browser) } catch (e) { failed = true; console.error(`[${n}] FAILED: ${String(e.message).split("\n")[0]}`) }
  }
} finally {
  await browser.close()
}
process.exit(failed ? 1 : 0)
