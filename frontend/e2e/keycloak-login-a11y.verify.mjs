// keycloak-login-a11y.verify.mjs — axe + structural check of the Keycloak customer-realm pages (#848).
//
// Usage (against any Keycloak that serves the jtoye-customers realm with the jtoye theme):
//   KC_BASE=http://127.0.0.1:8085 node e2e/keycloak-login-a11y.verify.mjs
//   KC_BASE=... APP_ORIGIN=http://localhost:3000 node e2e/keycloak-login-a11y.verify.mjs
//
// Exit 0 only when every page passes; exit 1 on any failure; exit 2 (VOID) when a page could not be
// loaded or axe returned nothing, because "found nothing" is not "clean".
//
// The pages: sign-in, register (reached by following the page's own Register link), forgot-password.
// Per page it asserts what #848 / UXT-079 / SW-MRC-12 reported: <html lang>, exactly one <main>, no
// positive tabindex, a <title> that names the page (register is "Register", not "Sign in"), and zero
// axe violations at WCAG 2.2 AA plus best-practice. On register it additionally asserts the password
// rules are on the page BEFORE any failed submit and tied to the field, and that the privacy notice
// link is present and points at the app's /legal/privacy. It also asserts every field the page marks
// with a required asterisk is exposed as required (required / aria-required) and, after an empty
// submit, that every aria-invalid field names its error in aria-describedby (the profile fields
// email, first name and last name come from user-profile-commons.ftl, which the theme overrides).
//
// Wired into the nightly (.github/workflows/e2e-nightly.yml, "Gate - Keycloak customer-realm pages"),
// the only workflow with a running Keycloak. Shown failing on the stock markup, passing on the theme.
import { chromium } from "@playwright/test"
import AxeBuilder from "@axe-core/playwright"

const KC_BASE = process.env.KC_BASE
const APP_ORIGIN = process.env.APP_ORIGIN || "http://localhost:3000"
if (!KC_BASE) {
  console.error("VOID: set KC_BASE to the Keycloak base URL")
  process.exit(2)
}

const AUTH_URL =
  `${KC_BASE}/realms/jtoye-customers/protocol/openid-connect/auth?client_id=storefront-client` +
  `&response_type=code&scope=openid&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM` +
  `&code_challenge_method=S256&redirect_uri=${encodeURIComponent(APP_ORIGIN + "/")}`

const failures = []
let voided = false
const check = (page, name, ok, detail = "") => {
  console.log(`${ok ? "PASS" : "FAIL"} [${page}] ${name}${detail ? ` — ${detail}` : ""}`)
  if (!ok) failures.push(`${page}: ${name}`)
}

async function structural(pg, label) {
  const facts = await pg.evaluate(() => ({
    lang: document.documentElement.getAttribute("lang"),
    title: document.title,
    mains: document.querySelectorAll("main, [role=main]").length,
    positiveTabindex: [...document.querySelectorAll("[tabindex]")]
      .filter((e) => Number(e.getAttribute("tabindex")) > 0)
      .map((e) => `${e.tagName.toLowerCase()}#${e.id || e.name || ""}=${e.getAttribute("tabindex")}`),
  }))
  check(label, "html has a lang", Boolean(facts.lang), String(facts.lang))
  check(label, "exactly one main landmark", facts.mains === 1, `found ${facts.mains}`)
  check(label, "no positive tabindex", facts.positiveTabindex.length === 0, facts.positiveTabindex.join(", "))
  return facts
}

// Every control behind a label that carries the visible required asterisk must expose it too.
async function requiredCues(pg, label) {
  const found = await pg.evaluate(() => {
    const rows = [...document.querySelectorAll("label[for]")]
      .filter((l) => l.parentElement?.querySelector(".required"))
      .map((l) => {
        const c = document.getElementById(l.getAttribute("for"))
        return {
          id: l.getAttribute("for"),
          ok: !!c && (c.required || c.getAttribute("aria-required") === "true"),
        }
      })
    return rows
  })
  check(label, "the page has starred (required) fields to check", found.length >= 3, `${found.length} found`)
  const missing = found.filter((r) => !r.ok).map((r) => r.id)
  check(label, "every starred field is exposed as required", missing.length === 0, missing.join(", "))
}

// Every invalid control must point at a non-empty element that says why.
async function errorsAreLinked(pg, label) {
  const found = await pg.evaluate(() =>
    [...document.querySelectorAll('[aria-invalid="true"]')].map((c) => {
      const ids = (c.getAttribute("aria-describedby") || "").split(/\s+/).filter(Boolean)
      const text = ids.map((i) => document.getElementById(i)?.textContent?.trim() || "").join(" ").trim()
      return { id: c.id || c.name, linked: text.length > 0 }
    })
  )
  check(label, "the empty submit produced invalid fields to check", found.length >= 3, `${found.length} found`)
  const unlinked = found.filter((r) => !r.linked).map((r) => r.id)
  check(label, "every invalid field names its error in aria-describedby", unlinked.length === 0, unlinked.join(", "))
}

async function axe(pg, label) {
  const results = await new AxeBuilder({ page: pg })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"])
    .analyze()
  if (!results.passes || results.passes.length === 0) {
    voided = true
    console.log(`VOID [${label}] axe returned no passes: it did not actually run`)
  }
  const v = results.violations.map((x) => `${x.id}(${x.nodes.length})`)
  check(label, "axe: zero violations", v.length === 0, v.join(", "))
}

const browser = await chromium.launch()
try {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } })
  const pg = await ctx.newPage()

  const resp = await pg.goto(AUTH_URL, { waitUntil: "domcontentloaded" })
  if (!resp || !resp.ok()) {
    console.error(`VOID: sign-in page did not load (${resp ? resp.status() : "no response"})`)
    process.exit(2)
  }
  const login = await structural(pg, "sign-in")
  check("sign-in", "title names the page", /sign in/i.test(login.title), login.title)
  await axe(pg, "sign-in")

  const forgotHref = await pg.getAttribute('a[href*="reset-credentials"]', "href").catch(() => null)
  const registerHref = await pg.getAttribute("#kc-registration a", "href").catch(() => null)

  if (!registerHref) {
    console.error("VOID: the sign-in page has no Register link")
    process.exit(2)
  }
  await pg.goto(new URL(registerHref, KC_BASE).toString(), { waitUntil: "domcontentloaded" })
  const reg = await structural(pg, "register")
  check("register", 'title says "Register"', /register/i.test(reg.title) && !/sign in/i.test(reg.title), reg.title)
  const policy = await pg.evaluate(() => {
    const pw = document.getElementById("password")
    const ids = (pw?.getAttribute("aria-describedby") || "").split(/\s+/).filter(Boolean)
    const text = ids.map((i) => document.getElementById(i)?.textContent?.trim() || "").join(" ")
    const el = ids.map((i) => document.getElementById(i)).find(Boolean)
    const visible = el ? !!(el.offsetWidth || el.offsetHeight) : false
    return { text, visible }
  })
  check(
    "register",
    "password rules are on the page up front, visible, and tied to the field",
    /12 characters/i.test(policy.text) && policy.visible,
    policy.text.slice(0, 120)
  )
  const privacy = await pg.evaluate(() =>
    [...document.querySelectorAll("a")].filter((a) => /privacy/i.test(a.textContent || "")).map((a) => a.href)
  )
  check("register", "privacy notice link present", privacy.some((h) => h === `${APP_ORIGIN}/legal/privacy`), privacy.join(", "))
  await requiredCues(pg, "register")
  await axe(pg, "register")

  // The failed-submit state must not lose the up-front rules.
  // Wait for the navigation the submit causes: reading the DOM straight after the click can still be
  // the page BEFORE the submit, whose fields are not yet marked invalid.
  await Promise.all([pg.waitForNavigation({ waitUntil: "domcontentloaded" }), pg.click('input[type="submit"]')])
  await structural(pg, "register-after-empty-submit")
  await errorsAreLinked(pg, "register-after-empty-submit")
  await axe(pg, "register-after-empty-submit")

  if (forgotHref) {
    await pg.goto(new URL(forgotHref, KC_BASE).toString(), { waitUntil: "domcontentloaded" })
    await structural(pg, "forgot-password")
    await axe(pg, "forgot-password")
  } else {
    console.log("NOTE: no forgot-password link on the sign-in page; that page was not checked")
  }
} finally {
  await browser.close()
}

if (voided) process.exit(2)
if (failures.length) {
  console.error(`\n${failures.length} failure(s):\n  ${failures.join("\n  ")}`)
  process.exit(1)
}
console.log("\nALL PASS")
