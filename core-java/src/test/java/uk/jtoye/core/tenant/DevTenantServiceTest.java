package uk.jtoye.core.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DevTenantService.
 * Tests service layer business logic with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class DevTenantServiceTest {

    @Mock
    private EntityManager em;

    @Mock
    private Query query;

    @InjectMocks
    private DevTenantService devTenantService;

    private UUID tenantId;
    private String tenantName;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenantName = "Test Tenant";
    }

    @Test
    @DisplayName("ensureTenantExists - Creates native query with correct SQL")
    void testEnsureTenantExists_CreatesCorrectQuery() {
        // Given
        String expectedSql = "INSERT INTO tenants (id, name) VALUES (:id, :name) ON CONFLICT (id) DO NOTHING";
        when(em.createNativeQuery(expectedSql)).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);

        // When
        devTenantService.ensureTenantExists(tenantId, tenantName);

        // Then
        verify(em).createNativeQuery(expectedSql);
    }

    @Test
    @DisplayName("ensureTenantExists - Sets id and name parameters correctly")
    void testEnsureTenantExists_SetsParametersCorrectly() {
        // Given
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);

        // When
        devTenantService.ensureTenantExists(tenantId, tenantName);

        // Then
        verify(query).setParameter(eq("id"), eq(tenantId));
        verify(query).setParameter(eq("name"), eq(tenantName));
    }

    @Test
    @DisplayName("ensureTenantExists - Executes update after setting parameters")
    void testEnsureTenantExists_ExecutesUpdate() {
        // Given
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);

        // When
        devTenantService.ensureTenantExists(tenantId, tenantName);

        // Then
        InOrder inOrder = inOrder(em, query);
        inOrder.verify(em).createNativeQuery(anyString());
        inOrder.verify(query).setParameter(eq("id"), eq(tenantId));
        inOrder.verify(query).setParameter(eq("name"), eq(tenantName));
        inOrder.verify(query).executeUpdate();
    }

    @Test
    @DisplayName("ensureTenantExists - Handles existing tenant (conflict does nothing)")
    void testEnsureTenantExists_ConflictDoesNothing() {
        // Given - executeUpdate returns 0 when ON CONFLICT DO NOTHING fires
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(0);

        // When - should not throw
        devTenantService.ensureTenantExists(tenantId, tenantName);

        // Then
        verify(query).executeUpdate();
    }

    @Test
    @DisplayName("ensureTenantExists - Handles empty tenant name")
    void testEnsureTenantExists_EmptyName() {
        // Given
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);

        // When
        devTenantService.ensureTenantExists(tenantId, "");

        // Then
        verify(query).setParameter(eq("name"), eq(""));
        verify(query).executeUpdate();
    }

    @Test
    @DisplayName("ensureTenantExists - Handles long tenant name")
    void testEnsureTenantExists_LongName() {
        // Given
        String longName = "A".repeat(255);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);

        // When
        devTenantService.ensureTenantExists(tenantId, longName);

        // Then
        verify(query).setParameter(eq("name"), eq(longName));
        verify(query).executeUpdate();
    }

    @Test
    @DisplayName("ensureTenantExists - Called multiple times with same ID is idempotent")
    void testEnsureTenantExists_IdempotentCalls() {
        // Given
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1).thenReturn(0);

        // When
        devTenantService.ensureTenantExists(tenantId, tenantName);
        devTenantService.ensureTenantExists(tenantId, tenantName);

        // Then
        verify(em, times(2)).createNativeQuery(anyString());
        verify(query, times(2)).executeUpdate();
    }
}
