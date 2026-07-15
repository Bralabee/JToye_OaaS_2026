import apiClient from "@/lib/api-client"

/**
 * Typed client for the vendor webhook management + delivery-log API (COMMS-06).
 *
 * Wraps the default authed `apiClient` (Bearer + X-Tenant-Id interceptors) so
 * the dashboard never hand-builds a webhook URL. Endpoints + DTO shapes come
 * from the backend contracts shipped in 22-03 (subscription CRUD + secret-once)
 * and 22-05 (delivery log + tagged Idempotency-Key-safe replay).
 *
 * The plaintext `signingSecret` is returned ONCE (on create + rotate) via
 * {@link WebhookSubscriptionWithSecret}; the read/list DTO never carries it.
 */

const BASE = "/api/v1/webhooks"

// --- Domain types (mirror the backend DTOs) --------------------------------

/** The four event families a subscription can select (22-03 WebhookEventType). */
export type WebhookEventType =
  | "ORDER_STATE_CHANGED"
  | "ORDER_REFUNDED"
  | "ONBOARDING_STATE_CHANGED"
  | "PAYMENT_EVENT"

/** Subscription lifecycle (22-03 WebhookSubscription.Status). */
export type SubscriptionStatus = "ACTIVE" | "PAUSED" | "AUTO_PAUSED" | "REVOKED"

/** Delivery lifecycle (22-05 WebhookDelivery.Status). */
export type DeliveryStatus = "PENDING" | "DELIVERED" | "RETRYING" | "FAILED"

export interface WebhookSubscription {
  id: string
  targetUrl: string
  eventTypes: WebhookEventType[]
  status: SubscriptionStatus
  consecutiveFailures: number
  createdAt: string
  updatedAt: string
}

/** Create/rotate response — the subscription plus its plaintext secret, ONCE. */
export interface WebhookSubscriptionWithSecret {
  subscription: WebhookSubscription
  signingSecret: string
}

export interface WebhookDelivery {
  id: string
  subscriptionId: string
  eventId: string
  eventType: string
  status: DeliveryStatus
  attemptCount: number
  lastHttpStatus: number | null
  lastError: string | null
  replay: boolean
  replayOf: string | null
  nextAttemptAt: string | null
  createdAt: string
  updatedAt: string
}

/** Spring `Page<T>` envelope (subset the UI reads). */
export interface Page<T> {
  content: T[]
  totalPages: number
  totalElements: number
  number: number
  size: number
}

export interface CreateWebhookSubscriptionRequest {
  targetUrl: string
  eventTypes: WebhookEventType[]
}

export interface DeliveryLogQuery {
  status?: DeliveryStatus | null
  eventType?: string | null
  page?: number
  size?: number
}

// --- Event-type presentation metadata (grouped-checkbox families, D-06) -----

/**
 * Display metadata for each event family. The backend exposes exactly one
 * {@link WebhookEventType} per family, so the UI-SPEC "grouped checkboxes" is a
 * one-checkbox-per-family selection over these four values.
 */
export const EVENT_TYPE_META: Record<
  WebhookEventType,
  { family: string; label: string; description: string }
> = {
  ORDER_STATE_CHANGED: {
    family: "Orders",
    label: "Order updates",
    description: "Order lifecycle state changes (confirmed, preparing, ready…).",
  },
  ORDER_REFUNDED: {
    family: "Refunds",
    label: "Refunds",
    description: "An order was refunded.",
  },
  ONBOARDING_STATE_CHANGED: {
    family: "Onboarding",
    label: "Onboarding updates",
    description: "Vendor onboarding / go-live state changes.",
  },
  PAYMENT_EVENT: {
    family: "Payments",
    label: "Payments",
    description: "Payment succeeded / failed events.",
  },
}

/** Stable order for rendering the event-type checkboxes + chips. */
export const EVENT_TYPE_ORDER: WebhookEventType[] = [
  "ORDER_STATE_CHANGED",
  "ORDER_REFUNDED",
  "ONBOARDING_STATE_CHANGED",
  "PAYMENT_EVENT",
]

// --- Idempotency key (reused verbatim from RefundDialog — WR-07 contract) ---

/**
 * Generate a cryptographically-secure idempotency key for a webhook replay.
 *
 * Identical secure-random contract to `RefundDialog.makeIdempotencyKey`: a key
 * collision across same-tenant submits would let one client replay another's
 * request, so `Math.random` is never an acceptable fallback.
 *
 * Order of preference:
 *   1. crypto.randomUUID  — modern HTTPS contexts
 *   2. crypto.getRandomValues — RFC 4122 v4 hand-rolled from 16 secure bytes
 *   3. throw — secure random is mandatory for an Idempotency-Key.
 */
export function makeIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID()
  }
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    const buf = new Uint8Array(16)
    crypto.getRandomValues(buf)
    buf[6] = (buf[6] & 0x0f) | 0x40
    buf[8] = (buf[8] & 0x3f) | 0x80
    const hex = Array.from(buf, (b) => b.toString(16).padStart(2, "0")).join("")
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }
  throw new Error(
    "No secure random source available — webhook replay requires a cryptographic Idempotency-Key. " +
      "Upgrade to a browser that supports crypto.randomUUID or crypto.getRandomValues."
  )
}

// --- Error helper (RFC 7807 detail extraction, RefundDialog §169-179) -------

export function extractErrorDetail(err: unknown, fallback: string): string {
  const e = err as {
    response?: { data?: { detail?: string; message?: string } }
    message?: string
  }
  return (
    e?.response?.data?.detail ??
    e?.response?.data?.message ??
    e?.message ??
    fallback
  )
}

// --- API methods -----------------------------------------------------------

export const webhooksApi = {
  list: async (): Promise<WebhookSubscription[]> => {
    const res = await apiClient.get<WebhookSubscription[]>(BASE)
    return res.data
  },

  get: async (id: string): Promise<WebhookSubscription> => {
    const res = await apiClient.get<WebhookSubscription>(`${BASE}/${id}`)
    return res.data
  },

  create: async (
    body: CreateWebhookSubscriptionRequest
  ): Promise<WebhookSubscriptionWithSecret> => {
    const res = await apiClient.post<WebhookSubscriptionWithSecret>(BASE, body)
    return res.data
  },

  rotateSecret: async (id: string): Promise<WebhookSubscriptionWithSecret> => {
    const res = await apiClient.post<WebhookSubscriptionWithSecret>(
      `${BASE}/${id}/rotate-secret`
    )
    return res.data
  },

  pause: async (id: string): Promise<WebhookSubscription> => {
    const res = await apiClient.post<WebhookSubscription>(`${BASE}/${id}/pause`)
    return res.data
  },

  resume: async (id: string): Promise<WebhookSubscription> => {
    const res = await apiClient.post<WebhookSubscription>(`${BASE}/${id}/resume`)
    return res.data
  },

  revoke: async (id: string): Promise<WebhookSubscription> => {
    const res = await apiClient.post<WebhookSubscription>(`${BASE}/${id}/revoke`)
    return res.data
  },

  listDeliveries: async (
    id: string,
    query: DeliveryLogQuery = {}
  ): Promise<Page<WebhookDelivery>> => {
    const params = new URLSearchParams()
    if (query.status) params.set("status", query.status)
    if (query.eventType) params.set("eventType", query.eventType)
    params.set("page", String(query.page ?? 0))
    params.set("size", String(query.size ?? 20))
    params.set("sort", "createdAt,desc")
    const res = await apiClient.get<Page<WebhookDelivery>>(
      `${BASE}/${id}/deliveries?${params.toString()}`
    )
    return res.data
  },

  replay: async (
    id: string,
    deliveryId: string,
    idempotencyKey: string
  ): Promise<WebhookDelivery> => {
    const res = await apiClient.post<WebhookDelivery>(
      `${BASE}/${id}/deliveries/${deliveryId}/replay`,
      undefined,
      { headers: { "Idempotency-Key": idempotencyKey } }
    )
    return res.data
  },
}

export default webhooksApi
