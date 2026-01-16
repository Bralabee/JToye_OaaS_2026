package uk.jtoye.core.customer;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uk.jtoye.core.customer.CustomerController.CreateCustomerRequest;
import uk.jtoye.core.customer.CustomerController.CustomerDto;
import uk.jtoye.core.customer.CustomerController.UpdateCustomerRequest;

/**
 * MapStruct mapper for Customer entity and DTOs.
 * Provides compile-time safe DTO mapping with automatic null checks.
 *
 * Benefits over manual mapping:
 * - Compile-time type safety (no reflection)
 * - 10-20% performance improvement
 * - Automatic null handling
 * - Easy custom mapping rules
 *
 * Note: componentModel = "spring" generates a Spring bean that can be injected.
 */
@Mapper(componentModel = "spring")
public interface CustomerMapper {

    /**
     * Convert Customer entity to CustomerDto.
     * MapStruct will automatically map all matching fields.
     */
    CustomerDto toDto(Customer customer);

    /**
     * Convert CreateCustomerRequest to Customer entity.
     * Note: tenantId, id, createdAt must be set manually in the service layer.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "notes", ignore = true)
    Customer toEntity(CreateCustomerRequest request);

    /**
     * Convert UpdateCustomerRequest to Customer entity for updates.
     * Note: id, tenantId, createdAt are managed by JPA/service layer.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "notes", ignore = true)
    Customer toEntity(UpdateCustomerRequest request);
}
