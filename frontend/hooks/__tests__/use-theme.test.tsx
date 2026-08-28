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

import { MessageChannel as NodeMessageChannel } from "node:worker_threads"
import { render, screen, act, fireEvent } from "@testing-library/react"
import { useTheme } from "@/hooks/use-theme"

// jsdom implements no MessageChannel, and `react-dom/server.browser` requires one
// AT MODULE LOAD (measured: "ReferenceError: MessageChannel is not defined" from
// react-dom-server.browser.development.js:8818, before a single test ran). Node's
// worker_threads ships a spec-compliant one; install it here, and let the SSR case
// import the server renderer dynamically so this assignment happens first.
const globalWithChannel = globalThis as { MessageChannel?: unknown }
if (typeof globalWithChannel.MessageChannel === "undefined") {
  globalWithChannel.MessageChannel = NodeMessageChannel
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
  const { dark, setDark, toggle } = useTheme()
  return (
    <div>
      <span data-testid={id}>{dark ? "dark" : "light"}</span>
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

describe("useTheme", () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.className = ""
    systemPrefersDark = false
    mediaListeners.clear()
    installMatchMedia()
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
