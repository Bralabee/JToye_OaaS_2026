// Issue #459 — the basket must not cross a CUSTOMER boundary, and must still
// cross an anonymous -> signed-in one.
//
// Deliberately a `.mjs` script and NOT a `.spec.ts`, matching the
// customer-realm-split.verify.mjs precedent: the docs-freshness gate counts
// `test()` blocks in `frontend/e2e/*.spec.ts`, and nothing here should move
// that number. It also needs to drive TWO real Keycloak registrations in one
// browser context, which is a shape the shared Playwright config's
// per-test isolation actively works against.
//
// Run (from the repo root, against the live compose stack):
//   NODE_PATH=frontend/node_modules PLAYWRIGHT_BASE_URL=http://localhost:3000 \
//     node --env-file=.env frontend/e2e/cart-identity-boundary.verify.mjs
//
// FOUR criteria. Two of them are REGRESSION GUARDS on behaviour that is correct
// before the fix, so this script is meant to be run on both sides of the change:
//
//   C1  after A signs out and B signs in, B does not see A's basket   (the fix)
//   C2  an anonymous basket IS carried forward into that same person's
//       sign-in                                     (what a naive fix breaks)
//   C3  the cross-shop guard still holds                       (control today)
//   C4  the post-order clear still works                       (control today)
//
// Every criterion carries its own fail arm, because each one has a shape that
// would otherwise pass for the wrong reason:
//   - C1 would pass if the sign-in simply failed, so B's subject id is read
//     back from the session endpoint and asserted DIFFERENT from A's.
//   - C2 would pass if we never actually signed in, so A's authenticated
//     session is asserted before the basket is looked at.
//   - C3 would pass if the cart page were always empty, so the same seed with
//     a MATCHING slug is asserted to render the item.
//   - C4 would pass if the basket were empty all along, so it is asserted
//     non-empty immediately before the order is placed.
//
// Secrets: the registration password comes from KC_SEED_USER_PASSWORD in .env
// and is never printed. Only booleans, pass/fail lines and generated test
// emails (not secret) are logged.

import { chromium } from "@playwright/test"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const PASSWORD = process.env.KC_SEED_USER_PASSWORD
const SHOP = process.env.E2E_SHOP_SLUG || "peckham-jollof-co"
const OTHER_SHOP = process.env.E2E_OTHER_SHOP_SLUG || "mama-ades-kitchen"
const HEADLESS = process.env.HEADED !== "1"

const results = []
function check(id, name, cond, detail = "") {
  results.push({ id, name, ok: Boolean(cond) })
  const tail = detail ? `  [${detail}]` : ""
  console.log(`  ${cond ? "PASS" : "FAIL"}  ${id} ${name}${tail}`)
}

const cartKey = (slug) => `jtoye-cart-${slug}`

function seedItem(id = "seed-1") {
  return {
    productId: id,
    title: `Seeded ${id}`,
    pricePennies: 700,
    quantity: 1,
    imageUrl: null,
    category: null,
  }
}

/**
 * What the cart PAGE renders — the user-visible answer, not storage.
 *
 * Deliberately waits for the NON-empty heading and only concludes "empty" when
 * that wait expires, rather than racing the two headings. The page paints the
 * empty state first (the provider hydrates from localStorage in an effect), so
 * a race resolves to "empty" purely on timing — measured, and it turned a
 * regression guard's own fail arm red while the product was fine.
 *
 * The bias is the safe direction for this script: every assertion that MATTERS
 * here (C1, C3) asserts EMPTY, and giving the full state the maximum chance to
 * appear can only make those harder to pass, never easier.
 */
async function cartPageState(page, slug) {
  await page.goto(`${BASE}/shop/${slug}/cart`, { waitUntil: "domcontentloaded" })
  const empty = page.getByRole("heading", { name: /your basket is empty/i })
  const full = page.getByRole("heading", { name: /^your basket$/i })
  const hydrated = await full
    .waitFor({ state: "visible", timeout: 8000 })
    .then(() => true)
    .catch(() => false)
  if (!hydrated) {
    await empty.waitFor({ state: "visible", timeout: 10000 })
    return { empty: true, itemCount: 0, titles: [] }
  }
  const titles = await page.locator("article h3, h3.text-sm").allTextContents()
  const countText = await page.locator("p.text-sm.text-slate-500").first().textContent()
  return {
    empty: false,
    itemCount: Number((countText || "").match(/(\d+)\s+item/)?.[1] ?? -1),
    titles: titles.map((t) => t.trim()).filter(Boolean),
  }
}

/**
 * The raw stored payload, for evidence rather than for the assertion.
 *
 * Reports the ORIGIN it read from, because it is trivially easy to read this on
 * the wrong one. Measured while building this script: after a sign-out the
 * browser is left sitting on the Keycloak origin (see the note in
 * sharedBrowserFlow), where `localStorage` is Keycloak's and reads empty — which
 * looks exactly like "the app cleared the basket" and is not.
 */
async function storedCart(page, slug) {
  const out = await page.evaluate((k) => {
    try {
      const raw = window.localStorage.getItem(k)
      return { origin: location.origin, raw }
    } catch (e) {
      return { origin: location.origin, raw: null, err: String(e) }
    }
  }, cartKey(slug))
  if (out.origin !== new URL(BASE).origin) {
    return { wrongOrigin: out.origin, payload: null }
  }
  return { wrongOrigin: null, payload: out.raw ? JSON.parse(out.raw) : null }
}

function describeStored(s) {
  if (s.wrongOrigin) return `<UNREADABLE: page is on ${s.wrongOrigin}>`
  if (s.payload === null) return "<key absent>"
  const owner = "owner" in s.payload ? JSON.stringify(s.payload.owner) : "<field absent>"
  return `${(s.payload.items || []).length} item(s), owner=${owner}`
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

/** Register a brand-new customer through the real Keycloak registration page. */
async function registerCustomer(page, email, returnTo) {
  await page.goto(`${BASE}/shop/signin?next=${encodeURIComponent(returnTo)}`, {
    waitUntil: "domcontentloaded",
  })
  const create = page.getByRole("button", { name: /create an account/i }).first()
  await create.waitFor({ state: "visible", timeout: 20000 })
  await Promise.all([
    page.waitForURL(/\/realms\//, { timeout: 30000 }),
    create.click(),
  ])
  await page.locator("#firstName").waitFor({ state: "visible", timeout: 20000 })
  await page.fill("#firstName", "Shared")
  await page.fill("#lastName", "Device")
  await page.fill("#email", email)
  await page.fill("#password", PASSWORD)
  await page.fill("#password-confirm", PASSWORD)
  await page.locator("input[type=submit], button[type=submit]").first().click()
  try {
    await page.waitForURL(
      (u) => u.href.startsWith(`${BASE}/shop`) && !u.href.includes("/auth/callback"),
      { timeout: 60000 }
    )
  } catch (err) {
    // Say WHY, not just "timed out": a Keycloak field error looks identical to
    // a hung page from the outside, and the difference is the whole diagnosis.
    const kcError = await page
      .locator("#input-error, .alert-error, #kc-error-message, .kc-feedback-text")
      .allTextContents()
      .catch(() => [])
    console.log(`        registration stalled at ${page.url().split("?")[0]}`)
    console.log(`        keycloak said: ${JSON.stringify(kcError)}`)
    throw err
  }
}

async function addFirstProduct(page, slug) {
  await page.goto(`${BASE}/shop/${slug}`, { waitUntil: "domcontentloaded" })
  const add = page.getByRole("button", { name: /^add$/i }).first()
  await add.waitFor({ state: "visible", timeout: 30000 })
  await add.click()
  // The quantity stepper replaces the Add button once the item is in.
  await page.waitForTimeout(500)
}

// ---------------------------------------------------------------------------
// C2 + C1 — one browser, one profile, two people. The reported scenario.
// ---------------------------------------------------------------------------
async function sharedBrowserFlow(browser) {
  console.log("\nC2/C1 — anonymous carry-forward, then A signs out and B signs in")
  const context = await browser.newContext()
  const page = await context.newPage()
  const emailA = `cust-a-${Date.now()}@example.com`
  const emailB = `cust-b-${Date.now()}@example.com`
  console.log(`  customer A: ${emailA}`)
  console.log(`  customer B: ${emailB}`)

  try {
    // --- ANONYMOUS: build a basket before signing in at all.
    await addFirstProduct(page, SHOP)
    const anon = await cartPageState(page, SHOP)
    const anonStored = await storedCart(page, SHOP)
    check(
      "C2.0",
      "anonymous basket is non-empty before any sign-in (fail arm for C2)",
      !anon.empty && anon.itemCount >= 1,
      `items=${anon.itemCount} titles=${JSON.stringify(anon.titles)}`
    )
    console.log(`        stored while anonymous: ${describeStored(anonStored)}`)
    const anonTitles = anon.titles

    // --- A SIGNS IN. Same person who was just browsing: the basket must follow.
    await registerCustomer(page, emailA, `/shop/${SHOP}`)
    const sessA = await session(page)
    check(
      "C2.1",
      "customer A really is signed in (fail arm: an empty-handed sign-in would make C2 vacuous)",
      sessA.authenticated === true && typeof sessA?.profile?.sub === "string" && sessA.profile.sub.length > 0,
      `authenticated=${sessA.authenticated} email=${sessA?.profile?.email ?? "-"}`
    )
    const afterSignIn = await cartPageState(page, SHOP)
    const storedAsA = await storedCart(page, SHOP)
    check(
      "C2",
      "an anonymous basket IS carried forward through that same person's sign-in",
      !afterSignIn.empty &&
        afterSignIn.itemCount >= 1 &&
        JSON.stringify(afterSignIn.titles) === JSON.stringify(anonTitles),
      `items=${afterSignIn.itemCount} titles=${JSON.stringify(afterSignIn.titles)}`
    )
    console.log(
      `        stored after A signed in: ${describeStored(storedAsA)} (A.sub=${sessA?.profile?.sub ?? "-"})`
    )

    // --- A SIGNS OUT through the real nav control.
    await page.goto(`${BASE}/shop/${SHOP}`, { waitUntil: "domcontentloaded" })
    const signOut = page.getByTitle("Sign out").first()
    await signOut.waitFor({ state: "visible", timeout: 30000 })
    await signOut.click()
    await page.waitForTimeout(3000)
    // ADJACENT DEFECT, recorded rather than worked around silently: the
    // end-session URL is built from `req.nextUrl.origin`, which inside the
    // container is the BIND address — `post_logout_redirect_uri=http://0.0.0.0:3000/shop`.
    // Keycloak refuses it, so the shopper is left on a Keycloak error page and
    // the browser never returns to the app on its own. Not this issue's bug; it
    // is logged here because it also means storage must be read after an
    // explicit navigation back, not wherever the sign-out happened to land.
    console.log(`        [adjacent] URL after sign-out: ${page.url().split("?")[0]}`)
    await page.goto(`${BASE}/shop/${SHOP}`, { waitUntil: "domcontentloaded" })
    await page.waitForTimeout(1000)
    const sessOut = await session(page)
    check(
      "C1.0",
      "customer A is signed OUT (fail arm: a failed sign-out would make C1 meaningless)",
      sessOut.authenticated !== true,
      `authenticated=${sessOut.authenticated}`
    )
    const storedAfterLogout = await storedCart(page, SHOP)
    console.log(`        stored after A's sign-out: ${describeStored(storedAfterLogout)}`)

    // The app session is provably dead (C1.0, just above). The IdP's SSO cookie
    // survives it because of the adjacent defect logged above, and would sign B
    // straight back in as A. Clearing cookies ends it. This CANNOT flatter the
    // result: the basket lives in localStorage, which is untouched here — the
    // stored payload printed above is the same one C1 is about to read.
    await context.clearCookies()

    // --- B SIGNS IN on the same browser profile.
    await registerCustomer(page, emailB, `/shop/${SHOP}`)
    const sessB = await session(page)
    check(
      "C1.1",
      "customer B is signed in AND is a different identity from A (fail arm for C1)",
      sessB.authenticated === true &&
        typeof sessB?.profile?.sub === "string" &&
        sessB.profile.sub.length > 0 &&
        sessB.profile.sub !== sessA?.profile?.sub,
      `B.email=${sessB?.profile?.email ?? "-"} distinct=${sessB?.profile?.sub !== sessA?.profile?.sub}`
    )
    const asB = await cartPageState(page, SHOP)
    check(
      "C1",
      "customer B does NOT inherit customer A's basket",
      asB.empty && asB.itemCount === 0,
      `empty=${asB.empty} items=${asB.itemCount} titles=${JSON.stringify(asB.titles)}`
    )

    // --- C1b: the SECOND mechanism, on its own.
    // C1 above is satisfied by the sign-out clear, so it says nothing about the
    // identity binding underneath. This is the sign-out that never happened —
    // a closed tab, a cleared cookie, a refresh the IdP declined — reconstructed
    // by putting a basket owned by A back on disk while B is signed in.
    const subA = sessA?.profile?.sub
    const subB = sessB?.profile?.sub

    // FAIL ARM FIRST: the same seed owned by B must render, or "empty" below
    // could just mean "seeding does not work".
    await page.goto(`${BASE}/shop`, { waitUntil: "domcontentloaded" })
    await page.evaluate(
      ([k, payload]) => window.localStorage.setItem(k, payload),
      [cartKey(SHOP), JSON.stringify({ shopSlug: SHOP, owner: subB, items: [seedItem("owned-by-b")] })]
    )
    const ownB = await cartPageState(page, SHOP)
    check(
      "C1b.0",
      "a basket owned by B DOES render for B (fail arm for C1b)",
      !ownB.empty && ownB.titles.some((t) => t.includes("owned-by-b")),
      `empty=${ownB.empty} titles=${JSON.stringify(ownB.titles)}`
    )

    await page.goto(`${BASE}/shop`, { waitUntil: "domcontentloaded" })
    await page.evaluate(
      ([k, payload]) => window.localStorage.setItem(k, payload),
      [cartKey(SHOP), JSON.stringify({ shopSlug: SHOP, owner: subA, items: [seedItem("owned-by-a")] })]
    )
    const ownA = await cartPageState(page, SHOP)
    check(
      "C1b",
      "a basket still owned by A is rejected for B even with no sign-out in between",
      ownA.empty && !ownA.titles.some((t) => t.includes("owned-by-a")),
      `empty=${ownA.empty} titles=${JSON.stringify(ownA.titles)}`
    )
  } catch (err) {
    const msg = err && err.message ? err.message : String(err)
    console.log(`  FAIL  C2/C1 threw: ${msg}`)
    results.push({ id: "C2/C1", name: "exception", ok: false })
  } finally {
    await context.close()
  }
}

// ---------------------------------------------------------------------------
// C3 — the cross-shop guard. Correct today; this is a regression guard.
// ---------------------------------------------------------------------------
async function crossShopGuard(browser) {
  console.log("\nC3 — a payload stamped with another shop's slug is rejected")
  const context = await browser.newContext()
  const page = await context.newPage()
  try {
    // Seed from /shop, NOT /shop/[slug]: the slug layout mounts the CartProvider,
    // which hydrates and then writes its own payload back over any seed within a
    // few hundred ms. Measured — the first version of this arm seeded on the
    // shop page and read an empty cart, which read as a passing guard.
    await page.goto(`${BASE}/shop`, { waitUntil: "domcontentloaded" })

    // FAIL ARM FIRST: a MATCHING payload must render, or "empty" proves nothing.
    await page.evaluate(
      ([k, payload]) => window.localStorage.setItem(k, payload),
      [cartKey(SHOP), JSON.stringify({ shopSlug: SHOP, items: [seedItem("match-1")] })]
    )
    const matching = await cartPageState(page, SHOP)
    check(
      "C3.0",
      "a MATCHING-slug payload DOES render (fail arm for C3)",
      !matching.empty && matching.titles.some((t) => t.includes("match-1")),
      `empty=${matching.empty} titles=${JSON.stringify(matching.titles)}`
    )

    // THE GUARD: same key, payload claiming a different shop. Re-seed from a
    // provider-free page for the same reason as above.
    await page.goto(`${BASE}/shop`, { waitUntil: "domcontentloaded" })
    await page.evaluate(
      ([k, payload]) => window.localStorage.setItem(k, payload),
      [cartKey(SHOP), JSON.stringify({ shopSlug: OTHER_SHOP, items: [seedItem("wrong-shop-1")] })]
    )
    const mismatched = await cartPageState(page, SHOP)
    check(
      "C3",
      "a MISMATCHED-slug payload is rejected and no other shop's items appear",
      mismatched.empty && !mismatched.titles.some((t) => t.includes("wrong-shop-1")),
      `empty=${mismatched.empty} titles=${JSON.stringify(mismatched.titles)}`
    )
  } catch (err) {
    const msg = err && err.message ? err.message : String(err)
    console.log(`  FAIL  C3 threw: ${msg}`)
    results.push({ id: "C3", name: "exception", ok: false })
  } finally {
    await context.close()
  }
}

// ---------------------------------------------------------------------------
// C4 — the post-order clear. Correct today; this is a regression guard.
// ---------------------------------------------------------------------------
async function postOrderClear(browser) {
  console.log("\nC4 — placing an order still empties the basket")
  const context = await browser.newContext()
  const page = await context.newPage()
  try {
    // Enough items to clear the shop's minimum order.
    await page.goto(`${BASE}/shop/${SHOP}`, { waitUntil: "domcontentloaded" })
    const adds = page.getByRole("button", { name: /^add$/i })
    await adds.first().waitFor({ state: "visible", timeout: 30000 })
    const addCount = Math.min(await adds.count(), 4)
    for (let i = 0; i < addCount; i++) {
      // Each click swaps that card's Add for a stepper, so always take the first.
      await page.getByRole("button", { name: /^add$/i }).first().click()
      await page.waitForTimeout(300)
    }
    const before = await cartPageState(page, SHOP)
    check(
      "C4.0",
      "basket is non-empty immediately before the order (fail arm for C4)",
      !before.empty && before.itemCount >= 1,
      `items=${before.itemCount}`
    )

    await page.goto(`${BASE}/shop/${SHOP}/checkout`, { waitUntil: "domcontentloaded" })
    // Collection needs no delivery address.
    const collection = page.getByRole("button", { name: /^collection$/i }).first()
    await collection.waitFor({ state: "visible", timeout: 20000 })
    await collection.click()
    await page.locator("#name").fill("Post Order")
    await page.locator("#email").fill(`order-${Date.now()}@example.com`)
    await page.locator("#phone").fill("07700900123")
    const submit = page.getByRole("button", { name: /place order/i }).first()
    await submit.waitFor({ state: "visible", timeout: 20000 })
    await submit.click()

    const confirmed = page.getByRole("heading", { name: /order confirmed/i }).first()
    await confirmed.waitFor({ state: "visible", timeout: 45000 })
    const after = await cartPageState(page, SHOP)
    check(
      "C4",
      "the basket is emptied once the order is placed",
      after.empty && after.itemCount === 0,
      `empty=${after.empty} items=${after.itemCount}`
    )
  } catch (err) {
    const msg = err && err.message ? err.message : String(err)
    console.log(`  FAIL  C4 threw: ${msg}`)
    results.push({ id: "C4", name: "exception", ok: false })
  } finally {
    await context.close()
  }
}

async function main() {
  console.log(`Issue #459 — cart identity boundary, against ${BASE}`)
  console.log(`shop=${SHOP} other=${OTHER_SHOP}`)
  if (!PASSWORD) {
    console.log("  FAIL  KC_SEED_USER_PASSWORD is not set — run with `node --env-file=.env`")
    process.exit(2)
  }

  const browser = await chromium.launch({ headless: HEADLESS })
  try {
    await crossShopGuard(browser)
    await postOrderClear(browser)
    await sharedBrowserFlow(browser)
  } finally {
    await browser.close()
  }

  const failed = results.filter((r) => !r.ok)
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
  if (failed.length > 0) {
    console.log(`FAILED: ${failed.map((f) => f.id).join(", ")}`)
    process.exit(1)
  }
  console.log("ALL PASS")
}

main().catch((err) => {
  console.error(err)
  process.exit(2)
})
