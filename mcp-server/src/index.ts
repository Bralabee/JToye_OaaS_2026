import { fileURLToPath } from "node:url";
import express from "express";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { buildServer } from "./server.js";

/**
 * index.ts — stateless Streamable HTTP host.
 *
 * One transport + one McpServer are built PER REQUEST (sessionIdGenerator:
 * undefined), so a single container serves any number of tenants concurrently —
 * each request's tenant is decided entirely by its own Bearer. The MCP server
 * holds no secret and no session state. GET /health does NOT call core, so the
 * container stays healthy even when core is briefly down.
 */
const PORT = Number(process.env.MCP_PORT ?? 9100);

export const app = express();
app.use(express.json());

// Trivial liveness probe for the Docker HEALTHCHECK; never touches core.
app.get("/health", (_req, res) => {
  res.status(200).json({ status: "ok" });
});

app.post("/mcp", async (req, res) => {
  const bearer = (req.headers.authorization ?? "").replace(/^Bearer\s+/i, "");
  if (!bearer) {
    // Fail fast; core is still the real validator for present-but-invalid tokens.
    res.status(401).json({ error: "missing_bearer_token" });
    return;
  }

  // Stateless: fresh transport + server per request; tools close over this tenant's token.
  const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
  const server = buildServer(bearer);
  res.on("close", () => {
    transport.close();
    server.close();
  });
  await server.connect(transport);
  await transport.handleRequest(req, res, req.body);
});

// Only bind a port when run as the entrypoint (not when imported by tests).
const isMain = process.argv[1] !== undefined && process.argv[1] === fileURLToPath(import.meta.url);
if (isMain) {
  app.listen(PORT, () => {
    // eslint-disable-next-line no-console
    console.log(`mcp-server listening on :${PORT}/mcp`);
  });
}
