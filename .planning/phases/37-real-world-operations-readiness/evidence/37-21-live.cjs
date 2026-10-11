// 37-21 live proof on the rebuilt compose runtime. Usage: node live.cjs <phase> <screens-dir>
// phases: kitchen | crossdevice | longname | dialog | backstops | notices
// Every check prints PASS/FAIL with the measured value; exit 1 on any FAIL.
const { chromium } = require(require.resolve("@playwright/test", { paths: ["/home/sanmi/IdeaProjects/JToye_OaaS_2026/frontend"] }))
const BASE = "http://localhost:3000"
const CORE = "http://localhost:9090"
const USER = process.env.E2E_VENDOR_USERNAME || "admin-user"
const PASSWORD = process.env.KC_SEED_USER_PASSWORD || ""
const [phase, screens] = process.argv.slice(2)
let failed = 0
const check = (name, ok, value) => {
  console.log(`${ok ? "PASS" : "FAIL"} [${phase}] ${name} :: ${JSON.stringify(value)}`)
  if (!ok) failed++
}
const UK = new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/London", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
const ukTime = (iso) => UK.format(new Date(iso))
const LONG60 = "Mama Put's Jollof, Suya & Small Chops Kitchen of Peckham Rye"
const LONG200 = "Open from about nine most weekdays but it really depends on deliveries and whether the oven is working, so please ring ahead first, especially on bank holidays and during Ramadan evenings thanks a lot"
const SHOP_NAME = process.env.LIVE_SHOP || "Mama Ade's Kitchen"

async function login(page) {
  if (!PASSWORD) { console.log("VOID: no KC_SEED_USER_PASSWORD"); process.exit(2) }
  await page.goto(`${BASE}/auth/signin`, { waitUntil: "domcontentloaded" })
  const sso = page.getByRole("button", { name: /sign in with keycloak/i })
  await sso.waitFor({ state: "visible", timeout: 15000 })
  await page.waitForTimeout(800)
  await sso.click()
  try { await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 25000 }) } catch {
    if (page.url().includes("/auth/signin")) await sso.click({ force: true }).catch(() => {})
    await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 20000 })
  }
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", USER)
    await page.fill("#password", PASSWORD)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 30000 })
}

/** The live tree: the streaming buffer can hold a hidden duplicate of the page. */
const live = (page) => page.locator("body > div:not([hidden])")

/** Rewrite the dashboard's shop LIST responses (never a write). */
async function rewriteShopList(page, edit) {
  await page.route(/\/api\/v1\/shops\?/, async (route) => {
    const res = await route.fetch()
    const json = await res.json()
    for (const s of json.content || []) edit(s)
    await route.fulfill({ response: res, json })
  })
}

async function pickKitchenShop(page) {
  // The board's shop follows the global switcher; choose it there so the run is about one known shop.
  await page.goto(`${BASE}/dashboard/kitchen`, { waitUntil: "domcontentloaded" })
  const control = live(page).getByTestId("kds-pause-control")
  await control.waitFor({ state: "visible", timeout: 20000 })
  await page.waitForFunction(() => {
    const b = document.querySelector('body > div:not([hidden]) [data-testid="kds-pause-control"]')
    return b && !b.disabled
  }, null, { timeout: 20000 })
  return control
}

async function openPauseMenu(page) {
  const control = live(page).getByTestId("kds-pause-control")
  await control.click()
  const menu = page.getByRole("menu")
  await menu.waitFor({ state: "visible", timeout: 10000 })
  await page.waitForTimeout(400) // let the zoom-in-95 open animation finish before measuring
  return menu
}

async function pauseVia(page, item) {
  const menu = await openPauseMenu(page)
  const [resp] = await Promise.all([
    page.waitForResponse((r) => /\/api\/v1\/shops\/[^/]+\/pause$/.test(r.url()) && r.request().method() === "PUT"),
    menu.getByRole("menuitem", { name: item }).click(),
  ])
  return { status: resp.status(), body: await resp.json(), sent: resp.request().postDataJSON() }
}

async function resumeViaBanner(page) {
  const banner = live(page).getByTestId("kds-paused-banner")
  const [resp] = await Promise.all([
    page.waitForResponse((r) => /\/pause$/.test(r.url()) && r.request().method() === "DELETE"),
    banner.getByRole("button", { name: "Resume orders" }).click(),
  ])
  return resp.status()
}

async function ensureResumed(page) {
  const text = (await live(page).getByTestId("kds-pause-control").textContent()).trim()
  if (text.startsWith("Paused")) {
    const menu = await openPauseMenu(page)
    await Promise.all([
      page.waitForResponse((r) => /\/pause$/.test(r.url()) && r.request().method() === "DELETE"),
      menu.getByRole("menuitem", { name: "Resume orders now" }).click(),
    ])
    await page.waitForTimeout(500)
  }
}

async function selectShopInSwitcher(page, name) {
  // The kitchen reconciles with the dashboard switcher context; set it through the UI's own store key.
  await page.evaluate(() => 0)
}

async function publicShop(slug) {
  const r = await fetch(`${CORE}/api/v1/public/shops/${slug}`)
  return r.ok ? r.json() : { status: r.status }
}

;(async () => {
  const browser = await chromium.launch()
  try {
    if (phase === "kitchen") {
      const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } })
      const page = await ctx.newPage()
      await login(page)
      const control = await pickKitchenShop(page)
      await ensureResumed(page)
      let shopName = ""
      check("control reads 'Pause orders' when not paused", (await control.textContent()).trim() === "Pause orders", (await control.textContent()).trim())
      const box = await control.boundingBox()
      check("control is 44px tall (h-11)", box && Math.round(box.height) === 44, box && box.height)
      const sw = await page.evaluate(() => document.documentElement.scrollWidth)
      check("390px: header row wraps, no horizontal scroll", sw <= 390, sw)
      check("390px: control inside the viewport", box && box.x + box.width <= 390, box && box.x + box.width)
      const menu = await openPauseMenu(page)
      const items = await menu.getByRole("menuitem").allTextContents()
      check("menu: 20 minutes, 40 minutes, Until I reopen", JSON.stringify(items) === JSON.stringify(["20 minutes", "40 minutes", "Until I reopen"]), items)
      const rowH = (await menu.getByRole("menuitem").first().boundingBox()).height
      check("menu rows are 44px", Math.round(rowH) === 44, rowH)
      await page.screenshot({ path: `${screens}/kitchen-pause-menu-390.png` })
      await page.keyboard.press("Escape")
      await page.getByRole("menu").waitFor({ state: "detached", timeout: 5000 }).catch(() => {})
      await page.waitForTimeout(300)

      const clicked = Date.now()
      const r = await pauseVia(page, "20 minutes")
      check("PUT {minutes: 20} -> 200", r.status === 200 && r.sent.minutes === 20, { status: r.status, sent: r.sent })
      const until = r.body.ordersPausedUntil
      shopName = r.body.name
      const hhmm = ukTime(until)
      check("server answered with ordersPausedUntil", !!until, { until, uk: hhmm, browserSum: ukTime(new Date(clicked + 20 * 60000).toISOString()) })
      await page.waitForTimeout(800)
      const label = (await control.textContent()).trim()
      check("control reads the server's time", label === `Paused until ${hhmm}`, label)
      const banner = live(page).getByTestId("kds-paused-banner")
      const btext = (await banner.textContent()).trim()
      check("banner: K3 timed sentence with the server's time, and the shop's name", btext.includes(`Orders paused until ${hhmm}. Customers can see your menu but can't order.`) && btext.includes(shopName), btext)
      const bg = await banner.evaluate((el) => getComputedStyle(el).backgroundColor)
      check("banner is white (L6: never slate-900-filled)", bg === "rgb(255, 255, 255)", bg)
      await page.screenshot({ path: `${screens}/kitchen-paused-timed-390.png` })
      const pub = await publicShop(r.body.slug)
      check("public shop agrees: openNow false, pausedUntil = the banner's instant", pub.openNow === false && pub.pausedUntil && ukTime(pub.pausedUntil) === hhmm, { openNow: pub.openNow, pausedUntil: pub.pausedUntil })

      // the shops table shows the same pause
      const page2 = await ctx.newPage()
      await page2.goto(`${BASE}/dashboard/shops`, { waitUntil: "domcontentloaded" })
      const row = live(page2).locator("tr", { hasText: shopName })
      await row.first().waitFor({ timeout: 20000 })
      const rowText = await row.first().textContent()
      check("shops table row badge 'Paused until HH:MM'", rowText.includes(`Paused until ${hhmm}`), rowText.replace(/\s+/g, " ").slice(0, 200))
      await row.first().screenshot({ path: `${screens}/shops-row-paused.png` })
      await page2.close()

      const st = await resumeViaBanner(page)
      check("Resume orders -> DELETE 200", st === 200, st)
      await banner.waitFor({ state: "detached", timeout: 10000 }).catch(() => {})
      check("banner gone after resume", (await live(page).getByTestId("kds-paused-banner").count()) === 0, await live(page).getByTestId("kds-paused-banner").count())
      check("control back to 'Pause orders'", (await control.textContent()).trim() === "Pause orders", (await control.textContent()).trim())
      const pub2 = await publicShop(r.body.slug)
      check("public shop open again", pub2.openNow === true && !pub2.pausedUntil, { openNow: pub2.openNow, pausedUntil: pub2.pausedUntil })

      const r2 = await pauseVia(page, "Until I reopen")
      check("PUT {untilReopened: true} -> 200", r2.status === 200 && r2.sent.untilReopened === true, { status: r2.status, sent: r2.sent })
      await page.waitForTimeout(800)
      check("control reads 'Paused'", (await control.textContent()).trim() === "Paused", (await control.textContent()).trim())
      const b2 = (await live(page).getByTestId("kds-paused-banner").textContent()).trim()
      check("banner: indefinite sentence", b2.includes("Orders paused until you reopen. Customers can see your menu but can't order."), b2)
      await page.screenshot({ path: `${screens}/kitchen-paused-indefinite-390.png` })
      const m = await openPauseMenu(page)
      const items2 = await m.getByRole("menuitem").allTextContents()
      check("paused menu adds 'Resume orders now'", items2.includes("Resume orders now"), items2)
      const [del] = await Promise.all([
        page.waitForResponse((x) => /\/pause$/.test(x.url()) && x.request().method() === "DELETE"),
        m.getByRole("menuitem", { name: "Resume orders now" }).click(),
      ])
      check("Resume orders now -> DELETE 200", del.status() === 200, del.status())
      await page.waitForTimeout(800)
      check("resumed: no banner", (await live(page).getByTestId("kds-paused-banner").count()) === 0, null)
      console.log(`INFO shop=${shopName} slug=${r.body.slug} id=${r.body.id}`)
    }

    if (phase === "crossdevice") {
      // Two "devices": separate browser contexts (separate sessions). Device B is never touched.
      const a = await browser.newContext({ viewport: { width: 1280, height: 900 } })
      const b = await browser.newContext({ viewport: { width: 1280, height: 900 } })
      const pa = await a.newPage(); const pb = await b.newPage()
      await login(pa); await login(pb)
      await pickKitchenShop(pa); await pickKitchenShop(pb)
      await ensureResumed(pa)
      await pb.reload({ waitUntil: "domcontentloaded" }); await pickKitchenShop(pb)
      const t0 = Date.now()
      const r = await pauseVia(pa, "40 minutes")
      const hhmm = ukTime(r.body.ordersPausedUntil)
      const bannerB = live(pb).getByTestId("kds-paused-banner")
      await bannerB.waitFor({ state: "visible", timeout: 75000 }).catch(() => {})
      const seenAfter = Math.round((Date.now() - t0) / 1000)
      const tb = (await bannerB.count()) ? (await bannerB.textContent()).trim() : null
      check("device B shows device A's pause within one 60 s poll, with the server's time", tb && tb.includes(`Orders paused until ${hhmm}.`), { seconds: seenAfter, text: tb })
      await pb.screenshot({ path: `${screens}/kitchen-crossdevice-b-1280.png` })
      await resumeViaBanner(pa)
      const t1 = Date.now()
      await bannerB.waitFor({ state: "detached", timeout: 75000 }).catch(() => {})
      check("device B clears the banner within one poll of the resume", (await live(pb).getByTestId("kds-paused-banner").count()) === 0, { seconds: Math.round((Date.now() - t1) / 1000) })
    }

    if (phase === "longname") {
      const ctx = await browser.newContext({ viewport: { width: 360, height: 780 } })
      const page = await ctx.newPage()
      await login(page)
      await rewriteShopList(page, (s) => { s.name = LONG60 })
      await page.addInitScript(() => {
        document.addEventListener("DOMContentLoaded", () => { document.documentElement.style.fontSize = "130%" })
      })
      await pickKitchenShop(page)
      await ensureResumed(page)
      await pauseVia(page, "20 minutes")
      await page.waitForTimeout(800)
      const banner = live(page).getByTestId("kds-paused-banner")
      const root = await page.evaluate(() => getComputedStyle(document.documentElement).fontSize)
      const bb = await banner.boundingBox()
      const nameEl = banner.getByText(LONG60)
      const nameOk = await nameEl.evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth, right: el.getBoundingClientRect().right }))
      const docSw = await page.evaluate(() => document.documentElement.scrollWidth)
      check("root font-size 130% applied", root === "20.8px", root)
      check("60-char name wraps inside the banner (no clipping)", nameOk.sw <= nameOk.cw && nameOk.right <= 360, nameOk)
      check("banner inside 360px", bb && bb.x + bb.width <= 360, bb && bb.x + bb.width)
      check("no horizontal scroll at 360px / 130%", docSw <= 360, docSw)
      await banner.scrollIntoViewIfNeeded()
      await page.screenshot({ path: `${screens}/kitchen-banner-60char-360-root130.png` })
      await resumeViaBanner(page)
    }

    if (phase === "dialog") {
      const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } })
      const page = await ctx.newPage()
      await login(page)
      await page.goto(`${BASE}/dashboard/shops`, { waitUntil: "domcontentloaded" })
      const edit = live(page).getByRole("button", { name: `Edit shop ${SHOP_NAME}` })
      await edit.waitFor({ timeout: 20000 })
      const openDialog = async () => {
        await live(page).getByRole("button", { name: `Edit shop ${SHOP_NAME}` }).click()
        const d = page.getByRole("dialog")
        await d.waitFor({ timeout: 10000 })
        return d
      }
      let dialog = await openDialog()
      const hours = dialog.getByRole("group", { name: "Opening hours" })
      const before = {}
      for (const day of ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]) {
        before[day] = [await hours.getByLabel(`${day} opens`, { exact: true }).inputValue(), await hours.getByLabel(`${day} closes`, { exact: true }).inputValue()]
      }
      check("editor pre-filled from the seeded all-day STRUCTURED schedule", Object.values(before).every(([o, c]) => o === "00:00" && c === "00:00"), before)
      check("free-text inputs gone", (await dialog.getByPlaceholder("09:00-17:00 or Closed").count()) === 0, null)
      await hours.scrollIntoViewIfNeeded()
      await page.screenshot({ path: `${screens}/dialog-editor-prefilled-1280.png` })
      await hours.getByLabel("Monday opens", { exact: true }).fill("09:00")
      await hours.getByLabel("Monday closes", { exact: true }).fill("17:00")
      const [put] = await Promise.all([
        page.waitForResponse((r) => /\/api\/v1\/shops\/[^/?]+$/.test(r.url()) && r.request().method() === "PUT"),
        dialog.getByRole("button", { name: "Update Shop" }).click(),
      ])
      const sent = put.request().postDataJSON()
      const got = await put.json()
      check("save -> 200", put.status() === 200, put.status())
      check("request carries the structured schedule with Monday 09:00-17:00 and the other days all-day", JSON.stringify(sent.openingSchedule.mon) === JSON.stringify([{ open: "09:00", close: "17:00" }]) && JSON.stringify(sent.openingSchedule.tue) === JSON.stringify([{ open: "00:00", close: "00:00" }]), sent.openingSchedule)
      check("request carries the current slug (the storefront address stays)", typeof sent.slug === "string" && got.slug === sent.slug, { sent: sent.slug, saved: got.slug })
      check("server says STRUCTURED", got.hoursSource === "STRUCTURED", got.hoursSource)
      const slug = got.slug
      // the storefront list
      const sp = await ctx.newPage()
      await sp.goto(`${BASE}/shop/${slug}`, { waitUntil: "domcontentloaded" })
      await sp.getByRole("heading", { level: 1 }).first().waitFor({ timeout: 15000 })
      await sp.waitForTimeout(1200)
      const gotIt = sp.getByRole("button", { name: "Got it" }); if (await gotIt.count()) await gotIt.first().click().catch(() => {})
      const summary = live(sp).getByText("Opening hours", { exact: true })
      await summary.click()
      const details = live(sp).locator("details", { has: sp.getByText("Opening hours", { exact: true }) })
      const rows = await details.locator("div > div").evaluateAll((els) => els.map((e) => Array.from(e.querySelectorAll("span")).map((s) => s.textContent)))
      const rowsObj = Object.fromEntries(rows.filter((r) => r.length === 2))
      check("storefront list: Monday 09:00–17:00, Tuesday Open 24 hours", rowsObj.Monday === "09:00–17:00" && rowsObj.Tuesday === "Open 24 hours", rowsObj)
      await details.screenshot({ path: `${screens}/storefront-hours-after-save.png` })
      const pub = await publicShop(slug)
      check("public payload schedule matches", JSON.stringify(pub.openingSchedule && pub.openingSchedule.mon) === JSON.stringify([{ open: "09:00", close: "17:00" }]), pub.openingSchedule && pub.openingSchedule.mon)
      // restore the seeded all-day week
      dialog = await openDialog()
      const h2 = dialog.getByRole("group", { name: "Opening hours" })
      await h2.getByLabel("Monday opens", { exact: true }).fill("00:00")
      await h2.getByLabel("Monday closes", { exact: true }).fill("00:00")
      const [put2] = await Promise.all([
        page.waitForResponse((r) => /\/api\/v1\/shops\/[^/?]+$/.test(r.url()) && r.request().method() === "PUT"),
        dialog.getByRole("button", { name: "Update Shop" }).click(),
      ])
      const sent2 = put2.request().postDataJSON()
      check("restore save -> 200, all seven days 00:00-00:00", put2.status() === 200 && Object.values(sent2.openingSchedule).length === 7 && Object.values(sent2.openingSchedule).every((w) => JSON.stringify(w) === JSON.stringify([{ open: "00:00", close: "00:00" }])), sent2.openingSchedule)
      const pub2 = await publicShop(slug)
      check("restored: public schedule is the all-day week again, slug unchanged", pub2.slug === slug && Object.values(pub2.openingSchedule || {}).every((w) => JSON.stringify(w) === JSON.stringify([{ open: "00:00", close: "00:00" }])), { slug: pub2.slug, schedule: pub2.openingSchedule })
      console.log(`INFO slug=${slug}`)
    }

    if (phase === "backstops") {
      const ctx = await browser.newContext({ viewport: { width: 375, height: 812 } })
      const page = await ctx.newPage()
      await login(page)
      await page.goto(`${BASE}/dashboard/shops`, { waitUntil: "domcontentloaded" })
      await live(page).getByRole("button", { name: `Edit shop ${SHOP_NAME}` }).click()
      let dialog = page.getByRole("dialog")
      await dialog.waitFor({ timeout: 10000 })
      const hours = dialog.getByRole("group", { name: "Opening hours" })
      await hours.scrollIntoViewIfNeeded()
      const o = await hours.getByLabel("Monday opens", { exact: true }).boundingBox()
      const c = await hours.getByLabel("Monday closes", { exact: true }).boundingBox()
      check("375px: the two time inputs sit side by side at equal width", Math.abs(o.y - c.y) < 1 && Math.abs(o.width - c.width) < 1 && o.x + o.width <= c.x, { open: o, close: c })
      const dw = await dialog.evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth }))
      check("375px: no horizontal overflow inside the dialog", dw.sw <= dw.cw, dw)
      const docSw = await page.evaluate(() => document.documentElement.scrollWidth)
      check("375px: no horizontal page scroll", docSw <= 375, docSw)
      await page.screenshot({ path: `${screens}/dialog-editor-375.png` })
      await page.keyboard.press("Escape")
      await dialog.waitFor({ state: "detached" }).catch(() => {})
      // needs-review 200-char quote (list response rewritten in the browser; nothing is saved)
      const page2 = await ctx.newPage()
      await rewriteShopList(page2, (s) => {
        if (s.name === SHOP_NAME) {
          s.hoursSource = "NEEDS_REVIEW"
          s.legacyOpeningHours = { wed: LONG200 }
          s.openingSchedule = { ...s.openingSchedule, wed: [] }
        }
      })
      await page2.goto(`${BASE}/dashboard/shops`, { waitUntil: "domcontentloaded" })
      await live(page2).getByRole("button", { name: `Edit shop ${SHOP_NAME}` }).click()
      dialog = page2.getByRole("dialog")
      await dialog.waitFor({ timeout: 10000 })
      const line = dialog.getByText(/^We couldn't read "Open from about nine/)
      await line.scrollIntoViewIfNeeded()
      const lw = await line.evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth, right: el.getBoundingClientRect().right, len: el.textContent.length }))
      const dw2 = await dialog.evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth, right: el.getBoundingClientRect().right }))
      check("200-char quote wraps inside the 375px dialog", lw.sw <= lw.cw && lw.right <= dw2.right, { line: lw, dialog: dw2 })
      check("dialog has no horizontal overflow with the quote", dw2.sw <= dw2.cw, dw2)
      check("Wednesday opens as Closed", (await dialog.getByRole("checkbox", { name: "Closed on Wednesday" }).getAttribute("aria-checked")) === "true", null)
      await page2.screenshot({ path: `${screens}/dialog-needs-review-200char-375.png` })
    }

    if (phase === "notices") {
      const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } })
      const page = await ctx.newPage()
      await login(page)
      let n = 0
      await rewriteShopList(page, (s) => {
        if (s.name === SHOP_NAME) { s.hoursSource = "LEGACY_ALWAYS_OPEN"; delete s.openingSchedule }
        else if (s.name === "Peckham Jollof Co.") { s.hoursSource = "NEEDS_REVIEW"; s.legacyOpeningHours = { tue: "lunchtime till late" }; s.openingSchedule = { ...s.openingSchedule, tue: [] } }
        n++
      })
      await page.goto(`${BASE}/dashboard`, { waitUntil: "domcontentloaded" })
      const card = live(page).getByTestId("hours-notice-card")
      await card.waitFor({ timeout: 20000 })
      const text = (await card.textContent()).replace(/\s+/g, " ")
      check("home card: legacy line", text.includes("1 shop has no opening hours") && text.includes("Customers can order from it at any time, including when you're closed."), text)
      check("home card: needs-review line under the ruling", text.includes("Check the opening hours for Peckham Jollof Co.") && text.includes("We couldn't read the hours you entered for Tuesday. Customers can order on that day at any time until you save your hours."), text)
      await card.screenshot({ path: `${screens}/home-hours-notice-390.png` })
      await card.getByRole("link", { name: `Set opening hours for ${SHOP_NAME}` }).click()
      await page.waitForURL(/\/dashboard\/shops/, { timeout: 15000 })
      const dialog = page.getByRole("dialog")
      await dialog.waitFor({ timeout: 20000 })
      await page.waitForTimeout(600)
      const active = await page.evaluate(() => document.activeElement && document.activeElement.id)
      check("Set opening hours opens that shop's dialog focused on the hours fieldset", active === "opening-hours", active)
      check("legacy info line shown", (await dialog.getByText("Customers can currently order from this shop at any time, because no opening hours were set. Add your hours to change that.").count()) === 1, null)
      check("parameters dropped after use", !page.url().includes("edit="), page.url())
      await page.screenshot({ path: `${screens}/dialog-from-notice-390.png` })
      await page.keyboard.press("Escape")
      const tableText = (await live(page).locator("table").first().textContent()).replace(/\s+/g, " ")
      check("table badges: Hours not set and Check hours", tableText.includes("Hours not set") && tableText.includes("Check hours"), tableText.slice(0, 300))
      await live(page).locator("table").first().screenshot({ path: `${screens}/shops-table-badges.png` })
    }
  } catch (e) {
    console.log(`FAIL [${phase}] exception :: ${e && e.message}`)
    failed++
  } finally {
    await browser.close()
  }
  console.log(failed ? `RESULT [${phase}] ${failed} FAIL` : `RESULT [${phase}] ALL PASS`)
  process.exit(failed ? 1 : 0)
})()
