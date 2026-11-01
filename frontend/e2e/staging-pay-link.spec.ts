/**
 * PAY-04 / D-03 (phase 30, plan 30-19) — a shop's payment link, opened and paid through the PUBLIC
 * staging edge with Stripe's 4242 TEST card, and read back from Stripe on the account that owns it.
 *
 * WHERE THE LINK COMES FROM. `scripts/staging-seed.sh --stage pay-link --link-out FILE` makes one
 * the way a vendor and a customer would (a vendor order with a phone, the public phone verification,
 * the vendor's POST /api/v1/orders/{id}/payment-links) and writes its URL, `/pay#token=…`, ONLY to
 * FILE, mode 600. This spec reads the URL from that file and never prints it: the token in its
 * fragment is a single-use bearer credential that pays the order.
 *
 * WHAT THE DESKTOP ARM PROVES, in order:
 *   1. the preview shows the shop, the amount and "Order …" before anything is spent;
 *   2. "Continue to payment" redeems the link (the redeem response's clientSecret and
 *      stripeAccountId are held IN MEMORY ONLY) and the address bar drops the token (/pay);
 *   3. the Stripe card form mounts, and after page.reload() it mounts AGAIN with no second redeem
 *      and without "Open your payment link" — the history.state resume plan 30-18 wrote (PR #1018
 *      review finding 5), on a real Next.js runtime, which Jest could not prove;
 *   4. 4242 pays and the page says "Payment received";
 *   5. Stripe itself, asked by id with the publishable key and the client secret (what stripe.js
 *      retrievePaymentIntent does; no secret key on the runner), reports status succeeded and
 *      livemode false. When the redeem names a connected account (a direct charge lives on the
 *      vendor's account, and a platform read answers resource_missing) the read sends
 *      Stripe-Account with it;
 *   6. re-opening the ORIGINAL link in a fresh tab says it has already been opened, or that the
 *      order is already paid.
 * It prints exactly one verdict line:
 *   STAGING-PAY order=<n> intent=<pi_…> status=succeeded livemode=false at=<ISO-8601 UTC> account=<acct_…|none>
 *
 * THE MOBILE ARM (@mobile-only, 360 x 780, the mobile project's touch emulation) opens a SECOND link,
 * asserts `(pointer: coarse)`, continues, waits for the card form and asserts the page does not
 * scroll sideways. It does not pay, so its order is left with an unpaid TEST intent: cancel it with
 * `scripts/staging-seed.sh --stage cancel-drafts` (D-02). It prints
 *   STAGING-PAY-MOBILE order=<n> scroll_width=<px> inner_width=<px> pointer=coarse
 *
 * TEST MODE ONLY. Both arms refuse, before any page opens, a publishable key that does not start
 * pk_test_ and a link that is not on the staging app host; the desktop arm asserts livemode false on
 * Stripe's own answer. The only card typed is Stripe's documented test card.
 *
 * NEVER IN A DEFAULT RUN (D-27). playwright.config.ts ignores staging-*.spec.ts unless STAGING_E2E
 * is exactly "1". Collected, a test FAILS (a skip would read as "not applicable") unless
 * PLAYWRIGHT_BASE_URL is exactly the staging app host; there is deliberately no fallback.
 *
 * INPUTS (environment of the test process only):
 *   STAGING_PAY_LINK_FILE           desktop arm: the --link-out file of one pay-link run
 *   STAGING_PAY_LINK_FILE_MOBILE    mobile arm: the --link-out file of ANOTHER run (a link opens once)
 *   STAGING_STRIPE_PUBLISHABLE_KEY  pk_test_… of the staging Stripe account (never printed)
 *   STAGING_EVIDENCE_DIR            optional; the mobile card form is screenshotted there, before any
 *                                   card data exists
 *
 * SECRETS. The link URL, its token, the client secret and the key are never logged. Trace,
 * screenshot-on-failure and video are off for this file. Every page URL, console line and error
 * message this spec prints passes through `redact`, which cuts out a #token= fragment and a client
 * secret.
 *
 * Run (after two pay-link runs):
 *   (cd frontend && STAGING_E2E=1 PLAYWRIGHT_BASE_URL=https://app-staging.olajay.co.uk \
 *      STAGING_PAY_LINK_FILE=… STAGING_PAY_LINK_FILE_MOBILE=… STAGING_STRIPE_PUBLISHABLE_KEY=pk_test_… \
 *      npx playwright test e2e/staging-pay-link.spec.ts --reporter=line)
 */

import { test, expect, type APIRequestContext, type Page } from "@playwright/test"
import { existsSync, mkdirSync, readFileSync } from "node:fs"
import path from "node:path"

const STAGING_APP = "https://app-staging.olajay.co.uk"
const STRIPE_API = "https://api.stripe.com"
const BASE = process.env.PLAYWRIGHT_BASE_URL

const PREVIEW_PATH = "/api/v1/public/payment-links/preview"
const REDEEM_PATH = "/api/v1/public/payment-links/redeem"
const CARD_FORM_TIMEOUT_MS = 30_000
const RECEIVED_TIMEOUT_MS = 60_000

// The page's own copy (app/pay/pay-client.tsx PAY_PAGE_COPY), written out: a spec that imported it
// would agree with a page whose copy had drifted from the UI-SPEC.
const CONTINUE = "Continue to payment"
const RECEIVED = "Payment received"
const NO_LINK = "Open your payment link"
const ALREADY_OPENED = "This payment link has already been opened"
const ALREADY_PAID = "This order is already paid"

test.use({ trace: "off", screenshot: "off", video: "off", locale: "en-GB" })

/** Cut a #token= fragment, a bare token query and a client secret out of anything printed. */
function redact(s: string): string {
  return s
    .replace(/#token=[^\s"'&]*/g, "#token=<redacted>")
    .replace(/([?&]token=)[^\s"'&]*/g, "$1<redacted>")
    .replace(/pi_[A-Za-z0-9]+_secret_[A-Za-z0-9]+/g, "pi_…_secret_<redacted>")
    .slice(0, 600)
}

/** The PaymentIntent id is the part of a client secret before "_secret_". */
function intentIdOf(clientSecret: string): string {
  const i = clientSecret.indexOf("_secret_")
  if (!clientSecret.startsWith("pi_") || i <= 3) {
    throw new Error("the redeem's clientSecret is not of the form pi_…_secret_…")
  }
  return clientSecret.slice(0, i)
}

/**
 * The connected account a captured response names: a non-null acct_ id, or null. A value that is
 * present but not an acct_ id fails the test, naming only its shape.
 */
function accountOf(body: { stripeAccountId?: unknown }, where: string): string | null {
  const v = body.stripeAccountId
  if (v === null || v === undefined) return null
  if (typeof v === "string" && /^acct_[A-Za-z0-9]+$/.test(v)) return v
  const shape = typeof v === "string" ? `a ${v.length}-character string not starting acct_` : `a ${typeof v}`
  throw new Error(`${where}: stripeAccountId is ${shape}, not an acct_ id or null`)
}

/** Guards shared by both arms. Returns the publishable key; refuses before any page is opened. */
function requireStagingInputs(): string {
  if (BASE !== STAGING_APP) {
    throw new Error(
      `PLAYWRIGHT_BASE_URL must be exactly ${STAGING_APP} for the staging /pay proof (set STAGING_E2E=1 too); ` +
        "refusing to skip a run that was asked for",
    )
  }
  const key = process.env.STAGING_STRIPE_PUBLISHABLE_KEY ?? ""
  if (!key.startsWith("pk_test_")) {
    const shown = key === "" ? "<unset>" : `${key.slice(0, 8)}…`
    throw new Error(`STAGING_STRIPE_PUBLISHABLE_KEY must be a Stripe TEST publishable key (pk_test_…); got ${shown}`)
  }
  return key
}

/** The link URL from a --link-out file. Its value is never printed, only what is wrong with it. */
function readLink(envName: string): string {
  const file = process.env[envName] ?? ""
  if (file === "") throw new Error(`${envName} is unset: run scripts/staging-seed.sh --stage pay-link --link-out FILE first`)
  if (!existsSync(file)) throw new Error(`${envName} names a file that does not exist`)
  const url = readFileSync(file, "utf8").trim()
  if (!url.startsWith(`${STAGING_APP}/pay#token=`) || url.length <= `${STAGING_APP}/pay#token=`.length) {
    throw new Error(`${envName}: the file does not hold a ${STAGING_APP}/pay#token=… link (value not shown)`)
  }
  return url
}

/** page.goto with the URL kept out of any error it throws. */
async function gotoLink(page: Page, url: string): Promise<void> {
  try {
    await page.goto(url, { waitUntil: "domcontentloaded", timeout: 30_000 })
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e)
    throw new Error(`opening the payment link failed: ${redact(message.split(url).join("<link>"))}`)
  }
}

function watch(page: Page): void {
  page.on("console", (msg) => {
    if (msg.type() === "error") console.log(`CONSOLE_ERROR ${redact(msg.text())}`)
  })
  page.on("requestfailed", (req) => {
    const u = new URL(req.url())
    if (u.pathname.includes("/payment-links")) {
      console.log(`LINK_REQUEST_FAILED ${req.method()} ${u.host}${u.pathname} ${req.failure()?.errorText ?? ""}`)
    }
  })
}

async function onFailure(page: Page, e: unknown): Promise<never> {
  const text = await page.locator("body").innerText({ timeout: 5_000 }).catch(() => "<no page text>")
  console.log(`PAGE_URL_ON_FAILURE ${redact(page.url())}`)
  console.log(`PAGE_TEXT_ON_FAILURE ${redact(text.replace(/\s+/g, " "))}`)
  if (e instanceof Error) {
    const err = new Error(redact(e.message))
    err.stack = redact(e.stack ?? "")
    throw err
  }
  throw new Error(redact(String(e)))
}

function cardFrame(page: Page) {
  return page
    .locator('iframe[src*="elements-inner-payment"]')
    .or(page.locator('iframe[title="Secure payment input frame"]'))
    .first()
}

/** Open the link, check the preview, press Continue; returns what the preview and redeem said. */
async function openAndContinue(page: Page, url: string) {
  const previewResponse = page.waitForResponse(
    (res) => res.request().method() === "POST" && new URL(res.url()).pathname === PREVIEW_PATH,
    { timeout: 30_000 },
  )
  await gotoLink(page, url)
  const preview = await previewResponse
  expect(preview.status(), "the link preview did not answer 200 (a used, expired or unknown link)").toBe(200)
  const p = (await preview.json()) as { shopName?: unknown; orderNumber?: unknown; amountPennies?: unknown; state?: unknown }
  const shopName = String(p.shopName ?? "")
  const orderNumber = String(p.orderNumber ?? "")
  const amountPennies = Number(p.amountPennies)
  expect(shopName, "the preview names no shop").not.toBe("")
  expect(orderNumber, "the preview names no order").not.toBe("")
  expect(Number.isInteger(amountPennies) && amountPennies > 0, "the preview carries no positive amount").toBe(true)
  console.log(`STAGING-PAY-LINK-OPENED order=${orderNumber} state=${String(p.state ?? "")}`)

  // What the customer sees before anything is spent.
  await expect(page.getByText(shopName, { exact: true }).first()).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText(`£${(amountPennies / 100).toFixed(2)}`, { exact: true }).first()).toBeVisible()
  await expect(page.getByText(`Order ${orderNumber}`, { exact: true })).toBeVisible()

  const redeemResponse = page.waitForResponse(
    (res) => res.request().method() === "POST" && new URL(res.url()).pathname === REDEEM_PATH,
    { timeout: 30_000 },
  )
  await page.getByRole("button", { name: CONTINUE }).click({ timeout: 15_000 })
  const redeem = await redeemResponse
  expect(redeem.status(), "the redeem did not answer 200").toBe(200)
  const r = (await redeem.json()) as { clientSecret?: unknown; stripeAccountId?: unknown }
  // IN MEMORY ONLY: never logged, never written.
  const clientSecret = typeof r.clientSecret === "string" ? r.clientSecret : ""
  expect(clientSecret !== "", "the redeem carries no clientSecret").toBe(true)
  const account = accountOf(r, "the redeem response")
  return { orderNumber, amountPennies, clientSecret, account }
}

test("staging /pay: preview, continue, reload-resume, pay 4242, Stripe reads succeeded, link spent @desktop-only", async ({
  page,
  request,
}) => {
  const publishableKey = requireStagingInputs()
  const url = readLink("STAGING_PAY_LINK_FILE")
  test.setTimeout(300_000)
  watch(page)
  try {
    await payThroughLink(page, request, url, publishableKey)
  } catch (e) {
    await onFailure(page, e)
  }
})

async function payThroughLink(page: Page, request: APIRequestContext, url: string, publishableKey: string): Promise<void> {
  let redeems = 0
  page.on("request", (req) => {
    if (req.method() === "POST" && new URL(req.url()).pathname === REDEEM_PATH) redeems += 1
  })

  // 1-2. Preview, Continue, redeem; the token leaves the address bar.
  const { orderNumber, clientSecret, account } = await openAndContinue(page, url)
  const intentId = intentIdOf(clientSecret)
  await page.waitForURL((u) => u.origin === STAGING_APP && u.pathname === "/pay" && !u.hash.includes("token="), {
    timeout: 15_000,
  })
  expect(page.url().includes("token="), "the token is still in the address bar after Continue").toBe(false)

  // 3. The card form, then a real reload: it must come back from the tab's history entry.
  await expect(cardFrame(page), `no Stripe card form within ${CARD_FORM_TIMEOUT_MS / 1000} s of Continue`).toBeVisible({
    timeout: CARD_FORM_TIMEOUT_MS,
  })
  await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 })
  await expect(
    cardFrame(page),
    "after a reload the card form did not come back (Next.js dropped the history.state resume entry)",
  ).toBeVisible({ timeout: CARD_FORM_TIMEOUT_MS })
  await expect(page.getByRole("heading", { name: NO_LINK })).toHaveCount(0)
  expect(redeems, "the reload redeemed the link again").toBe(1)
  console.log(`STAGING-PAY-RELOAD order=${orderNumber} card_form=remounted redeems=${redeems}`)

  // 4. Stripe's TEST card.
  const card = page.frameLocator('iframe[src*="elements-inner-payment"], iframe[title="Secure payment input frame"]').first()
  await card.locator('input[name="number"]').fill("4242424242424242", { timeout: 20_000 })
  await card.locator('input[name="expiry"]').fill("12 / 34", { timeout: 10_000 })
  await card.locator('input[name="cvc"]').fill("123", { timeout: 10_000 })
  const country = card.locator('select[name="country"]')
  if ((await country.count()) > 0) await country.selectOption("GB", { timeout: 10_000 }).catch(() => {})
  const postcode = card.locator('input[name="postalCode"]')
  if ((await postcode.count()) > 0 && (await postcode.isVisible())) await postcode.fill("SW1A 1AA", { timeout: 10_000 })
  await page.getByRole("button", { name: /^Pay\b/ }).click({ timeout: 15_000 })
  await expect(page.getByRole("heading", { name: RECEIVED })).toBeVisible({ timeout: RECEIVED_TIMEOUT_MS })

  // 5. Stripe's own answer, on the account that owns the intent.
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
    `Stripe did not return the PaymentIntent (account=${account ?? "none"}; a 404 means another Stripe account, or a direct charge read without Stripe-Account)`,
  ).toBe(200)
  const intent = (await stripeRes.json()) as { id?: unknown; status?: unknown; livemode?: unknown }
  expect(intent.id).toBe(intentId)
  expect(intent.livemode, "the PaymentIntent is LIVE mode").toBe(false)
  expect(intent.status).toBe("succeeded")

  // 6. The original link, in a fresh tab (a clean history entry): spent.
  const fresh = await page.context().newPage()
  watch(fresh)
  try {
    await gotoLink(fresh, url)
    await expect(
      fresh.getByRole("heading", { name: ALREADY_OPENED }).or(fresh.getByRole("heading", { name: ALREADY_PAID })),
      "re-opening the spent link did not say it was already opened or the order already paid",
    ).toBeVisible({ timeout: 30_000 })
  } catch (e) {
    await onFailure(fresh, e)
  } finally {
    await fresh.close()
  }

  console.log(
    `STAGING-PAY order=${orderNumber} intent=${intentId} status=succeeded livemode=false at=${new Date().toISOString()} account=${account ?? "none"}`,
  )
}

test.describe("staging /pay on a phone @mobile-only", () => {
  test.use({ viewport: { width: 360, height: 780 } })

  test("staging /pay mounts the card form at 360 px with a coarse pointer and no sideways scroll @mobile-only", async ({
    page,
  }) => {
    requireStagingInputs()
    const url = readLink("STAGING_PAY_LINK_FILE_MOBILE")
    test.setTimeout(180_000)
    watch(page)
    try {
      const { orderNumber } = await openAndContinue(page, url)
      // Read on the page itself, not about:blank: the instrument must be the one the page sees.
      const coarse = await page.evaluate(() => window.matchMedia("(pointer: coarse)").matches)
      expect(coarse, "(pointer: coarse) must match under the mobile project (hasTouch)").toBe(true)
      await expect(cardFrame(page), `no Stripe card form within ${CARD_FORM_TIMEOUT_MS / 1000} s of Continue`).toBeVisible({
        timeout: CARD_FORM_TIMEOUT_MS,
      })
      await page.waitForTimeout(1_000)
      const { scrollWidth, innerWidth } = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        innerWidth: window.innerWidth,
      }))
      expect(scrollWidth, `the page scrolls sideways at ${innerWidth} px`).toBeLessThanOrEqual(innerWidth)
      const evidenceDir = process.env.STAGING_EVIDENCE_DIR
      if (evidenceDir) {
        mkdirSync(evidenceDir, { recursive: true })
        await page.screenshot({ path: path.join(evidenceDir, "30-20-pay-mobile-360.png"), fullPage: true })
      }
      console.log(
        `STAGING-PAY-MOBILE order=${orderNumber} scroll_width=${scrollWidth} inner_width=${innerWidth} pointer=coarse`,
      )
    } catch (e) {
      await onFailure(page, e)
    }
  })
})
