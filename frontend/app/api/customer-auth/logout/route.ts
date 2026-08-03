import { NextResponse } from "next/server"
import {
  CUSTOMER_COOKIES,
  cookieBaseOptions,
} from "@/lib/customer-auth-cookies"

/**
 * POST /api/customer-auth/logout
 *
 * Clears the three HttpOnly cookies set by /api/customer-auth/login. This does
 * NOT perform Keycloak logout — that is initiated client-side with the id_token
 * hint (read via /api/customer-auth/session so the token itself never leaves
 * the server).
 */

export async function POST() {
  const res = NextResponse.json({ ok: true })
  for (const name of CUSTOMER_COOKIES) {
    res.cookies.set(name, "", { ...cookieBaseOptions(), maxAge: 0 })
  }
  return res
}
