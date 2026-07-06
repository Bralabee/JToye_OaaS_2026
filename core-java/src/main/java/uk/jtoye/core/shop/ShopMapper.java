package uk.jtoye.core.shop;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;

@Mapper(componentModel = "spring")
public interface ShopMapper {

    ShopDto toDto(Shop shop);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    Shop toEntity(CreateShopRequest request);

    // QA-council BE-02: partial update — null/absent request fields must NOT
    // overwrite existing values. Without IGNORE, an edit nulled every field the
    // request omitted: it wiped logo_url/banner_url/description/opening_hours and
    // violated the NOT NULL `published` column (surfaced as a misleading 409).
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    void updateEntity(CreateShopRequest request, @MappingTarget Shop shop);
}
