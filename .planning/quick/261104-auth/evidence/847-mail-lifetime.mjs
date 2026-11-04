import { chromium } from "@playwright/test"
const [, , base, mh, tag] = process.argv
const email = `${tag}-${Date.now()}@example.com`
// Throwaway account on a throwaway Keycloak container: the password is generated per run, never stored.
const pw = `Aa1!${(await import("node:crypto")).randomUUID()}`
const url = `${base}/realms/jtoye-customers/protocol/openid-connect/auth?client_id=storefront-client&response_type=code&scope=openid&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&redirect_uri=${encodeURIComponent("http://localhost:3000/")}`

const b = await chromium.launch()
const p = await (await b.newContext()).newPage()
await p.goto(url)
await p.goto(new URL(await p.getAttribute("#kc-registration a", "href"), base).toString())
console.log("register page:", p.url(), await p.title(), await p.evaluate(() => [...document.querySelectorAll("input")].map((i) => i.id).join(",")))
await p.fill("#email", email)
if (await p.locator("#password").count()) { await p.fill("#password", pw)
await p.fill("#password-confirm", pw) }
await p.fill("#firstName", "Test")
await p.fill("#lastName", "User")
await p.click('input[type="submit"]')
await p.waitForLoadState("domcontentloaded")
console.log("after register:", await p.title())

// reset-password mail
await p.goto(url)
await p.click('a[href*="reset-credentials"]')
await p.fill("#username", email)
await p.click('input[type="submit"]')
await p.waitForLoadState("domcontentloaded")
console.log("after forgot:", await p.title())
await b.close()

await new Promise((r) => setTimeout(r, 1500))
const res = await (await fetch(`${mh}/api/v2/messages`)).json()
const mine = res.items.filter((m) => (m.Content.Headers.To || []).some((t) => t.includes(email)))
for (const m of mine) {
  const body = m.Content.Body.replace(/=\r?\n/g, "").replace(/=3D/g, "=").replace(/=([0-9A-F]{2})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
  const link = (body.match(/https?:\/\/[^\s"<>]*key=[^\s"<>]+/) || [])[0]
  const key = link && new URL(link).searchParams.get("key")
  const payload = key && JSON.parse(Buffer.from(key.split(".")[1], "base64url").toString())
  const sent = Date.parse(m.Created)
  const kind = (link || "").includes("reset-credentials") ? "reset-credentials" : (link || "").includes("verify-email") ? "verify-email" : "?"
  const mins = payload ? Math.round((payload.exp * 1000 - sent) / 60000) : null
  console.log(`MAIL ${kind}: subject=${(m.Content.Headers.Subject || [""])[0]} link-lifetime=${mins} min ; body-says=${(body.match(/expire[^.]*\./i) || [""])[0].replace(/\s+/g, " ")}`)
}
