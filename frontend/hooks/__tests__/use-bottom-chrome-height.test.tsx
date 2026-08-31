import { useRef } from "react"
import { act, render } from "@testing-library/react"

import {
  BOTTOM_CHROME_VAR,
  useBottomChromeHeight,
} from "@/hooks/use-bottom-chrome-height"

/**
 * R-07 (2026-08-31 customer-surface audit) — the published bottom-chrome
 * offset that keeps the cookie notice off the cart bar and the tab bar.
 *
 * ── THE HARNESS REPRODUCES THE INTEGRATION SHAPE, NOT A MOUNT/UNMOUNT PAIR ──
 * A co-located "mount, then unmount" test passes against the BROKEN
 * `useEffect(…, [])` implementation exactly as readily as against the correct
 * one, and therefore proves nothing about the defect. The real shape is
 * `FloatingCartBar`: mounted unconditionally, with the ref-bearing element
 * rendered CONDITIONALLY inside `<AnimatePresence>` on `itemCount > 0`. So the
 * harness below drives ONE mounted component whose ref-bearing child comes and
 * goes on a prop, and asserts a SEQUENCE across re-renders.
 *
 * Step 2 — "published after a RE-RENDER" — is the arm that fails against a
 * `[]`-dependency hook. It must be asserted after a re-render and never after a
 * fresh mount, or the whole file silently reverts to the vacuous shape and the
 * blocker this spec exists to close ships green.
 *
 * ── WHY offsetHeight IS STUBBED ─────────────────────────────────────────────
 * jsdom computes no layout and reports `offsetHeight` as 0 for EVERY element.
 * Without the stub the hook's "height 0 -> removeProperty" rule makes step 2
 * indistinguishable from step 1, and the whole suite goes green against a hook
 * that never publishes anything at all. The stub is what gives these arms a
 * fail direction. The REAL pixel value in a real browser remains the
 * orchestrator's check; nothing here claims it.
 */

const BAR_HEIGHT = 56

/** Mutable so a single test can simulate a breakpoint change. */
let stubbedHeight = BAR_HEIGHT

const originalOffsetHeight = Object.getOwnPropertyDescriptor(
  HTMLElement.prototype,
  "offsetHeight"
)

beforeEach(() => {
  stubbedHeight = BAR_HEIGHT
  Object.defineProperty(HTMLElement.prototype, "offsetHeight", {
    configurable: true,
    get() {
      return stubbedHeight
    },
  })
})

afterEach(() => {
  if (originalOffsetHeight) {
    Object.defineProperty(HTMLElement.prototype, "offsetHeight", originalOffsetHeight)
  } else {
    delete (HTMLElement.prototype as unknown as { offsetHeight?: unknown }).offsetHeight
  }
  document.documentElement.style.removeProperty(BOTTOM_CHROME_VAR)
})

/** What the page can actually read. "" is the absent state. */
function published(): string {
  return document.documentElement.style.getPropertyValue(BOTTOM_CHROME_VAR)
}

/**
 * `FloatingCartBar` in miniature: always mounted, ref-bearing child gated on a
 * prop that changes by RE-RENDER rather than by remount.
 */
function Bar({ present }: { present: boolean }) {
  const ref = useRef<HTMLDivElement>(null)
  useBottomChromeHeight(ref)
  return <div data-testid="bar-host">{present ? <div ref={ref} /> : null}</div>
}

describe("useBottomChromeHeight — the AnimatePresence sequence (R-07)", () => {
  it("publishes on APPEARANCE, clears on disappearance, and clears on unmount", () => {
    // 1. First render with the child ABSENT — exactly how FloatingCartBar
    //    mounts, with an empty basket and a null ref.
    const view = render(<Bar present={false} />)
    expect(published()).toBe("")

    // 2. RE-RENDER with the child present. THE LOAD-BEARING ARM: a
    //    `useEffect(…, [])` hook has already fired once at step 1 and will
    //    never fire again, so it publishes nothing here and this reds.
    view.rerender(<Bar present={true} />)
    expect(published()).toBe(`${BAR_HEIGHT}px`)

    // 3. RE-RENDER with the child gone again — basket emptied.
    view.rerender(<Bar present={false} />)
    expect(published()).toBe("")

    // 4. Unmount from that state.
    view.unmount()
    expect(published()).toBe("")
  })

  it("removes the property on unmount FROM A PUBLISHED STATE", () => {
    // Step 4 above unmounts from an already-cleared state, so it cannot see a
    // broken cleanup. This one can: a value left behind would push the notice
    // permanently off the bottom of the next page, which has no bottom bar.
    const view = render(<Bar present={true} />)
    expect(published()).toBe(`${BAR_HEIGHT}px`) // CONTROL: it really was set
    view.unmount()
    expect(published()).toBe("")
  })

  it("treats a zero-height (breakpoint-hidden) bar as no bar at all", () => {
    // `mobile-tab-bar` is `md:hidden` rather than conditionally rendered, so
    // its ref is ALWAYS attached and only its height goes to 0.
    stubbedHeight = 0
    render(<Bar present={true} />)
    expect(published()).toBe("")
  })

  it("re-measures on resize, with no re-render to prompt it", () => {
    // The md-crossing case. Loading at >=md and then narrowing causes NO
    // re-render, so without the resize listener the stale `0px` would persist
    // and the notice would sit under the tab bar.
    stubbedHeight = 0
    render(<Bar present={true} />)
    expect(published()).toBe("")

    stubbedHeight = BAR_HEIGHT
    act(() => {
      window.dispatchEvent(new Event("resize"))
    })
    expect(published()).toBe(`${BAR_HEIGHT}px`)
  })

  it("CONTROL: the harness's own stub really does report a height", () => {
    // Without this, every `published() === ""` above would be equally
    // satisfied by a stub that never took effect — the exact false negative
    // the stub exists to prevent.
    const { getByTestId } = render(<Bar present={true} />)
    expect(getByTestId("bar-host").firstElementChild).not.toBeNull()
    expect((getByTestId("bar-host").firstElementChild as HTMLElement).offsetHeight).toBe(
      BAR_HEIGHT
    )
  })
})
