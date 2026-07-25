# Phase 25: Mutating MCP Tools - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-07-24
**Phase:** 25-mutating-mcp-tools
**Areas discussed:** Scope enforcement, Idempotency-Key origin, Write-tool set, Write credential + realm

---

## Scope enforcement

| Option | Description | Selected |
|--------|-------------|----------|
| Enforce now (mirror #206) | `@PreAuthorize` write scopes on the core create endpoints, exactly mirroring the shipped `catalog:write` gate; operators default-grant them | ✓ |
| Leave authenticated-only | No core gate; tool nominally requires a write-scoped credential (aspirational least-privilege) | |
| You decide | — | |

**User's choice:** Enforce now (mirror #206)
**Notes:** Satisfies AC-1 ("mapped to the appropriate write scopes") + the standing AI agent-readiness contract (scoped/least-privilege creds).

### Customer-scope granularity (sub-question)

| Option | Description | Selected |
|--------|-------------|----------|
| New `customers:write` | Introduce a dedicated scope, completing the `catalog:*`/`orders:*` taxonomy symmetrically | ✓ (Claude, from "you decide") |
| Reuse `orders:write` | Map both create tools to one scope; coarser | |
| You decide | Choose the granularity | ✓ |

**User's choice:** You decide → resolved to **new `customers:write`**
**Notes:** Finest-grained least-privilege — an agent granted only order-creation can't mint customers.

---

## Idempotency-Key origin

| Option | Description | Selected |
|--------|-------------|----------|
| Required agent-supplied arg | REQUIRED `idempotencyKey` tool input (1..64), always forwarded as header; reuse-on-retry; no non-idempotent path | ✓ |
| Optional agent-supplied arg | Mirror core's optional header; omission → duplicate on retry (footgun) | |
| Body-hash derived | MCP derives key from hash(tool+body); auto-dedup but can't distinguish retry from repeat | |

**User's choice:** Required agent-supplied arg
**Notes:** Makes AC-1's "replayed call returns the original result, not a duplicate" a structural property of the tool. No core change — only the tool layer makes the key mandatory.

---

## Write-tool set

| Option | Description | Selected |
|--------|-------------|----------|
| Two create tools only | `create_order` + `create_customer` only; state transitions deferred | ✓ (Claude, from "you decide") |
| Creates + order transitions | Also expose `confirm_order`/`cancel_order`/`mark_order_ready`; widens surface | |
| You decide | Pick the breadth honouring the boundary | ✓ |

**User's choice:** You decide → resolved to **two create tools only**
**Notes:** Matches AC-1's named examples + the 25-01/25-02 plan breakdown; keeps the boundary tight. Naming snake_case to match the read slice (`read_orders`). State transitions recorded as deferred.

---

## Write credential + realm

| Option | Description | Selected |
|--------|-------------|----------|
| New reference RW client | Ship `integration-orders-rw` in the realm template, exact mirror of the RO client's wiring | ✓ |
| Extend catalog-ro client | Add write scopes to the existing RO client; destroys its read-only guarantee | |
| Document-only | No committed template client; manual operator step | |

**User's choice:** New reference RW client
**Notes:** Committed + reproducible; sidesteps the KC24 managed-attribute trap (template import isn't subject to the strip).

### Credential scope bundle (sub-question)

| Option | Description | Selected |
|--------|-------------|----------|
| Write-only (strict) | `orders:write` + `customers:write` only; prerequisites seeded separately | |
| Write + read (realistic) | Also `catalog:read` so one credential can discover→create→read-back | ✓ (Claude, from "you decide") |
| You decide | Balance least-privilege vs self-contained E2E | ✓ |

**User's choice:** You decide → resolved to **write + `catalog:read`**
**Notes:** `create_order` needs a valid `shop_id` + product refs; self-sufficient credential makes E2E reproducible. Blast radius bounded — pointedly NO `catalog:write`; RLS walls cross-tenant.

---

## Claude's Discretion

- Customer-scope granularity → new `customers:write` scope (D-02).
- Write-tool breadth → two create tools only (D-07).
- RW credential scope bundle → `orders:write` + `customers:write` + `catalog:read` (D-10).
- Success-return shape, `corePost` error/timeout handling, `docs/metrics.json` MCP-vitest reconcile → mechanical, following the read-slice posture.

## Deferred Ideas

- Order state-transition MCP tools (`confirm_order`/`cancel_order`/`mark_order_ready`).
- Enforcing reserved `orders:read` (+ `customers:read`) on read endpoints.
- Programmatic per-tenant/per-agent RW client provisioning (needs KC24 managed-attribute change + #102 `KeycloakAdminClient` seam).
- `product.create` / `catalog:write` MCP tool (excluded to bound the RW credential's blast radius).
- MCP resource/prompt primitives beyond tools.
