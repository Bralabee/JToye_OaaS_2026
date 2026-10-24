# Phase 30 — Live Stripe Test-Mode Execution (`PAY-01..04`, `GAP-GTM-05`) Verification Report

- **Date:** 2026-10-17
- **Branch:** `feat/phase-30-live-stripe-billing-and-phone-verify`
- **Migration:** `V88__billing_tiers_and_phone_verification.sql`
- **Machine-Readable Stripe API Proof Artifact:** [`evidence/30-live-stripe-api-proofs.json`](./evidence/30-live-stripe-api-proofs.json)

---

## 1. Requirement Matrix & Live Stripe Object Proofs

| Requirement / Issue | Scope | Implementation | Live Stripe API Proof (`livemode=false`) | Status |
|---|---|---|---|---|
| **`PAY-01` (`#61`)** | Stored-first idempotent order refund against live Stripe Test Mode | `RefundService`, `StripeRefundClient` | `PaymentIntent`: `pi_3URXkHDBRetDCIGQ0XaaTGUa` (`2400p`, `succeeded`)<br>`Refund`: `re_3URXkHDBRetDCIGQ0O3oh4zy` (`100p`, `gbp`, `succeeded`, `requested_by_customer`) | **VERIFIED** |
| **`PAY-02` (`GAP-GTM-05`, `#102` remainder, `B8`, `B9`, `D-B9`)** | Plan-configurable recurring subscription billing (`SAAS_HYBRID_STANDARD`, `SAAS_FLAT_PREDICTABLE`, `BATCH_PAYG_STARTER`, `WALK_SETUP_STEPDOWN`) + Stripe Test Clock + self-serve cancellation | `BillingPlanMode`, `SubscriptionStatus`, `StripeBillingService`, `BillingController`, `StripeConnectService.applicationFeePennies`, `V88` | **1. `SAAS_HYBRID_STANDARD` (`£39/mo + 0.5%`):** `sub_1URXjbDBRetDCIGQAUrclx7j` (`active`), Invoice retrieved by ID: `in_1URXjbDBRetDCIGQNQgUctSo` (`amount_paid=3900`, `gbp`, `paid`)<br>**2. `SAAS_FLAT_PREDICTABLE` (`£79/mo + 0%`):** `sub_1URXjhDBRetDCIGQcbXrM9QA` (`active`), Invoice retrieved by ID: `in_1URXjhDBRetDCIGQ1pjFdDY8` (`amount_paid=7900`, `gbp`, `paid`)<br>**3. `BATCH_PAYG_STARTER` (`£0/mo + 6.0%`):** `pi_3URXjjDBReCFyfl50meoQppp` (`amount=5000`, `application_fee_amount=300`, `succeeded`)<br>**4. `WALK_SETUP_STEPDOWN` (`D-B9`) + Test Clock (`B8`):** `clock_1URXjkDBRetDCIGQfnXFaDoW` (`ready`), `sub_1URXjnDBRetDCIGQAE2YIpSx`, setup invoice `in_1URXjnDBRetDCIGQ92TXdzs3` (`19500p`, `paid`), renewal invoice after 32-day clock advance `in_1URXjtDBRetDCIGQ3M6E6ooP` (`19500p`, `paid`), self-serve cancel -> `status=canceled` | **VERIFIED** |
| **`PAY-03` (`#102`, `GAP-GTM-01`)** | Connected-account full loop: onboard → order → capture → transfer → payout → refund across both `DIRECT_CHARGE_VENDOR_MOR` and `DESTINATION_CHARGE_PLATFORM_MOR` | `StripeConnectService`, `PaymentService`, `RefundService` (`RequestOptions.setStripeAccount` + `setReverseTransfer(true)`) | **Connected Accounts:** `acct_1URWuCDBReCFyfl5` (`GB`, `gbp`, `charges_enabled=true`, `payouts_enabled=true`, `ba_1URWuKDBReCFyfl5CHUFDgvK`) & Express `acct_1URWr2DBRebezRB3`<br>**Direct Charge (`DIRECT_CHARGE_VENDOR_MOR`):** `pi_3URXk5DBReCFyfl51VYkh9Zw` (`4000p`, `fee=20p`), `ch_3URXk5DBReCFyfl51YKXYMGV`, Refund on connected account: `re_3URXk5DBReCFyfl51SkKyVtm` (`1000p`, `succeeded`)<br>**Destination Charge (`DESTINATION_CHARGE_PLATFORM_MOR`):** `pi_3URXk7DBRetDCIGQ14j9x0Bc` (`4000p`, `fee=200p`), `ch_3URXk7DBRetDCIGQ1oY01luK`, Transfer `tr_3URXhVDBRetDCIGQ0Q5PbFe5`, Connected-Account Payout `po_1URXk9DBReCFyfl5wT7HFaW5` (`1500p`, `paid` -> `ba_1URWuKDBReCFyfl5CHUFDgvK`), Reverse-Transfer Refund `re_3URXk7DBRetDCIGQ1mC6B54H` (`succeeded`, `transfer_reversal=trr_1URXkCDBRetDCIGQvw0yUsJC`) | **VERIFIED** |
| **`PAY-04` (`#461`, `#462`, `B5`, `GAP-GTM-04`)** | Telephone OTP verification (`customers.phone_verified`, `orders.phone_verified`, `phone_verification_challenges`), single-use expiring payment links (`order_payment_links` with DB `CHECK (phone_verified = true)`), and kitchen `PREPARING` guard on unpaid orders | `PhoneVerificationService`, `OrderPaymentLinkService`, `OrderPaymentLinkController`, `PublicPhoneAndPaymentLinkController`, `OrderService.isAwaitingUpfrontOrDepositPayment`, `V88` | **1. `UPFRONT` Link:** `pi_3URXkEDBRetDCIGQ1C4SPBHj` (`3250p`, `succeeded`, `phone_verified=true`, `+447700900123`)<br>**2. `SPLIT_DEPOSIT_VENDOR_MOR` `DEPOSIT` Link (50%):** `pi_3URXkFDBReCFyfl51yr0NA9y` (`4000p`, `fee=20p`, `succeeded`, `phone_verified=true`, `+447700900456`)<br>**3. `SPLIT_DEPOSIT_VENDOR_MOR` `BALANCE` Link (50%):** `pi_3URXkGDBReCFyfl50xY8SgIo` (`4000p`, `fee=20p`, `succeeded`, `phone_verified=true`, `+447700900456`) | **VERIFIED** |

---

## 2. Automated Verification Suites Executed

1. **Unit & Service Contract Suites (`rc=0`):**
   - `uk.jtoye.core.config.ApiPrefixConventionTest`
   - `uk.jtoye.core.payment.StripeBillingServiceTest` (all 4 `BillingPlanMode` presets, `WALK_SETUP_STEPDOWN` `£195/mo -> 2.5%` capped at `£39/mo`, self-serve cancel, invoice webhook sync)
   - `uk.jtoye.core.payment.StripeConnectServiceTest` (per-plan fee calculation + `WALK_SETUP_STEPDOWN` monthly cap)
   - `uk.jtoye.core.payment.RefundServiceTest` (`DIRECT_CHARGE_VENDOR_MOR` `Stripe-Account` header + `DESTINATION_CHARGE_PLATFORM_MOR` `reverse_transfer=true`)
   - `uk.jtoye.core.payment.PhoneVerificationAndPaymentLinkTest` (OTP challenge/verify, fail-before-pass 422 on unverified phone, single-use token redemption + 410 Gone on replay, `DEPOSIT` + `BALANCE` split-deposit links)
   - `uk.jtoye.core.order.OrderServiceTest` (blocks `CONFIRMED -> PREPARING` when `PaymentStatus` is `PENDING` or `FAILED`, permits `CAPTURED` and `AUTHORIZED`)
   - `uk.jtoye.core.payment.PaymentServiceTest`
2. **Testcontainers PostgreSQL 18 RLS & Schema Suites (`rc=0`):**
   - `uk.jtoye.core.security.RlsContractTest` (`everyPublicTableHasRlsAndForce`, `everyRlsEnabledTableHasAtLeastOnePolicy`, `noPolicyReadsBuggyAppTenantIdGuc`, `noPolicyUsesRawTenantGucCast`)
   - `uk.jtoye.core.payment.PhoneVerificationAndPaymentLinkRlsIntegrationTest` (cross-tenant RLS isolation on `phone_verification_challenges` and `order_payment_links`, `ck_order_payment_links_phone_verified` DB constraint fail-before-pass proof, `ck_tenants_billing_plan_mode` DB constraint proof)
3. **Environment Contract Gate (`rc=0`):**
   - `bash k8s/scripts/check-env-contract.sh` (`OK: 21/21 non-secret and 7/7 secret key(s) match k8s/base; 0 violation(s)`)
