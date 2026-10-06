"use client"

// RED stub (31.1-20): replaced in the GREEN commit.
type Copy = { heading: string; body: string }
export const CONFIRM_COPY: Record<"verified" | "already_verified" | "invalid", Copy> = {
  verified: { heading: "", body: "" },
  already_verified: { heading: "", body: "" },
  invalid: { heading: "", body: "" },
}

export function ConfirmClient() {
  return null
}

export default ConfirmClient
