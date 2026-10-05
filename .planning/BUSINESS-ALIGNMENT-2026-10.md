# Business Alignment Backlog (2026-10)

**What this is.** These are the product requirements that came out of the business research, mapped onto this roadmap so that business work and engineering work stay aligned. Each requirement either points at the existing phases and issues that already cover it, or names the gap and the phase it should go to.

**Status:** DECISIONS RULED 2026-10-05 (§3). This is not yet a phase plan or an issue: each gap enters its home phase through that phase's normal planning flow (`/gsd-discuss-phase` / `/gsd-plan-phase`) or as an issue under epic #880's disposition rules.

**Owner ruling, 2026-10-05:** the venture continues. Research decides *what* gets built, never *whether* to continue.

**Sources** (business side, in `~/IdeaProjects/jtoye-market-intel/gtm/research/`):
- `swarm-sim-2026-10-05/REPORT.md` (swarm v1). 36 composite personas over 6 rounds. Top-10 objections were all operational ("who answers at 9pm Friday?" ranked 17×3).
- `swarm-sim-v2-2026-10-05/REPORT.md` and `economics.md` (swarm v2). Three whole business designs plus unit economics.
- `business-designs-2026-10.md`. Designs D1/D2/D3 and fixes F1–F6.

**Evidence tags:** **[SIM]** is a simulation finding. It is a hypothesis to test, not measured demand. **[ECON]** is derived arithmetic. **[EVID]** is a sourced fact.

The coverage mapping was verified on 2026-10-05 against `ROADMAP.md`, `ISSUE-DISPOSITION.md` (§ Persona user-testing 2026-10-03), `ux-persona-test-20261003-pass2/consolidated/CATALOGUE.md`, `REQUIREMENTS.md`, `docs/PRD.md` and `gh issue view`.

---

## 1. Why these requirements, in one paragraph

Across both swarm runs, personas never refused or churned because a feature was missing. They did so because the product would not hold up in daily operation:
- an outage on a Friday;
- the one helper who set it up leaving;
- the value running out once setup was done;
- labels that cannot be printed in a batch;
- a price that felt like rent.

The business design that survived is a hypothesis: **"built for you, then pay as you grow."** Setup is paid at £195/month, then the price steps down to 2.5% of direct orders, capped at £39 [SIM, ECON]. Section 2 lists what the product must do for that design to hold up over time.

---

## 2. The backlog: requirement → coverage → gap → home

Coverage key: **NONE** = nothing in the plan; **PARTIAL** = some pieces exist, the gap is named; ⚠️ = conflicts with a recorded decision (see §3).

| ID | Requirement (and the evidence it rests on) | Coverage today | Gap to close | Home phase | Acceptance test (must be able to FAIL on today's tree) |
|---|---|---|---|---|---|
| **B1** ⚠️ | **Orders survive an outage.** Orders are relayed to the vendor's WhatsApp/phone when the platform is degraded, stay **paid**, and keep the allergen acknowledgement. Vendors get a status message within 15 min. [SIM v2 R5: the relay saved every paid order in A and B, but fallback orders arrived unpaid and without the allergen banner] | **NONE.** Nearby: Phase 29 DPLY-03, #98, #112 (alerts reach operators, not vendors); 37-A #866 (UXT-099); #208 (inbound only); UXT-094 (platform-wide number) | A degraded-mode relay that keeps payment status and the allergen record; a vendor-facing status broadcast | **37-A** ("new orders reach the kitchen and alert someone"); the status-broadcast half goes to **29** (#112) | Chaos drill: kill core-java for 40 min at peak. **0 paid orders lost; every relayed order shows PAID and the allergen acknowledgement; vendor notified ≤15 min.** |
| **B2** | **Survives the helper leaving.** Multiple staff logins, setup not tied to one phone or email, a handover sheet export, transfer of the admin role. [SIM v2: setups collapsed when the grandchild or visa-holding staff member left] | **PARTIAL.** 37-B #780 (UXT-004), #779 (UXT-003), #875 (UXT-117); #452 (UXT-025, staff invite) under CUST-02, deferred by D-3; 37-E #805 (UXT-035); #285 | Staff invite is unplanned. No handover-sheet export. No admin-ownership transfer. | **37-B** (staff access) | The owner invites a second admin, removes themselves, and the shop keeps trading. A handover PDF lists every setting needed to re-onboard. |
| **B3** | **Value continues after setup (retention).** The vendor's customer list is exportable, customers get a repeat-order nudge, and a monthly report shows orders kept off marketplaces and commission saved. [SIM v2 A: D1 churned at month 3 because "the book is done, £195 is rent"] | **PARTIAL.** 37-C #845 (UXT-076), #873 (UXT-120), 37-A #822 (UXT-053), 37-B #827 (UXT-058) | No customer-list export. No repeat nudge. No monthly savings report (PRD §8: "No product analytics"). | **37-C** (customer trust/retention) | A vendor downloads a CSV of their customers. A customer with a past order gets one consented "order again" message. A monthly report lists direct-order count and commission avoided, against a stated marketplace rate. |
| **B4** | **Batch and thermal PPDS labels:** multi-label runs on 58/80mm thermal printers. [SIM v1 and v2: blocking, no answer; PRD §4: "Batch/thermal output is absent"] | **NONE.** Only the label *content*: #861 (UXT-092) | Batch runs and thermal output | **31.1** (owns the allergen/PPDS evidence chain) | Print 40 labels for one product in one job on a 58mm and an 80mm printer profile. The output matches the FSA format golden. |
| **B5** | **Caterer payments:** deposits, staged or split payments, pay-by-bank. [SIM v2 B: still missing for event caterers] | **PARTIAL.** Epic #428 Wave 2 (P2, blocked on Wave 1); Phase 30 PAY-02/03 (Stripe mechanics). Pay-by-bank: 0 hits. | Wave 2 has no phase. Split payments untracked. Pay-by-bank absent, although the owner names **bank transfer** as a payment-link method (2026-10-05 clarification, §3). | **30** (money path), still gated by #428's Wave 1 finding | In Stripe test mode, a 30% deposit is taken now, the balance is charged on the event date, and refund rules are applied. |
| **B6** ⚠️ | **Hygiene rating helps and never shames.** Listing is never blocked on the *score*. Vendors get a private inspection-readiness checklist. The rating is shown publicly where it helps. [SIM v1: the gate became "a platform that shames small shops"; v2: the private checklist produced records the EHO credited; EVID: FSA March 2026 proposes online display, and 93% of consumers want it] | **PARTIAL, conflicts.** Phase 33 #788 (UXT-013, P0, gates 32); PRD §4 FhrsGate; FHRS is not in shop DTOs | Separate the **FSA registration match** (stays a gate) from the **score** (decision D-B6). Build the private checklist. Add public display. | **33** (owns #788 and the FSA match) | A shop rated FHRS 2 with a valid FSA registration can publish. Its storefront shows the rating badge. The vendor sees a readiness checklist that no customer can see. |
| **B7** | **Setup the real setter-up can finish.** Guided onboarding a staff member or relative can complete, a delegated setup role, and a measured **≤4 h median** to live. [SIM v1/v2: most owners name someone else as the setter-up; PRD §7 pilot gate] | **PARTIAL.** Phase 21 ONBD-01..05 (done); #452/CUST-02 (unplanned); 37-B #780; PRD §7 | No delegated role. The 4 h target appears in no requirement or measurement. | **37-B** (role); the **≤4 h measurement** goes to **32** with the GTM-02 pilot gates | A delegated user completes onboarding without GROUP_ADMIN. The pilot dashboard reports median time-to-published for real tenants. |
| **B8** | **Written exit terms, built in.** Cancel via WhatsApp or in-app, self-serve export (customers, menu, allergen book), no charge after cancelling. [SIM v2: the strongest trust builder in every community; a churn turned into public praise] | **PARTIAL.** #804 (UXT-032), #102 (offboarding, Phase 30 / PAY-02); exports in #827 and 37-A #857 (UXT-088) | Self-serve cancel. Vendor export of the allergen book. A billing rule that stops all charges on cancel. | **30** (subscription and offboarding, #102) | The vendor cancels in-app. The export ZIP contains customers, menu and the allergen book. The Stripe test clock shows no charge in the following cycle. |
| **B9** ⚠️ | **Billing for the surviving business design:** £195/month during setup, then an automatic step-down to 2.5% of direct orders capped at £39/month (£0 in a quiet month). The cap is per account across sites. Per-event pricing for caterers. [SIM v2 ranking: D2 won 21 of 36 first-choice votes; ECON: D2 breaks even at about £155/week direct GMV per vendor; use Stripe-priced Connect, because the alternative eats 27–51% of revenue] | **PARTIAL, conflicts.** PAY-02 is "£39/location/month + 0.5%"; #102; #804; fee rail at **0 bps** (`STRIPE_PLATFORM_FEE_BPS:0`) | The pricing model itself (decision D-B9). Plan step-down logic. Per-account cap. Per-event pricing. Fee-rail activation. | **30** (billing and fee rail) | Test-clock run: month 1 bills £195. On "book complete" the plan switches. A month with £600 of direct orders bills £15. A month with £3,000 bills £39. A month with £0 bills £0. |
| **B10** | **"Order direct" collection asset:** a printable sticker or QR per shop, "5% off collection". [SIM v2 B: 10% for everyone was rejected — "don't give 10% to people who already love you"] | **NONE.** Prerequisites: 37-G #801 (UXT-028, slug churn breaks QR codes), 37-C #792 (UXT-008, promotions never applied), 37-G #798 (UXT-023, no collection-only setting) | The asset, plus a collection-only discount rule (which depends on #792 and #801) | **37-G** (shop admin and the QR slug fix) | The QR still resolves after the shop is edited. Scanning it applies a 5% discount to collection orders only, and the discount appears on the receipt. |

**Already in the plan and blocking everything above:** Phase 29 (first hosted runtime) and Phase 30 (money path executed). Without them, every B-row is moot [EVID: never hosted, orders can complete unpaid].

---

## 3. Owner decisions: RULED 2026-10-05

The owner approved every option on offer and delegated the choice: *"use the best option or options for every issue based on its relevance, alignment and quality."*

The selections below were made on that delegation. Each records why it was chosen over the alternatives. Each changes the plan only through its home phase's normal planning flow.

| ID | Ruling | Why this option (relevance · alignment · quality) | Rejected, and why |
|---|---|---|---|
| **D-B1** Orders during an outage | **(c) now, then (b).** **Phase 37-A:** while the platform is degraded, orders already PAID keep flowing to the vendor's WhatsApp, with the allergen snapshot (V63 `order_items.allergen_mask`) and a PAID marker. New checkout pauses behind an honest "ordering paused, back shortly" banner, and every affected vendor gets a status broadcast within 15 min (the broadcast half is Phase 29, #112). **After Phase 30:** add a Stripe-hosted Payment Link as the degraded *new-order* path. It must capture the allergen acknowledgement, which means confirming in planning that Stripe Checkout/Payment Link custom fields can carry it, and every link order is reconciled into `orders` on recovery. | (c) is honest today and needs no new payment surface. It keeps the PAY-04 ruling (the consumer pays by link before service; no cash at fulfilment) and the V63 rule that the allergen record is never fabricated. (b) then restores new orders using Stripe's uptime, not ours. Matches the swarm finding: "the relay is the asset" [SIM v2 R5]. | **(a)** The edge holding orders and payment intents would put payment state and PCI scope into a gateway that today has no fallback and sits outside the frontend path (CLAUDE.md: "breaker-open returns 502"). It is the largest new failure surface of the three. |
| **D-B6** Hygiene rating | **(a), plus one floor.** **Phase 33:** publication is gated on the **FSA registration match** (#788 keeps, and tightens, the identity gate). The score threshold is removed, **except FHRS 0 ("urgent improvement necessary"), which still blocks**, set through the existing config layer and not hardcoded. The score is shown publicly on the storefront (FHRS added to shop DTOs). Vendors get a private inspection-readiness checklist. | Matches the FSA's March 2026 direction on online display [EVID] and the 93% of consumers who want the rating shown. Removes the "shames small shops" attack [SIM v1]. Keeps the state machine as sole writer of `Shop.published`. The FHRS-0 floor keeps the platform from listing a premises the regulator has flagged for urgent action. | **(b)** A per-tenant threshold lets the vendor set their own bar, which is no gate. **(c)** Keeping the score gate excludes about a quarter of the beachhead pool (19 of 74 Birmingham prospects are FHRS ≤2 [EVID]). |
| **D-B9** Pricing model | **(b) Support both behind plan configuration.** **Phase 30:** PAY-02 is reworded from a fixed price to *plan-configurable billing*. The model of record (£39/location + 0.5%) and the walk model (£195/mo setup, then auto step-down to 2.5% of direct orders capped at £39 per **account**, £0 in a quiet month, per-event pricing for caterers) are both config-defined plans. The platform fee basis points come from config per plan (never the literal `STRIPE_PLATFORM_FEE_BPS:0` default). Use **Stripe-priced Connect**, because the alternative consumes 27–51% of revenue [ECON]. | Neither price has been validated with a real vendor. The PRD calls the current one "a decided hypothesis". The walk is the arbiter. Building billing once as configuration avoids building it twice. | **(a)** Adopting B9 outright pre-empts the walk. **(c)** Keeping PAY-02 alone ships a price both swarms rejected, and the ledger found its service cost does not survive. |
| **D-B27** Staff invite and ownership transfer | **(a) Bring into scope.** **Phase 37-B:** #452 / CUST-02 (staff invite) comes out of the D-3 deferral. Add a **delegated setup role** that can complete onboarding without GROUP_ADMIN; publication still goes through the onboarding state machine, and nothing is inherited via JIT (#780). Add a **tenant-internal ownership transfer** that the current owner initiates and confirms, with no platform operator involved (respecting catalogue theme 4). | Directly answers the helper-leaves failure [SIM v2] and the "someone else sets it up" finding [SIM v1/v2]. Respects the refusal of a cross-tenant operator identity. | **(b)** Deferring leaves tenant one exposed to the most common churn path the simulation found. **Residual (recorded):** recovery when the *owner* is unreachable needs a verified-contact recovery flow. It is out of scope here and is re-raised in Phase 37-B planning. |

**Payment vocabulary (owner clarification, 2026-10-05).** In the owner's usage, *"pay on delivery / collection"* means the buyer is **sent a payment link** and pays by bank transfer or any integrated method **before service**. That is the PAY-04 model, and it is required. What is banned is **cash exchanged at the point of fulfilment** (the code's `"Cash on Delivery"` fallback, `PublicStorefrontService:508-521`). Wherever this plan or #461 says "pay-on-collection is not permitted", read "cash at fulfilment is not permitted". Consequences here: D-B1's Payment Link fallback *is* the owner's pay-on-collection, and bank transfer must be one of the link's methods (B5, PAY-04).

**What changes because of these rulings:**
- **PAY-02** wording, at Phase 30 planning.
- **The REQUIREMENTS line** that defers "self-serve user invitation flows", at Phase 37-B planning.
- **#788's scope note**, to separate the registration match from the score.

Each is changed by its own phase, not by this document.

---

## 4. Where business and engineering checkpoints meet

| Business checkpoint (walk / pilot) | Engineering prerequisite |
|---|---|
| The validation walk can pitch "nothing lost on a Friday" | Phase 29 live, plus B1 drill passed (or D-B1(c) stated honestly) |
| Pitch A, "built for you, then pay as you grow" | D-B9 ruled; B9 test-clock run green in Phase 30 |
| Tenant one onboarded (Phase 32) | B7 4-hour measurement on; B8 exit terms live; P0 clusters closed (#777–#791, #727) |
| Month-3 retention review | B3 savings report; B2 handover sheet |
| Pre-seed raise (deferred) | ≥10 paying, ≥80% 90-day retention (PRD §7 gates). See `jtoye-market-intel/company/06-strategy-and-fundraising/fundraising-readiness-2026-10.md` |

---

*Change log: 2026-10-05 drafted from swarm v1/v2 and economics. The coverage mapping was produced by a read-only verification pass over the planning artifacts and the issue tracker.*
