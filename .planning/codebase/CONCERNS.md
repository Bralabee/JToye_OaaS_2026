# Codebase Concerns

**Analysis Date:** 2026-09-03

**Scope:** Full repo, refreshed from the 2026-04-18 audit. Current branch `feature/qa-remediate-20260902` (HEAD `0eed4f66`), 69 commits ahead of `main` and **not yet opened as a PR** (`gh pr list --head feature/qa-remediate-20260902` → empty). This matters: a large share of the concerns below are **fixed on this branch but still live on `main`** until it merges — each such item is marked explicitly.

**Method:** Every claim below is evidenced against the live tree (`file:line`), an open GitHub issue, or a dated planning/QA-council artifact. Every claim in the 2026-04-18 CONCERNS.md was re-checked against current source; results are in the RESOLVED section. Unverified worries are excluded rather than restated.

---

## RESOLVED since the 2026-04-18 audit

All 14 items marked "P1/P2 Tech Debt" plus most of the "Deferred/Out-of-scope" items in the prior audit were closed. Evidence:

| Prior item | Status | Evidence |
|---|---|---|
| CQ-01 stock race (confirm vs. creation) | **RESOLVED** | `core-java/.../order/OrderService.java` — `transitionOrder` decrements stock only after the in-memory status flip, gated by optimistic lock + retry (`stockService.decrementForOrder`, comment cites "CQ-01 — RESEARCH §11 Q7"); a stock failure rolls back the status change in the same `@Transactional`. |
| CQ-02 `FinancialTransactionService.getSummary()` `findAll()` OOM risk | **RESOLVED** | `core-java/.../finance/FinancialTransactionService.java:178-206` — rewritten to 2 SQL statements via JPQL constructor-expression aggregation (`aggregateForCurrentTenant`, `aggregateByVatRate`), pinned byte-for-byte against the old behaviour by `FinancialSummaryGoldenFileTest` (Phase 14 Plan 02). |
| INFRA-17 K8s NetworkPolicies missing | **PARTIALLY RESOLVED** | `k8s/base/networkpolicies/` now exists with a validation script (`k8s/scripts/validate-networkpolicies.py`). Enforcement gap remains locally — issue **#297** "Install Calico on the local minikube profile to actually enforce NetworkPolicies" is still open. |
| INFRA-11a K8s Sealed Secrets not deployed | **PARTIALLY RESOLVED** | `k8s/base/secrets-template.yaml.example` + `k8s/scripts/seal-secrets.sh` + `k8s/scripts/check-no-plaintext-secrets.sh` exist (the sealing tooling is real). Issue **#300** "Work Order H: sealed-secrets / external-secrets for the local secrets path" is still open for the local-dev path specifically. |
| SEC: no security headers on Spring responses | **RESOLVED** | `core-java/.../security/SecurityConfig.java:238-253` — `frameOptions().deny()`, `contentTypeOptions`, `referrerPolicy(STRICT_ORIGIN_WHEN_CROSS_ORIGIN)`, and prod-gated HSTS (31536000s, includeSubDomains). Comment cites ASVS 14.4.1-14.4.7 / SEC-03. |
| CSP: no Content-Security-Policy headers | **RESOLVED** | `frontend/middleware.ts` — per-request nonce-based CSP (issue #89 / SEC-02), `frontend/next.config.mjs:35-40` documents the split (static headers here, CSP is per-request). |
| Edge: no OpenAPI spec for Go gateway | **RESOLVED** | `edge-go/docs/swagger.yaml`, `edge-go/docs/swagger.json`, `edge-go/cmd/edge/openapi_test.go` (v2.2 milestone). |
| SECR-08: Keycloak realm-export dev secrets committed in plaintext | **RESOLVED** | `.gitignore:166-171` now excludes the rendered `infra/keycloak/realm-export.json` and `realm-export-customers.json`; only `realm-export.template.json` / `realm-export-customers.template.json` (with `${VAR}` placeholders) are committed, rendered by `envsubst` at container start (`docker-compose.full-stack.yml:120-137`). |
| `/public/orders?email=` enumeration risk | **RESOLVED** | `PublicStorefrontController` requires a mandatory `verify` (order number) param alongside `email` — "without it the request is rejected to prevent email-based enumeration" (controller doc comment); a second, stronger variant derives the email exclusively from a verified JWT with no email query param at all. |
| `payment_event_outbox` flusher assumed single-instance (needs distributed lock) | **RESOLVED — original concern was a misdiagnosis of the pattern used** | `PaymentEventOutboxFlusher.java:32,151` — `claimPendingBatch` uses `FOR UPDATE SKIP LOCKED`, which is safe under N concurrent flusher instances by construction; no ShedLock/Debezium needed. Same pattern in `MediaEventOutboxFlusher.java`. |
| Alert runbook TODO stubs (9 of 9 unfilled) | **PARTIALLY RESOLVED** | `docs/runbooks/alerts.md` grew from 121 to 692 lines; 6 `<!-- TODO: fill in -->` stubs remain (lines 95, 101, 105, 154, 236) — the file's own closing note says these are "the ones no incident has supplied lessons for yet," i.e. a deliberate residual, not neglect. |
| Reactive `.block()` calls in `OrderStateMachineService` | **DOWNGRADED — original framing was inaccurate** | `core-java/build.gradle.kts` pulls in both `spring-boot-starter-web` (servlet/Tomcat MVC) **and** `spring-boot-starter-webflux` (used only for `WebClient` in `FhrsClient`/`ImageAnalysisService`). The app serves on blocking Tomcat threads, not a shared Reactor event loop, so a per-request `.block()` on a freshly-created stateless state machine (`OrderStateMachineService.java:52-132`, `VendorOnboardingStateMachineService.java`) does not starve a shared reactor pool — it is the intended blocking-MVC usage of Spring State Machine's reactive API. Not a defect; downgraded from the prior P2 rating. |
| Work Order H (sealed secrets) | still open | tracked live as issue **#300** (superseded the prior ad-hoc description). |
| Work Order K (edge distributed rate limiter / OTel) | **unverified whether resolved** — not re-checked this pass; no open issue found naming it explicitly. |
| Work Order M bulk import silent partial-failure / OOM | **LIKELY IMPROVED, not fully re-verified** | `core-java/.../product/BulkImportService.java` now has explicit per-row `try/catch` (lines 155, 250) with a documented distinction between a row-level failure and a caller-level `ShopAccessDeniedException` (line 143) — this addresses the "silent partial failure" half; large-file OOM behaviour was not re-measured. |
| Strix pentest backlog (11 findings, #548-#552) | **RESOLVED — all CLOSED** | `gh issue view 548/549/550/551/552` → all `state: CLOSED` (Phase 28, "Security Triage + the Dev/Prod Boundary," completed 2026-08-10). Prior memory note calling this an "untracked pentest backlog" is stale. |
| P3-12 unused JasperReports in prod JAR | **RESOLVED** | `core-java/build.gradle.kts:152-157` — comment records removal 2026-07-27: zero imports, zero `.jrxml`/`.jasper` templates existed; also closed 3 CVEs (beanutils + 2 jasper CVEs). |
| Work Order O WhatsApp order idempotency | **NOT RE-VERIFIED — inconclusive** | `edge-go/internal/whatsapp/` has a parser + tests but no idempotency-key handling found in a targeted grep; the WhatsApp *ordering* flow itself remains incomplete per open issue **#208** `[AI-6] Complete the WhatsApp conversational channel`, so the idempotency question is largely moot until that ships. |

**Still genuinely open from the prior audit, unchanged:**
- **Grafana dashboards**: only `infra/monitoring/grafana/dashboards/stomp-dashboard.json` is provisioned — no JVM/DB/business dashboards. Compose-only; Phase 29 (which was to add k8s monitoring) is **paused**, see below.
- **Alertmanager inhibition rules**: `infra/monitoring/alertmanager/alertmanager.yml.tmpl:47` still reads `inhibit_rules: []`.
- **OrderDto exposes `tenantId`**: `core-java/.../order/dto/OrderDto.java:12-13,73-74` still has a public `tenantId` field with getter/setter, serialized in every order API response, no `@JsonIgnore`. Low severity (callers are already tenant-scoped) but unaddressed across 5 intervening milestones.
- **Review module has no controller**: `core-java/.../review/{Review,ReviewService,ReviewRepository}.java` and DTOs exist (since V27, 2026-04-era), but there is still no `ReviewController` — the feature is DB+service only, invisible to any client. Not tracked as a live GitHub issue.

---

## OPEN — Critical/High severity, fixed-but-unmerged (QA council run `20260902-134741`)

A full `/qa-discover` + `/qa-plan` run against `main`@`1833fd3b` on 2026-09-02 found **97 findings** (2 Critical, 23 High, 36 Medium, 36 Low; 83 probes, each with a recorded fail-direction proof). Remediation for essentially the entire Critical+High tier has landed as commits on `feature/qa-remediate-20260902` (11 `qa/cluster-*` lane branches, all merged into this branch) — **but this branch carries no open PR yet, so `main` is still exposed to every one of these until it merges.**

**The two Criticals (both fixed on this branch, both still live on `main`):**

- **API-1** — the documented **read-only** machine credential `integration-catalog-ro` (scope `catalog:read` only, described in `docs/security-scopes.md` as "zero blast radius") could rewrite product titles, prices and allergen declarations via `POST /api/v1/sync/batch`, because `SyncController` had no `@PreAuthorize` at all — sharper than the pre-existing issue **#648** (which framed this as intra-tenant only). Fix: `9980ad17` — `@PreAuthorize("hasAuthority('SCOPE_catalog:write')")` on the controller + `ShopAccessService.require(...)` gating inside `SyncService` before mutation (per-shop for existing products, GROUP_ADMIN-only for tenant-wide/new-shop writes). RLS tenant wall itself held throughout (body-supplied `tenantId` was always ignored).
- **FE-1** — vendor "Sign Out" left the NextAuth session cookie valid: `@auth/core` re-issues the JWT on every session GET (`frontend/lib/api-client.ts:31` fires ~24 of these per dashboard load), so `/dashboard` silently re-opened as the departed vendor with no credential prompt. Fix: `fe0c4a42` — clears the app session server-side on the Keycloak return leg, flagged + pre-flighted.

**All 23 High findings have fix commits on the branch** (non-exhaustive citation — full list in `.qa-council/20260902-134741/findings.json` and `plan.md`): API-2/API-3/API-4/API-13 (sync/batch validation + checkout Idempotency-Key contract), SEC-1 (webhook control-plane role gate), SEC-2 (`ACCESS_STRICT_SCOPING` posture now explicit), INT-1/INT-2/INT-5/INT-7 (onboarding review-queue + gate-resolve dead ends), COR-1 (orders silently defaulting to DELIVERY with £0 fee), A11Y-1 through A11Y-16 (skip link, dialog focus-return, form-error association, colour contrast, keyboard-reachable scroll regions, etc.).

**Two escalations remain deliberately open, opt-in, not blocking:** E-3 (whether to *publish* the dashboard's a11y conformance claim — sequenced last, gate-widening only) and E-4 (an `og:image` brand asset + a `font-display: optional` trade — both owner decisions, not defects). See `.qa-council/20260902-134741/plan.md:218-219,248`.

**Action for the reader of this document:** before treating any of the above as closed, confirm `feature/qa-remediate-20260902` has actually merged to `main` (`git log main..origin/feature/qa-remediate-20260902` should be empty, or check for a merged PR). As of this writing it has not.

---

## Tech Debt

### Frontend page monoliths (Work Order/P3-12 remainder)
- Issue: Several dashboard pages exceed 1,000 lines, mixing data-fetching, form state, and rendering in one file.
- Files: `frontend/app/dashboard/marketing/page.tsx` (1497 lines), `frontend/app/dashboard/orders/page.tsx` (1229), `frontend/app/shop/[slug]/checkout/page.tsx` (1087), `frontend/app/dashboard/products/page.tsx` (1024), `frontend/app/dashboard/kitchen/page.tsx` (1023), `frontend/app/dashboard/onboarding/page.tsx` (1012).
- Impact: Higher regression risk per change, harder code review, slower onboarding for new contributors.
- Fix approach: Extract shared list/table/form sub-components per page (pattern already used in `frontend/components/storefront/` for smaller surfaces); no urgency — not user-visible.

### No i18n framework
- Issue: No `next-intl`/`react-i18next`/equivalent in `frontend/package.json`; all copy is hardcoded English.
- Impact: UK-only today (matches current market), but the CATER-0 epic (**#428**, catering track) and any future non-UK expansion would require a full rewrite of every string, not an incremental addition.
- Fix approach: Not urgent; note as an architectural constraint for any future locale-expansion plan.

### `OrderDto.tenantId` still exposed in API responses
- See RESOLVED section above — carried forward as a live, low-severity item since no fix has landed across 5 milestones despite extensive rework of the same file.
- Files: `core-java/src/main/java/uk/jtoye/core/order/dto/OrderDto.java:12-13,73-74`.
- Fix approach: Remove field + accessors, update `OrderMapper`, add a regression test asserting the JSON response has no `tenantId` key.

### Dependency-horizon deferrals (dated, tracked, not neglect — but real technical debt)
All governed by `infra/dependency-horizons.yaml` + `scripts/check-dependency-horizons.sh`, enforced in CI. Every exemption below is dated and expires without renewal — reproduced here so a reader of this document does not have to re-derive the calendar:

| Component | Horizon | Exemption expires | Tracked by |
|---|---|---|---|
| RabbitMQ 4.3.x (vendor community support) | 2026-11-30 | **2026-11-30** (ends ON the horizon — no viable upgrade target exists yet; 4.4 not yet on Docker Hub as of 2026-09-02) | **#724** |
| `rabbitmq-k8s` (staging/prod broker — not deployed from this repo, version unknown, `owner: UNASSIGNED`) | n/a | manual-review **2026-10-26** | ADR-0002 |
| Alpine 3.20 (dev seed/init helper only) | 2026-04-01 | 2026-11-30 | DEFERRED-27 |
| Keycloak 24.0.5 | 2024-06-10 | 2026-12-31 (upgrade needs its own live-auth-rehearsal plan — this repo has a recorded JWT-issuer/JWKS split-horizon outage history) | DEFERRED-27 |
| Prometheus 2.48.0 | 2023-12-28 | 2026-12-31 | DEFERRED-27 |
| Grafana 10.2.2 | 2024-07-24 | 2026-12-31 | DEFERRED-27 |
| Spring Boot 3.5.16 (OSS support ended) | 2026-06-30 | **2027-02-28** (Boot 4.1 is a scoped migration, not a bump — dependabot's naive PR #676 failed 5 CI jobs) | **#706** |

Several third-party images (minio, minio/mc, ollama, mailhog, alertmanager, redis-exporter, postgres-exporter) have no `endoflife.date` entry at all (404) and are tracked via dated `manual_review` (`expires: 2027-01-27`) rather than a horizon — by design, not a gap.

---

## Known Bugs

### TOAST_LIMIT=1 silently displaces unread errors
- Symptoms: A second toast destroys the first before it can be read — a mutation error can be silently displaced by a subsequent success toast.
- Files: `frontend/hooks/use-toast.ts:5,76` (`const TOAST_LIMIT = 1`, `.slice(0, TOAST_LIMIT)`).
- Trigger: Any two toast-triggering actions within the display window.
- Tracked: **#700** (confirmed live by measurement), re-confirmed as QA-council finding **A11Y-9**.

### Dashboard products table: unclamped long titles
- Symptoms: A 224-character product title renders unclamped, wrapping to six lines and quadrupling row height (storefront correctly truncates the same data).
- Files: `frontend/app/dashboard/products/page.tsx` (products table cell).
- Tracked: **#702** — **fix landed on `feature/qa-remediate-20260902`** (`c61ac59d`, "clamp the dashboard product title cell to two lines," A11Y-10). Not yet on `main`.

### Compose port RANGE vs. baked single port
- Symptoms: `docker-compose.full-stack.yml:365-367` publishes core-java on `9090-9091` (2 replicas), but the frontend's browser bundle bakes `NEXT_PUBLIC_API_URL=http://localhost:9090` at build time. If Docker assigns a browser-side call to replica 2 (port 9091), it silently fails.
- Files: `docker-compose.full-stack.yml:365-367,446,479`.
- Tracked: **#671** (P3, tech-debt).

### `jest-axe` cannot evaluate `scrollable-region-focusable`
- Symptoms: This is a geometric a11y rule requiring real layout; jsdom has none, so the dashboard's automated a11y gate is structurally blind to it — A11Y-3 (accessible names on destructive controls) is effectively ungated per-PR, caught only by manual/E2E sweeps.
- Tracked: **#689**.

### `StaffManagementService.grant()` re-insert vs. 404 ambiguity
- Symptoms: Shares the "vanished row" shape of a previously-fixed issue (#486) but the method upserts, so whether a re-grant after a vanished row should 404 or silently re-insert is an undecided product/API-contract question, not yet a translation bug.
- Files: `core-java/src/main/java/uk/jtoye/core/security/access/StaffManagementService.java`.
- Tracked: **#499** (P3, tech-debt).

### Nightly full-suite E2E lane instability
- Symptoms: The scheduled full-stack Playwright run is the project's only full-suite E2E instrument; it has failed intermittently, most recently attributed to `#647`-class fresh-volume provisioning gaps and crash-looping containers before Playwright ever runs.
- Tracked: **#683** (P1, ci) — **still open** as of this audit despite a HANDOFF.md note claiming an 2026-08-28 "resolution"; the issue's own guidance is explicit: "Do not close this because a re-run went green without a code change... Close it when you know which change fixed it." Treat the lane as unreliable until the issue is closed.

---

## Security Considerations

### Within-tenant BOLA on `/sync/batch` — see Critical API-1 above
- **Fixed on `feature/qa-remediate-20260902` (`9980ad17`), not yet on `main`.** Tracked as **#648** (P2, security) — the QA council's API-1/SEC-5/API-13 sharpened this to Critical (a documented read-only credential, not just intra-tenant shop-scoping).

### Webhook control plane had no role gate
- Any STAFF-rank tenant user could rotate integration signing secrets. Fixed on the remediation branch (`14a5b346`, SEC-1) — service-boundary GROUP_ADMIN gate. Not yet on `main`.

### `ACCESS_STRICT_SCOPING` unarmed everywhere
- The V57 shop-scoping strict mode (see CLAUDE.md V57 note — de-honours JIT-sourced tenant-wide GROUP_ADMIN grants) was set nowhere, meaning the #648-class human BOLA was live for any explicitly-granted user in every deployed environment. Made explicit (`ACCESS_STRICT_SCOPING=false` declared + WARN at startup) via `0f0fc384` (SEC-2). Still defaults off — this is a posture disclosure, not a behavioural fix; the strict mode itself remains opt-in.

### STOMP shop grant checked only at SUBSCRIBE
- Symptoms: A revoked subscriber keeps receiving on an already-open STOMP session — the grant is not re-checked per-message, only at subscribe time. Same class as a previously-fixed issue (#281), one transport (STOMP) still open.
- Tracked: **#627** (open, no severity label).

### 6 legacy `_aud` tables allowed a foreign-tenant INSERT (RLS gap)
- Fixed on the remediation branch: `766e5e96` — "V65 closes the foreign-tenant INSERT on the six legacy `_aud` tables; Envers settings reach Envers" (SEC-6, N-3). This is a **new Flyway migration (V65)** riding this branch — confirm it lands before schema-version claims elsewhere in this repo are trusted.

### Redis cache serializer used unrestricted polymorphic deserialization
- `DefaultTyping.EVERYTHING` + `LaissezFaireSubTypeValidator` is a known deserialization-gadget-chain risk class. Fixed on the remediation branch (`2d65609c`, SEC-4) — allowlisted the serializer's polymorphic types instead of laissez-faire.

### gitleaks path allowlists were whole-file and rule-agnostic
- A real AWS key, GitHub PAT, or private key dropped into an allowlisted path (e.g. `.planning/quick/**`) would not be caught. Fixed on the remediation branch (`acd9f975`, SEC-3) — allowlists now express content-shaped rules (placeholder patterns) rather than blanket path exemptions.

### Existing image objects still hold raw bytes / EXIF GPS / client-declared Content-Type
- Issue: The Phase 24 safe-upload pipeline (media_asset CoW model, V53/V58/V60) only validates/strips/transcodes uploads going forward; objects that predate the pipeline (or were backfilled from the flat `products.image_url` columns) still carry unvalidated raw bytes, EXIF GPS metadata, and client-declared (untrusted) Content-Type.
- Files: `core-java/.../media/MediaAssetService.java`; the V53 backfill loop is documented in CLAUDE.md's V59/V58/V53 note as "wraps existing products.image_url... as-is (no re-pipeline)".
- Impact: A vendor who uploaded a photo before Phase 24 shipped may have EXIF GPS coordinates (their home/kitchen location) still embedded and served publicly.
- Tracked: **#488** (P2, security) — a genuine backfill decision, not yet made.

### `olajay.co.uk` domain expiry, untracked renewal
- Issue: Every public var, legal page and DSAR contact address rides on `olajay.co.uk`, which expires **2026-12-31**, with no recorded renewal automation or reminder outside this issue.
- Tracked: **#649** (P1, compliance).

---

## Performance Bottlenecks

### 20+ "use client" pages fetch on mount, including `/shop`
- Issue: A broad swath of the frontend — not just the originally-filed subset — is client-rendered and fetches data client-side on mount rather than server-rendering, including the customer-facing `/shop` discovery page. This defeats SSR benefits (TTFB, SEO crawlability of dynamic content, perceived load).
- Tracked: **#507** (P2) — explicitly widens and corrects a prior narrower issue (#463)'s premise.

### `/shop` CLS 0.1616 breaches the declared 0.1 budget
- Issue: Measured 2026-09-02 against the running build: the cause is **not** a font `size-adjust` issue (that already ships correctly — `size-adjust:111.93%` measured in the built chunk) but a `flex-wrap` chip row at `frontend/app/shop/shop-discovery-client.tsx:524` that re-wraps on a filter change, shifting layout by exactly 42px (20+12+2 chip + 8px gap). `frontend/e2e/perf-budgets.ts`'s docstring still misattributes the cause to hydration/font swap.
- Tracked as QA-council finding **FE-3**; fix approach recorded (reserve chip-row height / `flex-nowrap` + overflow) but not yet confirmed landed — check `shop-discovery-client.tsx:524` directly before assuming resolved.

### Duplicate session fetches on every dashboard load
- Issue: ~23-24 identical `GET /api/auth/session` calls fire per dashboard page load (`frontend/lib/api-client.ts:31`), a dedup gap in the NextAuth client usage. This is also what widened the FE-1 (Critical) window above.
- Tracked as QA-council finding **FE-8** — explicitly noted as shrinking, not fixing, FE-1's exposure window.

---

## Fragile Areas

### `SyncController`/`SyncService` (`/sync/batch`)
- Files: `core-java/src/main/java/uk/jtoye/core/sync/SyncController.java`, `core-java/src/main/java/uk/jtoye/core/sync/SyncService.java`.
- Why fragile: The only production caller is `edge-go/cmd/edge/main.go:304`, forwarding the caller's own token — but no production producer exists today (searched frontend/scripts/mcp-server: no `sync/batch` caller found). It is simultaneously "the one route with real authz teeth now" (post-remediation) and "a route nobody actually calls," which makes any future regression here invisible until an integration is built against it.
- Safe modification: Add the negative/deny integration test the QA council found missing (`SyncControllerIntegrationTest` currently uses `@WebMvcTest(addFilters = false)` — zero real authorization coverage) before making further changes.

### Onboarding state machine (`VendorOnboardingStateMachineService`, `GateChainRunner`)
- Files: `core-java/src/main/java/uk/jtoye/core/onboarding/`.
- Why fragile: Sole writer of `Shop.published`; multiple open issues describe reachable-but-dead states (`OnboardingState.SUSPENDED` unreachable — **#** noted as INT-2 in the QA council run; `OnboardingState.LIVE` has no exit transition per INT-3) and a compliance gate that fails open (a nonexistent Companies House number resolves to a pass rather than a review-park, per INT-7 — **fixed on the remediation branch**, `d710f8ea`). Any change to gate logic needs the full `GateChainRunner` test suite re-run, not just the touched gate.

### `frontend/hooks/use-toast.ts`
- Why fragile: `TOAST_LIMIT = 1` (see Known Bugs above) plus two further upstream `use-toast` staleness bugs noted in `STATE.md`/`HANDOFF.md` as residual, unfixed as of 2026-08-31. Any change to toast behaviour should be paired with the #700 fix rather than layered on top of it.

### Keycloak realm configuration (`infra/keycloak/`)
- Why fragile: Three parallel artifacts (`realm-export.json` generated, `realm-export.template.json` committed, `realm-export-customers.template.json` committed) rendered via two separate `envsubst` invocations in `docker-compose.full-stack.yml`, each with its own allow-list of substitutable variable names. A name added to the JSON but missing from the corresponding allow-list survives as a **literal** `${VAR_NAME}` string in the rendered realm — this exact trap is documented in-file (`infra/keycloak/realm-export-customers.template.json:101`) because it was hit in production once (measured 2026-08-08).
- Safe modification: Any new `${VAR}` reference in either template must be added to the matching `envsubst` allow-list in the same commit; `infra/keycloak/README.md` documents both invocations.

---

## Scaling Limits

### Edge gateway rate limiter is per-instance in-memory
- Current capacity: Token-bucket rate limiter in `edge-go/internal/` is not shared across replicas.
- Limit: At N horizontally-scaled edge pods, the effective platform-wide limit is N× the configured per-tenant limit (100 req/min becomes 100×N).
- Scaling path: Move to a Redis-backed distributed token bucket (Bucket4j already used core-side with Redis backing — same library could back the edge, or a Go-native Redis rate limiter). No open issue found naming this explicitly in the current tracker (superseded from the prior audit's "Work Order K" without a live successor issue — worth re-filing if edge horizontal scaling is imminent).

### No load-test baseline, no contract tests, no fault-injection tests
- Tracked: **#115** `[P3-13]` (open, milestone "Remediation P3 — Hardening & Polish"). The platform has never been measured under concurrent load beyond ad-hoc E2E runs.

### No production tenant lifecycle (onboard/bill/pay-out/offboard)
- Tracked: **#102** `[P2-11]` (open) — single pooled Stripe account; no self-serve tenant provisioning exists outside the dev-only `DevTenantController`.

---

## Missing Critical Features

### No PITR (Point-in-Time Recovery) for Postgres
- Tracked: **#101** `[P2-10]` (open, bug) — RPO is 24h (daily `pg_dump` only); `SYSTEM_DESIGN_V2` documentation is noted as falsely claiming WAL-G is in place. No DB HA either.

### No log aggregation
- Container stdout only; no Loki/ELK/Promtail sidecar found under `infra/`. Cannot trace cross-service requests or do incident forensics beyond `docker compose logs`. No live issue found naming this explicitly in the current tracker — re-file if this is still a gap when Phase 29 (staging + monitoring) resumes.

### Customer-storefront realm unconfigured in every k8s environment
- Tracked: **#299** (P2, bug) — base, staging, production, and local k8s all lack customer-realm configuration; the identity provider count is 0 across the board (see also **#432**, no social signup).

### No mcp-server k8s manifests
- Tracked: **#301** (P2) — staging, production, and local all run the platform without its MCP surface; the AI-agent-readiness surface (Phase 25's mutating MCP tools) is effectively k8s-invisible.

### Phase 29 (Deployable Staging + Monitoring) — PAUSED
- The platform has still never run outside a laptop/local-k8s. Phase 29's body lives on branch `phase-29-research` (not `main`), paused at 9/16 on two owner-blocked actions: staging DNS and operator secrets. **Do not treat `phase-29-research` as a stale/cleanup-candidate branch** — it is the parked continuation of live milestone work, resumable on those two owner actions per `.planning/STATE.md`.

### Phase 30 (Money path executed against real Stripe) — not started
- Refunds and recurring billing have been proven only against a mock; `.planning/ROADMAP.md` lists Phase 30 as unstarted (`[ ]`).

### UX-4/5/6 — no locality, no real payment flow, no 2FA
- **#460** (P1): device location unused, shop coordinates inert, no delivery radius.
- **#461** (P1): orders complete with no payment; pay-on-collection needs replacing with channel-issued payment links.
- **#462** (P2, security): password signups have no second factor and no verified contact channel.

---

## Test Coverage Gaps

### `SyncControllerIntegrationTest` has zero authorization coverage
- What's not tested: The only test for `/sync/batch` is a `@WebMvcTest` with `addFilters = false` and a mocked service — it proves nothing about the security filter chain. This is exactly what let the API-1 Critical (documented read-only credential could write) ship undetected.
- Files: wherever `SyncControllerIntegrationTest` lives under `core-java/src/test/`.
- Risk: Any future authorization regression on this endpoint is invisible to CI.
- Priority: High — a deny-test proven to fail before the fix should be added per the QA council's own recommended action, even though the fix has already landed.

### E2E skip budget: 7 declared skips against a budget of 6 (one undeclared)
- What's not tested: `check-e2e-skip-budget` failed on the Phase 35 branch at 7/6 with one undeclared skip (`onboarding-blocked-flow`), tracked as **#686**. Separately, issue **#547** notes "7 E2E skips are declared and bounded, but still unverified surface" — i.e. even the *declared* skips represent untested paths, not just an accounting gap.
- Priority: Medium — the gate exists and catches drift, but the underlying paths remain genuinely unexercised.

### `jest-axe` structurally cannot evaluate geometric a11y rules
- What's not tested: `scrollable-region-focusable` and similar layout-dependent axe rules never run in the jsdom-based dashboard gate (see Known Bugs, #689). Only caught by manual QA-council sweeps or real-browser Playwright a11y runs.
- Priority: Medium.

### Frontend `.verify.mjs` guards not fully wired into CI
- What's not tested: Issue **#716** — "remaining frontend `.verify.mjs` guards" are not yet wired into CI, an enforcement blind spot identified in a PR #715 review (WR-06).
- Priority: Medium (open, unlabelled).

---

## Standing Operational Hazards (process traps, not code defects — repeatedly paid for on this project)

These are not bugs in the running system; they are ways this project has previously produced a **false-positive "it works"** signal, and they recur because the failure mode is structural, not a one-off mistake. Anyone changing infrastructure, migrations, or the outbox should re-read these before trusting a green check.

1. **Stale containers after any code change.** `docker compose start`/`restart` never rebuild; even `docker compose up -d --build <svc>` can leave the container on the OLD image if every Docker layer was cached (measured: image digest changed, container stayed on the previous one, health check reported "healthy" of the stale container). The only reliable remedy is `docker compose up -d --force-recreate --no-deps <svc>`. Enforced by `scripts/check-runtime-freshness.sh` (compares each image's `.Metadata.LastTagTime` — not `.Created`, which survives a cached rebuild — against the newest commit touching that service's build paths).
2. **Runtime-vs-branch parity gates must run from the main checkout, not a worktree.** `check-runtime-freshness`, `check-infra-exposure`, and `check-container-config-drift` all derive state from either the Compose project name (taken from the current directory) or a `.env` interpolation — both are VOID from a worktree.
3. **RLS migration-backfill needs a per-tenant `set_config` loop.** A bare `UPDATE` against a FORCE-RLS table from a Flyway migration hits zero rows (the migrator role has no tenant GUC set) — this is a recurring trap across multiple migrations (see the V57 grant_source backfill fix referenced in CLAUDE.md, and the general pattern documented in project memory as `trap_rls_migration_backfill`).
4. **The outbox-flusher dispatch trap.** Adding a new event type to any transactional outbox (`payment_event_outbox`, `media_event_outbox`, `onboarding.events`, webhook fanout) poison-dead-letters unless the flusher's `publishRow` dispatch branch is explicitly extended for the new type — the SKIP LOCKED claim mechanism does not automatically route new types.
5. **`SELECT max(version) FROM flyway_schema_history` sorts lexically (TEXT column), not numerically.** Returns e.g. `9` instead of `63` if read naively; use `ORDER BY (regexp_replace(version,'\D','','g'))::int DESC LIMIT 1`.
6. **A rebase-merge strips `(#NNN)` PR citations**, voiding `check-changelog-contract.sh`; only squash-merge (with a citation-bearing final subject line) is safe on this repo.
7. **`gh pr checks`/`statusCheckRollup` lag the underlying job and cannot distinguish "check failed" from "network error"** — both return non-zero/`PENDING`. Read the job directly (`gh run view <id> --json jobs`) when the answer matters; require `rows > 0 AND pending == 0` before trusting a poll loop, never an empty result table as an answer.

---

*Concerns audit: 2026-09-03*
