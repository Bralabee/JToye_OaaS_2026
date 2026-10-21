# API Coverage — Phase 30 external surfaces

> Full coverage by default. Opt-outs are explicit, reasoned decisions. One table; capability names are prefixed with the
> service they belong to. Plan numbers name where each INTEGRATE row is built and proven. Stripe rows are TEST mode only
> (D-06): every evidence object that carries a `livemode` field reads `false`; a Refund carries none, so its mode is read
> from its charge.

| capability | decision | reason |
|---|---|---|
| stripe: Stripe.js + Elements card form (publishable key baked at build) | INTEGRATE | #957 — 30-01 build channel and baked-key gate, 30-02 fail-loud payment step, 30-04 live on staging |
| stripe: publishable-key PaymentIntent retrieve with the client secret | INTEGRATE | the staging specs' read-back, 30-03 / 30-19 (account-aware: Stripe-Account when the intent lives on a connected account) |
| stripe: PaymentIntent create (platform, destination and direct) | INTEGRATE | existing app paths; destination 30-31, direct 30-32 (API and browser), platform 30-04 |
| stripe: PaymentIntent confirm with test payment methods | INTEGRATE | browser 4242 (30-04, 30-20, 30-32); server-side pm_card_visa (30-22) and pm_card_bypassPending (30-28/29) |
| stripe: PaymentIntent cancel | INTEGRATE | D-02 manual DRAFT cleanup, 30-07 tool, 30-09 run with read-back |
| stripe: platform webhook endpoint (signature verification) | INTEGRATE | existing; status-preserving handler 30-05; forged/unsigned arms 30-15 |
| stripe: Connect webhook endpoint (Connected-accounts scope, own secret, pinned API version) | INTEGRATE | 30-12 route, 30-13 secret wiring, 30-14 owner registration, 30-15 negative arms, 30-31 positive arm (account.updated) |
| stripe: events list/read for evidence (pending_webhooks) | INTEGRATE | 30-14, 30-31 |
| stripe: client timeouts and network retries (stripe-java) | INTEGRATE | #108 Stripe half — 30-10 config + circuit breakers, 30-11 live log line |
| stripe: Connect Express account create with capabilities + idempotency key | INTEGRATE | 30-12 code, 30-30 live |
| stripe: AccountLinks (hosted onboarding) | INTEGRATE | 30-30 link to a mode-600 file, 30-31 owner completes |
| stripe: account read (charges_enabled, payouts_enabled, capabilities) | INTEGRATE | 30-31 |
| stripe: destination charges (transfer_data, application fee) and transfers read | INTEGRATE | PAY-03 — 30-28 tool, 30-31 live |
| stripe: direct charges on the connected account (Stripe-Account header) | INTEGRATE | PAY-03 — 30-29 tool, 30-32 live (API loop and browser checkout) |
| stripe: refunds create/read, with transfer reversal for destination charges | INTEGRATE | PAY-01 E2E 30-22/30-24 (CI sandbox); PAY-03 loops 30-31/30-32 |
| stripe: balance read and payouts create/read on the connected account (manual schedule) | INTEGRATE | PAY-03 — 30-29 tool, 30-32 live |
| stripe: customers, prices, subscriptions (quantity per location) with idempotency keys | INTEGRATE | PAY-02 / D-04 — 30-25 code, 30-26 tool, 30-27 live |
| stripe: invoices read by id | INTEGRATE | PAY-02 — 30-27 |
| stripe: test payment methods attached for billing | INTEGRATE | PAY-02 — 30-26/30-27 |
| stripe: Stripe CLI listen (webhook forwarding in CI, pinned binary + sha256) | INTEGRATE | PAY-01 nightly — 30-22/30-23 (T-30-SC) |
| stripe: live mode | OPT-OUT | explicitly out of scope — TEST mode only (D-06); production refuses a pk_test_ image (D-08), per-environment images are Phase 32 |
| stripe: Checkout Sessions (hosted checkout) | OPT-OUT | not needed — the product's checkout and /pay use Elements on the existing PaymentIntent path (D-03 reuses the #957 path) |
| stripe: Stripe Payment Links product | OPT-OUT | not needed — PAY-04 links are the platform's own single-use tokens over a PaymentIntent (V88 order_payment_links) |
| stripe: customer billing portal | OPT-OUT | not needed yet — vendors change plan through the dashboard; no self-serve card management in PAY-02's criterion |
| stripe: Stripe Tax | OPT-OUT | not needed — VAT is computed by the product's own ledger (V40) |
| stripe: disputes | OPT-OUT | not needed yet — no criterion in PAY-01..04 exercises a dispute; revisit before production money (Phase 32) |
| stripe: test clocks (subscription time travel) | OPT-OUT | not needed — PAY-02 is proven by an invoice retrieved by id at subscription creation, not by a renewal |
| stripe: instant payouts | OPT-OUT | not needed — standard payouts prove PAY-03; instant payouts are a cost feature |
| stripe: Connect Standard / Custom accounts | OPT-OUT | not needed — ADR-0001 chose Express |
| stripe: SPLIT_DEPOSIT_VENDOR_MOR live loop | OPT-OUT | not needed for PAY-03's criterion (names DIRECT and DESTINATION only); SettlementRoutingDecision routes it as a direct charge with a deposit amount (l.33), covered by StripeConnectServiceTest and PaymentServiceTest; the 30-32 direct loop exercises the same charge path |
| github: repository variables (FRONTEND_PUBLIC_STRIPE_PUBLISHABLE_KEY, CI_STRIPE_PUBLISHABLE_KEY) | INTEGRATE | owner sets, Claude reads back — 30-03, 30-23 |
| github: repository secret (STRIPE_TEST_SECRET_KEY) presence check | INTEGRATE | 30-23 (names only, never values) |
| github: Actions runs watched (Deploy to Staging, Staging Edge Probe, E2E Nightly) | INTEGRATE | every ship plan (D-07) |
| github: issue comments with evidence | INTEGRATE | #957, #958, #108, #61, #102 — no closing keywords |
| azure-key-vault: write-once external secret (Connect webhook secret) | INTEGRATE | 30-14 owner via staging-secrets.sh --set-external; names-only checks |
| azure-key-vault: Secrets Store CSI object for the new secret | INTEGRATE | 30-13 SPC, 30-15 pod reads the key name |
| azure-aks: read-only kubectl (names, env keys, pod logs for the #108 line) | INTEGRATE | 30-11, 30-15 — context jtoye-stg-aks only, never secret values |
| sms provider: OTP / payment-link delivery to a phone | OPT-OUT | not needed yet — no provider configured; staging proof uses the OTP seam per the owner's 30-16 decision; recorded as a known gap (30-32) routed to issue #461 / #208 |
