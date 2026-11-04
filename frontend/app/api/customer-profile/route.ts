import { NextRequest, NextResponse } from "next/server"
import {
  ACCESS_COOKIE,
  ID_COOKIE,
  REFRESH_COOKIE,
  REFRESH_MAX_AGE,
  cookieBaseOptions,
} from "@/lib/customer-auth-cookies"
import { coreBaseUrl } from "@/lib/customer-orders-server"
import { forwardedForHeader } from "@/lib/forwarded-for"
import { refreshCustomerTokens, type RefreshedTokens } from "@/lib/customer-token-refresh"

/**
 * PUT /api/customer-profile   body: { displayName }
 *
 * SW-JRD-07 / SW-SAM-11 (owner ruling 2026-11-04, persona close-out): a signed-in customer changes the
 * name on their account. The name lives only in the sign-in provider (Keycloak, realm jtoye-customers);
 * there is no profile table and none is added. Core changes it through the existing Keycloak admin seam
 * (the one DSAR account deletion already uses), and decides WHICH account from the verified access token
 * alone. So this route forwards the name and nothing else: no email and no id from the body can choose
 * whose name changes.
 *
 * The access token is in an HttpOnly cookie browser JS cannot read, hence this server seam. If it has
 * expired but the refresh cookie is live, it is renewed first (same as /api/customer-auth/session). After
 * a successful change the three session cookies are re-issued from a fresh refresh, because the ID token
 * the rest of the site reads the name from would otherwise keep the old name until the next renewal.
 */
function sessionCookies(res: NextResponse, t: { accessToken: string; refreshToken: string; idToken: string; expiresAt: number }) {
  const base = cookieBaseOptions()
  res.cookies.set(ACCESS_COOKIE, t.accessToken, {
    ...base,
    maxAge: Math.max(0, t.expiresAt - Math.floor(Date.now() / 1000)),
  })
  res.cookies.set(REFRESH_COOKIE, t.refreshToken, { ...base, maxAge: REFRESH_MAX_AGE })
  res.cookies.set(ID_COOKIE, t.idToken, { ...base, maxAge: REFRESH_MAX_AGE })
}

export async function PUT(req: NextRequest) {
  let displayName: unknown
  try {
    displayName = ((await req.json()) as { displayName?: unknown }).displayName
  } catch {
    displayName = undefined
  }
  if (typeof displayName !== "string") {
    return NextResponse.json({ error: "invalid_body" }, { status: 400 })
  }

  let access = req.cookies.get(ACCESS_COOKIE)?.value
  let refresh = req.cookies.get(REFRESH_COOKIE)?.value
  let renewedFirst: RefreshedTokens | null = null
  if (!access && refresh) {
    // refreshCustomerTokens answers renewed | refused | transient (#843). Either failure is "no
    // usable session" for this one write, so a 401; this route never clears cookies (the session
    // probe owns that), so a transient IdP blip cannot sign anyone out from here.
    const first = await refreshCustomerTokens(refresh)
    if (first.kind !== "renewed") return NextResponse.json({ error: "not_authenticated" }, { status: 401 })
    renewedFirst = first.tokens
    access = renewedFirst.accessToken
    refresh = renewedFirst.refreshToken
  }
  if (!access) return NextResponse.json({ error: "not_authenticated" }, { status: 401 })

  // Whatever happens next, a first renewal that succeeded has already consumed the old refresh token
  // (the realm revokes it on use), so its tokens MUST reach the browser on every outcome below.
  const finish = (res: NextResponse, tokens: RefreshedTokens | null) => {
    if (tokens) sessionCookies(res, tokens)
    return res
  }

  try {
    const upstream = await fetch(`${coreBaseUrl()}/public/customer/profile`, {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
        "X-Customer-Token": access,
        ...forwardedForHeader(req.headers),
      },
      body: JSON.stringify({ displayName }),
      cache: "no-store",
    })
    const data = await upstream.json().catch(() => ({}))
    const res = NextResponse.json(data, { status: upstream.status, headers: { "Cache-Control": "no-store" } })
    if (upstream.status >= 200 && upstream.status < 300) {
      // Bring the new name into the ID token. Best effort: the change is already made, so a failed
      // renewal here only means the old name shows until the next ordinary renewal (or, when the first
      // renewal ran, that its tokens are kept).
      const again = refresh ? await refreshCustomerTokens(refresh) : null
      return finish(res, again?.kind === "renewed" ? again.tokens : renewedFirst)
    }
    return finish(res, renewedFirst)
  } catch {
    return finish(NextResponse.json({ error: "upstream_unavailable" }, { status: 502 }), renewedFirst)
  }
}
