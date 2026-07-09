import { buildSession } from "@/lib/session-callback"

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
