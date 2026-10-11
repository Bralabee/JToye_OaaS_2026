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

    // 37-20: the original free text is exposed as legacyOpeningHours for the NEEDS_REVIEW notice (and stays
    // as openingHours, unchanged, for every existing reader). hoursSource, openingSchedule and the pause
    // fields map by name.
    @Mapping(target = "legacyOpeningHours", source = "openingHours")
    ShopDto toDto(Shop shop);

    // 37-20 (D-15/D-25): the schedule, its source and the pause are NEVER mapped from a request. ShopService
    // validates and writes the schedule (and only ever writes STRUCTURED); the pause has its own endpoint.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "openingSchedule", ignore = true)
    @Mapping(target = "hoursSource", ignore = true)
    @Mapping(target = "ordersPausedUntil", ignore = true)
    @Mapping(target = "ordersPausedIndefinitely", ignore = true)
    Shop toEntity(CreateShopRequest request);

    // QA-council BE-02: partial update — null/absent request fields must NOT
    // overwrite existing values. Without IGNORE, an edit nulled every field the
    // request omitted: it wiped logo_url/banner_url/description/opening_hours and
    // violated the NOT NULL `published` column (surfaced as a misleading 409).
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "openingSchedule", ignore = true)
    @Mapping(target = "hoursSource", ignore = true)
    @Mapping(target = "ordersPausedUntil", ignore = true)
    @Mapping(target = "ordersPausedIndefinitely", ignore = true)
    void updateEntity(CreateShopRequest request, @MappingTarget Shop shop);
}
