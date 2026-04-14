-- V31: Transactional outbox for payment events.
--
-- Previously PaymentEventPublisher called rabbitTemplate.convertAndSend directly
-- inside a Stripe webhook transaction and swallowed failures via log.error. A
-- broker outage silently dropped audit / analytics events with no recovery.
--
-- This table lets the publisher persist the event in the SAME transaction as
-- the order mutation; a scheduled flusher picks up PENDING rows and publishes
-- them to RabbitMQ, marking SENT on success and tracking attempts/last_error
-- on failure. Events are durable even if RabbitMQ is down.

CREATE TABLE IF NOT EXISTS payment_event_outbox (
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    event_type  VARCHAR(64) NOT NULL,
    routing_key VARCHAR(128) NOT NULL,
    payload     TEXT        NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts    INT         NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    sent_at     TIMESTAMP WITH TIME ZONE
);

-- Flusher query path: pull PENDING rows oldest-first.
CREATE INDEX IF NOT EXISTS idx_payment_event_outbox_status_created_at
    ON payment_event_outbox (status, created_at);

-- Tenant-scoped diagnostics path.
CREATE INDEX IF NOT EXISTS idx_payment_event_outbox_tenant
    ON payment_event_outbox (tenant_id);
