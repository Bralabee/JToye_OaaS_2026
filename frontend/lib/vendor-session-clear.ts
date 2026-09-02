import type { NextResponse } from "next/server"
import type { ResponseCookie } from "next/dist/compiled/@edge-runtime/cookies"
import { signOut } from "@/auth"

/**
 * End the vendor's Auth.js session SERVER-SIDE and carry the clearing cookies on
 * a response we own — FE-1 (QA council 20260902-134741).
 *
 * Uses the server `signOut` exported from `@/auth` (`next-auth@5`'s
 * `lib/actions.js`: runs `Auth()` with `raw` + `skipCSRFCheck`, writes the
 * resulting cookies through `next/headers` `cookies()`, and with `redirect:false`
 * returns the raw `@auth/core` response). That is the canonical way to end a
 * session from the server: IT owns the cookie names, the `__Secure-` prefix on
 * HTTPS and the chunk cleaning (`authjs.session-token.0/.1`), so none of that is
 * typed out here — hand-expiring `authjs.session-token` is wrong the day the
 * deployment is HTTPS and the prefix changes.
 *
 * Next already merges `cookies().set()` calls into the route's response; copying
 * `res.cookies` onto OUR response as well is belt and braces (plan A18: harmless,
 * not load-bearing) and removes any dependence on that merge surviving a
 * hand-built redirect.
 *
 * NEVER THROWS. Both callers are on the P0 sign-out path: `logout-url` must still
 * hand the client the Keycloak end-session URL, and `logout-complete` must still
 * land the vendor on `/auth/signin`, whatever Auth.js does. A failure here is
 * logged and the response proceeds without the clear — which is today's
 * behaviour, never worse.
 *
 * With NO session cookie `@auth/core` returns early and emits nothing
 * (`lib/actions/signout.js`: `if (!sessionToken) return`), so the count is 0 and
 * that is the correct answer, not a failure.
 *
 * @returns how many clearing cookies were applied — 0 when there was nothing to clear.
 */
export async function clearVendorSessionInto(res: NextResponse, caller: string): Promise<number> {
  try {
    const raw = (await signOut({ redirect: false })) as { cookies?: ClearingCookie[] } | undefined
    const cookies = raw?.cookies ?? []
    for (const c of cookies) res.cookies.set(c.name, c.value, c.options)
    return cookies.length
  } catch (err) {
    console.error(
      `[vendor-auth] ${caller}: server signOut failed — responding without the session clear`,
      err
    )
    return 0
  }
}

/** The shape `@auth/core` puts in `ResponseInternal.cookies`. */
interface ClearingCookie {
  name: string
  value: string
  options?: Partial<ResponseCookie>
}
