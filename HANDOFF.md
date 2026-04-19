# Handoff: Dashboard SSE Auth Fix + Dev Polish + Phase 17 Ready

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
