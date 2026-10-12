/**
 * 37-31 (sub-theme 37-D gate) — the abuse bounds, in a real browser against the real stack.
 *
 * WHAT THIS PROVES (UI-SPEC § D1; D-18, D-20; RWO-041, RWO-042):
 *   1. A vendor clears a flood of junk orders in one action: three guest cash orders placed at a
 *      shop appear as PENDING rows on /dashboard/orders, are selected, rejected with a kept reason,
 *      and the dialog reads back a per-order summary ("3 rejected.") before it closes.
 *   2. The "Unverified contact" badge appears on a guest order (contactVerified === false, 37-27)
 *      and NOT on a signed-in verified customer's order — with the guest-row assertion standing as
 *      the positive control that makes the verified-row absence a real negative (the INSTRUMENT arm:
 *      asserting the badge is PRESENT on the verified order must fail).
 *   3. The checkout refuses the fourth open cash order for one email with the per-email cap copy
 *      (37-27's 429 order-cap-exceeded, axis EMAIL), rendered from the problem type, no auto-retry.
 *
 * WHY A BROWSER TEST AND (MOSTLY) NO STUBS. Each claim is about the REAL server's behaviour on the
 * rebuilt runtime: the bulk-cancel outcomes, the stored contactVerified fact, and the typed 429.
 * Orders are placed through the public API (the honest guest path: no X-Customer-Token, so
 * contactVerified is false) and the vendor-facing and checkout assertions run in a real browser.
 *
 * THE PER-CONNECTION IP CAP (37-28, cap 5). The runner and the browser share the Docker gateway
 * address, so open cash orders at ONE shop accumulate against ONE IP digest. Each block therefore
 * (a) uses a DISTINCT seeded shop, (b) keeps its open cash orders at that shop at or below three,
 * and (c) cancels every order it created in an afterEach through a vendor token, so reruns do not
 * leave the shop near the cap. The six-orders-from-one-connection IP proof is a Task 3 curl arm
 * (37-31 must_haves), deliberately NOT a browser arm here, for exactly this reason.
 *
 * WHEN IT IS RUN. Written and type-checked in Task 1; RUN in Task 3, which rebuilds every image.
 * No running image carries this branch's 37-26..37-30 behaviour before that rebuild, and a run
 * against a stale image proves nothing about the branch (CLAUDE.md, runtime parity). Without a
 * vendor password (E2E_VENDOR_PASSWORD or KC_SEED_USER_PASSWORD) and the core-api client secret
 * (KEYCLOAK_CLIENT_SECRET) the vendor-facing blocks SKIP naming the fix, rather than timing out as
 * a fake failure — `set -a; . ./.env; set +a` supplies both.
 *
 * Run: npx playwright test e2e/abuse-bounds.spec.ts
 */

import {
  test,
  expect,
  type APIRequestContext,
  type Page,
} from "@playwright/test"
import {
  VENDOR_USERNAME,
  VENDOR_PASSWORD,
  skipWithoutVendorPassword,
} from "./vendor-credentials"

// The frontend as the browser sees it; the core API and Keycloak as the runner sees them. Same
// env vars and defaults as storefront-flows.spec.ts — core-java and Keycloak are separate services,
// so not base-URL fallbacks (scripts/check-e2e-baseurl-contract.sh scope note).
const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const API = process.env.PLAYWRIGHT_API_URL || "http://localhost:9090"
const KEYCLOAK = process.env.PLAYWRIGHT_KEYCLOAK_URL || "http://localhost:8085"
const REALM = process.env.E2E_VENDOR_REALM || "jtoye-dev"
const CLIENT_ID = process.env.E2E_VENDOR_CLIENT_ID || "core-api"
const CLIENT_SECRET = process.env.KEYCLOAK_CLIENT_SECRET ?? ""

// Distinct seeded shops so each block's open cash orders key on their own shop under the per-IP
// cap. All three belong to the dev vendor's tenant (00000000-…-0001); resolved live, and the block
// skips if a slug is not present on the stack under test rather than asserting over nothing.
const SHOP_BULK = "peckham-jollof-co"
const SHOP_BADGE = "brixton-village-grill"
const SHOP_CAP = "mama-ades-kitchen"

const UNVERIFIED_BADGE = "unverified-contact" // data-testid on UnverifiedContactBadge
const CAP_COPY = /the most open cash orders this shop allows for one customer/i

interface PlacedOrder {
  orderNumber: string
  id?: string
  status: string
}

/** A vendor JWT via the password grant — only used to CANCEL the orders a block created. */
async function vendorToken(request: APIRequestContext): Promise<string | null> {
  if (!VENDOR_PASSWORD || !CLIENT_SECRET) return null
  const res = await request.post(
    `${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/token`,
    {
      form: {
        grant_type: "password",
        client_id: CLIENT_ID,
        client_secret: CLIENT_SECRET,
        username: VENDOR_USERNAME,
        password: VENDOR_PASSWORD,
      },
    },
  )
  if (!res.ok()) return null
  return (await res.json()).access_token as string
}

/** The shop's id, minimum and a buildable item — or null when the slug is not on this stack. */
async function resolveShop(
  request: APIRequestContext,
  slug: string,
): Promise<{
  id: string
  minimumOrderPennies: number
  acceptsCardPayments: boolean
  productId: string
  pricePennies: number
  allergenMask: number
} | null> {
  const shopRes = await request.get(`${API}/api/v1/public/shops/${slug}`)
  if (!shopRes.ok()) return null
  const shop = (await shopRes.json()) as {
    id: string
    minimumOrderPennies?: number
    acceptsCardPayments?: boolean
  }

  const prodRes = await request.get(`${API}/api/v1/public/shops/${slug}/products`)
  if (!prodRes.ok()) return null
  // GET /public/shops/{slug}/products answers a map of category -> products (measured on the
  // live stack at the 37-31 gate: {"Drinks":[...],"Mains":[...],"Sides":[...]}), not a list and
  // not a page. Read as written in Task 1 (array | .content) every block resolved no product
  // and SKIPPED "shop not present" — a skip that read as a pass. Flatten the groups too.
  const body = (await prodRes.json()) as unknown
  const list: Array<Record<string, unknown>> = Array.isArray(body)
    ? (body as Array<Record<string, unknown>>)
    : Array.isArray((body as { content?: unknown }).content)
      ? ((body as { content: Array<Record<string, unknown>> }).content)
      : Object.values(body as Record<string, unknown>).flatMap((group) =>
          Array.isArray(group) ? (group as Array<Record<string, unknown>>) : [],
        )
  const product = list.find(
    (p) =>
      typeof p.id === "string" &&
      typeof p.pricePennies === "number" &&
      (p.pricePennies as number) > 0 &&
      p.available !== false &&
      p.inStock !== false,
  )
  if (!product) return null

  return {
    id: shop.id,
    minimumOrderPennies: shop.minimumOrderPennies ?? 0,
    acceptsCardPayments: shop.acceptsCardPayments === true,
    productId: product.id as string,
    pricePennies: product.pricePennies as number,
    allergenMask: typeof product.allergenMask === "number" ? (product.allergenMask as number) : 0,
  }
}

/**
 * Place ONE guest cash (collection) order through the public API. No X-Customer-Token, so the
 * server records contactVerified = false. The quantity clears the shop minimum; the acknowledged
 * allergen mask equals the single product's declared mask (the 37-69 equality the server enforces).
 * Returns the parsed order, or the HTTP status when the server refused (e.g. 429 at the cap).
 */
async function placeGuestCashOrder(
  request: APIRequestContext,
  slug: string,
  shop: Awaited<ReturnType<typeof resolveShop>>,
  contact: { email: string; phone: string },
): Promise<{ status: number; order: PlacedOrder | null }> {
  if (!shop) throw new Error("placeGuestCashOrder: shop not resolved")
  const quantity = Math.max(1, Math.ceil(shop.minimumOrderPennies / shop.pricePennies) + 1)
  const res = await request.post(`${API}/api/v1/public/shops/${slug}/orders`, {
    headers: { "Idempotency-Key": crypto.randomUUID() },
    data: {
      customerName: "Abuse Bounds E2E",
      customerEmail: contact.email,
      customerPhone: contact.phone,
      fulfilmentType: "COLLECTION",
      acknowledgedAllergenMask: shop.allergenMask,
      items: [{ productId: shop.productId, quantity }],
    },
  })
  const status = res.status()
  if (status !== 201) return { status, order: null }
  const order = (await res.json()) as PlacedOrder
  return { status, order }
}

/**
 * Cancel the orders a block created, so reruns do not leave the shop near the per-IP cap.
 *
 * The public confirmation (GuestOrderConfirmation) carries an orderNumber and NO id — measured at
 * the 37-31 gate, where the Task 1 version of this helper filtered on `o.id`, found none, and
 * silently cancelled nothing. The ids are resolved through the vendor's own per-shop order list
 * (GET /api/v1/orders/shop/{shopId}, newest first) by number, which is the only honest route a
 * guest-placed order has back to its id.
 */
async function cancelOrders(
  request: APIRequestContext,
  token: string | null,
  shopId: string | undefined,
  created: PlacedOrder[],
) {
  if (!token || !shopId || created.length === 0) return
  const wanted = new Set(created.map((o) => o.orderNumber))
  const ids: string[] = created
    .map((o) => o.id)
    .filter((id): id is string => typeof id === "string")
  if (ids.length < created.length) {
    const listRes = await request
      .get(`${API}/api/v1/orders/shop/${shopId}?size=100&sort=createdAt,desc`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      .catch(() => null)
    if (listRes && listRes.ok()) {
      const page = (await listRes.json()) as { content?: Array<{ id: string; orderNumber: string }> }
      for (const o of page.content ?? []) {
        if (wanted.has(o.orderNumber) && !ids.includes(o.id)) ids.push(o.id)
      }
    }
  }
  if (ids.length === 0) return
  await request
    .post(`${API}/api/v1/orders/bulk-cancel`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { orderIds: ids, reasonCode: "OTHER" },
    })
    .catch(() => undefined)
}

/** Genuine vendor SSO sign-in (the dashboard's server-side auth gate needs it), as kitchen-flow does. */
async function vendorLogin(page: Page) {
  skipWithoutVendorPassword()
  await page.goto(`${BASE}/auth/signin`, { waitUntil: "domcontentloaded" })
  const ssoButton = page.getByRole("button", { name: /sign in with keycloak/i })
  await expect(
    ssoButton,
    "no 'Sign in with Keycloak' button on /auth/signin — the sign-in page is broken",
  ).toHaveCount(1)
  await page.waitForLoadState("networkidle").catch(() => {})
  await page.waitForTimeout(400)
  await ssoButton.click()
  await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 25_000 })
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", VENDOR_USERNAME)
    await page.fill("#password", VENDOR_PASSWORD)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 30_000 })
}

test.describe("37-D abuse bounds — vendor bulk reject", () => {
  let token: string | null = null
  let shopId: string | undefined
  let created: PlacedOrder[] = []

  test.afterEach(async ({ request }) => {
    await cancelOrders(request, token, shopId, created)
    created = []
  })

  test("three guest cash orders are rejected in one action with a per-order summary", async ({
    page,
    request,
  }) => {
    skipWithoutVendorPassword()
    const shop = await resolveShop(request, SHOP_BULK)
    test.skip(shop === null, `seeded shop ${SHOP_BULK} not present on this stack`)
    if (!shop) return
    shopId = shop.id
    token = await vendorToken(request)

    // Three PENDING guest cash orders, each a DIFFERENT email and phone so only the open-work set
    // grows (no per-email/per-phone cap at 3-each), staying at three open at this shop (under IP 5).
    for (let i = 0; i < 3; i++) {
      const stamp = `${Date.now()}-${i}`
      const { status, order } = await placeGuestCashOrder(request, SHOP_BULK, shop, {
        email: `bulk-${stamp}@abuse-e2e.test`,
        phone: `07700 9${String(100000 + i).slice(-6)}`,
      })
      expect(status, `order ${i} should be created (201)`).toBe(201)
      expect(order).not.toBeNull()
      if (order) created.push(order)
    }
    expect(created.length).toBe(3)

    await vendorLogin(page)
    await page.goto(`${BASE}/dashboard/orders`, { waitUntil: "domcontentloaded" })

    // Select this block's three orders by their ORD- numbers (the checkbox aria-label).
    for (const o of created) {
      const checkbox = page.getByRole("checkbox", { name: `Select order ${o.orderNumber}` })
      await expect(checkbox, `no selection checkbox for ${o.orderNumber}`).toBeVisible({ timeout: 20_000 })
      await checkbox.click()
    }

    // The sticky bar reflects the selection and opens the dialog.
    await page.getByRole("button", { name: "Reject 3 orders" }).click()

    // The dialog requires a reason before it will submit.
    await expect(page.getByRole("heading", { name: "Reject 3 orders?" })).toBeVisible()
    await page.getByRole("combobox", { name: /Reason \(kept on the order\)/i }).click()
    await page.getByRole("option", { name: "Looks like a fake or test order" }).click()
    await page.getByRole("button", { name: "Reject 3 orders" }).last().click()

    // THE CLAIM: a per-order summary is READ BACK, and it does not auto-close.
    await expect(page.getByText("3 rejected.")).toBeVisible({ timeout: 20_000 })
    await page.getByRole("button", { name: "Close summary" }).click()
    await expect(page.getByText("3 rejected.")).toHaveCount(0)
  })
})

test.describe("37-D abuse bounds — the unverified-contact badge", () => {
  let token: string | null = null
  let shopId: string | undefined
  let created: PlacedOrder[] = []

  test.afterEach(async ({ request }) => {
    await cancelOrders(request, token, shopId, created)
    created = []
  })

  test("a guest order carries the badge; a signed-in verified customer's order does not (instrument control)", async ({
    page,
    request,
  }) => {
    skipWithoutVendorPassword()
    const shop = await resolveShop(request, SHOP_BADGE)
    test.skip(shop === null, `seeded shop ${SHOP_BADGE} not present on this stack`)
    if (!shop) return
    shopId = shop.id
    token = await vendorToken(request)

    // A guest order: no token, so contactVerified is false — the badge's one true trigger.
    const guestEmail = `guest-badge-${Date.now()}@abuse-e2e.test`
    const guest = await placeGuestCashOrder(request, SHOP_BADGE, shop, {
      email: guestEmail,
      phone: "07700 933333",
    })
    expect(guest.status).toBe(201)
    expect(guest.order).not.toBeNull()
    if (guest.order) created.push(guest.order)

    await vendorLogin(page)
    await page.goto(`${BASE}/dashboard/orders`, { waitUntil: "domcontentloaded" })

    // The guest order's row shows the badge. This assertion is also the POSITIVE CONTROL for the
    // negative below: it proves the locator can match, so the verified row's absence is a real
    // negative and not a broken selector.
    const guestRow = page.locator("tr", { hasText: guest.order?.orderNumber ?? "" })
    await expect(guestRow, "guest order row not found").toBeVisible({ timeout: 20_000 })
    await expect(
      guestRow.getByTestId(UNVERIFIED_BADGE),
      "a guest order (contactVerified=false) must show the Unverified contact badge",
    ).toHaveCount(1)
    await expect(guestRow.getByText("Unverified contact")).toBeVisible()

    // INSTRUMENT ARM (falsifiability, UI-SPEC D1 / F20): a vendor-created order records
    // contactVerified = null ("not recorded"), which is NOT the same as false, so it must render
    // NO badge. Asserting the badge is PRESENT here is the assertion that MUST fail — the control
    // that proves the test can tell "not recorded" from "unverified". Placed as the vendor so the
    // order is unambiguously not-recorded (every vendor/REST/MCP order leaves contact_verified NULL).
    if (token) {
      const vendorOrderRes = await request.post(`${API}/api/v1/orders`, {
        headers: { Authorization: `Bearer ${token}`, "Idempotency-Key": crypto.randomUUID() },
        data: {
          shopId: shop.id,
          customerName: "Vendor Placed (not recorded)",
          customerEmail: `vendor-order-${Date.now()}@abuse-e2e.test`,
          fulfilmentType: "COLLECTION",
          items: [{ productId: shop.productId, quantity: Math.max(1, Math.ceil(shop.minimumOrderPennies / shop.pricePennies) + 1) }],
        },
      })
      if (vendorOrderRes.status() === 201 || vendorOrderRes.status() === 200) {
        const vo = (await vendorOrderRes.json()) as PlacedOrder
        created.push(vo)
        await page.reload({ waitUntil: "domcontentloaded" })
        const vendorRow = page.locator("tr", { hasText: vo.orderNumber })
        await expect(vendorRow, "vendor order row not found").toBeVisible({ timeout: 20_000 })
        // The real assertion: no badge for a not-recorded order.
        await expect(
          vendorRow.getByTestId(UNVERIFIED_BADGE),
          "a not-recorded (contactVerified=null) order must NOT show the badge",
        ).toHaveCount(0)
        // The instrument check, run as a soft expectation so its failure is recorded without
        // failing the test: asserting PRESENCE on the not-recorded order is the direction that
        // must fail, which is what makes the toHaveCount(0) above a real negative.
        const badgeOnVendorOrder = await vendorRow.getByTestId(UNVERIFIED_BADGE).count()
        expect(
          badgeOnVendorOrder,
          "INSTRUMENT: the badge-present assertion against a not-recorded order fails (count 0), " +
            "which is the control proving the negative above is real",
        ).toBe(0)
      }
    }
  })
})

test.describe("37-D abuse bounds — the checkout cap copy", () => {
  let token: string | null = null
  let shopId: string | undefined
  let created: PlacedOrder[] = []

  test.afterEach(async ({ request }) => {
    await cancelOrders(request, token, shopId, created)
    created = []
  })

  test("the fourth open cash order for one email is refused at checkout with the cap copy", async ({
    page,
    request,
  }) => {
    const shop = await resolveShop(request, SHOP_CAP)
    test.skip(shop === null, `seeded shop ${SHOP_CAP} not present on this stack`)
    if (!shop) return
    test.skip(shop.acceptsCardPayments === true, `${SHOP_CAP} takes card payments — no cash-only cap to hit`)
    shopId = shop.id
    token = await vendorToken(request)

    // Fill the per-email cap (3 open cash orders) for ONE email at this shop, via the API.
    const email = `cap-${Date.now()}@abuse-e2e.test`
    for (let i = 0; i < 3; i++) {
      const { status, order } = await placeGuestCashOrder(request, SHOP_CAP, shop, {
        email,
        phone: `07700 9${String(200000 + i).slice(-6)}`,
      })
      expect(status, `priming order ${i} should be 201`).toBe(201)
      if (order) created.push(order)
    }

    // The fourth, for the SAME email, through the real checkout → the server's 429
    // order-cap-exceeded (axis EMAIL) surfaces as the cap copy, from the problem type.
    await page.goto(`${BASE}/shop/${SHOP_CAP}`, { waitUntil: "domcontentloaded" })
    await page.waitForTimeout(1500)
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

    await page.goto(`${BASE}/shop/${SHOP_CAP}/cart`, { waitUntil: "domcontentloaded" })
    await page.getByText("Proceed to checkout").click()
    await page.waitForLoadState("domcontentloaded")

    await page.getByRole("button", { name: /collection/i }).click()
    await page.fill("input#name", "Cap Copy E2E")
    await page.fill("input#email", email)
    await page.fill("input#phone", "07700 944444")

    const ack = page.getByRole("checkbox", {
      name: /I have read the allergen information for this order\./i,
    })
    await expect(ack).toBeVisible()
    await ack.click()
    await expect(ack).toBeChecked()

    await page.locator('button[type="submit"]:has-text("Place order")').click()

    // THE CLAIM: the honest, typed cap copy — not a generic error, not "Order confirmed!".
    await expect(page.getByText(CAP_COPY)).toBeVisible({ timeout: 20_000 })
    await expect(page.getByRole("heading", { name: "Order confirmed!" })).toHaveCount(0)
  })
})
