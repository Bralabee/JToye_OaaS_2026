/**
 * @jest-environment node
 *
 * Phase 30 plan 30-22 (PAY-01) — the Stripe refund E2E must read back EXACTLY ONE refund.
 *
 * stripe-refund-flow.spec.ts only runs in the keyed nightly phase, so its read-back rule
 * lives in e2e/stripe-test-api.ts and is proven here, on every PR, against fixtures shaped
 * like GET /v1/refunds?payment_intent=… responses. The rule it replaces
 * (`toBeGreaterThanOrEqual(1)` then `refunds[0]`, one read) passed a duplicate refund.
 */
import {
  judgeRefundReadback,
  pollRefundReadback,
  type StripeRefundList,
  type StripeRefundRow,
} from "../e2e/stripe-test-api"

const INTENT = "pi_3ReadbackFixture0001"
const EXPECTED = { intentId: INTENT, amountPennies: 100 }

function refund(overrides: Partial<StripeRefundRow> = {}): StripeRefundRow {
  return {
    id: "re_3ReadbackFixture0001",
    status: "succeeded",
    amount: 100,
    payment_intent: INTENT,
    charge: "ch_3ReadbackFixture0001",
    ...overrides,
  }
}

function list(rows: StripeRefundRow[], hasMore = false): StripeRefundList {
  return { data: rows, has_more: hasMore }
}

describe("judgeRefundReadback", () => {
  it("settles on exactly one succeeded 100-pence refund for the intent", () => {
    const verdict = judgeRefundReadback(list([refund()]), EXPECTED)
    expect(verdict.kind).toBe("settled")
  })

  it("refuses a duplicate refund (two refunds for one intent)", () => {
    const verdict = judgeRefundReadback(
      list([refund(), refund({ id: "re_3ReadbackFixture0002" })]),
      EXPECTED,
    )
    expect(verdict.kind).toBe("violated")
    expect(verdict.kind === "violated" && verdict.reason).toMatch(/2 refunds/)
  })

  it("refuses a page that says more refunds exist beyond it", () => {
    expect(judgeRefundReadback(list([refund()], true), EXPECTED).kind).toBe("violated")
  })

  it("keeps polling while Stripe holds no refund yet", () => {
    expect(judgeRefundReadback(list([]), EXPECTED).kind).toBe("pending")
  })

  it("keeps polling while the one refund is still pending", () => {
    expect(judgeRefundReadback(list([refund({ status: "pending" })]), EXPECTED).kind).toBe(
      "pending",
    )
  })

  it("refuses a failed or canceled refund", () => {
    expect(judgeRefundReadback(list([refund({ status: "failed" })]), EXPECTED).kind).toBe(
      "violated",
    )
    expect(judgeRefundReadback(list([refund({ status: "canceled" })]), EXPECTED).kind).toBe(
      "violated",
    )
  })

  it("refuses the wrong amount and another intent's refund", () => {
    expect(judgeRefundReadback(list([refund({ amount: 200 })]), EXPECTED).kind).toBe("violated")
    expect(
      judgeRefundReadback(list([refund({ payment_intent: "pi_other" })]), EXPECTED).kind,
    ).toBe("violated")
  })

  it("refuses a refund with no charge id, so the charge's livemode is always read", () => {
    expect(judgeRefundReadback(list([refund({ charge: null })]), EXPECTED).kind).toBe("violated")
  })
})

describe("pollRefundReadback", () => {
  function clock() {
    let t = 0
    return {
      now: () => t,
      sleep: async (ms: number) => {
        t += ms
      },
    }
  }

  it("polls through pending reads and settles only after a confirming read", async () => {
    const reads = [list([]), list([refund({ status: "pending" })]), list([refund()]), list([refund()])]
    let n = 0
    const c = clock()
    const verdict = await pollRefundReadback(async () => reads[Math.min(n++, reads.length - 1)], EXPECTED, {
      deadlineMs: 30_000,
      intervalMs: 1_000,
      ...c,
    })
    expect(verdict.kind).toBe("settled")
    expect(n).toBe(4)
  })

  it("refuses a duplicate that appears on the confirming read", async () => {
    const reads = [list([refund()]), list([refund(), refund({ id: "re_3ReadbackFixture0002" })])]
    let n = 0
    const c = clock()
    const verdict = await pollRefundReadback(async () => reads[Math.min(n++, reads.length - 1)], EXPECTED, {
      deadlineMs: 30_000,
      intervalMs: 1_000,
      ...c,
    })
    expect(verdict.kind).toBe("violated")
  })

  it("gives up at the deadline with the last pending reason", async () => {
    const c = clock()
    let n = 0
    const verdict = await pollRefundReadback(
      async () => {
        n++
        return list([])
      },
      EXPECTED,
      { deadlineMs: 30_000, intervalMs: 1_000, ...c },
    )
    expect(verdict.kind).toBe("violated")
    expect(verdict.kind === "violated" && verdict.reason).toMatch(/30s/)
    expect(n).toBeGreaterThanOrEqual(30)
  })
})
