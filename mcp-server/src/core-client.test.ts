import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { coreGet, corePost } from "./core-client.js";

/**
 * core-client.test.ts — mocks the Node 20 global `fetch` and asserts that
 * coreGet forwards the caller's Bearer verbatim to the internal core base,
 * shapes a 2xx JSON response, and never swallows the token into a thrown error.
 */
describe("coreGet", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    delete process.env.CORE_BASE_URL; // exercise the internal-service-name default
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  function jsonResponse(status: number, body: unknown): Response {
    return {
      ok: status >= 200 && status < 300,
      status,
      headers: { get: (h: string) => (h.toLowerCase() === "content-type" ? "application/json" : null) },
      json: async () => body,
      text: async () => JSON.stringify(body),
    } as unknown as Response;
  }

  it("forwards the Bearer verbatim and targets the internal core base with accept: application/json", async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValue(jsonResponse(200, { content: [] }));

    await coreGet("/api/v1/products", "tok");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("http://core-java:9090/api/v1/products");
    const headers = (init as RequestInit).headers as Record<string, string>;
    expect(headers.authorization).toBe("Bearer tok");
    expect(headers.accept).toBe("application/json");
    // A timeout signal must be attached (trips before core's 30s query timeout).
    expect((init as RequestInit).signal).toBeDefined();
  });

  it("shapes a 200 JSON response into { ok, status, contentType, body }", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(200, { content: [{ id: "P1" }] }));

    const res = await coreGet("/api/v1/products", "tok");

    expect(res.ok).toBe(true);
    expect(res.status).toBe(200);
    expect(res.contentType).toContain("json");
    expect(res.body).toEqual({ content: [{ id: "P1" }] });
  });

  it("propagates a network/timeout fault without leaking the token into the error message", async () => {
    vi.mocked(fetch).mockRejectedValue(new Error("network down"));

    await expect(coreGet("/api/v1/products", "super-secret-token")).rejects.toThrow();
    try {
      await coreGet("/api/v1/products", "super-secret-token");
    } catch (err) {
      expect(String((err as Error).message)).not.toContain("super-secret-token");
    }
  });
});

/**
 * corePost — the write sibling of coreGet: POST with a JSON body, a
 * content-type: application/json request header, the verbatim Bearer, and a
 * caller-supplied extra-headers map (Idempotency-Key). Same SSRF posture (fixed
 * internal base) and same no-token-in-errors guarantee.
 */
describe("corePost", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    delete process.env.CORE_BASE_URL; // exercise the internal-service-name default
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  function jsonResponse(status: number, body: unknown): Response {
    return {
      ok: status >= 200 && status < 300,
      status,
      headers: { get: (h: string) => (h.toLowerCase() === "content-type" ? "application/json" : null) },
      json: async () => body,
      text: async () => JSON.stringify(body),
    } as unknown as Response;
  }

  it("POSTs a JSON body with content-type application/json, verbatim Bearer, and the Idempotency-Key header", async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValue(jsonResponse(201, { id: "O1" }));

    await corePost(
      "/api/v1/orders",
      "tok",
      { shopId: "s1", items: [{ productId: "p1", quantity: 1 }] },
      { "Idempotency-Key": "key-123" },
    );

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("http://core-java:9090/api/v1/orders");
    const ri = init as RequestInit;
    expect(ri.method).toBe("POST");
    const headers = ri.headers as Record<string, string>;
    expect(headers.authorization).toBe("Bearer tok");
    expect(headers.accept).toBe("application/json");
    expect(headers["content-type"]).toBe("application/json");
    expect(headers["Idempotency-Key"]).toBe("key-123");
    // The body is JSON-serialized (never the raw object).
    expect(ri.body).toBe(
      JSON.stringify({ shopId: "s1", items: [{ productId: "p1", quantity: 1 }] }),
    );
    // A timeout signal must be attached (trips before core's 30s query timeout).
    expect(ri.signal).toBeDefined();
  });

  it("WR-01: an extra-headers entry cannot override the verbatim Bearer or content-type", async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValue(jsonResponse(201, { id: "O1" }));

    // A caller (or a compromised tool layer) that tries to smuggle a different
    // authorization / content-type through the extra-headers map must lose: the
    // fixed security headers are applied LAST and win on key collision.
    await corePost(
      "/api/v1/orders",
      "real-token",
      { shopId: "s1" },
      {
        authorization: "Bearer FORGED",
        "content-type": "text/evil",
        accept: "text/evil",
        "Idempotency-Key": "key-123",
      },
    );

    const [, init] = fetchMock.mock.calls[0]!;
    const headers = (init as RequestInit).headers as Record<string, string>;
    expect(headers.authorization).toBe("Bearer real-token");
    expect(headers["content-type"]).toBe("application/json");
    expect(headers.accept).toBe("application/json");
    // A non-colliding extra header is still forwarded.
    expect(headers["Idempotency-Key"]).toBe("key-123");
  });

  it("shapes a 201 JSON response into { ok, status, contentType, body } (ok is true for 201)", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(201, { id: "C1" }));

    const res = await corePost("/api/v1/customers", "tok", { name: "Ada" }, {});

    expect(res.ok).toBe(true);
    expect(res.status).toBe(201);
    expect(res.contentType).toContain("json");
    expect(res.body).toEqual({ id: "C1" });
  });

  it("propagates a network/timeout fault without leaking the token into the error message", async () => {
    vi.mocked(fetch).mockRejectedValue(new Error("network down"));

    await expect(
      corePost("/api/v1/orders", "super-secret-token", { shopId: "s1" }, {}),
    ).rejects.toThrow();
    try {
      await corePost("/api/v1/orders", "super-secret-token", { shopId: "s1" }, {});
    } catch (err) {
      expect(String((err as Error).message)).not.toContain("super-secret-token");
    }
  });
});
