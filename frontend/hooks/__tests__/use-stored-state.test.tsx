/**
 * useStoredState — the storage-backed-state contract.
 *
 * These are the CartProvider regressions generalised: the point of extracting
 * the hook is that the next storage-backed feature inherits the fix instead of
 * re-deriving it. Each case here failed against the naive two-effect version.
 */
import { render, act } from "@testing-library/react"
import { StrictMode } from "react"
import { useStoredState } from "@/hooks/use-stored-state"

type Probe = { value: unknown; set: (v: unknown) => void; hydrated: boolean }

let probe: Probe

function Harness({
  storageKey,
  fallback = [] as unknown,
  options,
}: {
  storageKey: string
  fallback?: unknown
  options?: Parameters<typeof useStoredState>[2]
}) {
  const [value, setValue, hydrated] = useStoredState(storageKey, fallback, options)
  probe = { value, set: setValue as (v: unknown) => void, hydrated }
  return null
}

/** Records every write to a key while still performing it. */
function recordWrites(key: string) {
  const real = Storage.prototype.setItem
  const writes: string[] = []
  Storage.prototype.setItem = function (k: string, v: string) {
    if (k === key) writes.push(v)
    return real.call(this, k, v)
  }
  return { writes, restore: () => { Storage.prototype.setItem = real } }
}

describe("useStoredState", () => {
  beforeEach(() => localStorage.clear())

  it("hydrates from storage after mount", () => {
    localStorage.setItem("k1", JSON.stringify(["stored"]))
    render(<Harness storageKey="k1" />)
    expect(probe.value).toEqual(["stored"])
    expect(probe.hydrated).toBe(true)
  })

  it("NEVER writes the fallback over stored data, even under StrictMode", () => {
    localStorage.setItem("k2", JSON.stringify(["keep me"]))
    const rec = recordWrites("k2")
    try {
      render(
        <StrictMode>
          <Harness storageKey="k2" />
        </StrictMode>
      )
      // The defect is the WRITE, not the end state — a later write repairing it
      // is what made this survive review for so long.
      expect(rec.writes.filter((w) => JSON.parse(w).length === 0)).toEqual([])
      expect(JSON.parse(localStorage.getItem("k2")!)).toEqual(["keep me"])
      expect(probe.value).toEqual(["keep me"])
    } finally {
      rec.restore()
    }
  })

  it("does not write one key's value into another when the key changes", () => {
    localStorage.setItem("shop-a", JSON.stringify(["a"]))
    localStorage.setItem("shop-b", JSON.stringify(["b1", "b2"]))
    const rec = recordWrites("shop-b")
    try {
      const { rerender } = render(<Harness storageKey="shop-a" />)
      expect(probe.value).toEqual(["a"])

      rerender(<Harness storageKey="shop-b" />)

      expect(rec.writes.filter((w) => JSON.parse(w).includes("a"))).toEqual([])
      expect(JSON.parse(localStorage.getItem("shop-b")!)).toEqual(["b1", "b2"])
      expect(JSON.parse(localStorage.getItem("shop-a")!)).toEqual(["a"])
      expect(probe.value).toEqual(["b1", "b2"])
    } finally {
      rec.restore()
    }
  })

  it("persists updates once hydrated", () => {
    render(<Harness storageKey="k3" />)
    act(() => probe.set(["written"]))
    expect(JSON.parse(localStorage.getItem("k3")!)).toEqual(["written"])
  })

  it("calls onPersist with hydrated values only — never the pre-read fallback", () => {
    localStorage.setItem("k4", JSON.stringify(["real"]))
    const seen: unknown[] = []
    render(
      <Harness
        storageKey="k4"
        options={{ onPersist: (v) => seen.push(v) }}
      />
    )
    expect(seen.length).toBeGreaterThan(0)
    // A broadcast of the empty fallback is what flashed an empty basket badge.
    expect(seen).not.toContainEqual([])
    expect(seen[seen.length - 1]).toEqual(["real"])
  })

  it("falls back (and does not throw) on corrupt stored JSON", () => {
    localStorage.setItem("k5", "{not json")
    expect(() => render(<Harness storageKey="k5" fallback={["fb"]} />)).not.toThrow()
    expect(probe.value).toEqual(["fb"])
  })

  it("treats a parse that returns undefined as a rejection", () => {
    localStorage.setItem("k6", JSON.stringify({ wrongShape: true }))
    render(
      <Harness
        storageKey="k6"
        fallback={["fb"]}
        options={{ parse: () => undefined }}
      />
    )
    expect(probe.value).toEqual(["fb"])
  })

  it("survives a storage that throws on write (private mode / quota)", () => {
    const real = Storage.prototype.setItem
    Storage.prototype.setItem = () => {
      throw new Error("QuotaExceededError")
    }
    try {
      render(<Harness storageKey="k7" />)
      expect(() => act(() => probe.set(["still works"]))).not.toThrow()
      // In-memory state stays authoritative even when the cache is unusable.
      expect(probe.value).toEqual(["still works"])
    } finally {
      Storage.prototype.setItem = real
    }
  })

  it("does not re-run its effects when given inline options each render", () => {
    localStorage.setItem("k8", JSON.stringify(["x"]))
    let persists = 0
    const { rerender } = render(
      <Harness storageKey="k8" options={{ onPersist: () => persists++ }} />
    )
    const after = persists
    // New inline object identity on every render must not retrigger the write.
    rerender(<Harness storageKey="k8" options={{ onPersist: () => persists++ }} />)
    rerender(<Harness storageKey="k8" options={{ onPersist: () => persists++ }} />)
    expect(persists).toBe(after)
  })
})
