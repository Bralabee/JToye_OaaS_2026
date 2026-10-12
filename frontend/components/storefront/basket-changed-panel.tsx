"use client"

/**
 * C1 — "Your basket has changed" (Phase 37-34, UI-SPEC § C1; D-16; RWO-007 / UXT-007).
 *
 * RED scaffold: exports every name the tests import and renders nothing, so the suites LOAD and fail
 * on their planned assertions (a missing module is a load failure, which the RED classifier refuses —
 * the 37-30 precedent). Replaced by the real panel in the GREEN commit.
 */

import * as React from "react"
import type { BasketChange } from "@/types/storefront"

export const BASKET_CHANGED_HEADING_COPY = "Your basket has changed since you opened it"
export const BASKET_CHANGED_ACCEPT_LABEL = "Accept changes"
export const BASKET_CHANGED_REFUSAL_COPY = "Review the changes to your basket, then press Accept changes."
export const BASKET_UPDATED_NOTE_COPY = "Basket updated. Check the new total before you place your order."
export const BASKET_CONFIRMATION_REQUIRED_COPY =
  "Something went wrong preparing your order. Refresh the page and try again. Your basket is saved."
export const ALLERGENS_ALSO_CHANGED_COPY =
  "The allergens in your order have changed too. Check them below and tick the box again."

export interface BasketChangeRow {
  key: string
  text: string
  srText?: string
}

export function basketChangeCopy(
  _change: BasketChange,
  _nameFor: (productId: string | undefined) => string | null
): BasketChangeRow | null {
  return null
}

export interface BasketChangedPanelProps {
  changes: BasketChange[]
  nameFor: (productId: string | undefined) => string | null
  allergensChanged: boolean
  accepted: boolean
  onAccept: () => void
  refused: boolean
  acceptRef?: React.Ref<HTMLButtonElement>
  refusalId?: string
}

export function BasketChangedPanel(_props: BasketChangedPanelProps) {
  return null
}
