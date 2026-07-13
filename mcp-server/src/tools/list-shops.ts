import pino from "pino";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { coreGet } from "../core-client.js";
import { toToolError } from "../errors.js";

/**
 * list-shops.ts — the `list_shops` read tool.
 *
 * Sibling of list-products.ts. `GET /api/v1/shops` exposes no pagination params
 * (RESEARCH A1), so the tool takes an EMPTY input shape and forwards a fixed,
 * allow-listed path (SSRF-safe, T-20-04). Forward the caller's Bearer via
 * coreGet, wrap the JSON on success, delegate error shaping to toToolError. Log
 * ONLY the tool name + core status — never the Bearer or the response body.
 */
const logger = pino({ name: "jtoye-mcp" });

// Raw Zod shape (NOT z.object) — the @modelcontextprotocol/sdk v1.29.0 contract.
// GET /api/v1/shops takes no query params, so the input shape is empty.
export const listShopsInputSchema = {};

/**
 * Factory returning the tool handler bound to a request's Bearer. Exported so it
 * can be unit-tested in isolation with a mocked coreGet.
 */
export function listShopsHandler(bearer: string) {
  return async (_args: Record<string, never>): Promise<CallToolResult> => {
    try {
      const res = await coreGet("/api/v1/shops", bearer);
      logger.info({ tool: "list_shops", status: res.status }, "tool call");
      if (!res.ok) return toToolError(res);
      return { content: [{ type: "text", text: JSON.stringify(res.body) }] };
    } catch {
      // Never bind/log the error: it may carry undici internals; the token is
      // never in it, but we surface a generic, sanitized message regardless.
      logger.warn({ tool: "list_shops" }, "core unreachable or timed out");
      return {
        content: [{ type: "text", text: "Core API unreachable or timed out" }],
        isError: true,
      };
    }
  };
}

/** Register `list_shops` on an McpServer, closing over the request Bearer. */
export function registerListShops(server: McpServer, bearer: string): void {
  server.registerTool(
    "list_shops",
    {
      title: "List shops",
      description: "List the calling tenant's shops (RLS-scoped by the token).",
      inputSchema: listShopsInputSchema,
    },
    listShopsHandler(bearer),
  );
}
