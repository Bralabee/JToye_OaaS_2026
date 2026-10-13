import apiClient from "@/lib/api-client"
import { makeIdempotencyKey } from "@/lib/webhooks-api"
import type { ShopRole } from "@/lib/staff-api"

/**
 * Client for integrator API credentials (37-45 core endpoints; 37-46 Developers page; D-22, D-23,
 * D-24; RWO-035).
 *
 * Every call is authorised SERVER-side: a person signed in to the dashboard (`human-only-action`
 * otherwise) who is a Group admin (`shop-access-denied` otherwise). Nothing here is a trust
 * boundary; it is transport plus typing over `apiClient`.
 *
 * THE SECRET IS NEVER KEPT HERE (T-37-112). `createCredential` and `rotateCredential` return it to
 * the caller, which holds it in component state for the show-once dialog and drops it on close.
 * The server sends those responses with `Cache-Control: no-store`.
 */

export const CREDENTIALS_PATH = "/api/v1/credentials"

/** The six #206 scopes a credential may hold (`docs/security-scopes.md`). */
export type CredentialScope =
  | "catalog:read"
  | "catalog:write"
  | "orders:read"
  | "orders:write"
  | "customers:read"
  | "customers:write"

/**
 * The scopes in the order the create form lists them, each with its plain-English label
 * (UI-SPEC § Copywriting E1). The label is what a vendor reads; the id is what an integrator types.
 */
export const CREDENTIAL_SCOPES: { id: CredentialScope; label: string }[] = [
  { id: "catalog:read", label: "Read your menu" },
  { id: "catalog:write", label: "Change your menu" },
  { id: "orders:read", label: "Read orders" },
  { id: "orders:write", label: "Create and update orders" },
  { id: "customers:read", label: "Read customers" },
  { id: "customers:write", label: "Create and update customers" },
]

/** A scope's label, or the raw id for a scope this build does not know (never a blank chip). */
export function scopeLabel(scope: string): string {
  return CREDENTIAL_SCOPES.find((s) => s.id === scope)?.label ?? scope
}

/**
 * The roles a credential may hold. Never GROUP_ADMIN (Q7): a credential must not be able to manage
 * staff or mint further credentials. The server refuses it too (`credential-role-invalid`).
 */
export type CredentialRole = Extract<ShopRole, "STAFF" | "SHOP_MANAGER">
export const CREDENTIAL_ROLES: CredentialRole[] = ["STAFF", "SHOP_MANAGER"]

/** One credential as the list returns it (`CredentialDto`). Never carries a secret. */
export interface Credential {
  id: string
  name: string
  /** The Keycloak client ID: the client_id of the client-credentials grant. */
  clientId: string
  scopes: string[]
  /** null = every shop of the business. */
  shopId: string | null
  role: ShopRole
  /** ACTIVE or REVOKED; an unknown value is shown raw, never guessed. */
  status: string
  createdBy: string | null
  createdAt: string | null
  rotatedBy?: string | null
  rotatedAt?: string | null
  revokedBy?: string | null
  revokedAt?: string | null
  /** Best-effort and throttled: never more precise than `lastUsedIntervalMinutes`. null = never used. */
  lastUsedAt: string | null
  /** The credential's service-account user: how the Staff page recognises it as an integration. */
  serviceAccountUserId?: string | null
}

export interface CredentialList {
  credentials: Credential[]
  /** How often `lastUsedAt` is written at most, from server config. null when the server omits it. */
  lastUsedIntervalMinutes: number | null
}

/** The create and rotate response (`CredentialSecretDto`): the credential plus, once, its secret. */
export interface CredentialSecret {
  id: string
  name: string
  clientId: string
  /** Present only on the response that created or rotated it; null on an Idempotency-Key replay. */
  clientSecret: string | null
  secretShown: boolean
  tokenEndpoint: string | null
  scopes: string[]
  shopId: string | null
  role: ShopRole
  status: string
}

export interface CreateCredentialInput {
  name: string
  scopes: CredentialScope[]
  role: CredentialRole
  /** null ⇒ every shop. */
  shopId: string | null
}

/** GET /api/v1/credentials: every credential of the business, newest first, revoked ones included. */
export async function listCredentials(): Promise<CredentialList> {
  const res = await apiClient.get<CredentialList>(CREDENTIALS_PATH)
  const body = res.data as Partial<CredentialList> | undefined
  const interval = body?.lastUsedIntervalMinutes
  return {
    credentials: Array.isArray(body?.credentials) ? body.credentials : [],
    lastUsedIntervalMinutes: typeof interval === "number" && interval > 0 ? interval : null,
  }
}

/**
 * POST /api/v1/credentials. The Idempotency-Key is generated per submit: the api-client's own 5xx
 * retry re-sends the same config, so a retried create replays (200, `secretShown` false) instead
 * of issuing a second credential.
 */
export async function createCredential(input: CreateCredentialInput): Promise<CredentialSecret> {
  const res = await apiClient.post<CredentialSecret>(CREDENTIALS_PATH, input, {
    headers: { "Idempotency-Key": makeIdempotencyKey() },
  })
  return res.data
}

/** POST /api/v1/credentials/{id}/rotate: the old secret stops working at once; the new one is returned once. */
export async function rotateCredential(id: string): Promise<CredentialSecret> {
  const res = await apiClient.post<CredentialSecret>(`${CREDENTIALS_PATH}/${id}/rotate`)
  return res.data
}

/** DELETE /api/v1/credentials/{id}: 204, repeatable. The credential stays in the list as REVOKED. */
export async function deleteCredential(id: string): Promise<void> {
  await apiClient.delete(`${CREDENTIALS_PATH}/${id}`)
}

/**
 * The problem `type` suffixes the credential endpoints answer with (37-45 GlobalExceptionHandler).
 * Matched as a suffix of the RFC 7807 `type`, like the staff page does.
 */
export const CREDENTIAL_PROBLEMS = {
  notConfigured: "credentials-not-configured",
  scopeInvalid: "credential-scope-invalid",
  roleInvalid: "credential-role-invalid",
  humanOnly: "human-only-action",
  revoked: "credential-revoked",
  clientMissing: "credential-client-missing",
  serviceUnavailable: "credential-service-unavailable",
  shopAccessDenied: "shop-access-denied",
  staffTargetIsCredential: "staff-target-is-credential",
} as const

export interface CredentialProblem {
  status?: number
  /** The suffix of the problem `type` after its last "/", or undefined. */
  type?: string
  detail?: string
  /** From the Retry-After header, else the body's `retryAfterSeconds`. */
  retryAfterSeconds?: number
}

/** Axios error → the parts of its RFC 7807 body the page renders. Never throws. */
export function credentialProblem(err: unknown): CredentialProblem {
  if (!err || typeof err !== "object" || !("response" in err)) return {}
  const res = (
    err as {
      response?: {
        status?: number
        headers?: Record<string, unknown>
        data?: { type?: unknown; detail?: unknown; retryAfterSeconds?: unknown }
      }
    }
  ).response
  const type = typeof res?.data?.type === "string" ? res.data.type.split("/").pop() : undefined
  const detail =
    typeof res?.data?.detail === "string" && res.data.detail.trim() ? res.data.detail.trim() : undefined
  const header = Number(res?.headers?.["retry-after"])
  const bodyRetry = Number(res?.data?.retryAfterSeconds)
  const retryAfterSeconds =
    Number.isFinite(header) && header > 0
      ? header
      : Number.isFinite(bodyRetry) && bodyRetry > 0
        ? bodyRetry
        : undefined
  return { status: res?.status, type, detail, retryAfterSeconds }
}

/**
 * The sentence a failed create, rotate or delete shows, from what the server said. The server's
 * `detail` is the agent-readable copy for each typed problem (37-45), so it is shown as written;
 * the fallbacks cover a failure with no readable body.
 */
export function credentialActionError(err: unknown, action: "create" | "rotate" | "delete"): string {
  const p = credentialProblem(err)
  if (p.type === CREDENTIAL_PROBLEMS.serviceUnavailable) {
    // The server's detail already says when to retry; the header is used only without one.
    if (p.detail) return p.detail
    const wait = p.retryAfterSeconds ? ` Try again in about ${p.retryAfterSeconds} seconds.` : ""
    return `The account service did not answer, so nothing was changed.${wait}`
  }
  if (p.type === CREDENTIAL_PROBLEMS.shopAccessDenied) {
    return "Only a Group admin can manage API credentials."
  }
  if (p.detail) return p.detail
  const verb = action === "create" ? "create the credential" : action === "rotate" ? "rotate the secret" : "delete the credential"
  return `Couldn't ${verb}. Check your connection and try again.`
}
