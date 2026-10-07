/**
 * Storefront dish modal — dialog contract in a real browser (#446, #272 part 2).
 *
 * WHY THIS FILE EXISTS AND THE JEST TEST IS NOT ENOUGH
 *
 * The modal used to be the only hand-rolled overlay in the codebase: two bare
 * `fixed inset-0 z-50` divs with click handlers. Every symptom of that is a
 * RUNTIME behaviour, and jsdom has none of the machinery to show any of them —
 * no layout, no scrollbar, no real focus management. A component test can assert
 * that props were passed; it cannot assert that Escape dismissed anything.
 *
 * Measured on the pre-fix tree at 390px against the live stack, which is what
 * every block below was written to fail against:
 *
 *   role="dialog" count ............ 0
 *   aria-modal="true" count ........ 0
 *   Escape pressed ................. overlay count 2 -> 2 (nothing happened)
 *   body overflow while open ....... "visible" (page scrolled behind the modal)
 *   document.activeElement ......... BODY, before AND after opening
 *   Tab x12 from inside the modal .. 10 of 12 landed OUTSIDE it; by the 10th,
 *                                    focus was on the "Track order" nav link
 *                                    behind the overlay
 *   keyboard-reachable trigger ..... none — the card was an <article onClick>,
 *                                    so the dish detail could not be opened
 *                                    without a mouse at all
 *
 * The last line is why the trigger changed too, not just the modal: "focus
 * returns to the trigger" is unsatisfiable when there is no trigger to focus.
 */

import { test, expect, type APIRequestContext, type Page } from "@playwright/test"
import { VENDOR_PASSWORD, VENDOR_USERNAME, skipWithoutVendorPassword } from "./vendor-credentials"

// The seeded shop with a stable slug and a curated menu — same fixture the
// storefront flow spec uses, so the two cannot drift onto different data.
const SHOP_SLUG = "mama-ades-kitchen"

/** Everything the dialog contract is made of, read out of the live DOM. */
async function dialogState(page: Page) {
  return page.evaluate(() => {
    const dialog = document.querySelector('[role="dialog"]')
    const active = document.activeElement
    const labelledBy = dialog?.getAttribute("aria-labelledby")
    return {
      dialogCount: document.querySelectorAll('[role="dialog"]').length,
      ariaModal: dialog?.getAttribute("aria-modal") ?? null,
      accessibleName: labelledBy
        ? (document.getElementById(labelledBy)?.textContent ?? "").trim()
        : null,
      bodyOverflow: getComputedStyle(document.body).overflow,
      // Radix's other inertness mechanism (`hideOthers` from the aria-hidden
      // package). Measured on this page: it inerts the page content but NOT
      // <header> — the header contains the basket's aria-live="polite" region,
      // and the library deliberately leaves live regions announceable.
      mainAriaHidden:
        document.querySelector("main")?.getAttribute("aria-hidden") ?? null,
      /**
       * IS THE PAGE CONTENT INERT? — the property, not the node carrying it.
       *
       * This was `mainAriaHidden === "true"` and that turned out to pin the
       * wrong thing. `hideOthers` keeps `[aria-live]` elements AND `script`
       * elements out of the hidden set, and it does so by adding them as
       * TARGETS — which marks all of their ancestors as "keep" and makes the
       * walk recurse INTO those ancestors instead of hiding them. From
       * node_modules/aria-hidden (verbatim):
       *
       *   // we should not hide aria-live elements ... and script elements,
       *   // as they have no impact on accessibility.
       *   targets.push(...parentNode.querySelectorAll('[aria-live], script'))
       *
       * So the moment `/shop/[slug]` gained a `<script type="application/ld+json">`
       * for its schema.org markup (#447), `<main>` became a keep-ancestor and
       * the attribute moved one level down onto main's content `<div>`.
       * Measured both ways on the same build: with the JSON-LD removed, 16/16
       * of this file pass; with it present, only these two blocks failed.
       *
       * Nothing about the customer's experience changed — the library's own
       * comment is the reason: a script has no accessible representation, and
       * everything that does is still hidden. So this asserts the contract
       * ("the page behind the dialog is inert") rather than one node's
       * attribute. It still fails pre-fix, where NOTHING was hidden at all.
       */
      pageContentInert: (() => {
        const main = document.querySelector("main")
        if (!main) return false
        if (main.getAttribute("aria-hidden") === "true") return true
        const rendered = Array.from(main.children).filter((c) => c.tagName !== "SCRIPT")
        return (
          rendered.length > 0 &&
          rendered.every((c) => c.getAttribute("aria-hidden") === "true")
        )
      })(),
      headerAriaHidden:
        document.querySelector("header")?.getAttribute("aria-hidden") ?? null,
      activeTag: active?.tagName ?? null,
      activeName:
        active?.getAttribute("aria-label") ??
        (active?.textContent ?? "").trim().slice(0, 60),
      focusInsideDialog: !!(dialog && active && dialog.contains(active)),
    }
  })
}

/** The first dish card's dialog trigger. */
function firstTrigger(page: Page) {
  return page.getByRole("button", { name: /^View details for / }).first()
}

/**
 * The trigger's accessible name. Read from aria-label OR text content, because
 * this control is named by an `sr-only` child rather than an attribute — an
 * earlier version of this file read only `aria-label`, got `null`, and failed
 * three blocks against a CORRECT tree. Comparing a name to `null` is a test
 * defect that looks exactly like a product defect.
 */
async function accessibleNameOf(locator: ReturnType<typeof firstTrigger>) {
  return locator.evaluate(
    (el) => el.getAttribute("aria-label") || (el.textContent || "").trim()
  )
}

test.describe("Storefront dish modal — dialog contract", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`/shop/${SHOP_SLUG}`)
    // VACUITY GUARD. The catalogue is client-fetched; if it never arrives there
    // are no cards, every "the modal is closed" assertion below is trivially
    // true, and the suite reports green over a page that rendered nothing.
    await expect(firstTrigger(page)).toBeVisible({ timeout: 30_000 })
  })

  test("opens as a named modal dialog and inerts the page behind it", async ({ page }) => {
    const before = await dialogState(page)
    expect(before.dialogCount, "no dialog should exist before opening").toBe(0)
    expect(before.bodyOverflow).not.toBe("hidden")

    const trigger = firstTrigger(page)
    const triggerName = await accessibleNameOf(trigger)
    await trigger.click()

    const open = await dialogState(page)
    expect(open.dialogCount, "role=dialog was 0 pre-fix").toBe(1)
    expect(open.ariaModal, 'aria-modal was absent pre-fix').toBe("true")
    // The name must be the DISH, not a generic "Dialog" — this is the screen
    // where allergen data is communicated, so announcing which dish it is is
    // the whole point.
    expect(open.accessibleName, "dialog must be named by the dish title").toBeTruthy()
    expect(triggerName).toContain(open.accessibleName!)
    // The second inertness mechanism, asserted alongside aria-modal so neither
    // is trusted on its own.
    //
    // Scoped to <main> ON PURPOSE, and the scope is a measurement not a
    // convenience: `hideOthers` leaves <header> announceable because the basket
    // affordance inside it is an aria-live region, which the library will not
    // silence. That is shared Radix/aria-hidden behaviour — identical for the
    // cart drawer and all four existing dialogs — so it is recorded here rather
    // than worked around locally. It is not a hole in the contract: aria-modal
    // confines the AT virtual cursor to the dialog, and the focus trap asserted
    // below keeps the keyboard out of the header.
    //
    // Asserted as the PROPERTY rather than as `main[aria-hidden]`: the library
    // moves the attribute one node deeper as soon as <main> contains a
    // `<script>` (schema.org JSON-LD, #447), with no change to what is
    // announced. Full derivation, and the two measurements that isolated it,
    // in the `pageContentInert` note above.
    expect(open.pageContentInert, "page content must be inert behind the dialog").toBe(true)
    // Body scroll lock: the page must not scroll behind the overlay.
    expect(open.bodyOverflow, 'body overflow stayed "visible" pre-fix').toBe("hidden")
  })

  test("moves focus into the dialog on open", async ({ page }) => {
    await firstTrigger(page).click()

    const open = await dialogState(page)
    // Pre-fix this was BODY: focus never entered, so a screen-reader user was
    // never taken to the content that had just appeared.
    expect(open.focusInsideDialog, "focus must move into the dialog").toBe(true)
    expect(open.activeTag).toBe("BUTTON")
  })

  test("traps Tab inside the dialog", async ({ page }) => {
    await firstTrigger(page).click()
    await expect(page.getByRole("dialog")).toBeVisible()

    const escapes: string[] = []
    // More presses than the dialog has focusable controls, so the walk has to
    // wrap at least once. Pre-fix, 10 of 12 landed outside — the 10th on the
    // "Track order" link in the nav BEHIND the overlay.
    for (let i = 0; i < 12; i++) {
      await page.keyboard.press("Tab")
      const s = await dialogState(page)
      if (!s.focusInsideDialog) escapes.push(`tab ${i + 1}: ${s.activeTag} "${s.activeName}"`)
    }

    expect(escapes, `focus left the dialog: ${escapes.join(" | ")}`).toEqual([])
  })

  test("Escape closes it and returns focus to the trigger", async ({ page }) => {
    const trigger = firstTrigger(page)
    const triggerName = await accessibleNameOf(trigger)
    await trigger.click()
    await expect(page.getByRole("dialog")).toBeVisible()

    await page.keyboard.press("Escape")
    await expect(page.getByRole("dialog")).toHaveCount(0)

    const closed = await dialogState(page)
    // Pre-fix: overlay count went 2 -> 2 and nothing moved.
    expect(closed.dialogCount).toBe(0)
    // Scroll lock released — a lock that is never released is its own bug.
    expect(closed.bodyOverflow).not.toBe("hidden")
    expect(closed.mainAriaHidden).toBeNull()

    // Focus restored to the exact control that opened it, so a keyboard user
    // resumes at the dish they were on rather than at the top of the document.
    // RETRYING assertions, not a one-shot read: Radix restores focus during the
    // FocusScope unmount, which lands slightly after the dialog leaves the DOM.
    // A single read raced it and reported BODY on desktop while mobile passed —
    // the sort of viewport-dependent flake that gets rerun until it is green.
    await expect(trigger).toBeFocused()
    await expect
      .poll(async () => (await dialogState(page)).activeName)
      .toBe(triggerName)
  })

  test("can be opened and closed with the keyboard alone", async ({ page }) => {
    // The route that did not exist at all pre-fix: the card was a plain
    // <article onClick>, unreachable by Tab and unresponsive to Enter.
    const trigger = firstTrigger(page)
    const triggerName = await accessibleNameOf(trigger)

    await trigger.focus()
    await expect(trigger).toBeFocused()
    await page.keyboard.press("Enter")

    const open = await dialogState(page)
    expect(open.dialogCount).toBe(1)
    expect(open.focusInsideDialog).toBe(true)

    await page.keyboard.press("Escape")
    await expect(page.getByRole("dialog")).toHaveCount(0)
    await expect(trigger).toBeFocused()
    await expect
      .poll(async () => (await dialogState(page)).activeName)
      .toBe(triggerName)
  })

  test("still dismisses on a backdrop click", async ({ page }) => {
    // Non-regression: outside-click dismissal was the ONE dismissal path that
    // already worked, and the port must not have traded it away for Escape.
    await firstTrigger(page).click()
    await expect(page.getByRole("dialog")).toBeVisible()

    const box = page.viewportSize()!
    await page.mouse.click(box.width / 2, 12) // above the panel, on the backdrop
    await expect(page.getByRole("dialog")).toHaveCount(0)
    expect((await dialogState(page)).bodyOverflow).not.toBe("hidden")
  })

  test("the add-to-cart control inside the dialog is still reachable and works", async ({ page }) => {
    // Incremental-betterment guard. The trigger is a stretched button covering
    // the card, so it MUST NOT have swallowed the card's own "Add" control, and
    // the modal's own add button must still add.
    await firstTrigger(page).click()
    const dialog = page.getByRole("dialog")
    await expect(dialog).toBeVisible()

    await dialog.getByRole("button", { name: /Add to cart/i }).click()
    // The footer swaps to the quantity stepper once the dish is in the basket.
    await expect(dialog.getByText("In cart")).toBeVisible()
  })
})

test.describe("Storefront basket announcement — pluralisation (#272)", () => {
  test("announces '1 item in basket', not '1 items'", async ({ page }) => {
    await page.goto(`/shop/${SHOP_SLUG}`)

    const addButton = page.getByRole("button", { name: /^Add .+ to basket/ }).first()
    await expect(addButton).toBeVisible({ timeout: 30_000 })

    const announcement = () =>
      page.evaluate(() => {
        const el = Array.from(document.querySelectorAll("span.sr-only")).find((s) =>
          /in basket/.test(s.textContent || "")
        )
        return (el?.textContent || "(not found)").replace(/\s+/g, " ").trim()
      })

    // Only the count of EXACTLY 1 distinguishes correct from broken; the defect
    // is invisible at 0 and at 2, which is part of why it survived so long.
    await addButton.click()
    await expect.poll(announcement).toBe("1 item in basket")

    // And the plural still applies above one — a fix that hardcoded the
    // singular would satisfy the block above and be equally wrong.
    await page.getByRole("button", { name: /^Add .+ to basket/ }).first().click()
    await expect.poll(announcement).toBe("2 items in basket")
  })
})

/**
 * 31.1-21 (#817, #787, #861; D-09, D-16, D-18): the dish modal LEADS with the dish's allergens.
 *
 * Two claims, each needing a real browser:
 *
 * 1. AT 200% ZOOM ON A PHONE the "Allergen Information" heading is on screen when the dialog opens,
 *    before any scrolling. Browser zoom at 200% halves the CSS viewport, so a 390x844 phone at 200%
 *    lays out as 195x422 CSS pixels — that is what is emulated here. `deviceScaleFactor` is NOT
 *    used: it changes the device-pixel ratio and leaves the CSS layout untouched, so a check run
 *    under it would pass on the unfixed tree too (it could not fail). The unfixed tree is the
 *    fail direction: there the section sat below the description and the ingredients, and was not
 *    rendered at all for an empty declaration.
 *
 * 2. THE THREE STATEMENTS on a real menu row: the declared set as a named "Contains" list, a
 *    separate "May contain: Sesame" line (never inside that list), and the D-09 "Ingredients name:
 *    MILK – check with the shop" line the server derives from the ingredients text.
 *
 * WHY A DEDICATED FIXTURE PRODUCT, NOT AN EDIT TO A DEMO DISH. No seeded dish carries a may-contain
 * mask, and an existing dish cannot be put back once one is written: an omitted `mayContainMask` on
 * PUT keeps the stored value and there is no API path back to NULL (31.1-14, by design), so a demo
 * dish would be left claiming "no cross-contact risk recorded = 0" where it said "not recorded".
 * The spec therefore owns ONE product, found by its SKU or created once, which is shown on the
 * storefront only for the duration of the test and hidden again in a `finally` (there is no product
 * DELETE endpoint). The hide is verified BY CONTENT: the vendor read-back says `available: false`
 * and the public menu no longer lists the product id. No other product is touched.
 *
 * WHAT IT NEEDS. The compose stack (rebuilt with 31.1-06/-14 for the two public fields) and, for
 * block 2, a vendor password and KEYCLOAK_CLIENT_SECRET (`set -a; . ./.env; set +a`); without them
 * block 2 SKIPS naming the fix. Listed by plan 31.1-21; its RED/GREEN live run belongs to 31.1-30.
 */

const API = process.env.PLAYWRIGHT_API_URL || "http://localhost:9090"
const KEYCLOAK = process.env.PLAYWRIGHT_KEYCLOAK_URL || "http://localhost:8085"
const REALM = process.env.E2E_VENDOR_REALM || "jtoye-dev"
const CLIENT_ID = process.env.E2E_VENDOR_CLIENT_ID || "core-api"
const CLIENT_SECRET = process.env.KEYCLOAK_CLIENT_SECRET ?? ""

const FIXTURE_SKU = "E2E-31121-DISH-ALLERGENS"
const FIXTURE_TITLE = "E2E allergen statements dish"
const GLUTEN_BIT = 0
const SESAME_BIT = 10

type ProductDto = Record<string, unknown> & { id: string; sku: string; available?: boolean }

/** Measures, inside the open dialog, whether the allergen heading is visible with no scrolling. */
async function allergenHeadingPlacement(page: Page) {
  return page.evaluate(() => {
    const dialog = document.querySelector('[role="dialog"]') as HTMLElement | null
    const heading = Array.from(dialog?.querySelectorAll("h3") ?? []).find(
      (h) => (h.textContent ?? "").trim() === "Allergen Information"
    ) as HTMLElement | undefined
    if (!dialog || !heading) return { found: false } as const
    const scroller = heading.closest(".overflow-y-auto") as HTMLElement | null
    const footer = dialog.lastElementChild as HTMLElement | null
    const h = heading.getBoundingClientRect()
    const d = dialog.getBoundingClientRect()
    const f = footer?.getBoundingClientRect()
    const visibleBottom = Math.min(d.bottom, window.innerHeight, f && footer !== scroller ? f.top : Infinity)
    return {
      found: true,
      scrollTop: scroller?.scrollTop ?? -1,
      headingTop: Math.round(h.top),
      headingBottom: Math.round(h.bottom),
      dialogTop: Math.round(d.top),
      visibleBottom: Math.round(visibleBottom),
      innerWidth: window.innerWidth,
    } as const
  })
}

test.describe("Dish modal allergen section (31.1-21)", () => {
  test("at 200% zoom on a 390x844 phone the allergen heading is visible without scrolling", async ({ page }) => {
    // 200% zoom = half the CSS viewport (see the block comment above for why not deviceScaleFactor).
    await page.setViewportSize({ width: 195, height: 422 })
    // At 195x422 the fixed cookie notice covers the first trigger and intercepts the click on the
    // non-touch desktop project (31.1-30: 60 s timeout on the live stack). Acknowledge it first, with
    // the key/version lib/consent.ts reads (COOKIE_NOTICE_ACK_KEY / COOKIE_POLICY_VERSION); the
    // measurement is about the dialog, not the notice.
    await page.addInitScript(() => window.localStorage.setItem("jtoye-cookie-notice-ack", "2026-08-16"))
    await page.goto(`/shop/${SHOP_SLUG}`)
    const trigger = firstTrigger(page)
    await expect(trigger).toBeAttached({ timeout: 30_000 })
    await trigger.scrollIntoViewIfNeeded()
    await trigger.click()
    await expect(page.getByRole("dialog")).toBeVisible()

    const placement = await allergenHeadingPlacement(page)
    // Control: the emulation took (the layout viewport really is 195 CSS px wide).
    expect(placement.found ? placement.innerWidth : -1, "the zoom emulation did not apply").toBe(195)
    expect(placement.found, "no 'Allergen Information' heading in the dialog (empty declarations rendered none pre-fix)").toBe(true)
    if (!placement.found) return
    expect(placement.scrollTop, "the dialog content was scrolled before the check").toBe(0)
    expect(placement.headingTop).toBeGreaterThanOrEqual(placement.dialogTop)
    expect(
      placement.headingBottom,
      `allergen heading ends at ${placement.headingBottom}px, below the visible content edge ${placement.visibleBottom}px`
    ).toBeLessThanOrEqual(placement.visibleBottom)
  })

  test("a menu row shows Contains, a separate May contain line and the D-09 ingredients line", async ({
    page,
    request,
  }, testInfo) => {
    skipWithoutVendorPassword()
    test.skip(
      !CLIENT_SECRET,
      "No KEYCLOAK_CLIENT_SECRET — source the stack's .env (set -a; . ./.env; set +a) so the spec can mint a vendor token"
    )
    // One shared fixture product: with more than one worker the two projects would show and hide
    // it under each other, so only the desktop project runs then.
    test.skip(
      testInfo.config.workers > 1 && testInfo.project.name !== "desktop",
      "PLAYWRIGHT_WORKERS > 1: two projects would show and hide the same fixture product concurrently"
    )

    const token = await vendorToken(request)
    const auth = { Authorization: `Bearer ${token}` }
    const shopId = await shopIdFor(request, auth, SHOP_SLUG)
    const body = {
      sku: FIXTURE_SKU,
      title: FIXTURE_TITLE,
      // MILK in CAPITALS is the emphasis the server reads (31.1-06); Milk is NOT declared, so the
      // public row carries undeclaredIngredientAllergens ["Milk"].
      ingredientsText: "rice, butter (MILK), pepper",
      allergenMask: 1 << GLUTEN_BIT,
      mayContainMask: 1 << SESAME_BIT,
      pricePennies: 100,
      category: "Mains",
      displayOrder: 9999,
      available: true,
      featured: false,
      shopId,
    }

    const existing = await findBySku(request, auth, FIXTURE_SKU)
    const fixture = existing
      ? await putProduct(request, auth, existing.id, body)
      : await createProduct(request, auth, body)

    try {
      await page.goto(`/shop/${SHOP_SLUG}`)
      const trigger = page.getByRole("button", { name: `View details for ${FIXTURE_TITLE}` })
      await expect(trigger, "the fixture product is not on the storefront menu").toBeAttached({ timeout: 30_000 })
      await trigger.scrollIntoViewIfNeeded()
      await trigger.click()
      const dialog = page.getByRole("dialog")
      await expect(dialog).toBeVisible()

      const section = dialog.getByRole("region", { name: "Allergen Information" })
      const contains = section.getByRole("list", { name: "Contains" })
      await expect(contains.getByRole("listitem")).toHaveText(["Gluten"])
      const may = section.getByText("May contain: Sesame", { exact: true })
      await expect(may).toBeVisible()
      // Never merged into the declared set.
      await expect(contains.getByText(/Sesame/)).toHaveCount(0)
      await expect(section.getByText("Ingredients name: MILK – check with the shop", { exact: true })).toBeVisible()
      await expect(section.getByText("No allergens declared")).toHaveCount(0)

      // The card says the same before Add, as its accessible description.
      await page.keyboard.press("Escape")
      const add = page.getByRole("button", { name: `Add ${FIXTURE_TITLE} to basket` })
      await expect(add).toHaveAccessibleDescription(/Contains: Gluten.*Ingredients name: MILK – check with the shop/)
    } finally {
      // Restore: hidden from the storefront again, then READ BACK — a 200 on the PUT is not evidence.
      await putProduct(request, auth, fixture.id, { ...body, available: false })
      const after = await readProduct(request, auth, fixture.id)
      expect(after.available, "the fixture product is still available after the restore").toBe(false)
      const menu = await request.get(`${API}/public/shops/${SHOP_SLUG}/products`)
      expect(menu.status()).toBe(200)
      const ids = Object.values((await menu.json()) as Record<string, Array<{ id: string }>>)
        .flat()
        .map((p) => p.id)
      expect(ids.length, "the public menu came back empty, so its absence check would prove nothing").toBeGreaterThan(0)
      expect(ids, "the fixture product is still on the public menu after the restore").not.toContain(fixture.id)
    }
  })
})

async function vendorToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/token`, {
    form: {
      grant_type: "password",
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      username: VENDOR_USERNAME,
      password: VENDOR_PASSWORD,
    },
  })
  expect(res.status(), `Keycloak refused the vendor token request (${REALM}/${CLIENT_ID})`).toBe(200)
  const token = ((await res.json()) as { access_token?: string }).access_token
  expect(token, "the token response carried no access_token").toBeTruthy()
  return token as string
}

async function shopIdFor(request: APIRequestContext, auth: Record<string, string>, slug: string): Promise<string> {
  const res = await request.get(`${API}/api/v1/shops?size=100`, { headers: auth })
  expect(res.status(), "GET /api/v1/shops").toBe(200)
  const shops = ((await res.json()) as { content?: Array<{ id: string; slug: string }> }).content ?? []
  const shop = shops.find((s) => s.slug === slug)
  expect(shop, `the vendor's tenant has no shop ${slug}`).toBeTruthy()
  return shop!.id
}

async function findBySku(
  request: APIRequestContext,
  auth: Record<string, string>,
  sku: string
): Promise<ProductDto | null> {
  const res = await request.get(`${API}/api/v1/products/search?q=${encodeURIComponent(sku)}`, { headers: auth })
  expect(res.status(), "GET /api/v1/products/search").toBe(200)
  return ((await res.json()) as ProductDto[]).find((p) => p.sku === sku) ?? null
}

async function readProduct(request: APIRequestContext, auth: Record<string, string>, id: string): Promise<ProductDto> {
  const res = await request.get(`${API}/api/v1/products/${id}`, { headers: auth })
  expect(res.status(), `GET /api/v1/products/${id}`).toBe(200)
  return (await res.json()) as ProductDto
}

async function createProduct(
  request: APIRequestContext,
  auth: Record<string, string>,
  body: Record<string, unknown>
): Promise<ProductDto> {
  const res = await request.post(`${API}/api/v1/products`, { headers: auth, data: body })
  expect(res.status(), `POST /api/v1/products: ${await res.text()}`).toBe(201)
  return (await res.json()) as ProductDto
}

async function putProduct(
  request: APIRequestContext,
  auth: Record<string, string>,
  id: string,
  body: Record<string, unknown>
): Promise<ProductDto> {
  const res = await request.put(`${API}/api/v1/products/${id}`, { headers: auth, data: body })
  expect(res.status(), `PUT /api/v1/products/${id}: ${await res.text()}`).toBe(200)
  return (await res.json()) as ProductDto
}
