/**
 * useTheme — the ONE theme source shared by the dashboard sidebar and the
 * mobile tab bar (phase 34-02, TRUTH-01).
 *
 * Written BEFORE the hook exists, so this suite must fail on a missing module
 * before any implementation is committed (the RED gate).
 *
 * WHY ONE STORE RATHER THAN TWO COPIES. Before this change the two surfaces
 * exchanged theme state THROUGH A DOM CLASS: the tab bar read
 * `document.documentElement.classList.contains("dark")`, which is only correct
 * if the sidebar's mount effect had already run. The cross-subscriber cases
 * below are the executable form of "that mount-ordering dependency is gone" —
 * copying the logic into a second private hook would remove the lint
 * suppressions and keep the bug.
 */

import { TextEncoder as NodeTextEncoder, TextDecoder as NodeTextDecoder } from "node:util"
import { render, screen, act, fireEvent } from "@testing-library/react"
import {
  DARK_THEME_AVAILABLE,
  __setDarkThemeAvailableForTests,
  useTheme,
} from "@/hooks/use-theme"

/* ---------------------------------------------------------------------------
 * jsdom implements neither MessageChannel nor TextEncoder, and
 * `react-dom/server.browser` requires BOTH at module load — measured, one after
 * the other: "ReferenceError: MessageChannel is not defined"
 * (react-dom-server.browser.development.js:8818) then "ReferenceError:
 * TextEncoder is not defined" (:8835), each before the SSR case could run.
 * Install them here, and let the SSR case import the server renderer
 * DYNAMICALLY so these assignments land first.
 *
 * The channel is a LOCAL FAKE rather than node:worker_threads, and that is not
 * a shortcut. React's server renderer builds one at module scope for its
 * scheduler and assigns `port1.onmessage`, which RE-REFS a real MessagePort —
 * so even `unref()`ing both ports in the constructor leaves a live handle
 * holding Node's event loop open. Measured both ways: the real channel gave
 * "Jest did not exit one second after the test run has completed" and then a
 * hard rc=124 timeout, which in CI is a stuck job rather than a red one. A
 * fake holds no OS handle, and `renderToString` is fully synchronous, so no
 * message ever needs delivering.
 * ------------------------------------------------------------------------- */
class FakeMessagePort {
  onmessage: ((event: { data: unknown }) => void) | null = null
  postMessage(): void {}
  close(): void {}
  start(): void {}
  addEventListener(): void {}
  removeEventListener(): void {}
}

class FakeMessageChannel {
  readonly port1 = new FakeMessagePort()
  readonly port2 = new FakeMessagePort()
}

const missingGlobals = globalThis as {
  MessageChannel?: unknown
  TextEncoder?: unknown
  TextDecoder?: unknown
}
if (typeof missingGlobals.MessageChannel === "undefined") {
  missingGlobals.MessageChannel = FakeMessageChannel
}
if (typeof missingGlobals.TextEncoder === "undefined") {
  missingGlobals.TextEncoder = NodeTextEncoder
}
if (typeof missingGlobals.TextDecoder === "undefined") {
  missingGlobals.TextDecoder = NodeTextDecoder
}

const STORAGE_KEY = "theme"
const DARK_CLASS = "dark"

/* ---------------------------------------------------------------------------
 * jsdom implements no matchMedia. This stub is controllable (`systemPrefersDark`)
 * and records its own change-listeners, so the teardown case can assert the hook
 * removed exactly what it added.
 *
 * `mediaListeners` is the STUB's bookkeeping of a DOM contract — deliberately
 * NOT the hook's private listener Set, which no test here reaches into. A test
 * that asserted on the hook's internals would go red on a correct refactor.
 * ------------------------------------------------------------------------- */
type MediaListener = () => void

let systemPrefersDark = false
const mediaListeners = new Set<MediaListener>()

function installMatchMedia(): void {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      get matches() {
        return query.includes("dark") ? systemPrefersDark : false
      },
      media: query,
      onchange: null,
      addEventListener: (_type: string, cb: MediaListener) => {
        mediaListeners.add(cb)
      },
      removeEventListener: (_type: string, cb: MediaListener) => {
        mediaListeners.delete(cb)
      },
      addListener: jest.fn(),
      removeListener: jest.fn(),
      dispatchEvent: jest.fn(),
    }),
  })
}

/** A consumer that renders the resolved theme and exposes both mutators. */
function Probe({ id = "probe" }: { id?: string }) {
  const { dark, available, setDark, toggle } = useTheme()
  return (
    <div>
      <span data-testid={id}>{dark ? "dark" : "light"}</span>
      <span data-testid={`${id}-available`}>{String(available)}</span>
      <button type="button" data-testid={`${id}-toggle`} onClick={toggle}>
        toggle
      </button>
      <button type="button" data-testid={`${id}-on`} onClick={() => setDark(true)}>
        on
      </button>
      <button type="button" data-testid={`${id}-off`} onClick={() => setDark(false)}>
        off
      </button>
    </div>
  )
}

/** Server-render probe: the resolved theme reaches the markup only as a class. */
function ServerProbe() {
  const { dark } = useTheme()
  return <div className={dark ? DARK_CLASS : "light"}>theme</div>
}

/* Shared by both blocks below: every case starts from empty storage, a bare
 * <html>, a light system preference and a fresh matchMedia stub. */
beforeEach(() => {
  localStorage.clear()
  document.documentElement.className = ""
  systemPrefersDark = false
  mediaListeners.clear()
  installMatchMedia()
})

/** Calls to localStorage.setItem whose key is the theme key. */
function themeWrites(spy: jest.SpyInstance): unknown[][] {
  return spy.mock.calls.filter(([key]) => key === STORAGE_KEY)
}

/* ===========================================================================
 * FORCED LIGHT — owner ruling 2026-10-16 (phase 37 deferred-items.md, "Owner
 * rulings 2026-10-16"; quick 261016-edv). The dashboard is unreadable in dark
 * (37-55 evidence sections 6-7: 73/125 text elements on /dashboard/staff below
 * 4.5:1), so until the dashboard-wide dark pass ships the hook resolves light
 * whatever is stored or preferred, and leaves a stored choice byte-identical so
 * it returns with the pass.
 *
 * These cases run on the SHIPPED default — no seam call — so they prove what
 * ships. Flipping DARK_THEME_AVAILABLE to true turns every one of them red BY
 * DESIGN; they are deleted together with the flag when the dark pass lands.
 * ========================================================================= */
describe("useTheme, with the dark theme withheld (shipped default)", () => {
  it("ships with DARK_THEME_AVAILABLE false and reports available: false", () => {
    expect(DARK_THEME_AVAILABLE).toBe(false)

    render(<Probe />)

    expect(screen.getByTestId("probe-available")).toHaveTextContent("false")
  })

  it("resolves light with a stored dark preference, adds no dark class, and keeps the stored value", () => {
    localStorage.setItem(STORAGE_KEY, "dark")
    systemPrefersDark = false

    render(<Probe />)

    expect(screen.getByTestId("probe")).toHaveTextContent("light")
    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(false)
    expect(localStorage.getItem(STORAGE_KEY)).toBe("dark")
  })

  it("resolves light when nothing is stored and the system prefers dark", () => {
    systemPrefersDark = true

    render(<Probe />)

    expect(screen.getByTestId("probe")).toHaveTextContent("light")
    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(false)
  })

  it("removes a stale dark class that was on the document before mount", () => {
    // Both inputs say dark AND the class is already there, so this proves the
    // mount effect actively LEAVES no class — not merely that nobody added one.
    localStorage.setItem(STORAGE_KEY, "dark")
    systemPrefersDark = true
    document.documentElement.classList.add(DARK_CLASS)
    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(true)

    render(<Probe />)

    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(false)
    expect(screen.getByTestId("probe")).toHaveTextContent("light")
  })

  it("setDark(true), setDark(false) and toggle() write nothing for the theme key and stay light", () => {
    localStorage.setItem(STORAGE_KEY, "dark")
    // Installed AFTER seeding, so the seed is not counted. The spy, not a
    // read-back, is the proof: writing "dark" over "dark" is invisible to
    // getItem, and setDark(false) / toggle() would write "light".
    const setItem = jest.spyOn(Storage.prototype, "setItem")
    try {
      render(<Probe />)

      fireEvent.click(screen.getByTestId("probe-on"))
      fireEvent.click(screen.getByTestId("probe-off"))
      fireEvent.click(screen.getByTestId("probe-toggle"))

      expect(themeWrites(setItem)).toHaveLength(0)
      expect(screen.getByTestId("probe")).toHaveTextContent("light")
      expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(false)
      expect(localStorage.getItem(STORAGE_KEY)).toBe("dark")
    } finally {
      setItem.mockRestore()
    }
  })

  it("setDark(true) with nothing stored leaves storage empty and the theme light", () => {
    render(<Probe />)

    fireEvent.click(screen.getByTestId("probe-on"))

    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
    expect(screen.getByTestId("probe")).toHaveTextContent("light")
    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(false)
  })

  it("stays light when another tab stores dark and the storage event arrives", () => {
    render(<Probe />)
    expect(screen.getByTestId("probe")).toHaveTextContent("light")

    localStorage.setItem(STORAGE_KEY, "dark")
    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: STORAGE_KEY }))
    })

    expect(screen.getByTestId("probe")).toHaveTextContent("light")
    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(false)
  })
})

/* ===========================================================================
 * FLAG ON — the behaviour the dark pass restores. Every case below predates the
 * 2026-10-16 ruling and is unchanged; the seam turns the dark theme back on for
 * this block only, so flipping DARK_THEME_AVAILABLE to true leaves it green.
 * ========================================================================= */
describe("useTheme", () => {
  beforeEach(() => {
    __setDarkThemeAvailableForTests(true)
  })

  afterEach(() => {
    __setDarkThemeAvailableForTests(DARK_THEME_AVAILABLE)
  })

  /* --- resolving the current theme ------------------------------------- */

  it("resolves dark when the stored preference is dark and the system prefers light", () => {
    localStorage.setItem(STORAGE_KEY, "dark")
    systemPrefersDark = false

    render(<Probe />)

    expect(screen.getByTestId("probe")).toHaveTextContent("dark")
  })

  it("lets an explicit light preference win over a dark system preference", () => {
    localStorage.setItem(STORAGE_KEY, "light")
    systemPrefersDark = true

    render(<Probe />)

    expect(screen.getByTestId("probe")).toHaveTextContent("light")
  })

  it("falls back to a dark system preference when nothing is stored", () => {
    systemPrefersDark = true

    render(<Probe />)

    expect(screen.getByTestId("probe")).toHaveTextContent("dark")
  })

  it("falls back to a light system preference when nothing is stored", () => {
    systemPrefersDark = false

    render(<Probe />)

    expect(screen.getByTestId("probe")).toHaveTextContent("light")
  })

  it("resolves light and lets no exception escape when localStorage is unreadable", () => {
    // Private mode / blocked storage: getItem throws rather than returning null.
    const getItem = jest.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("storage is blocked")
    })
    try {
      systemPrefersDark = false
      expect(() => render(<Probe />)).not.toThrow()
      expect(screen.getByTestId("probe")).toHaveTextContent("light")
    } finally {
      getItem.mockRestore()
    }
  })

  /* --- the server snapshot (T-34-02-02) --------------------------------- */

  it("server-renders without throwing and never emits the dark class", async () => {
    // Both client inputs say DARK, so a server render that leaked the client
    // snapshot would emit class="dark". The server snapshot must still be false:
    // `app/layout.tsx` sets dynamic = "force-dynamic", so every dashboard render
    // is a server render, and useSyncExternalStore throws during SSR when no
    // getServerSnapshot is supplied at all.
    localStorage.setItem(STORAGE_KEY, "dark")
    systemPrefersDark = true

    const { renderToString } = await import("react-dom/server")

    let html = ""
    expect(() => {
      html = renderToString(<ServerProbe />)
    }).not.toThrow()

    expect(html).not.toContain(`class="${DARK_CLASS}"`)
    expect(html).toContain('class="light"')
  })

  /* --- writing the theme ------------------------------------------------ */

  it("applies the stored preference to the document class on mount", () => {
    // Preserved behaviour: before this change the sidebar's mount effect was
    // what made a stored dark preference survive a reload. Without this the
    // dashboard would load light every time with `theme=dark` in storage.
    localStorage.setItem(STORAGE_KEY, "dark")

    render(<Probe />)

    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(true)
  })

  it("setDark(true) stores dark, adds the dark class and updates the subscriber", () => {
    render(<Probe />)
    expect(screen.getByTestId("probe")).toHaveTextContent("light")

    fireEvent.click(screen.getByTestId("probe-on"))

    expect(screen.getByTestId("probe")).toHaveTextContent("dark")
    expect(localStorage.getItem(STORAGE_KEY)).toBe("dark")
    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(true)
  })

  it("setDark(false) stores light, removes the dark class and updates the subscriber", () => {
    localStorage.setItem(STORAGE_KEY, "dark")
    render(<Probe />)
    expect(screen.getByTestId("probe")).toHaveTextContent("dark")

    fireEvent.click(screen.getByTestId("probe-off"))

    expect(screen.getByTestId("probe")).toHaveTextContent("light")
    expect(localStorage.getItem(STORAGE_KEY)).toBe("light")
    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(false)
  })

  it("toggle() inverts the current value and applies the same two side effects", () => {
    render(<Probe />)
    expect(screen.getByTestId("probe")).toHaveTextContent("light")

    fireEvent.click(screen.getByTestId("probe-toggle"))
    expect(screen.getByTestId("probe")).toHaveTextContent("dark")
    expect(localStorage.getItem(STORAGE_KEY)).toBe("dark")
    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(true)

    fireEvent.click(screen.getByTestId("probe-toggle"))
    expect(screen.getByTestId("probe")).toHaveTextContent("light")
    expect(localStorage.getItem(STORAGE_KEY)).toBe("light")
    expect(document.documentElement.classList.contains(DARK_CLASS)).toBe(false)
  })

  /* --- cross-tab and cross-subscriber propagation ----------------------- */

  it("observes a storage event for the theme key written by another tab", () => {
    render(<Probe />)
    expect(screen.getByTestId("probe")).toHaveTextContent("light")

    // Another tab writes the key, then the browser delivers the event.
    localStorage.setItem(STORAGE_KEY, "dark")
    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: STORAGE_KEY }))
    })

    expect(screen.getByTestId("probe")).toHaveTextContent("dark")
  })

  it("ignores a storage event for an unrelated key", () => {
    render(<Probe />)
    expect(screen.getByTestId("probe")).toHaveTextContent("light")

    // The stored value IS changed first, so this case cannot pass vacuously:
    // a store that notified on every storage event would flip here.
    localStorage.setItem(STORAGE_KEY, "dark")
    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: "jtoye-cart-somewhere" }))
    })
    expect(screen.getByTestId("probe")).toHaveTextContent("light")

    // Positive control for the setup above: the same pending value DOES arrive
    // once the event names the theme key.
    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: STORAGE_KEY }))
    })
    expect(screen.getByTestId("probe")).toHaveTextContent("dark")
  })

  it("keeps two simultaneously mounted consumers in agreement after either one toggles", () => {
    // The sidebar/tab-bar pairing in miniature: the second consumer must see the
    // first's toggle with no reload and no dependence on mount order.
    render(
      <>
        <Probe id="first" />
        <Probe id="second" />
      </>
    )
    expect(screen.getByTestId("first")).toHaveTextContent("light")
    expect(screen.getByTestId("second")).toHaveTextContent("light")

    fireEvent.click(screen.getByTestId("first-toggle"))
    expect(screen.getByTestId("first")).toHaveTextContent("dark")
    expect(screen.getByTestId("second")).toHaveTextContent("dark")

    fireEvent.click(screen.getByTestId("second-toggle"))
    expect(screen.getByTestId("first")).toHaveTextContent("light")
    expect(screen.getByTestId("second")).toHaveTextContent("light")
  })

  /* --- listener hygiene (T-34-02-03) ------------------------------------ */

  it("removes exactly the listeners it added once the last subscriber unmounts", () => {
    const addSpy = jest.spyOn(window, "addEventListener")
    const removeSpy = jest.spyOn(window, "removeEventListener")
    try {
      const { unmount } = render(<Probe />)

      const added = addSpy.mock.calls.filter(([type]) => type === "storage")
      expect(added).toHaveLength(1)
      expect(mediaListeners.size).toBe(1)

      unmount()

      const removed = removeSpy.mock.calls.filter(([type]) => type === "storage")
      expect(removed).toHaveLength(1)
      // Same handler reference — a teardown that removed a fresh closure would
      // leave the original attached and still satisfy a bare call count.
      expect(removed[0][1]).toBe(added[0][1])
      expect(mediaListeners.size).toBe(0)
    } finally {
      addSpy.mockRestore()
      removeSpy.mockRestore()
    }
  })
})
