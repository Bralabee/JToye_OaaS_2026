/**
 * Reusable assertion: every fixed-ratio image window in a rendered tree is
 * structurally sound.
 *
 * jsdom has no layout engine, so we cannot measure that a 4:3 box is 4:3. What
 * we CAN do is assert the structure that produces the geometry.
 *
 * `aspect-ratio` yields to content: an IN-FLOW image in a NON-CLIPPING box
 * expands it to the image's intrinsic height, and the declared ratio silently
 * does nothing. Measured in a real browser, at a constant 512px width:
 *
 *   in flow + no clip  -> 512x683 / 512x385   BROKEN
 *   in flow + clip     -> 512x384             fine
 *   out of flow + clip -> 512x384             fine
 *
 * So the invariant is a DISJUNCTION — the frame clips, OR its images are out of
 * flow — and this contract encodes exactly that rather than a stricter rule.
 * Over-strict guards flag sound code and get disabled, which is worse than no
 * guard. (`AspectFrame` itself does both; that is its choice, not the rule.)
 *
 * `relative` is separately required whenever anything is pinned to the frame,
 * or inset-0 resolves against the wrong box.
 *
 * The geometric truth is checked in a real browser by
 * `e2e/public-layout.spec.ts`; this is the cheap in-CI approximation of it.
 *
 * Point this at any rendered container:
 *
 *   const { container } = render(<Thing />)
 *   expectSoundAspectFrames(container)
 *
 * It is a no-op for trees with no aspect frames, so it is safe to add to any
 * component test as a standing guard. `expectedFrames` makes it a positive
 * assertion too — pass a count when the test knows how many there should be, so
 * a frame that disappears entirely cannot pass silently.
 */

/** Matches Tailwind's fixed-ratio utilities: aspect-square / -video / -[a/b]. */
const ASPECT_CLASS = /(^|\s)aspect-(square|video|\[[^\]]+\])(\s|$)/

export function findAspectFrames(container: HTMLElement): HTMLElement[] {
  const all = [container, ...Array.from(container.querySelectorAll("*"))]
  return all.filter(
    (el): el is HTMLElement =>
      el instanceof HTMLElement && ASPECT_CLASS.test(el.className || "")
  )
}

/** Returns a human-readable violation per unsound frame; empty means sound. */
export function findAspectFrameViolations(container: HTMLElement): string[] {
  const violations: string[] = []

  for (const frame of findAspectFrames(container)) {
    const cls = frame.className
    const label = `aspect frame "${cls}"`
    const clips = /(^|\s)overflow-hidden(\s|$)/.test(cls)

    for (const img of Array.from(frame.querySelectorAll("img"))) {
      const imgCls = img.className || ""

      // Not ours: the image belongs to a nested frame, or sits in its own
      // definite-height box (e.g. an `h-10 w-10` carousel thumbnail), where
      // `h-full` has something real to resolve against.
      const owningFrame = img.closest("[data-aspect-frame]")
      if (owningFrame && owningFrame !== frame) continue
      if (/(^|\s)h-\d/.test(img.parentElement?.className || "")) continue

      const outOfFlow =
        /(^|\s)absolute(\s|$)/.test(imgCls) && /(^|\s)inset-0(\s|$)/.test(imgCls)

      // The defect needs BOTH: an in-flow image AND a box that lets it grow.
      if (!outOfFlow && !clips) {
        violations.push(
          `${label} neither clips nor takes its image out of flow ` +
            `(image class "${imgCls}") — the image's intrinsic height will ` +
            `expand the box and the declared ratio will be ignored. Add ` +
            `"overflow-hidden" to the frame, or "absolute inset-0" to the image.`
        )
      }

      // Anything pinned needs a positioned frame to pin against.
      if (outOfFlow && !/(^|\s)relative(\s|$)/.test(cls)) {
        violations.push(
          `${label} is missing "relative", so the pinned image resolves ` +
            `against the wrong box`
        )
      }
    }
  }

  return violations
}

export function expectSoundAspectFrames(
  container: HTMLElement,
  expectedFrames?: number
): void {
  if (typeof expectedFrames === "number") {
    expect(findAspectFrames(container)).toHaveLength(expectedFrames)
  }
  // Assert on the array so a failure prints the offending frame + why.
  expect(findAspectFrameViolations(container)).toEqual([])
}
