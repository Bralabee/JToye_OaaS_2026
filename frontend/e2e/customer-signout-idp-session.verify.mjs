// Issue #504 — customer sign-out must return the shopper to the storefront AND
// genuinely terminate the Keycloak SSO session.
//
// Deliberately a `.mjs` script and NOT a `.spec.ts`, matching the
// cart-identity-boundary.verify.mjs / customer-realm-split.verify.mjs
// precedent: the docs-freshness gate counts `test()` blocks in
// `frontend/e2e/*.spec.ts`, and nothing here should move that number. It also
// needs to drive TWO Keycloak identities through one browser context while
// deliberately NOT clearing cookies, which the shared Playwright config's
// per-test isolation actively works against.
//
// Run (from the repo root, against a stack serving the frontend):
//   PLAYWRIGHT_BASE_URL=http://localhost:3000 \
//     node --env-file=.env frontend/e2e/customer-signout-idp-session.verify.mjs
//
// ================= THE ONE RULE THIS SCRIPT IS BUILT AROUND =================
// COOKIES ARE NEVER CLEARED. Not once, anywhere.
//
// Issue #504 says it in as many words: "clearing browser cookies makes this look
// fixed". A cookie clear ends the SSO session from the browser's side and would
// make S3 pass over a completely unfixed IdP. So the browser keeps every
// KEYCLOAK_* / AUTH_SESSION_* cookie it was given, and S3 asks Keycloak itself
// whether the session is alive by trying to use it. The cookie inventory is
// printed before and after precisely so a reader can see nothing was thrown away.
// ============================================================================
//
// FOUR criteria, each with its own fail arm because each has a shape that would
// otherwise pass for the wrong reason:
//
//   S1  the post-logout redirect URI is a REACHABLE origin, not a bind address
//       fail arm: the URI is read out of the app's own live response, so a
//       route that stopped emitting one at all cannot pass by silence.
//   S2  sign-out lands the shopper back on the storefront, not an IdP error page
//       fail arm: S0 asserts the shopper was signed in first — a sign-out that
//       never happened also "does not show an error page".
//   S3  the IdP SSO session is genuinely terminated
//       fail arm: proven by a CREDENTIAL PROMPT on the next sign-in, with the
//       cookie jar intact. Asserting "we called the logout URL" would pass on
//       the broken tree, which called it and got an error page.
//   S4  the same-origin restriction still holds (regression guard — correct
//       today, and a fix that accepted an arbitrary target would close #504 by
//       opening an open-redirect)
//       fail arm: a legitimate relative redirect is asserted to SURVIVE, so
//       "everything collapses to /shop" cannot masquerade as a pass.
//
// Secrets: the registration password comes from KC_SEED_USER_PASSWORD in .env
// and is never printed. Only booleans, pass/fail lines, cookie NAMES and
// generated test emails (not secret) are logged.

import { chromium } from "@playwright/test"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"
const PASSWORD = process.env.KC_SEED_USER_PASSWORD
const SHOP = process.env.E2E_SHOP_SLUG || "peckham-jollof-co"
const HEADLESS = process.env.HEADED !== "1"

const results = []
function check(id, name, cond, detail = "") {
  results.push({ id, name, ok: Boolean(cond) })
  console.log(`  ${cond ? "PASS" : "FAIL"}  ${id} ${name}${detail ? `  [${detail}]` : ""}`)
}

function void_(why) {
  console.log(`\nVOID: ${why}`)
  process.exit(2)
}

if (!PASSWORD) void_("KC_SEED_USER_PASSWORD is unset — every arm below would be vacuous")

// Only ONE identity is registered, deliberately. "The next person on the shared
// device" is played by the second Sign in click, not by a second account: what
// S3 asks is whether Keycloak CHALLENGES that click at all. If it does, the
// identity is genuinely gated and who types into the form is beside the point;
// if it does not, the token it issues is A's regardless of who is standing there.
const emailA = `signout504-a-${Date.now()}@example.com`

const session = (page) =>
  page.evaluate(async (b) => {
    const r = await fetch(`${b}/api/customer-auth/session`, { credentials: "include", cache: "no-store" })
    return r.ok ? r.json() : { authenticated: false, httpStatus: r.status }
  }, BASE)

const logoutUrlFor = (page, redirect) =>
  page.evaluate(
    async ([b, r]) => {
      const res = await fetch(`${b}/api/customer-auth/logout-url?redirect=${encodeURIComponent(r)}`, {
        credentials: "include",
        cache: "no-store",
      })
      return res.ok ? res.json() : { err: res.status }
    },
    [BASE, redirect]
  )

/** Cookie NAMES only — never values. The inventory that proves nothing was cleared. */
async function idpCookieNames(context) {
  return (await context.cookies())
    .filter((c) => /KEYCLOAK|AUTH_SESSION/i.test(c.name))
    .map((c) => c.name)
    .sort()
}

async function registerCustomer(page, email) {
  await page.goto(`${BASE}/shop/signin?next=${encodeURIComponent(`/shop/${SHOP}`)}`, {
    waitUntil: "domcontentloaded",
  })
  const create = page.getByRole("button", { name: /create an account/i }).first()
  await create.waitFor({ state: "visible", timeout: 20000 })
  await Promise.all([page.waitForURL(/\/realms\//, { timeout: 30000 }), create.click()])
  await page.locator("#firstName").waitFor({ state: "visible", timeout: 20000 })
  await page.fill("#firstName", "Signout")
  await page.fill("#lastName", "Probe")
  await page.fill("#email", email)
  await page.fill("#password", PASSWORD)
  await page.fill("#password-confirm", PASSWORD)
  await page.locator("input[type=submit], button[type=submit]").first().click()
  try {
    await page.waitForURL((u) => u.href.startsWith(`${BASE}/shop`) && !u.href.includes("/auth/callback"), {
      timeout: 60000,
    })
  } catch (err) {
    // Say WHY: a Keycloak field error looks identical to a hung page from the
    // outside, and the difference is the whole diagnosis.
    const kcError = await page
      .locator("#input-error, .alert-error, #kc-error-message, .kc-feedback-text")
      .allTextContents()
      .catch(() => [])
    console.log(`        registration stalled at ${page.url().split("?")[0]}`)
    console.log(`        keycloak said: ${JSON.stringify(kcError)}`)
    throw err
  }
}

/**
 * Press Sign in and report whether Keycloak asked for a credential.
 *
 * A prompt means the SSO session is gone. No prompt plus an authenticated
 * session means Keycloak recognised a live session and issued a token silently —
 * on a shared device, to whoever is standing there now.
 */
async function signInAttempt(page) {
  await page.goto(`${BASE}/shop/signin?next=${encodeURIComponent(`/shop/${SHOP}`)}`, {
    waitUntil: "domcontentloaded",
  })
  const btn = page.getByRole("button", { name: /^sign in$/i }).first()
  await btn.waitFor({ state: "visible", timeout: 20000 })
  await btn.click()

  let credentialPrompt = false
  const deadline = Date.now() + 25000
  while (Date.now() < deadline) {
    if ((await page.locator("#password, input[name=password]").count().catch(() => 0)) > 0) {
      credentialPrompt = true
      break
    }
    const u = page.url()
    if (u.startsWith(`${BASE}/shop`) && !u.includes("/auth/callback") && !u.includes("/signin")) break
    await page.waitForTimeout(400)
  }
  if (credentialPrompt) return { credentialPrompt, sub: null, authenticated: null }
  await page.waitForTimeout(2500)
  const s = await session(page).catch(() => ({ authenticated: false }))
  return { credentialPrompt, sub: s?.profile?.sub ?? null, authenticated: s.authenticated === true }
}

const browser = await chromium.launch({ headless: HEADLESS })
const context = await browser.newContext()
const page = await context.newPage()

try {
  console.log(`#504 customer sign-out — BASE=${BASE}`)
  console.log(`  A=${emailA}`)

  // --- S0 FAIL ARM: A really is signed in. Without this, every "not signed in
  // as A" below is satisfied by a sign-in that simply never happened.
  await registerCustomer(page, emailA)
  const sessA = await session(page)
  check(
    "S0",
    "customer A really is signed in (fail arm for S2/S3)",
    sessA.authenticated === true && typeof sessA?.profile?.sub === "string" && sessA.profile.sub.length > 0,
    `authenticated=${sessA.authenticated}`
  )
  if (sessA.authenticated !== true) void_("A never signed in")
  const subA = sessA.profile.sub
  const cookiesBefore = await idpCookieNames(context)
  console.log(`        IdP cookies while signed in: ${JSON.stringify(cookiesBefore)}`)

  // --- S1: the redirect URI the APP ITSELF builds, read live.
  const lu = await logoutUrlFor(page, `/shop/${SHOP}`)
  let plr = null
  try {
    plr = new URL(lu.url).searchParams.get("post_logout_redirect_uri")
  } catch {
    /* relative or malformed — plr stays null and S1 reports it */
  }
  check(
    "S1",
    "post_logout_redirect_uri is a reachable origin, not the container bind address",
    typeof plr === "string" && plr.length > 0 && !/0\.0\.0\.0|\[?::\]?:/.test(plr) && plr.startsWith(BASE),
    `post_logout_redirect_uri=${plr}`
  )

  // --- S2: the real nav control, the real navigation.
  await page.goto(`${BASE}/shop/${SHOP}`, { waitUntil: "domcontentloaded" })
  const signOut = page.getByTitle("Sign out").first()
  await signOut.waitFor({ state: "visible", timeout: 30000 })
  await signOut.click()
  await page.waitForTimeout(4000)
  const landed = page.url()
  const landedText = (await page.locator("body").innerText().catch(() => "")).replace(/\s+/g, " ")
  check(
    "S2",
    "sign-out returns the shopper to the storefront, not a Keycloak error page",
    landed.startsWith(BASE) && !/invalid redirect uri|we are sorry/i.test(landedText),
    `landed=${landed.split("?")[0]}`
  )

  // --- the app session must be dead too (not one of the four criteria; a
  // precondition for S3 to mean anything).
  await page.goto(`${BASE}/shop/${SHOP}`, { waitUntil: "domcontentloaded" })
  await page.waitForTimeout(800)
  const sessOut = await session(page)
  check(
    "S2b",
    "the app session is dead after sign-out",
    sessOut.authenticated !== true,
    `authenticated=${sessOut.authenticated}`
  )

  // --- S3: THE SECURITY HALF. No cookie clear — see the header.
  const cookiesAfter = await idpCookieNames(context)
  console.log(`        IdP cookies still in the jar: ${JSON.stringify(cookiesAfter)}`)
  console.log(
    `        (${cookiesAfter.length} of ${cookiesBefore.length} retained — THIS SCRIPT cleared none of them;` +
      ` any reduction is Keycloak's own Set-Cookie during a real logout, which is itself evidence, not a shortcut)`
  )
  const attempt = await signInAttempt(page)
  check(
    "S3",
    "the IdP SSO session is genuinely terminated — a fresh sign-in is challenged for credentials",
    attempt.credentialPrompt === true,
    `credentialPrompt=${attempt.credentialPrompt} silentlySignedInAs=${
      attempt.sub === subA ? "THE PREVIOUS CUSTOMER" : attempt.sub ? "someone else" : "nobody"
    }`
  )
  check(
    "S3b",
    "and specifically: the next person is NOT silently signed in as the previous customer",
    !(attempt.credentialPrompt === false && attempt.authenticated === true && attempt.sub === subA),
    `sameSubAsA=${attempt.sub === subA}`
  )

  // --- S4: the same-origin restriction.
  //
  // Navigate back to the APP ORIGIN first. When S3 passes, the browser is left
  // parked on the Keycloak login page, and `logoutUrlFor` fetches a same-origin
  // relative-to-BASE URL from wherever the page happens to be — cross-origin
  // from there, so it dies with "Failed to fetch" and S4 never runs. Measured
  // while writing this; the same "you are standing on the IdP's origin" trap the
  // cart-identity-boundary script records for localStorage.
  await page.goto(`${BASE}/shop`, { waitUntil: "domcontentloaded" })
  //
  // Read the DECODED redirect target, never the raw URL. Two reasons, both
  // learned the hard way while writing this: the raw URL percent-encodes the
  // target (so `includes("/shop/x")` is false on a perfectly correct answer, and
  // S4b failed for that reason alone), and it carries a live `id_token_hint`
  // that must not be printed into a log.
  const redirectTargetOf = (out) => {
    if (typeof out?.url !== "string") return null
    try {
      return new URL(out.url).searchParams.get("post_logout_redirect_uri") ?? out.url
    } catch {
      return out.url // a relative path — already the target
    }
  }

  const hostile = ["//evil.example.com/steal", "http://evil.example.com/steal", "/\\evil.example.com"]
  const hostileOut = []
  for (const raw of hostile) {
    hostileOut.push(redirectTargetOf(await logoutUrlFor(page, raw)))
  }
  check(
    "S4",
    "a hostile redirect target never escapes this origin",
    hostileOut.length === hostile.length &&
      hostileOut.every((t) => typeof t === "string" && !t.includes("evil.example.com")),
    `targets=${JSON.stringify(hostileOut)}`
  )
  const legitTarget = redirectTargetOf(await logoutUrlFor(page, `/shop/${SHOP}`))
  check(
    "S4b",
    "a legitimate relative redirect still survives (fail arm for S4)",
    typeof legitTarget === "string" && legitTarget.endsWith(`/shop/${SHOP}`),
    `target=${legitTarget}`
  )

  console.log("")
  const failed = results.filter((r) => !r.ok)
  console.log(`${failed.length === 0 ? "ALL PASS" : `${failed.length} FAILED`}  (${results.length} criteria)`)
  if (failed.length) {
    for (const f of failed) console.log(`  FAILED ${f.id} ${f.name}`)
    process.exitCode = 1
  }
} catch (e) {
  console.log(`ERROR: ${e && e.message}`)
  console.log(`  at url: ${page.url()}`)
  process.exitCode = 3
} finally {
  await browser.close()
}
