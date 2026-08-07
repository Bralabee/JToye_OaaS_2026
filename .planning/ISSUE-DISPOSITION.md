# Issue disposition — all 57 open issues

**Measured 2026-08-07.** Every open issue on this board has exactly one row below. There is no
"miscellaneous" bucket: an issue is assigned to a phase, deferred with a reason and the condition
that would revive it, or named as immediate work.

## Why this document exists

`ROADMAP.md` described Phases 28–32 as the go-to-market closure track and read as complete. It was
not, and the gap was invisible because nothing measured it:

```
open issues                                        57
  named anywhere in .planning/ROADMAP.md           15
  not named                                        42
```

Digit-boundary match (`#N` not followed by a digit — a substring match scores `#11` against `#116`),
falsified in both directions before being trusted: `#999999` → 0 files, `#427` → 5 files.

Six of the 42 appear in **zero** files anywhere under `.planning/` (`rg -uu`, same control):
**#453, #460, #461, #544, #462, #507**. Four of those are P1, and all four were filed from the owner
using the running application — the highest-signal source on the board had the least planning
coverage.

**Re-run before trusting any number here:**

```bash
gh issue list --state open --limit 300 --json number --jq '.[].number' | sort -n > /tmp/open.txt
while read -r n; do
  grep -qE "#$n([^0-9]|\$)" .planning/ROADMAP.md || echo "unnamed: $n"
done < /tmp/open.txt
```

`--limit` defaults to **30** and silently undercounts. Always pass it.

> ⚠ **Verify your control token is absent BEFORE using it as a control — including from this file.**
> The snippet above sweeps `ROADMAP.md` only, and the six-digit token it uses is written into this
> document one line higher. So a two-file sweep (`ROADMAP.md` **+** `ISSUE-DISPOSITION.md`) finds the
> control token, returns rc=0, and reports "found" for a number that is not an issue — a control that
> cannot say *no* is not a control.
>
> This fired **twice in five minutes** while the document was being written. The first time, the
> documented token was already present. The fix was to name a second, fresh token in a warning
> about the first — which wrote *that* token into the file too, and the control failed identically
> on the next run. Both times only the control's own failure revealed it; the coverage number looked
> plausible on both runs.
>
> The rule, therefore, is not "use token X". It is: **a verification example and the material it
> verifies must not share a namespace** — so pick a token, grep for it, confirm rc=1 on every file
> in the sweep, and do not write it down here.

---

## Summary

| Disposition | n | Blocks a first paying tenant? |
|---|---:|---|
| Phase 28 — Security Triage + the Dev/Prod Boundary | 9 | yes (gates 29) |
| Phase 29 — Deployable Staging, With Its Own Monitoring | 12 | yes |
| Phase 30 — The Money Path, Executed | 5 | yes |
| Phase 31 — Consumer-Safety and Legal Floor | 3 | yes |
| Phase 32 — Production Cutover + First Tenant | 1 | — (is the tenant) |
| **Phase 33 — The Consumer Product** *(new)* | 9 | yes |
| **Phase 34 — Rendering + Test Truthfulness** *(new)* | 6 | no |
| Deferred, with a dated reason and a revival condition | 5 | no |
| Post-GTM hardening backlog | 6 | no |
| Immediate — a shipped defect, no phase needed | 1 | no |
| **Total** | **57** | |

Two phases are new. That is the honest outcome of the sweep rather than scope creep: 16 issues had
no home, and they cluster along two clean seams — *what a consumer experiences* and *whether the
test suite is telling the truth*. Naming them is what makes the rest of the roadmap's silence
readable.

---

## Phase 28 — Security Triage + the Dev/Prod Boundary (9)

Six were already in scope; three are added because they are the same defect class and are cheapest
fixed together.

| # | P | Title | Note |
|---|---|---|---|
| 548 | P2 | [SEC-02] Pentest findings: disposition of all 11, and the SEC-01 re-verification of A1 | *is* SC-1 + SC-2 |
| 549 | P2 | SEC-02/C3: the API contract is unauthenticated on staging | pairs with SC-3 |
| 551 | P3 | SEC-02/E1: nobody has audited which Keycloak clients can mint the core-api audience | |
| 552 | P2 | SEC-02/B1 remainder: rotate credentials read during the pentest; stop running as table owner | SC-4's rotation half |
| 283 | P2 | Replace the retained `auth == null` internal bypass with an explicit `asSystem()` marker | already named |
| 284 | P2 | `@Async` / `@Scheduled` / `@RabbitListener` propagate no SecurityContext | already named |
| **270** | P2 | MinIO bootstrap runs an unpinned `minio/mc` with root credentials on the backing network | **added** — same local-stack surface as SC-4 |
| **281** | P3 | Revoked user's open KDS SSE stream lingers until connection turnover (≤5 min) | **added** — bounded; decide fix-or-accept |
| **488** | P2 | Existing image objects still hold raw bytes, EXIF GPS and client-declared Content-Type | **added** — #445 was forward-only; needs a backfill decision |

`#548`, `#549`, `#551` and `#552` were filed 2026-08-05, four days after the roadmap was written, which
is why SEC-02 describes them without naming them. They are the criterion, not new scope.

## Phase 29 — Deployable Staging, With Its Own Monitoring (12)

| # | P | Title | Note |
|---|---|---|---|
| 99 | P2 | [P2-8] CI/CD deploy half is theatre | already named |
| 100 | P2 | [P2-9] Sealed Secrets half-landed | already named |
| 101 | P2 | [P2-10] No PITR (RPO 24h); no DB HA | already named |
| 297 | P3 | Install Calico on local minikube to actually enforce NetworkPolicies | already named |
| 299 | P2 | Customer-storefront realm unconfigured in EVERY k8s environment | already named |
| 301 | P2 | No mcp-server k8s manifest set | already named |
| **98** | P2 | [P2-7] Observability demo-grade: prod metrics unreachable, phantom alerts, no logs/tracing | **added** — DPLY-03 *is* this issue |
| **112** | P3 | [P3-10] Ops readiness: runbook stubs, no paging path, no SLOs | **added** — "alerts a human" needs a human to alert |
| **294** | P2 | Pre-rollout operator check: SES sending domain + `jtoye-images` bucket in eu-west-2 UNVERIFIED | **added** — blocks first deploy |
| **300** | P3 | Work Order H: sealed-secrets / external-secrets for the local secrets path | **added** — sibling of #100 |
| **304** | P2 | Rework `stomp-relay.spec.ts` to be ingress-capable | **added** — untestable until an ingress exists |
| **592** | — | One-click unsubscribe (RFC 8058) unwired in k8s | **added** — one env var beside `NOTIFICATION_UNSUBSCRIBE_BASE_URL` |

## Phase 30 — The Money Path, Executed (5)

| # | P | Title | Note |
|---|---|---|---|
| 61 | — | Phase 17 follow-up: verify refund E2E + decide WR-09 | already named |
| 102 | P2 | [P2-11] No production tenant lifecycle; single pooled Stripe account | already named |
| **461** | **P1** | UX-5: orders complete with no payment; pay-on-collection must become channel-issued payment links | **added** — see below |
| **462** | P2 | UX-6: password signups have no second factor and **no verified contact channel** | **moved here from Phase 33, 2026-08-07** — its verified-contact half is a hard dependency of #461 |
| **108** | P3 | [P3-6] Missing outbound-call timeouts (Stripe/SMTP/axios/S3); dead email breaker config | **added** — a hung Stripe call is a money-path failure |

**#461 is upstream of the rest of this phase.** PAY-01..03 cover Stripe *mechanics* — refunds,
subscriptions, payouts — and every one of them assumes an order that already took money. #461 says
orders today complete without taking any. Those are different problems, the second comes first, and
it was in no plan until this sweep.

### The product decision is already made — do not re-open it

Recorded in #461 verbatim from the owner, 2026-08-02, and reaffirmed 2026-08-07:

> *"a payment link should automatically be sent to them via the telephone number they've called on,
> or social media channel they've engaged on"* — and, stated directly: the payment request goes to
> the buyer's **verified telephone number**.

Pay-on-collection is not permitted, because a customer can order and simply not collect, leaving the
vendor with produced stock and no payment. That is a vendor-protection rule, not a preference.

**An earlier draft of this document said #461 "needs a product decision before it can be planned."
That was wrong** — the decision predates the sweep by five days and is in the issue body. What blocks
#461 is a dependency chain. Measured on the tree 2026-08-07:

| | dependency | state |
|---|---|---|
| 1 | a phone number is **captured** | `Customer.phone` exists — `@Column(length = 50)`, **optional**, free text (`Customer.java:49-50`). A phone or social order may have no Customer row at that point at all |
| 2 | that number is **verified** | **does not exist.** No `phone_verified` column in any migration, no OTP, no verification flow. **This is #462**, which is why it moved into this phase |
| 3 | a **channel** to deliver on | `WhatsAppSmsChannel` exists but `WhatsAppProperties.enabled` defaults **false** and needs SID + auth token + from-number; Phase 22's inbound parser is incomplete. **This is #208** — deferred, but now on the critical path |
| 4 | a **payment link** to send | Stripe test-mode keys — the standing commercial decision that gates this phase entirely |

Row 2's absence is falsified, not assumed: a control search for `emailVerified` resolves to **4**
files including `CustomerJwtVerifier`, so the pattern can find a real verification flow when one
exists. Phone has none.

**The platform verifies email and does not verify phone — and the design routes on phone.** That is
the load-bearing sentence for this phase. Row 2 is not a security nicety that can trail the money
path; it is the address the money path sends to.

## Phase 31 — Consumer-Safety and Legal Floor (3)

Unchanged. `#103` (WCAG 2.1 AA), `#116` (privacy policy / cookie banner / retention), `#427` Wave 1
(the allergen evidence chain) — all already named.

## Phase 32 — Production Cutover + First Tenant (1)

Unchanged. `#428` Wave 1 (catering discovery) — already named.

## Phase 33 — The Consumer Product *(new, 9)*

**The cluster with the least planning coverage and the highest-signal source.** Every P1 here was
found by the owner using the running application; no audit in this repo found any of them. Runs
parallel to 29–31 and **gates 32** — a first paying tenant is a consumer transaction, and today a
signed-out consumer sees five fictional vendors.

| # | P | Title | In `.planning/` before today? |
|---|---|---|---|
| 460 | **P1** | UX-4: no concept of locality — device location unused, shop coordinates inert, no delivery radius | **no** |
| 544 | **P1** | UX-14: "Cooking near you" is five hardcoded fictional vendors | **no** |
| 453 | **P1** | QA-A/F-H6: onboarding MANUAL_REVIEW is on no surface — a two-actor dead-end | **no** |
| 458 | P2 | UX-2: signed-in customer nav shows *For operators* + *Track order* ungated | **no** |
| 452 | P2 | QA-A/F-H5+F-H7: no 2nd-shop onboarding path, no staff invite | yes (issue only) |
| 545 | P2 | UX-15: Keycloak ships the stock theme on both realms; no J'Toye brand asset exists | yes (issue only) |
| 546 | P2 | UX-16: review customer-facing look and feel on web and mobile | yes (issue only) |
| 432 | — | Customer storefront has no social signup — `jtoye-customers` realm has `identityProviders: 0` | yes (issue only) |
| 285 | P3 | Staff screen: bulk-revoke of JIT-provisioned `shop_staff` rows | yes (issue only) |

`#453` intersects the recorded **no-platform-operator** constraint: there is no cross-tenant operator
identity, so a stalled onboarding notifies nobody. That is a design decision to make, not a bug to
fix, and it is why the issue is unadjudicated.

## Phase 34 — Rendering + Test Truthfulness *(new, 6)*

Does **not** gate 32. Grouped because they share one root: the suite reports on surfaces it does not
actually exercise.

| # | P | Title | Note |
|---|---|---|---|
| 507 | P2 | 20 more `"use client"` pages fetch on mount; `/shop` is client-rendered too | |
| 542 | P2 | A route-interception stub cannot cover a server-rendered route; #507 queues 25 conversions | the root of this group |
| 202 | — | Refactor 4 mount-time `setState`-in-effect hydration sites | |
| 286 | P2 | Vendor-authenticated Playwright E2E has never run live | **narrow it — see below** |
| 547 | — | 7 E2E skips are declared and bounded, but still unverified surface | tracker; closes via #304 (P29) + #61 (P30) + 1 untracked |
| 110 | P3 | [P3-8] No coverage measurement or gating; Playwright counted but never run in CI | **half already satisfied** |

**#286 is mostly satisfied and nobody noticed.** Measured against last night's nightly
(run 31138225934, 182 total / 175 passed / 7 skipped):

- `/dashboard/staff` click-through — `dashboard-interface-corrections.spec.ts` performs a real
  `vendorLogin` (3 refs, **0** route stubs) and navigates `/dashboard/staff`. It is not among the 7
  skips, so it ran green with a live session. **Satisfied.**
- `dashboard-mobile` at 375px — runs with a real login, but the mobile project viewport is
  **390 × 844** (`frontend/playwright.config.ts:84`), not 375, and the spec carries **9** route
  stubs. **Not** satisfied, and the stubs are precisely #542's complaint.

Narrow #286 to the viewport and the stubs, or close it and let #542 carry the remainder. Do not
close it whole.

**#110's second acceptance criterion — "Playwright runs in CI" — is now met** by the nightly job
(this is what closed #420). Only the coverage half (JaCoCo, the unconsumed Go profile, a Jest
`coverageThreshold`) remains. Narrow it.

## Deferred, with a dated reason (5)

Deferred is not closed. Each row names the condition that revives it. Per this project's recorded
trap, a `deferred:` block whose reason becomes false survives until expiry — **re-run the stated
measurement, do not re-read the reason.**

| # | Reason | Revives when |
|---|---|---|
| #207 | [AI-5] pgvector spike needs the image-strategy + embedding-source decision | the embedding source is chosen (#216 locked 4 of the image decisions; this one is open) |
| #208 | ⚠ **CRITICAL-PATH deferral** — [AI-6] WhatsApp channel needs a WhatsApp Business API account | the account exists. **Not an ordinary deferral**: #461 sends the payment request back *through the channel the customer engaged on*, so this is the delivery mechanism for a P1, not an optional AI feature. Same commercial class as the Stripe keys, and it should be obtained on the same trip |
| #209 | [AI-0] epic — idempotency (#204), scoped creds (#206) and MCP tools (#203) all shipped; its only open children are #207 and #208 | closes when both do; it is a tracker, not work |
| #296 | Conditional by its own title — *"if an in-cluster Keycloak is ever deployed"*. Phase 29 targets an external IdP | an in-cluster Keycloak is actually deployed |
| #303 | `OLLAMA_URL` / `ZIPKIN_ENDPOINT` are reasoned allowlist omissions; each needs a real backing service first | either service is actually deployed |

> **Two of these five are not really parked, and saying so is the point of the column.** #208 is the
> delivery channel for #461's payment request, and #209 cannot close while #208 is open. A deferral
> whose reason is *"waiting on a commercial account"* reads as low-stakes right up until the account
> is also what a P1 depends on.

## Post-GTM hardening backlog (6)

Real, all from the 2026-07-08 audit, none blocking a first paying tenant. Scheduled after Phase 32
rather than deferred, because they become urgent the moment there is production data to lose.

| # | P | Title |
|---|---|---|
| #107 | P3 | [P3-5] Unbounded-growth accumulators (`_aud`, outbox, stripe events); `revinfo` has no RLS |
| #109 | P3 | [P3-7] Sync batch upsert race (no unique constraint on `shops.name` / `products.sku`) |
| #111 | P3 | [P3-9] Cache hygiene: no stampede protection, cross-tenant evictions, uncached hot path |
| #114 | P3 | [P3-12] Dependency/code hygiene: unused JasperReports in prod JAR, page monoliths, no i18n |
| 115 | P3 | [P3-13] No load-test baseline; no contract tests; no fault-injection tests |
| #499 | P3 | `StaffManagementService.grant()` has #486's vanished-row shape, but it upserts |

`#115` was part-satisfied by Phase 27 (OPS-03 built the load-baseline harness) and left open with the
remainder re-filed as `#337`. Its contract-test and fault-injection halves are what remain here.

## Immediate — a shipped defect, no phase needed (1)

| # | Title |
|---|---|
| 587 | Outbound webhooks give a receiver 127 seconds before the event is permanently lost |

`WebhookDeliveryWorker.computeBackoffMillis` is `baseMs << (attempts - 1)` against
`max-attempts: 8`, so the schedule tops out at 64 s and totals **127 s**. The configured
`backoff-cap-ms: 3600000` is unreachable dead config. Any deploy or pod restart longer than about two
minutes silently loses every event fired during it. This is one method in shipped code and does not
need a phase around it — but it must be **shown to fail first**, with a receiver held down past 127 s
and the delivery observed reaching terminal `FAILED`, before any fix is trusted.

---

## What this document does not do

It assigns homes. It does not re-estimate, re-prioritise, or promise a date.

**One item needs a product decision before it can be planned at all**: `#453` — who adjudicates
onboarding `MANUAL_REVIEW`, given there is no cross-tenant platform operator identity. No amount of
planning substitutes for that.

> **Corrected 2026-08-07, same day, by the owner.** The line above originally read *"two items"* and
> named `#461` alongside `#453`. **That was wrong.** #461's decision was made on 2026-08-02 and is
> quoted verbatim in the issue body: the payment request goes to the buyer's **verified telephone
> number**, or the social channel they engaged on. What blocks #461 is a four-link dependency chain
> (capture → verify → channel → Stripe keys), not a decision — see Phase 30 above.
>
> **This is the same failure the document was written to fix, one layer in.** The sweep found 42
> issues the roadmap could not see; it then mis-read one of the six it had just rescued, by
> classifying it from its *title* rather than its body. A decision recorded five days earlier, in
> the issue itself, was reported as outstanding. **Read the body before assigning a blocker** — the
> title says what is wrong, not what has already been settled about it.

The four blocking commercial decisions recorded in `ROADMAP.md` for Phases 29–32 are unchanged by
this sweep and still gate everything downstream: the production domain, the hosting target, Stripe
test-mode keys, and ADR-0002 sign-off. **A fifth now sits beside them in practice** — a WhatsApp
Business API account (#208), because #461's payment request has to be delivered on the channel the
customer used.
