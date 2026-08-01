import {
  describeOrderError,
  retryAfterSeconds,
  secondsFromMessage,
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

    // A malformed header must never render "try again in NaN seconds". It now
    // recovers the number from the BODY instead — which is the path that
    // actually runs in a browser, since the header is hidden cross-origin.
    it.each([["not-a-number"], [""], ["Wed, 21 Oct 2026 07:28:00 GMT"]])(
      "recovers the wait from the body when Retry-After is %p",
      (value) => {
        const msg = describeOrderError(rateLimited(value))
        expect(msg).toContain("19 seconds")
        expect(msg).not.toMatch(/NaN|undefined/)
      }
    )

    it("recovers from the body when Retry-After is absent entirely", () => {
      const msg = describeOrderError(rateLimited())
      expect(msg).toContain("19 seconds")
      expect(msg).not.toMatch(/NaN|undefined/)
    })

    // Only when BOTH sources are unusable does it fall to the vaguer sentence.
    it.each([
      ["a body with no duration", "Rate limit exceeded."],
      ["an empty body message", ""],
    ])("falls back to an unquantified wait given %s", (_label, message) => {
      const err = { response: { status: 429, headers: {}, data: { message } } }
      const msg = describeOrderError(err)
      expect(msg).toMatch(/wait a moment/i)
      expect(msg).not.toMatch(/NaN|undefined|\d+ seconds/)
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

  // Retry-After is not CORS-safelisted and the API is cross-origin, so a real
  // browser NEVER sees the header. Measured 2026-08-01: every shopper got the
  // unquantified copy while these unit tests were green, because they build the
  // header directly. The body is readable and carries the same number.
  describe("browser reality: header hidden, body readable", () => {
    it("quantifies the wait from the BODY when the header is absent", () => {
      const err = {
        response: {
          status: 429,
          headers: {}, // exactly what a cross-origin browser response looks like
          data: {
            error: "Too Many Requests",
            message: "Rate limit exceeded. Please try again in 19 seconds.",
          },
        },
      }
      expect(describeOrderError(err)).toContain("19 seconds")
    })

    it("prefers the header when both are present", () => {
      const err = {
        response: {
          status: 429,
          headers: { "retry-after": "5" },
          data: { message: "Rate limit exceeded. Please try again in 19 seconds." },
        },
      }
      expect(describeOrderError(err)).toContain("5 seconds")
    })
  })

  /**
   * #413 reshaped the limiter's 429 body to RFC 7807. `message` is GONE and the
   * sentence moved to `detail`, alongside a typed `retryAfterSeconds` member.
   *
   * Copied verbatim from the live stack after the change:
   *   HTTP/1.1 429 / Content-Type: application/problem+json
   *   {"type":"https://jtoye.uk/errors/rate-limited","title":"Too Many Requests",
   *    "status":429,"detail":"Rate limit exceeded. Please try again in 19 seconds.",
   *    "retryAfterSeconds":19}
   */
  const rateLimited7807 = (over: Record<string, unknown> = {}) => ({
    response: {
      status: 429,
      headers: {},
      data: {
        type: "https://jtoye.uk/errors/rate-limited",
        title: "Too Many Requests",
        status: 429,
        detail: "Rate limit exceeded. Please try again in 19 seconds.",
        retryAfterSeconds: 19,
        ...over,
      },
    },
  })

  describe("#413: the RFC 7807 rate-limit body", () => {
    it("quantifies the wait from the TYPED retryAfterSeconds member", () => {
      const msg = describeOrderError(rateLimited7807())
      expect(msg).toContain("19 seconds")
      expect(msg).not.toBe(GENERIC_ORDER_ERROR)
    })

    it("prefers the typed member over re-parsing the prose", () => {
      // Same response, disagreeing sources. The typed member wins, so a reworded
      // sentence can never silently change the advice.
      const msg = describeOrderError(
        rateLimited7807({
          retryAfterSeconds: 7,
          detail: "Rate limit exceeded. Please try again in 19 seconds.",
        })
      )
      expect(msg).toContain("7 seconds")
      expect(msg).not.toContain("19 seconds")
    })

    it("still quantifies from `detail` when the typed member is absent", () => {
      // A stale core-java, or a future body that keeps the sentence and drops the
      // number, must not fall back to the vague copy.
      const msg = describeOrderError(rateLimited7807({ retryAfterSeconds: undefined }))
      expect(msg).toContain("19 seconds")
    })

    it("REGRESSION GUARD: the post-#413 body must not fall back to the vague copy", () => {
      // THIS is the failure the issue warned about. Before this file was updated,
      // the 429 branch read only `data.message` — which #413 removed — so the
      // quantified wait silently degraded to "wait a moment" with NOTHING going
      // red: the server tests do not know about the frontend, and the other tests
      // in this file construct the pre-#413 fixture.
      //
      // The body below is the real post-#413 shape and carries NO `message` field.
      const msg = describeOrderError(rateLimited7807())
      expect(msg).not.toMatch(/wait a moment/i)
      expect(msg).toMatch(/wait 19 seconds/i)
    })

    it("rejects a non-positive or non-finite typed member rather than saying 'wait 0 seconds'", () => {
      // Same trap `Number("")` sprang on the header path: 0 is falsy-but-numeric,
      // and "wait 0 seconds" is advice that is both wrong and useless. It must fall
      // through to `detail`, which still carries a usable number here.
      for (const bad of [0, -5, Number.NaN, Number.POSITIVE_INFINITY]) {
        const msg = describeOrderError(rateLimited7807({ retryAfterSeconds: bad }))
        expect(msg).toContain("19 seconds") // recovered from detail, not from `bad`
      }
    })

    it("still prefers the header over everything, once #412 makes it readable", () => {
      const msg = describeOrderError({
        response: {
          status: 429,
          headers: { "retry-after": "3" },
          data: rateLimited7807().response.data,
        },
      })
      expect(msg).toContain("3 seconds")
    })

    it("keeps working on the PRE-#413 body, so a stale core-java still degrades safely", () => {
      // Incremental betterment: the old parser is retained, not replaced.
      const msg = describeOrderError({
        response: {
          status: 429,
          headers: {},
          data: {
            error: "Too Many Requests",
            message: "Rate limit exceeded. Please try again in 42 seconds.",
          },
        },
      })
      expect(msg).toContain("42 seconds")
    })
  })

  describe("secondsFromMessage", () => {
    it("reads the limiter's sentence", () => {
      expect(secondsFromMessage("Rate limit exceeded. Please try again in 19 seconds.")).toBe(19)
    })

    it("declines unrelated prose, so a stray number is never a countdown", () => {
      expect(secondsFromMessage("Order 12 could not be placed.")).toBeNull()
      expect(secondsFromMessage("Minimum order is 10 pounds.")).toBeNull()
      expect(secondsFromMessage(undefined)).toBeNull()
      expect(secondsFromMessage("")).toBeNull()
    })

    it("rounds up and rejects zero", () => {
      expect(secondsFromMessage("try again in 2.4 seconds")).toBe(3)
      expect(secondsFromMessage("try again in 0 seconds")).toBeNull()
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
