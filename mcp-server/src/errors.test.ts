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
