// Keyboard reachability of the sixth chip inside the rail, and the credits disclosure.
const path = require("path")
const fs = require("fs")
const { chromium } = require(path.resolve(process.env.FRONTEND, "node_modules/playwright"))
;(async () => {
  const out = {}
  const browser = await chromium.launch()
  for (const [w, h] of [[390, 844], [1024, 768], [1366, 768]]) {
    const page = await browser.newPage({ viewport: { width: w, height: h } })
    await page.goto(process.argv[2] + "/", { waitUntil: "load" })
    await page.evaluate(() => document.fonts.ready)
    await page.waitForTimeout(1500)
    // Focus the search input, then Tab forward until the "Desserts" chip has focus.
    await page.locator("#hero-search").focus()
    const seen = []
    let reached = false
    for (let i = 0; i < 12; i++) {
      await page.keyboard.press("Tab")
      const name = await page.evaluate(() => document.activeElement && document.activeElement.textContent.trim())
      seen.push(name)
      if (name === "Desserts") { reached = true; break }
    }
    const vis = await page.evaluate(() => {
      const a = document.activeElement
      const rail = document.querySelector("[data-cuisine-rail]")
      const ar = a.getBoundingClientRect(), rr = rail.getBoundingClientRect()
      const ring = getComputedStyle(a).boxShadow
      return {
        activeLeft: Math.round(ar.left), activeRight: Math.round(ar.right),
        railLeft: Math.round(rr.left), railRight: Math.round(rr.right),
        fullyInsideRail: ar.left >= rr.left - 0.5 && ar.right <= rr.right + 0.5,
        railScrollLeft: Math.round(rail.scrollLeft),
        focusRingPainted: ring && ring !== "none",
        pageOverflowX: document.documentElement.scrollWidth - window.innerWidth,
      }
    })
    await page.screenshot({ path: path.join(process.argv[3], `focus-sixth-chip-${w}x${h}.png`) })
    // credits disclosure: closed by default, opens with Enter on the summary, links then visible
    const details = page.locator("[data-photo-credits]")
    const closed = await details.evaluate((d) => !d.open)
    await page.locator("[data-photo-credits] summary").focus()
    await page.keyboard.press("Enter")
    const opened = await details.evaluate((d) => d.open)
    const visibleLinks = await page.locator("[data-photo-credits] a").evaluateAll((as) => as.filter((a) => a.getBoundingClientRect().height > 0).length)
    const domLinksWhenClosed = await page.evaluate(() => document.querySelectorAll("[data-photo-credits] a").length)
    out[`${w}x${h}`] = { tabSequence: seen, reachedSixth: reached, ...vis, credits: { closedByDefault: closed, openedWithEnter: opened, visibleLinksAfterOpen: visibleLinks, linksInDom: domLinksWhenClosed } }
    await page.close()
  }
  await browser.close()
  fs.writeFileSync(path.join(process.argv[3], "keyboard-probe.json"), JSON.stringify(out, null, 2))
  console.log(JSON.stringify(out, null, 1))
})()
