package uk.jtoye.core.shop;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import uk.jtoye.core.shop.dto.CreatePromotionRequest;
import uk.jtoye.core.shop.dto.PromotionDto;

@Mapper(componentModel = "spring")
public interface PromotionMapper {

    PromotionDto toDto(ShopPromotion entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    ShopPromotion toEntity(CreatePromotionRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    void updateEntity(CreatePromotionRequest request, @MappingTarget ShopPromotion entity);
}
