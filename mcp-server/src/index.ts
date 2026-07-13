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
  try {
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  } catch {
    // Sanitized 500 (T-20-05): never surface a stack, message or filesystem
    // path — in ANY NODE_ENV, not just production.
    if (!res.headersSent) {
      res.status(500).json({ error: "internal_error" });
    }
  }
});

// Terminal 4-arg error middleware (T-20-05): catches whatever falls through
// the routes — most trivially express.json() parse failures, which Express's
// DEFAULT handler would otherwise answer with an HTML page carrying the full
// stack trace (absolute node_modules paths) whenever NODE_ENV != production.
// Sanitization must live in code, not in an env var. The 4-arg signature is
// what marks this as an error handler; `_next` stays declared though unused.
app.use((err: unknown, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
  if (res.headersSent) return;
  const status = (err as { status?: number; statusCode?: number } | null)?.status ??
    (err as { statusCode?: number } | null)?.statusCode;
  const isClientError =
    err instanceof SyntaxError || (typeof status === "number" && status >= 400 && status < 500);
  if (isClientError) {
    res.status(400).json({ error: "bad_request" });
  } else {
    res.status(500).json({ error: "internal_error" });
  }
});

// Only bind a port when run as the entrypoint (not when imported by tests).
const isMain = process.argv[1] !== undefined && process.argv[1] === fileURLToPath(import.meta.url);
if (isMain) {
  app.listen(PORT, () => {
    // eslint-disable-next-line no-console
    console.log(`mcp-server listening on :${PORT}/mcp`);
  });
}
