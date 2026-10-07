import type { OrderDetail } from "@/types/api"

// RED skeleton (31.1-22): wrong values so the tests fail on assertions, not on a missing module.

export async function acknowledgeAllergyNote(_orderId: string): Promise<OrderDetail> {
  return {} as OrderDetail
}

export function subjectOfAccessToken(_token: string | null | undefined): string | null {
  return null
}

export function describeAllergyNoteReader(
  _acknowledgedBy: string | null | undefined,
  _viewerSubject: string | null
): string | null {
  return null
}
