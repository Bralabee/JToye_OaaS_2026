// usage: FRONTEND=<frontend dir> node measure.cjs <baseUrl> <outDir> <label>
const path = require("path")
const fs = require("fs")
const { chromium } = require(path.resolve(process.env.FRONTEND, "node_modules/playwright"))
const [base, outDir, label] = process.argv.slice(2)
const VIEWPORTS = [
  { name: "1024x768", width: 1024, height: 768 },
  { name: "1280x800", width: 1280, height: 800 },
  { name: "1366x768", width: 1366, height: 768 },
  { name: "1440x900", width: 1440, height: 900 },
  { name: "390x844", width: 390, height: 844 },
]
;(async () => {
  fs.mkdirSync(outDir, { recursive: true })
  const browser = await chromium.launch()
  const results = { label, base, takenAt: new Date().toISOString(), viewports: {} }
  for (const vp of VIEWPORTS) {
    const page = await browser.newPage({ viewport: { width: vp.width, height: vp.height } })
    const resp = await page.goto(base + "/", { waitUntil: "load", timeout: 180000 })
    await page.evaluate(() => document.fonts.ready)
    await page.waitForTimeout(2500) // past the door deal-in (0.45s delay + 0.6s + stagger)
    const m = await page.evaluate(() => {
      const hero = document.querySelector("[data-hero-section]")
      const chips = Array.from(hero.querySelectorAll('a[href^="/shop?q="]'))
      const rail = chips[0] ? chips[0].parentElement : null
      const tops = [...new Set(chips.map((c) => Math.round(c.getBoundingClientRect().top + window.scrollY)))]
      const gold = document.querySelector('[data-hero-door][href="/shop"]')
      const gr = gold.getBoundingClientRect()
      const browse = Array.from(gold.querySelectorAll("span")).find(
        (s) => /Browse shops/.test(s.textContent) && s.querySelectorAll("span").length === 0
      )
      const strip = gold.querySelector("img")
      const stripVisible = strip
        ? getComputedStyle(strip).display !== "none" && strip.getBoundingClientRect().height > 0
        : false
      const main = document.querySelector("main")
      const h2s = Array.from(main.querySelectorAll("h2")).map((h) => h.textContent.trim())
      const footer = document.querySelector("footer")
      const fr = footer.getBoundingClientRect()
      const credEl =
        document.querySelector("[data-photo-credits]") ||
        Array.from(main.querySelectorAll("p")).find((p) => /^Photographs/.test(p.textContent.trim()))
      const cr = credEl ? credEl.getBoundingClientRect() : null
      const credMt = credEl ? parseFloat(getComputedStyle(credEl).marginTop) : 0
      const lastChip = Array.from(main.querySelectorAll("[data-hero-chip]")).pop()
      const trustBottom = lastChip ? lastChip.getBoundingClientRect().bottom : null
      const h1 = document.querySelector("h1")
      const sections = Array.from(main.querySelectorAll("section")).map((s) => ({
        bg: (s.className.match(/bg-[a-z]+(-\d+)?/) || [""])[0],
        firstHeading: ((s.querySelector("h1,h2") || {}).textContent || "").trim().slice(0, 40),
      }))
      return {
        scrollY: window.scrollY,
        chipCount: chips.length,
        chipRowTops: tops,
        chipRows: tops.length,
        railScrollWidth: rail ? rail.scrollWidth : null,
        railClientWidth: rail ? rail.clientWidth : null,
        railFits: rail ? rail.scrollWidth <= rail.clientWidth : null,
        h1FontSize: getComputedStyle(h1).fontSize,
        h1Height: Math.round(h1.getBoundingClientRect().height),
        goldDoorTop: Math.round(gr.top),
        goldDoorBottom: Math.round(gr.bottom),
        goldDoorHeight: Math.round(gr.height),
        browseShopsBottom: browse ? Math.round(browse.getBoundingClientRect().bottom) : null,
        goldDoorStripVisible: stripVisible,
        h2Order: h2s,
        sections,
        footerHeight: Math.round(fr.height),
        creditsHeight: cr ? Math.round(cr.height) : null,
        creditsMarginTop: credMt,
        creditsTag: credEl ? credEl.tagName : null,
        trustBottomToFooterBottom: trustBottom != null ? Math.round(fr.bottom - trustBottom) : null,
        footerPlusCredits: cr ? Math.round(fr.height + cr.height + credMt) : null,
        docHeight: document.documentElement.scrollHeight,
      }
    })
    m.httpStatus = resp.status()
    m.viewport = vp
    await page.screenshot({ path: path.join(outDir, `fold-${vp.name}.png`) })
    for (let y = 0; y < m.docHeight; y += Math.floor(vp.height / 2)) {
      await page.evaluate((yy) => window.scrollTo(0, yy), y)
      await page.waitForTimeout(150)
    }
    await page.waitForTimeout(800)
    await page.screenshot({ path: path.join(outDir, `full-${vp.name}.png`), fullPage: true })
    results.viewports[vp.name] = m
    await page.close()
  }
  await browser.close()
  fs.writeFileSync(path.join(outDir, `measure-${label}.json`), JSON.stringify(results, null, 2))
  for (const [k, v] of Object.entries(results.viewports)) {
    console.log(
      k,
      JSON.stringify({
        http: v.httpStatus, chips: v.chipCount, rows: v.chipRows, fits: v.railFits,
        sw: v.railScrollWidth, cw: v.railClientWidth, h1: v.h1FontSize,
        door: [v.goldDoorTop, v.goldDoorBottom], browse: v.browseShopsBottom, strip: v.goldDoorStripVisible,
        h2: v.h2Order, footer: v.footerHeight, credits: v.creditsHeight, credTag: v.creditsTag,
        sum: v.footerPlusCredits, trust2foot: v.trustBottomToFooterBottom,
      })
    )
  }
})().catch((e) => {
  console.error(e)
  process.exit(1)
})
