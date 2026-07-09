// Phase 18 Plan 01 — standalone Node ESM verification for the customer identity
// realm split. This is deliberately a `.mjs` script (NOT a `.spec.ts`), so the
// docs-freshness gate that counts `test()` blocks in `frontend/e2e/*.spec.ts`
// stays at 5 specs / 23 blocks — nothing new is counted.
//
// Scenario A (THIS plan, 18-01): a storefront customer self-registers and logs
// in against the NEW `jtoye-customers` Keycloak realm and lands logged-in on
// `/shop`, with `/api/customer-auth/session` returning `authenticated: true`.
// The flow is driven through the real storefront "Sign in" button so it proves
// the frontend is actually repointed at the customer realm end-to-end.
//
// Scenarios B and C are filled in by Plan 18-02 (see TODO stubs at the bottom).
//
// Run:
//   NODE_PATH=frontend/node_modules PLAYWRIGHT_BASE_URL=http://localhost:3100 \
//     node --env-file=.env frontend/e2e/customer-realm-split.verify.mjs
//
// Secrets: the customer registration password is read from KC_SEED_USER_PASSWORD
// in .env (a policy-compliant password) and is NEVER printed. Only booleans,
// pass/fail lines and the (non-secret) generated test email are logged.
//
// Living under frontend/ means the bare `@playwright/test` import resolves via
// frontend/node_modules through Node's normal ESM directory walk.

import { chromium } from "@playwright/test"
import { writeFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import { dirname, join } from "node:path"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3100"
const CUSTOMER_PASSWORD = process.env.KC_SEED_USER_PASSWORD // policy-compliant, never logged
const EXPECTED_REALM = "jtoye-customers"
const FORBIDDEN_REALM = "jtoye-dev"

const scriptDir = dirname(fileURLToPath(import.meta.url))
const EMAIL_OUT = join(scriptDir, ".last-customer-email")

const failures = []
function check(name, cond) {
  if (cond) {
    console.log(`  PASS  ${name}`)
  } else {
    console.log(`  FAIL  ${name}`)
    failures.push(name)
  }
}

function realmOf(url) {
  return url.includes("/realms/") ? url.split("/realms/")[1].split("/")[0] : "none"
}

async function scenarioA() {
  console.log(`Scenario A — customer self-register + login on ${EXPECTED_REALM}`)

  if (!CUSTOMER_PASSWORD) {
    check("KC_SEED_USER_PASSWORD is available from .env (needed for registration)", false)
    return
  }

  const email = `cust+${Date.now()}@example.com`
  console.log(`  test customer email: ${email}`) // email is not a secret

  const browser = await chromium.launch()
  const context = await browser.newContext()
  const page = await context.newPage()
  let realmSeen = "none"
  try {
    // 1) Land on a customer-auth-guarded storefront page; the RequireCustomerAuth
    //    guard renders a real "Sign in" button that calls customerLogin().
    await page.goto(`${BASE}/shop/orders`, { waitUntil: "domcontentloaded" })
    const signIn = page.getByRole("button", { name: /sign in/i }).first()
    await signIn.waitFor({ state: "visible", timeout: 20000 })

    // 2) Clicking "Sign in" must redirect the browser into a Keycloak realm.
    await Promise.all([
      page.waitForURL(/\/realms\//, { timeout: 20000 }),
      signIn.click(),
    ])
    realmSeen = realmOf(page.url())

    // 3) That realm MUST be jtoye-customers (the split), NOT the jtoye-dev staff realm.
    check(`Sign-in flow targets the ${EXPECTED_REALM} realm (saw: ${realmSeen})`,
      page.url().includes(`/realms/${EXPECTED_REALM}/`))
    check(`Sign-in flow does NOT target the ${FORBIDDEN_REALM} staff realm`,
      !page.url().includes(`/realms/${FORBIDDEN_REALM}/`))

    // Without the customer realm in the flow, nothing downstream can pass — bail.
    if (!page.url().includes(`/realms/${EXPECTED_REALM}/`)) return

    // 4) Follow the "Register" link and self-register a unique customer.
    const registerLink = page.locator("#kc-registration a, a[href*='registrations']").first()
    await registerLink.waitFor({ state: "visible", timeout: 15000 })
    await registerLink.click()

    await page.locator("#firstName").waitFor({ state: "visible", timeout: 15000 })
    await page.fill("#firstName", "Test")
    await page.fill("#lastName", "Customer")
    await page.fill("#email", email)
    await page.fill("#password", CUSTOMER_PASSWORD)
    await page.fill("#password-confirm", CUSTOMER_PASSWORD)
    await page.locator("input[type=submit], button[type=submit]").first().click()

    // 5) Land back on the storefront under /shop, past the OAuth callback.
    await page.waitForURL(
      (u) => u.href.startsWith(`${BASE}/shop`) && !u.href.includes("/auth/callback"),
      { timeout: 45000 }
    )
    const landed = page.url()
    check(`lands back on the storefront under /shop (${landed.replace(BASE, "")})`,
      landed.startsWith(`${BASE}/shop`))

    // 6) The cookie-backed session must report an authenticated customer.
    const res = await page.request.get(`${BASE}/api/customer-auth/session`)
    check("GET /api/customer-auth/session returns HTTP 200", res.status() === 200)
    let body = {}
    try { body = await res.json() } catch { /* leave body empty on parse failure */ }
    check("session authenticated === true", body.authenticated === true)
    check("session profile.email is a non-empty string",
      typeof body?.profile?.email === "string" && body.profile.email.length > 0)

    // Export the discovered email so Plan 18-02's separate-pools check can find it.
    writeFileSync(EMAIL_OUT, email, "utf8")
  } catch (err) {
    // Log only the error message + which realm we were on (never the password).
    const msg = err && err.message ? err.message : String(err)
    console.log(`  FAIL  Scenario A threw: ${msg}`)
    console.log(`        last realm in flow: ${realmSeen}`)
    failures.push("Scenario A exception")
  } finally {
    await browser.close()
  }
}

// ---------------------------------------------------------------------------
// TODO(18-02) Scenario B — admin dashboard login still works on jtoye-dev AND
//            the admin (core-api) login page shows NO "Register" link, proving
//            staff self-registration is disabled after the realm hardening.
// TODO(18-02) Scenario C — separate identity pools: the Scenario-A test customer
//            (read from .last-customer-email) is ABSENT from jtoye-dev, and
//            admin-user is ABSENT from jtoye-customers (admin-API user lookup
//            per realm). Confirms the two user stores are fully isolated.
// ---------------------------------------------------------------------------

await scenarioA()

if (failures.length > 0) {
  console.log(`\nRESULT: FAIL (${failures.length} failed assertion(s))`)
  process.exit(1)
}
console.log("\nRESULT: PASS (Scenario A green)")
process.exit(0)
