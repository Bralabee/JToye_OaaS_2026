/**
 * 37-59: the live instruments for UAT gap G-37-6 (UAT test 6), in Chrome against the delivered
 * compose runtime. 37-56 (the TypeScript sentence-end rule), 37-57 (the Java one) and 37-58 (the
 * finance container grids and the tiered Pagination) fixed it; this spec proves it where vendors
 * and customers would see it.
 *
 * WHICH TEST PROVES WHICH PART OF G-37-6
 *   1. Defect 1 (finance): at 390, 768, 1024 and 1280 px, with the live summary AND with a summary
 *      at the declared bound ("£999,999.99", "−£999,999.99", four VAT rates) injected by route,
 *      every finance figure's text sits on one line inside its own card, equals the formatted
 *      value, no two stat cards overlap, and the scope banner does not scroll sideways.
 *   2. Defect 3 (orders pagination): at 375, 390, 768, 1024 and 1280 px the Pagination nav and
 *      every visible control sit inside the card and the viewport, each control is at least
 *      44 x 44 px, the nav does not scroll sideways and its root is at least 200 px wide; first,
 *      previous, next and last show at 390; the five-page window with one aria-current at 1280;
 *      "Go to next page" at 390 reaches the second page ("Showing 21-…": a full page on the dev
 *      stack, a partial one on a fresh nightly volume).
 *   3. Item 4 (the void dialog), never mutating: opened through the real path (a list row opens
 *      /dashboard/orders/{id}; "Void order" there opens it) on a voidable order at 390, 768 and
 *      1280 px; it sits inside the viewport, nothing in it overflows sideways, its title is inside
 *      it, Keep order and Void order are visible and at least 44 px tall. It is closed with Keep
 *      order, and a request listener proves no void request is ever sent.
 *   4. Defect 2, TypeScript side (checkout copy): at Peckham Jollof Co.'s checkout (the name read
 *      from the public API and asserted to end in a full stop) the allergen panel's intro reads
 *      "These items are prepared by Peckham Jollof Co. Based on what the kitchen has declared,
 *      this order contains:" with no double full stop, and no order is placed.
 *   5. Defect 2, Java side (invitation email): the Mailhog invitation for that shop reads
 *      "… for Peckham Jollof Co." then a line break, with no double full stop.
 *
 * INSTRUMENT ARMS. Each test carries an arm that runs on EVERY execution and must fail as
 * designed; an arm that passes means the assertion beside it cannot fail:
 *   - test 1: the old four-column finance grid forced back at 768 (bound figures) must be caught;
 *   - test 2: the old nine-button 32 px pagination forced back at 390 must be caught;
 *   - test 3: the void dialog forced to a 600 px minimum width at 390 must be caught;
 *   - tests 4 and 5: the double-full-stop checker on a planted "Co.." string must report it.
 *
 * COVERAGE BOUNDARY. This needs a live stack: a Keycloak login, Mailhog, core-java, and a frontend
 * image rebuilt from the tree under test. Dashboard routes resolve the NextAuth session server-side
 * (app/dashboard/layout.tsx redirects without one), the defects are geometry jsdom cannot compute,
 * and the two copy defects are only observable in the rendered checkout and the sent email. It runs
 * at the 37-59 gate and in the nightly full-stack lane, never per PR. The per-PR guards are the
 * jest and JUnit tests of 37-56..37-58 (lib/sentence-end, Sentences, the finance and pagination
 * declarations). What no instrument here measures: motion (the finance cards' entrance is waited
 * out, not judged) and NoAccessPage (part of UAT test 6's re-test, not a G-37-6 defect).
 *
 * WHAT A RUN CREATES on the shared dev stack: one invitation for a Staff role at Peckham Jollof Co.
 * in tenant …0001 (test 5), cancelled in the test and again in its `finally` if still listed; one
 * anonymous basket at Peckham Jollof Co. (test 4), cleared before its context closes. Nothing is
 * voided. Nothing is ordered EXCEPT on a stack with no voidable order at all (a fresh nightly
 * volume, 2026-10-18): test 3 then places one COLLECTION order at Peckham Jollof Co. as the vendor
 * (submit: true, then confirm) and leaves it CONFIRMED, since the dialog it opens must be real and
 * closing it without voiding is the test. Test 3 copies the page's own Authorization header into
 * one local variable, passed only to its own request calls (the status lookups and that fixture
 * order); it is never annotated, attached, logged or written.
 *
 * @desktop-only: the tests pin their own viewports with page.setViewportSize, so 375/390 px are
 * covered here too, and one run per stack keeps the invitation and basket fixtures single.
 *
 * Run: set -a; . ./.env; set +a; cd frontend && npx playwright test e2e/screens-copy-uat.spec.ts --project=desktop
 */
import { test, expect, type Page, type Request, type TestInfo } from "@playwright/test"
import { CART_KEY_PREFIX } from "../lib/cart-identity"
import { messageText, messagesFor, toasts, vendorLogin } from "./helpers/staff-invitee"

const API = process.env.PLAYWRIGHT_API_URL || process.env.NEXT_PUBLIC_API_URL || "http://localhost:9090"
const SHOP_SLUG = "peckham-jollof-co"
const SUMMARY_PATH = "/api/v1/financial-transactions/summary"
const FINANCE_WIDTHS = [390, 768, 1024, 1280] as const
const ORDERS_WIDTHS = [375, 390, 768, 1024, 1280] as const
const VOID_WIDTHS = [390, 768, 1280] as const
/** formatSignedPennies(±99_999_999): "£999,999.99" / "−£999,999.99", the declared bound (37-58). */
const BOUND = 99_999_999
const VAT_RATES = ["STANDARD", "REDUCED", "ZERO", "EXEMPT"] as const
const VOIDABLE_STATUSES = ["COMPLETED", "CONFIRMED", "PREPARING", "READY"] as const
const MIN_TARGET = 44
const MAX_BASKET_ADDS = 4

// ---------------------------------------------------------------------------------------------
// Shared helpers

/** Two animation frames: the layout the viewport change caused has been computed and painted. */
async function settle(page: Page) {
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  )
}

function annotate(testInfo: TestInfo, type: string, value: unknown) {
  testInfo.annotations.push({ type, description: typeof value === "string" ? value : JSON.stringify(value) })
}

/**
 * Every run of exactly two full stops, or of four or more, with its context. A run of three is an
 * ellipsis typed as dots and is not a defect. Shared by the checkout panel, the email and both arms.
 */
function doubleFullStops(text: string): string[] {
  const hits: string[] = []
  const re = /\.{2,}/g
  for (let m = re.exec(text); m; m = re.exec(text)) {
    if (m[0].length !== 3) hits.push(text.slice(Math.max(0, m.index - 30), m.index + m[0].length + 10))
  }
  return hits
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
}

type ContainerCondition = { condition: string; px: number | null; selectors: string[] }

/**
 * The @container conditions in the page's stylesheets whose inner rules' selectors contain any of
 * `needles`, with each condition's rem threshold in px at the root font size.
 */
async function containerConditions(page: Page, needles: string[]): Promise<ContainerCondition[]> {
  return page.evaluate((needles) => {
    const found = new Map<string, Set<string>>()
    const walk = (rules: CSSRuleList) => {
      for (const rule of Array.from(rules)) {
        if (typeof CSSContainerRule !== "undefined" && rule instanceof CSSContainerRule) {
          const inner = (Array.from(rule.cssRules) as CSSStyleRule[]).map((r) => r.selectorText || "")
          const hits = inner.filter((s) => needles.some((n) => s.includes(n)))
          if (hits.length > 0) {
            const set = found.get(rule.conditionText) ?? new Set<string>()
            hits.forEach((h) => set.add(h))
            found.set(rule.conditionText, set)
          }
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
    return Array.from(found.entries()).map(([condition, selectors]) => {
      const m = /min-width:\s*([\d.]+)rem/.exec(condition)
      return { condition, px: m ? parseFloat(m[1]) * rootPx : null, selectors: Array.from(selectors).slice(0, 4) }
    })
  }, needles)
}

// ---------------------------------------------------------------------------------------------
// Defect 1: finance, measured in the page

type FinanceSummaryBody = {
  totalRevenuePennies: number
  totalExpensesPennies: number
  netAmountPennies: number
  totalVatPennies: number
  vatBreakdown: { vatRate: string; totalAmountPennies: number }[]
}

type FinanceResult = {
  /** "testid" on the 37-58 markup; "structural" on the pre-37-58 markup (the RED runtime). */
  selector: string
  statTexts: string[]
  vatTexts: string[]
  expectedStat: string[]
  expectedVat: string[]
  boxFailures: string[]
  overlaps: string[]
  bannerFound: boolean
  bannerFailures: string[]
  columns: number
  cardWidths: number[]
  statContainerWidth: number
  vatContainerWidth: number
  widestMaxContent: number
  statCardNeed: number
  vatTileNeed: number
}

/**
 * The box checker over every finance figure, plus the per-width record. Each figure's text client
 * rects must lie inside its containing `.bg-card` (and, for a VAT figure, inside its tile) within
 * 0.5 px, all on one line (distinct rounded tops more than 2 px apart count as lines), and the
 * figure must not scroll sideways (scrollWidth at most clientWidth + 1). The stat grid is marked
 * data-e2e-stat-grid so the instrument arm can force the old layout onto it.
 */
async function measureFinance(page: Page, summary: FinanceSummaryBody): Promise<FinanceResult> {
  return page.evaluate((summary) => {
    const MINUS = String.fromCharCode(0x2212)
    const gbp = new Intl.NumberFormat("en-GB", { style: "currency", currency: "GBP" })
    const fmt = (p: number) => (p < 0 ? `${MINUS}${gbp.format(Math.abs(p) / 100)}` : gbp.format(Math.abs(p) / 100))
    const expectedStat = [
      summary.totalRevenuePennies,
      summary.totalExpensesPennies,
      summary.netAmountPennies,
      summary.totalVatPennies,
    ].map(fmt)
    const expectedVat = summary.vatBreakdown.map((v) => fmt(v.totalAmountPennies))

    let selector = "testid"
    let stat = Array.from(
      document.querySelectorAll('[data-testid="finance-stat-grid"] [data-testid="finance-amount"]')
    ) as HTMLElement[]
    let vat = Array.from(
      document.querySelectorAll('[data-testid="finance-vat-grid"] [data-testid="finance-amount"]')
    ) as HTMLElement[]
    if (stat.length === 0) {
      // The pre-37-58 markup carries no test ids: the same figures, found by their classes.
      selector = "structural"
      stat = Array.from(document.querySelectorAll("main .bg-card .text-2xl.font-bold")) as HTMLElement[]
      vat = Array.from(document.querySelectorAll("main .bg-card p.text-lg.font-bold")) as HTMLElement[]
    }

    const failures: string[] = []
    const describe = (el: HTMLElement, i: number, kind: string) => `${kind}[${i}] "${(el.textContent || "").trim()}"`
    const check = (el: HTMLElement, i: number, kind: string) => {
      const card = el.closest(".bg-card") as HTMLElement | null
      if (!card) {
        failures.push(`VOID: ${describe(el, i, kind)} has no containing .bg-card`)
        return
      }
      const boxes: HTMLElement[] = [card]
      const tile = el.closest(".rounded-lg.border") as HTMLElement | null
      if (tile && tile !== card) boxes.push(tile)
      const range = document.createRange()
      range.selectNodeContents(el)
      const rects = Array.from(range.getClientRects()).filter((r) => r.width > 0 && r.height > 0)
      if (rects.length === 0) failures.push(`VOID: ${describe(el, i, kind)} has no text rects`)
      for (const box of boxes) {
        const b = box.getBoundingClientRect()
        const name = box === card ? "card" : "tile"
        for (const r of rects) {
          if (r.left < b.left - 0.5 || r.right > b.right + 0.5 || r.top < b.top - 0.5 || r.bottom > b.bottom + 0.5) {
            failures.push(
              `${describe(el, i, kind)} leaves its ${name}: text ${Math.round(r.left)}..${Math.round(r.right)} vs ${name} ${Math.round(b.left)}..${Math.round(b.right)}`
            )
            break
          }
        }
      }
      const tops = rects.map((r) => Math.round(r.top)).sort((a, b) => a - b)
      let lines = tops.length > 0 ? 1 : 0
      for (let k = 1; k < tops.length; k++) if (tops[k] - tops[k - 1] > 2) lines += 1
      if (lines > 1) failures.push(`${describe(el, i, kind)} spans ${lines} lines`)
      if (el.scrollWidth > el.clientWidth + 1) {
        failures.push(`${describe(el, i, kind)} scrolls sideways: ${el.scrollWidth} > ${el.clientWidth}`)
      }
    }
    stat.forEach((el, i) => check(el, i, "stat"))
    vat.forEach((el, i) => check(el, i, "vat"))

    // No two stat cards intersect.
    const cards = stat.map((el) => el.closest(".bg-card") as HTMLElement | null).filter((c): c is HTMLElement => !!c)
    const overlaps: string[] = []
    for (let a = 0; a < cards.length; a++) {
      for (let b = a + 1; b < cards.length; b++) {
        const x = cards[a].getBoundingClientRect()
        const y = cards[b].getBoundingClientRect()
        const w = Math.min(x.right, y.right) - Math.max(x.left, y.left)
        const h = Math.min(x.bottom, y.bottom) - Math.max(x.top, y.top)
        if (w > 0.5 && h > 0.5) overlaps.push(`stat card ${a} overlaps stat card ${b} by ${Math.round(w)}x${Math.round(h)}`)
      }
    }

    const banner = document.querySelector('[data-testid="finance-scope-banner"]') as HTMLElement | null
    const bannerFailures: string[] = []
    if (banner && banner.scrollWidth > banner.clientWidth + 1) {
      bannerFailures.push(`scope banner scrolls sideways: ${banner.scrollWidth} > ${banner.clientWidth}`)
    }

    // The per-width record: the grid's columns, the card widths, the containers, the needs.
    const grid =
      (document.querySelector('[data-testid="finance-stat-grid"]') as HTMLElement | null) ??
      (cards[0]?.parentElement as HTMLElement | null)
    if (grid) grid.setAttribute("data-e2e-stat-grid", "")
    const columns = grid ? getComputedStyle(grid).gridTemplateColumns.split(" ").filter(Boolean).length : 0
    const contentWidth = (el: HTMLElement | null) => {
      if (!el) return 0
      const cs = getComputedStyle(el)
      return Math.round(el.clientWidth - parseFloat(cs.paddingLeft || "0") - parseFloat(cs.paddingRight || "0"))
    }
    const vatGrid =
      (document.querySelector('[data-testid="finance-vat-grid"]') as HTMLElement | null) ??
      (vat[0]?.closest(".grid") as HTMLElement | null)

    const maxContent = (el: HTMLElement) => {
      const before = el.style.width
      el.style.width = "max-content"
      const w = el.getBoundingClientRect().width
      el.style.width = before
      return w
    }
    const px = (v: string) => parseFloat(v || "0")
    let widestMaxContent = 0
    let statCardNeed = 0
    for (const el of stat) {
      const mc = maxContent(el)
      widestMaxContent = Math.max(widestMaxContent, mc)
      const content = el.parentElement as HTMLElement
      const card = el.closest(".bg-card") as HTMLElement | null
      if (!content || !card) continue
      const c = getComputedStyle(content)
      const k = getComputedStyle(card)
      statCardNeed = Math.max(
        statCardNeed,
        mc + px(c.paddingLeft) + px(c.paddingRight) + px(k.borderLeftWidth) + px(k.borderRightWidth)
      )
    }
    let vatTileNeed = 0
    for (const el of vat) {
      const mc = maxContent(el)
      widestMaxContent = Math.max(widestMaxContent, mc)
      const tile = el.closest(".rounded-lg.border") as HTMLElement | null
      if (!tile) continue
      const t = getComputedStyle(tile)
      const badge = tile.firstElementChild as HTMLElement | null
      const badgeWidth = badge && badge !== el.parentElement ? badge.getBoundingClientRect().width : 0
      vatTileNeed = Math.max(
        vatTileNeed,
        badgeWidth + px(t.columnGap) + mc + px(t.paddingLeft) + px(t.paddingRight) + px(t.borderLeftWidth) + px(t.borderRightWidth)
      )
    }

    return {
      selector,
      statTexts: stat.map((el) => (el.textContent || "").trim()),
      vatTexts: vat.map((el) => (el.textContent || "").trim()),
      expectedStat,
      expectedVat,
      boxFailures: failures,
      overlaps,
      bannerFound: !!banner,
      bannerFailures,
      columns,
      cardWidths: cards.map((c) => Math.round(c.getBoundingClientRect().width)),
      statContainerWidth: contentWidth((grid?.parentElement as HTMLElement | null) ?? null),
      vatContainerWidth: contentWidth((vatGrid?.parentElement as HTMLElement | null) ?? null),
      widestMaxContent: Math.round(widestMaxContent * 10) / 10,
      statCardNeed: Math.round(statCardNeed * 10) / 10,
      vatTileNeed: Math.round(vatTileNeed * 10) / 10,
    }
  }, summary)
}

/** Open /dashboard/finance at `width` and return the summary the page itself received. */
async function openFinance(page: Page, width: number): Promise<FinanceSummaryBody> {
  await page.setViewportSize({ width, height: 900 })
  const summaryRead = page.waitForResponse(
    (r) => new URL(r.url()).pathname === SUMMARY_PATH && r.request().method() === "GET",
    { timeout: 30_000 }
  )
  await page.goto("/dashboard/finance", { waitUntil: "domcontentloaded" })
  const response = await summaryRead
  expect(response.status(), "the finance summary must load (a Group admin reads it)").toBe(200)
  const body = (await response.json()) as FinanceSummaryBody
  // The figures render after the read; then the cards' entrance motion (0.1 s delay) is waited out.
  await expect
    .poll(
      () =>
        page.evaluate(
          () =>
            document.querySelectorAll('[data-testid="finance-amount"]').length +
            document.querySelectorAll("main .bg-card .text-2xl.font-bold").length
        ),
      { timeout: 20_000, message: "the finance figures must render" }
    )
    .toBeGreaterThanOrEqual(4)
  await page.waitForTimeout(800)
  await settle(page)
  return body
}

/**
 * The finance container thresholds that apply to each grid: every @container condition whose inner
 * rule's selector the stat grid (or the VAT grid) itself matches, as `{ which, condition, px }`.
 */
async function financeGridThresholds(
  page: Page
): Promise<Array<{ which: "stat" | "vat"; condition: string; px: number }>> {
  const conditions = await containerConditions(page, ["grid-cols"])
  return page.evaluate((conditions) => {
    const grids = {
      stat: document.querySelector('[data-testid="finance-stat-grid"]'),
      vat: document.querySelector('[data-testid="finance-vat-grid"]'),
    }
    const out: Array<{ which: "stat" | "vat"; condition: string; px: number }> = []
    for (const c of conditions) {
      if (c.px === null) continue
      for (const which of ["stat", "vat"] as const) {
        const el = grids[which]
        if (el && c.selectors.some((s) => el.matches(s))) out.push({ which, condition: c.condition, px: c.px })
      }
    }
    return out
  }, conditions)
}

/**
 * Steer the viewport until the `which` grid's container content box is exactly `target` px wide:
 * the first width at which that grid's @container rule applies. Returns the width reached, or null
 * when it could not be reached (the caller reports that as a failure, never a pass).
 */
async function steerFinanceContainer(page: Page, which: "stat" | "vat", target: number): Promise<number | null> {
  const read = () =>
    page.evaluate((which) => {
      const grid = document.querySelector(`[data-testid="finance-${which}-grid"]`) as HTMLElement | null
      const c = grid?.parentElement as HTMLElement | null
      if (!c) return null
      const cs = getComputedStyle(c)
      const px = (v: string) => parseFloat(v || "0")
      return (
        c.getBoundingClientRect().width -
        px(cs.borderLeftWidth) -
        px(cs.borderRightWidth) -
        px(cs.paddingLeft) -
        px(cs.paddingRight)
      )
    }, which)
  let viewport = page.viewportSize()?.width ?? 1280
  for (let attempt = 0; attempt < 6; attempt++) {
    const w = await read()
    if (w === null) return null
    if (Math.abs(w - target) < 0.5) return Math.round(w * 10) / 10
    viewport += Math.round(target - w)
    await page.setViewportSize({ width: viewport, height: 900 })
    await settle(page)
  }
  return null
}

function recordFinance(testInfo: TestInfo, label: string, width: number, r: FinanceResult) {
  annotate(testInfo, `finance-${label}-${width}`, {
    selector: r.selector,
    columns: r.columns,
    cardWidths: r.cardWidths,
    statContainerWidth: r.statContainerWidth,
    vatContainerWidth: r.vatContainerWidth,
    statTexts: r.statTexts,
    vatTexts: r.vatTexts,
    boxFailures: r.boxFailures,
    overlaps: r.overlaps,
    bannerFailures: r.bannerFailures,
  })
}

function assertFinance(label: string, width: number, r: FinanceResult) {
  const where = `G-37-6 defect 1, ${label} summary, ${width}px`
  expect.soft(r.statTexts.length, `${where}: non-vacuity, exactly four stat figures measured`).toBe(4)
  expect
    .soft(r.vatTexts.length, `${where}: non-vacuity, one VAT figure per vatBreakdown entry`)
    .toBe(r.expectedVat.length)
  expect.soft(r.statTexts, `${where}: each stat figure is the whole formatted value`).toEqual(r.expectedStat)
  expect.soft(r.vatTexts, `${where}: each VAT figure is the whole formatted value`).toEqual(r.expectedVat)
  expect.soft(r.boxFailures, `${where}: every figure on one line inside its own card`).toEqual([])
  expect.soft(r.overlaps, `${where}: no two stat cards overlap`).toEqual([])
  expect.soft(r.bannerFound, `${where}: the scope banner is present`).toBe(true)
  expect.soft(r.bannerFailures, `${where}: the scope banner does not scroll sideways`).toEqual([])
}

// ---------------------------------------------------------------------------------------------
// Defect 3: the Pagination controls, measured in the page

type ControlsResult = {
  failures: string[]
  visible: string[]
  rootWidth: number
  navMaxContent: number
}

/**
 * The controls checker for the Pagination nav: every visible button (computed display not none)
 * inside the card and, horizontally, inside the viewport (0.5 px); at least 44 x 44 px (0.5 px);
 * the nav does not scroll sideways; the Pagination root (the nav's parent) at least 200 px wide.
 */
async function measureControls(page: Page): Promise<ControlsResult> {
  return page.evaluate((minTarget) => {
    const nav = document.querySelector('nav[aria-label="Pagination"]') as HTMLElement | null
    if (!nav) return { failures: ["VOID: no Pagination nav"], visible: [], rootWidth: 0, navMaxContent: 0 }
    const root = nav.parentElement as HTMLElement
    const card = nav.closest(".bg-card") as HTMLElement | null
    const failures: string[] = []
    if (!card) failures.push("VOID: the Pagination nav has no containing .bg-card")
    const viewport = document.documentElement.clientWidth
    const c = card?.getBoundingClientRect()
    const visible: string[] = []
    for (const b of Array.from(nav.querySelectorAll("button")) as HTMLElement[]) {
      if (getComputedStyle(b).display === "none") continue
      const r = b.getBoundingClientRect()
      if (r.width === 0 && r.height === 0) continue
      const name = b.getAttribute("aria-label") || (b.textContent || "").trim() || "button"
      visible.push(name)
      if (c && (r.left < c.left - 0.5 || r.right > c.right + 0.5 || r.top < c.top - 0.5 || r.bottom > c.bottom + 0.5)) {
        failures.push(`"${name}" leaves the card: ${Math.round(r.left)}..${Math.round(r.right)} vs ${Math.round(c.left)}..${Math.round(c.right)}`)
      }
      if (r.left < -0.5 || r.right > viewport + 0.5) {
        failures.push(`"${name}" leaves the viewport: ${Math.round(r.left)}..${Math.round(r.right)} vs 0..${viewport}`)
      }
      if (r.width < minTarget - 0.5 || r.height < minTarget - 0.5) {
        failures.push(`"${name}" is ${Math.round(r.width)} x ${Math.round(r.height)} px, under ${minTarget} x ${minTarget}`)
      }
    }
    if (visible.length < 2) failures.push(`VOID: only ${visible.length} visible control(s)`)
    if (nav.scrollWidth > nav.clientWidth + 1) {
      failures.push(`the nav scrolls sideways: ${nav.scrollWidth} > ${nav.clientWidth}`)
    }
    const rootWidth = root.getBoundingClientRect().width
    if (rootWidth < 200) failures.push(`the Pagination root is ${Math.round(rootWidth)} px wide, under 200`)
    const before = nav.style.width
    nav.style.width = "max-content"
    const navMaxContent = nav.getBoundingClientRect().width
    nav.style.width = before
    return { failures, visible, rootWidth: Math.round(rootWidth), navMaxContent: Math.round(navMaxContent * 10) / 10 }
  }, MIN_TARGET)
}

// ---------------------------------------------------------------------------------------------
// Item 4: the void dialog, measured in the page

type DialogResult = {
  failures: string[]
  soft: string[]
  width: number
  descendants: number
}

async function measureDialog(page: Page, title: string): Promise<DialogResult> {
  const dialog = page.getByRole("dialog", { name: title })
  return dialog.evaluate((el, minTarget) => {
    const failures: string[] = []
    const soft: string[] = []
    const viewportW = document.documentElement.clientWidth
    const viewportH = window.innerHeight
    const d = el.getBoundingClientRect()
    if (d.left < -0.5 || d.right > viewportW + 0.5 || d.top < -0.5 || d.bottom > viewportH + 0.5) {
      failures.push(
        `the dialog leaves the viewport: ${Math.round(d.left)}..${Math.round(d.right)} x ${Math.round(d.top)}..${Math.round(d.bottom)} vs ${viewportW} x ${viewportH}`
      )
    }
    if (el.scrollWidth > el.clientWidth + 1) failures.push(`the dialog scrolls sideways: ${el.scrollWidth} > ${el.clientWidth}`)
    let descendants = 0
    for (const child of Array.from(el.querySelectorAll("*")) as HTMLElement[]) {
      const r = child.getBoundingClientRect()
      if (r.width === 0 && r.height === 0) continue
      descendants += 1
      if (r.left < d.left - 0.5 || r.right > d.right + 0.5) {
        const label = `${child.tagName.toLowerCase()} "${(child.textContent || "").trim().slice(0, 40)}"`
        failures.push(`${label} leaves the dialog sideways: ${Math.round(r.left)}..${Math.round(r.right)} vs ${Math.round(d.left)}..${Math.round(d.right)}`)
      }
    }
    const cs = getComputedStyle(el)
    const px = (v: string) => parseFloat(v || "0")
    const box = {
      left: d.left + px(cs.borderLeftWidth) + px(cs.paddingLeft),
      right: d.right - px(cs.borderRightWidth) - px(cs.paddingRight),
      top: d.top + px(cs.borderTopWidth) + px(cs.paddingTop),
      bottom: d.bottom - px(cs.borderBottomWidth) - px(cs.paddingBottom),
    }
    const titleEl = el.querySelector("h2") as HTMLElement | null
    const buttons = Array.from(el.querySelectorAll("button")) as HTMLElement[]
    const close = buttons.find((b) => (b.textContent || "").trim() === "Close") ?? null
    if (!titleEl) failures.push("VOID: no dialog title")
    else {
      const range = document.createRange()
      range.selectNodeContents(titleEl)
      const rects = Array.from(range.getClientRects()).filter((r) => r.width > 0 && r.height > 0)
      if (rects.length === 0) failures.push("VOID: the title has no text rects")
      // Horizontal against the CONTENT box (a title running past the edge is the defect class);
      // vertical against the dialog's BORDER box: DialogTitle is leading-none, so its text rect
      // (the font's content area, 21 px for 18 px text) overhangs its 18 px line box by ~2 px
      // into the padding without any ink leaving the box (measured on the RED run, 37-59).
      for (const r of rects) {
        if (r.left < box.left - 0.5 || r.right > box.right + 0.5 || r.top < d.top - 0.5 || r.bottom > d.bottom + 0.5) {
          failures.push(
            `the title text leaves the content box: x ${r.left.toFixed(1)}..${r.right.toFixed(1)} y ${r.top.toFixed(1)}..${r.bottom.toFixed(1)} vs x ${box.left.toFixed(1)}..${box.right.toFixed(1)} y ${box.top.toFixed(1)}..${box.bottom.toFixed(1)}`
          )
          break
        }
      }
      if (close) {
        const k = close.getBoundingClientRect()
        for (const r of rects) {
          const w = Math.min(r.right, k.right) - Math.max(r.left, k.left)
          const h = Math.min(r.bottom, k.bottom) - Math.max(r.top, k.top)
          if (w > 0.5 && h > 0.5) {
            soft.push(`the title text runs under the Close control by ${Math.round(w)} x ${Math.round(h)} px`)
            break
          }
        }
      }
    }
    const inside = (r: DOMRect) =>
      r.left >= d.left - 0.5 && r.right <= d.right + 0.5 && r.top >= d.top - 0.5 && r.bottom <= d.bottom + 0.5
    for (const name of ["Keep order", "Void order"]) {
      const b = buttons.find((x) => (x.textContent || "").trim() === name) ?? null
      if (!b) {
        failures.push(`VOID: no "${name}" button`)
        continue
      }
      const r = b.getBoundingClientRect()
      if (getComputedStyle(b).display === "none" || r.width === 0) failures.push(`"${name}" is not visible`)
      if (!inside(r)) failures.push(`"${name}" leaves the dialog`)
      if (r.height < minTarget - 0.5) failures.push(`"${name}" is ${Math.round(r.height)} px tall, under ${minTarget}`)
    }
    const textarea = el.querySelector("textarea") as HTMLElement | null
    if (!textarea) failures.push("VOID: no reason textarea")
    else {
      const r = textarea.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) failures.push("the reason textarea is not visible")
      if (!inside(r)) failures.push("the reason textarea leaves the dialog")
    }
    return { failures, soft, width: Math.round(d.width), descendants }
  }, MIN_TARGET)
}

/** The dialog's entrance animation has finished (bounded: an animation that never ends is waited 3 s). */
async function animationsDone(page: Page, title: string) {
  await page.getByRole("dialog", { name: title }).evaluate((el) =>
    Promise.race([
      Promise.all(el.getAnimations({ subtree: true }).map((a) => a.finished.catch(() => undefined))),
      new Promise((resolve) => setTimeout(resolve, 3_000)),
    ])
  )
  await settle(page)
}

type OrderRow = { id: string; orderNumber?: string | null; voidedAt?: string | null }

test.describe("UAT gap G-37-6, live (37-59) @desktop-only", () => {
  test("G-37-6 defect 1: every finance figure stays whole inside its own card, live and at the bound", async ({
    page,
  }, testInfo) => {
    test.setTimeout(300_000)
    await page.setViewportSize({ width: 1280, height: 900 })
    await vendorLogin(page)

    // ---- The live summary ----------------------------------------------------------------------
    for (const width of FINANCE_WIDTHS) {
      const summary = await openFinance(page, width)
      const result = await measureFinance(page, summary)
      recordFinance(testInfo, "live", width, result)
      assertFinance("live", width, result)
    }

    // ---- The declared bound, injected by route (the dev ledger holds only small amounts) -------
    const isSummary = (url: URL) => url.pathname === SUMMARY_PATH
    await page.route(isSummary, async (route) => {
      const response = await route.fetch()
      const body = (await response.json()) as Record<string, unknown>
      const json = {
        ...body,
        totalRevenuePennies: BOUND,
        totalExpensesPennies: BOUND,
        totalVatPennies: BOUND,
        netAmountPennies: -BOUND,
        vatBreakdown: VAT_RATES.map((vatRate) => ({
          vatRate,
          totalAmountPennies: BOUND,
          totalVatPennies: BOUND,
          count: 1,
        })),
      }
      await route.fulfill({ response, json })
    })
    try {
      for (const width of FINANCE_WIDTHS) {
        const summary = await openFinance(page, width)
        expect(summary.netAmountPennies, "the injected summary reached the page").toBe(-BOUND)
        const result = await measureFinance(page, summary)
        recordFinance(testInfo, "bound", width, result)
        annotate(testInfo, `finance-bound-need-${width}`, {
          widestMaxContent: result.widestMaxContent,
          statCardNeed: result.statCardNeed,
          vatTileNeed: result.vatTileNeed,
          statContainerWidth: result.statContainerWidth,
          vatContainerWidth: result.vatContainerWidth,
          columns: result.columns,
        })
        assertFinance("bound", width, result)
      }
      annotate(testInfo, "finance-thresholds", await containerConditions(page, ["grid-cols"]))

      // ---- At each grid's own container threshold (bound figures) ------------------------------
      // The four widths above never land a container on a threshold, so a threshold below the
      // figure's need passes them all (37-59 GREEN: 27rem/55rem/26rem/52rem were each below the
      // bound figure's need and every width passed). Here each container is steered to exactly its
      // threshold, the first width at which its grid takes more columns, and must still hold.
      const boundAtEdge = await openFinance(page, 1280)
      const edges = await financeGridThresholds(page)
      annotate(testInfo, "finance-edges", edges)
      expect
        .soft(edges.length, "G-37-6 defect 1: non-vacuity, two thresholds read for each finance grid")
        .toBe(4)
      for (const edge of edges) {
        const reached = await steerFinanceContainer(page, edge.which, edge.px)
        expect.soft(reached, `${edge.which} container steered to ${edge.condition} (${edge.px}px)`).toBe(edge.px)
        if (reached === null) continue
        const result = await measureFinance(page, boundAtEdge)
        annotate(testInfo, `finance-edge-${edge.which}-${edge.px}`, {
          condition: edge.condition,
          viewport: page.viewportSize()?.width,
          containerWidth: reached,
          columns: result.columns,
          cardWidths: result.cardWidths,
          statCardNeed: result.statCardNeed,
          vatTileNeed: result.vatTileNeed,
          boxFailures: result.boxFailures,
        })
        expect
          .soft(result.boxFailures, `G-37-6 defect 1, bound summary, ${edge.which} container at ${edge.condition}: every figure on one line inside its own card`)
          .toEqual([])
      }

      // INSTRUMENT ARM at 768 (bound figures): the old four equal columns, forced back, must be caught.
      // measureFinance marks the stat grid (data-e2e-stat-grid) only at its END, and openFinance
      // loads a fresh page, so the grid is marked by one throwaway measurement BEFORE the style is
      // injected. Without it the forced rule matched nothing while the boxes were measured (the
      // GREEN run's 0 failures, 37-59), and on the pre-fix runtime the arm "fired" only because the
      // old grid was natively four columns at 768.
      const armSummary = await openFinance(page, 768)
      await measureFinance(page, armSummary)
      const marked = await page.locator("[data-e2e-stat-grid]").count()
      expect(marked, "the arm marked exactly one stat grid before forcing it").toBe(1)
      const forced = await page.addStyleTag({
        content: "[data-e2e-stat-grid] { grid-template-columns: repeat(4, minmax(0, 1fr)) !important; }",
      })
      await settle(page)
      const armed = await measureFinance(page, {
        totalRevenuePennies: BOUND,
        totalExpensesPennies: BOUND,
        netAmountPennies: -BOUND,
        totalVatPennies: BOUND,
        vatBreakdown: VAT_RATES.map((vatRate) => ({ vatRate, totalAmountPennies: BOUND })),
      })
      await forced.evaluate((n) => (n as Element).remove())
      expect(armed.columns, "the arm forced four columns").toBe(4)
      expect(armed.boxFailures.length, "the box checker must catch the old four-column grid at 768").toBeGreaterThan(0)
      annotate(testInfo, "arm-finance-768", { columns: armed.columns, boxFailures: armed.boxFailures })
    } finally {
      await page.unroute(isSummary)
    }
  })

  test("G-37-6 defect 3: the orders Pagination fits its card at 44 px, 375 to 1280", async ({ page }, testInfo) => {
    test.setTimeout(240_000)
    await page.setViewportSize({ width: 1280, height: 900 })
    await vendorLogin(page)
    await page.goto("/dashboard/orders", { waitUntil: "domcontentloaded" })
    const showing = page.getByText(/^Showing 1-20 of \d+$/)
    await expect(showing, "non-vacuity: the first page of orders").toBeVisible({ timeout: 20_000 })
    const total = Number(((await showing.textContent()) || "").replace(/^.* of /, ""))
    expect(total, "non-vacuity: more than one page of orders").toBeGreaterThan(20)
    const nav = page.getByRole("navigation", { name: "Pagination" })

    for (const width of ORDERS_WIDTHS) {
      await page.setViewportSize({ width, height: 900 })
      await settle(page)
      const result = await measureControls(page)
      annotate(testInfo, `pagination-${width}`, result)
      expect.soft(result.failures, `G-37-6 defect 3 at ${width}px: every control inside the card at 44 x 44`).toEqual([])
      if (width === 390) {
        for (const name of ["Go to first page", "Go to previous page", "Go to next page", "Go to last page"]) {
          await expect.soft(nav.getByRole("button", { name }), `${name} is visible at 390`).toBeVisible()
        }
      }
      if (width === 1280) {
        const pageWindow = await page.evaluate(() => {
          const buttons = Array.from(
            document.querySelectorAll('nav[aria-label="Pagination"] button')
          ) as HTMLElement[]
          const shown = buttons.filter(
            (b) => getComputedStyle(b).display !== "none" && /^Go to page \d+$/.test(b.getAttribute("aria-label") || "")
          )
          return { pages: shown.length, current: shown.filter((b) => b.getAttribute("aria-current") === "page").length }
        })
        annotate(testInfo, "pagination-window-1280", pageWindow)
        // paginationWindow clamps to the ends, so the window holds min(5, totalPages) pages:
        // a fresh nightly volume has two pages of orders, the shared dev DB has eight (2026-10-18).
        const totalPages = Math.ceil(total / 20)
        const expectedWindow = Math.min(5, totalPages)
        expect
          .soft(pageWindow.pages, `the ${expectedWindow}-page window at 1280 (${totalPages} pages in total)`)
          .toBe(expectedWindow)
        expect.soft(pageWindow.current, "exactly one aria-current page at 1280").toBe(1)
      }
    }
    annotate(
      testInfo,
      "pagination-thresholds",
      await containerConditions(page, ["inline-flex", "hidden"]).then((all) =>
        all.filter((c) => /min-width:\s*(19|21|27)rem/.test(c.condition))
      )
    )

    // Next and first at 390: the controls work where they used to sit outside the card.
    await page.setViewportSize({ width: 390, height: 900 })
    await settle(page)
    let stepError: string | null = null
    try {
      await nav.getByRole("button", { name: "Go to next page" }).click({ timeout: 10_000 })
      // "21-40" on a full second page, "21-N" on a partial one (a fresh nightly volume holds 21..40).
      await expect(page.getByText(/^Showing 21-\d+ of \d+$/)).toBeVisible({ timeout: 15_000 })
      await nav.getByRole("button", { name: "Go to first page" }).click({ timeout: 10_000 })
      await expect(page.getByText(/^Showing 1-20 of \d+$/)).toBeVisible({ timeout: 15_000 })
    } catch (error) {
      stepError = String(error).slice(0, 300)
    }
    expect.soft(stepError, "G-37-6 defect 3: next then first at 390 page the list").toBeNull()

    // INSTRUMENT ARM at 390: the old nine 32 px buttons, unwrapped, must be caught.
    const forced = await page.addStyleTag({
      content: [
        'nav[aria-label="Pagination"] button { display: inline-flex !important; width: 32px !important; height: 32px !important; min-width: 32px !important; flex-shrink: 0 !important; padding: 0 !important; }',
        'nav[aria-label="Pagination"] > span { display: none !important; }',
        'nav[aria-label="Pagination"] { flex-wrap: nowrap !important; }',
        'div:has(> nav[aria-label="Pagination"]) { flex-wrap: nowrap !important; }',
      ].join("\n"),
    })
    await settle(page)
    const armed = await measureControls(page)
    await forced.evaluate((n) => (n as Element).remove())
    await settle(page)
    expect(armed.failures.length, "the controls checker must catch the old pagination at 390").toBeGreaterThan(0)
    annotate(testInfo, "arm-pagination-390", armed)
  })

  test("G-37-6 item 4: the void dialog fits at 390, 768 and 1280 and is closed without voiding", async ({
    page,
    request,
  }, testInfo) => {
    test.setTimeout(240_000)
    await page.setViewportSize({ width: 1280, height: 900 })
    const voidPosts: string[] = []
    page.context().on("request", (r: Request) => {
      if (r.method() === "POST" && new URL(r.url()).pathname.endsWith("/void")) voidPosts.push(new URL(r.url()).pathname)
    })
    await vendorLogin(page)

    // The page's own list read: its Authorization header is held in memory for the status reads.
    const listRead = page.waitForRequest(
      (r) => new URL(r.url()).pathname === "/api/v1/orders" && r.method() === "GET",
      { timeout: 30_000 }
    )
    await page.goto("/dashboard/orders", { waitUntil: "domcontentloaded" })
    const listHeaders = await (await listRead).allHeaders()
    const authorization = listHeaders["authorization"] ?? null
    const tenantHeader = listHeaders["x-tenant-id"] ?? null
    await expect(page.getByText(/^Showing 1-20 of \d+$/)).toBeVisible({ timeout: 20_000 })

    // Prove the opener: a row click opens the order's own route.
    await page.locator("tbody tr").first().locator("td.font-mono").click()
    await page.waitForURL(/\/dashboard\/orders\/[0-9a-f-]{36}$/, { timeout: 20_000 })
    annotate(testInfo, "void-opener", "a list row click opened /dashboard/orders/{uuid}")
    await page.goBack({ waitUntil: "domcontentloaded" })
    await expect(page.getByText(/^Showing 1-20 of \d+$/)).toBeVisible({ timeout: 20_000 })

    // A voidable order: CONFIRMED or later and not voided.
    let target: OrderRow | null = null
    let source = ""
    if (authorization) {
      for (const status of VOIDABLE_STATUSES) {
        const res = await request.get(`${API}/api/v1/orders/status/${status}?size=50`, {
          headers: { Authorization: authorization, ...(tenantHeader ? { "X-Tenant-Id": tenantHeader } : {}) },
        })
        if (!res.ok()) {
          annotate(testInfo, "void-lookup", `GET /orders/status/${status} answered ${res.status()}`)
          continue
        }
        const content = (((await res.json()) as { content?: OrderRow[] }).content ?? []) as OrderRow[]
        const found = content.find((o) => !o.voidedAt)
        if (found) {
          target = found
          source = `api status ${status}`
          break
        }
      }
    }
    if (!target) {
      // Fallback: page the list until a Completed row with no Voided badge appears (bounded).
      const pages = Math.min(Math.ceil(Number(((await page.getByText(/^Showing 1-20 of \d+$/).textContent()) || "").replace(/^.* of /, "")) / 20), 30)
      for (let p = 0; p < pages && !target; p++) {
        const index = await page.locator("tbody tr").evaluateAll((rows) =>
          rows.findIndex((r) => /\bCompleted\b/.test(r.textContent || "") && !/\bVoided\b/.test(r.textContent || ""))
        )
        if (index >= 0) {
          await page.locator("tbody tr").nth(index).locator("td.font-mono").click()
          await page.waitForURL(/\/dashboard\/orders\/[0-9a-f-]{36}$/, { timeout: 20_000 })
          const id = new URL(page.url()).pathname.split("/").pop() as string
          target = { id }
          source = `list page ${p + 1}`
          break
        }
        const next = page.getByRole("navigation", { name: "Pagination" }).getByRole("button", { name: "Go to next page" })
        if (await next.isDisabled()) break
        await next.click()
        await expect(page.getByText(new RegExp(`^Showing ${(p + 1) * 20 + 1}-`))).toBeVisible({ timeout: 15_000 })
      }
    }
    if (!target && authorization) {
      // A fresh volume (the nightly) has no CONFIRMED-or-later order yet, so the instrument makes
      // one the same way the dashboard would: placed as the vendor, submitted on creation
      // (DRAFT -> PENDING, D-13) and accepted (PENDING -> CONFIRMED). Left in place: closing the
      // void dialog without voiding is the test, and a voided fixture would not be voidable.
      const headers = { Authorization: authorization, ...(tenantHeader ? { "X-Tenant-Id": tenantHeader } : {}) }
      const shopsRes = await request.get(`${API}/api/v1/shops`, { headers })
      expect(shopsRes.ok(), `GET /api/v1/shops answered ${shopsRes.status()}`).toBe(true)
      const shopsBody = (await shopsRes.json()) as { content?: Array<{ id: string; slug: string }> } | Array<{ id: string; slug: string }>
      const shops = Array.isArray(shopsBody) ? shopsBody : (shopsBody.content ?? [])
      const shopId = shops.find((s) => s.slug === SHOP_SLUG)?.id ?? null
      expect(shopId, `the vendor lists ${SHOP_SLUG}`).not.toBeNull()
      const prodRes = await request.get(`${API}/api/v1/public/shops/${SHOP_SLUG}/products`)
      expect(prodRes.ok(), `GET /public/shops/${SHOP_SLUG}/products answered ${prodRes.status()}`).toBe(true)
      // The public products read answers a map of category -> products (measured at the 37-31 gate).
      const prodBody = (await prodRes.json()) as unknown
      const products: Array<{ id: string; inStock?: boolean }> = Array.isArray(prodBody)
        ? (prodBody as Array<{ id: string; inStock?: boolean }>)
        : Object.values(prodBody as Record<string, unknown>).flatMap((group) =>
            Array.isArray(group) ? (group as Array<{ id: string; inStock?: boolean }>) : []
          )
      const product = products.find((p) => p.inStock !== false) ?? null  // PublicProductDto.inStock
      expect(product, `${SHOP_SLUG} lists an available product`).not.toBeNull()
      const createRes = await request.post(`${API}/api/v1/orders`, {
        headers: { ...headers, "Idempotency-Key": crypto.randomUUID() },
        data: {
          shopId,
          customerName: "G-37-6 void-dialog fixture",
          customerEmail: `g376-void-${Date.now()}@example.test`,
          fulfilmentType: "COLLECTION",
          items: [{ productId: (product as { id: string }).id, quantity: 1 }],
          submit: true,
        },
      })
      expect(createRes.status(), "POST /api/v1/orders (submit: true) creates a PENDING order").toBe(201)
      const createdOrder = (await createRes.json()) as OrderRow & { status?: string }
      const confirmRes = await request.post(`${API}/api/v1/orders/${createdOrder.id}/confirm`, { headers })
      expect(confirmRes.status(), "POST /api/v1/orders/{id}/confirm (PENDING -> CONFIRMED)").toBe(200)
      target = { id: createdOrder.id, orderNumber: createdOrder.orderNumber ?? null, voidedAt: null }
      source = "fixture order placed and confirmed as the vendor (no voidable order existed)"
    }
    expect(target, "VOID: no voidable order (CONFIRMED or later, not voided) was found; nothing was proven").not.toBeNull()
    const order = target as OrderRow
    annotate(testInfo, "void-target", { source, orderNumber: order.orderNumber ?? null })

    let label = order.orderNumber || order.id.substring(0, 8)
    for (const width of VOID_WIDTHS) {
      await page.setViewportSize({ width, height: 900 })
      await page.goto(`/dashboard/orders/${order.id}`, { waitUntil: "domcontentloaded" })
      const trigger = page.getByRole("button", { name: "Void order", exact: true })
      await expect(trigger, "a Group admin is offered Void order on a voidable order").toBeVisible({ timeout: 20_000 })
      await trigger.click()
      let dialog = page.getByRole("dialog", { name: `Void order ${label}?` })
      if (!order.orderNumber && (await dialog.count()) === 0) {
        // The fallback path knows only the id: read the label the dialog itself shows.
        const titleText = (await page.getByRole("dialog").locator("h2").textContent()) || ""
        label = titleText.replace(/^Void order /, "").replace(/\?$/, "")
        dialog = page.getByRole("dialog", { name: `Void order ${label}?` })
      }
      await expect(dialog).toBeVisible({ timeout: 10_000 })
      const title = `Void order ${label}?`
      await animationsDone(page, title)
      const result = await measureDialog(page, title)
      annotate(testInfo, `void-dialog-${width}`, result)
      expect.soft(result.failures, `G-37-6 item 4 at ${width}px: the void dialog fits`).toEqual([])
      expect.soft(result.soft, `G-37-6 item 4 at ${width}px: the title clears the Close control`).toEqual([])

      if (width === 390) {
        // INSTRUMENT ARM at 390: a 600 px minimum width must be caught.
        const forced = await page.addStyleTag({ content: '[role="dialog"] { min-width: 600px !important; }' })
        await settle(page)
        const armed = await measureDialog(page, title)
        await forced.evaluate((n) => (n as Element).remove())
        await settle(page)
        expect(armed.failures.length, "the dialog checker must catch a 600 px dialog at 390").toBeGreaterThan(0)
        annotate(testInfo, "arm-void-dialog-390", armed.failures.slice(0, 6))
        // Second ARM at 390: the 600 px arm is caught by the viewport check, so the title check's
        // own fail direction is forced separately — an unwrapped title must run past the content box.
        const nowrap = await page.addStyleTag({ content: '[role="dialog"] h2 { white-space: nowrap !important; }' })
        await settle(page)
        const armedTitle = await measureDialog(page, title)
        await nowrap.evaluate((n) => (n as Element).remove())
        await settle(page)
        expect(
          armedTitle.failures.some((f) => f.startsWith("the title text leaves the content box")),
          "the title check must catch an unwrapped title at 390"
        ).toBe(true)
        annotate(testInfo, "arm-void-title-390", armedTitle.failures.slice(0, 6))
      }

      await dialog.getByRole("button", { name: "Keep order", exact: true }).click()
      await expect(page.getByRole("dialog", { name: title }), "Keep order closes the dialog").toHaveCount(0)
    }
    expect(voidPosts, "no void request was ever sent").toEqual([])
  })

  test("G-37-6 defect 2 (TypeScript): the checkout panel names Peckham Jollof Co. with one full stop", async ({
    browser,
    request,
    baseURL,
  }, testInfo) => {
    test.setTimeout(180_000)
    const shopRes = await request.get(`${API}/api/v1/public/shops/${SHOP_SLUG}`)
    expect(shopRes.status(), `GET /api/v1/public/shops/${SHOP_SLUG}`).toBe(200)
    const name = String(((await shopRes.json()) as { name?: unknown }).name ?? "")
    expect(name.endsWith("."), `VOID: the shop name "${name}" does not end in a full stop; nothing would be proven`).toBe(true)
    annotate(testInfo, "shop-name", name)

    const menuRes = await request.get(`${API}/api/v1/public/shops/${SHOP_SLUG}/products`)
    expect(menuRes.status(), "the public menu").toBe(200)
    type MenuProduct = { id: string; title: string; allergenMask?: number | null; available?: boolean | null }
    const menu = (await menuRes.json()) as Record<string, MenuProduct[]>
    const declaring = Object.values(menu)
      .flat()
      .filter((p) => typeof p.allergenMask === "number" && p.allergenMask > 0 && p.available !== false)
    expect(declaring.length, "VOID: the shop has no product declaring an allergen, so the intro cannot render").toBeGreaterThan(0)

    const context = await browser.newContext({ baseURL })
    const orderPosts: string[] = []
    context.on("request", (r: Request) => {
      if (r.method() === "POST" && new URL(r.url()).pathname.endsWith(`/public/shops/${SHOP_SLUG}/orders`)) {
        orderPosts.push(new URL(r.url()).pathname)
      }
    })
    const page = await context.newPage()
    const cartKey = `${CART_KEY_PREFIX}${SHOP_SLUG}`
    try {
      await page.goto(`/shop/${SHOP_SLUG}`, { waitUntil: "domcontentloaded" })
      // One declaring product through its own Add button (bounded). One product keeps the basket's
      // allergen set fully declared, so the panel renders its intro rather than "not recorded".
      let added: MenuProduct | null = null
      for (const product of declaring.slice(0, MAX_BASKET_ADDS)) {
        const add = page.getByRole("button", { name: `Add ${product.title} to basket`, exact: true }).first()
        try {
          await add.waitFor({ state: "visible", timeout: 10_000 })
        } catch {
          continue
        }
        if (!(await add.isEnabled())) continue
        await add.click()
        await page.waitForTimeout(400)
        const raw = await page.evaluate((key) => window.localStorage.getItem(key), cartKey)
        const ids = raw ? ((JSON.parse(raw) as { items?: { productId?: string }[] }).items ?? []).map((i) => i.productId) : []
        if (ids.includes(product.id)) {
          added = product
          break
        }
      }
      expect(added, "VOID: no declaring product reached the basket through its Add button").not.toBeNull()
      annotate(testInfo, "basket-product", (added as MenuProduct).title)

      await page.goto(`/shop/${SHOP_SLUG}/checkout`, { waitUntil: "domcontentloaded" })
      const panel = page.getByTestId("order-allergen-panel")
      await expect(panel).toBeVisible({ timeout: 20_000 })
      const intro = panel.locator("p", { hasText: "These items are prepared by" }).first()
      await expect(intro, "the panel's declared-allergens intro renders").toBeVisible({ timeout: 15_000 })
      const introText = ((await intro.textContent()) || "").trim()
      annotate(testInfo, "checkout-intro", introText)
      expect
        .soft(introText, "G-37-6 defect 2 (TypeScript): one full stop after the shop name")
        .toBe(`These items are prepared by ${name} Based on what the kitchen has declared, this order contains:`)
      expect.soft(doubleFullStops(await panel.innerText()), "no double full stop in the panel").toEqual([])

      // INSTRUMENT ARM: the checker sees a planted double full stop.
      const planted = doubleFullStops("These items are prepared by Peckham Jollof Co.. Based on what")
      expect(planted.length, "the double-full-stop checker must report a planted 'Co..'").toBeGreaterThan(0)
      annotate(testInfo, "arm-checkout-copy", planted)

      expect(orderPosts, "no order was placed").toEqual([])
    } finally {
      try {
        await page.evaluate((key) => window.localStorage.removeItem(key), cartKey)
        const left = await page.evaluate((key) => window.localStorage.getItem(key), cartKey)
        annotate(testInfo, "basket-cleanup", left === null ? "basket key removed" : "FAILED: basket key still present")
      } catch (error) {
        annotate(testInfo, "basket-cleanup", `FAILED: ${String(error)}`)
      }
      await context.close()
    }
  })

  test("G-37-6 defect 2 (Java): the invitation email names Peckham Jollof Co. with one full stop", async ({
    page,
    request,
  }, testInfo) => {
    test.setTimeout(180_000)
    const shopRes = await request.get(`${API}/api/v1/public/shops/${SHOP_SLUG}`)
    expect(shopRes.status(), `GET /api/v1/public/shops/${SHOP_SLUG}`).toBe(200)
    const name = String(((await shopRes.json()) as { name?: unknown }).name ?? "")
    expect(name.endsWith("."), `VOID: the shop name "${name}" does not end in a full stop; nothing would be proven`).toBe(true)

    await page.setViewportSize({ width: 1440, height: 900 })
    await vendorLogin(page)
    await page.goto("/dashboard/staff", { waitUntil: "domcontentloaded" })
    const inviteCard = page.getByRole("region", { name: "Invite someone" })
    await expect(inviteCard).toBeVisible({ timeout: 20_000 })

    const address = `g376-${Date.now()}@example.test`
    const pending = page.getByRole("region", { name: "Pending invitations" })
    const row = pending.locator("tr[data-status]").filter({ hasText: address })
    try {
      await inviteCard.getByLabel("Email").fill(address)
      await inviteCard.getByLabel("Role").selectOption("STAFF")
      await inviteCard.getByLabel("Shop").selectOption({ label: name })
      await inviteCard.getByRole("button", { name: "Send invitation" }).click()
      await expect(toasts(page).getByText(`Invitation sent to ${address}.`, { exact: false })).toBeVisible({
        timeout: 15_000,
      })
      await expect(row, `one Pending row for ${address}`).toHaveCount(1, { timeout: 15_000 })

      const messages = await messagesFor(request, address)
      expect(messages.length, `no invitation email reached Mailhog for ${address}`).toBeGreaterThan(0)
      const text = messageText(messages[0])
      annotate(testInfo, "invite-subject", messages[0].Content?.Headers?.Subject?.[0] ?? "(no subject header)")
      const line = text.split(/\r?\n/).find((l) => l.includes(" for ")) ?? ""
      annotate(testInfo, "invite-line", line)
      expect
        .soft(new RegExp(`for ${escapeRegExp(name)}\\r?\\n`).test(text), `G-37-6 defect 2 (Java): "for ${name}" then a line break`)
        .toBe(true)
      expect.soft(doubleFullStops(text), "no double full stop in the invitation").toEqual([])

      // INSTRUMENT ARM: the checker sees a planted double full stop before a line break.
      const planted = doubleFullStops("has invited you to help run it on J'Toye as Staff for Peckham Jollof Co..\nAccept")
      expect(planted.length, "the double-full-stop checker must report a planted 'Co..'").toBeGreaterThan(0)
      annotate(testInfo, "arm-invite-copy", planted)

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
