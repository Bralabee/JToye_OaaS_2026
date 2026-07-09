import "next-auth"
import { DefaultSession } from "next-auth"

declare module "next-auth" {
  interface Session {
    accessToken?: string
    // refreshToken intentionally NOT exposed on the client session (issue #87
    // P1-5, threat T-bl2-05). It stays on the server-side JWT below.
    idToken?: string
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
