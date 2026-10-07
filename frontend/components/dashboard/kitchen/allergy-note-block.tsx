"use client"

// RED skeleton (31.1-22).
export interface AllergyNoteAcknowledgement {
  acknowledgedAt: string
  acknowledgedBy: string | null
}

export function AllergyNoteBlock(_props: {
  orderId: string
  orderLabel: string
  note: string | null | undefined
  acknowledgedAt?: string | null
  acknowledgedBy?: string | null
  onAcknowledged?: (ack: AllergyNoteAcknowledgement) => void
}) {
  return null
}
