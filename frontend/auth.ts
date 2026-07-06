import NextAuth from "next-auth"
import Keycloak from "next-auth/providers/keycloak"

async function refreshAccessToken(token: {
  refreshToken?: string
  [key: string]: unknown
}) {
  // Refresh runs server-side inside the frontend container, so it must use the
  // internal Docker network URL (keycloak:8080) — the public KEYCLOAK_ISSUER
  // (localhost:8085) is not reachable from here and hangs ~10s on a connect
  // timeout before failing, leaving the session with an expired token (401).
  // Mirrors the provider token/userinfo endpoints below (kcServerBase).
  const kcTokenBase = process.env.KEYCLOAK_ISSUER_INTERNAL || process.env.KEYCLOAK_ISSUER
  const tokenUrl = `${kcTokenBase}/protocol/openid-connect/token`

  const response = await fetch(tokenUrl, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "refresh_token",
      client_id: process.env.KEYCLOAK_CLIENT_ID!,
      client_secret: process.env.KEYCLOAK_CLIENT_SECRET || "",
      refresh_token: token.refreshToken as string,
    }),
  })

  const refreshed = await response.json()

  if (!response.ok) {
    throw new Error(
      `Token refresh failed: ${refreshed.error || response.statusText}`
    )
  }

  return {
    ...token,
    accessToken: refreshed.access_token,
    refreshToken: refreshed.refresh_token ?? token.refreshToken,
    idToken: refreshed.id_token ?? token.idToken,
    expiresAt: Math.floor(Date.now() / 1000) + refreshed.expires_in,
  }
}

// Server-side Keycloak base URL (internal Docker network or same as public)
const kcServerBase = process.env.KEYCLOAK_ISSUER_INTERNAL || process.env.KEYCLOAK_ISSUER
// Public-facing Keycloak URL (what the browser and token issuer use)
const kcPublicBase = process.env.NEXT_PUBLIC_KEYCLOAK_URL || process.env.KEYCLOAK_ISSUER

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Keycloak({
      clientId: process.env.KEYCLOAK_CLIENT_ID!,
      clientSecret: process.env.KEYCLOAK_CLIENT_SECRET || "",
      issuer: process.env.KEYCLOAK_ISSUER,
      authorization: {
        url: `${kcPublicBase}/protocol/openid-connect/auth`,
      },
      token: `${kcServerBase}/protocol/openid-connect/token`,
      userinfo: `${kcServerBase}/protocol/openid-connect/userinfo`,
      profile(profile) {
        return {
          id: profile.sub,
          name: profile.name ?? profile.preferred_username,
          email: profile.email,
          image: profile.picture,
        }
      },
    }),
  ],
  callbacks: {
    async jwt({ token, account }) {
      // Initial sign-in: store tokens and expiry
      if (account) {
        token.accessToken = account.access_token
        token.refreshToken = account.refresh_token
        token.idToken = account.id_token
        token.expiresAt = account.expires_at
        return token
      }

      // Token still valid: return as-is
      if (token.expiresAt && Date.now() < (token.expiresAt as number) * 1000) {
        return token
      }

      // Token expired: attempt refresh
      try {
        return await refreshAccessToken(token)
      } catch {
        // Refresh failed (typically the Keycloak SSO session / refresh token
        // itself expired — max lifespan 2h, idle 30m). Drop the stale access
        // token so the session reads as unauthenticated and the api-client 401
        // handler bounces to /auth/signin, instead of the app retrying forever
        // with a dead token and trapping the user on a wall of 401s.
        return { ...token, accessToken: undefined, error: "RefreshTokenError" }
      }
    },
    async session({ session, token }) {
      session.accessToken = token.accessToken as string
      session.refreshToken = token.refreshToken as string
      session.idToken = token.idToken as string
      return session
    },
  },
  pages: {
    signIn: "/auth/signin",
  },
  basePath: "/api/auth",
  trustHost: true,
})
