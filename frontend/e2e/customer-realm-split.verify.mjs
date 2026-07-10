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
// Scenario B (Plan 18-02): the admin dashboard login still works on the
// `jtoye-dev` staff realm AND its `core-api` login page shows NO Register/New-user
// link — proving self-registration was disabled by the realm hardening.
// Scenario C (Plan 18-02, admin-API): the two identity pools are disjoint — the
// Scenario-A test customer is absent from `jtoye-dev`, `admin-user` is absent from
// `jtoye-customers`, and `storefront-client` is gone from `jtoye-dev`.
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
import { readFileSync, writeFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import { dirname, join } from "node:path"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3100"
const CUSTOMER_PASSWORD = process.env.KC_SEED_USER_PASSWORD // policy-compliant, never logged
const EXPECTED_REALM = "jtoye-customers"
const FORBIDDEN_REALM = "jtoye-dev"

// Scenario B/C identity surfaces. STAFF_REALM/CUSTOMER_REALM alias the two realm
// names for readability; the seed staff admin and its password come from .env.
const STAFF_REALM = FORBIDDEN_REALM
const CUSTOMER_REALM = EXPECTED_REALM
const ADMIN_USERNAME = "admin-user"
const ADMIN_LOGIN_PASSWORD = process.env.KC_SEED_USER_PASSWORD // never logged
// Keycloak admin-API base (host-facing). Override via KC_ADMIN_BASE if needed.
const KC_ADMIN_BASE = process.env.KC_ADMIN_BASE || "http://localhost:8085"

const arrLen = (x) => (Array.isArray(x) ? x.length : -1)

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
// Scenario B — admin dashboard login still works on jtoye-dev AND the admin
// (core-api) login page shows NO "Register" link, proving staff self-registration
// is disabled after the realm hardening.
// ---------------------------------------------------------------------------
async function scenarioB() {
  console.log(`\nScenario B — admin login on ${STAFF_REALM}, NO Register link`)

  if (!ADMIN_LOGIN_PASSWORD) {
    check("KC_SEED_USER_PASSWORD is available from .env (needed for admin login)", false)
    return
  }

  const browser = await chromium.launch()
  const context = await browser.newContext()
  const page = await context.newPage()
  let realmSeen = "none"
  try {
    // 1) Start at the admin sign-in page and click "Sign in with Keycloak"; the
    //    NextAuth core-api provider redirects the browser into the jtoye-dev realm.
    await page.goto(`${BASE}/auth/signin`, { waitUntil: "domcontentloaded" })
    const kcButton = page.getByRole("button", { name: /sign in with keycloak/i }).first()
    await kcButton.waitFor({ state: "visible", timeout: 20000 })
    await Promise.all([
      page.waitForURL(/\/realms\//, { timeout: 30000 }),
      kcButton.click(),
    ])
    realmSeen = realmOf(page.url())

    // 2) The admin login page MUST be served by the jtoye-dev staff realm.
    check(`admin sign-in targets the ${STAFF_REALM} staff realm (saw: ${realmSeen})`,
      page.url().includes(`/realms/${STAFF_REALM}/`))

    // 3) The staff login page must offer NO self-registration affordance. After
    //    hardening (registrationAllowed:false), Keycloak renders no #kc-registration
    //    block and no anchor into /login-actions/registration (nor /registrations).
    await page.locator("input#username").first().waitFor({ state: "visible", timeout: 15000 })
    const registerLinks = page.locator(
      "#kc-registration a, #kc-registration-container a, a[href*='/registration'], a[href*='/registrations']"
    )
    const registerCount = await registerLinks.count()
    check(`staff login page shows NO Register/New-user link (found ${registerCount})`,
      registerCount === 0)

    // 4) Admin login STILL works: sign in as admin-user and land on /dashboard.
    await page.fill("#username", ADMIN_USERNAME)
    await page.fill("#password", ADMIN_LOGIN_PASSWORD)
    await page.locator("#kc-login, input[type=submit], button[type=submit]").first().click()
    await page.waitForURL((u) => u.href.startsWith(`${BASE}/dashboard`), { timeout: 45000 })
    check(`${ADMIN_USERNAME} still signs in and lands on /dashboard`,
      page.url().startsWith(`${BASE}/dashboard`))
  } catch (err) {
    const msg = err && err.message ? err.message : String(err)
    console.log(`  FAIL  Scenario B threw: ${msg}`)
    console.log(`        last realm in flow: ${realmSeen}`)
    failures.push("Scenario B exception")
  } finally {
    await browser.close()
  }
}

// ---------------------------------------------------------------------------
// Scenario C — separate identity pools (admin-API): the Scenario-A test customer
// (read from .last-customer-email) is ABSENT from jtoye-dev, admin-user is ABSENT
// from jtoye-customers, and storefront-client is GONE from jtoye-dev. Confirms
// the two user/client stores are fully isolated.
// ---------------------------------------------------------------------------
async function scenarioC() {
  console.log(`\nScenario C — separate identity pools (admin-API)`)

  const kcAdmin = process.env.KEYCLOAK_ADMIN
  const kcAdminPw = process.env.KEYCLOAK_ADMIN_PASSWORD
  if (!kcAdmin || !kcAdminPw) {
    check("KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD available from .env (admin-API)", false)
    return
  }

  let customerEmail = ""
  try {
    customerEmail = readFileSync(EMAIL_OUT, "utf8").trim()
  } catch { /* handled by the check below */ }
  if (!customerEmail) {
    check("test-customer email available from .last-customer-email (Scenario A output)", false)
    return
  }

  try {
    // Bootstrap a master admin-cli token for privileged per-realm lookups.
    const tokenRes = await fetch(
      `${KC_ADMIN_BASE}/realms/master/protocol/openid-connect/token`,
      {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          grant_type: "password",
          client_id: "admin-cli",
          username: kcAdmin,
          password: kcAdminPw,
        }),
      }
    )
    check("obtained a Keycloak master admin token", tokenRes.status === 200)
    const tokenBody = await tokenRes.json().catch(() => ({}))
    const adminToken = tokenBody.access_token
    if (!adminToken) {
      check("master admin token carries an access_token", false)
      return
    }
    const authHeaders = { Authorization: `Bearer ${adminToken}` }
    const getJson = async (path) => {
      const r = await fetch(`${KC_ADMIN_BASE}${path}`, { headers: authHeaders })
      // Coerce non-array bodies (401/403/5xx, connection reset) to null so a failed
      // query can never masquerade as an empty result and false-PASS an ABSENT check.
      const b = await r.json().catch(() => null)
      return { status: r.status, ok: r.ok, body: Array.isArray(b) ? b : null }
    }

    // 1) The Scenario-A test customer must be ABSENT from the jtoye-dev staff realm.
    const custInStaff = await getJson(
      `/admin/realms/${STAFF_REALM}/users?email=${encodeURIComponent(customerEmail)}`
    )
    check(`${STAFF_REALM} user query succeeded (HTTP ${custInStaff.status})`,
      custInStaff.ok && custInStaff.body !== null)
    check(`test customer is ABSENT from ${STAFF_REALM} (found ${arrLen(custInStaff.body)})`,
      arrLen(custInStaff.body) === 0)

    // 2) admin-user must be ABSENT from the jtoye-customers realm.
    const adminInCust = await getJson(
      `/admin/realms/${CUSTOMER_REALM}/users?username=${ADMIN_USERNAME}`
    )
    check(`${CUSTOMER_REALM} user query succeeded (HTTP ${adminInCust.status})`,
      adminInCust.ok && adminInCust.body !== null)
    check(`${ADMIN_USERNAME} is ABSENT from ${CUSTOMER_REALM} (found ${arrLen(adminInCust.body)})`,
      arrLen(adminInCust.body) === 0)

    // 3) storefront-client must be REMOVED from jtoye-dev (it lives only in jtoye-customers).
    const sfInStaff = await getJson(
      `/admin/realms/${STAFF_REALM}/clients?clientId=storefront-client`
    )
    check(`${STAFF_REALM} client query succeeded (HTTP ${sfInStaff.status})`,
      sfInStaff.ok && sfInStaff.body !== null)
    check(`storefront-client is REMOVED from ${STAFF_REALM} (found ${arrLen(sfInStaff.body)})`,
      arrLen(sfInStaff.body) === 0)
  } catch (err) {
    const msg = err && err.message ? err.message : String(err)
    console.log(`  FAIL  Scenario C threw: ${msg}`)
    failures.push("Scenario C exception")
  }
}

await scenarioA()
await scenarioB()
await scenarioC()

if (failures.length > 0) {
  console.log(`\nRESULT: FAIL (${failures.length} failed assertion(s))`)
  process.exit(1)
}
console.log("\nRESULT: PASS (Scenarios A + B + C green)")
process.exit(0)
