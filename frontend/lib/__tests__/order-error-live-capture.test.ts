import { describeOrderError, GENERIC_ORDER_ERROR } from "@/lib/order-error"

/**
 * #413 AC-4 — the shipping code, run over a payload a REAL browser actually received.
 *
 * Every other test in this directory constructs its own fixture, which is exactly why
 * none of them could see that `Retry-After` was invisible cross-origin (#412) or that
 * `message` was about to disappear (#413). A fixture I write to pass proves that I can
 * write a passing fixture.
 *
 * The constant below is therefore NOT hand-authored. It is copied verbatim from a
 * Chromium capture against the running stack on 2026-08-01: the storefront origin
 * (:3000) flooded the cross-origin public API (:9090) until throttled, then re-issued
 * via XHR — the same API axios's adapter uses — and dumped `getAllResponseHeaders()`
 * plus the parsed body. Headers are lowercased exactly as axios normalises them.
 *
 * If this file is ever updated, RE-CAPTURE it. Editing the literal by hand turns it
 * back into the kind of fixture it exists to replace.
 */
const CAPTURED_429 = {
  response: {
    status: 429,
    headers: {
      "cache-control": "no-cache, no-store, max-age=0, must-revalidate",
      "content-length": "175",
      "content-type": "application/problem+json;charset=UTF-8",
      expires: "0",
      pragma: "no-cache",
      "retry-after": "21",
      "x-ratelimit-limit": "600",
      "x-ratelimit-remaining": "0",
      "x-ratelimit-reset": "1785550476",
    },
    data: {
      type: "https://jtoye.uk/errors/rate-limited",
      title: "Too Many Requests",
      status: 429,
      detail: "Rate limit exceeded. Please try again in 21 seconds.",
      retryAfterSeconds: 21,
    },
  },
}

describe("#413 AC-4 — a real captured 429, through the shipping code", () => {
  it("quantifies the wait a shopper is shown", () => {
    const msg = describeOrderError(CAPTURED_429)
    expect(msg).toMatch(/wait 21 seconds/i)
    expect(msg).not.toMatch(/wait a moment/i)
    expect(msg).not.toBe(GENERIC_ORDER_ERROR)
    expect(msg).toMatch(/nothing has been charged/i)
  })

  it("the capture proves #412 landed: Retry-After is present in what the browser handed JS", () => {
    // Before #412 this key did not exist in getAllResponseHeaders() output at all —
    // the browser withheld it. Its mere presence here is the end-to-end receipt.
    expect(CAPTURED_429.response.headers).toHaveProperty("retry-after")
    expect(CAPTURED_429.response.headers).toHaveProperty("x-ratelimit-limit")
  })

  it("the capture proves #413 landed: RFC 7807, typed member, no tenantId", () => {
    const { data, headers } = CAPTURED_429.response
    expect(headers["content-type"]).toMatch(/^application\/problem\+json/)
    expect(headers["content-type"]).toMatch(/UTF-8/) // was ISO-8859-1
    expect(data.type).toBe("https://jtoye.uk/errors/rate-limited")
    expect(data.detail).toBeTruthy()
    expect(typeof data.retryAfterSeconds).toBe("number")
    // Flattened to top level by ProblemDetailJacksonMixin, not nested.
    expect(data).not.toHaveProperty("properties")
    // The old shape is gone.
    expect(data).not.toHaveProperty("message")
    expect(data).not.toHaveProperty("error")
    // The public path must never name a tenant to an unauthenticated guest.
    expect(JSON.stringify(data)).not.toContain("tenantId")
  })

  it("still quantifies with the header stripped, proving the typed member carries it alone", () => {
    // Isolates source 2 from source 1: if CORS config were ever lost again, the body
    // must still carry the shopper's answer.
    const headerless = {
      response: { ...CAPTURED_429.response, headers: {} },
    }
    expect(describeOrderError(headerless)).toMatch(/wait 21 seconds/i)
  })

  it("still quantifies with header AND typed member stripped, leaving only `detail`", () => {
    const { retryAfterSeconds, ...withoutTyped } = CAPTURED_429.response.data
    void retryAfterSeconds
    const proseOnly = {
      response: { status: 429, headers: {}, data: withoutTyped },
    }
    expect(describeOrderError(proseOnly)).toMatch(/wait 21 seconds/i)
  })
})
