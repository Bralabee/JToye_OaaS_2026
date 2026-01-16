package uk.jtoye.core.finance;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
import uk.jtoye.core.finance.dto.FinancialTransactionDto;

/**
 * MapStruct mapper for FinancialTransaction entity and DTOs.
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
public interface FinancialTransactionMapper {

    /**
     * Convert FinancialTransaction entity to FinancialTransactionDto.
     * Includes calculated VAT amount via expression.
     */
    @Mapping(target = "vatAmountPennies", expression = "java(transaction.calculateVatAmount())")
    @Mapping(target = "description", source = "reference")
    FinancialTransactionDto toDto(FinancialTransaction transaction);

    /**
     * Convert CreateTransactionRequest to FinancialTransaction entity.
     * Note: tenantId, id, and createdAt must be set manually in the service layer.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "reference", source = "description")
    FinancialTransaction toEntity(CreateTransactionRequest request);
}
