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

---

## Persona user-testing 2026-10-03 — 123 findings, epic #880 *(added 2026-10-04)*

Two passes of in-character persona testing ran against the live local stack: 6 reports in pass 1 and 9 in pass 2 (vendor rush, multi-site owner, kitchen hand, older regular, screen-reader user, prankster, regulator, integrator, mid-journey chaos). They produced 250 findings, deduplicated into **123 clusters**, plus 40 goods to preserve. Each cluster is tracked **exactly once**:
- 98 single-cluster issues;
- 5 P3 polish bundles (17 clusters);
- comments on 6 existing open issues (8 clusters);
- epic **#880**.

All carry the label `ux-persona-test`. Priorities: **P0 16 · P1 32 · P2 49 · P3 26**.

Three closed issues reproduce and were filed as regressions without reopening the originals: #84 → #777, #88 → #782, #465 → #843. #727's label changed from P3 to P0, because pass 2 showed an order recording the wrong allergen mask.

Sources:
- the catalogue, with themes, membership reconciliation and operator-cleanup list, at `.planning/ux-persona-test-20261003-pass2/consolidated/CATALOGUE.md`;
- the filing ledger at `consolidated/filed-issues.json`;
- regression guards for the fixes at `consolidated/goods-to-preserve.md`.

Screenshots and raw probe output stay local: the evidence folders hold session state.

**Phase 37 is on the roadmap (added 2026-10-04 by owner request; see ROADMAP.md § Phase 37).** 87 clusters have no home in Phases 28–36. They are grouped below into sub-themes 37-A to 37-G. The next step is `/gsd-plan-phase 37`. **Phase 31 is complete**, so its 17 clusters need a gap-closure plan (31.1) rather than new scope.

These nine findings sit under P0 in more than one area. They gate a first real tenant regardless of where they land:
- #777 and #778: DSAR erasure and access requests silently not done;
- #779 and #780: staff default and revoke both lead to tenant-wide admin;
- #781: cross-shop fake reviews;
- #782: rate limit bypass through X-Forwarded-For;
- #783: checkout charges a price the customer was not shown;
- #784 and #785: the allergen acknowledgement is not stored, and the stored allergen set is never shown to the customer.

### Phase 29 – Deployable Staging, With Its Own Monitoring (1)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-090 | P2 | #859 | Nothing asserts at deploy time that customer email verification is on; dev runs with it off |

### Phase 30 – The Money Path, Executed (5)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-033 | P1 | #102 | The published £39/location subscription has no billing built and no billing UI |
| UXT-034 | P1 | #102 | Vendors have no payments or payouts surface (/dashboard/payments returns 404) |
| UXT-045 | P1 | #814 | The local compose stack can never exercise the card-payment path (Stripe env-var names and build-time key mismatch) |
| UXT-059 | P2 | #828 | A completed cash order stays 'Payment status NONE / Unpaid' while counted as revenue |
| UXT-060 | P2 | #829 | Cash-only is revealed only at the bottom of checkout, and the Place order button shows a card icon |

### Phase 31 – Consumer-Safety and Legal Floor (17)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-001 | P0 | #777 | DSAR erasure is marked completed while nothing is erased for storefront customers |
| UXT-002 | P0 | #778 | A verified DSAR access request is never fulfilled (ACCESS delivery not implemented) |
| UXT-009 | P0 | #784 | The customer's allergen acknowledgement is never sent to or stored by the server |
| UXT-010 | P0 | #785 | The customer never sees the allergen set recorded on their order, and it can differ from what they acknowledged |
| UXT-012 | P0 | #787 | A product whose ingredients name an allergen (e.g. 'butter (MILK)') with no box ticked saves and shows as 'No allergens' |
| UXT-014 | P0 | #789 | Customers are never given the seller's legal identity or any way to contact the shop |
| UXT-015 | P1 | #793 | Stripe JS and fraud cookies load on a cash-only checkout, contradicting the cookie policy |
| UXT-019 | P1 | #794 | The platform's own registered office is not published anywhere on the site |
| UXT-043 | P1 | #812 | An allergy request is a generic free-text note: no alert to the vendor, no acknowledgement, never echoed to the customer |
| UXT-048 | P1 | #817 | Menu cards show allergens as an unnamed count, 'Add' works without seeing them, and 'none declared' is never stated |
| UXT-069 | P2 | #838 | There is no account page: data access and erasure are mailto-only although a backend intake exists |
| UXT-070 | P2 | #839 | The DSAR confirmation link does not open on compose and shows raw JSON when it does |
| UXT-071 | P2 | #840 | The cookie policy omits keys that hold the customer's email and id and survive sign-out |
| UXT-091 | P2 | #860 | The basket shows no allergens and checkout's combined set has no per-item attribution |
| UXT-092 | P2 | #861 | No 'may contain' field exists, label use-by is computed at download time, and records use US date format |
| UXT-113 | P3 | #871 | There is no marketing-consent choice or preferences page, and /unsubscribe says 'contact the vendor' |
| UXT-114 | P3 | #878 | The accessibility statement is stale (claims no skip link, cites WCAG 2.1, excludes basket/confirmation/tracking) |

### Phase 32 – Production Cutover + First Tenant (5)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-031 | P1 | #803 | A prospective vendor has no way in: 'Start your application' dead-ends at a login, and no sales or support contact exists |
| UXT-032 | P1 | #804 | No merchant terms, pricing page, VAT basis or card-fee figure: /legal/terms, /pricing, /contact and /about all 404 |
| UXT-067 | P2 | #836 | Internal strategy pages are public, expose a repo path, and contradict the landing page (incl. a 'payouts: Full' claim) |
| UXT-068 | P2 | #837 | Test and demo data is visible to customers and vendors (E2E 20% OFF promo, weeks-old test orders, a real person's name and email) |
| UXT-119 | P3 | #878 | Public pages: no vendor-to-vendor confidentiality statement; positioning excludes non-London, non-West-African operators |

### Phase 33 – The Consumer Product (7)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-013 | P0 | #788 | Shops go live with a failed FSA match, self-approved by the vendor, while the site claims 'UK food-hygiene verified' |
| UXT-024 | P1 | #452 | A second or third shop can never go live, while onboarding says 'Your storefront is live' |
| UXT-025 | P1 | #452 | There is no way to invite a staff member: they must self-register, then auto-become Group admin |
| UXT-030 | P1 | #460 | Delivery is accepted to any UK postcode with no radius check at checkout |
| UXT-079 | P2 | #848 | Keycloak sign-in and registration fall below the storefront's accessibility bar and hide password rules until failure |
| UXT-109 | P3 | #877 | The shop list uses gradients and initials instead of food photos |
| UXT-112 | P3 | #877 | Postcode search cannot tell invalid, out-of-area and no-kitchens apart, and its result count may not be announced |

### Phase 34 – Rendering + Test Truthfulness (1)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-061 | P2 | #830 | Menu 'Add' buttons are visible but ignore taps until hydration (4.2 s on 4G, 14 s on Slow 3G) |

### Phase 37 · 37-A Kitchen & order operations (15)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-011 | P0 | #786 | The kitchen screen ticket hides the customer's note (e.g. 'severe peanut allergy') and the fulfilment type |
| UXT-020 | P1 | #795 | A new order makes no sound and never reaches the kitchen screen until someone confirms it on another page |
| UXT-021 | P1 | #796 | When the all-day kitchen tablet's session lapses the board silently becomes a sign-in page |
| UXT-022 | P1 | #797 | A vendor cannot pause or stop taking orders: no pause switch, no holiday closure, and free-text hours fail open and cannot be cleared |
| UXT-049 | P2 | #818 | A double-tap on 'Start Preparing' jumps the order to READY and emails the customer 'Ready!' with no undo |
| UXT-050 | P2 | #819 | Kitchen mute survives sign-out and the next person's icon shows sound on while new orders are silent |
| UXT-051 | P2 | #820 | Cancelling an order is one unconfirmed tap with no reason, next to Confirm on small phone buttons |
| UXT-052 | P2 | #821 | Unanswered orders stay Pending forever and the customer is never told |
| UXT-053 | P2 | #822 | Orders and Customers have no search, so an order cannot be found by customer name at the counter |
| UXT-054 | P2 | #823 | Every kitchen ticket cuts the order number to 'ORD-…', so tickets look identical |
| UXT-055 | P2 | #824 | The kitchen board is not built for a wall tablet: wasted space, small text, newest-first ordering and very tall tickets |
| UXT-088 | P2 | #857 | A vendor cannot show who prepared an order or when: no timeline, no export |
| UXT-089 | P2 | #858 | 86ing an item or changing a price takes four taps and ~30 s in a long form |
| UXT-099 | P3 | #866 | With the API unreachable but the socket up, the board says 'Live' for ~50 s while dropping an order |
| UXT-101 | P3 | #875 | The orders table is clipped on tablet and the phone's first screen is an explainer, not orders |

### Phase 37 · 37-B Multi-site, staff access & finance (12)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-003 | P0 | #779 | Revoking a manager's last shop grant silently makes him tenant-wide Group admin |
| UXT-004 | P0 | #780 | Every staff login is a tenant-wide Group admin by default (JIT provisioning, strict-scoping off) |
| UXT-018 | P0 | #791 | A completed, paid order can be deleted, leaving ledger rows that point at nothing |
| UXT-026 | P1 | #799 | CSV import ignores the selected shop and hides imported items from every storefront; a menu cannot be copied to another site |
| UXT-027 | P1 | #800 | Per-shop dashboard and finance show the whole business's takings, and site managers get 'No financial data yet' instead of their shop's numbers |
| UXT-046 | P1 | #815 | Products have no VAT-rate choice; everything is booked as Standard 20% |
| UXT-047 | P1 | #816 | 'VAT (incl. 20%)' is shown for every vendor, with no VAT-registration status or number captured |
| UXT-058 | P2 | #827 | Finance has no 'today', no date range, no export and no cash/card split |
| UXT-087 | P2 | #856 | A kitchen hand sees far more customer personal data than needed to cook |
| UXT-098 | P3 | #875 | Scoped users see nav items they cannot use, and a 403 renders as 'No endpoints yet' / 'storefront is live' |
| UXT-100 | P3 | #867 | After access is removed the kitchen keeps showing that shop's tickets (with PII and live buttons) and blames the connection |
| UXT-117 | P3 | #875 | Staff list masks emails so same-domain staff are indistinguishable; products table has no Shop column |

### Phase 37 · 37-C Checkout integrity & customer trust/retention (23)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-007 | P0 | #783 | Checkout never re-validates the stored basket: stale prices, removed and sold-out items surface only as a charge or a bare error |
| UXT-008 | P1 | #792 | An advertised '20% OFF' promotion is displayed on the storefront but never applied to the order |
| UXT-029 | P1 | #802 | The order confirmation is rendered in place at /checkout: off-screen, unannounced, and lost on refresh |
| UXT-044 | P1 | #813 | Reviews publish the reviewer's full checkout name with no notice, policy or moderation |
| UXT-056 | P2 | #825 | No ready-by or delivery time exists anywhere: tickets, confirmation, tracking or emails |
| UXT-057 | P2 | #826 | Order confirmation and every status email omit the shop, items, total and address, and come from inconsistent senders |
| UXT-074 | P2 | #843 | On a slow network, returning after 5 minutes signs the customer out (parallel refreshes burn the single-use token) |
| UXT-075 | P2 | #844 | Customers cannot order ahead for a time, and a closed shop takes no pre-orders |
| UXT-076 | P2 | #845 | No 'order again' and no remembered address: a weekly order takes 10 taps and 85 keystrokes |
| UXT-077 | P2 | #846 | Customers have no way to leave a review; the only path is an unauthenticated API call with an email in the URL |
| UXT-078 | P2 | #847 | Password-reset links expire after 5 minutes |
| UXT-086 | P2 | #855 | A delivery customer's tracking page says 'Ready for collection' |
| UXT-093 | P2 | #862 | There is no allergen filter, and searching 'peanut' or 'gluten free' returns nothing |
| UXT-102 | P3 | #868 | A customer cannot cancel an order |
| UXT-103 | P3 | #876 | A network failure at Place order says only 'Failed to place order. Please try again.' |
| UXT-104 | P3 | #876 | Back then Forward wipes the checkout address, phone and notes |
| UXT-105 | P3 | #876 | Empty checkout submit skips the address and takes two rounds, with no error summary |
| UXT-106 | P3 | #869 | The basket is device-local: not restored on sign-in and not shared across devices |
| UXT-107 | P3 | #877 | Sign-out is a tiny unlabelled one-tap icon next to a person icon that does nothing |
| UXT-108 | P3 | #870 | Menu items cannot be customised (no modifiers) |
| UXT-110 | P3 | #877 | Storefront copy noise: duplicated dishes and alt text, descriptions repeating names, 'Co..', 'Draft' filter, status naming mismatch |
| UXT-111 | P3 | #877 | React hydration error #418 on /shop/orders and /track, and 'Auto-refreshing' on finished orders |
| UXT-116 | P3 | #877 | An unknown shop link returns a soft 404 (HTTP 200 'Shop not found') |

### Phase 37 · 37-D Abuse resistance (8)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-005 | P0 | #781 | A buyer of one shop can publish a 5-star review on a different shop of the same tenant |
| UXT-006 | P0 | #782 | The public rate limiter trusts any X-Forwarded-For value, so rotating it defeats the limit |
| UXT-039 | P1 | #808 | Anonymous traffic can exhaust the edge gateway's single process-wide rate limit and block every vendor's sync; its 429 is untyped |
| UXT-041 | P1 | #810 | Anyone can place many fake cash orders in seconds with throwaway contact details |
| UXT-042 | P1 | #811 | Item quantity has no upper bound: a £19 billion order is accepted and shown on the dashboard |
| UXT-072 | P2 | #841 | Order contact details are barely validated, and API/MCP orders need no customer contact at all |
| UXT-073 | P2 | #842 | Vendors have no bulk-reject and no fraud signal for junk orders |
| UXT-118 | P3 | #872 | Another shop's order returns 403 not 404, and public image URLs embed the tenant UUID |

### Phase 37 · 37-E Integrator surface (API, MCP, webhooks, sync) (13)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-016 | P0 | #790 | Updating a product without quantityInStock silently turns stock tracking off |
| UXT-017 | P0 | #727 | Products created by /sync/batch belong to no shop, are orderable at any shop, and record the wrong allergens |
| UXT-035 | P1 | #805 | A vendor has no way to give a developer API credentials; the only path is the owner's password plus the confidential core-api client secret |
| UXT-036 | P1 | #806 | An order taken by an AI agent through MCP stays DRAFT and the kitchen never sees it |
| UXT-037 | P1 | #807 | /sync/batch has no stock or availability field and silently skips unknown items while reporting SUCCESS |
| UXT-038 | P1 | #587 | Webhook endpoints auto-pause after ~46 s of failures and retries stop after 5 attempts, with nobody told |
| UXT-040 | P1 | #809 | Asked for one shop's menu, an AI agent gets every product in the business with allergens only as an integer |
| UXT-094 | P2 | #208 | A vendor cannot connect their own WhatsApp; WhatsApp ordering is one platform-wide setting |
| UXT-095 | P2 | #863 | The MCP server cannot be added to hosted ChatGPT/Claude connectors (no OAuth discovery, 5-minute tokens) |
| UXT-096 | P2 | #864 | Order webhooks carry only ids and status, with no items, totals or customer |
| UXT-097 | P2 | #865 | Vendor API and MCP orders accept products marked unavailable |
| UXT-122 | P3 | #879 | Webhooks have no test event, no request/response body, no delete, and do not follow redirects (Apps Script fails) |
| UXT-123 | P3 | #879 | API/MCP contract polish: MCP drops typed error details, OpenAPI hygiene problems, replay indistinguishable from fresh |

### Phase 37 · 37-F Accessibility (6)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-080 | P2 | #849 | At large text sizes the basket, tracker and shop header truncate or overflow |
| UXT-081 | P2 | #850 | Tracking pages are silent and unlabelled for screen readers (status changes, current step, copy button, lookup result, field name, contrast) |
| UXT-082 | P2 | #851 | Shop cards on the kitchen list have no accessible name ('link, link, link') |
| UXT-083 | P2 | #852 | Pressing Add/Remove drops focus to the page body and the basket announcement doubles the count without naming the item |
| UXT-084 | P2 | #853 | Basket, checkout, confirmation and tracking all share the title 'J'Toye — Discover Local Vendors' |
| UXT-085 | P2 | #854 | On mobile the cookie banner covers content and keyboard focus and is the 32nd tab stop |

### Phase 37 · 37-G Catalogue & shop-admin correctness (8)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-023 | P1 | #798 | Vendors cannot set a delivery fee, free-delivery threshold or collection-only: orders are charged £0 delivery and collection-only shops take deliveries |
| UXT-028 | P1 | #801 | Every shop update regenerates the public URL slug, so shared links and QR codes break |
| UXT-062 | P2 | #831 | Saving a shop overwrites its banner with the logo URL |
| UXT-063 | P2 | #832 | Products cannot be deleted once they have a photo or any order (even cancelled): misleading 409 |
| UXT-064 | P2 | #833 | Deleting a shop orphans its products, and editing an orphan silently moves it to 'All Shops' |
| UXT-065 | P2 | #834 | The image dialog stays on 'Processing…' although the server has the image ACTIVE within ~6 s |
| UXT-066 | P2 | #835 | The 'Publish to storefront' checkbox is ignored, or throws away the whole shop edit with a raw developer error |
| UXT-115 | P3 | #875 | SKU is required when adding a product (jargon for a stall holder) |

### Phase 37 · unsorted (2)

| Cluster | P | Issue | Finding |
|---|---|---|---|
| UXT-120 | P3 | #873 | No email campaigns, loyalty or vouchers |
| UXT-121 | P3 | #874 | No custom storefront domain (SUSPECTED) |
