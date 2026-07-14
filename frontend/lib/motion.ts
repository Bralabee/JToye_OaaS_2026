// Shared motion vocabulary — single source of truth for durations, springs,
// and entrance variants. Shapes match the hand-rolled variants previously
// inlined in dashboard pages so swaps are drop-in.
import type { Transition, Variants } from "framer-motion"

export const durations = {
  fast: 0.2,
  base: 0.5,
} as const

export const springPop: Transition = {
  type: "spring",
  stiffness: 500,
  damping: 30,
}

export const fadeInUp: Variants = {
  hidden: { opacity: 0, y: 20 },
  visible: { opacity: 1, y: 0 },
}

export const staggerContainer: Variants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { staggerChildren: 0.1 },
  },
}

export const staggerItem: Variants = fadeInUp
