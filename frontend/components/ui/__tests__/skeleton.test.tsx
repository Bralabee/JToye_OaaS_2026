/**
 * Skeleton — shared loading primitive consuming the globals.css shimmer
 * keyframe via an arbitrary animate-[...] utility, with reduced-motion opt-out.
 */

import { render } from "@testing-library/react"
import { Skeleton } from "@/components/ui/skeleton"

describe("Skeleton", () => {
  it("renders a div carrying the shimmer animation class and reduced-motion opt-out", () => {
    const { container } = render(<Skeleton />)
    const el = container.firstChild as HTMLElement
    expect(el.tagName).toBe("DIV")
    expect(el).toHaveClass("animate-[shimmer_1.8s_ease-in-out_infinite]")
    expect(el).toHaveClass("motion-reduce:animate-none")
  })

  it("merges a caller className with the base classes", () => {
    const { container } = render(<Skeleton className="h-4 w-32" />)
    const el = container.firstChild as HTMLElement
    expect(el).toHaveClass("h-4")
    expect(el).toHaveClass("w-32")
    expect(el).toHaveClass("rounded-md")
  })
})
