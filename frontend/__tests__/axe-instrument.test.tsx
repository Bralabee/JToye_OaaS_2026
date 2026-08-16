/**
 * Falsification of the jsdom-layer accessibility instrument (LGL-02, phase 31).
 *
 * This file does NOT test the product. It tests the measuring device.
 *
 * WHY IT IS PERMANENT RATHER THAN A ONE-OFF BREAK ARM RECORDED IN A SUMMARY:
 * this project has already paid for the alternative. A naive axe run reported
 * "0 button-name violations" and the number was meaningless, because the tables
 * under test never mounted — axe had scanned an empty tree and reported a clean
 * bill of health for markup that did not exist. A zero from an instrument that
 * has only ever been observed passing is not evidence; it may be incapable of
 * failing. Everything downstream in this phase asserts zero violations on the
 * declared surfaces, so those assertions are worth exactly as much as this file.
 *
 * Three arms, and each one is load-bearing:
 *
 *   1. NON-VACUITY CONTROL — the fixture is proven to have actually rendered,
 *      by finding its nodes in the document BEFORE any scan. This is the arm
 *      that would have caught the historical false zero.
 *   2. BREAK ARM — a deliberately inaccessible fixture must produce violations,
 *      and the assertion names `image-alt` and `button-name` specifically. A
 *      bare `length > 0` would stay green if the engine reported some unrelated
 *      rule while silently losing the two we rely on.
 *   3. CLEAN ARM — zero must be REACHABLE. If no fixture can ever reach zero,
 *      then "0 violations" elsewhere in the phase means the gate is broken, not
 *      that the page is accessible.
 *
 * The fixtures are defined inline and depend on no application component, so no
 * unrelated refactor can quietly make this file vacuous. They are NOT in a
 * separate file under `__tests__/`, because jest.config.js `testMatch` picks up
 * every file in that directory and would run a fixtures module as an empty suite.
 *
 * Rule-set note: jest-axe@10 pins axe-core at exactly 4.10.2 and nests its own
 * copy, so this layer runs 4.10.2 rules while @axe-core/playwright runs the
 * pinned 4.13.0. The two a11y layers do not share one rule set.
 */
import { render, screen } from "@testing-library/react"
import { axe, toHaveNoViolations } from "jest-axe"

expect.extend(toHaveNoViolations)

// axe walks the whole subtree and jsdom has no layout, so give it room; the
// default 5s timeout is tight enough to flake on a loaded CI runner.
const AXE_TIMEOUT_MS = 30_000

/**
 * Deliberately inaccessible. Every defect here is intentional:
 *  - `img` with no `alt`            -> image-alt
 *  - `button` with no accessible name -> button-name
 *  - `a` whose only child is an empty text node -> link-name
 */
function BrokenFixture() {
  return (
    <div>
      {/* eslint-disable-next-line @next/next/no-img-element, jsx-a11y/alt-text */}
      <img src="/broken.png" data-testid="probe-image" />
      <button type="button" data-testid="probe-button" />
      <a href="/somewhere" data-testid="probe-link">
        {""}
      </a>
    </div>
  )
}

/** The same three control types, each given a real accessible name. */
function CleanFixture() {
  return (
    <div>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/clean.png" alt="A jollof rice bowl" data-testid="probe-image" />
      <button type="button" data-testid="probe-button">
        Add to basket
      </button>
      <a href="/somewhere" data-testid="probe-link">
        View the full menu
      </a>
    </div>
  )
}

describe("jest-axe instrument falsification (LGL-02)", () => {
  it(
    "renders the broken fixture at all — the instrument can see the nodes",
    () => {
      const { container } = render(<BrokenFixture />)

      // The historical false zero came from scanning a tree that never
      // mounted. Prove the nodes exist BEFORE trusting anything axe says
      // about them.
      expect(screen.getByTestId("probe-image")).toBeInTheDocument()
      expect(screen.getByTestId("probe-button")).toBeInTheDocument()
      expect(screen.getByTestId("probe-link")).toBeInTheDocument()

      // And prove it via the same container object the scan is handed, not
      // just via the global screen — a scan over a detached container would
      // still return zero while `screen` looked healthy.
      expect(container.querySelectorAll("img, button, a")).toHaveLength(3)
    },
    AXE_TIMEOUT_MS
  )

  it(
    "BREAK ARM: reports violations on the broken fixture, naming image-alt and button-name",
    async () => {
      const { container } = render(<BrokenFixture />)
      expect(screen.getByTestId("probe-image")).toBeInTheDocument()

      const results = await axe(container)
      const ids = results.violations.map((v) => v.id)

      // A count alone is too weak: it would survive the engine losing
      // image-alt as long as it reported anything else at all.
      expect(results.violations.length).toBeGreaterThan(0)
      expect(ids).toContain("image-alt")
      expect(ids).toContain("button-name")
    },
    AXE_TIMEOUT_MS
  )

  it(
    "CLEAN ARM: reaches zero violations on an accessible fixture",
    async () => {
      const { container } = render(<CleanFixture />)

      // Same non-vacuity control on this side: a zero over an empty tree is
      // exactly the failure this file exists to make impossible.
      expect(screen.getByTestId("probe-image")).toBeInTheDocument()
      expect(container.querySelectorAll("img, button, a")).toHaveLength(3)

      const results = await axe(container)
      expect(results).toHaveNoViolations()
    },
    AXE_TIMEOUT_MS
  )
})
