"use client"

/**
 * 37-18 RED interface stub (K2, D-14, D-08). Replaced in the GREEN commit.
 */

export type BoardStoppedReason = "signedOut" | "noAccess"

/** Where "Sign in again" goes: the sign-in page, which returns the tablet to the kitchen. */
export const KITCHEN_SIGN_IN_HREF = "/auth/signin?callbackUrl=%2Fdashboard%2Fkitchen"

/** The tab title while the board is stopped (UI-SPEC K2). */
export const STOPPED_TAB_TITLE = "STOPPED · Kitchen Display — J'Toye"

export interface KdsBoardStoppedProps {
  reason: BoardStoppedReason
}

export function KdsBoardStopped(_props: KdsBoardStoppedProps) {
  return null
}
