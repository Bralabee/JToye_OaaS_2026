import { beforeEach, describe, expect, it, vi } from "vitest";

// Mock the core forwarder; toToolError (errors.js) runs for real so the
// delegation path is exercised end-to-end.
vi.mock("../core-client.js", () => ({ coreGet: vi.fn() }));

import { coreGet } from "../core-client.js";
import { listProductsHandler } from "./list-products.js";

/**
 * list-products.test.ts — the tool handler forwards to coreGet, wraps a 2xx
 * body as text content, delegates non-2xx to toToolError, and turns a thrown
 * network fault into a sanitized isError without leaking the token.
 */
describe("list_products handler", () => {
  beforeEach(() => {
    vi.mocked(coreGet).mockReset();
  });

  it("wraps a 200 core body as text content with no isError", async () => {
    vi.mocked(coreGet).mockResolvedValue({
      ok: true,
      status: 200,
      contentType: "application/json",
      body: { content: [{ id: "P1" }] },
    });

    const result = await listProductsHandler("tok")({});

    expect(result.isError).toBeFalsy();
    const text = (result.content[0] as { text: string }).text;
    expect(text).toContain("P1");
    expect(coreGet).toHaveBeenCalledWith("/api/v1/products", "tok");
  });

  it("forwards allow-listed page/size params only", async () => {
    vi.mocked(coreGet).mockResolvedValue({
      ok: true,
      status: 200,
      contentType: "application/json",
      body: { content: [] },
    });

    await listProductsHandler("tok")({ page: 2, size: 50 });

    expect(coreGet).toHaveBeenCalledWith("/api/v1/products?page=2&size=50", "tok");
  });

  it("delegates a non-2xx problem+json response to toToolError (isError)", async () => {
    vi.mocked(coreGet).mockResolvedValue({
      ok: false,
      status: 403,
      contentType: "application/problem+json",
      body: { title: "Forbidden" },
    });

    const result = await listProductsHandler("tok")({});

    expect(result.isError).toBe(true);
    expect((result.content[0] as { text: string }).text).toContain("403");
  });

  it("turns a thrown network fault into a sanitized isError (token never in message)", async () => {
    vi.mocked(coreGet).mockRejectedValue(new Error("connect ECONNREFUSED"));

    const result = await listProductsHandler("super-secret-token")({});

    expect(result.isError).toBe(true);
    const text = (result.content[0] as { text: string }).text;
    expect(text).toBe("Core API unreachable or timed out");
    expect(text).not.toContain("super-secret-token");
  });
});
