/**
 * Stripe TEST-mode helpers for stripe-*.spec.ts (Phase 30 plan 30-22, PAY-01, #61).
 *
 * WHY STRIPE CALLS DO NOT GO THROUGH PLAYWRIGHT'S `request` FIXTURE (threat T-30-87):
 *   The nightly runs these specs with the CI sandbox's sk_test_ secret, uploads its artifacts
 *   for 14 days, and the repository is PUBLIC. Measured 2026-11-01 (evidence
 *   30-22-fix-1065.txt): a Stripe call made with `request.post(..., { headers: { Authorization } })`
 *   puts the secret in plaintext in THREE places the moment the test fails:
 *     1. trace.zip (trace: "retain-on-failure" keeps the request headers);
 *     2. the JSON report's error message — on a transport error, APIRequestContext's call log
 *        lists every request header, Authorization included;
 *     3. error-context.md beside the failed test, which repeats that call log.
 *   `trace: "off"` closes (1) only. So the secret never enters Playwright at all: the client
 *   below is Node's own `fetch`, which no trace, call log or reporter records, and its error
 *   messages are built here, with the key redacted, never echoed from a request.
 *   The nightly adds two outer layers: the Stripe run's Playwright output goes to a directory
 *   that is not uploaded, and `scripts/check-stripe-ci-keys.sh --scan-artifacts` searches every
 *   uploaded path (zip members included) for the secret before upload.
 */

/** Applied at the TOP LEVEL of every stripe-*.spec.ts: `test.use(STRIPE_SPEC_ARTIFACTS)`. */
export const STRIPE_SPEC_ARTIFACTS = { trace: "off", video: "off" } as const

export const STRIPE_API_BASE = "https://api.stripe.com"

export function requireStripeTestSecretKey(): string {
  const key = process.env.STRIPE_TEST_SECRET_KEY ?? ""
  if (!key.startsWith("sk_test_")) {
    const shown = key === "" ? "<unset>" : `${key.slice(0, 8)}...`
    throw new Error(
      `STRIPE_TEST_SECRET_KEY must be a Stripe test secret key (sk_test_...); got ${shown}. ` +
        "This spec runs only when STRIPE_E2E=1 and never skips.",
    )
  }
  return key
}

export interface StripeResponse<T> {
  status: number
  body: T
}

export interface StripeTestClient {
  get<T>(path: string): Promise<StripeResponse<T>>
  post<T>(path: string, form: Record<string, string>): Promise<StripeResponse<T>>
}

/**
 * A Stripe REST client over Node `fetch`. `baseUrl` exists for the leak probe only; the specs
 * use the default. Returns status and parsed body — never request or response headers.
 */
export function stripeTestClient(options: { secretKey: string; baseUrl?: string }): StripeTestClient {
  const base = options.baseUrl ?? STRIPE_API_BASE
  const key = options.secretKey
  const redact = (text: string) => (key === "" ? text : text.split(key).join("sk_test_[redacted]"))

  async function call<T>(method: "GET" | "POST", path: string, form?: Record<string, string>) {
    let res: Response
    try {
      res = await fetch(`${base}${path}`, {
        method,
        headers: {
          Authorization: `Bearer ${key}`,
          ...(form ? { "Content-Type": "application/x-www-form-urlencoded" } : {}),
        },
        body: form ? new URLSearchParams(form).toString() : undefined,
      })
    } catch (err) {
      const name = err instanceof Error ? err.name : "Error"
      const cause = err instanceof Error && err.cause instanceof Error ? `: ${err.cause.message}` : ""
      throw new Error(redact(`Stripe ${method} ${path} did not complete (${name}${cause})`))
    }
    const text = await res.text()
    let body: unknown
    try {
      body = text === "" ? {} : JSON.parse(text)
    } catch {
      throw new Error(`Stripe ${method} ${path} returned HTTP ${res.status} with a body that is not JSON`)
    }
    return { status: res.status, body: body as T }
  }

  return {
    get: <T>(path: string) => call<T>("GET", path),
    post: <T>(path: string, form: Record<string, string>) => call<T>("POST", path, form),
  }
}

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

/**
 * One read of GET /v1/refunds?payment_intent=<intent>. EXACTLY one refund is the only state
 * that can settle: a second refund never disappears, so more than one is a violation at once,
 * not something to wait out. A refund with no charge id is refused because the charge is what
 * carries livemode (the Refund object has no livemode field to fall back on).
 */
export function judgeRefundReadback(list: StripeRefundList, expected: RefundExpectation): RefundVerdict {
  const intent = expected.intentId
  if (!Array.isArray(list.data)) {
    return { kind: "violated", reason: `the refund list for ${intent} has no data array` }
  }
  const refunds = list.data
  if (list.has_more === true) {
    return {
      kind: "violated",
      reason: `Stripe reports more refunds for ${intent} beyond this page — expected exactly one`,
    }
  }
  if (refunds.length === 0) return { kind: "pending", reason: `no refund for ${intent} yet` }
  if (refunds.length > 1) {
    return {
      kind: "violated",
      reason: `${refunds.length} refunds for ${intent} — expected exactly one (a duplicate refund)`,
    }
  }
  const refund = refunds[0]
  if (refund.payment_intent !== intent) {
    return { kind: "violated", reason: `refund ${refund.id} belongs to ${refund.payment_intent}, not ${intent}` }
  }
  if (!/^re_/.test(refund.id ?? "")) {
    return { kind: "violated", reason: `refund id ${refund.id} does not start re_` }
  }
  if (refund.amount !== expected.amountPennies) {
    return {
      kind: "violated",
      reason: `refund ${refund.id} is ${refund.amount} pence, expected ${expected.amountPennies}`,
    }
  }
  if (typeof refund.charge !== "string" || !/^ch_/.test(refund.charge)) {
    return {
      kind: "violated",
      reason: `refund ${refund.id} names no charge, so the charge's livemode cannot be read`,
    }
  }
  switch (refund.status) {
    case "succeeded":
      return { kind: "settled", refund: refund as StripeRefundRow & { id: string; charge: string } }
    case "pending":
    case "requires_action":
      return { kind: "pending", reason: `refund ${refund.id} is ${refund.status}` }
    default:
      return { kind: "violated", reason: `refund ${refund.id} is ${refund.status}` }
  }
}

export interface PollOptions {
  deadlineMs: number
  intervalMs: number
  sleep?: (ms: number) => Promise<void>
  now?: () => number
}

/**
 * Polls until the read-back has been SETTLED on two consecutive reads (one interval apart), so a
 * duplicate that lands just after the first good read is still caught. A violation ends the poll
 * at once; the deadline turns a still-pending read-back into a violation with its last reason.
 */
export async function pollRefundReadback(
  readList: () => Promise<StripeRefundList>,
  expected: RefundExpectation,
  options: PollOptions,
): Promise<RefundVerdict> {
  const now = options.now ?? Date.now
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)))
  const deadline = now() + options.deadlineMs
  let settledReads = 0
  for (;;) {
    const verdict = judgeRefundReadback(await readList(), expected)
    if (verdict.kind === "violated") return verdict
    if (verdict.kind === "settled") {
      settledReads += 1
      if (settledReads >= 2) return verdict
    } else {
      settledReads = 0
    }
    if (now() >= deadline) {
      const last = verdict.kind === "pending" ? verdict.reason : "settled once but not confirmed by a second read"
      return {
        kind: "violated",
        reason: `no single settled refund for ${expected.intentId} within ${options.deadlineMs / 1000}s (${last})`,
      }
    }
    await sleep(options.intervalMs)
  }
}
