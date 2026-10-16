/**
 * 37-55: the live instruments for the four UAT gaps 37-54 fixed (G-37-12..G-37-15), in
 * Chrome against the delivered compose runtime.
 *
 * WHICH TEST PROVES WHICH GAP
 *   1. G-37-12 (D-08, UAT test 12): a freshly invited STAFF user opening /dashboard/webhooks
 *      gets the API's typed 403 and the page says so ("Group admin access required"), with no
 *      "No webhook endpoints yet" and no "Add endpoint". The Group admin still sees Add endpoint.
 *   2. G-37-13 and G-37-15 (UAT tests 13 and 15): every visible text element and all five
 *      selects of the "Invite someone" and "Grant access" cards measure at least 4.5:1 in the
 *      dark theme AND in the light one, from COMPUTED colour against the first opaque
 *      ancestor background; and the Grant-access person picker shows no "null", "undefined"
 *      or "()". The dark-theme residue outside the two cards is measured and attached as the
 *      annotation "dark-residue" for the owner's decision; it is not asserted.
 *   3. G-37-14 (D-07, UAT test 14): at 390, 768, 1024, 1280 and 1440 px no token of a fixture
 *      Pending-invitations row is split across lines, both actions of every row sit inside the
 *      card and its scroll region, and the scroll region does not scroll sideways.
 *
 * INSTRUMENT ARMS. Each test carries one or more arms that run on EVERY execution and must
 * fail as designed; an arm that passes means the assertion beside it cannot fail:
 *   - test 1: the refusal assertion, aimed at the Group admin's page, must throw;
 *   - test 2: the contrast checker on #staff-role forced to near-white on white must name it,
 *     and the picker checker on an injected "Arm Member (null)" option must name it;
 *   - test 3: the layout checker at 768 with the old four-column table layout forced back by an
 *     injected style must report failures.
 *
 * COVERAGE BOUNDARY. This needs a live stack: a Keycloak login, Mailhog, core-java, and a
 * frontend image rebuilt from the tree under test. Dashboard routes resolve the NextAuth session
 * server-side (app/dashboard/layout.tsx redirects without one), so no stack-free lane can reach
 * these pages (e2e/layout-width-contract.spec.ts records the same boundary). It runs at the
 * 37-55 gate and in the nightly full-stack lane, never per PR. The per-PR guards are 37-54's
 * jest tests: the token-contrast resolver, the webhooks refusal branch, the picker label and
 * the container-query declarations. What no instrument here measures: the native option list
 * a select opens (drawn by the browser), and opacity (an ancestor's `opacity` is not folded
 * into the ratio; none of the measured controls uses one).
 *
 * WHAT A RUN CREATES on the shared dev stack: one STAFF account in the vendor realm and one
 * accepted invitation (test 1), and one fixture invitation (test 3) that the test cancels and
 * its `finally` cancels again if it is still open. The invitee's password lives only in the
 * helper's memory (e2e/helpers/staff-invitee.ts).
 *
 * @desktop-only: one run creates a Keycloak user and spends public-endpoint rate limit; the
 * tests pin their own viewports with page.setViewportSize, so 390 px is covered here too.
 *
 * Run: set -a; . ./.env; set +a; cd frontend && npx playwright test e2e/staff-webhooks-uat.spec.ts --project=desktop
 */
import { test, expect, type Page, type TestInfo } from "@playwright/test"
import { inviteAndJoinAsStaff, toasts, vendorLogin } from "./helpers/staff-invitee"

const REFUSAL_HEADING = "Group admin access required"
const WIDTHS = [390, 768, 1024, 1280, 1440] as const
const PENDING_TABLE_LABEL = "Pending invitations table"
const MIN_RATIO = 4.5

// ---------------------------------------------------------------------------------------------
// G-37-12

/** The refusal, as a Staff user must see it. Shared with its instrument arm. */
async function expectRefusal(page: Page, timeout: number) {
  await expect(page.getByRole("heading", { level: 1, name: "Webhooks" })).toBeVisible({ timeout })
  await expect(page.getByRole("heading", { name: REFUSAL_HEADING })).toBeVisible({ timeout })
  await expect(page.getByText("No webhook endpoints yet")).toHaveCount(0, { timeout })
  await expect(page.getByRole("button", { name: /add endpoint/i })).toHaveCount(0, { timeout })
}

// ---------------------------------------------------------------------------------------------
// G-37-13: computed contrast, measured in the page

type ContrastFailure = { el: string; text: string; color: string; background: string; ratio: number }
type ContrastResult = {
  failures: ContrastFailure[]
  selects: number
  texts: number
  /** Present when a card could not be found: the measurement is VOID, never clean. */
  missing: string[]
}

/**
 * WCAG 2 contrast of every visible element that owns a non-blank text node, plus every select,
 * against the first ancestor-or-self whose background-color is fully opaque. Colours are read
 * back through a 1x1 canvas, so whatever syntax the browser serialises (rgb, color(srgb), oklch)
 * reaches the formula as sRGB bytes; a colour that cannot be resolved is a failure, not a skip.
 *
 * scope "cards": the Invite someone card and the Grant access card (the Card holding #staff-user).
 * scope "outside": every element in <main> that is in neither card (the residue measurement).
 * scope "refusal": the Webhooks page's G-37-12 refusal card (the Card holding its heading).
 */
async function measureContrast(page: Page, scope: "cards" | "outside" | "refusal"): Promise<ContrastResult> {
  return page.evaluate(
    ({ scope, minRatio, refusalHeading }) => {
      const canvas = document.createElement("canvas")
      canvas.width = 1
      canvas.height = 1
      const ctx = canvas.getContext("2d", { willReadFrequently: true })
      const rgba = (css: string): [number, number, number, number] | null => {
        if (!ctx) return null
        ctx.clearRect(0, 0, 1, 1)
        ctx.fillStyle = "rgba(0, 0, 0, 0)"
        ctx.fillStyle = css
        ctx.fillRect(0, 0, 1, 1)
        const d = ctx.getImageData(0, 0, 1, 1).data
        // A transparent colour reads back as 0,0,0,0 whatever it was; that is still its alpha.
        return [d[0], d[1], d[2], d[3] / 255]
      }
      const channel = (v: number) => {
        const s = v / 255
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
      }
      const luminance = (c: [number, number, number]) =>
        0.2126 * channel(c[0]) + 0.7152 * channel(c[1]) + 0.0722 * channel(c[2])

      const opaqueBackground = (el: Element): { css: string; rgb: [number, number, number] } | null => {
        for (let n: Element | null = el; n; n = n.parentElement) {
          const css = getComputedStyle(n).backgroundColor
          const c = rgba(css)
          if (c && c[3] === 1) return { css, rgb: [c[0], c[1], c[2]] }
        }
        return null
      }

      const describe = (el: Element) => {
        const id = el.id ? `#${el.id}` : ""
        const role = el.getAttribute("role") ? `[role=${el.getAttribute("role")}]` : ""
        return `${el.tagName.toLowerCase()}${id}${role}`
      }

      const inviteCard = document.getElementById("invite-email")?.closest('[role="region"]') ?? null
      const grantCard = document.getElementById("staff-user")?.closest(".bg-card") ?? null
      const missing: string[] = []
      if (scope !== "refusal") {
        if (!inviteCard) missing.push("Invite someone card")
        if (!grantCard) missing.push("Grant access card")
      }

      let candidates: Element[] = []
      if (scope === "refusal") {
        const heading = Array.from(document.querySelectorAll("main h2")).find(
          (h) => (h.textContent || "").trim() === refusalHeading
        )
        const card = heading?.closest(".bg-card") ?? null
        if (!card) missing.push("refusal card")
        else candidates.push(card, ...Array.from(card.querySelectorAll("*")))
      } else if (scope === "cards") {
        for (const root of [inviteCard, grantCard]) {
          if (root) candidates.push(root, ...Array.from(root.querySelectorAll("*")))
        }
      } else {
        const main = document.querySelector("main")
        if (!main) missing.push("main")
        else {
          candidates = Array.from(main.querySelectorAll("*")).filter(
            (el) => !(inviteCard && inviteCard.contains(el)) && !(grantCard && grantCard.contains(el))
          )
        }
      }

      const failures: {
        el: string
        text: string
        color: string
        background: string
        ratio: number
      }[] = []
      let selects = 0
      let texts = 0
      for (const el of candidates) {
        const isSelect = el.tagName === "SELECT"
        // Options belong to their select: the select is measured, the closed list is browser-drawn.
        if (!isSelect && el.closest("select")) continue
        const ownText = Array.from(el.childNodes)
          .filter((n) => n.nodeType === Node.TEXT_NODE)
          .map((n) => n.textContent || "")
          .join(" ")
          .replace(/\s+/g, " ")
          .trim()
        if (!isSelect && !ownText) continue
        const rect = el.getBoundingClientRect()
        if (rect.width === 0 || rect.height === 0) continue
        const style = getComputedStyle(el)
        if (style.visibility !== "visible" || style.display === "none") continue
        if (
          typeof (el as HTMLElement).checkVisibility === "function" &&
          !(el as HTMLElement).checkVisibility({ visibilityProperty: true })
        )
          continue
        // sr-only: absolutely positioned and clipped to nothing.
        const clipped =
          style.position === "absolute" &&
          (style.clip === "rect(0px, 0px, 0px, 0px)" || (rect.width <= 1 && rect.height <= 1))
        if (clipped) continue

        const text = isSelect
          ? ((el as HTMLSelectElement).selectedOptions[0]?.textContent || "").trim()
          : ownText
        if (isSelect) selects += 1
        else texts += 1

        const fg = rgba(style.color)
        const bg = opaqueBackground(el)
        if (!fg || !bg) {
          failures.push({
            el: describe(el),
            text: text.slice(0, 80),
            color: style.color,
            background: bg ? bg.css : "no opaque ancestor background",
            ratio: 0,
          })
          continue
        }
        const a = fg[3]
        const seen: [number, number, number] = [
          a * fg[0] + (1 - a) * bg.rgb[0],
          a * fg[1] + (1 - a) * bg.rgb[1],
          a * fg[2] + (1 - a) * bg.rgb[2],
        ]
        const l1 = luminance(seen)
        const l2 = luminance(bg.rgb)
        const ratio = (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05)
        if (ratio < minRatio) {
          failures.push({
            el: describe(el),
            text: text.slice(0, 80),
            color: style.color,
            background: bg.css,
            ratio: Math.round(ratio * 100) / 100,
          })
        }
      }
      return { failures, selects, texts, missing }
    },
    { scope, minRatio: MIN_RATIO, refusalHeading: REFUSAL_HEADING }
  )
}

// ---------------------------------------------------------------------------------------------
// G-37-15

type PickerResult = { options: string[]; failures: string[] }

/** The Grant-access picker's option texts, and those that leak a missing value. */
async function pickerFailures(page: Page): Promise<PickerResult> {
  return page.evaluate(() => {
    const select = document.getElementById("staff-user") as HTMLSelectElement | null
    const options = select ? Array.from(select.options).map((o) => (o.textContent || "").trim()) : []
    const failures = options.filter((t) => /\bnull\b|\bundefined\b|\(\s*\)/i.test(t))
    return { options, failures }
  })
}

// ---------------------------------------------------------------------------------------------
// G-37-14: geometry of the Pending invitations card, measured in the page

type LayoutResult = {
  failures: string[]
  /** The fixture row's computed display ("flex" stacked, "table-row" table). */
  display: string
  /** The CardContent (the size container) content-box width, px. */
  cardContentWidth: number
}

async function layoutFailures(page: Page, address: string): Promise<LayoutResult> {
  return page.evaluate(
    ({ address, tableLabel }) => {
      const failures: string[] = []
      const scrollRegion = document.querySelector(`[aria-label="${tableLabel}"]`) as HTMLElement | null
      const card = scrollRegion?.closest('[role="region"]:not([aria-label])') as HTMLElement | null
      if (!scrollRegion || !card) {
        return { failures: ["VOID: the Pending invitations card or its scroll region is missing"], display: "", cardContentWidth: 0 }
      }
      const container = scrollRegion.parentElement as HTMLElement
      const cs = getComputedStyle(container)
      const cardContentWidth =
        container.clientWidth - parseFloat(cs.paddingLeft || "0") - parseFloat(cs.paddingRight || "0")

      const rows = Array.from(scrollRegion.querySelectorAll("tr[data-status]")) as HTMLElement[]
      const fixture = rows.find((r) => (r.textContent || "").includes(address)) ?? null
      if (!fixture) failures.push(`VOID: no Pending row for ${address}`)

      // 1. No token of the fixture row is split across lines.
      if (fixture) {
        const walker = document.createTreeWalker(fixture, NodeFilter.SHOW_TEXT)
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
          const value = node.textContent || ""
          const re = /\S+/g
          for (let m = re.exec(value); m; m = re.exec(value)) {
            const range = document.createRange()
            range.setStart(node, m.index)
            range.setEnd(node, m.index + m[0].length)
            const tops = Array.from(range.getClientRects())
              .filter((r) => r.width > 0 && r.height > 0)
              .map((r) => Math.round(r.top))
              .sort((x, y) => x - y)
            // Lines are >= 16px apart; a 1px rounding wobble on one line is not a second line.
            let lines = tops.length > 0 ? 1 : 0
            for (let i = 1; i < tops.length; i++) if (tops[i] - tops[i - 1] > 2) lines += 1
            if (lines > 1) failures.push(`token "${m[0]}" spans ${lines} lines`)
          }
        }
      }

      // 2. Both actions of every row sit inside the scroll region and the card.
      const inside = (inner: DOMRect, outer: DOMRect) =>
        inner.left >= outer.left - 0.5 &&
        inner.right <= outer.right + 0.5 &&
        inner.top >= outer.top - 0.5 &&
        inner.bottom <= outer.bottom + 0.5
      const regionRect = scrollRegion.getBoundingClientRect()
      const cardRect = card.getBoundingClientRect()
      for (const row of rows) {
        const buttons = Array.from(row.querySelectorAll("button"))
        if (buttons.length !== 2) failures.push(`row ${row.dataset.status}: ${buttons.length} buttons, expected 2`)
        for (const b of buttons) {
          const r = b.getBoundingClientRect()
          const name = b.getAttribute("aria-label") || b.textContent || "button"
          if (!inside(r, regionRect)) failures.push(`"${name}" leaves the scroll region`)
          if (!inside(r, cardRect)) failures.push(`"${name}" leaves the card`)
        }
      }

      // 3. The scroll region does not scroll sideways.
      if (scrollRegion.scrollWidth > scrollRegion.clientWidth + 1) {
        failures.push(`scroll region scrolls sideways: scrollWidth ${scrollRegion.scrollWidth} > clientWidth ${scrollRegion.clientWidth}`)
      }

      return {
        failures,
        display: fixture ? getComputedStyle(fixture).display : "",
        cardContentWidth: Math.round(cardContentWidth),
      }
    },
    { address, tableLabel: PENDING_TABLE_LABEL }
  )
}

/** Two animation frames: the layout the viewport change caused has been computed and painted. */
async function settle(page: Page) {
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  )
}

function annotate(testInfo: TestInfo, type: string, value: unknown) {
  testInfo.annotations.push({ type, description: typeof value === "string" ? value : JSON.stringify(value) })
}

/** Open /dashboard/staff in `theme` (set before the page's own scripts run) and wait for both cards. */
async function openStaffIn(page: Page, theme: "dark" | "light") {
  await page.addInitScript((t) => {
    try {
      localStorage.setItem("theme", t)
    } catch {
      // storage blocked: the html.dark control below reports it
    }
  }, theme)
  await page.goto("/dashboard/staff", { waitUntil: "domcontentloaded" })
  await expect(page.getByRole("region", { name: "Invite someone" })).toBeVisible({ timeout: 20_000 })
  await expect(page.locator("#staff-role")).toBeVisible({ timeout: 20_000 })
}

test.describe("UAT gaps G-37-12..G-37-15, live (37-55) @desktop-only", () => {
  test("G-37-12: a Staff user is refused on Webhooks; the Group admin still manages endpoints", async ({
    browser,
    page,
    request,
    baseURL,
  }, testInfo) => {
    test.setTimeout(180_000)
    await vendorLogin(page)
    const invitee = await inviteAndJoinAsStaff({ browser, adminPage: page, request, baseURL })
    try {
      // Registered BEFORE navigating, so the list read cannot be missed.
      const listRead = invitee.page.waitForResponse(
        (r) => new URL(r.url()).pathname === "/api/v1/webhooks" && r.request().method() === "GET",
        { timeout: 30_000 }
      )
      await invitee.page.goto("/dashboard/webhooks", { waitUntil: "domcontentloaded" })
      const response = await listRead

      // Non-vacuity control: the refusal is real and came from the API.
      expect(response.status(), "the Staff user's list read must be refused").toBe(403)
      const problem = (await response.json().catch(() => ({}))) as { type?: unknown }
      expect(String(problem.type ?? ""), "the refusal is the typed shop-access-denied problem").toMatch(
        /\/shop-access-denied$/
      )
      annotate(testInfo, "g-37-12-refusal", { status: response.status(), type: problem.type })

      await expectRefusal(invitee.page, 20_000)

      // Round-1 review of #925: the refusal card is new content, so it reads at 4.5:1 in dark
      // too (the G-37-13 bar). The page heading above it is the dashboard-wide residue (evidence
      // 37-55 section 7) and is deliberately outside this scope.
      await invitee.page.evaluate(() => localStorage.setItem("theme", "dark"))
      await invitee.page.reload({ waitUntil: "domcontentloaded" })
      await expectRefusal(invitee.page, 20_000)
      expect(
        await invitee.page.evaluate(() => document.documentElement.classList.contains("dark")),
        "html.dark must be set, or the dark measurement is vacuous"
      ).toBe(true)
      await settle(invitee.page)
      const refusalDark = await measureContrast(invitee.page, "refusal")
      annotate(testInfo, "g-37-12-refusal-dark", refusalDark)
      expect(refusalDark.missing, "the refusal card must be found").toEqual([])
      expect(refusalDark.texts, "the refusal card's title and body are measured").toBeGreaterThanOrEqual(2)
      expect(refusalDark.failures, "G-37-12: the refusal card at 4.5:1 (dark)").toEqual([])

      // Control: the Group admin still sees the endpoint management.
      await page.goto("/dashboard/webhooks", { waitUntil: "domcontentloaded" })
      await expect(page.getByRole("button", { name: "Add endpoint" }).first()).toBeVisible({ timeout: 20_000 })

      // INSTRUMENT ARM: the refusal assertion against the Group admin's page must fail.
      let armFailed = false
      try {
        await expectRefusal(page, 2_000)
      } catch {
        armFailed = true
      }
      expect(armFailed, "the refusal assertion must be able to fail").toBe(true)
      annotate(testInfo, "arm-refusal", "expectRefusal(adminPage) threw, as designed")
    } finally {
      await invitee.context.close()
    }
  })

  test("G-37-13 and G-37-15: the Staff form cards read at 4.5:1 in both themes; the picker shows no null", async ({
    page,
  }, testInfo) => {
    test.setTimeout(180_000)
    await vendorLogin(page)

    // ---- Dark --------------------------------------------------------------------------------
    await openStaffIn(page, "dark")
    await expect(page.locator("html"), "control: the dark theme is applied").toHaveClass(/(^|\s)dark(\s|$)/)

    const inviteCard = page.getByRole("region", { name: "Invite someone" })
    await inviteCard.getByLabel("Role").selectOption("GROUP_ADMIN")
    await expect(inviteCard.getByLabel("Shop"), "Group admin locks the shop").toBeDisabled()
    await expect(inviteCard.getByText("Group admin always covers every shop.")).toBeVisible()

    const invitePosts: string[] = []
    page.on("request", (r) => {
      if (r.method() === "POST" && new URL(r.url()).pathname === "/api/v1/staff/invites") invitePosts.push(r.url())
    })
    await inviteCard.getByLabel("Email").fill("")
    await inviteCard.getByRole("button", { name: "Send invitation" }).click()
    await expect(inviteCard.getByRole("alert").filter({ hasText: "Enter an email address." })).toBeVisible()
    await page.waitForTimeout(500)
    expect(invitePosts, "an empty email must not reach the API").toEqual([])

    const dark = await measureContrast(page, "cards")
    annotate(testInfo, "contrast-dark", dark)
    expect(dark.missing, "VOID if a card is missing").toEqual([])
    expect(dark.selects, "non-vacuity: all five selects measured").toBe(5)
    expect(dark.texts, "non-vacuity: at least 10 text elements measured").toBeGreaterThanOrEqual(10)
    expect.soft(dark.failures, "G-37-13: every control and text in the two cards at 4.5:1 (dark)").toEqual([])

    const picker = await pickerFailures(page)
    annotate(testInfo, "picker", picker)
    expect(picker.options.length, "non-vacuity: the picker has a placeholder and at least one person").toBeGreaterThanOrEqual(2)
    expect.soft(picker.failures, "G-37-15: no picker option leaks null/undefined/()").toEqual([])

    // Residue, NOT asserted: the dark theme outside the two cards, for the owner's decision.
    const residue = await measureContrast(page, "outside")
    annotate(testInfo, "dark-residue", residue)

    // INSTRUMENT ARM 1: the contrast checker sees near-white on white on #staff-role.
    const forced = await page.addStyleTag({
      content: "#staff-role { background-color: #ffffff !important; color: rgb(248, 250, 252) !important; }",
    })
    const armedContrast = await measureContrast(page, "cards")
    await forced.evaluate((n) => (n as Element).remove())
    expect(
      armedContrast.failures.some((f) => f.el.startsWith("select#staff-role")),
      "the contrast checker must name #staff-role when it is forced to near-white on white"
    ).toBe(true)
    annotate(testInfo, "arm-contrast", armedContrast.failures.filter((f) => f.el.startsWith("select#staff-role")))

    // INSTRUMENT ARM 2: the picker checker sees an injected "(null)" option.
    await page.evaluate(() => {
      const option = document.createElement("option")
      option.value = "e2e-arm"
      option.textContent = "Arm Member (null)"
      option.setAttribute("data-e2e-arm", "")
      document.getElementById("staff-user")?.appendChild(option)
    })
    const armedPicker = await pickerFailures(page)
    await page.evaluate(() => document.querySelector("[data-e2e-arm]")?.remove())
    expect(armedPicker.failures, "the picker checker must name the injected option").toContain("Arm Member (null)")
    annotate(testInfo, "arm-picker", armedPicker.failures)

    // Closing clean run: both arms are gone and the measurement is what it was before them.
    const closing = await measureContrast(page, "cards")
    expect(closing.failures, "the arms were removed (closing measurement equals the first)").toEqual(dark.failures)
    expect((await pickerFailures(page)).options, "the injected option was removed").toEqual(picker.options)

    // ---- Light (a fresh page in the same signed-in context) -----------------------------------
    const lightPage = await page.context().newPage()
    try {
      await openStaffIn(lightPage, "light")
      await expect(lightPage.locator("html"), "control: the dark theme is absent").not.toHaveClass(/(^|\s)dark(\s|$)/)
      const lightInvite = lightPage.getByRole("region", { name: "Invite someone" })
      await lightInvite.getByLabel("Role").selectOption("GROUP_ADMIN")
      await expect(lightInvite.getByLabel("Shop")).toBeDisabled()
      await lightInvite.getByRole("button", { name: "Send invitation" }).click()
      await expect(lightInvite.getByRole("alert").filter({ hasText: "Enter an email address." })).toBeVisible()
      const light = await measureContrast(lightPage, "cards")
      annotate(testInfo, "contrast-light", light)
      expect(light.missing).toEqual([])
      expect(light.selects, "non-vacuity: all five selects measured (light)").toBe(5)
      expect(light.texts).toBeGreaterThanOrEqual(10)
      expect.soft(light.failures, "every control and text in the two cards at 4.5:1 (light)").toEqual([])
    } finally {
      await lightPage.close()
    }
  })

  test("G-37-14: pending invitations keep every token on one line and both actions in reach, 390 to 1440", async ({
    page,
  }, testInfo) => {
    test.setTimeout(180_000)
    await page.setViewportSize({ width: 1440, height: 900 })
    await vendorLogin(page)
    await page.goto("/dashboard/staff", { waitUntil: "domcontentloaded" })
    const inviteCard = page.getByRole("region", { name: "Invite someone" })
    await expect(inviteCard).toBeVisible({ timeout: 20_000 })

    // The fixture: a Shop manager invitation for the shop with the longest name (worst realistic case).
    const address = `p${Date.now()}@example.test`
    await inviteCard.getByLabel("Email").fill(address)
    await inviteCard.getByLabel("Role").selectOption("SHOP_MANAGER")
    const shops = await inviteCard
      .getByLabel("Shop")
      .locator("option")
      .evaluateAll((opts) =>
        (opts as HTMLOptionElement[]).filter((o) => o.value).map((o) => ({ value: o.value, name: (o.textContent || "").trim() }))
      )
    expect(shops.length, "the admin's business must have at least one shop").toBeGreaterThan(0)
    const longest = shops.reduce((a, b) => (b.name.length > a.name.length ? b : a))
    annotate(testInfo, "fixture-shop", longest.name)
    await inviteCard.getByLabel("Shop").selectOption(longest.value)
    await inviteCard.getByRole("button", { name: "Send invitation" }).click()
    await expect(toasts(page).getByText(`Invitation sent to ${address}.`, { exact: false })).toBeVisible({
      timeout: 15_000,
    })
    const pending = page.getByRole("region", { name: "Pending invitations" })
    const row = pending.locator("tr[data-status]").filter({ hasText: address })
    await expect(row, `one Pending row for ${address}`).toHaveCount(1, { timeout: 15_000 })

    try {
      for (const width of WIDTHS) {
        await page.setViewportSize({ width, height: 900 })
        await settle(page)
        const result = await layoutFailures(page, address)
        annotate(testInfo, `layout-${width}`, {
          display: result.display,
          cardContentWidth: result.cardContentWidth,
          failures: result.failures,
        })
        expect.soft(result.failures, `G-37-14 at ${width}px`).toEqual([])
        if (width === 768) {
          await expect.soft(row, "a stacked row names its expiry at 768").toContainText("Expires", { useInnerText: true })
        }
      }

      // The table's own max-content width beside the container threshold in the stylesheet.
      await page.setViewportSize({ width: 1440, height: 900 })
      await settle(page)
      const measured = await page.evaluate((tableLabel) => {
        const region = document.querySelector(`[aria-label="${tableLabel}"]`)
        const table = region?.querySelector("table") as HTMLTableElement | null
        if (!table) return null
        const before = table.style.width
        table.style.width = "max-content"
        const maxContent = table.offsetWidth
        table.style.width = before
        const conditions = new Set<string>()
        const walk = (rules: CSSRuleList) => {
          for (const rule of Array.from(rules)) {
            if (typeof CSSContainerRule !== "undefined" && rule instanceof CSSContainerRule) {
              const inner = Array.from(rule.cssRules) as CSSStyleRule[]
              if (inner.some((r) => (r.selectorText || "").includes("table-row"))) conditions.add(rule.conditionText)
            } else if ("cssRules" in rule) {
              walk((rule as CSSGroupingRule).cssRules)
            }
          }
        }
        for (const sheet of Array.from(document.styleSheets)) {
          try {
            walk(sheet.cssRules)
          } catch {
            // a cross-origin sheet: not ours
          }
        }
        const rootPx = parseFloat(getComputedStyle(document.documentElement).fontSize)
        const thresholds = Array.from(conditions).map((c) => {
          const m = /min-width:\s*([\d.]+)rem/.exec(c)
          return { condition: c, px: m ? parseFloat(m[1]) * rootPx : null }
        })
        return { maxContent, rowDisplay: getComputedStyle(table.querySelector("tr[data-status]") as Element).display, thresholds }
      }, PENDING_TABLE_LABEL)
      annotate(testInfo, "pending-max-content", measured)
      expect(measured, "the pending table exists at 1440").not.toBeNull()

      // INSTRUMENT ARM at 768: the old four-column layout, forced back, must be caught.
      await page.setViewportSize({ width: 768, height: 900 })
      await settle(page)
      const scope = `[aria-label="${PENDING_TABLE_LABEL}"]`
      const oldLayout = await page.addStyleTag({
        content: [
          `${scope} thead { display: table-header-group !important; }`,
          `${scope} tr[data-status] { display: table-row !important; padding: 0 !important; }`,
          `${scope} tr[data-status] > td { display: table-cell !important; padding: 16px !important; }`,
          `${scope} tr[data-status] > td:last-child > div { flex-direction: row !important; }`,
        ].join("\n"),
      })
      await settle(page)
      const armed = await layoutFailures(page, address)
      await oldLayout.evaluate((n) => (n as Element).remove())
      await settle(page)
      expect(armed.failures.length, "the layout checker must catch the old table layout at 768").toBeGreaterThan(0)
      annotate(testInfo, "arm-layout-768", armed)

      // The action works where it used to be clipped: cancel the fixture at 768.
      await page.getByRole("button", { name: `Cancel invitation for ${address}` }).click()
      await page.getByRole("dialog").getByRole("button", { name: "Cancel invitation", exact: true }).click()
      await expect(row, "the cancelled invitation leaves the list").toHaveCount(0, { timeout: 15_000 })
    } finally {
      // Best effort: never leave the fixture open on the shared stack. A failure is recorded.
      try {
        if ((await row.count()) > 0) {
          await page.setViewportSize({ width: 1440, height: 900 })
          await page.getByRole("button", { name: `Cancel invitation for ${address}` }).click({ timeout: 10_000 })
          await page
            .getByRole("dialog")
            .getByRole("button", { name: "Cancel invitation", exact: true })
            .click({ timeout: 10_000 })
          await expect(row).toHaveCount(0, { timeout: 15_000 })
          annotate(testInfo, "fixture-cleanup", `cancelled ${address} in finally`)
        }
      } catch (error) {
        annotate(testInfo, "fixture-cleanup", `FAILED to cancel ${address}: ${String(error)}`)
      }
    }
  })
})
