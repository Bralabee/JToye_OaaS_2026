/**
 * PublicHeader wordmark home-target contract.
 *
 * The wordmark ALWAYS returns you to the landing page (/), from every public
 * surface. It used to be context-aware (/track homed to /shop), which meant the
 * logo landed you somewhere different depending on where you clicked it — the
 * reported inconsistent-nav bug. The storefront chrome (app/shop/layout.tsx)
 * points its own wordmark at / too, so the rule holds app-wide.
 */
import { render, screen } from "@testing-library/react"
import { usePathname } from "next/navigation"
import { PublicHeader } from "@/components/public/public-header"
import { WIDTH_TIER_CLASS } from "@/components/layout/content-tier"

const mockedPathname = usePathname as jest.Mock

/**
 * The stock scale token this rail carried before phase 35.
 *
 * Its ABSENCE is the assertion, and that is why the literal is written out here
 * rather than inferred: a rename that adds the tier class without removing this
 * one leaves TWO caps on one element. They resolve by cascade, the rendered
 * width happens to be identical, and the half-done state reads as correct in
 * review — so only an explicit absence check can see it.
 *
 * Not a tier literal, so plan 35-10's single-occurrence gate (which reads the
 * three `max-w-<tier>` strings, all of which live in
 * components/layout/content-tier.tsx) is unaffected by this constant.
 */
const SHED_WIDTH_TOKEN = "max-w-7xl"

function wordmarkHref() {
  // The wordmark is the only link whose accessible name contains "Toye".
  return screen.getByRole("link", { name: /toye/i }).getAttribute("href")
}

// Plain per-case blocks (deliberately not a table-driven `each` form): the
// docs-freshness metric gate counts literal block openings, so a table would
// make this file's coverage invisible to it.
describe("PublicHeader wordmark home target", () => {
  afterEach(() => mockedPathname.mockReturnValue("/"))

  function expectHomeFrom(pathname: string) {
    mockedPathname.mockReturnValue(pathname)
    render(<PublicHeader />)
    expect(wordmarkHref()).toBe("/")
  }

  it("marketing landing (/) -> wordmark links to /", () => {
    expectHomeFrom("/")
  })

  it("/for-operators -> wordmark links to /", () => {
    expectHomeFrom("/for-operators")
  })

  it("/track -> wordmark links to /, NOT /shop (it used to home to the shop)", () => {
    expectHomeFrom("/track")
  })

  it("/track/ORD-123 (nested track route) -> wordmark still links to /", () => {
    expectHomeFrom("/track/ORD-123")
  })

  it("/business-model-guide -> wordmark links to /", () => {
    expectHomeFrom("/business-model-guide")
  })

  it("/competitive -> wordmark links to /", () => {
    expectHomeFrom("/competitive")
  })
})

/**
 * The Marketing width tier on the shared header rail (ORCH-04, orchestrator
 * decision 2026-08-29 — CONTEXT.md section 4b).
 *
 * This rail's RENDERED WIDTH does not change: the Marketing tier is 1280px,
 * which is exactly what the stock token it replaces produced. What changes is
 * that the width is now DECLARED rather than incidental — the landing page's
 * content bands move to the same tier in this plan, and a shared declaration is
 * the only thing that stops the two drifting apart again (PATTERNS F-2: the
 * content was inset 128px from this very rail).
 *
 * Asserted through the rendered DOM, never against the source file: a grep over
 * a component that MENTIONS the tier in a comment — and this one now does —
 * passes with the class deleted.
 */
describe("PublicHeader declares the Marketing width tier (ORCH-04)", () => {
  /**
   * Non-vacuity is built into the lookup rather than bolted on: this throws if
   * no element declares a tier, so every assertion below is reached only after
   * the rail genuinely rendered.
   */
  function railOf(container: HTMLElement): HTMLElement {
    const rail = container.querySelector<HTMLElement>("[data-width-tier]")
    if (!rail) throw new Error("no element in the public header declares a width tier")
    return rail
  }

  it("declares the marketing tier on the rail that holds the wordmark", () => {
    const { container } = render(<PublicHeader />)
    const rail = railOf(container)

    expect(rail.getAttribute("data-width-tier")).toBe("marketing")
    // Proves it is THE RAIL and not some other element that happens to declare
    // a tier — the wordmark link lives inside it.
    expect(rail.querySelector('a[href="/"]')).not.toBeNull()
  })

  it("applies the Marketing tier class, imported from the vocabulary not restated", () => {
    const { container } = render(<PublicHeader />)
    // classList.contains is a TOKEN match. A substring search would be
    // satisfied by any longer class that happens to contain the string.
    expect(railOf(container).classList.contains(WIDTH_TIER_CLASS.marketing)).toBe(true)
  })

  it("no longer carries the stock scale token it was renamed from", () => {
    const { container } = render(<PublicHeader />)
    const rail = railOf(container)

    // CONTROL, run first: the instrument can see a class on this element at
    // all, so the absence below is about the token rather than about a broken
    // classList read or an element that rendered with no classes.
    expect(rail.classList.contains("mx-auto")).toBe(true)
    expect(rail.classList.contains(SHED_WIDTH_TOKEN)).toBe(false)
  })

  it("keeps every horizontal padding class the rail already had", () => {
    const { container } = render(<PublicHeader />)
    const rail = railOf(container)

    // The displaced-goods ledger, executable rather than asserted in prose:
    // the cap token is the ONLY thing this change is allowed to move.
    for (const preserved of ["mx-auto", "px-4", "sm:px-6", "lg:px-8"]) {
      expect(rail.classList.contains(preserved)).toBe(true)
    }
  })

  it("keeps the sticky chrome the rail sits inside", () => {
    render(<PublicHeader />)
    const banner = screen.getByRole("banner")

    for (const preserved of ["sticky", "top-0", "z-50", "border-b", "shadow-sm"]) {
      expect(banner.classList.contains(preserved)).toBe(true)
    }
  })
})
