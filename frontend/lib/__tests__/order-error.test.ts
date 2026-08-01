import {
  describeOrderError,
  retryAfterSeconds,
  GENERIC_ORDER_ERROR,
} from "@/lib/order-error"

/**
 * #409. The rate limiter answers 429 with `Retry-After` and an `error`/`message`
 * body — NOT the RFC 7807 `detail` the checkout used to read. The shopper was
 * told "Failed to place order. Please try again.", which invites the immediate
 * retry that re-trips the limit.
 *
 * The shape below is copied verbatim from the live stack on 2026-08-01:
 *   HTTP/1.1 429 / Retry-After: 19
 *   {"error":"Too Many Requests","message":"Rate limit exceeded. Please try again in 19 seconds."}
 */
const rateLimited = (retryAfter?: unknown) => ({
  response: {
    status: 429,
    headers: retryAfter === undefined ? {} : { "retry-after": retryAfter },
    data: {
      error: "Too Many Requests",
      message: "Rate limit exceeded. Please try again in 19 seconds.",
    },
  },
})

describe("describeOrderError — #409", () => {
  describe("429 rate limiting", () => {
    it("names the wait in seconds from Retry-After", () => {
      const msg = describeOrderError(rateLimited(19))
      expect(msg).toContain("19 seconds")
      // The whole point: it must NOT tell the shopper to retry now.
      expect(msg).not.toBe(GENERIC_ORDER_ERROR)
    })

    it("reassures that nothing was charged and the basket survives", () => {
      const msg = describeOrderError(rateLimited(19))
      expect(msg).toMatch(/nothing has been charged/i)
      expect(msg).toMatch(/basket is safe/i)
    })

    it("reads Retry-After case-insensitively", () => {
      const err = {
        response: { status: 429, headers: { "Retry-After": "7" }, data: {} },
      }
      expect(describeOrderError(err)).toContain("7 seconds")
    })

    it("singularises one second", () => {
      expect(describeOrderError(rateLimited(1))).toContain("1 second and")
    })

    // A malformed header must degrade to the vaguer sentence, never render
    // "try again in NaN seconds".
    it.each([["not-a-number"], [""], ["Wed, 21 Oct 2026 07:28:00 GMT"]])(
      "falls back to an unquantified wait when Retry-After is %p",
      (value) => {
        const msg = describeOrderError(rateLimited(value))
        expect(msg).toMatch(/wait a moment/i)
        expect(msg).not.toMatch(/NaN/)
      }
    )

    it("still says to wait when Retry-After is absent entirely", () => {
      const msg = describeOrderError(rateLimited())
      expect(msg).toMatch(/wait a moment/i)
      expect(msg).not.toMatch(/undefined|NaN/)
    })
  })

  describe("other server shapes", () => {
    it("prefers RFC 7807 detail", () => {
      const err = {
        response: { status: 422, data: { detail: "Minimum order is £10.00." } },
      }
      expect(describeOrderError(err)).toBe("Minimum order is £10.00.")
    })

    it("falls back to the non-7807 message field", () => {
      const err = { response: { status: 400, data: { message: "Shop is closed." } } }
      expect(describeOrderError(err)).toBe("Shop is closed.")
    })

    it("ignores a whitespace-only detail rather than blanking the error", () => {
      const err = {
        response: { status: 500, data: { detail: "   ", message: "Upstream failed." } },
      }
      expect(describeOrderError(err)).toBe("Upstream failed.")
    })

    it("uses the generic copy when the body carries nothing usable", () => {
      expect(describeOrderError({ response: { status: 500, data: {} } })).toBe(
        GENERIC_ORDER_ERROR
      )
    })

    it("uses the generic copy for a network error with no response", () => {
      expect(describeOrderError(new Error("Network Error"))).toBe(GENERIC_ORDER_ERROR)
      expect(describeOrderError(undefined)).toBe(GENERIC_ORDER_ERROR)
    })
  })

  describe("retryAfterSeconds", () => {
    it("returns null when there is no response or no header", () => {
      expect(retryAfterSeconds(new Error("boom"))).toBeNull()
      expect(retryAfterSeconds({ response: { status: 429 } })).toBeNull()
    })

    it("rounds a fractional delta up, so the advice is never too short", () => {
      expect(retryAfterSeconds(rateLimited("2.1"))).toBe(3)
    })

    it("rejects a negative delta", () => {
      expect(retryAfterSeconds(rateLimited("-5"))).toBeNull()
    })
  })
})
