-- V92 [#868, owner ruling 2026-11-04 — a customer who cancels an order paid in parts is refunded what was
-- actually paid, one refund per captured PaymentIntent].
--
-- A deposit-split order (V87) is paid by TWO PaymentIntents: the deposit at checkout and the balance later.
-- `orders.payment_reference` holds "the" PaymentIntent, and the balance webhook OVERWRITES it with the balance
-- intent, so once both are paid the deposit intent's id existed nowhere on the order. A refund is made against a
-- PaymentIntent, so the deposit leg could not be refunded without it.
--
-- `deposit_payment_reference` keeps the deposit PaymentIntent id, written once by the balance-stage handler
-- before it overwrites `payment_reference`. orders_aud mirrors it in the SAME migration (Order is @Audited).
--
-- NO BACKFILL, deliberately: NULL means "not recorded". An order paid in both stages BEFORE this migration has
-- no deposit intent on file, and the self-cancel path REFUSES it (409, contact the shop) rather than guess an
-- intent or refund only the balance. The id is a Stripe object id, not personal data. No UPDATE, so no tenant
-- loop and no RLS-backfill trap; no new table, no policy change.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS deposit_payment_reference VARCHAR(255);

ALTER TABLE orders_aud
    ADD COLUMN IF NOT EXISTS deposit_payment_reference VARCHAR(255);
