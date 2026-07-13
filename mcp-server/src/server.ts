import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerListProducts } from "./tools/list-products.js";
import { registerListShops } from "./tools/list-shops.js";

/**
 * server.ts — McpServer factory.
 *
 * `buildServer(bearer)` is called ONCE PER REQUEST by the stateless transport
 * (index.ts), so each tool handler closes over exactly the caller's token. The
 * MCP tier holds no tenant state and makes no auth decisions — it forwards.
 * The three read-only tools (list_products, list_shops, read_orders) all share
 * this skeleton — each a thin coreGet forwarder.
 */
export function buildServer(bearer: string): McpServer {
  const server = new McpServer(
    { name: "jtoye-mcp", version: "0.1.0" },
    { capabilities: { logging: {} } },
  );

  registerListProducts(server, bearer);
  registerListShops(server, bearer);

  return server;
}
