import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

/**
 * errors.ts — map a non-2xx core response into a sanitized MCP tool error.
 *
 * Core emits application/problem+json via GlobalExceptionHandler:
 *   { type, title, status, detail, [errors], [stripeCode] }
 * (status taxonomy 400/401/403/404/409/422/500/502).
 *
 * This mapper mirrors core's own posture (log the stack server-side, return a
 * generic message): it NEVER forwards err.stack, undici internals, the raw
 * upstream body, or the token to the model (T-20-05). It always sets isError.
 */

// Safe, generic messages for bare (non-problem+json) status codes.
const GENERIC_BY_STATUS: Record<number, string> = {
  401: "Unauthorized: token missing, expired, or wrong audience",
  403: "Forbidden: token lacks the required scope",
  404: "Not found",
  409: "Conflict",
  422: "Unprocessable request",
};

interface ProblemDetail {
  title?: string;
  detail?: string;
  status?: number;
}

export function toToolError(res: {
  status: number;
  contentType: string;
  body: unknown;
}): CallToolResult {
  const contentType = res.contentType || "";
  let msg: string;

  if (contentType.includes("problem+json") && res.body && typeof res.body === "object") {
    const p = res.body as ProblemDetail;
    msg = `core ${res.status} ${p.title ?? ""}: ${p.detail ?? ""}`.trim();
  } else {
    msg =
      GENERIC_BY_STATUS[res.status] ??
      (res.status >= 500 ? "Upstream core error" : `core ${res.status}`);
  }

  return { content: [{ type: "text", text: msg }], isError: true };
}
