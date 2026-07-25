"use client"

import type { ReactNode } from "react"
import { cn } from "@/lib/utils"
import { SafeImage } from "@/components/ui/safe-image"

/**
 * AspectFrame — an image in a fixed-ratio window.
 *
 * WHY THIS EXISTS (do not hand-roll the box again):
 *
 * `aspect-ratio` sets a PREFERRED size: it yields to content. An IN-FLOW image
 * in a NON-CLIPPING box expands that box to its own intrinsic height, and the
 * declared ratio silently does nothing.
 *
 * That shipped: the product modal declared `aspect-[4/3]` and, at a constant
 * 512px width, rendered a 900x1200 photo as 512x683 and an 858x645 as 512x385 —
 * a different shape per photo. `getComputedStyle` reported `aspect-ratio: 4 / 3`
 * the whole time, which is what makes it easy to look straight past.
 *
 * MEASURED, not assumed (each variant built and measured in a real browser):
 *
 *   in flow + no clip  -> 512x683 / 512x385   BROKEN (what shipped)
 *   in flow + clip     -> 512x384             fine
 *   out of flow + clip -> 512x384             fine (what we do)
 *
 * So EITHER guard is sufficient — clipping is what actually did the work in the
 * original one-line fix, not the absolute positioning it was first credited to.
 * This component applies both deliberately: clipping keeps `object-cover` from
 * bleeding past rounded corners anyway, and out-of-flow keeps overlay children
 * from ever being able to stretch the frame either.
 *
 * Three call sites used to spell this out by hand and one drifted. It now lives
 * here once, so the wrong version cannot be expressed at a call site.
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
        // Out of flow, so nothing here can ever stretch the frame.
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
