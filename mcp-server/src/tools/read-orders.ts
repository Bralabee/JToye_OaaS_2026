import { z } from "zod";
import pino from "pino";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { coreGet } from "../core-client.js";
import { toToolError } from "../errors.js";

/**
 * read-orders.ts — the `read_orders` read tool.
 *
 * Three read shapes over core's real order endpoints, all built from
 * ALLOW-LISTED path templates only (SSRF guard, T-20-04 — the caller never
 * chooses the host):
 *   - orderId set → GET /api/v1/orders/{orderId}/detail   (single order detail)
 *   - shopId  set → GET /api/v1/orders/shop/{shopId}       (shop-scoped list)
 *   - neither      → GET /api/v1/orders[?page&size]        (tenant order list)
 *
 * Order DTOs carry customer PII (customerName/customerEmail/customerPhone,
 * tenantId). Cross-tenant isolation is core's RLS: a tenant-A caller reading a
 * tenant-B order id resolves to 404/empty — MCP makes no tenant decision. We
 * log ONLY the tool name + core status — NEVER the Bearer or the response body
 * (T-20-01).
 */
const logger = pino({ name: "jtoye-mcp" });

// Raw Zod shape (NOT z.object) — the @modelcontextprotocol/sdk v1.29.0 contract.
export const readOrdersInputSchema = {
  page: z.number().int().min(0).optional().describe("0-based page index (list mode)"),
  size: z.number().int().min(1).max(100).optional().describe("page size, max 100 (list mode)"),
  shopId: z.string().optional().describe("scope the order list to one shop"),
  orderId: z.string().optional().describe("fetch a single order's detail"),
};

interface ReadOrdersArgs {
  page?: number;
  size?: number;
  shopId?: string;
  orderId?: string;
}

/** Build the core path from allow-listed templates only (T-20-04). */
function buildPath(args: ReadOrdersArgs): string {
  if (args.orderId !== undefined) {
    return `/api/v1/orders/${encodeURIComponent(args.orderId)}/detail`;
  }
  if (args.shopId !== undefined) {
    return `/api/v1/orders/shop/${encodeURIComponent(args.shopId)}`;
  }
  const qs = new URLSearchParams();
  if (args.page !== undefined) qs.set("page", String(args.page));
  if (args.size !== undefined) qs.set("size", String(args.size));
  const query = qs.toString();
  return query ? `/api/v1/orders?${query}` : "/api/v1/orders";
}

/**
 * Factory returning the tool handler bound to a request's Bearer. Exported so it
 * can be unit-tested in isolation with a mocked coreGet.
 */
export function readOrdersHandler(bearer: string) {
  return async (args: ReadOrdersArgs): Promise<CallToolResult> => {
    const path = buildPath(args);

    try {
      const res = await coreGet(path, bearer);
      // Log tool + status ONLY — never the body: order DTOs carry customer PII.
      logger.info({ tool: "read_orders", status: res.status }, "tool call");
      if (!res.ok) return toToolError(res);
      return { content: [{ type: "text", text: JSON.stringify(res.body) }] };
    } catch {
      // Never bind/log the error: it may carry undici internals; the token is
      // never in it, but we surface a generic, sanitized message regardless.
      logger.warn({ tool: "read_orders" }, "core unreachable or timed out");
      return {
        content: [{ type: "text", text: "Core API unreachable or timed out" }],
        isError: true,
      };
    }
  };
}

/** Register `read_orders` on an McpServer, closing over the request Bearer. */
export function registerReadOrders(server: McpServer, bearer: string): void {
  server.registerTool(
    "read_orders",
    {
      title: "Read orders",
      description:
        "Read the calling tenant's orders (RLS-scoped by the token): list all, " +
        "scope to one shop via shopId, or fetch a single order's detail via orderId.",
      inputSchema: readOrdersInputSchema,
    },
    readOrdersHandler(bearer),
  );
}
