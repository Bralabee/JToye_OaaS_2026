import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

/**
 * errors.ts — map a non-2xx core response into a sanitized MCP tool error.
 *
 * Core emits application/problem+json via GlobalExceptionHandler:
 *   { type, title, status, detail, [errors], [code], [retryAfterSeconds], ...typed members }
 * (status taxonomy 400/401/403/404/409/422/429/500/502).
 *
 * The first text line stays `core <status> <title>: <detail>`. #879 / UXT-123: the problem's `type` and its
 * typed extension members (per-field `errors`, `code`, `retryAfterSeconds`, ...) are no longer dropped; they
 * follow as one JSON line a model can read, and as `structuredContent` for a client that wants the object.
 *
 * This mapper mirrors core's own posture (log the stack server-side, return a generic message): it NEVER
 * forwards err.stack, undici internals, the raw upstream body, or the token to the model (T-20-05). Extension
 * members are filtered by key (anything that names a stack, trace, exception, token, secret, password or
 * authorization is dropped) and the whole detail is size-bounded, so a hostile or buggy upstream body cannot
 * smuggle a stack or a credential through, nor flood the model's context. It always sets isError.
 */

// Safe, generic messages for bare (non-problem+json) status codes.
const GENERIC_BY_STATUS: Record<number, string> = {
  401: "Unauthorized: token missing, expired, or wrong audience",
  403: "Forbidden: token lacks the required scope",
  404: "Not found",
  409: "Conflict",
  422: "Unprocessable request",
};

/** RFC 7807 members that already appear in the summary line or are noise (instance). */
const SUMMARY_MEMBERS = new Set(["title", "detail", "status", "instance", "type"]);
/** A member whose NAME says it may carry a stack or a credential is never forwarded. */
const FORBIDDEN_KEY = /stack|trace|exception|token|secret|password|authori[sz]ation|cookie/i;
/** Bounds on what reaches the model: one string, and the whole extension object once serialized. */
const MAX_STRING = 300;
const MAX_ARRAY = 25;
const MAX_DETAIL_JSON = 1500;

interface ProblemDetail {
  type?: unknown;
  title?: unknown;
  detail?: unknown;
  status?: unknown;
  [member: string]: unknown;
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

/** A value reduced to JSON-safe, bounded form; undefined when it is not forwardable. */
function bounded(value: unknown, depth = 0): unknown {
  if (value === null || typeof value === "number" || typeof value === "boolean") return value;
  if (typeof value === "string") {
    return value.length > MAX_STRING ? `${value.slice(0, MAX_STRING)}…` : value;
  }
  if (depth >= 3) return undefined;
  if (Array.isArray(value)) {
    return value.slice(0, MAX_ARRAY).map((v) => bounded(v, depth + 1)).filter((v) => v !== undefined);
  }
  if (typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>).slice(0, MAX_ARRAY)) {
      if (FORBIDDEN_KEY.test(k)) continue;
      const kept = bounded(v, depth + 1);
      if (kept !== undefined) out[k] = kept;
    }
    return out;
  }
  return undefined;
}

/** The typed extension members of a problem document (everything the summary line does not already carry). */
function extensionMembers(problem: ProblemDetail): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (typeof problem.type === "string" && problem.type.length > 0) {
    out.type = bounded(problem.type);
  }
  for (const [key, value] of Object.entries(problem)) {
    if (SUMMARY_MEMBERS.has(key) || FORBIDDEN_KEY.test(key)) continue;
    const kept = bounded(value);
    if (kept !== undefined) out[key] = kept;
  }
  // Size-bound the whole thing: drop members, largest first, until it fits.
  let json = JSON.stringify(out);
  const keys = Object.keys(out).sort((a, b) => JSON.stringify(out[b]).length - JSON.stringify(out[a]).length);
  while (json.length > MAX_DETAIL_JSON && keys.length > 0) {
    const dropped = keys.shift() as string;
    if (dropped === "type") continue; // never drop the type; it is the smallest and the most useful
    delete out[dropped];
    json = JSON.stringify(out);
  }
  return out;
}

export function toToolError(res: {
  status: number;
  contentType: string;
  body: unknown;
}): CallToolResult {
  const contentType = res.contentType || "";

  if (contentType.includes("problem+json") && res.body && typeof res.body === "object") {
    const p = res.body as ProblemDetail;
    const summary = `core ${res.status} ${text(p.title)}: ${text(p.detail)}`.trim();
    const extra = extensionMembers(p);
    const hasExtra = Object.keys(extra).length > 0;
    return {
      content: [{ type: "text", text: hasExtra ? `${summary}\n${JSON.stringify(extra)}` : summary }],
      ...(hasExtra
        ? { structuredContent: { status: res.status, title: text(p.title), detail: text(p.detail), ...extra } }
        : {}),
      isError: true,
    };
  }

  const msg =
    GENERIC_BY_STATUS[res.status] ??
    (res.status >= 500 ? "Upstream core error" : `core ${res.status}`);
  return { content: [{ type: "text", text: msg }], isError: true };
}
