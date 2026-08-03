package uk.jtoye.core.customer;

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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uk.jtoye.core.common.idempotency.Idempotent;
import uk.jtoye.core.common.idempotency.IdempotencyOutcome;
import uk.jtoye.core.common.idempotency.IdempotencyService;
import uk.jtoye.core.exception.ResourceNotFoundException;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * REST controller for customer management.
 * All endpoints require JWT authentication and are automatically tenant-scoped.
 */
@RestController
@RequestMapping("/customers")
@Tag(name = "Customers", description = "Customer relationship management endpoints")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class CustomerController {
    private final CustomerService customerService;
    private final IdempotencyService idempotencyService;

    public CustomerController(CustomerService customerService, IdempotencyService idempotencyService) {
        this.customerService = customerService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping
    @Operation(summary = "List customers", description = "Returns a paginated list of customers for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved customers"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT")
    })
    public Page<CustomerDto> list(
            @Parameter(description = "Pagination parameters", hidden = true)
            @PageableDefault(size = 100, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        // RLS ensures we only see current tenant rows
        return customerService.getAllCustomers(pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get customer by ID", description = "Returns a single customer by ID for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Customer found"),
            @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<CustomerDto> getById(
            @Parameter(description = "Customer ID") @PathVariable UUID id) {
        return customerService.getCustomerById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
    }

    @PreAuthorize("hasAuthority('SCOPE_customers:write')")  // Phase 25 [AI-02]: new customers:write scope (D-02)
    @PostMapping
    @Idempotent(endpoint = "customers.create")
    @Operation(summary = "Create customer", description = "Creates a new customer. Requires name and email (unique per tenant). Supply an Idempotency-Key header to make a retried POST safe: a repeated key replays the original customer and never creates a duplicate.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Customer created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing required fields"),
            @ApiResponse(responseCode = "409", description = "Customer email already exists for this tenant")
    })
    public ResponseEntity<CustomerDto> create(
            @Parameter(description = "Customer creation request") @Valid @RequestBody CreateCustomerRequest req,
            // Hidden from springdoc: IdempotencyHeaderCustomizer advertises the rich
            // Idempotency-Key parameter (description + maxLength) off @Idempotent, so
            // documenting the raw @RequestHeader too would double-list the header.
            @Parameter(hidden = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        CustomerDto dto;
        int status;
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            dto = customerService.createCustomer(req);
            status = 201;
        } else {
            IdempotencyOutcome<CustomerDto> outcome = idempotencyService.execute(
                    "customers.create", idempotencyKey, req, CustomerDto.class,
                    () -> customerService.createCustomer(req));
            dto = outcome.value();
            status = outcome.status();
        }
        // issue #97 [P2-6]: inherit the WebConfig /api/v1-prefixed request path so Location resolves.
        // Rebuild from the returned (fresh or replayed) dto so the 201 Location resolves identically on replay.
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(dto.id())
                .toUri();
        return ResponseEntity.status(status).location(location).body(dto);
    }

    @PreAuthorize("hasAuthority('SCOPE_customers:write')")  // Phase 25 [CR-01]: gate all customer mutations on customers:write (AI-02 least-privilege)
    @PutMapping("/{id}")
    @Operation(summary = "Update customer", description = "Updates an existing customer for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Customer updated successfully"),
            @ApiResponse(responseCode = "404", description = "Customer not found"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<CustomerDto> update(
            @Parameter(description = "Customer ID") @PathVariable UUID id,
            @Parameter(description = "Customer update request") @Valid @RequestBody UpdateCustomerRequest req) {
        // issue #500: no local catch. ResourceNotFoundException reaches GlobalExceptionHandler,
        // which is the single place that builds the RFC 7807 body.
        return ResponseEntity.ok(customerService.updateCustomer(id, req));
    }

    @PreAuthorize("hasAuthority('SCOPE_customers:write')")  // Phase 25 [CR-01]: gate all customer mutations on customers:write (AI-02 least-privilege)
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete customer", description = "Deletes a customer for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Customer deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Customer not found"),
            @ApiResponse(responseCode = "409", description = "Cannot delete customer with existing orders")
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Customer ID") @PathVariable UUID id) {
        // issue #500: see update() above — the typed body is GlobalExceptionHandler's job.
        customerService.deleteCustomer(id);
        return ResponseEntity.noContent().build();
    }

    public record CustomerDto(
            UUID id,
            UUID tenantId,
            String name,
            String email,
            String phone,
            Integer allergenRestrictions,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    // QA BE-2: @Size caps match the DB varchar lengths (name/email 255, phone 50) so an
    // over-length value fails bean validation with a clear 400, instead of reaching the
    // DB and surfacing as a misleading 409 "Duplicate Entry" (the blanket
    // DataIntegrityViolation→409 mapping conflates length with uniqueness).
    public record CreateCustomerRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 255) String name,
            @jakarta.validation.constraints.Email @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 255) String email,
            @jakarta.validation.constraints.Size(max = 50) String phone,
            Integer allergenRestrictions
    ) {}

    public record UpdateCustomerRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 255) String name,
            @jakarta.validation.constraints.Email @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 255) String email,
            @jakarta.validation.constraints.Size(max = 50) String phone,
            Integer allergenRestrictions
    ) {}
}
