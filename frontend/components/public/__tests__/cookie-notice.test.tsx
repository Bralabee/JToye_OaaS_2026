/**
 * The essential-cookies notice and the dormant consent banner (LGL-01, S1).
 *
 * Two things are being defended here and they pull in opposite directions.
 *
 * 1. THE COPY IS LEGALLY OPERATIVE, so it is asserted as FIXED STRINGS. A
 *    paraphrase is the realistic failure mode — someone "improves" the wording —
 *    and a role query cannot see it: `getByRole("button", { name: /got it/i })`
 *    is equally happy with copy that has quietly started claiming something the
 *    platform cannot stand behind. In particular "cookies and browser storage"
 *    must survive, because under PECR `localStorage` is storage on terminal
 *    equipment exactly as a cookie is, and this site uses it for the basket.
 *
 * 2. THE BANNER IS DORMANT, so "it does not render" is worthless on its own —
 *    it is satisfied by a banner that can never render at all. Every dormancy
 *    assertion is therefore paired with a fixture category that makes the banner
 *    actually appear. Same two-arm shape as the consent gate itself.
 */
import { render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { axe, toHaveNoViolations } from "jest-axe"

import { CookieNotice } from "@/components/public/cookie-notice"
import { ConsentBanner } from "@/components/public/consent-banner"
import {
  COOKIE_NOTICE_ACK_KEY,
  COOKIE_POLICY_VERSION,
  register,
} from "@/lib/consent"

expect.extend(toHaveNoViolations)

const AXE_TIMEOUT_MS = 30_000

/** The verbatim body. Any drift here is a legal-copy change, not a typo fix. */
const BODY_COPY =
  "We only use cookies and browser storage that are strictly necessary to run this site — keeping you signed in, remembering what is in your basket, and keeping your order secure. We do not use advertising or analytics cookies, so there is nothing here to accept or reject."

const FIXTURE = {
  id: "fixture-analytics",
  essential: false,
  label: "Fixture analytics",
  purpose: "Exists only so the dormant banner can be seen at all.",
} as const

beforeEach(() => {
  window.localStorage.clear()
})

describe("the notice a first-time visitor sees", () => {
  it("renders on a first visit with the exact contracted copy", () => {
    render(<CookieNotice />)

    expect(screen.getByText("Cookies on J'Toye")).toBeInTheDocument()
    // Fixed string, not a regex fragment: the whole sentence is the disclosure.
    expect(screen.getByText(BODY_COPY)).toBeInTheDocument()
  })

  it("never claims 'cookies only' while localStorage is in use", () => {
    render(<CookieNotice />)
    const notice = screen.getByRole("region", { name: "Cookie notice" })
    expect(notice.textContent).toContain("cookies and browser storage")
    expect(notice.textContent).not.toMatch(/cookies only/i)
  })

  it("carries none of the barred dark-pattern preambles", () => {
    render(<CookieNotice />)
    const text = screen.getByRole("region", { name: "Cookie notice" }).textContent ?? ""
    expect(text).not.toMatch(/we value your privacy/i)
    expect(text).not.toMatch(/by continuing/i)
  })

  it("is a labelled section — not a dialog, not an alertdialog", () => {
    render(<CookieNotice />)

    // A labelled <section> exposes the `region` role. Asserting the ROLE rather
    // than the tag name is what makes this meaningful to a screen-reader user.
    const notice = screen.getByRole("region", { name: "Cookie notice" })
    expect(notice.tagName).toBe("SECTION")

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument()
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument()
    expect(notice).not.toHaveAttribute("aria-modal")
  })

  it("does not trap focus or lock scrolling", () => {
    render(
      <>
        <CookieNotice />
        <a href="/somewhere-else">A link on the page behind the notice</a>
      </>
    )

    // Nothing may pin the document — a consent wall is exactly what S1 forbids.
    expect(document.body.style.overflow).not.toBe("hidden")
    expect(document.documentElement.style.overflow).not.toBe("hidden")
    expect(document.body.className).not.toMatch(/overflow-hidden/)

    // And the page behind it is still reachable.
    expect(
      screen.getByRole("link", { name: "A link on the page behind the notice" })
    ).toBeInTheDocument()
  })

  it("links to the cookie policy", () => {
    render(<CookieNotice />)
    const link = screen.getByRole("link", { name: "Cookie policy" })
    // The ROUTE is created by 31-11 in this same wave; this asserts the href
    // string only. That the route resolves 200 belongs to 31-17.
    expect(link).toHaveAttribute("href", "/legal/cookies")
  })

  it("reaches both controls by keyboard, each with a visible focus indicator", async () => {
    const user = userEvent.setup()
    render(<CookieNotice />)

    const dismiss = screen.getByRole("button", { name: "Got it" })
    const link = screen.getByRole("link", { name: "Cookie policy" })

    await user.tab()
    expect(document.activeElement).toBe(dismiss)
    await user.tab()
    expect(document.activeElement).toBe(link)

    // jsdom computes no styles, so the focus RING is asserted structurally.
    // Stated plainly rather than dressed up as a rendered measurement: this
    // proves the class is applied, not that it paints at 3:1. The ring is
    // cream/white because `--ring` is orange-700 and orange-700 on the oxblood
    // #3A0B0D surface is a weak boundary.
    for (const el of [dismiss, link]) {
      expect(el.className).toMatch(/focus-visible:ring-2/)
      expect(el.className).toMatch(/focus-visible:ring-cream|focus-visible:ring-white/)
    }
  })

  it("gives the dismiss control a 44px minimum target", () => {
    render(<CookieNotice />)
    // Structural again (no layout in jsdom): `min-h-11` / `min-w-11` are 2.75rem
    // = 44px in this Tailwind scale.
    const dismiss = screen.getByRole("button", { name: "Got it" })
    expect(dismiss.className).toMatch(/min-h-11/)
    expect(dismiss.className).toMatch(/min-w-11/)
  })
})

describe("dismissal and the version that re-shows it", () => {
  it("dismisses on 'Got it' and persists the CURRENT policy version", async () => {
    const user = userEvent.setup()
    render(<CookieNotice />)

    await user.click(screen.getByRole("button", { name: "Got it" }))

    expect(screen.queryByRole("region", { name: "Cookie notice" })).not.toBeInTheDocument()
    expect(window.localStorage.getItem(COOKIE_NOTICE_ACK_KEY)).toBe(COOKIE_POLICY_VERSION)
  })

  it("stays dismissed on a fresh mount at the same version", () => {
    window.localStorage.setItem(COOKIE_NOTICE_ACK_KEY, COOKIE_POLICY_VERSION)
    render(<CookieNotice />)
    expect(screen.queryByRole("region", { name: "Cookie notice" })).not.toBeInTheDocument()
  })

  it("RE-SHOWS after a policy version bump", () => {
    // The paired arm for the test above. Without it, "stays dismissed" is also
    // satisfied by a notice that can never render again for anyone.
    window.localStorage.setItem(COOKIE_NOTICE_ACK_KEY, "an-older-policy-version")
    render(<CookieNotice />)
    expect(screen.getByRole("region", { name: "Cookie notice" })).toBeInTheDocument()
  })
})

describe("the dormant consent banner", () => {
  it("does NOT render today, because the shipped config registers no non-essential category", () => {
    render(<ConsentBanner />)
    expect(screen.queryByRole("region", { name: "Cookie choices" })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Accept all" })).not.toBeInTheDocument()
  })

  it("DOES render once a non-essential category is registered", () => {
    const unregister = register(FIXTURE)
    try {
      render(<ConsentBanner />)
      expect(screen.getByRole("region", { name: "Cookie choices" })).toBeInTheDocument()
    } finally {
      unregister()
    }
  })

  it("presents 'Reject all' as the SAME component at the SAME size as 'Accept all'", () => {
    const unregister = register(FIXTURE)
    try {
      render(<ConsentBanner />)
      const accept = screen.getByRole("button", { name: "Accept all" })
      const rejectBtn = screen.getByRole("button", { name: "Reject all" })

      // Structural, not by eye: same element type, identical class list (so
      // identical size and weight), and adjacent in the DOM. A "Reject" text
      // link beside a filled "Accept" button fails all three.
      expect(rejectBtn.tagName).toBe(accept.tagName)
      expect(rejectBtn.className).toBe(accept.className)
      expect(accept.nextElementSibling).toBe(rejectBtn)
    } finally {
      unregister()
    }
  })

  it("offers a 'Manage cookies' control and pre-ticks nothing", async () => {
    const user = userEvent.setup()
    const unregister = register(FIXTURE)
    try {
      render(<ConsentBanner />)
      const manage = screen.getByRole("button", { name: "Manage cookies" })
      await user.click(manage)

      const boxes = screen.getAllByRole("checkbox")
      expect(boxes.length).toBeGreaterThan(0) // non-vacuity: there IS something to tick
      for (const box of boxes) expect(box).not.toBeChecked()
    } finally {
      unregister()
    }
  })

  it("records a rejection without pretending it was consent", async () => {
    const user = userEvent.setup()
    const unregister = register(FIXTURE)
    try {
      render(<ConsentBanner />)
      await user.click(screen.getByRole("button", { name: "Reject all" }))
      expect(screen.queryByRole("region", { name: "Cookie choices" })).not.toBeInTheDocument()
    } finally {
      unregister()
    }
  })
})

describe("accessibility", () => {
  it("reports zero axe violations, with a non-vacuity control asserted first", async () => {
    const { container } = render(<CookieNotice />)

    // NON-VACUITY CONTROL. A clean axe run over a tree that never mounted is
    // the historical false zero this project has already paid for once. Prove
    // the notice is really in the document before believing the scan.
    const notice = within(container).getByRole("region", { name: "Cookie notice" })
    expect(notice).toBeInTheDocument()
    expect(within(container).getByText("Cookies on J'Toye")).toBeInTheDocument()
    expect(within(container).getByRole("button", { name: "Got it" })).toBeInTheDocument()
    expect(within(container).getByRole("link", { name: "Cookie policy" })).toBeInTheDocument()

    const results = await axe(container)
    expect(results).toHaveNoViolations()
  }, AXE_TIMEOUT_MS)
})
