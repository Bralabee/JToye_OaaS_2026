// Usage: PW_PATH=<playwright dir> node record.js <baseUrl> <outDir> <label>
const path = require("path")
const fs = require("fs")
const { chromium } = require(process.env.PW_PATH)
const [base, outDir, label] = process.argv.slice(2)
fs.mkdirSync(outDir, { recursive: true })

async function state(page) {
  return page.evaluate(() => {
    const input = document.querySelector("#shop-search")
    const txt = document.body.innerText
    return {
      t: Math.round(performance.now()),
      value: input ? input.value : null,
      search: location.search,
      skeletonCards: document.querySelectorAll(".grid > .animate-pulse").length,
      cards: document.querySelectorAll("article[data-shop-name]").length,
      summary: (document.querySelector("p[aria-live=polite]") || {}).innerText || null,
      noKitchens: txt.includes("No kitchens found"),
      busy: txt.includes("High demand right now"),
      loadError: !!document.querySelector("[data-testid=discovery-load-error]"),
      customClearButtons: document.querySelectorAll("button[aria-label='Clear search']").length,
    }
  })
}

async function run(page, name, startUrl, typeText) {
  const samples = []
  const requests = []
  page.on("response", (r) => {
    const u = r.url()
    if (/\/public\/shops(\?|$)/.test(u))
      requests.push({ t: Date.now(), url: u.replace(/^.*\/public\/shops/, "/public/shops"), status: r.status() })
  })
  const latency = Number(process.env.LATENCY || 0)
  if (latency) {
    await page.route(/\/public\/shops(\?|$)/, async (route) => {
      const resp = await route.fetch()
      await new Promise((r) => setTimeout(r, latency))
      await route.fulfill({ response: resp })
    })
  }
  await page.goto(base + startUrl, { waitUntil: "networkidle" })
  const input = page.locator("#shop-search")
  await input.click()
  // Watch input value + URL on every frame: catches a flash a per-key sample misses.
  await page.evaluate(() => {
    window.__vals = []
    const el = document.querySelector("#shop-search")
    let last = el.value
    let lastS = location.search
    window.__kinds = []
    let lastK = null
    const kind = () => {
      const t = document.body.innerText
      if (document.querySelector("[data-testid=discovery-load-error]")) return "error"
      if (t.includes("High demand right now")) return "busy"
      if (document.querySelectorAll(".grid > .animate-pulse").length) return "skeleton"
      if (t.includes("No kitchens found")) return "empty-state"
      return "cards:" + document.querySelectorAll("article[data-shop-name]").length
    }
    const tick = () => {
      const k = kind()
      if (k !== lastK) {
        lastK = k
        window.__kinds.push({ t: Math.round(performance.now()), kind: k, value: el.value })
      }
      if (el.value !== last || location.search !== lastS) {
        last = el.value
        lastS = location.search
        window.__vals.push({ t: Math.round(performance.now()), value: el.value, search: location.search })
      }
      requestAnimationFrame(tick)
    }
    tick()
  })
  if (typeText) {
    await page.keyboard.type(typeText, { delay: 110 })
    await page.waitForTimeout(1200)
    samples.push({ step: "typed+1.2s", ...(await state(page)) })
  } else {
    samples.push({ step: "deeplink-loaded", ...(await state(page)) })
  }
  await page.keyboard.press("End")
  const n = (await input.inputValue()).length
  for (let i = 0; i < n; i++) {
    await page.keyboard.press("Backspace")
    await page.waitForTimeout(Number(process.env.BS_DELAY || 120))
    samples.push({ step: `bs${i + 1}`, ...(await state(page)) })
  }
  let waited = 0
  for (const ms of [200, 500, 1000, 2000]) {
    await page.waitForTimeout(ms - waited)
    waited = ms
    samples.push({ step: `quiet+${ms}ms`, ...(await state(page)) })
  }
  const vals = await page.evaluate(() => window.__vals)
  const kinds = await page.evaluate(() => window.__kinds)
  await page.screenshot({ path: path.join(outDir, `${label}-${name}-after-backspace.png`) })
  // The search box with text in it: how many clear affordances are painted.
  await input.fill("pounded yam")
  await page.waitForTimeout(300)
  await page.locator("#shop-search").locator("..").screenshot({ path: path.join(outDir, `${label}-${name}-box.png`) })
  const nativeCancel = await page.evaluate(() => {
    const cs = getComputedStyle(document.querySelector("#shop-search"), "::-webkit-search-cancel-button")
    return { appearance: cs.getPropertyValue("appearance"), webkitAppearance: cs.getPropertyValue("-webkit-appearance"), display: cs.getPropertyValue("display") }
  })
  return { name, startUrl, typeText, latencyMs: latency, samples, valueTimeline: vals, renderTimeline: kinds, requests, nativeCancel }
}

;(async () => {
  const browser = await chromium.launch()
  const out = { label, base, at: new Date().toISOString(), runs: [] }
  const consoleMsgs = []
  for (const [name, url, text] of [["typed", "/shop", "pounded yam"], ["deeplink", "/shop?q=jollof", null]]) {
    const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } })
    const page = await ctx.newPage()
    page.on("console", (m) => {
      if (["error", "warning"].includes(m.type())) consoleMsgs.push({ run: name, type: m.type(), text: m.text().slice(0, 300) })
    })
    page.on("pageerror", (e) => consoleMsgs.push({ run: name, type: "pageerror", text: String(e).slice(0, 300) }))
    out.runs.push(await run(page, name, url, text))
    await ctx.close()
  }
  out.console = consoleMsgs
  fs.writeFileSync(path.join(outDir, `${label}-samples.json`), JSON.stringify(out, null, 2))
  await browser.close()
  console.log("wrote", path.join(outDir, `${label}-samples.json`))
})().catch((e) => {
  console.error(e)
  process.exit(1)
})
