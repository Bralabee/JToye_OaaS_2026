import { describe, expect, it } from "vitest";
import { toToolError } from "./errors.js";

/**
 * errors.test.ts — RFC 7807 problem+json (emitted by core's GlobalExceptionHandler)
 * and bare status codes must map to sanitized MCP isError results: never a stack
 * trace, undici internals, the raw upstream body, or the token.
 */
describe("toToolError", () => {
  it("maps application/problem+json to 'core <status> <title>: <detail>' with isError", () => {
    const result = toToolError({
      status: 404,
      contentType: "application/problem+json",
      body: { title: "Resource Not Found", detail: "x", status: 404 },
    });

    expect(result.isError).toBe(true);
    expect(result.content).toEqual([{ type: "text", text: "core 404 Resource Not Found: x" }]);
    const text = (result.content[0] as { text: string }).text;
    expect(text).not.toMatch(/\bat\s+\w+/); // no stack frame
    expect(text.toLowerCase()).not.toContain("stack");
  });

  // #879 / UXT-123 (P2-RAV-13): core's typed problem details used to be flattened to prose, losing the error
  // type and the per-field details a caller needs to correct the request and retry.
  describe("typed problem details survive (#879)", () => {
    const validation = {
      type: "https://jtoye.uk/errors/validation",
      title: "Validation Failed",
      status: 400,
      detail: "Validation failed for 2 field(s)",
      errors: { customerEmail: "must be a well-formed email address", "items[0].quantity": "must be at least 1" },
    };

    it("keeps the one-line summary first, unchanged, so existing readers are unaffected", () => {
      const result = toToolError({ status: 400, contentType: "application/problem+json", body: validation });
      const text = (result.content[0] as { text: string }).text;
      expect(text.split("\n")[0]).toBe("core 400 Validation Failed: Validation failed for 2 field(s)");
    });

    it("carries the error type and the per-field errors as structured content and as text a model reads", () => {
      const result = toToolError({ status: 400, contentType: "application/problem+json", body: validation });

      expect(result.structuredContent).toMatchObject({
        status: 400,
        type: "https://jtoye.uk/errors/validation",
        errors: { customerEmail: "must be a well-formed email address" },
      });
      const text = (result.content[0] as { text: string }).text;
      expect(text).toContain("https://jtoye.uk/errors/validation");
      expect(text).toContain("customerEmail");
      expect(text).toContain("items[0].quantity");
    });

    it("carries machine-readable extension members such as code and retryAfterSeconds", () => {
      const result = toToolError({
        status: 429,
        contentType: "application/problem+json",
        body: {
          type: "https://jtoye.uk/errors/rate-limited",
          title: "Too Many Requests",
          status: 429,
          detail: "Rate limit exceeded.",
          code: "RATE_LIMITED",
          retryAfterSeconds: 7,
        },
      });

      expect(result.structuredContent).toMatchObject({ code: "RATE_LIMITED", retryAfterSeconds: 7 });
    });

    it("still never forwards a stack, a token or an over-long body (T-20-05)", () => {
      const result = toToolError({
        status: 500,
        contentType: "application/problem+json",
        body: {
          type: "about:blank",
          title: "Internal Server Error",
          status: 500,
          detail: "boom",
          stackTrace: "at com.jtoye.Foo.bar(Foo.java:12)",
          accessToken: "super-secret-token",
          trace: ["at x"],
          blob: "x".repeat(50_000),
        },
      });

      const everything = JSON.stringify(result);
      expect(everything).not.toContain("com.jtoye.Foo");
      expect(everything).not.toContain("super-secret-token");
      expect(everything.length).toBeLessThan(4_000);
    });

    it("a problem+json body with no extra members adds nothing after the summary line", () => {
      const result = toToolError({
        status: 404,
        contentType: "application/problem+json",
        body: { title: "Resource Not Found", detail: "x", status: 404 },
      });
      expect((result.content[0] as { text: string }).text).toBe("core 404 Resource Not Found: x");
    });
  });

  it("maps a bare 401 (non-problem) to a generic auth message with isError", () => {
    const result = toToolError({ status: 401, contentType: "text/plain", body: "Unauthorized" });

    expect(result.isError).toBe(true);
    expect((result.content[0] as { text: string }).text).toBe(
      "Unauthorized: token missing, expired, or wrong audience",
    );
  });

  it("maps a bare 500 to a generic 'Upstream core error' (no internals, no body echo)", () => {
    const leakyBody = { message: "NullPointerException at com.jtoye.Foo", token: "super-secret-token" };
    const result = toToolError({ status: 500, contentType: "text/plain", body: leakyBody });

    expect(result.isError).toBe(true);
    const text = (result.content[0] as { text: string }).text;
    expect(text).toBe("Upstream core error");
    expect(text).not.toContain("super-secret-token");
    expect(text).not.toContain("NullPointerException");
  });
});
