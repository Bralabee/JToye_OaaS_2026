/**
 * Motion helpers — Warm Editorial design system.
 * See DESIGN-SPEC.md §6 (Motion language).
 *
 * Principles:
 *  - Restraint: durations are measured; no springs on page transitions.
 *  - KDS-safe: no jitter, no infinite bounces. Long-running screens must sit still.
 *  - Reduced-motion-first: every export has a no-op path when the user prefers it.
 *
 * Consumers:
 *  - Use `EASE` + `DURATION` for ad-hoc `transition` objects.
 *  - Prefer the pre-baked `Variants` below (`fadeUp`, `scaleFade`, …) so the
 *    motion vocabulary stays consistent across the app.
 *  - Wrap with `useReducedMotionSafe(variant)` inside client components to
 *    honour `prefers-reduced-motion`.
 */
import { useMemo } from "react"
import {
  useReducedMotion,
  type Transition,
  type Variants,
} from "framer-motion"

/* -------------------------------------------------------------------------- */
/* Easing curves                                                              */
/* -------------------------------------------------------------------------- */

/**
 * Cubic-bezier curves mirrored from `--ease-*` tokens in `globals.css`.
 * Springs are Framer-native configs (no CSS equivalent).
 */
export const EASE = {
  standard: [0.4, 0, 0.2, 1] as const,
  emphasized: [0.3, 0, 0, 1] as const,
  decelerate: [0, 0, 0.2, 1] as const,
  accelerate: [0.4, 0, 1, 1] as const,
  spring: { type: "spring", stiffness: 400, damping: 30 } as const,
  springSoft: { type: "spring", stiffness: 280, damping: 28 } as const,
} as const

/* -------------------------------------------------------------------------- */
/* Duration scale (seconds — Framer's native unit)                            */
/* -------------------------------------------------------------------------- */

export const DURATION = {
  instant: 0.08,
  fast: 0.12,
  default: 0.18,
  moderate: 0.24,
  slow: 0.32,
  slowest: 0.48,
} as const

export type DurationKey = keyof typeof DURATION
export type EaseKey = keyof typeof EASE

/* -------------------------------------------------------------------------- */
/* Pre-baked variants                                                         */
/* -------------------------------------------------------------------------- */

/** Fade + 8px upward lift. Default entrance for page sections and cards. */
export const fadeUp: Variants = {
  hidden: { opacity: 0, y: 8 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: DURATION.default, ease: EASE.standard },
  },
  exit: {
    opacity: 0,
    y: 4,
    transition: { duration: DURATION.fast, ease: EASE.accelerate },
  },
}

/** Pure opacity fade. Use for overlays and scrims. */
export const fadeIn: Variants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { duration: DURATION.default, ease: EASE.standard },
  },
  exit: {
    opacity: 0,
    transition: { duration: DURATION.fast, ease: EASE.accelerate },
  },
}

/** Scale 0.96 → 1 with fade. Used by Dialog/Select/Popover content surfaces. */
export const scaleFade: Variants = {
  hidden: { opacity: 0, scale: 0.96 },
  visible: {
    opacity: 1,
    scale: 1,
    transition: { duration: DURATION.moderate, ease: EASE.emphasized },
  },
  exit: {
    opacity: 0,
    scale: 0.98,
    transition: { duration: DURATION.fast, ease: EASE.accelerate },
  },
}

/** Parent variant for staggered lists — use with `listItem` children. */
export const listStagger: Variants = {
  hidden: { opacity: 1 },
  visible: {
    opacity: 1,
    transition: {
      staggerChildren: 0.04,
      delayChildren: 0.02,
    },
  },
}

/** Child variant for lists. Pair with `listStagger` on the parent. */
export const listItem: Variants = {
  hidden: { opacity: 0, y: 6 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: DURATION.default, ease: EASE.standard },
  },
}

/**
 * Nav underline — width 0 → 100% from the left edge.
 * Apply to a `motion.span` positioned under the link.
 */
export const navUnderline: Variants = {
  rest: { scaleX: 0, originX: 0 },
  hover: {
    scaleX: 1,
    originX: 0,
    transition: { duration: DURATION.default, ease: EASE.standard },
  },
}

/* -------------------------------------------------------------------------- */
/* Reduced-motion helpers                                                     */
/* -------------------------------------------------------------------------- */

/** Every animatable state collapses to "visible" with zero-duration transition. */
const REDUCED_MOTION_TRANSITION: Transition = { duration: 0 }

/**
 * Strip animated offsets from a Variants object, leaving only the final
 * ("visible") state. Used when the user prefers reduced motion.
 */
function neutraliseVariants(variants: Variants): Variants {
  return {
    hidden: { opacity: 1, transition: REDUCED_MOTION_TRANSITION },
    visible: { opacity: 1, transition: REDUCED_MOTION_TRANSITION },
    exit: { opacity: 1, transition: REDUCED_MOTION_TRANSITION },
    rest: { opacity: 1, transition: REDUCED_MOTION_TRANSITION },
    hover: { opacity: 1, transition: REDUCED_MOTION_TRANSITION },
    // Preserve any extra keys callers may pass, but flatten their transitions.
    ...Object.fromEntries(
      Object.keys(variants)
        .filter(
          (key) => !["hidden", "visible", "exit", "rest", "hover"].includes(key),
        )
        .map((key) => [key, { transition: REDUCED_MOTION_TRANSITION }]),
    ),
  }
}

/**
 * Hook: returns the given variants unchanged, OR a flattened no-op variant
 * set when `prefers-reduced-motion: reduce` is active.
 *
 * ```tsx
 * const variants = useReducedMotionSafe(fadeUp);
 * return <motion.div variants={variants} initial="hidden" animate="visible" />;
 * ```
 */
export function useReducedMotionSafe(variants: Variants): Variants {
  const shouldReduceMotion = useReducedMotion()
  return useMemo(
    () => (shouldReduceMotion ? neutraliseVariants(variants) : variants),
    [shouldReduceMotion, variants],
  )
}

/** Re-exported for convenience so callsites can type their own variants. */
export type { Variants, Transition }
