/**
 * useCountUp — the reduced-motion contract: with prefers-reduced-motion the
 * hook must jump straight to the target (no tween). Intermediate tween frames
 * are deliberately NOT asserted — jsdom has no rAF timing guarantees.
 */

// Real framer-motion with useReducedMotion forced ON (overrides the global
// jest.setup mock for this file).
jest.mock("framer-motion", () => ({
  ...jest.requireActual("framer-motion"),
  useReducedMotion: () => true,
}))

import { renderHook } from "@testing-library/react"
import { useCountUp } from "@/hooks/use-count-up"

describe("useCountUp (prefers-reduced-motion)", () => {
  it("returns the target immediately under reduced motion", () => {
    const { result } = renderHook(() => useCountUp(42))
    expect(result.current).toBe(42)
  })

  it("renders 0 for a target of 0", () => {
    const { result } = renderHook(() => useCountUp(0))
    expect(result.current).toBe(0)
  })
})
