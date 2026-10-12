/**
 * The cash-order confirmation, as a browser walk sees it (37-37, UI-SPEC § C3, RWO-029 / UXT-029).
 *
 * Until 37-37 a cash order was confirmed IN PLACE on /shop/{slug}/checkout under the heading
 * "Order confirmed!". It now routes to /shop/{slug}/orders/{orderNumber}, where the first view
 * (the jtoye-just-placed marker present) reads h1 "Order confirmed", focused and announced. Every
 * spec that places a cash order waits here, so they all agree on what "confirmed" looks like — and
 * a spec that still waited for the retired "Order confirmed!" heading would time out, while a
 * negative check on it would pass for ever without testing anything.
 */
import { expect, type Locator, type Page } from "@playwright/test"

/** The C3 first-view heading. `exact` keeps "Order ORD-… placed" and the old "Order confirmed!" out. */
export function confirmedHeading(page: Page): Locator {
  return page.getByRole("heading", { level: 1, name: "Order confirmed", exact: true })
}

const ORDER_ROUTE = /\/shop\/[^/]+\/orders\/(ORD-[0-9A-Z-]+)$/

/**
 * Wait until a just-placed cash order has landed on its own route with the confirmed heading, and
 * return its order number, read from the URL (the page the customer is now on).
 */
export async function awaitCashConfirmation(page: Page, timeout = 15_000): Promise<string> {
  await expect(page).toHaveURL(ORDER_ROUTE, { timeout })
  await expect(confirmedHeading(page)).toBeVisible({ timeout })
  const match = new URL(page.url()).pathname.match(ORDER_ROUTE)
  expect(match, `no order number in the confirmation URL ${page.url()}`).not.toBeNull()
  return match![1]
}
