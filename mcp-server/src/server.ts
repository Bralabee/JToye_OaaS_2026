import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerListProducts } from "./tools/list-products.js";
import { registerListShops } from "./tools/list-shops.js";
import { registerReadOrders } from "./tools/read-orders.js";
import { registerCreateOrder } from "./tools/create-order.js";
import { registerCreateCustomer } from "./tools/create-customer.js";

/**
 * server.ts — McpServer factory.
 *
 * `buildServer(bearer)` is called ONCE PER REQUEST by the stateless transport
 * (index.ts), so each tool handler closes over exactly the caller's token. The
 * MCP tier holds no tenant state and makes no auth decisions — it forwards.
 * The three read-only tools (list_products, list_shops, read_orders) share this
 * skeleton as thin coreGet forwarders; the two write tools (create_order,
 * create_customer — Phase 25 [AI-02]) mirror it as thin corePost forwarders,
 * each mandating the Idempotency-Key so a replay never mints a duplicate.
 */
export function buildServer(bearer: string): McpServer {
  const server = new McpServer(
    { name: "jtoye-mcp", version: "0.1.0" },
    { capabilities: { logging: {} } },
  );

  registerListProducts(server, bearer);
  registerListShops(server, bearer);
  registerReadOrders(server, bearer);
  registerCreateOrder(server, bearer);
  registerCreateCustomer(server, bearer);

  return server;
}
