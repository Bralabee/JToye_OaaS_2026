"use client"

import { useSyncExternalStore, type ReactNode } from "react"
import { m, type Variants } from "framer-motion"
import { fadeInUp } from "@/lib/motion"
import { canEnhance, DESKTOP_MOTION_QUERY } from "@/lib/gsap-gate"

/**
 * Reveal — the mandated mobile / `prefers-reduced-motion` reveal floor
 * (sketch variant A) that degrades the GSAP desktop scenes. It reuses the
 * existing framer-motion vocabulary (`lib/motion.ts`) and coexists with GSAP:
 * on desktop-with-motion it renders its children PLAIN so it never
 * co-animates a GSAP-owned element.
 *
 * No-FOUC contract: children are ALWAYS rendered fully visible unless the
 * client has resolved that this is a floor context (mobile OR reduced-motion).
 * The server snapshot is `false` (plain, no hidden state in SSR markup), so if
 * JS never runs the content is never hidden. The floor gate is read via
 * `useSyncExternalStore` (React 19) subscribing to a matchMedia listener —
 * NOT `useEffect` + `setState`, which trips `react-hooks/set-state-in-effect`
 * (the rule that bit PR #221).
 */

const MOTION_TAGS = {
  div: m.div,
  section: m.section,
  ul: m.ul,
  li: m.li,
  span: m.span,
} as const

type RevealTag = keyof typeof MOTION_TAGS

function subscribeFloor(onChange: () => void): () => void {
  if (!canEnhance()) return () => {}
  const mql = window.matchMedia(DESKTOP_MOTION_QUERY)
  mql.addEventListener("change", onChange)
  return () => mql.removeEventListener("change", onChange)
}

/**
 * Client snapshot: the floor is active when this is NOT a desktop-with-motion
 * context. A browser without matchMedia (jsdom) counts as floor-active so the
 * reveal wrapper still renders (and its children stay visible via the mock).
 */
function getFloorSnapshot(): boolean {
  if (!canEnhance()) return true
  return !window.matchMedia(DESKTOP_MOTION_QUERY).matches
}

// Server render: never animate → children render plain + visible (no-FOUC).
function getServerSnapshot(): boolean {
  return false
}

function useFloorActive(): boolean {
  return useSyncExternalStore(subscribeFloor, getFloorSnapshot, getServerSnapshot)
}

type RevealProps = {
  children: ReactNode
  as?: RevealTag
  className?: string
  variants?: Variants
}

export function Reveal({
  children,
  as = "div",
  className,
  variants = fadeInUp,
}: RevealProps) {
  const floorActive = useFloorActive()

  // Server, pre-hydration, and desktop-with-motion all render plain + visible.
  if (!floorActive) {
    const PlainTag = as
    return <PlainTag className={className}>{children}</PlainTag>
  }

  const MotionTag = MOTION_TAGS[as] as typeof m.div
  return (
    <MotionTag
      className={className}
      initial="hidden"
      whileInView="visible"
      viewport={{ once: true, amount: 0.2 }}
      variants={variants}
    >
      {children}
    </MotionTag>
  )
}
