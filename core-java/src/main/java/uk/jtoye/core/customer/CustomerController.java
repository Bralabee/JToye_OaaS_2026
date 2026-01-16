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
import org.springframework.web.bind.annotation.*;
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

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
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
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Create customer", description = "Creates a new customer. Requires name and email (unique per tenant).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Customer created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing required fields"),
            @ApiResponse(responseCode = "409", description = "Customer email already exists for this tenant")
    })
    public ResponseEntity<CustomerDto> create(
            @Parameter(description = "Customer creation request") @Valid @RequestBody CreateCustomerRequest req) {
        CustomerDto dto = customerService.createCustomer(req);
        return ResponseEntity.created(URI.create("/customers/" + dto.id())).body(dto);
    }

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
        try {
            CustomerDto dto = customerService.updateCustomer(id, req);
            return ResponseEntity.ok(dto);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete customer", description = "Deletes a customer for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Customer deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Customer not found"),
            @ApiResponse(responseCode = "409", description = "Cannot delete customer with existing orders")
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Customer ID") @PathVariable UUID id) {
        try {
            customerService.deleteCustomer(id);
            return ResponseEntity.noContent().build();
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
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

    public record CreateCustomerRequest(
            @jakarta.validation.constraints.NotBlank String name,
            @jakarta.validation.constraints.Email @jakarta.validation.constraints.NotBlank String email,
            String phone,
            Integer allergenRestrictions
    ) {}

    public record UpdateCustomerRequest(
            @jakarta.validation.constraints.NotBlank String name,
            @jakarta.validation.constraints.Email @jakarta.validation.constraints.NotBlank String email,
            String phone,
            Integer allergenRestrictions
    ) {}
}
