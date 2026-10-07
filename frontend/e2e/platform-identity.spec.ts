/**
 * #794 acceptance (D-14, phase 31.1 plan 27): a PRODUCTION-MODE build of the
 * frontend publishes the platform's legal identity — J'Toye Digital Ltd,
 * company number 16471464, and the registered office from the Companies House
 * record that the owner confirmed on 2026-10-07 — on the three legal pages.
 *
 * WHY A BUILT FRONTEND. NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE is inlined by
 * `next build`. A dev server reads it per request, so it would pass with the
 * variable present in the shell even when no runtime's BUILD passes it — which
 * is exactly the defect #794 reported (the Dockerfile declared the ARG, and
 * nothing ever supplied it). Run this against a production build:
 *   - the compose frontend (31.1-30 runs it there), or
 *   - stack-free: `next build && next start` with the variable set.
 * The legal pages make no API call, so no backend is needed.
 *
 * THE EXPECTED ADDRESS IS READ FROM THE REPOSITORY, NOT FROM THE ENVIRONMENT.
 * Reading it from process.env would let an unset variable pass vacuously
 * (every string contains ""). It is read from the root .env.example — the
 * documented source, pinned to the owner-confirmed string by
 * RegisteredOfficeParityTest — and the read fails closed when it is missing or
 * empty. Built with the variable UNSET, every page omits the address block and
 * this spec FAILS: that is its RED, and it is what stops it passing vacuously.
 *
 * Run: PLAYWRIGHT_BASE_URL=http://localhost:3000 npx playwright test e2e/platform-identity.spec.ts
 */
import fs from "node:fs"
import path from "node:path"
import { test, expect } from "@playwright/test"

const LEGAL_NAME = "J'Toye Digital Ltd"
const COMPANY_NUMBER = "16471464"
const JURISDICTION = "England & Wales"
/** The dissolved namesake. Never published anywhere. */
const DISSOLVED_NAMESAKE = "13434105"

/** The repository root: the nearest ancestor holding docker-compose.full-stack.yml. */
function repoRoot(): string {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, "docker-compose.full-stack.yml"))) return dir
    if (path.dirname(dir) === dir) {
      throw new Error(
        `VOID: no docker-compose.full-stack.yml above ${process.cwd()} — cannot read the documented registered office`
      )
    }
  }
}

/** The single NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE= assignment in the root .env.example, quotes removed. */
function documentedOffice(): string {
  const file = path.join(repoRoot(), ".env.example")
  const values = fs
    .readFileSync(file, "utf8")
    .split(/\r?\n/)
    .filter((line) => line.startsWith("NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE="))
    .map((line) => line.slice("NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE=".length).trim())
    .map((value) => value.replace(/^"(.*)"$/, "$1"))
  if (values.length !== 1 || !values[0]) {
    throw new Error(
      `VOID: ${file} must carry exactly one non-empty NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE= line (found ${values.length})`
    )
  }
  if (values[0].includes(DISSOLVED_NAMESAKE)) {
    throw new Error(`VOID: the documented registered office names the dissolved company ${DISSOLVED_NAMESAKE}`)
  }
  return values[0]
}

const OFFICE = documentedOffice()

const PAGES = [
  { path: "/legal", heading: /legal & company information/i },
  { path: "/legal/privacy", heading: /privacy notice/i },
  { path: "/legal/accessibility", heading: /accessibility statement/i },
] as const

for (const legal of PAGES) {
  test(`${legal.path} publishes the company name, number and registered office (#794)`, async ({ page }) => {
    const response = await page.goto(legal.path)
    expect(response?.status(), `${legal.path} must answer 200`).toBe(200)

    // CONTROL: this is the legal page, rendered — a blank page, an error page
    // or a sign-in redirect cannot reach the identity assertions below.
    const main = page.locator("main").first()
    await expect(main.getByRole("heading", { level: 1 })).toHaveText(legal.heading)

    const text = (await main.textContent()) ?? ""
    expect(text, `${legal.path}: company name`).toContain(LEGAL_NAME)
    expect(text, `${legal.path}: company number`).toContain(COMPANY_NUMBER)
    expect(text, `${legal.path}: registered office`).toContain(OFFICE)
    expect(text, `${legal.path}: never the dissolved namesake`).not.toContain(DISSOLVED_NAMESAKE)
  })
}

test("/legal lists the identity in a fixed order: name, number, jurisdiction, registered office", async ({
  page,
}) => {
  await page.goto("/legal")
  const main = page.locator("main").first()
  await expect(main.getByRole("heading", { level: 1 })).toHaveText(/legal & company information/i)

  const terms = (await main.locator("dl dt").allTextContents()).map((t) => t.trim())
  const values = (await main.locator("dl dd").allTextContents()).map((t) => t.trim())
  // The four identity rows come first, in this order; contact rows may follow.
  expect(terms.slice(0, 4)).toEqual([
    "Registered company name",
    "Company number",
    "Place of registration",
    "Registered office",
  ])
  expect(values.slice(0, 4)).toEqual([LEGAL_NAME, COMPANY_NUMBER, JURISDICTION, OFFICE])
})
