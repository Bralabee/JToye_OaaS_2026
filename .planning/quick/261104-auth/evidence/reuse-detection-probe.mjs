// Measures what the real jtoye-customers realm does when a SPENT refresh token is replayed:
// does it only refuse the replay, or does it revoke the session (so the successor dies too)?
// Usage: KC_BASE=http://127.0.0.1:PORT node reuse-detection-probe.mjs   (run from frontend/ for @playwright/test)
import { chromium } from "@playwright/test"
import { createHash, randomBytes } from "node:crypto"

const base = process.env.KC_BASE
const verifier = randomBytes(32).toString("base64url")
const challenge = createHash("sha256").update(verifier).digest("base64url")
const redirect = "http://localhost:3000/"
const url = `${base}/realms/jtoye-customers/protocol/openid-connect/auth?client_id=storefront-client&response_type=code&scope=openid&code_challenge=${challenge}&code_challenge_method=S256&redirect_uri=${encodeURIComponent(redirect)}`
const tokenUrl = `${base}/realms/jtoye-customers/protocol/openid-connect/token`

const b = await chromium.launch()
const p = await (await b.newContext()).newPage()
let code = null
await p.goto(url)
await p.goto(new URL(await p.getAttribute("#kc-registration a", "href"), base).toString())
const pw = "Str0ng!" + randomBytes(6).toString("hex")
await p.fill("#email", `probe-${Date.now()}@example.com`)
await p.fill("#password", pw)
await p.fill("#password-confirm", pw)
await p.fill("#firstName", "P")
await p.fill("#lastName", "Q")
await p.click('input[type="submit"]')
await p.waitForURL(/[?&]code=/, { timeout: 15000 }).catch(() => {})
code = new URL(p.url()).searchParams.get("code")
await b.close()
if (!code) throw new Error("no code captured")

const post = async (params) => {
  const r = await fetch(tokenUrl, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(params) })
  const j = await r.json().catch(() => ({}))
  return { status: r.status, error: j.error, desc: j.error_description, refresh: j.refresh_token }
}
const t0 = await post({ grant_type: "authorization_code", client_id: "storefront-client", code, redirect_uri: redirect, code_verifier: verifier })
const refresh = (rt) => post({ grant_type: "refresh_token", client_id: "storefront-client", refresh_token: rt })
const r1 = await refresh(t0.refresh)
console.log("R0 -> R1:", r1.status)
const replay = await refresh(t0.refresh)
console.log("replay of spent R0:", replay.status, replay.error, replay.desc)
const r2 = await refresh(r1.refresh)
console.log("successor R1 after the replay:", r2.status, r2.error ?? "", r2.desc ?? "")
