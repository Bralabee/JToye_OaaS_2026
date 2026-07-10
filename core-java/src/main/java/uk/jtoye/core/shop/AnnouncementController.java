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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uk.jtoye.core.shop.dto.AnnouncementDto;
import uk.jtoye.core.shop.dto.CreateAnnouncementRequest;

import java.net.URI;
import java.util.UUID;

/**
 * REST controller for announcement management.
 * All endpoints require JWT authentication and are automatically tenant-scoped via RLS.
 */
@RestController
@RequestMapping("/announcements")
@Tag(name = "Announcements", description = "Announcement management endpoints")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class AnnouncementController {
    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping
    @Operation(summary = "List announcements", description = "Returns a paginated list of announcements for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved announcements"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT")
    })
    public Page<AnnouncementDto> list(
            @Parameter(description = "Pagination parameters", hidden = true)
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return announcementService.getAllAnnouncements(pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get announcement by ID", description = "Returns a single announcement by ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Announcement found"),
            @ApiResponse(responseCode = "404", description = "Announcement not found")
    })
    public ResponseEntity<AnnouncementDto> getById(
            @Parameter(description = "Announcement ID") @PathVariable UUID id) {
        return announcementService.getAnnouncementById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Create announcement", description = "Creates a new announcement for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Announcement created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<AnnouncementDto> create(
            @Parameter(description = "Announcement creation request") @Valid @RequestBody CreateAnnouncementRequest request) {
        AnnouncementDto dto = announcementService.createAnnouncement(request);
        // issue #97 [P2-6]: inherit the WebConfig /api/v1-prefixed request path so Location resolves
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(dto.getId())
                .toUri();
        return ResponseEntity.created(location).body(dto);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update announcement", description = "Updates an existing announcement")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Announcement updated successfully"),
            @ApiResponse(responseCode = "404", description = "Announcement not found"),
            @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<AnnouncementDto> update(
            @Parameter(description = "Announcement ID") @PathVariable UUID id,
            @Parameter(description = "Announcement update request") @Valid @RequestBody CreateAnnouncementRequest request) {
        AnnouncementDto dto = announcementService.updateAnnouncement(id, request);
        return ResponseEntity.ok(dto);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete announcement", description = "Deletes an announcement")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Announcement deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Announcement not found")
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Announcement ID") @PathVariable UUID id) {
        announcementService.deleteAnnouncement(id);
        return ResponseEntity.noContent().build();
    }
}
