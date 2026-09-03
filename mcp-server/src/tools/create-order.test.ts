import { beforeEach, describe, expect, it, vi } from "vitest";

// Capture the pino logger's calls so we can PROVE the handler never logs the
// request args or the response body (order DTOs carry customer PII — T-25-09).
// The mock returns a single shared spy object for every pino() call.
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
import { createOrderHandler, createOrderInputSchema } from "./create-order.js";

const CUSTOMER_EMAIL = "walk-in-victim@example.com";

// A fully valid create_order arg set incl. the tool-only idempotencyKey + PII.
function validArgs() {
  return {
    shopId: "7f000001-0000-4000-8000-000000000002",
    customerName: "Jane Doe",
    customerEmail: CUSTOMER_EMAIL,
    customerPhone: "07700900000",
    notes: "leave at door",
    items: [{ productId: "0b6cbcf6-3535-49a0-a839-3f382e3ba9a7", quantity: 2 }],
    idempotencyKey: "order-key-abc-123", // gitleaks:allow (fake test idempotency key, not a credential)
  };
}

const created201 = {
  ok: true as const,
  status: 201,
  contentType: "application/json",
  body: { id: "O1", status: "DRAFT", customerEmail: CUSTOMER_EMAIL },
};

/**
 * create-order.test.ts — the write tool forwards to core's fixed POST
 * /api/v1/orders (SSRF-safe constant path, T-25-08), splitting the REQUIRED
 * idempotencyKey OUT of the JSON body and sending it as the Idempotency-Key
 * header (D-05). It wraps 2xx bodies, delegates non-2xx to toToolError, and
 * NEVER logs the args or the response body (PII guard, T-25-09).
 */
describe("create_order handler", () => {
  beforeEach(() => {
    vi.mocked(corePost).mockReset();
    logSpies.info.mockReset();
    logSpies.warn.mockReset();
    logSpies.error.mockReset();
  });

  it("posts to the fixed /api/v1/orders path with idempotencyKey stripped to the header", async () => {
    vi.mocked(corePost).mockResolvedValue(created201);

    const result = await createOrderHandler("tok")(validArgs());

    expect(result.isError).toBeFalsy();
    expect((result.content[0] as { text: string }).text).toContain("O1");

    expect(corePost).toHaveBeenCalledTimes(1);
    const [path, bearer, body, headers] = vi.mocked(corePost).mock.calls[0]!;
    expect(path).toBe("/api/v1/orders");
    expect(bearer).toBe("tok");
    // The tool-only key is NOT in the body...
    expect(body).not.toHaveProperty("idempotencyKey");
    // ...and IS forwarded as the Idempotency-Key header.
    expect(headers).toEqual({ "Idempotency-Key": "order-key-abc-123" }); // gitleaks:allow (fake test idempotency key, not a credential)
    // The camelCase DTO fields survive verbatim in the body.
    expect(body).toMatchObject({
      shopId: "7f000001-0000-4000-8000-000000000002",
      items: [{ productId: "0b6cbcf6-3535-49a0-a839-3f382e3ba9a7", quantity: 2 }],
    });
  });

  it("COR-1: forwards fulfilmentType + the address block verbatim in the body", async () => {
    vi.mocked(corePost).mockResolvedValue(created201);

    await createOrderHandler("tok")({
      ...validArgs(),
      fulfilmentType: "DELIVERY",
      addressLine1: "12 Coldharbour Lane",
      addressCity: "London",
      addressPostcode: "SW9 8LF",
    });

    const [, , body] = vi.mocked(corePost).mock.calls[0]!;
    expect(body).toMatchObject({
      fulfilmentType: "DELIVERY",
      addressLine1: "12 Coldharbour Lane",
      addressCity: "London",
      addressPostcode: "SW9 8LF",
    });
    // Still not in the body — the tool-only key is split to the header regardless.
    expect(body).not.toHaveProperty("idempotencyKey");
  });

  it("delegates a 403 no-scope problem+json to toToolError (sanitized, no stack)", async () => {
    vi.mocked(corePost).mockResolvedValue({
      ok: false,
      status: 403,
      contentType: "application/problem+json",
      body: { title: "Forbidden", detail: "token lacks orders:write" },
    });

    const result = await createOrderHandler("tok")(validArgs());

    expect(result.isError).toBe(true);
    expect((result.content[0] as { text: string }).text).toContain("403");
  });

  it("delegates a 409 in-flight idempotency problem+json to toToolError", async () => {
    vi.mocked(corePost).mockResolvedValue({
      ok: false,
      status: 409,
      contentType: "application/problem+json",
      body: { title: "Idempotency Conflict", detail: "request already in flight" },
    });

    const result = await createOrderHandler("tok")(validArgs());

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

    const result = await createOrderHandler("tok")(validArgs());

    expect(result.isError).toBe(true);
    expect((result.content[0] as { text: string }).text).toContain("422");
  });

  it("turns a thrown network fault into a sanitized isError (token never in message)", async () => {
    vi.mocked(corePost).mockRejectedValue(new Error("connect ECONNREFUSED"));

    const args = { ...validArgs(), idempotencyKey: "k" };
    const result = await createOrderHandler("super-secret-token")(args);

    expect(result.isError).toBe(true);
    const text = (result.content[0] as { text: string }).text;
    expect(text).toBe("Core API unreachable or timed out");
    expect(text).not.toContain("super-secret-token");
  });

  it("NEVER logs the args or the response body — customer PII stays out of logs (T-25-09)", async () => {
    vi.mocked(corePost).mockResolvedValue(created201);

    await createOrderHandler("tok")(validArgs());

    // Every argument passed to any log method, serialized, must not carry the PII.
    const allLogArgs = [
      ...logSpies.info.mock.calls,
      ...logSpies.warn.mock.calls,
      ...logSpies.error.mock.calls,
    ].flat();
    const serialized = JSON.stringify(allLogArgs);
    expect(serialized).not.toContain(CUSTOMER_EMAIL);
    expect(serialized).not.toContain("customerEmail");
    expect(serialized).not.toContain("customerPhone");
    expect(serialized).not.toContain("Jane Doe");
  });
});

/**
 * Schema-level required-ness (D-05/D-08): idempotencyKey is a REQUIRED tool input
 * (1..64, matching IdempotencyService's key bound), so the tool has no
 * non-idempotent path; shopId + a non-empty items[] are required too.
 */
describe("create_order input schema", () => {
  const schema = z.object(createOrderInputSchema);

  it("rejects a missing idempotencyKey and an over-length key", () => {
    const base = {
      shopId: "7f000001-0000-4000-8000-000000000002",
      items: [{ productId: "0b6cbcf6-3535-49a0-a839-3f382e3ba9a7", quantity: 1 }],
    };
    expect(schema.safeParse(base).success).toBe(false); // no key
    expect(schema.safeParse({ ...base, idempotencyKey: "" }).success).toBe(false); // blank
    expect(schema.safeParse({ ...base, idempotencyKey: "x".repeat(65) }).success).toBe(false); // >64
  });

  it("rejects an empty items[] and a non-UUID shopId", () => {
    expect(
      schema.safeParse({ shopId: "7f000001-0000-4000-8000-000000000002", items: [], idempotencyKey: "k" })
        .success,
    ).toBe(false);
    expect(
      schema.safeParse({
        shopId: "not-a-uuid",
        items: [{ productId: "0b6cbcf6-3535-49a0-a839-3f382e3ba9a7", quantity: 1 }],
        idempotencyKey: "k",
      }).success,
    ).toBe(false);
  });

  // COR-1 (owner ruling E-1): fulfilmentType + the UK address block are OPTIONAL tool inputs.
  // Omitting them is the COLLECTION case and must stay valid — every pre-COR-1 caller does that.
  it("COR-1: accepts an order with NO fulfilmentType — omission is the COLLECTION case", () => {
    expect(
      schema.safeParse({
        shopId: "7f000001-0000-4000-8000-000000000002",
        items: [{ productId: "0b6cbcf6-3535-49a0-a839-3f382e3ba9a7", quantity: 1 }],
        idempotencyKey: "order-key-abc-123", // gitleaks:allow (fake test idempotency key, not a credential)
      }).success,
    ).toBe(true);
  });

  // The COLLECTION arm is NOT redundant with the omission arm above. Removing "COLLECTION"
  // from the enum was run as a deliberate break arm and the suite stayed GREEN without this
  // assertion: omission, DELIVERY and the invalid-value arms all still passed. A schema value
  // that no test ever parses is a value no test protects.
  it("COR-1: accepts an EXPLICIT COLLECTION order — the value must be in the enum, not only the default", () => {
    expect(
      schema.safeParse({
        shopId: "7f000001-0000-4000-8000-000000000002",
        items: [{ productId: "0b6cbcf6-3535-49a0-a839-3f382e3ba9a7", quantity: 1 }],
        idempotencyKey: "order-key-abc-123", // gitleaks:allow (fake test idempotency key, not a credential)
        fulfilmentType: "COLLECTION",
      }).success,
    ).toBe(true);
  });

  it("COR-1: accepts a DELIVERY order carrying the UK address block", () => {
    expect(
      schema.safeParse({
        shopId: "7f000001-0000-4000-8000-000000000002",
        items: [{ productId: "0b6cbcf6-3535-49a0-a839-3f382e3ba9a7", quantity: 1 }],
        idempotencyKey: "order-key-abc-123", // gitleaks:allow (fake test idempotency key, not a credential)
        fulfilmentType: "DELIVERY",
        addressLine1: "12 Coldharbour Lane",
        addressLine2: "Flat 3",
        addressCity: "London",
        addressPostcode: "SW9 8LF",
      }).success,
    ).toBe(true);
  });

  it("COR-1: rejects a fulfilmentType outside the enum — an agent gets the error here, not a 400", () => {
    const base = {
      shopId: "7f000001-0000-4000-8000-000000000002",
      items: [{ productId: "0b6cbcf6-3535-49a0-a839-3f382e3ba9a7", quantity: 1 }],
      idempotencyKey: "order-key-abc-123", // gitleaks:allow (fake test idempotency key, not a credential)
    };
    expect(schema.safeParse({ ...base, fulfilmentType: "TELEPORT" }).success).toBe(false);
    // Lower case is NOT accepted at the tool boundary: the enum is the contract an agent reads,
    // and core would normalise it anyway. Being strict here makes the tool self-describing.
    expect(schema.safeParse({ ...base, fulfilmentType: "delivery" }).success).toBe(false);
    // The V45 column widths are enforced before the request leaves the agent.
    expect(schema.safeParse({ ...base, addressPostcode: "X".repeat(13) }).success).toBe(false);
  });

  it("accepts a valid order with a 1..64 idempotencyKey", () => {
    expect(
      schema.safeParse({
        shopId: "7f000001-0000-4000-8000-000000000002",
        items: [{ productId: "0b6cbcf6-3535-49a0-a839-3f382e3ba9a7", quantity: 1 }],
        idempotencyKey: "order-key-abc-123", // gitleaks:allow (fake test idempotency key, not a credential)
      }).success,
    ).toBe(true);
  });
});
