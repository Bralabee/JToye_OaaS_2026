import { beforeEach, describe, expect, it, vi } from "vitest";

// Capture the pino logger's calls so we can PROVE the handler never logs the
// response body (order DTOs carry customer PII — T-20-01). The mock returns a
// single shared spy object for every pino() call.
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
vi.mock("../core-client.js", () => ({ coreGet: vi.fn() }));

import { coreGet } from "../core-client.js";
import { readOrdersHandler } from "./read-orders.js";

const ok200 = {
  ok: true as const,
  status: 200,
  contentType: "application/json",
  body: { content: [{ id: "O1" }] },
};

/**
 * read-orders.test.ts — the tool routes to core's real order endpoints from
 * allow-listed path templates (SSRF guard, T-20-04): list, shop-scoped list,
 * and single-order detail. It wraps 2xx bodies, delegates non-2xx to
 * toToolError, and NEVER logs the response body (PII guard, T-20-01).
 */
describe("read_orders handler", () => {
  beforeEach(() => {
    vi.mocked(coreGet).mockReset();
    logSpies.info.mockReset();
    logSpies.warn.mockReset();
    logSpies.error.mockReset();
  });

  it("with no args lists orders at /api/v1/orders", async () => {
    vi.mocked(coreGet).mockResolvedValue(ok200);

    const result = await readOrdersHandler("tok")({});

    expect(result.isError).toBeFalsy();
    expect((result.content[0] as { text: string }).text).toContain("O1");
    expect(coreGet).toHaveBeenCalledWith("/api/v1/orders", "tok");
  });

  it("forwards allow-listed page/size params on the list path", async () => {
    vi.mocked(coreGet).mockResolvedValue(ok200);

    await readOrdersHandler("tok")({ page: 1, size: 25 });

    expect(coreGet).toHaveBeenCalledWith("/api/v1/orders?page=1&size=25", "tok");
  });

  it("scopes to one shop at /api/v1/orders/shop/{shopId} when shopId is set", async () => {
    vi.mocked(coreGet).mockResolvedValue(ok200);

    await readOrdersHandler("tok")({ shopId: "sh1" });

    expect(coreGet).toHaveBeenCalledWith("/api/v1/orders/shop/sh1", "tok");
  });

  it("reads one order's detail at /api/v1/orders/{id}/detail when orderId is set", async () => {
    vi.mocked(coreGet).mockResolvedValue(ok200);

    await readOrdersHandler("tok")({ orderId: "o1" });

    expect(coreGet).toHaveBeenCalledWith("/api/v1/orders/o1/detail", "tok");
  });

  it("orderId takes precedence over shopId (detail wins)", async () => {
    vi.mocked(coreGet).mockResolvedValue(ok200);

    await readOrdersHandler("tok")({ orderId: "o1", shopId: "sh1" });

    expect(coreGet).toHaveBeenCalledWith("/api/v1/orders/o1/detail", "tok");
  });

  it("delegates a 404 problem+json (cross-tenant id → RLS 404) to toToolError", async () => {
    // A tenant-A caller reading a tenant-B order id surfaces here as 404/empty,
    // NEVER tenant B's row (core RLS is the isolation boundary; MCP picks no tenant).
    vi.mocked(coreGet).mockResolvedValue({
      ok: false,
      status: 404,
      contentType: "application/problem+json",
      body: { title: "Resource Not Found" },
    });

    const result = await readOrdersHandler("tok")({ orderId: "someone-elses-order" });

    expect(result.isError).toBe(true);
    expect((result.content[0] as { text: string }).text).toContain("404");
  });

  it("turns a thrown network fault into a sanitized isError (token never in message)", async () => {
    vi.mocked(coreGet).mockRejectedValue(new Error("connect ECONNREFUSED"));

    const result = await readOrdersHandler("super-secret-token")({});

    expect(result.isError).toBe(true);
    const text = (result.content[0] as { text: string }).text;
    expect(text).toBe("Core API unreachable or timed out");
    expect(text).not.toContain("super-secret-token");
  });

  it("NEVER logs the response body — customer PII stays out of logs (T-20-01)", async () => {
    const PII = "victim@example.com";
    vi.mocked(coreGet).mockResolvedValue({
      ok: true,
      status: 200,
      contentType: "application/json",
      body: {
        content: [
          { id: "O1", customerName: "Jane Doe", customerEmail: PII, customerPhone: "07700900000" },
        ],
      },
    });

    await readOrdersHandler("tok")({});

    // Every argument passed to any log method, serialized, must not carry the PII.
    const allLogArgs = [
      ...logSpies.info.mock.calls,
      ...logSpies.warn.mock.calls,
      ...logSpies.error.mock.calls,
    ].flat();
    const serialized = JSON.stringify(allLogArgs);
    expect(serialized).not.toContain(PII);
    expect(serialized).not.toContain("customerEmail");
    expect(serialized).not.toContain("customerPhone");
  });
});
