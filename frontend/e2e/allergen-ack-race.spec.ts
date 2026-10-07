/**
 * #785 (phase 31.1, D-05): the vendor edits a product's allergens WHILE the customer is at checkout.
 *
 * THE RACE, AS THE PERSONA FOUND IT. A customer reads the allergen panel and ticks "I have read the
 * allergen information"; between that tick and "Place order" the vendor adds an allergen to one of
 * the basket's products. Before 31.1-03/31.1-15 the order was accepted and recorded an
 * acknowledgement of a set the customer was never shown. Now:
 *   - the server refuses the submit with 409 `allergen-acknowledgement-stale`, carrying the
 *     current set (31.1-03);
 *   - the checkout re-renders the panel from it, unticks the box, announces the change and places
 *     no order (31.1-15);
 *   - the customer reads it, ticks again, and the order is placed with the recorded set shown on
 *     the confirmation under both "You confirmed you had read" and "Recorded on your order" (D-08).
 *
 * WHY A BROWSER TEST AND NO STUBS. The claim spans the real server's refusal, the real checkout's
 * recovery and the real confirmation. Nothing is routed or faked: the compose stack, the seeded
 * shop, a real vendor edit through the product API.
 *
 * NON-VACUITY. "No 'Order confirmed' appeared" is equally true of a page that never submitted. So
 * the first order POST's RESPONSE is captured and asserted to be a 409 of the stale TYPE — not the
 * UI text alone — and the second POST is asserted to be the one that succeeded.
 *
 * THE EDIT IS RESTORED, BY CONTENT. The spec changes a shared demo product. Its original
 * `allergenMask` is restored in a `finally` and then READ BACK from the API and compared — a PUT
 * that returned 200 is not evidence the row holds the old value.
 *
 * WHAT IT NEEDS. The compose stack; a vendor password (E2E_VENDOR_PASSWORD or KC_SEED_USER_PASSWORD)
 * and the core-api client secret (KEYCLOAK_CLIENT_SECRET) — `set -a; . ./.env; set +a`. Without
 * either it SKIPS naming the fix (vendor-credentials.ts), rather than timing out as a fake failure.
 *
 * WHEN IT IS RUN. Listed (not run) by plan 31.1-15. Its RED run (the pre-rebuild runtime accepts the
 * stale order with no refusal) and GREEN run (rebuilt runtime) belong to plan 31.1-30: no running
 * image carries this branch before that plan's full rebuild, and a run against a stale image proves
 * nothing about the branch (CLAUDE.md, runtime parity).
 *
 * Run: npx playwright test e2e/allergen-ack-race.spec.ts
 */

import { test, expect, type APIRequestContext, type Page, type Response } from "@playwright/test"
import { CART_KEY_PREFIX } from "../lib/cart-identity"
import { ALLERGENS, getAllergenNames } from "../types/api"
import { VENDOR_PASSWORD, VENDOR_USERNAME, skipWithoutVendorPassword } from "./vendor-credentials"

// The deterministic seeded shop (DemoDataSeeder, tenant 00000000-…-0001 — the vendor's tenant).
const SHOP_SLUG = "mama-ades-kitchen"

// The core API and Keycloak as the TEST RUNNER sees them — the same env vars and defaults as
// storefront-flows.spec.ts. Separate services from the frontend, so not base-URL fallbacks.
const API = process.env.PLAYWRIGHT_API_URL || "http://localhost:9090"
const KEYCLOAK = process.env.PLAYWRIGHT_KEYCLOAK_URL || "http://localhost:8085"
const REALM = process.env.E2E_VENDOR_REALM || "jtoye-dev"
const CLIENT_ID = process.env.E2E_VENDOR_CLIENT_ID || "core-api"
const CLIENT_SECRET = process.env.KEYCLOAK_CLIENT_SECRET ?? ""

const STALE_TYPE_SUFFIX = "/allergen-acknowledgement-stale"
const MILK_BIT = 6

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

type ProductDto = Record<string, unknown> & { id: string; allergenMask: number; title: string }

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

async function readProduct(request: APIRequestContext, token: string, id: string): Promise<ProductDto> {
  const res = await request.get(`${API}/api/v1/products/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(res.status(), `GET /api/v1/products/${id}`).toBe(200)
  return (await res.json()) as ProductDto
}

async function writeMask(request: APIRequestContext, token: string, product: ProductDto, mask: number) {
  const body: Record<string, unknown> = {}
  for (const field of UPDATE_FIELDS) {
    if (product[field] !== undefined && product[field] !== null) body[field] = product[field]
  }
  body.allergenMask = mask
  const res = await request.put(`${API}/api/v1/products/${product.id}`, {
    headers: { Authorization: `Bearer ${token}` },
    data: body,
  })
  expect(res.status(), `PUT /api/v1/products/${product.id} (allergenMask ${mask}): ${await res.text()}`).toBe(200)
}

/** The basket lines as the CartProvider stored them, whatever envelope it wraps them in. */
async function basketProductIds(page: Page): Promise<string[]> {
  const raw = await page.evaluate((key) => window.localStorage.getItem(key), `${CART_KEY_PREFIX}${SHOP_SLUG}`)
  expect(raw, "no basket in localStorage after adding items").toBeTruthy()
  const parsed = JSON.parse(raw as string) as { items?: Array<{ productId?: string }> }
  return (parsed.items ?? []).map((i) => i.productId).filter((id): id is string => typeof id === "string")
}

function isOrderPost(res: Response): boolean {
  return (
    res.request().method() === "POST" && new URL(res.url()).pathname.endsWith(`/public/shops/${SHOP_SLUG}/orders`)
  )
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

test.describe("#785 the allergen acknowledgement race", () => {
  test("a vendor edit after the tick is refused 409, re-acknowledged, and recorded", async ({
    page,
    request,
  }, testInfo) => {
    skipWithoutVendorPassword()
    // The edit is to a SHARED demo product. With the config's default single worker the mobile and
    // desktop projects run one after the other; with more workers they would race each other's edit
    // and restore, so only the desktop project runs then (the screenshots still cover 390/768/1280).
    test.skip(
      testInfo.config.workers > 1 && testInfo.project.name !== "desktop",
      "PLAYWRIGHT_WORKERS > 1: two projects would edit and restore the same product concurrently"
    )
    test.skip(
      !CLIENT_SECRET,
      "No KEYCLOAK_CLIENT_SECRET — source the stack's .env (set -a; . ./.env; set +a) so the spec can mint a vendor token"
    )

    const token = await vendorToken(request)

    // 1. A basket of two products, added through the storefront's own Add buttons.
    await page.goto(`/shop/${SHOP_SLUG}`)
    await page.waitForLoadState("domcontentloaded")
    const addButtons = page.locator('button:has-text("Add")')
    await expect(addButtons.first()).toBeVisible({ timeout: 15_000 })
    // 'Add' becomes a stepper once a product is in the basket, so .first() moves on to the next
    // product each time. Bounded, so a broken Add button fails fast.
    const MAX_ADDS = 6
    let ids: string[] = []
    for (let adds = 0; adds < MAX_ADDS && ids.length < 2; adds++) {
      await addButtons.first().click()
      await page.waitForTimeout(400)
      ids = Array.from(new Set(await basketProductIds(page)))
    }
    expect(ids.length, "could not put two distinct products in the basket").toBeGreaterThanOrEqual(2)

    // 2. Read the basket's declared union from the API, and choose the allergen the vendor will add:
    //    Milk, as #785 is written, unless the basket already declares it — then the first bit it
    //    does not declare. Either way the edit CHANGES the union, which is what makes it a race.
    const before = await Promise.all(ids.map((id) => readProduct(request, token, id)))
    const unionBefore = before.reduce((m, p) => m | p.allergenMask, 0)
    const addedBit =
      (unionBefore & (1 << MILK_BIT)) === 0
        ? MILK_BIT
        : (ALLERGENS.find((a) => (unionBefore & (1 << a.bit)) === 0)?.bit ?? -1)
    expect(addedBit, "the basket already declares all 14 allergens; no edit can change its set").toBeGreaterThanOrEqual(0)
    const addedName = ALLERGENS.find((a) => a.bit === addedBit)!.name
    const target = before[0]
    const originalMask = target.allergenMask

    try {
      // 3. Checkout: read the panel, fill the form, tick.
      await page.goto(`/shop/${SHOP_SLUG}/checkout`)
      await page.waitForLoadState("domcontentloaded")
      const panel = page.getByTestId("order-allergen-panel")
      await expect(panel).toBeVisible({ timeout: 15_000 })
      const chips = panel.getByTestId("allergen-chip")
      const expectedBefore = getAllergenNames(unionBefore)
      if (expectedBefore.length > 0) {
        await expect(chips).toHaveText(expectedBefore, { timeout: 15_000 })
      }
      await expect(panel.getByText(addedName, { exact: true })).toHaveCount(0)

      await page.getByRole("button", { name: /collection/i }).click()
      await page.fill("input#name", "Allergen Race 785")
      await page.fill("input#email", "race-785@test.com")
      await page.fill("input#phone", "07700 785785")
      const ack = page.getByRole("checkbox", { name: /I have read the allergen information for this order\./i })
      await ack.click()
      await expect(ack).toBeChecked()

      // 4. THE RACE: the vendor adds the allergen to a basket product after the tick.
      await writeMask(request, token, target, originalMask | (1 << addedBit))

      // 5. Place order — and capture the response, so the refusal is asserted at the wire.
      const placeOrder = page.locator('button[type="submit"]:has-text("Place order")')
      await expect(placeOrder).toBeEnabled({ timeout: 10_000 })
      const firstPost = page.waitForResponse(isOrderPost, { timeout: 20_000 })
      await placeOrder.click()
      const refused = await firstPost

      // NON-VACUITY CONTROL: the submit happened, and was refused with the STALE type.
      expect(refused.status(), "the first order POST was not refused 409 — the stale acknowledgement was accepted").toBe(409)
      const problem = (await refused.json()) as { type?: string; currentAllergens?: string[] }
      expect(problem.type ?? "", "a 409 of a different type is not the stale-acknowledgement refusal").toMatch(
        new RegExp(`${STALE_TYPE_SUFFIX}$`)
      )
      expect(problem.currentAllergens ?? []).toContain(addedName)

      // 6. The checkout recovered: alert, unticked, the new allergen listed, no confirmation.
      await expect(panel.getByRole("alert")).toContainText(/allergen information for your basket has changed/i)
      await expect(ack).not.toBeChecked()
      await expect(panel.getByTestId("allergen-chip").filter({ hasText: addedName })).toHaveCount(1)
      await expect(page.getByRole("heading", { name: "Order confirmed!" })).toHaveCount(0)
      await expect(ack).toBeFocused()
      await shoot(page, testInfo, "allergen-ack-stale")

      // 7. Tick again and place: this time it is accepted.
      await page.setViewportSize({ width: 1280, height: 900 })
      await ack.click()
      await expect(ack).toBeChecked()
      const secondPost = page.waitForResponse(isOrderPost, { timeout: 20_000 })
      await placeOrder.click()
      const accepted = await secondPost
      expect(accepted.status(), "the re-acknowledged order was not accepted").toBeLessThan(300)

      await expect(page.getByRole("heading", { name: "Order confirmed!" })).toBeVisible({ timeout: 15_000 })
      await expect(page.getByTestId("recorded-ack")).toContainText("You confirmed you had read:")
      await expect(page.getByTestId("recorded-ack")).toContainText(addedName)
      await expect(page.getByTestId("recorded-set")).toContainText("Recorded on your order:")
      await expect(page.getByTestId("recorded-set")).toContainText(addedName)
      await shoot(page, testInfo, "allergen-ack-confirmation")
    } finally {
      // 8. Restore the shared demo product, then PROVE it by reading the row back.
      await writeMask(request, token, target, originalMask)
      const restored = await readProduct(request, token, target.id)
      expect(restored.allergenMask, `product ${target.id} was not restored to allergenMask ${originalMask}`).toBe(
        originalMask
      )
    }
  })
})
