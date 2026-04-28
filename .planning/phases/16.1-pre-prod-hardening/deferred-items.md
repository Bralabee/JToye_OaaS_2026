# Phase 16.1 — Deferred Items

Issues discovered during phase execution but deferred per scope-boundary rules.
These are NOT blockers for the plans in this phase; they are pre-existing
latent issues that surfaced in passing.

---

## D1 — order_items_aud missing product_name column

**Discovered:** 2026-04-27 during Plan 16.1-04 execution (StripeWebhookIdempotencyIntegrationTest first run with `ddl-auto: validate`).

**Symptom:**
```
Schema-validation: missing column [product_name] in table [order_items_aud]
```

**Cause:** V30 (`V30__order_item_product_name.sql`) added `product_name` to `order_items` but did NOT add the matching column to the Envers audit table `order_items_aud`. Hibernate's `validate` mode catches this; `create-drop` (the test profile default) hides it because Hibernate rebuilds the schema from the JPA model on boot.

**Impact:** Production runs `ddl-auto: none` so the missing audit column does not break the running service — but Envers writes to `order_items_aud` will silently drop the `product_name` value on every audited Order mutation. Audit-trail completeness is degraded, not broken.

**Why deferred:** Out of scope for Plan 16.1-04 (AUDIT-W0-03 scope is Stripe webhook idempotency). Pre-existing latent migration drift, not caused by this plan's changes.

**Recommended fix:** Add a small Flyway migration (next free slot, currently V36) that runs:
```sql
ALTER TABLE order_items_aud ADD COLUMN IF NOT EXISTS product_name VARCHAR(255);
```
Ship in a `/gsd-quick` or fold into Phase 17 prep.

---

*Last updated: 2026-04-27*
