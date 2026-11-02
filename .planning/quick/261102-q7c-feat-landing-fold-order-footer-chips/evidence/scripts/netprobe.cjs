// usage: FRONTEND=<dir> node netprobe.cjs <baseUrl> <label> <outJson>
// Records every /_next/image request (URL, Chrome's initial priority) on a cold load of /.
const path = require("path")
const fs = require("fs")
const { chromium } = require(path.resolve(process.env.FRONTEND, "node_modules/playwright"))
const [base, label, outJson] = process.argv.slice(2)
;(async () => {
  const browser = await chromium.launch()
  const out = { label, base, takenAt: new Date().toISOString(), viewports: {} }
  for (const [w, h] of [[1024, 768], [1280, 800], [1366, 768], [1440, 900], [390, 844]]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: h } })
    const page = await ctx.newPage()
    const cdp = await ctx.newCDPSession(page)
    await cdp.send("Network.enable")
    await cdp.send("Network.setCacheDisabled", { cacheDisabled: true })
    const reqs = []
    cdp.on("Network.requestWillBeSent", (e) => {
      if (e.request.url.includes("/_next/image")) {
        const u = new URL(e.request.url)
        reqs.push({
          image: decodeURIComponent(u.searchParams.get("url") || "").replace("/marketing/landing/", ""),
          w: u.searchParams.get("w"),
          priority: e.request.initialPriority,
          type: e.type,
        })
      }
    })
    await page.goto(base + "/", { waitUntil: "load", timeout: 180000 })
    await page.waitForTimeout(2500)
    const strip = reqs.filter((r) => r.image.startsWith("jollof-rice-and-dodo") && r.w !== "640")
    const egusi = reqs.filter((r) => r.image.startsWith("egusi"))
    const stripImg = await page.evaluate(() => {
      const i = document.querySelector('[data-hero-door][href="/shop"] img')
      return i ? { currentSrc: i.currentSrc.slice(0, 100), fetchpriority: i.getAttribute("fetchpriority"), loading: i.getAttribute("loading"), srcset: i.getAttribute("srcset"), sizes: i.getAttribute("sizes") } : null
    })
    out.viewports[`${w}x${h}`] = { stripRequests: strip, egusiRequests: egusi, stripImg, allImageRequests: reqs }
    console.log(label, `${w}x${h}`, "strip:", JSON.stringify(strip), "| egusi:", JSON.stringify(egusi), "| strip currentSrc:", stripImg && stripImg.currentSrc.slice(0, 70))
    await ctx.close()
  }
  await browser.close()
  fs.writeFileSync(outJson, JSON.stringify(out, null, 2))
})().catch((e) => {
  console.error(e)
  process.exit(1)
})
