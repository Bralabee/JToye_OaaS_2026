// Issue #458 (items 1a, 2, 4) — browser proof for the footer persona gate and
// the /track empty state.
//
// Deliberately a `.mjs` script and NOT a `.spec.ts`, matching the
// cart-identity-boundary.verify.mjs / customer-realm-split.verify.mjs
// precedent: the docs-freshness gate counts `test()` blocks in
// `frontend/e2e/*.spec.ts`, and nothing here should move that number. It also
// needs one real Keycloak registration carried across several navigations in a
// single context, which the shared config's per-test isolation works against.
//
// Run (from the repo root, against a frontend serving THIS branch):
//   NODE_PATH=frontend/node_modules PLAYWRIGHT_BASE_URL=http://localhost:3100 \
//     node --env-file=.env frontend/e2e/track-operator-persona.verify.mjs
//
// The registered account is brand new, so it has ZERO orders — which is exactly
// the persona item 2 is about, and the one a seeded fixture would have hidden.
//
// SIX criteria. Three are the fix; three are controls that must hold BEFORE it
// as well, because a gate that hid the operator door from everybody would
// satisfy the fix half and be a worse regression than the bug:
//
//   C1  signed-in customer: no "For operators" in the footer of /shop         (fix)
//   C2  signed-in customer: none in the footer of their PROFILE, /shop/orders (fix)
//       — the page the report actually named
//   C3  signed-in customer with no orders on /track: an empty state, and NO
//       order-number field demanded                                           (fix)
//   C4  the SAME signed-in customer on /for-operators still sees the operator
//       column — the door is gated by PERSONA+SURFACE, not deleted         (control)
//   C5  after sign-out, the operator column is back on /shop               (control)
//   C6  the guest /track form still demands order number AND email         (control)
//
// Each criterion reads the rendered DOM inside the <footer>, not a prop and not
// a screenshot: the failure mode is "this persona can see this link", which a
// screenshot cannot assert on and a prop check would miss entirely.
//
// Secrets: the registration password comes from KC_SEED_USER_PASSWORD in .env
// and is never printed.

import { chromium } from "@playwright/test"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3100"
const PASSWORD = process.env.KC_SEED_USER_PASSWORD
const HEADLESS = process.env.HEADED !== "1"

const results = []
function check(id, name, cond, detail = "") {
  results.push({ id, name, ok: Boolean(cond) })
  const tail = detail ? `  [${detail}]` : ""
  console.log(`  ${cond ? "PASS" : "FAIL"}  ${id} ${name}${tail}`)
}

/** Everything the footer links to, as hrefs. Scoped to <footer> on purpose. */
async function footerHrefs(page) {
  await page.locator("footer").first().waitFor({ state: "attached", timeout: 20000 })
  return page.$$eval("footer a", (as) => as.map((a) => a.getAttribute("href")))
}

async function session(page) {
  const res = await page.request.get(`${BASE}/api/customer-auth/session`)
  if (res.status() !== 200) return { authenticated: false }
  try {
    return await res.json()
  } catch {
    return { authenticated: false }
  }
}

async function registerCustomer(page, email) {
  await page.goto(`${BASE}/shop/signin?next=${encodeURIComponent("/shop")}`, {
    waitUntil: "domcontentloaded",
  })
  const create = page.getByRole("button", { name: /create an account/i }).first()
  await create.waitFor({ state: "visible", timeout: 20000 })
  await Promise.all([
    page.waitForURL(/\/realms\//, { timeout: 30000 }),
    create.click(),
  ])
  await page.locator("#firstName").waitFor({ state: "visible", timeout: 20000 })
  await page.fill("#firstName", "Persona")
  await page.fill("#lastName", "Check")
  await page.fill("#email", email)
  await page.fill("#password", PASSWORD)
  await page.fill("#password-confirm", PASSWORD)
  await page.locator("input[type=submit], button[type=submit]").first().click()
  await page.waitForURL(
    (u) => u.href.startsWith(`${BASE}/shop`) && !u.href.includes("/auth/callback"),
    { timeout: 60000 }
  )
}

async function main() {
  if (!PASSWORD) {
    console.error("KC_SEED_USER_PASSWORD is not set — run with --env-file=.env")
    process.exit(2)
  }

  const browser = await chromium.launch({ headless: HEADLESS })
  const context = await browser.newContext()
  const page = await context.newPage()

  const email = `persona-458-${Date.now()}@example.com`
  console.log(`\nBASE=${BASE}  account=${email}\n`)

  // ---------- Baseline: anonymous, before anything is signed in ----------
  await page.goto(`${BASE}/shop`, { waitUntil: "domcontentloaded" })
  const anonShop = await footerHrefs(page)
  console.log(`  anon /shop footer: ${JSON.stringify(anonShop)}`)
  // Not a scored criterion — a starting condition. If the operator link were
  // already absent here, every "it is gone" result below would be meaningless.
  if (!anonShop.includes("/for-operators")) {
    console.error("  ABORT: the operator link is absent even for an anonymous visitor;")
    console.error("         nothing below could distinguish the gate from a broken page.")
    process.exit(3)
  }

  // ---------- Sign in (brand-new account => zero orders) ----------
  await registerCustomer(page, email)
  const sess = await session(page)
  if (!sess.authenticated) {
    console.error("  ABORT: registration did not yield a session; a signed-out page")
    console.error("         would pass C1-C3 for entirely the wrong reason.")
    process.exit(4)
  }
  console.log(`  session: authenticated=${sess.authenticated} sub=${(sess.profile?.sub || "").slice(0, 8)}…\n`)

  // ---------- C1: /shop ----------
  await page.goto(`${BASE}/shop`, { waitUntil: "domcontentloaded" })
  await page.waitForFunction(
    () => !Array.from(document.querySelectorAll("footer a")).some((a) => a.getAttribute("href") === "/for-operators"),
    null,
    { timeout: 15000 }
  ).catch(() => {})
  const c1 = await footerHrefs(page)
  check("C1", "no /for-operators in the footer of /shop", !c1.includes("/for-operators"), JSON.stringify(c1))

  // ---------- C2: the profile page the report named ----------
  await page.goto(`${BASE}/shop/orders`, { waitUntil: "domcontentloaded" })
  await page.waitForFunction(
    () => !Array.from(document.querySelectorAll("footer a")).some((a) => a.getAttribute("href") === "/for-operators"),
    null,
    { timeout: 15000 }
  ).catch(() => {})
  const c2 = await footerHrefs(page)
  check(
    "C2",
    "no /for-operators or /auth/signin in the footer of /shop/orders",
    !c2.includes("/for-operators") && !c2.includes("/auth/signin"),
    JSON.stringify(c2)
  )
  check("C2b", "the footer offers /shop/orders instead of /track", c2.includes("/shop/orders") && !c2.includes("/track"))

  // ---------- C3: /track with zero orders ----------
  await page.goto(`${BASE}/track`, { waitUntil: "domcontentloaded" })
  const emptyState = page.getByTestId("track-no-orders")
  let sawEmpty = false
  try {
    await emptyState.waitFor({ state: "visible", timeout: 20000 })
    sawEmpty = true
  } catch {
    sawEmpty = false
  }
  // The form must not merely be off-screen — `hidden` is the attribute the page
  // uses, so read it rather than trusting visibility heuristics.
  const formHidden = await page.$eval("form", (f) => f.hasAttribute("hidden")).catch(() => null)
  const emptyText = sawEmpty ? (await emptyState.innerText()).replace(/\s+/g, " ").trim() : ""
  check(
    "C3",
    "signed-in with zero orders gets an empty state, not an order-number form",
    sawEmpty && formHidden === true,
    `emptyState=${sawEmpty} formHidden=${formHidden} text="${emptyText.slice(0, 60)}…"`
  )

  // ---------- C4: the operator door is NOT deleted ----------
  await page.goto(`${BASE}/for-operators`, { waitUntil: "domcontentloaded" })
  // Wait for the session to resolve so this is not read off the first (guest)
  // render, which would pass even if the gate then wrongly hid the column.
  await page.waitForFunction(
    () => Array.from(document.querySelectorAll("header a, header span")).some((n) => /Persona|persona-458/.test(n.textContent || "")),
    null,
    { timeout: 15000 }
  ).catch(() => {})
  const c4 = await footerHrefs(page)
  check(
    "C4",
    "the SAME signed-in customer still sees the operator column ON /for-operators",
    c4.includes("/for-operators") && c4.includes("/auth/signin"),
    JSON.stringify(c4)
  )

  // ---------- C5: sign out, door returns ----------
  await context.clearCookies()
  await page.goto(`${BASE}/shop`, { waitUntil: "domcontentloaded" })
  const c5 = await footerHrefs(page)
  check(
    "C5",
    "after sign-out the operator column is back on /shop",
    c5.includes("/for-operators") && c5.includes("/auth/signin") && c5.includes("/track"),
    JSON.stringify(c5)
  )

  // ---------- C6: guest /track unchanged ----------
  await page.goto(`${BASE}/track`, { waitUntil: "domcontentloaded" })
  await page.locator("#orderNumber").waitFor({ state: "visible", timeout: 20000 })
  const guest = await page.evaluate(() => {
    const num = document.querySelector("#orderNumber")
    const mail = document.querySelector("#email")
    const form = document.querySelector("form")
    return {
      formHidden: form ? form.hasAttribute("hidden") : null,
      numRequired: num ? num.required : null,
      mailRequired: mail ? mail.required : null,
      mailPrefilled: mail ? mail.value : null,
      emptyStatePresent: Boolean(document.querySelector('[data-testid="track-no-orders"]')),
    }
  })
  check(
    "C6",
    "guest /track still demands order number AND email, with nothing pre-filled",
    guest.formHidden === false &&
      guest.numRequired === true &&
      guest.mailRequired === true &&
      guest.mailPrefilled === "" &&
      guest.emptyStatePresent === false,
    JSON.stringify(guest)
  )

  await browser.close()

  const failed = results.filter((r) => !r.ok)
  console.log(`\n  ${results.length - failed.length}/${results.length} passed`)
  if (failed.length > 0) {
    console.log(`  FAILED: ${failed.map((f) => f.id).join(", ")}`)
    process.exit(1)
  }
  console.log("  ALL PASS")
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
