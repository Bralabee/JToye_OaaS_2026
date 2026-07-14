/**
 * useCartCount — SSR-safe per-slug cart count driven by localStorage plus the
 * same-document `jtoye:cart-updated` CustomEvent (CartProvider persist effect)
 * and cross-tab `storage` events.
 */

import { renderHook, act } from "@testing-library/react"
import { useCartCount } from "@/hooks/use-cart-count"

const SLUG = "test-shop"
const KEY = `jtoye-cart-${SLUG}`

function seed(items: Array<{ quantity: number }>) {
  localStorage.setItem(KEY, JSON.stringify({ shopSlug: SLUG, items }))
}

describe("useCartCount", () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it("returns 0 when slug is undefined (SSR-safe default)", () => {
    const { result } = renderHook(() => useCartCount(undefined))
    expect(result.current).toBe(0)
  })

  it("reads the seeded localStorage cart state on mount", () => {
    seed([{ quantity: 2 }, { quantity: 3 }])
    const { result } = renderHook(() => useCartCount(SLUG))
    expect(result.current).toBe(5)
  })

  it("updates on jtoye:cart-updated with a matching slug and ignores non-matching slugs", () => {
    const { result } = renderHook(() => useCartCount(SLUG))
    expect(result.current).toBe(0)

    act(() => {
      window.dispatchEvent(
        new CustomEvent("jtoye:cart-updated", {
          detail: { slug: SLUG, itemCount: 4, totalPennies: 2000 },
        })
      )
    })
    expect(result.current).toBe(4)

    act(() => {
      window.dispatchEvent(
        new CustomEvent("jtoye:cart-updated", {
          detail: { slug: "another-shop", itemCount: 9, totalPennies: 100 },
        })
      )
    })
    expect(result.current).toBe(4)
  })

  it("re-reads localStorage on a cross-tab storage event for the slug's key", () => {
    const { result } = renderHook(() => useCartCount(SLUG))
    expect(result.current).toBe(0)

    // Simulate another tab writing the cart, then the storage event arriving.
    seed([{ quantity: 7 }])
    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: KEY }))
    })
    expect(result.current).toBe(7)
  })
})
