"use client"

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type Dispatch,
  type SetStateAction,
} from "react"

/**
 * useStoredState — React state backed by localStorage, without the clobber.
 *
 * THE TRAP THIS EXISTS TO CLOSE. The obvious implementation is two effects:
 * one reads storage on mount, one writes on every change. That is wrong, and
 * it looks right:
 *
 *   render 1        value = fallback (nothing read yet)
 *   effect: read    schedules setValue(stored)
 *   effect: write   writes FALLBACK over the stored value   <-- clobber
 *   render 2        value = stored
 *   effect: write   writes it back                          <-- "repaired"
 *
 * The repair is what makes it survive review: storage ends up correct, so it
 * looks harmless. It is not. Under React StrictMode's double-invoke the second
 * mount's READ happens after the first mount's WRITE, so it reads the value
 * that was just emptied and the data is genuinely gone. That shipped in
 * CartProvider: add to basket, hard-navigate to the cart, land on an empty one.
 *
 * The same ordering leaks ACROSS keys. When `key` changes on a live hook (a
 * client-side nav from one shop to another keeps the provider mounted and swaps
 * the prop), the write effect fires for the NEW key while `value` still holds
 * the OLD key's data — writing shop A's basket into shop B's slot.
 *
 * So: persist only once the CURRENT key has been hydrated. Tracking WHICH key
 * (not a plain `hydrated` boolean) is what makes the cross-key case safe — a
 * boolean is already true when the key changes.
 *
 * SSR-safe: no storage access during render, so the server and first client
 * render agree and hydration does not mismatch.
 *
 * Returns `[value, setValue, hydrated]`. `hydrated` is for callers that must
 * distinguish "empty because nothing is stored" from "empty because we have
 * not looked yet" — e.g. to avoid flashing an empty state.
 */
interface StoredStateOptions<T> {
  /** Parse a raw stored string. Return `undefined` to reject and use fallback. */
  parse?: (raw: string) => T | undefined
  serialize?: (value: T) => string
  /** Called after a successful write — broadcasts, analytics, cache priming. */
  onPersist?: (value: T, key: string) => void
}

function readStored<T>(
  key: string,
  fallback: T,
  parse: (raw: string) => T | undefined
): T {
  if (typeof window === "undefined") return fallback
  try {
    const raw = window.localStorage.getItem(key)
    if (raw === null) return fallback
    const parsed = parse(raw)
    return parsed === undefined ? fallback : parsed
  } catch {
    // Private mode, quota, corrupt JSON — a broken cache must never break the UI.
    return fallback
  }
}

export function useStoredState<T>(
  key: string,
  fallback: T,
  options: StoredStateOptions<T> = {}
): [T, Dispatch<SetStateAction<T>>, boolean] {
  const [value, setValue] = useState<T>(fallback)
  // WHICH key `value` holds hydrated data for. Null until the first read.
  const [hydratedKey, setHydratedKey] = useState<string | null>(null)

  // Options and fallback are held in refs so that an inline object or arrow
  // function at the call site cannot retrigger the effects below every render.
  const optionsRef = useRef(options)
  optionsRef.current = options
  const fallbackRef = useRef(fallback)
  fallbackRef.current = fallback

  const parse = useCallback(
    (raw: string): T | undefined =>
      optionsRef.current.parse
        ? optionsRef.current.parse(raw)
        : (JSON.parse(raw) as T),
    []
  )

  // READ: on mount, and again whenever the key changes.
  useEffect(() => {
    setValue(readStored(key, fallbackRef.current, parse))
    setHydratedKey(key)
  }, [key, parse])

  // WRITE: only for a key we have already read. See the note above — writing
  // before hydration is the clobber, and writing across a key change is the leak.
  useEffect(() => {
    if (hydratedKey !== key) return
    if (typeof window === "undefined") return
    try {
      const { serialize, onPersist } = optionsRef.current
      window.localStorage.setItem(
        key,
        serialize ? serialize(value) : JSON.stringify(value)
      )
      onPersist?.(value, key)
    } catch {
      // Quota or private mode: the in-memory state stays authoritative.
    }
  }, [key, value, hydratedKey])

  return [value, setValue, hydratedKey === key]
}
