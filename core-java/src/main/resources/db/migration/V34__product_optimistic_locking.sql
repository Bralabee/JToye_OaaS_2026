-- V34: Optimistic locking column for products (pairs with @Version in Product.java).
-- Parallels V32 which added the column to orders + shops. Default 0 for existing
-- rows; new inserts are managed by Hibernate. Enables stock race fix (CQ-01).
--
-- Without this column, two concurrent CONFIRM events on the last-in-stock product
-- could both succeed because adjustStockInBatch silently clamped with Math.max(0, ...)
-- instead of throwing. With @Version the UPDATE predicate becomes
--   UPDATE products SET ..., version = version + 1 WHERE id = ? AND version = ?
-- and the loser's UPDATE reports zero rows touched, raising
-- ObjectOptimisticLockingFailureException. StockService wraps decrementForOrder in
-- @Retryable(ObjectOptimisticLockingFailureException.class, maxAttempts=3, backoff=50ms)
-- so one retry re-reads the current stock and either succeeds (if inventory remains)
-- or throws InsufficientStockException (HTTP 409) — no oversell.

ALTER TABLE products ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
