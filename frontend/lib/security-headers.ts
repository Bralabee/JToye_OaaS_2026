// Content-Security-Policy construction (SEC-02 / ASVS 14.4.x, issue #89 P1-7).
//
// The CSP is built per-request in middleware.ts so it can carry a fresh nonce
// and drop `script-src 'unsafe-inline'` (the previous static next.config header
// could not — a nonce must vary per response). Modern browsers trust scripts via
// the nonce + `'strict-dynamic'` propagation (Next's framework bootstrap is
// nonced, and Stripe.js is injected by that already-trusted app code); the
// explicit Stripe hosts remain for non-strict-dynamic fallback and the unit test.
//
// style-src keeps 'unsafe-inline' deliberately: the AC (#89) only forbids
// 'unsafe-inline' in script-src, and nonce-ing Tailwind/Next inline styles is
// out of scope.

export interface CspOptions {
  /** Per-request nonce (base64), injected into script-src as 'nonce-<value>'. */
  nonce: string
  /** Development mode enables 'unsafe-eval' (React refresh / Next dev overlay). */
  isDev: boolean
  /** Public Keycloak realm URL for STAFF/vendor — allowed in form-action + connect-src. */
  keycloakOrigin?: string
  /**
   * Public Keycloak realm URL for CUSTOMERS (jtoye-customers).
   *
   * Separate from `keycloakOrigin` because these are realm URLs with a PATH, not
   * bare origins, and #382 split the two identity pools. Omitting it does not
   * fail loudly — it CSP-blocks the customer token exchange at
   * `…/jtoye-customers/protocol/openid-connect/token`, so registration creates
   * the Keycloak user and then the sign-in silently dies with "Authentication
   * failed. Please try again." Covered by __tests__/csp-headers.test.ts.
   */
  customerKeycloakOrigin?: string
  /** Public API origin — allowed in connect-src, plus its ws(s):// form. */
  apiOrigin?: string
  /**
   * Emit `upgrade-insecure-requests`. Off by default: the local Docker stack
   * runs NODE_ENV=production yet serves over http with MinIO images at
   * http://localhost:9000, so an unconditional upgrade would break images.
   * Real HTTPS deployments set CSP_UPGRADE_INSECURE_REQUESTS=true.
   */
  upgradeInsecure?: boolean
}

/**
 * Build the Content-Security-Policy header value.
 *
 * @returns the `; `-joined directive string (no header name).
 */
export function buildCsp({
  nonce,
  isDev,
  keycloakOrigin = "",
  customerKeycloakOrigin = "",
  apiOrigin = "",
  upgradeInsecure = false,
}: CspOptions): string {
  const wsOrigin = apiOrigin.replace(/^http/, "ws")

  // Both realms, de-duplicated and blank-safe. They are usually the same host on
  // different realm paths, so emitting both is correct; emitting an empty string
  // or a duplicate is not.
  const keycloakSources = [keycloakOrigin, customerKeycloakOrigin].filter(
    (src, i, all) => src !== "" && all.indexOf(src) === i,
  )

  const directives = [
    "default-src 'self'",
    // No 'unsafe-inline' (issue #89): nonce + strict-dynamic govern script trust.
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${isDev ? " 'unsafe-eval'" : ""} https://js.stripe.com https://*.js.stripe.com`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob: https://*.stripe.com https: http://localhost:9000",
    "font-src 'self' data:",
    `connect-src 'self' https://api.stripe.com https://*.stripe.com ${apiOrigin} ${wsOrigin} ${keycloakSources.join(" ")}`
      .replace(/\s+/g, " ")
      .trim(),
    "frame-src https://js.stripe.com https://*.js.stripe.com https://hooks.stripe.com",
    "frame-ancestors 'none'",
    `form-action 'self' ${keycloakSources.join(" ")}`.replace(/\s+/g, " ").trim(),
    "base-uri 'self'",
    "object-src 'none'",
    ...(upgradeInsecure && !isDev ? ["upgrade-insecure-requests"] : []),
  ]

  return directives.join("; ")
}
