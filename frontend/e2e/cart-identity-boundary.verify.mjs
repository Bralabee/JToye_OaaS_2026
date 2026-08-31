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
// FIVE criteria. Three of them are REGRESSION GUARDS on behaviour that is
// correct before the fix, so this script is meant to be run on both sides of
// the change:
//
//   C1  after A signs out and B signs in, B does not see A's basket   (the fix)
//   C1c an anonymous render does not ERASE the owner stamp, so the next
//       registration inherits nothing                     (R-16, the fix)
//   C2  an anonymous basket IS carried forward into that same person's
//       sign-in                                     (what a naive fix breaks)
//   C3  the cross-shop guard still holds                       (control today)
//   C4  the post-order clear still works                       (control today)
//
// WHAT C1c COVERS THAT C1b CANNOT. C1b (in sharedBrowserFlow) seeds a basket
// owned by A into a browser where B is ALREADY signed in, so it exercises the
// READ boundary and nothing else. It never performs the step that does the
// damage: a signed-OUT render of the shop page, which re-persists the basket
// and — before R-16 — stamped it `owner: null`. A null owner is adoptable by
// anyone, so by the time B arrives there is no boundary left to enforce and
// C1b's own precondition (a payload still owned by A) no longer exists in the
// wild. C1c performs that render for real and then reads the stamp back.
//
// Every criterion carries its own fail arm, because each one has a shape that
// would otherwise pass for the wrong reason:
//   - C1 would pass if the sign-in simply failed, so B's subject id is read
//     back from the session endpoint and asserted DIFFERENT from A's.
//   - C1c would pass if the page never hydrated at all (no render, no write,
//     stamp trivially unchanged), so the seeded item is asserted to RENDER
//     first; and its consequence half would pass if B's registration failed,
//     so B's session is asserted authenticated with a sub distinct from A's.
//   - C2 would pass if we never actually signed in, so A's authenticated
//     session is asserted before the basket is looked at.
//   - C3 would pass if the cart page were always empty, so the same seed with
//     a MATCHING slug is asserted to render the item.
//   - C4 would pass if the basket were empty all along, so it is asserted
//     non-empty immediately before the order is placed; and the allergen
//     acknowledgement it must tick first is asserted TICKED, so a refused
//     submit reads as "the order was never placed" rather than as a basket
//     that failed to clear.
//
// Secrets: the registration password comes from KC_SEED_USER_PASSWORD in .env
// and is never printed. What IS logged: booleans, pass/fail lines, the
// generated test emails, and — named explicitly rather than left out of this
// list (IN-02) — the Keycloak SUBJECT IDS of the throwaway customers these arms
// register, printed by `describeStored` and by the `B.sub=` detail strings.
// Those users exist only for the run and live on a realm destroyed with it, so
// the risk is nil; the point is that a secrets claim which has quietly drifted
// is the kind that gets trusted later.

import { chromium } from "@playwright/test"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const PASSWORD = process.env.KC_SEED_USER_PASSWORD
const SHOP = process.env.E2E_SHOP_SLUG || "peckham-jollof-co"
const OTHER_SHOP = process.env.E2E_OTHER_SHOP_SLUG || "mama-ades-kitchen"
const HEADLESS = process.env.HEADED !== "1"

/**
 * How many `check()` calls a complete run must produce. Asserted in main() so a
 * run that executed FEWER than it declares exits VOID rather than printing a
 * proportion that looks like a pass. Update it deliberately when adding an arm.
 */
const EXPECTED_CHECKS = 18

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
  // The item-count line, located by an EXPLICIT testid.
  //
  // Two incidental couplings have already broken this, and the second was
  // caught in review rather than in the wild (WR-09). It was
  // `p.text-sm.text-slate-500`; PR #522's contrast pass moved the paragraph to
  // `text-slate-600`, after which the locator matched nothing, `.textContent()`
  // waited out its full 30s default and every arm reading a non-empty basket
  // THREW. The first repair selected any `<p>` whose whole text is "N items" —
  // and `components/storefront/cart-drawer.tsx` renders exactly that shape. It
  // does not collide today only because Radix `Sheet` unmounts closed content
  // and the drawer is portalled after `{children}`: two facts no test pins, so
  // a `forceMount` would silently redirect `.first()` to the drawer.
  //
  // A testid moves only when somebody means it to, which is the whole point.
  //
  // Bounded and non-throwing: an unreadable count degrades to -1, which fails
  // every `itemCount >= 1` fail arm CLOSED and is announced, rather than
  // exploding the arm or — worse — reading as an empty basket.
  const countText = await page
    .locator('[data-testid="cart-item-count"]')
    .first()
    .textContent({ timeout: 5000 })
    .catch(() => null)
  if (countText === null) {
    console.log("        [instrument] item-count line not found — count reported as -1")
  }
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
// C1c — R-16. A signed-OUT render must not erase the owner stamp, and the next
// person to register must not inherit the basket it protects.
// ---------------------------------------------------------------------------
async function anonymousDowngradeGuard(browser) {
  console.log("\nC1c — a signed-out render must not downgrade owner:A to owner:null (R-16)")
  const context = await browser.newContext()
  const page = await context.newPage()
  // A's identity is an OPAQUE Keycloak subject id, and the downgrade is a
  // signed-OUT render — so this half needs no Keycloak at all. An arbitrary
  // string is exactly the shape of the thing being preserved, and using one
  // keeps the arm cheap enough to run on both sides of the change.
  const FAKE_A = "sub-absent-customer-a"
  const emailB = `cust-r16-${Date.now()}@example.com`
  console.log(`  customer B: ${emailB}`)

  try {
    // Seed from /shop — a provider-FREE page. The slug layout mounts
    // CartProvider, which hydrates and writes its own payload back within a few
    // hundred ms, so seeding there overwrites the seed. Measured; see the same
    // note in crossShopGuard.
    await page.goto(`${BASE}/shop`, { waitUntil: "domcontentloaded" })
    await page.evaluate(
      ([k, payload]) => window.localStorage.setItem(k, payload),
      [
        cartKey(SHOP),
        // `_seed` is a marker the APP never writes: CartProvider's serialize
        // emits exactly { shopSlug, owner, items }. Its DISAPPEARANCE is
        // therefore proof that the provider re-persisted this slot — see the
        // wait below.
        JSON.stringify({
          shopSlug: SHOP,
          owner: FAKE_A,
          items: [seedItem("owned-by-a")],
          _seed: true,
        }),
      ]
    )

    // THE DOWNGRADING RENDER. This navigation is the whole defect: nobody is
    // signed in, the provider hydrates, and its write effect re-persists the
    // basket. Everything below reads the result of THIS.
    await page.goto(`${BASE}/shop/${SHOP}`, { waitUntil: "domcontentloaded" })

    // WAIT FOR THE WRITE, NOT FOR THE CLOCK (WR-10). This was
    // `waitForTimeout(1500)` — a sleep, not a condition. On a loaded runner the
    // write effect may not have run when it expired, in which case C1c.1 ("the
    // stamp SURVIVES") would pass TRIVIALLY: nothing wrote, so nothing could
    // have downgraded it.
    //
    // The condition has to distinguish "the provider wrote" from "my seed is
    // still sitting there", which is why it cannot be a check on shopSlug or on
    // the items — the seed satisfies both. `_seed` is a key the app never emits,
    // so its removal happens if and only if serialize ran.
    await page.waitForFunction(
      (k) => {
        const raw = window.localStorage.getItem(k)
        if (!raw) return false
        try {
          return !("_seed" in JSON.parse(raw))
        } catch {
          return false
        }
      },
      cartKey(SHOP),
      { timeout: 15000 }
    )

    // FAIL ARM FIRST: without this, "the owner is unchanged" is satisfied by a
    // page that never hydrated and therefore never wrote anything.
    const seeded = await cartPageState(page, SHOP)
    check(
      "C1c.0",
      "the seeded basket DOES render while signed out (fail arm: proves the write effect ran)",
      !seeded.empty && seeded.titles.some((t) => t.includes("owned-by-a")),
      `empty=${seeded.empty} items=${seeded.itemCount} titles=${JSON.stringify(seeded.titles)}`
    )

    const afterAnon = await storedCart(page, SHOP)
    console.log(`        stored after the signed-out render: ${describeStored(afterAnon)}`)
    check(
      "C1c.1",
      "the owner stamp SURVIVES a signed-out render (only sign-out removes it)",
      afterAnon.payload !== null && afterAnon.payload.owner === FAKE_A,
      `owner=${JSON.stringify(afterAnon.payload?.owner)} expected=${JSON.stringify(FAKE_A)}`
    )

    // --- A BRAND-NEW CUSTOMER REGISTERS on this browser profile.
    await registerCustomer(page, emailB, `/shop/${SHOP}`)
    const sessB = await session(page)
    check(
      "C1c.2",
      "customer B really registered and is a different identity from A (fail arm for C1c)",
      sessB.authenticated === true &&
        typeof sessB?.profile?.sub === "string" &&
        sessB.profile.sub.length > 0 &&
        sessB.profile.sub !== FAKE_A,
      `authenticated=${sessB.authenticated} email=${sessB?.profile?.email ?? "-"} distinct=${sessB?.profile?.sub !== FAKE_A}`
    )

    const asB = await cartPageState(page, SHOP)
    check(
      "C1c",
      "a newly registered customer does NOT inherit the previous account's basket",
      asB.empty && asB.itemCount === 0 && !asB.titles.some((t) => t.includes("owned-by-a")),
      `empty=${asB.empty} items=${asB.itemCount} titles=${JSON.stringify(asB.titles)}`
    )

    // REVERSE-LEAK GUARD. Preservation must not become "the first owner wins
    // forever": a stamp still reading A here would mean every item B adds from
    // now on is stored under A's name — the same leak, running backwards.
    const asBStored = await storedCart(page, SHOP)
    console.log(`        stored after B registered: ${describeStored(asBStored)} (B.sub=${sessB?.profile?.sub ?? "-"})`)
    check(
      "C1c.3",
      "the signed-in writer TAKES the slot, so B's items cannot leak back to A",
      asBStored.payload !== null &&
        typeof sessB?.profile?.sub === "string" &&
        asBStored.payload.owner === sessB.profile.sub,
      `owner=${JSON.stringify(asBStored.payload?.owner)} B.sub=${sessB?.profile?.sub ?? "-"}`
    )
  } catch (err) {
    const msg = err && err.message ? err.message : String(err)
    console.log(`  FAIL  C1c threw: ${msg}`)
    results.push({ id: "C1c", name: "exception", ok: false })
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

    // The pre-submit allergen acknowledgement (Phase 31 / D-02). Added long
    // after this script was written, and the submit handler REFUSES before any
    // network call while it is unticked — so C4 sat waiting 45s for an "Order
    // confirmed" heading that was never coming. Asserted rather than merely
    // clicked: a silent no-op here would put the timeout back, and a timeout
    // says "the basket did not clear" when the truth is "the order was never
    // placed". Radix renders it as role=checkbox, not a native input.
    const ack = page
      .locator('[data-testid="allergen-ack-row"]')
      .getByRole("checkbox")
      .first()
    await ack.waitFor({ state: "visible", timeout: 20000 })
    await ack.click()
    check(
      "C4.1",
      "the allergen acknowledgement is ticked (fail arm: an unticked box refuses the order)",
      (await ack.getAttribute("aria-checked")) === "true",
      `aria-checked=${await ack.getAttribute("aria-checked")}`
    )

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
    await anonymousDowngradeGuard(browser)
    await sharedBrowserFlow(browser)
  } finally {
    await browser.close()
  }

  // "Found nothing" is never "clean" (IN-01). With no floor, an empty `results`
  // prints `0/0 checks passed` and exits 0 — the shape check-e2e-skip-budget.sh
  // exits 2/VOID for. Every arm currently pushes a failing result from its
  // catch, so an empty `results` is hard to reach today; a future arm that
  // returns early would reopen it silently. The floor is the arm count this
  // script declares, so DELETING an arm is also caught, not just an empty run.
  if (results.length < EXPECTED_CHECKS) {
    console.log(
      `\nVOID: expected at least ${EXPECTED_CHECKS} checks, got ${results.length} — ` +
        `a run that executed less than it declares is not a pass`
    )
    process.exit(2)
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
