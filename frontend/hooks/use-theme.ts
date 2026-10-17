"use client"

import { useEffect, useSyncExternalStore } from "react"
import { canEnhance } from "@/lib/gsap-gate"

/**
 * useTheme — the ONE light/dark theme source for the dashboard chrome.
 *
 * Replaces the private `useState` + mount-effect pair that lived in BOTH
 * `components/dashboard/sidebar.tsx` and `components/dashboard/mobile-tab-bar.tsx`
 * (each carrying a `react-hooks/set-state-in-effect` suppression, issue #99
 * follow-up). The two surfaces used to exchange theme state THROUGH A DOM CLASS —
 * the tab bar read `document.documentElement.classList.contains("dark")`, which
 * was only correct if the sidebar's effect had already run. One shared store
 * removes both suppressions AND that mount-ordering dependency; copying the
 * logic into a second private hook would have removed the suppressions and kept
 * the bug (the #457 precedent: extract, do not copy).
 *
 * Built on `useSyncExternalStore` — the sanctioned shape, matching the shipped
 * `components/marketing/reveal.tsx:34-58`. Deriving during render or reading an
 * external store are the only two fixes the lint rule accepts; moving the
 * `setState` into a helper is not one, because the rule traces into the call
 * graph.
 */

/**
 * DARK THEME WITHHELD — owner ruling 2026-10-16 (phase 37
 * `deferred-items.md`, "Owner rulings 2026-10-16"; quick 261016-edv).
 *
 * The dashboard is unreadable in dark today: measured in the 37-55 UAT evidence
 * (`37-55-uat-gaps-runtime.md` sections 6-7), 73 of 125 text elements on
 * /dashboard/staff outside the two Staff form cards fall below 4.5:1, and the
 * shop picker draws a light option list. Until a dashboard-wide dark pass ships
 * (Phase 39 candidate), while this is false:
 *   - the hook resolves LIGHT whatever is stored or the system prefers, and its
 *     mount effect leaves no `dark` class on <html>;
 *   - setDark / toggle write NOTHING to localStorage, so a stored `theme` is
 *     left byte-identical and the user's choice returns with the pass;
 *   - `available` is false, and both surfaces (sidebar, mobile tab bar) hide the
 *     Dark Mode / Light Mode control.
 *
 * Re-enabling dark is flipping this ONE line to true. The forced-light jest
 * blocks (use-theme.test.tsx, theme-cross-surface.test.tsx) and the live spec
 * e2e/dashboard-forced-light.spec.ts go red on that flip BY DESIGN; delete them
 * together with this flag. Typed `boolean`, not the literal `false`, so the
 * flag-on branches stay type-reachable.
 */
export const DARK_THEME_AVAILABLE: boolean = false

/** The live value of the flag; only the test seam below ever changes it. */
let darkThemeAvailable: boolean = DARK_THEME_AVAILABLE

const STORAGE_KEY = "theme"
const DARK_CLASS = "dark"
const PREFERS_DARK_QUERY = "(prefers-color-scheme: dark)"

type StoredTheme = "dark" | "light" | null

/** Every mounted subscriber. Module-level, so all consumers share one value. */
const listeners = new Set<() => void>()

/** Non-null only while listeners are attached; see the teardown in subscribe(). */
let mediaQuery: MediaQueryList | null = null

/**
 * SECURITY — T-34-02-01 (Tampering, browser storage -> DOM).
 *
 * `localStorage` is writable by any script already running on this origin, and
 * this value is read on every dashboard render. It is narrowed to a two-value
 * union HERE and never travels any further as a string: only the boolean
 * derived from it reaches `classList.toggle(DARK_CLASS, next)`, so no
 * attacker-chosen text can become a DOM class name or reach any other DOM sink.
 * Anything that is not exactly "dark" or "light" — an injected class list, a
 * stray token, an empty string — is treated as ABSENT and falls through to the
 * system preference.
 */
function readStored(): StoredTheme {
  if (typeof window === "undefined") return null
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw === "dark" || raw === "light" ? raw : null
  } catch {
    // Private mode / blocked storage: no preference is readable, and the read
    // must not throw out of a render (hooks/use-cart-count.ts:16-36).
    return null
  }
}

/**
 * An explicit stored preference always wins; otherwise follow the system.
 * While the dark theme is withheld, light — decided before the stored value is
 * even read, so no stored value reaches render.
 */
function getSnapshot(): boolean {
  if (!darkThemeAvailable) return false
  const stored = readStored()
  if (stored !== null) return stored === "dark"
  if (!canEnhance()) return false
  return window.matchMedia(PREFERS_DARK_QUERY).matches
}

/**
 * Server render: always light.
 *
 * MANDATORY, not optional (T-34-02-02). `app/layout.tsx` sets
 * `dynamic = "force-dynamic"` app-wide, so every dashboard surface renders on
 * the server on every request, and `useSyncExternalStore` THROWS during SSR
 * when no server snapshot is supplied. Returning false also keeps the
 * server-rendered markup free of any theme state.
 */
function getServerSnapshot(): boolean {
  return false
}

function notify(): void {
  for (const listener of listeners) listener()
}

/**
 * TEST SEAM — turns the withheld dark theme back on (or off) for a test block,
 * so the flag-on behaviour stays covered while the shipped flag is false.
 * Callers restore it with `__setDarkThemeAvailableForTests(DARK_THEME_AVAILABLE)`.
 * Follows the repo's `__<name>ForTests` convention (lib/vendor-logout.ts,
 * lib/gsap-gate.ts). Not on window: reachable only by importing this module.
 */
export function __setDarkThemeAvailableForTests(next: boolean): void {
  darkThemeAvailable = next
  notify()
}

function handleStorage(event: StorageEvent): void {
  // Another tab wrote a preference. Only this key concerns this store — an
  // unrelated key must not wake every dashboard subscriber.
  if (event.key === STORAGE_KEY) notify()
}

function handleSystemChange(): void {
  notify()
}

/**
 * Cleanup symmetry (T-34-02-03): the teardown removes EXACTLY what the matching
 * attach added — the same handler references, and the matchMedia listener only
 * where one was attached. Listeners are global to the store, so they are
 * attached on the first subscriber and released on the last.
 */
function subscribe(onStoreChange: () => void): () => void {
  listeners.add(onStoreChange)
  if (listeners.size === 1 && typeof window !== "undefined") {
    window.addEventListener("storage", handleStorage)
    if (canEnhance()) {
      mediaQuery = window.matchMedia(PREFERS_DARK_QUERY)
      mediaQuery.addEventListener("change", handleSystemChange)
    }
  }
  return () => {
    listeners.delete(onStoreChange)
    if (listeners.size === 0 && typeof window !== "undefined") {
      window.removeEventListener("storage", handleStorage)
      if (mediaQuery) {
        mediaQuery.removeEventListener("change", handleSystemChange)
        mediaQuery = null
      }
    }
  }
}

/**
 * Persist the preference, put the document in step, and wake every subscriber.
 * While the dark theme is withheld this does NOTHING: no localStorage write (a
 * stored `theme` stays byte-identical), no class change, no notify.
 */
function applyTheme(next: boolean): void {
  if (!darkThemeAvailable) return
  if (typeof window === "undefined") return
  try {
    localStorage.setItem(STORAGE_KEY, next ? "dark" : "light")
  } catch {
    // Blocked storage: the choice cannot persist across reloads, but the rest
    // of this session still honours it rather than failing the click.
  }
  document.documentElement.classList.toggle(DARK_CLASS, next)
  notify()
}

interface ThemeControls {
  /** True when the dark theme is active for this render. */
  dark: boolean
  /**
   * False while the dark theme is withheld (DARK_THEME_AVAILABLE, owner ruling
   * 2026-10-16); surfaces hide their theme control when it is false.
   */
  available: boolean
  /** Set the theme explicitly and persist it. */
  setDark: (next: boolean) => void
  /** Invert the current theme and persist it. */
  toggle: () => void
}

export function useTheme(): ThemeControls {
  const dark = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot)

  // The document class is a DERIVED side effect, not state — there is no
  // setState here, so this is not the shape the lint rule forbids. It is what
  // makes a stored preference survive a reload (the job the sidebar's mount
  // effect used to do, and a shipped behaviour that must not regress) and what
  // keeps the document in step after a cross-tab write or a system change.
  useEffect(() => {
    document.documentElement.classList.toggle(DARK_CLASS, dark)
  }, [dark])

  return {
    dark,
    available: darkThemeAvailable,
    setDark: applyTheme,
    toggle: () => applyTheme(!dark),
  }
}
