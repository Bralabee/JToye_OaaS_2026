/**
 * 37-52 (UI-SPEC § F2, F3, F5, F6; RWO-082 / UXT-082, RWO-083 / UXT-083, RWO-085 / UXT-085, RWO-080
 * basket and shop-header part; D-05) — the browser instruments that see what axe misses on the
 * storefront.
 *
 * axe reports 0 violations on every defect this plan fixes (public-a11y.spec.ts scans /shop, a
 * storefront and the basket), because none of them is a static-markup rule:
 *   - UXT-082: a shop card is an <a> wrapping an <article>, and Chrome named the link from nothing
 *     useful. The oracle is CHROME'S OWN accessibility tree, read through CDP
 *     (`Accessibility.getFullAXTree`), the instrument the persona used. Playwright's `getByRole(…,
 *     { name })` computes its own accname and may return a name Chrome does not (RESEARCH A6), so it
 *     is not used for this.
 *   - UXT-083: after "Add" focus fell to <body>, and the basket link read "1 1 item in basket". The
 *     instruments are `document.activeElement` after a real Enter, and a MutationObserver recorder on
 *     `[role=status],[aria-live]`.
 *   - UXT-085: on a phone the cookie notice covered the focused element, and "Got it" was the 32nd tab
 *     stop. The instrument tabs through the first 30 stops on an iPhone 13 profile and intersects the
 *     focused element's rect with the notice's.
 *   - UXT-080: at 360px with a 130% root font the basket names and the shop name ended in "…". The
 *     instrument is `scrollWidth <= clientWidth` plus the computed `text-overflow` / `white-space`.
 *
 * TWO BLOCKS (the 37-51 a11y-tracking.spec.ts shape).
 *
 * `live storefront` — WRITTEN AND LISTED IN 37-52, RUN IN 37-53 on the rebuilt stack (37-52 is
 * code-only). Customer-side only: it places no order and needs no vendor password; the basket lives
 * in the browser's localStorage. Each probe has an INSTRUMENT arm on the same real page: with
 * aria-labelledby stripped by `page.evaluate`, Chrome's name for that card is no longer the shop and
 * the name assertion THROWS; with the live regions removed, a real Add records nothing and the
 * announcement assertion THROWS; with the notice forced over the focused element, the overlap probe
 * reports it; with a name forced to an ellipsis, the overflow probe reports it.
 *
 * `instruments can fail @stack-free` — the same four probes on hand-built fixtures (page.setContent,
 * no server), on every run: a positive control for each probe, then the broken input on which each
 * must fail.
 *
 * Run: npx playwright test e2e/a11y-storefront.spec.ts                       (live: needs the stack)
 *      npx playwright test e2e/a11y-storefront.spec.ts --grep @stack-free
 */
import { test, expect, devices, type Page } from "@playwright/test"

const SHOP_SLUG = "mama-ades-kitchen"
const SHOP_NAME = "Mama Ade's Kitchen"

// ─── Probe 1: Chrome's name for every shop-card link (CDP) ────────────────────────────────────

interface AxLink {
  href: string
  name: string
  /** The text of the card's own heading / name element, read from the DOM. */
  cardName: string
}

/**
 * Every link in Chrome's accessibility tree whose href is a single shop page (`/shop/{slug}`), with
 * the name CHROME computed for it and the text of the card's name element (`h2`, or the element the
 * link's aria-labelledby points at when there is no h2). Read with `Accessibility.getFullAXTree`;
 * each AX node is resolved back to its DOM node to read the href.
 */
async function shopCardLinksFromChrome(page: Page): Promise<AxLink[]> {
  const cdp = await page.context().newCDPSession(page)
  try {
    await cdp.send("DOM.enable")
    await cdp.send("Accessibility.enable")
    const { nodes } = (await cdp.send("Accessibility.getFullAXTree")) as {
      nodes: Array<{
        ignored?: boolean
        role?: { value?: string }
        name?: { value?: string }
        backendDOMNodeId?: number
      }>
    }
    const links: AxLink[] = []
    for (const node of nodes) {
      if (node.ignored || node.role?.value !== "link" || !node.backendDOMNodeId) continue
      const { object } = (await cdp.send("DOM.resolveNode", { backendNodeId: node.backendDOMNodeId })) as {
        object: { objectId?: string }
      }
      if (!object.objectId) continue
      const { result } = (await cdp.send("Runtime.callFunctionOn", {
        objectId: object.objectId,
        returnByValue: true,
        functionDeclaration: `function () {
          const href = this.getAttribute("href") || ""
          const ref = this.getAttribute("aria-labelledby")
          const named = (ref && document.getElementById(ref)) || this.querySelector("h2")
          return { href, cardName: named ? (named.textContent || "").replace(/\\s+/g, " ").trim() : "" }
        }`,
      })) as { result: { value?: { href: string; cardName: string } } }
      const v = result.value
      if (!v || !/^\/shop\/[^/?#]+$/.test(v.href)) continue
      links.push({ href: v.href, name: (node.name?.value ?? "").replace(/\s+/g, " ").trim(), cardName: v.cardName })
    }
    return links
  } finally {
    await cdp.detach()
  }
}

/** The F2 assertion: there are cards, and Chrome names each one exactly by its shop. */
function expectCardsNamedByShop(links: AxLink[]): void {
  expect(links.length, "no shop-card links in Chrome's accessibility tree").toBeGreaterThan(0)
  for (const l of links) {
    expect(l.cardName, `card ${l.href} has no name element to compare with`).not.toBe("")
    expect(l.name, `Chrome's name for the card link ${l.href}`).toBe(l.cardName)
  }
}

// ─── Probe 2: focus and the basket live region ────────────────────────────────────────────────

/** The aria-label (or text) of the focused element; "BODY" when focus fell to the document. */
async function focusedLabel(page: Page): Promise<string> {
  return page.evaluate(() => {
    const el = document.activeElement
    if (!el || el === document.body) return "BODY"
    return el.getAttribute("aria-label") || (el.textContent || "").replace(/\s+/g, " ").trim()
  })
}

/**
 * Install the announcement recorder (the 37-51 recorder): a MutationObserver over the document that
 * records the text of each `[role=status],[aria-live]` element whenever it changes to something
 * non-empty. Re-installing resets.
 */
async function installAnnouncementRecorder(page: Page): Promise<void> {
  await page.evaluate(() => {
    type W = Window & { __a11yRecorder?: MutationObserver; __announcements?: string[] }
    const w = window as W
    w.__a11yRecorder?.disconnect()
    w.__announcements = []
    const last = new WeakMap<Element, string>()
    const norm = (el: Element) => (el.textContent ?? "").replace(/\s+/g, " ").trim()
    document.querySelectorAll("[role=status],[aria-live]").forEach((el) => last.set(el, norm(el)))
    w.__a11yRecorder = new MutationObserver(() => {
      document.querySelectorAll("[role=status],[aria-live]").forEach((el) => {
        const text = norm(el)
        if (last.get(el) !== text) {
          last.set(el, text)
          if (text) w.__announcements!.push(text)
        }
      })
    })
    w.__a11yRecorder.observe(document.documentElement, { childList: true, characterData: true, subtree: true })
  })
}

async function recordedAnnouncements(page: Page): Promise<string[]> {
  return page.evaluate(() => (window as Window & { __announcements?: string[] }).__announcements ?? [])
}

/** The F3 assertion: `expected` was announced, and no announcement said a count twice ("1 1 item"). */
function expectBasketAnnounced(records: string[], expected: string): void {
  expect(
    records.some((r) => r === expected),
    `no live-region announcement was exactly "${expected}"; recorded: ${JSON.stringify(records)}`,
  ).toBe(true)
  const doubled = records.filter((r) => /\b(\d+)\s+\1 items? in basket/.test(r))
  expect(doubled, "an announcement said the basket count twice").toEqual([])
}

/** Remove every live region from the page, as a regression that dropped them would. */
async function removeLiveRegions(page: Page): Promise<number> {
  return page.evaluate(() => {
    const regions = Array.from(document.querySelectorAll("[role=status],[aria-live]"))
    regions.forEach((el) => el.remove())
    return regions.length
  })
}

// ─── Probe 3: the cookie notice never covers the focused element ──────────────────────────────

const NOTICE = 'section[aria-label="Cookie notice"], section[aria-label="Cookie choices"]'

interface TabStop {
  index: number
  label: string
  inNotice: boolean
  obscured: boolean
  scrollY: number
}

/**
 * Press Tab `stops` times from the top of the page and, at each stop, intersect the focused
 * element's rect with the notice's. A stop INSIDE the notice is its own control and cannot be
 * obscured by it. Returns every stop, so the caller can assert both the overlap and its non-vacuity
 * (the walk reached below the fold, where the notice sits).
 */
async function walkTabStops(page: Page, stops: number): Promise<TabStop[]> {
  const out: TabStop[] = []
  for (let i = 1; i <= stops; i++) {
    await page.keyboard.press("Tab")
    out.push(await tabStopNow(page, i))
  }
  return out
}

async function tabStopNow(page: Page, index: number): Promise<TabStop> {
  return page.evaluate(
    ({ index, selector }) => {
      const el = document.activeElement as HTMLElement | null
      const notice = document.querySelector(selector) as HTMLElement | null
      const label = !el || el === document.body ? "BODY" : el.getAttribute("aria-label") || (el.textContent || "").replace(/\s+/g, " ").trim().slice(0, 60)
      const inNotice = !!(el && notice && notice.contains(el))
      let obscured = false
      if (el && el !== document.body && notice && !inNotice) {
        const a = el.getBoundingClientRect()
        // The DRAWN card, not the full-bleed, pointer-transparent wrapper.
        const card = (notice.querySelector(".pointer-events-auto") as HTMLElement | null) ?? notice
        const b = card.getBoundingClientRect()
        obscured = a.width > 0 && a.height > 0 && a.top < b.bottom && a.bottom > b.top && a.left < b.right && a.right > b.left
      }
      return { index, label, inNotice, obscured, scrollY: window.scrollY }
    },
    { index, selector: NOTICE },
  )
}

// ─── Probe 4: large text wraps ────────────────────────────────────────────────────────────────

/**
 * The F6 probe: the document does not scroll sideways, and none of `selector`'s elements (filtered
 * to those whose text is one of `texts`, when given) is clipped — no `text-overflow: ellipsis` on a
 * box that hides overflow, no `white-space: nowrap`, and never wider than its own box. Returns the
 * offenders, so an arm can assert it found one. "no element" is itself an offender (non-vacuity).
 */
async function clippedText(page: Page, selector: string, texts?: string[]): Promise<string[]> {
  return page.evaluate(
    ({ selector, texts }) => {
      const offenders: string[] = []
      const doc = document.documentElement
      if (doc.scrollWidth > doc.clientWidth) offenders.push(`document scrollWidth ${doc.scrollWidth} > clientWidth ${doc.clientWidth}`)
      const els = Array.from(document.querySelectorAll(selector)).filter((el) =>
        texts ? texts.includes((el.textContent ?? "").replace(/\s+/g, " ").trim()) : true,
      )
      if (els.length === 0) offenders.push(`no element matches ${selector}${texts ? ` with text ${JSON.stringify(texts)}` : ""}`)
      for (const el of els) {
        const cs = getComputedStyle(el)
        const name = (el.textContent ?? "").replace(/\s+/g, " ").trim().slice(0, 60)
        if (cs.textOverflow === "ellipsis" && cs.overflow !== "visible") offenders.push(`"${name}" text-overflow ellipsis`)
        if (cs.whiteSpace === "nowrap") offenders.push(`"${name}" white-space nowrap`)
        if (el.scrollWidth > el.clientWidth + 1) offenders.push(`"${name}" scrollWidth ${el.scrollWidth} > clientWidth ${el.clientWidth}`)
      }
      return offenders
    },
    { selector, texts },
  )
}

/** 360px wide, root font 130% — the UXT-080 condition. */
async function largeTextNarrow(page: Page): Promise<void> {
  await page.setViewportSize({ width: 360, height: 780 })
  await page.addStyleTag({ content: "html { font-size: 130% !important; }" })
}

/** The menu's first addable dish: its Add button and the dish name the button is named after. */
async function firstAddableDish(page: Page): Promise<{ dish: string; addLabel: string }> {
  const add = page.locator('button[aria-label^="Add "][aria-label$=" to basket"]').first()
  await expect(add, "no addable dish on the menu").toBeVisible({ timeout: 20_000 })
  const addLabel = (await add.getAttribute("aria-label"))!
  const dish = addLabel.replace(/^Add /, "").replace(/ to basket$/, "")
  return { dish, addLabel }
}

// ─── The live storefront (run in 37-53) ───────────────────────────────────────────────────────

/**
 * Soft where a defect would otherwise hide the next one: on the pre-fix tree focus falls to <body>
 * AND the announcement is wrong, the shop name AND the basket names are clipped, and the notice is
 * both last in tab order AND over the focused element. A RED run reports every one of them.
 */
const soft = expect.configure({ soft: true })

test.describe("live storefront @mobile-only", () => {
  test("F2: Chrome names every /shop card link by its shop; INSTRUMENT: stripped aria-labelledby is caught", async ({ page }) => {
    await page.goto("/shop")
    await expect(page.getByRole("heading", { level: 2, name: SHOP_NAME }).first()).toBeVisible({ timeout: 20_000 })

    const links = await shopCardLinksFromChrome(page)
    expectCardsNamedByShop(links)
    expect(links.map((l) => l.name), `${SHOP_NAME} is listed`).toContain(SHOP_NAME)

    // INSTRUMENT: the same real page with the card's aria-labelledby removed. Chrome's name for it is
    // no longer the shop, and the assertion must throw.
    const stripped = await page.evaluate((slug) => {
      const a = document.querySelector(`a[href="/shop/${slug}"]`)
      if (!a?.hasAttribute("aria-labelledby")) return false
      a.removeAttribute("aria-labelledby")
      return true
    }, SHOP_SLUG)
    expect(stripped, "the card had aria-labelledby to strip").toBe(true)
    const after = await shopCardLinksFromChrome(page)
    expect(after.find((l) => l.href === `/shop/${SHOP_SLUG}`)?.name).not.toBe(SHOP_NAME)
    expect(() => expectCardsNamedByShop(after), "the name probe missed a card named by something else").toThrow()
  })

  test("F3: Add moves focus to '+' and announces the dish once; the last Remove returns focus to Add; INSTRUMENT: no region, no record", async ({ page }) => {
    await page.goto(`/shop/${SHOP_SLUG}`)
    const { dish, addLabel } = await firstAddableDish(page)
    await installAnnouncementRecorder(page)

    await page.locator(`button[aria-label="${addLabel}"]`).first().focus()
    await page.keyboard.press("Enter")
    await soft.poll(() => focusedLabel(page), { message: "focus after Add", timeout: 5_000 }).toBe(`Add one more ${dish}`)
    await soft
      .poll(async () => (await recordedAnnouncements(page)).includes(`${dish} added. 1 item in basket.`), { timeout: 5_000 })
      .toBe(true)
    expectBasketAnnounced(await recordedAnnouncements(page), `${dish} added. 1 item in basket.`)

    // Back one stop to the stepper's remove control (the keyboard path), then remove the last unit.
    await page.keyboard.press("Shift+Tab")
    soft(await focusedLabel(page), "Shift+Tab from '+' reaches the remove control").toBe(`Remove ${dish} from basket`)
    // The remove control of the SAME card. A featured dish renders twice, the Popular rail first, and
    // the two steppers share their labels (only the rail's Add carries " (featured)"), so `.first()`
    // is the RAIL copy: pressing it returned focus, correctly, to the rail's own Add and failed this
    // assertion at the 37-53 gate. `firstAddableDish` pressed the menu copy, which comes after the rail.
    await page.locator(`button[aria-label="Remove ${dish} from basket"]`).last().focus()
    await page.keyboard.press("Enter")
    await expect.poll(() => focusedLabel(page), { message: "focus after the last Remove" }).toBe(addLabel)
    await expect.poll(async () => (await recordedAnnouncements(page)).includes(`${dish} removed. Basket is empty.`)).toBe(true)
    expectBasketAnnounced(await recordedAnnouncements(page), `${dish} removed. Basket is empty.`)

    // INSTRUMENT: with the live regions gone, a real Add records nothing and the assertion throws.
    expect(await removeLiveRegions(page), "there were live regions to remove").toBeGreaterThan(0)
    await installAnnouncementRecorder(page)
    await page.locator(`button[aria-label="${addLabel}"]`).first().focus()
    await page.keyboard.press("Enter")
    await expect.poll(() => focusedLabel(page)).toBe(`Add one more ${dish}`)
    expect(await recordedAnnouncements(page)).toEqual([])
    expect(() => expectBasketAnnounced([], `${dish} added. 1 item in basket.`)).toThrow()
    // And the focus probe can report a lost focus.
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur())
    expect(await focusedLabel(page)).toBe("BODY")
  })

  test("F6: at 360px with 130% text the shop name and the basket names wrap; INSTRUMENT: a forced ellipsis is caught", async ({ page }) => {
    await page.goto(`/shop/${SHOP_SLUG}`)
    const { dish, addLabel } = await firstAddableDish(page)
    await page.locator(`button[aria-label="${addLabel}"]`).first().click()
    // The stepper's remove control is named the same before and after 37-52, so this waits on the
    // basket, not on F3's renamed "+".
    await expect(page.locator(`button[aria-label="Remove ${dish} from basket"]`).first()).toBeVisible()

    await largeTextNarrow(page)
    soft(await clippedText(page, "h1", [SHOP_NAME]), "the storefront <h1>").toEqual([])

    await page.goto(`/shop/${SHOP_SLUG}/cart`)
    await largeTextNarrow(page)
    await expect(page.getByRole("heading", { name: dish }).first()).toBeVisible({ timeout: 15_000 })
    expect(await clippedText(page, "h3", [dish]), "the basket item name").toEqual([])

    // INSTRUMENT: the same basket name forced to one clipped line is reported.
    await page.addStyleTag({ content: "main h3 { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }" })
    expect((await clippedText(page, "h3", [dish])).length).toBeGreaterThan(0)
  })
})

test.describe("live storefront on an iPhone 13 @mobile-only", () => {
  // The iPhone 13 profile, in the project's Chromium (the device descriptor defaults to WebKit, which
  // has no CDP and is not what the persona used).
  const { defaultBrowserType: _webkit, ...iPhone13 } = devices["iPhone 13"]
  void _webkit
  test.use(iPhone13)

  test("F5: the notice is met first and never covers the focused element in the first 30 tab stops; INSTRUMENT: a notice forced over focus is caught", async ({ page }) => {
    await page.goto(`/shop/${SHOP_SLUG}`)
    await firstAddableDish(page)
    const notice = page.locator(NOTICE)
    await expect(notice, "the cookie notice must be showing for this test to mean anything").toBeVisible()

    const stops = await walkTabStops(page, 30)
    const gotIt = stops.find((s) => s.label === "Got it")
    soft(gotIt?.index ?? Infinity, `"Got it" among the first stops; walked: ${JSON.stringify(stops.map((s) => s.label))}`).toBeLessThanOrEqual(3)

    const obscured = stops.filter((s) => s.obscured)
    soft(obscured, `focused element under the notice: ${JSON.stringify(obscured)}`).toEqual([])
    // Non-vacuity: most stops were page content, and the walk went below the fold where the notice is.
    expect(stops.filter((s) => !s.inNotice && s.label !== "BODY").length).toBeGreaterThanOrEqual(20)
    expect(Math.max(...stops.map((s) => s.scrollY)), "the walk never scrolled, so it never reached the notice's band").toBeGreaterThan(0)

    // INSTRUMENT: put the notice's card exactly over the focused element; the probe must report it.
    await page.evaluate((selector) => {
      const el = document.activeElement as HTMLElement
      const notice = document.querySelector(selector) as HTMLElement
      const r = el.getBoundingClientRect()
      notice.style.top = `${Math.max(0, r.top - 8)}px`
      notice.style.bottom = "auto"
    }, NOTICE)
    const forced = await tabStopNow(page, 31)
    expect(forced.inNotice).toBe(false)
    expect(forced.obscured, "the overlap probe missed a notice drawn over the focused element").toBe(true)
  })
})

// ─── The probes can fail (no server) ──────────────────────────────────────────────────────────

const CARDS = `
  <main>
    <a id="named" href="/shop/named-kitchen" aria-labelledby="named-h" aria-describedby="named-d">
      <article><img alt="Named Kitchen banner" src="data:,"><span id="named-d">Open</span><h2 id="named-h">Named Kitchen</h2><p>Jollof · Halal</p></article>
    </a>
    <a id="bare" href="/shop/bare-kitchen">
      <article><img alt="Bare Kitchen banner" src="data:,"><span>Open</span><h2>Bare Kitchen</h2><p>Grill</p></article>
    </a>
  </main>`

const LONG = "Jollof Rice with Fried Plantain, Peppered Chicken and Coleslaw"
/** Without it the mobile project lays a fixture out on a 980px layout viewport, as a phone would. */
const VIEWPORT_META = '<meta name="viewport" content="width=device-width, initial-scale=1">'

test.describe("instruments can fail @stack-free", () => {
  test("CDP name probe: a labelled card link reads as its shop; an unlabelled <a><article> does not", async ({ page }) => {
    await page.setContent(CARDS)
    const links = await shopCardLinksFromChrome(page)
    const named = links.find((l) => l.href === "/shop/named-kitchen")
    const bare = links.find((l) => l.href === "/shop/bare-kitchen")
    // Positive control: Chrome's tree carries both links and names the labelled one by its heading.
    expect(named?.name).toBe("Named Kitchen")
    expect(bare, "the unlabelled link is in Chrome's tree").toBeTruthy()
    // Broken input: the article-wrapping link without aria-labelledby is not named by its shop.
    expect(bare!.name).not.toBe("Bare Kitchen")
    expect(() => expectCardsNamedByShop(links)).toThrow()
    expect(() => expectCardsNamedByShop(links.filter((l) => l === named))).not.toThrow()
  })

  test("recorder and announcement assertion: the new sentence passes; the old doubled count and silence fail", async ({ page }) => {
    await page.setContent(`<a id="basket" href="/cart" aria-live="polite"><span id="badge"></span><span id="sr"></span></a>
      <span id="status" role="status" aria-live="polite"></span>`)
    await installAnnouncementRecorder(page)
    // The old link-as-live-region: badge "1" + "1 item in basket".
    await page.evaluate(() => {
      document.getElementById("badge")!.textContent = "1"
      document.getElementById("sr")!.textContent = " 1 item in basket"
    })
    await expect.poll(async () => (await recordedAnnouncements(page)).length).toBeGreaterThan(0)
    const old = await recordedAnnouncements(page)
    expect(() => expectBasketAnnounced(old, "Mango Lassi added. 1 item in basket.")).toThrow()
    // The new region's sentence.
    await page.evaluate(() => {
      document.getElementById("status")!.textContent = "Mango Lassi added. 1 item in basket."
    })
    await expect.poll(async () => (await recordedAnnouncements(page)).includes("Mango Lassi added. 1 item in basket.")).toBe(true)
    expect(() =>
      expectBasketAnnounced(["Mango Lassi added. 1 item in basket."], "Mango Lassi added. 1 item in basket."),
    ).not.toThrow()
    // A doubled count anywhere in the record fails even beside the right sentence.
    expect(() =>
      expectBasketAnnounced(["1 1 item in basket", "Mango Lassi added. 1 item in basket."], "Mango Lassi added. 1 item in basket."),
    ).toThrow()
    // Silence fails.
    expect(() => expectBasketAnnounced([], "Mango Lassi added. 1 item in basket.")).toThrow()
  })

  test("overlap probe: a fixed notice over the focused element is reported; a clear one is not", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 664 })
    await page.setContent(`${VIEWPORT_META}<main><button id="a">Above</button><div style="height:520px"></div><button id="b" style="display:block">Under the notice</button></main>
      <section aria-label="Cookie notice" style="position:fixed;left:0;right:0;bottom:0">
        <div class="pointer-events-auto" style="height:200px;background:#3a0b0d"><button>Got it</button></div>
      </section>`)
    await page.locator("#a").focus()
    expect((await tabStopNow(page, 1)).obscured, "positive control: a button above the notice is clear").toBe(false)
    // #b sits at ~y=550..570, inside the 200px band (464..664) at the bottom of a 664px viewport.
    await page.evaluate(() => document.getElementById("b")!.focus({ preventScroll: true }))
    const under = await tabStopNow(page, 2)
    expect(under.label).toBe("Under the notice")
    expect(under.obscured, "the probe missed a button drawn under the notice").toBe(true)
    // The notice's own control is never "obscured" by the notice.
    await page.getByRole("button", { name: "Got it" }).focus()
    expect(await tabStopNow(page, 3)).toMatchObject({ inNotice: true, obscured: false })
  })

  test("large-text probe: a wrapping name passes; truncate, nowrap and a sideways-scrolling page fail", async ({ page }) => {
    await page.setContent(`${VIEWPORT_META}<main style="width:200px">
      <h3 id="wrap" style="overflow-wrap:break-word">${LONG}</h3>
      <h3 id="trunc" style="overflow:hidden;white-space:nowrap;text-overflow:ellipsis">${LONG} </h3>
    </main>`)
    await largeTextNarrow(page)
    expect(await clippedText(page, "#wrap")).toEqual([])
    const trunc = await clippedText(page, "#trunc")
    expect(trunc.some((o) => o.includes("ellipsis")), JSON.stringify(trunc)).toBe(true)
    expect(trunc.some((o) => o.includes("nowrap")), JSON.stringify(trunc)).toBe(true)
    expect(await clippedText(page, "#missing"), "an absent element is itself an offender").toEqual(["no element matches #missing"])
    await page.evaluate(() => {
      const wide = document.createElement("div")
      wide.style.width = "900px"
      wide.textContent = "wide"
      document.body.appendChild(wide)
    })
    expect((await clippedText(page, "#wrap")).some((o) => o.startsWith("document scrollWidth"))).toBe(true)
  })
})
