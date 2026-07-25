import { z } from "zod";
import pino from "pino";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { corePost } from "../core-client.js";
import { toToolError } from "../errors.js";

/**
 * create-order.ts — the `create_order` write tool (D-07, snake_case).
 *
 * A thin forwarder over core's POST /api/v1/orders. The path is a FIXED
 * constant — no caller input ever reaches the host or path (SSRF guard,
 * T-25-08) — and the caller's Bearer is forwarded verbatim (core is the sole
 * validator AND the sole RLS tenant boundary: a tenant-A token targeting a
 * tenant-B shopId resolves 404 in core, never here).
 *
 * Idempotency (D-05): `idempotencyKey` is a REQUIRED tool input, split OUT of
 * the JSON body and forwarded as the `Idempotency-Key` header, so the tool has
 * NO non-idempotent path — a replay returns the original order, never a
 * duplicate. Core's 409 (in-flight) / 422 (same-key different-body) problem+json
 * flow through toToolError unchanged (D-06).
 *
 * Order DTOs carry customer PII (customerName/customerEmail/customerPhone). We
 * log ONLY the tool name + core status — NEVER the Bearer, the args, or the
 * response body (T-25-09).
 */
const logger = pino({ name: "jtoye-mcp" });

// SSRF (T-25-08): a fixed path constant — the caller never composes host or path.
const CREATE_ORDER_PATH = "/api/v1/orders";

// Raw Zod shape (NOT z.object) — the @modelcontextprotocol/sdk v1.29.0 contract.
// Field names/types mirror CreateOrderRequest + OrderItemRequest, verified against
// docs/api/openapi-snapshot.json (D-08). `items` is kept required (.min(1)) to match
// the runtime @NotEmpty @Valid constraint — the snapshot's `required` array under-
// reports it (springdoc does not propagate @NotEmpty on a collection to `required`),
// but a create with no items is a guaranteed 400, so a self-describing schema is the
// better agent DX. `idempotencyKey` is tool-only (NOT a DTO field) — split to header.
export const createOrderInputSchema = {
  shopId: z.string().uuid().describe("Target shop (UUID, required)"),
  customerId: z
    .string()
    .uuid()
    .optional()
    .describe("Existing customer to attach (UUID, optional)"),
  customerName: z
    .string()
    .optional()
    .describe("Walk-in customer name (optional; ignored when customerId is set)"),
  customerEmail: z.string().email().optional().describe("Walk-in customer email (optional)"),
  customerPhone: z.string().optional().describe("Walk-in customer phone (optional)"),
  notes: z.string().optional().describe("Order notes (optional)"),
  items: z
    .array(z.object({ productId: z.string().uuid(), quantity: z.number().int().min(1) }))
    .min(1)
    .describe("Order line items — at least one { productId (UUID), quantity (>=1) }"),
  idempotencyKey: z
    .string()
    .min(1)
    .max(64)
    .describe(
      "Reuse the SAME key when retrying — a replay returns the original order, never a duplicate.",
    ),
};

interface CreateOrderArgs {
  shopId: string;
  customerId?: string;
  customerName?: string;
  customerEmail?: string;
  customerPhone?: string;
  notes?: string;
  items: { productId: string; quantity: number }[];
  idempotencyKey: string;
}

/**
 * Factory returning the tool handler bound to a request's Bearer. Exported so it
 * can be unit-tested in isolation with a mocked corePost.
 */
export function createOrderHandler(bearer: string) {
  return async (args: CreateOrderArgs): Promise<CallToolResult> => {
    // Split the tool-only key OUT of the body → header (D-05): no non-idempotent path.
    const { idempotencyKey, ...body } = args;

    try {
      const res = await corePost(CREATE_ORDER_PATH, bearer, body, {
        "Idempotency-Key": idempotencyKey,
      });
      // Log tool + status ONLY — never the body/args: order DTOs carry customer PII.
      logger.info({ tool: "create_order", status: res.status }, "tool call");
      if (!res.ok) return toToolError(res);
      return { content: [{ type: "text", text: JSON.stringify(res.body) }] };
    } catch {
      // Never bind/log the error: the token is never in it, but we surface a
      // generic sanitized message regardless (undici internals stay out).
      logger.warn({ tool: "create_order" }, "core unreachable or timed out");
      return {
        content: [{ type: "text", text: "Core API unreachable or timed out" }],
        isError: true,
      };
    }
  };
}

/** Register `create_order` on an McpServer, closing over the request Bearer. */
export function registerCreateOrder(server: McpServer, bearer: string): void {
  server.registerTool(
    "create_order",
    {
      title: "Create order",
      description:
        "Create an order for the calling tenant (RLS-scoped by the token) at a shop you " +
        "manage. Requires a stable idempotencyKey — REUSE the same key on any retry so a " +
        "replay returns the original order, never a duplicate.",
      inputSchema: createOrderInputSchema,
    },
    createOrderHandler(bearer),
  );
}
