/**
 * The located kitchen row on `/` — device location end to end (issue 460, CUST-01).
 *
 * This is the first spec in the repository to use Playwright's geolocation
 * emulation, so it establishes the pattern:
 *
 *     context.grantPermissions(["geolocation"])
 *     context.setGeolocation({ latitude, longitude })
 *
 * With it, both directions are deterministic: no real GPS, no flake, and — the
 * part that matters — the DENIED path is reachable too, because Playwright denies
 * every permission that has not been explicitly granted rather than hanging on a
 * prompt. A feature whose failure path cannot be exercised is a feature whose
 * failure path is untested.
 *
 * ── FOUR INSTRUMENT RULES, EACH FOR A RECORDED REASON ────────────────────────
 *
 *  1. HEADINGS ARE LOCATED BY ROLE, never by test-id or title. React streams this
 *     page with a second copy of the entire shell parked in `<div hidden id="S:n">`
 *     for ~300 ms. The test-id and title locators both see that copy and produce
 *     a strict-mode violation or a stale read; `getByRole` does not, because
 *     hidden content has no accessible role. The same race was filed as a product
 *     bug twice (556, 593) before it was recognised as a locator choice.
 *  2. NO NETWORK-IDLE WAIT. The island issues a request after the grant and the
 *     page also holds an SSE connection, so waiting for an idle network is both
 *     flaky and semantically wrong here. 33-03 migrated
 *     `marketing-dish-scroller.spec.ts` off it for exactly this. Wait on the
 *     assertion instead.
 *
 *     Rules 1 and 2 are enforced by greps for the forbidden identifiers, so this
 *     paragraph deliberately does not spell any of the three out. Written plainly
 *     it turned both limbs red — measured, before the wording changed: the
 *     network-idle token matched 3 lines and the two locator names 1, every one
 *     of them PROSE and none of them code. It is the recorded "a rule that must
 *     name the token it forbids fires on its own definition" shape, and the same
 *     workaround `app/page.tsx`'s docblock uses. The greps are correct and are
 *     left exactly as they are.
 *  3. THE PINNED PAIR IS BRIXTON <-> PECKHAM, ~2 km apart — never the two Peckham
 *     shops, which after 33-05 sit ~600 m apart, inside postcode-centroid noise
 *     for a coarse test coordinate.
 *  4. BOTH DIRECTIONS, OR NOTHING. A single coordinate that happens to put the
 *     right shop first proves nothing: insertion order, name order and distance
 *     order are indistinguishable from one sample. Only a list that REORDERS when
 *     the caller moves can be driven by the caller's position.
 *
 * ── THE HEADER COMES FIRST ───────────────────────────────────────────────────
 *
 * `Permissions-Policy: geolocation=(self)` (33-03) must be on the live response.
 * With the previous empty allowlist the API is refused before any prompt and
 * presents IDENTICALLY to a user denial — so a granted arm would fail here and
 * every diagnosis would be aimed at the wrong layer. The first test asserts the
 * live header for that reason, and it is deliberately the first thing that runs.
 *
 * Run: npx playwright test near-you-row   (needs the REBUILT stack — the header,
 * the server-rendered row and the island are all build outputs, and
 * `docker compose start` does not rebuild).
 */
import { test, expect, type Page } from "@playwright/test"
import { execFileSync } from "node:child_process"

/** Unit 74, Brixton Village Market, SW9 8PS. */
const BRIXTON = { latitude: 51.4626, longitude: -0.1132 }
/** Rye Lane / Bellenden Road, Peckham. ~2 km from Brixton. */
const PECKHAM = { latitude: 51.47, longitude: -0.07 }
/** ~260 km away: far outside any radius the row will ask for. */
const MANCHESTER = { latitude: 53.4808, longitude: -2.2426 }

const row = (page: Page) => page.getByRole("region", { name: "Dishes cooking near you" })
const nearYouHeading = (page: Page) => page.getByRole("heading", { name: /near you/i })
const useMyLocation = (page: Page) => page.getByRole("button", { name: /use my location/i })

/** Every shop card in the row, by slug, in DOM order. */
async function cardSlugs(page: Page): Promise<string[]> {
  const hrefs = await row(page).locator("a").evaluateAll((els) =>
    els.map((el) => el.getAttribute("href") ?? "")
  )
  return hrefs.filter((h) => h.startsWith("/shop/")).map((h) => h.replace("/shop/", ""))
}

/**
 * Distances shown in the row, read out of its rendered text rather than through a
 * locator. `getByText` would match the pill AND its ancestors; a regex over the
 * row's own text is unambiguous and counts every card at once.
 *
 * MILES, and the unit is load-bearing in this matcher rather than incidental: the
 * API returns kilometres and the card converts, so a regression that dropped the
 * conversion would print "3.0 km" and this returns an EMPTY array — which reds
 * the granted arm's length assertion below. Falsified at the string level, since
 * proving it end to end would need a deliberately broken image:
 *   "0.2 miles away"  -> 1 match      "3.0 km away"  -> 0 matches
 */
async function distancesShown(page: Page): Promise<string[]> {
  const text = await row(page).innerText()
  return text.match(/\d+(\.\d)? miles/g) ?? []
}

async function openLanding(page: Page) {
  // `domcontentloaded` — never the idle-network wait; see rule 2 above.
  await page.goto("/", { waitUntil: "domcontentloaded" })
  await expect(row(page)).toBeVisible({ timeout: 20_000 })
  await row(page).scrollIntoViewIfNeeded()
}

test.describe("landing row — device location", () => {
  test("the live response permits geolocation to its own origin", async ({ request }) => {
    // Asserted against the SERVED response, not against next.config.mjs. The
    // config is the source; this is the delivered runtime, and 33-03's fix only
    // reaches a visitor once the image carrying it is actually running.
    const res = await request.get("/")
    const header = res.headers()["permissions-policy"] ?? ""
    expect(header, "Permissions-Policy is absent from the live response").not.toBe("")
    expect(
      header,
      "geolocation is denied at the header — every located arm below would fail " +
        "identically to a user denial, and the defect is NOT in the row"
    ).toContain("geolocation=(self)")
  })

  test("no coordinate: real shops, and no heading claims proximity", async ({ page, context }) => {
    await context.clearPermissions()
    await openLanding(page)

    // THE criterion, scoped to headings. `/` legitimately renders "near you" at
    // three non-heading sites, so a document-wide absence check is unsatisfiable
    // — 33-03 measured that and recorded the scoping decision.
    await expect(nearYouHeading(page)).toHaveCount(0)
    await expect(page.getByRole("heading", { name: /kitchens on j'toye/i })).toHaveCount(1)

    // The row is real, and the assertion above is therefore not passing over an
    // empty page.
    const slugs = await cardSlugs(page)
    expect(slugs.length, "the kitchen row served no cards at all").toBeGreaterThan(0)
    expect(slugs).toContain("mama-ades-kitchen")
    // Nothing may claim a distance when none was computed.
    expect(await distancesShown(page)).toEqual([])

    // THE CONTROL THAT DISTINGUISHES SCOPING FROM NARROWING. The two deliberately
    // out-of-scope "near you" sites must still be on the page and must NOT have
    // tripped the assertion above. Without this half, a criterion narrowed until
    // green looks identical to one correctly scoped.
    await expect(page.getByRole("link", { name: /order food near you/i })).toHaveCount(1)
    // Asserted against the DOM's serialised content rather than through a
    // VISIBILITY-based locator, and that is not laziness.
    //
    // The first form of this line was `p:visible` and it measured 1 on mobile
    // and 0 on DESKTOP — a real instrument defect, caught only because the suite
    // runs both projects. `hero-scene.tsx` sets `[data-hero-step]` to
    // `autoAlpha: 0` (visibility: hidden) inside its desktop `gsap.matchMedia`
    // branch until the step scrolls into view, so on desktop the paragraph is
    // genuinely not visible at load. This is the recorded "a screenshot taken
    // without scrolling reads reveal content as an empty band" trap wearing a
    // locator's clothes: the control would have reported the Browse copy DELETED
    // on every desktop run.
    //
    // The claim being controlled is "this copy is still rendered and did not trip
    // the heading criterion", which is a statement about the document, not about
    // what is painted right now.
    expect(
      await page.content(),
      "the Browse step body is deliberately out of scope and must still be rendered"
    ).toContain("Find independent kitchens near you")
  })

  test("a denial keeps the server list, the location-free heading, and no spinner", async ({
    page,
    context,
  }) => {
    // Playwright denies anything not explicitly granted, so this is the real
    // PERMISSION_DENIED path rather than a simulation of it.
    await context.clearPermissions()
    await openLanding(page)
    const before = await cardSlugs(page)

    await useMyLocation(page).click()

    // NON-VACUITY FIRST: prove the denial actually landed. Without this, a click
    // that did nothing whatsoever would satisfy every assertion below, and this
    // is the likeliest state to regress — the only path where a coordinate was
    // asked for and not obtained.
    await expect(page.getByText(/showing every kitchen/i)).toBeVisible()

    await expect(nearYouHeading(page)).toHaveCount(0)
    await expect(page.getByRole("heading", { name: /kitchens on j'toye/i })).toHaveCount(1)
    expect(await cardSlugs(page)).toEqual(before)
    await expect(useMyLocation(page)).toBeEnabled()
    await expect(
      useMyLocation(page).locator(".animate-spin"),
      "a spinner survived a denial — the visitor is left waiting for something that will never arrive"
    ).toHaveCount(0)
  })

  test("a granted coordinate orders by real distance, and REORDERS when it moves", async ({
    page,
    context,
  }) => {
    await context.grantPermissions(["geolocation"])

    // ── Direction 1: Brixton ────────────────────────────────────────────────
    await context.setGeolocation(BRIXTON)
    await openLanding(page)
    await useMyLocation(page).click()

    await expect(nearYouHeading(page)).toHaveCount(1)
    const fromBrixton = await cardSlugs(page)
    expect(fromBrixton[0], "standing in Brixton, the Brixton kitchen is nearest").toBe(
      "brixton-village-grill"
    )
    // Every card carries the distance the ordering used.
    expect(await distancesShown(page)).toHaveLength(fromBrixton.length)

    // ── Direction 2: Peckham ────────────────────────────────────────────────
    // Reloaded rather than re-clicked, because `maximumAge` lets the browser
    // answer a second request from its cached fix. A fresh document has no such
    // cache, so this genuinely re-acquires the new coordinate.
    await context.setGeolocation(PECKHAM)
    await openLanding(page)
    await useMyLocation(page).click()

    await expect(nearYouHeading(page)).toHaveCount(1)
    const fromPeckham = await cardSlugs(page)
    expect(fromPeckham[0], "standing in Peckham, the Peckham kitchen is nearest").toBe(
      "mama-ades-kitchen"
    )

    // THE PROOF. A merely-sorted list is indistinguishable from name order or
    // insertion order; a list that inverts when the caller moves can only be
    // driven by the caller's position. Pinned to the ~2 km Brixton/Peckham pair,
    // never the ~600 m Peckham pair, which is inside centroid noise.
    expect(
      fromPeckham,
      "the order did not change when the caller moved ~2 km — nothing here is distance-driven"
    ).not.toEqual(fromBrixton)
  })

  test("far from every kitchen: says so, and still shows the full list", async ({
    page,
    context,
  }) => {
    await context.grantPermissions(["geolocation"])
    await context.setGeolocation(MANCHESTER)
    await openLanding(page)
    const unlocated = await cardSlugs(page)

    await useMyLocation(page).click()

    // The honest third state. Showing London shops under a "near you" heading to
    // somebody in Manchester is the same class of untruth issue 544 exists to stop.
    //
    // The radius is quoted to the customer in MILES — 3.1, being the 5 km the
    // island actually sent. Asserted as the literal rather than as `\d+ miles`:
    // a loose digit class would accept "5 miles", i.e. the kilometre figure with
    // the unit swapped, which is the one wrong answer that looks most right.
    await expect(
      page.getByRole("heading", { name: /no kitchens within 3\.1 miles/i })
    ).toHaveCount(1)
    await expect(nearYouHeading(page)).toHaveCount(0)
    // ...and the visitor is not punished for being far away.
    expect(await cardSlugs(page)).toEqual(unlocated)
    expect(
      await distancesShown(page),
      "a distance was printed for shops that are NOT in the result set"
    ).toEqual([])
  })
})

/**
 * ── THE EXCLUSION-DISCLOSURE ARM (plan-checker B8) ───────────────────────────
 *
 * 33-06's query filters `latitude IS NOT NULL`, so a published shop that failed
 * to geocode disappears from the platform's PRIMARY discovery row with no notice.
 * That is not hypothetical: Code-Point Open is GB-only, so a Northern Ireland
 * vendor will never geocode, and a postcode newer than the committed snapshot
 * will not either. A silent vanish is a regression by omission — a defect even
 * with a green suite, by this project's own doctrine.
 *
 * Nothing in the seeded data exercises it: all three published shops carry
 * coordinates after 33-05, and the two that do not are unpublished. So this arm
 * MAKES the state, on the live database, and puts it back — verifying the restore
 * BY CONTENT (reading the values back) rather than by an exit code, because
 * `docker exec` without `-i` exits 0 having delivered nothing to psql.
 *
 * `serial` so the restore in `afterAll` cannot be skipped by a parallel abort.
 */
test.describe.serial("a published shop with no coordinates is disclosed, not dropped", () => {
  const VICTIM = "peckham-jollof-co"
  let original: { lat: string; lon: string } | null = null

  function psql(sql: string): string {
    const user = execFileSync("docker", ["exec", "jtoye-postgres", "printenv", "POSTGRES_USER"], {
      encoding: "utf8",
    }).trim()
    const db = execFileSync("docker", ["exec", "jtoye-postgres", "printenv", "POSTGRES_DB"], {
      encoding: "utf8",
    }).trim()
    // No password is passed or printed: the official image trusts local socket
    // connections, which is the only path `docker exec` uses.
    return execFileSync(
      "docker",
      ["exec", "jtoye-postgres", "psql", "-U", user, "-d", db, "-tA", "-c", sql],
      { encoding: "utf8" }
    ).trim()
  }

  function coordsOf(slug: string): string {
    return psql(`SELECT latitude || '|' || longitude FROM shops WHERE slug = '${slug}';`)
  }

  test.beforeAll(() => {
    const before = coordsOf(VICTIM)
    // NON-VACUITY. If the shop already had no coordinates, nulling it changes
    // nothing and the disclosure below would be proving something else entirely.
    expect(before, `${VICTIM} already has no coordinates — this arm would be vacuous`).toMatch(
      /^-?\d+\.\d+\|-?\d+\.\d+$/
    )
    const [lat, lon] = before.split("|")
    original = { lat, lon }

    // psql's own command tag is the evidence, never the process exit status.
    const tag = psql(`UPDATE shops SET latitude = NULL, longitude = NULL WHERE slug = '${VICTIM}';`)
    expect(tag, "the UPDATE did not report one affected row").toBe("UPDATE 1")
    expect(coordsOf(VICTIM), "the coordinates were not actually cleared").toBe("")
  })

  test.afterAll(() => {
    if (!original) return
    const tag = psql(
      `UPDATE shops SET latitude = ${original.lat}, longitude = ${original.lon} WHERE slug = '${VICTIM}';`
    )
    expect(tag, "the restore did not report one affected row").toBe("UPDATE 1")
    // VERIFIED BY CONTENT. A restore that silently fails leaves every later run —
    // and `scripts/check-live-shop-coordinates.sh` — reading a broken database.
    expect(coordsOf(VICTIM), "RESTORE FAILED: the coordinates were not put back").toBe(
      `${original.lat}|${original.lon}`
    )
  })

  test("names the excluded shop's absence, and keeps it reachable", async ({ page, context }) => {
    await context.grantPermissions(["geolocation"])
    await context.setGeolocation(PECKHAM)
    await openLanding(page)

    const serverShops = await cardSlugs(page)
    expect(serverShops, "the un-geocodable shop must still be PUBLISHED and listed").toContain(
      VICTIM
    )

    await useMyLocation(page).click()
    await expect(nearYouHeading(page)).toHaveCount(1)

    // It is gone from the located row — that is 33-06's filter working...
    expect(await cardSlugs(page)).not.toContain(VICTIM)

    // ...and the row SAYS SO, with the right count and a way back.
    const disclosure = page.getByText(/no location data/i)
    await expect(disclosure).toBeVisible()
    await expect(disclosure).toContainText("1 kitchen has no location data")
    await expect(
      disclosure.getByRole("link", { name: /see every kitchen/i })
    ).toHaveAttribute("href", "/shop")

    // The shop lost its ranking, not its storefront.
    //
    // Located by its card HEADING, not by the link that wraps it. The first form
    // was `getByRole("link", { name: /Peckham Jollof/i })` and it found NOTHING
    // while the served HTML contained the shop's name five times — the card's
    // link wraps an `<article>`, and Chromium's accessible-name-from-content
    // walk does not pull that subtree up into the link's name. An absence
    // reported by an instrument that cannot see the thing is not evidence, and
    // this one would have read as "the excluded shop has vanished from the
    // platform", which is the exact defect this arm exists to detect.
    await page.goto("/shop", { waitUntil: "domcontentloaded" })
    await expect(page.getByRole("heading", { name: /Peckham Jollof/i })).toBeVisible({
      timeout: 20_000,
    })
  })
})
