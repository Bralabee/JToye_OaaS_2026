import { render } from "@testing-library/react"
import { __resetEntranceMountGateForTests, DESKTOP_MOTION_QUERY } from "@/lib/gsap-gate"

/**
 * R-03 on `/for-operators`: a LATE-arriving GSAP bundle must not retroactively
 * hide a headline and image the visitor is already reading.
 *
 * The other entrance tests run where jsdom has no matchMedia, so the scene
 * branch is unreachable from them. This file installs a matchMedia stub that
 * MATCHES the desktop-motion query BEFORE `@/lib/gsap` is first imported (gsap
 * registers its plugins only when matchMedia exists at import), so the real
 * scene is built against the real DOM and its inline styles can be read.
 *
 * What is asserted is the state the VISITOR would see, not the call sequence:
 * after the hook has run, is any headline word or the hero image frame hidden
 * (opacity 0 / visibility hidden)?
 *
 *  - ON TIME  (performance.now() inside the budget): the entrance plays, so the
 *    words ARE set hidden first. This arm is the non-vacuity control: it proves
 *    the instrument can see a hidden word, so "not hidden" below means something.
 *  - LATE     (performance.now() past the budget): nothing is hidden, the words
 *    are still split, and the verdict reads "skipped". Without the gate this arm
 *    fails: the `gsap.set(words, { autoAlpha: 0 })` blanks the painted headline.
 */

function installDesktopMatchMedia(): void {
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    writable: true,
    value: (query: string) => ({
      matches: query === DESKTOP_MOTION_QUERY,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }),
  })
}

function setNow(ms: number): void {
  jest.spyOn(performance, "now").mockReturnValue(ms)
}

function hidden(el: HTMLElement): boolean {
  return el.style.opacity === "0" || el.style.visibility === "hidden"
}

describe("useOperatorEntranceScene — late-arrival gate (R-03)", () => {
  let OperatorPitch: typeof import("@/components/marketing/operator-pitch").OperatorPitch

  beforeAll(async () => {
    installDesktopMatchMedia()
    // Imported AFTER the stub so gsap registers its plugins against it.
    OperatorPitch = (await import("@/components/marketing/operator-pitch")).OperatorPitch
  })

  beforeEach(() => {
    __resetEntranceMountGateForTests()
  })

  afterEach(() => {
    jest.restoreAllMocks()
  })

  it("CONTROL — on time: the entrance plays, so the words are set hidden first", () => {
    setNow(300)
    const { container } = render(<OperatorPitch />)
    const root = container.firstElementChild as HTMLElement
    const words = Array.from(container.querySelectorAll<HTMLElement>("[data-op-headline] .gsap-word"))

    expect(root).toHaveAttribute("data-motion-active", "desktop")
    expect(root).toHaveAttribute("data-entrance", "played")
    expect(words.length).toBeGreaterThanOrEqual(2)
    expect(words.every(hidden)).toBe(true)
    // The hero image is the LCP candidate: it is nudged (translate, 0.6 opacity), never hidden.
    const media = container.querySelector<HTMLElement>("[data-op-media]")!
    expect(media.style.transform).toMatch(/translate/)
    expect(hidden(media)).toBe(false)
  })

  it("late arrival: nothing already painted is hidden, words still split, verdict skipped", () => {
    setNow(5000)
    const { container } = render(<OperatorPitch />)
    const root = container.firstElementChild as HTMLElement
    const words = Array.from(container.querySelectorAll<HTMLElement>("[data-op-headline] .gsap-word"))

    // The visitor-visible state first: the scene ran, the spans exist (specs hang
    // assertions on them), and neither a word nor the hero image frame was blanked.
    expect(root).toHaveAttribute("data-motion-active", "desktop")
    expect(words.length).toBeGreaterThanOrEqual(2)
    expect(words.filter(hidden)).toHaveLength(0)
    const lateMedia = container.querySelector<HTMLElement>("[data-op-media]")!
    expect(hidden(lateMedia)).toBe(false)
    expect(lateMedia.style.transform).not.toMatch(/translate/)
    // Then the verdict the gate reports.
    expect(root).toHaveAttribute("data-entrance", "skipped")
  })

  it("a soft navigation (latch already consumed) is always safe, however late the clock", () => {
    setNow(300)
    render(<OperatorPitch />).unmount()
    // Second mount in the same document: performance.now() is now large, but the
    // hero DOM did not exist a moment ago, so there is nothing to blank.
    setNow(60000)
    const { container } = render(<OperatorPitch />)
    expect(container.firstElementChild).toHaveAttribute("data-entrance", "played")
  })
})
