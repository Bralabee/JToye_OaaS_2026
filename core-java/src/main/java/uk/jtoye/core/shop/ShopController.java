package uk.jtoye.core.shop;

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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import uk.jtoye.core.common.CurrentTenant;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/shops")
@Tag(name = "Shops", description = "Shop management endpoints")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class ShopController {
    private final ShopRepository repo;

    public ShopController(ShopRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "List shops", description = "Returns a paginated list of shops for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved shops"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT"),
            @ApiResponse(responseCode = "400", description = "Bad request - invalid tenant ID")
    })
    public Page<ShopDto> list(
            @Parameter(description = "Pagination parameters", hidden = true)
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return repo.findAll(pageable).map(this::toDto);
    }

    @PostMapping
    @Transactional
    @Operation(summary = "Create shop", description = "Creates a new shop for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Shop created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error or missing tenant"),
            @ApiResponse(responseCode = "409", description = "Shop name already exists for this tenant")
    })
    public ResponseEntity<ShopDto> create(
            @Parameter(description = "Shop creation request") @Valid @RequestBody CreateShopRequest req) {
        UUID tenantId = CurrentTenant.require();
        Shop s = new Shop();
        s.setTenantId(tenantId);
        s.setName(req.getName());
        s.setAddress(req.getAddress());
        // Use saveAndFlush so creation timestamp is populated for the response
        Shop saved = repo.saveAndFlush(s);
        return ResponseEntity.created(URI.create("/shops/" + saved.getId())).body(toDto(saved));
    }

    private ShopDto toDto(Shop s) {
        ShopDto dto = new ShopDto();
        dto.setId(s.getId());
        dto.setName(s.getName());
        dto.setAddress(s.getAddress());
        dto.setCreatedAt(s.getCreatedAt());
        return dto;
    }
}
