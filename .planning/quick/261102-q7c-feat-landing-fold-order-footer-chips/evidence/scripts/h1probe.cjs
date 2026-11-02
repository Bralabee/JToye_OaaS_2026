const path = require("path")
const { chromium } = require(path.resolve(process.env.FRONTEND, "node_modules/playwright"))
;(async () => {
  const browser = await chromium.launch()
  for (const [w, h] of [[1024, 768], [1280, 800], [1366, 768], [1440, 900], [390, 844]]) {
    const page = await browser.newPage({ viewport: { width: w, height: h } })
    await page.goto(process.argv[2] + "/", { waitUntil: "load" })
    await page.evaluate(() => document.fonts.ready)
    await page.waitForTimeout(2000)
    const r = await page.evaluate(() => {
      const h1 = document.querySelector("h1")
      const lines = (el) => {
        const range = document.createRange()
        range.selectNodeContents(el)
        return new Set(Array.from(range.getClientRects()).map((r) => Math.round(r.top))).size
      }
      const gold = document.querySelector('[data-hero-door][href="/shop"]')
      const out = { pageOverflowX: document.documentElement.scrollWidth - window.innerWidth, at56: { lines: lines(h1), h: h1.getBoundingClientRect().height, door: gold.getBoundingClientRect().bottom } }
      if (window.innerWidth >= 1024) {
        h1.style.fontSize = "48px"
        out.at48 = { lines: lines(h1), h: h1.getBoundingClientRect().height, door: gold.getBoundingClientRect().bottom }
        h1.style.minHeight = "0"
        out.at48noMin = { lines: lines(h1), h: h1.getBoundingClientRect().height, door: gold.getBoundingClientRect().bottom }
      }
      const rail = document.querySelector("[data-cuisine-rail]")
      out.rail = rail ? { sw: rail.scrollWidth, cw: rail.clientWidth } : null
      return out
    })
    console.log(w + "x" + h, JSON.stringify(r))
    await page.close()
  }
  await browser.close()
})()
