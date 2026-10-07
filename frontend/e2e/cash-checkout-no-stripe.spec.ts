/**
 * #793 (phase 31.1, PGC-793): a cash checkout loads no Stripe code and sets no Stripe cookie.
 *
 * WHY THIS IS A BROWSER TEST, AND WHY IT STUBS NOTHING. The claim is a NETWORK-level one: that no
 * request leaves this page for Stripe and no Stripe cookie lands in the jar. The defect was an
 * IMPORT side effect — `import { loadStripe } from "@stripe/stripe-js"` (the package default
 * entry) schedules the injection of js.stripe.com at module load, so every checkout page, cash or
 * card, fetched Stripe's script, which then set `__stripe_mid` (1 year) / `__stripe_sid` and
 * called m.stripe.network while the page said no payment is taken online. A jest test can prove
 * the page no longer imports that entry (`stripe-lazy-load.test.tsx`); only a real browser
 * against the real stack can prove that NOTHING ELSE on the route does. So: no `context.route`,
 * no fixtures — the compose stack, a seeded shop, a real order.
 *
 * THE NON-VACUITY CONTROL. "Zero Stripe requests" is equally true of a listener that recorded
 * nothing. The same listener must therefore also have seen the requests this walk is known to
 * make — at least one to the core API host, and the order POST itself — or the zero proves
 * nothing and the test fails saying so.
 *
 * WHAT IT NEEDS. A cash-only shop: `PublicShop.acceptsCardPayments === false`, which the server
 * derives from `paymentService.isConfigured()`. That is read from the shop response this page
 * itself fetched and asserted, so a run against a card-configured stack fails with that reason
 * rather than passing over a checkout that was never a cash one.
 *
 * WHEN IT IS RUN. Listed (not run) by plan 31.1-04. Its RED run (pre-rebuild runtime, which still
 * imports the default entry) and GREEN run (rebuilt runtime) belong to plan 31.1-30, because no
 * running image carries this branch's frontend before that plan's full rebuild, and a run against
 * a stale image proves nothing about the branch (CLAUDE.md, runtime parity).
 *
 * Run: npx playwright test e2e/cash-checkout-no-stripe.spec.ts
 */

import { test, expect, type Request } from "@playwright/test"

// The deterministic seeded shop the storefront suite also uses (DemoDataSeeder, UIX-05).
const SHOP_SLUG = "mama-ades-kitchen"

// The core API as the BROWSER sees it (NEXT_PUBLIC_API_URL; compose publishes core-java on
// 9090). Same env var and default as storefront-flows.spec.ts. A separate service from the
// frontend, so not a base-URL fallback (scripts/check-e2e-baseurl-contract.sh scope note).
const API_HOST = new URL(process.env.PLAYWRIGHT_API_URL || "http://localhost:9090").host

// The hosts #793 names (the script, and the fraud-signal iframe and beacon). The check below is
// STRICTER than this list: any host under stripe.com or stripe.network counts, so a Stripe call
// to a host the issue did not name (r.stripe.com, an API host) cannot slip past it.
const STRIPE_HOSTS = ["js.stripe.com", "m.stripe.network", "m.stripe.com"]
const STRIPE_DOMAINS = ["stripe.com", "stripe.network"]
const STRIPE_COOKIES = ["__stripe_mid", "__stripe_sid"]

function hostOf(url: string): string {
  try {
    return new URL(url).host
  } catch {
    return ""
  }
}

function isStripeHost(host: string): boolean {
  const bare = host.replace(/:\d+$/, "")
  return [...STRIPE_HOSTS, ...STRIPE_DOMAINS].some((h) => bare === h || bare.endsWith(`.${h}`))
}

function isStripeCookieDomain(domain: string): boolean {
  const bare = domain.replace(/^\./, "")
  return STRIPE_DOMAINS.some((d) => bare === d || bare.endsWith(`.${d}`))
}

test.describe("#793 cash checkout loads no Stripe", () => {
  test("a cash order walked to its confirmation makes zero Stripe requests and sets no Stripe cookie", async ({
    page,
    context,
  }, testInfo) => {
    // Record from BEFORE the first navigation, at CONTEXT level, so requests from iframes and
    // any page the walk opens are seen too.
    const requests: Array<{ method: string; url: string }> = []
    context.on("request", (req: Request) => {
      requests.push({ method: req.method(), url: req.url() })
    })

    await page.goto(`/shop/${SHOP_SLUG}`)
    await page.waitForLoadState("domcontentloaded")

    // Add items until the basket clears the shop minimum (the seeded shop has a £10 minimum and
    // the first item is £8.99). Bounded, so a broken Add button fails fast; see the same loop
    // and its history in storefront-flows.spec.ts.
    const addButtons = page.locator('button:has-text("Add")')
    await expect(addButtons.first()).toBeVisible({ timeout: 15_000 })
    const MAX_ADDS = 6
    let adds = 0
    while (adds < MAX_ADDS) {
      await addButtons.first().click()
      adds++
      await page.waitForTimeout(400)
      if ((await page.getByText(/Minimum order/i).count()) === 0 && adds >= 2) break
    }
    expect(adds, "never cleared the shop minimum within MAX_ADDS items").toBeLessThan(MAX_ADDS)

    await page.goto(`/shop/${SHOP_SLUG}/cart`)
    await page.waitForLoadState("domcontentloaded")

    // The shop response the CHECKOUT page fetches client-side (checkout/page.tsx, the fee
    // preview) is the authority on whether this is a cash-only shop. Armed just before the
    // navigation that triggers it, so its timeout measures that fetch and not the walk above.
    const shopResponse = page.waitForResponse(
      (res) =>
        res.request().method() === "GET" &&
        new URL(res.url()).pathname.endsWith(`/public/shops/${SHOP_SLUG}`) &&
        res.ok(),
      { timeout: 20_000 }
    )
    await page.getByText("Proceed to checkout").click()
    await page.waitForLoadState("domcontentloaded")

    const shop = (await (await shopResponse).json()) as { acceptsCardPayments?: boolean }
    expect(
      shop.acceptsCardPayments,
      "this spec needs a CASH-ONLY shop (acceptsCardPayments === false); the stack under test " +
        "has card payments configured, so no cash checkout exists to measure"
    ).toBe(false)

    // A COLLECTION order: no address needed, and the confirmation reads "Pay on collection".
    await page.getByRole("button", { name: /collection/i }).click()
    await page.fill("input#name", "Cash Checkout 793")
    await page.fill("input#email", "cash-793@test.com")
    await page.fill("input#phone", "07700 793793")

    const placeOrder = page.locator('button[type="submit"]:has-text("Place order")')
    await expect(placeOrder, "Place order is disabled — basket is likely under the shop minimum").toBeEnabled({
      timeout: 10_000,
    })
    const allergenAck = page.getByRole("checkbox", {
      name: /I have read the allergen information for this order\./i,
    })
    await expect(allergenAck).toBeVisible()
    await allergenAck.click()
    await expect(allergenAck).toBeChecked()

    await placeOrder.click()
    await expect(page.getByRole("heading", { name: "Order confirmed!" })).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText(/Pay on collection/i)).toBeVisible()

    // The default entry injected Stripe after a tick; its iframe and fraud beacons follow the
    // script. Give anything still in flight time to start before the record is read.
    await page.waitForTimeout(2_000)

    // Screenshots of the confirmation at the three reference widths.
    for (const width of [390, 768, 1280]) {
      await page.setViewportSize({ width, height: 900 })
      await page.waitForTimeout(300)
      const path = testInfo.outputPath(`cash-confirmation-${width}.png`)
      await page.screenshot({ path, fullPage: true })
      await testInfo.attach(`cash-confirmation-${width}`, { path, contentType: "image/png" })
    }

    // NON-VACUITY CONTROL FIRST: the listener saw this walk's own traffic.
    const apiRequests = requests.filter((r) => hostOf(r.url) === API_HOST)
    expect(
      apiRequests.length,
      `the request listener recorded no request to the core API host ${API_HOST} — it saw ` +
        `nothing, so a zero Stripe count below would prove nothing (recorded ${requests.length} requests)`
    ).toBeGreaterThan(0)
    const orderPosts = requests.filter(
      (r) => r.method === "POST" && new URL(r.url).pathname.endsWith(`/public/shops/${SHOP_SLUG}/orders`)
    )
    expect(orderPosts.length, "the listener did not see the order POST that produced this confirmation").toBe(1)

    // THE CLAIM: no request to any Stripe host, from the first navigation to the confirmation.
    const stripeRequests = requests.filter((r) => isStripeHost(hostOf(r.url))).map((r) => r.url)
    expect(stripeRequests, "a cash checkout requested Stripe (#793)").toEqual([])

    // ...and no Stripe cookie in the jar, by name or by domain.
    const cookies = await context.cookies()
    const stripeCookies = cookies
      .filter((c) => STRIPE_COOKIES.includes(c.name) || isStripeCookieDomain(c.domain))
      .map((c) => `${c.name}@${c.domain}`)
    expect(stripeCookies, "a cash checkout set a Stripe cookie (#793)").toEqual([])
  })
})
