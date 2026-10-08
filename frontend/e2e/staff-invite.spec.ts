/**
 * D-07 / D-26 end to end on the compose stack (37-09; run at the 37-15 gate).
 *
 * The whole invitation journey, nothing stubbed:
 *   1. admin-user (a Group admin) opens the Staff page and invites a fresh address as
 *      Shop manager of one shop, through the "Invite someone" form;
 *   2. the invitation email for exactly that address is read out of Mailhog (non-vacuity
 *      control: it must exist), and the link is taken FROM THE EMAIL, never constructed:
 *      it must open the web app at /invite with the token in the fragment;
 *   3. the invitee, in a fresh browser context, opens it: the token leaves the address
 *      bar, and NO request leaves for any origin but the web app, the core API and
 *      Keycloak (token hygiene, T-37-24);
 *   4. they create the account; the page starts the ordinary Keycloak sign-in with the
 *      invited address as login_hint (D-26 "login-hint"); they type the password once
 *      more and land on /dashboard with "You've joined {business}. You can work on
 *      {shop}." — and the parameter is gone from the address bar;
 *   5. back as admin-user, the Staff page lists the person with exactly
 *      "Shop manager · {shop}";
 *   6. the same link opened again is the single "can't be used" state.
 *
 * INSTRUMENT ARM (step 5): the same Staff-page assertion, aimed at a name nobody
 * invited, must FAIL. Without it a People table that rendered every row as
 * "Shop manager · {shop}" — or an assertion that cannot miss — would pass step 5.
 *
 * Owed to 37-15: needs the stack rebuilt from this tree (core-java for the invite
 * endpoints and staff/me businessName, the realm re-import for tenant_id, the frontend
 * for the pages). Against the pre-rebuild runtime step 1 fails: there is no "Invite
 * someone" card — that is the RED. Recorded in .planning/WINDOWS.md.
 *
 * @desktop-only: one run creates one Keycloak user and spends public-endpoint rate
 * limit; the mobile layout of these pages is covered by the jest suites and the
 * 375px backstop below.
 *
 * Run: npx playwright test e2e/staff-invite.spec.ts  (compose stack up, rebuilt)
 */
import { test, expect, type APIRequestContext, type Page } from "@playwright/test"
import { VENDOR_USERNAME, VENDOR_PASSWORD, skipWithoutVendorPassword } from "./vendor-credentials"

const API = process.env.PLAYWRIGHT_API_URL || process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"
const KEYCLOAK = process.env.PLAYWRIGHT_KEYCLOAK_URL || "http://localhost:8085"
const MAILHOG = process.env.MAILHOG_URL || "http://localhost:8025"

type MailhogMessage = {
  Content?: { Headers?: Record<string, string[]>; Body?: string }
  MIME?: { Parts?: Array<{ Headers?: Record<string, string[]>; Body?: string }> | null } | null
}

/** Undo the transfer encoding JavaMail chose; a quoted-printable `=` would otherwise read as `=3D`. */
function decodeBody(headers: Record<string, string[]> | undefined, body: string): string {
  const encoding = (headers?.["Content-Transfer-Encoding"]?.[0] || "").toLowerCase()
  if (encoding === "base64") {
    return Buffer.from(body.replace(/\s+/g, ""), "base64").toString("utf8")
  }
  if (encoding === "quoted-printable") {
    const bytes = body
      .replace(/=\r?\n/g, "")
      .replace(/=([0-9A-Fa-f]{2})/g, (_, hex: string) => String.fromCharCode(parseInt(hex, 16)))
    return Buffer.from(bytes, "latin1").toString("utf8")
  }
  return body
}

function messageText(message: MailhogMessage): string {
  const parts = message.MIME?.Parts ?? []
  if (parts.length > 0) return parts.map((p) => decodeBody(p.Headers, p.Body || "")).join("\n")
  return decodeBody(message.Content?.Headers, message.Content?.Body || "")
}

/** Every message Mailhog holds for `address`, polled until one arrives or the deadline passes. */
async function messagesFor(request: APIRequestContext, address: string): Promise<MailhogMessage[]> {
  const deadline = Date.now() + 30_000
  for (;;) {
    const res = await request.get(`${MAILHOG}/api/v2/search?kind=to&query=${encodeURIComponent(address)}`)
    expect(res.ok(), `Mailhog search answered ${res.status()} — is Mailhog up on ${MAILHOG}?`).toBe(true)
    const items = ((await res.json()) as { items?: MailhogMessage[] }).items ?? []
    if (items.length > 0 || Date.now() > deadline) return items
    await new Promise((r) => setTimeout(r, 500))
  }
}

async function vendorLogin(page: Page) {
  skipWithoutVendorPassword()
  await page.goto("/auth/signin", { waitUntil: "domcontentloaded" })
  const sso = page.getByRole("button", { name: /sign in with keycloak/i })
  await sso.waitFor({ state: "visible", timeout: 15_000 })
  await sso.click()
  await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 30_000 })
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", VENDOR_USERNAME)
    await page.fill("#password", VENDOR_PASSWORD)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 30_000 })
}

/** The People-table row for a person, located by the name the table shows. */
function personRow(page: Page, name: string) {
  return page
    .getByRole("region", { name: "People table" })
    .getByRole("row")
    .filter({ has: page.getByText(name, { exact: true }) })
}

/** The step-5 assertion, shared with the instrument arm so both run the same code. */
async function expectPersonHasExactly(page: Page, name: string, access: string, timeout: number) {
  const row = personRow(page, name)
  await expect(row, `a People row for ${name}`).toHaveCount(1, { timeout })
  await expect(row.getByText(access, { exact: true })).toHaveCount(1, { timeout })
}

test.describe("Staff invitation, end to end (D-07, D-26) @desktop-only", () => {
  test("a Group admin invites, the invitee joins with exactly the invited access", async ({
    browser,
    page,
    request,
    baseURL,
  }, testInfo) => {
    test.setTimeout(180_000)
    const frontendOrigin = new URL(baseURL || "http://localhost:3000").origin
    const stamp = `${Date.now()}`
    const address = `invitee-${stamp}@example.test`
    const firstName = "Invitee"
    const lastName = `E2E${stamp}`
    const displayName = `${firstName} ${lastName}`
    const password = `Jt0ye!Inv-${stamp}-aZ`

    // 1. admin-user invites the address as Shop manager of one shop, through the form.
    await vendorLogin(page)
    await page.goto("/dashboard/staff", { waitUntil: "domcontentloaded" })
    const inviteCard = page.getByRole("region", { name: "Invite someone" })
    await expect(inviteCard, "the Staff page offers an invite form (RED on the pre-37-09 runtime)").toBeVisible({
      timeout: 20_000,
    })
    await inviteCard.getByLabel("Email").fill(address)
    await inviteCard.getByLabel("Role").selectOption("SHOP_MANAGER")
    const shopSelect = inviteCard.getByLabel("Shop")
    const shopOption = shopSelect.locator("option").nth(1)
    await expect(shopOption, "the admin's business must have at least one shop").toHaveCount(1)
    const shopName = ((await shopOption.textContent()) || "").trim()
    await shopSelect.selectOption({ index: 1 })
    await inviteCard.getByRole("button", { name: "Send invitation" }).click()
    await expect(page.getByText(`Invitation sent to ${address}.`, { exact: false })).toBeVisible({
      timeout: 15_000,
    })
    const pending = page.getByRole("region", { name: "Pending invitations" })
    await expect(pending.getByText(address)).toBeVisible()
    await expect(pending.getByText(`Shop manager · ${shopName}`)).toBeVisible()

    // 2. The link, as the email carries it (non-vacuity control: the email must exist).
    const messages = await messagesFor(request, address)
    expect(messages.length, `no invitation email reached Mailhog for ${address}`).toBeGreaterThan(0)
    const text = messageText(messages[0])
    const match = text.match(/https?:\/\/[^\s<>"]+#token=[A-Za-z0-9._-]+/)
    expect(match, `the email carries no #token= link:\n${text}`).not.toBeNull()
    const link = match![0]
    const url = new URL(link)
    expect(url.origin, "the link must open the web app, not the API").toBe(frontendOrigin)
    expect(url.pathname).toBe("/invite")
    expect(url.search, "the token must not travel in a query string").toBe("")

    // 3. The invitee, in a fresh context: only the web app, the API and Keycloak are contacted.
    const invitee = await browser.newContext()
    const tab = await invitee.newPage()
    const allowed = new Set([frontendOrigin, new URL(API).origin, new URL(KEYCLOAK).origin])
    const strayRequests: string[] = []
    tab.on("request", (r) => {
      const u = new URL(r.url())
      if (u.protocol === "data:" || u.protocol === "blob:") return
      if (!allowed.has(u.origin)) strayRequests.push(r.url())
    })
    const sentReferers: string[] = []
    tab.on("request", (r) => {
      const referer = r.headers()["referer"]
      if (referer && referer.includes("/invite")) sentReferers.push(`${r.url()} <- ${referer}`)
    })

    await tab.goto(link, { waitUntil: "domcontentloaded" })
    const heading = tab.getByRole("heading", { level: 1, name: /^Join .+ on J'Toye$/ })
    await expect(heading).toBeVisible({ timeout: 20_000 })
    const business = ((await heading.textContent()) || "").replace(/^Join /, "").replace(/ on J'Toye$/, "")
    expect(new URL(tab.url()).hash, "the token must leave the address bar").toBe("")
    const inviteResponse = await tab.request.get(`${frontendOrigin}/invite`)
    expect(inviteResponse.headers()["referrer-policy"]).toBe("no-referrer")

    // The 375px backstop (UI-SPEC § B2): the card wraps, nothing scrolls sideways.
    await tab.setViewportSize({ width: 375, height: 812 })
    const overflow = await tab.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow, "no horizontal overflow at 375px").toBeLessThanOrEqual(0)
    await tab.setViewportSize({ width: 1280, height: 900 })

    // 4. Create the account, then the ordinary Keycloak sign-in with the address pre-filled.
    await tab.getByLabel("First name").fill(firstName)
    await tab.getByLabel("Last name").fill(lastName)
    await tab.getByLabel("Password", { exact: true }).fill(password)
    await tab.getByRole("button", { name: "Create account and join" }).click()
    await tab.waitForURL(/openid-connect/, { timeout: 30_000 })
    await expect(tab.locator("#username"), "login_hint pre-fills the invited address").toHaveValue(address)
    await tab.fill("#password", password)
    await tab.click("#kc-login")
    await tab.waitForURL(/\/dashboard/, { timeout: 30_000 })
    await expect(
      tab.getByText(`You've joined ${business}. You can work on ${shopName}.`)
    ).toBeVisible({ timeout: 20_000 })
    expect(new URL(tab.url()).searchParams.get("joined"), "the parameter is dropped").toBeNull()
    expect(strayRequests, "requests to an origin other than the app, the API and Keycloak").toEqual([])
    expect(sentReferers, "no request carried the invite page as its Referer").toEqual([])

    // 5. As admin-user, the Staff page lists the person with exactly the invited access.
    await page.goto("/dashboard/staff", { waitUntil: "domcontentloaded" })
    await expect(page.getByRole("heading", { name: "Staff & access" })).toBeVisible({ timeout: 20_000 })
    await expectPersonHasExactly(page, displayName, `Shop manager · ${shopName}`, 15_000)

    //    INSTRUMENT ARM: the same assertion against someone nobody invited must fail.
    let armFailed = false
    try {
      await expectPersonHasExactly(page, `Never Invited ${stamp}`, `Shop manager · ${shopName}`, 2_000)
    } catch {
      armFailed = true
    }
    expect(armFailed, "the step-5 assertion must be able to fail").toBe(true)

    // 6. The same link again: the single unusable state.
    const again = await invitee.newPage()
    await again.goto("about:blank")
    await again.goto(link, { waitUntil: "domcontentloaded" })
    await expect(
      again.getByRole("heading", { name: "This invitation link can't be used" })
    ).toBeVisible({ timeout: 20_000 })

    for (const [name, p] of [["dashboard-joined", tab], ["staff-after-join", page]] as const) {
      const path = testInfo.outputPath(`${name}.png`)
      await p.screenshot({ path, fullPage: true })
      await testInfo.attach(name, { path, contentType: "image/png" })
    }
    await invitee.close()
  })
})
