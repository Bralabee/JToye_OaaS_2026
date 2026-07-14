"use client"

import { useEffect, useRef, useState } from "react"
import { animate, useReducedMotion } from "framer-motion"

/**
 * Animated count-up on framer-motion's standalone animate(). Tween runs from
 * the previously displayed value (0 on first mount) to `target`; under
 * prefers-reduced-motion the value jumps straight to the target.
 * Returns a rounded integer.
 */
export function useCountUp(target: number, options?: { duration?: number }): number {
  const [value, setValue] = useState(0)
  const prevRef = useRef(0)
  const reducedMotion = useReducedMotion()
  const duration = options?.duration ?? 0.8

  useEffect(() => {
    if (reducedMotion) {
      prevRef.current = target
      // eslint-disable-next-line react-hooks/set-state-in-effect -- reduced-motion instant jump, no tween to drive updates
      setValue(target)
      return
    }
    const controls = animate(prevRef.current, target, {
      duration,
      ease: "easeOut",
      onUpdate: (latest) => setValue(latest),
    })
    prevRef.current = target
    return () => controls.stop()
  }, [target, duration, reducedMotion])

  return Math.round(value)
}
