/**
 * #840 (phase 31.1, PGC-840): in a real browser, every storage key the site writes is one the
 * cookie policy declares, and an explicit sign-out removes every personal one.
 *
 * WHY A BROWSER TEST AS WELL AS THE JEST ONES. `lib/__tests__/client-storage-keys.test.ts` holds
 * the registry against every `setItem` call in the SOURCE, and
 * `lib/__tests__/customer-auth-signout-clears-personal-keys.test.ts` drives the sign-out code
 * through the transition. Neither can see a key written by something that is not our source (a
 * library, a framework default) or prove the teardown runs on the real sign-out button with the
 * real Keycloak round-trip in between. This walk can: a real customer, a real cash order, the
 * real nav button, and the stored values read back out of the browser.
 *
 * IDENTITY LIFECYCLE CONTRACT. Asserted by STORED CONTENT through the transition, never by the
 * rendered view: the values are read before sign-out (and must be the ones this order wrote) and
 * read again after it.
 *
 * THE NON-VACUITY CONTROL. "Every personal key is absent after sign-out" is equally true of a
 * walk that never stored any. So before signing out the spec REQUIRES `jtoye-guest-orders` to
 * hold this order's number and email, the shop's checkout email to hold this email, and the
 * last-signin stamp to be set — or the absence afterwards proves nothing and the test fails
 * saying so.
 *
 * THE CUSTOMER. No customer is seeded in the `jtoye-customers` realm (its export has no users),
 * so a fresh one is registered through the real sign-in page, the storefront-flows.spec.ts
 * precedent (`verifyEmail=false` in that realm, so registering signs in).
 *
 * THE BASKET KEY. `hooks/use-stored-state.ts` legitimately re-creates an EMPTY
 * `jtoye-cart-<shop>` on the next render after a sign-out. So a basket key passes when it is
 * absent OR holds no items and no owner — the same "assert what it HOLDS" rule as
 * customer-auth-signout-clears-carts.test.ts. Every other personal key must be absent.
 *
 * WHEN IT IS RUN. Listed (not run) by plan 31.1-05. Its live run belongs to plan 31.1-30: the
 * running images predate this branch, and since 31.1-03 the server refuses a storefront order
 * without an allergen acknowledgement mask that the checkout only sends after 31.1-15, so a cash
 * order cannot reach its confirmation until that plan's rebuild.
 *
 * NAVIGATION IS RELATIVE: playwright.config.ts is the only base-URL authority (#505).
 *
 * Run: npx playwright test e2e/storage-keys-signout.spec.ts
 */

import { test, expect, type Page } from "@playwright/test"

import {
  CLIENT_STORAGE_KEYS,
  PERSONAL_KEYS_CLEARED_ON_SIGN_OUT,
  entryMatches,
  isRegisteredStorageKey,
  type StorageArea,
} from "../lib/client-storage-keys"

// The deterministic seeded cash-only shop the cash-checkout spec also uses (DemoDataSeeder).
const SHOP_SLUG = "mama-ades-kitchen"

type Snapshot = Record<StorageArea, Record<string, string>>

async function readStorage(page: Page): Promise<Snapshot> {
  return page.evaluate(() => {
    const dump = (s: Storage) => {
      const out: Record<string, string> = {}
      for (let i = 0; i < s.length; i++) {
        const k = s.key(i)
        if (k !== null) out[k] = s.getItem(k) ?? ""
      }
      return out
    }
    return { localStorage: dump(window.localStorage), sessionStorage: dump(window.sessionStorage) }
  })
}

function isPersonal(area: StorageArea, key: string): boolean {
  return PERSONAL_KEYS_CLEARED_ON_SIGN_OUT.some((e) => e.area === area && entryMatches(e, key))
}

async function registerCustomer(page: Page): Promise<string> {
  const rand = Math.floor(Math.random() * 1_000_000)
  const email = `e2e-840-${rand}@test.com`

  await page.goto("/shop")
  await page.waitForLoadState("domcontentloaded")
  await page.locator("nav").getByRole("link", { name: "Sign in" }).first().click()
  await page.waitForURL(/\/shop\/signin/, { timeout: 15_000 })
  await page.getByRole("button", { name: "Create an account" }).click()
  await page.waitForURL(/openid-connect\/registrations/, { timeout: 20_000 })

  await page.fill("input#email", email)
  await page.fill("input#password", "TestPass123!")
  await page.fill("input#password-confirm", "TestPass123!")
  await page.fill("input#firstName", "Storage")
  await page.fill("input#lastName", `Keys${rand}`)
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
  // PRESENCE CONTROL: the signed-in control (StorefrontNav's title="Sign out" button).
  await expect(
    page.locator('button[title="Sign out"]').first(),
    "registration did not leave a signed-in customer — nothing below would mean anything"
  ).toBeVisible({ timeout: 20_000 })
  return email
}

async function placeCashCollectionOrder(page: Page, email: string): Promise<string> {
  await page.goto(`/shop/${SHOP_SLUG}`)
  await page.waitForLoadState("domcontentloaded")

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
  await page.waitForLoadState("domcontentloaded")
  await page.getByText("Proceed to checkout").click()
  await page.waitForLoadState("domcontentloaded")

  await page.getByRole("button", { name: /collection/i }).click()
  await page.fill("input#name", "Storage Keys 840")
  await page.fill("input#email", email)
  await page.fill("input#phone", "07700 840840")

  const allergenAck = page.getByRole("checkbox", {
    name: /I have read the allergen information for this order\./i,
  })
  await expect(allergenAck).toBeVisible()
  await allergenAck.click()
  await expect(allergenAck).toBeChecked()

  await page.locator('button[type="submit"]:has-text("Place order")').click()
  await expect(page.getByRole("heading", { name: "Order confirmed!" })).toBeVisible({ timeout: 15_000 })
  const confText = await page.getByText(/Order\s+ORD-/).first().innerText()
  const match = confText.match(/ORD-[A-Z0-9-]+/)
  expect(match, "no order number on the confirmation").not.toBeNull()
  return match![0]
}

test.describe("#840 browser storage: declared, and personal keys cleared on explicit sign-out", () => {
  test("a signed-in cash order stores only registered keys, and Sign out removes every personal one", async ({
    page,
  }) => {
    test.setTimeout(120_000)

    const email = await registerCustomer(page)
    const orderNumber = await placeCashCollectionOrder(page, email)
    // The app origin, read from where the walk actually is (never a hardcoded base URL).
    const origin = new URL(page.url()).origin

    // ---- before sign-out: everything stored is declared -------------------------------
    const before = await readStorage(page)
    const undeclared: string[] = []
    for (const area of ["localStorage", "sessionStorage"] as const) {
      for (const key of Object.keys(before[area])) {
        if (!isRegisteredStorageKey(area, key)) undeclared.push(`${area}:${key}`)
      }
    }
    expect(
      undeclared,
      `keys stored by the site that the cookie policy does not declare (registry has ${CLIENT_STORAGE_KEYS.length} entries)`
    ).toEqual([])

    // ---- NON-VACUITY CONTROL: the personal values this walk wrote are really there ----
    const orders = before.localStorage["jtoye-guest-orders"]
    expect(orders, "jtoye-guest-orders was never written, so its absence later would prove nothing").toBeTruthy()
    const parsed = JSON.parse(orders) as Array<{ orderNumber: string; email: string }>
    expect(parsed.map((o) => o.orderNumber)).toContain(orderNumber)
    expect(parsed.find((o) => o.orderNumber === orderNumber)?.email).toBe(email)
    expect(before.localStorage[`jtoye-checkout-email-${SHOP_SLUG}`]).toBe(email)
    expect(before.localStorage["jtoye-customer-last-signin"], "no last-signin stamp after a sign-in").toBeTruthy()

    const personalBefore = (["localStorage", "sessionStorage"] as const).flatMap((area) =>
      Object.keys(before[area])
        .filter((key) => isPersonal(area, key))
        .map((key) => ({ area, key }))
    )
    expect(personalBefore.length, "no personal key was stored before sign-out").toBeGreaterThanOrEqual(3)

    // ---- the explicit sign-out, through the real nav button and the IdP round-trip ----
    await page.locator('button[title="Sign out"]').first().click()
    await page.waitForURL((u) => u.origin === origin && /^\/shop/.test(u.pathname), { timeout: 30_000 })
    await page.waitForLoadState("domcontentloaded")
    await expect(
      page.getByRole("navigation", { name: "Storefront" }).getByRole("link", { name: /^sign in$/i }),
      "back on the storefront but not signed out"
    ).toBeVisible({ timeout: 20_000 })

    // ---- after: every personal key is gone, read back by stored value ----------------
    const after = await readStorage(page)
    const survivors: string[] = []
    for (const { area, key } of personalBefore) {
      const value = after[area][key]
      if (value === undefined) continue
      if (area === "localStorage" && key.startsWith("jtoye-cart-")) {
        const basket = JSON.parse(value) as { owner?: string | null; items?: unknown[] }
        if ((basket.items ?? []).length === 0 && !basket.owner) continue
      }
      survivors.push(`${area}:${key}=${value.slice(0, 120)}`)
    }
    expect(survivors, "personal browser storage survived an explicit sign-out (#840)").toEqual([])

    // And nothing undeclared appeared on the way back either.
    const undeclaredAfter = (["localStorage", "sessionStorage"] as const).flatMap((area) =>
      Object.keys(after[area])
        .filter((key) => !isRegisteredStorageKey(area, key))
        .map((key) => `${area}:${key}`)
    )
    expect(undeclaredAfter).toEqual([])
  })
})
