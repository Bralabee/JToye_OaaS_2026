import {
  canEnhance,
  DESKTOP_MOTION_QUERY,
  prefersDesktopMotion,
  splitWords,
} from "@/lib/gsap-gate"

const HEADLINE = "Order from local kitchens. Or run yours."

describe("gsap-gate — prefersDesktopMotion", () => {
  it("enhances a wide desktop viewport with motion allowed", () => {
    expect(prefersDesktopMotion({ width: 1440, reducedMotion: false })).toBe(true)
  })

  it("does not enhance when reduced motion is requested", () => {
    expect(prefersDesktopMotion({ width: 1440, reducedMotion: true })).toBe(false)
  })

  it("does not enhance below the 768px breakpoint", () => {
    expect(prefersDesktopMotion({ width: 767, reducedMotion: false })).toBe(false)
  })

  it("enhances exactly at the 768px (Tailwind md) breakpoint", () => {
    expect(prefersDesktopMotion({ width: 768, reducedMotion: false })).toBe(true)
  })
})

describe("gsap-gate — DESKTOP_MOTION_QUERY", () => {
  it("targets md-and-up with a no-preference reduced-motion clause", () => {
    expect(DESKTOP_MOTION_QUERY).toContain("min-width: 768px")
    expect(DESKTOP_MOTION_QUERY).toContain("prefers-reduced-motion: no-preference")
  })
})

describe("gsap-gate — splitWords", () => {
  it("splits a headline into multiple .gsap-word spans without dropping text", () => {
    const h1 = document.createElement("h1")
    h1.textContent = HEADLINE
    const words = splitWords(h1)

    expect(words.length).toBeGreaterThan(1)
    for (const word of words) expect(word).toHaveClass("gsap-word")
    // Nothing dropped: concatenated textContent equals the original headline.
    expect(h1.textContent).toBe(HEADLINE)
  })

  it("is idempotent — calling twice does not double-wrap", () => {
    const h1 = document.createElement("h1")
    h1.textContent = HEADLINE

    const first = splitWords(h1)
    const second = splitWords(h1)

    expect(second.length).toBe(first.length)
    expect(h1.querySelectorAll(".gsap-word").length).toBe(first.length)
    expect(h1.textContent).toBe(HEADLINE)
  })

  it("preserves a nested child element's text inside word spans", () => {
    const h1 = document.createElement("h1")
    h1.appendChild(document.createTextNode("Order from local kitchens. "))
    const accent = document.createElement("span")
    accent.className = "accent"
    accent.textContent = "Or run yours."
    h1.appendChild(accent)

    splitWords(h1)

    const survivingAccent = h1.querySelector(".accent")
    expect(survivingAccent).not.toBeNull()
    // the accent element survives and its inner text is preserved…
    expect(survivingAccent!.textContent).toBe("Or run yours.")
    // …now wrapped in word spans inside the accent element
    expect(survivingAccent!.querySelectorAll(".gsap-word").length).toBeGreaterThan(1)
    // full round-trip: nothing dropped across the text node + child element
    expect(h1.textContent).toBe(HEADLINE)
  })
})

describe("gsap-gate — canEnhance", () => {
  const original = Object.getOwnPropertyDescriptor(window, "matchMedia")

  afterEach(() => {
    if (original) Object.defineProperty(window, "matchMedia", original)
    else delete (window as unknown as { matchMedia?: unknown }).matchMedia
  })

  it("is true when window.matchMedia is a function", () => {
    ;(window as unknown as { matchMedia: unknown }).matchMedia = jest.fn()
    expect(canEnhance()).toBe(true)
  })

  it("is false when window.matchMedia is absent (jsdom / SSR)", () => {
    delete (window as unknown as { matchMedia?: unknown }).matchMedia
    expect(canEnhance()).toBe(false)
  })
})
