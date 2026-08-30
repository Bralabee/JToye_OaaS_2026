/**
 * FE-2: SafeImage gained an optional `fetchPriority` prop, forwarded to the
 * underlying `<img>`'s `fetchpriority` attribute — a resource-fetch hint for
 * the browser's preload scanner. Optional and undefined by default so every
 * existing call site (which passes nothing) is unaffected.
 */
import { render } from "@testing-library/react"
import { SafeImage } from "@/components/ui/safe-image"

describe("SafeImage fetchPriority (FE-2)", () => {
  it("forwards fetchPriority to the rendered <img>", () => {
    const { container } = render(
      <SafeImage src="https://example.test/banner.jpg" alt="Banner" fetchPriority="high" />
    )
    const img = container.querySelector("img")
    expect(img).toBeInTheDocument()
    expect(img).toHaveAttribute("fetchpriority", "high")
  })

  it("omits the attribute entirely when fetchPriority is not passed (backward compatible)", () => {
    const { container } = render(<SafeImage src="https://example.test/thumb.jpg" alt="Thumb" />)
    const img = container.querySelector("img")
    expect(img).toBeInTheDocument()
    expect(img).not.toHaveAttribute("fetchpriority")
  })
})
