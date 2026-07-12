package uk.jtoye.core.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.jtoye.core.exception.InvalidStateTransitionException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.tenant.dto.CreateTenantRequest;
import uk.jtoye.core.tenant.dto.TenantDto;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TenantLifecycleService} (issue #102 AC1): transition
 * legality, timestamps, and the TTL status cache + eviction path behind
 * {@code TenantStatusInterceptor}. The end-to-end enforcement (real RLS
 * Postgres, MockMvc, RBAC) lives in {@code TenantLifecycleAdminIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class TenantLifecycleServiceTest {

    @Mock private TenantRepository tenantRepository;

    private TenantLifecycleService service;

    private UUID tenantId;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        service = new TenantLifecycleService(tenantRepository);
        service.setStatusCacheTtlSeconds(30);

        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName("Vendor " + tenantId);
    }

    // ------------------------------------------------------------------
    // Transitions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create defaults to ACTIVE + STANDARD plan")
    void create_defaults() {
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantDto dto = service.create(new CreateTenantRequest(
                "Peckham Grill", null, "Ola", "ola@example.com", null));

        assertEquals(TenantStatus.ACTIVE, dto.status());
        assertEquals(TenantPlan.STANDARD, dto.plan());
        assertEquals("Peckham Grill", dto.name());
        assertEquals("ola@example.com", dto.contactEmail());
        assertEquals(StripeConnectStatus.NONE, dto.stripeConnectStatus());
    }

    @Test
    @DisplayName("suspend: ACTIVE -> SUSPENDED sets suspendedAt")
    void suspend_fromActive() {
        tenant.setStatus(TenantStatus.ACTIVE);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantDto dto = service.suspend(tenantId);

        assertEquals(TenantStatus.SUSPENDED, dto.status());
        assertNotNull(dto.suspendedAt());
    }

    @Test
    @DisplayName("suspend rejects a non-ACTIVE tenant (400 mapping)")
    void suspend_fromSuspended_rejected() {
        tenant.setStatus(TenantStatus.SUSPENDED);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThrows(InvalidStateTransitionException.class, () -> service.suspend(tenantId));
    }

    @Test
    @DisplayName("reactivate: SUSPENDED -> ACTIVE clears suspendedAt")
    void reactivate_fromSuspended() {
        tenant.setStatus(TenantStatus.SUSPENDED);
        tenant.setSuspendedAt(java.time.OffsetDateTime.now());
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantDto dto = service.reactivate(tenantId);

        assertEquals(TenantStatus.ACTIVE, dto.status());
        assertNull(dto.suspendedAt());
    }

    @Test
    @DisplayName("reactivate rejects an ACTIVE tenant")
    void reactivate_fromActive_rejected() {
        tenant.setStatus(TenantStatus.ACTIVE);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThrows(InvalidStateTransitionException.class, () -> service.reactivate(tenantId));
    }

    @Test
    @DisplayName("offboard works from ACTIVE and from SUSPENDED")
    void offboard_fromActiveAndSuspended() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        tenant.setStatus(TenantStatus.ACTIVE);
        assertEquals(TenantStatus.OFFBOARDED, service.offboard(tenantId).status());

        tenant.setStatus(TenantStatus.SUSPENDED);
        assertEquals(TenantStatus.OFFBOARDED, service.offboard(tenantId).status());
        assertNotNull(tenant.getOffboardedAt());
    }

    @Test
    @DisplayName("OFFBOARDED is terminal — no suspend, reactivate, or re-offboard")
    void offboarded_isTerminal() {
        tenant.setStatus(TenantStatus.OFFBOARDED);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThrows(InvalidStateTransitionException.class, () -> service.suspend(tenantId));
        assertThrows(InvalidStateTransitionException.class, () -> service.reactivate(tenantId));
        assertThrows(InvalidStateTransitionException.class, () -> service.offboard(tenantId));
    }

    @Test
    @DisplayName("lifecycle ops 404 an unknown tenant")
    void unknownTenant_notFound() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.suspend(tenantId));
        assertThrows(ResourceNotFoundException.class, () -> service.get(tenantId));
    }

    // ------------------------------------------------------------------
    // Status cache + eviction path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("statusOf caches: second lookup within TTL does not hit the repository")
    void statusOf_cached() {
        tenant.setStatus(TenantStatus.ACTIVE);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertEquals(TenantStatus.ACTIVE, service.statusOf(tenantId));
        assertEquals(TenantStatus.ACTIVE, service.statusOf(tenantId));

        verify(tenantRepository, times(1)).findById(tenantId);
        assertEquals(Optional.of(TenantStatus.ACTIVE), service.peekCachedStatus(tenantId));
    }

    @Test
    @DisplayName("lifecycle mutations evict the cached status — enforcement is immediate on this instance")
    void mutation_evictsCache() {
        tenant.setStatus(TenantStatus.ACTIVE);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        assertFalse(service.isRequestBlocked(tenantId)); // primes the cache with ACTIVE

        service.suspend(tenantId); // mutates + evicts

        // Fresh lookup (cache was evicted) now sees SUSPENDED and blocks.
        assertTrue(service.isRequestBlocked(tenantId));
    }

    @Test
    @DisplayName("expired cache entries are refetched (TTL bounds cross-instance staleness)")
    void statusOf_ttlExpiry_refetches() {
        service.setStatusCacheTtlSeconds(-1); // entries are born expired
        tenant.setStatus(TenantStatus.ACTIVE);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        service.statusOf(tenantId);
        service.statusOf(tenantId);

        verify(tenantRepository, atLeast(2)).findById(tenantId);
    }

    @Test
    @DisplayName("isRequestBlocked: SUSPENDED and OFFBOARDED block; ACTIVE and missing-row do not")
    void isRequestBlocked_semantics() {
        tenant.setStatus(TenantStatus.SUSPENDED);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        assertTrue(service.isRequestBlocked(tenantId));

        service.evictStatus(tenantId);
        tenant.setStatus(TenantStatus.OFFBOARDED);
        assertTrue(service.isRequestBlocked(tenantId));

        service.evictStatus(tenantId);
        tenant.setStatus(TenantStatus.ACTIVE);
        assertFalse(service.isRequestBlocked(tenantId));

        // Missing registry row (legacy/dev tenant contexts) must NOT block —
        // the /dev/tenants/ensure flow runs before the row exists.
        UUID ghost = UUID.randomUUID();
        when(tenantRepository.findById(ghost)).thenReturn(Optional.empty());
        assertFalse(service.isRequestBlocked(ghost));
    }
}
