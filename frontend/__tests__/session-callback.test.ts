import {
  ACCESS_TOKEN_REFRESH_SKEW_S,
  accessTokenNeedsRefresh,
  buildSession,
} from "@/lib/session-callback"

/**
 * Guards the vendor NextAuth session against leaking the refresh token
 * (issue #87 P1-5, threat T-bl2-05). The refresh token must remain on the
 * server-side JWT only.
 */
describe("buildSession", () => {
  const token = {
    accessToken: "access-123",
    refreshToken: "refresh-xyz",
    idToken: "id-456",
  }

  it("copies accessToken and idToken onto the session", () => {
    const result = buildSession<Record<string, unknown>>({}, token)
    expect(result.accessToken).toBe("access-123")
    expect(result.idToken).toBe("id-456")
  })

  it("does NOT leak the refresh token into the client session", () => {
    const result = buildSession<Record<string, unknown>>({}, token)
    expect(result).not.toHaveProperty("refreshToken")
  })

  it("strips a pre-existing refreshToken off the incoming session", () => {
    const result = buildSession<Record<string, unknown>>(
      { refreshToken: "stale" },
      { accessToken: "a", idToken: "i" }
    )
    expect(result).not.toHaveProperty("refreshToken")
    expect(result.accessToken).toBe("a")
  })
})

/**
 * 37-18 (D-14): the kitchen board refreshes its token a minute BEFORE it expires. That is only
 * possible if the session read refreshes inside that minute (it used to refresh only once the
 * token had expired) and if the browser can see when the token expires.
 */
describe("accessTokenNeedsRefresh (37-18)", () => {
  const exp = 1_900_000_000

  it("refreshes inside the last minute of the access token, not only after it expires", () => {
    expect(accessTokenNeedsRefresh(exp, (exp - 30) * 1000)).toBe(true)
    expect(accessTokenNeedsRefresh(exp, (exp - ACCESS_TOKEN_REFRESH_SKEW_S) * 1000)).toBe(true)
  })

  it("keeps a token that has more than a minute left", () => {
    expect(accessTokenNeedsRefresh(exp, (exp - ACCESS_TOKEN_REFRESH_SKEW_S - 1) * 1000)).toBe(false)
  })

  it("refreshes an expired token, and one whose expiry is unknown", () => {
    expect(accessTokenNeedsRefresh(exp, (exp + 1) * 1000)).toBe(true)
    expect(accessTokenNeedsRefresh(undefined, exp * 1000)).toBe(true)
  })

  it("copies the access token's expiry onto the client session, so the board can schedule its refresh", () => {
    const result = buildSession<Record<string, unknown>>({}, { accessToken: "a", expiresAt: exp })
    expect(result.expiresAt).toBe(exp)
  })
})
