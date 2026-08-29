/**
 * OAuth callback — the error path a shopper actually lands on.
 *
 * WHY THIS FILE EXISTS. `/shop/auth/callback` is on #202's own acceptance list
 * as uncovered, and it is not an internal detail: it is the page a customer
 * arrives at when an identity-provider hop goes wrong. Before plan 34-04 the
 * missing-code error was written from a mount effect, so the SERVED bytes for a
 * code-less callback were a spinner and the explanation only appeared after
 * hydration. Both halves are asserted below — the served HTML, and what a
 * shopper can see and click.
 *
 * WHY A DESTINATION, NOT A TRANSIT. Recorded project feedback: a page must be
 * tested as somewhere a user LANDS, not merely as a waypoint. So Block 1 does
 * not stop at "the link is present"; it clicks it and requires the storefront
 * to render on the other side.
 *
 * SCOPING. Next keeps the outgoing shell in the document, marked `hidden`,
 * while a navigation settles, so `document` can briefly hold two complete
 * shells and text assertions over the whole page count double. `LIVE` is the
 * same answer `dashboard-interface-corrections.spec.ts:75` and
 * `dashboard-mobile.spec.ts` use, for the same reason.
 *
 * NAVIGATION IS RELATIVE. `playwright.config.ts` is the only base-URL authority
 * (`scripts/check-e2e-baseurl-contract.sh`, #505); this file declares no
 * fallback of its own.
 */
import { test, expect } from "@playwright/test"

/** The live app tree — excludes the `hidden` outgoing shell. */
const LIVE = "body > div:not([hidden])"

const NO_CODE = "No authorization code received."
const AUTH_FAILED = "Authentication failed. Please try again."

test.describe("OAuth callback error path", () => {
  test("no authorization code lands on an explained error, not a spinner", async ({
    page,
    request,
  }) => {
    // The served bytes, before any JavaScript runs. This is the assertion a
    // route-interception stub is structurally incapable of satisfying, and the
    // one that fails if the error is ever moved back into a mount effect: an
    // effect cannot run on the server, and app/layout.tsx:18 sets
    // `dynamic = "force-dynamic"`, so this page IS server-rendered per request.
    // Measured pre-34-04: 0 occurrences, and the served markup carried
    // `animate-spin` instead.
    const served = await request.get("/shop/auth/callback")
    expect(served.status(), "the callback route must serve, not 404").toBe(200)
    const html = await served.text()
    expect(
      html.includes(NO_CODE),
      "the error copy appeared 0 times in the served HTML before 34-04 — it was a spinner until hydration",
    ).toBe(true)

    await page.goto("/shop/auth/callback")

    const live = page.locator(LIVE)
    await expect(live.getByText(NO_CODE)).toBeVisible()

    const backToShop = live.getByRole("link", { name: "Back to shop" })
    await expect(backToShop).toBeVisible()
    await expect(backToShop).toHaveAttribute("href", "/shop")

    // A code-less callback must not present itself as work in progress. The
    // spinner is what a shopper saw indefinitely if hydration never completed.
    await expect(live.locator(".animate-spin")).toHaveCount(0)

    // LANDING DESTINATION: the way out has to actually go somewhere.
    await backToShop.click()
    await expect(page).toHaveURL(/\/shop$/)
    await expect(
      page.locator(LIVE).getByRole("heading", { level: 1 }),
    ).toBeVisible()
  })

  test("a bogus code shows the failure copy rather than hanging", async ({ page }) => {
    // `not-a-real-code` is synthetic on purpose: no genuine authorization code
    // is ever written into a trace, a screenshot or this file (T-34-04-04).
    //
    // MEASURED OUTCOME on the Compose stack: the page reaches AUTH_FAILED
    // without any network call at all. `customer-auth.ts:262-266` returns null
    // as soon as sessionStorage holds no PKCE verifier — which is exactly the
    // state a shopper is in when they open a callback URL out of band. The
    // weaker "it leaves the spinner state" fallback the plan allowed for was
    // therefore NOT needed and is not used; a permanently spinning page fails
    // this assertion.
    await page.goto("/shop/auth/callback?code=not-a-real-code&state=x")

    const live = page.locator(LIVE)
    await expect(live.getByText(AUTH_FAILED)).toBeVisible({ timeout: 15_000 })
    await expect(live.locator(".animate-spin")).toHaveCount(0)

    // The failure path keeps the same way out as the missing-code path.
    await expect(live.getByRole("link", { name: "Back to shop" })).toHaveAttribute(
      "href",
      "/shop",
    )
  })
})
