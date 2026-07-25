import { z } from "zod";
import pino from "pino";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { corePost } from "../core-client.js";
import { toToolError } from "../errors.js";

/**
 * create-customer.ts — the `create_customer` write tool (D-07, snake_case).
 *
 * A thin forwarder over core's POST /api/v1/customers. The path is a FIXED
 * constant — no caller input reaches the host or path (SSRF guard, T-25-08) —
 * and the caller's Bearer is forwarded verbatim (core is the sole validator AND
 * the sole RLS tenant boundary: the created customer lands under the token's
 * tenant, never another's).
 *
 * Idempotency (D-05): `idempotencyKey` is a REQUIRED tool input, split OUT of
 * the JSON body and forwarded as the `Idempotency-Key` header, so the tool has
 * NO non-idempotent path — a replay returns the original customer, never a
 * duplicate. Core's 409 (in-flight) / 422 (same-key different-body) problem+json
 * flow through toToolError unchanged (D-06).
 *
 * Customer DTOs carry PII (name/email/phone). We log ONLY the tool name + core
 * status — NEVER the Bearer, the args, or the response body (T-25-09).
 */
const logger = pino({ name: "jtoye-mcp" });

// SSRF (T-25-08): a fixed path constant — the caller never composes host or path.
const CREATE_CUSTOMER_PATH = "/api/v1/customers";

// Raw Zod shape (NOT z.object) — the @modelcontextprotocol/sdk v1.29.0 contract.
// Field names/types mirror CustomerController.CreateCustomerRequest, verified against
// docs/api/openapi-snapshot.json (D-08). `name`/`email` are kept required to match the
// runtime @NotBlank constraints — the snapshot's `required` array under-reports them
// (springdoc does not propagate @NotBlank to `required`), but a create without them is a
// guaranteed 400, so a self-describing schema is the better agent DX. `idempotencyKey`
// is tool-only (NOT a DTO field) — split to header.
export const createCustomerInputSchema = {
  name: z.string().min(1).max(255).describe("Customer full name (required)"),
  email: z
    .string()
    .email()
    .max(255)
    .describe("Customer email (required, unique per tenant)"),
  phone: z.string().max(50).optional().describe("Customer phone (optional)"),
  allergenRestrictions: z
    .number()
    .int()
    .optional()
    .describe("Allergen restriction bitmask (optional)"),
  idempotencyKey: z
    .string()
    .min(1)
    .max(64)
    .describe(
      "Reuse the SAME key when retrying — a replay returns the original customer, never a duplicate.",
    ),
};

interface CreateCustomerArgs {
  name: string;
  email: string;
  phone?: string;
  allergenRestrictions?: number;
  idempotencyKey: string;
}

/**
 * Factory returning the tool handler bound to a request's Bearer. Exported so it
 * can be unit-tested in isolation with a mocked corePost.
 */
export function createCustomerHandler(bearer: string) {
  return async (args: CreateCustomerArgs): Promise<CallToolResult> => {
    // Split the tool-only key OUT of the body → header (D-05): no non-idempotent path.
    const { idempotencyKey, ...body } = args;

    try {
      const res = await corePost(CREATE_CUSTOMER_PATH, bearer, body, {
        "Idempotency-Key": idempotencyKey,
      });
      // Log tool + status ONLY — never the body/args: customer DTOs carry PII.
      logger.info({ tool: "create_customer", status: res.status }, "tool call");
      if (!res.ok) return toToolError(res);
      return { content: [{ type: "text", text: JSON.stringify(res.body) }] };
    } catch {
      // Never bind/log the error: the token is never in it, but we surface a
      // generic sanitized message regardless (undici internals stay out).
      logger.warn({ tool: "create_customer" }, "core unreachable or timed out");
      return {
        content: [{ type: "text", text: "Core API unreachable or timed out" }],
        isError: true,
      };
    }
  };
}

/** Register `create_customer` on an McpServer, closing over the request Bearer. */
export function registerCreateCustomer(server: McpServer, bearer: string): void {
  server.registerTool(
    "create_customer",
    {
      title: "Create customer",
      description:
        "Create a customer for the calling tenant (RLS-scoped by the token). Requires a " +
        "stable idempotencyKey — REUSE the same key on any retry so a replay returns the " +
        "original customer, never a duplicate.",
      inputSchema: createCustomerInputSchema,
    },
    createCustomerHandler(bearer),
  );
}
