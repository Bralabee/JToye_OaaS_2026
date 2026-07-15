package uk.jtoye.core.notification.consent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Reads and writes the tenant-scoped consent state behind {@link ConsentGate}
 * and the public unsubscribe endpoint (COMMS-03).
 *
 * <p>Every method is {@code @Transactional} so {@code TenantSetLocalAspect} pins
 * the {@code app.current_tenant_id} GUC from {@link uk.jtoye.core.security.TenantContext}
 * before the RLS-guarded DB access — callers on off-request threads (the public
 * controller, later the dispatch listeners) MUST set the tenant context first.
 *
 * <p>{@link #suppress} is idempotent via the {@code INSERT ... ON CONFLICT DO
 * NOTHING} idiom against the {@code UNIQUE (tenant_id, recipient, category)} key,
 * so a replayed unsubscribe link is a no-op (the GDPR opt-out persists, never
 * duplicated, never expiring).
 */
@Service
public class SuppressionService {

    private final NotificationSuppressionRepository suppressionRepository;
    private final MarketingOptInRepository marketingOptInRepository;

    public SuppressionService(NotificationSuppressionRepository suppressionRepository,
                              MarketingOptInRepository marketingOptInRepository) {
        this.suppressionRepository = suppressionRepository;
        this.marketingOptInRepository = marketingOptInRepository;
    }

    @Transactional(readOnly = true)
    public boolean isSuppressed(UUID tenantId, String recipient, NotificationCategory category) {
        return suppressionRepository.existsByTenantIdAndRecipientAndCategory(tenantId, recipient, category);
    }

    @Transactional(readOnly = true)
    public boolean hasMarketingOptIn(UUID tenantId, String recipient) {
        return marketingOptInRepository.existsByTenantIdAndRecipient(tenantId, recipient);
    }

    /**
     * Idempotently record a per-category opt-out. Returns {@code true} when a new
     * suppression row was written (first unsubscribe) and {@code false} when one
     * already existed (a replayed link) — the public endpoint uses this to
     * distinguish "unsubscribed" from "already unsubscribed" without leaking
     * whether the address exists.
     */
    @Transactional
    public boolean suppress(UUID tenantId, String recipient, NotificationCategory category) {
        int inserted = suppressionRepository.insertIfAbsent(
                UUID.randomUUID(), tenantId, recipient, category.name());
        return inserted > 0;
    }
}
