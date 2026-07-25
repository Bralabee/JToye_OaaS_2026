/**
 * AspectFrame — the component that owns fixed-ratio image windows, plus a
 * self-test proving the shared contract actually BITES.
 *
 * A guard that cannot fail is worse than no guard: it reads as coverage while
 * catching nothing. So this file deliberately renders the broken shape (the one
 * that shipped) and asserts the contract reports it.
 */
import fs from "fs"
import path from "path"
import { render } from "@testing-library/react"
import { AspectFrame } from "../aspect-frame"
import {
  expectSoundAspectFrames,
  findAspectFrameViolations,
} from "@/test-utils/aspect-frame-contract"

describe("AspectFrame", () => {
  it("renders a sound frame for every supported ratio", () => {
    for (const ratio of ["1/1", "4/3", "3/2", "16/9", "3/1"] as const) {
      const { container, unmount } = render(
        <AspectFrame ratio={ratio} src="http://example.test/x.jpg" alt="x" />
      )
      expectSoundAspectFrames(container, 1)
      expect(
        container.querySelector(`[data-aspect-frame="${ratio}"]`)
      ).not.toBeNull()
      unmount()
    }
  })

  it("keeps the image out of flow even with overlay children", () => {
    const { container } = render(
      <AspectFrame ratio="4/3" src="http://example.test/x.jpg" alt="x">
        <div className="absolute bottom-2">overlay</div>
      </AspectFrame>
    )
    expectSoundAspectFrames(container, 1)
  })

  it("keeps the frame when there is no image (fallback path)", () => {
    const { container } = render(
      <AspectFrame ratio="4/3" src={null} alt="" fallbackIcon={<span>none</span>} />
    )
    // The frame must survive: a missing image must not collapse the layout.
    expect(container.querySelector('[data-aspect-frame="4/3"]')).not.toBeNull()
    expectSoundAspectFrames(container, 1)
  })
})

describe("aspect-frame contract self-test", () => {
  it("REPORTS a hand-rolled frame whose image is in flow (the shipped defect)", () => {
    const { container } = render(
      <div className="relative aspect-[4/3] overflow-hidden">
        {/* The exact shape that shipped: in-flow image with h-full. */}
        <img className="w-full h-full object-cover" alt="broken" />
      </div>
    )
    const violations = findAspectFrameViolations(container)
    expect(violations).toHaveLength(1)
    expect(violations[0]).toMatch(/IN FLOW/)
  })

  it("REPORTS a frame that does not clip or is not positioned", () => {
    const { container } = render(
      <div className="aspect-[4/3]">
        <img className="absolute inset-0 h-full w-full object-cover" alt="x" />
      </div>
    )
    const violations = findAspectFrameViolations(container)
    expect(violations).toEqual(
      expect.arrayContaining([
        expect.stringMatching(/missing "relative"/),
        expect.stringMatching(/missing "overflow-hidden"/),
      ])
    )
  })

  it("passes a sound hand-rolled frame (no false positives)", () => {
    const { container } = render(
      <div className="relative aspect-square overflow-hidden">
        <img className="absolute inset-0 h-full w-full object-cover" alt="x" />
      </div>
    )
    expect(findAspectFrameViolations(container)).toEqual([])
  })

  it("is a no-op on trees with no frames, so any test can adopt it", () => {
    const { container } = render(<div className="p-4">nothing here</div>)
    expect(findAspectFrameViolations(container)).toEqual([])
  })
})

describe("AspectFrame Tailwind safety", () => {
  it("spells every ratio class as a literal (JIT cannot see interpolation)", () => {
    const src = fs.readFileSync(
      path.join(process.cwd(), "components/ui/aspect-frame.tsx"),
      "utf8"
    )
    // A template like aspect-[${ratio}] compiles to NOTHING: Tailwind scans
    // source text, so the class would never be generated and the frame would
    // silently collapse — the same failure mode by a different route.
    expect(src).not.toMatch(/aspect-\[\$\{/)
    for (const literal of [
      "aspect-square",
      "aspect-[4/3]",
      "aspect-[3/2]",
      "aspect-video",
      "aspect-[3/1]",
    ]) {
      expect(src).toContain(literal)
    }
  })
})
