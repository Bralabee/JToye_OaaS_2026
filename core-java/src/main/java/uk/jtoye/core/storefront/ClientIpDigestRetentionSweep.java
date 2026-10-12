package uk.jtoye.core.storefront;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.SystemPrincipal;

import java.util.List;
import java.util.UUID;

/**
 * The declared lifetime of the pseudonymous client-address digest (Phase 37-28, D-27; RWO-041 / RWO-006).
 *
 * <h2>Why this exists</h2>
 *
 * {@code orders.client_ip_digest} is a keyed HMAC of the customer's client address, written so the per-IP
 * cash-order cap can be a query. It is needed only while the order is OPEN — that is what the cap counts
 * — and {@code OrderService} nulls it the moment an order reaches a terminal state. But an order is not
 * guaranteed to reach one: a kitchen can leave a PENDING order on the board for weeks, and a pseudonymous
 * identifier of a person's network address must not live with it for weeks. So the digest also has a
 * retention window in hours ({@code jtoye.storefront.client-ip-digest.retention-hours}, 72 — the
 * declared, published number) and this sweep nulls, once a day, every digest older than that in every
 * tenant, whatever the order's status. After it the row still says who ordered what; it no longer says
 * from where.
 *
 * <h2>Per tenant, pinned — read before editing the loop</h2>
 *
 * {@code orders} is under FORCE row-level security, and the application role is not exempt. A bare
 * {@code UPDATE orders SET client_ip_digest = NULL WHERE ...} from a session with no tenant pinned
 * matches ZERO rows and reports success — the same silent nothing that bit V25, V44, V57, V78, V79 and
 * V80 in migrations, and that a retention job would repeat every night without a test that could see it.
 * So the sweep iterates the {@code tenants} registry (no RLS, by design) and runs the UPDATE once per
 * tenant with {@link TenantContext} set and the GUC pinned in that tenant's own transaction — the
 * {@code DsarFanoutWorker} / {@code MediaQuarantineRetentionSweep} shape. {@link SystemPrincipal#asSystem}
 * declares the work internal (a background entry point, never a request thread); it is an authorisation
 * marker and grants no cross-tenant reach whatsoever — the reach IS the loop plus the pin. The proof is
 * {@code ClientIpDigestRetentionIntegrationTest}: two tenants, NOSUPERUSER role, and a break arm that
 * drops the pin and watches both tenants' digests survive.
 *
 * <h2>What it logs</h2>
 *
 * A count per tenant and a total, nothing else: no order id, no digest, nothing about a client. One
 * tenant's failure is logged and the loop continues; a retention job must not be stopped for every
 * tenant by one tenant's error.
 */
@Component
public class ClientIpDigestRetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(ClientIpDigestRetentionSweep.class);

    /** The Spring property the window is read from. Published as the retention period for this data. */
    public static final String RETENTION_PROPERTY = "jtoye.storefront.client-ip-digest.retention-hours";

    private static final String NULL_EXPIRED_SQL =
            "UPDATE orders SET client_ip_digest = NULL "
                    + "WHERE tenant_id = :tenantId AND client_ip_digest IS NOT NULL "
                    + "AND created_at < now() - make_interval(hours => :hours)";

    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final int retentionHours;

    public ClientIpDigestRetentionSweep(EntityManager entityManager,
                                        PlatformTransactionManager transactionManager,
                                        @Value("${" + RETENTION_PROPERTY + "}") int retentionHours) {
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        if (retentionHours < 1) {
            throw new IllegalStateException(RETENTION_PROPERTY + " must be at least 1, was " + retentionHours
                    + ": a window below an hour would erase the identity the open-order cap counts on");
        }
        this.retentionHours = retentionHours;
    }

    /**
     * Null every digest older than the retention window, in every tenant. Daily, from config; the
     * inline default keeps the job running if the key is ever absent — a retention job must not be
     * switched off by omission.
     *
     * @return how many digests were nulled across all tenants
     */
    @Scheduled(cron = "${jtoye.storefront.client-ip-digest.sweep-cron:0 20 3 * * *}")
    public int sweep() {
        List<UUID> tenantIds = listTenantIds();
        int total = 0;
        for (UUID tenantId : tenantIds) {
            try {
                total += sweepTenant(tenantId);
            } catch (Exception e) {
                log.error("event=client_ip_digest_sweep_failed tenant={} — continuing: {}", tenantId, e.getMessage());
            }
        }
        if (total > 0) {
            log.info("event=client_ip_digests_expired count={} olderThanHours={} tenants={}",
                    total, retentionHours, tenantIds.size());
        }
        return total;
    }

    private int sweepTenant(UUID tenantId) {
        TenantContext.set(tenantId);
        try {
            Integer nulled = transactionTemplate.execute(status -> {
                pinTenantGuc(tenantId);
                return SystemPrincipal.asSystem(() -> entityManager.createNativeQuery(NULL_EXPIRED_SQL)
                        .setParameter("tenantId", tenantId)
                        .setParameter("hours", retentionHours)
                        .executeUpdate());
            });
            int count = nulled == null ? 0 : nulled;
            if (count > 0) {
                log.info("event=client_ip_digests_expired tenant={} count={}", tenantId, count);
            }
            return count;
        } finally {
            // ALWAYS: pooled threads; a stale tenant left behind is a cross-tenant read waiting to happen.
            TenantContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private List<UUID> listTenantIds() {
        return transactionTemplate.execute(status ->
                entityManager.createNativeQuery("SELECT id FROM tenants").getResultList());
    }

    /**
     * The GUC the orders policies read, pinned on THIS transaction's connection. {@code TenantContext} is
     * set as well so {@code TenantSetLocalAspect} pins any repository call on the same path; neither
     * alone is the reach — the loop is.
     */
    private void pinTenantGuc(UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
            }
        });
    }
}
