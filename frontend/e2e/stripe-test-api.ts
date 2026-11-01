/**
 * Refund read-back for stripe-*.spec.ts (Phase 30 plan 30-22, PAY-01).
 *
 * RED transcription of stripe-refund-flow.spec.ts @ 217003b5 l.288-309: ONE read of
 * GET /v1/refunds?payment_intent=…, accepted when it holds one or more refunds and the FIRST
 * one is a succeeded 100-pence re_ for the intent.
 */

export interface StripeRefundRow {
  id?: string
  status?: string
  amount?: number
  payment_intent?: string
  charge?: string | null
}

export interface StripeRefundList {
  data?: StripeRefundRow[]
  has_more?: boolean
}

export type RefundVerdict =
  | { kind: "settled"; refund: StripeRefundRow & { id: string; charge: string } }
  | { kind: "pending"; reason: string }
  | { kind: "violated"; reason: string }

export interface RefundExpectation {
  intentId: string
  amountPennies: number
}

export function judgeRefundReadback(list: StripeRefundList, expected: RefundExpectation): RefundVerdict {
  const refunds = list.data ?? []
  if (refunds.length < 1) return { kind: "violated", reason: `no refund for ${expected.intentId}` }
  const refund = refunds[0]
  if (!/^re_/.test(refund.id ?? "")) return { kind: "violated", reason: "refund id prefix" }
  if (refund.status !== "succeeded") return { kind: "violated", reason: `status ${refund.status}` }
  if (refund.amount !== expected.amountPennies) return { kind: "violated", reason: `amount ${refund.amount}` }
  if (refund.payment_intent !== expected.intentId) return { kind: "violated", reason: "payment_intent" }
  return { kind: "settled", refund: refund as StripeRefundRow & { id: string; charge: string } }
}

export interface PollOptions {
  deadlineMs: number
  intervalMs: number
  sleep?: (ms: number) => Promise<void>
  now?: () => number
}

export async function pollRefundReadback(
  readList: () => Promise<StripeRefundList>,
  expected: RefundExpectation,
  _options: PollOptions,
): Promise<RefundVerdict> {
  return judgeRefundReadback(await readList(), expected)
}
