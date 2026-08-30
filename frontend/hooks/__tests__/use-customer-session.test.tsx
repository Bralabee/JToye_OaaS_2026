/**
 * The behaviour contract of `lib/customer-session-store` and the
 * `useCustomerSession` hook that reads it (plan 34-03, #202 / the `#99 follow-up`
 * suppression).
 *
 * WHY THIS SUITE EXISTS. The hook used to hydrate with a mount-time `setState`
 * inside a `useEffect`, suppressed with `react-hooks/set-state-in-effect`. Moving
 * the truth into an external store read by `useSyncExternalStore` removes the
 * suppression, but it also moves THREE public surfaces
 * (`components/public/public-header.tsx:58`, `components/public/public-footer.tsx:72`,
 * `components/storefront/storefront-nav.tsx:24` — measured on this tree
 * 2026-08-28) onto a single module-level cache. That is a strictly riskier shape
 * than the per-component state it replaces, so its three dangerous failure modes
 * are written down here FIRST:
 *
 *   1. an unstable snapshot -> React re-renders forever (asserted with `toBe`,
 *      never `toEqual` — `toEqual` passes on a fresh object every call, which is
 *      exactly the bug);
 *   2. a `null` answer that does not clear the cache -> a signed-out or expired
 *      customer keeps someone's name on screen (T-34-03-02);
 *   3. a marker-only read -> an attacker-writable localStorage flag becomes a
 *      signed-in UI (T-34-03-01).
 *
 * BULLET -> TEST MAP (the `<feature><behavior>` list in 34-03-PLAN.md):
 *   B1  initial snapshot is null
 *   B2  repeated getSnapshot() is reference-stable
 *   B3  getServerSnapshot() is null and touches no browser API
 *   B4  refresh() adopts the profile and notifies each subscriber once
 *   B5  an equivalent second answer neither changes the reference nor notifies
 *   B6  a null answer clears the cache and notifies
 *   B7  a rejecting refresh() leaves the previous snapshot alone
 *   B8  the first subscriber attaches focus + visibilitychange + storage + poll
 *       (B8b poll is bounded, B8c visibility gate, B8d focus path)
 *   B9  the last unsubscribe removes every listener and clears both timers
 *   B10 an unrelated storage key triggers nothing
 *
 * THE MOCK IS THE POINT. `getCustomerSession()` is the ONLY authority the store
 * is allowed to consult; `isLoggedIn()` is mocked alongside it purely so the
 * suite can prove the store never calls it.
 */

import { act, renderHook } from "@testing-library/react"
import * as customerAuth from "@/lib/customer-auth"
import * as store from "@/lib/customer-session-store"
import { useCustomerSession } from "@/hooks/use-customer-session"

jest.mock("@/lib/customer-auth", () => ({
  __esModule: true,
  getCustomerSession: jest.fn(),
  // Present ONLY so this suite can assert it is never called (T-34-03-01).
  isLoggedIn: jest.fn(() => true),
}))

type CustomerProfile = customerAuth.CustomerProfile

const getCustomerSessionMock = customerAuth.getCustomerSession as unknown as jest.Mock
const isLoggedInMock = customerAuth.isLoggedIn as unknown as jest.Mock

const MARKER_KEY = "jtoye-customer-logged-in"
const EXPIRES_KEY = "jtoye-customer-expires-at"

const ALICE: CustomerProfile = {
  sub: "kc-alice",
  email: "alice@example.com",
  name: "Alice Adeyemi",
  emailVerified: true,
}
// Same identity, different object — the equivalence case (B5).
const ALICE_AGAIN: CustomerProfile = { ...ALICE }

const session = (profile: CustomerProfile) => ({ profile, expiresAt: 1893456000 })

/**
 * The snapshot as it stands at MODULE EVALUATION — the only honest place to
 * observe "before any refresh", because the module registry is shared by every
 * test below and `beforeEach` has to touch the store to reset it.
 */
const PRISTINE_SNAPSHOT = store.getSnapshot()
const PRISTINE_SERVER_SNAPSHOT = store.getServerSnapshot()

/** Settle promise continuations. Microtasks are unaffected by fake timers. */
async function flushPromises(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

function setVisibility(state: DocumentVisibilityState): void {
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    get: () => state,
  })
}

let unsubscribes: Array<() => void> = []
function subscribeTracked(cb: () => void): () => void {
  const un = store.subscribe(cb)
  unsubscribes.push(un)
  return un
}

beforeEach(async () => {
  jest.useRealTimers()
  localStorage.clear()
  setVisibility("visible")
  getCustomerSessionMock.mockReset()
  getCustomerSessionMock.mockResolvedValue(null)
  isLoggedInMock.mockClear()

  // Reset the module-level cache through the PUBLIC contract (B6: a null answer
  // always clears), so the reset itself is one of the behaviours under test
  // rather than a private back door. Asserted, not assumed.
  await store.refresh()
  expect(store.getSnapshot()).toBeNull()

  getCustomerSessionMock.mockClear()
})

afterEach(() => {
  unsubscribes.forEach((un) => un())
  unsubscribes = []
  jest.useRealTimers()
  setVisibility("visible")
})

describe("customer-session-store", () => {
  it("B1: getSnapshot() returns null before any refresh", () => {
    expect(PRISTINE_SNAPSHOT).toBeNull()
  })

  it("B2: getSnapshot() returns the SAME reference on repeated calls while nothing changed", async () => {
    getCustomerSessionMock.mockResolvedValue(session(ALICE))
    await store.refresh()

    const first = store.getSnapshot()
    // Positive control: there is something to be unstable ABOUT.
    expect(first).not.toBeNull()
    // `toBe`, deliberately. `toEqual` is satisfied by a brand-new object on every
    // call, which is precisely the shape that makes useSyncExternalStore loop.
    expect(store.getSnapshot()).toBe(first)
    expect(store.getSnapshot()).toBe(first)
  })

  it("B3: getServerSnapshot() returns null and reads no browser API", async () => {
    getCustomerSessionMock.mockResolvedValue(session(ALICE))
    await store.refresh()
    // Positive control: a profile IS cached, so a null below is a decision and
    // not merely "nothing to return".
    expect(store.getSnapshot()).toEqual(ALICE)

    const getItem = jest.spyOn(Storage.prototype, "getItem")
    const winAdd = jest.spyOn(window, "addEventListener")
    const docAdd = jest.spyOn(document, "addEventListener")
    try {
      expect(store.getServerSnapshot()).toBeNull()
      expect(PRISTINE_SERVER_SNAPSHOT).toBeNull()
      expect(getItem).not.toHaveBeenCalled()
      expect(winAdd).not.toHaveBeenCalled()
      expect(docAdd).not.toHaveBeenCalled()
    } finally {
      getItem.mockRestore()
      winAdd.mockRestore()
      docAdd.mockRestore()
    }
  })

  it("B4: refresh() adopts the resolved profile and notifies every subscriber exactly once", async () => {
    const a = jest.fn()
    const b = jest.fn()
    subscribeTracked(a)
    subscribeTracked(b)
    await flushPromises()
    // The subscribe-time check resolved null over a null cache: no change, so no
    // notification. A store that notified unconditionally would fail here.
    expect(a).not.toHaveBeenCalled()
    expect(b).not.toHaveBeenCalled()

    getCustomerSessionMock.mockResolvedValue(session(ALICE))
    await store.refresh()

    expect(store.getSnapshot()).toEqual(ALICE)
    expect(a).toHaveBeenCalledTimes(1)
    expect(b).toHaveBeenCalledTimes(1)
  })

  it("B5: a second refresh() resolving to an equivalent profile neither changes the reference nor notifies", async () => {
    getCustomerSessionMock.mockResolvedValue(session(ALICE))
    await store.refresh()
    const first = store.getSnapshot()

    const listener = jest.fn()
    subscribeTracked(listener)
    await flushPromises()
    listener.mockClear()

    getCustomerSessionMock.mockResolvedValue(session(ALICE_AGAIN))
    await store.refresh()

    // A different object carrying the same identity must not churn three headers.
    expect(ALICE_AGAIN).not.toBe(ALICE)
    expect(store.getSnapshot()).toBe(first)
    expect(listener).not.toHaveBeenCalled()
  })

  it("B6: refresh() resolving to null clears the cached profile and notifies (sign-out / expiry)", async () => {
    getCustomerSessionMock.mockResolvedValue(session(ALICE))
    await store.refresh()
    expect(store.getSnapshot()).toEqual(ALICE)

    const listener = jest.fn()
    subscribeTracked(listener)
    await flushPromises()
    listener.mockClear()

    getCustomerSessionMock.mockResolvedValue(null)
    await store.refresh()

    expect(store.getSnapshot()).toBeNull()
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it("B7: a rejecting refresh() leaves the previous snapshot untouched and does not throw to the caller", async () => {
    getCustomerSessionMock.mockResolvedValue(session(ALICE))
    await store.refresh()
    const before = store.getSnapshot()

    const listener = jest.fn()
    subscribeTracked(listener)
    await flushPromises()
    listener.mockClear()

    getCustomerSessionMock.mockRejectedValue(new Error("network down"))
    await expect(store.refresh()).resolves.toBeUndefined()

    expect(store.getSnapshot()).toBe(before)
    expect(listener).not.toHaveBeenCalled()
  })

  it("B8: the FIRST subscriber attaches focus + visibilitychange + storage and starts the poll; a second attaches nothing", () => {
    jest.useFakeTimers()
    const winAdd = jest.spyOn(window, "addEventListener")
    const docAdd = jest.spyOn(document, "addEventListener")
    try {
      subscribeTracked(jest.fn())

      const winTypes = winAdd.mock.calls.map((c) => String(c[0]))
      const docTypes = docAdd.mock.calls.map((c) => String(c[0]))
      expect(winTypes).toContain("focus")
      expect(winTypes).toContain("storage")
      expect(docTypes).toContain("visibilitychange")
      // The interval AND the timeout that stops it.
      expect(jest.getTimerCount()).toBeGreaterThanOrEqual(2)

      const winCallsAfterFirst = winAdd.mock.calls.length
      const docCallsAfterFirst = docAdd.mock.calls.length
      subscribeTracked(jest.fn())
      expect(winAdd.mock.calls.length).toBe(winCallsAfterFirst)
      expect(docAdd.mock.calls.length).toBe(docCallsAfterFirst)
    } finally {
      winAdd.mockRestore()
      docAdd.mockRestore()
    }
  })

  it("B8b: the post-OAuth poll runs at 1s and is stopped by the 5s timeout", async () => {
    jest.useFakeTimers()
    subscribeTracked(jest.fn())

    // Subscribing checks the session AT ONCE — the mount check the deleted
    // effect used to do. Without this a signed-in customer would see "Sign in"
    // for up to a second on every page.
    const immediate = getCustomerSessionMock.mock.calls.length
    expect(immediate).toBe(1)

    jest.advanceTimersByTime(5000)
    await flushPromises()
    const polled = getCustomerSessionMock.mock.calls.length - immediate
    // Lower bound is the positive control: a store that never polled would give
    // 0 and satisfy an upper bound alone.
    expect(polled).toBeGreaterThanOrEqual(4)
    expect(polled).toBeLessThanOrEqual(5)

    jest.advanceTimersByTime(20000)
    await flushPromises()
    expect(getCustomerSessionMock.mock.calls.length - immediate).toBe(polled)
  })

  it("B8c: visibilitychange refreshes only when the document is visible", async () => {
    jest.useFakeTimers()
    subscribeTracked(jest.fn())
    await flushPromises()
    const before = getCustomerSessionMock.mock.calls.length

    setVisibility("hidden")
    document.dispatchEvent(new Event("visibilitychange"))
    await flushPromises()
    expect(getCustomerSessionMock.mock.calls.length).toBe(before)

    setVisibility("visible")
    document.dispatchEvent(new Event("visibilitychange"))
    await flushPromises()
    expect(getCustomerSessionMock.mock.calls.length).toBe(before + 1)
  })

  it("B8d: a window focus event refreshes (the OAuth-return path)", async () => {
    jest.useFakeTimers()
    subscribeTracked(jest.fn())
    await flushPromises()
    const before = getCustomerSessionMock.mock.calls.length

    window.dispatchEvent(new Event("focus"))
    await flushPromises()
    expect(getCustomerSessionMock.mock.calls.length).toBe(before + 1)
  })

  it("B9: the LAST unsubscribe removes every listener it added and clears both timers", () => {
    jest.useFakeTimers()
    const winAdd = jest.spyOn(window, "addEventListener")
    const winRemove = jest.spyOn(window, "removeEventListener")
    const docAdd = jest.spyOn(document, "addEventListener")
    const docRemove = jest.spyOn(document, "removeEventListener")
    try {
      const unA = store.subscribe(jest.fn())
      const unB = store.subscribe(jest.fn())
      expect(jest.getTimerCount()).toBeGreaterThan(0)

      unA()
      // One reader remains: nothing may be torn down yet.
      expect(winRemove).not.toHaveBeenCalled()
      expect(docRemove).not.toHaveBeenCalled()
      expect(jest.getTimerCount()).toBeGreaterThan(0)

      unB()

      // Compared by HANDLER IDENTITY, not by event name. A teardown that removes
      // the right event with a differently-bound function leaves the listener
      // attached and would pass a name-only comparison.
      const pairs = (calls: unknown[][]) =>
        calls.map((c) => [String(c[0]), c[1]] as [string, unknown])
      const addedWin = pairs(winAdd.mock.calls)
      const removedWin = pairs(winRemove.mock.calls)
      const addedDoc = pairs(docAdd.mock.calls)
      const removedDoc = pairs(docRemove.mock.calls)

      expect(addedWin.length).toBeGreaterThan(0) // positive control
      expect(removedWin.length).toBe(addedWin.length)
      addedWin.forEach(([type, fn]) => {
        expect(removedWin.some(([t, f]) => t === type && f === fn)).toBe(true)
      })
      expect(addedDoc.length).toBeGreaterThan(0)
      expect(removedDoc.length).toBe(addedDoc.length)
      addedDoc.forEach(([type, fn]) => {
        expect(removedDoc.some(([t, f]) => t === type && f === fn)).toBe(true)
      })

      expect(jest.getTimerCount()).toBe(0)
    } finally {
      winAdd.mockRestore()
      winRemove.mockRestore()
      docAdd.mockRestore()
      docRemove.mockRestore()
    }
  })

  it("B10: a storage event for an unrelated key triggers no refresh", async () => {
    jest.useFakeTimers()
    subscribeTracked(jest.fn())
    await flushPromises()
    const before = getCustomerSessionMock.mock.calls.length

    window.dispatchEvent(new StorageEvent("storage", { key: "some-other-app-key" }))
    await flushPromises()
    expect(getCustomerSessionMock.mock.calls.length).toBe(before)

    // Positive control: the two keys the store DOES watch still trigger it, so
    // the silence above is a filter and not a dead listener.
    window.dispatchEvent(new StorageEvent("storage", { key: MARKER_KEY }))
    await flushPromises()
    expect(getCustomerSessionMock.mock.calls.length).toBe(before + 1)

    window.dispatchEvent(new StorageEvent("storage", { key: EXPIRES_KEY }))
    await flushPromises()
    expect(getCustomerSessionMock.mock.calls.length).toBe(before + 2)
  })

  it("SEC (T-34-03-01): a stale local marker never produces a profile, and isLoggedIn() is never consulted", async () => {
    localStorage.setItem(MARKER_KEY, "true")
    localStorage.setItem(EXPIRES_KEY, String(Math.floor(Date.now() / 1000) + 3600))
    // Positive control: the marker is genuinely readable and genuinely says
    // "signed in", so the null below is the store REFUSING it.
    expect(customerAuth.isLoggedIn()).toBe(true)
    isLoggedInMock.mockClear()

    getCustomerSessionMock.mockResolvedValue(null)
    subscribeTracked(jest.fn())
    await flushPromises()
    await store.refresh()

    expect(store.getSnapshot()).toBeNull()
    expect(isLoggedInMock).not.toHaveBeenCalled()
  })
})

describe("useCustomerSession — the contract the three public surfaces depend on", () => {
  it("H1: returns { profile, refresh } with a null profile before the session resolves", () => {
    const { result } = renderHook(() => useCustomerSession())
    expect(result.current.profile).toBeNull()
    expect(typeof result.current.refresh).toBe("function")
  })

  it("H2: re-renders with the profile once the session resolves", async () => {
    getCustomerSessionMock.mockResolvedValue(session(ALICE))
    const { result, unmount } = renderHook(() => useCustomerSession())
    await act(async () => {
      await flushPromises()
    })
    expect(result.current.profile).toEqual(ALICE)
    unmount()
  })

  it("H3: two mounted readers see ONE store and cannot disagree (#457)", async () => {
    getCustomerSessionMock.mockResolvedValue(session(ALICE))
    const a = renderHook(() => useCustomerSession())
    const b = renderHook(() => useCustomerSession())
    await act(async () => {
      await flushPromises()
    })
    expect(a.result.current.profile).toEqual(ALICE)
    // Same REFERENCE, not merely equal: two independent copies is the #457 bug.
    expect(b.result.current.profile).toBe(a.result.current.profile)
    a.unmount()
    b.unmount()
  })

  it("H4: a signed-out answer collapses every mounted reader, even with the marker set", async () => {
    localStorage.setItem(MARKER_KEY, "true")
    localStorage.setItem(EXPIRES_KEY, String(Math.floor(Date.now() / 1000) + 3600))
    getCustomerSessionMock.mockResolvedValue(session(ALICE))
    const a = renderHook(() => useCustomerSession())
    const b = renderHook(() => useCustomerSession())
    await act(async () => {
      await flushPromises()
    })
    expect(a.result.current.profile).toEqual(ALICE) // positive control

    getCustomerSessionMock.mockResolvedValue(null)
    await act(async () => {
      await a.result.current.refresh()
    })
    expect(a.result.current.profile).toBeNull()
    expect(b.result.current.profile).toBeNull()
    a.unmount()
    b.unmount()
  })

  it("H5: mounting and unmounting the hook leaves no listener behind", async () => {
    const winAdd = jest.spyOn(window, "addEventListener")
    const winRemove = jest.spyOn(window, "removeEventListener")
    try {
      const { unmount } = renderHook(() => useCustomerSession())
      await act(async () => {
        await flushPromises()
      })
      const isStoreEvent = (t: unknown) => t === "focus" || t === "storage"
      const added = winAdd.mock.calls.filter((c) => isStoreEvent(c[0]))
      expect(added.length).toBe(2) // positive control: the hook DID subscribe

      unmount()
      const removed = winRemove.mock.calls.filter((c) => isStoreEvent(c[0]))
      expect(removed.length).toBe(2)
      added.forEach((c) => {
        expect(removed.some((r) => r[0] === c[0] && r[1] === c[1])).toBe(true)
      })
    } finally {
      winAdd.mockRestore()
      winRemove.mockRestore()
    }
  })
})
