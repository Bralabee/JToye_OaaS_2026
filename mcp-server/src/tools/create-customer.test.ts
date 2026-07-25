import { beforeEach, describe, expect, it, vi } from "vitest";

// Capture the pino logger's calls so we can PROVE the handler never logs the
// request args or the response body (customer DTOs carry PII — T-25-09). The
// mock returns a single shared spy object for every pino() call.
const { logSpies } = vi.hoisted(() => ({
  logSpies: {
    info: vi.fn(),
    warn: vi.fn(),
    error: vi.fn(),
    debug: vi.fn(),
    fatal: vi.fn(),
    trace: vi.fn(),
  },
}));
vi.mock("pino", () => ({ default: () => logSpies }));

// Mock the core forwarder; toToolError (errors.js) runs for real so the
// delegation path is exercised end-to-end.
vi.mock("../core-client.js", () => ({ corePost: vi.fn(), coreGet: vi.fn() }));

import { z } from "zod";
import { corePost } from "../core-client.js";
import { createCustomerHandler, createCustomerInputSchema } from "./create-customer.js";

const CUSTOMER_EMAIL = "ada@example.com";

function validArgs() {
  return {
    name: "Ada Lovelace",
    email: CUSTOMER_EMAIL,
    phone: "07700900123",
    allergenRestrictions: 3,
    idempotencyKey: "customer-key-xyz-789",
  };
}

const created201 = {
  ok: true as const,
  status: 201,
  contentType: "application/json",
  body: { id: "C1", name: "Ada Lovelace", email: CUSTOMER_EMAIL },
};

/**
 * create-customer.test.ts — the write tool forwards to core's fixed POST
 * /api/v1/customers (SSRF-safe constant path, T-25-08), splitting the REQUIRED
 * idempotencyKey OUT of the JSON body and sending it as the Idempotency-Key
 * header (D-05). It wraps 2xx bodies, delegates non-2xx to toToolError, and
 * NEVER logs the args or the response body (PII guard, T-25-09).
 */
describe("create_customer handler", () => {
  beforeEach(() => {
    vi.mocked(corePost).mockReset();
    logSpies.info.mockReset();
    logSpies.warn.mockReset();
    logSpies.error.mockReset();
  });

  it("posts to the fixed /api/v1/customers path with idempotencyKey stripped to the header", async () => {
    vi.mocked(corePost).mockResolvedValue(created201);

    const result = await createCustomerHandler("tok")(validArgs());

    expect(result.isError).toBeFalsy();
    expect((result.content[0] as { text: string }).text).toContain("C1");

    expect(corePost).toHaveBeenCalledTimes(1);
    const [path, bearer, body, headers] = vi.mocked(corePost).mock.calls[0]!;
    expect(path).toBe("/api/v1/customers");
    expect(bearer).toBe("tok");
    expect(body).not.toHaveProperty("idempotencyKey");
    expect(headers).toEqual({ "Idempotency-Key": "customer-key-xyz-789" });
    expect(body).toMatchObject({
      name: "Ada Lovelace",
      email: CUSTOMER_EMAIL,
      phone: "07700900123",
      allergenRestrictions: 3,
    });
  });

  it("delegates a 403 no-scope problem+json to toToolError (sanitized, no stack)", async () => {
    vi.mocked(corePost).mockResolvedValue({
      ok: false,
      status: 403,
      contentType: "application/problem+json",
      body: { title: "Forbidden", detail: "token lacks customers:write" },
    });

    const result = await createCustomerHandler("tok")(validArgs());

    expect(result.isError).toBe(true);
    expect((result.content[0] as { text: string }).text).toContain("403");
  });

  it("delegates a 409 duplicate/in-flight problem+json to toToolError", async () => {
    vi.mocked(corePost).mockResolvedValue({
      ok: false,
      status: 409,
      contentType: "application/problem+json",
      body: { title: "Conflict", detail: "customer email already exists" },
    });

    const result = await createCustomerHandler("tok")(validArgs());

    expect(result.isError).toBe(true);
    expect((result.content[0] as { text: string }).text).toContain("409");
  });

  it("delegates a 422 same-key/different-body problem+json to toToolError", async () => {
    vi.mocked(corePost).mockResolvedValue({
      ok: false,
      status: 422,
      contentType: "application/problem+json",
      body: { title: "Idempotency Payload Mismatch", detail: "same key, different body" },
    });

    const result = await createCustomerHandler("tok")(validArgs());

    expect(result.isError).toBe(true);
    expect((result.content[0] as { text: string }).text).toContain("422");
  });

  it("turns a thrown network fault into a sanitized isError (token never in message)", async () => {
    vi.mocked(corePost).mockRejectedValue(new Error("connect ECONNREFUSED"));

    const result = await createCustomerHandler("super-secret-token")(validArgs());

    expect(result.isError).toBe(true);
    const text = (result.content[0] as { text: string }).text;
    expect(text).toBe("Core API unreachable or timed out");
    expect(text).not.toContain("super-secret-token");
  });

  it("NEVER logs the args or the response body — customer PII stays out of logs (T-25-09)", async () => {
    vi.mocked(corePost).mockResolvedValue(created201);

    await createCustomerHandler("tok")(validArgs());

    const allLogArgs = [
      ...logSpies.info.mock.calls,
      ...logSpies.warn.mock.calls,
      ...logSpies.error.mock.calls,
    ].flat();
    const serialized = JSON.stringify(allLogArgs);
    expect(serialized).not.toContain(CUSTOMER_EMAIL);
    expect(serialized).not.toContain("Ada Lovelace");
    expect(serialized).not.toContain("07700900123");
  });
});

/**
 * Schema-level required-ness (D-05/D-08): idempotencyKey is REQUIRED (1..64);
 * name + email are required (runtime @NotBlank) even though the OpenAPI snapshot
 * under-reports them.
 */
describe("create_customer input schema", () => {
  const schema = z.object(createCustomerInputSchema);

  it("rejects a missing idempotencyKey / missing name / non-email", () => {
    expect(schema.safeParse({ name: "Ada", email: "ada@example.com" }).success).toBe(false); // no key
    expect(schema.safeParse({ email: "ada@example.com", idempotencyKey: "k" }).success).toBe(false); // no name
    expect(schema.safeParse({ name: "Ada", email: "not-an-email", idempotencyKey: "k" }).success).toBe(
      false,
    );
  });

  it("accepts a valid customer with a 1..64 idempotencyKey", () => {
    expect(
      schema.safeParse({ name: "Ada", email: "ada@example.com", idempotencyKey: "customer-key-xyz-789" })
        .success,
    ).toBe(true);
  });
});
