/**
 * Reusable assertion: every fixed-ratio image window in a rendered tree is
 * structurally sound.
 *
 * jsdom has no layout engine, so we cannot measure that a 4:3 box is 4:3. What
 * we CAN do — and what actually catches the defect — is assert the structure
 * that produces the geometry, because there is exactly one way to get it right:
 *
 *   frame:  position:relative + a declared aspect-* class + clipping
 *   image:  absolutely positioned and pinned to all four edges
 *
 * If the image is in flow, `aspect-ratio` yields to it and the box silently
 * takes the image's intrinsic ratio instead of the declared one. That is
 * invisible to every gate this repo runs in CI (Jest does no layout; the
 * Playwright specs are not wired into CI), which is how it reached a user.
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

    // Without a positioned ancestor the image's inset-0 resolves to some other
    // box entirely.
    if (!/(^|\s)relative(\s|$)/.test(cls)) {
      violations.push(`${label} is missing "relative"`)
    }

    // Clipping, so object-cover crops instead of bleeding past rounded corners.
    if (!/(^|\s)overflow-hidden(\s|$)/.test(cls)) {
      violations.push(`${label} is missing "overflow-hidden"`)
    }

    // Every image the frame is responsible for must be out of flow. An in-flow
    // one decides the frame's height instead of obeying it — the defect.
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

      if (!outOfFlow) {
        violations.push(
          `image in ${label} is IN FLOW (class "${imgCls}") — it must be ` +
            `"absolute inset-0", or the frame silently takes the image's ` +
            `intrinsic ratio instead of the declared one`
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
