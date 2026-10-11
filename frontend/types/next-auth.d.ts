import "next-auth"
import { DefaultSession } from "next-auth"

declare module "next-auth" {
  interface Session {
    accessToken?: string
    // refreshToken intentionally NOT exposed on the client session (issue #87
    // P1-5, threat T-bl2-05). It stays on the server-side JWT below.
    idToken?: string
    // 37-18 (D-14): the access token's expiry, epoch seconds, so the kitchen board can refresh
    // before it. Not a credential (the token itself carries the same `exp`).
    expiresAt?: number
    user: {
      id: string
      tenantId?: string
    } & DefaultSession["user"]
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    accessToken?: string
    refreshToken?: string
    idToken?: string
    tenantId?: string
    expiresAt?: number
    error?: string
  }
}
