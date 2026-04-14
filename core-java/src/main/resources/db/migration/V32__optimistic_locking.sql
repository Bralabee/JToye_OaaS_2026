-- V32: Optimistic locking columns for orders and shops.
--
-- JPA's @Version annotation needs a NOT NULL BIGINT column it can
-- increment on every UPDATE. Existing rows get 0 as their starting
-- version; new inserts are managed by Hibernate.
--
-- Pairs with @Version in Order.java and Shop.java. Concurrent writes
-- to the same row now throw ObjectOptimisticLockingFailureException
-- instead of silently overwriting each other's changes — critical for
-- stock-adjusting order transitions and shop config updates.

ALTER TABLE orders ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE shops  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
