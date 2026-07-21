---
gsd_state_version: 1.0
milestone: v2.3
milestone_name: vendor-ops-ai-interleaved
status: executing
stopped_at: Completed 23-14-PLAN.md — gap-closure CR-07 + WR-09 + WR-01/WR-11 (strict-scoping tightens)
last_updated: "2026-07-21T10:23:32.343Z"
last_activity: 2026-07-21 -- Executed gap plan 23-14 (CR-07 strict-scoping + WR-09 + WR-01/WR-11)
progress:
  total_phases: 6
  completed_phases: 2
  total_plans: 27
  completed_plans: 26
  percent: 96
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-07-14)

**Core value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.
**Current focus:** Phase 23 — vendor-scoped-access-responsive-dashboard-nav

## Current Position

Phase: 23 (vendor-scoped-access-responsive-dashboard-nav) — EXECUTING (gap-closure wave 23-08..23-15)
Plan: gap-closure 23-14 COMPLETE (14 of 15 SUMMARYs on disk; 23-01..23-14) — only 23-15 (phase-gate docs/OpenAPI reconcile) remains
Status (23-14): CR-07 CLOSED — enabling strict-scoping now genuinely tightens. V57 adds shop_staff.grant_source (JIT|OPERATOR) + aud mirror (backfill created_by IS NULL→JIT, NOT NULL DEFAULT 'JIT', no RLS policy → RlsContractTest green). Under strict-scoping ON, a JIT-sourced tenant-wide GROUP_ADMIN is DE-HONOURED (a day-one user genuinely becomes scoped) while OPERATOR grants + realm admins are honoured unchanged; the policy is applied in the shared isGroupAdminForUser decision helper (OUTSIDE the cached Membership snapshot, so a flag change is never served stale) → BOTH HTTP + STOMP (canAccessShop) tighten at once. Lockout safety: the oldest JIT admin (created_at,id) is retained as a WARN-logged bootstrap when no OPERATOR admin exists — no tenant can lock itself out on the flip. WR-09: onRequest skips JIT provision + directory upsert for an allowlisted machine client (isAllowlistedMachineClient, subject-shape-independent) so a UUID-sub Keycloak service account stops accumulating a permanent GROUP_ADMIN row. WR-01: the D-05 membership cache genuinely engages — all internal gate call sites reach @Cacheable resolveMembership through the bean proxy (ObjectProvider self()), proven by a caching-enabled test (entry POPULATED after a gate call, serves stale until evict, then re-resolves + denies). WR-11: JIT-provision eviction now fires AFTER commit via a single shared evictMembershipAfterCommit helper used by BOTH onRequest and StaffManagementService (no drift). Membership round-trips through the exact CacheConfig JSON serializer (unit-proven). Staff screen labels JIT rows 'Auto-granted on first sign-in' (no layout shift). Task 0 checkpoint = user ACCEPT (full path incl. bootstrap rule; no modification). Proven vs real Postgres (Testcontainers): StrictScopingTightening 5/5 (RED pre-fix on 4/5 — CR-07 central proof), Enforcement 12/12, CacheBypass 5/5, StaffManagement 19/19, FailClosed/JitProvision/ErrorType/RlsPolicy/RlsContract green; MembershipSerializerRoundTrip 3/3; frontend jest 93/93 + build green. VSA-02/VSA-04 stay NOT-marked-complete (anti-false-green — 23-15 still contributes). DEFERRED to 23-15: docs/metrics.json reconcile (schema 56→57; +9 Java @Test, +1 Jest) + OpenAPI snapshot regen.
Prior — 23-13 COMPLETE (13 of 15 SUMMARYs; 23-01..23-13):
Status: Executing Phase 23 gap-closure. 23-13 shipped — the FRONTEND now consumes the server's authority (CR-08 closed). fetchMyShops sources isGroupAdmin + userId from GET /api/v1/staff/me (23-12's MyAccessDto); decodeJwtPayload + isGroupAdminFromSession DELETED — a browser JWT parse was the wrong shape even for a UI hint. The silent-pinning regression is gone: the switcher persists ONLY a stale-selection correction (D-13), so the day-one implicit GROUP_ADMIN (not a realm admin) lands on All-shops and can re-select it instead of being narrowed to one shop with no way back. WR-06: new ShopSwitcherProvider (mounted once in dashboard-shell above both switchers) is the single fetch + single hydration writer; the two switcher instances read selection from useShopContext() and converge live — chosen over a cached promise because the provider also owns the single hydration writer (renders no DOM, so the MOBL-01 375px shell markup is unchanged). WR-12: staff-screen isSelf now compares the Keycloak sub (userId), not a session email (which 23-12's masking broke); useSession removed from the page. IN-02: grant-path 409 shows downgrade-specific copy distinct from the revoke path. Revocation-timing copy corrected to the real 5-minute SSE bound (23-11). Raw-UUID grant fallback → labelled 'Unlisted member' (23-14 owns the richer JIT treatment). Proven: 156/156 dashboard+hooks Jest (falsifiability RED shown pre-fix: 5/11 switcher + 4/10 staff), npm run build (tsc) green after every task. VSA-03/VSA-04 stay NOT-marked-complete (anti-false-green — 23-14 + 23-15 still contribute). 23-12 shipped earlier — three staff-backend findings closed together against real Postgres (Testcontainers). WR-05: StaffManagementService.grant() now validates its inputs BEFORE the D-11 guard and any write — a shopId not in the caller's tenant is a typed 404 via ShopRepository.findByIdAndTenantId (the FK cannot enforce tenancy because Postgres RI BYPASSES RLS, which is exactly why a foreign-tenant shop id was silently accepted), and a userId absent from user_directory is a distinct 404 (enforcing the GrantStaffRequest javadoc's already-claimed precondition → grants target only logged-in users, D-09). Foreign-tenant and non-existent shop return an IDENTICAL 404 (no existence oracle). CR-08 backend: new GET /api/v1/staff/me + MyAccessDto(userId, groupAdmin, grantedShopIds) — server-authoritative effective access, NOT requireGroupAdmin-gated (every caller may ask about itself), @Transactional(readOnly) so onRequest() JIT/upsert is skipped. The empty-set sentinel is RESOLVED at the DTO boundary: groupAdmin=true → grantedShopIds=null (unrestricted, NOT 'no shops'); groupAdmin=false → exact possibly-empty set (empty = no access). Proves the day-one implicit-GROUP_ADMIN case the client-side realm parse gets wrong. WR-10: DirectoryEntryDto masks email at the boundary (a***@example.com; full value retained server-side only), and GdprService now erases user_directory by tenant_id+email (a no-_aud derived cache, D-09 → straight tenant-scoped DELETE; zero matches is normal, ErasureRecord accounting unchanged). Proven: StaffManagementIntegrationTest 19/19 (RED-first on foreign-shop/unknown-user grant + masking), GdprErasureIntegrationTest incl. tenant-scoped directory erasure + zero-match balance (RED: row survived pre-fix, expected 0L but was 1L), :core-java:test green, FailClosed + RlsPolicy regression green. Grants still key on userId not email. VSA-04 stays PENDING (23-13/14/15 still contribute; anti-false-green).
  ⚠ ONE BLOCKER BEFORE THE PHASE PR CAN PASS CI — `docs/api/openapi-snapshot.json` is missing
  the `/api/v1/staff` endpoints; the surface is now FOUR (list, /me, /grant, /{id}) after 23-12.
  `OpenApiSnapshotTest` check-mode runs inside `integrationTest` (so scoped test runs stay green;
  the full `integrationTest` task is red until regen). Plan 23-15 owns
  `./gradlew :core-java:updateOpenApiSnapshot` + the docs-freshness `--write` count reconcile.
  Also still pending: `npx playwright test` + a live-browser pass over /dashboard/staff.
  23-13 DEFERRED its live 375px `dashboard-mobile.spec` run — blocked on `E2E_VENDOR_PASSWORD`
  (real Keycloak login; creds not in-session, same blocker as 23-07/webhooks) AND port-3000
  serves the pre-change image (needs a frontend rebuild). 23-13's 375px markup is unchanged +
  unit-MOBL-01 green; run the live spec at the phase PR after a rebuild + creds.
Last activity: 2026-07-21 -- Executed gap plan 23-14 (CR-07 strict-scoping tightens + WR-09 + WR-01/WR-11)

Progress: [██████████] 96%

## Milestone v2.3 Phase Map

| Phase | Name | Requirements | Migration | Est. plans |
|-------|------|--------------|-----------|-----------|
| 21 | Onboarding Blocker UX | ONBD-01..05 | none | 4 |
| 22 | Notifications & Comms | COMMS-01..07 (absorbs AI-01 #205, #208) | Comms tables (post-V53, out-of-order) | ~5 |
| 23 | Vendor-Scoped Access + Responsive Dashboard Nav | VSA-01..04, MOBL-01 | V52 shop_staff | 3 |
| 24 | Image Architecture — CoW Assets + Safe Upload Pipeline | IMG-01..04 | V53 media_asset | 3 |
| 25 | Mutating MCP Tools | AI-02 | none | 2 |
| 26 | Local-K8s Overlay + Verified Breakage Fixes | INFRA-01, INFRA-02 | none | 2 |

Execution order: 21 → 22 → 23 → 24 → 25 → 26 (locked; Comms inserted at 22 on 2026-07-14, absorbing the former standalone Outbound Webhooks). Hard dependency: 23 before 24 (V52 `shop_staff` precedes V53 `media_asset`).

## Performance Metrics

Full v2.0–v2.2 execution history (phases 1–20, quick-task ledger, per-plan durations) is preserved in `milestones/v2.2-ROADMAP.md`, git history, and MEMORY.md. v2.3 velocity starts fresh below.

**Velocity (v2.3):**

- Total plans completed: 15 / ~16 estimated
- Average duration: ~15m
- Total execution time: ~0.25 hours

**By Phase (v2.3):**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 21 | 5 | - | - |
| 22 | 7 | - | - |
| 23 | 14/15 | - | - |
| 24 | 0/3 | - | - |
| 25 | 0/2 | - | - |
| 26 | 0/2 | - | - |

*Updated after each plan completion*
| Phase 21 P02 | 25min | 2 tasks | 9 files |
| Phase 21 P03 | 21min | 3 tasks | 8 files |
| Phase 21 P04 | 18min | 3 tasks | 7 files |
| Phase 21 P05 | 45min | 3 tasks | 2 files |
| Phase 22 P01 | 11min | 3 tasks | 13 files |
| Phase 22 P02 | 20m | 3 tasks | 14 files |
| Phase 22 P03 | 50m | 3 tasks | 13 files |
| Phase 22 P04 | 16min | 3 tasks | 11 files |
| Phase 22 P05 | 32min | 3 tasks | 15 files |
| Phase 22 P06 | 12min | 3 tasks | 10 files |
| Phase 22 P07 | 13min | 3 tasks | 10 files |
| Phase 23 P01 | 12min | 3 tasks | 8 files |
| Phase 23 P02 | 7min | 3 tasks | 9 files |
| Phase 23 P03 | 55min | 3 tasks | 31 files |
| Phase 23 P4 | 10min | 2 tasks | 7 files |
| Phase 23 P05 | 35min | 4 tasks | 9 files |
| Phase 23 P07 | 40m | 2 tasks | 9 files |
| Phase 23 P06 | 12min | 3 tasks | 8 files |
| Phase 23 P08 | 44min | 3 tasks | 3 files |
| Phase 23 P09 | 29min | 3 tasks | 3 files |
| Phase 23 P10 | 45min | 3 tasks | 10 files |
| Phase 23 P11 | 20min | 3 tasks | 5 files |
| Phase 23 P12 | 40min | 3 tasks | 9 files |
| Phase 23 P13 | 17min | 3 tasks | 8 files |
| Phase 23 P14 | 24min | 3 tasks | 12 files |

## Accumulated Context

### Roadmap Evolution

- 2026-07-14 — **Phase 22 "Notifications & Comms" inserted** ahead of the original order (was Vendor-Scoped Access). Absorbs the former standalone Outbound Webhooks (#205) + WhatsApp (#208). Vendor-Scoped Access → 23, Image → 24; Mutating MCP (25) + K8s (26) unchanged. Scout found order-lifecycle email already works (`EmailNotificationService` + `OrderStateChangeListener`) — so the phase is extend+govern+add-channels, not build-first-consumer. SPEC written (7 reqs COMMS-01..07, ambiguity 0.16). Decided by user; roadmap-slot + 6 spec answers logged in `22-SPEC.md`.
- 2026-07-14 — Milestone v2.3 (Vendor Ops + AI interleaved) roadmap created. 6 phases (21–26) continue numbering from v2.2's Phase 20. Derived from 18 requirements across 6 categories in REQUIREMENTS.md; scope locked by user 2026-07-14. MOBL-01 folded into Phase 22 (pairs with the VSA-03 shop-switcher, avoids a one-requirement phase). AI track split into two phases (24 webhooks / 25 mutating MCP — independent surfaces, `fine` granularity). Infrastructure kept as a standalone durable phase (26). Migration ordering enforced: V52 `shop_staff` (Phase 22) precedes V53 `media_asset` (Phase 23).

### Decisions

Decisions are logged in PROJECT.md Key Decisions table. Recent decisions affecting current work:

- [v2.3 Scope]: Vendor Ops + AI interleaved, thinnest/highest-pain first — onboarding (zero-migration) leads, then vendor-scoped access, image architecture, AI track, infra. Locked by user 2026-07-14; do not re-litigate.
- [v2.3 Roadmap]: MOBL-01 folded into Phase 22 — the responsive nav pairs with the shop-context switcher (same dashboard-nav surface).
- [v2.3 Roadmap]: AI track split 24/25 — outbound webhooks and mutating MCP are independent deliverables (issues #205 vs #204) on separately-shipped infra.
- [v2.3 Constraint]: onboarding-blocker path is zero-migration (`WITHDRAWN` already in V43 CHECK); derive "in review" at the DTO layer, no `IN_REVIEW` state migration.
- [Phase 21]: 21-01: POST /onboarding/withdraw reuses the already-wired WITHDRAW state-machine transitions (no SM change) via the canonical transition() path; terminal source -> RFC 7807 400; WITHDRAW never touches Shop.published.
- [Phase 21]: 21-01: company-number correction is POST /onboarding/company-number — a data edit firing NO state-machine event, gated to DRAFT/ACTION_REQUIRED (else RFC 7807 400), reusing create's @Size(32)+@Pattern verbatim; blank/whitespace = sole trader (null).
- [Phase 21]: 21-02: manual-review stall notification writes an onboarding.events row to the shared V46 outbox; exchange bean + producer + flusher dispatch shipped atomically (Pitfall 1) so the shared flusher never poison-casts it; unbound topic exchange (Phase 24 #205 delivers); emit only on MANUAL_REVIEW park, at-least-once; SM untouched, zero migrations.
- [Phase 21]: 21-03: vendor OnboardingDto derives reviewPending = VERIFYING && anyGate==MANUAL_REVIEW && noGate==PENDING at the single toDto site (D-03) and now carries rejectionReason (D-09); hand-built record (not MapStruct), zero migration.
- [Phase 21]: 21-03: admin gate-resolve (POST /onboarding/admin/{id}/gates/{gateType}/resolve, PASS|WAIVE|FAIL+reason) writes ONLY the gate row (Envers-audited) then kickGateChainAfterCommit — the existing recompute advances the SM (GATES_PASSED/GATE_FAILED); never writes status/published, never runs recompute inline (CR-01). Interim resolver = tenant's own admin (D-01).
- [Phase 21]: 21-03: admin review queue is a NEW GET /onboarding/admin/reviews (VERIFYING + MANUAL_REVIEW) — the /pending approve/reject contract is untouched (Incremental Betterment, A4). ONBD-03/05 NOT marked complete: the user-visible vendor-UI halves land in 21-04.
- [Phase ?]: 21-04: onboarding support channel + review SLA config-injected via frontend NEXT_PUBLIC_* (A1); resolveSupportChannel keeps mailto out of the component (GLOBAL_RULE_6)
- [Phase ?]: 21-04: admin review-pending queue is a separate section (parallel GET /reviews) with a PASS/WAIVE/FAIL gate-resolve dialog; approve/reject queue preserved (A4)
- [Phase 22]: 22-01: Order-email path frozen (Pitfall 5 path A) — EmailNotificationService + its SimpleMailMessage test untouched; all NEW events ride the MimeMessageHelper multipart EmailChannel. — Guarantees zero regression to the one working channel (Incremental Betterment).
- [Phase 22]: 22-01: NotificationChannel seam owns NO consent category (22-02 owns NotificationCategory); RecipientRole {CUSTOMER,VENDOR} is the audience axis. Keeps 22-01/02/03 parallel-safe. — Decoupled contract so Wave-1 plans do not share a type.
- [Phase 22]: 22-01: Marked only COMMS-07 complete; COMMS-02 left pending (shared with 22-04's dispatch, which delivers its Mailhog/recipient acceptance). — Avoids a false-green — COMMS-02 acceptance is unmet until 22-04.
- [Phase 22]: 22-03: webhook_subscription (V55) FORCE-RLS via current_tenant_id(); plaintext signing_secret returned once on create+rotate, never on GET/list; rotate regenerates via SecureRandom.
- [Phase 22]: 22-03: WebhookSubscriptionController mounts /api/v1/webhooks hard-coded (webhook pkg NOT in WebConfig.API_V1_PACKAGES; RefundController precedent) — keeps change inside webhook/*, no WebConfig edit.
- [Phase 22]: 22-03: vendor target_url HTTPS-only + SSRF-blocked (loopback/RFC1918/link-local/169.254.169.254/IPv6-ULA) via WebhookUrlValidator at create; toggle webhook.target.block-private-ranges default ON; RFC 7807 400.
- [Phase 22]: 22-03: OpenAPI snapshot regen DEFERRED to phase gate — committed snapshot already stale for Phase 21 + 22-02; webhook-only partial regen impossible, out-of-scope per SCOPE BOUNDARY (deferred-items.md).
- [Phase 22]: 22-04: order-audience wired additively — the new order.notifications path is VENDOR-ONLY so the untouched legacy customer path is not duplicated (COMMS-02 = customer + vendor, no double-email; Pitfall 5 path A)
- [Phase 22]: 22-04: bound the Phase-21 dead onboarding.events exchange + a refund.notifications queue on order.refunded + a second payment.notifications queue — each its OWN durable queue (never steals from an incumbent consumer); PaymentEventOutboxFlusher untouched (Pitfall 3, consumers only)
- [Phase 22]: 22-04: first-deploy onboarding-stall backlog re-delivery ACCEPTED with no cutoff (RESEARCH A5) — genuine unresolved stalls, ConsentGate still applies
- [Phase ?]: 22-06: webhook management UI in lib/webhooks-api.ts wrapping the default apiClient (api-client.ts untouched); grouped event-type checkboxes map one-per-backend-enum-family; once-only SecretRevealDialog blocks backdrop/Esc/X; cards below sm + Table at sm+ (375px); replay carries a secure Idempotency-Key
- [Phase ?]: 22-07: Public /unsubscribe is a server page.tsx (exports metadata.robots noindex,nofollow) wrapping a Suspense'd use-client content module; token/email sent to the API but never rendered into meta/body (PII-safe); route sitemap-excluded + link-graph-allowlisted (email-only entry).
- [Phase ?]: 22-07: Phase-gate docs reconcile = docs-freshness.sh --write (schema 56, total 1388) + gradle updateOpenApiSnapshot (+14 endpoints, 0 removed) -> docs-freshness EXIT=0; whole-repo artifacts reconciled once at the last plan.
- [Phase ?]: 22-07: Authenticated E2E = real Keycloak login + Playwright route() stubs (dashboard-mobile pattern); unsubscribe-flow 6/6 live green; webhook dashboard specs need E2E_VENDOR_PASSWORD for a live authenticated run (env creds unknown).
- [Phase 23]: 23-01: V52 ships shop_staff + shop_staff_aud + user_directory (D-09) all ENABLE+FORCE RLS via the safe current_tenant_id() helper (never the raw ::uuid cast); functional unique index over (tenant_id, user_id, COALESCE(shop_id, zero-uuid)); tables ship EMPTY (no migrate-time backfill — JIT is 23-02, RESEARCH §1-FLAG).
- [Phase 23]: 23-01: VSA-01 left PENDING (anti-false-green, mirrors 22-01) — data layer delivered, but its JIT-provision backfill + realm-admin implicit-GROUP_ADMIN bridge + JIT idempotency test are scoped to 23-02; VSA-01 closes in 23-02.
- [Phase 23]: 23-02: ShopAccessService is the single in-tenant enforcement seam (require/requireGroupAdmin/isGroupAdmin/grantedShopIds over a per-user shopMembership cache); typed shop-403 (/shop-access-denied) provably distinct from RLS 404 and generic 403; JIT provision + throttled directory upsert live inside the @Transactional service not JwtTenantFilter (Pitfall 4); strict-scoping default OFF preserves day-one auto-provision (D-12)
- [Phase ?]: 23-03: VSA-02 CLOSED — require(shopId,minRole) gates every shop-scoped write (deny-by-default) + read-scope narrows every list to grantedShopIds at the QUERY across Shop/Product/Order/Promotion/Announcement (+ label + bulk per-row + KDS SSE grant-set fan-out via shopId now on OrderStateChangeEvent); typed shop-403 proven distinct from RLS 404; system-principal bypass + read-only-tx write-skip harden the gate
- [Phase 23]: 23-05: shop-context switcher persists via localStorage['shopContext'] (theme-toggle idiom, D-07) + broadcasts a same-tab 'shopcontext:change' CustomEvent (browser storage event fires only cross-tab); subscribeShopContext is the seam 23-07's useShopContext consumes to narrow screens live. GA defaults to All-shops; apply-to-all gated on GA+all-context; non-GA single grant pinned; D-13 stale id degrades to 'all' not a crash.
- [Phase 23]: 23-05: MOBL-01 CLOSED verify-first (satisfied-by-prior-work) — Phase 19 Surface D already shipped the responsive shell; switcher integrated width-capped (max-w-[55%]+truncate), no reintroduced overflow, proven by 375px Jest+Playwright + live human-verify APPROVED + surface-ledger proof (drawer_authored:false). VSA-03 LEFT PENDING — its 'all shop-scoped screens operate on the selected shop' clause closes only in 23-07 (anti-false-green).
- [Phase 23]: 23-07: VSA-03 CLOSED — useShopContext() (hooks/use-shop-context.ts) is the single consumption point for the switcher; Products/Orders/Marketing/Kitchen narrow to contextShopId and react live to 'shopcontext:change'. Orders narrows SERVER-side via the gated ?shopId=; Products/Promotions/Announcements narrow client-side over the already grant-scoped page (their endpoints take no shop param — cosmetic pagination caveat, never a scope widening). contextShopId===null ("all") is a strict fall-through so the GROUP_ADMIN cross-shop view is byte-for-byte unchanged. Create-forms default AND pin their shop (D-08 single-shop writes); Kitchen's board derives from the global context (fetchShops no longer picks a shop) with the published-first fallback preserved. Hook hydrates in a mount effect (NOT a useState initialiser) to avoid an SSR hydration mismatch.
- [Phase 23]: 23-07: Closure proven at Jest (58/58 dashboard suites) + npm run build tsc level only — live browser/Playwright verification DEFERRED (low-footprint session after a desktop crash), recorded in 23-07-SUMMARY "Deferred verification"; run before the phase PR.
- [Phase ?]: 23-06: /dashboard/staff renders the server's typed 403 as the shared access-required card (finance idiom) and the last-GA 409 as a persistent inline notice — no confirm-gate on self-revoke, so the server guard stays reachable (D-10/D-11/D-13)
- [Phase ?]: 23-06 phase-gate reconcile: docs/metrics.json + CLAUDE.md + AGENTS.md counts moved 1456 -> 1511 (java 1010 / jest 357 / pw 40 / go 77 / mcp 27); docs-freshness check-mode exit 0. PROJECT.md's 1456 left intact — it describes unmerged main, not this branch
- [Phase ?]: [Phase 23]: 23-08: CR-03 fail-OPEN closed — isSystemPrincipal split into isInternalCaller() (auth==null only) + isDeclaredMachineClient() (non-UUID sub AND azp/client_id in an explicit, empty-by-default machine-client-ids allowlist); anonymous/non-Jwt/unparseable-subject request principals now DENIED with typed 403, never escalated to GROUP_ADMIN (D-04 true in code). currentUserId() 500 replaced by requireVendorUserId() typed 403.
- [Phase ?]: [Phase 23]: 23-08: CR-04 closed — require(null,role) guards the null shop BEFORE the ImmutableCollections.MapN.get(null) NPE; null shopId = tenant-wide/unassigned resource, WRITE is GROUP_ADMIN-only (typed 403), READ half owned by plan 23-09 (pairing written into require() javadoc so halves cannot drift).
- [Phase ?]: [Phase 23]: 23-08: auth==null internal bypass RETAINED deliberately (measured blast radius 62 no-principal test files; not externally reachable — Spring Security 401 before any gated service). asSystem() ThreadLocal marker + StaffController @PreAuthorize scope backstop DEFERRED with reason. Proven by ShopAccessFailClosedIntegrationTest (7 cases, RED pre-fix on 1-4+7; 24 Phase-23 integration tests green).
- [Phase 23]: 23-10: CR-01 closed — the shop-access gate was structurally INSIDE the @Cacheable getShopById/getProductById body, so a warm per-tenant (user-agnostic) cache entry served data to any other tenant user without re-running require(). Fix: extract the cached load onto dedicated ShopCacheLoader/ProductCacheLoader beans (nested public static @Component reached through the Spring proxy, NOT a self-invocation — WR-01) and run require() in the public method on EVERY call, outside the cache boundary. Cached method NAMES kept (getShopById/getProductById) so the cache key + all 13 TenantCacheEvictor call sites are byte-for-byte unchanged; @Cacheable stays textually in the service files. Proven by a caching-ENABLED, two-different-scoped-user Testcontainers test that supplies its own CacheManager + tenantAwareCacheKeyGenerator via a nested @EnableCaching @TestConfiguration (defeating the @Profile("!test") blindness) and asserts cache population before denial; RED demonstrated pre-fix (userY got the DTO). WR-08 null-shop READ policy (pairs with 23-08 GROUP_ADMIN-only WRITE half — surfaced for user acceptance): scoped users see legacy shop_id IS NULL products via EXPLICITLY tenant-scoped finders with load-bearing parentheses (tenant_id AND (shop_id IN (:ids) OR shop_id IS NULL)) — RLS-bypass (table-owner) would otherwise leak cross-tenant null-shop rows; zero-grant users still see nothing. WR-07: malformed CSV shop_id → per-row 400, not a 403.
- [Phase ?]: [Phase 23]: 23-09: grant() reshaped to a session-based Hibernate write (not native ON CONFLICT) so Envers audits create AND role-change (WR-02); a DIFFERENT-role re-grant now APPLIES the change instead of silently no-opping while reporting success (CR-05). Concurrent duplicate insert isolated in REQUIRES_NEW + caught -> typed replay, never a 500. Last-GROUP_ADMIN check-then-act serialized by ShopStaffRepository.lockTenantGroupAdmins PESSIMISTIC_WRITE over shop_id IS NULL GROUP_ADMIN rows (== the counted set, since shop-scoped GROUP_ADMIN grants are rejected) in BOTH revoke() and the grant() downgrade path (CR-06). IN-03 two 409 messages extracted to constants, downgrade/revoke variants kept distinct for 23-13/IN-02. VSA-04 stays PENDING (23-12/13/14/15 still contribute). JIT insertGroupAdminIfAbsent still bypasses Envers -> handed to 23-14.
- [Phase 23]: 23-12: THREE staff-backend findings closed together (shared surface). WR-05: grant() validates shopId (tenant-scoped ShopRepository.findByIdAndTenantId — FK can't, Postgres RI bypasses RLS) + userId (user_directory membership) BEFORE the D-11 guard/write; foreign-tenant == non-existent 404 (no oracle). CR-08 backend: GET /api/v1/staff/me + MyAccessDto — NOT requireGroupAdmin-gated, readOnly (onRequest early-returns), empty-set sentinel resolved at DTO (groupAdmin=true→grantedShopIds=null unrestricted; false→exact possibly-empty set); proves the day-one implicit-GA case the client realm-parse gets wrong (consumed by 23-13). WR-10: DirectoryEntryDto masks email (a***@example.com, full value server-side only) + GdprService erases user_directory by tenant_id+email (no _aud, D-09 → tenant-scoped DELETE; zero-match is normal, ErasureRecord unchanged). Grants key on userId not email. Staff surface now FOUR endpoints → 23-15 OpenAPI regen. Falsifiability RED shown pre-fix. VSA-04 stays PENDING.
- [Phase 23]: 23-13: CR-08 FRONTEND closed — `fetchMyShops` sources `isGroupAdmin` + `userId` from `GET /api/v1/staff/me` (23-12's MyAccessDto); the client JWT parse (`decodeJwtPayload`/`isGroupAdminFromSession`) is DELETED. Silent-pin regression gone: the switcher persists ONLY the D-13 stale correction, so the day-one implicit GROUP_ADMIN (not a realm admin) lands on All-shops and can re-select it — the set who LAND on "all" == the set who can SELECT it == the server's answer. WR-06: one `ShopSwitcherProvider` (mounted once in dashboard-shell above both switchers) = single fetch + single hydration writer — chosen over a module-level cached promise BECAUSE it also owns the sole hydration write (a cached promise leaves two effects both attempting the stale correction); both switchers read selection from `useShopContext()` and converge live; renders no DOM so the MOBL-01 375px shell markup is byte-for-byte unchanged. WR-12: staff `isSelf` compares the Keycloak `sub` (userId) not a session email (23-12 masking made the email compare impossible); `useSession` removed from the page. IN-02: grant-path 409 downgrade copy distinct from the revoke path. Revocation-timing copy states the real 5-min SSE bound (23-11), not blanket immediacy. Raw-UUID grant fallback → 'Unlisted member' (23-14 owns the richer JIT treatment). Falsifiability RED shown pre-fix (5/11 switcher, 4/10 staff); 156/156 dashboard+hooks Jest, npm run build (tsc) green after every task. VSA-03/VSA-04 stay NOT-complete (anti-false-green — 23-14 + 23-15 still contribute). 375px Playwright deferred (E2E_VENDOR_PASSWORD + frontend rebuild).
- [Phase 23]: 23-14: CR-07 CLOSED (Task 0 = user ACCEPT of the D-04/D-12/D-05 revision; full path incl. bootstrap rule). Enabling strict-scoping now GENUINELY tightens: V57 records shop_staff.grant_source (JIT|OPERATOR) + aud mirror (backfill created_by IS NULL→JIT, NOT NULL DEFAULT 'JIT', NO RLS policy → RlsContractTest green). Under strict ON a JIT-sourced tenant-wide GROUP_ADMIN is DE-HONOURED (a day-one user genuinely becomes scoped) while OPERATOR grants + realm admins are honoured unchanged. The policy lives in the shared isGroupAdminForUser decision helper, applied OUTSIDE the cached Membership snapshot (which carries only the raw groupAdminFromJit fact) so a strict-scoping flag change is never served stale — and BOTH the HTTP gate and the STOMP canAccessShop ladder tighten at once. Lockout safety: the oldest JIT admin (created_at,id) is retained as a WARN-logged bootstrap ONLY when no OPERATOR tenant-wide GROUP_ADMIN exists. WR-09: onRequest skips the JIT provision + directory upsert for an allowlisted machine client via isAllowlistedMachineClient (azp/client_id, subject-shape-INDEPENDENT — so a UUID-sub Keycloak service account is caught, unlike isDeclaredMachineClient). WR-01: the D-05 membership cache is now real — internal gate calls reach @Cacheable resolveMembership through the bean proxy (ObjectProvider self()), proven by a caching-enabled test (entry POPULATED post gate-call, serves stale until evict, then re-resolves + denies). WR-11: JIT-provision eviction fires AFTER commit via a single shared evictMembershipAfterCommit helper used by BOTH onRequest and StaffManagementService (grep registerSynchronization==1, no drift). Membership + Task-2 fields round-trip through the exact CacheConfig JSON serializer (unit-proven). Operator provenance stamped at persistNewGrant + role-change; same-role replay is a documented no-op. Staff screen labels JIT rows 'Auto-granted on first sign-in' (no layout shift, 23-13 preserved). Proven vs real Postgres: StrictScopingTightening 5/5 (RED pre-fix 4/5 — CR-07 central proof), Enforcement 12/12, CacheBypass 5/5, StaffManagement 19/19, FailClosed/JitProvision/ErrorType/RlsPolicy/RlsContract green; frontend jest 93/93 + build green. VSA-02/VSA-04 stay PENDING (anti-false-green — 23-15 phase-gate docs/OpenAPI reconcile still contributes). DEFERRED to 23-15: docs/metrics.json (schema 56→57; +9 Java @Test, +1 Jest) + updateOpenApiSnapshot; bulk-revoke-JIT staff affordance (convenience, not a boundary).
- [Phase ?]: [Phase 23]: 23-11: CR-02 closed — the KDS STOMP transport (/topic/kitchen/{tenant}/{shopId}) is now shop-gated at SUBSCRIBE. New ShopAccessService.canAccessShop(tenantId,userId,realmAdmin,shopId) decides shop-read from EXPLICIT params (never SecurityContextHolder, which on the STOMP thread takes 23-08's retained internal-caller bypass and fails OPEN); identity from accessor.getUser() + a local realm_access.roles re-parse (CONNECT applies no authority conversion). isGroupAdmin() and canAccessShop() share one private isGroupAdminForUser ladder so HTTP and STOMP cannot drift. No writes on subscribe (no onRequest/JIT); tenant-pin fail-closed guard blocks an unpinned RLS GUC reading as implicit-GROUP_ADMIN under strict-OFF; TenantContext cleared in a finally on the pooled inbound thread. Day-one preservation proven end-to-end vs real Postgres; falsifiability RED shown pre-fix. WR-03 (post-revocation SSE 5-min window) accepted→23-14; staff-page copy correction→23-13.

### Pending Todos

- After v2.3 work pauses/completes: re-count the remediation backlog (`gh issue list --label remediation --state all`) — HANDOFF Step 2.
- Then (LAST): run the comprehensive QA audit with the upgraded charter (lifecycle dead-end sweep + role-spanning journey matrix) — HANDOFF Step 3. Rebuild ALL containers first.

### Blockers/Concerns

- **🛑 23-15 PHASE-GATE BLOCKED (2026-07-21): full `:core-java:integrationTest` is RED — 13 failures the phase record never disclosed.** 7 legacy test classes fail `expected 200/201/400 but was 403`: `ShopControllerIntegrationTest` (3), `LocationHeaderContractTest` (4), `ScopedCatalogAccessIntegrationTest` (2), `SecurityHeadersIntegrationTest` (1), `ProductSearchFtsIntegrationTest` (1), `OnboardingGoLiveIntegrationTest` (1), `TenantLifecycleAdminIntegrationTest` (1). **Root cause:** 23-08's CR-03 fail-closed change now DENIES authenticated non-JWT principals — `ShopAccessService.requireVendorUserId()` throws a typed 403 when `auth.getPrincipal()` is not a `Jwt`, and `isInternalCaller()` bypasses only `auth == null`. The 19 pre-existing `@WithMockUser` integration tests (non-JWT `UsernamePasswordAuthenticationToken`) were never migrated to the UUID-subject `.jwt()` pattern the new access suites use. **Deterministic** — `ShopControllerIntegrationTest` fails 3/6 in isolation, identical to the full run (not parallelism). The gap wave only ran scoped `--tests` runs, so the full task was never green; the STATE claim "only OpenApiSnapshotTest is red" was inaccurate. 23-15 Task 2 (count reconcile) + Task 3 (VSA-02/VSA-04 completion) are HELD per anti-false-green — reconciling counts / marking requirements complete over a red suite is the exact green-by-construction pattern this phase's verification caught. **Decision needed** (architectural/semantic, Rule 4, outside this docs-reconcile plan's scope): migrate the ~7 legacy `@WithMockUser` test classes to UUID-subject JWTs (test-only), OR reconsider whether 23-08's fail-closed on authenticated-non-JWT principals is too broad. 23-15 Task 1 (OpenAPI snapshot regen) is DONE + green (commit `adc1c58`, `OpenApiSnapshotTest` passes in check mode).
- **RULE 0 — one runtime at a time on local**: compose and the minikube `jtoye` cluster share one dev Postgres. Never run compose `core-java`/`edge-go` AND cluster core/edge writers at once. Compose is canonical; cluster is STOPPED at handoff.
- **Rebuild-all rule**: after ANY code change, rebuild ALL containers before E2E/QA. Cluster core is a pre-V51 image tag — re-tag + `minikube image load` fresh images before any k8s redeploy.
- **Phase 23 vision provider**: content-relevance gate (IMG-03 stage 6) needs Ollama (host :11434 conflict) or a hosted model — ships behind an advisory-default flag; the pipeline is not blocked on it.
- **Phase 26 netpol caveat**: minikube's default CNI does NOT enforce NetworkPolicies — local is not proof for netpol behaviour (needs policy-enforcing CNI or AKS).
- Phase 23 PR will fail CI: docs/api/openapi-snapshot.json lacks the /api/v1/staff endpoints — now FOUR after 23-12 (list, /me, /grant, /{id}). Run ./gradlew :core-java:updateOpenApiSnapshot and commit the diff (needs Docker/Testcontainers). Owned by 23-15's phase-gate reconcile. Scoped `--tests` runs stay green; only the full `integrationTest` task is red on OpenApiSnapshotTest until regen.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260715-fcq | Reconcile stale docs to current state (milestone identity → v2.3; test count 1257→1401; schema V51→V56; incl. AGENTS.md mirror) | 2026-07-15 | aed0929 | [260715-fcq-reconcile-stale-project-docs-to-current-](./quick/260715-fcq-reconcile-stale-project-docs-to-current-/) |

## Session Continuity

Last session: 2026-07-21T10:23:32.333Z
Stopped at: Completed 23-14-PLAN.md — gap-closure CR-07 strict-scoping + WR-09 + WR-01/WR-11
Resume file: None
