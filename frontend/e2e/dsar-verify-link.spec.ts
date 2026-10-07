/**
 * #839 acceptance on the compose stack (D-04, 31.1-20): the link in the
 * "Confirm your data request" email opens a readable page on the WEB app, and
 * confirming takes one deliberate press.
 *
 * Before 31.1-20 the emailed link pointed at http://localhost:8080/api/... —
 * nothing listens on 8080 in this stack, and where the link did reach the API
 * it answered raw JSON. So on the canonical runtime no data-subject request
 * could ever be confirmed by the person who made it.
 *
 * This spec drives the REAL path end to end, nothing stubbed:
 *   1. lodge a DSAR for a unique address through the public intake API;
 *   2. read the verification email for exactly that address out of Mailhog
 *      (non-vacuity control: the message must exist, or the test fails);
 *   3. take the link FROM THE EMAIL — never a constructed URL — and assert its
 *      origin is the frontend's and its path is /data-request/confirm, with
 *      the token in the fragment and not in a query string;
 *   4. open it: the page must send nothing until "Confirm my request" is
 *      pressed, and must clear the fragment from the address bar;
 *   5. press: the confirmed copy shows, and the page is not JSON;
 *   6. open the same link again and press: "already confirmed".
 *
 * Owed to 31.1-30: this needs the stack rebuilt from this tree (core-java for
 * the link format and compose's DSAR_VERIFY_BASE_URL, frontend for the page).
 * Against the pre-31.1-20 runtime step 3 fails on the origin — that is the RED.
 *
 * Rate limits: the intake allows 5 requests per IP per hour and 3 per address
 * per day. Each project lodges ONE request for its own unique address, so one
 * full run (mobile + desktop) spends 2 of the 5.
 *
 * Run: npx playwright test e2e/dsar-verify-link.spec.ts  (compose stack up)
 */
import { test, expect, type APIRequestContext } from "@playwright/test"

const API = process.env.PLAYWRIGHT_API_URL || process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"
const MAILHOG = process.env.MAILHOG_URL || "http://localhost:8025"
const INTAKE_PATH = "/api/v1/public/gdpr/dsar"
const VERIFY_PATH = "/api/v1/public/gdpr/dsar/verify"

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
  if (parts.length > 0) {
    return parts.map((p) => decodeBody(p.Headers, p.Body || "")).join("\n")
  }
  return decodeBody(message.Content?.Headers, message.Content?.Body || "")
}

/** Every message Mailhog holds for `address`, polled until one arrives or the deadline passes. */
async function messagesFor(request: APIRequestContext, address: string): Promise<MailhogMessage[]> {
  const deadline = Date.now() + 20_000
  for (;;) {
    const res = await request.get(
      `${MAILHOG}/api/v2/search?kind=to&query=${encodeURIComponent(address)}`
    )
    expect(res.ok(), `Mailhog search answered ${res.status()} — is Mailhog up on ${MAILHOG}?`).toBe(true)
    const data = (await res.json()) as { items?: MailhogMessage[] }
    const items = data.items ?? []
    if (items.length > 0 || Date.now() > deadline) return items
    await new Promise((r) => setTimeout(r, 500))
  }
}

test.describe("DSAR verification link (#839)", () => {
  test("the emailed link opens the web confirm page and confirms on a press", async ({
    page,
    request,
    baseURL,
  }, testInfo) => {
    const frontendOrigin = new URL(baseURL || "http://localhost:3000").origin
    const address = `dsar-839-${testInfo.project.name}-${Date.now()}@example.test`

    // 1. Lodge the request through the public intake, exactly as a subject would.
    const intake = await request.post(`${API}${INTAKE_PATH}`, {
      data: { email: address, requestType: "ACCESS" },
      headers: { "Idempotency-Key": `e2e-839-${testInfo.project.name}-${Date.now()}` },
    })
    expect(
      intake.status(),
      "the intake must accept the request (429 = the per-IP limit of 5/hour was spent by earlier runs)"
    ).toBe(202)

    // 2. Non-vacuity control: the verification email for THIS address exists.
    const messages = await messagesFor(request, address)
    expect(messages.length, `no verification email reached Mailhog for ${address}`).toBeGreaterThan(0)
    const text = messageText(messages[0])
    expect(messages[0].Content?.Headers?.Subject?.[0] || "").toContain("Confirm your data request")

    // 3. The link as the email carries it.
    const match = text.match(/https?:\/\/[^\s<>"]+#token=[A-Za-z0-9_-]+/)
    expect(match, `the email carries no #token= link:\n${text}`).not.toBeNull()
    const link = match![0]
    const url = new URL(link)
    expect(url.origin, "the link must open the web app, not the API").toBe(frontendOrigin)
    expect(url.pathname).toBe("/data-request/confirm")
    expect(url.search, "the token must not travel in a query string").toBe("")
    expect(text).not.toContain("?token=")
    expect(text).not.toContain("/api/")

    // 4. Opening the page spends nothing.
    const verifyPosts: string[] = []
    page.on("request", (r) => {
      if (r.url().includes(VERIFY_PATH)) verifyPosts.push(`${r.method()} ${r.url()}`)
    })
    await page.goto(link, { waitUntil: "domcontentloaded" })
    const confirmButton = page.getByRole("button", { name: /confirm my request/i })
    await expect(confirmButton).toBeVisible({ timeout: 15_000 })
    await page.waitForTimeout(1_000)
    expect(verifyPosts, "the page must not confirm before the press").toEqual([])
    expect(new URL(page.url()).hash, "the token must be cleared from the address bar").toBe("")

    for (const width of [390, 768, 1280]) {
      await page.setViewportSize({ width, height: 900 })
      await page.waitForTimeout(300)
      const path = testInfo.outputPath(`dsar-confirm-ready-${width}.png`)
      await page.screenshot({ path, fullPage: true })
      await testInfo.attach(`dsar-confirm-ready-${width}`, { path, contentType: "image/png" })
    }

    // 5. One press confirms, and the page reads as a page, not JSON.
    await confirmButton.click()
    await expect(page.getByRole("heading", { name: /your request is confirmed/i })).toBeVisible({
      timeout: 15_000,
    })
    expect(verifyPosts).toHaveLength(1)
    expect(verifyPosts[0]).toMatch(/^POST /)
    const body = (await page.locator("body").innerText()).trim()
    expect(body.startsWith("{")).toBe(false)
    expect(body).not.toContain('"status":')

    for (const width of [390, 768, 1280]) {
      await page.setViewportSize({ width, height: 900 })
      await page.waitForTimeout(300)
      const path = testInfo.outputPath(`dsar-confirm-confirmed-${width}.png`)
      await page.screenshot({ path, fullPage: true })
      await testInfo.attach(`dsar-confirm-confirmed-${width}`, { path, contentType: "image/png" })
    }

    // 6. The same link again: already confirmed, distinct from confirmed and from invalid.
    // Leave the page first. The page cleared the fragment, so goto(link) from here differs only
    // by #token= and Chromium treats it as a same-document fragment navigation: nothing reloads
    // (31.1-30 timed out waiting for the button on the confirmed page). Opening the email link
    // again is a fresh load for a person, and about:blank makes it one here.
    await page.goto("about:blank")
    await page.goto(link, { waitUntil: "domcontentloaded" })
    await page.getByRole("button", { name: /confirm my request/i }).click()
    await expect(page.getByRole("heading", { name: /already confirmed/i })).toBeVisible({
      timeout: 15_000,
    })
    await expect(page.getByRole("heading", { name: /your request is confirmed/i })).toHaveCount(0)
    await expect(page.getByRole("heading", { name: /can't be used/i })).toHaveCount(0)
  })
})
