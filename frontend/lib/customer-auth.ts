/**
 * Customer auth using Keycloak's storefront-client (public PKCE client).
 *
 * Security model:
 *   - OAuth tokens (access, refresh, id) are stored in HttpOnly cookies via
 *     the /api/customer-auth/* Next.js API routes. They are NEVER readable
 *     from JavaScript. This protects against XSS exfiltration.
 *   - The browser keeps only a non-sensitive localStorage marker
 *     (`jtoye-customer-logged-in`) + the expiry timestamp so that UI code
 *     can synchronously answer "am I logged in" without a round-trip.
 *   - Any code that needs customer profile data must await
 *     `getCustomerSession()`, which reads the cookie-backed session via
 *     /api/customer-auth/session.
 *
 * Separate from vendor NextAuth to avoid config conflicts.
 */

// Phase 18: customer identity lives in its own realm (jtoye-customers), decoupled
// from the B2B staff/vendor realm (jtoye-dev). Use the dedicated customer base URL,
// falling back ONLY to the jtoye-customers dev default — never to
// NEXT_PUBLIC_KEYCLOAK_URL (the staff/vendor realm), which would fail-open customer
// logins into the wrong identity pool and defeat the split this phase establishes.
// Admin NextAuth (frontend/auth.ts, client core-api) stays on jtoye-dev — untouched.
const KC_BASE =
  process.env.NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL ||
  "http://localhost:8085/realms/jtoye-customers" // never fall back to jtoye-dev (staff realm)
const CLIENT_ID = "storefront-client"
const REDIRECT_URI = typeof window !== "undefined" ? `${window.location.origin}/shop/auth/callback` : ""

const MARKER_KEY = "jtoye-customer-logged-in"
const EXPIRES_KEY = "jtoye-customer-expires-at"

export interface CustomerProfile {
  sub: string
  email: string
  name: string
  emailVerified: boolean
}

export interface CustomerSession {
  profile: CustomerProfile
  expiresAt: number
}

interface IdTokenClaims {
  sub?: string
  email?: string
  name?: string
  preferred_username?: string
  email_verified?: boolean
  exp?: number
}

/**
 * Decode a JWT payload segment in the browser. JWT segments are **base64url**
 * (alphabet includes `-` and `_`), which `atob` (standard base64) rejects with
 * `InvalidCharacterError` — so translate to standard base64, re-pad, then UTF-8
 * decode so multi-byte characters (e.g. accented customer names) survive.
 * Returns null on any malformed input rather than throwing.
 */
function decodeJwtPayload(token: string): IdTokenClaims | null {
  try {
    const seg = token.split(".")[1]
    if (!seg) return null
    const b64 = seg.replace(/-/g, "+").replace(/_/g, "/")
    const pad = b64.length % 4 === 0 ? "" : "=".repeat(4 - (b64.length % 4))
    const bytes = atob(b64 + pad)
    const json = decodeURIComponent(
      bytes
        .split("")
        .map((c) => "%" + c.charCodeAt(0).toString(16).padStart(2, "0"))
        .join("")
    )
    return JSON.parse(json) as IdTokenClaims
  } catch {
    return null
  }
}

function setMarker(expiresAt: number) {
  if (typeof window === "undefined") return
  try {
    localStorage.setItem(MARKER_KEY, "true")
    localStorage.setItem(EXPIRES_KEY, String(expiresAt))
  } catch {
    /* storage may be unavailable (private mode) — ignore */
  }
}

function clearMarker() {
  if (typeof window === "undefined") return
  try {
    localStorage.removeItem(MARKER_KEY)
    localStorage.removeItem(EXPIRES_KEY)
    // Legacy cleanup — remove any tokens left from pre-cookie versions
    localStorage.removeItem("jtoye-customer-tokens")
    localStorage.removeItem("jtoye-customer-profile")
  } catch {
    /* ignore */
  }
}

/**
 * Synchronous "am I probably logged in" check based on the localStorage
 * marker. UI-only — cannot be trusted for security decisions.
 */
export function isLoggedIn(): boolean {
  if (typeof window === "undefined") return false
  try {
    if (localStorage.getItem(MARKER_KEY) !== "true") return false
    const exp = Number(localStorage.getItem(EXPIRES_KEY) || "0")
    if (!exp) return false
    return exp > Math.floor(Date.now() / 1000)
  } catch {
    return false
  }
}

// Generate PKCE code verifier and challenge
function generateCodeVerifier(): string {
  const array = new Uint8Array(32)
  crypto.getRandomValues(array)
  return btoa(String.fromCharCode(...array))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "")
}

async function generateCodeChallenge(verifier: string): Promise<string> {
  const encoder = new TextEncoder()
  const data = encoder.encode(verifier)
  const digest = await crypto.subtle.digest("SHA-256", data)
  return btoa(String.fromCharCode(...new Uint8Array(digest)))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "")
}

/**
 * Initiate customer login — redirects to Keycloak login page.
 */
export async function customerLogin(returnTo?: string) {
  const verifier = generateCodeVerifier()
  const challenge = await generateCodeChallenge(verifier)

  sessionStorage.setItem("jtoye-pkce-verifier", verifier)
  if (returnTo) sessionStorage.setItem("jtoye-auth-return", returnTo)

  const params = new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    response_type: "code",
    scope: "openid email profile",
    code_challenge: challenge,
    code_challenge_method: "S256",
  })

  window.location.href = `${KC_BASE}/protocol/openid-connect/auth?${params}`
}

/**
 * Initiate customer registration — redirects to Keycloak registration page.
 */
export async function customerRegister(returnTo?: string) {
  const verifier = generateCodeVerifier()
  const challenge = await generateCodeChallenge(verifier)

  sessionStorage.setItem("jtoye-pkce-verifier", verifier)
  if (returnTo) sessionStorage.setItem("jtoye-auth-return", returnTo)

  const params = new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    response_type: "code",
    scope: "openid email profile",
    code_challenge: challenge,
    code_challenge_method: "S256",
  })

  window.location.href = `${KC_BASE}/protocol/openid-connect/registrations?${params}`
}

/**
 * Handle OAuth callback — exchange code for tokens, then hand them off to the
 * server to be stored as HttpOnly cookies. Returns the profile parsed from
 * the id token (profile data is not sensitive; only the raw tokens are).
 */
export async function handleCallback(code: string): Promise<CustomerProfile | null> {
  const verifier = sessionStorage.getItem("jtoye-pkce-verifier")
  if (!verifier) return null

  try {
    const response = await fetch(`${KC_BASE}/protocol/openid-connect/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        client_id: CLIENT_ID,
        code,
        redirect_uri: REDIRECT_URI,
        code_verifier: verifier,
      }),
    })

    if (!response.ok) return null

    const data = await response.json()
    const expiresAt = Math.floor(Date.now() / 1000) + data.expires_in

    // Hand tokens to the server — they become HttpOnly cookies and then the
    // access/refresh/id strings never touch JS again.
    const loginRes = await fetch("/api/customer-auth/login", {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        tokens: {
          accessToken: data.access_token,
          refreshToken: data.refresh_token,
          idToken: data.id_token,
          expiresAt,
        },
      }),
    })
    if (!loginRes.ok) return null

    // Decode ID token to get profile for returning to the caller (non-sensitive).
    // Use a base64url-safe, UTF-8-aware decode so accented names don't throw.
    const payload = decodeJwtPayload(data.id_token)
    if (!payload) return null
    const profile: CustomerProfile = {
      sub: payload.sub ?? "",
      email: payload.email || "",
      name: payload.name || payload.preferred_username || "",
      emailVerified: payload.email_verified || false,
    }

    setMarker(expiresAt)
    sessionStorage.removeItem("jtoye-pkce-verifier")

    return profile
  } catch {
    return null
  }
}

/**
 * Fetch the current customer session from the server (cookie-backed).
 * Returns null when the customer is not logged in or the session expired.
 *
 * NOTE: This is async. Components that need the profile should `await` it
 * in a `useEffect`. For "should I render the nav as logged in" synchronous
 * checks, use `isLoggedIn()`.
 */
export async function getCustomerSession(): Promise<CustomerSession | null> {
  try {
    const res = await fetch("/api/customer-auth/session", {
      credentials: "include",
      cache: "no-store",
    })
    if (!res.ok) {
      clearMarker()
      return null
    }
    const data = (await res.json()) as {
      authenticated: boolean
      expiresAt: number | null
      profile: CustomerProfile
    }
    if (!data.authenticated) {
      clearMarker()
      return null
    }
    // Refresh the marker so subsequent synchronous checks agree with server
    if (data.expiresAt) setMarker(data.expiresAt)
    return { profile: data.profile, expiresAt: data.expiresAt || 0 }
  } catch {
    return null
  }
}

/**
 * Customer logout — clears HttpOnly cookies on the server, clears the
 * localStorage marker, then follows the Keycloak end-session URL built by
 * the server (so the raw id token never reaches the browser).
 */
export async function customerLogout() {
  try {
    // Get the Keycloak logout URL while the cookie still exists
    let logoutUrl = "/shop"
    try {
      const urlRes = await fetch("/api/customer-auth/logout-url?redirect=/shop", {
        credentials: "include",
        cache: "no-store",
      })
      if (urlRes.ok) {
        const data = (await urlRes.json()) as { url?: string }
        if (data.url) logoutUrl = data.url
      }
    } catch {
      /* ignore — fall back to /shop */
    }

    await fetch("/api/customer-auth/logout", {
      method: "POST",
      credentials: "include",
    })

    clearMarker()
    if (typeof window !== "undefined") {
      window.location.href = logoutUrl
    }
  } catch {
    clearMarker()
    if (typeof window !== "undefined") {
      window.location.href = "/shop"
    }
  }
}

/**
 * Get the return URL after auth callback.
 */
export function getAuthReturnUrl(): string {
  const returnTo = sessionStorage.getItem("jtoye-auth-return")
  sessionStorage.removeItem("jtoye-auth-return")
  return returnTo || "/shop"
}

/**
 * Fetch helper for any endpoint that needs customer authentication: forwards
 * the request through a dedicated proxy (not yet needed by any caller — the
 * current codebase only uses public endpoints). Kept here so new callers have
 * an obvious, safe entry point rather than reaching for localStorage.
 */
export async function fetchWithCustomerAuth(
  input: RequestInfo | URL,
  init: RequestInit = {}
): Promise<Response> {
  return fetch(input, {
    ...init,
    credentials: "include",
  })
}
