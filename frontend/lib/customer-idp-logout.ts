/**
 * Back-channel termination of the customer's Keycloak SSO session (issue #504).
 *
 * WHY THIS EXISTS, given the front-channel redirect is also being fixed.
 *
 * Sign-out had two independent things to achieve — return the shopper to the
 * storefront, and end the session at the IdP — and BOTH were riding on one
 * fragile input: a `post_logout_redirect_uri` inferred from the container's
 * bind address. When that URI was refused, Keycloak errored out BEFORE
 * terminating anything, so the app cookies were gone while the SSO session
 * stayed live. Measured on the compose stack: after clicking Sign out, clicking
 * Sign in again returned the SAME `sub` with no credential prompt. On a shared
 * device the next person to press Sign in is signed in as the last one.
 *
 * This call removes the coupling. `POST /protocol/openid-connect/logout` with
 * `client_id` + `refresh_token` is server-to-server, has no redirect URI to get
 * wrong, and terminates the session outright. Measured against the running app
 * with the front-channel navigation removed ENTIRELY, so nothing else could have
 * done it:
 *
 *   POST /api/customer-auth/logout   -> 200 {"ok":true,"idp":"ok"}
 *   IdP cookies still in the jar     -> all 6, none cleared
 *   subsequent Sign in               -> credential prompt
 *
 * The surviving cookies are the point: they prove the termination happened at
 * the IdP and is not a browser cookie clear flattering the result — the trap
 * issue #504 explicitly warns about.
 *
 * And it is not redundant with the origin fix. Measured with NEITHER
 * APP_PUBLIC_ORIGIN nor NEXTAUTH_URL set — the total config failure this
 * module's sibling guards against — the shopper still lands on Keycloak's page
 * rather than the storefront (cosmetic), but the session is still gone and the
 * next Sign in is still challenged (the part that matters).
 *
 * The front-channel redirect is still performed afterwards, and still matters:
 * it is the OIDC-standard RP-initiated logout and it is what returns the
 * shopper to the storefront. Measured that the two compose safely — Keycloak
 * honours `post_logout_redirect_uri` even when the session it names is already
 * gone (landed on /shop, HTTP 204 back-channel first).
 *
 * SERVER ONLY. The refresh token arrives as an HttpOnly cookie and must never
 * reach the browser.
 */

/**
 * Same split-horizon rule as `customer-token-refresh.ts`: this runs INSIDE the
 * container, so it needs the pod-reachable issuer. The public issuer
 * (localhost:8085) is not routable from here and hangs on a connect timeout —
 * the #467 / port-3100 trap. Falls back to the public issuer only so a
 * non-containerised `next dev` still works.
 */
function logoutEndpoint(): string | null {
  const base =
    process.env.CUSTOMER_KEYCLOAK_ISSUER_INTERNAL ||
    process.env.CUSTOMER_KEYCLOAK_ISSUER
  if (!base) return null
  return `${base}/protocol/openid-connect/logout`
}

function clientId(): string {
  return process.env.CUSTOMER_KEYCLOAK_CLIENT_ID || "storefront-client"
}

export type IdpLogoutOutcome = "ok" | "failed" | "skipped"

/**
 * Best-effort. Every outcome is reported, none is thrown.
 *
 * Failing here must never block the cookie clear that follows it: a shopper who
 * pressed Sign out has to end up signed out of the app whatever the IdP says,
 * or a broker/network blip becomes the shared-device defect all over again.
 * "skipped" means there was nothing to revoke or nowhere to send it, and is not
 * an error — it is the honest answer under k8s, where the customer realm is not
 * yet wired into `k8s/base/frontend-deployment.yaml` at all.
 */
export async function endCustomerIdpSession(
  refreshToken: string | undefined | null
): Promise<IdpLogoutOutcome> {
  if (!refreshToken) return "skipped"
  const url = logoutEndpoint()
  if (!url) return "skipped"

  try {
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        client_id: clientId(),
        refresh_token: refreshToken,
      }),
      cache: "no-store",
    })
    // Keycloak answers 204 on success. A 400 here is the ordinary case of a
    // refresh token that already rotated or expired, not a reason to shout.
    return res.ok ? "ok" : "failed"
  } catch {
    return "failed"
  }
}
