/**
 * THE HORIZONTAL LAYOUT CONTRACT, MEASURED IN A REAL BROWSER.
 *
 * Everything else phase 35 built is a DECLARATION — a number in
 * `lib/layout-widths.ts`, a utility in the generated stylesheet, an attribute in
 * the markup, a jsdom assertion that the attribute is present. None of those can
 * tell you how wide the band actually RENDERS. `getComputedStyle(el).maxWidth`
 * will cheerfully report `1700px` on an element whose parent has squeezed it to
 * 400, so a declaration alone proves nothing about the page a vendor sees. This
 * file is the only instrument in the tree that measures the rendered band.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * COVERAGE SPLIT — READ THIS BEFORE CITING THIS FILE AS EVIDENCE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * The per-PR browser gate is the "Frontend E2E (public surfaces)" job in
 * `.github/workflows/ci-cd.yaml`. It is the ONLY browser job triggered by both
 * `push` and `pull_request`, and it runs a fixed spec list. This file's
 * `@stack-free` describes are wired into it; its dashboard describes are not,
 * because they need a Keycloak login and a live stack.
 *
 *   Marketing tier   `/`, `/for-operators`, `/business-model-guide`,
 *                    `/legal/privacy`                     PER-PR, BLOCKING
 *   Shell tier       `/dashboard`                         no executing lane
 *   Index tier       `/dashboard/orders`, `/products`     no executing lane
 *   Detail tier      `/dashboard/onboarding`              no executing lane
 *
 * The three dashboard tiers' only instrument is the nightly full-suite lane, and
 * **issue #683 is OPEN: that lane is currently DARK.** So the honest statement,
 * and the one CONTEXT.md section 5 fixes as authoritative, is that those three
 * tiers are
 *
 *     "covered by a spec that no current tree executes"
 *
 * — NOT "covered nightly". The difference is not pedantry. "Covered nightly"
 * tells the next reader an instrument is watching this tier and they will act on
 * that; the true state is that the assertion exists, is correct, and runs
 * nowhere. This phase's own rule is that an unstated coverage boundary reads as
 * covered, and an OVERSTATED boundary is the same defect with a confident face
 * on it. Same root cause as #686, whose skip-budget gate is wired only into that
 * same dark lane.
 *
 * Their per-PR substitutes are the static contract gate (plan 35-10) and the
 * jsdom declaration assertions in plans 35-03 through 35-05. Neither proves the
 * band RENDERS at the right width, which is the whole point of this file.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE ARITHMETIC MODEL, AND WHY A CONSTANT ASSERTION IS WRONG
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *     band = min(parent CONTENT box, TIER)
 *
 * A tier is a CEILING, never a target. Measured on this tree at three viewports
 * (the numbers each assertion below reproduces):
 *
 *   Shell   1440  main content 1184 -> band 1184   fluid, no cap binds
 *   Shell   1920  main content 1664 -> band 1664   STILL fluid — see below
 *   Shell   2560  main content 2304 -> band 1700   capped
 *   Index   2560  shell content 1636 -> band 1636  uncapped, fills its parent
 *   Detail  1920  shell content 1600 -> band 1100  capped
 *
 * At 1920 the dashboard's `main` offers 1664px (viewport minus the 256px
 * sidebar) and the 1700px shell cap NEVER BINDS. An assertion written as
 * `expect(band).toBe(SHELL_MAX_PX)` therefore REDS AT 1920 ON A PERFECTLY
 * CORRECT BUILD. Every comparison in this file is the min model.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE DENOMINATOR IS THE PARENT'S CONTENT BOX, NEVER ITS `clientWidth`
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * A child's border-box width resolves against its parent's CONTENT box.
 * `clientWidth` returns the parent's PADDING box — content plus left and right
 * padding. The dashboard shell band carries `p-4 sm:p-8`, i.e. 64px of
 * horizontal padding at every viewport at or above `sm`, so at 2560 the shell
 * band's own width is 1700, its `clientWidth` is 1700, and the Index child
 * inside it renders at 1700 − 64 = **1636**. Comparing that child to its
 * parent's raw `clientWidth` fails by exactly the padding — 64px — at 1440, 1920
 * and 2560 alike, on a build doing precisely what the contract asks. A gate that
 * reds on correct code is deleted within a week, taking the real assertion with
 * it.
 *
 * This is RESEARCH.md assumption A4 coming due, and A4's own stated mitigation
 * is carried into the assertions rather than left in the assumptions log: EVERY
 * width failure message prints all four numbers — the parent's raw
 * `clientWidth`, its resolved content box, its horizontal padding, and the
 * measured band. Without all four a 64px miss is indistinguishable from a
 * genuine 64px layout defect, and the next reader "fixes" the product to satisfy
 * the test. Control ARM E in plan 35-08 proved the padding-inclusive form reds
 * by exactly 64px at 2560; it is the only arm that can distinguish the two
 * denominators, because moving a declared value and watching the measurement
 * follow passes identically under either.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * VACUITY, AND SCOPE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * A band selector that matches nothing measures 0, and `0 <= 1700` is true, so a
 * naive width assertion PASSES over a page that failed to render.
 * `e2e/public-layout.spec.ts` documents the identical failure over a table.
 * `measureBands` therefore returns `null` — never zeros — when nothing matched,
 * and every test asserts non-null and a positive parent content box BEFORE any
 * width comparison.
 *
 * Scope is the second half of the same problem and it has already fired twice in
 * this phase. A document-wide `[data-width-tier="marketing"]` query on a public
 * route is satisfied by the header and footer rails ALONE: with the `/legal/*`
 * policy band fully reverted, the document-wide query still passed (ORCH-06 arm
 * A), and plan 35-07's header assertion was satisfied by the footer. So every
 * query here is SCOPED — `main`, `banner`, `contentinfo` — and the per-scope
 * counts are asserted, which is what makes a partial migration red here rather
 * than only in a jsdom count assertion.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * VIEWPORTS, TAGS AND THE SKIP BUDGET
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * `playwright.config.ts` declares exactly two projects — mobile at 390 and
 * desktop at 1440. NOTHING in that config produces 1920 or 2560, so a describe
 * that does not pin its own viewport measures the project's viewport while
 * claiming to measure another. Every describe below pins with `test.use`;
 * precedent and reasoning at `e2e/dashboard-mobile.spec.ts:375-380`.
 *
 * Every describe is tagged `@desktop-only` so the mobile project's `grepInvert`
 * never ENUMERATES it. A skip must mean "nobody checked this"; it must not also
 * mean "not applicable here", which is the config's own stated rule. Nothing
 * here is a mobile arm on purpose: the caps are inert below their own values
 * (proved by plan 35-01 as a property of the emitted CSS), and the 390px and
 * 375px overflow guards already exist in `public-layout.spec.ts` and
 * `dashboard-mobile.spec.ts`. A second copy would be the drift, not the fix.
 *
 * The dashboard describes skip via `skipWithoutVendorPassword()` when no
 * credential is supplied — `e2e/vendor-credentials.ts` defaults it to the empty
 * string DELIBERATELY, so an unsupplied password is a visible skip rather than a
 * silent 21-second auth failure misread as a product defect. That makes the
 * skip budget (`scripts/gates/e2e-skip-budget.conf`, MAX_SKIPS 6) a PRECONDITION
 * of running this file, not a consequence of it. The precondition is satisfied
 * rather than declared away: the only lane that runs the whole suite,
 * `e2e-nightly.yml`, exports `E2E_VENDOR_PASSWORD` from `KC_SEED_USER_PASSWORD`
 * before invoking Playwright, so these describes EXECUTE there and contribute
 * zero skips. Locally: `set -a; . ./.env; set +a` before running.
 *
 * No `PLAYWRIGHT_BASE_URL` constant is declared here and every navigation is
 * relative. `playwright.config.ts` is the only base-URL authority
 * (`scripts/check-e2e-baseurl-contract.sh`, #505); declaring no fallback at all
 * cannot drift from it.
 */
import { test, expect, type Page } from "@playwright/test"
import { stubPublicApi } from "./helpers/public-surface"
import {
  VENDOR_USERNAME,
  VENDOR_PASSWORD,
  skipWithoutVendorPassword,
} from "./vendor-credentials"
import {
  SHELL_MAX_PX,
  DETAIL_MAX_PX,
  MARKETING_MAX_PX,
  type WidthTier,
} from "../lib/layout-widths"

/**
 * One measured band. Four of these fields exist purely so a failure can be read:
 * `parentClientWidth`, `parentContentWidth`, `parentPaddingX` and `band` are all
 * printed on every width failure (RESEARCH A4's mitigation).
 */
interface BandMeasurement {
  /** Index within the scoped match set, so a message can name WHICH band. */
  ordinal: number
  tag: string
  /** Truncated — enough to identify the element, short enough to read. */
  className: string
  /** The band's own border-box width, from `getBoundingClientRect`. */
  band: number
  /** `getComputedStyle(el).maxWidth` — the DECLARATION, asserted by name. */
  maxWidth: string
  parentTag: string
  /** The parent's PADDING box. Printed for contrast; never the denominator. */
  parentClientWidth: number
  parentPaddingX: number
  /** THE DENOMINATOR: `clientWidth − paddingLeft − paddingRight`. */
  parentContentWidth: number
  /**
   * WHICH LANDMARK the band sits in, resolved by ancestry rather than by
   * document order.
   *
   * This is the scope fix, and it is not cosmetic. `document.querySelector
   * ("footer")` returns the FIRST footer in the document, and on
   * `/business-model-guide` and `/competitive` that is the PAGE'S OWN footer,
   * which lives INSIDE `<main>` — so a rail assertion written that way would be
   * satisfied by a content band, and the counts would only add up by
   * coincidence. `closest()` cannot make that mistake: `main` wins whenever the
   * element is inside it, so a nested `<header>`/`<footer>` is content, and only
   * a genuinely top-level rail is a rail.
   *
   * `stray` is a band in none of the three. It must never occur, and asserting
   * that is what stops a band drifting out of every scope this file checks.
   */
  region: "main" | "header" | "footer" | "stray"
}

interface ScopedMeasurement {
  /** False when the scope selector itself matched nothing. */
  scopeFound: boolean
  /** Whether the route rendered a `<main>` landmark at all. */
  mainPresent: boolean
  /** Matches BEFORE the hidden-ancestor filter, so filtering is never silent. */
  rawCount: number
  bands: BandMeasurement[]
}

const TIER_SELECTOR = (tier: WidthTier) => `[data-width-tier="${tier}"]`

/**
 * Measure every element declaring `tier`, optionally scoped to `scopeSelector`.
 *
 * PLURAL BY DESIGN. A single-element helper is what makes the partial-migration
 * blind spot possible: after plans 35-06 and 35-07 the landing route carries six
 * marketing-tier elements, and a helper that measured "the band" singular would
 * pass while five of them sat on the old, narrower token. Returning an array
 * makes the count assertion free.
 *
 * NEVER RETURNS ZEROS FOR A MISSING ELEMENT. `bands` is empty and the caller
 * VOIDs; a fabricated `{band: 0}` would satisfy `0 <= TIER` and pass.
 *
 * The hidden-ancestor filter defeats React's streaming staging buffer, which
 * briefly holds a SECOND `hidden` copy of the server-rendered tree and would
 * otherwise double every count (#556, #593). `rawCount` is returned alongside so
 * the filter can never remove a real element without leaving a trace.
 */
async function measureBands(
  page: Page,
  tier: WidthTier,
  scopeSelector?: string,
): Promise<ScopedMeasurement> {
  return page.evaluate(
    ({ sel, scope }) => {
      const mainPresent = document.querySelector("main") !== null
      const root: ParentNode | null = scope ? document.querySelector(scope) : document
      if (!root) return { scopeFound: false, mainPresent, rawCount: 0, bands: [] }

      const all = Array.from(root.querySelectorAll<HTMLElement>(sel))
      const visible = all.filter((el) => el.closest("[hidden]") === null)

      return {
        scopeFound: true,
        mainPresent,
        rawCount: all.length,
        bands: visible.map((el, ordinal) => {
          const parent = el.parentElement
          const pcs = parent ? getComputedStyle(parent) : null
          const padLeft = pcs ? parseFloat(pcs.paddingLeft) || 0 : 0
          const padRight = pcs ? parseFloat(pcs.paddingRight) || 0 : 0
          const region: "main" | "header" | "footer" | "stray" =
            el.closest("main") !== null
              ? "main"
              : el.closest("header") !== null
                ? "header"
                : el.closest("footer") !== null
                  ? "footer"
                  : "stray"
          return {
            ordinal,
            tag: el.tagName.toLowerCase(),
            className: String(el.className).slice(0, 90),
            band: Math.round(el.getBoundingClientRect().width * 100) / 100,
            maxWidth: getComputedStyle(el).maxWidth,
            parentTag: parent ? parent.tagName.toLowerCase() : "(none)",
            parentClientWidth: parent ? parent.clientWidth : 0,
            parentPaddingX: padLeft + padRight,
            // THE DENOMINATOR. Computed ONCE, here, so no individual assertion
            // can pick the padding box by accident.
            parentContentWidth: parent ? parent.clientWidth - padLeft - padRight : 0,
            region,
          }
        }),
      }
    },
    { sel: TIER_SELECTOR(tier), scope: scopeSelector ?? null },
  )
}

/** All four numbers, every time. Without them a 64px miss reads as a layout bug. */
function explain(m: BandMeasurement): string {
  return (
    `band=${m.band} ` +
    `parentContentWidth=${m.parentContentWidth} ` +
    `parentClientWidth=${m.parentClientWidth} ` +
    `parentPaddingX=${m.parentPaddingX} ` +
    `[<${m.tag} class="${m.className}"> inside <${m.parentTag}>, ` +
    `computed max-width=${m.maxWidth}]`
  )
}

/**
 * THE NON-VACUITY GATE. Called before every width comparison, without exception.
 *
 * Three distinct failures it converts from a silent pass into a red: the scope
 * selector matched nothing; the tier selector matched nothing (0 <= TIER is
 * true); the parent has zero content width (every band trivially fits).
 */
function assertMeasurable(
  result: ScopedMeasurement,
  where: string,
  tier: WidthTier,
  scopeSelector?: string,
): BandMeasurement[] {
  expect(
    result.scopeFound,
    `${where}: the scope selector "${scopeSelector}" matched no element, so ` +
      `nothing below was measured. This VOIDs the test rather than passing it.`,
  ).toBe(true)

  expect(
    result.bands.length,
    `${where}: no element matched ${TIER_SELECTOR(tier)}` +
      (scopeSelector ? ` within "${scopeSelector}"` : "") +
      `. A missing band measures 0 and 0 <= every tier, so this MUST fail ` +
      `rather than pass. (raw matches before the hidden-ancestor filter: ` +
      `${result.rawCount})`,
  ).toBeGreaterThan(0)

  for (const m of result.bands) {
    expect(
      m.parentContentWidth,
      `${where}: parent content box is not positive — every comparison below ` +
        `would be trivially satisfied. ${explain(m)}`,
    ).toBeGreaterThan(0)
  }

  return result.bands
}

/**
 * `band === min(parent CONTENT box, tierPx)`, within a pixel.
 *
 * The tolerance is one pixel because sub-pixel layout rounding is real and a
 * scrollbar-free viewport still resolves fractional widths; it is NOT slack for
 * a wrong denominator, which misses by 64.
 */
function expectBandMatchesTier(
  m: BandMeasurement,
  tierPx: number,
  tierName: string,
  where: string,
): void {
  const expected = Math.min(m.parentContentWidth, tierPx)
  const delta = Math.abs(m.band - expected)
  expect(
    delta,
    `${where} band #${m.ordinal}: expected min(parentContentWidth, ` +
      `${tierName}=${tierPx}) = ${expected}, measured ${m.band} ` +
      `(off by ${delta}). ${explain(m)}` +
      (delta === m.parentPaddingX && m.parentPaddingX > 0
        ? ` — NOTE: the miss equals the parent's horizontal padding exactly, ` +
          `which is the signature of comparing against clientWidth instead of ` +
          `the content box, NOT a layout defect.`
        : ""),
  ).toBeLessThanOrEqual(1)
}

/** The declaration, asserted BY NAME so it cannot be inferred from an absence. */
function expectDeclaredMaxWidth(
  m: BandMeasurement,
  expectedCss: string,
  tierName: string,
  where: string,
): void {
  expect(
    m.maxWidth,
    `${where} band #${m.ordinal}: the ${tierName} tier must declare a computed ` +
      `max-width of "${expectedCss}", found "${m.maxWidth}". ${explain(m)}`,
  ).toBe(expectedCss)
}

// ── Public routes ────────────────────────────────────────────────────────────

/**
 * Per-route marketing-band expectations, SCOPED so a rail cannot satisfy a
 * content assertion.
 *
 * `mainMin`/`mainMax` differ on `/` alone, and the reason is measured rather
 * than hedged: the "kitchen row" band is rendered `{shops.length > 0 && …}` from
 * a SERVER fetch (`app/page.tsx`, #544). On the per-PR gate there is no backend,
 * that fetch fails, the row is absent and `main` carries 3 bands; against a live
 * stack it answers and `main` carries 4. Both are correct pages. Every other
 * route here is unconditional and its count is exact — measured in both
 * configurations (a live-stack server and a dead-backend server built from the
 * same artefact) during plan 35-08.
 */
const MARKETING_ROUTES: {
  path: string
  mainMin: number
  mainMax: number
  note?: string
}[] = [
  {
    path: "/",
    mainMin: 3,
    mainMax: 4,
    note: "the 4th band is the server-data-conditional kitchen row (#544)",
  },
  { path: "/for-operators", mainMin: 3, mainMax: 3 },
  { path: "/business-model-guide", mainMin: 4, mainMax: 4 },
  // ORCH-06's route. It carries exactly ONE content band, which is what makes it
  // the sharpest scope control in this file: revert `components/legal/policy-
  // page.tsx` and the `main`-scoped count drops to 0 and this reds, while a
  // document-wide query would still find the two rails and pass.
  { path: "/legal/privacy", mainMin: 1, mainMax: 1 },
]

async function openPublicRoute(page: Page, path: string): Promise<void> {
  await page.goto(path)
  await page.waitForLoadState("domcontentloaded")
  // Outlast React's streaming staging buffer (`<div id="S:n" hidden>`), whose
  // duplicate copy of the server-rendered tree is briefly in the DOM. The
  // hidden-ancestor filter in `measureBands` is the belt; this is the braces.
  await page.waitForTimeout(1200)
  // WAIT FOR THE MARKER, never a fixed delay alone: a measurement taken before
  // the band exists finds nothing, and the non-vacuity gate would then convert a
  // RACE into a red that reads as a missing declaration.
  await page.waitForSelector(TIER_SELECTOR("marketing"), { timeout: 15_000 })
}

const MARKETING_CSS = `${MARKETING_MAX_PX}px`

function marketingDescribe(viewportWidth: number, viewportHeight: number): void {
  test.describe(`@desktop-only @stack-free Marketing tier @ ${viewportWidth}px`, () => {
    // TRAP: nothing in playwright.config.ts produces this viewport. Without this
    // line the describe measures 1440 while claiming to measure the width in its
    // own title.
    test.use({ viewport: { width: viewportWidth, height: viewportHeight } })

    test.beforeEach(async ({ context }) => {
      // Keeps these describes stack-free, which is what lets them run on every
      // pull request. The moment they need a backend they leave the gate.
      await stubPublicApi(context)
    })

    for (const route of MARKETING_ROUTES) {
      test(`${route.path} — every marketing band is min(parent content, MARKETING_MAX_PX)`, async ({
        page,
      }) => {
        await openPublicRoute(page, route.path)
        const where = `${route.path} @ ${viewportWidth}px`

        const all = await measureBands(page, "marketing")
        const allBands = assertMeasurable(all, `${where} [document]`, "marketing")
        expect(
          all.mainPresent,
          `${where}: the route rendered no <main> landmark, so the content ` +
            `assertions below would have nothing to be scoped to`,
        ).toBe(true)

        // ── SCOPE FIRST, WIDTH SECOND. A document-wide count here is satisfied
        // by the header and footer rails ALONE — ORCH-06 arm A measured exactly
        // that, with the `/legal/*` policy band fully reverted — so the content
        // question has to be asked of `main` specifically or it is not being
        // asked at all. Partitioned by ANCESTRY, never by document order: see
        // the `region` field's own note for the `/business-model-guide` case
        // that defeats `document.querySelector("footer")`.
        const mainBands = allBands.filter((m) => m.region === "main")
        const headerBands = allBands.filter((m) => m.region === "header")
        const footerBands = allBands.filter((m) => m.region === "footer")
        const strays = allBands.filter((m) => m.region === "stray")

        expect(
          mainBands.length,
          `${where}: expected ${route.mainMin}..${route.mainMax} marketing bands ` +
            `inside <main>, found ${mainBands.length} (document-wide: ` +
            `${allBands.length}). A PARTIAL migration — one band still on the old ` +
            `token — lands here, and the document-wide count would NOT have caught ` +
            `it. If a band was deliberately added or removed, update ` +
            `MARKETING_ROUTES.` + (route.note ? ` (${route.note})` : ""),
        ).toBeGreaterThanOrEqual(route.mainMin)
        expect(
          mainBands.length,
          `${where}: marketing bands inside <main> (document-wide: ${allBands.length})`,
        ).toBeLessThanOrEqual(route.mainMax)

        // The two rails, asserted SEPARATELY. Plan 35-07's header assertion was
        // satisfied by the FOOTER, so "at least one of them exists" is not the
        // question being asked here.
        expect(
          headerBands.length,
          `${where}: the top-level header rail declares no marketing band`,
        ).toBeGreaterThanOrEqual(1)
        expect(
          footerBands.length,
          `${where}: the top-level footer rail declares no marketing band`,
        ).toBeGreaterThanOrEqual(1)
        expect(
          strays.map(explain),
          `${where}: a marketing band sits outside <main>, the header rail and ` +
            `the footer rail, so no scoped assertion in this file covers it`,
        ).toEqual([])

        // ── THE WIDTHS. Every match on the route, not the first.
        for (const m of allBands) {
          expectBandMatchesTier(m, MARKETING_MAX_PX, "MARKETING_MAX_PX", where)
          expectDeclaredMaxWidth(m, MARKETING_CSS, "Marketing", where)
        }
      })
    }
  })
}

marketingDescribe(1440, 900)
marketingDescribe(1920, 1080)
marketingDescribe(2560, 1440)

// ── Dashboard routes ─────────────────────────────────────────────────────────

/**
 * Mirrors `dashboard-mobile.spec.ts`'s `vendorLogin`, duplicated rather than
 * imported: importing another `*.spec.ts` executes its module body and
 * re-registers every describe in it (see `helpers/public-surface.ts`'s header
 * for why that anti-pattern is named rather than repeated).
 */
async function vendorLogin(page: Page): Promise<void> {
  skipWithoutVendorPassword()
  await page.goto("/auth/signin", { waitUntil: "domcontentloaded" })

  const emailInput = page.locator('input[name="email"], input[type="email"]').first()
  if ((await emailInput.count()) > 0) {
    await emailInput.fill(VENDOR_USERNAME)
    await page
      .locator('input[name="password"], input[type="password"]')
      .first()
      .fill(VENDOR_PASSWORD)
    await page.locator('button[type="submit"]').first().click()
    await page.waitForURL(/\/dashboard/, { timeout: 15_000 })
    return
  }

  const ssoButton = page.getByRole("button", { name: /sign in with keycloak/i })
  if ((await ssoButton.count()) === 0) {
    test.skip(true, "No sign-in method found on /auth/signin — unknown auth flow")
  }
  await ssoButton.waitFor({ state: "visible", timeout: 10_000 })
  // Let React hydrate before clicking — a click on `domcontentloaded` can land
  // before the onClick handler is attached and silently no-op.
  await page.waitForLoadState("networkidle").catch(() => {})
  await page.waitForTimeout(400)
  await ssoButton.click()

  try {
    await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 20_000 })
  } catch {
    if (page.url().includes("/auth/signin")) {
      await ssoButton.click({ force: true }).catch(() => {})
    }
    await page.waitForURL(/(openid-connect|\/dashboard)/, { timeout: 20_000 })
  }
  if (!page.url().includes("/dashboard")) {
    await page.fill("#username", VENDOR_USERNAME)
    await page.fill("#password", VENDOR_PASSWORD)
    await page.click("#kc-login")
  }
  await page.waitForURL(/\/dashboard/, { timeout: 20_000 })
}

/**
 * Open a dashboard route and WAIT FOR THE TIER MARKER before returning.
 *
 * Load-bearing, not defensive. `app/dashboard/page.tsx`, `orders/page.tsx`,
 * `products/page.tsx`, `customers/page.tsx` and `shops/page.tsx` each render a
 * loading branch — a centred spinner carrying NO tier attribute — while their
 * data resolves. A measurement taken in that window finds no element, and the
 * non-vacuity gate would then convert a TIMING fact about the fetch into a red
 * that reads as a missing declaration. Waiting on the marker keeps the two
 * apart. A `networkidle` or a fixed delay would not: the dashboard holds an SSE
 * stream open, so networkidle never settles (#404).
 */
async function openDashboardRoute(page: Page, path: string, tier: WidthTier): Promise<void> {
  await page.goto(path, { waitUntil: "domcontentloaded" })
  await page.waitForSelector(TIER_SELECTOR(tier), { timeout: 30_000 })
}

const SHELL_SELECTOR = TIER_SELECTOR("shell")
const SHELL_CSS = `${SHELL_MAX_PX}px`
const DETAIL_CSS = `${DETAIL_MAX_PX}px`

const INDEX_ROUTES = ["/dashboard/orders", "/dashboard/products"]
// `/dashboard/onboarding` rather than `/dashboard/orders/[id]`: it needs no
// seeded order id, and all three of its branches (loading, create, loaded) carry
// the Detail tier, so the measurement cannot depend on which one rendered.
const DETAIL_ROUTE = "/dashboard/onboarding"

function dashboardDescribe(viewportWidth: number, viewportHeight: number): void {
  test.describe(`@desktop-only Dashboard tiers @ ${viewportWidth}px`, () => {
    test.use({ viewport: { width: viewportWidth, height: viewportHeight } })

    test.beforeEach(async ({ page }) => {
      await vendorLogin(page)
    })

    test("Shell — the band is min(main content, SHELL_MAX_PX)", async ({ page }) => {
      await openDashboardRoute(page, "/dashboard", "shell")
      const where = `/dashboard shell @ ${viewportWidth}px`

      const result = await measureBands(page, "shell")
      const bands = assertMeasurable(result, where, "shell")
      expect(
        bands.length,
        `${where}: the shell band is the tree's single width call site ` +
          `(components/dashboard/dashboard-shell.tsx) — exactly one is expected`,
      ).toBe(1)

      const shell = bands[0]

      // The min model, and the trap it exists for, recorded AT THE SITE:
      //   1440  main content 1184 -> 1184   fluid
      //   1920  main content 1664 -> 1664   STILL fluid; the 1700 cap does not
      //                                     bind until roughly a 1956px viewport
      //   2560  main content 2304 -> 1700   capped
      // `expect(shell.band).toBe(SHELL_MAX_PX)` would RED at 1440 and 1920 on a
      // perfectly correct build. At 1440 this is also CONTEXT.md's "must not
      // move" case: the dashboard already used all available width there before
      // this phase, and it still does.
      expectBandMatchesTier(shell, SHELL_MAX_PX, "SHELL_MAX_PX", where)

      // The shell band's parent is `main`, which carries no horizontal padding,
      // so here the content box and clientWidth coincide. Assert the content box
      // ANYWAY — one denominator rule for the whole file beats a per-tier
      // judgement call, and this is the assertion that keeps it honest.
      expect(
        shell.parentPaddingX,
        `${where}: <main> is expected to carry no horizontal padding; if that ` +
          `changes, the content box is still the denominator. ${explain(shell)}`,
      ).toBe(0)

      expectDeclaredMaxWidth(shell, SHELL_CSS, "Shell", where)
    })

    test("Index — uncapped BY NAME, and fills the shell's content box", async ({ page }) => {
      for (const route of INDEX_ROUTES) {
        await openDashboardRoute(page, route, "index")
        const where = `${route} index @ ${viewportWidth}px`

        // Scoped INSIDE the shell band. The Index tier's whole claim is about
        // its relationship to its parent, so an unscoped query would not be
        // asking the question.
        const result = await measureBands(page, "index", SHELL_SELECTOR)
        const bands = assertMeasurable(result, where, "index", SHELL_SELECTOR)
        expect(bands.length, `${where}: index bands inside the shell band`).toBe(1)
        const index = bands[0]

        // THE FALSIFIABLE FORM OF "UNCAPPED". Implemented purely as an absence,
        // "uncapped" is a contract no assertion can distinguish from a forgotten
        // cap (ORCH-03). Asserting the computed value BY NAME is what makes it a
        // statement rather than a silence.
        expectDeclaredMaxWidth(index, "none", "Index", where)

        // THE PADDING TRAP, at the one site where it bites hardest. This band's
        // parent IS the shell band, which carries `p-4 sm:p-8` = 64px of
        // horizontal padding at every viewport at or above `sm`. At 2560 the
        // shell band is 1700 and its clientWidth is therefore also 1700, but
        // this child renders at 1700 − 64 = 1636. Comparing to the raw
        // clientWidth reds a CORRECT build by exactly 64px at 1440, 1920 and
        // 2560 alike. Control ARM E in plan 35-08 measured that.
        expect(
          index.parentPaddingX,
          `${where}: the shell band is expected to carry horizontal padding — ` +
            `if it is 0, this test has stopped exercising the padding trap it ` +
            `exists for. ${explain(index)}`,
        ).toBeGreaterThan(0)

        // Uncapped means "equals the parent's content box", which is the min
        // model with an infinite tier. Written as the min model anyway, so
        // every tier in this file reads the same way.
        expectBandMatchesTier(index, Number.POSITIVE_INFINITY, "Index (uncapped)", where)
      }
    })

    test("Detail — capped, and measurably narrower than the shell on the same page", async ({
      page,
    }) => {
      await openDashboardRoute(page, DETAIL_ROUTE, "detail")
      const where = `${DETAIL_ROUTE} detail @ ${viewportWidth}px`

      // BOTH tiers measured on ONE page, so the comparison cannot be confounded
      // by a re-render between two navigations.
      const detailResult = await measureBands(page, "detail")
      const shellResult = await measureBands(page, "shell")
      const detailBands = assertMeasurable(detailResult, where, "detail")
      const shellBands = assertMeasurable(shellResult, where, "shell")
      expect(detailBands.length, `${where}: detail bands on the route`).toBe(1)
      expect(shellBands.length, `${where}: shell bands on the route`).toBe(1)

      const detail = detailBands[0]
      const shell = shellBands[0]

      expectBandMatchesTier(detail, DETAIL_MAX_PX, "DETAIL_MAX_PX", where)
      expectDeclaredMaxWidth(detail, DETAIL_CSS, "Detail", where)

      // The tier ladder itself: a reading surface must not be as wide as the
      // chrome around it. This is the assertion that would catch someone
      // "improving" the detail tier to the shell value.
      expect(
        detail.band,
        `${where}: the Detail band must be narrower than the Shell band around ` +
          `it — widening a form to the chrome width is a regression dressed up ` +
          `as an improvement. detail: ${explain(detail)} | shell: ${explain(shell)}`,
      ).toBeLessThan(shell.band)
    })
  })
}

dashboardDescribe(1440, 900)
dashboardDescribe(1920, 1080)
dashboardDescribe(2560, 1440)
