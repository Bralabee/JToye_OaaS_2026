/**
 * UXT-007 (#783, P0; Phase 37-34, D-16, RWO-007): the vendor reprices a product WHILE the customer is
 * at checkout.
 *
 * THE RACE, AS THE PERSONA FOUND IT. A customer opens checkout with Suya at £9.00 on the button;
 * between that render and "Place order" the vendor raises the price. Before 37-32/37-33 the order
 * was placed at the higher total the customer never saw (the server always charged its own price —
 * goods P2-KYL-P2 — but said nothing). Now:
 *   - the submit carries the prices the page showed, and the server refuses the difference with
 *     409 `basket-changed` naming each change (37-33);
 *   - the checkout shows "Your basket has changed since you opened it" with the row
 *     "{Item} · £{was} → £{now}", rewrites the stored basket to the server's values, and refuses
 *     Place order until "Accept changes" is pressed (37-34, UI-SPEC § C1);
 *   - the customer accepts and places the order ONCE, at the new total, under the SAME
 *     Idempotency-Key (goods P2-CHA-12/13, P2-RAV-20).
 *
 * WHY A BROWSER TEST AND NO STUBS. The claim spans the real server's refusal, the real checkout's
 * recovery and the real order that results. Nothing is routed or faked: the compose stack, a seeded
 * shop, a real vendor edit through the product API, the orders counted through the vendor API.
 *
 * NON-VACUITY, THREE WAYS. "The panel appeared" is asserted at the WIRE first (the first order POST's
 * response is a 409 of the basket-changed TYPE carrying PRICE_CHANGED with the two prices), the
 * second tap before accepting is shown to make NO POST (a response counter, not the absence of a
 * confirmation), and the INSTRUMENT arm below runs the identical flow WITHOUT the vendor edit: the
 * panel must NOT appear and the first POST must be accepted — so the panel assertion in the race arm
 * is one that can fail.
 *
 * THE EDIT IS RESTORED, BY CONTENT. The spec changes a shared demo product's price. The original
 * `pricePennies` is restored in a `finally` and then READ BACK from the API and compared — a PUT
 * that returned 200 is not evidence the row holds the old value.
 *
 * THE SENTENCE IS COMPUTED, NOT COPIED. The plan's "Suya · £9.00 → £14.00" is the integration
 * test's fixture (37-33). The seeded menu has no £9.00 Suya: Peckham Jollof Co sells "Suya Platter"
 * at £12.00 (DemoDataSeeder), so the expected row is built from the LIVE price and the £5.00 the
 * spec adds, and the product is resolved by title. A different seed changes the numbers, not the
 * claim.
 *
 * HYGIENE (e2e/order-cleanup.ts, the 37-31 rule): per-run contact details, and every order this
 * spec places is cancelled in `afterEach` by number through the vendor's own cancel, so the cash
 * caps it runs under (37-27/37-28) never count its leftovers.
 *
 * WHAT IT NEEDS. The compose stack REBUILT with 37-32..37-34 (the 37-D images refuse nothing of
 * this kind: the 409 does not exist there, so the race arm reads a 201 and the panel never shows —
 * that run is this spec's RED, recorded by the 37-C gate before the rebuild); a vendor password
 * (E2E_VENDOR_PASSWORD or KC_SEED_USER_PASSWORD) and the core-api client secret
 * (KEYCLOAK_CLIENT_SECRET) — `set -a; . ./.env; set +a`. Without either it SKIPS naming the fix
 * (vendor-credentials.ts), rather than timing out as a fake failure.
 *
 * WHEN IT IS RUN. Written, type-checked and listed by plan 37-34. Its RED run (the pre-rebuild
 * runtime places the order at the new total with no refusal) and GREEN run (rebuilt runtime) belong
 * to plan 37-38, the 37-C gate: no running image carries this branch before that plan's full
 * rebuild, and a run against a stale image proves nothing about the branch (CLAUDE.md, runtime
 * parity; the 37-30 -> 37-31 precedent).
 *
 * Run: npx playwright test e2e/basket-revalidation.spec.ts
 */

import { test, expect, type APIRequestContext, type Page, type Response } from "@playwright/test"
import { CART_KEY_PREFIX } from "../lib/cart-identity"
import { VENDOR_PASSWORD, VENDOR_USERNAME, skipWithoutVendorPassword } from "./vendor-credentials"
import { cancelOrdersByNumber, uniqueContact, vendorShopId } from "./order-cleanup"
import { awaitCashConfirmation, confirmedHeading } from "./cash-confirmation"

// The seeded shop with a Suya on its menu (DemoDataSeeder: "Suya Platter", Mains, 1200). The
// product is resolved by title at run time; the slug is the only fixture.
const SHOP_SLUG = "peckham-jollof-co"
const PRODUCT_TITLE_PATTERN = /suya/i
/** The reprice: £5.00 on top of whatever the live price is. */
const PRICE_RISE_PENNIES = 500

// The core API and Keycloak as the TEST RUNNER sees them — the same env vars and defaults as
// storefront-flows.spec.ts. Separate services from the frontend, so not base-URL fallbacks.
const API = process.env.PLAYWRIGHT_API_URL || "http://localhost:9090"
const KEYCLOAK = process.env.PLAYWRIGHT_KEYCLOAK_URL || "http://localhost:8085"
const REALM = process.env.E2E_VENDOR_REALM || "jtoye-dev"
const CLIENT_ID = process.env.E2E_VENDOR_CLIENT_ID || "core-api"
const CLIENT_SECRET = process.env.KEYCLOAK_CLIENT_SECRET ?? ""

const BASKET_CHANGED_TYPE_SUFFIX = "/basket-changed"

// The C1 copy, as components/storefront/basket-changed-panel.tsx exports it. Quoted here rather
// than imported: that module is a client component (lucide, the Button, `@/` aliases) and the
// Playwright loader is not the Next bundler. The jest suites hold the constants to these strings.
const HEADING_COPY = "Your basket has changed since you opened it"
const ACCEPT_LABEL = "Accept changes"
const REFUSAL_COPY = "Review the changes to your basket, then press Accept changes."
const UPDATED_NOTE_COPY = "Basket updated. Check the new total before you place your order."

/** The PUT body is a full CreateProductRequest; these are its fields, copied from the ProductDto. */
const UPDATE_FIELDS = [
  "sku",
  "title",
  "ingredientsText",
  "allergenMask",
  "mayContainMask",
  "pricePennies",
  "vatRate",
  "description",
  "imageUrl",
  "category",
  "displayOrder",
  "available",
  "featured",
  "preparationTimeMinutes",
  "dietaryTags",
  "shopId",
  "quantityInStock",
  "shelfLifeDays",
  "durabilityType",
] as const

type ProductDto = Record<string, unknown> & { id: string; title: string; pricePennies: number }

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

async function vendorToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/token`, {
    form: {
      grant_type: "password",
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      username: VENDOR_USERNAME,
      password: VENDOR_PASSWORD,
    },
  })
  expect(res.status(), `Keycloak refused the vendor token request (${REALM}/${CLIENT_ID})`).toBe(200)
  const token = ((await res.json()) as { access_token?: string }).access_token
  expect(token, "the token response carried no access_token").toBeTruthy()
  return token as string
}

/**
 * The public menu is a map of category -> products (measured at the 37-31 gate; the Task 1 version
 * of abuse-bounds read it as a list and resolved nothing). Flatten the groups, then pick by title.
 */
async function resolvePublicProduct(request: APIRequestContext): Promise<{ id: string; title: string; pricePennies: number }> {
  const res = await request.get(`${API}/api/v1/public/shops/${SHOP_SLUG}/products`)
  expect(res.status(), `GET /public/shops/${SHOP_SLUG}/products`).toBe(200)
  const body = (await res.json()) as unknown
  const list: Array<Record<string, unknown>> = Array.isArray(body)
    ? (body as Array<Record<string, unknown>>)
    : Object.values(body as Record<string, unknown>).flatMap((group) =>
        Array.isArray(group) ? (group as Array<Record<string, unknown>>) : [],
      )
  const match =
    list.find((p) => typeof p.title === "string" && PRODUCT_TITLE_PATTERN.test(p.title) && p.inStock !== false) ??
    list.find((p) => typeof p.pricePennies === "number" && (p.pricePennies as number) > 0 && p.inStock !== false)
  expect(match, `no orderable product on ${SHOP_SLUG}'s public menu`).toBeTruthy()
  const product = match as Record<string, unknown>
  expect(typeof product.id).toBe("string")
  expect(typeof product.pricePennies).toBe("number")
  return { id: product.id as string, title: product.title as string, pricePennies: product.pricePennies as number }
}

async function readProduct(request: APIRequestContext, token: string, id: string): Promise<ProductDto> {
  const res = await request.get(`${API}/api/v1/products/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(res.status(), `GET /api/v1/products/${id}`).toBe(200)
  return (await res.json()) as ProductDto
}

/** The vendor's edit: the same full PUT the dashboard sends, with the price changed. */
async function writePrice(request: APIRequestContext, token: string, product: ProductDto, pricePennies: number) {
  const body: Record<string, unknown> = {}
  for (const field of UPDATE_FIELDS) {
    if (product[field] !== undefined && product[field] !== null) body[field] = product[field]
  }
  body.pricePennies = pricePennies
  const res = await request.put(`${API}/api/v1/products/${product.id}`, {
    headers: { Authorization: `Bearer ${token}` },
    data: body,
  })
  expect(res.status(), `PUT /api/v1/products/${product.id} (pricePennies ${pricePennies}): ${await res.text()}`).toBe(200)
}

/** The basket lines as the CartProvider stored them, whatever envelope it wraps them in. */
async function basketProductIds(page: Page): Promise<string[]> {
  const raw = await page.evaluate((key) => window.localStorage.getItem(key), `${CART_KEY_PREFIX}${SHOP_SLUG}`)
  if (!raw) return []
  const parsed = JSON.parse(raw) as { items?: Array<{ productId?: string }> }
  return (parsed.items ?? []).map((i) => i.productId).filter((id): id is string => typeof id === "string")
}

/** The stored basket's owner stamp and the price it holds for `productId` (asserted by content, F15). */
async function storedBasket(page: Page): Promise<{ owner: unknown; prices: Record<string, number> }> {
  const raw = await page.evaluate((key) => window.localStorage.getItem(key), `${CART_KEY_PREFIX}${SHOP_SLUG}`)
  expect(raw, "no basket in localStorage").toBeTruthy()
  const parsed = JSON.parse(raw as string) as { owner?: unknown; items?: Array<{ productId: string; pricePennies: number }> }
  const prices: Record<string, number> = {}
  for (const line of parsed.items ?? []) prices[line.productId] = line.pricePennies
  return { owner: parsed.owner, prices }
}

function isOrderPost(res: Response): boolean {
  const path = new URL(res.url()).pathname
  return (
    res.request().method() === "POST" &&
    (path.endsWith(`/public/shops/${SHOP_SLUG}/orders`) || path === "/api/customer-orders")
  )
}

/** Every order this contact placed at the shop, through the vendor's own list. */
async function ordersFor(request: APIRequestContext, token: string, email: string): Promise<Array<{ orderNumber: string; totalAmountPennies: number }>> {
  const shopId = await vendorShopId(request, token, SHOP_SLUG)
  expect(shopId, `the vendor's shop list has no ${SHOP_SLUG}`).toBeTruthy()
  const res = await request.get(`${API}/api/v1/orders/shop/${shopId}?size=100&sort=createdAt,desc`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(res.status(), `GET /api/v1/orders/shop/${shopId}`).toBe(200)
  const page = (await res.json()) as { content?: Array<{ orderNumber: string; customerEmail?: string; totalAmountPennies: number }> }
  return (page.content ?? []).filter((o) => o.customerEmail === email)
}

async function shoot(page: Page, testInfo: import("@playwright/test").TestInfo, name: string) {
  for (const width of [390, 768, 1280]) {
    await page.setViewportSize({ width, height: 900 })
    await page.waitForTimeout(300)
    const path = testInfo.outputPath(`${name}-${width}.png`)
    await page.screenshot({ path, fullPage: true })
    await testInfo.attach(`${name}-${width}`, { path, contentType: "image/png" })
  }
}

/** Shop -> add the product -> checkout -> collection, per-run contact, tick. Returns the contact. */
async function openCheckoutWith(page: Page, product: { id: string; title: string }, tag: string) {
  await page.goto(`/shop/${SHOP_SLUG}`)
  await page.waitForLoadState("domcontentloaded")
  // 31.1-21 (A11Y-4): the Add button is named for its dish, so the actor adds THIS one.
  const add = page.getByRole("button", { name: `Add ${product.title} to basket` }).first()
  await expect(add).toBeVisible({ timeout: 15_000 })
  await add.click()
  await expect.poll(() => basketProductIds(page), { timeout: 10_000 }).toContain(product.id)

  await page.goto(`/shop/${SHOP_SLUG}/checkout`)
  await page.waitForLoadState("domcontentloaded")
  await expect(page.getByTestId("order-allergen-panel")).toBeVisible({ timeout: 15_000 })
  await page.getByRole("button", { name: /collection/i }).click()
  const contact = uniqueContact(tag)
  await page.fill("input#name", "Basket Revalidation 007")
  await page.fill("input#email", contact.email)
  await page.fill("input#phone", contact.phone)
  const ack = page.getByRole("checkbox", { name: /I have read the allergen information for this order\./i })
  await ack.click()
  await expect(ack).toBeChecked()
  return contact
}

test.describe("UXT-007: a vendor reprice between render and submit", () => {
  const placed: string[] = []
  test.afterEach(async ({ request }) => {
    await cancelOrdersByNumber(request, SHOP_SLUG, placed.splice(0))
  })

  test("is shown as '{Item} · £was → £now', refused until accepted, then placed once at the new total under the same key", async ({
    page,
    request,
  }, testInfo) => {
    skipWithoutVendorPassword()
    // The edit is to a SHARED demo product. With the config's default single worker the mobile and
    // desktop projects run one after the other; with more workers they would race each other's edit
    // and restore, so only the desktop project runs then (the screenshots still cover 390/768/1280).
    test.skip(
      testInfo.config.workers > 1 && testInfo.project.name !== "desktop",
      "PLAYWRIGHT_WORKERS > 1: two projects would edit and restore the same product concurrently",
    )
    test.skip(
      !CLIENT_SECRET,
      "No KEYCLOAK_CLIENT_SECRET — source the stack's .env (set -a; . ./.env; set +a) so the spec can mint a vendor token",
    )

    const token = await vendorToken(request)
    const product = await resolvePublicProduct(request)
    const before = await readProduct(request, token, product.id)
    const originalPrice = before.pricePennies
    const newPrice = originalPrice + PRICE_RISE_PENNIES
    const expectedRow = `${product.title} · ${formatPrice(originalPrice)} → ${formatPrice(newPrice)}`

    // Every order POST the page makes, counted at the wire so "no second POST" is a reading.
    const orderPosts: Response[] = []
    page.on("response", (res) => {
      if (isOrderPost(res)) orderPosts.push(res)
    })

    try {
      // 1. Checkout at the price the customer saw: the button carries the original total.
      const contact = await openCheckoutWith(page, product, "reval-007")
      const placeOrder = page.locator('button[type="submit"]:has-text("Place order")')
      await expect(placeOrder).toContainText(formatPrice(originalPrice), { timeout: 10_000 })
      const stampBefore = JSON.stringify((await storedBasket(page)).owner)

      // 2. THE RACE: the vendor raises the price after the page rendered it.
      await writePrice(request, token, before, newPrice)

      // 3. Place order — captured at the wire: a 409 of the basket-changed TYPE naming the change.
      const firstPost = page.waitForResponse(isOrderPost, { timeout: 20_000 })
      await placeOrder.click()
      const refused = await firstPost
      expect(refused.status(), "the first order POST was not refused 409 — the stale price was accepted").toBe(409)
      const problem = (await refused.json()) as {
        type?: string
        changes?: Array<{ kind?: string; productId?: string; expected?: number; current?: number }>
      }
      expect(problem.type ?? "", "a 409 of a different type is not the basket-changed refusal").toMatch(
        new RegExp(`${BASKET_CHANGED_TYPE_SUFFIX}$`),
      )
      const priceChange = (problem.changes ?? []).find((c) => c.kind === "PRICE_CHANGED" && c.productId === product.id)
      expect(priceChange, "the 409 carried no PRICE_CHANGED entry for the repriced product").toBeTruthy()
      expect(priceChange?.expected).toBe(originalPrice)
      expect(priceChange?.current).toBe(newPrice)

      // 4. The panel: role=alert, the heading focused, the exact row, nothing confirmed.
      const panel = page.getByTestId("basket-changed-panel")
      await expect(panel).toBeVisible({ timeout: 10_000 })
      await expect(panel).toHaveAttribute("role", "alert")
      const heading = panel.getByRole("heading", { level: 2, name: HEADING_COPY })
      await expect(heading).toBeFocused()
      await expect(panel.getByTestId("basket-change-row").first()).toContainText(expectedRow)
      await expect(confirmedHeading(page)).toHaveCount(0)
      await expect(page, "the page left the checkout, so an order was confirmed").toHaveURL(/\/checkout(\?|$)/)
      // The stored basket already holds the new price — and the same owner stamp it held before (F15).
      await expect.poll(async () => (await storedBasket(page)).prices[product.id]).toBe(newPrice)
      expect(JSON.stringify((await storedBasket(page)).owner)).toBe(stampBefore)
      await shoot(page, testInfo, "basket-changed")
      await page.setViewportSize({ width: 1280, height: 900 })

      // 5. Place order AGAIN before accepting: refused in words, focus on Accept changes, no POST.
      await expect(placeOrder).toBeEnabled()
      await placeOrder.click()
      await expect(page.getByText(REFUSAL_COPY)).toBeVisible()
      await expect(page.getByRole("button", { name: ACCEPT_LABEL })).toBeFocused()
      await page.waitForTimeout(500)
      expect(orderPosts.length, "a second order POST was made before the changes were accepted").toBe(1)

      // 6. Accept: the panel collapses to the note and the button shows the NEW total.
      await page.getByRole("button", { name: ACCEPT_LABEL }).click()
      await expect(page.getByText(UPDATED_NOTE_COPY)).toBeVisible()
      await expect(placeOrder).toContainText(formatPrice(newPrice))

      // 7. Place order: accepted this time, under the SAME Idempotency-Key.
      const secondPost = page.waitForResponse(isOrderPost, { timeout: 20_000 })
      await placeOrder.click()
      const accepted = await secondPost
      expect(accepted.status(), "the resubmit at the accepted price was not accepted").toBeLessThan(300)
      expect(accepted.request().headers()["idempotency-key"]).toBe(refused.request().headers()["idempotency-key"])
      // 37-37 (C3): the cash confirmation is the order's own route; its number is read from the URL.
      const number = await awaitCashConfirmation(page)
      expect(number, "no order number on the confirmation").toBeTruthy()
      placed.push(number as string)
      await shoot(page, testInfo, "basket-changed-confirmation")

      // 8. Exactly one order exists for this contact, and it is at the new total (goods P2-CHA-12/13, P2-RAV-20).
      const orders = await ordersFor(request, token, contact.email)
      expect(orders.map((o) => o.orderNumber)).toEqual([number])
      expect(orders[0].totalAmountPennies).toBe(newPrice)
      expect(orderPosts.length).toBe(2)
    } finally {
      // 9. Restore the shared demo product, then PROVE it by reading the row back.
      await writePrice(request, token, before, originalPrice)
      const restored = await readProduct(request, token, product.id)
      expect(restored.pricePennies, `product ${product.id} was not restored to pricePennies ${originalPrice}`).toBe(originalPrice)
    }
  })

  test("INSTRUMENT: with the vendor edit skipped the panel does not appear and the first POST is accepted", async ({
    page,
    request,
  }, testInfo) => {
    skipWithoutVendorPassword()
    test.skip(
      testInfo.config.workers > 1 && testInfo.project.name !== "desktop",
      "PLAYWRIGHT_WORKERS > 1: keep the order count per shop within the caps",
    )
    test.skip(!CLIENT_SECRET, "No KEYCLOAK_CLIENT_SECRET — source the stack's .env so the spec can cancel what it places")

    const token = await vendorToken(request)
    const product = await resolvePublicProduct(request)
    const contact = await openCheckoutWith(page, product, "reval-ctl")
    const placeOrder = page.locator('button[type="submit"]:has-text("Place order")')
    await expect(placeOrder).toContainText(formatPrice(product.pricePennies), { timeout: 10_000 })

    // No vendor edit. The same tap, the same page: no refusal, no panel, one accepted POST.
    const firstPost = page.waitForResponse(isOrderPost, { timeout: 20_000 })
    await placeOrder.click()
    const accepted = await firstPost
    expect(accepted.status(), "the unchanged basket was refused").toBeLessThan(300)
    // 37-37 (C3): the cash confirmation is the order's own route; its number is read from the URL.
    const number = await awaitCashConfirmation(page)
    await expect(page.getByTestId("basket-changed-panel")).toHaveCount(0)
    expect(number).toBeTruthy()
    placed.push(number as string)
    const orders = await ordersFor(request, token, contact.email)
    expect(orders.map((o) => o.orderNumber)).toEqual([number])
    expect(orders[0].totalAmountPennies).toBe(product.pricePennies)
  })
})
