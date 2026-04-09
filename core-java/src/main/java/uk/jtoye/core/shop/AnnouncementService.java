package uk.jtoye.core.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.dto.AnnouncementDto;
import uk.jtoye.core.shop.dto.CreateAnnouncementRequest;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class AnnouncementService {
    private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);

    private final ShopAnnouncementRepository announcementRepository;
    private final AnnouncementMapper announcementMapper;

    public AnnouncementService(ShopAnnouncementRepository announcementRepository, AnnouncementMapper announcementMapper) {
        this.announcementRepository = announcementRepository;
        this.announcementMapper = announcementMapper;
    }

    @Transactional(readOnly = true)
    public Page<AnnouncementDto> getAllAnnouncements(Pageable pageable) {
        log.debug("Fetching announcements with pagination: page {}, size {}",
                pageable.getPageNumber(), pageable.getPageSize());
        return announcementRepository.findAll(pageable)
                .map(announcementMapper::toDto);
    }

    @Transactional(readOnly = true)
    public Optional<AnnouncementDto> getAnnouncementById(UUID id) {
        log.debug("Fetching announcement by ID: {}", id);
        return announcementRepository.findById(id)
                .map(announcementMapper::toDto);
    }

    public AnnouncementDto createAnnouncement(CreateAnnouncementRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating announcement '{}' for tenant {}", request.getTitle(), tenantId);

        ShopAnnouncement entity = announcementMapper.toEntity(request);
        entity.setTenantId(tenantId);

        if (entity.getActive() == null) {
            entity.setActive(true);
        }

        entity = announcementRepository.saveAndFlush(entity);

        log.info("Created announcement '{}' with ID {} for tenant {}", entity.getTitle(), entity.getId(), tenantId);

        return announcementMapper.toDto(entity);
    }

    public AnnouncementDto updateAnnouncement(UUID id, CreateAnnouncementRequest request) {
        log.debug("Updating announcement {}", id);

        ShopAnnouncement entity = announcementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Announcement not found: " + id));

        announcementMapper.updateEntity(request, entity);

        entity = announcementRepository.saveAndFlush(entity);

        log.info("Updated announcement '{}' with ID {}", entity.getTitle(), entity.getId());

        return announcementMapper.toDto(entity);
    }

    public void deleteAnnouncement(UUID id) {
        log.debug("Deleting announcement {}", id);

        ShopAnnouncement entity = announcementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Announcement not found: " + id));

        announcementRepository.delete(entity);

        log.info("Deleted announcement '{}' with ID {}", entity.getTitle(), entity.getId());
    }
}
