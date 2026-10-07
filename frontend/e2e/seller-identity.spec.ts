/**
 * #789 (P0, 31.1-24 + 31.1-25): the customer can see who they are buying from — the seller's legal
 * name, geographic address and an email contact — on the shop page, before they place the order,
 * and on the durable record they keep (the confirmation and its email), together with the statement
 * that J'Toye is the ordering platform and not the seller and the CCR reg 28(1)(c) no-cancellation
 * statement for freshly prepared food (D-10, D-11, D-13, D-20).
 *
 * WHAT EACH HALF READS, AND WHY
 *
 *   1. The shop page is read from the SERVED HTML (`servedHtml`, no browser, no script). The block
 *      is rendered by the server component from the shop it already loaded, so it must be in the
 *      bytes a crawler and the first paint get. A DOM assertion could not tell that apart from a
 *      late client fetch (see `helpers/served-html.ts`).
 *   2. The checkout and the cash confirmation are read in the browser, because both are client
 *      pages. "Before purchase" is asserted as document ORDER: the block precedes Place order.
 *   3. The durable medium is the confirmation EMAIL in Mailhog. That half is implemented by 31.1-25;
 *      this spec is its acceptance as well, so it stays red until 31.1-25 ships.
 *
 * THE EXPECTED VALUES COME FROM THE API, NOT FROM THIS FILE. The seeded shop's seller object is
 * read from `GET /public/shops/{slug}` first, with a precondition that it exists (the dev seeder and
 * `scripts/seed-e2e-fixtures.sh` backfill one, 31.1-12). Every later assertion compares the page to
 * those values, so a reseed with different demo details cannot turn this spec green or red by
 * accident, and a missing seller is a precondition failure, not a pass.
 *
 * Fail direction (31.1-30 runs it live): against a frontend built before 31.1-24 the served HTML
 * has no "Who you are buying from" block and the first test fails on its first block assertion.
 *
 * Run: npx playwright test e2e/seller-identity.spec.ts  (compose stack up, rebuilt)
 */
import { test, expect, type APIRequestContext, type Page, type TestInfo } from "@playwright/test"

import { servedHtml } from "./helpers/served-html"

// The deterministic seeded shop the storefront suites use (DemoDataSeeder, UIX-05). Cash-only on
// the compose stack, which the cash walk below asserts rather than assumes.
const SHOP_SLUG = "mama-ades-kitchen"

// The core API as the BROWSER sees it (compose publishes core-java on 9090), and Mailhog. Separate
// services from the frontend, so neither is a base-URL fallback (scripts/check-e2e-baseurl-contract.sh
// scope note); the same env vars and defaults as cash-checkout-no-stripe / dsar-verify-link.
const API = process.env.PLAYWRIGHT_API_URL || "http://localhost:9090"
const MAILHOG = process.env.MAILHOG_URL || "http://localhost:8025"

// The statements the block must carry, as the customer reads them (seller-block.tsx). Matched as
// substrings so a paraphrase fails.
const PLATFORM_STATEMENT = "J'Toye is the ordering platform"
const CANCELLATION_FRAGMENT = "regulation 28(1)(c)"
const BLOCK_HEADING = "Who you are buying from"

type Seller = {
  legalName: string
  entityType: string
  companyNumber?: string
  vatNumber?: string
  addressLines: string[]
  email?: string
  phone?: string
}

/** The seller the API publishes for the shop — the authority every page is compared with. */
async function sellerFromApi(request: APIRequestContext): Promise<Seller> {
  const res = await request.get(`${API}/api/v1/public/shops/${SHOP_SLUG}`)
  expect(res.ok(), `GET /public/shops/${SHOP_SLUG} answered ${res.status()} — is core-java up on ${API}?`).toBe(true)
  const shop = (await res.json()) as { seller?: Seller }
  expect(
    shop.seller,
    "PRECONDITION: the seeded shop has no seller object. Run the dev seeder or " +
      "scripts/seed-e2e-fixtures.sh (31.1-12) — a missing seller here is a fixture gap, not a pass"
  ).toBeTruthy()
  expect(shop.seller!.email, "PRECONDITION: a live shop must publish an email (D-20)").toBeTruthy()
  return shop.seller!
}

/** Served HTML as text: Next escapes the apostrophe and the ampersand. */
function asText(html: string): string {
  return html.replace(/&#x27;/g, "'").replace(/&#39;/g, "'").replace(/&amp;/g, "&").replace(/&quot;/g, '"')
}

async function screenshots(page: Page, testInfo: TestInfo, name: string) {
  for (const width of [390, 768, 1280]) {
    await page.setViewportSize({ width, height: 900 })
    await page.waitForTimeout(300)
    const path = testInfo.outputPath(`${name}-${width}.png`)
    await page.screenshot({ path, fullPage: true })
    await testInfo.attach(`${name}-${width}`, { path, contentType: "image/png" })
  }
}

type MailhogMessage = {
  Content?: { Headers?: Record<string, string[]>; Body?: string }
  MIME?: { Parts?: Array<{ Headers?: Record<string, string[]>; Body?: string }> | null } | null
}

function decodeBody(headers: Record<string, string[]> | undefined, body: string): string {
  const encoding = (headers?.["Content-Transfer-Encoding"]?.[0] || "").toLowerCase()
  if (encoding === "base64") return Buffer.from(body.replace(/\s+/g, ""), "base64").toString("utf8")
  if (encoding === "quoted-printable") {
    const bytes = body
      .replace(/=\r?\n/g, "")
      .replace(/=([0-9A-Fa-f]{2})/g, (_, hex: string) => String.fromCharCode(parseInt(hex, 16)))
    return Buffer.from(bytes, "latin1").toString("utf8")
  }
  return body
}

function messageText(message: MailhogMessage): string {
  const parts = message.MIME?.Parts ?? []
  const raw =
    parts.length > 0
      ? parts.map((p) => decodeBody(p.Headers, p.Body || "")).join("\n")
      : decodeBody(message.Content?.Headers, message.Content?.Body || "")
  return asText(raw)
}

/** Every message Mailhog holds for `address`, polled until one arrives or the deadline passes. */
async function messagesFor(request: APIRequestContext, address: string): Promise<MailhogMessage[]> {
  const deadline = Date.now() + 30_000
  for (;;) {
    const res = await request.get(`${MAILHOG}/api/v2/search?kind=to&query=${encodeURIComponent(address)}`)
    expect(res.ok(), `Mailhog search answered ${res.status()} — is Mailhog up on ${MAILHOG}?`).toBe(true)
    const items = ((await res.json()) as { items?: MailhogMessage[] }).items ?? []
    if (items.length > 0 || Date.now() > deadline) return items
    await new Promise((r) => setTimeout(r, 500))
  }
}

// The served bytes do not vary with viewport, so this half runs once (@desktop-only excludes it
// from the mobile project's enumeration rather than skipping it at runtime).
test.describe("#789 seller identity on the shop page — served HTML @desktop-only", () => {
  test("the server renders who the customer is buying from: legal name, address, email, platform and cancellation statements", async ({
    request,
  }) => {
    const seller = await sellerFromApi(request)
    const html = asText(await servedHtml(request, `/shop/${SHOP_SLUG}`))

    expect(html, "the seller block heading is not in the served HTML").toContain(BLOCK_HEADING)
    expect(html, "the legal name is not in the served HTML").toContain(seller.legalName)
    expect(html, "the geographic address is not in the served HTML").toContain(seller.addressLines.join(", "))
    expect(html, "the email is not in the served HTML").toContain(`mailto:${seller.email}`)
    if (seller.companyNumber) expect(html).toContain(seller.companyNumber)
    if (seller.vatNumber) expect(html).toContain(seller.vatNumber)
    expect(html, "the platform-not-seller statement is not in the served HTML").toContain(PLATFORM_STATEMENT)
    expect(html, "the cancellation statement is not in the served HTML").toContain(CANCELLATION_FRAGMENT)

    // Fixed order in the served bytes: name, address, email, platform, cancellation.
    const order = [
      BLOCK_HEADING,
      seller.legalName,
      seller.addressLines[0],
      `mailto:${seller.email}`,
      PLATFORM_STATEMENT,
      CANCELLATION_FRAGMENT,
    ].map((needle) => html.indexOf(needle, html.indexOf(BLOCK_HEADING)))
    expect(order.every((i) => i >= 0)).toBe(true)
    expect([...order].sort((a, b) => a - b)).toEqual(order)
  })
})

test.describe("#789 seller identity before purchase and on the confirmation", () => {
  test("the checkout shows the seller above Place order, and the cash confirmation and its email name them", async ({
    page,
    request,
  }, testInfo) => {
    const seller = await sellerFromApi(request)

    // The shop page, as a customer sees it.
    await page.goto(`/shop/${SHOP_SLUG}`)
    const shopBlock = page.getByRole("region", { name: BLOCK_HEADING })
    await shopBlock.scrollIntoViewIfNeeded()
    await expect(shopBlock).toContainText(seller.legalName)
    await screenshots(page, testInfo, "seller-shop-page")
    await page.setViewportSize({ width: 1280, height: 900 })

    // Basket above the shop minimum (bounded; same loop as cash-checkout-no-stripe.spec.ts).
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
    await page.getByText("Proceed to checkout").click()

    // BEFORE PURCHASE: the block is on the checkout and precedes Place order.
    const checkoutBlock = page.getByRole("region", { name: BLOCK_HEADING })
    await expect(checkoutBlock).toBeVisible({ timeout: 20_000 })
    await expect(checkoutBlock).toContainText(seller.legalName)
    await expect(checkoutBlock).toContainText(PLATFORM_STATEMENT)
    await expect(checkoutBlock).toContainText(CANCELLATION_FRAGMENT)
    const placeOrder = page.locator('button[type="submit"]:has-text("Place order")')
    const precedes = await checkoutBlock.evaluate(
      (block, button) => Boolean(block.compareDocumentPosition(button as Node) & Node.DOCUMENT_POSITION_FOLLOWING),
      await placeOrder.elementHandle()
    )
    expect(precedes, "the seller block must come before Place order").toBe(true)
    await checkoutBlock.scrollIntoViewIfNeeded()
    await screenshots(page, testInfo, "seller-checkout")
    await page.setViewportSize({ width: 1280, height: 900 })

    // A cash COLLECTION order.
    const address = `seller-789-${testInfo.project.name}-${Date.now()}@example.test`
    await page.getByRole("button", { name: /collection/i }).click()
    await page.fill("input#name", "Seller Identity 789")
    await page.fill("input#email", address)
    await page.fill("input#phone", "07700 789789")
    await expect(placeOrder).toBeEnabled({ timeout: 10_000 })
    const allergenAck = page.getByRole("checkbox", { name: /I have read the allergen information for this order\./i })
    await allergenAck.click()
    await expect(allergenAck).toBeChecked()
    await placeOrder.click()

    // THE CONFIRMATION the customer is left with.
    await expect(page.getByRole("heading", { name: "Order confirmed!" })).toBeVisible({ timeout: 15_000 })
    await expect(
      page.getByText(/Pay on collection/i),
      "this walk needs a CASH-ONLY shop; the stack under test has card payments configured"
    ).toBeVisible()
    const confirmationBlock = page.getByRole("region", { name: BLOCK_HEADING })
    await expect(confirmationBlock).toContainText(seller.legalName)
    await expect(confirmationBlock).toContainText(PLATFORM_STATEMENT)
    await expect(confirmationBlock).toContainText(CANCELLATION_FRAGMENT)
    await confirmationBlock.scrollIntoViewIfNeeded()
    await screenshots(page, testInfo, "seller-confirmation")

    // THE DURABLE MEDIUM: the confirmation email (implemented by 31.1-25; this is its acceptance).
    const messages = await messagesFor(request, address)
    expect(messages.length, `no confirmation email reached Mailhog for ${address}`).toBeGreaterThan(0)
    const text = messages.map(messageText).join("\n")
    expect(text, "the confirmation email does not name the seller's legal name").toContain(seller.legalName)
    expect(text, "the confirmation email does not say J'Toye is the ordering platform").toContain(
      "J'Toye is the ordering platform"
    )
  })
})
