import { z } from "zod";
import pino from "pino";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { coreGet } from "../core-client.js";
import { toToolError } from "../errors.js";

/**
 * list-products.ts — the `list_products` read tool.
 *
 * Thin forwarding posture (analog: edge-go/internal/core/client.go): build the
 * path from an allow-listed template (page/size only → SSRF-safe, T-20-04),
 * forward the caller's Bearer via coreGet, wrap the JSON on success, delegate
 * error shaping to toToolError. We log ONLY the tool name + core status —
 * never the Bearer or the response body (order/customer PII, T-20-01).
 */
const logger = pino({ name: "jtoye-mcp" });

// Raw Zod shape (NOT z.object) — the @modelcontextprotocol/sdk v1.29.0 contract.
export const listProductsInputSchema = {
  page: z.number().int().min(0).optional().describe("0-based page index"),
  size: z.number().int().min(1).max(100).optional().describe("page size (max 100)"),
};

interface ListProductsArgs {
  page?: number;
  size?: number;
}

/**
 * Factory returning the tool handler bound to a request's Bearer. Exported so it
 * can be unit-tested in isolation with a mocked coreGet.
 */
export function listProductsHandler(bearer: string) {
  return async (args: ListProductsArgs): Promise<CallToolResult> => {
    const qs = new URLSearchParams();
    if (args.page !== undefined) qs.set("page", String(args.page));
    if (args.size !== undefined) qs.set("size", String(args.size));
    const query = qs.toString();
    const path = query ? `/api/v1/products?${query}` : "/api/v1/products";

    try {
      const res = await coreGet(path, bearer);
      logger.info({ tool: "list_products", status: res.status }, "tool call");
      if (!res.ok) return toToolError(res);
      return { content: [{ type: "text", text: JSON.stringify(res.body) }] };
    } catch {
      // Never bind/log the error: it may carry undici internals; the token is
      // never in it, but we surface a generic, sanitized message regardless.
      logger.warn({ tool: "list_products" }, "core unreachable or timed out");
      return {
        content: [{ type: "text", text: "Core API unreachable or timed out" }],
        isError: true,
      };
    }
  };
}

/** Register `list_products` on an McpServer, closing over the request Bearer. */
export function registerListProducts(server: McpServer, bearer: string): void {
  server.registerTool(
    "list_products",
    {
      title: "List products",
      description: "List the calling tenant's product catalogue (RLS-scoped by the token).",
      inputSchema: listProductsInputSchema,
    },
    listProductsHandler(bearer),
  );
}
