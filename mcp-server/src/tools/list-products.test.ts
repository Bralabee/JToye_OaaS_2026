import { beforeEach, describe, expect, it, vi } from "vitest";

// Mock the core forwarder; toToolError (errors.js) runs for real so the
// delegation path is exercised end-to-end.
vi.mock("../core-client.js", () => ({ coreGet: vi.fn() }));

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { coreGet } from "../core-client.js";
import { listProductsHandler, registerListProducts } from "./list-products.js";

const SHOP = "7f000001-0000-4000-8000-000000000002";
const ok200 = { ok: true as const, status: 200, contentType: "application/json", body: { content: [] } };

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

  it("UXT-040: forwards shopId and availableOnly through the allow-listed query string", async () => {
    vi.mocked(coreGet).mockResolvedValue(ok200);

    await listProductsHandler("tok")({ shopId: SHOP, availableOnly: true, page: 0 });

    expect(coreGet).toHaveBeenCalledWith(`/api/v1/products?page=0&shopId=${SHOP}&availableOnly=true`, "tok");
  });
});

/**
 * Phase 37-42 (RWO-040 / UXT-040): the REGISTERED tool, through a real in-memory MCP round trip, so the
 * SDK's own Zod parse runs. That parse is what dropped shopId before: the raw shape had no such key, so
 * an agent's "this shop" was stripped silently and it got the tenant's whole catalogue.
 */
describe("list_products registered tool (in-memory MCP round trip)", () => {
  async function connect() {
    const server = new McpServer({ name: "t", version: "0" });
    registerListProducts(server, "tok");
    const client = new Client({ name: "c", version: "0" });
    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
    await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
    return { server, client };
  }

  beforeEach(() => {
    vi.mocked(coreGet).mockReset();
  });

  it("UXT-040: an agent's shopId and availableOnly survive the schema and reach core", async () => {
    const { client, server } = await connect();
    vi.mocked(coreGet).mockResolvedValue(ok200);

    const result = await client.callTool({
      name: "list_products",
      arguments: { shopId: SHOP, availableOnly: true },
    });

    expect(result.isError).toBeFalsy();
    expect(coreGet).toHaveBeenCalledWith(`/api/v1/products?shopId=${SHOP}&availableOnly=true`, "tok");
    await client.close();
    await server.close();
  });

  it("T-37-99: a shopId that is not a UUID is refused by the schema and core is never called", async () => {
    const { client, server } = await connect();
    vi.mocked(coreGet).mockResolvedValue(ok200);

    const result = await client.callTool({
      name: "list_products",
      arguments: { shopId: "x&size=100000&shopId=../admin" },
    });

    expect(result.isError).toBe(true);
    expect((result.content as { text: string }[])[0]!.text).toContain("Invalid arguments for tool list_products");
    expect(coreGet).not.toHaveBeenCalled();
    await client.close();
    await server.close();
  });

  it("T-37-99: availableOnly must be a boolean, not a string an agent might pass through", async () => {
    const { client, server } = await connect();

    const result = await client.callTool({
      name: "list_products",
      arguments: { availableOnly: "true&tenant=other" },
    });

    expect(result.isError).toBe(true);
    expect(coreGet).not.toHaveBeenCalled();
    await client.close();
    await server.close();
  });

  it("T-37-99: unknown keys never reach the query string - only the allow-listed four", async () => {
    const { client, server } = await connect();
    vi.mocked(coreGet).mockResolvedValue(ok200);

    await client.callTool({
      name: "list_products",
      arguments: { shopId: SHOP, tenantId: "11111111-1111-4111-8111-111111111111", sort: "price", q: "x" },
    });

    expect(coreGet).toHaveBeenCalledTimes(1);
    expect(coreGet).toHaveBeenCalledWith(`/api/v1/products?shopId=${SHOP}`, "tok");
    await client.close();
    await server.close();
  });

  it("the published schema and description tell an agent about the shop filter and named allergens", async () => {
    const { client, server } = await connect();
    const { tools } = await client.listTools();
    const tool = tools.find((t) => t.name === "list_products")!;
    const props = tool.inputSchema.properties as Record<string, { type?: string; format?: string }>;

    expect(Object.keys(props)).toEqual(expect.arrayContaining(["page", "size", "shopId", "availableOnly"]));
    expect(props.availableOnly!.type).toBe("boolean");
    expect(tool.inputSchema.required ?? []).not.toContain("shopId");
    expect(tool.description).toContain("shopId");
    expect(tool.description).toContain("allergens");
    await client.close();
    await server.close();
  });
});
