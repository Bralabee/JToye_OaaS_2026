"use client"

import type { ReactNode } from "react"
import { cn } from "@/lib/utils"
import { SafeImage } from "@/components/ui/safe-image"

/**
 * AspectFrame — an image in a fixed-ratio window.
 *
 * WHY THIS EXISTS (do not hand-roll the box again):
 *
 * `aspect-ratio` sets a PREFERRED size. It yields to content. An in-flow child
 * with `h-full` has no definite height to resolve against — the parent's height
 * is exactly what `aspect-ratio` is deriving — so the browser falls back to the
 * image's INTRINSIC ratio and the box stretches to match it. The declared ratio
 * silently does nothing, and every image renders a different shape.
 *
 * That shipped: the product modal declared `aspect-[4/3]` and, at a constant
 * 512px width, rendered a 900x1200 photo as 512x683, a 675x1200 as 512x910 and
 * an 858x645 as 512x385. `getComputedStyle` reported `aspect-ratio: 4 / 3` the
 * whole time, which is what makes it easy to look straight past.
 *
 * The frame only holds when the image is OUT OF FLOW inside a positioned,
 * clipping box. Three call sites used to spell that out by hand and one of them
 * drifted. It now lives here once, so the wrong version cannot be expressed.
 *
 * Overlays (badges, carousel arrows, dot indicators) go in `children` and are
 * positioned against the frame, which is `relative` for exactly that reason.
 *
 * Enforced by `test-utils/aspect-frame-contract.ts`, which any component test
 * can point at its own rendered output.
 */

/**
 * Ratio -> class. A LOOKUP, not interpolation: Tailwind's JIT only emits
 * classes it can read as literals in the source, so building the class name by
 * interpolating the ratio into a template would emit nothing at all — the
 * frame would silently collapse, the same failure by a different route.
 * (Guarded by the "Tailwind safety" case in this component's test, which is
 * why that forbidden pattern is not spelled out here.)
 */
const RATIO_CLASS = {
  "1/1": "aspect-square",
  "4/3": "aspect-[4/3]",
  "3/2": "aspect-[3/2]",
  "16/9": "aspect-video",
  "3/1": "aspect-[3/1]",
} as const

export type AspectRatio = keyof typeof RATIO_CLASS

interface AspectFrameProps {
  ratio: AspectRatio
  src: string | null | undefined
  alt: string
  /** Extra classes for the FRAME (background, flex behaviour, rounding). */
  className?: string
  /** Rendered inside the frame, above the image — badges, arrows, dots. */
  children?: ReactNode
  loading?: "lazy" | "eager"
  /** Intrinsic pixel size of the source, forwarded for CLS reservation. */
  width?: number
  height?: number
  fallbackIcon?: ReactNode
}

export function AspectFrame({
  ratio,
  src,
  alt,
  className,
  children,
  loading = "lazy",
  width,
  height,
  fallbackIcon,
}: AspectFrameProps) {
  return (
    <div
      data-aspect-frame={ratio}
      className={cn("relative overflow-hidden", RATIO_CLASS[ratio], className)}
    >
      <SafeImage
        src={src}
        alt={alt}
        // absolute: the whole point. In flow, this collapses back to the bug.
        className="absolute inset-0 h-full w-full object-cover"
        fallbackClassName="absolute inset-0 flex h-full w-full items-center justify-center"
        fallbackIcon={fallbackIcon}
        loading={loading}
        width={width}
        height={height}
      />
      {children}
    </div>
  )
}
