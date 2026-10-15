// Ad-hoc real-browser proof of 37-51 against THIS branch's `next build` served by `next start`
// on :3051. The browser's API calls are answered by page routes; the Next server's metadata read
// goes to a stub core on :9351 (CORE_API_INTERNAL_URL). Nothing touches the shared compose stack.
const http = require("http")
const { chromium } = require("playwright")

const BASE = "http://localhost:3051"
const SLUG = "mama-ades-kitchen"
const SHOP = { slug: SLUG, name: "Mama Ade's Kitchen", acceptsCardPayments: false }
const ORDER_NUMBER = "ORD-3751PROOF-20261014-RUNTIME1"
const EMAIL = "proof@e2e.jtoye.test"

let status = "PENDING"
const order = () => ({
  orderNumber: ORDER_NUMBER, status, shopName: SHOP.name, totalAmountPennies: 1200, itemCount: 1,
  unitCount: 1, paymentStatus: "NONE", fulfilmentType: "COLLECTION",
  createdAt: "2026-10-14T10:00:00Z", updatedAt: new Date().toISOString(),
  acknowledgedAllergens: [], recordedAllergens: [], recordedAllergenFlags: [], placedVia: "STOREFRONT",
})

const results = []
const check = (name, ok, detail) => { results.push({ name, ok, detail }); console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? " :: " + detail : ""}`) }

async function recorder(page) {
  await page.evaluate(() => {
    if (window.__rec) window.__rec.disconnect()
    window.__ann = []
    const last = new WeakMap()
    document.querySelectorAll("[role=status],[aria-live]").forEach((el) => last.set(el, (el.textContent || "").trim()))
    window.__rec = new MutationObserver(() => {
      document.querySelectorAll("[role=status],[aria-live]").forEach((el) => {
        const t = (el.textContent || "").replace(/\s+/g, " ").trim()
        if (last.get(el) !== t) { last.set(el, t); if (t) window.__ann.push(t) }
      })
    })
    window.__rec.observe(document.documentElement, { childList: true, characterData: true, subtree: true })
  })
}
const ann = (page) => page.evaluate(() => window.__ann || [])
async function overflow(page) {
  return page.evaluate(() => {
    const out = []
    const ol = document.querySelector('ol[aria-label="Order progress"]')
    if (!ol) return ["no tracker"]
    if (ol.scrollWidth > ol.clientWidth) out.push(`ol ${ol.scrollWidth}>${ol.clientWidth}`)
    const d = document.documentElement
    if (d.scrollWidth > d.clientWidth) out.push(`doc ${d.scrollWidth}>${d.clientWidth}`)
    ol.querySelectorAll("[data-tracker-step] p").forEach((p) => {
      const cs = getComputedStyle(p)
      if (cs.textOverflow === "ellipsis" && cs.overflow !== "visible") out.push(`${p.textContent} ellipsis`)
      if (p.scrollWidth > p.clientWidth + 1) out.push(`${p.textContent} ${p.scrollWidth}>${p.clientWidth}`)
    })
    return out
  })
}
async function waitFor(fn, ms = 8000) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await fn()) return true; await new Promise((r) => setTimeout(r, 200)) }
  return false
}

;(async () => {
  const stub = http.createServer((req, res) => {
    if (new RegExp(`/public/shops/${SLUG}$`).test(req.url)) {
      res.writeHead(200, { "content-type": "application/json" }); res.end(JSON.stringify(SHOP)); return
    }
    res.writeHead(404); res.end()
  })
  await new Promise((r) => stub.listen(9351, "127.0.0.1", r))

  const browser = await chromium.launch()
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } })
  await context.route(`**/public/orders/${ORDER_NUMBER}**`, (r) => r.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(order()) }))
  await context.route(`**/public/shops/${SLUG}`, (r) => r.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(SHOP) }))
  await context.addInitScript(([slug, email]) => { localStorage.setItem(`jtoye-checkout-email-${slug}`, email) }, [SLUG, EMAIL])
  const page = await context.newPage()
  await page.clock.install()

  try {
    // Titles (F4), server-rendered by the new layouts.
    await page.goto(`${BASE}/shop/${SLUG}/cart`)
    check("basket title", (await page.title()) === "Basket · Mama Ade's Kitchen — J'Toye", await page.title())
    await page.goto(`${BASE}/shop/${SLUG}/checkout`)
    check("checkout title", (await page.title()) === "Checkout · Mama Ade's Kitchen — J'Toye", await page.title())

    // Order page (F1).
    await page.goto(`${BASE}/shop/${SLUG}/orders/${ORDER_NUMBER}`)
    await page.getByRole("heading", { level: 1, name: `Order ${ORDER_NUMBER}` }).waitFor()
    check("order title", (await page.title()) === `Order ${ORDER_NUMBER} · Mama Ade's Kitchen — J'Toye`, await page.title())
    const regions = await page.locator('[role="status"][aria-live="polite"]').count()
    check("order page: exactly one polite status region", regions === 1, String(regions))
    check("order page: aria-current on Received", /Received/.test(await page.locator('[aria-current="step"]').innerText()))
    await recorder(page)
    status = "CONFIRMED"
    await page.clock.fastForward(15_000)
    const heard = await waitFor(async () => (await ann(page)).some((a) => a === "Order update: Confirmed. Shop accepted your order."))
    check("order page: CONFIRMED announced", heard, JSON.stringify(await ann(page)))
    await page.clock.fastForward(15_000)
    await page.waitForTimeout(1500)
    check("order page: unchanged poll not re-announced", (await ann(page)).length === 1, JSON.stringify(await ann(page)))
    check("order page: aria-current moved to Confirmed", /Confirmed/.test(await page.locator('[aria-current="step"]').innerText()))
    await page.setViewportSize({ width: 360, height: 780 })
    await page.addStyleTag({ content: "html { font-size: 130% !important; }" })
    const o1 = await overflow(page)
    check("order page tracker at 360px / 130%: no overflow, no ellipsis", o1.length === 0, JSON.stringify(o1))

    // /track (F1), via the customer's own link.
    await page.setViewportSize({ width: 390, height: 844 })
    await page.getByRole("link", { name: "Track this order" }).click()
    await page.waitForURL(/\/track\?order=/)
    await page.getByText("Mama Ade's Kitchen").first().waitFor()
    check("/track title kept", (await page.title()) === "Track your order — J'Toye", await page.title())
    const tRegions = await page.locator('[role="status"][aria-live="polite"]').count()
    check("/track: exactly one polite status region", tRegions === 1, String(tRegions))
    await recorder(page)
    status = "PREPARING"
    await page.clock.fastForward(15_000)
    const heard2 = await waitFor(async () => (await ann(page)).some((a) => a === "Order update: Preparing. Being prepared now."))
    check("/track: PREPARING announced", heard2, JSON.stringify(await ann(page)))
    await page.clock.fastForward(15_000)
    await page.waitForTimeout(1500)
    check("/track: unchanged poll not re-announced", (await ann(page)).length === 1, JSON.stringify(await ann(page)))
    await page.setViewportSize({ width: 360, height: 780 })
    await page.addStyleTag({ content: "html { font-size: 130% !important; }" })
    const o2 = await overflow(page)
    check("/track tracker at 360px / 130%: no overflow, no ellipsis", o2.length === 0, JSON.stringify(o2))

    // INSTRUMENT on the real page: remove the region, move the order on, nothing is recorded.
    await page.evaluate(() => document.querySelectorAll("[role=status],[aria-live]").forEach((e) => e.remove()))
    await recorder(page)
    status = "READY"
    await page.clock.fastForward(15_000)
    await waitFor(async () => /Ready/.test(await page.locator('[aria-current="step"]').innerText()))
    check("INSTRUMENT /track region removed: the stepper moved to Ready", /Ready/.test(await page.locator('[aria-current="step"]').innerText()))
    check("INSTRUMENT /track region removed: nothing recorded", (await ann(page)).length === 0, JSON.stringify(await ann(page)))
    // INSTRUMENT: a clipped label is caught by the overflow probe on the real tracker.
    await page.addStyleTag({ content: '[data-tracker-step="READY"] p { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; width: 1ch; }' })
    const o3 = await overflow(page)
    check("INSTRUMENT /track forced ellipsis is flagged", o3.length > 0, JSON.stringify(o3))
  } catch (e) {
    check("script error", false, String(e))
  } finally {
    await browser.close()
    stub.close()
  }
  const failed = results.filter((r) => !r.ok).length
  console.log(`\n${results.length - failed}/${results.length} checks passed`)
  process.exit(failed ? 1 : 0)
})()
