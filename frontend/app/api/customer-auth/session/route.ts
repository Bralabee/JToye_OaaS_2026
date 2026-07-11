import { NextRequest, NextResponse } from "next/server"

/**
 * GET /api/customer-auth/session
 *
 * Returns the customer session state based on HttpOnly cookies. Never returns
 * the tokens themselves — only profile claims decoded from the ID token and
 * the access-token expiry.
 *
 * The no-session / expired case returns HTTP 200 with `{ authenticated: false }`
 * (NOT 401). This is the expected steady state for every anonymous visitor, and
 * a public component (storefront nav) probes this endpoint on mount for each
 * public page load — a 401 there makes the browser log a failed request to the
 * console on every anonymous page view (backlog #13). A 200 body carries no
 * session data, so consumers read the `authenticated` flag, not the status. The
 * authoritative auth gates remain server-side (dashboard auth() + core-java RLS);
 * this probe is a client-side convenience read only.
 */

const ACCESS_COOKIE = "jtoye-customer-access"
const ID_COOKIE = "jtoye-customer-id"

interface IdTokenClaims {
  sub?: string
  email?: string
  name?: string
  preferred_username?: string
  email_verified?: boolean
  exp?: number
}

function decodeJwtPayload(jwt: string): IdTokenClaims | null {
  try {
    const parts = jwt.split(".")
    if (parts.length !== 3) return null
    // base64url -> base64
    const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/")
    const pad = b64.length % 4 === 0 ? b64 : b64 + "=".repeat(4 - (b64.length % 4))
    const json = Buffer.from(pad, "base64").toString("utf8")
    return JSON.parse(json) as IdTokenClaims
  } catch {
    return null
  }
}

export async function GET(_req: NextRequest) {
  const access = _req.cookies.get(ACCESS_COOKIE)?.value
  const id = _req.cookies.get(ID_COOKIE)?.value

  if (!access || !id) {
    return NextResponse.json({ authenticated: false })
  }

  const claims = decodeJwtPayload(id)
  if (!claims) {
    return NextResponse.json({ authenticated: false })
  }

  const nowSec = Math.floor(Date.now() / 1000)
  if (claims.exp && claims.exp < nowSec) {
    return NextResponse.json({ authenticated: false })
  }

  return NextResponse.json({
    authenticated: true,
    expiresAt: claims.exp ?? null,
    profile: {
      sub: claims.sub ?? "",
      email: claims.email ?? "",
      name: claims.name ?? claims.preferred_username ?? "",
      emailVerified: claims.email_verified ?? false,
    },
  })
}
