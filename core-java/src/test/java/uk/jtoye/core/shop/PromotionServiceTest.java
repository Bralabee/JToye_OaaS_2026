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
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.shop.dto.CreatePromotionRequest;
import uk.jtoye.core.shop.dto.PromotionDto;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PromotionService.
 * Tests service layer business logic with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class PromotionServiceTest {

    @Mock
    private ShopPromotionRepository promotionRepository;

    @Mock
    private PromotionMapper promotionMapper;

    @Mock
    private ShopAccessService shopAccessService;

    @InjectMocks
    private PromotionService promotionService;

    private UUID tenantId;
    private UUID promotionId;
    private UUID shopId;
    private ShopPromotion testPromotion;
    private CreatePromotionRequest percentageRequest;
    private CreatePromotionRequest flatAmountRequest;
    private PromotionDto testDto;

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
        promotionId = UUID.randomUUID();
        shopId = UUID.randomUUID();

        TenantContext.set(tenantId);

        // Phase 23 (VSA-02): run as a GROUP_ADMIN so require(...) is a no-op and the
        // read-scope list takes the full-tenant path (existing assertions unchanged).
        lenient().when(shopAccessService.isGroupAdmin()).thenReturn(true);

        // Create test promotion entity
        testPromotion = new ShopPromotion();
        setField(testPromotion, "id", promotionId);
        testPromotion.setTenantId(tenantId);
        testPromotion.setShopId(shopId);
        testPromotion.setLabel("10% Off Everything");
        testPromotion.setDiscountType(DiscountType.PERCENTAGE);
        testPromotion.setDiscountPercent(10);
        testPromotion.setCategory("All");
        testPromotion.setValidFrom(OffsetDateTime.now().minusDays(1));
        testPromotion.setValidUntil(OffsetDateTime.now().plusDays(30));
        testPromotion.setActive(true);

        // Create test DTO
        testDto = new PromotionDto();
        testDto.setId(promotionId);
        testDto.setShopId(shopId);
        testDto.setLabel("10% Off Everything");
        testDto.setDiscountType(DiscountType.PERCENTAGE);
        testDto.setDiscountPercent(10);
        testDto.setCategory("All");
        testDto.setActive(true);

        // Create percentage request
        percentageRequest = new CreatePromotionRequest();
        percentageRequest.setLabel("10% Off Everything");
        percentageRequest.setDiscountType(DiscountType.PERCENTAGE);
        percentageRequest.setDiscountPercent(10);
        percentageRequest.setCategory("All");
        percentageRequest.setShopId(shopId);
        percentageRequest.setValidFrom(OffsetDateTime.now().minusDays(1));
        percentageRequest.setValidUntil(OffsetDateTime.now().plusDays(30));

        // Create flat amount request
        flatAmountRequest = new CreatePromotionRequest();
        flatAmountRequest.setLabel("500p Off Orders");
        flatAmountRequest.setDiscountType(DiscountType.FLAT_AMOUNT);
        flatAmountRequest.setDiscountAmountPennies(500);
        flatAmountRequest.setShopId(shopId);
        flatAmountRequest.setValidFrom(OffsetDateTime.now().minusDays(1));
        flatAmountRequest.setValidUntil(OffsetDateTime.now().plusDays(30));

        // Mock mapper behavior
        lenient().when(promotionMapper.toDto(any(ShopPromotion.class))).thenAnswer(invocation -> {
            ShopPromotion entity = invocation.getArgument(0);
            PromotionDto dto = new PromotionDto();
            dto.setId(entity.getId());
            dto.setShopId(entity.getShopId());
            dto.setLabel(entity.getLabel());
            dto.setDiscountType(entity.getDiscountType());
            dto.setDiscountPercent(entity.getDiscountPercent());
            dto.setDiscountAmountPennies(entity.getDiscountAmountPennies());
            dto.setCategory(entity.getCategory());
            dto.setValidFrom(entity.getValidFrom());
            dto.setValidUntil(entity.getValidUntil());
            dto.setActive(entity.getActive());
            dto.setCreatedAt(entity.getCreatedAt());
            return dto;
        });

        lenient().when(promotionMapper.toEntity(any(CreatePromotionRequest.class))).thenAnswer(invocation -> {
            CreatePromotionRequest req = invocation.getArgument(0);
            ShopPromotion entity = new ShopPromotion();
            entity.setShopId(req.getShopId());
            entity.setLabel(req.getLabel());
            entity.setDiscountType(req.getDiscountType());
            entity.setDiscountPercent(req.getDiscountPercent());
            entity.setDiscountAmountPennies(req.getDiscountAmountPennies());
            entity.setCategory(req.getCategory());
            entity.setValidFrom(req.getValidFrom());
            entity.setValidUntil(req.getValidUntil());
            entity.setActive(req.getActive());
            return entity;
        });

        lenient().doAnswer(invocation -> {
            CreatePromotionRequest req = invocation.getArgument(0);
            ShopPromotion entity = invocation.getArgument(1);
            entity.setLabel(req.getLabel());
            entity.setDiscountType(req.getDiscountType());
            entity.setDiscountPercent(req.getDiscountPercent());
            entity.setDiscountAmountPennies(req.getDiscountAmountPennies());
            entity.setCategory(req.getCategory());
            entity.setShopId(req.getShopId());
            entity.setValidFrom(req.getValidFrom());
            entity.setValidUntil(req.getValidUntil());
            entity.setActive(req.getActive());
            return null;
        }).when(promotionMapper).updateEntity(any(CreatePromotionRequest.class), any(ShopPromotion.class));
    }

    @Test
    @DisplayName("createPromotion - PERCENTAGE type sets discountPercent, discountAmountPennies is null")
    void createPromotion_percentage_setsDiscountPercent() {
        // Given
        when(promotionRepository.saveAndFlush(any(ShopPromotion.class))).thenAnswer(invocation -> {
            ShopPromotion entity = invocation.getArgument(0);
            setField(entity, "id", promotionId);
            return entity;
        });

        // When
        PromotionDto result = promotionService.createPromotion(percentageRequest);

        // Then
        assertNotNull(result);
        assertEquals(DiscountType.PERCENTAGE, result.getDiscountType());
        assertEquals(10, result.getDiscountPercent());
        assertNull(result.getDiscountAmountPennies());

        ArgumentCaptor<ShopPromotion> captor = ArgumentCaptor.forClass(ShopPromotion.class);
        verify(promotionRepository).saveAndFlush(captor.capture());
        assertEquals(tenantId, captor.getValue().getTenantId());
    }

    @Test
    @DisplayName("createPromotion - FLAT_AMOUNT type sets discountAmountPennies, discountPercent is null")
    void createPromotion_flatAmount_setsAmountPennies() {
        // Given
        when(promotionRepository.saveAndFlush(any(ShopPromotion.class))).thenAnswer(invocation -> {
            ShopPromotion entity = invocation.getArgument(0);
            setField(entity, "id", promotionId);
            return entity;
        });

        // When
        PromotionDto result = promotionService.createPromotion(flatAmountRequest);

        // Then
        assertNotNull(result);
        assertEquals(DiscountType.FLAT_AMOUNT, result.getDiscountType());
        assertEquals(500, result.getDiscountAmountPennies());
        assertNull(result.getDiscountPercent());

        ArgumentCaptor<ShopPromotion> captor = ArgumentCaptor.forClass(ShopPromotion.class);
        verify(promotionRepository).saveAndFlush(captor.capture());
        assertEquals(tenantId, captor.getValue().getTenantId());
    }

    @Test
    @DisplayName("getAllPromotions - returns mapped page")
    void getAllPromotions_returnsMappedPage() {
        // Given
        Pageable pageable = PageRequest.of(0, 20);
        Page<ShopPromotion> page = new PageImpl<>(List.of(testPromotion), pageable, 1);
        // FC-1 (QA-council, F-H1): the GROUP_ADMIN list path is now tenant-scoped at the
        // query (findByTenantId), not a bare findAll() that leaked cross-tenant rows through
        // the RLS storefront carve-out. The test's intent (GROUP_ADMIN list returns a mapped
        // page) is preserved — the mock/verify move to the tenant-scoped finder.
        when(promotionRepository.findByTenantId(tenantId, pageable)).thenReturn(page);

        // When
        Page<PromotionDto> result = promotionService.getAllPromotions(pageable);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(promotionId, result.getContent().get(0).getId());
        assertEquals("10% Off Everything", result.getContent().get(0).getLabel());
        verify(promotionRepository).findByTenantId(tenantId, pageable);
    }

    @Test
    @DisplayName("updatePromotion - updates entity and returns DTO")
    void updatePromotion_updatesEntity() {
        // Given
        CreatePromotionRequest updateRequest = new CreatePromotionRequest();
        updateRequest.setLabel("Updated Promo");
        updateRequest.setDiscountType(DiscountType.FLAT_AMOUNT);
        updateRequest.setDiscountAmountPennies(200);
        updateRequest.setShopId(shopId);
        updateRequest.setValidFrom(OffsetDateTime.now());
        updateRequest.setValidUntil(OffsetDateTime.now().plusDays(60));
        updateRequest.setActive(true);

        when(promotionRepository.findById(promotionId)).thenReturn(Optional.of(testPromotion));
        when(promotionRepository.saveAndFlush(any(ShopPromotion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        PromotionDto result = promotionService.updatePromotion(promotionId, updateRequest);

        // Then
        assertNotNull(result);
        assertEquals("Updated Promo", result.getLabel());
        assertEquals(DiscountType.FLAT_AMOUNT, result.getDiscountType());
        assertEquals(200, result.getDiscountAmountPennies());
        verify(promotionRepository).findById(promotionId);
        verify(promotionMapper).updateEntity(eq(updateRequest), eq(testPromotion));
        verify(promotionRepository).saveAndFlush(testPromotion);
    }

    @Test
    @DisplayName("updatePromotion - throws ResourceNotFoundException when ID not found")
    void updatePromotion_notFound_throwsException() {
        // Given
        UUID unknownId = UUID.randomUUID();
        when(promotionRepository.findById(unknownId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> promotionService.updatePromotion(unknownId, percentageRequest));

        assertTrue(exception.getMessage().contains("Promotion not found"));
        verify(promotionRepository).findById(unknownId);
        verify(promotionRepository, never()).saveAndFlush(any(ShopPromotion.class));
    }

    @Test
    @DisplayName("deletePromotion - deletes entity")
    void deletePromotion_deletesEntity() {
        // Given
        when(promotionRepository.findById(promotionId)).thenReturn(Optional.of(testPromotion));

        // When
        promotionService.deletePromotion(promotionId);

        // Then
        verify(promotionRepository).findById(promotionId);
        verify(promotionRepository).delete(testPromotion);
    }

    @Test
    @DisplayName("deletePromotion - throws ResourceNotFoundException when ID not found")
    void deletePromotion_notFound_throwsException() {
        // Given
        UUID unknownId = UUID.randomUUID();
        when(promotionRepository.findById(unknownId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> promotionService.deletePromotion(unknownId));

        assertTrue(exception.getMessage().contains("Promotion not found"));
        verify(promotionRepository).findById(unknownId);
        verify(promotionRepository, never()).delete(any(ShopPromotion.class));
    }
}
