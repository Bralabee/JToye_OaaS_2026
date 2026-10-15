/**
 * A real non-admin session on the compose stack: invite a person as Staff, take the
 * link out of Mailhog, register on /invite and sign in (37-55, for G-37-12).
 *
 * WHY THIS FILE EXISTS
 *
 * The G-37-12 refusal on /dashboard/webhooks can only be observed by someone the API
 * refuses. The dev realm's seeded vendor is a Group admin, so the only honest way to
 * get a Staff session is the same journey a real invitee takes. That journey is
 * proven, step by step, in `e2e/staff-invite.spec.ts` (37-09, run at the 37-15 and
 * 37-53 gates and nightly). Its helpers are private to that spec, and the spec stays
 * byte-identical (its assertions are goods evidence), so the behaviour is reproduced
 * here as a module rather than imported from it.
 *
 * WHAT IT CREATES, PER CALL: one Keycloak user in the vendor realm and one invitation
 * (accepted by the call itself, so it does not linger in "Pending invitations"). The
 * address is on the non-routable `example.test`.
 *
 * THE PASSWORD (T-37-127): generated per call, held only in this function's memory,
 * typed into the two forms that need it and dropped. It is never returned, logged,
 * annotated, attached or written to a file.
 */
import { randomBytes } from "node:crypto"
import {
  expect,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
} from "@playwright/test"
import { VENDOR_USERNAME, VENDOR_PASSWORD, skipWithoutVendorPassword } from "../vendor-credentials"

const KEYCLOAK = process.env.PLAYWRIGHT_KEYCLOAK_URL || "http://localhost:8085"
const MAILHOG = process.env.MAILHOG_URL || "http://localhost:8025"

export type MailhogMessage = {
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

/** The decoded text of every MIME part, or of the single body. */
export function messageText(message: MailhogMessage): string {
  const parts = message.MIME?.Parts ?? []
  if (parts.length > 0) return parts.map((p) => decodeBody(p.Headers, p.Body || "")).join("\n")
  return decodeBody(message.Content?.Headers, message.Content?.Body || "")
}

/** Every message Mailhog holds for `address`, polled until one arrives or the deadline passes. */
export async function messagesFor(request: APIRequestContext, address: string): Promise<MailhogMessage[]> {
  const deadline = Date.now() + 30_000
  for (;;) {
    const res = await request.get(`${MAILHOG}/api/v2/search?kind=to&query=${encodeURIComponent(address)}`)
    expect(res.ok(), `Mailhog search answered ${res.status()} — is Mailhog up on ${MAILHOG}?`).toBe(true)
    const items = ((await res.json()) as { items?: MailhogMessage[] }).items ?? []
    if (items.length > 0 || Date.now() > deadline) return items
    await new Promise((r) => setTimeout(r, 500))
  }
}

/** The seeded Group admin (admin-user), through "Sign in with Keycloak". Skips with a named remedy without a password. */
export async function vendorLogin(page: Page) {
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

/**
 * The toast viewport. Radix renders every toast twice: once in this region and once as an
 * aria-live announcement outside it, so an unscoped getByText matches two nodes and fails
 * strict mode (first measured at the 37-15 live run). Scope toast text here.
 */
export function toasts(page: Page) {
  return page.getByRole("region", { name: /^Notifications/ })
}

/** A password Keycloak's policy accepts: upper, lower, digit and symbol, unique per call. */
function oneTimePassword(): string {
  return `Jt0ye!${randomBytes(12).toString("base64url")}-aZ9`
}

export interface StaffInvitee {
  /** The invitee's own browser context. The caller closes it. */
  context: BrowserContext
  /** Signed in as the invitee, on /dashboard. */
  page: Page
  address: string
  shopName: string
}

/**
 * Invite a per-run address as Staff on the admin's first real shop, then join as that
 * person in a fresh context. `adminPage` must already be signed in as a Group admin.
 */
export async function inviteAndJoinAsStaff({
  browser,
  adminPage,
  request,
  baseURL,
}: {
  browser: Browser
  adminPage: Page
  request: APIRequestContext
  baseURL: string | undefined
}): Promise<StaffInvitee> {
  const frontendOrigin = new URL(baseURL || "http://localhost:3000").origin
  const stamp = `${Date.now()}`
  const address = `staff-${stamp}@example.test`

  // 1. The admin invites the address as Staff of one shop, through the form.
  await adminPage.goto("/dashboard/staff", { waitUntil: "domcontentloaded" })
  const inviteCard = adminPage.getByRole("region", { name: "Invite someone" })
  await expect(inviteCard, "the Staff page offers the invite form").toBeVisible({ timeout: 20_000 })
  await inviteCard.getByLabel("Email").fill(address)
  await inviteCard.getByLabel("Role").selectOption("STAFF")
  const shopSelect = inviteCard.getByLabel("Shop")
  const shopOption = shopSelect.locator("option").nth(1)
  await expect(shopOption, "the admin's business must have at least one shop").toHaveCount(1)
  const shopName = ((await shopOption.textContent()) || "").trim()
  await shopSelect.selectOption({ index: 1 })
  await inviteCard.getByRole("button", { name: "Send invitation" }).click()
  await expect(toasts(adminPage).getByText(`Invitation sent to ${address}.`, { exact: false })).toBeVisible({
    timeout: 15_000,
  })

  // 2. The link, as the email carries it (non-vacuity control: the email must exist).
  const messages = await messagesFor(request, address)
  expect(messages.length, `no invitation email reached Mailhog for ${address}`).toBeGreaterThan(0)
  const text = messageText(messages[0])
  const match = text.match(/https?:\/\/[^\s<>"]+#token=[A-Za-z0-9._-]+/)
  expect(match, `the invitation email for ${address} carries no #token= link`).not.toBeNull()
  const link = match![0]
  expect(new URL(link).origin, "the link must open the web app").toBe(frontendOrigin)

  // 3. Register on /invite in a fresh context, then the ordinary Keycloak sign-in.
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto(link, { waitUntil: "domcontentloaded" })
  const heading = page.getByRole("heading", { level: 1, name: /^Join .+ on J'Toye$/ })
  await expect(heading).toBeVisible({ timeout: 20_000 })
  const business = ((await heading.textContent()) || "").replace(/^Join /, "").replace(/ on J'Toye$/, "")

  const password = oneTimePassword()
  await page.getByLabel("First name").fill("Staff")
  await page.getByLabel("Last name").fill(`UAT${stamp}`)
  await page.getByLabel("Password", { exact: true }).fill(password)
  await page.getByRole("button", { name: "Create account and join" }).click()
  await page.waitForURL(/openid-connect/, { timeout: 30_000 })
  expect(new URL(page.url()).origin, "the sign-in is Keycloak's").toBe(new URL(KEYCLOAK).origin)
  await expect(page.locator("#username"), "login_hint pre-fills the invited address").toHaveValue(address)
  await page.fill("#password", password)
  await page.click("#kc-login")

  // 4. Signed in, on the dashboard, with the joined confirmation for exactly this shop.
  await page.waitForURL(/\/dashboard/, { timeout: 30_000 })
  await expect(toasts(page).getByText(`You've joined ${business}. You can work on ${shopName}.`)).toBeVisible({
    timeout: 20_000,
  })
  return { context, page, address, shopName }
}
