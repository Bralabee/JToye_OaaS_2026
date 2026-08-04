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

import { test, expect, type Page } from "@playwright/test"

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
      // package). Measured on this page: it inerts <main> but NOT <header> —
      // the header contains the basket's aria-live="polite" region, and the
      // library deliberately leaves live regions announceable. See the note on
      // the assertion below; <main> is the honest thing to assert here.
      mainAriaHidden:
        document.querySelector("main")?.getAttribute("aria-hidden") ?? null,
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
    expect(open.mainAriaHidden, "page content must be inert behind the dialog").toBe("true")
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

    const addButton = page.getByRole("button", { name: "Add", exact: true }).first()
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
    await page.getByRole("button", { name: "Add", exact: true }).first().click()
    await expect.poll(announcement).toBe("2 items in basket")
  })
})
