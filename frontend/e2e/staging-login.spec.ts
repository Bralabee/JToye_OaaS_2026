/**
 * DPLY-01 — one real vendor login through the PUBLIC staging edge (plan 29-14,
 * the Phase 29 tracer). The Phase 26-08 L7 proof, moved from a local overlay to
 * https://app-staging.olajay.co.uk behind Traefik.
 *
 * WHAT IT PROVES, in order:
 *   1. the vendor sign-in button sends the browser to the staging realm's
 *      authorize endpoint with client_id=core-api and the ENCODED staging
 *      callback as redirect_uri (the request URL is captured, not inferred);
 *   2. the Keycloak form accepts the staging test vendor and the browser lands
 *      on /dashboard with a rendered <h1>;
 *   3. the products list shows at least one dish from the culinary catalogue the
 *      staging seed (plan 29-06) created through the product API.
 * The authorize URL, the final URL, the h1 text and the product count are
 * written to stdout for the evidence file.
 *
 * NEVER IN A DEFAULT RUN (D-27). playwright.config.ts ignores staging-*.spec.ts
 * unless STAGING_E2E is exactly "1", so local, CI and nightly runs never reach a
 * public host. Even when collected, the test SKIPS unless PLAYWRIGHT_BASE_URL is
 * exactly the staging app host: there is deliberately no fallback here (the
 * config is the only base-URL authority, scripts/check-e2e-baseurl-contract.sh).
 *
 * THE CREDENTIAL. STAGING_VENDOR_USERNAME / STAGING_VENDOR_PASSWORD come only
 * from the environment of the test process; the caller reads the password from
 * Key Vault (jtoye-stg-kv, staging-seed--test-vendor-password) through the plan
 * 29-01 guard's az_read. The realm holds no seed users, so no committed value
 * can sign in. A missing credential FAILS the test (a skip would read as "not
 * applicable"); neither variable is ever logged, and trace, screenshot and video
 * are off for this file so a failing run cannot write the typed password into an
 * artifact.
 *
 * @desktop-only: one login proves the path; the mobile project's grepInvert drops
 * it, so the switch collects it exactly once.
 */

import { test, expect, type Page, type Request } from "@playwright/test"
import { readFileSync } from "node:fs"
import path from "node:path"

const STAGING_APP = "https://app-staging.olajay.co.uk"
const STAGING_AUTH = "https://auth-staging.olajay.co.uk"
const STAGING_REALM = "jtoye-staging"
const EXPECTED_CLIENT_ID = "core-api"
const EXPECTED_REDIRECT_URI = `${STAGING_APP}/api/auth/callback/keycloak`

// No fallback literal: an unset or different base URL skips, below.
const BASE = process.env.PLAYWRIGHT_BASE_URL

test.use({ trace: "off", screenshot: "off", video: "off" })

/**
 * Every dish name in the bundled culinary catalogue the seed draws from. The
 * seed takes the first pack the API lists, so the assertion accepts any dish of
 * any pack rather than guessing which pack that is.
 */
function catalogueDishNames(): string[] {
  const file = path.join(__dirname, "..", "..", "core-java", "src", "main", "resources", "seed", "oaas_culinary_catalog.json")
  const parsed = JSON.parse(readFileSync(file, "utf8")) as { dishes?: Array<{ name?: unknown }> }
  const names = (parsed.dishes ?? [])
    .map((d) => d.name)
    .filter((n): n is string => typeof n === "string" && n.length > 0)
  if (names.length === 0) {
    throw new Error(`no dish names read from ${file}: the product assertion could not fail`)
  }
  return names
}

function isAuthorizeRequest(r: Request): boolean {
  return r.url().includes("/protocol/openid-connect/auth")
}

test("staging vendor signs in through the public edge and sees seeded products @desktop-only", async ({ page }) => {
  test.skip(
    BASE !== STAGING_APP,
    `PLAYWRIGHT_BASE_URL must be exactly ${STAGING_APP} for the staging login (set STAGING_E2E=1 too)`,
  )
  const username = process.env.STAGING_VENDOR_USERNAME
  const password = process.env.STAGING_VENDOR_PASSWORD
  if (!username || !password) {
    // Names the variables, never their values.
    throw new Error(
      "STAGING_VENDOR_USERNAME and STAGING_VENDOR_PASSWORD must both be set (read the password from " +
        "jtoye-stg-kv through scripts/lib/azure-guard.sh az_read); refusing to skip a credentialed run",
    )
  }
  const dishNames = catalogueDishNames()

  // While the typed password is on screen, a failure must not leave the Keycloak
  // form there: Playwright writes a page snapshot (error-context.md) for a failed
  // test even with trace and screenshot off. At any other point the page text is
  // printed instead, so a refusal (invalid_client, a 502, an empty product list)
  // is on record.
  const state = { passwordOnScreen: false }
  try {
    await signInAndReadDashboard(page, username, password, dishNames, state)
  } catch (e) {
    if (state.passwordOnScreen) {
      await page.goto("about:blank").catch(() => {})
    } else {
      const text = await page.locator("body").innerText({ timeout: 5_000 }).catch(() => "<no page text>")
      console.log(`PAGE_URL_ON_FAILURE ${page.url()}`)
      console.log(`PAGE_TEXT_ON_FAILURE ${text.replace(/\s+/g, " ").slice(0, 600)}`)
    }
    throw e
  }
})

async function signInAndReadDashboard(
  page: Page,
  username: string,
  password: string,
  dishNames: string[],
  state: { passwordOnScreen: boolean },
): Promise<void> {
  // 1. Sign-in button -> the staging realm's authorize endpoint.
  await page.goto("/auth/signin", { waitUntil: "domcontentloaded" })
  const sso = page.getByRole("button", { name: /sign in with keycloak/i })
  await sso.waitFor({ state: "visible", timeout: 20_000 })
  // A click before hydration can silently no-op; wait for the page to settle.
  await page.waitForLoadState("networkidle").catch(() => {})

  let authorize = page.waitForRequest(isAuthorizeRequest, { timeout: 20_000 })
  await sso.click()
  let authorizeRequest: Request
  try {
    authorizeRequest = await authorize
  } catch (e) {
    if (!new URL(page.url()).pathname.startsWith("/auth/signin")) throw e
    // The first click raced hydration: still on the sign-in page, click once more.
    authorize = page.waitForRequest(isAuthorizeRequest, { timeout: 20_000 })
    await sso.click({ force: true })
    authorizeRequest = await authorize
  }
  const authorizeUrl = authorizeRequest.url()
  console.log(`AUTHORIZE_URL ${authorizeUrl}`)
  const parsed = new URL(authorizeUrl)
  expect(parsed.origin).toBe(STAGING_AUTH)
  expect(parsed.pathname).toBe(`/realms/${STAGING_REALM}/protocol/openid-connect/auth`)
  expect(parsed.searchParams.get("client_id")).toBe(EXPECTED_CLIENT_ID)
  expect(authorizeUrl).toContain(`client_id=${EXPECTED_CLIENT_ID}`)
  expect(authorizeUrl).toContain(`redirect_uri=${encodeURIComponent(EXPECTED_REDIRECT_URI)}`)

  // 2. The Keycloak form, then the dashboard.
  await page.waitForURL(/\/protocol\/openid-connect\/auth/, { timeout: 30_000 })
  await page.locator("#username").fill(username)
  state.passwordOnScreen = true
  await page.locator("#password").fill(password)
  await page.locator("#kc-login").click()
  await page.waitForURL((u) => u.origin === STAGING_APP && u.pathname.startsWith("/dashboard"), { timeout: 45_000 })
  state.passwordOnScreen = false
  const finalUrl = page.url()
  console.log(`FINAL_URL ${finalUrl}`)
  expect(new URL(finalUrl).pathname.startsWith("/dashboard")).toBe(true)
  // By role: React's hidden streaming buffer is outside the accessibility tree.
  const h1 = page.getByRole("heading", { level: 1 })
  await expect(h1).toBeVisible({ timeout: 30_000 })
  console.log(`H1 ${(await h1.innerText()).trim()}`)

  // 3. The seeded rows, read from what the vendor sees.
  await page.goto("/dashboard/products", { waitUntil: "domcontentloaded" })
  await expect(page.getByRole("heading", { level: 1, name: "Products" })).toBeVisible({ timeout: 30_000 })
  let visible: string[] = []
  await expect
    .poll(
      async () => {
        // innerText omits hidden elements, so the streaming buffer cannot count.
        const text = await page.locator("body").innerText()
        visible = dishNames.filter((n) => text.includes(n))
        return visible.length
      },
      { timeout: 30_000, message: "no seeded culinary-catalogue dish is visible on /dashboard/products" },
    )
    .toBeGreaterThan(0)
  console.log(`PRODUCT_COUNT ${visible.length} (${visible.join(", ")})`)
}
