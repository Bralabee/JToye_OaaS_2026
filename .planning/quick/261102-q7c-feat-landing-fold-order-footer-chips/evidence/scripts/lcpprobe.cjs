// usage: FRONTEND=<dir> node lcpprobe.cjs <baseUrl> <label> <outJson> [runs]
const path = require("path")
const fs = require("fs")
const { chromium } = require(path.resolve(process.env.FRONTEND, "node_modules/playwright"))
const [base, label, outJson, runsArg] = process.argv.slice(2)
const RUNS = Number(runsArg || 3)
;(async () => {
  const browser = await chromium.launch()
  const res = { label, base, takenAt: new Date().toISOString(), viewports: {} }
  for (const [w, h] of [[1366, 768], [1440, 900]]) {
    const runs = []
    for (let i = 0; i < RUNS; i++) {
      const ctx = await browser.newContext({ viewport: { width: w, height: h } })
      const page = await ctx.newPage()
      await page.goto(base + "/", { waitUntil: "load", timeout: 180000 })
      await page.waitForTimeout(3000)
      const r = await page.evaluate(
        () =>
          new Promise((resolve) => {
            const entries = []
            new PerformanceObserver((l) => entries.push(...l.getEntries())).observe({
              type: "largest-contentful-paint",
              buffered: true,
            })
            setTimeout(() => {
              const last = entries[entries.length - 1]
              const describe = (el) => {
                if (!el) return null
                const img = el.tagName === "IMG" ? el : null
                return {
                  tag: el.tagName,
                  src: img ? (img.currentSrc || img.src).replace(location.origin, "").slice(0, 140) : null,
                  loading: img ? img.getAttribute("loading") : null,
                  fetchpriority: img ? img.getAttribute("fetchpriority") : null,
                  inDoor: !!el.closest("[data-hero-door]"),
                  inCollage: !!el.closest("[aria-hidden]") && !!img,
                  text: img ? null : (el.textContent || "").trim().slice(0, 60),
                }
              }
              const imgs = Array.from(document.querySelectorAll("[data-hero-section] img")).map((i) => ({
                src: (i.currentSrc || "").replace(location.origin, "").slice(0, 100),
                loading: i.getAttribute("loading"),
                fetchpriority: i.getAttribute("fetchpriority"),
                visible: i.getBoundingClientRect().height > 0 && getComputedStyle(i).display !== "none",
              }))
              const resources = performance
                .getEntriesByType("resource")
                .filter((e) => e.initiatorType === "img")
                .map((e) => ({ name: e.name.replace(location.origin, "").slice(0, 100), start: Math.round(e.startTime), end: Math.round(e.responseEnd), bytes: e.transferSize }))
              resolve({
                lcpElement: describe(last && last.element),
                lcpStartTime: last ? Math.round(last.startTime) : null,
                lcpRenderTime: last ? Math.round(last.renderTime) : null,
                lcpLoadTime: last ? Math.round(last.loadTime) : null,
                lcpSize: last ? last.size : null,
                candidates: entries.map((e) => ({ t: Math.round(e.startTime), tag: e.element && e.element.tagName, size: e.size })),
                heroImages: imgs,
                imgResources: resources,
              })
            }, 500)
          })
      )
      runs.push(r)
      await ctx.close()
    }
    res.viewports[`${w}x${h}`] = runs
    console.log(
      `${label} ${w}x${h}`,
      runs.map((r) => JSON.stringify({ el: r.lcpElement && (r.lcpElement.src || r.lcpElement.text), loading: r.lcpElement && r.lcpElement.loading, prio: r.lcpElement && r.lcpElement.fetchpriority, start: r.lcpStartTime, render: r.lcpRenderTime, load: r.lcpLoadTime })).join("\n   ")
    )
  }
  await browser.close()
  fs.writeFileSync(outJson, JSON.stringify(res, null, 2))
})().catch((e) => {
  console.error(e)
  process.exit(1)
})
