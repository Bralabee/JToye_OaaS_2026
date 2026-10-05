# Phase 37: Real-world operations readiness - Context

**Gathered:** 2026-10-05
**Status:** Ready for planning

<domain>
## Phase Boundary

A real vendor can run a Friday service, a multi-site business and a third-party integration on J'Toye, and a real customer can order safely, without the product claiming anything its data does not back.

**Scope, as narrowed in this discussion (D-01):** Phase 37 plans the **29 P0/P1 persona clusters** that sit in sub-themes 37-A..37-E and 37-G, **plus all six 37-F accessibility clusters** (P2, kept in by D-05). The remaining P2/P3 clusters of the 87 go to a follow-up decimal phase (37.x, planned later). The source list is `catalogue.json` filtered on `subTheme ^= "37"` and priority P0/P1, giving 29 clusters. The sub-theme counts below are measured from that file.

| Sub-theme | Clusters in Phase 37 (issue) |
|---|---|
| 37-A Kitchen (4) | UXT-011 #786 (P0), UXT-020 #795, UXT-021 #796, UXT-022 #797 |
| 37-B Staff access & finance (7) | UXT-003 #779 (P0), UXT-004 #780 (P0), UXT-018 #791 (P0), UXT-026 #799, UXT-027 #800, UXT-046 #815, UXT-047 #816 |
| 37-C Checkout integrity (4) | UXT-007 #783 (P0), UXT-008 #792, UXT-029 #802, UXT-044 #813 |
| 37-D Abuse (5) | UXT-005 #781 (P0), UXT-006 #782 (P0), UXT-039 #808, UXT-041 #810, UXT-042 #811 |
| 37-E Integrators (7) | UXT-016 #790 (P0), UXT-017 #727 (P0), UXT-035 #805, UXT-036 #806, UXT-037 #807, UXT-038 (comment on #587), UXT-040 #809 |
| 37-F Accessibility (6, all P2) | UXT-080..UXT-085 (#849..#854) |
| 37-G Catalogue & shop admin (2) | UXT-023 #798, UXT-028 #801 |

Every P0 that the roadmap says gates Phase 32 is in scope: #779 #780 #781 #782 #783 #786 #790 #791 #727.

**Roadmap success criteria this changes.** `/gsd-plan-phase 37` must re-state them against this scope:
- SC-1's "a double-tap cannot skip a status" is UXT-049, a P2, so it moves to 37.x.
- SC-4's "a vendor can bulk-reject junk orders" is a means for UXT-041 and stays (D-21).
- SC-6 stays (D-05).

Do not plan a criterion for a cluster that moved to 37.x.

</domain>

<decisions>
## Implementation Decisions

### Phase shape and order
- **D-01:** **Phase 37 covers the P0/P1 clusters only. P2/P3 go to a 37.x follow-up phase,** except 37-F (D-05). The 87-cluster scope is about three times any earlier phase, and only the P0/P1 set gates Phase 32 (a first tenant).
- **D-02:** **37-B (staff access) goes first.** Every other sub-theme's tests assert "staff see only what they were granted", so they must be written against the new default (D-06), not the old one.
- **D-03:** **One PR per sub-theme.** Each runs its own D3 review series to zero admissible findings and merges independently. The phase completes when all its sub-theme PRs have merged.
- **D-04:** **37-C waits for Phase 31.1 to merge.** 31.1 D-05/D-06 change checkout submit and the order's allergen record. 37-A, B, D, E, F and G may run in parallel with 31.1. Like 31.1 (D-21), Phase 37 is written against the Phase 38 tree (Boot 4.1 / Jackson 3) once that is on `main`. Merge `main` before executing each sub-theme.
- **D-05:** **37-F accessibility stays in Phase 37 despite being all P2.** A screen-reader customer who cannot follow their order is blocked on a core journey, and it is an Equality Act 2010 exposure before a real tenant. It is small and contained, and ships as its own PR.

### Staff access (37-B)
- **D-06:** **Ungranted means no access: `jtoye.access.strict-scoping` defaults to `true` in every profile.** This uses the existing V57 off-ramp: no new automatic provisioning, automatically created tenant-wide GROUP_ADMIN rows are no longer honoured, OPERATOR grants and realm admins still are, and the oldest automatic admin is kept as a WARN-logged bootstrap so no tenant locks itself out. Compose and the k8s overlays must not override it back to false. — **Reversibility:** costly — the moment the default flips, users who only held an automatic grant lose access; flipping back silently re-escalates them, so it needs an owner ruling, never a config edit.
- **D-07:** **Staff are onboarded by email invite, and the invite carries the grant.**
  - The Group admin enters an email, a role and a shop (or all shops).
  - The person follows the link, registers or signs in, and lands with exactly that grant (OPERATOR grant source).
  - Self-registering without an invite yields no access (D-06).
  - The invite is tenant-scoped, single-use and expires. It is sent through `EmailNotificationService`.
- **D-08:** **Revoking the last grant ends access on the next request.**
  - Evict the per-user membership cache (the existing D-05 per-user eviction in `ShopAccessService`).
  - The next API call returns a typed 403, and the dashboard shows a clear "You no longer have access" page.
  - The Keycloak session is NOT ended (no dependency on `KeycloakAdminClient` for revoke).
  - Proven with the staff member's own token through the API (roadmap SC-2).
- **D-09:** **The Staff page lists everyone who has signed in to the tenant (from `user_directory`) with their actual effective access, including "No access" with a Grant button.** The effective access shown is read from the same source `ShopAccessService` decides with, never derived separately. This addresses theme 2: say ≠ data.
- **D-10:** **A COMPLETED (or any post-PENDING) order can no longer be deleted. Only DRAFT/PENDING orders are deletable. Anything later is voided instead.**
  - Delete returns a typed 409 once the order is CONFIRMED or later.
  - A new Group-admin-only "void" requires a reason. It keeps the order, writes a reversing ledger entry, and leaves an audit trail.
  - The ledger can never reference an order that returns 404.
  - — **Reversibility:** one-way — a void and its reversing ledger rows are financial records and cannot be un-written once vendors use them.

### Kitchen order model (37-A)
- **D-11:** **PENDING orders appear on the kitchen board in a "New" lane, and Accept there moves them to CONFIRMED.** The kitchen no longer depends on someone confirming on the Orders page. The vendor still accepts every order; there is no auto-confirm.
- **D-12:** **The new-order alert repeats until the order is accepted.**
  - A looping sound every few seconds, a flashing card, and a tab title like "(2) New orders".
  - A mute is honoured and is visibly shown as muted.
  - Browsers allow audio only after a user gesture, so the board needs an explicit "Enable sound" tap at the start of a shift.
  - No push notifications in this phase.
  - The kitchen card must show the customer's notes and the fulfilment type (UXT-011, P0). The printed ticket already does.
- **D-13:** **MCP `create_order` produces a PENDING order, like the storefront,** so it lands in the New lane and emits the same events. The tool description states the resulting status. No `submit_order` tool. This is part of theme 6 (one domain path for every writer).
- **D-14:** **When the session lapses, the kitchen board enters a loud "board stopped" state, never a quiet redirect.**
  - A full-screen red "Signed out: new orders are NOT showing" with an alarm sound.
  - After sign-in, return to `/dashboard/kitchen`.
  - The board refreshes its token silently while the SSO session lasts.
  - Realm session lifetimes are NOT changed.
  - A lapsed session, a muted handover or an unreachable API is never shown as "Live" (roadmap SC-1).
- **D-15:** **A one-tap pause (20 min, 40 min, or until reopened) plus structured opening hours.**
  - The pause is enforced server-side by both the storefront and checkout, with a customer-facing message.
  - Free-text hours become structured open/close times per day.
  - Empty or unparseable hours read as CLOSED, not open. This reverses `PublicStorefrontService.validateShopIsOpen`'s fail-open.
  - Existing free-text hours need a migration path. Each existing shop's rendered behaviour must not silently change; any shop whose hours cannot be parsed is surfaced to its vendor.
  - — **Reversibility:** costly — the hours data model changes for every shop.

### Checkout integrity and abuse (37-C, 37-D)
- **D-16:** **Checkout re-validates the basket at submit: the client sends what it showed, and the server refuses with a typed diff.**
  - Submit carries the unit prices and total the customer saw.
  - On any change (price, availability, sold out, minimum), the server returns a typed RFC 7807 409 listing each change.
  - The UI shows each change (for example "Suya £9.00 → £14.00") and requires re-confirmation.
  - No server-side quote store. This is deliberately the same shape as 31.1 D-05's stale-acknowledgement refusal, and must be built to compose with it, not beside it.
- **D-17:** **UXT-008: stop rendering any promotion that the order path does not apply.** A real promotions engine is a separate capability (Deferred). This is the theme-2 fix (say ≠ data): no claim without data behind it.
- **D-18:** **Cash checkout is bounded by per-identity caps plus an "unverified contact" flag. No captcha, and no email verification gate.**
  - Caps on open cash orders per email, per phone and per client IP. The limits are config-declared, never literals.
  - Over the cap, the server returns a typed 429.
  - Guest orders show an "unverified contact" badge on the kitchen board.
  - Vendors can bulk-reject junk orders (roadmap SC-4).
  - A third-party captcha is rejected because it conflicts with the cookie policy.
- **D-19:** **Client IP comes from a config-declared trusted-proxy CIDR list, and the default trusts none.**
  - `X-Forwarded-For` is read only when the immediate peer is a trusted proxy, taking the right-most untrusted hop. Otherwise the socket peer address is used.
  - The k8s overlays declare their ingress range.
  - This fixes `ClientIpResolver` (UXT-006), which is a regression of closed #88.
  - The edge gateway's single process-wide bucket (UXT-039) moves to per-tenant limiting after auth, with a typed 429, `Retry-After` and rate headers.
- **D-20:** **Item quantity gets a per-line ceiling and a basket-total sanity cap** (UXT-042), both config-declared. They are enforced in the domain service that every writer calls, not only on the storefront DTO (theme 6).
- **D-21:** **A review is accepted only when the cited order belongs to the reviewed shop** (UXT-005, `order.shopId == shop.id` in `ReviewService`).

### Integrator credentials (37-E)
- **D-22:** **A credential is a Keycloak service-account client created per credential through `KeycloakAdminClient`.**
  - Scopes come from the existing #206 client scopes (`docs/security-scopes.md`).
  - A hard-coded `tenant_id` claim mapper ties each client to the issuing tenant.
  - The secret is shown once, and can be rotated and deleted.
  - Tokens stay ordinary JWTs, so core-java, edge-go and the MCP server need no new authentication path.
  - Depends on `KeycloakAdminClient` as changed by Phase 38 (Jackson 3) and armed in compose by 31.1 D-22. Re-prove the admin calls on the merged tree.
  - Platform-issued API keys in Postgres were rejected: a second auth path that every gate would have to honour.
  - — **Reversibility:** costly — the credentials vendors issue live in Keycloak; changing mechanism means re-issuing every integrator's credential.
- **D-23:** **A credential's service-account user gets an ordinary `shop_staff` grant** (a role, plus a shop or all shops), so `ShopAccessService` limits it exactly like a person. This composes with D-06.
- **D-24:** **Only a Group admin can create, rotate or revoke a credential.**
  - Each action records who did it and when.
  - The Developers page lists every credential with its scopes, shops and last-used time.

### Claude's Discretion
- How each remaining in-scope cluster is fixed, where the discussion did not settle a design choice: UXT-016 (partial update / PATCH semantics), UXT-017/#727 (sync requires a shop), UXT-026, UXT-027, UXT-029, UXT-037, UXT-038, UXT-040, UXT-044, UXT-046/047 (VAT-rate choice and registration status), UXT-023, UXT-028, and the 37-F fixes. Each must be shown to FAIL on the pre-fix tree, and must keep the 40 entries in `goods-to-preserve.md`.
- The exact config keys and default values for the D-18 caps and D-20 limits.
- Invite expiry and link lifetime (D-07).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Scope source
- `.planning/ROADMAP.md` § "Phase 37: Real-world operations readiness" — the goal, the draft success criteria (re-state per this file's Phase Boundary) and the Phase 32 gating list
- `.planning/ux-persona-test-20261003-pass2/consolidated/CATALOGUE.md` — every cluster's repro, expected/actual, verified code location; § "Cross-cutting themes" 1, 2, 3, 5, 6 are the systemic fixes D-06, D-09, D-11, D-13, D-16, D-17 and D-20 implement
- `.planning/ux-persona-test-20261003-pass2/consolidated/catalogue.json` — machine-readable clusters (`subTheme`, `priority`); the D-01 scope filter
- `.planning/ux-persona-test-20261003-pass2/consolidated/filed-issues.json` — cluster → GitHub issue mapping
- `.planning/ux-persona-test-20261003-pass2/consolidated/goods-to-preserve.md` — 40 behaviours every plan must keep (Incremental Betterment Doctrine; roadmap SC-7)
- `.planning/ISSUE-DISPOSITION.md` § "Persona user-testing 2026-10-03" — what went to 31.1 and to Phases 29/30/32/33/34 instead

### Adjacent phases
- `.planning/phases/31.1-persona-gap-closure/31.1-CONTEXT.md` — D-05/D-06 (stale acknowledgement refused; acknowledged mask recorded) that D-16 composes with; D-21/D-22 (Phase 38 first; `KC_ADMIN_ENABLED=true` in compose) that D-22 here depends on
- Phase 38 (Spring Boot 4.1) plans on branch `phase-37-spring-boot-4-1` — the Jackson 3 goldens; `KeycloakAdminClient` changes

### Architecture constraints
- `docs/security-scopes.md` — the #206 client scopes D-22 issues
- `docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md` — order/money flow context for D-10 and D-11
- `core-java/src/main/resources/application.yml` §`jtoye.access` (lines ~178-193) — the strict-scoping switch and the V57 off-ramp semantics D-06 flips

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java`: the JIT provisioning, strict-scoping de-honouring, bootstrap-admin retention and per-user cache eviction. D-06, D-08, D-09 and D-23 all run through it.
- `core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClient.java`: the admin API client used by D-22 (and by D-07 if the invite needs user lookup).
- `core-java/src/main/java/uk/jtoye/core/notification/EmailNotificationService.java`: sends the invites (D-07).
- `frontend/app/dashboard/kitchen/page.tsx`: the board. It lists CONFIRMED/PREPARING/READY, beeps only on CONFIRMED, and renders no `order.notes`. The print `kitchen-ticket.tsx` does render notes.
- `frontend/app/dashboard/staff/page.tsx`: the Staff page (D-09).
- `mcp-server/src/tools/create-order.ts`: the DRAFT-producing tool (D-13).

### Established Patterns
- **RFC 7807 typed errors and the Idempotency-Key contract** on mutating endpoints. They apply to the D-10 void, D-16's 409, the D-18/D-19 429s and the D-22 credential endpoints.
- **Every new tenant table has ENABLE+FORCE RLS through `current_tenant_id()`, plus a NOSUPERUSER proof** (the invite and credential-audit tables). `RlsContractTest`'s schema walk catches a missing policy.
- **Config-declared limits, never literals** (D-18, D-19, D-20).
- **Theme 6: validation lives in the domain service every writer calls.** Storefront, vendor API, MCP, `/sync/batch` and CSV import must refuse the same things (roadmap SC-5).

### Integration Points
- `core-java/src/main/java/uk/jtoye/core/security/ClientIpResolver.java:32,47`: the first-hop XFF trust (D-19).
- `core-java/src/main/java/uk/jtoye/core/order/OrderService.java:599` `deleteOrder`: the SHOP_MANAGER-only check with no status guard (D-10).
- `core-java/src/main/java/uk/jtoye/core/review/ReviewService.java:70-95`: the missing shop check (D-21).
- `core-java/src/main/java/uk/jtoye/core/storefront/dto/GuestOrderItemRequest.java:13`: `@Min(1)` only (D-20).
- `core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java` `validateShopIsOpen`: fail-open hours (D-15).
- `core-java/src/main/java/uk/jtoye/core/sync/SyncService.java`: never sets `shopId` (#727).
- `edge-go/internal/`: the process-wide pre-auth rate limiter (D-19, UXT-039).

</code_context>

<specifics>
## Specific Ideas

- The kitchen persona (Tunde) and the Friday-rush persona (Funmi) define what "loud" means. The alert must be unmissable in a noisy kitchen. A single chime was rejected.
- D-16's change diff must name the item and both prices, for example "Suya £9.00 → £14.00". UXT-007's repro (vendor reprices while the checkout page is open) is the failing-direction test.
- Status claims read from the same source as the action (theme 2). The Staff page "No access" row (D-09) and the kitchen "Live" indicator (D-14) are the in-scope instances.

</specifics>

<deferred>
## Deferred Ideas

- **Phase 37.x (P2/P3 persona clusters, plan after 37):**
  - the 50 remaining 37-A..37-E/37-G clusters at P2/P3 (measured: `catalogue.json`, subTheme 37-* excluding 37-F, priority not P0/P1), including UXT-049 (double-tap skips a status), UXT-050 (mute survives sign-out) and UXT-052 (unanswered orders pending forever, i.e. an auto-reject timeout);
  - the two unassigned clusters UXT-120 and UXT-121 (#873, #874).
- **A real promotions engine** (D-17): advertised discounts applied and recorded server-side. A new capability, so its own phase.
- **Web Push notifications to the owner's phone for new orders** (D-12). Needs a service worker and VAPID keys.
- **Ending the Keycloak session on revoke** (D-08).
- **Longer vendor-realm SSO lifetime for shift tablets** (D-14).
- **A per-tenant strict-scoping switch** (D-06 chose a global default).
- **Email verification gating cash orders** (D-18 chose caps and a flag).

</deferred>

---

*Phase: 37-real-world-operations-readiness*
*Context gathered: 2026-10-05*
