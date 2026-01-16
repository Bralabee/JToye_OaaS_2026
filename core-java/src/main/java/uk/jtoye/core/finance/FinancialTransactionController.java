package uk.jtoye.core.finance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
import uk.jtoye.core.finance.dto.FinancialTransactionDto;

import java.net.URI;
import java.util.UUID;

/**
 * REST controller for financial transaction management.
 * All endpoints require JWT authentication and are automatically tenant-scoped.
 *
 * Financial transactions are IMMUTABLE - no update or delete endpoints.
 */
@RestController
@RequestMapping("/financial-transactions")
@Tag(name = "Financial Transactions", description = "Financial transaction management endpoints (audit-compliant)")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class FinancialTransactionController {

    private final FinancialTransactionService financialTransactionService;

    public FinancialTransactionController(FinancialTransactionService financialTransactionService) {
        this.financialTransactionService = financialTransactionService;
    }

    @GetMapping
    @Operation(summary = "List financial transactions", description = "Returns a paginated list of financial transactions for the authenticated tenant. Sorted by creation time descending.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved transactions"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT")
    })
    public Page<FinancialTransactionDto> getAllTransactions(
            @Parameter(description = "Pagination parameters", hidden = true)
            @PageableDefault(size = 100, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        // RLS ensures we only see current tenant rows
        return financialTransactionService.getAllTransactions(pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get transaction by ID", description = "Returns a single financial transaction by ID for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Transaction found"),
            @ApiResponse(responseCode = "404", description = "Transaction not found")
    })
    public ResponseEntity<FinancialTransactionDto> getTransactionById(
            @Parameter(description = "Transaction ID") @PathVariable UUID id) {
        return financialTransactionService.getTransactionById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Create financial transaction", description = "Creates a new financial transaction. Requires amount and VAT rate. Transactions are immutable after creation.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Transaction created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing required fields")
    })
    public ResponseEntity<FinancialTransactionDto> createTransaction(
            @Parameter(description = "Transaction creation request") @Valid @RequestBody CreateTransactionRequest request) {
        FinancialTransactionDto dto = financialTransactionService.createTransaction(request);
        return ResponseEntity.created(URI.create("/financial-transactions/" + dto.id())).body(dto);
    }

    // NOTE: No PUT or DELETE endpoints - financial transactions are IMMUTABLE
    // This maintains audit trail integrity per compliance requirements
}

