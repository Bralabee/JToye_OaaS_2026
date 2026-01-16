package uk.jtoye.core.shop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ShopService.
 * Tests service layer business logic with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class ShopServiceTest {

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private ShopMapper shopMapper;

    @InjectMocks
    private ShopService shopService;

    private UUID tenantId;
    private UUID shopId;
    private Shop testShop;
    private CreateShopRequest validRequest;

    /**
     * Helper method to set private fields using reflection.
     * Needed for auto-generated fields like id and createdAt.
     */
    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();

        // Set up tenant context
        TenantContext.set(tenantId);

        // Create test shop (using reflection to set auto-generated fields)
        testShop = new Shop();
        setField(testShop, "id", shopId);
        testShop.setTenantId(tenantId);
        testShop.setName("Test Shop");
        testShop.setAddress("123 Test Street, London");
        setField(testShop, "createdAt", OffsetDateTime.now());

        // Create valid request
        validRequest = new CreateShopRequest();
        validRequest.setName("Test Shop");
        validRequest.setAddress("123 Test Street, London");

        // Mock ShopMapper behavior to mimic actual MapStruct implementation
        // Use lenient() to avoid UnnecessaryStubbingException in tests that don't use the mapper
        lenient().when(shopMapper.toDto(any(Shop.class))).thenAnswer(invocation -> {
            Shop shop = invocation.getArgument(0);
            ShopDto dto = new ShopDto();
            dto.setId(shop.getId());
            dto.setName(shop.getName());
            dto.setAddress(shop.getAddress());
            dto.setCreatedAt(shop.getCreatedAt());
            return dto;
        });
    }

    @Test
    @DisplayName("createShop - Success with valid request")
    void testCreateShop_Success() {
        // Given
        when(shopRepository.saveAndFlush(any(Shop.class))).thenAnswer(invocation -> {
            Shop shop = invocation.getArgument(0);
            setField(shop, "id", shopId);
            setField(shop, "createdAt", OffsetDateTime.now());
            return shop;
        });

        // When
        ShopDto result = shopService.createShop(validRequest);

        // Then
        assertNotNull(result);
        assertEquals(shopId, result.getId());
        assertEquals("Test Shop", result.getName());
        assertEquals("123 Test Street, London", result.getAddress());
        assertNotNull(result.getCreatedAt());

        ArgumentCaptor<Shop> shopCaptor = ArgumentCaptor.forClass(Shop.class);
        verify(shopRepository).saveAndFlush(shopCaptor.capture());

        Shop savedShop = shopCaptor.getValue();
        assertEquals(tenantId, savedShop.getTenantId());
        assertEquals("Test Shop", savedShop.getName());
    }

    @Test
    @DisplayName("createShop - Fails when tenant context not set")
    void testCreateShop_MissingTenant() {
        // Given
        TenantContext.clear();

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            shopService.createShop(validRequest);
        });

        assertEquals("Tenant context not set", exception.getMessage());
        verify(shopRepository, never()).saveAndFlush(any(Shop.class));
    }

    @Test
    @DisplayName("createShop - Sets tenant ID correctly")
    void testCreateShop_SetsTenantId() {
        // Given
        ArgumentCaptor<Shop> shopCaptor = ArgumentCaptor.forClass(Shop.class);
        when(shopRepository.saveAndFlush(shopCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        shopService.createShop(validRequest);

        // Then
        Shop savedShop = shopCaptor.getValue();
        assertEquals(tenantId, savedShop.getTenantId());
    }

    @Test
    @DisplayName("createShop - Handles null address")
    void testCreateShop_NullAddress() {
        // Given
        validRequest.setAddress(null);
        when(shopRepository.saveAndFlush(any(Shop.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ShopDto result = shopService.createShop(validRequest);

        // Then
        assertNotNull(result);
        assertNull(result.getAddress());
        verify(shopRepository).saveAndFlush(any(Shop.class));
    }

    @Test
    @DisplayName("getShopById - Success when shop exists")
    void testGetShopById_Success() {
        // Given
        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));

        // When
        Optional<ShopDto> result = shopService.getShopById(shopId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(shopId, result.get().getId());
        assertEquals("Test Shop", result.get().getName());
        assertEquals("123 Test Street, London", result.get().getAddress());
        verify(shopRepository).findById(shopId);
    }

    @Test
    @DisplayName("getShopById - Returns empty when shop not found")
    void testGetShopById_NotFound() {
        // Given
        when(shopRepository.findById(shopId)).thenReturn(Optional.empty());

        // When
        Optional<ShopDto> result = shopService.getShopById(shopId);

        // Then
        assertFalse(result.isPresent());
        verify(shopRepository).findById(shopId);
    }

    @Test
    @DisplayName("getAllShops - Returns paginated results")
    void testGetAllShops_Paginated() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        Page<Shop> shopPage = new PageImpl<>(List.of(testShop), pageable, 1);
        when(shopRepository.findAll(pageable)).thenReturn(shopPage);

        // When
        Page<ShopDto> result = shopService.getAllShops(pageable);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(shopId, result.getContent().get(0).getId());
        assertEquals("Test Shop", result.getContent().get(0).getName());
        verify(shopRepository).findAll(pageable);
    }

    @Test
    @DisplayName("updateShop - Success when shop exists")
    void testUpdateShop_Success() {
        // Given
        CreateShopRequest updateRequest = new CreateShopRequest();
        updateRequest.setName("Updated Shop");
        updateRequest.setAddress("456 New Street, Manchester");

        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(shopRepository.saveAndFlush(any(Shop.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ShopDto result = shopService.updateShop(shopId, updateRequest);

        // Then
        assertNotNull(result);
        assertEquals("Updated Shop", result.getName());
        assertEquals("456 New Street, Manchester", result.getAddress());

        verify(shopRepository).findById(shopId);
        verify(shopRepository).saveAndFlush(any(Shop.class));
    }

    @Test
    @DisplayName("updateShop - Fails when shop not found")
    void testUpdateShop_NotFound() {
        // Given
        when(shopRepository.findById(shopId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            shopService.updateShop(shopId, validRequest);
        });

        assertTrue(exception.getMessage().contains("Shop not found"));
        verify(shopRepository).findById(shopId);
        verify(shopRepository, never()).saveAndFlush(any(Shop.class));
    }

    @Test
    @DisplayName("updateShop - Updates all fields correctly")
    void testUpdateShop_UpdatesAllFields() {
        // Given
        ArgumentCaptor<Shop> shopCaptor = ArgumentCaptor.forClass(Shop.class);
        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(shopRepository.saveAndFlush(shopCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        CreateShopRequest updateRequest = new CreateShopRequest();
        updateRequest.setName("Brand New Name");
        updateRequest.setAddress("789 Another Street");

        // When
        shopService.updateShop(shopId, updateRequest);

        // Then
        Shop updatedShop = shopCaptor.getValue();
        assertEquals("Brand New Name", updatedShop.getName());
        assertEquals("789 Another Street", updatedShop.getAddress());
    }

    @Test
    @DisplayName("deleteShop - Success when shop exists")
    void testDeleteShop_Success() {
        // Given
        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));

        // When
        shopService.deleteShop(shopId);

        // Then
        verify(shopRepository).findById(shopId);
        verify(shopRepository).delete(testShop);
    }

    @Test
    @DisplayName("deleteShop - Fails when shop not found")
    void testDeleteShop_NotFound() {
        // Given
        when(shopRepository.findById(shopId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            shopService.deleteShop(shopId);
        });

        assertTrue(exception.getMessage().contains("Shop not found"));
        verify(shopRepository).findById(shopId);
        verify(shopRepository, never()).delete(any(Shop.class));
    }

    @Test
    @DisplayName("DTO mapping - Converts Shop entity to DTO correctly")
    void testDtoMapping_CorrectFieldMapping() {
        // Given
        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));

        // When
        Optional<ShopDto> result = shopService.getShopById(shopId);

        // Then
        assertTrue(result.isPresent());
        ShopDto dto = result.get();
        assertEquals(testShop.getId(), dto.getId());
        assertEquals(testShop.getName(), dto.getName());
        assertEquals(testShop.getAddress(), dto.getAddress());
        assertEquals(testShop.getCreatedAt(), dto.getCreatedAt());
    }

    @Test
    @DisplayName("updateShop - Preserves tenant ID")
    void testUpdateShop_PreservesTenantId() {
        // Given
        UUID originalTenantId = testShop.getTenantId();
        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(shopRepository.saveAndFlush(any(Shop.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        shopService.updateShop(shopId, validRequest);

        // Then
        assertEquals(originalTenantId, testShop.getTenantId()); // Tenant ID should not change
    }

    @Test
    @DisplayName("createShop - Handles empty string address")
    void testCreateShop_EmptyAddress() {
        // Given
        validRequest.setAddress("");
        when(shopRepository.saveAndFlush(any(Shop.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ShopDto result = shopService.createShop(validRequest);

        // Then
        assertNotNull(result);
        assertEquals("", result.getAddress());
        verify(shopRepository).saveAndFlush(any(Shop.class));
    }

    @Test
    @DisplayName("createShop - Handles long shop name")
    void testCreateShop_LongShopName() {
        // Given
        String longName = "A".repeat(255);
        validRequest.setName(longName);
        when(shopRepository.saveAndFlush(any(Shop.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ShopDto result = shopService.createShop(validRequest);

        // Then
        assertEquals(longName, result.getName());
        verify(shopRepository).saveAndFlush(any(Shop.class));
    }

    @Test
    @DisplayName("updateShop - Handles updating to null address")
    void testUpdateShop_UpdateToNullAddress() {
        // Given
        validRequest.setAddress(null);
        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(shopRepository.saveAndFlush(any(Shop.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        ShopDto result = shopService.updateShop(shopId, validRequest);

        // Then
        assertNull(result.getAddress());
        verify(shopRepository).saveAndFlush(any(Shop.class));
    }
}
