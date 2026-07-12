package uk.jtoye.core.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.jtoye.core.exception.InvalidStateTransitionException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.tenant.dto.CreateTenantRequest;
import uk.jtoye.core.tenant.dto.TenantDto;
import uk.jtoye.core.tenant.keycloak.KeycloakDeprovisionService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production tenant lifecycle (issue #102 [P2-11] AC1): create / suspend /
 * reactivate / offboard without SQL, plus the request-time status lookup used
 * by {@code TenantStatusInterceptor} to reject suspended/offboarded tenants'
 * traffic.
 *
 * <p><b>Transitions:</b> {@code ACTIVE → SUSPENDED → ACTIVE} (reversible) and
 * {@code ACTIVE|SUSPENDED → OFFBOARDED} (terminal). Anything else throws
 * {@link InvalidStateTransitionException} (→ 400, same mapping the order state
 * machine uses).
 *
 * <p><b>Status cache & eviction path:</b> the interceptor consults
 * {@link #isRequestBlocked(UUID)} on every tenant-scoped request, so the
 * lookup is served from a small in-memory TTL cache
 * ({@code tenant-lifecycle.status-cache-ttl-seconds}, default 30s) instead of
 * a per-request DB read. Every lifecycle mutation calls
 * {@link #evictStatus(UUID)}, so enforcement on THIS instance is immediate;
 * other instances converge within one TTL window (the same bounded-staleness
 * trade-off the rate limiter makes for availability). A distributed eviction
 * bus is deliberately out of scope for this slice.
 */
@Service
public class TenantLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(TenantLifecycleService.class);

    private final TenantRepository tenantRepository;
    private final KeycloakDeprovisionService keycloakDeprovisionService;

    /** TTL for the request-time status cache, seconds. */
    @Value("${tenant-lifecycle.status-cache-ttl-seconds:30}")
    private long statusCacheTtlSeconds;

    /**
     * tenantId → (status, expiresAtMillis). Unbounded in theory but keyed by
     * real tenant ids observed on requests — cardinality equals the tenant
     * count, which is small by construction for this platform.
     */
    private final ConcurrentHashMap<UUID, CachedStatus> statusCache = new ConcurrentHashMap<>();

    private record CachedStatus(TenantStatus status, long expiresAtMillis) {
        boolean expired() { return System.currentTimeMillis() > expiresAtMillis; }
    }

    public TenantLifecycleService(TenantRepository tenantRepository,
                                  KeycloakDeprovisionService keycloakDeprovisionService) {
        this.tenantRepository = tenantRepository;
        this.keycloakDeprovisionService = keycloakDeprovisionService;
    }

    // ------------------------------------------------------------------
    // Admin lifecycle operations (AC1)
    // ------------------------------------------------------------------

    @Transactional
    public TenantDto create(CreateTenantRequest request) {
        Tenant tenant = new Tenant();
        tenant.setName(request.name().trim());
        tenant.setPlan(request.plan() != null ? request.plan() : TenantPlan.STANDARD);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setContactName(request.contactName());
        tenant.setContactEmail(request.contactEmail());
        tenant.setContactPhone(request.contactPhone());
        tenant.setUpdatedAt(OffsetDateTime.now());
        Tenant saved = tenantRepository.save(tenant);
        log.info("event=tenant_created tenant={} plan={}", saved.getId(), saved.getPlan());
        return TenantDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<TenantDto> list() {
        return tenantRepository.findAll(Sort.by("createdAt")).stream()
                .map(TenantDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TenantDto get(UUID tenantId) {
        return TenantDto.from(require(tenantId));
    }

    @Transactional
    public TenantDto suspend(UUID tenantId) {
        Tenant tenant = require(tenantId);
        assertTransition(tenant, TenantStatus.SUSPENDED, TenantStatus.ACTIVE);
        tenant.setStatus(TenantStatus.SUSPENDED);
        tenant.setSuspendedAt(OffsetDateTime.now());
        tenant.setUpdatedAt(OffsetDateTime.now());
        Tenant saved = tenantRepository.save(tenant);
        evictStatus(tenantId);
        log.warn("event=tenant_suspended tenant={}", tenantId);
        return TenantDto.from(saved);
    }

    @Transactional
    public TenantDto reactivate(UUID tenantId) {
        Tenant tenant = require(tenantId);
        assertTransition(tenant, TenantStatus.ACTIVE, TenantStatus.SUSPENDED);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setSuspendedAt(null);
        tenant.setUpdatedAt(OffsetDateTime.now());
        Tenant saved = tenantRepository.save(tenant);
        evictStatus(tenantId);
        log.info("event=tenant_reactivated tenant={}", tenantId);
        return TenantDto.from(saved);
    }

    /**
     * Terminal offboarding. Keycloak user deprovisioning (issue #102 remainder)
     * now runs best-effort AFTER this transaction commits: an
     * {@link TransactionSynchronization#afterCommit()} hook invokes
     * {@link KeycloakDeprovisionService#deprovision(UUID)}, which disables + logs
     * out the tenant's Keycloak users so a stolen/cached token can no longer
     * mint or keep a session at the IdP. Running after-commit and outside this tx
     * means a Keycloak outage can NEVER roll back or fail the offboard — the
     * tenant still reaches OFFBOARDED, the marker just stays NULL and an ERROR is
     * logged. Synchronous enforcement via {@link #isRequestBlocked(UUID)} remains
     * the hard guarantee; deprovisioning is the identity-layer complement. The
     * feature is fully inert unless configured (default off).
     */
    @Transactional
    public TenantDto offboard(UUID tenantId) {
        Tenant tenant = require(tenantId);
        assertTransition(tenant, TenantStatus.OFFBOARDED, TenantStatus.ACTIVE, TenantStatus.SUSPENDED);
        tenant.setStatus(TenantStatus.OFFBOARDED);
        tenant.setOffboardedAt(OffsetDateTime.now());
        tenant.setUpdatedAt(OffsetDateTime.now());
        Tenant saved = tenantRepository.save(tenant);
        evictStatus(tenantId);
        log.warn("event=tenant_offboarded tenant={}", tenantId);

        // Best-effort identity-layer deprovisioning, AFTER this tx commits and
        // outside it, so a Keycloak failure cannot roll back the offboard. The
        // service is already non-throwing; the try/catch is belt-and-braces.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        keycloakDeprovisionService.deprovision(tenantId);
                    } catch (Throwable t) {
                        log.error("event=tenant_keycloak_deprovision_hook_error tenant={}: {}",
                                tenantId, t.getMessage());
                    }
                }
            });
        }
        return TenantDto.from(saved);
    }

    // ------------------------------------------------------------------
    // Request-time status enforcement lookup
    // ------------------------------------------------------------------

    /**
     * True when the tenant's API traffic must be rejected (SUSPENDED or
     * OFFBOARDED). Served from the TTL cache; a tenant id with no registry row
     * (legacy/dev contexts) is treated as not-blocked — RLS still scopes its
     * data, and the dev {@code /dev/tenants/ensure} flow depends on the
     * pre-insert request being allowed through.
     */
    public boolean isRequestBlocked(UUID tenantId) {
        TenantStatus status = statusOf(tenantId);
        return status == TenantStatus.SUSPENDED || status == TenantStatus.OFFBOARDED;
    }

    /** Cached status lookup; missing registry row → ACTIVE (see {@link #isRequestBlocked}). */
    public TenantStatus statusOf(UUID tenantId) {
        CachedStatus cached = statusCache.get(tenantId);
        if (cached != null && !cached.expired()) {
            return cached.status();
        }
        TenantStatus fresh = tenantRepository.findById(tenantId)
                .map(Tenant::getStatus)
                .orElse(TenantStatus.ACTIVE);
        statusCache.put(tenantId, new CachedStatus(
                fresh, System.currentTimeMillis() + statusCacheTtlSeconds * 1000));
        return fresh;
    }

    /**
     * Eviction path for status changes: called by every lifecycle mutation
     * above (and test-visible). Same-instance enforcement is immediate; other
     * instances converge within {@code tenant-lifecycle.status-cache-ttl-seconds}.
     */
    public void evictStatus(UUID tenantId) {
        statusCache.remove(tenantId);
    }

    // ------------------------------------------------------------------

    private Tenant require(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
    }

    private static void assertTransition(Tenant tenant, TenantStatus target, TenantStatus... allowedFrom) {
        for (TenantStatus from : allowedFrom) {
            if (tenant.getStatus() == from) {
                return;
            }
        }
        throw new InvalidStateTransitionException(
                "Cannot transition tenant from " + tenant.getStatus() + " to " + target);
    }

    /** Test seam: package-private TTL override without a Spring context. */
    void setStatusCacheTtlSeconds(long seconds) {
        this.statusCacheTtlSeconds = seconds;
    }

    /** Test seam: observe whether a status is currently cached. */
    Optional<TenantStatus> peekCachedStatus(UUID tenantId) {
        CachedStatus cached = statusCache.get(tenantId);
        return (cached == null || cached.expired()) ? Optional.empty() : Optional.of(cached.status());
    }
}
