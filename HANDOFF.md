# Handoff: Dashboard SSE Auth Fix + Dev Polish + Phase 17 Ready

> **NEW 2026-04-27 — Council Audit + Remediation Plan completed.**
>
> **READ THIS FIRST**: [`docs/audit/REMEDIATION-PLAN-2026-04-27.md`](docs/audit/REMEDIATION-PLAN-2026-04-27.md) — consolidated 12-week plan across 8 specialist + reviewer remediation pairs. Includes a **30-hour pre-prod path** (Wave 0) that closes 3 confirmed cross-tenant data leaks + Stripe webhook idempotency + production observability blackout, then waves 1-3+ for hardening and commercial GTM.
>
> **Source artifacts** (read in this order if onboarding cold):
> 1. [`docs/audit/REMEDIATION-PLAN-2026-04-27.md`](docs/audit/REMEDIATION-PLAN-2026-04-27.md) — what to do
> 2. [`docs/audit/COUNCIL-AUDIT-2026-04-27.md`](docs/audit/COUNCIL-AUDIT-2026-04-27.md) — why (10-agent council audit)
> 3. [`docs/audit/remediation/`](docs/audit/remediation/) — 8 pair docs with full code, SQL, tests ready to ship
> 4. [`docs/audit/sources/`](docs/audit/sources/) — 10 original audit findings (drill-down)
>
> **9 founder decisions block the plan** — see remediation plan §"Founder decisions blocking the plan". Highest-leverage three: (1) approve edge-go absorb? (2) where does prod K8s live? (3) founder personal runway + day-job status (drives raise vs bootstrap). Answer those three and the rest of the sequencing locks itself.
>
> **Pre-prod 30-hour path (Wave 0 — must close before any prod rollout >1 tenant or real payments)**:
> - Backend F1 SSE leak (1.5h) + F2 Stripe idempotency with TOCTOU-safe insert (3h)
> - Security F1 IDOR mandatory `verify` (2h)
> - Database F1 reviews policy + F2 FORCE RLS on 9 tables + F11 RlsContractTest (4h)
> - DevOps F1 prod actuator exposure flip + F3 MDC tenantId + F13 git rm backup file (~1.5 days)
>
> **Also see** — a separate advisory/strategic thread from 2026-04-21 is captured under `docs/planning/`:
> [`SESSION-HANDOFF-2026-04-21.md`](docs/planning/SESSION-HANDOFF-2026-04-21.md) (resume pointer),
> [`PLATFORM-STATE-AND-POSITIONING-2026-04-19.md`](docs/planning/PLATFORM-STATE-AND-POSITIONING-2026-04-19.md) (competitive analysis),
> [`PROGRESSION-PLAN-2026-04-21.md`](docs/planning/PROGRESSION-PLAN-2026-04-21.md) (four-wave roadmap).
> This document below remains the authoritative **coding** resume. Read the strategic thread when deciding *what* to build; read below for *how to resume Phase 17*.

---

## ⚠️ Council Audit (2026-04-27) — resume guidance

**State**: Audit complete and committed-pending. 10 specialist agents (7 technical, 3 commercial) reviewed full stack + market. Output at [`docs/audit/COUNCIL-AUDIT-2026-04-27.md`](docs/audit/COUNCIL-AUDIT-2026-04-27.md).

**Recommended next session — choose one of these paths**:

### Path A — Fix the pre-prod blockers (council recommendation)
Skip Phase 17 in favour of fixing the 5 confirmed data-integrity bugs. Total effort ~2 days of focused work. Run `/gsd-quick` per item or `/gsd-plan-phase` for a bundled "Pre-prod Hardening" phase:

1. **`OrderSseService` cross-tenant leak** — `core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java:17,29-40`. Capture `TenantContext.get()` at `subscribe()`, filter `broadcast()` by event tenant. Add `OrderSseServiceTenantIsolationTest` regression. Expected: two SSE subscriptions from different tenants → tenant A's transition NOT seen by tenant B.
2. **Customer-orders IDOR** — `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java:91-104`. Make `verify` mandatory; reject 400 without it. Expected: `curl '/public/orders?email=victim@example.com'` → 400 not 200.
3. **Stripe webhook idempotency** — `core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java:113-132`. Add `processed_stripe_events(event_id PRIMARY KEY)` table (V35 migration); guard with `INSERT ON CONFLICT DO NOTHING` at top of `handleWebhookEvent`. Expected: same `event.id` POSTed twice → exactly one `financial_transactions` row.
4. **`reviews_tenant_write` RLS** — `db/migration/V27__customer_reviews.sql:31-36`. New V35 migration: drop `app.tenant_id` reference (use `app.current_tenant_id`), drop the `customer_email` OR-clause, require `EXISTS (orders WHERE id=order_id AND customer_email=app.customer_email)`. Expected: spam-review attempt with arbitrary `tenant_id` → INSERT rejected.
5. **`FORCE ROW LEVEL SECURITY`** on `reviews`, `shop_promotions`, `shop_announcements`, all 6 `_aud` tables (V35 migration). Expected: `SELECT relforcerowsecurity FROM pg_class WHERE relname IN (…)` → all true.

### Path B — Continue Phase 17 (Vendor Order Detail + Stripe Refund)
The original handoff path. ⚠️ Note: the QA audit flagged that the `charge.refunded` webhook is currently in the "ignore" branch despite Phase 17 (PR #51) shipping vendor refunds — Phase 17 implementation MUST close this gap. Add `RefundWebhookHandlingIntegrationTest` as part of the implementation plan, not as follow-up.

### Path C — Commercial pivot (no code changes)
Per the commercial critic agent's recommendation: freeze feature development, spend 30 days door-knocking 30 ethnic-food vendors in Peckham/Brixton/Tottenham/East Ham/Croydon. Goal: 10 paying vendors at £49/mo in 90 days. Re-prioritize code work *only* against what those 10 customers need — likely the dashboard responsive rebuild + `--primary` design token rebrand from frontend audit's top-5 fixes.

**My recommendation if you ask**: Path A first (2 days), then Path C. Path B can run in parallel with Path C if there's contractual commitment — but Phase 17 polish before customer #1 is the trap the critic agent specifically flagged.

---

**Generated**: 2026-04-19 (session wrap-up)
**Branch**: `main` (clean — all in-session work merged)
**Main tip**: `0d6f863` `fix(dashboard): authenticate orders SSE stream + bind NextAuth to :3100 (#53)`
**Dev server**: running on http://localhost:3100 (started via `npm run dev` — dev script now cross-env wrapped)
**docker-compose**: unchanged this session — all services still up (postgres :5433, redis :6379, keycloak :8085, rabbitmq :5672/:15672/:61613, minio :9000/:9001, core-java :9090, edge-go :8089, observability stack). No backend rebuild needed for this session's work.

---

## Current goal

Phase 17 — Vendor Order Detail + Stripe Refund Flow (VOPS-01..03). Research landed in PR #51. Implementation still pending. Run `/gsd-plan-phase 17` on a fresh feature branch when ready.

---

## Completed (this session)

### 1. Root-caused and fixed the dashboard `401` on `/api/v1/orders/stream` (PR #53 → merged `0d6f863`)

- **Root cause, proven forensically**:
  - `/orders/stream` shipped in commit 879418a (2026-04-01) with the frontend calling `new EventSource(...)`. Browser `EventSource` cannot attach `Authorization: Bearer <jwt>` (W3C spec limitation).
  - `SecurityConfig` (line 69-72) never permitAll'd `/orders/stream`; it remained JWT-protected.
  - No codebase-side query-string bearer resolver (`grep BearerTokenResolver` → 0 matches).
  - No Next.js rewrite proxy for the endpoint.
  - `OrderSseServiceTest.java` is unit-only — no HTTP integration test ever exercised the endpoint with auth. The 401 was latent, not a regression.
- **Fix**: replaced raw `EventSource` with `@microsoft/fetch-event-source` which runs on `fetch()` and accepts auth headers. Reads `session.accessToken` from NextAuth the same way `api-client.ts` axios interceptor does.
- **Also in #53**: baked `NEXTAUTH_URL=http://localhost:3100` into the `dev` script so `npm run dev` emits the right OAuth callback. Previously `.env.local:11` had a stale `:3000` value (written before the dev port moved when MCP server claimed :3000), producing `chrome-error://chromewebdata/` after Keycloak sign-in.
- **Verified end-to-end**:
  - Fresh Playwright context across all 8 dashboard routes: 23/23 backend calls → 200. Zero 4xx.
  - `curl -H 'Authorization: Bearer <jwt>' /api/v1/orders/stream` → connection held (SSE streaming).
  - `curl` without token → `HTTP/1.1 401 Bearer` (auth still enforced).
  - `/dashboard/orders` renders all 20 rows (53 orders total).

### 2. Dev script hardening (this PR)

- Wrap dev env override with `cross-env` so the `NEXTAUTH_URL=… next dev` syntax works on Windows cmd.exe in addition to POSIX shells. Added `cross-env@^10.1.0` as `devDependency`.
- Set `SessionProvider refetchOnWindowFocus={false}` in `frontend/components/providers.tsx` to stop NextAuth re-polling `/api/auth/session` on tab focus, which was racing with in-flight `getSession()` calls from the axios interceptor and throwing spurious `ClientFetchError` into the console. The axios 401 handler still refreshes on real expiry; user-visible freshness unaffected.

### 3. Memory rule preserved (binding)

`feedback_design_direction.md` still indexed in MEMORY.md. Any future UI refresh must start with `/gsd-sketch` for explicit user approval. Do not autonomously ship visuals.

---

## Remaining work

### Immediate — Phase 17 implementation (VOPS-01..03)

Research is done. Next session should:

1. `git checkout -b feature/phase-17-implementation main`. Do NOT reuse the `feature/phase-17-vendor-order-detail-stripe-refund` name — it was deleted after PR #51 merged.
2. `/gsd-plan-phase 17` — turn the 5 research gate items into an implementation plan.
3. Build order (per PR #51 research):
   - Flyway V35 migration: `refunds` table (+FK to `orders`, amounts in pennies, `stripe_refund_id` nullable, `status` enum).
   - `Refund` entity + `RefundRepository` + `RefundService`.
   - `POST /api/v1/orders/{id}/refund` controller with `Idempotency-Key` header.
   - `OrderStateMachine` additions: `REFUND_REQUESTED` event transitioning `CONFIRMED|PREPARING|READY|COMPLETED → REFUNDED`; idempotent on second call.
   - Stripe webhook handler for `charge.refunded` / `refund.updated`.
   - RabbitMQ `order.refunded` event publishing.
   - Frontend: `/dashboard/orders/[id]` detail view + refund dialog (existing stack, not the reverted design).
   - E2E: vendor → `/dashboard/orders` → click row → click refund → confirm → Stripe test-mode success → UI updates to `REFUNDED`.

### Non-blocking cleanup candidates

- `.planning/research/DESIGN-SPEC.md` + `HANDOFF-DESIGN-OVERHAUL.md` remain on disk as historical artifacts. User did not ask to delete them. Keep as reference only; **do not re-propose the direction**.
- If the user ever wants the INFRASTRUCTURE pieces that died with the revert, cherry-pick onto a small non-visual PR — they are legitimate improvements the user did NOT object to, only the visuals:
  - ESLint 9 flat config (`eslint.config.mjs`) replacing the removed `next lint`
  - `types/jest-dom.d.ts` for matcher types (kills the 36 baseline `__tests__` tsc errors)
  - `metadataBase: new URL(process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3100")` in `app/layout.tsx` metadata
  - csp-headers.test / header-snapshot.test type cleanup (replace `any` with proper `HeaderRoute` alias)
- `next lint` is still broken at baseline until the ESLint flat config is reintroduced (Next.js 16 removed the command).
- `middleware` → `proxy` filename deprecation warning in `next build` — Next.js 16 rename, unrelated follow-up.
- `.env.local` still has `NEXTAUTH_URL=http://localhost:3000`. Harmless now because the `dev` script override takes precedence (npm-set env > .env file per Next.js docs). User explicitly declined to edit that file; left as-is.

### Not outstanding (done via parallel sessions)

- **Phase 14** — Stock race fix + `getSummary` DB aggregation (CQ-01 + CQ-02) — merged as PR #46.
- **Phase 15** — K8s NetworkPolicies + Sealed Secrets drafting (INF-01 + INF-02) — merged as PR #47.
- **Phase 16** — Go Edge OpenAPI + Swagger UI (DOC-01) — merged as PR #48.
- **CSP dev fix** — PR #50 dropped `upgrade-insecure-requests` in dev so MinIO HTTP images load.
- **Phase 17 research** — PR #51 defined the 5 design-gate items for Stripe refunds.
- **Design overhaul** — PR #49 merged then reverted via PR #52 per user feedback.

---

## Key decisions (this session)

| Decision | Rationale |
|---|---|
| Fix SSE with `@microsoft/fetch-event-source` rather than query-string token | Query strings leak into logs, proxies, and referers. Header-based auth matches the rest of `api-client.ts`. No Spring Security changes → zero backend blast radius. |
| Bake `NEXTAUTH_URL` into the `dev` script instead of editing `.env.local` | User explicitly refused to edit `.env.local`. `npm run dev` process env overrides `.env.local` per Next.js precedence. Version-controlled fix survives fresh clones. |
| Add `cross-env` wrapper | POSIX inline env var syntax breaks on Windows cmd.exe. Project deploys across dev/staging/test/prod; dev script must work everywhere. |
| `refetchOnWindowFocus={false}` on `SessionProvider` | Stops spurious `ClientFetchError` console spam. Real session expiry still handled by the axios 401 refresh path. |
| Leave `.env.local` untouched | User explicitly said "I'm not running no sed command." Rule respected. |

---

## Environment state

- **Repo**: `/home/sanmi/IdeaProjects/JToye_OaaS_2026` (primary; no worktrees)
- **Branch**: `main`
- **Tip**: `0d6f863 fix(dashboard): authenticate orders SSE stream + bind NextAuth to :3100 (#53)`
- **Open PRs**: `chore/dev-polish-and-handoff` (this PR — cross-env + SessionProvider polish + this HANDOFF update)
- **Local branches**: only `main` + the feature branch above
- **Dev port**: 3100 (MCP holds 3000)
- **Running services**: same as prior session — all docker-compose services healthy, dev server on :3100
- **Test baseline**: `Run Tests` CI job on PR #53 passed in 2m17s. Security scan + gitleaks passed.

---

## Resume instructions

### For the human / next session

1. **Verify state** (<30s):
   ```bash
   cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
   git status                      # expect clean
   git log --oneline -3            # tip should be SSE fix or later
   ss -ltn | grep 3100             # dev server listening
   curl -s http://localhost:3100/shop | grep -oE "from-orange|bg-slate-50" | head
   ```

2. **If starting Phase 17 implementation**:
   ```bash
   git checkout -b feature/phase-17-implementation
   find .planning -iname "*phase-17*" -o -iname "*refund*" | head
   /gsd-plan-phase 17
   ```

3. **If the SSE 401 reappears on `/dashboard/orders`**:
   - Check that the dev server was started with `npm run dev` (not raw `next dev`), so `NEXTAUTH_URL` is set correctly.
   - Clear `localhost:3100` site data in the browser — stale NextAuth cookies from the old `:3000` callback attempts will keep throwing session errors.
   - Confirm `frontend/app/dashboard/orders/page.tsx:8` still imports `fetchEventSource`.

4. **If the user asks for a UI refresh** — STOP and read `feedback_design_direction.md` memory. Do not autonomously ship visuals. Use `/gsd-sketch` for throwaway HTML variants, get explicit user sign-off, then move to production code.

### For a fresh Claude session

Paste this into the new session:

```
Resuming J'Toye OaaS work. Context:
- Main tip: 0d6f863 (PR #53 dashboard SSE auth fix merged).
- User rejected editorial/serif direction; see feedback_design_direction.md memory.
- Dev server runs via `npm run dev` (NEXTAUTH_URL is baked into the dev script via cross-env).
- Go-forward priority: Phase 17 (Stripe refund flow) — research shipped via PR #51, implementation pending.
- Read /home/sanmi/IdeaProjects/JToye_OaaS_2026/HANDOFF.md for full state.
- Do NOT autonomously ship any visual redesign. Sketch-first is mandatory.
```

---

## References

- **Binding design rule**: `feedback_design_direction.md` (memory) — reject editorial directions; sketch-first for bold moves.
- Project guide: `CLAUDE.md` (project root).
- Phase 17 research: `.planning/research/phase-17-*` (see PR #51 for exact file list).
- Memory index: `/home/sanmi/.claude/projects/-home-sanmi-IdeaProjects-JToye-OaaS-2026/memory/MEMORY.md`.
