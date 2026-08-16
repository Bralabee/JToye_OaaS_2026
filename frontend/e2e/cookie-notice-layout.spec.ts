/**
 * The cookie notice occupies NO layout space (LGL-01, plan 31-16).
 *
 * ── WHY THIS FILE EXISTS INSTEAD OF A CLS COMPARISON ────────────────────────
 * The plan's original criterion was "measure CLS on `/` and break it by making
 * the notice `position: static`". That was run, and it is VACUOUS. Measured on
 * the real tree, both directions:
 *
 *     position: fixed   ->  CLS = 0.1793
 *     position: static  ->  CLS = 0.1793      (identical, 4 d.p.)
 *
 * The break arm cannot fail, and the reason is structural rather than incidental:
 * the notice mounts at the END of `<body>`, after all page content. Layout shift
 * scores the movement of content that is ALREADY laid out, and appending an
 * element below everything moves nothing — on this page it lands ~1200px down,
 * far below a 812px fold. A page-level CLS number is additionally dominated by
 * the hero island's pre-existing 0.1793, so the notice's contribution could not
 * be seen in it even if there were one.
 *
 * Reporting "CLS unchanged, therefore zero layout shift" from that pair would be
 * a criterion observed only passing, which is exactly what this project treats
 * as no evidence at all.
 *
 * ── THE STRICTLY STRONGER FORM ──────────────────────────────────────────────
 * Ask the question the criterion actually cares about: does mounting the notice
 * change the document's layout? Compare the SAME build with the notice shown and
 * with it suppressed (via its own ack key, so no rebuild and no code difference
 * between arms), and assert that both the document height and the page-space Y
 * of a stable landmark are unchanged.
 *
 * This is strictly stronger on three counts: it isolates the notice's own
 * contribution instead of drowning it in a page-level score; it fires for
 * `position: static` (an in-flow notice grows `scrollHeight` by its own height);
 * and it carries a POSITIVE CONTROL proving the measurement can detect exactly
 * that growth. Verified in both directions — see the plan summary.
 */
import { test, expect, type Browser, type BrowserContext } from "@playwright/test"

const BASE = process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000"

/** Must match `COOKIE_NOTICE_ACK_KEY` / `COOKIE_POLICY_VERSION` in lib/consent.ts. */
const ACK_KEY = "jtoye-cookie-notice-ack"
const ACK_VALUE = "2026-08-16"

/** Sub-pixel/rounding slack only. The notice is ~80px tall, so an in-flow notice
 *  clears this by an order of magnitude — it is not headroom for a real shift. */
const LAYOUT_TOLERANCE_PX = 2

type Layout = { noticePresent: number; scrollHeight: number; anchorY: number }

async function measure(context: BrowserContext, suppressNotice: boolean): Promise<Layout> {
  const page = await context.newPage()
  if (suppressNotice) {
    await page.addInitScript(
      ([k, v]) => window.localStorage.setItem(k as string, v as string),
      [ACK_KEY, ACK_VALUE]
    )
  }
  await page.goto(`${BASE}/`, { waitUntil: "domcontentloaded" })
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible({ timeout: 20_000 })
  await page
    .waitForFunction(() => Array.from(document.images).every((i) => i.complete), undefined, {
      timeout: 20_000,
    })
    .catch(() => {
      /* measure what we have rather than failing on a slow image */
    })
  // Let the notice's 200ms entrance and any late hydration land.
  await page.waitForTimeout(1200)

  const noticePresent = await page.getByRole("region", { name: "Cookie notice" }).count()
  const scrollHeight = await page.evaluate(() => document.body.scrollHeight)
  const anchorY = await page
    .getByRole("heading", { name: "How it works" })
    .evaluate((el) => el.getBoundingClientRect().top + window.scrollY)

  await page.close()
  return { noticePresent, scrollHeight, anchorY }
}

async function freshContext(browser: Browser) {
  return browser.newContext({ viewport: { width: 375, height: 812 }, isMobile: true })
}

test.describe("Cookie notice — layout cost", () => {
  test("occupies no layout space, with both arms and a positive control", async ({ browser }) => {
    const shownCtx = await freshContext(browser)
    const hiddenCtx = await freshContext(browser)

    const shown = await measure(shownCtx, false)
    const hidden = await measure(hiddenCtx, true)

    test.info().annotations.push({
      type: "notice-layout",
      description:
        `notice SHOWN  present=${shown.noticePresent} scrollHeight=${shown.scrollHeight} anchorY=${shown.anchorY.toFixed(2)} · ` +
        `notice HIDDEN present=${hidden.noticePresent} scrollHeight=${hidden.scrollHeight} anchorY=${hidden.anchorY.toFixed(2)}`,
    })

    // ---- NON-VACUITY. Without these the comparison below is satisfied by a
    // notice that never rendered in EITHER arm, which is the whole failure mode.
    expect(shown.noticePresent, "the notice did not render in the SHOWN arm — nothing was compared").toBe(1)
    expect(hidden.noticePresent, "the notice rendered in the HIDDEN arm — the arms are not different").toBe(0)

    // ---- THE ASSERTION. An in-flow notice grows the document by its own height.
    expect(
      Math.abs(shown.scrollHeight - hidden.scrollHeight),
      "mounting the notice changed the document height — it is occupying layout space, " +
        "so it is no longer out of flow and will cost CLS on a page where it lands above the fold"
    ).toBeLessThanOrEqual(LAYOUT_TOLERANCE_PX)

    expect(
      Math.abs(shown.anchorY - hidden.anchorY),
      "mounting the notice moved existing page content"
    ).toBeLessThanOrEqual(LAYOUT_TOLERANCE_PX)

    // ---- POSITIVE CONTROL. Prove `scrollHeight` actually detects an in-flow
    // append of the notice's size — otherwise the equality above proves only
    // that the instrument is blind. This reproduces exactly what `position:
    // static` does to the notice.
    const controlPage = await hiddenCtx.newPage()
    await controlPage.addInitScript(
      ([k, v]) => window.localStorage.setItem(k as string, v as string),
      [ACK_KEY, ACK_VALUE]
    )
    await controlPage.goto(`${BASE}/`, { waitUntil: "domcontentloaded" })
    await expect(controlPage.getByRole("heading", { level: 1 })).toBeVisible({ timeout: 20_000 })
    await controlPage.waitForTimeout(800)

    const before = await controlPage.evaluate(() => document.body.scrollHeight)
    const after = await controlPage.evaluate(() => {
      const el = document.createElement("div")
      el.style.height = "80px"
      el.textContent = "in-flow control block"
      document.body.appendChild(el)
      return document.body.scrollHeight
    })

    expect(
      after - before,
      "POSITIVE CONTROL FAILED: appending an 80px in-flow block did not change " +
        "document height, so this measurement cannot detect an in-flow notice and " +
        "the equality assertions above prove nothing"
    ).toBeGreaterThanOrEqual(40)

    await controlPage.close()
    await shownCtx.close()
    await hiddenCtx.close()
  })
})
