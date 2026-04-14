import { NextResponse } from "next/server"

/**
 * POST /api/customer-auth/logout
 *
 * Clears the three HttpOnly cookies set by /api/customer-auth/login. This does
 * NOT perform Keycloak logout — that is initiated client-side with the id_token
 * hint (read via /api/customer-auth/session so the token itself never leaves
 * the server).
 */

const ACCESS_COOKIE = "jtoye-customer-access"
const REFRESH_COOKIE = "jtoye-customer-refresh"
const ID_COOKIE = "jtoye-customer-id"

export async function POST() {
  const res = NextResponse.json({ ok: true })
  for (const name of [ACCESS_COOKIE, REFRESH_COOKIE, ID_COOKIE]) {
    res.cookies.set(name, "", {
      httpOnly: true,
      secure: process.env.NODE_ENV === "production",
      sameSite: "lax",
      path: "/",
      maxAge: 0,
    })
  }
  return res
}
