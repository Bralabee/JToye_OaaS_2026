/**
 * 37-51 (UI-SPEC § F1, F4, F6; RWO-081 / UXT-081, RWO-084 / UXT-084, RWO-080 tracker part; D-05)
 * — the browser instruments that see what axe misses on the tracking journey.
 *
 * axe reports 0 violations on all three defects this plan fixes, because none of them is a
 * static-markup rule:
 *   - UXT-081: a screen reader heard NOTHING when the order moved on. That is a property of what
 *     the page WRITES into a live region over time, so the instrument is a MutationObserver
 *     recorder on `[role=status],[aria-live]`, installed BEFORE the vendor moves the order.
 *   - UXT-084: basket, checkout and order shared one title, so the route announcer said the same
 *     thing at every step. The instrument is `page.title()` at each step.
 *   - UXT-080: at 360px with a 130% root font the tracker could not fit its labels. The instrument
 *     is `scrollWidth <= clientWidth` plus the computed `text-overflow` of every step label.
 *
 * TWO BLOCKS.
 *
 * `live tracking journey` — WRITTEN AND LISTED IN 37-51, RUN IN 37-53 on the rebuilt stack (the
 * gate plan owns the stack; 37-51 is code-only). It places a REAL cash order at Mama Ade's, opens
 * its page, installs the recorder, confirms the order through the vendor API
 * (POST /api/v1/orders/{id}/confirm) and asserts the announcement; then the same on /track with
 * start-preparation. Its INSTRUMENT arms run on the same real pages: with the status region
 * removed by `page.evaluate`, a further real transition records NOTHING and the announcement
 * assertion THROWS; a wrong expected title fails; a label forced to ellipsis is caught by the
 * overflow probe. The order is cancelled afterwards (order-cleanup.ts), so it holds no cash-cap
 * slot. Tagged @mobile-only: one real order per run is enough, and the 360px check sets its own
 * viewport.
 *
 * `instruments can fail @stack-free` — the same three probes against hand-built fixtures
 * (page.setContent, no server at all), on every run: a positive control for each probe, then the
 * broken input on which each must fail. This is the `public-a11y.spec.ts` "axe instrument"
 * precedent: a green result from the live block means "announced / titled / wrapping", not "the
 * recorder silently stopped recording".
 *
 * Run: npx playwright test e2e/a11y-tracking.spec.ts            (live: needs the stack + vendor password)
 *      npx playwright test e2e/a11y-tracking.spec.ts --grep @stack-free
 */
import { test, expect, type APIRequestContext, type Page } from "@playwright/test"
import { awaitCashConfirmation } from "./cash-confirmation"
import { cancelOrdersByNumber, uniqueContact, vendorShopId, vendorToken } from "./order-cleanup"
import { skipWithoutVendorPassword } from "./vendor-credentials"

const API = process.env.PLAYWRIGHT_API_URL || "http://localhost:9090"
const SHOP_SLUG = "mama-ades-kitchen"
const SHOP_NAME = "Mama Ade's Kitchen"

// ─── The three probes ─────────────────────────────────────────────────────────────────────────

/**
 * Install the announcement recorder: a MutationObserver over the whole document that, after every
 * batch of mutations, reads each `[role=status],[aria-live]` element and records its text whenever
 * it CHANGED to something non-empty. That is what a screen reader acts on. Re-installing resets.
 */
async function installAnnouncementRecorder(page: Page): Promise<void> {
  await page.evaluate(() => {
    type W = Window & { __a11yRecorder?: MutationObserver; __announcements?: string[] }
    const w = window as W
    w.__a11yRecorder?.disconnect()
    w.__announcements = []
    const last = new WeakMap<Element, string>()
    const read = () => {
      document.querySelectorAll("[role=status],[aria-live]").forEach((el) => {
        const text = (el.textContent ?? "").replace(/\s+/g, " ").trim()
        if (last.get(el) !== text) {
          last.set(el, text)
          if (text) w.__announcements!.push(text)
        }
      })
    }
    // Baseline: what is already in a region is not an announcement made after installation.
    document.querySelectorAll("[role=status],[aria-live]").forEach((el) => {
      last.set(el, (el.textContent ?? "").replace(/\s+/g, " ").trim())
    })
    w.__a11yRecorder = new MutationObserver(read)
    w.__a11yRecorder.observe(document.documentElement, {
      childList: true,
      characterData: true,
      subtree: true,
    })
  })
}

async function recordedAnnouncements(page: Page): Promise<string[]> {
  return page.evaluate(() => (window as Window & { __announcements?: string[] }).__announcements ?? [])
}

/** The assertion every arm is about: some recorded announcement contains `expected`. */
function expectAnnounced(records: string[], expected: string): void {
  expect(
    records.some((r) => r.includes(expected)),
    `no live-region announcement contained "${expected}"; recorded: ${JSON.stringify(records)}`,
  ).toBe(true)
}

/** Remove every live region from the page, as a regression that dropped it would. */
async function removeLiveRegions(page: Page): Promise<number> {
  return page.evaluate(() => {
    const regions = Array.from(document.querySelectorAll("[role=status],[aria-live]"))
    regions.forEach((el) => el.remove())
    return regions.length
  })
}

/**
 * The F6 probe for a tracker (or any container): the container does not scroll sideways, and no
 * step label is clipped — neither wider than its own box nor drawn with `text-overflow: ellipsis`
 * on a box that hides its overflow. Returns the offenders, so an arm can assert it found one.
 */
async function overflowOffenders(page: Page, container: string, labels: string): Promise<string[]> {
  return page.evaluate(
    ({ container, labels }) => {
      const offenders: string[] = []
      const box = document.querySelector(container)
      if (!box) return [`no element matches ${container}`]
      if (box.scrollWidth > box.clientWidth) {
        offenders.push(`${container} scrollWidth ${box.scrollWidth} > clientWidth ${box.clientWidth}`)
      }
      const doc = document.documentElement
      if (doc.scrollWidth > doc.clientWidth) {
        offenders.push(`document scrollWidth ${doc.scrollWidth} > clientWidth ${doc.clientWidth}`)
      }
      const found = box.querySelectorAll(labels)
      if (found.length === 0) offenders.push(`no labels match ${labels}`)
      found.forEach((el) => {
        const cs = getComputedStyle(el)
        const name = (el.textContent ?? "").trim()
        if (cs.textOverflow === "ellipsis" && cs.overflow !== "visible") offenders.push(`"${name}" ellipsis`)
        if (el.scrollWidth > el.clientWidth + 1) {
          offenders.push(`"${name}" scrollWidth ${el.scrollWidth} > clientWidth ${el.clientWidth}`)
        }
      })
      return offenders
    },
    { container, labels },
  )
}

/** 360px wide, root font 130% — the UXT-080 condition. */
async function largeTextNarrow(page: Page): Promise<void> {
  await page.setViewportSize({ width: 360, height: 780 })
  await page.addStyleTag({ content: "html { font-size: 130% !important; }" })
}

// ─── Vendor-side transitions (the real API, as the kitchen would) ─────────────────────────────

async function vendorTransition(
  request: APIRequestContext,
  orderNumber: string,
  action: "confirm" | "start-preparation" | "mark-ready",
): Promise<void> {
  const token = await vendorToken(request)
  expect(token, "no vendor token (password grant refused, or KEYCLOAK_CLIENT_SECRET unset)").toBeTruthy()
  const shopId = await vendorShopId(request, token!, SHOP_SLUG)
  expect(shopId, `the vendor's shop list has no ${SHOP_SLUG}`).toBeTruthy()
  const list = await request.get(`${API}/api/v1/orders/shop/${shopId}?size=100&sort=createdAt,desc`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(list.status(), "GET /api/v1/orders/shop/{shopId}").toBe(200)
  const page = (await list.json()) as { content?: Array<{ id: string; orderNumber: string }> }
  const id = page.content?.find((o) => o.orderNumber === orderNumber)?.id
  expect(id, `order ${orderNumber} is not in the vendor's list`).toBeTruthy()
  const res = await request.post(`${API}/api/v1/orders/${id}/${action}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(res.status(), `POST /api/v1/orders/{id}/${action}`).toBe(200)
}

/** A poll is every 15 s; allow two of them plus the transition. */
const ANNOUNCE_TIMEOUT = 40_000

async function awaitAnnouncement(page: Page, expected: string): Promise<void> {
  await expect
    .poll(async () => (await recordedAnnouncements(page)).some((r) => r.includes(expected)), {
      timeout: ANNOUNCE_TIMEOUT,
      message: `waiting for a live-region announcement containing "${expected}"`,
    })
    .toBe(true)
}

// ─── The live journey (run in 37-53) ──────────────────────────────────────────────────────────

const placed: string[] = []
test.afterEach(async ({ request }) => {
  await cancelOrdersByNumber(request, SHOP_SLUG, placed.splice(0))
})

test.describe("live tracking journey @mobile-only", () => {
  test("titles at every step, the status change announced on the order page and on /track, the tracker wraps at 360px / 130%", async ({
    page,
    request,
  }) => {
    test.setTimeout(240_000)
    skipWithoutVendorPassword()

    // ── Basket and checkout: each step has its own title (F4). ──
    await page.goto(`/shop/${SHOP_SLUG}`)
    const add = page.locator('button:has-text("Add")')
    await expect(add.first()).toBeVisible({ timeout: 15_000 })
    await add.first().click()

    await page.goto(`/shop/${SHOP_SLUG}/cart`)
    await expect(page.getByRole("heading", { name: "Your basket" })).toBeVisible()
    await expect(page).toHaveTitle(`Basket · ${SHOP_NAME} — J'Toye`)
    // INSTRUMENT: the title assertion can fail — a wrong expected title is refused.
    await expect(expect(page).toHaveTitle(`Basket · Not ${SHOP_NAME} — J'Toye`, { timeout: 1_000 })).rejects.toThrow()

    await page.goto(`/shop/${SHOP_SLUG}/checkout`)
    await expect(page.getByRole("heading", { level: 1, name: "Checkout" })).toBeVisible()
    await expect(page).toHaveTitle(`Checkout · ${SHOP_NAME} — J'Toye`)

    // ── Place a real cash COLLECTION order. ──
    const contact = uniqueContact("a11y-tracking")
    await page.getByRole("button", { name: /collection/i }).click()
    await page.fill("input#name", "Tracking Audit")
    await page.fill("input#email", contact.email)
    await page.fill("input#phone", contact.phone)
    const ack = page.getByRole("checkbox", { name: /I have read the allergen information for this order\./i })
    await ack.click()
    await expect(ack).toBeChecked()
    await page.locator('button[type="submit"]:has-text("Place order")').click()
    const orderNumber = await awaitCashConfirmation(page)
    placed.push(orderNumber)

    // The C3 first view keeps its own title variant (37-37); a reload is the plain order title.
    await expect(page).toHaveTitle(`Order ${orderNumber} confirmed · ${SHOP_NAME} — J'Toye`)
    await page.reload()
    await expect(page.getByRole("heading", { level: 1, name: `Order ${orderNumber}` })).toBeVisible()
    await expect(page).toHaveTitle(`Order ${orderNumber} · ${SHOP_NAME} — J'Toye`)

    // ── Order page: exactly one polite status region; the change is announced (F1). ──
    await expect(page.locator('[role="status"][aria-live="polite"]')).toHaveCount(1)
    await expect(page.locator('[aria-current="step"]')).toHaveText(/Received/)
    await expect(page.getByRole("button", { name: "Copy order number" })).toBeVisible()
    await installAnnouncementRecorder(page)
    await vendorTransition(request, orderNumber, "confirm")
    await awaitAnnouncement(page, "Order update: Confirmed. Shop accepted your order.")
    await expect(page.locator('[aria-current="step"]')).toHaveText(/Confirmed/)
    // An unchanged poll is not re-announced: one more poll cycle, still exactly one message.
    await page.waitForTimeout(16_000)
    expect(
      (await recordedAnnouncements(page)).filter((r) => r.startsWith("Order update:")),
      "an unchanged poll must not announce again",
    ).toHaveLength(1)

    // ── F6 on the order page's tracker. ──
    await largeTextNarrow(page)
    expect(await overflowOffenders(page, 'ol[aria-label="Order progress"]', "[data-tracker-step] p")).toEqual([])
    // INSTRUMENT: force one label to clip, and the probe must see it.
    await page.addStyleTag({
      content: '[data-tracker-step="CONFIRMED"] p:first-child { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; width: 2ch; }',
    })
    expect(await overflowOffenders(page, 'ol[aria-label="Order progress"]', "[data-tracker-step] p")).not.toEqual([])

    // ── /track: the same contract (F1), reached the way a customer reaches it. ──
    await page.setViewportSize({ width: 390, height: 844 })
    await page.getByRole("link", { name: "Track this order" }).click()
    await expect(page).toHaveURL(/\/track\?order=/)
    await expect(page.getByText(orderNumber)).toBeVisible({ timeout: 15_000 })
    await expect(page).toHaveTitle("Track your order — J'Toye")
    await expect(page.locator('[role="status"][aria-live="polite"]')).toHaveCount(1)
    await expect(page.locator('[aria-current="step"]')).toHaveText(/Confirmed/)
    await installAnnouncementRecorder(page)
    await vendorTransition(request, orderNumber, "start-preparation")
    await awaitAnnouncement(page, "Order update: Preparing. Being prepared now.")

    // ── F6 on /track's horizontal tracker. ──
    await largeTextNarrow(page)
    expect(await overflowOffenders(page, 'ol[aria-label="Order progress"]', "[data-tracker-step] p")).toEqual([])

    // ── INSTRUMENT on the real page: with the region removed, a real transition records nothing
    //    and the announcement assertion fails. ──
    expect(await removeLiveRegions(page), "there was a live region to remove").toBeGreaterThan(0)
    await installAnnouncementRecorder(page)
    await vendorTransition(request, orderNumber, "mark-ready")
    await expect(page.locator('[aria-current="step"]')).toHaveText(/Ready/, { timeout: ANNOUNCE_TIMEOUT })
    const afterRemoval = await recordedAnnouncements(page)
    expect(afterRemoval).toEqual([])
    expect(() => expectAnnounced(afterRemoval, "Order update: Ready.")).toThrow()
  })
})

// ─── The instruments, against fixtures, on every run ──────────────────────────────────────────

const FIXTURE = `<!doctype html><html lang="en"><head><title>Basket · Fixture Kitchen — J'Toye</title></head>
<body><main>
  <div id="region" role="status" aria-live="polite"></div>
  <ol aria-label="Order progress" style="display:flex;gap:4px;max-width:300px;list-style:none;padding:0">
    <li data-tracker-step="PENDING" style="flex:1;min-width:0"><p>Received</p></li>
    <li data-tracker-step="CONFIRMED" style="flex:1;min-width:0"><p>Confirmed</p></li>
  </ol>
</main></body></html>`

test.describe("instruments can fail @stack-free", () => {
  test("the recorder records a region's change, and records NOTHING once the region is removed", async ({ page }) => {
    await page.setContent(FIXTURE)
    await installAnnouncementRecorder(page)
    await page.evaluate(() => {
      document.getElementById("region")!.textContent = "Order update: Confirmed. Shop accepted your order."
    })
    // Positive control: the recorder sees a real write.
    await expect.poll(() => recordedAnnouncements(page)).toEqual(["Order update: Confirmed. Shop accepted your order."])
    expectAnnounced(await recordedAnnouncements(page), "Order update: Confirmed.")

    // The broken page: the region is gone, the same kind of update happens, nothing is announced.
    await installAnnouncementRecorder(page)
    expect(await removeLiveRegions(page)).toBe(1)
    await page.evaluate(() => {
      const p = document.createElement("p")
      p.textContent = "Order update: Preparing. Being prepared now."
      document.querySelector("main")!.appendChild(p)
    })
    const records = await recordedAnnouncements(page)
    expect(records).toEqual([])
    expect(() => expectAnnounced(records, "Order update: Preparing.")).toThrow()
  })

  test("the recorder does not count an unchanged rewrite as a new announcement", async ({ page }) => {
    await page.setContent(FIXTURE)
    await page.evaluate(() => {
      document.getElementById("region")!.textContent = "Order update: Confirmed. Shop accepted your order."
    })
    await installAnnouncementRecorder(page)
    await page.evaluate(() => {
      const r = document.getElementById("region")!
      r.textContent = "Order update: Confirmed. Shop accepted your order."
    })
    expect(await recordedAnnouncements(page)).toEqual([])
  })

  test("a wrong expected title fails", async ({ page }) => {
    await page.setContent(FIXTURE)
    await expect(page).toHaveTitle("Basket · Fixture Kitchen — J'Toye")
    await expect(expect(page).toHaveTitle("Checkout · Fixture Kitchen — J'Toye", { timeout: 1_000 })).rejects.toThrow()
  })

  test("the overflow probe passes wrapping labels and flags a clipped one at 360px / 130%", async ({ page }) => {
    await page.setContent(FIXTURE)
    await largeTextNarrow(page)
    expect(await overflowOffenders(page, 'ol[aria-label="Order progress"]', "[data-tracker-step] p")).toEqual([])
    await page.addStyleTag({
      content: '[data-tracker-step="CONFIRMED"] p { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; width: 2ch; }',
    })
    const offenders = await overflowOffenders(page, 'ol[aria-label="Order progress"]', "[data-tracker-step] p")
    expect(offenders.join(" | ")).toMatch(/"Confirmed" (ellipsis|scrollWidth)/)
  })
})
