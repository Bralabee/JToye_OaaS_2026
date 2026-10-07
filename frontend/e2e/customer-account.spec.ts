/**
 * #838 acceptance in a real browser (D-04, 31.1-26): a signed-in customer finds "My account" in the
 * storefront menu, and "Download my data" and "Delete my account" each lodge a request through the
 * EXISTING public intake — each one answered by a "Confirm your data request" email, because the
 * intake still verifies by email whoever asks.
 *
 * TWO BLOCKS.
 *
 *   1. SERVED HTML, no cookies. `/shop/account` is a server component: an anonymous request must get
 *      the sign-in wall IN THE RAW BYTES (no hydration, no client fetch), returning to
 *      /shop/account, with noindex and with no data-rights control at all. `request.get` runs no
 *      script, so this sees only what the server sent (e2e/helpers/served-html.ts). This is the
 *      assertion scripts/gates/ssr-routes.conf binds the route to.
 *
 *   2. CLICK-THROUGH, signed in. A fresh customer is registered through the real sign-in page (the
 *      `jtoye-customers` realm seeds no users and has verifyEmail=false, so registering signs in —
 *      the storage-keys-signout.spec.ts precedent). From /shop the spec clicks "My account" in the
 *      nav, presses each action, and requires:
 *        - exactly one intake POST per action, carrying the signed-in address and the right type;
 *        - the constant "Check your email to confirm" copy;
 *        - one Mailhog message per action, subject "Confirm your data request", to that address.
 *      The deletion is lodged and NEVER confirmed: the spec does not open the emailed link, so the
 *      throwaway account is not erased by the run (the confirm page is dsar-verify-link.spec.ts's).
 *      The Mailhog count is the non-vacuity control: a page that showed the copy without lodging
 *      anything would leave the inbox empty.
 *
 * RATE LIMITS. The intake allows 5 requests per IP per hour and 3 per address per day. Block 2 is
 * `@desktop-only`, so one full run lodges 2 (one address, fresh every run). With
 * dsar-verify-link.spec.ts's 2, a full suite run spends 4 of the 5 per-IP hourly allowance.
 *
 * WHEN IT IS RUN. Listed (not run) by plan 31.1-26 — the running images predate this branch, so the
 * page does not exist on them. Its live RED/GREEN run belongs to plan 31.1-30's rebuild: against the
 * pre-31.1-26 runtime block 1 fails (no wall for /shop/account; the [slug] route answers instead)
 * and block 2 fails on the missing "My account" link.
 *
 * NAVIGATION IS RELATIVE: playwright.config.ts is the only base-URL authority (#505).
 *
 * Run: npx playwright test e2e/customer-account.spec.ts  (compose stack up, rebuilt from this tree)
 */
import { test, expect, type APIRequestContext, type Page } from "@playwright/test"
import { servedHtml, countOf } from "./helpers/served-html"

const MAILHOG = process.env.MAILHOG_URL || "http://localhost:8025"
const INTAKE_PATH = "/api/v1/public/gdpr/dsar"
const LODGED_HEADING = "Check your email to confirm"
const VERIFY_SUBJECT = "Confirm your data request"

/** The chrome-only `<h2>` floor (ssr-coverage.spec.ts measured it on a page that renders no body). */
const FOOTER_H2_FLOOR = 3

type MailhogMessage = { Content?: { Headers?: Record<string, string[]> } }

/** Mailhog's messages for `address`, polled until `atLeast` have arrived or the deadline passes. */
async function messagesFor(
  request: APIRequestContext,
  address: string,
  atLeast: number
): Promise<MailhogMessage[]> {
  const deadline = Date.now() + 20_000
  for (;;) {
    const res = await request.get(`${MAILHOG}/api/v2/search?kind=to&query=${encodeURIComponent(address)}`)
    expect(res.ok(), `Mailhog search answered ${res.status()} — is Mailhog up on ${MAILHOG}?`).toBe(true)
    const items = ((await res.json()) as { items?: MailhogMessage[] }).items ?? []
    if (items.length >= atLeast || Date.now() > deadline) return items
    await new Promise((r) => setTimeout(r, 500))
  }
}

const subjectOf = (m: MailhogMessage) => m.Content?.Headers?.Subject?.[0] ?? ""

async function registerCustomer(page: Page): Promise<string> {
  const rand = Math.floor(Math.random() * 1_000_000)
  const email = `e2e-838-${Date.now()}-${rand}@test.com`

  await page.goto("/shop")
  await page.waitForLoadState("domcontentloaded")
  await page.locator("nav").getByRole("link", { name: "Sign in" }).first().click()
  await page.waitForURL(/\/shop\/signin/, { timeout: 15_000 })
  await page.getByRole("button", { name: "Create an account" }).click()
  await page.waitForURL(/openid-connect\/registrations/, { timeout: 20_000 })

  await page.fill("input#email", email)
  await page.fill("input#password", "TestPass123!")
  await page.fill("input#password-confirm", "TestPass123!")
  await page.fill("input#firstName", "Account")
  await page.fill("input#lastName", `Rights${rand}`)
  await page.locator('input[type="submit"]').click()

  // The callback URL (/shop/auth/callback?code=...) also matches /shop/, so a bare /shop pattern
  // resolves BEFORE the code exchange finishes, and the goto below aborts it (31.1-30: the old
  // helper lost that race on the live stack). Wait until the callback page has replaced itself.
  await page.waitForURL(
    (url) => /^\/shop(\/|$)/.test(url.pathname) && !url.pathname.startsWith("/shop/auth/callback"),
    { timeout: 25_000 }
  )
  await page.goto("/shop")
  await page.waitForLoadState("domcontentloaded")
  // PRESENCE CONTROL: StorefrontNav's signed-in control.
  await expect(
    page.locator('button[title="Sign out"]').first(),
    "registration did not leave a signed-in customer — nothing below would mean anything"
  ).toBeVisible({ timeout: 20_000 })
  return email
}

test.describe("#838 My account — the data-rights front door", () => {
  test("/shop/account serves the sign-in wall to an anonymous request, in the first paint", async ({
    request,
  }) => {
    const html = await servedHtml(request, "/shop/account")

    // LIVENESS CONTROL — the shared layout's footer headings survive a body that rendered nothing,
    // so every "0" below is about content, not a dead server.
    expect(
      countOf(html, "<h2"),
      `only ${countOf(html, "<h2")} <h2 in ${html.length} bytes — the shared layout did not render`
    ).toBeGreaterThanOrEqual(FOOTER_H2_FLOOR)

    expect(countOf(html, "Sign in to continue"), "the wall is not in the served bytes").toBeGreaterThan(0)
    expect(
      countOf(html, "Sign in to get a copy of your data or delete your account."),
      "the account page's own wall message is not in the served bytes"
    ).toBeGreaterThan(0)
    expect(
      countOf(html, "/shop/signin?next=%2Fshop%2Faccount"),
      "the wall does not return the customer to /shop/account after sign-in"
    ).toBeGreaterThan(0)
    expect(countOf(html, /<meta name="robots" content="noindex, ?nofollow"/), "the page must be noindex").toBeGreaterThan(0)

    // The wall is the WHOLE answer: no data-rights control reaches an anonymous request.
    expect(countOf(html, "Download my data")).toBe(0)
    expect(countOf(html, "Delete my account")).toBe(0)
  })

  test("@desktop-only a signed-in customer lodges both requests from My account, each confirmed by email", async ({
    page,
    request,
  }, testInfo) => {
    test.setTimeout(120_000)
    const email = await registerCustomer(page)

    const intakePosts: Array<{ body: unknown; key: string | null }> = []
    page.on("request", (r) => {
      if (r.method() === "POST" && new URL(r.url()).pathname.endsWith(INTAKE_PATH)) {
        intakePosts.push({ body: r.postDataJSON(), key: r.headers()["idempotency-key"] ?? null })
      }
    })

    // The nav link is the door — a customer does not type the URL.
    await page.getByRole("navigation", { name: "Storefront" }).getByRole("link", { name: "My account" }).click()
    await page.waitForURL(/\/shop\/account$/, { timeout: 15_000 })
    await expect(page.getByRole("heading", { level: 1, name: "My account" })).toBeVisible()
    const field = page.getByLabel("Email address")
    await expect(field).toHaveValue(email)
    await expect(field).toHaveAttribute("readonly", "")

    // 1. Download my data.
    await page.getByRole("button", { name: "Download my data" }).click()
    await expect(page.getByText(LODGED_HEADING).first()).toBeVisible({ timeout: 15_000 })
    expect(intakePosts, "exactly one intake POST for the download").toHaveLength(1)
    expect(intakePosts[0].body).toEqual({ email, requestType: "ACCESS" })
    expect(intakePosts[0].key, "the POST must carry an Idempotency-Key").toBeTruthy()
    const afterFirst = await messagesFor(request, email, 1)
    expect(afterFirst.length, `no verification email reached Mailhog for ${email}`).toBe(1)
    expect(subjectOf(afterFirst[0])).toContain(VERIFY_SUBJECT)

    // 2. Delete my account: the dialog first, then the request.
    await page.getByRole("button", { name: "Delete my account" }).click()
    const dialog = page.getByRole("dialog")
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText("sign-in account is deleted")
    await expect(dialog).toContainText("order and tax records")
    expect(intakePosts, "opening the dialog must lodge nothing").toHaveLength(1)
    await dialog.getByRole("button", { name: "Yes, delete my account" }).click()
    await expect(dialog).toBeHidden({ timeout: 15_000 })
    expect(intakePosts, "exactly one more intake POST for the deletion").toHaveLength(2)
    expect(intakePosts[1].body).toEqual({ email, requestType: "ERASURE" })
    expect(intakePosts[1].key).toBeTruthy()
    expect(intakePosts[1].key, "the two requests are independent, so their keys differ").not.toBe(intakePosts[0].key)
    const afterSecond = await messagesFor(request, email, 2)
    expect(afterSecond.length, "one verification email per action").toBe(2)
    for (const m of afterSecond) expect(subjectOf(m)).toContain(VERIFY_SUBJECT)

    // Nothing on the page says whether any shop holds data for this address.
    await expect(page.getByText(/shops? (hold|holds|held)|no data (is )?held|we found/i)).toHaveCount(0)

    for (const width of [390, 768, 1280]) {
      await page.setViewportSize({ width, height: 900 })
      await page.waitForTimeout(300)
      const path = testInfo.outputPath(`customer-account-lodged-${width}.png`)
      await page.screenshot({ path, fullPage: true })
      await testInfo.attach(`customer-account-lodged-${width}`, { path, contentType: "image/png" })
    }
  })
})
