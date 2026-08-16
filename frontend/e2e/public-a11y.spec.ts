/**
 * Public-surface accessibility conformance — THE PER-PR AXE GATE (LGL-02, D-09, D-13).
 *
 * WHY THIS FILE EXISTS, AND WHY IT LIVES IN THE JOB IT DOES.
 *
 * `frontend-e2e` in ci-cd.yaml is the ONLY browser job that triggers on both
 * `push` and `pull_request`, so it is the only one that can BLOCK a merge.
 * `e2e-nightly.yml` is `schedule` + `workflow_dispatch` only — a gate placed
 * there cannot fail a PR, it can only report after the fact. The job already
 * builds the frontend, starts it and installs chromium, so the marginal cost of
 * this spec is scan time alone.
 *
 * ── THE THING THIS FILE IS MOSTLY ABOUT: A ZERO IS PRESUMED AN ARTEFACT ──
 *
 * "axe reported no violations" and "axe was pointed at a page that never
 * rendered" produce byte-identical output. This project has already paid for
 * that once: a "0 button-name violations" result was meaningless because the
 * tables under test never mounted. Two more reproductions were measured while
 * this gate was being written, and both are now permanent tests rather than
 * anecdotes:
 *
 *   - `/shop/[slug]/checkout` WITH AN EMPTY BASKET renders a stub reading
 *     "Nothing to checkout" — MEASURED on this tree: 8 elements inside `<main>`,
 *     0 `<h1>`, 0 `<form>`, 0 `<input>` — and axe reports **0 violations / 22
 *     passes** over it. Scanning that is not scanning checkout. The basket is
 *     therefore SEEDED before the scan.
 *   - The dish detail panel is a MODAL, not a route. Navigating to `/shop/[slug]`
 *     and scanning reproduces the artefact exactly: `[role="dialog"]` count 0,
 *     and axe reports **0 violations / 23 passes** — a clean bill of health for
 *     a dialog that is not in the DOM. It is therefore OPENED before the scan.
 *
 * Both artefacts report a PERFECT ZERO, which is the entire argument: the
 * output of a scan over nothing is indistinguishable from the output of a scan
 * over a clean page. (RESEARCH recorded one moderate violation on the unseeded
 * checkout; re-measured here it is zero. The markup moved. The number quoted
 * above is the one this plan actually measured, not the one it inherited.)
 *
 * So every scan in this file is guarded, and the guard is STRUCTURAL rather than
 * a convention: `scanSurface()` asserts its controls and only then constructs an
 * `AxeBuilder`. There is no code path through this file that scans a surface
 * without first proving the surface rendered. That is deliberately stronger than
 * "every test remembers to assert a control first" — a reviewer cannot forget a
 * step that does not exist, and a static count of controls-vs-scans would pass
 * vacuously the moment the scan was factored into a helper (as it is here).
 *
 * The one deliberate exception is `INSTRUMENT`, which scans a hand-built broken
 * fixture precisely to prove the scanner can still FAIL. It is retained on every
 * run rather than run once and deleted: half a second per run re-proves that a
 * green result from this file means "clean", not "axe silently stopped working"
 * — the same argument `contrast-tokens.test.ts` makes for its own instrument
 * check. (RESEARCH suggested deleting it after recording; keeping it is a
 * deliberate, recorded departure.)
 *
 * ── SCOPE (D-09) ──
 *
 * The declared surfaces, plus the five `/legal/*` routes this phase authored —
 * a page this phase created should not ship outside the gate it also creates.
 * The AUTHENTICATED VENDOR DASHBOARD IS DELIBERATELY OUT, and the published
 * conformance statement names it as an exception rather than staying silent.
 * Do not add it here without moving that statement too.
 *
 * KEEP IT STACK-FREE. Every surface below is reachable with the fixture stub in
 * `helpers/public-surface.ts`. The moment this needs a backend it stops running
 * in CI and the blind spot comes back.
 */
import { test, expect, type Page } from "@playwright/test"
import AxeBuilder from "@axe-core/playwright"
import {
  stubPublicApi,
  resolveStorefrontPath,
  openStorefront,
} from "./helpers/public-surface"
// The ONE definition of the basket namespace, imported rather than retyped —
// two copies of this string is how a seed silently stops seeding.
import { CART_KEY_PREFIX } from "../lib/cart-identity"

/**
 * WCAG 2.1 level AA, which is exactly what the published statement claims.
 * Tags and claim must move together: scanning a narrower set than we publish
 * would make the gate green while the claim stayed wrong.
 */
const WCAG_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]

/**
 * React's streaming staging buffer (`<div id="S:n" hidden>`) briefly holds a
 * SECOND copy of the server-rendered tree, which would double every landmark
 * count below and make `main === 1` fail for a reason that has nothing to do
 * with accessibility. 1200ms is the settle the sibling layout spec uses.
 */
const SETTLE_MS = 1200

/** A readable, actionable failure: the rule ids, not a bare count. */
function summarise(violations: { id: string; impact?: string | null; help?: string; nodes: { target: unknown[] }[] }[]): string {
  return violations
    .map(
      (v) =>
        `${v.id} [${v.impact ?? "n/a"}] x${v.nodes.length} — ${v.help ?? ""}\n` +
        v.nodes
          .slice(0, 3)
          .map((n) => `      at ${JSON.stringify(n.target)}`)
          .join("\n")
    )
    .join("\n")
}

/**
 * THE ONLY WAY THIS FILE SCANS A REAL SURFACE.
 *
 * Controls first, scan second, and the two are welded together on purpose — see
 * the header. `control` is REQUIRED (it may be a no-op only for surfaces whose
 * universal control is genuinely sufficient, and those pass an explicit one).
 */
async function scanSurface(
  page: Page,
  label: string,
  control: (page: Page) => Promise<void>
): Promise<void> {
  // ── UNIVERSAL NON-VACUITY CONTROL ──
  // Asserted on every surface: a page that failed to render, 404'd, or was
  // replaced by an error boundary has no <main> and no <h1>, and axe reports
  // near-nothing over it. Shape, not yesterday's numbers — the counts move.
  await expect(
    page.locator("main"),
    `${label}: expected exactly one <main> landmark. Without it, everything ` +
      `below is measured over a page that did not render, where axe reports ` +
      `near-zero for the wrong reason`
  ).toHaveCount(1)

  const h1Count = await page.locator("h1").count()
  expect(
    h1Count,
    `${label}: no <h1> — the page did not render its own content`
  ).toBeGreaterThanOrEqual(1)

  // ── PER-SURFACE CONTROL ──
  await control(page)

  // ── ONLY NOW, THE SCAN ──
  const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()

  // Guard the guard: axe must have actually examined something. A rule set that
  // matched no nodes at all would report zero violations and zero passes, which
  // is indistinguishable from a clean page in the assertion below.
  expect(
    results.passes.length + results.violations.length,
    `${label}: axe evaluated no rules at all — the scan itself is void`
  ).toBeGreaterThan(0)

  expect(
    summarise(results.violations),
    `${label}: WCAG 2.1 AA violations (${results.violations.length} rule(s))`
  ).toBe("")
}

/** The universal control is enough here; stated explicitly rather than implied. */
const NO_EXTRA_CONTROL = async () => {}

async function open(page: Page, route: string): Promise<void> {
  await page.goto(route)
  await page.waitForLoadState("domcontentloaded")
  await page.waitForTimeout(SETTLE_MS)
}

/**
 * Static public routes: the landing page, both sign-ins, and the five policy
 * pages LGL-01 published. All server-rendered with no backend dependency.
 */
const SIMPLE_ROUTES = [
  "/",
  "/shop/signin",
  "/auth/signin",
  "/legal",
  "/legal/privacy",
  "/legal/cookies",
  "/legal/retention",
  "/legal/accessibility",
] as const

test.describe("public surfaces — WCAG 2.1 AA", () => {
  test.beforeEach(async ({ context }) => {
    await stubPublicApi(context)
  })

  for (const route of SIMPLE_ROUTES) {
    test(`${route} has no WCAG 2.1 AA violations`, async ({ page }) => {
      await open(page, route)
      await scanSurface(page, route, NO_EXTRA_CONTROL)
    })
  }

  test("/shop has no WCAG 2.1 AA violations", async ({ page }) => {
    await open(page, "/shop")
    await scanSurface(page, "/shop", async (p) => {
      // The listing's whole content IS the cards. Zero cards is the "shop
      // directory listed nothing" state, over which a clean scan means nothing.
      const cards = await p.locator("article:visible").count()
      expect(
        cards,
        "/shop rendered no shop cards — the directory did not load"
      ).toBeGreaterThan(0)
      console.log(`  [control] /shop shop cards = ${cards}`)
    })
  })

  test("a storefront has no WCAG 2.1 AA violations", async ({ page }) => {
    // Resolved at runtime, never a hardcoded slug: the fixture slug 404s the
    // moment a real backend is reachable, and a "Shop not found" page satisfies
    // every invariant a real storefront does.
    const path = await resolveStorefrontPath(page)
    await openStorefront(page, path) // refuses to continue with no dish cards

    await scanSurface(page, `${path} (storefront)`, async (p) => {
      const dishes = await p.locator("article:visible").count()
      expect(dishes, `${path} rendered no dish cards`).toBeGreaterThan(0)
      console.log(`  [control] ${path} dish cards = ${dishes}`)
    })
  })

  /**
   * THE MODAL — NOT A ROUTE.
   *
   * Scanning `/shop/[slug]` without opening this reproduces the paid-for
   * artefact: the dialog is not in the DOM, axe finds nothing wrong with it,
   * and the result reads as "the dish panel is accessible".
   */
  test("the dish detail modal has no WCAG 2.1 AA violations", async ({ page }) => {
    const path = await resolveStorefrontPath(page)
    await openStorefront(page, path)

    // The keyboard-reachable trigger #533 added. Before it, the card was an
    // <article onClick> and the panel could not be opened without a mouse.
    const trigger = page.getByRole("button", { name: /^View details for / }).first()
    await expect(
      trigger,
      "no dish-detail trigger — the modal cannot be opened, so scanning here " +
        "would measure the shop page and call it the modal"
    ).toBeVisible({ timeout: 15_000 })
    await trigger.click()

    await scanSurface(page, `${path} (dish modal)`, async (p) => {
      // THE CONTROL THIS TEST EXISTS FOR.
      const dialogs = await p.locator('[role="dialog"]').count()
      expect(
        dialogs,
        "the dish modal is not open — a scan of the shop page underneath it " +
          "would report cleanly and prove nothing about the modal"
      ).toBe(1)
      console.log(`  [control] dish modal [role="dialog"] count = ${dialogs}`)
    })
  })

  /**
   * CHECKOUT — SEEDED, BECAUSE THE EMPTY STATE IS NOT CHECKOUT.
   *
   * `items.length === 0` renders a "Nothing to checkout" stub — 8 elements, no
   * `<h1>`, no `<form>`, no `<input>` (page.tsx:369-373). The real
   * `<h1>Checkout</h1>` and the entire address/contact form live behind
   * `items.length > 0` (page.tsx:728). Measured on this tree: scanning the
   * unseeded page reports 0 violations over 22 passes — a flawless result for a
   * page containing none of the surface being claimed, and in particular none
   * of the seven autofill tokens and error-announcement wiring 31-14 shipped.
   */
  test("checkout has no WCAG 2.1 AA violations (with a seeded basket)", async ({
    page,
  }) => {
    const path = await resolveStorefrontPath(page)
    const slug = path.split("/").filter(Boolean).pop() as string
    expect(slug, "could not derive a slug from the resolved storefront path").toBeTruthy()

    // Seed BEFORE navigation. The provider hydrates from localStorage on mount,
    // so a write after load would be too late and the page would render empty.
    // The stored shape carries its own slug and owner — a payload missing them
    // is rejected by parseCart() and the basket silently reads as empty, which
    // is the seed failing in exactly the way this test must not tolerate.
    await page.addInitScript(
      ([key, shopSlug]) => {
        window.localStorage.setItem(
          key,
          JSON.stringify({
            shopSlug,
            owner: null,
            items: [
              {
                productId: "p-1",
                title: "Portrait Dish",
                pricePennies: 950,
                quantity: 2,
                imageUrl: null,
                category: "Mains",
              },
            ],
          })
        )
      },
      [`${CART_KEY_PREFIX}${slug}`, slug]
    )

    await open(page, `${path}/checkout`)

    await scanSurface(page, `${path}/checkout`, async (p) => {
      // Both halves fail on the empty stub, and each names a different way the
      // seed can have failed.
      await expect(
        p.getByRole("heading", { level: 1, name: "Checkout" }),
        "checkout rendered its EMPTY state — the basket seed did not take, so " +
          "the form, the address fields and the allergen panel are all absent " +
          "and a clean scan would be measuring a four-element stub"
      ).toBeVisible()

      const lineItems = p.getByText("Portrait Dish")
      const lineCount = await lineItems.count()
      expect(
        lineCount,
        "no order-summary line item — the seeded basket did not reach the page"
      ).toBeGreaterThan(0)
      console.log(`  [control] checkout seeded line items on page = ${lineCount}`)
    })
  })
})

/**
 * THE INSTRUMENT — PROOF THE SCANNER CAN FAIL, ON EVERY RUN.
 *
 * Everything above asserts an ABSENCE. An absence is exactly what a broken,
 * misconfigured or silently no-op scanner also reports. This is the only test
 * in the file that asserts a PRESENCE, and it is what makes the other results
 * evidence rather than assertion.
 *
 * It deliberately does NOT go through `scanSurface()`: the fixture has no
 * `<main>` and no `<h1>` by design, so the universal control would (correctly)
 * reject it. That is the one honest reason to bypass the guard, and it is
 * spelled out here so a future reader does not read it as a loophole.
 */
test.describe("axe instrument", () => {
  test("INSTRUMENT: the scanner reports violations on a deliberately broken fixture", async ({
    page,
  }) => {
    await page.setContent(`
      <!doctype html>
      <html lang="en">
        <head><title>instrument</title></head>
        <body>
          <img src="data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==">
          <button></button>
          <a href="/nowhere"></a>
          <p style="color:#bbbbbb;background:#ffffff">low contrast text</p>
        </body>
      </html>
    `)

    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    const ids = results.violations.map((v) => v.id).sort()
    console.log(`  [instrument] violations: ${ids.join(", ") || "(none)"}`)

    expect(
      results.violations.length,
      "axe reported ZERO violations on a fixture built to violate — the scanner " +
        "is not working, and every clean result in this file is therefore void"
    ).toBeGreaterThan(0)

    // Named rather than merely counted: a non-zero total could come from any
    // rule at all, including one unrelated to the fixture's defects. `image-alt`
    // is unambiguous, is in the tag set above, and is the same rule the break
    // arm for this gate introduces on a real surface.
    expect(ids, "the instrument fixture's missing alt text was not detected").toContain(
      "image-alt"
    )
  })
})
