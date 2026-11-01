/**
 * Phase 30 PAY-01 (plan 30-22) — Vendor Refund E2E against real Stripe test mode.
 *
 * WHY THIS IS A SEPARATE SPEC FROM vendor-refund-flow.spec.ts:
 *   Default Playwright runs (and Phase 1 of e2e-nightly.yml) run against a cash-only
 *   core-java (STRIPE_API_KEY empty) so cash-checkout-no-stripe.spec.ts (#793) can
 *   prove a cash checkout loads zero Stripe resources. A refund spec inside the
 *   default collection therefore had to call runtime skip when no CAPTURED order
 *   existed, which either hid broken refund wiring as a skip or tripped the
 *   declared skip budget (S-2).
 *
 *   playwright.config.ts ignores stripe-*.spec.ts unless STRIPE_E2E === "1".
 *   When STRIPE_E2E=1 is set (Phase 2 of e2e-nightly.yml, after stripe listen
 *   forwards webhooks and core-java is recreated with STRIPE_API_KEY +
 *   STRIPE_WEBHOOK_SECRET), this spec is collected and MUST NOT SKIP: every
 *   missing prerequisite is a hard failure.
 *
 * Selector contract with OrderDetailPanel / RefundModal:
 *   - Refund button:     button text "Issue refund"
 *   - Amount input:      #amountPounds
 *   - Reason select:     #reason
 *   - Note textarea:     #note
 *   - Refund-in-flight:  button text "Refunding…"
 *   - Refund history:    heading "Refunds (1)" (exactly one refund) and its "£1.00" row
 *   - Order status:      badge text "Refunded" (OrderDetailPanel STATUS_CONFIG.REFUNDED; any
 *                        refund moves the order to REFUNDED, RefundService step 8)
 *   - Detail route URL:  /dashboard/orders/<uuid>
 *
 * THE STRIPE SECRET NEVER ENTERS PLAYWRIGHT (T-30-87): every api.stripe.com call goes through
 * ./stripe-test-api (Node fetch), and this file turns traces and video off at the top level.
 * Why both, and what each one closes, is in that module's header.
 */

import { test, expect, type APIRequestContext, type Page } from "@playwright/test"
import { writeFileSync } from "node:fs"
import {
  STRIPE_SPEC_ARTIFACTS,
  pollRefundReadback,
  requireStripeTestSecretKey,
  stripeTestClient,
  type StripeRefundList,
} from "./stripe-test-api"
import {
  VENDOR_USERNAME,
  VENDOR_PASSWORD,
  NO_VENDOR_PASSWORD_REASON,
} from "./vendor-credentials"
import {
  cancelOrdersByNumber,
  uniqueContact,
  vendorShopId,
  vendorToken,
} from "./order-cleanup"

// Top level, not inside the describe: trace and video are worker options (T-30-87).
test.use(STRIPE_SPEC_ARTIFACTS)

const API = process.env.PLAYWRIGHT_API_URL || "http://localhost:9090"
const SHOP_SLUG = "mama-ades-kitchen"
const REFUND_PENNIES = 100

function intentIdFromClientSecret(clientSecret: string): string {
  const idx = clientSecret.indexOf("_secret_")
  if (!clientSecret.startsWith("pi_") || idx <= 3) {
    throw new Error(
      "GuestOrderConfirmation.clientSecret is missing or not of the form pi_..._secret_... — " +
        "is core-java started with STRIPE_API_KEY=sk_test_...?",
    )
  }
  return clientSecret.slice(0, idx)
}

async function resolveShopMenu(request: APIRequestContext): Promise<{
  minimumOrderPennies: number
  productId: string
  pricePennies: number
  allergenMask: number
}> {
  const shopRes = await request.get(`${API}/public/shops/${SHOP_SLUG}`)
  expect(shopRes.status(), `GET /public/shops/${SHOP_SLUG}`).toBe(200)
  const shop = (await shopRes.json()) as {
    minimumOrderPennies?: number
    acceptsCardPayments?: boolean
  }
  expect(
    shop.acceptsCardPayments,
    `${SHOP_SLUG} has acceptsCardPayments !== true — core-java does not have STRIPE_API_KEY configured`,
  ).toBe(true)

  const menuRes = await request.get(`${API}/public/shops/${SHOP_SLUG}/products`)
  expect(menuRes.status(), `GET /public/shops/${SHOP_SLUG}/products`).toBe(200)
  const body = (await menuRes.json()) as unknown
  const list: Array<Record<string, unknown>> = Array.isArray(body)
    ? (body as Array<Record<string, unknown>>)
    : Array.isArray((body as { content?: unknown }).content)
      ? (body as { content: Array<Record<string, unknown>> }).content
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
  expect(product, `${SHOP_SLUG} has no available priced product`).toBeTruthy()
  const p = product as Record<string, unknown>
  return {
    minimumOrderPennies: Math.max(shop.minimumOrderPennies ?? 0, 200),
    productId: p.id as string,
    pricePennies: p.pricePennies as number,
    allergenMask: typeof p.allergenMask === "number" ? (p.allergenMask as number) : 0,
  }
}

async function vendorLoginStrict(page: Page) {
  if (!VENDOR_PASSWORD) {
    throw new Error(NO_VENDOR_PASSWORD_REASON)
  }
  await page.goto("/auth/signin")
  await page.waitForLoadState("domcontentloaded")

  const emailInput = page.locator('input[name="email"], input[type="email"]').first()
  if ((await emailInput.count()) > 0) {
    await emailInput.fill(VENDOR_USERNAME)
    await page.locator('input[name="password"], input[type="password"]').first().fill(VENDOR_PASSWORD)
    await page.locator('button[type="submit"]').first().click()
    await page.waitForURL(/\/dashboard/, { timeout: 15_000 })
    return
  }

  const ssoButton = page.getByRole("button", { name: /sign in with keycloak/i })
  await expect(ssoButton, "No sign-in method found on /auth/signin").toBeVisible({ timeout: 10_000 })
  await ssoButton.click()

  await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 15_000 })
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", VENDOR_USERNAME)
    await page.fill("#password", VENDOR_PASSWORD)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 20_000 })
}

test.describe("Phase 30 PAY-01 — Stripe test-mode vendor refund flow", () => {
  test("vendor issues a £1.00 refund on a captured Stripe card order @desktop-only", async ({
    page,
    request,
  }) => {
    const stripe = stripeTestClient({ secretKey: requireStripeTestSecretKey() })

    const token = await vendorToken(request)
    if (!token) {
      throw new Error(
        "Could not obtain vendor JWT — set E2E_VENDOR_PASSWORD (or KC_SEED_USER_PASSWORD) and KEYCLOAK_CLIENT_SECRET",
      )
    }
    const vendorAuth = { Authorization: `Bearer ${token}` }
    const shopId = await vendorShopId(request, token, SHOP_SLUG)
    expect(shopId, `vendor tenant has no shop ${SHOP_SLUG}`).not.toBeNull()

    // 1. Place a storefront card order at mama-ades-kitchen.
    const menu = await resolveShopMenu(request)
    const quantity = Math.max(1, Math.ceil(menu.minimumOrderPennies / menu.pricePennies) + 1)
    const contact = uniqueContact("stripe-refund")

    const orderRes = await request.post(`${API}/public/shops/${SHOP_SLUG}/orders`, {
      headers: { "Idempotency-Key": crypto.randomUUID() },
      data: {
        customerName: "Stripe Refund E2E",
        customerEmail: contact.email,
        customerPhone: contact.phone,
        fulfilmentType: "COLLECTION",
        acknowledgedAllergenMask: menu.allergenMask,
        items: [
          {
            productId: menu.productId,
            quantity,
            expectedUnitPricePennies: menu.pricePennies,
          },
        ],
        expectedTotalPennies: menu.pricePennies * quantity,
      },
    })
    expect(orderRes.status(), "POST /public/shops/mama-ades-kitchen/orders").toBe(201)
    const confirmation = (await orderRes.json()) as {
      orderNumber?: string
      clientSecret?: string | null
    }
    const orderNumber = confirmation.orderNumber ?? ""
    expect(orderNumber, "orderNumber in GuestOrderConfirmation").toMatch(/^ORD-/)
    const clientSecret = confirmation.clientSecret ?? ""
    const intentId = intentIdFromClientSecret(clientSecret)

    try {
      // 2. Confirm the PaymentIntent against api.stripe.com using pm_card_visa.
      const confirmPiRes = await stripe.post<{ id?: string; status?: string; livemode?: boolean }>(
        `/v1/payment_intents/${encodeURIComponent(intentId)}/confirm`,
        {
          payment_method: "pm_card_visa",
          return_url: "http://localhost:3000/track",
        },
      )
      expect(confirmPiRes.status, "Stripe PaymentIntent confirm HTTP status").toBe(200)
      const confirmedPi = confirmPiRes.body
      expect(confirmedPi.id).toBe(intentId)
      expect(confirmedPi.status, "PaymentIntent status after pm_card_visa confirm").toBe("succeeded")
      expect(confirmedPi.livemode, "PaymentIntent livemode must be false").toBe(false)

      // 3. Resolve the order UUID via the vendor API and wait for the Stripe webhook
      //    (payment_intent.succeeded forwarded by stripe listen) to mark it CAPTURED.
      const listRes = await request.get(
        `${API}/api/v1/orders/shop/${shopId}?size=100&sort=createdAt,desc`,
        { headers: vendorAuth },
      )
      expect(listRes.status(), "GET /api/v1/orders/shop/{shopId}").toBe(200)
      const orderPage = (await listRes.json()) as {
        content?: Array<{ id: string; orderNumber: string }>
      }
      const orderRow = (orderPage.content ?? []).find((o) => o.orderNumber === orderNumber)
      expect(orderRow, `Order ${orderNumber} not found in shop ${SHOP_SLUG}`).toBeTruthy()
      const orderId = (orderRow as { id: string }).id

      const captureDeadline = Date.now() + 30_000
      let paymentStatus = ""
      let paymentReference = ""
      while (Date.now() < captureDeadline) {
        const detailRes = await request.get(`${API}/api/v1/orders/${orderId}`, {
          headers: vendorAuth,
        })
        expect(detailRes.status(), `GET /api/v1/orders/${orderId}`).toBe(200)
        const detail = (await detailRes.json()) as {
          paymentStatus?: string
          paymentReference?: string
        }
        paymentStatus = detail.paymentStatus ?? ""
        paymentReference = detail.paymentReference ?? ""
        if (paymentStatus === "CAPTURED") break
        await new Promise((r) => setTimeout(r, 1_000))
      }
      expect(
        paymentStatus,
        `Order ${orderNumber} (${orderId}) never reached paymentStatus=CAPTURED within 30s — did stripe listen deliver payment_intent.succeeded to core-java?`,
      ).toBe("CAPTURED")
      expect(paymentReference, "Order paymentReference must match Stripe PaymentIntent id").toBe(
        intentId,
      )

      // 4. Transition the order to CONFIRMED so it is eligible for a vendor refund.
      const confirmOrderRes = await request.post(`${API}/api/v1/orders/${orderId}/confirm`, {
        headers: vendorAuth,
      })
      expect(confirmOrderRes.status(), `POST /api/v1/orders/${orderId}/confirm`).toBe(200)
      const confirmedOrder = (await confirmOrderRes.json()) as { status?: string }
      expect(confirmedOrder.status, "order status after the vendor confirm").toBe("CONFIRMED")

      // 5. Log in as the vendor, open the order detail route, and issue a £1.00 partial refund.
      await vendorLoginStrict(page)
      await page.goto(`/dashboard/orders/${orderId}`)
      await page.waitForLoadState("domcontentloaded")
      await expect(page.getByText(orderNumber).first()).toBeVisible({ timeout: 15_000 })

      const refundButton = page.getByRole("button", { name: /^Issue refund$/i })
      await expect(
        refundButton,
        "Issue refund button must be visible on a CONFIRMED + CAPTURED order",
      ).toBeVisible({ timeout: 10_000 })
      await refundButton.click()

      await page.fill("input#amountPounds", "1.00")
      await page.selectOption("select#reason", "REQUESTED_BY_CUSTOMER")
      await page.fill("textarea#note", "Phase 30 PAY-01 refund E2E")

      const dialogSubmit = page.getByRole("button", { name: /^Issue refund$/i }).last()
      await dialogSubmit.click()

      await expect(page.getByText(/Refunding/)).toHaveCount(0, { timeout: 20_000 })
      // Exactly one refund on the page: the heading counts the order's refunds, and a second
      // refund (a double submit, a retried POST) would read "Refunds (2)".
      await expect(page.getByRole("heading", { name: "Refunds (1)", exact: true })).toBeVisible({
        timeout: 10_000,
      })
      await expect(page.getByText("£1.00", { exact: true })).toBeVisible({ timeout: 10_000 })
      // The order's status badge. Strict locator, no .first(): a second "Refunded" on the page
      // fails loudly instead of letting the wrong element satisfy the check.
      await expect(page.getByText("Refunded", { exact: true })).toBeVisible({ timeout: 15_000 })

      // 6. Read the refund back from Stripe: EXACTLY one refund for this intent, 100 pence,
      //    succeeded, settled on two consecutive reads within 30 s (./stripe-test-api).
      const verdict = await pollRefundReadback(
        async () => {
          const res = await stripe.get<StripeRefundList>(
            `/v1/refunds?payment_intent=${encodeURIComponent(intentId)}&limit=10`,
          )
          expect(res.status, "GET /v1/refunds?payment_intent=...").toBe(200)
          return res.body
        },
        { intentId, amountPennies: REFUND_PENNIES },
        { deadlineMs: 30_000, intervalMs: 1_000 },
      )
      if (verdict.kind !== "settled") {
        throw new Error(`Stripe refund read-back for ${orderNumber}: ${verdict.reason}`)
      }
      const refund = verdict.refund

      // 7. The Refund object carries no livemode; its CHARGE does. Always read it — no fallback.
      const chargeRes = await stripe.get<{ id?: string; livemode?: boolean; payment_intent?: string }>(
        `/v1/charges/${encodeURIComponent(refund.charge)}`,
      )
      expect(chargeRes.status, `GET /v1/charges/${refund.charge}`).toBe(200)
      expect(chargeRes.body.payment_intent, "the refunded charge belongs to this intent").toBe(intentId)
      expect(chargeRes.body.livemode, "Stripe charge livemode must be false").toBe(false)

      console.log(
        `STRIPE-REFUND order=${orderNumber} intent=${intentId} refund=${refund.id} amount=100 livemode=false`,
      )

      const evidencePath = process.env.STRIPE_E2E_EVIDENCE
      if (evidencePath) {
        writeFileSync(
          evidencePath,
          JSON.stringify({ order: orderNumber, intent: intentId, refund: refund.id }),
          "utf8",
        )
      }
    } finally {
      await cancelOrdersByNumber(request, SHOP_SLUG, [orderNumber])
    }
  })
})
