"use client"

import { LazyMotion, MotionConfig } from "framer-motion"

// Async feature loading keeps the motion runtime out of the initial bundle;
// `strict` throws on any full `motion.` component so only `m.` slips through.
const loadFeatures = () => import("@/lib/motion-features").then((mod) => mod.default)

export function MotionProvider({ children }: { children: React.ReactNode }) {
  return (
    <LazyMotion strict features={loadFeatures}>
      <MotionConfig reducedMotion="user">{children}</MotionConfig>
    </LazyMotion>
  )
}
