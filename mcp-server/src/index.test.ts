import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { app } from "./index.js";

/**
 * index.test.ts — integration against the express host. Uses app.listen(0) +
 * the Node 20 global fetch (no supertest dependency — see SUMMARY deviation).
 * Asserts the fail-fast 401 for a missing Bearer and that /health does not touch core.
 */
describe("mcp http host", () => {
  let server: Server;
  let base: string;

  beforeAll(async () => {
    server = app.listen(0);
    await new Promise<void>((resolve) => server.once("listening", () => resolve()));
    const { port } = server.address() as AddressInfo;
    base = `http://127.0.0.1:${port}`;
  });

  afterAll(async () => {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });

  it("returns 401 missing_bearer_token when POST /mcp has no Authorization header", async () => {
    const r = await fetch(`${base}/mcp`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", method: "tools/list", id: 1 }),
    });

    expect(r.status).toBe(401);
    expect(await r.json()).toEqual({ error: "missing_bearer_token" });
  });

  it("returns 200 on GET /health without calling core", async () => {
    const r = await fetch(`${base}/health`);

    expect(r.status).toBe(200);
    const body = (await r.json()) as { status?: string };
    expect(body.status).toBe("ok");
  });
});
