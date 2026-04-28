-- V37: Phase 17 WR-01 — refunds_aud is missing the `version` column that
-- the Refund entity declares as @Version. Envers includes @Version columns
-- in the audit table by default unless explicitly excluded with @NotAudited,
-- so without this column every UPDATE on a Refund row (markRefundFailed,
-- applyStripeStatusToRefund, post-Stripe success path) raises
-- `org.hibernate.exception.SQLGrammarException: Column "VERSION" not found`.
--
-- Forward-only fix; V36 already shipped on the feature branch so we ADD
-- rather than amending V36. orders_aud (V4-V11) sets the precedent of
-- mirroring every persistent column, including @Version.

ALTER TABLE refunds_aud ADD COLUMN version BIGINT;
