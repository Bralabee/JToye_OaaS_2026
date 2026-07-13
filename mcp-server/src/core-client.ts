/**
 * core-client.ts — thin HTTP forwarder to the core REST API.
 *
 * Security posture (analog: edge-go/internal/core/client.go, with a deliberate
 * divergence): the base URL is a FIXED compose env (never caller-controlled →
 * SSRF guard, T-20-04); the caller's Bearer is forwarded verbatim (core is the
 * sole validator, T-20-88); and — unlike the Go analog which logs the response
 * body — this tier NEVER logs the Bearer or the response body (order/customer
 * PII, T-20-01/T-20-05). Callers only ever receive { ok, status, contentType, body }.
 */

// Internal compose service name — NEVER localhost from inside the container.
const CORE_BASE_URL = process.env.CORE_BASE_URL ?? "http://core-java:9090";

// Trip cleanly BEFORE core's 30s query timeout so a hung upstream surfaces as a
// sanitized "unreachable/timed out" tool error rather than a socket hang.
const CORE_TIMEOUT_MS = 10_000;

export interface CoreResponse {
  ok: boolean;
  status: number;
  contentType: string;
  body: unknown;
}

/**
 * GET `path` on the core API, forwarding the caller's Bearer verbatim.
 * `path` must be built from an allow-listed template by the tool layer — this
 * function never composes the host from caller input.
 */
export async function coreGet(path: string, bearer: string): Promise<CoreResponse> {
  const r = await fetch(`${CORE_BASE_URL}${path}`, {
    method: "GET",
    headers: {
      authorization: `Bearer ${bearer}`,
      accept: "application/json",
    },
    signal: AbortSignal.timeout(CORE_TIMEOUT_MS),
  });

  const contentType = r.headers.get("content-type") ?? "";
  const body = contentType.includes("json")
    ? await r.json().catch(() => null)
    : await r.text();

  return { ok: r.ok, status: r.status, contentType, body };
}
