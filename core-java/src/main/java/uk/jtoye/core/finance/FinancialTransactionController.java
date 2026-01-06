package uk.jtoye.core.finance;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import uk.jtoye.core.security.TenantContext;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/financial-transactions")
@RequiredArgsConstructor
public class FinancialTransactionController {

    private final FinancialTransactionRepository financialTransactionRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public Page<FinancialTransactionDto> getAllTransactions(
            @PageableDefault(size = 100, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return financialTransactionRepository.findAll(pageable)
                .map(this::toDto);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<FinancialTransactionDto> getTransactionById(@PathVariable UUID id) {
        return financialTransactionRepository.findById(id)
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<FinancialTransactionDto> createTransaction(@Valid @RequestBody CreateTransactionRequest request) {
        FinancialTransaction transaction = new FinancialTransaction();
        transaction.setTenantId(TenantContext.get().orElseThrow());
        transaction.setAmountPennies(request.amountPennies());
        transaction.setVatRate(request.vatRate());
        transaction.setReference(request.description());

        FinancialTransaction saved = financialTransactionRepository.save(transaction);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
    }

    private FinancialTransactionDto toDto(FinancialTransaction transaction) {
        return new FinancialTransactionDto(
                transaction.getId(),
                transaction.getTenantId(),
                transaction.getAmountPennies(),
                transaction.getVatRate(),
                transaction.calculateVatAmount(),
                transaction.getReference(),
                transaction.getCreatedAt()
        );
    }

    public record FinancialTransactionDto(
            UUID id,
            UUID tenantId,
            Long amountPennies,
            VatRate vatRate,
            Long vatAmountPennies,
            String description,
            OffsetDateTime createdAt
    ) {}

    public record CreateTransactionRequest(
            @jakarta.validation.constraints.NotNull Long amountPennies,
            @jakarta.validation.constraints.NotNull VatRate vatRate,
            String description
    ) {}
}
