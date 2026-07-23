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
import uk.jtoye.core.shop.dto.AnnouncementDto;
import uk.jtoye.core.shop.dto.CreateAnnouncementRequest;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AnnouncementService.
 * Tests service layer business logic with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

    @Mock
    private ShopAnnouncementRepository announcementRepository;

    @Mock
    private AnnouncementMapper announcementMapper;

    @Mock
    private ShopAccessService shopAccessService;

    @InjectMocks
    private AnnouncementService announcementService;

    private UUID tenantId;
    private UUID announcementId;
    private UUID shopId;
    private ShopAnnouncement testAnnouncement;
    private CreateAnnouncementRequest scheduledRequest;
    private CreateAnnouncementRequest unscheduledRequest;
    private AnnouncementDto testDto;

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
        announcementId = UUID.randomUUID();
        shopId = UUID.randomUUID();

        TenantContext.set(tenantId);

        // Phase 23 (VSA-02): run as a GROUP_ADMIN so require(...) is a no-op and the
        // read-scope list takes the full-tenant path (existing assertions unchanged).
        lenient().when(shopAccessService.isGroupAdmin()).thenReturn(true);

        // Create test announcement entity
        testAnnouncement = new ShopAnnouncement();
        setField(testAnnouncement, "id", announcementId);
        testAnnouncement.setTenantId(tenantId);
        testAnnouncement.setShopId(shopId);
        testAnnouncement.setTitle("Holiday Hours");
        testAnnouncement.setBody("We will be closed on Boxing Day.");
        testAnnouncement.setValidFrom(OffsetDateTime.now().minusDays(1));
        testAnnouncement.setValidUntil(OffsetDateTime.now().plusDays(30));
        testAnnouncement.setActive(true);

        // Create test DTO
        testDto = new AnnouncementDto();
        testDto.setId(announcementId);
        testDto.setShopId(shopId);
        testDto.setTitle("Holiday Hours");
        testDto.setBody("We will be closed on Boxing Day.");
        testDto.setActive(true);

        // Create scheduled request (with date bounds)
        scheduledRequest = new CreateAnnouncementRequest();
        scheduledRequest.setTitle("Holiday Hours");
        scheduledRequest.setBody("We will be closed on Boxing Day.");
        scheduledRequest.setShopId(shopId);
        scheduledRequest.setValidFrom(OffsetDateTime.now().minusDays(1));
        scheduledRequest.setValidUntil(OffsetDateTime.now().plusDays(30));
        scheduledRequest.setActive(true);

        // Create unscheduled request (no date bounds)
        unscheduledRequest = new CreateAnnouncementRequest();
        unscheduledRequest.setTitle("Welcome!");
        unscheduledRequest.setBody("Thanks for visiting our shop.");
        unscheduledRequest.setShopId(shopId);

        // Mock mapper behavior
        lenient().when(announcementMapper.toDto(any(ShopAnnouncement.class))).thenAnswer(invocation -> {
            ShopAnnouncement entity = invocation.getArgument(0);
            AnnouncementDto dto = new AnnouncementDto();
            dto.setId(entity.getId());
            dto.setShopId(entity.getShopId());
            dto.setTitle(entity.getTitle());
            dto.setBody(entity.getBody());
            dto.setValidFrom(entity.getValidFrom());
            dto.setValidUntil(entity.getValidUntil());
            dto.setActive(entity.getActive());
            dto.setCreatedAt(entity.getCreatedAt());
            return dto;
        });

        lenient().when(announcementMapper.toEntity(any(CreateAnnouncementRequest.class))).thenAnswer(invocation -> {
            CreateAnnouncementRequest req = invocation.getArgument(0);
            ShopAnnouncement entity = new ShopAnnouncement();
            entity.setShopId(req.getShopId());
            entity.setTitle(req.getTitle());
            entity.setBody(req.getBody());
            entity.setValidFrom(req.getValidFrom());
            entity.setValidUntil(req.getValidUntil());
            entity.setActive(req.getActive());
            return entity;
        });

        lenient().doAnswer(invocation -> {
            CreateAnnouncementRequest req = invocation.getArgument(0);
            ShopAnnouncement entity = invocation.getArgument(1);
            entity.setTitle(req.getTitle());
            entity.setBody(req.getBody());
            entity.setShopId(req.getShopId());
            entity.setValidFrom(req.getValidFrom());
            entity.setValidUntil(req.getValidUntil());
            entity.setActive(req.getActive());
            return null;
        }).when(announcementMapper).updateEntity(any(CreateAnnouncementRequest.class), any(ShopAnnouncement.class));
    }

    @Test
    @DisplayName("createAnnouncement - with scheduling persists validFrom and validUntil dates")
    void createAnnouncement_withScheduling() {
        // Given
        when(announcementRepository.saveAndFlush(any(ShopAnnouncement.class))).thenAnswer(invocation -> {
            ShopAnnouncement entity = invocation.getArgument(0);
            setField(entity, "id", announcementId);
            return entity;
        });

        // When
        AnnouncementDto result = announcementService.createAnnouncement(scheduledRequest);

        // Then
        assertNotNull(result);
        assertEquals("Holiday Hours", result.getTitle());
        assertNotNull(result.getValidFrom());
        assertNotNull(result.getValidUntil());

        ArgumentCaptor<ShopAnnouncement> captor = ArgumentCaptor.forClass(ShopAnnouncement.class);
        verify(announcementRepository).saveAndFlush(captor.capture());
        assertEquals(tenantId, captor.getValue().getTenantId());
        assertNotNull(captor.getValue().getValidFrom());
        assertNotNull(captor.getValue().getValidUntil());
    }

    @Test
    @DisplayName("createAnnouncement - without scheduling allows null dates")
    void createAnnouncement_withoutScheduling() {
        // Given
        when(announcementRepository.saveAndFlush(any(ShopAnnouncement.class))).thenAnswer(invocation -> {
            ShopAnnouncement entity = invocation.getArgument(0);
            setField(entity, "id", announcementId);
            return entity;
        });

        // When
        AnnouncementDto result = announcementService.createAnnouncement(unscheduledRequest);

        // Then
        assertNotNull(result);
        assertEquals("Welcome!", result.getTitle());
        assertNull(result.getValidFrom());
        assertNull(result.getValidUntil());
        assertTrue(result.getActive());

        ArgumentCaptor<ShopAnnouncement> captor = ArgumentCaptor.forClass(ShopAnnouncement.class);
        verify(announcementRepository).saveAndFlush(captor.capture());
        assertEquals(tenantId, captor.getValue().getTenantId());
    }

    @Test
    @DisplayName("getAllAnnouncements - returns mapped page")
    void getAllAnnouncements_returnsMappedPage() {
        // Given
        Pageable pageable = PageRequest.of(0, 20);
        Page<ShopAnnouncement> page = new PageImpl<>(List.of(testAnnouncement), pageable, 1);
        when(announcementRepository.findAll(pageable)).thenReturn(page);

        // When
        Page<AnnouncementDto> result = announcementService.getAllAnnouncements(pageable);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(announcementId, result.getContent().get(0).getId());
        assertEquals("Holiday Hours", result.getContent().get(0).getTitle());
        verify(announcementRepository).findAll(pageable);
    }

    @Test
    @DisplayName("updateAnnouncement - updates entity and returns DTO")
    void updateAnnouncement_updatesEntity() {
        // Given
        CreateAnnouncementRequest updateRequest = new CreateAnnouncementRequest();
        updateRequest.setTitle("Updated Announcement");
        updateRequest.setBody("New body text.");
        updateRequest.setShopId(shopId);
        updateRequest.setValidFrom(OffsetDateTime.now());
        updateRequest.setValidUntil(OffsetDateTime.now().plusDays(60));
        updateRequest.setActive(true);

        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(testAnnouncement));
        when(announcementRepository.saveAndFlush(any(ShopAnnouncement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        AnnouncementDto result = announcementService.updateAnnouncement(announcementId, updateRequest);

        // Then
        assertNotNull(result);
        assertEquals("Updated Announcement", result.getTitle());
        assertEquals("New body text.", result.getBody());
        verify(announcementRepository).findById(announcementId);
        verify(announcementMapper).updateEntity(eq(updateRequest), eq(testAnnouncement));
        verify(announcementRepository).saveAndFlush(testAnnouncement);
    }

    @Test
    @DisplayName("updateAnnouncement - throws ResourceNotFoundException when ID not found")
    void updateAnnouncement_notFound_throws() {
        // Given
        UUID unknownId = UUID.randomUUID();
        when(announcementRepository.findById(unknownId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> announcementService.updateAnnouncement(unknownId, scheduledRequest));

        assertTrue(exception.getMessage().contains("Announcement not found"));
        verify(announcementRepository).findById(unknownId);
        verify(announcementRepository, never()).saveAndFlush(any(ShopAnnouncement.class));
    }

    @Test
    @DisplayName("deleteAnnouncement - deletes entity")
    void deleteAnnouncement_deletesEntity() {
        // Given
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(testAnnouncement));

        // When
        announcementService.deleteAnnouncement(announcementId);

        // Then
        verify(announcementRepository).findById(announcementId);
        verify(announcementRepository).delete(testAnnouncement);
    }

    @Test
    @DisplayName("deleteAnnouncement - throws ResourceNotFoundException when ID not found")
    void deleteAnnouncement_notFound_throws() {
        // Given
        UUID unknownId = UUID.randomUUID();
        when(announcementRepository.findById(unknownId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class,
                () -> announcementService.deleteAnnouncement(unknownId));

        assertTrue(exception.getMessage().contains("Announcement not found"));
        verify(announcementRepository).findById(unknownId);
        verify(announcementRepository, never()).delete(any(ShopAnnouncement.class));
    }
}
