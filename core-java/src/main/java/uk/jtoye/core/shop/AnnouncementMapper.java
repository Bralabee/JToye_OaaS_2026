package uk.jtoye.core.shop;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import uk.jtoye.core.shop.dto.AnnouncementDto;
import uk.jtoye.core.shop.dto.CreateAnnouncementRequest;

@Mapper(componentModel = "spring")
public interface AnnouncementMapper {

    AnnouncementDto toDto(ShopAnnouncement entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    ShopAnnouncement toEntity(CreateAnnouncementRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    // The shop is not copied from the request: AnnouncementService authorises a changed shopId against the
    // target shop and assigns it explicitly.
    @Mapping(target = "shopId", ignore = true)
    void updateEntity(CreateAnnouncementRequest request, @MappingTarget ShopAnnouncement entity);
}
