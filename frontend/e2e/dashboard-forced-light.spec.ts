/**
 * Quick 261016-edv: the dashboard is LIGHT whatever the vendor stored or the system prefers,
 * live in Chrome against the delivered compose runtime.
 *
 * THE RULING. Owner ruling 2026-10-16 (phase 37 `deferred-items.md`, "Owner rulings 2026-10-16"):
 * the dashboard is unreadable in dark (37-55 evidence sections 6-7: 73/125 text elements on
 * /dashboard/staff below 4.5:1, and the shop picker draws a light option list), so until a
 * dashboard-wide dark pass ships (Phase 39 candidate) `hooks/use-theme.ts` withholds the dark
 * theme behind DARK_THEME_AVAILABLE = false: it resolves light, leaves no `dark` class on <html>,
 * writes nothing to localStorage, and both surfaces hide the Dark Mode / Light Mode control.
 *
 * WHICH TEST PROVES WHAT. Both run with the system emulating `prefers-color-scheme: dark`
 * (file-level `colorScheme: "dark"`) and with `theme = "dark"` stored, so every input says dark:
 *   1. desktop (1440, @desktop-only): on /dashboard and then /dashboard/staff, <html> carries no
 *      `dark` class, the sidebar footer shows Sign Out and no theme control (nor does any button
 *      on the page, hidden ones included), and the stored value is still "dark" after the reload
 *      and the second route.
 *   2. mobile (390 x 844, isMobile + hasTouch, @mobile-only): the "More navigation" sheet, where
 *      the tab bar's theme control lived, shows Sign Out and no theme control; <html> carries no
 *      `dark` class; the stored value is still "dark".
 * Controls in both: the emulation reached the page (matchMedia reports dark) and the stored choice
 * is present before anything is asserted about the theme.
 *
 * WHY A STALE CLASS IS SEEDED. Server-rendered markup never carries `dark`, so "no dark class"
 * read before hydration is true of every build, the old one included. An init script therefore
 * puts `dark` on <html> before the page's own scripts run, and the assertion waits for it to be
 * GONE: only the hook's mount effect removes it, so its absence proves the client ran and chose
 * light. A build that still applied dark keeps the class and the wait times out (measured RED on
 * the pre-change runtime; live-runtime.md in the quick task's evidence).
 *
 * INSTRUMENT ARMS, run on EVERY execution, each must fail as designed:
 *   (1) `dark` added to <html> by hand: the html-class checker must report dark; removed: light.
 *   (2) a button reading "Dark Mode" injected into the region under test (sidebar footer / open
 *       sheet): the theme-button locator must count one more than before; removed: as before.
 * The forced-light assertions are soft (the controls are hard), so a build that still applies
 * dark fails every one of them in a single run AND still reaches the arms.
 *
 * COVERAGE BOUNDARY. Needs the live compose stack and a Keycloak login (dashboard routes resolve
 * the NextAuth session server-side), so it runs at gates and in the nightly full-stack lane, never
 * per PR; the per-PR guards are the forced-light jest blocks in hooks/__tests__/use-theme.test.tsx
 * and components/dashboard/__tests__/theme-cross-surface.test.tsx. A vendorLogin skip (no
 * password) is VOID, not a pass.
 *
 * GOES RED BY DESIGN when DARK_THEME_AVAILABLE is flipped to true; delete it with the flag.
 *
 * Run: set -a; . ./.env; set +a; cd frontend && npx playwright test e2e/dashboard-forced-light.spec.ts
 */
import { test, expect, type Locator, type Page, type TestInfo } from "@playwright/test"
import { vendorLogin } from "./helpers/staff-invitee"

test.use({ colorScheme: "dark" })

const THEME_CONTROL = /dark mode|light mode/i
const SIGN_OUT = /^sign out$/i

function annotate(testInfo: TestInfo, type: string, value: unknown) {
  testInfo.annotations.push({ type, description: typeof value === "string" ? value : JSON.stringify(value) })
}

/**
 * Put `dark` on <html> before the page's own scripts run, on every navigation of this page.
 * At init-script time the document may not have its <html> yet; the observer catches the
 * parser inserting it (mutation records are delivered before any page script executes).
 */
async function seedStaleDarkClass(page: Page) {
  await page.addInitScript(() => {
    const add = () => document.documentElement?.classList.add("dark")
    if (document.documentElement) {
      add()
      return
    }
    const observer = new MutationObserver(() => {
      if (document.documentElement) {
        add()
        observer.disconnect()
      }
    })
    observer.observe(document, { childList: true })
  })
}

/** The html-class checker: does <html> carry `dark` right now? */
async function htmlHasDark(page: Page): Promise<boolean> {
  return page.evaluate(() => document.documentElement.classList.contains("dark"))
}

async function storedTheme(page: Page): Promise<string | null> {
  return page.evaluate(() => localStorage.getItem("theme"))
}

/**
 * The controls (hard), then the forced-light assertions for the page as it stands. The
 * forced-light assertions are SOFT so that a failing build still reaches the rest of the test
 * and its instrument arms: one run then shows every forced-light failure and proves the arms.
 */
async function expectForcedLight(page: Page, testInfo: TestInfo, label: string) {
  const prefersDark = await page.evaluate(() => window.matchMedia("(prefers-color-scheme: dark)").matches)
  annotate(testInfo, `${label}-prefers-dark`, prefersDark)
  expect(prefersDark, "control: the dark colour-scheme emulation reached the page").toBe(true)

  const stored = await storedTheme(page)
  annotate(testInfo, `${label}-stored-theme`, stored)
  expect.soft(stored, "the stored dark choice is present and untouched").toBe("dark")

  let staleClassRemoved = true
  try {
    await expect.poll(() => htmlHasDark(page), { timeout: 15_000 }).toBe(false)
  } catch {
    staleClassRemoved = false
  }
  annotate(testInfo, `${label}-html-dark`, await htmlHasDark(page))
  expect
    .soft(
      staleClassRemoved,
      "quick 261016-edv: the dashboard must leave no `dark` class on <html> (the seeded stale one must be removed within 15 s)"
    )
    .toBe(true)
}

/** Arm (1): the html-class checker can report dark. */
async function armHtmlClass(page: Page, testInfo: TestInfo) {
  await page.evaluate(() => document.documentElement.classList.add("dark"))
  const armed = await htmlHasDark(page)
  await page.evaluate(() => document.documentElement.classList.remove("dark"))
  const after = await htmlHasDark(page)
  annotate(testInfo, "arm-html-class", { armed, after })
  expect(armed, "arm 1: the html-class checker must report a hand-added dark class").toBe(true)
  expect(after, "arm 1: removed again, the checker reports light").toBe(false)
}

/**
 * Arm (2): the theme-button locator can count a theme control in `region`. Relative to the
 * count before the injection, so the arm also proves itself on a build that still renders one.
 */
async function armThemeButton(page: Page, region: Locator, testInfo: TestInfo) {
  const before = await region.getByRole("button", { name: THEME_CONTROL }).count()
  await region.evaluate((el) => {
    const b = document.createElement("button")
    b.type = "button"
    b.textContent = "Dark Mode"
    b.setAttribute("data-e2e-arm", "")
    el.appendChild(b)
  })
  const armed = await region.getByRole("button", { name: THEME_CONTROL }).count()
  await page.evaluate(() => document.querySelector("[data-e2e-arm]")?.remove())
  const after = await region.getByRole("button", { name: THEME_CONTROL }).count()
  annotate(testInfo, "arm-theme-button", { before, armed, after })
  expect(armed, "arm 2: the theme-button locator must count an injected Dark Mode button").toBe(before + 1)
  expect(after, "arm 2: removed again, the locator counts what it counted before").toBe(before)
}

test.describe("dashboard forced light (quick 261016-edv)", () => {
  test("desktop: stored dark and a dark system still give the light dashboard, with no theme control @desktop-only", async ({
    page,
  }, testInfo) => {
    test.setTimeout(120_000)
    await seedStaleDarkClass(page)
    await vendorLogin(page)

    await page.evaluate(() => localStorage.setItem("theme", "dark"))
    await page.reload({ waitUntil: "domcontentloaded" })

    // The sidebar footer: the region the theme control shared with Sign Out. At 1440 the tab
    // bar is md:hidden and its sheet is closed, so the only Sign Out is the sidebar's.
    const signOut = page.getByRole("button", { name: SIGN_OUT })
    await expect(signOut, "control: the sidebar footer is rendered (Sign Out)").toBeVisible({ timeout: 20_000 })
    const footer = signOut.locator("xpath=..")

    await expectForcedLight(page, testInfo, "dashboard")
    const footerThemeButtons = await footer.getByRole("button", { name: THEME_CONTROL }).count()
    const pageThemeButtons = await page.getByRole("button", { name: THEME_CONTROL, includeHidden: true }).count()
    annotate(testInfo, "dashboard-theme-buttons", { footer: footerThemeButtons, page: pageThemeButtons })
    expect.soft(footerThemeButtons, "the sidebar footer offers no Dark Mode / Light Mode control").toBe(0)
    expect.soft(pageThemeButtons, "no button on the page, hidden ones included, is a theme control").toBe(0)

    await armHtmlClass(page, testInfo)
    await armThemeButton(page, footer, testInfo)

    // A second dashboard route: the choice still does not apply and is still stored.
    await page.goto("/dashboard/staff", { waitUntil: "domcontentloaded" })
    await expect(page.getByRole("region", { name: "Invite someone" })).toBeVisible({ timeout: 20_000 })
    await expect(signOut, "control: the sidebar footer on /dashboard/staff").toBeVisible({ timeout: 20_000 })
    await expectForcedLight(page, testInfo, "staff")
    const staffThemeButtons = await page.getByRole("button", { name: THEME_CONTROL, includeHidden: true }).count()
    annotate(testInfo, "staff-theme-buttons", staffThemeButtons)
    expect.soft(staffThemeButtons, "no theme control on /dashboard/staff").toBe(0)
  })

  test("mobile: the More sheet offers Sign Out and no theme control, and the dashboard stays light @mobile-only", async ({
    page,
  }, testInfo) => {
    test.setTimeout(120_000)
    await seedStaleDarkClass(page)
    await vendorLogin(page)

    await page.evaluate(() => localStorage.setItem("theme", "dark"))
    await page.reload({ waitUntil: "domcontentloaded" })

    await expectForcedLight(page, testInfo, "dashboard")

    await page.getByRole("button", { name: "More navigation" }).click()
    const sheet = page.getByRole("dialog")
    await expect(sheet, "control: the More sheet opened").toBeVisible({ timeout: 10_000 })
    const signOut = sheet.getByRole("button", { name: SIGN_OUT })
    await expect(signOut, "control: the sheet's footer is rendered (Sign Out)").toBeVisible()

    const sheetThemeButtons = await sheet.getByRole("button", { name: THEME_CONTROL }).count()
    annotate(testInfo, "sheet-theme-buttons", sheetThemeButtons)
    expect.soft(sheetThemeButtons, "the More sheet offers no Dark Mode / Light Mode control").toBe(0)
    expect.soft(await htmlHasDark(page), "still no dark class with the sheet open").toBe(false)
    expect.soft(await storedTheme(page), "the stored dark choice is untouched").toBe("dark")

    await armHtmlClass(page, testInfo)
    await armThemeButton(page, signOut.locator("xpath=.."), testInfo)
  })
})
