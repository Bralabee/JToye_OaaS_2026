/**
 * Issue #957 (phase 30, plan 30-03) — one storefront CARD order through the PUBLIC staging edge,
 * paid with Stripe's 4242 TEST card and read back from Stripe.
 *
 * THE DEFECT IT PROVES FIXED. Every frontend image before plan 30-01 shipped without the Stripe
 * publishable key inlined. On staging the backend IS Stripe-configured, so a card order created an
 * unpaid DRAFT with a PaymentIntent, and the checkout then silently fell back to the order form:
 * no payment step, ever. The assertion that carries #957 is step 4 below: the Stripe Payment
 * Element must be on screen within 30 s of "Place order".
 *
 * WHAT IT PROVES, in order:
 *   1. the staging shop's checkout accepts a COLLECTION card order (the POST's 201 carries a
 *      clientSecret, held IN MEMORY ONLY);
 *   2. the Payment Element iframe renders within 30 s (#957);
 *   3. 4242 4242 4242 4242 pays, and the browser lands on /shop/{slug}/orders/{orderNumber};
 *   4. public tracking reports paymentStatus CAPTURED within 120 s (the webhook reached core-java);
 *   5. Stripe itself, asked by id with the publishable key and the order's client secret (what
 *      stripe.js retrievePaymentIntent does; no secret key on the runner), reports the intent as
 *      status succeeded and livemode false. A key from another Stripe account answers 404 here, so
 *      this read also proves the browser key and the server key belong to one account. When the
 *      confirmation names a connected account (stripeAccountId, plan 30-17: a direct charge lives on
 *      the vendor's account, where a platform read answers resource_missing), the read sends
 *      Stripe-Account with it (plan 30-19), so plan 30-32 can prove the direct-charge path here too.
 * It prints exactly one verdict line:
 *   STAGING-CARD order=<n> intent=<pi_…> payment_status=CAPTURED livemode=false at=<ISO-8601 UTC> account=<acct_…|none>
 * and, as soon as the order exists, one ORDER line with its number, because a run that stops
 * before payment leaves an unpaid staging DRAFT that plan 30-09 cancels (D-02).
 *
 * TEST MODE ONLY (T-30-11). The spec refuses, before it opens any page, a publishable key that
 * does not start pk_test_, and it asserts livemode false on Stripe's own answer. The only card it
 * types is Stripe's documented test card.
 *
 * NEVER IN A DEFAULT RUN (D-27). playwright.config.ts ignores staging-*.spec.ts unless STAGING_E2E
 * is exactly "1". Even when collected, the test FAILS (a skip would read as "not applicable")
 * unless PLAYWRIGHT_BASE_URL is exactly the staging app host; there is deliberately no fallback
 * (the config is the only base-URL authority, scripts/check-e2e-baseurl-contract.sh).
 *
 * INPUTS (environment of the test process only):
 *   STAGING_STRIPE_PUBLISHABLE_KEY  required, pk_test_… of the staging Stripe account (public by
 *                                   design, but never printed: only its prefix is named)
 *   STAGING_SHOP_SLUG               optional, default staging-test-kitchen
 *   STAGING_EVIDENCE_DIR            optional; when set, the payment step is screenshotted there
 *                                   BEFORE any card data is typed
 *
 * SECRETS. The client secret and the key are never logged. Trace, screenshot and video are off for
 * this file, so a failing run cannot write the typed card data or the secret into an artifact; on
 * failure only the page URL and visible text are printed (neither carries either value).
 *
 * @desktop-only: one paid order proves the path; the mobile project's grepInvert drops it, so the
 * switch collects it exactly once.
 *
 * Run: (cd frontend && STAGING_E2E=1 PLAYWRIGHT_BASE_URL=https://app-staging.olajay.co.uk \
 *        STAGING_STRIPE_PUBLISHABLE_KEY=pk_test_… npx playwright test e2e/staging-card-checkout.spec.ts \
 *        --project=desktop --reporter=line)
 */

import { test, expect, type APIRequestContext, type Page } from "@playwright/test"
import { mkdirSync } from "node:fs"
import path from "node:path"

const STAGING_APP = "https://app-staging.olajay.co.uk"
// The core API as the staging browser sees it (the frontend's inlined NEXT_PUBLIC_API_URL). A
// separate service from the frontend, so not a base-URL fallback.
const STAGING_API = "https://api-staging.olajay.co.uk"
const STRIPE_API = "https://api.stripe.com"
const DEFAULT_SHOP_SLUG = "staging-test-kitchen"

// No fallback literal: an unset or different base URL fails, below.
const BASE = process.env.PLAYWRIGHT_BASE_URL

const PAYMENT_STEP_TIMEOUT_MS = 30_000
const CAPTURE_DEADLINE_MS = 120_000
const CAPTURE_POLL_MS = 5_000

test.use({ trace: "off", screenshot: "off", video: "off", locale: "en-GB" })

function utcStamp(d: Date): string {
  // 2026-10-22T04:15:00.123Z -> 20261022T041500Z
  return d.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z")
}

/**
 * The connected account the confirmation names: a non-null acct_ id, or null. A value that is
 * present but not an acct_ id fails the test, naming only its shape (plan 30-19).
 */
function accountOf(body: { stripeAccountId?: unknown }, where: string): string | null {
  const v = body.stripeAccountId
  if (v === null || v === undefined) return null
  if (typeof v === "string" && /^acct_[A-Za-z0-9]+$/.test(v)) return v
  const shape = typeof v === "string" ? `a ${v.length}-character string not starting acct_` : `a ${typeof v}`
  throw new Error(`${where}: stripeAccountId is ${shape}, not an acct_ id or null`)
}

/** The PaymentIntent id is the part of a client secret before "_secret_". */
function intentIdOf(clientSecret: string): string {
  const i = clientSecret.indexOf("_secret_")
  if (!clientSecret.startsWith("pi_") || i <= 3) {
    // Names the shape, never the value.
    throw new Error("the order's clientSecret is not of the form pi_…_secret_…")
  }
  return clientSecret.slice(0, i)
}

test("staging card checkout shows the Stripe payment step, pays with 4242 and captures @desktop-only", async ({
  page,
  request,
}) => {
  if (BASE !== STAGING_APP) {
    throw new Error(
      `PLAYWRIGHT_BASE_URL must be exactly ${STAGING_APP} for the staging card checkout (set STAGING_E2E=1 too); ` +
        "refusing to skip a run that was asked for",
    )
  }
  const publishableKey = process.env.STAGING_STRIPE_PUBLISHABLE_KEY ?? ""
  if (!publishableKey.startsWith("pk_test_")) {
    // Names the rule and at most the prefix, never the value.
    const shown = publishableKey === "" ? "<unset>" : `${publishableKey.slice(0, 8)}…`
    throw new Error(
      `STAGING_STRIPE_PUBLISHABLE_KEY must be a Stripe TEST publishable key (pk_test_…); got ${shown}. ` +
        "Refusing before any page is opened: this spec places a real order and must never touch live mode",
    )
  }
  const slug = process.env.STAGING_SHOP_SLUG || DEFAULT_SHOP_SLUG
  if (!/^[a-z0-9-]+$/.exec(slug)) {
    throw new Error("STAGING_SHOP_SLUG must match ^[a-z0-9-]+$")
  }
  const email = `guest.staging+957-${utcStamp(new Date())}@olajay.co.uk`
  // Every wait below carries its own bound; their sum stays under this one, so a hang surfaces
  // as a step's own error inside the try below and never as the bare test timeout.
  test.setTimeout(360_000)

  try {
    await walkCardCheckout(page, request, { slug, email, publishableKey })
  } catch (e) {
    const text = await page.locator("body").innerText({ timeout: 5_000 }).catch(() => "<no page text>")
    console.log(`PAGE_URL_ON_FAILURE ${page.url()}`)
    console.log(`PAGE_TEXT_ON_FAILURE ${text.replace(/\s+/g, " ").slice(0, 600)}`)
    throw e
  }
})

async function walkCardCheckout(
  page: Page,
  request: APIRequestContext,
  input: { slug: string; email: string; publishableKey: string },
): Promise<void> {
  const { slug, email, publishableKey } = input

  // Diagnostics for a refusal that never becomes a response (a blocked or aborted order request,
  // a page error). Both are redacted: a client secret, should any page ever log one, is cut out.
  const redact = (s: string) => s.replace(/pi_[A-Za-z0-9]+_secret_[A-Za-z0-9]+/g, "pi_…_secret_<redacted>").slice(0, 300)
  page.on("requestfailed", (req) => {
    if (new URL(req.url()).pathname.includes("/orders")) {
      console.log(`ORDER_REQUEST_FAILED ${req.method()} ${new URL(req.url()).host}${new URL(req.url()).pathname} ${req.failure()?.errorText ?? ""}`)
    }
  })
  page.on("console", (msg) => {
    if (msg.type() === "error") console.log(`CONSOLE_ERROR ${redact(msg.text())}`)
  })

  // 1. The shop, the first available dish, the basket, the checkout.
  await page.goto(`/shop/${slug}`, { waitUntil: "domcontentloaded", timeout: 30_000 })
  const addButtons = page.locator('button:has-text("Add")')
  await expect(addButtons.first()).toBeVisible({ timeout: 20_000 })
  // A click before hydration can silently no-op; wait for the page to settle (bounded).
  await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {})
  const MAX_ADDS = 4
  let cleared = false
  for (let adds = 0; adds < MAX_ADDS && !cleared; adds++) {
    await addButtons.first().click({ timeout: 15_000 })
    await page.waitForTimeout(500)
    cleared = (await page.getByText(/Minimum order/i).count()) === 0
  }
  expect(cleared, `never cleared the shop minimum within ${MAX_ADDS} items`).toBe(true)

  await page.goto(`/shop/${slug}/cart`, { waitUntil: "domcontentloaded", timeout: 30_000 })
  await page.getByText("Proceed to checkout").click({ timeout: 20_000 })
  await page.waitForURL((u) => u.pathname === `/shop/${slug}/checkout`, { timeout: 30_000 })
  await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {})

  // 2. COLLECTION, the customer's details, the allergen acknowledgement.
  await page.getByRole("button", { name: /collection/i }).click({ timeout: 15_000 })
  await page.fill("input#name", "Staging Card 957", { timeout: 15_000 })
  await page.fill("input#email", email, { timeout: 15_000 })
  await page.fill("input#phone", "07700 900123", { timeout: 15_000 })
  const allergenAck = page.getByRole("checkbox", {
    name: /I have read the allergen information for this order\./i,
  })
  await expect(allergenAck).toBeVisible({ timeout: 15_000 })
  await allergenAck.click()
  await expect(allergenAck).toBeChecked()

  const placeOrder = page.locator('button[type="submit"]:has-text("Place order")')
  await expect(placeOrder, "Place order is disabled (basket under the minimum, or card payments unavailable)").toBeEnabled({
    timeout: 15_000,
  })

  // 3. Place order; the POST's 201 carries the order number and the client secret.
  const orderPost = page.waitForResponse(
    (res) => res.request().method() === "POST" && new URL(res.url()).pathname.endsWith(`/public/shops/${slug}/orders`),
    { timeout: 30_000 },
  )
  await placeOrder.click({ timeout: 15_000 })
  const postResponse = await orderPost
  expect(postResponse.status(), "the guest order POST did not answer 201").toBe(201)
  const confirmation = (await postResponse.json()) as {
    orderNumber?: unknown
    clientSecret?: unknown
    stripeAccountId?: unknown
  }
  const orderNumber = typeof confirmation.orderNumber === "string" ? confirmation.orderNumber : ""
  expect(orderNumber, "the guest order confirmation carries no orderNumber").not.toBe("")
  // From here on this run has created a staging order; say which one before anything can fail.
  console.log(`STAGING-CARD-ORDER-CREATED order=${orderNumber}`)
  // IN MEMORY ONLY: never logged, never written. Only its intent-id prefix is ever printed.
  const clientSecret = typeof confirmation.clientSecret === "string" ? confirmation.clientSecret : ""
  expect(
    clientSecret !== "",
    "the confirmation carries no clientSecret: the staging backend took the non-card branch, so no card payment can be proven",
  ).toBe(true)
  const intentId = intentIdOf(clientSecret)
  const account = accountOf(confirmation, "the guest order confirmation")

  // 4. THE #957 ASSERTION: the Stripe Payment Element is on screen within 30 s.
  const paymentIframe = page
    .locator('iframe[src*="elements-inner-payment"]')
    .or(page.locator('iframe[title="Secure payment input frame"]'))
    .first()
  await expect(
    paymentIframe,
    `#957: no Stripe payment step within ${PAYMENT_STEP_TIMEOUT_MS / 1000} s after Place order ` +
      `(order ${orderNumber} is an unpaid DRAFT)`,
  ).toBeVisible({ timeout: PAYMENT_STEP_TIMEOUT_MS })

  const evidenceDir = process.env.STAGING_EVIDENCE_DIR
  if (evidenceDir) {
    mkdirSync(evidenceDir, { recursive: true })
    // Before any card data is typed: the page holds only the order summary and an empty form.
    await page.waitForTimeout(1_500)
    await page.screenshot({ path: path.join(evidenceDir, "30-04-payment-step.png"), fullPage: true })
  }

  // 5. Stripe's TEST card.
  const card = page.frameLocator('iframe[src*="elements-inner-payment"], iframe[title="Secure payment input frame"]').first()
  await card.locator('input[name="number"]').fill("4242424242424242", { timeout: 20_000 })
  await card.locator('input[name="expiry"]').fill("12 / 34", { timeout: 10_000 })
  await card.locator('input[name="cvc"]').fill("123", { timeout: 10_000 })
  const country = card.locator('select[name="country"]')
  if ((await country.count()) > 0) {
    await country.selectOption("GB", { timeout: 10_000 }).catch(() => {})
  }
  const postcode = card.locator('input[name="postalCode"]')
  if ((await postcode.count()) > 0 && (await postcode.isVisible())) {
    await postcode.fill("SW1A 1AA", { timeout: 10_000 })
  }
  await page.getByRole("button", { name: /^Pay\b/ }).click({ timeout: 15_000 })
  await page.waitForURL((u) => u.origin === STAGING_APP && u.pathname === `/shop/${slug}/orders/${orderNumber}`, {
    timeout: 60_000,
  })

  // 6. Public tracking, bound to the order's email, until the webhook has captured the payment.
  const trackUrl = `${STAGING_API}/api/v1/public/orders/${encodeURIComponent(orderNumber)}?email=${encodeURIComponent(email)}`
  const deadline = Date.now() + CAPTURE_DEADLINE_MS
  let paymentStatus = ""
  for (;;) {
    const res = await request.get(trackUrl, { timeout: 15_000 })
    let status = ""
    if (res.ok()) {
      const body = (await res.json()) as { status?: unknown; paymentStatus?: unknown }
      status = String(body.status ?? "")
      paymentStatus = String(body.paymentStatus ?? "")
    }
    console.log(`TRACK http=${res.status()} status=${status} paymentStatus=${paymentStatus} at=${new Date().toISOString()}`)
    if (paymentStatus === "CAPTURED") break
    if (Date.now() + CAPTURE_POLL_MS > deadline) {
      throw new Error(
        `order ${orderNumber}: paymentStatus never reached CAPTURED within ${CAPTURE_DEADLINE_MS / 1000} s ` +
          `(last: ${paymentStatus || "<none>"}) — did the Stripe webhook reach core-java?`,
      )
    }
    await new Promise((r) => setTimeout(r, CAPTURE_POLL_MS))
  }

  // 7. Stripe's own answer, by id, with the publishable key and the order's client secret.
  // A transport error's message quotes the request URL, which carries the client secret: rethrow
  // it with the secret cut out, so a failed read cannot print it.
  // On the account that owns the intent: a direct charge is invisible to a platform read.
  const headers: Record<string, string> = { Authorization: `Bearer ${publishableKey}` }
  if (account !== null) headers["Stripe-Account"] = account
  const stripeRes = await request
    .get(`${STRIPE_API}/v1/payment_intents/${encodeURIComponent(intentId)}?client_secret=${encodeURIComponent(clientSecret)}`, {
      headers,
      timeout: 20_000,
    })
    .catch((e: unknown) => {
      const message = e instanceof Error ? e.message : String(e)
      throw new Error(`the Stripe PaymentIntent read failed: ${redact(message.split(clientSecret).join("<redacted>"))}`)
    })
  expect(
    stripeRes.status(),
    `Stripe did not return the PaymentIntent for the publishable key (account=${account ?? "none"}; a 404 means the key is from another account, or a direct charge read without Stripe-Account)`,
  ).toBe(200)
  const intent = (await stripeRes.json()) as { id?: unknown; status?: unknown; livemode?: unknown }
  expect(intent.id).toBe(intentId)
  expect(intent.livemode, "the PaymentIntent is LIVE mode").toBe(false)
  expect(intent.status).toBe("succeeded")

  console.log(
    `STAGING-CARD order=${orderNumber} intent=${intentId} payment_status=CAPTURED livemode=false at=${new Date().toISOString()} account=${account ?? "none"}`,
  )
}
