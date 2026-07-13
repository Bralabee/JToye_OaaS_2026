import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { coreGet } from "./core-client.js";

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
