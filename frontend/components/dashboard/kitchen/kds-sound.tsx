"use client"

/**
 * 37-17 (D-12): the kitchen's repeating new-order alert. RED interface only: the tests in
 * __tests__/kds-sound.test.tsx are written against this shape and the implementation follows.
 */

/** How often the new-order chime repeats while an order waits to be accepted. */
export const NEW_ORDER_ALERT_INTERVAL_MS = 5000

export type SoundState = "off" | "on" | "muted"

export interface KitchenAlertInputs {
  /** PENDING orders on this board's New lane. */
  pendingCount: number
  /** The operator's persisted `kds-muted` preference. */
  muted: boolean
  /** The board has stopped (37-18, K2): the chime gives way to the alarm. */
  stopped: boolean
}

export interface KitchenAlert {
  soundState: SoundState
  /** Call ONLY from a tap handler: it is the user gesture that unlocks audio. */
  enableSound: () => void
}

export function useKitchenAlert(inputs: KitchenAlertInputs): KitchenAlert {
  void inputs
  return { soundState: "off", enableSound: () => {} }
}
