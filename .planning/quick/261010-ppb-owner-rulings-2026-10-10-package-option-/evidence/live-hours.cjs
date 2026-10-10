// 261010-ppb live proof on the rebuilt compose frontend. Usage: node live-hours.cjs <phase> <screens-dir>
// phases: notices | editor | pause403
// Login and the shops-LIST rewrite are 37-21's (evidence/37-21-live.cjs). Nothing is written to the
// server: the NEEDS_REVIEW shop is produced by rewriting GET responses in the browser, the editor's
// save is captured by page.route and fulfilled WITHOUT forwarding, and the pause 403 is a stub.
// Every check prints PASS/FAIL with the measured value; exit 1 on any FAIL, 2 on VOID.
const { chromium } = require(require.resolve("@playwright/test", { paths: ["/home/sanmi/IdeaProjects/JToye_OaaS_2026/frontend"] }))
const BASE = "http://localhost:3000"
const USER = process.env.E2E_VENDOR_USERNAME || "admin-user"
const PASSWORD = process.env.KC_SEED_USER_PASSWORD || ""
const [phase, screens] = process.argv.slice(2)
const REVIEW_SHOP = process.env.LIVE_REVIEW_SHOP || "Peckham Jollof Co."
let failed = 0
const check = (name, ok, value) => {
  console.log(`${ok ? "PASS" : "FAIL"} [${phase}] ${name} :: ${JSON.stringify(value)}`)
  if (!ok) failed++
}

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
  check(`signed in as ${USER} and reached /dashboard`, page.url().includes("/dashboard"), page.url())
}

/** The live tree: the streaming buffer can hold a hidden duplicate of the page. */
const live = (page) => page.locator("body > div:not([hidden])")

/** Rewrite the dashboard's shop LIST and by-id GET responses (never a write). */
async function rewriteShops(page, edit) {
  await page.route(/\/api\/v1\/shops(\?|\/[^/?]+$)/, async (route) => {
    if (route.request().method() !== "GET") return route.fallback()
    const res = await route.fetch()
    const json = await res.json()
    if (Array.isArray(json.content)) for (const s of json.content) edit(s)
    else if (json && json.name) edit(json)
    await route.fulfill({ response: res, json })
  })
}

const asReview = (days) => (s) => {
  if (s.name !== REVIEW_SHOP) return
  s.hoursSource = "NEEDS_REVIEW"
  s.openingSchedule = { mon: [{ open: "10:00", close: "16:00" }] }
  s.legacyOpeningHours = { mon: "10:00-16:00", sun: "Closed" }
  for (const d of days) s.legacyOpeningHours[d] = "lunchtime till late"
}

;(async () => {
  const browser = await chromium.launch()
  try {
    if (phase === "notices") {
      const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } })
      const page = await ctx.newPage()
      await login(page)
      let days = ["tue"]
      await rewriteShops(page, (s) => asReview(days)(s))
      await page.goto(`${BASE}/dashboard`, { waitUntil: "domcontentloaded" })
      let card = live(page).getByTestId("hours-notice-card")
      await card.waitFor({ timeout: 20000 })
      let text = (await card.textContent()).replace(/\s+/g, " ")
      check("card, one unreadable day: the ruled singular sentence", text.includes(`Check the opening hours for ${REVIEW_SHOP}`) && text.includes("We could not read the hours you entered for Tuesday, so customers can order on that day at any time. Check and save your hours."), text)
      check("card: the 37-21 wording is gone", !text.includes("until you save your hours"), null)
      await card.screenshot({ path: `${screens}/home-card-singular-390.png` })
      days = ["tue", "thu"]
      await page.reload({ waitUntil: "domcontentloaded" })
      card = live(page).getByTestId("hours-notice-card")
      await card.waitFor({ timeout: 20000 })
      text = (await card.textContent()).replace(/\s+/g, " ")
      check("card, two unreadable days: the ruled plural sentence", text.includes("We could not read the hours you entered for Tuesday and Thursday, so customers can order on those days at any time. Check and save your hours."), text)
    }

    if (phase === "editor") {
      const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } })
      const page = await ctx.newPage()
      await login(page)
      await rewriteShops(page, asReview(["tue"]))
      let captured = null
      await page.route(/\/api\/v1\/shops\/[^/?]+$/, async (route) => {
        if (route.request().method() !== "PUT") return route.fallback()
        captured = route.request().postDataJSON()
        // Fulfilled here, never forwarded: the dev database is not written.
        await route.fulfill({ status: 200, contentType: "application/json", json: { id: route.request().url().split("/").pop(), name: REVIEW_SHOP, ...captured, hoursSource: "STRUCTURED" } })
      })
      await page.goto(`${BASE}/dashboard/shops`, { waitUntil: "domcontentloaded" })
      const edit = live(page).getByRole("button", { name: `Edit shop ${REVIEW_SHOP}` })
      await edit.waitFor({ timeout: 20000 })
      await edit.click()
      const dialog = page.getByRole("dialog")
      await dialog.waitFor({ timeout: 10000 })
      const hours = dialog.getByRole("group", { name: "Opening hours" })
      const tue = hours.getByRole("group", { name: "Tuesday" })
      const sun = hours.getByRole("group", { name: "Sunday" })
      const tueClosed = await hours.getByRole("checkbox", { name: "Closed on Tuesday" }).getAttribute("aria-checked")
      check("Tuesday (unreadable) is NOT ticked Closed", tueClosed === "false", tueClosed)
      const tueTimes = [await hours.getByLabel("Tuesday opens", { exact: true }).inputValue(), await hours.getByLabel("Tuesday closes", { exact: true }).inputValue()]
      check("Tuesday reads 00:00 / 00:00", tueTimes[0] === "00:00" && tueTimes[1] === "00:00", tueTimes)
      check("Tuesday shows 'Open 24 hours'", (await tue.getByText("Open 24 hours", { exact: true }).count()) === 1, null)
      check("Tuesday shows no overnight hint", (await tue.getByText("Closes after midnight (next day)").count()) === 0, null)
      const amber = 'We could not read "lunchtime till late", so this day is open all day. Set the real times, or tick Closed.'
      check("Tuesday carries the ruled amber line", (await tue.getByText(amber, { exact: true }).count()) === 1, amber)
      const sunClosed = await hours.getByRole("checkbox", { name: "Closed on Sunday" }).getAttribute("aria-checked")
      check("Sunday (written 'Closed') stays ticked, with no amber line", sunClosed === "true" && (await sun.getByText(/We could not read/).count()) === 0, sunClosed)
      await tue.scrollIntoViewIfNeeded()
      await page.screenshot({ path: `${screens}/editor-all-day-prefill-1280.png` })
      await hours.getByLabel("Monday closes", { exact: true }).fill("17:00")
      await dialog.getByRole("button", { name: "Update Shop" }).click()
      const deadline = Date.now() + 15000
      while (!captured && Date.now() < deadline) await page.waitForTimeout(200)
      check("a save was captured (and not forwarded)", captured !== null, captured && Object.keys(captured))
      const sched = captured && captured.openingSchedule
      check("captured PUT: tue is the all-day window", JSON.stringify(sched && sched.tue) === JSON.stringify([{ open: "00:00", close: "00:00" }]), sched && sched.tue)
      check("captured PUT: mon is 10:00-17:00 and there is no sun key", JSON.stringify(sched && sched.mon) === JSON.stringify([{ open: "10:00", close: "17:00" }]) && !(sched && "sun" in sched), sched)
    }

    if (phase === "pause403") {
      const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } })
      const page = await ctx.newPage()
      await login(page)
      let stubbed = 0
      await page.route(/\/api\/v1\/shops\/[^/]+\/pause$/, async (route) => {
        if (route.request().method() !== "PUT") return route.fallback()
        stubbed++
        await route.fulfill({
          status: 403,
          contentType: "application/problem+json",
          body: JSON.stringify({ type: "https://jtoye.uk/errors/shop-access-denied", title: "Shop Access Denied", status: 403, detail: "Shop access denied", requiredRole: "STAFF" }),
        })
      })
      await page.goto(`${BASE}/dashboard/kitchen`, { waitUntil: "domcontentloaded" })
      const control = live(page).getByTestId("kds-pause-control")
      await control.waitFor({ state: "visible", timeout: 20000 })
      await page.waitForFunction(() => {
        const b = document.querySelector('body > div:not([hidden]) [data-testid="kds-pause-control"]')
        return b && !b.disabled
      }, null, { timeout: 20000 })
      check("control reads 'Pause orders' before the tap", (await control.textContent()).trim() === "Pause orders", (await control.textContent()).trim())
      await control.click()
      const menu = page.getByRole("menu")
      await menu.waitFor({ state: "visible", timeout: 10000 })
      await page.waitForTimeout(400)
      await menu.getByRole("menuitem", { name: "20 minutes" }).click()
      const overlay = page.getByTestId("kds-board-stopped")
      await overlay.waitFor({ state: "visible", timeout: 15000 })
      check("the stubbed PUT was the one refused", stubbed === 1, stubbed)
      const headline = (await overlay.getByRole("heading").first().textContent()).trim()
      check("K2 overlay shows the Access ended headline", headline === "Your access has ended: new orders are NOT showing", headline)
      await page.waitForTimeout(800)
      const referral = await page.getByText(/Ask a manager|can't pause orders/).count()
      check("no manager-referral or inline refusal text anywhere in the live tree", referral === 0, referral)
      const alerts = await live(page).getByTestId("kds-pause-control").locator("xpath=..").locator('[role="alert"]').count()
      check("the pause control renders no role=alert of its own", alerts === 0, alerts)
      await page.screenshot({ path: `${screens}/pause403-k2-access-ended-1280.png` })
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
