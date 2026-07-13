/**
 * Unit tests for the 429-aware retry helpers (F-RATE / #88).
 *
 * These pure helpers decide (a) whether an axios error is a rate-limit signal
 * and (b) how long to wait before the next bounded retry. They must NEVER treat
 * a non-429 error (network failure, 404, 500) as rate limiting — that is what
 * lets the storefront distinguish "busy, retrying" from an authoritative empty
 * catalogue.
 */

import {
  isRateLimitError,
  getRetryDelayMs,
  BASE_DELAY_MS,
  MAX_DELAY_MS,
  MAX_RETRY_ATTEMPTS,
} from "@/lib/public-fetch-retry"

describe("isRateLimitError", () => {
  it("is true for an HTTP 429 axios error", () => {
    expect(isRateLimitError({ response: { status: 429, headers: {} } })).toBe(true)
  })

  it("is false for HTTP 500", () => {
    expect(isRateLimitError({ response: { status: 500, headers: {} } })).toBe(false)
  })

  it("is false for HTTP 404", () => {
    expect(isRateLimitError({ response: { status: 404, headers: {} } })).toBe(false)
  })

  it("is false for a network error with no response", () => {
    expect(isRateLimitError({ message: "Network Error" })).toBe(false)
  })

  it("is false for undefined and null", () => {
    expect(isRateLimitError(undefined)).toBe(false)
    expect(isRateLimitError(null)).toBe(false)
  })
})

describe("getRetryDelayMs", () => {
  it("uses exponential backoff from BASE_DELAY_MS when no Retry-After header is present", () => {
    const err = { response: { status: 429, headers: {} } }
    expect(getRetryDelayMs(err, 0)).toBe(BASE_DELAY_MS) // 800
    expect(getRetryDelayMs(err, 2)).toBe(3200) // 800 * 2**2
  })

  it("honours a numeric Retry-After header (seconds to ms)", () => {
    const err = { response: { status: 429, headers: { "retry-after": "2" } } }
    expect(getRetryDelayMs(err, 0)).toBe(2000)
  })

  it("clamps an over-large Retry-After to MAX_DELAY_MS", () => {
    const err = { response: { status: 429, headers: { "retry-after": "999" } } }
    expect(getRetryDelayMs(err, 0)).toBe(MAX_DELAY_MS) // 10_000
  })

  it("clamps runaway exponential backoff to MAX_DELAY_MS", () => {
    const err = { response: { status: 429, headers: {} } }
    // 800 * 2**10 = 819_200, far above the ceiling.
    expect(getRetryDelayMs(err, 10)).toBe(MAX_DELAY_MS)
  })

  it("falls back to backoff when Retry-After is non-numeric", () => {
    const err = { response: { status: 429, headers: { "retry-after": "later" } } }
    expect(getRetryDelayMs(err, 0)).toBe(BASE_DELAY_MS)
  })

  it("falls back to backoff when Retry-After is zero or negative", () => {
    const zero = { response: { status: 429, headers: { "retry-after": "0" } } }
    const negative = { response: { status: 429, headers: { "retry-after": "-5" } } }
    expect(getRetryDelayMs(zero, 1)).toBe(1600) // 800 * 2**1
    expect(getRetryDelayMs(negative, 1)).toBe(1600)
  })
})

describe("retry budget constants", () => {
  it("bounds the retry budget so a 429 loop can never amplify the flood", () => {
    expect(MAX_RETRY_ATTEMPTS).toBe(4)
    expect(BASE_DELAY_MS).toBeLessThan(MAX_DELAY_MS)
  })
})
