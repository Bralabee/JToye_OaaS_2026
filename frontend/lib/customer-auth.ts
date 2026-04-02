/**
 * Lightweight customer auth using Keycloak's storefront-client (public client).
 * Uses OAuth Authorization Code flow with PKCE, stores tokens in localStorage.
 * Separate from vendor NextAuth to avoid config conflicts.
 */

const KC_BASE = process.env.NEXT_PUBLIC_KEYCLOAK_URL || "http://localhost:8085/realms/jtoye-dev"
const CLIENT_ID = "storefront-client"
const REDIRECT_URI = typeof window !== "undefined" ? `${window.location.origin}/shop/auth/callback` : ""

interface CustomerTokens {
  accessToken: string
  refreshToken: string
  idToken: string
  expiresAt: number // unix seconds
}

interface CustomerProfile {
  sub: string
  email: string
  name: string
  emailVerified: boolean
}

function getStoredTokens(): CustomerTokens | null {
  if (typeof window === "undefined") return null
  try {
    const raw = localStorage.getItem("jtoye-customer-tokens")
    if (!raw) return null
    return JSON.parse(raw)
  } catch {
    return null
  }
}

function storeTokens(tokens: CustomerTokens) {
  localStorage.setItem("jtoye-customer-tokens", JSON.stringify(tokens))
}

function clearTokens() {
  localStorage.removeItem("jtoye-customer-tokens")
  localStorage.removeItem("jtoye-customer-profile")
}

function getStoredProfile(): CustomerProfile | null {
  if (typeof window === "undefined") return null
  try {
    const raw = localStorage.getItem("jtoye-customer-profile")
    if (!raw) return null
    return JSON.parse(raw)
  } catch {
    return null
  }
}

function storeProfile(profile: CustomerProfile) {
  localStorage.setItem("jtoye-customer-profile", JSON.stringify(profile))
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

  // Store verifier for callback
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

  // Keycloak's registration URL
  window.location.href = `${KC_BASE}/protocol/openid-connect/registrations?${params}`
}

/**
 * Handle OAuth callback — exchange code for tokens.
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

    storeTokens({
      accessToken: data.access_token,
      refreshToken: data.refresh_token,
      idToken: data.id_token,
      expiresAt: Math.floor(Date.now() / 1000) + data.expires_in,
    })

    // Decode ID token to get profile (JWT payload is base64)
    const payload = JSON.parse(atob(data.id_token.split(".")[1]))
    const profile: CustomerProfile = {
      sub: payload.sub,
      email: payload.email || "",
      name: payload.name || payload.preferred_username || "",
      emailVerified: payload.email_verified || false,
    }

    storeProfile(profile)
    sessionStorage.removeItem("jtoye-pkce-verifier")

    return profile
  } catch {
    return null
  }
}

/**
 * Get current customer session.
 */
export function getCustomerSession(): { profile: CustomerProfile; tokens: CustomerTokens } | null {
  const tokens = getStoredTokens()
  const profile = getStoredProfile()
  if (!tokens || !profile) return null

  // Check if token is expired
  if (tokens.expiresAt < Math.floor(Date.now() / 1000)) {
    // Could refresh here, but for MVP just clear and return null
    clearTokens()
    return null
  }

  return { profile, tokens }
}

/**
 * Customer logout.
 */
export function customerLogout() {
  const tokens = getStoredTokens()
  clearTokens()

  // Redirect to Keycloak logout
  if (tokens?.idToken) {
    const params = new URLSearchParams({
      id_token_hint: tokens.idToken,
      post_logout_redirect_uri: `${window.location.origin}/shop`,
    })
    window.location.href = `${KC_BASE}/protocol/openid-connect/logout?${params}`
  } else {
    window.location.href = "/shop"
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
