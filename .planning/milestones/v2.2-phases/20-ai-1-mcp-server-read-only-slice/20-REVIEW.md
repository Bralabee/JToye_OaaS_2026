---
phase: 20-ai-1-mcp-server-read-only-slice
reviewed: 2026-07-13T11:59:29Z
depth: standard
files_reviewed: 22
files_reviewed_list:
  - core-java/src/main/java/uk/jtoye/core/dev/DemoDataSeeder.java
  - docs/metrics.json
  - mcp-server/.dockerignore
  - mcp-server/.gitignore
  - mcp-server/package.json
  - mcp-server/README.md
  - mcp-server/scripts/e2e-rls.sh
  - mcp-server/scripts/e2e.sh
  - mcp-server/src/core-client.ts
  - mcp-server/src/errors.ts
  - mcp-server/src/index.ts
  - mcp-server/src/server.ts
  - mcp-server/src/tools/list-products.ts
  - mcp-server/src/tools/list-shops.test.ts
  - mcp-server/src/tools/list-shops.ts
  - mcp-server/src/tools/read-orders.test.ts
  - mcp-server/src/tools/read-orders.ts
  - mcp-server/tsconfig.json
  - mcp-server/vitest.config.ts
  - scripts/docs-freshness.sh
  - mcp-server/Dockerfile
  - docker-compose.full-stack.yml
findings:
  critical: 0
  warning: 7
  info: 8
  total: 15
status: issues_found
---

# Phase 20: Code Review Report

**Reviewed:** 2026-07-13T11:59:29Z
**Depth:** standard
**Files Reviewed:** 22
**Status:** issues_found

Note: the workflow config listed `mcp-server/src/tools/list-shows.ts` (typo); the actual file reviewed is `mcp-server/src/tools/list-shops.ts`. Cross-referenced but out-of-scope files consulted: `mcp-server/src/tools/list-products.test.ts`, `mcp-server/src/core-client.test.ts`, `mcp-server/src/errors.test.ts`, `mcp-server/src/index.test.ts`, `core-java/.../SecurityConfig.java`, `OrderController.java`, `ProductController.java`, `ShopController.java`, `WebConfig.java`, `V13__seed_default_tenants.sql`, `infra/keycloak/realm-export.template.json`, `.github/workflows/ci-cd.yaml`, `.github/workflows/docs-freshness.yml`, `mcp-server/package-lock.json`.

## Narrative Findings (AI reviewer)

## Summary

Reviewed the Phase 20 read-only MCP server slice: the Node/TypeScript MCP tier (`mcp-server/`), its Docker/compose wiring, the two live E2E shell proofs, the tenant-B seeder addition, and the docs-freshness metrics extension. Verification was hands-on: I ran the vitest suite (23/23 pass), `tsc --noEmit` (clean), the docs-freshness gate (passes, 1231 = 872+231+77+28+23), inspected `package-lock.json` (222 packages, all `registry.npmjs.org`, all integrity-pinned), and empirically exercised the built server against three edge cases (missing `Accept` header, malformed JSON in dev and prod modes, dot-segment path inputs).

**Security posture verification against the phase threat model:**
- **Read-only surface:** exactly 3 `registerTool` calls (`server.ts:21-23`), all delegating to `coreGet` which is hardcoded `method: "GET"` (`core-client.ts:33`). No mutating call exists. PASS.
- **T-20-01 (no token/body logging):** all three tool handlers log only `{ tool, status }`; the PII-log test (`read-orders.test.ts:115-140`) proves it. `console.log` at `index.ts:49` prints only the port. PASS.
- **T-20-04 (SSRF guard):** `CORE_BASE_URL` is env-fixed and never caller-composed. PASS on host control — but the path allow-list is not airtight against dot-segments (WR-02).
- **T-20-05 (sanitized errors):** `errors.ts` + the catch blocks in each handler are sound and test-proven — but the Express layer beneath them leaks stack traces in non-production mode (WR-03).
- **Supply chain:** 4 runtime deps match the approved list; 3 devDependencies fall outside it (WR-07).
- **Cross-tenant proof fixtures:** tenant B (`…0002`) is genuinely seeded by V13 and `tenant-b-user` exists in the realm template with the matching `tenant_id` attribute — the e2e-rls.sh assertions rest on real fixtures.

No Critical findings. Seven Warnings, mostly at the seams: the CI gate counts these tests but never runs them, the path template guard admits `..` segments, error sanitization depends on an env var rather than code, and the README makes three claims the code does not honor.

## Warnings

### WR-01: CI never runs the mcp-server test suite or type-check — the 23 counted tests are unverified in CI

**File:** `docs/metrics.json:12-14`, `scripts/docs-freshness.sh:62-67` (root cause lives in `.github/workflows/ci-cd.yaml`, out of scope)
**Issue:** This phase adds `mcp_test_blocks: 23` to the "logical invocations passing" project standard and wires the count into the docs-freshness gate — but no CI workflow executes `vitest` or `tsc` for `mcp-server/`. `ci-cd.yaml` has jobs for core-java (Gradle), edge-go (go test), and frontend (build + Jest); there is no `working-directory: mcp-server` step anywhere. The docs-freshness gate only *counts* `it/test(` occurrences via grep; it never runs them. A TypeScript compile error or test regression in `mcp-server/` merges green. This repeats the exact gap class recorded in project memory for #87/PR #130 ("jest doesn't type-check") — vitest doesn't either, and here even vitest never runs. The suite passes locally today (verified: 23/23, tsc clean), so the standard is *currently* true but *unenforced*.
**Fix:** Add an mcp-server step to the `test` job in `.github/workflows/ci-cd.yaml`:
```yaml
      - name: Install mcp-server dependencies
        run: npm ci
        working-directory: mcp-server
      - name: Type-check mcp-server (tsc)
        run: npx tsc --noEmit
        working-directory: mcp-server
      - name: Run mcp-server vitest suite
        run: npm test
        working-directory: mcp-server
```

### WR-02: `read_orders` path template admits dot-segment escape — allow-list (T-20-04) is not airtight

**File:** `mcp-server/src/tools/read-orders.ts:42-54` (schema at 27-32)
**Issue:** `orderId`/`shopId` are `z.string()` with no format constraint. `encodeURIComponent` does NOT encode `.`, so a caller-supplied `".."` survives as a dot-segment, and the WHATWG URL parser inside `fetch` normalizes it before the request leaves the process. Empirically verified against the built code:
- `orderId: ".."` → built path `/api/v1/orders/../detail` → actual request `GET /api/v1/detail`
- `shopId: ".."` → built path `/api/v1/orders/shop/..` → actual request `GET /api/v1/orders/`

The blast radius is contained today — `/` stays encoded (`%2F`) so at most one segment collapses, the method is always GET, the same caller Bearer is forwarded, and the reachable endpoints (`/api/v1/detail` → 404, `/api/v1/orders/` → the tenant order list the tool already exposes) grant nothing new. But the caller demonstrably changes *which core endpoint is hit*, which directly contradicts the documented invariant "tool paths from allow-listed templates only" (file header, lines 11-14) and silently converts a shop-scoped read into a full tenant order list. Both parameters are UUIDs in core (`OrderController` uses `@PathVariable UUID`), so strict validation is free.
**Fix:**
```typescript
export const readOrdersInputSchema = {
  // ...
  shopId: z.string().uuid().optional().describe("scope the order list to one shop"),
  orderId: z.string().uuid().optional().describe("fetch a single order's detail"),
};
```

### WR-03: Error sanitization (T-20-05) depends on `NODE_ENV`, not code — dev mode leaks full stack traces over HTTP

**File:** `mcp-server/src/index.ts:25-42` (no error middleware anywhere in the app)
**Issue:** There is no try/catch around `server.connect`/`transport.handleRequest` and no Express error-handling middleware. Any error thrown before or inside the transport (most trivially: malformed JSON rejected by `express.json()`) falls through to Express's *default* error handler. Empirically verified: `POST /mcp` with body `{not-json` returns a 400 **HTML page containing the full stack trace including absolute filesystem paths** (`/home/.../node_modules/body-parser/...`) when `NODE_ENV` is not `production`. The Dockerfile sets `NODE_ENV=production` (verified: stack suppressed, bare "Bad Request"), so the shipped container is safe — but the sanitization invariant is enforced by one env var, not by the code. `npm run dev` (tsx, no NODE_ENV) or any non-container `npm start` violates T-20-05 on the wire. The error response is also HTML, not JSON-RPC, which breaks MCP clients on this path.
**Fix:** Wrap the `/mcp` handler and add a terminal sanitized error middleware:
```typescript
app.post("/mcp", async (req, res) => {
  // ...existing body...
  try {
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  } catch {
    if (!res.headersSent) {
      res.status(500).json({
        jsonrpc: "2.0",
        error: { code: -32603, message: "Internal server error" },
        id: null,
      });
    }
  }
});
// after all routes — catches express.json() parse errors too:
app.use((_err: unknown, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
  if (!res.headersSent) res.status(400).json({ error: "bad_request" });
});
```

### WR-04: `read_orders` hands customer PII to any authenticated token — including `catalog:read`-only machine credentials (orders:read unenforced)

**File:** `mcp-server/src/tools/read-orders.ts:83-95`, `mcp-server/README.md:37` (core gap: `core-java/.../security/SecurityConfig.java:154`)
**Issue:** Core's authorization for `GET /api/v1/orders*` is `anyRequest().authenticated()` — the only scope gates in core are `SCOPE_catalog:write` on product mutations (verified in `ProductController`; no `@PreAuthorize` exists on `OrderController`). The README's scope column honestly marks read_orders "(`orders:read` reserved)", but the practical consequence deserves flagging: the reference `integration-catalog-ro` credential — marketed by this same README as a narrow catalog reader and the phase's blast-radius exemplar — can call `read_orders` and receive `customerName`/`customerEmail`/`customerPhone` for every order in its tenant. This phase is what makes that reach *operational*: it ships the agent-facing tool and the credential recipe side by side. Data stays tenant-scoped (RLS), so this is a least-privilege/PII-exposure gap, not an isolation break.
**Fix:** Either enforce `orders:read` in core before agents adopt this tool (`@PreAuthorize("hasAuthority('SCOPE_orders:read')")` on OrderController GETs, plus realm scope), or add an explicit warning in README §2/§4 that `integration-catalog-ro` can currently read order PII and should not be handed to catalog-only integrations until `orders:read` is enforced. Track as a follow-up issue if core changes are out of this slice.

### WR-05: README §5 verification example returns 406, not the documented 200

**File:** `mcp-server/README.md:98-102`
**Issue:** The "Read is allowed" curl example sends only `Content-Type: application/json`. The Streamable HTTP transport hard-requires `Accept: application/json, text/event-stream` on POST and rejects otherwise. Empirically verified against the built server: the exact documented request returns **406** `{"jsonrpc":"2.0","error":{"code":-32000,"message":"Not Acceptable: Client must accept both application/json and text/event-stream"}...}` — never 200. Anyone following the README's own verification recipe concludes the server is broken. Both e2e scripts get this right (`-H 'Accept: application/json, text/event-stream'`); the README diverges from them.
**Fix:** Add the Accept header to the example:
```bash
curl -s -o /dev/null -w '%{http_code}\n' "$MCP/mcp" \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_products","arguments":{}}}'
```

### WR-06: README documents a `list_products` search capability that does not exist — unknown args are silently swallowed

**File:** `mcp-server/README.md:35` vs `mcp-server/src/tools/list-products.ts:20-23`
**Issue:** The §2 tool table states: "Optional search → `GET /api/v1/products/search?q=`." The tool's input schema accepts only `page`/`size`; there is no search argument and no code path to `/products/search` (the core endpoint does exist — `ProductController.java:81`). Because the SDK builds a non-strict `z.object` from the raw shape, an agent that follows the README and passes `{"search": "jollof"}` gets the argument *silently stripped* and receives the full unfiltered catalogue with no error — silent wrong behavior, the worst failure mode for an agent consumer.
**Fix:** Either implement it (add `search: z.string().max(200).optional()` and route to `/api/v1/products/search?q=${encodeURIComponent(args.search)}` — still an allow-listed template), or delete the claim from the README table. If keeping the schema as-is, consider `z.strictObject` semantics so unknown arguments fail loudly instead of silently.

### WR-07: Three devDependencies outside the approved six-package supply-chain list

**File:** `mcp-server/package.json:20-26`
**Issue:** The phase's approved dependency list is exactly: `@modelcontextprotocol/sdk`, `zod`, `express`, `vitest`, `tsx`, `pino`. The manifest additionally declares `typescript`, `@types/node`, and `@types/express`. Mitigating facts verified: all three are devDependencies excluded from the runtime image (`npm ci --omit=dev` in the runner stage), the lockfile is fully integrity-pinned with zero non-npmjs resolutions, and `typescript` is functionally required by the `build: tsc` script. This is a deviation from the stated supply-chain constraint, not a live vulnerability — but the constraint says any extra dependency must be flagged for explicit approval.
**Fix:** Get the three build-toolchain packages explicitly added to the approved list in the phase record (recommended — they are load-bearing), or drop `typescript`/`@types/*` and build via `tsx`-bundled esbuild instead (not recommended; loses type-checking at build time).

## Info

### IN-01: `e2e.sh` FAILURES counter is dead code — the failure summary branch is unreachable

**File:** `mcp-server/scripts/e2e.sh:40,63,139-147`
**Issue:** Unlike its sibling `e2e-rls.sh` (whose `fail()` increments `FAILURES`), `e2e.sh`'s `fail()` only echoes. Every failure path `exit 1`s explicitly, so `FAILURES` is always 0 and the `else` branch at line 144-146 can never execute. Harmless today, but a future non-exiting `fail` call would be silently swallowed by the always-green summary.
**Fix:** Copy the incrementing `fail()` from `e2e-rls.sh:58` for consistency, or delete the counter and summary conditional.

### IN-02: `|| echo 000` doubles curl's own `000` write-out on network failure

**File:** `mcp-server/scripts/e2e.sh:80-85`, `mcp-server/scripts/e2e-rls.sh:112-115,122-127`
**Issue:** On connection failure, `curl -w '%{http_code}'` already emits `000` before exiting non-zero; the `|| echo 000` then appends a second `000`, so the status variable can hold `000\n000`. All comparisons are `!= "200"` so behavior is correct, but failure log lines print a mangled status (`HTTP 000 000`).
**Fix:** Capture curl's exit separately, e.g. `STATUS="$(curl ... -w '%{http_code}')" || STATUS=000`.

### IN-03: `read_orders` silently drops `page`/`size` in shopId mode though core supports pagination there

**File:** `mcp-server/src/tools/read-orders.ts:42-54`
**Issue:** `buildPath` ignores `page`/`size` when `shopId` is set, but core's `GET /orders/shop/{shopId}` takes a `Pageable`. The schema describes the params as "(list mode)" so it is documented, yet an agent passing `{shopId, page: 2}` gets page 0 with no signal. Core also supports `GET /orders?shopId=` (post-#179), which would let one template carry both.
**Fix:** Append the same `qs` logic to the shop path, or route shop-scoping through `/api/v1/orders?shopId=&page=&size=`.

### IN-04: Health checks hardcode port 9100 while the server honors `MCP_PORT`

**File:** `mcp-server/Dockerfile:45-46`, `docker-compose.full-stack.yml:367`, `mcp-server/src/index.ts:15`
**Issue:** `index.ts` binds `MCP_PORT` (default 9100), but both HEALTHCHECKs probe `localhost:9100` literally. Overriding `MCP_PORT` yields a running-but-unhealthy container that `restart: unless-stopped` will not fix. Also `Number(garbage)` → `NaN` → `app.listen(NaN)` throws at startup (acceptable fail-fast, but unvalidated).
**Fix:** Drop the env override (document 9100 as fixed) or use `process.env.MCP_PORT || 9100` inside the healthcheck expression.

### IN-05: Non-Bearer Authorization schemes are forwarded double-wrapped

**File:** `mcp-server/src/index.ts:26`
**Issue:** `replace(/^Bearer\s+/i, "")` only strips a well-formed prefix; `Authorization: Basic dXNlcjpwYXNz` passes the non-empty check and is forwarded as `Bearer Basic dXNlcjpwYXNz`. Core rejects it (401), so no security impact — but the fail-fast contract ("missing token fails at the MCP host") doesn't cover malformed schemes.
**Fix:** Match instead of replace: `const m = /^Bearer\s+(\S+)$/i.exec(req.headers.authorization ?? ""); if (!m) { 401 }`.

### IN-06: Three duplicated pino instances — the T-20-01 logging posture has no single enforcement point

**File:** `mcp-server/src/tools/list-products.ts:17`, `list-shops.ts:16`, `read-orders.ts:24`
**Issue:** Each tool module constructs its own `pino({ name: "jtoye-mcp" })`. The never-log-the-body rule is re-implemented per file by convention; a fourth tool could silently diverge, and only `read-orders.test.ts` asserts the invariant.
**Fix:** Export a shared logger (and ideally a `logToolCall(tool, status)` helper that structurally cannot receive a body) from a `src/logger.ts`.

### IN-07: README scope column overstates enforcement — `catalog:read` is not core-enforced on product reads

**File:** `mcp-server/README.md:33-38`
**Issue:** The table column is headed "Scope (Core-enforced)" and lists `catalog:read` for `list_products`, but core's only scope gates are `SCOPE_catalog:write` on mutations; `GET /products` is authenticated-only (core's own `OpenApiConfig.java:59` says "read surface, authenticated-only"). Any valid tenant token without `catalog:read` still gets 200.
**Fix:** Change the cell to "authenticated (`catalog:read` advertised)" to match core's documented model.

### IN-08: `mcp-server` compose dependency on Keycloak is unnecessary

**File:** `docker-compose.full-stack.yml:364-365`
**Issue:** The MCP container never contacts Keycloak (no issuer/JWKS env by design — it forwards opaque tokens). `depends_on: keycloak: service_healthy` adds startup coupling that the header comment explicitly disclaims. Transitively implied via core-java anyway.
**Fix:** Remove the keycloak entry from `mcp-server.depends_on`.

---

**Clean areas worth recording (verified, not assumed):**
- `DemoDataSeeder` tenant-B addition is sound: tenant `…0002` exists in `V13__seed_default_tenants.sql:6`; `tenant-b-user` exists in the realm template with `tenant_id = …0002`; the probe writes run in their own transaction under a separately-pinned `TenantContext`; idempotency keys (slug `tenant-b-probe`, SKU `TENANTB-PROBE-1`) collide with nothing under the global `idx_shops_slug` and tenant-scoped `idx_products_tenant_sku` uniques; the tenant-A quarantine sweep cannot touch the probe row (RLS-scoped `findAll`); the probe shop is unpublished, respecting WR-10's spirit.
- All five core endpoints the tools target exist under the `/api/v1` prefix (`WebConfig.API_V1_PREFIX`) with matching pagination semantics; the zod `size` cap of 100 mirrors core's global pageable maximum.
- `docs/metrics.json` is exact: gate passes, arithmetic checks (872+231+77+28+23 = 1231), and `docs-freshness.yml` has no path filter so drift is always caught. `CLAUDE.md` was correctly updated to 1231 in this branch.
- `package-lock.json` is committed (so `npm ci` in the Dockerfile works), fully integrity-pinned, npmjs-only.
- The empty `catch {}` blocks in the tool handlers are deliberate, commented, and each emits a sanitized `logger.warn` — not silent swallowing.
- Neither e2e script echoes a token or response body; token/PII material only ever lands in `mktemp -d` files removed by the EXIT trap.

---

_Reviewed: 2026-07-13T11:59:29Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
