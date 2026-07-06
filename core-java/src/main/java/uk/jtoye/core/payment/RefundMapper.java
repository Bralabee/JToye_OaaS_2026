package uk.jtoye.core.payment;

import org.mapstruct.Mapper;
import uk.jtoye.core.payment.dto.RefundDto;

import java.util.List;

/**
 * MapStruct mapper for {@link Refund} → {@link RefundDto}.
 *
 * <p>Mirrors {@code OrderMapper}'s {@code componentModel = "spring"} pattern —
 * generated implementation is auto-wired as a Spring bean.
 */
@Mapper(componentModel = "spring")
public interface RefundMapper {

    RefundDto toDto(Refund refund);

    List<RefundDto> toDtoList(List<Refund> refunds);
}
