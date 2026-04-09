package uk.jtoye.core.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.dto.CreatePromotionRequest;
import uk.jtoye.core.shop.dto.PromotionDto;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class PromotionService {
    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

    private final ShopPromotionRepository promotionRepository;
    private final PromotionMapper promotionMapper;

    public PromotionService(ShopPromotionRepository promotionRepository, PromotionMapper promotionMapper) {
        this.promotionRepository = promotionRepository;
        this.promotionMapper = promotionMapper;
    }

    @Transactional(readOnly = true)
    public Page<PromotionDto> getAllPromotions(Pageable pageable) {
        log.debug("Fetching promotions with pagination: page {}, size {}",
                pageable.getPageNumber(), pageable.getPageSize());
        return promotionRepository.findAll(pageable)
                .map(promotionMapper::toDto);
    }

    @Transactional(readOnly = true)
    public Optional<PromotionDto> getPromotionById(UUID id) {
        log.debug("Fetching promotion by ID: {}", id);
        return promotionRepository.findById(id)
                .map(promotionMapper::toDto);
    }

    public PromotionDto createPromotion(CreatePromotionRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating promotion '{}' for tenant {}", request.getLabel(), tenantId);

        ShopPromotion entity = promotionMapper.toEntity(request);
        entity.setTenantId(tenantId);

        entity = promotionRepository.saveAndFlush(entity);

        log.info("Created promotion '{}' with ID {} for tenant {}", entity.getLabel(), entity.getId(), tenantId);

        return promotionMapper.toDto(entity);
    }

    public PromotionDto updatePromotion(UUID id, CreatePromotionRequest request) {
        log.debug("Updating promotion {}", id);

        ShopPromotion entity = promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found: " + id));

        promotionMapper.updateEntity(request, entity);

        entity = promotionRepository.saveAndFlush(entity);

        log.info("Updated promotion '{}' with ID {}", entity.getLabel(), entity.getId());

        return promotionMapper.toDto(entity);
    }

    public void deletePromotion(UUID id) {
        log.debug("Deleting promotion {}", id);

        ShopPromotion entity = promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found: " + id));

        promotionRepository.delete(entity);

        log.info("Deleted promotion '{}' with ID {}", entity.getLabel(), entity.getId());
    }
}
