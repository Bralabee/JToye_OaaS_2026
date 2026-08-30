/**
 * The customer session pill, in a real browser (plan 34-03, #202's own
 * acceptance list — uncovered until now).
 *
 * WHAT THIS EXISTS TO CATCH. `lib/customer-session-store.ts` is the single source
 * of "who is signed in" for the three public surfaces that show the pill. It is
 * built on the ASYNC `getCustomerSession()` (server truth) and never on the
 * synchronous `isLoggedIn()` localStorage marker, because the marker is
 * attacker-writable and goes stale. That reasoning lives in a docblock, and a
 * docblock cannot fail a build. This spec can: it PLANTS the marker before any
 * page script runs, with no session cookie, and requires the signed-OUT pill.
 * (T-34-03-01.)
 *
 * SELECTORS ARE ROLE-BASED, NOT TEXT SCANS. React's streaming staging buffer
 * briefly puts a duplicate copy of the server tree in the DOM and `textContent`
 * concatenates across it (traps #556 / #593). `getByRole` reads the
 * accessibility tree and is immune to both — and it is also what makes the
 * viewport handling below honest, since a `display:none` control is simply not
 * in that tree.
 *
 * NAVIGATION IS RELATIVE. `playwright.config.ts` is the only base-URL authority
 * (`scripts/check-e2e-baseurl-contract.sh`, #505); this file declares no
 * fallback of its own.
 *
 * WHY NO `@desktop-only` / `@mobile-only` TAG. PublicHeader's sign-in control
 * sits in a `hidden sm:flex` row on `/` and moves into the hamburger sheet at
 * the mobile viewport, while StorefrontNav's is visible at both. Rather than
 * excluding a project — which would leave one viewport unchecked — the helper
 * below asks the page which shape it is in. The config is explicit that a skip
 * must mean "nobody checked this" and must not also mean "not applicable here".
 */
import { test, expect, type BrowserContext, type Locator, type Page } from "@playwright/test"

/** The two keys `lib/customer-auth.ts` keeps as its synchronous UI marker. */
const MARKER_KEY = "jtoye-customer-logged-in"
const EXPIRES_KEY = "jtoye-customer-expires-at"

/**
 * A third key nothing in the app reads, writes or clears.
 *
 * It is the control that the init script actually RAN. The two marker keys
 * cannot serve that purpose: `getCustomerSession()` calls `clearMarker()` the
 * moment the server answers "not authenticated", so reading them back after the
 * assertions would be a race against the very code under test, and a missing
 * marker would be indistinguishable from an init script that never executed.
 */
const PLANTED_PROOF_KEY = "jtoye-e2e-session-pill-planted"

/**
 * Write a stale-but-unexpired marker into localStorage BEFORE any page script
 * runs, with no session cookie anywhere. This is precisely what
 * `isLoggedIn()` (lib/customer-auth.ts:136) reads and would answer `true` to.
 */
async function plantStaleMarker(context: BrowserContext): Promise<void> {
  await context.addInitScript(
    ([markerKey, expiresKey, proofKey]) => {
      try {
        localStorage.setItem(markerKey, "true")
        // An hour in the future, so the marker's own expiry check passes and the
        // only thing standing between it and a signed-in pill is server truth.
        localStorage.setItem(
          expiresKey,
          String(Math.floor(Date.now() / 1000) + 3600)
        )
        localStorage.setItem(proofKey, "yes")
      } catch {
        // Some origins refuse storage; the assertion on the proof key reports it.
      }
    },
    [MARKER_KEY, EXPIRES_KEY, PLANTED_PROOF_KEY]
  )
}

/**
 * Wait until the store has actually ASKED the server.
 *
 * Without this the absence assertions could run before any session resolution
 * and would pass on a build that trusts the marker a moment later — the exact
 * regression this spec exists to catch, reported as a pass. The store probes on
 * mount and again once a second for five seconds, so a late listener still
 * catches a poll rather than hanging.
 */
async function waitForSessionProbe(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) => r.url().includes("/api/customer-auth/session"),
    { timeout: 20_000 }
  )
}

/**
 * The signed-OUT affordance, wherever this viewport keeps it.
 *
 * Returns the control rather than asserting, so each block states its own
 * expectation. `getByRole` excludes `display:none`, so the desktop row's link
 * genuinely does not match at the mobile viewport — the hamburger branch is
 * reached because the control really has moved, not because a guess was made.
 */
async function signedOutControl(page: Page): Promise<Locator> {
  const direct = page.getByRole("link", { name: /^sign in$/i }).first()
  if (await direct.isVisible().catch(() => false)) return direct

  const menu = page.getByRole("button", { name: /open menu/i })
  await expect(
    menu,
    "no visible sign-in control and no menu button either — the page chrome did not render"
  ).toBeVisible()
  await menu.click()
  return page.getByRole("link", { name: /^sign in$/i }).first()
}

/** The controls that exist ONLY for a signed-in customer. */
function signedInControls(scope: Page | Locator) {
  return {
    myOrders: scope.getByRole("link", { name: /my orders/i }),
    signOut: scope.getByRole("button", { name: /sign out/i }),
  }
}

test.describe("customer session pill — server truth beats the local marker", () => {
  test("a stale local marker does not produce a signed-in pill", async ({
    page,
    context,
  }) => {
    await plantStaleMarker(context)

    await page.goto("/shop")
    await page.waitForLoadState("domcontentloaded")

    // PRESENCE CONTROL, first and mandatory. Every assertion after this is an
    // ABSENCE, and an empty, errored or 404 page satisfies all of them. This
    // landmark is rendered by app/shop/layout.tsx, so it is present whether or
    // not the shop directory itself loaded.
    const nav = page.getByRole("navigation", { name: "Storefront" })
    await expect(
      nav,
      "the storefront nav never rendered — anything asserted past this point " +
        "would be asserted over a page that has no session pill at all"
    ).toBeVisible({ timeout: 15_000 })

    // CONTROL: the init script really ran, so the marker really was in place
    // when the page booted.
    const planted = await page.evaluate(
      (key) => localStorage.getItem(key),
      PLANTED_PROOF_KEY
    )
    expect(planted, "the init script did not run, so nothing was planted").toBe(
      "yes"
    )

    await waitForSessionProbe(page)

    // The signed-out affordance is present...
    await expect(nav.getByRole("link", { name: /^sign in$/i })).toBeVisible()

    // ...and the signed-in ones are not. This is the browser-level statement of
    // the contract in use-customer-session.ts: server truth wins over the marker.
    const signedIn = signedInControls(nav)
    await expect(signedIn.myOrders).toHaveCount(0)
    await expect(signedIn.signOut).toHaveCount(0)
  })

  test("the pill is the same on the marketing header and the storefront nav", async ({
    page,
    context,
  }) => {
    await plantStaleMarker(context)

    // --- the marketing header (PublicHeader) -----------------------------------
    await page.goto("/")
    await page.waitForLoadState("domcontentloaded")

    // PRESENCE CONTROL for this surface: the banner landmark PublicHeader emits.
    const banner = page.getByRole("banner")
    await expect(
      banner.first(),
      "the marketing header never rendered — the absence assertions below would " +
        "be vacuous"
    ).toBeVisible({ timeout: 15_000 })

    await waitForSessionProbe(page)

    await expect(await signedOutControl(page)).toBeVisible()
    const marketingSignedIn = signedInControls(page)
    await expect(marketingSignedIn.myOrders).toHaveCount(0)
    await expect(marketingSignedIn.signOut).toHaveCount(0)

    // --- the storefront nav, SAME context ---------------------------------------
    // Same browser context means the same tab-scoped module state, so a build in
    // which the two surfaces read different stores (#457) would disagree here.
    await page.goto("/shop")
    await page.waitForLoadState("domcontentloaded")

    const nav = page.getByRole("navigation", { name: "Storefront" })
    await expect(nav, "the storefront nav never rendered").toBeVisible({
      timeout: 15_000,
    })

    await waitForSessionProbe(page)

    await expect(nav.getByRole("link", { name: /^sign in$/i })).toBeVisible()
    const storefrontSignedIn = signedInControls(nav)
    await expect(storefrontSignedIn.myOrders).toHaveCount(0)
    await expect(storefrontSignedIn.signOut).toHaveCount(0)
  })
})
