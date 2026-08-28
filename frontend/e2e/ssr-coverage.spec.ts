/**
 * THE SSR COVERAGE INSTRUMENT, AND THE PROOF THAT IT IS ONE (#542, #507).
 *
 * ROADMAP Phase 34, criterion 1: *"A route-interception stub is never the
 * coverage story for a server-rendered route ... the phase must produce a
 * pattern that does not silently drop coverage, shown to fail against a stubbed
 * SSR route."*
 *
 * Two blocks, and neither is a convenience test:
 *
 *   1. A LIVE `context.route` stub is registered on `/shop`, and the block first
 *      proves the stub is working (the DOM carries its marker) before showing
 *      that `request.get` and `context.request.get` are both untouched by it.
 *      Without that positive control the block would pass just as happily over a
 *      stub that was never registered — the classic vacuous assertion, and the
 *      exact shape this phase exists to eliminate.
 *
 *   2. `/shop/orders` — the last server-rendering, data-loading public route
 *      with NO served-HTML assertion anywhere in the suite — gets one.
 *
 * WHY THE DISTINCTION MATTERS AT ALL. `page.goto` + `expect(locator)` sees the
 * union of the server render and the browser render and cannot tell them apart:
 * the client island fetches, the browser stub answers, the DOM fills, Playwright
 * waits, green. `request.get` performs no navigation, runs no script and is not
 * intercepted, so it sees only what the server actually sent. See
 * `e2e/helpers/served-html.ts`, which is the single definition of that
 * instrument for the whole suite.
 *
 * ─── MEASURED, live Compose stack, Playwright 1.62.1, 2026-08-28 ───────────────
 *
 * Block 1 — with `context.route("**\/shop", fulfill(STUB_HTML))` registered:
 *
 *   A  request fixture   .get("/shop")   stubbed? NO   ~54,190 bytes real content
 *   B  page.goto("/shop") -> page.content()  stubbed? YES  <- POSITIVE CONTROL
 *   C  context.request   .get("/shop")   stubbed? NO   ~54,190 bytes real content
 *
 *   B is the arm that proves the stub is alive. A and C bypass it entirely.
 *   Re-measured for this commit against the same stack: `/shop` served 54,263
 *   bytes with 1 `<h1` and 5 occurrences of "Brixton Village Grill"; the stub
 *   body is 61 bytes and carries neither.
 *
 * Block 2 — `/shop/orders` with NO cookies (`curl`, no browser):
 *
 *   status 200, 30,974 bytes
 *   "Sign in to continue" .................................. 2
 *   "Sign in to view your order history and track deliveries"  2
 *   "/shop/signin?next=" ................................... 3
 *   `<h1` .................................................. 0   <- SEE BELOW
 *   `<h2` .................................................. 4
 *   ORD-shaped order numbers ............................... 0
 *
 *   (The doubled counts are React's streaming staging buffer, which parks a
 *   second copy of the shell in `<div hidden>`; every assertion below is
 *   therefore `> 0`, never an exact count.)
 *
 * ─── A PLANNED CONTROL THAT DOES NOT EXIST ON THIS ROUTE, RECORDED NOT SWAPPED ──
 *
 * This block was specified with an `<h1` presence control — the thing that holds
 * even on a server that rendered nothing, so that a 0 elsewhere is provably
 * about CONTENT and not about a dead server. Measured: `/shop/orders` serves
 * **0** `<h1`. The wall's own heading is an `<h2>` ("Sign in to continue") and
 * the route sets `robots: noindex` because it is a per-customer surface, so
 * there is no page-level `<h1` to assert. The criterion is unsatisfiable here as
 * written, and quietly deleting it would have left the ORDER-NUMBER assertion
 * below with no liveness control at all.
 *
 * It is replaced by a STRICTLY STRONGER control, and the replacement was
 * measured rather than assumed. Counting `<h2` across four routes on the same
 * stack:
 *
 *   /                                    <h1=1  <h2=5   wall=0
 *   /shop                                <h1=1  <h2=6   wall=0
 *   /shop/orders                         <h1=0  <h2=4   wall=2
 *   /shop/definitely-not-a-real-shop-a2  <h1=0  <h2=3   wall=0   <- CHROME FLOOR
 *
 * The last row is a page whose entire body is a not-found screen: it carries no
 * wall and still serves 3 `<h2>`. Those three are `PublicFooter`'s section
 * headings, which the shared layout renders whatever the page body does. So
 * `<h2 >= 3` is a chrome-only floor that survives a page that rendered nothing,
 * which is precisely the property the `<h1` control was for — and it is stronger
 * than the original, because it is a floor with a known source rather than a
 * bare presence check.
 */
import { test, expect, type APIRequestContext } from "@playwright/test"
import { servedHtml, countOf } from "./helpers/served-html"

/**
 * A string that cannot occur in real markup. If this ever appears in a served
 * response, something is serving test fixtures to customers.
 */
const STUB_MARKER = "ssr-coverage-stub-marker-do-not-ship"
const STUB_HTML = `<html><body><h1>${STUB_MARKER}</h1></body></html>`

/**
 * `ORD-{8 hex}-{YYYYMMDD}-{8 hex}` — `OrderService.generateOrderNumber`, whose
 * parts are `UUID` substrings upper-cased, so both variable segments are hex.
 *
 * The sample below is a REAL order number read out of the running database, not
 * the example in that method's Javadoc: the Javadoc says
 * `ORD-A1B2C3D4-20260116-E5F6G7H8`, and `G`/`H` are not hex digits, so a
 * positive control built from it reports 0 and would have been read as "the
 * regex is fine, the page is clean". It is not fine — it is a broken instrument,
 * which is why the control is asserted in the test rather than trusted here.
 */
const ORDER_NUMBER = /ORD-[0-9A-F]{8}-\d{8}-[0-9A-F]{8}/g
const REAL_ORDER_NUMBER = "ORD-00000000-20260714-DB2E43A5"

/** The chrome-only `<h2>` floor, measured on a page that renders no body content. */
const FOOTER_H2_FLOOR = 3

// The served bytes do not vary with viewport, so this file runs once rather than
// being duplicated across both projects. `@desktop-only` EXCLUDES it from the
// mobile project's enumeration (playwright.config.ts grepInvert) rather than
// skipping it at runtime — a skip must mean "nobody checked this".
test.describe("SSR coverage — the served bytes are the instrument @desktop-only", () => {
  test("a browser route stub cannot answer for the served bytes", async ({
    page,
    context,
    request,
  }) => {
    await context.route("**/shop", (route) =>
      route.fulfill({ status: 200, contentType: "text/html", body: STUB_HTML })
    )

    // ── THE POSITIVE CONTROL, FIRST AND DELIBERATELY ──────────────────────────
    // Everything below asserts that something is ABSENT. An absence assertion is
    // worthless unless the thing could have been present, so the stub must be
    // shown to be live before it is shown to be bypassed. Without this arm the
    // block passes identically over a stub that silently failed to register.
    await page.goto("/shop")
    const dom = await page.content()
    expect(
      countOf(dom, STUB_MARKER),
      `the route stub did not take effect, so every assertion below would be vacuous ` +
        `(DOM was ${dom.length} bytes)`
    ).toBeGreaterThan(0)

    // ── THE INSTRUMENT: neither request context is intercepted ─────────────────
    const contexts: Array<[string, APIRequestContext]> = [
      ["request fixture", request],
      ["context.request", context.request],
    ]

    for (const [label, api] of contexts) {
      const res = await api.get("/shop")
      expect(res.status(), `${label} should serve 200`).toBe(200)
      const html = await res.text()

      expect(
        countOf(html, "<h1"),
        `${label} returned ${html.length} bytes with no <h1 — that is a dead server, ` +
          `not a passing stub-immunity check`
      ).toBeGreaterThan(0)

      expect(
        countOf(html, STUB_MARKER),
        `${label} was intercepted by context.route. Measured 2026-08-28: the request ` +
          `fixture and context.request each returned ~54,190 bytes of real content ` +
          `while page.goto received the ${STUB_HTML.length}-byte stub body — this run ` +
          `got ${html.length} bytes. If this ever fails, request.get has stopped being ` +
          `a server-only instrument and every SSR coverage claim built on it is void.`
      ).toBe(0)

      // The bytes must carry real content, not merely lack the marker: a server
      // returning an empty 200 would satisfy the assertion above.
      expect(
        countOf(html, "Brixton Village Grill"),
        `${label} carried 0 occurrences of a seeded shop name in ${html.length} bytes`
      ).toBeGreaterThan(0)
    }

    // ...and the DOM is STILL stubbed after all of that, so the two instruments
    // genuinely disagree within one run rather than in two separate runs that
    // could have been looking at different server states.
    expect(
      countOf(await page.content(), STUB_MARKER),
      "the stub stopped being live mid-test, so the disagreement above proves nothing"
    ).toBeGreaterThan(0)
  })

  test("/shop/orders serves its sign-in wall in the first paint", async ({ request }) => {
    // No cookies: `request` is a fresh APIRequestContext with no storage state,
    // so this is exactly what an anonymous visitor's browser receives before any
    // JavaScript runs. `app/shop/orders/page.tsx` renders the wall FROM THE
    // SERVER for this case (:49-56) — it was a `"use client"` page, where the
    // visitor sat behind a spinner that resolved into a wall.
    const html = await servedHtml(request, "/shop/orders")

    // LIVENESS CONTROL — the shared layout's footer headings, which survive a
    // page body that rendered nothing (measured: 3 on the not-found screen).
    // Without this, every "0 occurrences" below could equally mean "the server
    // answered with nothing at all".
    expect(
      countOf(html, "<h2"),
      `only ${countOf(html, "<h2")} <h2 in ${html.length} bytes — below the chrome ` +
        `floor of ${FOOTER_H2_FLOOR}, so the server did not render the shared layout ` +
        `and nothing below is about content`
    ).toBeGreaterThanOrEqual(FOOTER_H2_FLOOR)

    // THE WALL ITSELF, read verbatim out of `components/storefront/
    // customer-signin-prompt.tsx` and the `message` prop the page passes it.
    expect(
      countOf(html, "Sign in to continue"),
      "the wall's heading is not in the served bytes — /shop/orders is client-rendering it again"
    ).toBeGreaterThan(0)
    expect(
      countOf(html, "Sign in to view your order history and track deliveries"),
      "the page's own wall message is not in the served bytes"
    ).toBeGreaterThan(0)

    // A crawlable, working door out of the wall — not a JS-only click handler.
    // The `next` parameter is what returns the customer here after signing in.
    expect(
      countOf(html, "/shop/signin?next="),
      "the wall serves no link to the sign-in page"
    ).toBeGreaterThan(0)

    // ── THE WALL MUST BE THE WHOLE ANSWER ─────────────────────────────────────
    // An SSR regression that leaked a customer's orders into the first paint of
    // an UNAUTHENTICATED request would be invisible to every DOM assertion in
    // the suite (the island re-fetches and re-renders the correct thing over the
    // top). It is visible here.
    //
    // The regex is validated against a real order number IN THE TEST, because a
    // regex that cannot match reports 0 against anything and would turn this
    // into a permanently-green no-op.
    expect(
      countOf(REAL_ORDER_NUMBER, ORDER_NUMBER),
      "the order-number pattern does not match a real order number — the check below is vacuous"
    ).toBe(1)
    expect(
      countOf(html, ORDER_NUMBER),
      "an unauthenticated request received order-number-shaped content in the served HTML"
    ).toBe(0)
  })
})
