package uk.jtoye.core.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.jtoye.core.security.TenantContext;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerRepository customerRepository;

    public CustomerController(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @GetMapping
    public Page<CustomerDto> getAllCustomers(
            @PageableDefault(size = 100, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return customerRepository.findAll(pageable)
                .map(this::toDto);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerDto> getCustomerById(@PathVariable UUID id) {
        return customerRepository.findById(id)
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<CustomerDto> createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        Customer customer = new Customer();
        customer.setTenantId(TenantContext.get().orElseThrow());
        customer.setName(request.name());
        customer.setEmail(request.email());
        customer.setPhone(request.phone());
        customer.setAllergenRestrictions(request.allergenRestrictions() != null ? request.allergenRestrictions() : 0);
        customer.setUpdatedAt(OffsetDateTime.now());

        Customer saved = customerRepository.save(customer);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CustomerDto> updateCustomer(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCustomerRequest request) {
        return customerRepository.findById(id)
                .map(customer -> {
                    customer.setName(request.name());
                    customer.setEmail(request.email());
                    customer.setPhone(request.phone());
                    customer.setAllergenRestrictions(request.allergenRestrictions() != null ? request.allergenRestrictions() : 0);
                    customer.setUpdatedAt(OffsetDateTime.now());
                    return customerRepository.save(customer);
                })
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCustomer(@PathVariable UUID id) {
        if (customerRepository.existsById(id)) {
            customerRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private CustomerDto toDto(Customer customer) {
        return new CustomerDto(
                customer.getId(),
                customer.getTenantId(),
                customer.getName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getAllergenRestrictions(),
                customer.getCreatedAt(),
                customer.getUpdatedAt()
        );
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
